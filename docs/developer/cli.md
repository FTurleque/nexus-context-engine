# CLI NEXUS — surface 0.2.0

Ce document décrit la CLI Phase 6. Le contrat historique MVP reste dans [`cli-mvp.md`](cli-mvp.md).

## Commandes

```text
nexus project add <chemin> [nom] [--json]
nexus project list [--json]
nexus index <id-ou-nom> [--rebuild] [--deep-java] [--json]
nexus minos-import <id-ou-nom> < export-minos.json [--json]
nexus search <id-ou-nom> <requête> [--limit N] [--explain] [--json]
nexus search-federated <projet1,projet2,...> <requête> [--limit N] [--explain] [--json]
nexus context <id-ou-nom> <requête> [--budget N] [--explain] [--json]
nexus context-federated <projet1,projet2,...> <requête> [--budget N] [--explain] [--json]
nexus inspect <id-ou-nom> [--json]
nexus --help [--json]
nexus --version [--json]
```

`--json` est global. `stdout` porte les résultats, `stderr` les erreurs.

| Code | Sens |
|---:|---|
| 0 | succès |
| 1 | erreur runtime |
| 2 | erreur d'usage |

## Composition

Phase 6 supprime le second composition root historique de la CLI :

```text
arguments
  ↓
NexusCli — parsing / validation
  ↓
NexusApplication
  ↓
repositories / indexing / search / context / federation
  ↓
CliRenderer
```

REST et MCP utilisent la même façade. Les règles READY, providers, ranking et opt-ins sont donc communes.

## Build

```powershell
.\mvnw.cmd clean install
```

Fat JAR :

```powershell
java -jar .\target\nexus-context-engine-0.2.0-cli.jar --help
```

Archive installable :

```text
target/distribution/nexus-context-engine-0.2.0.zip
```

Voir [`release-and-recovery.md`](release-and-recovery.md).

## Projet et indexation

```powershell
java -jar .\target\nexus-context-engine-0.2.0-cli.jar project add N:\workspace-dev\mon-app mon-app
java -jar .\target\nexus-context-engine-0.2.0-cli.jar index mon-app
java -jar .\target\nexus-context-engine-0.2.0-cli.jar index mon-app --rebuild
```

JDT LS reste explicite :

```powershell
$env:NEXUS_JDTLS_HOME = 'C:\tools\jdtls'
java -jar .\target\nexus-context-engine-0.2.0-cli.jar index mon-app --deep-java
```

Hardening Phase 6 :

- une seule indexation active par projet/processus ;
- `NEXUS_MAX_FILE_SIZE_BYTES` limite les fichiers avant hash/lecture ;
- `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS` limite les providers externes ;
- exclusions/providers sont visibles dans les diagnostics d'indexation.

## MINOS

NEXUS ne lance jamais MINOS.

```powershell
Get-Content -Raw .\minos-export.json |
    java -jar .\target\nexus-context-engine-0.2.0-cli.jar minos-import mon-app --json
```

Le payload reste borné à 128 MiB. Les chemins sont validés contre les fichiers canoniques déjà indexés.

## Recherche mono-projet

```powershell
java -jar .\target\nexus-context-engine-0.2.0-cli.jar search mon-app "service de facturation" --limit 10 --explain
```

Le projet doit être `READY`.

## Recherche fédérée

```powershell
java -jar .\target\nexus-context-engine-0.2.0-cli.jar search-federated app-api,app-domain "facturation" --limit 20 --explain
```

La portée accepte noms uniques ou UUID séparés par des virgules. Chaque projet doit être `READY`. NEXUS sur-récupère localement avant diversification globale par `(projectId,path)` pour éviter un top-K artificiellement sous-rempli.

## ContextBundle mono-projet

```powershell
java -jar .\target\nexus-context-engine-0.2.0-cli.jar context mon-app "Corriger la facturation" --budget 2000 --explain
```

## Contexte fédéré

```powershell
java -jar .\target\nexus-context-engine-0.2.0-cli.jar context-federated app-api,app-domain "Corriger la facturation" --budget 4000 --explain
```

Le budget est global. Les quotas projet sont déterministes, les items portent leur provenance, le merge est round-robin et les contenus identiques sont dédupliqués. Instructions, skills et Git restent évalués dans leur projet d'origine.

## Sémantique

Désactivé par défaut. Activation explicite :

```powershell
$env:NEXUS_SEMANTIC_PROVIDER = "ollama"
$env:NEXUS_OLLAMA_EMBEDDING_MODEL = "qwen3-embedding:0.6b"
java -jar .\target\nexus-context-engine-0.2.0-cli.jar index mon-app --rebuild
java -jar .\target\nexus-context-engine-0.2.0-cli.jar search mon-app "adapter une requête au modèle métier"
```

La CLI n'a pas de flag sémantique dédié : l'opt-in commun par environnement évite un comportement différent entre CLI, REST et MCP.

## Qualification

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-phase-6.ps1
```

Ce script inclut le reactor complet et `scripts/self-smoke.ps1` et vérifie également release, checksums, SBOM et archive installable.
