# Passes de jetons à préfixe de la redaction (2026-11-11)

Relevé de `AUD-PRF-30` (audit du 2026-10-17, changement OpenSpec `sprint-14-redaction-jwt-et-xml`, tâches 4.1 et 4.2). Banc : `core/src/test/java/com/nexus/benchmark/RedactionThroughputBenchmarkTest.java`, décrit dans [le pipeline d'indexation](../../developer/indexing-pipeline.md#reproduire-les-mesures).

## Conditions

- Poste de développement Windows 10, JDK de la build Maven du dépôt, politique `secret-redaction-v9` ; médiane de 7 exécutions après 3 tours de chauffe.
- Corpus : 1 Mo de code et de configuration ordinaires sans secret (1 048 788 caractères, un seul document), puis le corpus synthétique d'`IndexingHeapPeakBenchmarkTest` (2 000 fichiers Java, 3 643 630 caractères, un appel de `redact` par fichier).
- Seuil de décision du sprint : livrer le pré-filtre si le temps qu'il retire dépasse 25 % du temps de `redact`.

## Avant le pré-filtre

| Corpus | `redact` | 9 passes sans filtre | 9 passes pré-filtrées | Part des passes dans `redact` | Gain du pré-filtre (`prefilterGainRatio`) |
|---|---|---|---|---|---|
| Code ordinaire, 1 Mo | 392 ms | 192 ms | 17 ms | 49 % | 45 % |
| Corpus synthétique | 1 268 ms | 539 ms | 5 ms | 43 % | 42 % |

Le gain est le temps que le pré-filtre retire des passes, rapporté au temps de `redact` mesuré, comme le champ `prefilterGainRatio` du banc. Il dépasse le seuil de 25 % sur les deux corpus : le pré-filtre est livré.

## Après le pré-filtre

Mesure prise avec le préfixe `gh` pour la famille GitHub ; la version livrée filtre sur `ghp_`, `gho_`, `ghu_`, `ghs_` et `ghr_`, plus sélectifs.

| Corpus | `redact` | 9 passes sans filtre | 9 passes pré-filtrées |
|---|---|---|---|
| Code ordinaire, 1 Mo | 215 ms | 215 ms | 22 ms |
| Corpus synthétique | 769 ms | 996 ms | 8 ms |

`redact` passe de 392 à 215 ms sur le code ordinaire et de 1 268 à 769 ms sur le corpus synthétique. Les durées des passes sans filtre varient d'une exécution à l'autre sur ce poste chargé ; seules les tendances comptent. Le banc vérifie à chaque exécution que les passes pré-filtrées rendent le même texte que les passes sans filtre, et `SensitiveContentRedactorTest#structuredTokenPrefixFilterKeepsEveryPassResult` le vérifie sur la sonde de 35 cas et sur les cas de bornes.
