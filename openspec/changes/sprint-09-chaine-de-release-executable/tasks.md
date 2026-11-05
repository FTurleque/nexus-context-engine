# Tasks

Ordre interne du sprint : `AUD-DEP-21`, `AUD-SEC-06`, `AUD-DEP-03`, `AUD-DEP-09` et `AUD-QUA-12` débloquent `AUD-DEP-02`, traité en dernier. Les tâches marquées « manuel » sont des réglages GitHub du mainteneur ; leur vérification n'utilise que des lectures `gh`.

## 1. Secrets explicites (AUD-DEP-21)

- [ ] 1.1 AUD-DEP-21 — Remplacer `secrets: inherit` de `release.yml` par la transmission des deux secrets PFX nommés (design D1) ; vérifier par `git grep -n "secrets: inherit" -- .github/workflows` (aucun résultat)
- [ ] 1.2 AUD-DEP-21 — Ajouter à `scripts/release/test-final-audit-contracts.sh` le refus de `secrets: inherit` dans `.github/workflows/` ; vérifier par `bash .claude/scripts/contracts.sh`

## 2. Matériel de signature confiné (AUD-SEC-06)

- [ ] 2.1 AUD-SEC-06 — Retirer les secrets PFX de l'`env` du job `windows-installer` et ne les mapper que sur les étapes de signature (distribution, installeur de production), sous condition `github.event_name != 'pull_request'` (design D2) ; vérifier par `bash .claude/scripts/contracts.sh` après ajout du contrat correspondant dans `test-final-audit-contracts.sh`
- [ ] 2.2 AUD-SEC-06 — Décoder le PFX dans chaque étape de signature, l'effacer dans un `finally` de la même étape et ne plus publier son chemin dans `GITHUB_ENV` (`build-windows-distribution.ps1`, `build-windows-installer.ps1`, `sign-windows-artifact.ps1`) ; vérifier par le check « Windows self-contained installer » de la PR (artefacts non signés, aucun secret exposé) puis par un `workflow_dispatch` de `windows-installer.yml` avec `require_authenticode: true` sur la branche

## 3. Environnement protégé et tags (AUD-DEP-03)

- [ ] 3.1 AUD-DEP-03 (manuel) — Créer l'environnement `release` : reviewer requis (mainteneur), politique de déploiement limitée aux tags `v*` ; y créer les deux secrets PFX puis les supprimer des secrets de dépôt ; vérifier par `gh api repos/FTurleque/nexus-context-engine/environments --jq '.environments[] | {name, protection_rules}'`
- [ ] 3.2 AUD-DEP-03 — Déclarer `environment: release` sur le job `publish` et sur le job Windows appelé avec `require_authenticode: true` ; si l'environnement conditionnel n'est pas accepté dans le workflow réutilisable, appliquer l'alternative du design (job de signature dédiée) ; retirer la transmission de secrets de `release.yml` (design D1, D3) ; vérifier par `bash .claude/scripts/contracts.sh` (contrat : `publish` déclare `environment: release`)
- [ ] 3.3 AUD-DEP-03 (manuel) — Créer un ruleset de tags ciblant `refs/tags/v*` (création, mise à jour, suppression restreintes au mainteneur) ; vérifier par `gh api repos/FTurleque/nexus-context-engine/rulesets --jq '.[] | {name, target, enforcement}'` (un ruleset `tag` actif)

## 4. Provenance des assets Windows (AUD-DEP-09)

- [ ] 4.1 AUD-DEP-09 — Ajouter dans `publish`, avant l'upload, `actions/attest` (épinglé par SHA, même version que pour l'image) en `subject-path` sur les ZIP et `setup.exe`, puis une étape `gh attestation verify` bloquante (design D4) ; vérifier par `bash .claude/scripts/contracts.sh` (contrat : les deux `subject-path` et la vérification existent)
- [ ] 4.2 AUD-DEP-09 — Documenter la vérification consommateur (`gh attestation verify <asset> --repo FTurleque/nexus-context-engine`) dans `docs/developer/immutable-release-publishing.md` et `docs/developer/release-and-recovery.md`, avec l'assertion correspondante dans `scripts/release/test-operational-doc-contracts.sh` ; vérifier par `bash .claude/scripts/contracts.sh`

## 5. Build reproductible (AUD-DEP-05)

- [ ] 5.1 AUD-DEP-05 — Ajouter `project.build.outputTimestamp` au pom racine et sa mise à jour dans la procédure de release de `docs/developer/release-and-recovery.md` (design D5) ; vérifier par deux `./mvnw -B -ntp clean package -DskipTests` successifs puis `sha256sum target/nexus-context-engine-*-cli.jar target/distribution/*.zip` (empreintes identiques)

## 6. Outillage Maven épinglé (AUD-DEP-06)

