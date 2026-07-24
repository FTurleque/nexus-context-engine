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

État : **terminée, validée localement et fusionnée le 20 juillet 2026**.

Objectif : faciliter l'utilisation de NEXUS dans GitHub Copilot et Claude sans créer deux implémentations propriétaires du moteur de contexte.

Architecture retenue :

- réutiliser le serveur MCP NEXUS validé à l'Itération 12 ;
- isoler la génération de configuration dans `adapters/assistant-clients` ;
- ne jamais dupliquer `SearchService`, le ranking ou le `ContextBuilder` ;
- ne modifier automatiquement aucune préférence utilisateur ;
- ne gérer aucun secret ni mécanisme d'authentification ;
- conserver les instructions natives des clients séparées des tools MCP NEXUS ;
- formaliser cette frontière dans ADR-0041.

Profils validés :

- `copilot-cli` — commande d'installation ou JSON `mcpServers` ;
- `copilot-jetbrains` — JSON `servers` pour les IDE JetBrains ;
- `claude-project` — commande avec scope `project` ou JSON projet ;
- `claude-user` — commande avec scope `user`.

Livrables validés :

- module autonome `adapters/assistant-clients` ;
- génération déterministe de commandes et fragments JSON ;
- normalisation des chemins locaux, y compris les chemins Windows avec espaces ;
- 4 tests de génération ;
- documentation `adapters/assistant-clients/README.md` ;
- script `scripts/validate-iteration-13.ps1` ;
- ADR-0041 ajouté et indexé ;
- runner `adapters/assistant-clients/target/nexus-assistant-clients-0.1.0-SNAPSHOT-runner.jar`.

Validation réelle du 20 juillet 2026 :

- build cœur : succès en 14,325 s ;
- 45 tests cœur, 0 échec, 0 erreur ;
- baseline qualité conservée : `precision@3 = 0,4444`, `recall@3 = 1,0000` ;
- `SELF-SMOKE SUCCESS` ;
- 219 fichiers indexés ;
- 1 170 symboles ;
- 9 578 relations ;
- indexation complète : 2 132 ms ;
- indexation incrémentale : 681 ms ;
- recherche explicable : 780 ms ;
- contexte strict : 4 items, 170/180 tokens, 930 ms ;
- contexte multi-source : 12 items, 1 181/1 200 tokens, 1 068 ms ;
- contexte avec skill : 1 180/1 200 tokens, 1 093 ms ;
- contexte Git : 1 596/1 600 tokens, 1 085 ms ;
- Git : 50 commits inspectés, 13 liés, 3 fragments sélectionnés ;
- réduction du contexte candidat strict : 99,01 % ;
- régression MCP : succès, 1 test, 0 échec, 0 erreur ;
- test MCP : 2,767 s ;
- build MCP : 9,024 s ;
- intégrations assistants : succès, 4 tests, 0 échec, 0 erreur ;
- tests intégrations : 0,274 s ;
- build intégrations : 3,102 s ;
- profils Copilot CLI, Copilot JetBrains, Claude project et Claude user : générés avec succès ;
- runner MCP : produit ;
- runner intégrations : produit.

Les avertissements SLF4J, native access, Vector API et Maven Shade observés pendant la validation restent non bloquants pour cette itération.

Critère de sortie : **validé**. Depuis un runner MCP NEXUS local, un développeur peut obtenir une configuration ou une commande d'installation déterministe pour les quatre profils clients couverts, sans dupliquer la logique du moteur, modifier silencieusement ses préférences ou exposer de secret.

PR #6 fusionnée dans `main` au commit `05b311044b8bb0a64dfc598d7e2e00b31f8359a7`.

---

# Phase 5 — Écosystème et passage à l'échelle

## Itération 14 — AI Skills Registry

État : **terminée, validée localement et fusionnée le 20 juillet 2026**.

