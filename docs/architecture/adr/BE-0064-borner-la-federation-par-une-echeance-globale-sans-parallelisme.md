---
id: BE-0064
title: "Borner la fédération par une échéance globale vérifiée entre projets, sans parallélisme"
domain: backend
rules: false
---

# Borner la fédération par une échéance globale vérifiée entre projets, sans parallélisme

- **Statut** : Proposed
- **Date** : 2026-11-07
- **Origine** : sprint 6 de l'audit de code du 17/10/2026, constat `AUD-PRF-03`, changement OpenSpec `sprint-06-federation-et-runtimes-longue-duree`
- **Complète** : [BE-0043](BE-0043-federer-la-recherche-locale-par-projet-avant-un-moteur-externe.md)

## Context

`FederatedSearchService.search` et `FederatedContextService.build` parcourent le scope fédéré (jusqu'à `FederatedScopePolicy.MAX_PROJECTS` = 100) dans une boucle synchrone. BE-0043 exclut tout parallélisme tant qu'une mesure ne le justifie pas. La boucle n'a toutefois aucune échéance globale : un projet lent retarde toute la requête, et la réponse ne dit pas quels projets n'ont pas été interrogés.

Mesure disponible : `FederatedContextBudgetScaleBenchmarkTest` (100 projets, builders locaux simulés) mesure 90 ms de coût d'orchestration fédérée le 2026-11-07 (poste de développement Windows). Le coût de la boucle elle-même est donc négligeable ; la latence d'une requête fédérée réelle vient du travail par projet (recherche, Git, SQLite) et de l'embedding de la requête (timeout par appel de 120 s pour Ollama par défaut). Aucune mesure ne justifie un parallélisme.

La question est : **comment borner la latence d'une requête fédérée et rendre ses coupes explicables, sans paralléliser et sans changer l'ordre ni les scores des projets servis ?**

## Facteurs de décision

- ne pas introduire de parallélisme (BE-0043) ;
- déterminisme : l'ordre et les scores des projets servis ne dépendent jamais de l'échéance ;
- explicabilité : toute coupe produit un diagnostic public nommant les projets concernés ;
- parité CLI, REST et MCP par la projection publique unique (ARCH-0061) ;
- tests sans dépendance à l'horloge réelle ;
- borne définie avant l'allocation, par une politique réutilisable et non par une constante locale.

## Options envisagées

- **A — Exécuteur avec timeout par projet** : interrompt un projet lent, mais introduit des threads, de l'annulation et de l'ordonnancement, donc le parallélisme exclu par BE-0043 ; écarté.
- **B — Échéance globale vérifiée avant chaque projet** : aucun thread, comportement déterministe pour les projets servis ; un projet commencé n'est pas interrompu.
- **C — Statu quo sans échéance** : la latence reste non bornée et les coupes sont impossibles à expliquer ; écarté.

## Decision

**Option retenue : B — échéance globale vérifiée entre les projets, sans parallélisme.**

- Une requête fédérée, de recherche ou de contexte, démarre une échéance (`FederationDeadline`) après validation de la requête et du scope. L'échéance est vérifiée **avant** de traiter chaque projet, dans l'ordre déterministe du scope ; un projet jamais commencé après l'échéance n'est pas servi. Un projet commencé n'est jamais interrompu.
- La boucle reste synchrone et séquentielle ; aucun exécuteur ni thread n'est ajouté.
- L'horloge est injectée (`java.time.InstantSource`) pour que les tests simulent le temps sans dormir ni dépendre de l'horloge réelle.
- **Valeur par défaut : 60 secondes**, modifiable par `NEXUS_FEDERATION_DEADLINE_SECONDS`, **bornée à l'intervalle 1 à 600 secondes** ; une valeur hors intervalle ou non entière est refusée au démarrage avec un message en français. C'est un plafond large devant le coût d'orchestration mesuré (90 ms pour 100 projets), recalibré par la qualification sémantique si le coût par projet l'exige.
- Le temps restant plafonne les appels bornables : l'appel d'embedding de la requête utilise `min(timeout du provider, temps restant)`, avec un minimum de 1 ms. C'est le seul appel long bornable de la boucle. Le vecteur de requête est calculé au premier projet qui en a besoin et partagé ensuite (`SearchQueryContext`), de sorte que son calcul est soumis à l'échéance de la requête.
- Les projets non commencés à l'échéance sont listés, dans l'ordre du scope, dans un diagnostic public `unservedProjects` de la réponse (identifiant et nom de chaque projet), produit par la projection publique unique et donc identique en CLI, REST et MCP. Une réponse sans coupe porte une liste vide et des résultats identiques à ceux d'une exécution sans échéance.
- Les projets servis ne changent ni d'ordre ni de score : l'échéance ne décide que de leur nombre. Hors décision : parallélisme, échéance par projet ou mono-projet.

## Do's and Don'ts

### Do

- Vérifier l'échéance globale avant chaque projet, dans l'ordre déterministe du scope.
- Injecter l'horloge (`InstantSource`) dans la politique d'échéance.
- Plafonner le délai d'embedding de la requête par le temps restant.
- Nommer chaque projet non servi, dans l'ordre du scope, dans le diagnostic public `unservedProjects`.
- Lire la valeur par défaut (60 s) et l'intervalle (1 à 600 s) dans `FederationDeadlinePolicy`.

### Don't

- Ne pas paralléliser la boucle fédérée ni introduire d'exécuteur pour appliquer l'échéance.
- Ne pas interrompre un projet commencé.
- Ne pas modifier l'ordre ni le score des projets servis en fonction de l'échéance.
- Ne pas asserter la durée réelle ni dormir dans un test d'échéance.

## Consequences

### Positives

- la latence d'une requête fédérée est bornée à l'échéance plus le coût du dernier projet commencé ;
- les projets non servis sont nommés, jamais abandonnés en silence ;
- aucun parallélisme, aucun thread, comportement déterministe pour les projets servis ;
- le même diagnostic sort par CLI, REST et MCP.

### Négatives

- une réponse peut être partielle et dépend du temps pour le nombre de projets servis ;
- un projet commencé juste avant l'échéance peut la dépasser ; seul l'embedding est plafonné.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Réponse partielle prise pour une réponse complète | Moyen | diagnostic public `unservedProjects` rendu par toutes les surfaces |
| Défaut de 60 s trop court sur un scope lent | Moyen | variable `NEXUS_FEDERATION_DEADLINE_SECONDS`, intervalle borné, recalibrage par qualification |
| Tests dépendants du temps | Moyen | horloge injectée, aucun test n'asserte la durée réelle |

## Compliance and Enforcement

- Tests JUnit (`core`) à horloge simulée : `FederatedContextServiceTest` et `FederatedSearchServicePolicyTest` (échéance atteinte en cours de scope, échéance non atteinte, ordre du diagnostic, projets servis inchangés), `FederationDeadlinePolicyTest` (valeur par défaut, bornes, variable d'environnement).
- Tests de parité CLI, REST et MCP : le diagnostic `unservedProjects` est une composante des records de `PublicOutput`, donc couvert par `PublicOutputSchema` et par les tests de parité des adaptateurs.
- Revue : aucune boucle fédérée ne doit s'exécuter dans un exécuteur ou un flux parallèle.

## Conditions de réexamen

La décision est réévaluée si une mesure qualifiée montre qu'un parallélisme borné réduit la latence fédérée sans dégrader le déterminisme, ou si le coût par projet réel rend le défaut de 60 s inadapté. Une modification substantielle donne lieu à un nouvel ADR qui remplace celui-ci.

## Mise en œuvre

Sprint 6, tâches 3.2 et 3.3 : `FederationDeadline` et `FederationDeadlinePolicy` (`com.nexus.project`), vérification dans `FederatedSearchService` et dans l'étape « collecte par projet » de `FederatedContextService`, `EmbeddingProvider.embedQuery(String, Duration)`, composante `unservedProjects` de `PublicOutput.FederatedSearch` et `PublicOutput.FederatedContext`.

## Décisions liées

- [BE-0043](BE-0043-federer-la-recherche-locale-par-projet-avant-un-moteur-externe.md) — fédération locale par projet, sans parallélisme avant mesure.
- [ARCH-0061](ARCH-0061-decider-le-contrat-de-sortie-publique-dans-la-projection-du-coeur.md) — projection publique unique.

## References

- `openspec/changes/archive/2026-11-07-sprint-06-federation-et-runtimes-longue-duree/design.md` (D3).
- `core/src/test/java/com/nexus/benchmark/FederatedContextBudgetScaleBenchmarkTest.java`.
