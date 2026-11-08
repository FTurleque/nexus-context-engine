---
id: BE-0070
title: "Intégrer le contexte Git local, borné, mis en cache en mémoire et confiné au dépôt du projet"
domain: backend
rules: true
---

# Intégrer le contexte Git local, borné, mis en cache en mémoire et confiné au dépôt du projet

- **Statut** : Accepted
- **Date** : 2026-11-08
- **Origine** : consolidation du registre ADR (audit du 2026-11-08)
- **Remplace** : [BE-0035](archive/BE-0035-integrer-le-contexte-git-local-comme-source-bornee-et-explicable.md), [BE-0046](archive/BE-0046-mettre-en-cache-le-contexte-git-dans-les-runtimes-longue-duree.md), [BE-0053](archive/BE-0053-borner-le-parcours-des-arbres-git-avant-collecte.md), [BE-0065](archive/BE-0065-dimensionner-les-caches-git-sur-le-scope-federe.md), [BE-0067](archive/BE-0067-plafonner-la-decouverte-du-depot-git-a-la-racine-du-projet.md)
- **Remplacé par** : —

## Context

L'historique Git local apporte des signaux que le code et la documentation n'ont pas : fichiers récemment modifiés, commits qui expliquent un fichier sélectionné, diff local lié à la demande, fichiers modifiés ensemble. Le risque est de faire de NEXUS un client Git généraliste, d'injecter un historique hors sujet ou de consommer une part disproportionnée du budget.

