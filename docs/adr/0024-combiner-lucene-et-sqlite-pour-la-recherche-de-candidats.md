---
status: accepted
date: 2026-07-19
---

# ADR-0024 — Combiner Lucene et SQLite pour la recherche de candidats

## Contexte et problème

À l'issue de l'Itération 1, NEXUS dispose de deux représentations complémentaires du projet :

- Lucene contient un index de recherche dérivé, optimisé pour les chemins, le contenu et les noms de symboles ;
- SQLite constitue la source de vérité structurelle et contient les fichiers, symboles et relations persistés.

L'Itération 2 doit transformer une requête textuelle en candidats pertinents. Une décision est nécessaire sur la répartition des responsabilités entre les deux stockages.

Dupliquer chaque symbole en document Lucene autonome simplifierait certaines recherches mais introduirait une seconde représentation canonique des symboles et complexifierait la synchronisation. À l'inverse, réaliser toute la recherche dans SQLite priverait NEXUS des capacités BM25 et multi-champs de Lucene.

## Facteurs de décision

- préserver SQLite comme source de vérité ;
- préserver Lucene comme index reconstructible ;
- exploiter BM25 et les boosts par champ ;
- retourner des candidats fichiers et symboles ;
- permettre une recherche exacte et approximative de symboles ;
- éviter la duplication structurelle prématurée ;
- conserver une architecture extensible ;
- limiter la complexité du MVP.

## Options envisagées

- indexer chaque fichier et chaque symbole comme documents Lucene autonomes ;
- utiliser exclusivement Lucene ;
- utiliser exclusivement SQLite ;
- utiliser Lucene pour la recherche lexicale de fichiers et SQLite pour la recherche structurelle de symboles et relations, puis fusionner les candidats.

## Décision retenue

**Option retenue : utiliser Lucene pour la recherche lexicale de fichiers et SQLite pour la recherche structurelle de symboles et relations, puis fusionner les candidats avant ranking.**

La chaîne de recherche cible est :

```text
SearchRequest
    │
    ├── LuceneFileSearchStrategy
    │      └── BM25 + boosts par champ
    │
    ├── SymbolSearchStrategy
    │      └── exact + substring + fuzzy sur les symboles SQLite
    │
    └── GraphExpansionStrategy
           └── relations structurelles SQLite
              │
              ▼
        CandidateMerger
              │
              ▼
         ContextRanker
```

Lucene continue d'indexer les noms de symboles comme champs de document fichier afin que ces noms influencent le score lexical du fichier, mais les objets `CodeSymbol` complets restent lus depuis SQLite.

La fusion utilise des identifiants déterministes permettant de dédupliquer un même fichier ou symbole produit par plusieurs stratégies tout en conservant l'ensemble des signaux.

### Conséquences positives

- chaque stockage reste utilisé selon sa spécialité ;
- aucun second référentiel canonique des symboles n'est introduit ;
- BM25 reste disponible pour le code et les chemins ;
- la recherche de symboles peut restituer des `CodeSymbol` complets ;
- les relations structurelles peuvent être exploitées sans dupliquer le graphe dans Lucene ;
- les stratégies restent testables séparément.

### Conséquences négatives et compromis acceptés

- la recherche combine deux backends ;
- les scores Lucene et symboliques doivent être normalisés avant ranking ;
- une recherche fuzzy sur de très gros volumes de symboles pourra nécessiter une optimisation future ;
- la fusion des candidats devient une responsabilité explicite du moteur.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Scores incomparables entre stratégies | Élevé | Normaliser les signaux avant calcul du score final |
| Recherche fuzzy SQLite trop lente à grande échelle | Moyen | Limiter le MVP et prévoir une implémentation indexée ultérieure si les métriques le justifient |
| Duplication d'un fichier produit par plusieurs stratégies | Moyen | Identifiant stable et fusion déterministe des signaux |
| Divergence entre Lucene et SQLite | Élevé | Conserver les règles de reconstruction de l'ADR-0022 |

### Confirmation

La décision est respectée si :

- `SearchIndex` fournit une capacité de recherche lexicale mais ne devient pas source de vérité des symboles ;
- `IndexRepository` expose les symboles et relations nécessaires aux stratégies structurelles ;
- un candidat peut accumuler plusieurs signaux provenant de plusieurs stratégies ;
- une reconstruction Lucene ne modifie pas les symboles stockés dans SQLite ;
- les tests couvrent la fusion et la déduplication.

## Analyse détaillée des options

### Documents Lucene autonomes pour chaque symbole

**Avantages :**

- recherche uniforme dans Lucene ;
- fuzzy queries possibles directement dans l'index ;
- scoring unifié plus simple.

**Inconvénients :**

- duplication importante ;
- synchronisation plus complexe ;
- gestion des relations moins naturelle ;
- risque de transformer Lucene en quasi-source de vérité.

### Lucene uniquement

**Avantages :**

- moteur unique pour la recherche ;
- implémentation conceptuellement simple.

**Inconvénients :**

- mauvais support des relations structurelles canoniques ;
- récupération des symboles complets plus complexe ;
- contradiction avec le rôle de SQLite défini précédemment.

### SQLite uniquement

**Avantages :**

- une seule source interrogée ;
- cohérence transactionnelle simple.

**Inconvénients :**

- perte des capacités BM25 et multi-champs de Lucene ;
- ranking lexical moins riche ;
- rend l'ADR-0007 largement inutile.

### Lucene + SQLite avec fusion

**Avantages :**

- spécialisation claire des backends ;
- faible duplication ;
- extensibilité ;
- meilleure cohérence avec l'architecture existante.

**Inconvénients :**

- orchestration et normalisation supplémentaires.

## Impacts sur l'architecture

```text
Lucene
→ File candidates

SQLite
→ Symbol candidates
→ Graph relations

       │
       ▼
CandidateMerger
       │
       ▼
ContextRanker
```

## Conditions de réexamen

Réexaminer si :

- le volume de symboles rend la recherche fuzzy SQLite insuffisante ;
- un index SCIP ou un provider externe apporte sa propre capacité de recherche ;
- les métriques montrent qu'un schéma Lucene plus granulaire améliore fortement la qualité ;
- la recherche devient multi-repository à grande échelle.

## Décisions liées

- ADR-0006 — Utiliser SQLite comme source de vérité structurelle locale.
- ADR-0007 — Utiliser Apache Lucene comme index de recherche local.
- ADR-0010 — Adopter un ranking hybride, déterministe et explicable.
- ADR-0022 — Traiter Lucene comme un index dérivé et reconstructible de SQLite.
