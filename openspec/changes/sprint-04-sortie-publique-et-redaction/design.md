# Design

## Context

Trois mappers projettent la sortie publique : `CliResultMapper` (cœur), `McpResultMapper` (`adapters/mcp-java`) et `ApiMapper` (`adapters/rest-quarkus`). Chacun appelle `com.nexus.security.PublicContextPolicy.expose(...)` (`CliRenderer.java:194`, `McpResultMapper.java:106`, `ApiMapper.java:97`), calcule ses chemins (`repositoryPath` côté CLI, `relativePath` via `PublicProjectPathPolicy` côté MCP, dupliqué) et choisit ses champs. La CLI trie `scoreComponents` (`new TreeMap<>`), MCP et REST transmettent le `Map.copyOf` de `RankedCandidate` et `ContextItem`, dont l'ordre d'itération varie d'une JVM à l'autre. Les signatures de symboles sortent telles quelles (`McpResultMapper.java:89`, `ApiMapper.java:150`, `CliResultMapper.java:90`, `CliRenderer.java:178` et `:322`).

`PublicContextPolicy` importe `ContextBundle`, `ContextItem`, `FederatedContextBundle` et `FederatedContextItem`, alors que `ContextFragmentFactory` importe `ProjectPathGuard`, `SafeFileIO` et `SensitiveContentRedactor` : `security` et `context` forment un cycle.

La redaction (`SensitiveContentRedactor`, `SecretAssignmentScanner`, politique `secret-redaction-v5`, profil sémantique `content-v4`) s'applique avant l'indexation lexicale (`LuceneSearchIndex.java:255`), avant l'embedding (`SemanticIndexingService.java:202` et `:210`, deux fois sur le même document) et dans `ContextBundle`. Une valeur au-delà de `MAX_SECRET_CHARS = 4096` lève `IllegalArgumentException` (`SecretAssignmentScanner.java:127-129`), non capturée dans les boucles d'indexation.

Le REST recopie les cinq records d'opération de `NexusApplication` (`NexusApiApplicationService.java:227-272`).

### Dépendances envers d'autres sprints

- `AUD-TST-01` (sprint 1) est fermé par #268 : la couverture de `NexusApiApplicationService` est mesurée, la refonte AUD-QUA-08 est vérifiable par JaCoCo.
- `AUD-TST-07` (sprint 3) est un prérequis d'AUD-QUA-09 : les tests E2E `NexusResourceTest` et `NexusMcpServerIntegrationTest` ont encore un seul `@Test` chacun sur `origin/develop`. Le découpage par endpoint et par outil doit être fusionné avant la tâche 2, sans quoi une divergence de projection masque les contrats suivants.
- Le sprint 7 (AUD-ARC-01, bloc cyclique du cœur) dépend d'AUD-ARC-03.

## Goals / Non-Goals

**Goals:**
- Une seule projection publique, dans le cœur, consommée par les trois canaux.
- Une sortie JSON identique d'une JVM à l'autre pour la même requête.
- Aucune métadonnée de symbole d'origine externe ne sort sans redaction.
- Une valeur sensible trop longue ne bloque que son fichier.
- Couverture des 19 formats retenus, avec reconstruction des dérivés.

**Non-Goals:**
- Unifier le rendu humain de la CLI avec le JSON (seuls ses champs sensibles passent par la projection).
- Couvrir les scalaires YAML de bloc et les valeurs sur la ligne suivante.
- Modifier le ranking ou la sélection sous budget.

## Decisions

**D1 — Projection publique dans un package dédié du cœur.** Un package `com.nexus.exposure` porte la projection publique et accueille `PublicContextPolicy` déplacée depuis `security`. Il dépend de `context`, `ranking`, `search` et `security` ; `security` ne dépend plus d'aucun package métier hors `config`, `paths` et `project`. Alternative écartée : ranger la projection dans `context`, qui mêlerait modèle de contexte et format de sortie. AUD-QUA-09 précède AUD-ARC-03 : le déplacement se fait une fois la projection unique en place, pour ne pas déplacer trois appels qui vont disparaître.

