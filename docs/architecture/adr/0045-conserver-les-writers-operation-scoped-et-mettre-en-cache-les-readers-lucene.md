---
status: accepted
date: 2026-09-02
---

# ADR-0045 — Conserver les writers Lucene operation-scoped et mettre en cache les readers

## Contexte et problème

NEXUS traite Lucene comme un index local dérivé et reconstructible. Jusqu'ici, les index lexical et sémantique ouvrent puis ferment `FSDirectory`, `DirectoryReader`, `IndexWriter` et analyseur à chaque opération.

Le watch item #50 a demandé de ne pas introduire d'état Lucene persistant sans preuve mesurée. La PR #159 a ajouté un benchmark hermétique ABBA sur corpus lexical et sémantique, avec mesures p50/p95, micro-écritures, ressources et rollback/rebuild. Les résultats exact-head ont montré un gain p95 de recherche très important et reproductible : environ 85 % lexical / 87 % sémantique sous Linux et 79 % lexical / 92 % sémantique sous Windows. Le prototype persistant a également satisfait le recovery.

Cependant, conserver un `IndexWriter` ouvert dans les processus longue durée REST ou MCP maintiendrait le write-lock Lucene pendant toute la vie du processus. Cela contredirait le modèle actuel : les mutations sont sérialisées inter-processus par `ProjectIndexLockManager`, puis les writers Lucene sont libérés à la fin de chaque mutation. Deux processus NEXUS partageant un `NEXUS_HOME` doivent pouvoir muter le même projet successivement après libération du verrou NEXUS.

La question est donc : comment conserver l'essentiel du gain de recherche sans transformer un cache dérivé local en verrou propriétaire de longue durée et sans fragiliser le rebuild, notamment sous Windows ?

## Facteurs de décision

- gain p95 de recherche mesuré sur Linux et Windows ;
- préservation du verrouillage inter-processus existant ;
- fraîcheur immédiate après mutation locale et détection des commits d'un autre processus ;
- recovery et rebuild d'un index dérivé, y compris après corruption sémantique ;
- libération déterministe des handles sous Windows ;
- borne explicite sur le nombre de projets gardés chauds ;
- absence de thread de maintenance ou de nouvelle dépendance ;
- possibilité de revenir à l'implémentation operation-scoped comme fallback sûr.

## Options envisagées

- A — conserver intégralement le lifecycle operation-scoped ;
- B — conserver readers et writers persistants par projet ;
- C — conserver uniquement les readers/searchers persistants, writers operation-scoped ;
- D — introduire un service Lucene externe.

## Décision retenue

**Option retenue : C — readers/searchers persistants bornés, writers operation-scoped.**

NEXUS conserve les implémentations operation-scoped comme baseline/fallback et ajoute des implémentations de production qui mettent en cache, par projet chaud, un `SearcherManager` et son `Directory`.

Les writers restent ouverts uniquement pendant `applyChanges` ou `rebuild`, puis sont fermés. Le `ProjectIndexLockManager` reste ainsi l'autorité de sérialisation des mutations inter-processus.

Avant chaque recherche servie par le cache, le `SearcherManager` est rafraîchi afin d'observer un commit local ou externe. Après une mutation locale, le reader caché est également rafraîchi. Un rebuild invalide le reader du projet avant la reconstruction ; le rebuild sémantique peut ainsi supprimer et recréer son répertoire dérivé sans handle persistant concurrent.

Le cache est borné à 100 projets par index, valeur alignée sur la cardinalité maximale d'une portée fédérée. Quand la capacité est atteinte pour un nouveau projet, la recherche retombe sur l'implémentation operation-scoped au lieu d'accumuler de nouveaux handles. La correction prime donc sur le taux de hit du cache.

La persistance est activée uniquement par la composition `NexusApplication.createLongLived(...)`, destinée aux processus REST et MCP. `NexusApplication.create(...)` reste operation-scoped pour la CLI, les commandes one-shot et les tests historiques. La façade est `AutoCloseable` et possède explicitement les index lorsqu'une composition longue durée est utilisée.

### Conséquences positives

- conservation de l'essentiel du gain de recherche mesuré pour les serveurs longue durée ;
- aucun write-lock Lucene gardé pendant la vie de REST/MCP ;
- compatibilité avec les mutations inter-processus existantes ;
- cache borné et fallback operation-scoped ;
- fermeture explicite, testable et portable ;
- aucun nouveau file-lock pour la CLI et les compositions operation-scoped ;
- rebuild sémantique #54 préservé ;
- aucune nouvelle dépendance ni thread de refresh.

### Conséquences négatives et compromis acceptés

