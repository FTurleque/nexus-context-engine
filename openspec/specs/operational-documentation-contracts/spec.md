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

### Requirement: La documentation courante ne cite pas la version de développement 0.1.0-SNAPSHOT
Les documents courants contrôlés par `scripts/release/test-operational-doc-contracts.sh`, dont `docs/developer/reproduce-and-debug.md`, SHALL NOT contenir `0.1.0-SNAPSHOT`, et le guide de reproduction SHALL nommer les artefacts du build sous la forme `nexus-context-engine-<version>`.

#### Scenario: Ancienne version dans le guide de reproduction
- **GIVEN** `docs/developer/reproduce-and-debug.md` qui contient `target/nexus-context-engine-0.1.0-SNAPSHOT.jar`
- **WHEN** `bash .claude/scripts/contracts.sh` s'exécute
- **THEN** le contrat documentaire échoue en nommant le document et la chaîne
- **AND** la commande se termine avec un code non nul

#### Scenario: Artefact nommé sans version figée
- **GIVEN** `docs/developer/reproduce-and-debug.md` qui nomme `target/nexus-context-engine-<version>-cli.jar`
- **WHEN** le contrat documentaire s'exécute
- **THEN** il ne signale pas ce document

#### Scenario: Relevé daté hors contrôle
- **GIVEN** `docs/project/history/cli-mvp.md`, relevé daté qui cite `0.1.0-SNAPSHOT`
- **WHEN** le contrat documentaire s'exécute
- **THEN** il ne signale pas ce relevé

### Requirement: Les guides courants n'appellent Maven que par le wrapper
Les documents courants (`docs/**/*.md` hors ADR, `docs/project/audits/`, `docs/project/history/` et `docs/project/rapport-supervisor/`, plus `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `SECURITY.md`) SHALL NOT contenir `mvn` ou `mvn.cmd` en position de commande dans un bloc de code ou un code en ligne, hors diagramme Mermaid et exception listée ; le contrat SHALL nommer le document et la ligne.

#### Scenario: Commande Maven directe dans le guide de reproduction
- **GIVEN** `docs/developer/reproduce-and-debug.md` dont un bloc de code contient la ligne `mvn clean install`
- **WHEN** `bash .claude/scripts/contracts.sh` s'exécute
- **THEN** le contrat documentaire échoue en nommant `docs/developer/reproduce-and-debug.md` et la ligne
- **AND** la commande se termine avec un code non nul

#### Scenario: Commande Maven directe en code en ligne
- **GIVEN** `docs/architecture/arc42/02-contraintes.md` dont une ligne contient le code en ligne `mvn clean install`
- **WHEN** le contrat documentaire s'exécute
- **THEN** il échoue en nommant le document et la ligne

#### Scenario: Forme de contournement
- **GIVEN** un bloc de code qui contient `mvn.cmd -q package`, `cd core && mvn test` ou `PS> mvn -v`
- **WHEN** l'auto-test du contrat s'exécute
- **THEN** chaque forme est détectée
- **AND** une forme non détectée fait échouer le contrat avec un message d'auto-test qui la cite

#### Scenario: Wrapper vérifié
- **GIVEN** un guide dont les blocs de code contiennent `.\mvnw.cmd clean install` et `./mvnw -version`
- **WHEN** le contrat documentaire s'exécute
- **THEN** il ne signale pas ce guide

#### Scenario: Mention qui désigne Maven sans le prescrire
- **GIVEN** `docs/developer/claude-code.md` qui écrit « `mvn` hors wrapper » et un diagramme Mermaid de `docs/developer/ci-and-supply-chain.md` qui écrit `mvn installé`
- **WHEN** le contrat documentaire s'exécute
- **THEN** il ne signale aucune de ces lignes

#### Scenario: ADR hors contrôle
- **GIVEN** `docs/architecture/adr/GEN-0031-packager-la-cli-dans-un-jar-autonome.md`, append-only, qui cite `mvn clean install`
- **WHEN** le contrat documentaire s'exécute
- **THEN** il ne signale pas cet ADR

#### Scenario: Exception devenue obsolète
- **GIVEN** une exception listée par le contrat (document et texte exact de ligne) dont la ligne n'existe plus dans le document
- **WHEN** le contrat documentaire s'exécute
- **THEN** il échoue en nommant l'exception obsolète

### Requirement: Les commandes exec:java de la documentation visent le module core
Dans les documents courants contrôlés par l'exigence « Les guides courants n'appellent Maven que par le wrapper », toute commande Maven qui lance le but `exec:java` SHALL sélectionner le module `core` par `-pl core`, et le contrat SHALL nommer le document et la ligne d'une commande qui ne le fait pas.

#### Scenario: exec:java lancé à la racine du reactor
- **GIVEN** un guide dont un bloc de code contient `.\mvnw.cmd -q exec:java "-Dexec.args=--help"`
- **WHEN** le contrat documentaire s'exécute
- **THEN** il échoue en nommant le document et la ligne

#### Scenario: exec:java sur le module core
- **GIVEN** un guide dont un bloc de code contient `.\mvnw.cmd -q -pl core exec:java "-Dexec.args=--help"`
- **WHEN** le contrat documentaire s'exécute
- **THEN** il ne signale pas cette ligne
