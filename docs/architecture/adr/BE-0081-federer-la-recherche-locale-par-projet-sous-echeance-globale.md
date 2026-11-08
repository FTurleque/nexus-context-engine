---
id: BE-0081
title: "Fédérer la recherche locale par projet, avec provenance conservée et une échéance globale sans parallélisme"
domain: backend
rules: true
---

# Fédérer la recherche locale par projet, avec provenance conservée et une échéance globale sans parallélisme

- **Statut** : Accepted
- **Date** : 2026-11-08
- **Origine** : consolidation du registre ADR (audit du 2026-11-08)
- **Complète** : [DATA-0074](DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md)
- **Remplace** : [BE-0043](archive/BE-0043-federer-la-recherche-locale-par-projet-avant-un-moteur-externe.md), [BE-0064](archive/BE-0064-borner-la-federation-par-une-echeance-globale-sans-parallelisme.md), [DATA-0054](archive/DATA-0054-lectures-registre-federees-groupees.md)
- **Remplacé par** : —

## Context

NEXUS doit chercher dans plusieurs repositories locaux à la fois : combiner les résultats de plusieurs codebases en gardant chaque résultat rattaché à son repository d'origine. Le registre de projets, SQLite et Lucene isolent déjà chaque projet par `projectId` ; le `SearchService` traite un seul `ProjectDescriptor` à la fois. Zoekt, OpenGrok, des index distants ou un cache partagé ajouteraient une dépendance opérationnelle avant toute preuve que Lucene et l'architecture locale sont insuffisants.

Une requête fédérée parcourt jusqu'à `FederatedScopePolicy.MAX_PROJECTS` (100) projets. Relire chaque descripteur avant, pendant et après la lecture ouvrait 75 connexions SQLite pour 25 projets ; une acquisition des verrous sur des threads virtuels a bloqué un essai local. Le coût d'orchestration de la boucle est négligeable (90 ms pour 100 projets simulés, `FederatedContextBudgetScaleBenchmarkTest`) : la latence vient du travail par projet (recherche, Git, SQLite) et de l'embedding de la requête (timeout de 120 s par appel pour Ollama par défaut). Sans échéance, un projet lent retarde toute la requête et la réponse ne dit pas quels projets n'ont pas été interrogés.

La question est : **comment fédérer la recherche sur plusieurs projets locaux, avec une provenance explicite, des lectures de registre groupées et une latence bornée et explicable, sans moteur externe ni parallélisme ?**

## Options envisagées

- **Architecture de fédération** : remplacer Lucene par Zoekt ou OpenGrok (dépendance avant mesure) ; un index Lucene global de tous les projets (isolation et reconstruction par projet perdues) ; un `SearchService` multi-projet (ranking et contrats modifiés sans mesure) ; **fédérer des recherches projet-locales au-dessus du `SearchService` existant** (retenu).
- **Lectures du registre** : relire chaque descripteur projet par projet (connexions proportionnelles au scope) ; acquérir les verrous sur des threads virtuels (propriété des verrous compliquée, blocage observé) ; **lecture groupée par lot dans un snapshot transactionnel, verrous séquentiels sur le thread propriétaire** (retenu).
- **Latence** : exécuteur avec timeout par projet (threads, annulation, ordonnancement : le parallélisme exclu) ; statu quo sans échéance (latence non bornée, coupes inexplicables) ; **échéance globale vérifiée avant chaque projet** (retenu).

## Decision

### Fédération au-dessus du SearchService

`FederatedSearchService` fédère plusieurs recherches projet-locales au-dessus du `SearchService` existant, avec une portée de projets explicite et une provenance conservée. Chaque projet garde son index Lucene isolé, ses données SQLite identifiées par `projectId`, les stratégies de recherche et le ranking déterministe existants.

```text
Projet A ─┐
Projet B ─┼─▶ SearchService par projet ─▶ RankedCandidate ─▶ FederatedSearchService ─▶ FederatedSearchHit
Projet C ─┘                                                                             (ProjectDescriptor + RankedCandidate)
```

La fédération :

