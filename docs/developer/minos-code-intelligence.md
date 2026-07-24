# Intégration MINOS Code Intelligence

Statut : **implémentée — validation inter-dépôt finale en attente**

Suivi : issue #11 / PR #12.

Jalon fournisseur : MINOS M13 (`FTurleque/minos-code-intelligence` issue #37 / PR #38).

## Responsabilités

```text
MINOS
  -> faits de code, symboles, relations, provenance, preuves

NEXUS
  -> index local, recherche, ranking, sélection, budget, ContextBundle
```

L’intégration ne change pas les poids du ranking et ne remplace aucune source contextuelle native de NEXUS.

## Pourquoi un processus local

NEXUS cible Java 21. MINOS impose Java 24. Une dépendance Maven vers le JAR MINOS introduirait donc un couplage de bytecode incompatible avec la baseline NEXUS.

NEXUS consomme à la place le contrat JSON `NexusExportContract` v1 au travers d’un bridge local MINOS. La commande système lancée par NEXUS est fixe :

```text
java -cp integrations/minos/minos-code-intelligence-all.jar \
  com.minos.integration.nexus.NexusExportBridgeMain
```

La racine du projet n’est jamais placée sur la ligne de commande système : NEXUS l’écrit sur stdin et le bridge renvoie le JSON sur stdout.

Le processus reste local ; aucune requête réseau n’est utilisée.

## Activation conventionnelle

L’intégration est **désactivée par défaut**. Elle devient active uniquement si le shaded JAR MINOS existe à cet emplacement :

```text
<NEXUS_HOME>/integrations/minos/minos-code-intelligence-all.jar
```

MINOS utilise un home dédié à l’intégration :

```text
<NEXUS_HOME>/integrations/minos/home
```

Arborescence :

```text
<NEXUS_HOME>/
└─ integrations/
   └─ minos/
      ├─ minos-code-intelligence-all.jar
      └─ home/
```

Aucune variable `NEXUS_MINOS_*` n’est utilisée. Le timeout de transport production est fixé à **20 secondes**.

### Runtime enfant Java 24

NEXUS lance volontairement la commande constante `java`. Lorsque MINOS est installé, le `java` résolu dans l’environnement du processus NEXUS doit donc être un runtime **Java 24** capable d’exécuter le JAR MINOS.

Cela ne change pas la baseline de compilation de NEXUS : la validation du cœur reste effectuée avec Java 21. Dans un IDE ou un service, NEXUS peut être lancé avec son JDK 21 explicite tout en donnant au processus enfant un `PATH` où `java` résout vers Java 24.

## Installation du JAR MINOS

Exemple PowerShell :

```powershell
$minosDir = Join-Path $env:NEXUS_HOME 'integrations\minos'
New-Item -ItemType Directory -Force -Path (Join-Path $minosDir 'home') | Out-Null

Copy-Item `
  N:\workspace-dev\minos-code-intelligence\target\minos-code-intelligence-0.1.0-SNAPSHOT-all.jar `
  (Join-Path $minosDir 'minos-code-intelligence-all.jar')
```

## Préparation MINOS

Le projet doit exister dans le registre MINOS dédié et disposer d’un snapshot actif.

Exemple avec un SCIP existant :

```powershell
$java24 = 'C:\path\to\jdk-24\bin\java.exe'
$minosJar = Join-Path $env:NEXUS_HOME 'integrations\minos\minos-code-intelligence-all.jar'
$minosHome = Join-Path $env:NEXUS_HOME 'integrations\minos\home'

& $java24 "-Dminos.home=$minosHome" -jar $minosJar `
  project add N:\workspace-dev\my-project --name my-project

& $java24 "-Dminos.home=$minosHome" -jar $minosJar `
  index my-project --scip N:\workspace-dev\my-project\index.scip `
  --provider scip-typescript --provider-version 0.4.0
```

NEXUS n’essaie pas de lancer un indexeur SCIP manquant. Il consomme uniquement la connaissance déjà disponible dans MINOS.

## Cycle d’indexation NEXUS

Lors d’une indexation normale :

```text
ProjectScanner
  -> analyses embarquées NEXUS
  -> MinosCodeIndexImporter
  -> ScipCodeIndexImporter
  -> SQLite
  -> Lucene / SearchService
```

MINOS est positionné avant SCIP. SQLite NEXUS déduplique déjà les symboles externes et relations identiques ; un fait fourni par MINOS garde donc `source_provider=minos`, puis SCIP peut compléter les faits absents.

Quand le JAR conventionnel est absent, l’importer MINOS renvoie un snapshot vide. Le remplacement transactionnel existant supprime alors les anciennes lignes `source_provider=minos`, ce qui évite de conserver silencieusement une connaissance périmée.

## Validation du contrat

NEXUS exige :

```text
contractVersion = 1
producer        = MINOS
```

La racine indiquée par MINOS doit être un chemin absolu déjà canonique et être exactement celle du projet NEXUS en cours d’indexation.

Chaque `filePath` exporté doit :

- être relatif ;
- ne contenir aucune remontée `..` après normalisation ;
- désigner un fichier réel sous la racine ;
- rester sous la racine après résolution canonique des liens symboliques.

Un contrat incompatible, une mauvaise racine ou un chemin non sûr n’est jamais transformé en accès fichier arbitraire.

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

Les kinds sans équivalent NEXUS sont ignorés. Aucune équivalence approximative n’est créée.

Tous les symboles importés portent :

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

Les relations comme `DEPENDS_ON`, `RELATED_TEST`, `IMPACT_PATH`, `CENTRALITY`, etc. restent disponibles dans le contrat MINOS mais ne sont pas converties vers une sémantique NEXUS différente.

Pour un fait `FACTUAL` sans confiance explicite, NEXUS applique `1.0`. Les dérivations/heuristiques doivent porter une confiance explicite.

## Sécurité et bornes du processus

NEXUS :

- utilise une commande enfant fixe, sans token issu d’une requête, d’un chemin projet ou d’une variable d’intégration ;
- transmet la racine projet par stdin ;
- utilise un classpath relatif fixe sous le répertoire de travail `NEXUS_HOME` ;
- fixe `MINOS_HOME` à `integrations/minos/home` dans l’environnement enfant ;
- borne le temps d’exécution à 20 secondes en production ;
- redirige stdout/stderr vers des fichiers temporaires pour éviter les blocages de pipes ;
- limite le document JSON transporté à 128 MiB ;
- rejette les codes de sortie MINOS non nuls ;
- ne réinjecte qu’un diagnostic stderr normalisé et borné dans l’exception ;
- nettoie les fichiers temporaires.

## Qualification automatisée

`MinosCodeIndexImporterTest` couvre :

- mode désactivé sans JAR conventionnel ;
- activation par présence du JAR conventionnel ;
- mapping conservateur ;
- refus d’une version inconnue ;
- refus d’une mauvaise racine ;
- exécution réelle de la commande bridge fixe ;
- transport de la racine projet par stdin.

`MinosRealIntegrationTest` est un harness opt-in utilisant un `NEXUS_HOME` déjà préparé avec le vrai JAR MINOS et son snapshot.

Propriétés du harness :

```text
nexus.minos.integration.home
nexus.minos.integration.fixture
```

Le chemin recommandé est le script :

```powershell
.\scripts\validate-minos-integration.ps1 `
  -MinosJar N:\workspace-dev\minos-code-intelligence\target\minos-code-intelligence-0.1.0-SNAPSHOT-all.jar `
  -Java24 C:\path\to\jdk-24\bin\java.exe `
  -Fixture N:\workspace-dev\minos-code-intelligence\fixtures\typescript\typescript-modules
```

Le script :

1. exige `JAVA_HOME` sur Java 21 pour Maven/NEXUS ;
2. vérifie le runtime Java 24 fourni ;
3. crée un `NEXUS_HOME` temporaire ;
4. installe le JAR MINOS au chemin conventionnel ;
5. prépare le `MINOS_HOME` dédié avec la fixture et son SCIP réel ;
6. place Java 24 en tête du `PATH` uniquement pour que le processus enfant fixe `java` l’utilise ;
7. lance `MinosRealIntegrationTest` ;
8. nettoie le sandbox.

Le harness vérifie ensuite :

1. le vrai `NexusExportBridgeMain` MINOS ;
2. l’ingestion dans SQLite NEXUS ;
3. `GreetingPort` avec `sourceProvider=minos` ;
4. une recherche NEXUS qui retourne ce symbole.

Replay attendu :

```text
M13 MINOS->NEXUS: symbols=<n>, relations=<n>, nexus-symbols=<n>, search=<n>
```

## Non-objectifs

Cette intégration ne :

- rend pas MINOS obligatoire ;
- ne change pas le ranking ;
- ne change pas `DefaultContextBuilder` ;
- ne donne pas à MINOS le contrôle du budget de tokens ;
- n’ajoute pas de dépendance `com.minos` ;
- n’ajoute pas d’accès réseau ;
- n’utilise pas MINOS comme source documentaire citée.

Décision : [ADR-0044](../adr/0044-consommer-minos-via-un-contrat-json-local-versionne.md).
