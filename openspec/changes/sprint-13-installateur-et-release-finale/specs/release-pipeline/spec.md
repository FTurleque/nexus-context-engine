# Spec Delta

## ADDED Requirements

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
