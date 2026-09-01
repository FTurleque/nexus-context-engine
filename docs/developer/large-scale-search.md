# Recherche multi-repository et passage à l'échelle

Les documents `iteration-*` conservent les baselines historiques. Ce fichier décrit le contrat courant.

## Portée fédérée

Toutes les surfaces partagent une limite maximale de **100 projets uniques**.

```text
sélecteurs
  ↓
normalisation / déduplication stable
  ↓
FederatedScopePolicy <= 100 uniques
  ↓
résolution + READY
  ↓
SearchService / ContextBuilder par projet
```

La cardinalité est appliquée avant les lookups/readiness afin qu'un scope surdimensionné échoue sans résoudre 101 projets.

## Recherche fédérée

Pour une portée valide :

```text
SearchService(A, localOverfetch)
SearchService(B, localOverfetch)
...
  ↓
tri global déterministe
  ↓
diversification (projectId,path)
  ↓
top-K global
```

Le pool local est borné et la limite publique des résultats est commune à CLI/REST/MCP.

## SQLite et symboles

Les recherches symbole/relation filtrent côté repository avant matérialisation. V005 impose également les invariants de plage directement en SQLite.

## Graphe

Les projections/voisinages sont bornés en nœuds/arêtes et le benchmark vérifie le coût à grande échelle. Les caches dérivés restent invalidés par la génération canonique.

## Contexte fédéré

`FederatedContextService` combine :

- budget final global ;
- budget de travail préparatoire ;
- provenance ;
- fair floor / round-robin / refill ;
- déduplication ;
- sources natives projet-locales.

Le budget de découverte natif de chaque construction est partagé entre instructions, skills, customisations et Git avant le budget final de tokens.

## Scale Benchmark courant

`.github/workflows/scale-benchmark.yml` qualifie :

- SQLite jusqu'aux tiers configurés ;
- portefeuille jusqu'à 100 projets ;
- graphe ;
- budget fédéré ;
- découverte native filesystem avec 1 000 skills.

Les rapports sont des preuves du SHA qui les a produits, pas des garanties intemporelles.

## Moteurs externes

Zoekt/OpenGrok/OpenSearch, FTS supplémentaire, vector DB, cache Git persistant ou lifecycle Lucene partagé ne sont introduits qu'après mesure reproductible démontrant un besoin réel.
