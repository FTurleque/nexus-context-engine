# Construction du contexte et gestion des budgets

Ce chapitre décrit les contrats courants de `DefaultContextBuilder` et de la fédération.

## Deux niveaux de bornes

NEXUS borne le travail **avant** et **après** la découverte :

1. `ContextDiscoveryLimits` limite visites, candidats, octets et durée des sources natives/Git avant sélection ;
2. le budget de tokens limite le `ContextBundle` final.

Une sortie finale petite n'autorise donc pas un scan natif non borné en amont.

Defaults de découverte :

```text
visited entries  100000
candidates       5000
bytes            32 MiB
elapsed          15 s
```

Voir [`native-context-discovery-limits.md`](native-context-discovery-limits.md).

## Pipeline mono-projet

```text
ContextRequest
  ↓
SearchService
  ↓
instructions / skills / Git
  │  même ContextDiscoveryBudget
  ↓
fragments + déduplication
  ↓
BudgetedContextSelector
  ↓
ContextBundle
```

Invariant : `estimatedTokens <= tokenBudget`.

Les instructions, références, skills et customisations projet passent par les frontières filesystem durcies. Les ressources de skills sont inventoriées, jamais exécutées automatiquement.

## Git

Git est local/read-only. Le provider n'est actif que lorsque le budget final le permet, mais il reste également soumis au budget de découverte partagé. Historique, chemins et patch sont bornés avant le sélecteur de tokens.

## Fédération : ordre de validation

Une requête fédérée ne commence pas par résoudre tous les projets.

```text
sélecteurs explicites
  ↓
normalisation / UUID
  ↓
cardinalité canonique <= 100 projets uniques
  ↓
résolution projet
  ↓
gate READY
  ↓
service fédéré
```

La limite de 100 uniques est donc fail-fast : le 101e UUID unique provoque l'erreur de portée avant `PROJECT_NOT_FOUND` ou `requireReadyProject` sur les sélecteurs concernés.

## FederatedContextService

Pour une portée valide et READY :

- budget global ;
- budget de travail préparatoire ;
- fair floor ;
- provenance projet ;
- round-robin/refill ;
- déduplication inter-projet ;
- metadata de starvation.

Les sources natives sont évaluées dans leur projet d'origine. Une instruction, un skill ou un fragment Git n'est jamais propagé implicitement d'un projet à un autre.

## Budgets de familles

Le sélecteur final conserve les sous-budgets usuels : instructions, skills, Git puis contexte de tâche, avec restitution des portions inutilisées. Ils complètent `ContextDiscoveryLimits` ; ils ne le remplacent pas.

## Métadonnées

Le bundle explicable expose notamment les métriques de découverte, sélection, déduplication, Git, skills et budget de travail afin qu'une consommation ou une starvation soit diagnostiquable.

## Validation

- `ContextDiscoveryLimitsTest` : frontière exacte et N+1 ;
- `NativeContextDiscoveryBudgetBenchmarkTest` : 1 000 skills filesystem au seuil exact ;
- tests fédérés application/CLI : cardinalité avant résolution ;
- benchmark fédéré : 100 projets et budget de travail global.

Les mêmes contrats sont utilisés par CLI, REST et MCP.
