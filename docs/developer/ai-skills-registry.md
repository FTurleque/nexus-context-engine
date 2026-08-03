# AI Skills Registry dans NEXUS

## Statut

Capacité livrée depuis l'Itération 14. Phase 6 corrige la dérive de composition identifiée lors de l'audit de consolidation.

NEXUS peut découvrir des skills provenant d'un snapshot local AI Skills Registry sans accès réseau pendant la construction du contexte.

## Snapshot local

```text
.nexus/registry/skills/**/SKILL.md
```

`.nexus/registry` est un cache local non versionné. Son absence n'empêche jamais les skills propres au projet de fonctionner.

## Sources et priorités

```text
LocalAgentSkillsProvider       priorité 80
AiSkillsRegistryProvider      priorité 60
            ↓
SkillDiscoveryService
            ↓
tri / déduplication par nom
```

Un skill local de même nom conserve la priorité sur le snapshot partagé.

## Composition Phase 6

Les deux providers sont maintenant composés indépendamment dans `NexusApplication` :

```text
NexusApplication
   ├─ LocalAgentSkillsProvider
   └─ AiSkillsRegistryProvider
            ↓
     SkillDiscoveryService
```

`LocalAgentSkillsProvider` ne crée plus et n'appelle plus `AiSkillsRegistryProvider`. La composition est donc conforme au port `SkillSourceProvider` et la politique d'agrégation reste centralisée.

## Divulgation progressive

```text
découverte  → frontmatter seulement
sélection   → name + description + metadata
activation  → SKILL.md complet
ressources  → inventoriées, jamais exécutées automatiquement
```

Cette politique reste identique pour origine locale et registry.

## Absence de réseau

Pendant `ContextBuilder` :

- aucune requête HTTP ;
- aucun clone/fetch Git ;
- aucun secret ;
- aucune dépendance à la disponibilité d'un registre distant.

La synchronisation éventuelle du snapshot appartient à un outil externe.

## Contexte fédéré

Phase 6 conserve les skills dans leur portée projet : chaque `DefaultContextBuilder` découvre ses providers pour son propre projet avant que `FederatedContextService` ne fusionne les résultats. Aucun skill d'un projet n'est propagé implicitement à un autre.

## Validation

Les tests historiques I14 continuent de couvrir découverte progressive, priorité locale, absence de snapshot et non-exécution des ressources. Le gate Phase 6 ajoute la qualification du reactor/composition complet :

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-phase-6.ps1
```

Voir [`agent-skills.md`](agent-skills.md), [`current-limitations.md`](current-limitations.md) et la [`roadmap`](../roadmap.md).
