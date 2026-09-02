# NEXUS Context Engine

> Moteur local d'intelligence de contexte pour projets logiciels : recherche hybride, ranking explicable et construction de contexte sous budget.

NEXUS n'est ni un chatbot, ni un LLM, ni un orchestrateur d'agents. Il se place entre les repositories et les consommateurs IA afin de sélectionner un contexte technique pertinent, borné et traçable.

## État courant

```text
repository  FTurleque/nexus-context-engine
visibility  public
develop     intégration et qualification
main        release
version     0.2.0
Java        runtime >=21 / release 21
Maven       3.9.16 via wrapper vérifié par SHA-512 versionné
```

La stratégie de branche est explicite : les changements sont intégrés et qualifiés sur `develop`, puis promus vers `main` pour les releases. La protection effective de `develop` est un contrôle GitHub de gouvernance distinct du code versionné.

## Capacités

- indexation locale incrémentale avec détection fail-closed des mutations concurrentes ;
- SQLite canonique et index Lucene reconstructibles ;
- JavaParser, Markdown, SCIP opportuniste borné, JDT LS opt-in et import MINOS ;
- recherche fichier/symbole/graphe/Git ;
- recherche sémantique locale opt-in ;
- fédération multi-projet avec limite de cardinalité appliquée avant résolution/readiness ;
- `ContextBundle` projet-local et fédéré avec budget final et travail préparatoire borné ;
- instructions AGENTS/Copilot/Claude/Gemini ;
- Agent Skills locaux + AI Skills Registry local ;
- contexte Git local à historique et diff bornés ;
- CLI, REST Quarkus et MCP Java STDIO ;
- distribution ZIP, installateur Windows self-contained et runtime Docker ;
- CodeQL, OSV, Trivy, SBOM, attestations et benchmarks de régression.

## Invariants de hardening

### Filesystem et stockage

`ProjectPathGuard` protège les lectures sensibles sous la racine canonique et refuse traversal, symlink final et symlink d'ancêtre. Les sources SCIP, skills et customisations durcies passent par cette frontière.

`NEXUS_MAX_FILE_SIZE_BYTES` vaut 8 MiB par défaut et possède un plafond dur de **256 MiB**. Les artefacts SCIP sont bornés séparément : 256 MiB par défaut / **1 GiB maximum** pour l'index complet, 16 MiB par défaut / **64 MiB maximum** par message Protobuf.

La découverte native partage un budget avant sélection de tokens :

```text
NEXUS_CONTEXT_DISCOVERY_MAX_VISITED_ENTRIES
NEXUS_CONTEXT_DISCOVERY_MAX_CANDIDATES
NEXUS_CONTEXT_DISCOVERY_MAX_BYTES
NEXUS_CONTEXT_DISCOVERY_MAX_MILLIS
```

Les défauts sont respectivement 100000 entrées, 5000 candidats, 32 MiB et 15 s. Un dépassement est fail-closed.

Sur POSIX, `NEXUS_HOME`, `indexes` et `locks` sont rendus privés (`0700`) et le fichier SQLite est durci en `0600`. Les chemins persistants NEXUS sont créés et revalidés composant par composant avec `NOFOLLOW_LINKS` : un symlink enfant précréé sous `indexes`, `locks` ou `jdtls-workspaces` est refusé. Sur Windows/filesystems sans vue POSIX, les ACL natives ne sont pas réécrites destructivement.

### Recherche et fédération

Le scope fédéré est limité à 100 projets uniques. La cardinalité canonique est vérifiée avant résolution/readiness.

Les limites REST fédérées réutilisent les politiques centrales de résultats et de budget contexte. Une map `constraints` non vide est rejetée tant qu'aucune sémantique de contrainte n'est implémentée.

La recherche Lucene borne une requête analysée à **128 termes uniques** avant expansion sur les cinq champs de recherche pour rester sous le budget de clauses du moteur.

### Code Intelligence externe et indexation

Le framing JDT LS est borné avant allocation : message 16 MiB, headers 64 KiB, ligne de header 8 KiB et file entrante 256 messages maximum. Les URI JDT externes non `file:` sont ignorées plutôt que converties en chemins locaux.

