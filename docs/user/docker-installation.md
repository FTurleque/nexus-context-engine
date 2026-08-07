# Distribution Docker NEXUS

NEXUS 0.2.0 peut être exécuté nativement ou dans Docker. Docker reste **optionnel** et n'est jamais requis pour le mode Windows natif.

Lorsque `Docker` ou `Natif + Docker` est sélectionné dans l'assistant Windows, le setup peut désormais **installer Docker Desktop automatiquement s'il est absent**.

## Docker Desktop sur Windows

Le wizard distingue le runtime NEXUS Docker du moteur Docker nécessaire pour l'exécuter.

Comportement :

```text
Docker/CLI déjà détecté
  -> aucune installation Docker Desktop

Docker/CLI absent
  -> option "Installer automatiquement Docker Desktop s'il est absent"
     cochée par défaut
  -> téléchargement officiel
  -> vérification Authenticode Docker Inc
  -> installation per-user / backend WSL 2
```

Source de téléchargement utilisée par le setup :

```text
https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe
```

Commande d'installation :

```text
Docker Desktop Installer.exe install --user --backend=wsl-2 --quiet
```

NEXUS ne passe **pas** `--accept-license`. La Docker Subscription Service Agreement reste à accepter par l'utilisateur au premier démarrage de Docker Desktop.

Le mode per-user installe normalement Docker Desktop sous :

```text
%LOCALAPPDATA%\Programs\DockerDesktop
```

et ne requiert pas de privilèges administrateur pour l'installation Docker Desktop elle-même. En revanche, la première activation ou mise à jour de WSL 2 peut nécessiter une élévation et éventuellement un redémarrage Windows.

Le setup vérifie l'Authenticode du binaire téléchargé et refuse de l'exécuter si la signature n'est pas valide ou si le signataire n'est pas `Docker Inc`.

Docker Desktop est un prérequis partagé : **la désinstallation de NEXUS ne désinstalle jamais Docker Desktop ni WSL**.

Après une installation Docker Desktop effectuée par NEXUS, Docker Desktop est lancé. Au premier démarrage, l'utilisateur doit accepter les conditions Docker et terminer si nécessaire la préparation WSL 2.

Le script généré `nexus-docker-up.cmd` :

1. cherche `docker` dans le `PATH` ;
2. cherche aussi la CLI dans les emplacements Docker Desktop per-user/all-users ;
3. teste `docker info` ;
4. démarre Docker Desktop si le moteur n'est pas prêt ;
5. attend jusqu'à environ 180 secondes ;
6. lance ensuite `docker compose up -d`.

Si Docker n'est toujours pas prêt, le script sort avec un code d'erreur et peut être relancé après la fin de l'onboarding Docker Desktop/WSL 2.

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

Le wizard Windows vérifie le **port hôte externe** demandé avant installation. S'il est occupé, il sélectionne automatiquement le premier port TCP libre suivant. Le port interne du conteneur n'est pas sondé sur Windows.

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

- état Docker Desktop/CLI : détecté ou installation automatique prévue ;
- rappel que la licence Docker reste à accepter au premier démarrage ;
- image ;
- conteneur ;
- adresse/ports REST résolus ;
- repository ;
- restart policy ;
- Ollama activé/désactivé ;
- cinq intégrations assistants ;
- runtime MCP choisi ;
- démarrage Docker post-install.

Aucun téléchargement ni installation Docker Desktop n'est lancé avant la validation de ce récapitulatif.

## Désinstallation

La désinstallation NEXUS :

- arrête le Compose NEXUS géré ;
- supprime les fichiers NEXUS installés ;
- conserve `NEXUS_HOME` et les repositories ;
- **conserve Docker Desktop**, même lorsqu'il a été installé par le wizard ;
- conserve WSL ;
- ne retire aucune image ou configuration Docker qui n'appartient pas au déploiement NEXUS géré.

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
