---
id: BE-0083
title: "Appliquer des règles d'exclusion du scan de type gitignore, avec des exclusions intégrées"
domain: backend
rules: true
---

# Appliquer des règles d'exclusion du scan de type gitignore, avec des exclusions intégrées

- **Statut** : Accepted
- **Date** : 2026-11-08
- **Origine** : consolidation du registre ADR (audit du 2026-11-08)
- **Remplace** : [BE-0021](archive/BE-0021-reutiliser-jgit-pour-les-regles-gitignore-et-nexusignore.md), [BE-0058](archive/BE-0058-exclure-les-worktrees-claude-code-du-scan-par-defaut.md)
- **Remplacé par** : —

## Context

Le scanner NEXUS doit éviter les fichiers ignorés par le projet, les contenus générés et les fichiers sensibles. La syntaxe `.gitignore` comporte des règles non triviales : jokers, `**`, chemins relatifs au fichier d'ignore, négations, fichiers imbriqués. La réimplémenter par `PathMatcher` ou par expressions régulières créerait des divergences avec Git. NEXUS a son propre fichier `.nexusignore`, qui doit reprendre la même grammaire plutôt qu'en inventer une.

Certains répertoires doivent être exclus sans configuration. Claude Code crée ses worktrees Git sous `.claude/worktrees/<nom>/` : chacun est une copie complète du dépôt, dont `AGENTS.md`, la documentation et les sources concurrencent ceux du dépôt dans l'index, la recherche et les `ContextBundle`. Claude Code ne déclare ce répertoire que dans `.git/info/exclude`, que NEXUS ne lit pas ; l'étape 9 de `scripts/self-smoke.ps1` a échoué ainsi le 2026-10-17 sur le dépôt NEXUS. Le reste de `.claude/` (instructions, agents, skills, règles) doit rester découvrable.

La question est : **comment évaluer les exclusions du scan avec la sémantique Git, sans exécutable externe, et quelles exclusions intégrées s'appliquent sans configuration et sans dépendre de la copie de travail ?**

## Options envisagées

