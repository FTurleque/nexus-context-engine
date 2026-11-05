# git-recency-diagnostics Specification

## Purpose
Définit la trace que laisse le signal de récence Git quand il absorbe un incident, sans renoncer à son comportement fail-open.
Documentation durable : docs/developer/git-context.md#openspec-git-recency-diagnostics

## Requirements

### Requirement: Un incident de récence Git est journalisé
Quand le calcul de récence Git échoue pour une autre raison que l'absence de dépôt, `GitRecencyCandidateEnricher` SHALL renvoyer les candidats inchangés et SHALL émettre un enregistrement `System.Logger` de niveau `WARNING` qui nomme le type d'exception.

#### Scenario: Dépôt corrompu
- **GIVEN** un dépôt Git dont l'historique ne peut pas être parcouru
- **WHEN** JGit lève une exception pendant le parcours de l'historique
- **THEN** les candidats sont renvoyés inchangés et un enregistrement `WARNING` est émis
- **AND** aucune exception n'est propagée à l'appelant

#### Scenario: Projet hors Git
- **GIVEN** un projet qui n'est pas un dépôt Git
- **WHEN** la récence Git est calculée
- **THEN** les candidats sont renvoyés inchangés et aucun enregistrement `WARNING` n'est émis
- **AND** aucune raison d'explication n'est ajoutée

### Requirement: La trace ne divulgue ni chemin ni contenu
L'enregistrement émis SHALL NOT contenir de chemin absolu de la machine ni de contenu de fichier du projet.

#### Scenario: Message d'exception avec chemin
- **GIVEN** une exception JGit dont le message contient le chemin absolu du dépôt
- **WHEN** l'incident est journalisé
- **THEN** l'enregistrement journalisé ne contient pas ce chemin

### Requirement: L'explication signale la récence indisponible
Quand une explication est demandée et que la récence Git a échoué, le résultat SHALL porter une raison indiquant que la récence Git est indisponible.

#### Scenario: Explication après incident
- **GIVEN** un projet dont la récence Git échoue
- **WHEN** une explication de contexte est demandée
- **THEN** l'explication contient la raison « récence Git indisponible »
