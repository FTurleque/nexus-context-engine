# Proposition

## Why

Le contrôle d'immuabilité ajouté au sprint 14 (code de sortie 79) arrive après la publication de la release GitHub. Or `gh release edit "$TAG" --draft=false` publie une version finale sans `--latest=false` : l'API GitHub applique alors `make_latest: true`, valeur par défaut pour une release nouvellement publiée. Une version finale non immuable reçoit donc le badge Latest de GitHub avant l'échec 79, alors que `docs/developer/release-and-recovery.md` affirme qu'après un code 79 « `latest` n'a pas bougé » (`AUD-DEP-38`). Seul le tag d'image GHCR `latest`, déplacé dans l'étape suivante, est réellement protégé. Le risque reste résiduel tant que le réglage « releases immuables » est actif (relevé du 2026-11-11 : `enabled: true`). Le critère de sortie du sprint 15 exige le comportement : « aucune version finale non immuable ne reçoit le badge Latest ».

Source : `docs/project/audits/2026-10-17/findings.json` (`AUD-DEP-38`, sprint 15), `docs/project/audits/2026-10-17/etat-2026-11-12.md` (section du constat et « Prochaines étapes », point 2), `docs/project/audits/2026-10-17/sprints.md` (S15, critère de sortie).

## What Changes

