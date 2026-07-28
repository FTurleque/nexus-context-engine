# Architecture de NEXUS

Ce document décrit **l'architecture courante** de NEXUS. Les décisions historiques, alternatives et justifications détaillées sont conservées dans `docs/adr/`.

État de référence : `main` au commit `13fd6970f7350602c7a86aae729ddd4adad771bd`, 29 juillet 2026.

## 1. Mission et frontières

NEXUS est un moteur d'intelligence de contexte. Il transforme :

```text
repository + projet enregistré + requête + budget
```

en :

```text
ContextBundle minimal, pertinent, classé, traçable et explicable
```

NEXUS n'est pas :

- un LLM ;
- un chatbot ;
- un orchestrateur généraliste ;
- un routeur de modèles ;
- un client Git distant ;
- un exécuteur de skills ;
- un substitut à MINOS pour la Code Intelligence profonde.

Il reste indépendant des fournisseurs de modèles, des IDE, des protocoles clients et des providers externes.

## 2. Vue d'ensemble

```text
Utilisateur / IDE / Agent / Orchestrateur
                 │
                 ▼
        CLI / REST / MCP / API Java
                 │
                 ▼
          NexusApplication
                 │
        ┌────────┼─────────┐
        │        │         │
        ▼        ▼         ▼
     projets  indexation  contexte
                 │         ▲
                 ▼         │
        SQLite + Lucene    │
                 │         │
                 ▼         │
              recherche ───┘
                 │
      ranking + enrichissements
                 │
                 ▼
          ContextBundle
```

`NexusApplication` est la façade applicative commune utilisée par REST et MCP. La CLI réutilise les mêmes composants métier mais possède encore un composition root dupliqué ; sa suppression est planifiée en Phase 6.

## 3. Structure du repository

Le cœur reste un projet Maven Java 21 sans framework applicatif obligatoire :

```text
pom.xml
src/main/java/com/nexus/
```

Les runtimes qui imposent des dépendances distinctes sont isolés :

```text
adapters/
├── rest-quarkus/
├── mcp-java/
└── assistant-clients/
```

Ces adaptateurs disposent aujourd'hui de POM autonomes. Cette isolation a permis d'éviter de contaminer le cœur avec Quarkus ou le SDK MCP, mais les versions Java/plugins/dépendances sont partiellement dupliquées. La Phase 6 prévoit une gouvernance Maven commune sans supprimer cette isolation runtime.

## 4. Projet et persistance

### 4.1 Identité projet

`ProjectDescriptor` porte notamment :

```text
UUID
name
rootPath
sourceType
languages
technologies
lastIndexedAt
indexStatus
```

`IndexStatus` distingue :

```text
NOT_INDEXED
INDEXING
READY
FAILED
```

Le registre est persistant via `ProjectRepository` / `SqliteProjectRepository`.

### 4.2 SQLite canonique

SQLite est la source de vérité structurelle locale pour :

- projets ;
- fichiers indexés ;
- hashes de contenu ;
- catégories/langages ;
- symboles ;
- relations ;
- provenance des providers ;
- état d'indexation.

Les migrations embarquées sont appliquées au démarrage de `SqliteDatabase`.

### 4.3 Lucene dérivé

Lucene est un index dérivé et reconstructible. Il porte notamment :

- contenu ;
- chemin ;
- langage ;
- catégorie ;
- noms de symboles ;
- noms qualifiés ;
- termes normalisés de code.

Les identifiants `camelCase`, acronymes et séparateurs non alphanumériques sont normalisés afin d'améliorer la recherche de code.

Un index vectoriel Lucene séparé peut être créé lorsque le mode sémantique est explicitement activé.

## 5. Scan et indexation

```text
filesystem
   │
   ▼
ProjectScanner
   │
   ├── .gitignore
   ├── .nexusignore
   └── exclusions sensibles/intégrées
   │
   ▼
ScannedFile
   │
   ├── JavaParserLanguageAnalyzer
   ├── MarkdownLanguageAnalyzer
   └── AnalysisResult vide pour langage lexical sans parser embarqué
   │
   ▼
SQLite canonique
   │
   ├── import SCIP opportuniste
   ├── JDT LS si --deep-java
   └── MINOS via import explicite séparé
   │
   ▼
Lucene lexical
   └── Lucene sémantique si configuré
```

