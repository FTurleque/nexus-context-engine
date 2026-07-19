# Feuille de route incrémentale

Cette feuille de route distingue volontairement :

1. la validation du **moteur de contexte** ;
2. l'enrichissement progressif des **sources de contexte** ;
3. l'ajout des **adaptateurs et intégrations**.

Le principe directeur reste de valider la qualité, l'explicabilité et la maîtrise du contexte avant d'étendre le nombre d'intégrations.

---

## Itération 0 — Socle architectural

État : **terminée et validée localement**.

Objectif : établir le contrat et les frontières du projet.

Livrables principaux :

- mission du projet et périmètre du MVP ;
- décisions d'architecture initiales ;
- socle Maven et Java 21 ;
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

État global : **terminée et validée localement le 19 juillet 2026**.

La Phase 1 valide le flux complet :

```text
Repository local
    ↓
Indexation structurelle
    ↓
Recherche hybride
    ↓
Ranking explicable
    ↓
Construction du contexte
    ↓
Budget de tokens
    ↓
CLI humaine / JSON
    ↓
ContextBundle
```

## Itération 1 — Indexation locale et fondations de recherche

État : **terminée et validée localement**.

Objectif : enregistrer un repository Java local, l'indexer de manière incrémentale et disposer d'une base de recherche locale exploitable.

Livrables :

- registre local des projets ;
- scanner du système de fichiers ;
- prise en compte de `.gitignore` et `.nexusignore` ;
- exclusions des secrets et contenus générés ;
- calcul incrémental des empreintes SHA-256 ;
- abstraction de persistance SQLite ;
- persistance des fichiers, symboles et métadonnées ;
- abstraction `SearchIndex` ;
- index Lucene local ;
- synchronisation SQLite → Lucene ;
- point d'entrée CLI minimal.

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
- compilation de 43 fichiers source avec `--release 21` ;
- compilation de 4 fichiers de test ;
- tests : 6 exécutés, 0 échec, 0 erreur, 0 ignoré ;
- génération et installation locale du JAR : succès.

Validation self-smoke :

- 47 fichiers indexés ;
- 161 symboles ;
- 287 relations ;
- première indexation : 741 ms ;
- seconde indexation : 282 ms ;
- 0 fichier modifié, 0 supprimé lors de la seconde passe ;
- état final `READY` ;
- résultat : `SELF-SMOKE SUCCESS`.

Le self-smoke a révélé puis permis de corriger un défaut réel : JavaParser utilisait son niveau de langage par défaut et refusait les text blocks. L'analyseur est désormais configuré explicitement en Java 21 et ce comportement est couvert par un test de non-régression.

---

## Itération 2 — Recherche, graphe et classement explicable

État : **terminée et validée localement**.

Objectif : transformer une demande textuelle en une liste de fichiers et symboles pertinents avec un score explicable.

Livrables :

- recherche lexicale Lucene avec BM25 ;
- pondération par champ ;
- recherche exacte et approximative de symboles ;
- correspondance sur chemins et packages ;
- fusion déterministe des candidats ;
- graphe structurel minimal ;
- propagation sur un et deux sauts ;
- ranking déterministe ;
- décomposition des scores ;
- explication des raisons de sélection ;
- corpus de requêtes de référence ;
- métriques `precision@K` et `recall@K`.

Décisions associées :

- ADR-0024 — combiner Lucene et SQLite pour la recherche de candidats ;
- ADR-0025 — normaliser les signaux et calculer un score composé explicable ;
- ADR-0026 — construire un graphe minimal à partir des imports résolus.

Approche retenue :

- Lucene fournit les candidats fichiers et le signal lexical BM25 ;
- SQLite fournit les symboles et les relations ;
- les candidats sont fusionnés de manière déterministe ;
- le graphe d'imports internes ajoute un signal structurel ;
- le score final reste une somme pondérée de composantes bornées et inspectables.

Critère de sortie : les requêtes classent de manière reproductible les éléments pertinents et chaque score est explicable.

Validation locale du 19 juillet 2026 :

- compilation de 57 fichiers source ;
- compilation de 7 fichiers de test ;
- 9 tests exécutés, 0 échec, 0 erreur, 0 ignoré ;
- génération et installation du JAR : succès.

Validation self-smoke :

- 64 fichiers ;
- 238 symboles ;
- 460 relations ;
- première indexation : 943 ms ;
- seconde indexation : 278 ms avec 0 changement ;
- `ProjectIndexingService.java` classé premier pour la requête `ProjectIndexingService` ;
- score `0,5585` ;
- composantes principales : BM25 `+0,400`, chemin `+0,100`, graphe `+0,059` ;
- résultat : `SELF-SMOKE SUCCESS`.

---

## Itération 3 — Construction du contexte et budget

État : **terminée et validée localement**.

Objectif : produire le premier véritable `ContextBundle` NEXUS.

Livrables :

