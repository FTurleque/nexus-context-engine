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

Depuis Windows PowerShell :

```powershell
$image = & .\scripts\release\build-docker-image.ps1
```

Image par défaut :

```text
nexus-context-engine:0.2.0
```

Pour choisir le tag :

```powershell
& .\scripts\release\build-docker-image.ps1 -Image "nexus-context-engine:test"
```

## Smoke local

```powershell
& .\scripts\release\test-docker-runtime.ps1 `
  -Image "nexus-context-engine:0.2.0" `
  -HostPort 18080
```

Le test vérifie :

1. CLI/version JSON ;
2. processus MCP STDIO gardé vivant avec stdin ouvert ;
3. REST `/q/health/live` sur un port hôte personnalisé.

## Persistance

Dans le conteneur :

```text
NEXUS_HOME=/data/nexus
```

Le volume `/data/nexus` doit être persistant. Le supprimer revient à supprimer explicitement les données NEXUS.

## Repositories

Le Compose de référence monte la racine choisie en lecture seule :

```text
<Windows path>:/workspace:ro
```

NEXUS ne peut indexer que les chemins visibles dans le conteneur.

Exemple Windows :

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

Exemple :

```text
127.0.0.1:9080:8080
```

Le port hôte est le paramètre à changer en priorité.

## REST et token

À l'intérieur de Docker, Quarkus doit écouter sur `0.0.0.0` pour être publié par Docker. La politique de sécurité NEXUS exige donc un `NEXUS_REST_API_TOKEN`.

L'assistant Windows génère un token local si Docker est choisi et que l'utilisateur n'en fournit pas.

Ne publiez pas REST sur `0.0.0.0` côté hôte ou sur une adresse LAN sans comprendre l'exposition réseau et sans protéger le token.

## MCP Docker

MCP reste STDIO. Il n'existe pas de port MCP Docker.

Commande de connexion :

```text
docker exec -i nexus java -jar /opt/nexus/lib/nexus-mcp.jar
```

Cette commande peut être utilisée comme `command`/`args` par les clients MCP.

## Compose

Template :

```text
packaging/docker/docker-compose.yml.template
```

L'assistant Windows en copie une instance dans :

```text
<install>\docker\docker-compose.yml
```

et génère :

```text
<install>\docker\.env
```

Contrôle :

```powershell
.\nexus-docker-up.cmd
.\nexus-docker-down.cmd
```

CLI dans le conteneur déjà démarré :

```powershell
.\nexus-docker.cmd --version --json
```

MCP STDIO :

```powershell
.\nexus-docker-mcp.cmd
```

## Sémantique / Ollama

La recherche sémantique reste désactivée par défaut.

Variables :

```text
NEXUS_SEMANTIC_PROVIDER
NEXUS_OLLAMA_BASE_URL
NEXUS_OLLAMA_EMBEDDING_MODEL
NEXUS_OLLAMA_EMBEDDING_DIMENSIONS
NEXUS_OLLAMA_TIMEOUT_SECONDS
```

Une URL `127.0.0.1` dans un conteneur désigne **le conteneur**, pas l'hôte Windows. Adaptez `NEXUS_OLLAMA_BASE_URL` à votre topologie Docker lorsque Ollama tourne hors du conteneur.

## Publication GHCR

Le workflow :

```text
.github/workflows/docker-distribution.yml
```

construit et smoke l'image sur les PR concernées. Lors d'un push qualifié sur `main`, il publie :

```text
ghcr.io/fturleque/nexus-context-engine:<version>
ghcr.io/fturleque/nexus-context-engine:latest
```

L'installateur Windows utilise l'image versionnée comme valeur par défaut afin d'éviter qu'un upgrade silencieux de `latest` modifie un déploiement existant.
