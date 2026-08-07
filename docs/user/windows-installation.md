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

- si Docker Desktop/CLI est déjà présent, il est réutilisé ;
- si Docker est absent, le wizard peut télécharger et installer automatiquement Docker Desktop en mode utilisateur/WSL 2 ;
- image NEXUS configurable ;
- `NEXUS_HOME` persistant ;
- repository monté sous `/workspace:ro` ;
- REST publié sur un port hôte configurable et vérifié avant installation ;
- MCP reste en STDIO via `docker exec -i`.

### Natif + Docker

Les deux runtimes NEXUS sont installés. L'utilisateur choisit ensuite lequel sera utilisé par les intégrations MCP.

Si REST natif et REST Docker sont tous les deux configurés, leurs ports externes ne peuvent pas être identiques : le wizard réserve le port natif et déplace automatiquement le port hôte Docker si nécessaire.

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

Lorsque REST natif est activé, NEXUS vérifie la disponibilité du port par une tentative de bind TCP sur l'adresse choisie :

```text
port demandé libre     -> le port est conservé
port demandé occupé    -> recherche du premier port disponible suivant
```

La recherche avance jusqu'à `65535`; si nécessaire elle reprend à `1024`. La valeur finale remplace automatiquement le champ du wizard et c'est cette valeur qui est persistée et affichée dans le récapitulatif final.

Exemple :

```text
8080 occupé
8081 occupé
8082 libre
=> REST natif utilisera 8082
```

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

## Écran 7 — Docker Desktop

Visible uniquement lorsqu'un mode Docker est sélectionné **et qu'aucun runtime Docker n'est détecté**.

Option :

```text
[x] Installer automatiquement Docker Desktop s'il est absent
```

Cette option est cochée par défaut.

Si elle reste cochée, après validation de la page **Ready to Install** et clic sur Installer, NEXUS :

1. télécharge l'installateur x64 officiel depuis :

   ```text
   https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe
   ```

2. vérifie la signature Authenticode ; le statut doit être valide et le certificat doit être signé pour `Docker Inc` ;
3. exécute :

   ```text
   Docker Desktop Installer.exe install --user --backend=wsl-2 --quiet
   ```

4. vérifie qu'un runtime Docker est ensuite détectable ;
5. lance Docker Desktop après l'installation NEXUS.

Le setup ne passe volontairement **pas** `--accept-license`. NEXUS ne doit pas accepter la Docker Subscription Service Agreement à la place de l'utilisateur. Au premier lancement Docker Desktop, l'utilisateur doit accepter les conditions Docker pour démarrer le moteur.

Le mode Docker Desktop per-user cible normalement :

```text
%LOCALAPPDATA%\Programs\DockerDesktop
```

L'installation Docker Desktop elle-même ne nécessite normalement pas de droits administrateur dans ce mode. En revanche, l'activation ou la mise à jour initiale de WSL 2 peut nécessiter une élévation et éventuellement un redémarrage.

Si l'installation automatique est décochée alors qu'aucun runtime Docker n'existe, le wizard bloque la poursuite du mode Docker.

Docker Desktop est considéré comme un **prérequis partagé** : le désinstalleur NEXUS ne le supprime jamais.

## Écran 8 — Configuration Docker

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

Le port hôte est entièrement personnalisable. Avant de quitter l'écran, le wizard tente réellement de réserver temporairement ce port TCP sur l'adresse hôte choisie. S'il est déjà utilisé, il teste automatiquement les ports suivants jusqu'à trouver le premier disponible, met le champ à jour et informe l'utilisateur du changement.

Exemple :

```text
Port demandé : 8080
8080 occupé
8081 occupé
8082 disponible
=> Port REST hôte retenu : 8082
```

En mode `Natif + Docker`, si le REST natif utilise déjà `8080`, Docker ne pourra pas reprendre `8080` même s'il est encore libre au niveau OS au moment du wizard : ce port est considéré comme réservé au runtime natif et Docker reçoit le premier autre port disponible.

Il est généralement préférable de conserver `8080` dans le conteneur et de changer uniquement le port hôte. Le **port REST interne du conteneur n'est pas testé sur Windows**, car il appartient à l'espace réseau du conteneur.

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

### Démarrage Docker généré

`nexus-docker-up.cmd` ne dépend pas uniquement du `PATH` courant. Il recherche aussi la CLI Docker Desktop dans les emplacements per-user et all-users.

Avant `docker compose up -d`, le script :

1. teste `docker info` ;
2. démarre Docker Desktop si nécessaire ;
3. attend le moteur jusqu'à environ 180 secondes ;
4. lance le Compose NEXUS lorsque le moteur est prêt.

Si l'onboarding Docker Desktop/WSL 2 n'est pas terminé dans ce délai, le script se termine avec un code non nul et peut être relancé ensuite.

## Écran 9 — Intégrations assistants IA

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
claude mcp add --scope user nexus -- <commande MCP NEXUS>
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

## Écran 10 — Runtime MCP en mode Both

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
- REST natif et **port externe final vérifié** ;
- ajout au PATH ;
- recherche sémantique désactivée ou Ollama activé ;
- URL Ollama si activé ;
- rappel que le binaire Ollama n'est pas installé par NEXUS ;
- état Docker Desktop/CLI : déjà détecté ou installation automatique prévue ;
- rappel que la licence Docker reste à accepter par l'utilisateur lorsqu'une installation Docker Desktop est prévue ;
- image Docker ;
- conteneur ;
- bind et **port hôte Docker final vérifié** ;
- port REST interne du conteneur ;
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

Docker :
  - Docker Desktop : sera téléchargé depuis desktop.docker.com et installé en mode utilisateur (WSL 2)
  - La licence Docker devra être acceptée par l'utilisateur au premier démarrage.
  - Image : ghcr.io/fturleque/nexus-context-engine:0.2.0
  - REST : 127.0.0.1:8081 -> conteneur:8080

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

Aucun téléchargement Docker Desktop, aucune configuration externe et aucun fichier applicatif n'est installé avant la confirmation de cette page.

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

`NEXUS_REST_PORT` contient le port externe final retenu après vérification de disponibilité.

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

`NEXUS_DOCKER_HOST_PORT` contient le port hôte final retenu après vérification ; il peut donc différer du port initialement demandé. `NEXUS_DOCKER_CONTAINER_PORT` reste la valeur interne explicitement choisie.

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
- paramètres REST et ports résolus ;
- état Ollama + URL ;
- préférence d'installation automatique Docker Desktop ;
- information qu'une installation Docker Desktop a été déclenchée par NEXUS ;
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
- **ne désinstalle jamais Docker Desktop**, même si NEXUS l'a installé ;
- ne désinstalle pas WSL ;
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

Le smoke standard reste en mode natif et ne télécharge jamais Docker Desktop.

## Docker local

```powershell
$image = & .\scripts\release\build-docker-image.ps1
& .\scripts\release\test-docker-runtime.ps1 -Image $image -HostPort 18080
```
