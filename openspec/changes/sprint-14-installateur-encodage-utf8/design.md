# Design

**Profil :** complet

## Contexte

- [Existant vérifié] L'installateur est un gabarit Pascal (`packaging/windows/nexus-installer.iss.template`) que deux scripts de durcissement réécrivent par remplacement exact avant compilation (`scripts/release/build-windows-installer.ps1`, appel de `Protect-NexusInstallerSource`) : `scripts/release/harden-windows-installer-source.ps1:166-182` remplace l'écrivain de `config\nexus-native.env.cmd` pour échapper `%` par `CmdEnvEscape` (`:26-33`), puis `scripts/release/harden-windows-rest-auth-source.ps1:15-45` remplace à nouveau ce bloc pour le jeton REST. Toute modification de l'écrivain touche donc le gabarit et les deux ancres.
- [Existant vérifié] `SaveStringToFile` prend une `AnsiString` : une variable `String` y est convertie vers la page ANSI (`packaging/windows/nexus-installer.iss.template:1438-1442`). `Content` (`:1282`) et `EnvContent` (`:1308`) sont des `String` ; `ComposeTemplate` est une `AnsiString` (`:1309`) recopiée sans conversion.
- [Existant vérifié] Inno Setup 7.0.2 compile l'installateur (`scripts/release/ensure-inno-setup.ps1:11`) ; Pascal Scripting y fournit `SaveStringsToUTF8FileWithoutBOM(FileName, S, Append)` (documentation Inno Setup, rubrique « SaveStringsToUTF8FileWithoutBOM »), à côté de `SaveStringsToUTF8File` déjà utilisé par `SavePowerShellScript` (`:1443-1450`).
- [Existant vérifié] `cmd.exe` décode un fichier batch dans la page de code de la console. Les lanceurs (`scripts/release/build-windows-distribution.ps1:260-273` et suivants) font `setlocal DisableDelayedExpansion` puis `call "%~dp0config\nexus-native.env.cmd"`. Le fichier pose `NEXUS_HOME` sans condition : il écrase la valeur de l'appelant (`scripts/release/harden-windows-rest-auth-source.ps1:38-44`).
- [Existant vérifié] `NEXUS_HOME` vaut par défaut `GetEnv('USERPROFILE') + '\.nexus'` (`packaging/windows/nexus-installer.iss.template:920`), est enregistré dans le registre en Unicode (`:1987`) et relu à la réinstallation (`:697`). La documentation utilisateur l'annonce sous la forme `%USERPROFILE%\.nexus` (`docs/user/windows-installation.md`, « Écran 4 — Configuration runtime »).
- [Existant vérifié] Le mode smoke force le déploiement natif, désactive Docker (`:131-134`) et laisse toutes les cases de clients décochées et désactivées (`:986-993` ; cases créées décochées `:767` et désactivées `:797` tant que le préflight des clients n'a pas tourné, préflight sauté en smoke `:873`) : ni fragment TOML ni fragment JSON n'est écrit, et `docker\.env` non plus.
- [Existant vérifié] `scripts/release/test-windows-config-escaping.ps1` rejoue déjà une règle Pascal (`PowerShellSingleQuote`) en PowerShell à partir de la source durcie, puis exécute le résultat ; c'est le modèle retenu pour l'écrivain du `.cmd`.

## Objectifs / Hors objectifs

**Objectifs :**
- `installer-client-configuration` › Les fichiers de configuration générés par l'installateur sont écrits en UTF-8 sans BOM.
- `installer-client-configuration` › Les lanceurs natifs reçoivent les valeurs de la configuration native telles qu'elles ont été saisies.

**Hors objectifs :**
- Changer le contenu des fichiers (clés, ordre, échappements JSON et TOML) au-delà de l'encodage et de l'écriture de `NEXUS_HOME`.
- Faire lire la configuration native par NEXUS lui-même (fichier de propriétés UTF-8 lu par Java) : refonte écartée, voir « Décisions ».

## Composants et responsabilités

- [Proposition] Une procédure `SaveUtf8TextFile(Path, Text)` du gabarit, sur le modèle de `SavePowerShellScript`, devient le seul écrivain des fichiers de configuration non PowerShell : `SaveStringsToUTF8FileWithoutBOM`, échec journalisé comme aujourd'hui.
- [Proposition] Une fonction `CmdEnvPathValue(Value)` ajoutée par `harden-windows-installer-source.ps1` à côté de `CmdEnvEscape` : si `Value` commence, sans tenir compte de la casse et à une frontière de dossier, par la valeur de `LOCALAPPDATA` puis de `USERPROFILE` (préfixe le plus long d'abord), elle rend `%LOCALAPPDATA%` ou `%USERPROFILE%` suivi de `CmdEnvEscape(reste)` ; sinon `CmdEnvEscape(Value)`.
- [Proposition] Une fonction `CmdUtf8Frame(Body)` qui, si `Body` contient un caractère au-delà de `#127`, l'entoure de la sauvegarde de la page courante, de `chcp 65001 >nul` et de la restauration ; sinon rend `Body` inchangé.
- Les lanceurs de `scripts/release/build-windows-distribution.ps1` ne changent pas.

## Modèle de domaine et invariants

Invariant : pour toute valeur `v` écrite par l'installateur dans un fichier de configuration, le lecteur prévu de ce fichier (`cmd.exe`, Compose, PowerShell en UTF-8, client MCP) obtient `v` à l'identique. Aucune conversion vers une page de code à huit bits n'intervient entre la saisie et le fichier.

## Traitements et interactions

Vue UML de séquence (cible) — un lanceur natif charge la configuration native ; couvre `installer-client-configuration` › « Les lanceurs natifs reçoivent les valeurs de la configuration native telles qu'elles ont été saisies ».

```mermaid
sequenceDiagram
    participant L as nexus.cmd
    participant E as nexus-native.env.cmd
    participant C as Console (page d'origine, ex. 850)
    L->>E: call (setlocal DisableDelayedExpansion)
    alt contenu ASCII (NEXUS_HOME écrit %USERPROFILE%\.nexus)
        E->>E: set "NEXUS_HOME=%USERPROFILE%\.nexus"
        Note over E: expansion par cmd.exe depuis la variable Unicode
    else contenu avec caractère non ASCII
        E->>C: chcp : page d'origine mémorisée
        E->>C: chcp 65001
        E->>E: set "NEXUS_HOME=D:\Données\Łódź\nexus" (lignes lues en UTF-8)
        E->>C: chcp <page d'origine>
    end
    E-->>L: variables posées
    L->>L: nexus.exe ou java.exe hérite de NEXUS_HOME
```

Lecture : la branche ASCII couvre les valeurs par défaut sous un profil accentué sans dépendre de `chcp` ; la branche `chcp` ne sert qu'à une valeur saisie hors profil ou à un caractère non ASCII dans une autre variable (URL Ollama). Les fichiers UTF-8 (TOML, JSON, `.env`) n'ont pas d'interaction nouvelle : seul leur encodage change.

## États et transitions

Non applicable : les fichiers sont écrits une fois par installation, sans cycle de vie.

## Contrats d'interface

- Forme cible de `config\nexus-native.env.cmd` pour un `NEXUS_HOME` hors profil non ASCII ([Proposition]) :

```text
@echo off
for /f "tokens=2 delims=:." %%P in ('chcp') do set "NEXUS_ENV_PREVIOUS_CP=%%P"
chcp 65001 >nul
set "NEXUS_HOME=D:\Données\Łódź\nexus"
set "NEXUS_REST_HOST=127.0.0.1"
...
chcp %NEXUS_ENV_PREVIOUS_CP% >nul
set "NEXUS_ENV_PREVIOUS_CP="
```

- `tokens=2 delims=:.` lit le numéro dans `Page de codes active : 850` comme dans `Active code page: 850.` ; l'espace de tête est accepté par `chcp`.
- Les fichiers UTF-8 sans BOM se terminent par une ligne vide supplémentaire (`SaveStringsTo*` ajoute un saut de ligne après chaque élément) : sans effet pour `cmd.exe`, Compose, TOML et JSON.
- Les codes de sortie des lanceurs et des scripts de connexion ne changent pas.

## Persistance et intégrité

- Registre : inchangé (valeurs Unicode). Une réinstallation réécrit les fichiers à partir du registre : un poste déjà touché est corrigé par la mise à jour.
- Aucune donnée NEXUS ni migration SQLite.

## Transactions, concurrence et idempotence

Non applicable : écriture unique en fin d'installation (`CurStepChanged`, `ssPostInstall`, `:2305-2306`), déjà non atomique aujourd'hui, et réécrite à chaque installation.

## Sécurité et isolation

- `NEXUS_HOME` refuse toujours retour à la ligne, guillemet double et `^` (`scripts/release/harden-windows-installer-source.ps1:93-99`) ; `%` reste doublé par `CmdEnvEscape` dans la partie littérale. La partie remplacée par `%USERPROFILE%` ou `%LOCALAPPDATA%` est développée par `cmd.exe` à partir de la variable de l'utilisateur courant : aucune valeur saisie n'est évaluée.
- Le jeton REST généré (`NEXUS_REST_API_TOKEN`) est hexadécimal (`GenerateLocalToken`, `packaging/windows/nexus-installer.iss.template:620-639`) et ne déclenche pas la branche `chcp` ; un jeton saisi non ASCII y passe comme toute autre valeur. Aucune sortie ajoutée ne l'affiche (`chcp` redirigé vers `nul`).

## Erreurs et reprise

- Échec d'écriture d'un fichier : journalisé (`Log`) comme pour `SavePowerShellScript` ; comportement inchangé.
- Si `chcp 65001` échoue (processus sans console), la page reste celle d'origine et une valeur littérale non ASCII serait mal décodée : voir [Question] ; les valeurs par défaut restent correctes par la branche ASCII.

## Observabilité

Non applicable : aucun journal nouveau ; `chcp` est redirigé vers `nul` pour ne pas polluer la sortie standard des lanceurs MCP (canal JSON-RPC de `nexus-mcp.cmd`).

## Performance et mesure

Non applicable : un appel `chcp` de plus par lancement, seulement dans la branche non ASCII.

## Compatibilité, migration et retour arrière

- Le contenu ASCII des fichiers est identique octet pour octet à l'actuel, sauf la ligne vide finale et `NEXUS_HOME` écrit sous la forme `%USERPROFILE%\.nexus` : les lecteurs existants ne voient aucune différence.
- `%USERPROFILE%` est développé à l'exécution : pour une installation par utilisateur (`PrivilegesRequired=lowest`), c'est le profil de l'utilisateur qui lance NEXUS, celui qui a installé.
- Retour arrière : réinstaller la version précédente.

## Décisions

- [Décision] UTF-8 sans BOM pour `.env`, TOML et JSON : un BOM ferait partie de la première clé du `.env` lu par Compose et n'est pas attendu par les lecteurs TOML ; `connect-codex-desktop.ps1` lit déjà en UTF-8 (`:1847`).
- [Proposition] Pour le `.cmd`, les deux mécanismes du constat combinés plutôt qu'un seul : la référence `%USERPROFILE%` / `%LOCALAPPDATA%` rend le cas par défaut indépendant de la console ; `chcp 65001` encadré couvre une valeur saisie hors profil. Alternative écartée : refuser un `NEXUS_HOME` non ASCII à l'assistant, régression pour un dossier `D:\Données`. Alternative écartée : un fichier de propriétés lu par Java, qui changerait le contrat des lanceurs et de NEXUS pour un défaut d'installateur.
- [Proposition] En mode smoke, cocher et activer Codex Desktop et le client générique (`:986-993`) pour que le smoke écrive les fragments TOML et JSON ; la connexion réelle reste exclue du smoke (`:2324`).
- Aucun ADR : décision locale à l'installateur, sans format persistant ni contrat public nouveau.

## Risques / Compromis

- [Risque] `chcp 65001` sans console : la branche littérale non ASCII échouerait → test 2.1 exécuté avec `CreateNoWindow` ; la [Question] tranche la suite si le test échoue.
- [Risque] Une ancre de durcissement non mise à jour fait échouer la compilation (`Installer hardening anchor missing`) → les trois sources changent dans la même tâche 1.2, et `scripts/release/test-windows-config-escaping.ps1` charge la source durcie.
- [Risque] Le smoke écrit dans le profil réel du poste (`NEXUS_HOME` de `config\nexus-native.env.cmd` écrase celui du test) → le nouveau cas fixe `USERPROFILE` sur un dossier de `target\installer-smoke` pour l'installation et pour l'exécution de `nexus.cmd`.

## Stratégie de vérification

- `pwsh -NoProfile -File scripts/release/test-windows-config-escaping.ps1` : rejeu de `CmdEnvPathValue` et `CmdUtf8Frame` depuis la source durcie, fichier écrit en UTF-8 sans BOM, exécution par `cmd.exe` en page 850 avec console puis avec `CreateNoWindow`, valeurs `Données\Łódź` et `100%`, page restaurée.
- `pwsh -NoProfile -File scripts/release/test-windows-installer.ps1 -Setup <setup smoke>` : profil `Hélène Łukasz` et `{app}` accentué, `NEXUS_HOME` vu par `nexus.cmd`, fragments TOML et JSON sans BOM et décodés en UTF-8 strict, `connect-codex-desktop.ps1` sur un profil de test ; contrat statique : plus aucun `SaveStringToFile` sur `.env`, `.toml`, `.json` ni `nexus-native.env.cmd`.
- Workflow `Windows installer` (`.github/workflows/windows-installer.yml`) vert sur la PR.
- `bash scripts/release/test-openspec-doc-contracts.sh`, `bash .claude/scripts/contracts.sh`.
- `openspec validate sprint-14-installateur-encodage-utf8 --strict`.

## Traçabilité

| Exigence (`capacité › titre`) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| `installer-client-configuration › Les fichiers de configuration générés par l'installateur sont écrits en UTF-8 sans BOM` | Dossier d'installation accentué dans le fragment Codex | Composants (`SaveUtf8TextFile`) ; Décisions | 1.1, 2.2 | test non écrit (`scripts/release/test-windows-installer.ps1`) |
| `installer-client-configuration › Les fichiers de configuration générés par l'installateur sont écrits en UTF-8 sans BOM` | Connexion de Codex Desktop depuis un dossier accentué | Contexte | 1.1, 2.2 | test non écrit |
| `installer-client-configuration › Les fichiers de configuration générés par l'installateur sont écrits en UTF-8 sans BOM` | Fragments JSON d'import manuel | Composants | 1.1, 2.2 | test non écrit |
| `installer-client-configuration › Les fichiers de configuration générés par l'installateur sont écrits en UTF-8 sans BOM` | Dossier de dépôt Docker accentué | Décisions | 1.1, 1.4, 2.3 | test non écrit (contrat statique ; lecture Compose selon la [Question] de la proposition) |
| `installer-client-configuration › Les lanceurs natifs reçoivent les valeurs de la configuration native telles qu'elles ont été saisies` | Profil accentué et valeurs par défaut | Séquence (branche ASCII) | 1.2, 2.2 | test non écrit (`scripts/release/test-windows-installer.ps1`) |
| `installer-client-configuration › Les lanceurs natifs reçoivent les valeurs de la configuration native telles qu'elles ont été saisies` | Dossier hors profil avec un caractère absent de la page ANSI | Séquence (branche `chcp`) ; Contrats d'interface | 1.2, 2.1 | test non écrit (`scripts/release/test-windows-config-escaping.ps1`) |
| `installer-client-configuration › Les lanceurs natifs reçoivent les valeurs de la configuration native telles qu'elles ont été saisies` | Signe pourcent dans la valeur | Sécurité et isolation | 1.2, 2.1 | test non écrit |
| `installer-client-configuration › Les lanceurs natifs reçoivent les valeurs de la configuration native telles qu'elles ont été saisies` | Page de code de la console restaurée | Contrats d'interface | 1.2, 2.1 | test non écrit |

## Questions ouvertes

- [Question] `chcp 65001` dans un `cmd.exe` lancé par un client MCP sans console visible (`CreateNoWindow`) ou détaché (`DETACHED_PROCESS`) : si le test 2.1 échoue dans l'un de ces modes, faut-il réserver la branche littérale non ASCII aux lanceurs interactifs et le documenter comme limite, ou faire refuser par l'assistant un `NEXUS_HOME` non ASCII hors profil ? Les valeurs par défaut ne sont pas concernées.
