# Installation Windows et assistant de déploiement NEXUS

Ce document décrit le setup Windows NEXUS 0.2.0 et constitue la documentation fonctionnelle de l'assistant de déploiement.

La template exécutable est :

```text
packaging/windows/nexus-installer.iss.template
```

Le contrat exhaustif de chaque écran est :

```text
docs/user/deployment-wizard-template.md
```

## Livrables Windows

La release Windows x64 produit :

```text
target\dist\nexus-context-engine-0.2.0-windows-x64.zip
target\dist\nexus-context-engine-0.2.0-windows-x64.zip.sha256
target\dist\NEXUS-0.2.0-windows-x64-setup.exe
target\dist\NEXUS-0.2.0-windows-x64-setup.exe.sha256
```

Le payload natif embarque son propre runtime Java construit avec `jpackage`. Une JVM système n'est pas requise après installation.

Le payload contient :

```text
app\                         app-image jpackage + runtime Java
lib\nexus-cli.jar             CLI
lib\nexus-mcp.jar             serveur MCP STDIO
lib\nexus-assistant-clients.jar générateur d'intégrations assistants
rest\                        application Quarkus
nexus.cmd                    launcher CLI natif
nexus-mcp.cmd                launcher MCP natif
nexus-rest.cmd               launcher REST natif
nexus-assistant-clients.cmd  launcher générateur assistants
nexus-docker.cmd             CLI dans le conteneur
nexus-docker-mcp.cmd         MCP STDIO via docker exec -i
docker\docker-compose.yml.template
```

## Modes de déploiement

### Natif Windows

- aucun Docker requis ;
- aucune JVM système requise après installation ;
- accès direct aux repositories Windows ;
- MCP local en STDIO.

### Docker

- Docker CLI + Docker Desktop/Engine requis ;
- image NEXUS configurable ;
- `NEXUS_HOME` persistant ;
- repository monté sous `/workspace:ro` ;
- REST publié sur un port hôte configurable ;
- MCP reste en STDIO via `docker exec -i`.

### Natif + Docker

Les deux runtimes sont installés. L'utilisateur choisit ensuite lequel sera utilisé par les intégrations MCP.

## Écran 1 — Mode de déploiement

Choix exclusifs :

```text
Natif Windows
Docker
Natif + Docker
```

Le mode natif est sélectionné par défaut.

## Écran 2 — Profil

```text
Recommandé
Personnalisé
```

Le profil recommandé garde les valeurs sûres par défaut. Le profil personnalisé expose les composants natifs et les paramètres runtime avancés.

## Écran 3 — Composants natifs

Visible uniquement en mode natif/both et profil personnalisé.

Choix :

```text
CLI NEXUS                  activé par défaut
Serveur MCP STDIO          activé par défaut
API REST Quarkus           désactivée par défaut
```

Au moins une surface native doit rester sélectionnée.

## Écran 4 — Configuration runtime

Visible en profil personnalisé.

### `NEXUS_HOME`

Défaut :

```text
%USERPROFILE%\.nexus
```

Il est conservé lors de la désinstallation.

### REST natif

Défauts :

```text
host = 127.0.0.1
port = 8080
```

Le port doit être compris entre `1` et `65535`.

Si REST écoute hors loopback, un token est obligatoire.

### Token REST

Sur loopback il peut rester vide. En Docker, le processus interne écoute nécessairement hors loopback ; si aucun token n'est fourni, le setup en génère un pour respecter la politique de sécurité NEXUS.

## Écran 5 — Recherche sémantique

Le provider sémantique n'est **plus saisi dans un champ texte libre**.

Le wizard propose une case unique :

```text
[ ] Activer Ollama pour la recherche sémantique
```

Défaut : désactivé.

Lorsque la case est cochée :

```text
NEXUS_SEMANTIC_PROVIDER=ollama
```

Lorsque la case est décochée :

```text
NEXUS_SEMANTIC_PROVIDER=
```

Important : le setup **configure NEXUS pour utiliser Ollama mais n'installe pas le binaire Ollama**. L'instance doit déjà être installée ou accessible.

## Écran 6 — Configuration Ollama

Visible uniquement si :

```text
Ollama activé + profil Personnalisé
```

URL par défaut :

```text
http://127.0.0.1:11434
```

