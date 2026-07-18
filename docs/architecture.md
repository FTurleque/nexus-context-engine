# Socle architectural

## 1. Objectif architectural

NEXUS est un moteur d'intelligence de contexte. Sa responsabilité principale est de transformer un projet et une demande utilisateur en un `ContextBundle` minimal, classé, explicable et contraint par un budget.

Le moteur doit rester indépendant des fournisseurs de LLM, des fournisseurs d'embeddings, des IDE, des agents et des protocoles de transport.

## 2. Décisions

### 2.1 Socle Java : niveau de compilation Java 21

**Décision :** compiler le MVP avec `--release 21`.

Alternatives envisagées :

- Java 21 : LTS, très largement disponible et suffisamment moderne pour les besoins du MVP ;
- Java 24 : disponible dans l'environnement local actuel, mais non retenu comme niveau minimal de compilation afin de préserver la portabilité ;
- Java 25 : LTS plus récente, mais son utilisation comme niveau de compilation imposerait immédiatement un JDK 25 à tous les contributeurs.

Justification : NEXUS est un projet open source destiné à être intégré dans différents environnements. Java 21 constitue une base stable et portable. Le développement peut être réalisé avec un JDK plus récent, notamment Java 24, tant que le code reste compatible avec le niveau de compilation Java 21.

### 2.2 Framework : cœur Java simple, Quarkus ultérieurement

**Décision :** ne pas introduire Quarkus dans le cœur pendant les premières itérations.

Alternatives envisagées :

- Quarkus dès le départ : pratique pour REST, l'injection de dépendances et certains adaptateurs, mais cela risquerait de coupler prématurément le cœur métier à un runtime applicatif ;
- cœur Java simple avec adaptateur Quarkus ultérieur : câblage un peu plus explicite, mais meilleure portabilité et testabilité.

Recommandation : conserver les domaines et services applicatifs en Java simple. Introduire Quarkus uniquement lors de la création de l'adaptateur API REST. La version de Quarkus sera choisie au moment de cette itération en fonction de la version LTS alors retenue.

### 2.3 Structure du repository : un seul module Maven au départ

**Décision :** démarrer avec un seul module Maven organisé par responsabilités.

Ne pas créer immédiatement `nexus-core`, `nexus-indexer`, `nexus-search`, `nexus-api`, `nexus-cli` et `nexus-mcp`.

Extraire des modules Maven uniquement lorsqu'au moins une des conditions suivantes apparaît :

- packaging ou runtime distinct ;
- isolation nécessaire des dépendances ;
- cycle de livraison indépendant ;
- isolation du build apportant un bénéfice réel.

Ordre probable d'extraction ultérieure : `nexus-core`, puis `nexus-cli` et `nexus-api`, puis éventuellement `nexus-mcp`.

### 2.4 Analyse Java : AST derrière `LanguageAnalyzer`

**Décision :** utiliser JavaParser pour le premier analyseur Java.

Alternatives envisagées :

- expressions régulières : rejetées pour l'analyse structurelle ;
- Eclipse JDT : puissant, mais plus lourd pour la première tranche d'indexation locale ;
- Tree-sitter : intéressant pour le multi-langage, mais introduit des considérations supplémentaires de runtime avant validation du MVP Java ;
- JavaParser : spécialisé Java, simple à embarquer et suffisant pour le MVP.

L'interface `LanguageAnalyzer` empêche JavaParser de devenir une dépendance architecturale du moteur complet.

### 2.5 Stockage : SQLite lors de l'introduction de la persistance

**Décision :** utiliser SQLite derrière une abstraction de stockage pour la persistance du MVP.

Alternatives envisagées :

- fichiers structurés : très simples au départ, mais peu adaptés aux mises à jour incrémentales, aux relations et aux métadonnées de recherche ;
- H2 : bonne base embarquée Java, mais moins attractive pour un index local portable sous forme de fichier unique ;
- SQLite : base mature, locale, inspectable, portable et adaptée aux métadonnées ainsi qu'à une indexation lexicale via FTS5.

Zones logiques initiales :

- registre des projets ;
- fichiers indexés ;
- symboles ;
- relations entre symboles et fichiers ;
- index de recherche lexicale ;
- métadonnées d'indexation.

