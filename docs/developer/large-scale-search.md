# Recherche multi-repository et passage à l'échelle

Ce document décrit l'état **final livré** de l'Itération 16 et les limites encore ouvertes après l'audit de consolidation du 29 juillet 2026.

## Statut

Itération 16 : **terminée et validée localement le 21 juillet 2026**.

NEXUS sait rechercher sur une liste explicite de projets locaux sans backend réseau ou moteur externe.

## Architecture livrée

```text
projectIds explicites
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
diversification par projectId + path
       │
       ▼
FederatedSearchHit[]
```

Invariants :

- aucun projet n'est ajouté implicitement ;
- les doublons de `projectId` dans la portée sont éliminés ;
- la provenance `ProjectDescriptor` est conservée ;
- deux projets avec le même chemin relatif restent deux résultats distincts ;
- chaque projet conserve son index Lucene propre ;
- aucun moteur externe n'est obligatoire ;
- le ranking mono-projet existant est réutilisé.

## Coordination lexicale

Pour une requête contenant au moins deux termes analysés uniques, Lucene impose une coordination minimale de deux termes afin de réduire les faux positifs à un seul terme.

Les champs restent pondérés : symbole, nom qualifié, chemin, termes de code et contenu.

## Baseline finale canonique

Corpus hermétique de sept repositories et huit requêtes :

```text
repositories               7
fichiers                    2 104
symboles                    10 878
relations                   10 087
index Lucene cumulé         5 121 497 octets
indexation complète         8 818 ms
incrémental sans changement 762 ms
recherche fédérée p50       133 ms
recherche fédérée p95       304 ms
contexte p50                48 ms
contexte p95                206 ms
precision@3                 0,4583
recall@3                    0,8958
hit@3                       1,0000
MRR@3                       1,0000
```

Le palier incrémental contrôlé sur `collection-manager` avait également montré environ `34,45×` entre reconstruction complète et petit delta.

Résultats détaillés :

- [`iteration-16-baseline-results.md`](iteration-16-baseline-results.md) ;
- [`iteration-16-extended-portfolio-results.md`](iteration-16-extended-portfolio-results.md) ;
- [`large-scale-baseline-runbook.md`](large-scale-baseline-runbook.md).

## Décision sur les moteurs externes

Les mesures de l'Itération 16 ne justifient pas :

- Zoekt ;
- OpenGrok ;
- index distant ;
- distribution de l'index ;
- parallélisation de la fédération ;
- changement supplémentaire des poids de ranking.

Lucene reste le moteur local par défaut.

Cette décision n'interdit pas une réévaluation future ; elle exige simplement une preuve mesurée avant d'ajouter une infrastructure plus lourde.

## Limite F01 — top-K après diversification

L'audit de consolidation a identifié une limite qui n'était pas couverte par la validation initiale.

Le service appelle actuellement :

```text
SearchService(project, query, limit)
```

pour chaque projet, puis diversifie les résultats par chemin.

Cas possible :

```text
limit = 10

rangs 1..10 d'un projet
→ plusieurs FILE/SYMBOL du même fichier

rang 11
→ autre fichier pertinent
```

La diversification peut supprimer plusieurs des dix premiers candidats sans jamais avoir récupéré le rang 11. Le résultat fédéré peut alors contenir moins de chemins uniques que demandé alors qu'il existait des candidats disponibles.

Ce défaut de retrieval est planifié en **Itération 18** avant toute nouvelle fonctionnalité fédérée.

## Limite F04/F05/F06 — scale symboles et graphe

La baseline actuelle reste modeste par rapport à un très grand monorepo.

Aujourd'hui :

- `SymbolSearchStrategy` charge les symboles du projet puis filtre/fuzzy en Java ;
- `NexusApplication.findSymbols` fait un filtrage projet-wide ;
- `NexusApplication.findUsages` charge les relations du projet ;
- `ProjectGraphBuilder` reconstruit le graphe depuis les symboles/relations à chaque recherche.

La prochaine étape de scale est donc de **cibler les requêtes SQLite/Lucene et réutiliser le graphe**, pas de remplacer Lucene.

Ce travail est planifié en **Itération 19**.

## Comparabilité inter-projets

Les scores Lucene sont normalisés dans le contexte d'un projet avant ranking. L'Itération 16 a validé la pertinence sur son corpus fédéré, mais toute évolution importante du ranking doit continuer à tester explicitement :

```text
precision@3
recall@3
hit@3
MRR@3
```

sur un corpus multi-repository avec provenance.

## Recherche fédérée et adaptateurs

La capacité existe dans la façade applicative :

```java
searchAcrossProjects(projectIds, query, limit, explain)
```

Les contrats CLI, REST et MCP courants ne l'exposent pas encore de façon homogène.

Cette exposition est planifiée en Itération 22, après correction du top-K et du gate de readiness. La logique restera dans `FederatedSearchService`, pas dans les adaptateurs.

## Contexte multi-projet

`DefaultContextBuilder` reste mono-projet.

Une ancienne PR draft #10 a expérimenté un contexte fédéré sous budget global, mais elle a été fermée sans merge et sans qualification locale finale.

Le besoin est replanifié en Itération 23 avec des prérequis plus stricts :

- recherche fédérée corrigée ;
- provenance stable ;
- budget global ;
- collisions de chemins traitées ;
- métriques de starvation ;
- politique explicite pour `INSTRUCTION`, `SKILL` et `GIT`.

## Quand réexaminer un moteur externe

Un moteur spécialisé devient un candidat seulement si une mesure reproductible démontre au moins un problème que les optimisations locales de Phase 6 ne résolvent pas :

- p95 de recherche non acceptable sur le portefeuille réel ;
- empreinte mémoire/disque non acceptable ;
- reconstruction locale trop coûteuse ;
- volume de symboles dépassant durablement les capacités des requêtes ciblées ;
- besoin réel d'un index distant/partagé ;
- gain de pertinence démontré par une capacité absente de Lucene/SQLite.

Même dans ce cas, l'intégration devra rester derrière un port NEXUS et être comparée à la baseline locale.

## Validation

Le runner historique de l'Itération 16 reste :

```powershell
.\scripts\validate-iteration-16.ps1
```

Les nouveaux travaux de Phase 6 devront ajouter leurs propres tests/benchmarks et rejouer les corpus historiques et fédérés.
