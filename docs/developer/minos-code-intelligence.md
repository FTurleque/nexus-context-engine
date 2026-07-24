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

NEXUS consomme à la place le contrat JSON `NexusExportContract` v1 via :

```text
java 24 -jar <minos-all.jar> nexus-export --root <project>
```

Le processus reste local ; aucune requête réseau n’est utilisée.

## Configuration

### Désactivé par défaut

Sans configuration, NEXUS continue à utiliser ses analyseurs et importers habituels.

### Activation

```powershell
$env:NEXUS_MINOS_JAR = 'N:\workspace-dev\minos-code-intelligence\target\minos-code-intelligence-0.1.0-SNAPSHOT-all.jar'
$env:NEXUS_MINOS_JAVA = 'C:\path\to\jdk-24\bin\java.exe'
```

Optionnel :

```powershell
$env:NEXUS_MINOS_HOME = 'C:\Users\me\.minos'
$env:NEXUS_MINOS_TIMEOUT_SECONDS = '20'
```

`NEXUS_MINOS_JAVA` est volontairement obligatoire dès qu’un JAR est configuré. NEXUS ne tente pas d’exécuter MINOS avec son propre runtime Java 21.

Timeout accepté : `1..300` secondes.

## Préparation MINOS

Le projet doit exister dans le registre MINOS et disposer d’un snapshot actif.

Exemple avec un SCIP existant :

```powershell
java -Dminos.home=$env:NEXUS_MINOS_HOME -jar $env:NEXUS_MINOS_JAR `
  project add N:\workspace-dev\my-project --name my-project

java -Dminos.home=$env:NEXUS_MINOS_HOME -jar $env:NEXUS_MINOS_JAR `
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

Quand l’intégration est désactivée, l’importer MINOS renvoie un snapshot vide. Le remplacement transactionnel existant supprime alors les anciennes lignes `source_provider=minos`, ce qui évite de conserver silencieusement une connaissance périmée.

## Validation du contrat

NEXUS exige :

```text
contractVersion = 1
producer        = MINOS
```

La racine canonique indiquée par MINOS doit être exactement celle du projet NEXUS en cours d’indexation.

Un contrat incompatible ou un export appartenant à un autre projet provoque un échec d’indexation explicite.

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

## Erreurs processus

NEXUS :

- borne le temps d’exécution ;
- redirige stdout/stderr vers des fichiers temporaires pour éviter les blocages de pipes ;
- rejette les codes de sortie MINOS non nuls ;
- ne réinjecte qu’un diagnostic stderr normalisé et borné dans l’exception ;
- nettoie les fichiers temporaires.

## Qualification automatisée

`MinosCodeIndexImporterTest` couvre :

- mode désactivé ;
- obligation de fournir Java 24 ;
- mapping conservateur ;
- refus d’une version inconnue ;
- refus d’une mauvaise racine ;
- exécution réelle d’un JAR synthétique comme processus.

`MinosRealIntegrationTest` est un harness opt-in utilisant le vrai JAR MINOS.

Propriétés :

```text
nexus.minos.integration.jar
nexus.minos.integration.java
nexus.minos.integration.fixture
```

Exemple :

```powershell
mvn -Dtest=MinosRealIntegrationTest `
  -Dnexus.minos.integration.jar=N:\workspace-dev\minos-code-intelligence\target\minos-code-intelligence-0.1.0-SNAPSHOT-all.jar `
  -Dnexus.minos.integration.java=C:\path\to\jdk-24\bin\java.exe `
  -Dnexus.minos.integration.fixture=N:\workspace-dev\minos-code-intelligence\fixtures\typescript\typescript-modules `
  test
```

Le harness :

1. crée un `MINOS_HOME` temporaire ;
2. enregistre la fixture dans MINOS ;
3. importe son SCIP réel ;
4. lance le vrai `nexus-export` ;
5. ingère le snapshot dans SQLite NEXUS ;
6. vérifie `GreetingPort` avec `sourceProvider=minos` ;
7. exécute une recherche NEXUS et vérifie que ce symbole est retourné.

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
