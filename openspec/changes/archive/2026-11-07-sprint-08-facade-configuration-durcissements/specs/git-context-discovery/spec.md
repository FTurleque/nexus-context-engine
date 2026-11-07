# Spec Delta

## Purpose

Définit quel dépôt Git peut alimenter le contexte d'un projet, afin que l'historique, les diffs et la configuration Git d'un autre compte n'entrent jamais dans un `ContextBundle`.

## ADDED Requirements

### Requirement: La découverte Git est plafonnée à la racine du projet
Le moteur SHALL retenir sans condition un dépôt Git situé à la racine du projet enregistré. Un dépôt trouvé dans un répertoire parent de la racine SHALL n'être retenu que si son répertoire Git appartient à l'utilisateur qui exécute NEXUS.

#### Scenario: Dépôt à la racine
- **WHEN** le projet enregistré contient son propre répertoire `.git`
- **THEN** le contexte Git (historique récent, état de l'arbre de travail) est produit comme avant

#### Scenario: Sous-module d'un dépôt de l'utilisateur
- **WHEN** le projet enregistré est un sous-répertoire d'un dépôt dont le répertoire Git appartient à l'utilisateur courant
- **THEN** le contexte Git de ce dépôt est produit

#### Scenario: Dépôt parent d'un autre compte
- **WHEN** le projet enregistré n'a pas de `.git` et un répertoire parent contient un dépôt dont le répertoire Git appartient à un autre compte
- **THEN** aucun élément Git (commit, message, diff) n'apparaît dans le contexte et aucune configuration de ce dépôt n'est lue

### Requirement: Un dépôt écarté produit un diagnostic public
Lorsque le contexte Git est ignoré pour cause de propriétaire, le moteur SHALL l'indiquer par un diagnostic stable qui ne contient aucun chemin absolu.

#### Scenario: Diagnostic sans chemin
- **WHEN** le contexte Git d'un projet est ignoré parce que le dépôt parent appartient à un autre compte
- **THEN** le résultat de `explain` ou du contexte porte un diagnostic Git identifiable, sans chemin absolu ni nom de compte

### Requirement: Les trois usages de Git appliquent la même règle
Le contexte Git local, le contexte Git persistant des runtimes longue durée et l'enrichissement par récence SHALL appliquer la même règle de découverte.

#### Scenario: Runtime longue durée
- **WHEN** un runtime MCP ou REST longue durée sert un projet situé sous un dépôt parent d'un autre compte
- **THEN** ni le contexte Git ni le signal de récence n'utilisent ce dépôt
