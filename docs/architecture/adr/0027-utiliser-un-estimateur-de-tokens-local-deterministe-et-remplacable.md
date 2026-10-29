---
status: accepted
date: 2026-07-19
---

# ADR-0027 — Utiliser un estimateur de tokens local, déterministe et remplaçable

## Contexte et problème

NEXUS doit construire un `ContextBundle` respectant un budget exprimé en tokens, tout en restant indépendant des modèles et fournisseurs. Les tokenizers réels diffèrent entre familles de modèles et peuvent nécessiter une bibliothèque ou un service spécifique.

Le MVP doit néanmoins disposer d'une estimation stable pour sélectionner et tronquer le contexte sans dépendance réseau ni SDK fournisseur.

## Facteurs de décision

- fonctionnement local et hors ligne ;
- déterminisme ;
- indépendance du fournisseur LLM ;
- coût d'exécution très faible ;
- comportement conservateur pour limiter le risque de dépassement ;
- possibilité de remplacer l'estimation par un tokenizer exact dans un adaptateur futur.

## Options envisagées

- utiliser le tokenizer d'un fournisseur unique ;
- appeler un LLM ou une API pour compter les tokens ;
- ne gérer que des budgets en caractères ;
- utiliser une heuristique locale derrière `TokenEstimator`.

## Décision retenue

**Option retenue : fournir une heuristique locale déterministe derrière le port `TokenEstimator`.**

L'implémentation par défaut du MVP estime les tokens à partir du nombre de points de code Unicode avec un ratio conservateur d'environ **3,5 caractères par token** :

```text
estimatedTokens = ceil(codePointCount / 3.5)
```

Cette estimation n'est pas présentée comme le comptage exact d'un modèle. Elle constitue une unité de budget locale, stable et suffisamment prudente pour le moteur générique.

Le contrat `TokenEstimator` reste indépendant de cette heuristique. Un adaptateur consommateur pourra fournir ultérieurement un tokenizer spécifique au modèle final.

### Conséquences positives

- aucune dépendance LLM ou réseau ;
- résultat reproductible ;
- coût négligeable ;
- tests de budget stables ;
- remplacement possible sans modifier `ContextBuilder`.

### Conséquences négatives et compromis acceptés

- l'estimation diffère du tokenizer réel ;
- certains langages ou contenus Unicode peuvent être sur- ou sous-estimés ;
- un client exigeant une limite stricte du modèle final devra utiliser un estimateur spécifique ou une marge de sécurité.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Sous-estimation par rapport au tokenizer final | Élevé | Ratio volontairement conservateur et possibilité d'injecter un tokenizer exact |
| Couplage du builder à l'heuristique | Élevé | Toute estimation passe par `TokenEstimator` |
| Changement d'heuristique rendant les tests instables | Moyen | Tests dédiés et identification de l'estimateur dans les métadonnées du bundle |

### Confirmation

La décision est respectée si :

- `ContextBuilder` ne calcule jamais directement un nombre de tokens ;
- l'implémentation par défaut fonctionne sans réseau ;
- un autre `TokenEstimator` peut être injecté ;
- le bundle expose l'identité ou la stratégie de l'estimateur utilisé.

## Analyse détaillée des options

### Tokenizer d'un fournisseur unique

**Avantages :** comptage exact pour le modèle ciblé.

**Inconvénients :** couplage fournisseur et résultat non générique.

### API distante

**Avantages :** possibilité de déléguer la complexité.

**Inconvénients :** coût, latence, réseau et confidentialité.

### Budget en caractères uniquement

**Avantages :** simplicité maximale.

**Inconvénients :** contrat moins naturel pour les consommateurs LLM et difficulté à comparer avec leurs fenêtres de contexte.

### Heuristique locale derrière `TokenEstimator`

**Avantages :** simple, déterministe, local et remplaçable.

**Inconvénients :** approximation assumée.

## Impacts sur l'architecture

```text
ContextBuilder
     │
     ▼
TokenEstimator
     │
     ├── HeuristicTokenEstimator  (MVP)
     └── ModelSpecificEstimator   (futur adaptateur)
```

## Conditions de réexamen

Réexaminer le ratio par défaut si des mesures sur plusieurs tokenizers montrent une sous-estimation fréquente. L'abstraction `TokenEstimator` doit rester inchangée.

## Décisions liées

- ADR-0013 — Construire un ContextBundle sous budget de tokens explicable.
- ADR-0017 — Découpler NEXUS des outils et orchestrateurs externes.
