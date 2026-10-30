# Design

## Context

`ProjectGraphBuilder` (`ranking.graph`) ne construit plus de graphe : il interroge le repository pour les seules arêtes qui touchent les chemins courants, bornées par `MAX_NEIGHBOR_EDGES`. `ProjectGraph` n'a aucune référence en code ni en test ; ADR-0026 (accepté) cite encore les deux noms. Le sprint 7 déplace des types entre `search` et `ranking.graph` ; ce sprint vient après et part de l'état qu'il laisse.

`SqliteDatabase` ne fixe aucun `PRAGMA journal_mode` : SQLite reste en journal rollback (`delete`). `docs/project/history/scale-regression-results.md` compare `DELETE` et WAL et conclut que WAL n'est pas adopté ; la transaction unique d'écriture est une décision d'ADR-0052.

La règle `.claude/rules/java-core.md` exige le français pour les messages destinés à l'utilisateur. Sur 316 messages littéraux de `IllegalArgumentException`, `IllegalStateException`, `IOException` et `UncheckedIOException` du code de production, environ un tiers est en anglais ; une partie remonte telle quelle par CLI, REST et MCP (message filtré par `safeMessage`).

`BudgetedContextSelector.truncate` reconstruit `préfixe + ligne + marqueur` et réestime à chaque ligne. `SqliteGraphQueries` relance `OUTGOING_GRAPH_SQL` (`ORDER BY r.source_ref, r.target_ref LIMIT ? OFFSET ?`) tant que les imports ne se résolvent pas. `RepositoryPath.fromProvider` appelle `String.matches` et `RepositoryGlobMatcher.matchesAny` compile chaque motif à chaque appel.

## Goals / Non-Goals

**Goals:**
- Documentation courante et instruction d'agent conformes au code, tenues par le contrat documentaire.
- Décisions graphe et journal consignées dans des ADR qui complètent les décisions existantes.
- Messages en français pour tout ce qu'un utilisateur peut provoquer.
- Coûts linéaires ou quasi linéaires sur les trois chemins relevés, à sortie identique.

**Non-Goals:**
- Adopter WAL ou changer la transaction unique.
- Traduire les préconditions internes ou les messages de bibliothèques tierces.
- Changer l'estimation de tokens, l'algorithme de sélection ou les bornes du voisinage.

## Decisions

**D1 — Voisinage projeté, renommé et consigné (`AUD-QUA-02`).** `ProjectGraph` est supprimé ; `ProjectGraphBuilder` devient `GraphNeighborhoodProjection` (même package, même API). Un nouvel ADR complète ADR-0026 : le voisinage est projeté à la demande depuis SQLite, borné, sans cache par génération. ADR-0026 n'est pas modifié. Alternative écartée : garder le nom, qui laisse croire à une matérialisation.

**D2 — Le contrat tient les formulations corrigées (`AUD-QUA-13`, `AUD-QUA-03`).** `test-operational-doc-contracts.sh` refuse « nouveau moteur FTS/trigram » dans `current-limitations.md`, « ProjectGraph cache » dans `search-ranking.md` et toute commande `mvn ` hors wrapper dans `AGENTS.md`. `AGENTS.md` reste indexé par NEXUS : sa reformulation est contrôlée par l'étape 9 de `scripts/self-smoke.ps1`.

**D3 — ADR du journal rollback (`AUD-PRF-10`).** Le nouvel ADR complète ADR-0052 : journal rollback conservé, mesures de `scale-regression-results.md`, condition de réévaluation de `scale-regression-benchmark.md`. Un test lit `PRAGMA journal_mode` sur une base ouverte par `SqliteDatabase` et attend `delete`, de sorte qu'un changement silencieux échoue en CI et renvoie à l'ADR.

**D4 — Frontière de langue écrite dans la règle (`AUD-QUA-11`).** Sont en français les messages atteignables depuis un argument CLI, une requête REST ou MCP, un fichier ou une variable de configuration (requêtes, budgets, limites, provider Ollama, chemins). Restent dans la langue du fichier les préconditions internes (`Objects.requireNonNull`, invariants entre classes du cœur). La règle `.claude/rules/java-core.md` l'écrit ; c'est une convention de code, pas une décision d'architecture : pas d'ADR. Les tests qui comparent un message traduit sont mis à jour dans le même commit.

**D5 — Troncature par dichotomie (`AUD-PRF-13`).** Les lignes sont découpées une fois ; la recherche du plus grand nombre de lignes dont `préfixe + marqueur` tient dans le budget se fait par dichotomie, chaque essai construisant un préfixe. Coût O(L log n) au lieu de O(L × n). Le résultat est identique à la boucle actuelle dès que l'estimation est croissante avec le préfixe, ce qui vaut pour `HeuristicTokenEstimator`, seule implémentation. Alternative écartée : cumul incrémental de points de code, plus rapide mais lié à la formule de l'estimateur.

**D6 — Pagination par clé (`AUD-PRF-16`).** La requête remplace `OFFSET` par `(r.source_ref, r.target_ref) > (?, ?)` sur l'ordre déjà imposé ; la page suivante part du dernier couple lu. Les couples sont uniques (`DISTINCT`), donc aucune ligne n'est sautée ni répétée. Le plan reste sur l'index `(project_id, kind, …)`, vérifié par `SqliteGraphQueryPlanTest`.

**D7 — Pas de compilation par appel (`AUD-PRF-17`).** `RepositoryPath.fromProvider` teste « lettre ASCII puis `:` » par comparaison de caractères. `RepositoryGlobMatcher` garde un cache borné (taille fixe, éviction déterministe, accès thread-safe) des motifs compilés, indexé par le glob source.

## Risks / Trade-offs

- [Le renommage entre en conflit avec le sprint 7] → ce sprint part de `develop` après la fusion du sprint 7 ; le renommage est un commit séparé.
- [Un test ou un client compare un message anglais] → recherche des littéraux concernés dans les tests des quatre modules avant traduction ; les messages publics REST/MCP sont listés dans la PR.
- [Estimateur futur non croissant] → D5 le documente dans la Javadoc de `TokenEstimator` ; un test de propriété compare dichotomie et boucle linéaire sur des fragments Unicode.
- [Cache de globs non borné par erreur] → taille maximale en constante et test d'éviction.
- [Reformulation d'`AGENTS.md` qui change le contexte retenu par le self-smoke] → étape 9 de `scripts/self-smoke.ps1` exécutée avant la PR.