- implémentation de `ContextBuilder` ;
- implémentation locale par défaut de `TokenEstimator` ;
- sélection d'extraits de symboles ;
- fenêtres lexicales pour les candidats fichiers ;
- déduplication ;
- fusion des chevauchements ;
- budget de tokens configurable ;
- troncature explicite ;
- explication des exclusions ;
- calcul du ratio de réduction du contexte.

Décisions associées :

- ADR-0027 — estimateur de tokens local, déterministe et remplaçable ;
- ADR-0028 — fragments basés prioritairement sur les symboles ;
- ADR-0029 — sélection gloutonne, déterministe et explicable sous budget.

Pipeline retenu :

```text
SearchService
    ↓
ContextFragmentFactory
    ↓
FragmentMerger
    ↓
BudgetedContextSelector
    ↓
ContextBundle
```

Critère de sortie : le bundle ne dépasse jamais le budget configuré et conserve le contexte attendu sur les scénarios de référence.

Validation locale du 19 juillet 2026 :

- compilation de 65 fichiers source ;
- compilation de 10 fichiers de test ;
- 13 tests exécutés, 0 échec, 0 erreur, 0 ignoré ;
- génération et installation du JAR : succès.

Validation self-smoke :

- 75 fichiers ;
- 288 symboles ;
- 564 relations ;
- première indexation : 931 ms ;
- seconde indexation : 275 ms avec 0 changement ;
- `ContextBundle` construit avec un budget de 180 tokens ;
- 3 items sélectionnés ;
- 178/180 tokens estimés ;
- 5 076 tokens candidats avant sélection ;
- réduction d'environ 96,49 % ;
- troncatures et exclusions explicitement expliquées ;
- résultat : `SELF-SMOKE SUCCESS`.

L'invariant principal est validé :

```text
ContextBundle.estimatedTokens <= ContextBundle.tokenBudget
```

---

## Itération 4 — CLI utilisable pour le MVP

État : **terminée et validée localement**.

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
- option globale `--json` ;
- sorties lisibles par un humain ;
- séparation `stdout` / `stderr` ;
- codes de sortie stables `0`, `1`, `2` ;
- `--help` et `--version` ;
- JAR CLI autonome ;
- launchers Windows PowerShell et CMD ;
- tests de bout en bout ;
- métriques initiales de performance et de qualité.

Décisions associées :

- ADR-0030 — stabiliser le contrat CLI avec sorties humaines, JSON et codes de sortie ;
- ADR-0031 — packager la CLI dans un JAR autonome.

Critère de sortie : à partir d'un repository Java local et d'une demande textuelle, NEXUS identifie et classe les fichiers et symboles pertinents, puis produit un `ContextBundle` respectant un budget configurable via un artefact CLI autonome.

Validation locale du 19 juillet 2026 :

- `mvn clean install` : succès ;
- compilation de 66 fichiers source avec `--release 21` ;
- compilation de 11 fichiers de test ;
- 16 tests exécutés, 0 échec, 0 erreur, 0 ignoré ;
- baseline qualité sur 3 requêtes : `mean precision@3 = 0,4444` ;
- baseline qualité : `mean recall@3 = 1,0000` ;
- JAR bibliothèque généré ;
- JAR autonome `nexus-context-engine-0.1.0-SNAPSHOT-cli.jar` généré ;
- deux artefacts installés dans le dépôt Maven local.

Validation self-smoke du MVP :

- exécution directe du JAR autonome : succès ;
- `--version --json` : succès ;
- `project add --json` : succès ;
- `project list --json` : succès ;
- documents JSON réellement parsés via PowerShell `ConvertFrom-Json` ;
- première indexation : 77 fichiers scannés, 77 modifiés, 0 supprimé ;
- index produit : 77 fichiers, 322 symboles, 599 relations ;
- indexation complète : 896 ms ;
- seconde indexation incrémentale : 232 ms, 0 modifié, 0 supprimé ;
- état final : `READY` ;
- recherche explicable : 254 ms ;
- `ProjectIndexingService.java` classé premier ;
- construction du contexte : 285 ms ;
- bundle : 3 items, 178/180 tokens ;
- réduction du contexte candidat : environ 96,45 % ;
- sortie humaine sans `--json` : succès ;
- résultat final : `SELF-SMOKE SUCCESS`.

Conclusion de la Phase 1 :

> **Le MVP du moteur NEXUS est validé de bout en bout.**

Le moteur sait désormais, hors ligne et sans dépendance obligatoire à un LLM :

```text
Enregistrer un projet
→ l'indexer
→ le réindexer de manière incrémentale
→ rechercher les fichiers et symboles pertinents
→ expliquer le ranking
→ construire un contexte
→ respecter un budget de tokens
→ exposer le résultat à un humain ou en JSON
```

Sous Windows PowerShell 5.1, certains warnings JVM/SLF4J écrits sur `stderr` peuvent encore apparaître comme un `NativeCommandError` visuel. Ce comportement est non bloquant : le self-smoke contrôle le vrai code de sortie du processus, conserve le JSON sur `stdout` et termine avec `SELF-SMOKE SUCCESS`.

