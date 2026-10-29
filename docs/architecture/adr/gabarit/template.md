---
id: ARCH-NNNN
title: "Titre court qui énonce la décision"
domain: architecture
rules: false
---

# Titre court qui énonce la décision

- **Statut** : Proposed | Accepted | Superseded | Rejected
- **Date** : AAAA-MM-JJ
- **Origine** : itération, audit, PR ou changement OpenSpec
- **Complète** : ADR complété (si applicable)
- **Remplace** : ADR remplacé (si applicable)
- **Remplacé par** : ADR remplaçant (à remplir si cet ADR est remplacé plus tard)

Copier ce fichier en `docs/architecture/adr/{PRÉFIXE}-{NNNN}-verbe-a-l-infinitif-objet.md` (kebab-case français, sans accents) : préfixe du domaine (`architecture` → `ARCH`, `backend` → `BE`, `data` → `DATA`, `general` → `GEN`, `security` → `SEC`) et numéro suivant de l'index `README.md`. L'en-tête YAML reste en ligne 1 ; `id` reprend le début du nom de fichier (`ARCH-NNNN`), le titre `#` reprend exactement `title`. `rules: true` seulement avec un fichier compagnon `{même nom}.rules.ts` dont la règle a été vue en échec sur une violation ; `files` borne le périmètre d'une règle qui ne regarde qu'une partie du dépôt. Les titres de section restent ceux d'Archgate ; le texte est en français. Supprimer ce paragraphe dans l'ADR.

## Context

Ce qui existait au moment de la décision (faits vérifiés : `fichier:ligne`, mesures, runs de CI), ce qui posait problème, ce qui devait être préservé et la question exacte à laquelle l'ADR répond.

## Options envisagées

- **Option A** : avantages, inconvénients.
- **Option B** : avantages, inconvénients.

## Decision

**Option retenue : Option A.**

Ce qui est décidé, ce que la décision impose, ce qu'elle autorise et ce qu'elle ne décide pas ; conditions prouvables (test, contrat, gate).

## Do's and Don'ts

### Do

- Ce que la décision impose, en une phrase vérifiable.

### Don't

- Ce que la décision interdit.

## Consequences

### Positives

- …

### Négatives

- …

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Risque identifié | Faible / Moyen / Élevé | Mesure prévue |

## Compliance and Enforcement

- Contrôle automatique : test JUnit, contrat de `bash .claude/scripts/contracts.sh`, étape de `scripts/self-smoke.ps1`, workflow, hook ou règle Archgate compagnon — ce qu'il vérifie.
- Revue : ce qu'un relecteur doit vérifier.

## Conditions de réexamen

La décision est réévaluée si : condition mesurable, nouvelle contrainte, évolution d'un standard. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Mise en œuvre

(Complété à l'implémentation : écarts à la conception, preuves.)

## References

- ADR liés, changement OpenSpec, sections arc42, documentation externe.
