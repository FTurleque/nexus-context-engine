# Documentation d'architecture — NEXUS Context Engine

Ce répertoire contient la documentation d'architecture structurée selon **arc42** (v8),
avec des diagrammes **C4** modélisés en **Mermaid** et des décisions formalisées en **ADR MADR**.

## Organisation

```text
docs/architecture/
├── README.md               ← ce fichier
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
├── adr/
│   ├── README.md
│   └── template.md
├── quality/
│   └── scenarios.md
└── risks/
    └── register.md
```

## Conventions

- Langue : **français** pour la documentation, **anglais** pour les identifiants de code.
- Diagrammes : blocs Mermaid exclusivement, sans ASCII ni image binaire.
- Notation : style UML avec stéréotypes explicites (`«Person»`, `«Software System»`,
  `«Container»`, `«Component»`, `«interface»`, `«adapter»`, `«node»`, `«database»`).
- Niveaux C4 : Context, Container, Component (séparés, pas mélangés).
- ADR : format MADR adapté ; numérotation `0000-titre.md` ; aucun ADR accepté ne
  peut être supprimé, un remplacement crée un nouvel ADR avec lien.

## Sources primaires

| Source | Rôle |
|--------|------|
| `docs/architecture.md` | Synthèse d'architecture courante |
| `docs/adr/` | Historique décisionnel (ADR-0000 à ADR-0044) |
| `docs/roadmap.md` | État courant, backlog de stabilisation et preuves de qualification |
| `docs/developer/` | Documentation technique détaillée |
| `docs/index-provenance.md` | Autorité et fraîcheur des index canoniques, externes et sémantiques |
| `src/main/java/` | Sources du cœur Java |
| `adapters/` | Sources des adaptateurs REST / MCP / Clients |

## Statut

- **Version** : 0.2.0
- **Phase 6** : intégrée dans `main` via PR #15
- **Hardening post-Phase 6** : intégré dans `main` via PR #18
- **Provenance des index externes et sémantiques** : intégrée dans `main` via PR #24
- **Licence** : propriétaire source-available, intégrée via PR #25
- **Qualification #24** : Windows Java 24 PASS ; Linux Java 21 + distribution PASS
- **Branche historique `hardening/post-phase6-audit`** : clôturée/supprimée ; ne constitue plus une branche active
- **Dernière mise à jour** : 2026-08-06
