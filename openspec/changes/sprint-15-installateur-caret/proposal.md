# Proposition

## Why

L'assistant d'installation Windows refuse le caractère `^` dans `NEXUS_HOME`, le token REST, le dossier de dépôt Docker et l'URL Ollama. Or Windows autorise `^` dans un nom de profil, et les valeurs par défaut de `NEXUS_HOME` et du dossier de dépôt Docker dérivent du profil. Un utilisateur dont le profil contient `^` ne peut donc pas valider la page de configuration Docker, même en profil standard où la page Runtime est sautée : le message parle de « chemins générés » qu'il ne voit pas et ne peut pas corriger. En installation silencieuse, le refus passe par `MsgBox`, que `/SUPPRESSMSGBOXES` ne supprime pas. La vérification du 12/11/2026 classe ce constat en sévérité faible (`AUD-QUA-29`, sprint 15). Critère de sortie du sprint : un profil « a^b » est accepté en mode Docker.

Le même profil fait apparaître un défaut préexistant, hors `AUD-QUA-29` : installés dans le dossier par défaut `{localappdata}\Programs\NEXUS`, qui contient alors `^`, les lanceurs natifs ne chargent pas `config\nexus-native.env.cmd`. Décision du mainteneur du 12/11/2026 : ce défaut est corrigé dans ce changement, sans constat séparé.

Source : `docs/project/audits/2026-10-17/findings.json` (entrée `AUD-QUA-29`), `docs/project/audits/2026-10-17/etat-2026-11-12.md` (section `AUD-QUA-29`, « Angles morts »), `docs/project/audits/2026-10-17/sprints.md` (« S15 — Suites de la vérification du 12 novembre »).

## What Changes

- `AUD-QUA-29` (Faible) — `^` refusé à tort par l'assistant
  - [Existant vérifié] `ContainsUnsafeConfigChars` refuse retour chariot, saut de ligne, guillemet double et `^` (`scripts/release/harden-windows-installer-source.ps1:113-117`). La page Runtime l'applique à `NEXUS_HOME` et au token REST (`:161-168`). La page Docker l'applique à `NEXUS_HOME` (`RuntimePage.Values[0]`), au dossier de dépôt Docker (`DockerPage.Values[5]`) et à l'URL Ollama (`:223-230`), que le déploiement soit natif ou non.
  - [Existant vérifié] Les deux valeurs refusées viennent du profil : `NEXUS_HOME` vaut par défaut `GetEnv('USERPROFILE') + '\.nexus'` (`packaging/windows/nexus-installer.iss.template:920`), le dossier de dépôt Docker `GetEnv('USERPROFILE')` (`:972`). La page Runtime est sautée hors profil personnalisé (`:1008`) ; la page Docker est affichée dès que Docker est déployé (`:1012`).
  - [Existant vérifié] `^` est littéral dans les formats écrits. Dans `config\nexus-native.env.cmd`, chaque valeur est écrite entre guillemets, `set "NOM=valeur"` (`scripts/release/harden-windows-installer-source.ps1:243-251`, `scripts/release/harden-windows-rest-auth-source.ps1:15-45`), et les lanceurs désactivent l'expansion retardée (`scripts/release/build-windows-distribution.ps1:262`). Dans `docker\.env`, le dossier de dépôt, l'URL Ollama et le token passent par `DotEnvQuoted` (`scripts/release/harden-windows-installer-source.ps1:104-111`, `:280`, `:285-286`) : valeurs entre apostrophes, que Compose lit telles quelles. Relevé local du 12/11/2026 : `set "V=a^b"` rend `V=a^b` ; la vérification de l'audit rejoue la ligne `NEXUS_HOME` d'un profil « a^b » sans erreur en page 850 (`findings.json`, champ `verification`).
  - [Existant vérifié] Les refus de `NextButtonClick` passent par `MsgBox` (`packaging/windows/nexus-installer.iss.template:1105-1242`), comme l'avis de port remplacé (`NotifyPortSelection`, `:611-618`, appelée en `:1142` et `:1237`) et l'avis de port réservé (`:1232-1236`). Seul le refus du dossier d'installation passe par `SuppressibleMsgBox` (`:1092-1099`), forme que le smoke vérifie en installation silencieuse (`scripts/release/test-windows-installer.ps1:330-347`). [Écart] Le blocage d'une installation silencieuse Docker sur une boîte modale est déduit de la lecture, pas rejoué (`etat-2026-11-12.md`, « Angles morts ») ; le smoke ne déploie jamais Docker (`packaging/windows/nexus-installer.iss.template:131-134`).
  - [Exigence] L'assistant accepte `^` dans `NEXUS_HOME`, le token REST, le dossier de dépôt Docker et l'URL Ollama, l'écrit tel quel et refuse toujours retour à la ligne et guillemet double.
  - [Exigence] En installation silencieuse avec `/SUPPRESSMSGBOXES`, un refus ou un avis de l'assistant est journalisé sans boîte modale ; un refus interrompt l'installation.
  - [Décision] Retirer `^` de `ContainsUnsafeConfigChars`, plutôt que de ne valider `RuntimePage.Values[0]` que si `InstallNative()` : le même contrôle de la page Docker porte sur `DockerPage.Values[5]`, qui vaut aussi le profil, et un profil « a^b » resterait refusé (voir `design.md`, « Décisions »).
  - [Décision] Toutes les boîtes de `NextButtonClick` et de `NotifyPortSelection` passent à `SuppressibleMsgBox`, avis d'information compris : chacune bloquerait une installation silencieuse.
