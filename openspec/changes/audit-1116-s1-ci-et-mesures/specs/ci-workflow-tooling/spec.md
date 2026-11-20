# Spec Delta

## ADDED Requirements

### Requirement: Aucun checkout ne conserve le jeton dans la configuration Git
Chaque étape `actions/checkout` d'un workflow ou d'une action composite SHALL déclarer `persist-credentials: false` sous `with:`, et le contrôle d'hygiène des workflows SHALL échouer, en nommant le fichier et la ligne, sur une étape `actions/checkout` qui ne le déclare pas ou le déclare à une autre valeur.

#### Scenario: Checkout du job de publication
- **WHEN** on lit l'étape `actions/checkout` du job `publish` de `release.yml`
- **THEN** elle déclare `persist-credentials: false`
- **AND** le job s'authentifie par les variables et commandes explicites de ses étapes, sans jeton lu dans `.git/config`

#### Scenario: Checkout sans la clé
- **GIVEN** un workflow dont une étape `uses: actions/checkout@<sha>` a un bloc `with:` sans `persist-credentials`
- **WHEN** le contrôle d'hygiène des checkouts s'exécute
- **THEN** il se termine avec le code 1 en nommant le fichier et la ligne de l'étape
- **AND** aucun fichier n'est modifié

#### Scenario: Checkout sans bloc with
- **GIVEN** une étape `uses: actions/checkout@<sha>` sans bloc `with:`
- **WHEN** le contrôle d'hygiène des checkouts s'exécute
- **THEN** il se termine avec le code 1 en nommant le fichier et la ligne de l'étape

#### Scenario: Valeur true
- **GIVEN** une étape `actions/checkout` qui déclare `persist-credentials: true`
- **WHEN** le contrôle d'hygiène des checkouts s'exécute
- **THEN** il se termine avec le code 1 en nommant le fichier et la ligne

#### Scenario: Workflows du dépôt
- **WHEN** le contrôle d'hygiène des checkouts s'exécute sur `.github/workflows` et `.github/actions`
- **THEN** il se termine avec le code 0
- **AND** `bash .claude/scripts/contracts.sh` exécute ce contrôle et sa fixture négative

### Requirement: Le contexte GitHub et les sorties d'étape arrivent dans le shell par l'environnement
Un bloc `run:` SHALL NOT contenir d'expression `${{ github.actor }}`, `${{ steps.<id>.outputs.<nom> }}` ni `${{ github.event.pull_request.<champ> }}` ; la valeur SHALL être transmise par une variable d'environnement de l'étape ou du job, et le contrôle d'hygiène SHALL échouer en nommant le fichier et la ligne.

#### Scenario: Connexion au registre de la release
- **WHEN** on lit l'étape de `release.yml` qui exécute `docker login ghcr.io`
- **THEN** le nom d'utilisateur arrive par une variable d'environnement déclarée dans `env:`
- **AND** aucune expression `${{ github.actor }}` ne figure dans le texte du bloc `run:`

#### Scenario: Base de la PR du benchmark d'échelle
- **WHEN** `scale-benchmark.yml` lit le SHA de base de la PR
- **THEN** la valeur arrive dans le bloc `run:` par une variable d'environnement

#### Scenario: Sortie d'étape interpolée
- **GIVEN** un workflow dont un bloc `run:` contient `echo "${{ steps.preflight.outputs.version_exists }}"`
- **WHEN** le contrôle d'hygiène des expressions s'exécute
- **THEN** il se termine avec le code 1 en nommant le fichier et la ligne

#### Scenario: Expression dans env
- **GIVEN** une étape dont le bloc `env:` déclare `DIGEST: ${{ steps.publish.outputs.digest }}` et dont le bloc `run:` lit `"$DIGEST"`
- **WHEN** le contrôle d'hygiène des expressions s'exécute
- **THEN** l'étape n'est pas signalée

