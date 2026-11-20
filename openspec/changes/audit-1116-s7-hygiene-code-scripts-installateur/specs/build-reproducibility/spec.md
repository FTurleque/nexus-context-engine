# Spec Delta

## ADDED Requirements

### Requirement: Une construction Windows échoue si la sortie précédente subsiste
`scripts/release/build-windows-distribution.ps1` et `scripts/release/build-windows-installer.ps1` SHALL vérifier, après la suppression de leur sortie précédente et avant toute copie ou compilation, qu'aucun des chemins supprimés n'existe encore. Si l'un subsiste, le script SHALL échouer avec un message qui nomme ce chemin.

#### Scenario: Fichier verrouillé dans l'ancienne distribution
- **GIVEN** un dossier de distribution précédent dont un fichier est ouvert en exclusivité par un autre processus
- **WHEN** on lance `scripts/release/build-windows-distribution.ps1`
- **THEN** le script échoue avec un code non nul et un message qui nomme le chemin non supprimé
- **AND** aucun nouveau ZIP ni `.sha256` n'est produit

#### Scenario: Ancien programme d'installation verrouillé
- **GIVEN** un `setup.exe` précédent ouvert en exclusivité par un autre processus
- **WHEN** on lance `scripts/release/build-windows-installer.ps1`
- **THEN** le script échoue avec un code non nul et un message qui nomme ce fichier
- **AND** Inno Setup n'est pas lancé

#### Scenario: Sortie précédente absente ou supprimable
- **GIVEN** un dossier de sortie vide ou dont la sortie précédente peut être supprimée
- **WHEN** on lance l'un des deux scripts
- **THEN** la suppression ne fait pas échouer le script et la construction se poursuit
