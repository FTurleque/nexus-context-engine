---
name: nexus-schema-migration
description: Ajouter une migration SQLite NEXUS (db/migration Vnnn) — évolution de schéma ou invalidation des index dérivés — avec test d'upgrade, checksum, documentation de recovery et ADR si le format persistant change. Utiliser pour toute modification du schéma SQLite ou quand un changement impose de reconstruire les index existants.
argument-hint: "<description courte>"
---

# Migration SQLite NEXUS

SQLite est canonique ; Lucene et le sémantique sont dérivés. Les migrations embarquées sont **append-only** et leur contenu est protégé par checksum (`SchemaMigratorChecksumTest`) : ne jamais modifier une migration publiée (le hook le refuse).

## Étapes

1. Numéro suivant : `ls core/src/main/resources/db/migration | sort | tail -1` → `V(n+1)__verbe_objet.sql` (snake_case anglais).
2. Choisir le type :
   - **Évolution de schéma** : DDL idempotent quand c'est possible (`IF NOT EXISTS`), contraintes `CHECK` pour les invariants de domaine (cf. V005), index justifiés par un plan de requête.
   - **Invalidation des dérivés** (redaction, identité de chemin, plages) : reprendre le motif de `V010__invalidate_scalar_secret_indexes.sql` — incrémenter `project_index_generations.generation` et repasser les projets en `NOT_INDEXED` ; conserver les faits canoniques, ne jamais supprimer le registre.
3. Commentaire SQL en tête, en français : pourquoi la migration existe.
4. Test d'upgrade dans `core/src/test/java/com/nexus/persistence/sqlite/SchemaMigrator<Sujet>UpgradeTest.java` sur le modèle de `SchemaMigratorScalarSecretUpgradeTest` : base à l'état précédent, migration, assertions sur génération/statut/données conservées.
5. Si le code lit une nouvelle colonne, mettre à jour la capacité SQLite concernée (`persistence/sqlite`) et ses tests de plan de requête.
6. Documentation : `docs/developer/release-and-recovery.md` (impact upgrade, rebuild imposé), contrats éventuels dans `scripts/release/test-operational-doc-contracts.sh`.
7. ADR si le format persistant ou une politique durable change (skill `nexus-adr`).
8. Validation complète (skill `nexus-validate`).
