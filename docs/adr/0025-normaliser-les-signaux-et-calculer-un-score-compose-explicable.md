---
status: accepted
date: 2026-07-19
---

# ADR-0025 — Normaliser les signaux et calculer un score composé explicable

## Contexte et problème

NEXUS doit combiner plusieurs signaux de pertinence : score Lucene/BM25, correspondance exacte de symbole, similarité fuzzy, correspondance de chemin et proximité dans le graphe. Ces signaux n'ont ni la même échelle ni la même sémantique.

Additionner directement les scores bruts rendrait le résultat dépendant des particularités d'implémentation de chaque backend. Un score Lucene élevé pourrait écraser tous les autres signaux, tandis qu'un futur provider pourrait modifier le classement simplement parce qu'il produit une plage numérique différente.

Le classement doit rester déterministe, stable et explicable conformément à l'ADR-0010.

## Facteurs de décision

- comparabilité des signaux ;
- stabilité du ranking ;
- explicabilité ;
- possibilité de tester chaque composante ;
- capacité à ajuster les poids sans modifier les stratégies de recherche ;
- support futur de nouveaux signaux ;
- absence de dépendance à un LLM.

## Options envisagées

- additionner les scores bruts ;
- utiliser uniquement le meilleur score disponible ;
- normaliser chaque signal dans une plage commune puis appliquer des poids explicites ;
- entraîner un modèle de learning-to-rank dès le MVP.

## Décision retenue

**Option retenue : normaliser chaque signal dans la plage `[0,1]`, puis calculer un score composé déterministe à l'aide de poids explicites et versionnés dans le code.**

Les signaux initiaux sont :

```text
lexicalScore
symbolExactScore
symbolFuzzyScore
pathScore
graphScore
```

Le score final est de la forme :

```text
score = Σ(normalizedSignal × weight)
```

Les poids initiaux constituent une configuration technique du MVP et devront être validés sur un corpus de référence. Ils ne sont pas considérés comme définitivement optimaux.

Chaque `RankedCandidate` doit exposer :

- le score final ;
- les composantes utilisées ;
- les raisons textuelles dérivées de ces composantes ;
- un ordre secondaire stable pour départager les égalités.

L'explication est produite par le moteur de ranking lui-même. Elle ne doit pas être reformulée ou inventée par un LLM.

### Conséquences positives

- les stratégies peuvent produire leurs propres scores sans dicter directement le ranking final ;
- l'ajout d'un nouveau signal reste maîtrisable ;
- le résultat est reproductible ;
- l'utilisateur peut comprendre la contribution de chaque facteur ;
- les poids peuvent être ajustés sur la base de métriques.

### Conséquences négatives et compromis acceptés

- la normalisation peut perdre une partie de l'information absolue du score brut ;
- les poids initiaux comportent nécessairement une part d'heuristique ;
- le tuning devra être effectué sur un corpus suffisamment représentatif ;
- plusieurs candidats identiques peuvent nécessiter une fusion préalable de leurs signaux.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Poids arbitraires | Élevé | Golden queries et métriques `precision@K` / `recall@K` |
| Normalisation instable selon le nombre de résultats | Moyen | Normalisation déterministe par stratégie et tests de non-régression |
| Explication incohérente avec le score | Élevé | Générer les raisons directement depuis les composantes effectivement utilisées |
| Égalités non déterministes | Moyen | Tri secondaire stable par type, chemin et identifiant |

### Confirmation

La décision est respectée si :

- tous les signaux consommés par le ranker sont bornés dans `[0,1]` ;
- les poids sont centralisés ;
- le score total peut être recomposé à partir des composantes ;
- les raisons affichées correspondent à des composantes non nulles ;
- un même jeu de candidats produit toujours le même ordre.

## Analyse détaillée des options

### Additionner les scores bruts

**Avantages :** simplicité.

**Inconvénients :** échelles incompatibles, comportement fragile et difficile à expliquer.

### Utiliser uniquement le meilleur score

**Avantages :** logique simple et robuste aux échelles.

**Inconvénients :** perd la valeur du consensus entre plusieurs signaux et empêche un vrai ranking hybride.

### Normaliser puis pondérer

**Avantages :** explicable, extensible, testable et cohérent avec le MVP.

**Inconvénients :** nécessite une calibration des poids.

### Learning-to-rank

**Avantages :** potentiel de qualité supérieur avec suffisamment de données.

**Inconvénients :** nécessite un corpus d'entraînement, réduit l'explicabilité et ajoute une complexité disproportionnée au MVP.

## Impacts sur l'architecture

```text
Search strategies
      │
      ▼
raw signals
      │
      ▼
Signal normalization
      │
      ▼
DeterministicContextRanker
      │
      ├── score final
      ├── composantes
      └── raisons
```

## Conditions de réexamen

Réexaminer les poids et méthodes de normalisation lorsque :

- le corpus de référence augmente ;
- un nouveau signal majeur est ajouté ;
- les métriques montrent un biais systématique ;
- une méthode de ranking plus avancée démontre un gain mesurable tout en restant suffisamment explicable.

## Décisions liées

- ADR-0010 — Adopter un ranking hybride, déterministe et explicable.
- ADR-0024 — Combiner Lucene et SQLite pour la recherche de candidats.
