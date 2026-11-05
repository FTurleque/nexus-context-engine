---
id: SEC-0033
title: "Séparer les instructions contextuelles de la configuration opérationnelle des agents"
domain: security
rules: true
files: ["core/src/main/java/com/nexus/context/**"]
---

# Séparer les instructions contextuelles de la configuration opérationnelle des agents

- **Statut** : Accepted
- **Date** : 2026-07-19

## Context

Les repositories peuvent contenir à la fois :

- des **instructions destinées au raisonnement ou au travail sur le code** ;
- des **configurations opérationnelles** qui règlent les permissions, hooks, serveurs MCP, commandes autorisées, modèles, variables d'environnement ou comportements d'un outil.

Exemples :

```text
CLAUDE.md
AGENTS.md
.github/copilot-instructions.md

.claude/settings.json
.claude/settings.local.json
.mcp.json
```

Ces fichiers n'ont pas la même fonction.

Injecter automatiquement un fichier de configuration opérationnelle dans un `ContextBundle` peut :

- exposer des chemins locaux ou informations sensibles ;
- injecter des permissions ou commandes sans rapport avec la requête ;
- consommer inutilement le budget de tokens ;
- faire croire au consommateur que NEXUS doit exécuter ou reproduire la configuration d'un autre agent.

La question est : **comment NEXUS doit-il tenir compte du paramétrage existant d'un projet sans confondre configuration d'outil et contexte destiné au modèle ?**

## Facteurs de décision

- sécurité locale ;
- minimisation du contexte ;
- respect de la responsabilité de NEXUS ;
- compatibilité avec les projets déjà configurés ;
- explicabilité ;
- absence d'exécution implicite de hooks ou serveurs ;
- préparation des futurs adaptateurs MCP et agents.

## Options envisagées

- injecter tous les fichiers `.github`, `.claude` et assimilés ;
- ignorer complètement les configurations opérationnelles ;
- détecter les configurations opérationnelles mais ne sélectionner que leurs éléments explicitement contextuels ;
- déléguer leur interprétation au LLM.

## Decision

**NEXUS distingue explicitement les sources contextuelles des configurations opérationnelles.**

### Sources contextuelles

Elles peuvent être sélectionnées et intégrées au `ContextBundle` :

```text
AGENTS.md
AGENT.md
CLAUDE.md
.claude/CLAUDE.md
GEMINI.md
.github/copilot-instructions.md
.github/instructions/**/*.instructions.md
documentation Markdown pertinente
fichiers référencés explicitement depuis une instruction supportée
```

### Configurations opérationnelles

Elles peuvent être **détectées et signalées**, mais leur contenu brut n'est pas injecté automatiquement :

```text
.claude/settings.json
.claude/settings.local.json
.mcp.json
configurations de permissions
hooks
serveurs MCP
configuration de modèle
variables d'environnement
```

NEXUS ne lance aucun hook, ne démarre aucun serveur MCP et ne transforme pas automatiquement les permissions d'un outil en instructions de contexte.

### Configuration contextuelle explicite

Lorsqu'une convention native possède une directive explicitement destinée à désigner du contexte, un provider spécifique peut l'interpréter de manière sûre.

Exemples :

- référence `@docs/architecture.md` depuis un fichier d'instructions ;
- motif `applyTo` d'une instruction Copilot ;
- futur paramètre natif déclarant un nom de fichier de contexte.

La directive doit être comprise par le provider. NEXUS ne demande pas au LLM d'interpréter arbitrairement un JSON de configuration.

### Détection sans injection

Les configurations opérationnelles détectées sont exposées dans les métadonnées de diagnostic du build de contexte :

```text
nativeConfigurationsDetected
```

Cette information permet à l'utilisateur ou à un futur adaptateur de savoir que le projet possède déjà une configuration d'agent sans envoyer son contenu au modèle.

### Fichiers locaux non versionnés

Les variantes locales comme :

```text
.claude/settings.local.json
```

sont considérées comme spécifiques à la machine ou à l'utilisateur.

Elles ne deviennent jamais une source de contexte portable par défaut.

## Do's and Don'ts

### Do

- Sélectionner comme sources contextuelles `AGENTS.md`, `AGENT.md`, `CLAUDE.md`, `.claude/CLAUDE.md`, `GEMINI.md`, `.github/copilot-instructions.md`, `.github/instructions/**/*.instructions.md`, la documentation Markdown pertinente et les fichiers référencés explicitement depuis une instruction supportée.
- Détecter et signaler les configurations opérationnelles dans les métadonnées de diagnostic du build de contexte.
- Confier une directive contextuelle explicite (`@docs/architecture.md`, `applyTo`) à un provider qui la comprend et l'interprète de manière sûre.

