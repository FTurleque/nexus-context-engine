---
id: DATA-0020
title: "Versionner le schéma SQLite avec des migrations SQL embarquées"
domain: data
rules: true
files: ["**/src/main/java/**", "core/src/main/resources/db/migration/**"]
---

# Versionner le schéma SQLite avec des migrations SQL embarquées

- **Statut** : Accepted
- **Date** : 2026-07-19

## Context

Le schéma SQLite de NEXUS évoluera avec le registre de projets, les fichiers indexés, les symboles et les relations. Une création de tables codée directement dans les repositories rendrait l'évolution difficile à tracer. Introduire immédiatement un framework complet de migration ajouterait cependant une dépendance importante pour un schéma local encore réduit.

## Facteurs de décision

- historique explicite du schéma ;
- migrations reproductibles ;
- fonctionnement hors ligne ;
- simplicité du MVP ;
- absence de dépendance à un serveur ;
- possibilité future de remplacer le mécanisme sans modifier les repositories métier.

## Options envisagées

- créer les tables avec `CREATE TABLE IF NOT EXISTS` dispersés dans le code ;
- adopter immédiatement Flyway ou Liquibase ;
- utiliser des scripts SQL versionnés embarqués avec un migrateur minimal NEXUS.

## Decision

**Option retenue : utiliser des scripts SQL versionnés embarqués et un migrateur minimal, transactionnel et strictement limité à l'application ordonnée de migrations.**

Les migrations sont placées dans le module core :

```text
core/src/main/resources/db/migration/
```

avec un nommage :

```text
V001__initial_schema.sql
V002__description.sql
```

Une table `schema_migrations` enregistre la version appliquée et la date d'exécution.

Le migrateur :

1. ouvre une transaction ;
2. crée `schema_migrations` si nécessaire ;
3. détermine les versions déjà appliquées ;
4. applique les migrations connues dans l'ordre ;
5. enregistre chaque version ;
6. rollback en cas d'échec.

Le migrateur n'a pas vocation à devenir un framework généraliste. Si le nombre ou la complexité des migrations augmente significativement, l'adoption de Flyway/Liquibase sera réévaluée par ADR.

### Conséquences positives

- historique du schéma visible dans Git ;
- aucune magie de création dispersée dans les repositories ;
- fonctionnement local et léger ;
- contrôle transactionnel explicite ;
- possibilité de tester une base vierge et des montées de version.

### Conséquences négatives et compromis acceptés

- un petit composant de migration doit être maintenu ;
- les migrations complexes devront être écrites avec prudence pour SQLite ;
- le mécanisme offre moins de fonctionnalités qu'un outil spécialisé.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Migration partiellement appliquée | Élevé | Transaction et rollback |
| Numéro de migration dupliqué | Élevé | Liste explicite ordonnée et test de démarrage |
| Évolution vers un migrateur maison trop complexe | Moyen | Réévaluer un outil standard dès que le besoin dépasse l'application de scripts ordonnés |
| Script incompatible SQLite | Élevé | Tests d'intégration sur base temporaire réelle |

### Confirmation

- aucune table métier n'est créée directement dans les repositories ;
- la base vierge est initialisée par les migrations ;
- une migration déjà appliquée n'est pas rejouée ;
- un échec rollback la transaction ;
- des tests couvrent l'initialisation.

## Do's and Don'ts

### Do

- Placer les migrations dans `core/src/main/resources/db/migration/`, nommées `V001__initial_schema.sql`, `V002__description.sql`…
- Enregistrer dans la table `schema_migrations` chaque version appliquée et sa date d'exécution.
- Appliquer les migrations connues dans l'ordre, dans une transaction, avec rollback en cas d'échec.
- Initialiser une base vierge par les migrations.
- Réévaluer Flyway ou Liquibase par ADR si le nombre ou la complexité des migrations augmente significativement.

### Don't

