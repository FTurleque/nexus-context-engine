# Spec Delta

## ADDED Requirements

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