**D2 — La projection produit des structures ordonnées, pas du JSON.** Elle renvoie des `Map` à ordre déterministe (`LinkedHashMap` construite dans un ordre de clés fixe, `TreeMap` pour les composantes de score) ou des records ; chaque adaptateur sérialise sans ajouter, retirer ni renommer de clé. Les constantes de noms de champs (`ESTIMATED_TOKENS_FIELD`…) vivent dans la projection. Une divergence voulue entre canaux est déclarée par la projection elle-même, jamais réimplémentée dans l'adaptateur.

**D3 — Parité arbitrée vers le contrat le plus complet et le plus sûr.** Chemins : chemin relatif au projet via `PublicProjectPathPolicy` sur les trois canaux. `excerpt` (redacté) et `id` sont produits pour tous les canaux. Le choix exact est figé par le test de parité ; tout écart retenu est documenté dans `docs/developer/rest-api.md` et `docs/developer/mcp.md`.

**D4 — Tri dans la projection, records inchangés.** `scoreComponents` est trié par clé dans la projection. `RankedCandidate` et `ContextItem` gardent `Map.copyOf` : l'ordre interne n'est pas un contrat, seul l'ordre exposé l'est.

**D5 — Redaction des métadonnées de symbole à la sortie.** `signature`, `qualifiedName`, `source` et `target` passent par `SensitiveContentRedactor.redact` dans la projection. La persistance reste inchangée : pas d'invalidation d'index pour ce point. Alternative écartée : rediger à la persistance via `CodeIntelligenceMetadataPolicy`, qui imposerait une migration pour un gain nul tant que toute sortie traverse la projection.

**D6 — Exclusion du seul fichier fautif.** Le dépassement de `MAX_SECRET_CHARS` reste fail-closed, mais lève une exception dédiée (`OversizedSecretException` ou équivalent) que l'indexation lexicale, l'indexation sémantique et la construction de contexte capturent par fichier : le fichier est exclu, un diagnostic public sans contenu ni valeur est ajouté au rapport d'indexation, le lot continue. Alternative écartée : rediger jusqu'à la fin de ligne, dont la borne n'est pas fiable pour une valeur de plusieurs kilo-octets sans séparateur ; le critère de sortie du sprint retient l'exclusion.

**D7 — Une seule montée de politique.** Les 19 formats (AUD-SEC-01) et l'exclusion par fichier (AUD-SEC-02) partagent une seule montée : `POLICY_VERSION = "secret-redaction-v6"`, `CONTENT_PROFILE_VERSION = 5` (`content-v5`), une migration d'invalidation sur le motif de `V010__invalidate_scalar_secret_indexes.sql` (skill `nexus-redaction-change`). Le numéro de migration est le suivant disponible au moment de l'implémentation. AUD-PRF-18 ne change pas le contenu redigé et ne demande pas de montée.

**D8 — Motifs bornés.** Chaque nouveau motif utilise des quantificateurs possessifs et une longueur maximale `MAX_SECRET_CHARS`, comme `STRUCTURED_TOKEN`. Les composants de clé ajoutés (`token`, `auth`, `pwd`, `credentials`, `private_key`, `access_key`, `secretKey`, `Authorization`) sont comparés par segment pour éviter les faux positifs (`tokenizer`, `author`).

**D9 — ADR.** La projection publique unique fixe où se décide le contrat de sortie des trois canaux : nouvel ADR qui complète ADR-0039 et ADR-0040. Le déplacement de `PublicContextPolicy` et la montée de politique de redaction n'en demandent pas d'autre.

## Risks / Trade-offs

- [Clients qui dépendaient d'une divergence de champs] → changement de contrat signalé dans les notes de version ; le test de parité rend tout écart futur explicite.
- [Faux positifs des nouveaux motifs (`token` dans du code ordinaire)] → la sonde de 35 cas inclut des cas négatifs ; un faux positif ne fait que masquer une valeur.
- [Reconstruction complète des index à la mise à jour] → coût unique, documenté dans `docs/developer/release-and-recovery.md`, déjà subi par V006, V009 et V010.
- [Conflit de numéro de migration avec le sprint 5] → les deux sprints prennent le numéro suivant au moment de l'implémentation ; le checksum (`SchemaMigratorChecksumTest`) détecte toute collision.
- [AUD-TST-07 non livré au démarrage] → la tâche 2 attend sa fusion ; les tâches 1 et 5 à 8 n'en dépendent pas.