- [ ] 6.1 AUD-DEP-06 — Épingler en `pluginManagement` les plugins clean, resources, jar, install et deploy par propriétés ; ajouter `requirePluginVersions` et `requireUpperBoundDeps` (exclusions justifiées) et resserrer `requireMavenVersion` à `[3.10.0,)` (design D6) ; vérifier par `./mvnw -B -ntp clean install`
- [ ] 6.2 AUD-DEP-06 — Compléter la skill `nexus-dependency-bump` et `docs/developer/ci-and-supply-chain.md` pour les nouveaux plugins épinglés et l'échec `requireUpperBoundDeps` ; vérifier par `bash .claude/scripts/contracts.sh`

## 7. Liste blanche de licences (AUD-DEP-07)

- [ ] 7.1 AUD-DEP-07 — Établir la liste des licences actuelles depuis `target/licenses/THIRD_PARTY_NOTICES.txt`, puis configurer `includedLicenses`, `licenseMerges` et `failOnBlacklist=true` sur `aggregate-third-party-notices` (design D7) ; vérifier par `./mvnw -B -ntp clean install` (notices inchangées hormis la normalisation des libellés)
- [ ] 7.2 AUD-DEP-07 — Ajouter à `scripts/release/test-final-audit-contracts.sh` la présence de `failOnBlacklist` et d'une liste `includedLicenses` non vide dans `pom.xml` ; vérifier par `bash .claude/scripts/contracts.sh`

## 8. Version lue dans le pom (AUD-QUA-12)

- [ ] 8.1 AUD-QUA-12 — Lire la version dans `pom.xml` dans `ci.yml` (job Linux : ZIP, racine de smoke, message du lanceur, artefacts retenus) et dans `scripts/validate-phase-6.ps1` (JAR CLI, ZIP, SBOM, `--version`) (design D8) ; vérifier par `git grep -n "0\.2\.0" -- .github/workflows/ci.yml scripts/validate-phase-6.ps1` (aucun résultat), par le check « Linux reactor Maven build » et par le check « Windows gate » de la PR
- [ ] 8.2 AUD-QUA-12 — Ajouter à `scripts/release/test-final-audit-contracts.sh` le refus d'une version littérale du produit dans ces deux fichiers ; vérifier par `bash .claude/scripts/contracts.sh`

## 9. Pré-version et répétition de la release (AUD-DEP-02)

- [ ] 9.1 AUD-DEP-02 — Accepter `vX.Y.Z-rc.N` dans `validate-release-tag.sh`, avec les cas valides et invalides dans `scripts/release/test-release-tag-policy.sh` ; marquer la release GitHub comme pré-version et ne pas déplacer `latest` pour un tag `-rc` (`publish-github-release-assets.sh` et son test, étape `latest` de `release.yml`) (design D9) ; vérifier par `bash scripts/release/test-release-tag-policy.sh`, `bash scripts/release/test-publish-github-release-assets.sh` et `bash .claude/scripts/contracts.sh`
- [ ] 9.2 AUD-DEP-02 — Nouvel ADR (skill `nexus-adr`) qui complète ADR-0031 : politique de publication (environnement protégé, attestations de tous les assets, tags de pré-version) ; mettre à jour le registre des ADR, la vue arc42 des décisions et `docs/developer/immutable-release-publishing.md` ; vérifier par `bash .claude/scripts/contracts.sh`
- [ ] 9.3 AUD-DEP-02 (manuel) — Après fusion des sections 1 à 9.2 et des tâches manuelles 3.1 et 3.3 : passer la version à `X.Y.Z-rc.1`, promouvoir `develop` vers `main` (`/nx-release`), poser le tag `vX.Y.Z-rc.1` sur la tête de `main`, approuver l'environnement `release` ; vérifier par `gh run list --workflow release.yml --limit 1` (run réussi) et `gh release view vX.Y.Z-rc.1 --json isPrerelease,assets`
- [ ] 9.4 AUD-DEP-02 — Vérifier la répétition : `gh attestation verify` sur l'image `ghcr.io/fturleque/nexus-context-engine:X.Y.Z-rc.1`, le ZIP et le `setup.exe` ; signature Authenticode valide du `setup.exe` ; `latest` inchangé ; consigner la preuve (identifiant de run, sorties de vérification) dans un relevé daté sous `docs/project/history/` ; vérifier par `bash .claude/scripts/contracts.sh`

## Workflow follow-up

- Ouvrir la PR depuis une branche issue de `origin/develop` vers `develop` (skill `nexus-pr-workflow`), relue par `/nx-review` (agent `nexus-security-auditor` pour les sections 1 à 4) ; merge exact-head en merge commit (`gh pr merge --merge --match-head-commit <sha>`). Les tâches 9.3 et 9.4 suivent la fusion.
- Une fois la répétition consignée, archiver le changement OpenSpec (`/opsx:archive sprint-09-chaine-de-release-executable`).
