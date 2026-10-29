# Proposal

## Why

Quatre écarts séparent la documentation courante et les décisions consignées de l'état du code : la section « Graphe dérivé par génération » décrit un cache que le code ne matérialise plus (et `ProjectGraph` n'a plus aucune référence) ; `current-limitations.md` présente comme futur le moteur FTS5 trigram livré par la migration V008 ; `AGENTS.md`, instruction primaire des agents, prescrit `mvn clean install` que les hooks du dépôt refusent ; le choix du journal rollback plutôt que WAL est mesuré mais ne figure dans aucun ADR. S'y ajoutent des messages d'exception en anglais remontés à l'utilisateur malgré la convention française, et trois coûts évitables sur des chemins chauds : troncature de fragment quadratique, pagination du voisinage graphe par `OFFSET`, regex compilées à chaque appel.

Ce changement porte le sprint 11 de l'audit du 17/10/2026 (`docs/audits/2026-10-17/sprints.md`). Ces corrections sont isolées, sans prérequis ni conséquence sur les autres lots ; le sprint exige des arbitrages écrits.

## What Changes

Tous les constats sont actifs sur `origin/develop` au 29/10/2026. Dans l'ordre du sprint :

- `AUD-QUA-02` — `docs/developer/search-ranking.md` décrit la projection bornée du voisinage (`findGraphNeighbors`, `MAX_NEIGHBOR_EDGES`) ; `ProjectGraph` est supprimé ; `ProjectGraphBuilder` est renommé `GraphNeighborhoodProjection`. Nouvel ADR (skill `nexus-adr`) qui complète ADR-0026.
- `AUD-QUA-13` — le watch item de `docs/developer/current-limitations.md` vise une évolution au-delà du FTS5 trigram de V008 ; le contrat documentaire refuse l'ancienne formulation.
- `AUD-QUA-03` — `AGENTS.md` prescrit `./mvnw clean install` (`.\mvnw.cmd` sous Windows) ; le contrat documentaire refuse `mvn clean install` hors wrapper.
- `AUD-PRF-10` — nouvel ADR (skill `nexus-adr`) qui complète ADR-0052 : journal rollback conservé, mesures, règle de réévaluation ; un test fixe le mode de journal.
- `AUD-QUA-11` — les messages d'exception atteignables depuis une entrée utilisateur ou de configuration sont en français ; la règle `.claude/rules/java-core.md` borne l'exception (préconditions internes).
- `AUD-PRF-13` — la troncature de fragment de `BudgetedContextSelector` cherche le nombre de lignes par dichotomie au lieu de reconstruire le préfixe à chaque ligne.
- `AUD-PRF-16` — le voisinage sortant de `SqliteGraphQueries` est paginé par clé `(source_ref, target_ref)`.
- `AUD-PRF-17` — `RepositoryPath.fromProvider` teste la lettre de lecteur sans regex ; `RepositoryGlobMatcher` réutilise les motifs compilés par un cache borné.

Aucun constat du sprint n'est déjà fermé.

## Capabilities

### New Capabilities
- `operational-documentation-contracts`: alignement de la documentation courante et des instructions d'agent sur le code, vérifié par `scripts/release/test-operational-doc-contracts.sh` (capability partagée avec `sprint-10-conteneur-et-gouvernance`).
- `sqlite-journal-policy`: mode de journal SQLite de production et sa traçabilité.
- `user-facing-error-language`: langue des messages d'erreur remontés à l'utilisateur.
- `query-path-performance`: coûts bornés sur les chemins de requête (troncature, pagination du voisinage graphe, motifs compilés).

### Modified Capabilities

(aucune : `openspec/specs/` est vide)

## Impact

- Code : `core/.../ranking/graph/ProjectGraph.java` (supprimé), `ProjectGraphBuilder.java` (renommé) et ses appelants, `core/.../context/BudgetedContextSelector.java`, `core/.../persistence/sqlite/SqliteGraphQueries.java`, `core/.../paths/RepositoryPath.java`, `core/.../context/source/instruction/RepositoryGlobMatcher.java`, messages d'exception des services atteints par CLI, REST et MCP (requêtes, budgets, configuration Ollama).
- Tests : `ProjectGraphBuilderProjectionTest` (renommé), `BudgetedContextSelector*Test`, `SqliteGraphNeighborhoodProjectionTest`, `SqliteGraphQueryPlanTest`, `RepositoryPathTest`, `InstructionDiscoverySupportTest`, tests qui comparent des messages traduits.
- Documentation : `AGENTS.md`, `docs/developer/search-ranking.md`, `docs/developer/current-limitations.md`, `docs/developer/scale-regression-results.md` (renvoi à l'ADR), `.claude/rules/java-core.md`, deux nouveaux ADR et l'index du registre.
- Contrats : `scripts/release/test-operational-doc-contracts.sh`.
- Comportement observable : messages d'erreur en français ; sorties de contexte, de recherche et de voisinage identiques. Aucune migration SQLite.
- Hors périmètre : adoption de WAL, traduction des préconditions internes (`Objects.requireNonNull`, invariants d'API interne).
