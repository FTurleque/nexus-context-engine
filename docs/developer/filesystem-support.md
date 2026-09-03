# Support filesystem et qualification

Cette page définit la frontière de support filesystem de NEXUS. Elle complète l'ADR-0047 et doit être lue comme un contrat de support, pas comme une détection runtime automatique.

## Matrice de support

| Environnement | Statut | Preuve attendue |
|---|---|---|
| Linux, filesystem local du runner qualifié | **supporté** | `Filesystem Semantics Qualification / linux` vert sur le HEAD exact |
| Windows, filesystem local du runner qualifié | **supporté** | `Filesystem Semantics Qualification / windows` vert sur le HEAD exact |
| Filesystem local utilisateur avec sémantique standard de l'OS | **supporté sous les garanties de l'OS hôte** | même contrat fonctionnel que la qualification Linux/Windows |
| SMB/CIFS | **non supporté** | qualification dédiée requise avant toute revendication de support |
| NFS | **non supporté** | qualification dédiée requise avant toute revendication de support |
| volume distribué / synchronisé / virtuel à sémantique spéciale | **non supporté par défaut** | qualification dédiée par technologie/configuration |
| WSL/DrvFS ou montage équivalent | **non qualifié** | qualification dédiée requise |
| filesystem activement hostile sous contrôle d'un acteur local privilégié | **hors modèle de garantie** | nécessite des primitives de sandbox plus fortes que les garanties Java portables actuelles |

`Non supporté` ne signifie pas qu'un montage échouera nécessairement. Cela signifie que NEXUS ne promet pas la cohérence de `FileLock`, de metadata ou de confinement au-delà des garanties effectivement qualifiées.

## Ce que la qualification locale vérifie

Le workflow `Filesystem Semantics Qualification` exécute les mêmes scénarios sur Linux et Windows :

1. acquisition d'un lock projet dans la JVM du test ;
2. tentative concurrente dans une **seconde JVM** : elle doit échouer comme `busy` ;
3. libération puis acquisition réussie dans la JVM enfant ;
4. nouvelle acquisition après terminaison du processus enfant ;
5. refus d'un répertoire de locks symbolique ;
6. refus d'un fichier de lock symbolique sans modification de sa cible ;
7. refus d'un symlink final par `SafeFileIO` au moment de l'ouverture ;
8. refus d'une traversée lexicale hors racine ;
9. refus d'un symlink d'ancêtre sous la racine projet ;
10. simulation déterministe d'une mutation validation→ouverture où le fichier final devient un symlink : la lecture doit échouer fermée.

Les rapports Surefire et les informations du filesystem du runner sont conservés 90 jours comme artifacts de qualification.

## Limites explicitement acceptées

### Ancêtres/hard-links mutés activement

`ProjectPathGuard` vérifie la racine et les composants du chemin, puis `SafeFileIO` réouvre le fichier final avec `NOFOLLOW_LINKS`. Cela réduit la fenêtre TOCTOU mais ne constitue pas une sandbox absolue contre un acteur capable de remplacer agressivement des ancêtres, hard-links ou points de montage entre les étapes.

NEXUS ne revendique donc pas de protection contre un administrateur local ou un adversaire filesystem privilégié.

### Filesystems réseau

La sémantique `java.nio.channels.FileLock` dépend du filesystem et de sa configuration. Une qualification locale réussie ne prouve rien sur SMB/NFS.

Avant d'étendre le support à un filesystem réseau, la qualification doit au minimum couvrir :

- deux processus distincts sur deux clients lorsque le protocole le permet ;
- acquisition concurrente du même lock ;
- libération après arrêt normal et après terminaison forcée ;
- visibilité/cohérence des créations, remplacements et suppressions ;
- symlinks/reparse points si le protocole les expose ;
- comportement après coupure réseau/reconnexion ;
- comportement du stockage SQLite et des index Lucene dérivés ;
- version/configuration serveur et options de montage documentées.

Une preuve réalisée sur une configuration SMB/NFS ne doit pas être extrapolée automatiquement à une autre implémentation ou option de montage.

## Lecture des résultats CI

La qualification doit être attachée au HEAD exact de la PR ou du commit concerné. Un ancien run vert n'est pas une preuve pour un nouveau HEAD.

Pour un changement touchant `NexusPaths`, `ProjectIndexLockManager`, `ProjectPathGuard`, `SafeFileIO`, leurs tests ou la présente documentation, la matrice Linux/Windows doit être relancée automatiquement.

## Références

- ADR-0047 — frontière de support filesystem.
- Issue #52 — qualification hostile/network filesystem semantics.
- `ProjectIndexLockManagerTest` — lock JVM + inter-processus.
- `FilesystemSemanticsQualificationTest` — confinement et mutation de chemin.
- `SafeFileIOTest` — ouverture `NOFOLLOW_LINKS` et lecture bornée.
