# Proposition

## Why

L'audit du 16/11/2026 regroupe dans son sprint 7, « Hygiène du code, des scripts et de l'installateur », cinq constats de sévérité faible : `AUD-QUA-03`, `AUD-QUA-07`, `AUD-QUA-09`, `AUD-QUA-10` et `AUD-DEP-07`. Aucun n'est une fuite ni une panne. Ils augmentent le coût de chaque évolution : méthodes de plus de 100 lignes dans le contexte Git et le ranking, utilitaires recopiés avec des sémantiques divergentes, 1 150 lignes de Python embarquées dans un script Bash, empreintes de l'installateur recopiées à la main dans plusieurs fichiers, et une construction Windows qui peut recopier dans le ZIP un reste de la distribution précédente.

Le critère de sortie du sprint (`docs/project/audits/2026-11-16/sprints.md`, section S7) : « LocalGitContextSourceProvider.discover, SemanticHybridContextRanker.rank et ScipSnapshotMapper.importDocument font moins de 80 lignes, et une règle outillée (test d'architecture ou script de contrats) en garde la mesure. Un seul safeMessage interne et un seul utilitaire de fermeture restent dans le main. build-windows-distribution.ps1 et build-windows-installer.ps1 échouent si la distribution précédente subsiste. Les heredocs Python de test-operational-doc-contracts.sh sont des modules scripts/release/*.py testés. Les empreintes de l'installateur viennent de config/tool-integrity.properties seul. »

Le sprint passe après les chantiers de fond pour ne pas réécrire deux fois les mêmes fichiers (`sprints.md`, « Pourquoi ici »). Prérequis : `AUD-QUA-07` dépend de `AUD-QUA-02` (sprint 5, change `audit-1116-s5-frontieres-coeur-api-index`), qui retire le chemin d'écriture d'index par lot et touche `IndexDocumentPublisher`, `LuceneSearchIndex` et `SemanticIndexingService`. `AUD-QUA-03` dépend de `AUD-QUA-04` (sprint 6, change `audit-1116-s6-contrats-adaptateurs-contexte`), qui type les métadonnées de `ContextBuildMetadata`.

Sources : `docs/project/audits/2026-11-16/findings.md` et `findings.json` (champ `constats`), `sprints.md`, `sprints-meta.json`, `plan-action.md`, `README.md`. Le code n'a pas changé depuis le commit audité `7ec5c5dc`, hors `scripts/release/test-final-audit-contracts.sh` (+4 −1 lignes). Les preuves ci-dessous sont relues au HEAD `6e0a5aaf`.

## What Changes

### AUD-QUA-03 — Méthodes longues du cœur

- [Existant vérifié] Mesure par accolades (de la ligne de déclaration à l'accolade fermante incluse, records exclus) rejouée au HEAD : neuf méthodes du code de production dépassent 80 lignes, avec les valeurs de l'audit. `NexusApplicationFactory.create` (`core/src/main/java/com/nexus/application/NexusApplicationFactory.java:125`, 116 lignes), `LocalGitContextSourceProvider.discover` (`core/src/main/java/com/nexus/context/source/git/LocalGitContextSourceProvider.java:64`, 108), `SemanticHybridContextRanker.rank` (`core/src/main/java/com/nexus/ranking/SemanticHybridContextRanker.java:68`, 95), `ProjectScanner.scanWithDiagnostics` (`core/src/main/java/com/nexus/index/scan/ProjectScanner.java:79`, 92), `AiSkillsRegistryProvider.discover` (`core/src/main/java/com/nexus/context/source/skill/AiSkillsRegistryProvider.java:36`, 85), `ScipSnapshotMapper.importDocument` (`core/src/main/java/com/nexus/index/scip/ScipSnapshotMapper.java:55`, 84), `BudgetedContextSelector.select` (`core/src/main/java/com/nexus/context/BudgetedContextSelector.java:34`, 83), `ContextBuildMetadata.metadata` (`core/src/main/java/com/nexus/context/ContextBuildMetadata.java:35`, 83), `JdtLanguageServerCodeIntelligenceProvider.analyze` (`core/src/main/java/com/nexus/index/jdt/JdtLanguageServerCodeIntelligenceProvider.java:138`, 82). Aucune méthode des adaptateurs ne dépasse 75 lignes.
- [Existant vérifié] Aucun outil du dépôt ne mesure la longueur des méthodes : `core/src/test/java/com/nexus/architecture/CoreArchitectureTest.java` contrôle les imports, les noms pleinement qualifiés et la baseline des cycles (`:157-170`), pas les corps de méthodes.
- [Décision] Découper `discover`, `rank` et `importDocument` en étapes privées nommées, sans changer leur comportement, comme l'a été `FederatedContextService.build` (action de l'audit). `NexusApplicationFactory.create` reste une composition linéaire, acceptée par l'audit précédent.
- [Exigence] Le test d'architecture refuse toute méthode ou tout constructeur du code de production du cœur qui atteint 80 lignes et n'est pas dans une liste figée versionnée ; cette liste ne peut que décroître. Le seuil de 80 lignes est celui du critère de sortie du sprint 7.

### AUD-QUA-07 — Utilitaires recopiés

- [Existant vérifié] Trois `safeMessage` aux sémantiques différentes. `core/src/main/java/com/nexus/cli/NexusCli.java:325-328` rend le message, ou le nom simple de la classe s'il est vide. `core/src/main/java/com/nexus/context/source/git/LocalGitContextSourceProvider.java:245-251` rend `Type: message`, inséré dans un diagnostic (`:168`). `adapters/mcp-java/src/main/java/com/nexus/mcp/NexusMcpTools.java:260-271` projette l'exception vers trois messages publics, dont un passe par `PublicDiagnosticPolicy.internal().text`. Le message brut de la CLI est filtré en aval par `core/src/main/java/com/nexus/cli/CliRenderer.java:258`.
- [Existant vérifié] Quatre `closeQuietly` : `core/src/main/java/com/nexus/index/ProjectIndexLockManager.java:234` (canal fermé, échec ignoré), `core/src/main/java/com/nexus/index/service/IndexDocumentPublisher.java:81` et `core/src/main/java/com/nexus/search/lucene/LuceneSearchIndex.java:155` (échec ajouté en `suppressed`), `core/src/main/java/com/nexus/persistence/sqlite/SqliteFileWriter.java:272` (plusieurs instructions, `null` ignoré, échec ajouté en `suppressed`).
- [Existant vérifié] Noms pleinement qualifiés hors import : 7 dans `core/src/main/java/com/nexus/context/ContextFragmentFactory.java` (`:151-184`), d'autres dans `ContextBudgetAllocator.java:101-104`, `SkillContextSelector.java:38-43`. Un proxy dynamique en lignes denses dans `core/src/main/java/com/nexus/persistence/sqlite/SqliteDatabase.java:57-62`.
- [Écart] Le total de 42 noms pleinement qualifiés vient du script de l'audit, qui n'est pas dans le dépôt. Un relevé par `grep` au HEAD en trouve davantage, car il compte aussi les chaînes et les `java.*`. Le chiffre n'est donc pas un critère de vérification : la tâche remplace tous ceux que relève une commande versionnée dans la tâche, hors collision de noms simples.
- [Décision] Un seul utilitaire interne de message d'exception et un seul utilitaire de fermeture `Closeables.closeSuppressing` dans le code de production. La projection MCP reste une projection publique distincte, renommée pour ne plus porter le nom `safeMessage`. Les noms pleinement qualifiés deviennent des imports, et le proxy de `SqliteDatabase` une classe nommée.
- [Décision] Refactoring sans exigence nouvelle : les réponses CLI, REST et MCP restent celles que fixent `core-package-boundaries › Le découpage ne change aucun comportement observable` et les tests de contrat existants (`NexusMcpErrorSanitizationTest`, `NexusMcpNegativeContractTest`).

### AUD-QUA-09 — Scripts

- [Existant vérifié] `scripts/compare-jdt.ps1:19` et `scripts/compare-scip.ps1:21` définissent la même fonction `Measure-Quality` de 40 lignes (`diff` vide). `Get-JavaVersionText` et `Get-JavaMajorVersion` sont définies dans `scripts/validate-minos-integration.ps1:28,59` et `scripts/validate-phase-6.ps1:10,35`.
- [Écart] L'audit présente ces deux fonctions Java comme recopiées. Les deux copies ont le même nom et le même rôle mais pas le même texte (paramètre `$Executable` contre `$JavaExecutable`, lecture de la version différente) : les regrouper impose de choisir une implémentation.
- [Existant vérifié] `scripts/release/test-operational-doc-contracts.sh` (1 269 lignes) embarque cinq blocs `python3 - <<'PY'` (lignes 4-733, 821-925, 927-970, 1008-1137, 1140-1269) et redéfinit `text` (`:12`, `:828`, `:1015`) et `require` (`:19`, `:1022`). Il contient 88 appels `require(`/`forbid(` en début de ligne. Les tests Python du dépôt (`scripts/tests/test_scale_budgets.py`) ne couvrent aucun de ces blocs.
- [Existant vérifié] 15 des 30 scripts `.ps1` suivis sous `scripts/` hors `scripts/archive/` n'appellent pas `Set-StrictMode`, dont `scripts/self-smoke.ps1`, `scripts/nexus.ps1` et `scripts/validate-phase-6.ps1`.
- [Écart] L'audit compte 31 scripts `.ps1` hors archive, le dépôt en suit 30 au HEAD. Le nombre de scripts sans mode strict (15) concorde.
- [Exigence] `Measure-Quality`, `Get-JavaVersionText` et `Get-JavaMajorVersion` ne sont définies que dans `scripts/lib/NexusScript.psm1`. Une redéfinition locale fait échouer le contrat.
- [Exigence] Chaque script PowerShell actif de `scripts/` active `Set-StrictMode -Version Latest`. Un script qui ne le fait pas fait échouer le contrat.
- [Exigence] Les contrats de `test-operational-doc-contracts.sh` s'exécutent depuis des modules Python versionnés sous `scripts/release/`, qui partagent une seule bibliothèque `require`/`forbid` et ont leurs tests unitaires, lancés par le même contrat.

### AUD-QUA-10 — Nettoyage de la sortie précédente

- [Existant vérifié] `scripts/release/build-windows-distribution.ps1:214-215` supprime la sortie précédente avec `-ErrorAction SilentlyContinue`, puis recrée les dossiers (`:216`) et recopie dedans. `scripts/release/build-windows-installer.ps1:112` fait de même pour le `.iss` généré, le `setup.exe` et son `.sha256`.
- [Exigence] Une construction Windows (distribution ou installateur) échoue en nommant le chemin quand un élément de la sortie précédente subsiste après sa suppression, avant toute copie.

### AUD-DEP-07 — Empreintes de l'installateur

- [Existant vérifié] Le template versionné télécharge des URL flottantes : `packaging/windows/nexus-installer.iss.template:457` (Docker Desktop) et `:1268` (Ollama). `scripts/release/harden-windows-installer-source.ps1` (399 lignes, 15 occurrences de `Replace-ExactlyOnce`) remplace au build ces URL par les versions figées et insère `VerifyFileSha256` (`:349`, `:362`, `:390`).
- [Existant vérifié] Les deux empreintes SHA-256 sont recopiées dans `scripts/release/harden-windows-installer-source.ps1:362,390`, `scripts/release/build-windows-installer.ps1:182,184` et `scripts/release/test-windows-installer.ps1:102,104`. `config/tool-integrity.properties` ne porte que l'empreinte Linux d'Ollama (`:6`).
- [Écart] Hors audit : `distribution/README.md:56-57` recopie aussi les deux empreintes, quatrième copie.
- [Exigence] Les empreintes SHA-256 de Docker Desktop et d'Ollama que vérifie l'installateur viennent de `config/tool-integrity.properties`, seule source versionnée. La construction de l'installateur échoue si une clé manque ou est mal formée.

## Type de changement

outillage (scripts, contrats, build de l'installateur) avec un refactoring du cœur sans effet observable : profil court. Les sections de conception qui touchent l'installateur et le test d'architecture sont traitées, car elles portent des exigences.

## Périmètre et exclusions

**Dans le périmètre :**
- Découpage de `LocalGitContextSourceProvider.discover`, `SemanticHybridContextRanker.rank` et `ScipSnapshotMapper.importDocument` ; règle de longueur des méthodes dans le test d'architecture et sa liste figée.
- Utilitaire unique de message d'exception, `Closeables.closeSuppressing`, imports à la place des noms pleinement qualifiés, classe nommée pour le proxy de `SqliteDatabase`.
- `Measure-Quality`, `Get-JavaVersionText`, `Get-JavaMajorVersion` dans `scripts/lib/NexusScript.psm1` ; `Set-StrictMode -Version Latest` dans les scripts actifs ; extraction des cinq blocs Python de `test-operational-doc-contracts.sh` en modules testés.
- Échec explicite des deux scripts de construction Windows sur une sortie précédente non supprimée.
- Empreintes de Docker Desktop et d'Ollama lues dans `config/tool-integrity.properties` par la construction de l'installateur et par son test ; template épinglé ; `harden-windows-installer-source.ps1` réduit à ce qui ne peut pas vivre dans le template.

**Hors périmètre :**
- Les six autres méthodes de plus de 80 lignes (`create`, `scanWithDiagnostics`, `AiSkillsRegistryProvider.discover`, `select`, `metadata`, `analyze`) : elles entrent dans la liste figée, que le change ne vide pas.
- Les six `catch (OversizedSecretException …)` relevés par le titre d'`AUD-QUA-07` : l'action de l'audit ne demande pas de les factoriser. Seuls leurs noms pleinement qualifiés deviennent des imports.
- Les trois blocs Python de `scripts/release/test-final-audit-contracts.sh` et les deux scripts `measure-iteration-17-real-semantic*.ps1` presque identiques : le critère de sortie ne les cite pas (voir les questions ouvertes).
- Les versions qualifiées de Docker Desktop (4.86.0, build 236216) et d'Ollama (v0.33.3) : elles ne changent pas.
- Tout changement des réponses CLI, REST et MCP, des formats SQLite et Lucene et de la politique de redaction.

## Capabilities

### New Capabilities

### Modified Capabilities
- `core-package-boundaries` : exigence ajoutée, les méthodes du cœur restent sous 80 lignes, hors liste figée qui ne peut que décroître.
- `powershell-script-tooling` : « Un module partagé porte les fonctions communes des scripts » réécrite pour `Measure-Quality`, `Get-JavaVersionText` et `Get-JavaMajorVersion` ; exigence ajoutée sur le mode strict des scripts actifs.
- `operational-documentation-contracts` : exigence ajoutée, les contrats documentaires opérationnels s'exécutent depuis des modules Python testés.
- `build-reproducibility` : exigence ajoutée, une construction Windows échoue si la sortie précédente subsiste.
- `installer-client-configuration` : exigence ajoutée, les empreintes des programmes d'installation téléchargés viennent du fichier d'intégrité versionné.

## Impact

- Code du cœur : `core/src/main/java/com/nexus/context/source/git/LocalGitContextSourceProvider.java`, `core/src/main/java/com/nexus/ranking/SemanticHybridContextRanker.java`, `core/src/main/java/com/nexus/index/scip/ScipSnapshotMapper.java`, `core/src/main/java/com/nexus/cli/NexusCli.java`, `ProjectIndexLockManager.java`, `IndexDocumentPublisher.java`, `LuceneSearchIndex.java`, `SqliteFileWriter.java`, `SqliteDatabase.java`, `ContextFragmentFactory.java` et les autres fichiers à noms pleinement qualifiés ; un nouvel utilitaire `Closeables` ; `adapters/mcp-java/src/main/java/com/nexus/mcp/NexusMcpTools.java` (renommage de la projection).
- Tests : `core/src/test/java/com/nexus/architecture/CoreArchitectureTest.java`, nouvelle baseline `core/src/test/resources/architecture/long-methods.txt` ; tests existants de `LocalGitContextSourceProvider`, `SemanticHybridContextRanker`, `ScipSnapshotMapper` ; `adapters/mcp-java/src/test/java/com/nexus/mcp/NexusMcpErrorSanitizationTest.java` et `NexusMcpNegativeContractTest.java` (nom de méthode).
- Scripts : `scripts/lib/NexusScript.psm1`, `scripts/tests/Test-NexusScript.ps1`, les 15 scripts sans mode strict, `scripts/compare-jdt.ps1`, `scripts/compare-scip.ps1`, `scripts/validate-minos-integration.ps1`, `scripts/validate-phase-6.ps1`, `scripts/release/build-windows-distribution.ps1`, `scripts/release/build-windows-installer.ps1`, `scripts/release/test-windows-installer.ps1`, `scripts/release/harden-windows-installer-source.ps1`, `scripts/release/test-operational-doc-contracts.sh`, nouveaux modules `scripts/release/*.py` et leurs tests sous `scripts/tests/`.
- Installateur et intégrité : `packaging/windows/nexus-installer.iss.template`, `config/tool-integrity.properties`, `scripts/release/test-tool-integrity-anchors.sh`, `distribution/README.md`.
- Documentation durable : `docs/developer/architecture-implementation.md` (section `openspec-core-package-boundaries`), `docs/developer/ci-and-supply-chain.md` (sections `openspec-powershell-script-tooling`, `openspec-operational-documentation-contracts`, `openspec-build-reproducibility`), `docs/developer/release-and-recovery.md` (section `openspec-installer-client-configuration`), `docs/developer/openspec-traceability.md`.
- ADR applicables, sans nouvel ADR : `ARCH-0076` et l'ADR qui le complète au sprint 5 (sens des dépendances, pour la place de `Closeables`), `GEN-0057`. Les décisions de ce change sont locales (outillage, refactoring) et réversibles.
- Effet utilisateur : aucun sur les sorties. Un poste de build où un fichier de l'ancienne distribution est verrouillé voit désormais la construction échouer au lieu de produire un ZIP contaminé.

## Questions ouvertes

- [Question] Faut-il étendre l'extraction aux trois blocs Python de `scripts/release/test-final-audit-contracts.sh` (l'action de l'audit vise les heredocs en général, le critère de sortie seulement `test-operational-doc-contracts.sh`) ? Par défaut : non, suivi séparé.
- [Question] Faut-il fusionner `scripts/measure-iteration-17-real-semantic.ps1` et `-diagnostic.ps1` (155 lignes chacun, 20 lignes de diff), ou les laisser comme scripts de mesure historiques ? Par défaut : les laisser, avec le mode strict.
- [Question] `distribution/README.md` est livré dans le ZIP sans `config/tool-integrity.properties`. Faut-il y garder les empreintes, vérifiées égales au fichier d'intégrité par le contrat, ou les retirer et ne garder que les versions ? Proposition par défaut : les garder, vérifiées par le contrat.
- [Question] Le diagnostic « contexte Git indisponible » perd le nom du type d'exception quand le message est présent si l'on applique l'utilitaire unique tel que le définit la CLI. Ce diagnostic n'est couvert par aucune spec ni aucun test. Faut-il garder le type ?
