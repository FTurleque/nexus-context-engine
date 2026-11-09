---
id: DATA-0088
title: "Conserver le journal rollback SQLite et ne réévaluer WAL que sur mesure répétée"
domain: data
rules: false
---

# Conserver le journal rollback SQLite et ne réévaluer WAL que sur mesure répétée

- **Statut** : Proposed
- **Date** : 2026-11-09
- **Origine** : audit du 2026-10-17, constat `AUD-PRF-10` ; changement OpenSpec `sprint-11-finitions-et-micro-optimisations` (D3)
- **Complète** : [DATA-0074](DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md), qui reprend la cohérence des lectures et la transaction unique de publication de [DATA-0052](archive/DATA-0052-garantir-la-coherence-des-lectures-et-des-index-derives.md)

## Context

`SqliteDatabase` ouvre chaque connexion avec `foreign_keys = ON`, `temp_store = MEMORY` et un cache de pages fixé (`core/src/main/java/com/nexus/persistence/sqlite/SqliteDatabase.java`, méthode `newConnection`). Aucun `PRAGMA journal_mode` n'y est émis : la base canonique reste dans le mode par défaut de SQLite, le journal rollback (`delete`). Dans le code de production, seul le benchmark de scale bascule une base en WAL (`ScaleRegressionBenchmarkTest`).

SQLite n'admet qu'un écrivain effectif à la fois, quel que soit le journal. WAL améliore surtout la coexistence lecteurs/écrivain ; il ne retire pas la sérialisation des écritures, que [DATA-0074](DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md) traite par des transactions courtes, une transaction de publication unique et la politique de reprise de `SqliteWriteRetryPolicy` ([`sqlite-write-contention.md`](../../developer/sqlite-write-contention.md)).

Le benchmark de régression scale compare les deux modes sur deux projets de 100 000 symboles : 40 transactions d'écriture sur l'un pendant des recherches worst-miss sur l'autre ([`scale-regression-results.md`](../../project/history/scale-regression-results.md), section 3, head `f4dd558b`, deux exécutions Linux sur runner GitHub) :

| Mesure | Run 1 DELETE | Run 1 WAL | Run 2 DELETE | Run 2 WAL |
|---|---:|---:|---:|---:|
| Reader p95 | 104,4 ms | 72,6 ms | 73,6 ms | 73,4 ms |
| Writer total | 225 ms | 39 ms | 139 ms | 36 ms |
| Erreurs reader | 0 | 0 | 0 | 0 |

Le gain reader p95 est de 30,4 % au premier run et de 0,3 % au second ; le gain writer est net dans les deux. La règle d'adoption fixée avant la mesure ([`scale-regression-benchmark.md`](../../developer/scale-regression-benchmark.md), « Autres scénarios scale ») demandait un gain reader p95 répété d'au moins 25 % sans régression writer ni recovery.

Question : quel mode de journal la base canonique utilise-t-elle, et à quelle condition ce choix peut-il changer ?

## Options envisagées

- **Journal rollback (`delete`)** : mode par défaut, un seul fichier persistant à côté de la base (plus le journal transitoire), aucun fichier `-wal`/`-shm` à gérer dans `NEXUS_HOME` ni dans les sauvegardes ; lecteurs bloqués pendant le commit d'un écrivain. Le gain lecteur attendu de WAL n'est pas reproduit par la mesure.
- **WAL** : écrivain plus rapide et lecteurs non bloqués pendant les écritures ; fichiers `-wal` et `-shm` persistants à protéger comme la base (permissions 0600/ACL), checkpoints à surveiller, mode persistant inscrit dans le fichier, incompatible avec les filesystems réseau que [SEC-0082](SEC-0082-borner-le-support-filesystem-et-la-traversee-des-chemins.md) ne qualifie pas. Le bénéfice ciblé (lecture concurrente) n'atteint pas le seuil de façon répétée.

## Decision

**Option retenue : journal rollback (`delete`).**

