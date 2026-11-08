---
id: DATA-0069
title: "Dimensionner le cache de pages SQLite des connexions"
domain: data
rules: false
---

# Dimensionner le cache de pages SQLite des connexions

- **Statut** : Proposed
- **Date** : 2026-11-08
- **Origine** : PR de promotion #306, check « Hermetic scale benchmark »
- **Complète** : DATA-0050

## Context

`SqliteDatabase` configure chaque connexion avec `foreign_keys`, `temp_store=MEMORY`
(DATA-0050) et le délai d'attente ; le cache de pages garde la valeur par défaut de
SQLite, 2 MiB.

Trois index des tables canoniques commencent par `file_id` : `idx_symbols_file_provider`
(V005), `idx_symbols_file_fuzzy_prefilter` (V015) et `idx_symbol_relations_file_provider`
(V012). Une indexation qui écrit les lignes de nombreux fichiers dans une même
transaction y insère des clés hors de leur ordre. Au-delà de quelques centaines de
milliers de lignes, ces index dépassent 2 MiB : chaque insertion relit alors des pages
depuis le disque.

Le run `42440042849` du benchmark de la PR #306 mesure 32 856 ms pour peupler un
million de symboles et autant de relations, contre 27 236 ms pour la base mesurée sur
le même runner ; le plafond relatif est de 32 683 ms. Une sonde locale du 2026-11-08,
avec le pilote embarqué, la même charge et le journal `DELETE`, isole le coût :

| Variante (1M symboles et relations) | Peuplement total |
|---|---|
| Configuration courante | 42 193 à 51 820 ms |
| Sans `idx_symbol_relations_file_provider` | 33 871 à 39 936 ms |
| Sans `idx_symbols_file_fuzzy_prefilter` | 36 485 à 42 443 ms |
| `cache_size` de 32 MiB | 38 493 à 39 834 ms |
| `cache_size` de 64 MiB | 32 212 à 35 239 ms |
| `cache_size` de 128 MiB | 35 437 à 37 318 ms |

Les deux index servent des requêtes mesurées (suppression par fichier, préfiltre
approché scopé au projet) et ne peuvent pas être retirés. Les migrations publiées ne
sont pas modifiables (DATA-0020).

## Options envisagées

- **Option A — Cache de 64 MiB sur chaque connexion** : les pages des index restent en
  mémoire pendant l'indexation ; gain d'environ 25 % à un million de lignes. La mémoire
  native par connexion peut atteindre 64 MiB.
- **Option B — Cache agrandi seulement pour les sessions d'écriture** : borne la mémoire
  des lecteurs, mais une écriture ouverte par `openConnection()` hors session n'en
  profite pas, et deux réglages coexistent selon le chemin d'accès.
- **Option C — Retirer ou fusionner des index** : supprime le coût à la source, mais
  dégrade la suppression par fichier (V012) ou le préfiltre scopé (V015), dont les plans
  sont vérifiés par des tests.
- **Option D — Accepter le surcoût** : laisse le peuplement à la limite du plafond de
  régression de +20 %, où le bruit de mesure décide du résultat.

## Decision

**Option retenue : Option A.**

Chaque connexion créée par `SqliteDatabase` configure `PRAGMA cache_size = -65536`
(64 MiB) avant d'être rendue, dans le même bloc que `temp_store=MEMORY`. SQLite alloue
les pages à l'usage : une connexion qui ne lit que quelques pages n'occupe pas le
plafond. Le schéma, les migrations, le journal, `synchronous` et les seuils du
benchmark ne changent pas.

La valeur de 64 MiB est la plus petite des valeurs mesurées qui supprime la régression ;
128 MiB n'apporte rien de plus.

## Do's and Don'ts

### Do

- Fixer `PRAGMA cache_size` à `-SqliteDatabase.PAGE_CACHE_KIB` sur chaque connexion créée par `SqliteDatabase`.
- Mesurer de nouveau le peuplement à un million de lignes avant de modifier la valeur.

### Don't

- Ne pas configurer un autre cache de pages ailleurs que dans `SqliteDatabase`.
- Ne pas modifier pour ce réglage les migrations publiées, les seuils du benchmark ni les garanties du journal et de `synchronous`.

## Consequences

### Positives

- Le peuplement d'un million de symboles et relations repasse sous la base de `main`
  dans la sonde locale, avec de la marge sous le plafond relatif.
- Les grandes indexations réelles qui écrivent beaucoup de fichiers par transaction
  profitent du même gain.

### Négatives

- La mémoire native de chaque connexion peut atteindre 64 MiB, hors heap Java et hors
  gate de heap, en plus des temporaires de DATA-0050.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Nombreuses connexions simultanées dans un runtime longue durée | Moyen | Les lectures sont bornées (`LIMIT`) et touchent peu de pages ; une extension de volumétrie qualifie la mémoire du processus, comme le demande DATA-0050. |
| Valeur modifiée sans mesure | Faible | `SqliteDatabaseHardeningTest` échoue et renvoie à cet ADR. |

## Compliance and Enforcement

- Test `SqliteDatabaseHardeningTest.sizesThePageCacheOfEveryConnection` (`core/src/test/java/com/nexus/persistence/sqlite/`) : `PRAGMA cache_size` vaut `-SqliteDatabase.PAGE_CACHE_KIB` sur une connexion ouverte par `SqliteDatabase`, lancé par `mvnw clean install` (workflow `.github/workflows/ci.yml`).
- Règle Archgate de DATA-0050 (`sqlite-connections-use-memory-temp-store`) : seul `SqliteDatabase.java` ouvre une connexion JDBC, donc aucune connexion n'échappe à ce réglage.
- Workflow `.github/workflows/scale-benchmark.yml` (`ScaleRegressionBenchmarkTest`) : plafond relatif du peuplement contre la base mesurée sur le même runner.
