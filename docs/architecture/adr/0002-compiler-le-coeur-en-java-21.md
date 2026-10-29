---
status: accepted
date: 2026-07-19
---

# ADR-0002 — Compiler le cœur avec Java 21 comme niveau minimal

## Contexte et problème

Le projet NEXUS est développé dans un environnement où un JDK 24 est actuellement disponible. Le premier bootstrap du projet avait fixé `maven.compiler.release` à 25, ce qui a provoqué un échec de compilation local avec l'erreur `release version 25 not supported`.

NEXUS est destiné à devenir un projet open source et à pouvoir être intégré dans différents environnements Java. Le niveau de compilation doit donc équilibrer modernité du langage, stabilité, compatibilité de l'écosystème et facilité d'adoption.

La question est : **quel niveau Java minimal imposer au cœur NEXUS, indépendamment du JDK utilisé pour développer ou exécuter les adaptateurs futurs ?**

## Facteurs de décision

- compatibilité avec l'environnement de développement actuel ;
- stabilité d'une version LTS ;
- adoption probable par les contributeurs ;
- compatibilité avec Maven et les bibliothèques prévues ;
- possibilité d'utiliser un JDK plus récent tout en produisant un bytecode compatible ;
- absence de besoin identifié pour une fonctionnalité spécifique à Java 24 ou 25 dans le cœur ;
- portabilité du futur JAR NEXUS.

## Options envisagées

- compiler avec Java 25 ;
- compiler avec Java 24 ;
- compiler avec Java 21 ;
- descendre sous Java 21 pour maximiser la compatibilité.

## Décision retenue

**Option retenue : compiler le cœur avec `--release 21`.**

Le `pom.xml` fixe :

```xml
<maven.compiler.release>21</maven.compiler.release>
```

Le projet peut être développé et compilé avec un JDK plus récent, tant que le code du cœur reste compatible avec Java 21.

Cette décision concerne le **niveau minimal de compilation du cœur**. Elle n'interdit pas qu'un adaptateur optionnel futur impose un runtime plus récent si cette contrainte est isolée et justifiée par un ADR spécifique.

### Conséquences positives

- le build fonctionne avec le JDK 24 actuellement utilisé ;
- le JAR produit reste compatible avec un runtime Java 21 ;
- le projet s'appuie sur une base LTS largement disponible ;
- les contributeurs ne sont pas obligés d'installer immédiatement Java 25 ;
- le cœur reste compatible avec de nombreuses bibliothèques et environnements d'entreprise.

### Conséquences négatives et compromis acceptés

- les fonctionnalités introduites uniquement après Java 21 ne peuvent pas être utilisées dans le cœur ;
- certaines bibliothèques futures pourraient nécessiter un runtime supérieur et devront être isolées ;
- le projet devra réévaluer périodiquement ce niveau minimal.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Introduction accidentelle d'API Java > 21 | Moyen | Compiler systématiquement avec `maven.compiler.release=21` |
| Dépendance future exigeant Java > 21 | Moyen | Isoler la dépendance dans un adaptateur ou créer un nouvel ADR avant relèvement global |
| Niveau minimal devenu trop ancien | Faible à moyen | Réévaluer lors d'un changement majeur de version ou d'écosystème |

### Confirmation

La décision est confirmée par :

- `mvn clean install` exécuté avec succès avec un JDK plus récent tout en ciblant `release 21` ;
- l'absence d'option Maven imposant un niveau supérieur dans le module cœur ;
- les futurs pipelines CI qui devront inclure au moins une exécution compatible Java 21.

## Analyse détaillée des options

### Compiler avec Java 25

**Avantages :**

- accès immédiat aux dernières fonctionnalités LTS ;
- positionnement très moderne pour un projet greenfield.

**Inconvénients :**

- impose un JDK 25 à tous les contributeurs ;
- a déjà provoqué un échec de build dans l'environnement local ;
- aucun besoin fonctionnel du MVP ne justifie cette contrainte.

### Compiler avec Java 24

**Avantages :**

- correspond au JDK actuellement installé ;
- permet l'utilisation de fonctionnalités plus récentes que Java 21.

**Inconvénients :**

- Java 24 n'est pas retenu comme base LTS du projet ;
- réduire la compatibilité pour des fonctionnalités non nécessaires n'apporte pas de valeur au MVP.

### Compiler avec Java 21

**Avantages :**

- version LTS ;
- largement adoptée ;
- suffisamment moderne pour le cœur NEXUS ;
- compatible avec l'environnement actuel utilisant un JDK 24 ;
- bon compromis entre modernité et portabilité.

**Inconvénients :**

- empêche l'usage direct de fonctionnalités de langage plus récentes dans le cœur.

### Descendre sous Java 21

**Avantages :**

- compatibilité avec des environnements plus anciens.

**Inconvénients :**

- aucun besoin actuel ne le justifie ;
- réduit l'accès aux APIs et améliorations modernes ;
- créerait une contrainte héritée inutile pour un projet greenfield.

## Impacts sur l'architecture

Cette décision affecte :

- le `pom.xml` racine ;
- la sélection des dépendances embarquées dans le cœur ;
- les contraintes de compatibilité des futurs modules.

Elle ne fixe pas définitivement le runtime de tous les adaptateurs futurs.

## Conditions de réexamen

Réexaminer la décision si :

- Java 21 n'est plus supporté par des dépendances structurantes ;
- une fonctionnalité essentielle nécessite une version supérieure ;
- le coût de maintenir la compatibilité Java 21 dépasse son bénéfice ;
- une future version majeure de NEXUS justifie explicitement un relèvement du niveau minimal.

## Décisions liées

- ADR-0003 — Conserver un cœur Java sans framework applicatif obligatoire.
- ADR-0008 — Utiliser JavaParser comme analyseur Java embarqué du MVP.
- ADR-0009 — Rendre l'intelligence de code extensible via des providers et index externes.
