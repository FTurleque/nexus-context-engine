---
description: Prépare une release depuis main (runs post-merge, PR de version, tag à poser)
allowed-tools: Bash(git fetch *) Bash(git log *) Bash(git describe *) Bash(gh run list *) Bash(gh pr list *) Bash(true)
---

!`git fetch -q --tags origin main || true`

Dernier tag de version :

!`git describe --tags --abbrev=0 --match 'v*' origin/main || true`

PR intégrées dans `main` depuis ce tag :

!`git log --oneline --merges --first-parent "$(git describe --tags --abbrev=0 --match 'v*' origin/main)"..origin/main || true`

Runs post-merge du HEAD de `main` :

!`gh run list --branch main --limit 20 --json workflowName,conclusion,status,headSha --jq '.[] | "\(.workflowName): \(.status) \(.conclusion) @\(.headSha[0:8])"' || true`

PR ouvertes vers `main` :

!`gh pr list --base main --state open || true`

Suis le skill `nexus-pr-workflow`, section « Préparer une release ». Si un run post-merge du HEAD de `main` est rouge ou en cours, arrête-toi et dis lequel. Propose la version suivante à partir des PR listées, prépare la PR de version des pom en français, et rappelle le tag que le mainteneur posera. Ne merge pas et ne pose aucun tag sans mon accord.
