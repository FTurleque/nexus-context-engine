# REST exposure security contract

NEXUS treats every REST listener outside loopback as a privileged deployment boundary. A remote listener is accepted only when authentication, project-root confinement and transport security are all proven by effective configuration.

## Loopback

`quarkus.http.host=127.0.0.1`, `localhost` or another loopback address remains the default development posture. The remote-exposure transport checks do not apply to a loopback-only listener.

## Docker loopback forward

`NEXUS_REST_EXPOSURE_MODE=loopback-forward` is reserved for the Docker runtime. It additionally requires `NEXUS_RUNTIME=docker` and an explicit `NEXUS_DOCKER_HOST_FORWARD_ADDRESS` that resolves to loopback. The official Compose wiring must derive this declaration from the same bind address used for Docker port publication.

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