Les tâches externes sont limitées à **8 workers réellement actifs** à l'échelle JVM et leur timeout global est plafonné à **3 600 s**. Les mutations d'index file-backed disposent en plus d'un budget global non bloquant : `NEXUS_MAX_CONCURRENT_INDEXING` vaut **2** par défaut, accepte de 1 à 16 et rejette explicitement la surcharge au lieu d'empiler un travail sans borne.

L'import MINOS conserve une limite de transport de **128 MiB**, mais le JSON est traité en streaming : l'arbre complet n'est pas matérialisé, les symboles et relations sont validés un par un, et chaque catégorie est limitée à **500 000 faits**. La CLI lit stdin sous la même borne sans conserver un `byte[]` complet du payload en parallèle.

### REST et observabilité

Le listener applicatif reste local-first sur `127.0.0.1:8080`. Health et métriques vivent sur un listener de management distinct, loopback-only :

```text
127.0.0.1:9000/q/health
127.0.0.1:9000/q/health/ready
127.0.0.1:9000/q/metrics
```

Les endpoints `/q/*` ne sont pas servis par le listener applicatif. Hors loopback, l'API métier échoue fermé si transport sécurisé effectif, token robuste ou allowlist de racines ne sont pas démontrés.

Les corps HTTP applicatifs sont limités explicitement à **1 MiB** avant désérialisation. Les mappers d'erreur REST retournent des messages publics stables sans recopier les chemins ou diagnostics internes des exceptions ; une saturation du budget d'indexation est exposée en `503 Service Unavailable` avec `Retry-After`.

### Sémantique, Ollama et secrets

Le sémantique reste désactivé par défaut. Une URL Ollama distante doit utiliser HTTPS ; HTTP distant exige explicitement :

```text
NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true
```

Les credentials intégrés dans `NEXUS_OLLAMA_BASE_URL` sont refusés. Les secrets à forte confiance sont redigés avant embeddings et à la frontière finale de chaque `ContextBundle`, y compris pour instructions natives, skills et diff Git. Les assignments quotés contenant des espaces et les clés composées usuelles (`DB_PASSWORD`, `AWS_SECRET_ACCESS_KEY`, `MY_CLIENT_SECRET`, `database.password`) sont pris en charge. Les clés privées reconnues mais tronquées sont redigées jusqu'à la fin du contenu. Le profil sémantique courant est `content-v2`, ce qui force le rebuild d'un ancien index incompatible.

La configuration Ollama est bornée à **1 024 dimensions** et **600 s** de timeout maximum afin qu'une variable d'environnement ne puisse pas neutraliser les protections de ressources.

### SQLite

SQLite reste l'autorité canonique. Depuis V005, `symbols` impose aussi au niveau base :

```text
start_line >= 1
end_line >= start_line
```

Les snapshots de Code Intelligence externes sont persistés avec des `PreparedStatement` réutilisés et des batches bornés de 1 000 faits afin d'éviter un statement SQL par symbole/relation.

## Build

Sous Windows :

```powershell
.\mvnw.cmd clean install
```

Sous Linux/macOS :

```bash
sh ./mvnw clean install
```

Le wrapper utilise **Maven 3.9.16**. Son archive est vérifiée contre une ancre SHA-512 stockée dans `config/tool-integrity.properties`. JDT LS utilise de la même façon une ancre SHA-256 versionnée.

Pour l'installateur Windows, tout `ISCC.exe` réutilisé doit correspondre à la version Inno Setup épinglée et présenter une signature Authenticode valide de l'éditeur attendu ; sinon le bootstrap versionné est utilisé puis requalifié.

## CLI

Après build :

```powershell
java -jar .\target\nexus-context-engine-0.2.0-cli.jar --help
```

Commandes principales :

```text
project add
project list
index [--rebuild] [--deep-java]
minos-import
search
search-federated
context
context-federated
inspect
--help
--version
```

## Distribution

`clean install` produit notamment :

