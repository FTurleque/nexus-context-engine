# Lucene lifecycle qualification

Les runtimes longue durée (REST, MCP, composés par `NexusApplication.createLongLived(...)`) gardent en cache, par projet chaud, les readers Lucene lexical et sémantique ; les writers restent ouverts le temps d'une indexation ou d'un rebuild, et la CLI (`NexusApplication.create(...)`) reste entièrement operation-scoped. La décision est [DATA-0074](../architecture/adr/DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md). Cette page décrit le benchmark qui qualifie ce cycle de vie (issue #50) et ce qu'il a mesuré.

## Résultat de la qualification

La qualification ABBA exact-head de la PR #159 a mesuré, pour le prototype persistant, un gain p95 de recherche d'environ 85 % (lexical) et 87 % (sémantique) sous Linux, 79 % et 92 % sous Windows, et un rollback suivi d'un rebuild de production réussi. Seuls les readers persistants ont été adoptés en production (PR #161) : un `IndexWriter` persistant garderait le `write.lock` Lucene pendant toute la vie du processus et concurrencerait le verrou NEXUS par projet (`ProjectIndexLockManager`), si bien que le gain du prototype sur les micro-écritures n'est pas repris. Le workflow `.github/workflows/lucene-lifecycle-benchmark.yml` rejoue cette qualification sous Linux et Windows ; les sections suivantes décrivent le harnais, qui compare toujours l'implémentation operation-scoped au prototype de test.

## Qualification harness

`LuceneLifecycleQualificationBenchmarkTest` is an opt-in, hermetic benchmark. It does **not** change the runtime implementation. The persistent classes used by the test are test-only prototypes.

The benchmark covers representative local corpora for both search engines:

- lexical Lucene search;
- lexical one-document micro-updates;
- semantic kNN Lucene search;
- semantic one-document micro-updates.

For each operation it compares:

1. the production operation-scoped implementation;
2. a persistent reader/writer prototype holding the relevant Lucene resources open.

Measurements use ABBA ordering after warmup to reduce first-run and ordering bias. The report records p50, p95, mean and sample count for each side.

The CI profile uses 3,000 lexical documents and 2,000 semantic documents. The full profile uses 10,000 lexical documents and 8,000 semantic documents.

## Resource evidence

The report records the steady-state resource contract explicitly. Operation-scoped production retains no Lucene directory, reader or writer between calls. The benchmark prototype retains four directories, two readers, two writers and one analyzer while all four measured persistent resources are open.

An approximate retained-heap delta is also emitted for diagnostic comparison. It is intentionally **not** a hard CI budget because JVM GC timing makes a single heap delta unsuitable as a deterministic regression gate.

## Recovery evidence

The benchmark stages an uncommitted lexical update and an uncommitted semantic update through the persistent writer prototypes, rolls both writers back, then performs a production rebuild and verifies searchability.

This checks the minimum recovery invariant required before a persistent lifecycle can even be considered: abandoned writer state must not prevent the existing production rebuild path from restoring a usable index.

Physical semantic-index corruption recovery remains covered separately by the semantic recovery qualification introduced for issue #54.

## Adoption rule

A persistent lifecycle is **not** adopted merely because one metric is faster.

The benchmark marks a persistent lifecycle as a candidate only when all of the following hold in the same run:

- lexical search p95 improves by at least 25%;
- semantic search p95 improves by at least 25%;
- lexical persistent micro-write p95 regresses by no more than 10%;
- semantic persistent micro-write p95 regresses by no more than 10%;
- lexical rollback + production rebuild succeeds;
- semantic rollback + production rebuild succeeds.

Even when those conditions hold, the report recommendation is only:

`candidate_requires_repeated_linux_windows_qualification_and_adr_before_production_change`

Production adoption still requires repeated Linux and Windows qualification and an ADR describing ownership, refresh policy, shutdown semantics, locking behavior and corruption/rebuild handling.

If the conditions are not met, the recommendation is:

`retain_operation_scoped_lifecycle`

No threshold may be lowered merely to make persistent state qualify.

## Running locally

CI profile:

```bash
./mvnw -B -pl core \
  -Dtest=LuceneLifecycleQualificationBenchmarkTest \
  -Dnexus.scale.benchmark.enabled=true \
  -Dnexus.scale.benchmark.profile=ci \
  -Dnexus.lucene.lifecycle.benchmark.output=target/lucene-lifecycle-benchmark.json \
  test
```

Use `nexus.scale.benchmark.profile=full` for the larger corpus.

GitHub Actions runs the same qualification through `.github/workflows/lucene-lifecycle-benchmark.yml`, retains the JSON report for 90 days and prints the decision metrics in the job log.

## OpenSpec lucene-reader-cache

Spec : [`openspec/specs/lucene-reader-cache/spec.md`](../../openspec/specs/lucene-reader-cache/spec.md) ; matrice de traçabilité : [openspec-traceability.md](openspec-traceability.md).

Cette section décrit la lecture des métadonnées de commit Lucene sur le reader en cache des runtimes longue durée (`PersistentLuceneSemanticSearchIndex`, `PersistentLuceneSearchIndex`).

### Comportement existant vérifié

| Étiquette | Fait | Source |
|---|---|---|
| [Existant vérifié] | La compatibilité d'un index sémantique se lit sur le reader en cache quand il existe, sinon sur le dernier commit, sans ouvrir de `DirectoryReader` supplémentaire ; des recherches successives n'en ouvrent qu'un. | `core/src/test/java/com/nexus/search/semantic/lucene/LuceneSemanticSearchIndexTest#compatibilityIsReadOnTheCachedReaderAcrossSuccessiveSearches` |
| [Existant vérifié] | Une provenance différente ou un index absent rendent « incompatible » ; le commit d'un autre writer est vu par le reader en cache. | `core/src/test/java/com/nexus/search/semantic/lucene/LuceneSemanticSearchIndexTest#compatibilityIsReadOnTheCachedReaderAcrossSuccessiveSearches` |
| [Existant vérifié] | La présence de l'index lexical se lit sans reader hors du cache ; un répertoire absent rend « absent » sans erreur. | `core/src/test/java/com/nexus/search/lucene/LuceneSearchIndexTest#persistentPresenceProbeOpensNoReaderOutsideTheCache` |

### Vues

Vue organigramme de composants (non UML, lecture C4 simplifiée) — D'où viennent les métadonnées de commit lues par la compatibilité et la sonde de présence ? (existant)

```mermaid
flowchart LR
    Q["Compatibilité sémantique ou sonde de présence"] --> C{"Reader en cache ?"}
    C -->|oui| U["userData du reader en cache"]
    C -->|non| L["Dernier commit, sans charger les segments"]
```

Exigences : `lucene-reader-cache › La compatibilité sémantique est lue sur le reader en cache`, `lucene-reader-cache › La présence de l'index lexical est lue sans ouvrir l'index`.

### Écarts et limites

- Limite : le décompte d'ouvertures (`openedReaderCount`) est un compteur de test des classes persistantes ; il ne couvre pas les readers ouverts par d'autres composants.

### Propositions et questions ouvertes

- Questions : aucune.
