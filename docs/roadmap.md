# Feuille de route NEXUS

Cette feuille de route est la source de vérité active pour l'évolution de NEXUS. Les détails historiques d'implémentation restent conservés dans les issues, PR et ADR correspondants ; ce document décrit l'état courant et le prochain travail attendu.

## État courant

```text
repository  FTurleque/nexus-context-engine
visibility  public
main        c7a03479a78713b78ec2ddc477e1d07d400d8aba
version     0.2.0
Java        runtime >=21 / release 21
Phase 1→6  livrées / intégrées
hardening  post-Phase 6 intégré
P1 audit   #19 + #20 intégrés via PR #24
licence    propriétaire source-available via PR #25
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
| Réconciliation documentaire | issue #21 | 🚧 en cours |
| CI / couverture / supply-chain | issue #22 | ⏳ à traiter |
| Benchmark scale SQLite/fédération | issue #23 | ⏳ à traiter |

## Baseline fonctionnelle livrée

NEXUS 0.2.0 fournit notamment :

- indexation locale incrémentale avec SQLite canonique et Lucene dérivé ;
- JavaParser, Markdown et recherche lexicale polyglotte ;
- SCIP opportuniste, JDT LS opt-in et import MINOS explicite ;
- recherche fichier/symbole/graphe/Git et fédération multi-projet ;
- recherche sémantique locale opt-in ;
- ContextBundle avec budget strict, provenance et contexte fédéré ;
- instructions AGENTS/Copilot/Claude/Gemini ;
- Agent Skills locaux et AI Skills Registry ;
- CLI, REST Quarkus et MCP Java STDIO ;
- distribution CLI autonome avec checksums et SBOM.

Baseline grande échelle historique : 2 104 fichiers, 10 878 symboles, 10 087 relations, indexation complète 8 818 ms, fédération p50/p95 133/304 ms, contexte p50/p95 48/206 ms, hit@3 et MRR@3 à 1,0.

## Hardening intégré

Le hardening post-Phase 6 n'est plus un travail en attente ni une branche active. Il est intégré dans `main` et couvre notamment :

- confinement filesystem sous racine canonique, refus des symlinks et lectures `NOFOLLOW_LINKS` via `SafeFileIO` ;
- revalidation de la taille réelle avant hash/lecture ;
- exclusion mutuelle par projet dans la JVM **et entre processus** via `FileLock` sous `NEXUS_HOME/locks` ;
- timeout wall-clock commun aux providers et importers externes ;
- liveness/readiness séparées ;
- fédération avec fair floor, déduplication et réutilisation du budget ;
- REST loopback par défaut et token requis hors loopback ;
- cohérence des erreurs UUID et nettoyage du cycle de vie des locks.

Le support cible de `NEXUS_HOME` reste un filesystem local. Les garanties de `FileLock` sur un filesystem réseau ne sont pas revendiquées.

## Provenance des index — PR #24

Les issues P1 #19 et #20 sont clôturées.

### Intelligence de code externe

Quand l'état canonique SOURCE/TEST change, les snapshots persistés de providers externes non embarqués sont invalidés, y compris lorsqu'un provider n'est plus actif dans le runtime courant. Les providers/importers configurés peuvent ensuite republier un snapshot courant.

### Index sémantique

Le commit Lucene sémantique porte un manifeste de provenance comprenant :

- fingerprint canonique ;
- identité du provider ;
- modèle ;
- dimensions vectorielles ;
- profil de préparation du contenu ;
- version de schéma sémantique.

Une provenance absente ou incompatible force un rebuild. La recherche refuse un index sémantique obsolète avant même de calculer l'embedding de requête.

Référence : [`index-provenance.md`](index-provenance.md).

## Qualification actuelle

La qualification exacte-head de PR #24 (`25c12b100b774a4ec3d69d221675bf31d8ebaa0c`) a été exécutée avec succès dans NEXUS CI run #15 après le passage du dépôt en public :

- Windows / Java 24 : **PASS** ;
- `scripts/validate-phase-6.ps1` : **PASS** ;
- Linux / Java 21 Maven reactor : **PASS** ;
- smoke de la distribution Linux : **PASS**.

PR #24 a ensuite été fusionnée dans `main` comme `c7a03479a78713b78ec2ddc477e1d07d400d8aba`.

Les anciens échecs GitHub Actions avec `steps=[]` / `BlobNotFound` sont considérés comme un incident historique/transitoire : la relance a exécuté les deux runners normalement.

## Travail restant priorisé

### P2 — #21 Réconciliation documentaire

Objectif : garantir que README, architecture, arc42, roadmap et runbook recovery décrivent tous la même architecture effectivement livrée.

### P2 — #22 CI, couverture et supply-chain

À évaluer/mettre en œuvre :

- gate JaCoCo explicite ;
- politique de vulnérabilités/dépendances ;
- code scanning ;
- pinning immuable des actions tierces ;
- conservation SBOM de release ;
- `THIRD_PARTY_NOTICES.md` et obligations de redistribution des dépendances.

La licence NEXUS elle-même est déjà propriétaire/source-available et intégrée via PR #25.

### P3 — #23 Benchmark scale

Mesurer avant toute nouvelle complexité d'indexation :

- recherches SQLite lexicales sur corpus croissants ;
- coût fédéré multi-projet ;
- seuils justifiant ou non FTS5/trigram/autre stratégie.

Aucun FTS supplémentaire, vector DB, index distribué ou nouveau moteur n'est adopté sans benchmark montrant un bénéfice matériel.

## Références

- Architecture : [`architecture.md`](architecture.md)
- Arc42 : [`architecture/README.md`](architecture/README.md)
- Provenance des index : [`index-provenance.md`](index-provenance.md)
- Limites courantes : [`developer/current-limitations.md`](developer/current-limitations.md)
- Release/recovery : [`developer/release-and-recovery.md`](developer/release-and-recovery.md)
- ADR : [`adr/`](adr/)
