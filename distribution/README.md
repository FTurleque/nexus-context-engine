# NEXUS Context Engine — distribution 0.2.0

NEXUS peut être distribué sans cloner le dépôt et sans Maven sur la machine cible.

Deux familles de livrables existent :

- le ZIP multiplateforme Maven `nexus-context-engine-0.2.0.zip`, qui utilise le Java 21+ installé sur la machine ;
- le ZIP Windows x64 et le setup `.exe` produits par `scripts/release/build-windows-release.ps1`, qui embarquent leur propre runtime Java.

## Licence et conformité

NEXUS Context Engine est un logiciel **propriétaire source-available**. Copyright © 2026 Fabrice Turleque. Tous droits réservés.

Les conditions complètes applicables à NEXUS sont fournies dans `LICENSE`. Les composants tiers restent soumis à leurs propres licences.

Fichiers de conformité inclus dans les distributions :

```text
LICENSE
THIRD_PARTY_NOTICES.txt
SBOM.cdx.json
```

Le build échoue si une dépendance compile/runtime distribuée ne fournit pas d'information de licence exploitable.

## Windows — setup EXE ou ZIP autonome

Le livrable Windows x64 embarque un runtime Java construit avec `jpackage`. Aucun Java système n'est requis pour l'exécution.

Après installation par le setup :

```powershell
nexus --version
nexus --help
```

L'installateur peut ajouter le répertoire NEXUS au `PATH` de l'utilisateur. La désinstallation retire uniquement l'entrée PATH qu'elle a elle-même gérée et supprime les fichiers installés.

Les données de `NEXUS_HOME` sont stockées hors du répertoire d'installation et sont volontairement conservées lors d'une désinstallation/réinstallation.

Dans le ZIP Windows autonome :

```powershell
.\nexus.cmd --version --json
.\nexus.cmd --help
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

- `NEXUS_HOME` : stockage local NEXUS ;
- `NEXUS_MAX_FILE_SIZE_BYTES` : taille maximale d'un fichier source indexé, 8 MiB par défaut ;
- `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS` : timeout global des providers externes, 180 s par défaut ;
- `NEXUS_JDTLS_HOME` : active explicitement JDT LS pour `--deep-java` ;
- `NEXUS_SEMANTIC_PROVIDER=ollama` : active explicitement la recherche sémantique ;
- `NEXUS_OLLAMA_BASE_URL`, `NEXUS_OLLAMA_EMBEDDING_MODEL`, `NEXUS_OLLAMA_EMBEDDING_DIMENSIONS`, `NEXUS_OLLAMA_TIMEOUT_SECONDS` : configuration Ollama ;
- `NEXUS_SEMANTIC_RRF_WEIGHT` : poids RRF sémantique, limité à 10.

Aucun provider externe ni moteur sémantique n'est activé par défaut.

## Intégrité

Les ZIP et setup Windows générés par les scripts de release possèdent un sidecar `.sha256`. Vérifiez ces checksums avant distribution ou installation.

Le SBOM CycloneDX et les notices tierces sont embarqués afin que chaque distribution conserve son inventaire de conformité.
