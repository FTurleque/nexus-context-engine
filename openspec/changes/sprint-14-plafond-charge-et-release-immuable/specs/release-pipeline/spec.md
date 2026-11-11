# Spec Delta

## ADDED Requirements

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
