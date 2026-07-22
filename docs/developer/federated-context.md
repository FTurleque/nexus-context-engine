# Contexte fédéré multi-projet — Itération 18

## Statut

Le premier incrément de l'Itération 18 introduit une construction de contexte de tâche sur plusieurs projets explicitement sélectionnés.

La capacité est placée dans le cœur applicatif, sous REST et MCP. Elle n'est pas encore exposée par les adaptateurs publics et sa validation locale reste à exécuter avant toute décision de finalisation.

## Objectif

L'Itération 16 a démontré que NEXUS peut rechercher plusieurs repositories avec une provenance explicite et un ranking fédéré déterministe. Le contexte restait cependant mono-projet afin de ne pas mélanger sans politique les instructions, Agent Skills et signaux Git propres à chaque racine.

Ce premier incrément répond à la partie mesurable du problème :

- rechercher plusieurs projets explicitement sélectionnés ;
- matérialiser les fragments utiles dans chaque projet ;
- sélectionner les fragments selon le classement fédéré ;
- partager un **seul budget de tokens global** ;
- conserver le projet d'origine de chaque item ;
- ne pas dédupliquer deux repositories distincts parce qu'ils possèdent le même chemin relatif.

## Flux

```text
List<projectId>
      │
      ▼
NexusApplication.contextAcrossProjects(...)
      │
      ▼
FederatedContextBuilder
      │
      ├── FederatedSearchService
      │       ├── SearchService(Project A)
      │       ├── SearchService(Project B)
      │       └── SearchService(Project C)
      │
      ├── ContextFragmentFactory par projet
      ├── FragmentMerger par projet
      │
      ├── portée interne projectId/relativePath
      │
      ▼
BudgetedContextSelector
      │
      ▼
FederatedContextBundle
      └── FederatedContextItem
            ├── ProjectDescriptor
            └── ContextItem
```

Le `projectId` ajouté au chemin pendant la sélection est strictement interne. Il garantit que `src/main/java/demo/Service.java` dans deux repositories différents reste deux identités distinctes. Le chemin original relatif au projet est restitué dans le `ContextItem` final.

## Contrats

### `FederatedContextRequest`

Contient :

- la liste explicite des `ProjectDescriptor` ;
- la requête ;
- le budget global ;
- les sources demandées ;
- les contraintes ;
- le mode explicable.

La portée n'est jamais étendue implicitement à tous les projets connus de NEXUS.

### `FederatedContextItem`

Associe chaque `ContextItem` sélectionné à son `ProjectDescriptor` d'origine. La provenance est donc un contrat métier, pas une convention de nommage de chemin.

### `FederatedContextBundle`

Expose :

- les items avec provenance ;
- le budget global ;
- le nombre estimé de tokens réellement sélectionnés ;
- les exclusions lorsqu'elles sont demandées ;
- les métadonnées de mesure.

L'invariant reste :

```text
estimatedTokens <= tokenBudget
```

## Politique de budget du premier incrément

Le premier incrément n'impose **aucun quota statique par projet**.

Les candidats sont classés par la recherche fédérée existante, puis tous les fragments éligibles sont soumis ensemble au `BudgetedContextSelector`. La pertinence globale décide donc de la répartition du budget.

Cette politique évite d'inventer un partage égalitaire ou un quota minimal avant d'avoir observé un problème réel. Pour rendre ce choix mesurable, le bundle expose notamment :

- `selectedItemsByProject` ;
- `selectedTokensByProject` ;
- `budgetPolicy = global-ranking-no-static-project-quota`.

Si un corpus réel montre qu'un projet monopolise systématiquement le budget au détriment d'informations nécessaires, l'itération devra comparer des politiques de diversification avant d'en adopter une.

## Sources couvertes

Le premier incrément couvre uniquement :

```text
FILE
SYMBOL
TEST
DOCUMENTATION
```

Lorsque `requestedSources` est vide, ces quatre sources constituent la portée implicite du contexte fédéré.

## Sources volontairement différées

Les sources suivantes restent projet-locales :

```text
INSTRUCTION
SKILL
GIT
```

Une requête fédérée qui les demande explicitement est refusée. NEXUS ne choisit pas silencieusement :

- quelles instructions d'un repository doivent primer sur celles d'un autre ;
- si un skill local doit s'appliquer à tout le workspace ou uniquement à son projet ;
- comment répartir le budget Git entre plusieurs historiques ;
- quel projet éventuel doit être considéré comme projet principal.

Ces arbitrages devront être décidés à partir d'usages et de mesures réels.

## Déduplication

La déduplication est locale au projet.

Deux fragments de projets différents ne sont jamais fusionnés sur leur seul chemin relatif. Cela permet par exemple à deux repositories de contenir simultanément :

```text
src/main/java/demo/InvoiceService.java
```

sans perdre l'une des deux provenances.

## Façade applicative

Le point d'entrée ajouté est :

```java
NexusApplication.contextAcrossProjects(
        projectIds,
        query,
        tokenBudget,
        requestedSources,
        constraints,
        explain);
```

La façade résout les projets enregistrés, puis délègue à `FederatedContextBuilder`.

REST et MCP ne sont pas modifiés dans ce premier incrément. Leur exposition doit intervenir seulement après validation du contrat et du comportement sous budget.

## Tests du premier incrément

`NexusApplicationFederatedContextTest` couvre :

- deux projets réellement enregistrés et indexés ;
- un chemin relatif identique dans deux repositories ;
- conservation des deux provenances ;
- respect d'un budget unique ;
- chemins finaux relatifs au projet ;
- déterminisme d'une exécution répétée ;
- rejet explicite de `GIT` tant que la politique multi-projet n'est pas définie ;
- refus d'un contexte fédéré lorsqu'un projet de la portée n'est pas indexé.

## Validation attendue

Avant de considérer le premier incrément comme validé :

```text
mvn clean install
scripts/self-smoke.ps1
scripts/validate-iteration-18.ps1
```

La validation doit confirmer au minimum :

- absence de régression du contexte mono-projet ;
- recherche fédérée historique toujours verte ;
- tests du contexte fédéré verts ;
- invariant de budget global ;
- provenance des items ;
- conservation de chemins identiques entre repositories distincts.

## Paliers suivants

Après validation du socle, l'Itération 18 doit mesurer un portefeuille réel avant de décider :

1. si la sélection purement pilotée par le ranking suffit ou nécessite une diversification inter-projets ;
2. comment intégrer les instructions natives ;
3. comment intégrer les Agent Skills ;
4. comment intégrer le contexte Git ;
5. quand exposer le contrat via REST et MCP.

L'objectif n'est pas de maximiser le nombre de sources, mais de conserver le meilleur contexte possible sous un budget global explicable.

## Décision d'architecture

Voir ADR-0044 — `Construire un contexte fédéré sous budget global avant d'arbitrer les sources projet-locales`.
