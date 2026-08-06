# Scénarios de qualité — NEXUS Context Engine

Ce fichier complète la Section 10 de l'arc42 avec des scénarios additionnels et leur couverture de test actuelle.

## Couverture par scénario

| Scénario | Type | Test couvrant | Statut |
|----------|------|--------------|--------|
| QS-01 — Déterminisme ranking | Usage | `DefaultContextBuilderIntegrationTest`, self-smoke | Couvert |
| QS-02 — Confinement filesystem | Défaillance | Tests adversariaux symlink (H1) + qualification hardening | Couvert / qualifié |
| QS-03 — Exclusion mutuelle inter-processus | Défaillance | Tests `FileLock` (H2) + qualification hardening | Couvert / qualifié |
| QS-04 — Budget de tokens respecté | Usage | `BudgetedContextSelector` tests | Couvert |
| QS-05 — Démarrage CLI | Usage | `scripts/self-smoke.ps1` | Couvert |
| QS-06 — Provider/importer en timeout | Défaillance | `ExternalTaskRunner` + tests H3 | Couvert / qualifié |
| QS-07 — Qualité de recherche | Changement | `SearchQualityMetrics`, baseline Phase 6 | Couvert |
| QS-08 — Ajout provider langage | Changement | Revue de code | Non automatisé |
| QS-09 — SQLite corrompu | Défaillance | Runbook recovery | Runbook uniquement |
| QS-10 — Fédération sous budget serré | Usage | Tests de fair floor/refill post-Phase 6 | Couvert / qualifié |
| QS-14 — REST token/host | Sécurité | Tests primitives REST + qualification hardening | Couvert / qualifié |
| QS-15 — Mutation MINOS concurrente | Défaillance | Verrou de mutation JVM + OS | Couvert par le modèle de verrouillage ; cas à conserver en non-régression |
| QS-16 — Snapshot externe obsolète | Défaillance | `ExternalCodeIntelligenceInvalidationTest` | Couvert / qualifié via PR #24 |
| QS-17 — Index sémantique incompatible | Défaillance | `SemanticIndexProvenanceIntegrationTest` | Couvert / qualifié via PR #24 |
| QS-19 — Changement de dépendances / supply-chain | Sécurité / changement | JaCoCo + OSV-Scanner + CodeQL + notices/SBOM | Couvert / qualifié via PR #28 |

## Scénarios restant à renforcer

| ID | Scénario | Priorité |
|----|----------|---------|
| QS-11 | Recherche sémantique avec Ollama indisponible — fallback gracieux | Moyenne |
| QS-12 | Index Lucene physiquement corrompu — rebuild automatique ou diagnostic explicite | Moyenne |
| QS-13 | Instruction avec référence `@fichier` cyclique (récursion > 5 niveaux) | Moyenne |
| QS-18 | `NEXUS_HOME` sur filesystem réseau — détecter/refuser ou documenter une qualification spécifique | Moyenne |
| QS-20 | Nouvelle dépendance à licence atypique — revue de compatibilité explicite malgré l'inventaire automatisé | Moyenne |

## QS-19 — critères intégrés

PR #28 a matérialisé le scénario supply-chain :

- régression de couverture core sous 70 % lignes ou 50 % branches ⇒ build Maven en échec ;
- nouvelle vulnérabilité introduite dans une PR ⇒ gate OSV ;
- analyse statique Java/Kotlin ⇒ CodeQL `security-extended` ;
- dépendance compile/runtime sans licence exploitable ⇒ build en échec ;
- ZIP sans `LICENSE`, `THIRD_PARTY_NOTICES.txt` ou `SBOM.cdx.json` ⇒ qualification en échec ;
- Action contrôlée par le repository ⇒ pin à un SHA immuable.

Baseline JaCoCo qualifiée : **77,07 % lignes / 58,46 % branches**.

## Métriques de qualité de référence (Phase 6)

Source : `docs/developer/iteration-16-baseline-results.md`

| Métrique | Valeur |
|----------|--------|
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

- PR #24, head `25c12b100b774a4ec3d69d221675bf31d8ebaa0c` : Windows Java 24 PASS, Linux Java 21 reactor PASS, distribution smoke PASS.
- PR #28, head `a363e93dc97597d288389b4f4b9e8404abe4296c` : NEXUS CI #31 PASS, OSV #4 PASS, CodeQL #6 PASS ; intégrée dans `main` via `4c9b7cd4e26913af42f687b48718c8e733fa06f7`.
