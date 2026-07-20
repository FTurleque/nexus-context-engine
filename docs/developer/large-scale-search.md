# Recherche à grande échelle — Itération 16

Ce document décrit le premier incrément de l'Itération 16 et le protocole de mesure à utiliser avant toute évaluation d'un moteur de recherche externe.

## 1. État de l'architecture au démarrage

NEXUS savait déjà gérer plusieurs projets dans son stockage :

- `ProjectRegistry` enregistre plusieurs `ProjectDescriptor` ;
- SQLite conserve les fichiers, symboles et relations avec une portée `projectId` ;
- `ProjectIndexingService` indexe un projet identifié ;
- `LuceneSearchIndex` utilise un répertoire d'index distinct par `projectId` ;
- la clé Lucene d'un document contient `(projectId, relativePath)`.

La limite se situait au niveau orchestration :

- `SearchService.search(...)` reçoit un seul `ProjectDescriptor` ;
- `NexusApplication.search(...)` reçoit un seul `projectId` ;
- `DefaultContextBuilder` construit un contexte pour un seul projet.

Il n'était donc pas nécessaire de remplacer Lucene pour commencer le multi-repository.

## 2. Premier incrément retenu

Le premier incrément ajoute une fédération locale au-dessus du moteur existant :

```text
liste explicite de projectId
          │
          ▼
NexusApplication.searchAcrossProjects(...)
          │
          ▼
FederatedSearchService
          │
          ├── SearchService(Project A)
          ├── SearchService(Project B)
          └── SearchService(Project C)
                    │
                    ▼
           fusion déterministe
                    │
                    ▼
           FederatedSearchHit[]
           - projet d'origine
           - RankedCandidate
```

Invariants :

- aucun projet n'est ajouté implicitement à la portée ;
- les doublons de `projectId` dans la portée sont ignorés ;
- deux résultats de projets différents restent distincts, même avec le même chemin relatif ;
- le résultat conserve le `ProjectDescriptor` d'origine ;
- chaque projet conserve son index Lucene isolé ;
- `SearchService`, `DeterministicContextRanker` et `DefaultContextBuilder` ne sont pas modifiés ;
- aucune dépendance réseau ou moteur externe n'est ajoutée.

## 3. Limites connues à mesurer

### 3.1 Coût linéaire par nombre de projets

La première implémentation exécute une recherche par projet sélectionné. Aucun parallélisme n'est introduit avant mesure.

La baseline doit déterminer si ce coût devient significatif et à partir de combien de projets ou de documents.

### 3.2 Comparabilité du ranking inter-projets

Les signaux Lucene sont normalisés dans `LuceneFileSearchStrategy` par rapport au meilleur score du projet courant. Les scores finaux sont donc déterministes, mais leur comparabilité entre projets doit être validée sur un corpus multi-repository.

Il ne faut pas modifier les poids du ranking avant d'avoir mesuré `precision@3` et `recall@3` sur ce corpus.

### 3.3 Recherche structurelle SQLite

`SymbolSearchStrategy` interroge les symboles canoniques du projet. L'ADR-0024 identifie déjà la recherche fuzzy à très fort volume de symboles comme un point de réexamen.

La baseline doit séparer le volume de fichiers du volume de symboles et de relations.

### 3.4 Contexte multi-projet

`DefaultContextBuilder` reste projet-local. Cette limitation est volontaire pour le premier incrément.

Les sources suivantes dépendent du projet :

- instructions natives ;
- Agent Skills locaux ;
- contexte Git ;
- chemins absolus des fragments.

Une future construction de contexte fédérée devra définir explicitement :

- un budget de tokens global ;
- la provenance de chaque item ;
- la répartition du budget entre projets ;
- la déduplication inter-projets ;
- le traitement des instructions, skills et signaux Git de plusieurs racines.

## 4. Baseline reproductible

