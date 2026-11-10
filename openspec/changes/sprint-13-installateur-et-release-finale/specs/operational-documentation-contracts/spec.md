# Spec Delta

## ADDED Requirements

### Requirement: La documentation de publication GHCR nomme le workflow de release
La documentation utilisateur Docker SHALL attribuer la publication des tags GHCR `<version>` et `latest` au workflow de release déclenché par tag, et SHALL NOT l'attribuer à `docker-distribution.yml`.

#### Scenario: Contrat documentaire
- **WHEN** `docs/user/docker-installation.md` attribue la publication GHCR à `docker-distribution.yml`
- **THEN** `bash .claude/scripts/contracts.sh` échoue en nommant le fichier
- **AND** la commande se termine avec un code non nul
