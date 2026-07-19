# Architecture de NEXUS

## 1. Objectif architectural

NEXUS est un **moteur d'intelligence de contexte**. Sa responsabilité principale est de transformer une demande, un projet et un ensemble de sources de contexte disponibles en un `ContextBundle` minimal, pertinent, classé, explicable, traçable et contraint par un budget.

NEXUS n'est pas un chatbot et ne choisit pas quel modèle IA doit traiter une demande. Il se situe entre l'environnement appelant et le LLM ou l'agent consommateur du contexte.

```text
Utilisateur / IDE / Agent / Orchestrateur
                 │
                 │ demande
                 ▼
               NEXUS
                 │
                 ├── comprend la demande
                 ├── identifie le projet
                 ├── découvre les sources de contexte
                 ├── interroge les index de code
                 ├── recherche les éléments pertinents
                 ├── enrichit avec les relations structurelles
                 ├── classe les candidats
                 ├── élimine doublons et bruit
                 ├── respecte un budget
                 └── explique ses décisions
                 │
                 ▼
            ContextBundle
                 │
                 ▼
       Copilot / Claude / MCP / JARVIS / autre
                 │
                 ▼
             LLM / Agent
```

Le moteur doit rester indépendant :

- des fournisseurs de LLM ;
- des fournisseurs d'embeddings ;
- des IDE ;
- des formats propres à Copilot ou Claude ;
- des agents et orchestrateurs ;
- des protocoles de transport ;
- des moteurs d'indexation et de recherche concrets.

Le principe directeur est le suivant : **NEXUS doit orchestrer des capacités existantes lorsque celles-ci sont adaptées, plutôt que reconstruire chaque sous-problème depuis zéro.**

---

## 2. Principes structurants

### 2.1 Socle Java : niveau de compilation Java 21

**Décision :** compiler le cœur avec `--release 21`.

Alternatives envisagées :

- Java 21 : LTS, largement disponible et suffisamment moderne pour le cœur ;
- Java 24 : disponible dans l'environnement local actuel, mais non retenu comme niveau minimal ;
- Java 25 : LTS plus récente, mais imposerait immédiatement un JDK 25 à tous les contributeurs.

NEXUS peut être développé avec un JDK plus récent tant que le cœur reste compatible Java 21.

### 2.2 Framework : cœur Java simple, frameworks aux frontières

**Décision :** aucun framework applicatif obligatoire dans le cœur.

Quarkus pourra être utilisé pour l'adaptateur API REST lorsqu'il sera nécessaire. Les adaptateurs CLI, MCP ou IDE ne doivent pas imposer leurs dépendances au moteur central.

```text
                 nexus-core logique
                       │
         ┌─────────────┼─────────────┐
         ▼             ▼             ▼
       CLI          API REST        MCP
                   Quarkus      MCP Java SDK
```

### 2.3 Structure du repository : un module Maven tant que possible

Le repository reste initialement un seul module Maven organisé par responsabilités.

Des modules séparés ne seront créés que lorsqu'une contrainte réelle apparaît :

- runtime distinct ;
- packaging distinct ;
- isolation de dépendances lourdes ;
- cycle de livraison distinct ;
- bénéfice réel pour le build ou l'intégration.

Ordre probable d'extraction :

```text
nexus-context-engine
        │
        ├── nexus-core
        ├── nexus-cli
        ├── nexus-api
        ├── nexus-mcp
        └── adaptateurs optionnels lourds
```

---

## 3. Architecture logique cible

```text
                               NEXUS
                    Context Intelligence Engine
                                  │
              ┌───────────────────┼───────────────────┐
              │                   │                   │
              ▼                   ▼                   ▼
       Project Registry     Context Sources     Code Intelligence
              │                   │                   │
            SQLite      ┌─────────┼─────────┐   ┌─────┼─────────────┐
                        │         │         │   │     │             │
                    Instructions Skills   Docs JavaParser SCIP    JDT / Tree-sitter
                        │         │         │   │     │             │
                        └─────────┴─────────┴───┴─────┴─────────────┘
                                      │
                                      ▼
                              Index & Search Layer
                               SQLite + Lucene
                                      │
                                      ▼
                              Candidate Retrieval
                                      │
                                      ▼
                             Structural Enrichment
                                      │
                                      ▼
                              Explainable Ranking
                                      │
                                      ▼
                                Token Budget
                                      │
                                      ▼
                                ContextBundle
                                      │
                    ┌─────────────────┼─────────────────┐
                    ▼                 ▼                 ▼
                 Copilot            Claude             MCP / JARVIS
```

NEXUS possède son propre modèle métier. Les outils externes sont utilisés derrière des **ports** et **adaptateurs**.

---

## 4. Intelligence de code

