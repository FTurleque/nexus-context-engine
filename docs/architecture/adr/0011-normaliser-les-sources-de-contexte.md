---
status: accepted
date: 2026-07-19
---

# ADR-0011 — Normaliser les sources de contexte derrière des providers

## Contexte et problème

NEXUS doit à terme sélectionner plusieurs formes d'information : code, symboles, tests, documentation, instructions, skills, profils d'agents, prompts et contexte Git. Ces informations proviennent de formats et d'emplacements différents selon les projets et les outils.

Si le cœur traite directement chaque convention fournisseur, il deviendra rapidement rempli de conditions spécifiques à Copilot, Claude, Git ou un format particulier de skill. Cela nuirait à l'indépendance du moteur et rendrait le ranking difficile à généraliser.

La question est : **comment représenter et découvrir des sources de contexte hétérogènes tout en conservant un pipeline commun de sélection, ranking et budget ?**

## Facteurs de décision

- indépendance vis-à-vis des fournisseurs ;
- capacité à ajouter de nouvelles sources sans modifier `ContextBuilder` ;
- provenance traçable ;
- ranking commun ou comparable ;
- possibilité de filtrer par type de source ;
- prise en charge de scopes et priorités spécifiques ;
- maintien d'un modèle métier cohérent.

## Options envisagées

- coder chaque type de source directement dans `ContextBuilder` ;
- créer un pipeline séparé pour chaque fournisseur ;
- normaliser toutes les sources derrière `ContextSourceProvider` et un modèle commun ;
- convertir toutes les sources en texte brut sans métadonnées.

## Décision retenue

**Option retenue : normaliser les sources de contexte derrière des `ContextSourceProvider` et un modèle commun de descripteurs/candidats.**

Les types de contexte conceptuels comprennent :

```text
CODE
SYMBOL
TEST
DOCUMENTATION
INSTRUCTION
SKILL
AGENT_PROFILE
PROMPT
GIT
```

Chaque famille est découverte par un provider :

```text
ContextSourceProvider
├── CodeContextSourceProvider
├── DocumentationSourceProvider
├── InstructionSourceProvider
├── SkillSourceProvider
├── GitContextSourceProvider
└── autres providers
```

Un `ContextSourceDescriptor` ou modèle équivalent contient des informations communes telles que :

- identifiant ;
- projet ;
- type ;
- origine ;
- chemin ;
- scope ;
- métadonnées ;
- provenance.

Les providers sont responsables de comprendre les conventions natives. Le cœur est responsable de normaliser, rechercher, classer et budgéter.

Cette décision ne signifie pas que tous les types ont exactement le même scoring. Des stratégies ou poids spécifiques peuvent être appliqués, mais ils convergent vers un pipeline commun.

### Conséquences positives

- ajout d'une nouvelle source sans réécrire le moteur ;
- séparation entre découverte native et sélection métier ;
- `ContextBuilder` reste indépendant de Copilot ou Claude ;
- provenance et scope deviennent explicites ;
- un même bundle peut combiner code, docs et instructions ;
- les exclusions inter-sources peuvent être expliquées uniformément.

### Conséquences négatives et compromis acceptés

- le modèle commun doit être suffisamment riche sans devenir abstrait au point d'être inutile ;
- certaines sources nécessiteront des métadonnées spécifiques ;
- le ranking entre types hétérogènes demandera des règles de normalisation ;
- les providers doivent gérer les particularités de scope et priorité.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Modèle commun trop générique | Élevé | Conserver un noyau commun et des métadonnées typées ou extensions contrôlées |
| Fuite de concepts fournisseur dans le cœur | Élevé | Limiter les conventions natives aux providers |
| Comparaison incorrecte de scores entre sources | Élevé | Normaliser les scores et prévoir des sous-budgets/poids par type |
| Multiplication de providers peu cohérents | Moyen | Définir un contrat de découverte, provenance et contenu commun |

### Confirmation

La décision est respectée si :

- `ContextBuilder` ne lit pas directement `.github/`, `CLAUDE.md` ou `SKILL.md` ;
- les formats natifs sont interprétés par des providers dédiés ;
- chaque candidat conserve son origine et sa stratégie de découverte ;
- une nouvelle source peut être ajoutée sans modifier les contrats des autres providers ;
- le `ContextBundle` peut contenir plusieurs types de contexte simultanément.

## Analyse détaillée des options

### Coder chaque type directement dans `ContextBuilder`

**Avantages :**

- implémentation rapide pour quelques sources ;
- moins d'interfaces initiales.

**Inconvénients :**

- forte croissance de complexité ;
- couplage aux formats ;
- tests difficiles ;
- violation de la responsabilité centrale du builder.

### Créer un pipeline séparé par fournisseur

**Avantages :**

- adaptation maximale à chaque outil.

**Inconvénients :**

- duplication de recherche/ranking/budget ;
- résultats différents selon le client ;
- perte de la valeur d'un moteur commun.

### Normaliser derrière des providers

**Avantages :**

- extensibilité ;
- responsabilités claires ;
- pipeline commun ;
- bonne compatibilité avec une architecture ports/adaptateurs.

**Inconvénients :**

- conception initiale du modèle commun plus exigeante ;
- besoin de mapping pour chaque format.

### Convertir toutes les sources en texte brut

**Avantages :**

- modèle extrêmement simple.

**Inconvénients :**

- perte du scope, de la provenance, du type et de la priorité ;
- ranking moins précis ;
- explicabilité affaiblie.

## Impacts sur l'architecture

```text
Formats natifs
AGENTS.md / Copilot / Claude / SKILL.md / Docs / Git
                     │
                     ▼
            ContextSourceProvider
                     │
                     ▼
          Modèle normalisé NEXUS
                     │
                     ▼
        Search / Ranking / Budget
                     │
                     ▼
              ContextBundle
```

## Conditions de réexamen

Réexaminer le modèle commun si :

- plusieurs providers nécessitent de contourner systématiquement le contrat ;
- de nouvelles sources imposent des notions absentes comme durée de validité ou sécurité ;
- le ranking multi-source devient impossible à expliquer avec les métadonnées existantes.

## Décisions liées

- ADR-0001 — Positionner NEXUS comme moteur d'intelligence de contexte indépendant des modèles.
- ADR-0012 — Réutiliser les standards existants pour instructions et skills.
- ADR-0013 — Construire un ContextBundle sous budget de tokens explicable.
- ADR-0017 — Découpler NEXUS des outils et orchestrateurs externes.
