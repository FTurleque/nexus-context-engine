# Spec Delta

## ADDED Requirements

### Requirement: Les bornes étroites de redaction refusent le contenu
La redaction SHALL refuser le contenu qui porte une partie utilisateur d'URL de plus de 1 024 caractères ou un jeton à préfixe fixe dont le corps dépasse la borne de son motif, sans le tronquer ni le publier en clair.

#### Scenario: Partie utilisateur d'URL trop longue
- **GIVEN** un contenu contenant `https://` suivi de 1 025 caractères d'identifiant puis `@hôte`
- **WHEN** le contenu est redigé
- **THEN** la redaction est refusée et le message d'erreur ne contient aucune partie de la valeur
- **AND** aucun contenu partiellement redigé n'est publié

#### Scenario: Partie utilisateur d'URL à la borne
- **GIVEN** un contenu contenant `https://` suivi de 1 024 caractères d'identifiant puis `@hôte`
- **WHEN** le contenu est redigé
- **THEN** la valeur est masquée par `[REDACTED]`

#### Scenario: Jeton à préfixe trop long
- **GIVEN** un contenu contenant un jeton `ghp_` suivi de 256 caractères alphanumériques
- **WHEN** le contenu est redigé
- **THEN** la redaction est refusée et le message d'erreur ne contient aucune partie du jeton

## MODIFIED Requirements

### Requirement: Une valeur sensible trop longue n'exclut que son fichier
Quand la redaction refuse le contenu d'un fichier parce qu'une valeur sensible dépasse la borne de son motif (4 096 caractères pour une assignation, une option, un élément XML ou un jeton `Bearer` ; les bornes étroites de l'exigence « Les bornes étroites de redaction refusent le contenu »), NEXUS SHALL exclure ce seul fichier des index lexical et sémantique, SHALL le conserver dans l'index structurel, SHALL ajouter au rapport un diagnostic public `INDEX_REDACTION_LIMIT` sans valeur ni contenu, et SHALL poursuivre l'indexation des autres fichiers.

#### Scenario: Indexation d'un projet
- **WHEN** un projet de trois fichiers contient un fichier avec `password = "` suivi de 5 000 caractères
- **THEN** l'indexation réussit, les deux autres fichiers sont recherchables et le rapport signale l'exclusion du fichier fautif

#### Scenario: Fichier conservé dans l'index structurel
- **GIVEN** un fichier exclu parce qu'il porte une valeur sensible trop longue
- **WHEN** l'indexation se termine
- **THEN** le fichier reste enregistré dans l'index structurel
- **AND** la recherche lexicale ne renvoie pas son contenu

#### Scenario: Aucune fuite
- **GIVEN** un fichier exclu parce qu'il porte une valeur sensible trop longue
- **WHEN** l'index lexical, l'index sémantique et un contexte sont construits
- **THEN** ni l'index lexical, ni l'index sémantique, ni un contexte ne contient sa valeur

#### Scenario: Construction de contexte
- **GIVEN** un contexte qui inclurait un fragment portant une valeur sensible de plus de 4 096 caractères
- **WHEN** le contexte est construit
- **THEN** ce fragment est écarté et le contexte est construit avec les autres fragments

#### Scenario: Valeur à la borne
- **GIVEN** une assignation sensible dont la valeur compte exactement 4 096 caractères
- **WHEN** le texte est redigé
- **THEN** la valeur est masquée par `[REDACTED]` et le contenu n'est pas refusé
- **AND** la même assignation avec une valeur de 4 097 caractères est refusée et son contenu n'est pas publié
