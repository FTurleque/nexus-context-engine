# ADR-0035 — Intégrer le contexte Git local comme source bornée et explicable

- Statut : `accepted`
- Date : 2026-07-20

## Contexte et problème

NEXUS sait déjà sélectionner du code, de la documentation, des instructions natives et des Agent Skills. Il ne tient toutefois pas encore compte de l'historique Git local du repository.

Cet historique peut apporter des signaux utiles :

- un fichier récemment modifié est parfois plus pertinent qu'un fichier lexicalement proche mais ancien ;
- les commits récents qui touchent un fichier sélectionné peuvent expliquer son évolution ;
- un diff local peut être directement lié à la demande en cours ;
- des fichiers fréquemment modifiés ensemble peuvent révéler une relation structurelle absente du graphe d'imports.

Le risque est de transformer NEXUS en client Git généraliste, d'ajouter du contexte historique sans rapport avec la demande ou de consommer une part disproportionnée du budget.

## Facteurs de décision

- Fonctionnement local-first et hors ligne.
- Lecture seule du repository Git.
- Aucun `fetch`, `pull`, `push`, `checkout` ou commit déclenché par NEXUS.
- Réutilisation de JGit déjà présent dans le projet.
- Contexte Git ciblé uniquement sur les fichiers remontés par la recherche.
- Budget Git séparé et borné.
- Ranking déterministe et explicable.
- Préservation des résultats existants lorsqu'aucun repository Git n'est disponible.
- Pas de persistance canonique de l'historique Git dans SQLite pour cette première itération.

## Options envisagées

### Option A — Indexer l'intégralité de l'historique Git dans SQLite et Lucene

Avantages :

- recherche rapide sur l'historique ;
- possibilité d'analyses riches hors du repository Git.

Inconvénients :

- forte augmentation du volume indexé ;
- synchronisation complexe avec les réécritures d'historique ;
- duplication d'une source déjà disponible localement ;
- surdimensionné pour le besoin actuel.

### Option B — Ajouter tout l'historique récent au `ContextBundle`

Avantages :

- implémentation simple ;
- contexte historique riche.

Inconvénients :

- bruit important ;
- explosion du budget ;
- commits sans rapport avec la tâche ;
- mauvaise explicabilité de la sélection.

### Option C — Provider Git local ciblé + enrichissement de ranking borné

Avantages :

- lecture à la demande ;
- aucun stockage Git supplémentaire ;
- historique limité aux fichiers candidats ;
- bonus de récence séparé et explicable ;
- budget Git spécifique ;
- fonctionnement inchangé hors repository Git.

Inconvénients :

- lecture de l'historique à chaque requête ;
- coût dépendant de la taille et de la profondeur du repository ;
- les renommages complexes et historiques très anciens restent volontairement limités.

## Décision

Nous retenons l'option C.

NEXUS introduit deux responsabilités distinctes :

```text
Recherche
→ GitRecencyCandidateEnricher
→ ajoute un signal gitRecencyScore aux candidats connus
→ bonus de ranking plafonné

Construction du contexte
→ GitContextSourceProvider
→ analyse les chemins les mieux classés
→ produit des fragments GIT bornés
```

Le provider initial est local et basé sur JGit.

### Données Git retenues

Le contexte Git peut contenir :

1. les commits récents qui touchent les chemins cibles ;
2. un historique court des principaux fichiers cibles ;
3. un diff local pertinent, limité aux chemins cibles ;
4. les fichiers fréquemment modifiés avec les chemins cibles dans les commits récents.

Les données sont lues depuis le repository local uniquement.

### Bornes initiales

Le provider analyse au maximum :

- 50 commits récents pour le signal de récence et les co-changements ;
- 5 chemins cibles pour l'historique détaillé ;
- 5 commits par historique de fichier ;
- 8 relations de co-changement ;
- un diff local borné en taille.

Ces limites sont des paramètres d'implémentation révisables après mesure.

### Signal de récence

Le signal `gitRecencyScore` est normalisé entre `0` et `1` selon la position d'un fichier dans la fenêtre des commits récents.

Le ranking applique un bonus maximal de `0,05` :

```text
gitRecencyContribution = gitRecencyScore × 0,05
```

Ce bonus s'ajoute au score historique existant. Le score de ranking est un score relatif, pas une probabilité ; il peut donc dépasser `1,0` dans le cas théorique où toutes les composantes sont maximales.

Un candidat sans historique Git local conserve exactement son score précédent.

### Budget Git

Le contexte Git est sélectionné après les instructions et les skills, avant le contexte de tâche.

Il est désactivé pour les budgets globaux inférieurs à 500 tokens.

Pour les autres budgets :

```text
gitBudget = min(
    budget restant,
    500,
    max(64, budget total × 15 %)
)
```

Les fragments Git peuvent être tronqués par le sélecteur générique lorsqu'ils sont trop volumineux. Contrairement aux Agent Skills, ils ne constituent pas une procédure atomique.

### Absence de repository Git

Si le projet n'est pas contenu dans un repository Git valide :

- aucun bonus de récence n'est ajouté ;
- aucun fragment Git n'est produit ;
- aucune erreur fonctionnelle n'est levée ;
- un diagnostic peut être exposé en mode explicable.

## Conséquences

### Positives

- Le ranking peut valoriser les fichiers récemment actifs sans dépendre d'un LLM.
- Le `ContextBundle` peut expliquer les changements récents liés aux fichiers sélectionnés.
- Les changements locaux non commités peuvent être visibles lorsqu'ils concernent les chemins cibles.
- Les co-changements ajoutent un signal structurel complémentaire au graphe d'imports.
- Aucun historique Git n'est dupliqué dans SQLite.

### Négatives

- Une requête sur un grand repository peut nécessiter une lecture supplémentaire de l'historique.
- Le signal de récence peut favoriser un fichier actif mais non pertinent ; le poids reste donc volontairement faible.
- La première version ne suit pas exhaustivement les renommages sur toute l'histoire.
- Les co-changements sont corrélatifs et ne prouvent pas une dépendance métier.

## Confirmation du respect de la décision

La décision est respectée si les tests démontrent que :

1. un projet non Git conserve le ranking historique ;
2. un fichier récemment modifié reçoit un `gitRecencyScore` explicable ;
3. le contexte Git ne contient que des chemins liés aux candidats ;
4. un diff non lié n'est pas injecté ;
5. les co-changements sont bornés ;
6. le budget Git ne fait jamais dépasser le budget global ;
7. aucun appel réseau ni mutation Git n'est effectué ;
8. le self-smoke construit un bundle contenant au moins un item `GIT` sur le repository NEXUS.

## Conditions de réexamen

Cette décision pourra être réexaminée si :

- les mesures montrent que la lecture Git à la demande devient trop coûteuse ;
- la persistance d'un index Git local apporte un gain mesurable ;
- le suivi des renommages doit devenir exhaustif ;
- le bonus de récence dégrade la précision du corpus de référence ;
- un provider Git distant devient nécessaire dans une future itération multi-repository.
