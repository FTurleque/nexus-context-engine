# Spec Delta

## ADDED Requirements

### Requirement: Le contrôle d'hygiène refuse une clé espacée avant les deux-points
Le contrôle `forms` SHALL échouer, en nommant le fichier et la ligne, sur toute clé de mapping suivie d'un ou plusieurs espaces avant « : » (`run :`, `runs-on :`), élément de séquence compris, hors des lignes de texte d'un bloc `run:` ou `script:`.

#### Scenario: run espacé qui interpole un input
- **GIVEN** un workflow dont une étape s'écrit `- run : echo ${{ inputs.profile }}`
- **WHEN** le contrôle `forms` ou `inputs` s'exécute
- **THEN** il échoue avec le code 1 en signalant une forme non prise en charge sur cette ligne

#### Scenario: runs-on espacé sans timeout
- **GIVEN** un job qui déclare `runs-on : ubuntu-latest` et aucun `timeout-minutes`
- **WHEN** le contrôle `timeouts` s'exécute
- **THEN** il échoue avec le code 1 en nommant le fichier et la ligne

#### Scenario: Texte d'un bloc run
- **GIVEN** une étape `run: |` dont une ligne de script contient `label : valeur`
- **WHEN** le contrôle `forms` s'exécute
- **THEN** cette ligne n'est pas signalée

### Requirement: Le contrôle d'hygiène lit les clés de job à l'indentation de leur corps
Les contrôles d'hygiène SHALL lire les clés d'un job à l'indentation de la première clé de ce job, et le contrôle `forms` SHALL échouer, en nommant le fichier et la ligne, sur une clé placée entre l'indentation de l'identifiant du job et celle de sa première clé.

#### Scenario: Corps de job indenté de 4 sans timeout
- **GIVEN** un job dont toutes les clés sont indentées de 4 espaces sous son identifiant, avec `runs-on:` et sans `timeout-minutes`
- **WHEN** le contrôle `timeouts` s'exécute
- **THEN** il échoue avec le code 1 en nommant le fichier, la ligne et le job

#### Scenario: Clé de job désalignée
- **GIVEN** un job dont la première clé est indentée de 4 espaces sous son identifiant et une clé suivante de 2
- **WHEN** le contrôle `forms` s'exécute
- **THEN** il échoue avec le code 1 en signalant une forme non prise en charge sur la ligne de cette clé

#### Scenario: Corps de job indenté de 2
- **GIVEN** les workflows de `.github/workflows` et les actions de `.github/actions`, dont les corps de job sont indentés de 2
- **WHEN** les contrôles `forms`, `inputs` et `timeouts` s'exécutent
- **THEN** ils se terminent avec le code 0
