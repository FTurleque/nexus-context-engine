# Limites actuelles et dette de consolidation

> Phase 6 intégrée via PR #15 ; hardening post-Phase 6 via PR #18 ; provenance des index via PR #24 ; licence via PR #25 ; CI/supply-chain via PR #28 ; consolidation post-audit via PR #49.

Ce registre distingue les constats **fermés** des limites **réellement ouvertes**. Les ADR, issues et PR conservent l'historique détaillé ; les anciennes branches, anciennes formulations de sécurité et anciens gates ne sont pas des états courants.

## Registre Phase 6

| ID | Sujet | Traitement | État |
|---|---|---|---|
| F01 | top-K fédéré sous-rempli | sur-récupération bornée avant diversification | fermé |
| F02 | gate `READY` non uniforme | gate applicatif commun | fermé |
| F03 | fenêtre SQLite/index dérivés | lecture hors READY interdite, recovery non-READY par rebuild | fermé |
| F04 | scan complet recherche symbolique | préfiltrage SQLite borné avant fuzzy Java | fermé |
| F05 | `findSymbols`/`findUsages` projet-wide | requêtes SQL bornées | fermé |
| F06 | graphe reconstruit par requête | cache dérivé par génération, puis projections SQL bornées via PR #49 | fermé |
| F07 | composition CLI dupliquée | `NexusApplication` composition root | fermé |
| F08 | drift Maven | reactor parent + dependency/plugin management + Enforcer | fermé |
| F09 | coupling Skills Registry | providers composés indépendamment | fermé |
| F10 | absence single-flight | mutex JVM, renforcé par `FileLock` OS | fermé |
| F11 | fichiers non bornés | plafond commun avant hash/lecture | fermé |
| F12 | MINOS full walk | validation contre `indexed_files` canonique | fermé |
| F13 | lifecycle Lucene par opération | aucun changement sans benchmark | watch item |
| F14 | opt-in sémantique non uniforme | configuration commune CLI/REST/MCP | fermé |
| F15 | fédération non exposée | CLI + REST + MCP | fermé |
| F16 | coûts Git/embeddings | batching embeddings ; cache Git différé | partiellement optimisé |
| F17 | absence ContextBundle fédéré | budget global, provenance, fairness, déduplication et borne de travail | fermé |
| F18 | distribution orientée checkout | wrapper, ZIP, checksums, SBOM, runbook | fermé |

## Hardening post-Phase 6 — issue #16 / PR #18

