# ADR-0055 — Préserver les lecteurs lors des reconstructions sémantiques

- Statut : Accepté
- Date : 2026-09-13
- Complète : ADR-0045 et ADR-0052

## Contexte

La suppression manuelle des fichiers Lucene avant un rebuild empêche la
reconstruction sous Windows si une autre instance garde des segments mappés.
Sous Linux, elle peut réutiliser les générations de commit et laisser un
lecteur persistant servir l'ancien corpus. Un commit physiquement corrompu
nécessite cependant un traitement particulier : `OpenMode.CREATE` essaie
d'abord de lire les informations de génération existantes.

## Facteurs et options

La compatibilité avec les lecteurs indépendants Windows/Linux, la conservation
des générations Lucene et le maintien des writers temporaires guident le choix.
Une purge préalable est rejetée car elle invalide les lecteurs. Des répertoires
versionnés pour tous les rebuilds ajouteraient inutilement une politique de
collecte à la voie normale. La publication d'un répertoire distinct est donc
réservée à la récupération des commits illisibles.

## Décision

Un rebuild normal utilise directement `IndexWriter` en mode `CREATE`, sans
purge préalable. Lucene conserve la progression des générations et gère les
anciens segments. Le nouveau corpus n'est validé qu'après l'ajout de tous les
documents ; la fermeture après exception ne publie pas un corpus partiel.

Si le commit est corrompu ou son format illisible, le rebuild produit un index
complet dans `semantic-lucene/recovery-<uuid>`. Une fois son commit réussi,
un remplacement atomique du petit fichier `current-generation` publie le
répertoire actif. Le pointeur est borné, lu sans suivre de lien et limité à un
nom de génération local validé. Un stockage ne permettant pas le remplacement
atomique provoque un échec, sans publication partielle.

Les index existants restent lisibles à leur emplacement historique en l'absence
de pointeur. Les lecteurs résolvent le répertoire actif avant chaque opération.
Le cache ferme et remplace son lecteur lorsqu'un même UUID désigne désormais
un autre répertoire. Les mutations et les lectures de façade conservent la
sérialisation par projet de l'ADR-0052.

## Conséquences

Les lecteurs indépendants suivent les rebuilds ordinaires et les récupérations,
y compris sous Windows. Aucun mécanisme de verrou global entre JVM ni aucun
writer persistant n'est ajouté. SQLite reste canonique.

Les fichiers d'une génération corrompue sont conservés : les supprimer pendant
la récupération pourrait casser un lecteur externe encore ouvert. Des
corruptions répétées peuvent donc augmenter l'espace occupé. Leur nettoyage
se fait après arrêt de tous les processus partageant `NEXUS_HOME`, selon la
procédure de quarantaine documentée. Les rebuilds ordinaires ne créent pas de
nouveau répertoire de récupération.

Le format physique exceptionnel change : les outils de diagnostic doivent
tenir compte de `current-generation`. Revenir à un binaire antérieur après
une récupération exige de mettre le seul index sémantique en quarantaine et
de le reconstruire ; SQLite et l'index lexical sont conservés.

## Validation

Les tests utilisent deux instances indépendantes et vérifient les rebuilds
répétés, l'index vide, un changement de dimension, une corruption réelle du
commit, une mutation après récupération externe, les pointeurs invalides et
l'absence de publication partielle. Ils sont exécutés sous Windows et Linux.

- [Procédure opérateur](../developer/semantic-search.md)
- [Audit d'origine](../audits/2026-09-13-audit-complet-apres-merge-221.md)
