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

## Vues

Aucune vue Mermaid n'est ajoutée : le design décrit sept corrections locales (renommage d'un type, formulations de documentation, un test de `PRAGMA`, une règle de langue, trois optimisations d'algorithme dans une seule classe chacune). Il ne décrit aucune structure entre composants ni aucun flux à plusieurs acteurs qu'un diagramme éclairerait mieux que le texte ; en dessiner un reviendrait à inventer.

## Traçabilité

Aucun scénario n'est couvert : le changement n'est pas réalisé. La colonne « Tâche » renvoie aux numéros de `tasks.md`.

| Exigence (`capacité › titre`) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| `operational-documentation-contracts › Le voisinage graphe est documenté comme une projection bornée` | Ancienne description réintroduite | D1, D2 | 1.1 | à produire par la tâche 1.1 — refus de « ProjectGraph cache » ajouté au contrat puis `bash .claude/scripts/contracts.sh` ; test non écrit |
| `operational-documentation-contracts › Le voisinage graphe est documenté comme une projection bornée` | Aucun type de graphe orphelin | D1 | 1.2 | à produire par la tâche 1.2 — la recherche `git grep` de la tâche 1.2 sans résultat, puis tests `GraphNeighborhoodProjection*`, `GraphCandidateEnricher*`, `SqliteGraphNeighborhoodProjectionTest` ; preuve manquante |
| `operational-documentation-contracts › Les limites courantes ne présentent pas comme futur un moteur livré` | Watch item obsolète | D2 | 2.1 | à produire par la tâche 2.1 — refus de « nouveau moteur FTS/trigram » ajouté au contrat puis `bash .claude/scripts/contracts.sh` ; test non écrit |
| `operational-documentation-contracts › L'instruction primaire des agents n'utilise que le wrapper Maven` | Commande Maven directe | D2 | 3.1 | à produire par la tâche 3.1 — refus d'une commande Maven hors wrapper dans `AGENTS.md` ajouté au contrat ; test non écrit |
| `operational-documentation-contracts › L'instruction primaire des agents n'utilise que le wrapper Maven` | Contexte construit par NEXUS sur son propre dépôt | D2 | 3.1 | à produire par la tâche 3.1 — `pwsh -NoProfile -File scripts/self-smoke.ps1` (étape 9 : `AGENTS.md` retenu) ; preuve manquante |
| `query-path-performance › La troncature de fragment ne reconstruit pas le préfixe à chaque ligne` | Équivalence de sortie | D5 | 6.1, 6.2 | à produire par la tâche 6.2 — test comparant dichotomie et boucle linéaire, `./mvnw -B -ntp -pl core test -Dtest='BudgetedContextSelector*' ...` ; test non écrit |
| `query-path-performance › La troncature de fragment ne reconstruit pas le préfixe à chaque ligne` | Gros fragment | D5 | 6.1 | à produire par la tâche 6.1 — même commande ; aucune tâche ne cite de test du nombre d'appels de l'estimateur ; test non écrit |
| `query-path-performance › Le voisinage sortant est paginé par clé` | Nombreux imports non résolus | D6 | 7.1 | à produire par la tâche 7.1 — `./mvnw -B -ntp -pl core test -Dtest='SqliteGraphNeighborhoodProjectionTest,SqliteGraphQueryPlanTest' ...` avec un cas de plusieurs pages ; test non écrit |
| `query-path-performance › Le voisinage sortant est paginé par clé` | Plan de requête | D6 | 7.1 | à produire par la tâche 7.1 — `SqliteGraphQueryPlanTest` (test existant) doit rester vert ; aucune assertion nouvelle citée |
| `query-path-performance › Les motifs ne sont pas compilés à chaque appel` | Chemin avec lettre de lecteur | D7 | 8.1 | à produire par la tâche 8.1 — `./mvnw -B -ntp -pl core test -Dtest=RepositoryPathTest ...` ; `RepositoryPathTest` existe, cas à confirmer |
| `query-path-performance › Les motifs ne sont pas compilés à chaque appel` | Même glob évalué deux fois | D7 | 8.2 | à produire par la tâche 8.2 — test de borne et de réutilisation du cache ; test non écrit |
| `sqlite-journal-policy › Les bases canoniques utilisent le journal rollback` | Lecture du mode de journal | D3 | 4.2 | à produire par la tâche 4.2 — assertion `PRAGMA journal_mode` = `delete` dans `SqliteDatabaseHardeningTest` ; test non écrit |
| `sqlite-journal-policy › Les bases canoniques utilisent le journal rollback` | Changement de mode sans décision | D3 | 4.1, 4.2 | à produire par la tâche 4.2 — message de l'assertion citant l'ADR de la tâche 4.1 ; test non écrit |
| `user-facing-error-language › Les erreurs provoquées par l'utilisateur sont en français` | Requête vide | D4 | 5.2, 5.3 | à produire par la tâche 5.3 — `./mvnw -B -ntp -pl core test -Dtest='FederatedContextService*,OllamaEmbeddingProvider*' ...` ; test non écrit |
| `user-facing-error-language › Les erreurs provoquées par l'utilisateur sont en français` | Configuration Ollama hors bornes | D4 | 5.2, 5.3 | à produire par la tâche 5.3 — même commande ; test non écrit |
| `user-facing-error-language › Les erreurs provoquées par l'utilisateur sont en français` | Règle écrite | D4 | 5.1 | à produire par la tâche 5.1 — `bash .claude/scripts/contracts.sh` ; aucun contrat cité qui lise la règle ; test non écrit |

