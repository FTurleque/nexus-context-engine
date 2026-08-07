# Installation Windows et assistant de déploiement NEXUS

Ce document décrit le setup Windows NEXUS 0.2.0 et constitue la documentation fonctionnelle de l'assistant de déploiement.

Le setup n'est plus uniquement un copieur de fichiers. Il permet de choisir **comment NEXUS sera exécuté**, quelles surfaces seront utilisées, quels paramètres seront appliqués et quels assistants MCP devront être préparés.

La template exécutable de référence est :

```text
packaging/windows/nexus-installer.iss.template
```

La matrice documentaire exhaustive de toutes les valeurs est :

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

Le payload natif embarque son propre runtime Java construit avec `jpackage`. Une JVM système n'est donc pas requise après installation.

Le payload Windows contient désormais les surfaces nécessaires à l'onboarding complet :

```text
app\                         app-image jpackage + runtime Java
lib\nexus-cli.jar             CLI
lib\nexus-mcp.jar             serveur MCP STDIO
lib\nexus-assistant-clients.jar générateur de configurations assistants
rest\                        application Quarkus
nexus.cmd                    launcher CLI natif
nexus-mcp.cmd                launcher MCP natif
nexus-rest.cmd               launcher REST natif
nexus-assistant-clients.cmd  launcher générateur assistants
nexus-docker.cmd             CLI dans le conteneur
nexus-docker-mcp.cmd         MCP STDIO via docker exec -i
docker\docker-compose.yml.template
```

## Prérequis selon le mode

### Natif Windows

Après installation :

- Windows x64 compatible ;
- aucune JVM système ;
- aucun Maven ;
- aucun Docker.

Le Java embarqué est utilisé par CLI, MCP et REST.

### Docker

Le setup ne tente pas d'installer Docker Desktop à la place de l'utilisateur. Le mode Docker exige :

- Docker CLI présent ;
- Docker Desktop ou Docker Engine démarré ;
- accès au registre configuré pour l'image sélectionnée ;
- partage/accès Docker aux lecteurs Windows montés comme repositories.

Le setup vérifie que la commande `docker` est disponible avant d'accepter le mode Docker.

### Natif + Docker

Les prérequis Docker s'ajoutent aux garanties du mode natif. Les deux runtimes peuvent coexister et partager le même `NEXUS_HOME` si l'utilisateur conserve le mapping par défaut.

## Écran 1 — Mode de déploiement

Trois choix exclusifs :

### Natif Windows

Recommandé pour l'usage local avec Copilot CLI, Copilot JetBrains et Claude Code.

Caractéristiques :

- runtime Java embarqué ;
- MCP local en STDIO ;
- accès direct aux chemins Windows ;
- Docker non requis.

### Docker

NEXUS est exécuté dans un conteneur.

Caractéristiques :

- image configurable ;
- nom de conteneur configurable ;
- `NEXUS_HOME` monté comme volume ;
- repository Windows monté en lecture seule sous `/workspace` ;
- REST publié avec adresse et port hôte configurables ;
- MCP reste en STDIO via `docker exec -i`.

### Natif + Docker

Installe les deux modes. Une page supplémentaire permet alors de choisir quel runtime MCP les intégrations assistants utiliseront.

## Écran 2 — Profil d'installation

### Recommandé

Applique les valeurs sûres par défaut.

Pour le natif :

- CLI : activé ;
- MCP STDIO : activé ;
- REST natif : désactivé ;
- `NEXUS_HOME` : `%USERPROFILE%\.nexus` ;
- sémantique : désactivée.

Pour Docker :

- image : `ghcr.io/fturleque/nexus-context-engine:0.2.0` ;
- conteneur : `nexus` ;
- bind REST hôte : `127.0.0.1` ;
- port REST hôte : `8080` ;
- port REST conteneur : `8080` ;
- restart policy : `unless-stopped`.

### Personnalisé

Expose les composants natifs et les paramètres runtime détaillés.

## Écran 3 — Composants natifs

Visible uniquement en mode natif ou natif + Docker et avec le profil personnalisé.

Choix indépendants :

- **CLI NEXUS** ;
- **Serveur MCP STDIO** ;
- **API REST Quarkus**.

Au moins une surface native doit rester sélectionnée.

Le runtime Java embarqué est partagé entre les surfaces natives installées.

## Écran 4 — Configuration runtime

Visible en profil personnalisé.

### `NEXUS_HOME`

Défaut :

```text
%USERPROFILE%\.nexus
```

Ce répertoire contient les données persistantes NEXUS et n'est pas supprimé par le désinstallateur.

### REST natif — adresse

Défaut :

```text
127.0.0.1
```

### REST natif — port

Défaut :

```text
8080
```

Validation : entier de `1` à `65535`.

### Token REST

Optionnel lorsque REST natif écoute uniquement sur loopback.

Si l'adresse native n'est ni `127.0.0.1` ni `localhost`, un token est obligatoire. NEXUS refuse déjà un démarrage REST non-loopback sans token.

