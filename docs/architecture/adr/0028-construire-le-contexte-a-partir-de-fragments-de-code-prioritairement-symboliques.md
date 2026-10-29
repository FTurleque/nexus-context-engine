---
status: accepted
date: 2026-07-19
---

# ADR-0028 — Construire le contexte à partir de fragments de code prioritairement symboliques

## Contexte et problème

La recherche de l'Itération 2 produit des candidats classés de type fichier, test ou symbole. Le contexte final ne doit pas copier systématiquement les fichiers complets : cela consommerait rapidement le budget et réduirait la densité d'information utile.

NEXUS dispose déjà des plages de lignes des symboles Java. Ces informations permettent de matérialiser des extraits ciblés. Les résultats fichier restent nécessaires lorsque la recherche identifie un fichier sans symbole précis ou lorsqu'un petit fichier peut être inclus intégralement à faible coût.

## Facteurs de décision

- maximiser l'information pertinente par token ;
- préserver la lisibilité du code sélectionné ;
- utiliser les positions structurelles déjà extraites par JavaParser ;
- éviter les doublons entre un fichier et ses symboles ;
- conserver un comportement générique pour les futurs langages ;
- permettre la traçabilité des lignes incluses.

## Options envisagées

- toujours inclure les fichiers complets ;
- toujours produire des fenêtres lexicales autour des termes de requête ;
- privilégier les plages de symboles lorsque disponibles, puis utiliser des extraits de fichier comme solution de repli ;
- demander à un LLM de résumer les fichiers avant inclusion.

## Décision retenue

**Option retenue : matérialiser d'abord des fragments basés sur les symboles classés, puis utiliser des fragments de fichier lorsque aucun symbole suffisamment précis n'est disponible.**

La stratégie initiale est :

```text
RankedCandidate SYMBOL
→ lignes startLine..endLine
→ ajout d'un petit contexte de lignes autour du symbole
→ ContextFragment

RankedCandidate FILE / TEST
→ fichier court : contenu complet
→ fichier long : fenêtres autour des termes de la requête
→ ContextFragment
```

Lorsque plusieurs candidats symboliques d'un même fichier se chevauchent ou sont adjacents, leurs plages sont fusionnées avant estimation du budget.

Lorsqu'un fichier possède déjà des fragments symboliques pertinents, NEXUS évite d'ajouter en plus son contenu complet, sauf si le fichier est suffisamment petit pour que l'inclusion intégrale soit clairement plus simple et reste sous les limites configurées.

Les fragments conservent :

- le chemin ;
- le type de candidat ;
- le symbole éventuel ;
- les lignes de début et de fin ;
- le score ;
- les raisons de sélection ;
- l'estimation de tokens ;
- l'indication de troncature éventuelle.

### Conséquences positives

- densité de contexte supérieure à l'inclusion systématique des fichiers ;
- exploitation directe de l'analyse structurelle existante ;
- meilleure explicabilité ;
- fusion possible des plages redondantes ;
- préparation naturelle à des providers SCIP ou JDT plus précis.

### Conséquences négatives et compromis acceptés

- un symbole isolé peut manquer de contexte global ;
- les fenêtres lexicales de repli sont moins précises qu'un vrai symbole ;
- la fusion de plusieurs fragments demande une logique supplémentaire ;
- les fichiers non Java n'auront pas immédiatement la même qualité de découpage structurel.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Extrait trop étroit pour comprendre le symbole | Élevé | Ajouter quelques lignes de contexte configurées autour de la plage |
| Duplication fichier + symbole | Élevé | Regrouper par chemin et privilégier les fragments symboliques |
| Fenêtres lexicales trop nombreuses | Moyen | Fusionner les intervalles chevauchants et limiter le nombre de fenêtres |
| Coupure au milieu d'une structure | Moyen | Les symboles utilisent les bornes AST ; la troncature finale est marquée explicitement |

### Confirmation

La décision est respectée si :

- les candidats symboliques utilisent leurs lignes AST ;
- les intervalles chevauchants sont fusionnés ;
- un petit fichier peut être inclus intégralement ;
- un fichier long utilise un extrait plutôt qu'une inclusion intégrale automatique ;
- chaque `ContextItem` indique sa plage de lignes et sa troncature.

## Analyse détaillée des options

### Toujours inclure les fichiers complets

**Avantages :** contexte maximal et logique simple.

**Inconvénients :** gaspillage de tokens, duplication et faible densité d'information.

### Fenêtres lexicales uniquement

**Avantages :** applicable à tous les fichiers texte.

**Inconvénients :** ignore les frontières structurelles connues et peut couper les méthodes ou classes.

### Symboles d'abord, fichiers en repli

**Avantages :** exploite l'AST, réduit le bruit et reste extensible.

**Inconvénients :** nécessite une matérialisation et fusion d'intervalles.

### Résumé par LLM

**Avantages :** compression potentiellement forte.

**Inconvénients :** coût, non-déterminisme, dépendance externe et risque de perte d'information.

## Impacts sur l'architecture

```text
RankedCandidate
      │
      ▼
ContextFragmentFactory
      │
      ├── SymbolFragmentExtractor
      └── FileFragmentExtractor
              │
              ▼
        FragmentMerger
              │
              ▼
         ContextItem
```

## Conditions de réexamen

Réexaminer si SCIP/JDT fournit des plages plus riches, si d'autres langages nécessitent un découpage différent, ou si les benchmarks montrent qu'un autre type d'extrait conserve mieux le rappel sous budget.

## Décisions liées

- ADR-0013 — Construire un ContextBundle sous budget de tokens explicable.
- ADR-0025 — Normaliser les signaux et calculer un score composé explicable.
- ADR-0027 — Utiliser un estimateur de tokens local, déterministe et remplaçable.
