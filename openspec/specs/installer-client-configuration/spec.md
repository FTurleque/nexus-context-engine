# installer-client-configuration Specification

## Purpose
Garantit que l'installateur Windows connecte et déconnecte NEXUS des clients IA sans altérer les autres entrées de leurs fichiers de configuration MCP, et que les valeurs injectées dans les scripts qu'il génère ne changent pas leur syntaxe.
Documentation durable : docs/developer/release-and-recovery.md#openspec-installer-client-configuration

## Requirements

### Requirement: Les configurations des clients sont lues et écrites en UTF-8
Quand l'installateur ajoute ou retire l'entrée `nexus` d'un fichier de configuration MCP d'un client IA, il SHALL lire le fichier en UTF-8 et l'écrire en UTF-8 sans BOM, de sorte que toute autre entrée reste identique caractère pour caractère.

#### Scenario: Chemin accentué dans une autre entrée
- **GIVEN** un `claude_desktop_config.json` UTF-8 sans BOM qui déclare un serveur `outil` de commande `C:\Users\Hélène\outil.exe`
- **WHEN** l'installateur connecte NEXUS à Claude Desktop
- **THEN** le fichier contient l'entrée `nexus` et la commande de `outil` vaut toujours `C:\Users\Hélène\outil.exe`

#### Scenario: Désinstallation
- **GIVEN** le même fichier, avec l'entrée `nexus` ajoutée par l'installateur
- **WHEN** NEXUS est désinstallé
- **THEN** l'entrée `nexus` est retirée et l'entrée `outil` est inchangée

### Requirement: Un fichier de configuration illisible n'est jamais réécrit
Quand le fichier de configuration d'un client ne se lit pas comme un objet JSON, l'installateur SHALL NOT le modifier et SHALL signaler l'échec par un code de sortie non nul du script de connexion ou de déconnexion.

#### Scenario: JSON invalide
- **GIVEN** un `claude_desktop_config.json` dont le contenu est `{"mcpServers": {`
- **WHEN** l'installateur tente de connecter NEXUS
- **THEN** le script de connexion se termine avec un code non nul
- **AND** le fichier garde exactement ses octets d'origine et aucune sauvegarde n'est requise pour le restaurer

#### Scenario: Plusieurs fichiers dont un invalide
- **GIVEN** deux fichiers de configuration Claude Desktop détectés, le premier invalide et le second valide
- **WHEN** l'installateur connecte NEXUS
- **THEN** le script de connexion lit et contrôle les deux fichiers avant d'en écrire un, et se termine avec un code non nul
- **AND** aucun des deux fichiers n'est modifié, et aucun contenu de l'un n'est écrit dans l'autre

### Requirement: Une sauvegarde précède toute réécriture d'une configuration existante
Avant de réécrire un fichier de configuration de client existant, l'installateur SHALL en conserver une copie octet pour octet à côté du fichier, et SHALL remplacer le fichier par une écriture complète dans un fichier temporaire du même dossier suivie d'un remplacement.

#### Scenario: Connexion réussie
- **GIVEN** un fichier de configuration Copilot JetBrains `mcp.json` existant et valide
- **WHEN** l'installateur connecte NEXUS
- **THEN** une copie du fichier d'origine existe dans le même dossier avec un nom qui l'identifie comme sauvegarde NEXUS
- **AND** le fichier `mcp.json` final est un JSON valide

#### Scenario: Échec pendant l'écriture
- **WHEN** l'écriture du fichier temporaire échoue
- **THEN** le fichier de configuration d'origine est inchangé et le script se termine avec un code non nul

### Requirement: Les valeurs injectées dans les scripts PowerShell générés sont échappées
Une valeur placée entre apostrophes dans un script PowerShell généré par l'installateur SHALL voir doublés l'apostrophe ASCII et chacun des caractères U+2018, U+2019, U+201A et U+201B, de sorte que le script se lit sans erreur et rend la valeur d'origine.

#### Scenario: Dossier d'installation avec apostrophe typographique
- **GIVEN** un dossier d'installation `C:\Outils\Jean’s NEXUS`
- **WHEN** l'installateur génère le script de connexion des clients
- **THEN** le script s'analyse sans erreur de syntaxe et la commande enregistrée pour `nexus` contient `C:\Outils\Jean’s NEXUS\lib\nexus-mcp.jar`

