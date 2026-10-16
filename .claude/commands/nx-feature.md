---
description: Démarre une nouvelle fonctionnalité NEXUS de bout en bout (plan d'architecture, branche, implémentation, tests, doc)
argument-hint: "<description de la fonctionnalité>"
allowed-tools: Bash(git status *) Bash(git fetch *) Bash(true)
---

!`git status --short --branch || true`

Fonctionnalité demandée : $ARGUMENTS

1. Lance l'agent `nexus-architect` pour obtenir le plan (fichiers, bornes, tests, doc, décision ADR) et présente-le moi de façon concise.
2. Après mon accord : crée une branche depuis `origin/develop` (skill `nexus-pr-workflow`), puis implémente en suivant le skill `nexus-new-capability`.
3. Fais écrire ou compléter les tests par l'agent `nexus-test-engineer`, puis relire par l'agent `nexus-reviewer`.
4. Termine par le skill `nexus-validate` en mode `full` et propose l'ouverture de la PR.
