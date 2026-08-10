# Politique commune de taille des requêtes

NXA2-06 introduit `QueryPolicy` comme frontière commune pour les requêtes de recherche, de contexte et de symboles NEXUS.

## Borne canonique

La limite est fixée à **16 KiB (16 384 octets) après encodage UTF-8** :

```text
QueryPolicy.MAX_QUERY_UTF8_BYTES = 16384
```

La mesure est effectuée après suppression des espaces extérieurs avec la même sémantique de normalisation que les surfaces historiques. Une requête vide après normalisation reste refusée.

La politique est exprimée en octets et non en nombre de caractères Java. Une requête composée de caractères multi-octets consomme donc son coût réel de transport/traitement. Par exemple, `é` occupe deux octets en UTF-8.

## Frontières

La politique est appliquée :

- dans `NexusApplication` pour tous les appels Java directs ;
- dans les ressources REST projet-locales ;
- dans les ressources REST fédérées ;
- indirectement pour CLI et MCP via la façade applicative, avec tests contractuels de leurs réponses d'erreur.

La façade valide la requête **avant** la résolution `READY` du projet pour les surfaces projet-locales et avant l'appel aux moteurs de recherche/contexte. Un appel direct ne peut donc pas contourner la borne des adaptateurs et une requête surdimensionnée ne parvient pas au ranking, Lucene, semantic search/Ollama, context builder ou repository de symboles.

## Sémantique de frontière

- exactement 16 384 octets UTF-8 : accepté ;
- 16 385 octets : refusé ;
- la même règle s'applique aux caractères ASCII et Unicode ;
- les espaces extérieurs retirés ne comptent pas dans la taille normalisée.

## Surfaces qualifiées

Les tests couvrent :

- `QueryPolicy` en ASCII et Unicode à la frontière exacte et N+1 ;
- toutes les surfaces publiques de `NexusApplication` (`search`, fédéré, contexte, symboles/usages) ;
- CLI avec erreur d'usage structurée ;
- REST search/context avec HTTP 400 ;
- REST fédéré avant délégation au service ;
- MCP réel sur STDIO avec `nexus_tool_error`.

Les adaptateurs peuvent ajouter des validations de schéma plus restrictives à l'avenir, mais la limite UTF-8 de `QueryPolicy` reste l'autorité canonique commune.