L'indexation normale détecte les changements par SHA-256.

En cas d'état autre que `READY`, une nouvelle indexation force actuellement un rebuild. La Phase 6 doit uniformiser le gate de lecture et formaliser la cohérence entre SQLite et les index dérivés lors d'un échec partiel.

## 6. Langages

NEXUS distingue la reconnaissance d'un fichier de son analyse structurelle.

| Langage | Scan/SQLite/Lucene | Structure embarquée |
|---|---:|---|
| Java | oui | JavaParser |
| Markdown | oui | analyse documentaire |
| Kotlin | oui | non |
| TypeScript | oui | non |
| JavaScript | oui | non |
| Python | oui | non |
| SQL | oui | non |

Les langages sans analyseur structurel restent recherchables et injectables dans le contexte. Leur structure peut provenir d'un index externe ou d'un provider.

## 7. Code Intelligence

NEXUS normalise les données externes vers son propre modèle :

```text
CodeSymbol
SymbolRelation
CodeIntelligenceSnapshot
```

Trois familles de contrat coexistent :

```text
LanguageAnalyzer
→ extraction locale embarquée

CodeIndexImporter
→ import opportuniste d'un index déjà produit

CodeIntelligenceProvider
→ analyse profonde explicitement activée
```

### 7.1 JavaParser

Analyseur Java embarqué par défaut : types, méthodes, signatures, positions et imports.

### 7.2 SCIP

`ScipCodeIndexImporter` importe opportunément `<projectRoot>/index.scip`. NEXUS ne lance pas `scip-java`.

### 7.3 JDT Language Server

`JdtLanguageServerCodeIntelligenceProvider` peut être composé depuis l'environnement et exécuté explicitement via `--deep-java`.

Il reste opt-in car la validation a montré un coût nettement supérieur au chemin JavaParser standard.

### 7.4 MINOS

MINOS est intégré par contrat JSON local versionné :

```text
MINOS Java 24 -> JSON stdout -> NEXUS Java 21 stdin
```

NEXUS ne lance jamais MINOS et ne dépend d'aucun type `com.minos`.

Voir `docs/developer/code-intelligence.md` et `docs/developer/minos-code-intelligence.md`.

## 8. Recherche

### 8.1 Pipeline mono-projet

```text
query
 │
 ├── LuceneFileSearchStrategy
 ├── SymbolSearchStrategy
 └── SemanticSearchStrategy     opt-in
 │
 ▼
CandidateMerger
 │
 ├── GraphCandidateEnricher
 └── GitRecencyCandidateEnricher
 │
 ▼
ContextRanker
 │
 ▼
RankedCandidate[]
```

Le chemin par défaut utilise `DeterministicContextRanker`.

Le mode sémantique utilise `SemanticHybridContextRanker` et une Reciprocal Rank Fusion entre ranking historique et ranking vectoriel.

### 8.2 Recherche fédérée

`FederatedSearchService` exécute le moteur projet par projet puis fusionne les résultats en conservant `ProjectDescriptor` avec chaque hit.

Les identités de provenance sont :

```text
projectId + path
```

Deux repositories différents ne sont jamais dédupliqués parce qu'ils possèdent le même chemin relatif.

Limite active : la diversification par chemin intervient après un top-K local borné ; elle peut donc sous-remplir le résultat global. La correction est planifiée en Itération 18.

### 8.3 Points de scale connus

La recherche symbolique, `findSymbols`, `findUsages` et la construction du graphe utilisent encore des lectures projet-wide. Elles sont acceptables sur les volumes validés mais constituent le principal plafond algorithmique identifié pour les volumes beaucoup plus grands.

La Phase 6 remplace ces scans par des requêtes ciblées avant d'envisager un moteur externe.

## 9. Sources de contexte

`DefaultContextBuilder` combine quatre familles :

```text
instructions natives
skills
Git
contexte de tâche (code/tests/docs/symboles)
```

### 9.1 Instructions

Providers actifs :

- `AgentsMdInstructionProvider` ;
- `CopilotInstructionProvider` ;
- `ClaudeInstructionProvider` ;
- `GeminiInstructionProvider`.

