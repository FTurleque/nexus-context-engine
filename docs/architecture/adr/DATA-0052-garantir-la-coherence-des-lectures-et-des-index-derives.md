---
id: DATA-0052
title: "Garantir la cohérence des lectures et des index dérivés"
domain: data
rules: false
---

# Garantir la cohérence des lectures et des index dérivés

- **Statut** : Accepted
- **Date** : 2026-09-12
- **Complète** : ADR-0022 et ADR-0045

## Context

SQLite reste la source canonique et Lucene un dérivé reconstructible. Une lecture
qui vérifie `READY` puis ouvre Lucene sans coordination peut toutefois rencontrer
un rebuild en cours et observer un index partiellement remplacé. La perte ou la
corruption d'un dérivé pouvait aussi être confondue avec un index vide. Enfin, une
évolution de la redaction devait invalider les contenus dérivés déjà persistés.

## Decision

Les mutations et les lectures d'un projet partagent une identité de verrou par
chemin de lock. Les mutations prennent le verrou d'écriture inter-processus ; les
lectures prennent un verrou partagé inter-processus et le conservent pendant toute
la façade de recherche, de contexte ou d'inspection. Les lecteurs du même processus
mutualisent le `FileLock` partagé pour rester compatibles avec les sémantiques JVM.
Les lectures fédérées acquièrent plusieurs verrous dans l'ordre global des UUID,
afin d'éviter les cycles entre portées concurrentes qui se recouvrent.

Chaque commit Lucene porte la version de la politique de redaction. `isPresent`
vérifie l'existence, l'intégrité minimale et cette version avant qu'une indexation
incrémentale soit autorisée. Le profil sémantique inclut également la version de la
redaction ; un dérivé ancien ou absent force une reconstruction.

## Do's and Don'ts

### Do

- Partager entre les mutations et les lectures d'un projet une identité de verrou par chemin de lock.
- Prendre le verrou d'écriture inter-processus pour une mutation, et pour une lecture un verrou partagé inter-processus conservé pendant toute la façade de recherche, de contexte ou d'inspection.
- Mutualiser le `FileLock` partagé entre les lecteurs du même processus.
- Acquérir les verrous des lectures fédérées dans l'ordre global des UUID.
- Porter la version de la politique de redaction dans chaque commit Lucene, et la vérifier par `isPresent`, avec l'existence et l'intégrité minimale, avant d'autoriser une indexation incrémentale.
- Inclure la version de la redaction dans le profil sémantique et reconstruire un dérivé ancien ou absent.

### Don't

- Ne pas ouvrir Lucene pour une lecture publique sans le verrou partagé du projet.
- Ne pas autoriser une indexation incrémentale sur un dérivé absent, altéré ou d'une autre version de redaction.
- Ne pas présenter `READY` comme un snapshot atomique du filesystem vivant.

## Conséquences et validation

Une lecture publique ne traverse plus un remplacement de dérivé et une indexation
READY avec un dérivé absent ne peut plus être considérée comme valide. Les lectures
restent soumises au coût du verrou partagé et `READY` n'est pas présenté comme un
snapshot atomique du filesystem vivant.

Les tests couvrent la concurrence lecteurs/écrivain, la détection d'un index Lucene
absent, la compatibilité du commit, la reconstruction sémantique et la redaction
des clés JSON citées. La validation complète est réalisée par `mvn clean install`
et `scripts/self-smoke.ps1`.

## Compliance and Enforcement

- Tests `ProjectIndexLockManagerTest` (`core/src/test/java/com/nexus/index/`, dont `allowsConcurrentReadersButExcludesWriters` et `enforcesReadWriteSemanticsAcrossDistinctJvmProcesses`), `NexusApplicationFederatedScopePolicyTest.federatedReadsReleaseLocksAfterSuccessAndPartialAcquisitionFailure` (`core/src/test/java/com/nexus/application/`), `SemanticIndexProvenanceIntegrationTest` (`core/src/test/java/com/nexus/search/semantic/`, reconstruction quand la provenance sémantique est incompatible), lancés par `mvnw clean install` (workflow `.github/workflows/ci.yml` : job Linux reactor, et job Windows gate par `scripts/validate-phase-6.ps1`).
- Workflow `.github/workflows/filesystem-semantics-qualification.yml` : `ProjectIndexLockManagerTest` sur les systèmes de fichiers qualifiés.
- Aucune règle Archgate : la prise des verrous autour des façades ne se lit pas sur le texte du dépôt ; vérifié en revue.
