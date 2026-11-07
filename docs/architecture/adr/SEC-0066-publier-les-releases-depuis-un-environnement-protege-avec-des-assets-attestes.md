---
id: SEC-0066
title: "Publier les releases depuis un environnement protégé, avec des assets attestés et des tags de pré-version"
domain: security
rules: false
---

# Publier les releases depuis un environnement protégé, avec des assets attestés et des tags de pré-version

- **Statut** : Proposed
- **Date** : 2026-11-07
- **Origine** : sprint 9 de l'audit de code du 17/10/2026, constats `AUD-DEP-21`, `AUD-DEP-03`, `AUD-DEP-09` et `AUD-DEP-02`, changement OpenSpec `sprint-09-chaine-de-release-executable` (décisions D1, D3, D4 et D9 de son `design.md`)
- **Complète** : [GEN-0031](GEN-0031-packager-la-cli-dans-un-jar-autonome.md)

## Context

GEN-0031 décide ce que le build produit (JAR standard et JAR autonome `-cli`) ; aucune décision ne fixe comment ces livrables, la distribution ZIP, l'installeur Windows et l'image GHCR sont publiés. Le contrat de publication immuable courant est décrit par `docs/developer/immutable-release-publishing.md` : build unique de l'image, préflight fail-closed, tags version et SHA immuables, `latest` déplacé en dernier.

Faits relevés sur `origin/develop` le 2026-11-07 :

- `.github/workflows/release.yml:62` appelle `windows-installer.yml` avec `secrets: inherit` : tout secret du dépôt est transmis au build Windows (`AUD-DEP-21`).
- Le job `publish` (`.github/workflows/release.yml:150`) ne déclare aucune clé `environment:` ; `gh api repos/FTurleque/nexus-context-engine/environments` renvoie `total_count` = 0, et le seul ruleset actif, `Protect main & develop`, cible des branches : aucune règle ne porte sur `refs/tags/v*` (`AUD-DEP-03`).
- Les deux `actions/attest` du job `publish` (`.github/workflows/release.yml:354` et `:361`) portent sur le digest de l'image ; le ZIP et le `setup.exe` Windows ne sont accompagnés que de fichiers `.sha256` publiés au même endroit qu'eux (`AUD-DEP-09`).
- `scripts/release/validate-release-tag.sh:9` n'accepte que `vX.Y.Z` ; `gh run list --workflow release.yml` ne renvoie aucun run et `gh release list` aucune release (`AUD-DEP-02`). La chaîne n'a jamais été exécutée et ne peut pas être répétée sans publier une version finale.

Ce qui doit être préservé : un seul workflow publie (`release.yml`), l'image qualifiée est publiée sans rebuild, les tags version et SHA ne sont jamais déplacés, la publication reste fail-closed.

La question est : **sous quelles conditions la chaîne de release est-elle autorisée à publier, et quelles preuves accompagnent chaque asset publié ?**

## Options envisagées

- **A — Statu quo** : secrets de dépôt hérités, publication sans approbation, attestation de l'image seule, tags `vX.Y.Z` seuls. Aucun coût, mais un tag poussé suffit à publier, chaque workflow appelé voit tous les secrets, les assets Windows n'ont qu'une empreinte co-localisée, et la première exécution de la chaîne est forcément une version finale.
- **B — Garde-fous dans le seul workflow** : conditions `if:` sur l'acteur ou sur la branche, secrets nommés transmis explicitement. Réduit l'exposition des secrets, mais la protection reste modifiable par n'importe quelle PR qui touche le workflow et aucun réglage GitHub ne l'atteste.
- **C — Environnement GitHub protégé, secrets portés par l'environnement, attestation de tous les assets, tags de pré-version** : la protection est un réglage du dépôt, vérifiable en lecture par `gh api`, hors du code des PR ; chaque asset est vérifiable par `gh attestation verify` ; une pré-version permet une répétition réelle. Coût : réglages manuels du mainteneur et deux approbations à chaque release (signature Windows, puis publication).
- **D — Signature par une clé gérée (Sigstore keyless signé hors GitHub, KMS)** : preuve indépendante de GitHub, mais outillage et gestion de clé disproportionnés pour un projet sans consommateur externe ; écarté par les non-objectifs du changement.

