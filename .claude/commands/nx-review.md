---
description: Fait relire le diff courant (ou une PR) par l'agent nexus-reviewer, et par nexus-security-auditor si des zones sensibles sont touchées
argument-hint: "[numéro de PR]"
allowed-tools: Bash(git diff *) Bash(gh pr diff *) Bash(true)
---

Fichiers concernés :

!`git diff --name-only origin/develop...HEAD || true`

Cible : `$ARGUMENTS` (PR si un numéro est donné, sinon la branche courante contre `origin/develop`, copie de travail incluse).

1. Lance l'agent `nexus-reviewer` sur cette cible.
2. Si des fichiers touchent `core/src/main/java/com/nexus/security/`, `context/`, `index/`, `adapters/rest-quarkus/`, `.github/workflows/`, `mvnw*` ou `config/`, lance en parallèle l'agent `nexus-security-auditor` sur ces fichiers.
3. Restitue un verdict consolidé, constats dédoublonnés et classés par sévérité. Ne corrige rien sans mon accord.