### Requirement: Les fichiers de configuration générés par l'installateur sont écrits en UTF-8 sans BOM
L'installateur SHALL écrire `docker\.env`, `integrations\codex-desktop.mcp.toml`, `integrations\copilot-jetbrains.mcp.json` et `integrations\generic-mcp.json` en UTF-8 sans BOM, de sorte que chaque valeur qu'il y place, dossier d'installation ou dossier de dépôt Docker, se relise en UTF-8 caractère pour caractère, accents compris.

#### Scenario: Dossier d'installation accentué dans le fragment Codex
- **GIVEN** un dossier d'installation dont le nom contient `Instàllé Hélène`, le runtime natif et le client Codex Desktop retenus
- **WHEN** l'installateur écrit les fichiers d'intégration
- **THEN** `integrations\codex-desktop.mcp.toml` se décode en UTF-8 strict et sa clé `command` désigne `<dossier d'installation>\app\runtime\bin\java.exe`, caractère pour caractère
- **AND** le fichier ne commence pas par les octets `EF BB BF`

#### Scenario: Connexion de Codex Desktop depuis un dossier accentué
- **GIVEN** le fragment TOML du scénario précédent et un fichier `.codex\config.toml` existant dans le profil
- **WHEN** `connect-codex-desktop.ps1` ajoute le bloc NEXUS
- **THEN** `.codex\config.toml` contient le chemin du Java embarqué caractère pour caractère et aucun caractère U+FFFD

#### Scenario: Fragments JSON d'import manuel
- **GIVEN** le même dossier d'installation et les clients Copilot JetBrains et générique retenus
- **WHEN** l'installateur écrit `copilot-jetbrains.mcp.json` et `generic-mcp.json`
- **THEN** chaque fichier, décodé en UTF-8 strict puis lu comme JSON, donne pour `nexus` une commande égale au chemin du Java embarqué
- **AND** aucun des deux fichiers ne commence par les octets `EF BB BF`

#### Scenario: Dossier de dépôt Docker accentué
- **GIVEN** un déploiement Docker dont le dossier de dépôt est `C:\Users\Hélène`
- **WHEN** l'installateur écrit `docker\.env`
- **THEN** le fichier, décodé en UTF-8 strict, contient la ligne `NEXUS_REPOSITORY_BIND='C:/Users/Hélène'`
- **AND** sa première ligne commence par `NEXUS_DOCKER_IMAGE=`, sans BOM

### Requirement: Les lanceurs natifs reçoivent les valeurs de la configuration native telles qu'elles ont été saisies
Quand un lanceur natif (`nexus.cmd`, `nexus-mcp.cmd`, `nexus-rest.cmd`, `nexus-assistant-clients.cmd`) appelle `config\nexus-native.env.cmd` dans une console, visible ou cachée, il SHALL obtenir dans `NEXUS_HOME` et les autres variables de ce fichier les valeurs saisies dans l'installateur, caractère pour caractère, quelle que soit la page de code de la console, et SHALL laisser la console dans sa page de code d'origine.

#### Scenario: Profil accentué et valeurs par défaut
- **GIVEN** un profil Windows `…\Hélène Łukasz` (variable `USERPROFILE`), une installation native avec le `NEXUS_HOME` par défaut et une console en page de code 850
- **WHEN** `nexus.cmd` enregistre un projet, puis qu'un second appel de `nexus.cmd` liste les projets
- **THEN** les deux commandes réussissent, la liste contient le projet enregistré et la base `nexus.db` est sous `…\Hélène Łukasz\.nexus`
- **AND** aucun dossier au nom altéré (par exemple `HÚlÞne`) n'est créé

#### Scenario: Dossier hors profil avec un caractère absent de la page ANSI
- **GIVEN** un `NEXUS_HOME` saisi hors du profil, contenant `Données` et `Łódź`, et un dossier existant à ce chemin
- **WHEN** un lanceur natif appelle `config\nexus-native.env.cmd` dans une console cachée (processus lancé sans fenêtre visible)
- **THEN** `NEXUS_HOME` vu par le lanceur désigne ce dossier caractère pour caractère

#### Scenario: Signe pourcent dans la valeur
- **GIVEN** un `NEXUS_HOME` saisi qui contient `100%`
- **WHEN** un lanceur natif appelle `config\nexus-native.env.cmd`
- **THEN** `NEXUS_HOME` vu par le lanceur contient `100%` à l'identique

#### Scenario: Page de code de la console restaurée
- **GIVEN** une console en page de code 850 et une configuration native qui contient un caractère non ASCII
- **WHEN** un lanceur natif a appelé `config\nexus-native.env.cmd`
- **THEN** `chcp` dans la même console rend toujours la page 850