Toute base ouverte par `SqliteDatabase` est en mode de journal `delete`. Le code de production n'émet aucun `PRAGMA journal_mode` qui change ce mode, ni dans `SqliteDatabase`, ni dans les migrations, ni dans un helper de connexion. Le benchmark de scale conserve la comparaison DELETE/WAL sur ses propres bases.

WAL n'est réévalué que si les deux conditions suivantes sont réunies :

1. une charge réelle ou un scénario reproductible montre une contention lecture/écriture qui pénalise l'utilisateur ;
2. des exécutions répétées du benchmark de scale, sur protocole identique, montrent un gain reader p95 d'au moins 25 % à chaque exécution, sans régression writer ni recovery.

Cette décision ne change ni `synchronous`, ni la transaction de publication de DATA-0074, ni la politique de reprise des écritures.

## Do's and Don'ts

### Do

- Laisser chaque connexion ouverte par `SqliteDatabase` en journal `delete`.
- Réévaluer WAL par le benchmark de scale répété, selon la règle de 25 % de gain reader p95 à chaque exécution, sans régression writer ni recovery.
- Tenir la comparaison DELETE/WAL dans le seul benchmark de scale.

### Don't

- Ne pas activer WAL, `truncate`, `persist`, `memory` ou `off` sur la base canonique sans un ADR qui remplace celui-ci.
- Ne pas adopter WAL sur la foi d'une exécution unique ou du seul gain writer.

## Consequences

### Positives

- Un seul fichier persistant par base dans `NEXUS_HOME` : permissions privées, sauvegarde et suppression restent simples.
- Comportement identique sur tous les filesystems locaux qualifiés par SEC-0082.
- Le mode est vérifié en CI : un changement silencieux échoue et renvoie à cet ADR.

### Négatives

- L'écrivain est plus lent qu'en WAL (225/139 ms contre 39/36 ms sur 40 transactions dans la mesure citée).
- Les lecteurs attendent le commit d'un écrivain concurrent ; l'effet sur la recherche n'est pas reproduit de façon stable.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Une charge réelle multi-processus révèle une contention lecteur/écrivain | Moyen | Condition de réévaluation ci-dessus ; benchmark de scale conservé |
| Un changement de mode par une dépendance ou un helper | Faible | Test `SqliteDatabaseHardeningTest.keepsTheRollbackJournalOnEveryConnection` |

## Compliance and Enforcement

- Contrôle automatique : `SqliteDatabaseHardeningTest.keepsTheRollbackJournalOnEveryConnection` lit `PRAGMA journal_mode` sur une connexion de `SqliteDatabase` et attend `delete`, avec un message qui cite DATA-0088.
- Contrôle automatique : le benchmark de scale (`ScaleRegressionBenchmarkTest`, workflow dédié) mesure DELETE et WAL et exige zéro erreur lecteur.
- Revue : toute PR qui ajoute un `PRAGMA journal_mode` au code de production cite un ADR qui remplace celui-ci et les mesures répétées exigées.

## Conditions de réexamen

La décision est réévaluée si : les deux conditions de la section Decision sont réunies ; SQLite permet plusieurs écrivains ; NEXUS qualifie un filesystem où le journal rollback pose un problème mesuré ; la politique `synchronous` change. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Mise en œuvre

Aucun code de production n'est modifié : le mode `delete` est celui de SQLite par défaut. Le test `keepsTheRollbackJournalOnEveryConnection` le fixe.

## References

- [DATA-0074](DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md) — cycle de vie et cohérence des index dérivés, transaction de publication.
- [DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md) — SQLite source de vérité.
- [SEC-0082](SEC-0082-borner-le-support-filesystem-et-la-traversee-des-chemins.md) — filesystems supportés.
- [`scale-regression-results.md`](../../project/history/scale-regression-results.md) — mesures DELETE/WAL.
- [`scale-regression-benchmark.md`](../../developer/scale-regression-benchmark.md) — protocole et règle d'adoption.
- [`sqlite-write-contention.md`](../../developer/sqlite-write-contention.md) — contention d'écriture et réglages de connexion.
- `openspec/changes/sprint-11-finitions-et-micro-optimisations/` — décision D3, tâches 4.1 et 4.2.
