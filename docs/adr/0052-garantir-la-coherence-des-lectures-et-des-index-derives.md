# ADR-0052 — Garantir la cohérence des lectures et des index dérivés

- Statut : Accepté
- Date : 2026-09-12
- Complète : ADR-0022 et ADR-0045

## Contexte

SQLite reste la source canonique et Lucene un dérivé reconstructible. Une lecture
qui vérifie `READY` puis ouvre Lucene sans coordination peut toutefois rencontrer
un rebuild en cours et observer un index partiellement remplacé. La perte ou la
corruption d'un dérivé pouvait aussi être confondue avec un index vide. Enfin, une
évolution de la redaction devait invalider les contenus dérivés déjà persistés.

## Décision

Les mutations et les lectures d'un projet partagent une identité de verrou par
chemin de lock. Les mutations prennent le verrou d'écriture inter-processus ; les
lectures prennent un verrou partagé inter-processus et le conservent pendant toute
la façade de recherche, de contexte ou d'inspection. Les lecteurs du même processus
mutualisent le `FileLock` partagé pour rester compatibles avec les sémantiques JVM.
Les lectures fédérées acquièrent plusieurs verrous dans l'ordre global des UUID,
afin d'éviter les cycles entre portées concurrentes qui se recouvrent.

Chaque commit Lucene porte la version de la politique de redaction. `isPresent`
vérifie l'existence, l'intégrité minimale et cette version avant qu'une indexation
incrémentale soit autorisée. Le profil sémantique inclut également la version de la
redaction ; un dérivé ancien ou absent force une reconstruction.

## Conséquences et validation

Une lecture publique ne traverse plus un remplacement de dérivé et une indexation
READY avec un dérivé absent ne peut plus être considérée comme valide. Les lectures
restent soumises au coût du verrou partagé et `READY` n'est pas présenté comme un
snapshot atomique du filesystem vivant.

Les tests couvrent la concurrence lecteurs/écrivain, la détection d'un index Lucene
absent, la compatibilité du commit, la reconstruction sémantique et la redaction
des clés JSON citées. La validation complète est réalisée par `mvn clean install`
et `scripts/self-smoke.ps1`.
