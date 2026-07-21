# Runbook — Baseline de passage à l'échelle de l'Itération 16

Ce runbook complète [`large-scale-search.md`](large-scale-search.md) avec la procédure reproductible de collecte des métriques sur des repositories locaux réels.

## Prérequis

- Java 21 ;
- Maven disponible dans le `PATH` ;
- Git disponible dans le `PATH` pour le runner de portefeuille ;
- repositories sources accessibles localement, ou authentification Git opérationnelle pour les clones contrôlés ;
- aucune modification des repositories sources n'est nécessaire pour la baseline standard.

Le harness utilise un `NEXUS_HOME` temporaire créé par JUnit. Les données NEXUS de l'utilisateur ne sont donc pas réutilisées.

## Baseline sur des racines locales

Depuis la racine de NEXUS :

```powershell
.\scripts\measure-iteration-16-baseline.ps1 `
    -ProjectRoots @(
        "N:\workspace-dev\nexus-context-engine",
        "N:\workspace-dev\autre-repository"
    ) `
    -Queries @(
        "SearchService",
        "DatabaseMigrationManager"
    )
```

`-Query` reste accepté comme alias de compatibilité lorsqu'une seule requête est mesurée.

Le script exécute `LargeScaleSearchBaselineTest` avec les repositories et requêtes fournis. Il produit par défaut :

```text
target\iteration-16-baseline.json
```

Un autre chemin peut être fourni avec `-Output`, qu'il soit relatif à NEXUS ou absolu.

### Transport portable des paramètres

Le script ne concatène pas les racines et les requêtes dans la ligne de commande Maven. Il écrit une configuration JSON UTF-8 temporaire sous `target`, puis transmet uniquement son chemin via :

```text
-Dnexus.baseline.input=<fichier-json>
```

Cette approche évite que `cmd.exe`, utilisé indirectement par `mvn.cmd` sous Windows, interprète les caractères `|` comme des pipes de commande. Le fichier temporaire est supprimé dans le bloc `finally`, y compris après un échec du benchmark.

Les propriétés historiques `nexus.baseline.projects`, `nexus.baseline.queries` et `nexus.baseline.query` restent lues par le harness pour compatibilité avec les appels directs existants. Les scripts PowerShell constituent toutefois le chemin de référence.

## Portefeuille Java contrôlé

Le palier multi-repository de référence est défini dans :

```text
scripts/config/iteration-16-java-portfolio.json
```

Il contient :

- le checkout NEXUS courant ;
- `MediaUtilityTools` ;
- `collection-manager` ;
- `db-toolkit-core` ;
- cinq requêtes associées à des résultats attendus qualifiés par `repository:path`.

JARVIS et ses satellites sont explicitement exclus de ce portefeuille.

Commande :

```powershell
.\scripts\measure-iteration-16-portfolio.ps1
```

Le runner :

1. refuse de démarrer si le checkout NEXUS contient des modifications suivies non validées ;
2. conserve le checkout NEXUS courant comme première racine ;
3. clone les autres repositories dans `target\iteration-16-portfolio\repositories` ;
4. positionne chaque clone sur le commit exact défini dans le manifest ;
5. enregistre les commits réellement résolus dans `resolved-portfolio.json` ;
6. exécute le harness avec toutes les requêtes ;
7. calcule `precision@3` et `recall@3` sur le corpus réel du manifest ;
8. enrichit le rapport de performance avec les sources et les métriques de qualité.

Les clones contrôlés peuvent être recréés ou réutilisés. `git checkout --detach --force` et `git clean -ffd` ne s'appliquent qu'aux copies situées dans le workspace de benchmark. Les repositories de travail de l'utilisateur ne sont jamais modifiés.

Rapports produits :

```text
target\iteration-16-portfolio-baseline.json
target\iteration-16-portfolio\resolved-portfolio.json
```

L'accès aux repositories privés utilise l'authentification Git déjà configurée sur le poste, par exemple Git Credential Manager sous Windows.

## Métriques produites

Le rapport JSON contient :

- `repositoryCount` ;
- `queryCount` et `queries` ;
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
- les mêmes volumes principaux par projet ;
- des métriques de latence par requête ;
- jusqu'à cinq résultats qualifiés par `projectId`, type, chemin relatif et score pour chaque requête.

Le runner de portefeuille ajoute :

- le nom du portefeuille ;
- les URLs, références demandées et commits résolus ;
- le classement `repository:path` des trois premiers résultats ;
- `precision@3` et `recall@3` par requête et en moyenne.

Le protocole utilise, pour chaque requête :

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

Le corpus du portefeuille complète ces tests synthétiques avec des symboles et chemins issus de repositories réels. Il sert d'abord à observer la stabilité du ranking inter-projets ; aucun changement de poids ne doit être engagé sur une seule exécution.

## Mesure d'indexation incrémentale avec petit delta

Le harness standard ne modifie jamais les repositories sources. Il mesure donc l'indexation incrémentale **sans changement**.

Pour mesurer un petit delta de manière reproductible :

1. travailler sur une copie dédiée d'un repository de référence ;
2. exécuter une première baseline en conservant le même `NEXUS_HOME` de benchmark ;
3. appliquer un changement contrôlé et documenté, par exemple la modification d'un fichier et l'ajout d'un fichier ;
4. relancer l'indexation incrémentale ;
5. enregistrer le nombre de fichiers modifiés et la durée ;
6. réinitialiser ou supprimer la copie contrôlée après la mesure.

Cette mesure n'est pas automatisée dans le premier incrément afin de ne jamais altérer silencieusement les sources de l'utilisateur.

## Interprétation

Comparer plusieurs paliers, par exemple :

```text
1 repository
4 repositories du portefeuille Java
5 repositories
10 repositories
```

Les paliers doivent utiliser les repositories réellement pertinents pour NEXUS plutôt qu'un nombre artificiel de copies identiques.

Une dégradation observée sur une seule exécution ne justifie pas un nouveau backend. Il faut rechercher une tendance reproductible sur les volumes, la latence, la mémoire, la taille d'index et la qualité.

Zoekt ou OpenGrok ne doivent être évalués que si cette baseline met en évidence une limite concrète que l'architecture locale ne peut pas corriger simplement.
