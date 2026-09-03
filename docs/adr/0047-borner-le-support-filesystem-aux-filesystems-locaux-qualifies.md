---
status: accepted
date: 2026-09-03
---

# ADR-0047 — Borner le support filesystem aux filesystems locaux qualifiés

## Contexte et problème

NEXUS protège les accès sensibles avec `ProjectPathGuard`, `SafeFileIO`, `NOFOLLOW_LINKS` et un `FileLock` par projet pour les mutations d'index. Ces mécanismes réduisent fortement les sorties de racine, les lectures via symlink et les collisions d'indexation sur un filesystem local.

Le watch item #52 rappelle toutefois deux limites distinctes :

- les primitives Java portables ne constituent pas un sandbox absolu contre un acteur local capable de muter agressivement les ancêtres, hard-links ou points de montage pendant une opération ;
- la sémantique de `FileLock`, des liens et de la cohérence metadata n'est pas équivalente sur tous les filesystems réseau (SMB/CIFS, NFS, volumes distribués ou couches de virtualisation).

La question n'est donc pas de promettre une portabilité non démontrée, mais de définir précisément ce que NEXUS supporte aujourd'hui et quelles preuves sont requises avant toute extension.

## Facteurs de décision

- comportement reproductible sur Linux et Windows locaux ;
- exclusion mutuelle inter-processus vérifiable avec une JVM distincte ;
- refus fail-closed des symlinks sur les chemins durcis ;
- réduction des fenêtres TOCTOU lors de l'ouverture effective ;
- aucune revendication de sécurité sandbox contre un acteur filesystem activement hostile ;
- aucune hypothèse implicite sur SMB/NFS ;
- possibilité d'élargir ultérieurement le support avec une qualification dédiée par type de filesystem.

## Options envisagées

- A — déclarer tous les filesystems montables supportés par défaut ;
- B — supporter uniquement les filesystems locaux qualifiés et considérer SMB/NFS comme non supportés tant qu'ils ne disposent pas de preuves spécifiques ;
- C — tenter de détecter automatiquement le type de filesystem et bloquer toutes les variantes inconnues.

## Décision retenue

**Option retenue : B.**

NEXUS supporte officiellement les usages sur filesystem local lorsque les qualifications Linux/Windows de la version concernée sont vertes. Les filesystems réseau, distribués ou à sémantique spéciale ne sont **pas supportés par contrat** tant qu'une qualification complète n'a pas démontré la technologie, la configuration et les scénarios nécessaires à une extension de support. Une qualification ciblée ou observationnelle ne suffit pas, à elle seule, à élargir ce contrat.

Cette décision n'ajoute pas un blocage runtime fondé sur `FileStore.type()` : ce type est insuffisamment portable et ne décrit pas à lui seul les garanties réelles d'un montage. La frontière de support est donc documentaire et de qualification, pas une heuristique fragile exécutée en production.

### Filesystems dans le périmètre actuel

- filesystem local du runner Ubuntu utilisé par NEXUS CI / qualification dédiée ;
- filesystem local NTFS du runner Windows utilisé par NEXUS CI / qualification dédiée ;
- filesystem local équivalent sur poste utilisateur, sous réserve des garanties standards du système hôte.

### Hors périmètre de support actuel

- SMB/CIFS général ; la fixture SMB 3.1.1 loopback Windows qualifiée reste une preuve ciblée, pas une extension de support ;
- NFS ;
- volumes distribués ou synchronisés dont la cohérence de lock/metadata n'est pas démontrée ;
- montages WSL/DrvFS ou couches de virtualisation particulières sans qualification dédiée ;
- scénario où un acteur local privilégié modifie activement ancêtres, hard-links ou points de montage pendant l'opération.

« Non supporté » signifie ici que NEXUS ne promet ni exclusion mutuelle inter-processus ni confinement renforcé au-delà des garanties fournies par le filesystem sous-jacent. Cela ne signifie pas que l'exécution échouera nécessairement.

## Confirmation

La décision est confirmée par une qualification dédiée Linux/Windows couvrant au minimum :

- acquisition exclusive réelle dans deux JVM distinctes ;
- libération puis réacquisition du lock ;
- refus d'un répertoire de locks symbolique ;
- refus d'un fichier de lock symbolique sans modification de sa cible ;
- lecture `SafeFileIO` refusant un symlink final au moment de l'ouverture ;
- confinement lexical et canonique des chemins projet ;
- mutation de chemin entre validation et lecture traitée fail-closed lorsque le composant final devient un symlink.

Une extension vers SMB/NFS exige une PR de qualification séparée avec un environnement représentatif et reproductible. Aucun simple succès sur filesystem local ne vaut qualification réseau.

### Qualification SMB sélectionnée

Une qualification ciblée a ensuite été ajoutée sur Windows Server 2025 avec un partage réel créé par `New-SmbShare` et utilisé via UNC. La connexion négociée est SMB 3.1.1 signée, non clusterisée et non `ContinuouslyAvailable`, avec client et serveur sur le même runner.

Cette fixture vérifie :

- round-trip Java lecture/écriture/déplacement via UNC ;
- `ProjectIndexLockManager` entre deux JVM distinctes sur le partage ;
- refus du second propriétaire pendant la détention ;
- réacquisition après libération ;
- capture de la configuration SMB client/serveur et des rapports Surefire.

Le résultat est une **preuve sélectionnée**, pas une extension du support. Elle ne couvre ni deux clients physiques distincts, ni panne/réconnexion, ni SQLite/Lucene réseau, ni un NAS/serveur différent, ni NFS. L'option B reste donc inchangée.

## Conséquences

### Positives

- contrat de support précis et auditable ;
- absence de promesse implicite sur des sémantiques de lock non démontrées ;
- tests locaux renforcés sur les deux OS ;
- preuve SMB ciblée reproductible sans extrapolation à l'ensemble du protocole ;
- chemin clair pour qualifier ultérieurement SMB/NFS sans modifier rétroactivement la politique.

### Négatives

- certains déploiements sur partage réseau restent hors support ;
- aucune protection absolue contre un administrateur local ou un acteur filesystem activement hostile ;
- une future extension réseau nécessitera une infrastructure de test adaptée, notamment multi-client et avec injection de panne.

## Conditions de réexamen

Réévaluer cette décision si :

- un besoin utilisateur réel exige SMB, NFS ou un filesystem distribué précis ;
- une infrastructure CI/reproductible permet de qualifier ces montages avec plusieurs clients et scénarios de panne ;
- Java ou le système hôte fournit des primitives de confinement/locking plus fortes et portables ;
- un incident réel démontre une divergence entre le comportement local qualifié et la documentation.

## Décisions liées

- ADR-0019 — stockage local dans un `NEXUS_HOME` configurable.
- ADR-0022 — Lucene comme index dérivé reconstructible.
- ADR-0045 — lifecycle Lucene long-lived borné.

## Références

- Issue #52 — qualify hostile/network filesystem semantics.
- `Filesystem Semantics Qualification` — qualification locale Linux/Windows.
- `SMB Filesystem Qualification` — preuve SMB 3.1.1 loopback sélectionnée.
