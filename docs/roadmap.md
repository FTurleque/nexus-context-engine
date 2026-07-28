# Feuille de route NEXUS

Cette feuille de route est la source de vérité active pour l'évolution de NEXUS.

État de référence au 29 juillet 2026 :

```text
repository  FTurleque/nexus-context-engine
main        13fd6970f7350602c7a86aae729ddd4adad771bd
Java        21
version     0.1.0-SNAPSHOT
```

Le principe directeur reste :

> **qualité du contexte > correctness > passage à l'échelle > opérabilité > nombre de fonctionnalités > nombre d'intégrations.**

Une nouvelle brique doit rester optionnelle lorsqu'elle n'est pas indispensable au moteur. Un framework, un protocole client, un provider externe ou un runtime de modèle ne doit pas fuiter dans le cœur NEXUS.

Les ADR acceptés conservent l'historique des décisions et ne sont pas réécrits rétroactivement. Les documents d'itération conservent les mesures historiques. Cette roadmap décrit l'état courant et l'ordre des prochains travaux.

---

# État consolidé

| Phase | Itérations | État |
|---|---|---|
| Phase 1 — Valider le moteur | 0 → 4 | ✅ terminée |
| Phase 2 — Étendre les sources de contexte | 5 → 7 | ✅ terminée |
| Phase 3 — Enrichir l'intelligence de code | 8 → 10 | ✅ terminée |
| Phase 4 — Exposer NEXUS aux autres outils | 11 → 13 | ✅ terminée |
| Phase 5 — Écosystème et passage à l'échelle | 14 → 17 | ✅ terminée |
| Intégration compagnon MINOS | issue #11 / PR #12 | ✅ livrée |
| Phase 6 — Consolidation, hardening et industrialisation | 18 → 24 | ⏭ prochaine phase |

La validation NEXUS associée à l'intégration MINOS a établi sur Java 21 : 128 sources principales, 41 sources de tests, 80 tests exécutés, 0 failure, 0 error, 6 skipped et `BUILD SUCCESS`. Le replay réel MINOS → NEXUS a également été validé. Les preuves détaillées restent dans l'issue #11 et la PR #12.

---

# Phase 1 — Valider le moteur NEXUS

État global : **terminée et validée localement le 19 juillet 2026**.

## Itération 0 — Socle architectural

✅ Java 21, contrats du cœur, ADR et premier analyseur Java.

## Itération 1 — Indexation locale et fondations de recherche

✅ Registre de projets, scanner local, SQLite canonique, Lucene reconstructible et indexation incrémentale.

## Itération 2 — Recherche, graphe et classement explicable

✅ Recherche hybride, fusion déterministe, graphe minimal, ranking et explications de score.

## Itération 3 — Construction du contexte et budget

✅ `ContextBuilder`, estimation locale des tokens, fragments et invariant `estimatedTokens <= tokenBudget`.

## Itération 4 — CLI utilisable pour le MVP

✅ CLI humaine/JSON, codes de sortie stables, JAR autonome et flux projet → indexation → recherche → contexte.

L'historique détaillé des Itérations 0 à 10 est conservé dans [`roadmap-history-through-iteration-10.md`](roadmap-history-through-iteration-10.md).

---

# Phase 2 — Étendre les sources de contexte

État global : **terminée et validée localement le 20 juillet 2026**.

## Itération 5 — Instructions et documentation

✅ Markdown et instructions natives `AGENTS.md`, Copilot, Claude et Gemini avec scopes, références sécurisées et déduplication.

## Itération 6 — Skills et divulgation progressive

✅ Agent Skills découverts par métadonnées, sélectionnés avant chargement complet, intégrés sous budget et jamais exécutés par NEXUS.

## Itération 7 — Contexte Git

✅ Signal de récence et contexte Git local borné, explicable et en lecture seule.

Point de surveillance conservé : le coût de l'inspection Git doit être remesuré avant toute décision de cache ou de persistance supplémentaire.

---

# Phase 3 — Enrichir l'intelligence de code

État global : **terminée et validée localement le 20 juillet 2026**.

## Itération 8 — SCIP et index de code externes

✅ Import SCIP opportuniste derrière `CodeIndexImporter`, avec provenance conservée et sans rendre SCIP obligatoire.

## Itération 9 — Analyse Java profonde optionnelle

✅ Eclipse JDT Language Server derrière `CodeIntelligenceProvider`, activé explicitement via `--deep-java`.