- Lanceurs natifs dans un dossier d'installation qui contient `^` (préexistant, hors `AUD-QUA-29`, sans identifiant d'audit)
  - [Existant vérifié] `nexus.cmd`, `nexus-mcp.cmd`, `nexus-assistant-clients.cmd` et `nexus-rest.cmd` chargent leur configuration par `if exist "%~dp0config\nexus-native.env.cmd" call "%~dp0config\nexus-native.env.cmd"` (`scripts/release/build-windows-distribution.ps1:263`, `:271`, `:279`, `:287`). Relevé local du 12/11/2026 (Windows 10, `cmd.exe`, lanceur démarré comme processus, console visible ou cachée) : dans un dossier `in^st`, `CALL` double le `^` même entre guillemets et échoue (« Le chemin d'accès spécifié est introuvable ») ; `NEXUS_HOME` et `NEXUS_REST_PORT` restent vides et le programme démarre avec ses valeurs par défaut. Le dossier d'installation par défaut `{localappdata}\Programs\NEXUS` (`packaging/windows/nexus-installer.iss.template:17`) est sous le profil.
  - [Existant vérifié] Sans `config\nexus-native.env.cmd` (distribution portable), les lanceurs gardent leurs valeurs par défaut ; `nexus-rest.cmd` pose alors `127.0.0.1` et `8080` (`scripts/release/build-windows-distribution.ps1:288-289`).
  - [Exigence] Un lanceur natif charge `config\nexus-native.env.cmd` quel que soit son dossier d'installation, `^` compris, et lance son programme depuis le répertoire courant de l'appelant ; un dossier de configuration qu'il ne peut pas ouvrir l'arrête avec un message et un code non nul ; sans ce fichier, il garde ses valeurs par défaut.
  - [Décision] Décision du mainteneur du 12/11/2026 : corriger ce défaut dans ce changement plutôt que par un constat séparé. Forme retenue : entrer dans `config` par `pushd "%~dp0config" || goto configError`, appeler `call .\nexus-native.env.cmd`, puis `popd` ; l'étiquette `:configError` écrit un message sur la sortie d'erreur et rend `exit /b 1` (voir `design.md`, « Décisions », pour la sonde et les formes écartées).

## Type de changement

fonctionnel (validation de l'assistant d'installation Windows, chargement de la configuration native par les lanceurs) : profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- `ContainsUnsafeConfigChars` et ses deux messages de refus (`scripts/release/harden-windows-installer-source.ps1:113-117`, `:165`, `:227`).
- Les boîtes de `NextButtonClick` et de `NotifyPortSelection` (`packaging/windows/nexus-installer.iss.template:611-618`, `:1084-1247`), et les ancres du script de durcissement qui en recopient le texte (`scripts/release/harden-windows-installer-source.ps1:151-231`).
- Le chargement de `config\nexus-native.env.cmd` par les quatre lanceurs natifs (`scripts/release/build-windows-distribution.ps1:260-292`).
- Les tests `scripts/release/test-windows-config-escaping.ps1` et `scripts/release/test-windows-installer.ps1` (contrat statique, contrôle des lanceurs de la distribution construite, smoke).

**Hors périmètre :**
- Les lanceurs Docker (`nexus-docker.cmd`, `nexus-docker-mcp.cmd`, `nexus-docker-up.cmd`) : ils n'appellent pas `config\nexus-native.env.cmd` et lisent `docker\.env` par `for /f` sans `CALL` (`scripts/release/build-windows-distribution.ps1:297`, `:305` ; `nexus-docker-up.cmd` écrit par `packaging/windows/nexus-installer.iss.template:1437`) ; relevé local du 12/11/2026 dans un dossier `ca^ret` sans erreur (`design.md`, « Contexte »).
- Les boîtes affichées hors validation des pages : fermeture de Claude Desktop (`packaging/windows/nexus-installer.iss.template:1709`), fin d'installation (`:2170`, `:2389`), désinstallation (`:2502`).
- La garde `InstallNative()` sur `NEXUS_HOME` à la page Docker : sans `^`, le contrôle n'y refuse plus que retour à la ligne et guillemet double, absents d'un nom de profil Windows.
- Les fragments TOML et JSON des clients : ils ne contiennent que des chemins sous `{app}` (`packaging/windows/nexus-installer.iss.template:1393-1394`, `:1424-1425`), sans `NEXUS_HOME` ni dossier de dépôt.

## Capabilities

### New Capabilities

### Modified Capabilities
- `installer-client-configuration`: ajout de trois exigences : `^` accepté et écrit tel quel dans les valeurs de la configuration générée, refus et avis de l'assistant sans boîte modale en installation silencieuse, configuration native chargée par les lanceurs quel que soit le dossier d'installation.

## Impact

- Installateur : `scripts/release/harden-windows-installer-source.ps1` (`ContainsUnsafeConfigChars`, messages, ancres des validations), `packaging/windows/nexus-installer.iss.template` (`NotifyPortSelection`, `NextButtonClick`).
- Distribution : `scripts/release/build-windows-distribution.ps1` (lanceurs `nexus.cmd`, `nexus-mcp.cmd`, `nexus-assistant-clients.cmd`, `nexus-rest.cmd`), donc l'archive portable comme l'installateur.
- Tests : `scripts/release/test-windows-config-escaping.ps1`, `scripts/release/test-windows-installer.ps1` ; workflow `Windows installer` (`.github/workflows/windows-installer.yml`, `windows-latest`), seul à exécuter ces tests.
- Documentation : `docs/developer/release-and-recovery.md` (section `OpenSpec installer-client-configuration`), `docs/user/windows-installation.md` (« Écran 4 — Configuration runtime », « Écran 8 — Configuration Docker »), `docs/developer/openspec-traceability.md` à l'implémentation.
- Audit : statut de `AUD-QUA-29` dans `docs/project/audits/2026-10-17/findings.json`. Le défaut des lanceurs n'a pas d'entrée d'audit : il est consigné dans la PR et dans la page durable.
- Aucun code Java, aucune migration SQLite, aucun ADR.

## Questions ouvertes

Aucune : la question des lanceurs natifs est tranchée par la décision du mainteneur du 12/11/2026.
