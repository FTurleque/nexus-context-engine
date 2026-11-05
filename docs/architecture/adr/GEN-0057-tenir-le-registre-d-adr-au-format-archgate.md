---
id: GEN-0057
title: "Tenir le registre d'ADR au format Archgate, à l'emplacement arc42"
domain: general
rules: true
files: ["docs/architecture/adr/**", ".archgate/**"]
---

# Tenir le registre d'ADR au format Archgate, à l'emplacement arc42

- **Statut** : Accepted
- **Date** : 2026-10-29
- **Origine** : décision du propriétaire du 2026-10-29 (gouvernance Archgate alignée sur celle de MINOS, GEN-0073)
- **Remplace** : [ADR-0000](archive/0000-adopter-les-adr-madr.md) (format MADR et gouvernance des ADR) et [ADR-0056](archive/0056-regrouper-le-registre-des-adr-dans-la-documentation-d-architecture.md) (emplacement du registre)
- **Remplacé par** : —

## Context

ADR-0000 a fixé un format MADR 4 adapté en français (front matter `status` / `date`, sections « Contexte et problème », « Décision retenue »…), une numérotation séquentielle sur quatre chiffres (`NNNN-slug.md`) et une gouvernance append-only. ADR-0056 a regroupé le registre dans `docs/architecture/adr/`, à l'emplacement que prévoit arc42 (section 9, `docs/architecture/arc42/09-decisions.md`), et laissé le format et l'outillage à décider séparément. Les 57 ADR suivaient en pratique trois formats d'en-tête de statut (front matter, liste à puces, ligne libre).

Archgate lit les ADR et exécute des règles TypeScript qui leur sont rattachées. Par défaut il lit `.archgate/adrs/` ; `.archgate/config.json` accepte `paths.adrs`. Son schéma impose un en-tête YAML en ligne 1 (`id`, `title`, `domain`, `rules`, `files` facultatif) ; sa convention nomme un ADR `{ID}-{slug}.md`, avec un `id` formé du préfixe du domaine et d'un numéro (`ARCH-001`) ; ses résumés pour agents (`archgate review-context`) lisent les sections intitulées « Decision » et « Do's and Don'ts ». MINOS a adopté cette convention (GEN-0073) ; NEXUS s'y aligne.

Les numéros des ADR NEXUS sont uniques dans tout le registre et cités dans la documentation courante, les hooks Claude Code, les filtres de chemins des workflows et les corpus de tests sémantiques : ils ne changent pas.

Options considérées pour l'identifiant :

- **`ADR-NNNN` pour tous** : numéro conservé, mais `id` et nom de fichier hors de la convention Archgate.
- **Préfixe du domaine et numérotation par domaine** (`ARCH-001`, `BE-001`) : convention Archgate stricte, au prix d'une renumérotation de tout le registre.
- **Préfixe du domaine et numéro unique conservé** (`ARCH-0039`) : convention Archgate `{ID}-{slug}.md`, numéros inchangés, identique à MINOS.

## Decision