- accepte uniquement une liste explicite de projets, dédupliquée par `projectId`, au plus `FederatedScopePolicy.MAX_PROJECTS` ;
- exécute la recherche indépendamment pour chaque projet ;
- ne déduplique pas deux résultats provenant de projets distincts, même de chemin relatif identique ;
- trie les résultats globaux de façon déterministe ;
- conserve le `ProjectDescriptor` avec chaque résultat : la provenance est explicite, jamais inférée d'un chemin.

`NexusApplication` expose `searchAcrossProjects(...)` : les adaptateurs CLI, REST et MCP réutilisent cette façade sans réimplémenter la fédération. Aucune recherche globale implicite sur tous les projets n'existe. Le `ContextBuilder` reste mono-projet : un contexte multi-projet relève d'une décision séparée, car instructions natives, skills et données Git sont projet-locales et doivent être arbitrées sous un budget de tokens unique.

### Lectures groupées du registre et verrous

Le port `ProjectRepository.findByIds` charge uniquement les projets demandés, sans doublons, dans l'ordre de la portée. L'adaptateur SQLite lit descripteurs, langues et technologies par une seule connexion et un snapshot transactionnel, par lots de **500 identifiants** au plus. La façade garde ses trois contrôles de disponibilité, groupés : une portée de 100 projets demande trois ouvertures du registre, quel que soit le nombre de projets. Aucun cache de statut ne masque une mutation.

Les verrous de lecture sont acquis séquentiellement par UUID et libérés dans l'ordre inverse, sur le même thread propriétaire. Une acquisition partielle libère tous les verrous déjà obtenus et conserve l'exception initiale.

### Échéance globale sans parallélisme

- Une requête fédérée, de recherche ou de contexte, démarre une échéance (`FederationDeadline`) après validation de la requête et du scope. L'échéance est vérifiée **avant** chaque projet, dans l'ordre déterministe du scope ; un projet non commencé à l'échéance n'est pas servi ; un projet commencé n'est jamais interrompu.
- La boucle reste synchrone et séquentielle : aucun exécuteur, aucun thread, aucun flux parallèle.
- L'horloge est injectée (`java.time.InstantSource`) : les tests simulent le temps sans dormir ni dépendre de l'horloge réelle.
- **Valeur par défaut : 60 secondes**, modifiable par `NEXUS_FEDERATION_DEADLINE_SECONDS`, **bornée à l'intervalle 1 à 600 secondes** ; une valeur hors intervalle ou non entière est refusée au démarrage avec un message en français. La valeur et l'intervalle sont définis dans `FederationDeadlinePolicy`.
- Le temps restant plafonne l'appel d'embedding de la requête : `min(timeout du provider, temps restant)`, au moins 1 ms. Le vecteur de requête est calculé au premier projet qui en a besoin et partagé ensuite (`SearchQueryContext`).
- Les projets non commencés sont nommés, dans l'ordre du scope, par le diagnostic public `unservedProjects` (identifiant et nom), produit par la projection publique unique ([ARCH-0085](ARCH-0085-stabiliser-le-contrat-de-sortie-publique-cli-rest-mcp.md)) et donc identique en CLI, REST et MCP. Sans coupe, la liste est vide et les résultats sont identiques à ceux d'une exécution sans échéance.
- Les projets servis ne changent ni d'ordre ni de score : l'échéance ne décide que de leur nombre.

### Moteur externe

