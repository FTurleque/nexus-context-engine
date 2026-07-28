# Support multi-langage

Ce chapitre décrit le support multi-langage courant de NEXUS.

## Principe

NEXUS sépare deux niveaux :

```text
fichier reconnu
   │
   ├── niveau 1 : scan + SQLite + Lucene + ContextBundle
   │             support lexical natif
   │
   └── niveau 2 : symboles + relations
                 analyseur embarqué ou Code Intelligence externe
```

Un langage peut donc être utile au moteur sans parser structurel embarqué.

## Langages reconnus

| Langage | Extensions | Support lexical natif | Structure embarquée |
|---|---|---:|---|
| Java | `.java` | oui | JavaParser |
| Markdown | `.md` | oui | analyse documentaire |
| Kotlin | `.kt`, `.kts` | oui | non |
| TypeScript | `.ts`, `.tsx` | oui | non |
| JavaScript | `.js`, `.jsx`, `.mjs`, `.cjs` | oui | non |
| Python | `.py` | oui | non |
| SQL | `.sql` | oui | non |

La colonne « structure embarquée » décrit uniquement ce que le cœur sait extraire sans source externe.

## `SourceLanguage`

`SourceLanguage` centralise la détection par extension :

```text
Path
  ↓
SourceLanguage.detect(path)
  ↓
ScannedFile.language
```

Cette centralisation évite de dupliquer la liste des langages dans le scanner, le ranking ou le `ContextBuilder`.

## Analyse structurelle

Lorsqu'un `LanguageAnalyzer` supporte le fichier :

```text
fichier
→ analyseur
→ CodeSymbol / SymbolRelation
```

Sinon :

```text
fichier
→ AnalysisResult vide
→ fichier toujours persisté/indexé lexicalement
```

NEXUS ne fabrique pas des symboles approximatifs par regex uniquement pour remplir le modèle.

## Sources structurelles externes

Les langages non couverts par un parser embarqué peuvent recevoir des faits par :

### SCIP

Un `index.scip` présent à la racine est importé opportunément pendant l'indexation.

### MINOS

Un export MINOS peut être importé explicitement :

```text
MINOS nexus-export
→ JSON
→ NEXUS minos-import
```

L'import reste indépendant de l'indexation normale et conserve `sourceProvider=minos`.

### Providers

`CodeIntelligenceProvider` permet d'ajouter une analyse profonde explicitement activée. Le provider livré aujourd'hui est JDT Language Server pour Java.

La structure externe est normalisée vers le même modèle NEXUS ; le `ContextBuilder` ne connaît pas son fournisseur.

## Recherche lexicale

Tous les langages reconnus alimentent `LuceneSearchIndex` avec :

- chemin ;
- langage ;
- catégorie ;
- contenu ;
- termes de code normalisés ;
- symboles éventuels lorsqu'une source structurelle les fournit.

Ils bénéficient du ranking lexical, du signal de chemin, de la récence Git et du pipeline de contexte.

Sans symbole/relations, un fichier ne contribue simplement pas à la recherche symbolique ou au graphe.

## Contexte

Le pipeline ne branche pas sur le langage :

```text
SearchCandidate
   ↓
ContextFragmentFactory
   ↓
FragmentMerger
   ↓
BudgetedContextSelector
   ↓
ContextBundle
```

Un fichier Python/TypeScript/Kotlin sélectionné est donc injecté sous les mêmes invariants de budget qu'un fichier Java.

## Détection des tests

Le scanner reconnaît notamment :

- `src/test`, `src/it`, `test`, `tests`, `__tests__` ;
- Java : `*Test.java`, `*Tests.java`, `*IT.java` ;
- Kotlin : `*Test.kt`, `*Tests.kt` ;
- Python : `test_*.py`, `*_test.py` ;
- JS/TS : `*.test.*`, `*.spec.*` pour les extensions supportées.

Cette détection affecte `FileCategory.TEST` ; elle ne prétend pas analyser le framework de test utilisé.

## Ajouter un langage

### Support lexical

1. ajouter le langage/extensions dans `SourceLanguage` ;
2. ajouter les conventions de test uniquement si elles sont déterministes ;
3. ajouter des tests de détection/indexation/recherche ;
4. vérifier qu'aucune branche n'est nécessaire dans `DefaultContextBuilder`.

### Support structurel

1. préférer un standard/index/provider existant lorsqu'il fournit de meilleurs faits qu'un parser maison ;
2. mesurer le besoin ;
3. normaliser vers `CodeSymbol` / `SymbolRelation` ;
4. conserver la provenance ;
5. rendre la dépendance optionnelle ;
6. documenter par ADR si la décision est structurante.

## Limites actuelles

Le support natif structurel reste principalement Java. Ce choix n'est pas considéré comme un défaut tant qu'un besoin mesuré ne justifie pas un nouveau parser embarqué.

Le principal chantier de scale multi-langage est plutôt transversal : rendre les recherches de symboles/relations ciblées afin que les données ajoutées par SCIP, JDT ou MINOS restent efficaces à grand volume.

Voir :

- [`code-intelligence.md`](code-intelligence.md) ;
- [`minos-code-intelligence.md`](minos-code-intelligence.md) ;
- [`current-limitations.md`](current-limitations.md) ;
- [`../roadmap.md`](../roadmap.md), Itération 19.

## Validation historique

Le runner de l'Itération 10 reste disponible :

```powershell
.\scripts\validate-iteration-10.ps1
```

Il vérifie notamment un projet polyglotte synthétique, la détection des langages, des recherches Python/TypeScript/SQL et la construction d'un contexte sous budget.
