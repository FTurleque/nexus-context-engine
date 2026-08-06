# NEXUS Context Engine

> Moteur local d'intelligence de contexte pour projets logiciels : recherche hybride, ranking explicable et construction de contexte sous budget.

NEXUS n'est ni un chatbot, ni un LLM, ni un orchestrateur d'agents. Il se place entre les repositories et les consommateurs IA afin de sélectionner un contexte technique pertinent, borné et traçable.

## État courant

```text
repository   FTurleque/nexus-context-engine
visibility   public
main         Phase 6 + hardening + provenance des index intégrés
Java         runtime >=21 / release 21
version      0.2.0
Phase 1→6    livrées / intégrées
hardening    post-Phase 6 intégré via PR #18
provenance   externe + sémantique intégrée via PR #24
licence      propriétaire source-available via PR #25
```

La Phase 6 a été fusionnée via PR #15. Le hardening post-Phase 6 a été intégré via PR #18. Les deux P1 issus de l'audit de stabilisation (#19/#20) ont été qualifiés puis intégrés via PR #24.

## Capacités

- indexation locale incrémentale ;
- SQLite canonique et index Lucene reconstructibles ;
- JavaParser, Markdown et recherche lexicale polyglotte ;
- SCIP opportuniste, JDT LS opt-in et import MINOS explicite ;
- recherche hybride fichier/symbole/graphe/Git ;
- recherche sémantique locale opt-in ;
- recherche fédérée multi-projet ;
- `ContextBundle` projet-local et contexte fédéré avec budget global/provenance ;
- instructions AGENTS/Copilot/Claude/Gemini ;
- Agent Skills locaux + AI Skills Registry local ;
- contexte Git local borné ;
- CLI, REST Quarkus et MCP Java STDIO ;
- générateurs de configuration Copilot/Claude ;
- liveness/readiness REST et métriques ;
- distribution CLI autonome versionnée.

## Hardening intégré

Le hardening post-Phase 6 renforce plusieurs frontières de production :

- **filesystem** : racine canonique, refus des liens symboliques sous le repository, ouverture avec `NOFOLLOW_LINKS` via `SafeFileIO` — scanner, ignore files, instructions/références, Agent Skills, provider JDT LS, `ContextFragmentFactory` et importeur SCIP ;
- **taille des fichiers** : revalidation du fichier réel et de sa taille avant hash ou lecture ; flux bornés à `NEXUS_MAX_FILE_SIZE_BYTES` ;
- **concurrence** : single-flight par projet dans la JVM et verrou OS par projet sous `NEXUS_HOME/locks` ;
- **providers/importers** : enveloppe wall-clock commune via `ExternalTaskRunner`, interruption sans fermeture bloquante ;
- **readiness** : liveness et readiness distinctes ;
- **contexte fédéré** : fair floor déterministe, déduplication, réutilisation globale du budget libéré ;
- **REST** : écoute loopback par défaut, Bearer token requis hors-loopback ;
- **cohérence API** : UUID inconnu → erreur UUID directe ;
- **ressources JVM** : slots de locks locaux retirés à libération.

Le support cible de `NEXUS_HOME` reste un filesystem local. Les garanties de `FileLock` sur un filesystem réseau ne sont pas revendiquées.

## Provenance et fraîcheur des index

Depuis PR #24, NEXUS ne réutilise plus silencieusement de données dérivées dont la compatibilité avec l'état canonique n'est pas démontrée.

- lorsqu'un fichier `SOURCE`/`TEST` canonique change, les snapshots persistés des providers externes non embarqués sont invalidés, y compris si le provider n'est plus actif dans le runtime courant ;
- l'index sémantique Lucene persiste un manifeste contenant fingerprint canonique, provider, modèle, dimensions, profil de préparation du contenu et version de schéma ;
- une provenance sémantique absente ou incompatible force un rebuild ;
- une recherche sémantique obsolète est refusée avant même le calcul de l'embedding de requête.

Voir [`docs/index-provenance.md`](docs/index-provenance.md).

## Build reproductible

Sous Windows :

```powershell
.\mvnw.cmd clean install
```

Sous Linux/macOS :

```bash
sh ./mvnw clean install
```

Le wrapper est épinglé sur Maven 3.9.11 et vérifie le SHA-512 du téléchargement.

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

Les sélecteurs fédérés CLI sont des noms/UUID séparés par des virgules.

## Distribution sans clone

`clean install` produit :

```text
target/nexus-context-engine-0.2.0-cli.jar
target/nexus-context-engine-0.2.0-cli.jar.sha256
target/distribution/nexus-context-engine-0.2.0.zip
target/distribution/nexus-context-engine-0.2.0.zip.sha256
target/sbom/bom.json
```

Le ZIP contient `bin/nexus.cmd`, `bin/nexus`, `lib/nexus-cli.jar`, `README.md` et `LICENSE`. Maven n'est pas requis sur la machine cible ; une JVM Java 21 ou supérieure est nécessaire.

## Correctness et scale

