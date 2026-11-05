# Proposal

## Why

Le sprint 9 de l'audit de code du 17/10/2026 (`docs/project/audits/2026-10-17/sprints.md`, section S9) rend la chaîne de release exécutable. Le workflow `Release` n'a jamais tourné : avant de le lancer une première fois, on corrige ce qu'il manipule (secrets, environnement, attestations, version). Les neuf constats sont toujours présents sur `origin/develop` (vérification du 2026-10-29, état GitHub lu en direct avec `gh`) :

- `AUD-DEP-21` — `release.yml` appelle `windows-installer.yml` avec `secrets: inherit` : tout secret du dépôt devient accessible au build Windows.
- `AUD-SEC-06` (fusionne `AUD-DEP-01`) — le PFX Authenticode et son mot de passe sont posés dans l'`env` du job Windows, donc visibles de toutes ses étapes, y compris sur `pull_request` où le code de la PR est extrait.
- `AUD-DEP-03` — le job `publish` ne déclare aucun environnement GitHub ; `gh api …/environments` renvoie 0 environnement et le seul ruleset (`Protect main & develop`) ne cible que des branches : aucune règle sur `refs/tags/v*`.
- `AUD-DEP-09` — les deux `actions/attest` du job `publish` ne visent que l'image ; le ZIP et le `setup.exe` Windows ne sont accompagnés que de `.sha256` co-localisés.
- `AUD-DEP-05` — aucun `project.build.outputTimestamp` : les JAR et le ZIP ne sont pas reproductibles bit à bit.
- `AUD-DEP-06` — l'enforcer ne vérifie que Java, Maven `[3.9.0,)` et les doublons de dépendances ; les plugins du cycle de vie (jar, resources, install, deploy, clean) ne sont pas épinglés, alors que le wrapper fixe Maven 3.10.0.
- `AUD-DEP-07` — la génération des notices de licences échoue sur une licence manquante mais n'en refuse aucune.
- `AUD-QUA-12` — la version `0.2.0` est écrite en dur dans `ci.yml` (quatre occurrences) et `scripts/validate-phase-6.ps1` (six occurrences).
- `AUD-DEP-02` — `gh run list --workflow release.yml` : 0 run ; 0 tag, 0 release. De plus, `validate-release-tag.sh` n'accepte que `vX.Y.Z` : un tag de pré-version est aujourd'hui refusé, ce qui empêche la répétition demandée.

Critère de sortie du sprint : un tag de pré-version produit, depuis un environnement protégé, des artefacts attestés et signés, sans que la clé soit visible hors des étapes de signature ni la version codée en dur.

## What Changes

- Les workflows réutilisables ne reçoivent que les secrets qu'ils déclarent ; `secrets: inherit` disparaît (`AUD-DEP-21`).
- Le matériel de signature n'existe que dans les étapes qui signent, jamais sur `pull_request`, et il est effacé dans la même étape (`AUD-SEC-06`).
- La publication et la signature de release s'exécutent dans l'environnement protégé `release` (approbation requise, tags `v*` seuls), qui porte les secrets PFX ; un ruleset protège les tags `v*` (`AUD-DEP-03`, réglages manuels du mainteneur).
- Le ZIP et le `setup.exe` Windows reçoivent une attestation de provenance vérifiée avant l'upload, et la vérification consommateur est documentée (`AUD-DEP-09`).
- Le pom racine fixe `project.build.outputTimestamp` ; deux builds successifs donnent les mêmes empreintes de JAR et de ZIP (`AUD-DEP-05`).
- Les plugins du cycle de vie sont épinglés et l'enforcer exige des versions de plugins, la cohérence des bornes supérieures de dépendances et Maven `[3.10.0,)` (`AUD-DEP-06`).
- Une liste blanche de licences fait échouer le build sur toute licence hors liste (`AUD-DEP-07`).
- La CI et le gate Windows lisent la version dans `pom.xml` (`AUD-QUA-12`).
- La chaîne accepte un tag de pré-version `vX.Y.Z-rc.N` qui publie une release marquée pré-version sans déplacer `latest` ; une répétition complète est exécutée et sa preuve consignée (`AUD-DEP-02`).
- Nouvel ADR : politique de publication (environnement protégé, attestations de tous les assets, tags de pré-version).

## Capabilities

### New Capabilities
- `release-pipeline`: chaîne de publication déclenchée par tag (secrets, environnement protégé, attestations, identité de version, pré-versions).
- `build-reproducibility`: build Maven reproductible et outillage épinglé (horodatage de sortie, versions de plugins, version minimale de Maven).
- `license-compliance`: contrôle des licences des dépendances livrées.

### Modified Capabilities

(aucune : `openspec/specs/` est vide)

## Impact

- Workflows : `.github/workflows/release.yml`, `.github/workflows/windows-installer.yml`, `.github/workflows/ci.yml`.
- Scripts : `scripts/validate-phase-6.ps1`, `scripts/release/validate-release-tag.sh` et `scripts/release/test-release-tag-policy.sh`, `scripts/release/publish-github-release-assets.sh` et son test, `scripts/release/build-windows-distribution.ps1`, `scripts/release/build-windows-installer.ps1`, `scripts/release/test-final-audit-contracts.sh`.
- Build : `pom.xml` (propriétés, `pluginManagement`, enforcer, `license-maven-plugin`).
- Réglages GitHub (manuels, mainteneur) : environnement `release`, secrets d'environnement, ruleset de tags.
- Documentation : `docs/developer/immutable-release-publishing.md`, `docs/developer/release-and-recovery.md`, `docs/developer/ci-and-supply-chain.md`, registre des ADR et vue arc42 des décisions.
- Comportement observable : les PR n'ont plus accès à la clé de signature (installeur de smoke non signé, comme sur un fork) ; une release attend une approbation ; les assets Windows sont vérifiables par `gh attestation verify`. Aucun changement de code produit.
