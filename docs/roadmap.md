# Feuille de route NEXUS

Cette feuille de route décrit l'état architectural **courant** et les travaux encore réellement ouverts. Les détails historiques restent dans GitHub.

## Stratégie de branches

```text
develop = intégration et qualification
main    = release
```

Toute promotion vers `main` doit partir d'un HEAD exact qualifié. Le contrat de protection de `develop` est décrit dans [`developer/branch-governance.md`](developer/branch-governance.md) et doit être appliqué dans les règles GitHub du repository.

## Baseline fonctionnelle NEXUS 0.2.0

- Java 21 cible/runtime minimum ;
- SQLite canonique, Lucene dérivé ;
- indexation incrémentale et recovery fail-closed ;
- JavaParser, Markdown, SCIP borné, JDT LS opt-in, import MINOS ;
- recherche fichier/symbole/graphe/Git et fédération multi-projet ;
- recherche sémantique locale opt-in avec provenance ;
- contexte projet/fédéré sous budget ;
- instructions natives, Agent Skills, AI Skills Registry ;
- CLI, REST Quarkus et MCP Java STDIO ;
- distribution ZIP, Windows self-contained et Docker ;
- CodeQL, OSV, Trivy, SBOM, attestations et benchmarks de régression.

## Hardening intégré — NXA3

NXA3 a établi les contrats suivants :

- TLS REST effectif hors loopback ;
- confinement SCIP et sources natives contre traversal/symlinks ;
- budget global de découverte native avant sélection de tokens ;
- fail-fast fédéré avant résolution/readiness ;
- diff Git à capacité fixe et historique borné ;
- CodeQL exact-head ;
- ancres d'intégrité Maven/JDT LS versionnées ;
- publication Docker build-once / publish-qualified-image ;
- préflight GHCR fail-closed et reprise idempotente ;
- V005 SQLite pour les invariants de plage ;
- Dependabot ciblé sur `develop` ;
- documentation opérationnelle contrôlée par CI.

## Hardening intégré — NXA4

NXA4 complète cette baseline avec :

- frames JSON-RPC JDT LS bornées avant allocation, headers bornés et file entrante limitée à 256 messages ;
- tâches externes bornées à **8 workers réellement actifs** avec saturation explicite ;
- requêtes Lucene analysées bornées à **128 termes uniques** avant expansion multi-champs ;
- limites REST fédérées alignées sur les politiques globales ;
- `constraints` non supportées refusées explicitement au lieu d'être ignorées ;
- health/metrics Quarkus déplacés vers un listener de management dédié `127.0.0.1:9000` ;
- Ollama distant en HTTPS par défaut, HTTP distant seulement via opt-in administratif explicite ;
- credentials intégrés dans l'URI Ollama refusés ;
- redaction conservatrice des secrets avant embeddings et avant restitution des fragments de contexte ;
- exclusions scanner étendues pour les fichiers/répertoires sensibles ;
- profil sémantique `content-v2` pour reconstruire les vecteurs historiques incompatibles ;
- `NEXUS_HOME`/SQLite privés sur POSIX (`0700` répertoires, `0600` fichier) et refus des chemins persistants symboliques concernés ;
- checks de bounds SCIP résistants aux overflows ;
- parcours JavaParser limité aux catégories AST nécessaires.

## Qualification de scale

Le Scale Benchmark couvre :

1. SQLite/recherche ;
2. graphe ;
3. fédération jusqu'à 100 projets et budget de travail contrôlé ;
4. découverte native filesystem avec 1 000 skills synthétiques au seuil exact du budget.

La recherche lexicale contient en plus un test de non-régression sur les requêtes à forte cardinalité afin d'éviter un dépassement du budget de clauses Lucene.

## Travail restant

### Gouvernance — NXA3-14 / #130

Le principal contrôle non réalisable par un commit reste la protection effective de `develop` dans GitHub :

- PR obligatoire ;
- checks requis selon la politique approuvée ;
- force-push et suppression interdits ;
- exceptions administratives limitées et traçables.

Tant que l'API GitHub indique `protected=false`, ce point reste **ouvert**.

### Watch items

Les améliorations suivantes restent conditionnées à une preuve reproductible :

- lifecycle Lucene persistant/partagé ;
- isolation processus plus forte d'un provider réellement non coopératif ;
- filesystem réseau/hostile ;
- cache Git persistant ;
- recovery sémantique renforcé selon les scénarios de corruption/provider indisponible ;
- moteur de recherche substring alternatif.

## Références

- Architecture : [`architecture.md`](architecture.md)
- CI / supply-chain : [`developer/ci-and-supply-chain.md`](developer/ci-and-supply-chain.md)
- Release/recovery : [`developer/release-and-recovery.md`](developer/release-and-recovery.md)
- REST : [`developer/rest-api.md`](developer/rest-api.md)
- Sémantique : [`developer/semantic-search.md`](developer/semantic-search.md)
- JDT LS : [`developer/jdt-language-server.md`](developer/jdt-language-server.md)
- Limites courantes : [`developer/current-limitations.md`](developer/current-limitations.md)
