---
paths:
  - "core/src/main/java/com/nexus/persistence/**"
  - "core/src/main/resources/db/migration/**"
  - "core/src/test/java/com/nexus/persistence/**"
---

# Persistance SQLite

- SQLite est la **source canonique** ; Lucene (lexical et sémantique) et les snapshots externes sont des dérivés reconstructibles. Ne jamais lire un dérivé comme vérité.
- Les migrations `db/migration/Vnnn__*.sql` sont **append-only** : ne jamais modifier une migration existante, créer `V(n+1)`. Une invalidation d'index (redaction, identité de chemin, plages) se fait par une migration dédiée qui force le rebuild (cf. V006, V007, V009, V010). Procédure complète : skill `nexus-schema-migration`.
- Requêtes : uniquement `PreparedStatement` paramétré. Les listes passent par `json_each(?)` plutôt que par une concaténation `IN (...)`.
- Écritures répétitives : lots multi-lignes bornés à 128 lignes par instruction (ADR-0051) ; le helper ne possède pas la transaction.
- Les requêtes du graphe vivent dans `SqliteGraphQueries` ; toute requête sensible au plan doit avoir un test `EXPLAIN QUERY PLAN` (voir `SqliteGraphQueryPlanTest`) qui vérifie l'index utilisé.
- Lecture fédérée : grouper les lectures du registre (une requête pour N projets) au lieu de N lectures unitaires.
- Les invariants de domaine importants sont aussi imposés en SQL (`CHECK`, ex. V005 sur les plages de symboles).
- `NEXUS_HOME` et la base sont privés (0700/0600 sur POSIX, ACL inspectées sous Windows) : ne pas créer de fichier persistant hors des helpers de `NexusPaths`.
