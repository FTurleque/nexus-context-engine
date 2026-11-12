---
description: Bilan d'entretien du dépôt NEXUS (PR ouvertes, CI de main, contrats, dette signalée)
allowed-tools: Bash(git fetch *) Bash(gh pr list *) Bash(gh run list *) Bash(bash .claude/scripts/contracts.sh) Bash(true)
---

!`git fetch -q origin || true`

PR ouvertes :

!`gh pr list --state open --json number,title,author,mergeStateStatus --jq '.[] | "#\(.number) \(.mergeStateStatus) \(.author.login) \(.title)"' || true`

Derniers runs sur main :

!`gh run list --branch main --limit 12 --json workflowName,conclusion,status,headSha --jq '.[] | "\(.workflowName): \(.status) \(.conclusion) @\(.headSha[0:8])"' || true`

Contrats locaux :

!`bash .claude/scripts/contracts.sh 2>&1 || true`

Fais un bilan d'entretien priorisé : ce qui est rouge ou bloqué, PR à intégrer (agent `nexus-dependency-steward` pour Dependabot), contrats en échec, et les 3 actions les plus utiles à faire maintenant. N'exécute aucune action d'écriture sans mon accord.
