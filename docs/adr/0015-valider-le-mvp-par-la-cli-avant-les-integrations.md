---
status: accepted
date: 2026-07-19
---

# ADR-0015 — Valider le MVP par la CLI avant l'API, MCP et les intégrations IDE

## Contexte et problème

NEXUS doit à terme être utilisable depuis plusieurs environnements : API REST, MCP, GitHub Copilot, Claude, IDE et orchestrateurs personnalisés. Développer ces intégrations en parallèle du moteur ferait cependant courir le risque de valider les interfaces avant d'avoir démontré la qualité réelle de la sélection de contexte.

Le MVP doit pouvoir être testé de bout en bout avec un minimum de couches techniques afin de mesurer l'indexation, la recherche, le ranking, le budget et l'explicabilité.

La question est : **quelle surface d'utilisation doit servir de première validation complète du moteur avant d'investir dans des intégrations externes ?**

## Facteurs de décision

- faible complexité d'intégration ;
- accès direct aux capacités du moteur ;
- facilité d'automatisation des tests de bout en bout ;
- possibilité d'utiliser NEXUS hors ligne ;
- absence de dépendance à un protocole ou fournisseur ;
- capacité à inspecter les sorties humaines et JSON ;
- réduction du risque de développer des adaptateurs autour d'un moteur non validé.

## Options envisagées

- commencer par une API REST ;
- commencer par un serveur MCP ;
- commencer par un plugin IDE ;
- commencer par une CLI locale couvrant le flux complet.

## Décision retenue

**Option retenue : valider le MVP par une CLI locale avant d'implémenter l'API REST, MCP ou les intégrations IDE.**

La CLI cible doit permettre au minimum :

```text
nexus project add
nexus project list
nexus index
nexus search
nexus context
nexus inspect
```

Elle doit également supporter :

- `--budget` ;
- `--explain` ;
- une sortie lisible par un humain ;
- une sortie JSON adaptée aux tests et scripts.

La CLI reste un adaptateur mince. Elle ne contient ni logique de recherche, ni ranking, ni politique de budget.

Le critère de validation du MVP est le suivant : à partir d'un repository Java local et d'une demande textuelle, NEXUS identifie et classe les fichiers et symboles pertinents puis construit un `ContextBundle` respectant le budget.

### Conséquences positives

- le moteur peut être validé avec peu de dépendances ;
- les scénarios de bout en bout sont faciles à reproduire ;
- la CLI fournit un outil de diagnostic utile pendant le développement ;
- les adaptateurs futurs réutiliseront des services déjà validés ;
- le fonctionnement local-first est démontré avant toute intégration distante.

### Conséquences négatives et compromis acceptés

- l'intégration directe avec Copilot ou Claude arrive plus tard ;
- la CLI n'est pas l'expérience finale la plus ergonomique pour tous les utilisateurs ;
- une couche de parsing de commandes doit tout de même être maintenue.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Logique métier introduite dans les commandes | Élevé | Les commandes délèguent aux services applicatifs |
| CLI conçue comme API interne de fait | Moyen | Conserver des services indépendants des arguments CLI |
| Retard des intégrations externes | Faible | La roadmap prévoit API puis MCP après validation du moteur |
| Sortie humaine difficile à tester | Faible | Fournir une sortie JSON stable en complément |

### Confirmation

La décision est respectée si :

- l'ensemble du MVP est démontrable depuis la CLI ;
- les mêmes services peuvent être appelés ultérieurement par REST ou MCP ;
- les tests de bout en bout peuvent exécuter les scénarios principaux sans LLM ;
- l'API ou le MCP ne sont pas nécessaires pour valider la qualité du ranking.

## Analyse détaillée des options

### Commencer par une API REST

**Avantages :**

- intégration simple avec de nombreux clients ;
- contrat réseau disponible tôt.

**Inconvénients :**

- nécessite framework, serveur et DTO avant validation du moteur ;
- ajoute une couche de débogage inutile pour les premiers tests.

### Commencer par MCP

**Avantages :**

- intégration directe avec des agents compatibles ;
- démonstration intéressante de la vision produit.

**Inconvénients :**

- risque de concentrer l'effort sur le protocole plutôt que la qualité du contexte ;
- nécessite un client MCP pour certains tests ;
- surface d'outils à stabiliser trop tôt.

### Commencer par un plugin IDE

**Avantages :**

- expérience utilisateur immédiate ;
- accès naturel au projet local.

**Inconvénients :**

- développement spécifique à un IDE ;
- cycle de packaging et compatibilité supplémentaires ;
- mauvais support pour les tests automatisés du moteur.

### Commencer par une CLI locale

**Avantages :**

- simple ;
- scriptable ;
- indépendante des fournisseurs ;
- adaptée aux tests ;
- compatible avec le mode hors ligne.

**Inconvénients :**

- expérience moins intégrée ;
- nécessite une intégration supplémentaire pour l'usage quotidien dans les assistants.

## Impacts sur l'architecture

```text
CLI
 │
 ▼
Application Services
 │
 ├── Project Registry
 ├── Indexing
 ├── Search
 └── ContextBuilder

Futurs adaptateurs
REST / MCP / IDE
        │
        └── réutilisent les mêmes services
```

## Conditions de réexamen

Cette décision est principalement liée à l'ordre d'implémentation. Une fois le MVP validé, API et MCP peuvent devenir des surfaces de premier plan sans invalider l'ADR historique.

## Décisions liées

- ADR-0003 — Conserver un cœur Java sans framework applicatif obligatoire.
- ADR-0004 — Démarrer avec un seul module Maven et extraire uniquement sur besoin réel.
- ADR-0013 — Construire un ContextBundle sous budget de tokens explicable.
- ADR-0016 — Utiliser le SDK Java MCP officiel pour l'adaptateur MCP.
