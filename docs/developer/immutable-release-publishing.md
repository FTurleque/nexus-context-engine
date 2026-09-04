# Publication de release immuable

Ce document décrit le contrat NXA3 courant : **construire une fois, qualifier cette image exacte, puis publier cette même image sans rebuild**.

## Signal de release

`.github/workflows/release.yml` est le seul workflow autorisé à publier dans GHCR. Il est déclenché par un tag Git :

```text
v<major>.<minor>.<patch>
```

Avant toute publication, le workflow vérifie :

- SemVer strict `vX.Y.Z` ;
- égalité avec la version racine de `pom.xml` ;
- commit taggé égal au HEAD courant de `main` ;
- succès des gates release sur ce commit exact.

## Build unique et handoff intègre

`.github/workflows/docker-distribution.yml` construit l'image une seule fois puis exécute sur cette même image :

1. smokes CLI, MCP et REST ;
2. qualification Docker/Compose ;
3. Trivy ;
4. SBOM CycloneDX ;
5. gate HIGH/CRITICAL corrigibles.

Lorsqu'il est appelé par la release avec `export_qualified_image: true`, le workflow exporte ensuite l'**image exacte déjà qualifiée** avec :

- archive `docker save` ;
- SHA-256 de l'archive ;
- ID de configuration Docker ;
- SHA Git qualifié.

Le job de publication télécharge cet artefact, vérifie le hash et l'ID après `docker load`, puis publie cette image. `release.yml` ne contient aucun second `docker build`.

## Tags GHCR

Pour une version `0.2.0` sur le commit `<sha>` :

```text
ghcr.io/fturleque/nexus-context-engine:0.2.0
ghcr.io/fturleque/nexus-context-engine:sha-<sha>
ghcr.io/fturleque/nexus-context-engine:latest
```

Les tags version et SHA sont immuables. `latest` est volontairement mutable.

## Préflight fail-closed et reprise idempotente

`scripts/release/ghcr-immutable-preflight.sh` distingue explicitement :

- manifeste réellement absent : création permise ;
- erreur d'authentification, réseau, timeout, serveur ou réponse ambiguë : échec ;
- tag existant qui correspond à l'image qualifiée : **reprise idempotente** permise ;
- tag existant qui correspond à un autre contenu : conflit d'immutabilité, échec définitif.

Si les tags version et SHA existent tous deux, ils doivent résoudre le même digest. Une exécution interrompue après la création d'un seul tag peut donc reprendre sans écraser un tag historique : le contenu existant est validé, puis seul le tag manquant est créé à partir de la référence immuable déjà vérifiée.

## Digest et attestations

Après publication :

- les tags version et SHA sont relus ;
- ils doivent désigner le même digest ;
- le digest doit correspondre à l'image qualifiée ;
- les attestations de provenance et de SBOM portent sur ce digest ;
- `latest` n'est déplacé qu'après ces vérifications.

Le digest publié est l'identité de référence du conteneur. Le triplet de traçabilité est :

```text
Git tag vX.Y.Z
Git commit SHA
GHCR digest sha256:...
```

## Échec partiel

La publication est fail-closed. Un retry ne masque jamais une divergence :

- même contenu déjà publié ⇒ reprise autorisée ;
- contenu différent sous un tag immuable ⇒ arrêt ;
- registry ambigu ou indisponible ⇒ arrêt.

Pour corriger une release dont le contenu doit changer, créer une nouvelle version SemVer. Ne jamais déplacer un ancien tag version ou SHA.

## Rollback

Un rollback de consommation pointe vers un tag SemVer historique ou, de préférence, vers son digest GHCR. Les tags immuables ne sont jamais déplacés pour simuler un rollback.

Voir aussi [`ci-and-supply-chain.md`](ci-and-supply-chain.md) et [`release-and-recovery.md`](release-and-recovery.md).
