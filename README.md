# NEXUS Context Engine

> Moteur local d'intelligence de contexte pour projets logiciels : recherche hybride, ranking explicable et construction de contexte sous budget.

NEXUS n'est ni un chatbot, ni un LLM, ni un orchestrateur d'agents. Il se place entre les repositories et les consommateurs IA afin de sélectionner un contexte technique pertinent, borné et traçable.

## État courant

```text
repository    FTurleque/nexus-context-engine
visibility    public
main          Phase 6 + hardening + provenance + supply-chain + Windows/Docker intégrés
Java          runtime >=21 / release 21
version       0.2.0
Phase 1→6     livrées / intégrées
hardening     post-Phase 6 intégré via PR #18
provenance    externe + sémantique intégrée via PR #24
licence       propriétaire source-available via PR #25
supply-chain  CI/couverture/OSV/CodeQL intégrés via PR #28 puis renforcés via PR #49
windows       EXE installer autonome intégré via PR #41
wizard        Natif / Docker / Both intégré via PR #46
post-audit    P1/P2/P3 techniques intégrés via PR #49
```

La Phase 6 a été fusionnée via PR #15. Le hardening post-Phase 6 a été intégré via PR #18. La provenance des index a été renforcée via PR #24. La distribution Windows autonome et l'installateur EXE Inno Setup sans prérequis JVM ont été intégrés via PR #41. L'assistant de déploiement Natif / Docker / Both est intégré via PR #46.

La consolidation post-audit de l'issue #48 a été intégrée via PR #49 : cohérence d'indexation face aux mutations concurrentes, bornes de travail pour graphe et contexte fédéré, limites SCIP, limite commune des résultats, durcissement REST distant, configuration Windows/Docker, sécurité de l'image conteneur, readiness, génération d'index et déduplication des providers.

## Capacités

- indexation locale incrémentale avec détection fail-closed d'une mutation du repository pendant la construction du snapshot ;
- SQLite canonique et index Lucene reconstructibles ;
- JavaParser, Markdown et recherche lexicale polyglotte ;
- SCIP opportuniste avec limites dédiées de fichier/message, JDT LS opt-in et import MINOS explicite ;
- recherche hybride fichier/symbole/graphe/Git ;
- graphe projet projeté côté SQLite avec budgets de matérialisation ;
- recherche sémantique locale opt-in ;
- recherche fédérée multi-projet ;
- `ContextBundle` projet-local et contexte fédéré avec budget final **et coût de travail borné** ;
- limite maximale commune des résultats exposés par CLI, REST et MCP ;
- instructions AGENTS/Copilot/Claude/Gemini ;
- Agent Skills locaux + AI Skills Registry local ;
- contexte Git local borné ;
- CLI, REST Quarkus et MCP Java STDIO ;
- générateurs de configuration Copilot, Claude et Codex ;
- liveness/readiness REST et métriques ;
- distribution CLI autonome versionnée ;
- installateur Windows EXE autonome avec runtime Java embarqué ;
- runtime Docker avec contrôles CVE, SBOM et attestations de provenance sur publication `main`.

## Assistant de déploiement Windows

L'assistant intégré via la PR #46 (issue #45) propose :

```text
Natif Windows
Docker
Natif + Docker
```

Le profil recommandé installe les surfaces natives par défaut sans rendre REST obligatoire. Le profil personnalisé permet de choisir CLI, MCP STDIO et REST, ainsi que les paramètres de runtime.

La matrice assistants est :

```text
GitHub Copilot CLI
GitHub Copilot JetBrains
Claude CLI / Claude Code
Codex Desktop
Client MCP générique
```

MCP reste en transport **STDIO**. En Docker, les clients utilisent `docker exec -i` ; aucun port MCP HTTP n'est ajouté.

La recherche sémantique est désactivée par défaut. Si Ollama est activé explicitement, le setup peut télécharger l'installateur officiel uniquement après vérification Authenticode fail-closed du signataire attendu. Docker Desktop suit le même principe de téléchargement officiel et de signature vérifiée ; NEXUS n'accepte jamais la licence Docker à la place de l'utilisateur.

