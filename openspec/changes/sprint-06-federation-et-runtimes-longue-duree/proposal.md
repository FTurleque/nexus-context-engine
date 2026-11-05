# Proposal

## Why

Une requête fédérée (recherche ou contexte) sur un scope qui peut atteindre `FederatedScopePolicy.MAX_PROJECTS` = 100 projets répète par projet des coûts qui ne dépendent que de la requête ou de l'état Git : embedding de la requête par Ollama, ouverture d'un `DirectoryReader` Lucene pour lire des métadonnées, relecture de 50 commits et 50 diffs d'arbres, recherche de symboles sur l'index SQLite global. Le cache de contexte Git (16 entrées) est plus petit que le scope fédéré et ne produit aucun hit sur une requête répétée. La boucle synchrone par projet n'a pas d'échéance globale : un projet lent retarde toute la requête sans que la réponse nomme les projets non servis.

La méthode qui porte ce chemin, `FederatedContextService.build`, fait 168 lignes ; `ProjectIndexingService.indexLocked` en fait 111. Les découper d'abord rend les corrections de performance relisibles et testables par étape. Ce changement porte le sprint 6 de l'audit de code du 17/10/2026.

## What Changes

Constats traités, dans l'ordre interne du sprint (tous actifs sur `origin/develop` @ `17a00866`) :

- `AUD-QUA-10` — `FederatedContextService.build` et `ProjectIndexingService.indexLocked` sont découpés en étapes nommées (validation, collecte par projet, sélection, métadonnées), sans changement de comportement. `NexusApplicationFactory.create` reste une composition linéaire.
- `AUD-PRF-02` — le vecteur de requête est calculé une seule fois par requête fédérée (clé : requête redactée normalisée + identifiant de modèle) et transmis aux stratégies sémantiques de chaque projet.
- `AUD-PRF-03` — la fédération respecte une échéance globale ; les projets non servis avant l'échéance sont nommés dans un diagnostic public. Aucun parallélisme n'est introduit (ADR-0043).
- `AUD-PRF-04` — la compatibilité sémantique (`isCompatible`) et la présence lexicale (`isPresent`, sonde de readiness REST) lisent les métadonnées de commit via le reader mis en cache (ADR-0045), sans `DirectoryReader.open` par appel.
- `AUD-PRF-06` — la capacité du cache de contexte Git couvre le scope fédéré maximal, et ses hits/misses sont exposés dans les métadonnées `explain`.
- `AUD-PRF-07` — `GitRecencyCandidateEnricher` met en cache, par (dépôt, HEAD), les chemins modifiés des derniers commits, dans les bornes existantes.
- `AUD-PRF-09` — le préfiltre fuzzy et la recherche FTS trigram des symboles sont scopés par projet, et les termes d'une requête sont interrogés en un nombre borné de requêtes SQL.

Deux nouveaux ADR complètent ADR-0043 (échéance fédérée) et ADR-0046 (capacité du cache Git et cache de récence). Une migration SQLite ajoute l'index de symboles scopé projet.

## Capabilities

### New Capabilities
- `federated-search-bounds`: coûts et bornes d'une requête fédérée (vecteur de requête unique, échéance globale, diagnostic des projets non servis).
- `lucene-reader-cache`: lectures de métadonnées d'index Lucene servies par le reader en cache, sans ouverture par requête.
- `git-context-cache`: caches Git des runtimes longue durée (contexte Git, récence des commits), dimensionnés pour le scope fédéré et observables.
- `symbol-search-scoping`: recherche de symboles dont le coût dépend du projet ciblé et non de la base entière.

### Modified Capabilities

(aucune : `openspec/specs/` est vide)

## Impact

- Code : `context/FederatedContextService`, `search/FederatedSearchService`, `index/ProjectIndexingService`, `search/semantic/SemanticSearchStrategy`, `search/semantic/lucene/{Lucene,PersistentLucene}SemanticSearchIndex`, `search/lucene/{Lucene,PersistentLucene}SearchIndex`, `context/source/git/{PersistentGitContextSourceProvider,GitRecencyCandidateEnricher}`, `persistence/sqlite/SqliteSymbolSearch`, `search/SymbolSearchStrategy`, `application/NexusApplication`.
- Persistance : nouvelle migration `V0NN` (index symboles scopé projet) ; aucune invalidation des index dérivés.
- Contrats publics : nouveau diagnostic des projets non servis dans les réponses fédérées CLI, REST et MCP (parité) ; nouvelles métadonnées `explain` du cache Git.
- Documentation : deux ADR, `docs/developer/` (fédération, caches), contrats documentaires.
- Hors périmètre : parallélisme de la fédération, moteur de recherche externe, changement de modèle d'embedding.
