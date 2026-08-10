# Limites globales du corpus d'indexation

NXA2-05 ajoute un budget global au scanner afin qu'un repository contenant un très grand nombre de petits fichiers ne puisse pas provoquer un travail CPU/mémoire non borné avant l'indexation.

## Limites par défaut

| Variable | Défaut | Portée |
|---|---:|---|
| `NEXUS_MAX_INDEX_FILES` | `100000` | nombre de fichiers non ignorés visités pendant un scan |
| `NEXUS_MAX_INDEX_TOTAL_BYTES` | `2147483648` (2 GiB) | volume cumulé des sources supportées, sûres et sous la limite par fichier |
| `NEXUS_MAX_INDEX_FILE_BYTES` | politique existante | taille maximale d'un fichier individuel |

Les valeurs configurées doivent être strictement positives. Une configuration invalide échoue au lieu de désactiver silencieusement la protection.

## Sémantique du nombre de fichiers

Le compteur de fichiers est incrémenté pour chaque entrée fichier non ignorée rencontrée par le walk, avant le filtre de langage. Un corpus composé de millions de petits fichiers binaires ou d'extensions inconnues ne peut donc pas contourner le budget de traversal en restant hors des langages indexables.

Les sous-arbres ignorés par les règles NEXUS/Git ne sont pas visités et ne consomment pas ce budget fichier.

## Sémantique du volume cumulatif

Le volume est ajouté uniquement après les contrôles suivants :

1. type de source supporté ;
2. refus des symlinks/entrées non régulières ;
3. confinement `ProjectPathGuard` ;
4. limite individuelle `NEXUS_MAX_INDEX_FILE_BYTES`.

Un fichier rejeté par la limite individuelle reste compté dans le budget de fichiers, mais ne consomme pas le budget d'octets indexables puisqu'il n'est ni hashé ni analysé.

La somme utilise un contrôle anti-overflow avant addition.

## Comportement en dépassement

Le scanner lève immédiatement une `IOException` précise dès le fichier `N+1` ou le premier octet cumulatif au-delà de la borne. Il ne renvoie pas un corpus tronqué.

`ProjectIndexingService` utilise le même scanner pour le scan initial et pour la vérification finale de fingerprint. Un dépassement à n'importe lequel de ces points empêche la publication `READY` et place le projet en `FAILED`. Un ancien index complet peut rester physiquement présent pour recovery, mais les surfaces applicatives refusent déjà tout projet non `READY`.

## Benchmark

`.github/workflows/scanner-corpus-benchmark.yml` exécute un benchmark hermétique dédié :

- profil PR `ci` : 2 000 petits fichiers ;
- profil manuel `full` : 10 000 petits fichiers ;
- les limites du scanner sont positionnées exactement sur le corpus mesuré ;
- le rapport `target/scanner-corpus-benchmark.json` vérifie le nombre de fichiers, le volume exact et un budget de scan de 30 secondes.

Les tests unitaires couvrent en plus les frontières exactes, `N+1`, le volume exact, l'octet supplémentaire et l'interaction avec la limite par fichier.
