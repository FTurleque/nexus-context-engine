---
status: accepted
date: 2026-09-02
---

# ADR-0046 — Mettre en cache le contexte Git dans les runtimes longue durée

## Contexte et problème

Le contexte Git local est actuellement recalculé à chaque construction de contexte. Cette stratégie est simple et sûre, mais rescane une fenêtre bornée allant jusqu'à 50 commits et recalcule status/diffs ciblés à chaque requête.

Le watch item #53 interdisait l'introduction d'un cache persistant sans preuve multi-repository, modèle d'invalidation et stockage borné. La PR #163 a ajouté une qualification hermétique Linux/Windows sur 6 repositories × 24 commits, avec invalidation HEAD, index, working tree, rename, rebase et worktree lié.

Les résultats exact-head ont montré :

- Linux : p95 warm 14,375 ms → 3,910 ms, soit 72,80 % d'amélioration ;
- Windows : p95 warm 55,686 ms → 19,528 ms, soit 64,93 % d'amélioration ;
- toutes les invalidations : PASS ;
- stockage borné : PASS ;
- `persistentCacheCandidate=true` sur les deux OS.

La question est donc d'adopter ce gain sans introduire de format disque, de watcher, de thread de fond ou de contexte Git stale.

## Facteurs de décision

- gain p95 confirmé sur Linux et Windows ;
- aucune persistance disque supplémentaire ;
- invalidation déterministe avant chaque hit ;
- isolation correcte des repositories et worktrees liés ;
- conservation du provider local comme source de vérité ;
- consommation du `ContextDiscoveryBudget` pendant la validation du cache ;
- mémoire strictement bornée ;
- activation limitée aux processus longue durée REST/MCP ;
- CLI et usages one-shot inchangés.

## Options envisagées

- A — conserver le recalcul systématique ;
- B — cache disque partagé ;
- C — cache mémoire borné par processus longue durée ;
- D — watcher Git/FS avec invalidation événementielle.

## Décision retenue

**Option retenue : C — cache mémoire borné par processus longue durée.**

`PersistentGitContextSourceProvider` encapsule `LocalGitContextSourceProvider` et ne remplace jamais ce dernier comme source de vérité. Un résultat n'est réutilisé que si un fingerprint calculé juste avant le hit est inchangé.

Le fingerprint comprend :

- worktree réel ;
- HEAD exact ;
- status ciblé ;
- digest SHA-256 du diff staged ciblé ;
- digest SHA-256 du diff unstaged ciblé.

La clé comprend la racine projet, la requête, les chemins cibles normalisés et le mode `explain`. Les worktrees liés restent donc isolés même lorsqu'ils partagent le même repository Git.

La validation du fingerprint consomme explicitement le `ContextDiscoveryBudget` via des visites/checkpoints. Le cache ne crée donc pas un chemin de travail hors budget.

La capacité par défaut est fixée à **16 résultats**, identique au prototype qualifié par #163. L'éviction est LRU par ordre d'accès. Un résultat indiquant un repository indisponible n'est jamais mis en cache afin qu'une indisponibilité transitoire soit retentée à la requête suivante.

La persistance signifie ici **persistance en mémoire entre requêtes d'un même processus**, et non persistance sur disque. Aucun fichier, schéma SQLite ou protocole inter-processus supplémentaire n'est ajouté.

Le cache est activé uniquement dans `NexusApplication.createLongLived(...)`, déjà utilisé par REST et MCP. `NexusApplication.create(...)` conserve `LocalGitContextSourceProvider` pour la CLI, les commandes one-shot et les tests qui ne demandent pas explicitement le runtime longue durée.

### Conséquences positives

- réduction p95 substantielle et mesurée du contexte Git warm ;
- aucune donnée dérivée supplémentaire sur disque ;
- invalidation avant chaque hit, sans TTL arbitraire ;
- comportement portable Linux/Windows ;
- mémoire bornée et éviction déterministe ;
- worktrees et sous-projets restent isolés ;
- fallback naturel vers le provider local si le fingerprint ne peut pas être établi.

### Conséquences négatives et compromis acceptés

- chaque hit paie toujours le coût d'un status ciblé et de deux diffs digestés ;
- la clé inclut la chaîne de requête, ce qui limite le taux de hit lorsque les requêtes changent fortement ;
- le cache est local au processus et n'est pas partagé entre REST et MCP ;
- 16 entrées peuvent provoquer du churn sur des workloads très variés ;
- un changement concurrent pendant le calcul peut provoquer un miss/recalcul supplémentaire, mais pas une persistance stale durable.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Contexte stale après commit/rebase | Élevé | HEAD inclus dans le fingerprint |
| Contexte stale après staged/unstaged | Élevé | status + deux digests ciblés avant hit |
| Confusion entre worktrees | Élevé | worktree réel dans fingerprint + racine projet dans clé |
| Mémoire non bornée | Moyen | capacité fixe 16 + LRU |
| Indisponibilité Git cachée | Moyen | résultats `repositoryAvailable=false` jamais conservés |
| Contournement du budget | Élevé | visites/checkpoint `ContextDiscoveryBudget` pendant fingerprint |
| Régression CLI | Faible | activation uniquement via `createLongLived(...)` |

## Confirmation

La décision est confirmée par :

- benchmark #163 Linux/Windows ;
- tests unitaires hit/miss, invalidation working tree/index/HEAD et borne LRU ;
- tests benchmark rebase/rename/worktree ;
- NEXUS CI Linux/Windows ;
- Git Context Cache Qualification ;
- SonarQube Cloud, CodeQL et OSV.

## Conditions de réexamen

Réévaluer cette décision si :

- la capacité 16 provoque un churn réel mesurable ;
- la validation status/diff devient plus coûteuse que le recalcul sur les workloads réels ;
- un partage inter-processus devient nécessaire ;
- JGit fournit un mécanisme de snapshot/invalidation plus sûr et moins coûteux ;
- les résultats du provider commencent à dépendre d'autres entrées que projet/requête/chemins/état Git.

## Décisions liées

- ADR-0005 — fonctionnement local-first et opt-in.
- ADR-0045 — lifecycle Lucene long-lived borné.

## Références

- Issue #53 — measure persistent Git context caching.
- PR #163 — qualification Linux/Windows du cache Git.
