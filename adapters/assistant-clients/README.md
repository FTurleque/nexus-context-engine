# Intégrations assistants MCP

Ce module génère des commandes et configurations pour connecter le runner MCP NEXUS aux clients pris en charge. Il ne modifie aucun fichier de configuration automatiquement.

Version : `0.2.0`.

## Runtimes MCP

Le générateur sait désormais viser trois formes d'exécution :

1. **legacy Java système** — compatibilité avec la syntaxe Phase 6 historique ;
2. **native Windows** — chemin explicite vers le `java.exe` embarqué par NEXUS et vers `nexus-mcp.jar` ;
3. **Docker** — `docker exec -i <container> java -jar /opt/nexus/lib/nexus-mcp.jar`.

MCP reste en transport **STDIO** dans les modes natif et Docker. Aucun port MCP HTTP n'est créé.

## Syntaxe

Compatibilité historique :

```text
java -jar nexus-assistant-clients-0.2.0-runner.jar <profil> <runner-mcp> [command|json]
```

Runtime natif autonome :

```text
java -jar nexus-assistant-clients-0.2.0-runner.jar <profil> native <java-exe> <runner-mcp> [command|json]
```

Runtime Docker :

```text
java -jar nexus-assistant-clients-0.2.0-runner.jar <profil> docker <container-name> [command|json]
```

Profils :

```text
copilot-cli       command ou json
copilot-jetbrains json (schéma servers)
claude-project    command ou json
claude-user       command
generic           json (schéma mcpServers)
```

## Exemples Windows autonome

Copilot CLI avec le Java embarqué :

```powershell
.\nexus-assistant-clients.cmd copilot-cli native `
  .\app\runtime\bin\java.exe `
  .\lib\nexus-mcp.jar command
```

Copilot JetBrains :

```powershell
.\nexus-assistant-clients.cmd copilot-jetbrains native `
  .\app\runtime\bin\java.exe `
  .\lib\nexus-mcp.jar json
```

Claude Code utilisateur :

```powershell
.\nexus-assistant-clients.cmd claude-user native `
  .\app\runtime\bin\java.exe `
  .\lib\nexus-mcp.jar command
```

## Exemples Docker

Copilot CLI :

```powershell
.\nexus-assistant-clients.cmd copilot-cli docker nexus command
```

Copilot JetBrains :

```powershell
.\nexus-assistant-clients.cmd copilot-jetbrains docker nexus json
```

Client MCP générique :

```powershell
.\nexus-assistant-clients.cmd generic docker nexus json
```

## Frontière avec l'installateur Windows

L'assistant d'installation Windows utilise la même architecture mais ajoute des effets de bord **explicitement choisis par l'utilisateur** :

- Copilot CLI et Claude Code peuvent être connectés si leur CLI est détectée ;
- une entrée `nexus` déjà présente est conservée et n'est pas écrasée ;
- Copilot JetBrains reçoit un `mcp.json` prêt à importer, car l'emplacement du fichier est géré par l'IDE/plugin et ne doit pas être deviné ;
- un JSON générique est toujours disponible si demandé ;
- aucune authentification, clé API ou session Copilot/Claude n'est gérée par NEXUS.

Les fichiers générés par l'installateur se trouvent sous `integrations/` dans le répertoire NEXUS.

## Frontière avec les instructions natives

Les tools MCP NEXUS complètent les mécanismes d'instructions existants mais ne les remplacent pas :

- Copilot : `.github/copilot-instructions.md` et `.github/instructions/**/*.instructions.md` ;
- Claude : `CLAUDE.md` et `.claude/CLAUDE.md`.

NEXUS peut indexer ces instructions comme sources de contexte. Le présent module configure uniquement l'accès aux tools MCP.

## Sécurité

Le générateur standalone :

- ne lance aucun client externe ;
- ne modifie aucun fichier de préférences ;
- ne demande aucune information d'authentification ;
- ne génère aucune valeur sensible ;
- écrit seulement la configuration demandée sur stdout.

Le setup Windows possède une politique séparée et traçable pour les intégrations explicitement sélectionnées. Voir `docs/user/windows-installation.md`.

## Build

```powershell
.\mvnw.cmd clean install
```

Runner :

```text
adapters/assistant-clients/target/nexus-assistant-clients-0.2.0-runner.jar
```
