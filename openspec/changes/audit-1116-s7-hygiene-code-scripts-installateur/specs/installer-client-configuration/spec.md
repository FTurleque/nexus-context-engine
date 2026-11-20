# Spec Delta

## ADDED Requirements

### Requirement: Les empreintes des programmes d'installation téléchargés viennent du fichier d'intégrité
Les empreintes SHA-256 de Docker Desktop et d'Ollama que vérifie l'installateur Windows avant de lancer ces programmes SHALL être lues dans `config/tool-integrity.properties` lors de la construction. Aucun template, script de construction ou script de test SHALL contenir ces empreintes en littéral. Une clé absente ou une valeur qui n'est pas 64 caractères hexadécimaux SHALL faire échouer la construction.

#### Scenario: Empreinte montée dans le fichier d'intégrité
- **GIVEN** une nouvelle empreinte de Docker Desktop écrite dans `config/tool-integrity.properties` avec la version et le build qualifiés correspondants
- **WHEN** on construit l'installateur
- **THEN** la source Inno Setup générée vérifie le programme téléchargé contre cette empreinte et télécharge l'URL versionnée de ce build
- **AND** aucun autre fichier versionné n'a été modifié pour cette montée, hors documentation

#### Scenario: Clé d'intégrité absente
- **GIVEN** un `config/tool-integrity.properties` sans la clé de l'empreinte Windows d'Ollama
- **WHEN** on lance `scripts/release/build-windows-installer.ps1`
- **THEN** le script échoue avec un code non nul et un message qui nomme la clé manquante
- **AND** aucun programme d'installation n'est produit

#### Scenario: Empreinte recopiée en littéral
- **GIVEN** un script de `scripts/release/` ou le template `packaging/windows/nexus-installer.iss.template` qui contient en littéral l'empreinte de Docker Desktop ou d'Ollama du fichier d'intégrité
- **WHEN** on lance `bash .claude/scripts/contracts.sh`
- **THEN** le contrat échoue en nommant le fichier
- **AND** la commande se termine avec un code non nul

#### Scenario: Programme téléchargé non conforme
- **GIVEN** un programme d'installation Docker Desktop ou Ollama téléchargé dont le SHA-256 diffère de l'empreinte du fichier d'intégrité
- **WHEN** l'installateur le vérifie
- **THEN** l'installation de ce prérequis est annulée avec un message qui nomme la version qualifiée
- **AND** le programme téléchargé n'est pas lancé

#### Scenario: Template versionné sans URL flottante
- **WHEN** on lit `packaging/windows/nexus-installer.iss.template`
- **THEN** les téléchargements de Docker Desktop et d'Ollama visent une URL qui porte la version ou le build qualifié
- **AND** l'appel de vérification SHA-256 figure dans le template