La mesure de référence a confirmé que JDT LS est beaucoup plus coûteux que le chemin normal ; il reste strictement à la demande.

## Itération 10 — Multi-langage

✅ Support lexical natif de Kotlin, TypeScript, JavaScript, Python et SQL. Java conserve JavaParser comme structure embarquée ; les autres langages peuvent recevoir une structure via SCIP, MINOS ou un autre provider.

---

# Phase 4 — Exposer NEXUS aux autres outils

État global : **terminée et intégrée le 20 juillet 2026**.

## Itération 11 — Adaptateur REST

✅ Quarkus REST isolé du cœur, DTO dédiés, projets/indexation/recherche/contexte/explication, health et métriques.

PR #4 fusionnée dans `main`.

## Itération 12 — Adaptateur MCP

✅ Serveur MCP Java STDIO autonome avec six tools :

```text
list_projects
search_code
find_symbol
find_usages
build_context
explain_context
```

REST et MCP s'appuient sur la façade applicative `NexusApplication`. L'incident Jackson rencontré pendant cette itération constitue désormais un cas de référence pour la gouvernance future des dépendances.

PR #5 fusionnée dans `main`.

## Itération 13 — Adaptateurs Copilot et Claude

✅ Génération déterministe de configurations pour Copilot CLI, Copilot JetBrains, Claude project et Claude user, sans modifier silencieusement les préférences utilisateur.

PR #6 fusionnée dans `main`.

---

# Phase 5 — Écosystème et passage à l'échelle

État global : **terminée et validée**.

## Itération 14 — AI Skills Registry

✅ Snapshot local optionnel, priorité des skills projet, divulgation progressive et absence d'accès réseau pendant la construction du contexte.

PR #7 fusionnée dans `main`.

## Itération 15 — JARVIS, Alfred et Brainiac

✅ NEXUS utilisé comme fournisseur de contexte technique par JARVIS tout en conservant les responsabilités séparées : JARVIS orchestre, NEXUS construit le contexte, Watchtower résout les profils, Alfred/Brainiac spécialisent le traitement.

## Itération 16 — Recherche à grande échelle

✅ Recherche fédérée locale multi-repository avec provenance et diversification par chemin.

Baseline finale canonique :

```text
repositories               7
fichiers                    2 104
symboles                    10 878
relations                   10 087
index Lucene cumulé         5 121 497 octets
indexation complète         8 818 ms
incrémental sans changement 762 ms
recherche fédérée p50       133 ms
recherche fédérée p95       304 ms
contexte p50                48 ms
contexte p95                206 ms
precision@3                 0,4583
recall@3                    0,8958
hit@3                       1,0000
MRR@3                       1,0000
```

Décision conservée : aucun besoin mesuré de Zoekt, OpenGrok, index distant ou distribution de l'index. Lucene reste le moteur local par défaut.

## Itération 17 — Recherche sémantique optionnelle

✅ Embeddings et index vectoriel Lucene opt-in derrière `EmbeddingProvider` et `SemanticSearchIndex`, fusion RRF déterministe.

Baseline réelle finale :

```text
baseline precision@3       0,0000
sémantique precision@3     0,1667
baseline recall@3          0,0000
sémantique recall@3        0,4167
baseline hit@3             0,0000
sémantique hit@3           0,5000
baseline MRR@3             0,0000
sémantique MRR@3           0,3056
indexation sémantique      ~33,11× la baseline
recherche sémantique       ~1,43× la baseline
```

Décision conservée : capacité utile mais désactivée par défaut. Aucun moteur vectoriel externe n'est justifié par les mesures actuelles.

---

# Intégration compagnon — MINOS Code Intelligence

État : **terminée, validée et livrée le 24 juillet 2026** — issue #11 / PR #12.

```text
MINOS Java 24
  nexus-export --root <project>
        |
        | JSON stdout
        v
NEXUS Java 21
  minos-import <project> < stdin
        |
        v
SQLite -> SearchService -> ranking -> ContextBuilder
```

Invariants :

- aucune dépendance Maven NEXUS → MINOS ;
- aucun lancement de processus MINOS par NEXUS ;
- transport local explicite ;
- contrat JSON versionné ;
- validation stricte de la racine et des chemins ;
- `sourceProvider=minos` conservé ;
- ranking et construction du contexte restent sous responsabilité NEXUS.

Le replay réel final a confirmé l'import et la recherche d'un symbole MINOS dans NEXUS.

---

# Audit de consolidation — dette active

