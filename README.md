# NEXUS Context Engine

> Moteur local d'intelligence de contexte pour projets logiciels : recherche hybride, ranking explicable et construction de contexte sous budget.

NEXUS n'est ni un chatbot, ni un LLM, ni un orchestrateur d'agents. Il se place entre les repositories et les consommateurs IA afin de sélectionner un contexte technique pertinent, borné et traçable.

## État courant

```text
repository   FTurleque/nexus-context-engine
main         Phase 6 intégrée
base         develop
work         hardening/post-phase6-audit
issue        #16 — Post-Phase 6 hardening
Java         runtime >=21 / release 21
version      0.2.0
Phase 1→6    livrées / intégrées
hardening    implémenté sur branche, validation en attente
CI hardening NON EXÉCUTÉE avant validation explicite
```

La Phase 6 a été fusionnée via la PR #15 et l'issue #13 est historique. Le cycle post-Phase 6 est volontairement développé à partir de `develop`. La branche `hardening/post-phase6-audit` ne doit pas être fusionnée ni qualifiée par GitHub Actions avant validation explicite.

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

## Hardening post-Phase 6

La branche de travail issue de l'audit renforce plusieurs frontières de production :

- **filesystem** : racine canonique et refus des liens symboliques sous le repository pour le scanner, les ignore files, les instructions/références et les Agent Skills ;
- **taille des fichiers** : revalidation du fichier réel et de sa taille avant hash ou lecture ;
- **concurrence** : single-flight par projet dans la JVM et verrou OS sous `NEXUS_HOME/locks` pour les processus partageant le même home ;
- **providers/importers** : enveloppe wall-clock commune via `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS`, interruption du worker et retour sans fermeture bloquante de l'executor ;
- **readiness** : liveness du processus, readiness du service et état READY des projets sont exposés comme notions distinctes ;
- **contexte fédéré** : fair floor initial, déduplication puis réutilisation globale du budget libéré ;
- **REST** : écoute loopback par défaut ; toute écoute non-loopback exige un Bearer token ;
- **cohérence API** : un UUID valide mais inconnu n'est plus réinterprété comme un nom de projet ;
- **ressources JVM** : les slots de locks locaux sont retirés lorsqu'ils ne sont plus utilisés.

Ces changements disposent de tests de régression dans la branche, mais **aucun résultat PASS de build/CI n'est revendiqué tant que la qualification n'a pas été autorisée et exécutée**.

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

Le ZIP contient `bin/nexus.cmd`, `bin/nexus` et `lib/nexus-cli.jar`. Maven n'est pas requis sur la machine cible ; une JVM Java 21 ou supérieure est nécessaire.

## Correctness et scale

- toute lecture dépendant d'un index exige un projet `READY` ;
- un état `FAILED`, `NOT_INDEXED` ou `INDEXING` persistant entraîne un rebuild complet au prochain index ;
- la façade de production n'accepte qu'une indexation active par projet, y compris entre plusieurs processus partageant le même `NEXUS_HOME` ;
- top-K fédéré sur-récupéré avant diversification ;
- symboles/usages filtrés côté SQLite avec limites ;
- graphe réutilisé tant que la génération canonique n'a pas changé ;
- taille de fichier bornée avant hash/lecture et fichiers symlinkés refusés ;
- providers **et importers** externes bornés par timeout ;
- MINOS valide contre les fichiers canoniques déjà indexés ;
- le contexte fédéré peut redistribuer son budget restant après fair floor et déduplication.

Les recherches SQLite utilisant des recherches de sous-chaîne restent un **watch item mesuré** : aucun FTS5, trigram ou moteur supplémentaire ne sera introduit sans benchmark montrant un bénéfice matériel sur les corpus cibles.

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

Une écoute non-loopback (`0.0.0.0`, adresse LAN, etc.) sans `NEXUS_REST_API_TOKEN` est refusée au démarrage. Le token peut également être fourni par la propriété JVM `-Dnexus.rest.api-token=...`.

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

La qualification Phase 6 historique reste décrite par :

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-phase-6.ps1
```

Pour le hardening post-Phase 6, les tests et contrôles ont été **préparés mais pas exécutés via CI**. La qualification exacte de la branche sera lancée uniquement après validation explicite, conformément au gate de l'issue #16.

## Documentation

- architecture : [`docs/architecture.md`](docs/architecture.md) ;
- implémentation : [`docs/developer/architecture-implementation.md`](docs/developer/architecture-implementation.md) ;
- CLI : [`docs/developer/cli.md`](docs/developer/cli.md) ;
- recherche : [`docs/developer/search-ranking.md`](docs/developer/search-ranking.md) ;
- contexte : [`docs/developer/context-building.md`](docs/developer/context-building.md) ;
- sémantique : [`docs/developer/semantic-search.md`](docs/developer/semantic-search.md) ;
- limites/watch items : [`docs/developer/current-limitations.md`](docs/developer/current-limitations.md) ;
- release/recovery : [`docs/developer/release-and-recovery.md`](docs/developer/release-and-recovery.md) ;
- roadmap : [`docs/roadmap.md`](docs/roadmap.md).

## Décisions conservées

SQLite reste canonique. Lucene reste dérivé. Aucun Zoekt/OpenGrok/OpenSearch, index distribué, vector DB, cache Git persistant, FTS supplémentaire ou lifecycle Lucene plus complexe n'est adopté sans mesure démontrant qu'il répond à un problème réel.
