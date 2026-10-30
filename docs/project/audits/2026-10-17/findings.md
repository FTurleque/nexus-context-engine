# Constats — audit du 17 octobre 2026

Commit audité : `origin/develop` @ `98b1f3f0`. Constats triés par sévérité puis par axe ; chacun porte son sprint et ses dépendances. Source structurée : [`findings.json`](findings.json). Ordre d'exécution : [`sprints.md`](sprints.md).

| Sévérité | Nombre |
|---|---:|
| Moyenne | 22 |
| Faible | 61 |
| Info | 38 |
| **Total** | **121** |

## Moyenne (22)

### AUD-ARC-01 — Composante fortement connexe de 12 packages du cœur (context, context.source, .git, .skill, index, index.scan, ranking, ranking.graph, search, search.semantic, search.semantic.ollama, security)

- **Axe** : Architecture · **Effort** : L · **Sprint** : S7 · Casser le bloc cyclique du cœur
- **Dépend de** : `AUD-ARC-02`, `AUD-ARC-03`, `AUD-ARC-04`, `AUD-ARC-05`, `AUD-ARC-06`, `AUD-ARC-07` · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 4 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
Commande : graphe des `import com.nexus.*` des 243 fichiers main + Tarjan (scratchpad graph.py/scc.py) → « SCC 12 [...] ». Arêtes retour : core/src/main/java/com/nexus/index/IndexRepository.java:3 `import com.nexus.search.ResultLimitPolicy;` ; core/src/main/java/com/nexus/search/SymbolSearchStrategy.java:7 `import com.nexus.index.IndexRepository;` ; core/src/main/java/com/nexus/ranking/ContextRanker.java:3 `import com.nexus.search.SearchCandidate;` ; core/src/main/java/com/nexus/search/SearchService.java:4 `import com.nexus.ranking.ContextRanker;`
```

**Impact.** Les capacités index/recherche/ranking/contexte/sécurité ne sont pas séparables : toute modification d'un type de modèle recompile et risque d'impacter les 12 packages ; l'extraction prévue par ADR-0004 (nexus-core, adaptateurs lourds) est impossible sans casser d'abord ces cycles. ADR-0004 l.36 rend pourtant « obligatoire » la séparation logique par packages.

**Action.** Casser les 5 cycles bilatéraux (AUD-ARC-02 à 06) en isolant les types de modèle partagés (CodeSymbol, FileCategory, SearchCandidate, ContextFragment, ResultLimitPolicy) dans des packages feuilles et en remontant l'orchestration (ProjectIndexingService, SearchService) d'un cran ; viser un DAG puis le verrouiller (AUD-ARC-07).

### AUD-ARC-02 — Cycle index ↔ search : le package index mêle modèle de domaine, port de persistance et orchestration qui dépend des ports de recherche

- **Axe** : Architecture · **Effort** : M · **Sprint** : S7 · Casser le bloc cyclique du cœur
- **Dépend de** : `AUD-ARC-07` · **Débloque** : `AUD-ARC-01`
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 3 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/index/ProjectIndexingService.java:8-9 `import com.nexus.search.SearchIndex;` / `import com.nexus.search.semantic.SemanticIndexingService;` ; core/src/main/java/com/nexus/index/IndexDocumentPublisher.java:3-5 ; core/src/main/java/com/nexus/search/SearchDocument.java:5 `import com.nexus.index.CodeSymbol;` (search→index : 10 imports, index→search : 4, index→search.semantic : 2)
```

**Impact.** Le port IndexRepository (index) dépend d'une politique de search, et le service d'indexation dépend de la classe concrète SemanticIndexingService (null = désactivé) : couplage indexation ↔ recherche lexicale/sémantique, régressions croisées.

**Action.** Déplacer ProjectIndexingService/IndexDocumentPublisher/ExternalCodeIntelligenceRefresher dans un package d'orchestration (ex. index.service) ou déplacer ResultLimitPolicy/SearchDocument dans un package neutre ; introduire un port d'indexation sémantique (interface) à la place de la classe concrète.

### AUD-ARC-03 — Cycle context ↔ security : la politique de sortie publique est rangée dans security et dépend des bundles de contexte

