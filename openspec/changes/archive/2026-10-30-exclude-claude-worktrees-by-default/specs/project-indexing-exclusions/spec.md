# Spec Delta

## Purpose

Définit les exclusions que le scan de projet applique toujours, avant et indépendamment de `.gitignore` et de `.nexusignore`, afin que le contenu généré, sensible ou dupliqué n'entre jamais dans l'index.

## ADDED Requirements

### Requirement: Les worktrees Claude Code sont exclus du scan
Le scan de projet SHALL exclure tout fichier et tout répertoire situé sous un répertoire `.claude/worktrees`, à toute profondeur, sans configuration du projet.

#### Scenario: Worktree à la racine du projet
- **WHEN** le projet contient `.claude/worktrees/demo/AGENTS.md` et `AGENTS.md`
- **THEN** seul `AGENTS.md` est indexé et aucun chemin sous `.claude/worktrees/` n'apparaît dans les résultats

#### Scenario: Worktree dans un sous-module
- **WHEN** le projet contient `module/.claude/worktrees/demo/src/App.java`
- **THEN** ce fichier n'est pas indexé

#### Scenario: Aucune règle d'ignore n'est nécessaire
- **WHEN** le projet n'a ni `.gitignore` ni `.nexusignore` mentionnant `.claude/worktrees`
- **THEN** le worktree est tout de même exclu

### Requirement: L'exclusion ne touche pas le reste de `.claude`
Le scan SHALL continuer à indexer les autres chemins sous `.claude/` (instructions, agents, skills, règles) selon les règles habituelles.

#### Scenario: Skills et instructions voisins
- **WHEN** le projet contient `.claude/skills/exemple/SKILL.md` et `.claude/worktrees/demo/README.md`
- **THEN** `.claude/skills/exemple/SKILL.md` reste découvrable et `.claude/worktrees/demo/README.md` est exclu

#### Scenario: Nom proche
- **WHEN** le projet contient `docs/worktrees/guide.md` ou `.claude/worktrees-notes.md`
- **THEN** ces fichiers ne sont pas exclus par cette règle

### Requirement: L'exclusion intégrée n'est pas réversible par les fichiers d'ignore
Une négation dans `.gitignore` ou `.nexusignore` SHALL NOT réintégrer un chemin sous `.claude/worktrees`.

#### Scenario: Négation explicite
- **WHEN** un `.nexusignore` contient `!.claude/worktrees/`
- **THEN** les fichiers du worktree restent exclus
