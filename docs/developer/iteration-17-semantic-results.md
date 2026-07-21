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

## Diagnostic kNN brut versus fusion additive

Un diagnostic dédié a ensuite été exécuté le 21 juillet 2026 afin de séparer retrieval brut et fusion.

### Résultat agrégé

| Métrique | kNN brut | Hybride additif |
|---|---:|---:|
| `precision@3` | 0,1667 | 0,0000 |
| `recall@3` | 0,4167 | 0,0000 |
| `hit@3` | 0,5000 | 0,0000 |
| `MRR@3` | 0,3056 | 0,0000 |
| recherche moyenne | 157,0 ms | 346,5 ms |

L'indexation sémantique a pris `67 057 ms` sur 248 fichiers.

Les six besoins attendus sont tous retrouvés par le kNN brut dans les 17 premiers résultats, aux rangs `6, 2, 1, 17, 10, 3`.

Le diagnostic tranche donc le problème principal : **le retrieval sémantique contient un signal utile, mais la fusion additive le détruit**.

Les scores kNN des premiers voisins sont généralement compris entre environ `0,73` et `0,85`. Leur contribution historique était multipliée par un poids `0,15`, tandis que les canaux lexicaux, symboliques, chemin et graphe utilisent d'autres échelles et peuvent cumuler plusieurs contributions. Une addition directe de ces scores n'est donc pas une fusion robuste.

## Correction — Reciprocal Rank Fusion

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

## Validation RRF 1:1 sur corpus réel figé

Le 21 juillet 2026, l'incrément RRF a été validé localement :

```text
15 tests
0 échec
0 erreur
BUILD SUCCESS
Fusion semantic RRF      : SUCCESS
Activation create(paths) : DESACTIVEE
```

Le diagnostic a ensuite été relancé sur le corpus immuable de l'Itération 16 :

```text
CorpusRef = a5d23386fede9b4a4eccf4d5c52308fcd5cae4b1
fichiers  = 236
```

### Qualité RRF avec poids sémantique 1,0

| Métrique | kNN brut | RRF 1:1 |
|---|---:|---:|
| `precision@3` | 0,1667 | 0,0556 |
| `recall@3` | 0,4167 | 0,0833 |
| `hit@3` | 0,5000 | 0,1667 |
| `MRR@3` | 0,3056 | 0,0556 |
| recherche moyenne | 159,0 ms | 376,3 ms |

La RRF 1:1 est donc meilleure que la fusion additive, qui obtenait zéro sur toutes les métriques top 3, mais elle reste très inférieure au retrieval kNN brut.

Les écarts sont particulièrement révélateurs :

- `docs/developer/git-context.md` : kNN rang 1, RRF 1:1 rang 29 ;
- `docs/developer/agent-skills.md` : kNN rang 2, RRF 1:1 rang 20 ;
- ADR SQLite/Lucene : kNN rang 6, RRF 1:1 rang 42 ;
- MCP : kNN rang 16, RRF 1:1 rang 8 ;
- fédération multi-repository : kNN rang 10, RRF 1:1 rang 22 ;
- contexte sous budget : rang 3 dans les deux canaux.

La conclusion est donc plus précise : **le passage à une fusion par rang est correct, mais une pondération 1:1 reste trop favorable au canal historique pour les requêtes à forte divergence lexicale**.

## Sweep pondéré de la RRF

Le poids sémantique est désormais explicite dans `SemanticSearchConfiguration` et `SemanticHybridContextRanker`. La valeur par défaut reste `1,0` tant que la mesure n'a pas retenu un autre ratio.

Le diagnostic teste, sur le même index construit une seule fois :

```text
1,00
1,25
1,50
2,00
3,00
4,00
6,00
8,00
```

Pour chaque ratio, le rapport conserve `precision@3`, `recall@3`, `hit@3`, `MRR@3`, latence et rangs par requête. Il indique également :

- le plus petit poids qui rejoint au minimum le rappel et le hit du kNN brut ;
- le meilleur poids observé selon l'ordre `recall -> hit -> MRR -> precision`, avec préférence pour le poids le plus faible en cas d'égalité.

Ce sweep doit éviter un nouveau réglage intuitif ou arbitraire du ranking.

## Corpus réel figé

Pour éviter qu'un nouveau commit de l'Itération 17 change le corpus à chaque mesure, les runners réels utilisent désormais par défaut le merge de l'Itération 16 :

```text
CorpusRef = a5d23386fede9b4a4eccf4d5c52308fcd5cae4b1
```

Le code exécuté reste celui de la branche courante, mais le **corpus indexé est figé**. Cela permet de comparer les variantes de fusion sur la même base documentaire sans auto-contamination progressive.

## État de décision

À ce stade :

- les embeddings démontrent une valeur nette sur le corpus contrôlé ;
- le kNN brut démontre également un signal utile sur le repository réel ;
- la fusion additive est rejetée ;
- la RRF 1:1 améliore la situation mais ne préserve pas suffisamment le signal sémantique ;
- un sweep pondéré est maintenant prêt pour choisir le ratio à partir des métriques ;
- le coût d'indexation Ollama reste élevé et doit rester un critère majeur de décision ;
- aucune activation par défaut n'est encore justifiée.

La prochaine validation doit exécuter le sweep pondéré sur le corpus figé. La pondération retenue ne sera intégrée comme valeur par défaut de la capacité sémantique opt-in qu'après cette mesure.