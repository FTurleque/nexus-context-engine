# ADR-0050 — Conserver les temporaires SQLite en mémoire

- Statut : Accepté
- Date : 2026-09-12
- Complète : ADR-0024

## Contexte

Le suivi transactionnel de la projection FTS5 introduit des triggers sur les
insertions canoniques. Le run Scale Benchmark `34534070804` de la PR #219 mesure
7 315 ms pour charger 100 000 symboles et autant de relations, au-delà du plafond
de 5 000 ms. Une sonde locale avec le même pilote SQLite isole le surcoût dans
les insertions avec triggers sur disque ; augmenter le cache de pages ne le
réduit pas. `temp_store=MEMORY` le réduit sans retirer les triggers.

## Décision

Chaque connexion créée par `SqliteDatabase` configure `PRAGMA temp_store=MEMORY`
avant toute transaction. Les structures temporaires SQL utilisent ainsi la
mémoire native SQLite. Le fichier canonique, son journal de transaction, les
clés étrangères et le réglage `synchronous` conservent leurs garanties.
Les migrations déjà checksumées et les seuils de CI ne sont pas modifiés.

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
