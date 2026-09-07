# Architecture d'implémentation — NEXUS 0.2.0

Ce chapitre décrit l'organisation concrète du code versionné après NXA3 + NXA4 et les remédiations d'audit du 7 septembre 2026. `develop` reçoit l'intégration qualifiée ; `main` reste la branche de release.

## Repository

```text
nexus-context-engine/
├── pom.xml
├── core/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/nexus/
│       ├── main/resources/db/migration/
│       ├── test/java/com/nexus/
│       └── test/resources/
├── adapters/rest-quarkus/
├── adapters/mcp-java/
├── adapters/assistant-clients/
├── distribution/
├── packaging/
├── scripts/
├── .github/workflows/
└── docs/
```

Le module `core` possède physiquement son layout Maven standard. Aucun `src/` applicatif n'existe à la racine et `core/pom.xml` ne redirige plus `sourceDirectory`, `testSourceDirectory`, resources ou test-resources vers le parent. Les artefacts publics et la distribution restent publiés sous les emplacements historiques de `target/` pour compatibilité.

Le parent gouverne Java 21, BOM/plugins, JaCoCo, SBOM et dépendances communes. Quarkus est en 3.39.1 et le SDK MCP en 2.0.1.

## Composition root

`NexusApplication` compose les ports communs :

```text
NexusPaths
  ↓
SqliteDatabase
  ├─ SqliteProjectRepository
  └─ SqliteIndexRepository
  ↓
ProjectRegistry
ProjectIndexingService
SearchService
FederatedSearchService
DefaultContextBuilder
FederatedContextService
```

CLI, REST et MCP délèguent à cette façade.

## Indexation et persistance

`ProjectIndexingService` orchestre mutex JVM + `FileLock`, scan borné, fingerprint canonique, analyses, SQLite, index dérivés, revalidation puis `READY`.

Une erreur de provider/importer ou une mutation canonique détectée pendant l'opération fait passer le projet à `FAILED` avant propagation de l'échec. Un provider explicitement demandé ne produit pas un succès dégradé silencieux.

V004 invalide les anciens index contenant des plages invalides ; V005 reconstruit `symbols` avec les `CHECK` :

```text
start_line >= 1
end_line >= start_line
```

Les migrations sont forward-only et enregistrées avec `script_sha256`.

## Stockage NEXUS

`NexusPaths.ensurePrivateStorage()` précède l'ouverture/migration de SQLite :

```text
home / indexes / locks  -> 0700 sur POSIX
nexus.db                -> 0600 sur POSIX
```

Les chemins persistants durcis concernés sont refusés lorsqu'ils sont symboliques. Sur Windows/filesystems sans vue POSIX, les ACL natives sont conservées et inspectées sur chaque chemin sensible.

Par défaut, un principal inattendu disposant de droits sensibles déclenche un diagnostic sans réécriture destructive. `NEXUS_REQUIRE_PRIVATE_STORAGE=true` active le mode strict : ACL sensible inattendue, échec d'inspection ou absence simultanée de vues POSIX/ACL ⇒ `IOException` fail-closed.

## Frontière filesystem

`ProjectPathGuard` est la frontière partagée pour les lectures projet durcies. SCIP, instructions/références, skills locaux/registry et customisations concernées refusent traversal, symlink final et symlink d'ancêtre.

`SafeFileIO` utilise `SecureDirectoryStream` lorsqu'il est exposé par le provider. Le fallback portable capture, avant ouverture, le chemin réel, le type et le `fileKey` de chaque composant ; après ouverture `NOFOLLOW_LINKS`, les mêmes informations sont revalidées et le channel est fermé si une substitution visible est détectée. Cette stratégie réduit fortement la fenêtre TOCTOU sans prétendre sandboxer un filesystem local hostile capable d'un échange/rétablissement extrêmement rapide ou de sémantiques de montage particulières.

Le scanner ajoute des exclusions sensibles (`.aws`, `.ssh`, `.gnupg`, `.kube`, credentials/keystores, etc.).

La découverte native utilise un `ContextDiscoveryBudget` commun à `DefaultContextBuilder` :

- entrées visitées ;
- candidats ;
- octets cumulés ;
- deadline.

Les limites sont consommées avant le travail coûteux lorsque possible et un dépassement échoue fermé.

## Code Intelligence

### JavaParser

Le parcours AST cible directement `TypeDeclaration`, méthodes, constructeurs et imports nécessaires ; il ne parcourt plus arbitrairement tous les `Node` pour retrouver les types.

### SCIP

SCIP reste opportuniste et confiné. Les vérifications de bounds protobuf utilisent une formulation résistante aux overflows (`length > data.length - position`) avant lecture. Les plafonds transport sont non extensibles : 256 MiB pour l'index et 16 MiB par message maximum.

### Politique commune de métadonnées

`CodeIntelligenceMetadataPolicy` borne les champs en octets UTF-8 puis refuse un snapshot au-delà de :

```text
100 000 symboles
250 000 relations
64 MiB de métadonnées UTF-8 cumulées
```

La validation intervient avant les maps de canonicalisation/déduplication secondaires.

### JDT LS

`JdtJsonRpcFrameReader` borne :

```text
MAX_MESSAGE_BYTES      16 MiB
MAX_HEADER_BYTES       64 KiB
MAX_HEADER_LINE_BYTES  8 KiB
MAX_PENDING_MESSAGES   256
```

La file entrante est bornée ; saturation/framing invalide détruit la session fail-closed.

