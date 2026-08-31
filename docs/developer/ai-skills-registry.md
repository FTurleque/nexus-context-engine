# AI Skills Registry dans NEXUS

NEXUS peut découvrir des skills depuis un snapshot local :

```text
.nexus/registry/skills/**/SKILL.md
```

Aucun accès réseau n'est effectué pendant la construction du contexte.

## Composition

```text
LocalAgentSkillsProvider   priorité 80
AiSkillsRegistryProvider   priorité 60
          ↓
SkillDiscoveryService
          ↓
déduplication / sélection / activation progressive
```

Un skill local de même nom reste prioritaire.

## Frontière filesystem

Le registry n'est pas une exception de sécurité. Les résolutions projet passent par `ProjectPathGuard` :

- traversal refusé ;
- symlink final refusé ;
- symlink d'ancêtre refusé ;
- disparition/remplacement unsafe pendant l'opération échoue fermé selon le point de lecture concerné.

Les mêmes principes s'appliquent aux customisations projet durcies.

## Budget de découverte

`AiSkillsRegistryProvider` consomme le `ContextDiscoveryBudget` partagé :

- chaque entrée visitée ;
- chaque `SKILL.md` candidat ;
- les octets de frontmatter lus ;
- la deadline commune.

Un registry pathologique ne peut donc pas effectuer un scan illimité avant la sélection de tokens.

Le benchmark `NativeContextDiscoveryBudgetBenchmarkTest` crée 1 000 skills réels et vérifie la frontière exacte, l'ordre déterministe, les compteurs et le temps de découverte.

## Divulgation progressive

```text
découverte  → frontmatter seulement
sélection   → name + description + metadata
activation  → SKILL.md complet
ressources  → inventoriées, jamais exécutées automatiquement
```

Les ressources/scripts ne sont jamais exécutés par NEXUS.

## Contexte fédéré

Chaque projet découvre ses propres providers dans son `DefaultContextBuilder`. Aucun skill d'un projet n'est propagé implicitement à un autre.

## Absence du snapshot

`.nexus/registry` est optionnel. Son absence n'empêche pas les skills propres au projet de fonctionner.

Voir [`agent-skills.md`](agent-skills.md), [`native-context-discovery-limits.md`](native-context-discovery-limits.md) et [`current-limitations.md`](current-limitations.md).
