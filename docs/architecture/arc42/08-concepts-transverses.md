# Section 8 — Concepts transverses

## 8.1 Identité et accès

- **Identités métier** : les projets utilisent des **UUID v4** durables, immuables après
  création (ADR-0018). Les identifiants numériques de SQLite (fichiers, symboles) sont
  locaux et ne traversent pas les frontières d'API.
- **Résolution** : un projet peut être résolu par UUID ou par nom unique. Un UUID valide
  mais inconnu renvoie une erreur UUID directe — aucun fallback par nom (H7).
- **Authentification REST** : Bearer token optionnel via `NEXUS_REST_API_TOKEN` ou
  `-Dnexus.rest.api-token`. Un host non-loopback sans token provoque un fail-fast au
  démarrage via `NexusRestExposureGuard` (H6, ADR-0039).
- **MCP STDIO** : pas d'authentification applicative — le transport local garantit
  l'isolation (ADR-0040).

## 8.2 Sécurité locale (filesystem)

Le vecteur de sécurité principal de NEXUS est le **path traversal** et la **redirection par
lien symbolique** :

- `ProjectPathGuard` canonicalise la racine du projet et refuse tout accès hors périmètre.
- `SafeFileIO` ouvre les fichiers avec `OpenOption.NOFOLLOW_LINKS` pour le composant final.
- Les liens symboliques pointant hors de la racine sont refusés dans le scanner.
- Le répertoire de locks et le fichier de lock ne peuvent pas être redirigés par symlink (H2).
- Les références `@fichier` dans les instructions sont vérifiées (pas de chemin absolu, pas
  de remontée hors du repository, récursion limitée à 5 niveaux).

> **Limite assumée** : le modèle Java portable ne constitue pas un sandbox absolu contre un
> acteur local qui remplace agressivement un répertoire ancêtre pendant le traitement.

## 8.3 Données

- **Persistance** : SQLite (ADR-0006). Schéma versionné par migrations SQL embarquées
  forward-only (`SchemaMigrator`, ADR-0020). Rollback en cas d'erreur de migration.
- **Index dérivé** : Lucene peut être supprimé et reconstruit intégralement depuis SQLite
  (ADR-0022). La commande `index --rebuild` force la reconstruction.
- **Hashing** : SHA-256 pour la détection de changements et la déduplication de contenu
  (ADR-0023). Le hash et la taille réelle sont revalidés avant hash/lecture (H1).
- **Taille maximale** : `NEXUS_MAX_FILE_SIZE_BYTES` (8 MiB par défaut), centralisé dans
  `ProjectFileLimits` (H1).
- **UUID** : identifiants métier en `java.util.UUID` (ADR-0018).

## 8.4 Interfaces et versionnement

- **CLI** : contrat stable — sorties humaine, JSON et codes de sortie normalisés (ADR-0030).
- **REST API** : versionnée sous `/api/v1/`. DTOs mappés via `ApiMapper` et `ApiModels`.
  Aucune logique métier dans l'adaptateur (ADR-0039).
- **MCP tools** : spécifications déclarées par `NexusMcpTools.specifications()`. Format
  JSON-RPC 2.0 (ADR-0040, ADR-0041). Les résultats sont sérialisés en JSON texte.
- **ContextBundle** : contrat de sortie canonique — indépendant du consommateur (ADR-0001).

## 8.5 Erreurs

- **État incohérent** : tout état persistant non-READY (y compris `INDEXING` après crash)
  impose un rebuild complet à la reprise.
- **Provider timeout** : `ExternalTaskRunner` commun avec timeout wall-clock côté appelant,
  annulation/interruption sans attente bloquante (H3). Le projet passe en `FAILED` si
  l'indexation échoue après timeout d'un importer récalcitrant.
- **Conflits de verrou** : le verrou JVM `ReentrantLock` protège le single-flight
  in-process ; le `FileLock` OS protège l'inter-processus (H2).
- **REST** : `IllegalArgumentExceptionMapper` et `IOExceptionMapper` traduisent les
  exceptions en réponses HTTP structurées.
- **MCP** : les erreurs de tools retournent `isError=true` avec un message structuré
  `{"error":"nexus_tool_error","message":"..."}`.

## 8.6 Résilience

- **Single-flight** : un seul verrou JVM par `projectId` empêche deux indexations
  simultanées du même projet dans le même processus.
- **FileLock OS** : protège contre deux processus NEXUS sur le même projet.
- **Gate READY** : toute opération de lecture interactive (recherche, contexte, symboles,
  usages, fédération) exige `indexStatus == READY` (I18).
- **Reconstruction** : Lucene est reconstructible depuis SQLite sans perte de données métier.
- **Providers bornés** : chaque provider externe est soumis à un timeout global
  `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS` (H3).

## 8.7 Configuration

Variables d'environnement principales :

