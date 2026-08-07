# NEXUS Context Engine — distribution 0.2.0

NEXUS peut être distribué sans cloner le dépôt et sans Maven sur la machine cible.

Trois modes d'exécution sont supportés par la stratégie de distribution :

- ZIP multiplateforme Maven avec Java 21+ système ;
- Windows natif autonome avec runtime Java embarqué ;
- Docker avec CLI, MCP STDIO et REST dans la même image.

Le setup Windows permet de choisir **Natif**, **Docker** ou **Natif + Docker** et de personnaliser les options de déploiement.

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

Le setup peut ajouter le répertoire NEXUS au PATH utilisateur.

`NEXUS_HOME` est stocké hors des fichiers applicatifs et est conservé à la désinstallation.

## Docker

La distribution Docker expose les modes :

```text
rest
cli
mcp
assistant
```

MCP reste en STDIO et ne possède pas de port réseau. Depuis un client installé sur l'hôte :

```text
docker exec -i <container> java -jar /opt/nexus/lib/nexus-mcp.jar
```

REST utilise un port configurable. Le Compose généré par le setup mappe par défaut :

```text
127.0.0.1:8080 -> container:8080
```

Le port hôte, le port interne, l'image, le nom du conteneur, la restart policy, `NEXUS_HOME` et la racine de repositories sont personnalisables.

Le repository monté par le template Compose est en lecture seule sous `/workspace`.

## Intégrations assistants

Le setup sait préparer :

- GitHub Copilot CLI ;
- GitHub Copilot JetBrains ;
- Claude Code scope utilisateur ;
- JSON MCP générique.

Une entrée MCP `nexus` existante n'est pas écrasée.

Le runtime des intégrations peut être :

- natif, via le `java.exe` embarqué ;
- Docker, via `docker exec -i`.

NEXUS ne gère aucune authentification Copilot/Claude.

## ZIP Windows autonome

Le ZIP Windows contient également les launchers natifs/Docker et les templates nécessaires :

```powershell
.\nexus.cmd --version --json
.\nexus-mcp.cmd
.\nexus-assistant-clients.cmd --help
```

Le launcher REST est présent, mais l'utilisateur doit fournir les variables de configuration souhaitées lorsqu'il utilise directement le ZIP sans passer par l'assistant d'installation.

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

Dans le repository :

```text
docs/user/windows-installation.md
docs/user/docker-installation.md
docs/user/deployment-wizard-template.md
```

Template Inno Setup :

```text
packaging/windows/nexus-installer.iss.template
```

Template Compose :

```text
packaging/docker/docker-compose.yml.template
```

## Intégrité

Les ZIP et setup Windows possèdent un sidecar `.sha256`. Vérifiez ces checksums avant distribution ou installation.
