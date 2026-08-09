# Section 8 — Concepts transverses

## 8.1 Identité et accès

- projets identifiés par UUID v4 durables ;
- UUID valide mais inconnu ⇒ erreur UUID directe, sans fallback implicite par nom ;
- MCP reste en STDIO local ;
- REST local écoute loopback par défaut ;
- exposition REST distante soumise à une politique fail-closed.

### Sécurité REST distante

Une écoute hors loopback exige simultanément :

- `NEXUS_REST_API_TOKEN` robuste — minimum 32 octets et entropie estimée ≥ 96 bits ;
- `NEXUS_REST_ALLOWED_PROJECT_ROOTS` non vide ;
- `NEXUS_REST_EXPOSURE_MODE=reverse-proxy-https|direct-https` ;
- `loopback-forward` uniquement avec `NEXUS_RUNTIME=docker` et publication hôte sur loopback.

## 8.2 Sécurité filesystem

La frontière de confiance est le repository canonicalisé :

- `ProjectPathGuard` vérifie confinement et symlinks ;
- `SafeFileIO` ouvre le composant final avec `NOFOLLOW_LINKS` ;
- scanner, ignore files, instructions/références, Agent Skills, JDT LS, `ContextFragmentFactory` et SCIP suivent cette politique ;
- `NEXUS_MAX_FILE_SIZE_BYTES` borne la consommation ;
- SCIP possède en plus des plafonds dédiés de fichier/message avant allocation Protobuf ;
- répertoire/fichier de lock ne doivent pas être redirigés par symlink.

Limite assumée : ces primitives Java portables ne constituent pas un sandbox absolu contre un acteur local hostile modifiant ancêtres/hard-links. Les filesystems réseau ne sont pas qualifiés pour la garantie `FileLock`.

## 8.3 Données, autorité et provenance

- source canonique : SQLite ;
- migrations forward-only via `SchemaMigrator` ;
- Lucene lexical/sémantique et snapshots externes : dérivés ;
- fingerprint canonique : preuve déterministe de cohérence ;
- changement SOURCE/TEST ⇒ invalidation des snapshots externes persistés concernés ;
- index sémantique : fingerprint, provider, modèle, dimensions, profil de préparation, version de schéma ;
- mismatch/absence ⇒ rebuild ;
- recherche sémantique incompatible refusée avant embedding de requête.

## 8.4 Cohérence d'indexation

Une mutation de projet est protégée par :

1. mutex JVM par `projectId` ;
2. `FileLock` OS sous `NEXUS_HOME/locks`.

Le snapshot canonique est revalidé avant publication. Une mutation du repository détectée pendant l'indexation provoque un échec fail-closed plutôt que le passage à `READY` avec un état mixte.

`index_generation` ne progresse pas pour un no-op effectif.

## 8.5 Interfaces et limites

- CLI : sorties humaines/JSON et codes de sortie normalisés ;
- REST : `/api/v1/`, DTOs dans l'adaptateur ;
- MCP : JSON-RPC 2.0 ;
- `ContextBundle` : contrat canonique indépendant du consommateur ;
- CLI, REST et MCP : `ResultLimitPolicy` commune pour la limite maximale des résultats.

## 8.6 Résilience

- état persistant non-READY ⇒ rebuild complet à la reprise ;
- provider/importer timeout ⇒ `ExternalTaskRunner` ;
- worker tiers non coopératif ⇒ risque résiduel suivi par issue #51 ;
- conflit de mutation ⇒ mutex JVM + `FileLock` ;
- Lucene reste reconstructible depuis l'état canonique.

## 8.7 Configuration principale

| Variable | Défaut | Effet |
|---|---|---|
| `NEXUS_HOME` | répertoire NEXUS | stockage local |
| `NEXUS_MAX_FILE_SIZE_BYTES` | 8 MiB | taille max consommée |
| `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS` | 180 | timeout providers/importers |
| `NEXUS_JDTLS_HOME` | non défini | active JDT LS |
| `NEXUS_SEMANTIC_PROVIDER` | non défini | `ollama` active la sémantique |
| `NEXUS_OLLAMA_BASE_URL` | `http://localhost:11434` | endpoint Ollama |
| `NEXUS_REST_API_TOKEN` | non défini | Bearer auth REST |
| `NEXUS_REST_ALLOWED_PROJECT_ROOTS` | non défini | allowlist projets REST distants |
| `NEXUS_REST_EXPOSURE_MODE` | non défini | politique d'exposition distante |
| `NEXUS_RUNTIME` | natif implicite | autorise notamment le contrat Docker `loopback-forward` |

## 8.8 Observabilité et readiness

- métriques Micrometer/Prometheus sans contenu privé comme label ;
- liveness : processus vivant ;
- readiness service : dépendances de base disponibles ;
- project readiness : projet `READY` avant lecture indexée ;
- aucun projet enregistré : état explicite distinct de `allProjectsReady=true` ;
- metadata contexte : allocation, sélection, starvation, déduplication, refill, budget de travail ;
- MCP : stdout réservé au framing JSON-RPC, diagnostics sur stderr.

## 8.9 Persistance

- SQLite : source canonique ACID ;
- Lucene : état dérivé/reconstructible ;
- providers externes persistés : déduplication SQL ;
- verrou : `NEXUS_HOME/locks/{projectId}.lock` + `FileLock` ;
- migrations forward-only.

## 8.10 Performance

- symboles/usages bornés côté SQLite ;
- graphe via projections/voisinages SQL bornés ;
- sur-récupération fédérée contrôlée ;
- fair floor + refill ;
- budget de travail fédéré distinct du budget final ;
- embeddings batchables ;
- aucun FTS/vector DB/cache Git persistant/lifecycle Lucene partagé sans benchmark favorable.

## 8.11 Supply-chain

- JaCoCo core bloquant 70 % lignes / 50 % branches ;
- OSV delta PR + SBOM CycloneDX agrégé du reactor en gate bloquant ;
- CodeQL `security-extended` ;
- Dependabot Maven, GitHub Actions et Docker ;
- Actions contrôlées épinglées à des SHA ;
- notices tierces + SBOM distribués ;
- Docker Distribution : round-trip dotenv, Trivy, SBOM image, gate HIGH/CRITICAL corrigibles ;
- publication `main` : attestations de provenance et SBOM sur le digest image.

## 8.12 Tests et qualification

PR #49 exact-head `4f04c1ad3ff5b41aa9d1892ade57ad62b90a43f9` : NEXUS CI, Scale Benchmark, Windows Installer, Docker Distribution, CodeQL, OSV-Scanner PASS.

PR #61 exact-head `ba91be044a600d2396e0939fc154848dc47f6310` : NEXUS CI, CodeQL, OSV-Scanner PASS.

## 8.13 Déploiement et recovery

- build : `mvnw clean install` ;
- distribution : CLI/ZIP + Windows ZIP/EXE + Docker ;
- SQLite : sauvegarder avant upgrade ; migrations forward-only ;
- Lucene : supprimer/reconstruire depuis SQLite en cas de corruption ;
- index sémantique sans provenance compatible : rebuild ;
- recovery Ollama/corruption Lucene physique : watch item #54.

Voir [`../../developer/release-and-recovery.md`](../../developer/release-and-recovery.md).
