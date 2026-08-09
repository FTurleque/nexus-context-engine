# SQLite multi-project write contention

## Threat model

NEXUS stores all registered projects in one SQLite database under `NEXUS_HOME`. The indexing single-flight contract is intentionally **per project**: a JVM mutex and an OS `FileLock` prevent two active index mutations of the same project, but different projects use different locks and may legitimately reach SQLite at the same time.

SQLite still permits only one effective writer at a time. WAL does not remove that writer serialization; it primarily improves reader/writer coexistence. NXA-09 therefore keeps the current journal-mode policy and adds bounded recovery for transient writer contention instead of introducing a JVM-global lock or changing storage architecture.

## Pre-fix reproduction

Before the remediation, a hermetic integration test held a real write transaction for project A and attempted a repository write for project B against the same database. With the production `PRAGMA busy_timeout = 5000`, the second writer failed with the driver's official result code:

```text
SQLITE_BUSY after 5012 ms
```

The timeout was bounded, but there was no application-level recovery after that first wait expired.

## Central transaction policy

`SqliteDatabase.writeTransaction(...)` is now the single transaction-retry boundary used by:

- `SqliteProjectRepository.save`;
- `SqliteIndexRepository.applyChanges`;
- `SqliteIndexRepository.replaceExternalCodeIntelligence`.

Bootstrap/migrations use the same `SqliteWriteRetryPolicy`, while `SchemaMigrator` retains its own transaction rollback semantics.

Each retry is a **complete transaction attempt**:

1. open a fresh SQLite connection;
2. start a transaction;
3. run the whole mutation;
4. commit on success;
5. on failure, rollback before returning the error to the retry policy;
6. close the connection;
7. only then, if the error is recognized as transient contention, back off and retry from the beginning.

A transaction that has already committed is never replayed merely because closing the JDBC connection later fails.

## Retryable errors

Retry classification uses the Xerial driver's official `SQLiteException.getResultCode()` values. Only:

- `SQLITE_BUSY`;
- `SQLITE_LOCKED`

are retryable.

Constraint violations, SQL errors, schema errors, corruption, I/O failures, programming errors and arbitrary `SQLException` messages are not retried. No substring matching of exception text is used.

## Bounded timing

Production defaults remain:

```text
busy_timeout = 5000 ms
maxAttempts = 2
backoff = 50 ms
```

The worst-case contention wait attributable to this policy is therefore approximately:

```text
2 × 5000 ms + 50 ms = 10050 ms
```

There is no infinite retry loop. Internal/package-private constructor seams allow tests to use busy timeouts and backoffs measured in a few milliseconds without weakening production defaults.

## Exactly-once and generation semantics

A failed attempt is rolled back before a retry. Tests deliberately insert a row and then raise a synthetic official `SQLITE_BUSY`; the second attempt can insert the same primary key successfully only if the first attempt was fully rolled back.

For index mutations the whole `applyChanges` transaction, including `project_index_generations`, is replayed atomically. A recovered transient contention therefore produces one committed file state and exactly one generation increment.

External code-intelligence replacement follows the same rule. Importing an identical external snapshot remains a true no-op and does not increment generation.

## Multi-process behavior

The retry mechanism is database-level and connection-level; it does not depend on a JVM-global mutex. Independent NEXUS processes sharing the same local `NEXUS_HOME` therefore see the same SQLite lock protocol and can recover from short `BUSY`/`LOCKED` intervals.

The existing per-project `FileLock` is retained. Distinct project locks can still be held concurrently; SQLite serialization is handled separately by the bounded transaction policy.

The qualified support boundary for `FileLock` remains a local filesystem. Network filesystems require separate qualification.

## Concurrent reads

The remediation does not serialize readers behind an application-wide lock. Integration coverage holds a real SQLite writer while reading a different project and requires the read to succeed.

The existing scale benchmark continues to exercise concurrent read/write behavior under DELETE and WAL journal modes and requires zero reader failures.

## Bootstrap

Two independent `SqliteDatabase` instances may start against the same fresh database. Migration/bootstrap attempts use the same bounded contention policy, with rollback/connection close before retry. Concurrent bootstrap is covered hermetically with independent connections and the final migration table must contain the expected migration set exactly once.

## Qualification expectations

NXA-09 qualification includes:

- real cross-project writer contention and recovery;
- bounded failure when contention outlives the policy;
- non-BUSY no-retry behavior;
- rollback-before-retry and exactly-once persistence;
- generation correctness;
- external refresh no-op preservation;
- concurrent reads;
- per-project OS `FileLock` compatibility;
- concurrent bootstrap;
- complete SQLite/index/provenance/core tests;
- scale/concurrent read-write benchmark;
- Linux and Windows repository gates;
- `./mvnw -B clean install`.
