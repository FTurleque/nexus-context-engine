---
status: accepted
date: 2026-07-19
---

# ADR-0020 — Versionner le schéma SQLite avec des migrations SQL embarquées

## Contexte et problème

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

## Décision retenue

**Option retenue : utiliser des scripts SQL versionnés embarqués et un migrateur minimal, transactionnel et strictement limité à l'application ordonnée de migrations.**

Les migrations sont placées dans :

```text
src/main/resources/db/migration/
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

## Conditions de réexamen

Réexaminer si les migrations impliquent des branches, callbacks complexes, validations avancées, support multi-SGBD ou deviennent difficiles à tester avec le mécanisme minimal.

## Décisions liées

- ADR-0006 — Utiliser SQLite comme source de vérité structurelle locale.
