# Feuille de route incrémentale

## Itération 0 — Socle architectural

État : terminée et validée localement.

Livrables :

- mission du projet et périmètre du MVP ;
- décisions d'architecture ;
- socle Maven et Java ;
- contrats principaux du cœur ;
- premier analyseur AST Java ;
- premier test de l'analyseur.

Critère de sortie : le repository compile et le contrat de l'analyseur Java est testable.

Validation locale :

- `mvn clean install` : succès ;
- compilation de 20 fichiers source en Java 21 : succès ;
- tests : 1 exécuté, 0 échec, 0 erreur, 0 ignoré ;
- génération du JAR `nexus-context-engine-0.1.0-SNAPSHOT.jar` : succès ;
- installation dans le dépôt Maven local : succès.

## Itération 1 — Indexation des projets locaux

Livrables :

- registre local des projets ;
- scanner du système de fichiers ;
- prise en compte de `.gitignore` et `.nexusignore` ;
- exclusions des secrets et contenus générés ;
- calcul incrémental des empreintes de fichiers ;
- abstraction de persistance SQLite ;
- persistance des fichiers et symboles Java ;
- point d'entrée CLI pour l'indexation.

Critère de sortie : un repository Java local peut être enregistré, indexé et inspecté hors ligne.

## Itération 2 — Recherche et classement

Livrables :

- recherche lexicale ;
- recherche exacte et approximative de symboles ;
- relations de base entre fichiers et symboles ;
- modèle de score déterministe ;
- décomposition des scores et explications ;
- corpus de requêtes de référence.

Critère de sortie : les requêtes classent de manière reproductible les fichiers et symboles pertinents au-dessus des éléments connus comme non pertinents.

## Itération 3 — Construction du contexte et budget

Livrables :

- implémentation de `ContextBuilder` ;
- implémentation locale par défaut de `TokenEstimator` ;
- sélection d'extraits ;
- déduplication et fusion des chevauchements ;
- budget de tokens configurable ;
- explication des exclusions et troncatures.

Critère de sortie : les bundles générés restent dans le budget configuré tout en conservant le contexte pertinent attendu.

## Itération 4 — CLI utilisable pour le MVP

Livrables :

- `project add/list` ;
- `index` ;
- `search` ;
- `context` ;
- `inspect` ;
- sorties JSON et lisibles par un humain ;
- tests de corpus de bout en bout ;
- métriques initiales de performance et de qualité du contexte.

Critère de sortie : l'objectif complet du MVP peut être démontré depuis la ligne de commande.

## Itération 5 — Adaptateur API

Stack candidate : Quarkus LTS, version choisie au démarrage de l'itération.

Livrables :

- adaptateur d'application REST ;
- DTO de requêtes et réponses isolés des modèles du cœur ;
- endpoints de santé et d'observabilité ;
- aucune logique métier dans les ressources REST.

## Itération 6 — Enrichissement

Ajouts possibles, à valider indépendamment :

- contexte Git ;
- résolution des instructions applicables ;
- graphe de dépendances enrichi ;
- fournisseur optionnel de recherche sémantique ;
- langages supplémentaires via de nouvelles implémentations de `LanguageAnalyzer`.

## Itération 7 — Intégrations

Uniquement après validation de la qualité du moteur :

- adaptateur MCP ;
- intégrations IDE ;
- sources de projets GitHub et GitLab ;
- connecteur AI Skills Registry ;
- intégrations clientes pour JARVIS, Alfred et Brainiac.
