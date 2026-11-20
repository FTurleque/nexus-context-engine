# Spec Delta

## ADDED Requirements

### Requirement: Les dépendances entre packages du cœur suivent une matrice d'autorisations complète
Le test d'architecture SHALL associer chaque package de production du cœur à l'ensemble fermé des packages `com.nexus` qu'il peut référencer, et SHALL échouer sur toute référence, importée ou pleinement qualifiée, hors de cet ensemble, en nommant la règle, la classe et le type référencé. Un package de production absent de la matrice SHALL faire échouer le test.

#### Scenario: Dépendance non autorisée
- **GIVEN** une classe de `com.nexus.context` qui importe un type de `com.nexus.persistence.sqlite`
- **WHEN** le test d'architecture analyse ce code
- **THEN** il échoue en nommant la règle de `com.nexus.context`, la classe et le type importé

#### Scenario: Référence pleinement qualifiée non autorisée
- **GIVEN** une classe de `com.nexus.exposure` qui référence `com.nexus.search.lucene.LuceneSearchIndex` sans import
- **WHEN** le test d'architecture analyse ce code
- **THEN** il échoue sur la règle de `com.nexus.exposure` en nommant la classe

#### Scenario: Package non classé
- **GIVEN** une classe d'un package `com.nexus` qui ne figure pas dans la matrice
- **WHEN** le test d'architecture analyse ce code
- **THEN** il échoue en nommant ce package comme absent de la matrice

#### Scenario: Graphe courant
- **WHEN** le test d'architecture analyse le code de production du cœur
- **THEN** il ne trouve aucune référence hors matrice et aucun package non classé

### Requirement: Chaque règle de la matrice a une fixture négative
Chaque entrée de la matrice d'autorisations SHALL être accompagnée d'une fixture négative, sources en répertoire temporaire, qui fait échouer le test d'architecture sur cette entrée et sur aucune autre.

#### Scenario: Fixture par règle
- **WHEN** la suite par défaut exécute les fixtures négatives
- **THEN** chaque fixture échoue sur la règle qu'elle vise, et seulement sur elle

#### Scenario: Règle sans fixture
- **GIVEN** une entrée ajoutée à la matrice sans fixture négative
- **WHEN** le test d'architecture s'exécute
- **THEN** il échoue en nommant l'entrée sans fixture

### Requirement: Les adaptateurs techniques du cœur ne sont référencés que par la composition
Les packages `com.nexus.persistence.sqlite`, `com.nexus.search.lucene`, `com.nexus.search.semantic.lucene`, `com.nexus.search.semantic.ollama`, `com.nexus.index.jdt`, `com.nexus.index.scip` et `com.nexus.index.minos` SHALL n'être référencés que par `com.nexus.application`, ou par un autre package de cette liste quand la matrice l'autorise nommément.

#### Scenario: Adaptateur Lucene référencé par l'exposition
- **WHEN** une classe de `com.nexus.exposure` importe un type de `com.nexus.search.lucene`
- **THEN** le test d'architecture échoue sur la règle de `com.nexus.exposure`

#### Scenario: Adaptateur SQLite référencé par le contexte
- **WHEN** une classe de `com.nexus.context` importe un type de `com.nexus.persistence.sqlite`
- **THEN** le test d'architecture échoue sur la règle de `com.nexus.context`

### Requirement: Aucun package du cœur ne dépend des points d'entrée
Les packages du cœur autres que `com.nexus.cli` SHALL NOT référencer `com.nexus.cli`, et seul `com.nexus.cli` SHALL référencer `com.nexus.application` hors de ce package lui-même.

#### Scenario: Remontée vers la CLI
- **WHEN** une classe de `com.nexus.search` importe un type de `com.nexus.cli`
- **THEN** le test d'architecture échoue sur la règle de `com.nexus.search`

#### Scenario: Remontée vers la composition
- **WHEN** une classe de `com.nexus.index.service` importe un type de `com.nexus.application`
- **THEN** le test d'architecture échoue sur la règle de `com.nexus.index.service`

