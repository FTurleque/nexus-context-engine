---
id: BE-0065
title: "Dimensionner les caches Git des runtimes longue durée sur le scope fédéré"
domain: backend
rules: false
---

# Dimensionner les caches Git des runtimes longue durée sur le scope fédéré

- **Statut** : Proposed
- **Date** : 2026-11-07
- **Origine** : sprint 6 de l'audit de code du 17/10/2026, constats `AUD-PRF-06` et `AUD-PRF-07`, changement OpenSpec `sprint-06-federation-et-runtimes-longue-duree`
- **Complète** : [BE-0046](BE-0046-mettre-en-cache-le-contexte-git-dans-les-runtimes-longue-duree.md)

## Context

BE-0046 fixe la capacité du cache de contexte Git à 16 résultats, « identique au prototype qualifié par #163 », et classe parmi ses réexamens un churn mesurable. Une requête de contexte fédérée porte sur jusqu'à `FederatedScopePolicy.MAX_PROJECTS` = 100 projets, chacun avec sa propre entrée : au-delà de 16 projets, un parcours cyclique évince chaque entrée avant sa réutilisation et ne produit aucun hit. Les compteurs `CacheStats` existent mais ne sont lus que par les tests.

Séparément, `GitRecencyCandidateEnricher` ouvre le dépôt, lit `MAX_COMMITS` = 50 commits et autant de diffs d'arbres à chaque recherche, alors que ces chemins modifiés ne dépendent que du dépôt et de son HEAD. Son filtre par chemins candidats empêche de réutiliser le résultat d'une requête à l'autre.

La question est : **comment faire servir une requête fédérée répétée par les caches Git, sans nouvelle persistance et sans contexte périmé ?**

## Options envisagées

- **A — Conserver 16 entrées** : aucun changement, aucun hit au-delà de 16 projets.
- **B — Capacité configurable** : ajoute une constante opérationnelle sans lien avec la politique de scope.
- **C — Capacité alignée sur `FederatedScopePolicy.MAX_PROJECTS`**, cache de récence par (dépôt, HEAD) sans filtre de candidats : le seul scope maximal du produit dimensionne les deux caches.

## Decision

**Option retenue : C.**

- La capacité du cache de contexte Git passe de 16 à `FederatedScopePolicy.MAX_PROJECTS` (100). Éviction LRU, clé (racine projet, requête, chemins cibles, `explain`) et validation du fingerprint par HEAD, status et diffs restent ceux de BE-0046.
- Borne mémoire : le nombre d'entrées est borné par la capacité, et chaque entrée ne porte que le résultat déjà borné du provider local (50 commits, 6000 caractères par zone de patch, `ContextDiscoveryBudget`).
- Les compteurs `CacheStats` (hits, misses, évictions, entrées) et la capacité sont exposés sous la clé `gitContextCache` des métadonnées `explain`, pour le contexte mono-projet et fédéré ; ils sont absents hors `explain`.
- `GitRecencyCandidateEnricher` mémorise, par (répertoire Git, HEAD), les chemins modifiés des 50 derniers commits dans les bornes existantes de 1 024 chemins par commit et 4 096 cumulés, puis applique le filtre de candidats. Un nouveau HEAD remplace l'entrée du dépôt ; la capacité est `MAX_PROJECTS`.
- Au-delà des bornes, un marqueur « trop large » est mémorisé et le parcours filtré historique est conservé, avec le même résultat.
- Les caches restent en mémoire : ni fichier, ni watcher, ni thread.

## Do's and Don'ts

### Do

- Prendre la capacité des caches Git dans `FederatedScopePolicy.MAX_PROJECTS`.
- Exposer les compteurs du cache de contexte Git sous `gitContextCache` en mode `explain`.
- Inclure HEAD dans la clé du cache de récence et en invalider l'entrée du dépôt au changement de HEAD.
- Garder le parcours filtré historique quand l'historique dépasse les bornes de chemins.

### Don't

- Ne pas ajouter de capacité configurable, de fichier de cache, de watcher ni de thread de maintenance.
- Ne pas mettre en cache un résultat qui dépasse les bornes de chemins modifiés.

## Consequences

### Positives

- une requête de contexte fédérée répétée sur 100 projets inchangés est servie par le cache de contexte Git ;
- la récence n'ouvre plus le journal des commits tant que HEAD ne change pas ;
- le taux de hit est observable dans `explain`.

### Négatives

- la mémoire maximale du cache de contexte Git est jusqu'à 6,25 fois celle de BE-0046 ;
- chaque hit paie toujours le fingerprint (status ciblé et deux diffs digérés) ;
- la première recherche d'un dépôt parcourt le journal sans filtre de candidats, dans les bornes de chemins.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Récence périmée après un commit | Moyen | HEAD dans la clé, entrée du dépôt remplacée |
| Mémoire des caches | Moyen | capacités fixes à 100, entrées déjà bornées |
| Contexte périmé après modification du working tree | Élevé | fingerprint recalculé avant chaque hit (BE-0046) |

## Compliance and Enforcement

- Tests JUnit (`core`) : `PersistentGitContextSourceProviderTest` (deux passages cycliques sur 20 projets, hits au second passage, compteurs dans `explain`), `PersistentGitContextSourceProviderBudgetTest`, `GitRecencyCandidateEnricherTest` (second appel sans parcours du journal, nouveau commit pris en compte, historique trop large, éviction).
- Qualification : `git-context-cache-qualification.yml` sur un scope de plus de 16 projets.
- Revue : aucune capacité de cache Git n'est définie hors `FederatedScopePolicy.MAX_PROJECTS`.

## Conditions de réexamen

La décision est réévaluée si la mémoire mesurée des caches à 100 entrées pose problème en production, ou si la qualification montre que la validation du fingerprint coûte plus que le recalcul. Une modification substantielle donne lieu à un nouvel ADR qui remplace celui-ci.

## Mise en œuvre

Sprint 6, tâches 5.2, 5.3 et 6.1 : `PersistentGitContextSourceProvider.DEFAULT_CAPACITY`, métadonnée `gitContextCache`, cache de récence de `GitRecencyCandidateEnricher`.

## Décisions liées

- [BE-0046](BE-0046-mettre-en-cache-le-contexte-git-dans-les-runtimes-longue-duree.md) — cache de contexte Git des runtimes longue durée.
- [BE-0043](BE-0043-federer-la-recherche-locale-par-projet-avant-un-moteur-externe.md) — fédération locale.

## References

- `openspec/changes/archive/2026-11-07-sprint-06-federation-et-runtimes-longue-duree/design.md` (D5 et D6).
- `core/src/main/java/com/nexus/project/FederatedScopePolicy.java`.