```text
target/nexus-context-engine-0.2.0-cli.jar
target/nexus-context-engine-0.2.0-cli.jar.sha256
target/distribution/nexus-context-engine-0.2.0.zip
target/distribution/nexus-context-engine-0.2.0.zip.sha256
target/licenses/THIRD_PARTY_NOTICES.txt
target/sbom/bom.json
```

La distribution Windows x64 ajoute le ZIP self-contained et le setup EXE avec leurs SHA-256.

## CI et supply-chain

Les gates comprennent :

- **NEXUS CI** : Windows Java 24, Linux Java 21, tests, distribution, JaCoCo, SBOM/notices, ancres d'intégrité et contrats documentaires ;
- **Windows Installer** ;
- **Docker Distribution** : smokes CLI/MCP/REST, Trivy, SBOM image et gate de vulnérabilités ;
- **Scale Benchmark** : SQLite, graphe, fédération et découverte native filesystem ;
- **Scanner Corpus Benchmark** ;
- **CodeQL** exact-head ;
- **OSV-Scanner** : delta PR + SBOM agrégé ;
- **SonarCloud** : Quality Gate sur les changements de PR.

Les Actions contrôlées sont épinglées par SHA immuable.

## Publication Docker

La release est déclenchée uniquement par un tag `vX.Y.Z` sur le HEAD exact de `main`.

Docker Distribution construit l'image une fois, la qualifie, puis l'exporte avec hash et ID. `release.yml` charge et vérifie cette **image exacte déjà qualifiée** ; il ne la reconstruit pas.

Les tags version et SHA sont immuables. Le préflight GHCR échoue fermé sur les erreurs ambiguës et n'autorise une reprise que lorsque le contenu existant est identique au contenu qualifié. `latest` est le seul pointeur mutable.

## Configuration importante

```text
NEXUS_HOME
NEXUS_MAX_FILE_SIZE_BYTES
NEXUS_MAX_SCIP_INDEX_BYTES
NEXUS_MAX_SCIP_MESSAGE_BYTES
NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS
NEXUS_MAX_CONCURRENT_INDEXING
NEXUS_JDTLS_HOME
NEXUS_CONTEXT_DISCOVERY_MAX_VISITED_ENTRIES
NEXUS_CONTEXT_DISCOVERY_MAX_CANDIDATES
NEXUS_CONTEXT_DISCOVERY_MAX_BYTES
NEXUS_CONTEXT_DISCOVERY_MAX_MILLIS
NEXUS_SEMANTIC_PROVIDER
NEXUS_OLLAMA_BASE_URL
NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA
NEXUS_OLLAMA_EMBEDDING_MODEL
NEXUS_OLLAMA_EMBEDDING_DIMENSIONS
NEXUS_OLLAMA_TIMEOUT_SECONDS
NEXUS_REST_API_TOKEN
NEXUS_REST_ALLOWED_PROJECT_ROOTS
NEXUS_REST_EXPOSURE_MODE
NEXUS_RUNTIME
```

## Documentation

- architecture : [`docs/architecture.md`](docs/architecture.md) ;
- CI/supply-chain : [`docs/developer/ci-and-supply-chain.md`](docs/developer/ci-and-supply-chain.md) ;
- release/recovery : [`docs/developer/release-and-recovery.md`](docs/developer/release-and-recovery.md) ;
- REST : [`docs/developer/rest-api.md`](docs/developer/rest-api.md) ;
- sémantique : [`docs/developer/semantic-search.md`](docs/developer/semantic-search.md) ;
- Code Intelligence/JDT : [`docs/developer/code-intelligence.md`](docs/developer/code-intelligence.md) ;
- gouvernance des branches : [`docs/developer/branch-governance.md`](docs/developer/branch-governance.md) ;
- limites courantes : [`docs/developer/current-limitations.md`](docs/developer/current-limitations.md) ;
- roadmap : [`docs/roadmap.md`](docs/roadmap.md).

## Licence

NEXUS Context Engine est un logiciel **propriétaire source-available**. Copyright © 2026 Fabrice Turleque. Tous droits réservés. Les conditions complètes figurent dans [`LICENSE`](LICENSE).