## Decision

**Option retenue : C.**

1. **Aucun héritage de secrets (D1).** Aucun workflow ne transmet `secrets: inherit`. `release.yml` transmet par leur nom les deux secrets PFX que déclare `windows-installer.yml` ; GitHub les résout vers les secrets de l'environnement `release` du job appelé.
2. **Environnement protégé `release` (D3).** Le job `publish` et la signature de release (job Windows appelé avec `require_authenticode: true`, ou job de signature dédié) déclarent `environment: release` : reviewer requis (le mainteneur), déploiement limité aux tags `v*`, secrets PFX portés par l'environnement. Un ruleset de tags `refs/tags/v*` réserve création, mise à jour et suppression au mainteneur. Rien n'est écrit dans GHCR ni dans les releases avant l'approbation.
3. **Attestation de tous les assets (D4).** Avant `publish-github-release-assets.sh`, `actions/attest` (même SHA que pour l'image) atteste en `subject-path` chaque ZIP et `setup.exe` ; `gh attestation verify` les vérifie ensuite, et un échec arrête le job avant tout upload et avant `latest`.
4. **Tags de pré-version (D9).** Le tag est `vX.Y.Z` ou `vX.Y.Z-rc.N`, égal à la version Maven et sur la tête de `main`. Une pré-version publie une release marquée pré-version, sous des tags d'image immuables, sans déplacer `latest`. La première exécution est une répétition sur `X.Y.Z-rc.1`, prouvée par un relevé daté sous `docs/project/history/`.

Hors décision : build unique et préflight GHCR (inchangés), confinement des secrets dans le job Windows (D2) et D5 à D8, durcissements sans ADR.

## Do's and Don'ts

### Do

- Transmettre à un workflow réutilisable, par leur nom, les seuls secrets qu'il déclare, y compris quand ils n'existent que dans l'environnement `release`.
- Déclarer `environment: release` sur le job `publish` et sur la signature de release.
- Garder les secrets PFX Authenticode comme secrets de l'environnement `release`, avec un reviewer requis et une politique de déploiement limitée aux tags `v*`.
- Protéger `refs/tags/v*` par un ruleset de tags actif.
- Attester chaque ZIP et chaque `setup.exe` publiés par `actions/attest` en `subject-path`, et les vérifier par `gh attestation verify` avant tout upload.
- Accepter `vX.Y.Z-rc.N` égal à la version Maven et à la tête de `main`, publier une release marquée pré-version et laisser `latest` inchangé.
- Consigner la preuve de la répétition sur pré-version dans `docs/project/history/`.

### Don't

- Ne pas écrire `secrets: inherit` dans `.github/workflows/`.
- Ne pas publier dans GHCR ni dans les releases sans l'approbation de l'environnement `release`.
- Ne pas téléverser un asset dont l'attestation n'a pas été vérifiée.
- Ne pas déplacer `latest` pour un tag de pré-version.
- Ne pas déplacer ni réutiliser un tag d'image version ou SHA, pré-version comprise.

## Consequences

### Positives

- Un tag poussé par un autre acteur ou par un jeton compromis ne publie rien sans approbation ; les tags `v*` ne sont créés que par le mainteneur.
- La clé de signature n'est lisible que dans un run approuvé de l'environnement `release`.
- Le consommateur vérifie l'image, le ZIP et le `setup.exe` par la même commande, `gh attestation verify`, qui désigne le workflow `Release` du dépôt.
- La chaîne peut être exécutée de bout en bout avant la première version finale.

### Négatives

- Chaque release attend deux approbations du mainteneur : le job Windows signé et le job `publish` déclarent tous deux `release`.
- Les réglages (environnement, secrets d'environnement, ruleset de tags) vivent hors du dépôt : ils ne sont vérifiables qu'en lecture par `gh api`.
- Une version `-rc` peut figurer sur `main` entre deux releases finales.
- Une pré-version publie dans le namespace GHCR réel.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| L'environnement conditionnel n'est pas exprimable dans le workflow réutilisable Windows | Moyen | Le job `windows-installer` déclare `environment.name: ${{ inputs.require_authenticode && 'release' \|\| '' }}` (tâche 3.2) ; le run sans environnement, pour un nom évalué vide, reste à constater en CI. Repli : job de signature dédié dans `release.yml` |
| Environnement `release` absent au premier run : GitHub le crée sans protection | Élevé | Environnement et ruleset de tags créés et relevés par `gh api` avant la fusion de la mise en œuvre |
| Réglage GitHub supprimé ou modifié hors revue | Élevé | Lecture `gh api …/environments` et `…/rulesets` à chaque répétition et dans le relevé de preuve |
| Approbation par le seul mainteneur | Moyen | La protection vise l'exécution non sollicitée, pas la revue à deux ; modèle solo de `docs/developer/branch-governance.md` |
| Pré-version prise pour une version finale | Faible | Release marquée pré-version, `latest` jamais déplacé, tags d'image distincts |

## Compliance and Enforcement

- Contrat de `bash .claude/scripts/contracts.sh` (`scripts/release/test-final-audit-contracts.sh`, bloc « Sprint 9 : adr-publication ») : cet ADR existe, complète GEN-0031, et `docs/developer/immutable-release-publishing.md` le référence.
- Contrats du changement `sprint-09-chaine-de-release-executable` dans le même script : refus de `secrets: inherit` (tâche 1.2), `environment: release` sur `publish` et environnement conditionnel du job Windows (tâche 3.2), `subject-path` et vérification des assets avant upload (tâche 4.1) ; acceptation et refus des tags de pré-version par `scripts/release/test-release-tag-policy.sh` (tâche 9.1).
- Réglages GitHub : lecture `gh api repos/FTurleque/nexus-context-engine/environments` (environnement `release`, reviewer requis) et `gh api repos/FTurleque/nexus-context-engine/rulesets` (ruleset de cible `tag` actif) ; aucun contrôle automatique.
- Revue : toute modification de `.github/workflows/release.yml` ou `windows-installer.yml` vérifie qu'aucun secret n'est transmis hors déclaration, que `environment: release` reste sur `publish`, et que la vérification des attestations précède l'upload.
- Aucune règle Archgate compagnon (`rules: false`).

## Conditions de réexamen

La décision est réévaluée si : le projet a des consommateurs externes qui exigent une signature indépendante de GitHub, un second mainteneur permet une revue à deux sur l'environnement, GitHub retire ou change les attestations d'artefacts, ou la chaîne publie vers un autre registre que GHCR. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Mise en œuvre

- Secrets : `release.yml` transmet les deux secrets PFX par leur nom à `windows-installer.yml` ; ils ne sont mappés que sur les étapes « Build self-contained Windows distribution » et « Build production installer », hors `pull_request`. `sign-windows-artifact.ps1` décode le PFX dans un répertoire propre à l'appel et l'efface dans un `finally` ; les scripts de build retirent les variables de signature de l'environnement du processus avant Maven, jpackage et ISCC. L'installeur de smoke n'est jamais signé.
- Environnement : `environment.name` conditionnel à `require_authenticode` sur le job Windows plutôt qu'un job de signature dédié, la signature de l'installeur dépendant de `nexus.exe` signé dans le même job.
- Attestations : `actions/attest` en `subject-path` sur le ZIP et le `setup.exe`, exactement un de chaque, puis `gh attestation verify` bloquant avant l'upload et avant `latest`.
- Preuves de la répétition sur tag de pré-version : à produire (tâches 9.3 et 9.4 du changement).

## References

- [GEN-0031](GEN-0031-packager-la-cli-dans-un-jar-autonome.md) — packaging de la CLI, complété par cet ADR.
- Changement OpenSpec `openspec/changes/sprint-09-chaine-de-release-executable/` (`design.md`, D1, D3, D4, D9 ; spec `release-pipeline`).
- Audit `docs/project/audits/2026-10-17/sprints.md`, section S9.
- [`docs/developer/immutable-release-publishing.md`](../../developer/immutable-release-publishing.md), [`docs/developer/release-and-recovery.md`](../../developer/release-and-recovery.md), [`docs/developer/branch-governance.md`](../../developer/branch-governance.md).
- Section arc42 [09 — Décisions](../arc42/09-decisions.md).
- GitHub : environnements de déploiement, rulesets de tags, attestations d'artefacts (`actions/attest`, `gh attestation verify`).
