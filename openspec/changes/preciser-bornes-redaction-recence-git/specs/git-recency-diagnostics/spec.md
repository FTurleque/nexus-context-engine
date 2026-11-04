# Spec Delta

## ADDED Requirements

### Requirement: Le signal de récence Git est borné en chemins
Le signal de récence Git SHALL être ignoré, candidats inchangés, avec la raison d'explication `récence Git ignorée : budget de chemins dépassé`, quand plus de 512 chemins candidats sont fournis, ou quand les 50 derniers commits modifient plus de 1 024 chemins chacun ou 4 096 chemins cumulés.

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
- **GIVEN** un historique dont un des 50 derniers commits modifie 1 025 chemins
- **WHEN** le signal de récence est calculé
- **THEN** les candidats sont renvoyés inchangés avec la raison de budget
- **AND** aucun enregistrement de niveau `WARNING` n'est émis
