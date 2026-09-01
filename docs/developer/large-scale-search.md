# Recherche multi-repository et passage à l'échelle

Les documents `iteration-*` conservent les baselines historiques. Ce fichier décrit le contrat courant après NXA3 + NXA4.

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

Le pool local est borné et la limite publique des résultats est commune à CLI/REST/MCP via `ResultLimitPolicy`.

## Requêtes Lucene à forte cardinalité

`LuceneSearchIndex` analyse les termes uniques de la requête puis s'arrête à :

```text
MAX_ANALYZED_QUERY_TERMS = 128
```

Le `MultiFieldQueryParser` développe ensuite ces termes sur cinq champs. La borne 128 conserve une marge sous la limite Lucene par défaut de 1 024 clauses imbriquées, en incluant la coordination externe.

Un test de non-régression utilise une requête de 1 500 termes et vérifie qu'elle ne déclenche pas `TooManyClauses`.

## SQLite et symboles

Les recherches symbole/relation filtrent côté repository avant matérialisation. V005 impose également :

```text
start_line >= 1
end_line >= start_line
```

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

Les limites REST de résultats et de budget contexte fédérés sont alignées sur les politiques centrales ; le REST ne peut pas demander un plafond supérieur au cœur.

## Providers externes et scale

Les providers/importers externes utilisent `ExternalTaskRunner` avec timeout et maximum **8 workers réellement actifs**. Un provider qui ignore l'interruption conserve sa place de capacité jusqu'à sa terminaison réelle.

JDT LS borne en plus son framing à 16 MiB par message, 64 KiB de headers cumulés, 8 KiB par ligne et 256 messages en attente.

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
