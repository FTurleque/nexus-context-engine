# Spec Delta

## ADDED Requirements

### Requirement: La recherche approchée d'un projet seul est mesurée
Le benchmark d'échelle SHALL comporter un scénario où un seul projet occupe la base et SHALL y rapporter la durée du comptage et de la récupération de la recherche de symboles approchée, sur le schéma courant et sur le schéma qui précède le préfiltre scopé par fichier.

#### Scenario: Profil full du benchmark
- **WHEN** le benchmark d'échelle s'exécute avec le profil `full`
- **THEN** le rapport JSON contient, pour un projet seul de 100 000 fichiers, la durée de comptage et de récupération approchées avec le schéma courant et avec le schéma précédent

#### Scenario: Profil ci du benchmark
- **WHEN** le benchmark d'échelle s'exécute avec le profil `ci`
- **THEN** le rapport JSON contient le même scénario sur le palier mono-projet du profil `ci`
