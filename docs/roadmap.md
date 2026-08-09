# Feuille de route NEXUS

Cette feuille de route est la source de vérité active pour l'évolution de NEXUS. Les détails historiques restent dans les issues, PR et ADR ; ce document décrit l'état réellement intégré dans `main` et les travaux futurs explicitement suivis.

## État courant

```text
repository    FTurleque/nexus-context-engine
visibility    public
main          Phase 6 + hardening + provenance + supply-chain + Windows/Docker + post-audit intégrés
version       0.2.0
Java          runtime >=21 / release 21
Phase 1→6     livrées / intégrées
hardening     PR #18
provenance    PR #24
licence       PR #25
supply-chain  PR #28, renforcée par PR #49
windows       PR #41
wizard        issue #45 / PR #46
post-audit    issue #48 / PR #49
final-docs    PR #61
```

Principe directeur :

> **qualité du contexte > correctness > passage à l'échelle > opérabilité > nombre de fonctionnalités > nombre d'intégrations.**

## État consolidé

| Lot | Référence | État |
|---|---|---|
| Phase 1 — Valider le moteur | I0 → I4 | ✅ terminée |
| Phase 2 — Étendre les sources de contexte | I5 → I7 | ✅ terminée |
| Phase 3 — Enrichir l'intelligence de code | I8 → I10 | ✅ terminée |
| Phase 4 — Exposer NEXUS aux autres outils | I11 → I13 | ✅ terminée |
| Phase 5 — Écosystème et passage à l'échelle | I14 → I17 | ✅ terminée |
| Phase 6 — Consolidation, hardening et industrialisation | PR #15 | ✅ intégrée |
| Hardening post-Phase 6 | issue #16 / PR #18 | ✅ intégré |
| Provenance/fraîcheur des index | issues #19/#20 / PR #24 | ✅ intégré |
| Licence propriétaire publique | PR #25 | ✅ intégrée |
| Réconciliation documentaire initiale | issue #21 / PR #26 | ✅ intégrée |
| CI / couverture / supply-chain | issue #22 / PR #28 | ✅ intégrée |
| Benchmark scale SQLite/fédération | issue #23 | ✅ établi |
| Distribution Windows EXE autonome | issue #40 / PR #41 | ✅ intégrée |
| Assistant Windows Natif/Docker/Both | issue #45 / PR #46 | ✅ intégré |
| Consolidation post-audit P1/P2/P3 | issue #48 / PR #49 | ✅ intégrée et qualifiée |
| Réconciliation documentaire post-audit | PR #61 | ✅ intégrée et qualifiée |

## Baseline fonctionnelle livrée

NEXUS 0.2.0 fournit notamment :

- indexation locale incrémentale avec SQLite canonique et index Lucene dérivés ;
- détection fail-closed d'une mutation du repository pendant la construction d'un snapshot ;
- JavaParser, Markdown, SCIP opportuniste avec limites dédiées, JDT LS opt-in et import MINOS explicite ;
- recherche fichier/symbole/graphe/Git et fédération multi-projet ;
- projections de graphe bornées côté SQLite ;
- recherche sémantique locale opt-in avec provenance ;
- `ContextBundle` projet-local et fédéré avec budget final et coût de travail borné ;
- limite maximale commune des résultats CLI/REST/MCP ;
- instructions natives, Agent Skills, AI Skills Registry et contexte Git local ;
- CLI, REST Quarkus et MCP Java STDIO ;
- distribution CLI autonome, installateur Windows EXE et assistant Natif/Docker/Both ;
- image Docker qualifiée par Trivy, SBOM CycloneDX et attestations de provenance/SBOM sur publication `main`.

## Invariants de hardening

