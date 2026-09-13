# ADR-0054 — Grouper les lectures du registre pour les opérations fédérées

- Statut : Accepté
- Date : 2026-09-12
- Complète : ADR-0052

## Contexte

Les contrôles de cohérence ajoutés aux opérations fédérées relisaient chaque
descripteur avant, pendant et après la lecture. À 25 projets, cela ouvrait 75
connexions SQLite, avec autant de validations du stockage. Une tentative
d'acquisition des verrous sur des threads virtuels a également bloqué un essai
local et compliquait leur propriété.

## Décision

Le port `ProjectRepository.findByIds` charge uniquement les projets demandés,
sans doublons, dans l'ordre de la portée. L'adaptateur SQLite utilise une seule
connexion et un snapshot transactionnel pour les descripteurs, langues et
technologies. Les requêtes sont bornées à 500 identifiants par lot.

La façade conserve ses trois contrôles de disponibilité, désormais groupés.
Les verrous restent acquis séquentiellement par UUID et libérés dans l'ordre
inverse, sur le même thread propriétaire. Une acquisition partielle libère tous
les verrous déjà obtenus et conserve l'exception initiale.

## Conséquences

Une portée de 100 projets au plus requiert trois ouvertures du registre pour les
contrôles de façade, indépendamment du nombre de projets. Aucun cache de statut
ne peut masquer une mutation. Les protections de l'ADR-0052 restent applicables.

Les tests couvrent les métadonnées, l'ordre, les doublons, les UUID absents, la
frontière de lot et la libération des verrous après succès ou échec partiel.
