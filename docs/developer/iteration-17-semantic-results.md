# Résultats de l'Itération 17 — Recherche sémantique optionnelle

Ce document conserve les mesures obtenues pendant l'évaluation de la recherche sémantique. La capacité reste opt-in tant que les paliers de validation ne sont pas terminés.

## Palier 1 — corpus contrôlé à divergence de vocabulaire

Validation exécutée localement le 21 juillet 2026 avec :

```text
provider   = Ollama local
model      = qwen3-embedding:0.6b
dimensions = 1024
endpoint   = http://localhost:11434
corpus     = 8 documents
requêtes   = 5
```

Le corpus est volontairement construit pour que la requête exprime le besoin avec un vocabulaire différent de celui du document pertinent.

### Qualité

| Métrique | Baseline lexical/symbolique/graphe | Avec sémantique | Delta |
|---|---:|---:|---:|
| `precision@3` | 0,0000 | 0,3333 | +0,3333 |
| `recall@3` | 0,0000 | 1,0000 | +1,0000 |
| `hit@3` | 0,0000 | 1,0000 | +1,0000 |
| `MRR@3` | 0,0000 | 0,9000 | +0,9000 |

Les cinq documents pertinents sont absents du top 3 lexical. Avec la stratégie sémantique :

- quatre requêtes classent le document pertinent au rang 1 ;
- une requête le classe au rang 2 ;
- les cinq requêtes ont donc un résultat pertinent dans le top 3.

### Coût observé

| Métrique | Baseline | Avec sémantique | Rapport / delta |
|---|---:|---:|---:|
| indexation | 372 ms | 3 188 ms | ~8,57× |
| recherche moyenne | 25,0 ms | 176,6 ms | +151,6 ms / ~7,06× |
| index sémantique | 0 octet | 37 249 octets | +37 249 octets |

La hausse de coût est nette mais reste mesurée sur un corpus minuscule ; ces rapports ne doivent pas être extrapolés directement à un repository réel.

## Palier 2 — snapshot réel NEXUS, fusion additive initiale

Validation exécutée localement le 21 juillet 2026 sur un snapshot hermétique issu du commit `20c091b49a402ad787b95055a09af74e945ba6b8` :

```text
provider   = Ollama local
model      = qwen3-embedding:0.6b
dimensions = 1024
endpoint   = http://localhost:11434
fichiers   = 248
symboles   = 1 028
relations  = 1 658
requêtes   = 6
```

Le snapshot a été construit par `git archive` puis débarrassé des artefacts de benchmark de l'Itération 17. Il ne contient donc ni `.git`, ni `index.scip` local, ni contenu non versionné opportuniste.

### Qualité du pipeline hybride observé

| Métrique | Baseline | Hybride sémantique | Delta |
|---|---:|---:|---:|
| `precision@3` | 0,0000 | 0,0000 | 0,0000 |
| `recall@3` | 0,0000 | 0,0000 | 0,0000 |
| `hit@3` | 0,0000 | 0,0000 | 0,0000 |
| `MRR@3` | 0,0000 | 0,0000 | 0,0000 |

### Coût observé

| Métrique | Baseline | Hybride sémantique | Rapport / delta |
|---|---:|---:|---:|
| indexation complète | 2 073 ms | 68 972 ms | ~33,27× |
| recherche moyenne | 205,0 ms | 308,5 ms | +103,5 ms / ~1,50× |
| index sémantique | 0 octet | 1 052 033 octets | +1 052 033 octets |

Ce résultat prouve que la fusion additive initiale n'exploite pas correctement le canal sémantique sur le corpus réel. Il ne permet toutefois pas, à lui seul, de conclure que le retrieval vectoriel est mauvais.

## Diagnostic kNN brut versus fusion hybride

Un diagnostic dédié a ensuite été exécuté le 21 juillet 2026 sur le snapshot `b7746cc705caaaceed2de891a8cd78dd4080450d` afin de séparer retrieval brut et fusion.

### Résultat agrégé

| Métrique | kNN brut | Hybride additif |
|---|---:|---:|
| `precision@3` | 0,1667 | 0,0000 |
| `recall@3` | 0,4167 | 0,0000 |
| `hit@3` | 0,5000 | 0,0000 |
| `MRR@3` | 0,3056 | 0,0000 |
| recherche moyenne | 157,0 ms | 346,5 ms |

L'indexation sémantique a pris `67 057 ms` sur 248 fichiers.

### Rangs des documents déclarés pertinents

Les six besoins attendus sont tous retrouvés par le kNN brut dans les 17 premiers résultats :

| Besoin | Rang kNN brut | Rang hybride observé |
|---|---:|---:|
| SQLite/Lucene dérivé reconstructible | 6 | hors top 50 |
| divulgation progressive des Agent Skills | 2 | hors top 50 |
| contexte Git local, offline et read-only | 1 | hors top 50 |
| adaptateur MCP STDIO | 17 | 9 |
| fédération multi-repository avec provenance | 10 | hors top 50 |
| sélection du contexte sous budget | 3 | 22 |

Le diagnostic tranche donc le problème principal : **le retrieval sémantique contient un signal utile, mais la fusion additive le détruit**.

Les scores kNN des premiers voisins sont généralement compris entre environ `0,73` et `0,85`. Leur contribution historique était multipliée par un poids `0,15`, tandis que les canaux lexicaux, symboliques, chemin et graphe utilisent d'autres échelles et peuvent cumuler plusieurs contributions. Une addition directe de ces scores n'est donc pas une fusion robuste.

## Correction retenue — Reciprocal Rank Fusion

La composition sémantique opt-in utilise désormais `SemanticHybridContextRanker` avec une **Reciprocal Rank Fusion (RRF)** déterministe.

Principes :

- le classement historique est calculé sans contribution sémantique ;
- le classement sémantique est ordonné séparément par similarité kNN ;
- les deux listes sont fusionnées à partir de leurs rangs, avec `k = 60` ;
- les composantes `baselineRrfScore` et `semanticRrfScore` rendent la fusion explicable ;
- en l'absence de signal sémantique, le ranker historique est délégué tel quel ;
- `NexusApplication.create(paths)` continue d'utiliser uniquement `DeterministicContextRanker` ;
- la RRF n'est utilisée que par `NexusApplication.create(paths, SemanticSearchConfiguration.enabled(...))`.

Cette correction ne modifie donc pas le comportement par défaut de NEXUS.

## Corpus réel figé pour les comparaisons suivantes

Pour éviter qu'un nouveau commit de l'Itération 17 change le corpus à chaque mesure, les runners réels utilisent désormais par défaut le merge de l'Itération 16 :

```text
CorpusRef = a5d23386fede9b4a4eccf4d5c52308fcd5cae4b1
```

Le code exécuté reste celui de la branche courante, mais le **corpus indexé est figé**. Cela permet de comparer l'ancienne fusion et la RRF sur la même base documentaire sans auto-contamination progressive.

## État de décision

À ce stade :

- les embeddings démontrent une valeur nette sur le corpus contrôlé ;
- le kNN brut retrouve les six cibles réelles dans le top 17 et trois requêtes sur six ont déjà une cible pertinente dans le top 3 brut ;
- l'échec du premier pipeline réel provient principalement de la stratégie de fusion additive ;
- la RRF est maintenant implémentée uniquement en mode opt-in ;
- le coût d'indexation Ollama reste élevé et doit rester un critère majeur de décision ;
- aucune activation par défaut n'est encore justifiée.

La prochaine validation doit exécuter le diagnostic et le benchmark réel sur le corpus figé afin de mesurer objectivement la RRF avant la décision finale de l'Itération 17.
