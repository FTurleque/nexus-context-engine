# Feuille de route NEXUS

Cette feuille de route est la source de vérité active pour l'évolution de NEXUS.

État de travail Phase 6 :

```text
repository  FTurleque/nexus-context-engine
branch      phase-6-consolidation-hardening
version     0.2.0
Java        runtime >=21 / release 21
qualification locale exact-head : PASS
```

Principe directeur :

> **qualité du contexte > correctness > passage à l'échelle > opérabilité > nombre de fonctionnalités > nombre d'intégrations.**

Les ADR acceptés conservent l'historique des décisions. Les résultats historiques restent dans leurs documents dédiés. Cette roadmap décrit l'état courant du produit et les preuves de qualification requises avant intégration.

## État consolidé

| Phase | Itérations | État |
|---|---|---|
| Phase 1 — Valider le moteur | 0 → 4 | ✅ terminée |
| Phase 2 — Étendre les sources de contexte | 5 → 7 | ✅ terminée |
| Phase 3 — Enrichir l'intelligence de code | 8 → 10 | ✅ terminée |
| Phase 4 — Exposer NEXUS aux autres outils | 11 → 13 | ✅ terminée |
| Phase 5 — Écosystème et passage à l'échelle | 14 → 17 | ✅ terminée |
| Intégration compagnon MINOS | issue #11 / PR #12 | ✅ livrée |
| Phase 6 — Consolidation, hardening et industrialisation | 18 → 24 | ✅ qualifiée techniquement sur branche, intégration en attente |

Dernière qualification intégrée connue avant Phase 6 : Java 21, 128 sources principales, 41 sources de tests, 80 tests exécutés, 0 failure, 0 error, 6 skipped, `BUILD SUCCESS`, Sonar Quality Gate Passed. Le replay réel MINOS → NEXUS avait importé 11 symboles / 6 relations et retrouvé `GreetingPort` avec provenance `minos`.

## Phases 1 à 5 — livré

- **0→4** : architecture Java 21, SQLite canonique, Lucene dérivé, indexation incrémentale, recherche hybride, ranking explicable, ContextBuilder, budget strict, CLI autonome.
- **5→7** : Markdown, instructions natives, Agent Skills à divulgation progressive et contexte Git local borné.
- **8→10** : SCIP opportuniste, JDT LS opt-in et support lexical Kotlin/TypeScript/JavaScript/Python/SQL.
- **11→13** : REST Quarkus, MCP Java STDIO et intégrations Copilot/Claude.
- **14→17** : AI Skills Registry, JARVIS/Alfred/Brainiac, recherche fédérée locale et recherche sémantique optionnelle.

Baseline grande échelle canonique : 2 104 fichiers, 10 878 symboles, 10 087 relations, indexation complète 8 818 ms, fédération p50/p95 133/304 ms, contexte p50/p95 48/206 ms, hit@3 et MRR@3 à 1,0.

Décisions conservées : Lucene reste le moteur local par défaut ; aucun Zoekt/OpenGrok/OpenSearch, index distribué ou vector DB sans benchmark démontrant le besoin ; sémantique désactivée par défaut.

## Intégration MINOS

✅ Issue #11 / PR #12 livrées le 24 juillet 2026. NEXUS ne dépend pas de MINOS et ne le lance jamais ; l'import reste un JSON local explicite, versionné et validé. Phase 6 réutilise désormais la vue canonique `indexed_files` pour l'allow-list du chemin applicatif.

---

# Phase 6 — Consolidation, hardening et industrialisation

Issue : **#13 — Phase 6 — Consolidation, hardening, scale et réconciliation documentaire**.

Branche : `phase-6-consolidation-hardening`.

Les itérations ci-dessous sont couvertes par le log exact-head `=== PHASE 6 PASS ===` produit par `scripts/validate-phase-6.ps1`. La preuve du head qualifié est conservée dans la PR #15.

## I18 — Correctness de recherche et cohérence des index

**✅ implémentée / ✅ qualifiée Phase 6**

- sur-récupération bornée avant diversification fédérée ;
- test du cas FILE + SYMBOL sur le même chemin ;
- gate READY commun pour recherche, contexte, symboles, usages, fédération et MINOS ;
- aucune lecture interactive hors READY ;
- tout état persistant non-READY force un rebuild complet ;
- un INDEXING abandonné après crash est récupérable, alors que la concurrence active est refusée par single-flight.

## I19 — Recherche symbolique et graphe à grande échelle

