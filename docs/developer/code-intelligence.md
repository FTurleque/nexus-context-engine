# Intelligence de code externe et SCIP

Ce chapitre décrit l'implémentation initiale de l'Itération 8 : enrichir l'index structurel NEXUS avec un index de code externe sans rendre cet index obligatoire.

## 1. Principe général

JavaParser reste l'analyseur embarqué de base.

```text
Repository local
      │
      ├── fichiers .java/.md
      │       │
      │       ▼
      │  LanguageAnalyzer
      │       │
      │       └── JavaParser / Markdown
      │
      └── index.scip optionnel
              │
              ▼
       CodeIndexImporter
              │
              ▼
   CodeIntelligenceSnapshot
              │
              ▼
        SQLite canonique
```

L'absence de `index.scip` ne modifie pas le comportement historique de NEXUS.

## 2. Les contrats

### `CodeIndexImporter`

Un importer transforme un format déjà produit par un outil externe vers le modèle NEXUS :

```java
public interface CodeIndexImporter {
    String sourceProvider();
    Optional<CodeIntelligenceSnapshot> importIndex(Path projectRoot) throws IOException;
}
```

Le premier importer est `ScipCodeIndexImporter`.

### `CodeIntelligenceProvider`

Ce port prépare les providers capables de produire directement une intelligence sémantique :

```java
public interface CodeIntelligenceProvider {
    String sourceProvider();
    CodeIntelligenceSnapshot analyze(Path projectRoot) throws IOException;
}
```

Il n'est pas encore activé par la CLI pendant cette itération. Il permettra par exemple d'ajouter ultérieurement un provider JDT sans modifier les consommateurs.

### `CodeIntelligenceSnapshot`

Le snapshot normalise les données d'un provider :

```text
CodeIntelligenceSnapshot
├── sourceProvider
├── IndexedSymbol[]
└── IndexedRelation[]
```

Chaque symbole et relation conserve la même provenance que son snapshot.

## 3. Activation de SCIP

La CLI construit `ProjectIndexingService` avec :

```java
List.of(new ScipCodeIndexImporter())
```

À chaque indexation :

1. les fichiers locaux sont scannés ;
2. les fichiers nouveaux ou modifiés sont analysés par JavaParser/Markdown ;
3. SQLite reçoit les changements locaux ;
4. chaque `CodeIndexImporter` est rafraîchi ;
5. l'index Lucene dérivé est mis à jour ;
6. le projet passe à `READY`.

Pour SCIP, l'importeur cherche uniquement :

```text
<projectRoot>/index.scip
```

S'il n'existe pas, le snapshot SCIP précédent est purgé et l'indexation continue sans erreur.

## 4. Générer `index.scip`

NEXUS ne lance pas `scip-java` lui-même.

Lorsqu'un projet utilise `scip-java`, l'index doit être généré séparément à la racine du projet selon la procédure fournie par l'indexeur. Le flux attendu côté NEXUS est ensuite simplement :

```powershell
nexus index mon-projet
```

ou avec Maven pendant le développement de NEXUS :

```powershell
mvn -q exec:java "-Dexec.args=index mon-projet"
```

Si `index.scip` est présent, il est consommé. Sinon, JavaParser reste la seule source d'intelligence de code.

## 5. Mapping SCIP vers le modèle NEXUS

### Symboles

Les `Document.symbols` possédant une occurrence de définition sont convertis en `CodeSymbol` uniquement lorsque leur kind possède un équivalent fiable dans le modèle NEXUS.

Le mapping initial couvre principalement les catégories nécessaires aux projets JVM :

| SCIP | NEXUS |
|---|---|
| Class | `CLASS` |
| Constructor | `CONSTRUCTOR` |
| Enum | `ENUM` |
| Interface / Protocol / Trait / TypeClass | `INTERFACE` |
| Method / Function et variantes de méthodes | `METHOD` |

Les kinds qui représentent des champs, variables, paramètres ou catégories sans équivalent fiable ne sont pas transformés artificiellement en `TYPE`. Leurs occurrences et relations restent toutefois importées, ce qui préserve l'intelligence de référence sans polluer la recherche de symboles.

L'identifiant SCIP est conservé comme `qualifiedName` lorsqu'il constitue l'identité la plus sûre. `display_name` alimente `name` lorsqu'il est disponible.

### Relations

Le mapping initial utilise :

