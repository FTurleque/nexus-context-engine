# secret-redaction Specification

## Purpose
Définit les formats de secrets que NEXUS redige avant indexation, embedding et sortie, le comportement face à une valeur trop longue, et l'invalidation des index dérivés quand la politique change.
Documentation durable : docs/developer/secret-redaction.md#openspec-secret-redaction

## Requirements

### Requirement: Les formats de secrets courants sont redigés
La redaction SHALL masquer la valeur des 19 formats retenus par l'audit : en-tête `Authorization: Bearer`, assignations `token=`, `"token": "…"`, `private_key:`, `pwd=`, `credentials:`, `secretKey = "…"`, `access_key=`, `auth=`, option `--password`, balise `<password>…</password>`, jetons `sk-`, `sk_live_`, `xoxb-`, `AIza`, `glpat-`, `npm_`, identifiant dans une URL `https://<jeton>@hôte`, et bloc `PGP PRIVATE KEY BLOCK`.

#### Scenario: Sonde versionnée
- **WHEN** le test de sonde de 35 cas s'exécute
- **THEN** aucun des 19 formats retenus ne laisse sa valeur en clair dans le texte redigé

#### Scenario: Cas négatifs
- **WHEN** le texte contient des identifiants ordinaires comme `tokenizer`, `author` ou `auth()` sans valeur assignée
- **THEN** le texte est inchangé

#### Scenario: Formats hors périmètre
- **WHEN** la sonde contient un scalaire YAML de bloc `password: |` ou une valeur placée sur la ligne suivant sa clé
- **THEN** le test les épingle comme limite connue, conformément à `docs/developer/secret-redaction.md`

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

### Requirement: Un changement de politique invalide les index dérivés
Toute modification du comportement de redaction SHALL incrémenter `SensitiveContentRedactor.POLICY_VERSION` et le profil sémantique `content-vN`, et SHALL être accompagnée d'une migration qui repasse les projets indexés en `NOT_INDEXED`.

#### Scenario: Mise à jour d'une base existante
- **GIVEN** une base migrée avec la politique `secret-redaction-v6`
- **WHEN** la nouvelle version l'ouvre
- **THEN** la migration d'invalidation incrémente la génération d'index des projets, les repasse en `NOT_INDEXED` et conserve le registre des projets

#### Scenario: Index lexical d'une ancienne politique
- **GIVEN** un index Lucene dont la version de politique diffère de `secret-redaction-v7`
- **WHEN** l'indexation d'un projet vérifie la présence de l'index lexical
- **THEN** il est traité comme invalide et reconstruit

### Requirement: Chaque document est redigé une fois avant l'embedding
L'indexation sémantique SHALL rediger chaque document une seule fois avant normalisation, troncature et envoi au fournisseur d'embeddings.

#### Scenario: Document envoyé au fournisseur
- **GIVEN** un document contenant un secret
- **WHEN** il est préparé pour l'embedding
- **THEN** le texte envoyé est redigé et la redaction n'a été appliquée qu'une fois au document

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

### Requirement: Un jeton sk- est masqué ou refusé à toute longueur de corps
Pour un jeton `sk-`, `sk-proj-` ou `sk-ant-`, la redaction SHALL masquer un corps, compté après le préfixe, de 20 à 255 caractères et SHALL refuser le contenu dès 256 caractères de corps, sans jamais publier le jeton en clair.

#### Scenario: Corps de 255 caractères
- **GIVEN** un contenu contenant `sk-` suivi de 255 caractères alphanumériques
- **WHEN** le contenu est redigé
- **THEN** le jeton est remplacé par `[REDACTED]`

#### Scenario: Corps de 256 caractères
- **GIVEN** un contenu contenant `sk-` suivi de 256 caractères alphanumériques
- **WHEN** le contenu est redigé
- **THEN** la redaction est refusée et le message d'erreur ne contient aucune partie du jeton

#### Scenario: Préfixe sk-ant- à la borne
- **GIVEN** un contenu contenant `sk-ant-` suivi de 255 caractères, puis un autre contenu avec `sk-ant-` suivi de 256 caractères
- **WHEN** chacun est redigé
- **THEN** le premier jeton est masqué et le second contenu est refusé

#### Scenario: Préfixe sk-proj- à la borne
- **GIVEN** un contenu contenant `sk-proj-` suivi de 255 caractères, puis un autre contenu avec `sk-proj-` suivi de 256 caractères
- **WHEN** chacun est redigé
- **THEN** le premier jeton est masqué et le second contenu est refusé

#### Scenario: Fenêtre sans fuite
- **WHEN** des contenus portant `sk-` suivi de 256 à 260 caractères sont redigés
- **THEN** chacun est refusé et aucun n'est publié en clair
