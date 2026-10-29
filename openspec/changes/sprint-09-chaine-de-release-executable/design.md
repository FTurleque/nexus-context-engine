# Design

## Context

`release.yml` se déclenche sur `push` d'un tag `v*.*.*`. Le job `validate-release` lit la version dans `pom.xml` et appelle `validate-release-tag.sh`, qui exige `vX.Y.Z` égal à la version Maven et un commit égal à la tête de `main`. Douze workflows réutilisables qualifient ensuite le commit ; `windows-installer` est appelé avec `secrets: inherit`. Le job `publish` (`contents`, `packages`, `id-token`, `attestations` en écriture) pousse l'image GHCR sous des tags immuables, l'atteste (provenance et SBOM), téléverse les assets par `publish-github-release-assets.sh`, puis déplace `latest`.

Dans `windows-installer.yml`, les deux secrets PFX sont posés dans l'`env` du job ; une étape décode le PFX dans `RUNNER_TEMP` et publie son chemin dans `GITHUB_ENV`. La signature a lieu dans `build-windows-distribution.ps1` et `build-windows-installer.ps1`, via `sign-windows-artifact.ps1` ; une étape `always()` efface le fichier en fin de job. Le workflow tourne aussi sur `pull_request` vers `develop` et `main`.

État GitHub lu le 2026-10-29 : aucun environnement, un seul ruleset de branches (`~DEFAULT_BRANCH`, `refs/heads/develop`), aucun run de `Release`, aucun tag, aucune release ; `release-assets-qualification.yml` tourne et réussit.

## Goals / Non-Goals

**Goals:**
- La clé de signature n'est lisible que par les étapes qui signent, dans un run approuvé de l'environnement `release`.
- Chaque asset publié (image, ZIP, `setup.exe`) porte une attestation de provenance vérifiable.
- Un changement de version ne touche que les pom.
- Les JAR et le ZIP sont reproductibles ; l'outillage Maven est épinglé ; les licences sont contrôlées.
- Une répétition réelle de la chaîne sur un tag de pré-version, avec preuve consignée.

