# Spec Delta

## ADDED Requirements

### Requirement: Les méthodes du cœur restent sous 80 lignes hors baseline décroissante
Le test d'architecture SHALL mesurer chaque méthode et constructeur du code de production du cœur, de sa ligne de déclaration à son accolade fermante incluse. Une méthode de 80 lignes ou plus absente d'une baseline versionnée SHALL faire échouer le test, comme une entrée de baseline qui n'atteint plus 80 lignes ou n'existe plus. Le seuil vient du critère de sortie du sprint 7 de l'audit du 16/11/2026.

#### Scenario: Nouvelle méthode longue
- **GIVEN** une méthode du code de production du cœur absente de la baseline
- **WHEN** une modification la porte à 80 lignes ou plus
- **THEN** le test d'architecture échoue et nomme la classe, la méthode, sa ligne de déclaration et sa longueur
- **AND** la baseline versionnée n'est pas modifiée par le test

#### Scenario: Méthode découpée sans mise à jour de la baseline
- **GIVEN** une méthode listée dans la baseline
- **WHEN** une modification la ramène sous 80 lignes, la renomme ou la supprime sans retirer l'entrée
- **THEN** le test d'architecture échoue et demande de retirer l'entrée

#### Scenario: Méthodes visées par le sprint 7
- **WHEN** le test d'architecture s'exécute sur le code de production du cœur
- **THEN** `LocalGitContextSourceProvider.discover`, `SemanticHybridContextRanker.rank` et `ScipSnapshotMapper.importDocument` mesurent moins de 80 lignes
- **AND** aucune des trois ne figure dans la baseline

#### Scenario: Corps d'un record
- **GIVEN** une déclaration de record de plus de 80 lignes dont chaque méthode mesure moins de 80 lignes
- **WHEN** le test d'architecture s'exécute
- **THEN** il ne signale pas ce record
