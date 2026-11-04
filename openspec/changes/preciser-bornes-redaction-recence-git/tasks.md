# Tasks

## 1. Tests de la récence Git

- [ ] 1.1 Compléter les tests d'ordre N / N+1 du budget de candidats pour vérifier qu'aucun `WARNING` n'est émis ; couvre `git-recency-diagnostics › Le signal de récence Git est borné en chemins` (scénarios « Plus de 512 chemins candidats » et « Exactement 512 chemins candidats ») ; vérifier : `./mvnw -B -ntp -pl core test -Dtest=GitRecencyCandidateEnricherTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 1.2 Écrire le test du commit à 1 025 chemins et celui du cumul au-delà de 4 096 ; couvre le scénario « Commit au-delà de 1 024 chemins » ; vérifier : même commande, deux nouveaux tests verts

## 2. Tests de redaction

- [ ] 2.1 Écrire les tests de la partie utilisateur d'URL à 1 024 caractères (masquée) et 1 025 (refusée, message sans valeur) ; couvre `secret-redaction › Les bornes étroites de redaction refusent le contenu` (scénarios « Partie utilisateur d'URL trop longue » et « à la borne ») ; vérifier : `./mvnw -B -ntp -pl core test -Dtest=SensitiveContentRedactorTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 2.2 Écrire le test d'un jeton `ghp_` de 255 caractères (masqué) et de 256 (refusé) ; couvre le scénario « Jeton à préfixe trop long » ; vérifier : même commande

## 3. Documentation durable

- [ ] 3.1 Reporter les références de tests dans la matrice `docs/developer/openspec-traceability.md` après synchronisation des specs, et retirer les mentions « Ce comportement n'est pas dans la spec » de `docs/developer/git-context.md` et `docs/developer/secret-redaction.md` qui deviennent fausses ; vérifier : `bash .claude/scripts/contracts.sh`

## Workflow follow-up

- Obtenir les réponses aux trois questions de la proposition avant toute synchronisation des specs.
- Ne pas archiver ni synchroniser ce changement sans validation explicite.
