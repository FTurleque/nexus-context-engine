# SCIP import filesystem security

SCIP data is treated as untrusted metadata. A path declared by `index.scip` never grants NEXUS permission to read outside the registered project.

## Canonical project boundary

`ScipCodeIndexImporter` creates a `ProjectPathGuard` from the registered project root before opening the index. The guard resolves the project root once to its canonical directory and then rejects symbolic-link components beneath that trusted root.

The `index.scip` file itself must be a regular file reachable without a symbolic link below the project root. An unsafe index is rejected; it is not treated as if no SCIP index were present.

## Source rereads

Each SCIP document path is resolved as a project-relative path through the same `ProjectPathGuard`. Before NEXUS validates symbol line ranges, the referenced source must still exist as a regular file and must not traverse a final or ancestor symbolic link.

A missing or unsafe source is a failed import. NEXUS does not skip canonical line-count validation when the source disappeared after the SCIP index was produced, because doing so could publish symbol ranges that were never checked against the current project corpus.

## Path traversal

Absolute paths and relative traversal that escapes the canonical project root are rejected before filesystem access. Paths that remain inside the root are emitted in normalized repository form using `/` separators.

## Why this matches MINOS

MINOS and SCIP now share the same filesystem trust model: external code-intelligence metadata may describe project files, but only `ProjectPathGuard` decides whether the corresponding on-disk path is eligible for a source reread. This keeps the importer contract independent of platform symlink behavior and prevents a repository entry from redirecting validation to an arbitrary host file.
