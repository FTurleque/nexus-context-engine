# Proposition

## Why

L'audit du 16/11/2026 regroupe dans son sprint S4, « Robustesse des chemins de requête et d'indexation », quatre constats corrigeables localement, sans toucher aux frontières du cœur : `AUD-PRF-01` (Moyenne), `AUD-PRF-02` (Moyenne), `AUD-PRF-05` (Faible) et `AUD-SEC-07` (Faible). Ensemble, ils font qu'une requête en langage naturel coûte un parcours complet des symboles du projet, qu'une panne passagère d'Ollama annule une indexation sémantique de plusieurs heures, et que le repli de lecture de Windows laisse passer une jonction substituée par course.

Le critère de sortie du sprint (`docs/project/audits/2026-11-16/sprints.md`, section S4) exige : un cas de requête de moins de 3 caractères avec un budget p95 dans `scripts/verify-scale-budgets.py` ; un test où un faux fournisseur renvoie 503 puis 200 et l'indexation aboutit en `READY`, et un test où une erreur de contrat la laisse `FAILED` ; le délai Ollama par défaut réaligné sur la mesure documentée ; un test qui injecte une jonction entre le contrôle et l'ouverture dans le repli de `SafeFileIO` et vérifie le refus ; un extrait sémantique qui ne normalise plus qu'un préfixe borné. Ordre interne : `AUD-PRF-05` avant `AUD-PRF-02`. Prérequis : `AUD-PRF-01` dépend de `AUD-SEC-01` (sprint S3).

Sources : `docs/project/audits/2026-11-16/findings.md` et `findings.json` (champ `constats`), `docs/project/audits/2026-11-16/plan-action.md`, `docs/project/audits/2026-11-16/sprints.md`. Commit audité : `7ec5c5dc` ; depuis, seul `scripts/release/test-final-audit-contracts.sh` a changé hors documentation (`git diff --stat 7ec5c5dc HEAD -- core adapters scripts .github`). Les preuves ci-dessous sont relues au HEAD de la branche `docs/audit-cloture-qua-34` (`6e0a5aaf`).

## What Changes

### AUD-PRF-01 — termes de recherche courts

- [Existant vérifié] Un terme de moins de `TRIGRAM_MIN_CODE_POINTS = 3` points de code (`core/src/main/java/com/nexus/persistence/sqlite/SqliteIndexSql.java:24`) est routé vers `LIKE_ARM_SQL` (`core/src/main/java/com/nexus/persistence/sqlite/SqliteSymbolSearch.java:99`, `:104-105`). Cette requête combine `LOWER(s.name) LIKE '%x%'`, `LOWER(s.qualified_name) LIKE '%x%'` et un préfiltre sur le premier caractère et la longueur, puis trie par `CASE` avant `LIMIT` (`SqliteSymbolSearch.java:226-247`).
- [Existant vérifié] `SymbolSearchStrategy.search` envoie la requête entière et chacun de ses termes, jusqu'à `MAX_QUERY_TERMS = 8` (`core/src/main/java/com/nexus/search/SymbolSearchStrategy.java:31`, `:42`, `:48-50`) ; le découpage n'impose aucune longueur minimale de terme (`core/src/main/java/com/nexus/search/SearchText.java:67-71`).
- [Existant vérifié] Mesure de l'audit, avec le SQLite 3.53.1 de Python et non sqlite-jdbc, sur le schéma des 22 migrations (1 M de symboles, 20 000 fichiers) : plan `SEARCH s USING INDEX idx_symbols_file_fuzzy_prefilter (file_id=?)` puis `USE TEMP B-TREE FOR ORDER BY` ; environ 0,7 s par terme court, 2,3 s pour trois termes, contre 116 ms pour un terme trigramme. Le plan vaut aussi pour sqlite-jdbc ; les chiffres absolus restent à remesurer avec sqlite-jdbc.
- [Existant vérifié] Le banc d'échelle ne mesure que des requêtes de 11 caractères et plus (`core/src/test/java/com/nexus/benchmark/ScaleRegressionBenchmarkTest.java:252-255`) ; `scripts/verify-scale-budgets.py:45-48` et `:96-99` n'ont de budget que pour `exact`, `contains`, `miss` et `relation`.
- [Exigence] Les termes de moins de trois points de code issus du découpage ne déclenchent pas de recherche par sous-chaîne ; la requête entière est toujours interrogée.
- [Exigence] Une requête entière de moins de trois points de code ne cherche que l'égalité et le préfixe du nom en minuscules, par un index.
- [Exigence] Le banc d'échelle mesure une requête de moins de trois points de code et `scripts/verify-scale-budgets.py` compare son p95 à un budget par palier.
- [Proposition] L'index sur le nom en minuscules est une migration d'évolution de schéma (skill `.claude/skills/nexus-schema-migration/`). La dernière migration au HEAD est `V022__invalidate_end_begin_same_line_indexes.sql` (`core/src/main/java/com/nexus/persistence/sqlite/SchemaMigrator.java:42`). Le change `audit-1116-s3-redaction-v12-affectations` (sprint S3, `AUD-SEC-01`) prend la migration suivante ; celle de ce change prend le numéro d'après. Le numéro n'est pas figé ici (voir Questions ouvertes).
- [Écart] Les résultats d'une requête courte changent : une sous-chaîne au milieu du nom (`getId` pour `id`) ou du nom qualifié n'est plus trouvée par la recherche de symboles. C'est le comportement demandé par l'audit, pas une régression masquée.