- la composition longue durée de `NexusApplication` acquiert un lifecycle de fermeture explicite ;
- les tests qui utilisent `createLongLived(...)` doivent fermer la façade avant nettoyage des répertoires temporaires ;
- un projet au-delà de la capacité du cache ne bénéficie pas de la persistance tant qu'une politique d'éviction plus sophistiquée n'est pas justifiée ;
- le refresh avant recherche ajoute un faible coût de vérification de commit ;
- les writers ne capturent pas le gain de micro-écriture du prototype persistant, afin de préserver le multi-processus.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Reader stale après mutation externe | Élevé | `SearcherManager.maybeRefreshBlocking()` avant recherche |
| Handle Windows empêchant un rebuild | Élevé | invalidation/fermeture du reader avant rebuild |
| Accumulation de readers | Moyen | capacité fixe de 100 projets par index, fallback operation-scoped au-delà |
| Fermeture concurrente avec une recherche | Élevé | verrou lecture/écriture par ressource et fermeture déterministe |
| Régression multi-processus | Élevé | aucun `IndexWriter` persistant ; tests de lock/rebuild et qualification Windows |
| File-lock dans les commandes courtes/tests | Moyen | `create(...)` reste operation-scoped ; cache réservé à `createLongLived(...)` |
| Régression performance | Moyen | workflow `Lucene Lifecycle Qualification` Linux + Windows |

### Confirmation

La décision est confirmée par :

- tests de fraîcheur après update ;
- test de commit externe observé après refresh ;
- tests de rebuild avec reader chaud ;
- test de fermeture permettant suppression du répertoire sous Windows ;
- test de borne du cache ;
- tests prouvant que la composition longue durée est utilisée par REST/MCP ;
- qualification Linux + Windows du benchmark lifecycle ;
- `Scale Benchmark`, NEXUS CI, Windows Installer, CodeQL, OSV et Docker Distribution ;
- inspection de l'absence de writer persistant dans les classes de cache.

## Analyse détaillée des options

### Option A — tout operation-scoped

**Avantages :** simplicité maximale, aucun état à fermer, excellente isolation multi-processus.

**Inconvénients :** coût répété d'ouverture des readers confirmé comme matériel par #159 ; p95 de recherche sensiblement plus élevé dans les processus longue durée.

### Option B — readers et writers persistants

**Avantages :** meilleur résultat brut du prototype sur recherches et micro-écritures.

**Inconvénients :** le writer maintient le verrou Lucene pendant la vie du processus et concurrence le modèle de lock NEXUS ; recovery et shutdown deviennent plus couplés. Rejetée malgré les chiffres de micro-écriture.

### Option C — readers persistants, writers operation-scoped

**Avantages :** cible directement le principal gain mesuré ; préserve le verrouillage inter-processus ; s'appuie sur `SearcherManager` pour le partage thread-safe et le refresh.

**Inconvénients :** lifecycle de fermeture et cache borné à gérer ; le gain des writers persistants n'est pas adopté.

### Option D — service de recherche externe

**Avantages :** lifecycle et concurrence délégués à un moteur spécialisé.

**Inconvénients :** contredit le positionnement local-first, ajoute déploiement, réseau et dépendance opérationnelle disproportionnés au besoin.

## Impacts sur l'architecture

```text
NexusApplication.create(...)                 -> indexes operation-scoped (CLI / one-shot / tests)
NexusApplication.createLongLived(...)        -> owner / AutoCloseable (REST / MCP)
    ├── PersistentLuceneSearchIndex
    │     ├── BoundedLuceneSearcherCache (readers)
    │     └── LuceneSearchIndex (writers operation-scoped / fallback)
    └── PersistentLuceneSemanticSearchIndex
          ├── BoundedLuceneSearcherCache (readers)
          └── LuceneSemanticSearchIndex (writers operation-scoped / recovery)
```

REST ferme la façade lors de la destruction du bean application-scoped. MCP ferme serveur et façade dans son shutdown hook. La CLI conserve la composition operation-scoped et n'ouvre donc aucun reader persistant entre commandes.

## Conditions de réexamen

La décision doit être réévaluée si :

- les mesures montrent que `maybeRefreshBlocking()` annule le gain sur des corpus représentatifs ;
- la limite de 100 projets chauds devient insuffisante pour les usages réels ;
- un besoin justifié de writers persistants apparaît avec un protocole multi-processus compatible ;
- Lucene modifie substantiellement le lifecycle ou les garanties de `SearcherManager` ;
- un service de recherche externe devient nécessaire pour un changement d'échelle prouvé.

## Décisions liées

- ADR-0007 — Utiliser Apache Lucene comme index de recherche local.
- ADR-0014 — Rendre la recherche sémantique et les embeddings optionnels.
- ADR-0022 — Traiter Lucene comme un index dérivé et reconstructible de SQLite.
- ADR-0043 — Fédérer la recherche locale par projet avant un moteur externe.

## Références

- Issue #50 — benchmark persistent Lucene reader/writer lifecycle.
- PR #159 — qualification ABBA Linux/Windows du lifecycle Lucene.
- PR #161 — préparation des wrappers/cache reader-only.
- Documentation Apache Lucene 10.5.1 — `SearcherManager` / `ReferenceManager`.
