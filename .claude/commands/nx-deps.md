---
description: Passe en revue les PR Dependabot ouvertes et propose un plan d'intégration ordonné
allowed-tools: Bash(gh pr list *) Bash(true)
---

!`gh pr list --state open --author app/dependabot --json number,title,mergeStateStatus --jq '.[] | "#\(.number) \(.mergeStateStatus) \(.title)"' || true`

Lance l'agent `nexus-dependency-steward` sur ces PR. Présente son plan (catégories, ordre, pièges détectés), puis attends mon accord avant tout rebase, push ou merge. Pour l'exécution, suis les skills `nexus-dependency-bump` et `nexus-pr-workflow`.
