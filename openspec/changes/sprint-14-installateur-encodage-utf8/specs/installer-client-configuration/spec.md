# Spec Delta

## ADDED Requirements

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
