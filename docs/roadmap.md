# Feuille de route NEXUS

Cette feuille de route est la source de vérité active pour l'évolution de NEXUS.

État courant :

```text
repository  FTurleque/nexus-context-engine
main        Phase 6 intégrée
develop     Hardening post-Phase 6 intégré (2026-08-05)
issue       #16 — Post-Phase 6 hardening — CLÔTURÉE
version     0.2.0
Java        runtime >=21 / release 21
qualification post-Phase 6 : EXÉCUTÉE — gates A-D PASS, self-smoke 13/13
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
| Phase 6 — Consolidation, hardening et industrialisation | 18 → 24 | ✅ intégrée via PR #15 |
| Hardening post-Phase 6 | issue #16 / PR #17 | ✅ intégré dans `develop` le 2026-08-05 |

La qualification Phase 6 historique reste conservée dans la PR #15. Elle ne constitue pas une preuve pour les changements de l'issue #16.

## Phases 1 à 5 — livré

- **0→4** : architecture Java 21, SQLite canonique, Lucene dérivé, indexation incrémentale, recherche hybride, ranking explicable, ContextBuilder, budget strict, CLI autonome.
- **5→7** : Markdown, instructions natives, Agent Skills à divulgation progressive et contexte Git local borné.
- **8→10** : SCIP opportuniste, JDT LS opt-in et support lexical Kotlin/TypeScript/JavaScript/Python/SQL.
- **11→13** : REST Quarkus, MCP Java STDIO et intégrations Copilot/Claude.
- **14→17** : AI Skills Registry, JARVIS/Alfred/Brainiac, recherche fédérée locale et recherche sémantique optionnelle.

Baseline grande échelle canonique : 2 104 fichiers, 10 878 symboles, 10 087 relations, indexation complète 8 818 ms, fédération p50/p95 133/304 ms, contexte p50/p95 48/206 ms, hit@3 et MRR@3 à 1,0.

Décisions conservées : Lucene reste le moteur local par défaut ; aucun Zoekt/OpenGrok/OpenSearch, index distribué, FTS supplémentaire ou vector DB sans benchmark démontrant le besoin ; sémantique désactivée par défaut.

## Intégration MINOS

✅ Issue #11 / PR #12 livrées le 24 juillet 2026. NEXUS ne dépend pas de MINOS et ne le lance jamais ; l'import reste un JSON local explicite, versionné et validé. Phase 6 réutilise la vue canonique `indexed_files` pour l'allow-list du chemin applicatif.

---

# Phase 6 — Consolidation, hardening et industrialisation

Issue : **#13** — clôturée.

PR : **#15** — fusionnée dans `main`.

Les itérations I18→I24 ont été qualifiées avant intégration. Elles constituent la baseline fonctionnelle sur laquelle part l'issue #16.

## I18 — Correctness de recherche et cohérence des index

**✅ intégrée**

- sur-récupération bornée avant diversification fédérée ;
- gate READY commun pour recherche, contexte, symboles, usages, fédération et MINOS ;
- aucune lecture interactive hors READY ;
- tout état persistant non-READY force un rebuild complet ;
- un INDEXING abandonné après crash est récupérable.

## I19 — Recherche symbolique et graphe à grande échelle

**✅ intégrée**

- `searchSymbols` / `searchRelations` bornés côté repository/SQLite ;
- fuzzy Java appliqué seulement sur un pool préfiltré ;
- `findSymbols` / `findUsages` sans scan complet applicatif ;
- génération monotone par projet ;
- graphe dérivé mis en cache par génération ;
- enrichissement graphe limité aux fichiers voisins nécessaires.

## I20 — Composition applicative et gouvernance Maven

**✅ intégrée**

- CLI déléguée à `NexusApplication` ;
- même composition root pour CLI/REST/MCP ;
- providers Local Skills / AI Skills Registry indépendants ;
- reactor Maven racine + module core ;
- Java/dépendances/BOM/plugins centralisés ;
- Enforcer Java `[21,)`, compilation `release=21`, Maven ≥3.9.

## I21 — Hardening indexation et ressources

**✅ intégrée, puis renforcée par #16**

Baseline Phase 6 : single-flight in-process, taille maximale configurable, import MINOS canonique, timeout global provider et diagnostics structurés.

## I22 — Runtime, opérabilité et opt-ins

**✅ intégrée, puis renforcée par #16**

Baseline Phase 6 : readiness REST, métriques, sémantique opt-in commune, batching embeddings et fédération exposée en CLI/REST/MCP.

## I23 — ContextBundle fédéré multi-projet

**✅ intégrée, puis renforcée par #16**

Baseline Phase 6 : portée explicite de projets READY, budget global, provenance, round-robin, déduplication et sources natives projet-locales.

## I24 — Distribution, installation et release readiness

**✅ intégrée**

- version reactor 0.2.0 ;
- Maven Wrapper 3.9.11 avec SHA-512 ;
- fat JAR CLI ;
- ZIP autonome ;
- launchers Windows/POSIX ;
- SHA-256 ;
- SBOM CycloneDX ;
- migration SQLite forward-only ;
- runbook backup/recovery.

Runbook : [`developer/release-and-recovery.md`](developer/release-and-recovery.md).

---

# Hardening post-Phase 6 — issue #16

Base : `develop` créée depuis le `main` post-Phase 6.

Branche : `hardening/post-phase6-audit`.

PR de revue : **#17**, ouverte en draft vers `develop`.

**Règle de gate : aucune CI et aucun merge dans `develop` avant validation explicite.** Les statuts ci-dessous signifient uniquement « code présent sur la branche », jamais « PASS qualifié ».

## H1 — Frontière filesystem sûre

**✅ implémenté / ✅ qualifié**

- nouvelle politique `ProjectPathGuard` ;
- root canonicalisé ;
- liens symboliques sous la racine refusés ;
- confinement appliqué au scanner, `.gitignore`/`.nexusignore`, instructions, références et Agent Skills ;
- ouverture du composant final avec `NOFOLLOW_LINKS` via `SafeFileIO` ;
- politique `NEXUS_MAX_FILE_SIZE_BYTES` centralisée dans `ProjectFileLimits` ;
- hash et flux de lecture réellement bornés au moment de la consommation ;
- fichier réel et taille réelle revalidés avant hash/lecture ;
- tests adversariaux symlink préparés avec skip explicite si la plateforme n'autorise pas leur création.

Frontière étendue post-qualification : la lecture interne du provider JDT LS (via `SafeFileIO.readStringNoFollow`), `ContextFragmentFactory` et l'importeur SCIP couverts par `SafeFileIO` + `NOFOLLOW_LINKS`.

Limite assumée : le modèle Java portable ne constitue pas un sandbox absolu contre un acteur local qui remplace agressivement un répertoire ancêtre ou crée des hard-links pendant le traitement.

## H2 — Single-flight inter-processus

**✅ implémenté / ✅ qualifié**

- mutex JVM par `projectId` conservé ;
- slots locaux libérés quand ils ne sont plus utilisés ;
- `FileLock` OS par projet sous `NEXUS_HOME/locks` ;
- le répertoire de locks et le fichier final ne peuvent pas être redirigés par symlink ;
- NEXUS ne tronque ni n'écrit de contenu métier dans le fichier de lock ;
- la présence du fichier n'est pas assimilée à un verrou actif ;
- index/rebuild/deep-Java et import MINOS sont coordonnés par le même verrou de mutation ;
- tests d'exclusion/réacquisition et de redirection symlink préparés.

La sémantique d'un filesystem réseau doit être qualifiée séparément ; le support cible reste un `NEXUS_HOME` local.

## H3 — Timeouts externes cohérents

**✅ implémenté / ✅ qualifié**

- `ExternalTaskRunner` commun ;
- même enveloppe pour `CodeIndexImporter` et `CodeIntelligenceProvider` ;
- worker daemon ;
- timeout wall-clock côté appelant ;
- annulation/interruption sans attente bloquante de fermeture d'executor ;
- test d'une tâche volontairement non coopérative préparé ;
- test d'intégration d'un importer récalcitrant préparé, avec passage du projet en `FAILED`.

Risque résiduel accepté pour ce niveau : une intégration Java qui ignore définitivement l'interruption peut survivre sur un worker daemon. Une isolation en processus/circuit-breaker n'est justifiée que si ce cas apparaît réellement avec de futurs providers.

## H4 — Health model explicite

**✅ implémenté / ✅ qualifié**

- liveness Quarkus séparée ;
- readiness service distincte ;
- `allProjectsReady` ;
- `degraded` si un projet est `FAILED` ;
- les opérations de lecture gardent le gate strict `project == READY`.

## H5 — Fédération avec réutilisation du budget

**✅ implémenté / ✅ qualifié**

- fair floor déterministe par projet ;
- pool local borné par le budget global ;
- premier passage équitable par préfixe de ranking local ;
- déduplication ;
- refill global des candidats différés avec le budget rendu disponible ;
- ordre relatif du ranking local préservé : un petit candidat moins pertinent ne peut pas dépasser un candidat mieux classé simplement parce que ce dernier dépasse le fair floor ;
- métriques `refillTokens`, `refillItems`, `unusedTokens`.

## H6 — Sécurité de l'exposition REST

**✅ implémenté / ✅ qualifié**

- `127.0.0.1` reste le défaut ;
- `NEXUS_REST_API_TOKEN` ou `-Dnexus.rest.api-token` active Bearer auth ;
- host non-loopback sans token : fail-fast au bootstrap via bean eager ;
- test unitaire des primitives loopback/token préparé.

## H7 — Corrections de cohérence mineures

**✅ implémenté / ✅ qualifié**

- UUID valide mais inconnu : erreur UUID directe, pas de fallback par nom ;
- cycle de vie des locks JVM nettoyé ;
- README/roadmap/limitations réconciliés avec l'intégration réelle de Phase 6.

## H8 — Scale lexical SQLite

**⏳ benchmark requis avant modification**

Le coût potentiel de `LOWER(...) LIKE '%...%'` reste un watch item réel. La décision est volontairement différée jusqu'à une mesure sur des jeux de 10k, 100k, 500k et 1M symboles. Les options à comparer sont notamment FTS5, n-gram/trigram et maintien de la stratégie actuelle.

Introduire maintenant FTS5 ou un moteur supplémentaire sans preuve irait contre le principe directeur de NEXUS et augmenterait la complexité de migration/recovery sans bénéfice démontré.

---

# Gate de validation #16

Avant toute intégration dans `develop` :

1. revue statique du diff `develop...hardening/post-phase6-audit` ;
2. vérification qu'aucun workflow CI n'a été déclenché par la branche ;
3. présentation du périmètre et des risques résiduels ;
4. **validation explicite du propriétaire**.

Après validation, la qualification devra couvrir au minimum :

- reactor Maven complet ;
- tests core + REST ;
- scénarios symlink Windows/Linux quand supportés ;
- deux propriétaires concurrents du même lock ;
- redirection symlink du répertoire/fichier de lock ;
- import MINOS concurrent d'une mutation d'index ;
- importer/provider en timeout ;
- readiness/liveness ;
- REST loopback et non-loopback avec/sans token ;
- budget fédéré et ordre de ranking pendant le refill ;
- packaging/distribution ;
- exact-head du commit destiné à `develop`.

Aucun résultat de ces gates n'est préjugé avant exécution réelle.

Voir aussi : [`developer/current-limitations.md`](developer/current-limitations.md) et [`developer/release-and-recovery.md`](developer/release-and-recovery.md).