Objectif : connecter la sélection de skills de NEXUS à AI Skills Registry tout en gardant NEXUS utilisable sans registre.

Architecture retenue :

- `AiSkillsRegistryProvider` s'appuie sur le contrat `SkillSourceProvider` existant ;
- NEXUS lit un snapshot local sous `.nexus/registry/skills/**/SKILL.md` ;
- aucune requête réseau ni opération Git n'est effectuée pendant la construction d'un contexte ;
- l'absence de snapshot ne bloque jamais NEXUS ;
- la divulgation progressive est conservée : frontmatter avant sélection, corps complet après sélection ;
- les skills locaux du projet gardent une priorité `80`, supérieure à la priorité `60` des skills du registre ;
- `.nexus/registry/` reste un cache local non versionné.

Livrables validés :

- `AiSkillsRegistryProvider` ;
- agrégation dans le pipeline de découverte existant ;
- priorité déterministe des skills locaux sur les doublons du registre ;
- tests de découverte, sélection et chargement progressif ;
- ADR-0042 ajouté et indexé ;
- documentation `docs/developer/ai-skills-registry.md` ;
- script `scripts/validate-iteration-14.ps1`.

Validation réelle du 20 juillet 2026 :

- `mvn clean install` : succès en 14,179 s ;
- 47 tests cœur, 0 échec, 0 erreur ;
- baseline qualité conservée : `precision@3 = 0,4444`, `recall@3 = 1,0000` ;
- `SELF-SMOKE SUCCESS` ;
- 222 fichiers indexés ;
- 1 185 symboles ;
- 9 600 relations ;
- indexation complète : 2 127 ms ;
- indexation incrémentale : 594 ms ;
- recherche explicable : 724 ms ;
- contexte strict : 3 items, 100/180 tokens, 883 ms ;
- contexte multi-source : 12 items, 1 200/1 200 tokens, 1 077 ms ;
- contexte avec skill : 1 192/1 200 tokens, 1 143 ms ;
- contexte Git : 1 592/1 600 tokens, 1 114 ms ;
- Git : 50 commits inspectés, 13 liés, 3 fragments sélectionnés ;
- réduction du contexte candidat strict : 99,41 % ;
- `AiSkillsRegistryProviderTest` : 2 tests, 0 échec, 0 erreur ;
- tests dédiés : succès en 1,860 s ;
- priorité locale sur registre : validée ;
- divulgation progressive du registre : validée.

Incident révélé puis corrigé pendant la validation :

- le premier `testCompile` a échoué car le test utilisait `duplicates()` alors que `SkillDiscoveryResult` expose `deduplicatedSkills()` ;
- le correctif a uniquement modifié le test, sans toucher au code de production ;
- la validation complète a ensuite été rejouée avec succès.

Les avertissements SLF4J, native access, Vector API et Maven Shade observés restent non bloquants pour cette itération.

Critère de sortie : **validé**. NEXUS peut découvrir et sélectionner des skills provenant d'un snapshot local AI Skills Registry sans rendre le registre obligatoire, sans accès réseau pendant une requête et sans perdre la priorité des règles spécifiques au projet.

PR #7 fusionnée dans `main` au commit `118de1333d8c94dd152ebadec2106f8b00e1b291`.

---

## Itération 15 — JARVIS, Alfred et Brainiac

État : **terminée, validée localement et intégrée le 20 juillet 2026**.

Objectif : utiliser NEXUS comme fournisseur de contexte commun tout en conservant JARVIS comme orchestrateur et en gardant NEXUS indépendant de JARVIS, Watchtower et des modèles spécialisés.

Répartition validée :

```text
JARVIS
→ orchestration, recherche documentaire et routage

NEXUS
→ sélection et construction du contexte technique

AI Skills Registry
→ découverte des capacités

JARVIS Watchtower
→ catalogue et résolution des profils de modèles

Alfred
→ traitement général et documentaire

Brainiac
→ raisonnement approfondi et résolution complexe

Ollama / runtime LLM
→ exécution de la génération
```

