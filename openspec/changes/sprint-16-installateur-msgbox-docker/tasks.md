# Tasks

Contrôle ciblé, noté `CHECK` ci-dessous : `pwsh -NoProfile -File scripts/release/test-windows-config-escaping.ps1`.

## 1. Avis de Docker Desktop en installation silencieuse

- [x] 1.1 `AUD-QUA-33` — Dans `scripts/release/test-windows-config-escaping.ps1`, écrire le contrôle de toutes les boîtes de la source durcie : chaînes et commentaires retirés, découpe en routines, chaque `MsgBox(`, `TaskDialogMsgBox(`, `InputBox(` ou `InputQuery(` précédé de `WizardSilent()` ou `UninstSilent` dans sa routine, `SuppressibleMsgBox(` limité à `NextButtonClick` et `NotifyPortSelection`. Ajouter deux contrôles négatifs : garde retirée de `StartDockerDesktopAfterInstall`, `MsgBox` injecté dans une routine sans garde. Couvre `installer-client-configuration › Le contrôle de la source durcie refuse toute boîte modale sans garde de mode silencieux` (trois scénarios). Vérifier : `CHECK` échoue sur le gabarit d'origine en nommant `StartDockerDesktopAfterInstall`.
- [x] 1.2 `AUD-QUA-33` — Dans `StartDockerDesktopAfterInstall` (`packaging/windows/nexus-installer.iss.template:2276-2290`), journaliser le lancement de Docker Desktop puis n'afficher la boîte que sous `if not WizardSilent() then`. Couvre `installer-client-configuration › Une installation silencieuse n'attend aucune réponse après l'installation de Docker Desktop` (scénarios « installation silencieuse » et « installation interactive »). Vérifier : `CHECK` réussit ; compilation du gabarit durci par `ISCC.exe` si l'installateur peut être compilé ici.
- [x] 1.3 `AUD-QUA-33` — Dans le même contrôle, verrouiller la forme de `StartDockerDesktopAfterInstall` : sortie anticipée `if not DockerDesktopInstalledDuringSetup then exit;`, journal du lancement, boîte sous `not WizardSilent()`. Couvre le scénario « Docker Desktop déjà présent ». Vérifier : `CHECK` réussit ; une copie sans la garde ou sans la sortie anticipée échoue.

## 2. Recherche d'autres boîtes ou attentes en installation silencieuse

- [x] 2.1 Balayer le gabarit, les deux scripts de durcissement, `scripts/release/build-windows-distribution.ps1` et les lanceurs générés à la recherche de toute autre primitive modale ou attente d'une réponse (`MsgBox`, `TaskDialogMsgBox`, `InputBox`, `InputQuery`, `ExitSetupMsgBox`, `pause`, `set /p`, `Read-Host`, `Exec` sans délai borné). Un constat nouveau est corrigé dans ce change, avec ses scénarios et ses tests, étiqueté [Écart] hors audit ; ce qui reste ouvert est consigné dans « Écarts et limites ». Vérifier : résultat du balayage consigné dans la section « Écarts et limites (installer-client-configuration) » de `docs/developer/release-and-recovery.md`.

## 3. Documentation durable

- [x] 3.1 Compléter `docs/developer/release-and-recovery.md`, section « OpenSpec installer-client-configuration » : lignes [Existant vérifié] (avis de Docker Desktop gardé, contrôle de toutes les boîtes), séquence UML de l'étape post-installation, limites (installation silencieuse réelle avec Docker Desktop non rejouée) ; mettre à jour `docs/user/windows-installation.md` (« Écran 7 — Docker Desktop »). Vérifier : `bash .claude/scripts/contracts.sh`.
- [x] 3.2 Compléter le tableau « Traçabilité » du design avec les références relues au HEAD (lignes du gabarit et du contrôle). Les lignes de `docs/developer/openspec-traceability.md` ne s'ajoutent qu'à la synchronisation des specs : `scripts/release/openspec_doc_contracts.py` refuse une ligne sans scénario de spec de référence (`check_capability_rows`). Vérifier : `bash scripts/release/test-openspec-doc-contracts.sh` et `openspec validate sprint-16-installateur-msgbox-docker --strict`.

## Workflow follow-up

- Une PR vers `main` depuis une branche issue de `origin/main` (skill `nexus-pr-workflow`, `GEN-0089`), merge exact-head en merge commit ; `/nx-validate full` avant la PR.
- À la synchronisation des specs, ajouter à la section `installer-client-configuration` de `docs/developer/openspec-traceability.md` une ligne par scénario des deux exigences (preuves de 1.1 à 1.3), puis lancer `bash scripts/release/test-openspec-doc-contracts.sh`.
- Mettre à jour le statut de `AUD-QUA-33` dans `docs/project/audits/2026-10-17/findings.json` avec la PR, puis synchroniser les specs et archiver le changement, seulement sur demande explicite.
- Rejouer une installation silencieuse réelle en mode Docker sur un poste sans Docker Desktop (tâche mainteneur, non faisable en CI).
