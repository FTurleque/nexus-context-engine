# Tasks

## 1. Tests de la récence Git

- [x] 1.1 Compléter les tests d'ordre N / N+1 du budget de candidats pour vérifier qu'aucun `WARNING` n'est émis ; couvre `git-recency-diagnostics › Le signal de récence Git est borné en chemins` (scénarios « Plus de 512 chemins candidats » et « Exactement 512 chemins candidats ») ; vérifier : `./mvnw -B -ntp -pl core test -Dtest=GitRecencyCandidateEnricherTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [x] 1.2 Écrire les tests du commit à 1 024 et 1 025 chemins (récence calculée sur les seuls candidats) et du cumul des chemins candidats à 4 096 et 4 097 ; couvre les scénarios « Commit au-delà de 1 024 chemins », « Cumul des chemins candidats au-delà de 4 096 » et « à la borne » ; vérifier : même commande

## 2. Tests de redaction

- [x] 2.1 Écrire les tests de la partie utilisateur d'URL à 1 024 caractères (masquée) et 1 025 (refusée, message sans valeur) ; couvre `secret-redaction › Les bornes étroites de redaction refusent le contenu` (scénarios « Partie utilisateur d'URL trop longue » et « à la borne ») ; vérifier : `./mvnw -B -ntp -pl core test -Dtest=SensitiveContentRedactorTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [x] 2.2 Écrire le test d'un jeton `ghp_` dont le corps compte 255 caractères (masqué) et 256 (refusé) ; couvre le scénario « Jeton à préfixe trop long » ; vérifier : même commande

- [x] 2.3 Vérifier dans `ProjectIndexingServiceTest#anOversizedSecretExcludesOnlyItsFileFromDerivedIndexes` que le fichier refusé reste enregistré dans l'index structurel ; couvre `secret-redaction › Une valeur sensible trop longue n'exclut que son fichier` (scénario « Fichier conservé dans l'index structurel ») ; vérifier : `./mvnw -B -ntp -pl core test -Dtest=ProjectIndexingServiceTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`

## 3. Documentation durable

- [x] 3.1 Reporter les références de tests dans la matrice `docs/developer/openspec-traceability.md` après synchronisation des specs, et retirer les mentions « Ce comportement n'est pas dans la spec » de `docs/developer/git-context.md` et `docs/developer/secret-redaction.md` qui deviennent fausses ; vérifier : `bash .claude/scripts/contracts.sh`

## Workflow follow-up

- Réponses aux trois questions de la proposition : consignées dans `proposal.md` (« Décisions du mainteneur »).
- Synchroniser les specs et archiver le changement une fois les tâches 1 à 3 vérifiées.