Pour Docker, le processus Quarkus doit écouter `0.0.0.0` à l'intérieur du conteneur afin que Docker publie le port. Si aucun token n'est fourni, le setup génère une valeur locale et l'écrit dans le fichier `.env` Docker afin de respecter la frontière de sécurité REST NEXUS.

### Sémantique

Valeurs habituelles :

```text
vide     recherche sémantique désactivée
ollama   provider Ollama activé
```

URL Ollama par défaut proposée :

```text
http://127.0.0.1:11434
```

En Docker, une instance Ollama installée sur l'hôte peut nécessiter une URL accessible depuis le conteneur, par exemple une adresse adaptée à Docker Desktop. Cette valeur doit être personnalisée selon l'environnement.

## Écran 5 — Configuration Docker

Visible dès que Docker fait partie du mode choisi.

### Image

Défaut :

```text
ghcr.io/fturleque/nexus-context-engine:0.2.0
```

Pour tester une image construite localement, remplacer par exemple par :

```text
nexus-context-engine:0.2.0
```

### Nom du conteneur

Défaut :

```text
nexus
```

Les launchers `nexus-docker.cmd` et `nexus-docker-mcp.cmd` lisent ce nom depuis `docker\.env`.

### Adresse d'écoute hôte

Défaut :

```text
127.0.0.1
```

Conserver loopback évite d'exposer REST sur le LAN.

### Port REST hôte

Défaut : `8080`.

Il est entièrement personnalisable. Exemples :

```text
127.0.0.1:9080  -> conteneur:8080
127.0.0.1:18080 -> conteneur:8080
```

C'est le réglage à privilégier lorsque plusieurs instances ou services utilisent déjà `8080`.

### Port REST conteneur

Défaut : `8080`.

Il peut être changé en mode avancé, mais il est généralement préférable de conserver `8080` en interne et de modifier uniquement le port hôte.

### Repository / racine de projets

Chemin Windows monté en lecture seule :

```text
<chemin Windows> -> /workspace:ro
```

Exemple :

```text
N:\workspace-dev -> /workspace:ro
```

Docker Desktop doit être autorisé à accéder au lecteur concerné. Les chemins sont normalisés avec `/` dans le `.env` généré.

### Restart policy

Défaut :

```text
unless-stopped
```

La valeur est transmise directement à Docker Compose.

## Écran 6 — Intégrations assistants IA

L'utilisateur choisit explicitement les intégrations à préparer.

### GitHub Copilot CLI

Si `copilot` est détecté et qu'aucune entrée MCP `nexus` n'existe déjà, le setup exécute une commande équivalente à :

Mode natif :

```text
copilot mcp add nexus --tools "*" -- <java NEXUS> -jar <nexus-mcp.jar>
```

Mode Docker :

```text
copilot mcp add nexus --tools "*" -- docker exec -i <container> java -jar /opt/nexus/lib/nexus-mcp.jar
```

Si une entrée `nexus` existe déjà, elle est préservée.

### GitHub Copilot JetBrains

Le plugin JetBrains gère lui-même l'emplacement de son `mcp.json`. Le setup ne devine donc pas un chemin interne susceptible de varier selon l'IDE ou la version.

Il génère :

```text
{installation}\integrations\copilot-jetbrains.mcp.json
```

Le JSON utilise le schéma `servers` et contient soit le Java NEXUS embarqué, soit `docker exec -i` selon le runtime MCP choisi.

Dans Copilot Chat JetBrains, utiliser **Configure your MCP server / Add MCP Tools** puis intégrer l'entrée `nexus` générée.

### Claude Code

Le setup cible le scope utilisateur lorsque la CLI `claude` est détectée :

```text
claude mcp add nexus --scope user -- <commande MCP NEXUS>
```

Une entrée existante `nexus` est préservée.

Le scope projet reste disponible via le générateur `nexus-assistant-clients.cmd` lorsque l'utilisateur veut produire un `.mcp.json` versionné avec un repository particulier.

### Client MCP générique

Le setup génère :

```text
{installation}\integrations\generic-mcp.json
```

Le document utilise le schéma `mcpServers` et peut servir de base aux clients compatibles STDIO.

## Écran 7 — Runtime MCP en mode Both

Visible uniquement lorsque **Natif + Docker** est sélectionné.

Choix :

- MCP natif avec Java embarqué ;
- MCP Docker via `docker exec -i`.

Ce choix affecte les configurations Copilot/Claude/génériques générées par le setup. Il ne désactive aucun des deux runtimes.

## Fichiers de configuration générés

### Natif

```text
{installation}\config\nexus-native.env.cmd
```

Contient les valeurs choisies pour :

```text
NEXUS_HOME
NEXUS_REST_HOST
NEXUS_REST_PORT
NEXUS_REST_API_TOKEN
NEXUS_SEMANTIC_PROVIDER
NEXUS_OLLAMA_BASE_URL
```

Les launchers natifs chargent ce fichier avant de démarrer NEXUS.

