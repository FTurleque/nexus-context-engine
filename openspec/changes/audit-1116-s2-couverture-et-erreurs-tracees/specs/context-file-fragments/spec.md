# Spec Delta

## Purpose

Définit quelles lignes d'un fichier entrent dans un contexte quand aucun candidat symbole ne cible ce fichier : le fichier entier sous un seuil de tokens, sinon des fenêtres autour des termes de la requête, fusionnées et plafonnées, avec un repli sur le début du fichier.
Documentation durable : docs/developer/context-building.md#openspec-context-file-fragments

## ADDED Requirements

### Requirement: Un fichier sous le seuil plein est servi entier
Un fichier sans candidat symbole dont l'estimation ne dépasse pas `max(120, min(800, budget / 4))` tokens, `budget` étant le budget de tokens de la demande, SHALL produire un seul fragment qui couvre ses lignes de 1 à la dernière.

#### Scenario: Petit fichier
- **GIVEN** un fichier de 10 lignes courtes et un budget de 4 000 tokens
- **WHEN** ses fragments sont construits
- **THEN** un seul fragment est produit, de la ligne 1 à la ligne 10

### Requirement: Au-delà du seuil, chaque ligne qui contient un terme ouvre une fenêtre de cinq lignes de part et d'autre
Pour un fichier au-dessus du seuil plein, une ligne qui contient, sans tenir compte de la casse, un terme de la requête d'au moins 2 caractères SHALL ouvrir une fenêtre de la ligne `n - 5` à la ligne `n + 5`, bornée à la première et à la dernière ligne du fichier. Un terme d'un seul caractère SHALL être ignoré.

#### Scenario: Terme au milieu du fichier
- **GIVEN** un fichier de 200 lignes au-dessus du seuil dont seule la ligne 100 contient `refreshToken`
- **WHEN** ses fragments sont construits pour la requête `refreshtoken`
- **THEN** un seul fragment est produit, des lignes 95 à 105

#### Scenario: Termes près des bords
- **GIVEN** un fichier de 200 lignes au-dessus du seuil dont seules les lignes 2 et 199 contiennent le terme
- **WHEN** ses fragments sont construits
- **THEN** deux fragments sont produits, des lignes 1 à 7 et des lignes 194 à 200

#### Scenario: Terme d'un seul caractère
- **GIVEN** un fichier au-dessus du seuil où le caractère `x` apparaît sur de nombreuses lignes et où aucune ligne ne contient `zz`
- **WHEN** ses fragments sont construits pour la requête `x zz`
- **THEN** le résultat est celui d'une requête sans correspondance

### Requirement: Les fenêtres qui se chevauchent ou se touchent sont fusionnées
Deux fenêtres d'un même fichier dont la seconde commence au plus une ligne après la fin de la première SHALL être fusionnées en un seul fragment qui couvre les deux.

#### Scenario: Deux occurrences proches
- **GIVEN** un fichier de 200 lignes au-dessus du seuil dont seules les lignes 50 et 56 contiennent le terme
- **WHEN** ses fragments sont construits
- **THEN** un seul fragment est produit, des lignes 45 à 61

#### Scenario: Fenêtres contiguës
- **GIVEN** un fichier de 200 lignes au-dessus du seuil dont seules les lignes 50 et 61 contiennent le terme
- **WHEN** ses fragments sont construits
- **THEN** un seul fragment est produit, des lignes 45 à 66

#### Scenario: Fenêtres séparées
- **GIVEN** un fichier de 200 lignes au-dessus du seuil dont seules les lignes 50 et 62 contiennent le terme
- **WHEN** ses fragments sont construits
- **THEN** deux fragments sont produits, des lignes 45 à 55 et des lignes 57 à 67

### Requirement: Au plus quatre lignes correspondantes ouvrent une fenêtre
Seules les quatre premières lignes correspondantes, dans l'ordre du fichier, SHALL ouvrir une fenêtre, avant fusion ; les lignes correspondantes suivantes SHALL NOT produire de fragment.

#### Scenario: Cinq occurrences éloignées
- **GIVEN** un fichier de 300 lignes au-dessus du seuil dont seules les lignes 20, 80, 140, 200 et 260 contiennent le terme
- **WHEN** ses fragments sont construits
- **THEN** quatre fragments sont produits, autour des lignes 20, 80, 140 et 200, et aucun fragment ne contient la ligne 260

#### Scenario: Occurrences proches comptées avant fusion
- **GIVEN** un fichier de 300 lignes au-dessus du seuil dont seules les lignes 20, 21, 22, 23 et 200 contiennent le terme
- **WHEN** ses fragments sont construits
- **THEN** un seul fragment est produit, des lignes 15 à 28, et aucun fragment ne contient la ligne 200

### Requirement: Sans correspondance, les quarante premières lignes sont servies
Pour un fichier au-dessus du seuil plein dont aucune ligne ne contient de terme retenu, un seul fragment SHALL couvrir les lignes 1 à `min(40, nombre de lignes)`.

#### Scenario: Aucun terme présent
- **GIVEN** un fichier de 200 lignes au-dessus du seuil qui ne contient aucun terme de la requête
- **WHEN** ses fragments sont construits
- **THEN** un seul fragment est produit, des lignes 1 à 40
