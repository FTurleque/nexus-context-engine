# Construction du contexte et gestion du budget

Ce chapitre décrit `DefaultContextBuilder` et l'orchestration fédérée ajoutée en Phase 6.

## ContextBundle projet-local

`ContextRequest` contient :

```text
projectId
query
tokenBudget
requestedSources
constraints
explain
```

`ContextBundle` contient :

```text
items
tokenBudget
estimatedTokens
excluded
metadata
```

Invariant : `estimatedTokens <= tokenBudget`.

## Gate READY

`DefaultContextBuilder` exigeait déjà `READY`. Phase 6 applique désormais le même gate dans `NexusApplication` à toutes les lectures indexées : recherche, symboles, usages, contexte, recherche fédérée, contexte fédéré et import MINOS.

## Pipeline mono-projet

```text
ContextRequest
   ↓
SearchService
   ↓
RankedCandidate[]
   ├─ filtre requestedSources
   ├─ instructions natives
   ├─ Agent Skills
   └─ Git ciblé
   ↓
ContextFragmentFactory
   ↓
déduplication cross-source
   ↓
FragmentMerger
   ↓
BudgetedContextSelector
   ↓
ContextBundle
```

La limite de retrieval dépend du budget :

```text
retrievalLimit = min(100, max(20, tokenBudget / 40))
```

## Instructions natives

Providers : AGENTS.md, Copilot, Claude et Gemini. Les références locales restent confinées au repository et bornées en profondeur.

## Agent Skills

```text
SkillSourceProvider[]
        ↓
SkillDiscoveryService
        ↓
SkillSelector
        ↓
SkillLoader
        ↓
SkillContextSelector
```

Phase 6 compose `LocalAgentSkillsProvider` et `AiSkillsRegistryProvider` indépendamment. Le provider local n'instancie plus le registre. La priorité local > registry reste portée par les descriptors et la déduplication du service.

Les ressources sont inventoriées mais jamais exécutées automatiquement.

## Git

`LocalGitContextSourceProvider` reste local/read-only. Il est désactivé sous 500 tokens de budget global et reçoit un sous-budget borné lorsqu'il est actif.

Aucun cache Git persistant n'est introduit sans benchmark.

## Budgets par famille

Instructions : environ 25 %, plafonné à 600 tokens.

Skills : environ 20 %, plafonné à 2 000 tokens.

Git : environ 15 %, plafonné à 500 tokens, seulement si budget global >= 500.

La tâche utilise le budget restant. Les portions inutilisées restent disponibles aux familles suivantes.

## Fragments et déduplication

Pour un symbole précis, NEXUS privilégie un extrait autour de ses lignes. Pour un fichier, le builder choisit fichier entier, fenêtres autour des termes ou fallback borné selon le budget.

Les doublons entre recherche générique et sources natives sont supprimés avant fusion. `FragmentMerger` fusionne les plages adjacentes/chevauchantes d'un même fichier.

## Estimation des tokens

`HeuristicTokenEstimator` reste local, déterministe et remplaçable. La valeur estimée n'est pas présentée comme équivalente à un tokenizer de fournisseur LLM.

## ContextBundle fédéré — Phase 6

`FederatedContextService` reçoit une portée explicite de projets READY et un **budget global unique**.

```text
projects[] + query + globalBudget
            ↓
allocation déterministe du budget
            ↓
DefaultContextBuilder(Project A)
DefaultContextBuilder(Project B)
...
            ↓
round-robin inter-projet
            ↓
déduplication inter-projet du contenu
            ↓
FederatedContextBundle
```

Chaque item devient un `FederatedContextItem` contenant :

```text
ProjectDescriptor project
ContextItem item
```

Cela empêche toute ambiguïté de provenance même lorsque deux repositories contiennent le même chemin relatif.

### Fairness

Le budget est réparti entre les projets de la portée, reste strictement <= budget global, puis les items sont entrelacés round-robin. Les metadata exposent :

```text
allocationByProject
localTokensByProject
localItemsByProject
selectedTokensByProject
selectedItemsByProject
starvedProjects
starvedProjectCount
crossProjectDeduplicatedItems
mergePolicy=fair-budget-round-robin
nativeSourceScope=project-local
```

### Sources natives

Instructions, Skills et Git sont calculés **dans le projet d'origine**. Phase 6 n'autorise aucune propagation implicite d'une instruction ou d'un skill d'un projet vers un autre.

### Déduplication inter-projet

Deux items de même type dont le contenu normalisé est identique ne sont conservés qu'une fois dans le bundle fédéré. Leur chemin seul n'est jamais utilisé pour dédupliquer entre projets.

## Surfaces fédérées

```text
CLI  context-federated
REST POST /api/v1/federated/context
MCP  build_context_across_projects
MCP  explain_context_across_projects
```

## Sécurité

- chemins confinés aux racines projets ;
- aucune exécution de skill ;
- Git read-only ;
- providers externes non requis pour le contexte standard ;
- projet non-READY refusé ;
- budget local ou fédéré strictement respecté.

## Validation

La Phase 6 ajoute un test dédié au budget global, à la provenance et à la déduplication fédérée. Le gate final Windows est :

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-phase-6.ps1
```

Voir [`current-limitations.md`](current-limitations.md) et la [`roadmap`](../roadmap.md).
