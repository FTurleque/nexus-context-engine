# Contexte natif des projets

NEXUS réutilise les conventions déjà présentes dans un repository sans imposer un format propriétaire.

## Familles

### Instructions

```text
AGENTS.md / AGENT.md
.github/copilot-instructions.md
.github/instructions/**/*.instructions.md
CLAUDE.md / .claude/CLAUDE.md
GEMINI.md
```

Les scopes/applyTo sont respectés et les références locales restent confinées au projet.

### Agent Skills locaux

```text
.agents/skills/**/SKILL.md
.github/skills/**/SKILL.md
.claude/skills/**/SKILL.md
```

Découverte légère par frontmatter, sélection déterministe, puis chargement complet du `SKILL.md` uniquement pour les skills retenus. NEXUS n'exécute jamais les scripts du skill.

### AI Skills Registry local

```text
.nexus/registry/skills/**/SKILL.md
```

Snapshot optionnel et local, sans réseau pendant `ContextBuilder`. Les skills projet restent prioritaires en cas de même nom.

### Git

Contexte historique local/read-only uniquement pour les chemins déjà ciblés par la recherche.

## Frontière filesystem commune

Les lectures projet durcies utilisent `ProjectPathGuard` ou les helpers communs qui s'y appuient. Traversal, symlink final et symlink d'ancêtre sont refusés sur ces chemins. Les customisations opérationnelles détectées ne sont pas injectées comme instructions.

## Budget de découverte avant tokens

Instructions, skills, registry, customisations et Git partagent un même `ContextDiscoveryBudget` :

```text
visited entries
candidate resources
cumulative bytes
global deadline
```

Ce budget est consommé avant le travail coûteux lorsque possible. Il s'ajoute aux sous-budgets de tokens du bundle final.

Un workspace pathologique ne peut donc pas scanner un nombre illimité de fichiers puis être seulement tronqué à la fin.

## Déduplication et sélection

Les instructions applicables et skills sélectionnés sont dédupliqués avant fusion avec le contexte de tâche. Une documentation référencée et le même contenu remonté par la recherche peuvent également être dédupliqués cross-source.

## Fédération

Toutes ces sources restent projet-locales. Une fédération valide construit le contexte de chaque projet dans son propre périmètre puis fusionne les items avec provenance ; aucune instruction/skill/Git n'est propagé implicitement entre repositories.

## Sécurité

```text
contenu hors projet                 refusé
symlink sur chemin durci            refusé
hook exécuté                        jamais
script skill exécuté                jamais
settings injectés automatiquement   jamais
réseau registry pendant context     jamais
```

Voir [`native-context-discovery-limits.md`](native-context-discovery-limits.md), [`agent-skills.md`](agent-skills.md), [`ai-skills-registry.md`](ai-skills-registry.md) et [`git-context.md`](git-context.md).
