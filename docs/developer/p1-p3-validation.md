# Qualification P1 / P3 — septembre 2026

## Baseline

- HEAD de `develop` et `origin/develop` vérifié : `5634c699d707a0ef1af63c9e35c5980777e595ea`.
- Branche : `codex/fix-p1-and-eliminate-p3-debt`.
- Aucun fichier suivi modifié initialement ; `scripts/__pycache__/` et `scripts/tests/` étaient déjà non suivis et restent hors livraison.

## Sécurité

La reconnaissance des clés citées abandonnait les valeurs non citées. Une configuration JSON/YAML pouvait donc publier ses scalaires sensibles dans les contextes et index dérivés. `SecretAssignmentScanner` reconnaît les composants de clé, puis parcourt une seule ligne avec une borne de 4096 caractères par valeur. Les dépassements refusent le contenu entier ; les valeurs courtes, vides, booléennes et null sont masquées. Les marqueurs suivis de texte ne permettent pas de contourner la redaction. Les tests couvrent échappements, Unicode, CR/LF/CRLF, idempotence et faux positifs.

Des fixtures synthétiques traversent les véritables chemins CLI, REST et MCP, la politique publique, un provider local capturant les embeddings et un rebuild Lucene. V010 invalide les projets et incrémente leur génération sans modifier V009. Le profil semantic content-v4 empêche la réactivation d’embeddings content-v3, même après un rebuild uniquement lexical. Les tests partent également d’une base V009 et d’une base neuve.

## Hotspots

