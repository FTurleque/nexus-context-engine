# Scénarios de qualité — NEXUS Context Engine

Ce fichier complète la Section 10 de l'arc42 avec les scénarios de qualité **courants** et leur couverture. Les anciens runs restent dans les documents historiques ; le statut « qualifié » d'un changement appartient toujours au SHA exact qui a produit les checks.

## Couverture par scénario

| Scénario | Type | Couverture principale | Statut courant |
|---|---|---|---|
| QS-01 — Déterminisme ranking | Usage | tests contexte/ranking | Couvert |
| QS-02 — Confinement filesystem | Défaillance | tests traversal/symlink / `SafeFileIO` / `ProjectPathGuard` | Couvert |
| QS-03 — Permissions stockage POSIX | Sécurité | `NexusPaths` + tests filesystem | Couvert |
| QS-04 — Exclusion mutuelle inter-processus | Défaillance | mutex JVM + `FileLock` | Couvert |
| QS-05 — Budget contexte/découverte | Usage | `BudgetedContextSelector`, `ContextDiscoveryLimits`, benchmark 1 000 skills | Couvert |
| QS-06 — Provider/importer timeout | Défaillance | `ExternalTaskRunner` | Couvert |
| QS-07 — Saturation providers externes | Défaillance | sémaphore global max 8 tâches externes actives | Couvert |
| QS-08 — JDT JSON-RPC hostile | Sécurité/Résilience | `JdtJsonRpcFrameReaderTest` | Couvert |
| QS-09 — Recherche Lucene forte cardinalité | Performance/Résilience | cap 128 termes + test de régression | Couvert |
| QS-10 — Fédération bornée | Usage/Performance | 100 uniques fail-fast + budget de travail | Couvert |
| QS-11 — Mutation repository pendant indexation | Correctness | revalidation fingerprint avant `READY` | Couvert |
| QS-12 — Graphe / recherche structurelle bornés | Performance | requêtes ciblées + projections SQL | Couvert |
| QS-13 — Limites cross-surface | Correctness | politiques centrales résultats/budgets | Couvert |
| QS-14 — `constraints` non supportées | Correctness | rejet explicite `ContextRequest`/REST | Couvert |
| QS-15 — REST distant | Sécurité | auth + roots + transport effectif | Couvert |
| QS-16 — Management REST isolé | Sécurité/Opérabilité | test 404 app listener + health/metrics management | Couvert |
| QS-17 — Ollama distant | Sécurité | HTTPS par défaut, HTTP distant opt-in, credentials URI refusés | Couvert |
| QS-18 — Secrets avant embeddings/contexte | Sécurité | `SensitiveContentRedactorTest` + profil `content-v2` | Couvert |
| QS-19 — SQLite invariants | Correctness | V004/V005 + tests fresh/upgrade/invalid INSERT | Couvert |
| QS-20 — Supply-chain reactor | Sécurité | JaCoCo + OSV + CodeQL + notices/SBOM + ancres | Couvert |
| QS-21 — Supply-chain Docker | Sécurité | Trivy + SBOM + image exacte qualifiée | Couvert |
| QS-22 — Dérive documentaire | Gouvernance/Correctness | `test-operational-doc-contracts.sh` dans NEXUS CI | Couvert |
| QS-23 — `develop` protégé | Gouvernance | ruleset/branch protection GitHub | **Ouvert #130** |
| QS-24 — Recovery sémantique physique | Défaillance | runbook/fixtures à renforcer | Watch item #54 |
| QS-25 — Filesystem hostile/réseau | Défaillance | matrice dédiée requise | Watch item #52 |

## Bornes actives à surveiller

```text
Portée fédérée                  <= 100 projets uniques
Découverte native visitée       <= 100000 entrées
Découverte native candidats     <= 5000
Découverte native octets        <= 32 MiB
Découverte native durée         <= 15 s
Tâches externes actives         <= 8
JDT message JSON-RPC            <= 16 MiB
JDT headers cumulés             <= 64 KiB
JDT ligne header                <= 8 KiB
JDT messages en attente         <= 256
Lucene termes analysés uniques  <= 128
```

Une modification de ces valeurs doit mettre à jour code, tests, documentation et benchmark/justification lorsqu'applicable.

## Critères supply-chain actifs

- régression de couverture core sous 70 % lignes ou 50 % branches ⇒ build en échec ;
- nouvelle vulnérabilité introduite en PR ⇒ gate OSV delta ;
- vulnérabilité dans le SBOM CycloneDX agrégé ⇒ OSV gate bloquant ;
- analyse statique Java/Kotlin ⇒ CodeQL `security-extended` ;
- Quality Gate PR ⇒ SonarCloud ;
- dépendance distribuée sans licence exploitable ⇒ build en échec ;
- distribution sans `LICENSE`, notices ou SBOM ⇒ qualification en échec ;
- Action contrôlée par le dépôt ⇒ pin SHA immuable ;
- image Docker avec vulnérabilité HIGH/CRITICAL corrigible ⇒ gate en échec ;
- image publiée depuis `main` ⇒ image exacte qualifiée, SBOM et provenance sur le digest ;
- dérive des contrats documentaires machine-vérifiables ⇒ NEXUS CI en échec.

Les minima JaCoCo bloquants restent 70 % lignes / 50 % branches. Une mesure historique plus élevée n'est pas une preuve de l'état courant.

## Scénarios volontairement ouverts

- **#50** lifecycle Lucene persistant : benchmark avant changement ;
- **#51** provider externe non coopératif : fixture/cas réel avant isolation processus plus forte ;
- **#52** filesystem hostile/réseau : matrice et qualification dédiée ;
- **#53** cache Git persistant : mesures cold/warm + modèle d'invalidation ;
- **#54** recovery Ollama/Lucene physique ;
- **#55** nouvelle dépendance à licence inhabituelle : revue explicite ;
- **#130** protection effective de `develop` : configuration repository-admin obligatoire.

## Baselines historiques

Les mesures historiques de performance/qualité restent dans `docs/developer/iteration-*` et autres rapports dédiés. Elles servent uniquement de point de comparaison.

## Preuve de qualification

Ne recopier ici ni numéro de PR ni run comme « preuve courante ». Pour toute promotion/merge/release, la preuve est l'ensemble des checks applicables attachés au **SHA exact** candidat.