- **Moteur d'ignore** : syntaxe propriétaire (nouvelle convention, moteur à maintenir) ; conversion des motifs vers `java.nio.file.PathMatcher` (ne reproduit pas toute la sémantique `.gitignore`) ; `git check-ignore` (processus externe, Git obligatoire, appels coûteux pendant un scan massif) ; moteur d'ignore de JGit (Java pur, parser existant, scope à gérer).
- **Exclusion des worktrees Claude Code** : A — suite de segments consécutifs `.claude`, `worktrees` intégrée, à toute profondeur et sans casse ; B — `worktrees` dans `BUILT_IN_DIRECTORIES` (exclut aussi `docs/worktrees/`) ; C — lire `.git/info/exclude` ou `git worktree list` (source locale à la copie de travail : index différent selon la machine) ; D — motif glob JGit synthétique (exposé aux négations, plus lourd qu'un test de segments).

## Decision

**Options retenues : moteur d'ignore de JGit, et suite de segments intégrée (A) pour les worktrees Claude Code.**

### Règles de type gitignore

JGit parse et évalue les règles de type `.gitignore`, et la même grammaire s'applique aux fichiers `.nexusignore`. Le scanner maintient des règles scopées par répertoire. Lors de la traversée :

1. les règles héritées des répertoires parents sont évaluées ;
2. les `.gitignore` et `.nexusignore` du répertoire courant sont chargés pour ses descendants ;
3. la règle applicable la plus spécifique peut compléter ou annuler une règle précédente selon la sémantique Git ;
4. les règles intégrées NEXUS sont appliquées pour les dossiers générés et secrets manifestes.

L'usage de JGit dans le scanner concerne uniquement la sémantique d'ignore ; il ne fait pas de NEXUS un client Git. L'exécutable `git` n'est pas requis pour scanner un projet.

### Exclusions intégrées

- Les exclusions intégrées s'appliquent avant les fichiers d'ignore : aucune règle `.gitignore` ou `.nexusignore`, négation comprise, ne réintègre un chemin qu'elles excluent.
- Les répertoires intégrés couvrent au minimum les métadonnées, outils et sorties générées courantes : `.git`, `.idea`, `.gradle`, `target`, `build`, `out`, `node_modules`, `dist`, `coverage` et équivalents explicitement configurés. Chaque entrée de `BUILT_IN_DIRECTORIES` est un nom d'un seul segment, exclu à toute profondeur.
- Les fichiers sensibles évidents (`.env`, clés privées, conteneurs de certificats) sont exclus par défaut ; `.nexusignore` permet au projet d'ajouter ses propres exclusions.
- `ProjectIgnoreMatcher` porte une liste `BUILT_IN_DIRECTORY_SEQUENCES` de suites de segments, distincte de `BUILT_IN_DIRECTORIES`, qui contient la seule suite `.claude`, `worktrees`. `isBuiltInExcluded` exclut tout chemin relatif au projet dont deux segments consécutifs, comparés en minuscules (`Locale.ROOT`), forment cette suite, quelle que soit leur profondeur : le répertoire `.claude/worktrees` et son contenu, à la racine comme dans un sous-module (`module/.claude/worktrees/…`).
- Cette suite ne concerne ni les autres chemins de `.claude/` (`.claude/skills/…`), ni les noms proches (`.claude/worktrees-notes.md`), ni un répertoire `worktrees` hors de `.claude` (`docs/worktrees/…`).

### Hors décision

L'exclusion d'autres outils (`.cursor/`, `.codex/`, `.aider*`) et une liste d'exclusions configurable ne sont pas décidées ; toute nouvelle suite de segments passe par un nouvel ADR. Aucune migration SQLite n'est requise pour une nouvelle exclusion : le prochain réindexage retire les fichiers déjà indexés.

## Do's and Don'ts

### Do

- Parser et évaluer les règles de type `.gitignore` avec JGit, et appliquer la même grammaire aux fichiers `.nexusignore`.
- Maintenir des règles scopées par répertoire : règles héritées des parents, puis `.gitignore` et `.nexusignore` du répertoire courant pour ses descendants.
- Appliquer les exclusions intégrées avant les fichiers d'ignore : `.git`, `.idea`, `.gradle`, `target`, `build`, `out`, `node_modules`, `dist`, `coverage` et équivalents configurés, fichiers sensibles évidents (`.env`, clés privées, conteneurs de certificats).
- Exclure tout chemin dont deux segments consécutifs sont `.claude` puis `worktrees`, sans casse et à toute profondeur, et exprimer toute exclusion intégrée de plusieurs segments dans `BUILT_IN_DIRECTORY_SEQUENCES`.
- Laisser les autres chemins de `.claude/` soumis aux règles d'ignore habituelles.

### Don't

- Ne pas utiliser JGit dans le scanner pour autre chose que la sémantique d'ignore, ni convertir les motifs vers `PathMatcher`, ni lancer `git`.
- Ne pas ajouter `worktrees` seul ni `.claude` seul à `BUILT_IN_DIRECTORIES`.
- Ne pas lire `.git/info/exclude` ni interroger `git worktree list` pour décider d'une exclusion.
- Ne pas permettre à une négation `.gitignore` ou `.nexusignore` de réintégrer un chemin d'une exclusion intégrée.

## Consequences

### Positives

- NEXUS ne réimplémente pas la grammaire Git ; `.nexusignore` est immédiatement compréhensible ; le scan fonctionne en Java pur, règles imbriquées comprises, avec une logique d'exclusion centralisée.
- Tout projet indexé avec des worktrees Claude Code est protégé de ces copies sans configuration, et l'index reste identique d'une machine à l'autre pour un même contenu de dépôt.

### Négatives

- JGit ajoute une dépendance de plusieurs mégaoctets pour une capacité ciblée, et le scope de chaque `IgnoreNode` doit être géré correctement.
- Les exclusions intégrées ne sont pas des règles Git : elles forment une couche supplémentaire, non annulable par le projet. Un projet qui versionnerait du contenu sous `.claude/worktrees` ne peut pas l'indexer ; la comparaison sans casse exclut aussi `.Claude/WorkTrees`.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Différence subtile avec le Git natif | Moyen | Tests de négations, `**` et fichiers imbriqués |
| Répertoire ignoré contenant une négation profonde | Moyen | Respecter la sémantique Git lors du choix de `SKIP_SUBTREE` |
| Sur-exclusion de fichiers légitimes | Moyen | Liste intégrée conservatrice et `.nexusignore` explicite |
| JGit utilisé hors de son rôle | Faible à moyen | Logique encapsulée dans le package du scanner, règle Archgate |
| Autre outil à répertoire de worktrees différent | Moyen | Hors périmètre ; un nouvel ADR ajoute la suite correspondante |

## Compliance and Enforcement

- Tests (`core`, lancés par `./mvnw clean install` sous Windows et Linux) dans `core/src/test/java/com/nexus/index/scan/` : `ProjectScannerTest` (`respectsGitignoreNexusignoreAndBuiltInExclusions`, `respectsGitignoreNegationRules`, `excludesClaudeWorktreesWithoutAnyIgnoreRule`, `claudeWorktreesExclusionKeepsTheRestOfClaudeAndNearbyNames`, `ignoreNegationCannotReincludeClaudeWorktrees`), `ProjectIgnoreMatcherBuiltInExclusionTest` (suite à toute profondeur, casse, noms voisins non exclus), `ProjectIgnoreMatcherBudgetTest`.
- `scripts/self-smoke.ps1`, étape 9 : le contexte du dépôt NEXUS retient son propre `AGENTS.md`.
- Règle Archgate `BE-0083-appliquer-des-regles-d-exclusion-du-scan-de-type-gitignore.rules.ts` / `scanner-ignore-rules-use-jgit` : dans `core/src/main/java/com/nexus/index/scan/` (commentaires et littéraux effacés), `ProjectIgnoreMatcher` importe `org.eclipse.jgit.ignore.IgnoreNode` ; aucun usage de JGit hors de `org.eclipse.jgit.ignore`, aucun `PathMatcher`, aucun lancement de processus.
- Revue : une nouvelle exclusion de plusieurs segments passe par `BUILT_IN_DIRECTORY_SEQUENCES` et par un ADR ; aucune lecture de configuration locale à la copie de travail n'entre dans `ProjectIgnoreMatcher`.

## Conditions de réexamen

La décision est réévaluée si JGit impose un niveau Java incompatible, si son API d'ignore devient instable ou si une bibliothèque plus légère offre une compatibilité Git mieux démontrée ; si Claude Code change l'emplacement de ses worktrees, si d'autres outils imposent des exclusions comparables ou si un besoin d'exclusions configurables apparaît. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Mise en œuvre

- `core/src/main/java/com/nexus/index/scan/ProjectIgnoreMatcher.java` : `IgnoreNode` scopés par répertoire, `BUILT_IN_DIRECTORIES`, `BUILT_IN_DIRECTORY_SEQUENCES`, `SENSITIVE_FILE_NAMES`, `SENSITIVE_SUFFIXES`, `isBuiltInExcluded`, `containsBuiltInDirectorySequence`.
- `core/src/main/java/com/nexus/index/scan/ProjectScanner.java` : élagage `SKIP_SUBTREE` d'un répertoire exclu avant lecture de ses fichiers d'ignore.
- Documentation technique : [indexing-pipeline.md](../../developer/indexing-pipeline.md).

## References

- [ARCH-0005](ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.md) — local-first et intégrations externes opt-in.
- OpenSpec : `openspec/changes/archive/2026-10-30-exclude-claude-worktrees-by-default/`, spécification `openspec/specs/project-indexing-exclusions/spec.md`.
- [arc42 §9 — Décisions](../arc42/09-decisions.md).
