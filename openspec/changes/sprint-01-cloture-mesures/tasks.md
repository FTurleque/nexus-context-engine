# Tasks

## 1. Sécurité du dépôt (AUD-DEP-30)

- [ ] 1.1 `AUD-DEP-30` — (mainteneur) Activer, dans GitHub (Settings → Code security), Dependabot alerts, Dependabot security updates, Secret scanning et Push protection ; vérifier par `gh api repos/FTurleque/nexus-context-engine --jq '.security_and_analysis'` (tous `enabled`) et `gh api repos/FTurleque/nexus-context-engine/vulnerability-alerts -i` (204)
- [x] 1.2 `AUD-DEP-30` — Écrire dans `SECURITY.md` la section de traitement des alertes Dependabot et secret scanning (délai, triage, faux positifs des fixtures de redaction) ; vérifier par `bash .claude/scripts/contracts.sh`
- [ ] 1.3 `AUD-DEP-30` — Trier les alertes ouvertes après activation : `gh api repos/FTurleque/nexus-context-engine/dependabot/alerts --jq 'length'` et `gh api repos/FTurleque/nexus-context-engine/secret-scanning/alerts --jq 'length'` ; chaque alerte est corrigée ou fermée avec sa justification

## 2. Benchmark d'échelle requis (AUD-DEP-14)

- [x] 2.1 `AUD-DEP-14` — Retirer le filtre `paths` du déclencheur `pull_request` de `.github/workflows/scale-benchmark.yml` et ajouter une étape de détection qui conclut en succès sans mesurer quand le diff ne touche aucun chemin surveillé ; vérifier sur la PR elle-même (chemin surveillé touché → benchmark mesuré)
- [x] 2.2 `AUD-DEP-14` — Étendre le contrat anti-dérive de `scripts/release/test-operational-doc-contracts.sh` à la liste lue par l'étape de détection ; vérifier par `bash .claude/scripts/contracts.sh`
- [x] 2.3 `AUD-DEP-14` — Vérifier sur une PR de documentation seule que « Hermetic scale benchmark » conclut en succès en quelques secondes
- [ ] 2.4 `AUD-DEP-14` — (mainteneur, après 2.1 à 2.3) Ajouter « Hermetic scale benchmark » aux checks requis du ruleset « Protect main & develop » ; vérifier par `gh api repos/FTurleque/nexus-context-engine/rulesets` puis la règle `required_status_checks`
- [x] 2.5 `AUD-DEP-14` — Mettre à jour `docs/developer/scale-regression-benchmark.md` et la liste des checks requis de `docs/developer/branch-governance.md` et du skill `nexus-pr-workflow` ; vérifier par `bash .claude/scripts/contracts.sh`

## 3. Tests symlink dans le `@TempDir`

- [x] 3.1 `suivi #267, AUD-TST-11` — Dans `NativeProjectCustomizationDetectorTest` (`rejectsAncestorSymlinkForAgentProfiles`, `rejectsFinalSymlinkForOperationalConfiguration`) et `AiSkillsRegistryProviderTest` (`rejectsAncestorSymlinkForRegistrySkillsRoot`, `rejectsFinalSkillDefinitionSymlink`), placer la racine du projet sous `temporaryDirectory.resolve("project")` et la cible externe sous `temporaryDirectory`, sans `resolveSibling` ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='NativeProjectCustomizationDetectorTest,AiSkillsRegistryProviderTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [x] 3.2 `suivi #267, AUD-TST-11` — Vérifier qu'aucun test de `core/src/test` ne crée de fichier par `resolveSibling` sur un `@TempDir` : `git grep -n "temporaryDirectory.resolveSibling" -- core/src/test` vide
- [x] 3.3 `suivi #267, AUD-TST-11` — Lancer les tests avec `CI=true` pour confirmer que les tests symlink restent fail-closed : `CI=true ./mvnw -B -ntp -pl core test -Dtest='NativeProjectCustomizationDetectorTest,AiSkillsRegistryProviderTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`

## 4. Clôture

- [ ] 4.1 Passer à « Fermé », avec leur PR, les constats du sprint 1 (`AUD-TST-01`, `AUD-TST-03` : #268 ; `AUD-QUA-06`, `AUD-DEP-20`, `AUD-TST-08` : #266 ; `AUD-TST-11` : #267 ; `AUD-DEP-14` : #265 et 2.4 ; `AUD-DEP-30` : 1.1) ; mettre à jour leur statut dans `docs/project/audits/2026-10-17/findings.json` (fermés, avec la PR) ; vérifier par `/nx-validate quick`

## Workflow follow-up

- Une PR vers `develop` pour 1.2, 2.1 à 2.3, 2.5 et 3 (skill `nexus-pr-workflow`), merge exact-head ; les tâches mainteneur 1.1 et 2.4 se font hors PR.
- Archiver le changement OpenSpec une fois la PR fusionnée et les réglages vérifiés.
