---
id: SEC-0068
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
- **C — Environnement GitHub protégé, attestation de tous les assets, tags de pré-version, assets Windows non signés Authenticode** : la protection est un réglage du dépôt, vérifiable en lecture par `gh api`, hors du code des PR ; chaque asset est vérifiable gratuitement par `gh attestation verify` ; une pré-version permet une répétition réelle ; aucun certificat de signature de code payant. Coût : réglages manuels du mainteneur, une approbation à chaque release, et l'avertissement SmartScreen « éditeur inconnu » à l'installation sous Windows.
- **D — Signature par une clé gérée (Sigstore keyless signé hors GitHub, KMS)** : preuve indépendante de GitHub, mais outillage et gestion de clé disproportionnés pour un projet sans consommateur externe ; écarté par les non-objectifs du changement.
- **E — Option C avec signature Authenticode des assets Windows** : supprime l'avertissement SmartScreen, mais exige un certificat de signature de code payant ; depuis juin 2023 sa clé privée réside sur un matériel ou un service de signature distant, sans `.pfx` exportable. Écartée : le projet vise un coût nul.

## Decision

**Option retenue : C.**

1. **Aucun héritage de secrets (D1).** Aucun workflow ne transmet `secrets: inherit` ; un workflow réutilisable ne reçoit, par leur nom, que les secrets qu'il déclare. `release.yml` ne transmet aucun secret à `windows-installer.yml`, appelé avec `require_authenticode: false`.
2. **Environnement protégé `release` (D3).** Le job `publish` déclare `environment: release` : reviewer requis (le mainteneur), déploiement limité aux tags `v*`. Le job Windows n'entre dans `release` que s'il est appelé avec `require_authenticode: true`, ce que `release.yml` ne fait pas ; l'environnement ne porte aucun secret. Un ruleset de tags `refs/tags/v*` réserve création, mise à jour et suppression au mainteneur. Rien n'est écrit dans GHCR ni dans les releases avant l'approbation.
3. **Attestation de tous les assets (D4).** Avant `publish-github-release-assets.sh`, `actions/attest` (même SHA que pour l'image) atteste en `subject-path` chaque ZIP et `setup.exe` ; `gh attestation verify` les vérifie ensuite, et un échec arrête le job avant tout upload et avant `latest`.
4. **Assets Windows non signés.** Le ZIP et le `setup.exe` publiés ne portent pas de signature Authenticode ; leur provenance est l'attestation du point 3. La capacité de signature optionnelle de `windows-installer.yml` reste disponible pour une qualification manuelle, sans être utilisée par la release.
5. **Tags de pré-version (D9).** Le tag est `vX.Y.Z` ou `vX.Y.Z-rc.N`, égal à la version Maven et sur la tête de `main`. Une pré-version publie une release marquée pré-version, sous des tags d'image immuables, sans déplacer `latest`. La première exécution est une répétition sur `X.Y.Z-rc.1`, prouvée par un relevé daté sous `docs/project/history/`.

Hors décision : build unique et préflight GHCR (inchangés), confinement des secrets dans le job Windows (D2) et D5 à D8, durcissements sans ADR.

## Do's and Don'ts

### Do

- Transmettre à un workflow réutilisable, par leur nom, les seuls secrets qu'il déclare.
- Déclarer `environment: release` sur le job `publish`, avec un reviewer requis et une politique de déploiement limitée aux tags `v*`.
- Documenter pour l'utilisateur la vérification `gh attestation verify` des assets Windows, seule preuve de provenance en l'absence de signature Authenticode.
- Protéger `refs/tags/v*` par un ruleset de tags actif.
- Attester chaque ZIP et chaque `setup.exe` publiés par `actions/attest` en `subject-path`, et les vérifier par `gh attestation verify` avant tout upload.
- Accepter `vX.Y.Z-rc.N` égal à la version Maven et à la tête de `main`, publier une release marquée pré-version et laisser `latest` inchangé.
- Consigner la preuve de la répétition sur pré-version dans `docs/project/history/`.

### Don't

- Ne pas écrire `secrets: inherit` dans `.github/workflows/`.
- Ne pas exiger de signature Authenticode dans `release.yml` sans certificat ni décision qui remplace celle-ci.
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

- Chaque release attend une approbation du mainteneur, sur le job `publish`.
- Les assets Windows ne sont pas signés : SmartScreen affiche « éditeur inconnu » à l'installation.
- Les réglages (environnement, ruleset de tags) vivent hors du dépôt : ils ne sont vérifiables qu'en lecture par `gh api`.
- Une version `-rc` peut figurer sur `main` entre deux releases finales.
- Une pré-version publie dans le namespace GHCR réel.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Asset Windows remplacé entre la production et le téléchargement par l'utilisateur, sans signature Authenticode pour le détecter | Moyen | Attestation de provenance vérifiée avant l'upload ; vérification `gh attestation verify` documentée pour l'utilisateur ; empreintes `.sha256` publiées |
| Environnement `release` absent au premier run : GitHub le crée sans protection | Élevé | Environnement et ruleset de tags créés et relevés par `gh api` avant la fusion de la mise en œuvre |
| Réglage GitHub supprimé ou modifié hors revue | Élevé | Lecture `gh api …/environments` et `…/rulesets` à chaque répétition et dans le relevé de preuve |
| Approbation par le seul mainteneur | Moyen | La protection vise l'exécution non sollicitée, pas la revue à deux ; modèle solo de `docs/developer/branch-governance.md` |
| Pré-version prise pour une version finale | Faible | Release marquée pré-version, `latest` jamais déplacé, tags d'image distincts |

## Compliance and Enforcement

- Contrat de `bash .claude/scripts/contracts.sh` (`scripts/release/test-final-audit-contracts.sh`, bloc « Sprint 9 : adr-publication ») : cet ADR existe, complète GEN-0031, et `docs/developer/immutable-release-publishing.md` le référence.
- Contrats du changement `sprint-09-chaine-de-release-executable` dans le même script : refus de `secrets: inherit` (tâche 1.2), `environment: release` sur `publish` et appel du job Windows sans secret et avec `require_authenticode: false` (tâche 3.2), `subject-path` et vérification des assets avant upload (tâche 4.1) ; acceptation et refus des tags de pré-version par `scripts/release/test-release-tag-policy.sh` (tâche 9.1).
- Réglages GitHub : lecture `gh api repos/FTurleque/nexus-context-engine/environments` (environnement `release`, reviewer requis) et `gh api repos/FTurleque/nexus-context-engine/rulesets` (ruleset de cible `tag` actif) ; aucun contrôle automatique.
- Revue : toute modification de `.github/workflows/release.yml` ou `windows-installer.yml` vérifie qu'aucun secret n'est transmis hors déclaration, que `environment: release` reste sur `publish`, et que la vérification des attestations précède l'upload.
- Aucune règle Archgate compagnon (`rules: false`).

## Conditions de réexamen

La décision est réévaluée si : le projet dispose d'un moyen de signature Authenticode sans coût, le projet a des consommateurs externes qui exigent une signature indépendante de GitHub, un second mainteneur permet une revue à deux sur l'environnement, GitHub retire ou change les attestations d'artefacts, ou la chaîne publie vers un autre registre que GHCR. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Mise en œuvre

- Release : `release.yml` appelle `windows-installer.yml` avec `require_authenticode: false` et sans secret ; le job Windows s'exécute hors environnement et produit des assets non signés.
- Signature optionnelle de `windows-installer.yml` (qualification manuelle) : secrets mappés sur les seules étapes « Build self-contained Windows distribution » et « Build production installer », hors `pull_request` ; `sign-windows-artifact.ps1` décode le PFX dans un répertoire propre à l'appel et l'efface dans un `finally` ; `environment.name` conditionnel à `require_authenticode`. L'installeur de smoke n'est jamais signé.
- Attestations : `actions/attest` en `subject-path` sur le ZIP et le `setup.exe`, exactement un de chaque, puis `gh attestation verify` bloquant avant l'upload et avant `latest`.
- Preuves de la répétition sur tag de pré-version : [relevé du 2026-11-08](../../project/history/release-rehearsal-0.2.0-rc.2-2026-11-08.md) (`v0.2.0-rc.2`, attestations vérifiées sur l'image, le ZIP et le `setup.exe`).

## References

- [GEN-0031](GEN-0031-packager-la-cli-dans-un-jar-autonome.md) — packaging de la CLI, complété par cet ADR.
- Changement OpenSpec archivé `openspec/changes/archive/2026-11-08-sprint-09-chaine-de-release-executable/` (`design.md`, D1, D3, D4, D9) ; spec de référence `openspec/specs/release-pipeline/spec.md`.
- Audit `docs/project/audits/2026-10-17/sprints.md`, section S9.
- [`docs/developer/immutable-release-publishing.md`](../../developer/immutable-release-publishing.md), [`docs/developer/release-and-recovery.md`](../../developer/release-and-recovery.md), [`docs/developer/branch-governance.md`](../../developer/branch-governance.md).
- Section arc42 [09 — Décisions](../arc42/09-decisions.md).
- GitHub : environnements de déploiement, rulesets de tags, attestations d'artefacts (`actions/attest`, `gh attestation verify`).
