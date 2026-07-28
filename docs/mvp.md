# Définition historique du MVP

> **Document historique.** Cette page conserve le périmètre qui a servi à valider le premier MVP de NEXUS lors des Itérations 0 à 4. Elle ne décrit pas le périmètre actuel du produit.

Pour l'état courant :

- [`README.md`](../README.md) — capacités livrées ;
- [`docs/architecture.md`](architecture.md) — architecture courante ;
- [`docs/roadmap.md`](roadmap.md) — feuille de route active ;
- [`docs/developer/current-limitations.md`](developer/current-limitations.md) — dette et limites vérifiées.

## Objectif du MVP historique

À partir d'un repository Java local et d'une demande en langage naturel, NEXUS devait identifier et classer les fichiers et symboles les plus susceptibles d'être utiles, puis construire un `ContextBundle` respectant un budget de tokens configurable.

## Inclus dans le MVP

- enregistrer et lister des projets locaux ;
- parcourir un repository local en appliquant des règles d'exclusion ;
- détecter les fichiers source Java ;
- analyser Java au moyen d'un `LanguageAnalyzer` basé sur un AST ;
- indexer les fichiers, symboles et relations de base ;
- persister l'index localement ;
- rechercher du contexte par approche lexicale et par symboles ;
- appliquer un classement déterministe et explicable ;
- gérer un budget de tokens configurable ;
- sélectionner des extraits et symboles pertinents plutôt que des fichiers complets lorsque cela suffit ;
- dédupliquer les contenus ;
- produire une sortie lisible par un humain et exploitable par une machine ;
- fournir les commandes CLI nécessaires pour exercer la tranche verticale complète ;
- disposer d'un corpus de qualité et de tests de reproductibilité automatisés.

## Hors périmètre du MVP à cette date

Au moment où le MVP a été défini, les éléments suivants étaient volontairement différés :

- appels obligatoires à un LLM ;
- embeddings externes obligatoires ;
- base de données vectorielle ;
- repositories GitHub/GitLab comme sources ;
- plugins IDE ;
- serveur MCP ;
- AI Skills Registry ;
- intégration JARVIS/Alfred/Brainiac ;
- routage de modèles ;
- multi-langage complet.

Plusieurs de ces capacités ont **depuis été livrées** : MCP, AI Skills Registry, intégration JARVIS, multi-langage lexical et recherche sémantique opt-in. Le maintien de cette liste sert uniquement à préserver la frontière historique du MVP.

## Surface CLI du MVP

```bash
nexus project add ./mon-projet
nexus project list
nexus index mon-projet
nexus search mon-projet "ingestion de documents"
nexus context mon-projet "Corrige le traitement d'upload PDF" --budget 20000 --explain
nexus inspect mon-projet
```

La surface courante comprend en plus notamment `index --deep-java` et `minos-import`. Voir [`docs/developer/cli.md`](developer/cli.md).

## Critères de validation historiques

Le MVP était considéré comme valide lorsque :

1. un projet local pouvait être enregistré puis rechargé dans un processus ultérieur ;
2. l'indexation respectait `.gitignore`, `.nexusignore` et les exclusions sensibles ;
3. les structures Java principales étaient extraites via AST ;
4. une réindexation sans changement restait stable ;
5. une requête retournait des candidats classés de manière déterministe ;
6. chaque candidat pouvait exposer les facteurs de son score ;
7. `ContextBuilder` ne dépassait jamais le budget configuré ;
8. les extraits symboliques étaient privilégiés lorsqu'ils suffisaient ;
9. les fragments dupliqués ou chevauchants étaient fusionnés/supprimés ;
10. `--explain` rendait les sélections/exclusions inspectables ;
11. un corpus de référence mesurait précision et rappel ;
12. le moteur pouvait fonctionner hors ligne une fois les dépendances Maven résolues.

Ces critères ont été validés au terme de l'Itération 4 puis étendus par les itérations suivantes.

## Métriques historiques du MVP

Les métriques minimales retenues dès le MVP étaient :

- `precision@K` ;
- `recall@K` ;
- réduction du contexte ;
- économie estimée de tokens ;
- durée d'indexation ;
- latence de recherche ;
- latence de construction du contexte.

Ce principe reste valable : les valeurs observées constituent des baselines techniques reproductibles, pas des SLA universels.
