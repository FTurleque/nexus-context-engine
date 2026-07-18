# MVP definition

## Objective

From a local Java repository and a natural-language request, NEXUS identifies and ranks the files and symbols most likely to be useful, then builds a `ContextBundle` that respects a configurable token budget.

## In scope

- register and list local projects;
- scan a local repository with ignore rules;
- detect Java source files;
- parse Java through an AST-based `LanguageAnalyzer`;
- index files, symbols and basic relations;
- persist the local index;
- lexical and symbol-aware context search;
- deterministic explainable ranking;
- configurable token budget;
- context selection using excerpts and relevant symbols;
- deduplication;
- human-readable and machine-readable output;
- CLI commands needed to exercise the complete vertical slice;
- automated quality corpus and reproducibility tests.

## Explicitly out of scope

- mandatory LLM calls;
- mandatory external embeddings;
- vector database;
- GitHub and GitLab repository sources;
- IDE plugins;
- complete MCP server;
- AI Skills Registry integration;
- JARVIS, Alfred or Brainiac integration;
- automatic model routing;
- multi-language parity.

## MVP command surface

Target commands:

```bash
nexus project add ./my-project
nexus project list
nexus index my-project
nexus search my-project "document upload"
nexus context my-project "Fix PDF upload handling" --budget 20000 --explain
nexus inspect my-project
```

The CLI is an adapter over application services. No context selection logic belongs in CLI handlers.

## Acceptance criteria

The MVP is valid when all of the following are reproducibly demonstrated on test corpora and at least one representative Java repository:

1. A local project can be registered by path and reloaded in a later process.
2. Indexing respects `.gitignore`, `.nexusignore` and built-in secret/generated-content exclusions.
3. Java classes, interfaces, records, enums, methods and imports are structurally extracted without regex-only parsing.
4. Re-running indexing without source changes produces the same searchable index and results.
5. A natural-language query returns ranked files and symbols with deterministic scores.
6. Each selected candidate can expose the score factors that caused its selection.
7. `ContextBuilder` never exceeds the configured token budget according to the active `TokenEstimator`.
8. Relevant symbol excerpts are preferred over full files when they provide sufficient context.
9. Duplicated or overlapping excerpts are removed or merged.
10. `--explain` reports selected and materially excluded candidates with reasons.
11. Golden-query tests measure expected relevant and irrelevant results.
12. The full MVP works offline after dependencies have been resolved.

## Initial quality metrics

At minimum, capture:

- precision@K on golden queries;
- recall@K on golden queries;
- context reduction ratio;
- estimated token savings;
- indexing time;
- search latency;
- context build latency.

These are measurements, not marketing claims. Baselines must be stored with the corpus and compared over time.
