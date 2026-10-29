# Spec Delta

## Purpose

Définit quand le benchmark d'échelle mesure, et comment il reste requis sans bloquer les PR qui ne touchent pas ses chemins.

## ADDED Requirements

### Requirement: Le check est produit sur toute PR
Le workflow de benchmark d'échelle SHALL produire le check « Hermetic scale benchmark » sur toute PR vers `develop`.

#### Scenario: PR hors des chemins surveillés
- **WHEN** une PR ne modifie que de la documentation hors des chemins surveillés
- **THEN** le check conclut en succès sans mesurer et indique qu'aucun chemin surveillé n'est touché

#### Scenario: PR sur un chemin surveillé
- **WHEN** une PR modifie un fichier sous un chemin surveillé
- **THEN** le benchmark mesure et compare aux budgets

### Requirement: Une seule liste de chemins surveillés
La détection de changements, le run post-merge sur `develop` et le contrat anti-dérive SHALL lire la même liste de chemins surveillés.

#### Scenario: Dérive de la liste
- **WHEN** un chemin est ajouté à la détection sans l'être au run post-merge
- **THEN** `bash .claude/scripts/contracts.sh` échoue

### Requirement: Le check est requis sur develop
La protection de `develop` SHALL exiger « Hermetic scale benchmark » parmi ses checks obligatoires.

#### Scenario: Lecture du ruleset
- **WHEN** le mainteneur lit la règle `required_status_checks` du ruleset de `develop`
- **THEN** « Hermetic scale benchmark » y figure