**Non-Goals:**
- Reproductibilité bit à bit de l'image Docker et de l'installeur Inno Setup (horodatages d'outils tiers).
- Signature de l'image par une clé gérée (les attestations GitHub suffisent ici).
- Mode dry-run du job `publish` : la répétition réelle sur pré-version le rend inutile.
- Publication vers un dépôt ou un namespace GHCR de test.

## Decisions

**D1 — Aucun héritage de secrets (`AUD-DEP-21`).** `release.yml` ne transmet plus `secrets: inherit`. Tant que les secrets PFX sont des secrets de dépôt, il passe les deux secrets nommés ; une fois D3 appliqué, il n'en passe aucun, les secrets étant résolus par l'environnement. Un contrat de `test-final-audit-contracts.sh` refuse `secrets: inherit` dans `.github/workflows/`.

**D2 — Matériel de signature limité aux étapes de signature (`AUD-SEC-06`).** Les secrets quittent l'`env` du job. Seules les deux étapes qui signent (distribution et installeur de production) les reçoivent, et seulement hors `pull_request`. Chacune décode le PFX dans un répertoire temporaire propre à l'étape, signe, puis l'efface dans un bloc `finally` ; le chemin n'est plus publié dans `GITHUB_ENV`. Les étapes de smoke et de qualification n'y ont jamais accès. Sur `pull_request`, le build produit des artefacts non signés, comme pour un fork. L'étape de nettoyage `always()` reste en défense en profondeur.

**D3 — Environnement protégé `release` (`AUD-DEP-03`).** Le mainteneur crée l'environnement `release` : un reviewer requis, politique de déploiement limitée aux tags `v*`, et y déplace les deux secrets PFX (supprimés des secrets de dépôt). Le job `publish` et le job Windows appelé avec `require_authenticode: true` déclarent `environment: release`. Un ruleset de tags `refs/tags/v*` restreint création, mise à jour et suppression au mainteneur. Les réglages sont des tâches manuelles ; leur état est vérifié en lecture par `gh api`.

**D4 — Attestation de tous les assets (`AUD-DEP-09`).** Avant `publish-github-release-assets.sh`, `actions/attest` (épinglé par SHA, même version que pour l'image) atteste par `subject-path` les ZIP et `setup.exe` Windows. Une étape suivante exécute `gh attestation verify` sur chacun et échoue sinon. `docs/developer/immutable-release-publishing.md` documente la vérification côté consommateur.

**D5 — Build reproductible (`AUD-DEP-05`).** Le pom racine fixe `project.build.outputTimestamp` ; la valeur est mise à jour avec la version à chaque release (procédure de `docs/developer/release-and-recovery.md`). La vérification compare les SHA-256 du JAR CLI et du ZIP de distribution entre deux `./mvnw clean package` successifs.

**D6 — Outillage épinglé (`AUD-DEP-06`).** `pluginManagement` épingle `maven-clean-plugin`, `maven-resources-plugin`, `maven-jar-plugin`, `maven-install-plugin` et `maven-deploy-plugin` par propriétés (suivies par Dependabot). L'enforcer ajoute `requirePluginVersions`, `requireUpperBoundDeps` et resserre `requireMavenVersion` à `[3.10.0,)`, aligné sur le wrapper. `requireUpperBoundDeps` est préféré à `dependencyConvergence`, trop bruyant avec les BOM Quarkus ; les exclusions éventuelles sont listées et justifiées dans le pom.

**D7 — Liste blanche de licences (`AUD-DEP-07`).** `license-maven-plugin` reçoit `includedLicenses` (licences permissives et faiblement réciproques compatibles avec la licence propriétaire du produit, établies à partir des notices actuelles), `licenseMerges` pour normaliser les libellés, et `failOnBlacklist=true`. Toute nouvelle licence exige une modification revue du pom.

**D8 — Version lue dans le pom (`AUD-QUA-12`).** `ci.yml` lit la version par `./mvnw help:evaluate -q -DforceStdout -Dexpression=project.version` (ou par lecture XML comme `release.yml`) et l'exporte en variable ; `validate-phase-6.ps1` la lit par `[xml]` sur `pom.xml`. Un contrat refuse une version littérale dans ces deux fichiers.

**D9 — Tags de pré-version et répétition (`AUD-DEP-02`).** `validate-release-tag.sh` accepte aussi `vX.Y.Z-rc.N`, toujours égal à la version Maven et à la tête de `main`. Pour une pré-version, la release GitHub est marquée pré-version et `latest` n'est pas déplacé ; les tags d'image version et SHA restent immuables. La répétition passe par une promotion `develop` → `main` portant une version `X.Y.Z-rc.1`, la pose du tag par le mainteneur et l'approbation de l'environnement. La preuve (identifiant de run, résultat de `gh attestation verify` sur l'image et les assets, signature Authenticode vérifiée) est consignée dans un rapport daté sous `docs/audits/`. Un nouvel ADR complète ADR-0031 : politique de publication (environnement protégé, attestations de tous les assets, tags de pré-version).

## Risks / Trade-offs

- [Le job Windows est un workflow réutilisable : l'environnement conditionnel `require_authenticode ? release : aucun` peut ne pas être exprimable] → à défaut, une job de signature dédiée dans `release.yml`, en environnement `release`, signe et réassemble les artefacts produits sans secrets ; arbitrage tranché lors de la tâche 3.2.
- [La signature de l'installeur dépend des binaires déjà signés de la distribution] → les deux étapes de signature restent dans le même job, dans l'ordre actuel ; seul le périmètre des secrets change.
- [Une version `-rc` sur `main` entre deux releases] → assumée : `main` ne reçoit que des promotions ; la release finale suit par une nouvelle promotion `X.Y.Z`.
- [La pré-version publie dans le namespace GHCR réel] → tags distincts, immuables, jamais `latest` ; acceptable pour un projet sans consommateur de pré-version.
- [`requireUpperBoundDeps` ou la liste blanche cassent une PR Dependabot] → l'échec est voulu ; la procédure de la skill `nexus-dependency-bump` est complétée.
- [Approbation de l'environnement par le seul mainteneur] → la protection porte sur l'exécution non sollicitée (tag poussé par un autre acteur), pas sur la revue à deux.
