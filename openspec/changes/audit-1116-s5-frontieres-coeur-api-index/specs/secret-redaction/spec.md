# Spec Delta

## ADDED Requirements

### Requirement: La redaction est idempotente
Rediger, avec la politique courante, un texte déjà redigé par cette même politique SHALL rendre ce texte à l'identique, séparateurs de lignes compris.

#### Scenario: Corpus de la sonde de redaction
- **GIVEN** chaque cas de la sonde versionnée de redaction, en LF et en CRLF
- **WHEN** le texte redigé est redigé une seconde fois
- **THEN** le second résultat est égal au premier, caractère pour caractère

#### Scenario: Contenus générés
- **GIVEN** des contenus générés qui mêlent blocs de clé privée, jetons et affectations de secrets
- **WHEN** chaque texte redigé est redigé une seconde fois
- **THEN** le second résultat est égal au premier
- **AND** aucune seconde redaction ne lève de refus de redaction

### Requirement: Un contenu redigé par l'indexation n'est pas redigé de nouveau par les index dérivés
Les sessions des index lexical et sémantique SHALL NOT repasser dans le redacteur un contenu que le pipeline d'indexation a marqué comme redigé par la politique courante, et SHALL rediger avant indexation tout contenu non marqué.

#### Scenario: Contenu marqué par le pipeline
- **GIVEN** un fichier contenant un secret, indexé par le pipeline d'indexation
- **WHEN** ses documents sont appliqués aux sessions lexicale et sémantique
- **THEN** le redacteur n'est appelé qu'une fois pour ce fichier, par le pipeline
- **AND** le texte indexé et le texte envoyé au fournisseur d'embeddings sont égaux au contenu redigé par le pipeline

#### Scenario: Contenu non marqué
- **GIVEN** un document dont le contenu contient un secret et n'est pas marqué comme redigé
- **WHEN** il est appliqué à une session lexicale ou sémantique
- **THEN** le secret n'apparaît ni dans l'index lexical ni dans le texte envoyé au fournisseur d'embeddings

#### Scenario: Contenu non marqué que la redaction refuse
- **GIVEN** un document non marqué qui contient une valeur sensible trop longue
- **WHEN** il est appliqué à une session lexicale ou sémantique
- **THEN** il est exclu de cet index seul et le reste du lot est indexé

## MODIFIED Requirements

### Requirement: Chaque document est redigé une fois avant l'embedding
L'indexation sémantique SHALL envoyer au fournisseur d'embeddings un texte redigé une seule fois avant normalisation et troncature : par le pipeline d'indexation pour un contenu marqué comme redigé, par l'indexation sémantique elle-même sinon.

#### Scenario: Document envoyé au fournisseur
- **GIVEN** un document non marqué contenant un secret
- **WHEN** il est préparé pour l'embedding
- **THEN** le texte envoyé est redigé et la redaction n'a été appliquée qu'une fois au document

#### Scenario: Document redigé par le pipeline
- **GIVEN** un document dont le contenu a été redigé et marqué par le pipeline d'indexation
- **WHEN** il est préparé pour l'embedding
- **THEN** le texte envoyé provient de ce contenu redigé, sans nouvel appel au redacteur
