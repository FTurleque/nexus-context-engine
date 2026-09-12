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

## Hardening post-audit du 7 septembre 2026

La campagne de consolidation ajoute :

- confiance explicite de la racine JDT LS avant démarrage ;
- plafonds communs de métadonnées Code Intelligence ;
- inspection ACL Windows sur tous les chemins sensibles ;
- mode optionnel `NEXUS_REQUIRE_PRIVATE_STORAGE=true` pour échouer fermé lorsque la confidentialité du stockage ne peut pas être démontrée ;
- revalidation de l'identité filesystem avant/après ouverture dans le fallback `SafeFileIO` lorsque `SecureDirectoryStream` n'est pas disponible ;
- circuit-breaker provider linéarisé contre les races timeout/start ;
- qualification sémantique réelle Ollama mensuelle/manuelle avec runtime épinglé par SHA-256 et seuils de non-régression ;
- contrat explicite de non-adoption de `jdk.incubator.vector` tant qu'une nouvelle mesure same-runner ne démontre pas un bénéfice robuste.

## Qualification de scale

Le Scale Benchmark couvre :

1. SQLite/recherche ;
2. graphe ;
3. fédération jusqu'à 100 projets et budget de travail contrôlé ;
4. découverte native filesystem avec 1 000 skills synthétiques au seuil exact du budget.

La recherche lexicale contient en plus un test de non-régression sur les requêtes à forte cardinalité afin d'éviter un dépassement du budget de clauses Lucene.

## Gouvernance effective

NXA3-14 / #130 est satisfait : le ruleset GitHub actif protège `develop`, exige le passage par pull request, interdit force-push/suppression et impose les checks permanents approuvés. Toute modification repository-admin doit être suivie d'une revalidation API.

Le modèle de maintenance courant est **solo**. L'absence de revue humaine distincte du mainteneur et l'absence d'obligation de resynchroniser une PR avec sa base juste avant merge sont des choix explicites de ce modèle, pas des hardenings à poursuivre. Les audits doivent se concentrer sur les protections réellement applicables : exact-head, checks automatisés, PR obligatoire, interdiction des force-pushes/suppressions et qualification des artefacts.

## Travail restant

### Watch items

Les améliorations suivantes restent conditionnées à une preuve reproductible :

- isolation processus plus forte uniquement si un provider réellement non coopératif est introduit et démontre ce mode d'échec ;
- extension du support à un filesystem réseau/distribué précis au-delà de la qualification SMB loopback actuelle ;
- moteur de recherche substring alternatif si les besoins réels dépassent les stratégies actuelles ;
- réévaluation du Vector API uniquement si le workload devient significativement plus vectoriel ou si une API non incubateur apporte un gain net.

## Références

- Architecture : [`architecture.md`](architecture.md)
- CI / supply-chain : [`developer/ci-and-supply-chain.md`](developer/ci-and-supply-chain.md)
- Release/recovery : [`developer/release-and-recovery.md`](developer/release-and-recovery.md)
- REST : [`developer/rest-api.md`](developer/rest-api.md)
- Sémantique : [`developer/semantic-search.md`](developer/semantic-search.md)
- JDT LS : [`developer/jdt-language-server.md`](developer/jdt-language-server.md)
- Limites courantes : [`developer/current-limitations.md`](developer/current-limitations.md)
