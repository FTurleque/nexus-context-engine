---
status: accepted
date: 2026-07-19
---

# ADR-0017 — Découpler NEXUS des outils et orchestrateurs externes

## Contexte et problème

NEXUS est destiné à être utilisé par plusieurs environnements : GitHub Copilot, Claude, ChatGPT/OpenAI, serveurs MCP, IDE, agents personnalisés, AI Skills Registry, JARVIS, Alfred ou Brainiac.

Ces systèmes ont des responsabilités différentes. Certains fournissent une interface utilisateur, d'autres orchestrent des agents, choisissent un modèle, exécutent des skills ou gèrent leur propre format d'instructions. Si le cœur NEXUS dépend directement de leurs APIs ou conventions, il perd son rôle de moteur de contexte commun et devient difficile à réutiliser.

La question est : **comment intégrer ces outils sans faire de l'un d'eux une dépendance ou un centre de gravité architectural de NEXUS ?**

## Facteurs de décision

- indépendance du moteur ;
- capacité à servir plusieurs clients ;
- absence de routage de modèles dans NEXUS ;
- possibilité d'utiliser NEXUS sans AI Skills Registry ;
- possibilité d'utiliser NEXUS sans JARVIS, Alfred ou Brainiac ;
- maintien des conventions natives Copilot/Claude ;
- capacité à remplacer un client sans modifier le cœur ;
- cohérence avec le modèle `ContextBundle`.

## Options envisagées

- faire de JARVIS l'orchestrateur obligatoire de NEXUS ;
- intégrer directement Copilot et Claude dans le cœur ;
- faire de AI Skills Registry une dépendance obligatoire pour gérer les skills ;
- exposer NEXUS par des adaptateurs indépendants et conserver toutes ces intégrations optionnelles.

## Décision retenue

**Option retenue : exposer NEXUS par des adaptateurs indépendants et conserver Copilot, Claude, AI Skills Registry, JARVIS, Alfred, Brainiac et autres outils comme consommateurs ou fournisseurs optionnels.**

La répartition cible des responsabilités est :

```text
JARVIS / orchestrateur
→ choisit le flux, l'agent ou le modèle

NEXUS
→ sélectionne et construit le contexte

AI Skills Registry
→ découvre ou résout des capacités/skills

Alfred / Brainiac / agents spécialisés
→ exécutent le traitement spécialisé

LLM
→ raisonne et génère la réponse
```

Pour Copilot et Claude :

- leurs fichiers et conventions peuvent être découverts par des providers ;
- NEXUS peut produire un contexte exploitable par un adaptateur spécifique ;
- NEXUS ne remplace pas leurs mécanismes natifs ;
- les formats natifs ne doivent pas devenir les modèles métier du cœur.

Pour AI Skills Registry :

- NEXUS peut demander ou résoudre un skill via le registre ;
- le registre reste optionnel ;
- NEXUS doit pouvoir découvrir des skills locaux sans lui.

Pour JARVIS, Alfred et Brainiac :

- aucune dépendance de compilation du cœur ;
- aucune logique de routage spécifique ;
- intégration via API, MCP ou contrat dédié si nécessaire.

### Conséquences positives

- NEXUS reste réutilisable dans de nombreux environnements ;
- aucun outil externe ne bloque l'évolution du cœur ;
- les responsabilités de chaque projet restent claires ;
- les conventions fournisseurs peuvent évoluer dans leurs adaptateurs ;
- le moteur conserve une API conceptuelle stable autour de `ContextRequest` et `ContextBundle`.

### Conséquences négatives et compromis acceptés

- des adaptateurs spécifiques doivent être développés et maintenus ;
- certaines fonctionnalités natives d'un outil peuvent nécessiter un mapping imparfait ;
- le parcours d'intégration peut être moins direct qu'un couplage fort ;
- plusieurs versions d'adaptateurs peuvent devoir coexister.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Types Copilot/Claude dans le domaine | Élevé | Traduction aux frontières et modèles internes neutres |
| NEXUS commence à choisir les modèles | Élevé | Maintenir le routage hors périmètre du cœur |
| AI Skills Registry devient obligatoire | Élevé | Garder `SkillSourceProvider` local et connecteur de registre optionnel |
| Dépendance circulaire entre projets | Élevé | NEXUS ne dépend d'aucun orchestrateur consommateur |
| Divergence des résultats selon l'adaptateur | Moyen | Tous les adaptateurs appellent les mêmes services de cœur |

### Confirmation

La décision est respectée si :

- le build du cœur ne dépend d'aucun SDK Copilot, Claude ou projet JARVIS ;
- `ContextBundle` est identique quel que soit le canal d'accès ;
- un connecteur AI Skills Registry peut être désactivé ;
- les décisions de routage de modèles ne sont pas présentes dans le cœur ;
- les tests du moteur peuvent s'exécuter sans aucun de ces outils.

## Analyse détaillée des options

### Faire de JARVIS l'orchestrateur obligatoire

**Avantages :**

- intégration forte dans un écosystème cohérent ;
- simplification potentielle des flux internes.

**Inconvénients :**

- NEXUS ne serait plus un composant indépendant ;
- adoption externe plus difficile ;
- dépendances circulaires possibles ;
- responsabilité de contexte confondue avec orchestration.

### Intégrer directement Copilot et Claude dans le cœur

**Avantages :**

- accès rapide à leurs conventions et APIs ;
- intégration potentiellement plus riche.

**Inconvénients :**

- couplage à des fournisseurs ;
- évolution du cœur dictée par leurs changements ;
- difficulté à supporter d'autres environnements.

### Rendre AI Skills Registry obligatoire

**Avantages :**

- source centralisée de skills ;
- découverte homogène.

**Inconvénients :**

- NEXUS ne fonctionnerait plus seul ;
- complexité réseau et opérationnelle ;
- contradiction avec le local-first.

### Utiliser des adaptateurs indépendants

**Avantages :**

- faible couplage ;
- interopérabilité ;
- évolutivité ;
- responsabilités claires.

**Inconvénients :**

- coût de mapping ;
- davantage de composants périphériques à maintenir.

## Impacts sur l'architecture

```text
                    NEXUS Core
                       │
                       ▼
                  ContextBundle
                       │
       ┌───────────────┼───────────────┐
       ▼               ▼               ▼
   Copilot Adapter  Claude Adapter   MCP Adapter
       │               │               │
       └───────────────┼───────────────┘
                       ▼
                 Outils consommateurs

AI Skills Registry
      ▲
      │ connecteur optionnel
      └──────── NEXUS
```

## Conditions de réexamen

Cette décision ne doit être réexaminée que si NEXUS change explicitement de mission pour devenir un orchestrateur complet ou un produit lié à un fournisseur unique.

Une intégration peut devenir recommandée ou livrée par défaut sans devenir une dépendance du cœur.

## Décisions liées

- ADR-0001 — Positionner NEXUS comme moteur d'intelligence de contexte indépendant des modèles.
- ADR-0003 — Conserver un cœur Java sans framework applicatif obligatoire.
- ADR-0011 — Normaliser les sources de contexte derrière des providers.
- ADR-0012 — Réutiliser les standards existants pour les instructions et les skills.
- ADR-0016 — Utiliser le SDK Java MCP officiel pour l'adaptateur MCP.
