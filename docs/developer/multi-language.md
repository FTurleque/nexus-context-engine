# Support multi-langage

Ce chapitre décrit le support multi-langage introduit par l'Itération 10.

## Principe

NEXUS sépare volontairement deux niveaux de capacité :

```text
Fichier reconnu
    │
    ├── niveau 1 : scan + SQLite + Lucene + ContextBundle
    │             disponible nativement
    │
    └── niveau 2 : symboles + relations
                  analyseur spécialisé ou provider externe
```

Un langage peut donc être utile à NEXUS avant même de disposer d'un parser structurel embarqué.

## Langages reconnus

| Langage | Extensions | Support lexical natif | Structure embarquée |
|---|---|---:|---:|
| Java | `.java` | oui | JavaParser |
| Markdown | `.md` | oui | non |
| Kotlin | `.kt`, `.kts` | oui | non |
| TypeScript | `.ts`, `.tsx` | oui | non |
| JavaScript | `.js`, `.jsx`, `.mjs`, `.cjs` | oui | non |
| Python | `.py` | oui | non |
| SQL | `.sql` | oui | non |

La colonne « structure embarquée » décrit uniquement le comportement par défaut du cœur NEXUS. Un index SCIP ou un autre provider peut ajouter des symboles et relations sans modifier le scanner ni les consommateurs.

## `SourceLanguage`

`SourceLanguage` centralise la correspondance entre extensions et identifiants de langage.

Le scanner ne contient plus une condition codée en dur limitée à Java et Markdown :

```text
Path
  ↓
SourceLanguage.detect(path)
  ↓
ScannedFile.language
```

L'objectif est d'éviter que chaque nouveau langage impose des modifications dispersées dans plusieurs composants.

## Analyseur structurel optionnel

Avant l'Itération 10, chaque fichier scanné devait obligatoirement trouver un `LanguageAnalyzer`. Cette contrainte empêchait d'indexer lexicalement un langage sans parser dédié.

Le comportement est désormais :

```text
LanguageAnalyzer compatible ?
    │
    ├── oui → analyse structurelle normale
    │
    └── non → AnalysisResult vide
              + fichier persisté
              + contenu indexé dans Lucene
              + contexte disponible
```

NEXUS ne génère donc pas de symboles approximatifs par expression régulière uniquement pour satisfaire le contrat.

## Recherche

Les fichiers des nouveaux langages alimentent `LuceneSearchIndex` exactement comme les autres sources génériques.

Ils bénéficient notamment :

- de la recherche BM25 ;
- du signal de chemin ;
- du ranking déterministe ;
- du signal Git lorsqu'il est disponible ;
- de la construction de fragments de fichier par `ContextFragmentFactory`.

Sans enrichissement structurel, ils ne contribuent pas à `SymbolSearchStrategy` et ne créent pas de relations de graphe symboliques.

## Contexte

Aucune branche conditionnelle par langage n'a été ajoutée dans `DefaultContextBuilder`.

Un fichier Python ou TypeScript sélectionné par le ranking suit le même pipeline qu'un candidat fichier Java :

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

Le critère architectural de l'Itération 10 est ainsi conservé : ajouter un langage ne modifie pas le fonctionnement fondamental du `ContextBuilder` ni du ranking.

## Classification des tests

`ProjectScanner` reconnaît plusieurs conventions courantes :

- répertoires `src/test`, `src/it`, `test`, `tests`, `__tests__` ;
- Java : `*Test.java`, `*Tests.java`, `*IT.java` ;
- Kotlin : `*Test.kt`, `*Tests.kt` ;
- Python : `test_*.py`, `*_test.py` ;
- JavaScript / TypeScript : `*.test.*`, `*.spec.*` pour les extensions supportées.

Cette détection sert uniquement à la catégorie `FileCategory.TEST`.

## Enrichissement avec SCIP

Le support lexical et SCIP sont complémentaires.

```text
Source Kotlin / TypeScript / Python / ...
        │
        ├── scanner NEXUS → fichier + contenu lexical
        │
        └── index SCIP disponible → symboles + relations externes
```

`ScipCodeIndexImporter` reste opportuniste : NEXUS ne lance pas automatiquement un indexeur externe et continue de fonctionner lorsque `index.scip` est absent.

## Ajouter un nouveau langage

Pour un support lexical simple :

1. ajouter le langage et ses extensions à `SourceLanguage` ;
2. ajouter les conventions de test uniquement lorsqu'elles sont déterministes et utiles ;
3. ajouter un test de détection ;
4. ajouter un scénario d'indexation/recherche.

Aucune modification du `ContextBuilder` ou du ranking ne doit être nécessaire.

Pour un support structurel :

1. évaluer un `LanguageAnalyzer`, un index SCIP ou un `CodeIntelligenceProvider` ;
2. mesurer le gain réel ;
3. normaliser les résultats vers `CodeSymbol` et `SymbolRelation` ;
4. conserver la provenance ;
5. documenter toute nouvelle dépendance structurante dans un ADR.

## Validation reproductible

Depuis la racine du repository :

```powershell
.\scripts\validate-iteration-10.ps1
```

Le script exécute :

```text
mvn clean install
→ self-smoke NEXUS
→ création d'un projet polyglotte synthétique
→ indexation sans analyseur structurel dédié
→ vérification des langages détectés
→ recherches Python / TypeScript / SQL
→ construction d'un ContextBundle Python sous budget
```

Le script n'utilise pas `exit` et laisse le terminal PowerShell ouvert en cas d'échec.

## Décision associée

Voir ADR-0038 — Indexer les langages additionnels lexicalement et enrichir la structure via des providers.
