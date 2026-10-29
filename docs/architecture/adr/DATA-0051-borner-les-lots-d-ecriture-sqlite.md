---
id: DATA-0051
title: "Borner les lots d'écriture SQLite"
domain: data
rules: true
files: ["**/src/main/java/**"]
---

# Borner les lots d'écriture SQLite

- **Statut** : Accepted
- **Date** : 2026-09-12
- **Complète** : ADR-0050

## Context

Les triggers FTS5 de V008 rendent le coût d'un `executeUpdate` par ligne visible
dans la courbe SQLite. Le run de CI de la PR #219 dépassait le plafond d'une
million de lignes malgré `temp_store=MEMORY`.

## Decision

Les écritures répétitives de `symbols` et `symbol_relations` passent par des
insertions SQL multi-lignes paramétrées, limitées à 128 lignes par instruction.
Le helper ne possède pas la transaction : le code appelant garde la maîtrise des
commits, des rollbacks et de la visibilité de la génération FTS. Le protocole de
benchmark est incrémenté afin de séparer les mesures avant et après ce changement.

## Do's and Don'ts

### Do

- Écrire les lignes répétitives de `symbols` et `symbol_relations` par des insertions SQL multi-lignes paramétrées, au plus 128 lignes par instruction.
- Laisser au code appelant les commits, les rollbacks et la visibilité de la génération FTS.

### Don't

- Ne pas faire posséder la transaction par le helper d'insertion.
- Ne pas dépasser 128 lignes par instruction.

## Conséquences et validation

La limite de 128 borne le nombre de paramètres par instruction tout en réduisant
les allers-retours avec SQLite. Les valeurs restent liées par paramètres et le
SQL de structure est fermé par l'énumération des tables autorisées. Les courbes
locales passent d'environ 49,5 s à 35,6 s au palier d'un million ; les plafonds
absolus restent inchangés.

## Compliance and Enforcement

- Règle Archgate `DATA-0051-borner-les-lots-d-ecriture-sqlite.rules.ts` / `sqlite-batch-insert-bounded-to-128` : `SqliteBatchInsert` déclare `ROW_LIMIT = 128`.
- Règle Archgate `DATA-0051-borner-les-lots-d-ecriture-sqlite.rules.ts` / `symbol-inserts-go-through-batch-insert` : aucun `INSERT INTO symbols` ni `INSERT INTO symbol_relations` dans les sources de production hors de `SqliteBatchInsert`.
- Règle Archgate `DATA-0051-borner-les-lots-d-ecriture-sqlite.rules.ts` / `batch-insert-owns-no-transaction` : `SqliteBatchInsert` n'appelle ni `commit`, ni `rollback`, ni `setAutoCommit`, ni savepoint, et n'exécute aucune instruction de transaction.
- Test `SqliteBatchInsertTest` (`core/src/test/java/com/nexus/persistence/sqlite/`, lots pleins et reliquat avec valeurs liées), lancés par `mvnw clean install` (workflow `.github/workflows/ci.yml` : job Linux reactor, et job Windows gate par `scripts/validate-phase-6.ps1`).
- Workflow `.github/workflows/scale-benchmark.yml` (`ScaleRegressionBenchmarkTest`) : plafonds de la courbe SQLite.
