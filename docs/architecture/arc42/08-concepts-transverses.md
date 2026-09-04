# Section 8 — Concepts transverses

## 8.1 Identité, readiness et fédération

- projets identifiés par UUID durables ;
- lectures indexées : projet `READY` requis ;
- portée fédérée : maximum 100 projets uniques ;
- cardinalité canonique validée avant résolution/readiness ;
- doublons dédupliqués avec ordre stable ;
- limites publiques de résultats et budget contexte réutilisent les politiques centrales.

Une map `constraints` non vide est refusée tant que la sémantique correspondante n'est pas implémentée.

## 8.2 Sécurité filesystem et stockage

La frontière de confiance est la racine canonique du projet :

- `ProjectPathGuard` refuse traversal, symlink final et symlink d'ancêtre ;
- `SafeFileIO` borne les lectures sensibles ;
- SCIP relit ses sources via la frontière canonique et vérifie ses bounds sans overflow ;
- instructions, références, skills et customisations projet durcies réutilisent la même politique ;
- le scanner ignore davantage de chemins sensibles (`.ssh`, `.aws`, `.gnupg`, `.kube`, credentials, keystores, etc.).

Pour le stockage NEXUS :

- `NEXUS_HOME`, `indexes`, `locks` → `0700` sur POSIX ;
- fichier SQLite → `0600` sur POSIX ;
- chemins persistants symboliques concernés → refusés ;
- Windows/filesystems non-POSIX → ACL natives préservées.

La protection Java portable ne constitue pas un sandbox absolu contre un acteur local capable de modifier agressivement le filesystem pendant l'opération.

## 8.3 Budget de découverte native

`ContextDiscoveryLimits` configure et `ContextDiscoveryBudget` consomme le budget partagé entre instructions, skills, customisations et Git avant le budget final de tokens.

| Dimension | Défaut |
|---|---:|
| entrées visitées | 100000 |
| candidats | 5000 |
| octets | 32 MiB |
| durée | 15 s |

Configuration invalide ou dépassement : échec fermé.

## 8.4 Git

Git reste local/read-only. Commits, chemins, historique et co-changements sont capés. Le diff working-tree utilise un sink à capacité fixe avant conversion/troncature.

## 8.5 Données et provenance

- SQLite canonique ;
- migrations forward-only avec checksum ;
- V004 invalide les anciens index aux plages impossibles ;
- V005 impose `start_line >= 1` et `end_line >= start_line` ;
- Lucene/snapshots externes dérivés ;
- provenance incompatible ⇒ rebuild/refus selon le type d'index.

## 8.6 Cohérence d'indexation et tâches externes

Une mutation par projet : mutex JVM + `FileLock` OS. Le snapshot canonique est revalidé avant `READY`. Une mutation concurrente détectée provoque un échec fail-closed.

Les intégrations externes utilisent `ExternalTaskRunner` : timeout, interruption et maximum **8 workers réellement actifs** simultanément. La capacité n'est rendue qu'à la fin réelle du worker ; une saturation est rejetée explicitement.

## 8.7 Code Intelligence défensive

JDT LS reste opt-in et communique en STDIO. `JdtJsonRpcFrameReader` impose avant allocation :

```text
message       <= 16 MiB
headers       <= 64 KiB
header line   <= 8 KiB
pending queue <= 256 messages
```

`Content-Length` invalide/contradictoire, payload/header tronqué ou saturation de queue provoquent un échec fermé de la session.

SCIP utilise des vérifications de bounds résistantes à l'overflow. JavaParser cible directement les catégories AST nécessaires.

## 8.8 Recherche lexicale et scale

La recherche Lucene borne les requêtes analysées à **128 termes uniques** avant expansion sur les cinq champs du `MultiFieldQueryParser`, afin de rester sous le budget de clauses du moteur.

Les recherches symboliques/relationnelles sont ciblées côté repository et les projections graphe sont bornées.

## 8.9 Sécurité REST et management

Listener applicatif par défaut : `127.0.0.1:8080`.

Listener management distinct : `127.0.0.1:9000` pour `/q/health`, `/q/health/ready` et `/q/metrics`.

`/q/*` n'est pas servi par le listener applicatif. Hors loopback :

- token robuste ;
- roots autorisées ;
- mode d'exposition explicite ;
- HTTP clair désactivé pour les modes HTTPS ;
- key material TLS effectif ;
- reverse proxy : forwarding + trusted proxies bornés ;
- listener management non publié.

`loopback-forward` est réservé à Docker avec publication hôte loopback.

## 8.10 Sémantique, Ollama et secrets

- sémantique opt-in ;
- Ollama HTTP autorisé sans opt-in uniquement sur loopback ;
- endpoint distant HTTPS obligatoire par défaut ;
- HTTP distant exige `NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true` ;
- userinfo/credentials intégrés à l'URI refusés ;
- redaction conservatrice des secrets avant embeddings et fragments de contexte ;
- profil sémantique `content-v2` pour invalider les vecteurs historiques incompatibles.

La redaction conserve les séparateurs de lignes des blocs multilignes afin de ne pas décaler les ranges source persistés.

## 8.11 Supply-chain

- Maven 3.9.16 via wrapper + SHA-512 versionné ;
- JDT LS fixe + SHA-256 versionné ;
- CodeQL exact-head ;
- OSV delta + SBOM agrégé ;
- Trivy + SBOM image ;
- Actions épinglées par SHA ;
- Docker build-once puis publication de l'image exacte qualifiée ;
- GHCR fail-closed/resumable pour même contenu.

## 8.12 Tests, benchmarks et documentation

La preuve courante appartient au SHA exact. Le Scale Benchmark couvre SQLite, graphe, fédération et découverte native 1 000 skills. NEXUS CI exécute les tests d'ancres et les contrats documentaires avant le reactor.

Les contrats documentaires doivent inclure les invariants NXA4 machine-vérifiables (management listener, Ollama policy, redaction, JDT bounds, Lucene cap, stockage POSIX) pour éviter qu'une future modification de code ne laisse les synthèses courantes obsolètes.

## 8.13 Gouvernance

`develop` est l'intégration, `main` la release. La protection effective de `develop` doit être appliquée côté GitHub ; les workflows seuls ne peuvent pas empêcher un push direct sur une branche non protégée.