#### Scenario: Point d'entrée CLI
- **WHEN** le test d'architecture contrôle `com.nexus.cli`
- **THEN** ses références à `com.nexus.application` sont autorisées

### Requirement: La politique d'exclusion de fichiers est hors du package de scan d'indexation
Le type qui évalue les règles d'exclusion de type gitignore SHALL résider hors de `com.nexus.index.scan`, dans un package qui ne référence, dans `com.nexus`, que des packages de fondation ; les packages `com.nexus.context.source` et ses sous-packages SHALL NOT référencer `com.nexus.index.scan`.

#### Scenario: Imports des sources de contexte
- **WHEN** le test d'architecture contrôle `com.nexus.context.source.instruction` et `com.nexus.context.source.skill`
- **THEN** aucune référence ne désigne `com.nexus.index.scan`

#### Scenario: Exclusions inchangées
- **GIVEN** un projet qui porte un `.gitignore`, un `.nexusignore` et un répertoire exclu par défaut
- **WHEN** le projet est scanné pour l'indexation et que ses instructions et skills sont découverts
- **THEN** les fichiers exclus sont les mêmes qu'avant le déplacement, pour le scan comme pour la découverte

### Requirement: Le package du modèle d'index ne porte ni verrou ni exécution de tâches
Le package `com.nexus.index` SHALL NOT contenir le gestionnaire de verrou de projet, la jauge de capacité d'indexation ni l'exécuteur de tâches externes, et l'exécuteur de tâches externes SHALL NOT être un type public.

#### Scenario: Contenu du package index
- **WHEN** on liste les types de `com.nexus.index`
- **THEN** aucun ne verrouille un fichier, ne limite la concurrence d'indexation ni ne lance de thread

#### Scenario: Visibilité de l'exécuteur
- **WHEN** une classe hors du package de l'exécuteur de tâches externes tente de l'utiliser
- **THEN** la compilation échoue

#### Scenario: Verrouillage inchangé
- **WHEN** deux indexations du même projet sont demandées en même temps, dans un processus ou dans deux
- **THEN** la seconde est refusée avec le même diagnostic qu'avant le déplacement

### Requirement: Une recherche sémantique désactivée est une implémentation sans effet du port
Quand la recherche sémantique est désactivée, la façade applicative SHALL recevoir une implémentation sans effet du port d'index sémantique, jamais `null`, et l'état « recherche sémantique activée » SHALL être dérivé de cette seule implémentation ou de la configuration, sans second champ à synchroniser.

#### Scenario: Composition sans recherche sémantique
- **GIVEN** une configuration où la recherche sémantique est désactivée
- **WHEN** l'application est créée, interrogée sur sa disponibilité puis fermée
- **THEN** la disponibilité rapporte la recherche sémantique désactivée et la fermeture réussit
- **AND** aucun champ de la façade ne vaut `null`

#### Scenario: Composition avec recherche sémantique
- **GIVEN** une configuration où la recherche sémantique est activée
- **WHEN** l'application est créée et interrogée sur sa disponibilité
- **THEN** la disponibilité rapporte la recherche sémantique activée, comme avant le changement

### Requirement: Le code de production n'expose pas de commodité réservée aux tests
Le code de production du cœur SHALL NOT exposer publiquement de fabrique de verrou limité au processus, de conversion `RepositoryPath` depuis un `Path` ni de test de lien symbolique sur un chemin candidat de `ProjectPathGuard` sans appelant de production ; un test qui en a besoin SHALL les obtenir d'une fabrique de test.

#### Scenario: Verrou limité au processus
- **WHEN** on cherche dans `core/src/main` une méthode publique qui renvoie un gestionnaire de verrou sans `NEXUS_HOME`
- **THEN** il n'y en a aucune, et les tests l'obtiennent de la fabrique de test du service d'indexation

#### Scenario: Méthodes de chemins
- **WHEN** une classe de production hors de leur package appelle `RepositoryPath.fromPath` ou `ProjectPathGuard.containsSymbolicLink`
- **THEN** la compilation échoue, ou la méthode n'existe plus
