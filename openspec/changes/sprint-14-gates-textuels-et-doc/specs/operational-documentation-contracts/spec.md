# Spec Delta

## ADDED Requirements

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
