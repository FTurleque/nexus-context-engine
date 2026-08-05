# Section 12 — Glossaire

## Termes métier

| Terme | Définition |
|-------|-----------|
| **ContextBundle** | Résultat canonique produit par NEXUS : liste ordonnée de `ContextItem` sous budget de tokens, avec metadata d'explicabilité |
| **ContextItem** | Fragment de contexte (code, documentation, instruction, skill, Git) avec score, composantes de score, raisons et estimation de tokens |
| **ContextRequest** | Requête entrante : projectId, query, tokenBudget, sources demandées, contraintes |
| **Token budget** | Limite maximale de tokens estimés pour le ContextBundle — jamais dépassée |
| **Projet NEXUS** | Repository local enregistré avec un UUID métier durable, un chemin racine canonicalisé et un état d'indexation |
| **Fédération** | Recherche ou construction de contexte sur plusieurs projets READY simultanément, avec budget global partagé et provenance par projet |
| **Provenance** | Attribution d'un ContextItem à son projet d'origine dans un ContextBundle fédéré |
| **Score composé** | Score de ranking calculé à partir de signaux normalisés (BM25, symboles, graphe, récence Git, sémantique) agrégés de façon déterministe |
| **Divulgation progressive** | Stratégie des Agent Skills : découverte légère sur frontmatter seul, puis chargement complet uniquement si le skill est sélectionné |
| **Fair floor** | Budget minimal garanti par projet dans une fédération, avant redistribution des tokens non consommés |

## Termes techniques

| Terme | Définition |
|-------|-----------|
| **SQLite canonique** | La base SQLite est la seule source d'état structurel durable ; Lucene peut toujours être reconstruit depuis elle |
| **Index Lucene dérivé** | Index de recherche construit depuis SQLite, reconstructible à tout moment avec `index --rebuild` |
| **SHA-256** | Empreinte cryptographique utilisée pour détecter les changements de fichiers de façon déterministe |
| **Single-flight** | Mécanisme empêchant deux indexations simultanées du même projet (verrou JVM + FileLock OS) |
| **FileLock** | Verrouillage Java (`java.nio.channels.FileLock`) sur `NEXUS_HOME/locks/{uuid}.lock` |
| **Gate READY** | Condition préalable à toute opération de lecture : `indexStatus == READY` |
| **NexusApplication** | Façade applicative (composition root) instanciée et partagée par CLI, REST et MCP |
| **ProjectPathGuard** | Composant vérifiant que chaque accès filesystem reste dans le périmètre du projet |
| **SafeFileIO** | Utilitaire ouvrant les fichiers avec `NOFOLLOW_LINKS` pour le composant final |
| **ExternalTaskRunner** | Exécuteur commun pour les providers externes avec timeout wall-clock et annulation/interruption |
| **HeuristicTokenEstimator** | Estimateur local de tokens, déterministe et remplaçable (ADR-0027) |
| **DeterministicContextRanker** | Implémentation par défaut du ranking, sans appel réseau |
| **SemanticHybridContextRanker** | Ranker opt-in combinant signaux lexicaux et vectoriels (Ollama) |
| **RRF** | Reciprocal Rank Fusion — méthode de fusion des rangs lexicaux et sémantiques |
| **BM25** | Algorithme de ranking probabiliste utilisé par Lucene pour les requêtes textuelles |
| **SCIP** | Semantic Code Intelligence Protocol — format d'index de code externe importé opportunistement |
| **MCP** | Model Context Protocol — protocole JSON-RPC 2.0 d'exposition d'outils aux assistants IA |
| **STDIO** | Standard Input/Output — transport MCP utilisé par NEXUS (pas de transport HTTP côté MCP) |
| **SBOM** | Software Bill of Materials — inventaire des dépendances, format CycloneDX |
| **NEXUS_HOME** | Répertoire de stockage local NEXUS (base SQLite, index Lucene, verrous) |
| **Maven Reactor** | Build multi-module Maven : parent + core + adapters |

## Acronymes

| Acronyme | Forme développée |
|----------|-----------------|
| ADR | Architecture Decision Record |
| MADR | Markdown Architectural Decision Record |
| AST | Abstract Syntax Tree |
| JDT LS | Eclipse JDT Language Server |
| JGit | Eclipse JGit — bibliothèque Java pour Git |
| MCP | Model Context Protocol |
| SBOM | Software Bill of Materials |
| SCIP | Semantic Code Intelligence Protocol |
| RRF | Reciprocal Rank Fusion |
| BM25 | Best Match 25 (algorithme TF-IDF étendu) |
| SHA-256 | Secure Hash Algorithm 256 bits |
| JDBC | Java Database Connectivity |
| REST | Representational State Transfer |
| STDIO | Standard Input / Output |
| CLI | Command-Line Interface |
| IDE | Integrated Development Environment |
| LLM | Large Language Model |
| UUID | Universally Unique Identifier |
| hit@K | Taux de présence du résultat cible dans les K premiers résultats |
| MRR@K | Mean Reciprocal Rank à K |
