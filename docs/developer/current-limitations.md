# Limites actuelles et dette de consolidation

> État courant : Phase 6 PR #15 ; hardening PR #18 ; provenance PR #24 ; licence PR #25 ; CI/supply-chain PR #28 ; consolidation post-audit PR #49 ; réconciliation documentaire finale PR #61.

Ce registre distingue les constats **fermés** des limites **réellement ouvertes**. Les anciennes formulations et anciennes preuves de qualification ne représentent pas l'état courant.

## Consolidation terminée

### Phase 6 / hardening

| Sujet | Traitement | État |
|---|---|---|
| top-K fédéré sous-rempli | sur-récupération bornée avant diversification | fermé |
| gate `READY` non uniforme | gate applicatif commun | fermé |
| fenêtre SQLite/index dérivés | lecture hors READY interdite, recovery non-READY par rebuild | fermé |
| recherche symbolique projet-wide | requêtes SQL bornées | fermé |
| graphe reconstruit/matérialisé globalement | cache dérivé puis projections SQL bornées | fermé |
| absence single-flight | mutex JVM + `FileLock` OS par projet | fermé |
| fichiers non bornés | plafond commun avant consommation | fermé |
| lifecycle Lucene par opération | changement conditionné à un benchmark | watch item #50 |
| distribution orientée checkout | wrapper, ZIP, Windows EXE, checksums, SBOM, runbooks | fermé |

### Provenance — PR #24

- changement SOURCE/TEST ⇒ invalidation des snapshots externes persistés concernés ;
- index sémantique ⇒ manifeste avec fingerprint canonique, provider, modèle, dimensions, profil de préparation et version de schéma ;
- provenance absente/incompatible ⇒ rebuild ;
- recherche sémantique stale refusée avant embedding de requête.

### CI / supply-chain — PR #28 puis PR #49

- JaCoCo core bloquant : 70 % lignes / 50 % branches ;
- CodeQL Java/Kotlin `security-extended` ;
- OSV delta PR + SBOM CycloneDX agrégé du reactor scanné en mode bloquant ;
- Actions contrôlées épinglées à des SHA immuables ;
- `THIRD_PARTY_NOTICES.txt` avec `failOnMissing=true` ;
- SBOM distribué et conservé comme preuve CI ;
- Dependabot Maven, GitHub Actions et Docker ;
- image Docker : Trivy, SBOM CycloneDX, gate HIGH/CRITICAL corrigibles, attestations de provenance et SBOM sur publication `main`.

## Consolidation post-audit — issue #48 / PR #49

### P1 — tous fermés / qualifiés

- snapshot d'indexation cohérent face aux mutations concurrentes du repository ;
- gate OSV complet pour le reactor Maven via SBOM agrégé ;
- projections SQL bornées pour le graphe projet ;
- coût de travail du contexte fédéré borné indépendamment du budget final ;
- politique SCIP dédiée + borne du message Protobuf avant allocation.

### P2 — tous fermés / qualifiés

- `ResultLimitPolicy` commune CLI/REST/MCP ;
- exposition REST distante : token robuste + racines autorisées + mode d'exposition explicite ;
- génération `.cmd` native durcie ;
- génération `.env` Docker durcie et qualifiée par round-trip ;
- image Docker : CVE/SBOM/attestations ;
- tests argv réels Windows pour les intégrations assistants.

### P3 — tous fermés / qualifiés

- readiness explicite lorsqu'aucun projet n'est enregistré ;
- pas de bump `index_generation` sur no-op effectif ;
- déduplication SQL des providers externes persistés ;
- alignement Jackson via dependency management/BOM ;
- stratégie `develop` réconciliée avec `main` comme branche d'intégration protégée ;
- README, roadmap, current limitations et runbooks réconciliés via PR #61.

## Invariants actuels

### Filesystem

La racine projet est canonicalisée. `ProjectPathGuard`, `SafeFileIO` et `NOFOLLOW_LINKS` protègent les lectures sensibles. `NEXUS_MAX_FILE_SIZE_BYTES` est appliqué au moment de la consommation.

**Limite résiduelle :** les primitives Java portables ne constituent pas un sandbox absolu contre un acteur local qui modifie agressivement des ancêtres ou hard-links pendant le traitement. Suivi : issue #52.

