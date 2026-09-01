# Guide développeur NEXUS

Ce répertoire distingue :

1. **documentation courante** — doit suivre l'exact head ;
2. **documents d'itération/benchmark** — résultats historiques, volontairement datés ;
3. **ADR** — décisions append-only.

## État courant

```text
version      0.2.0
Java         >=21, release 21
Maven        3.9.16 via wrapper vérifié
integration  develop
release      main
Quarkus      3.39.1
MCP SDK      2.0.1
```

Le build courant utilise **Maven 3.9.16** via le wrapper et son ancre d'intégrité versionnée.

Les campagnes NXA3 et NXA4 constituent la baseline de hardening courante. Une ancienne PR ou un ancien run vert n'est jamais une preuve pour un nouveau HEAD : la preuve applicable reste le run exact-head du commit concerné.

## Parcours recommandé

| Sujet | Document |
|---|---|
| architecture globale | [Architecture](../architecture.md) |
| Arc42 | [Documentation d'architecture](../architecture/README.md) |
| architecture concrète | [Architecture d'implémentation](architecture-implementation.md) |
| limites et watch items | [Limites actuelles](current-limitations.md) |
| roadmap | [Roadmap](../roadmap.md) |
| release / migration / recovery | [Release et recovery](release-and-recovery.md) |
| CI / supply-chain | [CI et supply-chain](ci-and-supply-chain.md) |
| gouvernance branches | [Gouvernance](branch-governance.md) |
| contexte et budgets | [Construction du contexte](context-building.md) |
| découverte native | [Limites de découverte](native-context-discovery-limits.md) |
| REST | [API REST](rest-api.md) |
| sémantique / Ollama | [Recherche sémantique](semantic-search.md) |
| Code Intelligence | [Code Intelligence](code-intelligence.md) |
| JDT LS | [JDT Language Server](jdt-language-server.md) |
| MCP | [MCP](mcp.md) |
| Git | [Contexte Git](git-context.md) |
| AI Skills Registry | [AI Skills Registry](ai-skills-registry.md) |
| scale | [Recherche à grande échelle](large-scale-search.md) |

## Composition

```text
CLI / REST / MCP
       │
       ▼
NexusApplication
       │
       ├─ SQLite canonique / Lucene dérivé
       ├─ Search + FederatedSearch
       ├─ DefaultContextBuilder
       └─ FederatedContextService
```

## Contrats à préserver

### Baseline NXA3

- SQLite canonique ; index dérivés reconstructibles.
- `ProjectPathGuard` pour les lectures projet sensibles ; traversal/symlinks refusés.
- découverte native bornée avant sélection de tokens par `ContextDiscoveryLimits`/`ContextDiscoveryBudget`.
- maximum 100 projets uniques, validé avant résolution/readiness.
- Git local/read-only, historique borné et diff à capacité fixe.
- REST distant uniquement avec auth, roots et transport TLS effectif.
- CodeQL/NEXUS CI exact-head.
- outils fixes vérifiés contre des hashes versionnés.
- image Docker construite une fois, qualifiée, publiée sans rebuild.
- V005 impose les invariants de plage `CodeSymbol` dans SQLite.
- Dependabot cible `develop`.

### Baseline NXA4

- management Quarkus séparé du listener applicatif (`127.0.0.1:9000`).
- frames JDT LS et backlog JSON-RPC bornés ; tâche externe globale <= 8 workers actifs.
- requête Lucene analysée <= 128 termes uniques avant expansion multi-champs.
- limites REST fédérées alignées sur les politiques centrales.
- `constraints` non supportées rejetées explicitement.
- Ollama distant HTTPS par défaut ; HTTP distant seulement via `NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true`.
- credentials dans l'URI Ollama refusés.
- secrets à forte confiance redigés avant embeddings et fragments de contexte.
- profil sémantique `content-v2` pour reconstruire les vecteurs historiques incompatibles.
- `NEXUS_HOME` privé sur POSIX et chemins persistants symboliques concernés refusés.

## Build

```powershell
.\mvnw.cmd clean install
```

ou :

```bash
./mvnw clean install
```

## Contribution

1. Modifier la documentation courante lorsque le contrat change.
2. Ajouter une preuve automatique lorsqu'un fait documentaire est machine-vérifiable.
3. Ne pas présenter un ancien run vert comme preuve d'un nouveau head.
4. Ne pas abaisser un budget/seuil pour masquer une régression.
5. Toute décision structurante durable implique un ADR lorsque le changement dépasse un simple durcissement d'implémentation.

La protection effective de `develop` reste une configuration GitHub externe au code et doit être vérifiée par API après modification. Tant que `protected=false`, #130 reste ouvert.
