# Limites actuelles et dette de consolidation

Ce document décrit les limites **réellement présentes dans le code courant** de NEXUS au 29 juillet 2026 (`main` : `13fd6970f7350602c7a86aae729ddd4adad771bd`).

Il ne remplace ni les ADR ni la roadmap :

- les ADR expliquent les décisions historiques ;
- ce document décrit les écarts/limites actuels ;
- `docs/roadmap.md` ordonne leur traitement ;
- l'issue #13 suit la Phase 6 de consolidation.

Une limite indiquée ici n'est pas nécessairement un bug utilisateur déjà observé. Certaines sont des plafonds de scale ou des divergences entre l'architecture cible et l'implémentation actuelle.

## Synthèse

| ID | Priorité | Domaine | Limite | Roadmap |
|---|---|---|---|---|
| F01 | P1 | fédération | top-K potentiellement sous-rempli après diversification | I18 |
| F02 | P1 | disponibilité | gate `READY` non uniforme | I18 |
| F03 | P1 | cohérence | fenêtre SQLite / index dérivés sur panne | I18 |
| F04 | P1 | recherche | scan complet des symboles | I19 |
| F05 | P1 | API | `findSymbols` / `findUsages` projet-wide | I19 |
| F06 | P1 | graphe | reconstruction complète à chaque recherche | I19 |
| F07 | P1 | composition | composition root CLI dupliqué | I20 |
| F08 | P1 | build | drift de versions entre POM | I20 |
| F09 | P1 | skills | provider registry couplé au provider local | I20 |
| F10 | P2 | indexation | pas de single-flight explicite par projet | I21 |
| F11 | P2 | ressources | pas de taille maximale de fichier configurable | I21 |
| F12 | P2 | MINOS | allow-list reconstruite par scan physique complet | I21 |
| F13 | P2 | Lucene | lifecycle reader/writer par opération | I22 |
| F14 | P2 | sémantique | capacité opt-in non opérationnalisée uniformément | I22 |
| F15 | P2 | fédération | recherche fédérée non exposée dans les adaptateurs publics | I22 |
| F16 | P2 | performance | Git/embeddings à optimiser seulement après mesure | I22 |
| F17 | P1 fonctionnel | contexte | pas de `ContextBundle` fédéré livré | I23 |
| F18 | P2 produit | distribution | `0.1.0-SNAPSHOT`, usage encore orienté checkout | I24 |

---

## F01 — Top-K fédéré après diversification

### État actuel

`FederatedSearchService` demande à chaque projet :

```java
searchService.search(project, query, limit, explain)
```

puis fusionne les candidats et ne conserve qu'un résultat par couple `projectId + path`.

Si les `limit` premiers candidats locaux correspondent majoritairement à plusieurs `FILE`/`SYMBOL` du même fichier, la diversification peut supprimer plusieurs éléments sans avoir récupéré les candidats classés juste après le top-K local.

### Risque

Un appel demandant 10 résultats peut retourner moins de 10 chemins uniques alors que des candidats pertinents existaient au-delà du dixième rang local.

### Direction

Sur-récupérer avant diversification, ou intégrer la diversification plus tôt dans le retrieval. Le facteur doit être mesuré et borné.

---

## F02 — Disponibilité d'un projet non uniformisée

### État actuel

`DefaultContextBuilder` refuse explicitement un projet dont `IndexStatus != READY`.

En revanche, les chemins applicatifs de recherche, `findSymbols` et `findUsages` ne réalisent pas tous le même contrôle avant d'interroger les indexes.

### Risque

Un projet `INDEXING` ou `FAILED` peut être interrogé alors que ses données canoniques et dérivées ne représentent pas une génération cohérente.

### Direction

Créer une politique applicative commune de readiness et l'appliquer à toutes les lectures dépendant d'un index.

---

## F03 — Cohérence SQLite / Lucene / providers sur panne partielle

### État actuel

`ProjectIndexingService` applique les changements SQLite avant de rafraîchir certains providers et avant l'écriture de Lucene.

Si une étape ultérieure échoue :

- le projet passe à `FAILED` ;
- la prochaine indexation force un rebuild ;
- mais SQLite peut déjà contenir une version plus récente que Lucene pendant cette fenêtre.

### Risque

Sans gate F02, une recherche peut observer un état partiel.

### Direction

Formaliser une génération d'index ou une politique équivalente, empêcher toute lecture incohérente et tester une panne injectée à chaque frontière importante.

---

## F04 — Recherche symbolique par scan complet

### État actuel

`SymbolSearchStrategy` appelle `IndexRepository.findSymbols(projectId)` puis exécute en Java :

