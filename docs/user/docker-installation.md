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

CLI et MCP ne dépendent pas du contrat de publication REST.

### `docker run` REST en loopback

Un lancement REST manuel en mode `loopback-forward` doit déclarer **explicitement** l'adresse de publication hôte. L'image ne fournit volontairement aucune valeur par défaut pour cette déclaration : le conteneur ne peut pas savoir quelle adresse le daemon Docker a réellement utilisée pour `-p`.

Exemple sûr :

```bash
TOKEN="$(openssl rand -hex 32)"
docker run --rm \
  -e NEXUS_REST_API_TOKEN="$TOKEN" \
  -e NEXUS_DOCKER_HOST_FORWARD_ADDRESS=127.0.0.1 \
  -p 127.0.0.1:8080:8080 \
  nexus-context-engine:0.2.0 rest
```

Les deux valeurs doivent décrire le même déploiement :

```text
NEXUS_DOCKER_HOST_FORWARD_ADDRESS=127.0.0.1
-p 127.0.0.1:8080:8080
```

Ces lancements sont volontairement refusés :

```text
# déclaration absente
docker run ... -p 127.0.0.1:8080:8080 nexus-context-engine:0.2.0 rest

# déclaration distante déguisée en loopback-forward
NEXUS_DOCKER_HOST_FORWARD_ADDRESS=0.0.0.0
-p 0.0.0.0:8080:8080
NEXUS_REST_EXPOSURE_MODE=loopback-forward
```

Attention : `docker run -p 8080:8080 ...` publie généralement sur toutes les interfaces hôte. Il ne doit donc pas être utilisé en `loopback-forward`.

### Exposition distante volontaire

Une exposition distante ne doit pas être présentée comme `loopback-forward`. Elle doit utiliser un mode prévu pour une topologie HTTPS, par exemple `reverse-proxy-https`, avec un reverse proxy TLS de confiance devant NEXUS, un token fort et une allowlist de racines projet.

Exemple de variables NEXUS pour cette topologie :

```text
NEXUS_REST_EXPOSURE_MODE=reverse-proxy-https
NEXUS_REST_API_TOKEN=<au moins 32 octets aléatoires>
NEXUS_REST_ALLOWED_PROJECT_ROOTS=/workspace
```

Le mode d'exposition exprime le contrat de déploiement ; il ne configure pas à lui seul le certificat ou le reverse proxy.

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

Le test vérifie CLI, MCP STDIO, REST loopback positif, l'absence de déclaration de forward et le refus d'un forward distant déclaré en `loopback-forward`.

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

### Contrat `loopback-forward`

À l'intérieur du conteneur, NEXUS écoute volontairement sur `0.0.0.0:8080`. Cette adresse interne ne prouve rien sur l'adresse utilisée par le daemon Docker côté hôte.

Le Compose officiel ferme cette ambiguïté avec une déclaration explicite transmise au conteneur :

```text
NEXUS_DOCKER_BIND_ADDRESS
        │
        ├── ports: <bind>:<host-port>:<container-port>
        └── NEXUS_DOCKER_HOST_FORWARD_ADDRESS=<bind>
```

Le défaut officiel reste `127.0.0.1`. Si `NEXUS_DOCKER_BIND_ADDRESS=0.0.0.0` est configuré tout en conservant `NEXUS_REST_EXPOSURE_MODE=loopback-forward`, NEXUS refuse de démarrer.

Le `.env` généré par le wizard conserve **une seule source de vérité**, `NEXUS_DOCKER_BIND_ADDRESS`. Le Compose dérive lui-même `NEXUS_DOCKER_HOST_FORWARD_ADDRESS`; l'utilisateur n'a pas deux adresses indépendantes à synchroniser.

## REST et token

À l'intérieur de Docker, Quarkus écoute sur `0.0.0.0` pour pouvoir être publié. La politique de sécurité NEXUS exige donc un `NEXUS_REST_API_TOKEN`.

L'assistant Windows génère un token local **cryptographiquement sûr** (BCryptGenRandom, 256 bits, encodé en hexadécimal) si Docker est choisi et que l'utilisateur n'en fournit pas.

Le template Compose est **fail-fast** : `NEXUS_REST_API_TOKEN` y est déclaré obligatoire (`${NEXUS_REST_API_TOKEN:?...}`). En utilisation autonome (hors assistant), `docker compose` refuse donc de démarrer immédiatement et explicitement tant qu'aucun token n'est fourni. Définissez-le dans le fichier `.env` à côté du `docker-compose.yml` :

```dotenv
NEXUS_REST_API_TOKEN=<votre-token>
```

Vous pouvez générer un token robuste avec, par exemple :

```bash
openssl rand -hex 32
```

## Threat model du forward Docker

NEXUS **peut vérifier** depuis le conteneur :

- `NEXUS_RUNTIME=docker` ;
- le mode d'exposition déclaré ;
- la présence et la robustesse du Bearer token ;
- l'allowlist de racines ;
- la déclaration `NEXUS_DOCKER_HOST_FORWARD_ADDRESS` ;
- que cette déclaration est une adresse loopback en mode `loopback-forward`.

NEXUS **ne peut pas introspecter de manière fiable** depuis le conteneur l'adresse de publication réellement choisie par le daemon Docker. Le processus ne peut donc pas prouver cryptographiquement qu'un opérateur manuel n'a pas menti, par exemple en combinant :

```text
NEXUS_DOCKER_HOST_FORWARD_ADDRESS=127.0.0.1
-p 0.0.0.0:8080:8080
```

Le packaging officiel élimine ce mauvais état en utilisant la **même variable** pour le bind Docker et la déclaration transmise au guard. Un lancement manuel qui fournit volontairement une fausse déclaration sort du contrat vérifiable par le processus ; l'administrateur doit garder `-p` et `NEXUS_DOCKER_HOST_FORWARD_ADDRESS` cohérents.

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

Le wizard limite la publication Docker à loopback. Le `.env` généré fixe `NEXUS_REST_EXPOSURE_MODE=loopback-forward`; le Compose dérive la déclaration de forward de `NEXUS_DOCKER_BIND_ADDRESS` et la transmet au conteneur.

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

Le setup NEXUS peut **installer automatiquement Ollama** (sémantique activée + Ollama absent + option d'installation automatique cochée + signature Authenticode `CN=Ollama Inc.` vérifiée) ; sinon il configure uniquement la connexion. Ollama n'est jamais installé si la sémantique est désactivée, ni réinstallé s'il est déjà présent.

Dans un conteneur, `127.0.0.1` désigne le conteneur lui-même. Pour un Ollama tournant sur l'hôte Windows, NEXUS écrit automatiquement `http://host.docker.internal:11434` dans le `.env` Docker et résout aussi cette bascule à l'exécution (`NEXUS_RUNTIME=docker`). Le service Compose déclare `extra_hosts: host.docker.internal:host-gateway` pour rester portable sur Docker Engine Linux. Un Ollama distant ou sur DNS personnalisé est conservé tel quel.

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

construit et smoke l'image sur les PR concernées. Il vérifie aussi que le Compose par défaut publie sur `127.0.0.1`, que la déclaration interne correspond au bind, et qu'un Compose explicitement distant en `loopback-forward` est rejeté. Sur `main`, il publie :

```text
ghcr.io/fturleque/nexus-context-engine:<version>
ghcr.io/fturleque/nexus-context-engine:latest
```

Le setup utilise l'image versionnée par défaut.