Le détail des composants, de leurs responsabilités et dépendances figure dans [architecture-implementation.md](architecture-implementation.md#séparation-des-capacités-internes-septembre-2026). Les anciens composants réunissaient orchestration, transport/parsing, mapping ou persistance ; les façades conservent leurs contrats publics et délèguent ces capacités à des collaborateurs de package.

| Façade | Lignes avant | Lignes après |
|---|---:|---:|
| `JdtLanguageServerCodeIntelligenceProvider` | 1241 | 344 |
| `SqliteIndexRepository` | 1129 | 111 |
| `ScipCodeIndexImporter` | 925 | 201 |
| `ProjectIndexingService` | 652 | 416 |
| `NexusMcpTools` | 596 | 279 |
| `DefaultContextBuilder` | 593 | 223 |
| `MinosCodeIndexImporter` | 593 | 110 |
| `LocalGitContextSourceProvider` | 534 | 253 |
| `CliRenderer` | 463 | 379 |
| `NexusApplication` | 557 | 416 |

Les autres classes volumineuses ont été examinées par responsabilité : `NexusPaths` et `SafeFileIO` conservent leurs politiques Windows/POSIX cohérentes ; `OllamaEmbeddingProvider` garde le protocole HTTP borné ; les caches Lucene, le scanner et le cache Git persistent leurs invariants de cycle de vie. Leur découpage supplémentaire ne réduirait pas une concentration comparable aux anciennes façades. Aucune nouvelle dépendance applicative, modification de migration historique ou hausse de seuil benchmark.

## Limites de qualification

Les benchmarks locaux constituent une comparaison de qualification, pas une preuve statistique multi-machines. Les jobs Actions Linux/Windows, CodeQL et OSV doivent encore valider le commit publié. JDT LS réel et SMB nécessitent les installations/services correspondants. Le benchmark semantic disponible a aussi exécuté Ollama local (`qwen3-embedding:0.6b`, 1024 dimensions) ; le test de confidentialité utilise un provider factice capturant ses entrées. V010 invalide logiquement les anciens dérivés : elle ne garantit pas un effacement forensique des anciennes sauvegardes ou des segments supprimés.

## Fichiers livrés

- ajouté : `adapters/mcp-java/src/main/java/com/nexus/mcp/McpProjectResolver.java`
- ajouté : `adapters/mcp-java/src/main/java/com/nexus/mcp/McpResultMapper.java`
- ajouté : `adapters/mcp-java/src/main/java/com/nexus/mcp/McpToolArguments.java`
- ajouté : `adapters/mcp-java/src/main/java/com/nexus/mcp/McpToolSchemas.java`
- modifié : `adapters/mcp-java/src/main/java/com/nexus/mcp/NexusMcpTools.java`
- modifié : `adapters/mcp-java/src/test/java/com/nexus/mcp/NexusMcpBooleanParsingTest.java`
- modifié : `adapters/mcp-java/src/test/java/com/nexus/mcp/NexusMcpFederatedScopePolicyTest.java`
- modifié : `adapters/mcp-java/src/test/java/com/nexus/mcp/NexusMcpNegativeContractTest.java`
- modifié : `adapters/mcp-java/src/test/java/com/nexus/mcp/NexusMcpServerIntegrationTest.java`
- ajouté : `adapters/mcp-java/src/test/java/com/nexus/mcp/NexusMcpToolSchemaContractTest.java`
- modifié : `adapters/rest-quarkus/src/test/java/com/nexus/api/NexusResourceTest.java`
- modifié : `core/src/main/java/com/nexus/application/NexusApplication.java`
- ajouté : `core/src/main/java/com/nexus/application/NexusApplicationFactory.java`
- modifié : `core/src/main/java/com/nexus/cli/CliRenderer.java`
- ajouté : `core/src/main/java/com/nexus/cli/CliResultMapper.java`
- ajouté : `core/src/main/java/com/nexus/context/ContextBudgetAllocator.java`
- ajouté : `core/src/main/java/com/nexus/context/ContextBuildMetadata.java`
- ajouté : `core/src/main/java/com/nexus/context/ContextPipelineState.java`
- modifié : `core/src/main/java/com/nexus/context/DefaultContextBuilder.java`
- ajouté : `core/src/main/java/com/nexus/context/NativeContextPipeline.java`
- ajouté : `core/src/main/java/com/nexus/context/TaskContextMaterializer.java`
- ajouté : `core/src/main/java/com/nexus/context/source/git/GitContextFragments.java`
- ajouté : `core/src/main/java/com/nexus/context/source/git/GitWorkingTreeContext.java`
- modifié : `core/src/main/java/com/nexus/context/source/git/LocalGitContextSourceProvider.java`
- ajouté : `core/src/main/java/com/nexus/index/ExternalCodeIntelligenceRefresher.java`
- ajouté : `core/src/main/java/com/nexus/index/IndexDocumentPublisher.java`
- modifié : `core/src/main/java/com/nexus/index/ProjectIndexingService.java`
- ajouté : `core/src/main/java/com/nexus/index/jdt/JdtDocumentMessages.java`
- modifié : `core/src/main/java/com/nexus/index/jdt/JdtLanguageServerCodeIntelligenceProvider.java`
- ajouté : `core/src/main/java/com/nexus/index/jdt/JdtLocationMapper.java`
- ajouté : `core/src/main/java/com/nexus/index/jdt/JdtProcessLauncher.java`
- ajouté : `core/src/main/java/com/nexus/index/jdt/JdtRelationCollector.java`
- ajouté : `core/src/main/java/com/nexus/index/jdt/JdtStdioSession.java`
- ajouté : `core/src/main/java/com/nexus/index/jdt/JdtSymbolMapper.java`
- ajouté : `core/src/main/java/com/nexus/index/jdt/JdtWorkspaceMessages.java`
- modifié : `core/src/main/java/com/nexus/index/minos/MinosCodeIndexImporter.java`
- ajouté : `core/src/main/java/com/nexus/index/minos/MinosDocumentParser.java`
- ajouté : `core/src/main/java/com/nexus/index/minos/MinosFactMapper.java`
- ajouté : `core/src/main/java/com/nexus/index/minos/MinosPathPolicy.java`
- ajouté : `core/src/main/java/com/nexus/index/minos/MinosPayloadReader.java`
- modifié : `core/src/main/java/com/nexus/index/scip/ScipCodeIndexImporter.java`
- ajouté : `core/src/main/java/com/nexus/index/scip/ScipDocumentParser.java`
- ajouté : `core/src/main/java/com/nexus/index/scip/ScipParseBudget.java`
- ajouté : `core/src/main/java/com/nexus/index/scip/ScipPayload.java`
- ajouté : `core/src/main/java/com/nexus/index/scip/ScipProtoReader.java`
- ajouté : `core/src/main/java/com/nexus/index/scip/ScipSnapshotMapper.java`
- ajouté : `core/src/main/java/com/nexus/index/scip/ScipWireInput.java`
- modifié : `core/src/main/java/com/nexus/persistence/sqlite/SchemaMigrator.java`
- ajouté : `core/src/main/java/com/nexus/persistence/sqlite/SqliteFileQueries.java`
- ajouté : `core/src/main/java/com/nexus/persistence/sqlite/SqliteFileWriter.java`
- ajouté : `core/src/main/java/com/nexus/persistence/sqlite/SqliteGraphQueries.java`
- modifié : `core/src/main/java/com/nexus/persistence/sqlite/SqliteIndexRepository.java`
- ajouté : `core/src/main/java/com/nexus/persistence/sqlite/SqliteIndexRows.java`
- ajouté : `core/src/main/java/com/nexus/persistence/sqlite/SqliteIndexSql.java`
- ajouté : `core/src/main/java/com/nexus/persistence/sqlite/SqliteProviderWriter.java`
- ajouté : `core/src/main/java/com/nexus/persistence/sqlite/SqliteRelationQueries.java`
- ajouté : `core/src/main/java/com/nexus/persistence/sqlite/SqliteSymbolSearch.java`
- modifié : `core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java`
- ajouté : `core/src/main/java/com/nexus/security/SecretAssignmentScanner.java`
- modifié : `core/src/main/java/com/nexus/security/SensitiveContentRedactor.java`
- ajouté : `core/src/main/resources/db/migration/V010__invalidate_scalar_secret_indexes.sql`
- modifié : `core/src/test/java/com/nexus/cli/NexusCliTest.java`
- modifié : `core/src/test/java/com/nexus/context/source/git/LocalGitContextSourceProviderTest.java`
- modifié : `core/src/test/java/com/nexus/index/jdt/JdtLanguageServerCodeIntelligenceProviderTest.java`
- modifié : `core/src/test/java/com/nexus/index/jdt/JdtRelationIdentityTest.java`
- ajouté : `core/src/test/java/com/nexus/persistence/sqlite/SchemaMigratorScalarSecretUpgradeTest.java`
- modifié : `core/src/test/java/com/nexus/persistence/sqlite/SchemaMigratorSymbolRangeUpgradeTest.java`
- modifié : `core/src/test/java/com/nexus/persistence/sqlite/SqliteWriteContentionIntegrationTest.java`
- modifié : `core/src/test/java/com/nexus/search/semantic/SemanticIndexProvenanceIntegrationTest.java`
- modifié : `core/src/test/java/com/nexus/search/semantic/SemanticIndexingServiceTest.java`
- modifié : `core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java`
- modifié : `docs/architecture.md`
- modifié : `docs/developer/architecture-implementation.md`
- modifié : `docs/developer/current-limitations.md`
- ajouté : `docs/developer/p1-p3-validation.md`
- modifié : `docs/developer/release-and-recovery.md`
- ajouté : `docs/developer/secret-redaction.md`
- modifié : `docs/developer/semantic-search.md`

Aucun fichier de production supprimé.

## Commandes et résultats

Java utilisé pour la qualification finale : Microsoft OpenJDK 21.0.12.1, via `JAVA_HOME` ; wrapper Maven du repository.

| Commande | Résultat |
|---|---|
| `git fetch origin develop` puis vérification des refs | HEAD local et distant identiques à la baseline indiquée |
| `mvnw.cmd clean verify` sur baseline | Succès |
| `mvnw.cmd -pl core -Dtest=SensitiveContentRedactorTest,SchemaMigrator*Test,SemanticIndexingServiceTest test` | Succès après correction du chemin de fixtures de migration |
| Suites ciblées JDT/SQLite, SCIP, indexation, contexte/MCP | Succès après adaptation des références internes dans les tests |
| `mvnw.cmd test` | Reactor vert |
| `mvnw.cmd clean verify` | Reactor vert |
| `mvnw.cmd clean install` | Reactor vert ; nettoyage ultérieur des imports revalidé dans le reactor final |
| `mvnw.cmd -pl core -Dtest=*Scale*BenchmarkTest,ProjectScannerCorpusBudgetBenchmarkTest,LuceneLifecycleQualificationBenchmarkTest,GitContextCacheQualificationBenchmarkTest,SemanticSearchBenchmarkTest -Dnexus.scale.benchmark.enabled=true -Dnexus.git.cache.benchmark.enabled=true -Dnexus.semantic.benchmark.enabled=true -Dnexus.scale.benchmark.profile=ci test` | Baseline : 7 tests réussis |
| Même commande avec `NativeContextDiscoveryBudgetBenchmarkTest` ajouté | Candidat : 7 réussis, 1 échec de budget temporel natif (14 342 ms / 10 000 ms) |
| `mvnw.cmd -pl core -Dtest=NativeContextDiscoveryBudgetBenchmarkTest -Dnexus.scale.benchmark.enabled=true test` | Candidat isolé : échec 11 115 ms ; baseline isolée : échec 10 419 ms. Aucun seuil relevé. |
| `git diff --check` et recherche TODO/FIXME/HACK/XXX dans les zones refactorées | Aucun problème relevé |

Les échecs intermédiaires de compilation provenaient de références aux classes extraites et d’imports invalides lors du nettoyage ; ils ont été corrigés. Aucun test n’a été désactivé. Les tests opt-in non activés par le reactor conservent leurs conditions existantes.

## CI à attendre

Les workflows `ci.yml`, `codeql.yml`, `osv-scanner.yml` doivent valider la PR. Les qualifications `scale-benchmark.yml`, `scanner-corpus-benchmark.yml`, `git-context-cache-qualification.yml`, `lucene-lifecycle-benchmark.yml`, `filesystem-semantics-qualification.yml`, `semantic-search-qualification.yml` et `runtime-flags-qualification.yml` restent à exécuter selon leurs triggers. SMB, distributions Docker et installateur Windows exigent leurs runners/environnements dédiés. Aucun résultat CodeQL/OSV local n’est revendiqué.

La qualification native de performance reste en échec local, y compris sur la baseline. La mission ne peut donc pas être déclarée entièrement validée selon tous ses critères de sortie ; une mesure sur le runner de référence est requise.

## Taille des collaborateurs ajoutés

| Composant | Lignes |
|---|---:|
| `McpProjectResolver` | 60 |
| `McpResultMapper` | 159 |
| `McpToolArguments` | 105 |
| `McpToolSchemas` | 51 |
| `NexusApplicationFactory` | 166 |
| `CliResultMapper` | 103 |
| `ContextBudgetAllocator` | 163 |
| `ContextBuildMetadata` | 129 |
| `ContextPipelineState` | 23 |
| `NativeContextPipeline` | 134 |
| `TaskContextMaterializer` | 41 |
| `GitContextFragments` | 138 |
| `GitWorkingTreeContext` | 179 |
| `ExternalCodeIntelligenceRefresher` | 85 |
| `IndexDocumentPublisher` | 208 |
| `JdtDocumentMessages` | 44 |
| `JdtLocationMapper` | 88 |
| `JdtProcessLauncher` | 39 |
| `JdtRelationCollector` | 194 |
| `JdtStdioSession` | 329 |
| `JdtSymbolMapper` | 283 |
| `JdtWorkspaceMessages` | 47 |
| `MinosDocumentParser` | 206 |
| `MinosFactMapper` | 196 |
| `MinosPathPolicy` | 56 |
| `MinosPayloadReader` | 95 |
| `ScipDocumentParser` | 198 |
| `ScipParseBudget` | 41 |
| `ScipPayload` | 35 |
| `ScipProtoReader` | 117 |
| `ScipSnapshotMapper` | 276 |
| `ScipWireInput` | 98 |
| `SqliteFileQueries` | 174 |
| `SqliteFileWriter` | 145 |
| `SqliteGraphQueries` | 218 |
| `SqliteIndexRows` | 79 |
| `SqliteIndexSql` | 82 |
| `SqliteProviderWriter` | 202 |
| `SqliteRelationQueries` | 109 |
| `SqliteSymbolSearch` | 254 |
| `SecretAssignmentScanner` | 132 |

## Comparaison locale des mesures

| Mesure | Develop | Candidat |
|---|---:|---:|
| SQLite exact, 10 000 symboles, p95 ms | 11,249 | 10,455 |
| SQLite exact, 100 000 symboles, p95 ms | 61,659 | 57,519 |
| Graphe, p95 ms | 3 271,290 | 3 031,075 |
| Scanner corpus, ms | 11 515 | 11 204 |
| Fédération, ms | 120 | 118 |

Ces mesures ne prouvent pas une absence de régression statistique : un seul passage de qualification par révision, matériel et charge Windows locaux. Les assertions des sept benchmarks correspondants passent ; la découverte native reste le seul échec local de cette série.

## Revue de sécurité et de compatibilité

La revue des extractions a vérifié les portées transactionnelles JDBC, la même instance `SqliteDatabase` pour les sessions, la fermeture des ressources, les verrous par projet, les limites de transport SCIP/MINOS et les timeouts externes. Le lancement JDT continue d’utiliser le `ProcessBuilder` sécurisé du package et `JdtJsonRpcFrameReader` ; l’allowlist d’environnement et les racines de confiance restent en place. `ProjectPathGuard`, `SafeFileIO`, les politiques Windows SID/ACL, Ollama et le gate REST distant ne sont pas contournés. Les adapters délèguent à `NexusApplication` et les résultats publics gardent leurs politiques de sanitation. Les noms et paramètres des neuf tools MCP sont vérifiés par un test contractuel dédié. Les limites numériques et les requêtes SQLite sont conservées.

## Reactor final

`mvnw.cmd clean verify install` : succès sur les cinq modules. Rapports Surefire : core 493 tests (24 conditionnels ignorés par leurs conditions préexistantes), REST 53, MCP 15, intégrations assistants 24 ; aucune erreur ni défaillance. Les huit benchmarks opt-in ont été exécutés séparément, avec les résultats détaillés ci-dessus.

`scripts/self-smoke.ps1` après installation : **SELF-SMOKE SUCCESS**, 13 étapes, JAR autonome, double indexation, recherche explicable, contexte multi-source, instructions, Markdown, Agent Skills à divulgation progressive et contexte Git.
