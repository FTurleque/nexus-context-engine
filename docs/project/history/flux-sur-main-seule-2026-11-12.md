# Branche unique main : suppression de develop et ruleset « Protect main » (2026-11-12)

Relevé des tâches 4.1 et 4.2 du changement OpenSpec `flux-sur-main-seule` ([GEN-0089](../../architecture/adr/GEN-0089-integrer-et-publier-depuis-la-seule-branche-main.md)) : état des branches et du ruleset de `main`, lu par `git` et `gh api` le 2026-11-12 à 12:25 UTC, après le merge de FTurleque/nexus-context-engine#382 (merge commit `6935f3a0`).

## Branches (tâche 4.1)

Avant suppression, `develop` ne contenait aucun commit absent de `main` (`git rev-list --count origin/main..origin/develop` = 0) et aucune PR ouverte ne la visait (`gh pr list --base develop --state open` vide).

```text
$ git ls-remote --heads origin
6935f3a0fd1bc2280f5a8c896dab670c67fb37e7	refs/heads/main
```

La branche locale `develop` est supprimée ; `main` est la seule branche locale.

## Ruleset « Protect main » (tâche 4.2)

```text
$ gh api repos/FTurleque/nexus-context-engine/rulesets/20503835 --jq '{name, enforcement, target, include: .conditions.ref_name.include, rules: [.rules[].type], merge: (.rules[]|select(.type=="pull_request").parameters.allowed_merge_methods), checks: [.rules[]|select(.type=="required_status_checks").parameters.required_status_checks[].context]}'
{"checks":["Windows gate","Linux reactor Maven build","CodeQL Java analysis","OSV new-vulnerability delta gate / osv-scan","Build aggregate reactor SBOM","OSV aggregate SBOM vulnerability gate / osv-scan","SonarCloud Code Analysis","Hermetic scale benchmark"],"enforcement":"active","include":["~DEFAULT_BRANCH"],"merge":["merge"],"name":"Protect main","rules":["deletion","non_fast_forward","pull_request","required_status_checks"],"target":"branch"}
$ gh api repos/FTurleque/nexus-context-engine --jq '{default_branch, allow_squash_merge, allow_rebase_merge, allow_merge_commit}'
{"allow_merge_commit":true,"allow_rebase_merge":false,"allow_squash_merge":false,"default_branch":"main"}
```

Scénarios couverts : `repository-governance › Seul le merge commit est accepté › Lecture du ruleset` (`allowed_merge_methods` = `["merge"]`) et `scale-benchmark-gate › Le check est requis sur main › Lecture du ruleset` (« Hermetic scale benchmark » dans `required_status_checks`).

## Écart relevé

Sur le HEAD de la PR #382, deux runs non requis ont été annulés : « Main Push Windows Installer » et « Scanner Corpus Benchmark ». Quand une PR modifie à la fois une enveloppe `main-push-*.yml` et son workflow appelé, les deux runs partagent le groupe de concurrence du workflow appelé (`windows-installer-<PR>`, `scanner-corpus-<PR>`), qui ne contient pas `github.workflow`, et l'un annule l'autre. Chaque qualification a réussi une fois sur ce HEAD (« Windows self-contained installer », « benchmark / Bounded scanner corpus benchmark »).