En profil recommandé, cocher Ollama conserve cette URL sans afficher l'écran avancé.

En Docker, `127.0.0.1` pointe vers le conteneur lui-même. Si Ollama tourne sur l'hôte Windows, l'URL doit être adaptée à la topologie Docker utilisée.

## Écran 7 — Configuration Docker

Visible dès que Docker est sélectionné.

Valeurs par défaut :

```text
Image                ghcr.io/fturleque/nexus-context-engine:0.2.0
Conteneur            nexus
Adresse hôte         127.0.0.1
Port REST hôte       8080
Port REST conteneur  8080
Repository           %USERPROFILE%
Restart policy       unless-stopped
```

Le port hôte est entièrement personnalisable. Il est généralement préférable de conserver `8080` dans le conteneur et de changer uniquement le port hôte.

Exemples :

```text
127.0.0.1:9080  -> container:8080
127.0.0.1:18080 -> container:8080
```

Le repository choisi est monté :

```text
<chemin Windows> -> /workspace:ro
```

`NEXUS_HOME` est monté en lecture/écriture sous `/data/nexus`.

## Écran 8 — Intégrations assistants IA

La même matrice est utilisée partout dans NEXUS : wizard, générateur standalone et documentation.

### GitHub Copilot CLI

Si `copilot` est détecté et qu'aucune entrée `nexus` n'existe :

```text
copilot mcp add nexus --tools "*" -- <commande MCP NEXUS>
```

Une entrée existante est préservée.

### GitHub Copilot JetBrains

Le setup ne devine pas le chemin de configuration interne de l'IDE. Il génère :

```text
{installation}\integrations\copilot-jetbrains.mcp.json
```

Le fichier utilise le schéma `servers`.

### Claude CLI / Claude Code

Si `claude` est détecté et qu'aucune entrée `nexus` n'existe :

```text
claude mcp add nexus --scope user -- <commande MCP NEXUS>
```

Le profil projet reste disponible avec `nexus-assistant-clients.cmd`.

Asset géré :

```text
{installation}\integrations\connect-claude-cli.cmd
```

### Codex Desktop

Le setup traite Codex Desktop comme un client de la configuration Codex locale partagée.

Si la CLI `codex` est détectée et qu'aucune entrée `nexus` n'existe :

```text
codex mcp add nexus -- <commande MCP NEXUS>
```

Le setup génère **également** un snippet TOML :

```text
{installation}\integrations\codex-desktop.mcp.toml
```

Structure :

```toml
[mcp_servers.nexus]
command = "<java.exe embarqué ou docker>"
args = ["..."]
```

Cela permet une configuration manuelle si la CLI `codex` n'est pas présente. Une entrée existante `nexus` n'est pas écrasée.

### Client MCP générique

Le setup génère :

```text
{installation}\integrations\generic-mcp.json
```

avec le schéma `mcpServers`.

### Authentification

NEXUS ne gère aucune authentification Copilot, Claude ou Codex et ne stocke aucun token externe.

## Écran 9 — Runtime MCP en mode Both

Visible uniquement en mode `Natif + Docker`.

Choix :

```text
MCP natif avec Java embarqué
MCP Docker via docker exec -i
```

Ce choix s'applique aux cinq intégrations assistants.

## Dernière étape — Récapitulatif avant installation

La page standard Inno Setup **Ready to Install** est obligatoire et affiche un récapitulatif construit par `UpdateReadyMemo`.

Elle est la dernière étape avant le bouton **Installer**.

Le récapitulatif contient :

- mode Natif / Docker / Both ;
- profil ;
- répertoire d'installation ;
- `NEXUS_HOME` ;
- composants natifs ;
- REST natif et port ;
- ajout au PATH ;
- recherche sémantique désactivée ou Ollama activé ;
- URL Ollama si activé ;
- rappel que le binaire Ollama n'est pas installé par NEXUS ;
- image Docker ;
- conteneur ;
- bind/ports ;
- repository ;
- restart policy ;
- démarrage Docker post-install ;
- GitHub Copilot CLI ;
- GitHub Copilot JetBrains ;
- Claude CLI / Claude Code ;
- Codex Desktop ;
- MCP générique ;
- runtime MCP utilisé par ces intégrations.

Exemple :

