---
status: accepted
date: 2026-07-19
---

# ADR-0012 — Réutiliser les standards existants pour les instructions et les skills

## Contexte et problème

Les environnements IA utilisent plusieurs conventions pour fournir des règles et capacités aux agents. Un projet peut contenir `AGENTS.md`, `.github/copilot-instructions.md`, des fichiers `.instructions.md`, `CLAUDE.md`, `GEMINI.md` ou d'autres conventions. Les skills suivent également des formats émergents, notamment le standard Agent Skills basé sur `SKILL.md`.

NEXUS doit sélectionner les instructions et skills pertinents sans imposer un nouveau format propriétaire et sans obliger les utilisateurs à dupliquer leur configuration pour chaque assistant.

La question est : **NEXUS doit-il inventer ses propres formats d'instructions et de skills, ou réutiliser les conventions existantes derrière des providers normalisés ?**

## Facteurs de décision

- interopérabilité avec les outils existants ;
- absence de duplication de configuration ;
- adoption de standards ouverts lorsque disponibles ;
- compatibilité avec Copilot et Claude ;
- divulgation progressive pour limiter le contexte ;
- séparation entre sélection d'un skill et exécution du skill ;
- évolutivité vers AI Skills Registry ;
- indépendance du cœur NEXUS.

## Options envisagées

- créer des formats propriétaires `.nexus-instructions` et `.nexus-skill` ;
- supporter uniquement les formats GitHub Copilot ;
- supporter uniquement `AGENTS.md` et Agent Skills ;
- supporter plusieurs conventions via des providers, avec `AGENTS.md` et Agent Skills comme standards privilégiés.

## Décision retenue

**Option retenue : supporter plusieurs conventions via des providers normalisés, privilégier `AGENTS.md` pour les instructions portables et adopter le standard Agent Skills basé sur `SKILL.md` pour les skills.**

Pour les instructions, NEXUS doit pouvoir découvrir progressivement :

```text
AGENTS.md
.github/copilot-instructions.md
.github/instructions/*.instructions.md
CLAUDE.md
GEMINI.md
autres formats configurés
```

Les providers natifs traduisent ces fichiers vers un modèle d'instruction commun incluant au minimum :

- origine ;
- scope ;
- motifs de chemin ;
- priorité ;
- contenu ;
- métadonnées.

NEXUS ne remplace pas les fichiers natifs et n'exige pas leur migration.

Pour les skills, NEXUS adopte le principe de divulgation progressive :

```text
Découverte
→ nom, description et métadonnées

Sélection
→ chargement du SKILL.md

Exécution
→ scripts, références et assets chargés par l'agent lorsque nécessaires
```

NEXUS sélectionne ou recommande un skill. Il n'est pas responsable de son exécution.

### Conséquences positives

- les projets existants peuvent conserver leurs fichiers natifs ;
- NEXUS peut unifier plusieurs environnements sans imposer un nouveau standard ;
- les skills ne sont pas tous chargés dans le contexte ;
- la future intégration AI Skills Registry peut s'appuyer sur un modèle déjà standardisé ;
- les instructions applicables peuvent être résolues indépendamment du client final.

### Conséquences négatives et compromis acceptés

- les règles de scope diffèrent selon les formats et devront être normalisées avec soin ;
- certaines conventions propriétaires peuvent évoluer ;
- plusieurs fichiers peuvent contenir des instructions contradictoires ;
- la compatibilité parfaite avec chaque outil peut nécessiter des adaptateurs spécifiques.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Conflit entre instructions de plusieurs origines | Élevé | Conserver origine, scope et priorité ; définir une stratégie de résolution explicable |
| Duplication d'une même instruction dans plusieurs formats | Moyen | Déduplication et empreintes de contenu |
| Chargement excessif de skills | Élevé | Divulgation progressive et budget par source |
| Dépendance au format d'un fournisseur | Moyen | Providers séparés et modèle interne neutre |
| Confusion entre sélection et exécution | Moyen | Le modèle NEXUS référence le skill ; l'agent consommateur l'exécute |

### Confirmation

La décision est respectée si :

- NEXUS n'introduit pas de format de skill propriétaire obligatoire ;
- `SKILL.md` peut être découvert sans charger immédiatement toutes ses ressources ;
- plusieurs formats d'instructions sont normalisés via des providers ;
- le `ContextBundle` conserve la provenance des instructions et skills ;
- l'exécution d'un skill n'est pas implémentée dans le cœur du `ContextBuilder`.

## Analyse détaillée des options

### Créer des formats propriétaires NEXUS

**Avantages :**

- contrôle total du schéma ;
- comportement homogène.

**Inconvénients :**

- duplication pour les utilisateurs ;
- faible interopérabilité ;
- coût de maintenance d'un standard supplémentaire ;
- opposition à la stratégie de réutilisation de briques existantes.

### Supporter uniquement les formats GitHub Copilot

**Avantages :**

- intégration forte avec un environnement populaire ;
- périmètre initial limité.

**Inconvénients :**

- couplage fournisseur ;
- mauvaise compatibilité avec Claude ou d'autres agents ;
- contradiction avec la mission model-agnostic.

### Supporter uniquement `AGENTS.md` et Agent Skills

**Avantages :**

- standards portables ;
- implémentation plus simple.

**Inconvénients :**

- les projets existants utilisant des conventions natives devraient dupliquer ou migrer leur documentation ;
- perte d'informations spécifiques déjà présentes.

### Supporter plusieurs conventions avec standards privilégiés

**Avantages :**

- interopérabilité ;
- migration non obligatoire ;
- convergence vers des standards ouverts ;
- bonne séparation via providers.

**Inconvénients :**

- davantage de parsers/adaptateurs ;
- résolution de conflits plus complexe.

## Impacts sur l'architecture

```text
InstructionSourceProvider
├── AgentsMdInstructionProvider
├── CopilotInstructionProvider
├── ClaudeInstructionProvider
└── GenericInstructionProvider

SkillSourceProvider
└── AgentSkillsProvider
       │
       ▼
Modèles normalisés NEXUS
       │
       ▼
Ranking + Budget
```

## Conditions de réexamen

Réexaminer si :

- un standard d'instructions universel s'impose largement ;
- le standard Agent Skills évolue de manière incompatible ;
- les providers fournisseurs deviennent trop coûteux à maintenir ;
- AI Skills Registry impose un contrat d'échange plus riche, sans toutefois rendre le registre obligatoire.

## Décisions liées

- ADR-0011 — Normaliser les sources de contexte derrière des providers.
- ADR-0013 — Construire un ContextBundle sous budget de tokens explicable.
- ADR-0017 — Découpler NEXUS des outils et orchestrateurs externes.
