# ci-workflow-tooling Specification

## Purpose
Définit les règles d'hygiène vérifiables des workflows GitHub Actions du dépôt : épinglage des actions, passage des inputs, bornes de durée et images de runner.
Documentation durable : docs/developer/ci-and-supply-chain.md#openspec-ci-workflow-tooling

## Requirements

### Requirement: Les inputs ne sont jamais interpolés dans du shell
Un workflow SHALL NOT placer une expression `${{ inputs.<nom> }}` dans le texte d'un bloc `run:` ; la valeur SHALL être transmise par une variable d'environnement de l'étape ou du job.

#### Scenario: Profil de benchmark
- **WHEN** `scale-benchmark.yml`, `lucene-lifecycle-benchmark.yml` ou `scanner-corpus-benchmark.yml` lit `inputs.profile`
- **THEN** la valeur arrive dans le bloc `run:` par une variable d'environnement et la validation `ci|full` s'applique à cette variable
- **AND** aucune expression `${{ inputs.` ne figure dans le texte d'un bloc `run:` de ces workflows

#### Scenario: Valeur hostile
- **WHEN** un appelant transmet un profil contenant `"` ou `$(...)`
- **THEN** la valeur n'est pas exécutée et l'étape échoue sur la validation du profil
- **AND** aucune valeur de profil n'est écrite dans les sorties de l'étape

#### Scenario: Contrat d'interpolation
- **WHEN** un bloc `run:` d'un workflow contient `${{ inputs.`
- **THEN** `bash .claude/scripts/contracts.sh` échoue en nommant le fichier et la ligne
- **AND** la commande se termine avec un code non nul

### Requirement: Le commentaire de version d'une action concorde avec son SHA
Chaque `uses:` d'une action externe SHALL être épinglé par SHA complet suivi d'un commentaire `# vX.Y.Z` ; un même SHA SHALL porter le même commentaire partout, et une action SHALL n'avoir qu'une version majeure dans l'ensemble des workflows.

#### Scenario: Commentaire divergent
- **GIVEN** deux lignes qui épinglent `actions/upload-artifact` sur le même SHA
- **WHEN** leurs commentaires de version diffèrent
- **THEN** le contrat local échoue
- **AND** le message nomme la ligne en cause et l'autre ligne qui porte l'autre commentaire

#### Scenario: Deux majeures de checkout
- **GIVEN** un workflow qui épingle `actions/checkout` en v4
- **WHEN** un autre workflow l'épingle en v7
- **THEN** le contrat local échoue
- **AND** le message liste chaque version majeure avec son emplacement

#### Scenario: Workflow réutilisable OSV
- **WHEN** `osv-scanner.yml` appelle un workflow réutilisable de `google/osv-scanner-action`
- **THEN** le SHA est celui du commit d'un tag publié et le commentaire est la version de ce tag

#### Scenario: Action référencée par un tag
- **WHEN** un `uses:` d'une action externe désigne un tag ou une branche au lieu d'un SHA complet
- **THEN** le contrat local échoue en indiquant que la référence n'est pas épinglée par un SHA de commit complet

#### Scenario: Commentaire qui n'est pas une version
- **WHEN** un `uses:` épinglé par SHA complet porte un commentaire qui n'est pas une version `vX.Y.Z`
- **THEN** le contrat local échoue en indiquant le commentaire trouvé

### Requirement: Chaque job non réutilisable est borné dans le temps
Tout job qui n'appelle pas un workflow réutilisable SHALL déclarer `timeout-minutes`.

#### Scenario: Gates requis
- **WHEN** on lit les jobs `windows-gate` et `linux-reactor` de `ci.yml`, `analyze` de `codeql.yml` et `validate-release` de `release.yml`
- **THEN** chacun déclare `timeout-minutes`

#### Scenario: Job sans borne
- **WHEN** un job avec `runs-on:` ne déclare pas `timeout-minutes`
- **THEN** le contrat local échoue en nommant le workflow et le job
- **AND** le message indique aussi la ligne du job

#### Scenario: Job appelant un workflow réutilisable
- **WHEN** un job déclare `uses:` d'un workflow réutilisable et aucun `timeout-minutes`
- **THEN** le contrat local ne le signale pas

### Requirement: Les benchmarks tournent sur des images de runner épinglées
Les jobs de benchmark et de qualification de performance SHALL désigner une image de runner versionnée (`ubuntu-24.04`, `windows-2025`) et non un libellé `-latest`.

#### Scenario: Benchmark d'échelle
- **WHEN** on lit `runs-on` du job de `scale-benchmark.yml`
- **THEN** la valeur est une image versionnée

#### Scenario: Matrice multi-OS
- **WHEN** `lucene-lifecycle-benchmark.yml` ou `git-context-cache-qualification.yml` déclare sa matrice d'OS
- **THEN** chaque entrée est une image versionnée

#### Scenario: Libellé -latest refusé
- **WHEN** un job de benchmark déclare `runs-on: ubuntu-latest`
- **THEN** le contrat local échoue en nommant le workflow, la ligne et le job

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
