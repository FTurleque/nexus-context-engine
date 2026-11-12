# Spec Delta

## ADDED Requirements

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
