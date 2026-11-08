# Répétition de release `0.2.0-rc.2` (2026-11-08)

Relevé de la tâche 9.4 du changement OpenSpec `sprint-09-chaine-de-release-executable` : publication d'une pré-version par la chaîne de release, depuis l'environnement protégé `release`, et vérification de ses attestations.

## Pré-version publiée

| Élément | Valeur |
|---|---|
| Tag | `v0.2.0-rc.2` |
| Commit de `main` | `38e1b95b52de436a25c8aa3cc9d9986572879ac2` (promotion FTurleque/nexus-context-engine#318) |
| Run `release.yml` | [42529376520](https://github.com/FTurleque/nexus-context-engine/actions/runs/42529376520), conclusion `success` |
| Approbation de l'environnement `release` | `approved`, par FTurleque |
| Release | [v0.2.0-rc.2](https://github.com/FTurleque/nexus-context-engine/releases/tag/v0.2.0-rc.2), publiée le 2026-11-08T22:37:12Z |
| `isPrerelease` | `true` |
| Image | `ghcr.io/fturleque/nexus-context-engine:0.2.0-rc.2`, digest `sha256:014bdc0a25d7de239925696040d7db0b97a8d8f56e874a42fa82a71e8484ca13` |

Assets de la release :

```text
NEXUS-0.2.0-rc.2-windows-x64-setup.exe            98574485 octets
NEXUS-0.2.0-rc.2-windows-x64-setup.exe.sha256
nexus-context-engine-0.2.0-rc.2-windows-x64.zip  168873001 octets
nexus-context-engine-0.2.0-rc.2-windows-x64.zip.sha256
nexus-image.cdx.json
nexus-windows-packaged.cdx.json
nexus-windows-trivy.cdx.json
trivy-image-vulnerabilities.json
trivy-windows-vulnerabilities.json
```

Les empreintes SHA-256 du setup et du ZIP téléchargés correspondent à leurs fichiers `.sha256`.

## Attestations

`gh attestation verify <artefact> --repo FTurleque/nexus-context-engine --format json` réussit (code 0) pour les trois artefacts. Chaque attestation est unique et porte la même provenance :

| Artefact | Prédicat | Workflow signataire | Ref | Commit |
|---|---|---|---|---|
| `NEXUS-0.2.0-rc.2-windows-x64-setup.exe` | `https://slsa.dev/provenance/v1` | `.github/workflows/release.yml@refs/tags/v0.2.0-rc.2` | `refs/tags/v0.2.0-rc.2` | `38e1b95b` |
| `nexus-context-engine-0.2.0-rc.2-windows-x64.zip` | `https://slsa.dev/provenance/v1` | `.github/workflows/release.yml@refs/tags/v0.2.0-rc.2` | `refs/tags/v0.2.0-rc.2` | `38e1b95b` |
| `oci://ghcr.io/fturleque/nexus-context-engine:0.2.0-rc.2` | `https://slsa.dev/provenance/v1` | `.github/workflows/release.yml@refs/tags/v0.2.0-rc.2` | `refs/tags/v0.2.0-rc.2` | `38e1b95b` |

## `latest` inchangé

- Release GitHub : `GET /repos/FTurleque/nexus-context-engine/releases/latest` répond 404. Aucune release finale n'existe, et la pré-version n'est pas marquée `latest`.
- Image : `ghcr.io/fturleque/nexus-context-engine:latest` a le digest `sha256:fbe2256df0f44285ba9301f977b13bd47c4b605744f0ed6869a5b7491a4bd9bc`, celui de `:0.2.0`. Ce n'est pas le digest de `:0.2.0-rc.2`.

## Environnement et tags protégés (tâches 3.1 et 3.3)

`gh api repos/FTurleque/nexus-context-engine/environments` :

```json
{"name":"release","protection_rules":[{"type":"branch_policy"},{"type":"required_reviewers","reviewers":["FTurleque"]}],"deployment_branch_policy":{"custom_branch_policies":true,"protected_branches":false}}
```

- Politique de déploiement : un seul motif, `{"name":"v*","type":"tag"}`.
- Secrets de l'environnement : 0.

`gh api repos/FTurleque/nexus-context-engine/rulesets` :

```json
{"name":"Protect main & develop","target":"branch","enforcement":"active"}
{"name":"Protect tags","target":"tag","enforcement":"active"}
```

## Tag `v0.2.0-rc.1`

Le tag `v0.2.0-rc.1` a été posé le même jour sur le commit `5a38107f` de `main`. Ses portes de qualification ont réussi dans le [run 42489170512](https://github.com/FTurleque/nexus-context-engine/actions/runs/42489170512), après la relance d'un job : Maven Central avait refusé le téléchargement de Maven (HTTP 429). Sa publication n'a pas été approuvée. L'installeur Windows de ce commit ne reliait pas correctement les clients IA. La répétition a donc été menée avec `0.2.0-rc.2`, qui contient les correctifs FTurleque/nexus-context-engine#311 à FTurleque/nexus-context-engine#317.
