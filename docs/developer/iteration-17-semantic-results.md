# Résultats de l'Itération 17 — Recherche sémantique optionnelle

Ce document conserve les mesures et la décision finale de l'Itération 17. La recherche sémantique reste une capacité explicitement opt-in : `NexusApplication.create(paths)` conserve le moteur historique sans embeddings.

## Configuration de référence

```text
provider   = Ollama local
model      = qwen3-embedding:0.6b
dimensions = 1024
endpoint   = http://localhost:11434
RRF k      = 60
poids RRF sémantique retenu = 8,0
```

L'index vectoriel Lucene est dérivé et reconstructible. SQLite reste canonique.

## Palier 1 — corpus contrôlé à divergence de vocabulaire

Validation du 21 juillet 2026 sur 8 documents et 5 requêtes formulées avec un vocabulaire différent du document pertinent.

| Métrique | Baseline | Sémantique |
|---|---:|---:|
| `precision@3` | 0,0000 | 0,3333 |
| `recall@3` | 0,0000 | 1,0000 |
| `hit@3` | 0,0000 | 1,0000 |
| `MRR@3` | 0,0000 | 0,9000 |

Quatre documents pertinents sont classés au rang 1 et un au rang 2.

Coût observé :

| Métrique | Baseline | Sémantique |
|---|---:|---:|
| indexation | 372 ms | 3 188 ms |
| recherche moyenne | 25,0 ms | 176,6 ms |
| index sémantique | 0 octet | 37 249 octets |

Ce palier démontre que les embeddings peuvent résoudre une divergence lexicale que le moteur historique ne couvre pas.

## Palier 2 — diagnostic sur repository réel

La première fusion additive a été rejetée : elle additionnait directement des signaux BM25/symboliques/graphe et cosine dont les échelles ne sont pas comparables.

Diagnostic kNN brut versus fusion additive :

| Métrique | kNN brut | Hybride additif |
|---|---:|---:|
| `precision@3` | 0,1667 | 0,0000 |
| `recall@3` | 0,4167 | 0,0000 |
| `hit@3` | 0,5000 | 0,0000 |
| `MRR@3` | 0,3056 | 0,0000 |

Les six besoins du corpus réel sont retrouvés par le kNN brut dans le top 17, aux rangs `6, 2, 1, 17, 10, 3`.

Conclusion : le signal vectoriel est utile ; le défaut provenait principalement de la fusion.

## Correction — Reciprocal Rank Fusion

`SemanticHybridContextRanker` fusionne désormais deux classements séparés :

- canal historique calculé sans contribution sémantique ;
- canal vectoriel classé par similarité kNN ;
- fusion RRF déterministe avec `k = 60` ;
- composantes explicables `baselineRrfScore` et `semanticRrfScore` ;
- délégation exacte au ranker historique en l'absence de signal sémantique.

La RRF n'est utilisée que lorsque `SemanticSearchConfiguration` est explicitement activée.

## Corpus réel figé

Pour rendre les mesures comparables entre corrections de l'Itération 17, le corpus réel est figé sur le merge final de l'Itération 16 :

```text
CorpusRef = a5d23386fede9b4a4eccf4d5c52308fcd5cae4b1
fichiers  = 236
symboles  = 946
relations = 1 539
requêtes  = 6
```

Le code benchmarké reste celui de la branche courante, mais le contenu indexé ne varie plus.

## Sweep pondéré de la RRF

Le sweep a mesuré les poids sémantiques `1,00`, `1,25`, `1,50`, `2,00`, `3,00`, `4,00`, `6,00`, `8,00` sur un index sémantique construit une seule fois.

| Poids | precision@3 | recall@3 | hit@3 | MRR@3 | recherche moyenne |
|---:|---:|---:|---:|---:|---:|
| 1,00 | 0,0556 | 0,0833 | 0,1667 | 0,0556 | 311,0 ms |
| 1,25 | 0,0556 | 0,0833 | 0,1667 | 0,0556 | 314,3 ms |
| 1,50 | 0,0556 | 0,0833 | 0,1667 | 0,0556 | 308,0 ms |
| 2,00 | 0,0556 | 0,0833 | 0,1667 | 0,0556 | 309,0 ms |
| 3,00 | 0,0556 | 0,0833 | 0,1667 | 0,0556 | 304,8 ms |
| 4,00 | 0,1667 | 0,4167 | 0,5000 | 0,1944 | 314,2 ms |
| 6,00 | 0,1667 | 0,4167 | 0,5000 | 0,2222 | 310,7 ms |
| 8,00 | 0,1667 | 0,4167 | 0,5000 | 0,3056 | 309,7 ms |

Le harness produit :

```text
smallestWeightMatchingRawRecallAndHit = 4.0
bestObservedWeightByRecallHitMrr      = 8.0
```