## Écarts et points à vérifier

- [Écart] `tasks.md:6` (tâche 1.2) cite `NexusApplication` parmi les appelants de `ProjectGraphBuilder` à adapter : au HEAD, `NexusApplication.java` ne le référence pas ; le seul appelant de production est `core/src/main/java/com/nexus/ranking/graph/GraphCandidateEnricher.java:28,33`, avec le test `core/src/test/java/com/nexus/ranking/graph/ProjectGraphBuilderProjectionTest.java:20,29,32`.
- [Existant vérifié] `ProjectGraph` n'est référencé que par son propre fichier (`core/src/main/java/com/nexus/ranking/graph/ProjectGraph.java:8-20`) ; `docs/architecture/adr/BE-0026-construire-un-graphe-minimal-de-fichiers-a-partir-des-imports.md:152,155` cite encore `ProjectGraphBuilder` et `ProjectGraph`.
- [Existant vérifié] Formulations visées par le contrat : `docs/developer/search-ranking.md:76` (titre « Graphe dérivé par génération ») et `:83` (« ProjectGraph cache generation N »), `docs/developer/current-limitations.md:149` (« nouveau moteur FTS/trigram »), `AGENTS.md:11` (commande Maven hors wrapper). `scripts/self-smoke.ps1:165-167` exige déjà la présence de `AGENTS.md` dans le contexte.
- [Existant vérifié] `SqliteDatabase` ne fixe que `foreign_keys` et `temp_store` (`core/src/main/java/com/nexus/persistence/sqlite/SqliteDatabase.java:101,105`) ; `PRAGMA journal_mode` n'apparaît qu'en test (`core/src/test/java/com/nexus/benchmark/ScaleRegressionBenchmarkTest.java:557`). Les coûts visés sont présents : `LIMIT ? OFFSET ?` (`core/src/main/java/com/nexus/persistence/sqlite/SqliteGraphQueries.java:37`), `slashPath.matches("^[A-Za-z]:.*")` (`core/src/main/java/com/nexus/paths/RepositoryPath.java:42`), `Pattern.compile` à chaque motif (`core/src/main/java/com/nexus/context/source/instruction/RepositoryGlobMatcher.java:14`).
- [Question] Chevauchement de capacité avec `sprint-10-conteneur-et-gouvernance` : les deux changements ajoutent des exigences à `operational-documentation-contracts` avec des `## Purpose` différents et modifient tous deux `scripts/release/test-operational-doc-contracts.sh`. La consolidation en une seule spec de référence n'est pas décidée ici.
- [Question] Chevauchement avec `sprint-07-casser-le-bloc-cyclique-du-coeur` : le renommage de D1 touche `ranking.graph` que le sprint 7 réorganise (le design de ce changement l'ordonne après le sprint 7).
- Limites : le décompte des 316 messages (dont environ un tiers en anglais) et la liste exacte des messages à traduire n'ont pas été relus ; D4 renvoie la traduction à la tâche 5.2.
