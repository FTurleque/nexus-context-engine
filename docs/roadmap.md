# Feuille de route incrémentale

Cette feuille de route active suit la progression actuelle de NEXUS et conserve les critères de sortie des prochaines itérations.

L'historique détaillé des validations des Itérations 0 à 10, avec toutes les métriques intermédiaires, est archivé sans modification dans [`roadmap-history-through-iteration-10.md`](roadmap-history-through-iteration-10.md).

Le principe directeur reste :

> **qualité du contexte > nombre de fonctionnalités > nombre d'intégrations.**

Une nouvelle brique doit rester optionnelle lorsqu'elle n'est pas indispensable au moteur et ne doit pas faire fuiter un framework, un protocole client ou un fournisseur externe dans le cœur.

---

# Phase 1 — Valider le moteur NEXUS

État global : **terminée et validée localement le 19 juillet 2026**.

## Itération 0 — Socle architectural

État : **terminée et validée localement**.

Résultat : socle Java 21, contrats du cœur, ADR et premier analyseur Java établis.

## Itération 1 — Indexation locale et fondations de recherche

État : **terminée et validée localement**.

Résultat : registre de projets, scan local, SQLite canonique, Lucene reconstructible et indexation incrémentale validés hors ligne.

## Itération 2 — Recherche, graphe et classement explicable

État : **terminée et validée localement**.

Résultat : recherche hybride, fusion déterministe, graphe minimal, ranking et explications de score validés.

## Itération 3 — Construction du contexte et budget

État : **terminée et validée localement**.

Résultat : `ContextBuilder`, estimation locale des tokens, sélection de fragments et invariant `estimatedTokens <= tokenBudget` validés.

## Itération 4 — CLI utilisable pour le MVP

État : **terminée et validée localement**.

Résultat : CLI autonome humaine/JSON, codes de sortie stables et flux complet projet → indexation → recherche → `ContextBundle` validés.

---

# Phase 2 — Étendre les sources de contexte

État global : **terminée et validée localement le 20 juillet 2026**.

## Itération 5 — Instructions et documentation

État : **terminée et validée localement**.

Résultat : documentation Markdown et instructions natives (`AGENTS.md`, Copilot, Claude, Gemini) intégrées avec scopes, priorités, références sécurisées et déduplication.

## Itération 6 — Skills et divulgation progressive

État : **terminée et validée localement**.

Résultat : Agent Skills découverts par métadonnées, sélectionnés avant chargement complet, intégrés sous budget et jamais exécutés par NEXUS.

## Itération 7 — Contexte Git

État : **terminée et validée localement**.

Résultat : signal de récence Git et contexte Git local borné, explicable et optionnel ajoutés sans transformer NEXUS en client Git complet.

Point de surveillance conservé : le coût de l'inspection Git doit continuer à être mesuré avant toute stratégie de cache ou de persistance supplémentaire.

---

# Phase 3 — Enrichir l'intelligence de code

État global : **terminée et validée localement le 20 juillet 2026**.

## Itération 8 — SCIP et index de code externes

État : **terminée et validée localement**.

Résultat : import SCIP opportuniste derrière `CodeIndexImporter`, avec provenance conservée et enrichissement mesuré sans rendre SCIP obligatoire.

Mesure de référence sur NEXUS : +243 symboles et +8 186 relations, sans dégradation observée du corpus de recherche de l'itération.

## Itération 9 — Analyse Java profonde optionnelle

État : **terminée et validée localement**.

Résultat : Eclipse JDT Language Server intégré comme provider Java profond explicitement activé par `--deep-java`.

Mesure de référence : +705 symboles et +1 093 relations, mais environ 60,1 s contre 1,8 s pour le chemin normal. JDT LS reste donc strictement à la demande.

## Itération 10 — Multi-langage

État : **terminée et validée localement**.

Résultat : support lexical natif de Kotlin, TypeScript, JavaScript, Python et SQL, sans modification fondamentale du `ContextBuilder` ni du ranking.

La structure reste enrichissable via SCIP ou des providers optionnels. La validation a également conduit à normaliser les identifiants `snake_case` et `camelCase` dans Lucene.

---

# Phase 4 — Exposer NEXUS aux autres outils

## Itération 11 — Adaptateur API

État : **terminée, validée localement et fusionnée le 20 juillet 2026**.

Résultat : adaptateur REST Quarkus isolé du cœur avec DTO dédiés, endpoints projets/indexation/recherche/contexte/explication, health et métriques.

Validation de référence :

- cœur : 45 tests verts ;
- baseline : `precision@3 = 0,4444`, `recall@3 = 1,0000` ;
- self-smoke : succès ;
- test REST de bout en bout : succès ;
- health et metrics : succès ;
- runner Quarkus produit.

Défauts révélés puis corrigés : gestion de l'initialisation SQLite dans CDI et `HTTP 415` causé par un `@Consumes(JSON)` trop large.

PR #4 fusionnée dans `main` au commit `d5565dc3da0be823929afe73ca7345fd2bc1e6ca`.

---

## Itération 12 — Adaptateur MCP

État : **terminée, validée localement et fusionnée le 20 juillet 2026**.

