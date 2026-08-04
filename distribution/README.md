# NEXUS Context Engine — distribution 0.2.0

Cette archive contient la CLI NEXUS autonome. Elle ne nécessite pas de cloner le dépôt ni d'installer Maven.

## Prérequis

- Java 21 ou supérieur ;
- accès en lecture/écriture au répertoire configuré par `NEXUS_HOME` (ou au répertoire NEXUS par défaut).

## Windows

```powershell
.\bin\nexus.cmd --version
.\bin\nexus.cmd --help
```

## Linux / macOS

```bash
sh ./bin/nexus --version
sh ./bin/nexus --help
```

## Configuration opérationnelle

Variables principales :

- `NEXUS_HOME` : stockage local NEXUS ;
- `NEXUS_MAX_FILE_SIZE_BYTES` : taille maximale d'un fichier source indexé, 8 MiB par défaut ;
- `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS` : timeout global des providers externes, 180 s par défaut ;
- `NEXUS_JDTLS_HOME` : active explicitement JDT LS pour `--deep-java` ;
- `NEXUS_SEMANTIC_PROVIDER=ollama` : active explicitement la recherche sémantique ;
- `NEXUS_OLLAMA_BASE_URL`, `NEXUS_OLLAMA_EMBEDDING_MODEL`, `NEXUS_OLLAMA_EMBEDDING_DIMENSIONS`, `NEXUS_OLLAMA_TIMEOUT_SECONDS` : configuration Ollama ;
- `NEXUS_SEMANTIC_RRF_WEIGHT` : poids RRF sémantique, limité à 10.

Aucun provider externe ni moteur sémantique n'est activé par défaut.

## Intégrité

Les livrables générés par le build de release sont accompagnés d'un fichier `.sha256`. Vérifiez ce checksum avant distribution ou installation.

Le build produit également un SBOM CycloneDX agrégé dans `target/sbom/`.
