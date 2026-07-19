# Feuille de route incrémentale

Cette feuille de route distingue volontairement :

1. la validation du **moteur de contexte** ;
2. l'enrichissement progressif des **sources de contexte** ;
3. l'ajout des **adaptateurs et intégrations**.

Le principe directeur est de valider d'abord la capacité de NEXUS à produire un contexte pertinent, explicable et maîtrisé avant d'ajouter des intégrations nombreuses.

---

## Itération 0 — Socle architectural

État : **terminée et validée localement**.

Livrables :

- mission du projet et périmètre du MVP ;
- décisions d'architecture initiales ;
- socle Maven et Java ;
- contrats principaux du cœur ;
- premier analyseur AST Java avec JavaParser ;
- premier test de l'analyseur.

Critère de sortie : le repository compile et le contrat de l'analyseur Java est testable.

Validation locale :

- `mvn clean install` : succès ;
- compilation de 20 fichiers source en Java 21 : succès ;
- tests : 1 exécuté, 0 échec, 0 erreur, 0 ignoré ;
- génération du JAR `nexus-context-engine-0.1.0-SNAPSHOT.jar` : succès ;
- installation dans le dépôt Maven local : succès.

---

# Phase 1 — Valider le moteur NEXUS

## Itération 1 — Indexation locale et fondations de recherche

État : **terminée et validée localement**.

Objectif : enregistrer un repository Java local, l'indexer de manière incrémentale et disposer d'une base de recherche locale exploitable.

Livrables :

- registre local des projets ;
- scanner du système de fichiers ;
- prise en compte de `.gitignore` et `.nexusignore` ;
- exclusions des secrets et contenus générés ;
- calcul incrémental des empreintes de fichiers ;
- abstraction de persistance SQLite ;
- persistance des fichiers, symboles et métadonnées ;
- abstraction `SearchIndex` ;
- index Lucene local pour les chemins, symboles et contenus ;
- synchronisation SQLite → Lucene lors de l'indexation ;
- point d'entrée CLI minimal pour indexer et inspecter un projet.

Décisions associées :

```text
SQLite
→ source de vérité structurelle

Lucene
→ index de recherche local reconstructible

JavaParser
→ analyse structurelle Java embarquée

JGit
→ sémantique .gitignore / .nexusignore

SHA-256
→ détection incrémentale des changements
```

Critère de sortie : un repository Java local peut être enregistré, indexé, réindexé sans duplication et inspecté hors ligne.

Validation locale du 19 juillet 2026 :

- `mvn clean install` : succès ;
- compilation de 43 fichiers source avec `--release 21` : succès ;
- compilation de 4 fichiers de test : succès ;
- tests : 6 exécutés, 0 échec, 0 erreur, 0 ignoré ;
- tests couverts : analyse JavaParser, text blocks Java 21, indexation incrémentale SQLite/Lucene, scanner et règles d'ignore, registre de projets ;
- génération du JAR `nexus-context-engine-0.1.0-SNAPSHOT.jar` : succès ;
- installation dans le dépôt Maven local : succès.

Validation self-smoke sur le repository NEXUS :

- enregistrement du repository : succès ;
- première indexation : 47 fichiers scannés, 47 fichiers modifiés, 0 supprimé ;
- index produit : 47 fichiers, 161 symboles et 287 relations ;
- première indexation avec reconstruction complète : 741 ms sur la machine de validation ;
- seconde indexation incrémentale : 47 fichiers scannés, 0 fichier modifié, 0 supprimé ;
- seconde indexation : 282 ms sur la machine de validation ;
- inspection finale : état `READY`, 47 fichiers, 161 symboles et 287 relations ;
- résultat : `SELF-SMOKE SUCCESS`.

Le self-smoke a révélé puis permis de corriger un défaut réel : JavaParser utilisait son niveau de langage par défaut et refusait les text blocks présents dans le code NEXUS. L'analyseur est désormais configuré explicitement avec `ParserConfiguration.LanguageLevel.JAVA_21` et ce comportement est couvert par un test de non-régression.

