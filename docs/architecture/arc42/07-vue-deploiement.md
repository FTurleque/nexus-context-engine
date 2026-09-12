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

Sur POSIX, NEXUS durcit `NEXUS_HOME`, `indexes` et `locks` en `0700` et SQLite en `0600`. Sur Windows/filesystems sans vue POSIX, les ACL natives sont conservées plutôt que remplacées destructivement. `NEXUS_REQUIRE_PRIVATE_STORAGE=true` permet d'exiger un stockage démontré privé et d'échouer fermé sur ACL sensible inattendue ou inspection impossible.

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

REST application : 127.0.0.1:8080
REST management  : 127.0.0.1:9000
```

Ollama et JDT LS restent opt-in.

## 7.3 REST application et management

Le listener applicatif par défaut est loopback sur `127.0.0.1:8080`.

Health et métriques sont servis uniquement par le listener de management séparé :

```text
127.0.0.1:9000/q/health
127.0.0.1:9000/q/health/ready
127.0.0.1:9000/q/metrics
```

`/q/*` n'est pas servi par le listener applicatif. Un reverse proxy métier ne doit pas publier le listener de management.

Une exposition API non-loopback exige auth, roots et transport sécurisé effectif. Le token doit être généré par CSPRNG et satisfaire le gate structurel NEXUS ; ce gate rejette les valeurs manifestement faibles sans prétendre mesurer leur entropie cryptographique.

### `direct-https`

- `quarkus.http.insecure-requests=disabled` ;
- key material TLS serveur configuré.

### `reverse-proxy-https`

- mêmes exigences TLS backend ;
- `quarkus.http.proxy.proxy-address-forwarding=true` ;
- trusted proxies explicites et bornés.

### Docker `loopback-forward`

Réservé à `NEXUS_RUNTIME=docker` lorsque la publication hôte est déclarée loopback. Le conteneur ne déduit pas arbitrairement l'adresse de bind du daemon.

Le smoke Docker teste la santé **depuis le conteneur** sur `127.0.0.1:9000` et vérifie séparément qu'un endpoint métier est accessible sur le port applicatif publié. Le port management n'a pas besoin d'être exposé à l'hôte.

## 7.4 Ollama

Ollama reste opt-in. Une configuration HTTP est autorisée sans opt-in uniquement vers une adresse de bouclage (`localhost`, `127.0.0.0/8`, `::1`).

Un endpoint distant doit utiliser HTTPS, sauf exception administrative explicite :

```text
NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true
```

Les credentials intégrés dans `NEXUS_OLLAMA_BASE_URL` sont refusés. En Docker, une URL de bouclage peut être adaptée vers `host.docker.internal` après validation de cette politique.

La qualification réelle sémantique utilise périodiquement/manuellement un runtime Ollama `0.33.3` téléchargé depuis la release officielle, vérifié contre un SHA-256 versionné dans le repository, puis exécute le benchmark réel `qwen3-embedding:0.6b` avec seuils de qualité/non-régression.

## 7.5 JDT Language Server

JDT LS communique en STDIO local. Le framing entrant est borné : message 16 MiB, headers 64 KiB, ligne 8 KiB et backlog 256 messages. Les tâches externes sont limitées à 8 workers actifs simultanément à l'échelle JVM.

Avant démarrage du subprocess, la racine canonique exacte du repository doit appartenir à `NEXUS_JDTLS_TRUSTED_PROJECT_ROOTS`.

Le workspace JDT par projet vit sous :

```text
NEXUS_HOME/jdtls-workspaces/<project-id-derived>
```

## 7.6 MCP

MCP Java 2.0.1 reste supporté en STDIO local. `stdout` est réservé au framing JSON-RPC ; les diagnostics vont sur `stderr`.

## 7.7 Persistance et recovery

SQLite est l'autorité à sauvegarder. Lucene lexical/sémantique peut être reconstruit. Les migrations sont forward-only ; V005 impose les contraintes de plage des symboles.

Le profil sémantique `content-v3` rend les anciens vecteurs incompatibles : une indexation reconstruit l'index sémantique concerné plutôt que de réutiliser silencieusement des embeddings pré-hardening.

Sur les filesystems sans `SecureDirectoryStream`, `SafeFileIO` capture chemin réel et identité filesystem de chaque composant avant ouverture puis les revalide immédiatement après. Cette défense détecte les substitutions visibles sans prétendre sandboxer un filesystem local hostile.

## 7.8 Distribution et release

Le reactor produit CLI, ZIP, SBOM/notices et distributions Windows. Docker Distribution construit une image unique et qualifie cette image. Une release taggée sur `main` récupère l'artefact exact qualifié et le publie sans rebuild.

Le préflight GHCR est fail-closed. Tags version et SHA sont immuables ; un retry est idempotent uniquement pour le même contenu.

`jdk.incubator.vector` reste absent des launchers de production : la qualification ABBA same-runner n'a pas démontré de bénéfice suffisamment robuste pour justifier l'adoption d'un module incubateur.

## 7.9 CI et gouvernance

`develop` est la branche d'intégration et `main` la branche de release. Les gates applicables qualifient le SHA exact.

Le ruleset GitHub actif `Protect main & develop` protège les deux branches, impose les pull requests, interdit suppression/non-fast-forward et exige les checks permanents approuvés. NXA3-14 / #130 est satisfait.

NEXUS est actuellement maintenu par **une seule personne**. La politique n'exige pas de seconde approbation humaine ni de resynchronisation stricte avec la base juste avant merge ; ces absences ne sont pas des findings dans le modèle solo courant. Toute évolution de ce modèle doit être décidée explicitement avant de modifier les règles GitHub.
