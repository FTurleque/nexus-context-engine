# Recherche sémantique optionnelle

## Statut

La capacité sémantique a été livrée à l'Itération 17 et reste **strictement opt-in**. Phase 6 rend son activation opérationnelle identique pour CLI, REST et MCP et réduit le coût d'indexation via batching.

Sans configuration :

```java
NexusApplication.create(paths)
```

résout `SemanticSearchConfiguration.fromEnvironment()` en mode désactivé. Aucun provider d'embeddings ni index sémantique n'est créé.

## Activation commune

```powershell
$env:NEXUS_SEMANTIC_PROVIDER = "ollama"
```

Variables :

```text
NEXUS_SEMANTIC_PROVIDER              ollama | disabled/off
NEXUS_SEMANTIC_RRF_WEIGHT            8.0 par défaut, <= 10
NEXUS_OLLAMA_BASE_URL                http://localhost:11434 par défaut
NEXUS_OLLAMA_EMBEDDING_MODEL         qwen3-embedding:0.6b par défaut
NEXUS_OLLAMA_EMBEDDING_DIMENSIONS    1024 par défaut
NEXUS_OLLAMA_TIMEOUT_SECONDS         60 par défaut
```

L'activation programmable reste disponible avec `SemanticSearchConfiguration.enabled(...)` pour les tests/intégrations spécialisées.

## Pipeline

```text
indexation
SearchDocument[]
  ↓ lots de 32 par défaut
EmbeddingProvider.embedAll(...)
  ↓
SemanticSearchIndex (Lucene dérivé)

recherche
lexical + symbole + graphe/Git + sémantique
  ↓
SemanticHybridContextRanker
  ↓ RRF k=60, poids sémantique 8 par défaut
résultats explicables
```

`EmbeddingProvider.embedAll` possède un fallback séquentiel pour les providers existants. `OllamaEmbeddingProvider` l'implémente réellement en envoyant plusieurs textes dans une requête `/api/embed`.

## Baseline de décision

Corpus hermétique historique : 236 fichiers, 946 symboles, 1 539 relations, 6 requêtes.

```text
baseline lexical top-3 : tous les indicateurs sémantiques à zéro
semantic precision@3  : 0,1667
semantic recall@3     : 0,4167
semantic hit@3        : 0,5000
semantic MRR@3        : 0,3056
indexation            : 1 943 ms → 64 332 ms (~33,11×)
recherche             : 208,8 ms → 298,7 ms (~1,43×)
```

Cette baseline justifie toujours :

- sémantique désactivé par défaut ;
- pas de vector DB ;
- pas de promotion automatique du sémantique en moteur principal ;
- optimisation mesurée avant toute complexification supplémentaire.

Phase 6 implémente le batching, mais une nouvelle mesure locale est nécessaire avant de déclarer le coût réduit de manière chiffrée.

## Correctness

Le mode sémantique respecte les mêmes gates `READY` que le lexical. Si une indexation sémantique échoue, le projet ne doit pas être servi comme cohérent ; la prochaine indexation d'un état non-READY force un rebuild complet.
