# Design

## Context

`ProjectIgnoreMatcher.isIgnored` applique d'abord `isBuiltInExcluded` (liste `BUILT_IN_DIRECTORIES` comparée segment par segment, puis noms de fichiers sensibles), puis les règles `.gitignore` et `.nexusignore` via JGit (ADR-0021). L'exclusion intégrée précède donc toute règle d'ignore et ne peut pas être annulée par une négation. Les chemins évalués sont relatifs à la racine du projet scanné.

Chaque entrée de `BUILT_IN_DIRECTORIES` est un nom d'un seul segment, ce qui ne permet pas d'exprimer `.claude/worktrees` sans exclure tout `.claude/` (qui porte les skills, agents et règles que NEXUS doit découvrir). Voir `proposal.md` pour la motivation.

## Goals / Non-Goals

**Goals:**
- Exclure `.claude/worktrees` à toute profondeur, de façon déterministe, sans configuration.
- Garder le reste de `.claude/` indexable.

**Non-Goals:**
- Lire `.git/info/exclude` ou interroger `git worktree list`.
- Traiter d'autres outils (`.cursor/`, `.codex/`, `.aider*`).
- Rendre la liste configurable.

## Decisions

**D1 — Séquence de segments plutôt que préfixe de chemin.** La règle teste la présence de deux segments consécutifs `.claude`, `worktrees` (comparaison insensible à la casse, comme `BUILT_IN_DIRECTORIES`). Un test sur le préfixe `.claude/worktrees/` manquerait les sous-modules (`module/.claude/worktrees/…`) et ne serait pas cohérent avec les exclusions existantes, qui valent à toute profondeur. Alternative écartée : motif glob JGit synthétique, plus lourd et exposé aux négations.

**D2 — Liste séparée des répertoires d'un segment.** Ajouter une petite constante dédiée (séquences de segments) à côté de `BUILT_IN_DIRECTORIES`, plutôt que d'y glisser `"worktrees"`, qui exclurait aussi `docs/worktrees/`. Elle reste extensible à d'autres séquences sans changer la mécanique.

**D3 — Pas de lecture de `.git/info/exclude`.** Cette source est locale à une copie de travail et non reproductible : le même dépôt donnerait des index différents selon la machine, contraire au déterminisme du projet. Alternative écartée.

**D4 — ADR complémentaire.** La liste intégrée est une décision d'ADR-0021 (« liste intégrée conservatrice »). Un nouvel ADR la complète (statut « complète », jamais « remplace ») et l'ADR-0021 n'est pas modifié (règle d'ADR append-only). Le numéro est le suivant disponible dans `docs/architecture/adr/` au moment de l'implémentation.

## Risks / Trade-offs

- [Un projet versionne volontairement un dossier `.claude/worktrees`] → exclusion non contournable, assumée et documentée dans l'ADR ; ce chemin est la convention d'un outil, pas un répertoire de contenu.
- [Sur-exclusion sur la casse (`.Claude/Worktrees`)] → même comportement que les exclusions intégrées existantes ; sans effet sur les systèmes sensibles à la casse en pratique.
- [Index existants contenant déjà des fichiers de worktrees] → ils sont retirés au prochain réindexage ; aucune migration d'invalidation n'est requise, puisque le contenu ne change pas pour les fichiers restants. À confirmer par le test d'indexation.