### AUD-PRF-05 — extrait sémantique

- [Existant vérifié] `SemanticIndexingService.excerpt` applique `WHITESPACE.matcher(redactedContent).replaceAll(" ").trim()` au contenu entier, puis garde `DEFAULT_EXCERPT_CHARS = 320` caractères (`core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java:25`, `:28`, `:278-286`). Mesure de l'audit : 74 à 89 ms et une copie complète par fichier de 8 Mo.
- [Exigence] L'extrait est identique à celui de la normalisation complète, sans allouer de chaîne de la taille du document ni normaliser au-delà du préfixe nécessaire.

### AUD-PRF-02 — panne transitoire d'Ollama pendant l'indexation

- [Existant vérifié] HTTP 429 et 5xx (`core/src/main/java/com/nexus/search/semantic/ollama/OllamaEmbeddingProvider.java:284-285`), délai dépassé (`:309-311`, `:262`, `:265`) et connexion impossible (`:267-268`) lèvent `EmbeddingProviderUnavailableException` ; une autre réponse non 2xx ou une réponse invalide lève une `IOException` (`:287`, `:291-301`).
- [Existant vérifié] `SemanticIndexingService` appelle `embeddingProvider.embedAll(...)` une seule fois par lot, sans reprise (`SemanticIndexingService.java:209-211`). L'audit citait `:208-210` : décalage d'une ligne, contenu identique.
- [Existant vérifié] Un échec fait passer le projet `FAILED` (`core/src/main/java/com/nexus/index/service/ProjectIndexingService.java:238-239`) et le passage suivant reconstruit tout, embeddings compris (`:208`) ; politique fail-closed documentée (`docs/developer/semantic-search.md:107`).
- [Existant vérifié] Délai par défaut 60 s (`OllamaEmbeddingProvider.java:33`, `docs/developer/semantic-search.md:30`), alors que la qualification réelle sur CPU le fixe à 120 s (`.github/workflows/semantic-search-qualification.yml:65`, `:117`).
- [Exigence] Une `EmbeddingProviderUnavailableException` levée par l'appel d'un lot pendant l'indexation est reprise un nombre borné de fois, avec une attente croissante et plafonnée ; l'indexation aboutit si une reprise réussit.
- [Exigence] Une erreur de contrat (`IOException` autre que `EmbeddingProviderUnavailableException`) n'est pas reprise et laisse le projet `FAILED`.
- [Exigence] Le délai Ollama par défaut vaut celui que la qualification réelle retient, 120 s.
- [Question] Nombre de tentatives, attente initiale, facteur et plafond de l'attente : ni l'audit (« par exemple 3 tentatives avec attente croissante, plafonnée ») ni `docs/developer/semantic-search.md` ne fixent de valeur.

