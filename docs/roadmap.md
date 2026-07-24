# Feuille de route incrémentale

Cette feuille de route active suit la progression actuelle de NEXUS et conserve les décisions et critères de sortie utiles aux prochaines évolutions.

L'historique détaillé des validations des Itérations 0 à 10 est archivé dans [`roadmap-history-through-iteration-10.md`](roadmap-history-through-iteration-10.md).

Le principe directeur reste :

> **qualité du contexte > nombre de fonctionnalités > nombre d'intégrations.**

Une nouvelle brique doit rester optionnelle lorsqu'elle n'est pas indispensable au moteur et ne doit pas faire fuiter un framework, un protocole client ou un fournisseur externe dans le cœur.

---

# Phase 1 — Valider le moteur NEXUS

État global : **terminée et validée localement le 19 juillet 2026**.

## Itération 0 — Socle architectural

État : **terminée et validée**.

Résultat : socle Java 21, contrats du cœur, ADR et premier analyseur Java.

## Itération 1 — Indexation locale et fondations de recherche

État : **terminée et validée**.

Résultat : registre de projets, scan local, SQLite canonique, Lucene reconstructible et indexation incrémentale.

## Itération 2 — Recherche, graphe et classement explicable

État : **terminée et validée**.

Résultat : recherche hybride, fusion déterministe, graphe minimal, ranking et explications de score.

## Itération 3 — Construction du contexte et budget

État : **terminée et validée**.

Résultat : `ContextBuilder`, estimation locale des tokens, sélection de fragments et invariant `estimatedTokens <= tokenBudget`.

## Itération 4 — CLI utilisable pour le MVP

État : **terminée et validée**.

Résultat : CLI autonome humaine/JSON, codes de sortie stables et flux complet projet → indexation → recherche → `ContextBundle`.

---

# Phase 2 — Étendre les sources de contexte

État global : **terminée et validée localement le 20 juillet 2026**.

## Itération 5 — Instructions et documentation

Résultat : Markdown et instructions natives (`AGENTS.md`, Copilot, Claude, Gemini) avec scopes, priorités, références sécurisées et déduplication.

## Itération 6 — Skills et divulgation progressive

Résultat : Agent Skills découverts par métadonnées, sélectionnés avant chargement complet, intégrés sous budget et jamais exécutés par NEXUS.

## Itération 7 — Contexte Git

Résultat : signal de récence Git et contexte Git local borné, explicable et optionnel.

Point de surveillance : continuer à mesurer le coût de l'inspection Git avant cache ou persistance supplémentaire.

---

# Phase 3 — Enrichir l'intelligence de code

État global : **terminée et validée localement le 20 juillet 2026**.

## Itération 8 — SCIP et index de code externes

Résultat : import SCIP opportuniste derrière `CodeIndexImporter`, provenance conservée et enrichissement sans rendre SCIP obligatoire.

## Itération 9 — Analyse Java profonde optionnelle

Résultat : Eclipse JDT Language Server comme provider Java profond activé explicitement par `--deep-java`.

Le coût mesuré reste très supérieur au chemin normal ; JDT LS reste strictement à la demande.

## Itération 10 — Multi-langage

Résultat : support lexical natif de Kotlin, TypeScript, JavaScript, Python et SQL ; enrichissement structurel toujours possible via SCIP/providers.

---

# Phase 4 — Exposer NEXUS aux autres outils

## Itération 11 — Adaptateur API

État : **terminée, validée et fusionnée le 20 juillet 2026**.

Résultat : adaptateur REST Quarkus isolé du cœur, DTO dédiés, endpoints projets/indexation/recherche/contexte/explication, health et métriques.

PR #4 fusionnée au commit `d5565dc3da0be823929afe73ca7345fd2bc1e6ca`.

## Itération 12 — Adaptateur MCP

État : **terminée, validée et fusionnée le 20 juillet 2026**.

Architecture : SDK MCP Java officiel 2.0.0, transport STDIO, façade `NexusApplication` commune, six tools et aucune logique de ranking dans l'adaptateur.

```text
list_projects
search_code
find_symbol
find_usages
build_context
explain_context
```

PR #5 fusionnée au commit `d6e6b190b4082686c1514b0a82f2fef033180858`.

## Itération 13 — Adaptateurs Copilot et Claude

État : **terminée, validée et fusionnée le 20 juillet 2026**.

Résultat : réutilisation du serveur MCP pour Copilot CLI, Copilot JetBrains, Claude project et Claude user, sans dupliquer le moteur.

PR #6 fusionnée au commit `05b311044b8bb0a64dfc598d7e2e00b31f8359a7`.

---

# Phase 5 — Écosystème et passage à l'échelle

## Itération 14 — AI Skills Registry

État : **terminée, validée et fusionnée le 20 juillet 2026**.

Résultat : `AiSkillsRegistryProvider` lit un snapshot local optionnel, conserve la divulgation progressive et la priorité des skills projet.

PR #7 fusionnée au commit `118de1333d8c94dd152ebadec2106f8b00e1b291`.

## Itération 15 — JARVIS, Alfred et Brainiac

