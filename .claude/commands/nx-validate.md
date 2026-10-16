---
description: Valide l'itération courante (contrats, build Maven complet, self-smoke) ou en mode rapide les tests ciblés
argument-hint: "[quick|full]"
allowed-tools: Bash(git status *) Bash(git diff *) Bash(bash .claude/scripts/contracts.sh) Bash(true)
---

Fichiers modifiés par rapport à `origin/develop` :

!`git diff --stat origin/develop...HEAD || true`

!`git status --short || true`

Applique le skill `nexus-validate` en mode `$ARGUMENTS` (mode `full` si vide). En mode `quick`, choisis les tests ciblés d'après les fichiers modifiés ci-dessus. Lance le build long en arrière-plan. Termine par un compte rendu exact : commandes lancées, résultats, et ce qui n'a pas été vérifié.
