# Limites globales du corpus d'indexation

NEXUS applique des budgets séparés au walk filesystem, au volume de sources indexables et à la matérialisation des documents destinés aux index dérivés. Le but est qu'un repository hostile ou simplement très volumineux ne transforme pas une limite de disque acceptable en travail CPU/heap non borné.

## Limites par défaut

| Limite | Défaut | Portée |
|---|---:|---|
| `NEXUS_MAX_INDEX_FILES` | `100000` | nombre d'entrées non racine rencontrées par le walk : fichiers, répertoires et entrées ensuite ignorées |
| `NEXUS_MAX_INDEX_TOTAL_BYTES` | `2147483648` (2 GiB) | volume cumulé des sources supportées, sûres et sous la limite par fichier |
| `NEXUS_MAX_FILE_SIZE_BYTES` | `8388608` (8 MiB) | taille maximale d'un fichier individuel, plafond configurable 256 MiB |
| batch index dérivé | `128` documents | nombre maximal de contenus source retenus simultanément avant flush Lucene |
| batch index dérivé | `16777216` octets (16 MiB) | volume source maximal retenu simultanément avant flush Lucene |

Les valeurs configurables doivent être strictement positives. Une configuration invalide échoue au lieu de désactiver silencieusement la protection.

## Sémantique du budget de traversal

`NEXUS_MAX_INDEX_FILES` conserve son nom historique pour compatibilité, mais sa sémantique est désormais celle d'un **budget d'entrées visitées**. Le compteur est consommé :

- pour chaque répertoire non racine rencontré, avant la décision de descendre ou d'ignorer le sous-arbre ;
- pour chaque fichier/entrée rencontré, avant le filtre d'ignore et avant le filtre de langage.

Ainsi, des millions de petits fichiers binaires, d'extensions inconnues ou de répertoires vides ne peuvent pas contourner le budget global du walk. Un sous-arbre ignoré consomme l'entrée correspondant à sa racine, mais ses descendants ne sont naturellement pas visités.

## Sémantique du volume cumulatif

Le volume est ajouté uniquement après les contrôles suivants :

1. type de source supporté ;
2. refus des symlinks/entrées non régulières ;
3. confinement `ProjectPathGuard` ;
4. limite individuelle `NEXUS_MAX_FILE_SIZE_BYTES`.

Un fichier rejeté par la limite individuelle consomme le budget de traversal mais pas le budget d'octets indexables puisqu'il n'est ni hashé ni analysé. La somme utilise un contrôle anti-overflow avant addition.

## Heap borné pendant les rebuilds dérivés

Le plafond de 2 GiB est un budget de corpus sur disque, **pas** une autorisation de matérialiser 2 GiB de `String` en heap.

`ProjectIndexingService` ne conserve donc plus l'ensemble des `SearchDocument` d'un rebuild. Le pipeline est :

```text
scan canonique
  -> snapshot + validation hash
  -> analyse
  -> batch lexical/sémantique borné
  -> flush Lucene
  -> libération du contenu source du batch
```

Un batch est vidé avant de dépasser 128 documents ou 16 MiB de contenu source cumulé. Un fichier individuel plus grand que 16 MiB peut former seul un batch ; sa taille reste de toute façon bornée par `NEXUS_MAX_FILE_SIZE_BYTES`.

Lors d'un rebuild, les index dérivés sont d'abord remis à zéro puis remplis par batches. Si un flush ou une étape ultérieure échoue, le projet ne devient pas `READY` : il reste/passe `FAILED`, et l'indexation suivante reconstruit intégralement les dérivés avant qu'ils soient à nouveau exposés.

L'index sémantique reçoit lui aussi uniquement le batch courant ; la liste de vecteurs produite par `SemanticIndexingService` est donc bornée par ce même batch au lieu de couvrir tout le corpus.

## Comportement en dépassement

Le scanner lève immédiatement une `IOException` précise dès l'entrée `N+1` ou le premier octet cumulatif au-delà de la borne. Il ne renvoie pas un corpus tronqué.

`ProjectIndexingService` utilise le même scanner pour le scan initial et pour la vérification finale de fingerprint. Un dépassement à n'importe lequel de ces points empêche la publication `READY` et place le projet en `FAILED`. Un ancien index complet peut rester physiquement présent pour recovery, mais les surfaces applicatives refusent déjà tout projet non `READY`.

## Preuves et benchmarks

`.github/workflows/scanner-corpus-benchmark.yml` exécute un benchmark hermétique dédié :

- profil PR `ci` : 2 000 petits fichiers ;
- profil manuel `full` : 10 000 petits fichiers ;
- les limites du scanner sont positionnées exactement sur le corpus mesuré ;
- le rapport `target/scanner-corpus-benchmark.json` vérifie le nombre de fichiers, le volume exact et un budget de scan de 30 secondes.

Les tests unitaires couvrent en plus :

- frontières exactes et `N+1` ;
- volume exact et octet supplémentaire ;
- interaction avec la limite par fichier ;
- explosion composée uniquement de répertoires vides ;
- rebuild des index dérivés en plusieurs batches dont le volume retenu reste sous le budget configuré.
