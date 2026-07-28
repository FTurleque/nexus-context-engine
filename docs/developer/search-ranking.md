# Recherche, graphe et ranking explicable

Ce chapitre décrit le pipeline **actuel** de recherche NEXUS. Les mesures et décisions historiques des Itérations 2, 7, 16 et 17 restent conservées dans la roadmap, les ADR et les documents de benchmark.

## 1. Pipeline mono-projet

```text
query
 │
 ├── LuceneFileSearchStrategy
 ├── SymbolSearchStrategy
 └── SemanticSearchStrategy          opt-in
 │
 ▼
CandidateMerger
 │
 ├── GraphCandidateEnricher
 └── GitRecencyCandidateEnricher
 │
 ▼
ContextRanker
 ├── DeterministicContextRanker      défaut
 └── SemanticHybridContextRanker     mode sémantique
 │
 ▼
RankedCandidate[]
```

`SearchService` orchestre les stratégies, fusionne les candidats, applique les enrichisseurs puis délègue le classement final au `ContextRanker`.

## 2. Retrieval interne

Pour une demande de `limit`, `SearchService` calcule :

```text
retrievalLimit = min(500, max(20, limit * 3))
```

Chaque `SearchStrategy` reçoit cette limite afin de laisser de la marge à la fusion et aux enrichissements avant le ranking final.

Cette sur-récupération existe au niveau mono-projet. La recherche fédérée possède encore une limite distincte : elle demande actuellement seulement le `limit` global à chaque projet avant diversification par chemin. Voir F01 dans [`current-limitations.md`](current-limitations.md).

## 3. Recherche Lucene

`LuceneFileSearchStrategy` délègue à `LuceneSearchIndex`.

Champs recherchés et boosts :

| Champ | Boost |
|---|---:|
| `symbol_name` | 5.0 |
| `qualified_name` | 4.0 |
| `path_text` | 3.0 |
| `code_terms` | 2.0 |
| `content` | 1.0 |

`code_terms` normalise notamment les identifiants `camelCase`, acronymes et séparateurs afin d'améliorer la recherche de code.

Pour une requête multi-termes contenant au moins deux termes analysés uniques, Lucene construit une requête coordonnée avec un minimum de deux termes correspondants. Ce comportement a été introduit pendant l'Itération 16 pour réduire les faux positifs à un seul terme.

Le score brut Lucene est normalisé relativement au meilleur hit éligible :

```text
lexicalScore = hit.score / maxHitScore
```

`pathScore` mesure la proportion de termes présents dans le chemin.

Les catégories `INSTRUCTION`, `AGENT_PROFILE` et `SKILL` sont exclues de la recherche générique ; elles ont leurs propres pipelines de découverte.

## 4. Recherche de symboles

`SymbolSearchStrategy` produit :

```text
symbolExactScore
symbolFuzzyScore
pathScore
```

Un match exact vaut `1.0` lorsque le nom ou nom qualifié correspond à la requête/à un terme pertinent.

Le fuzzy combine :

- `0.9` pour un contains direct ;
- une similarité Levenshtein normalisée ;
- un seuil minimal `0.62`.

### Limite actuelle

La stratégie récupère encore tous les symboles du projet via `IndexRepository.findSymbols(projectId)` puis calcule le fuzzy en Java avant le `limit` final.

C'est le principal plafond de scale identifié. L'Itération 19 doit déplacer le préfiltrage vers des requêtes repository indexées et réserver Levenshtein à un ensemble borné.

## 5. Fusion des candidats

`CandidateMerger` agrège les signaux de candidats représentant la même identité logique au lieu de sommer prématurément les scores.

Principe :

```text
une stratégie trouve
+
une autre stratégie confirme
=
un candidat avec plusieurs signaux explicables
```

Le ranking final reste responsable de la pondération.

## 6. Graphe

`ProjectGraphBuilder` utilise :

