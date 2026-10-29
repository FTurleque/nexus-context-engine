---
status: accepted
date: 2026-07-19
---

# ADR-0007 — Utiliser Apache Lucene comme index de recherche local

## Contexte et problème

NEXUS doit rechercher rapidement des éléments pertinents dans un repository local : chemins, packages, noms de symboles, signatures, contenu de code, commentaires et documentation. La recherche doit pouvoir combiner correspondances exactes, recherche textuelle, pondération par champ, filtrage et ranking lexical.

SQLite est retenu comme source de vérité structurelle, mais l'utiliser également comme moteur principal de recherche textuelle limiterait les capacités de ranking et l'évolution future. À l'inverse, introduire dès le MVP un moteur serveur spécialisé serait disproportionné.

La question est : **quel moteur utiliser pour l'index de recherche local du MVP tout en gardant la possibilité de remplacer ou compléter cette implémentation ?**

## Facteurs de décision

- fonctionnement local et embarqué ;
- intégration naturelle avec Java ;
- ranking lexical de qualité ;
- BM25 ;
- pondération par champ ;
- recherche exacte et approximative ;
- filtres structurés ;
- index reconstructible ;
- absence de serveur externe ;
- possibilité future d'étendre vers des recherches vectorielles sans l'imposer au MVP.

## Options envisagées

- utiliser SQLite FTS comme moteur de recherche principal ;
- utiliser Apache Lucene embarqué ;
- utiliser Zoekt ou OpenGrok dès le MVP ;
- utiliser un moteur serveur comme Elasticsearch/OpenSearch ;
- développer un moteur lexical maison.

## Décision retenue

**Option retenue : utiliser Apache Lucene comme index de recherche local privilégié, derrière une abstraction `SearchIndex` ou équivalente.**

Lucene indexera des documents dérivés du repository et des données structurées NEXUS. Les champs candidats comprennent :

```text
projectId
path
module
packageName
language
symbolName
symbolKind
qualifiedName
content
comments
documentation
sourceType
```

La stratégie de recherche pourra exploiter :

- BM25 ;
- boosts par champ ;
- correspondances exactes de symboles ;
- recherche approximative ;
- filtres par projet, langage, type ou source ;
- combinaison avec les autres stratégies de recherche NEXUS.

Lucene est un **index dérivé**, pas la source de vérité. Le code métier ne doit pas dépendre directement des classes Lucene.

### Conséquences positives

- moteur de recherche Java embarqué mature ;
- ranking lexical plus riche que de simples requêtes SQL ;
- pas de service externe ;
- support naturel de plusieurs champs et boosts ;
- possibilité d'évaluer ultérieurement les capacités vectorielles de Lucene ;
- index local adapté au MVP.

### Conséquences négatives et compromis acceptés

- deux stockages locaux doivent être synchronisés : SQLite et Lucene ;
- l'index doit être reconstruit lors de certaines migrations ou corruptions ;
- Lucene ajoute une dépendance et une API technique spécifique ;
- le tuning du schéma d'index et des analyzers demandera des mesures sur corpus réel.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Divergence SQLite/Lucene | Élevé | Transactions applicatives, statut d'indexation et commande de reconstruction complète |
| Mauvais ranking par défaut | Élevé | Corpus de référence, `precision@K`, `recall@K`, tuning mesuré |
| Couplage métier à Lucene | Moyen | `SearchIndex` et `SearchStrategy` comme ports |
| Index trop volumineux | Moyen | Choisir les champs indexés/stored avec mesure ; éviter les duplications inutiles |
| Lucene insuffisant à très grande échelle | Faible pour le MVP | Prévoir des adaptateurs externes futurs sans changer le contrat métier |

### Confirmation

La décision est respectée si :

- la recherche lexicale passe par un port NEXUS ;
- Lucene peut être reconstruit à partir des données et sources canoniques ;
- les scores de recherche sont mesurés sur un corpus de référence ;
- aucune API Lucene n'apparaît dans `ContextRequest`, `ContextBundle` ou les modèles métier ;
- le MVP fonctionne sans serveur de recherche externe.

## Analyse détaillée des options

### Utiliser SQLite FTS comme moteur principal

**Avantages :**

- une seule technologie de stockage ;
- FTS disponible localement ;
- simplicité opérationnelle.

**Inconvénients :**

- moins flexible pour un ranking multi-champs avancé ;
- extension plus limitée pour les besoins futurs ;
- mélange entre persistance canonique et index de recherche.

### Utiliser Apache Lucene embarqué

**Avantages :**

- bibliothèque Java pure et mature ;
- excellent support du full-text ;
- BM25 et boosts ;
- recherche locale sans serveur ;
- index spécialisé et reconstructible.

**Inconvénients :**

- synchronisation supplémentaire ;
- nécessite une conception de schéma d'index ;
- tuning nécessaire.

### Utiliser Zoekt ou OpenGrok dès le MVP

**Avantages :**

- moteurs spécialisés pour la recherche de code ;
- pertinents pour de gros volumes ou de nombreux repositories.

**Inconvénients :**

- complexité opérationnelle supérieure ;
- intégration externe plus lourde ;
- surdimensionnés pour le premier cas local mono-repository.

### Utiliser Elasticsearch/OpenSearch

**Avantages :**

- recherche distribuée puissante ;
- écosystème riche.

**Inconvénients :**

- serveur obligatoire ;
- coût d'exploitation important ;
- incompatible avec le principe local-first minimal du MVP.

### Développer un moteur lexical maison

**Avantages :**

- contrôle total.

**Inconvénients :**

- réimplémentation d'un problème mature ;
- risque de qualité inférieure ;
- coût de maintenance élevé ;
- aucune valeur différenciante pour NEXUS.

## Impacts sur l'architecture

```text
SQLite
source de vérité
   │
   ├── fichiers / symboles / métadonnées
   │
   ▼
Indexing Pipeline
   │
   ▼
SearchIndex Port
   │
   ▼
Lucene Adapter
   │
   ▼
LexicalSearchStrategy
```

La recherche Lucene sera combinée avec symboles et graphe dans les étapes ultérieures.

## Conditions de réexamen

Réexaminer si :

- les métriques montrent que Lucene ne répond plus aux volumes visés ;
- NEXUS devient un service fédérant de nombreux repositories ;
- un moteur externe spécialisé apporte un gain mesurable suffisant ;
- les coûts de synchronisation dépassent les bénéfices.

Lucene reste le moteur local par défaut tant qu'il satisfait les critères mesurés.

## Décisions liées

- ADR-0005 — Adopter un fonctionnement local-first et des intégrations externes opt-in.
- ADR-0006 — Utiliser SQLite comme source de vérité structurelle locale.
- ADR-0010 — Adopter un ranking hybride, déterministe et explicable.
- ADR-0014 — Rendre la recherche sémantique et les embeddings optionnels.