Les avertissements Maven/Guice, SLF4J sans provider, accès natif SQLite et Vector API Lucene observés lors du build sont non bloquants et ne remettent pas en cause le critère de sortie de l'itération. Sous Windows PowerShell 5.1, certains accents des sorties capturées peuvent également être mal affichés selon l'encodage de la console ; ce problème d'affichage n'affecte pas les données indexées ni le résultat fonctionnel.

---

## Itération 2 — Recherche, graphe et classement explicable

Objectif : transformer une demande textuelle en une liste de fichiers et symboles pertinents avec un score explicable.

Livrables :

- recherche lexicale Lucene avec BM25 ;
- pondération par champ ;
- recherche exacte et approximative de symboles ;
- correspondance sur chemins et packages ;
- relations de base entre fichiers et symboles ;
- construction d'un graphe structurel minimal ;
- stratégie de ranking déterministe ;
- décomposition des scores ;
- explication des raisons de sélection ;
- corpus de requêtes de référence ;
- métriques `precision@K` et `recall@K`.

Travail de recherche à effectuer pendant l'itération :

- étudier les principes du RepoMap d'Aider ;
- évaluer l'utilisation d'un PageRank ou d'une propagation de pertinence dans le graphe ;
- mesurer le gain réel par rapport à un ranking lexical simple.

Le ranking ne doit pas dépendre du code d'Aider ni reproduire son comportement à l'identique. Seuls les principes pertinents doivent être adaptés au modèle NEXUS.

Critère de sortie : les requêtes classent de manière reproductible les fichiers et symboles pertinents au-dessus des éléments connus comme non pertinents, et chaque score est explicable.

---

## Itération 3 — Construction du contexte et budget

Objectif : produire le premier véritable `ContextBundle` NEXUS.

Livrables :

- implémentation de `ContextBuilder` ;
- implémentation locale par défaut de `TokenEstimator` ;
- sélection d'extraits de symboles ;
- sélection de fichiers complets lorsque nécessaire ;
- déduplication ;
- fusion des chevauchements ;
- budget de tokens configurable ;
- allocation éventuelle de sous-budgets par type de contexte ;
- explication des exclusions ;
- explication des troncatures ;
- calcul du ratio de réduction du contexte.

Critère de sortie : les bundles générés restent dans le budget configuré tout en conservant le contexte pertinent attendu sur les corpus de référence.

---

## Itération 4 — CLI utilisable pour le MVP

Objectif : rendre le moteur exploitable de bout en bout sans intégration externe.

Livrables :

- `nexus project add` ;
- `nexus project list` ;
- `nexus index` ;
- `nexus search` ;
- `nexus context` ;
- `nexus inspect` ;
- option `--budget` ;
- option `--explain` ;
- sorties JSON ;
- sorties lisibles par un humain ;
- tests de corpus de bout en bout ;
- métriques initiales de performance et de qualité.

Critère de sortie : à partir d'un repository Java local et d'une demande textuelle, NEXUS identifie et classe les fichiers et symboles pertinents, puis produit un `ContextBundle` respectant un budget configurable.

Cette itération constitue la validation du **MVP du moteur**.

---

# Phase 2 — Étendre les sources de contexte

## Itération 5 — Instructions et documentation

Objectif : permettre à NEXUS de sélectionner autre chose que du code.

Livrables :

- abstraction `ContextSourceProvider` ;
- modèle `ContextSourceDescriptor` ;
- indexation de documentation Markdown ;
- `InstructionSourceProvider` ;
- support de `AGENTS.md` ;
- support de `.github/copilot-instructions.md` ;
- support de `.github/instructions/*.instructions.md` ;
- support de `CLAUDE.md` ;
- support extensible de formats d'instructions supplémentaires ;
- résolution du scope des instructions ;
- ranking des instructions ;
- explication de leur sélection ou exclusion.

