# Pré-version `0.2.1-rc.1` immuable (2026-11-11)

Relevé de la tâche 2.5 du changement OpenSpec `sprint-14-plafond-charge-et-release-immuable` (`AUD-DEP-37`) : première release publiée après l'activation des releases immuables et des deux rulesets de tags, lue par `gh api` le 2026-11-11 à 19:01 UTC. Les réglages lus avant cette release sont consignés dans [releases-immuables-et-tags-reglages-2026-11-11.md](releases-immuables-et-tags-reglages-2026-11-11.md).

## Pré-version publiée

| Élément | Valeur |
|---|---|
| Tag | `v0.2.1-rc.1`, objet tag `56a3353040bc4dd2c93c4ce317a97ab1589301d0` |
| Commit de `main` | `037c9a0154c1ee693f314fb3e3e1621dc86e537c` (promotion FTurleque/nexus-context-engine#375) |
| Run `release.yml` | [43080652705](https://github.com/FTurleque/nexus-context-engine/actions/runs/43080652705), tentative 1, conclusion `success` |
| Release | [v0.2.1-rc.1](https://github.com/FTurleque/nexus-context-engine/releases/tag/v0.2.1-rc.1), publiée le 2026-11-11T19:01:39Z |
| Image | `ghcr.io/fturleque/nexus-context-engine:0.2.1-rc.1`, digest `sha256:ddb06947a60bebab163710fd3f80aa2ee42800cd759a48277c219cea7317ab69` |

Le push du tag a été accepté par le ruleset « Protect tags » (création réservée à l'administrateur) : scénario « Création d'un tag de release ».

## Immuabilité et pré-version

```text
$ gh api repos/FTurleque/nexus-context-engine/releases/tags/v0.2.1-rc.1 --jq '{tag_name, immutable, prerelease, draft, published_at}'
{"draft":false,"immutable":true,"prerelease":true,"published_at":"2026-11-11T19:01:39Z","tag_name":"v0.2.1-rc.1"}
```

Le job « Publish immutable release » a réussi : `scripts/release/publish-github-release-assets.sh` sort en code 79 quand la release publiée n'est pas immuable.

Assets de la release :

```text
NEXUS-0.2.1-rc.1-windows-x64-setup.exe
NEXUS-0.2.1-rc.1-windows-x64-setup.exe.sha256
nexus-context-engine-0.2.1-rc.1-windows-x64.zip
nexus-context-engine-0.2.1-rc.1-windows-x64.zip.sha256
nexus-image.cdx.json
nexus-windows-packaged.cdx.json
nexus-windows-trivy.cdx.json
trivy-image-vulnerabilities.json
trivy-windows-vulnerabilities.json
```

## `latest` inchangé

```text
$ gh api repos/FTurleque/nexus-context-engine/releases/latest --jq '{tag_name, immutable}'
{"immutable":false,"tag_name":"v0.2.0"}
$ docker buildx imagetools inspect ghcr.io/fturleque/nexus-context-engine:latest
Digest:    sha256:d81b034d372782528a0e2a221f1323eac6b8b43b7ca63c1913d3da3a71b92762
```

La release `latest` et le tag d'image `latest` désignent toujours `0.2.0`.

## Provenance de l'image

```text
$ gh attestation verify oci://ghcr.io/fturleque/nexus-context-engine:0.2.1-rc.1 \
    --repo FTurleque/nexus-context-engine \
    --signer-workflow FTurleque/nexus-context-engine/.github/workflows/release.yml
```

| Résultat | Signataire | Commit source |
|---|---|---|
| réussite | `.github/workflows/release.yml@refs/tags/v0.2.1-rc.1` | `037c9a0154c1ee693f314fb3e3e1621dc86e537c` |
