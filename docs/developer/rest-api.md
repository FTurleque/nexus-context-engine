# Adaptateur REST NEXUS

L'adaptateur Quarkus expose `NexusApplication` sans introduire Quarkus dans le cœur.

## Stack

```text
module      adapters/rest-quarkus
Java        21
Quarkus     3.39.1
version     NEXUS 0.2.0
```

Build :

```powershell
.\mvnw.cmd clean install
```

Lancement :

```powershell
java -jar .\adapters\rest-quarkus\target\quarkus-app\quarkus-run.jar
```

Par défaut, l'API reste local-first sur `127.0.0.1:8080`.

## Architecture

```text
Client HTTP
  ↓
Resources REST / DTO / ApiMapper
  ↓
NexusApiApplicationService
  ↓
NexusApplication
```

## Endpoints principaux

Listener applicatif :

```text
GET  /api/v1/projects
POST /api/v1/projects
GET  /api/v1/projects/{projectId}
POST /api/v1/projects/{projectId}/index
POST /api/v1/projects/{projectId}/search
POST /api/v1/projects/{projectId}/context
POST /api/v1/federated/search
POST /api/v1/federated/context
```

Listener de management séparé, lié par défaut à `127.0.0.1:9000` :

```text
GET  /q/health
GET  /q/health/ready
GET  /q/metrics
```

Les endpoints `/q/*` ne sont pas servis par le listener applicatif. Cette séparation évite de publier accidentellement les métriques/health lorsqu'un reverse proxy expose l'API métier.

Les lectures indexées exigent un projet `READY`.

## Limites de requête

Les endpoints mono-projet et fédérés appliquent les mêmes politiques centrales :

- requête textuelle : limite UTF-8 définie par `QueryPolicy` ;
- résultats publics : maximum `ResultLimitPolicy.MAX_RESULT_LIMIT` ;
- budget contexte : maximum `ContextBudgetPolicy.MAX_CONTEXT_TOKEN_BUDGET` ;
- portée fédérée : maximum **100 projets uniques**.

Une valeur hors limite est rejetée en `400 bad_request` avant l'exécution de la recherche/construction de contexte.

Le champ `constraints` existe encore dans les DTO pour compatibilité de contrat, mais aucune sémantique de contrainte n'est actuellement implémentée. Une map non vide est donc **refusée explicitement en 400** ; elle n'est jamais ignorée silencieusement.

## Fédération

Les requêtes fédérées acceptent au maximum **100 projets uniques**. La cardinalité canonique est validée avant la résolution/readiness ; une requête contenant 101 UUID uniques échoue donc avec l'erreur de portée avant tout `PROJECT_NOT_FOUND` qui résulterait d'une résolution ultérieure.

Une portée valide est ensuite résolue, vérifiée `READY` et traitée par les services fédérés communs.

## Sécurité REST

### Loopback

Loopback est le défaut sûr. Le contrat distant ne s'applique pas à un listener strictement loopback.

### Exposition non-loopback

Elle exige simultanément :

- `NEXUS_REST_API_TOKEN` robuste ;
- `NEXUS_REST_ALLOWED_PROJECT_ROOTS` non vide ;
- un `NEXUS_REST_EXPOSURE_MODE` supporté ;
- un transport Quarkus effectif compatible avec ce mode.

Le listener de management doit rester non publié. Sa valeur par défaut NEXUS est `127.0.0.1:9000`.

### `direct-https`

Le démarrage échoue si :

- `quarkus.http.insecure-requests` n'est pas `disabled` ;
- aucun key material TLS serveur Quarkus effectif n'est configuré.

Une simple étiquette `direct-https` n'est pas une preuve de TLS.

### `reverse-proxy-https`

Le backend doit satisfaire les mêmes garanties TLS que `direct-https`, puis en plus :

- `quarkus.http.proxy.proxy-address-forwarding=true` ;
- `quarkus.http.proxy.trusted-proxies` explicite et non vide ;
- aucune plage de confiance globale telle que `0.0.0.0/0` ou `::/0`.

### `loopback-forward`

Réservé au runtime Docker avec publication hôte déclarée sur loopback.

## Sémantique / Ollama

L'adaptateur utilise la même `SemanticSearchConfiguration` que CLI/MCP. Sans `NEXUS_SEMANTIC_PROVIDER`, aucun provider d'embeddings n'est créé.

Un endpoint Ollama distant doit utiliser HTTPS. HTTP reste autorisé pour `localhost`, `127.0.0.0/8` et `::1`. Une configuration HTTP distante nécessite un opt-in administratif explicite :

```text
NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true
```

Les credentials intégrés dans `NEXUS_OLLAMA_BASE_URL` sont refusés. Les contenus à forte probabilité de secret sont redigés avant l'appel au provider d'embeddings.

## Observabilité

Les métriques applicatives n'utilisent ni requête, ni contenu source, ni chemin projet comme label. Readiness distingue état service et états projets.

En tests, `quarkus.management.test-port=0` laisse le système choisir un port libre ; des tests dédiés vérifient à la fois l'absence de `/q/*` sur le listener applicatif et leur présence sur le listener de management.

## Qualification

Le reactor Maven, NEXUS CI et Docker Distribution qualifient l'adaptateur sur le SHA exact concerné. Le smoke Docker REST exerce le listener packagé ; les tests unitaires couvrent les combinaisons TLS/proxy acceptées et rejetées.

Voir [`ci-and-supply-chain.md`](ci-and-supply-chain.md) et [`../architecture.md`](../architecture.md).
