---
id: BE-0090
title: "Garder la recherche sémantique optionnelle et la qualifier en deux corpus, hors du chemin de release"
domain: backend
rules: false
---

# Garder la recherche sémantique optionnelle et la qualifier en deux corpus, hors du chemin de release

- **Statut** : Proposed
- **Date** : 2026-11-12
- **Origine** : changement OpenSpec `flux-sur-main-seule` (intégration sur la seule branche `main`, GEN-0089)
- **Remplace** : [BE-0084](archive/BE-0084-garder-la-recherche-semantique-optionnelle-et-qualifiee-hors-release.md)
- **Remplacé par** : —

## Context

La recherche sémantique par embeddings aide à retrouver un contenu dont le vocabulaire diffère de la requête (documentation, demandes métier, certains cas de code). Elle pose des questions de fournisseur, de confidentialité, de coût, de latence, de stockage vectoriel et de reproductibilité, alors que la recherche lexicale, symbolique et par graphe sert déjà les requêtes sans embeddings.

Son gain se mesure par `.github/workflows/semantic-search-qualification.yml`, qui lance `RealSemanticSearchBenchmarkTest` contre un runtime Ollama épinglé par SHA-256 et un modèle au manifeste verrouillé, puis applique les seuils `recallAt3 ≥ 0,25`, `hitAt3 ≥ 0,33`, `mrrAt3 ≥ 0,20` et une régression maximale de 0,10 face au ranking sans embeddings. Sur le dépôt entier (environ 620 000 tokens embarqués), un run dure 1 h à 2 h 05 selon le CPU alloué par GitHub. Un run de PR annulé par chaque nouveau push ne termine pas, et rien ne qualifie le code une fois fusionné si seule la PR déclenche la mesure. Le code est intégré sur la seule branche `main` ([GEN-0089](GEN-0089-integrer-et-publier-depuis-la-seule-branche-main.md)) : c'est un push sur `main` qui suit la fusion.

La question est double : **la recherche sémantique doit-elle être une dépendance du moteur ou une stratégie optionnelle adoptée sur preuve ? Et sur quel corpus, à quel moment, la qualification réelle s'exécute-t-elle pour produire une mesure qui termine, sans bloquer la release ?**

## Options envisagées

