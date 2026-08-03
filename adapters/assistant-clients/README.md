# Intégrations Copilot et Claude

Ce module génère des configurations pour connecter le runner MCP NEXUS aux clients pris en charge. Il ne modifie aucun fichier de configuration automatiquement.

Version Phase 6 : `0.2.0`.

## Profils

### Copilot CLI

Commande :

```text
java -jar nexus-assistant-clients-0.2.0-runner.jar copilot-cli <runner-mcp> command
```

JSON pour une configuration MCP Copilot :

```text
java -jar nexus-assistant-clients-0.2.0-runner.jar copilot-cli <runner-mcp> json
```

### Copilot JetBrains

```text
java -jar nexus-assistant-clients-0.2.0-runner.jar copilot-jetbrains <runner-mcp> json
```

Le résultat utilise le schéma `servers` du fichier `mcp.json` consommé par l'intégration MCP du plugin Copilot dans les IDE JetBrains.

### Claude Code — projet

Commande :

```text
java -jar nexus-assistant-clients-0.2.0-runner.jar claude-project <runner-mcp> command
```

Configuration `.mcp.json` :

```text
java -jar nexus-assistant-clients-0.2.0-runner.jar claude-project <runner-mcp> json
```

### Claude Code — utilisateur

```text
java -jar nexus-assistant-clients-0.2.0-runner.jar claude-user <runner-mcp> command
```

Le scope `user` rend le serveur disponible dans plusieurs projets pour l'utilisateur courant.

## Frontière avec les instructions natives

Les tools MCP NEXUS complètent les mécanismes d'instructions existants mais ne les remplacent pas :

- Copilot : `.github/copilot-instructions.md` et `.github/instructions/**/*.instructions.md` ;
- Claude : `CLAUDE.md` et `.claude/CLAUDE.md`.

NEXUS peut indexer ces instructions comme sources de contexte. Le module présent configure uniquement l'accès aux tools MCP.

## Sécurité et effets de bord

Le générateur :

- ne lance aucun client externe ;
- ne modifie aucun fichier de préférences ;
- ne demande aucune information d'authentification ;
- ne génère aucune valeur sensible ;
- écrit seulement la configuration demandée sur stdout.

## Build Phase 6

Le module fait partie du reactor :

```powershell
.\mvnw.cmd clean install
```

Runner :

```text
adapters/assistant-clients/target/nexus-assistant-clients-0.2.0-runner.jar
```

Le gate global est `scripts/validate-phase-6.ps1`; aucun build séparé n'est requis pour qualifier ce module indépendamment du core/MCP/REST.
