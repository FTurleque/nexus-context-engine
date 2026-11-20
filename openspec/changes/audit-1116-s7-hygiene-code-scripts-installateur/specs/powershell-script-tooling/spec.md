# Spec Delta

## MODIFIED Requirements

### Requirement: Un module partagé porte les fonctions communes des scripts
Les fonctions `Invoke-Maven`, `Resolve-AbsolutePath`, `Invoke-NexusJson`, `Measure-Quality`, `Get-JavaVersionText` et `Get-JavaMajorVersion` SHALL être définies uniquement dans `scripts/lib/NexusScript.psm1`, et les scripts actifs de `scripts/` SHALL l'importer au lieu de les redéfinir.

#### Scenario: Aucune redéfinition locale
- **WHEN** on recherche une définition `function Invoke-Maven`, `function Resolve-AbsolutePath`, `function Invoke-NexusJson`, `function Measure-Quality`, `function Get-JavaVersionText` ou `function Get-JavaMajorVersion` dans `scripts/` hors `scripts/lib/` et `scripts/archive/`
- **THEN** aucune définition n'est trouvée

#### Scenario: Le contrat refuse une redéfinition
- **GIVEN** un script actif de `scripts/`
- **WHEN** il définit sa propre fonction `Invoke-Maven`
- **THEN** `bash .claude/scripts/contracts.sh` échoue en nommant le script
- **AND** la commande se termine avec un code non nul

#### Scenario: Le contrat refuse une fonction de mesure recopiée
- **GIVEN** un script actif de `scripts/`
- **WHEN** il définit sa propre fonction `Measure-Quality` ou `Get-JavaMajorVersion`
- **THEN** `bash .claude/scripts/contracts.sh` échoue en nommant le script et la fonction
- **AND** la commande se termine avec un code non nul

## ADDED Requirements

### Requirement: Les scripts PowerShell actifs activent le mode strict
Chaque script `.ps1` de `scripts/` hors `scripts/archive/` SHALL appeler `Set-StrictMode -Version Latest` avant sa première instruction exécutable autre que `param` et `#requires`, en plus de `$ErrorActionPreference = "Stop"`.

#### Scenario: Script actif sans mode strict
- **GIVEN** un script actif de `scripts/` qui n'appelle pas `Set-StrictMode -Version Latest`
- **WHEN** on lance `bash .claude/scripts/contracts.sh`
- **THEN** le contrat échoue en nommant le script
- **AND** la commande se termine avec un code non nul

#### Scenario: Propriété mal orthographiée
- **GIVEN** un script actif qui lit une propriété absente d'un objet JSON renvoyé par NEXUS
- **WHEN** le script s'exécute
- **THEN** il échoue sur cette lecture au lieu d'obtenir `$null`

#### Scenario: Scripts archivés
- **WHEN** un script de `scripts/archive/` n'appelle pas `Set-StrictMode`
- **THEN** le contrat ne le signale pas
