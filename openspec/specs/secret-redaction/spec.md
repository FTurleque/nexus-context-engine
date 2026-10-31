# secret-redaction Specification

## Purpose
Définit les formats de secrets que NEXUS redige avant indexation, embedding et sortie, le comportement face à une valeur trop longue, et l'invalidation des index dérivés quand la politique change.

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
Quand une valeur sensible dépasse 4 096 caractères, NEXUS SHALL exclure du traitement le seul fichier qui la porte, SHALL ajouter au rapport un diagnostic public sans valeur ni contenu, et SHALL poursuivre l'indexation des autres fichiers.

#### Scenario: Indexation d'un projet
- **WHEN** un projet de trois fichiers contient un fichier avec `password = "` suivi de 5 000 caractères
- **THEN** l'indexation réussit, les deux autres fichiers sont recherchables et le rapport signale l'exclusion du fichier fautif

#### Scenario: Aucune fuite
- **WHEN** le fichier fautif est exclu
- **THEN** ni l'index lexical, ni l'index sémantique, ni un contexte ne contient sa valeur

#### Scenario: Construction de contexte
- **WHEN** un contexte inclurait un fragment portant une valeur sensible de plus de 4 096 caractères
- **THEN** ce fragment est écarté et le contexte est construit avec les autres fragments

### Requirement: Un changement de politique invalide les index dérivés
Toute modification du comportement de redaction SHALL incrémenter `SensitiveContentRedactor.POLICY_VERSION` et le profil sémantique `content-vN`, et SHALL être accompagnée d'une migration qui repasse les projets indexés en `NOT_INDEXED`.

#### Scenario: Mise à jour d'une base existante
- **WHEN** une base migrée avec la politique `secret-redaction-v5` est ouverte par la nouvelle version
- **THEN** la migration d'invalidation incrémente la génération d'index des projets, les repasse en `NOT_INDEXED` et conserve le registre des projets

#### Scenario: Index lexical d'une ancienne politique
- **WHEN** un index Lucene porte une version de politique différente de `secret-redaction-v6`
- **THEN** il est traité comme invalide et reconstruit

### Requirement: Chaque document est redigé une fois avant l'embedding
L'indexation sémantique SHALL rediger chaque document une seule fois avant normalisation, troncature et envoi au fournisseur d'embeddings.

#### Scenario: Document envoyé au fournisseur
- **WHEN** un document contenant un secret est préparé pour l'embedding
- **THEN** le texte envoyé est redigé et la redaction n'a été appliquée qu'une fois au document