État : **terminée, validée et intégrée le 20 juillet 2026**.

Répartition :

```text
JARVIS       -> orchestration, recherche documentaire et routage
NEXUS        -> sélection et construction du contexte technique
AI Skills    -> découverte des capacités
Watchtower   -> catalogue et résolution des profils
Alfred       -> traitement général/documentaire
Brainiac     -> raisonnement approfondi
```

L'intégration reste côté consommateur JARVIS ; NEXUS ne dépend ni de JARVIS ni de Watchtower.

Incréments JARVIS fusionnés :

```text
NEXUS provider        feb25195a6e3543307828204526e93f7d8451d30
Watchtower routing    0156175408cdd6c072d10d174c12e59702102d8f
```

## Itération 16 — Recherche à grande échelle

État : **terminée et validée localement le 21 juillet 2026**.

Résultat : recherche fédérée locale multi-repositories avec provenance, ranking déterministe, coordination multi-termes et diversification par chemin.

Baseline canonique finale :

```text
repositories     7
fichiers         2 104
symboles         10 878
relations        10 087
p50 recherche    133 ms
p95 recherche    304 ms
p50 contexte     48 ms
p95 contexte     206 ms
precision@3      0,4583
recall@3         0,8958
hit@3            1,0000
MRR@3            1,0000
```

Décision : Lucene reste le moteur local ; aucun Zoekt/OpenGrok/index distant/distribution n'est justifié par les mesures.

Documentation : ADR-0043 et documents `docs/developer/large-scale-*`.

## Itération 17 — Recherche sémantique optionnelle

État : **terminée et validée localement le 21 juillet 2026**.

Architecture : `EmbeddingProvider`, `SemanticSearchIndex`, `LuceneSemanticSearchIndex`, `SemanticIndexingService`, activation explicite et `SemanticHybridContextRanker` par Reciprocal Rank Fusion.

Décision mesurée : poids RRF sémantique `8,0`, mais activation toujours opt-in.

Benchmark A/B final :

```text
baseline precision@3       0,0000
baseline recall@3          0,0000
semantic precision@3       0,1667
semantic recall@3          0,4167
semantic hit@3             0,5000
semantic MRR@3             0,3056
coût indexation            ~33,11x
coût recherche             ~1,43x
```

Validation finale : `mvn clean install`, **73 tests**, 0 échec, 0 erreur, 5 harness opt-in ignorés, `BUILD SUCCESS`.

Décision : conserver la recherche sémantique comme capacité locale opt-in ; ne pas l'activer dans le chemin standard.

Documentation : ADR-0014, `docs/developer/semantic-search.md`, `docs/developer/iteration-17-semantic-results.md`.

---

# Intégration externe — MINOS M13

État : **INTÉGRALEMENT IMPLÉMENTÉ — VALIDATION INTER-DÉPÔT EN ATTENTE**.

Suivi NEXUS : issue #11 / PR Draft #12.

Suivi fournisseur : `FTurleque/minos-code-intelligence` issue #37 / PR Draft #38.

## Objectif

Permettre à NEXUS de consommer la Code Intelligence normalisée de MINOS sans dépendance Maven vers MINOS, sans relever le niveau Java 21 du cœur et sans déplacer le ranking ou la construction du contexte.

## Architecture

```text
MINOS Java 24
  NexusExportContract v1
  nexus-export --root <project>
        |
        | JSON local versionné
        v
NEXUS Java 21
  MinosCodeIndexImporter
        |
        v
  SQLite -> recherche -> ranking -> ContextBuilder
```

Décision : ADR-0044.

## Propriétés

- intégration désactivée par défaut ;
- `NEXUS_MINOS_JAR` active le provider ;
- `NEXUS_MINOS_JAVA` est obligatoire lorsque MINOS est actif ;
- `NEXUS_MINOS_HOME` et `NEXUS_MINOS_TIMEOUT_SECONDS` sont optionnels ;
- aucun type `com.minos` dans NEXUS ;
- aucun réseau ;
- validation contractVersion/producteur/root ;
- mapping conservateur des symboles/relations ;
- provenance `sourceProvider=minos` ;
- import MINOS avant SCIP direct ;
- aucune modification de `SearchService`, des poids de ranking ou de `DefaultContextBuilder` ;
- NEXUS continue à fonctionner sans MINOS.

## Qualification

Tests automatiques :

```text
MinosCodeIndexImporterTest
  -> disabled by default
  -> Java 24 explicite
  -> contrat/root
  -> mapping conservateur
  -> vrai processus via JAR synthétique

MinosRealIntegrationTest
  -> harness opt-in avec vrai JAR MINOS
```

Replay réel attendu :

```text
M13 MINOS->NEXUS: symbols=<n>, relations=<n>, nexus-symbols=<n>, search=<n>
```

Le replay doit vérifier que `GreetingPort` entre dans SQLite NEXUS avec `sourceProvider=minos` puis est retourné par `SearchService`.

Porte : validation complète NEXUS Java 21 + replay avec le shaded JAR du **head exact MINOS qualifié**.

Documentation : `docs/developer/minos-code-intelligence.md`, ADR-0044.

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