Conformément au critère défini avant la mesure (`recall -> hit -> MRR -> precision`, poids minimal en cas d'égalité), **8,0 est retenu comme poids RRF sémantique par défaut de la capacité opt-in**.

Cette valeur reste surchargeable explicitement. Elle n'active pas la recherche sémantique à elle seule.

## Palier final — benchmark A/B réel avec RRF x8

Validation exécutée localement le 21 juillet 2026 sur le corpus figé.

### Qualité

| Métrique | Baseline historique | Sémantique RRF x8 | Delta |
|---|---:|---:|---:|
| `precision@3` | 0,0000 | 0,1667 | +0,1667 |
| `recall@3` | 0,0000 | 0,4167 | +0,4167 |
| `hit@3` | 0,0000 | 0,5000 | +0,5000 |
| `MRR@3` | 0,0000 | 0,3056 | +0,3056 |

Sur les six requêtes :

- `agent-skills.md` est classé rang 3 ;
- `git-context.md` est classé rang 1 ;
- `context-building.md` est classé rang 2 ;
- les trois autres cibles restent hors top 3, tout en étant présentes dans le retrieval vectoriel plus profond observé pendant le diagnostic.

La RRF x8 préserve ainsi exactement les quatre métriques top-3 du kNN brut sur ce corpus, tout en conservant le canal historique dans la fusion.

### Coût réel

| Métrique | Baseline | Sémantique RRF x8 | Rapport / delta |
|---|---:|---:|---:|
| indexation complète | 1 943 ms | 64 332 ms | ~33,11× |
| recherche moyenne | 208,8 ms | 298,7 ms | +89,8 ms / ~1,43× |
| index sémantique | 0 octet | 1 001 537 octets | +1 001 537 octets |

Le coût principal est donc l'indexation des embeddings avec le provider Ollama de référence. La surcharge de recherche reste nettement plus modérée que la surcharge d'indexation.

## Décision de l'Itération 17

La recherche sémantique satisfait le critère d'adoption de l'itération : elle apporte un gain mesurable sur les requêtes où le vocabulaire diverge de celui des documents pertinents.

Décision retenue :

- **conserver la recherche sémantique comme capacité locale optionnelle validée** ;
- conserver `NexusApplication.create(paths)` comme chemin global par défaut, sans embeddings ;
- utiliser RRF `k = 60` avec poids sémantique `8,0` comme configuration par défaut uniquement lorsqu'un caller active explicitement la capacité ;
- conserver `qwen3-embedding:0.6b` via Ollama comme baseline locale mesurée, sans rendre ce provider obligatoire ;
- ne pas introduire de base vectorielle externe : Lucene suffit pour le périmètre mesuré ;
- ne pas activer automatiquement les embeddings lors de l'indexation standard, le coût observé d'environ `33×` ne le justifie pas ;
- recommander cette capacité lorsque la divergence lexicale ou la recherche conceptuelle justifie explicitement ce coût ;
- conserver la possibilité de remplacer le provider ou la stratégie de représentation derrière les abstractions existantes.

La capacité n'est donc ni supprimée, ni promue au chemin universel : elle devient un **mode opt-in mesuré et explicable**.

## Validation finale avant fusion

Validation complète exécutée localement le 21 juillet 2026 sur la tête finale de la branche :

```text
mvn clean install : SUCCESS
73 tests
0 échec
0 erreur
5 harness opt-in ignorés
BUILD SUCCESS en 16,571 s

SELF-SMOKE SUCCESS
257 fichiers indexés
1 431 symboles
9 957 relations
indexation complète : 2 271 ms
indexation incrémentale sans changement : 657 ms
recherche explicable : 727 ms
contexte strict : 107/180 tokens en 885 ms
contexte multi-source : 1 199/1 200 tokens en 1 051 ms
contexte avec skill : 1 199/1 200 tokens en 1 123 ms
contexte Git : 1 588/1 600 tokens en 1 084 ms
réduction du contexte candidat strict : 99,47 %

validation ciblée Itération 17 : SUCCESS
17 tests
0 échec
0 erreur
Fusion semantic RRF : SUCCESS
Composition application : OPT-IN VALIDEE
Corpus golden historique : SUCCESS
Corpus golden fédéré : SUCCESS
Activation create(paths) : DESACTIVEE
```

Les avertissements SLF4J, native access, Vector API et Maven Shade observés restent non bloquants et n'ont provoqué aucun échec.

**Critère de sortie : validé.** L'Itération 17 peut être fusionnée : la recherche sémantique apporte un gain mesurable sur les requêtes conceptuelles, reste strictement opt-in, conserve le chemin historique sans embeddings et n'introduit ni fournisseur obligatoire ni base vectorielle externe.