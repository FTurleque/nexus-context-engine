---
name: nexus-adr
description: Décider si un changement NEXUS justifie un Architecture Decision Record puis le rédiger au format MADR français du projet, avec numérotation, liens Complète/Remplace et mise à jour de l'index, sans jamais réécrire une décision acceptée. Utiliser pour « faut-il un ADR », « documente cette décision », ou après un changement de format persistant, de borne ou de contrat public.
argument-hint: "<titre de la décision>"
---

# ADR NEXUS

## Faut-il un ADR ?

Oui si la décision est **durable et structurante** : format persistant ou migration de schéma, contrat public CLI/REST/MCP, nouvelle borne ou politique de sécurité, dépendance majeure ou nouveau provider, changement d'une décision existante.
Non pour un simple durcissement d'implémentation, un refactoring sans changement de contrat, une montée de version mineure : le dire explicitement.

## Rédaction

1. Numéro suivant : `ls docs/adr | grep -E '^[0-9]{4}-' | sort | tail -1`.
2. Fichier `docs/adr/NNNN-verbe-a-l-infinitif-objet.md` (kebab-case français, sans accents), à partir de `docs/adr/template.md`. Les ADR récents utilisent l'en-tête court :
   ```markdown
   # ADR-NNNN — Titre exprimant la décision

   - Statut : Proposé
   - Date : AAAA-MM-JJ
   - Complète : ADR-XXXX        (ou « Remplace : ADR-XXXX »)

   ## Contexte
   ## Décision
   ## Conséquences et validation
   ```
3. Rédiger en français : contexte factuel (mesures, PR, runs), décision précise et vérifiable, conséquences, validation (tests, contrats, benchmarks).
4. Statut `Proposé` tant que l'utilisateur n'a pas validé ; ne jamais passer `Accepté` sans sa décision explicite.
5. Ajouter la ligne dans le tableau de `docs/adr/README.md` (`| [ADR-NNNN](fichier.md) | Titre | Statut |`) et, si l'ADR touche l'architecture courante, référencer-le depuis `docs/architecture.md` ou le document développeur concerné.

## Remplacer une décision

Ne jamais réécrire un ADR accepté (le hook demande confirmation). Créer un nouvel ADR « Remplace : ADR-XXXX », puis n'ajouter dans l'ancien qu'une ligne « Remplacé par : ADR-NNNN » et mettre son statut à jour dans l'index.

Pour un audit global des ADR, utiliser plutôt les skills `adr-audit` / `adr-check` s'ils sont disponibles.
