# semantic-search-qualification Specification

## Purpose
Définit quand et sur quel corpus la qualification sémantique réelle (Ollama local, modèle épinglé) s'exécute, pour qu'elle termine et produise une mesure sur `main`.
Documentation durable : docs/developer/semantic-search.md#openspec-semantic-search-qualification

## Requirements

### Requirement: Une PR exécute un corpus réduit borné
Sur une pull request, la qualification sémantique réelle SHALL exécuter un sous-ensemble représentatif du corpus, avec un `timeout-minutes` inférieur ou égal à 45, et SHALL appliquer les mêmes seuils `recallAt3`, `hitAt3` et `mrrAt3` que le corpus complet.

#### Scenario: PR touchant la recherche sémantique
- **WHEN** une PR vers `main` modifie `core/src/main/java/com/nexus/search/semantic/**`
- **THEN** le job exécute le corpus réduit, avec un timeout de 45 minutes au plus, et échoue si un seuil n'est pas atteint
- **AND** le rapport, les journaux et les fichiers texte du run sont conservés comme artefacts, même en cas d'échec

### Requirement: Le corpus complet s'exécute sur main
Le corpus complet SHALL s'exécuter sur `main` après la fusion d'un changement des chemins surveillés, et ce run SHALL NOT être annulé par un run ultérieur.

#### Scenario: Fusion sur main
- **GIVEN** une PR qui touche les chemins surveillés
- **WHEN** elle est fusionnée dans `main`
- **THEN** un run du corpus complet démarre sur le SHA de `main` et va jusqu'à son terme
- **AND** son groupe de concurrence est ce SHA : un push ultérieur sur `main` ne l'annule pas

#### Scenario: Rapport rattaché au SHA
- **WHEN** le run du corpus complet se termine
- **THEN** son rapport porte `snapshotCommit` égal au SHA de `main` qualifié
- **AND** le run échoue si le `snapshotCommit` du rapport diffère du SHA attendu

### Requirement: Le rôle de la qualification dans la release est explicite
`release.yml` SHALL refléter la décision de l'ADR qui complète ADR-0014 : soit le job de publication dépend d'une qualification sémantique réussie, soit `docs/developer/semantic-search.md` indique que la release ne l'exige pas.

#### Scenario: Lecture du workflow de release
- **WHEN** on lit les `needs` du job `publish` de `release.yml` et `docs/developer/semantic-search.md`
- **THEN** l'un des deux exprime la règle de l'ADR, sans contradiction avec l'autre
