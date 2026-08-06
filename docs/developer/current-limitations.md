# Limites actuelles et dette de consolidation

> Phase 6 intégrée via PR #15 ; hardening post-Phase 6 intégré via PR #18 ; hardening de provenance des index intégré via PR #24 ; licence propriétaire publique intégrée via PR #25.

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
| H09 | coût SQLite `%substring%` | benchmark avant FTS5/trigram/autre moteur | ouvert / #23 |

## Audit de provenance — issues #19/#20 / PR #24

| ID | Constat | Traitement intégré | État |
|---|---|---|---|
| P19 | snapshot externe JDT/MINOS/provider potentiellement obsolète | changement SOURCE/TEST ⇒ invalidation de tous les snapshots non embarqués persistés, même provider absent du runtime courant | fermé / qualifié |
| P20 | index sémantique réutilisable avec état/modèle/profil incompatible | manifeste Lucene de provenance + rebuild sur mismatch + garde de recherche avant embedding | fermé / qualifié |

La provenance sémantique comprend le fingerprint canonique, l'identité provider/modèle, les dimensions, le profil de préparation du contenu et la version de schéma. Voir [`../index-provenance.md`](../index-provenance.md).

## Invariants actuels

### Frontière filesystem

La racine projet est canonicalisée. Les chemins applicatifs sont vérifiés par `ProjectPathGuard`; les ouvertures sensibles utilisent `SafeFileIO` et `NOFOLLOW_LINKS`. Le scanner, les fichiers d'ignore, instructions/références, Agent Skills, JDT LS, `ContextFragmentFactory` et SCIP suivent cette frontière.

`NEXUS_MAX_FILE_SIZE_BYTES` est appliqué au moment de la consommation, pas uniquement au scan.

**Limite résiduelle :** le modèle Java portable n'est pas un sandbox contre un acteur local qui modifie agressivement les répertoires ancêtres ou manipule des hard-links pendant le traitement. Ce threat model nécessiterait des handles de répertoire sécurisés lorsqu'ils sont disponibles ou une isolation OS/processus.

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

Le fair floor et le refill évitent la starvation et réutilisent le budget libéré après déduplication. Le coût d'overfetch local reste borné mais doit être benchmarké pour des portfolios beaucoup plus larges (#23).

### Sécurité REST

- loopback + aucun token : fonctionnement local ;
- token configuré : Bearer auth requise ;
- non-loopback + aucun token : démarrage refusé ;
- non-loopback + token : exposition autorisée.

Les endpoints techniques Quarkus (health/métriques) restent une décision d'exploitation distincte des endpoints applicatifs.

## Watch items ouverts

1. **Scale SQLite substring** — benchmark #23 avant FTS5/trigram/autre moteur.
2. **Portfolios très larges** — mesurer overfetch et coût fédéré #23.
3. **Lifecycle Lucene persistant** — writer/SearcherManager partagé uniquement si benchmark utile.
4. **Cache Git** — pas de cache persistant sans mesure multi-repository.
5. **Provider Java non coopératif** — envisager isolation processus seulement avec preuve opérationnelle.
6. **Filesystem activement hostile** — sécurité absolue hors périmètre portable actuel.
7. **Filesystem réseau pour `NEXUS_HOME`** — non supporté sans qualification dédiée.
8. **Supply-chain publique** — vulnérabilités, code scanning, couverture, actions immuables et notices tierces suivis par #22.
9. **Ollama indisponible / corruption physique Lucene** — renforcer les scénarios de récupération explicites.

## Qualification récente

PR #24, head exact `25c12b100b774a4ec3d69d221675bf31d8ebaa0c`, NEXUS CI run #15 :

- Windows Java 24 : PASS ;
- `scripts/validate-phase-6.ps1` : PASS ;
- Linux Java 21 Maven reactor : PASS ;
- distribution smoke : PASS.

PR #24 est intégrée dans `main` via `c7a03479a78713b78ec2ddc477e1d07d400d8aba`.

Voir aussi : [`release-and-recovery.md`](release-and-recovery.md), [`../roadmap.md`](../roadmap.md) et [`../index-provenance.md`](../index-provenance.md).
