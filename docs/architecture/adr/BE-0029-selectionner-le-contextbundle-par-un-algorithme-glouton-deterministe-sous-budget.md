---
id: BE-0029
title: "Sélectionner le ContextBundle par un algorithme glouton déterministe sous budget"
domain: backend
rules: false
---

# Sélectionner le ContextBundle par un algorithme glouton déterministe sous budget

- **Statut** : Accepted
- **Date** : 2026-07-19

## Context

Une fois les candidats classés et transformés en fragments, NEXUS doit sélectionner un sous-ensemble qui respecte strictement le budget estimé. Le problème peut être assimilé à une variante de sélection sous contrainte de capacité, mais le MVP doit rester simple, rapide et explicable.

Un solveur d'optimisation global ou un LLM de reranking ajouterait une complexité disproportionnée. En revanche, une simple accumulation naïve peut dupliquer des plages, gaspiller le budget ou produire des résultats instables.

## Facteurs de décision

- respect strict du budget estimé ;
- déterminisme ;
- faible coût d'exécution ;
- explicabilité des inclusions et exclusions ;
- prise en compte de la fusion des fragments ;
- capacité à tronquer explicitement un dernier fragment utile ;
- métriques de réduction du contexte.

## Options envisagées

- inclure les fragments jusqu'au premier dépassement ;
- résoudre un problème d'optimisation global ;
- demander à un LLM de choisir les fragments ;
- utiliser une sélection gloutonne déterministe après fusion et déduplication.

## Decision

**Option retenue : utiliser une sélection gloutonne déterministe après normalisation, fusion et déduplication des fragments.**

Le pipeline est :

```text
Fragments matérialisés
      │
      ▼
Normalisation des chemins et intervalles
      │
      ▼
Fusion des chevauchements / adjacences
      │
      ▼
Déduplication
      │
      ▼
Tri déterministe par score puis chemin puis ligne
      │
      ▼
Sélection gloutonne sous budget
      │
      ├── fragment complet s'il tient
      ├── fragment tronqué s'il reste un budget utile
      └── exclusion expliquée sinon
      │
      ▼
ContextBundle
```

La somme des `estimatedTokens` des `ContextItem` ne doit jamais dépasser `tokenBudget` selon le `TokenEstimator` actif.

Un fragment trop grand peut être tronqué uniquement si une portion significative peut encore être incluse. La troncature est explicite dans le contenu et dans les métadonnées de l'item. Une exclusion conserve un motif lisible.

Le bundle expose des métriques telles que :

- nombre de candidats classés ;
- nombre de fragments matérialisés ;
- nombre d'items sélectionnés ;
- nombre d'exclusions ;
- nombre de troncatures ;
- tokens estimés avant sélection ;
- tokens sélectionnés ;
- ratio de réduction.

### Conséquences positives

- comportement simple à comprendre et reproduire ;
- budget strict ;
- résultats stables ;
- exclusions auditables ;
- coût linéaire ou quasi linéaire après tri ;
- bonne base de comparaison pour des stratégies plus sophistiquées.

### Conséquences négatives et compromis acceptés

- l'algorithme glouton n'est pas garanti optimal globalement ;
- un fragment légèrement moins bien classé mais plus compact pourrait parfois apporter plus d'information totale ;
- la stratégie ne modélise pas encore explicitement la diversité des types de contexte ;
- les sous-budgets par source restent une extension future.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Gros premier fragment consommant trop de budget | Moyen | Favoriser les fragments symboliques et appliquer un plafond de fragment avant sélection |
| Troncature trop agressive | Moyen | Marquer la troncature et imposer un minimum de contenu utile |
| Instabilité à score égal | Élevé | Tie-break déterministe chemin puis ligne |
| Déduplication supprimant une raison importante | Moyen | Fusionner les raisons et conserver le score maximal |
| Ratio de réduction trompeur | Moyen | Le calculer à partir des fragments matérialisés uniques avant sélection |

### Confirmation

La décision est respectée si :

- aucun bundle ne dépasse son budget estimé ;
- le même jeu d'entrée produit le même bundle ;
- les fragments fusionnés ne se chevauchent pas dans le résultat ;
- les exclusions et troncatures sont expliquées ;
- les métriques de réduction sont présentes dans `ContextBundle.metadata`.

## Do's and Don'ts

### Do

- Sélectionner les fragments par un algorithme glouton déterministe après normalisation, fusion et déduplication.
- Trier de façon déterministe par score, puis chemin, puis ligne.
- Marquer explicitement toute troncature dans le contenu et dans les métadonnées de l'item, et donner un motif lisible à chaque exclusion.
- Exposer dans le bundle les métriques de sélection (candidats, fragments, items, exclusions, troncatures, tokens avant et après sélection, ratio de réduction).

### Don't

- Ne jamais laisser la somme des `estimatedTokens` des `ContextItem` dépasser `tokenBudget` selon le `TokenEstimator` actif.
- Ne pas tronquer un fragment si aucune portion significative ne peut être incluse.

## Compliance and Enforcement

- Tests `DefaultContextBuilderIntegrationTest` (`buildsDeterministicSymbolFirstContextWithinBudget`, `truncatesLargeRelevantFragmentWithoutExceedingBudget`), `BudgetedContextSelectorUnicodeTest` et `BudgetedContextSelectorRedactionTest` (`core/src/test/java/com/nexus/context/`, lancés par `mvnw verify`).
- Aucune règle Archgate : le respect du budget est un comportement à l'exécution, vérifié par les tests et en revue.

## Analyse détaillée des options

### Accumulation jusqu'au premier dépassement

**Avantages :** implémentation minimale.

**Inconvénients :** arrête trop tôt, ne gère pas correctement la fusion ni une troncature utile.

### Optimisation globale

**Avantages :** peut trouver une meilleure combinaison selon une fonction d'utilité.

**Inconvénients :** complexité et coût prématurés, fonction d'utilité encore immature.

### Sélection par LLM

**Avantages :** compréhension sémantique potentielle.

**Inconvénients :** coût, non-déterminisme, confidentialité et paradoxe consistant à envoyer beaucoup de contexte pour décider quel contexte envoyer.

### Sélection gloutonne déterministe

**Avantages :** simple, rapide, explicable et testable.

**Inconvénients :** optimalité globale non garantie.

## Impacts sur l'architecture

```text
ContextFragmentFactory
        │
        ▼
FragmentMerger
        │
        ▼
BudgetedContextSelector
        │
        ├── TokenEstimator
        └── TruncationPolicy
        │
        ▼
ContextBundle
```

## Conditions de réexamen

Réexaminer lorsque le corpus de référence montre que le glouton réduit trop fortement le rappel sous budget, ou lorsqu'un modèle de diversité/sous-budget par source apporte un gain mesurable.

## Décisions liées

- ADR-0013 — Construire un ContextBundle sous budget de tokens explicable.
- ADR-0027 — Utiliser un estimateur de tokens local, déterministe et remplaçable.
- ADR-0028 — Construire le contexte à partir de fragments de code prioritairement symboliques.
