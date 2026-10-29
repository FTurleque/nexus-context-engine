# Proposal

## Why

La sortie publique de NEXUS est projetée trois fois : `CliResultMapper` (103 lignes), `McpResultMapper` (159 lignes) et `ApiMapper` (180 lignes) réappliquent chacun `PublicContextPolicy.expose`, la projection des chemins et le choix des champs. Les trois canaux divergent sur les mêmes objets (`path` repository contre chemin relatif, `excerpt` et `id` absents de la CLI, ordre de `scoreComponents`), et la règle de parité CLI/REST/MCP repose sur une relecture manuelle. Tant que la projection n'est pas unique, chaque correctif de sortie (ordre, signatures, redaction) se fait trois fois.

Sur la même frontière, la redaction présente trois lacunes : 19 formats de secrets courants sortent en clair (`Authorization: Bearer`, `token=`, `sk-`, `xoxb-`, `AIza`, `glpat-`, bloc PGP…), les signatures de symboles fournies par SCIP, MINOS ou JDT sortent sans redaction, et une seule valeur sensible de plus de 4 096 caractères fait échouer l'indexation du projet entier.

## What Changes

- `NexusApiApplicationService` renvoie directement les records d'opération de `NexusApplication` (`IndexOperation`, `SearchOperation`, `FederatedSearchOperation`, `ContextOperation`, `FederatedContextOperation`) au lieu de leurs copies (AUD-QUA-08).
- Une projection publique unique vit dans le cœur et produit, pour chaque résultat exposé, une structure ordonnée que CLI, MCP et REST sérialisent sans la recomposer ; un test de parité compare les clés produites par les trois canaux (AUD-QUA-09, qui absorbe AUD-ARC-08).
- `PublicContextPolicy` quitte le package `security`, qui ne dépend plus des bundles de contexte ; le cycle `context` ↔ `security` disparaît (AUD-ARC-03).
- La projection publique ordonne `scoreComponents` par clé ; deux exécutions dans deux JVM produisent le même JSON (AUD-QUA-01).
- La projection publique redige `signature`, `qualifiedName` et les extrémités de relation issues de producteurs externes, sur les trois canaux et dans le rendu humain de la CLI (AUD-SEC-03).
- Une valeur sensible trop longue exclut uniquement le fichier qui la porte, avec un diagnostic public ; le reste du projet s'indexe et la construction de contexte écarte le fragment concerné (AUD-SEC-02).
- La politique de redaction couvre les 19 formats retenus ; la sonde de 35 cas de l'audit devient un test versionné ; `POLICY_VERSION` passe à `secret-redaction-v6`, le profil sémantique à `content-v5`, et une migration invalide les index dérivés (AUD-SEC-01).
- `SemanticIndexingService` redige une seule fois chaque document avant l'embedding (AUD-PRF-18).

Constats du sprint déjà fermés : aucun. Prérequis : `AUD-TST-01` est fermé par #268 (`quarkus-jacoco` présent dans `adapters/rest-quarkus/pom.xml`) ; `AUD-TST-07` (sprint 3) reste ouvert sur `origin/develop`.

## Capabilities

### New Capabilities
- `public-output-projection` : projection unique des résultats publics (champs, chemins, ordre, redaction des métadonnées de symboles) partagée par CLI, REST et MCP.
- `secret-redaction` : formats de secrets couverts, comportement sur valeur trop longue, version de politique et invalidation des index dérivés.
- `core-package-boundaries` : dépendances autorisées entre packages du cœur ; capability partagée avec les sprints 3 et 7, ce changement y ajoute l'exigence sur `security` (AUD-ARC-03).

### Modified Capabilities

(aucune : `openspec/specs/` est vide)

## Impact

- Code : `core/src/main/java/com/nexus/security/` (`PublicContextPolicy` déplacée, `SensitiveContentRedactor`, `SecretAssignmentScanner`), nouveau package de projection publique dans `core/src/main/java/com/nexus/`, `core/src/main/java/com/nexus/cli/CliResultMapper.java` et `CliRenderer.java`, `adapters/mcp-java/.../McpResultMapper.java`, `adapters/rest-quarkus/.../ApiMapper.java` et `NexusApiApplicationService.java`, `core/src/main/java/com/nexus/search/lucene/LuceneSearchIndex.java`, `core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java`, `core/src/main/java/com/nexus/context/ContextBundle.java`.
- Schéma : une migration d'invalidation `Vnnn__invalidate_<sujet>_indexes.sql`, numéro suivant disponible (V011 si aucune autre migration n'est fusionnée avant).
- Contrat public : clés JSON alignées entre canaux (la CLI gagne `excerpt` et `id` si la parité les retient, MCP et REST ordonnent `scoreComponents`). Les formats de sortie changent pour les clients qui dépendaient d'une divergence.
- Opérations : reconstruction des index lexicaux et sémantiques à la mise à jour ; documentée dans `docs/developer/release-and-recovery.md`.
- Documentation : `docs/developer/secret-redaction.md`, `docs/developer/current-limitations.md`, `docs/developer/semantic-search.md`, `README.md`, `docs/architecture.md`, vues arc42 08 et 10, `scripts/release/test-operational-doc-contracts.sh`.
- Hors périmètre : scalaires YAML de bloc et valeurs sur la ligne suivante (exclus par `secret-redaction.md`), qui relèvent d'un changement de politique séparé.
