# Tasks

Prérequis : `AUD-TST-01` fermé par #268 ; `AUD-TST-07` (sprint 3) fusionné avant la tâche 2.1.

## 1. Records d'opération REST

- [x] 1.1 AUD-QUA-08 — Faire renvoyer par `NexusApiApplicationService` les records `NexusApplication.IndexOperation`, `SearchOperation`, `FederatedSearchOperation`, `ContextOperation` et `FederatedContextOperation`, supprimer les cinq copies et leur recopie champ à champ, ne garder dans le service REST que l'ajout des métriques ; vérifier par `./mvnw -B -ntp -pl adapters/rest-quarkus -am verify` (gate JaCoCo REST inclus) et `git grep -n "record SearchOperation" adapters/` sans résultat

## 2. Projection publique unique

- [ ] 2.1 AUD-QUA-09 — Créer dans le cœur la projection publique (package `com.nexus.exposure`, design D1-D3) qui produit, pour les résultats de recherche, de symbole, de contexte et de contexte fédéré, des structures à ordre de clés fixe, avec chemins projetés par `PublicProjectPathPolicy`, `excerpt` redacté et `id` ; couvrir par un test unitaire de la projection ; vérifier par `./mvnw -B -ntp -pl core test -Dtest=PublicOutputProjection* -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 2.2 AUD-QUA-09 — Brancher `CliResultMapper`, `McpResultMapper` et `ApiMapper` sur la projection, supprimer les appels directs à `PublicContextPolicy.expose` et les `relativePath` dupliqués ; vérifier par `git grep -n "PublicContextPolicy.expose" adapters/ core/src/main/java/com/nexus/cli/` sans résultat et `./mvnw -B -ntp clean install`
- [ ] 2.3 AUD-QUA-09 — Ajouter un test de parité qui compare, pour un même projet de fixture, les clés JSON produites par CLI, MCP et REST pour `search`, `find_symbol`, `context` et `context` fédéré, et échoue sur toute clé absente d'un canal sans divergence déclarée par la projection ; vérifier par `./mvnw -B -ntp -pl adapters/rest-quarkus,adapters/mcp-java -am test -Dtest=*Parity* -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] 2.4 AUD-QUA-09 — Rédiger un nouvel ADR (skill `nexus-adr`) qui complète ADR-0039 et ADR-0040 : le contrat de sortie des trois canaux est décidé par la projection publique du cœur ; mettre à jour `docs/developer/rest-api.md`, `docs/developer/mcp.md` et l'index des ADR ; vérifier par `bash .claude/scripts/contracts.sh`

## 3. Cycle context ↔ security

- [ ] 3.1 AUD-ARC-03 — Déplacer `PublicContextPolicy` de `com.nexus.security` vers `com.nexus.exposure` (après 2.2) et mettre à jour ses appelants restants (les sondes datées de `docs/project/audits/` ne sont pas compilées et restent inchangées) ; vérifier par `git grep -n "^import com.nexus.context" core/src/main/java/com/nexus/security/` sans résultat et `./mvnw -B -ntp -pl core test -Djacoco.skip=true`

## 4. Ordre déterministe

- [ ] 4.1 AUD-QUA-01 — Trier `scoreComponents` par clé dans la projection (résultats classés et éléments de contexte, simples et fédérés) ; ajouter un test qui construit les composantes dans deux ordres d'insertion différents et compare la sérialisation JSON MCP et REST à l'ordre trié ; vérifier par `./mvnw -B -ntp -pl adapters/rest-quarkus,adapters/mcp-java -am test -Dtest=*ScoreComponents*,*Parity* -Dsurefire.failIfNoSpecifiedTests=false`

## 5. Métadonnées de symbole

- [ ] 5.1 AUD-SEC-03 — Rediger dans la projection `signature`, `qualifiedName`, `source` et `target`, et faire passer `CliRenderer` (lignes du rendu humain qui concatènent la signature) par la même redaction ; ajouter un test avec une signature SCIP, MINOS et JDT contenant `password = "…"` et un jeton `ghp_…` ; vérifier par `./mvnw -B -ntp -pl core test -Dtest=PublicOutputProjection*,CliRenderer* -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` puis `-Dtest=*Parity*` sur les adaptateurs

## 6. Valeur sensible trop longue

