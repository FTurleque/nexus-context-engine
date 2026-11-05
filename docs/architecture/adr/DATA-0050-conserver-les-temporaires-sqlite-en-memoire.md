---
id: DATA-0050
title: "Conserver les temporaires SQLite en mémoire"
domain: data
rules: true
files: ["**/src/main/java/**", "core/src/main/resources/db/migration/**"]
---

# Conserver les temporaires SQLite en mémoire

- **Statut** : Accepted
- **Date** : 2026-09-12
- **Complète** : ADR-0024

## Context

Le suivi transactionnel de la projection FTS5 introduit des triggers sur les
insertions canoniques. Le run Scale Benchmark `34534070804` de la PR #219 mesure
7 315 ms pour charger 100 000 symboles et autant de relations, au-delà du plafond
de 5 000 ms. Une sonde locale avec le même pilote SQLite isole le surcoût dans
les insertions avec triggers sur disque ; augmenter le cache de pages ne le
réduit pas. `temp_store=MEMORY` le réduit sans retirer les triggers.

## Decision

Chaque connexion créée par `SqliteDatabase` configure `PRAGMA temp_store=MEMORY`
avant toute transaction. Les structures temporaires SQL utilisent ainsi la
mémoire native SQLite. Le fichier canonique, son journal de transaction, les
clés étrangères et le réglage `synchronous` conservent leurs garanties.
Les migrations déjà checksumées et les seuils de CI ne sont pas modifiés.

## Do's and Don'ts

### Do

- Configurer `PRAGMA temp_store=MEMORY` sur chaque connexion créée par `SqliteDatabase`, avant toute transaction.

### Don't

- Ne pas modifier pour ce réglage les migrations déjà checksumées ni les seuils de CI.
- Ne pas changer les garanties du fichier canonique, de son journal de transaction, des clés étrangères ni du réglage `synchronous`.

## Conséquences et validation

Les opérations SQL temporaires consomment davantage de mémoire native, hors heap
Java, et ne disposent plus du repli disque de `temp_store=FILE`. Leur consommation
dépend des requêtes et des transactions ; le gate de heap Java ne constitue pas
une mesure de cette mémoire native. Toute extension majeure de volumétrie doit
donc qualifier également la mémoire du processus.

Le benchmark existant conserve les courbes jusqu'à un million de symboles et
relations, les budgets de latence, de chargement et de stockage. Un test de
rollback vérifie ensemble les valeurs canoniques, la file de changements et la
projection FTS après réouverture d'une connexion.

Référence : [fichiers temporaires SQLite](https://sqlite.org/tempfiles.html),
notamment la distinction entre stockage temporaire SQL et journal de transaction.

## Compliance and Enforcement

- Règle Archgate `DATA-0050-conserver-les-temporaires-sqlite-en-memoire.rules.ts` / `sqlite-connections-use-memory-temp-store` : seul `SqliteDatabase.java` ouvre une connexion JDBC (`DriverManager.getConnection`, `jdbc:sqlite:`), chaque ouverture y est suivie de `PRAGMA temp_store = MEMORY` avant le `return connection;`, et aucune source Java ni migration SQL ne fixe `temp_store` à une autre valeur.
- Tests `SqliteIndexedSubstringSearchTest.rollbackRestoresCanonicalRowsAndFlushedProjection` (`core/src/test/java/com/nexus/persistence/sqlite/`, valeurs canoniques, file de changements et projection FTS après réouverture) et `SqliteDatabaseHardeningTest` (clés étrangères), lancés par `mvnw clean install` (workflow `.github/workflows/ci.yml` : job Linux reactor, et job Windows gate par `scripts/validate-phase-6.ps1`).
- Workflow `.github/workflows/scale-benchmark.yml` (`ScaleRegressionBenchmarkTest`) : budgets de chargement, de latence et de stockage.
- Hook `.claude/hooks/guard-edit.ps1` : refuse la modification d'une migration publiée.