- **Axe** : Architecture · **Effort** : S · **Sprint** : S4 · Une seule sortie publique, redaction complétée
- **Dépend de** : `AUD-QUA-09` · **Débloque** : `AUD-ARC-01`
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/security/PublicContextPolicy.java:5-8 `import com.nexus.context.ContextBundle;` … `FederatedContextItem;` ; core/src/main/java/com/nexus/context/ContextFragmentFactory.java:6-8 `import com.nexus.security.ProjectPathGuard;` / `SafeFileIO;` / `SensitiveContentRedactor;`
```

**Impact.** Le package transverse security (I/O sûres, redaction) n'est plus une brique feuille : toute évolution du modèle de contexte touche security, et inversement ; empêche de réutiliser security dans un module séparé.

**Action.** Déplacer PublicContextPolicy (projection de sortie) vers context (ou un package exposure/application) ; garder security sans dépendance vers les packages métier (il ne dépend aujourd'hui que de config/paths/project en plus de context).

### AUD-ARC-07 — Aucune protection structurelle des frontières : pas de test d'architecture, pas de module-info, 160/219 types publics

- **Axe** : Architecture · **Effort** : S · **Sprint** : S3 · Filets de test et traces
- **Dépend de** : aucun · **Débloque** : `AUD-ARC-01`, `AUD-ARC-02`, `AUD-ARC-04`, `AUD-ARC-05`, `AUD-ARC-06`
- **Statut** : Fermé · **Changement** : Corrigé par la PR #282 (b6b56a07) · **Vérification du 2026-10-30** (`b6b56a07`) : CoreArchitectureTest échoue sur un import interdit du cœur, baseline package-cycles.txt, ADR complétant ADR-0004 (PR #282)

**Preuve**

```text
Commande : `grep -rli 'archunit|jdeps|ArchitectureTest' core/src/test adapters/*/src/test pom.xml core/pom.xml scripts .github` → aucun résultat ; `find . -name module-info.java` → aucun ; 160/219 fichiers main du core déclarent un type public. docs/adr/0004-demarrer-avec-un-seul-module-maven.md:70 « Maven ne protège pas encore physiquement toutes les frontières ».
```

**Impact.** Les règles « ports/adaptateurs », « core sans framework », « Lucene/SQLite confinés » ne reposent que sur la discipline ; la SCC d'AUD-ARC-01 montre l'érosion déjà acquise ; un adaptateur peut instancier n'importe quel service interne (ex. tests REST/MCP qui construisent SqliteDatabase).

**Action.** Ajouter un test JUnit sans dépendance (parse des imports, comme graph.py) ou ArchUnit qui fige : core sans io.quarkus/jakarta/spring, org.apache.lucene ⊂ search.lucene|search.semantic.lucene, org.sqlite/java.sql ⊂ persistence.sqlite (+ application), project feuille, et un baseline de cycles qui ne peut que décroître.

### AUD-QUA-01 — Les sorties MCP et REST exposent scoreComponents dans un ordre qui change à chaque démarrage de JVM

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S4 · Une seule sortie publique, redaction complétée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Prévention pour le code futur (règle de revue), code existant inchangé · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 5 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration) Réserve : .claude/rules/code-review.md:12 (PR #254) interdit l'ordre d'itération d'un HashMap/HashSet en sortie pour le code revu ; les mappers MCP/REST existants transmettent toujours Map.copyOf sans tri.

**Preuve**

```text
core/src/main/java/com/nexus/ranking/RankedCandidate.java:30 `components = Map.copyOf(components);` ; core/src/main/java/com/nexus/context/ContextItem.java:36 `scoreComponents = Map.copyOf(scoreComponents);` ; adapters/mcp-java/src/main/java/com/nexus/mcp/McpResultMapper.java:68 `result.put("scoreComponents", ranked.components());` et :152 ; adapters/rest-quarkus/src/main/java/com/nexus/api/ApiMapper.java:141 et :171 (map passée telle quelle) ; à l'inverse core/src/main/java/com/nexus/cli/CliResultMapper.java:44 `new TreeMap<>(ranked.components())`. Aucune option Jackson de tri (grep ORDER_MAP_ENTRIES_BY_KEYS : 0 ; application.properties ne règle que fail-on-unknown-properties). Reproduction : 6 exécutions de `java MapOrder.java` (Map.copyOf de 6 clés) → 4 ordres distincts.
```

**Impact.** Viole la règle de déterminisme (.claude/rules/java-core.md : « ne jamais exposer l'ordre d'itération d'un HashMap/HashSet ») : deux appels MCP/REST identiques sur deux processus produisent un JSON différent (diff, cache, golden tests côté client). La CLI est déterministe : parité rompue.

**Action.** Projeter en TreeMap (ou LinkedHashMap trié) dans McpResultMapper et ApiMapper, ou trier dès le record ; ajouter un test qui sérialise deux fois avec des JVM distinctes ou compare à l'ordre trié.

### AUD-QUA-05 — 12 scripts PowerShell redéfinissent chacun une fonction Invoke-Maven et 16 appellent `mvn` au lieu du wrapper

- **Axe** : Qualité · **Effort** : M 1-3j · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : `AUD-QUA-04` · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
`grep -ho '^function [A-Za-z-]*' scripts/*.ps1 | sort | uniq -c` → `12 function Invoke-Maven`, `5 function Resolve-AbsolutePath`, `3 function Invoke-NexusJson`. `grep -lE '& mvn |"mvn"' scripts/*.ps1` → 16 fichiers (ex. scripts/validate-iteration-17.ps1:13 `& mvn @Arguments`) ; seuls 4 scripts utilisent mvnw. | validate-phase-6.ps1:72 est traité à part par AUD-QUA-06.
```

**Impact.** Duplication sans module partagé (aucun .psm1) ; la convention « toujours le wrapper Maven » (.claude/CLAUDE.md) n'est pas tenue par les scripts de mesure/comparaison ; toute correction (codes retour, arguments) doit être répétée 12 fois.

**Action.** Extraire un module scripts/lib/NexusScript.psm1 (Invoke-Maven via wrapper, Resolve-AbsolutePath, Invoke-NexusJson) et l'importer ; archiver les scripts d'itération obsolètes (AUD-QUA-04) avant de migrer les autres.

### AUD-QUA-06 — Le gate CI Windows retombe silencieusement sur le Maven installé du runner si le wrapper échoue

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S1 · Remettre les mesures d'aplomb
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #266 (89a37f0b) · **Vérification du 2026-10-30** (`4024c874`) : gate Windows sur JDK 21 épinglé, wrapper Maven obligatoire en CI (PR #266)

**Preuve**

```text
scripts/validate-phase-6.ps1:60-73 : `Write-Warning "Le Maven Wrapper est indisponible… Utilisation de Maven installe sur le runner."` puis `foreach ($candidate in @("mvn.cmd", "mvn"))` ; appelé par .github/workflows/ci.yml:45. docs/developer/README.md:21 : « Le build courant utilise Maven 3.10.0 via le wrapper et son ancre d'intégrité versionnée ».
```

**Impact.** Un échec de téléchargement/vérification du wrapper ne bloque pas le gate : la build peut passer avec une version Maven non ancrée, ce qui contredit la doc et rend la qualification non reproductible (croiser avec l'axe Supply-chain/CI).

**Action.** Échouer explicitement en CI (variable CI/GITHUB_ACTIONS) quand le wrapper échoue ; garder le repli seulement en usage local, avec avertissement.

### AUD-QUA-09 — Pas de projection publique unique : CLI, MCP et REST réappliquent chacun la politique de sortie et divergent sur les mêmes objets

- **Axe** : Qualité, Architecture · **Effort** : M 1-3j · **Sprint** : S4 · Une seule sortie publique, redaction complétée
- **Dépend de** : `AUD-TST-07` · **Débloque** : `AUD-ARC-03`
- **Fusionne** : `AUD-ARC-08`
- **Statut** : Ouvert · **Changement** : Prévention pour le code futur (règle de revue), code existant inchangé · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 4 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration) Réserve : .claude/rules/code-review.md:14 (PR #254) rappelle la parité CLI/REST/MCP ; la projection reste écrite trois fois.

**Preuve**

```text
core/src/main/java/com/nexus/cli/CliResultMapper.java:49-62 contextItemMap : `"path", repositoryPath(item.path())` ; adapters/mcp-java/src/main/java/com/nexus/mcp/McpResultMapper.java:143-157 contextItem : `"path", relativePath(project, item.path())` (PublicProjectPathPolicy). CliResultMapper.java:33-46 rankedResult sans `excerpt` ni `id` ; McpResultMapper.java:61-73 avec `excerpt` redacté et `id`. CLI : `ESTIMATED_TOKENS_FIELD` ; MCP : littéral "estimatedTokens". relativePath dupliqué (CliResultMapper.java:96, McpResultMapper.java:76). | Vu aussi par AUD-ARC-08 : core/src/main/java/com/nexus/cli/CliRenderer.java:194 `bundle = com.nexus.security.PublicContextPolicy.expose(project, bundle, query);` ; adapters/mcp-java/src/main/java/com/nexus/mcp/McpResultMapper.java:106 `var bundle = com.nexus.security.PublicContextPolicy.expose(...)` ; adapters/rest-quarkus/s | Taille des mappers : CliResultMapper 103, McpResultMapper 159, ApiMapper 180 lignes.
```

**Impact.** La règle de parité CLI/REST/MCP (.claude/rules/adapters.md) dépend d'une relecture manuelle de 3 mappers (103 + 159 + 180 lignes) ; AUD-QUA-01 en est une conséquence.

**Action.** Introduire dans le cœur une projection publique unique (record ou Map ordonnée) consommée par les trois adaptateurs, et un test de parité qui compare les clés produites.

### AUD-QUA-10 — Méthodes longues concentrées dans le contexte fédéré, l'indexation et la découverte Git/skills

- **Axe** : Qualité · **Effort** : M 1-3j · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : aucun · **Débloque** : `AUD-PRF-03`
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 3 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
Mesure heuristique (script d'accolades, 1 213 méthodes) : 18 méthodes > 60 lignes, 3 > 100 : core/src/main/java/com/nexus/context/FederatedContextService.java:45 build = 168 lignes ; core/src/main/java/com/nexus/index/ProjectIndexingService.java:219 indexLocked = 111 ; core/src/main/java/com/nexus/context/source/git/LocalGitContextSourceProvider.java:55 discover = 106 (imbrication 4) ; NexusApplicationFactory.java:66 create = 99 ; SemanticHybridContextRanker.java:68 rank = 95 (imbrication 4).
```

**Impact.** Méthodes de 100+ lignes au cœur du budget et de l'indexation : relecture et tests de branches plus coûteux, risque de régression sur la sélection déterministe.

**Action.** Découper FederatedContextService.build (validation, collecte par projet, sélection, métadonnées) et indexLocked en étapes nommées ; ne pas toucher NexusApplicationFactory.create (composition linéaire).

### AUD-SEC-01 — La redaction, liste fermée documentée comme non exhaustive, ne couvre pas 19 formats de secrets courants (Bearer, token=, sk-, xoxb-, AIza, glpat-, PGP…)

- **Axe** : Sécurité · **Effort** : M 1-3j · **Sprint** : S4 · Une seule sortie publique, redaction complétée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/security/SecretAssignmentScanner.java:10-12 — liste fermée des composants de clé : api_key|access_token|auth_token|client_secret|secret_access_key|password|passwd|secret (pas de token, auth, pwd, credentials, private_key, access_key, Authorization) ; SensitiveContentRedactor.java:28-29 — STRUCTURED_TOKEN limité à gh*_, github_pat_, AKIA/ASIA ; :24 PRIVATE_KEY_SUFFIX="PRIVATE KEY-----" exclut "PGP PRIVATE KEY BLOCK-----". Sonde (copie des 2 classes, java 21) : LEAK pour `Authorization: Bearer …`, `token=…`, `"token": "…"`, `private_key: …`, `'sk-proj-…'`, `xoxb-…`, `AIza…`, `sk_live_…`, `glpat-…`, `npm_…`, `https://<token>@github.com`, `pwd=`, `credentials:`, `<password>…</password>`, `secretKey = "…"`, `--password …`, `access_key=`, `auth=`, bloc PGP, `password: |` (YAML bloc), `"password":\n "…"` (valeur sur la ligne suivante). | Sur ces 21 fuites, 2 (YAML bloc, valeur sur la ligne suivante) sont exclues explicitement par docs/…/secret-redaction.md:19-23 : 19 formats restent dans le périmètre.
```

**Impact.** Les jetons de ces formats présents dans le code ou la documentation d'un projet indexé sortent en clair dans les contextes CLI/REST/MCP et vers un Ollama distant éventuel. C'est une limite de couverture assumée (secret-redaction.md:7-10, current-limitations.md:114), pas une régression.

**Action.** Étendre la politique (skill nexus-redaction-change) aux 19 formats, versionner la sonde de 35 cas comme test, bumper POLICY_VERSION et invalider les index par une nouvelle migration. Les scalaires YAML de bloc et les valeurs sur la ligne suivante (exclus par secret-redaction.md:19-23) relèvent d'un changement de politique séparé.

### AUD-SEC-02 — Une seule valeur sensible de plus de 4 096 caractères fait échouer l'indexation complète du projet et toute construction de contexte qui l'inclut

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : S4 · Une seule sortie publique, redaction complétée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/security/SecretAssignmentScanner.java:126-129 — `if (length > MAX_SECRET_CHARS) throw new IllegalArgumentException("Valeur sensible dépassant la limite de redaction")` ; appelé sans capture dans LuceneSearchIndex.java:255 (toLuceneDocument) depuis les boucles rebuild/applyChanges :101-116, dans SemanticIndexingService.java:202 et ContextBundle.java:35. Test existant : core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java:70-71 confirme l'exception pour 4 097 caractères. Sonde : `password = "x×5000"` → EXC IllegalArgumentException. | secret-redaction.md:26-27 documente le refus du contenu ; l'échec du projet entier n'est documenté nulle part.
```

**Impact.** Fail-closed (pas de fuite), mais un fichier d'un dépôt tiers (ex. `"client_secret": "<blob>"` dans un Markdown, une fixture ou un JSON embarqué dans du JS) suffit à rendre tout le projet non indexable ; en REST l'erreur remonte en 400 « Requête invalide » sans diagnostic exploitable.

**Action.** Remplacer l'exception par une redaction jusqu'à la fin de ligne (ou du contenu) pour la valeur trop longue, comme pour les clés privées tronquées ; à défaut, exclure uniquement le fichier concerné avec un diagnostic public, sans interrompre le lot.

### AUD-SEC-04 — En mode NEXUS_REST_TRUST_LOCAL=true, l'API REST loopback sans token n'a ni contrôle Host/Origin ni allowlist de racines : exposée au DNS rebinding depuis un navigateur

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : S8 · Façade, configuration et durcissements locaux
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
adapters/rest-quarkus/src/main/java/com/nexus/api/NexusRestExposureGuard.java:79-81 `if (localTrust || ...isLocalTrustExplicitlyEnabled()) { return; }` ; NexusRestAuthenticationFilter.java:30-33 aucun contrôle sans token ; NexusRestProjectRootPolicy.java:85-87 aucune restriction si allowlist vide ; grep `cors|origin|Host` sur adapters/rest-quarkus/src/main → 0 résultat ; NexusResource.java:50-56 POST /api/v1/projects accepte un rootPath arbitraire.
```

**Impact.** En mode trust-local sans allowlist, une page web visitée par l'utilisateur peut, par DNS rebinding, enregistrer n'importe quelle racine locale puis en lire le contenu indexé. Le mode est opt-in, d'où Moyenne et non Élevée.

**Action.** Rejeter les requêtes dont l'en-tête Host n'est pas 127.0.0.1/localhost/[::1]:port (filtre JAX-RS) même en trust-local, et documenter le risque navigateur ; optionnellement exiger une allowlist de racines en trust-local.

### AUD-SEC-06 — Le PFX Authenticode et son mot de passe sont exposés à toutes les étapes du job Windows, y compris sur pull_request avec le code de la PR

- **Axe** : Sécurité, Dépendances · **Effort** : S<1j · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : aucun · **Débloque** : `AUD-DEP-02`
- **Fusionne** : `AUD-DEP-01`
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
.github/workflows/windows-installer.yml:16 `pull_request:` ; :52-56 `env: ... NEXUS_WINDOWS_SIGNING_PFX_BASE64: ${{ secrets.NEXUS_WINDOWS_SIGNING_PFX_BASE64 }}` / `..._PASSWORD` ; :58-62 checkout `ref: ${{ env.NEXUS_HEAD_SHA }}` (head de la PR). | Vu aussi par AUD-DEP-01 : .github/workflows/windows-installer.yml:50-56 : `env:` du job windows-installer → `NEXUS_WINDOWS_SIGNING_PFX_BASE64: ${{ secrets.… }}` / `NEXUS_WINDOWS_SIGNING_PFX_PASSWORD: ${{ secrets.… }}` ; étapes suivantes l.106-131 « Build self-contained Windows distribution », « Build production installer » (
```

**Impact.** GitHub ne transmet pas les secrets aux PR de forks ; le risque se limite aux branches poussées dans le dépôt (collaborateurs, Dependabot n'a pas accès aux secrets Actions). Tout script/test Maven modifié dans une telle PR peut lire et exfiltrer la clé de signature.

**Action.** Déplacer ces deux variables au seul step de signature, conditionné à `github.event_name != 'pull_request'` (ou à un environment protégé avec reviewers).

### AUD-TST-01 — NexusApiApplicationService, bean CDI exercé par les @QuarkusTest, n'est crédité qu'à 13 lignes sur 95 par l'agent JaCoCo seul

- **Axe** : Tests, Dépendances · **Effort** : S · **Sprint** : S1 · Remettre les mesures d'aplomb
- **Dépend de** : aucun · **Débloque** : `AUD-QUA-08`
- **Statut** : Fermé · **Changement** : Corrigé par la PR #268 (dfb695ce) · **Vérification du 2026-10-30** (`4024c874`) : quarkus-jacoco crédite NexusApiApplicationService dans la couverture REST (PR #268)

**Preuve**

```text
/home/claude/audit-work/run/jacoco-rest.csv:38 « NexusApiApplicationService,355,55,9,1,82,13,…,17,6 » (13/95 lignes hors records ; 17 méthodes sur 23 jamais créditées). adapters/rest-quarkus/src/test/java/com/nexus/api/NexusResourceTest.java:23 « @QuarkusTest » appelle index/search/context (l.58-189). Agrégat records inclus : 21/107 l. | adapters/rest-quarkus/pom.xml:26-40 : aucune dépendance io.quarkus:quarkus-jacoco ; :75-95 jacoco-maven-plugin seul. Documentation Quarkus « tests-with-coverage » (Context7) : avec @QuarkusTest, l'agent seul donne une couverture incomplète, l'extension quarkus-jacoco est requise. | Relecture : NexusResource (30/37 l., jacoco-rest.csv:20) et ApiMapper (85/101, :12), appelés seulement par les @QuarkusTest, sont crédités ; seul le bean @ApplicationScoped ne l'est pas, et le log Maven ne contient aucun avertissement « do not match ». La cause (quarkus-jacoco absent) est probable, pas démontrée.
```

**Impact.** Le gate REST (73,4 % mesurés) sous-estime la classe centrale de l'adaptateur : une régression de NexusApiApplicationService ne fait pas bouger la mesure, et la refonte AUD-QUA-08 n'est pas vérifiable par la couverture.

**Action.** Ajouter quarkus-jacoco (scope test) avec la configuration conjointe documentée (exclClassLoaders=*QuarkusClassLoader, append), comparer la mesure ; à défaut, couvrir la classe par des tests unitaires via son constructeur package-private.

### AUD-TST-02 — ProjectPathGuard (primitive de confinement) sans classe de test dédiée : 3 appels directs dans un seul test, 13/24 branches couvertes (54,2 %)

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : S3 · Filets de test et traces
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #282 (b6b56a07) · **Vérification du 2026-10-30** (`b6b56a07`) : ProjectPathGuardTest couvre chaque garde de ProjectPathGuard (PR #282)

**Preuve**

```text
core/src/test/java/com/nexus/security/FilesystemSemanticsQualificationTest.java:40-56 seuls appels directs guard.resolve/requireRegularFile ; aucun appel direct à requireDirectory/containsSymbolicLink/repositoryPath (grep) alors que requireDirectory sert dans ProjectIgnoreMatcher.java:128, AiSkillsRegistryProvider.java:45… ; /home/claude/audit-work/run/jacoco-core.csv : ProjectPathGuard L 41/58, B 13/24.
```

**Impact.** Une régression sur requireDirectory (racine réelle hors projet, répertoire non régulier) ou repositoryPath ne serait détectée qu'indirectement, si un test de provider tombe dessus.

**Action.** Créer ProjectPathGuardTest : chaque méthode publique × (traversal, absolu, symlink final, symlink intermédiaire, racine non répertoire, hors racine).

### AUD-TST-03 — Gates JaCoCo au seul niveau BUNDLE, seuils effectifs différents de la doc : 6 classes de sécurité ou de bornes sous 55 % de branches passent sans alerte

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : S1 · Remettre les mesures d'aplomb
- **Dépend de** : aucun · **Débloque** : aucun
- **Fusionne** : `AUD-TST-04`
- **Statut** : Fermé · **Changement** : Corrigé par la PR #268 (dfb695ce) · **Vérification du 2026-10-30** (`4024c874`) : gates JaCoCo par paquet pour les classes de bornes (PR #268)

**Preuve**

```text
pom.xml:228 « <element>BUNDLE</element> » (idem core/pom.xml, adapters/*/pom.xml) ; mesures : JdtStdioSession B 13/70 (18,6 %), MinosPayloadReader B 7/18 (38,9 %), SemanticSearchConfiguration B 15/50 (30 %), NexusRestTransportPolicy B 28/54 (51,9 %), ProjectPathGuard B 13/24, GeminiInstructionProvider L 7/27 (/home/claude/audit-work/run/jacoco-*.csv). | Vu aussi par AUD-TST-04 : pom.xml:36-37 « nexus.coverage.line.minimum 0.65 / branch 0.55 » + pom.xml:222-239 execution jacoco-check héritée ; core/pom.xml:85,90 « 0.70 / 0.50 » ; adapters/rest-quarkus/pom.xml:90,95 « 0.60 / 0.60 » ; docs/developer/adapter-coverage-gates.md:51-52 « core | 70 % | 50 % », « REST | 60 % | 60 % »
```

**Impact.** Marge core 83,8 %/67,7 % vs 70 %/55 % : on peut perdre ~1 380 lignes couvertes avant que le gate ne réagisse ; le gate ne protège pas les paquets security/index.jdt/index.minos.

**Action.** Ajouter des règles PACKAGE (com.nexus.security, com.nexus.api, index.jdt/minos/scip) avec des planchers proches du mesuré, ou des règles CLASS ciblées.

### AUD-PRF-01 — Aucun index sur symbol_relations(file_id) : chaque fichier réindexé ou supprimé déclenche un SCAN complet de la table (tous projets)

- **Axe** : Performance · **Effort** : S · **Sprint** : S5 · Indexation et écriture SQLite
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteFileWriter.java:100-101 : `DELETE FROM symbol_relations WHERE file_id = ? AND source_provider = ?` exécuté pour chaque update (boucle :28-31) ; SqliteFileWriter.java:47 `DELETE FROM indexed_files ...` → cascade FK V001__initial_schema.sql:76 `FOREIGN KEY (file_id) REFERENCES indexed_files(id) ON DELETE CASCADE`. Les 6 index de symbol_relations (V001:79-83, V002:10-14, V003:7-17) commencent tous par project_id. Commande : migrations V001–V010 appliquées dans SQLite 3.45.1 puis `EXPLAIN QUERY PLAN DELETE FROM symbol_relations WHERE file_id=1 AND source_provider='x'` → `SCAN symbol_relations` (idem pour la sonde de cascade `SELECT 1 FROM symbol_relations WHERE file_id=1`). | Relecture : les triggers V008:110-116 ajoutent une ligne en attente par relation supprimée ; aucune mesure ne quantifie le coût et aucun budget n'est dépassé (d'où Moyenne).
```

**Impact.** Coût O(fichiers modifiés × relations de toute la base) dans la transaction d'indexation : un rebuild complet (fullRebuild ⇒ tous les fichiers passent par replaceAnalysis) ou une suppression massive devient quadratique (déduit du plan de requête, non mesuré) et s'aggrave avec le nombre de projets fédérés partageant la base. Non couvert par les benchmarks : ScaleRegressionBenchmarkTest.java:404-432 peuple par INSERT directs, sans passer par applyChanges sur données existantes.

**Action.** Migration une nouvelle migration `CREATE INDEX idx_symbol_relations_file_provider ON symbol_relations(file_id, source_provider)` (couvre aussi la cascade FK), test EXPLAIN QUERY PLAN dans SqliteGraphQueryPlanTest, et scénario de benchmark « réindexation incrémentale / rebuild sur base peuplée ».

### AUD-PRF-02 — Le vecteur de la requête est recalculé par appel HTTP Ollama pour chaque projet de la fédération (jusqu'à 100 fois la même requête)