- `AUD-DEP-38` (Faible)
  - [Existant vérifié] `scripts/release/publish-github-release-assets.sh:65-77` : une pré-version est créée en brouillon avec `--prerelease --latest=false` (`:68`) ; une version finale est créée en brouillon sans aucun drapeau `--latest` (`CHANNEL_ARGS` vide, `:65`, `:69-71`).
  - [Existant vérifié] `scripts/release/publish-github-release-assets.sh:145` publie le brouillon par `gh release edit "$TAG" --draft=false`, sans `--latest=false`. Le contrôle d'immuabilité vient après : lecture du champ `immutable` (`:163-166`) puis sortie en 79 (`:167-170`).
  - [Existant vérifié] Documentation de l'API GitHub (« Update a release », paramètre `make_latest`) : « Defaults to `true` for newly published releases » ; les brouillons et les pré-versions ne peuvent pas être Latest. La page « Immutable releases » de GitHub précise que le titre, les notes, l'état pré-version et l'état Latest d'une release immuable restent modifiables après publication.
  - [Existant vérifié] `.github/workflows/release.yml:426-450` appelle le script ; l'étape suivante `:452-481` ne déplace que le tag d'image GHCR `latest`, et seulement pour une version finale (`:456-469`).
  - [Existant vérifié] `docs/developer/release-and-recovery.md:79` : « Si `scripts/release/publish-github-release-assets.sh` sort en code 79 (release publiée non immuable), `latest` n'a pas bougé ». Même ambiguïté dans `docs/developer/immutable-release-publishing.md:119` et `:166` (« avant le déplacement de `latest` »).
  - [Existant vérifié] Le faux `gh` de `scripts/release/test-publish-github-release-assets.sh` ignore `--latest=false` à la création (`:103-105`), n'accepte que `--draft=false` en édition (`:158-171`) et ne modélise pas `make_latest` : aucun cas ne détecte le badge posé à la publication.
  - [Écart] Le constat cite `:147` pour la publication et `:73-78` pour la création ; au commit `244ab1d3`, ce sont `:145` et `:72-77` (décalage de deux lignes). Le défaut est confirmé.
  - [Exigence] Une version finale n'est désignée Latest sur GitHub qu'après la lecture de `immutable: true`, avant le déplacement du tag d'image GHCR `latest`.
  - [Exigence] Une pré-version n'est jamais désignée Latest ; une version finale non immuable (code 79) ne l'est pas non plus.
  - [Exigence] La reprise d'une release publiée et immuable qui n'est pas encore Latest pose le badge sans autre modification ; si elle l'est déjà, la reprise reste sans mutation.
  - [Exigence] Le faux `gh` modélise `make_latest` (valeur par défaut de l'API à la publication) et les tests le vérifient.
  - [Décision] Corriger le pipeline plutôt que la documentation (voir design, « Décisions »).

## Type de changement

outillage (script de publication et son test) avec une part documentaire : profil court.

## Périmètre et exclusions

**Dans le périmètre :**
- `scripts/release/publish-github-release-assets.sh` : création et publication d'une version finale avec `--latest=false`, puis badge Latest posé après le contrôle d'immuabilité.
- `scripts/release/test-publish-github-release-assets.sh` : modélisation de `make_latest` dans le faux `gh` et nouveaux cas.
- `scripts/release/test-final-audit-contracts.sh` : contrat d'ordre (publication hors Latest, lecture `immutable`, pose du badge).
- `docs/developer/release-and-recovery.md`, `docs/developer/immutable-release-publishing.md`, `docs/developer/openspec-traceability.md` : distinction du badge Latest GitHub et du tag d'image `latest`, références `chemin:ligne` recalculées.

**Hors périmètre :**
- L'étape GHCR « Move latest only after immutable publication attestations and release assets » de `.github/workflows/release.yml` : inchangée ; elle s'exécute déjà après le script.
- Le réglage « releases immuables » et les rulesets de tags : traités par `AUD-DEP-37` (sprint 14).
- Le badge Latest actuellement porté par `v0.2.0` (non immuable, publiée avant le réglage) : aucun rattrapage ; il passera à la prochaine version finale immuable.
- Toute comparaison SemVer avant de poser le badge Latest ou le tag d'image `latest` : la relance d'une version finale plus ancienne repose les deux pointeurs sur elle (voir « Questions ouvertes »).

## Capabilities

### New Capabilities
<!-- Aucune. -->

### Modified Capabilities
- `release-pipeline` : ajout d'une exigence sur le moment où le badge Latest est posé ; modification de l'exigence « Les releases publiées sont immuables », dont le scénario « Release immuable » interdisait toute modification de la release alors que la pose du badge en est une.

## Impact

- Publication : `scripts/release/publish-github-release-assets.sh`, `scripts/release/test-publish-github-release-assets.sh`.
- Contrats : `scripts/release/test-final-audit-contracts.sh` (bloc « Sprint 9 : attest-prerelease », grep `--prerelease --latest=false` à conserver tel quel).
- Documentation : `docs/developer/release-and-recovery.md`, `docs/developer/immutable-release-publishing.md`, `docs/developer/openspec-traceability.md`.
- Aucune nouvelle permission : `gh release edit --latest` et `GET /repos/{owner}/{repo}/releases/latest` relèvent de `contents: write` / `contents: read`, déjà accordés au job `publish` (`.github/workflows/release.yml:171-172`).
- Aucun code Java, aucune migration SQLite, aucun ADR (voir design).

## Questions ouvertes

Aucune. Les deux questions de la première rédaction sont tranchées par décision du mainteneur du 2026-11-12 :

- [Décision] Relancer le job `publish` d'une version finale plus ancienne que la dernière publiée (par exemple `v0.2.1` après `v0.2.2`) repose le badge Latest sur cette version, comme l'étape GHCR repose déjà sans condition le tag d'image `latest`. Ce comportement, symétrique pour les deux pointeurs et limité à une relance manuelle, est accepté et documenté dans `docs/developer/release-and-recovery.md` (tâche 2.1). Aucune comparaison SemVer n'est faite avant de poser l'un ou l'autre.
- [Décision] Quand la relecture qui suit `gh release edit "$TAG" --latest` ne désigne pas le tag comme Latest, le script sort avec le code dédié `80`, distinct des codes existants (64, 65, 66, 69, 70, 73 à 79), et non avec le code générique 70, avec un message explicite qui nomme le tag.
