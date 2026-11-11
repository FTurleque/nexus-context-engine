# Provenance GHCR de la release `0.2.0` (2026-11-11)

Relevé de la tâche 4.3 du changement OpenSpec `sprint-13-installateur-et-release-finale` (`AUD-DEP-34`) : provenance des tags `0.2.0` et `latest` du paquet `ghcr.io/fturleque/nexus-context-engine` après la release finale. Le préflight qui précède est consigné dans [preflight-ghcr-0.2.0-2026-11-10.md](preflight-ghcr-0.2.0-2026-11-10.md).

## Release

| Élément | Valeur |
|---|---|
| Tag Git | `v0.2.0`, sur `main` au commit `9f6e7839b049cf07e37b7660864437ee0c905163` |
| Run `release.yml` | `42945695122`, réussi après relance du seul job en échec |
| Release GitHub | `v0.2.0`, publiée le 2026-11-11T02:41:58Z |
| Digest publié | `sha256:d81b034d372782528a0e2a221f1323eac6b8b43b7ca63c1913d3da3a71b92762` |

## Tags du registre

```text
$ docker buildx imagetools inspect ghcr.io/fturleque/nexus-context-engine:0.2.0
Digest:    sha256:d81b034d372782528a0e2a221f1323eac6b8b43b7ca63c1913d3da3a71b92762
$ docker buildx imagetools inspect ghcr.io/fturleque/nexus-context-engine:latest
Digest:    sha256:d81b034d372782528a0e2a221f1323eac6b8b43b7ca63c1913d3da3a71b92762
```

## Vérification de provenance

Commande de la tâche, exécutée pour les deux tags :

```text
gh attestation verify oci://ghcr.io/fturleque/nexus-context-engine:<tag> \
  --repo FTurleque/nexus-context-engine \
  --signer-workflow FTurleque/nexus-context-engine/.github/workflows/release.yml
```

| Tag | Résultat | Signataire | Commit source | Sujet |
|---|---|---|---|---|
| `0.2.0` | réussite | `.github/workflows/release.yml@refs/tags/v0.2.0` | `9f6e7839` | `sha256:d81b034d…` |
| `latest` | réussite | `.github/workflows/release.yml@refs/tags/v0.2.0` | `9f6e7839` | `sha256:d81b034d…` |

Les deux tags désignent l'image publiée par `release.yml` depuis le tag `v0.2.0` ; aucune version d'août ne porte plus `0.2.0` ni `latest`.