| ID | Constat | Traitement intégré | État |
|---|---|---|---|
| H01 | lecture extérieure via symlink | `ProjectPathGuard`, racine canonique, refus des symlinks, `SafeFileIO` + `NOFOLLOW_LINKS`, lectures bornées | fermé / qualifié |
| H02 | single-flight limité à une JVM | mutex JVM + `FileLock` OS par projet sous `NEXUS_HOME/locks` | fermé / qualifié |
| H03 | timeouts externes incohérents | `ExternalTaskRunner` commun aux importers/providers | fermé / qualifié |
| H04 | readiness ambiguë | liveness/readiness service/project séparées | fermé / qualifié |
| H05 | budget fédéré perdu | fair floor + refill global + ordre local préservé | fermé / qualifié |
| H06 | REST non-loopback insuffisamment protégé | initialement Bearer token ; complété par PR #49 avec robustesse, allowlist et mode d'exposition | fermé / qualifié |
| H07 | UUID inconnu réinterprété comme nom | résolution UUID/nom séparée | fermé / qualifié |
| H08 | mutex JVM conservés indéfiniment | slots libérés après usage | fermé / qualifié |
| H09 | coût SQLite `%substring%` | benchmark avant FTS5/trigram/autre moteur | benchmark établi (#23 clos) — watch item |

## Audit de provenance — issues #19/#20 / PR #24

| ID | Constat | Traitement intégré | État |
|---|---|---|---|
| P19 | snapshot externe JDT/MINOS/provider potentiellement obsolète | changement SOURCE/TEST ⇒ invalidation des snapshots non embarqués persistés | fermé / qualifié |
| P20 | index sémantique réutilisable avec état/modèle/profil incompatible | manifeste Lucene de provenance + rebuild sur mismatch + garde avant embedding | fermé / qualifié |

La provenance sémantique comprend fingerprint canonique, identité provider/modèle, dimensions, profil de préparation du contenu et version de schéma. Voir [`../index-provenance.md`](../index-provenance.md).

## CI / couverture / supply-chain — issue #22 / PR #28 puis PR #49

| ID | Constat | Traitement intégré | État |
|---|---|---|---|
| S22-1 | JaCoCo report-only | gate `check` core à 70 % lignes / 50 % branches | fermé / qualifié |
| S22-2 | gate vulnérabilités incomplet | delta PR + SBOM CycloneDX agrégé du reactor scanné en mode bloquant | fermé / qualifié |
| S22-3 | absence d'analyse statique sécurité | CodeQL Java/Kotlin `security-extended` | fermé / qualifié |
| S22-4 | Actions par tags mutables | Actions contrôlées épinglées à des SHA immuables | fermé / qualifié |
| S22-5 | SBOM non conservé avec la distribution | SBOM embarqué dans le ZIP + artefact CI | fermé / qualifié |
| S22-6 | notices tierces non matérialisées | `THIRD_PARTY_NOTICES.txt`, `failOnMissing=true`, embarqué dans le ZIP | fermé / qualifié |
| S49-D1 | image Docker sans gate CVE complet | Trivy JSON + gate fixable HIGH/CRITICAL | fermé / qualifié |
| S49-D2 | image Docker sans SBOM/provenance publiés | SBOM CycloneDX + attestations sur digest GHCR publié depuis `main` | fermé / qualifié |
| S49-D3 | `.env` Docker généré insuffisamment qualifié | round-trip littéral dédié dans Docker Distribution | fermé / qualifié |

Baseline de couverture de référence : **77,07 % lignes / 58,46 % branches**. Les minima bloquants restent 70 % / 50 %.

## Consolidation post-audit — issue #48 / PR #49

### P1

| Constat | Traitement intégré | État |
|---|---|---|
| snapshot d'indexation incohérent face aux mutations concurrentes | fingerprint/snapshot revalidé et opération fail-closed avant publication d'un état mixte | fermé / qualifié |
| OSV incomplet sur le reactor | génération Maven du SBOM agrégé puis scan OSV bloquant | fermé / qualifié |
| matérialisation globale graphe | projections SQL bornées et budgets de nœuds/arêtes | fermé / qualifié |
| coût de travail du contexte fédéré non borné | budget de travail distinct du budget final | fermé / qualifié |
| SCIP partageait une limite générique | politique SCIP dédiée + borne du message Protobuf avant allocation | fermé / qualifié |

### P2

| Constat | Traitement intégré | État |
|---|---|---|
| limites de résultats divergentes | `ResultLimitPolicy` commune CLI/REST/MCP | fermé / qualifié |
| exposition REST distante trop permissive | token robuste + racines autorisées + mode TLS/exposition explicite | fermé / qualifié |
| génération `.cmd` native | échappement durci + qualification dédiée | fermé / qualifié |
| génération `.env` Docker | échappement littéral + test round-trip | fermé / qualifié |
| supply-chain image Docker | Trivy + SBOM + attestations | fermé / qualifié |
| intégrations assistants testées sans argv réel Windows | tests argv réels PowerShell/cmd | fermé / qualifié |

### P3

| Constat | Traitement intégré | État |
|---|---|---|
| readiness sans projet ambiguë | état explicite sans projet enregistré | fermé / qualifié |
| `index_generation` bump sans changement | bump supprimé pour les no-op effectifs | fermé / qualifié |
| providers externes dupliqués | déduplication SQL + migration/indexes | fermé / qualifié |
| alignement Jackson | dependency management/BOM cohérent | fermé / qualifié |
| stratégie `develop` | documentation et workflow réconciliés avec `main` comme branche d'intégration protégée | fermé / qualifié |
| docs post-correctifs | réconciliation finale dans la PR documentaire post-merge | en cours jusqu'au merge de cette PR |

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
- snapshot canonique revalidé avant publication ; une mutation concurrente détectée fait échouer l'indexation ;
- `index_generation` ne progresse pas pour un no-op effectif ;
- support de verrouillage inter-processus revendiqué pour un `NEXUS_HOME` local ;
- filesystem réseau ⇒ non supporté sans qualification spécifique.

### Ressources et providers

- `NEXUS_MAX_FILE_SIZE_BYTES` : 8 MiB par défaut ;
- `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS` : 180 s par défaut ;
- SCIP possède en plus ses propres plafonds de fichier/message ;
- importers et providers utilisent la même enveloppe wall-clock ;
- un worker Java tiers qui ignore définitivement l'interruption peut survivre comme daemon après timeout.

Ce dernier point reste un risque résiduel ; isolation processus/circuit-breaker ne sera introduit que si des providers réels le justifient.

### Readiness

- **liveness** : processus vivant ;
- **readiness service** : dépendances de base accessibles ;
- **project readiness** : projet `READY` avant lecture indexée ;
- **aucun projet enregistré** : état explicite, sans être confondu avec « tous les projets sont READY » ;
- **degraded** : au moins un projet `FAILED` ;
- **allProjectsReady** : vrai uniquement lorsque l'ensemble enregistré satisfait réellement le contrat.

### Graphe et fédération

Le graphe projet n'exige plus de charger globalement tous les symboles/relations pour répondre aux besoins de ranking : le repository expose des projections bornées.

Le fair floor et le refill évitent la starvation et réutilisent le budget libéré après déduplication. Le coût de préparation du contexte fédéré est lui-même borné ; le budget final n'est donc plus l'unique protection contre un travail excessif.

### Limites de résultats

Les surfaces CLI, REST et MCP partagent une politique maximale commune. Une surface ne peut plus contourner le plafond en acceptant une valeur arbitrairement supérieure.

### Sécurité REST

- loopback + aucun token : fonctionnement local autorisé ;
- hors loopback + token absent/faible : démarrage refusé ;
- hors loopback + allowlist de racines absente : démarrage refusé ;
- hors loopback + mode d'exposition absent/invalide : démarrage refusé ;
- modes distants admis : `reverse-proxy-https` ou `direct-https` ;
- `loopback-forward` : uniquement avec `NEXUS_RUNTIME=docker`, pour une publication hôte maintenue sur loopback ;
- racines administrables : canonicalisées et limitées par `NEXUS_REST_ALLOWED_PROJECT_ROOTS`.

La robustesse du token distant exige au moins 32 octets et une entropie estimée d'au moins 96 bits.

## Watch items réellement ouverts

1. **Scale SQLite substring** — benchmark requis avant FTS5/trigram/autre moteur.
2. **Lifecycle Lucene persistant** — writer/SearcherManager partagé uniquement si un benchmark montre un bénéfice matériel.
3. **Cache Git** — pas de cache persistant sans mesure multi-repository.
4. **Provider Java non coopératif** — envisager isolation processus seulement avec preuve opérationnelle.
5. **Filesystem activement hostile** — sécurité absolue hors périmètre portable actuel.
6. **Filesystem réseau pour `NEXUS_HOME`** — non supporté sans qualification dédiée.
7. **Compatibilité juridique d'une nouvelle dépendance inhabituelle** — revue explicite malgré l'inventaire automatisé des licences.
8. **Ollama indisponible / corruption physique Lucene** — conserver et renforcer les scénarios de récupération explicites lorsque des cas réels le justifient.

## Qualification récente

Consolidation post-audit PR #49 :

```text
QUALIFIED_HEAD=4f04c1ad3ff5b41aa9d1892ade57ad62b90a43f9
MERGE_SHA=c1ff9ef03ef33097c0d51154e02c30109b0a46f1
```

- NEXUS CI `31314135008` : PASS ;
- Scale Benchmark `31314135000` : PASS ;
- Windows Installer `31314134983` : PASS ;
- Docker Distribution `31314134994` : PASS ;
- CodeQL `31314134977` : PASS ;
- OSV-Scanner `31314135231` : PASS.

Aucun workflow/configuration/status SonarCloud actif n'est défini dans le dépôt qualifié ; SonarCloud n'est pas un gate exécutable actuel.

Voir aussi : [`release-and-recovery.md`](release-and-recovery.md), [`ci-and-supply-chain.md`](ci-and-supply-chain.md), [`../roadmap.md`](../roadmap.md) et [`../index-provenance.md`](../index-provenance.md).