### Requirement: L'assistant refuse un dossier d'installation hors de la page ANSI
L'assistant d'installation SHALL refuser, avec un message qui nomme la limite, un dossier d'installation dont le nom ne fait pas l'aller-retour par la page de code ANSI active du poste, parce que le runtime Java embarqué ne démarre pas depuis un tel dossier.

#### Scenario: Dossier d'installation avec un caractère absent de la page ANSI
- **GIVEN** un poste en page ANSI 1252 et un dossier d'installation qui contient `Łukasz`
- **WHEN** l'utilisateur valide la page du dossier d'installation
- **THEN** l'assistant affiche un refus explicite et reste sur cette page

#### Scenario: Dossier d'installation accentué de la page ANSI
- **GIVEN** un poste en page ANSI 1252 et un dossier d'installation qui contient `Instàllé Hélène`
- **WHEN** l'utilisateur valide la page du dossier d'installation
- **THEN** l'assistant passe à la page suivante

### Requirement: L'assistant accepte un accent circonflexe dans les valeurs de la configuration générée
L'assistant d'installation SHALL accepter le caractère `^` dans `NEXUS_HOME`, le token REST, le dossier de dépôt Docker et l'URL Ollama, et SHALL l'écrire tel quel dans `config\nexus-native.env.cmd` et `docker\.env`. Il SHALL continuer de refuser, dans ces mêmes valeurs, retour chariot, saut de ligne et guillemet double.

#### Scenario: Profil contenant un accent circonflexe en mode Docker
- **GIVEN** un profil Windows `C:\Users\a^b` (variable `USERPROFILE`), un déploiement Docker, le profil d'installation standard (page Runtime sautée) et les valeurs par défaut
- **WHEN** l'utilisateur valide la page de configuration Docker
- **THEN** l'assistant passe à la page suivante sans message de refus
- **AND** `docker\.env` contient la ligne `NEXUS_REPOSITORY_BIND='C:/Users/a^b'`

#### Scenario: NEXUS_HOME contenant un accent circonflexe lu par un lanceur natif
- **GIVEN** un `NEXUS_HOME` existant `…\a^b\.nexus` écrit par l'assistant dans `config\nexus-native.env.cmd`
- **WHEN** un lanceur natif charge `config\nexus-native.env.cmd`
- **THEN** `NEXUS_HOME` vu par le programme lancé désigne ce dossier caractère pour caractère

#### Scenario: Guillemet double toujours refusé
- **GIVEN** le profil d'installation personnalisé et un `NEXUS_HOME` saisi qui contient `"`
- **WHEN** l'utilisateur valide la page Runtime
- **THEN** l'assistant affiche un refus explicite et reste sur cette page
- **AND** aucun fichier de configuration n'est écrit

### Requirement: En installation silencieuse, les messages de l'assistant n'attendent aucune réponse
Quand l'installation est lancée en mode silencieux avec `/SUPPRESSMSGBOXES`, chaque refus ou avis émis par l'assistant à la validation d'une page SHALL être journalisé sans boîte modale ; un refus SHALL arrêter l'installation avec un code de sortie non nul, sans écrire de configuration. Hors mode silencieux, le message SHALL rester affiché à l'utilisateur.

#### Scenario: Valeur refusée en installation silencieuse
- **GIVEN** une installation lancée avec `/VERYSILENT /SUPPRESSMSGBOXES /LOG` et une valeur que la validation d'une page refuse
- **WHEN** l'assistant valide cette page
- **THEN** l'installateur se termine sans attendre de réponse, avec un code de sortie non nul
- **AND** le journal contient le message de refus et aucune configuration n'est écrite

#### Scenario: Port remplacé en installation silencieuse
- **GIVEN** une installation lancée avec `/VERYSILENT /SUPPRESSMSGBOXES /LOG` et un port REST demandé déjà occupé
- **WHEN** l'assistant valide la page qui porte ce port
- **THEN** l'avis du port retenu est journalisé sans boîte modale et l'installation continue avec ce port

#### Scenario: Refus en installation interactive
- **GIVEN** une installation interactive et une valeur que la validation d'une page refuse
- **WHEN** l'utilisateur valide cette page
- **THEN** l'assistant affiche le message de refus et reste sur cette page

