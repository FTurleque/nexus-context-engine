# Spec Delta

## Purpose

Définit la lecture d'un fichier de projet sans suivre de lien au moment de l'ouverture, y compris dans le repli non atomique des systèmes de fichiers sans traversée sûre, et le refus d'une substitution concurrente visible avant toute lecture.
Documentation durable : docs/developer/filesystem-support.md#openspec-safe-file-reads

## ADDED Requirements

### Requirement: Le repli de lecture refuse une jonction substituée avant toute lecture
Dans le repli non atomique, quand un composant du chemin est remplacé par une jonction ou un autre point d'analyse entre le contrôle des composants et l'ouverture du fichier, la lecture SHALL échouer par une `IOException` et SHALL fermer le canal ouvert avant d'en lire un octet. Un composant déjà point d'analyse au contrôle et inchangé à la revalidation SHALL rester lisible.

#### Scenario: Répertoire remplacé par une jonction
- **GIVEN** un système Windows, un répertoire `selected` qui contient un fichier `data`, et un répertoire extérieur qui contient un autre fichier `data`
- **WHEN** la lecture de `selected/data` passe par le repli et que, entre le contrôle et l'ouverture, `selected` est déplacé puis remplacé par une jonction vers le répertoire extérieur
- **THEN** la lecture échoue par une `IOException`
- **AND** le canal ouvert est fermé et aucun octet du fichier extérieur n'est rendu

#### Scenario: Jonction ancêtre préexistante
- **GIVEN** un système Windows et un fichier atteint par un répertoire ancêtre qui est une jonction présente avant la lecture et inchangée pendant celle-ci
- **WHEN** la lecture du fichier passe par le repli
- **THEN** le contenu du fichier est rendu

#### Scenario: Fichier ordinaire
- **WHEN** la lecture d'un fichier ordinaire passe par le repli
- **THEN** son contenu est rendu, comme avant ce changement

#### Scenario: Runner Windows en CI
- **GIVEN** la qualification filesystem sur un runner Windows
- **WHEN** les tests de lecture s'exécutent
- **THEN** le scénario « Répertoire remplacé par une jonction » s'exécute et n'est pas ignoré
