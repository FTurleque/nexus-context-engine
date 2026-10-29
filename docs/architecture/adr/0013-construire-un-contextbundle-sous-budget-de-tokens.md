---
status: accepted
date: 2026-07-19
---

# ADR-0013 — Construire un ContextBundle sous budget de tokens explicable

## Contexte et problème

La finalité opérationnelle de NEXUS est de produire un contexte utile à un assistant ou à un agent IA. Les repositories et leurs sources associées peuvent représenter des volumes très supérieurs à la fenêtre de contexte utile ou au budget économique disponible.

Envoyer les fichiers complets classés par score jusqu'à épuisement du budget serait simple, mais inefficace : des symboles précis peuvent suffire, des extraits peuvent se chevaucher, plusieurs sources peuvent dupliquer la même information et certains éléments doivent être conservés ensemble pour rester compréhensibles.

La question est : **comment définir le contrat de sortie de NEXUS et garantir qu'il reste sous un budget configurable tout en conservant le contexte le plus utile et en expliquant les arbitrages ?**

## Facteurs de décision

- budget configurable ;
- indépendance vis-à-vis du tokenizer d'un fournisseur ;
- préférence pour les extraits ciblés lorsque suffisants ;
- déduplication et fusion des chevauchements ;
- possibilité de combiner plusieurs types de sources ;
- explicabilité des exclusions et troncatures ;
- traçabilité des stratégies ayant sélectionné chaque élément ;
- sortie exploitable par un humain et par une machine.

## Options envisagées

- retourner uniquement une liste ordonnée de fichiers ;
- retourner tous les candidats et laisser le client gérer le budget ;
- construire un `ContextBundle` canonique sous budget dans NEXUS ;
- déléguer la sélection finale à un LLM.

## Décision retenue

**Option retenue : NEXUS construit un `ContextBundle` canonique sous budget, à partir d'un `ContextRequest`, avec une estimation des tokens abstraite et des décisions explicables.**

Le `ContextRequest` contient au minimum :

- projet ;
- requête ;
- budget de tokens ;
- sources demandées ;
- contraintes ;
- indicateur `explain`.

Le `ContextBundle` contient au minimum :

- éléments sélectionnés ;
- score et raisons par élément ;
- estimation des tokens ;
- budget configuré ;
- éléments exclus significatifs et motifs ;
- stratégies ayant produit les éléments ;
- métadonnées de construction.

`TokenEstimator` est un port indépendant. L'implémentation locale par défaut peut utiliser une estimation déterministe ; des tokenizers spécifiques pourront être ajoutés plus tard.

Le pipeline doit :

1. classer les candidats ;
2. privilégier les extraits de symboles aux fichiers complets lorsque cela conserve le sens ;
3. préserver les relations indispensables à la compréhension ;
4. fusionner les extraits qui se chevauchent ;
5. éliminer les doublons inter-sources ;
6. appliquer éventuellement des sous-budgets par type de source ;
7. ne jamais dépasser le budget selon le `TokenEstimator` actif ;
8. enregistrer les exclusions et troncatures lorsque `explain=true`.

### Conséquences positives

- tous les clients bénéficient du même arbitrage de contexte ;
- le budget devient une responsabilité centrale du moteur ;
- les économies de contexte sont mesurables ;
- l'utilisateur peut comprendre pourquoi un élément a été sacrifié ;
- le bundle est indépendant du fournisseur LLM ;
- la sélection d'extraits réduit le gaspillage de tokens.

### Conséquences négatives et compromis acceptés

- l'estimation locale peut différer du tokenizer réel du modèle final ;
- la sélection optimale sous budget est un problème complexe ;
- les dépendances entre éléments peuvent rendre un simple tri glouton insuffisant ;
- l'explication des exclusions augmente la taille des métadonnées, même si elles ne sont pas nécessairement envoyées au LLM.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Dépassement du budget réel du modèle | Élevé | Marge configurable et tokenizers spécifiques optionnels |
| Extrait trop court pour comprendre le code | Élevé | Étendre autour du symbole selon règles structurelles et dépendances |
| Duplication inter-sources | Moyen | Empreintes, fusion d'extraits et normalisation |
| Sous-budget rigide qui exclut une source essentielle | Moyen | Sous-budgets configurables et possibilité de réallocation |
| Algorithme de sélection favorisant uniquement le score individuel | Moyen | Prendre en compte diversité, relations et couverture de la requête |

### Confirmation

La décision est respectée si :

- `ContextBuilder` ne produit jamais un bundle supérieur au budget estimé ;
- des tests couvrent les frontières exactes du budget ;
- les extraits chevauchants sont fusionnés ;
- les doublons sont supprimés ;
- `--explain` expose les exclusions et troncatures ;
- le remplacement du `TokenEstimator` ne modifie pas le contrat du builder ;
- le ratio de réduction du contexte est mesuré.

## Analyse détaillée des options

### Retourner uniquement une liste ordonnée de fichiers

**Avantages :**

- contrat simple ;
- faible responsabilité du moteur.

**Inconvénients :**

- fichiers souvent trop volumineux ;
- aucune gestion de budget ;
- logique dupliquée dans chaque client ;
- perte de la valeur de sélection fine par symbole.

### Retourner tous les candidats et laisser le client gérer le budget

**Avantages :**

- clients libres de leur stratégie ;
- NEXUS se limite à la recherche.

**Inconvénients :**

- incohérence entre clients ;
- duplication des algorithmes ;
- NEXUS ne résout plus le problème principal d'optimisation du contexte.

### Construire un ContextBundle sous budget dans NEXUS

**Avantages :**

- responsabilité centralisée ;
- résultats comparables ;
- interopérabilité ;
- métriques de réduction ;
- explicabilité.

**Inconvénients :**

- algorithme plus complexe ;
- nécessité d'abstraire l'estimation de tokens.

### Déléguer la sélection finale à un LLM

**Avantages :**

- compréhension potentiellement avancée des dépendances sémantiques.

**Inconvénients :**

- il faut déjà envoyer de nombreux candidats au LLM ;
- coût, latence et confidentialité ;
- non-déterminisme ;
- dépendance fournisseur.

## Impacts sur l'architecture

```text
ContextRequest
     │
     ▼
Candidate Retrieval
     │
     ▼
Explainable Ranking
     │
     ▼
Deduplication / Merge
     │
     ▼
TokenEstimator + Budget Policy
     │
     ▼
ContextBundle
```

Le `ContextBundle` devient le contrat canonique entre le moteur et ses adaptateurs.

## Conditions de réexamen

Réexaminer l'algorithme de sélection si :

- les métriques montrent une perte importante de rappel sous budget ;
- les modèles consommateurs introduisent des contraintes de contexte structurées ;
- les tokenizers spécifiques deviennent nécessaires pour garantir des limites strictes ;
- de nouvelles sources nécessitent une allocation de budget différente.

Le principe de construire le bundle dans NEXUS plutôt que dans chaque client reste à préserver.

## Décisions liées

- ADR-0001 — Positionner NEXUS comme moteur d'intelligence de contexte indépendant des modèles.
- ADR-0010 — Adopter un ranking hybride, déterministe et explicable.
- ADR-0011 — Normaliser les sources de contexte derrière des providers.
- ADR-0014 — Rendre la recherche sémantique et les embeddings optionnels.
