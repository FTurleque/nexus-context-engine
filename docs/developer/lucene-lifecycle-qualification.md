# Lucene lifecycle qualification

NEXUS currently keeps Lucene resources **operation-scoped**: readers, writers, analyzers and directories are opened for an operation and closed before returning. This is the conservative default because it minimizes stale-reader state, file-lock lifetime and recovery coupling.

Issue #50 tracks whether a persistent `DirectoryReader` / `IndexWriter` lifecycle would provide enough measurable benefit to justify the additional state and recovery complexity.

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