L'audit du 29 juillet 2026 est suivi par l'issue #13. Les éléments ci-dessous sont des travaux planifiés, pas des correctifs déjà livrés.

| ID | Priorité | Limite vérifiée | Traitement |
|---|---|---|---|
| F01 | P1 | fédération : diversification après top-K local pouvant sous-remplir le résultat | I18 |
| F02 | P1 | `IndexStatus.READY` non imposé uniformément aux recherches/symboles/usages | I18 |
| F03 | P1 | fenêtre de désynchronisation possible SQLite / index dérivés lors d'un échec | I18 |
| F04 | P1 | recherche symbolique par scan complet + fuzzy Java | I19 |
| F05 | P1 | `findSymbols` / `findUsages` par scans complets | I19 |
| F06 | P1 | graphe reconstruit depuis tous les symboles/relations à chaque recherche | I19 |
| F07 | P1 | CLI possède encore son propre composition root malgré `NexusApplication` | I20 |
| F08 | P1 | versions/plugins Maven dupliqués entre cœur et adaptateurs | I20 |
| F09 | P1 | `LocalAgentSkillsProvider` couple directement `AiSkillsRegistryProvider` | I20 |
| F10 | P2 | absence de single-flight explicite d'indexation par projet | I21 |
| F11 | P2 | absence de plafond configurable de taille de fichier avant lecture complète | I21 |
| F12 | P2 | import MINOS rescannant l'arbre physique pour son allow-list | I21 |
| F13 | P2 | lifecycle Lucene reader/searcher/writer créé par opération | I22 |
| F14 | P2 | sémantique validée mais non configurée uniformément dans CLI/REST/MCP | I22 |
| F15 | P2 | recherche fédérée applicative non encore exposée par les adaptateurs publics | I22 |
| F16 | P2 | coûts Git et embeddings à remesurer avant cache/batch/persistance | I22 |
| F17 | P1 fonctionnel | aucun `ContextBundle` fédéré multi-projet livré | I23 |
| F18 | P2 produit | distribution encore `0.1.0-SNAPSHOT`, sans installation versionnée autonome | I24 |

La documentation courante est réconciliée séparément de cette dette : les ADR restent historiques ; les documents décrivant l'architecture actuelle doivent, eux, rester synchronisés avec le code.

---

# Phase 6 — Consolidation, hardening et industrialisation

La Phase 6 corrige d'abord les limites structurelles avant de relancer l'expansion fonctionnelle.

## Itération 18 — Correctness de recherche et cohérence des index

État : **prochaine itération**.

### Objectifs

1. Corriger la fédération top-K : récupérer suffisamment de candidats locaux avant diversification afin qu'un fichier représenté par plusieurs candidats `FILE`/`SYMBOL` ne réduise artificiellement le nombre de résultats globaux.
2. Imposer une politique unique de disponibilité : une recherche, `findSymbols`, `findUsages` ou un contexte ne doit pas servir un projet `NOT_INDEXED`, `INDEXING` ou `FAILED` comme s'il était cohérent.
3. Formaliser l'état de génération des données canoniques et dérivées afin qu'un échec après commit SQLite soit détectable et récupérable sans résultat ambigu.
4. Ajouter des scénarios de panne contrôlée entre SQLite, providers et Lucene.
5. Conserver l'auto-récupération par rebuild lorsque l'état est `FAILED`.

### Gates de sortie

- test reproduisant le top-K sous-rempli avant correctif puis top-K rempli après diversification ;
- tests de refus de recherche sur tous les états non `READY` ;
- test de panne d'index dérivé et rebuild de récupération ;
- corpus golden mono-projet et fédéré sans régression ;
- `mvn clean install` + self-smoke exact-head.

### Décisions structurantes possibles

Créer un ADR si la notion de génération d'index ou la politique de lecture pendant indexation modifie le contrat de persistance.

---

## Itération 19 — Recherche symbolique et graphe à grande échelle

État : **planifiée après I18**.

### Objectifs

1. Remplacer les scans complets par des requêtes `IndexRepository` ciblées : recherche de symboles, relations par source/cible/kind et limites côté stockage.
2. Utiliser les indexes SQLite existants et ajouter uniquement les indexes démontrés nécessaires par les plans de requête.
3. Réserver Levenshtein/fuzzy Java à un ensemble préfiltré borné.
4. Éviter de reconstruire le graphe complet à chaque recherche : cache ou représentation matérialisée invalidée par génération d'index, uniquement après mesure.
5. Conserver provenance et déterminisme avec JavaParser, SCIP, JDT et MINOS.
6. Ajouter un benchmark qui sépare volume de fichiers, symboles et relations.