Voir :

- [`docs/user/windows-installation.md`](docs/user/windows-installation.md) ;
- [`docs/user/docker-installation.md`](docs/user/docker-installation.md) ;
- [`docs/user/deployment-wizard-template.md`](docs/user/deployment-wizard-template.md).

## Hardening et invariants

Les frontières de production actuellement garanties comprennent :

- **filesystem** : racine projet canonicalisée, refus des symlinks pour les lectures sensibles, `SafeFileIO` et `NOFOLLOW_LINKS` ;
- **taille** : revalidation de la taille réelle avant hash/lecture et politique SCIP dédiée avant allocation Protobuf ;
- **indexation** : snapshot cohérent ; une mutation canonique détectée pendant l'indexation fait échouer l'opération plutôt que de publier un état mixte ;
- **concurrence** : single-flight par projet dans la JVM et verrou OS par projet sous `NEXUS_HOME/locks` ;
- **providers/importers** : enveloppe wall-clock commune via `ExternalTaskRunner` ;
- **readiness** : liveness, readiness service et readiness projet séparées, y compris lorsqu'aucun projet n'est enregistré ;
- **graphe** : projections et voisinages bornés côté repository ;
- **contexte fédéré** : fair floor, déduplication, refill global et borne du travail préparatoire ;
- **résultats** : plafond commun CLI/REST/MCP ;
- **REST** : loopback sûr par défaut ; exposition distante fail-closed avec token robuste, allowlist de racines et mode d'exposition explicite ;
- **générations** : pas de bump `index_generation` sans changement effectif ;
- **providers persistés** : déduplication SQL et index dédiés.

Le support cible de `NEXUS_HOME` reste un filesystem local. Les garanties de `FileLock` sur un filesystem réseau ne sont pas revendiquées.

## Provenance et fraîcheur des index

Depuis PR #24, NEXUS ne réutilise pas de données dérivées dont la compatibilité avec l'état canonique n'est pas démontrée :

- changement SOURCE/TEST ⇒ invalidation des snapshots externes persistés concernés ;
- index sémantique ⇒ manifeste avec fingerprint canonique, provider, modèle, dimensions, profil de préparation et version de schéma ;
- provenance absente/incompatible ⇒ rebuild ;
- recherche sémantique obsolète refusée avant embedding de requête.

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

Le wrapper est épinglé sur Maven 3.9.11 et le projet cible Java 21.

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
target/sbom/bom.json
```

Le ZIP multiplateforme nécessite Java 21+. La distribution Windows x64 et le setup EXE embarquent leur runtime Java.

```text
target\dist\nexus-context-engine-0.2.0-windows-x64.zip
target\dist\nexus-context-engine-0.2.0-windows-x64.zip.sha256
target\dist\NEXUS-0.2.0-windows-x64-setup.exe
target\dist\NEXUS-0.2.0-windows-x64-setup.exe.sha256
```

## Configuration importante

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
NEXUS_REST_ALLOWED_PROJECT_ROOTS
NEXUS_REST_EXPOSURE_MODE
NEXUS_RUNTIME
```

`NEXUS_MAX_FILE_SIZE_BYTES` vaut 8 MiB par défaut. Le timeout global de code intelligence vaut 180 s. Les providers lourds et la sémantique sont désactivés par défaut.

### Sécurité REST

La configuration locale par défaut reste :

```text
quarkus.http.host=127.0.0.1
```

Sur loopback, aucun token n'est imposé par défaut.

Une écoute hors loopback est refusée sauf si **toutes** les conditions suivantes sont satisfaites :

