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

Les cinq documents pertinents sont absents du top 3 lexical. Avec la stratégie sémantique, quatre sont classés au rang 1 et un au rang 2.

### Coût observé

| Métrique | Baseline | Avec sémantique | Rapport / delta |
|---|---:|---:|---:|
| indexation | 372 ms | 3 188 ms | ~8,57× |
| recherche moyenne | 25,0 ms | 176,6 ms | +151,6 ms / ~7,06× |
| index sémantique | 0 octet | 37 249 octets | +37 249 octets |

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

### Qualité du pipeline hybride observé

| Métrique | Baseline | Hybride sémantique |
|---|---:|---:|
| `precision@3` | 0,0000 | 0,0000 |
| `recall@3` | 0,0000 | 0,0000 |
| `hit@3` | 0,0000 | 0,0000 |
| `MRR@3` | 0,0000 | 0,0000 |

### Coût observé

| Métrique | Baseline | Hybride sémantique | Rapport / delta |
|---|---:|---:|---:|
| indexation complète | 2 073 ms | 68 972 ms | ~33,27× |
| recherche moyenne | 205,0 ms | 308,5 ms | +103,5 ms / ~1,50× |
| index sémantique | 0 octet | 1 052 033 octets | +1 052 033 octets |

La fusion additive initiale n'exploite donc pas correctement le canal sémantique.

## Diagnostic kNN brut versus fusion additive

Le diagnostic dédié sépare retrieval brut et fusion :

| Métrique | kNN brut | Hybride additif |
|---|---:|---:|
| `precision@3` | 0,1667 | 0,0000 |
| `recall@3` | 0,4167 | 0,0000 |
| `hit@3` | 0,5000 | 0,0000 |
| `MRR@3` | 0,3056 | 0,0000 |
| recherche moyenne | 157,0 ms | 346,5 ms |

Les six besoins attendus sont tous retrouvés par le kNN brut dans les 17 premiers résultats, aux rangs `6, 2, 1, 17, 10, 3`.

Le diagnostic tranche donc le problème principal : **le retrieval sémantique contient un signal utile, mais la fusion additive le détruit**.

## Correction — Reciprocal Rank Fusion

La composition sémantique opt-in utilise `SemanticHybridContextRanker` avec une **Reciprocal Rank Fusion (RRF)** déterministe.

Principes :

- le classement historique est calculé sans contribution sémantique ;
- le classement sémantique est ordonné séparément par similarité kNN ;
- les deux listes sont fusionnées à partir de leurs rangs avec `k = 60` ;
- les composantes `baselineRrfScore` et `semanticRrfScore` rendent la fusion explicable ;
- en l'absence de signal sémantique, le ranker historique est délégué tel quel ;
- `NexusApplication.create(paths)` continue d'utiliser uniquement `DeterministicContextRanker` ;
- la RRF n'est utilisée que par la composition sémantique explicitement activée.

## Validation RRF 1:1 sur corpus réel figé

L'incrément RRF a été validé localement :

```text
15 tests
0 échec
0 erreur
BUILD SUCCESS
Fusion semantic RRF      : SUCCESS
Activation create(paths) : DESACTIVEE
```

Le corpus réel est ensuite figé sur le merge final de l'Itération 16 :

```text
CorpusRef = a5d23386fede9b4a4eccf4d5c52308fcd5cae4b1
fichiers  = 236
```

| Métrique | kNN brut | RRF 1:1 |
|---|---:|---:|
| `precision@3` | 0,1667 | 0,0556 |
| `recall@3` | 0,4167 | 0,0833 |
| `hit@3` | 0,5000 | 0,1667 |
| `MRR@3` | 0,3056 | 0,0556 |

La RRF 1:1 améliore la fusion additive mais reste trop dominée par le canal historique.

## Sweep pondéré de la RRF

Validation locale du 21 juillet 2026 :

```text
17 tests
0 échec
0 erreur
BUILD SUCCESS
CorpusRef = a5d23386fede9b4a4eccf4d5c52308fcd5cae4b1
fichiers  = 236
indexation sémantique = 66 413 ms
```

Le sweep réutilise un seul index et mesure les poids `1,00`, `1,25`, `1,50`, `2,00`, `3,00`, `4,00`, `6,00`, `8,00`.

### Résultats agrégés

| Poids sémantique | precision@3 | recall@3 | hit@3 | MRR@3 | recherche moyenne |
|---:|---:|---:|---:|---:|---:|
| 1,00 | 0,0556 | 0,0833 | 0,1667 | 0,0556 | 311,0 ms |
| 1,25 | 0,0556 | 0,0833 | 0,1667 | 0,0556 | 314,3 ms |
| 1,50 | 0,0556 | 0,0833 | 0,1667 | 0,0556 | 308,0 ms |
| 2,00 | 0,0556 | 0,0833 | 0,1667 | 0,0556 | 309,0 ms |
| 3,00 | 0,0556 | 0,0833 | 0,1667 | 0,0556 | 304,8 ms |
| 4,00 | 0,1667 | 0,4167 | 0,5000 | 0,1944 | 314,2 ms |
| 6,00 | 0,1667 | 0,4167 | 0,5000 | 0,2222 | 310,7 ms |
| 8,00 | 0,1667 | 0,4167 | 0,5000 | 0,3056 | 309,7 ms |

Références :

```text
baseline historique : recall@3=0,0000, hit@3=0,0000, MRR@3=0,0000
kNN brut             : recall@3=0,4167, hit@3=0,5000, MRR@3=0,3056
```

### Décision de pondération

Le sweep produit :

```text
smallestWeightMatchingRawRecallAndHit = 4.0
bestObservedWeightByRecallHitMrr      = 8.0
```

Le poids `4,0` est le premier qui restaure le rappel et le hit du kNN brut, mais son `MRR@3 = 0,1944` reste inférieur.

Le poids `8,0` est le seul poids testé qui rejoint simultanément le kNN brut sur les quatre métriques top-3 :

```text
precision@3 = 0,1667
recall@3    = 0,4167
hit@3       = 0,5000
MRR@3       = 0,3056
```

Conformément au critère défini avant la mesure (`recall -> hit -> MRR -> precision`, poids minimal en cas d'égalité), **8,0 devient le poids sémantique RRF par défaut de la capacité opt-in**.

Cette décision ne change pas le comportement par défaut de NEXUS : sans `SemanticSearchConfiguration.enabled(...)`, aucun embedding ni index vectoriel n'est utilisé.

## Corpus réel figé

Les runners réels utilisent par défaut :

```text
CorpusRef = a5d23386fede9b4a4eccf4d5c52308fcd5cae4b1
```

Le code exécuté reste celui de la branche courante, mais le corpus indexé est immuable entre les variantes de ranking.

## État de décision

À ce stade :

- les embeddings démontrent une valeur nette sur le corpus contrôlé ;
- le kNN brut démontre un signal utile sur le repository réel ;
- la fusion additive est rejetée ;
- la RRF pondérée x8 préserve le signal top-3 du kNN brut sur le corpus figé ;
- la capacité reste strictement opt-in et désactivée dans `create(paths)` ;
- le coût d'indexation Ollama reste élevé et doit rester un critère majeur de décision ;
- aucune activation globale par défaut n'est justifiée.

Le dernier palier de l'Itération 17 consiste à relancer le **benchmark A/B réel complet** avec x8 comme valeur opt-in par défaut afin de figer le compromis qualité / indexation / latence / stockage avant la décision finale de conservation et la clôture de l'itération.
