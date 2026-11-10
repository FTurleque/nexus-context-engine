# installer-client-configuration Specification

## Purpose
Garantit que l'installateur Windows connecte et déconnecte NEXUS des clients IA sans altérer les autres entrées de leurs fichiers de configuration MCP, et que les valeurs injectées dans les scripts qu'il génère ne changent pas leur syntaxe.
Documentation durable : docs/developer/release-and-recovery.md#openspec-installer-client-configuration

## Requirements

### Requirement: Les configurations des clients sont lues et écrites en UTF-8
Quand l'installateur ajoute ou retire l'entrée `nexus` d'un fichier de configuration MCP d'un client IA, il SHALL lire le fichier en UTF-8 et l'écrire en UTF-8 sans BOM, de sorte que toute autre entrée reste identique caractère pour caractère.

#### Scenario: Chemin accentué dans une autre entrée
- **GIVEN** un `claude_desktop_config.json` UTF-8 sans BOM qui déclare un serveur `outil` de commande `C:\Users\Hélène\outil.exe`
- **WHEN** l'installateur connecte NEXUS à Claude Desktop
- **THEN** le fichier contient l'entrée `nexus` et la commande de `outil` vaut toujours `C:\Users\Hélène\outil.exe`

#### Scenario: Désinstallation
- **GIVEN** le même fichier, avec l'entrée `nexus` ajoutée par l'installateur
- **WHEN** NEXUS est désinstallé
- **THEN** l'entrée `nexus` est retirée et l'entrée `outil` est inchangée

### Requirement: Un fichier de configuration illisible n'est jamais réécrit
Quand le fichier de configuration d'un client ne se lit pas comme un objet JSON, l'installateur SHALL NOT le modifier et SHALL signaler l'échec par un code de sortie non nul du script de connexion ou de déconnexion.

#### Scenario: JSON invalide
- **GIVEN** un `claude_desktop_config.json` dont le contenu est `{"mcpServers": {`
- **WHEN** l'installateur tente de connecter NEXUS
- **THEN** le script de connexion se termine avec un code non nul
- **AND** le fichier garde exactement ses octets d'origine et aucune sauvegarde n'est requise pour le restaurer

#### Scenario: Plusieurs fichiers dont un invalide
- **GIVEN** deux fichiers de configuration Claude Desktop détectés, le premier invalide et le second valide
- **WHEN** l'installateur connecte NEXUS
- **THEN** le premier fichier est inchangé et le second ne reçoit que sa propre entrée `nexus`, sans contenu repris du premier

### Requirement: Une sauvegarde précède toute réécriture d'une configuration existante
Avant de réécrire un fichier de configuration de client existant, l'installateur SHALL en conserver une copie octet pour octet à côté du fichier, et SHALL remplacer le fichier par une écriture complète dans un fichier temporaire du même dossier suivie d'un remplacement.

#### Scenario: Connexion réussie
- **GIVEN** un fichier de configuration Copilot JetBrains `mcp.json` existant et valide
- **WHEN** l'installateur connecte NEXUS
- **THEN** une copie du fichier d'origine existe dans le même dossier avec un nom qui l'identifie comme sauvegarde NEXUS
- **AND** le fichier `mcp.json` final est un JSON valide

#### Scenario: Échec pendant l'écriture
- **WHEN** l'écriture du fichier temporaire échoue
- **THEN** le fichier de configuration d'origine est inchangé et le script se termine avec un code non nul

### Requirement: Les valeurs injectées dans les scripts PowerShell générés sont échappées
Une valeur placée entre apostrophes dans un script PowerShell généré par l'installateur SHALL voir doublés l'apostrophe ASCII et chacun des caractères U+2018, U+2019, U+201A et U+201B, de sorte que le script se lit sans erreur et rend la valeur d'origine.

#### Scenario: Dossier d'installation avec apostrophe typographique
- **GIVEN** un dossier d'installation `C:\Outils\Jean’s NEXUS`
- **WHEN** l'installateur génère le script de connexion des clients
- **THEN** le script s'analyse sans erreur de syntaxe et la commande enregistrée pour `nexus` contient `C:\Outils\Jean’s NEXUS\nexus-mcp.cmd`
