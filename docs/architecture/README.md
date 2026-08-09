# Documentation d'architecture — NEXUS Context Engine

Ce répertoire contient la documentation d'architecture structurée selon **arc42**, avec diagrammes C4/Mermaid et ADR MADR.

## Organisation

```text
docs/architecture/
├── README.md
├── arc42/
│   ├── 01-introduction-objectifs.md
│   ├── 02-contraintes.md
│   ├── 03-contexte-perimetre.md
│   ├── 04-strategie-solution.md
│   ├── 05-vue-blocs.md
│   ├── 06-vue-execution.md
│   ├── 07-vue-deploiement.md
│   ├── 08-concepts-transverses.md
│   ├── 09-decisions.md
│   ├── 10-exigences-qualite.md
│   ├── 11-risques-dette.md
│   └── 12-glossaire.md
├── quality/scenarios.md
└── risks/register.md
```

## Conventions

- français pour la documentation, anglais pour les identifiants de code ;
- Mermaid pour les diagrammes ;
- niveaux C4 séparés ;
- ADR append-only : une décision remplacée est supersédée, jamais supprimée.

## Sources primaires

| Source | Rôle |
|---|---|
| `docs/architecture.md` | synthèse d'architecture courante |
| `docs/adr/` | historique décisionnel |
| `docs/roadmap.md` | état courant et travail réellement restant |
| `docs/developer/` | documentation technique détaillée |
| `docs/index-provenance.md` | autorité et fraîcheur des index |
| `src/main/java/` | cœur Java |
| `adapters/` | REST / MCP / intégrations assistants |

## Statut courant

- **Version** : 0.2.0
- **Phase 6** : PR #15
- **Hardening post-Phase 6** : PR #18
- **Provenance des index** : PR #24
- **Licence propriétaire source-available** : PR #25
- **Supply-chain** : PR #28, renforcée par PR #49
- **Distribution Windows** : PR #41
- **Assistant Natif/Docker/Both** : PR #46
- **Consolidation post-audit P1/P2/P3** : issue #48 / PR #49
- **Réconciliation documentaire post-audit** : PR #61

### Preuves récentes

PR #49 :

```text
QUALIFIED_HEAD=4f04c1ad3ff5b41aa9d1892ade57ad62b90a43f9
MERGE_SHA=c1ff9ef03ef33097c0d51154e02c30109b0a46f1
```

NEXUS CI, Scale Benchmark, Windows Installer, Docker Distribution, CodeQL et OSV-Scanner : PASS.

PR #61 :

```text
QUALIFIED_HEAD=ba91be044a600d2396e0939fc154848dc47f6310
MERGE_SHA=660ca9f07a23950d2a5284605531524372331bc5
```

NEXUS CI, CodeQL et OSV-Scanner : PASS.

Aucun workflow/configuration/status SonarCloud actif n'est défini dans la baseline actuelle.

## Frontières actives à conserver

- SQLite canonique ; Lucene et intelligence externe dérivés ;
- mutation d'index single-flight JVM + OS sur filesystem local ;
- snapshot canonique revalidé avant publication ;
- filesystem borné et symlinks refusés pour les lectures sensibles ;
- providers/importers externes optionnels et bornés ;
- graphe, résultats et contexte fédéré bornés ;
- exposition REST distante fail-closed ;
- supply-chain reactor + image Docker qualifiée.

Dernière réconciliation : post-audit #48/#49/#61.