- occurrence non définition → `REFERENCES` ;
- `Relationship.is_implementation` → `IMPLEMENTS` ;
- `Relationship.is_reference` → `REFERENCES` ;
- `Relationship.is_type_definition` → `TYPE_DEFINITION` ;
- `Relationship.is_definition` → `DEFINITION_OF`.

Toutes les relations importées portent :

```text
sourceProvider = "scip"
confidence = 1.0
```

La confiance pourra être différenciée par provider dans une future itération.

## 6. Gestion des plages

SCIP utilise des numéros de lignes à base zéro. NEXUS utilise des numéros de lignes à base un pour ses fragments.

La conversion est donc :

```text
nexusLine = scipLine + 1
```

Le parseur accepte :

1. `single_line_range` et `multi_line_range`, format actuel ;
2. l'ancien champ compact `range` en fallback.

Lorsqu'un index contient les deux, la plage typée est prioritaire.

## 7. Fusion avec JavaParser

SQLite stocke la provenance dans les colonnes `source_provider` déjà présentes dans le schéma initial.

Le rafraîchissement d'un provider externe suit ces règles :

```text
supprimer ancien snapshot du provider
        │
        ▼
conserver JavaParser et les autres providers
        │
        ▼
insérer les symboles externes absents
        │
        ▼
insérer les relations externes non dupliquées
```

Un symbole est considéré comme déjà couvert lorsqu'un symbole du même fichier possède :

- le même `kind` ;
- le même `name` ;
- la même ligne de début.

Cette règle donne la priorité à JavaParser pour les définitions qu'il sait déjà extraire tout en permettant à SCIP d'ajouter les symboles manquants.

Une relation externe est ignorée lorsqu'une relation du même projet possède déjà exactement :

- le même `kind` ;
- la même source ;
- la même cible.

Les données externes qui pointent vers un document absent de `indexed_files` ne créent jamais un fichier canonique artificiel.

## 8. Provenance

`CodeSymbol` expose :

```text
sourceProvider
```

`SymbolRelation` expose :

```text
sourceProvider
confidence
```

Les anciens constructeurs restent compatibles et utilisent :

```text
sourceProvider = javaparser
confidence = 1.0
```

La provenance peut donc être lue depuis `IndexRepository.findSymbols` et `IndexRepository.findRelations`.

## 9. Mesurer le gain par rapport à JavaParser seul

La validation de l'itération doit être faite sur le même projet dans deux états.

### Baseline A — JavaParser seul

Mettre temporairement `index.scip` de côté puis reconstruire :

```powershell
nexus index mon-projet --rebuild
nexus inspect mon-projet
```

Noter :

- nombre de symboles ;
- nombre de relations ;
- `precision@K` ;
- `recall@K` ;
- temps d'indexation.

### Enrichissement B — JavaParser + SCIP

Restaurer `index.scip` puis reconstruire :

```powershell
nexus index mon-projet --rebuild
nexus inspect mon-projet
```

Comparer les mêmes métriques.

Le gain recherché n'est pas uniquement une hausse du nombre de lignes SQLite. Il faut vérifier que les nouveaux symboles et références améliorent réellement le corpus de requêtes de référence sans créer de doublons visibles ni dégrader `precision@K`.

## 10. Tests de protection

Les tests ajoutés couvrent notamment :

- absence d'index SCIP ;
- lecture d'un document SCIP ;
- définition de symbole ;
- référence de symbole ;
- relation d'implémentation ;
- plage SCIP typée ;
- fallback de plage historique ;
- priorité de la plage typée ;
- kinds de symboles non représentables ignorés sans perdre leurs références ;
- provenance `scip` ;
- fusion avec JavaParser ;
- déduplication d'une définition déjà connue ;
- purge du snapshot externe lorsqu'il disparaît ;
- rafraîchissement externe même quand aucun fichier source n'a changé.

## 11. Limites actuelles

Cette première version ne cherche pas à couvrir tout SCIP.

Limites volontaires :

- `external_symbols` n'est pas persisté comme fichier local ;
- le mapping de `SymbolInformation.Kind` est réduit au modèle actuel de NEXUS ;
- NEXUS ne vérifie pas encore si `index.scip` correspond exactement au commit Git courant ;
- NEXUS ne lance pas `scip-java` ;
- les relations SCIP autres que les catégories normalisées sont ignorées ;
- le provider actif `CodeIntelligenceProvider` n'est pas encore exécuté par le pipeline.

Ces limites maintiennent l'itération bornée et préservent le fonctionnement du MVP sans SCIP.