- comparaison exacte ;
- `contains` ;
- distance de Levenshtein ;
- tri ;
- `limit`.

Le calcul est donc proportionnel au nombre total de symboles du projet avant application du top-K.

### Risque

Le comportement validé sur environ 10 000 symboles n'est pas un modèle adapté à des centaines de milliers de symboles.

### Direction

Ajouter des requêtes repository ciblées et préfiltrer avec SQLite/Lucene avant toute fuzzy coûteuse en Java.

---

## F05 — `findSymbols` et `findUsages` projet-wide

### État actuel

La façade `NexusApplication` implémente ces opérations en chargeant respectivement tous les symboles ou toutes les relations puis en filtrant en mémoire.

Ces méthodes alimentent notamment les tools MCP `find_symbol` et `find_usages`.

### Direction

Déplacer la sélection et la limite dans `IndexRepository` avec des requêtes indexées par nom/nom qualifié/source/cible/kind.

---

## F06 — Graphe reconstruit par requête

### État actuel

`ProjectGraphBuilder.build(projectId)` recharge les symboles et les relations du projet puis reconstruit le graphe d'imports. `GraphCandidateEnricher` l'appelle pendant la recherche.

### Risque

Le coût augmente avec le corpus même lorsqu'aucune indexation n'a changé entre deux recherches.

### Direction

Après mesure, conserver une représentation d'adjacence associée à la génération d'index ou un cache invalidé explicitement. Aucun cache complexe avant benchmark.

---

## F07 — Composition root CLI dupliqué

### État actuel

`NexusApplication` centralise la composition utilisée par REST et MCP.

`NexusCli` instancie encore directement :

- SQLite repositories ;
- Lucene ;
- JavaParser/Markdown ;
- SCIP/JDT ;
- `ProjectIndexingService` ;
- `SearchService` ;
- enrichisseurs ;
- `DefaultContextBuilder`.

### Risque

Une nouvelle option ou un changement de composition peut être appliqué à REST/MCP mais oublié dans la CLI, ou l'inverse.

### Direction

Faire déléguer la CLI à `NexusApplication` et conserver dans la CLI uniquement parsing, validation et rendu.

---

## F08 — Gouvernance Maven dispersée

### État actuel

Le cœur, REST, MCP et assistant-clients possèdent des POM indépendants qui répètent Java, plugins et plusieurs versions de dépendances.

L'Itération 12 a déjà rencontré une incompatibilité Jackson entre le SDK MCP et NEXUS et a dû aligner explicitement `jackson-core`, `jackson-databind` et `jackson-annotations`.

### Direction

Introduire un parent/reactor léger ou une stratégie équivalente de `dependencyManagement`, puis vérifier convergence et toolchain sans rendre Quarkus/MCP transitifs vers le cœur.

---

## F09 — Composition AI Skills Registry non conforme au port prévu

### État actuel

`SkillDiscoveryService` sait agréger plusieurs `SkillSourceProvider`.

Cependant `NexusApplication` compose uniquement `LocalAgentSkillsProvider`, et ce provider instancie lui-même `AiSkillsRegistryProvider` dans `discover()`.

### Conséquence

Le comportement fonctionnel local > registry est validé, mais la frontière provider est moins modulaire que l'architecture documentée.

### Direction

Composer explicitement :

```text
LocalAgentSkillsProvider
AiSkillsRegistryProvider
        │
        ▼
SkillDiscoveryService
```

et conserver la priorité dans `SkillDescriptor`/déduplication, pas dans un couplage provider → provider.

---

## F10 — Indexation concurrente non sérialisée explicitement

### État actuel

Aucun mécanisme de single-flight/lock par `projectId` n'est visible dans `ProjectIndexingService`.

SQLite possède un `busy_timeout`, mais cela ne définit pas la sémantique métier de deux indexations simultanées.

### Direction

Garantir une opération active par projet et décider explicitement si un second appel est refusé, rejoint l'opération existante ou est mis en file.

---

## F11 — Taille de fichier non bornée

### État actuel

`ProjectScanner` enregistre les fichiers supportés sans plafond configurable de taille. Pour un fichier à indexer lexicalement, `ProjectIndexingService` utilise ensuite `Files.readString(...)`.

### Risque

Un dump SQL, Markdown ou source très volumineux peut provoquer une pression mémoire et une latence disproportionnées.

### Direction

Ajouter une limite configurable, un diagnostic explicable et des tests de gros fichier. La valeur par défaut doit être choisie à partir de corpus réels.

---

## F12 — Allow-list MINOS construite par `Files.walk`