### AUD-SEC-07 — jonction substituée dans le repli de SafeFileIO

- [Existant vérifié] Sans `SecureDirectoryStream` (cas de Windows), `SafeFileIO.openReadNoFollow` prend un repli non atomique : instantané des composants, ouverture, revalidation (`core/src/main/java/com/nexus/security/SafeFileIO.java:121-148`). Le repli contrôle `Files.isSymbolicLink` (`:187`, `:217`), compare `toRealPath(LinkOption.NOFOLLOW_LINKS)` (`:205`, `:230`) et ignore l'identité quand `fileKey()` est nul (`:235-236`). Une jonction n'est pas un lien symbolique pour `Files.isSymbolicLink`, et `toRealPath(NOFOLLOW_LINKS)` ne la résout pas.
- [Existant vérifié] Une seam de package réservée aux tests permet de forcer le repli et de remplacer l'ouverture (`SafeFileIO.java:113-116`) ; `SafeFileIOTest#visibleReplacementDuringFallbackOpeningClosesChannelBeforeReading` l'utilise pour un lien symbolique. `SafeFileIOTest` s'exécute sur `windows-latest` (`.github/workflows/filesystem-semantics-qualification.yml:55`, `:103`, `:112`).
- [Existant vérifié] Une jonction déjà présente est arrêtée par `toRealPath()` qui suit les liens (`core/src/main/java/com/nexus/security/ProjectPathGuard.java:63`, `:80`) ; le résidu est la fenêtre entre ce contrôle et l'ouverture. `docs/developer/filesystem-support.md:204` reconnaît qu'aucun test ne couvre les jonctions.
- [Exigence] Le repli refuse, avant toute lecture, un composant remplacé par une jonction entre l'instantané et l'ouverture.
- [Décision] Le repli compare l'état de chaque composant avant et après l'ouverture (chemin réel qui suit les liens, attribut de point d'analyse) au lieu de refuser tout répertoire point d'analyse. L'instantané part de la racine du système de fichiers (`SafeFileIO.java:184-186`) : refuser toute jonction ancêtre casserait la lecture d'un projet placé sous un dossier redirigé. Cela s'écarte de la lettre de l'action de l'audit (« refuser les attributs `isOther()` d'un répertoire »), pas de son objectif.

## Type de changement

fonctionnel (chemin de requête de symboles, indexation sémantique, lecture de fichiers, schéma SQLite) : profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- Recherche de symboles SQLite par CLI, REST et MCP (`SymbolSearchStrategy`, `SqliteSymbolSearch`), son banc d'échelle et `scripts/verify-scale-budgets.py`.
- Préparation des documents et appel du fournisseur d'embeddings pendant l'indexation (`SemanticIndexingService`) ; délai par défaut du fournisseur Ollama.
- Repli non atomique de `SafeFileIO` et son test Windows.
- Migration d'index, documentation durable, matrice de traçabilité et contrats associés.

**Hors périmètre :**
- `find_usages` avec un symbole de 1 ou 2 caractères : même forme sur `symbol_relations` (`core/src/main/java/com/nexus/persistence/sqlite/SqliteRelationQueries.java:61-62`, `:88-99`), non mesurée par l'audit et absente du critère de sortie. Elle reste un résidu documenté.
- Réutiliser les vecteurs des fichiers inchangés après un échec d'indexation (« à terme » dans l'action de `AUD-PRF-02`).
- Reprise ou disjoncteur sur le chemin de requête sémantique : il dégrade déjà sans candidat sémantique (`docs/developer/semantic-search.md:100-105`).
- La traversée atomique (`SecureDirectoryStream`), la limite ABA du repli et les liens physiques.
- `AUD-SEC-01` et sa migration (sprint S3, change `audit-1116-s3-redaction-v12-affectations`).

## Capabilities