### Cohérence et concurrence d'index

SQLite reste canonique ; Lucene lexical/sémantique et intelligence externe sont dérivés.

- lecture indexée ⇒ projet `READY` ;
- état persistant non-READY ⇒ rebuild complet à la prochaine indexation ;
- mutation par projet ⇒ mutex JVM + `FileLock` OS ;
- snapshot canonique revalidé avant publication ;
- mutation concurrente détectée ⇒ échec fail-closed ;
- `index_generation` ne progresse pas pour un no-op effectif ;
- garantie inter-processus revendiquée uniquement sur filesystem local.

### Providers externes

- `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS` : 180 s par défaut ;
- importers/providers utilisent une enveloppe wall-clock commune ;
- un worker Java tiers ignorant définitivement l'interruption peut survivre comme daemon.

Ce risque est suivi par l'issue #51 ; l'isolation processus n'est pas introduite sans cas réel reproductible.

### Readiness

- liveness : processus vivant ;
- readiness service : dépendances de base accessibles ;
- project readiness : projet `READY` avant lecture indexée ;
- aucun projet enregistré : état explicite, distinct de « tous les projets sont READY » ;
- degraded : au moins un projet `FAILED`.

### Graphe, fédération et résultats

- graphe projet : projections et voisinages bornés côté repository ;
- contexte fédéré : fair floor, déduplication, refill global et borne du travail préparatoire ;
- CLI/REST/MCP : plafond maximal commun des résultats.

### Sécurité REST

Loopback sans token reste autorisé localement. Hors loopback :

- token absent/faible ⇒ démarrage refusé ;
- allowlist `NEXUS_REST_ALLOWED_PROJECT_ROOTS` absente/vide ⇒ refus ;
- `NEXUS_REST_EXPOSURE_MODE` absent/invalide ⇒ refus ;
- modes distants admis : `reverse-proxy-https` ou `direct-https` ;
- `loopback-forward` uniquement avec `NEXUS_RUNTIME=docker` et publication hôte sur loopback ;
- token distant : minimum 32 octets et entropie estimée ≥ 96 bits.

## Watch items réellement ouverts

1. **#50 Lifecycle Lucene persistant** — benchmark représentatif avant writer/SearcherManager partagé.
2. **#51 Provider externe non coopératif** — isolation processus seulement sur fixture/cas réel.
3. **#52 Filesystem hostile/réseau** — qualification spécifique avant extension du support.
4. **#53 Cache Git persistant** — mesures cold/warm et invalidation avant adoption.
5. **#54 Recovery sémantique** — Ollama indisponible et corruption Lucene physique.
6. **#55 Dépendance inhabituelle** — revue juridique explicite malgré l'inventaire automatisé.
7. **Scale SQLite substring** — aucun FTS5/trigram/autre moteur sans benchmark matériellement favorable.

## Qualification récente

PR #49 :

```text
QUALIFIED_HEAD=4f04c1ad3ff5b41aa9d1892ade57ad62b90a43f9
MERGE_SHA=c1ff9ef03ef33097c0d51154e02c30109b0a46f1
```

NEXUS CI `31314135008`, Scale Benchmark `31314135000`, Windows Installer `31314134983`, Docker Distribution `31314134994`, CodeQL `31314134977`, OSV-Scanner `31314135231` — PASS.

PR #61 :

```text
QUALIFIED_HEAD=ba91be044a600d2396e0939fc154848dc47f6310
MERGE_SHA=660ca9f07a23950d2a5284605531524372331bc5
```

NEXUS CI `31315318844`, CodeQL `31315318865`, OSV-Scanner `31315319213` — PASS. Le premier Linux a rencontré un HTTP 429 Maven Central ; un unique rerun exact-head est passé sans modification du projet.

Aucun workflow/configuration/status SonarCloud actif n'est défini dans la baseline courante.

Voir aussi : [`release-and-recovery.md`](release-and-recovery.md), [`ci-and-supply-chain.md`](ci-and-supply-chain.md), [`../roadmap.md`](../roadmap.md) et [`../index-provenance.md`](../index-provenance.md).
