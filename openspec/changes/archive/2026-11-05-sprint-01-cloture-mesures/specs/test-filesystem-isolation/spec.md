# Spec Delta

## Purpose

Définit où les tests créent leurs fichiers, afin qu'un run de tests ne laisse aucun résidu sur le poste ou le runner.

## ADDED Requirements

### Requirement: Les fichiers de test restent dans le répertoire temporaire géré
Un test SHALL créer tous ses fichiers et répertoires, y compris les cibles « hors projet » des tests d'évasion, sous le répertoire `@TempDir` que JUnit nettoie.

#### Scenario: Cible externe d'un test symlink
- **WHEN** un test d'évasion crée un lien vers une cible qui doit être hors du projet simulé
- **THEN** la cible est créée sous le `@TempDir`, hors de la racine du projet simulé, et disparaît à la fin du test

#### Scenario: Aucun résidu après le run
- **WHEN** la suite `core` s'exécute
- **THEN** aucun fichier n'est créé à côté du `@TempDir` par `resolveSibling`
