# Spec Delta

## ADDED Requirements

### Requirement: Le contrat Maven refuse les appels indirects
Le contrat des scripts PowerShell actifs SHALL refuser un appel de Maven hors `Invoke-Maven` écrit par indirection : nom de commande Maven dans une variable puis invoqué, passage par `cmd /c` ou `cmd.exe /c`, ou `Invoke-Expression`/`iex` d'une chaîne qui contient Maven.

#### Scenario: Commande dans une variable
- **GIVEN** un script actif qui contient `$m = "mvn"; & $m clean install`
- **WHEN** `bash .claude/scripts/contracts.sh` s'exécute
- **THEN** le contrat échoue en nommant le script et la ligne

#### Scenario: Passage par cmd
- **GIVEN** un script actif qui contient `cmd /c mvn clean install`
- **WHEN** le contrat s'exécute
- **THEN** le contrat échoue en nommant le script et la ligne

#### Scenario: Invoke-Expression
- **GIVEN** un script actif qui contient `Invoke-Expression "mvn clean install"`
- **WHEN** le contrat s'exécute
- **THEN** le contrat échoue en nommant le script et la ligne

#### Scenario: Message qui cite le wrapper
- **GIVEN** un script actif dont un message d'erreur cite `.\mvnw.cmd clean package`
- **WHEN** le contrat s'exécute
- **THEN** cette ligne n'est pas signalée
