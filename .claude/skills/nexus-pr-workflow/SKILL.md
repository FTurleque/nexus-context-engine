---
name: nexus-pr-workflow
description: Cycle de vie Git/GitHub de NEXUS — branche depuis main, PR vers main, checks obligatoires, merge exact-head en merge commit, gestion des PR Dependabot et préparation d'une release par tag sur main. Utiliser pour ouvrir, mettre à jour, intégrer des PR ou préparer une release.
argument-hint: "[open|merge <pr>|release]"
---

# Workflow PR NEXUS

## Modèle

- `main` est la seule branche d'intégration et la source des releases (GEN-0089). Elle est protégée par le ruleset « Protect main » : PR obligatoire, pas de force-push ni de suppression, checks obligatoires.
- Mainteneur unique : pas de seconde approbation humaine requise, pas d'obligation d'être à jour avec la base avant merge.
- Intégration par **merge commit** (`--merge`), jamais squash/rebase, toujours **exact-head** (`--match-head-commit`). GitHub porte la règle : le ruleset n'autorise que `allowed_merge_methods = ["merge"]` et le dépôt désactive squash et rebase (`allow_squash_merge`, `allow_rebase_merge` à `false`) ; ces réglages appartiennent au mainteneur et se vérifient en lecture (`docs/developer/branch-governance.md`, section « Méthode d'intégration »). Ne jamais proposer `--squash` ni `--rebase`, même si l'interface GitHub les offre.
- Fichiers sensibles (`.github/workflows/`, `scripts/release/`, `config/tool-integrity.properties`, wrappers Maven, `packaging/`) : propriétaire déclaré dans `.github/CODEOWNERS`, revue code owner non obligatoire.
- Vulnérabilité découverte : signalement privé GitHub selon `SECURITY.md`, jamais d'issue ni de PR publique qui la décrit avant la release corrigée.

Checks obligatoires sur `main` : `Windows gate`, `Linux reactor Maven build`, `CodeQL Java analysis`, `OSV new-vulnerability delta gate / osv-scan`, `Build aggregate reactor SBOM`, `OSV aggregate SBOM vulnerability gate / osv-scan`, `SonarCloud Code Analysis` et `Hermetic scale benchmark` (produit sur toute PR, succès sans mesure hors des chemins surveillés). `Real Ollama semantic retrieval` est long et non obligatoire.

## Ouvrir

1. `git fetch origin` puis `git switch -c <type>/<sujet> origin/main` (types : `feat`, `fix`, `refactor`, `docs`, `chore`, `deps`, `ci`, `test`).
2. Commits au format conventionnel en anglais (`fix: ...`, `chore(deps): ...`), corps expliquant le pourquoi.
3. Valider (skill `nexus-validate`), pousser la branche, puis, dans un appel séparé du `git push` (le hook lit la ligne du push jusqu'au bout), `gh pr create --base main` avec un corps en français : résumé, détails, validation réellement exécutée.

## Merger

1. `gh pr checks <n>` : tous les checks obligatoires `pass` sur le HEAD courant.
2. `sha=$(gh pr view <n> --json headRefOid --jq .headRefOid)`
3. `gh pr merge <n> --merge --match-head-commit "$sha"` (le hook refuse un merge sans `--match-head-commit`).
4. Après le merge, les qualifications post-merge (`main-push-*.yml`, corpus sémantique `full`, Docker) tournent sur le push de `main` ; les lire avant une release.

## PR Dependabot

- Une CVE sur `main` fait échouer OSV/Trivy sur toutes les PR : merger d'abord la PR corrective, puis commenter `@dependabot rebase` sur les autres (impossible si quelqu'un a poussé sur la branche → `gh pr update-branch <n>`).
- Pousser sur une branche Dependabot est permis (elle ne la gère plus ensuite) ; ne jamais la force-pusher : ouvrir une PR de remplacement et fermer l'originale.
- Détails par écosystème : skill `nexus-dependency-bump`.
- SonarCloud « analysis failed/cancelled » sans autre cause : relancer par un nouveau push (un commit vide suffit).

## Préparer une release

1. Lire les runs post-merge du HEAD de `main` : `gh run list --branch main --limit 20` (tous verts, dont le dernier corpus sémantique `full`).
2. PR vers `main` qui fixe la version des pom (`X.Y.Z`, ou `X.Y.Z-rc.N` pour une pré-version) et la documentation courante qui la cite ; merge exact-head.
3. Le mainteneur pose le tag annoté `vX.Y.Z` sur le SHA de `main` issu de ce merge, le pousse et approuve l'environnement `release` (`scripts/release/validate-release-tag.sh`, `docs/developer/release-and-recovery.md`).
