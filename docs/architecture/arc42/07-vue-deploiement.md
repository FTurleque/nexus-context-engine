# Section 7 — Vue de déploiement

## 7.1 Modes supportés

NEXUS est local-first et peut être consommé via :

- CLI JVM ;
- distribution ZIP ;
- REST Quarkus ;
- MCP STDIO ;
- distribution Windows self-contained ;
- image Docker.

`NEXUS_HOME` contient SQLite canonique, index dérivés et locks. La garantie `FileLock` vise un filesystem local.

## 7.2 Frontières locales

```text
IDE / utilisateur
   ├─ CLI ──────────┐
   ├─ REST ─────────┼─> NexusApplication
   └─ MCP STDIO ────┘        │
                              ├─ SQLite canonique
                              ├─ Lucene dérivé
                              ├─ locks OS
                              └─ repositories locaux
```

Ollama et JDT LS restent opt-in.

## 7.3 REST

Le listener par défaut est loopback. Une exposition non-loopback exige auth, roots et transport sécurisé effectif.

### `direct-https`

- `quarkus.http.insecure-requests=disabled` ;
- key material TLS serveur configuré.

### `reverse-proxy-https`

- mêmes exigences TLS backend ;
- `quarkus.http.proxy.proxy-address-forwarding=true` ;
- trusted proxies explicites et bornés.

### Docker `loopback-forward`

Réservé à `NEXUS_RUNTIME=docker` lorsque la publication hôte est déclarée loopback. Le conteneur ne déduit pas arbitrairement l'adresse de bind du daemon.

## 7.4 MCP

MCP Java 2.0.1 reste supporté en STDIO local. `stdout` est réservé au framing JSON-RPC ; les diagnostics vont sur `stderr`.

## 7.5 Persistance et recovery

SQLite est l'autorité à sauvegarder. Lucene peut être reconstruit. Les migrations sont forward-only ; V005 impose les contraintes de plage des symboles.

## 7.6 Distribution et release

Le reactor produit CLI, ZIP, SBOM/notices et distributions Windows. Docker Distribution construit une image unique et qualifie cette image. Une release taggée sur `main` récupère l'artefact exact qualifié et le publie sans rebuild.

Le préflight GHCR est fail-closed. Tags version et SHA sont immuables ; un retry est idempotent uniquement pour le même contenu.

## 7.7 CI

`develop` est la branche d'intégration et `main` la branche de release. Les gates applicables qualifient le SHA exact. La protection de `develop` doit être configurée dans GitHub selon le contrat de gouvernance versionné.