| Variable | Défaut | Effet |
|----------|--------|-------|
| `NEXUS_HOME` | répertoire système NEXUS | Stockage local |
| `NEXUS_MAX_FILE_SIZE_BYTES` | 8 388 608 (8 MiB) | Taille max d'un fichier indexé |
| `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS` | 180 | Timeout global providers externes |
| `NEXUS_JDTLS_HOME` | non défini | Active JDT LS pour `--deep-java` |
| `NEXUS_SEMANTIC_PROVIDER` | non défini | `=ollama` active les embeddings |
| `NEXUS_OLLAMA_BASE_URL` | `http://localhost:11434` | Base URL Ollama |
| `NEXUS_OLLAMA_EMBEDDING_MODEL` | non défini | Modèle d'embedding Ollama |
| `NEXUS_OLLAMA_EMBEDDING_DIMENSIONS` | non défini | Dimensions du vecteur |
| `NEXUS_OLLAMA_TIMEOUT_SECONDS` | non défini | Timeout Ollama |
| `NEXUS_SEMANTIC_RRF_WEIGHT` | non défini | Poids RRF sémantique (≤ 10) |
| `NEXUS_REST_API_TOKEN` | non défini | Active Bearer auth REST |

## 8.8 Observabilité

- **Métriques REST** : Micrometer + Prometheus, durée des opérations et des providers,
  sans contenu privé comme label. Exposées sur `/q/metrics`.
- **Health REST** : 
  - `/q/health/live` — `NexusLivenessCheck` (Quarkus liveness)
  - `/q/health/ready` — `NexusReadinessCheck` (compte les projets par état, `degraded`
    si un projet est `FAILED`) (H4)
- **CLI** : sortie `--explain` pour les explications de ranking et de sélection.
- **Metadata ContextBundle** : `allocatedTokens`, `selectedItems`, `starvation`,
  `deduplicated`, `refillTokens`, `refillItems`, `unusedTokens`.
- **Logs** : `stderr` pour les messages diagnostics (le MCP utilise `stdout` exclusivement
  pour le framing JSON-RPC).

## 8.9 Persistance

- **Source canonique** : SQLite, transactions ACID, `ON DELETE CASCADE` pour les symboles.
- **Index dérivé** : Lucene — `updateDocument` pour les modifications, suppression + rebuild
  pour la reconstruction complète.
- **Verrou** : `FileLock` Java sur `NEXUS_HOME/locks/{projectId}.lock` — présence du fichier
  ≠ verrou actif (H2).
- **Migration** : `SchemaMigrator`, table `schema_migrations`, scripts forward-only
  `V001__initial_schema.sql`, `V002__...` (ADR-0020).

## 8.10 Messaging

NEXUS n'utilise pas de bus de messages ou de broker. La coordination inter-processus
passe exclusivement par le `FileLock` OS sur le filesystem local.

## 8.11 Performance

- **Recherche** : candidats bornés à la source (`SymbolSearchStrategy` avec pool préfiltré) ;
  graphe mis en cache par génération monotone par projet.
- **Fédération** : sur-récupération locale bornée avant tri global ; fair floor déterministe
  par projet ; refill des candidats différés avec le budget rendu disponible (H5).
- **Budget** : algorithme glouton déterministe, estimateur de tokens heuristique local et
  remplaçable (`HeuristicTokenEstimator`, ADR-0027).
- **Embeddings** : batchables ; Ollama utilise `/api/embed` en lots pour réduire la latence.
- **Baseline Phase 6** : 2 104 fichiers, 10 878 symboles, indexation ≈ 8 818 ms,
  fédération p50/p95 133/304 ms, contexte p50/p95 48/206 ms.

## 8.12 Concurrence

- **Single-flight JVM** : `ReentrantLock` par `projectId`, libéré quand il n'est plus utilisé.
- **Single-flight OS** : `FileLock` Java sur le fichier de lock, ni tronqué ni écrit
  avec du contenu métier (H2).
- **Providers opt-in** : soumis à `ExternalTaskRunner` avec worker daemon et timeout
  wall-clock côté appelant (H3).
- **Slots Lucene** : pas de lifecycle Lucene partagé plus complexe (décision consciente,
  `docs/architecture.md` § Choix non adoptés).

## 8.13 Tests

- Tests unitaires JUnit 5 : ranking, contexte, parsers, instructions, skills, Git.
- Tests d'intégration : indexation complète sur le repository NEXUS lui-même, qualité de
  recherche (`hit@3`, `MRR@3`), federated context.
- Self-smoke `scripts/self-smoke.ps1` : enregistrement, indexation, inspection, contexte.
- Tests adversariaux symlink (H1, H2) : skip si la plateforme ne les autorise pas.
- Surefire avec `workingDirectory` pointant sur la racine du projet pour les ressources
  de test.

## 8.14 Déploiement et rollback

- **Build** : `mvn clean install` — génère fat JAR CLI, JAR bibliothèque, ZIP, sha256, SBOM.
- **Distribution** : ZIP autonome avec launchers OS. Pas de conteneur Docker (hypothèse à valider).
- **Migration SQLite** : forward-only — pas de rollback de schéma. En cas d'erreur, la base
  reste à la version précédente.
- **Recovery** : `nexus index <project> --rebuild` reconstruit Lucene depuis SQLite.
  Voir `docs/developer/release-and-recovery.md`.
- **Rollback** : remplacer le fat JAR ou le ZIP par la version précédente. La base SQLite
  reste compatible tant que la migration forward-only n'a pas été appliquée par la nouvelle
  version.

> **Hypothèse à valider** : aucune procédure de rollback de schéma n'est documentée pour
> le cas où une migration V002 introduirait une régression. La preuve nécessaire est la
> présence d'un runbook de downgrade dans `docs/developer/release-and-recovery.md`.
