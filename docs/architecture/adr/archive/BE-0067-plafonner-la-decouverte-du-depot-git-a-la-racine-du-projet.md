---
id: BE-0067
title: "Plafonner la découverte du dépôt Git à la racine du projet, sauf dépôt parent de l'utilisateur courant"
domain: backend
rules: false
---

# Plafonner la découverte du dépôt Git à la racine du projet, sauf dépôt parent de l'utilisateur courant

> **Archivé le 2026-11-08** : remplacé par [BE-0070](../BE-0070-integrer-le-contexte-git-local-borne-mis-en-cache-et-confine.md) (contexte Git local borné, mis en cache et confiné). Ce document est conservé pour l'historique ; il n'est plus lu par Archgate.

- **Statut** : Superseded
- **Date** : 2026-11-07
- **Origine** : sprint 8 de l'audit de code du 17/10/2026, constat `AUD-SEC-05`, changement OpenSpec `sprint-08-facade-configuration-durcissements` (design D7)
- **Complète** : [BE-0035](BE-0035-integrer-le-contexte-git-local-comme-source-bornee-et-explicable.md)
- **Remplacé par** : [BE-0070](../BE-0070-integrer-le-contexte-git-local-borne-mis-en-cache-et-confine.md)

## Context

BE-0035 fait du dépôt Git local une source de contexte en lecture seule, sans préciser quel dépôt alimente un projet. `LocalGitContextSourceProvider`, `PersistentGitContextSourceProvider` et `GitRecencyCandidateEnricher` ouvraient chacun le dépôt par `FileRepositoryBuilder.findGitDir(projectRoot)`, qui remonte les répertoires parents sans plafond jusqu'au premier dépôt valide, quel que soit son propriétaire.

