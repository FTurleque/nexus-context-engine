# Template de référence — NEXUS Windows Deployment Wizard

Ce document est le **contrat documentaire complet** du setup Windows. Toute évolution de `packaging/windows/nexus-installer.iss.template` qui ajoute, retire ou renomme un choix utilisateur doit mettre cette template à jour dans la même PR.

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
  custom:
    show_advanced_native_components: true
    show_runtime_advanced: true
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

semantic_provider:
  type: string
  default: ""
  common_values: ["", "ollama"]

ollama_base_url:
  type: url
  default: "http://127.0.0.1:11434"
```

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

Choix :

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

claude_code_user:
  default: true
  action: "connect if CLI detected and nexus entry absent"
  scope: user
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

## Compose de référence

Source de vérité :

```text
packaging/docker/docker-compose.yml.template
```

Contrat :

```yaml
service: nexus
runtime_mode: rest
nexus_home_container: /data/nexus
repository_container: /workspace
repository_read_only: true
health_endpoint: /q/health/live
healthcheck_enabled: true
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

Avant ajout :

```text
copilot mcp get nexus
```

Si l'entrée existe : **ne rien écraser**.

## Intégration Claude Code

Native :

```text
claude mcp add nexus --scope user -- "<install>\app\runtime\bin\java.exe" -jar "<install>\lib\nexus-mcp.jar"
```

Docker :

```text
claude mcp add nexus --scope user -- docker exec -i <container> java -jar /opt/nexus/lib/nexus-mcp.jar
```

Avant ajout :

```text
claude mcp get nexus
```

Si l'entrée existe : **ne rien écraser**.

## Désinstallation

```yaml
remove_application_files: true
remove_managed_path_entry: true
stop_managed_docker_compose: true
remove_managed_copilot_cli_entry: true
remove_managed_claude_user_entry: true
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
ClaudeUserManaged
```

## Gates obligatoires

```yaml
maven_reactor: required
windows_distribution_build: required
inno_setup_compile: required
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
scripts/release/build-docker-image.ps1
scripts/release/test-docker-runtime.ps1
adapters/assistant-clients/**
```

Cette template n'est pas un pseudo-design futur : elle décrit le contrat que l'implémentation de la branche doit satisfaire.
