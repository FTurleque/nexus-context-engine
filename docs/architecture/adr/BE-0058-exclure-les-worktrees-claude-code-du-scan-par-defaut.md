---
id: BE-0058
title: "Exclure les worktrees Claude Code du scan par défaut"
domain: backend
rules: false
---

# Exclure les worktrees Claude Code du scan par défaut

- **Statut** : Accepted
- **Date** : 2026-10-30
- **Origine** : changement OpenSpec `exclude-claude-worktrees-by-default`
- **Complète** : [BE-0021](BE-0021-reutiliser-jgit-pour-les-regles-gitignore-et-nexusignore.md) (liste intégrée conservatrice d'exclusions)

## Context

`ProjectIgnoreMatcher.isIgnored` applique d'abord les exclusions intégrées (`isBuiltInExcluded`), puis les règles `.gitignore` et `.nexusignore` évaluées par JGit (BE-0021). Les exclusions intégrées précèdent toute règle d'ignore : une négation ne peut pas les annuler. Chaque répertoire de la liste `BUILT_IN_DIRECTORIES` est un nom d'un seul segment (`target`, `node_modules`, `.git`…), exclu à toute profondeur.

Claude Code crée ses worktrees Git sous `.claude/worktrees/<nom>/`. Chacun est une copie complète du dépôt : son `AGENTS.md`, sa documentation et ses sources concurrencent ceux du dépôt dans l'index, la recherche et les `ContextBundle`. Claude Code ne déclare ce répertoire que dans `.git/info/exclude`, que NEXUS ne lit pas. Un projet qui n'ajoute pas lui-même `.claude/worktrees/` à son `.nexusignore` indexe donc ces copies ; l'étape 9 de `scripts/self-smoke.ps1` a échoué ainsi le 2026-10-17 sur le dépôt NEXUS (le contexte retenait `.claude/worktrees/…/AGENTS.md` au lieu de `AGENTS.md`).

Le reste de `.claude/` (instructions, agents, skills, règles) doit rester découvrable. La question est : comment exclure ces worktrees sans configuration, de façon déterministe, sans exclure le reste de `.claude/` ni les répertoires nommés `worktrees` ailleurs ?

## Options envisagées

- **Option A — suite de segments consécutifs intégrée** : exclure tout chemin contenant les segments consécutifs `.claude` puis `worktrees`, à toute profondeur, sans tenir compte de la casse. Cohérente avec les exclusions intégrées existantes, non annulable par une négation ; un projet ne peut pas réintégrer ce répertoire.
- **Option B — ajouter `worktrees` à `BUILT_IN_DIRECTORIES`** : simple, mais exclut aussi `docs/worktrees/` et tout répertoire de contenu ainsi nommé.
- **Option C — lire `.git/info/exclude` ou `git worktree list`** : suit la déclaration de l'outil, mais cette source est locale à une copie de travail : le même dépôt donnerait des index différents selon la machine, contraire au déterminisme du projet.
- **Option D — motif glob JGit synthétique ajouté aux règles d'ignore** : réutilise BE-0021, mais exposé aux négations des fichiers d'ignore et plus lourd qu'un test de segments.

## Decision

**Option retenue : Option A.**

`ProjectIgnoreMatcher` porte une liste `BUILT_IN_DIRECTORY_SEQUENCES` de suites de segments, distincte de `BUILT_IN_DIRECTORIES`. Elle contient la seule suite `.claude`, `worktrees`. `isBuiltInExcluded` exclut tout chemin relatif au projet dont deux segments consécutifs, comparés en minuscules (`Locale.ROOT`), forment cette suite, quelle que soit leur profondeur : le répertoire `.claude/worktrees` lui-même et tout ce qu'il contient, à la racine comme dans un sous-module (`module/.claude/worktrees/…`).

L'exclusion s'applique avant les fichiers d'ignore ; aucune règle `.gitignore` ou `.nexusignore`, négation comprise, ne réintègre ces chemins. Elle ne concerne ni les autres chemins de `.claude/` (`.claude/skills/…`), ni les noms proches (`.claude/worktrees-notes.md`), ni un répertoire `worktrees` hors de `.claude` (`docs/worktrees/…`).

Cet ADR ne décide pas l'exclusion d'autres outils (`.cursor/`, `.codex/`, `.aider*`) ni une liste configurable ; toute nouvelle suite passe par un nouvel ADR. Aucune migration SQLite n'est requise : le prochain réindexage retire les fichiers déjà indexés sous ces chemins.

## Do's and Don'ts

### Do

- Exclure tout chemin dont deux segments consécutifs sont `.claude` puis `worktrees`, sans tenir compte de la casse et à toute profondeur, dans `isBuiltInExcluded`.
- Exprimer une exclusion intégrée de plusieurs segments dans `BUILT_IN_DIRECTORY_SEQUENCES`.
- Laisser les autres chemins de `.claude/` soumis aux règles d'ignore habituelles.

### Don't

- Ne pas ajouter `worktrees` seul ni `.claude` seul à `BUILT_IN_DIRECTORIES`.
- Ne pas lire `.git/info/exclude` ni interroger `git worktree list` pour décider d'une exclusion.
- Ne pas permettre à une négation `.gitignore` ou `.nexusignore` de réintégrer un chemin sous `.claude/worktrees`.

## Consequences

### Positives

- Tout projet indexé avec des worktrees Claude Code est protégé des copies du dépôt, sans configuration.
- L'index reste identique d'une machine à l'autre pour un même contenu de dépôt.
- Les skills, agents et instructions de `.claude/` restent découvrables.

### Négatives

- Un projet qui versionnerait volontairement du contenu sous `.claude/worktrees` ne peut pas l'indexer.
- La comparaison sans casse exclut aussi `.Claude/WorkTrees`, comme pour les exclusions intégrées existantes.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Un autre outil adopte un répertoire de worktrees différent | Moyen | Hors périmètre ; un nouvel ADR ajoute la suite correspondante |
| Index existants contenant des fichiers de worktrees | Faible | Retirés au prochain réindexage ; pas de migration d'invalidation |

## Compliance and Enforcement

- Contrôle automatique : `ProjectIgnoreMatcherBuiltInExclusionTest` (suite à toute profondeur, casse, noms voisins non exclus) et `ProjectScannerTest` (worktree à la racine et dans `module/`, `.claude/skills/` indexé, `docs/worktrees/` et `.claude/worktrees-notes.md` conservés, négation `!.claude/worktrees/` sans effet), exécutés par `./mvnw clean install` sous Windows et Linux.
- Contrôle automatique : l'étape 9 de `scripts/self-smoke.ps1` vérifie que le contexte du dépôt NEXUS retient son propre `AGENTS.md`.
- Revue : une nouvelle exclusion de plusieurs segments passe par `BUILT_IN_DIRECTORY_SEQUENCES` et par un ADR ; aucune lecture de configuration locale à la copie de travail n'entre dans `ProjectIgnoreMatcher`.

## Conditions de réexamen

La décision est réévaluée si Claude Code change l'emplacement de ses worktrees, si d'autres outils imposent des exclusions comparables ou si un besoin d'exclusions configurables apparaît. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Mise en œuvre

`ProjectIgnoreMatcher.containsBuiltInDirectorySequence` parcourt les segments du chemin relatif et compare chaque suite de `BUILT_IN_DIRECTORY_SEQUENCES` à chaque position. Les tests cités plus haut couvrent chaque scénario du changement OpenSpec.

## References

- [BE-0021](BE-0021-reutiliser-jgit-pour-les-regles-gitignore-et-nexusignore.md) — JGit pour les règles d'ignore et liste intégrée d'exclusions.
- Changement OpenSpec `openspec/changes/exclude-claude-worktrees-by-default/`.
- [Pipeline d'indexation](../../developer/indexing-pipeline.md) — exclusions intégrées du scan.
- [arc42 §9 — Décisions](../arc42/09-decisions.md).
