# Intégration MINOS Code Intelligence

Statut : **implémentée — validation inter-dépôt finale en attente**

Suivi : issue #11 / PR #12.

Jalon fournisseur : MINOS M13 (`FTurleque/minos-code-intelligence` issue #37 / PR #38).

## Responsabilités

```text
MINOS
  -> faits de code, symboles, relations, provenance, preuves

NEXUS
  -> persistance, recherche, ranking, sélection, budget, ContextBundle
```

L’intégration ne change ni les poids du ranking ni `DefaultContextBuilder`.

## Frontière Java 24 / Java 21

NEXUS cible Java 21. MINOS impose Java 24. Une dépendance Maven vers MINOS créerait donc un couplage de bytecode incompatible.

M13 utilise un échange JSON explicite :

```text
MINOS Java 24
  nexus-export --root <project>
        |
        | JSON stdout
        v
NEXUS Java 21
  minos-import <project> < stdin
        |
        v
  SQLite -> SearchService -> ranking -> ContextBuilder
```

NEXUS **ne lance jamais MINOS**. Il n’existe ni `ProcessBuilder`, ni chemin de JAR MINOS, ni runtime Java 24 configuré dans le cœur NEXUS.

## Utilisation

Le projet doit d’abord être connu des deux moteurs et disposer d’un snapshot actif côté MINOS.

Exemple PowerShell :

```powershell
$java24 = 'C:\path\to\jdk-24\bin\java.exe'
$minosJar = 'N:\workspace-dev\minos-code-intelligence\target\minos-code-intelligence-0.1.0-SNAPSHOT-all.jar'
$minosHome = 'N:\workspace-dev\.minos-m13'
$project = 'N:\workspace-dev\my-project'
$export = 'N:\workspace-dev\minos-export.json'

& $java24 "-Dminos.home=$minosHome" -jar $minosJar `
  nexus-export --root $project | Set-Content -Encoding utf8 $export

Get-Content -Raw $export | nexus minos-import my-project
```

Le transport reste local et l’orchestration appartient au shell, à l’IDE, à JARVIS ou à un autre consommateur — pas au cœur NEXUS.

## Commande NEXUS

```text
nexus minos-import <id-ou-nom> < export-minos.json [--json]
```

La commande :

1. résout un projet NEXUS déjà enregistré ;
2. lit le JSON uniquement sur stdin ;
3. refuse un payload supérieur à **128 MiB** ;
4. valide le contrat MINOS ;
5. mappe uniquement les faits représentables ;
6. remplace transactionnellement le snapshot externe `sourceProvider=minos`.

L’indexation normale `nexus index` ne déclenche aucun import MINOS. Elle continue à utiliser les analyseurs/importers NEXUS existants, notamment SCIP.

## Contrat

NEXUS exige :

```text
contractVersion = 1
producer        = MINOS
```

La racine indiquée dans le document doit correspondre exactement à la racine canonique du projet NEXUS ciblé.

### Sécurité des chemins

Les `filePath` du JSON sont considérés comme non fiables.

NEXUS construit d’abord une allow-list à partir des fichiers réellement présents sous la racine locale de confiance. Ensuite, pour chaque `filePath` MINOS :

- le chemin doit être relatif ;
- toute remontée `..` est refusée ;
- le chemin normalisé doit appartenir à l’allow-list locale ;
- le chemin provenant du JSON n’est jamais passé à `Files.*`, `toRealPath()` ou une autre API d’I/O.

Les chemins absolus, remontants, inconnus ou extérieurs sont donc ignorés sans créer d’accès fichier piloté par l’entrée JSON.

## Mapping symboles

Seuls les symboles `RESOLVED` sont importés.

```text
MINOS                         NEXUS
CLASS                         CLASS
INTERFACE / TRAIT             INTERFACE
RECORD                        RECORD
ENUM                          ENUM
ANNOTATION                    ANNOTATION
METHOD / FUNCTION             METHOD
CONSTRUCTOR                   CONSTRUCTOR
STRUCT / TYPE_ALIAS           TYPE
```

Les kinds sans équivalent sont ignorés. Tous les symboles promus portent :

```text
sourceProvider = minos
```

## Mapping relations

Seules les relations `RESOLVED` sont importées.

```text
MINOS             NEXUS
IMPORTS           IMPORTS
EXTENDS           EXTENDS
IMPLEMENTS        IMPLEMENTS
CALLS             CALLS
REFERENCES        REFERENCES
TYPE_DEFINITION   TYPE_DEFINITION
DEFINITION        DEFINITION_OF
```

Les relations non représentables (`DEPENDS_ON`, `RELATED_TEST`, `IMPACT_PATH`, `CENTRALITY`, etc.) ne sont pas remappées arbitrairement.

Pour un fait `FACTUAL` sans confiance explicite, NEXUS applique `1.0`. Une dérivation sans confiance explicite est rejetée.

## API applicative

La façade NEXUS expose également :

```java
CodeIntelligenceSnapshot importMinos(UUID projectId, String payload)
```

Cette méthode applique la même validation et le même remplacement transactionnel que la CLI. Elle permet aux adaptateurs REST/MCP ou à JARVIS de fournir le payload sans passer par la CLI.

## Qualification automatisée

`MinosCodeIndexImporterTest` couvre :

- stabilité de `sourceProvider=minos` ;
- mapping conservateur ;
- refus d’une version inconnue ;
- refus d’une mauvaise racine ;
- rejet des chemins absolus et remontants ;
- ignorance des kinds non représentables.

`NexusCliTest` couvre l’import JSON via stdin dans le flux CLI stable.

`MinosRealIntegrationTest` consomme le vrai export MINOS préparé par le script inter-dépôt et vérifie :

1. `GreetingPort` dans le snapshot parsé ;
2. `GreetingPort` persisté dans SQLite avec `sourceProvider=minos` ;
3. une recherche NEXUS qui retourne ce symbole.

## Replay réel

```powershell
.\scripts\validate-minos-integration.ps1 `
  -MinosJar N:\workspace-dev\minos-code-intelligence\target\minos-code-intelligence-0.1.0-SNAPSHOT-all.jar `
  -Java24 C:\path\to\jdk-24\bin\java.exe `
  -Fixture N:\workspace-dev\minos-code-intelligence\fixtures\typescript\typescript-modules
```

Le script :

1. exige `JAVA_HOME` sur Java 21 pour Maven/NEXUS ;
2. vérifie le runtime Java 24 fourni ;
3. prépare `target/m13-replay` ;
4. copie la fixture ;
5. initialise un `MINOS_HOME` temporaire ;
6. exécute MINOS Java 24 pour enregistrer/indexer la fixture ;
7. exécute `nexus-export` et écrit `minos-export.json` ;
8. lance `MinosRealIntegrationTest` sous Maven/Java 21 ;
9. nettoie le sandbox.

Replay attendu :

```text
M13 MINOS->NEXUS: symbols=<n>, relations=<n>, nexus-symbols=<n>, search=<n>
M13 MINOS -> NEXUS replay SUCCESS
```

## Non-objectifs

Cette intégration ne :

- rend pas MINOS obligatoire ;
- ne lance pas MINOS depuis NEXUS ;
- ne change pas le ranking ;
- ne change pas `DefaultContextBuilder` ;
- ne donne pas à MINOS le contrôle du budget de tokens ;
- n’ajoute pas de dépendance `com.minos` ;
- n’ajoute pas d’accès réseau.

Décision : [ADR-0044](../adr/0044-consommer-minos-via-un-contrat-json-local-versionne.md).
