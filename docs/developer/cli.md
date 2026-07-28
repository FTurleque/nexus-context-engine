# CLI NEXUS — surface courante

Ce document décrit la CLI **actuelle**. Le contrat historique de l'Itération 4 reste archivé dans [`cli-mvp.md`](cli-mvp.md).

## Commandes

```text
nexus project add <chemin> [nom] [--json]
nexus project list [--json]
nexus index <id-ou-nom> [--rebuild] [--deep-java] [--json]
nexus minos-import <id-ou-nom> < export-minos.json [--json]
nexus search <id-ou-nom> <requête> [--limit N] [--explain] [--json]
nexus context <id-ou-nom> <requête> [--budget N] [--explain] [--json]
nexus inspect <id-ou-nom> [--json]
nexus --help [--json]
nexus --version [--json]
```

`--json` est une option globale. Les succès sont rendus sur `stdout`, les erreurs sur `stderr`, avec des codes de sortie stables :

| Code | Sens |
|---:|---|
| 0 | succès |
| 1 | erreur runtime |
| 2 | erreur d'usage/arguments |

## Build et exécution

```powershell
mvn clean install
```

Le build principal produit notamment :

```text
target/nexus-context-engine-0.1.0-SNAPSHOT.jar
target/nexus-context-engine-0.1.0-SNAPSHOT-cli.jar
```

Exécution directe :

```powershell
java -jar .\target\nexus-context-engine-0.1.0-SNAPSHOT-cli.jar --help
```

Launchers de développement :

```powershell
.\scripts\nexus.ps1 --help
```

```cmd
scripts\nexus.cmd --help
```

La Phase 6 prévoit une distribution versionnée indépendante d'un checkout ; elle n'est pas encore livrée.

## Projet

Enregistrer :

```powershell
.\scripts\nexus.ps1 project add N:\workspace-dev\mon-app mon-app
```

Lister :

```powershell
.\scripts\nexus.ps1 project list --json
```

## Indexation

Chemin normal :

```powershell
.\scripts\nexus.ps1 index mon-app
```

Rebuild :

```powershell
.\scripts\nexus.ps1 index mon-app --rebuild
```

Analyse Java profonde :

```powershell
$env:NEXUS_JDTLS_HOME = 'C:\tools\jdtls'
.\scripts\nexus.ps1 index mon-app --deep-java
```

`--deep-java` exige un `CodeIntelligenceProvider` JDT actif. Il reste opt-in à cause de son coût opérationnel supérieur au chemin JavaParser normal.

Un éventuel `index.scip` à la racine du projet est importé opportunément lors de l'indexation normale.

## MINOS

MINOS est importé explicitement ; `nexus index` ne lance pas MINOS.

```powershell
Get-Content -Raw .\minos-export.json |
    .\scripts\nexus.ps1 minos-import mon-app --json
```

Le payload est lu sur stdin et borné à 128 MiB.

Voir [`minos-code-intelligence.md`](minos-code-intelligence.md).

## Recherche

```powershell
.\scripts\nexus.ps1 search mon-app "service de facturation" --limit 10 --explain
```

JSON :

```powershell
$result = .\scripts\nexus.ps1 search mon-app "service de facturation" --limit 10 --explain --json |
    ConvertFrom-Json
```

Le chemin CLI courant reste mono-projet. La façade Java possède déjà `searchAcrossProjects(...)`; son exposition dans les adaptateurs est planifiée en Phase 6 après correction du top-K fédéré.

## Construction de contexte

```powershell
.\scripts\nexus.ps1 context mon-app "Corriger la facturation" --budget 2000 --explain
```

Le bundle peut contenir code, symboles, tests, documentation, instructions, skills et Git selon la requête et le budget.

Le contexte CLI reste mono-projet. Le `ContextBundle` fédéré est planifié après le hardening de la recherche et de l'indexation.

## Inspection

```powershell
.\scripts\nexus.ps1 inspect mon-app --json
```

La sortie expose les volumes canoniques : fichiers, symboles et relations.

## Architecture actuelle de la CLI

La CLI respecte les frontières métier, mais `NexusCli` instancie encore directement le composition root du moteur.

```text
NexusCli
├── repositories SQLite
├── Lucene
├── analyzers/importers/providers
├── ProjectIndexingService
├── SearchService
└── DefaultContextBuilder
```

REST et MCP utilisent déjà `NexusApplication`. La Phase 6 — Itération 20 prévoit de faire déléguer la CLI à cette même façade afin d'éviter le drift de composition.

## Recherche sémantique

La capacité sémantique est validée dans le cœur via :

```java
NexusApplication.create(paths, SemanticSearchConfiguration.enabled(provider));
```

La CLI ne possède pas encore de contrat stable pour l'activer. Ce n'est pas un oubli documentaire : c'est une limite opérationnelle suivie par I22. Le mode reste désactivé par défaut.

## Validation

Gate principal :

```powershell
mvn clean install
.\scripts\self-smoke.ps1
```

Le self-smoke valide le JAR CLI autonome et le flux projet → index → recherche → contexte.

Les validations spécialisées sont disponibles sous `scripts/validate-iteration-*.ps1`.

## Contrat à préserver

La CLI ne doit pas devenir une seconde implémentation du moteur :

```text
arguments
  ↓
validation / parsing
  ↓
NexusApplication
  ↓
objets métier
  ↓
CliRenderer humain/JSON
```

La migration vers cette composition unique est planifiée ; les formats humain/JSON et codes de sortie doivent rester compatibles ou être versionnés explicitement.
