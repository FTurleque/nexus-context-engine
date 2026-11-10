# operational-documentation-contracts Specification

## Purpose
Définit ce que la documentation courante et l'instruction primaire des agents doivent refléter du code, et que `scripts/release/test-operational-doc-contracts.sh` vérifie à chaque build.
Documentation durable : docs/developer/ci-and-supply-chain.md#openspec-operational-documentation-contracts

## Requirements

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

### Requirement: La documentation ne recopie pas les versions des dépendances montées par Dependabot
La documentation courante SHALL désigner les versions de Quarkus et du SDK MCP par leurs propriétés `quarkus.platform.version` et `mcp.sdk.version` du `pom.xml`, et le contrat documentaire SHALL lire ces propriétés dans le `pom.xml` sans comparer de numéro littéral.

#### Scenario: Montée de version par Dependabot
- **WHEN** une PR ne modifie que `quarkus.platform.version` ou `mcp.sdk.version` dans le `pom.xml`
- **THEN** `scripts/release/test-operational-doc-contracts.sh` reste vert

#### Scenario: Version recopiée dans la documentation
- **WHEN** un document courant contrôlé contient un littéral de la forme `Quarkus 3.x.y` ou `MCP SDK 2.x.y`
- **THEN** le contrat échoue en nommant le document

#### Scenario: Propriété absente du pom
- **WHEN** le `pom.xml` ne déclare plus l'une des deux propriétés
- **THEN** le contrat échoue

### Requirement: La documentation de publication GHCR nomme le workflow de release
La documentation utilisateur Docker SHALL attribuer la publication des tags GHCR `<version>` et `latest` au workflow de release déclenché par tag, et SHALL NOT l'attribuer à `docker-distribution.yml`.

#### Scenario: Contrat documentaire
- **WHEN** `docs/user/docker-installation.md` attribue la publication GHCR à `docker-distribution.yml`
- **THEN** `bash .claude/scripts/contracts.sh` échoue en nommant le fichier
- **AND** la commande se termine avec un code non nul