Un projet enregistré sous un répertoire partagé (par exemple un `/srv` ou un disque commun dont la racine est un dépôt d'un autre compte) recevait donc l'historique, les messages de commit, les diffs non commités et la configuration Git de ce compte dans son `ContextBundle` et dans le signal de récence. Git lui-même refuse ce cas depuis la version 2.35.2 (`safe.directory`). Le cas légitime à préserver est le monorepo : un projet enregistré dans un sous-répertoire de son propre dépôt garde son contexte Git (`LocalGitContextSourceProviderTest.keepsGitContextInsideANestedProjectInAMonorepo`).

La question est : **quel dépôt Git peut alimenter le contexte d'un projet, et que voit l'utilisateur quand un dépôt est écarté ?**

## Options envisagées

- **A — Plafond strict à la racine du projet** : aucun dépôt parent ; casse le monorepo enregistré par sous-projet.
- **B — Plafond configurable** (équivalent de `GIT_CEILING_DIRECTORIES`) : ajoute une configuration opérationnelle, laisse la remontée ouverte par défaut.
- **C — Racine retenue sans condition, dépôt parent retenu seulement s'il appartient à l'utilisateur courant**, à l'image de `safe.directory` : préserve le monorepo de l'utilisateur, écarte le dépôt d'un autre compte sans configuration.

## Decision

**Option retenue : Option C.**

- Une fabrique unique, `GitRepositoryLocator` (`com.nexus.context.source.git`), ouvre le dépôt pour les trois usages : contexte Git local, cache des runtimes longue durée et récence. Aucun autre code de production du package n'appelle `FileRepositoryBuilder.findGitDir`.
- La remontée part de la racine réelle du projet et s'arrête au premier répertoire qui contient une entrée `.git` ; un `.git` invalide ne fait pas chercher plus haut.
- Un dépôt dont l'entrée `.git` est à la racine du projet est retenu sans condition.
- Un dépôt trouvé dans un répertoire parent n'est retenu que si son entrée `.git` et, pour un fichier gitlink, le répertoire Git qu'il désigne appartiennent à l'utilisateur courant. L'utilisateur courant est reconnu par son nom de compte et par le propriétaire attribué à un fichier qu'il crée (sous Windows, un processus élevé attribue ses fichiers au groupe Administrateurs, que Git tolère aussi).
- Un propriétaire non déterminable (système de fichiers sans propriétaire, partage réseau) vaut refus.
- Le contrôle précède toute lecture de la configuration du dépôt : un dépôt écarté n'est ni ouvert ni lu.
- Un dépôt écarté produit le diagnostic stable `contexte Git ignoré : dépôt Git parent n'appartenant pas à l'utilisateur courant` dans `gitDiagnostics`, et la raison `récence Git ignorée : dépôt Git parent n'appartenant pas à l'utilisateur courant` dans l'explication de la récence. Ni l'un ni l'autre ne contient de chemin ni de nom de compte.

La décision ne change ni les bornes de BE-0035, ni les capacités de cache de BE-0065, ni la lecture seule du dépôt. Elle n'ajoute aucune configuration.

## Do's and Don'ts

### Do

- Ouvrir tout dépôt du contexte Git par `GitRepositoryLocator`.
- Vérifier le propriétaire d'un dépôt parent avant d'en lire la configuration.
- Traiter un propriétaire non déterminable comme un refus.
- Émettre le diagnostic stable, sans chemin ni nom de compte, quand un dépôt parent est écarté.

### Don't

- Ne pas appeler `FileRepositoryBuilder.findGitDir` dans le code de production du contexte Git.
- Ne pas chercher un dépôt au-delà du premier `.git` rencontré.
- Ne pas retenir un dépôt parent d'un autre compte, même en lecture seule.

## Consequences

### Positives

- L'historique, les diffs et la configuration Git d'un autre compte n'entrent plus dans un `ContextBundle` ni dans le ranking.
- Les trois usages de Git appliquent une seule règle, testée en un seul endroit.
- Le monorepo de l'utilisateur garde son contexte sans configuration.

### Négatives

- Un projet sous un dépôt parent partagé légitimement entre comptes perd son contexte Git ; le diagnostic l'indique.
- Le premier contrôle de propriétaire crée et supprime un fichier temporaire vide pour connaître le propriétaire par défaut du processus.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Système de fichiers sans propriétaire (FAT, partage SMB) | Faible | Dépôt parent écarté avec diagnostic ; dépôt à la racine du projet non concerné |
| Compte de service exécutant NEXUS sur le dépôt d'un développeur | Moyen | Diagnostic stable ; enregistrer le projet à la racine de son dépôt |

## Compliance and Enforcement

- Contrôle automatique : `GitRepositoryLocatorTest` (dépôt à la racine sans consultation du propriétaire, dépôt parent de l'utilisateur, dépôt parent d'un autre compte écarté avant lecture d'une configuration invalide, propriétaire non déterminable, gitlink et cible contrôlés, arrêt au premier `.git`, diagnostic public sans chemin, résolution réelle sous Windows et Linux).
- Contrôle automatique : `LocalGitContextSourceProviderTest`, `PersistentGitContextSourceProviderTest` et `GitRecencyCandidateEnricherTest` vérifient l'absence de commit, de diff et de signal de récence pour un dépôt parent écarté, le propriétaire étant simulé par injection.
- Contrôle automatique : la règle Archgate de BE-0035 continue d'interdire toute commande distante ou mutante dans le package.
- Revue : tout nouvel usage de JGit dans `com.nexus.context.source.git` passe par `GitRepositoryLocator`.

## Conditions de réexamen

La décision est réévaluée si un besoin qualifié de dépôt parent partagé entre comptes apparaît, si JGit expose un contrôle équivalent à `safe.directory`, ou si NEXUS doit lire un dépôt nu.

## Mise en œuvre

`GitRepositoryLocator` et son injection dans les trois classes ; diagnostics `GitRepositoryLocator.PARENT_REPOSITORY_REJECTED` et `GitRecencyCandidateEnricher.REJECTED_REPOSITORY_REASON`. Documentation : [git-context.md](../../../developer/git-context.md#découverte-du-dépôt).

## References

- [BE-0035](BE-0035-integrer-le-contexte-git-local-comme-source-bornee-et-explicable.md) — contexte Git local borné.
- [BE-0046](BE-0046-mettre-en-cache-le-contexte-git-dans-les-runtimes-longue-duree.md), [BE-0065](BE-0065-dimensionner-les-caches-git-sur-le-scope-federe.md) — caches Git des runtimes longue durée.
- [ARCH-0061](ARCH-0061-decider-le-contrat-de-sortie-publique-dans-la-projection-du-coeur.md) — projection des diagnostics publics.
- Changement OpenSpec `openspec/changes/archive/2026-11-07-sprint-08-facade-configuration-durcissements` (design D7, spec `git-context-discovery`).
- Git, `safe.directory` : https://git-scm.com/docs/git-config#Documentation/git-config.txt-safedirectory
