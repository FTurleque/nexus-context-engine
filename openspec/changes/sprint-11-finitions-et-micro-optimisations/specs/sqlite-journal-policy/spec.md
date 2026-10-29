# Spec Delta

## Purpose

Définit le mode de journal des bases SQLite canoniques de NEXUS et le garde-fou qui empêche de le changer sans décision consignée.

## ADDED Requirements

### Requirement: Les bases canoniques utilisent le journal rollback
Une base ouverte par `SqliteDatabase` SHALL être en mode de journal `delete`, conformément à l'ADR qui consigne le choix rollback/WAL.

#### Scenario: Lecture du mode de journal
- **WHEN** un test ouvre une base par `SqliteDatabase` et lit `PRAGMA journal_mode`
- **THEN** la valeur est `delete`

#### Scenario: Changement de mode sans décision
- **WHEN** le code active WAL sur la connexion
- **THEN** le test échoue avec un message qui cite l'ADR à remplacer
