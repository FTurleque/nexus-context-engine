---
id: ARCH-0041
title: "Réutiliser le serveur MCP pour les clients assistants"
domain: architecture
rules: true
files: ["adapters/assistant-clients/**", "core/pom.xml", "adapters/mcp-java/pom.xml"]
---

# Réutiliser le serveur MCP pour les clients assistants

- **Statut** : Accepted
- **Date** : 2026-07-20

## Context

NEXUS doit être utilisable depuis plusieurs environnements clients sans dupliquer le moteur de recherche, le ranking ou la construction du contexte.

## Decision

Les intégrations clientes réutilisent le serveur MCP STDIO NEXUS validé à l'Itération 12.

Le module `adapters/assistant-clients` reste autonome et génère uniquement des commandes ou fragments de configuration. Il ne modifie pas les préférences de l'utilisateur et ne crée aucun nouveau chemin métier vers le cœur.

## Do's and Don'ts

### Do

- Faire réutiliser par les intégrations clientes le serveur MCP STDIO NEXUS.
- Garder `adapters/assistant-clients` autonome, limité à générer des commandes ou des fragments de configuration.

### Don't

- Ne pas modifier les préférences de l'utilisateur pendant la génération.
- Ne pas créer de nouveau chemin métier vers le cœur.

## Consequences

- une seule surface d'outils NEXUS est maintenue ;
- le cœur reste indépendant des clients ;
- la génération de configuration est déterministe et testable ;
- les conventions natives des clients restent distinctes des tools MCP.

## Confirmation

La décision est respectée si le cœur et le serveur MCP restent indépendants du module d'intégration, si les profils clients sont générés par tests déterministes et si aucun fichier utilisateur n'est modifié pendant la génération.

## Compliance and Enforcement

- Règle Archgate `ARCH-0041-reutiliser-le-serveur-mcp-pour-les-clients-assistants.rules.ts` / `assistant-clients-independent-of-engine` : `adapters/assistant-clients/pom.xml` ne dépend ni de `nexus-context-engine` ni de `nexus-mcp-java`, ses sources n'importent aucun package `com.nexus` hors de `com.nexus.integrations`, et ni `core/pom.xml` ni `adapters/mcp-java/pom.xml` ne dépendent de `nexus-assistant-clients`.
- Règle Archgate `ARCH-0041-reutiliser-le-serveur-mcp-pour-les-clients-assistants.rules.ts` / `assistant-clients-write-no-file` : les sources principales du module n'écrivent, ne créent, ne copient, ne déplacent ni ne suppriment aucun fichier ; seul `new FileOutputStream(FileDescriptor.out)` est admis.
- Tests `AssistantIntegrationGeneratorTest`, `AssistantIntegrationCliTest` et `AssistantIntegrationEscapingTest` (`adapters/assistant-clients/src/test/java/com/nexus/integrations`) : génération déterministe des profils clients ; tests lancés par `./mvnw -B clean install` (job « Linux reactor Maven build » de `.github/workflows/ci.yml`).

## Décisions liées

- ADR-0017 — Découpler NEXUS des outils et orchestrateurs externes.
- ADR-0040 — Exposer NEXUS via un adaptateur MCP STDIO mince.
