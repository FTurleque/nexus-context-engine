# Distribution Docker NEXUS

NEXUS 0.2.0 peut être exécuté nativement ou dans Docker. Docker est **optionnel** et n'est jamais requis pour le mode Windows natif.

## Image

L'image contient les mêmes surfaces applicatives que la distribution Windows :

```text
/opt/nexus/lib/nexus-cli.jar
/opt/nexus/lib/nexus-mcp.jar
/opt/nexus/lib/nexus-assistant-clients.jar
/opt/nexus/rest/quarkus-run.jar
```

Le processus s'exécute avec un utilisateur non-root UID `10001`.

## Modes de l'entrypoint

```text
rest       API REST Quarkus, mode par défaut
cli        CLI NEXUS
mcp        serveur MCP JSON-RPC / STDIO
assistant  générateur de configurations assistants
shell      shell de diagnostic
```

Exemples :

```powershell
docker run --rm nexus-context-engine:0.2.0 cli --version --json
docker run --rm -i nexus-context-engine:0.2.0 mcp
docker run --rm nexus-context-engine:0.2.0 assistant generic docker nexus json
```

## Construire localement

```powershell
$image = & .\scripts\release\build-docker-image.ps1
```

Image par défaut :

```text
nexus-context-engine:0.2.0
```

Smoke :

```powershell
& .\scripts\release\test-docker-runtime.ps1 `
  -Image "nexus-context-engine:0.2.0" `
  -HostPort 18080
```

Le test vérifie CLI, MCP STDIO et REST sur un port hôte personnalisé.

## Persistance

Dans le conteneur :

```text
NEXUS_HOME=/data/nexus
```

Le volume `/data/nexus` doit être persistant.

## Repositories

Le Compose monte la racine choisie en lecture seule :

```text
<Windows path>:/workspace:ro
```

Exemple :

```text
N:/workspace-dev:/workspace:ro
```

Docker Desktop doit avoir accès au lecteur concerné.

## REST et ports

Variables :

```text
NEXUS_DOCKER_BIND_ADDRESS
NEXUS_DOCKER_HOST_PORT
NEXUS_DOCKER_CONTAINER_PORT
```

Défauts :

```text
127.0.0.1
8080
8080
```

Mapping :

```text
<bind-address>:<host-port>:<container-port>
```

Le port hôte est celui à changer en priorité.

## REST et token

À l'intérieur de Docker, Quarkus écoute sur `0.0.0.0` pour pouvoir être publié. La politique de sécurité NEXUS exige donc un `NEXUS_REST_API_TOKEN`.

L'assistant Windows génère un token local si Docker est choisi et que l'utilisateur n'en fournit pas.

## MCP Docker

MCP reste STDIO. Il n'existe pas de port MCP Docker.

```text
docker exec -i nexus java -jar /opt/nexus/lib/nexus-mcp.jar
```

Cette commande est réutilisée par toutes les intégrations assistants quand le runtime MCP Docker est sélectionné.

## Intégrations assistants avec Docker

La matrice est identique au mode natif :

- GitHub Copilot CLI ;
- GitHub Copilot JetBrains ;
- Claude CLI / Claude Code ;
- Codex Desktop ;
- client MCP générique.

Exemples générés :

```text
copilot mcp add nexus --tools "*" -- docker exec -i nexus java -jar /opt/nexus/lib/nexus-mcp.jar
claude mcp add --scope user nexus -- docker exec -i nexus java -jar /opt/nexus/lib/nexus-mcp.jar
codex mcp add nexus -- docker exec -i nexus java -jar /opt/nexus/lib/nexus-mcp.jar
```

Assets :

```text
integrations\copilot-jetbrains.mcp.json
integrations\codex-desktop.mcp.toml
integrations\generic-mcp.json
```

Une entrée `nexus` existante est préservée. NEXUS ne gère pas l'authentification Copilot/Claude/Codex.

## Compose

Template :

```text
packaging/docker/docker-compose.yml.template
```

L'assistant Windows génère :

```text
<install>\docker\.env
<install>\docker\docker-compose.yml
<install>\nexus-docker-up.cmd
<install>\nexus-docker-down.cmd
```

## Sémantique / Ollama

La recherche sémantique reste désactivée par défaut.

Dans le wizard Windows, le provider n'est pas un champ texte :

```text
[ ] Activer Ollama pour la recherche sémantique
```

Si cochée :

```text
NEXUS_SEMANTIC_PROVIDER=ollama
```

URL par défaut :

```text
http://127.0.0.1:11434
```

Le setup NEXUS **n'installe pas Ollama** ; il configure uniquement la connexion.

Dans un conteneur, `127.0.0.1` désigne le conteneur. Adaptez `NEXUS_OLLAMA_BASE_URL` si Ollama tourne sur l'hôte Windows ou ailleurs.

## Récapitulatif Windows avant déploiement Docker

Avant le bouton Installer, le setup affiche la page Ready avec :

- image ;
- conteneur ;
- adresse/ports REST ;
- repository ;
- restart policy ;
- Ollama activé/désactivé ;
- cinq intégrations assistants ;
- runtime MCP choisi ;
- démarrage Docker post-install.

## Publication GHCR

Le workflow :

```text
.github/workflows/docker-distribution.yml
```

construit et smoke l'image sur les PR concernées. Sur `main`, il publie :

```text
ghcr.io/fturleque/nexus-context-engine:<version>
ghcr.io/fturleque/nexus-context-engine:latest
```

Le setup utilise l'image versionnée par défaut.
