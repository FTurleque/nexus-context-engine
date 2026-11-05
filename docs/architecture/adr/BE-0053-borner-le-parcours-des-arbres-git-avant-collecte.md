---
id: BE-0053
title: "Borner le parcours des arbres Git avant collecte"
domain: backend
rules: true
files: ["core/src/main/java/com/nexus/context/source/git/LocalGitContextSourceProvider.java"]
---

# Borner le parcours des arbres Git avant collecte

- **Statut** : Accepted
- **Date** : 2026-09-12
- **Complète** : ADR-0035

## Context

Le contexte Git est soumis à un budget de travail partagé et à une limite de
chemins par commit. Un appel à `DiffFormatter.scan` matérialisait pourtant tout le
diff avant le premier débit du budget, ce qui rendait les limites tardives en cas de
commit volumineux.

## Decision

`LocalGitContextSourceProvider` parcourt directement les deux arbres avec un
`TreeWalk` récursif et `TreeFilter.ANY_DIFF`. Chaque entrée est débitée avant sa
collecte ; les limites par commit et cumulées restent appliquées immédiatement.
La détection coûteuse des renommages n'est pas nécessaire pour cette découverte
bornée.

## Do's and Don'ts

### Do

- Parcourir directement les deux arbres dans `LocalGitContextSourceProvider` avec un `TreeWalk` récursif et `TreeFilter.ANY_DIFF`.
- Débiter chaque entrée du budget avant sa collecte.
- Appliquer immédiatement les limites par commit et cumulées.

### Don't

- Ne pas matérialiser le diff (`DiffFormatter.scan`) avant le premier débit du budget.
- Ne pas ajouter la détection coûteuse des renommages à cette découverte bornée.

## Conséquences et validation

Le travail et la mémoire consommés avant un refus dépendent désormais du budget
autorisé, au prix de la perte du signal de renommage dans cette étape. Les chemins
modifiés restent déterministes et soumis aux mêmes plafonds de résultat.

Une sonde avec un commit synthétique très large et un budget d'une visite vérifie
que le parcours s'arrête avant matérialisation complète et renvoie
`ContextDiscoveryLimitExceededException`. Les tests Git existants couvrent les
résultats bornés et les dégradations prévues.

## Compliance and Enforcement

- Tests (`core`, lancés par `mvnw clean install`) : `core/src/test/java/com/nexus/context/source/git/LocalGitContextSourceProviderTest.java` (`buildsOnlyGitContextRelatedToTargetPaths`, `truncatesLargeTargetDiffDeterministically`). La sonde décrite plus haut (commit synthétique très large, budget d'une visite, `ContextDiscoveryLimitExceededException`) n'a pas été retrouvée parmi les tests du dépôt.
- Règle Archgate `BE-0053-borner-le-parcours-des-arbres-git-avant-collecte.rules.ts` : `git-trees-walked-before-collect` exige dans `LocalGitContextSourceProvider` un `new TreeWalk(...)`, `setRecursive(true)` et `TreeFilter.ANY_DIFF`, et y refuse `DiffFormatter`, `RenameDetector` et `setDetectRenames` ; commentaires et littéraux effacés.
