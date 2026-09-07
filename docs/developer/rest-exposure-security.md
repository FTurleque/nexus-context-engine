# REST exposure security contract

NEXUS treats every REST listener as a privileged deployment boundary. A remote listener is accepted only when authentication, project-root confinement and transport security are all proven by effective configuration.

## Loopback

`quarkus.http.host=127.0.0.1`, `localhost` or another loopback address remains the default network posture. The remote-exposure transport checks do not apply to a loopback-only listener, but loopback is no longer treated as an implicit authentication boundary.

By default, startup therefore requires a strong Bearer token even on loopback:

```text
NEXUS_REST_API_TOKEN=<strong random token>
```

The token must contain at least 32 UTF-8 bytes and satisfy the structural character-diversity threshold enforced by NEXUS. This gate rejects obviously weak repeated values but does not prove cryptographic entropy; generate the token with a CSPRNG.

For a deliberately trusted single-user workstation, the historical unauthenticated loopback mode remains available only through an explicit declaration:

```text
NEXUS_REST_TRUST_LOCAL=true
```

`NEXUS_REST_TRUST_LOCAL` accepts only `true` or `false`. When it is absent or `false`, a missing local token is a startup error. If a token is configured, the REST authentication filter still enforces it even when local trust is enabled.

For workstations where local authentication must also constrain the filesystem administration boundary, enable the hardened local posture:

```text
NEXUS_REST_HARDEN_LOCAL=true
NEXUS_REST_API_TOKEN=<strong random token>
NEXUS_REST_ALLOWED_PROJECT_ROOTS=<explicit allowed roots>
```

When `NEXUS_REST_HARDEN_LOCAL=true`, startup fails closed unless both conditions are satisfied:

- a Bearer token meeting the same minimum strength contract as remote exposure;
- a non-empty canonical project-root allowlist.

`NEXUS_REST_HARDEN_LOCAL=true` takes precedence over `NEXUS_REST_TRUST_LOCAL=true`: hardened mode can never disable authentication or root confinement.

## Docker loopback forward

`NEXUS_REST_EXPOSURE_MODE=loopback-forward` is reserved for the Docker runtime. It additionally requires `NEXUS_RUNTIME=docker` and an explicit `NEXUS_DOCKER_HOST_FORWARD_ADDRESS` that resolves to loopback. The official Compose wiring must derive this declaration from the same bind address used for Docker port publication.

The official Docker/installer path generates a local REST token when one is not supplied, so the authenticated loopback default remains usable without weakening the runtime contract.

## Direct HTTPS

A non-loopback listener using `NEXUS_REST_EXPOSURE_MODE=direct-https` must satisfy all normal remote requirements (strong Bearer token and configured project roots) and the following Quarkus transport requirements:

- `quarkus.http.insecure-requests=disabled`; HTTP redirect is not sufficient because it still opens a plaintext listener;
- server TLS key material must be configured through the Quarkus TLS registry (`quarkus.tls.*.key-store.*`, optionally selected with `quarkus.http.tls-configuration-name`) or through the direct HTTP certificate/keystore properties (`quarkus.http.ssl.certificate.*`).

The process fails at startup if these properties do not prove an HTTPS-only server configuration.

## Reverse proxy HTTPS

`NEXUS_REST_EXPOSURE_MODE=reverse-proxy-https` is deliberately stricter than a declarative "TLS terminated elsewhere" flag. Because NEXUS cannot prove an external network ACL, the backend listener itself must still satisfy the same HTTPS-only requirements as `direct-https`.

In addition it requires:

- `quarkus.http.proxy.proxy-address-forwarding=true`;
- an explicit non-empty `quarkus.http.proxy.trusted-proxies` list;
- no catch-all trusted range such as `0.0.0.0/0` or `::/0`.

This prevents arbitrary direct clients from being treated as trusted forwarding infrastructure. Use concrete proxy addresses or narrowly scoped CIDRs.

## Rationale

The exposure mode is an operator intent, not proof of transport security. NEXUS therefore validates effective Quarkus settings before accepting a non-loopback listener. A missing certificate, an enabled plaintext HTTP listener, or an unbounded proxy trust configuration is a startup error rather than a warning.

Loopback itself is likewise not proof that every local process is trusted. Authentication is now the default local boundary; unauthenticated local access requires the explicit `NEXUS_REST_TRUST_LOCAL=true` opt-out, while `NEXUS_REST_HARDEN_LOCAL=true` adds canonical project-root confinement.
