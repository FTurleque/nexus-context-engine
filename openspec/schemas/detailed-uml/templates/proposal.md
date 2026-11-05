# Proposition

<!-- Étiquettes de nature : [Exigence] [Existant vérifié] [Décision] [Proposition] [Question] [Écart].
     Un constat sur l'existant cite sa source (chemin:ligne ou test). Les marqueurs `## Why`,
     `## What Changes`, `## Capabilities` et `## Impact` sont lus par OpenSpec : ne pas les renommer. -->

## Why

<!-- Problème ou opportunité, et pourquoi maintenant. Faits sourcés, sans généralités. -->

## What Changes

<!-- Une puce par changement, avec son étiquette de nature. Marquer **BREAKING** tout changement
     incompatible. Une divergence entre code, tests et specs devient une puce [Écart]. -->

## Type de changement

<!-- Une valeur : fonctionnel | architectural | documentaire | outillage.
     Elle fixe le profil de design.md (complet ou court). -->

## Périmètre et exclusions

**Dans le périmètre :**
<!-- Acteurs, surfaces et comportements concernés. -->

**Hors périmètre :**
<!-- Ce qui est exclu, pour borner specs et tâches. -->

## Capabilities

### New Capabilities
<!-- Comportements durables introduits. Un nom de capacité désigne un comportement du système,
     pas le travail du changement. Kebab-case, organisation existante des specs.
     Chaque entrée crée specs/<capability-path>/spec.md. -->
- `<capability-path>`: <ce que couvre la capacité>

### Modified Capabilities
<!-- Capacités existantes dont les EXIGENCES changent (chemin exact sous openspec/specs/).
     Laisser vide s'il n'y a pas de changement d'exigence. Un changement sans capacité
     (refactoring pur, outillage, documentation) pose `skip_specs: true` dans son .openspec.yaml.
     N'inventer aucune exigence pour satisfaire la validation. -->
- `<existing-capability-path>`: <exigence qui change>

## Impact

<!-- Code, API, dépendances, systèmes, documentation durable et tests concernés (chemins réels). -->

## Questions ouvertes

<!-- [Question] qui peuvent changer le périmètre. Omettre la section s'il n'y en a pas. -->