Architecture retenue :

- l'intégration est réalisée du côté consommateur JARVIS ; aucun couplage vers JARVIS ou Watchtower n'est ajouté au cœur NEXUS ;
- JARVIS consomme le contexte NEXUS via l'API REST locale `/api/v1/projects/{projectId}/context` ;
- le contrat `ExternalContextProvider` isole l'enrichissement technique dans JARVIS ;
- `NexusContextProvider` est désactivé par défaut, utilise un timeout court et fonctionne en fail-open ;
- le contexte NEXUS enrichit le prompt mais ne devient jamais une source documentaire citée par JARVIS ;
- la recherche documentaire JARVIS reste la source de vérité des citations ;
- l'absence de résultats documentaires conserve le comportement existant et NEXUS ne se substitue pas aux preuves documentaires ;
- aucune dépendance Maven JARVIS → NEXUS ni NEXUS → JARVIS n'est introduite ;
- `LanguageModelClient` découple désormais l'orchestration de génération du runtime Ollama ;
- `ModelCatalog`, `ModelProfile`, `ModelRouter` et `ModelRoute` portent le contrat de routage ;
- `WatchtowerModelCatalog` charge optionnellement un `catalog.yaml` local, sans requête réseau pendant la génération ;
- les questions générales ou documentaires sont routées vers le profil logique `alfred` ;
- les demandes contenant des marqueurs explicites de raisonnement approfondi sont routées vers `brainiac` ;
- les fallbacks déclarés par Watchtower sont respectés lorsqu'un runtime exécutable existe ;
- tant que Watchtower conserve `runtime.model_name: null` pour Alfred et Brainiac, le fallback explicite `legacy-ollama` maintient le comportement historique ;
- la décision de routage journalise le profil demandé, le profil résolu, la version, le runtime, le fallback et la raison, sans journaliser les prompts privés.

Premier incrément — NEXUS → JARVIS :

- PR JARVIS #89 `M8 — Intégrer NEXUS comme fournisseur de contexte optionnel` ;
- 524 tests `jarvis-core`, 0 échec, 0 erreur, 16 ignorés ;
- 16 tests ciblés NEXUS/Answer, 0 échec, 0 erreur ;
- Spotless validé sur `jarvis`, `jarvis-core`, `jarvis-worker` et `jarvis-web` ;
- PR fusionnée dans `FTurleque/jarvis:master` au commit `feb25195a6e3543307828204526e93f7d8451d30`.

Deuxième incrément — Watchtower / Alfred / Brainiac :

- PR JARVIS #90 `M8 — Router la génération via Watchtower, Alfred et Brainiac` ;
- 530 tests `jarvis-core`, 0 échec, 0 erreur, 16 ignorés ;
- 21 tests ciblés Watchtower/Answer/Ollama, 0 échec, 0 erreur ;
- Spotless validé sur les quatre modules JARVIS ;
- routage déterministe et fallback `legacy-ollama` validés ;
- PR fusionnée dans `FTurleque/jarvis:master` au commit `0156175408cdd6c072d10d174c12e59702102d8f`.

Critère de sortie : **validé**. JARVIS peut enrichir ses réponses avec un contexte technique sélectionné par NEXUS, puis router la génération vers des profils logiques Watchtower sans rendre NEXUS dépendant de l'orchestrateur ou du runtime de modèles. Le découpage cible JARVIS → orchestration, NEXUS → contexte, Watchtower → catalogue de profils et Alfred/Brainiac → spécialisation est désormais matérialisé et testé.

---

## Itération 16 — Recherche à grande échelle

État : **terminée et validée localement le 21 juillet 2026**.

Objectif : permettre à NEXUS d'adresser plusieurs repositories réels avec une recherche fédérée locale, déterministe et explicable, puis mesurer objectivement si un moteur externe devient nécessaire.

