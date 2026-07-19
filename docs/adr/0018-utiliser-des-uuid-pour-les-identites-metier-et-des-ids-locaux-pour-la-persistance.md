---
status: accepted
date: 2026-07-19
---

# ADR-0018 — Utiliser des UUID pour les identités métier et des identifiants locaux pour la persistance

## Contexte et problème

NEXUS doit identifier durablement les projets enregistrés tout en stockant localement de nombreux fichiers, symboles et relations. Les identifiants exposés au domaine doivent rester stables lorsqu'un index est reconstruit, alors que les lignes internes de SQLite peuvent être recréées ou réordonnées.

Utiliser uniquement des clés auto-incrémentées pour tous les objets exposerait des identités purement locales dans les contrats métier. À l'inverse, attribuer des UUID à chaque symbole et chaque relation augmenterait le volume de stockage et la complexité sans bénéfice immédiat pour le MVP.

## Facteurs de décision

- stabilité de l'identité d'un projet entre plusieurs processus ;
- possibilité de reconstruire l'index ;
- efficacité des jointures SQLite ;
- absence de dépendance à une séquence distribuée ;
- simplicité des contrats publics ;
- possibilité future de synchroniser ou exporter des projets sans collision.

## Options envisagées

- identifiants auto-incrémentés partout ;
- UUID partout ;
- clés naturelles fondées sur les chemins ;
- UUID pour les identités métier durables et identifiants locaux numériques pour les entités d'index.

## Décision retenue

**Option retenue : utiliser un UUID comme identité métier durable des projets et des identifiants locaux numériques pour les lignes d'index internes.**

Le `ProjectDescriptor.id` reste un `UUID`, persisté dans SQLite sous sa représentation texte canonique.

Les entités locales comme `indexed_files`, `symbols` et `symbol_relations` utilisent des clés SQLite `INTEGER PRIMARY KEY`. Leur identité fonctionnelle est protégée par des contraintes naturelles supplémentaires, par exemple :

```text
indexed_files  UNIQUE(project_id, relative_path)
symbols        rattachés à un fichier indexé
```

Le chemin racine canonique d'un projet est également unique dans le registre local afin d'éviter l'enregistrement accidentel du même projet plusieurs fois.

Les identifiants internes SQLite ne doivent pas être exposés comme identifiants stables dans les futurs contrats API publics.

### Conséquences positives

- les projets possèdent une identité stable et portable ;
- les jointures locales restent compactes et efficaces ;
- l'index peut être reconstruit sans promettre la stabilité des IDs techniques ;
- les futurs exports ou intégrations peuvent référencer un projet sans dépendre d'une base SQLite particulière.

### Conséquences négatives et compromis acceptés

- deux stratégies d'identifiants coexistent ;
- les mappings de persistance doivent distinguer identité métier et clé technique ;
- les symboles ne possèdent pas encore d'identité globale stable indépendante de leur fichier.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Exposition accidentelle d'un ID SQLite comme identifiant public | Moyen | Garder les IDs techniques dans les adaptateurs de persistance |
| Même projet enregistré via deux chemins équivalents | Élevé | Normaliser avec `toAbsolutePath().normalize()` et persister le chemin canonique |
| Besoin futur d'identifier durablement un symbole | Moyen | Introduire ultérieurement un `SymbolId` déterministe ou UUID via un nouvel ADR |

### Confirmation

- `ProjectDescriptor` conserve un `UUID` ;
- la table `projects` utilise ce UUID comme clé durable ;
- les tables d'index utilisent des clés locales ;
- `root_path` possède une contrainte d'unicité ;
- aucun contrat métier ne dépend d'un `rowid` SQLite.

## Analyse détaillée des options

### Identifiants auto-incrémentés partout

**Avantages :** simples et compacts.

**Inconvénients :** non portables, instables après reconstruction et inadaptés aux futurs échanges externes.

### UUID partout

**Avantages :** identités globalement uniques et portables.

**Inconvénients :** volume et complexité inutiles pour des millions de symboles locaux potentiels.

### Clés naturelles fondées sur les chemins

**Avantages :** lisibles et déterministes.

**Inconvénients :** les chemins changent lors des renommages et ne suffisent pas pour les symboles.

### Stratégie hybride

**Avantages :** stabilité là où elle est nécessaire et efficacité pour l'index local.

**Inconvénients :** nécessite de respecter clairement la frontière entre domaine et persistance.

## Conditions de réexamen

Réexaminer si NEXUS doit synchroniser des symboles entre plusieurs machines, conserver leur identité à travers des renommages ou exposer un graphe distribué.

## Décisions liées

- ADR-0006 — Utiliser SQLite comme source de vérité structurelle locale.
