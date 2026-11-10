# Spec Delta

## ADDED Requirements

### Requirement: Le contrôle d'hygiène refuse les formes YAML qu'il ne sait pas lire
Le contrôle d'hygiène des workflows SHALL échouer, en nommant le fichier et la ligne, sur un job en flow mapping, une clé citée parmi celles qu'il contrôle, ou un `run:` sans valeur suivi d'un scalaire simple sur la ligne suivante.

#### Scenario: Job en flow mapping
- **GIVEN** un workflow dont un job s'écrit `b: { runs-on: ubuntu-24.04, steps: [ { run: "echo ${{ inputs.profile }}" } ] }`
- **WHEN** le contrôle d'hygiène s'exécute
- **THEN** il échoue en signalant une forme non prise en charge

#### Scenario: Clé citée
- **GIVEN** un job dont la clé s'écrit `"runs-on"` sans `timeout-minutes`
- **WHEN** le contrôle s'exécute
- **THEN** il échoue en nommant le fichier et la ligne

#### Scenario: run suivi d'un scalaire simple
- **GIVEN** une étape `run:` dont la valeur `echo ${{ inputs.profile }}` est sur la ligne suivante, sans indicateur de bloc
- **WHEN** le contrôle s'exécute
- **THEN** il échoue en signalant l'interpolation ou la forme non prise en charge

### Requirement: Le contrôle d'hygiène couvre les scripts d'actions et les actions composites
Le contrôle des inputs SHALL s'appliquer au paramètre `script:` d'une étape `with:` et aux fichiers `.github/actions/*/action.yml`, en plus des blocs `run:` de `.github/workflows`.

#### Scenario: github-script
- **GIVEN** une étape `actions/github-script` dont `with: script:` contient `${{ inputs.profile }}`
- **WHEN** le contrôle s'exécute
- **THEN** il échoue en nommant le fichier et la ligne

#### Scenario: Action composite
- **GIVEN** une action composite de `.github/actions/` dont un `run:` contient `${{ inputs.model }}`
- **WHEN** le contrôle s'exécute
- **THEN** il échoue en nommant le fichier de l'action

### Requirement: Le SHA épinglé correspond au tag amont annoncé
Un contrôle avec accès réseau SHALL vérifier, pour chaque action externe épinglée, que le SHA désigne le commit du tag amont nommé par le commentaire `# vX.Y.Z`, tag annoté déréférencé compris.

#### Scenario: SHA changé sans le commentaire
- **GIVEN** un `uses:` dont le SHA a changé et dont le commentaire nomme toujours l'ancien tag
- **WHEN** le contrôle des tags amont s'exécute
- **THEN** il échoue en nommant l'action, le SHA, le tag et le commit du tag

#### Scenario: Tag annoté
- **GIVEN** une action dont le tag `vX.Y.Z` est annoté
- **WHEN** le contrôle compare le SHA au commit désigné par le tag déréférencé
- **THEN** il passe si le SHA est ce commit
