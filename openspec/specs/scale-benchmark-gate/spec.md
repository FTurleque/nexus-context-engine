# scale-benchmark-gate Specification

## Purpose
Définit quand le benchmark d'échelle mesure, et comment il reste requis sans bloquer les PR qui ne touchent pas ses chemins.
Documentation durable : docs/developer/scale-regression-benchmark.md#openspec-scale-benchmark-gate

## Requirements

### Requirement: Le check est produit sur toute PR
Le workflow de benchmark d'échelle SHALL produire le check « Hermetic scale benchmark » sur toute PR vers `main`.

#### Scenario: PR hors des chemins surveillés
- **WHEN** une PR ne modifie que de la documentation hors des chemins surveillés
- **THEN** le check conclut en succès sans mesurer et indique qu'aucun chemin surveillé n'est touché

#### Scenario: PR sur un chemin surveillé
- **WHEN** une PR modifie un fichier sous un chemin surveillé
- **THEN** le benchmark mesure et compare aux budgets

### Requirement: Une seule liste de chemins surveillés
La détection de changements, le run post-merge sur `main` et le contrat anti-dérive SHALL lire la même liste de chemins surveillés.

#### Scenario: Dérive de la liste
- **WHEN** un chemin est ajouté à la détection sans l'être au run post-merge
- **THEN** `bash .claude/scripts/contracts.sh` échoue

### Requirement: Le check est requis sur main
La protection de `main` SHALL exiger « Hermetic scale benchmark » parmi ses checks obligatoires.

#### Scenario: Lecture du ruleset
- **WHEN** le mainteneur lit la règle `required_status_checks` du ruleset de `main`
- **THEN** « Hermetic scale benchmark » y figure

### Requirement: Le peuplement 1M est borné par un plafond absolu
`scripts/verify-scale-budgets.py` SHALL refuser un palier 1M dont la population dépasse un plafond absolu fixe, indépendant du palier 500k du même rapport, en plus du plafond `max(40 s, 2,6 × population 500k)`. Ce plafond SHALL valoir au moins 40 755 ms et moins de 52 000 ms, et SHALL s'appliquer au rapport général comme à la courbe dédiée, avec ou sans base comparable.

#### Scenario: Ralentissement uniforme hors PR
- **GIVEN** un rapport `full` sans base comparable dont la population vaut 20 000 ms à 500k et 52 000 ms à 1M
- **WHEN** `python scripts/verify-scale-budgets.py` vérifie le rapport
- **THEN** la vérification échoue par une `AssertionError` `population-safety` qui nomme le palier 1M, la population mesurée et le plafond absolu
- **AND** aucun rapport n'est modifié

#### Scenario: Run de release v0.2.0
- **GIVEN** un rapport général dont la population vaut 16 810 ms à 500k et 40 755 ms à 1M
- **WHEN** le gate vérifie le rapport
- **THEN** la vérification réussit

#### Scenario: Croissance plus que proportionnelle
- **GIVEN** un rapport dont la population vaut 16 810 ms à 500k et 43 707 ms à 1M
- **WHEN** le gate vérifie le rapport
- **THEN** la vérification échoue par `population-safety` avec le plafond proportionnel de 43 706 ms

### Requirement: La page du benchmark énonce les plafonds de population appliqués
`docs/developer/scale-regression-benchmark.md` SHALL énoncer le plafond absolu de 1M avec la valeur appliquée par `scripts/verify-scale-budgets.py`, et SHALL NOT affirmer que les plafonds de population sont inchangés.

#### Scenario: Valeur divergente
- **GIVEN** une copie de travail où la valeur du plafond absolu de 1M diffère entre la page et le script
- **WHEN** `bash .claude/scripts/contracts.sh` s'exécute
- **THEN** le contrat échoue en nommant la page et les deux valeurs

#### Scenario: Affirmation périmée
- **GIVEN** une page qui contient « plafonds absolus de latence et de population restent inchangés »
- **WHEN** `bash .claude/scripts/contracts.sh` s'exécute
- **THEN** le contrat échoue