Zoekt, OpenGrok ou un autre backend ne sont évalués que si une baseline reproductible (nombre de repositories, fichiers, symboles, relations, taille des index, temps d'indexation complète et incrémentale, latence projet-locale et fédérée après échauffement et répétitions, temps de construction du contexte, `precision@3`, `recall@3`, mémoire) montre une limite significative de l'architecture locale : latence fédérée incompatible avec l'usage, coût d'indexation, taille ou reconstruction des index, dégradation de `precision@3` ou `recall@3`, recherche structurelle SQLite dominante, besoin réel d'index distants. Un moteur externe reste derrière un port ou une stratégie NEXUS et n'est jamais requis pour une recherche locale.

Hors décision : parallélisme, échéance par projet ou mono-projet, contexte multi-projet.

## Do's and Don'ts

### Do

- Fédérer des recherches projet-locales par `FederatedSearchService`, au-dessus du `SearchService` existant.
- Accepter uniquement une liste explicite de projets, dédupliquée par `projectId`.
- Trier les résultats globaux de façon déterministe et conserver le `ProjectDescriptor` avec chaque résultat.
- Exposer la fédération par `NexusApplication.searchAcrossProjects(...)`.
- Charger les projets par `ProjectRepository.findByIds`, en une connexion et un snapshot transactionnel, par lots de 500 identifiants.
- Acquérir les verrous séquentiellement par UUID, les libérer dans l'ordre inverse sur le thread propriétaire, et tout libérer en cas d'acquisition partielle en gardant l'exception initiale.
- Vérifier l'échéance globale avant chaque projet, dans l'ordre du scope, avec une horloge injectée.
- Plafonner le délai d'embedding de la requête par le temps restant.
- Nommer chaque projet non servi, dans l'ordre du scope, dans `unservedProjects`.
- Lire la valeur par défaut (60 s) et l'intervalle (1 à 600 s) dans `FederationDeadlinePolicy`.

### Don't

- Ne pas dédupliquer deux résultats provenant de projets distincts.
- Ne pas introduire de recherche globale implicite sur tous les projets.
- Ne pas rendre le `ContextBuilder` multi-projet.
- Ne pas relire les descripteurs projet par projet pour les contrôles de façade.
- Ne pas acquérir ni libérer les verrous hors du thread propriétaire.
- Ne pas paralléliser la boucle fédérée ni introduire d'exécuteur pour appliquer l'échéance.
- Ne pas interrompre un projet commencé ; ne pas modifier l'ordre ni le score des projets servis selon l'échéance.
- Ne pas asserter la durée réelle ni dormir dans un test d'échéance.
- Ne pas rendre un moteur externe requis pour une recherche locale.

## Consequences

### Positives

- Le multi-repository fonctionne sans nouveau moteur, sans réseau ni serveur ; index isolés et reconstructibles par projet.
- La provenance de chaque résultat est explicite ; CLI, REST et MCP partagent la même façade et le même diagnostic.
- Le coût d'accès au registre ne dépend plus du nombre de projets.
- La latence d'une requête fédérée est bornée à l'échéance plus le coût du dernier projet commencé ; les projets non servis sont nommés, jamais abandonnés en silence.

### Négatives

- Une recherche par projet : le coût total croît avec le nombre de projets.
- Les signaux lexicaux Lucene sont normalisés à l'intérieur de chaque projet : la comparabilité inter-projets se mesure.
- Une réponse peut être partielle ; un projet commencé juste avant l'échéance peut la dépasser, seul l'embedding est plafonné.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Réponse partielle prise pour une réponse complète | Moyen | `unservedProjects` rendu par toutes les surfaces |
| Défaut de 60 s trop court sur un scope lent | Moyen | `NEXUS_FEDERATION_DEADLINE_SECONDS`, intervalle borné, recalibrage par qualification |
| Cycle de verrous entre portées qui se recouvrent | Élevé | Ordre global des UUID, libération inverse sur le thread propriétaire |
| Tests dépendants du temps | Moyen | Horloge injectée ; aucun test n'asserte la durée réelle |

## Compliance and Enforcement

- Règle Archgate `BE-0081-federer-la-recherche-locale-par-projet-sous-echeance-globale.rules.ts` : `federated-search-built-on-search-service` exige que `FederatedSearchService` référence `SearchService` sans accès direct à Lucene, à `com.nexus.persistence`, à `java.sql` ni à `java.net`, et que `NexusApplication` déclare `searchAcrossProjects` ; `context-builder-stays-single-project` refuse tout type `Federated*` dans `ContextBuilder.java` et `DefaultContextBuilder.java` ; commentaires et littéraux effacés.
- Tests (`core`, lancés par `mvnw clean install`, `.github/workflows/ci.yml` : job Linux reactor et job Windows gate par `scripts/validate-phase-6.ps1`) : `FederatedSearchServiceIntegrationTest` (`searchesExplicitProjectsPreservesProvenanceAndDiversifiesPaths`, `rejectsAnEmptyProjectScope`), `FederatedSearchServicePolicyTest`, `FederatedSearchScopePolicyTest`, `FederatedGoldenSearchCorpusTest` (`core/src/test/java/com/nexus/search/`) ; `SqliteProjectRepositoryBulkReadTest` (métadonnées, ordre de la portée, frontière de lot) ; `NexusApplicationFederatedScopePolicyTest.federatedReadsReleaseLocksAfterSuccessAndPartialAcquisitionFailure` ; à horloge simulée, `FederatedContextServiceTest`, `FederatedSearchServicePolicyTest` et `FederationDeadlinePolicyTest` (défaut, bornes, variable d'environnement).
- Parité CLI, REST et MCP : `unservedProjects` est une composante des records de `PublicOutput`, couverte par `PublicOutputSchema` et les tests de parité des adaptateurs (`FederatedUnservedProjectsDiagnosticTest`).
- Revue : aucune boucle fédérée ne s'exécute dans un exécuteur ou un flux parallèle ; aucune intégration Zoekt ou OpenGrok sans mesures justificatives.

## Conditions de réexamen

La décision est réévaluée si les mesures de passage à l'échelle montrent qu'une recherche par projet, la recherche structurelle SQLite ou la reconstruction des index Lucene ne satisfont plus les besoins opérationnels ; si une mesure qualifiée montre qu'un parallélisme borné réduit la latence fédérée sans dégrader le déterminisme ; ou si le coût réel par projet rend le défaut de 60 s inadapté. Une modification substantielle donne lieu à un nouvel ADR qui remplace celui-ci.

## Mise en œuvre

- Fédération : `core/src/main/java/com/nexus/search/FederatedSearchService.java`, `FederatedSearchOutcome.java`, `SearchQueryContext.java` ; façade `NexusApplication.searchAcrossProjects(...)` (`core/src/main/java/com/nexus/application/NexusApplication.java`).
- Portée et échéance : `core/src/main/java/com/nexus/project/FederatedScopePolicy.java` (`MAX_PROJECTS = 100`), `FederationDeadline.java`, `FederationDeadlinePolicy.java` (`DEFAULT_DURATION`, `MIN_DURATION`, `MAX_DURATION`) ; plafond d'embedding dans `core/src/main/java/com/nexus/search/semantic/SemanticSearchStrategy.java`.
- Registre : `ProjectRepository.findByIds`, lots de 500 dans `core/src/main/java/com/nexus/persistence/sqlite/SqliteProjectRepository.java`.
- Diagnostic : composante `unservedProjects` de `PublicOutput.FederatedSearch` et `PublicOutput.FederatedContext` (`core/src/main/java/com/nexus/exposure/PublicOutput.java`).
- Description technique courante : [`large-scale-search.md`](../../developer/large-scale-search.md), [`federated-scope-fail-fast.md`](../../developer/federated-scope-fail-fast.md).

## Écarts constatés

Constat du 2026-10-29 ; la décision est inchangée.

- **Contexte multi-projet sans décision séparée.** La décision réserve la construction d'un contexte multi-projet à une décision séparée. `FederatedContextService` (`core/src/main/java/com/nexus/context/FederatedContextService.java:28`) construit un contexte sur plusieurs projets en composant des `ContextBuilder` mono-projet (`core/src/main/java/com/nexus/context/FederatedContextService.java:39`), et aucun ADR du registre ni de `archive/` ne porte cette décision (aucune occurrence de `FederatedContext` dans `docs/architecture/adr/`). Le `ContextBuilder` lui-même reste mono-projet : `ContextRequest` porte un seul `projectId` (`core/src/main/java/com/nexus/context/ContextRequest.java:11`).

## References

- [ARCH-0005](ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.md) — local-first, intégrations externes opt-in.
- [DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md) — SQLite canonique, Lucene dérivé et reconstructible.
- [DATA-0074](DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md) — cohérence des lectures et verrous des index dérivés.
- [BE-0071](BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md) — recherche de candidats et ranking explicable.
- [BE-0072](BE-0072-construire-le-contextbundle-sous-budget-de-tokens.md) — `ContextBundle` sous budget de tokens.
- [ARCH-0085](ARCH-0085-stabiliser-le-contrat-de-sortie-publique-cli-rest-mcp.md) — projection publique unique.
- `openspec/changes/archive/2026-11-07-sprint-06-federation-et-runtimes-longue-duree/design.md` (D3) ; `core/src/test/java/com/nexus/benchmark/FederatedContextBudgetScaleBenchmarkTest.java`.