### 4.1 Contrat principal

L'abstraction `LanguageAnalyzer` reste le point d'entrée minimal pour l'analyse structurelle d'un langage.

Elle ne doit cependant pas devenir l'unique abstraction d'intelligence de code. Le moteur distinguera progressivement :

```text
LanguageAnalyzer
→ extraction syntaxique et structurelle locale

CodeIntelligenceProvider
→ définitions, références, usages, implémentations, hiérarchies

CodeIndexImporter
→ import d'un index produit par un outil externe
```

### 4.2 JavaParser : analyseur Java embarqué par défaut

**Décision MVP :** conserver JavaParser.

Rôle :

- classes ;
- interfaces ;
- records ;
- enums ;
- méthodes ;
- signatures ;
- imports ;
- informations de position dans le fichier.

Avantages :

- bibliothèque Java simple à embarquer ;
- fonctionnement local ;
- pas de processus externe obligatoire ;
- bonne base pour le MVP.

JavaParser ne doit pas devenir responsable à lui seul de la résolution sémantique complète de tous les projets.

### 4.3 SCIP : source d'intelligence de code optionnelle et prioritaire

NEXUS doit prévoir un adaptateur capable d'importer ou interroger des index **SCIP**.

Objectifs :

- bénéficier de définitions et références déjà résolues ;
- enrichir les relations entre symboles ;
- faciliter le multi-langage ;
- éviter de réimplémenter chaque moteur de résolution de symboles.

Architecture visée :

```text
CodeIntelligenceProvider
├── JavaParserCodeIntelligenceProvider
├── ScipCodeIntelligenceProvider
└── autres fournisseurs
```

Le modèle interne de NEXUS reste `CodeSymbol` / `SymbolRelation`. SCIP est une source, pas le modèle métier central.

### 4.4 Eclipse JDT Language Server : analyse Java profonde à la demande

JDT LS est envisagé comme fournisseur optionnel pour des besoins Java plus riches :

- références ;
- implémentations ;
- hiérarchie d'appels ;
- hiérarchie de types ;
- résolution dans des projets Maven ou Gradle complexes.

Il ne fait pas partie du chemin critique du MVP en raison de son poids opérationnel supérieur à JavaParser.

### 4.5 Tree-sitter : stratégie multi-langage future

Tree-sitter est envisagé comme analyseur multi-langage optionnel pour les langages ne disposant pas d'un fournisseur plus riche ou plus naturel.

Il ne doit pas être imposé au cœur tant que ses contraintes de runtime et de compatibilité ne sont pas justifiées par un besoin réel.

---

## 5. Persistance et recherche

### 5.1 Séparation des responsabilités : SQLite + Lucene

La décision initiale d'utiliser SQLite pour tout le stockage est affinée.

**Décision :**

```text
SQLite
→ source de vérité structurelle et métadonnées

Lucene
→ moteur de recherche local
```

### 5.2 SQLite

SQLite stockera notamment :

- registre des projets ;
- fichiers indexés ;
- empreintes de contenu ;
- symboles ;
- relations ;
- état et historique d'indexation ;
- métadonnées de sources de contexte.

SQLite reste derrière une abstraction de persistance.

### 5.3 Apache Lucene

Lucene devient le moteur de recherche lexical privilégié.

Les documents Lucene pourront indexer des champs tels que :

```text
projectId
path
module
packageName
language
symbolName
symbolKind
qualifiedName
content
comments
documentation
sourceType
```

Le moteur pourra exploiter :

- BM25 ;
- pondération par champ ;
- recherche exacte ;
- recherche approximative ;
- filtres structurés.

La recherche vectorielle reste optionnelle et pourra être ajoutée plus tard sans imposer immédiatement une base vectorielle dédiée.

### 5.4 Abstractions de recherche

```text
SearchStrategy
├── LexicalSearchStrategy
├── SymbolSearchStrategy
├── GraphSearchStrategy
├── SemanticSearchStrategy      futur et optionnel
└── ExternalCodeSearchStrategy  futur
```

Lucene est une implémentation privilégiée, pas une dépendance exposée dans les contrats métier.

### 5.5 Recherche massive future

Pour des installations comportant de très grands volumes de code ou de nombreux repositories, des adaptateurs vers des moteurs spécialisés comme Zoekt ou OpenGrok pourront être étudiés.

Ils ne font pas partie du MVP local.

---

## 6. Graphe et classement du contexte

### 6.1 Graphe interne

NEXUS construit progressivement un graphe à partir des informations disponibles :

```text
File
  │
  ├── contains ──> Symbol
  ├── imports ───> File / Symbol
  ├── calls ─────> Symbol
  ├── references > Symbol
  ├── implements > Symbol
  ├── extends ───> Symbol
  └── testedBy ──> Test
```

