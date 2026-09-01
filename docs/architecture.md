# Architecture de NEXUS

Ce document décrit l'architecture **courante versionnée** de NEXUS 0.2.0. `develop` est la branche d'intégration et `main` la branche de release ; les anciens résultats d'itération restent dans les documents historiques.

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
7. La découverte native partage `ContextDiscoveryLimits`/`ContextDiscoveryBudget` avant sélection de tokens : visites, candidats, octets et deadline.
8. Les providers/importers externes sont optionnels, bornés en temps et en concurrence ; au plus 8 tâches externes restent réellement actives simultanément à l'échelle JVM.
9. Le transport JSON-RPC JDT LS borne messages, headers, lignes et backlog avant allocation/accumulation.
10. Ranking, limites et budgets restent déterministes/explicables.
11. Une seule mutation d'index par projet est active sur un `NEXUS_HOME` local ; snapshot revalidé avant `READY`.
12. Le graphe, Git, la fédération et la découverte native sont bornés en travail, pas seulement en résultat final.
13. Une requête Lucene analysée est bornée à 128 termes uniques avant expansion multi-champs.
14. Une exposition REST hors loopback est fail-closed sans authentification, roots et transport TLS effectif conformes.
15. Health/metrics ne sont pas servis par le listener applicatif : le management Quarkus reste sur `127.0.0.1:9000` par défaut.
16. La recherche sémantique reste opt-in ; Ollama distant exige HTTPS sauf opt-in administratif explicite pour HTTP.
17. Les secrets à forte confiance sont redigés avant embeddings et avant restitution des fragments de contexte.
18. Le profil sémantique `content-v2` invalide/reconstruit les anciens vecteurs incompatibles.
19. `NEXUS_HOME`/SQLite sont durcis en permissions privées sur POSIX ; les ACL Windows natives ne sont pas remplacées destructivement.
20. Les outils téléchargés à version fixe sont contrôlés par des ancres d'intégrité versionnées.
21. Une image release est construite une fois, qualifiée puis publiée sans rebuild.

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
  │  ProjectPathGuard + SafeFileIO + limites + exclusions sensibles
  ↓
analyses embarquées / enrichissements optionnels
  ↓
SQLite canonique + génération/fingerprint
  │
  ├─ SCIP borné et confiné
  ├─ JDT LS opt-in, framing borné, timeout + SHA-256 versionné
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

La recherche combine Lucene, SQLite ciblé, graphe borné, Git local et sémantique opt-in. La fédération valide la cardinalité canonique avant toute résolution coûteuse puis applique une sur-récupération locale contrôlée et un top-K global.

Lucene limite les requêtes à forte cardinalité à 128 termes analysés uniques avant expansion sur les cinq champs de recherche.

Le contexte mono-projet combine recherche, instructions, skills et Git sous deux familles de bornes :

- budget de découverte natif pré-sélection ;
- budget de tokens final.

Une map `constraints` non vide est rejetée tant que sa sémantique n'est pas implémentée ; elle n'est jamais ignorée silencieusement.

Le contexte fédéré conserve provenance, fair floor, déduplication, refill et budget de travail global.

## Secrets et sémantique

Les contenus à forte probabilité de secret sont redigés avant les embeddings et avant les fragments de contexte retournés. La redaction conserve les séparateurs de lignes des blocs multilignes pour ne pas décaler les ranges persistés.

Ollama HTTP est accepté sans opt-in uniquement sur loopback. Une URI distante doit utiliser HTTPS ; HTTP distant exige `NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true`. Les credentials intégrés à l'URI sont refusés.

## Code Intelligence externe

JDT LS reste un processus opt-in. Son framing entrant applique :

```text
message       <= 16 MiB
headers       <= 64 KiB
header line   <= 8 KiB
pending queue <= 256 messages
```

Les tâches externes utilisent un budget de concurrence global de 8 workers actifs. Une intégration qui ignore l'interruption ne peut donc pas faire croître indéfiniment le nombre de threads NEXUS.

## Persistance locale

`NEXUS_HOME`, `indexes` et `locks` sont rendus privés sur POSIX (`0700`) et SQLite en `0600`. Les chemins persistants durcis refusent les symlinks concernés. Sur Windows, NEXUS conserve les ACL natives au lieu d'appliquer des permissions POSIX fictives.

## Sécurité REST

Listener applicatif par défaut :

```text
127.0.0.1:8080
```

Listener management séparé :

```text
127.0.0.1:9000/q/health
127.0.0.1:9000/q/health/ready
127.0.0.1:9000/q/metrics
```

Hors loopback :

- token robuste ;
- `NEXUS_REST_ALLOWED_PROJECT_ROOTS` non vide ;
- `direct-https` : listener Quarkus TLS effectif et HTTP clair désactivé ;
- `reverse-proxy-https` : mêmes garanties TLS backend + forwarding activé + trusted proxies bornés ;
- `loopback-forward` : uniquement runtime Docker avec publication hôte loopback.

Le listener de management ne doit pas être publié par le reverse proxy.

## Supply-chain et release

Les gates exact-head comprennent NEXUS CI, CodeQL, OSV, Docker Distribution, Scale Benchmark, Scanner Corpus Benchmark et Windows Installer selon leur périmètre ; SonarCloud fournit en plus le Quality Gate de PR.

Docker Distribution construit et qualifie une image unique. La release charge l'artefact qualifié, vérifie hash/ID et publie ce contenu exact.

Les tags version/SHA sont immuables. Le préflight GHCR échoue fermé sur les erreurs ambiguës et permet une reprise idempotente seulement si le contenu existant correspond à l'image qualifiée.

## Gouvernance

`develop` doit être protégé par la configuration GitHub décrite dans [`developer/branch-governance.md`](developer/branch-governance.md). Tant que l'API retourne `protected=false`, ce contrôle de gouvernance reste non satisfait même si le code et la CI sont corrects.

Voir aussi [`developer/architecture-implementation.md`](developer/architecture-implementation.md), [`developer/ci-and-supply-chain.md`](developer/ci-and-supply-chain.md), [`developer/current-limitations.md`](developer/current-limitations.md) et [`roadmap.md`](roadmap.md).
