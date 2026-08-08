# Limites actuelles et dette de consolidation

> Phase 6 intégrée via PR #15 ; hardening post-Phase 6 via PR #18 ; provenance des index via PR #24 ; licence propriétaire publique via PR #25 ; CI/supply-chain via PR #28.

Ce registre distingue les constats **fermés** des limites **réellement ouvertes**. Les ADR et PR conservent l'historique détaillé ; les anciennes branches/gates ne sont pas des états courants.

## Registre Phase 6

| ID | Sujet | Traitement | État |
|---|---|---|---|
| F01 | top-K fédéré sous-rempli | sur-récupération bornée avant diversification | fermé |
| F02 | gate `READY` non uniforme | gate applicatif commun | fermé |
| F03 | fenêtre SQLite/index dérivés | lecture hors READY interdite, recovery non-READY par rebuild | fermé |
| F04 | scan complet recherche symbolique | préfiltrage SQLite borné avant fuzzy Java | fermé |
| F05 | `findSymbols`/`findUsages` projet-wide | requêtes SQL bornées | fermé |
| F06 | graphe reconstruit par requête | cache dérivé par génération | fermé |
| F07 | composition CLI dupliquée | `NexusApplication` composition root | fermé |
| F08 | drift Maven | reactor parent + dependency/plugin management + Enforcer | fermé |
| F09 | coupling Skills Registry | providers composés indépendamment | fermé |
| F10 | absence single-flight | mutex JVM, renforcé ensuite par `FileLock` OS | fermé |
| F11 | fichiers non bornés | plafond commun avant hash/lecture | fermé |
| F12 | MINOS full walk | validation contre `indexed_files` canonique | fermé |
| F13 | lifecycle Lucene par opération | aucun changement sans benchmark | watch item |
| F14 | opt-in sémantique non uniforme | configuration commune CLI/REST/MCP | fermé |
| F15 | fédération non exposée | CLI + REST + MCP | fermé |
| F16 | coûts Git/embeddings | batching embeddings ; cache Git différé | partiellement optimisé |
| F17 | absence ContextBundle fédéré | budget global, provenance, fairness, déduplication | fermé |
| F18 | distribution orientée checkout | wrapper, ZIP, checksums, SBOM, runbook | fermé |

## Hardening post-Phase 6 — issue #16 / PR #18

