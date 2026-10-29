# Spec Delta

## Purpose

Définit la trace que laisse le signal de récence Git quand il absorbe un incident, sans renoncer à son comportement fail-open.

## ADDED Requirements

### Requirement: Un incident de récence Git est journalisé
Quand le calcul de récence Git échoue pour une autre raison que l'absence de dépôt, `GitRecencyCandidateEnricher` SHALL renvoyer les candidats inchangés et SHALL émettre un enregistrement `System.Logger` de niveau `WARNING` qui nomme le type d'exception.

#### Scenario: Dépôt corrompu
- **WHEN** JGit lève une exception pendant le parcours de l'historique
- **THEN** les candidats sont renvoyés inchangés et un enregistrement `WARNING` est émis

#### Scenario: Projet hors Git
- **WHEN** le projet n'est pas un dépôt Git
- **THEN** les candidats sont renvoyés inchangés et aucun enregistrement `WARNING` n'est émis

### Requirement: La trace ne divulgue ni chemin ni contenu
L'enregistrement émis SHALL NOT contenir de chemin absolu de la machine ni de contenu de fichier du projet.

#### Scenario: Message d'exception avec chemin
- **WHEN** le message de l'exception JGit contient le chemin absolu du dépôt
- **THEN** l'enregistrement journalisé ne contient pas ce chemin

### Requirement: L'explication signale la récence indisponible
Quand une explication est demandée et que la récence Git a échoué, le résultat SHALL porter une raison indiquant que la récence Git est indisponible.

#### Scenario: Explication après incident
- **WHEN** une explication de contexte est demandée sur un projet dont la récence Git échoue
- **THEN** l'explication contient la raison « récence Git indisponible »
