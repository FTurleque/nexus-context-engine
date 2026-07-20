# Runbook — Baseline de passage à l'échelle de l'Itération 16

Ce runbook complète [`large-scale-search.md`](large-scale-search.md) avec la procédure reproductible de collecte des métriques sur des repositories locaux réels.

## Prérequis

- Java 21 ;
- Maven disponible dans le `PATH` ;
- repositories sources accessibles localement ;
- aucune modification des repositories sources n'est nécessaire pour la baseline standard.

Le harness utilise un `NEXUS_HOME` temporaire créé par JUnit. Les données NEXUS de l'utilisateur ne sont donc pas réutilisées.

## Commande

Depuis la racine de NEXUS :

```powershell
.\scripts\measure-iteration-16-baseline.ps1 `
    -ProjectRoots @(
        "N:\workspace-dev\nexus-context-engine",
        "N:\workspace-dev\autre-repository"
    ) `
    -Query "SearchService"
```

Le script exécute `LargeScaleSearchBaselineTest` avec les repositories fournis et produit par défaut :

```text
target\iteration-16-baseline.json
```

Un autre chemin peut être fourni avec `-Output`.

## Métriques produites

Le rapport JSON contient :

- `repositoryCount` ;
- `totalFiles` ;
- `totalSymbols` ;
- `totalRelations` ;
- `totalLuceneIndexBytes` ;
- `totalFullIndexMs` ;
- `totalIncrementalNoChangeMs` ;
- `federatedSearchP50Ms` ;
- `federatedSearchP95Ms` ;
- `contextP50Ms` ;
- `contextP95Ms` ;
- `usedHeapBeforeBytes` ;
- `usedHeapAfterBytes` ;
- `usedHeapDeltaBytes` ;
- les mêmes volumes principaux par projet.

Le protocole utilise :

- 3 recherches fédérées d'échauffement ;
- 10 recherches fédérées mesurées ;
- 1 construction de contexte d'échauffement par projet ;
- 3 constructions de contexte mesurées par projet ;
- un budget de contexte de 1 200 tokens.

Ces paramètres sont constants afin de rendre deux exécutions comparables sur la même machine.

## Qualité de recherche

La validation standard exécute deux baselines de qualité :

```text
GoldenSearchCorpusTest
FederatedGoldenSearchCorpusTest
```

La première protège la baseline historique mono-projet.

La seconde utilise des identités qualifiées par la provenance :

```text
projectId:relativePath
```

Elle vérifie que la fédération conserve au minimum :

```text
mean precision@3 >= 0,44
mean recall@3 = 1,00
```

Ce petit corpus fédéré protège le contrat technique. Il ne remplace pas un corpus métier multi-repository réel, qui devra être ajouté avant toute décision de tuning du ranking ou d'adoption d'un moteur externe.

## Mesure d'indexation incrémentale avec petit delta

Le harness standard ne modifie jamais les repositories sources. Il mesure donc l'indexation incrémentale **sans changement**.

Pour mesurer un petit delta de manière reproductible :

1. travailler sur une copie dédiée d'un repository de référence ;
2. exécuter une première baseline ;
3. appliquer un changement contrôlé et documenté, par exemple la modification d'un fichier et l'ajout d'un fichier ;
4. relancer l'indexation incrémentale sur le même `NEXUS_HOME` de benchmark ;
5. enregistrer le nombre de fichiers modifiés et la durée.

Cette mesure n'est pas automatisée dans le premier incrément afin de ne jamais altérer silencieusement les sources de l'utilisateur.

## Interprétation

Comparer plusieurs paliers, par exemple :

```text
1 repository
3 repositories
5 repositories
10 repositories
```

Les paliers doivent utiliser les repositories réellement pertinents pour NEXUS plutôt qu'un nombre artificiel de copies identiques.

Une dégradation observée sur une seule exécution ne justifie pas un nouveau backend. Il faut rechercher une tendance reproductible sur les volumes, la latence, la mémoire, la taille d'index et la qualité.

Zoekt ou OpenGrok ne doivent être évalués que si cette baseline met en évidence une limite concrète que l'architecture locale ne peut pas corriger simplement.
