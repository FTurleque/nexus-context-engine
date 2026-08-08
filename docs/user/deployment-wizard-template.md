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
docker_runtime_required_for_docker_execution: true
docker_preinstalled_required: false
docker_desktop_auto_install_if_missing: true
docker_desktop_download_is_runtime: true
docker_desktop_authenticode_verification: true
docker_license_not_auto_accepted: true
mcp_transport: stdio
ready_summary_required: true
external_port_availability_check: true
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
  docker_runtime_required: false
  system_jvm_required_after_install: false

docker:
  docker_runtime_required_at_execution: true
  docker_preinstalled_required: false
  wizard_can_provision_docker_desktop: true

both:
  docker_runtime_required_at_execution: true
  docker_preinstalled_required: false
  wizard_can_provision_docker_desktop: true
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
  availability_check: tcp-bind
  if_busy: "select first available TCP port starting from requested port"
  fallback_scan: "requested..65535, then 1024..requested-1"
  write_resolved_value_back_to_wizard: true
  ready_summary_uses_resolved_value: true

rest_api_token:
  type: secret-string
  default: ""
  required_if: "native_rest == true AND native_rest_host not in [127.0.0.1, localhost]"
  docker_behavior: "generate a local token when Docker is selected and value is blank"
```

La disponibilité du port REST natif n'est testée que si le composant REST natif est réellement activé.

## Écran `semantic-search`

Le provider n'est **pas un champ texte libre**.

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
# NEXUS peut installer Ollama, mais seulement si : sémantique activée + Ollama absent +
# installation automatique choisie + signature Authenticode (CN=Ollama Inc.) vérifiée.
ollama_binary_auto_install_supported: true
ollama_installer_authenticode_verified: true       # signataire attendu : CN=Ollama Inc.
ollama_auto_install_requires_explicit_optin: true
ollama_never_installed_when_semantic_disabled: true
ollama_never_reinstalled_when_present: true
ollama_authentication_managed_by_nexus: false
semantic_enabled_by_default: false
```

## Écran `ollama`

```yaml
id: ollama
show_if: "semantic-search.ollama == true AND profile == custom"
ollama_base_url:
  type: url
  default: "http://127.0.0.1:11434"
  required: true
```

En profil recommandé, cocher Ollama utilise l'URL par défaut sans exposer cet écran avancé.

## Écran `docker-desktop`

Cet écran distingue le **runtime NEXUS Docker** du moteur Docker nécessaire pour l'exécuter.

Condition :

```yaml
id: docker-desktop
show_if: "mode in [docker,both] AND docker_runtime_detected == false"
```

Option :

```yaml
auto_install_docker_desktop:
  type: boolean
  label: "Installer automatiquement Docker Desktop s'il est absent"
  default: true
  required_to_continue_if_runtime_missing: true
```

Contrat de téléchargement :

```yaml
download:
  timing: "PrepareToInstall, après affichage et validation de Ready to Install"
  url: "https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe"
  protocol: https
  temporary_file: true
  embedded_in_nexus_setup: false
```

Contrat de confiance :

```yaml
authenticode:
  required: true
  status: Valid
  signer_cn: "Docker Inc"
  on_failure: abort_docker_desktop_installation
```

Contrat d'installation :

```yaml
install:
  mode: per-user
  backend: wsl-2
  command: "Docker Desktop Installer.exe install --user --backend=wsl-2 --quiet"
  accept_license_flag: false
  first_start_user_acceptance_required: true
  expected_user_install_root: "%LOCALAPPDATA%\\Programs\\DockerDesktop"
  wsl2_may_require_admin_or_restart: true
```

Règles :

```yaml
if_docker_runtime_already_present:
  download_docker_desktop: false
  reinstall_docker_desktop: false

if_auto_install_disabled_and_runtime_missing:
  allow_continue: false

uninstall:
  remove_docker_desktop: false
  remove_wsl: false
```

Après installation de Docker Desktop, NEXUS lance Docker Desktop. L'utilisateur doit accepter les conditions Docker au premier démarrage et terminer, si nécessaire, la préparation WSL 2.

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
  required: true

host_rest_port:
  type: integer
  default: 8080
  min: 1
  max: 65535
  availability_check: tcp-bind
  if_busy: "select first available TCP port starting from requested port"
  fallback_scan: "requested..65535, then 1024..requested-1"
  write_resolved_value_back_to_wizard: true
  ready_summary_uses_resolved_value: true
  must_not_equal_native_rest_port_when_both_are_enabled: true

container_rest_port:
  type: integer
  default: 8080
  min: 1
  max: 65535
  availability_check_on_windows_host: false
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
external_port_resolution:
  probe: "temporary TCP listener on the selected host bind address"
  keep_requested_port_if_available: true
  auto_increment_until_available: true
  wrap_after_65535_to: 1024
  reserve_native_rest_port_for_docker_when_mode_is_both: true
  notify_user_when_port_changes: true