| ID | Constat | Traitement intégré | État |
|---|---|---|---|
| H01 | lecture extérieure via symlink | `ProjectPathGuard`, racine canonique, refus des symlinks, `SafeFileIO` + `NOFOLLOW_LINKS`, lectures bornées | fermé / qualifié |
| H02 | single-flight limité à une JVM | mutex JVM + `FileLock` OS par projet sous `NEXUS_HOME/locks` | fermé / qualifié |
| H03 | timeouts externes incohérents | `ExternalTaskRunner` commun aux importers/providers | fermé / qualifié |
| H04 | readiness ambiguë | liveness/readiness service/project séparées | fermé / qualifié |
| H05 | budget fédéré perdu | fair floor + refill global + ordre local préservé | fermé / qualifié |
| H06 | REST non-loopback sans auth | fail-fast + Bearer token | fermé / qualifié |
| H07 | UUID inconnu réinterprété comme nom | résolution UUID/nom séparée | fermé / qualifié |
| H08 | mutex JVM conservés indéfiniment | slots libérés après usage | fermé / qualifié |
| H09 | coût SQLite `%substring%` | benchmark avant FTS5/trigram/autre moteur | benchmark établi (#23 clos) — watch item |

## Audit de provenance — issues #19/#20 / PR #24

| ID | Constat | Traitement intégré | État |
|---|---|---|---|
| P19 | snapshot externe JDT/MINOS/provider potentiellement obsolète | changement SOURCE/TEST ⇒ invalidation de tous les snapshots non embarqués persistés, même provider absent du runtime courant | fermé / qualifié |
| P20 | index sémantique réutilisable avec état/modèle/profil incompatible | manifeste Lucene de provenance + rebuild sur mismatch + garde de recherche avant embedding | fermé / qualifié |

La provenance sémantique comprend le fingerprint canonique, l'identité provider/modèle, les dimensions, le profil de préparation du contenu et la version de schéma. Voir [`../index-provenance.md`](../index-provenance.md).

## CI / couverture / supply-chain — issue #22 / PR #28

| ID | Constat | Traitement intégré | État |
|---|---|---|---|
| S22-1 | JaCoCo report-only | gate `check` core à 70 % lignes / 50 % branches | fermé / qualifié |
| S22-2 | absence de gate vulnérabilités | OSV-Scanner PR + scan courant/hebdomadaire | fermé / qualifié |
| S22-3 | absence d'analyse statique sécurité | CodeQL Java/Kotlin `security-extended` | fermé / qualifié |
| S22-4 | Actions par tags mutables | Actions contrôlées épinglées à des SHA immuables | fermé / qualifié |
| S22-5 | SBOM non conservé avec la distribution | SBOM embarqué dans le ZIP + artefact CI 90 jours | fermé / qualifié |
| S22-6 | notices tierces non matérialisées | `THIRD_PARTY_NOTICES.txt`, `failOnMissing=true`, embarqué dans le ZIP | fermé / qualifié |

Baseline qualifiée de couverture : **77,07 % lignes / 58,46 % branches**. Voir [`ci-and-supply-chain.md`](ci-and-supply-chain.md).

## Invariants actuels

### Frontière filesystem

La racine projet est canonicalisée. Les chemins applicatifs sont vérifiés par `ProjectPathGuard`; les ouvertures sensibles utilisent `SafeFileIO` et `NOFOLLOW_LINKS`. Le scanner, les fichiers d'ignore, instructions/références, Agent Skills, JDT LS, `ContextFragmentFactory` et SCIP suivent cette frontière.

`NEXUS_MAX_FILE_SIZE_BYTES` est appliqué au moment de la consommation, pas uniquement au scan.

**Limite résiduelle :** le modèle Java portable n'est pas un sandbox contre un acteur local qui modifie agressivement les répertoires ancêtres ou manipule des hard-links pendant le traitement.

### Cohérence et concurrence d'index

SQLite reste canonique. Lucene lexical/sémantique et intelligence externe sont dérivés.

- lecture dépendant d'un index ⇒ projet `READY` ;
- état persistant non-READY ⇒ rebuild complet à l'indexation suivante ;
- mutation par projet ⇒ mutex JVM + `FileLock` OS ;
- support de verrouillage inter-processus revendiqué pour un `NEXUS_HOME` local ;
- filesystem réseau ⇒ non supporté sans qualification spécifique.

### Ressources et providers

- `NEXUS_MAX_FILE_SIZE_BYTES` : 8 MiB par défaut ;
- `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS` : 180 s par défaut ;
- importers et providers utilisent la même enveloppe wall-clock ;
- un worker Java tiers qui ignore définitivement l'interruption peut survivre comme daemon après timeout.

Ce dernier point reste un risque résiduel ; isolation processus/circuit-breaker ne sera introduit que si des providers réels le justifient.

### Readiness

- **liveness** : processus vivant ;
- **readiness service** : dépendances de base accessibles ;
- **project readiness** : projet `READY` avant lecture indexée ;
- **degraded** : au moins un projet `FAILED` ;
- **allProjectsReady** : tous les projets enregistrés `READY`.

### Fédération

Le fair floor et le refill évitent la starvation et réutilisent le budget libéré après déduplication. Le coût d'overfetch local reste borné ; le benchmark de scale (#23, clos, outillé par le workflow *Scale Benchmark*) couvre les portfolios beaucoup plus larges.

### Sécurité REST

- loopback + aucun token : fonctionnement local ;
- token configuré : Bearer auth requise ;
- non-loopback + aucun token : démarrage refusé ;
- non-loopback + token : exposition autorisée.

## Watch items

1. **Scale SQLite substring** — benchmark établi (#23 clos, workflow *Scale Benchmark*) ; requis avant FTS5/trigram/autre moteur.
2. **Portfolios très larges** — overfetch et coût fédéré couverts par le benchmark #23.
3. **Lifecycle Lucene persistant** — writer/SearcherManager partagé uniquement si benchmark utile.
4. **Cache Git** — pas de cache persistant sans mesure multi-repository.
5. **Provider Java non coopératif** — envisager isolation processus seulement avec preuve opérationnelle.
6. **Filesystem activement hostile** — sécurité absolue hors périmètre portable actuel.
7. **Filesystem réseau pour `NEXUS_HOME`** — non supporté sans qualification dédiée.
8. **Compatibilité juridique d'une nouvelle dépendance inhabituelle** — revue explicite malgré l'inventaire automatisé des licences.
9. **Ollama indisponible / corruption physique Lucene** — renforcer les scénarios de récupération explicites.

## Qualification récente

PR #28, head exact `a363e93dc97597d288389b4f4b9e8404abe4296c` :

- NEXUS CI run #31 : Windows Java 24 PASS ; Linux Java 21 PASS ; JaCoCo 70/50 PASS ; distribution/compliance PASS ;
- OSV-Scanner run #4 : PASS ;
- CodeQL run #6 : PASS.

PR #28 est intégrée dans `main` via `4c9b7cd4e26913af42f687b48718c8e733fa06f7`.

Voir aussi : [`release-and-recovery.md`](release-and-recovery.md), [`ci-and-supply-chain.md`](ci-and-supply-chain.md), [`../roadmap.md`](../roadmap.md) et [`../index-provenance.md`](../index-provenance.md).