- **Axe** : Performance · **Effort** : S · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : `AUD-DEP-19` · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/search/semantic/SemanticSearchStrategy.java:125-127 : `queryVector = ... embeddingProvider.embedQuery(embeddingQuery)` dans search(project,...) ; appelé par projet via FederatedSearchService.java:56-58 `for (ProjectDescriptor project : scope) { ... searchService.search(project, ...)` et FederatedContextService.java:78-90 (contextBuilder.build par projet). Aucun cache : grep `embedQuery` → seul OllamaEmbeddingProvider.java:185 l'implémente, sans mémoïsation.
```

**Impact.** Latence fédérée sémantique proportionnelle au nombre de projets × latence d'embedding ; charge inutile sur Ollama (timeout par requête 120 s).

**Action.** Calculer le vecteur une fois par requête fédérée (mémoïsation par requête normalisée + modelId, portée requête) et le passer aux stratégies par projet.

### AUD-PRF-04 — La recherche sémantique et la sonde de readiness REST ouvrent chacune un DirectoryReader Lucene complet, hors du cache de l'ADR 0045

- **Axe** : Performance · **Effort** : S · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : aucun · **Débloque** : aucun
- **Fusionne** : `AUD-PRF-05`
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/search/semantic/lucene/PersistentLuceneSemanticSearchIndex.java:49-51 `return operationScoped.isCompatible(projectId, provenance);` → LuceneSemanticSearchIndex.java:92-97 `FSDirectory.open(...)` puis `DirectoryReader.open(directory)` pour lire `getIndexCommit().getUserData()` ; appelé à chaque search par SemanticSearchStrategy.java:114. | Vu aussi par AUD-PRF-05 : core/src/main/java/com/nexus/application/NexusApplication.java:336-341 `.allMatch(project -> { ... searchIndex.isPresent(project.id()) ...` ; PersistentLuceneSearchIndex.java:70-72 délègue à l'opération non cachée LuceneSearchIndex.java:150-157 (`DirectoryReader.open` + userData) ; appelée par adapt
```

**Impact.** Ouverture/fermeture de tous les segments (dont fichiers HNSW) à chaque requête et par projet fédéré, alors que le searcher chaud existe déjà : annule une partie du gain du cache persistant.

**Action.** Lire les userData via le searcher mis en cache (IndexCommit du reader acquis) ou `SegmentInfos.readLatestCommit`, et/ou mémoïser le résultat par (projectId, génération SQLite, génération Lucene).

### AUD-PRF-07 — GitRecencyCandidateEnricher relit 50 commits et 50 diffs d'arbres à chaque recherche et pour chaque projet, sans cache

- **Axe** : Performance · **Effort** : S · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/context/source/git/GitRecencyCandidateEnricher.java:58-88 : `openRepository(...)`, `git.log().setMaxCount(MAX_COMMITS).call()` (MAX_COMMITS=50, :35) et `diffFormatter.scan(parent.getTree(), commit.getTree())` par commit ; branché dans SearchService par NexusApplicationFactory.java:122-126.
```

**Impact.** Coût JGit (ouverture repo, parse de 50 commits, 50 diffs filtrés) payé par projet et par requête, y compris en fédération et pour le contexte (DefaultContextBuilder passe par searchService). Signal pourtant stable tant que HEAD ne change pas.

**Action.** Cache borné par (repository, HEAD) des chemins modifiés des 50 derniers commits (bornes MAX_CUMULATIVE_CHANGED_PATHS=4096 déjà existantes), appliqué ensuite au filtre de candidats.

### AUD-DEP-02 — Le workflow Release n'a jamais été exécuté : 0 run et 0 release/tag publiés

- **Axe** : Dépendances · **Effort** : M · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : `AUD-DEP-03`, `AUD-DEP-09`, `AUD-DEP-21`, `AUD-QUA-12`, `AUD-SEC-06` · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé (vérifié en direct) — gh run list --workflow release.yml → 0 run ; gh api …/tags → 0.

**Preuve**

```text
WebFetch https://github.com/FTurleque/nexus-context-engine/actions/workflows/release.yml → « This workflow has no runs yet » ; https://github.com/FTurleque/nexus-context-engine/releases → « There aren't any releases here ». Job publish : .github/workflows/release.yml:150-400 (push GHCR, attest, assets, latest).
```

**Impact.** La chaîne de publication (push GHCR immuable, attestations, upload d'assets, exigence Authenticode, présence des secrets PFX) n'a aucune preuve d'exécution réelle ; la première release servira de test en production. release-assets-qualification.yml (12 runs) ne couvre qu'une partie.

**Action.** Exécuter une répétition complète sur un tag de pré-release (ex. v0.2.0-rc dans un dépôt/namespace GHCR de test) ou ajouter un mode dry-run du job publish ; consigner la preuve.

### AUD-DEP-30 — Alertes Dependabot, mises à jour de sécurité et secret scanning désactivés sur le dépôt public

- **Axe** : Dépendances, Sécurité · **Effort** : S · **Sprint** : S1 · Remettre les mesures d'aplomb
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Partiel · **Changement** : Procédure de traitement des alertes dans SECURITY.md (PR #276) ; activation en attente du mainteneur · **Vérification du 2026-10-30** (`4024c874`) : security_and_analysis : dependabot_security_updates, secret_scanning et secret_scanning_push_protection « disabled » ; vulnerability-alerts répond 404 (tâche 1.1)

**Preuve**

```text
gh api repos/FTurleque/nexus-context-engine/dependabot/alerts → 403 « Dependabot alerts are disabled for this repository » ; gh api repos/…/vulnerability-alerts → 404 « Vulnerability alerts are disabled » ; security_and_analysis : dependabot_security_updates, secret_scanning, secret_scanning_push_protection = disabled (17/10/2026).
```

**Impact.** Une CVE publiée sur une dépendance déjà fusionnée n'est signalée qu'au prochain run OSV d'une PR ; un secret poussé par erreur n'est ni bloqué ni signalé. Les gates OSV requis (AUD-DEP-23) ne couvrent que le code en cours de revue, pas le stock.

**Action.** Activer Dependabot alerts et security updates, secret scanning et push protection (gratuits pour un dépôt public) ; documenter dans SECURITY.md (AUD-DEP-17) le traitement des alertes.

## Faible (61)

### AUD-ARC-04 — Cycles context ↔ context.source / .git / .skill : les ports de sources dépendent du modèle de contexte qui dépend d'eux

- **Axe** : Architecture · **Effort** : S · **Sprint** : S7 · Casser le bloc cyclique du cœur
- **Dépend de** : `AUD-ARC-07` · **Débloque** : `AUD-ARC-01`
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 4 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/context/source/ContextSourceFragmentFactory.java:3 `import com.nexus.context.ContextFragment;` ; core/src/main/java/com/nexus/context/source/git/LocalGitContextSourceProvider.java:5 idem ; core/src/main/java/com/nexus/context/source/skill/SkillContextSelector.java:5-6 `import com.nexus.context.ContextItem;` ; core/src/main/java/com/nexus/context/DefaultContextBuilder.java:4-7 `import com.nexus.context.source.*`
```

**Impact.** Les providers de sources (JGit, YAML, fichiers) ne sont pas isolables du builder ; coût de maintenance modéré, cycle localisé à un type (ContextFragment) et deux types (ContextItem, ContextSelectionResult).

**Action.** Déplacer ContextFragment/ContextItem/ContextSelectionResult dans un package modèle (ex. context.model) dont dépendent à la fois context et context.source.*.

### AUD-ARC-05 — Cycle search ↔ ranking : le ranker dépend des candidats de recherche, le service de recherche dépend du ranker

- **Axe** : Architecture · **Effort** : S · **Sprint** : S7 · Casser le bloc cyclique du cœur
- **Dépend de** : `AUD-ARC-07` · **Débloque** : `AUD-ARC-01`
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/ranking/DeterministicContextRanker.java:3-5 `import com.nexus.search.CandidateType;` `SearchCandidate;` `SearchSignals;` ; core/src/main/java/com/nexus/search/SearchService.java:4-7 `import com.nexus.ranking.ContextRanker;` … `import com.nexus.ranking.graph.GraphCandidateEnricher;`
```

**Impact.** ranking (règle déterministe/explicable, ADR-0010/0025) n'est pas testable/évolutive indépendamment de search ; ranking.graph ↔ search ajoute un second cycle (6/1 imports).

**Action.** Sortir SearchCandidate/SearchSignals/CandidateType dans un package modèle de candidats (ou dans ranking) et faire dépendre search de ranking uniquement.

### AUD-ARC-06 — Le package de ports sémantiques instancie l'implémentation Ollama (cycle search.semantic ↔ search.semantic.ollama)

- **Axe** : Architecture · **Effort** : S · **Sprint** : S7 · Casser le bloc cyclique du cœur
- **Dépend de** : `AUD-ARC-07` · **Débloque** : `AUD-ARC-01`
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/search/semantic/SemanticSearchConfiguration.java:3-4 `import com.nexus.search.semantic.ollama.OllamaEmbeddingProvider;` `OllamaEndpointResolver;` ; :109 `OllamaEmbeddingProvider ollama = new OllamaEmbeddingProvider(` ; core/src/main/java/com/nexus/search/semantic/ollama/OllamaEmbeddingProvider.java:5 `import com.nexus.search.semantic.EmbeddingProvider;`
```

**Impact.** Le port EmbeddingProvider n'est pas réellement découplé de son unique adaptateur : ajouter un autre fournisseur d'embeddings impose de modifier la configuration du port (contraire à l'indépendance fournisseurs LLM d'AGENTS.md).

**Action.** Déplacer la lecture d'environnement et la construction Ollama (`fromEnvironment`) dans le composition root (application) ou dans le package ollama.

### AUD-ARC-09 — Séquence de lecture cohérente (readiness → verrou partagé → session → readiness) dupliquée dans six méthodes de la façade

- **Axe** : Architecture · **Effort** : S · **Sprint** : S8 · Façade, configuration et durcissements locaux
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/application/NexusApplication.java:209-214 (search), :229-235 (searchAcrossProjects), :252-263 (context), :280-291 (contextAcrossProjects), :301-306 (findSymbols), :315-321 (findUsages) — ex. `requireReadyProject(projectId); ProjectIndexLockManager.LockHandle readLock = projectIndexLockManager.acquireRead(projectId); try (var reads = indexRepository.openReadSession()) {`
```

**Impact.** L'invariant 4/11 de docs/architecture.md (READY + verrou partagé + revalidation) est garanti par copier-coller ; une nouvelle opération de lecture peut l'oublier ou l'appliquer partiellement (inspect, l.195-202, ne revalide pas READY).

**Action.** Extraire un template `withConsistentRead(scope, Function<...>)` (mono et fédéré) utilisé par toutes les lectures indexées.

### AUD-ARC-10 — Écriture MINOS orchestrée par la façade et la CLI, hors ProjectIndexingService

- **Axe** : Architecture · **Effort** : S · **Sprint** : S5 · Indexation et écriture SQLite
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/application/NexusApplication.java:185-190 `projectIndexLockManager.acquire(projectId)` … `new MinosCodeIndexImporter().importPayload(...)` … `indexRepository.replaceExternalCodeIntelligence(projectId, snapshot);` ; core/src/main/java/com/nexus/cli/NexusCli.java:150 `payload = MinosCodeIndexImporter.readPayload(input);`
```

**Impact.** Deux chemins d'écriture d'intelligence de code coexistent (ExternalCodeIntelligenceRefresher et la façade) avec des règles de verrouillage différentes (pas de single-flight LockSlot, ProjectIndexingService.java:206-210) ; l'importer n'est pas composé dans NexusApplicationFactory et la CLI contourne la façade pour lire le payload.

**Action.** Déplacer importMinos dans ProjectIndexingService (même verrouillage/diagnostics), composer MinosCodeIndexImporter dans la factory et exposer la lecture bornée du payload via la façade.

### AUD-ARC-11 — SQLException (technologie de persistance) fait partie du contrat public de la façade et des points d'entrée

- **Axe** : Architecture · **Effort** : S · **Sprint** : S8 · Façade, configuration et durcissements locaux
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 4 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/application/NexusApplication.java:95 `public static NexusApplication create(NexusPaths paths) throws SQLException, IOException {` ; adapters/rest-quarkus/src/main/java/com/nexus/api/NexusApiApplicationService.java:192 `} catch (SQLException | IOException exception) {` ; adapters/mcp-java/src/main/java/com/nexus/mcp/NexusMcpServer.java:32 ; core/src/main/java/com/nexus/cli/NexusCli.java:68
```

**Impact.** Les adaptateurs connaissent JDBC alors que SQLite est censé être un détail derrière les ports ; un changement de stockage toucherait les trois adaptateurs.

**Action.** Envelopper les échecs d'ouverture dans PersistenceException (déjà existante, core/src/main/java/com/nexus/persistence/PersistenceException.java:3) ou une exception applicative.

### AUD-ARC-12 — Configuration lue directement dans l'environnement par 12 classes (16 appels à System.getenv) du cœur, hors composition root

- **Axe** : Architecture · **Effort** : M · **Sprint** : S8 · Façade, configuration et durcissements locaux
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 5 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
Commande : `grep -rn 'System.getenv' core/src/main` → 16 occurrences dans 12 fichiers (ex. core/src/main/java/com/nexus/index/ProjectIndexingService.java:404, core/src/main/java/com/nexus/index/jdt/JdtLanguageServerCodeIntelligenceProvider.java:89/197/202/206, core/src/main/java/com/nexus/context/ContextMaterializationLimits.java:90/97, core/src/main/java/com/nexus/security/ProjectFileLimits.java:14) ; 30 noms `NEXUS_*` distincts.
```

**Impact.** Pas de vue unique de la configuration effective ; services de domaine non purs (ProjectIndexingService lit son timeout lui-même) ; tests obligés de passer par des constructeurs package-private.

**Action.** Introduire un objet de configuration résolu une fois par NexusApplicationFactory (le pattern `from(Map<String,String>)` existe déjà dans ProjectScanLimits/ContextDiscoveryLimits) et l'injecter.

### AUD-ARC-13 — ProjectIndexingService expose cinq constructeurs publics télescopiques dont deux retombent silencieusement sur un verrou process-local

- **Axe** : Architecture · **Effort** : S · **Sprint** : S5 · Indexation et écriture SQLite
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/index/ProjectIndexingService.java:45,54,64,76,94 `public ProjectIndexingService(` ; :86-87 `providerTimeoutFromEnvironment(), ProjectIndexLockManager.processLocalOnly());` ; seul usage main : core/src/main/java/com/nexus/application/NexusApplicationFactory.java:107 ; 18 fichiers de test les utilisent.
```

**Impact.** Une composition alternative utilisant un constructeur public perdrait le verrou inter-processus (invariant 11) sans erreur ; API de construction difficile à faire évoluer.

**Action.** Réduire à un constructeur public complet (ou un builder) et déplacer les variantes de commodité dans un support de test.

### AUD-ARC-14 — Responsabilités mélangées dans config.NexusPaths (résolution des chemins + durcissement POSIX/ACL du stockage) et code de test dans le jar (search.evaluation)

- **Axe** : Architecture · **Effort** : S · **Sprint** : S8 · Façade, configuration et durcissements locaux
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 3 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/config/NexusPaths.java:78-134 `ensurePrivateStorage` / `ensurePrivateDirectory` / `ensurePrivateFile` / `hardenPrivateFile` (44 occurrences Posix/Acl) ; core/src/main/java/com/nexus/search/evaluation/SearchQualityMetrics.java référencée uniquement par core/src/test (grep : aucun usage main).
```

**Impact.** Hygiène : un record de chemins porte une politique de sécurité ; métriques d'évaluation livrées en production.

**Action.** Extraire le durcissement dans security (ex. PrivateStoragePolicy) et déplacer SearchQualityMetrics vers src/test ou un module de benchmark.

### AUD-ARC-19 — Le package index.jdt définit une classe nommée ProcessBuilder qui masque java.lang.ProcessBuilder

- **Axe** : Architecture, Qualité · **Effort** : S · **Sprint** : S8 · Façade, configuration et durcissements locaux
- **Dépend de** : aucun · **Débloque** : aucun
- **Fusionne** : `AUD-QUA-14`
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/index/jdt/ProcessBuilder.java:15-18 « Cette classe package-private porte volontairement le nom simple {@code ProcessBuilder} : le provider JDT du même package l'utilise sans nom qualifié » ; :28 `final class ProcessBuilder {` | Vu aussi par AUD-QUA-14 : core/src/main/java/com/nexus/index/jdt/ProcessBuilder.java:28 `final class ProcessBuilder {` ; javadoc :16-19 « porte volontairement le nom simple ProcessBuilder » ; usage core/src/main/java/com/nexus/index/jdt/JdtProcessLauncher.java:33 `ProcessBuilder builder = new ProcessBuilder(command);`.
```

**Impact.** Pattern surprenant : un relecteur peut croire que JdtLanguageServerCodeIntelligenceProvider utilise le ProcessBuilder JDK (frontière d'exécution sensible, invariant 9) ; risque de régression lors d'un refactoring/import automatique.

**Action.** Renommer en BoundedJdtProcessBuilder (ou équivalent) et référencer explicitement.

### AUD-QUA-02 — Le graphe dérivé est documenté comme cache par génération alors que le code ne le matérialise plus ; ProjectGraph est du code mort

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S11 · Finitions documentaires et micro-optimisations
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 3 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
docs/developer/search-ranking.md:79-86 « ProjectGraphBuilder construit le graphe… SQLite canonical generation N → ProjectGraph cache generation N. Tant que la génération ne change pas, le graphe est réutilisé » ; core/src/main/java/com/nexus/ranking/graph/ProjectGraphBuilder.java:14-15 « Le graphe projet complet n'est plus matérialisé ni mis en cache » ; core/src/main/java/com/nexus/ranking/graph/ProjectGraph.java:8 `public record ProjectGraph(...)` : 0 référence dans tout le dépôt Java (main + test).
```

**Impact.** Documentation courante (censée suivre l'exact head) qui décrit un mécanisme supprimé ; classe orpheline de 31 lignes et nom `ProjectGraphBuilder` trompeur (il ne construit plus de graphe).

**Action.** Réécrire la section « Graphe dérivé par génération » (projection bornée findGraphNeighbors, MAX_NEIGHBOR_EDGES), supprimer ProjectGraph, envisager de renommer ProjectGraphBuilder en GraphNeighborhoodProjection.

### AUD-QUA-03 — AGENTS.md prescrit `mvn clean install` alors que les hooks du dépôt refusent `mvn` hors wrapper

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S11 · Finitions documentaires et micro-optimisations
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 5 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
AGENTS.md:11 « exécuter `mvn clean install` puis `scripts/self-smoke.ps1` » ; .claude/hooks/guard-command.ps1:49-51 `Pattern = $start + 'mvn(\.cmd)?\s'` / « Utiliser le wrapper vérifié (./mvnw…) » ; README.md:164-170 et .claude/CLAUDE.md utilisent `mvnw`.
```

**Impact.** Instruction primaire des agents (indexée par NEXUS lui-même) contradictoire avec l'outillage : un agent qui suit AGENTS.md est bloqué par le hook, et le wrapper vérifié (ancre SHA-512) est contourné hors Claude Code.

**Action.** Remplacer par `./mvnw clean install` (ou `.\mvnw.cmd`) dans AGENTS.md ; ajouter le fait au contrat test-operational-doc-contracts.sh.

### AUD-QUA-04 — Quatre scripts validate-iteration-11 à 14 ne sont référencés nulle part et deux pointent vers des JAR 0.1.0-SNAPSHOT inexistants

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : aucun · **Débloque** : `AUD-QUA-05`
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 3 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
Comptage `grep -rl <nom>` hors du fichier lui-même : validate-iteration-11/12/13/14.ps1 refs=0, ci=0. scripts/validate-iteration-12.ps1:10 et scripts/validate-iteration-13.ps1:10-11 `nexus-mcp-java-0.1.0-SNAPSHOT-runner.jar` alors que pom.xml:9 `<version>0.2.0</version>`. Au total 23 scripts racine `scripts/*.ps1|py` dont seulement 3 appelés par la CI (validate-phase-6, measure-scale-regression, verify-scale-budgets).
```

**Impact.** Scripts cassés et non maintenus qui ressemblent à des points d'entrée de validation ; bruit pour les contributeurs et pour l'indexation NEXUS du dépôt.

**Action.** Supprimer ou déplacer dans scripts/archive/ les validate-iteration-9 à 14 et 16/17 non référencés par la doc courante ; documenter que validate-phase-6 + self-smoke sont les seuls gates.

### AUD-QUA-07 — La récence Git avale toute exception sans diagnostic ni log

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S3 · Filets de test et traces
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #282 (b6b56a07) · **Vérification du 2026-10-30** (`b6b56a07`) : GitRecencyCandidateEnricher journalise par System.Logger ce qu'il attrape, sans chemin, en restant fail-open (PR #282)

**Preuve**

```text
core/src/main/java/com/nexus/context/source/git/GitRecencyCandidateEnricher.java:99-102 `} catch (Exception exception) { // … reste fail-open … return candidates; }` ; `grep -c Logger` sur le fichier → 0. Par contraste LocalGitContextSourceProvider.java:156-158 ajoute `diagnostics.add("contexte Git indisponible : " …)`.
```

**Impact.** Fail-open voulu, mais un incident JGit (dépôt corrompu, limite) dégrade le ranking sans aucune trace, contraire à la règle « jamais un abandon silencieux » (.claude/rules/java-core.md).

**Action.** Journaliser via System.Logger (niveau DEBUG/WARNING) et/ou ajouter une raison explicable au résultat lorsque explain est demandé.

### AUD-QUA-08 — Les cinq records d'opération de NexusApplication sont recopiés à l'identique dans le service REST

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S4 · Une seule sortie publique, redaction complétée
- **Dépend de** : `AUD-TST-01` · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
Détection par fenêtres de 8 lignes normalisées : 27 fenêtres communes entre adapters/rest-quarkus/src/main/java/com/nexus/api/NexusApiApplicationService.java:226-272 et core/src/main/java/com/nexus/application/NexusApplication.java:474-520 (IndexOperation, SearchOperation, FederatedSearchOperation, ContextOperation, FederatedContextOperation) ; recopie champ à champ, ex. NexusApiApplicationService.java:85-87 `return new SearchOperation(operation.project(), operation.query(), …)`.
```

**Impact.** ~47 lignes dupliquées plus le code de recopie ; un champ ajouté côté cœur peut être oublié côté REST sans erreur de compilation.

**Action.** Retourner directement les records NexusApplication.* depuis NexusApiApplicationService (seules les métriques y sont ajoutées).

### AUD-QUA-11 — Environ un tiers des messages d'exception sont en anglais malgré la convention française

- **Axe** : Qualité · **Effort** : M 1-3j · **Sprint** : S11 · Finitions documentaires et micro-optimisations
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
Heuristique sur 316 messages littéraux `new (IllegalArgumentException|IllegalStateException|IOException|UncheckedIOException)("…` : 109 contiennent des marqueurs anglais (must/cannot/invalid…). Ex. core/src/main/java/com/nexus/context/FederatedContextService.java:57 `"query must not be blank"` ; core/src/main/java/com/nexus/search/semantic/ollama/OllamaEmbeddingProvider.java:154 `"dimensions must be between 1 and 1024"` ; à côté, ProjectIgnoreMatcher.java:136 `"Le chemin n'appartient pas au projet : "`.
```

**Impact.** Mélange de langues dans les erreurs remontées via CLI/MCP (NexusMcpTools.java:236 renvoie safeMessage(exception)) ; la règle .claude/rules/java-core.md exige le français pour les messages utilisateur. Une partie est des préconditions internes, d'où Faible.

**Action.** Traduire les messages atteignables depuis une entrée utilisateur/configuration (Ollama, requêtes, budgets) ; garder l'anglais pour les invariants internes et le documenter dans la règle.

### AUD-QUA-12 — Version 0.2.0 codée en dur dans la CI et le gate Windows

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : aucun · **Débloque** : `AUD-DEP-02`
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
.github/workflows/ci.yml:91 `ZIP="target/distribution/nexus-context-engine-0.2.0.zip"`, :93, :133, :182 ; scripts/validate-phase-6.ps1:164-166, :188-189, :198, :268 `if ($version.version -ne "0.2.0")`.
```

**Impact.** Chaque bump de version impose ≥ 11 modifications manuelles hors pom.xml ; oubli = gate rouge ou, pire, contrôle d'une mauvaise version.

**Action.** Lire la version depuis pom.xml (`./mvnw help:evaluate` ou `[xml]` comme déjà fait à validate-phase-6.ps1:138) et la propager en variable.

### AUD-QUA-13 — NXA-20260914-05 partiellement ouvert : le watch item FTS/trigram reste présenté comme futur

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S11 · Finitions documentaires et micro-optimisations
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 5 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
docs/developer/current-limitations.md:148 « nouveau moteur FTS/trigram pour les recherches substring ; » alors que core/src/main/resources/db/migration/V008__indexed_substring_search.sql existe et que docs/developer/search-ranking.md:72 décrit `relation_search_fts` « en FTS5 trigram ». Volet Quarkus corrigé : docs/developer/README.md:18 `Quarkus 3.40.1` = pom.xml:52.
```

**Impact.** Priorisation trompeuse d'une fonctionnalité livrée ; le constat d'audit précédent n'est pas clos.

**Action.** Reformuler le watch item (« évolution au-delà de FTS5 trigram V008 ») ou le retirer ; ajouter l'assertion au contrat documentaire.

### AUD-SEC-03 — Les signatures de symboles issues de SCIP/MINOS/JDT sont renvoyées par REST, MCP et CLI sans passer par la redaction

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : S4 · Une seule sortie publique, redaction complétée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 3 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
adapters/mcp-java/src/main/java/com/nexus/mcp/McpResultMapper.java:89 `result.put("signature", symbol.signature());` ; adapters/rest-quarkus/src/main/java/com/nexus/api/ApiMapper.java:150 `symbol.signature(),` ; core/src/main/java/com/nexus/cli/CliResultMapper.java:90. Origine externe : index/scip/ScipDocumentParser.java:107 + :142-149 (signature_documentation.text libre), index/minos/MinosFactMapper.java:59 (champ JSON `signature` libre), index/jdt/JdtSymbolMapper.java:104 (detail LSP).
```

**Impact.** Le texte de signature fourni par un producteur externe (hover/documentation SCIP, export MINOS) peut contenir des littéraux (ex. type littéral d'une constante) et sort sans redaction ni filtrage de chemins par find_symbol, search_code et les réponses de recherche REST. Non démontré avec un indexeur réel (voir angles morts).

**Action.** Appliquer SensitiveContentRedactor.redact (ou PublicDiagnosticPolicy.text) à signature/qualifiedName/source/target dans les trois mappers, ou à la persistance via CodeIntelligenceMetadataPolicy.

### AUD-SEC-05 — La découverte JGit remonte les répertoires parents sans plafond ni contrôle de propriétaire du dépôt trouvé

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : S8 · Façade, configuration et durcissements locaux
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/context/source/git/LocalGitContextSourceProvider.java:163-166 `new FileRepositoryBuilder().findGitDir(projectRoot.toFile()).setMustExist(true).build()` ; idem GitRecencyCandidateEnricher.java:131-133 et PersistentGitContextSourceProvider.java:142-144 ; aucun setCeilingDirectory ni équivalent safe.directory (grep `ceiling|Ceiling` : 0) ; GitWorkingTreeContext.java:95-99 exécute `git.diff()` sur l'arbre de travail.
```

**Impact.** Un projet enregistré sous un répertoire partagé (ex. /tmp/x/proj) utilise un `.git` parent possiblement créé par un autre compte : historique, messages de commit et diff injectés dans le contexte IA, et configuration Git (.git/config, attributs) d'un tiers interprétée par JGit (classe de risque CVE-2022-24765).

**Action.** Fixer setCeilingDirectories sur le parent de la racine projet (ou exiger que le gitdir soit sous la racine ou appartienne à l'utilisateur courant) et ignorer le contexte Git sinon, avec diagnostic.

### AUD-SEC-07 — inputs.profile (type string) est interpolé directement dans des blocs run: de trois workflows

- **Axe** : Sécurité, Dépendances · **Effort** : S<1j · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : aucun · **Débloque** : aucun
- **Fusionne** : `AUD-DEP-13`
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
.github/workflows/scale-benchmark.yml:73 `REQUESTED="${{ inputs.profile }}"` (input `type: string` :6-10) ; idem lucene-lifecycle-benchmark.yml:88 et scanner-corpus-benchmark.yml:63 ; la validation ci|full (scale-benchmark.yml:77) intervient après l'expansion. | Vu aussi par AUD-DEP-13 : .github/workflows/scale-benchmark.yml:73 `REQUESTED="${{ inputs.profile }}"` ; scanner-corpus-benchmark.yml:63 ; lucene-lifecycle-benchmark.yml:88.
```

**Impact.** Injection de commande shell par une valeur de workflow_dispatch contenant `"`/`$(...)`. Limité aux comptes ayant le droit write (déclencheurs dispatch) et à permissions: contents: read (scale-benchmark.yml:44-45).

**Action.** Passer la valeur par `env: REQUESTED: ${{ inputs.profile }}` puis utiliser "$REQUESTED", ou déclarer l'input en `type: choice`.

### AUD-SEC-08 — Absence de .dockerignore : `COPY . .` envoie tout le contexte de build (.git, fichiers locaux .env, données) dans l'étape builder

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : S10 · Conteneur et gouvernance du dépôt
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
packaging/docker/Dockerfile:8 `COPY . .` ; `ls -a` à la racine : aucun .dockerignore.
```

**Impact.** L'image runtime multi-stage ne copie que les jars (Dockerfile:22-27), mais le cache/la couche builder locale contient l'historique Git et tout secret local présent dans l'arbre au moment du build ; contexte plus lourd.

**Action.** Ajouter un .dockerignore (.git, **/target, .env*, nexus-data, workspace, docs/audits…).

### AUD-TST-06 — Assertions de durée murale serrées dans 5 tests de la suite par défaut (500 ms à 2 s)

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : S3 · Filets de test et traces
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #282 (b6b56a07) · **Vérification du 2026-10-30** (`b6b56a07`) : assertions de durée murale remplacées par des assertions d'état ou la borne du worker récalcitrant, marges x5 sans seuil abaissé ; Windows gate vert sur b2f88d8c (PR #282)

**Preuve**

```text
core/src/test/java/com/nexus/index/ExternalTaskRunnerTest.java:34 « assertTrue(elapsedMillis < 750L » ; JdtLanguageServerCodeIntelligenceProviderTest.java:186 « assertTimeoutPreemptively(Duration.ofMillis(500), session::close) » ; ProjectIndexingServiceTimeoutTest.java:74 « elapsedMillis < 1_500L » ; SqliteWriteContentionIntegrationTest.java:244 « elapsedMillis < 1_000L » ; SensitiveContentRedactorTest.java:57 « ofSeconds(2) ».
```

**Impact.** Risque de rouge intermittent sur runner chargé (Windows gate, antivirus) sans régression réelle.

**Action.** Garder la sémantique « ne pas attendre le worker » en comparant au délai du worker récalcitrant (1 500 ms) avec marge, ou isoler ces tests dans une exécution non parallèle avec seuils ×3.

### AUD-TST-07 — Tests E2E monolithiques : NexusResourceTest et NexusMcpServerIntegrationTest = 1 @Test chacun pour 200 et 360 lignes

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : S3 · Filets de test et traces
- **Dépend de** : aucun · **Débloque** : `AUD-QUA-09`
- **Statut** : Fermé · **Changement** : Corrigé par la PR #282 (b6b56a07) · **Vérification du 2026-10-30** (`b6b56a07`) : NexusResourceTest et NexusMcpServerIntegrationTest : un @Test par endpoint et par outil (PR #282)

**Preuve**

```text
adapters/rest-quarkus/src/test/java/com/nexus/api/NexusResourceTest.java:29-30 unique @Test « exposesTheNexusFlowThroughRestWithoutLeakingCoreModels » (≈15 requêtes) ; adapters/mcp-java/src/test/java/com/nexus/mcp/NexusMcpServerIntegrationTest.java:56 unique @Test ; /home/claude/audit-work/run/mvn-verify.log:705 « Tests run: 1 … NexusResourceTest ».
```

**Impact.** La première assertion en échec masque tous les contrats suivants ; diagnostic CI plus lent.

**Action.** Découper par endpoint/outil (projet partagé via @BeforeAll ou fixture), ou assertAll par étape.

### AUD-TST-08 — Scripts de test orphelins : test-rest-token-contract.ps1 et Test-Hooks.ps1 appelés par aucun workflow ni script

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : S1 · Remettre les mesures d'aplomb
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #266 (89a37f0b) · **Vérification du 2026-10-30** (`4024c874`) : CodeQL analyse le code avec build (PR #266)

**Preuve**

```text
grep -rn "test-rest-token-contract\|Test-Hooks" .github scripts → seulement l'auto-usage scripts/release/test-rest-token-contract.ps1:20 et docs/developer/claude-code.md:45 ; test-mcp-protocol.ps1 n'est appelé que par test-docker-runtime.ps1:53 et test-windows-installer.ps1:214.
```

**Impact.** Contrat token REST côté distribution et garde-fous hooks non rejoués en CI ; dérive silencieuse possible.

**Action.** Brancher test-rest-token-contract.ps1 dans le Windows gate ou docker-distribution, et Test-Hooks.ps1 dans un job pwsh léger de ci.yml.

### AUD-TST-09 — 5 tests opt-in ne tournent dans aucun workflow (MINOS réel, baselines large/small-delta, 2 diagnostics sémantiques)

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : S3 · Filets de test et traces
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 4 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/test/java/com/nexus/index/minos/MinosRealIntegrationTest.java:45 assumeTrue(Boolean.getBoolean("nexus.minos.integration.replay")) → seul scripts/validate-minos-integration.ps1 ; LargeScaleSearchBaselineTest.java:49, SmallDeltaIndexingBaselineTest.java:44, RealSemanticRetrievalDiagnosticTest.java:28, SemanticSearchBenchmarkTest.java:27 → uniquement scripts/measure-iteration-1x-*.ps1 (grep .github/workflows : 0).
```

**Impact.** Intégration MINOS réelle jamais requalifiée automatiquement ; ces classes peuvent pourrir (compilent mais leurs scénarios ne sont plus exécutés).

**Action.** Ajouter un workflow_dispatch/schedule pour MinosRealIntegrationTest, ou documenter leur statut d'outillage de mesure et les sortir de src/test.

### AUD-TST-10 — IndexRepository : 98 lignes de méthodes default jamais exécutées (0/98, 0/56 branches) gonflent le dénominateur core

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : S3 · Filets de test et traces
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #282 (b6b56a07) · **Vérification du 2026-10-30** (`b6b56a07`) : méthodes default d'IndexRepository rendues abstraites (PR #282)

**Preuve**

```text
core/src/main/java/com/nexus/index/IndexRepository.java:18,31,52 « default … » ; /home/claude/audit-work/run/summary.md « IndexRepository 98 manquées / 0 couverte ».
```

**Impact.** Code de repli mort ou non testé ; si une implémentation alternative l'utilise un jour, comportement non vérifié.

**Action.** Supprimer les default inutilisés (SqliteIndexRepository les surcharge) ou les tester via un fake minimal.

### AUD-TST-11 — Tests symlink conditionnés par assume (15 sites) : un runner sans symlinks les ignore silencieusement

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : S1 · Remettre les mesures d'aplomb
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par les PR #267 (e3f67017) et #277 (a218a613) · **Vérification du 2026-10-30** (`4024c874`) : tests symlink fail-closed quand CI est défini (PR #267) ; cibles sous le @TempDir, git grep -n "temporaryDirectory.resolveSibling" -- core/src/test vide (PR #277)

**Preuve**

```text
core/src/test/java/com/nexus/security/SafeFileIOTest.java:54-55 « Assumptions.assumeTrue(createSymbolicLink(...)) » ; idem ProjectScannerTest.java:105, ProjectIndexLockManagerTest.java:148,165, ScipProjectPathGuardTest.java:181, MinosProjectPathGuardTest.java:159… ; contre-exemple fail-closed : FilesystemSemanticsQualificationTest.java:70 « fail(...) ». Run Windows utilisateur : aucun skip symlink (/home/claude/audit-work/run/mvn-verify.log:533 SafeFileIOTest 1 skip = fileKey l.229).
```

**Impact.** Sur un poste Windows sans mode développeur, les tests d'évasion symlink de SafeFileIO/scanner/lock passent en « skipped » sans alerte.

**Action.** En CI (env CI=true), transformer l'assume symlink en fail via une propriété, comme FilesystemSemanticsQualificationTest.

### AUD-PRF-03 — La fédération n'a pas de deadline globale : un projet lent retarde toute la requête sans diagnostic du projet non servi

- **Axe** : Performance · **Effort** : M · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : `AUD-QUA-10` · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/search/FederatedSearchService.java:56-66 boucle `for (ProjectDescriptor project : scope)` synchrone ; core/src/main/java/com/nexus/context/FederatedContextService.java:78-90 boucle `for (int index = 0; index < scope.size(); index++)` synchrone ; aucun Executor/deadline (grep Executor|parallel|deadline : 0 occurrence dans ces fichiers). | ADR-0043:86-89 : « aucun parallélisme n'est introduit avant d'avoir mesuré le besoin ».
```

**Impact.** La latence p95 croît linéairement avec le scope (jusqu'à MAX_PROJECTS=100) ; c'est la structure qui portait NXA-20260914-04 (p95 25 projets hors budget). Une seule source lente (Git, Ollama) bloque toute la requête fédérée.

**Action.** Ajouter une échéance globale et un diagnostic par projet non servi ; le parallélisme reste exclu tant qu'une mesure ne le justifie pas (ADR-0043:86-89).

### AUD-PRF-06 — Cache de contexte Git limité à 16 entrées alors que le scope fédéré monte à 100 projets : éviction LRU systématique sur une requête fédérée répétée

- **Axe** : Performance · **Effort** : S · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/context/source/git/PersistentGitContextSourceProvider.java:38 `static final int DEFAULT_CAPACITY = 16;` ; :43 LinkedHashMap accessOrder ; :98-103 éviction de l'entrée la plus ancienne ; clé :120 `(projectRoot, query, targets, explain)` → une entrée par projet. FederatedContextService.java:78 parcourt jusqu'à FederatedScopePolicy.MAX_PROJECTS=100. | ADR-0046:61, :82 et :112 assument la capacité 16 et le churn ; constat maintenu en Faible pour la fédération à 100 projets.
```

**Impact.** Pour un scope > 16 projets, l'accès cyclique évince chaque entrée avant sa réutilisation : taux de hit nul sur la répétition d'un contexte fédéré, alors que le fingerprint (status + 2 diffs) est quand même calculé à chaque appel.

**Action.** Aligner la capacité sur MAX_PROJECTS (ou la rendre configurable avec borne mémoire), et exposer hits/misses (CacheStats existe, :108) dans les métadonnées explain ; qualifier via git-context-cache-qualification avec un scope > 16.

### AUD-PRF-08 — Chaque indexation, même incrémentale sans changement, hache intégralement tous les fichiers deux fois (scan initial + scan final)

- **Axe** : Performance · **Effort** : M · **Sprint** : S5 · Indexation et écriture SQLite
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/index/scan/ProjectScanner.java:158 `FileHasher.sha256(safeFile, maxFileSizeBytes)` pour chaque fichier visité, sans raccourci (taille, mtime) ; ProjectIndexingService.java:247 `scanner.scanWithDiagnostics(...)` puis :297-298 second `scanner.scanWithDiagnostics(...)` + fingerprint ; les fichiers modifiés sont en plus relus et rehachés (IndexDocumentPublisher.java:58-59). | ADR-0023 accepte la lecture intégrale et prévoit « mesurer, puis metadata-first si nécessaire » ; le scan final est un contrôle de cohérence voulu.
```

**Impact.** I/O proportionnelle à la taille du dépôt (budget DEFAULT_MAX_TOTAL_BYTES = 2 GiB, ProjectScanLimits.java:23) ⇒ jusqu'à ~4 GiB lus pour une réindexation sans delta ; pénalise les réindexations fréquentes du runtime REST et les filesystems réseau.

**Action.** Mesurer la part du double hachage sur le corpus du scanner-corpus-benchmark ; n'introduire un raccourci (taille, mtime) qu'au-delà du seuil fixé par ADR-0023.

### AUD-PRF-09 — Recherche de symboles : index FTS trigram et préfiltre fuzzy globaux (non scopés projet), jusqu'à 9 recherches × 3 requêtes SQL par projet

- **Axe** : Performance · **Effort** : M · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — preuve par commande ou mesure ; aucun fichier de code modifié entre 98b1f3f0 et 0b6828ce

**Preuve**

```text
V008__indexed_substring_search.sql:20-21 `idx_symbols_fuzzy_prefilter ON symbols(SUBSTR(LOWER(name),1,1), LENGTH(name))` (sans projet) ; SqliteSymbolSearch.java:38-52 `symbol_search_fts MATCH ? AND f.project_id = ?` (filtre projet après le MATCH global) ; au-delà de FUZZY_SMALL_CANDIDATE_THRESHOLD=10 000 (SqliteIndexSql.java:25), requête `INDEXED BY idx_symbols_qualified_name` : EXPLAIN → `SCAN s USING INDEX idx_symbols_qualified_name` ; SymbolSearchStrategy.java:43-49 appelle searchSymbols pour la requête puis pour chaque terme (MAX_QUERY_TERMS=8). | Le benchmark 2 projets × 100 k symboles reste dans son budget.
```

**Impact.** Le travail par requête dépend du nombre total de symboles de tous les projets (pas du seul projet ciblé) et est multiplié par terme et par projet fédéré ; le chemin « >10 000 candidats » peut parcourir tout l'index qualified_name.

**Action.** Ajouter project_id dénormalisé (ou index composite via indexed_files) au préfiltre fuzzy, mutualiser trigram+count+fuzzy en une requête par terme ou interroger les termes en un lot json_each ; benchmark avec 25/100 projets peuplés dans la même base.

### AUD-PRF-10 — La décision de garder le journal rollback (sans WAL) est mesurée mais consignée hors ADR

- **Axe** : Performance · **Effort** : S · **Sprint** : S11 · Finitions documentaires et micro-optimisations
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 3 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteDatabase.java:100-110 (aucun PRAGMA journal_mode) ; docs/developer/scale-regression-results.md:70-93 compare DELETE et WAL et conclut :89 « WAL n'est pas adopté » ; docs/developer/sqlite-write-contention.md:7 ; transaction unique voulue (V008:13-15, ADR-0052). Aucun ADR ne porte la décision.
```

**Impact.** Une décision mesurée et structurante vit dans un document de résultats ; elle peut être défaite sans que la chaîne d'ADR le signale.

**Action.** Écrire l'ADR qui consigne le choix rollback/WAL, ses mesures et la règle de réévaluation (scale-regression-benchmark.md:142).

### AUD-PRF-11 — Indexation Lucene : un IndexWriter ouvert et un commit (fsync) par lot de 128 fichiers

- **Axe** : Performance · **Effort** : M · **Sprint** : S5 · Indexation et écriture SQLite
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/search/lucene/LuceneSearchIndex.java:96-108 `applyChanges` → `open(projectId)` (new IndexWriter) puis `commit(resources.writer())` ; appelé à chaque flush IndexDocumentPublisher.java:120-123 ; lots de DEFAULT_INDEX_DOCUMENT_BATCH_FILES=128 / 16 MiB (ProjectIndexingService.java:31-32). Idem pour l'index sémantique (LuceneSemanticSearchIndex.java:188-191).
```

**Impact.** ≈ 782 ouvertures de writer + commits durables pour 100 000 fichiers (lexical) et autant côté sémantique ; coût d'écriture et fragmentation en segments.

**Action.** Conserver un writer pour la durée d'une indexation (operation-scoped au niveau de l'indexation, compatible ADR 0045) et ne committer qu'à la barrière, ou espacer les commits.

### AUD-PRF-12 — Les résultats d'analyse (symboles, relations) de tous les fichiers modifiés restent en mémoire jusqu'à l'écriture SQLite finale

- **Axe** : Performance · **Effort** : M · **Sprint** : S5 · Indexation et écriture SQLite
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/index/IndexDocumentPublisher.java:24 `List<IndexedFileUpdate> updates = new ArrayList<>()` alimentée :66-68 pour chaque fichier modifié, retournée :82 ; écrite en une fois ProjectIndexingService.java:279 `indexRepository.applyChanges(projectId, updates, removedPaths)`.
```

**Impact.** Le lot de documents est borné (128 fichiers/16 MiB) mais pas les analyses : en rebuild complet, la rétention suit le nombre de fichiers (jusqu'à 100 000) et de symboles/relations ; pic de heap non mesuré.

**Action.** Écrire les analyses par lots dans la même transaction (ou une table de staging) au rythme des flushs de documents, la génération restant publiée à la fin.

### AUD-PRF-13 — Troncature de fragment quadratique : reconstruction du préfixe complet et réestimation à chaque ligne

- **Axe** : Performance · **Effort** : S · **Sprint** : S11 · Finitions documentaires et micro-optimisations
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/context/BudgetedContextSelector.java:135-147 : `for (String line : lines) { String candidate = builder.isEmpty() ? line : builder + System.lineSeparator() + line; String withMarker = candidate + ...; if (tokenEstimator.estimate(withMarker) > budget) break;`
```

**Impact.** O(lignes × longueur du préfixe) par fragment tronqué ; avec maxPerItemTokens = budget/2 (jusqu'à 100 000 tokens pour MAX_CONTEXT_TOKEN_BUDGET=200 000) et un gros symbole, CPU notable sur le chemin requête.

**Action.** Cumuler incrémentalement le nombre de points de code (l'estimateur est linéaire : HeuristicTokenEstimator 3,5 cp/token) et ne construire la chaîne qu'une fois.

### AUD-PRF-14 — Écriture par fichier : 4 prepareStatement + un SELECT supplémentaire après chaque upsert

- **Axe** : Performance · **Effort** : S · **Sprint** : S5 · Indexation et écriture SQLite
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteFileWriter.java:58-92 (INSERT ... ON CONFLICT puis `SELECT id FROM indexed_files WHERE project_id = ? AND relative_path = ?`) et :100-103 (2 DELETE préparés), appelés dans la boucle :28-31 ; SqliteBatchInsert prépare en plus le tail à chaque fichier (SqliteBatchInsert.java:57).
```

**Impact.** Aller-retours JDBC et compilation SQL répétés par fichier dans la transaction d'indexation.

**Action.** Préparer les instructions une fois par transaction et utiliser `RETURNING id` (SQLite ≥ 3.35) sur l'upsert.

### AUD-PRF-15 — Index redondants qui amplifient les écritures : préfixes stricts d'autres index

- **Axe** : Performance · **Effort** : S · **Sprint** : S5 · Indexation et écriture SQLite
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — preuve par commande ou mesure ; aucun fichier de code modifié entre 98b1f3f0 et 0b6828ce

**Preuve**

```text
V001__initial_schema.sql:79-80 `idx_symbol_relations_project ON symbol_relations(project_id)` (préfixe de 5 autres index) ; V001:38-39 `idx_indexed_files_project` (préfixe de UNIQUE(project_id, relative_path)) ; V005:28 `idx_symbols_file ON symbols(file_id)` (préfixe de idx_symbols_file_provider :31). Commande PRAGMA index_list : 6 index sur symbol_relations.
```

**Impact.** Chaque INSERT/DELETE de relation met à jour 6 index B-tree + projection FTS ; coût d'écriture et de stockage sans gain de plan.

**Action.** Migration supprimant les index préfixes après vérification EXPLAIN QUERY PLAN des requêtes existantes.

### AUD-PRF-16 — Voisinage graphe sortant paginé par OFFSET : chaque page rescanne les pages précédentes

- **Axe** : Performance · **Effort** : S · **Sprint** : S11 · Finitions documentaires et micro-optimisations
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteGraphQueries.java:20 `LIMIT ? OFFSET ?` ; boucle :130-150 relance OUTGOING_GRAPH_SQL avec `offset += relations.size()` tant que les imports ne se résolvent pas en arêtes (imports externes).
```

**Impact.** Coût quadratique en nombre de pages pour des graines aux nombreux imports non résolus ; borné par les imports des graines.

**Action.** Pagination par clé (`(source_ref, target_ref) > (?, ?)`) sur l'ordre déjà imposé.

### AUD-PRF-17 — Regex compilées à chaque appel sur des chemins chauds d'import et de découverte

- **Axe** : Performance · **Effort** : S · **Sprint** : S11 · Finitions documentaires et micro-optimisations
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/paths/RepositoryPath.java:42 `slashPath.matches("^[A-Za-z]:.*")` dans fromProvider (appelé par fait SCIP/MINOS) ; context/source/instruction/RepositoryGlobMatcher.java:14 `Pattern.compile(toRegex(pattern))` par appel et par motif.
```

**Impact.** Allocation/compilation répétée proportionnelle au nombre de faits importés et de requêtes de contexte.

**Action.** Constantes Pattern précompilées ; cache borné des globs compilés.

### AUD-PRF-18 — SemanticIndexingService redige deux fois le même document avant l'envoi d'embeddings

- **Axe** : Performance · **Effort** : S · **Sprint** : S4 · Une seule sortie publique, redaction complétée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java:202 et :210 — même document redigé deux fois. core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java:202 et :210 (`SensitiveContentRedactor.redact(document.content())` deux fois par document, avant troncature) ; context/ContextFragmentFactory.java:124 puis BudgetedContextSelector.java:41-44 redactent à nouveau chaque fragment ; LuceneSearchIndex.java:255 une troisième fois à l'indexation lexicale.
```

**Impact.** Passes regex répétées sur des fichiers jusqu'à 8 MiB ; la défense en profondeur est voulue mais le travail sur le document entier peut être mutualisé.

**Action.** Supprimer l'appel redondant de SemanticIndexingService.java:210 (ou :202) ; la redaction répétée aux frontières CLI/REST/MCP est voulue (secret-redaction.md:29-31) et reste en place.

### AUD-DEP-03 — Le job de publication n'utilise aucun environnement GitHub protégé ; la protection des tags v*.*.* n'est pas attestée

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : aucun · **Débloque** : `AUD-DEP-02`
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé (vérifié en direct) — gh api …/environments → total_count 0.

**Preuve**

```text
.github/workflows/release.yml:150-175 : job publish avec `contents: write`, `packages: write`, `id-token: write`, `attestations: write`, sans clé `environment:` ; ruleset 20503835 (docs/audits/2026-09-14/github-ruleset.json:1) ne cible que `~DEFAULT_BRANCH` et `refs/heads/develop`. | gh api …/environments → total_count 0 ; ruleset 20503835 limité à ~DEFAULT_BRANCH et refs/heads/develop (aucune règle sur refs/tags).
```

**Impact.** Tout acteur capable de pousser un tag déclenche la publication ; la garde repose uniquement sur validate-release-tag.sh (tag = HEAD de main, release.yml:28-46). Pas d'approbation manuelle ni de secrets cantonnés à un environnement.

**Action.** Ajouter `environment: release` (reviewer requis, branches/tags autorisés v*.*.*) au job publish et y cantonner les secrets PFX ; créer un ruleset de tags (création/suppression/mise à jour restreintes).

### AUD-DEP-04 — Dans l'image Docker, les scripts d'entrée et les répertoires applicatifs appartiennent à l'UID d'exécution 10001

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S10 · Conteneur et gouvernance du dépôt
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
packaging/docker/Dockerfile:19-20 `chown -R 10001:10001 /opt/nexus …` ; :26-27 `COPY --chown=10001:10001 … /usr/local/bin/nexus-entrypoint|nexus-healthcheck` ; :52 `USER 10001:10001`. Idem Dockerfile.runtime:7-15 (jars en --chown=10001).
```

**Impact.** Un processus compromis peut réécrire l'entrypoint, la sonde de santé ou les JAR (persistance au redémarrage du conteneur si le système de fichiers n'est pas en lecture seule). Non-root mais pas immuable.

**Action.** Laisser /opt/nexus et /usr/local/bin propriété de root (COPY sans --chown, chmod 0755/0644), ne chown que /data/nexus et /workspace ; documenter `read_only: true` dans le compose.

### AUD-DEP-05 — Aucune propriété project.build.outputTimestamp : les JAR et ZIP produits ne sont pas reproductibles bit à bit

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
grep -rn outputTimestamp --include=pom.xml . → aucun résultat (pom.xml racine, core, adapters/*).
```

**Impact.** Impossible de vérifier indépendamment qu'un artefact publié correspond aux sources ; les SHA-256 publiés varient à chaque build (horodatages d'entrées ZIP).

**Action.** Ajouter `<project.build.outputTimestamp>` au pom racine (mis à jour à chaque release) et vérifier avec `mvnw verify artifact:compare` ou deux builds successifs.

### AUD-DEP-06 — L'enforcer ne contrôle ni versions de plugins ni convergence ; les plugins implicites (jar, resources, install, deploy, clean) ne sont pas épinglés

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
pom.xml:257-265 règles = requireJavaVersion [21,), requireMavenVersion [3.9.0,), banDuplicatePomDependencyVersions ; grep maven-(jar|resources|install|deploy|clean)-plugin dans les pom → aucun.
```

**Impact.** Les versions des plugins du cycle de vie dépendent du super-POM de la version de Maven (atténué par le wrapper épinglé 3.10.0, mais requireMavenVersion accepte toute 3.9+) ; conflits transitifs non signalés.

**Action.** Épingler les plugins du cycle de vie en pluginManagement, ajouter requirePluginVersions et dependencyConvergence (ou requireUpperBoundDeps), resserrer requireMavenVersion à [3.10.0,).

### AUD-DEP-07 — La génération des notices de licences ne bloque aucune licence incompatible

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
pom.xml:282-292 : aggregate-add-third-party avec `failOnMissing=true`, sans includedLicenses/excludedLicenses/failOnBlacklist ; grep forbiddenLicenses|includedLicenses|failOnBlacklist → aucun.
```

**Impact.** Une dépendance GPL/AGPL introduite par Dependabot ou un transitif passerait le build alors que le produit est sous licence propriétaire (LICENSE:1-3).

**Action.** Configurer une liste blanche (Apache-2.0, MIT, BSD, EPL-2.0/EDL, LGPL dual…) avec `failOnBlacklist`/`failIfWarning`, et un `licenseMerges` pour normaliser les libellés.

### AUD-DEP-08 — L'image Docker publiée n'embarque ni LICENSE ni THIRD_PARTY_NOTICES ni labels OCI

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S10 · Conteneur et gouvernance du dépôt
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 3 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
packaging/docker/Dockerfile:22-27 et Dockerfile.runtime:10-15 : seuls JAR, quarkus-app et scripts sont copiés ; grep LABEL dans packaging/docker → aucun. Les notices sont pourtant produites (pom.xml:291) et intégrées au ZIP (distribution/assembly.xml).
```

**Impact.** Distribution GHCR sans notices tierces (obligations NOTICE Apache-2.0) ni métadonnées source/licence/révision exploitables par les scanners.

**Action.** Copier LICENSE et target/licenses/THIRD_PARTY_NOTICES.txt dans /opt/nexus/ ; ajouter LABEL org.opencontainers.image.{source,revision,licenses,version}.

### AUD-DEP-09 — Les assets Windows (ZIP, setup.exe) ne reçoivent aucune attestation de provenance

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : aucun · **Débloque** : `AUD-DEP-02`
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
.github/workflows/release.yml:352-365 : les deux `actions/attest` ciblent uniquement l'image (`subject-name: ${{ steps.publish.outputs.image }}`) ; les assets l.374-381 ne sont accompagnés que de fichiers .sha256 publiés au même endroit.
```

**Impact.** Le consommateur Windows ne peut vérifier la provenance (gh attestation verify) ; un .sha256 co-localisé ne protège pas contre une substitution sur la release.

**Action.** Ajouter `actions/attest` (subject-path des ZIP/EXE) avant l'upload et documenter `gh attestation verify`.

### AUD-DEP-10 — Le commentaire de version d'upload-artifact dans osv-scanner.yml est faux (« v4.6.2 » pour le SHA de v7.0.2)

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
.github/workflows/osv-scanner.yml:72 `actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v4.6.2` ; `git ls-remote --tags https://github.com/actions/upload-artifact` → `cf430e03… refs/tags/v7.0.2` ; v4.6.2 = ea165f8d….
```

**Impact.** Revue des pins trompeuse ; Dependabot (PR #248 « bump upload-artifact 4.6.2 → 7.0.2 ») a modifié le SHA sans le commentaire sur cette ligne.

**Action.** Corriger le commentaire en `# v7.0.2` ; ajouter au contrat local une vérification SHA ↔ commentaire.

### AUD-DEP-11 — Deux versions majeures d'actions/checkout coexistent : 11 usages en v4.4.0 et 8 en v7.0.1

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
grep -ho 'uses: …# …' .github/workflows/*.yml | uniq -c → `11 actions/checkout@11d5960… # v4.4.0`, `8 actions/checkout@3d3c42e… # v7.0.1` (ex. windows-installer.yml:59, scale-benchmark.yml:58) ; ls-remote confirme v4.4.0 et v7.0.1.
```

**Impact.** Comportements divergents entre workflows (credentials persistés, runtime Node), surface de maintenance double ; Dependabot ne converge pas les pins.

**Action.** Aligner toutes les occurrences sur v7.0.1 (même SHA) dans une PR dédiée.

### AUD-DEP-12 — Les workflows réutilisables OSV sont épinglés sur un commit non tagué, sans version en commentaire

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé (vérifié en direct) — Les PR Dependabot #257 et #259 (ouvertes, vertes) remplacent 8ac9e5c par 9a96bc4, qui n'est pas non plus un tag (v2.7.0 = b1d267a) : le constat survivrait à leur fusion.

**Preuve**

```text
.github/workflows/osv-scanner.yml:31 et :82 `google/osv-scanner-action/…@8ac9e5ce… # fail closed on incomplete scans` ; `git describe --tags 8ac9e5c` → `v2.6.0-2-g8ac9e5c` (commit sur main, contenu dans v2.7.0).
```

**Impact.** Pin légitime (vérifié sur main amont) mais non suivable sémantiquement par Dependabot/revue ; dérive silencieuse probable.

**Action.** Épingler sur le SHA de v2.7.0 (6ad84ce1…) avec `# v2.7.0`, après vérification que le correctif « fail closed » y est inclus.

### AUD-DEP-14 — Le benchmark d'échelle n'est pas un check requis et sa version post-merge sur develop ignore une partie des chemins couverts en PR

- **Axe** : Dépendances, Tests · **Effort** : S · **Sprint** : S1 · Remettre les mesures d'aplomb
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Partiel · **Changement** : PR #265 (4aa90dfa) et #278 (4024c874) ; ajout au ruleset en attente du mainteneur · **Vérification du 2026-10-30** (`4024c874`) : « Hermetic scale benchmark » produit sur toute PR et conclu en succès hors chemins surveillés (PR #278) ; absent des required_status_checks du ruleset « Protect main & develop » (tâche 2.4)

**Preuve**

```text
scale-benchmark.yml:15-31 (PR) inclut application/**, index/**, project/**, config/**, scripts/verify-scale-budgets.py, scripts/tests/test_scale_budgets.py, config/scale-benchmark-protocol ; develop-push-scale-benchmark.yml:6-17 ne les contient pas.
```

**Impact.** Un merge touchant ces zones (ou un merge combinant plusieurs PR) n'est pas re-benchmarké sur develop ; un rouge de charge peut n'apparaître qu'à la release.

**Action.** Aligner les filtres de chemins du wrapper develop-push sur ceux du workflow réutilisable (ou les factoriser). Rendre « Scale benchmark » requis sur les chemins qu'il surveille (ou documenter pourquoi il reste consultatif).

### AUD-DEP-15 — Plusieurs jobs n'ont pas de timeout-minutes (défaut GitHub 360 min)

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — preuve par commande ou mesure ; aucun fichier de code modifié entre 98b1f3f0 et 0b6828ce

**Preuve**

```text
Analyse YAML : ci.yml windows-gate/linux-reactor, codeql.yml analyze, release.yml validate-release, osv-scanner scan-pr/scan-sbom (réutilisables) → timeout absent ; les autres jobs en ont (5 à 180).
```

**Impact.** Un blocage (Windows gate, résolution Maven) consomme jusqu'à 6 h de runner et retarde les checks requis.

**Action.** Fixer timeout-minutes sur ci.yml (ex. 45), codeql.yml (30), validate-release (10).

### AUD-DEP-16 — Le ruleset autorise squash et rebase alors que la politique impose le merge commit

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S10 · Conteneur et gouvernance du dépôt
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé (vérifié en direct) — gh api …/rulesets/20503835 : allowed_merge_methods = [merge, squash, rebase] ; allow_squash_merge et allow_rebase_merge = true.

**Preuve**

```text
gh api repos/FTurleque/nexus-context-engine/rulesets/20503835 (17/10/2026) : pull_request.allowed_merge_methods = [merge, squash, rebase] ; réglages du dépôt allow_squash_merge et allow_rebase_merge = true ; .claude/skills/nexus-pr-workflow/SKILL.md:13 « merge commit, jamais squash/rebase ».
```

**Impact.** La règle n'est tenue que par un hook local Claude Code ; un merge via l'interface GitHub peut la contourner (instantané du 14/09, état actuel non vérifié).

**Action.** Restreindre allowed_merge_methods à ["merge"] dans le ruleset 20503835.

### AUD-DEP-17 — Le dépôt public n'a ni SECURITY.md ni CODEOWNERS

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S10 · Conteneur et gouvernance du dépôt
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
ls .github → dependabot.yml, workflows ; `ls SECURITY.md .github/SECURITY.md` → No such file or directory.
```

**Impact.** Pas de canal documenté de divulgation de vulnérabilités ; les fichiers sensibles (.github/workflows, config/tool-integrity.properties) n'ont pas de propriétaire déclaré.

**Action.** Ajouter SECURITY.md (signalement privé GitHub) et un CODEOWNERS minimal sur workflows, scripts/release et ancres d'intégrité.

### AUD-DEP-18 — Durcissement conteneur incomplet : pas d'instruction HEALTHCHECK dans l'image, compose sans cap_drop ni no-new-privileges

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S10 · Conteneur et gouvernance du dépôt
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
packaging/docker/Dockerfile:1-55 sans HEALTHCHECK (sonde seulement dans docker-compose.yml.template:46-53) ; grep read_only|cap_drop|no-new-privileges dans le template → aucun.
```

**Impact.** `docker run` direct sans compose n'a pas de sonde ; surface noyau/capacités par défaut.

**Action.** Ajouter `HEALTHCHECK CMD /usr/local/bin/nexus-healthcheck` ; dans le compose : `read_only: true` + tmpfs, `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`.

### AUD-DEP-19 — La qualification sémantique réelle est instable : 16 des 25 derniers runs annulés, environ 2 h par run, hors gate de release

- **Axe** : Dépendances · **Effort** : M · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : aucun · **Débloque** : `AUD-PRF-02`
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé (vérifié en direct) — Aucun run de Semantic Search Qualification sur develop depuis le 17/10 (gh run list --branch develop -L 15).

**Preuve**

```text
WebFetch …/actions/workflows/semantic-search-qualification.yml → runs #40-#64 : 16 cancelled, 9 success (#61 annulé sur la PR de promotion #250 après 2h02) ; semantic-search-qualification.yml:33 timeout 180 ; release.yml:151-164 `needs` sans job sémantique.
```

**Impact.** La promotion develop→main du 16/10 s'est faite sans qualification sémantique réelle aboutie sur ce SHA ; coût runner élevé. PR #253 (98b1f3f) vient d'ajuster le budget.

**Action.** Réduire le corpus de qualification PR (sous-ensemble représentatif) et garder le corpus complet en planifié ; décider explicitement si la release doit l'exiger.

### AUD-DEP-20 — Deux gates requis mesurent moins que leur nom : « Windows gate » sur un JDK 24 non épinglé, CodeQL Java sans build

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S1 · Remettre les mesures d'aplomb
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #266 (89a37f0b) · **Vérification du 2026-10-30** (`4024c874`) : wrapper Maven obligatoire en CI, sans repli sur le Maven du runner (PR #266)

**Preuve**

```text
.github/workflows/ci.yml:33-37 `java-version: '24'` (choix documenté docs/developer/runtime-flags-qualification.md:21) ; codeql.yml:52-54 `build-mode: none`, `queries: security-extended`.
```

**Impact.** Le gate Windows obligatoire ne compile pas avec la cible livrée (Java 21.0.12) et dérive avec les mises à jour 24.x ; CodeQL sans build a une précision réduite sur Java (flux à travers types résolus).

**Action.** Faire tourner le Windows gate en 21 épinglé (24/25 en matrice secondaire), passer CodeQL en build-mode manual/autobuild via mvnw.

### AUD-DEP-21 — release.yml transmet tous les secrets du dépôt au workflow Windows avec secrets: inherit

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : aucun · **Débloque** : `AUD-DEP-02`
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
.github/workflows/release.yml:59 `secrets: inherit` alors que windows-installer.yml:11-15 ne déclare que les deux secrets PFX.
```

**Impact.** Moindre privilège non appliqué : tout secret ajouté plus tard au dépôt devient accessible au job de build Windows.

**Action.** Passer explicitement `secrets: { NEXUS_WINDOWS_SIGNING_PFX_BASE64: …, NEXUS_WINDOWS_SIGNING_PFX_PASSWORD: … }`.

### AUD-DEP-22 — Les runners sont désignés par des libellés flottants -latest et aucun cache Maven n'est utilisé

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
grep -ho 'runs-on: .*' → 13 ubuntu-latest, 4 windows-latest, 3 matrices sur *-latest ; grep 'cache:' / actions/cache dans .github/workflows → aucun.
```

**Impact.** Changement d'image runner silencieux (cf. variance des benchmarks de l'audit 2026-09-14) ; chaque run retélécharge le reactor depuis Maven Central (temps, dépendance réseau). Pas de risque d'empoisonnement de cache en contrepartie.

**Action.** Épingler ubuntu-24.04 / windows-2025 pour les benchmarks ; `setup-java cache: maven` si le gain justifie la surface de cache.

### AUD-DEP-31 — La montée Quarkus 3.40.1.1 (PR #262) échoue sur un contrat documentaire qui recopie le numéro de version

- **Axe** : Dépendances, Qualité · **Effort** : S · **Sprint** : S10 · Conteneur et gouvernance du dépôt
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Nouveau · **Vérification du 2026-10-29** (`0b6828ce`) : Nouveau constat — Observé dans la CI de la PR #262.

**Preuve**

```text
gh pr checks 262 : « Linux reactor Maven build » FAILURE (run 40405755776, 26/10) ; log : « documentation contract drift: docs/developer/README.md must contain 'Quarkus      3.40.1.1' » (étape scripts/release/test-operational-doc-contracts.sh). Les 9 autres PR Dependabot ouvertes (#255 à #264) sont vertes et en attente depuis 3 à 10 jours.
```

**Impact.** Chaque montée de version d'une dépendance recopiée dans la documentation casse la CI de sa PR Dependabot et demande une retouche manuelle ; les correctifs de sécurité de Quarkus attendent.

**Action.** Faire lire la version au contrat documentaire depuis pom.xml (ou générer la ligne de doc), puis fusionner les PR Dependabot en attente ; à rapprocher de AUD-QUA-12 (version du projet codée en dur).

## Info (38)

Constats sans action : ils documentent ce qui tient. Ne pas les « corriger » ; plusieurs expliquent pourquoi un risque est déjà borné.

### AUD-ARC-15 — Cœur sans framework applicatif et dépendances techniques confinées à leurs packages adaptateurs

- **Axe** : Architecture · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
Commande : `grep -rn 'jakarta|quarkus|springframework' core/src/main` → 0 ; imports tiers par package : org.apache.lucene uniquement dans search.lucene (29) et search.semantic.lucene (19), org.sqlite uniquement dans persistence.sqlite, org.eclipse.jgit dans context.source.git (33) et index.scan (1), com.github.javaparser dans index.java (11) ; java.sql hors persistence.sqlite seulement dans application et cli (signatures, cf. AUD-ARC-11).
```

**Impact.** Borne le risque de contamination framework/infra du domaine ; constat de l'audit du 14/09/2026 (ligne Architecture) toujours d'actualité.

**Action.** Figer ces règles par le test d'AUD-ARC-07.

### AUD-ARC-16 — Adaptateurs REST/MCP minces : ils passent exclusivement par NexusApplication et des types valeur

- **Axe** : Architecture · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
Commande : imports `com.nexus.*` des adapters/*/src/main → `com.nexus.application.NexusApplication` (11) + types valeur (ProjectDescriptor, QueryPolicy, RankedCandidate, ContextItem…) ; aucun import persistence/lucene/service en main (persistence.sqlite n'apparaît que dans 2 tests). adapters/assistant-clients/pom.xml ne dépend pas de nexus-context-engine.
```

**Impact.** Les adaptateurs ne contournent pas l'application (ADR-0039/0040/0041 respectés) ; seule la projection publique est dupliquée (AUD-ARC-08).

**Action.** Aucune.

### AUD-ARC-17 — Composition root explicite et cohérente ; packages project et persistence.sqlite hors cycles

- **Axe** : Architecture · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/application/NexusApplicationFactory.java:73-163 (construction explicite SQLite, Lucene, providers, ranker, sources ; variantes Persistent* en décorateurs de SearchIndex/SemanticSearchIndex/GitContextSourceProvider) ; Tarjan : project n'a aucune arête sortante, persistence.sqlite → {index, project, search, config, persistence} sans retour.
```

**Impact.** Ports IndexRepository/ProjectRepository/SearchIndex effectivement implémentés côté adaptateurs ; SQLite canonique et Lucene dérivé conformes à ADR-0006/0022.

**Action.** Aucune.

### AUD-ARC-18 — Pas de god object : fichier main le plus long 532 lignes (NexusApplication), façade à 31 membres publics

- **Axe** : Architecture · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
Commande : `find core/src/main adapters/*/src/main -name '*.java' | xargs wc -l | sort -rn` → 532 NexusApplication.java, 425 ProjectIndexingService.java, 391 LuceneSemanticSearchIndex.java ; aucune ligne > 200 caractères.
```

**Impact.** La décomposition en classes est fine (243 fichiers, ~760 lignes/package en moyenne) ; le couplage est un problème de dépendances entre packages, pas de taille de classes.

**Action.** Aucune.

### AUD-QUA-15 — Duplication globale faible et sans TODO/FIXME ni printStackTrace

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — preuve par commande ou mesure ; aucun fichier de code modifié entre 98b1f3f0 et 0b6828ce

**Preuve**

```text
Script de fenêtres de 8 lignes normalisées sur 243 fichiers / 26 383 lignes main : 45 fenêtres dupliquées inter-fichiers, dont 27 pour la paire AUD-QUA-08 ; suivantes : LuceneSemanticSearchIndex/PersistentLuceneSemanticSearchIndex (5), AiSkillsRegistryProvider/LocalAgentSkillsProvider (4). `grep -rnE 'TODO|FIXME|XXX|HACK'` → 0 ; `grep printStackTrace` → 0 ; aucun catch vide.
```

**Impact.** Base de code homogène et peu dupliquée.

**Action.** Aucune.

### AUD-QUA-16 — Logs : System.Logger dans le cœur, aucun System.out/err hors CLI

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
`grep -rnE 'System\.(out|err)'` hors /cli/ → 1 seul résultat, adapters/mcp-java/src/main/java/com/nexus/mcp/NexusMcpServer.java:46 (System.out passé au transport STDIO, légitime) ; System.Logger utilisé dans 8 classes du cœur (NexusPaths, SqliteDatabase, SqliteWriteRetryPolicy…) ; aucune dépendance slf4j/JUL.
```

**Impact.** Conforme à la règle java-core.

**Action.** Aucune.

### AUD-QUA-17 — Gestion d'erreurs larges globalement justifiée

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — preuve par commande ou mesure ; aucun fichier de code modifié entre 98b1f3f0 et 0b6828ce

**Preuve**

```text
11 `catch (Exception|Throwable|RuntimeException …)` en main ; frontières d'adaptateur (NexusCli.java:62, NexusMcpTools.java:236 avec safeMessage), agrégation `addSuppressed` (NexusApplication.java:441-446), rethrow enrichi (SkillFrontmatterParser.java:52-53), diagnostic (LocalGitContextSourceProvider.java:156-157). Les 10 `catch (… ignored)` sont commentés (ex. ProjectIndexLockManager.java:212-213 « Best effort lors d'un échec d'acquisition »). Seul écart : AUD-QUA-07.
```

**Impact.** Pas d'exception avalée sans intention documentée.

**Action.** Aucune.

### AUD-QUA-18 — Typage propre : aucun raw type, Optional limité aux retours, deux @SuppressWarnings justifiés

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — preuve par commande ou mesure ; aucun fichier de code modifié entre 98b1f3f0 et 0b6828ce

**Preuve**

```text
Recherche de raw types `(List|Map|Set|Optional|Class) x =` → 0 ; Optional en champ/paramètre → 0 (seul SqliteProjectRepository.java:141 en retour) ; `@SuppressWarnings` → 2 (SqliteBatchInsert.java:62 commenté, SafeFileIO.java:122 unchecked). `Map<String, Object>` : 79 occurrences concentrées dans les mappers de sortie (McpResultMapper 20, CliResultMapper 11, CliRenderer 11) — cf. AUD-QUA-09.
```

**Impact.** Typage fort dans le domaine ; typage faible confiné aux projections JSON.

**Action.** Aucune.

### AUD-QUA-19 — Taille des fichiers maîtrisée

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
`wc -l` main : 1 seul fichier > 500 lignes (core/src/main/java/com/nexus/application/NexusApplication.java = 532, composition root + façade), 16 fichiers > 300 lignes ; NexusCli 344, CliRenderer 379, SqlScriptSplitter 277, NexusMcpTools 279.
```

**Impact.** Pas de fichier démesuré.

**Action.** Surveiller NexusApplication (façade) ; ne rien découper sans besoin.

### AUD-QUA-20 — Configuration .claude/ hygiénique et exclue de l'indexation NEXUS

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Toujours vrai — .nexusignore (0b6828ce) exclut toujours /.claude/CLAUDE.md et ajoute /.claude/worktrees/ (PR #254).

**Preuve**

```text
.nexusignore exclut `/.claude/CLAUDE.md` (avec justification self-smoke étape 9) ; .claude/settings.json : allow limité à mvnw/git/gh en lecture, `ask` pour `gh pr merge|close`, deny sur .env/*.pem/*.key ; grep de chemins locaux/identifiants dans .claude → seul `C:\Program Files\Git\bin\bash.exe` dans un message d'aide (.claude/scripts/contracts.sh:12). contracts.sh rejoue exactement les 3 contrats du job Linux (ci.yml:63-79) : liste dupliquée à maintenir en double.
```

**Impact.** Pas de secret ni de chemin personnel ; seule dette : liste de contrats recopiée.

**Action.** Optionnel : faire lire la liste des contrats par la CI depuis contracts.sh pour éviter la double maintenance.

### AUD-SEC-09 — NXA-20260914-01 corrigé : les clés JSON/JS citées et les scalaires non cités sont redigés, index historiques invalidés par V009/V010

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)
- **Suivi de l'audit du 14/09** : NXA-20260914-01 : corrigé

**Preuve**

```text
core/src/main/java/com/nexus/security/SecretAssignmentScanner.java:43-73 (clé citée puis `:`/`=`) ; POLICY_VERSION="secret-redaction-v5" SensitiveContentRedactor.java:19 ; core/src/main/resources/db/migration/V009__invalidate_quoted_secret_indexes.sql et V010__invalidate_scalar_secret_indexes.sql (génération +1, NOT_INDEXED). Sonde : `{"password": "…"}`, `{"api_key":"…"}`, `"password" : "a\"…"`, `DB_PASSWORD=…`, `Password=…;Server=x` → [REDACTED].
```

**Impact.** Le scénario de l'audit précédent ne fuit plus ; redaction appliquée à Lucene (LuceneSearchIndex.java:255), embeddings (SemanticIndexingService.java:202, SemanticSearchStrategy.java:125) et frontière publique (PublicContextPolicy.java:50, ContextBundle.java:35).

**Action.** Aucune ; les lacunes restantes sont traitées par AUD-SEC-01.

### AUD-SEC-10 — NXA-20260914-02 corrigé : le timeout Ollama couvre désormais en-têtes et corps (sendAsync + get(timeout)), redirections non suivies

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)
- **Suivi de l'audit du 14/09** : NXA-20260914-02 : corrigé

**Preuve**

```text
core/src/main/java/com/nexus/search/semantic/ollama/OllamaEmbeddingProvider.java:219-228 `httpClient.sendAsync(request, info -> new BoundedBodySubscriber(maxResponseBytes))` puis `exchange.get(timeout.toNanos(), NANOSECONDS)` (« ne termine qu'après le corps entier ») ; :240-245 annulation du subscriber et `exchange.cancel(true)` ; :86-88 HttpClient sans followRedirects (défaut NEVER).
```

**Impact.** Un Ollama qui se bloque après les en-têtes ne peut plus retenir l'appel au-delà du timeout (≤600 s) ; plafond 1 MiB conservé ; erreurs transformées en EmbeddingProviderUnavailableException (repli lexical).

**Action.** Aucune. (Non rejoué par sonde réseau ici : voir angles morts.)

### AUD-SEC-11 — NXA-20260914-03 corrigé : les principaux Windows de confiance sont résolus par SID (S-1-5-18, S-1-5-32-544, S-1-3-0), sans fallback par nom

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)
- **Suivi de l'audit du 14/09** : NXA-20260914-03 : corrigé

**Preuve**

```text
core/src/main/java/com/nexus/config/WindowsStoragePrincipals.java:24-25 `Advapi32Util.getAccountBySid(sid)` ; :42 liste des 3 SID ; :33-36 échec → ensemble vide (mode privé fail-closed) ; NexusPaths.java:342-355 comparaison exacte ; test core/src/test/java/com/nexus/config/WindowsStoragePrincipalsTest.java:24-27 (noms français acceptés, `DOMAIN\Administrateurs` refusé).
```

**Impact.** Les ACL légitimes sur Windows localisé ne déclenchent plus de faux refus ; pas d'élargissement par suffixe.

**Action.** Aucune ; qualifier sur un Windows FR réel (non exécutable ici).

### AUD-SEC-12 — Authentification REST solide par défaut : token exigé même sur loopback, comparaison temps constant, politique de longueur/diversité, management loopback-only

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
adapters/rest-quarkus/src/main/java/com/nexus/api/NexusRestExposureGuard.java:83-90 (token requis sur loopback sauf opt-out) et :33-55 (hors loopback : token, allowlist, mode d'exposition, TLS effectif) ; NexusRestSecurity.java:221-232 `MessageDigest.isEqual` ; :185-192 ≥32 octets ; NexusRestTransportPolicy.java (insecure-requests=disabled, trusted-proxies non globaux) ; application.properties : `quarkus.http.host=127.0.0.1`, `max-body-size=1M`, `quarkus.management.host=127.0.0.1`.
```

**Impact.** Pas de faille distante tant que REST reste en configuration par défaut ; seules extensions Quarkus : rest-jackson, smallrye-health, micrometer (pas d'OpenAPI/Swagger exposé).

**Action.** Aucune.

### AUD-SEC-13 — Réponses d'erreur REST/MCP sans chemins ni stack traces ; chemins projet masqués

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — preuve par commande ou mesure ; aucun fichier de code modifié entre 98b1f3f0 et 0b6828ce

**Preuve**

```text
IllegalArgumentExceptionMapper.java:28-37 (message générique « Requête invalide » sauf 2 messages contrôlés) ; IOExceptionMapper.java:21 ; IllegalStateExceptionMapper.java:38-43 ; ApiMapper.java:38 `rootPath` renvoyé à null ; NexusMcpTools.java:260-272 safeMessage via PublicDiagnosticPolicy ; PublicDiagnosticPolicy.java:47-54 (file: URI et chemins absolus → [INTERNAL_PATH], puis redaction).
```

**Impact.** Pas d'exposition d'informations internes dans les cas mappés.

**Action.** Aucune (exceptions non mappées : voir angles morts).

### AUD-SEC-14 — SQL entièrement paramétré ; LIKE échappé avec ESCAPE et requêtes FTS5 citées trigramme par trigramme

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteIndexSql.java:56-69 (ftsTrigramQuery : `"` doublé ; escapeLike : \, %, _) ; SqliteSymbolSearch.java:58-66 et SqliteRelationQueries.java:70-96 `MATCH ?` / `LIKE ? ESCAPE '\\'` ; SqliteProjectRepository.java:67 `json_each(?)` ; seule concaténation : SqliteBatchInsert.java:65 (préfixe de table constant + placeholders).
```

**Impact.** Pas d'injection SQL ni d'injection d'opérateurs FTS depuis les requêtes clients.

**Action.** Aucune.

### AUD-SEC-15 — Lectures de fichiers projet bornées par ProjectPathGuard + SafeFileIO (NOFOLLOW, SecureDirectoryStream, revalidation) ; aucune lecture de contenu hors de ces garde-fous trouvée

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — preuve par commande ou mesure ; aucun fichier de code modifié entre 98b1f3f0 et 0b6828ce

**Preuve**

```text
grep `Files.read*|newInputStream|FileInputStream|Files.walk|Files.lines` hors SafeFileIO : uniquement readAttributes (ProjectScanner.java:139, ProjectIgnoreMatcher.java:163), walkFileTree sans FOLLOW_LINKS (ProjectScanner.java:93, InstructionDiscoverySupport.java:44/115, AiSkillsRegistryProvider.java:53, LocalAgentSkillsProvider.java:87/165), Files.list d'index internes/JDT plugins ; ProjectPathGuard.java:60-71 (symlink d'ancêtre refusé, toRealPath sous la racine) ; SafeFileIO.java:116-148 ; IndexDocumentPublisher.java:58-62 (relecture + vérification SHA-256 contre le scan).
```

**Impact.** Traversal/symlink/TOCTOU couverts pour le contenu indexé et le contexte ; SCIP lu via guard + SafeFileIO borné (ScipCodeIndexImporter.java:142-158).

**Action.** Aucune.

### AUD-SEC-16 — JDT LS : racine exacte approuvée, environnement réduit à une allowlist de 28 variables, URI non file: ou hors racine ignorées

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/index/jdt/ProcessBuilder.java:33-61 (allowlist), :79-96 (vérification de confiance juste avant start, re-sanitisation), :98-126 (égalité exacte du chemin réel) ; JdtProcessLauncher.java:16-37 (commande en liste, pas de shell) ; JdtLocationMapper.java:39-45 (scheme file, normalisé, sous projectRoot).
```

**Impact.** NEXUS_REST_API_TOKEN et autres secrets du processus ne sont pas transmis au subprocess qui peut exécuter des builds Maven/Gradle.

**Action.** Aucune.

### AUD-SEC-17 — Désérialisation sûre : pas de typage polymorphe Jackson, SnakeYAML Engine sans alias et borné

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
grep `DefaultTyping|JsonTypeInfo|ObjectInputStream|XMLDecoder|new Yaml` : 0 ; core/src/main/java/com/nexus/context/source/skill/SkillFrontmatterParser.java:34-38 `setAllowDuplicateKeys(false).setMaxAliasesForCollections(0).setCodePointLimit(65_536)` ; application.properties `quarkus.jackson.fail-on-unknown-properties=true` ; MINOS en streaming borné 128 MiB (MinosPayloadReader.java:57).
```

**Impact.** Pas de gadget de désérialisation ni de billion-laughs.

**Action.** Aucune.

### AUD-SEC-18 — Ollama : URL issue de la seule configuration (pas de SSRF client), credentials refusés, HTTP distant soumis à opt-in

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/search/semantic/SemanticSearchConfiguration.java:22,90 (NEXUS_OLLAMA_BASE_URL) ; OllamaEndpointResolver.java:70-90 ; aucune route REST/outil MCP ne modifie l'URL. Note : en Docker, une URL loopback est réécrite en http://host.docker.internal (:49-64) après validation, donc en clair sur le pont Docker sans opt-in.
```

**Impact.** Pas de SSRF pilotable par un client ; le trafic Docker→hôte en HTTP reste local à la machine.

**Action.** Aucune (éventuellement documenter la réécriture Docker).

### AUD-SEC-19 — Docker et CI : runtime non-root, bases épinglées par digest, actions tierces toutes épinglées par SHA, aucun pull_request_target

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
packaging/docker/Dockerfile:3-4 (digests), :52 `USER 10001:10001` ; docker-compose.yml.template:36 token obligatoire `:?`, :42 publication `${NEXUS_DOCKER_BIND_ADDRESS:-127.0.0.1}`, :45 workspace `:ro` ; grep `uses:` sans SHA de 40 caractères → uniquement des workflows locaux `./.github/workflows/*` ; grep `pull_request_target|workflow_run|issue_comment` : 0 ; permissions `contents: read` par défaut (ex. ci.yml:15-16), écriture limitée à release.yml:167-170.
```

**Impact.** Surface supply-chain et conteneur maîtrisée.

**Action.** Aucune.

### AUD-TST-12 — Build mesuré vert : 618 tests (519 core + 53 REST + 22 MCP + 24 assistant), 24 ignorés tous attendus, tous les jacoco:check passent

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — preuve par commande ou mesure ; aucun fichier de code modifié entre 98b1f3f0 et 0b6828ce

**Preuve**

```text
/home/claude/audit-work/run/mvn-verify.log:541 « Tests run: 519, … Skipped: 24 », :734 « 53 », :819 « 22 », :899 « 24 », :573-920 « All coverage checks have been met. » ; couverture core 83,8 %/67,7 %, REST 73,4 %/69,0 %, MCP 89,7 %/78,8 %, assistant 94,5 %/88,8 % (/home/claude/audit-work/run/summary.md).
```

**Impact.** Les 24 skips = 14 opt-in/benchmark + 10 tests POSIX (séparateur « / ») qui s'exécutent sur le job Linux (.github/workflows/ci.yml:49,84 ubuntu-latest ./mvnw -B clean install, sans filtre OS).

**Action.** Aucune ; vérifier dans le log Linux CI que le nombre de skips y est ≤ 15 (POSIX exécutés).

### AUD-TST-13 — Redaction et bornes bien testées : SensitiveContentRedactorTest 260 l. (JSON cités, quotes échappées, clés composées, clés privées, lookahead borné), SecretAssignmentScanner 121/132 branches

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java:28-33 (JSON + YAML commentaire), :85 « redactsQuotedJsonAndJavascriptKeysIncludingEscapedValues », :227 clés privées ; JdtJsonRpcFrameReaderTest.java:35 MAX_MESSAGE_BYTES+1 ; ScipIndexLimitsTest/ScipParseBudgetTest ; OllamaEmbeddingProviderResponseLimitTest ; NexusRestSecurity 91 %/80 %, NexusRestAuthenticationFilter 100 %.
```

**Impact.** Chemins secrets/token/bornes de protocole couverts ; trou non couvert : scalaires YAML multi-lignes (« password: | ») non testés (grep 0).

**Action.** Ajouter un cas YAML bloc littéral/plié et documenter le comportement attendu.

### AUD-TST-14 — Surefire sans exclusion de tags, failIfNoSpecifiedTests forcé en CI, CI ci.yml sur PR/push develop+main en Linux (JDK 21) et Windows (JDK 24)

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 3 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
pom.xml:153-160 (pas d'excludedGroups/groups) ; pom.xml:319-330 profil ci-strict-specified-tests (env.CI=true) ; .github/workflows/ci.yml:5-12, :25 windows-latest + validate-phase-6.ps1 (clean install + self-smoke), :49/:84 ubuntu ./mvnw -B clean install ; .claude/rules/ci-and-scripts.md : « Windows gate », « Linux reactor Maven build » requis.
```

**Impact.** Le lifecycle install inclut verify → jacoco:check bloquant sur les deux OS. Réserve : validate-phase-6.ps1:73-80 bascule sur un mvn système si le wrapper échoue ; le Windows gate n'exécute pas la suite sous JDK 21.

**Action.** Supprimer le fallback mvn système en CI ; envisager une matrice JDK 21 sur Windows.

### AUD-PRF-19 — Lots d'écriture SQLite multi-lignes bornés à 128 lignes, conformes à l'ADR 0051

- **Axe** : Performance · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteBatchInsert.java:26 `private static final int ROW_LIMIT = 128;`, :44-49 exécution par lot complet, transaction possédée par l'appelant.
```

**Impact.** Pas de N+1 à l'insertion des symboles/relations (embarqués et externes, SqliteProviderWriter.java:160-200 via map fileIds).

**Action.** Aucune.

### AUD-PRF-20 — Lectures du registre fédéré groupées (json_each, tranches de 500) conformément à l'ADR 0054

- **Axe** : Performance · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteProjectRepository.java:65-82 `WHERE id IN (SELECT value FROM json_each(?))` + `for (int offset = 0; offset < ids.size(); offset += 500)` ; findAll charge langages/technologies en 2 requêtes globales (:126-127).
```

**Impact.** Pas de N+1 sur le registre.

**Action.** Aucune.

### AUD-PRF-21 — Requêtes de graphe groupées par json_each et contraintes par index (project_id, kind, ref), avec test de plan

- **Axe** : Performance · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteGraphQueries.java:12-20 et :73-80 `INDEXED BY idx_symbol_relations_project_kind_target` ; V003:13-17 ; core/src/test/java/com/nexus/persistence/sqlite/SqliteGraphQueryPlanTest.java ; GraphCandidateEnricher.java:40 un seul findFiles groupé.
```

**Impact.** Le voisinage graphe ne fait pas de N+1 par candidat.

**Action.** Aucune.

### AUD-PRF-22 — Cache de searchers Lucene borné à 100 projets (= MAX_PROJECTS) sans writer retenu, rafraîchi après commit (ADR 0045)

- **Axe** : Performance · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/search/lucene/PersistentLuceneSearchIndex.java:24 `MAX_CACHED_PROJECTS = 100` ; BoundedLuceneSearcherCache.java:221-233 `maybeRefreshBlocking()` puis acquire/release ; PersistentLuceneSearchIndex.java:44-46 refreshIfCached après applyChanges, invalidate avant rebuild (:51-54).
```

**Impact.** Recherche lexicale sans ouverture de reader par requête dans les runtimes longue durée (hors AUD-PRF-04/05).

**Action.** Aucune ; à noter : pas d'éviction, un miss au-delà de la capacité retombe sur le chemin operation-scoped.

### AUD-PRF-23 — Embeddings d'indexation envoyés par lots (≤ 256 documents, budget de caractères) et fingerprint canonique mis en cache par génération

- **Axe** : Performance · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java:62 (batchSize 1–256), :141-159 boucle de lots `embeddingProvider.embedAll(...)` ; OllamaEmbeddingProvider.java:209-210 un seul POST `input: texts` ; SemanticSearchStrategy.java:189-197 cache `(generation, fingerprint)`.
```

**Impact.** Pas d'appel HTTP par document ; findFiles complet évité tant que la génération ne change pas.

**Action.** Aucune.

### AUD-PRF-24 — Une seule connexion SQLite réutilisée par requête applicative (session de lecture thread-local)

- **Axe** : Performance · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteDatabase.java:48-62 (connexion empruntée via proxy, close neutralisé) et :65-88 ; NexusApplication.java:211, :231, :254, :282 `indexRepository.openReadSession()` autour de search/context (fédérés inclus).
```

**Impact.** Évite l'ouverture d'une connexion + PRAGMA + chargement du schéma par instruction.

**Action.** Aucune.

### AUD-PRF-25 — Lot de documents d'indexation borné (128 fichiers / 16 MiB) et temporaires SQLite en mémoire (ADR 0050)

- **Axe** : Performance · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
core/src/main/java/com/nexus/index/ProjectIndexingService.java:31-32 ; IndexDocumentPublisher.java:46-49 et :76-78 flush avant/après dépassement ; SqliteDatabase.java:105 `PRAGMA temp_store = MEMORY`.
```

**Impact.** Mémoire de contenu bornée pendant l'indexation (hors analyses, cf. AUD-PRF-12).

**Action.** Aucune.

### AUD-DEP-23 — Toutes les actions externes sont épinglées par SHA de 40 caractères vérifié, aucun pull_request_target, permissions read par défaut sur les 19 workflows

- **Axe** : Dépendances · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
Script YAML sur .github/workflows/*.yml : `unpinned=[]` pour les 19 fichiers, `perms={'contents': 'read'…}` au niveau workflow ; grep pull_request_target|workflow_run → aucun ; écritures (`contents/packages/id-token/attestations: write`) limitées au job publish (release.yml:167-171). SHA vérifiés par git ls-remote (checkout, upload/download-artifact, setup-java, attest, trivy-action, codeql-action).
```

**Impact.** Chaîne d'approvisionnement CI saine.

**Action.** Conserver ; ajouter un contrôle SHA ↔ commentaire (cf. AUD-DEP-10).

### AUD-DEP-24 — Wrapper Maven durci : archive 3.10.0 vérifiée par ancre SHA-512 versionnée et cache extrait revérifié

- **Axe** : Dépendances · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
mvnw:4-18,46,98 ; config/tool-integrity.properties:4 `maven.3.10.0.sha512=22d3…` (ancres jdtls et ollama l.5-6) ; distributionSha256Sum absent de .mvn/wrapper/maven-wrapper.properties mais non utilisé (wrapper maison).
```

**Impact.** Bootstrap Maven intègre ; Dependabot ne doit pas remplacer ces scripts (consigne .claude/skills/nexus-dependency-bump/SKILL.md:18).

**Action.** Conserver.

### AUD-DEP-25 — Images de base épinglées par digest, runtime JRE UBI10 minimal non-root, REST en loopback par défaut

- **Axe** : Dépendances · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 2 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
packaging/docker/Dockerfile:3-4 (`maven:3.9.11-…@sha256:6fdc…`, `eclipse-temurin:21.0.12_8-jre-ubi10-minimal@sha256:c7fe…`), :52 USER 10001 ; docker-compose.yml.template:35,41 (token requis, bind 127.0.0.1) ; Dependabot docker actif (.github/dependabot.yml:19-24).
```

**Impact.** Base saine.

**Action.** Conserver.

### AUD-DEP-26 — Dépendances majeures à jour au 17/10/2026 ; seul le SDK MCP a une mineure publiée depuis deux jours

- **Axe** : Dépendances · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
pom.xml:40-52 vs git ls-remote --tags : Quarkus 3.40.1, Lucene 10.5.2, sqlite-jdbc 3.53.4.0, Jackson 2.22.3, Micrometer 1.17.1, JavaParser 3.28.2, JGit 7.8.0.202609011348-r = derniers tags ; MCP SDK 2.0.1 utilisé, v2.1.0 taguée le 2026-10-15T19:05+02:00.
```

**Impact.** Pas de dette de version ; aucune CVE vérifiable faute d'accès API (angle mort).

**Action.** Laisser Dependabot proposer MCP SDK 2.1.0 et le qualifier.

### AUD-DEP-27 — Chaîne de release conçue fail-closed : tag = HEAD de main, tous les gates en needs, tags immuables, attestations provenance + SBOM, Trivy bloquant

- **Axe** : Dépendances · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — preuve par commande ou mesure ; aucun fichier de code modifié entre 98b1f3f0 et 0b6828ce

**Preuve**

```text
release.yml:28-46 (validate-release-tag.sh), :151-164 (13 needs), :299-350 (immutabilité et digest), :352-365 (attest) ; docker-distribution.yml:265-274 et windows-installer.yml:248-257 (Trivy exit-code 1 sur HIGH/CRITICAL corrigeables) ; windows-installer.yml:74-77 (Authenticode exigé en release).
```

**Impact.** Conception solide, mais jamais exécutée (AUD-DEP-02).

**Action.** Conserver.

### AUD-DEP-28 — Le constat NXA-20260914-04 (gate de charge rouge) est résolu sans relèvement des plafonds

- **Axe** : Dépendances, Performance, Tests · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Fusionne** : `AUD-PRF-26`
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 1 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
WebFetch …/actions/workflows/scale-benchmark.yml : #390 (PR #250, 16/10) et #389 verts ; Develop Push Scale Benchmark #57 vert ; scripts/verify-scale-budgets.py:128-129 plafonds inchangés (25 projets : search 160, context 260). | Vu aussi par AUD-PRF-26 : scripts/verify-scale-budgets.py:127-136 `25: {'search': 160, 'context': 260}` (plafonds identiques à ceux cités par l'audit du 14/09) ; FederatedSearchService.java:56 et FederatedContextService.java:78 toujours séquentiels ; seul le timeout du workflow sémantique réel a été fondé sur mesures (.githu | AUD-TST-05 (lecture statique, « toujours ouvert ») écarté : les runs réels sont verts.
```

**Impact.** Gate de charge à nouveau qualifiant.

**Action.** Aucune.

### AUD-DEP-29 — Observabilité en place : health liveness/readiness, Prometheus sur listener de management loopback, journalisation System.Logger

- **Axe** : Dépendances · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-10-29** (`0b6828ce`) : Inchangé — 3 fichier(s) cité(s), aucun modifié entre 98b1f3f0 et 0b6828ce (git diff --name-only : 4 fichiers de configuration)

**Preuve**

```text
adapters/rest-quarkus/src/main/resources/application.properties:8-13 (management 127.0.0.1:9000, prometheus, binder http-server) ; adapters/rest-quarkus/src/main/java/com/nexus/api/NexusLivenessCheck.java, NexusReadinessCheck.java ; 9 usages System.getLogger dans core/adapters.
```

**Impact.** Exploitation outillée ; pas de configuration de format de logs (JSON) ni de niveau par environnement.

**Action.** Optionnel : quarkus-logging-json pour Docker.
