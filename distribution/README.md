# NEXUS Context Engine — distribution 0.2.0

NEXUS peut être distribué sans cloner le dépôt et sans Maven sur la machine cible.

Trois modes d'exécution sont supportés :

- ZIP multiplateforme Maven avec Java 21+ système ;
- Windows natif autonome avec runtime Java embarqué ;
- Docker avec CLI, MCP STDIO et REST dans la même image.

Le setup Windows permet de choisir **Natif**, **Docker** ou **Natif + Docker**, de personnaliser les options et d'afficher un récapitulatif final avant installation.

## Licence et conformité

NEXUS Context Engine est un logiciel **propriétaire source-available**. Copyright © 2026 Fabrice Turleque. Tous droits réservés.

Fichiers de conformité inclus :

```text
LICENSE
THIRD_PARTY_NOTICES.txt
SBOM.cdx.json
```

## Windows natif

Le payload x64 embarque un runtime Java `jpackage`. Aucun Java système n'est requis après installation.

Surfaces :

```text
nexus.cmd                    CLI
nexus-mcp.cmd                MCP JSON-RPC / STDIO
nexus-rest.cmd               REST Quarkus
nexus-assistant-clients.cmd  génération d'intégrations assistants
```

`NEXUS_HOME` est stocké hors des fichiers applicatifs et est conservé à la désinstallation.

## Docker

La distribution Docker expose :

```text
rest
cli
mcp
assistant
```

MCP reste en STDIO et ne possède pas de port réseau :

```text
docker exec -i <container> java -jar /opt/nexus/lib/nexus-mcp.jar
```

REST utilise un port configurable. Le Compose généré mappe par défaut :

```text
127.0.0.1:8080 -> container:8080
```

Le port hôte, le port interne, l'image, le nom du conteneur, la restart policy, `NEXUS_HOME` et la racine de repositories sont personnalisables.

Le repository est monté en lecture seule sous `/workspace`.

## Recherche sémantique

La recherche sémantique est désactivée par défaut.

Le setup Windows ne demande plus un nom de provider libre. Il propose :

```text
[ ] Activer Ollama pour la recherche sémantique
```

Si activé :

```text
NEXUS_SEMANTIC_PROVIDER=ollama
```

URL par défaut :

```text
http://127.0.0.1:11434
```

Le setup configure NEXUS pour utiliser Ollama mais **n'installe pas le binaire Ollama**.

## Intégrations assistants

La matrice de distribution est :

- GitHub Copilot CLI ;
- GitHub Copilot JetBrains ;
- Claude CLI / Claude Code ;
- Codex Desktop ;
- client MCP générique.

Une entrée MCP `nexus` existante n'est pas écrasée.

Le runtime MCP peut être :

- natif, via le `java.exe` embarqué ;
- Docker, via `docker exec -i`.

### Copilot CLI

Le setup peut exécuter :

```text
copilot mcp add nexus --tools "*" -- <commande MCP NEXUS>
```

### Copilot JetBrains

Asset généré :

```text
integrations\copilot-jetbrains.mcp.json
```

### Claude CLI

Le setup peut exécuter :

```text
claude mcp add nexus --scope user -- <commande MCP NEXUS>
```

Le profil projet reste disponible via le générateur standalone.

### Codex Desktop

Le setup peut exécuter, si `codex` est détecté :

```text
codex mcp add nexus -- <commande MCP NEXUS>
```

Il génère aussi :

```text
integrations\codex-desktop.mcp.toml
```

avec une section :

```toml
[mcp_servers.nexus]
```

### Générique

Asset généré :

```text
integrations\generic-mcp.json
```

NEXUS ne gère aucune authentification Copilot, Claude ou Codex.

## Récapitulatif avant installation

La page **Ready to Install** doit afficher exactement les choix courants avant le bouton Installer :

- mode et profil ;
- composants natifs ;
- `NEXUS_HOME` ;
- REST ;
- Ollama activé/désactivé + URL ;
- Docker image/conteneur/ports/volumes ;
- cinq intégrations assistants ;
- runtime MCP ;
- PATH et démarrage Docker post-install.

## ZIP Windows autonome

Le ZIP Windows contient les launchers natifs/Docker et les templates nécessaires :

```powershell
.\nexus.cmd --version --json
.\nexus-mcp.cmd
.\nexus-assistant-clients.cmd --help
```

## Distribution multiplateforme Maven

Prérequis : Java 21 ou supérieur.

Windows :

```powershell
.\bin\nexus.cmd --version
```

Linux / macOS :

```bash
sh ./bin/nexus --version
```

## Configuration opérationnelle

Variables principales :

- `NEXUS_HOME` ;
- `NEXUS_MAX_FILE_SIZE_BYTES` ;
- `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS` ;
- `NEXUS_JDTLS_HOME` ;
- `NEXUS_SEMANTIC_PROVIDER` ;
- `NEXUS_OLLAMA_BASE_URL` ;
- `NEXUS_OLLAMA_EMBEDDING_MODEL` ;
- `NEXUS_OLLAMA_EMBEDDING_DIMENSIONS` ;
- `NEXUS_OLLAMA_TIMEOUT_SECONDS` ;
- `NEXUS_REST_API_TOKEN`.

Aucun provider externe ni moteur sémantique n'est activé par défaut.

## Documentation complète

```text
docs/user/windows-installation.md
docs/user/docker-installation.md
docs/user/deployment-wizard-template.md
packaging/windows/nexus-installer.iss.template
packaging/docker/docker-compose.yml.template
```

## Intégrité

Les ZIP et setup Windows possèdent un sidecar `.sha256`. Vérifiez ces checksums avant distribution ou installation.
