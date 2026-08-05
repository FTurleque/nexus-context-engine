# Scénarios de qualité — NEXUS Context Engine

Ce fichier complète la Section 10 de l'arc42 avec des scénarios additionnels et leur
couverture de test actuelle.

## Couverture par scénario

| Scénario | Type | Test couvrant | Statut |
|----------|------|--------------|--------|
| QS-01 — Déterminisme ranking | Usage | `DefaultContextBuilderIntegrationTest`, self-smoke | Couvert |
| QS-02 — Confinement filesystem | Défaillance | Tests adversariaux symlink (H1) | Implémenté, à qualifier |
| QS-03 — Exclusion mutuelle inter-processus | Défaillance | Tests FileLock (H2) | Implémenté, à qualifier |
| QS-04 — Budget de tokens respecté | Usage | `BudgetedContextSelector` tests | Couvert |
| QS-05 — Démarrage CLI | Usage | `scripts/self-smoke.ps1` | Couvert |
| QS-06 — Provider en timeout | Défaillance | `ExternalTaskRunner` test (H3) | Implémenté, à qualifier |
| QS-07 — Qualité de recherche | Changement | `SearchQualityMetrics`, baseline Phase 6 | Couvert |
| QS-08 — Ajout provider langage | Changement | Revue de code | Non automatisé |
| QS-09 — SQLite corrompu | Défaillance | Runbook recovery | Runbook uniquement |

## Scénarios manquants identifiés

| ID | Scénario manquant | Priorité |
|----|------------------|---------|
| QS-10 | Fédération avec budget serré sur 10+ projets — vérifier fair floor et refill | Haute |
| QS-11 | Recherche sémantique avec Ollama indisponible — fallback gracieux | Moyenne |
| QS-12 | Index Lucene corrompu — démarrage, rebuild automatique ou message d'erreur explicite | Moyenne |
| QS-13 | Instruction avec référence `@fichier` cyclique (récursion > 5 niveaux) | Moyenne |
| QS-14 | REST avec `NEXUS_REST_API_TOKEN` — token valide et token invalide | Haute (H6) |
| QS-15 | Deux importations MINOS concurrentes pendant une mutation d'index | Haute (H2) |

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