### Don't

- Ne pas injecter automatiquement le contenu brut d'une configuration opérationnelle (`.claude/settings.json`, `.claude/settings.local.json`, `.mcp.json`, permissions, hooks, serveurs MCP, modèle, variables d'environnement).
- Ne lancer aucun hook, ne démarrer aucun serveur MCP et ne pas transformer les permissions d'un outil en instructions de contexte.
- Ne pas demander au LLM d'interpréter arbitrairement un JSON de configuration.
- Ne pas faire d'une variante locale non versionnée comme `.claude/settings.local.json` une source de contexte portable par défaut.

## Conséquences positives

- NEXUS tient compte de l'écosystème existant sans absorber son exécution ;
- réduction du risque de fuite de secrets ou de configuration locale ;
- le budget reste consacré au contexte utile ;
- les responsabilités restent claires ;
- les futurs adaptateurs peuvent exploiter les métadonnées de configuration sans modifier le cœur.

## Conséquences négatives et compromis acceptés

- certains réglages spécifiques à un outil ne seront pas reproduits par NEXUS ;
- une configuration propriétaire contenant indirectement du contexte nécessite un provider explicite ;
- la simple présence d'un fichier de configuration ne garantit pas que NEXUS en comprenne toutes les options.

## Risques et mesures de maîtrise

| Risque | Impact | Mesure |
|---|---|---|
| Injection de secrets depuis un settings JSON | Élevé | Ne jamais injecter le contenu brut par défaut |
| Perte d'un contexte déclaré dans une configuration | Moyen | Ajouter un provider ciblé lorsque la convention est documentée |
| Exécution accidentelle d'un hook | Élevé | Découverte en lecture seule uniquement |
| Confusion entre MCP configuré et contexte MCP | Moyen | Signaler la configuration, ne pas démarrer le serveur |

## Confirmation

La décision est respectée si :

- `.claude/settings.json` n'apparaît pas comme `ContextItem` brut ;
- la présence de `.claude/settings.json` peut être visible dans les métadonnées ;
- `.claude/CLAUDE.md` reste une source d'instructions ;
- une référence explicite vers un fichier du repository peut être intégrée ;
- aucun hook ou serveur externe n'est exécuté pendant la construction du contexte.

## Compliance and Enforcement

- Règle Archgate `SEC-0033-separer-instructions-contextuelles-et-configuration-operationnelle-des-agents.rules.ts` / `context-build-runs-no-hook-or-mcp-server` : sous `core/src/main/java/com/nexus/context/` (commentaires et littéraux effacés), aucun `ProcessBuilder`, `Runtime.getRuntime().exec`, `ProcessHandle` ni référence au SDK `io.modelcontextprotocol`. Contrôle propre à Archgate.
- Test `core/src/test/java/com/nexus/context/NativeContextSourcesIntegrationTest.java` (`combinesApplicableNativeInstructionsCodeAndDocumentationWithoutInjectingOperationalConfig`) : `.claude/CLAUDE.md` est sélectionné, le contenu de `.claude/settings.json` n'est pas injecté et sa présence figure dans les métadonnées.
- Test `core/src/test/java/com/nexus/context/source/NativeProjectCustomizationDetectorTest.java` : la détection refuse les symlinks finaux et d'ancêtre.

## Écarts constatés

### 2026-10-29

- La décision nomme la métadonnée de diagnostic `nativeConfigurationsDetected` ; le code l'expose sous la clé `nativeCustomizationsDetected` (`core/src/main/java/com/nexus/context/ContextBuildMetadata.java:100`), clé vérifiée par `NativeContextSourcesIntegrationTest.java:133`. Aucune occurrence de `nativeConfigurationsDetected` hors de cet ADR.

## Conditions de réexamen

Réexaminer si :

- un standard de configuration contextuelle portable devient largement adopté ;
- l'adaptateur MCP nécessite d'exposer une partie structurée des configurations existantes ;
- des projets réels démontrent qu'une configuration opérationnelle contient systématiquement des informations indispensables au ranking.

## Décisions liées

- ADR-0005 — Adopter un fonctionnement local-first et opt-in.
- ADR-0011 — Normaliser les sources de contexte derrière des providers.
- ADR-0012 — Réutiliser les standards existants pour les instructions et les skills.
- ADR-0032 — Préserver et normaliser le contexte natif des projets.
