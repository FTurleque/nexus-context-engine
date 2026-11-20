# Spec Delta

## MODIFIED Requirements

### Requirement: Chaque test opt-in a un lanceur automatisé
Un test de `src/test` désactivé par défaut par une propriété système SHALL être lancé par au moins un workflow GitHub Actions ; sinon il SHALL être retiré de `src/test` et son statut d'outillage de mesure documenté.

#### Scenario: Intégration MINOS réelle
- **WHEN** on recherche `MinosRealIntegrationTest` dans `.github/workflows`
- **THEN** un workflow le lance, ou la classe n'est plus sous `src/test`

#### Scenario: Baselines de mesure
- **WHEN** on recherche `LargeScaleSearchBaselineTest`, `SmallDeltaIndexingBaselineTest`, `RealSemanticRetrievalDiagnosticTest` ou `SemanticSearchBenchmarkTest`
- **THEN** chacun est lancé par un workflow, ou se trouve hors de `src/test` avec son statut décrit dans `docs/developer/ci-and-supply-chain.md`

#### Scenario: Bancs de débit de redaction et de pic de heap
- **WHEN** on recherche `RedactionThroughputBenchmarkTest` et `IndexingHeapPeakBenchmarkTest` dans `.github/workflows`
- **THEN** chacun est nommé dans un argument `-Dtest=` d'un workflow, avec la propriété qui l'active
- **AND** `docs/developer/ci-and-supply-chain.md` nomme le workflow et le job qui le lancent
