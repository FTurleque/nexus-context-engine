# Préflight GHCR de la release `0.2.0` (2026-11-10)

Relevé de la tâche 4.2 du changement OpenSpec `sprint-13-installateur-et-release-finale` (`AUD-DEP-34`) : origine des versions du paquet `ghcr.io/fturleque/nexus-context-engine` qui portaient `0.2.0` et `latest`, décision prise et état du registre avant la release finale.

## Origine des tags

| Élément | Valeur |
|---|---|
| Version de paquet | `1113962027`, créée le 2026-08-09T14:40:23Z |
| Tags | `latest`, `0.2.0` |
| Digest | `sha256:fbe2256df0f44285ba9301f977b13bd47c4b605744f0ed6869a5b7491a4bd9bc` |
| Signataire de l'attestation | `.github/workflows/docker-distribution.yml@refs/heads/main`, commit `3326a848` |

`gh attestation verify oci://ghcr.io/fturleque/nexus-context-engine:latest --repo FTurleque/nexus-context-engine --signer-workflow FTurleque/nexus-context-engine/.github/workflows/release.yml` échouait sur cette image : elle n'avait pas été publiée par `release.yml`.

## Décision

Les versions d'août sont supprimées ; la release finale publie sous `0.2.0` et `latest` depuis `release.yml`. La version annoncée par la roadmap reste `0.2.0`. Un utilisateur qui a tiré `0.2.0` en août obtiendra une autre image sous ce tag après la release.

## Versions supprimées

Onze versions, toutes créées en août 2026 :

| Version | Création | Nature |
|---|---|---|
| `1113962027` | 2026-08-09T14:40:23Z | image `sha256:fbe2256d…`, tags `latest`, `0.2.0` |
| `1113962311`, `1113962490` | 2026-08-09T14:40 | bundles Sigstore de `sha256:fbe2256d…` |
| `1113962322`, `1113962502` | 2026-08-09T14:40 | index des référents de `sha256:fbe2256d…` |
| `1113787063` | 2026-08-09T13:02:23Z | image `sha256:da69ef69…`, sans tag |
| `1113787759`, `1113788035` | 2026-08-09T13:02 / 13:03 | bundles Sigstore de `sha256:da69ef69…` |
| `1113787765`, `1113788043` | 2026-08-09T13:02 / 13:03 | index des référents de `sha256:da69ef69…` |
| `1112145578` | 2026-08-08T18:32:23Z | image `sha256:91a4c4ba…`, sans tag |

Les pré-versions `0.2.0-rc.2` et `0.2.0-rc.3` et leurs attestations sont conservées.

## État du registre après suppression

```text
$ docker buildx imagetools inspect ghcr.io/fturleque/nexus-context-engine:0.2.0
ERROR: ghcr.io/fturleque/nexus-context-engine:0.2.0: not found
$ docker buildx imagetools inspect ghcr.io/fturleque/nexus-context-engine:latest
ERROR: ghcr.io/fturleque/nexus-context-engine:latest: not found
```

Le préflight immuable du run de release `v0.2.0` trouve le tag de version absent. La vérification de provenance de la tâche 4.3 se fait après ce run.
