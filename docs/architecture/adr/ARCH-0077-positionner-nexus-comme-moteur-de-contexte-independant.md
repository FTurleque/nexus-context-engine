---
id: ARCH-0077
title: "Positionner NEXUS comme moteur de contexte indépendant des modèles, des clients et des orchestrateurs"
domain: architecture
rules: true
---

# Positionner NEXUS comme moteur de contexte indépendant des modèles, des clients et des orchestrateurs

- **Statut** : Accepted
- **Date** : 2026-11-08
- **Origine** : consolidation du registre ADR (audit du 2026-11-08)
- **Remplace** : [ARCH-0001](archive/ARCH-0001-positionner-nexus-comme-moteur-intelligence-contexte.md), [ARCH-0017](archive/ARCH-0017-decoupler-nexus-des-outils-et-orchestrateurs-externes.md)

## Context

Les assistants et agents IA ont chacun leurs mécanismes de contexte : fichiers ouverts, instructions projet, agents, prompts, skills, hooks, mémoire de conversation, outils MCP ou conventions propriétaires. GitHub Copilot, Claude, ChatGPT/OpenAI, les IDE, les agents personnalisés, AI Skills Registry, JARVIS, Alfred ou Brainiac n'organisent pas ces éléments de la même manière et n'ont pas les mêmes responsabilités : certains fournissent une interface, d'autres orchestrent des agents, choisissent un modèle, exécutent des skills ou imposent leur format d'instructions.

Le problème que NEXUS résout est situé en amont de la génération de réponses et du choix de modèle : **parmi toutes les informations disponibles pour une demande, déterminer lesquelles sont utiles, dans quel ordre, sous quel budget et avec quelle justification**.

Sans frontière claire, NEXUS deviendrait à la fois chatbot, orchestrateur d'agents, routeur de modèles, registre de skills et moteur de recherche de code ; et si son cœur dépendait des API ou conventions de ces outils, il perdrait son rôle de moteur de contexte commun.

La question : **quelle est la mission de NEXUS, et comment s'intègre-t-il aux modèles, clients et orchestrateurs sans que l'un d'eux devienne une dépendance ou le centre de gravité de son architecture ?**

## Options envisagées

Mission :

- **Chatbot complet** : expérience directement exploitable, mais concurrence des assistants matures, dépendance aux API de modèles, mélange de raisonnement, interface, orchestration et contexte, faible réutilisabilité dans Copilot ou Claude.
- **Orchestrateur et routeur de modèles** : contrôle global, mais chevauchement avec JARVIS, responsabilité étrangère au contexte, fort couplage aux fournisseurs.
- **Moteur de recherche de code seul** : périmètre simple, mais ignore instructions, skills, documentation et Git, sans valeur différenciante.
- **Moteur indépendant d'intelligence et de construction du contexte** : répond au problème central, englobe le code sans s'y limiter, rend la qualité du contexte mesurable sans LLM ; demande de normaliser des sources hétérogènes et des adaptateurs par consommateur.

Intégration :

- **JARVIS orchestrateur obligatoire** : écosystème cohérent, mais NEXUS cesse d'être indépendant, dépendances circulaires possibles.
- **Copilot et Claude intégrés dans le cœur** : accès direct à leurs API, mais cœur dicté par leurs évolutions.
- **AI Skills Registry obligatoire** : source centralisée, mais NEXUS ne fonctionne plus seul, contradiction avec le local-first.
- **Adaptateurs indépendants, intégrations optionnelles** : faible couplage, responsabilités claires ; coût de mapping et composants périphériques à maintenir.

## Decision

**Options retenues : moteur indépendant d'intelligence et de construction du contexte, exposé par des adaptateurs indépendants ; tous les outils externes sont des consommateurs ou des fournisseurs optionnels.**

### Mission et frontière

NEXUS se situe entre l'environnement appelant et le consommateur du contexte :

```text
Utilisateur / IDE / Agent / Orchestrateur
                 │ ContextRequest
                 ▼
               NEXUS ── découvre les sources, recherche, enrichit, classe,
                 │      élimine bruit et doublons, applique le budget, explique
                 ▼
            ContextBundle
                 ▼
   Copilot / Claude / MCP / JARVIS / autre  →  LLM / Agent
```

