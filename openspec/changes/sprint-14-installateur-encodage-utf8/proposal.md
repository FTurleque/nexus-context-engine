# Proposition

## Why

L'installateur Windows écrit la configuration native (`config\nexus-native.env.cmd`), le `.env` Docker, le fragment TOML de Codex Desktop et les fragments JSON MCP par `SaveStringToFile`, qui convertit le texte vers la page de code ANSI du poste. Sur un profil accentué (« Hélène ») avec les valeurs par défaut, `cmd.exe` relit `NEXUS_HOME` dans la page OEM et les lanceurs `nexus.cmd` et `nexus-mcp.cmd` reçoivent un dossier inexistant ; Codex Desktop reçoit un chemin Java altéré. La vérification du 11/11/2026 classe ce constat en sévérité élevée (`AUD-QUA-28`, sprint 14) : il touche la même population que `AUD-QUA-22`, corrigé au sprint 13 pour les seuls fichiers JSON des clients, et aucun test ne l'exerce, le smoke de l'installateur tournant avec un profil ASCII.

Source : `docs/project/audits/2026-10-17/findings.json` (entrée `AUD-QUA-28`), `docs/project/audits/2026-10-17/etat-2026-11-11.md` (section `AUD-QUA-28`).

## What Changes

- `AUD-QUA-28` (Élevée) — encodage des fichiers générés
  - [Existant vérifié] `SaveStringToFile` convertit le texte Unicode vers la page ANSI avec correspondance « best-fit », comme le rappelle le commentaire de `SavePowerShellScript` (`packaging/windows/nexus-installer.iss.template:1438-1442`). Il écrit `config\nexus-native.env.cmd` (`:1293`), `docker\.env` (`:1336`), `integrations\copilot-jetbrains.mcp.json` (`:1691`, `:1693`), `integrations\codex-desktop.mcp.toml` (`:1725`, `:1727`) et `integrations\generic-mcp.json` (`:1733`, `:1735`).
  - [Existant vérifié] Les valeurs écrites dépendent du profil ou du dossier d'installation : `NEXUS_HOME` vaut par défaut `GetEnv('USERPROFILE') + '\.nexus'` (`:920`), le dossier de dépôt Docker `GetEnv('USERPROFILE')` (`:972`, écrit dans `NEXUS_REPOSITORY_BIND` par `scripts/release/harden-windows-installer-source.ps1:211`), et les fragments natifs JSON et TOML contiennent `{app}\app\runtime\bin\java.exe` et `{app}\lib\nexus-mcp.jar` (`:1393-1394`, `:1424-1425`), `{app}` valant par défaut `{localappdata}\Programs\NEXUS` (`:17`).
  - [Existant vérifié] Les lecteurs attendent un autre encodage : `connect-codex-desktop.ps1` relit le fragment TOML en UTF-8 (`:1847`) puis l'ajoute à `~\.codex\config.toml` (`:1851-1852`) ; les lanceurs générés par `scripts/release/build-windows-distribution.ps1:260-273` appellent `config\nexus-native.env.cmd`, que `cmd.exe` décode dans la page de code de la console.
  - [Existant vérifié] Le rejeu du 11/11 (page ANSI 1252, page OEM 850) montre `if exist "%NEXUS_HOME%"` en échec pour `…\Hélène\.nexus` (`findings.json`, champ `verification`). [Écart] Le `.env` Docker et le fragment TOML sont déduits de la lecture du gabarit, non rejoués (`etat-2026-11-11.md`, « Angles morts »).
  - [Existant vérifié] Le smoke n'exerce aucun profil ni dossier d'installation non ASCII : il installe dans `target\installer-smoke\install` (`scripts/release/test-windows-installer.ps1:285-299`) avec le profil du poste.
  - [Exigence] `docker\.env`, le fragment TOML et les fragments JSON sont écrits en UTF-8 sans BOM et se relisent caractère pour caractère.
  - [Exigence] Les lanceurs natifs reçoivent `NEXUS_HOME` et les autres variables de `config\nexus-native.env.cmd` tels qu'ils ont été saisis, quelle que soit la page de code de la console, qu'ils rendent inchangée.
  - [Proposition] Pour le `.cmd` : `NEXUS_HOME` écrit en référence à `%LOCALAPPDATA%` ou `%USERPROFILE%` quand il commence par leur valeur, et `chcp 65001` encadré (page d'origine restaurée) quand une valeur garde un caractère non ASCII (voir `design.md`, « Décisions »).
  - [Proposition] Le smoke installe avec un profil et un `{app}` accentués, contrôle `NEXUS_HOME` vu par `nexus.cmd` et le TOML de Codex.

## Type de changement

fonctionnel (fichiers générés par l'installateur Windows) : profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- Les cinq fichiers écrits par `SaveStringToFile` qui portent une valeur saisie ou dérivée du poste : `config\nexus-native.env.cmd`, `docker\.env`, `integrations\codex-desktop.mcp.toml`, `integrations\copilot-jetbrains.mcp.json`, `integrations\generic-mcp.json`.
- Les deux scripts de durcissement qui réécrivent l'écrivain de `config\nexus-native.env.cmd` (`scripts/release/harden-windows-installer-source.ps1:166-182`, `scripts/release/harden-windows-rest-auth-source.ps1:15-45`).
- Les tests `scripts/release/test-windows-installer.ps1` et `scripts/release/test-windows-config-escaping.ps1`.

**Hors périmètre :**
- Les lanceurs `.cmd` à texte constant écrits par `SaveStringToFile` (`nexus-docker-up.cmd`, `nexus-docker-down.cmd` `:1382-1383`, `connect-*.cmd` `:1719`, `:1761`, `:1794`, `:1812`, `:1832`, `:1859`) : ils ne contiennent que des références `%~dp0` et des valeurs ASCII validées ; seul le message `n'est pas prêt` (`:1356`) s'affiche altéré, défaut d'affichage sans effet sur le comportement.
- `docker\docker-compose.yml` (`:1338-1340`) : lu et écrit en `AnsiString`, octets recopiés sans conversion.
- Les scripts PowerShell générés et les configurations JSON des clients : déjà en UTF-8 (`installer-client-configuration`, sprint 13).
- L'échappement des caractères de contrôle par `JsonEscape` et `TomlEscape` (`:207-219`) : les valeurs concernées sont des chemins Windows, et `NEXUS_HOME` refuse déjà retour à la ligne, guillemet et `^` (`scripts/release/harden-windows-installer-source.ps1:93-99`).
- La reprise des données qu'une version antérieure aurait créées sous un chemin altéré.

## Capabilities

### New Capabilities

### Modified Capabilities
- `installer-client-configuration`: ajout de deux exigences — encodage UTF-8 sans BOM des fichiers de configuration générés, et valeurs de `config\nexus-native.env.cmd` reçues intactes par les lanceurs natifs.

## Impact

- Installateur : `packaging/windows/nexus-installer.iss.template` (`WriteNativeConfiguration`, `WriteDockerConfiguration`, `WriteAssistantAssets`, nouvel écrivain UTF-8 sans BOM, réglage du mode smoke `:986-993`), `scripts/release/harden-windows-installer-source.ps1`, `scripts/release/harden-windows-rest-auth-source.ps1`.
- Tests : `scripts/release/test-windows-installer.ps1`, `scripts/release/test-windows-config-escaping.ps1` ; workflow `Windows installer` (`.github/workflows/windows-installer.yml`).
- Documentation : `docs/developer/release-and-recovery.md` (section `OpenSpec installer-client-configuration`), `docs/user/windows-installation.md` (« Écran 4 — Configuration runtime »), `docs/developer/openspec-traceability.md`.
- Audit : statut de `AUD-QUA-28` dans `docs/project/audits/2026-10-17/findings.json`.
- Aucun code Java, aucune migration SQLite, aucun ADR.

## Questions ouvertes

- [Question] `docker\.env` n'est jamais écrit par l'installateur smoke (`InstallDocker()` rend faux en mode smoke, `packaging/windows/nexus-installer.iss.template:131-134`) : faut-il ajouter une lecture Compose réelle (`docker compose --env-file … config`) d'un `.env` accentué à `scripts/release/test-docker-runtime.ps1`, ou le contrat statique de la tâche 1.4 et le rejeu de la tâche 2.1 suffisent-ils ? La réponse change la preuve du scénario « Dossier de dépôt Docker accentué », pas l'implémentation.
