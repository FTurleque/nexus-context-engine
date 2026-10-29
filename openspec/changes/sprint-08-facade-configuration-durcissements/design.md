# Design

## Context

`NexusApplication` est la façade unique que CLI, REST et MCP appellent. Ses lectures indexées (`search`, `searchAcrossProjects`, `context`, `contextAcrossProjects`, `findSymbols`, `findUsages`, `inspect`) appliquent chacune à la main la séquence d'ADR-0052 : vérifier READY, prendre le verrou partagé, ouvrir une session de lecture, revalider READY. Ses fabriques (`create`, `createLongLived`) et `NexusApplicationFactory` déclarent `throws SQLException`, que `NexusCli`, `NexusMcpServer` et `NexusApiApplicationService` reprennent ou attrapent.

La configuration `NEXUS_*` est lue dans le cœur par douze classes (`NexusPaths`, `SecurityPolicy`, `ContextMaterializationLimits`, `ContextDiscoveryLimits`, `IndexingCapacityGate`, `ProjectIndexingService`, `JdtLanguageServerCodeIntelligenceProvider`, la classe JDT `ProcessBuilder`, `ProjectScanLimits`, `ScipIndexLimits`, `SemanticSearchConfiguration`, `ProjectFileLimits`). `ProjectScanLimits` et `ContextDiscoveryLimits` exposent déjà une fabrique `from(Map<String, String>)`.

Côté REST, `NexusRestExposureGuard` valide la posture au démarrage ; en trust-local, il ne demande ni jeton ni allowlist de racines, et aucun filtre ne contrôle l'en-tête `Host` ni l'en-tête `Origin`. Côté Git, `LocalGitContextSourceProvider`, `PersistentGitContextSourceProvider` et `GitRecencyCandidateEnricher` construisent le dépôt par `FileRepositoryBuilder.findGitDir(projectRoot)`, qui remonte sans limite.

## Goals / Non-Goals

**Goals:**
- Un seul chemin de code pour la lecture cohérente, mono-projet et fédérée.
- Aucune trace de JDBC dans le contrat public de la façade ni dans les adaptateurs.
- Une configuration effective résolue une fois, injectée, testable sans constructeur package-private.
- Refuser en mode REST loopback les requêtes dont l'hôte ou l'origine n'est pas loopback.
- Ne jamais lire un dépôt Git parent appartenant à un autre compte.

**Non-Goals:**
- Changer les noms, les bornes ou les valeurs par défaut des variables `NEXUS_*`.
- Centraliser la configuration propre à l'adaptateur REST (`NexusRestSecurity`, `NexusRestProjectRootPolicy`) : elle reste dans l'adaptateur, qui est sa propre composition root Quarkus.
- Activer CORS ou servir des clients navigateur.
- Rendre la remontée Git configurable.

## Decisions

**D1 — Gabarit de lecture cohérente (`AUD-ARC-09`).** Deux méthodes privées de la façade, une mono-projet et une fédérée, prennent la portée et une fonction de lecture recevant la session et les descripteurs revalidés. Elles portent l'ordre READY → verrou → session → READY et la libération dans `finally`. Les sept lectures, `inspect` compris, passent par elles. Alternative écartée : un décorateur générique hors de la façade, qui exposerait le verrou à d'autres appelants.

**D2 — Exception applicative à la frontière (`AUD-ARC-11`).** Les fabriques enveloppent `SQLException` dans `PersistenceException` (existante) avec la cause conservée ; `IOException` reste déclarée. Les adaptateurs ne traitent plus que `PersistenceException` et `IOException`. Les codes de sortie CLI et les réponses MCP/REST restent inchangés.

**D3 — Configuration résolue en composition root (`AUD-ARC-12`).** Un record `NexusConfiguration` est construit par `NexusApplicationFactory` à partir d'une `Map<String, String>` (l'environnement en production, une table en test), sur le modèle `from(Map)` déjà présent. Chaque classe de limites reçoit sa part par constructeur ou par `from(Map)`. Une règle du test d'architecture (introduit par `AUD-ARC-07`) interdit l'appel à `System.getenv` dans `core/src/main` hors de la composition root. Un nouvel ADR complète ADR-0003 (cœur sans framework : la composition root manuelle porte aussi la configuration).

**D4 — Stockage privé dans `security` (`AUD-ARC-14`).** `ensurePrivateStorage`, `ensurePrivateDirectory`, `ensurePrivateFile` et `hardenPrivateFile` vont dans une classe `security.PrivateStoragePolicy` ; `NexusPaths` redevient un record de résolution. `SearchQualityMetrics` et son test passent sous `core/src/test`. Le comportement des permissions POSIX/ACL ne change pas : `NexusPathsTest` le couvre déjà.

**D5 — Nom explicite pour le constructeur de processus JDT (`AUD-ARC-19`).** La classe devient `BoundedJdtProcessBuilder` et ses usages la nomment explicitement. La politique d'environnement et de racines de confiance est inchangée.

**D6 — Contrôle d'hôte et d'origine sur listener loopback (`AUD-SEC-04`).** Un filtre JAX-RS `@PreMatching` de l'adaptateur REST, actif dès que le listener applicatif est loopback (trust-local ou jeton loopback), refuse une requête dont l'en-tête `Host` ne désigne pas `127.0.0.1`, `localhost` ou `[::1]` (avec ou sans port), ou qui porte un en-tête `Origin` non loopback. Une requête sans `Origin` (clients en ligne de commande, assistants) reste acceptée. Le refus est une erreur 403 sans détail. Hors loopback, le garde fail-closed existant (jeton, allowlist, TLS) s'applique seul. Le risque navigateur et la recommandation de l'allowlist de racines en trust-local sont documentés dans `docs/developer/rest-exposure-security.md`. Alternative écartée : exiger une allowlist en trust-local, qui casserait l'usage local documenté ; elle reste une recommandation.

**D7 — Plafond de la découverte Git (`AUD-SEC-05`).** Les trois points de découverte passent par une fabrique unique. Le dépôt trouvé à la racine du projet ou en dessous est retenu ; un dépôt trouvé dans un répertoire parent n'est retenu que si son répertoire Git appartient à l'utilisateur courant, à l'image de `safe.directory` de Git. Sinon le contexte Git est ignoré et un diagnostic public, sans chemin absolu, l'indique. Un monorepo dont le projet est un sous-module de l'utilisateur garde son contexte. Un nouvel ADR complète ADR-0035 (contexte Git local borné).

## Risks / Trade-offs

- [Client local légitime envoyant un `Host` non loopback, par exemple via un alias de nom ou un proxy local] → refusé ; le diagnostic et la documentation indiquent d'utiliser `127.0.0.1` ou de passer en mode jeton hors loopback.
- [Propriétaire de fichier non déterminable (système de fichiers sans propriétaire, partage SMB)] → le dépôt parent est ignoré par défaut, avec diagnostic ; les systèmes de fichiers qualifiés d'ADR-0047 sont couverts par les tests Windows et Linux.
- [Refactoring de configuration étendu à douze classes] → livré classe par classe derrière la même suite de tests ; les valeurs par défaut et les messages d'erreur sont figés par les tests de limites existants.
- [Règle d'architecture dépendante de `AUD-ARC-07`] → si le test d'architecture n'est pas encore fusionné, la tâche 3.3 le signale et bloque la sortie du sprint plutôt que d'ajouter un contrôle parallèle.