Architecture retenue :

- `FederatedSearchService` orchestre les recherches projet par projet ;
- `FederatedSearchHit` conserve explicitement la provenance via `ProjectDescriptor` ;
- SQLite reste canonique par `projectId` et Lucene reste un index dérivé reconstructible par projet ;
- la fédération conserve le ranking existant et fusionne les résultats de manière déterministe ;
- les requêtes lexicales multi-termes coordonnent au moins deux termes analysés uniques afin d'éviter les faux positifs à un seul terme ;
- les résultats fédérés sont diversifiés par couple `projectId + chemin normalisé` après ranking afin qu'un même fichier ne monopolise pas le top-K via plusieurs candidats `FILE` / `SYMBOL` ;
- deux repositories différents ne sont jamais dédupliqués ;
- aucun backend réseau, index distant ou moteur externe n'est requis pendant la recherche.

Validation fonctionnelle :

- `mvn install` : 55 tests, 0 échec, 0 erreur, 2 harness opt-in ignorés ;
- validation ciblée : 7 tests, 0 échec, 0 erreur ;
- recherche multi-projet : validée ;
- provenance `projectId` : validée ;
- coordination lexicale multi-termes : validée ;
- corpus golden historique et fédéré : validés.

Palier incrémental contrôlé sur `collection-manager` :

- reconstruction complète : 11 128 ms ;
- incrémental petit delta : 323 ms ;
- rollback incrémental : 303 ms ;
- accélération reconstruction / petit delta : `34,45×` ;
- visibilité de la probe après delta et purge après rollback : validées.

Baseline finale canonique sur un corpus hermétique de sept repositories réels et huit requêtes :

- 2 104 fichiers ;
- 10 878 symboles ;
- 10 087 relations ;
- index Lucene cumulé : 5 121 497 octets ;
- indexation complète : 8 818 ms ;
- incrémental sans changement : 762 ms ;
- recherche fédérée : `p50 = 133 ms`, `p95 = 304 ms` ;
- construction de contexte : `p50 = 48 ms`, `p95 = 206 ms` ;
- `precision@3 = 0,4583` ;
- `recall@3 = 0,8958` ;
- `hit@3 = 1,0000` ;
- `MRR@3 = 1,0000`.

Le corpus hermétique est reconstruit à partir d'un snapshot Git contrôlé et exclut les artefacts propres au benchmark afin d'éviter l'auto-contamination des mesures. Les données locales non versionnées, notamment un éventuel `index.scip`, ne sont pas incluses dans cette baseline canonique ; les volumes sémantiques des anciens runs sur checkout enrichi ne sont donc pas strictement comparables.

Décision : les quatre paliers mesurés ne justifient ni Zoekt, ni OpenGrok, ni index distant, ni distribution de l'index, ni parallélisation prématurée de la fédération, ni nouveau changement des poids du ranking. Lucene reste le moteur local par défaut.

Critère de sortie : **validé**. NEXUS recherche désormais sur plusieurs repositories réels avec provenance, ranking déterministe, coordination multi-termes et diversification par chemin, tout en conservant des latences bornées et un résultat pertinent classé premier sur les huit requêtes du corpus final.

Documentation de référence : ADR-0043, `docs/developer/large-scale-search.md`, `docs/developer/large-scale-baseline-runbook.md`, `docs/developer/iteration-16-baseline-results.md` et `docs/developer/iteration-16-extended-portfolio-results.md`.

---

## Itération 17 — Recherche sémantique optionnelle

État : **terminée et validée localement le 21 juillet 2026**.

Objectif : mesurer si les embeddings améliorent réellement la qualité du contexte sans rendre un fournisseur d'embeddings, un runtime de modèle ou un index vectoriel externe obligatoire.

Architecture retenue :