### Requirement: Un lanceur natif charge sa configuration quel que soit le dossier d'installation
Les lanceurs `nexus.cmd`, `nexus-mcp.cmd`, `nexus-assistant-clients.cmd` et `nexus-rest.cmd` SHALL charger `config\nexus-native.env.cmd` quel que soit leur dossier d'installation, `^` compris, et SHALL lancer leur programme depuis le répertoire courant de l'appelant. Si ce fichier existe mais que son dossier ne s'ouvre pas, le lanceur SHALL s'arrêter avec un message d'erreur et un code non nul, sans lancer le programme. Sans ce fichier, il SHALL garder ses défauts.

#### Scenario: Dossier d'installation contenant un accent circonflexe
- **GIVEN** un dossier d'installation `…\in^st` dont `config\nexus-native.env.cmd` pose `NEXUS_HOME` sur un dossier existant et `NEXUS_REST_PORT=9123`
- **WHEN** un lanceur natif de ce dossier est démarré comme processus, console visible ou cachée
- **THEN** le programme lancé voit `NEXUS_HOME` caractère pour caractère et `NEXUS_REST_PORT=9123`
- **AND** la sortie d'erreur du lanceur ne contient aucun message de chemin introuvable

#### Scenario: Répertoire courant de l'appelant conservé
- **GIVEN** un dossier d'installation `…\in^st` avec sa configuration et un appelant dont le répertoire courant est `…\caller`
- **WHEN** l'appelant démarre `nexus.cmd` avec un argument relatif `.`
- **THEN** le programme lancé a pour répertoire courant `…\caller` et résout `.` vers ce dossier
- **AND** le répertoire courant de l'appelant est inchangé après la fin du lanceur

#### Scenario: Dossier de configuration impossible à ouvrir
- **GIVEN** un dossier d'installation dont `config\nexus-native.env.cmd` existe mais dont le dossier `config` ne peut pas être ouvert par `pushd`
- **WHEN** un lanceur natif est démarré
- **THEN** le lanceur se termine avec le code 1 et un message sur la sortie d'erreur qui nomme `config\nexus-native.env.cmd`
- **AND** le programme n'est pas lancé, aucune donnée n'est écrite sous `NEXUS_HOME`

#### Scenario: Distribution portable sans configuration
- **GIVEN** un dossier d'installation sans `config\nexus-native.env.cmd`, `^` compris dans son nom
- **WHEN** `nexus-rest.cmd` est démarré
- **THEN** le lanceur ne signale aucune erreur et le programme reçoit l'hôte `127.0.0.1` et le port `8080` par défaut

### Requirement: Un connecteur MCP natif démarre NEXUS quel que soit le dossier d'installation
L'entrée `nexus` d'un connecteur natif de l'installateur, enregistrée dans un client MCP ou écrite dans un fragment d'import, SHALL lancer directement `{app}\app\runtime\bin\java.exe` sur `{app}\lib\nexus-mcp.jar`, sans `cmd.exe`, avec dans son environnement les valeurs non vides que `config\nexus-native.env.cmd` donne à `nexus-mcp.cmd`. Le serveur MCP SHALL démarrer même si le dossier d'installation contient une espace ou un `^`.

#### Scenario: Dossier d'installation contenant un accent circonflexe
- **GIVEN** un dossier d'installation `…\in^st` et l'entrée native `nexus` de ce dossier, avec `NEXUS_HOME` dans son environnement
- **WHEN** un client lance l'entrée comme processus, ses arguments étant mis entre guillemets selon les règles de `CreateProcess`, et envoie `initialize`
- **THEN** le serveur MCP répond et ouvre sa base sous le `NEXUS_HOME` de l'entrée

#### Scenario: Dossier d'installation contenant une espace
- **GIVEN** un profil `C:\Users\John Doe` et le dossier d'installation par défaut, ou tout dossier d'installation `…\in st`
- **WHEN** un client lance l'entrée native `nexus` de ce dossier et envoie `initialize`
- **THEN** le serveur MCP répond

#### Scenario: Configuration native transmise au client
- **GIVEN** un `NEXUS_HOME` saisi ou par défaut, et Ollama activé ou non
- **WHEN** l'installateur génère les connecteurs natifs et les fragments d'import
- **THEN** l'entrée `nexus` porte `NEXUS_HOME` dans son environnement, et `NEXUS_SEMANTIC_PROVIDER` et `NEXUS_OLLAMA_BASE_URL` quand ils ne sont pas vides
- **AND** aucune entrée native ne lance `cmd.exe` ni `nexus-mcp.cmd`
