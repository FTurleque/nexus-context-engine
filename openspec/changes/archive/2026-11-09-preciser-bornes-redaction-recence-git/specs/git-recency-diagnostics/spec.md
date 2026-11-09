# Spec Delta

## ADDED Requirements

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
