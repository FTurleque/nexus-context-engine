# Section 10 — Exigences de qualité

## 10.1 Tableau des qualités

| Priorité | Attribut | Objectif mesurable | ADR |
|----------|----------|-------------------|-----|
| 1 | **Correctness** | Scores et sélection strictement déterministes et reproductibles | ADR-0010, ADR-0025, ADR-0029 |
| 2 | **Sécurité locale** | Aucun accès hors périmètre projet ; symlinks refusés | H1, H2 |
| 3 | **Indépendance fournisseur** | Zéro dépendance obligatoire vers LLM / IDE / orchestrateur | ADR-0001, ADR-0005 |
| 4 | **Opérabilité** | Health check + métriques ; CLI démarre sans config supplémentaire | ADR-0030, ADR-0031 |
| 5 | **Évolutivité** | Nouveaux providers sans modifier le cœur | ADR-0003, ADR-0009 |

## 10.2 Scénarios de qualité

### QS-01 — Déterminisme du ranking (correctness)

| Dimension | Valeur |
|-----------|--------|
| **Stimulus** | Deux appels identiques `build_context(project, query, budget)` séparés de 60 s, sans modification du repository |
| **Environnement** | Projet READY, sémantique désactivée |
| **Réponse attendue** | ContextBundle strictement identique : mêmes items, même ordre, mêmes scores, même budget consommé |
| **Mesure** | Égalité octets-à-octets du JSON retourné |
| **Seuil** | 100 % de reproductibilité |
| **Méthode de vérification** | Test d'intégration `DefaultContextBuilderIntegrationTest`, self-smoke |
| **Propriétaire** | Équipe cœur |

---

### QS-02 — Confinement filesystem (sécurité locale)

| Dimension | Valeur |
|-----------|--------|
| **Stimulus** | Un fichier `.nexusignore` tente de référencer `../outside/secret.txt` ou un symlink sous la racine pointe hors du repository |
| **Environnement** | N'importe quel OS supportant les symlinks |
| **Réponse attendue** | Erreur levée par `ProjectPathGuard`, aucun contenu externe lu, projet non marqué FAILED pour cette seule violation |
| **Mesure** | Absence de lecture hors périmètre dans les logs |
| **Seuil** | 0 lecture autorisée hors périmètre |
| **Méthode de vérification** | Tests adversariaux symlink (H1) — skip si la plateforme interdit la création de symlinks |
| **Propriétaire** | Équipe hardening |

---

### QS-03 — Exclusion mutuelle inter-processus (fiabilité)

| Dimension | Valeur |
|-----------|--------|
| **Stimulus** | Deux processus NEXUS tentent simultanément d'indexer le même projet (`projectId` identique) |
| **Environnement** | Même `NEXUS_HOME` sur un filesystem local |
| **Réponse attendue** | L'un des deux processus attend ou échoue proprement, aucune corruption de SQLite ou Lucene |
| **Mesure** | Intégrité de la base SQLite après les deux tentatives ; aucun état INDEXING persistant après crash |
| **Seuil** | 0 corruption tolérée |
| **Méthode de vérification** | Test d'exclusion/réacquisition (H2) |
| **Propriétaire** | Équipe hardening |

---

### QS-04 — Budget de tokens respecté (correctness)

| Dimension | Valeur |
|-----------|--------|
| **Stimulus** | Appel `build_context` avec `tokenBudget=2000` sur un projet contenant 50 000 tokens de contenu total |
| **Environnement** | Projet READY, budget strict |
| **Réponse attendue** | `ContextBundle.estimatedTokens ≤ 2000` ; aucun item excédant le budget n'est inclus |
| **Mesure** | `estimatedTokens ≤ tokenBudget` vérifié sur chaque item + total |
| **Seuil** | Aucune violation du budget, tolérance 0 |
| **Méthode de vérification** | Test unitaire `BudgetedContextSelector`, test d'intégration |
| **Propriétaire** | Équipe cœur |

---

### QS-05 — Démarrage CLI sans configuration (opérabilité)

| Dimension | Valeur |
|-----------|--------|
| **Stimulus** | Exécution de `nexus --help` sur une machine fraîche sans `NEXUS_HOME` préexistant |
| **Environnement** | JVM 21, distribution ZIP |
| **Réponse attendue** | Affichage de l'aide, code de sortie 0, aucune erreur |
| **Mesure** | Code de sortie et contenu de l'aide |
| **Seuil** | < 3 s de démarrage, code 0 |
| **Méthode de vérification** | Script self-smoke `scripts/self-smoke.ps1` |
| **Propriétaire** | Équipe distribution |

---

### QS-06 — Provider en timeout (résilience)

| Dimension | Valeur |
|-----------|--------|
| **Stimulus** | Le provider JDT LS ne répond pas dans `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS` secondes |
| **Environnement** | Projet en cours d'indexation avec `--deep-java` |
| **Réponse attendue** | Indexation complétée sans résultats JDT ; projet marqué READY (pas FAILED) |
| **Mesure** | `indexStatus == READY` malgré le timeout du provider |
| **Seuil** | Aucune attente bloquante après le timeout ; wall-clock ≤ timeout + 5 s |
| **Méthode de vérification** | Test d'intégration `ExternalTaskRunner` avec task non-coopérative (H3) |
| **Propriétaire** | Équipe hardening |

---

### QS-07 — Qualité de recherche (correctness)

| Dimension | Valeur |
|-----------|--------|
| **Stimulus** | Requête de recherche sur le corpus de qualification Phase 6 (2 104 fichiers, 10 878 symboles) |
| **Environnement** | Projet READY, sémantique désactivée |
| **Réponse attendue** | Le fichier cible apparaît dans les 3 premiers résultats |
| **Mesure** | `hit@3 = 1.0`, `MRR@3 = 1.0` sur le jeu de qualification |
| **Seuil** | `hit@3 ≥ 0.95`, `MRR@3 ≥ 0.90` |
| **Méthode de vérification** | `SearchQualityMetrics`, baseline Phase 6 enregistrée dans `docs/developer/iteration-16-baseline-results.md` |
| **Propriétaire** | Équipe qualité |

---

### QS-08 — Scénario de changement — ajout d'un provider de langage

| Dimension | Valeur |
|-----------|--------|
| **Stimulus** | Ajout du support d'un nouveau langage (ex : Rust) via un nouveau `LanguageAnalyzer` |
| **Environnement** | Cœur Java 21, sans modifier NexusApplication |
| **Réponse attendue** | Le nouveau provider est enregistré dans NexusApplication ; aucune modification des providers existants |
| **Mesure** | Diff minimal : uniquement les nouveaux fichiers + enregistrement dans NexusApplication |
| **Seuil** | 0 modification des providers existants |
| **Méthode de vérification** | Revue de code, ADR-0038 |
| **Propriétaire** | Équipe cœur |

---

### QS-09 — Scénario de défaillance — SQLite corrompu

| Dimension | Valeur |
|-----------|--------|
| **Stimulus** | La base SQLite est corrompue (coupure de courant en cours d'écriture) |
| **Environnement** | Production locale |
| **Réponse attendue** | NEXUS détecte l'erreur au démarrage, signale l'état via health check, propose un rebuild |
| **Mesure** | `/q/health/ready` retourne `DOWN` avec message explicite |
| **Seuil** | Aucune donnée utilisateur perdue dans Lucene (reconstructible depuis SQLite) |
| **Méthode de vérification** | Test de récupération, runbook `docs/developer/release-and-recovery.md` |
| **Propriétaire** | Équipe hardening |
