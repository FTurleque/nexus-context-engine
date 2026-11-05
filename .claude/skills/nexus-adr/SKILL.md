---
name: nexus-adr
description: Décider si un changement NEXUS justifie un Architecture Decision Record puis le rédiger au format Archgate du projet (GEN-0057), avec préfixe de domaine, numéro suivant, liens Complète/Remplace, archivage de l'ADR remplacé et mise à jour de l'index, sans jamais réécrire une décision acceptée. Utiliser pour « faut-il un ADR », « documente cette décision », ou après un changement de format persistant, de borne ou de contrat public.
argument-hint: "<titre de la décision>"
---

# ADR NEXUS

Convention : [GEN-0057](../../../docs/architecture/adr/GEN-0057-tenir-le-registre-d-adr-au-format-archgate.md) (format Archgate, registre `docs/architecture/adr/`).

## Faut-il un ADR ?

Oui si la décision est **durable et structurante** et tranche entre des options réelles : format persistant ou migration de schéma, contrat public CLI/REST/MCP, nouvelle borne ou politique de sécurité, dépendance majeure ou nouveau provider, changement d'une décision existante.
Non pour un simple durcissement d'implémentation, un refactoring sans changement de contrat, une montée de version mineure : le dire explicitement.

## Rédaction

1. Numéro suivant : le dernier de l'index `docs/architecture/adr/README.md` (archive comprise) plus un, par exemple `ls docs/architecture/adr docs/architecture/adr/archive | grep -oE '[0-9]{4}-' | sort | tail -1`. Un numéro n'est jamais réutilisé ni renuméroté.
2. Domaine et préfixe : `architecture` → `ARCH`, `backend` → `BE`, `data` → `DATA`, `general` → `GEN`, `security` → `SEC` (`.archgate/config.json` déclare `security`).
3. Copier `docs/architecture/adr/gabarit/template.md` en `docs/architecture/adr/{PRÉFIXE}-{NNNN}-verbe-a-l-infinitif-objet.md` (kebab-case français, sans accents) :
   - en-tête YAML en **ligne 1** : `id: {PRÉFIXE}-{NNNN}` (début du nom de fichier), `title`, `domain`, `rules: false` (`true` seulement avec un compagnon `{même nom}.rules.ts` vu en échec sur une violation ; `files` si la règle ne regarde qu'une partie du dépôt) ;
   - titre `#` égal au `title`, sans préfixe `ADR-NNNN` ;
   - en tête de corps : `- **Statut** : Proposed`, `- **Date** : AAAA-MM-JJ`, puis `- **Complète** :` ou `- **Remplace** :` si applicable ;
   - sections `Context`, `Decision`, `Do's and Don'ts` (`Do`, `Don't`, tirés du seul texte de la décision), `Consequences`, `Compliance and Enforcement` (contrôles qui existent vraiment), `References` ; les autres sections gardent leur titre français.
4. Rédiger en français : contexte factuel (mesures, PR, runs), décision précise et vérifiable, conséquences, validation (tests, contrats, benchmarks).
5. Statut `Proposed` tant que l'utilisateur n'a pas validé ; ne jamais passer `Accepted` sans sa décision explicite.
6. Ajouter la ligne dans la table « Index » de `docs/architecture/adr/README.md` (`| [{PRÉFIXE}-{NNNN}]({fichier}.md) | Titre | Date | Statut |`) et dans `docs/architecture/arc42/09-decisions.md` ; si l'ADR touche l'architecture courante, le référencer depuis `docs/architecture/overview.md` ou le document développeur concerné.
7. Vérifier : `archgate check` (aucune violation ; la règle de GEN-0057 contrôle nom, `id`, préfixe, numéro, titre et index) et `archgate adr list` (le nouvel ADR y figure). Ne jamais utiliser `archgate init` ni `archgate adr create`, ni écrire dans `.archgate/adrs/`.

## Remplacer une décision

Ne jamais réécrire un ADR accepté (le hook demande confirmation). Créer un nouvel ADR « Remplace : … » ; dans l'ancien, ne changer que le statut (`Superseded`), ajouter « Remplacé par : … » et un bandeau en tête, puis le déplacer par `git mv` dans `docs/architecture/adr/archive/` en gardant son nom ; mettre à jour ses liens entrants, l'index (section « Archive ») et `09-decisions.md`. Un amendement partiel donne un ADR consolidé qui remplace l'ancien. Un ADR n'est jamais supprimé.

Pour un audit global des ADR, utiliser plutôt les skills `adr-audit` / `adr-check` s'ils sont disponibles.
