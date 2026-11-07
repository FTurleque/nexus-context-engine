# Publication de release immuable

Ce document décrit le contrat NXA3 courant : **construire une fois, qualifier cette image exacte, puis publier cette même image sans rebuild**.

## Signal de release

`.github/workflows/release.yml` est le seul workflow autorisé à publier dans GHCR. Il est déclenché par un tag Git :

```text
v<major>.<minor>.<patch>
v<major>.<minor>.<patch>-rc.<n>
```

Avant toute publication, le workflow vérifie (`scripts/release/validate-release-tag.sh`) :

- tag `vX.Y.Z` (version finale) ou `vX.Y.Z-rc.N` avec `N >= 1` sans zéro initial (pré-version) ;
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

Les tags version et SHA sont immuables. `latest` est volontairement mutable et ne désigne qu'une version finale : pour un tag `vX.Y.Z-rc.N`, la release GitHub est créée en pré-version (`--prerelease --latest=false`), les tags version et SHA sont publiés et `latest` reste inchangé.

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

## Vérification consommateur

Chaque asset Windows publié (ZIP portable `nexus-context-engine-<version>-windows-x64.zip` et installeur `NEXUS-<version>-windows-x64-setup.exe`) porte une attestation de provenance SLSA produite par `actions/attest` dans le job `publish` de `release.yml`. Le job vérifie chaque attestation par `gh attestation verify` avant tout upload d'asset et avant le déplacement de `latest` : une vérification en échec arrête la publication.

Après téléchargement depuis la release GitHub, un consommateur vérifie l'asset avec la GitHub CLI :

```text
gh attestation verify <asset> --repo FTurleque/nexus-context-engine
```

Pour exiger en plus que l'attestation provienne du workflow `Release` :

```text
gh attestation verify NEXUS-<version>-windows-x64-setup.exe \
  --repo FTurleque/nexus-context-engine \
  --signer-workflow FTurleque/nexus-context-engine/.github/workflows/release.yml
```

L'image se vérifie de la même façon par son digest :

```text
gh attestation verify oci://ghcr.io/fturleque/nexus-context-engine@sha256:<digest> --repo FTurleque/nexus-context-engine
```

Le fichier `.sha256` co-localisé contrôle seulement l'intégrité du téléchargement ; l'attestation établit la provenance (dépôt, workflow, commit et tag). Une vérification en échec signifie que l'asset ne doit pas être installé.

## Échec partiel

La publication est fail-closed. Un retry ne masque jamais une divergence :

- même contenu déjà publié ⇒ reprise autorisée ;
- contenu différent sous un tag immuable ⇒ arrêt ;
- registry ambigu ou indisponible ⇒ arrêt.

Pour corriger une release dont le contenu doit changer, créer une nouvelle version SemVer. Ne jamais déplacer un ancien tag version ou SHA.

## Rollback

Un rollback de consommation pointe vers un tag SemVer historique ou, de préférence, vers son digest GHCR. Les tags immuables ne sont jamais déplacés pour simuler un rollback.

## Politique de publication cible (SEC-0066, proposée)

[SEC-0066](../architecture/adr/SEC-0066-publier-les-releases-depuis-un-environnement-protege-avec-des-assets-attestes.md), au statut `Proposed`, complète [GEN-0031](../architecture/adr/GEN-0031-packager-la-cli-dans-un-jar-autonome.md) et fixe les conditions de publication que met en œuvre le changement OpenSpec `sprint-09-chaine-de-release-executable`. Les workflows et scripts du dépôt portent les règles qui les concernent ; les réglages GitHub (environnement `release`, secrets d'environnement, ruleset de tags) restent à appliquer par le mainteneur et ne sont pas encore en place. L'état constaté avant le changement est relevé dans le contexte de l'ADR.

- Aucun workflow ne transmet `secrets: inherit` ; un workflow réutilisable ne reçoit, par leur nom, que les secrets qu'il déclare, y compris quand ils n'existent que dans l'environnement `release`.
- Le job `publish` et la signature de release déclarent `environment: release` : reviewer requis, déploiement limité aux tags `v*`, secrets PFX Authenticode portés par l'environnement. Aucune écriture dans GHCR ni dans les releases avant l'approbation.
- Une release demande deux approbations de l'environnement `release` : le job Windows signé, puis le job `publish`.
- L'environnement et le ruleset de tags existent avant qu'un workflow référence `release` : GitHub crée sans protection un environnement absent au premier job qui le nomme. Aucun tag `v*` ni `workflow_dispatch` avec `require_authenticode: true` n'est lancé tant que la lecture `gh api` ci-dessous ne montre pas le reviewer requis et le ruleset actif.
- Un ruleset de tags actif cible `refs/tags/v*` et réserve leur création, leur mise à jour et leur suppression au mainteneur.
- Chaque ZIP et chaque `setup.exe` publiés reçoivent une attestation de provenance (`actions/attest` en `subject-path`, même version épinglée que pour l'image), vérifiée par `gh attestation verify` avant tout upload et avant le déplacement de `latest`.
- Le tag de release est `vX.Y.Z` ou `vX.Y.Z-rc.N`, égal à la version Maven et posé sur la tête de `main`. Une pré-version publie une release marquée pré-version, sous des tags d'image version et SHA immuables, et ne déplace jamais `latest`.
- La première exécution de la chaîne est une répétition sur `X.Y.Z-rc.1`, prouvée par un relevé daté sous `docs/project/history/` (run, `gh attestation verify` sur l'image et les assets, signature Authenticode).

Les réglages GitHub (environnement, secrets d'environnement, ruleset de tags) se vérifient en lecture :

```bash
gh api repos/FTurleque/nexus-context-engine/environments --jq '.environments[] | {name, protection_rules}'
gh api repos/FTurleque/nexus-context-engine/rulesets --jq '.[] | {name, target, enforcement}'
```

Voir aussi [`ci-and-supply-chain.md`](ci-and-supply-chain.md) et [`release-and-recovery.md`](release-and-recovery.md).
