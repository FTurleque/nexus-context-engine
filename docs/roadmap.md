# Feuille de route NEXUS

Cette feuille de route est la source de vérité active pour l'évolution de NEXUS. Les détails historiques d'implémentation restent conservés dans les issues, PR et ADR correspondants ; ce document décrit l'état courant et le prochain travail attendu.

## État courant

```text
repository    FTurleque/nexus-context-engine
visibility    public
main          Phase 6 + hardening + provenance + licence + supply-chain + Windows/Docker intégrés
version       0.2.0
Java          runtime >=21 / release 21
Phase 1→6     livrées / intégrées
hardening     post-Phase 6 intégré
P1 audit      #19 + #20 intégrés via PR #24
docs          #21 intégré via PR #26
supply-chain  #22 intégré via PR #28, renforcé via PR #49
licence       propriétaire source-available via PR #25
windows       EXE installer autonome intégré via PR #41
wizard        issue #45 / PR #46 intégrée
post-audit    issue #48 / PR #49 intégrée techniquement ; réconciliation documentaire finale en cours
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
| Intégration compagnon MINOS | issue #11 / PR #12 | ✅ livrée |
| Phase 6 — Consolidation, hardening et industrialisation | I18 → I24 / PR #15 | ✅ intégrée |
| Hardening post-Phase 6 | issue #16 / PR #18 | ✅ intégré |
| Provenance/fraîcheur des index | issues #19/#20 / PR #24 | ✅ intégré |
| Licence propriétaire publique | PR #25 | ✅ intégrée |
| Réconciliation documentaire initiale | issue #21 / PR #26 | ✅ intégrée |
| CI / couverture / supply-chain | issue #22 / PR #28 | ✅ intégrée |
| Distribution Windows EXE autonome | issue #40 / PR #41 | ✅ intégrée |
| Benchmark scale SQLite/fédération | issue #23 | ✅ établi |
| Assistant de déploiement Windows (Natif/Docker/Both) | issue #45 / PR #46 | ✅ intégré |
| Consolidation post-audit P1/P2/P3 | issue #48 / PR #49 | ✅ code + gates + merge ; docs finales via PR dédiée |

## Baseline fonctionnelle livrée

NEXUS 0.2.0 fournit notamment :

- indexation locale incrémentale avec SQLite canonique et index dérivés Lucene ;
- détection fail-closed d'une mutation canonique du repository pendant la construction d'un snapshot ;
- JavaParser, Markdown et recherche lexicale polyglotte ;
- SCIP opportuniste avec limites dédiées de taille et de message ;
- JDT LS opt-in et import MINOS explicite ;
- recherche fichier/symbole/graphe/Git et fédération multi-projet ;
- projections de graphe bornées côté SQLite ;
- recherche sémantique locale opt-in ;
- `ContextBundle` projet-local et fédéré avec budget final et coût de travail borné ;
- limite maximale commune des résultats CLI/REST/MCP ;
- instructions AGENTS/Copilot/Claude/Gemini ;
- Agent Skills locaux et AI Skills Registry ;
- CLI, REST Quarkus et MCP Java STDIO ;
- distribution CLI autonome avec checksums, licence, notices tierces et SBOM ;
- installateur Windows EXE autonome avec runtime Java embarqué ;
- assistant Natif / Docker / Both ;
- image Docker qualifiée par Trivy, SBOM CycloneDX et attestations sur publication `main`.

Baseline grande échelle historique : 2 104 fichiers, 10 878 symboles, 10 087 relations, indexation complète 8 818 ms, fédération p50/p95 133/304 ms, contexte p50/p95 48/206 ms, hit@3 et MRR@3 à 1,0.

## Hardening intégré

Le hardening post-Phase 6 et la consolidation #48/#49 couvrent notamment :

- confinement filesystem sous racine canonique, refus des symlinks et lectures `NOFOLLOW_LINKS` via `SafeFileIO` ;
- revalidation de la taille réelle avant hash/lecture ;
- exclusion mutuelle par projet dans la JVM et entre processus via `FileLock` sous `NEXUS_HOME/locks` ;
- snapshot d'indexation cohérent face aux mutations concurrentes du repository ;
- timeout wall-clock commun aux providers et importers externes ;
- liveness/readiness séparées, y compris le cas sans projet enregistré ;
- graphe projet construit à partir de projections SQL bornées ;
- fédération avec fair floor, déduplication, réutilisation du budget et borne du travail préparatoire ;
- plafond commun des résultats CLI/REST/MCP ;
- politique SCIP dédiée avant allocation Protobuf ;
- REST loopback par défaut et exposition distante fail-closed ;
- pas de bump `index_generation` sans changement effectif ;
- déduplication SQL des providers externes persistés ;
- alignement Jackson par dependency management cohérent.

Le support cible de `NEXUS_HOME` reste un filesystem local. Les garanties de `FileLock` sur un filesystem réseau ne sont pas revendiquées.

## Sécurité REST actuelle

Une écoute locale loopback reste autorisée sans token par défaut.

Une écoute hors loopback exige simultanément :

- un `NEXUS_REST_API_TOKEN` robuste (minimum 32 octets et entropie estimée minimale de 96 bits) ;
- une allowlist non vide `NEXUS_REST_ALLOWED_PROJECT_ROOTS` ;
- un mode explicite `NEXUS_REST_EXPOSURE_MODE=reverse-proxy-https|direct-https` ;
- ou `loopback-forward` uniquement pour `NEXUS_RUNTIME=docker`, avec publication côté hôte maintenue sur loopback.

Les racines projet administrables sont canonicalisées avant comparaison.

## CI et supply-chain

La baseline active comprend :

- JaCoCo bloquant sur `core` : minimum **70 % lignes / 50 % branches** ;
- NEXUS CI Windows Java 24 + Linux Java 21 ;
- Windows Installer ;
- Scale Benchmark couvrant SQLite, graphe et contexte fédéré ;
- CodeQL Java/Kotlin `security-extended` ;
- OSV delta PR **et scan bloquant du SBOM CycloneDX agrégé du reactor** ;
- Dependabot Maven, GitHub Actions et Docker ;
- Docker Distribution avec round-trip dotenv, Trivy, SBOM image et blocage des vulnérabilités HIGH/CRITICAL corrigibles ;
- publication GHCR sur `main` avec attestations de provenance et de SBOM sur le digest publié ;
- Actions contrôlées par le dépôt épinglées à des SHA immuables.

Aucun workflow/configuration/status SonarCloud actif n'est défini dans le dépôt courant. SonarCloud n'est donc pas un gate exécutable de la baseline actuelle.

Référence : [`developer/ci-and-supply-chain.md`](developer/ci-and-supply-chain.md).

## Distribution Windows et Docker

La release Windows x64 produit :

- `nexus-context-engine-0.2.0-windows-x64.zip` ;
- `NEXUS-0.2.0-windows-x64-setup.exe` ;
- les SHA-256 associés.

Le setup est current-user et embarque son runtime Java. Le wizard permet Natif, Docker ou Both, avec REST optionnel côté natif, MCP STDIO, configuration Ollama explicite et bootstrap Docker Desktop vérifié par Authenticode lorsque demandé.

Références :

- [`user/windows-installation.md`](user/windows-installation.md) ;
- [`user/docker-installation.md`](user/docker-installation.md) ;
- [`user/deployment-wizard-template.md`](user/deployment-wizard-template.md).

## Qualification de la consolidation post-audit

PR #49 :

```text
QUALIFIED_HEAD=4f04c1ad3ff5b41aa9d1892ade57ad62b90a43f9
MERGE_SHA=c1ff9ef03ef33097c0d51154e02c30109b0a46f1
```

Gates qualifiés sur ce HEAD :

- NEXUS CI `31314135008` — PASS ;
- Scale Benchmark `31314135000` — PASS ;
- Windows Installer `31314134983` — PASS ;
- Docker Distribution `31314134994` — PASS ;
- CodeQL `31314134977` — PASS ;
- OSV-Scanner `31314135231` — PASS.

Le rerun du Scale Benchmark a été effectué une seule fois sur le même HEAD après un outlier I/O runner ; les budgets n'ont pas été modifiés.

## Travail restant priorisé

Les P1/P2/P3 techniques de l'issue #48 sont intégrés. Le seul reliquat de cette campagne est la réconciliation documentaire finale menée par la PR post-merge dédiée.

Les sujets suivants restent des **watch items**, pas des bugs ouverts :

1. coût des recherches SQLite `%substring%` — aucun FTS5/trigram sans benchmark montrant un bénéfice matériel ;
2. lifecycle Lucene persistant — pas de writer/SearcherManager partagé sans mesure probante ;
3. cache Git persistant — pas d'introduction sans mesure multi-repository ;
4. providers Java tiers non coopératifs — isolation processus seulement si un cas réel le justifie ;
5. filesystems réseau pour `NEXUS_HOME` — non supportés sans qualification dédiée.

## Références

- Architecture : [`architecture.md`](architecture.md)
- Arc42 : [`architecture/README.md`](architecture/README.md)
- Provenance des index : [`index-provenance.md`](index-provenance.md)
- CI / supply-chain : [`developer/ci-and-supply-chain.md`](developer/ci-and-supply-chain.md)
- Limites courantes : [`developer/current-limitations.md`](developer/current-limitations.md)
- Release/recovery : [`developer/release-and-recovery.md`](developer/release-and-recovery.md)
- ADR : [`adr/`](adr/)
