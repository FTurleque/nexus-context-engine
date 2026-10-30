---
name: nexus-pr-workflow
description: Cycle de vie Git/GitHub de NEXUS — branche depuis develop, PR vers develop, checks obligatoires, merge exact-head en merge commit, gestion des PR Dependabot, promotion develop vers main et resynchronisation quand main contient des commits absents de develop. Utiliser pour ouvrir, mettre à jour, intégrer ou promouvoir des PR.
argument-hint: "[open|merge <pr>|promote|sync]"
---

# Workflow PR NEXUS

## Modèle

- `develop` = intégration, `main` = release. Les deux sont protégées par le ruleset « Protect main & develop » : PR obligatoire, pas de force-push ni de suppression, checks obligatoires.
- Mainteneur unique : pas de seconde approbation humaine requise, pas d'obligation d'être à jour avec la base avant merge.
- Intégration par **merge commit** (`--merge`), jamais squash/rebase, toujours **exact-head** (`--match-head-commit`).

Checks obligatoires sur `develop` : `Windows gate`, `Linux reactor Maven build`, `CodeQL Java analysis`, `OSV new-vulnerability delta gate / osv-scan`, `Build aggregate reactor SBOM`, `OSV aggregate SBOM vulnerability gate / osv-scan`, `SonarCloud Code Analysis`, et `Hermetic scale benchmark` une fois ajouté au ruleset par le mainteneur (produit sur toute PR, succès sans mesure hors des chemins surveillés ; avant cet ajout, un rouge reste bloquant en revue). `Real Ollama semantic retrieval` est long et non obligatoire.

## Ouvrir

1. `git fetch origin` puis `git switch -c <type>/<sujet> origin/develop` (types : `feat`, `fix`, `refactor`, `docs`, `chore`, `deps`, `ci`, `test`).
2. Commits au format conventionnel en anglais (`fix: ...`, `chore(deps): ...`), corps expliquant le pourquoi.
3. Valider (skill `nexus-validate`), pousser la branche, `gh pr create --base develop` avec un corps en français : résumé, détails, validation réellement exécutée.

## Merger

1. `gh pr checks <n>` : tous les checks obligatoires `pass` sur le HEAD courant.
2. `sha=$(gh pr view <n> --json headRefOid --jq .headRefOid)`
3. `gh pr merge <n> --merge --match-head-commit "$sha"` (le hook refuse un merge sans `--match-head-commit`).
4. Après plusieurs merges de dépendances, vérifier la CI push de `develop` qui combine l'ensemble.

## PR Dependabot

- Une CVE sur `develop` fait échouer OSV/Trivy sur toutes les PR : merger d'abord la PR corrective, puis commenter `@dependabot rebase` sur les autres (impossible si quelqu'un a poussé sur la branche → `gh pr update-branch <n>`).
- Pousser sur une branche Dependabot est permis (elle ne la gère plus ensuite) ; ne jamais la force-pusher : ouvrir une PR de remplacement et fermer l'originale.
- Détails par écosystème : skill `nexus-dependency-bump`.
- SonarCloud « analysis failed/cancelled » sans autre cause : relancer par un nouveau push (un commit vide suffit).

## Promouvoir develop → main

1. Vérifier la divergence : `git rev-list --count --no-merges origin/develop..origin/main` doit valoir 0 (sinon, section suivante d'abord).
2. `gh pr create --base main --head develop --title "release: promote develop to main"` ; corps : PR intégrées, dépendances, validation, rappel que la preuve est la CI exact-head de cette PR.
3. Merger exact-head une fois verte ; la release se fait ensuite par tag `vX.Y.Z` sur le SHA de `main` (`scripts/release/validate-release-tag.sh`).

## Resynchroniser main → develop

Quand des PR ont été mergées directement sur `main`, la promotion suivante est en conflit et `develop` ne peut pas recevoir de push direct :

1. `git switch -c sync/main-into-develop origin/develop`, `git fetch origin main`, `git merge --no-ff FETCH_HEAD`.
2. Pour chaque conflit, vérifier si `develop` contient déjà l'équivalent (souvent réimplémenté sous d'autres SHA) : `git cherry -v origin/develop origin/main`, recherche des identifiants de `main` dans `develop`. Garder la version la plus récente et réintroduire seulement ce qui manque vraiment.
3. Adapter les tests de `main` aux refactorings de `develop` (classes déplacées), supprimer les doublons.
4. Validation complète, PR vers `develop`, merge ; la PR de promotion devient alors mergeable.
