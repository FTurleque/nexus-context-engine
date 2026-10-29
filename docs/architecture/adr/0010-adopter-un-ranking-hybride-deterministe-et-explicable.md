---
status: accepted
date: 2026-07-19
---

# ADR-0010 — Adopter un ranking hybride, déterministe et explicable

## Contexte et problème

Le cœur de la valeur de NEXUS réside dans sa capacité à classer les éléments candidats selon leur pertinence pour une demande précise. Une recherche purement lexicale ne suffit pas toujours : un fichier important peut ne pas contenir les mots exacts de la requête, mais être relié à un symbole mentionné, appelé par un fichier sélectionné ou associé à un test pertinent.

À l'inverse, un ranking entièrement opaque ou généré par un LLM empêcherait la reproductibilité, rendrait les tests difficiles et compliquerait l'explication des choix de contexte.

La question est : **comment combiner plusieurs signaux de pertinence tout en produisant un classement reproductible, mesurable et explicable ?**

## Facteurs de décision

- qualité du contexte sélectionné ;
- reproductibilité des résultats ;
- explicabilité pour l'utilisateur et les agents ;
- possibilité de tester le ranking sur des corpus de référence ;
- combinaison de signaux lexicaux, symboliques et structurels ;
- indépendance vis-à-vis d'un LLM ;
- possibilité d'intégrer progressivement de nouveaux signaux ;
- maîtrise du budget de tokens.

## Options envisagées

- utiliser uniquement le score BM25 de Lucene ;
- utiliser uniquement un graphe de dépendances/PageRank ;
- demander à un LLM de classer les candidats ;
- adopter un ranking hybride déterministe combinant plusieurs composantes de score ;
- reproduire directement l'algorithme RepoMap d'Aider.

## Décision retenue

**Option retenue : adopter un ranking hybride, déterministe et explicable, combinant plusieurs composantes de score normalisées.**

Les composantes candidates comprennent :

```text
lexicalScore
symbolScore
pathScore
graphScore
architecturalImportance
testAssociationScore
recentChangeScore
sourcePriorityScore
```

Le score final doit être calculé à partir d'une configuration explicite et stable. Pour une même requête, un même index et une même configuration, le résultat doit être reproductible.

NEXUS étudiera les principes utiles du RepoMap d'Aider, notamment :

- importance des symboles dans un graphe ;
- proximité avec les symboles mentionnés ;
- propagation de pertinence ;
- sélection sous contrainte de budget.

NEXUS ne copiera pas mécaniquement l'algorithme ni le code d'Aider. Les principes retenus seront adaptés au modèle multi-source de NEXUS et validés par mesure.

L'explication d'un score est produite à partir des mêmes composantes qui ont servi au calcul. Elle n'est pas générée a posteriori par un LLM.

### Conséquences positives

- chaque sélection peut être justifiée par des facteurs concrets ;
- les résultats sont testables et comparables dans le temps ;
- le moteur peut combiner Lucene, symboles et graphe ;
- de nouveaux signaux peuvent être ajoutés progressivement ;
- les utilisateurs peuvent comprendre pourquoi un fichier est inclus ou exclu ;
- la qualité peut être mesurée avec `precision@K` et `recall@K`.

### Conséquences négatives et compromis acceptés

- le calibrage des poids demandera des corpus représentatifs ;
- un score déterministe peut être moins flexible qu'un reranking LLM dans certains cas ;
- les scores issus de sources hétérogènes doivent être normalisés ;
- l'ajout de trop nombreux signaux peut rendre le modèle difficile à interpréter.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Tuning arbitraire des poids | Élevé | Utiliser corpus de référence et métriques objectives |
| Score global explicable seulement en apparence | Élevé | Conserver la contribution exacte de chaque composante |
| Graphe surpondéré et bruit structurel | Moyen | Comparer lexical seul vs hybride sur scénarios mesurés |
| Dépendance implicite à l'algorithme d'Aider | Moyen | Implémentation propre, contrat NEXUS et validation indépendante |
| Instabilité des résultats | Élevé | Ordonnancement secondaire stable et configuration versionnée |

### Confirmation

La décision est respectée si :

- chaque `RankedCandidate` peut exposer son score total et ses composantes ;
- le même jeu de données produit le même classement ;
- les golden queries vérifient fichiers attendus et fichiers non pertinents ;
- les gains des signaux de graphe sont mesurés avant généralisation ;
- `--explain` présente les raisons de sélection et d'exclusion ;
- aucun appel LLM n'est nécessaire pour calculer ou expliquer le score.

## Analyse détaillée des options

### Utiliser uniquement BM25

**Avantages :**

- simple ;
- rapide ;
- éprouvé ;
- immédiatement disponible avec Lucene.

**Inconvénients :**

- ne capture pas la proximité structurelle ;
- dépend fortement des termes présents dans les documents ;
- peut ignorer des dépendances importantes.

### Utiliser uniquement un graphe/PageRank

**Avantages :**

- identifie l'importance structurelle ;
- peut révéler des éléments indirectement liés.

**Inconvénients :**

- un élément important globalement n'est pas forcément pertinent pour la requête ;
- qualité dépendante du graphe disponible ;
- moins efficace pour les intentions textuelles précises.

### Demander à un LLM de reranker

**Avantages :**

- compréhension sémantique potentiellement riche ;
- adaptation à des requêtes complexes.

**Inconvénients :**

- coût et latence ;
- non-déterminisme ;
- fuite potentielle de code ;
- dépendance fournisseur ;
- explications difficiles à auditer.

### Adopter un ranking hybride déterministe

**Avantages :**

- combine plusieurs signaux ;
- reste local et reproductible ;
- explicable ;
- optimisable progressivement.

**Inconvénients :**

- nécessite calibration et normalisation ;
- complexité supérieure à un score unique.

### Reproduire directement RepoMap d'Aider

**Avantages :**

- bénéficier d'une approche déjà éprouvée sur du code ;
- accélérer la conception initiale.

**Inconvénients :**

- NEXUS a un périmètre multi-source différent ;
- risque de couplage conceptuel ;
- les hypothèses d'Aider ne sont pas nécessairement valides pour instructions, skills ou documentation.

## Impacts sur l'architecture

```text
Candidate Retrieval
  ├── Lucene
  ├── Symbol Search
  ├── Graph Search
  └── autres sources
        │
        ▼
Score Components
        │
        ▼
ContextRanker
        │
        ├── score total
        └── explication structurée
```

## Conditions de réexamen

Réexaminer les poids ou composantes si :

- les métriques de qualité stagnent ;
- une nouvelle source fournit un signal significatif ;
- le graphe devient suffisamment riche pour modifier l'équilibre ;
- un reranker optionnel démontre un gain mesurable sans compromettre les principes local-first.

Le principe de base — déterminisme et explicabilité du ranking par défaut — reste à préserver.

## Décisions liées

- ADR-0007 — Utiliser Apache Lucene comme index de recherche local.
- ADR-0009 — Rendre l'intelligence de code extensible via des providers et index externes.
- ADR-0013 — Construire un ContextBundle sous budget de tokens explicable.
- ADR-0014 — Rendre la recherche sémantique et les embeddings optionnels.
