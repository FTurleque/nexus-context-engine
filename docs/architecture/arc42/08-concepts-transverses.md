# Section 8 — Concepts transverses

## 8.1 Identité, readiness et fédération

- projets identifiés par UUID durables ;
- lectures indexées : projet `READY` requis ;
- portée fédérée : maximum 100 projets uniques ;
- la cardinalité canonique est validée avant résolution/readiness ;
- doublons dédupliqués avec ordre stable.

## 8.2 Sécurité filesystem

La frontière de confiance est la racine canonique du projet :

- `ProjectPathGuard` refuse traversal, symlink final et symlink d'ancêtre ;
- `SafeFileIO` borne les lectures sensibles ;
- SCIP relit ses sources via la frontière canonique ;
- instructions, références, skills et customisations projet durcies réutilisent la même politique.

La protection Java portable ne constitue pas un sandbox absolu contre un acteur local capable de modifier agressivement le filesystem pendant l'opération.

## 8.3 Budget de découverte native

`ContextDiscoveryLimits` est partagé entre instructions, skills, customisations et Git avant le budget final de tokens.

| Dimension | Défaut |
|---|---:|
| entrées visitées | 100000 |
| candidats | 5000 |
| octets | 32 MiB |
| durée | 15 s |

Configuration invalide ou dépassement : échec fermé.

## 8.4 Git

Git reste local/read-only. Commits, chemins, historique et co-changements sont capés. Le diff working-tree utilise un sink à capacité fixe avant conversion/troncature.

## 8.5 Données et provenance

- SQLite canonique ;
- migrations forward-only avec checksum ;
- V004 invalide les anciens index aux plages impossibles ;
- V005 impose `start_line >= 1` et `end_line >= start_line` ;
- Lucene/snapshots externes dérivés ;
- provenance incompatible ⇒ rebuild/refus selon le type d'index.

## 8.6 Cohérence d'indexation

Une mutation par projet : mutex JVM + `FileLock` OS. Le snapshot canonique est revalidé avant `READY`. Une mutation concurrente détectée provoque un échec fail-closed.

## 8.7 Sécurité REST

Hors loopback :

- token robuste ;
- roots autorisées ;
- mode d'exposition explicite ;
- HTTP clair désactivé pour les modes HTTPS ;
- key material TLS effectif ;
- reverse proxy : forwarding + trusted proxies bornés.

`loopback-forward` est réservé à Docker avec publication hôte loopback.

## 8.8 Interfaces

CLI, REST et MCP utilisent `NexusApplication` et les politiques communes de résultats, readiness et portée. MCP utilise le SDK 2.0.1 en STDIO.

## 8.9 Supply-chain

- Maven 3.9.16 via wrapper + SHA-512 versionné ;
- JDT LS fixe + SHA-256 versionné ;
- CodeQL exact-head ;
- OSV delta + SBOM agrégé ;
- Trivy + SBOM image ;
- Actions épinglées par SHA ;
- Docker build-once puis publication de l'image exacte qualifiée ;
- GHCR fail-closed/resumable pour même contenu.

## 8.10 Tests et benchmarks

La preuve courante appartient au SHA exact. Le Scale Benchmark couvre SQLite, graphe, fédération et découverte native 1 000 skills. NEXUS CI exécute les tests d'ancres et les contrats documentaires avant le reactor.

## 8.11 Gouvernance

`develop` est l'intégration, `main` la release. La protection effective de `develop` doit être appliquée côté GitHub ; les workflows seuls ne peuvent pas empêcher un push direct sur une branche non protégée.
