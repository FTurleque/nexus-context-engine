# Spec Delta

## Purpose

Définit les garanties de la chaîne de publication déclenchée par tag : cloisonnement des secrets, environnement protégé, provenance des assets, identité de version et pré-versions.

## ADDED Requirements

### Requirement: Les workflows appelés ne reçoivent que les secrets déclarés
Aucun workflow du dépôt SHALL transmettre `secrets: inherit` à un workflow réutilisable.

#### Scenario: Contrat de workflow
- **WHEN** `bash .claude/scripts/contracts.sh` s'exécute sur la branche
- **THEN** il échoue si un fichier de `.github/workflows/` contient `secrets: inherit`

### Requirement: Le matériel de signature est confiné aux étapes de signature
Les secrets de signature Authenticode SHALL n'être exposés qu'aux étapes qui signent, SHALL ne jamais être exposés sur un événement `pull_request`, et le PFX décodé SHALL être effacé dans l'étape qui l'a créé.

#### Scenario: Pull request
- **WHEN** le workflow Windows s'exécute sur une `pull_request`
- **THEN** aucune étape ne reçoit les secrets de signature et les artefacts produits ne sont pas signés

#### Scenario: Étapes de qualification d'un run de release
- **WHEN** le workflow Windows s'exécute pour une release
- **THEN** seules les étapes de signature de la distribution et de l'installeur reçoivent les secrets, et aucun chemin vers le PFX n'est publié dans l'environnement du job

### Requirement: La publication s'exécute dans l'environnement protégé
Le job de publication et la signature de release SHALL s'exécuter dans l'environnement GitHub `release`, qui exige une approbation et n'accepte que des tags `v*` ; les tags `v*` SHALL être protégés par un ruleset.

#### Scenario: Tag poussé
- **WHEN** un tag `v*` est poussé
- **THEN** le job de publication attend l'approbation de l'environnement `release` avant toute écriture dans GHCR ou dans les releases

#### Scenario: Réglages vérifiables
- **WHEN** le mainteneur lit `gh api repos/{owner}/{repo}/environments` et la liste des rulesets
- **THEN** l'environnement `release` existe avec un reviewer requis, et un ruleset actif cible `refs/tags/v*`

### Requirement: Chaque asset publié porte une attestation de provenance
L'image, chaque ZIP et chaque `setup.exe` Windows publiés SHALL porter une attestation de provenance GitHub, vérifiée par la chaîne avant l'upload des assets.

#### Scenario: Vérification consommateur
- **WHEN** un utilisateur exécute `gh attestation verify` sur le `setup.exe` téléchargé d'une release
- **THEN** la vérification réussit et désigne le workflow `Release` du dépôt

#### Scenario: Attestation absente
- **WHEN** la vérification d'un asset échoue dans le job de publication
- **THEN** le job échoue avant tout upload d'asset et avant le déplacement de `latest`

### Requirement: La version n'est écrite que dans les pom
La CI et le gate Windows SHALL lire la version produit dans `pom.xml` ; aucune version littérale du produit SHALL figurer dans `.github/workflows/ci.yml` ni dans `scripts/validate-phase-6.ps1`.

#### Scenario: Changement de version
- **WHEN** la version des pom passe de `X.Y.Z` à une autre valeur sans autre modification
- **THEN** la CI et `scripts/validate-phase-6.ps1` contrôlent les livrables de la nouvelle version

### Requirement: Un tag de pré-version publie une pré-version
La chaîne SHALL accepter un tag `vX.Y.Z-rc.N` égal à la version Maven et à la tête de `main`, SHALL marquer la release GitHub comme pré-version et SHALL NOT déplacer le tag d'image `latest`.

#### Scenario: Tag de pré-version valide
- **WHEN** le tag `v0.3.0-rc.1` est poussé sur la tête de `main` dont la version Maven est `0.3.0-rc.1`
- **THEN** la release `v0.3.0-rc.1` est publiée comme pré-version, avec image, assets et attestations, et `latest` est inchangé

#### Scenario: Tag incohérent
- **WHEN** le tag ne correspond pas à la version Maven ou n'est pas sur la tête de `main`
- **THEN** `validate-release` échoue et aucun job de qualification ni de publication ne s'exécute
