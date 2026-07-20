# Adaptateur REST NEXUS

Ce chapitre décrit l'adaptateur HTTP introduit par l'Itération 11.

## 1. Objectif

L'adaptateur expose les capacités du moteur NEXUS à des applications externes sans introduire Quarkus dans le cœur.

Le code se trouve dans :

```text
adapters/rest-quarkus/
```

Le cœur reste construit par le `pom.xml` racine. L'adaptateur possède son propre build Maven et dépend du JAR `nexus-context-engine`.

## 2. Version Quarkus

L'Itération 11 démarre avec :

```text
Quarkus 3.33 LTS
micro-version : 3.33.2.1
Java : 21
```

La version est figée dans `adapters/rest-quarkus/pom.xml` afin de rendre les validations reproductibles.

## 3. Architecture

```text
Client HTTP
    ↓
NexusResource
    ↓
DTO REST / ApiMapper
    ↓
NexusApiApplicationService
    ↓
NEXUS core
    ├── ProjectRegistry
    ├── ProjectIndexingService
    ├── SearchService
    ├── ContextBuilder
    ├── SQLite
    ├── Lucene
    ├── SCIP optionnel
    └── JDT LS optionnel
```

Les ressources REST ne contiennent aucune logique métier d'indexation, recherche ou construction de contexte.

Les modèles du cœur ne constituent pas le contrat HTTP public. Les réponses sont transformées vers les DTO définis dans `ApiModels`.

## 4. Configuration

L'adaptateur réutilise les variables et propriétés du moteur NEXUS.

La donnée locale reste configurée avec :

```text
NEXUS_HOME
```

ou la propriété JVM :

```text
-Dnexus.home=<chemin>
```

Par défaut, Quarkus écoute uniquement en local :

```text
127.0.0.1:8080
```

Ce choix préserve le comportement local-first de NEXUS. Une exposition réseau doit être configurée explicitement par l'opérateur.

## 5. Construire l'adaptateur

Le cœur doit d'abord être installé dans le dépôt Maven local :

```powershell
mvn clean install
mvn -f adapters/rest-quarkus/pom.xml clean verify
```

La validation complète recommandée reste :

```powershell
.\scripts\validate-iteration-11.ps1
```

## 6. Lancer l'API

Après packaging :

```powershell
java -jar .\adapters\rest-quarkus\target\quarkus-app\quarkus-run.jar
```

Le contrat initial est versionné sous :

```text
/api/v1
```

## 7. Projets

### Lister les projets

```http
GET /api/v1/projects
```

### Enregistrer un projet local

```http
POST /api/v1/projects
Content-Type: application/json
```

```json
{
  "rootPath": "N:/workspace-dev/my-project",
  "name": "my-project"
}
```

### Lire un projet

```http
GET /api/v1/projects/{projectId}
```

Les identités HTTP utilisent les UUID NEXUS.

## 8. Indexation

### Indexation normale

```http
POST /api/v1/projects/{projectId}/index
```

### Reconstruction complète

```http
POST /api/v1/projects/{projectId}/index?rebuild=true
```

### Analyse Java profonde optionnelle

```http
POST /api/v1/projects/{projectId}/index?deepJava=true
```

La présence de `deepJava=true` ne rend pas JDT LS obligatoire. Si aucun provider JDT LS n'est configuré dans l'environnement, le comportement suit les règles du moteur NEXUS.

### Inspecter l'index

```http
GET /api/v1/projects/{projectId}/index
```

La réponse contient les compteurs :

```text
files
symbols
relations
```

## 9. Recherche

```http
POST /api/v1/projects/{projectId}/search
Content-Type: application/json
```

```json
{
  "query": "ProjectIndexingService",
  "limit": 10,
  "explain": true
}
```

Valeurs par défaut :

```text
limit = 10
explain = false
```

Les résultats exposent notamment :

- rang ;
- score global ;
- type de candidat ;
- chemin relatif ;
- symbole éventuel ;
- composantes du score ;
- raisons d'explication lorsque demandées.

## 10. Contexte

```http
POST /api/v1/projects/{projectId}/context
Content-Type: application/json
```

```json
{
  "query": "ProjectIndexingService architecture",
  "tokenBudget": 1200,
  "requestedSources": ["FILE", "SYMBOL", "DOCUMENTATION"],
  "constraints": {},
  "explain": true
}
```

Valeurs par défaut :

```text
tokenBudget = 2000
explain = false
requestedSources = toutes les sources éligibles
constraints = vide
```

L'invariant du moteur reste inchangé :

```text
estimatedTokens <= tokenBudget
```

## 11. Endpoints d'explication

Deux routes rendent l'intention explicite pour les clients qui veulent systématiquement les justifications :

```http
POST /api/v1/projects/{projectId}/explain/search
POST /api/v1/projects/{projectId}/explain/context
```

Elles réutilisent les mêmes pipelines que les routes standard en forçant `explain=true`.

## 12. Santé

SmallRye Health expose notamment :

```http
GET /q/health
GET /q/health/ready
```

Le check `nexus-context-engine` publie l'état de readiness ainsi que des informations non sensibles sur les adapters de stockage et de recherche utilisés.

## 13. Observabilité

Micrometer avec export Prometheus expose :

```http
GET /q/metrics
```

L'adaptateur enregistre également :

```text
nexus.api.operations
nexus.api.operation.duration
```

avec le tag :

```text
operation=index|search|context
```

Les métriques HTTP Quarkus restent activées pour observer les requêtes entrantes.

## 14. Gestion des erreurs

Les erreurs de validation sont normalisées en HTTP `400` avec une réponse JSON :

```json
{
  "error": "bad_request",
  "message": "query est obligatoire"
}
```

Les erreurs d'entrée/sortie non récupérables sont normalisées en HTTP `500` avec `error=io_error`.

Cette première version privilégie un contrat stable et explicite. Des erreurs métier plus fines (`404`, `409`, `422`) pourront être introduites lorsque les cas d'usage API seront stabilisés.

## 15. Validation de l'Itération 11

Le test Quarkus de bout en bout couvre :

1. création d'un projet ;
2. indexation ;
3. inspection ;
4. recherche explicable ;
5. endpoint d'explication de recherche ;
6. construction de contexte sous budget ;
7. endpoint d'explication de contexte ;
8. erreur de validation HTTP `400` ;
9. readiness ;
10. métriques.

Le script `scripts/validate-iteration-11.ps1` vérifie en plus que le self-smoke historique du cœur reste vert et que le runner Quarkus est réellement produit.
