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

La découverte native partage un budget avant sélection de tokens :

```text
NEXUS_CONTEXT_DISCOVERY_MAX_VISITED_ENTRIES
NEXUS_CONTEXT_DISCOVERY_MAX_CANDIDATES
NEXUS_CONTEXT_DISCOVERY_MAX_BYTES
NEXUS_CONTEXT_DISCOVERY_MAX_MILLIS
```

Les défauts sont respectivement 100000 entrées, 5000 candidats, 32 MiB et 15 s. Un dépassement est fail-closed.

Sur POSIX, `NEXUS_HOME`, `indexes` et `locks` sont rendus privés (`0700`) et le fichier SQLite est durci en `0600`. Les chemins persistants NEXUS concernés sont refusés lorsqu'ils sont symboliques. Sur Windows/filesystems sans vue POSIX, les ACL natives ne sont pas réécrites destructivement.

### Recherche et fédération

Le scope fédéré est limité à 100 projets uniques. La cardinalité canonique est vérifiée avant résolution/readiness.

Les limites REST fédérées réutilisent les politiques centrales de résultats et de budget contexte. Une map `constraints` non vide est rejetée tant qu'aucune sémantique de contrainte n'est implémentée.

La recherche Lucene borne une requête analysée à **128 termes uniques** avant expansion sur les cinq champs de recherche pour rester sous le budget de clauses du moteur.

### Code Intelligence externe

Le framing JDT LS est borné avant allocation : message 16 MiB, headers 64 KiB, ligne de header 8 KiB et file entrante 256 messages maximum. Les tâches externes sont en plus limitées à **8 workers réellement actifs** à l'échelle JVM ; la saturation est rejetée explicitement.

### REST et observabilité

Le listener applicatif reste local-first sur `127.0.0.1:8080`. Health et métriques vivent sur un listener de management distinct, loopback-only :

```text
127.0.0.1:9000/q/health
127.0.0.1:9000/q/health/ready
127.0.0.1:9000/q/metrics
```

Les endpoints `/q/*` ne sont pas servis par le listener applicatif. Hors loopback, l'API métier échoue fermé si transport sécurisé effectif, token robuste ou allowlist de racines ne sont pas démontrés.

### Sémantique, Ollama et secrets

Le sémantique reste désactivé par défaut. Une URL Ollama distante doit utiliser HTTPS ; HTTP distant exige explicitement :

```text
NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true
```

Les credentials intégrés dans `NEXUS_OLLAMA_BASE_URL` sont refusés. Les secrets à forte confiance sont redigés avant embeddings et avant restitution des fragments de contexte. Le profil sémantique courant est `content-v2`, ce qui force le rebuild d'un ancien index incompatible.

### SQLite

SQLite reste l'autorité canonique. Depuis V005, `symbols` impose aussi au niveau base :

```text
start_line >= 1
end_line >= start_line
```

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
NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS
NEXUS_JDTLS_HOME
NEXUS_CONTEXT_DISCOVERY_MAX_VISITED_ENTRIES
NEXUS_CONTEXT_DISCOVERY_MAX_CANDIDATES
NEXUS_CONTEXT_DISCOVERY_MAX_BYTES
NEXUS_CONTEXT_DISCOVERY_MAX_MILLIS
NEXUS_SEMANTIC_PROVIDER
NEXUS_OLLAMA_BASE_URL
NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA
NEXUS_OLLAMA_EMBEDDING_MODEL
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
