---
name: nexus-ci-triage
description: Diagnostiquer et corriger les échecs de CI d'une PR NEXUS — GitHub Actions (Windows gate, reactor Linux, CodeQL, OSV, SBOM, Docker, installer) et SonarCloud — en extrayant la cause racine des logs. Utiliser quand une PR ou un workflow est rouge.
argument-hint: "<numéro de PR ou id de run>"
---

# Triage CI NEXUS

1. Déléguer l'analyse à l'agent `nexus-ci-triage` avec le numéro de PR ou l'id de run (il garde les logs hors du contexte principal et connaît les causes récurrentes).
2. Pour chaque cause racine retournée :
   - **liée au changement** : corriger sur la branche de la PR, valider localement (skill `nexus-validate`, mode quick puis full si nécessaire), pousser ;
   - **préexistante sur develop** (CVE, test instable) : corriger dans la PR qui la débloque ou dans une PR dédiée, puis mettre à jour les autres PR (`@dependabot rebase` / `gh pr update-branch`).
3. Ne jamais : abaisser un seuil ou un budget pour faire passer un check, désactiver un test, contourner un hook (`--no-verify`), force-pusher.
4. Rapporter : check, cause, correctif, SHA poussé, checks encore en cours.

Extraction manuelle si besoin :

```bash
gh run view <run> --log-failed > "$TMPDIR/run.log"
grep -nE '##\[error\]|\[ERROR\]|<<< FAILURE|GHSA-|CVE-|Total .* vulnerabilities' "$TMPDIR/run.log" | cut -c1-300 | head -40
```