### État actuel

`MinosCodeIndexImporter.safeProjectFiles(root)` parcourt tout l'arbre et appelle `toRealPath()` pour construire une allow-list de fichiers sûrs.

Cette protection évite les traversals pilotés par le JSON, mais elle parcourt aussi des zones que le scanner NEXUS peut ignorer (`.git`, `target`, `node_modules`, etc.).

### Direction

Réutiliser la vue canonique NEXUS (`indexed_files`) ou une allow-list produite par le scanner, sans affaiblir les protections de chemin de l'ADR-0044.

---

## F13 — Lifecycle Lucene par opération

### État actuel

La recherche ouvre un `FSDirectory`, un `DirectoryReader`, un `StandardAnalyzer` et un `IndexSearcher` par appel. Les écritures ouvrent également leur writer par opération.

### Position actuelle

Ce choix reste acceptable sur les baselines mesurées et simplifie le lifecycle local.

### Direction

Mesurer sous charge REST/MCP persistante. Adopter `SearcherManager` ou un lifecycle partagé uniquement si la mesure prouve un bénéfice significatif et si l'invalidation reste sûre.

---

## F14 — Recherche sémantique essentiellement programmable

### État actuel

`SemanticSearchConfiguration` est utilisée par `NexusApplication` et les tests/benchmarks. La CLI, REST et MCP ne possèdent pas encore une politique/configuration homogène pour l'activer.

### Direction

Après unification de composition : configuration explicite, désactivée par défaut, avec provider/modèle/dimensions vérifiés. Ne jamais déclencher d'embeddings implicitement.

---

## F15 — Recherche fédérée non exposée par les adaptateurs

### État actuel

`NexusApplication.searchAcrossProjects(...)` et `FederatedSearchService` existent, mais les contrats REST/MCP/CLI courants restent principalement mono-projet.

### Direction

Exposer la fédération seulement après F01/F02, avec une portée de projets explicite et une provenance obligatoire dans la réponse.

---

## F16 — Coûts Git et sémantique

### Git

Les itérations précédentes ont observé un coût sensible lié à l'inspection de commits. Aucun cache n'est adopté tant qu'un benchmark multi-repository ne démontre pas un besoin.

### Sémantique

La baseline réelle a mesuré environ :

```text
indexation  ~33,11×
recherche   ~1,43×
```

par rapport au chemin lexical de référence.

`SemanticIndexingService` produit actuellement les embeddings document par document.

### Direction

Mesurer batch, cache de vecteurs par hash et incrémental avant d'élargir l'usage. Le mode sémantique reste opt-in.

---

## F17 — `ContextBundle` fédéré non livré

### État actuel

La recherche fédérée existe, mais `DefaultContextBuilder` reste projet-local.

Une ancienne PR draft #10 a préparé un prototype de contexte fédéré, puis a été fermée sans merge et sans validation locale acquise.

### Direction

Reprendre le besoin après les corrections de Phase 6 : budget global, provenance, collision de chemins, fairness/starvation. Commencer par les sources techniques `FILE`, `SYMBOL`, `TEST`, `DOCUMENTATION` et refuser `INSTRUCTION`, `SKILL`, `GIT` tant que leur sémantique multi-projet n'est pas décidée.

---

## F18 — Distribution encore orientée développement

### État actuel

Le projet publie des artefacts `0.1.0-SNAPSHOT` et des runners Maven locaux. L'usage normal suppose encore un build/checkout dans la plupart des scénarios documentés.

### Direction

Versioning, Maven Wrapper, distributions versionnées, checksums, installation sans clone, upgrade/migration/rebuild de `NEXUS_HOME`, qualification Windows/Linux.

---

# Sujets qui ne sont pas des défauts actuels

Les éléments suivants restent volontairement différés et ne doivent pas être introduits juste parce qu'ils existent :

- Zoekt ;
- OpenGrok ;
- Elasticsearch/OpenSearch ;
- vector DB externe ;
- Tree-sitter embarqué ;
- index distribué ;
- cache Git persistant ;
- parallélisme multi-projet ;
- tokenizer exact d'un fournisseur.

Une itération peut les réexaminer si un benchmark démontre un besoin que l'architecture locale ne corrige pas simplement.

# Règle de fermeture d'une limite

Une ligne Fxx ne doit être considérée close que lorsque :

1. le comportement problématique possède un test ou benchmark reproductible ;
2. le correctif est validé sur exact head ;
3. les corpus golden concernés ne régressent pas ;
4. la documentation courante est mise à jour ;
5. la roadmap indique le résultat réel plutôt que l'intention initiale.
