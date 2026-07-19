---
status: accepted
date: 2026-07-19
---

# ADR-0016 — Utiliser le SDK Java MCP officiel pour l'adaptateur MCP

## Contexte et problème

NEXUS doit pouvoir être exposé à terme à des assistants et agents compatibles avec le Model Context Protocol (MCP). Les outils envisagés couvrent notamment la recherche de code, la recherche de symboles, la construction de contexte et l'explication des décisions.

Le protocole MCP possède déjà un SDK Java dédié. Réimplémenter le protocole, les transports, la négociation et les mécanismes d'outils dans NEXUS créerait un coût de maintenance important sans valeur différenciante pour le moteur de contexte.

La question est : **le futur serveur MCP de NEXUS doit-il implémenter le protocole lui-même ou s'appuyer sur le SDK Java existant en gardant les handlers minces ?**

## Facteurs de décision

- conformité au protocole MCP ;
- réduction du code maison ;
- maintien de la logique métier dans le cœur ;
- support des transports standards ;
- facilité de mise à jour du protocole ;
- testabilité ;
- compatibilité avec le socle Java du projet ;
- absence de dépendance MCP dans les contrats métier.

## Options envisagées

- réimplémenter MCP directement dans NEXUS ;
- utiliser un SDK communautaire non officiel ;
- utiliser le SDK Java MCP officiel et créer un adaptateur mince ;
- exposer uniquement REST et laisser un proxy externe convertir vers MCP.

## Décision retenue

**Option retenue : utiliser le SDK Java MCP officiel pour le futur adaptateur MCP et maintenir les handlers comme une couche mince au-dessus des services NEXUS.**

Les outils MCP candidats comprennent :

```text
search_code
find_symbol
find_usages
get_relevant_files
get_related_tests
get_architecture_context
get_module_context
get_project_instructions
get_recent_changes
build_context
explain_context
```

La liste définit une orientation fonctionnelle, pas une API figée avant l'implémentation.

Les handlers MCP doivent :

1. valider et traduire la requête protocolaire ;
2. appeler un service applicatif NEXUS ;
3. traduire le résultat en réponse MCP ;
4. ne pas réimplémenter la recherche, le ranking ou le budget.

Le SDK MCP est une dépendance d'adaptateur. Il ne doit pas apparaître dans `ContextRequest`, `ContextBundle`, `SearchStrategy` ou les modèles métier.

### Conséquences positives

- NEXUS évite de réimplémenter un protocole existant ;
- la compatibilité MCP peut suivre l'évolution du SDK ;
- les mêmes services restent utilisables par CLI et REST ;
- la surface MCP reste séparée du cœur ;
- les tests de conformité protocolaire sont facilités par l'écosystème du SDK.

### Conséquences négatives et compromis acceptés

- l'adaptateur dépend du cycle de vie du SDK ;
- une évolution majeure du SDK peut nécessiter une migration ;
- les concepts MCP doivent être traduits vers les concepts NEXUS ;
- certaines fonctionnalités du protocole peuvent rester inutilisées.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Fuite de types MCP dans le cœur | Élevé | Isoler le SDK dans le package/module adaptateur |
| Handlers contenant de la logique métier | Élevé | Revue d'architecture et tests des services indépendants du protocole |
| Rupture de compatibilité du SDK | Moyen | Versionner l'adaptateur et suivre les versions du protocole |
| Multiplication d'outils MCP redondants | Moyen | Stabiliser la surface à partir des cas d'usage réels et réutiliser `build_context` comme capacité centrale |
| MCP devient requis pour utiliser NEXUS | Élevé | Maintenir CLI et API/usage bibliothèque indépendants |

### Confirmation

La décision est respectée si :

- aucune implémentation maison du framing ou transport MCP n'est ajoutée sans justification ;
- les types du SDK restent dans l'adaptateur ;
- un même service de recherche produit les mêmes résultats depuis CLI et MCP ;
- les handlers ne calculent aucun score métier ;
- NEXUS reste utilisable sans démarrer un serveur MCP.

## Analyse détaillée des options

### Réimplémenter MCP directement dans NEXUS

**Avantages :**

- contrôle total ;
- aucune dépendance SDK.

**Inconvénients :**

- maintenance du protocole ;
- risque d'incompatibilités ;
- temps détourné du moteur de contexte ;
- tests de conformité supplémentaires.

### Utiliser un SDK communautaire non officiel

**Avantages :**

- peut offrir une ergonomie spécifique ou des fonctionnalités avancées.

**Inconvénients :**

- risque de retard par rapport au protocole ;
- dépendance à un projet intermédiaire lorsque le SDK officiel existe.

### Utiliser le SDK Java MCP officiel

**Avantages :**

- meilleure proximité avec l'évolution du protocole ;
- réduction du code maison ;
- support Java cohérent avec NEXUS ;
- possibilité de garder un adaptateur mince.

**Inconvénients :**

- dépendance externe à maintenir ;
- adaptations nécessaires lors des changements de version.

### Exposer uniquement REST avec proxy MCP externe

**Avantages :**

- NEXUS ne dépend pas de MCP ;
- mutualisation possible via une passerelle existante.

**Inconvénients :**

- composant supplémentaire ;
- expérience moins directe pour les agents ;
- mapping des tools hors du projet et moins maîtrisé.

## Impacts sur l'architecture

```text
Client MCP
   │
   ▼
MCP Java SDK
   │
   ▼
Nexus MCP Adapter
   │
   ▼
Application Services
   │
   ▼
NEXUS Core
```

L'adaptateur MCP pourra devenir un module séparé lorsque l'isolation de dépendance ou le packaging le justifiera.

## Conditions de réexamen

Réexaminer si :

- le SDK officiel n'est plus maintenu ;
- ses contraintes de runtime deviennent incompatibles avec NEXUS ;
- un changement majeur du protocole impose une nouvelle architecture ;
- un proxy externe standard devient objectivement plus simple et plus fiable.

## Décisions liées

- ADR-0003 — Conserver un cœur Java sans framework applicatif obligatoire.
- ADR-0004 — Démarrer avec un seul module Maven et extraire uniquement sur besoin réel.
- ADR-0015 — Valider le MVP par la CLI avant l'API, MCP et les intégrations IDE.
- ADR-0017 — Découpler NEXUS des outils et orchestrateurs externes.

## Références

- Model Context Protocol : https://modelcontextprotocol.io/
- SDK Java MCP : https://github.com/modelcontextprotocol/java-sdk
