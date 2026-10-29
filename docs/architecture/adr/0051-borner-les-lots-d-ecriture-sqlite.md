# ADR-0051 — Borner les lots d'écriture SQLite

- Statut : Accepté
- Date : 2026-09-12
- Complète : ADR-0050

## Contexte

Les triggers FTS5 de V008 rendent le coût d'un `executeUpdate` par ligne visible
dans la courbe SQLite. Le run de CI de la PR #219 dépassait le plafond d'une
million de lignes malgré `temp_store=MEMORY`.

## Décision

Les écritures répétitives de `symbols` et `symbol_relations` passent par des
insertions SQL multi-lignes paramétrées, limitées à 128 lignes par instruction.
Le helper ne possède pas la transaction : le code appelant garde la maîtrise des
commits, des rollbacks et de la visibilité de la génération FTS. Le protocole de
benchmark est incrémenté afin de séparer les mesures avant et après ce changement.

## Conséquences et validation

La limite de 128 borne le nombre de paramètres par instruction tout en réduisant
les allers-retours avec SQLite. Les valeurs restent liées par paramètres et le
SQL de structure est fermé par l'énumération des tables autorisées. Les courbes
locales passent d'environ 49,5 s à 35,6 s au palier d'un million ; les plafonds
absolus restent inchangés.
