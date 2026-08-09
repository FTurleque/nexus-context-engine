# Scénarios de qualité — NEXUS Context Engine

Ce fichier complète la Section 10 de l'arc42 avec les scénarios de qualité **courants** et leur couverture.

## Couverture par scénario

| Scénario | Type | Couverture principale | Statut |
|---|---|---|---|
| QS-01 — Déterminisme ranking | Usage | tests contexte/ranking + self-smoke | Couvert |
| QS-02 — Confinement filesystem | Défaillance | tests adversariaux symlink / `SafeFileIO` | Couvert / qualifié |
| QS-03 — Exclusion mutuelle inter-processus | Défaillance | tests `FileLock` + mutex JVM | Couvert / qualifié |
| QS-04 — Budget de tokens respecté | Usage | `BudgetedContextSelector` | Couvert |
| QS-05 — Démarrage CLI | Usage | self-smoke distribution | Couvert |
| QS-06 — Provider/importer en timeout | Défaillance | `ExternalTaskRunner` | Couvert / qualifié |
| QS-07 — Qualité de recherche | Changement | `SearchQualityMetrics` + baseline | Couvert |
| QS-08 — Ajout provider langage | Changement | revue + tests du provider | Non automatisé globalement |
| QS-09 — SQLite corrompu | Défaillance | runbook recovery | Runbook |
| QS-10 — Fédération sous budget serré | Usage | fair floor/refill + budget de travail | Couvert / qualifié |
| QS-11 — Ollama indisponible | Défaillance | recovery à renforcer | Watch item #54 |
| QS-12 — Lucene physiquement corrompu | Défaillance | recovery à renforcer | Watch item #54 |
| QS-14 — Sécurité REST distante | Sécurité | gardes token/roots/exposure-mode | Couvert / qualifié via #49 |
| QS-15 — Mutation MINOS concurrente | Défaillance | verrou de mutation JVM + OS | Couvert |
| QS-16 — Snapshot externe obsolète | Défaillance | invalidation provenance | Couvert / qualifié |
| QS-17 — Index sémantique incompatible | Défaillance | provenance Lucene | Couvert / qualifié |
| QS-18 — `NEXUS_HOME` réseau/hostile | Défaillance | qualification dédiée | Watch item #52 |
| QS-19 — Supply-chain reactor | Sécurité | JaCoCo + OSV + CodeQL + notices/SBOM | Couvert / qualifié |
| QS-20 — Licence atypique | Gouvernance | revue explicite | Watch item #55 |
| QS-21 — Mutation repository pendant indexation | Correctness | revalidation snapshot avant READY | Couvert / qualifié via #49 |
| QS-22 — Graphe borné | Performance | projections SQL + Scale Benchmark | Couvert / qualifié via #49 |
| QS-23 — Contexte fédéré borné en travail | Performance | budget de travail + tests/benchmark | Couvert / qualifié via #49 |
| QS-24 — Limite résultats cross-surface | Correctness | `ResultLimitPolicy` CLI/REST/MCP | Couvert / qualifié via #49 |
| QS-25 — Supply-chain image Docker | Sécurité | Trivy + SBOM + attestations | Couvert / qualifié via #49 |

## Critères supply-chain actifs

- régression de couverture core sous 70 % lignes ou 50 % branches ⇒ build en échec ;
- nouvelle vulnérabilité introduite en PR ⇒ gate OSV delta ;
- vulnérabilité dans le SBOM CycloneDX agrégé du reactor ⇒ gate OSV bloquant ;
- analyse statique Java/Kotlin ⇒ CodeQL `security-extended` ;
- dépendance distribuée sans licence exploitable ⇒ build en échec ;
- distribution sans `LICENSE`, notices ou SBOM ⇒ qualification en échec ;
- Action contrôlée par le dépôt ⇒ pin SHA immuable ;
- image Docker avec vulnérabilité HIGH/CRITICAL corrigible ⇒ gate en échec ;
- image publiée depuis `main` ⇒ SBOM et provenance attestés sur le digest.

Baseline JaCoCo de référence : **77,07 % lignes / 58,46 % branches** ; minima bloquants 70 % / 50 %.

## Scénarios restant volontairement ouverts

- **#50** lifecycle Lucene persistant : benchmark avant changement ;
- **#51** provider externe non coopératif : fixture/cas réel avant isolation processus ;
- **#52** filesystem hostile/réseau : matrice et qualification dédiée ;
- **#53** cache Git persistant : mesures cold/warm + modèle d'invalidation ;
- **#54** recovery Ollama/Lucene physique ;
- **#55** nouvelle dépendance à licence inhabituelle : revue explicite.

## Métriques de qualité de référence

Source historique : `docs/developer/iteration-16-baseline-results.md`.

| Métrique | Valeur |
|---|---:|
| Fichiers indexés | 2 104 |
| Symboles | 10 878 |
| Relations | 10 087 |
| Durée indexation complète | 8 818 ms |
| Fédération p50 | 133 ms |
| Fédération p95 | 304 ms |
| Contexte p50 | 48 ms |
| Contexte p95 | 206 ms |
| hit@3 | 1.0 |
| MRR@3 | 1.0 |

## Preuves de qualification récentes

PR #49 : head `4f04c1ad3ff5b41aa9d1892ade57ad62b90a43f9` — NEXUS CI `31314135008`, Scale Benchmark `31314135000`, Windows Installer `31314134983`, Docker Distribution `31314134994`, CodeQL `31314134977`, OSV-Scanner `31314135231` : PASS.

PR #61 : head `ba91be044a600d2396e0939fc154848dc47f6310` — NEXUS CI `31315318844`, CodeQL `31315318865`, OSV-Scanner `31315319213` : PASS ; merge `660ca9f07a23950d2a5284605531524372331bc5`.
