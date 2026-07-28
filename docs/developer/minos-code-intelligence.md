# Intégration MINOS Code Intelligence

Statut : **terminée, validée et livrée le 24 juillet 2026**.

Suivi historique : NEXUS issue #11 / PR #12 ; jalon fournisseur MINOS M13.

## Responsabilités

```text
MINOS
→ faits de code, symboles, relations, provenance et preuves

NEXUS
→ persistance, recherche, ranking, sélection, budget et ContextBundle
```

L'intégration ne transfère ni le ranking ni la construction de contexte vers MINOS.

## Frontière Java 24 / Java 21

MINOS est validé avec Java 24 alors que NEXUS cible Java 21. La frontière retenue évite donc toute dépendance binaire croisée :

```text
MINOS Java 24
  nexus-export --root <project>
        │
        │ JSON stdout
        ▼
NEXUS Java 21
  minos-import <project> < stdin
        │
        ▼
IndexRepository -> SearchService -> ranking -> ContextBuilder
```

NEXUS :

- ne lance jamais MINOS ;
- ne contient aucun `ProcessBuilder` pour cette intégration ;
- ne configure aucun chemin de JAR MINOS ;
- ne dépend d'aucun type `com.minos` ;
- ne requiert aucun réseau.

## Commande NEXUS

```text
nexus minos-import <id-ou-nom> < export-minos.json [--json]
```

La commande :

1. résout un projet NEXUS déjà enregistré ;
2. lit le JSON uniquement sur stdin ;
3. refuse un payload supérieur à 128 MiB ;
4. valide le contrat MINOS ;
5. mappe uniquement les faits représentables ;
6. remplace transactionnellement le snapshot externe `sourceProvider=minos`.

L'indexation normale `nexus index` ne déclenche pas d'import MINOS.

## Contrat

NEXUS exige :

```text
contractVersion = 1
producer        = MINOS
```

La racine exportée doit correspondre à la racine canonique du projet NEXUS ciblé.

### Sécurité des chemins

Les `filePath` du JSON sont non fiables.

Protections actuelles :

- chemin relatif obligatoire ;
- remontée `..` refusée ;
- chemin normalisé présent dans une allow-list locale ;
- aucune ouverture d'un chemin arbitraire fourni par le JSON ;
- racine projet locale considérée comme frontière de confiance.

L'implémentation actuelle construit cette allow-list en parcourant les fichiers physiques sous la racine. Cette stratégie est sûre mais potentiellement coûteuse sur un gros repository contenant `.git`, `node_modules`, `target`, etc. L'Itération 21 doit étudier sa construction depuis la vue canonique NEXUS sans réduire les protections de l'ADR-0044.

## Mapping des symboles

Seuls les symboles `RESOLVED` et représentables sont importés.

```text
MINOS                    NEXUS
CLASS                    CLASS
INTERFACE / TRAIT        INTERFACE
RECORD                   RECORD
ENUM                     ENUM
ANNOTATION               ANNOTATION
METHOD / FUNCTION        METHOD
CONSTRUCTOR              CONSTRUCTOR
TYPE / STRUCT / TYPE_ALIAS TYPE
```

Tous les symboles promus portent :

```text
sourceProvider = minos
```

Les kinds sans équivalent sûr sont ignorés plutôt que remappés arbitrairement.

## Mapping des relations

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

Pour un fait `FACTUAL` sans confiance explicite, NEXUS utilise `1.0`. Une relation dérivée sans confiance explicite est rejetée.

## API applicative

La façade expose :

```java
CodeIntelligenceSnapshot importMinos(UUID projectId, String payload)
```

Cette méthode applique le même adaptateur de contrat et le même remplacement de snapshot que la CLI.

La méthode rend l'intégration réutilisable par un adaptateur, mais **REST et MCP ne publient pas actuellement de tool/endpoint MINOS dédié**. Une exposition éventuelle devra rester explicite et ne pas transformer MINOS en dépendance implicite.

## Qualification finale

Qualification NEXUS finale documentée :

```text
Java                21.0.10 LTS Microsoft
Maven               3.9.11
compile             release 21
sources main        128
sources test        41
tests               80
failures            0
errors              0
skipped             6
BUILD SUCCESS
```

MINOS compagnon a également été qualifié sur son `main` avec Java 24.

Replay réel MINOS → NEXUS :

```text
M13 MINOS->NEXUS: symbols=11, relations=6, nexus-symbols=11, search=5
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
M13 MINOS -> NEXUS replay SUCCESS
```

Le scénario vérifie notamment qu'un symbole importé avec `sourceProvider=minos` est ensuite retrouvé par `SearchService`.

La qualification publiée dans l'issue #11 indique également :

```text
Sonar Quality Gate Passed
0 Security Hotspots
0.0% Duplication on New Code
```

## Tests et replay

`MinosCodeIndexImporterTest` couvre notamment :

- version/producteur ;
- racine projet ;
- chemins absolus/remontants ;
- kinds non représentables ;
- mapping conservateur ;
- provenance.

`NexusCliTest` couvre l'import stdin.

`MinosRealIntegrationTest` couvre le vrai export MINOS puis la persistance/recherche NEXUS.

Runner inter-dépôt :

```powershell
.\scripts\validate-minos-integration.ps1 `
  -MinosJar <minos-all.jar> `
  -Java24 <java-24.exe> `
  -Fixture <fixture>
```

## Non-objectifs

Cette intégration :

- ne rend pas MINOS obligatoire ;
- ne lance pas MINOS ;
- ne modifie pas les poids du ranking ;
- ne modifie pas le budget du `ContextBuilder` ;
- n'ajoute pas de dépendance Maven croisée ;
- n'ajoute pas de réseau.

Décision : [ADR-0044](../adr/0044-consommer-minos-via-un-contrat-json-local-versionne.md).

Limite de performance actuelle : [`current-limitations.md`](current-limitations.md), F12.