### Gates de sortie

- aucune stratégie de recherche interactive ne charge tous les symboles/relations du projet sans justification explicite ;
- résultats fonctionnels identiques ou meilleurs sur les corpus golden ;
- benchmark multi-palier avec p50/p95, heap et volumes ;
- pas d'adoption de moteur externe sans preuve qu'un backend local ciblé ne suffit pas.

---

## Itération 20 — Composition applicative et gouvernance des dépendances

État : **planifiée après I18 ; peut avancer en parallèle de I19 si les branches restent indépendantes**.

### Objectifs

1. Faire de `NexusApplication` la composition applicative commune pour CLI, REST et MCP ; les adaptateurs ne doivent conserver que parsing/validation/mapping/transport.
2. Introduire un parent/reactor Maven léger ou une stratégie équivalente de `dependencyManagement` afin de centraliser Java, plugins et versions communes sans coupler les runtimes.
3. Ajouter des vérifications de convergence/version capables de détecter le type de drift Jackson observé pendant I12.
4. Composer `LocalAgentSkillsProvider` et `AiSkillsRegistryProvider` comme deux `SkillSourceProvider` indépendants via `SkillDiscoveryService`.
5. Centraliser la configuration des capacités optionnelles sans imposer Ollama, JDT, SCIP, MINOS ou un adaptateur client au cœur.
6. Conserver les artefacts autonomes CLI/MCP/assistant-clients.

### Gates de sortie

- parité fonctionnelle CLI / `NexusApplication` ;
- REST et MCP toujours minces ;
- aucune duplication du composition root métier ;
- test de priorité local > registry avec providers réellement indépendants ;
- dépendances convergentes sur tous les artefacts.

---

## Itération 21 — Robustesse d'indexation et gouvernance des ressources

État : **planifiée après I18/I20**.

### Objectifs

1. Garantir une seule indexation active par `projectId` et définir le comportement d'un second appel concurrent.
2. Ajouter une limite configurable de taille de fichier et un diagnostic d'exclusion avant `Files.readString`/indexation Lucene.
3. Définir le comportement pour fichiers illisibles, encodages invalides et changements pendant le scan.
4. Faire valider l'import MINOS contre la vue canonique des fichiers NEXUS lorsque cela préserve les invariants de sécurité, plutôt que rescanner tout l'arbre physique.
5. Ajouter timeout/annulation/diagnostics pour les providers externes lourds lorsque le contrat le permet.
6. Vérifier les migrations SQLite et la stratégie de récupération/backup avant distribution stable.

### Gates de sortie

- test de concurrence d'indexation ;
- test de fichier géant exclu sans pic mémoire disproportionné ;
- test MINOS sans traversal physique inutile ;
- échec provider borné et projet laissé dans un état explicite ;
- self-smoke et corpus golden sans régression.

---

## Itération 22 — Runtime persistant, opérabilité et capacités opt-in

État : **planifiée après I19/I20/I21**.

### Objectifs

1. Mesurer le coût réel de création des readers/searchers/writers Lucene dans un serveur persistant puis réutiliser un lifecycle géré uniquement si le gain est démontré.
2. Faire refléter aux health/readiness l'état réel de NEXUS et des projets.
3. Uniformiser les métriques de durée, volume, erreurs providers et fédération sans journaliser le contenu privé des requêtes ou du contexte.
4. Exposer la recherche fédérée par les surfaces publiques qui en ont besoin sans dupliquer `FederatedSearchService`.
5. Rendre le mode sémantique explicitement configurable depuis les surfaces retenues, toujours désactivé par défaut.
6. Étudier batch/cache d'embeddings et cache Git seulement si les mesures montrent un bénéfice reproductible.
7. Conserver `SearcherManager`, cache Git, batching ou parallélisme comme décisions mesurées, jamais comme objectifs en soi.

### Gates de sortie

- tests concurrents REST/MCP ;
- readiness cohérente avec `IndexStatus` ;
- métriques sans fuite de contenu ;
- parité de recherche fédérée entre façade et adaptateurs retenus ;
- sémantique toujours opt-in ;
- comparaison avant/après de toute optimisation runtime adoptée.

---

## Itération 23 — ContextBundle fédéré multi-projet

État : **planifiée après I18 à I22**.

