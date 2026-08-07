# Template de référence — NEXUS Windows Deployment Wizard

Ce document est le **contrat documentaire complet** du setup Windows. Toute évolution de `packaging/windows/nexus-installer.iss.template` qui ajoute, retire ou renomme un choix utilisateur doit mettre ce document à jour dans la même PR.

## Métadonnées

```yaml
product: NEXUS Context Engine
version: 0.2.0
platform: windows-x64
installer: Inno Setup
privileges: current-user
install_dir_default: "%LOCALAPPDATA%\\Programs\\NEXUS"
data_dir_default: "%USERPROFILE%\\.nexus"
native_java_system_required: false
docker_required_for_native: false
mcp_transport: stdio
ready_summary_required: true
```

## Écran `deployment-mode`

```yaml
id: deployment-mode
type: single-choice
required: true
default: native
choices:
  native:
    label: "Natif Windows"
    installs_native: true
    installs_docker: false
  docker:
    label: "Docker"
    installs_native: false
    installs_docker: true
  both:
    label: "Natif + Docker"
    installs_native: true
    installs_docker: true
```

Règles :

```yaml
native:
  docker_cli_required: false
  system_jvm_required_after_install: false
docker:
  docker_cli_required: true
  docker_engine_required: true
both:
  docker_cli_required: true
  docker_engine_required: true
```

## Écran `profile`

```yaml
id: profile
type: single-choice
required: true
default: recommended
choices:
  recommended:
    show_advanced_native_components: false
    show_runtime_advanced: false
    show_ollama_url_advanced: false
  custom:
    show_advanced_native_components: true
    show_runtime_advanced: true
    show_ollama_url_advanced: true
```

## Écran `native-components`

Condition :

```yaml
show_if: "mode in [native,both] AND profile == custom"
```

Valeurs :

```yaml
native_cli:
  type: boolean
  default: true
native_mcp_stdio:
  type: boolean
  default: true
native_rest:
  type: boolean
  default: false
validation:
  at_least_one: [native_cli, native_mcp_stdio, native_rest]
```

## Écran `runtime`

Condition :

```yaml
show_if: "profile == custom"
```

Valeurs :

```yaml
nexus_home:
  type: path
  default: "%USERPROFILE%\\.nexus"
  required: true
  preserve_on_uninstall: true

native_rest_host:
  type: string
  default: "127.0.0.1"

native_rest_port:
  type: integer
  default: 8080
  min: 1
  max: 65535

rest_api_token:
  type: secret-string
  default: ""
  required_if: "native_rest == true AND native_rest_host not in [127.0.0.1, localhost]"
  docker_behavior: "generate a local token when Docker is selected and value is blank"
```

## Écran `semantic-search`

Le provider n'est **plus un champ texte libre**.

```yaml
id: semantic-search
type: multi-choice
required: false
show_always: true
choices:
  ollama:
    label: "Activer Ollama pour la recherche sémantique"
    default: false
    when_checked:
      NEXUS_SEMANTIC_PROVIDER: ollama
    when_unchecked:
      NEXUS_SEMANTIC_PROVIDER: ""
```

Contrat :

```yaml
ollama_binary_installed_by_nexus_setup: false
ollama_authentication_managed_by_nexus: false
semantic_enabled_by_default: false
```

Le setup configure l'utilisation d'une instance Ollama accessible ; il ne doit pas prétendre installer le binaire Ollama.

## Écran `ollama`

Condition :

```yaml
show_if: "semantic-search.ollama == true AND profile == custom"
```

```yaml
ollama_base_url:
  type: url
  default: "http://127.0.0.1:11434"
  required: true
```

En profil recommandé, cocher Ollama utilise l'URL par défaut sans exposer cet écran avancé.

## Écran `docker`

Condition :

```yaml
show_if: "mode in [docker,both]"
```

Valeurs :

```yaml
image:
  type: string
  default: "ghcr.io/fturleque/nexus-context-engine:0.2.0"
  required: true

container_name:
  type: string
  default: "nexus"
  required: true

host_bind_address:
  type: string
  default: "127.0.0.1"

host_rest_port:
  type: integer
  default: 8080
  min: 1
  max: 65535

container_rest_port:
  type: integer
  default: 8080
  min: 1
  max: 65535
  guidance: "prefer changing host_rest_port only"

repository_bind:
  type: path
  default: "%USERPROFILE%"
  required: true
  container_path: "/workspace"
  access: read-only

nexus_home_bind:
  source: "runtime.nexus_home"
  container_path: "/data/nexus"
  access: read-write

restart_policy:
  type: string
  default: "unless-stopped"
```

Port contract :

