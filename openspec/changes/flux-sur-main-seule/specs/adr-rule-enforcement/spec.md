# Spec Delta

## MODIFIED Requirements

### Requirement: Les règles des ADR s'exécutent en CI
Toute PR vers `main` SHALL exécuter `archgate check` sur le dépôt et SHALL échouer si une règle signale une violation.

#### Scenario: Violation d'une règle
- **GIVEN** une PR qui viole une règle d'un fichier `docs/architecture/adr/*.rules.ts`
- **WHEN** le workflow de CI s'exécute
- **THEN** le job Archgate échoue en nommant la règle et le fichier

#### Scenario: Aucune violation
- **WHEN** aucune règle ne signale de violation
- **THEN** le job Archgate réussit
