---
status: proposed
date: 2026-07-22
---

# ADR-0044 — Construire un contexte fédéré sous un budget global avant d'arbitrer les sources projet-locales

## Contexte et problème

L'Itération 16 a validé la recherche fédérée locale sur plusieurs repositories sans remplacer Lucene. `FederatedSearchService` conserve la provenance de chaque résultat et produit un classement global déterministe, mais `DefaultContextBuilder` reste volontairement limité à un seul projet.

Un contexte multi-projet ne peut pas être obtenu correctement en concaténant plusieurs `ContextBundle` projet-locaux : chacun consommerait son propre budget et pourrait inclure ses instructions, Agent Skills et signaux Git sans arbitrage global. Le résultat dépasserait facilement le budget demandé et masquerait la provenance réelle des fragments.

La question est : **comment construire un premier contexte multi-projet utile, déterministe et mesurable sans inventer prématurément une politique pour les sources projet-locales ?**

## Facteurs de décision

- conserver un unique budget de tokens pour toute la requête ;
- préserver explicitement le projet d'origine de chaque item ;
- réutiliser le ranking fédéré validé à l'Itération 16 ;
- réutiliser la matérialisation et la sélection sous budget existantes ;
- ne pas dédupliquer deux fragments appartenant à deux repositories différents sur leur seul chemin relatif ;
- éviter une répartition arbitraire du budget tant qu'aucune mesure ne montre un problème de domination d'un projet ;
- ne pas mélanger implicitement les instructions natives, Agent Skills locaux et signaux Git de plusieurs racines ;
- conserver le fonctionnement mono-projet inchangé.

## Options envisagées

- construire un `ContextBundle` complet par projet puis concaténer les résultats ;
- répartir le budget à parts égales entre tous les projets ;
- imposer immédiatement un quota minimal ou maximal par projet ;
- sélectionner globalement les fragments de tâche selon le ranking fédéré, tout en différant les sources projet-locales.

## Décision retenue

**Option retenue : sélectionner globalement les fragments de tâche issus de la recherche fédérée sous un budget unique, avec provenance explicite, sans quota statique par projet et sans sources projet-locales dans ce premier incrément.**

Le flux est :

```text
projets explicites
      │
      ▼
FederatedSearchService
      │
      ▼
FederatedSearchHit + ProjectDescriptor
      │
      ├── matérialisation des fragments par projet
      └── fusion des fragments par fichier et par projet
      │
      ▼
portée interne projectId/chemin
      │
      ▼
BudgetedContextSelector
      │
      ▼
FederatedContextItem
- ProjectDescriptor
- ContextItem au chemin relatif original
```

Le préfixe `projectId` utilisé pendant la sélection est un détail interne. Il empêche deux chemins relatifs identiques de projets différents d'entrer en collision dans les opérations de fusion, de tri ou d'explication. Il est retiré avant de produire le contrat public `FederatedContextItem`.

Le premier incrément accepte uniquement les sources de tâche :

- `FILE` ;
- `SYMBOL` ;
- `TEST` ;
- `DOCUMENTATION`.

Une requête fédérée demandant explicitement `INSTRUCTION`, `SKILL` ou `GIT` est refusée tant qu'une politique dédiée n'a pas été mesurée et décidée.

La répartition du budget est pilotée par le classement global existant. Aucun quota égalitaire ou quota minimal par projet n'est introduit avant observation d'un problème reproductible de starvation. Les métadonnées exposent le nombre d'items et de tokens sélectionnés par projet afin de rendre ce risque mesurable.

Aucune déduplication inter-projets n'est appliquée : deux repositories peuvent légitimement contenir le même chemin relatif ou une implémentation homonyme.

## Conséquences positives

- le budget demandé reste un invariant global ;
- la provenance n'est jamais inférée depuis le texte ou un chemin ambigu ;
- le ranking fédéré déjà validé décide de la priorité des fragments ;
- aucune nouvelle heuristique de quota n'est ajoutée sans mesure ;
- les contrats mono-projet restent inchangés ;
- le cœur reste local-first et indépendant des adaptateurs REST/MCP ;
- les sources projet-locales ne contaminent pas silencieusement un contexte multi-repository.

## Conséquences négatives et compromis acceptés

- un projet très pertinent peut consommer une grande partie du budget ;
- les instructions, skills et signaux Git ne sont pas encore disponibles dans un contexte fédéré ;
- la provenance nécessite un nouveau contrat `FederatedContextItem` au lieu de réutiliser directement `ContextItem` ;
- la politique de budget devra être réexaminée si les mesures montrent une starvation nuisible.

## Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Un projet monopolise le budget | Moyen | exposer `selectedItemsByProject` et `selectedTokensByProject`, puis mesurer avant d'ajouter des quotas |
| Collision de chemins relatifs entre repositories | Élevé | conserver le `ProjectDescriptor` et utiliser une portée interne par `projectId` pendant la sélection |
| Mélange incohérent d'instructions/skills/Git | Élevé | refuser explicitement ces sources dans le premier incrément |
| Régression du contexte mono-projet | Moyen | ne pas modifier `DefaultContextBuilder` et conserver ses tests de non-régression |
| Dépassement du budget global | Élevé | appliquer une seule instance de `BudgetedContextSelector` sur l'ensemble des fragments fédérés |

## Confirmation

La décision est respectée si :

- plusieurs projets explicitement sélectionnés peuvent produire un contexte unique ;
- `estimatedTokens <= tokenBudget` pour le bundle fédéré ;
- chaque item expose son projet d'origine ;
- deux projets distincts contenant le même chemin relatif peuvent tous deux être présents ;
- deux exécutions identiques produisent une sélection déterministe ;
- `INSTRUCTION`, `SKILL` et `GIT` sont rejetés tant que leur politique fédérée n'est pas définie ;
- le contexte mono-projet historique reste inchangé ;
- la distribution réelle du budget par projet est observable dans les métadonnées.

## Analyse détaillée des options

### Concaténer des `ContextBundle` projet-locaux

**Avantages :**

- réutilisation directe de l'API existante ;
- implémentation superficiellement simple.

**Inconvénients :**

- chaque projet consomme son propre budget ;
- le budget global n'est plus garanti ;
- les sources natives, skills et Git sont sélectionnés indépendamment sans arbitrage global ;
- le classement global fédéré est perdu au moment de la sélection finale.

### Répartir le budget également entre projets

**Avantages :**

- chaque projet obtient une part prévisible ;
- aucune starvation totale.

**Inconvénients :**

- la pertinence n'est pas nécessairement répartie également ;
- du budget peut être gaspillé sur un projet faiblement pertinent ;
- le nombre de projets modifie mécaniquement la qualité des fragments disponibles.

### Imposer des quotas par projet

**Avantages :**

- contrôle fin de la diversité inter-projets.

**Inconvénients :**

- seuils arbitraires sans baseline ;
- complexité supplémentaire dans la sélection ;
- risque de dégrader les requêtes naturellement concentrées sur un seul repository.

### Sélection globale pilotée par le ranking fédéré

**Avantages :**

- réutilise les signaux de pertinence déjà validés ;
- conserve un seul budget ;
- ajoute peu de logique nouvelle ;
- rend la distribution observable avant de la contraindre.

**Inconvénients :**

- peut révéler une domination réelle d'un projet ;
- nécessite une politique séparée pour les sources intrinsèquement projet-locales.

## Impacts sur l'architecture

Nouveaux contrats :

- `FederatedContextRequest` ;
- `FederatedContextItem` ;
- `FederatedContextBundle` ;
- `FederatedContextBuilder`.

`NexusApplication` expose `contextAcrossProjects(...)` sous les adaptateurs. `DefaultContextBuilder`, `ContextItem` et `ContextBundle` restent inchangés.

## Conditions de réexamen

Réexaminer cette décision si :

- les mesures montrent qu'un projet monopolise régulièrement le budget au détriment de résultats nécessaires ;
- un besoin réel impose des instructions, skills ou signaux Git multi-projets ;
- REST ou MCP doivent exposer le contexte fédéré et nécessitent un contrat de sérialisation supplémentaire ;
- le ranking fédéré évolue d'une manière qui change la comparabilité inter-projets ;
- une politique de workspace ou de projet principal apparaît dans les usages réels.

Une modification substantielle de la politique de budget ou des sources projet-locales donne lieu à un nouvel ADR ou à l'acceptation explicite d'une évolution de cette décision tant qu'elle reste `proposed`.

## Décisions liées

- ADR-0013 — Construire un `ContextBundle` sous budget de tokens explicable.
- ADR-0028 — Construire le contexte à partir de fragments de code prioritairement symboliques.
- ADR-0029 — Sélectionner le `ContextBundle` par un algorithme glouton déterministe sous budget.
- ADR-0032 — Préserver et normaliser le contexte natif déjà configuré dans les projets.
- ADR-0034 — Adopter la divulgation progressive pour les Agent Skills.
- ADR-0035 — Intégrer le contexte Git local comme source bornée et explicable.
- ADR-0043 — Fédérer la recherche locale par projet avant d'introduire un moteur externe.