---

# Phase 2 — Étendre les sources de contexte

## Itération 5 — Instructions et documentation

État : **terminée et validée localement le 20 juillet 2026**.

Objectif : permettre à NEXUS de sélectionner autre chose que du code tout en réutilisant les conventions déjà présentes dans les projets.

Livrables validés :

- abstraction `ContextSourceProvider` ;
- modèle `ContextSourceDescriptor` ;
- indexation de documentation Markdown ;
- `MarkdownLanguageAnalyzer` ;
- support de `AGENTS.md` et alias `AGENT.md` ;
- support de `.github/copilot-instructions.md` ;
- support de `.github/instructions/**/*.instructions.md` avec `applyTo` ;
- support de `CLAUDE.md` et `.claude/CLAUDE.md` ;
- support de `GEMINI.md` ;
- résolution du scope repository, répertoire et glob ;
- ranking des instructions par priorité de source et spécificité ;
- explication de leur sélection ou exclusion ;
- résolution sécurisée des références `@fichier` ;
- confinement des références au repository ;
- profondeur maximale de 5 et détection de cycles ;
- respect des `.gitignore` / `.nexusignore`, y compris imbriqués ;
- déduplication SHA-256 inter-provider ;
- déduplication entre documents référencés et documents remontés par Lucene ;
- sous-budget d'instructions plafonné à 25 % du budget global et à 600 tokens ;
- détection sans injection brute des settings, MCP, hooks, profils d'agents et skills ;
- catégories `DOCUMENTATION`, `INSTRUCTION`, `AGENT_PROFILE` et `SKILL` ;
- dogfooding via le `AGENTS.md` racine de NEXUS ;
- tests brownfield couvrant `.github`, `.claude`, scopes imbriqués et documentation.

Décisions associées :

- ADR-0011 — normaliser les sources de contexte derrière des providers ;
- ADR-0012 — réutiliser les standards existants ;
- ADR-0032 — préserver et normaliser le contexte natif des projets ;
- ADR-0033 — séparer les instructions contextuelles de la configuration opérationnelle.

Critère de sortie : une demande peut produire un `ContextBundle` contenant simultanément du code, de la documentation et uniquement les instructions applicables.

Validation locale du 20 juillet 2026 :

- `mvn clean install` : succès ;
- compilation de 83 fichiers source avec `--release 21` ;
- compilation de 13 fichiers de test ;
- 19 tests exécutés, 0 échec, 0 erreur, 0 ignoré ;
- baseline qualité conservée : `mean precision@3 = 0,4444`, `mean recall@3 = 1,0000` ;
- JAR bibliothèque et JAR CLI autonome générés et installés.

Validation self-smoke :

- JAR autonome : succès ;
- Java et Markdown détectés comme langues du projet ;
- première indexation : 145 fichiers scannés, 145 modifiés, 0 supprimé ;
- index produit : 145 fichiers, 406 symboles, 781 relations ;
- indexation complète : 1 115 ms ;
- seconde indexation incrémentale : 236 ms, 0 modifié, 0 supprimé ;
- recherche `ProjectIndexingService` : 277 ms, fichier principal classé premier ;
- contexte strict : 5 items, 172/180 tokens, 379 ms ;
- `AGENTS.md` natif sélectionné ;
- `docs/architecture.md` chargé comme référence explicite ;
- contexte multi-source : 9 items, 1 185/1 200 tokens, 414 ms ;
- présence simultanée de `INSTRUCTION`, `DOCUMENTATION`, code et tests ;
- 2 fragments documentaires supprimés par déduplication inter-source ;
- réduction du contexte candidat strict : environ 99,12 % ;
- résultat final : `SELF-SMOKE SUCCESS`.

Le critère de sortie est donc validé : NEXUS sait maintenant construire un contexte multi-source qui respecte les conventions natives du repository avant d'ajouter le contexte issu de sa propre recherche.

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

Critère de sortie : démontrer un gain mesurable sur des projets Java complexes avant de recommander cet adaptateur.

---

## Itération 10 — Multi-langage

Objectif : étendre progressivement NEXUS au-delà de Java.

Stratégies possibles :

- index SCIP existants ;
- Tree-sitter ;
- analyseurs spécifiques ;
- combinaison des approches.

Langages candidats :

- Kotlin ;
- TypeScript / JavaScript ;
- Python ;
- SQL.

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
- mécanismes d'invocation adaptés à chaque environnement ;
- documentation d'intégration.

NEXUS ne remplace pas leurs systèmes natifs. Il fournit une couche commune d'intelligence de contexte.

---

# Phase 5 — Écosystème et passage à l'échelle

## Itération 14 — AI Skills Registry

Objectif : connecter la sélection de skills de NEXUS à un registre externe tout en gardant NEXUS utilisable sans registre.

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