### Docker

```text
{installation}\docker\.env
{installation}\docker\docker-compose.yml
{installation}\nexus-docker-up.cmd
{installation}\nexus-docker-down.cmd
```

Le Compose est issu de :

```text
packaging/docker/docker-compose.yml.template
```

Les variables principales sont :

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

## MCP Docker et ports

MCP ne possède **aucun port Docker** dans cette architecture.

Le transport reste :

```text
client IA -> STDIO -> docker exec -i -> NexusMcpServer
```

Seule l'API REST utilise un mapping TCP.

Cette séparation évite d'introduire un transport MCP HTTP simplement pour le packaging.

## PATH Windows

La tâche :

```text
Ajouter NEXUS au PATH de l'utilisateur
```

est proposée uniquement lorsque le mode natif est installé.

Le setup enregistre exactement l'entrée qu'il gère dans :

```text
HKCU\Software\FTurleque\NEXUS\ManagedPath
```

La désinstallation retire uniquement cette entrée.

## Démarrage Docker

Si la tâche **Démarrer le déploiement Docker à la fin de l'installation** est sélectionnée, le setup exécute :

```text
nexus-docker-up.cmd
```

Ce launcher utilise :

```text
docker compose --env-file docker\.env -f docker\docker-compose.yml up -d
```

Des raccourcis menu Démarrer permettent ensuite de démarrer ou arrêter le déploiement Docker.

## Upgrade / reconfiguration

Les principaux choix sont persistés sous :

```text
HKCU\Software\FTurleque\NEXUS
```

Lors d'une réinstallation, l'assistant recharge notamment :

- mode de déploiement ;
- `NEXUS_HOME` ;
- paramètres REST ;
- sémantique/Ollama ;
- image et conteneur Docker ;
- ports ;
- repository ;
- restart policy.

La reconfiguration remplace les fichiers de configuration **gérés par le setup**, pas les données NEXUS.

## Désinstallation

La désinstallation :

- arrête le Compose NEXUS géré si Docker avait été configuré ;
- supprime les fichiers applicatifs installés ;
- retire du PATH uniquement l'entrée gérée par NEXUS ;
- retire les entrées Copilot CLI / Claude Code uniquement lorsqu'elles avaient été ajoutées avec succès par le setup ;
- ne supprime pas `NEXUS_HOME` ;
- ne supprime pas les repositories utilisateur ;
- ne désinstalle pas Docker Desktop/Engine ;
- ne gère aucune authentification Copilot/Claude.

## Générer un candidat Windows local

Prérequis de build :

- Windows x64 ;
- `JAVA_HOME` vers un JDK 21 ou supérieur ;
- réseau la première fois si Inno Setup n'est pas disponible.

Commande complète :

```powershell
Set-ExecutionPolicy -Scope Process Bypass -Force
& .\scripts\release\build-windows-release.ps1
```

Pour une itération de packaging après qualification du code :

```powershell
& .\scripts\release\build-windows-release.ps1 -SkipVerify
```

`-SkipVerify` ne constitue pas une preuve de release finale.

## Construire et tester Docker localement

```powershell
$image = & .\scripts\release\build-docker-image.ps1
& .\scripts\release\test-docker-runtime.ps1 -Image $image -HostPort 18080
```

Le smoke vérifie :

- CLI/version ;
- maintien du serveur MCP STDIO avec stdin ouvert ;
- REST/health sur un port hôte personnalisé.

## Qualification setup

La CI Windows construit une variante smoke non interactive puis exécute :

```powershell
& .\scripts\release\test-windows-installer.ps1 -Setup <smoke-setup.exe>
```

La variante smoke force le mode natif recommandé et désactive les intégrations assistants afin de ne jamais modifier la configuration réelle du runner CI.

## Vérification d'intégrité

```powershell
$actual = (Get-FileHash .\target\dist\NEXUS-0.2.0-windows-x64-setup.exe -Algorithm SHA256).Hash.ToLowerInvariant()
Get-Content .\target\dist\NEXUS-0.2.0-windows-x64-setup.exe.sha256
$actual
```

Les distributions embarquent également :

```text
LICENSE
THIRD_PARTY_NOTICES.txt
SBOM.cdx.json
VERSION
RUNTIME-MODULES.txt
```

## Principes invariants

1. Le mode natif ne dépend jamais de Docker.
2. Le mode natif installé ne dépend jamais d'une JVM système.
3. Docker reste optionnel.
4. MCP reste STDIO tant qu'un besoin explicite ne justifie pas un transport distant.
5. Le port Docker personnalisable concerne REST, pas MCP.
6. `NEXUS_HOME` est persistant et n'est pas supprimé automatiquement.
7. Les configurations MCP existantes ne sont pas écrasées silencieusement.
8. NEXUS ne prend jamais possession de l'authentification Copilot ou Claude.
9. Les repositories montés dans Docker sont en lecture seule par défaut.
10. Une écoute REST exposée doit rester protégée par la politique de token NEXUS.
