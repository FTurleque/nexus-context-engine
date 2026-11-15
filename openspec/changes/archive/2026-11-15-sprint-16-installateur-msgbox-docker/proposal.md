# Proposition

## Why

Le constat `AUD-QUA-33` du 15/11 (sprint 16, Faible) : une installation silencieuse en mode Docker, sur un poste sans Docker, reste suspendue après l'installation automatique de Docker Desktop. L'installateur lance Docker Desktop puis affiche une boîte modale d'information, qu'aucun paramètre de ligne de commande ne supprime. Dans une session non interactive (déploiement `/VERYSILENT`), la boîte est invisible et l'installation n'aboutit jamais.

Le contrat du sprint 15 (`AUD-QUA-29`) a ôté les boîtes non supprimables de la validation des pages, mais il ne lit que deux routines. Le constat montre que cette portée est trop étroite : une boîte ajoutée ailleurs dans le gabarit échappe au contrôle.

Sources : `docs/project/audits/2026-10-17/etat-2026-11-15.md` (`AUD-QUA-33`), `docs/project/audits/2026-10-17/sprints.md` (S16, critère de sortie : « aucune MsgBox non gardée dans la source durcie (contrat) »).

## What Changes

- [Existant vérifié] `StartDockerDesktopAfterInstall` appelle `MsgBox(…, mbInformation, MB_OK)` sans garde de mode silencieux (`packaging/windows/nexus-installer.iss.template:2276-2290`, appel `:2284-2289`). Elle est appelée en `ssPostInstall` (`:2488-2491`) dès que `InstallDockerDesktopIfNeeded` a installé Docker Desktop (`:489`, `DockerDesktopInstalledDuringSetup`). La case d'installation automatique est cochée par défaut (`:954`) et une installation silencieuse saute la page : la valeur par défaut s'applique.
- [Existant vérifié] `/SUPPRESSMSGBOXES` ne supprime pas `MsgBox` (seulement `SuppressibleMsgBox`) ; un `MsgBox` non gardé suspend donc même `/VERYSILENT /SUPPRESSMSGBOXES`. Les trois autres `MsgBox(` du gabarit sont gardés : `RunWhileClaudeDesktopClosed` sort avant sa boîte en silencieux (`:1819-1824`, `UninstSilent` et `WizardSilent()`), l'avis d'échec des clients IA est sous `not WizardSilent()` (`:2503-2504`), la question de la désinstallation sous `not UninstSilent` (`:2613-2617`).
- [Existant vérifié] Le contrat actuel borne la recherche d'un `MsgBox(` nu à `NextButtonClick` et `NotifyPortSelection` (`scripts/release/test-windows-config-escaping.ps1:420-455`, `scripts/release/test-windows-installer.ps1:74-98`). Une boîte ailleurs, comme celle de `AUD-QUA-33`, n'est vue par aucun test.
- [Exigence] Une installation silencieuse (`/SILENT` ou `/VERYSILENT`) n'affiche aucune boîte modale d'information après l'installation de Docker Desktop : l'avis est journalisé, l'installation se termine. Hors mode silencieux, l'avis reste affiché.
- [Décision] La garde est `not WizardSilent()`, comme l'avis voisin des clients IA (`:2503`) : elle vaut avec ou sans `/SUPPRESSMSGBOXES`, alors que `SuppressibleMsgBox` resterait affichée sans ce paramètre.
- [Exigence] Le contrôle de la source durcie refuse tout `MsgBox(` du gabarit qui n'est pas précédé, dans sa routine, d'une garde de mode silencieux (`WizardSilent()` ou `UninstSilent`), et tout `SuppressibleMsgBox(` hors `NextButtonClick` et `NotifyPortSelection`. Une boîte non gardée injectée, ou la garde retirée, fait échouer le contrôle.
- [Écart] Hors audit : la couverture n'avait pas de contrôle négatif sur les routines hors validation de pages ; elle est ajoutée par le même contrôle (mutation de `StartDockerDesktopAfterInstall` et boîte injectée dans une routine sans garde).
- [Proposition] Pas de migration, pas de changement de format persistant, pas de nouvelle exigence sur les boîtes de validation de page (déjà couvertes par `installer-client-configuration › En installation silencieuse, les messages de l'assistant n'attendent aucune réponse`).

## Type de changement

fonctionnel (comportement de l'installateur Windows en installation silencieuse) ; profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- Garde de mode silencieux sur l'avis posé par `StartDockerDesktopAfterInstall`, et journalisation du lancement de Docker Desktop.
- Contrôle de la source durcie (`scripts/release/test-windows-config-escaping.ps1`) : toute boîte du gabarit est gardée ou suppressible, avec contrôles négatifs.
- Documentation durable de la capacité et lignes de traçabilité.

**Hors périmètre :**
- Les boîtes de validation de page (`NextButtonClick`, `NotifyPortSelection`) : elles passent déjà par `SuppressibleMsgBox`.
- Le lancement de Docker Desktop lui-même (processus `ewNoWait`) : il ne suspend pas l'installation.
- Une installation silencieuse réelle avec installation de Docker Desktop (poste sans Docker, WSL 2) : non rejouée.
- Les messages d'erreur renvoyés par `PrepareToInstall` (affichés par Inno Setup lui-même).

## Capabilities

### New Capabilities

### Modified Capabilities
- `installer-client-configuration` : deux exigences ajoutées (aucune boîte modale d'information après l'installation silencieuse de Docker Desktop ; contrôle de la source durcie sur toute boîte du gabarit).

## Impact

- Code : `packaging/windows/nexus-installer.iss.template` (`StartDockerDesktopAfterInstall`, `:2276-2290`).
- Contrôles : `scripts/release/test-windows-config-escaping.ps1` (nouveau contrôle de toute boîte du gabarit, avec contrôles négatifs), exécuté par `.github/workflows/windows-installer.yml:77`.
- Documentation : `docs/developer/release-and-recovery.md` (section « OpenSpec installer-client-configuration »), `docs/developer/openspec-traceability.md` (section `installer-client-configuration`), `docs/user/windows-installation.md` (« Écran 7 — Docker Desktop », comportement en installation silencieuse).
- ADR applicables, sans nouvel ADR : aucun ADR n'est propre à ce correctif d'installateur ; la décision reste dans le design.
- Effet utilisateur : un déploiement `/VERYSILENT` en mode Docker se termine après l'installation de Docker Desktop, au lieu d'attendre une boîte.

## Questions ouvertes

Aucune.
