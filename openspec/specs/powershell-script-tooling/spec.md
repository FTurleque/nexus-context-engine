# powershell-script-tooling Specification

## Purpose
Définit comment les scripts PowerShell du dépôt appellent Maven et partagent leurs fonctions utilitaires, et quels scripts sont des points d'entrée de validation.
Documentation durable : docs/developer/ci-and-supply-chain.md#openspec-powershell-script-tooling

## Requirements

### Requirement: Un module partagé porte les fonctions communes des scripts
Les fonctions `Invoke-Maven`, `Resolve-AbsolutePath` et `Invoke-NexusJson` SHALL être définies uniquement dans `scripts/lib/NexusScript.psm1`, et les scripts actifs de `scripts/` SHALL l'importer au lieu de les redéfinir.

#### Scenario: Aucune redéfinition locale
- **WHEN** on recherche une définition `function Invoke-Maven`, `function Resolve-AbsolutePath` ou `function Invoke-NexusJson` dans `scripts/` hors `scripts/lib/` et `scripts/archive/`
- **THEN** aucune définition n'est trouvée

#### Scenario: Le contrat refuse une redéfinition
- **GIVEN** un script actif de `scripts/`
- **WHEN** il définit sa propre fonction `Invoke-Maven`
- **THEN** `bash .claude/scripts/contracts.sh` échoue en nommant le script
- **AND** la commande se termine avec un code non nul

### Requirement: Maven est appelé par le wrapper vérifié
Les scripts actifs SHALL appeler Maven uniquement via `Invoke-Maven`, qui utilise `mvnw.cmd` ou `mvnw`. En CI (`CI` ou `GITHUB_ACTIONS` définie), un échec du wrapper SHALL faire échouer le script sans repli sur un Maven installé.

#### Scenario: Wrapper indisponible en CI
- **GIVEN** `GITHUB_ACTIONS` est définie
- **WHEN** le wrapper renvoie un code non nul
- **THEN** `Invoke-Maven` lève une exception qui mentionne l'exigence du wrapper vérifié et le script échoue
- **AND** aucun Maven installé n'est appelé en repli

#### Scenario: Code retour Maven propagé
- **WHEN** la commande Maven lancée par `Invoke-Maven` se termine avec un code non nul
- **THEN** le script appelant échoue avec ce code

#### Scenario: Appel direct refusé par le contrat
- **GIVEN** un script actif de `scripts/`
- **WHEN** il invoque directement l'exécutable Maven installé
- **THEN** `bash .claude/scripts/contracts.sh` échoue en nommant le script
- **AND** la commande se termine avec un code non nul

### Requirement: Les scripts obsolètes ne sont pas des points d'entrée
Les scripts d'itération non référencés par la documentation courante SHALL se trouver sous `scripts/archive/`, et la documentation courante SHALL désigner `scripts/validate-phase-6.ps1` et `scripts/self-smoke.ps1` comme points d'entrée de validation.

#### Scenario: Script d'itération obsolète
- **WHEN** on liste les scripts `validate-iteration-11.ps1` à `validate-iteration-14.ps1`
- **THEN** ils se trouvent sous `scripts/archive/` et non à la racine de `scripts/`

#### Scenario: Aucune référence vers un artefact inexistant
- **WHEN** on recherche `0.1.0-SNAPSHOT` dans les scripts de `scripts/` hors `scripts/archive/`
- **THEN** aucune occurrence n'est trouvée

#### Scenario: Points d'entrée documentés
- **WHEN** on lit la documentation courante des scripts de validation
- **THEN** elle désigne `scripts/validate-phase-6.ps1` et `scripts/self-smoke.ps1` comme points d'entrée de validation

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
