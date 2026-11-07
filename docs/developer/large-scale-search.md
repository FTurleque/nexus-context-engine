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

La limite opérationnelle est donc de **128 termes analysés uniques** avant expansion multi-champs. Le `MultiFieldQueryParser` développe ensuite ces termes sur cinq champs ; cette borne conserve une marge sous la limite Lucene par défaut de 1 024 clauses imbriquées, en incluant la coordination externe.

Un test de non-régression utilise une requête de 1 500 termes et vérifie qu'elle ne déclenche pas `TooManyClauses`.

## SQLite et symboles

Les connexions utilisent `temp_store=MEMORY` pour éviter le coût des temporaires
sur disque pendant les insertions avec triggers. Le journal canonique et la
durabilité restent inchangés. La mémoire temporaire native dépend du travail SQL
et n'est pas comptée par le gate de heap Java ; voir [ADR-0050](../architecture/adr/DATA-0050-conserver-les-temporaires-sqlite-en-memoire.md).

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

## Vecteur de requête unique et échéance globale

Une requête fédérée (recherche ou contexte) crée un `SearchQueryContext` pour toute la requête et le transmet aux stratégies de chaque projet. `SemanticSearchStrategy` y mémorise le vecteur de la requête **redactée** : le fournisseur d'embeddings reçoit au plus un appel `embedQuery`, quel que soit le nombre de projets, et zéro si aucun projet n'a d'index sémantique compatible. Une indisponibilité du fournisseur est mémorisée de la même façon : un seul appel est tenté et chaque projet porte le même diagnostic de dégradation. Le contexte n'est pas conservé entre requêtes.

Une échéance globale (`FederationDeadlinePolicy`, [BE-0064](../architecture/adr/BE-0064-borner-la-federation-par-une-echeance-globale-sans-parallelisme.md)) est vérifiée avant chaque projet, dans l'ordre du scope, sans parallélisme :

| Paramètre | Valeur |
|---|---|
| `NEXUS_FEDERATION_DEADLINE_SECONDS` | entier de 1 à 600, défaut 60 ; toute autre valeur est refusée |
| Projet commencé | jamais interrompu |
| Délai d'embedding | `min(timeout du fournisseur, temps restant)`, au moins 1 ms |

Les projets non commencés à l'échéance ne sont pas servis et sont nommés, dans l'ordre du scope, par `unservedProjects` (identifiant et nom) : composante des réponses fédérées `FederatedSearch` et `FederatedContext` de la projection publique, donc identique en CLI (clé JSON et ligne « Projets non servis »), REST et MCP. La liste est vide, et les résultats identiques à ceux d'une exécution sans échéance, lorsque tous les projets sont traités à temps. En mode `explain`, le contexte fédéré ajoute une exclusion « projet non servi » par projet concerné. Les tests injectent une horloge (`InstantSource`) et n'affirment rien sur la durée réelle.

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
