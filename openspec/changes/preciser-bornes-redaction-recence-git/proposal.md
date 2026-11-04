# Proposition

## Why

Deux comportements bornés du cœur sont vérifiés dans le code mais absents des specs de référence : le budget du signal de récence Git (512 chemins candidats, 1 024 chemins par commit, 4 096 cumulés) et les bornes étroites de redaction (1 024 caractères pour la partie utilisateur d'une URL, 255 pour le corps d'un jeton à préfixe). Sans exigence, ces bornes peuvent changer sans que rien ne l'oppose, et les tests des bornes de commit et de redaction n'existent pas. L'enrichissement des specs de référence du 2026-11-04 les a relevés comme écarts (`docs/developer/git-context.md#openspec-git-recency-diagnostics`, `docs/developer/secret-redaction.md#openspec-secret-redaction`).

## What Changes

- [Existant vérifié] `GitRecencyCandidateEnricher` ignore le signal de récence, renvoie les candidats inchangés et ajoute la raison `récence Git ignorée : budget de chemins dépassé` au-delà de 512 chemins candidats, ou quand les 50 derniers commits modifient plus de 1 024 chemins chacun ou 4 096 cumulés (`core/src/main/java/com/nexus/context/source/git/GitRecencyCandidateEnricher.java:50-53`, `:79`, `:140-141`).
- [Existant vérifié] `SensitiveContentRedactor` refuse un contenu portant une partie utilisateur d'URL de plus de 1 024 caractères ou un jeton à préfixe fixe dont le corps dépasse la borne de son motif (`core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:27`, `:77-85`).
- [Proposition] Ajouter à `git-recency-diagnostics` l'exigence « Le signal de récence Git est borné en chemins ».
- [Proposition] Ajouter à `secret-redaction` l'exigence « Les bornes étroites de redaction refusent le contenu ».
- [Proposition] Écrire les tests manquants des bornes de commit (1 024, 4 096) et des bornes étroites de redaction.
- [Écart] Le texte de l'exigence « Une valeur sensible trop longue n'exclut que son fichier » parle d'un seuil unique de 4 096 caractères et d'une exclusion « du traitement », alors que le code applique des seuils plus étroits et conserve le fichier dans l'index structurel (`core/src/main/java/com/nexus/index/IndexDocumentPublisher.java:73-86`). Ce changement n'y touche pas : voir les questions ouvertes.

## Type de changement

fonctionnel

## Périmètre et exclusions

**Dans le périmètre :**
- Rendre contractuelles, si elles sont validées, les bornes ci-dessus telles que le code les applique.
- Les tests qui les prouvent.

**Hors périmètre :**
- Modifier une borne ou le comportement du code.
- Réécrire les exigences existantes de `secret-redaction` et de `git-recency-diagnostics`.
- Les autres propositions issues de l'enrichissement (parité inter-canaux, jonctions Windows, runner `real-ollama`) : elles restent dans les pages durables et le bilan `docs/project/history/openspec-couverture-2026-11-04.md`.

## Capabilities

### New Capabilities

### Modified Capabilities
- `git-recency-diagnostics`: ajout d'une exigence sur les budgets de chemins du signal de récence.
- `secret-redaction`: ajout d'une exigence sur les bornes étroites (partie utilisateur d'URL, corps de jeton à préfixe).

## Impact

- Code : aucun changement de comportement ; tests ajoutés dans `core/src/test/java/com/nexus/context/source/git/GitRecencyCandidateEnricherTest.java` et `core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java`.
- Documentation durable : `docs/developer/git-context.md`, `docs/developer/secret-redaction.md`, `docs/developer/openspec-traceability.md`.
- Specs : deux ajouts d'exigence, à synchroniser seulement après validation.

## Questions ouvertes

- [Question] Les bornes 512 / 1 024 / 4 096 sont-elles un contrat ou des réglages d'implémentation susceptibles de varier ? Si ce sont des réglages, l'exigence se limite au comportement « budget dépassé, signal ignoré » sans chiffres.
- [Question] Quelles bornes exactes par préfixe de jeton (`sk-`, `xox?-`, `glpat-`, `ghp_`…) la spec doit-elle fixer ? Le code utilise `255` pour la plupart et une borne composée pour `sk-` (`SensitiveContentRedactor.java:84-85`).
- [Question] Faut-il aligner le texte de « Une valeur sensible trop longue n'exclut que son fichier » sur le code (fichier conservé dans l'index structurel, seuils étroits) par une exigence MODIFIED, ou aligner le code sur le texte ? C'est un choix métier ; ce changement ne le tranche pas.
