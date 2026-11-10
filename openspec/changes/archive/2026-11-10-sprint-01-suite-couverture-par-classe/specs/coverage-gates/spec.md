# Spec Delta

## Purpose

Définit les planchers de couverture JaCoCo qui font échouer le build, leur granularité (module, paquet, classe) et leur écart maximal à la mesure de référence, pour qu'une régression de couverture soit détectée avant de s'accumuler.
Documentation durable : docs/developer/adapter-coverage-gates.md#openspec-coverage-gates

## ADDED Requirements

### Requirement: Les classes de bornes ont un plancher de couverture par classe
Les classes `JdtStdioSession`, `MinosPayloadReader`, `SemanticSearchConfiguration` et `NexusRestTransportPolicy` SHALL porter chacune une règle JaCoCo de niveau classe, en lignes et en branches, qui fait échouer `./mvnw clean install` sous son plancher.

#### Scenario: Régression d'une classe de bornes
- **GIVEN** une modification qui fait passer la couverture de branches de `NexusRestTransportPolicy` sous son plancher sans faire passer son paquet sous le plancher du paquet
- **WHEN** `./mvnw clean install` s'exécute
- **THEN** l'exécution JaCoCo du module échoue en nommant la classe

#### Scenario: Règle mal nommée
- **GIVEN** une règle de classe dont l'inclusion ne correspond à aucune classe compilée
- **WHEN** le contrôle des planchers s'exécute
- **THEN** il échoue en nommant la règle sans classe correspondante

#### Scenario: Mesure courante
- **WHEN** `./mvnw clean install` s'exécute sur le HEAD
- **THEN** les quatre règles de classe sont respectées
