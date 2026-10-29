# Spec Delta

## Purpose

Définit les règles d'hygiène vérifiables des workflows GitHub Actions du dépôt : épinglage des actions, passage des inputs, bornes de durée et images de runner.

## ADDED Requirements

### Requirement: Les inputs ne sont jamais interpolés dans du shell
Un workflow SHALL NOT placer une expression `${{ inputs.<nom> }}` dans le texte d'un bloc `run:` ; la valeur SHALL être transmise par une variable d'environnement de l'étape ou du job.

#### Scenario: Profil de benchmark
- **WHEN** `scale-benchmark.yml`, `lucene-lifecycle-benchmark.yml` ou `scanner-corpus-benchmark.yml` lit `inputs.profile`
- **THEN** la valeur arrive dans le bloc `run:` par une variable d'environnement et la validation `ci|full` s'applique à cette variable

#### Scenario: Valeur hostile
- **WHEN** un appelant transmet un profil contenant `"` ou `$(...)`
- **THEN** la valeur n'est pas exécutée et l'étape échoue sur la validation du profil

#### Scenario: Contrat d'interpolation
- **WHEN** un bloc `run:` d'un workflow contient `${{ inputs.`
- **THEN** `bash .claude/scripts/contracts.sh` échoue en nommant le fichier et la ligne

### Requirement: Le commentaire de version d'une action concorde avec son SHA
Chaque `uses:` d'une action externe SHALL être épinglé par SHA complet suivi d'un commentaire `# vX.Y.Z` ; un même SHA SHALL porter le même commentaire partout, et une action SHALL n'avoir qu'une version majeure dans l'ensemble des workflows.

#### Scenario: Commentaire divergent
- **WHEN** deux lignes épinglent `actions/upload-artifact` sur le même SHA avec des commentaires différents
- **THEN** le contrat local échoue

#### Scenario: Deux majeures de checkout
- **WHEN** un workflow épingle `actions/checkout` en v4 et un autre en v7
- **THEN** le contrat local échoue

#### Scenario: Workflow réutilisable OSV
- **WHEN** `osv-scanner.yml` appelle un workflow réutilisable de `google/osv-scanner-action`
- **THEN** le SHA est celui du commit d'un tag publié et le commentaire est la version de ce tag

### Requirement: Chaque job non réutilisable est borné dans le temps
Tout job qui n'appelle pas un workflow réutilisable SHALL déclarer `timeout-minutes`.

#### Scenario: Gates requis
- **WHEN** on lit les jobs `windows-gate` et `linux-reactor` de `ci.yml`, `analyze` de `codeql.yml` et `validate-release` de `release.yml`
- **THEN** chacun déclare `timeout-minutes`

#### Scenario: Job sans borne
- **WHEN** un job avec `runs-on:` ne déclare pas `timeout-minutes`
- **THEN** le contrat local échoue en nommant le workflow et le job

### Requirement: Les benchmarks tournent sur des images de runner épinglées
Les jobs de benchmark et de qualification de performance SHALL désigner une image de runner versionnée (`ubuntu-24.04`, `windows-2025`) et non un libellé `-latest`.

#### Scenario: Benchmark d'échelle
- **WHEN** on lit `runs-on` du job de `scale-benchmark.yml`
- **THEN** la valeur est une image versionnée

#### Scenario: Matrice multi-OS
- **WHEN** `lucene-lifecycle-benchmark.yml` ou `git-context-cache-qualification.yml` déclare sa matrice d'OS
- **THEN** chaque entrée est une image versionnée
