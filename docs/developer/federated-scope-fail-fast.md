# Portée fédérée fail-fast

NXA2-08 conserve `FederatedScopePolicy` comme autorité unique de cardinalité pour les opérations fédérées NEXUS et évite les résolutions projet inutiles dans l'adaptateur MCP.

## Contrat canonique

La portée maximale reste :

```text
FederatedScopePolicy.MAX_PROJECTS = 100
```

Le plafond porte sur les **UUID projet uniques**, pas sur le nombre brut de sélecteurs fournis par le client.

- 1 à 100 UUID uniques : acceptés ;
- 101 UUID uniques : refusés avec `FederatedScopePolicy.TOO_MANY_PROJECTS_MESSAGE` ;
- des centaines de doublons qui se résolvent vers au plus 100 UUID restent valides.

## Préflight MCP

Les outils fédérés MCP (`search_across_projects`, `build_context_across_projects`, `explain_context_across_projects`) partagent le même chemin `resolveProjects`.

Avant toute résolution :

1. les sélecteurs textuellement équivalents sont dédupliqués en conservant l'ordre ;
2. les sélecteurs qui sont déjà des UUID explicites sont comptés par UUID ;
3. si 101 UUID explicites distincts sont présents, la requête est rejetée immédiatement, sans accès au repository de projets.

Les sélecteurs par nom ne sont **pas** comptés comme des projets distincts avant résolution. Cela évite un faux positif lorsqu'un nom et un UUID désignent le même projet.

## Résolution incrémentale

Pour les noms et les mélanges nom/UUID, MCP résout les sélecteurs nécessaires puis déduplique sur l'UUID canonique du `ProjectDescriptor`.

Après chaque nouvel UUID réellement découvert, `FederatedScopePolicy.validateUniqueCount(...)` est exécuté. La résolution s'arrête donc au 101e UUID unique ; aucun sélecteur restant n'est résolu pour une requête déjà condamnée.

Cette stratégie préserve simultanément :

- le fail-fast quand le dépassement est démontrable sans résolution ;
- la déduplication canonique par UUID ;
- la compatibilité des alias nom/UUID ;
- le message d'erreur partagé avec REST, application et services fédérés.

## Preuves de non-régression

Les tests couvrent :

- frontière 100/101 dans `FederatedScopePolicy` ;
- 1 000 occurrences du même UUID explicite sans faux rejet ;
- absence de supposition sur l'identité des sélecteurs par nom ;
- MCP STDIO réel avec 101 UUID inexistants : le résultat doit être le plafond fédéré, ce qui prouve qu'aucune résolution projet n'a précédé le rejet ;
- MCP STDIO réel avec plus de 100 occurrences du même UUID READY : la requête reste valide et la portée finale contient un seul projet.
