# adr-rule-enforcement Specification

## Purpose
Garantit que les règles automatisées des ADR (fichiers `.rules.ts` d'Archgate) s'exécutent à chaque PR avec une version d'outil fixée, pour qu'un ADR qui annonce un contrôle soit réellement contrôlé.
Documentation durable : docs/developer/ci-and-supply-chain.md#openspec-adr-rule-enforcement

## Requirements

### Requirement: Les règles des ADR s'exécutent en CI
Toute PR vers `main` SHALL exécuter `archgate check` sur le dépôt et SHALL échouer si une règle signale une violation.

#### Scenario: Violation d'une règle
- **GIVEN** une PR qui viole une règle d'un fichier `docs/architecture/adr/*.rules.ts`
- **WHEN** le workflow de CI s'exécute
- **THEN** le job Archgate échoue en nommant la règle et le fichier

#### Scenario: Aucune violation
- **WHEN** aucune règle ne signale de violation
- **THEN** le job Archgate réussit

### Requirement: La version d'Archgate exécutée en CI est épinglée et vérifiée
La CI SHALL installer une version d'Archgate fixée dans le dépôt et SHALL vérifier l'empreinte de l'outil téléchargé contre une ancre versionnée avant de l'exécuter.

#### Scenario: Empreinte divergente
- **WHEN** l'outil téléchargé ne correspond pas à l'ancre
- **THEN** le job échoue avant d'exécuter `archgate check`