```text
RÉCAPITULATIF NEXUS

Mode : Natif + Docker
Profil : Personnalisé
NEXUS_HOME : C:\Users\<user>\.nexus

Recherche sémantique : Ollama ACTIVÉ
  - URL : http://127.0.0.1:11434
  - Le setup configure NEXUS mais n'installe pas le binaire Ollama.

Assistants MCP :
  - GitHub Copilot CLI
  - GitHub Copilot JetBrains
  - Claude CLI / Claude Code
  - Codex Desktop
  - Client MCP générique
  - Runtime MCP : Natif / Java embarqué
```

Aucune configuration externe n'est ajoutée avant la confirmation et l'exécution réelle de l'installation.

## Fichiers générés

### Natif

```text
{installation}\config\nexus-native.env.cmd
```

Variables :

```text
NEXUS_HOME
NEXUS_REST_HOST
NEXUS_REST_PORT
NEXUS_REST_API_TOKEN
NEXUS_SEMANTIC_PROVIDER
NEXUS_OLLAMA_BASE_URL
```

### Docker

```text
{installation}\docker\.env
{installation}\docker\docker-compose.yml
{installation}\nexus-docker-up.cmd
{installation}\nexus-docker-down.cmd
```

Variables principales :

```text
NEXUS_DOCKER_IMAGE
NEXUS_DOCKER_CONTAINER
NEXUS_DOCKER_RESTART_POLICY
NEXUS_DOCKER_BIND_ADDRESS
NEXUS_DOCKER_HOST_PORT
NEXUS_DOCKER_CONTAINER_PORT
NEXUS_HOME_BIND
NEXUS_REPOSITORY_BIND
NEXUS_SEMANTIC_PROVIDER
NEXUS_OLLAMA_BASE_URL
NEXUS_REST_API_TOKEN
```

### Assistants

Selon les choix :

```text
integrations\connect-copilot-cli.cmd
integrations\copilot-jetbrains.mcp.json
integrations\connect-claude-cli.cmd
integrations\connect-codex-desktop.cmd
integrations\codex-desktop.mcp.toml
integrations\generic-mcp.json
```

## MCP Docker et ports

MCP n'a aucun port Docker :

```text
client IA -> STDIO -> docker exec -i -> NexusMcpServer
```

Seule l'API REST utilise un mapping TCP.

## PATH Windows

La tâche :

```text
Ajouter NEXUS au PATH de l'utilisateur
```

est proposée lorsque le mode natif est installé.

L'entrée gérée est mémorisée sous :

```text
HKCU\Software\FTurleque\NEXUS\ManagedPath
```

La désinstallation retire uniquement cette entrée.

## Upgrade / reconfiguration

Les principaux choix sont persistés sous :

```text
HKCU\Software\FTurleque\NEXUS
```

Sont notamment conservés :

- mode de déploiement ;
- `NEXUS_HOME` ;
- paramètres REST ;
- état Ollama + URL ;
- image et conteneur Docker ;
- ports ;
- repository ;
- restart policy.

## Désinstallation

La désinstallation :

- arrête le Compose NEXUS géré ;
- retire l'entrée PATH gérée ;
- retire uniquement les intégrations Copilot CLI / Claude CLI / Codex ajoutées avec succès par le setup ;
- supprime les fichiers applicatifs ;
- conserve `NEXUS_HOME` ;
- conserve les repositories utilisateur ;
- ne désinstalle pas Docker ;
- ne désinstalle pas Ollama ;
- ne supprime pas les configurations MCP qui n'ont pas été créées par NEXUS.

## Construire localement

Prérequis :

- Windows x64 ;
- `JAVA_HOME` vers JDK 21+ ;
- Inno Setup 7 (le script peut le préparer si nécessaire).

Distribution :

```powershell
.\scripts\release\build-windows-distribution.ps1
```

EXE :

```powershell
.\scripts\release\build-windows-installer.ps1
```

Setup produit :

```text
target\dist\NEXUS-0.2.0-windows-x64-setup.exe
```

Smoke install/uninstall :

```powershell
$setup = (Resolve-Path '.\target\dist\NEXUS-0.2.0-windows-x64-setup.exe').Path
.\scripts\release\test-windows-installer.ps1 -Setup $setup
```

## Docker local

```powershell
$image = & .\scripts\release\build-docker-image.ps1
& .\scripts\release\test-docker-runtime.ps1 -Image $image -HostPort 18080
```
