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

### Requirement: Le signal de récence Git est borné en chemins
Le signal de récence Git SHALL être ignoré, candidats inchangés, avec la raison `récence Git ignorée : budget de chemins dépassé` et sans `WARNING`, quand plus de 512 chemins candidats sont fournis ou quand, sur les 50 derniers commits, les modifications des chemins candidats dépassent 1 024 entrées dans un commit ou 4 096 cumulées.

#### Scenario: Plus de 512 chemins candidats
- **GIVEN** 513 chemins candidats distincts dans un projet versionné
- **WHEN** le signal de récence est calculé
- **THEN** les candidats sont renvoyés inchangés avec la raison `récence Git ignorée : budget de chemins dépassé` lorsque l'explication est demandée
- **AND** aucun enregistrement de niveau `WARNING` n'est émis

#### Scenario: Exactement 512 chemins candidats
- **GIVEN** 512 chemins candidats distincts dans un projet versionné
- **WHEN** le signal de récence est calculé
- **THEN** la récence est calculée et aucune raison de budget n'est ajoutée

#### Scenario: Commit au-delà de 1 024 chemins
- **GIVEN** un historique dont un des 50 derniers commits modifie 1 025 chemins, dont un chemin candidat
- **WHEN** le signal de récence est calculé
- **THEN** la récence du chemin candidat est calculée sur les seuls chemins candidats et aucune raison de budget n'est ajoutée
- **AND** aucun enregistrement de niveau `WARNING` n'est émis

#### Scenario: Cumul des chemins candidats au-delà de 4 096
- **GIVEN** un historique dont les 50 derniers commits modifient les chemins candidats 4 097 fois au total, sans dépasser 1 024 entrées par commit
- **WHEN** le signal de récence est calculé
- **THEN** les candidats sont renvoyés inchangés avec la raison de budget
- **AND** aucun enregistrement de niveau `WARNING` n'est émis

#### Scenario: Cumul des chemins candidats à la borne
- **GIVEN** un historique dont les 50 derniers commits modifient les chemins candidats exactement 4 096 fois au total
- **WHEN** le signal de récence est calculé
- **THEN** la récence est calculée et aucune raison de budget n'est ajoutée