**✅ implémentée / ✅ qualifiée Phase 6**

- `searchSymbols` / `searchRelations` bornés côté repository/SQLite ;
- fuzzy Java appliqué seulement sur un pool préfiltré ;
- `findSymbols` / `findUsages` sans scan complet applicatif ;
- migration V002 et génération monotone par projet ;
- graphe dérivé mis en cache par génération ;
- enrichissement graphe charge seulement les fichiers voisins nécessaires.

Le lifecycle Lucene par opération reste un watch item : aucune complexification sans benchmark runtime démontrant le gain.

## I20 — Composition applicative et gouvernance Maven

**✅ implémentée / ✅ qualifiée Phase 6**

- CLI entièrement déléguée à `NexusApplication` ;
- même composition root pour CLI/REST/MCP ;
- providers Local Skills / AI Skills Registry indépendants ;
- reactor Maven racine + module core ;
- Java/dépendances/BOM/plugins centralisés ;
- Enforcer Java `[21,)`, compilation `release=21`, Maven ≥3.9 et doublons de versions ;
- alignement Jackson MCP centralisé.

## I21 — Hardening indexation et ressources

**✅ implémentée / ✅ qualifiée Phase 6**

- single-flight in-process par projet ;
- taille maximale configurable avant hash/lecture : `NEXUS_MAX_FILE_SIZE_BYTES`, 8 MiB par défaut ;
- diagnostics d'exclusion ;
- import MINOS contre les fichiers canoniques ;
- timeout global provider : `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS`, 180 s par défaut ;
- cancellation et diagnostics provider/importer ;
- durées structurées dans `IndexingReport`.

## I22 — Runtime, opérabilité et opt-ins

**✅ implémentée / ✅ qualifiée Phase 6**

- readiness REST avec comptes READY/INDEXING/FAILED/NOT_INDEXED ;
- métriques index/search/context/fédération sans contenu privé dans les labels ;
- métriques durée provider ;
- sémantique activable uniformément via `NEXUS_SEMANTIC_PROVIDER=ollama` ;
- batching `EmbeddingProvider.embedAll` et Ollama `/api/embed` ;
- recherche fédérée exposée en CLI/REST/MCP.

Watch items conservés sur preuve : lifecycle Lucene persistant et cache Git.

## I23 — ContextBundle fédéré multi-projet

**✅ implémentée / ✅ qualifiée Phase 6**

- portée explicite de projets READY ;
- budget global déterministe ;
- provenance projet par item ;
- round-robin pour limiter la starvation ;
- déduplication inter-projet ;
- métriques allocation/sélection/starvation/déduplication ;
- instructions, skills et Git restent projet-locaux ;
- CLI `context-federated` ;
- REST `/api/v1/federated/context` ;
- MCP `build_context_across_projects` / `explain_context_across_projects` ;
- test budget/provenance/déduplication.

L'ancienne PR draft #10 reste historique et n'est pas une source autoritative.

## I24 — Distribution, installation et release readiness

**✅ implémentée / ✅ qualifiée Windows et Linux**

- version reactor 0.2.0 ;
- Maven Wrapper `only-script` épinglé sur Maven 3.9.11 avec SHA-512 ;
- fat JAR CLI ;
- ZIP autonome sans clone ni Maven ;
- launchers Windows/POSIX ;
- SHA-256 du JAR et du ZIP ;
- SBOM CycloneDX agrégé ;
- migration SQLite forward-only ;
- runbook backup/recovery ;
- `scripts/validate-phase-6.ps1` pour la qualification exact-head.

Runbook : [`developer/release-and-recovery.md`](developer/release-and-recovery.md).

---

# Dette F01–F18

Le détail est maintenu dans [`developer/current-limitations.md`](developer/current-limitations.md).

Après qualification : F01–F12, F14–F15 et F17–F18 sont fermés ; F13 reste volontairement un watch item Lucene ; F16 a reçu le batching embeddings, tandis que le cache Git reste différé sur benchmark.

# Gate final Phase 6

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-phase-6.ps1
```

Ce gate vérifie une JVM Java 21 ou supérieure avec compilation `release=21`, Maven Wrapper, `clean install` du reactor, `scripts/self-smoke.ps1`, livrables 0.2.0, SHA-256, SBOM CycloneDX, exécution réelle de l'archive autonome et exact-head Git.

La preuve exact-head est publiée dans la PR #15. Le merge de la PR et la fermeture de #13 restent soumis à autorisation explicite.