### New Capabilities
- `semantic-embedding-indexing` : préparation des documents et appel du fournisseur d'embeddings pendant l'indexation sémantique (extrait borné, reprise bornée des indisponibilités transitoires, erreur de contrat bloquante, délai par défaut du fournisseur Ollama).
- `safe-file-reads` : lecture de fichiers de projet sans suivre de lien au moment de l'ouverture, y compris dans le repli non atomique.

### Modified Capabilities
- `symbol-search-scoping` : trois exigences ajoutées (termes courts sans recherche par sous-chaîne, requête entière courte limitée à l'égalité et au préfixe du nom, mesure de la requête courte sous budget p95).

## Impact

- Code : `core/src/main/java/com/nexus/search/SymbolSearchStrategy.java`, `core/src/main/java/com/nexus/persistence/sqlite/SqliteSymbolSearch.java` ; `core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java` ; `core/src/main/java/com/nexus/search/semantic/ollama/OllamaEmbeddingProvider.java:33` ; `core/src/main/java/com/nexus/security/SafeFileIO.java`.
- Schéma : une migration `core/src/main/resources/db/migration/V0NN__index_symbols_by_lower_name.sql` (nom indicatif), enregistrée dans `SchemaMigrator.java` après celle de `AUD-SEC-01`.
- Tests : `SymbolSearchStrategyTest`, tests de plan de requête SQLite, `ScaleRegressionBenchmarkTest`, `SemanticIndexingServiceTest`, tests d'indexation de `ProjectIndexingService`, `OllamaEmbeddingProviderTest`, `SafeFileIOTest`, test d'upgrade de la migration et tests qui comptent les migrations.
- Scripts et workflows : `scripts/verify-scale-budgets.py` ; aucun workflow à modifier (`SafeFileIOTest` est déjà dans la qualification Windows).
- Documentation : `docs/developer/large-scale-search.md` (section `OpenSpec symbol-search-scoping`), `docs/developer/scale-regression-benchmark.md`, `docs/developer/semantic-search.md` (variable `NEXUS_OLLAMA_TIMEOUT_SECONDS`, nouvelle section `OpenSpec semantic-embedding-indexing`), `docs/developer/filesystem-support.md` (nouvelle section `OpenSpec safe-file-reads`), `docs/developer/release-and-recovery.md` (migration), `docs/developer/openspec-traceability.md`, contrats `scripts/release/test-operational-doc-contracts.sh`.
- ADR applicables, sans nouvel ADR prévu : `DATA-0020` (migrations embarquées append-only), `BE-0090` (recherche sémantique optionnelle et qualifiée hors release), `DATA-0074` (cycle de vie et cohérence des index dérivés), `SEC-0082` (support filesystem et traversée des chemins bornés), `BE-0081` (échéance globale de la recherche fédérée).
- Effet utilisateur : une requête courte ne trouve plus de sous-chaîne au milieu d'un nom ; une indexation sémantique survit à une indisponibilité passagère d'Ollama ; une requête sémantique hors fédération peut attendre jusqu'à 120 s au lieu de 60 s avant de dégrader.

## Questions ouvertes

- [Question] Numéro de la migration d'index : celui qui suit la migration de `AUD-SEC-01` sur `main` au moment de l'implémentation (`V024` si S3 prend `V023`). À relire avec `ls core/src/main/resources/db/migration | sort | tail -1` avant d'écrire le fichier.
- [Question] Forme de l'index et de la requête courte (index global sur `LOWER(name)`, index `(file_id, LOWER(name))`, ou autre), à trancher sur le plan `EXPLAIN QUERY PLAN` et le banc : voir `design.md`, « Persistance et intégrité ».
- [Question] Budget p95 de la requête courte par palier dans `scripts/verify-scale-budgets.py` : aucune source ne le fixe ; à poser sur la première mesure sqlite-jdbc du banc.
- [Question] Nombre de tentatives, attente initiale et plafond de la reprise Ollama (voir `AUD-PRF-02`).
- [Question] Le délai par défaut de 120 s doit-il aussi s'appliquer au vecteur de requête hors fédération (`core/src/main/java/com/nexus/search/semantic/SemanticSearchStrategy.java:205-206`), ou seulement à l'indexation ?