container_port_probe_on_windows_host: false
mcp_port: null
mcp_docker_transport: "docker exec -i <container> java -jar /opt/nexus/lib/nexus-mcp.jar"
```

Le test de disponibilité est effectué uniquement sur le port **externe/hôte**.

### Launcher Docker généré

`nexus-docker-up.cmd` doit :

```yaml
steps:
  - locate docker from PATH
  - locate Docker Desktop per-user CLI if PATH is stale
  - locate Docker Desktop all-users CLI if PATH is stale
  - run docker info
  - start Docker Desktop when engine is not ready
  - wait up to approximately 180 seconds
  - run docker compose up -d
on_engine_not_ready:
  exit_code: 30
  action: "tell user to finish Docker Desktop license/WSL onboarding and retry"
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

```yaml
id: integration-runtime
show_if: "mode == both"
type: single-choice
default: native
choices:
  native:
    command: "<install-dir>\\app\\runtime\\bin\\java.exe"
    args: ["-jar", "<install-dir>\\lib\\nexus-mcp.jar"]
  docker:
    command: docker
    args: ["exec", "-i", "<container_name>", "java", "-jar", "/opt/nexus/lib/nexus-mcp.jar"]
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
  native_rest_port_is_resolved_available_value: true
  add_to_path: required_if_selected
  semantic_search: required
  ollama_url_native: required_if_ollama
  ollama_url_docker: required_if_ollama_and_docker   # host.docker.internal résolu
  ollama_auto_install_plan: required_if_ollama       # détecté / auto-install vérifié / absent
  docker_runtime_state: required_if_docker
  docker_desktop_auto_install_plan: required_if_runtime_missing
  docker_desktop_download_source: required_if_auto_install
  docker_license_acceptance_note: required_if_auto_install
  docker_image: required_if_docker
  docker_container: required_if_docker
  docker_host_and_container_ports: required_if_docker
  docker_host_port_is_resolved_available_value: true
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

Exemple Docker absent :

```text
Docker :
  - Docker Desktop : sera téléchargé depuis desktop.docker.com et installé en mode utilisateur (WSL 2)
  - La licence Docker devra être acceptée par l'utilisateur au premier démarrage.
```

Exemple sémantique :

```text
Recherche sémantique : Ollama ACTIVÉ
  - URL (Natif) : http://127.0.0.1:11434
  - URL (Docker) : http://host.docker.internal:11434
  - Ollama : sera TÉLÉCHARGÉ depuis ollama.com puis INSTALLÉ (signature Authenticode vérifiée) avant NEXUS.
```

Exemple de résolution automatique de port :

```text
Port demandé : 8080
8080 occupé
8081 occupé
8082 libre
=> le wizard remplace la valeur par 8082 et le récapitulatif affiche 8082
```

**Aucun téléchargement Docker Desktop, aucune installation Docker Desktop et aucune modification externe ne doit avoir lieu avant que l'utilisateur confirme cette page.**

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

Docker Desktop n'est pas embarqué dans le payload NEXUS : il est téléchargé à la demande depuis Docker lorsque nécessaire.

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

`NEXUS_DOCKER_HOST_PORT` contient la valeur finale retenue après vérification de disponibilité.

## Intégrations MCP

Native :

```text
copilot mcp add nexus --tools "*" -- "<install>\app\runtime\bin\java.exe" -jar "<install>\lib\nexus-mcp.jar"
claude mcp add --scope user nexus -- "<install>\app\runtime\bin\java.exe" -jar "<install>\lib\nexus-mcp.jar"
codex mcp add nexus -- "<install>\app\runtime\bin\java.exe" -jar "<install>\lib\nexus-mcp.jar"
```

Docker :

```text
copilot mcp add nexus --tools "*" -- docker exec -i <container> java -jar /opt/nexus/lib/nexus-mcp.jar
claude mcp add --scope user nexus -- docker exec -i <container> java -jar /opt/nexus/lib/nexus-mcp.jar
codex mcp add nexus -- docker exec -i <container> java -jar /opt/nexus/lib/nexus-mcp.jar
```

Une entrée `nexus` existante est toujours préservée.

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
uninstall_docker_desktop: false
remove_wsl: false
remove_unmanaged_mcp_entries: false
```

Docker Desktop est un prérequis partagé et reste installé même lorsqu'il a été provisionné par NEXUS.

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
DockerAutoInstall
DockerDesktopInstalledByNexus
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

Les valeurs `NativeRestPort` et `DockerHostPort` persistées correspondent aux ports résolus et effectivement retenus par le wizard.

## Gates obligatoires

```yaml
maven_reactor: required
assistant_generator_claude_cli_test: required
assistant_generator_codex_desktop_test: required
windows_distribution_build: required
inno_setup_compile: required
windows_wizard_runtime_initialization: required
windows_docker_desktop_bootstrap_static_contract: required
windows_docker_desktop_download_disabled_in_smoke: required
windows_external_port_resolution_contract: required
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

Le smoke Windows standard reste volontairement en mode natif afin de ne jamais télécharger ni installer Docker Desktop sur un runner GitHub Actions. Le contrat Docker Desktop est vérifié statiquement et la compilation Inno Setup prouve que le code Pascal Script est valide.

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
