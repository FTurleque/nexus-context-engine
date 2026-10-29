---
status: accepted
date: 2026-07-19
---

# ADR-0006 — Utiliser SQLite comme source de vérité structurelle locale

## Contexte et problème

NEXUS doit persister localement plusieurs catégories de données structurées : projets enregistrés, fichiers indexés, empreintes de contenu, symboles, relations, état d'indexation et métadonnées de sources de contexte.

Le stockage doit permettre une réindexation incrémentale, des mises à jour transactionnelles, des relations entre entités et une inspection relativement simple. Le MVP ne nécessite ni serveur de base de données ni infrastructure distribuée.

La recherche textuelle sera traitée séparément par un index spécialisé. La question porte donc sur la **source de vérité structurelle** du moteur, pas sur le moteur de ranking lexical.

## Facteurs de décision

- fonctionnement local et embarqué ;
- absence de serveur externe ;
- transactions fiables ;
- simplicité de déploiement ;
- fichier de données portable ;
- support des relations et métadonnées ;
- facilité d'inspection et de diagnostic ;
- compatibilité avec une stratégie d'indexation incrémentale ;
- séparation entre données canoniques et index de recherche reconstructible.

## Options envisagées

- fichiers JSON/YAML structurés ;
- base H2 embarquée ;
- SQLite ;
- moteur de recherche Lucene utilisé également comme source de vérité ;
- base serveur externe.

## Décision retenue

**Option retenue : utiliser SQLite comme source de vérité structurelle locale, derrière une abstraction de persistance NEXUS.**

SQLite stockera notamment :

- registre des projets ;
- fichiers indexés ;
- empreintes de contenu ;
- dates et états d'indexation ;
- symboles ;
- relations structurelles ;
- provenance des symboles et relations ;
- métadonnées des sources de contexte ;
- paramètres locaux nécessaires à la cohérence de l'index.

L'index Lucene est considéré comme **reconstructible** à partir des données canoniques et des sources projet. Une corruption de l'index de recherche ne doit pas remettre en cause l'identité du projet ni la cohérence structurelle stockée dans SQLite.

Le cœur métier ne doit pas exposer directement des types JDBC ou SQLite. Les accès passent par des repositories ou ports de persistance.

### Conséquences positives

- aucune base serveur à installer ;
- stockage local cohérent avec la stratégie local-first ;
- transactions adaptées aux mises à jour incrémentales ;
- fichier de base facile à sauvegarder ou inspecter ;
- séparation claire entre données canoniques et index de recherche ;
- possibilité de reconstruire Lucene à partir de données persistées.

### Conséquences négatives et compromis acceptés

- SQLite n'est pas destiné à une architecture distribuée multi-nœuds ;
- les accès concurrents massifs ne constituent pas son cas d'usage principal ;
- les migrations de schéma devront être gérées ;
- une couche d'abstraction est nécessaire pour éviter le couplage de tout le code au SQL.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Divergence entre SQLite et Lucene | Élevé | Définir une stratégie de synchronisation et permettre la reconstruction de l'index Lucene |
| Migration de schéma incorrecte | Élevé | Versionner le schéma et tester les migrations |
| Base locale volumineuse | Moyen | Mesurer la taille des données, ne pas dupliquer inutilement les contenus complets |
| Couplage excessif au SQL | Moyen | Repositories/ports de persistance et modèles métier séparés |
| Besoin futur de stockage distribué | Moyen | Considérer SQLite comme implémentation locale, pas comme contrat métier |

### Confirmation

La décision est respectée si :

- les identités projet, fichiers et symboles persistent dans SQLite ;
- la couche métier ne dépend pas d'APIs SQLite spécifiques ;
- une réindexation Lucene complète est possible sans perdre les données canoniques ;
- les opérations d'indexation critiques utilisent des transactions adaptées ;
- le stockage fonctionne sans service de base de données externe.

## Analyse détaillée des options

### Fichiers JSON/YAML structurés

**Avantages :**

- très simples à mettre en place ;
- facilement lisibles manuellement ;
- aucun driver de base de données.

**Inconvénients :**

- mises à jour partielles complexes ;
- gestion de concurrence et transactions faibles ;
- relations et requêtes plus difficiles ;
- risque de réécriture de gros fichiers.

### Base H2 embarquée

**Avantages :**

- excellente intégration Java ;
- moteur SQL complet ;
- mode embarqué.

**Inconvénients :**

- moins universellement inspectable qu'un fichier SQLite ;
- le besoin de NEXUS ne dépend pas d'une spécificité H2 ;
- SQLite offre un format local très portable et largement outillé.

### SQLite

**Avantages :**

- base embarquée mature ;
- fichier unique ;
- transactions ;
- SQL standard suffisant pour le MVP ;
- large écosystème d'outils d'inspection ;
- cohérent avec un produit local-first.

**Inconvénients :**

- limites pour des scénarios distribués ou très fortement concurrents ;
- nécessite un driver JDBC et une stratégie de migration.

### Utiliser Lucene également comme source de vérité

**Avantages :**

- une seule technologie de stockage ;
- données directement recherchables.

**Inconvénients :**

- Lucene est conçu comme index de recherche, pas comme base relationnelle canonique ;
- gestion des relations et transactions métier moins naturelle ;
- reconstruction et migrations plus délicates ;
- mélange de responsabilités.

### Utiliser une base serveur externe

**Avantages :**

- montée en charge et concurrence plus importantes ;
- centralisation possible.

**Inconvénients :**

- infrastructure obligatoire ;
- incompatible avec le MVP local simple ;
- complexité opérationnelle disproportionnée.

## Impacts sur l'architecture

```text
Project Registry
Indexed Files
Code Symbols
Relations
Context Metadata
      │
      ▼
Persistence Port
      │
      ▼
SQLite Adapter
      │
      └── source de vérité locale

Lucene Index
      └── index dérivé / reconstructible
```

## Conditions de réexamen

Réexaminer si :

- NEXUS doit gérer simultanément de nombreux utilisateurs sur un service centralisé ;
- plusieurs nœuds doivent écrire dans le même registre ;
- la concurrence ou le volume dépassent objectivement les capacités mesurées ;
- un mode serveur devient une cible produit prioritaire.

Le remplacement de SQLite ne doit pas modifier les contrats métier de persistance.

## Décisions liées

- ADR-0005 — Adopter un fonctionnement local-first et des intégrations externes opt-in.
- ADR-0007 — Utiliser Apache Lucene comme index de recherche local.
