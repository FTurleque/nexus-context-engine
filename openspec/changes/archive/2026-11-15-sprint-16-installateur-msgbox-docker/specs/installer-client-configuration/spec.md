# Spec Delta

## ADDED Requirements

### Requirement: Une installation silencieuse n'attend aucune réponse après l'installation de Docker Desktop
Quand l'installation est lancée en mode silencieux (`/SILENT` ou `/VERYSILENT`) et que l'installateur vient d'installer Docker Desktop, il SHALL lancer Docker Desktop, journaliser ce lancement et se terminer sans afficher de boîte modale, avec ou sans `/SUPPRESSMSGBOXES`. Hors mode silencieux, l'avis qui demande d'accepter les conditions Docker SHALL rester affiché.

#### Scenario: Docker Desktop installé en installation silencieuse
- **GIVEN** un poste sans Docker, une installation lancée avec `/VERYSILENT /LOG` en mode Docker et l'installation automatique de Docker Desktop par défaut
- **WHEN** l'installateur termine l'installation de Docker Desktop puis passe à l'étape post-installation
- **THEN** Docker Desktop est lancé et le journal le consigne
- **AND** l'installateur se termine sans attendre de réponse, que `/SUPPRESSMSGBOXES` soit passé ou non

#### Scenario: Docker Desktop installé en installation interactive
- **GIVEN** une installation interactive et Docker Desktop installé par l'installateur
- **WHEN** Docker Desktop est lancé à la fin de l'installation
- **THEN** une boîte d'information demande d'accepter les conditions Docker au premier démarrage
- **AND** l'installation se termine après la réponse de l'utilisateur

#### Scenario: Docker Desktop déjà présent
- **GIVEN** un poste où Docker Desktop est déjà installé
- **WHEN** l'installation, silencieuse ou interactive, atteint l'étape post-installation
- **THEN** l'installateur ne lance pas Docker Desktop et n'affiche aucun avis sur Docker Desktop

### Requirement: Le contrôle de la source durcie refuse toute boîte modale sans garde de mode silencieux
Le contrôle de release de la source durcie de l'installateur SHALL échouer quand un appel `MsgBox` du gabarit n'est pas précédé, dans sa routine, d'une garde de mode silencieux (`WizardSilent()` ou `UninstSilent`), ou quand un `SuppressibleMsgBox` figure hors de la validation des pages. Il SHALL citer la routine et l'appel fautifs.

#### Scenario: Gabarit courant
- **GIVEN** le gabarit de l'installateur et sa source durcie à ce commit
- **WHEN** le contrôle de la source durcie s'exécute
- **THEN** il réussit : chaque `MsgBox` est gardé et chaque `SuppressibleMsgBox` appartient à la validation des pages

#### Scenario: Garde retirée de l'avis Docker Desktop
- **GIVEN** une copie de la source durcie où l'avis de `StartDockerDesktopAfterInstall` n'a plus sa garde
- **WHEN** le contrôle s'exécute sur cette copie
- **THEN** il échoue en nommant `StartDockerDesktopAfterInstall` et l'appel `MsgBox`

#### Scenario: Boîte ajoutée dans une routine sans garde
- **GIVEN** une copie de la source durcie où une routine quelconque contient un `MsgBox` sans garde
- **WHEN** le contrôle s'exécute sur cette copie
- **THEN** il échoue en nommant cette routine
