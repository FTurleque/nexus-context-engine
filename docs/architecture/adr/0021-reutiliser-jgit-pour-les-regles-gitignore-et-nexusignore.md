---
status: accepted
date: 2026-07-19
---

# ADR-0021 — Réutiliser JGit pour les règles `.gitignore` et `.nexusignore`

## Contexte et problème

Le scanner NEXUS doit éviter les fichiers ignorés par le projet, les contenus générés et les fichiers sensibles. La syntaxe `.gitignore` comporte des règles non triviales : jokers, `**`, chemins absolus relatifs au fichier d'ignore, négations et fichiers d'ignore imbriqués.

Réimplémenter cette syntaxe à l'aide de `PathMatcher` ou d'expressions régulières créerait rapidement des divergences avec Git. NEXUS possède en outre son propre fichier `.nexusignore`, qui doit volontairement utiliser la même grammaire pour éviter d'inventer une nouvelle syntaxe.

## Facteurs de décision

- fidélité à la sémantique Git ;
- réutilisation d'une bibliothèque Java mature ;
- support des `.gitignore` imbriqués ;
- même syntaxe pour `.nexusignore` ;
- règles intégrées de sécurité et de dossiers générés ;
- fonctionnement sans exécutable Git externe.

## Options envisagées

- implémenter une syntaxe d'ignore propriétaire ;
- convertir les patterns vers `java.nio.file.PathMatcher` ;
- invoquer `git check-ignore` ;
- réutiliser le moteur d'ignore de JGit dans le scanner local.

## Décision retenue

**Option retenue : utiliser JGit pour parser et évaluer les règles de type `.gitignore`, et appliquer la même grammaire aux fichiers `.nexusignore`.**

Le scanner maintient des règles scopées par répertoire. Lors de la traversée :

1. les règles héritées des répertoires parents sont évaluées ;
2. les `.gitignore` et `.nexusignore` du répertoire courant sont chargés pour ses descendants ;
3. la règle applicable la plus spécifique peut compléter ou annuler une règle précédente selon la sémantique Git ;
4. les règles intégrées NEXUS sont appliquées pour les dossiers générés et secrets manifestes.

Les règles intégrées couvrent au minimum les métadonnées/outils et sorties générées courantes : `.git`, `.idea`, `.gradle`, `target`, `build`, `out`, `node_modules`, `dist`, `coverage` et équivalents explicitement configurés.

Les fichiers sensibles évidents tels que `.env`, clés privées et conteneurs de certificats sont exclus par défaut. `.nexusignore` permet au projet d'ajouter ses propres exclusions.

L'utilisation de JGit ici concerne uniquement la **sémantique d'ignore** ; elle ne transforme pas NEXUS en client Git complet.

### Conséquences positives

- NEXUS évite de réimplémenter la grammaire Git ;
- `.nexusignore` est immédiatement compréhensible par les développeurs ;
- le scanner fonctionne en Java pur ;
- les règles imbriquées peuvent être prises en compte ;
- la logique d'exclusion est centralisée.

### Conséquences négatives et compromis acceptés

- JGit ajoute une dépendance de plusieurs mégaoctets pour une capacité ciblée ;
- il faut correctement gérer le scope de chaque `IgnoreNode` ;
- les exclusions de sécurité NEXUS ne sont pas exactement des règles Git et restent une couche supplémentaire.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Différence subtile avec le comportement du Git natif | Moyen | Tests de cas avec négations, `**` et fichiers imbriqués |
| Répertoire ignoré contenant une négation profonde | Moyen | Respecter la sémantique Git lors du choix de `SKIP_SUBTREE` |
| Sur-exclusion de fichiers légitimes par les règles de sécurité | Moyen | Liste intégrée conservatrice et `.nexusignore` explicite |
| Dépendance JGit utilisée hors de son rôle | Faible à moyen | Encapsuler la logique dans le package d'ignore/scanner |

### Confirmation

- aucun parser maison de la syntaxe `.gitignore` n'est introduit ;
- `.nexusignore` utilise la même syntaxe ;
- des tests couvrent règles racine, règles imbriquées et négations ;
- le scanner exclut les dossiers générés et secrets évidents ;
- l'exécutable `git` n'est pas requis pour scanner un projet.

## Analyse détaillée des options

### Syntaxe propriétaire

**Avantages :** contrôle total.

**Inconvénients :** nouvelle convention à apprendre et moteur à maintenir.

### `PathMatcher`

**Avantages :** aucune dépendance.

**Inconvénients :** ne reproduit pas correctement toute la sémantique `.gitignore`.

### `git check-ignore`

**Avantages :** comportement du Git natif.

**Inconvénients :** processus externe, Git obligatoire, appels coûteux pendant un scan massif.

### JGit

**Avantages :** Java pur, parser/matcher existant, bonne adéquation au projet.

**Inconvénients :** dépendance supplémentaire et gestion correcte du scope à implémenter.

## Conditions de réexamen

Réexaminer si JGit impose un niveau Java incompatible, si son API d'ignore devient instable ou si une bibliothèque plus légère offre une compatibilité Git mieux démontrée.

## Décisions liées

- ADR-0005 — Adopter un fonctionnement local-first et des intégrations externes opt-in.
