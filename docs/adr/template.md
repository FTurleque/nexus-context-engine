---
status: proposed
date: YYYY-MM-DD
---

# ADR-XXXX — Titre court exprimant la décision

## Contexte et problème

Décrire le contexte dans lequel la décision doit être prise, le problème concret à résoudre et les contraintes qui rendent cette décision architecturale significative.

La formulation doit permettre à une personne découvrant le projet plusieurs mois ou années plus tard de comprendre :

- ce qui existait au moment de la décision ;
- ce qui posait problème ;
- ce qui devait être préservé ;
- la question exacte à laquelle l'ADR répond.

## Facteurs de décision

- facteur fonctionnel ou technique déterminant ;
- contrainte de sécurité ;
- contrainte de portabilité ;
- contrainte de performance ou de coût ;
- exigence de maintenabilité ;
- exigence d'interopérabilité ;
- autre facteur pertinent.

## Options envisagées

- Option A ;
- Option B ;
- Option C.

## Décision retenue

**Option retenue : Option A.**

Expliquer clairement la décision et sa justification principale.

Préciser le périmètre de la décision : ce qu'elle impose, ce qu'elle autorise et ce qu'elle ne décide pas.

### Conséquences positives

- conséquence positive ;
- propriété architecturale améliorée ;
- simplification obtenue.

### Conséquences négatives et compromis acceptés

- coût ou complexité supplémentaire ;
- limitation acceptée ;
- dette ou risque restant à surveiller.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Risque identifié | Faible / Moyen / Élevé | Mesure prévue |

### Confirmation

Décrire comment le projet vérifiera que la décision est effectivement respectée :

- revue d'architecture ;
- tests automatisés ;
- règles de dépendances ;
- métriques ;
- inspection de configuration ;
- critères de sortie d'itération.

## Analyse détaillée des options

### Option A

Description de l'option.

**Avantages :**

- avantage ;
- avantage.

**Inconvénients :**

- inconvénient ;
- inconvénient.

### Option B

Description de l'option.

**Avantages :**

- avantage.

**Inconvénients :**

- inconvénient.

### Option C

Description de l'option.

**Avantages :**

- avantage.

**Inconvénients :**

- inconvénient.

## Impacts sur l'architecture

Décrire les composants, contrats, dépendances, données ou flux affectés.

```text
Composant A
    │
    ▼
Composant B
```

## Conditions de réexamen

La décision doit être réévaluée si :

- condition mesurable ;
- nouvelle contrainte ;
- évolution d'un standard ;
- coût opérationnel devenu significatif.

Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Décisions liées

- ADR-XXXX — Décision liée.

## Références

- documentation, standard ou ressource ayant éclairé la décision.
