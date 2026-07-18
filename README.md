# NEXUS Context Engine

> A local-first, model-agnostic Context Intelligence Engine for software projects.

NEXUS builds a minimal, relevant, explainable and traceable context for AI assistants and agents. It is not a chatbot and it does not route requests to a specific model.

## Mission

Given a local software repository and a natural-language request, NEXUS should identify and rank the files and symbols most likely to be useful, then build a context bundle that respects a configurable token budget.

```text
User / Agent / IDE
        |
        v
      Request
        |
        v
      NEXUS
   index + search
   rank + explain
   budget + build
        |
        v
  Context Bundle
        |
        v
   LLM / AI Agent
```

## MVP scope

The MVP is deliberately narrow:

- local repositories only;
- Java first;
- AST-based structural indexing;
- lexical and symbol-aware retrieval;
- explainable deterministic ranking;
- configurable token budget;
- file and symbol excerpts rather than indiscriminate full-file injection;
- local-only operation by default;
- no mandatory LLM or embedding provider.

Explicitly deferred: GitHub/GitLab sources, IDE integrations, full MCP server, external embeddings, vector databases, JARVIS, Alfred, Brainiac and AI Skills Registry integration.

## Architecture direction

The initial repository is a single Maven module organized by capability. Module boundaries will be extracted only when runtime or dependency isolation justifies them.

Core extension points include:

- `LanguageAnalyzer`
- `SearchStrategy`
- `ContextRanker`
- `TokenEstimator`
- `ContextBuilder`

See [docs/architecture.md](docs/architecture.md), [docs/mvp.md](docs/mvp.md) and [docs/roadmap.md](docs/roadmap.md).

## Technology baseline

- Java 25
- Maven
- JavaParser for the first Java AST analyzer
- JUnit for automated tests

Quarkus is intentionally deferred to the API/application boundary. The context engine itself must remain usable as a plain Java library.

## Status

**Iteration 0 — architecture baseline and Java indexing contracts.**

The repository currently contains only the minimum foundation needed to begin the first vertical slice.

## Security defaults

NEXUS is local-first. No repository content should leave the machine unless an external integration is explicitly enabled. A project-level `.nexusignore` format is planned to complement `.gitignore`-style exclusions for secrets and generated content.

## License

License selection is intentionally pending before the repository is made public.
