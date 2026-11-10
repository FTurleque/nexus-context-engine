# Spec Delta

## ADDED Requirements

### Requirement: Un échec à la barrière de publication rend le projet à reconstruire
Si la publication canonique échoue après le commit des index dérivés, ou si le commit sémantique échoue après le commit lexical, l'indexation SHALL échouer, SHALL laisser le projet `FAILED` avec la génération canonique précédente et sans analyse en attente, et le passage suivant SHALL reconstruire entièrement le projet.

#### Scenario: Publication canonique en échec après le commit Lucene
- **GIVEN** un projet `READY` à la génération N
- **WHEN** une réindexation commit les index dérivés puis la publication canonique lève une exception
- **THEN** l'indexation échoue avec cette exception et le projet est `FAILED`
- **AND** la génération canonique reste N et aucune analyse en attente ne subsiste pour ce projet

#### Scenario: Commit sémantique en échec après le commit lexical
- **GIVEN** un projet `READY` avec recherche sémantique active
- **WHEN** le commit lexical réussit puis le commit sémantique lève une exception
- **THEN** l'indexation échoue, le projet est `FAILED` et la publication canonique n'a pas lieu
- **AND** la génération canonique est inchangée

#### Scenario: Passage suivant
- **GIVEN** un projet laissé `FAILED` par l'un de ces échecs
- **WHEN** l'indexation suivante réussit
- **THEN** elle reconstruit entièrement le projet et le laisse `READY` avec des index dérivés cohérents avec la génération publiée

#### Scenario: Aucun service avant READY
- **GIVEN** un projet laissé `FAILED` par l'un de ces échecs
- **WHEN** une recherche porte sur ce projet
- **THEN** elle est refusée par la garde de disponibilité sans lire les index dérivés
