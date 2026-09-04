# Contexte Git local

NEXUS utilise Git local comme signal de pertinence et source de contexte **read-only, sans réseau et bornée**.

## Principes

Le provider n'effectue ni fetch, pull, push, checkout ni création de commit. Il travaille uniquement sur le repository local déjà présent et sur les chemins cibles remontés par la recherche.

## Bornes structurelles

`LocalGitContextSourceProvider` impose notamment :

```text
commits récents                 50
chemins cibles d'historique      5
commits par chemin               5
co-changements                   8
chemins modifiés / commit     2000
chemins modifiés cumulés     10000
patch rendu / zone            6000 caractères
```

Le provider consomme aussi le `ContextDiscoveryBudget` partagé avec les autres sources natives.

## Cache des runtimes longue durée

REST et MCP utilisent `NexusApplication.createLongLived(...)`. Cette composition enveloppe le provider Git local dans `PersistentGitContextSourceProvider`, un cache **mémoire uniquement** limité à 16 résultats en LRU.

La CLI et les usages one-shot continuent d'utiliser `NexusApplication.create(...)` et `LocalGitContextSourceProvider` sans cache persistant entre requêtes.

Avant chaque hit, le cache recalcule un fingerprint borné :

```text
worktree réel
HEAD exact
status des chemins cibles
SHA-256 du diff staged ciblé
SHA-256 du diff unstaged ciblé
```

Toute variation provoque un miss puis un recalcul via `LocalGitContextSourceProvider`. Cela couvre notamment commit/rebase, index, working tree, rename et isolation des worktrees liés.

La validation du fingerprint consomme elle aussi le `ContextDiscoveryBudget` via des visites et checkpoints ; le cache n'introduit donc aucun chemin de découverte hors budget. Un résultat `repositoryAvailable=false` n'est jamais conservé afin de retenter automatiquement une indisponibilité transitoire.

Aucun fichier de cache, schéma SQLite, watcher filesystem ou thread de maintenance n'est ajouté. Voir ADR-0046.

## Diff local avant allocation

Les patches working-tree sont filtrés aux chemins cibles puis écrits dans `BoundedOutput`, un `OutputStream` à capacité fixe. Le sink cesse de retenir des octets après sa capacité ; NEXUS ne rend donc pas d'abord le patch entier dans un `ByteArrayOutputStream` extensible avant de le tronquer.

Après conversion UTF-8, le texte reste limité à 6 000 caractères et reçoit :

```text
... [diff Git tronqué par NEXUS]
```

si la sortie a dépassé l'une des bornes.

## Fragments

Le provider peut créer :

```text
.nexus/git/recent-commits.md
.nexus/git/file-history.md
.nexus/git/working-tree-diff.md
.nexus/git/co-changes.md
```

Un fichier non ciblé n'est pas injecté dans le patch/statut. Dans un monorepo, les chemins hors racine du projet NEXUS sont exclus du contexte projet.

## Budget final

Le contexte Git n'est activé que lorsque le budget global le permet et reçoit un sous-budget final. Ce sous-budget de tokens est distinct du budget de **travail de découverte**, qui reste obligatoire avant sélection.

## Preuves

`LocalGitContextSourceProviderTest` couvre :

- commits/historique/co-changements ciblés ;
- monorepo et exclusion hors projet ;
- dégradation hors Git ;
- diff massif réellement tronqué ;
- sink fixe qui ne retient jamais plus d'octets que sa capacité.

`PersistentGitContextSourceProviderTest` couvre :

- hit sur état Git stable ;
- invalidation working tree, index et HEAD ;
- éviction LRU et capacité stricte ;
- absence de cache pour un repository indisponible.

`GitContextCacheQualificationBenchmarkTest` couvre Linux et Windows sur plusieurs repositories ainsi que HEAD/index/working-tree/rename/rebase/worktrees liés. Les benchmarks et tests exact-head restent l'autorité de qualification.