Toutes les relations ne sont pas obligatoires pour le MVP.

### 6.2 Ranking inspiré des principes de RepoMap d'Aider

Le ranking de NEXUS doit étudier et réutiliser les principes pertinents du mécanisme RepoMap d'Aider :

- importance des symboles dans un graphe ;
- proximité avec les symboles mentionnés dans la requête ;
- propagation de pertinence dans les relations ;
- sélection sous contrainte de budget.

NEXUS n'est pas une copie d'Aider RepoMap. Le ranking doit rester :

- indépendant de l'interface utilisateur ;
- multi-source ;
- explicable ;
- déterministe ;
- extensible.

Un candidat pourra recevoir des composantes telles que :

```text
lexicalScore
symbolScore
pathScore
graphScore
architecturalImportance
testAssociationScore
recentChangeScore
sourcePriorityScore
```

Le score final doit être reproductible pour une même requête, un même index et une même configuration.

Les explications sont dérivées des facteurs de score et non générées par un LLM.

---

## 7. Sources de contexte normalisées

NEXUS doit traiter les différentes formes de contexte comme des **sources**, et non comme des concepts codés en dur pour un fournisseur particulier.

```text
ContextSource
├── CODE
├── SYMBOL
├── TEST
├── DOCUMENTATION
├── INSTRUCTION
├── SKILL
├── AGENT_PROFILE
├── PROMPT
└── GIT
```

Chaque source est découverte par un `ContextSourceProvider`.

```text
ContextSourceProvider
├── CodeContextSourceProvider
├── DocumentationSourceProvider
├── InstructionSourceProvider
├── SkillSourceProvider
├── GitContextSourceProvider
└── autres fournisseurs
```

### 7.1 Instructions : standards et formats fournisseurs

NEXUS doit pouvoir découvrir et normaliser plusieurs formats :

```text
AGENTS.md
.github/copilot-instructions.md
.github/instructions/*.instructions.md
CLAUDE.md
GEMINI.md
autres formats configurés
```

Architecture visée :

```text
InstructionSourceProvider
├── AgentsMdInstructionProvider
├── CopilotInstructionProvider
├── ClaudeInstructionProvider
└── GenericInstructionProvider
```

Le moteur interne manipule un modèle commun contenant notamment :

```text
id
origin
scope
pathPatterns
priority
content
metadata
```

NEXUS détermine quelles instructions sont applicables. Il ne remplace pas les fichiers natifs des outils.

### 7.2 Skills : adoption du standard Agent Skills

NEXUS ne doit pas inventer son propre format de skills.

Le format **Agent Skills** basé sur `SKILL.md` devient la cible native privilégiée.

NEXUS doit appliquer un principe de divulgation progressive :

```text
Découverte
→ indexer nom, description et métadonnées

Sélection
→ charger le SKILL.md uniquement si pertinent

Exécution par l'agent
→ charger scripts, références et assets seulement si nécessaires
```

NEXUS sélectionne et fournit les skills pertinents. L'exécution du skill reste la responsabilité de l'agent ou de l'orchestrateur consommateur.

### 7.3 Documentation

Les fichiers Markdown et autres documents projet pourront être indexés comme sources distinctes du code, avec leurs propres métadonnées et stratégies de ranking.

### 7.4 Git

Le contexte Git futur pourra fournir :

- commits récents pertinents ;
- fichiers fréquemment modifiés ensemble ;
- diff lié à une zone du code ;
- historique limité d'un symbole ou fichier.

Le Git context reste optionnel dans un `ContextRequest`.

---

## 8. Construction du contexte

### 8.1 Pipeline cible

```text
ContextRequest
      │
      ▼
Intent / Query Analysis
      │
      ▼
Context Source Discovery
      │
      ▼
Candidate Retrieval
      │
      ├── Lucene
      ├── Symbol index
      ├── Code graph
      ├── SCIP / provider optionnel
      ├── Instructions
      ├── Skills
      ├── Documentation
      └── Git
      │
      ▼
Candidate Normalization
      │
      ▼
Explainable Ranking
      │
      ▼
Deduplication / Overlap Merge
      │
      ▼
Token Budget Selection
      │
      ▼
ContextBundle
```

### 8.2 Budget de tokens

`TokenEstimator` reste une interface indépendante du modèle.

Le moteur doit :

- privilégier les extraits de symboles aux fichiers complets ;
- préserver les relations indispensables à la compréhension ;
- fusionner les extraits qui se chevauchent ;
- éliminer les doublons inter-sources ;
- réserver éventuellement des sous-budgets par type de source ;
- enregistrer les exclusions et troncatures lorsque `explain=true`.

---

## 9. Modèle de données minimal et évolutif

