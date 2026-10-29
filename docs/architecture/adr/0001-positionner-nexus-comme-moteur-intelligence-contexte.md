---
status: accepted
date: 2026-07-19
---

# ADR-0001 — Positionner NEXUS comme moteur d'intelligence de contexte indépendant des modèles

## Contexte et problème

Les assistants et agents IA disposent chacun de leurs propres mécanismes de contexte : fichiers ouverts, instructions projet, agents, prompts, skills, hooks, mémoire de conversation, outils MCP ou conventions propriétaires. GitHub Copilot, Claude et d'autres environnements n'organisent pas ces éléments de la même manière.

Le problème que NEXUS cherche à résoudre n'est pas la génération de réponses ni le choix d'un modèle. Le problème est situé en amont : **parmi toutes les informations disponibles pour une demande donnée, déterminer lesquelles sont réellement utiles, dans quel ordre, sous quel budget et avec quelle justification**.

Sans positionnement clair, NEXUS risquerait de devenir simultanément chatbot, orchestrateur d'agents, routeur de modèles, registre de skills et moteur de recherche de code. Une telle concentration de responsabilités rendrait le projet difficile à intégrer, à tester et à faire évoluer.

## Facteurs de décision

- indépendance vis-à-vis de GitHub Copilot, Claude, ChatGPT et des fournisseurs de modèles ;
- réutilisabilité par des IDE, agents, CLI, API et serveurs MCP ;
- séparation claire entre préparation du contexte et consommation du contexte ;
- possibilité de fonctionner sans LLM ;
- explicabilité de la sélection ;
- fonctionnement local possible ;
- capacité à normaliser plusieurs sources hétérogènes ;
- compatibilité future avec JARVIS, Alfred, Brainiac et AI Skills Registry sans dépendance directe.

## Options envisagées

- construire NEXUS comme un chatbot complet ;
- construire NEXUS comme un orchestrateur et routeur de modèles ;
- construire NEXUS comme un moteur de recherche de code uniquement ;
- construire NEXUS comme un moteur indépendant d'intelligence et de construction du contexte.

## Décision retenue

**Option retenue : construire NEXUS comme un moteur indépendant d'intelligence et de construction du contexte.**

NEXUS se situe entre l'environnement appelant et le consommateur final du contexte :

```text
Utilisateur / IDE / Agent / Orchestrateur
                 │
                 │ ContextRequest
                 ▼
               NEXUS
                 │
                 ├── découvre les sources disponibles
                 ├── recherche les candidats pertinents
                 ├── enrichit avec les relations connues
                 ├── classe les candidats
                 ├── élimine le bruit et les doublons
                 ├── applique le budget
                 └── explique les décisions
                 │
                 ▼
            ContextBundle
                 │
                 ▼
   Copilot / Claude / MCP / JARVIS / autre
                 │
                 ▼
             LLM / Agent
```

NEXUS **ne décide pas** :

- quel LLM doit être utilisé ;
- quel agent doit exécuter la tâche ;
- comment un skill doit être exécuté ;
- comment l'orchestrateur répartit le travail ;
- quelle réponse finale doit être produite.

NEXUS **décide ou recommande** uniquement ce qui concerne le contexte :

- quelles sources sont applicables ;
- quels fichiers et symboles sont pertinents ;
- quelles instructions doivent être prises en compte ;
- quels skills peuvent être utiles ;
- quelle documentation ou quel contexte Git mérite d'être inclus ;
- quels éléments doivent être exclus ou tronqués.

Le contrat canonique de sortie est un `ContextBundle` indépendant du fournisseur consommateur.

### Conséquences positives

- le cœur NEXUS peut être utilisé sans dépendre d'un fournisseur IA ;
- la même intelligence de contexte peut servir Copilot, Claude, une CLI ou un agent maison ;
- les mécanismes de sélection deviennent testables sans appel de modèle ;
- le périmètre métier reste cohérent et mesurable ;
- l'orchestration des modèles et agents peut évoluer indépendamment ;
- les intégrations deviennent des adaptateurs et non des dépendances structurantes.

### Conséquences négatives et compromis acceptés