- les symboles de type (`CLASS`, `INTERFACE`, `RECORD`, `ENUM`, `ANNOTATION`, `TYPE`) ;
- les relations `IMPORTS` ;
- la provenance normalisée persistée dans SQLite.

Il résout les imports vers des fichiers internes puis construit un graphe de voisinage non orienté.

`GraphCandidateEnricher` propage un score à deux sauts :

```text
premier saut = seedScore × 0.65
second saut  = seedScore × 0.35
```

Le seed utilise le maximum des signaux directs lexical/symbole/path.

### Limite actuelle

Le graphe est reconstruit depuis les symboles/relations du projet à chaque recherche. I19 doit mesurer puis réutiliser une représentation associée à la génération d'index, sans introduire un cache complexe sans preuve.

## 7. Récence Git

`GitRecencyCandidateEnricher` ajoute un signal faible et explicable de récence locale.

La valeur de référence historique du bonus est `0.05` par défaut et peut être désactivée par configuration.

Le Git context reste local et en lecture seule ; l'enrichisseur ne transforme pas NEXUS en client Git réseau.

Le coût de l'inspection Git reste un point de mesure avant toute stratégie de cache.

## 8. Ranking historique par défaut

`DeterministicContextRanker` conserve les composantes explicables issues des stratégies/enrichisseurs.

Les poids historiques principaux établis par l'Itération 2 sont :

| Signal | Poids de base |
|---|---:|
| lexical | 0.40 |
| symbol exact | 0.30 |
| symbol fuzzy | 0.10 |
| path | 0.10 |
| graph | 0.10 |

Les enrichissements ajoutés ensuite, notamment Git, restent explicitement bornés et documentés dans leurs composants/ADR.

À score égal, le tri applique des critères stables afin de préserver le déterminisme.

## 9. Recherche sémantique opt-in

Quand `SemanticSearchConfiguration` est activée :

```text
EmbeddingProvider
        │
        ▼
LuceneSemanticSearchIndex
        │
        ▼
SemanticSearchStrategy
```

La fusion additive initiale a été rejetée. `SemanticHybridContextRanker` construit deux rankings séparés puis applique une Reciprocal Rank Fusion :

```text
k = 60
poids historique = 1.0
poids sémantique = 8.0
```

Le chemin sans signal sémantique reste le ranking historique.

Voir [`semantic-search.md`](semantic-search.md).

## 10. Recherche fédérée

`FederatedSearchService` reçoit une liste explicite de `ProjectDescriptor` :

1. déduplication des projets par UUID ;
2. recherche projet par projet via `SearchService` ;
3. fusion globale par score ;
4. stabilisation des égalités par ordre de projet/rang local ;
5. diversification par `projectId + path` ;
6. top-K final.

Deux repositories différents ne sont jamais dédupliqués sur leur seul chemin.

Limites et baseline : [`large-scale-search.md`](large-scale-search.md).

## 11. Explicabilité

Lorsque `explain=true`, NEXUS conserve :

- composantes de score ;
- raisons de ranking ;
- raisons de troncature/exclusion dans le contexte ;
- provenance des données de Code Intelligence.

Ces explications sont dérivées du calcul. Elles ne sont pas générées par un LLM.

## 12. Qualité

Le projet utilise des corpus golden mono-projet et fédéré.

Métriques suivies selon les itérations :

```text
precision@K
recall@K
hit@K
MRR@K
p50 / p95 de recherche
```

Une modification du retrieval ou du ranking doit comparer les mêmes corpus avant/après.

## 13. Dette de scale à traiter avant un moteur externe

Ordre recommandé :

1. corriger le top-K fédéré ;
2. imposer le gate `READY` ;
3. préfiltrer les symboles côté repository ;
4. requêter les relations de manière ciblée ;
5. réutiliser le graphe ;
6. mesurer à nouveau p50/p95/heap ;
7. seulement ensuite réévaluer Zoekt/OpenGrok/index distant si les mesures le justifient.

Voir [`current-limitations.md`](current-limitations.md) et les Itérations 18-19 de la [`roadmap`](../roadmap.md).
