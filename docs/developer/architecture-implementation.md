# Architecture d'implémentation — NEXUS 0.2.0

Ce chapitre décrit l'organisation concrète du code versionné. `develop` reçoit l'intégration qualifiée ; `main` reste la branche de release.

## Repository

```text
nexus-context-engine/
├── pom.xml
├── core/pom.xml
├── src/main/java/com/nexus/
├── src/main/resources/db/migration/
├── src/test/java/com/nexus/
├── adapters/rest-quarkus/
├── adapters/mcp-java/
├── adapters/assistant-clients/
├── distribution/
├── packaging/
├── scripts/
├── .github/workflows/
└── docs/
```

Le parent gouverne Java 21, BOM/plugins, JaCoCo, SBOM et dépendances communes. Quarkus est en 3.39.1 et le SDK MCP en 2.0.1.

## Composition root

`NexusApplication` compose les ports communs :

```text
NexusPaths
  ↓
SqliteDatabase
  ├─ SqliteProjectRepository
  └─ SqliteIndexRepository
  ↓
ProjectRegistry
ProjectIndexingService
SearchService
FederatedSearchService
DefaultContextBuilder
FederatedContextService
```

CLI, REST et MCP délèguent à cette façade.

## Indexation et persistance

`ProjectIndexingService` orchestre mutex JVM + `FileLock`, scan borné, fingerprint canonique, analyses, SQLite, index dérivés, revalidation puis `READY`.

V004 invalide les anciens index contenant des plages invalides ; V005 reconstruit `symbols` avec les `CHECK` :

```text
start_line >= 1
end_line >= start_line
```

Les migrations sont forward-only et enregistrées avec checksum.

## Frontière filesystem

`ProjectPathGuard` est la frontière partagée pour les lectures projet durcies. SCIP, instructions/références, skills locaux/registry et customisations concernées refusent traversal, symlink final et symlink d'ancêtre.

La découverte native utilise un `ContextDiscoveryBudget` commun à `DefaultContextBuilder` :

- entrées visitées ;
- candidats ;
- octets cumulés ;
- deadline.

Les limites sont consommées avant le travail coûteux lorsque possible et un dépassement échoue fermé.

## Fédération

`FederatedScopePolicy` limite la portée à **100 projets uniques** (UUID canoniques). Les surfaces valident la cardinalité canonique **avant** `requireReadyProject` ou résolution équivalente ; un 101e projet unique échoue donc avant les lookups/readiness ultérieurs.

Une portée valide est ensuite résolue puis transmise à `FederatedSearchService`/`FederatedContextService` sous budget de travail et budget final.

## Git local

`LocalGitContextSourceProvider` borne :

- 50 commits récents ;
- chemins modifiés par commit et cumulés ;
- historique court ;
- co-changements ;
- patches cibles.

Le patch working-tree est écrit dans `BoundedOutput`, sink à capacité fixe, avant conversion/troncature à 6 000 caractères. Le statut est filtré aux chemins cibles.

## REST

`NexusRestExposureGuard` et `NexusRestTransportPolicy` valident une exposition non-loopback :

- token robuste ;
- roots autorisées ;
- `direct-https` avec HTTP clair désactivé et key material TLS effectif ;
- `reverse-proxy-https` avec le même backend TLS plus forwarding et trusted proxies bornés ;
- `loopback-forward` uniquement pour Docker publié côté hôte sur loopback.

## MCP

Le module utilise le SDK MCP 2.0.1 en STDIO. `stdout` reste réservé au framing JSON-RPC. Le SDK amont 2.0.1 apporte notamment des lectures HTTP/STDIO bornées ; NEXUS conserve STDIO comme transport local supporté.

## Supply-chain

NEXUS CI vérifie les ancres Maven/JDT LS et les contrats documentaires avant le reactor. CodeQL qualifie l'exact head. OSV scanne delta PR + SBOM reactor. Docker Distribution construit une image unique, exécute smokes/Trivy/SBOM, puis exporte l'image exacte si la release la demande.

`release.yml` ne rebuild pas cette image : il vérifie l'archive et l'ID Docker, puis publie les tags immuables sous préflight GHCR fail-closed/resumable.

## Gouvernance

La configuration GitHub de `develop` doit imposer le contrat de [`branch-governance.md`](branch-governance.md). Ce contrôle repository-admin n'est pas remplacé par les workflows versionnés.
