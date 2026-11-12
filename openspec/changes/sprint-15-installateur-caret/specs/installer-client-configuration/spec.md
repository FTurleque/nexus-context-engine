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
- **GIVEN** un dossier d'installation dont le nom ne contient pas `^` et un `NEXUS_HOME` existant `…\a^b\.nexus`
- **WHEN** un lanceur natif appelle `config\nexus-native.env.cmd`
- **THEN** `NEXUS_HOME` vu par le lanceur désigne ce dossier caractère pour caractère

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
