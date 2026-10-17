---
name: nexus-new-capability
description: Implémenter une nouvelle fonctionnalité de bout en bout dans NEXUS en respectant ports/adaptateurs — domaine dans core, port et implémentation SQLite/Lucene/provider, composition dans NexusApplication, exposition CLI/REST/MCP à parité, bornes, tests, documentation et ADR. Utiliser pour ajouter une commande, un outil MCP, un endpoint REST, une source ou un provider.
argument-hint: "<capacité>"
---

# Nouvelle capacité NEXUS

## 1. Cadrer

- Critère de réussite observable (commande CLI, réponse REST/MCP, métadonnée) et bornes attendues (taille, cardinalité, durée).
- Pour un changement structurant, demander d'abord un plan à l'agent `nexus-architect`.
- Chercher l'existant réutilisable : `git grep`, outils NEXUS/MINOS si disponibles (`find_symbol`, `search_code`).

## 2. Domaine (`core`)

- Placer la logique dans le package métier existant (`context`, `search`, `index`, `project`, `ranking`, `security`…), sans framework.
- Nouvelle I/O = nouveau port (interface) dans le domaine + implémentation dans `persistence/sqlite`, `search/lucene` ou un provider.
- Bornes : réutiliser les politiques centrales ; diagnostic explicable pour toute exclusion/troncature ; ordre déterministe.
- Lectures projet via `ProjectPathGuard`/`SafeFileIO` ; redaction avant toute sortie de contenu.

## 3. Composition

- Brancher dans `NexusApplication` (composition root partagé). Lecture d'index : `projectIndexLockManager.acquireRead(...)` + `READY` exigé ; fédération : valider la portée (≤ 100 projets) avant résolution.

## 4. Exposition à parité

- CLI (`com.nexus.cli`) : sortie humaine + `--json`, codes de sortie stables (ADR-0030).
- REST (`adapters/rest-quarkus`) : limites alignées sur les politiques centrales, projection repository-relative, `constraints` non supportées rejetées.
- MCP (`adapters/mcp-java`) : schéma d'entrée borné, stdout réservé au protocole.

## 5. Tests et doc

- Tests unitaires du domaine + tests d'adaptateur ; borne exacte et borne + 1 (agent `nexus-test-engineer`).
- Doc courante en français : `docs/developer/<sujet>.md`, `docs/developer/rest-api.md`, `docs/developer/mcp.md`, `README.md` si l'usage change ; tableau de `docs/developer/README.md`.
- ADR si décision durable (skill `nexus-adr`).
- Relecture par l'agent `nexus-reviewer`, puis validation complète (skill `nexus-validate`).
