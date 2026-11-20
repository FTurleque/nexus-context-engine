# Spec Delta

## Purpose

Garantit que chaque banc de performance secondaire (débit de redaction, pic de heap de l'indexation, cycle de vie Lucene, cache Git, scanner) est lancé par un workflow et fait échouer son job quand sa mesure franchit un seuil versionné, pour qu'une régression de ces composants ne passe pas en CI. Le benchmark d'échelle relève de `scale-benchmark-gate`.
Documentation durable : docs/developer/ci-and-supply-chain.md#openspec-performance-benchmark-thresholds

## ADDED Requirements

### Requirement: Le débit de redaction est mesuré quand la redaction change
Un workflow SHALL exécuter le banc de débit de redaction sur toute PR vers `main` qui modifie un fichier sous `core/src/main/java/com/nexus/security/`, sur demande manuelle, et sur une image de runner versionnée, et SHALL conserver le rapport JSON du banc comme artefact du run.

#### Scenario: PR qui modifie le redacteur
- **WHEN** une PR vers `main` modifie `core/src/main/java/com/nexus/security/SensitiveContentRedactor.java`
- **THEN** le workflow exécute `RedactionThroughputBenchmarkTest`, nommé dans un `-Dtest=`
- **AND** le rapport `redaction-throughput-benchmark.json` est attaché au run

#### Scenario: PR hors du paquet security
- **WHEN** une PR ne modifie que de la documentation
- **THEN** le banc de débit de redaction n'est pas exécuté par ce workflow

### Requirement: Le banc de débit de redaction échoue sous son seuil
Le job du banc de débit de redaction SHALL échouer quand la mesure retenue franchit le seuil versionné dans le dépôt, et SHALL afficher la mesure, le seuil et le corpus en cause.

#### Scenario: Régression du redacteur
- **GIVEN** un rapport dont la mesure retenue franchit le seuil versionné
- **WHEN** l'étape de vérification du rapport s'exécute
- **THEN** le job échoue avec un code non nul et un message qui nomme le corpus, la mesure et le seuil
- **AND** le rapport reste attaché au run

#### Scenario: Mesure dans le seuil
- **GIVEN** un rapport dont la mesure retenue respecte le seuil
- **WHEN** l'étape de vérification s'exécute
- **THEN** le job réussit et affiche la mesure et le seuil

#### Scenario: Rapport absent ou incomplet
- **GIVEN** un run où le rapport du banc est absent ou ne contient pas la mesure retenue
- **WHEN** l'étape de vérification s'exécute
- **THEN** le job échoue en signalant le rapport manquant ou le champ absent

### Requirement: Le pic de heap de l'indexation est mesuré par un job planifié et seuillé
Un job planifié, aussi déclenchable à la main, SHALL exécuter le banc de pic de heap d'un rebuild complet sur une image de runner versionnée, SHALL conserver son rapport JSON comme artefact, et SHALL échouer, en affichant la mesure et le seuil, quand le pic retenu dépasse le seuil versionné dans le dépôt.

#### Scenario: Run planifié dans le seuil
- **WHEN** le job planifié exécute `IndexingHeapPeakBenchmarkTest`, nommé dans un `-Dtest=`, et que le pic retenu respecte le seuil
- **THEN** le job réussit et affiche le pic retenu et le seuil
- **AND** le rapport `indexing-heap-benchmark.json` est attaché au run

#### Scenario: Rétention qui croît
- **GIVEN** un rapport dont le pic retenu dépasse le seuil versionné
- **WHEN** l'étape de vérification s'exécute
- **THEN** le job échoue avec un message qui nomme le pic retenu, le nombre de fichiers et le seuil

### Requirement: Les bancs Lucene et Git échouent sous un plancher de gain
Les étapes de vérification de `lucene-lifecycle-benchmark.yml` et `git-context-cache-qualification.yml` SHALL faire échouer le job quand le gain p95 qu'elles affichent passe sous un plancher versionné dans le dépôt, sur chaque système de la matrice, en affichant le gain mesuré et le plancher.

#### Scenario: Gain du cache Git sous le plancher
- **GIVEN** un rapport du banc du cache Git dont `warmP95ImprovementRatio` est inférieur au plancher versionné
- **WHEN** l'étape de vérification Linux ou Windows s'exécute
- **THEN** le job échoue avec un message qui nomme le gain mesuré et le plancher

#### Scenario: Gain Lucene sous le plancher
- **GIVEN** un rapport du banc du cycle de vie Lucene dont un `p95ImprovementRatio` de recherche est inférieur au plancher versionné
- **WHEN** l'étape de vérification s'exécute
- **THEN** le job échoue en nommant le domaine (`lexical` ou `semantic`), le gain mesuré et le plancher

#### Scenario: Gains au-dessus du plancher
- **WHEN** chaque gain vérifié atteint son plancher
- **THEN** le job réussit et affiche chaque gain avec son plancher

### Requirement: Le budget du banc du scanner suit un relevé retenu
Le budget de durée de scan appliqué par `scanner-corpus-benchmark.yml` SHALL être dérivé d'un relevé daté de `docs/project/history/`, et `docs/developer/indexing-corpus-limits.md` SHALL énoncer la valeur appliquée et citer ce relevé ; un contrat local SHALL échouer si la page et le workflow divergent.

#### Scenario: Valeur divergente
- **GIVEN** une copie de travail où le budget du workflow et celui de la page diffèrent
- **WHEN** `bash .claude/scripts/contracts.sh` s'exécute
- **THEN** le contrat échoue en nommant la page, le workflow et les deux valeurs

#### Scenario: Scan au-delà du budget
- **GIVEN** un rapport dont `scanMs` dépasse le budget appliqué
- **WHEN** l'étape de vérification s'exécute
- **THEN** le job échoue en affichant `scanMs` et le budget
