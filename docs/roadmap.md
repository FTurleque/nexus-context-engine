# Feuille de route NEXUS

Cette feuille de route décrit l'état architectural courant et les travaux encore réellement ouverts. Les détails historiques restent dans GitHub.

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

## Hardening NXA3

La campagne NXA3 couvre les contrats suivants :

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
- documentation opérationnelle vérifiée par CI.

## Qualification de scale

Le Scale Benchmark couvre désormais quatre familles :

1. SQLite/recherche ;
2. graphe ;
3. fédération jusqu'à 100 projets et budget 200k tokens ;
4. découverte native filesystem avec 1 000 skills synthétiques au seuil exact du budget.

Aucun nouveau moteur FTS/trigram, cache Git persistant ou lifecycle Lucene plus complexe n'est adopté sans régression mesurée et besoin démontré.

## Travail restant

### Gouvernance

Le contrôle NXA3 qui ne peut pas être réalisé par un commit est la protection effective de `develop` dans GitHub : PR obligatoire, checks requis, force-push/suppression interdits et exceptions administratives limitées et traçables. Tant que l'API GitHub indique que la branche est non protégée, ce point reste ouvert.

### Watch items

Les améliorations suivantes restent conditionnées à une preuve :

- lifecycle Lucene persistant ;
- isolation processus de providers non coopératifs ;
- filesystem réseau/hostile ;
- cache Git persistant ;
- recovery sémantique renforcé ;
- moteur de recherche substring alternatif.

## Références

- Architecture : [`architecture.md`](architecture.md)
- CI / supply-chain : [`developer/ci-and-supply-chain.md`](developer/ci-and-supply-chain.md)
- Release/recovery : [`developer/release-and-recovery.md`](developer/release-and-recovery.md)
- Publication immuable : [`developer/immutable-release-publishing.md`](developer/immutable-release-publishing.md)
- Limites natives : [`developer/native-context-discovery-limits.md`](developer/native-context-discovery-limits.md)
- Limites courantes : [`developer/current-limitations.md`](developer/current-limitations.md)