Objectif : rendre NEXUS directement utilisable par les assistants et agents compatibles MCP sans introduire le protocole dans le cœur.

Architecture retenue :

- SDK Java MCP officiel `2.0.0` ;
- adaptateur isolé dans `adapters/mcp-java` ;
- transport local STDIO ;
- façade applicative commune `NexusApplication` utilisée par REST et MCP ;
- handlers MCP limités à validation, appel NEXUS et mapping ;
- aucune logique de ranking ou de construction du contexte dans MCP ;
- `stdout` réservé au transport JSON-RPC.

Tools validés :

```text
list_projects
search_code
find_symbol
find_usages
build_context
explain_context
```

Livrables validés :

- serveur MCP STDIO autonome ;
- schémas d'entrée explicites ;
- réponses JSON inspectables dans le contenu MCP ;
- vrai client MCP Java pour les tests d'intégration ;
- parité `search_code` avec `NexusApplication.search` ;
- parité `build_context` avec `NexusApplication.context` ;
- validation `find_symbol` ;
- contrat exact des six tools ;
- régression REST après extraction de `NexusApplication` ;
- ADR-0040 ;
- documentation `docs/developer/mcp.md` ;
- script `scripts/validate-iteration-12.ps1` avec mode `-AdapterOnly` ;
- runner `adapters/mcp-java/target/nexus-mcp-java-0.1.0-SNAPSHOT-runner.jar`.

Validation réelle du 20 juillet 2026 :

- `mvn clean install` du cœur : succès ;
- 45 tests cœur, 0 échec, 0 erreur ;
- baseline qualité conservée : `precision@3 = 0,4444`, `recall@3 = 1,0000` ;
- `SELF-SMOKE SUCCESS` ;
- 216 fichiers indexés ;
- 1 152 symboles ;
- 9 562 relations ;
- indexation complète : 2 068 ms ;
- indexation incrémentale : 659 ms ;
- recherche explicable : 726 ms ;
- contexte strict : 3 items, 100/180 tokens, 882 ms ;
- contexte multi-source : 12 items, 1 187/1 200 tokens, 1 082 ms ;
- contexte avec skill : 1 194/1 200 tokens, 1 101 ms ;
- contexte Git : 1 598/1 600 tokens, 1 080 ms ;
- réduction du contexte candidat strict : 99,39 % ;
- régression REST après façade commune : succès ;
- client MCP STDIO réel : succès ;
- parité `search_code` : succès ;
- parité `build_context` : succès ;
- `find_symbol` : succès ;
- test MCP final : 1 test, 0 échec, 0 erreur ;
- contrat exact des six tools : succès ;
- build MCP final : 10,056 s ;
- packaging runner MCP : succès.

Défaut révélé et corrigé pendant la validation :

- le premier démarrage du client MCP a échoué avec `NoClassDefFoundError: JsonSerializeAs` à cause d'un graphe Jackson incohérent entre NEXUS et `mcp-json-jackson2` ;
- le POM MCP aligne désormais explicitement `jackson-core` et `jackson-databind` sur `2.22.1`, ainsi que `jackson-annotations` sur `2.21`, sans modifier les dépendances du cœur.

Critère de sortie : **validé**. Un client MCP réel initialise une session STDIO, découvre exactement les six tools NEXUS et obtient pour la recherche et la construction du contexte les mêmes résultats que la façade applicative commune utilisée par l'API.

PR #5 fusionnée dans `main` au commit `d6e6b190b4082686c1514b0a82f2fef033180858`.

---

## Itération 13 — Adaptateurs Copilot et Claude

État : **en cours**.

Objectif : faciliter l'utilisation de NEXUS dans GitHub Copilot et Claude sans créer deux implémentations propriétaires du moteur de contexte.

Orientation retenue au démarrage :

- réutiliser l'adaptateur MCP NEXUS validé à l'Itération 12 ;
- fournir des mécanismes d'installation et de configuration adaptés aux clients ;
- cibler GitHub Copilot dans les environnements MCP pris en charge, notamment les IDE JetBrains et Copilot CLI ;
- cibler Claude Code via ses scopes MCP `local`, `project` et `user` ;
- générer des configurations reproductibles autour du runner MCP NEXUS ;
- ne jamais modifier silencieusement une configuration utilisateur ;
- conserver les conventions natives déjà indexées par NEXUS (`.github/copilot-instructions.md`, `.github/instructions/**`, `CLAUDE.md`) ;
- documenter clairement la frontière entre instructions natives et tools MCP NEXUS.

Premier incrément prévu :

- profil d'intégration `copilot` ;
- profil d'intégration `claude` ;
- génération de commandes d'installation MCP locales ;
- génération de snippets de configuration partageables ;
- validation des chemins Windows ;
- tests déterministes de génération ;
- documentation d'installation ;
- aucun appel réseau ni authentification gérés par NEXUS.

Critère de sortie : depuis un runner MCP NEXUS local, un développeur peut obtenir une configuration ou une commande d'installation valide pour Copilot et Claude, sans dupliquer la logique du moteur ni exposer de secret.

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