La baseline de l'Itération 16 doit être exécutée sur plusieurs paliers de volume. Les seuils exacts ne doivent pas être inventés à l'avance : ils doivent correspondre aux repositories réellement visés par NEXUS.

Pour chaque palier, relever :

| Métrique | Portée |
|---|---|
| nombre de repositories | total sélectionné |
| nombre de fichiers | par projet + total |
| nombre de symboles | par projet + total |
| nombre de relations | par projet + total |
| taille de l'index Lucene | par projet + total |
| indexation complète | par projet + total |
| indexation incrémentale sans changement | par projet + total |
| indexation incrémentale avec petit delta | par projet + total |
| recherche locale | p50 / p95 après échauffement |
| recherche fédérée | p50 / p95 selon nombre de projets |
| construction du contexte | p50 / p95 |
| `precision@3` | corpus projet-local + corpus fédéré |
| `recall@3` | corpus projet-local + corpus fédéré |
| mémoire | heap JVM avant/après et pic si reproductible |

### 4.1 Protocole de latence

Pour chaque requête de référence :

1. exécuter quelques appels d'échauffement non comptabilisés ;
2. exécuter plusieurs répétitions ;
3. conserver au minimum p50 et p95 ;
4. distinguer recherche mono-projet et fédérée ;
5. conserver la même machine et la même JVM pour comparer deux runs.

Une seule durée observée ne constitue pas une preuve de passage à l'échelle.

### 4.2 Corpus qualité multi-repository

Le corpus doit identifier les résultats attendus avec une clé de provenance :

```text
projectId + relativePath
```

Deux repositories contenant le même chemin relatif doivent donc produire deux identités différentes.

Les métriques `precision@3` et `recall@3` doivent être calculées sur les résultats fédérés sans supprimer cette provenance.

## 5. Quand réexaminer Lucene

Lucene reste le moteur local par défaut.

Une évaluation Zoekt/OpenGrok devient pertinente uniquement si la baseline montre un problème reproductible que l'architecture locale ne corrige pas simplement, par exemple :

- latence fédérée trop élevée au nombre de repositories réellement visé ;
- reconstruction Lucene trop lente ou trop coûteuse ;
- empreinte disque ou mémoire non acceptable ;
- recherche de symboles SQLite dominante à très fort volume ;
- besoin réel d'index distants ;
- dégradation de qualité du ranking fédéré nécessitant une capacité qu'un backend spécialisé apporte mieux.

L'apparition d'un de ces symptômes déclenche une comparaison, pas une adoption automatique.

## 6. Zoekt et OpenGrok

Aucune comparaison détaillée n'est réalisée dans ce premier incrément, car aucune métrique actuelle ne démontre encore le besoin d'un moteur externe.

Si la baseline déclenche cette évaluation, elle devra utiliser des sources récentes et comparer au minimum :

- maintenance et activité du projet ;
- performances d'indexation et de recherche ;
- indexation incrémentale ;
- langages pris en charge ;
- API ;
- déploiement local ;
- support Windows natif ou via Linux/Docker ;
- consommation mémoire ;
- complexité opérationnelle ;
- licence ;
- capacité à rester optionnel derrière un port/adaptateur NEXUS.

## 7. Compatibilité REST et MCP

La fédération est placée dans le cœur applicatif, sous les adaptateurs. REST et MCP pourront donc l'exposer ultérieurement sans dupliquer la logique.

Le premier incrément ne modifie pas encore leurs contrats publics. Cette évolution doit être faite après validation locale de la sémantique multi-projet et de la provenance.

## 8. Validation du premier incrément

La validation attendue est :

```text
mvn clean install
scripts/self-smoke.ps1
scripts/validate-iteration-16.ps1
```

Le script dédié couvre la régression du corpus golden et la fédération explicite multi-projet. Les mesures de volume réel doivent ensuite être ajoutées au compte rendu de l'Itération 16 à partir des repositories retenus pour la baseline.
