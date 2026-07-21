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

### Résultats par requête

| Besoin exprimé | Document attendu | Baseline | Sémantique |
|---|---|---:|---:|
| éviter que plusieurs appelants martèlent le stockage pour la même absence | `docs/cache-stampede.md` | hors top 3 | rang 1 |
| isoler les règles métier des frameworks et bases de données | `docs/hexagonal-boundaries.md` | hors top 3 | rang 2 |
| faire tenir les preuves les plus utiles dans la limite d'entrée du modèle | `docs/token-budget.md` | hors top 3 | rang 1 |
| éviter de rescanner tout le projet après deux fichiers modifiés | `docs/incremental-index.md` | hors top 3 | rang 1 |
| empêcher l'envoi de code propriétaire vers des services hébergés | `docs/local-privacy.md` | hors top 3 | rang 1 |

## Interprétation du palier 1

Ce résultat démontre une **valeur fonctionnelle réelle** de la recherche sémantique sur les requêtes où le vocabulaire diverge fortement : la baseline ne retrouve aucun document pertinent dans le top 3 alors que les embeddings couvrent les cinq cas.

Ce palier ne suffit toutefois pas à recommander l'activation par défaut :

- le corpus est contrôlé et très petit ;
- le coût d'indexation est significativement supérieur ;
- la recherche moyenne est plus lente ;
- le comportement sur un ensemble mêlant code, ADR, documentation et tests doit être observé.

## Palier 2 — snapshot réel NEXUS

Validation exécutée localement le 21 juillet 2026 sur le snapshot hermétique :

```text
commit     = 20c091b49a402ad787b95055a09af74e945ba6b8
provider   = Ollama local
model      = qwen3-embedding:0.6b
dimensions = 1024
endpoint   = http://localhost:11434
fichiers   = 248
symboles   = 1 028
relations  = 1 658
requêtes   = 6
```

Le snapshot a été construit par `git archive HEAD` puis débarrassé des artefacts de benchmark de l'Itération 17. Il ne contient donc ni `.git`, ni `index.scip` local, ni contenu non versionné opportuniste.

### Qualité du pipeline hybride observé

| Métrique | Baseline | Hybride avec sémantique | Delta |
|---|---:|---:|---:|
| `precision@3` | 0,0000 | 0,0000 | 0,0000 |
| `recall@3` | 0,0000 | 0,0000 | 0,0000 |
| `hit@3` | 0,0000 | 0,0000 | 0,0000 |
| `MRR@3` | 0,0000 | 0,0000 | 0,0000 |

Sur les six requêtes paraphrasées, aucun document déclaré pertinent n'entre dans le top 3, ni dans la baseline ni dans le classement hybride actuel.

### Coût observé

| Métrique | Baseline | Hybride sémantique | Rapport / delta |
|---|---:|---:|---:|
| indexation complète | 2 073 ms | 68 972 ms | ~33,27× |
| recherche moyenne | 205,0 ms | 308,5 ms | +103,5 ms / ~1,50× |
| index sémantique | 0 octet | 1 052 033 octets | +1 052 033 octets |

Le coût d'indexation devient donc substantiel sur un repository réel, même avec seulement 248 fichiers. La surcharge de recherche est plus modérée que sur le petit corpus contrôlé, mais reste mesurable.

### Top 3 observé

Les résultats hybrides restent majoritairement dominés par des fichiers de code et de test déjà favorisés par les signaux lexicaux, symboliques, chemin et graphe. Par exemple :

- la requête sur SQLite/Lucene retourne des tests Agent Skills et `ProjectIndexingServiceTest` ;
- la requête sur la divulgation progressive retourne les tests des providers de skills ;
- la requête MCP retourne `NexusMcpTools` et le test d'intégration MCP ;
- la requête multi-repository retourne notamment `FederatedSearchServiceIntegrationTest` et `NexusApplication` ;
- la requête de budget de contexte retourne notamment `AgentSkillsIntegrationTest` et l'ADR sur les fragments symboliques.

## Diagnostic nécessaire avant décision

Le résultat `semanticRank = 0` du premier harness réel signifie **hors top 3 hybride**. Il ne permet pas de distinguer :

1. un échec du modèle / de la représentation documentaire : le document pertinent n'est pas bien classé dans le kNN brut ;
2. un échec de fusion : le kNN récupère le bon document, mais le poids `semanticScore` est insuffisant face aux signaux historiques.

Il serait donc incorrect de modifier immédiatement les poids du ranking ou de rejeter les embeddings sur la seule base du top 3 hybride.

Un diagnostic dédié est ajouté :

```text
RealSemanticRetrievalDiagnosticTest
scripts/measure-iteration-17-real-semantic-diagnostic.ps1
```

Il mesure séparément :

- le rang kNN brut à 3 et à 50 ;
- le rang hybride à 3 et à 50 ;
- les dix premiers voisins kNN avec leur score ;
- les dix premiers résultats hybrides ;
- `precision@3`, `recall@3`, `hit@3` et `MRR@3` pour chaque étage.

Le test lui-même et son runner sont exclus du snapshot afin de préserver l'herméticité du corpus.

## État de décision

À ce stade :

- le palier contrôlé démontre que les embeddings peuvent résoudre une forte divergence de vocabulaire ;
- le pipeline hybride actuel ne produit **aucun gain top 3** sur le snapshot réel NEXUS ;
- le coût d'indexation réel est élevé (~33× sur ce run) ;
- aucune activation par défaut n'est justifiée ;
- aucune modification des poids du ranking n'est encore justifiée sans connaître le comportement du kNN brut.

La prochaine décision dépend donc du diagnostic kNN brut versus fusion hybride. Si le kNN brut échoue également, il faudra travailler la représentation des documents, le chunking ou le modèle. S'il réussit mais que l'hybride échoue, le problème se situe dans la stratégie de fusion/ranking.