- Ne pas créer de table métier directement dans les repositories.
- Ne pas rejouer une migration déjà appliquée.
- Ne pas faire du migrateur un framework généraliste : il reste limité à l'application ordonnée de migrations.

## Analyse détaillée des options

### SQL dispersé dans le code

**Avantages :** mise en œuvre immédiate.

**Inconvénients :** historique faible, ordre difficile à maîtriser et évolution risquée.

### Flyway ou Liquibase immédiatement

**Avantages :** outils éprouvés et riches.

**Inconvénients :** dépendance et configuration supplémentaires disproportionnées pour le premier schéma local ; support SQLite à surveiller selon l'outil/version.

### Scripts SQL embarqués + migrateur minimal

**Avantages :** explicite, léger, versionné et suffisant pour le MVP.

**Inconvénients :** quelques responsabilités de migration restent à notre charge.

## Compliance and Enforcement

- Règle Archgate `DATA-0020-versionner-le-schema-sqlite-avec-des-migrations-sql-embarquees.rules.ts` / `migration-files-named-vnnn` : chaque fichier de `core/src/main/resources/db/migration/` est nommé `Vnnn__description.sql`, numéros uniques et contigus depuis `001`.
- Règle Archgate `DATA-0020-versionner-le-schema-sqlite-avec-des-migrations-sql-embarquees.rules.ts` / `migrations-listed-in-schema-migrator` : `SchemaMigrator.java` déclare chaque migration du dossier par `new Migration(n, "db/migration/Vnnn__….sql")`, `n` égal à `nnn`, une seule fois, dans l'ordre croissant, et aucun script absent.
- Règle Archgate `DATA-0020-versionner-le-schema-sqlite-avec-des-migrations-sql-embarquees.rules.ts` / `no-table-creation-outside-migrations` : aucun `CREATE TABLE` dans les sources Java de production, hors de `schema_migrations` dans `SchemaMigrator.java`.
- Tests `SchemaMigratorTest`, `SchemaMigratorChecksumTest` (dont `reRunningMigrationsWithUnchangedScriptsSucceeds`), `SchemaMigratorSymbolRangeUpgradeTest`, `SchemaMigratorLexicalRedactionUpgradeTest`, `SchemaMigratorRepositoryPathUpgradeTest`, `SchemaMigratorScalarSecretUpgradeTest` (`core/src/test/java/com/nexus/persistence/sqlite/`), lancés par `mvnw clean install` (workflow `.github/workflows/ci.yml` : job Linux reactor, et job Windows gate par `scripts/validate-phase-6.ps1`).
- Contrat `scripts/release/test-operational-doc-contracts.sh` (exécuté par `.github/workflows/ci.yml`) : liste des migrations documentée et dernière migration attendue.
- Hook `.claude/hooks/guard-edit.ps1` : refuse la modification d'une migration existante `core/src/main/resources/db/migration/V*__*.sql` ; tests dans `.claude/hooks/tests/Test-Hooks.ps1`.

## Écarts constatés

Constat du 2026-10-29, sans modification de la décision :

- Le migrateur fait plus que l'application ordonnée de migrations décrite : il calcule l'empreinte SHA-256 de chaque script, la vérifie pour une migration déjà appliquée ou la rétro-remplit (`core/src/main/java/com/nexus/persistence/sqlite/SchemaMigrator.java:43`, `:48`), et ajoute pour cela la colonne `script_sha256` à `schema_migrations` par `ALTER TABLE` (`SchemaMigrator.java:73`). Cette vérification n'est décidée par aucun ADR actif ; DATA-0049 et DATA-0050 parlent seulement de migrations « checksumées ».

## Conditions de réexamen

Réexaminer si les migrations impliquent des branches, callbacks complexes, validations avancées, support multi-SGBD ou deviennent difficiles à tester avec le mécanisme minimal.

## Décisions liées

- ADR-0006 — Utiliser SQLite comme source de vérité structurelle locale.