1. `NEXUS_REST_API_TOKEN` est configuré et respecte la politique de robustesse (au moins 32 octets et entropie estimée minimale de 96 bits) ;
2. `NEXUS_REST_ALLOWED_PROJECT_ROOTS` contient au moins une racine existante autorisée ;
3. `NEXUS_REST_EXPOSURE_MODE` vaut `reverse-proxy-https` ou `direct-https` ;
4. le mode spécial `loopback-forward` n'est admis que lorsque `NEXUS_RUNTIME=docker`, pour un port publié côté hôte sur loopback.

Les racines administrables via REST sont canonicalisées avant comparaison.

## CI et supply-chain

Les gates actifs du dépôt comprennent :

- **NEXUS CI** : Windows Java 24, Linux Java 21, reactor, tests, JaCoCo et distribution/compliance ;
- **Windows Installer** : distribution x64, smoke install/execute/uninstall et setup production ;
- **Docker Distribution** : parité CLI/MCP/REST, round-trip `.env`, Trivy, SBOM image et gate HIGH/CRITICAL corrigibles ;
- **Scale Benchmark** : SQLite, graphe et contexte fédéré ;
- **CodeQL** ;
- **OSV-Scanner** : delta PR + scan bloquant du SBOM CycloneDX agrégé du reactor.

Sur publication de l'image depuis `main`, le workflow Docker publie l'image versionnée et `latest`, puis atteste la provenance et le SBOM sur le digest publié.

Aucun workflow ou status SonarCloud actif n'est actuellement défini dans le dépôt ; SonarCloud n'est donc pas un gate exécutable de la baseline courante.

Qualification de la consolidation post-audit PR #49 :

```text
QUALIFIED_HEAD=4f04c1ad3ff5b41aa9d1892ade57ad62b90a43f9
MERGE_SHA=c1ff9ef03ef33097c0d51154e02c30109b0a46f1
```

Sur ce HEAD, NEXUS CI, Windows Installer, Docker Distribution, Scale Benchmark, CodeQL et OSV-Scanner ont tous terminé en succès.

## Documentation

- architecture : [`docs/architecture.md`](docs/architecture.md) ;
- Arc42 : [`docs/architecture/README.md`](docs/architecture/README.md) ;
- provenance : [`docs/index-provenance.md`](docs/index-provenance.md) ;
- implémentation : [`docs/developer/architecture-implementation.md`](docs/developer/architecture-implementation.md) ;
- CLI : [`docs/developer/cli.md`](docs/developer/cli.md) ;
- recherche : [`docs/developer/search-ranking.md`](docs/developer/search-ranking.md) ;
- contexte : [`docs/developer/context-building.md`](docs/developer/context-building.md) ;
- sémantique : [`docs/developer/semantic-search.md`](docs/developer/semantic-search.md) ;
- limites : [`docs/developer/current-limitations.md`](docs/developer/current-limitations.md) ;
- release/recovery : [`docs/developer/release-and-recovery.md`](docs/developer/release-and-recovery.md) ;
- CI/supply-chain : [`docs/developer/ci-and-supply-chain.md`](docs/developer/ci-and-supply-chain.md) ;
- installation Windows : [`docs/user/windows-installation.md`](docs/user/windows-installation.md) ;
- Docker : [`docs/user/docker-installation.md`](docs/user/docker-installation.md) ;
- template wizard : [`docs/user/deployment-wizard-template.md`](docs/user/deployment-wizard-template.md) ;
- roadmap : [`docs/roadmap.md`](docs/roadmap.md).

## Licence

NEXUS Context Engine est un logiciel **propriétaire source-available**. Copyright © 2026 Fabrice Turleque. Tous droits réservés.

La visibilité publique du code ne transforme pas NEXUS en logiciel open source. Les conditions complètes figurent dans [`LICENSE`](LICENSE).

## Décisions conservées

SQLite reste canonique. Lucene reste dérivé. Aucun Zoekt/OpenGrok/OpenSearch, index distribué, vector DB, cache Git persistant, FTS supplémentaire ou lifecycle Lucene plus complexe n'est adopté sans mesure démontrant qu'il répond à un problème réel.
