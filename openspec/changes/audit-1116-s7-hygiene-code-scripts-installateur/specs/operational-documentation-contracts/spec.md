# Spec Delta

## ADDED Requirements

### Requirement: Les contrats documentaires opérationnels s'exécutent depuis des modules Python testés
`scripts/release/test-operational-doc-contracts.sh` SHALL n'embarquer aucun programme Python en heredoc : ses contrôles Python SHALL vivre dans des modules versionnés sous `scripts/release/`, qui importent une seule bibliothèque commune de lecture et de vérification. Le script SHALL lancer les tests unitaires de ces modules et échouer si l'un d'eux échoue.

#### Scenario: Heredoc Python réintroduit
- **GIVEN** une version de `scripts/release/test-operational-doc-contracts.sh` qui contient un bloc `python3 - <<`
- **WHEN** on lance `bash .claude/scripts/contracts.sh`
- **THEN** le contrat échoue en nommant le fichier et la ligne du bloc
- **AND** la commande se termine avec un code non nul

#### Scenario: Fonction de vérification redéfinie
- **GIVEN** un module de contrats de `scripts/release/` qui définit sa propre fonction `require` ou `forbid`
- **WHEN** on lance `bash .claude/scripts/contracts.sh`
- **THEN** le contrat échoue en nommant le module

#### Scenario: Test unitaire en échec
- **GIVEN** un test unitaire d'un module de contrats qui échoue
- **WHEN** on lance `scripts/release/test-operational-doc-contracts.sh`
- **THEN** le script se termine avec un code non nul et nomme le test en échec
- **AND** les contrôles documentaires ne sont pas déclarés réussis

#### Scenario: Contrôles inchangés
- **WHEN** on lance `scripts/release/test-operational-doc-contracts.sh` sur le HEAD qui précède l'extraction puis sur celui qui la livre, sans autre modification
- **THEN** les deux exécutions réussissent
- **AND** chaque motif exigé ou interdit avant l'extraction l'est encore après
