# Spec Delta

## ADDED Requirements

### Requirement: L'image construite par le compose porte sa version
L'image construite sur le poste par le compose généré par l'installateur SHALL porter dans `org.opencontainers.image.version` la version de NEXUS installée et SHALL NOT laisser ce label vide.

#### Scenario: Construction locale après installation Docker
- **GIVEN** une installation Docker de NEXUS dont le compose construit l'image (`pull_policy: build`)
- **WHEN** l'image est construite par `docker compose build`
- **THEN** `docker inspect` de l'image rend `org.opencontainers.image.version` égal à la version de l'installateur

#### Scenario: Révision inconnue sur le poste
- **WHEN** la construction locale n'a pas accès au SHA du commit source
- **THEN** `org.opencontainers.image.revision` porte la révision écrite par l'installateur au packaging, ou le label est absent ; il n'est jamais une chaîne vide
