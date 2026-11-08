---
id: BE-0071
title: "Rechercher les candidats dans Lucene, SQLite et le graphe d'imports, puis les classer par un score composé explicable"
domain: backend
rules: true
---

# Rechercher les candidats dans Lucene, SQLite et le graphe d'imports, puis les classer par un score composé explicable

- **Statut** : Accepted
- **Date** : 2026-11-08
- **Origine** : consolidation du registre ADR (audit du 2026-11-08)
- **Remplace** : [BE-0010](archive/BE-0010-adopter-un-ranking-hybride-deterministe-et-explicable.md), [BE-0024](archive/BE-0024-combiner-lucene-et-sqlite-pour-la-recherche-de-candidats.md), [BE-0025](archive/BE-0025-normaliser-les-signaux-et-calculer-un-score-compose-explicable.md), [BE-0026](archive/BE-0026-construire-un-graphe-minimal-de-fichiers-a-partir-des-imports.md)
- **Remplacé par** : —

## Context

NEXUS doit classer les éléments d'un projet selon leur pertinence pour une demande. La recherche lexicale seule manque les fichiers reliés à un symbole mentionné ou importés par un fichier pertinent ; un classement opaque ou produit par un LLM empêcherait la reproductibilité, les tests sur corpus de référence et l'explication des choix.

NEXUS dispose de deux représentations du projet : Lucene, index dérivé et reconstructible optimisé pour les chemins, le contenu et les noms de symboles ; SQLite, source de vérité des fichiers, symboles et relations ([DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md)). Dupliquer chaque symbole en document Lucene introduirait un second référentiel canonique ; tout chercher dans SQLite priverait NEXUS de BM25 et des boosts par champ.

Les signaux (BM25, symbole exact ou approché, chemin, proximité structurelle) n'ont ni la même échelle ni la même sémantique : additionnés bruts, le plus grand écraserait les autres. JavaParser fournit des imports et des types fiables, sans résolution profonde ni graphe d'appels ; le RepoMap d'Aider montre l'intérêt d'un graphe de fichiers, sur un modèle de données différent.

La question est : **comment récupérer des candidats dans Lucene, SQLite et le graphe d'imports, puis combiner leurs signaux en un classement reproductible, mesurable et explicable, sans LLM ?**

## Options envisagées

- **Classement** : BM25 seul (aveugle à la structure) ; graphe/PageRank seul (importance non liée à la requête) ; reranking LLM (coût, non-déterminisme, fuite de code) ; RepoMap d'Aider tel quel (hypothèses non valides hors code) ; **ranking hybride déterministe** (retenu).
- **Stockages** : un document Lucene par symbole (duplication) ; Lucene seul (relations mal servies) ; SQLite seul (perte de BM25) ; **Lucene pour les fichiers, SQLite pour symboles et relations, puis fusion** (retenu).
- **Score** : somme brute (échelles incompatibles) ; meilleur score seul (perd le consensus) ; learning-to-rank (corpus, explicabilité réduite) ; **normalisation dans `[0,1]` puis poids explicites** (retenu).
- **Structure** : attendre SCIP ; graphe d'appels complet avec JavaParser (coût, inexactitude) ; copie de RepoMap ; **graphe minimal de fichiers à partir des imports résolus** (retenu).

## Decision