Les embeddings ne font pas partie du schéma de stockage du MVP.

### 2.6 Recherche : approche hybride sans embeddings obligatoires

Le pipeline de recherche du MVP doit combiner :

1. correspondance lexicale sur les chemins, noms de symboles et textes indexés ;
2. correspondance exacte ou approximative des symboles ;
3. classification des fichiers et association avec les tests ;
4. proximité structurelle à partir des relations connues lorsqu'elles sont disponibles.

Une recherche sémantique basée sur des embeddings pourra être ajoutée ultérieurement comme implémentation optionnelle de `SearchStrategy`.

### 2.7 Classement : déterministe et explicable

Le classement constitue un composant indépendant. Chaque candidat reçoit des composantes de score explicites, par exemple :

- correspondance lexicale ;
- correspondance exacte d'un symbole ;
- correspondance du chemin ou du nom ;
- proximité dans le graphe de dépendances ;
- bonus lié à un test associé ;
- bonus lié à une modification récente.

Le score final doit être reproductible pour une même requête, un même index et une même configuration. Les explications sont dérivées directement des composantes ayant produit le score et ne sont pas générées par un LLM.

### 2.8 Budget de tokens : abstraction indépendante des modèles

`TokenEstimator` est une interface. L'implémentation locale par défaut peut utiliser une estimation déterministe. Des tokenizers spécifiques à certains fournisseurs pourront être ajoutés ultérieurement.

La sélection doit privilégier les extraits de symboles pertinents avant les fichiers complets et enregistrer les décisions d'exclusion ou de troncature lorsque `explain=true`.

## 3. Responsabilités initiales

```text
io.github.fturleque.nexus
├── project      identité des projets et contrats du registre
├── index        analyse des langages et contrats d'indexation structurelle
├── search       stratégies de recherche et candidats
├── ranking      contrats de classement déterministe
├── token        abstraction d'estimation des tokens
└── context      requête, bundle et orchestration du contexte
```

Des adaptateurs futurs pourront ajouter des responsabilités pour la persistance, la CLI, REST, Git, les instructions et MCP sans déplacer la logique métier dans les couches de transport.

## 4. Modèle de données minimal

### `ProjectDescriptor`

- `id` ;
- `name` ;
- `rootPath` ;
- `sourceType` ;
- `languages` ;
- `technologies` ;
- `lastIndexedAt` ;
- `indexStatus`.

### Fichier indexé — persistance prévue à l'itération 1

- `id` ;
- `projectId` ;
- `relativePath` ;
- `language` ;
- `sizeBytes` ;
- `contentHash` ;
- `modifiedAt` ;
- `estimatedTokens` ;
- `category`.

### `CodeSymbol`

- `kind` ;
- `name` ;
- `qualifiedName` ;
- `signature` ;
- `startLine` ;
- `endLine`.

### `SymbolRelation`

- `kind` ;
- `source` ;
- `target`.

### `ContextRequest`

- `projectId` ;
- `query` ;
- `tokenBudget` ;
- `requestedSources` ;
- `constraints` ;
- `explain`.

### `ContextBundle`

- éléments sélectionnés ;
- estimation des tokens ;
- budget de tokens ;
- éléments exclus et motifs ;
- métadonnées.

## 5. Socle de sécurité

- fonctionnement local par défaut ;
- aucun appel sortant vers un modèle ou un service d'embeddings dans les flux principaux ;
- exclusions de type `.gitignore` complétées par `.nexusignore` ;
- exclusions intégrées pour les dossiers générés courants et les fichiers manifestement sensibles ;
- intégrations externes explicites et observables ;
- stockage du contenu indexé dans l'espace de données local de NEXUS, sans copie silencieuse vers un service cloud.

## 6. Socle d'observabilité

Les futurs adaptateurs applicatifs devront pouvoir exposer des événements structurés concernant :

- la durée d'indexation et le nombre de fichiers ;
- la latence de recherche et le nombre de candidats ;
- la durée de construction du contexte ;
- les estimations de tokens des candidats et des éléments sélectionnés ;
- le ratio de réduction ;
- les motifs d'exclusion.

Le cœur doit fournir les données nécessaires à ces métriques sans dépendre d'un backend de télémétrie particulier.
