# Index provenance and freshness

NEXUS maintains several representations of project knowledge. They do not have the same authority and must not be reused under the same freshness rules.

## Canonical project index

The SQLite file index is the canonical representation of the project state known to NEXUS.

Each indexed file contributes stable metadata to a deterministic SHA-256 canonical fingerprint:

- normalized relative path;
- content hash;
- detected language;
- file category.

The fingerprint deliberately uses canonical metadata already produced by the scanner/indexer. A derived index can therefore prove that it corresponds to the same canonical file state without trusting workspace timestamps.

`IndexRepository.generation(projectId)` remains the monotone cache-invalidation generation. The semantic search guard uses it only to cache the canonical fingerprint; the fingerprint itself is the compatibility proof.

## External code-intelligence snapshots

JDT LS, MINOS and imported code indexes are external snapshots. They are useful enrichments, but they are never more authoritative than the canonical file state.

When a canonical `SOURCE` or `TEST` file is added, modified or removed, NEXUS invalidates every persisted non-embedded code-intelligence provider that is currently present in SQLite. This includes snapshots produced by a provider that is no longer configured in the current runtime.

After invalidation:

- configured importers may refresh their snapshot from their external artifact;
- an explicitly requested deep-code provider may rebuild its snapshot;
- a previous JDT LS snapshot is not retained merely because JDT LS is unavailable now;
- a previous explicit MINOS import is not retained after canonical source/test changes and must be imported again before it can influence queries.

If no canonical source/test file changed, a current external snapshot may remain available.

The embedded analyzer (`CodeSymbol.DEFAULT_SOURCE_PROVIDER`) is part of canonical indexing and is never purged through the external-provider lifecycle.

## Semantic Lucene index

The semantic Lucene index is disposable derived state. Every provenance-aware commit records:

- canonical SHA-256 fingerprint;
- embedding provider identity;
- embedding model identity;
- vector dimensions;
- semantic content-preparation profile;
- semantic schema version.

The content-preparation profile includes the algorithm version and the parameters that change the embedded text representation (currently the maximum embedding input length and excerpt length).

### Reuse rule

A persisted semantic index is reusable only when all provenance fields exactly match the expected values for the current runtime and canonical project state.

A missing manifest is treated as incompatible. This intentionally makes semantic indexes created by older NEXUS versions self-healing: the next semantic indexing cycle rebuilds them.

### Rebuild rule

NEXUS performs a full semantic rebuild when any provenance field differs, including these cases:

- semantic indexing was disabled while canonical files changed, then enabled again;
- the embedding provider changes;
- the embedding model changes, even when vector dimensions are identical;
- vector dimensions change;
- the content preparation/chunking profile changes;
- the semantic schema version changes.

Otherwise the semantic index continues to use incremental updates and removals.

### Search-time guard

Search does not assume that a semantic indexing cycle has already repaired the derived index. Before creating a query embedding, the production semantic search strategy compares the persisted Lucene provenance with the expected provenance derived from SQLite and the active embedding configuration.

If the index is stale or incompatible, semantic search returns no semantic candidates for that strategy. Lexical/symbolic strategies remain available, and no query embedding is generated against the incompatible vector space.

## Recovery properties

These rules preserve the existing recovery model:

1. SQLite remains canonical.
2. External snapshots can be invalidated and refreshed without changing canonical file contents.
3. Lucene semantic state can always be discarded/rebuilt.
4. A failed indexing operation leaves the project non-`READY`; the next indexing operation performs the existing full-rebuild recovery path.

The design therefore favors a temporary loss of enrichment over silently serving intelligence whose provenance cannot be proven.
