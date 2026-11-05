---
name: nexus-redaction-change
description: Modifier la politique de redaction des secrets de NEXUS (SensitiveContentRedactor, SecretAssignmentScanner) en propageant toutes ses conséquences — version de politique, invalidation lexicale, profil sémantique content-vN, documentation et contrats. Utiliser pour ajouter ou corriger un motif de secret ou un format d'assignation.
---

# Changement de politique de redaction

La redaction précède l'embedding, l'estimation de tokens et la sélection du contexte ; les index persistés contiennent du contenu déjà redigé. Toute évolution impose donc de reconstruire les dérivés.

## Checklist

1. **Code** : modifier `core/src/main/java/com/nexus/security/SensitiveContentRedactor.java` ou `SecretAssignmentScanner.java`. Garder les motifs bornés (quantificateurs possessifs, longueur max) pour éviter le backtracking ; préserver les séparateurs de lignes des blocs multilignes.
2. **Tests** : `SensitiveContentRedactorTest` (et tests du scanner) — cas positifs, faux positifs à éviter, valeur longue, borne de taille, ranges inchangés.
3. **Version de politique** : incrémenter `SensitiveContentRedactor.POLICY_VERSION` (`secret-redaction-vN`) ; Lucene lexical (`LuceneSearchIndex`) le compare pour invalider ses dérivés.
4. **Migration d'invalidation** : nouvelle `Vnnn__invalidate_<sujet>_indexes.sql` (skill `nexus-schema-migration`) + test d'upgrade.
5. **Profil sémantique** : incrémenter `CONTENT_PROFILE_VERSION` dans `SemanticIndexingService` ; mettre à jour `content-vN` dans `README.md`, `docs/architecture/overview.md`, `docs/architecture/arc42/08-concepts-transverses.md`, `docs/architecture/arc42/10-exigences-qualite.md`, `docs/developer/current-limitations.md`, `docs/developer/release-and-recovery.md`, `docs/developer/semantic-search.md`, et les `require(...)` correspondants de `scripts/release/test-operational-doc-contracts.sh`.
6. **Doc fonctionnelle** : `docs/developer/secret-redaction.md` et le paragraphe secrets du `README.md` (formats pris en charge).
7. `bash .claude/scripts/contracts.sh`, validation complète, puis faire relire par l'agent `nexus-security-auditor`.