Les scopes, références locales et doublons sont résolus avant sélection sous budget.

### 9.2 Skills

Le modèle cible est `SkillSourceProvider` → `SkillDiscoveryService` → `SkillSelector` → `SkillLoader` → `SkillContextSelector`.

Les skills locaux ont une priorité supérieure au snapshot AI Skills Registry.

L'implémentation courante fait encore déléguer la découverte du registre depuis `LocalAgentSkillsProvider`; la Phase 6 doit restaurer une composition explicite des deux providers indépendants.

### 9.3 Git

Le contexte Git est local, borné et strictement en lecture seule. Il peut fournir récence, commits liés, historique court, diff local et co-changements.

Aucun `fetch`, `pull`, `push`, `checkout` ou commit n'est effectué.

## 10. Construction du contexte

```text
ContextRequest
   │
   ▼
SearchService
   │
   ▼
fragments tâche
   │
   ├── instructions applicables
   ├── skills sélectionnés
   └── Git ciblé
   │
   ▼
FragmentMerger
   │
   ▼
BudgetedContextSelector
   │
   ▼
ContextBundle
```

Le budget est une responsabilité du moteur. Le bundle respecte toujours :

```text
estimatedTokens <= tokenBudget
```

L'estimateur par défaut est `HeuristicTokenEstimator`, déterministe et remplaçable. Il ne prétend pas reproduire le tokenizer d'un fournisseur de LLM.

## 11. Recherche sémantique

La sémantique est une capacité opt-in :

```text
EmbeddingProvider
        │
        ▼
SemanticIndexingService
        │
        ▼
LuceneSemanticSearchIndex
        │
        ▼
SemanticSearchStrategy
        │
        ▼
SemanticHybridContextRanker (RRF)
```

La baseline locale mesurée utilise Ollama/qwen3-embedding. Le coût d'indexation d'environ `33×` interdit une activation automatique.

L'activation est aujourd'hui disponible par configuration de `NexusApplication`; son exposition cohérente dans les surfaces opérationnelles est planifiée en Phase 6.

## 12. Adaptateurs

### CLI

Adaptateur historique et JAR autonome. La CLI contient encore un composition root dupliqué ; elle doit migrer vers la façade commune.

### REST

Quarkus est confiné à `adapters/rest-quarkus`. Le serveur écoute sur loopback par défaut et expose health/métriques.

### MCP

Le SDK Java MCP est confiné à `adapters/mcp-java`. Le transport validé est STDIO et `stdout` est réservé au protocole.

### Assistant clients

`adapters/assistant-clients` produit des configurations déterministes pour Copilot et Claude ; il ne modifie pas les préférences utilisateur.

## 13. Invariants architecturaux

1. **SQLite est canonique ; Lucene est dérivé.**
2. **Le cœur reste Java 21 sans framework applicatif obligatoire.**
3. **Les providers externes restent optionnels.**
4. **Le ranking et la sélection sont déterministes et explicables.**
5. **NEXUS ne dépasse pas le budget estimé.**
6. **Les conventions clients restent dans les adaptateurs/providers.**
7. **NEXUS n'exécute pas les skills.**
8. **Le contexte Git ne mute pas le repository.**
9. **MINOS ne devient pas une dépendance binaire ou un processus enfant.**
10. **Une optimisation de scale doit être mesurée avant d'introduire une infrastructure plus lourde.**

## 14. Dette architecturale explicitement suivie

Les limites qui ne doivent plus rester implicites sont centralisées dans :

- `docs/developer/current-limitations.md` ;
- `docs/roadmap.md`, Phase 6 ;
- issue #13.

Les principaux axes sont : correctness fédérée, cohérence des index, suppression des scans complets, composition/builds, concurrence d'indexation, gouvernance des ressources, lifecycle Lucene, exposition des capacités opt-in, contexte fédéré et distribution.

## 15. Règle d'évolution

Avant d'ajouter une nouvelle technologie, NEXUS doit d'abord répondre à trois questions :

1. quel défaut ou besoin mesuré corrige-t-elle ?
2. pourquoi les abstractions locales existantes ne suffisent-elles pas ?
3. peut-elle rester optionnelle derrière un port NEXUS ?

Sans réponse mesurée, l'option reste différée.