- toute lecture dépendant d'un index exige un projet `READY` ;
- un état `FAILED`, `NOT_INDEXED` ou `INDEXING` persistant entraîne un rebuild complet au prochain index ;
- une seule mutation d'index par projet est active à la fois, y compris entre processus partageant un `NEXUS_HOME` local ;
- top-K fédéré sur-récupéré avant diversification ;
- symboles/usages filtrés côté SQLite avec limites ;
- graphe réutilisé tant que la génération canonique n'a pas changé ;
- taille de fichier bornée avant hash/lecture et fichiers symlinkés refusés ;
- providers **et importers** externes bornés par timeout ;
- MINOS valide contre les fichiers canoniques déjà indexés ;
- le contexte fédéré peut redistribuer son budget restant après fair floor et déduplication.

Les recherches SQLite utilisant des recherches de sous-chaîne restent un **watch item** suivi par #23 : aucun FTS5, trigram ou moteur supplémentaire ne sera introduit sans benchmark montrant un bénéfice matériel sur les corpus cibles.

## Configuration

Variables importantes :

```text
NEXUS_HOME
NEXUS_MAX_FILE_SIZE_BYTES
NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS
NEXUS_JDTLS_HOME
NEXUS_SEMANTIC_PROVIDER
NEXUS_SEMANTIC_RRF_WEIGHT
NEXUS_OLLAMA_BASE_URL
NEXUS_OLLAMA_EMBEDDING_MODEL
NEXUS_OLLAMA_EMBEDDING_DIMENSIONS
NEXUS_OLLAMA_TIMEOUT_SECONDS
NEXUS_REST_API_TOKEN
```

`NEXUS_MAX_FILE_SIZE_BYTES` vaut 8 MiB par défaut. Le timeout global de code intelligence vaut 180 s. Les providers lourds et la sémantique sont **désactivés par défaut**.

Pour activer explicitement Ollama :

```powershell
$env:NEXUS_SEMANTIC_PROVIDER = "ollama"
```

### Sécurité REST

La configuration par défaut reste :

```text
quarkus.http.host=127.0.0.1
```

Sur loopback, aucun token n'est imposé par défaut. Si `NEXUS_REST_API_TOKEN` est défini, les ressources REST JAX-RS exigent :

```text
Authorization: Bearer <token>
```

Une écoute non-loopback (`0.0.0.0`, adresse LAN, etc.) sans `NEXUS_REST_API_TOKEN` est refusée au démarrage. Le token peut également être fourni par `-Dnexus.rest.api-token=...`.

## Surfaces fédérées

CLI :

```text
search-federated <projet1,projet2,...> <requête>
context-federated <projet1,projet2,...> <requête>
```

REST :

```text
POST /api/v1/federated/search
POST /api/v1/federated/context
```

MCP :

```text
search_across_projects
build_context_across_projects
explain_context_across_projects
```

Le contexte fédéré applique un budget global, conserve la provenance projet, réduit la starvation, réutilise le budget rendu disponible et ne propage pas implicitement instructions/skills/Git d'un projet vers un autre.

## Qualification

La qualification Windows reste pilotée par :

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-phase-6.ps1
```

Preuve récente : PR #24, head exact `25c12b100b774a4ec3d69d221675bf31d8ebaa0c`, NEXUS CI run #15 :

- Windows / Java 24 : **PASS** ;
- script de qualification local : **PASS** ;
- Linux / Java 21 Maven reactor : **PASS** ;
- distribution Linux : **PASS**.

PR #24 est intégrée dans `main` via `c7a03479a78713b78ec2ddc477e1d07d400d8aba`.

## Documentation

- architecture : [`docs/architecture.md`](docs/architecture.md) ;
- Arc42 : [`docs/architecture/README.md`](docs/architecture/README.md) ;
- provenance des index : [`docs/index-provenance.md`](docs/index-provenance.md) ;
- implémentation : [`docs/developer/architecture-implementation.md`](docs/developer/architecture-implementation.md) ;
- CLI : [`docs/developer/cli.md`](docs/developer/cli.md) ;
- recherche : [`docs/developer/search-ranking.md`](docs/developer/search-ranking.md) ;
- contexte : [`docs/developer/context-building.md`](docs/developer/context-building.md) ;
- sémantique : [`docs/developer/semantic-search.md`](docs/developer/semantic-search.md) ;
- limites/watch items : [`docs/developer/current-limitations.md`](docs/developer/current-limitations.md) ;
- release/recovery : [`docs/developer/release-and-recovery.md`](docs/developer/release-and-recovery.md) ;
- roadmap : [`docs/roadmap.md`](docs/roadmap.md).

## Licence

NEXUS Context Engine est un logiciel **propriétaire source-available**. Copyright © 2026 Fabrice Turleque. Tous droits réservés.

La visibilité publique du code ne transforme pas NEXUS en logiciel open source. Aucun droit général d'utilisation, d'exécution, de modification, de redistribution, de sous-licence ou de commercialisation n'est accordé sans autorisation écrite, sous réserve des droits impératifs et des droits indépendamment accordés par les conditions applicables de GitHub.

Les conditions complètes figurent dans [`LICENSE`](LICENSE). Voir également [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Décisions conservées

SQLite reste canonique. Lucene reste dérivé. Aucun Zoekt/OpenGrok/OpenSearch, index distribué, vector DB, cache Git persistant, FTS supplémentaire ou lifecycle Lucene plus complexe n'est adopté sans mesure démontrant qu'il répond à un problème réel.