Lire cet historique coûte (jusqu'à 50 commits, leurs arbres, status et diffs ciblés), et un commit volumineux peut matérialiser un diff entier avant tout débit du budget de découverte. La qualification de la PR #163 (6 dépôts × 24 commits) a mesuré pour un cache mémoire validé par fingerprint un p95 warm de 14,375 à 3,910 ms sous Linux et de 55,686 à 19,528 ms sous Windows, toutes invalidations correctes. Une requête fédérée porte sur jusqu'à `FederatedScopePolicy.MAX_PROJECTS` = 100 projets, chacun avec son entrée de cache.

Une remontée libre des répertoires parents (`FileRepositoryBuilder.findGitDir`) peut retenir le dépôt d'un autre compte placé au-dessus du projet ; Git refuse ce cas (`safe.directory`). Le monorepo de l'utilisateur, enregistré par sous-projet, doit garder son contexte.

La question est : **comment exploiter le dépôt Git local comme source de contexte et signal de ranking, en lecture seule, borné en travail et en tokens, réutilisé sans contexte périmé dans les runtimes longue durée, et limité au dépôt qui appartient au projet ?**

## Options envisagées

- **Source** : A — indexer tout l'historique dans SQLite et Lucene (volume, synchronisation avec les réécritures d'historique, duplication d'une source locale) ; B — ajouter tout l'historique récent au `ContextBundle` (bruit, budget, explicabilité) ; C — provider Git local ciblé sur les chemins candidats et bonus de ranking plafonné, lu à la demande.
- **Parcours des commits** : `DiffFormatter.scan`, qui matérialise tout le diff avant le premier débit du budget ; ou parcours direct des deux arbres par `TreeWalk`, débité entrée par entrée.
- **Réutilisation** : A — recalcul systématique ; B — cache disque partagé ; C — cache mémoire borné par processus longue durée ; D — watcher Git ou filesystem à invalidation événementielle. Capacité : 16 entrées fixes (aucun hit au-delà de 16 projets), capacité configurable (constante opérationnelle sans lien avec la politique de scope), ou capacité alignée sur `FederatedScopePolicy.MAX_PROJECTS`.
- **Découverte du dépôt** : A — plafond strict à la racine du projet (casse le monorepo) ; B — plafond configurable, équivalent de `GIT_CEILING_DIRECTORIES` (configuration opérationnelle, remontée ouverte par défaut) ; C — racine retenue sans condition, dépôt parent retenu seulement s'il appartient à l'utilisateur courant.

## Decision

**Options retenues : source C, parcours par `TreeWalk`, cache mémoire C dimensionné sur `FederatedScopePolicy.MAX_PROJECTS`, découverte C.**

### Source Git locale en lecture seule

- Deux responsabilités distinctes : `GitRecencyCandidateEnricher` ajoute un signal `gitRecencyScore` aux candidats connus de la recherche ; `GitContextSourceProvider` analyse les chemins les mieux classés et produit des fragments `GIT` bornés.
- Le contexte Git peut contenir les commits récents qui touchent les chemins cibles, un historique court des principaux fichiers cibles, un diff local limité aux chemins cibles et les fichiers fréquemment modifiés avec eux.
- Le provider est local et basé sur JGit ; les données sont lues depuis le repository local uniquement, sans `fetch`, `pull`, `push`, `checkout` ni commit.
- L'historique Git n'est pas persisté dans SQLite.

### Bornes de collecte

- Le provider analyse au plus 50 commits récents, 5 chemins cibles pour l'historique détaillé, 5 commits par historique de fichier, 8 relations de co-changement, et un diff local borné en taille (6000 caractères par zone de patch). Ces limites sont des paramètres d'implémentation révisables après mesure.
- `LocalGitContextSourceProvider` parcourt directement les deux arbres avec un `TreeWalk` récursif et `TreeFilter.ANY_DIFF`. Chaque entrée est débitée avant sa collecte ; les limites par commit et cumulées s'appliquent immédiatement. La détection coûteuse des renommages n'est pas nécessaire pour cette découverte bornée.

### Signal de récence et budget de tokens

- `gitRecencyScore` est normalisé entre `0` et `1` selon la position du fichier dans la fenêtre des commits récents ; sa contribution vaut `gitRecencyScore × 0,05` et s'ajoute au score existant, score relatif qui peut dépasser `1,0`. Un candidat sans historique Git local conserve exactement son score.
- Le contexte Git est sélectionné après les instructions et les skills, avant le contexte de tâche, avec `gitBudget = min(budget restant, 500, max(64, budget total × 15 %))`. Il est désactivé pour un budget global inférieur à 500 tokens. Ses fragments peuvent être tronqués par le sélecteur générique : ils ne forment pas une procédure atomique.
- Hors repository Git valide : aucun bonus de récence, aucun fragment Git, aucune erreur fonctionnelle ; un diagnostic peut être exposé en mode explicable.

### Découverte du dépôt

- `GitRepositoryLocator` (`com.nexus.context.source.git`) est la fabrique unique qui ouvre le dépôt pour le contexte Git local, le cache des runtimes longue durée et la récence. Aucun autre code de production du package n'appelle `FileRepositoryBuilder.findGitDir`.
- La remontée part de la racine réelle du projet et s'arrête au premier répertoire qui contient une entrée `.git` ; un `.git` invalide ne fait pas chercher plus haut.
- Un dépôt dont l'entrée `.git` est à la racine du projet est retenu sans condition. Un dépôt trouvé dans un répertoire parent n'est retenu que si son entrée `.git` et, pour un fichier gitlink, le répertoire Git qu'il désigne appartiennent à l'utilisateur courant, reconnu par son nom de compte et par le propriétaire attribué à un fichier qu'il crée (sous Windows, un processus élevé attribue ses fichiers au groupe Administrateurs, que Git tolère aussi). Un propriétaire non déterminable vaut refus.
- Le contrôle précède toute lecture de la configuration du dépôt : un dépôt écarté n'est ni ouvert ni lu.
- Un dépôt écarté produit le diagnostic stable `contexte Git ignoré : dépôt Git parent n'appartenant pas à l'utilisateur courant` dans `gitDiagnostics` et la raison `récence Git ignorée : dépôt Git parent n'appartenant pas à l'utilisateur courant` dans l'explication de la récence, sans chemin ni nom de compte. Aucune configuration n'est ajoutée.

### Cache du contexte Git des runtimes longue durée

- `PersistentGitContextSourceProvider` encapsule `LocalGitContextSourceProvider`, qui reste la source de vérité. Un résultat n'est réutilisé que si un fingerprint calculé juste avant le hit est inchangé : worktree réel, HEAD exact, status ciblé, digests SHA-256 des diffs staged et unstaged ciblés.
- La clé comprend la racine projet, la requête, les chemins cibles normalisés et le mode `explain` ; les worktrees liés restent isolés.
- La validation du fingerprint consomme le `ContextDiscoveryBudget` par visites et checkpoints.
- La capacité par défaut est `FederatedScopePolicy.MAX_PROJECTS` (100), éviction LRU par ordre d'accès ; chaque entrée ne porte que le résultat déjà borné du provider local. Un résultat indiquant un repository indisponible n'est jamais mis en cache.
- Les compteurs `CacheStats` (hits, misses, évictions, entrées) et la capacité sont exposés sous la clé `gitContextCache` des métadonnées `explain`, en contexte mono-projet et fédéré ; ils sont absents hors `explain`.
- Le cache est activé uniquement dans `NexusApplication.createLongLived(...)` (REST, MCP) ; `NexusApplication.create(...)` conserve `LocalGitContextSourceProvider` pour la CLI et les usages one-shot.

### Cache du signal de récence

- `GitRecencyCandidateEnricher` mémorise, par (répertoire Git, HEAD), les chemins modifiés des 50 derniers commits dans les bornes de 1 024 chemins par commit et 4 096 cumulés, puis applique le filtre de candidats. Un nouveau HEAD remplace l'entrée du dépôt ; la capacité est `MAX_PROJECTS`.
- Au-delà des bornes, un marqueur « trop large » est mémorisé et le parcours filtré par les chemins candidats s'applique, avec le même résultat.

### Hors décision

La persistance des caches est une persistance en mémoire entre requêtes d'un même processus : ni fichier, ni schéma SQLite, ni protocole inter-processus, ni watcher, ni thread de maintenance, ni capacité configurable. Le suivi exhaustif des renommages, un provider Git distant et la lecture d'un dépôt nu ne sont pas décidés.

## Do's and Don'ts

### Do

- Séparer le signal de recherche (`GitRecencyCandidateEnricher`) de la construction du contexte (`GitContextSourceProvider`).
- Lire le repository local uniquement, par JGit, ouvert par `GitRepositoryLocator`.
- Borner l'analyse (50 commits, 5 chemins cibles, 5 commits par fichier, 8 co-changements, diff local borné) et parcourir les arbres par `TreeWalk` récursif `ANY_DIFF`, chaque entrée débitée avant collecte.
- Limiter la récence à `gitRecencyScore × 0,05` et le contexte Git à `gitBudget`.
- Vérifier le propriétaire d'un dépôt parent avant d'en lire la configuration ; un propriétaire non déterminable vaut refus.
- Valider le fingerprint, en débitant le `ContextDiscoveryBudget`, avant chaque hit ; dimensionner les caches sur `FederatedScopePolicy.MAX_PROJECTS`.

### Don't

- Ne pas appeler de commande JGit distante ou mutante, ni inclure de diff hors des chemins cibles, ni produire de contexte Git sous 500 tokens.
- Ne pas modifier le score d'un candidat sans historique Git local.
- Ne pas matérialiser le diff (`DiffFormatter.scan`) avant le premier débit du budget, ni détecter les renommages dans cette découverte.
- Ne pas appeler `FileRepositoryBuilder.findGitDir`, ni chercher au-delà du premier `.git`, ni retenir un dépôt parent d'un autre compte.
- Ne jamais mettre en cache un repository indisponible ni un historique hors bornes de chemins.
- Ne pas instancier le cache hors du runtime longue durée, ni lui ajouter fichier, schéma SQLite, protocole, watcher, thread ou capacité configurable.

## Consequences

### Positives

- Ranking et `ContextBundle` exploitent les changements récents, locaux et co-modifiés des fichiers sélectionnés, sans LLM ni duplication dans SQLite.
- Le travail consommé avant un refus dépend du budget autorisé.
- Une requête répétée, y compris fédérée sur 100 projets inchangés, est servie par le cache, dont le taux de hit est visible dans `explain`.
- Rien d'un dépôt d'un autre compte n'entre dans le contexte ; le monorepo de l'utilisateur garde le sien sans configuration.

### Négatives

- Chaque hit paie le fingerprint ; la clé inclut la requête ; le cache est local au processus.
- La récence peut favoriser un fichier actif non pertinent (poids faible) ; les co-changements sont corrélatifs ; les renommages ne sont pas suivis.
- Un dépôt parent partagé légitimement entre comptes ne fournit pas de contexte Git.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Contexte périmé ou worktrees confondus | Élevé | fingerprint (worktree, HEAD, status, digests) avant chaque hit, racine projet dans la clé |
| Contournement du budget de découverte | Élevé | débit par entrée de `TreeWalk` et pendant le fingerprint |
| Récence périmée après un commit | Moyen | HEAD dans la clé du cache de récence |
| Mémoire des caches | Moyen | capacités fixes à `MAX_PROJECTS`, entrées déjà bornées |
| Système de fichiers sans propriétaire, compte de service | Faible à moyen | dépôt parent écarté avec diagnostic ; projet enregistré à la racine de son dépôt |

## Compliance and Enforcement

- Tests (`core`, `mvnw clean install`) de `core/src/test/java/com/nexus/context/source/git/` : `LocalGitContextSourceProviderTest`, `GitRecencyCandidateEnricherTest`, `PersistentGitContextSourceProviderTest` (dont capacité `MAX_PROJECTS` et scope cyclique de 20 projets), `PersistentGitContextSourceProviderBudgetTest`, `GitRepositoryLocatorTest`.
- `scripts/self-smoke.ps1` (étape 12) : budget Git, désactivation sous 500 tokens, item `GIT` cohérent sur le dépôt NEXUS.
- Workflow `.github/workflows/git-context-cache-qualification.yml` : `GitContextCacheQualificationBenchmarkTest` et `PersistentGitContextSourceProviderTest` sous Linux et Windows.
- Règle Archgate `BE-0070-integrer-le-contexte-git-local-borne-mis-en-cache-et-confine.rules.ts` : `git-context-reads-local-repository-only`, `git-trees-walked-before-collect`, `git-cache-only-in-long-lived-runtime`, `git-cache-in-memory-sixteen-entries` (identifiant historique ; vérifie `DEFAULT_CAPACITY = FederatedScopePolicy.MAX_PROJECTS`) ; commentaires et littéraux effacés.
- Revue : tout nouvel usage de JGit dans `com.nexus.context.source.git` passe par `GitRepositoryLocator` ; aucune capacité de cache Git hors `FederatedScopePolicy.MAX_PROJECTS`.

## Conditions de réexamen

La décision est réévaluée si la lecture à la demande ou la validation du fingerprint coûte plus que le recalcul sur des workloads réels ; si la mémoire ou le churn mesurés des caches posent problème ; si un index Git persistant ou un partage inter-processus apporte un gain mesurable ; si le suivi des renommages doit devenir exhaustif ; si le bonus de récence dégrade le corpus de référence ; si un provider distant, un dépôt nu ou un dépôt parent partagé entre comptes devient un besoin qualifié ; si JGit offre un snapshot, une invalidation ou un équivalent de `safe.directory` plus sûr ; si les résultats du provider dépendent d'autres entrées que projet, requête, chemins et état Git. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Mise en œuvre

- `core/src/main/java/com/nexus/context/source/git/` (providers, `GitRecencyCandidateEnricher`, `GitRepositoryLocator`) ; `core/src/main/java/com/nexus/ranking/DeterministicContextRanker.java` (`DEFAULT_GIT_RECENCY_WEIGHT`) ; `core/src/main/java/com/nexus/context/ContextBudgetAllocator.java` (`gitBudget`) ; `core/src/main/java/com/nexus/application/NexusApplicationFactory.java` (choix du provider).
- Documentation technique : [git-context.md](../../developer/git-context.md).

## References

- [ARCH-0005](ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.md) — local-first et intégrations opt-in.
- [DATA-0074](DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md) — cycle de vie des index dérivés et des readers longue durée.
- [BE-0081](BE-0081-federer-la-recherche-locale-par-projet-sous-echeance-globale.md) — recherche fédérée et scope maximal.
- [ARCH-0085](ARCH-0085-stabiliser-le-contrat-de-sortie-publique-cli-rest-mcp.md) — projection des diagnostics publics.
- Issue #53 (mesure du cache Git persistant), PR #163 (qualification Linux/Windows).
- OpenSpec : `openspec/changes/archive/2026-11-07-sprint-06-federation-et-runtimes-longue-duree/` (D5, D6), `openspec/changes/archive/2026-11-07-sprint-08-facade-configuration-durcissements/` (D7, spec `git-context-discovery`), `openspec/specs/git-recency-diagnostics/spec.md`.
- Git, `safe.directory` : https://git-scm.com/docs/git-config#Documentation/git-config.txt-safedirectory
