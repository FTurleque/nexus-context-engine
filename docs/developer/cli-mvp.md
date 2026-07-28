# CLI du MVP — document historique

> **Document historique.** Cette page décrit le contrat validé lors de l'Itération 4, le 19 juillet 2026. Elle ne représente plus toute la surface actuelle de NEXUS.

Pour la CLI courante, voir [`cli.md`](cli.md).

## But historique

L'Itération 4 devait prouver qu'un premier moteur NEXUS utilisable de bout en bout pouvait fonctionner depuis un JAR autonome :

```text
project add
→ index
→ search
→ context
→ inspect
```

avec :

- sortie humaine ;
- sortie JSON ;
- codes de sortie stables ;
- SQLite et Lucene dans le flux réel ;
- ranking explicable ;
- `ContextBundle` sous budget ;
- self-smoke exécuté sur le JAR autonome.

## Contrat MVP validé

La surface de l'époque était :

```text
nexus project add <chemin> [nom] [--json]
nexus project list [--json]
nexus index <id-ou-nom> [--rebuild] [--json]
nexus search <id-ou-nom> <requête> [--limit N] [--explain] [--json]
nexus context <id-ou-nom> <requête> [--budget N] [--explain] [--json]
nexus inspect <id-ou-nom> [--json]
nexus --help [--json]
nexus --version [--json]
```

Depuis, la CLI a évolué avec notamment :

```text
index --deep-java
minos-import
```

Les intégrations REST, MCP, multi-langage, skills registry, recherche fédérée et recherche sémantique qui étaient hors du MVP ont également été livrées dans des itérations ultérieures.

## Codes de sortie hérités

| Code | Signification |
|---:|---|
| `0` | succès |
| `1` | erreur d'exécution |
| `2` | erreur d'usage ou d'arguments |

`--json` reste une option globale et le principe `stdout` pour le résultat / `stderr` pour les erreurs reste à préserver.

## Packaging MVP

L'Itération 4 a établi deux artefacts Maven :

```text
target/nexus-context-engine-0.1.0-SNAPSHOT.jar
target/nexus-context-engine-0.1.0-SNAPSHOT-cli.jar
```

et les launchers :

```text
scripts/nexus.ps1
scripts/nexus.cmd
```

Cette distribution reste orientée développement. La Phase 6 planifie une installation versionnée sans clone du repository.

## Validation historique

La validation de référence de l'Itération 4 était :

```text
66 sources principales
11 fichiers de tests
16 tests
0 échec
0 erreur
0 ignoré

77 fichiers indexés
322 symboles
599 relations
indexation complète     896 ms
indexation incrémentale 232 ms
recherche                254 ms
contexte                 285 ms
3 items
178/180 tokens
SELF-SMOKE SUCCESS
```

Baseline qualité historique :

```text
mean precision@3 = 0,4444
mean recall@3    = 1,0000
```

Ces valeurs sont des preuves historiques du MVP, pas les métriques de l'exact head actuel.

## Frontière architecturale héritée

Le MVP a établi la règle toujours valable :

```text
le moteur calcule
l'adaptateur transporte/rend
```

La CLI ne doit donc pas déplacer le ranking, l'indexation ou la sélection sous budget dans ses handlers.

L'implémentation actuelle possède encore un composition root dupliqué dans `NexusCli`; sa centralisation dans `NexusApplication` est planifiée en Itération 20.

## Où trouver l'état actuel

- CLI courante : [`cli.md`](cli.md)
- architecture : [`../architecture.md`](../architecture.md)
- limites : [`current-limitations.md`](current-limitations.md)
- roadmap : [`../roadmap.md`](../roadmap.md)
