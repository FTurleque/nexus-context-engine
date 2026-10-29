---
status: accepted
date: 2026-07-20
---

# ADR-0041 — Réutiliser le serveur MCP pour les clients assistants

## Contexte

NEXUS doit être utilisable depuis plusieurs environnements clients sans dupliquer le moteur de recherche, le ranking ou la construction du contexte.

## Décision

Les intégrations clientes réutilisent le serveur MCP STDIO NEXUS validé à l'Itération 12.

Le module `adapters/assistant-clients` reste autonome et génère uniquement des commandes ou fragments de configuration. Il ne modifie pas les préférences de l'utilisateur et ne crée aucun nouveau chemin métier vers le cœur.

## Conséquences

- une seule surface d'outils NEXUS est maintenue ;
- le cœur reste indépendant des clients ;
- la génération de configuration est déterministe et testable ;
- les conventions natives des clients restent distinctes des tools MCP.

## Confirmation

La décision est respectée si le cœur et le serveur MCP restent indépendants du module d'intégration, si les profils clients sont générés par tests déterministes et si aucun fichier utilisateur n'est modifié pendant la génération.

## Décisions liées

- ADR-0017 — Découpler NEXUS des outils et orchestrateurs externes.
- ADR-0040 — Exposer NEXUS via un adaptateur MCP STDIO mince.
