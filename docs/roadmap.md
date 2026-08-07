# Feuille de route NEXUS

Cette feuille de route est la source de vérité active pour l'évolution de NEXUS. Les détails historiques d'implémentation restent conservés dans les issues, PR et ADR correspondants ; ce document décrit l'état courant et le prochain travail attendu.

## État courant

```text
repository   FTurleque/nexus-context-engine
visibility   public
main         Phase 6 + hardening + provenance + licence + supply-chain + Windows EXE intégrés
version      0.2.0
Java         runtime >=21 / release 21
Phase 1→6   livrées / intégrées
hardening   post-Phase 6 intégré
P1 audit    #19 + #20 intégrés via PR #24
docs         #21 intégré via PR #26
supply-chain #22 intégré via PR #28
licence     propriétaire source-available via PR #25
windows     EXE installer autonome intégré via PR #41
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
| Réconciliation documentaire | issue #21 / PR #26 | ✅ intégrée |
| CI / couverture / supply-chain | issue #22 / PR #28 | ✅ intégrée |
| Distribution Windows EXE autonome | issue #40 / PR #41 | ✅ intégrée |
| Benchmark scale SQLite/fédération | issue #23 | ⏳ prochain lot |

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
- distribution CLI autonome avec checksums, licence, notices tierces et SBOM ;
- installateur Windows EXE autonome avec runtime Java embarqué (sans prérequis JVM).

Baseline grande échelle historique : 2 104 fichiers, 10 878 symboles, 10 087 relations, indexation complète 8 818 ms, fédération p50/p95 133/304 ms, contexte p50/p95 48/206 ms, hit@3 et MRR@3 à 1,0.

## Hardening intégré

Le hardening post-Phase 6 couvre notamment :

- confinement filesystem sous racine canonique, refus des symlinks et lectures `NOFOLLOW_LINKS` via `SafeFileIO` ;
- revalidation de la taille réelle avant hash/lecture ;
- exclusion mutuelle par projet dans la JVM **et entre processus** via `FileLock` sous `NEXUS_HOME/locks` ;
- timeout wall-clock commun aux providers et importers externes ;
- liveness/readiness séparées ;
- fédération avec fair floor, déduplication et réutilisation du budget ;
- REST loopback par défaut et token requis hors loopback.

Le support cible de `NEXUS_HOME` reste un filesystem local. Les garanties de `FileLock` sur un filesystem réseau ne sont pas revendiquées.

## Provenance des index — PR #24

Les issues P1 #19 et #20 sont clôturées.

- changement canonique SOURCE/TEST ⇒ invalidation des snapshots externes non embarqués persistés ;
- index sémantique ⇒ manifeste de provenance avec fingerprint canonique, provider, modèle, dimensions, profil de préparation et version de schéma ;
- provenance absente/incompatible ⇒ rebuild ;
- recherche sémantique obsolète refusée avant embedding de requête.

Référence : [`index-provenance.md`](index-provenance.md).

## CI et supply-chain — PR #28

L'issue #22 est fonctionnellement livrée via PR #28.

Gates intégrés :

- JaCoCo bloquant sur `core` : minimum **70 % lignes / 50 % branches** ; baseline qualifiée **77,07 % / 58,46 %** ;
- OSV-Scanner : gate des nouvelles vulnérabilités sur PR + scan périodique de l'état courant ;
- CodeQL Java/Kotlin avec queries `security-extended` ;
- Dependabot hebdomadaire Maven + GitHub Actions ;
- Actions contrôlées par le dépôt épinglées à des SHA immuables ;
- génération des notices tierces avec `failOnMissing=true` ;
- `LICENSE`, `THIRD_PARTY_NOTICES.txt` et `SBOM.cdx.json` embarqués dans le ZIP autonome ;
- conservation 90 jours du SBOM, des notices et de la preuve JaCoCo en artefact CI.

Qualification exacte-head de PR #28 (`a363e93dc97597d288389b4f4b9e8404abe4296c`) :

- NEXUS CI run #31 (`31096267391`) : Windows Java 24 PASS, Linux Java 21 PASS, JaCoCo 70/50 PASS, distribution/compliance PASS ;
- OSV-Scanner run #4 (`31096267797`) : PASS ;
- CodeQL run #6 (`31096267378`) : PASS.

PR #28 est fusionnée dans `main` via `4c9b7cd4e26913af42f687b48718c8e733fa06f7`.

Référence : [`developer/ci-and-supply-chain.md`](developer/ci-and-supply-chain.md).

## Distribution Windows — PR #41

L'issue #40 est clôturée.

La release Windows x64 produit, sans prérequis JVM sur la machine cible :

- un ZIP Windows autonome (`nexus-context-engine-0.2.0-windows-x64.zip`) ;
- un setup Inno Setup EXE (`NEXUS-0.2.0-windows-x64-setup.exe`), current-user, sans élévation.

Le runtime Java est embarqué via `jpackage`. Le setup ajoute NEXUS au `PATH` utilisateur et enregistre un désinstallateur standard Windows. `NEXUS_HOME` est conservé lors d'une désinstallation.

Qualification exacte-head de PR #41 (`1be179c76a28ae57387b287df3dc7c33b1225443`) :

- NEXUS CI run 31158371347 : Windows gate Java 24 PASS, Linux reactor Java 21 PASS ;
- Windows Installer run 31158371344 : build, smoke install/execute/uninstall, production EXE PASS ;
- OSV-Scanner run 31158371667 : PASS ;
- CodeQL run 31158371350 : PASS.

PR #41 est fusionnée dans `main` via `f4b41f8150d94ef983c486864e428023ab446b4f`.

Référence : [`docs/user/windows-installation.md`](user/windows-installation.md) et [`distribution/README.md`](../distribution/README.md).

## Travail restant priorisé

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
- CI / supply-chain : [`developer/ci-and-supply-chain.md`](developer/ci-and-supply-chain.md)
- Limites courantes : [`developer/current-limitations.md`](developer/current-limitations.md)
- Release/recovery : [`developer/release-and-recovery.md`](developer/release-and-recovery.md)
- ADR : [`adr/`](adr/)
