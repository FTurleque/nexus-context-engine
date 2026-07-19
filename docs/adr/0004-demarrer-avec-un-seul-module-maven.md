---
status: accepted
date: 2026-07-19
---

# ADR-0004 — Démarrer avec un seul module Maven et extraire uniquement sur besoin réel

## Contexte et problème

La vision cible de NEXUS fait apparaître plusieurs responsabilités : cœur de contexte, indexation, recherche, instructions, Git, API, CLI, MCP et adaptateurs d'intelligence de code. Il serait possible de matérialiser immédiatement chacune de ces responsabilités par un module Maven distinct.

Cependant, le MVP doit d'abord prouver la qualité du moteur de contexte. Créer dès le départ une arborescence multi-modules complète imposerait des frontières et des dépendances avant que les besoins réels ne soient observés.

La question est : **faut-il matérialiser immédiatement l'architecture logique en modules Maven séparés ou conserver un module unique tant qu'aucune contrainte technique ne justifie l'extraction ?**

## Facteurs de décision

- vitesse d'itération sur le MVP ;
- réduction de la complexité de build ;
- absence actuelle de runtimes multiples réellement implémentés ;
- nécessité future d'isoler certaines dépendances lourdes ;
- capacité à conserver des responsabilités claires par packages ;
- possibilité d'extraire ultérieurement sans modifier le modèle métier ;
- éviter la surarchitecture.

## Options envisagées

- créer immédiatement tous les modules envisagés (`nexus-core`, `nexus-indexer`, `nexus-search`, `nexus-api`, `nexus-cli`, `nexus-mcp`, etc.) ;
- créer dès maintenant un petit ensemble de modules principaux ;
- conserver un module Maven unique organisé par responsabilités et extraire uniquement lorsqu'une contrainte réelle apparaît.

## Décision retenue

**Option retenue : conserver un module Maven unique organisé par responsabilités et extraire uniquement lorsqu'une contrainte réelle apparaît.**

La séparation logique reste obligatoire dans les packages et dépendances internes. L'absence de modules Maven distincts ne signifie pas l'absence d'architecture.

Une extraction devient justifiée lorsqu'au moins un des critères suivants apparaît :

- runtime distinct ;
- packaging distinct ;
- isolation d'une dépendance lourde ou native ;
- cycle de livraison indépendant ;
- besoin d'empêcher structurellement une dépendance indésirable ;
- gain mesurable pour le build ou l'intégration.

L'ordre probable d'extraction est :

```text
nexus-context-engine
        │
        ├── nexus-core
        ├── nexus-cli
        ├── nexus-api
        ├── nexus-mcp
        └── adaptateurs optionnels lourds
```

Cet ordre est indicatif et ne constitue pas une obligation de créer tous ces modules.

### Conséquences positives

- build initial simple ;
- navigation et refactoring facilités pendant la phase de découverte ;
- moins de POM et de gestion de versions internes ;
- les frontières peuvent émerger à partir des usages réels ;
- le MVP reste concentré sur la valeur fonctionnelle.

### Conséquences négatives et compromis acceptés

- Maven ne protège pas encore physiquement toutes les frontières ;
- une discipline de packages et de dépendances est nécessaire ;
- l'extraction future demandera un travail de déplacement et de stabilisation des APIs ;
- certaines dépendances optionnelles devront être surveillées pour ne pas contaminer le cœur avant extraction.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Le module unique devient un monolithe non structuré | Élevé | Organiser par capacité et maintenir des ports clairs |
| Dépendances techniques utilisées directement partout | Élevé | Encapsuler SQLite, Lucene et providers derrière des abstractions |
| Extraction future coûteuse | Moyen | Éviter les accès croisés arbitraires entre packages et documenter les frontières |
| Création de modules trop tardive | Moyen | Réévaluer aux étapes API, MCP et providers lourds |

### Confirmation

La décision est respectée si :

- le projet reste mono-module pendant le MVP sauf justification explicite ;
- les packages reflètent des responsabilités distinctes ;
- les dépendances externes sont encapsulées ;
- toute création de module Maven supplémentaire est motivée par une contrainte documentée ou un nouvel ADR.

## Analyse détaillée des options

### Créer immédiatement tous les modules envisagés

**Avantages :**

- frontières physiques explicites ;
- dépendances potentiellement mieux contrôlées ;
- architecture cible visible dès le départ.

**Inconvénients :**

- beaucoup de modules vides ou artificiels ;
- complexité Maven sans valeur immédiate ;
- risque de figer trop tôt de mauvaises frontières ;
- ralentissement des refactorings du MVP.

### Créer dès maintenant un petit ensemble de modules principaux

**Avantages :**

- compromis entre isolation et simplicité ;
- possibilité d'isoler le cœur assez tôt.

**Inconvénients :**

- les critères de découpage ne sont pas encore validés par l'implémentation ;
- risque de déplacer fréquemment les frontières.

### Conserver un module unique et extraire sur besoin réel

**Avantages :**

- complexité minimale ;
- apprentissage rapide ;
- extraction basée sur des contraintes observées ;
- cohérent avec une approche incrémentale.

**Inconvénients :**

- isolation moins forte au début ;
- nécessite une discipline architecturale explicite.

## Impacts sur l'architecture

L'architecture logique reste indépendante de la structure Maven :

```text
Module Maven unique
└── io.github.fturleque.nexus
    ├── project
    ├── index
    ├── search
    ├── ranking
    ├── token
    └── context
```

Les futurs packages `persistence`, `cli`, `api`, `mcp` ou `integration` peuvent apparaître avant une éventuelle extraction physique.

## Conditions de réexamen

Réexaminer lors de :

- l'introduction d'un serveur REST ;
- l'introduction d'un serveur MCP ;
- l'ajout d'un provider nécessitant un processus ou runtime spécifique ;
- l'apparition de temps de build ou conflits de dépendances significatifs.

## Décisions liées

- ADR-0003 — Conserver un cœur Java sans framework applicatif obligatoire.
- ADR-0015 — Valider le MVP par la CLI avant les intégrations.
- ADR-0016 — Utiliser le SDK Java MCP officiel pour l'adaptateur MCP.