- confinement filesystem sous racine canonique, refus des symlinks et lectures `NOFOLLOW_LINKS` ;
- taille réelle revalidée avant hash/lecture ;
- mutex JVM + `FileLock` OS par projet sur `NEXUS_HOME` local ;
- snapshot canonique revalidé avant publication ;
- providers/importers externes bornés par timeout wall-clock ;
- liveness, readiness service et readiness projet séparées ;
- graphe et contexte fédéré bornés en coût de travail ;
- plafond commun des résultats CLI/REST/MCP ;
- politique SCIP dédiée avant allocation Protobuf ;
- exposition REST distante fail-closed ;
- pas de bump `index_generation` sur no-op effectif ;
- déduplication SQL des providers externes persistés.

## Sécurité REST actuelle

Loopback reste autorisé sans token par défaut. Une écoute hors loopback exige simultanément :

- `NEXUS_REST_API_TOKEN` robuste — minimum 32 octets et entropie estimée minimale de 96 bits ;
- `NEXUS_REST_ALLOWED_PROJECT_ROOTS` non vide ;
- `NEXUS_REST_EXPOSURE_MODE=reverse-proxy-https|direct-https` ;
- `loopback-forward` uniquement avec `NEXUS_RUNTIME=docker` et publication hôte maintenue sur loopback.

## CI et supply-chain active

- NEXUS CI : Windows Java 24 + Linux Java 21 ;
- JaCoCo bloquant sur `core` : 70 % lignes / 50 % branches minimum ;
- Windows Installer ;
- Scale Benchmark ;
- CodeQL Java/Kotlin `security-extended` ;
- OSV delta PR + scan bloquant du SBOM CycloneDX agrégé du reactor ;
- Dependabot Maven, GitHub Actions et Docker ;
- Docker Distribution avec round-trip dotenv, Trivy, SBOM image et gate HIGH/CRITICAL corrigibles ;
- publication GHCR sur `main` avec attestations de provenance et SBOM ;
- Actions contrôlées épinglées à des SHA immuables.

Aucun workflow/configuration/status SonarCloud actif n'est défini dans la baseline courante.

## Qualification de la consolidation

PR #49 :

```text
QUALIFIED_HEAD=4f04c1ad3ff5b41aa9d1892ade57ad62b90a43f9
MERGE_SHA=c1ff9ef03ef33097c0d51154e02c30109b0a46f1
```

Gates : NEXUS CI `31314135008`, Scale Benchmark `31314135000`, Windows Installer `31314134983`, Docker Distribution `31314134994`, CodeQL `31314134977`, OSV-Scanner `31314135231` — PASS.

PR #61 :

```text
QUALIFIED_HEAD=ba91be044a600d2396e0939fc154848dc47f6310
MERGE_SHA=660ca9f07a23950d2a5284605531524372331bc5
```

Gates : NEXUS CI `31315318844`, CodeQL `31315318865`, OSV-Scanner `31315319213` — PASS. Le premier Linux a rencontré un HTTP 429 Maven Central ; un unique rerun exact-head a passé sans modification du projet.

## Travail restant priorisé

Il n'existe plus de reliquat P1/P2/P3 de l'issue #48. Les sujets ouverts sont des **watch items**, pas des bugs à corriger sans preuve :

1. issue #50 — lifecycle Lucene persistant : benchmark avant changement ;
2. issue #51 — providers externes non coopératifs : isolation seulement sur cas réel ;
3. issue #52 — filesystem hostile/réseau : qualification dédiée avant extension du support ;
4. issue #53 — cache Git persistant : mesures multi-repository avant adoption ;
5. issue #54 — recovery sémantique/Ollama/Lucene : renforcer lorsque des scénarios opérationnels le justifient ;
6. issue #55 — revue juridique explicite des dépendances inhabituelles.

## Références

- Architecture : [`architecture.md`](architecture.md)
- Arc42 : [`architecture/README.md`](architecture/README.md)
- Provenance : [`index-provenance.md`](index-provenance.md)
- CI / supply-chain : [`developer/ci-and-supply-chain.md`](developer/ci-and-supply-chain.md)
- Limites courantes : [`developer/current-limitations.md`](developer/current-limitations.md)
- Release/recovery : [`developer/release-and-recovery.md`](developer/release-and-recovery.md)
- ADR : [`adr/`](adr/)
