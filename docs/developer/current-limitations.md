# Limites actuelles et dette de consolidation

> État de travail : `phase-6-consolidation-hardening`.
> Les correctifs Phase 6 sont implémentés mais ne sont **pas encore déclarés qualifiés** tant que `scripts/validate-phase-6.ps1` n'a pas produit `=== PHASE 6 PASS ===` sur l'exact head.

Ce registre suit les constats F01–F18 issus de l'audit de juillet 2026. Les ADR acceptés restent historiques et ne sont pas réécrits.

## Registre Phase 6

| ID | Sujet | Traitement Phase 6 | État avant qualification |
|---|---|---|---|
| F01 | top-K fédéré sous-rempli | sur-récupération bornée avant diversification + test de régression | corrigé, à qualifier |
| F02 | gate `READY` non uniforme | gate applicatif commun pour search/context/symbols/usages/fédération/MINOS | corrigé, à qualifier |
| F03 | fenêtre SQLite/index dérivés | lecture interdite hors `READY`, recovery non-READY par rebuild complet, génération canonique | corrigé, à qualifier |
| F04 | scan complet recherche symbolique | préfiltrage SQLite borné avant fuzzy Java | corrigé, à qualifier |
| F05 | `findSymbols`/`findUsages` projet-wide | requêtes repository SQL avec `LIMIT` | corrigé, à qualifier |
| F06 | graphe reconstruit par requête | cache dérivé par génération d'index | corrigé, à qualifier |
| F07 | composition CLI dupliquée | CLI déléguée entièrement à `NexusApplication` | corrigé, à qualifier |
| F08 | drift Maven | reactor parent, dependency/plugin management et Enforcer communs | corrigé, à qualifier |
| F09 | coupling Skills Registry | providers local/registry composés indépendamment | corrigé, à qualifier |
| F10 | absence single-flight | verrou in-process par `projectId`, second run actif refusé | corrigé, à qualifier |
| F11 | fichiers non bornés | plafond configurable avant hash/lecture, 8 MiB par défaut, diagnostics | corrigé, à qualifier |
| F12 | MINOS full walk | chemin applicatif validé contre `indexed_files` canonique | corrigé, à qualifier |
| F13 | lifecycle Lucene par opération | **pas de changement sans preuve de benchmark** ; reste un watch item | différé sur preuve |
| F14 | opt-in sémantique non uniforme | configuration environnement commune CLI/REST/MCP, désactivée par défaut | corrigé, à qualifier |
| F15 | fédération non exposée | CLI + REST + MCP exposent recherche fédérée | corrigé, à qualifier |
| F16 | coûts Git/embeddings | embeddings batchables + batch Ollama ; aucun cache Git sans mesure | partiellement optimisé, watch item Git |
| F17 | absence ContextBundle fédéré | budget global, provenance, fairness, déduplication et sources natives projet-locales | corrigé, à qualifier |
| F18 | distribution orientée checkout | version 0.2.0, wrapper, ZIP autonome, SHA-256, SBOM, runbook recovery | corrigé, à qualifier |

## Invariants renforcés

### Cohérence d'index

SQLite reste la source canonique. Lucene lexical et sémantique restent dérivés et reconstructibles. Une lecture dépendant d'un index exige désormais `IndexStatus.READY` au niveau de la façade applicative.

Une panne pendant l'indexation conduit normalement à `FAILED`. Un crash brutal peut laisser `INDEXING`; cet état ne constitue pas un verrou persistant : la prochaine indexation force un rebuild complet. Le single-flight ne concerne que les traitements réellement concurrents dans le processus actif.

### Scale

La recherche symbole et les tools `find_symbol` / `find_usages` ne matérialisent plus systématiquement l'ensemble du projet. Le graphe est reconstruit uniquement lorsque la génération SQLite change.

Aucun moteur externe n'a été ajouté : les baselines existantes ne justifient toujours pas Zoekt, OpenGrok, OpenSearch ou un index distribué.

### Ressources et providers

- `NEXUS_MAX_FILE_SIZE_BYTES` : 8 MiB par défaut ; exclusion avant SHA-256 et `readString` ;
- `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS` : 180 s par défaut ;
- JDT conserve ses timeouts internes et reçoit en plus une enveloppe globale ;
- les durées importer/provider sont structurées dans `IndexingReport` et exportées par REST sans contenu privé dans les labels.

### Sémantique

Le mode sémantique reste opt-in. `NEXUS_SEMANTIC_PROVIDER=ollama` active explicitement la capacité. Les embeddings sont envoyés par lots bornés ; aucune vector DB n'est introduite.

### Fédération

La recherche fédérée et le contexte fédéré exigent une liste explicite de projets READY. Le contexte :

- partage un budget global déterministe ;
- conserve la provenance projet ;
- entrelace les résultats pour limiter la starvation ;
- déduplique les contenus identiques entre projets ;
- évalue instructions, skills et Git dans leur projet d'origine, sans propagation implicite.

## Watch items conservés après Phase 6

Ces points ne sont pas considérés comme des corrections manquantes de la Phase 6 :

1. **Lifecycle Lucene persistant** : `SearcherManager`/writer partagé uniquement si un benchmark REST/MCP démontre un gain matériel.
2. **Cache Git** : aucun cache persistant sans mesure multi-repository.
3. **Moteur externe** : Zoekt/OpenGrok/OpenSearch uniquement si les nouvelles requêtes bornées et le cache graphe ne suffisent plus.
4. **Vector DB** : non justifiée par le corpus actuel.
5. **Transport MCP distant** : hors périmètre ; stdio local reste la surface prévue.

## Règle de fermeture

F01–F12, F14–F15, F17–F18 ne passent de « corrigé, à qualifier » à « fermé » qu'après :

1. `mvnw.cmd clean install` PASS ;
2. `scripts/self-smoke.ps1` PASS ;
3. contrôles packaging/SBOM/checksums PASS ;
4. exact-head confirmé par `scripts/validate-phase-6.ps1` ;
5. réconciliation finale de la roadmap et de l'issue #13.

Voir aussi : `docs/developer/release-and-recovery.md` et `docs/roadmap.md`.
