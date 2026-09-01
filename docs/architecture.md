# Architecture de NEXUS

Ce document décrit l'architecture **courante versionnée** de NEXUS 0.2.0. `develop` est la branche d'intégration et `main` la branche de release ; les anciens résultats d'itération restent dans les documents historiques, pas dans cette synthèse.

## Mission

NEXUS transforme :

```text
repositories + projets enregistrés + requête + budget
```

en recherche classée ou en contexte minimal, pertinent, explicable et borné.

## Invariants

1. JVM >= 21 ; bytecode/API Java 21.
2. `NexusApplication` est le composition root partagé CLI/REST/MCP.
3. SQLite est canonique ; Lucene et snapshots externes sont dérivés/reconstructibles.
4. Une lecture indexée exige `READY`, mais les limites de portée fédérée sont validées **avant** la résolution/readiness.
5. Maximum 100 projets uniques par portée fédérée.
6. `ProjectPathGuard` protège les lectures sensibles, y compris SCIP, instructions, skills et customisations projet.
7. La découverte native partage un `ContextDiscoveryBudget` avant sélection de tokens : visites, candidats, octets et deadline.
8. Les providers/importers externes sont optionnels et bornés.
9. Ranking, limites et budgets restent déterministes/explicables.
10. Une seule mutation d'index par projet est active sur un `NEXUS_HOME` local ; snapshot revalidé avant `READY`.
11. Le graphe, Git, la fédération et la découverte native sont bornés en travail, pas seulement en résultat final.
12. Une exposition REST hors loopback est fail-closed sans authentification, roots et transport TLS effectif conformes.
13. Les outils téléchargés à version fixe sont contrôlés par des ancres d'intégrité versionnées.
14. Une image release est construite une fois, qualifiée puis publiée sans rebuild.

## Composition

```text
CLI ───────┐
REST ──────┼──> NexusApplication
MCP ───────┘        │
                    ├─ ProjectRepository / IndexRepository (SQLite)
                    ├─ ProjectIndexingService
                    ├─ SearchService / FederatedSearchService
                    ├─ DefaultContextBuilder
                    └─ FederatedContextService
```

## Indexation et autorité

```text
ProjectScanner
  │  ProjectPathGuard + SafeFileIO + limites
  ↓
analyses embarquées / enrichissements optionnels
  ↓
SQLite canonique + génération/fingerprint
  │
  ├─ SCIP borné et confiné
  ├─ JDT LS opt-in, timeout + SHA-256 versionné
  └─ MINOS explicite
  ↓
Lucene lexical dérivé
  └─ Lucene sémantique dérivé si opt-in
```

V004 invalide les index historiques dont les plages de symboles sont incompatibles avec le domaine. V005 reconstruit `symbols` avec :

```text
start_line >= 1
end_line >= start_line
```

## Recherche et contexte

La recherche combine Lucene, SQLite borné, graphe borné, Git local et sémantique opt-in. La fédération valide la cardinalité canonique avant toute résolution coûteuse puis applique une sur-récupération locale contrôlée et un top-K global.

Le contexte mono-projet combine recherche, instructions, skills et Git sous deux familles de bornes :

- budget de découverte natif pré-sélection ;
- budget de tokens final.

Le contexte fédéré conserve provenance, fair floor, déduplication, refill et budget de travail global.

## Git local

`LocalGitContextSourceProvider` reste read-only et sans réseau. Commits, historique et chemins sont capés ; les patches cibles passent par un sink à capacité fixe avant troncature à 6 000 caractères par zone.

## Sécurité REST

Loopback est le défaut. Hors loopback :

- token robuste ;
- `NEXUS_REST_ALLOWED_PROJECT_ROOTS` non vide ;
- `direct-https` : listener Quarkus TLS effectif et HTTP clair désactivé ;
- `reverse-proxy-https` : mêmes garanties TLS backend + forwarding activé + trusted proxies bornés ;
- `loopback-forward` : uniquement runtime Docker avec publication hôte loopback.

## Supply-chain et release

Les gates exact-head comprennent NEXUS CI, CodeQL, OSV, Docker Distribution, Scale Benchmark et Windows Installer selon leur périmètre. Docker Distribution construit et qualifie une image unique. La release charge l'artefact qualifié, vérifie hash/ID et publie ce contenu exact.

Les tags version/SHA sont immuables. Le préflight GHCR échoue fermé sur les erreurs ambiguës et permet une reprise idempotente seulement si le contenu existant correspond à l'image qualifiée.

## Gouvernance

`develop` doit être protégé par la configuration GitHub décrite dans [`developer/branch-governance.md`](developer/branch-governance.md). Tant que l'API retourne `protected=false`, ce contrôle de gouvernance reste non satisfait même si le code et la CI sont corrects.

Voir aussi [`developer/architecture-implementation.md`](developer/architecture-implementation.md), [`developer/ci-and-supply-chain.md`](developer/ci-and-supply-chain.md) et [`roadmap.md`](roadmap.md).