NEXUS **ne décide pas** quel LLM utiliser, quel agent exécute la tâche, comment un skill s'exécute, comment l'orchestrateur répartit le travail ni quelle réponse finale produire.

NEXUS **décide ou recommande** uniquement ce qui concerne le contexte : sources applicables, fichiers et symboles pertinents, instructions à prendre en compte, skills utiles, documentation ou contexte Git à inclure, éléments à exclure ou à tronquer.

Le contrat canonique de sortie est un `ContextBundle` indépendant du fournisseur consommateur ; `ContextRequest` et `ContextBundle` restent indépendants des protocoles clients, et le `ContextBundle` est le même quel que soit le canal d'accès.

### Répartition des responsabilités

```text
JARVIS / orchestrateur          → choisit le flux, l'agent ou le modèle
NEXUS                           → sélectionne et construit le contexte
AI Skills Registry              → découvre ou résout des capacités/skills
Alfred / Brainiac / agents      → exécutent le traitement spécialisé
LLM                             → raisonne et génère la réponse
```

### Intégrations

- **Copilot et Claude** : leurs fichiers et conventions peuvent être découverts par des providers ([ARCH-0078](ARCH-0078-normaliser-les-sources-de-contexte-natives-derriere-des-providers.md)) ; NEXUS peut produire un contexte exploitable par un adaptateur spécifique ; il ne remplace pas leurs mécanismes natifs, et leurs formats natifs ne deviennent pas les modèles métier du cœur.
- **AI Skills Registry** : NEXUS peut demander ou résoudre un skill via le registre ; le registre reste optionnel et NEXUS découvre des skills locaux sans lui ([BE-0079](BE-0079-charger-les-agent-skills-par-divulgation-progressive.md)).
- **JARVIS, Alfred et Brainiac** : aucune dépendance de compilation du cœur, aucune logique de routage spécifique ; intégration par API, MCP ou contrat dédié si nécessaire.
- Tous les adaptateurs appellent les mêmes services du cœur et ne contiennent pas la logique métier de sélection ([ARCH-0075](ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md)).

Une intégration peut devenir recommandée ou livrée par défaut sans devenir une dépendance du cœur.

## Do's and Don'ts

### Do

- Produire comme contrat canonique de sortie un `ContextBundle` indépendant du fournisseur consommateur.
- Limiter ce que NEXUS décide ou recommande au contexte : sources applicables, fichiers et symboles pertinents, instructions, skills utiles, documentation ou contexte Git à inclure, éléments à exclure ou à tronquer.
- Garder `ContextRequest` et `ContextBundle` indépendants des protocoles clients.
- Exposer NEXUS par des adaptateurs indépendants ; Copilot, Claude, AI Skills Registry, JARVIS, Alfred, Brainiac et les autres outils restent des consommateurs ou fournisseurs optionnels.
- Garder AI Skills Registry optionnel : NEXUS découvre des skills locaux sans lui.
- Intégrer JARVIS, Alfred et Brainiac par API, MCP ou contrat dédié.

### Don't

- Ne pas décider dans NEXUS quel LLM utiliser, quel agent exécute la tâche, comment un skill s'exécute, comment l'orchestrateur répartit le travail ni quelle réponse finale produire.
- Ne pas créer de dépendance de compilation du cœur vers JARVIS, Alfred, Brainiac, un SDK Copilot, Claude/Anthropic, OpenAI ou tout autre fournisseur LLM.
- Ne pas placer de logique de routage de modèles dans le cœur.
- Ne pas faire des formats natifs Copilot ou Claude les modèles métier du cœur, ni remplacer leurs mécanismes natifs.
- Ne pas placer la logique métier de sélection dans les adaptateurs MCP, REST ou IDE.

## Consequences

### Positives

- Le cœur s'utilise et se teste sans fournisseur IA ni clé API de modèle ; la sélection est testable sans appel de modèle.
- La même intelligence de contexte sert Copilot, Claude, une CLI ou un agent maison ; aucun outil externe ne bloque l'évolution du cœur.
- Le périmètre métier reste cohérent et mesurable ; les responsabilités de chaque projet restent claires.
- Les intégrations sont des adaptateurs, non des dépendances structurantes ; les conventions fournisseurs évoluent dans leurs adaptateurs.

