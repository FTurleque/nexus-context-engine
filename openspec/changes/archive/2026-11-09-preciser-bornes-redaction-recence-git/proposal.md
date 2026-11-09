# Proposition

## Why

Deux comportements bornés du cœur sont vérifiés dans le code mais absents des specs de référence : le budget du signal de récence Git (512 chemins candidats, 1 024 chemins par commit, 4 096 cumulés) et les bornes étroites de redaction (1 024 caractères pour la partie utilisateur d'une URL, 255 pour le corps d'un jeton à préfixe). Sans exigence, ces bornes peuvent changer sans que rien ne l'oppose, et les tests des bornes de commit et de redaction n'existent pas. L'enrichissement des specs de référence du 2026-11-04 les a relevés comme écarts (`docs/developer/git-context.md#openspec-git-recency-diagnostics`, `docs/developer/secret-redaction.md#openspec-secret-redaction`).

## What Changes

- [Existant vérifié] `GitRecencyCandidateEnricher` ignore le signal de récence, renvoie les candidats inchangés et ajoute la raison `récence Git ignorée : budget de chemins dépassé` au-delà de 512 chemins candidats, ou quand les modifications des chemins candidats sur les 50 derniers commits dépassent 1 024 entrées dans un commit ou 4 096 cumulées ; un historique plus large, tous chemins confondus, est relu sur les seuls chemins candidats sans mise en cache (`core/src/main/java/com/nexus/context/source/git/GitRecencyCandidateEnricher.java:50-53`, `:79`, `:140-141`).
- [Existant vérifié] `SensitiveContentRedactor` refuse un contenu portant une partie utilisateur d'URL de plus de 1 024 caractères ou un jeton à préfixe fixe dont le corps dépasse la borne de son motif (`core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:27`, `:77-85`).
- [Proposition] Ajouter à `git-recency-diagnostics` l'exigence « Le signal de récence Git est borné en chemins ».
- [Proposition] Ajouter à `secret-redaction` l'exigence « Les bornes étroites de redaction refusent le contenu ».
- [Proposition] Écrire les tests manquants des bornes de commit (1 024, 4 096) et des bornes étroites de redaction.
- [Écart] Le texte de l'exigence « Une valeur sensible trop longue n'exclut que son fichier » parle d'un seuil unique de 4 096 caractères et d'une exclusion « du traitement », alors que le code applique aussi les bornes étroites et conserve le fichier dans l'index structurel (`core/src/main/java/com/nexus/index/service/IndexDocumentPublisher.java:89-92`, `:182-192`).
- [Proposition] Réécrire cette exigence (MODIFIED) sur le comportement du code : exclusion des seuls index lexical et sémantique, fichier conservé dans l'index structurel, diagnostic `INDEX_REDACTION_LIMIT`.

## Type de changement

fonctionnel

## Périmètre et exclusions

**Dans le périmètre :**
- Rendre contractuelles les bornes ci-dessus telles que le code les applique.
- Aligner le texte de « Une valeur sensible trop longue n'exclut que son fichier » sur le code.
- Les tests qui les prouvent.

**Hors périmètre :**
- Modifier une borne ou le comportement du code.
- Réécrire les autres exigences existantes de `secret-redaction` et de `git-recency-diagnostics`.
- Les autres propositions issues de l'enrichissement (parité inter-canaux, jonctions Windows, runner `real-ollama`) : elles restent dans les pages durables et le bilan `docs/project/history/openspec-couverture-2026-11-04.md`.

## Capabilities

### New Capabilities

### Modified Capabilities
- `git-recency-diagnostics`: ajout d'une exigence sur les budgets de chemins du signal de récence.
- `secret-redaction`: ajout d'une exigence sur les bornes étroites (partie utilisateur d'URL, corps de jeton à préfixe) ; réécriture de « Une valeur sensible trop longue n'exclut que son fichier ».

## Impact

- Code : aucun changement de comportement ; tests ajoutés dans `core/src/test/java/com/nexus/context/source/git/GitRecencyCandidateEnricherTest.java`, `core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java` et `core/src/test/java/com/nexus/index/ProjectIndexingServiceTest.java`.
- Documentation durable : `docs/developer/git-context.md`, `docs/developer/secret-redaction.md`, `docs/developer/openspec-traceability.md`.
- Specs : deux ajouts d'exigence et une exigence réécrite.

## Décisions du mainteneur

- Les bornes 512 / 1 024 / 4 096 de la récence Git sont un contrat : l'exigence les chiffre.
- Les jetons à préfixe sont bornés par « la borne de leur motif » : seule la borne du scénario `ghp_` (corps de 255 caractères masqué, 256 refusé) est chiffrée.
- L'exigence « Une valeur sensible trop longue n'exclut que son fichier » est alignée sur le code par une exigence MODIFIED.
