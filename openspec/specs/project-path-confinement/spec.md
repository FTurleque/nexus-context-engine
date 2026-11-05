# project-path-confinement Specification

## Purpose
Définit le comportement vérifié de `ProjectPathGuard`, primitive de confinement des lectures de projet, par une classe de test dédiée.
Documentation durable : docs/developer/filesystem-support.md#openspec-project-path-confinement

## Requirements

### Requirement: Chaque méthode publique de ProjectPathGuard a un test direct
`ProjectPathGuardTest` SHALL appeler directement `resolve(Path)`, `resolve(RepositoryPath)`, `requireRegularFile`, `requireDirectory`, `containsSymbolicLink` et `repositoryPath`.

#### Scenario: Couverture directe
- **WHEN** on recherche les appels de ces méthodes dans `ProjectPathGuardTest`
- **THEN** chacune est appelée au moins une fois

### Requirement: Les chemins hors du projet sont refusés
`ProjectPathGuard` SHALL refuser un chemin relatif qui sort de la racine par `..`, un chemin absolu, et une cible dont la résolution réelle est hors de la racine.

#### Scenario: Traversal
- **WHEN** on résout `../outside.txt`
- **THEN** une `IOException` est levée

#### Scenario: Chemin absolu
- **WHEN** on résout un chemin absolu
- **THEN** une `IOException` est levée

### Requirement: Les liens symboliques sont refusés à toute position
`ProjectPathGuard` SHALL refuser un chemin dont le composant final ou un composant intermédiaire est un lien symbolique, et `containsSymbolicLink` SHALL renvoyer `true` dans ces deux cas.

#### Scenario: Symlink final
- **GIVEN** un lien symbolique vers un fichier du projet
- **WHEN** `requireRegularFile` le reçoit
- **THEN** une `IOException` est levée

#### Scenario: Symlink intermédiaire
- **GIVEN** un répertoire intermédiaire du chemin qui est un lien symbolique vers un répertoire hors projet
- **WHEN** `containsSymbolicLink` et `requireDirectory` reçoivent ce chemin
- **THEN** `containsSymbolicLink` renvoie `true` et `requireDirectory` lève une `IOException`

#### Scenario: Signalement d'un symlink final
- **GIVEN** un lien symbolique vers un fichier du projet
- **WHEN** `containsSymbolicLink` reçoit ce lien
- **THEN** il renvoie `true`

#### Scenario: Runner sans symlinks en CI
- **GIVEN** un système de fichiers qui refuse la création de lien symbolique et la variable `CI` définie
- **WHEN** un test de confinement tente de créer un lien symbolique
- **THEN** le test échoue au lieu d'être ignoré

### Requirement: Le type attendu est vérifié
`requireDirectory` SHALL refuser une cible qui n'est pas un répertoire, et `requireRegularFile` une cible qui n'est pas un fichier régulier.

#### Scenario: Racine non répertoire
- **WHEN** `requireDirectory` reçoit un fichier régulier du projet
- **THEN** une `IOException` est levée

#### Scenario: Répertoire à la place d'un fichier
- **WHEN** `requireRegularFile` reçoit un répertoire du projet
- **THEN** une `IOException` est levée

### Requirement: Le chemin de dépôt est relatif et normalisé
`repositoryPath` SHALL renvoyer un chemin relatif à la racine, avec `/` comme séparateur, sous Windows comme sous Linux.

#### Scenario: Fichier imbriqué
- **WHEN** on demande le chemin de dépôt de `src/main/App.java`
- **THEN** le résultat est `src/main/App.java`
