# Spec Delta

## Purpose

Définit ce que la documentation courante et l'instruction primaire des agents doivent refléter du code, et que `scripts/release/test-operational-doc-contracts.sh` vérifie à chaque build.

## ADDED Requirements

### Requirement: Le voisinage graphe est documenté comme une projection bornée
`docs/developer/search-ranking.md` SHALL décrire le voisinage graphe comme une projection à la demande depuis SQLite, bornée par `MAX_NEIGHBOR_EDGES`, et SHALL NOT décrire de graphe mis en cache par génération.

#### Scenario: Ancienne description réintroduite
- **WHEN** `docs/developer/search-ranking.md` contient « ProjectGraph cache »
- **THEN** le contrat documentaire échoue

#### Scenario: Aucun type de graphe orphelin
- **WHEN** le code de production et de test est parcouru
- **THEN** aucun type `ProjectGraph` n'y est déclaré

### Requirement: Les limites courantes ne présentent pas comme futur un moteur livré
`docs/developer/current-limitations.md` SHALL NOT présenter le moteur FTS5 trigram des recherches substring comme une évolution à venir.

#### Scenario: Watch item obsolète
- **WHEN** `current-limitations.md` contient « nouveau moteur FTS/trigram »
- **THEN** le contrat documentaire échoue

### Requirement: L'instruction primaire des agents n'utilise que le wrapper Maven
`AGENTS.md` SHALL prescrire le build par le wrapper vérifié (`./mvnw` ou `.\mvnw.cmd`) et SHALL NOT contenir de commande `mvn` hors wrapper.

#### Scenario: Commande Maven directe
- **WHEN** `AGENTS.md` contient `mvn clean install`
- **THEN** le contrat documentaire échoue

#### Scenario: Contexte construit par NEXUS sur son propre dépôt
- **WHEN** l'étape 9 de `scripts/self-smoke.ps1` construit le contexte de validation
- **THEN** `AGENTS.md` est retenu et prescrit le wrapper
