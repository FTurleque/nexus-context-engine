# Native context discovery limits

NEXUS applies one cumulative work budget to native context discovery **before** token selection. The same budget instance is shared by instruction providers, Agent Skills discovery/loading, local Git context and customization detection.

## Defaults

| Dimension | Environment variable | Default | Hard maximum |
|---|---|---:|---:|
| Visited entries | `NEXUS_CONTEXT_DISCOVERY_MAX_VISITED_ENTRIES` | 100000 | 1000000 |
| Candidate resources | `NEXUS_CONTEXT_DISCOVERY_MAX_CANDIDATES` | 5000 | 100000 |
| Cumulative bytes | `NEXUS_CONTEXT_DISCOVERY_MAX_BYTES` | 33554432 | 536870912 |
| Elapsed milliseconds | `NEXUS_CONTEXT_DISCOVERY_MAX_MILLIS` | 15000 | 120000 |

Values must be strictly positive and within the hard maximum. Invalid configuration is fail-closed when a context build starts.

## Accounting

The budget is charged before expensive reads or materialization whenever possible:

- filesystem entries and Git history entries consume visited-entry budget;
- selected metadata/resources consume candidate budget;
- instruction bodies, selected skill bodies and rendered Git diffs consume byte budget;
- every provider shares the same deadline.

Git also has local structural caps for recent commits, changed paths and fixed-capacity diff rendering. The visible 6000-character diff limit is therefore no longer implemented by first allocating an unbounded complete patch.

When a limit is exceeded, context construction fails instead of silently returning a partially discovered native configuration. This makes resource exhaustion explicit and deterministic.