Critère de sortie : une demande peut produire un `ContextBundle` contenant simultanément du code, de la documentation et uniquement les instructions applicables.

---

## Itération 6 — Skills et divulgation progressive

Objectif : intégrer les skills comme source de contexte standardisée sans inventer un format propriétaire.

Livrables :

- abstraction `SkillSourceProvider` ;
- support du standard Agent Skills ;
- découverte des `SKILL.md` ;
- indexation légère du nom, de la description et des métadonnées ;
- chargement complet uniquement lorsqu'un skill est sélectionné ;
- prise en compte optionnelle des références et assets ;
- modèle permettant de référencer un skill sans l'exécuter ;
- préparation du connecteur futur AI Skills Registry.

Principe :

```text
Découverte
→ métadonnées seulement

Sélection
→ SKILL.md

Exécution
→ responsabilité de l'agent consommateur
```

Critère de sortie : NEXUS sait recommander et inclure les skills pertinents dans un `ContextBundle` sans les charger tous ni les exécuter lui-même.

---

## Itération 7 — Contexte Git

Objectif : enrichir la pertinence avec l'historique récent du projet sans transformer NEXUS en client Git complet.

Livrables :

- `GitContextSourceProvider` ;
- commits récents liés aux fichiers sélectionnés ;
- diff pertinent ;
- historique limité d'un fichier ou symbole ;
- détection de fichiers fréquemment modifiés ensemble ;
- bonus de récence configurable dans le ranking ;
- budget spécifique pour le contexte Git.

Critère de sortie : le contexte Git améliore les résultats sur des scénarios mesurés sans provoquer une explosion du volume de contexte.

---

# Phase 3 — Enrichir l'intelligence de code

## Itération 8 — SCIP et index de code externes

Objectif : réutiliser des index d'intelligence de code existants pour enrichir NEXUS.

Livrables :

- abstraction `CodeIndexImporter` ;
- abstraction `CodeIntelligenceProvider` ;
- prototype d'import SCIP ;
- mapping SCIP → `CodeSymbol` / `SymbolRelation` ;
- support initial de `scip-java` lorsqu'un index est disponible ;
- stratégie de fusion avec les données JavaParser ;
- gestion de la provenance des relations ;
- mesure de la qualité obtenue par rapport à JavaParser seul.

Critère de sortie : NEXUS peut enrichir un projet avec définitions et références externes sans rendre SCIP obligatoire.

---

## Itération 9 — Analyse Java profonde optionnelle

Objectif : couvrir les cas Java complexes nécessitant plus qu'un AST embarqué.

Candidat principal : Eclipse JDT Language Server.

Livrables possibles :

- provider JDT optionnel ;
- références ;
- implémentations ;
- hiérarchies de types ;
- hiérarchies d'appels ;
- activation uniquement lorsque nécessaire ;
- isolation du processus et des dépendances.

Critère de sortie : démontrer un gain mesurable sur des projets Java complexes avant de considérer cet adaptateur comme recommandé.

---

## Itération 10 — Multi-langage

Objectif : étendre progressivement NEXUS au-delà de Java.

Stratégies possibles :

- index SCIP existants ;
- Tree-sitter ;
- analyseurs spécifiques à certains langages ;
- combinaison des approches.

Langages candidats :

- Kotlin ;
- TypeScript / JavaScript ;
- Python ;
- SQL.

Le choix sera guidé par les besoins réels et la qualité des providers disponibles.

Critère de sortie : ajouter un langage sans modifier le fonctionnement fondamental du `ContextBuilder` ni du ranking.

---

# Phase 4 — Exposer NEXUS aux autres outils

## Itération 11 — Adaptateur API

Objectif : exposer les capacités NEXUS à d'autres applications.

Stack candidate : Quarkus LTS, version choisie au démarrage de l'itération.

Livrables :

