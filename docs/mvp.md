# Définition du MVP

## Objectif

À partir d'un repository Java local et d'une demande en langage naturel, NEXUS identifie et classe les fichiers et symboles les plus susceptibles d'être utiles, puis construit un `ContextBundle` respectant un budget de tokens configurable.

## Inclus dans le périmètre

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

## Explicitement hors périmètre

- appels obligatoires à un LLM ;
- embeddings externes obligatoires ;
- base de données vectorielle ;
- repositories GitHub et GitLab comme sources ;
- plugins IDE ;
- serveur MCP complet ;
- intégration avec AI Skills Registry ;
- intégration avec JARVIS, Alfred ou Brainiac ;
- routage automatique entre modèles ;
- prise en charge complète de plusieurs langages.

## Surface de commandes cible du MVP

Commandes visées :

```bash
nexus project add ./mon-projet
nexus project list
nexus index mon-projet
nexus search mon-projet "ingestion de documents"
nexus context mon-projet "Corrige le traitement d'upload PDF" --budget 20000 --explain
nexus inspect mon-projet
```

La CLI constitue un adaptateur au-dessus des services applicatifs. Aucune logique de sélection du contexte ne doit être implémentée directement dans les handlers de commandes.

## Critères de validation

Le MVP est considéré comme valide lorsque tous les points suivants sont démontrés de manière reproductible sur des corpus de test et sur au moins un repository Java représentatif :

1. Un projet local peut être enregistré à partir de son chemin puis rechargé dans un processus ultérieur.
2. L'indexation respecte `.gitignore`, `.nexusignore` et les exclusions intégrées concernant les secrets et contenus générés.
3. Les classes, interfaces, records, enums, méthodes et imports Java sont extraits structurellement sans dépendre uniquement d'expressions régulières.
4. Une nouvelle indexation sans modification des sources produit le même index exploitable et les mêmes résultats.
5. Une requête en langage naturel retourne des fichiers et symboles classés avec des scores déterministes.
6. Chaque candidat sélectionné peut exposer les facteurs ayant contribué à son score.
7. `ContextBuilder` ne dépasse jamais le budget configuré selon le `TokenEstimator` actif.
8. Les extraits de symboles pertinents sont privilégiés par rapport aux fichiers complets lorsqu'ils fournissent un contexte suffisant.
9. Les extraits dupliqués ou qui se chevauchent sont supprimés ou fusionnés.
10. `--explain` présente les éléments sélectionnés ainsi que les candidats significatifs exclus, avec leurs motifs.
11. Des tests basés sur des requêtes de référence mesurent les résultats pertinents attendus et les résultats non pertinents.
12. Le MVP complet fonctionne hors ligne une fois les dépendances Maven résolues.

## Métriques de qualité initiales

Mesurer au minimum :

- précision à K (`precision@K`) ;
- rappel à K (`recall@K`) ;
- ratio de réduction du contexte ;
- économie estimée de tokens ;
- durée d'indexation ;
- latence de recherche ;
- latence de construction du contexte.

Ces métriques constituent des mesures techniques et non des promesses. Les valeurs de référence doivent être conservées avec les corpus afin de pouvoir suivre leur évolution dans le temps.
