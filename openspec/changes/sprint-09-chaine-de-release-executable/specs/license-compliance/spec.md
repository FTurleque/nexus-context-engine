# Spec Delta

## Purpose

Définit le contrôle des licences des dépendances livrées, afin qu'aucune licence incompatible avec la licence du produit n'entre dans un artefact.

## ADDED Requirements

### Requirement: Seules les licences de la liste blanche sont acceptées
Le build SHALL échouer lorsqu'une dépendance livrée (portées compile et runtime) porte une licence absente de la liste blanche du pom racine.

#### Scenario: Licence hors liste
- **WHEN** une dépendance livrée déclare une licence absente de la liste blanche
- **THEN** la génération des notices fait échouer le build en nommant la dépendance et sa licence

#### Scenario: Licence manquante
- **WHEN** une dépendance livrée ne déclare aucune licence
- **THEN** le build échoue, comme aujourd'hui

### Requirement: Les libellés de licence sont normalisés
Les variantes de libellé d'une même licence SHALL être regroupées sous un libellé unique dans les notices tierces.

#### Scenario: Variantes d'Apache 2.0
- **WHEN** deux dépendances déclarent respectivement « Apache License, Version 2.0 » et « The Apache Software License, Version 2.0 »
- **THEN** les notices tierces les listent sous un seul libellé et le contrôle de liste blanche les accepte
