# ADR-0053 — Borner le parcours des arbres Git avant collecte

- Statut : Accepté
- Date : 2026-09-12
- Complète : ADR-0035

## Contexte

Le contexte Git est soumis à un budget de travail partagé et à une limite de
chemins par commit. Un appel à `DiffFormatter.scan` matérialisait pourtant tout le
diff avant le premier débit du budget, ce qui rendait les limites tardives en cas de
commit volumineux.

## Décision

`LocalGitContextSourceProvider` parcourt directement les deux arbres avec un
`TreeWalk` récursif et `TreeFilter.ANY_DIFF`. Chaque entrée est débitée avant sa
collecte ; les limites par commit et cumulées restent appliquées immédiatement.
La détection coûteuse des renommages n'est pas nécessaire pour cette découverte
bornée.

## Conséquences et validation

Le travail et la mémoire consommés avant un refus dépendent désormais du budget
autorisé, au prix de la perte du signal de renommage dans cette étape. Les chemins
modifiés restent déterministes et soumis aux mêmes plafonds de résultat.

Une sonde avec un commit synthétique très large et un budget d'une visite vérifie
que le parcours s'arrête avant matérialisation complète et renvoie
`ContextDiscoveryLimitExceededException`. Les tests Git existants couvrent les
résultats bornés et les dégradations prévues.
