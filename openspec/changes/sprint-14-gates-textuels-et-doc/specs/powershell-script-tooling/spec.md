# Spec Delta

## ADDED Requirements

### Requirement: Le contrat Maven refuse un appel direct en position de commande
Le contrat des scripts PowerShell actifs SHALL refuser un nom ou chemin Maven (`mvn`, `mvn.cmd`, `mvnw`, `mvnw.cmd`) placé en position de commande après `=`, `;`, `{`, `(`, `|`, `&` ou un `.` de dot-sourcing, passé à `cmd /k` (comme à `cmd /c`), ou passé à `pwsh`/`powershell` par `-c` ou `-Command`, en nommant le script et la ligne.

#### Scenario: Affectation du résultat
- **GIVEN** un script actif qui contient `$output = mvn clean install 2>&1`
- **WHEN** `bash .claude/scripts/contracts.sh` s'exécute
- **THEN** le contrat échoue avec `<script>:<ligne>: calls Maven directly instead of Invoke-Maven`
- **AND** la commande se termine avec un code non nul

#### Scenario: Après un point-virgule
- **GIVEN** un script actif qui contient `Push-Location $root; mvn clean install`
- **WHEN** le contrat s'exécute
- **THEN** le contrat échoue en nommant le script et la ligne

#### Scenario: Dans un bloc
- **GIVEN** un script actif qui contient `if ($full) { mvn clean install }`
- **WHEN** le contrat s'exécute
- **THEN** le contrat échoue en nommant le script et la ligne

#### Scenario: Dot-sourcing
- **GIVEN** un script actif qui contient `. mvn clean install`
- **WHEN** le contrat s'exécute
- **THEN** le contrat échoue en nommant le script et la ligne

#### Scenario: Passage par cmd /k
- **GIVEN** un script actif qui contient `cmd /k mvn clean install`
- **WHEN** le contrat s'exécute
- **THEN** le contrat échoue en nommant le script et la ligne

#### Scenario: Passage par pwsh -Command
- **GIVEN** un script actif qui contient `pwsh -Command "mvn clean install"`
- **WHEN** le contrat s'exécute
- **THEN** le contrat échoue en nommant le script et la ligne

#### Scenario: Message qui cite le wrapper dans une phrase
- **GIVEN** un script actif dont un message contient `Lancez d'abord : .\mvnw.cmd clean package`
- **WHEN** le contrat s'exécute
- **THEN** cette ligne n'est pas signalée
