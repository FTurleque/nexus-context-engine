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
- aucune mesure n'a encore été réalisée sur le corpus réel NEXUS ;
- le comportement sur un ensemble mêlant code, ADR, documentation et tests doit encore être observé.

## Palier 2 — snapshot réel NEXUS

État : **à mesurer**.

Le prochain benchmark utilise un snapshot Git hermétique du repository NEXUS, exclut les artefacts propres à l'Itération 17 afin d'éviter l'auto-contamination, puis compare la même baseline avec et sans embeddings sur des requêtes paraphrasées visant des documents réels.

Le critère de décision restera le même : gain de qualité mesurable versus coût d'indexation, latence, stockage et confidentialité.
