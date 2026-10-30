# test-suite-reliability Specification

## Purpose
Définit les propriétés de fiabilité de la suite de tests : indépendance vis-à-vis de la charge du runner, absence de code de port non exécuté, exécution réelle des tests opt-in.

## Requirements

### Requirement: La suite par défaut n'impose pas de durée murale serrée
Un test de la suite par défaut SHALL NOT comparer une durée mesurée à un seuil absolu inférieur à la durée de l'opération attendue (délai d'un worker, timeout configuré) ; un garde-fou contre un blocage infini SHALL laisser une marge d'au moins cinq fois la durée nominale.

#### Scenario: Worker récalcitrant
- **WHEN** `ExternalTaskRunnerTest` vérifie que l'appelant n'attend pas un worker qui ignore l'interruption
- **THEN** l'assertion porte sur l'état de la tâche ou sur une borne égale au délai du worker, non sur un seuil absolu inférieur

#### Scenario: Runner chargé
- **WHEN** la suite s'exécute sur un runner Windows chargé
- **THEN** aucun des tests `ExternalTaskRunnerTest`, `ProjectIndexingServiceTimeoutTest`, `SqliteWriteContentionIntegrationTest`, `JdtLanguageServerCodeIntelligenceProviderTest` et `SensitiveContentRedactorTest` n'échoue pour la seule durée observée

### Requirement: Le port IndexRepository ne porte pas de comportement non exécuté
Toute méthode `default` conservée dans `IndexRepository` SHALL être exécutée par au moins un test.

#### Scenario: Couverture du port
- **WHEN** on lit la ligne `IndexRepository` du rapport JaCoCo de `core` après `./mvnw clean install`
- **THEN** aucune ligne n'est manquée

### Requirement: Chaque test opt-in a un lanceur automatisé
Un test de `src/test` désactivé par défaut par une propriété système SHALL être lancé par au moins un workflow GitHub Actions ; sinon il SHALL être retiré de `src/test` et son statut d'outillage de mesure documenté.

#### Scenario: Intégration MINOS réelle
- **WHEN** on recherche `MinosRealIntegrationTest` dans `.github/workflows`
- **THEN** un workflow le lance, ou la classe n'est plus sous `src/test`

#### Scenario: Baselines de mesure
- **WHEN** on recherche `LargeScaleSearchBaselineTest`, `SmallDeltaIndexingBaselineTest`, `RealSemanticRetrievalDiagnosticTest` ou `SemanticSearchBenchmarkTest`
- **THEN** chacun est lancé par un workflow, ou se trouve hors de `src/test` avec son statut décrit dans `docs/developer/ci-and-supply-chain.md`