1. `docs/architecture/adr/` est le registre unique ; `.archgate/config.json` y pointe (`paths.adrs`), déclare le domaine `security` (`SEC`) et la base `origin/develop` ; `.archgate/adrs/` ne contient qu'un `.gitkeep`.
2. Un ADR s'appelle `{PRÉFIXE}-{NNNN}-{slug}.md`, `id: {PRÉFIXE}-{NNNN}` : préfixe du domaine (`ARCH`, `BE`, `DATA`, `GEN`, `SEC`), numéro unique dans le registre, pris à la suite de l'index, jamais renuméroté ni réutilisé ; slug en kebab-case français sans accents ; titre `#` égal au `title`.
3. Sections aux titres d'Archgate (`Context`, `Decision`, `Do's and Don'ts`, `Consequences`, `Compliance and Enforcement`, `References`), les autres gardent leur titre ; statut (`Proposed`, `Accepted`, `Superseded`, `Rejected`) et date en tête de corps ; texte en français.
4. Une règle Archgate vit à côté de son ADR (`{même nom}.rules.ts`, `rules: true`, identifiants en kebab-case anglais, `files` si elle ne regarde qu'une partie du dépôt), a été vue en échec sur une violation et complète les tests, contrats et hooks sans les remplacer.
5. Un ADR accepté ne se réécrit pas : seules les corrections mineures sans changement de sens et les liens « Complété par / Remplacé par » sont admis. Une décision changée est remplacée par un nouvel ADR ; un amendement partiel donne un ADR consolidé. Les options rejetées sont documentées dans l'ADR de la décision.
6. Un ADR `Superseded`, `Rejected` ou reclassé part dans `archive/` (non lu par Archgate), sous son nom d'origine, avec un bandeau vers son remplaçant ; il n'est jamais supprimé.
7. Un écart entre décision et code s'écrit dans une section « Écarts constatés » datée. Le gabarit est `gabarit/template.md` ; `archgate adr create` n'est pas utilisé (il numérote par domaine).

## Do's and Don'ts

### Do

- Copier `gabarit/template.md`, prendre le numéro suivant de l'index, nommer le fichier `{PRÉFIXE}-{NNNN}-{slug}.md` et écrire `id: {PRÉFIXE}-{NNNN}`.
- Garder l'en-tête YAML en ligne 1 et un titre `#` égal au `title`.
- Ajouter chaque nouvel ADR à l'index `README.md` et à `docs/architecture/arc42/09-decisions.md`.
- Remplacer par un ADR consolidé tout ADR modifié en partie, et archiver l'ancien avec son bandeau.
- Écrire « Do's and Don'ts » et « Compliance and Enforcement » à partir du seul texte de la décision et des contrôles qui existent.

### Don't

- Ne pas renuméroter un ADR ni réutiliser un numéro.
- Ne pas recréer de registre ou de pont dans `.archgate/adrs/`, ni utiliser `archgate init` ou `archgate adr create`.
- Ne pas laisser un ADR amendé actif à côté de son amendement.
- Ne pas modifier une décision acceptée pour la faire coïncider avec le code : consigner l'écart.
- Ne pas déclarer `rules: true` sans règle compagnon vue en échec.
- Ne pas supprimer un ADR, même remplacé.

## Consequences

### Positives

- Un seul fichier par décision, lu par les humains, l'index arc42, Archgate et les agents, aux noms de la convention Archgate, comme dans MINOS.
- Les numéros historiques restent valides ; le préfixe dit le domaine au premier regard.
- Un seul format d'en-tête de statut dans tout le registre.

### Négatives

- Les titres de section sont en anglais dans des documents en français.
- Les 55 ADR repris n'ont pas de section « Do's and Don'ts » : elle n'est pas inventée a posteriori, ce qui laisse un avertissement par ADR.
- `README.md` reste dans le dossier sans en-tête : Archgate le signale comme non lu, ce qui interdit le mode `--strict`.
- Les liens externes vers les anciens noms de fichiers, et ceux des audits datés de `docs/audits/`, ne résolvent plus.
- Une règle bornée par `files` n'est exécutée par `archgate check` que si un fichier de son périmètre a changé (ou sur un arbre propre égal à la base).

### Risques

- Une évolution du schéma Archgate impose de reprendre les en-têtes : vérifier `archgate adr list` à chaque montée de version de la CLI.
- `archgate check` écrit `.archgate/rules.d.ts` et `docs/architecture/rules.d.ts` : fichiers générés, ignorés par Git.
- L'attribution d'un domaine à un ADR repris relève d'un jugement ; la corriger impose de renommer le fichier et ses références.

## Compliance and Enforcement

- Règle Archgate `GEN-0057-tenir-le-registre-d-adr-au-format-archgate.rules.ts` : `adr-id-matches-file-name` (nom `{PRÉFIXE}-{NNNN}-…`, `id` égal, préfixe conforme au domaine), `adr-number-is-unique` (registre et archive), `adr-listed-in-index`, `adr-heading-matches-title`, `adr-has-archgate-sections` (avertissement), `archgate-adrs-holds-only-gitkeep`, `rule-helpers-match-template`.
- Fonctions communes des règles : le scanner de sécurité d'Archgate refuse tout import local dans un `*.rules.ts`. Une règle recopie donc à l'identique les fonctions de `gabarit/fonctions-communes.ts` dont elle a besoin (masquage des commentaires et littéraux Java ou XML, numéro de ligne) ; `rule-helpers-match-template` échoue dès qu'une copie diverge. Une correction se fait dans le gabarit, puis dans toutes les copies.
- Hooks Claude Code : `.claude/hooks/guard-edit.ps1` demande confirmation avant de modifier un ADR existant du registre ; `.claude/hooks/post-edit-reminders.ps1` rappelle la mise à jour de l'index ; tests dans `.claude/hooks/tests/Test-Hooks.ps1`.
- `archgate adr list` liste chaque ADR actif ; un ADR absent de la liste a un en-tête invalide.
- Revue : un nouvel ADR part du gabarit, figure dans `README.md` et dans `docs/architecture/arc42/09-decisions.md` (skill `nexus-adr`, commande `/nx-adr`).

## Mise en œuvre

Le 2026-10-29, les 55 ADR actifs ont été renommés par `git mv` (numéro et slug conservés, préfixe du domaine ajouté), leur front matter MADR remplacé par l'en-tête Archgate, leur statut et leur date uniformisés en tête de corps et les sections à équivalence directe retitrées (Contexte / Contexte et problème → `Context`, Décision / Décision retenue → `Decision`, Conséquences → `Consequences`, Références → `References`), sans modification du texte des décisions. Le titre `#` a perdu son préfixe `ADR-NNNN —`, porté désormais par l'`id`. ADR-0000 et ADR-0056 sont archivés dans `archive/`.

## References

- Index du registre : [`README.md`](README.md) ; gabarit : [`gabarit/template.md`](gabarit/template.md) ; section arc42 : [`09-decisions.md`](../arc42/09-decisions.md).
- MINOS : GEN-0073 « Tenir le registre d'ADR au format Archgate, à l'emplacement arc42 » (modèle de cette décision).
- Documentation Archgate : `reference/configuration` (`paths.adrs`), `reference/adr-schema`, `guides/writing-adrs`, `guides/writing-rules`.
