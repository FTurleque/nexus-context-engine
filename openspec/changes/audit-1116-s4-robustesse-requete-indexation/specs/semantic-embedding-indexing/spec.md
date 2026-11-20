# Spec Delta

## Purpose

Définit comment l'indexation sémantique prépare chaque document et appelle le fournisseur d'embeddings : extrait borné, reprise bornée d'une indisponibilité transitoire, erreur de contrat bloquante et délai par défaut du fournisseur Ollama.
Documentation durable : docs/developer/semantic-search.md#openspec-semantic-embedding-indexing

## ADDED Requirements

### Requirement: L'extrait sémantique ne normalise qu'un préfixe borné
L'extrait d'un document vectorisé SHALL être identique au préfixe de 320 caractères, sans paire de substitution coupée, du contenu redigé dont chaque suite de blancs est remplacée par une espace puis rogné. Son calcul SHALL NOT allouer de chaîne de la taille du contenu, et SHALL s'arrêter dès que les caractères lus suffisent à fixer l'extrait.

#### Scenario: Équivalence de sortie
- **GIVEN** des contenus redigés avec blancs en tête, suites de tabulations, CR, LF et espaces, caractères de contrôle en tête et en fin, caractère hors BMP à cheval sur la 320e position, contenu plus court que 320 caractères
- **WHEN** l'extrait de chacun est calculé
- **THEN** il est identique à celui de la normalisation du contenu entier suivie de la troncature à 320 caractères

#### Scenario: Contenu vide ou blanc
- **GIVEN** un document dont le contenu redigé ne contient que des blancs
- **WHEN** son extrait est calculé
- **THEN** l'extrait est le chemin relatif du document

#### Scenario: Gros document
- **GIVEN** un contenu redigé de 8 Mio dont les 1 000 premiers caractères ne sont pas blancs
- **WHEN** son extrait est calculé
- **THEN** l'extrait est identique à celui de la normalisation du contenu entier
- **AND** le calcul n'a pas lu le contenu au-delà de ses 1 000 premiers caractères

### Requirement: Une indisponibilité transitoire du fournisseur est reprise un nombre borné de fois
Pendant l'indexation, quand l'appel d'embedding d'un lot échoue par indisponibilité transitoire du fournisseur (HTTP 429 ou 5xx, délai dépassé, connexion impossible), l'appel SHALL être repris après une attente croissante et plafonnée, au plus un nombre fixe de fois. Si une reprise réussit, l'indexation SHALL se poursuivre comme sans échec.

#### Scenario: 503 puis 200
- **GIVEN** un projet et un fournisseur d'embeddings dont le premier appel d'un lot répond HTTP 503 et le suivant HTTP 200 avec des vecteurs valides
- **WHEN** le projet est indexé avec recherche sémantique active
- **THEN** l'indexation réussit et le projet est `READY`
- **AND** les index lexical, structurel et sémantique sont publiés, et le fournisseur a reçu deux appels pour ce lot

#### Scenario: Indisponibilité persistante
- **GIVEN** un fournisseur d'embeddings qui répond HTTP 503 à chaque appel
- **WHEN** le projet est indexé avec recherche sémantique active
- **THEN** l'indexation échoue par l'indisponibilité du fournisseur après le nombre maximal de reprises, et le projet est `FAILED`
- **AND** aucun index dérivé n'est committé et la génération canonique est inchangée

#### Scenario: Interruption pendant l'attente
- **GIVEN** un fournisseur d'embeddings qui répond HTTP 503, et le fil d'indexation interrompu pendant l'attente avant une reprise
- **WHEN** l'interruption survient
- **THEN** l'indexation s'arrête sans nouvel appel au fournisseur, le projet est `FAILED` et le fil reste marqué interrompu

### Requirement: Une erreur de contrat du fournisseur n'est pas reprise
Pendant l'indexation, une erreur de protocole ou de contrat du fournisseur d'embeddings (statut HTTP 4xx autre que 429, réponse illisible, nombre de vecteurs ou dimension incorrects, valeur non finie) SHALL NOT être reprise : l'indexation SHALL échouer au premier appel et laisser le projet `FAILED`.

#### Scenario: Dimension incorrecte
- **GIVEN** un fournisseur d'embeddings qui renvoie des vecteurs d'une dimension différente de celle de l'index
- **WHEN** le projet est indexé avec recherche sémantique active
- **THEN** l'indexation échoue par une erreur de contrat et le projet est `FAILED`
- **AND** le fournisseur n'a reçu qu'un appel et aucun index dérivé n'est committé

#### Scenario: HTTP 400
- **GIVEN** un fournisseur Ollama qui répond HTTP 400
- **WHEN** le projet est indexé avec recherche sémantique active
- **THEN** l'indexation échoue sans reprise et le projet est `FAILED`

### Requirement: Le délai Ollama par défaut est celui de la qualification réelle
Sans `NEXUS_OLLAMA_TIMEOUT_SECONDS`, le fournisseur Ollama SHALL appliquer un délai de 120 secondes par appel, valeur que retient la qualification sémantique réelle sur CPU, et la documentation de la recherche sémantique SHALL énoncer cette même valeur par défaut.

#### Scenario: Variable absente
- **WHEN** la recherche sémantique Ollama est activée sans `NEXUS_OLLAMA_TIMEOUT_SECONDS`
- **THEN** le fournisseur construit applique un délai de 120 secondes

#### Scenario: Variable fournie
- **WHEN** la recherche sémantique Ollama est activée avec `NEXUS_OLLAMA_TIMEOUT_SECONDS=30`
- **THEN** le fournisseur construit applique un délai de 30 secondes

#### Scenario: Valeur documentée divergente
- **GIVEN** une copie de travail où la valeur par défaut de `NEXUS_OLLAMA_TIMEOUT_SECONDS` dans `docs/developer/semantic-search.md` diffère de celle du code
- **WHEN** `bash .claude/scripts/contracts.sh` s'exécute
- **THEN** le contrat échoue