- [ ] 6.1 AUD-SEC-02 — Remplacer l'`IllegalArgumentException` de `SecretAssignmentScanner` par une exception dédiée, la capturer par fichier dans `LuceneSearchIndex`, `SemanticIndexingService` et la construction de contexte (`ContextBundle`), exclure le seul fichier fautif et ajouter un diagnostic public sans valeur ni contenu ; mettre à jour `SensitiveContentRedactorTest` (cas 4 097 caractères) ; vérifier par `./mvnw -B -ntp -pl core test -Dtest=SensitiveContentRedactorTest,LuceneSearchIndexTest,SemanticIndexingServiceTest,ProjectIndexingServiceTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 6.2 AUD-SEC-02 — Ajouter un test d'indexation : projet de trois fichiers dont un porte `password = "<5 000 caractères>"`, l'indexation réussit, deux fichiers sont recherchables, le rapport contient un diagnostic d'exclusion, aucune sortie REST ne renvoie 400 ; vérifier par `-Dtest=ProjectIndexingServiceTest` puis `./mvnw -B -ntp -pl adapters/rest-quarkus -am test`

## 7. Couverture de redaction

- [ ] 7.1 AUD-SEC-01 — Versionner la sonde de 35 cas de l'audit en test paramétré (`SecretRedactionProbeTest` ou équivalent) : les 19 formats retenus sont redigés, les cas négatifs restent intacts, les deux formats hors périmètre (YAML bloc, valeur sur la ligne suivante) sont épinglés comme limite connue ; vérifier qu'il échoue avant la tâche 7.2 par `./mvnw -B -ntp -pl core test -Dtest=SecretRedactionProbeTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 7.2 AUD-SEC-01 — Étendre `SecretAssignmentScanner` (composants de clé) et `SensitiveContentRedactor` (jetons structurés `sk-`, `sk_live_`, `xoxb-`, `AIza`, `glpat-`, `npm_`, `Bearer`, identifiants dans une URL, bloc PGP, balise XML `<password>`, option `--password`) avec des motifs bornés (design D8) ; vérifier par `-Dtest=SecretRedactionProbeTest,SensitiveContentRedactorTest`
- [ ] 7.3 AUD-SEC-01, AUD-SEC-02 — Passer `POLICY_VERSION` à `secret-redaction-v6` et `CONTENT_PROFILE_VERSION` à 5 (`content-v5`) ; ajouter la migration d'invalidation suivante (skill `nexus-schema-migration`, motif de V010) et son test `SchemaMigrator<Sujet>UpgradeTest` ; vérifier par `./mvnw -B -ntp -pl core test -Dtest=SchemaMigrator* -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 7.4 AUD-SEC-01, AUD-SEC-02 — Mettre à jour `docs/developer/secret-redaction.md` (formats couverts, exclusion par fichier), `README.md`, `docs/architecture/overview.md`, `docs/architecture/arc42/08-concepts-transverses.md`, `docs/architecture/arc42/10-exigences-qualite.md`, `docs/developer/current-limitations.md`, `docs/developer/release-and-recovery.md`, `docs/developer/semantic-search.md` et les `require(...)` de `scripts/release/test-operational-doc-contracts.sh` (skill `nexus-redaction-change`) ; vérifier par `bash .claude/scripts/contracts.sh`

## 8. Redaction sémantique unique

- [ ] 8.1 AUD-PRF-18 — Supprimer le second appel à `SensitiveContentRedactor.redact` de `SemanticIndexingService` (le texte normalisé part du contenu déjà redigé) ; garder la redaction aux frontières CLI/REST/MCP ; vérifier par `./mvnw -B -ntp -pl core test -Dtest=SemanticIndexingServiceTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` et `git grep -c "SensitiveContentRedactor.redact" core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java` égal à 1

## 9. Validation

- [ ] 9.1 Valider l'itération (`/nx-validate full` : contrats, `./mvnw -B -ntp clean install`, `scripts/self-smoke.ps1`) et faire relire par les agents `nexus-reviewer` et `nexus-security-auditor`

## Workflow follow-up

- Ouvrir la PR vers `develop` (skill `nexus-pr-workflow`), merge exact-head en merge commit.
- Archiver le changement OpenSpec une fois la PR fusionnée.
