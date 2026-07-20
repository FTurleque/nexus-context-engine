# Intégrations Copilot et Claude

Ce module génère des configurations pour connecter le runner MCP NEXUS aux clients pris en charge. Il ne modifie aucun fichier de configuration automatiquement.

## Profils

### Copilot CLI

Commande :

```text
java -jar nexus-assistant-clients-0.1.0-SNAPSHOT-runner.jar copilot-cli <runner-mcp> command
```

JSON pour une configuration MCP Copilot :

```text
java -jar nexus-assistant-clients-0.1.0-SNAPSHOT-runner.jar copilot-cli <runner-mcp> json
```

Copilot CLI accepte les serveurs MCP STDIO et les configurations `mcpServers`.

### Copilot JetBrains

```text
java -jar nexus-assistant-clients-0.1.0-SNAPSHOT-runner.jar copilot-jetbrains <runner-mcp> json
```

Le résultat utilise le schéma `servers` du fichier `mcp.json` consommé par l'intégration MCP du plugin Copilot dans les IDE JetBrains.

### Claude Code — projet

Commande :

```text
java -jar nexus-assistant-clients-0.1.0-SNAPSHOT-runner.jar claude-project <runner-mcp> command
```

Configuration `.mcp.json` :

```text
java -jar nexus-assistant-clients-0.1.0-SNAPSHOT-runner.jar claude-project <runner-mcp> json
```

Le scope `project` de Claude Code est partageable via `.mcp.json`.

### Claude Code — utilisateur

```text
java -jar nexus-assistant-clients-0.1.0-SNAPSHOT-runner.jar claude-user <runner-mcp> command
```

Le scope `user` rend le serveur disponible dans plusieurs projets pour l'utilisateur courant.

## Frontière avec les instructions natives

Les tools MCP NEXUS complètent les mécanismes d'instructions existants mais ne les remplacent pas :

- Copilot : `.github/copilot-instructions.md` et `.github/instructions/**/*.instructions.md` ;
- Claude : `CLAUDE.md` et `.claude/CLAUDE.md`.

NEXUS peut déjà indexer ces instructions comme sources de contexte. Le module présent configure uniquement l'accès aux tools MCP.

## Sécurité et effets de bord

Le générateur :

- ne lance aucun client externe ;
- ne modifie aucun fichier de préférences ;
- ne demande aucune information d'authentification ;
- ne génère aucune valeur sensible ;
- écrit seulement la configuration demandée sur la sortie standard.

## Build

```text
mvn -f adapters/assistant-clients/pom.xml clean verify
```

Runner :

```text
adapters/assistant-clients/target/nexus-assistant-clients-0.1.0-SNAPSHOT-runner.jar
```