- NEXUS ne fournit pas à lui seul une expérience conversationnelle complète ;
- chaque environnement consommateur devra disposer d'un mécanisme pour invoquer NEXUS et exploiter le `ContextBundle` ;
- certains environnements peuvent limiter la quantité ou le type de contexte injectable ;
- des adaptateurs spécifiques seront nécessaires pour tirer pleinement parti de certains outils.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Glissement fonctionnel vers un orchestrateur généraliste | Élevé | Refuser dans le cœur toute logique de choix de modèle ou d'exécution d'agent |
| Modèle `ContextBundle` trop orienté vers un fournisseur | Élevé | Maintenir des types métier neutres et traduire aux frontières |
| Difficulté d'intégration dans certains assistants | Moyen | Fournir progressivement CLI, API et MCP comme surfaces d'accès |
| Confusion entre skill sélectionné et skill exécuté | Moyen | Le bundle référence ou fournit le skill ; l'exécution reste externe |

### Confirmation

Cette décision est respectée si :

- le cœur peut être testé sans clé API de modèle ;
- aucune dépendance vers Copilot, Claude, JARVIS ou un fournisseur LLM n'est nécessaire au moteur ;
- `ContextRequest` et `ContextBundle` restent indépendants des protocoles clients ;
- les adaptateurs MCP, REST ou IDE ne contiennent pas la logique métier de sélection ;
- le routage des modèles reste hors du périmètre du cœur.

## Analyse détaillée des options

### Construire NEXUS comme un chatbot complet

**Avantages :**

- expérience utilisateur directement exploitable ;
- contrôle de bout en bout de l'interaction.

**Inconvénients :**

- concurrence directe avec des assistants déjà matures ;
- dépendance accrue aux APIs de modèles ;
- mélange entre raisonnement, interface, orchestration et contexte ;
- faible réutilisabilité dans Copilot ou Claude.

### Construire NEXUS comme un orchestrateur et routeur de modèles

**Avantages :**

- possibilité de sélectionner automatiquement le meilleur agent ou modèle ;
- contrôle global d'un écosystème IA.

**Inconvénients :**

- chevauchement avec JARVIS ou d'autres orchestrateurs ;
- responsabilité très différente de la construction du contexte ;
- fort couplage aux fournisseurs et à leurs capacités ;
- complexité opérationnelle inutile pour le MVP.

### Construire NEXUS comme un moteur de recherche de code uniquement

**Avantages :**

- périmètre simple ;
- valeur immédiate pour les repositories logiciels ;
- nombreux outils et standards réutilisables.

**Inconvénients :**

- ne traite pas les instructions, skills, documentation ou Git ;
- ne répond pas au problème global de gestion du contexte IA ;
- risque de reproduire des moteurs de code search existants sans valeur différenciante suffisante.

### Construire NEXUS comme un moteur indépendant d'intelligence et de construction du contexte

**Avantages :**

- correspond au problème central identifié ;
- englobe le code sans s'y limiter ;
- permet l'interopérabilité ;
- favorise une architecture ports/adaptateurs ;
- rend la qualité du contexte mesurable indépendamment du LLM.

**Inconvénients :**

- nécessite une normalisation de sources très différentes ;
- demande des adaptateurs pour chaque environnement consommateur ;
- impose de définir soigneusement les frontières avec les orchestrateurs.

## Impacts sur l'architecture

Cette décision définit la frontière fondamentale du système :

```text
Sources de contexte
Code / Docs / Instructions / Skills / Git
                 │
                 ▼
               NEXUS
       Context Intelligence Engine
                 │
                 ▼
            ContextBundle
                 │
       ┌─────────┼─────────┐
       ▼         ▼         ▼
    Copilot    Claude     MCP
```

Les composants internes doivent converger vers la production d'un `ContextBundle`, et les intégrations externes doivent rester périphériques.

## Conditions de réexamen

Cette décision ne doit être réexaminée que si le projet change volontairement de mission. L'ajout d'une API, d'un serveur MCP ou d'un connecteur d'agent ne constitue pas une raison de modifier ce positionnement.

## Décisions liées

- ADR-0003 — Conserver un cœur Java sans framework applicatif obligatoire.
- ADR-0011 — Normaliser les sources de contexte derrière des providers.
- ADR-0013 — Construire un ContextBundle sous budget de tokens explicable.
- ADR-0017 — Découpler NEXUS des outils et orchestrateurs externes.
