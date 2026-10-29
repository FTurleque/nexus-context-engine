# ADR-0056 — Regrouper le registre des ADR dans la documentation d'architecture

> **Archivé le 2026-10-29** : remplacé par [GEN-0057](../GEN-0057-tenir-le-registre-d-adr-au-format-archgate.md) (registre au format Archgate, à l'emplacement arc42). Ce document est conservé pour l'historique ; il n'est plus lu par Archgate.

- **Statut** : Superseded
- **Date** : 2026-10-29
- **Complète** : ADR-0000
- **Remplacé par** : [GEN-0057](../GEN-0057-tenir-le-registre-d-adr-au-format-archgate.md)

## Contexte

La documentation d'architecture de NEXUS suit arc42 dans `docs/architecture/`.
La section 9 (`arc42/09-decisions.md`) est l'index des décisions, et arc42
range les ADR à côté des vues qu'ils justifient. Le dépôt comportait deux
répertoires d'ADR : celui qui portait les décisions, à la racine de `docs/`,
et un `docs/architecture/adr/` qui ne contenait qu'un README et un gabarit.
Deux emplacements laissent un doute sur l'endroit où lire et écrire une
décision, et sur l'index qui fait foi.

## Facteurs et options

Un seul registre, situé là où arc42 l'attend, avec les décisions acceptées
conservées telles quelles. Deux options sont étudiées :

- garder le registre à la racine de `docs/` et supprimer le répertoire vide
  de la documentation d'architecture ; arc42 garde alors ses ADR hors de son
  arborescence ;
- placer le registre dans `docs/architecture/adr/`, à côté des vues arc42,
  en déplaçant les fichiers sans modifier les décisions.

La seconde option est retenue : elle donne un seul point d'entrée à la
documentation d'architecture et à ses décisions.

## Décision

Le registre unique des ADR de NEXUS est `docs/architecture/adr/`. Il contient
les ADR, leur index `README.md` et le gabarit `template.md`. Les ADR y gardent
leur numéro, leur nom de fichier, leur format MADR et leur contenu ; seuls les
liens relatifs sortants sont recalculés pour la nouvelle profondeur. Les règles
d'ADR-0000 (format, numérotation, append-only, remplacement explicite)
s'appliquent sans changement à ce répertoire.

Les hooks Claude Code, le skill `nexus-adr`, les règles de documentation, les
filtres de chemins des workflows et les corpus de tests sémantiques désignent
ce répertoire. Les documents datés de `docs/audits/` gardent les chemins de
leur date.

## Conséquences

- Un lecteur trouve les vues arc42 et les décisions qui les justifient sous
  `docs/architecture/`.
- Les liens externes vers l'ancien emplacement, et ceux des audits datés, ne
  résolvent plus.
- Le format des ADR et leur outillage (Archgate notamment) restent à décider
  séparément ; ce choix ne présume pas du format.

## Validation

`bash .claude/scripts/contracts.sh`, les tests des hooks
(`.claude/hooks/tests/Test-Hooks.ps1`) et une vérification des liens Markdown
internes du dépôt (hors `docs/audits/`) passent sur ce registre.
