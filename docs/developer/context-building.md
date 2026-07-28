# Construction du contexte et gestion du budget

Ce chapitre décrit l'implémentation **actuelle** de `DefaultContextBuilder`.

La construction de contexte est l'une des responsabilités centrales de NEXUS : transformer une requête et des sources hétérogènes en un `ContextBundle` déterministe, explicable et borné.

## 1. Contrats

### `ContextRequest`

```text
projectId
query
tokenBudget
requestedSources
constraints
explain
```

- `requestedSources` vide signifie : toutes les sources éligibles ;
- `constraints` transporte des contraintes clé/valeur sans faire fuiter un client dans le cœur ;
- `explain` active les raisons et exclusions détaillées.

### `ContextBundle`

```text
items
tokenBudget
estimatedTokens
excluded
metadata
```

Invariant :

```text
estimatedTokens <= tokenBudget
```

### `ContextItem`

```text
type
path
symbol
startLine
endLine
content
score
scoreComponents
reasons
estimatedTokens
truncated
```

## 2. Gate de disponibilité

`DefaultContextBuilder` résout le projet puis exige :

```text
IndexStatus.READY
```

Un projet `NOT_INDEXED`, `INDEXING` ou `FAILED` est refusé avec `ContextBuildingException`.

Cette protection est déjà correcte dans le builder. La Phase 6 doit appliquer le même principe aux autres lectures indexées (`search`, symboles, usages).

## 3. Pipeline complet

```text
ContextRequest
    │
    ▼
SearchService
    │
    ▼
RankedCandidate[]
    │
    ├── filtre requestedSources
    │
    ├── targetPaths
    │      ├── instructions natives
    │      └── contexte Git ciblé
    │
    ├── discovery/matching/loading des skills
    │
    ▼
ContextFragmentFactory
    │
    ├── fragments tâche
    ├── fragments instructions
    ├── fragments skills
    └── fragments Git
    │
    ▼
déduplication cross-source
    │
    ▼
FragmentMerger
    │
    ▼
sélections par budgets successifs
    │
    ▼
ContextBundle + metadata
```

## 4. Retrieval de candidats

La limite demandée à `SearchService` dépend du budget :

```text
retrievalLimit = min(100, max(20, tokenBudget / 40))
```

Le builder récupère ainsi un ensemble plus large que les quelques fragments qui rentreront finalement dans le budget.

## 5. Filtrage des sources

Si `requestedSources` est non vide, seuls les candidats de ces types sont conservés pour les fragments de tâche.

Types actuellement utilisés dans la construction :

```text
FILE
SYMBOL
TEST
DOCUMENTATION
INSTRUCTION
SKILL
GIT
```

Les instructions, skills et Git possèdent leurs propres pipelines de discovery ; ils ne sont pas ramenés artificiellement par la recherche Lucene générique.

## 6. `targetPaths`

Le builder conserve jusqu'à 100 chemins relatifs dérivés des candidats classés.

Ces chemins servent à :

- résoudre les instructions avec scope ;
- cibler l'historique/diff Git ;
- contextualiser les sources natives autour des fichiers réellement pertinents.

## 7. Instructions natives

Providers composés :

```text
AgentsMdInstructionProvider
CopilotInstructionProvider
ClaudeInstructionProvider
GeminiInstructionProvider
```

`ContextSourceDiscoveryService` agrège les sources applicables et déduplique les contenus.

Les références locales `@fichier` sont confinées au repository et limitées en profondeur.

Les sources découvertes sont transformées en fragments via `ContextSourceFragmentFactory`.

## 8. Agent Skills

Pipeline :

```text
SkillDiscoveryService
→ SkillSelector
→ SkillLoader
→ SkillContextSelector
```

La découverte utilise seulement les métadonnées légères. Le corps complet du `SKILL.md` est chargé après sélection.

Les ressources associées sont inventoriées mais ne sont ni chargées ni exécutées automatiquement.

La priorité locale sur AI Skills Registry est conservée par les descriptors/algorithmes de déduplication. La composition provider actuelle doit encore être simplifiée ; voir F09.

## 9. Contexte Git

`LocalGitContextSourceProvider` est interrogé seulement si :

- la source `GIT` est demandée ou les sources sont laissées ouvertes ;
- le provider existe ;
- le budget global est d'au moins 500 tokens.

Le provider peut produire :

- commits récents liés ;
- historique court ;
- diff local ciblé ;
- co-changements.

Il reste strictement local et en lecture seule.

## 10. Fragments de tâche

`ContextFragmentFactory` matérialise les candidats code/tests/documentation/symboles.

### Symbole précis

Lorsqu'un `CodeSymbol` est disponible, NEXUS privilégie un extrait autour de sa plage plutôt que le fichier entier.

### Fichier sans symbole précis

Le builder peut :

- inclure le fichier entier s'il reste assez petit relativement au budget ;
- sinon produire des fenêtres autour des termes de la requête ;
- utiliser un fallback borné si aucune correspondance lexicale locale n'est trouvée.

