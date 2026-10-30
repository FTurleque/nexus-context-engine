# Spec Delta

## Purpose

Définit comment les scripts PowerShell du dépôt appellent Maven et partagent leurs fonctions utilitaires, et quels scripts sont des points d'entrée de validation.

## ADDED Requirements

### Requirement: Un module partagé porte les fonctions communes des scripts
Les fonctions `Invoke-Maven`, `Resolve-AbsolutePath` et `Invoke-NexusJson` SHALL être définies uniquement dans `scripts/lib/NexusScript.psm1`, et les scripts actifs de `scripts/` SHALL l'importer au lieu de les redéfinir.

#### Scenario: Aucune redéfinition locale
- **WHEN** on recherche une définition `function Invoke-Maven`, `function Resolve-AbsolutePath` ou `function Invoke-NexusJson` dans `scripts/` hors `scripts/lib/` et `scripts/archive/`
- **THEN** aucune définition n'est trouvée

#### Scenario: Le contrat refuse une redéfinition
- **WHEN** un script actif de `scripts/` définit sa propre fonction `Invoke-Maven`
- **THEN** `bash .claude/scripts/contracts.sh` échoue en nommant le script

### Requirement: Maven est appelé par le wrapper vérifié
Les scripts actifs SHALL appeler Maven uniquement via `Invoke-Maven`, qui utilise `mvnw.cmd` ou `mvnw`. En CI (`CI` ou `GITHUB_ACTIONS` définie), un échec du wrapper SHALL faire échouer le script sans repli sur un Maven installé.

#### Scenario: Wrapper indisponible en CI
- **WHEN** `GITHUB_ACTIONS` est définie et le wrapper renvoie un code non nul
- **THEN** `Invoke-Maven` lève une exception qui mentionne l'exigence du wrapper vérifié et le script échoue

#### Scenario: Code retour Maven propagé
- **WHEN** la commande Maven lancée par `Invoke-Maven` se termine avec un code non nul
- **THEN** le script appelant échoue avec ce code

#### Scenario: Appel direct refusé par le contrat
- **WHEN** un script actif de `scripts/` invoque directement l'exécutable Maven installé
- **THEN** `bash .claude/scripts/contracts.sh` échoue en nommant le script

### Requirement: Les scripts obsolètes ne sont pas des points d'entrée
Les scripts d'itération non référencés par la documentation courante SHALL se trouver sous `scripts/archive/`, et la documentation courante SHALL désigner `scripts/validate-phase-6.ps1` et `scripts/self-smoke.ps1` comme points d'entrée de validation.

#### Scenario: Script d'itération obsolète
- **WHEN** on liste les scripts `validate-iteration-11.ps1` à `validate-iteration-14.ps1`
- **THEN** ils se trouvent sous `scripts/archive/` et non à la racine de `scripts/`

#### Scenario: Aucune référence vers un artefact inexistant
- **WHEN** on recherche `0.1.0-SNAPSHOT` dans les scripts de `scripts/` hors `scripts/archive/`
- **THEN** aucune occurrence n'est trouvée