**Option retenue : récupérer les candidats par stratégies spécialisées (Lucene pour les fichiers, SQLite pour les symboles et relations, graphe minimal d'imports), les fusionner, puis les classer par un ranking hybride, déterministe et explicable, combinant des signaux normalisés et pondérés.**

### Recherche des candidats

Lucene sert la recherche lexicale de fichiers (BM25 et boosts par champ) ; SQLite sert la recherche structurelle de symboles (exacte, sous-chaîne, approchée) et de relations. Les candidats sont fusionnés avant ranking.

Lucene indexe les noms de symboles comme champs du document fichier, pour qu'ils influencent le score lexical du fichier, mais les objets `CodeSymbol` complets sont lus depuis SQLite. Lucene ne devient pas source de vérité des symboles, et une reconstruction Lucene ne modifie pas les symboles stockés dans SQLite.

La fusion utilise des identifiants déterministes : un même fichier ou symbole produit par plusieurs stratégies est dédupliqué et conserve l'ensemble de ses signaux.

### Graphe minimal d'imports

NEXUS construit un graphe minimal orienté entre fichiers à partir des imports résolus vers les types définis dans le repository : nœud = `relativePath`, arête = import résolu. Les imports externes qui ne correspondent à aucun type du repository sont ignorés et ne créent aucune arête. Le graphe est reconstruit de manière déterministe à partir de SQLite.

`graphScore` est calculé à partir de la proximité avec les fichiers déjà pertinents et de l'importance structurelle locale, par une propagation bornée sur un ou deux sauts. Un PageRank personnalisé n'est ajouté que si les benchmarks montrent un gain sur `precision@K` ou `recall@K`.

NEXUS adopte le principe d'un ranking structurel par graphe, sans reproduire la propagation globale de RepoMap tant que les relations disponibles se limitent aux imports.

### Score composé

Chaque signal consommé par le ranker est normalisé dans `[0,1]`, puis le score est calculé de façon déterministe avec des poids explicites, centralisés et versionnés dans le code :

```text
score = Σ(normalizedSignal × weight)
```

Les signaux sont `lexicalScore`, `symbolExactScore`, `symbolFuzzyScore`, `pathScore` et `graphScore`. D'autres composantes peuvent s'y ajouter par la même formule (`architecturalImportance`, `testAssociationScore`, `recentChangeScore`, `sourcePriorityScore`…), chacune mesurée avant généralisation. Les poids sont une configuration technique validée sur corpus de référence ; ils ne sont pas considérés comme définitivement optimaux.

Pour une même requête, un même index et une même configuration, le classement est reproductible ; les égalités sont départagées par un ordre secondaire stable (type, chemin, identifiant).

### Explication

Chaque `RankedCandidate` expose son score final, ses composantes, des raisons textuelles dérivées de ces composantes et un ordre secondaire stable. Le score total se recompose à partir des composantes et les raisons affichées correspondent à des composantes non nulles. L'explication est produite par le moteur de ranking lui-même, à partir des composantes qui ont servi au calcul ; elle n'est ni générée a posteriori, ni reformulée par un LLM. Aucun appel LLM n'est nécessaire pour calculer ou expliquer le score.

### Hors décision

- Les principes repris du RepoMap d'Aider (importance des symboles dans un graphe, proximité des symboles mentionnés, propagation de pertinence, sélection sous budget) sont adaptés au modèle multi-source de NEXUS et validés par mesure ; ni l'algorithme ni le code d'Aider ne sont copiés.
- La sélection sous budget des fragments relève de [BE-0072](BE-0072-construire-le-contextbundle-sous-budget-de-tokens.md) ; les signaux sémantique et de récence Git relèvent de [BE-0084](BE-0084-garder-la-recherche-semantique-optionnelle-et-qualifiee-hors-release.md) et [BE-0070](BE-0070-integrer-le-contexte-git-local-borne-mis-en-cache-et-confine.md) ; la fédération multi-projets relève de [BE-0081](BE-0081-federer-la-recherche-locale-par-projet-sous-echeance-globale.md).

## Do's and Don'ts

### Do

- Utiliser Lucene pour la recherche lexicale de fichiers et SQLite pour la recherche structurelle de symboles et relations, puis fusionner les candidats avant ranking.
- Indexer les noms de symboles comme champs du document fichier Lucene et lire les objets `CodeSymbol` complets depuis SQLite.
- Dédupliquer par identifiants déterministes un même fichier ou symbole produit par plusieurs stratégies, en conservant l'ensemble des signaux.
- Construire le graphe à partir des imports résolus vers les types du repository (nœud = `relativePath`, arête = import résolu) et calculer `graphScore` par une propagation bornée.
- Normaliser chaque signal dans `[0,1]` et calculer le score avec des poids explicites, centralisés et versionnés dans le code.
- Produire le même classement pour une même requête, un même index et une même configuration, avec un ordre secondaire stable.
- Exposer dans chaque `RankedCandidate` le score final, les composantes, les raisons dérivées de ces composantes et produire l'explication dans le moteur de ranking.
- Valider par mesure tout principe repris du RepoMap d'Aider et tout nouveau signal.

### Don't

- Ne pas faire de Lucene une source des objets `CodeSymbol` complets.
- Ne pas créer d'arête pour un import externe qui ne correspond à aucun type du repository.
- Ne pas ajouter de PageRank personnalisé sans gain mesuré sur `precision@K` ou `recall@K`.
- Ne pas additionner des scores bruts non normalisés.
- Ne pas générer, reformuler ni inventer l'explication d'un score par un LLM.
- Ne pas copier mécaniquement l'algorithme ni le code d'Aider.
- Ne pas considérer les poids initiaux comme définitivement optimaux.

## Consequences

### Positives

- Chaque stockage sert sa spécialité, sans second référentiel canonique des symboles ni graphe dupliqué dans Lucene ; les stratégies restent testables séparément.
- Chaque sélection est justifiée par des facteurs concrets ; les résultats sont reproductibles, comparables dans le temps et mesurables (`precision@K`, `recall@K`).
- Un signal de proximité structurelle existe à partir des données déjà persistées ; de nouveaux signaux s'ajoutent sans que leur plage numérique dicte le classement.

### Négatives

- La recherche combine deux backends et la fusion des candidats devient une responsabilité explicite du moteur.
- La normalisation perd une partie de l'information absolue des scores bruts ; le calibrage des poids demande des corpus représentatifs.
- Les imports ne capturent ni les dépendances implicites du même package, ni les appels dynamiques, ni l'injection : le graphe est moins riche que celui d'Aider ou d'un index SCIP.
- Trop de signaux rendraient le modèle difficile à interpréter.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Poids arbitraires ou tuning opportuniste | Élevé | Golden queries, `precision@K` / `recall@K`, poids centralisés |
| Explication incohérente avec le score | Élevé | Raisons générées depuis les composantes effectivement utilisées |
| Instabilité à score égal | Élevé | Tri secondaire stable par type, chemin et identifiant |
| Divergence entre Lucene et SQLite | Élevé | Lucene dérivé et reconstructible ([DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md)) |
| Faux sentiment de précision sémantique du graphe | Élevé | Graphe nommé « minimal », imports non résolus ignorés |
| Graphe surpondéré, propagation de bruit | Moyen | Profondeur et poids bornés ; comparaison avec et sans `graphScore` |

## Compliance and Enforcement

- Tests `DeterministicContextRankerValidationTest`, `DeterministicContextRankerGitTest`, `SemanticRankingTest` (`core/src/test/java/com/nexus/ranking/`), `ProjectGraphBuilderProjectionTest` (`core/src/test/java/com/nexus/ranking/graph/`), `SearchServiceIntegrationTest` (`combinesLexicalSymbolAndGraphSignalsDeterministically`), `GoldenSearchCorpusTest` (`core/src/test/java/com/nexus/search/`) et `SearchCandidateValidationTest` (`core/src/test/java/com/nexus/search/candidate/`), lancés par `mvnw verify`.
- Règle Archgate `BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.rules.ts` :
  - `ranking-has-no-network-or-llm-call` : refuse dans `core/src/main/java/com/nexus/ranking/` tout accès réseau (`java.net`, client HTTP, socket), tout lancement de processus et toute référence à `EmbeddingProvider` ou à Ollama ;
  - `lucene-search-does-not-touch-sqlite` : refuse dans `core/src/main/java/com/nexus/search/lucene/` toute référence à `com.nexus.persistence`, `java.sql`, `org.sqlite` ou `IndexRepository`.
  Commentaires et littéraux effacés avant la recherche.
- Revue : bornes `[0,1]` des signaux, centralisation et stabilité des poids, résolution des imports ; aucun contrôle automatisé ne les fige.

## Conditions de réexamen

La décision est réévaluée si :

- les métriques de qualité stagnent ou montrent un biais systématique, ou le corpus de référence s'élargit ;
- une nouvelle source (SCIP, JDT, provider externe) fournit un signal, des références ou une capacité de recherche significatifs ;
- le volume de symboles rend la recherche approchée SQLite insuffisante, ou un schéma Lucene plus granulaire améliore fortement la qualité ;
- un PageRank personnalisé, un reranker optionnel ou une méthode plus avancée démontre un gain mesurable en restant local-first et explicable ;
- un repository multi-langage exige d'autres types de relations.

Le déterminisme et l'explicabilité du ranking par défaut restent à préserver. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Mise en œuvre

- Orchestration : `core/src/main/java/com/nexus/search/SearchService.java` (stratégies, fusion, enrichisseurs, ranker).
- Stratégies : `core/src/main/java/com/nexus/search/lucene/LuceneFileSearchStrategy.java`, `core/src/main/java/com/nexus/search/SymbolSearchStrategy.java`.
- Fusion et signaux : `core/src/main/java/com/nexus/search/candidate/CandidateMerger.java` (maximum par signal), `SearchSignals.java`.
- Graphe : `core/src/main/java/com/nexus/ranking/graph/ProjectGraphBuilder.java` (voisinage borné projeté depuis SQLite), `GraphCandidateEnricher.java` (propagation sur deux sauts) ; requêtes dans `core/src/main/java/com/nexus/persistence/sqlite/SqliteGraphQueries.java`.
- Ranking : `core/src/main/java/com/nexus/ranking/DeterministicContextRanker.java` (poids centralisés, raisons, tri secondaire), `RankedCandidate.java`.
- Détail technique : [`docs/developer/search-ranking.md`](../../developer/search-ranking.md).

## References

- [BE-0072](BE-0072-construire-le-contextbundle-sous-budget-de-tokens.md) — construction du `ContextBundle` sous budget.
- [DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md) — SQLite source de vérité, Lucene index dérivé.
- [ARCH-0080](ARCH-0080-enrichir-l-intelligence-de-code-par-des-providers-et-importers-optionnels.md) — providers et importers d'intelligence de code.
- [BE-0084](BE-0084-garder-la-recherche-semantique-optionnelle-et-qualifiee-hors-release.md), [BE-0070](BE-0070-integrer-le-contexte-git-local-borne-mis-en-cache-et-confine.md), [BE-0081](BE-0081-federer-la-recherche-locale-par-projet-sous-echeance-globale.md).
- Aider Repository Map : https://aider.chat/docs/repomap.html
