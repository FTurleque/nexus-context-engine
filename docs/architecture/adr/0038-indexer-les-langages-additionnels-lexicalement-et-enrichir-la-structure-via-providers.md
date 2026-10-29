# ADR-0038 — Indexer les langages additionnels lexicalement et enrichir la structure via des providers

- Statut : `accepted`
- Date : 2026-07-20

## Contexte et problème

NEXUS a été construit initialement autour de Java et de la documentation Markdown. Le pipeline d'indexation est toutefois déjà générique : `ProjectScanner` produit des `ScannedFile`, `ProjectIndexingService` délègue éventuellement l'analyse structurelle à des `LanguageAnalyzer`, SQLite conserve les fichiers et relations, et Lucene fournit la recherche lexicale.

L'Itération 10 doit étendre NEXUS à plusieurs langages sans modifier le fonctionnement fondamental du `ContextBuilder`, du ranking ou des contrats de recherche.

Deux besoins doivent être distingués :

1. rendre immédiatement un fichier d'un nouveau langage découvrable, recherchable et utilisable dans un `ContextBundle` ;
2. extraire des symboles et relations sémantiques précis pour ce langage.

Ces deux capacités n'ont pas le même coût ni les mêmes dépendances. Exiger un parser structurel embarqué pour chaque langage bloquerait le support multi-langage et multiplierait les runtimes, bindings natifs ou dépendances spécifiques.

## Facteurs de décision

- conserver le cœur local-first et utilisable hors ligne ;
- ne pas rendre Tree-sitter, un language server ou un indexeur externe obligatoire ;
- réutiliser le pipeline Lucene existant pour la recherche de fichiers ;
- conserver SQLite comme source canonique ;
- ne pas produire de faux symboles à partir d'expressions régulières fragiles ;
- permettre un enrichissement structurel futur sans changer `ContextBuilder` ni le ranking ;
- réutiliser `CodeIndexImporter`, `CodeIntelligenceProvider` et SCIP déjà introduits ;
- garder les coûts d'installation et d'indexation proportionnels aux besoins.

## Options envisagées

### Option A — Ajouter immédiatement un parser structurel embarqué pour chaque langage

Avantages :

- symboles disponibles dès la première indexation ;
- comportement homogène avec JavaParser.

Inconvénients :

- multiplication des dépendances et des APIs spécifiques ;
- maintenance importante ;
- risque de dépendances natives ;
- délai élevé avant de rendre un nouveau langage simplement recherchable.

### Option B — Utiliser Tree-sitter comme dépendance obligatoire pour tous les nouveaux langages

Avantages :

- modèle commun de parsing ;
- grande couverture de grammaires.

Inconvénients :

- introduit une nouvelle dépendance structurante et potentiellement native ;
- impose ce coût même lorsque la recherche lexicale suffit ;
- ne fournit pas à lui seul toute la sémantique inter-fichiers attendue d'un index SCIP ou d'un language server.

### Option C — Indexation lexicale native, analyse structurelle optionnelle

Avantages :

- support immédiat de nouveaux langages dans le scan, Lucene et le contexte ;
- aucune dépendance structurelle supplémentaire obligatoire ;
- pas de faux symboles générés approximativement ;
- enrichissement possible via SCIP, un `LanguageAnalyzer` spécialisé ou un `CodeIntelligenceProvider` ;
- aucune modification requise du `ContextBuilder` ou du ranking.

Inconvénients :

- un langage nouvellement supporté peut ne disposer initialement que de recherche lexicale ;
- les recherches symboliques précises nécessitent un enrichissement supplémentaire.

## Décision retenue

NEXUS adopte l'option C.

Le support multi-langage est découpé en deux niveaux indépendants.

### Niveau 1 — Support lexical natif

`SourceLanguage` centralise les extensions reconnues par le scanner.

Pour un fichier reconnu :