```yaml
rest_mapping: "${host_bind_address}:${host_rest_port}:${container_rest_port}"
mcp_port: null
mcp_docker_transport: "docker exec -i <container> java -jar /opt/nexus/lib/nexus-mcp.jar"
```

## Écran `assistants`

Condition :

```yaml
show_if: "native_mcp_stdio == true OR mode in [docker,both]"
```

La même matrice doit apparaître dans le wizard, la documentation et le générateur standalone.

```yaml
copilot_cli:
  default: true
  action: "connect if CLI detected and nexus entry absent"
  existing_entry_policy: preserve

copilot_jetbrains:
  default: true
  action: "generate integrations/copilot-jetbrains.mcp.json"
  schema_root: servers
  auto_modify_ide_config: false

claude_cli:
  label: "Claude CLI / Claude Code"
  default: true
  action: "connect if CLI detected and nexus entry absent"
  command: "claude mcp add --scope user nexus -- <nexus-mcp-command>"
  scope: user
  existing_entry_policy: preserve
  generator_project_profile_retained: true

codex_desktop:
  default: true
  action:
    - "connect through codex CLI when detected and nexus entry absent"
    - "generate integrations/codex-desktop.mcp.toml"
  command: "codex mcp add nexus -- <nexus-mcp-command>"
  config_snippet_root: "[mcp_servers.nexus]"
  existing_entry_policy: preserve

generic_mcp:
  default: true
  action: "generate integrations/generic-mcp.json"
  schema_root: mcpServers
```

Authentication contract :

```yaml
manage_copilot_authentication: false
manage_claude_authentication: false
manage_codex_authentication: false
store_external_tokens: false
```

## Écran `integration-runtime`

Condition :

```yaml
show_if: "mode == both"
```

```yaml
type: single-choice
default: native
choices:
  native:
    command: "<install-dir>\\app\\runtime\\bin\\java.exe"
    args:
      - "-jar"
      - "<install-dir>\\lib\\nexus-mcp.jar"
  docker:
    command: "docker"
    args:
      - "exec"
      - "-i"
      - "<container_name>"
      - "java"
      - "-jar"
      - "/opt/nexus/lib/nexus-mcp.jar"
```

## Page standard `ready-summary`

La page Inno Setup **Ready to Install** est obligatoire et constitue la dernière confirmation avant l'installation.

Le setup surcharge `UpdateReadyMemo` et doit afficher au minimum :

```yaml
summary:
  deployment_mode: required
  profile: required
  install_directory: required
  nexus_home: required
  native_components: required_if_native
  native_rest_host_port: required_if_native_rest
  add_to_path: required_if_selected
  semantic_search: required
  ollama_url: required_if_ollama
  ollama_binary_install_note: required_if_ollama
  docker_image: required_if_docker
  docker_container: required_if_docker
  docker_host_and_container_ports: required_if_docker
  docker_repository: required_if_docker
  docker_restart_policy: required_if_docker
  start_docker_after_install: required_if_selected
  assistants:
    - copilot_cli
    - copilot_jetbrains
    - claude_cli
    - codex_desktop
    - generic_mcp
  integration_runtime: required_if_any_assistant
```

Exemple sémantique :

```text
Recherche sémantique : Ollama ACTIVÉ
  - URL : http://127.0.0.1:11434
  - Le setup configure NEXUS mais n'installe pas le binaire Ollama.
```

Aucun fichier ni configuration externe ne doit être modifié avant que l'utilisateur confirme cette page et lance réellement l'installation.

## Tâches Windows

```yaml
add_to_user_path:
  show_if: installs_native
  default: checked-once
  managed_registry_value: "HKCU\\Software\\FTurleque\\NEXUS\\ManagedPath"

start_docker_after_install:
  show_if: installs_docker
  default: checked-once
  command: "nexus-docker-up.cmd"
```

## Payload natif attendu

```text
app\nexus.exe
app\runtime\bin\java.exe
lib\nexus-cli.jar
lib\nexus-mcp.jar
lib\nexus-assistant-clients.jar
rest\quarkus-run.jar
nexus.cmd
nexus-mcp.cmd
nexus-rest.cmd
nexus-assistant-clients.cmd
```

## Payload Docker attendu

```text
nexus-docker.cmd
nexus-docker-mcp.cmd
docker\docker-compose.yml.template
```

Après configuration :

```text
docker\.env
docker\docker-compose.yml
nexus-docker-up.cmd
nexus-docker-down.cmd
```

## Assets assistants générés

Selon les choix :

