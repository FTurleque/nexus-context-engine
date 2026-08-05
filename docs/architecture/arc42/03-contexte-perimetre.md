# Section 3 — Contexte et périmètre

## 3.1 Frontière du système

NEXUS Context Engine est un processus local qui :

- **reçoit** des requêtes de contexte ou de recherche (CLI, REST, MCP) ;
- **lit** des repositories de code enregistrés localement ;
- **écrit** dans `NEXUS_HOME` (SQLite + Lucene) ;
- **appelle optionnellement** des services locaux externes (Ollama, JDT LS) ou consomme
  des fichiers locaux fournis par des outils tiers (MINOS JSON, SCIP).

NEXUS ne :

- génère pas de réponses textuelles ;
- route pas de modèles de langage ;
- exécute pas de code utilisateur, de hooks ou de scripts ;
- accède pas à Internet.

## 3.2 Diagramme C4 Niveau 1 — Contexte du système

```mermaid
C4Context
    title Diagramme de contexte — NEXUS Context Engine

    Person(dev, "Développeur", "«Person»\nUtilise NEXUS via CLI, IDE ou assistant IA")
    Person(admin, "Administrateur local", "«Person»\nGère NEXUS_HOME, variables d'environnement et runtime")

    System(nexus, "NEXUS Context Engine", "«Software System»\nMoteur local d'intelligence de contexte.\nConstruit un ContextBundle pertinent, explicable\net borné depuis des repositories locaux.")

    System_Ext(copilot, "GitHub Copilot / Claude / Gemini", "«Software System»\nAssistants IA consommateurs du ContextBundle\nvia MCP ou configuration d'agent")
    System_Ext(ollama, "Ollama", "«Software System»\nServeur local d'embeddings vectoriels\n(opt-in, /api/embed)")
    System_Ext(jdtls, "JDT Language Server", "«Software System»\nProvider Java profond opt-in,\nanalyse approfondie des symboles")
    System_Ext(minos, "MINOS", "«Software System»\nOutil d'intelligence de code externe,\nconsommé via un contrat JSON local versionné")
    System_Ext(scip, "SCIP (index)", "«Software System»\nIndex de code enrichi importé opportunistement\n(fichier local généré par un outil tiers)")
    System_Ext(vcs, "Repository Git local", "«Software System»\nSources, historique Git,\n.gitignore / .nexusignore, instructions natives")

    Rel(dev, nexus, "Envoie des requêtes", "CLI / REST / MCP STDIO")
    Rel(admin, nexus, "Configure et surveille", "Variables d'env, NEXUS_HOME")
    Rel(nexus, vcs, "Lit les fichiers source", "Filesystem local")
    Rel(copilot, nexus, "Appelle les outils de contexte", "MCP STDIO / REST HTTP")
    Rel(nexus, ollama, "Génère des embeddings", "HTTP /api/embed (opt-in)")
    Rel(nexus, jdtls, "Requête analyse Java profonde", "Processus local (opt-in)")
    Rel(nexus, minos, "Importe l'intelligence de code", "Lecture JSON local (opt-in)")
    Rel(nexus, scip, "Importe l'index SCIP", "Lecture fichier local (opt-in)")
```

## 3.3 Acteurs et systèmes externes

### Acteurs humains

| Acteur | Interaction |
|--------|-------------|
| Développeur | CLI directe, configuration d'un assistant IA |
| Administrateur local | Variables d'environnement, NEXUS_HOME, monitoring |

### Systèmes externes

| Système | Couplage | Protocole | Optionnel |
|---------|----------|-----------|-----------|
| Repository Git local | Fort (source de données) | Filesystem | Non |
| GitHub Copilot / Claude / autres assistants | Faible (consommateur) | MCP STDIO ou REST HTTP | Oui |
| Ollama | Faible | HTTP `/api/embed` (batches) | Oui — `NEXUS_SEMANTIC_PROVIDER=ollama` |
| JDT Language Server | Faible | Processus local sous timeout | Oui — `NEXUS_JDTLS_HOME` |
| MINOS | Très faible | Lecture JSON local | Oui — contrat versionné |
| SCIP | Très faible | Lecture fichier local | Oui — import opportuniste |

## 3.4 Interfaces exposées par NEXUS

| Interface | Protocole | Endpoint / Point d'entrée | Authentification |
|-----------|-----------|---------------------------|-----------------|
| CLI | Processus JVM | `com.nexus.cli.NexusCli` (fat JAR) | N/A |
| REST API | HTTP/1.1 JSON | `127.0.0.1:8080/api/v1/` | Bearer token optionnel (H6) |
| MCP STDIO | JSON-RPC 2.0 sur stdin/stdout | `com.nexus.mcp.NexusMcpServer` | N/A (transport local) |
| Métriques | HTTP Prometheus | `/q/metrics` (Quarkus) | N/A |
| Health | HTTP | `/q/health/live`, `/q/health/ready` | N/A |