1. `ProjectScanner` le découvre et calcule ses métadonnées ;
2. le fichier est persisté dans SQLite ;
3. son contenu est indexé dans Lucene lorsqu'il appartient à une catégorie de recherche générique ;
4. le langage est ajouté aux métadonnées du projet ;
5. la recherche peut retourner ce fichier ;
6. `ContextFragmentFactory` peut construire un fragment à partir de son contenu sans changement spécifique au langage.

Un `LanguageAnalyzer` structurel n'est plus obligatoire pour qu'un fichier scanné soit indexé. Si aucun analyseur ne le supporte, `ProjectIndexingService` produit un `AnalysisResult` vide en symboles et relations tout en poursuivant l'indexation lexicale.

### Niveau 2 — Intelligence structurelle optionnelle

Les symboles et relations peuvent être ajoutés ultérieurement par :

- un `LanguageAnalyzer` embarqué spécialisé ;
- un `CodeIndexImporter`, notamment SCIP ;
- un `CodeIntelligenceProvider` à la demande ;
- un futur adaptateur Tree-sitter si les métriques justifient son adoption.

Ces enrichissements doivent utiliser les mêmes modèles `CodeSymbol` et `SymbolRelation` et conserver leur provenance.

## Langages ajoutés dans l'Itération 10

Le scanner reconnaît nativement :

- Kotlin : `.kt`, `.kts` ;
- TypeScript : `.ts`, `.tsx` ;
- JavaScript : `.js`, `.jsx`, `.mjs`, `.cjs` ;
- Python : `.py` ;
- SQL : `.sql`.

Java et Markdown conservent leur comportement existant.

## Classification des tests

La classification `FileCategory.TEST` est étendue aux conventions usuelles déjà identifiables sans parser :

- répertoires `src/test`, `src/it`, `test`, `tests`, `__tests__` ;
- suffixes Java/Kotlin `*Test`, `*Tests`, `*IT` selon les conventions existantes ;
- Python `test_*.py` et `*_test.py` ;
- JavaScript/TypeScript `*.test.*` et `*.spec.*` pour les extensions prises en charge.

Cette classification reste une heuristique de catégorie de fichier, pas une analyse syntaxique.

## Conséquences

### Positives

- NEXUS devient réellement polyglotte pour la recherche lexicale et la construction de contexte ;
- aucun runtime externe supplémentaire n'est requis par défaut ;
- l'ajout futur d'un analyseur structurel ne change pas les consommateurs ;
- SCIP devient naturellement un enrichissement multi-langage plutôt qu'un mécanisme réservé à Java ;
- les fichiers SQL du projet peuvent désormais être recherchés comme source de contexte.

### Négatives

- sans index structurel externe, les nouveaux langages ne contribuent pas à `SymbolSearchStrategy` ni au graphe de symboles ;
- la qualité dépend davantage de Lucene pour ces langages au niveau 1 ;
- la liste des extensions supportées doit être gouvernée explicitement.

## Confirmation du respect de la décision

La décision est confirmée si :

1. un projet contenant uniquement Kotlin, TypeScript, JavaScript, Python ou SQL peut être indexé sans analyseur structurel enregistré ;
2. ses langages sont enregistrés dans `ProjectDescriptor.languages` ;
3. ses fichiers sont recherchables lexicalement ;
4. un fichier non supporté reste ignoré ;
5. JavaParser continue de fonctionner pour Java ;
6. le `ContextBuilder` et le ranking ne nécessitent aucune branche conditionnelle par langage ;
7. les enrichissements SCIP continuent de fusionner leurs symboles et relations avec la base canonique.

## Conditions de réexamen

Cette décision devra être réévaluée si :

- la recherche lexicale s'avère insuffisante sur un corpus réel d'un langage donné ;
- un parser structurel commun apporte un gain mesurable supérieur à son coût opérationnel ;
- un binding Tree-sitter stable et simple à distribuer devient justifié par plusieurs langages ;
- la majorité des projets ciblés exige une navigation symbolique sans index SCIP disponible.

Dans ce cas, l'ajout d'un analyseur structurel doit rester compatible avec le niveau lexical existant et ne pas rendre les autres langages dépendants de ce composant.
