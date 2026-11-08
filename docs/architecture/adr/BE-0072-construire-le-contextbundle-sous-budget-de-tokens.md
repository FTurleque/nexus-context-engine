---
id: BE-0072
title: "Construire le ContextBundle sous budget de tokens : estimateur local, fragments symboliques, sélection gloutonne déterministe"
domain: backend
rules: true
---

# Construire le ContextBundle sous budget de tokens : estimateur local, fragments symboliques, sélection gloutonne déterministe

- **Statut** : Accepted
- **Date** : 2026-11-08
- **Origine** : consolidation du registre ADR (audit du 2026-11-08)
- **Remplace** : [BE-0013](archive/BE-0013-construire-un-contextbundle-sous-budget-de-tokens.md), [BE-0027](archive/BE-0027-utiliser-un-estimateur-de-tokens-local-deterministe-et-remplacable.md), [BE-0028](archive/BE-0028-construire-le-contexte-a-partir-de-fragments-de-code-prioritairement-symboliques.md), [BE-0029](archive/BE-0029-selectionner-le-contextbundle-par-un-algorithme-glouton-deterministe-sous-budget.md)
- **Remplacé par** : —

## Context

La finalité de NEXUS est de fournir à un assistant ou à un agent IA un contexte utile qui tienne dans une enveloppe d'entrée bornée du modèle de langage. Un projet et ses sources associées dépassent de loin la fenêtre de contexte utile ou le budget économique disponible : il faut ne retenir que les fragments les plus utiles qui tiennent dans un budget de tokens borné, et expliquer ce qui a été sacrifié.

Envoyer les fichiers complets par ordre de score jusqu'à épuisement du budget gaspille des tokens : un symbole précis peut suffire, des extraits se chevauchent, des sources se dupliquent. NEXUS connaît les plages de lignes des symboles Java ; les résultats fichier restent utiles sans symbole précis ou pour un petit fichier.

Les tokenizers réels diffèrent entre familles de modèles et dépendent du fournisseur : NEXUS doit estimer localement, hors ligne et de façon stable. Choisir les fragments sous capacité est une variante du sac à dos : un solveur global ou un LLM serait disproportionné, une accumulation naïve instable.

La question est : **comment construire dans NEXUS un contrat de sortie qui retient les fragments les plus utiles sous un budget de tokens borné et configurable, de façon déterministe, indépendante du fournisseur et explicable ?**

## Options envisagées

