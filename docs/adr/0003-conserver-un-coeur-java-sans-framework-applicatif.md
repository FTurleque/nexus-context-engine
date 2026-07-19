---
status: accepted
date: 2026-07-19
---

# ADR-0003 — Conserver un cœur Java sans framework applicatif obligatoire

## Contexte et problème

NEXUS doit être utilisable dans plusieurs contextes : CLI locale, API REST, serveur MCP, intégration IDE, agent personnalisé ou composant embarqué dans un orchestrateur. Ces surfaces d'exécution n'ont pas les mêmes besoins ni les mêmes dépendances.

Un framework applicatif comme Quarkus pourrait accélérer la création d'une API REST et fournir injection de dépendances, configuration, observabilité et packaging. En revanche, introduire ce framework dans le cœur dès le MVP ferait dépendre les contrats métier et les services de contexte d'un runtime qui n'est pas nécessaire pour toutes les utilisations.

La question est : **le cœur de NEXUS doit-il être construit comme une application liée à un framework, ou comme une bibliothèque Java indépendante avec des frameworks uniquement aux frontières ?**

## Facteurs de décision

- réutilisabilité du cœur dans plusieurs runtimes ;
- testabilité sans démarrer de conteneur applicatif ;
- indépendance vis-à-vis de Quarkus ou d'un autre framework ;
- possibilité d'une CLI légère ;
- possibilité d'un adaptateur MCP distinct ;
- maîtrise des dépendances transverses ;
- limitation du couplage technique prématuré ;
- simplicité du MVP.

## Options envisagées

- utiliser Quarkus dans tout le projet dès le départ ;
- utiliser un autre framework Java léger comme socle global ;
- construire le cœur en Java simple et utiliser les frameworks uniquement dans les adaptateurs ;
- ne jamais utiliser de framework, y compris pour l'API future.

## Décision retenue

**Option retenue : construire le cœur en Java simple et utiliser les frameworks uniquement dans les adaptateurs qui en ont besoin.**

Le domaine et les services principaux doivent dépendre de contrats Java propres à NEXUS.

Les frameworks sont autorisés aux frontières :

```text
                     Cœur NEXUS
             Java + contrats métier
                      │
         ┌────────────┼────────────┐
         ▼            ▼            ▼
       CLI         API REST       MCP
                Quarkus possible  SDK MCP
```

Quarkus reste le candidat privilégié pour un futur adaptateur REST, mais sa version sera choisie au moment de cette itération. Il ne doit pas imposer ses annotations ou ses types aux contrats du cœur.

### Conséquences positives

- le moteur peut être embarqué comme bibliothèque Java ;
- les tests unitaires du cœur restent rapides ;
- une migration future de framework ne remet pas en cause la logique métier ;
- les dépendances lourdes peuvent être isolées ;
- la CLI et le serveur MCP peuvent partager exactement les mêmes services ;
- les couches de transport restent remplaçables.

### Conséquences négatives et compromis acceptés

- le câblage des dépendances du cœur est plus explicite ;
- certaines fonctionnalités offertes automatiquement par un framework devront être introduites dans les adaptateurs ;
- le projet devra surveiller les fuites d'abstractions des frameworks vers le domaine ;
- lors de l'extraction en modules, certains ports devront être stabilisés.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Annotations Quarkus ou CDI introduites dans le cœur | Élevé | Revue de dépendances et séparation stricte des packages/modules d'adaptation |
| Duplication de configuration entre adaptateurs | Moyen | Définir un modèle de configuration NEXUS neutre et traduire aux frontières |
| Surabstraction prématurée pour anticiper tous les adaptateurs | Moyen | N'introduire un port que lorsqu'un besoin réel apparaît |
| Câblage manuel trop complexe | Faible à moyen | Autoriser un mécanisme d'injection dans l'adaptateur sans contaminer les contrats métier |

### Confirmation

La décision est respectée si :

- le module cœur compile sans dépendance obligatoire à Quarkus, Spring ou autre framework applicatif ;
- les services métier peuvent être instanciés dans des tests unitaires simples ;
- les DTO REST n'entrent pas dans les signatures métier ;
- les handlers MCP délèguent aux mêmes services applicatifs ;
- les ressources REST ne contiennent pas de logique de ranking ou de construction de contexte.

## Analyse détaillée des options

### Utiliser Quarkus dans tout le projet dès le départ

**Avantages :**

- démarrage rapide d'une API ;
- injection de dépendances et configuration intégrées ;
- observabilité et packaging disponibles tôt.

**Inconvénients :**

- couplage prématuré du moteur à un runtime ;
- dépendances inutiles pour la CLI ou l'usage en bibliothèque ;
- risque de propagation d'annotations dans le domaine ;
- complexité supplémentaire avant validation du moteur.

### Utiliser un autre framework Java léger comme socle global

**Avantages :**

- pourrait offrir un compromis d'injection/configuration plus léger.

**Inconvénients :**

- le problème de couplage reste le même ;
- aucun besoin du MVP ne justifie d'imposer un framework global.

### Construire le cœur en Java simple et utiliser les frameworks uniquement dans les adaptateurs

**Avantages :**

- meilleure portabilité ;
- responsabilités claires ;
- tests simplifiés ;
- évolution indépendante des interfaces d'exposition.

**Inconvénients :**

- demande une architecture plus disciplinée ;
- le câblage initial peut être plus manuel.

### Ne jamais utiliser de framework, y compris pour l'API future

**Avantages :**

- contrôle total des dépendances ;
- empreinte minimale potentielle.

**Inconvénients :**

- réimplémentation inutile de fonctions HTTP, configuration, sécurité et observabilité ;
- coût de maintenance sans bénéfice démontré.

## Impacts sur l'architecture

Les frontières sont organisées autour de ports et d'adaptateurs :

```text
Adaptateur entrant
CLI / REST / MCP
        │
        ▼
Services applicatifs NEXUS
        │
        ▼
Domaine / contrats
        │
        ▼
Ports sortants
Persistence / Search / Code Intelligence
        │
        ▼
Adaptateurs techniques
SQLite / Lucene / SCIP / autres
```

## Conditions de réexamen

Réexaminer uniquement si :

- le cœur devient exclusivement destiné à un runtime unique ;
- le coût du câblage indépendant devient objectivement supérieur aux bénéfices ;
- une fonctionnalité fondamentale ne peut être fournie correctement qu'à travers un framework partagé.

## Décisions liées

- ADR-0001 — Positionner NEXUS comme moteur d'intelligence de contexte indépendant des modèles.
- ADR-0004 — Démarrer avec un seul module Maven et extraire uniquement sur besoin réel.
- ADR-0016 — Utiliser le SDK Java MCP officiel pour l'adaptateur MCP.
- ADR-0017 — Découpler NEXUS des outils et orchestrateurs externes.