L'objectif est de préserver la diversité du contexte plutôt que de laisser un seul fichier consommer tout le budget.

## 11. Déduplication cross-source

Un fichier déjà sélectionné comme instruction/référence native ne doit pas être réinjecté comme fragment de tâche uniquement parce que Lucene l'a également remonté.

Le builder compare les chemins normalisés puis expose notamment :

```text
metadata.crossSourceDeduplicatedFragments
```

`FragmentMerger` fusionne ensuite les plages chevauchantes/adjacentes d'un même fichier.

## 12. Estimateur de tokens

L'implémentation par défaut est `HeuristicTokenEstimator` :

```text
estimatedTokens = ceil(pointsDeCodeUnicode / 3.5)
```

Cette estimation est :

- locale ;
- déterministe ;
- remplaçable via `TokenEstimator` ;
- indépendante d'un fournisseur LLM.

Elle ne prétend pas reproduire exactement un tokenizer OpenAI, Anthropic ou autre.

## 13. Sélection gloutonne

`BudgetedContextSelector` :

1. trie les fragments par score, chemin et lignes ;
2. borne la part d'un fragment à environ la moitié du budget de la sélection ;
3. inclut le fragment complet s'il tient ;
4. sinon tente une troncature utile ;
5. conserve les exclusions lorsque `explain=true`.

Un fragment tronqué porte le marqueur :

```text
... [fragment tronqué par NEXUS]
```

La sélection reste déterministe.

## 14. Politique de budgets par famille

### Instructions

```text
instructionBudget = min(
    totalBudget,
    600,
    max(24, totalBudget / 4)
)
```

Soit environ 25 % du budget, plafonné à 600 tokens.

### Skills

```text
skillBudget = min(
    remaining,
    2000,
    max(64, totalBudget / 5)
)
```

Soit environ 20 %, plafonné à 2 000 tokens.

### Git

Le Git est désactivé sous 500 tokens de budget global.

Lorsqu'il est actif :

```text
gitBudget = min(
    remaining,
    500,
    max(64, totalBudget * 15 / 100)
)
```

### Tâche

Le contexte de tâche reçoit tout le budget restant après instructions, skills et Git.

Le budget non consommé par une famille n'est pas perdu : il reste disponible pour les familles suivantes.

## 15. Metadata d'explication

Le bundle expose notamment :

```text
query
tokenEstimator
rankedCandidates
sourceEligibleCandidates
documentationCandidates
materializedFragments
crossSourceDeduplicatedFragments
mergedFragments
instructionProviders
nativeSourcesDiscovered
instructionBudget
instructionSelectedItems
instructionSelectedTokens
skillProviders
skillsDiscovered
skillsMatched
skillsActivated
skillResourcesDiscovered
skillBudget
skillSelectedItems
skillSelectedTokens
skillsExecuted=false
gitProvider
gitEnabled
gitRepositoryAvailable
gitDiagnostics
gitCommitsInspected
gitRelatedCommits
gitCoChangeLinks
gitBudget
gitSelectedItems
gitSelectedTokens
nativeCustomizationsDetected
selectedItems
excludedItems
truncatedItems
availableEstimatedTokens
selectedEstimatedTokens
reductionRatio
```

Les métadonnées décrivent le calcul et la sélection ; elles ne dépendent pas d'une explication générée par un modèle.

## 16. Sécurité

- les chemins de fragments doivent rester sous la racine projet ;
- les instructions ne peuvent pas référencer arbitrairement un fichier extérieur ;
- les skills ne sont jamais exécutés ;
- le Git context ne mute pas le repository ;
- un provider externe n'est pas requis pour construire un contexte standard ;
- un bundle ne doit jamais dépasser son budget estimé.

## 17. Contexte multi-projet

Le builder actuel reste **mono-projet**.

La recherche fédérée existe déjà, mais aucun `ContextBundle` fédéré n'est livré sur `main`.

Une ancienne PR draft #10 a exploré ce besoin sans validation/merge final. La capacité est replanifiée en Itération 23 après les travaux de correctness, scale et composition.

Le futur builder fédéré doit notamment garantir :

- budget global unique ;
- provenance par projet ;
- absence de collision de chemins ;
- déterminisme ;
- mesure de starvation ;
- politique explicite pour instructions/skills/Git.

## 18. Validation

Les tests de contexte couvrent entre autres :

- projet READY obligatoire ;
- budget strict ;
- fragments symboliques ;
- déduplication/fusion ;
- instructions natives ;
- Agent Skills ;
- Git ;
- metadata ;
- troncature ;
- sources demandées.

Le gate de base reste :

```powershell
mvn clean install
.\scripts\self-smoke.ps1
```

Voir aussi [`current-limitations.md`](current-limitations.md) et les Itérations 18/23 de la [`roadmap`](../roadmap.md).
