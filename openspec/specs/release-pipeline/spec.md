# release-pipeline Specification

## Purpose
Définit les garanties de la chaîne de publication déclenchée par tag : cloisonnement des secrets, environnement protégé, provenance des assets, identité de version et pré-versions.
Documentation durable : docs/developer/immutable-release-publishing.md#openspec-release-pipeline

## Requirements

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

#### Scenario: Qualification manuelle signée
- **WHEN** le workflow Windows s'exécute par `workflow_dispatch` avec `require_authenticode: true`
- **THEN** seules les étapes de signature de la distribution et de l'installeur reçoivent les secrets, et aucun chemin vers le PFX n'est publié dans l'environnement du job

#### Scenario: Run de release
- **WHEN** `release.yml` appelle le workflow Windows
- **THEN** il l'appelle avec `require_authenticode: false` et sans aucun secret, et les assets Windows publiés ne sont pas signés Authenticode

### Requirement: La publication s'exécute dans l'environnement protégé
Le job de publication SHALL s'exécuter dans l'environnement GitHub `release`, qui exige une approbation et n'accepte que des tags `v*` ; les tags `v*` SHALL être protégés par un ruleset.

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

### Requirement: Les tags GHCR de version et latest viennent du workflow de release
Les tags `ghcr.io/fturleque/nexus-context-engine:<version>` et `:latest` SHALL désigner une image dont l'attestation de provenance est signée par `.github/workflows/release.yml`, et seul ce workflow SHALL pousser ces tags.

#### Scenario: Préflight de la release finale
- **WHEN** le préflight immuable de la release v0.2.0 interroge le registre
- **THEN** le tag de version est absent ou désigne déjà l'image qualifiée par ce run, et le préflight ne refuse pas le tag

#### Scenario: Vérification de la provenance
- **WHEN** on vérifie l'attestation de `:latest` après une release finale
- **THEN** le workflow signataire est `.github/workflows/release.yml` sur un tag `v*`

#### Scenario: Workflow de distribution Docker
- **WHEN** `docker-distribution.yml` s'exécute sur `main`
- **THEN** il ne pousse aucun tag sur GHCR

### Requirement: Les releases publiées sont immuables
Le dépôt SHALL avoir les releases immuables activées. Après avoir publié ou retrouvé publiée une release GitHub, `scripts/release/publish-github-release-assets.sh` SHALL vérifier que son champ `immutable` vaut `true` et SHALL échouer sinon, avant le déplacement de `latest`.

#### Scenario: Lecture du réglage
- **WHEN** le mainteneur exécute `gh api repos/FTurleque/nexus-context-engine/immutable-releases`
- **THEN** la réponse est un code 200 dont le champ `enabled` vaut `true`

#### Scenario: Release publiée non immuable
- **GIVEN** une release dont le champ `immutable` vaut `false` après sa sortie du brouillon
- **WHEN** le script de publication termine la publication
- **THEN** il sort avec un code non nul distinct des codes existants et un message qui nomme le tag
- **AND** l'étape qui déplace `latest` ne s'exécute pas

#### Scenario: Release immuable
- **GIVEN** une release dont le champ `immutable` vaut `true` après sa sortie du brouillon
- **WHEN** le script de publication termine la publication
- **THEN** il sort en 0 sans modifier la release

### Requirement: Un tag de version ne se déplace ni ne se supprime
Un ruleset actif ciblant `refs/tags/v*` SHALL interdire la mise à jour, la suppression et le non-fast-forward de ces tags sans aucun acteur de contournement. La création d'un tag `v*` SHALL rester réservée au mainteneur.

#### Scenario: Lecture des rulesets de tags
- **WHEN** le mainteneur lit les rulesets de cible `tag` du dépôt avec `gh api`
- **THEN** un ruleset actif ciblant `refs/tags/v*` porte les règles `update`, `deletion` et `non_fast_forward` avec une liste `bypass_actors` vide
- **AND** un ruleset actif ciblant `refs/tags/v*` porte la règle `creation` avec le mainteneur pour seul acteur de contournement

#### Scenario: Création d'un tag de release
- **WHEN** le mainteneur pousse un nouveau tag `vX.Y.Z-rc.N` sur la tête de `main`
- **THEN** GitHub accepte le push et `release.yml` démarre

### Requirement: Une release échouée reprend sans recréer son tag
`docs/developer/release-and-recovery.md` SHALL décrire la reprise d'un run de release échoué : relance des jobs échoués du même run quand le commit ne change pas, nouvelle version des pom et nouveau tag quand le contenu change. Aucune page courante SHALL prescrire la suppression ou le déplacement d'un tag `v*`.

#### Scenario: Contrat de la procédure
- **GIVEN** une copie de travail dont `docs/developer/release-and-recovery.md` ne contient plus la procédure de reprise
- **WHEN** `bash .claude/scripts/contracts.sh` s'exécute
- **THEN** le contrat échoue en nommant la page

#### Scenario: Prescription de suppression de tag
- **GIVEN** une page courante de `docs/developer/` qui prescrit `git push --delete` ou `git tag -d` sur un tag `v*`
- **WHEN** `bash .claude/scripts/contracts.sh` s'exécute
- **THEN** le contrat échoue en nommant la page et la ligne
