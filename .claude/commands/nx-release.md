---
description: Prépare la promotion develop vers main (vérification de divergence, PR de release)
allowed-tools: Bash(git fetch *) Bash(git rev-list *) Bash(git log *) Bash(gh pr list *) Bash(true)
---

!`git fetch -q origin main develop || true`

Commits non-merge présents sur `main` mais absents de `develop` :

!`git log --oneline --no-merges origin/develop..origin/main || true`

PR intégrées dans `develop` depuis `main` :

!`git log --oneline --merges --first-parent origin/main..origin/develop || true`

PR de release déjà ouvertes :

!`gh pr list --base main --state open || true`

Suis le skill `nexus-pr-workflow`, section « Promouvoir develop → main ». Si la première liste n'est pas vide, arrête-toi et propose d'abord la resynchronisation (section « Resynchroniser main → develop »). Rédige le corps de PR en français à partir des PR listées. Ne merge pas sans mon accord.