Avant `Process.start()`, la racine canonique exacte du projet doit figurer dans `NEXUS_JDTLS_TRUSTED_PROJECT_ROOTS`. Une racine parente ne donne pas confiance implicitement à ses descendants et la variable de confiance n'est pas transmise au subprocess.

`ExternalTaskRunner` borne les intégrations externes à **8 workers réellement actifs**. La capacité n'est rendue qu'à la fin réelle du worker, même si l'appelant a déjà reçu un timeout. Le circuit-breaker linéarise validation+démarrage contre l'ouverture liée au timeout.

La composition de production courante n'exécute pas de provider Java tiers arbitraire : JDT LS est déjà un subprocess et SCIP un import local. Une isolation processus générique supplémentaire n'est donc pas introduite sans provider réel démontrant un comportement non coopératif.

## Recherche

`LuceneSearchIndex` utilise cinq champs de recherche et borne l'analyse à **128 termes uniques** avant expansion par `MultiFieldQueryParser`, afin de rester sous le budget par défaut de clauses Lucene.

Les recherches symboles/usages et les projections de graphe sont ciblées/bornées côté repository.

La comparaison ABBA `runtime-flags-qualification.yml` a mesuré le contrat de production face à `--add-modules=jdk.incubator.vector`. Le gain global observé n'était pas suffisamment robuste et le p95 graphe régressait légèrement ; le Vector API reste donc absent des launchers de production jusqu'à nouvelle mesure probante.

## Fédération

`FederatedScopePolicy` limite la portée à **100 projets uniques**. Les surfaces valident la cardinalité canonique avant `requireReadyProject` ou résolution équivalente.

Une portée valide est ensuite transmise à `FederatedSearchService`/`FederatedContextService` sous budget de travail et budget final.

Les limites REST fédérées réutilisent `ResultLimitPolicy` et `ContextBudgetPolicy` ; elles ne possèdent pas une limite parallèle plus permissive.

## Contexte et secrets

`ContextRequest` refuse une map `constraints` non vide tant que la fonctionnalité n'est pas implémentée.

`SensitiveContentRedactor` est appliqué :

- aux contenus avant embeddings sémantiques ;
- aux fragments de contexte retournés au client.

Les blocs privés multilignes conservent leurs séparateurs de lignes après redaction afin de ne pas déplacer les ranges source.

Le profil sémantique est `content-v2`, ce qui rend un index historique incompatible et force sa reconstruction.

## Ollama

`OllamaEndpointResolver` valide le transport avant adaptation runtime :

- HTTP loopback autorisé ;
- HTTPS distant autorisé ;
- HTTP distant refusé par défaut ;
- HTTP distant possible uniquement avec `NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true` ;
- URI avec userinfo/credentials refusée ;
- en Docker, un loopback validé peut être adapté vers `host.docker.internal`.

`OllamaEmbeddingProvider` borne la réponse `/api/embed` avant matérialisation JSON et valide dimension/valeurs finies.

`.github/workflows/semantic-search-qualification.yml` apporte la preuve réelle périodique/manuelle : téléchargement Ollama 0.33.3 depuis la release officielle, vérification SHA-256 repository-pinned, endpoint loopback, modèle `qwen3-embedding:0.6b`, `RealSemanticSearchBenchmarkTest`, floors de qualité/non-régression et artefacts de preuve.

## Git local

`LocalGitContextSourceProvider` borne commits récents, chemins, historique, co-changements et patches cibles. Le patch working-tree est écrit dans `BoundedOutput`, sink à capacité fixe, avant conversion/troncature à 6 000 caractères.

## REST

`NexusRestExposureGuard` et `NexusRestTransportPolicy` valident une exposition API non-loopback : token généré par CSPRNG et conforme au gate structurel, roots autorisées et transport effectif.

Le gate de token rejette les valeurs trop courtes ou à diversité de caractères manifestement insuffisante, mais ne prétend pas mesurer l'entropie cryptographique d'une chaîne statique.

Quarkus possède deux listeners distincts :

```text
application  127.0.0.1:8080
management   127.0.0.1:9000
```

Health/metrics `/q/*` sont servis par le management listener uniquement. Le reverse proxy métier ne doit pas le publier.

## MCP

Le module utilise le SDK MCP 2.0.1 en STDIO. `stdout` reste réservé au framing JSON-RPC. NEXUS conserve STDIO comme transport local supporté.

## Supply-chain

NEXUS CI vérifie les ancres Maven/JDT LS, l'ancre Ollama de qualification et les contrats documentaires avant le reactor. CodeQL qualifie l'exact head. OSV scanne delta PR + SBOM reactor. SonarCloud fournit le Quality Gate externe de PR.

Docker Distribution construit une image unique, exécute smokes/Trivy/SBOM puis exporte l'image exacte si la release la demande. `release.yml` ne rebuild pas cette image : il vérifie archive/ID et publie les tags immuables sous preflight GHCR fail-closed/resumable.

## Gouvernance

Le ruleset GitHub actif `Protect main & develop` satisfait NXA3-14 / #130 pour `develop` : pull request obligatoire, suppression/non-fast-forward interdits et checks permanents requis.

NEXUS est actuellement maintenu par **une seule personne**. Le modèle solo n'exige ni seconde approbation humaine ni resynchronisation stricte avec la base juste avant merge ; ces absences ne sont pas des hardenings ouverts. La qualification repose sur le SHA candidat et les gates automatisés applicables. Voir [`branch-governance.md`](branch-governance.md).