- **Place de la recherche sémantique** : embeddings obligatoires (complexité, dépendance à un modèle et à un stockage, fonctionnement hors ligne compromis) ; fournisseur externe systématique (code envoyé à l'extérieur, coût récurrent, réseau, verrouillage) ; base vectorielle dédiée immédiate (nouvelle infrastructure, doublon avec Lucene) ; **`SemanticSearchStrategy` optionnelle, adoptée sur gain mesuré** (retenu).
- **Qualification réelle** : corpus complet partout et exigé en release (jusqu'à 2 h 05 d'attente sur un débit qui varie du simple au double ; un run lent bloque la release sans rien dire du code) ; corpus réduit partout (moins de distracteurs, une régression à l'échelle du dépôt n'est jamais vue) ; **corpus réduit sur PR, corpus complet sur `main` après fusion, release non bloquée** (retenu).

## Decision

### Recherche sémantique optionnelle

La recherche sémantique est une stratégie optionnelle, désactivée par défaut, adoptée durablement seulement si elle démontre un gain mesurable. NEXUS ne dépend d'aucun embedding : `mvnw clean install` et le fonctionnement par défaut n'exigent aucun modèle, et le chemin lexical, symbolique et graphe reste complet sans embeddings.

```text
SearchStrategy
├── LexicalSearchStrategy    obligatoire
├── SymbolSearchStrategy     obligatoire
├── GraphSearchStrategy      progressif
└── SemanticSearchStrategy   optionnel ── embeddings locaux | fournisseur externe opt-in
```

`SemanticSearchStrategy` peut utiliser un modèle d'embeddings local, un fournisseur externe explicitement activé, les capacités vectorielles de Lucene, ou un autre stockage spécialisé si les mesures le justifient ; aucune base vectorielle dédiée n'est ajoutée sans ADR qui en établit le besoin. Aucune donnée n'est envoyée automatiquement à un fournisseur d'embeddings.

L'évaluation compare, avec et sans sémantique : précision et rappel, coût d'indexation, taille de stockage, latence, coût financier, volume de données envoyé à l'extérieur, gain sur le contexte final sous budget.

### Qualification réelle en deux corpus

- **Pull request** : le job exécute le corpus `pr`, sous-ensemble déclaré dans `RealSemanticBenchmarkCorpus`, qui contient chaque chemin jugé pertinent par les requêtes du benchmark. Sa borne est `timeout-minutes: 45`.
- **`main`** : un `push` sur `main` qui touche les chemins surveillés exécute le corpus `full`, borné à 180 minutes. Hors pull request, le groupe de concurrence est le SHA qualifié : un push ultérieur ne l'annule pas et un run en cours n'est jamais annulé ; seul un run en attente sur le même SHA peut être remplacé. Le rapport porte `snapshotCommit` égal à ce SHA.
- **Seuils** : les mêmes pour les deux corpus ; un seuil n'est jamais abaissé pour faire passer le corpus `pr`.
- **Release** : `release.yml` n'exige pas la qualification sémantique réelle. La preuve de qualité est le dernier run `full` réussi sur `main`, que le mainteneur consulte avant de poser un tag de release (`gh run list -w semantic-search-qualification.yml --branch main`).
- `workflow_dispatch` choisit le corpus (`full` par défaut) ; le `schedule` mensuel exécute aussi `full` sur `main`.

Cette décision ne fixe ni les seuils, ni le modèle, ni le runtime Ollama, qui relèvent du workflow et de [`semantic-search.md`](../../developer/semantic-search.md) (composition des tranches du corpus `pr`, budgets de temps, débits mesurés).

## Do's and Don'ts

### Do

- Garder la recherche sémantique optionnelle et désactivée par défaut.
- N'adopter durablement la recherche sémantique que sur un gain mesuré (précision, rappel, coûts, latence, données envoyées, gain sur le contexte final).
- Exiger un opt-in explicite pour un fournisseur d'embeddings externe.
- Exécuter le corpus `pr` sur pull request avec `timeout-minutes` au plus égal à 45.
- Exécuter le corpus `full` sur `main` après la fusion d'un changement des chemins surveillés, sans annulation par un run ultérieur.
- Appliquer les mêmes seuils aux deux corpus.
- Garder dans le corpus `pr` chaque chemin jugé pertinent par une requête du benchmark ; le test échoue sinon.

### Don't

- Ne pas faire dépendre le build ni le fonctionnement par défaut d'un embedding.
- Ne pas ajouter de base vectorielle dédiée sans ADR qui en établit le besoin.
- Ne pas ajouter la qualification sémantique réelle aux `needs` du job `publish` de `release.yml` sans un ADR qui remplace celui-ci.
- Ne pas abaisser un seuil pour faire passer le corpus `pr`.
- Ne pas annuler un run en cours hors pull request (`cancel-in-progress` limité aux pull requests).

## Consequences

### Positives

- Le build et le fonctionnement par défaut restent simples et locaux ; aucune donnée ne part vers un fournisseur sans activation explicite.
- L'adoption de la sémantique est guidée par des mesures ; elle s'ajoute sans modifier les contrats principaux.
- Une PR qui touche la recherche sémantique obtient un verdict en moins de 45 minutes ; le code fusionné dans `main` est qualifié sur le corpus complet, jusqu'au terme du run.
- La release ne dépend pas du débit CPU d'un runner pour une fonctionnalité optionnelle.

### Négatives

- Certaines requêtes sémantiques restent moins bien servies sans embeddings ; les résultats varient selon le modèle choisi.
- Le corpus `pr` a moins de distracteurs : une régression qui n'apparaît qu'à l'échelle du dépôt n'est vue qu'après fusion, sur `main`.
- Une release peut partir alors que le dernier run `full` de `main` a échoué ; le mainteneur le vérifie avant de poser le tag.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Adoption d'embeddings sans bénéfice réel | Moyen | Critère d'adoption fondé sur corpus et métriques |
| Fuite de code vers un fournisseur externe | Critique | Activation opt-in et documentation des données envoyées |
| Résultats non reproductibles après changement de modèle | Moyen | Configuration et identité du modèle versionnées |
| Régression masquée par le corpus réduit | Moyen | Run `full` sur `main` après fusion, mêmes seuils |
| Corpus `pr` au-delà de 45 min avec la croissance du dépôt | Faible | `throughput.json` à chaque run ; corpus ajusté avant d'augmenter la borne |
| Chemin pertinent sorti du corpus `pr` | Faible | Présence de chaque chemin pertinent vérifiée avant indexation |

## Compliance and Enforcement

- Test `NexusApplicationSemanticConfigurationTest` (`defaultCompositionKeepsSemanticSearchDisabled`, `core/src/test/java/com/nexus/application/`) : la composition par défaut laisse la recherche sémantique désactivée.
- Tests `SemanticSearchBenchmarkTest` (`compareLexicalBaselineWithOptionalSemanticSearch`), `SemanticSearchConfigurationBoundsTest` (`core/src/test/java/com/nexus/search/semantic/`), `OllamaEndpointResolverTest` et `OllamaEmbeddingProviderEndpointPolicyTest` (`core/src/test/java/com/nexus/search/semantic/ollama/`), lancés par `mvnw verify`.
- Workflow `.github/workflows/semantic-search-qualification.yml` : qualification réelle (`RealSemanticSearchBenchmarkTest`) selon les déclencheurs ci-dessus.
- Contrat `scripts/release/test-final-audit-contracts.sh` : déclencheurs `pull_request` et `push` sur `main`, bornes de 45 et 180 minutes, sélection du corpus, absence d'annulation hors PR, absence de la qualification dans les `needs` de `publish`, budgets documentés dans `docs/developer/ci-and-supply-chain.md`.
- `RealSemanticBenchmarkCorpus` refuse un corpus inconnu et un corpus `pr` qui ne contient pas un chemin pertinent ; `RealSemanticBenchmarkCorpusTest` vérifie à chaque build que le corpus `pr` du dépôt contient chaque chemin pertinent.
- Revue : aucune base vectorielle dédiée sans ADR ; un changement des requêtes du benchmark garde leurs chemins pertinents dans le corpus `pr`.

## Conditions de réexamen

La décision est réévaluée si le corpus de référence montre une faiblesse persistante des stratégies non sémantiques ; si un modèle local léger offre un bon rapport qualité/coût ; si un client demande explicitement la recherche sémantique ; si les capacités vectorielles du moteur local permettent une intégration simple ; si la recherche sémantique devient active par défaut ; si le corpus `full` termine de façon fiable sous 45 minutes ; ou si un run `full` sur `main` révèle une régression que le corpus `pr` n'a pas vue.

## Mise en œuvre

- Stratégie : `core/src/main/java/com/nexus/search/semantic/` (`SemanticSearchStrategy`, provider Ollama) ; composition par défaut sans sémantique dans `NexusApplication`.
- Qualification : `.github/workflows/semantic-search-qualification.yml` (`timeout-minutes`, groupe de concurrence, `SEMANTIC_CORPUS`, seuils) ; corpus et requêtes dans `core/src/test/java/com/nexus/search/semantic/RealSemanticBenchmarkCorpus.java`.
- Description technique courante (tranches du corpus `pr`, budgets de temps, débits, contrat runtime) : [`semantic-search.md`](../../developer/semantic-search.md) ; [`ci-and-supply-chain.md`](../../developer/ci-and-supply-chain.md).

## References

- [ARCH-0005](ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.md) — local-first, intégrations externes opt-in.
- [DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md) — Lucene, index de recherche local dérivé.
- [BE-0071](BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md) — ranking hybride, déterministe et explicable.
- [GEN-0089](GEN-0089-integrer-et-publier-depuis-la-seule-branche-main.md) — intégration et release depuis la seule branche `main`.
- `docs/project/audits/2026-10-17/findings.md` — constat `AUD-DEP-19`.
