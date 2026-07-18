# Incremental roadmap

## Iteration 0 — Architecture baseline

Status: started.

Deliverables:

- project mission and MVP boundary;
- architecture decisions;
- Maven/Java baseline;
- core contracts;
- first Java AST analyzer;
- first analyzer test.

Exit criterion: the repository builds and the Java analyzer contract is testable.

## Iteration 1 — Local project indexing

Deliverables:

- local project registry;
- filesystem scanner;
- `.gitignore` and `.nexusignore` resolution;
- secret/generated-content exclusions;
- incremental file hashing;
- SQLite persistence abstraction;
- Java files and symbols persisted;
- indexing CLI entry point.

Exit criterion: a local Java repository can be registered, indexed and inspected offline.

## Iteration 2 — Retrieval and ranking

Deliverables:

- lexical search;
- exact and fuzzy symbol search;
- basic file/symbol relations;
- deterministic score model;
- score breakdown and explanations;
- golden-query corpus.

Exit criterion: queries reproducibly rank expected files and symbols above known irrelevant files.

## Iteration 3 — Context Builder and budget

Deliverables:

- `ContextBuilder` implementation;
- local default `TokenEstimator`;
- excerpt selection;
- deduplication/overlap merging;
- configurable token budget;
- explainable exclusions and truncation.

Exit criterion: generated bundles stay within budget and preserve expected relevant context.

## Iteration 4 — Usable MVP CLI

Deliverables:

- `project add/list`;
- `index`;
- `search`;
- `context`;
- `inspect`;
- JSON and human-readable output;
- end-to-end corpus tests;
- baseline performance and context-quality metrics.

Exit criterion: the complete MVP objective is demonstrable from the command line.

## Iteration 5 — API adapter

Candidate stack: Quarkus LTS.

Deliverables:

- REST application adapter;
- request/response DTOs isolated from core models;
- health/observability endpoints;
- no business logic in REST resources.

## Iteration 6 — Enrichment

Possible additions, validated independently:

- Git context;
- instruction resolver;
- richer dependency graph;
- optional semantic search provider;
- additional languages through `LanguageAnalyzer` implementations.

## Iteration 7 — Integrations

Only after the engine quality is proven:

- MCP adapter;
- IDE integrations;
- GitHub/GitLab project sources;
- AI Skills Registry connector;
- JARVIS/Alfred/Brainiac consumers.