### Négatives

- NEXUS ne fournit pas à lui seul une expérience conversationnelle complète ; chaque consommateur doit savoir invoquer NEXUS et exploiter le `ContextBundle`.
- Des adaptateurs spécifiques sont à développer et maintenir, parfois en plusieurs versions ; certaines fonctionnalités natives n'ont qu'un mapping imparfait.
- Certains environnements limitent la quantité ou le type de contexte injectable ; le parcours d'intégration est moins direct qu'un couplage fort.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Glissement vers un orchestrateur généraliste ou un routeur de modèles | Élevé | Refuser dans le cœur toute logique de choix de modèle ou d'exécution d'agent |
| Modèle `ContextBundle` ou types du domaine orientés vers un fournisseur | Élevé | Types métier neutres, traduction aux frontières |
| AI Skills Registry devient obligatoire | Élevé | `SkillSourceProvider` local, connecteur de registre optionnel |
| Dépendance circulaire entre projets | Élevé | NEXUS ne dépend d'aucun orchestrateur consommateur |
| Divergence des résultats selon l'adaptateur | Moyen | Tous les adaptateurs appellent les mêmes services du cœur |
| Confusion entre skill sélectionné et skill exécuté | Moyen | Le bundle référence ou fournit le skill ; l'exécution reste externe |
| Intégration difficile dans certains assistants | Moyen | CLI, API REST et MCP comme surfaces d'accès |

## Compliance and Enforcement

- Règle Archgate `ARCH-0077-positionner-nexus-comme-moteur-de-contexte-independant.rules.ts` / `core-depends-on-no-assistant-or-orchestrator` : refuse dans `core/pom.xml` (commentaires XML effacés) tout `groupId`/`artifactId` Anthropic, OpenAI, Copilot, Claude, JARVIS, Alfred ou Brainiac, et dans `core/src/main/java` (commentaires et littéraux effacés) tout import de ces SDK ou projets.
- Règles Archgate de `ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.rules.ts` (`rest-adapter-delegates-business-logic`, `mcp-handlers-delegate-to-nexus-application`) : les adaptateurs REST et MCP appellent les mêmes services du cœur et ne nomment aucun service de sélection.
- Tests du cœur lancés par `./mvnw -B clean install` (job « Linux reactor Maven build » de `.github/workflows/ci.yml`) sans clé API de modèle ni outil externe.
- Revue : absence de choix de modèle, de routage ou d'exécution d'agent dans le cœur ; neutralité de `ContextRequest` et `ContextBundle`.

## Conditions de réexamen

La décision n'est réexaminée que si NEXUS change volontairement de mission (orchestrateur complet, produit lié à un fournisseur unique). L'ajout d'une API, d'un serveur MCP ou d'un connecteur d'agent ne justifie pas de modifier ce positionnement.

## Mise en œuvre

- Contrats : `core/src/main/java/com/nexus/context/ContextRequest.java`, `core/src/main/java/com/nexus/context/ContextBundle.java` ; construction : `core/src/main/java/com/nexus/context/DefaultContextBuilder.java`.
- Composition root : `core/src/main/java/com/nexus/application/NexusApplication.java` ; adaptateurs minces : `adapters/rest-quarkus`, `adapters/mcp-java`, `adapters/assistant-clients`.

## References

- [ARCH-0005](ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.md) — local-first, intégrations externes opt-in.
- [ARCH-0075](ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md) — adaptateurs minces isolés.
- [ARCH-0076](ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md) — cœur Java sans framework.
- [ARCH-0078](ARCH-0078-normaliser-les-sources-de-contexte-natives-derriere-des-providers.md) — sources de contexte natives derrière des providers.
- [BE-0072](BE-0072-construire-le-contextbundle-sous-budget-de-tokens.md) — `ContextBundle` sous budget de tokens.
- [BE-0079](BE-0079-charger-les-agent-skills-par-divulgation-progressive.md) — Agent Skills et AI Skills Registry optionnel.
- [Vue d'ensemble de l'architecture](../overview.md).