- **Contrat** : liste de fichiers (pas de budget) ; tous les candidats, budget géré par le client (incohérence entre clients) ; sélection par un LLM (coût, confidentialité, non-déterminisme) ; **`ContextBundle` canonique sous budget construit dans NEXUS** (retenu).
- **Estimation** : tokenizer d'un fournisseur (couplage) ; API distante (réseau, confidentialité) ; budget en caractères (peu naturel pour un consommateur LLM) ; **heuristique locale derrière `TokenEstimator`** (retenu).
- **Extraits** : fichiers complets (gaspillage) ; fenêtres lexicales seules (ignorent les bornes structurelles) ; résumé par LLM (perte d'information) ; **symboles d'abord, fichiers en repli** (retenu).
- **Sélection** : arrêt au premier dépassement (ni fusion ni troncature utile) ; optimisation globale (prématurée) ; LLM (envoyer beaucoup de contexte pour choisir le contexte) ; **glouton déterministe après fusion et déduplication** (retenu).

## Decision

**Option retenue : NEXUS construit un `ContextBundle` canonique à partir d'un `ContextRequest`, en retenant les fragments les plus utiles — symboliques d'abord — par une sélection gloutonne déterministe qui ne dépasse jamais le budget de tokens estimé par un `TokenEstimator` local et remplaçable, et en expliquant ses arbitrages.**

### Contrat

Le `ContextRequest` porte au minimum : projet, requête, budget de tokens, sources demandées, contraintes et indicateur `explain`.

Le `ContextBundle` porte au minimum : éléments sélectionnés, score et raisons par élément, estimation des tokens, budget configuré, éléments exclus significatifs et motifs, stratégies ayant produit les éléments et métadonnées de construction. Il est le contrat canonique entre le moteur et ses adaptateurs : tous les clients bénéficient du même arbitrage.

### Estimation des tokens

`TokenEstimator` est un port indépendant de toute heuristique. L'implémentation par défaut est locale, déterministe et sans réseau ; elle compte les points de code Unicode avec un ratio conservateur d'environ 3,5 caractères par token :

```text
estimatedTokens = ceil(codePointCount / 3.5)
```

Cette estimation est une unité de budget locale et prudente, pas le comptage exact d'un modèle. Le pipeline de contexte ne calcule jamais directement un nombre de tokens : toute estimation passe par `TokenEstimator`, qu'un adaptateur consommateur peut remplacer par un tokenizer propre au modèle final sans changer le contrat du builder. Le bundle expose l'identité de l'estimateur utilisé.

### Fragments prioritairement symboliques

Les fragments sont matérialisés d'abord à partir des symboles classés : lignes `startLine..endLine` issues de l'AST, plus un petit contexte de lignes autour. Les candidats fichier ou test sans symbole suffisamment précis donnent des fragments de fichier : contenu complet pour un fichier court, fenêtres autour des termes de la requête pour un fichier long.

Les plages chevauchantes ou adjacentes d'un même fichier sont fusionnées avant estimation du budget. Un fichier qui a déjà des fragments symboliques pertinents n'est pas ajouté en entier, sauf s'il est assez petit pour que l'inclusion intégrale reste plus simple et sous les limites configurées.

Chaque fragment conserve : chemin, type de candidat, symbole éventuel, lignes de début et de fin, score, raisons de sélection, estimation de tokens et indication de troncature.

### Sélection gloutonne sous budget

```text
fragments matérialisés
  → normalisation des chemins et intervalles
  → fusion des chevauchements et adjacences
  → déduplication (y compris inter-sources)
  → tri déterministe par score, puis chemin, puis ligne
  → sélection gloutonne : fragment complet s'il tient,
    fragment tronqué s'il reste un budget utile, exclusion expliquée sinon
  → ContextBundle
```

La somme des `estimatedTokens` des `ContextItem` ne dépasse jamais `tokenBudget` selon le `TokenEstimator` actif. Un fragment n'est tronqué que si une portion significative peut encore être incluse ; la troncature est explicite dans le contenu et dans les métadonnées de l'item. Une exclusion conserve un motif lisible ; exclusions et troncatures sont enregistrées lorsque `explain=true`. Le pipeline préserve les relations indispensables à la compréhension et peut appliquer des sous-budgets par type de source.

Le bundle expose les métriques de sélection : candidats classés, fragments matérialisés, items sélectionnés, exclusions, troncatures, tokens estimés avant sélection, tokens sélectionnés et ratio de réduction.

### Hors décision

- Le classement des candidats relève de [BE-0071](BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md).
- Les bornes propres aux sources natives (instructions, skills, Git) relèvent de [ARCH-0078](ARCH-0078-normaliser-les-sources-de-contexte-natives-derriere-des-providers.md), [BE-0079](BE-0079-charger-les-agent-skills-par-divulgation-progressive.md) et [BE-0070](BE-0070-integrer-le-contexte-git-local-borne-mis-en-cache-et-confine.md).
- Un tokenizer propre à un fournisseur relève d'un adaptateur consommateur, pas du cœur.

## Do's and Don'ts

### Do

- Construire dans NEXUS un `ContextBundle` canonique sous budget à partir d'un `ContextRequest`, avec au minimum les champs listés au contrat.
- Estimer par le port `TokenEstimator` (défaut local : `ceil(codePointCount / 3.5)`), indépendant de l'heuristique, et exposer l'identité de l'estimateur.
- Matérialiser d'abord les fragments symboliques (lignes AST et petit contexte), puis des fragments de fichier en repli.
- Fusionner les plages chevauchantes ou adjacentes d'un même fichier avant estimation, et éliminer les doublons inter-sources.
- Sélectionner par un algorithme glouton déterministe, trié par score, puis chemin, puis ligne.
- Marquer explicitement toute troncature dans le contenu et les métadonnées de l'item, donner un motif lisible à chaque exclusion et les enregistrer lorsque `explain=true`.
- Exposer dans le bundle les métriques de sélection et le ratio de réduction.

### Don't

- Ne jamais laisser la somme des `estimatedTokens` des `ContextItem` dépasser `tokenBudget` selon le `TokenEstimator` actif.
- Ne pas calculer un nombre de tokens dans le pipeline de contexte hors de `TokenEstimator`, ni y nommer l'heuristique par défaut.
- Ne pas présenter l'estimation comme le comptage exact d'un modèle.
- Ne pas ajouter le contenu complet d'un fichier qui a déjà des fragments symboliques pertinents, sauf fichier assez petit pour rester sous les limites configurées.
- Ne pas tronquer un fragment si aucune portion significative ne peut être incluse.
- Ne pas déléguer la sélection, le résumé ou le comptage des tokens à un LLM ou à un service distant.

## Consequences

### Positives

- Le budget est une responsabilité centrale du moteur ; le bundle est indépendant du fournisseur et les économies sont mesurables.
- Les extraits symboliques augmentent la densité d'information par token.
- Le comportement est reproductible et auditable : budget strict, arbitrages expliqués, coût quasi linéaire après tri.

### Négatives

- L'estimation locale diffère du tokenizer réel ; certains contenus Unicode sont sur- ou sous-estimés ; un client exigeant une limite stricte du modèle final doit fournir un estimateur spécifique ou garder une marge.
- Le glouton n'est pas optimal globalement : un fragment moins bien classé mais plus compact peut parfois apporter plus ; la diversité des types de contexte n'est pas modélisée explicitement.
- Un symbole isolé peut manquer de contexte global ; les fenêtres lexicales de repli sont moins précises ; les fichiers non Java n'ont pas la même qualité de découpage.
- L'explication des exclusions augmente la taille des métadonnées.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Dépassement du budget réel du modèle | Élevé | Ratio conservateur, estimateur spécifique injectable, marge côté client |
| Couplage du builder à l'heuristique | Élevé | Toute estimation passe par `TokenEstimator` (règle Archgate) |
| Extrait trop étroit pour comprendre le code | Élevé | Lignes de contexte autour des bornes AST |
| Duplication fichier + symbole ou inter-sources | Élevé | Regroupement par chemin, fusion des plages, déduplication |
| Instabilité à score égal | Élevé | Tri secondaire par chemin puis ligne |
| Gros premier fragment consommant le budget | Moyen | Fragments symboliques privilégiés et plafond par fragment avant sélection |
| Troncature trop agressive | Moyen | Minimum de contenu utile, troncature marquée |
| Déduplication perdant une raison importante | Moyen | Fusion des raisons, score maximal conservé |
| Ratio de réduction trompeur | Moyen | Calculé sur les fragments matérialisés uniques avant sélection |

## Compliance and Enforcement

- Tests `DefaultContextBuilderIntegrationTest` (`buildsDeterministicSymbolFirstContextWithinBudget`, `truncatesLargeRelevantFragmentWithoutExceedingBudget`), `FragmentMergerTest`, `ContextFragmentFactoryRangeValidationTest`, `ContextFragmentFactoryPathBoundaryTest`, `BudgetedContextSelectorUnicodeTest`, `BudgetedContextSelectorRedactionTest`, `ContextBudgetPolicyTest`, `ContextMaterializationBudgetTest` (`core/src/test/java/com/nexus/context/`) et `HeuristicTokenEstimatorTest` (`estimatesDeterministicallyAndHandlesUnicodeCodePoints`, `core/src/test/java/com/nexus/token/`), lancés par `mvnw verify`.
- Invariant à la construction : `ContextBundle` refuse un `estimatedTokens` hors de `[0, tokenBudget]`.
- Règle Archgate `BE-0072-construire-le-contextbundle-sous-budget-de-tokens.rules.ts` / `token-estimation-behind-port` : `HeuristicTokenEstimator` implémente `TokenEstimator` et n'ouvre aucun accès réseau ; `core/src/main/java/com/nexus/context/` ne nomme pas `HeuristicTokenEstimator` et ne code pas en dur le ratio `3.5` (commentaires et littéraux effacés).
- Revue : contenu minimal de `ContextRequest` et `ContextBundle` (aucun contrôle ne le vérifie champ par champ), choix des fragments et règle de troncature.

## Conditions de réexamen

La décision est réévaluée si :

- le corpus de référence montre que le glouton ou la forme des extraits réduit trop fortement le rappel sous budget, ou qu'un modèle de diversité ou de sous-budget par source apporte un gain mesurable ;
- des mesures sur plusieurs tokenizers montrent une sous-estimation fréquente du ratio par défaut (l'abstraction `TokenEstimator` restant inchangée), ou des tokenizers spécifiques deviennent nécessaires pour garantir des limites strictes ;
- SCIP ou JDT fournit des plages plus riches, ou d'autres langages exigent un découpage différent ;
- les modèles consommateurs introduisent des contraintes de contexte structurées.

Le principe de construire le bundle dans NEXUS plutôt que dans chaque client reste à préserver. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Mise en œuvre

- Contrat : `core/src/main/java/com/nexus/context/ContextRequest.java`, `ContextBundle.java`, `ContextBudgetPolicy.java` (bornes du budget).
- Estimation : `core/src/main/java/com/nexus/token/TokenEstimator.java`, `HeuristicTokenEstimator.java` (identité `heuristic-unicode-3.5-chars-per-token`), injecté par la composition root.
- Fragments : `core/src/main/java/com/nexus/context/ContextFragmentFactory.java`, `FragmentMerger.java`.
- Sélection : `core/src/main/java/com/nexus/context/BudgetedContextSelector.java` ; répartition par source dans `ContextBudgetAllocator.java` ; métriques dans `ContextBuildMetadata.java` ; orchestration dans `DefaultContextBuilder.java`.
- `ContextRequest` porte le champ `constraints` mais refuse toute map non vide : aucune contrainte n'est encore implémentée, et aucune n'est ignorée silencieusement.
- Détail technique (lignes de contexte, seuils, plafond par fragment) : [`docs/developer/context-building.md`](../../developer/context-building.md).

## References

- [BE-0071](BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md) — recherche et classement des candidats.
- [ARCH-0077](ARCH-0077-positionner-nexus-comme-moteur-de-contexte-independant.md) — NEXUS moteur de contexte indépendant des modèles et des outils.
- [ARCH-0078](ARCH-0078-normaliser-les-sources-de-contexte-natives-derriere-des-providers.md) — sources de contexte derrière des providers.
- [BE-0084](BE-0084-garder-la-recherche-semantique-optionnelle-et-qualifiee-hors-release.md) — recherche sémantique optionnelle.
