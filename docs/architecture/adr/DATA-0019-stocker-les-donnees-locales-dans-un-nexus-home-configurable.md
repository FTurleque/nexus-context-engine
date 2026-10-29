---
id: DATA-0019
title: "Stocker les données locales dans un NEXUS_HOME configurable"
domain: data
rules: true
files: ["**/src/main/java/**"]
---

# Stocker les données locales dans un NEXUS_HOME configurable

- **Statut** : Accepted
- **Date** : 2026-07-19

## Context

NEXUS doit persister un registre de projets, une base SQLite et des index Lucene sans écrire dans les repositories analysés. L'emplacement doit être prévisible, local par défaut, configurable pour les environnements professionnels et portable entre Windows, Linux et macOS.

Écrire les index à l'intérieur de chaque repository augmenterait le risque de les versionner accidentellement, compliquerait les permissions et polluerait les projets consommateurs.

## Facteurs de décision

- fonctionnement local-first ;
- séparation entre code source et données dérivées ;
- compatibilité Windows/Linux/macOS ;
- possibilité de déplacer les données sur un autre volume ;
- comportement testable ;
- configuration simple pour CLI, API et MCP.

## Options envisagées

- stocker `.nexus/` dans chaque repository ;
- utiliser uniquement un chemin spécifique au système d'exploitation ;
- utiliser `${user.home}/.nexus` sans possibilité de configuration ;
- utiliser un `NEXUS_HOME` configurable avec repli sur `${user.home}/.nexus`.

## Decision

**Option retenue : utiliser un répertoire racine NEXUS configurable, avec la priorité suivante :**

1. propriété système Java `nexus.home` ;
2. variable d'environnement `NEXUS_HOME` ;
3. repli sur `${user.home}/.nexus`.

La structure initiale est :

```text
NEXUS_HOME/
├── nexus.db
└── indexes/
    └── <project-uuid>/
        └── lucene/
```

Les repositories source ne reçoivent aucun index NEXUS par défaut. Le fichier `.nexusignore` reste un fichier de configuration projet et peut, lui, être versionné volontairement.

Les tests doivent pouvoir injecter un répertoire temporaire via la propriété `nexus.home` afin de ne jamais écrire dans le profil réel de l'utilisateur.

### Conséquences positives

- aucune pollution des repositories ;
- emplacement central et prévisible ;
- isolation facile des tests ;
- déplacement possible vers un disque dédié ;
- suppression/reconstruction des index indépendante du code source.

### Conséquences négatives et compromis acceptés

- les données d'un projet ne voyagent pas automatiquement avec son repository ;
- un nettoyage explicite peut être nécessaire pour les projets supprimés ;
- le chemin par défaut n'utilise pas immédiatement toutes les conventions natives XDG/AppData.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Index volumineux sur le disque système | Moyen | Permettre `NEXUS_HOME` sur un autre volume |
| Données de test écrites dans le profil réel | Élevé | Injection systématique d'un répertoire temporaire dans les tests |
| Données orphelines après suppression d'un projet | Faible à moyen | Ajouter ultérieurement une commande de purge/maintenance |
| Permissions insuffisantes | Moyen | Créer les répertoires au démarrage et produire une erreur explicite |

### Confirmation

- aucun index n'est créé dans le repository source ;
- `NexusPaths` centralise la résolution des chemins ;
- les tests utilisent un home temporaire ;
- SQLite et Lucene utilisent des sous-chemins dérivés de `NEXUS_HOME`.

## Do's and Don'ts

### Do

- Résoudre le répertoire racine par la propriété système `nexus.home`, puis par la variable d'environnement `NEXUS_HOME`, puis par repli sur `${user.home}/.nexus`.
- Placer dans ce répertoire la base `nexus.db` et les index sous `indexes/<project-uuid>/lucene/`.
- Centraliser la résolution des chemins dans `NexusPaths`.
- Injecter dans les tests un répertoire temporaire par la propriété `nexus.home`.

### Don't

- Ne pas écrire d'index NEXUS dans les repositories source par défaut.
- Ne pas écrire dans le profil réel de l'utilisateur pendant les tests.

## Analyse détaillée des options

### `.nexus/` dans chaque repository

**Avantages :** données proches du projet.

**Inconvénients :** pollution, risque de commit, problèmes de permissions et duplication des réglages d'exclusion.

### Chemins natifs spécifiques à chaque OS

**Avantages :** intégration parfaite aux conventions système.

**Inconvénients :** davantage de branches de code et de comportements à documenter dès le MVP.

### `${user.home}/.nexus` fixe

**Avantages :** très simple.

**Inconvénients :** impossible de déplacer les données ou d'isoler proprement certains environnements.

### `NEXUS_HOME` configurable avec repli

**Avantages :** simplicité par défaut, configurabilité et testabilité.

**Inconvénients :** convention propre à NEXUS à documenter.

## Compliance and Enforcement

- Règle Archgate `DATA-0019-stocker-les-donnees-locales-dans-un-nexus-home-configurable.rules.ts` / `nexus-home-resolution-order` : `NexusPaths` déclare `HOME_PROPERTY = "nexus.home"` et `HOME_ENVIRONMENT_VARIABLE = "NEXUS_HOME"`, et `fromEnvironment()` lit dans cet ordre la propriété, la variable, puis `user.home` avec `.nexus`.
- Règle Archgate `DATA-0019-stocker-les-donnees-locales-dans-un-nexus-home-configurable.rules.ts` / `nexus-home-resolved-by-nexus-paths` : dans les sources de production, les littéraux `"nexus.home"`, `"NEXUS_HOME"` et `"nexus.db"` n'apparaissent que dans `NexusPaths.java`, et aucun autre fichier ne lit `NexusPaths.HOME_PROPERTY` ou `NexusPaths.HOME_ENVIRONMENT_VARIABLE` par `System.getProperty` ou `System.getenv`.
- Test `NexusPathsTest` (`core/src/test/java/com/nexus/config/`, répertoires privés créés sous le home), lancés par `mvnw clean install` (workflow `.github/workflows/ci.yml` : job Linux reactor, et job Windows gate par `scripts/validate-phase-6.ps1`).
- Aucun contrôle automatisé de l'absence d'index dans le repository source ; vérifié en revue.

## Conditions de réexamen

Réexaminer si NEXUS devient une application desktop nécessitant une intégration stricte aux répertoires système, ou un service multi-utilisateur avec un stockage administré.

## Décisions liées

- ADR-0005 — Adopter un fonctionnement local-first et des intégrations externes opt-in.
- ADR-0006 — Utiliser SQLite comme source de vérité structurelle locale.
- ADR-0007 — Utiliser Apache Lucene comme index de recherche local.
