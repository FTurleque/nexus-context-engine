---
id: ARCH-0058
title: "Vérifier les frontières du cœur par un test d'architecture"
domain: architecture
rules: false
---

# Vérifier les frontières du cœur par un test d'architecture

- **Statut** : Proposed
- **Date** : 2026-10-30
- **Origine** : audit du 2026-10-17, constat AUD-ARC-07 ; changement OpenSpec `sprint-03-filets-de-test-et-traces`
- **Complète** : [ARCH-0004](ARCH-0004-demarrer-avec-un-seul-module-maven.md)

## Context

[ARCH-0004](ARCH-0004-demarrer-avec-un-seul-module-maven.md) garde le cœur dans un seul module Maven et rend obligatoire la séparation logique par packages. [ARCH-0003](ARCH-0003-conserver-un-coeur-java-sans-framework-applicatif.md) exclut les frameworks applicatifs du cœur, [DATA-0006](DATA-0006-utiliser-sqlite-comme-source-de-verite-structurelle.md) et [DATA-0007](DATA-0007-utiliser-lucene-comme-index-de-recherche-local.md) placent SQLite et Lucene derrière leurs adaptateurs. Un seul module ne donne aucune barrière de compilation entre packages : ces frontières tiennent par la revue.

Sur `origin/develop` au 2026-10-29, les imports réels sont conformes : `org.apache.lucene` n'apparaît que dans `search.lucene` et `search.semantic.lucene`, `java.sql` et `org.sqlite` dans `persistence.sqlite` plus `java.sql.SQLException` dans `application.NexusApplication`, `application.NexusApplicationFactory` et `cli.NexusCli`, et `com.nexus.project` n'importe aucun autre package `com.nexus`. Le graphe des packages contient en revanche un bloc fortement connexe de 36 arêtes (index, search, ranking, context, security et leurs sous-packages), que le sprint 7 de l'audit doit casser. La règle Archgate compagnon d'ARCH-0003 contrôle les frameworks ; aucun contrôle ne couvre Lucene, SQLite, la feuille `project` ni les cycles.

La question est : **comment empêcher qu'une frontière du cœur ou le bloc de cycles se dégrade entre deux revues, sans extraire de module Maven ?**

## Options envisagées

- **Option A — test JUnit sur les sources, sans dépendance** : lit `core/src/main/java`, extrait `package` et `import` (dont `import static`), calcule le graphe des packages. Rapide, déterministe, dans la suite par défaut, aucune surface supply-chain nouvelle. Le parseur est à maintenir et à tester.
- **Option B — ArchUnit** : règles expressives sur le bytecode. Ajoute une dépendance de test et son graphe transitif pour des règles qui tiennent en quelques dizaines de lignes.
- **Option C — modules Maven ou `module-info.java`** : barrière de compilation réelle. Impossible tant que les cycles existent, et contraire à ARCH-0004 tant qu'aucun besoin d'extraction n'est observé.

## Decision

**Option retenue : Option A.**

`core/src/test/java/com/nexus/architecture/CoreArchitectureTest.java` fait partie de la suite par défaut du module `core` et vérifie, sur le code de production, des règles nommées dont chaque échec cite la règle, la classe et le nom importé :

- `core-sans-framework` : aucun type de `io.quarkus`, `jakarta`, `javax.ws`, `org.springframework` ni `io.modelcontextprotocol` ;
- `lucene-confine` : `org.apache.lucene` seulement dans `com.nexus.search.lucene` et `com.nexus.search.semantic.lucene` ;
- `sqlite-confine` : `org.sqlite` et `java.sql` seulement dans `com.nexus.persistence.sqlite`, plus une liste fermée, versionnée dans le test, de couples classe et nom importé (`java.sql.SQLException` dans `NexusApplication`, `NexusApplicationFactory` et `NexusCli`) ;
- `project-feuille` : `com.nexus.project` n'importe aucun autre package `com.nexus` ;
- `cycles-baseline` : les arêtes du graphe des packages internes à une composante fortement connexe sont égales à la baseline `core/src/test/resources/architecture/package-cycles.txt` (une arête par ligne, triée). Une arête nouvelle fait échouer le test, une entrée qui ne forme plus de cycle aussi.

Les imports et les noms pleinement qualifiés des préfixes surveillés comptent ; commentaires, chaînes et blocs de texte sont ignorés. Chaque règle a un cas négatif sur une fixture de sources en répertoire temporaire.

La décision ne casse aucun cycle (sprint 7), ne réduit pas la visibilité des types publics et ne contrôle pas les imports des adaptateurs.

## Do's and Don'ts

### Do

- Retirer de la baseline les arêtes d'un cycle cassé, dans la même PR.
- Ajouter une exception à `sqlite-confine` sous forme de couple classe et nom importé exact, justifié dans la PR.
- Ajouter une règle de frontière dans `CoreArchitectureRules` avec un nom stable et un cas négatif.

### Don't

- Ajouter une arête à la baseline de cycles pour faire passer un changement.
- Élargir une exception à un package entier ou à un préfixe.
- Désactiver ou filtrer `CoreArchitectureTest` hors de la suite par défaut.

## Consequences

### Positives

- Une dérive de frontière échoue au build local et en CI, avec la règle et l'import fautif.
- La baseline mesure la dette de cycles et ne peut que décroître ; le sprint 7 la vide.
- Aucune dépendance nouvelle.

### Négatives

- Le parseur ne voit que les imports et les noms qualifiés des préfixes surveillés : une dépendance par réflexion ou par chaîne lui échappe.
- Les arêtes entre packages `com.nexus` passent par les imports ; un nom pleinement qualifié `com.nexus.x.Type` sans import n'ajoute pas d'arête.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Le parseur manque une forme de référence | Moyen | Test du parseur (import statique, joker, nom qualifié, commentaire, chaîne, bloc de texte) |
| Une PR cassant un cycle oublie la baseline | Faible | Le test échoue et demande de retirer l'entrée |

## Compliance and Enforcement

- Contrôle automatique : `CoreArchitectureTest`, exécuté par `./mvnw test` dans le module `core` (Windows gate et reactor Linux), filtrable par `-Dtest='*Architecture*'`.
- Contrôle automatique : la règle Archgate compagnon d'ARCH-0003 reste en place pour les frameworks.
- Revue : toute modification de `package-cycles.txt` ou de `SQLITE_EXCEPTIONS` est justifiée dans la PR.

## Conditions de réexamen

La décision est réévaluée si le cœur est découpé en modules Maven ou reçoit un `module-info.java`, ou si une règle doit porter sur le bytecode. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## References

- [ARCH-0003](ARCH-0003-conserver-un-coeur-java-sans-framework-applicatif.md), [ARCH-0004](ARCH-0004-demarrer-avec-un-seul-module-maven.md), [DATA-0006](DATA-0006-utiliser-sqlite-comme-source-de-verite-structurelle.md), [DATA-0007](DATA-0007-utiliser-lucene-comme-index-de-recherche-local.md)
- Changement OpenSpec `openspec/changes/sprint-03-filets-de-test-et-traces/` (spec `core-package-boundaries`)
- [Architecture d'implémentation](../../developer/architecture-implementation.md), section « Frontières vérifiées du cœur »
