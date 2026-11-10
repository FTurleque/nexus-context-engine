# coverage-gates Specification

## Purpose
Définit les planchers de couverture JaCoCo qui font échouer le build, leur granularité (module, paquet, classe) et leur écart maximal à la mesure de référence, pour qu'une régression de couverture soit détectée avant de s'accumuler.
Documentation durable : docs/developer/adapter-coverage-gates.md#openspec-coverage-gates

## Requirements

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

### Requirement: Un plancher de couverture suit la mesure de référence
Chaque plancher JaCoCo de lignes et de branches SHALL être au plus 3 points sous la mesure de référence de sa cible, relevée sur le build complet du reactor et publiée dans la page de couverture.

#### Scenario: Plancher trop bas
- **GIVEN** une cible mesurée à 89,7 % de lignes dont le plancher vaut 78 %
- **WHEN** le contrôle d'écart compare le rapport JaCoCo aux planchers
- **THEN** le contrôle échoue en nommant la cible, le plancher et la mesure
- **AND** la commande se termine avec un code non nul

#### Scenario: Plancher dans l'écart admis
- **GIVEN** une cible mesurée à 89,7 % de lignes dont le plancher vaut 87 %
- **WHEN** le contrôle s'exécute
- **THEN** il passe pour cette cible

### Requirement: La page de couverture publie la mesure de référence des planchers
La page de couverture SHALL publier, pour chaque cible qui porte un plancher, la mesure de référence et le commit où elle a été relevée.

#### Scenario: Plancher sans mesure publiée
- **WHEN** un plancher existe dans un `pom.xml` sans ligne de mesure correspondante dans la page
- **THEN** le contrôle documentaire échoue en nommant la cible
