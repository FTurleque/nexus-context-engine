---
status: accepted
date: 2026-07-19
---

# ADR-0023 — Utiliser SHA-256 pour détecter les changements de fichiers

## Contexte et problème

Une réindexation complète de tous les fichiers à chaque exécution serait coûteuse et inutile. NEXUS doit identifier rapidement les fichiers nouveaux, modifiés, inchangés ou supprimés afin de limiter le parsing Java, les écritures SQLite et les mises à jour Lucene.

Se fier uniquement à la date de modification ou à la taille est rapide mais peut produire des faux négatifs, notamment lors de copies, restaurations, changements d'horloge ou outils préservant les timestamps.

## Facteurs de décision

- déterminisme ;
- fonctionnement local ;
- détection fiable des changements de contenu ;
- coût acceptable pour un scan local ;
- simplicité de persistance ;
- possibilité d'éviter l'analyse AST lorsque le contenu n'a pas changé.

## Options envisagées

- date de modification uniquement ;
- taille + date de modification ;
- CRC ou hash rapide non cryptographique ;
- SHA-256 du contenu ;
- stratégie hybride metadata-first puis SHA-256.

## Décision retenue

**Option retenue : utiliser SHA-256 comme empreinte canonique du contenu, avec possibilité d'optimiser ultérieurement le calcul via une stratégie metadata-first mesurée.**

Chaque fichier indexé persiste :

- `sizeBytes` ;
- `modifiedAt` ;
- `contentHash` SHA-256.

Pour le MVP, le scanner calcule SHA-256 des fichiers éligibles. Un fichier dont l'empreinte est identique à celle persistée peut conserver ses symboles et son document Lucene sans nouvelle analyse AST.

Les timestamps et la taille restent des métadonnées utiles mais ne constituent pas seuls la preuve canonique d'identité du contenu.

Les fichiers disparus lors du scan sont supprimés de l'index structurel et de Lucene.

### Conséquences positives

- détection déterministe des changements réels ;
- réindexation idempotente ;
- réduction du parsing des fichiers inchangés ;
- hash portable et facilement inspectable ;
- base future pour la déduplication de contenu.

### Conséquences négatives et compromis acceptés

- tous les octets des fichiers éligibles doivent être lus pour calculer le hash ;
- le coût peut devenir significatif sur de très gros repositories ;
- SHA-256 est plus coûteux qu'un hash non cryptographique.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Scan lent sur gros repository | Moyen | Mesurer ; introduire metadata-first ou cache uniquement si nécessaire |
| Fichiers binaires volumineux hashés inutilement | Moyen | Scanner uniquement les types de contexte éligibles et appliquer les exclusions avant hash |
| Hash calculé mais analyse échouée | Moyen | Conserver un statut d'indexation permettant de retenter l'analyse |
| Collision | Très faible | SHA-256 fournit un risque négligeable pour cet usage local |

### Confirmation

- le hash persisté est SHA-256 en représentation hexadécimale ;
- deux indexations sans modification ne reparsent pas les fichiers inchangés ;
- un changement de contenu est détecté même si le nom reste identique ;
- les suppressions sont propagées aux stockages.

## Analyse détaillée des options

### Timestamp uniquement

**Avantages :** extrêmement rapide.

**Inconvénients :** peu fiable comme preuve de contenu.

### Taille + timestamp

**Avantages :** bon filtre rapide.

**Inconvénients :** toujours vulnérable aux métadonnées préservées ou trompeuses.

### Hash rapide non cryptographique

**Avantages :** performances supérieures.

**Inconvénients :** dépendance ou implémentation supplémentaire, collisions plus probables et aucun besoin de performance démontré à ce stade.

### SHA-256

**Avantages :** standard, déterministe, disponible dans le JDK et suffisamment robuste.

**Inconvénients :** nécessite lecture complète du fichier.

### Metadata-first puis SHA-256

**Avantages :** évite potentiellement de relire certains fichiers.

**Inconvénients :** complexifie la garantie qu'un fichier est réellement inchangé. Cette optimisation doit être introduite uniquement avec des métriques.

## Conditions de réexamen

Réexaminer si les métriques d'indexation montrent que le calcul des hashes représente une part dominante du temps sur les repositories cibles.

## Décisions liées

- ADR-0006 — Utiliser SQLite comme source de vérité structurelle locale.
- ADR-0022 — Traiter Lucene comme un index dérivé et reconstructible de SQLite.
