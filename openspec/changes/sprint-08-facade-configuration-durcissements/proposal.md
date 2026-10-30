# Proposal

## Why

Le sprint 8 de l'audit de code du 17/10/2026 (`docs/project/audits/2026-10-17/sprints.md`, section S8) nettoie la façade `NexusApplication` et la lecture de la configuration une fois les packages stabilisés par les sprints 4 et 7, puis durcit deux points d'entrée locaux. Les sept constats sont toujours présents sur `origin/develop` (vérification du 2026-10-29) :

- `AUD-ARC-09` — la séquence de lecture cohérente (READY, verrou partagé, session de lecture, revalidation READY) est recopiée dans six méthodes de la façade ; `inspect` n'en applique qu'une partie. L'invariant porté par ADR-0052 tient au copier-coller.
- `AUD-ARC-11` — `SQLException` figure dans les signatures publiques de `NexusApplication.create*`, de `NexusApplicationFactory` et dans les points d'entrée CLI, MCP et REST : les trois adaptateurs connaissent JDBC.
- `AUD-ARC-12` — douze classes du cœur lisent elles-mêmes l'environnement (`System.getenv`, seize appels) : pas de vue unique de la configuration effective, services de domaine non purs.
- `AUD-ARC-14` — `NexusPaths` mêle résolution des chemins et durcissement POSIX/ACL du stockage ; `search.evaluation.SearchQualityMetrics`, utilisée seulement par les tests, est livrée dans le JAR.
- `AUD-ARC-19` (fusionne `AUD-QUA-14`) — le package `index.jdt` définit une classe `ProcessBuilder` qui masque `java.lang.ProcessBuilder` à une frontière d'exécution sensible.
- `AUD-SEC-04` — en mode `NEXUS_REST_TRUST_LOCAL=true`, l'API REST loopback sans jeton ne vérifie ni l'hôte ni l'origine des requêtes ; une requête émise depuis un navigateur peut l'atteindre.
- `AUD-SEC-05` — la découverte du dépôt Git du contexte remonte les répertoires parents sans plafond ni contrôle du propriétaire du dépôt trouvé.

Critère de sortie du sprint : la façade n'expose plus `SQLException` ; `System.getenv` n'apparaît plus dans le cœur que dans la composition root ; le mode trust-local refuse un `Host` ou une `Origin` non loopback ; la découverte Git s'arrête à la racine du projet, sauf dépôt parent appartenant à l'utilisateur courant.

## What Changes

- Un gabarit unique de lecture cohérente, mono-projet et fédéré, porte toutes les lectures indexées de la façade, `inspect` compris (`AUD-ARC-09`).
- Les échecs d'ouverture du stockage traversent la façade sous une exception applicative (`PersistenceException`) ; aucun adaptateur n'importe `java.sql` (`AUD-ARC-11`).
- Un objet de configuration résolu une fois par `NexusApplicationFactory` est injecté dans les services ; le cœur ne lit plus l'environnement ailleurs (`AUD-ARC-12`).
- Le durcissement du stockage privé quitte `NexusPaths` pour le package `security` ; `SearchQualityMetrics` passe dans les sources de test (`AUD-ARC-14`).
- La classe JDT est renommée sans masquer `java.lang.ProcessBuilder` (`AUD-ARC-19`).
- L'adaptateur REST loopback refuse les requêtes dont l'hôte ou l'origine n'est pas loopback, même en mode trust-local (`AUD-SEC-04`).
- La découverte Git est plafonnée à la racine du projet ; un dépôt parent n'est retenu que s'il appartient à l'utilisateur courant, sinon le contexte Git est ignoré avec un diagnostic (`AUD-SEC-05`).
- Deux nouveaux ADR : configuration résolue en composition root, portée de la découverte Git.

## Capabilities

### New Capabilities
- `rest-transport-hardening`: contrôles de transport de l'adaptateur REST indépendants de l'authentification (hôte et origine des requêtes sur un listener loopback).
- `git-context-discovery`: portée de la découverte du dépôt Git qui alimente le contexte (plafond, propriétaire, diagnostic).

### Modified Capabilities

(aucune : `openspec/specs/` est vide)

Les constats `AUD-ARC-09`, `AUD-ARC-11`, `AUD-ARC-12`, `AUD-ARC-14` et `AUD-ARC-19` sont des refactorings internes sans changement de comportement observable : ils n'ont pas de spec et sont vérifiés par les tâches (tests ciblés, règle d'architecture, contenu du JAR).

## Impact

- Code : `core/src/main/java/com/nexus/application/` (`NexusApplication`, `NexusApplicationFactory`), `core/src/main/java/com/nexus/config/`, `core/src/main/java/com/nexus/security/`, les classes de limites qui lisent l'environnement, `core/src/main/java/com/nexus/index/jdt/`, `core/src/main/java/com/nexus/context/source/git/`, `core/src/main/java/com/nexus/cli/NexusCli.java`, `adapters/rest-quarkus/src/main/java/com/nexus/api/`, `adapters/mcp-java/src/main/java/com/nexus/mcp/NexusMcpServer.java`.
- Tests : `core/src/test/java/com/nexus/application/`, `core/src/test/java/com/nexus/config/NexusPathsTest.java`, `core/src/test/java/com/nexus/index/jdt/`, `core/src/test/java/com/nexus/context/source/git/`, `adapters/rest-quarkus/src/test/java/com/nexus/api/`.
- Documentation : `docs/developer/rest-exposure-security.md`, `docs/developer/git-context.md`, `docs/developer/architecture-implementation.md`, registre des ADR et vue arc42 des décisions.
- Comportement observable : en trust-local, une requête REST à hôte ou origine non loopback est refusée ; un projet situé sous un dépôt Git d'un autre compte n'a plus de contexte Git. Aucune migration SQLite, aucun changement de contrat CLI, MCP ou REST pour les clients locaux légitimes.
- Prérequis : sprints 4 et 7 fusionnés (points d'entrée et packages stabilisés) ; la règle d'architecture de `AUD-ARC-12` s'appuie sur le test d'architecture introduit par `AUD-ARC-07` (sprint 3).
