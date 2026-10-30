# project-path-confinement Specification

## Purpose
Définit le comportement vérifié de `ProjectPathGuard`, primitive de confinement des lectures de projet, par une classe de test dédiée.

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
- **WHEN** `requireRegularFile` reçoit un lien symbolique vers un fichier du projet
- **THEN** une `IOException` est levée

#### Scenario: Symlink intermédiaire
- **WHEN** un répertoire intermédiaire du chemin est un lien symbolique vers un répertoire hors projet
- **THEN** `containsSymbolicLink` renvoie `true` et `requireDirectory` lève une `IOException`

#### Scenario: Runner sans symlinks en CI
- **WHEN** le système de fichiers refuse la création de lien symbolique et `CI` est défini
- **THEN** le test échoue au lieu d'être ignoré

### Requirement: Le type attendu est vérifié
`requireDirectory` SHALL refuser une cible qui n'est pas un répertoire, et `requireRegularFile` une cible qui n'est pas un fichier régulier.

#### Scenario: Racine non répertoire
- **WHEN** `requireDirectory` reçoit un fichier régulier du projet
- **THEN** une `IOException` est levée

### Requirement: Le chemin de dépôt est relatif et normalisé
`repositoryPath` SHALL renvoyer un chemin relatif à la racine, avec `/` comme séparateur, sous Windows comme sous Linux.

#### Scenario: Fichier imbriqué
- **WHEN** on demande le chemin de dépôt de `src/main/App.java`
- **THEN** le résultat est `src/main/App.java`