### `ProjectDescriptor`

- `id` ;
- `name` ;
- `rootPath` ;
- `sourceType` ;
- `languages` ;
- `technologies` ;
- `lastIndexedAt` ;
- `indexStatus`.

### `IndexedFile`

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

- `id` ;
- `projectId` ;
- `fileId` ;
- `kind` ;
- `name` ;
- `qualifiedName` ;
- `signature` ;
- `startLine` ;
- `endLine` ;
- `sourceProvider`.

### `SymbolRelation`

- `kind` ;
- `source` ;
- `target` ;
- `confidence` ;
- `sourceProvider`.

### `ContextSourceDescriptor`

- `id` ;
- `projectId` ;
- `type` ;
- `origin` ;
- `path` ;
- `scope` ;
- `metadata`.

### `ContextRequest`

- `projectId` ;
- `query` ;
- `tokenBudget` ;
- `requestedSources` ;
- `constraints` ;
- `explain`.

### `ContextBundle`

- éléments sélectionnés ;
- score et raisons par élément ;
- estimation des tokens ;
- budget de tokens ;
- éléments exclus et motifs ;
- stratégies ayant produit chaque élément ;
- métadonnées de construction.

---

## 10. Adaptateurs et intégrations

### 10.1 Copilot et Claude

NEXUS ne remplace pas les mécanismes natifs de contexte de Copilot ou Claude.

Des adaptateurs pourront :

- découvrir leurs formats natifs ;
- les normaliser dans le modèle NEXUS ;
- produire un `ContextBundle` exploitable par l'environnement appelant.

### 10.2 MCP

Le futur serveur MCP doit utiliser le **SDK Java MCP** plutôt que réimplémenter le protocole.

Les handlers MCP restent des adaptateurs minces au-dessus des services NEXUS.

Exemples d'outils :

```text
search_code
find_symbol
find_usages
get_relevant_files
get_related_tests
get_architecture_context
get_module_context
get_project_instructions
get_recent_changes
build_context
explain_context
```

### 10.3 AI Skills Registry

NEXUS sélectionne les skills utiles et pourra déléguer leur résolution à un registre externe.

```text
NEXUS
  │
  ├── contexte code
  ├── instructions
  ├── documentation
  └── skills requis
          │
          ▼
   AI Skills Registry
```

NEXUS ne dépend pas du registre pour fonctionner.

### 10.4 JARVIS, Alfred et Brainiac

NEXUS fournit le contexte.

L'orchestrateur ou l'agent décide :

- quel agent utiliser ;
- quel modèle appeler ;
- comment exécuter un skill ;
- comment utiliser le `ContextBundle`.

---

## 11. Socle de sécurité

- fonctionnement local par défaut ;
- aucun appel externe obligatoire ;
- `.gitignore` et `.nexusignore` ;
- exclusions intégrées des secrets et contenus générés ;
- intégrations externes explicites ;
- journalisation de la provenance des éléments du contexte ;
- stockage local des index ;
- séparation claire entre index local et services externes optionnels.

L'utilisation d'un fournisseur d'embeddings, d'un index distant ou d'une source Git distante doit être opt-in et observable.

---

## 12. Observabilité et métriques

Le cœur doit exposer les données nécessaires pour mesurer :

- durée d'indexation ;
- nombre de fichiers et symboles ;
- latence de recherche ;
- nombre de candidats ;
- contributions des stratégies de recherche ;
- durée de ranking ;
- durée de construction du contexte ;
- tokens candidats et sélectionnés ;
- ratio de réduction ;
- raisons d'exclusion ;
- précision et rappel sur corpus de référence.

Le cœur ne dépend d'aucun backend de télémétrie particulier.

---

## 13. Positionnement final de NEXUS

La valeur de NEXUS ne réside pas dans la création d'un nouveau parser, d'un nouveau format de skills ou d'un nouveau protocole agent.

Sa valeur est de fournir une couche commune capable de répondre de manière déterministe et explicable à la question :

> **Pour cette demande précise, dans ce projet précis, parmi toutes les sources de contexte disponibles, quelles informations faut-il fournir à l'IA et lesquelles faut-il écarter ?**

```text
Code Intelligence  → JavaParser / SCIP / JDT / Tree-sitter
Search             → Lucene / fournisseurs futurs
Instructions       → AGENTS.md / Copilot / Claude / formats génériques
Skills             → Agent Skills / AI Skills Registry
Documentation      → fichiers projet
Git                → repository local
Ranking            → lexical + symboles + graphe + signaux métier
Budget             → TokenEstimator

                         │
                         ▼
                      NEXUS
                         │
                         ▼
                   ContextBundle
```

Cette séparation doit rester la décision architecturale fondamentale du projet.