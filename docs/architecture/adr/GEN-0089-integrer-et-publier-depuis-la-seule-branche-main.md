---
id: GEN-0089
title: "Intégrer et publier depuis la seule branche main"
domain: general
rules: false
---

# Intégrer et publier depuis la seule branche main

- **Statut** : Accepted
- **Date** : 2026-11-12
- **Origine** : demande du mainteneur du 2026-11-12 ; changement OpenSpec `flux-sur-main-seule`
- **Complète** : [GEN-0057](GEN-0057-tenir-le-registre-d-adr-au-format-archgate.md) (base de comparaison d'Archgate)
- **Remplacé par** : —

## Context

NEXUS a un seul mainteneur et un seul contributeur régulier. Le dépôt intègre chaque PR sur une branche d'intégration `develop`, puis promeut `develop` vers `main` par une PR dédiée avant chaque release ; la release est un tag `vX.Y.Z` sur un SHA de `main` (`scripts/release/validate-release-tag.sh`, [SEC-0068](SEC-0068-publier-les-releases-depuis-un-environnement-protege-avec-des-assets-attestes.md)).

Faits relevés le 2026-11-12 :

- la promotion FTurleque/nexus-context-engine#381 ne portait que deux PR documentaires et a demandé un tour complet des huit checks requis ;
- un correctif mergé directement sur `main` oblige à resynchroniser `main` vers `develop` avant la promotion suivante ;
- le ruleset 20503835 « Protect main » protège `main` (PR obligatoire, merge commit seul, ni suppression ni force-push, huit checks requis dont « Hermetic scale benchmark ») et ne cible plus `develop` ;
- dix-neuf workflows, Dependabot, la base Archgate, deux hooks Claude Code et la documentation courante supposent l'existence de `develop`.

Question : faut-il garder une branche d'intégration distincte de la branche de release ?

## Options envisagées

- **`develop` pour l'intégration, `main` pour la release** : sépare le code intégré du code publié ; double les tours de CI par release, ajoute une PR de promotion et un risque de divergence entre les deux branches, sans second relecteur pour en tirer parti.
- **Branche unique `main`, release par tag** (retenu) : chaque PR est qualifiée par les checks requis avant d'entrer sur `main`, les qualifications longues tournent après le merge sur `main`, et la release reste un geste explicite (tag) posé sur un SHA déjà qualifié.

## Decision

**Option retenue : branche unique `main`.**

1. `main` est la seule branche d'intégration et la seule source des releases. Toute branche de travail part de `origin/main` et revient par une PR vers `main`, intégrée par merge commit exact-head.
2. La protection de `main` (ruleset « Protect main ») porte les checks requis de toute PR ; les qualifications post-merge (benchmarks, corpus sémantique `full`, installateur, distribution Docker) se déclenchent sur un push de `main`.
3. Une release est un tag `vX.Y.Z` (ou `vX.Y.Z-rc.N`) posé par le mainteneur sur un SHA de `main`, après lecture des qualifications post-merge de ce SHA.
4. Dependabot cible `main` ; Archgate compare à `origin/main` (`.archgate/config.json`).
5. Aucune branche longue durée autre que `main` n'existe dans le dépôt.

Cette décision ne change ni les règles du ruleset, ni la politique des tags et des releases immuables, ni `release.yml`.

## Do's and Don'ts

### Do

- Partir de `origin/main` et ouvrir chaque PR vers `main`.
- Déclencher les qualifications post-merge sur un push de `main`.
- Lire les qualifications post-merge du SHA de `main` avant de poser un tag de release.

### Don't

- Ne pas recréer de branche d'intégration longue durée (`develop` ou autre).
- Ne pas faire cibler une autre branche que `main` par un workflow, Dependabot ou Archgate.
- Ne pas pousser directement sur `main`.

## Consequences

### Positives

- Une PR par changement, sans PR de promotion ni resynchronisation de branches.
- Un seul historique à lire : le SHA qualifié par les checks de PR est celui que les qualifications post-merge mesurent et que le tag publie.

### Négatives

- Une régression vue seulement par une qualification post-merge (corpus sémantique `full`, benchmark `ci`) arrive sur `main` avant d'être détectée ; elle se corrige par une PR, et la release attend que ces qualifications soient vertes.
- `main` contient du code non encore publié ; l'état publié se lit au dernier tag `v*`.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Release posée sur un SHA dont une qualification post-merge a échoué | Moyen | Lecture des runs du SHA avant le tag (skill `nexus-pr-workflow`, section « Préparer une release ») |
| Workflow qui cible encore une branche disparue et ne se déclenche plus | Moyen | Contrat `scripts/release/test-final-audit-contracts.sh` : aucune mention de `develop` dans `.github/` |

## Compliance and Enforcement

- Ruleset GitHub 20503835 « Protect main » : PR obligatoire, merge commit seul, checks requis (lecture `gh api`, `docs/developer/branch-governance.md`).
- Contrat `scripts/release/test-final-audit-contracts.sh` : aucune mention de la branche `develop` dans `.github/`.
- Contrat `scripts/release/test-operational-doc-contracts.sh` : Dependabot cible `main` sur chaque écosystème ; le run post-merge du benchmark d'échelle (`main-push-scale-benchmark.yml`) surveille exactement `WATCHED_PATHS`.
- Hook `.claude/hooks/guard-command.ps1` : push direct vers `main` refusé (tests `.claude/hooks/tests/Test-Hooks.ps1`).

## Mise en œuvre

- Déclencheurs : `.github/workflows/*.yml` (`pull_request` et `push` sur `main`, enveloppes `main-push-*.yml`).
- Configuration : `.github/dependabot.yml`, `.archgate/config.json`.
- Processus : skill `nexus-pr-workflow`, commande `/nx-release`, [`branch-governance.md`](../../developer/branch-governance.md), [`release-and-recovery.md`](../../developer/release-and-recovery.md).

## References

- [GEN-0057](GEN-0057-tenir-le-registre-d-adr-au-format-archgate.md) — registre des ADR et configuration Archgate.
- [BE-0090](BE-0090-garder-la-recherche-semantique-optionnelle-et-qualifiee-hors-release.md) — qualification sémantique complète après fusion sur `main`.
- [SEC-0068](SEC-0068-publier-les-releases-depuis-un-environnement-protege-avec-des-assets-attestes.md) — publication des releases depuis un environnement protégé.