- adaptateur REST ;
- DTO isolés des modèles du cœur ;
- endpoints projets ;
- endpoints indexation ;
- endpoints recherche ;
- endpoints contexte ;
- endpoints d'explication ;
- santé et observabilité ;
- aucune logique métier dans les ressources REST.

---

## Itération 12 — Adaptateur MCP

Objectif : rendre NEXUS directement utilisable par les assistants et agents compatibles MCP.

Décision : utiliser le SDK Java MCP existant plutôt que réimplémenter le protocole.

Outils candidats :

```text
search_code
find_symbol
find_usages
get_relevant_files
get_related_tests
get_architecture_context
get_module_context
get_project_instructions
get_recent_changes
build_context
explain_context
```

Critère de sortie : un client MCP peut interroger NEXUS et recevoir les mêmes résultats que la CLI ou l'API.

---

## Itération 13 — Adaptateurs Copilot et Claude

Objectif : faciliter l'utilisation de NEXUS dans des environnements ayant leurs propres mécanismes de contexte.

Livrables à étudier :

- adaptateur Copilot ;
- adaptateur Claude ;
- découverte de leurs conventions projet ;
- traduction entre leurs concepts et le modèle NEXUS ;
- mécanismes d'invocation appropriés à chaque environnement ;
- documentation d'intégration.

NEXUS ne remplace pas leurs systèmes natifs. Il fournit une couche commune d'intelligence de contexte.

---

# Phase 5 — Écosystème et passage à l'échelle

## Itération 14 — AI Skills Registry

Objectif : connecter la sélection de skills de NEXUS à un registre externe.

NEXUS doit rester utilisable sans registre.

Flux cible :

```text
Demande
   │
   ▼
NEXUS
   │
   ├── contexte code
   ├── documentation
   ├── instructions
   └── skills recherchés
           │
           ▼
    AI Skills Registry
```

---

## Itération 15 — JARVIS, Alfred et Brainiac

Objectif : utiliser NEXUS comme fournisseur de contexte commun.

Répartition cible :

```text
JARVIS
→ orchestration et routage

NEXUS
→ sélection et construction du contexte

AI Skills Registry
→ découverte des capacités

Alfred / Brainiac / agents
→ traitement spécialisé

LLM
→ raisonnement et génération
```

NEXUS ne doit introduire aucune dépendance vers ces projets.

---

## Itération 16 — Recherche à grande échelle

Objectif : permettre à NEXUS d'adresser des volumes dépassant le cas du repository local.

Pistes à évaluer uniquement si les métriques le justifient :

- Zoekt comme moteur de recherche de code externe ;
- OpenGrok ;
- index distants ;
- plusieurs repositories ;
- cache partagé ;
- recherche fédérée.

Lucene reste le moteur local par défaut tant qu'il répond aux besoins.

---

## Itération 17 — Recherche sémantique optionnelle

Objectif : mesurer si les embeddings améliorent réellement la qualité du contexte.

Livrables potentiels :

- `SemanticSearchStrategy` ;
- provider d'embeddings local ou externe ;
- activation explicite ;
- stockage vectoriel via Lucene lorsque pertinent ;
- comparaison avec le ranking lexical + symbolique + graphe ;
- mesure du coût, de la latence et du gain de précision.

Aucun fournisseur d'embeddings ne devient obligatoire.

Critère d'adoption : conserver la recherche sémantique uniquement si elle apporte un gain mesurable sur le corpus de référence.

---

# Critères globaux de progression

Une nouvelle brique ne doit être adoptée durablement que si elle satisfait au moins un des critères suivants :

- amélioration mesurable de la précision ou du rappel ;
- réduction du contexte ou du budget de tokens ;
- amélioration de la couverture fonctionnelle ;
- réduction significative de code maison ;
- amélioration de l'interopérabilité ;
- besoin réel d'une intégration cliente.

Les composants externes doivent rester derrière des abstractions NEXUS et ne doivent jamais devenir obligatoires sans justification.

La priorité générale reste :

> **qualité du contexte > nombre de fonctionnalités > nombre d'intégrations.**
