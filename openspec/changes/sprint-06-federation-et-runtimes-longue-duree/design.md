# Design

## Context

`FederatedSearchService.search` et `FederatedContextService.build` parcourent le scope fédéré (jusqu'à `FederatedScopePolicy.MAX_PROJECTS` = 100) dans une boucle synchrone et délèguent à la recherche ou au builder de contexte d'un seul projet. Tout ce que fait le chemin mono-projet est donc payé N fois :

- `SemanticSearchStrategy.search` appelle `embeddingProvider.embedQuery` pour chaque projet ; `OllamaEmbeddingProvider` ne mémoïse rien (timeout par requête 120 s).
- `SemanticSearchStrategy` appelle `semanticSearchIndex.isCompatible`, qui délègue à l'opération non cachée `LuceneSemanticSearchIndex.isCompatible` (`FSDirectory.open` puis `DirectoryReader.open` pour lire `getIndexCommit().getUserData()`). La sonde de readiness de `NexusApplication` appelle `searchIndex.isPresent` pour chaque projet, avec le même motif dans `LuceneSearchIndex`. Le cache de readers de l'ADR-0045 existe déjà mais n'est pas utilisé pour ces lectures.
- `GitRecencyCandidateEnricher` ouvre le dépôt, lit `MAX_COMMITS` = 50 commits et 50 diffs d'arbres à chaque recherche.
- `PersistentGitContextSourceProvider` garde `DEFAULT_CAPACITY` = 16 entrées LRU, une par projet : au-delà de 16 projets, un parcours cyclique évince chaque entrée avant sa réutilisation (ADR-0046 assume 16).
- `SqliteSymbolSearch` filtre `project_id` après le `MATCH` FTS global ; `idx_symbols_fuzzy_prefilter` (V008) n'a pas de colonne projet ; `SymbolSearchStrategy` interroge la requête puis chaque terme (`MAX_QUERY_TERMS` = 8).

L'ADR-0043 exclut tout parallélisme tant qu'une mesure ne le justifie pas. Le sprint garde cette règle : les gains viennent de la mutualisation, l'échéance borne la latence sans paralléliser.

## Goals / Non-Goals

**Goals:**
- Une requête fédérée sur N projets calcule un seul vecteur de requête.
- Aucune lecture de métadonnées Lucene n'ouvre de `DirectoryReader` hors du cache.
- Une échéance globale borne la fédération et la réponse nomme les projets non servis.
- Les caches Git servent une requête fédérée répétée sur 100 projets.
- Le coût d'une recherche de symboles dépend du projet ciblé.

**Non-Goals:**
- Paralléliser la boucle fédérée.
- Changer l'ordre ou le score des résultats servis.
- Modifier `NexusApplicationFactory.create`.
- Changer de fournisseur ou de modèle d'embedding.

## Decisions

**D1 — Découpage avant optimisation (`AUD-QUA-10`).** `FederatedContextService.build` est découpé en étapes privées nommées (validation de la requête et du scope, collecte par projet, sélection sous budget, métadonnées) et `ProjectIndexingService.indexLocked` en étapes (scan, diff, publication, providers externes, statut). Les tests existants (`FederatedContextServiceTest`, `FederatedContextBudgetScaleBenchmarkTest`, `ProjectIndexingServiceTest`) servent de filet ; aucun test n'est modifié dans cette étape. L'échéance (D3) s'insère ensuite dans l'étape « collecte par projet ».

**D2 — Vecteur de requête calculé par la fédération et transmis (`AUD-PRF-02`).** Le service fédéré calcule le vecteur une fois, sur la requête redactée (`SensitiveContentRedactor.redact`), et le transmet via le contexte de requête aux stratégies sémantiques par projet. La clé est (requête redactée normalisée, identifiant de modèle) ; la portée est la requête, sans cache inter-requêtes (pas d'état mémoire nouveau à borner). Alternative écartée : un cache LRU dans `OllamaEmbeddingProvider`, qui mélangerait des requêtes de clients différents et poserait une question de borne mémoire. Un échec du provider reste traité une fois et dégrade tous les projets avec le même diagnostic.

**D3 — Échéance globale vérifiée entre projets (`AUD-PRF-03`).** La fédération reçoit une échéance globale (borne par défaut fixée dans l'ADR à partir de la mesure de `FederatedContextBudgetScaleBenchmarkTest` et de la qualification sémantique). Elle est vérifiée avant chaque projet ; le temps restant plafonne les délais des appels bornables (embedding). Un projet commencé n'est pas interrompu. Les projets non commencés à l'échéance sont listés, dans l'ordre déterministe du scope, dans un diagnostic public de la réponse, émis par la projection publique unique (CLI, REST et MCP à parité). Alternative écartée : exécuteur avec timeout par projet, qui introduit le parallélisme exclu par l'ADR-0043. Nouvel ADR qui complète ADR-0043.

**D4 — Métadonnées Lucene lues sur le reader en cache (`AUD-PRF-04`).** `PersistentLuceneSemanticSearchIndex.isCompatible` et `PersistentLuceneSearchIndex.isPresent` lisent `userData` sur l'`IndexCommit` du reader acquis dans le cache de l'ADR-0045 (acquisition/libération symétriques). Sans reader en cache, la lecture passe par `SegmentInfos.readLatestCommit`, qui ne charge aucun segment. Aucun nouvel ADR : la décision applique l'ADR-0045.

**D5 — Cache de contexte Git dimensionné sur le scope (`AUD-PRF-06`).** `DEFAULT_CAPACITY` prend la valeur de `FederatedScopePolicy.MAX_PROJECTS`, avec une borne mémoire par entrée inchangée (les entrées portent des fragments déjà bornés). Les compteurs `CacheStats` existants sont exposés dans les métadonnées `explain`. La qualification `git-context-cache-qualification` est étendue à un scope de plus de 16 projets.

**D6 — Cache de récence par (dépôt, HEAD) (`AUD-PRF-07`).** `GitRecencyCandidateEnricher` mémorise, par (racine du dépôt, identifiant de HEAD), la liste ordonnée des chemins modifiés des `MAX_COMMITS` derniers commits, dans la borne `MAX_CUMULATIVE_CHANGED_PATHS` = 4096 existante, et applique ensuite le filtre de candidats. Un changement de HEAD invalide l'entrée ; la capacité suit D5. Cette décision rejoint le même ADR que D5, qui complète ADR-0046.

**D7 — Recherche de symboles scopée projet (`AUD-PRF-09`).** Une migration `V0NN` (skill `nexus-schema-migration`) ajoute un index de préfiltre fuzzy qui commence par la colonne projet (dénormalisée ou via `indexed_files`) ; `SqliteSymbolSearch` restreint le `MATCH` au projet avant le tri et interroge les termes en un lot (`json_each`) plutôt qu'une requête par terme. Le résultat (ordre et contenu) est identique à l'existant. Un benchmark peuple 25 puis 100 projets dans la même base.

## Dépendances envers d'autres sprints

- **Sprint 2, `AUD-DEP-19`** : la qualification sémantique réelle (`semantic-search-qualification.yml`) doit être stabilisée avant de mesurer D2 ; sans elle, le gain ne se prouve qu'en test unitaire avec un provider qui compte ses appels.
- **Sprint 5** : partage `ProjectIndexingService` (D1) et `LuceneSearchIndex` (D4). Le sprint 6 démarre après la fusion du sprint 5 dans `develop`, pas en parallèle. Le numéro de migration de D7 est le suivant disponible après les migrations du sprint 5.
- **Sprint 4, `AUD-QUA-09`** : le diagnostic des projets non servis (D3) passe par la projection publique unique livrée au sprint 4.

## Risks / Trade-offs

- [L'échéance rend la réponse dépendante du temps] → seuls le nombre de projets servis et le diagnostic varient ; l'ordre et les scores des projets servis restent déterministes. Les tests injectent une horloge et n'affirment rien sur la durée réelle.
- [Un projet commencé peut dépasser l'échéance] → assumé sans parallélisme ; le temps restant plafonne l'appel d'embedding, seul appel long bornable.
- [Capacité Git portée à 100 entrées] → mémoire bornée par la taille déjà plafonnée des fragments ; à confirmer par `PersistentGitContextSourceProviderBudgetTest`.
- [Cache de récence obsolète après un commit] → la clé contient HEAD ; un working tree modifié n'affecte pas la récence des commits.
- [Migration sur une base existante] → ajout d'index seulement, sans invalidation ; testé par un test d'upgrade.
