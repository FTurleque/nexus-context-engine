---
status: accepted
date: 2026-07-19
---

# ADR-0014 — Rendre la recherche sémantique et les embeddings optionnels

## Contexte et problème

La recherche sémantique par embeddings peut améliorer la découverte de contenus dont le vocabulaire diffère de la requête. Elle peut être utile pour la documentation, les demandes métier ou certains cas de recherche de code.

Cependant, imposer des embeddings au MVP introduirait immédiatement des questions de fournisseur, de confidentialité, de coût, de latence, de stockage vectoriel et de reproductibilité. NEXUS dispose déjà d'une stratégie de base combinant recherche lexicale, symboles et graphe, qui doit être évaluée avant d'ajouter une brique plus lourde.

La question est : **la recherche sémantique doit-elle être une dépendance obligatoire du moteur, ou une stratégie optionnelle adoptée uniquement si elle apporte un gain mesurable ?**

## Facteurs de décision

- fonctionnement local-first ;
- absence de fournisseur obligatoire ;
- confidentialité du code ;
- coût d'indexation et de requête ;
- latence ;
- reproductibilité ;
- qualité mesurable du contexte ;
- possibilité d'utiliser des embeddings locaux ou externes ;
- éviter une base vectorielle lourde sans besoin démontré.

## Options envisagées

- rendre les embeddings obligatoires dès le MVP ;
- utiliser systématiquement un fournisseur d'embeddings externe ;
- intégrer immédiatement une base vectorielle dédiée ;
- définir `SemanticSearchStrategy` comme extension optionnelle et mesurer son gain avant adoption durable.

## Décision retenue

**Option retenue : définir la recherche sémantique comme stratégie optionnelle, désactivée par défaut, et ne l'adopter durablement que si elle démontre un gain mesurable.**

Le MVP ne dépend d'aucun embedding.

Une future `SemanticSearchStrategy` pourra utiliser :

- un modèle d'embeddings local ;
- un fournisseur externe explicitement opt-in ;
- des capacités vectorielles de Lucene lorsque pertinentes ;
- un autre stockage spécialisé si les métriques justifient réellement cette complexité.

L'évaluation doit comparer au minimum :

- précision et rappel ;
- coût d'indexation ;
- taille de stockage ;
- latence ;
- coût financier éventuel ;
- volume de données envoyé à l'extérieur ;
- gain sur le contexte final sous budget.

### Conséquences positives

- le MVP reste simple et local ;
- aucune donnée n'est envoyée automatiquement à un fournisseur d'embeddings ;
- NEXUS évite d'introduire prématurément une base vectorielle ;
- la recherche sémantique peut être ajoutée sans modifier les contrats principaux ;
- son adoption sera guidée par des mesures plutôt que par une tendance technologique.

### Conséquences négatives et compromis acceptés

- certaines requêtes sémantiques pourraient être moins bien servies par le MVP ;
- un travail d'évaluation sera nécessaire plus tard ;
- plusieurs providers d'embeddings pourraient nécessiter des adaptateurs ;
- les résultats pourront varier selon le modèle d'embeddings choisi.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Adoption d'embeddings sans bénéfice réel | Moyen | Critère d'adoption basé sur corpus et métriques |
| Fuite de code vers un provider externe | Critique | Activation opt-in et documentation explicite des données envoyées |
| Coût de stockage vectoriel élevé | Moyen | Mesurer avant adoption ; utiliser Lucene si suffisant |
| Résultats non reproductibles après changement de modèle | Moyen | Versionner la configuration et l'identité du modèle d'embeddings |
| Dépendance implicite d'autres stratégies aux embeddings | Élevé | Maintenir un chemin lexical/symbolique/graphe complet sans embeddings |

### Confirmation

La décision est respectée si :

- `mvn clean install` et le MVP complet ne nécessitent aucun modèle d'embeddings ;
- `SemanticSearchStrategy` est optionnelle ;
- l'activation d'un provider externe est explicite ;
- un benchmark compare la stratégie avec et sans sémantique avant adoption recommandée ;
- aucune base vectorielle dédiée n'est ajoutée sans ADR justifiant son besoin.

## Analyse détaillée des options

### Rendre les embeddings obligatoires dès le MVP

**Avantages :**

- recherche sémantique disponible immédiatement ;
- meilleure tolérance potentielle aux différences de vocabulaire.

**Inconvénients :**

- complexité importante avant validation du moteur ;
- dépendance à un modèle et à un stockage ;
- coût et confidentialité ;
- difficile à faire fonctionner entièrement hors ligne.

### Utiliser systématiquement un fournisseur externe

**Avantages :**

- qualité potentiellement élevée sans gérer de modèle local ;
- intégration rapide.

**Inconvénients :**

- code potentiellement envoyé à l'extérieur ;
- coût récurrent ;
- disponibilité réseau ;
- verrouillage fournisseur.

### Intégrer immédiatement une base vectorielle dédiée

**Avantages :**

- fonctionnalités spécialisées ;
- montée en charge vectorielle.

**Inconvénients :**

- nouvelle infrastructure ;
- surdimensionnement du MVP ;
- duplication potentielle avec Lucene ;
- maintenance accrue.

### Rendre la stratégie optionnelle et mesurée

**Avantages :**

- préserve la simplicité ;
- permet l'expérimentation ;
- adoption fondée sur des preuves ;
- choix libre du provider.

**Inconvénients :**

- fonctionnalité différée ;
- nécessite une campagne de benchmark dédiée.

## Impacts sur l'architecture

```text
SearchStrategy
├── LexicalSearchStrategy       obligatoire
├── SymbolSearchStrategy        obligatoire
├── GraphSearchStrategy         progressif
└── SemanticSearchStrategy      optionnel
        │
        ├── local embeddings
        └── external provider opt-in
```

## Conditions de réexamen

Réexaminer lorsque :

- le corpus de référence montre une faiblesse persistante des stratégies non sémantiques ;
- un modèle local léger offre un bon rapport qualité/coût ;
- un client demande explicitement une recherche sémantique ;
- les capacités vectorielles du moteur local permettent une intégration simple.

## Décisions liées

- ADR-0005 — Adopter un fonctionnement local-first et des intégrations externes opt-in.
- ADR-0007 — Utiliser Apache Lucene comme index de recherche local.
- ADR-0010 — Adopter un ranking hybride, déterministe et explicable.
