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

La campagne NXA3 durcit REST, filesystem/SCIP/skills, découverte native, fédération, Git, supply-chain, release Docker, SQLite et documentation. Les preuves d'une PR ou d'une itération ne sont pas recopiées ici comme état courant : la preuve applicable reste le run exact-head du commit concerné.

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

- SQLite canonique ; index dérivés reconstructibles.
- `ProjectPathGuard` pour les lectures projet sensibles ; traversal/symlinks refusés.
- découverte native bornée avant sélection de tokens par `ContextDiscoveryLimits`.
- maximum 100 projets uniques, validé avant résolution/readiness.
- Git local/read-only, historique borné et diff à capacité fixe.
- REST distant uniquement avec auth, roots et transport TLS effectif.
- CodeQL/NEXUS CI exact-head.
- outils fixes vérifiés contre des hashes versionnés.
- image Docker construite une fois, qualifiée, publiée sans rebuild.
- V005 impose les invariants de plage `CodeSymbol` dans SQLite.
- Dependabot cible `develop`.

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
5. Toute décision structurante durable implique un ADR.

La protection effective de `develop` reste une configuration GitHub externe au code et doit être vérifiée par API après modification.