### Requirement: Les gates requis Linux tournent sur un JDK et une image épinglés
Les jobs `linux-reactor` de `ci.yml` et `build-sbom` de `osv-scanner.yml` SHALL déclarer une version exacte du JDK (`java-version`), identique à celle du job `windows-gate`, et une image de runner versionnée `ubuntu-NN.NN` ; un contrôle local SHALL échouer, en nommant le workflow, le job et la ligne, sur une version flottante ou un libellé `-latest`.

#### Scenario: Lecture des deux jobs
- **WHEN** on lit `runs-on` et `java-version` des jobs `linux-reactor` et `build-sbom`
- **THEN** `runs-on` vaut `ubuntu-24.04` et `java-version` vaut la version exacte déclarée par `windows-gate`

#### Scenario: JDK flottant
- **GIVEN** une copie de travail où `build-sbom` déclare `java-version: '21'`
- **WHEN** `bash .claude/scripts/contracts.sh` s'exécute
- **THEN** le contrat échoue en nommant `osv-scanner.yml`, le job `build-sbom` et la ligne

#### Scenario: Image -latest
- **GIVEN** une copie de travail où `linux-reactor` déclare `runs-on: ubuntu-latest`
- **WHEN** le contrôle des images de runner s'exécute
- **THEN** il échoue en nommant `ci.yml`, la ligne et le job

#### Scenario: Versions divergentes entre gates
- **GIVEN** une copie de travail où `windows-gate` et `linux-reactor` déclarent deux versions exactes différentes
- **WHEN** `bash .claude/scripts/contracts.sh` s'exécute
- **THEN** le contrat échoue en nommant les deux jobs et les deux versions

### Requirement: Le filtre de chemins d'un gate couvre le wrapper Maven et son ancre
Les filtres `paths:` `pull_request` et `push` de `docker-distribution.yml` SHALL contenir `mvnw`, `mvnw.cmd`, `.mvn/**`, `config/tool-integrity.properties`, `scripts/release/verify-pinned-checksum.sh` et `scripts/release/ToolArchiveVerifier.java`. Ceux de `windows-installer.yml` et `main-push-windows-installer.yml` SHALL contenir `config/tool-integrity.properties`. Un contrôle local SHALL échouer sur une entrée absente.

#### Scenario: PR qui ne modifie que l'ancre d'intégrité
- **WHEN** une PR vers `main` ne modifie que `config/tool-integrity.properties`
- **THEN** les workflows `docker-distribution.yml` et `windows-installer.yml` se déclenchent

#### Scenario: Push qui ne modifie que le wrapper
- **WHEN** un push sur `main` ne modifie que `mvnw`
- **THEN** `docker-distribution.yml` se déclenche

#### Scenario: Entrée retirée d'un filtre
- **GIVEN** une copie de travail où le filtre `push` de `docker-distribution.yml` ne contient plus `.mvn/**`
- **WHEN** `bash .claude/scripts/contracts.sh` s'exécute
- **THEN** le contrat échoue en nommant le workflow, l'événement et l'entrée manquante

### Requirement: Le filtre de chemins d'un gate ne contient aucun motif mort
Un filtre `paths:` de `docker-distribution.yml`, `windows-installer.yml` ou `main-push-windows-installer.yml` SHALL NOT contenir de motif dont le premier segment littéral ne désigne aucun fichier ni répertoire suivi par Git, et un contrôle local SHALL échouer en nommant le workflow et le motif.

#### Scenario: Répertoire src absent
- **GIVEN** un filtre qui contient `'src/**'` alors qu'aucun répertoire `src` n'existe à la racine du dépôt
- **WHEN** `bash .claude/scripts/contracts.sh` s'exécute
- **THEN** le contrat échoue en nommant le workflow et le motif `src/**`

#### Scenario: Filtres du dépôt
- **WHEN** le contrôle des motifs s'exécute sur les trois workflows
- **THEN** il se termine avec le code 0
