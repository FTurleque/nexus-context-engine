# Section 8 — Concepts transverses

## 8.1 Identité et accès

- **Identités métier** : projets en UUID v4 durables ; identifiants SQLite locaux non exposés comme identité métier.
- **Résolution** : UUID valide mais inconnu ⇒ erreur UUID directe ; aucun fallback implicite par nom.
- **REST** : Bearer token via `NEXUS_REST_API_TOKEN` ou `-Dnexus.rest.api-token`; host non-loopback sans token ⇒ fail-fast.
- **MCP STDIO** : transport local, pas d'authentification applicative supplémentaire.

## 8.2 Sécurité filesystem

La frontière de confiance est le repository canonicalisé :

- `ProjectPathGuard` vérifie confinement et absence de composants symlinkés sous la racine ;
- `SafeFileIO` ouvre le composant final avec `NOFOLLOW_LINKS` ;
- scanner, fichiers d'ignore, instructions/références, Agent Skills, JDT LS, `ContextFragmentFactory` et SCIP utilisent cette politique ;
- `NEXUS_MAX_FILE_SIZE_BYTES` borne effectivement la consommation ;
- le répertoire/fichier de lock ne peuvent pas être redirigés par symlink.

> **Limite assumée** : les primitives Java portables utilisées ici ne constituent pas un sandbox absolu contre un acteur local qui modifie agressivement un répertoire ancêtre ou exploite des hard-links pendant le traitement.

## 8.3 Données, autorité et provenance

- **Source canonique** : SQLite.
- **Migrations** : forward-only via `SchemaMigrator` et `schema_migrations`.
- **Index dérivés** : Lucene lexical/sémantique et snapshots externes sont reconstructibles ou remplaçables.
- **Hashing** : SHA-256 pour changements/déduplication ; contenu réel revalidé avant consommation.
- **Fingerprint canonique** : représentation déterministe des métadonnées de fichiers pertinentes pour prouver la cohérence d'un index dérivé.

### Intelligence externe

Un changement canonique `SOURCE`/`TEST` invalide les snapshots persistés de providers externes non embarqués, y compris si le provider n'est plus actif dans le runtime courant.

### Index sémantique

Le commit Lucene persiste : fingerprint canonique, provider, modèle, dimensions, profil de préparation et version de schéma. Une absence/mismatch force un rebuild.

La recherche sémantique vérifie cette compatibilité **avant** de calculer l'embedding de requête.

Voir [`../../index-provenance.md`](../../index-provenance.md).

## 8.4 Interfaces et versionnement

- **CLI** : sorties humaines/JSON et codes de sortie normalisés.
- **REST** : `/api/v1/`, DTOs via adaptateur, logique métier hors adaptateur.
- **MCP** : JSON-RPC 2.0, tools déclarés par `NexusMcpTools`.
- **ContextBundle** : contrat canonique indépendant du consommateur.

## 8.5 Erreurs

- état persistant non-READY ⇒ rebuild complet à la reprise ;
- provider/importer timeout ⇒ `ExternalTaskRunner`, interruption sans attente bloquante ;
- conflit de mutation ⇒ mutex JVM + `FileLock` OS ;
- REST ⇒ exceptions traduites en réponses structurées ;
- MCP ⇒ `isError=true` et payload structuré.

## 8.6 Résilience et concurrence

Deux niveaux de single-flight protègent une mutation de projet :

1. `ReentrantLock` JVM par `projectId` ;
2. `FileLock` OS par projet sous `NEXUS_HOME/locks`.

Le support cible est un filesystem local. Les sémantiques de `FileLock` réseau ne sont pas revendiquées.

Le fichier `.lock` peut persister ; sa présence n'est pas un lease. Seul le `FileLock` actif représente la propriété exclusive.

Le gate `READY` protège toutes les lectures interactives dépendant d'un index. Lucene reste reconstructible depuis l'état canonique.

## 8.7 Configuration

| Variable | Défaut | Effet |
|----------|--------|-------|
| `NEXUS_HOME` | répertoire système NEXUS | stockage local |
| `NEXUS_MAX_FILE_SIZE_BYTES` | 8 MiB | taille max consommée |
| `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS` | 180 | timeout providers/importers |
| `NEXUS_JDTLS_HOME` | non défini | active JDT LS pour `--deep-java` |
| `NEXUS_SEMANTIC_PROVIDER` | non défini | `ollama` active la sémantique |
| `NEXUS_OLLAMA_BASE_URL` | `http://localhost:11434` | endpoint Ollama |
| `NEXUS_OLLAMA_EMBEDDING_MODEL` | non défini | modèle embeddings |
| `NEXUS_OLLAMA_EMBEDDING_DIMENSIONS` | non défini | dimensions |
| `NEXUS_OLLAMA_TIMEOUT_SECONDS` | non défini | timeout Ollama |
| `NEXUS_SEMANTIC_RRF_WEIGHT` | non défini | poids RRF sémantique |
| `NEXUS_REST_API_TOKEN` | non défini | Bearer auth REST |

## 8.8 Observabilité

- **Métriques** : Micrometer/Prometheus, sans contenu privé comme label.
- **Liveness** : processus vivant.
- **Readiness service** : dépendances de base disponibles.
- **Project readiness** : projet `READY` avant lecture indexée.
- **Metadata contexte** : allocations, sélection, starvation, déduplication, refill et budget inutilisé.
- **MCP** : `stdout` réservé au framing JSON-RPC ; diagnostics sur `stderr`.

## 8.9 Persistance

- SQLite : transactions ACID, source canonique.
- Lucene : état dérivé ; rebuild possible.
- Verrou : `NEXUS_HOME/locks/{projectId}.lock` + `FileLock` Java.
- Migrations : `V001__initial_schema.sql`, `V002__index_generation.sql`, forward-only.

## 8.10 Messaging

NEXUS n'utilise ni broker ni bus de messages. La coordination inter-processus locale repose sur `FileLock`.

## 8.11 Performance

- requêtes symboles/usages bornées côté SQLite ;
- graphe en cache par génération ;
- sur-récupération fédérée bornée ;
- fair floor + refill ;
- embeddings batchables ;
- baseline historique : 2 104 fichiers, 10 878 symboles, indexation ≈ 8 818 ms, fédération p50/p95 133/304 ms, contexte p50/p95 48/206 ms.

Les changements plus complexes (FTS, moteur externe, vector DB, lifecycle Lucene partagé) restent conditionnés à un benchmark.

## 8.12 Tests et qualification

- JUnit : ranking, contexte, parsers, instructions, skills, Git, indexation/provenance.
- Intégration : recherche, contexte fédéré, code intelligence.
- Self-smoke : `scripts/self-smoke.ps1`.
- Tests adversariaux filesystem/locks quand la plateforme le permet.
- PR #24 exact-head : Windows Java 24 PASS, Linux Java 21 reactor PASS, distribution smoke PASS.

## 8.13 Déploiement et recovery

- build : `mvnw clean install` ;
- distribution : fat JAR CLI + ZIP autonome + SHA-256 + SBOM + `LICENSE` ;
- SQLite : sauvegarder avant upgrade ; migrations forward-only ;
- Lucene : supprimer/reconstruire depuis SQLite en cas de corruption ;
- index sémantique sans provenance compatible : rebuild ;
- rollback applicatif : restaurer la version précédente ; si une migration incompatible a déjà été appliquée, restaurer la sauvegarde SQLite canonique correspondante.

Voir [`../../developer/release-and-recovery.md`](../../developer/release-and-recovery.md).
