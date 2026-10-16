---
description: Diagnostique les checks CI en échec d'une PR NEXUS et propose ou applique le correctif
argument-hint: "<numéro de PR>"
allowed-tools: Bash(gh pr checks *) Bash(grep *) Bash(gh pr view *) Bash(true)
---

!`gh pr view $ARGUMENTS --json number,title,headRefName,headRefOid,mergeStateStatus --jq '"#\(.number) \(.title) [\(.headRefName) @ \(.headRefOid[0:8])] \(.mergeStateStatus)"' || true`

!`gh pr checks $ARGUMENTS 2>&1 | grep -v "	pass	" || true`

Applique le skill `nexus-ci-triage` à cette PR. Corrige ce qui est lié au changement et pousse sur la branche de la PR ; pour une cause préexistante sur `develop`, explique-la et propose la PR qui la débloque.
