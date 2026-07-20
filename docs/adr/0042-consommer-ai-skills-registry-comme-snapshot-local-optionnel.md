---
status: accepted
date: 2026-07-20
---

# ADR-0042 — Consommer AI Skills Registry comme snapshot local optionnel

## Contexte

NEXUS sait déjà découvrir des Agent Skills versionnés dans le repository courant via `SkillSourceProvider`. L'Itération 14 doit permettre de réutiliser le dépôt externe AI Skills Registry sans rendre ce dépôt, le réseau ou Git obligatoires pendant la construction d'un `ContextBundle`.

## Décision

NEXUS consomme AI Skills Registry sous la forme d'un snapshot local placé sous `.nexus/registry/skills` dans le projet courant.

`AiSkillsRegistryProvider` découvre uniquement les métadonnées des fichiers `SKILL.md`. Le corps complet reste chargé par `SkillLoader` après sélection, conformément à la divulgation progressive existante.

Les skills locaux du projet conservent une priorité supérieure à ceux du registre. En cas de nom identique, la déduplication existante conserve donc la définition locale.

L'absence du snapshot ne constitue pas une erreur et ne modifie pas le comportement normal de NEXUS.

## Conséquences

- aucune requête réseau n'est effectuée pendant la construction du contexte ;
- NEXUS reste utilisable sans AI Skills Registry ;
- la logique de sélection, de déduplication et de chargement progressif reste commune ;
- un projet peut surcharger localement un skill partagé ;
- la synchronisation du snapshot reste une opération externe explicite ;
- `.nexus/registry` n'est pas versionné avec le projet.

## Confirmation

La décision est respectée si :

- zéro skill de registre est retourné lorsque `.nexus/registry/skills` est absent ;
- les métadonnées sont découvertes sans charger le corps complet du `SKILL.md` ;
- le corps complet n'est chargé qu'après sélection ;
- un skill local de même nom gagne sur le skill du registre ;
- les tests du cœur et le self-smoke restent verts sans snapshot de registre.

## Décisions liées

- ADR-0005 — Adopter un fonctionnement local-first et des intégrations externes opt-in.
- ADR-0012 — Réutiliser les standards existants pour instructions et skills.
- ADR-0017 — Découpler NEXUS des outils et orchestrateurs externes.
- ADR-0034 — Adopter la divulgation progressive pour les Agent Skills.
