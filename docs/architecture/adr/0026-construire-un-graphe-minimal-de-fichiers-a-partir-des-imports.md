---
status: accepted
date: 2026-07-19
---

# ADR-0026 — Construire un graphe minimal de fichiers à partir des imports résolus

## Contexte et problème

Le ranking lexical identifie les fichiers qui contiennent directement les termes de la requête, mais certains fichiers importants sont seulement reliés structurellement à ces résultats. NEXUS doit donc disposer d'un signal de proximité dans le graphe.

À ce stade, JavaParser extrait déjà les imports et les types définis. NEXUS ne dispose pas encore de résolution sémantique profonde, d'appels de méthodes complets ni d'un index SCIP. Construire dès maintenant un graphe d'appels exhaustif serait coûteux et fragile.

Aider montre l'intérêt d'un graphe de fichiers pondéré par les relations entre définitions et références, puis d'un ranking de type PageRank personnalisé pour sélectionner les parties les plus importantes du repository. NEXUS souhaite réutiliser ce principe sans dépendre du code d'Aider et sans prétendre disposer dès le MVP du même niveau de relations. Le RepoMap d'Aider construit notamment un graphe où les fichiers sont des nœuds, les dépendances créent des arêtes et la pertinence de la conversation peut influencer la personnalisation du ranking. Les éléments les mieux classés sont ensuite sélectionnés sous budget.

## Facteurs de décision

- disposer rapidement d'un signal structurel utile ;
- utiliser uniquement des données déjà fiables dans le MVP ;
- éviter de simuler une précision sémantique inexistante ;
- permettre l'enrichissement futur par SCIP/JDT ;
- produire un graphe déterministe ;
- garder le coût raisonnable sur un repository local ;
- mesurer le gain par rapport au ranking lexical seul.

## Options envisagées

- ne pas utiliser de graphe avant SCIP ;
- construire un graphe complet d'appels et de références avec JavaParser ;
- construire un graphe minimal de fichiers à partir des imports résolus vers les types indexés ;
- copier directement l'algorithme RepoMap d'Aider.

## Décision retenue

**Option retenue : construire un graphe minimal orienté entre fichiers à partir des imports résolus vers les types définis dans le repository.**

Le pipeline est :

```text
Types indexés
qualifiedName → definingFile

Imports indexés
sourceFile → importedQualifiedName

Résolution
sourceFile → targetFile

Graphe
node = relativePath
edge = import résolu
```

Les imports externes qui ne correspondent à aucun type du repository sont ignorés pour le graphe interne.

Le signal initial `graphScore` sera calculé à partir de la proximité avec les fichiers déjà pertinents et de l'importance structurelle locale. L'implémentation peut commencer par une propagation bornée sur un ou deux sauts. Un PageRank personnalisé pourra être ajouté seulement si les benchmarks montrent qu'il améliore `precision@K` ou `recall@K`.

Cette décision est volontairement plus simple que RepoMap : NEXUS adopte le **principe** d'un ranking structurel par graphe, mais attend des relations plus riches avant de reproduire une propagation globale plus sophistiquée. Aider utilise des définitions/références issues de Tree-sitter et pondère son graphe avant un PageRank ; NEXUS ne dispose pour l'instant que des imports Java fiables.

### Conséquences positives

- un signal de proximité structurelle est disponible dès l'Itération 2 ;
- le graphe repose sur des données déjà persistées ;
- le comportement reste déterministe ;
- les fichiers indirectement pertinents peuvent remonter ;
- l'architecture pourra accueillir plus tard appels, références et implémentations ;
- la complexité reste compatible avec le MVP.

### Conséquences négatives et compromis acceptés

- les imports ne représentent pas tous les liens sémantiques ;
- les dépendances dans le même package sans import explicite peuvent être manquées ;
- les appels dynamiques et l'injection ne sont pas capturés ;
- le graphe initial sera moins riche que celui d'Aider ou d'un index SCIP.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Faux sentiment de précision sémantique | Élevé | Nommer et documenter le graphe comme `minimal` et conserver la provenance des relations |
| Imports non résolus | Moyen | Les ignorer sans générer d'arête artificielle |
| Graphe trop pauvre pour améliorer le ranking | Moyen | Comparer les métriques avec et sans `graphScore` |
| Propagation de bruit | Moyen | Limiter la profondeur et le poids initial du signal |
| Évolution future vers SCIP difficile | Faible | Construire le graphe à partir de contrats `SymbolRelation` génériques |

### Confirmation

La décision est respectée si :

- seules les relations internes résolues créent des arêtes ;
- le graphe est reconstruit de manière déterministe à partir de SQLite ;
- `graphScore` reste une composante séparée et explicable ;
- les benchmarks comparent ranking avec et sans graphe ;
- l'ajout futur de relations SCIP ne nécessite pas de modifier le contrat du ranker.

## Analyse détaillée des options

### Attendre SCIP

**Avantages :** graphe potentiellement plus précis.

**Inconvénients :** aucune expérimentation structurelle avant plusieurs itérations et dépendance à une brique optionnelle future.

### Graphe complet avec JavaParser

**Avantages :** davantage de relations immédiatement.

**Inconvénients :** résolution de symboles et classpath complexes, coût important, risque d'inexactitude.

### Graphe minimal basé sur les imports

**Avantages :** simple, déterministe, déjà alimenté par les données du MVP et extensible.

**Inconvénients :** couverture structurelle partielle.

### Copier RepoMap

**Avantages :** algorithme éprouvé pour la sélection de contexte de code.

**Inconvénients :** modèle de données différent, dépendances et hypothèses différentes, risque de recopier un mécanisme non adapté aux sources futures de NEXUS.

## Impacts sur l'architecture

```text
IndexRepository
  ├── symbols
  └── relations
       │
       ▼
ProjectGraphBuilder
       │
       ▼
ProjectGraph
       │
       ▼
GraphSearchStrategy
       │
       ▼
graphScore
```

## Conditions de réexamen

Réexaminer lorsque :

- SCIP ou JDT fournit des références fiables ;
- le corpus montre que le graphe d'imports n'améliore pas la qualité ;
- le repository multi-langage nécessite des types de relations supplémentaires ;
- un PageRank personnalisé démontre un gain mesurable par rapport à la propagation locale.

## Décisions liées

- ADR-0009 — Rendre l'intelligence de code extensible via des providers et index externes.
- ADR-0010 — Adopter un ranking hybride, déterministe et explicable.
- ADR-0024 — Combiner Lucene et SQLite pour la recherche de candidats.
- ADR-0025 — Normaliser les signaux et calculer un score composé explicable.

## Références

- Aider Repository Map : https://aider.chat/docs/repomap.html
- Aider RepoMap implementation : https://github.com/paul-gauthier/aider/blob/main/aider/repomap.py
