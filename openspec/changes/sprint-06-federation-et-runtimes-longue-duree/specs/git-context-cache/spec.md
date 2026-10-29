# Spec Delta

## Purpose

Définit les caches Git des runtimes longue durée (contexte Git et récence des commits), dimensionnés pour le scope fédéré maximal et observables, afin qu'une requête répétée ne relise pas l'historique Git.

## ADDED Requirements

### Requirement: Le cache de contexte Git couvre le scope fédéré maximal
Le cache de contexte Git SHALL pouvoir conserver au moins une entrée par projet d'un scope fédéré de taille `FederatedScopePolicy.MAX_PROJECTS`.

#### Scenario: Requête fédérée répétée au-delà de 16 projets
- **WHEN** la même requête de contexte fédérée sur 20 projets inchangés est exécutée deux fois
- **THEN** chaque projet est servi par le cache au second passage

### Requirement: Les statistiques du cache Git sont exposées
Le mode `explain` d'une réponse de contexte SHALL exposer les compteurs de hits et de misses du cache de contexte Git.

#### Scenario: Explain après un hit
- **WHEN** une requête de contexte en mode `explain` est servie par le cache Git
- **THEN** les métadonnées `explain` contiennent des compteurs de hits et de misses dont le compteur de hits est au moins 1

### Requirement: La récence des commits est mise en cache par HEAD
L'enrichissement par récence Git SHALL réutiliser les chemins modifiés des derniers commits tant que le HEAD du dépôt ne change pas, dans la borne de chemins cumulés existante, et SHALL les recalculer quand HEAD change.

#### Scenario: HEAD inchangé
- **WHEN** deux recherches successives portent sur un dépôt dont HEAD n'a pas changé
- **THEN** la seconde n'ouvre pas le journal des commits

#### Scenario: Nouveau commit
- **WHEN** un commit modifiant `src/Nouveau.java` est ajouté entre deux recherches
- **THEN** la seconde recherche attribue un signal de récence à `src/Nouveau.java`