Une ancienne PR draft #10 avait préparé un prototype nommé « Itération 18 — contexte fédéré multi-projet ». Elle a été fermée sans merge et sans qualification locale ; elle reste une source historique d'idées, pas une base autoritative. Le numéro 18 est donc réaffecté au hardening et le besoin fonctionnel est replanifié ici.

### Objectifs

1. Construire un contexte technique sur une liste explicite de projets avec un **budget global unique**.
2. Conserver la provenance `projectId` de chaque item.
3. Éviter toute collision entre chemins relatifs identiques appartenant à des repositories différents.
4. Réutiliser la recherche fédérée, `ContextFragmentFactory`, la fusion et la sélection sous budget corrigées par les itérations précédentes.
5. Mesurer la starvation entre projets avant d'introduire des quotas.
6. Commencer par `FILE`, `SYMBOL`, `TEST`, `DOCUMENTATION`.
7. Définir séparément la politique multi-projet pour `INSTRUCTION`, `SKILL` et `GIT` ; refuser explicitement ces sources tant que leur sémantique n'est pas décidée.
8. Exposer la capacité dans `NexusApplication`, puis REST/MCP seulement après validation du contrat.

### Gates de sortie

- budget global jamais dépassé ;
- deux repositories avec le même chemin restent deux provenances distinctes ;
- déterminisme sur exécutions répétées ;
- métriques `selectedItemsByProject` et `selectedTokensByProject` ;
- benchmark de pertinence et starvation ;
- corpus mono-projet non régressé.

---

## Itération 24 — Distribution, installation et release readiness

État : **planifiée après stabilisation de la Phase 6**.

### Objectifs

1. Sortir du seul usage `0.1.0-SNAPSHOT` pour définir versioning et compatibilité des contrats publics.
2. Fournir un Maven Wrapper afin de rendre le build reproductible sans Maven préinstallé.
3. Produire des distributions versionnées pour les surfaces réellement supportées : CLI, MCP et, si retenu, REST.
4. Permettre une installation sans cloner le repository.
5. Produire checksums et métadonnées de version ; ajouter SBOM/signature si le modèle de distribution le justifie.
6. Définir l'upgrade de `NEXUS_HOME`, migrations SQLite et reconstruction des index dérivés.
7. Qualifier au minimum Windows et Linux sur les artefacts distribués.
8. Documenter installation, upgrade, rollback et désinstallation.

### Gates de sortie

- installation depuis un artefact versionné sur machine propre ;
- `--version` cohérent avec l'artefact ;
- self-smoke Windows + Linux ;
- migration d'un `NEXUS_HOME` antérieur testée ;
- checksums vérifiés ;
- aucune dépendance à un checkout source pour l'usage normal.

---

# Décisions explicitement différées

Les sujets suivants ne doivent pas devenir des chantiers automatiques :

- Zoekt / OpenGrok / moteur distant ;
- vector database externe ;
- parallélisation de la fédération ;
- cache Git persistant ;
- cache graphe complexe ;
- Tree-sitter embarqué ;
- tokenizer fournisseur exact ;
- index distribué.

Ils sont réévalués seulement si une mesure d'une itération de Phase 6 démontre un problème que l'architecture locale actuelle ne corrige pas simplement.

---

# Critères globaux de progression

Une évolution est adoptée durablement si elle apporte au moins un bénéfice mesurable parmi :

- correction d'un défaut de correctness ou de cohérence ;
- amélioration de précision, rappel, hit-rate ou MRR ;
- réduction du contexte ou du budget de tokens ;
- réduction significative de latence, mémoire ou I/O ;
- amélioration de la couverture fonctionnelle ;
- réduction de duplication ou de drift de dépendances ;
- amélioration de l'interopérabilité ou de l'opérabilité ;
- besoin réel d'une surface cliente.

Chaque itération doit :

1. partir d'un `main` identifié ;
2. préserver les ADR acceptés ou en créer un nouveau si la décision change ;
3. posséder des tests ciblés ;
4. rejouer le corpus golden concerné ;
5. produire des mesures avant/après lorsqu'elle prétend améliorer la performance ;
6. terminer par `mvn clean install` et `scripts/self-smoke.ps1` sur l'exact head ;
7. réconcilier README, architecture, guide développeur et roadmap avant merge.

> **La Phase 6 ne cherche pas à rendre NEXUS plus gros. Elle cherche à rendre les capacités déjà livrées plus correctes, plus cohérentes, plus scalables et réellement distribuables.**