```text
integrations\connect-copilot-cli.cmd
integrations\copilot-jetbrains.mcp.json
integrations\connect-claude-cli.cmd
integrations\connect-codex-desktop.cmd
integrations\codex-desktop.mcp.toml
integrations\generic-mcp.json
```

Les scripts de connexion utilisent le runtime MCP choisi (Java embarqué ou `docker exec -i`).

## `.env` Docker généré

```dotenv
NEXUS_DOCKER_IMAGE=ghcr.io/fturleque/nexus-context-engine:0.2.0
NEXUS_DOCKER_CONTAINER=nexus
NEXUS_DOCKER_RESTART_POLICY=unless-stopped
NEXUS_DOCKER_BIND_ADDRESS=127.0.0.1
NEXUS_DOCKER_HOST_PORT=8080
NEXUS_DOCKER_CONTAINER_PORT=8080
NEXUS_HOME_BIND=C:/Users/<user>/.nexus
NEXUS_REPOSITORY_BIND=N:/workspace-dev
NEXUS_SEMANTIC_PROVIDER=
NEXUS_OLLAMA_BASE_URL=http://127.0.0.1:11434
NEXUS_REST_API_TOKEN=<generated-or-user-value>
```

Avec Ollama activé :

```dotenv
NEXUS_SEMANTIC_PROVIDER=ollama
```

## Intégration Copilot CLI

Native :

```text
copilot mcp add nexus --tools "*" -- "<install>\app\runtime\bin\java.exe" -jar "<install>\lib\nexus-mcp.jar"
```

Docker :

```text
copilot mcp add nexus --tools "*" -- docker exec -i <container> java -jar /opt/nexus/lib/nexus-mcp.jar
```

Une entrée existante est préservée.

## Intégration Claude CLI

Native :

```text
claude mcp add --scope user nexus -- "<install>\app\runtime\bin\java.exe" -jar "<install>\lib\nexus-mcp.jar"
```

Docker :

```text
claude mcp add --scope user nexus -- docker exec -i <container> java -jar /opt/nexus/lib/nexus-mcp.jar
```

Le profil projet reste disponible dans `nexus-assistant-clients`.

## Intégration Codex Desktop

Lorsque `codex` est disponible :

```text
codex mcp add nexus -- <commande MCP NEXUS>
```

Un snippet est également généré :

```toml
[mcp_servers.nexus]
command = "<java.exe ou docker>"
args = ["..."]
```

Fichier :

```text
integrations\codex-desktop.mcp.toml
```

Le setup ne modifie pas directement un fichier Codex arbitraire si la CLI n'est pas détectée ; le snippet reste disponible pour import/copie manuelle.

## Désinstallation

```yaml
remove_application_files: true
remove_managed_path_entry: true
stop_managed_docker_compose: true
remove_managed_copilot_cli_entry: true
remove_managed_claude_cli_entry: true
remove_managed_codex_entry: true
remove_nexus_home: false
remove_user_repositories: false
uninstall_docker_engine: false
remove_unmanaged_mcp_entries: false
```

## Persistance de reconfiguration

Clé :

```text
HKCU\Software\FTurleque\NEXUS
```

Valeurs de référence :

```text
DeploymentMode
ManagedPath
NexusHome
NativeRestHost
NativeRestPort
RestApiToken
SemanticProvider
OllamaBaseUrl
DockerImage
DockerContainer
DockerBindAddress
DockerHostPort
DockerContainerPort
DockerRepository
DockerRestartPolicy
DockerManaged
CopilotCliManaged
ClaudeCliManaged
CodexManaged
```

## Gates obligatoires

```yaml
maven_reactor: required
assistant_generator_claude_cli_test: required
assistant_generator_codex_desktop_test: required
windows_distribution_build: required
inno_setup_compile: required
windows_wizard_runtime_initialization: required
windows_ready_summary_contract: required
windows_smoke_install: required
windows_cli_smoke: required
windows_assistant_payload_smoke: required
windows_uninstall_smoke: required
docker_image_build: required
docker_cli_smoke: required
docker_mcp_stdio_process_smoke: required
docker_rest_custom_port_smoke: required
codeql: required
osv: required
```

## Règles de synchronisation

Une PR qui modifie l'un des fichiers suivants doit vérifier cette template :

```text
packaging/windows/nexus-installer.iss.template
packaging/docker/docker-compose.yml.template
scripts/release/build-windows-distribution.ps1
scripts/release/build-windows-installer.ps1
scripts/release/test-windows-installer.ps1
scripts/release/build-docker-image.ps1
scripts/release/test-docker-runtime.ps1
adapters/assistant-clients/**
```

Cette template n'est pas un pseudo-design futur : elle décrit le contrat que l'implémentation de la branche doit satisfaire.