- `EmbeddingProvider` et `SemanticSearchIndex` isolent les providers et le stockage vectoriel ;
- `LuceneSemanticSearchIndex` utilise le kNN/cosine natif Lucene et reste dérivé/reconstructible ;
- `SemanticIndexingService` suit le cycle rebuild/delta/suppression ;
- `OllamaEmbeddingProvider` fournit la baseline locale mesurée ;
- `SemanticSearchConfiguration` rend l'activation explicite ;
- `NexusApplication.create(paths)` reste sans embeddings ;
- `SemanticHybridContextRanker` remplace la fusion additive initiale par une Reciprocal Rank Fusion déterministe ;
- RRF `k = 60`, poids historique `1,0`, poids sémantique retenu `8,0` ;
- aucun moteur vectoriel externe n'est introduit.

Diagnostic et tuning :

- le kNN brut retrouve les six cibles réelles dans le top 17 ;
- la fusion additive initiale est rejetée car elle détruit ce signal ;
- le sweep RRF teste `1,00`, `1,25`, `1,50`, `2,00`, `3,00`, `4,00`, `6,00`, `8,00` ;
- `4,0` est le premier poids qui rejoint le rappel/hit du kNN brut ;
- `8,0` est le meilleur poids mesuré selon `recall -> hit -> MRR -> precision` et rejoint les quatre métriques top-3 du kNN brut.

Benchmark A/B réel final sur le corpus hermétique figé de l'Itération 16 :

- 236 fichiers ;
- 946 symboles ;
- 1 539 relations ;
- 6 requêtes ;
- baseline : `precision@3 = 0,0000`, `recall@3 = 0,0000`, `hit@3 = 0,0000`, `MRR@3 = 0,0000` ;
- sémantique RRF x8 : `precision@3 = 0,1667`, `recall@3 = 0,4167`, `hit@3 = 0,5000`, `MRR@3 = 0,3056` ;
- indexation complète : `1 943 ms` baseline contre `64 332 ms` sémantique, soit environ `33,11×` ;
- recherche moyenne : `208,8 ms` baseline contre `298,7 ms` sémantique, soit environ `1,43×` ;
- index sémantique : `1 001 537` octets.

Validation finale locale :

- `mvn clean install` : 73 tests, 0 échec, 0 erreur, 5 harness opt-in ignorés, `BUILD SUCCESS` en 16,571 s ;
- `SELF-SMOKE SUCCESS` ;
- 257 fichiers indexés ;
- 1 431 symboles ;
- 9 957 relations ;
- indexation complète : 2 271 ms ;
- indexation incrémentale sans changement : 657 ms ;
- recherche explicable : 727 ms ;
- contexte strict : 107/180 tokens en 885 ms ;
- contexte multi-source : 1 199/1 200 tokens en 1 051 ms ;
- contexte avec skill : 1 199/1 200 tokens en 1 123 ms ;
- contexte Git : 1 588/1 600 tokens en 1 084 ms ;
- réduction du contexte candidat strict : 99,47 % ;
- validation ciblée : 17 tests, 0 échec, 0 erreur ;
- corpus golden historique et fédéré : succès ;
- `NexusApplication.create(paths)` : sémantique désactivée.

Décision : **conserver la recherche sémantique comme capacité locale opt-in validée**, recommandée pour les recherches conceptuelles ou à forte divergence lexicale lorsque le coût d'indexation est acceptable. Ne pas l'activer automatiquement dans l'indexation standard : le coût d'environ `33×` ne le justifie pas. Lucene reste suffisant pour le stockage vectoriel mesuré et aucun provider n'est obligatoire.

Critère de sortie : **validé**. L'Itération 17 démontre un gain de qualité réel et mesurable sans modifier le chemin historique par défaut, tout en documentant explicitement le coût d'indexation et le compromis de latence.

Documentation de référence : ADR-0014, `docs/developer/semantic-search.md` et `docs/developer/iteration-17-semantic-results.md`.

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