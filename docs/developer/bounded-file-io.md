# Budget de lecture SafeFileIO

`SafeFileIO.newInputStreamNoFollow(path, maxBytes)` applique une borne unique au nombre total d'octets physiquement traversés par le flux.

## Invariant

Le même compteur est consommé par :

- `read()` ;
- `read(byte[], offset, length)` ;
- les opérations composées de `InputStream`, notamment `readNBytes` et `readAllBytes` ;
- `skip(long)`.

Un octet ignoré par un parseur est donc soumis à la même limite qu'un octet retourné au parseur. Cette propriété est nécessaire pour les formats tels que SCIP/Protobuf, où les champs inconnus sont volontairement sautés.

## Détection de dépassement

Les opérations bulk et `skip` sont limitées au budget restant plus un octet sentinelle. L'octet sentinelle permet de distinguer :

- une EOF exacte à `maxBytes`, qui est valide ;
- la présence d'au moins un octet supplémentaire, qui provoque immédiatement une `IOException`.

Le flux ne peut ainsi pas parcourir une quantité arbitraire de données au-delà de la borne avant de signaler le dépassement.

## SCIP

`ScipCodeIndexImporter` ouvre `index.scip` via cette primitive avec `NEXUS_MAX_SCIP_INDEX_BYTES`. Les champs Protobuf inconnus, y compris les champs `length-delimited` traités par `skipFully`, consomment donc le budget total. Les limites par message restent indépendantes et complémentaires.

Les tests couvrent la frontière exacte, le dépassement par lecture, le dépassement par `skip`, un parcours mixte lecture + saut, un champ SCIP inconnu supérieur à la borne totale et un champ inconnu tronqué.
