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

Objectif : exposer les capacités NEXUS à d'autres applications sans introduire de framework applicatif obligatoire dans le cœur.

Stack retenue :

- Java 21 ;
- Quarkus 3.33 LTS, micro-version figée `3.33.2.1` pour la reproductibilité de l'itération ;
- Quarkus REST + Jackson ;
- SmallRye Health ;
- Micrometer + Prometheus.

Livrables validés :

- adaptateur autonome dans `adapters/rest-quarkus` ;
- aucune dépendance Quarkus dans le `pom.xml` du cœur ;
- DTO HTTP isolés des modèles métier ;
- mapping explicite domaine → DTO ;
- ressources REST sans logique métier de recherche, ranking ou construction de contexte ;
- `GET /api/v1/projects` ;
- `POST /api/v1/projects` ;
- `GET /api/v1/projects/{projectId}` ;
- `POST /api/v1/projects/{projectId}/index` ;
- `GET /api/v1/projects/{projectId}/index` ;
- `POST /api/v1/projects/{projectId}/search` ;
- `POST /api/v1/projects/{projectId}/context` ;
- `POST /api/v1/projects/{projectId}/explain/search` ;
- `POST /api/v1/projects/{projectId}/explain/context` ;
- readiness `/q/health/ready` ;
- métriques `/q/metrics` ;
- compteurs et timers NEXUS pour `index`, `search` et `context` ;
- erreurs HTTP JSON normalisées ;
- ADR-0039 ;
- documentation `docs/developer/rest-api.md` ;
- script `scripts/validate-iteration-11.ps1` avec mode `-AdapterOnly`.

Validation réelle du 20 juillet 2026 :

- `mvn clean install` du cœur : succès ;
- 45 tests cœur, 0 échec, 0 erreur ;
- baseline qualité conservée : `mean precision@3 = 0,4444`, `mean recall@3 = 1,0000` ;
- `scripts/self-smoke.ps1` : `SELF-SMOKE SUCCESS` ;
- `mvn -f adapters/rest-quarkus/pom.xml clean verify` : `BUILD SUCCESS` ;
- test REST de bout en bout : 1 test, 0 échec, 0 erreur ;
- création projet via HTTP : succès ;
- indexation via HTTP : succès ;
- recherche et explication via HTTP : succès ;
- contexte et explication via HTTP : succès ;
- `/q/health/ready` : succès ;
- `/q/metrics` : succès ;
- runner `adapters/rest-quarkus/target/quarkus-app/quarkus-run.jar` produit.

Mesures Quarkus :

- démarrage en profil test : 3,572 s ;
- test REST de bout en bout : 4,233 s ;
- build adaptateur complet : 19,568 s ;
- augmentation Quarkus : 2 996 ms.

Défauts révélés et corrigés pendant la validation :

1. l'initialisation de `SqliteDatabase` pouvait lever `SQLException` / `IOException` sans traitement dans le bean CDI ; l'erreur est maintenant encapsulée explicitement avec sa cause ;
2. `@Consumes(application/json)` appliqué au niveau de toute la ressource imposait à tort un `Content-Type` JSON au `POST /index` sans corps et provoquait HTTP 415 ; `@Consumes` est désormais limité aux endpoints recevant réellement un DTO.

Critère de sortie : **validé**. Les capacités principales de NEXUS sont accessibles par REST, les DTO restent isolés du cœur, l'observabilité est disponible et aucune logique métier n'est dupliquée dans les ressources HTTP.

PR #4 fusionnée dans `main` au commit `d5565dc3da0be823929afe73ca7345fd2bc1e6ca`.

---

## Itération 12 — Adaptateur MCP

État : **en cours**.

Objectif : rendre NEXUS directement utilisable par les assistants et agents compatibles MCP.

Décisions :

- utiliser le SDK Java MCP officiel conformément à ADR-0016 ;
- isoler le SDK dans `adapters/mcp-java` ;
- utiliser STDIO comme premier transport local ;
- centraliser la composition du moteur dans `NexusApplication` afin que REST et MCP appellent la même façade applicative ;
- ne jamais réimplémenter le framing, le transport, le ranking ou la construction du contexte dans les handlers MCP.

Premier périmètre :

```text
list_projects
search_code
find_symbol
find_usages
build_context
explain_context
```

Livrables en cours :

- adaptateur MCP Java autonome ;
- serveur STDIO ;
- schémas d'entrée explicites ;
- réponses JSON dans un contenu texte MCP inspectable ;
- test d'intégration avec un vrai client MCP Java ;
- parité `search_code` avec `NexusApplication.search` ;
- parité `build_context` avec `NexusApplication.context` ;
- régression REST après extraction de la façade commune ;
- ADR-0040 ;
- documentation `docs/developer/mcp.md` ;
- script `scripts/validate-iteration-12.ps1`.

Critère de sortie : un client MCP réel peut initialiser une session, découvrir et appeler les tools NEXUS, et recevoir pour les capacités centrales les mêmes résultats que la façade utilisée par l'API.

Aucune validation n'est revendiquée avant exécution locale du script d'itération.

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
