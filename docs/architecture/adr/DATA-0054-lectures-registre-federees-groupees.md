---
id: DATA-0054
title: "Grouper les lectures du registre pour les opérations fédérées"
domain: data
rules: false
---

# Grouper les lectures du registre pour les opérations fédérées

- **Statut** : Accepted
- **Date** : 2026-09-12
- **Complète** : ADR-0052

## Context

Les contrôles de cohérence ajoutés aux opérations fédérées relisaient chaque
descripteur avant, pendant et après la lecture. À 25 projets, cela ouvrait 75
connexions SQLite, avec autant de validations du stockage. Une tentative
d'acquisition des verrous sur des threads virtuels a également bloqué un essai
local et compliquait leur propriété.

## Decision

Le port `ProjectRepository.findByIds` charge uniquement les projets demandés,
sans doublons, dans l'ordre de la portée. L'adaptateur SQLite utilise une seule
connexion et un snapshot transactionnel pour les descripteurs, langues et
technologies. Les requêtes sont bornées à 500 identifiants par lot.

La façade conserve ses trois contrôles de disponibilité, désormais groupés.
Les verrous restent acquis séquentiellement par UUID et libérés dans l'ordre
inverse, sur le même thread propriétaire. Une acquisition partielle libère tous
les verrous déjà obtenus et conserve l'exception initiale.

## Do's and Don'ts

### Do

- Charger par `ProjectRepository.findByIds` uniquement les projets demandés, sans doublons, dans l'ordre de la portée.
- Lire dans l'adaptateur SQLite les descripteurs, langues et technologies par une seule connexion et un snapshot transactionnel.
- Borner les requêtes à 500 identifiants par lot.
- Garder les trois contrôles de disponibilité de la façade, groupés.
- Acquérir les verrous séquentiellement par UUID et les libérer dans l'ordre inverse, sur le même thread propriétaire.
- Libérer tous les verrous déjà obtenus lors d'une acquisition partielle, en conservant l'exception initiale.

### Don't

- Ne pas acquérir ni libérer les verrous hors du thread propriétaire.
- Ne pas relire les descripteurs projet par projet pour les contrôles de façade.

## Consequences

Une portée de 100 projets au plus requiert trois ouvertures du registre pour les
contrôles de façade, indépendamment du nombre de projets. Aucun cache de statut
ne peut masquer une mutation. Les protections de l'ADR-0052 restent applicables.

Les tests couvrent les métadonnées, l'ordre, les doublons, les UUID absents, la
frontière de lot et la libération des verrous après succès ou échec partiel.

## Compliance and Enforcement

- Tests `SqliteProjectRepositoryBulkReadTest` (`core/src/test/java/com/nexus/persistence/sqlite/`, métadonnées, ordre de la portée et frontière de lot) et `NexusApplicationFederatedScopePolicyTest.federatedReadsReleaseLocksAfterSuccessAndPartialAcquisitionFailure` (`core/src/test/java/com/nexus/application/`), lancés par `mvnw clean install` (workflow `.github/workflows/ci.yml` : job Linux reactor, et job Windows gate par `scripts/validate-phase-6.ps1`).
- Aucune règle Archgate : la borne de 500 et l'ordre des verrous sont couverts par ces tests ; vérifié en revue.
