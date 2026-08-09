# Architecture de NEXUS

Ce document décrit l'architecture **courante** de NEXUS 0.2.0 sur `main`. Les ADR conservent l'historique ; l'état actif inclut Phase 6, hardening, provenance, supply-chain, Windows/Docker et consolidation post-audit.

## Mission

NEXUS transforme :

```text
repositories + projets enregistrés + requête + budget
```

en recherche classée ou en contexte minimal, pertinent, explicable et borné.

## Invariants

1. JVM d'exécution 21 ou supérieure ; bytecode/API ciblés Java 21.
2. `NexusApplication` est le composition root partagé par CLI, REST et MCP.
3. SQLite est canonique.
4. Lucene lexical/sémantique et intelligence externe sont dérivés/reconstructibles.
5. Toute lecture interactive dépendant d'un index exige un projet `READY`.
6. Les providers/importers externes sont optionnels et bornés par timeout wall-clock.
7. Le sémantique est désactivé par défaut.
8. Ranking, limites et budgets restent déterministes/explicables.
9. Le contexte fédéré conserve la provenance et ne propage pas implicitement instructions/skills/Git entre projets.
10. Une donnée dérivée n'est réutilisée que si sa compatibilité avec l'état canonique courant est démontrée.
11. Une seule mutation d'index par projet est active sur un `NEXUS_HOME` local, y compris entre processus.
12. Le snapshot canonique est revalidé avant publication ; une mutation concurrente détectée provoque un échec fail-closed.
13. Le graphe et le contexte fédéré sont bornés en coût de travail, pas seulement en taille du résultat final.
14. CLI, REST et MCP partagent une limite maximale commune des résultats.
15. Une exposition REST hors loopback est fail-closed sans token robuste, allowlist de racines et mode d'exposition explicite.

## Reactor Maven

```text
pom.xml                         parent/reactor 0.2.0
├── core/                       io.github.fturleque:nexus-context-engine
├── adapters/rest-quarkus/
├── adapters/mcp-java/
└── adapters/assistant-clients/
```

Les dépendances, BOM et plugins sont gouvernés depuis le parent. Jackson est aligné par dependency management cohérent.

## Composition applicative

```text
CLI ───────┐
REST ──────┼──> NexusApplication
MCP ───────┘        │
                    ├─ ProjectRepository / IndexRepository (SQLite)
                    ├─ ProjectIndexingService
                    ├─ SearchService
                    ├─ FederatedSearchService
                    ├─ DefaultContextBuilder
                    └─ FederatedContextService
```

## Indexation et autorité

```text
ProjectScanner
  │  ProjectPathGuard + SafeFileIO + limites
  ↓
analyses embarquées
  ↓
SQLite canonique + génération/fingerprint
  │
  ├─ SCIP opportuniste avec limites fichier/message
  ├─ JDT LS opt-in sous timeout
  └─ MINOS explicite
  ↓
Lucene lexical dérivé
  └─ Lucene sémantique dérivé si opt-in
```

Une mutation par projet est protégée par mutex JVM + `FileLock` OS. Le snapshot canonique est revalidé avant passage à `READY`. Un état persistant non-READY impose un rebuild complet à la reprise.

`index_generation` est un signal de cache et ne progresse pas pour un no-op effectif.

## Provenance des index dérivés

- changement SOURCE/TEST ⇒ invalidation des snapshots externes persistés concernés ;
- index sémantique ⇒ manifeste avec fingerprint canonique, provider, modèle, dimensions, profil de préparation et version de schéma ;
- provenance absente/incompatible ⇒ rebuild ;
- recherche sémantique incompatible refusée avant embedding de requête.

Voir [`index-provenance.md`](index-provenance.md).

## Frontière filesystem

`ProjectPathGuard` et `SafeFileIO` imposent :

- racine canonique ;
- refus des symlinks pour les lectures sensibles ;
- `NOFOLLOW_LINKS` sur le composant final ;
- revalidation de taille avant consommation.

La garantie portable n'est pas un sandbox absolu contre un acteur local hostile modifiant ancêtres/hard-links. Les filesystems réseau ne sont pas déclarés supportés pour la garantie `FileLock` sans qualification dédiée.

## Recherche et graphe

```text
LuceneFileSearchStrategy
SymbolSearchStrategy (SQLite borné)
SemanticSearchStrategy (opt-in + provenance guard)
       ↓
CandidateMerger
       ↓
GraphCandidateEnricher (projections/voisinages bornés)
GitRecencyCandidateEnricher
       ↓
ContextRanker / SemanticHybridContextRanker
       ↓
top-K borné
```

La recherche fédérée applique une sur-récupération locale contrôlée avant tri/diversification globale.

## Contexte

Le contexte mono-projet combine recherche classée, instructions natives, skills et Git sous budget strict.

Le contexte fédéré :

- reçoit une portée explicite ;
- partage un budget global ;
- utilise fair floor, déduplication et refill ;
- conserve la provenance projet ;
- applique une borne au travail préparatoire en plus du budget final.

## Surfaces

- CLI : mono-projet + recherche/contexte fédérés ;
- REST : API `/api/v1/`, health/readiness, métriques ;
- MCP STDIO : tools mono-projet et fédérés ;
- assistant-clients : intégrations Copilot, Claude, Codex et client MCP générique.

## Sécurité REST

Loopback reste la configuration sûre par défaut. Hors loopback, le démarrage exige :

- `NEXUS_REST_API_TOKEN` robuste — minimum 32 octets et entropie estimée ≥ 96 bits ;
- `NEXUS_REST_ALLOWED_PROJECT_ROOTS` non vide ;
- `NEXUS_REST_EXPOSURE_MODE=reverse-proxy-https|direct-https` ;
- `loopback-forward` uniquement avec `NEXUS_RUNTIME=docker` et publication hôte sur loopback.

## Distribution et supply-chain

NEXUS 0.2.0 fournit : Maven Wrapper, CLI, ZIP autonome, checksums, SBOM CycloneDX, notices tierces, Windows ZIP/EXE autonome et runtime Docker.

La baseline CI comprend : NEXUS CI, Windows Installer, Scale Benchmark, CodeQL, OSV delta + SBOM reactor agrégé, Docker Distribution avec Trivy/SBOM et attestations lors de la publication `main`.

## Qualification intégrée

PR #49 : head exact `4f04c1ad3ff5b41aa9d1892ade57ad62b90a43f9` — NEXUS CI, Scale Benchmark, Windows Installer, Docker Distribution, CodeQL et OSV-Scanner PASS ; merge `c1ff9ef03ef33097c0d51154e02c30109b0a46f1`.

PR #61 : head exact `ba91be044a600d2396e0939fc154848dc47f6310` — NEXUS CI, CodeQL et OSV-Scanner PASS ; merge `660ca9f07a23950d2a5284605531524372331bc5`.

Aucun workflow/configuration/status SonarCloud actif n'est défini dans la baseline courante.

## Choix volontairement non adoptés

Sans benchmark ou incident justifiant le coût : pas de Zoekt/OpenGrok/OpenSearch, index distribué, vector DB, FTS supplémentaire, cache Git persistant, lifecycle Lucene partagé plus complexe ni isolation processus systématique des providers externes.
