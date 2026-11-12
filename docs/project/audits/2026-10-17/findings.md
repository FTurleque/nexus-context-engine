# Constats — audit du 17 octobre 2026

Commit audité : `origin/develop` @ `98b1f3f0`. Constats triés par sévérité puis par axe ; chacun porte son sprint et ses dépendances. Source structurée : [`findings.json`](findings.json). Ordre d'exécution : [`sprints.md`](sprints.md).

| Sévérité | Nombre |
|---|---:|
| Élevée | 2 |
| Moyenne | 25 |
| Faible | 86 |
| Info | 44 |
| **Total** | **157** |

## Élevée (2)

### AUD-QUA-22 — L'installateur réécrit les configurations MCP des clients IA sans sauvegarde : chemins accentués corrompus, JSON invalide remplacé par un fichier vide

- **Axe** : Qualité, Sécurité · **Effort** : S · **Sprint** : S13 · Installateur et release finale
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Lecture UTF-8 par Read-ClientJson, contrôle de tous les fichiers avant écriture, sauvegarde <fichier>.nexus-backup-<horodatage> puis File.Replace, arrêt sur erreur avec trap qui nomme le fichier, désinstallation qui continue après un fichier illisible et garde le drapeau *Managed (PR #354, sprint 13). · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — iss.template : Read-ClientJson/sauvegarde/File.Replace présents (7 occurrences) ; ajouts = garde ANSI du dossier, SaveUtf8TextFile. | Test actif : test-windows-installer.ps1 (Windows, non exécutable ici) Réserve : Réserve d'origine inchangée.

**Preuve**

```text
packaging/windows/nexus-installer.iss.template:1681 et :1685 (connect-claude-desktop.ps1, exécuté par {sys}\WindowsPowerShell\v1.0\powershell.exe, :1542) :
  '  $c = if (Test-Path $cfg) { Get-Content $cfg -Raw | ConvertFrom-Json } else { [PSCustomObject]@{} }'
  ...
  '  Save-CleanJson $cfg $c'
idem :1604/:1609 (Copilot JetBrains mcp.json) et :1792-1809 (désinstallation) ; Save-CleanJson :1453-1458 écrit par [IO.File]::WriteAllText sans copie préalable ; aucun $ErrorActionPreference='Stop' dans ces scripts (seule occurrence :528) ; aucun -Encoding sur Get-Content ; grep backup|.bak|Copy-Item → 0 ; test-windows-installer.ps1 ne teste ni JSON invalide ni caractère non ASCII. | packaging/windows/nexus-installer.iss.template:1681 `$c = if (Test-Path $cfg) { Get-Content $cfg -Raw | ConvertFrom-Json } ...` (connect-claude-desktop.ps1), :1805 (disconnect-claude-desktop.ps1, désinstallation), :1604 et :1792 (Copilot JetBrains) : Get-Content sans -Encoding sous Windows PowerShell 5.1 (exécutable imposé :1542, :2264) lit un fichier UTF-8 sans BOM, format de Claude Desktop, dans la page de code ANSI, puis Save-CleanJson (:1453-1459) réécrit en UTF-8 : un caractère non ASCII des autres entrées (ex. chemin C:\Users\Frédéric\...) devient « FrÃ©dÃ©ric ». Aucun $ErrorActionPreference='Stop' dans ces scripts (seul :528 en pose un) : si ConvertFrom-Json échoue (JSON invalide), l'affectation est abandonnée, $c reste $null (ou garde l'objet du fichier précédent dans la boucle foreach :1680), et Save-CleanJson écrit 'null' ou le contenu d'un autre fichier, puis le script sort en 0. Aucune copie de sauvegarde. Test d'installateur en ASCII seul : scripts/release/test-windows-installer.ps1:203-205 (-Encoding ascii).
```

**Impact.** Sur un poste dont le nom d'utilisateur ou un chemin contient un accent (courant en français), connecter ou déconnecter NEXUS corrompt silencieusement les autres serveurs MCP déclarés dans claude_desktop_config.json et le mcp.json de Copilot JetBrains. Un fichier JSON invalide est remplacé par un fichier vide, sans sauvegarde, avec un code de sortie 0.

**Action.** Lire avec [IO.File]::ReadAllText(…, UTF8) (ou Get-Content -Encoding UTF8), poser $ErrorActionPreference='Stop' et refuser d'écrire si ConvertFrom-Json échoue, sauvegarder le fichier avant écriture, écrire par fichier temporaire puis remplacement ; ajouter des tests avec chemins non ASCII et JSON invalide dans test-windows-installer.ps1.

### AUD-QUA-28 — L'installateur écrit la configuration native, le .env Docker et le fragment TOML de Codex dans la page ANSI : avec un profil accentué, NEXUS_HOME par défaut est introuvable

- **Axe** : Qualité, Dépendances · **Effort** : M · **Sprint** : S14 · Suites de la vérification du 11 novembre
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Écriture UTF-8 sans BOM (SaveUtf8TextFile) de nexus-native.env.cmd, docker\.env, codex-desktop.mcp.toml, copilot-jetbrains.mcp.json et generic-mcp.json ; NEXUS_HOME accentué sous le profil écrit %USERPROFILE%\… ou %LOCALAPPDATA%\… (CmdEnvPathValue), autres lignes non ASCII lues sous chcp 65001 avec page de code restaurée (CmdUtf8Frame) ; dossier d'installation hors page ANSI refusé par l'assistant (le runtime Java embarqué ne démarre pas depuis un tel dossier) ; smoke avec profil « Hélène Łukasz » et dossier « Instàllé Hélène ». · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — Source durcie : plus de SaveStringToFile sur une configuration ; env.cmd, docker\.env, TOML et JSON Codex/Copilot/générique via SaveUtf8TextFile (template:1325-1348, 1391, 1746-1790). Restent ANSI : docker-compose.yml (copie d'octets AnsiString :1393-1395), nexus-docker-up/down.cmd et connect-*.cmd (ASCII sauf un echo, chemins %~dp0). Portage des règles réelles : Hélène, Łukasz, 李 → set "NEXUS_HOME=%USERPROFILE%\.nexus" ASCII sans cadre ; O'Brien & Co, 50% (%%), a^b, (x86) → littéral ASCII entre guillemets, sûr ; hors profil non ASCII → cadre chcp 65001 + restauration. Dossier d'installation hors ACP refusé (IsAnsiRoundTrip :1070, :1094, :1309) ; Hélène en 1252 accepté. | Test actif : Oui : windows-installer.yml (pull_request vers main sur packaging/windows/**, scripts/release/**…, workflow_dispatch, workflow_call) exécute test-windows-config-escaping.ps1 (:77) puis test-windows-installer.ps1 sur l'installateur smoke (:109) ; appelé par main-push-windows-installer.yml (push main, filtre de chemins) et release.yml:59. Plus aucune référence à develop. | Rejoué le 12/11 sous Windows PowerShell 5.1 sur ta machine (ACP 1252, console 850), fonctions Pascal extraites de la source durcie comme en CI : profils « Hélène », « Łukasz », « 李 » → set "NEXUS_HOME=%USERPROFILE%\.nexus" ; « O'Brien & Co », « 50% », « (x86) » et un chemin non ASCII hors profil (cadre chcp 65001) → NEXUS_HOME résolu sur le dossier réel, console visible et cachée ; contrôle négatif ANSI sans cadre → MISSING. 8/8 PASS. Réserve : Non rejoué sous Windows ici (repro-qua28.ps1 à lancer) ; docker\.env et copilot-jetbrains.mcp.json sans smoke (contrat statique) ; littéral non ASCII hors profil illisible sans console (DETACHED_PROCESS) ; profil hors page ANSI (Łukasz en 1252, 李) : dossier par défaut {localappdata}\Programs\NEXUS refusé, l'utilisateur doit en choisir un autre ; NEXUS_HOME/dépôt Docker contenant ^ refusés (voir nouveau constat). Rejeu du 12/11 : avec un profil hors page 1252 (« Łukasz », « 李 »), le dossier d'installation par défaut {localappdata}\Programs\NEXUS est refusé et l'utilisateur doit en choisir un autre ; un profil contenant « ^ » est refusé par l'assistant (AUD-QUA-29).

**Preuve**

```text
SaveStringToFile (AnsiString, conversion ANSI : cf. packaging/windows/nexus-installer.iss.template:1439-1440) écrit :1293 config\nexus-native.env.cmd (NEXUS_HOME, défaut %USERPROFILE%\.nexus :920), :1336 docker\.env (NEXUS_REPOSITORY_BIND, défaut %USERPROFILE% :972, harden-windows-installer-source.ps1:211), :1725-1727 codex-desktop.mcp.toml et :1691-1693, :1733-1735 *.mcp.json contenant {app} (défaut {localappdata}\Programs\NEXUS :17). cmd.exe lit le .env.cmd dans la page OEM (850) : é (E9 en 1252) devient « Ú » ; connect-codex-desktop.ps1 relit le TOML en UTF-8 (:1847) : é devient U+FFFD puis part dans ~/.codex/config.toml ; Compose lit .env en UTF-8.
```

**Impact.** Profil « Hélène » avec les valeurs par défaut : nexus-mcp.cmd/nexus.cmd (build-windows-distribution.ps1:263, :271) reçoivent un NEXUS_HOME inexistant (C:\Users\HÚlÞne\.nexus), Codex Desktop reçoit un chemin Java faux, le bind du dépôt Docker vise un autre dossier. Un caractère hors de la page ANSI devient « ? ». Même population que AUD-QUA-22 ; aucun test (smoke en ASCII).

**Action.** Écrire ces fichiers en UTF-8 (sans BOM pour .env/TOML/JSON) ; pour le .cmd, chcp 65001 encadré ou des valeurs dérivées de %USERPROFILE%/%LOCALAPPDATA% plutôt que littérales ; ajouter à test-windows-installer.ps1 un profil et un {app} accentués avec contrôle de NEXUS_HOME vu par nexus.cmd et du TOML Codex.

## Moyenne (25)

### AUD-ARC-01 — Composante fortement connexe de 12 packages du cœur (context, context.source, .git, .skill, index, index.scan, ranking, ranking.graph, search, search.semantic, search.semantic.ollama, security)

- **Axe** : Architecture · **Effort** : L · **Sprint** : S7 · Casser le bloc cyclique du cœur
- **Dépend de** : `AUD-ARC-02`, `AUD-ARC-03`, `AUD-ARC-04`, `AUD-ARC-05`, `AUD-ARC-06`, `AUD-ARC-07`, `AUD-ARC-20` · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Les cinq cycles bilatéraux sont cassés par l'extraction des packages feuilles (index.service, context.model, search.candidate) et la remontée de la construction Ollama dans le composition root ; le graphe des packages du cœur est acyclique, décision ARCH-0076, verrouillée par CoreArchitectureTest avec une baseline de cycles vide (PR #300) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
Commande : graphe des `import com.nexus.*` des 243 fichiers main + Tarjan (scratchpad graph.py/scc.py) → « SCC 12 [...] ». Arêtes retour : core/src/main/java/com/nexus/index/IndexRepository.java:3 `import com.nexus.search.ResultLimitPolicy;` ; core/src/main/java/com/nexus/search/SymbolSearchStrategy.java:7 `import com.nexus.index.IndexRepository;` ; core/src/main/java/com/nexus/ranking/ContextRanker.java:3 `import com.nexus.search.SearchCandidate;` ; core/src/main/java/com/nexus/search/SearchService.java:4 `import com.nexus.ranking.ContextRanker;`
```

**Impact.** Les capacités index/recherche/ranking/contexte/sécurité ne sont pas séparables : toute modification d'un type de modèle recompile et risque d'impacter les 12 packages ; l'extraction prévue par ADR-0004 (nexus-core, adaptateurs lourds) est impossible sans casser d'abord ces cycles. ADR-0004 l.36 rend pourtant « obligatoire » la séparation logique par packages.

**Action.** Casser les 5 cycles bilatéraux (AUD-ARC-02 à 06) en isolant les types de modèle partagés (CodeSymbol, FileCategory, SearchCandidate, ContextFragment, ResultLimitPolicy) dans des packages feuilles et en remontant l'orchestration (ProjectIndexingService, SearchService) d'un cran ; viser un DAG puis le verrouiller (AUD-ARC-07).

### AUD-ARC-02 — Cycle index ↔ search : le package index mêle modèle de domaine, port de persistance et orchestration qui dépend des ports de recherche

- **Axe** : Architecture · **Effort** : M · **Sprint** : S7 · Casser le bloc cyclique du cœur
- **Dépend de** : `AUD-ARC-07` · **Débloque** : `AUD-ARC-01`
- **Statut** : Fermé · **Changement** : ProjectIndexingService, IndexDocumentPublisher et ExternalCodeIntelligenceRefresher passent dans le package d'orchestration index.service, ResultLimitPolicy dans config et SearchDocument dans index, et le port SemanticIndexing (implémenté par SemanticIndexingService, NoopSemanticIndexing à la place de null) remplace la classe concrète (PR #300) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/index/ProjectIndexingService.java:8-9 `import com.nexus.search.SearchIndex;` / `import com.nexus.search.semantic.SemanticIndexingService;` ; core/src/main/java/com/nexus/index/IndexDocumentPublisher.java:3-5 ; core/src/main/java/com/nexus/search/SearchDocument.java:5 `import com.nexus.index.CodeSymbol;` (search→index : 10 imports, index→search : 4, index→search.semantic : 2)
```

**Impact.** Le port IndexRepository (index) dépend d'une politique de search, et le service d'indexation dépend de la classe concrète SemanticIndexingService (null = désactivé) : couplage indexation ↔ recherche lexicale/sémantique, régressions croisées.

**Action.** Déplacer ProjectIndexingService/IndexDocumentPublisher/ExternalCodeIntelligenceRefresher dans un package d'orchestration (ex. index.service) ou déplacer ResultLimitPolicy/SearchDocument dans un package neutre ; introduire un port d'indexation sémantique (interface) à la place de la classe concrète.

### AUD-ARC-03 — Cycle context ↔ security : la politique de sortie publique est rangée dans security et dépend des bundles de contexte

- **Axe** : Architecture · **Effort** : S · **Sprint** : S4 · Une seule sortie publique, redaction complétée
- **Dépend de** : `AUD-QUA-09` · **Débloque** : `AUD-ARC-01`
- **Statut** : Fermé · **Changement** : Corrigé par la PR #284 (050af1e4) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/security/PublicContextPolicy.java:5-8 `import com.nexus.context.ContextBundle;` … `FederatedContextItem;` ; core/src/main/java/com/nexus/context/ContextFragmentFactory.java:6-8 `import com.nexus.security.ProjectPathGuard;` / `SafeFileIO;` / `SensitiveContentRedactor;`
```

**Impact.** Le package transverse security (I/O sûres, redaction) n'est plus une brique feuille : toute évolution du modèle de contexte touche security, et inversement ; empêche de réutiliser security dans un module séparé.

**Action.** Déplacer PublicContextPolicy (projection de sortie) vers context (ou un package exposure/application) ; garder security sans dépendance vers les packages métier (il ne dépend aujourd'hui que de config/paths/project en plus de context).

### AUD-ARC-07 — Aucune protection structurelle des frontières : pas de test d'architecture, pas de module-info, 160/219 types publics

- **Axe** : Architecture · **Effort** : S · **Sprint** : S3 · Filets de test et traces
- **Dépend de** : aucun · **Débloque** : `AUD-ARC-01`, `AUD-ARC-02`, `AUD-ARC-04`, `AUD-ARC-05`, `AUD-ARC-06`
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #282 (b6b56a07) · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — core/pom.xml : version + planchers relevés ; CoreArchitectureTest.java identique à 1111, aucun @Disabled. | Test actif : core/src/test/java/com/nexus/architecture/CoreArchitectureTest.java Réserve : Réserve d'origine inchangée.

**Preuve**

```text
Commande : `grep -rli 'archunit|jdeps|ArchitectureTest' core/src/test adapters/*/src/test pom.xml core/pom.xml scripts .github` → aucun résultat ; `find . -name module-info.java` → aucun ; 160/219 fichiers main du core déclarent un type public. docs/adr/0004-demarrer-avec-un-seul-module-maven.md:70 « Maven ne protège pas encore physiquement toutes les frontières ».
```

**Impact.** Les règles « ports/adaptateurs », « core sans framework », « Lucene/SQLite confinés » ne reposent que sur la discipline ; la SCC d'AUD-ARC-01 montre l'érosion déjà acquise ; un adaptateur peut instancier n'importe quel service interne (ex. tests REST/MCP qui construisent SqliteDatabase).

**Action.** Ajouter un test JUnit sans dépendance (parse des imports, comme graph.py) ou ArchUnit qui fige : core sans io.quarkus/jakarta/spring, org.apache.lucene ⊂ search.lucene|search.semantic.lucene, org.sqlite/java.sql ⊂ persistence.sqlite (+ application), project feuille, et un baseline de cycles qui ne peut que décroître.

### AUD-QUA-01 — Les sorties MCP et REST exposent scoreComponents dans un ordre qui change à chaque démarrage de JVM

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S4 · Une seule sortie publique, redaction complétée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #284 (050af1e4) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/ranking/RankedCandidate.java:30 `components = Map.copyOf(components);` ; core/src/main/java/com/nexus/context/ContextItem.java:36 `scoreComponents = Map.copyOf(scoreComponents);` ; adapters/mcp-java/src/main/java/com/nexus/mcp/McpResultMapper.java:68 `result.put("scoreComponents", ranked.components());` et :152 ; adapters/rest-quarkus/src/main/java/com/nexus/api/ApiMapper.java:141 et :171 (map passée telle quelle) ; à l'inverse core/src/main/java/com/nexus/cli/CliResultMapper.java:44 `new TreeMap<>(ranked.components())`. Aucune option Jackson de tri (grep ORDER_MAP_ENTRIES_BY_KEYS : 0 ; application.properties ne règle que fail-on-unknown-properties). Reproduction : 6 exécutions de `java MapOrder.java` (Map.copyOf de 6 clés) → 4 ordres distincts.
```

**Impact.** Viole la règle de déterminisme (.claude/rules/java-core.md : « ne jamais exposer l'ordre d'itération d'un HashMap/HashSet ») : deux appels MCP/REST identiques sur deux processus produisent un JSON différent (diff, cache, golden tests côté client). La CLI est déterministe : parité rompue.

**Action.** Projeter en TreeMap (ou LinkedHashMap trié) dans McpResultMapper et ApiMapper, ou trier dès le record ; ajouter un test qui sérialise deux fois avec des JVM distinctes ou compare à l'ordre trié.

### AUD-QUA-05 — 12 scripts PowerShell redéfinissent chacun une fonction Invoke-Maven et 16 appellent `mvn` au lieu du wrapper

- **Axe** : Qualité · **Effort** : M 1-3j · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : `AUD-QUA-04` · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #281 (85c08184) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
`grep -ho '^function [A-Za-z-]*' scripts/*.ps1 | sort | uniq -c` → `12 function Invoke-Maven`, `5 function Resolve-AbsolutePath`, `3 function Invoke-NexusJson`. `grep -lE '& mvn |"mvn"' scripts/*.ps1` → 16 fichiers (ex. scripts/validate-iteration-17.ps1:13 `& mvn @Arguments`) ; seuls 4 scripts utilisent mvnw. | validate-phase-6.ps1:72 est traité à part par AUD-QUA-06.
```

**Impact.** Duplication sans module partagé (aucun .psm1) ; la convention « toujours le wrapper Maven » (.claude/CLAUDE.md) n'est pas tenue par les scripts de mesure/comparaison ; toute correction (codes retour, arguments) doit être répétée 12 fois.

**Action.** Extraire un module scripts/lib/NexusScript.psm1 (Invoke-Maven via wrapper, Resolve-AbsolutePath, Invoke-NexusJson) et l'importer ; archiver les scripts d'itération obsolètes (AUD-QUA-04) avant de migrer les autres.

### AUD-QUA-06 — Le gate CI Windows retombe silencieusement sur le Maven installé du runner si le wrapper échoue

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S1 · Remettre les mesures d'aplomb
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #266 (89a37f0b) · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — ci.yml : seul le retrait de develop des triggers PR/push (main conservé) ; validate-phase-6.ps1 inchangé (aucun repli « Maven installé ») ; README dev : integration/release/Dependabot passés à main. | Test actif : test-tool-integrity-anchors.sh PASS (fixtures hygiene ajoutées)

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
- **Statut** : Fermé · **Changement** : Corrigé par la PR #284 (050af1e4) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/cli/CliResultMapper.java:49-62 contextItemMap : `"path", repositoryPath(item.path())` ; adapters/mcp-java/src/main/java/com/nexus/mcp/McpResultMapper.java:143-157 contextItem : `"path", relativePath(project, item.path())` (PublicProjectPathPolicy). CliResultMapper.java:33-46 rankedResult sans `excerpt` ni `id` ; McpResultMapper.java:61-73 avec `excerpt` redacté et `id`. CLI : `ESTIMATED_TOKENS_FIELD` ; MCP : littéral "estimatedTokens". relativePath dupliqué (CliResultMapper.java:96, McpResultMapper.java:76). | Vu aussi par AUD-ARC-08 : core/src/main/java/com/nexus/cli/CliRenderer.java:194 `bundle = com.nexus.security.PublicContextPolicy.expose(project, bundle, query);` ; adapters/mcp-java/src/main/java/com/nexus/mcp/McpResultMapper.java:106 `var bundle = com.nexus.security.PublicContextPolicy.expose(...)` ; adapters/rest-quarkus/s | Taille des mappers : CliResultMapper 103, McpResultMapper 159, ApiMapper 180 lignes.
```

**Impact.** La règle de parité CLI/REST/MCP (.claude/rules/adapters.md) dépend d'une relecture manuelle de 3 mappers (103 + 159 + 180 lignes) ; AUD-QUA-01 en est une conséquence.

**Action.** Introduire dans le cœur une projection publique unique (record ou Map ordonnée) consommée par les trois adaptateurs, et un test de parité qui compare les clés produites.

### AUD-QUA-10 — Méthodes longues concentrées dans le contexte fédéré, l'indexation et la découverte Git/skills

- **Axe** : Qualité · **Effort** : M 1-3j · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : aucun · **Débloque** : `AUD-PRF-03`
- **Statut** : Fermé · **Changement** : FederatedContextService.build et ProjectIndexingService.indexLocked sont découpés en étapes privées nommées (validation, collecte par projet, sélection, métadonnées ; diff, publication, providers externes, vérification, statut) sans changement de comportement ; NexusApplicationFactory.create reste linéaire comme le demandait l'action (sprint 6, tâches 1.1 et 1.2, PR #299) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
Mesure heuristique (script d'accolades, 1 213 méthodes) : 18 méthodes > 60 lignes, 3 > 100 : core/src/main/java/com/nexus/context/FederatedContextService.java:45 build = 168 lignes ; core/src/main/java/com/nexus/index/ProjectIndexingService.java:219 indexLocked = 111 ; core/src/main/java/com/nexus/context/source/git/LocalGitContextSourceProvider.java:55 discover = 106 (imbrication 4) ; NexusApplicationFactory.java:66 create = 99 ; SemanticHybridContextRanker.java:68 rank = 95 (imbrication 4).
```

**Impact.** Méthodes de 100+ lignes au cœur du budget et de l'indexation : relecture et tests de branches plus coûteux, risque de régression sur la sélection déterministe.

**Action.** Découper FederatedContextService.build (validation, collecte par projet, sélection, métadonnées) et indexLocked en étapes nommées ; ne pas toucher NexusApplicationFactory.create (composition linéaire).

### AUD-SEC-01 — La redaction, liste fermée documentée comme non exhaustive, ne couvre pas 19 formats de secrets courants (Bearer, token=, sk-, xoxb-, AIza, glpat-, PGP…)

- **Axe** : Sécurité · **Effort** : M 1-3j · **Sprint** : S4 · Une seule sortie publique, redaction complétée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #284 (050af1e4) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/security/SecretAssignmentScanner.java:10-12 — liste fermée des composants de clé : api_key|access_token|auth_token|client_secret|secret_access_key|password|passwd|secret (pas de token, auth, pwd, credentials, private_key, access_key, Authorization) ; SensitiveContentRedactor.java:28-29 — STRUCTURED_TOKEN limité à gh*_, github_pat_, AKIA/ASIA ; :24 PRIVATE_KEY_SUFFIX="PRIVATE KEY-----" exclut "PGP PRIVATE KEY BLOCK-----". Sonde (copie des 2 classes, java 21) : LEAK pour `Authorization: Bearer …`, `token=…`, `"token": "…"`, `private_key: …`, `'sk-proj-…'`, `xoxb-…`, `AIza…`, `sk_live_…`, `glpat-…`, `npm_…`, `https://<token>@github.com`, `pwd=`, `credentials:`, `<password>…</password>`, `secretKey = "…"`, `--password …`, `access_key=`, `auth=`, bloc PGP, `password: |` (YAML bloc), `"password":\n "…"` (valeur sur la ligne suivante). | Sur ces 21 fuites, 2 (YAML bloc, valeur sur la ligne suivante) sont exclues explicitement par docs/…/secret-redaction.md:19-23 : 19 formats restent dans le périmètre.
```

**Impact.** Les jetons de ces formats présents dans le code ou la documentation d'un projet indexé sortent en clair dans les contextes CLI/REST/MCP et vers un Ollama distant éventuel. C'est une limite de couverture assumée (secret-redaction.md:7-10, current-limitations.md:114), pas une régression.

**Action.** Étendre la politique (skill nexus-redaction-change) aux 19 formats, versionner la sonde de 35 cas comme test, bumper POLICY_VERSION et invalider les index par une nouvelle migration. Les scalaires YAML de bloc et les valeurs sur la ligne suivante (exclus par secret-redaction.md:19-23) relèvent d'un changement de politique séparé.

### AUD-SEC-02 — Une seule valeur sensible de plus de 4 096 caractères fait échouer l'indexation complète du projet et toute construction de contexte qui l'inclut

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : S4 · Une seule sortie publique, redaction complétée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #284 (050af1e4) · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — SensitiveContentRedactorTest : 201 lignes ajoutées, aucune supprimée (v9 JWT/XML/options) ; aucun @Disabled. | Test actif : SensitiveContentRedactorTest, ProjectIndexingServiceTest (build Maven)

**Preuve**

```text
core/src/main/java/com/nexus/security/SecretAssignmentScanner.java:126-129 — `if (length > MAX_SECRET_CHARS) throw new IllegalArgumentException("Valeur sensible dépassant la limite de redaction")` ; appelé sans capture dans LuceneSearchIndex.java:255 (toLuceneDocument) depuis les boucles rebuild/applyChanges :101-116, dans SemanticIndexingService.java:202 et ContextBundle.java:35. Test existant : core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java:70-71 confirme l'exception pour 4 097 caractères. Sonde : `password = "x×5000"` → EXC IllegalArgumentException. | secret-redaction.md:26-27 documente le refus du contenu ; l'échec du projet entier n'est documenté nulle part.
```

**Impact.** Fail-closed (pas de fuite), mais un fichier d'un dépôt tiers (ex. `"client_secret": "<blob>"` dans un Markdown, une fixture ou un JSON embarqué dans du JS) suffit à rendre tout le projet non indexable ; en REST l'erreur remonte en 400 « Requête invalide » sans diagnostic exploitable.

**Action.** Remplacer l'exception par une redaction jusqu'à la fin de ligne (ou du contenu) pour la valeur trop longue, comme pour les clés privées tronquées ; à défaut, exclure uniquement le fichier concerné avec un diagnostic public, sans interrompre le lot.

### AUD-SEC-04 — En mode NEXUS_REST_TRUST_LOCAL=true, l'API REST loopback sans token n'a ni contrôle Host/Origin ni allowlist de racines : exposée au DNS rebinding depuis un navigateur

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : S8 · Façade, configuration et durcissements locaux
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Le filtre JAX-RS NexusRestLoopbackTransportFilter (PR #303) refuse en 403 sans détail, en trust-local comme en mode jeton loopback, toute requête dont le Host n'est pas littéralement 127.0.0.1, localhost ou [::1] ou dont l'Origin n'est pas loopback ; le risque navigateur résiduel du trust-local et la recommandation d'allowlist de racines sont documentés. · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

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
- **Statut** : Fermé avec réserve · **Changement** : Fermé par #304 (tâches 2.1 et 2.2) : les secrets PFX ne figurent plus dans l'env du job windows-installer ; ils ne sont mappés que sur les deux étapes de signature, vides sur pull_request, et le PFX est décodé puis effacé dans la même étape sans que son chemin soit publié · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — windows-installer.yml : trigger PR develop retiré (main gardé), concurrence incluant github.workflow ; secrets PFX non réexposés. | Test actif : workflow_hygiene ; contrats release PASS Réserve : Réserve d'origine inchangée.

**Preuve**

```text
.github/workflows/windows-installer.yml:16 `pull_request:` ; :52-56 `env: ... NEXUS_WINDOWS_SIGNING_PFX_BASE64: ${{ secrets.NEXUS_WINDOWS_SIGNING_PFX_BASE64 }}` / `..._PASSWORD` ; :58-62 checkout `ref: ${{ env.NEXUS_HEAD_SHA }}` (head de la PR). | Vu aussi par AUD-DEP-01 : .github/workflows/windows-installer.yml:50-56 : `env:` du job windows-installer → `NEXUS_WINDOWS_SIGNING_PFX_BASE64: ${{ secrets.… }}` / `NEXUS_WINDOWS_SIGNING_PFX_PASSWORD: ${{ secrets.… }}` ; étapes suivantes l.106-131 « Build self-contained Windows distribution », « Build production installer » (
```

**Impact.** GitHub ne transmet pas les secrets aux PR de forks ; le risque se limite aux branches poussées dans le dépôt (collaborateurs, Dependabot n'a pas accès aux secrets Actions). Tout script/test Maven modifié dans une telle PR peut lire et exfiltrer la clé de signature.

**Action.** Déplacer ces deux variables au seul step de signature, conditionné à `github.event_name != 'pull_request'` (ou à un environment protégé avec reviewers).

### AUD-SEC-24 — Le motif JWT de la redaction coûte environ 12 s par Mo sur une entrée forgée « eyJ- » répétée : un fichier de 8 Mo bloque l'indexation

- **Axe** : Sécurité, Performance · **Effort** : S · **Sprint** : S14 · Suites de la vérification du 11 novembre
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #369 · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:207-255 redactJwts saute à la fin de la suite base64url (runEnd) quand le 1er segment ne mène pas à un « . » ou après un échec ; :278-316 CDATA sans regex, pointeur cdataBodyEnd monotone. Sonde java 21 (copie 244ab1d3) 1 Mo / 8 Mo, rendu inchangé : eyJ- 195/1463 ms (510ff8ca : 15 608 ms à 1 Mo) ; eyJ 143/1174 ; eyJa.eyJa. 187/1417 ; eyJ+4000 b64url 164/1259 (sans séparateur 131/1094) ; <![CDATA[ 185/1354 ; <password><![CDATA[ 221/1551 (510ff8ca : 1 581). Différentiel réflexif redactJwts/redactXmlSecretElements vs replaceAll des motifs, 3×10 000 cas : 0 écart. Fuzz 644 unités × 24 opérations à 1 Mo : aucune > 1 s. | Test actif : Oui : SensitiveContentRedactorTest.java:505 (#forgedRepetitionOfJwtStarts…), :515 (#forgedRepetitionOfCdataOpenings…), :539 (#jwtAndCdataRenderingMatchesTheV8Patterns), :555 (#generatedJwtAndCdata…, 4 000 cas) ; aucun @Disabled/@Tag/assume dans la classe (non exécutés ici). | SensitiveContentRedactorTest 116/0 au build 244ab1d3 ; mesure indépendante : 1 Mo de « eyJ- » 195 ms (15,6 s avant), 8 Mo 1,5 s. Réserve : Tests à seuil absolu (assertTimeoutPreemptively 1 s, SensitiveContentRedactorTest.java:490) : marge mesurée ×4,5 à ×5 sur 2 vCPU ; stabilité CI non vérifiée ici (pas de Maven).

**Preuve**

```text
core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:78-81 `\beyJ[A-Za-z0-9_-]{10,4096}+\.` appliqué en :132 : possessif mais une tentative par occurrence de « eyJ » précédée d'un non-mot, chacune balayant jusqu'à 4 096 caractères. Mesure java 21 (2 vCPU, meilleur de 3) sur 1 Mo de « eyJ- » : 13 827 ms (nexus-1109 : 13 923 ms, préexistant) ; « <password><![CDATA[ » répété : 1 341 ms (:73) ; contenu ordinaire 1 Mo : 285 ms. Limite de fichier par défaut 8 Mo (ProjectFileLimits.java:18).
```

**Impact.** Un fichier forgé du dépôt indexé (≤ 8 Mo) immobilise l'indexation ~2 min par passe de redaction (indexation, Lucene, sémantique rejouent redact), et bloque les sorties contexte qui le redigent : déni de service local.

**Action.** Ajouter une garde de début de jeton (?<![A-Za-z0-9_-]) devant eyJ (et [^\]] CDATA borné par position), ou scanner linéaire ; test de performance 1 Mo < 1 s pour eyJ- et CDATA.

### AUD-TST-01 — NexusApiApplicationService, bean CDI exercé par les @QuarkusTest, n'est crédité qu'à 13 lignes sur 95 par l'agent JaCoCo seul

- **Axe** : Tests, Dépendances · **Effort** : S · **Sprint** : S1 · Remettre les mesures d'aplomb
- **Dépend de** : aucun · **Débloque** : `AUD-QUA-08`
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #268 (dfb695ce) · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — rest-quarkus/pom.xml et pom racine : seul version 0.2.0→0.2.1 (et jackson 2.23.0) ; quarkus-jacoco toujours déclaré (:43). | Test actif : jacoco:check rest-quarkus (build Maven, non relancé ici) Réserve : Réserve d'origine inchangée.

**Preuve**

```text
/home/claude/audit-work/run/jacoco-rest.csv:38 « NexusApiApplicationService,355,55,9,1,82,13,…,17,6 » (13/95 lignes hors records ; 17 méthodes sur 23 jamais créditées). adapters/rest-quarkus/src/test/java/com/nexus/api/NexusResourceTest.java:23 « @QuarkusTest » appelle index/search/context (l.58-189). Agrégat records inclus : 21/107 l. | adapters/rest-quarkus/pom.xml:26-40 : aucune dépendance io.quarkus:quarkus-jacoco ; :75-95 jacoco-maven-plugin seul. Documentation Quarkus « tests-with-coverage » (Context7) : avec @QuarkusTest, l'agent seul donne une couverture incomplète, l'extension quarkus-jacoco est requise. | Relecture : NexusResource (30/37 l., jacoco-rest.csv:20) et ApiMapper (85/101, :12), appelés seulement par les @QuarkusTest, sont crédités ; seul le bean @ApplicationScoped ne l'est pas, et le log Maven ne contient aucun avertissement « do not match ». La cause (quarkus-jacoco absent) est probable, pas démontrée.
```

**Impact.** Le gate REST (73,4 % mesurés) sous-estime la classe centrale de l'adaptateur : une régression de NexusApiApplicationService ne fait pas bouger la mesure, et la refonte AUD-QUA-08 n'est pas vérifiable par la couverture.

**Action.** Ajouter quarkus-jacoco (scope test) avec la configuration conjointe documentée (exclClassLoaders=*QuarkusClassLoader, append), comparer la mesure ; à défaut, couvrir la classe par des tests unitaires via son constructeur package-private.

### AUD-TST-02 — ProjectPathGuard (primitive de confinement) sans classe de test dédiée : 3 appels directs dans un seul test, 13/24 branches couvertes (54,2 %)

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : S3 · Filets de test et traces
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #282 (b6b56a07) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

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
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par les PR #343 (merge 159cef5a) et #347 · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — core/pom.xml : planchers com.nexus.security relevés 0.85/0.79→0.87/0.82 (remesure secret-redaction-v9 89,29/84,63 % ; écart ≤3 pts respecté) ; règles CLASS intactes ; adapter-coverage-gates.md aligné. | Test actif : coverage-floors=PASS (contrat doc) Réserve : Réserve d'origine inchangée.

**Preuve**

```text
pom.xml:228 « <element>BUNDLE</element> » (idem core/pom.xml, adapters/*/pom.xml) ; mesures : JdtStdioSession B 13/70 (18,6 %), MinosPayloadReader B 7/18 (38,9 %), SemanticSearchConfiguration B 15/50 (30 %), NexusRestTransportPolicy B 28/54 (51,9 %), ProjectPathGuard B 13/24, GeminiInstructionProvider L 7/27 (/home/claude/audit-work/run/jacoco-*.csv). | Vu aussi par AUD-TST-04 : pom.xml:36-37 « nexus.coverage.line.minimum 0.65 / branch 0.55 » + pom.xml:222-239 execution jacoco-check héritée ; core/pom.xml:85,90 « 0.70 / 0.50 » ; adapters/rest-quarkus/pom.xml:90,95 « 0.60 / 0.60 » ; docs/developer/adapter-coverage-gates.md:51-52 « core | 70 % | 50 % », « REST | 60 % | 60 % »
```

**Impact.** Marge core 83,8 %/67,7 % vs 70 %/55 % : on peut perdre ~1 380 lignes couvertes avant que le gate ne réagisse ; le gate ne protège pas les paquets security/index.jdt/index.minos.

**Action.** Ajouter des règles PACKAGE (com.nexus.security, com.nexus.api, index.jdt/minos/scip) avec des planchers proches du mesuré, ou des règles CLASS ciblées.

### AUD-PRF-01 — Aucun index sur symbol_relations(file_id) : chaque fichier réindexé ou supprimé déclenche un SCAN complet de la table (tous projets)

- **Axe** : Performance · **Effort** : S · **Sprint** : S5 · Indexation et écriture SQLite
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : La migration V012 crée idx_symbol_relations_file_provider (file_id, source_provider), qui sert la suppression par fichier et la sonde de cascade, et le benchmark d'échelle réindexe des fichiers existants par applyChanges (PR #297, sprint 5, tâches 1.1 à 1.3 et 6.2) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteFileWriter.java:100-101 : `DELETE FROM symbol_relations WHERE file_id = ? AND source_provider = ?` exécuté pour chaque update (boucle :28-31) ; SqliteFileWriter.java:47 `DELETE FROM indexed_files ...` → cascade FK V001__initial_schema.sql:76 `FOREIGN KEY (file_id) REFERENCES indexed_files(id) ON DELETE CASCADE`. Les 6 index de symbol_relations (V001:79-83, V002:10-14, V003:7-17) commencent tous par project_id. Commande : migrations V001–V010 appliquées dans SQLite 3.45.1 puis `EXPLAIN QUERY PLAN DELETE FROM symbol_relations WHERE file_id=1 AND source_provider='x'` → `SCAN symbol_relations` (idem pour la sonde de cascade `SELECT 1 FROM symbol_relations WHERE file_id=1`). | Relecture : les triggers V008:110-116 ajoutent une ligne en attente par relation supprimée ; aucune mesure ne quantifie le coût et aucun budget n'est dépassé (d'où Moyenne).
```

**Impact.** Coût O(fichiers modifiés × relations de toute la base) dans la transaction d'indexation : un rebuild complet (fullRebuild ⇒ tous les fichiers passent par replaceAnalysis) ou une suppression massive devient quadratique (déduit du plan de requête, non mesuré) et s'aggrave avec le nombre de projets fédérés partageant la base. Non couvert par les benchmarks : ScaleRegressionBenchmarkTest.java:404-432 peuple par INSERT directs, sans passer par applyChanges sur données existantes.

**Action.** Migration une nouvelle migration `CREATE INDEX idx_symbol_relations_file_provider ON symbol_relations(file_id, source_provider)` (couvre aussi la cascade FK), test EXPLAIN QUERY PLAN dans SqliteGraphQueryPlanTest, et scénario de benchmark « réindexation incrémentale / rebuild sur base peuplée ».

### AUD-PRF-02 — Le vecteur de la requête est recalculé par appel HTTP Ollama pour chaque projet de la fédération (jusqu'à 100 fois la même requête)

- **Axe** : Performance · **Effort** : S · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : `AUD-DEP-19` · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Un SearchQueryContext unique par requête fédérée (recherche et contexte) mémorise le vecteur de la requête redactée, clé (provider, modèle) : le fournisseur d'embeddings reçoit au plus un appel quel que soit le nombre de projets, et une indisponibilité est elle aussi mémorisée et partagée (sprint 6, tâches 2.1 et 2.2, PR #299) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

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
- **Statut** : Fermé · **Changement** : Les métadonnées de commit Lucene (compatibilité sémantique et sonde de readiness lexicale) sont lues sur le reader en cache, avec repli SegmentInfos.readLatestCommit sans DirectoryReader quand aucun reader n'est en cache (sprint 6, tâches 4.1 et 4.2, PR #299) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/search/semantic/lucene/PersistentLuceneSemanticSearchIndex.java:49-51 `return operationScoped.isCompatible(projectId, provenance);` → LuceneSemanticSearchIndex.java:92-97 `FSDirectory.open(...)` puis `DirectoryReader.open(directory)` pour lire `getIndexCommit().getUserData()` ; appelé à chaque search par SemanticSearchStrategy.java:114. | Vu aussi par AUD-PRF-05 : core/src/main/java/com/nexus/application/NexusApplication.java:336-341 `.allMatch(project -> { ... searchIndex.isPresent(project.id()) ...` ; PersistentLuceneSearchIndex.java:70-72 délègue à l'opération non cachée LuceneSearchIndex.java:150-157 (`DirectoryReader.open` + userData) ; appelée par adapt
```

**Impact.** Ouverture/fermeture de tous les segments (dont fichiers HNSW) à chaque requête et par projet fédéré, alors que le searcher chaud existe déjà : annule une partie du gain du cache persistant.

**Action.** Lire les userData via le searcher mis en cache (IndexCommit du reader acquis) ou `SegmentInfos.readLatestCommit`, et/ou mémoïser le résultat par (projectId, génération SQLite, génération Lucene).

### AUD-PRF-07 — GitRecencyCandidateEnricher relit 50 commits et 50 diffs d'arbres à chaque recherche et pour chaque projet, sans cache

- **Axe** : Performance · **Effort** : S · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : GitRecencyCandidateEnricher mémorise par (répertoire Git, HEAD) les chemins modifiés des 50 derniers commits dans les bornes existantes, invalide l'entrée au changement de HEAD et borne le cache à MAX_PROJECTS entrées LRU, décision portée par BE-0070 (sprint 6, tâche 6.1, PR #299) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/context/source/git/GitRecencyCandidateEnricher.java:58-88 : `openRepository(...)`, `git.log().setMaxCount(MAX_COMMITS).call()` (MAX_COMMITS=50, :35) et `diffFormatter.scan(parent.getTree(), commit.getTree())` par commit ; branché dans SearchService par NexusApplicationFactory.java:122-126.
```

**Impact.** Coût JGit (ouverture repo, parse de 50 commits, 50 diffs filtrés) payé par projet et par requête, y compris en fédération et pour le contexte (DefaultContextBuilder passe par searchService). Signal pourtant stable tant que HEAD ne change pas.

**Action.** Cache borné par (repository, HEAD) des chemins modifiés des 50 derniers commits (bornes MAX_CUMULATIVE_CHANGED_PATHS=4096 déjà existantes), appliqué ensuite au filtre de candidats.

### AUD-PRF-27 — La publication du staging pagine index_staged_files sans index (project_id, id) : coût quadratique, 5,6 s à 100 000 fichiers sous verrou d'écriture

- **Axe** : Performance · **Effort** : S · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #344 (merge 59ea02fa) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
SqliteFileWriter.java:56-63 `SELECT … FROM index_staged_files WHERE project_id = ? AND id > ? ORDER BY id LIMIT ?` (LIMIT = SqliteBatchInsert.ROW_LIMIT = 128), relancée en boucle :96-109 dans la transaction unique SqliteIndexWriteSession.java:55-58. V014__index_staged_analyses.sql:10-22 : seul index = UNIQUE(project_id, relative_path). EXPLAIN QUERY PLAN (SQLite 3.45.1, sans ANALYZE, et la production n'exécute jamais ANALYZE : grep persistence/sqlite) → `SEARCH index_staged_files USING COVERING INDEX sqlite_autoindex_index_staged_files_1 (project_id=?)` + `USE TEMP B-TREE FOR ORDER BY`. Chaque page relit donc toutes les lignes du projet et les trie. Mesure de la pagination seule, en mémoire : 10 000 fichiers 0,07 s ; 40 000 fichiers 0,76 s ; 100 000 fichiers 5,6 s (×2,5 fichiers → ×7,4 temps, croissance O(N²)). Après ANALYZE, le plan passe à `INTEGER PRIMARY KEY (rowid>?)`. Plan non vérifié sur sqlite-jdbc 3.54.1.0.
```

**Impact.** Au plafond par défaut (100 000 fichiers), la transaction de publication tient le verrou d'écriture SQLite plusieurs secondes de plus (sur disque, sans compter les copies). C'est de l'ordre du busy_timeout de 5 s × 2 tentatives (SqliteWriteRetryPolicy) : les autres écrivains, dont l'indexation d'un autre projet, peuvent échouer en SQLITE_BUSY. Au plafond dur (1 000 000), on extrapole à quelques centaines de secondes. Cela contredit l'objectif de DATA-0062 de « ne jamais tenir le verrou pendant toute l'indexation ». Aucun benchmark ne l'observe : le scénario de réindexation et singleWriteTransactionMs mesurent applyChanges.

**Action.** Migration V015 `CREATE INDEX idx_index_staged_files_project ON index_staged_files(project_id)` : le rowid est implicitement suffixé, d'où un plan `(project_id=? AND rowid>?)` ordonné sans tri. Ou paginer par rowid seul. Ajouter le plan de STAGED_FILES_PAGE_SQL à SqliteGraphQueryPlanTest (absence de TEMP B-TREE), et un scénario de benchmark qui passe par openWriteSession/stage/publish sur un corpus ≥ 50 000 fichiers.

### AUD-PRF-29 — La migration V015 (préfiltre approché scopé par fichier) ralentit la recherche de symboles d'un gros projet seul dans sa base : 0,10 → 0,36 s à 100 000 fichiers

- **Axe** : Performance · **Effort** : S · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #344 (merge 59ea02fa) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
V015__index_symbols_by_file_for_fuzzy_search.sql:12-15 ; SqliteSymbolSearch.java:155-166 et :185-194 : le plan devient `SEARCH f (project_id=?)` puis un sondage d'idx_symbols_file_fuzzy_prefilter PAR FICHIER, au lieu d'un intervalle sur l'index (premier caractère, longueur). Mesure (SQLite 3.45.1, en mémoire, sans ANALYZE, 1 projet, 5 symboles par fichier, 9 termes) : comptage plafonné 50 000 fichiers V014 0,051 s → V015 0,117 s ; 100 000 fichiers comptage 0,097 → 0,256 s, récupération 0,099 → 0,358 s (×2,6 à ×3,6).
```

**Impact.** Le cas mono-projet est l'usage principal. La recherche de symboles y coûte désormais de l'ordre du nombre de fichiers du projet par terme, alors qu'avant elle coûtait de l'ordre du nombre de candidats. On gagne en fédération et on perd au plafond de 100 000 fichiers. Aucun benchmark mono-projet ne compare avant et après V015.

**Action.** Mesurer sur sqlite-jdbc 3.54 un projet seul à 100 000 fichiers. Si la régression se confirme, ajouter un index (file_id réordonné) ou choisir le parcours selon la part du projet dans la base. Sinon, ajouter un scénario mono-projet au benchmark d'échelle.

### AUD-DEP-02 — Le workflow Release n'a jamais été exécuté : 0 run et 0 release/tag publiés

- **Axe** : Dépendances · **Effort** : M · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : `AUD-DEP-03`, `AUD-DEP-09`, `AUD-DEP-21`, `AUD-QUA-12`, `AUD-SEC-06` · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Fermé par #304 (tags de pré-version vX.Y.Z-rc.N, release marquée pré-version sans déplacer latest, ADR de politique de publication) puis par la répétition 0.2.0-rc.2 : version passée par #315 (0018cf3e), promotion vers main par #318 (38e1b95b), tag v0.2.0-rc.2 publié par un run Release réussi après approbation de l'environnement release, relevé consigné par #319 (de2a066a) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
WebFetch https://github.com/FTurleque/nexus-context-engine/actions/workflows/release.yml → « This workflow has no runs yet » ; https://github.com/FTurleque/nexus-context-engine/releases → « There aren't any releases here ». Job publish : .github/workflows/release.yml:150-400 (push GHCR, attest, assets, latest).
```

**Impact.** La chaîne de publication (push GHCR immuable, attestations, upload d'assets, exigence Authenticode, présence des secrets PFX) n'a aucune preuve d'exécution réelle ; la première release servira de test en production. release-assets-qualification.yml (12 runs) ne couvre qu'une partie.

**Action.** Exécuter une répétition complète sur un tag de pré-release (ex. v0.2.0-rc dans un dépôt/namespace GHCR de test) ou ajouter un mode dry-run du job publish ; consigner la preuve.

### AUD-DEP-30 — Alertes Dependabot, mises à jour de sécurité et secret scanning désactivés sur le dépôt public

- **Axe** : Dépendances, Sécurité · **Effort** : S · **Sprint** : S1 · Remettre les mesures d'aplomb
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Procédure de traitement des alertes dans SECURITY.md (PR #276) ; réglages de sécurité activés par le mainteneur · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
gh api repos/FTurleque/nexus-context-engine/dependabot/alerts → 403 « Dependabot alerts are disabled for this repository » ; gh api repos/…/vulnerability-alerts → 404 « Vulnerability alerts are disabled » ; security_and_analysis : dependabot_security_updates, secret_scanning, secret_scanning_push_protection = disabled (17/10/2026).
```

**Impact.** Une CVE publiée sur une dépendance déjà fusionnée n'est signalée qu'au prochain run OSV d'une PR ; un secret poussé par erreur n'est ni bloqué ni signalé. Les gates OSV requis (AUD-DEP-23) ne couvrent que le code en cours de revue, pas le stock.

**Action.** Activer Dependabot alerts et security updates, secret scanning et push protection (gratuits pour un dépôt public) ; documenter dans SECURITY.md (AUD-DEP-17) le traitement des alertes.

## Faible (86)

### AUD-ARC-04 — Cycles context ↔ context.source / .git / .skill : les ports de sources dépendent du modèle de contexte qui dépend d'eux

- **Axe** : Architecture · **Effort** : S · **Sprint** : S7 · Casser le bloc cyclique du cœur
- **Dépend de** : `AUD-ARC-07` · **Débloque** : `AUD-ARC-01`
- **Statut** : Fermé · **Changement** : ContextFragment, ContextItem et ContextSelectionResult passent dans le package context.model, dont dépendent à la fois context et context.source.* (PR #300) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/context/source/ContextSourceFragmentFactory.java:3 `import com.nexus.context.ContextFragment;` ; core/src/main/java/com/nexus/context/source/git/LocalGitContextSourceProvider.java:5 idem ; core/src/main/java/com/nexus/context/source/skill/SkillContextSelector.java:5-6 `import com.nexus.context.ContextItem;` ; core/src/main/java/com/nexus/context/DefaultContextBuilder.java:4-7 `import com.nexus.context.source.*`
```

**Impact.** Les providers de sources (JGit, YAML, fichiers) ne sont pas isolables du builder ; coût de maintenance modéré, cycle localisé à un type (ContextFragment) et deux types (ContextItem, ContextSelectionResult).

**Action.** Déplacer ContextFragment/ContextItem/ContextSelectionResult dans un package modèle (ex. context.model) dont dépendent à la fois context et context.source.*.

### AUD-ARC-05 — Cycle search ↔ ranking : le ranker dépend des candidats de recherche, le service de recherche dépend du ranker

- **Axe** : Architecture · **Effort** : S · **Sprint** : S7 · Casser le bloc cyclique du cœur
- **Dépend de** : `AUD-ARC-07` · **Débloque** : `AUD-ARC-01`
- **Statut** : Fermé · **Changement** : SearchCandidate, SearchSignals, CandidateType, CandidateEnricher et CandidateMerger passent dans le package search.candidate, et ranking comme ranking.graph ne dépendent plus de search (PR #300) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/ranking/DeterministicContextRanker.java:3-5 `import com.nexus.search.CandidateType;` `SearchCandidate;` `SearchSignals;` ; core/src/main/java/com/nexus/search/SearchService.java:4-7 `import com.nexus.ranking.ContextRanker;` … `import com.nexus.ranking.graph.GraphCandidateEnricher;`
```

**Impact.** ranking (règle déterministe/explicable, ADR-0010/0025) n'est pas testable/évolutive indépendamment de search ; ranking.graph ↔ search ajoute un second cycle (6/1 imports).

**Action.** Sortir SearchCandidate/SearchSignals/CandidateType dans un package modèle de candidats (ou dans ranking) et faire dépendre search de ranking uniquement.

### AUD-ARC-06 — Le package de ports sémantiques instancie l'implémentation Ollama (cycle search.semantic ↔ search.semantic.ollama)

- **Axe** : Architecture · **Effort** : S · **Sprint** : S7 · Casser le bloc cyclique du cœur
- **Dépend de** : `AUD-ARC-07` · **Débloque** : `AUD-ARC-01`
- **Statut** : Fermé · **Changement** : La lecture d'environnement et l'instanciation d'OllamaEmbeddingProvider quittent SemanticSearchConfiguration pour NexusApplicationFactory, composition root seule autorisée à lire l'environnement (PR #300) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/search/semantic/SemanticSearchConfiguration.java:3-4 `import com.nexus.search.semantic.ollama.OllamaEmbeddingProvider;` `OllamaEndpointResolver;` ; :109 `OllamaEmbeddingProvider ollama = new OllamaEmbeddingProvider(` ; core/src/main/java/com/nexus/search/semantic/ollama/OllamaEmbeddingProvider.java:5 `import com.nexus.search.semantic.EmbeddingProvider;`
```

**Impact.** Le port EmbeddingProvider n'est pas réellement découplé de son unique adaptateur : ajouter un autre fournisseur d'embeddings impose de modifier la configuration du port (contraire à l'indépendance fournisseurs LLM d'AGENTS.md).

**Action.** Déplacer la lecture d'environnement et la construction Ollama (`fromEnvironment`) dans le composition root (application) ou dans le package ollama.

### AUD-ARC-09 — Séquence de lecture cohérente (readiness → verrou partagé → session → readiness) dupliquée dans six méthodes de la façade

- **Axe** : Architecture · **Effort** : S · **Sprint** : S8 · Façade, configuration et durcissements locaux
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Les gabarits readProject et readProjects de NexusApplication (PR #303) portent seuls la séquence READY → verrou partagé → session → revalidation READY, et les sept lectures indexées, inspect compris, passent par eux. · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/application/NexusApplication.java:209-214 (search), :229-235 (searchAcrossProjects), :252-263 (context), :280-291 (contextAcrossProjects), :301-306 (findSymbols), :315-321 (findUsages) — ex. `requireReadyProject(projectId); ProjectIndexLockManager.LockHandle readLock = projectIndexLockManager.acquireRead(projectId); try (var reads = indexRepository.openReadSession()) {`
```

**Impact.** L'invariant 4/11 de docs/architecture.md (READY + verrou partagé + revalidation) est garanti par copier-coller ; une nouvelle opération de lecture peut l'oublier ou l'appliquer partiellement (inspect, l.195-202, ne revalide pas READY).

**Action.** Extraire un template `withConsistentRead(scope, Function<...>)` (mono et fédéré) utilisé par toutes les lectures indexées.

### AUD-ARC-10 — Écriture MINOS orchestrée par la façade et la CLI, hors ProjectIndexingService

- **Axe** : Architecture · **Effort** : S · **Sprint** : S5 · Indexation et écriture SQLite
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : ProjectIndexingService.importExternalCodeIntelligence porte l'import MINOS sous le même verrou single-flight puis inter-processus que l'indexation, MinosCodeIndexImporter est composé par NexusApplicationFactory et la CLI lit le payload borné par la façade (PR #297, sprint 5, tâche 7.1) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/application/NexusApplication.java:185-190 `projectIndexLockManager.acquire(projectId)` … `new MinosCodeIndexImporter().importPayload(...)` … `indexRepository.replaceExternalCodeIntelligence(projectId, snapshot);` ; core/src/main/java/com/nexus/cli/NexusCli.java:150 `payload = MinosCodeIndexImporter.readPayload(input);`
```

**Impact.** Deux chemins d'écriture d'intelligence de code coexistent (ExternalCodeIntelligenceRefresher et la façade) avec des règles de verrouillage différentes (pas de single-flight LockSlot, ProjectIndexingService.java:206-210) ; l'importer n'est pas composé dans NexusApplicationFactory et la CLI contourne la façade pour lire le payload.

**Action.** Déplacer importMinos dans ProjectIndexingService (même verrouillage/diagnostics), composer MinosCodeIndexImporter dans la factory et exposer la lecture bornée du payload via la façade.

### AUD-ARC-11 — SQLException (technologie de persistance) fait partie du contrat public de la façade et des points d'entrée

- **Axe** : Architecture · **Effort** : S · **Sprint** : S8 · Façade, configuration et durcissements locaux
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : NexusApplicationFactory enveloppe l'échec d'ouverture SQLite dans PersistenceException, cause conservée (PR #303), si bien que la façade et les trois adaptateurs n'exposent ni n'importent plus java.sql. · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/application/NexusApplication.java:95 `public static NexusApplication create(NexusPaths paths) throws SQLException, IOException {` ; adapters/rest-quarkus/src/main/java/com/nexus/api/NexusApiApplicationService.java:192 `} catch (SQLException | IOException exception) {` ; adapters/mcp-java/src/main/java/com/nexus/mcp/NexusMcpServer.java:32 ; core/src/main/java/com/nexus/cli/NexusCli.java:68
```

**Impact.** Les adaptateurs connaissent JDBC alors que SQLite est censé être un détail derrière les ports ; un changement de stockage toucherait les trois adaptateurs.

**Action.** Envelopper les échecs d'ouverture dans PersistenceException (déjà existante, core/src/main/java/com/nexus/persistence/PersistenceException.java:3) ou une exception applicative.

### AUD-ARC-12 — Configuration lue directement dans l'environnement par 12 classes (16 appels à System.getenv) du cœur, hors composition root

- **Axe** : Architecture · **Effort** : M · **Sprint** : S8 · Façade, configuration et durcissements locaux
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Le record NexusConfiguration, résolu une fois par NexusApplicationFactory depuis une Map et injecté dans les classes qui lisaient l'environnement (PR #303), remplace les 16 appels à System.getenv du cœur ; une règle du test d'architecture interdit tout nouvel appel hors composition root (ARCH-0086). · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
Commande : `grep -rn 'System.getenv' core/src/main` → 16 occurrences dans 12 fichiers (ex. core/src/main/java/com/nexus/index/ProjectIndexingService.java:404, core/src/main/java/com/nexus/index/jdt/JdtLanguageServerCodeIntelligenceProvider.java:89/197/202/206, core/src/main/java/com/nexus/context/ContextMaterializationLimits.java:90/97, core/src/main/java/com/nexus/security/ProjectFileLimits.java:14) ; 30 noms `NEXUS_*` distincts.
```

**Impact.** Pas de vue unique de la configuration effective ; services de domaine non purs (ProjectIndexingService lit son timeout lui-même) ; tests obligés de passer par des constructeurs package-private.

**Action.** Introduire un objet de configuration résolu une fois par NexusApplicationFactory (le pattern `from(Map<String,String>)` existe déjà dans ProjectScanLimits/ContextDiscoveryLimits) et l'injecter.

### AUD-ARC-13 — ProjectIndexingService expose cinq constructeurs publics télescopiques dont deux retombent silencieusement sur un verrou process-local

- **Axe** : Architecture · **Effort** : S · **Sprint** : S5 · Indexation et écriture SQLite
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : ProjectIndexingService n'a plus qu'un constructeur public complet, sans valeur par défaut du ProjectIndexLockManager ; les variantes de commodité vivent dans la fabrique de test ProjectIndexingServices (PR #297, sprint 5, tâche 8.1) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/index/ProjectIndexingService.java:45,54,64,76,94 `public ProjectIndexingService(` ; :86-87 `providerTimeoutFromEnvironment(), ProjectIndexLockManager.processLocalOnly());` ; seul usage main : core/src/main/java/com/nexus/application/NexusApplicationFactory.java:107 ; 18 fichiers de test les utilisent.
```

**Impact.** Une composition alternative utilisant un constructeur public perdrait le verrou inter-processus (invariant 11) sans erreur ; API de construction difficile à faire évoluer.

**Action.** Réduire à un constructeur public complet (ou un builder) et déplacer les variantes de commodité dans un support de test.

### AUD-ARC-14 — Responsabilités mélangées dans config.NexusPaths (résolution des chemins + durcissement POSIX/ACL du stockage) et code de test dans le jar (search.evaluation)

- **Axe** : Architecture · **Effort** : S · **Sprint** : S8 · Façade, configuration et durcissements locaux
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Le durcissement POSIX/ACL du stockage quitte NexusPaths pour security.PrivateStoragePolicy et SearchQualityMetrics passe sous core/src/test (PR #303). · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

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
- **Statut** : Fermé · **Changement** : La classe JDT ProcessBuilder est renommée BoundedJdtProcessBuilder et ses usages la nomment explicitement (PR #303). · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/index/jdt/ProcessBuilder.java:15-18 « Cette classe package-private porte volontairement le nom simple {@code ProcessBuilder} : le provider JDT du même package l'utilise sans nom qualifié » ; :28 `final class ProcessBuilder {` | Vu aussi par AUD-QUA-14 : core/src/main/java/com/nexus/index/jdt/ProcessBuilder.java:28 `final class ProcessBuilder {` ; javadoc :16-19 « porte volontairement le nom simple ProcessBuilder » ; usage core/src/main/java/com/nexus/index/jdt/JdtProcessLauncher.java:33 `ProcessBuilder builder = new ProcessBuilder(command);`.
```

**Impact.** Pattern surprenant : un relecteur peut croire que JdtLanguageServerCodeIntelligenceProvider utilise le ProcessBuilder JDK (frontière d'exécution sensible, invariant 9) ; risque de régression lors d'un refactoring/import automatique.

**Action.** Renommer en BoundedJdtProcessBuilder (ou équivalent) et référencer explicitement.

### AUD-ARC-20 — Le test d'architecture ne lit que les imports : 39 références pleinement qualifiées entre packages échappent au contrôle des cycles

- **Axe** : Architecture, Tests · **Effort** : S · **Sprint** : S7 · Casser le bloc cyclique du cœur
- **Dépend de** : aucun · **Débloque** : `AUD-ARC-01`
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #342 (merge 0d64e56b) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/test/java/com/nexus/architecture/JavaSourceGraph.java:101-117 packageGraph ne parcourt que file.imports() ; CoreArchitectureRules.java:29 WATCHED_QUALIFIED_PREFIXES ne contient que frameworks/Lucene/SQLite, donc projectLeaf (:59-62) ne voit pas non plus `com.nexus.x.Y` sans import. Le style est courant dans le cœur : 39 références FQN inter-packages (ex. context/ContextFragmentFactory.java:143-176 → security, exposure/PublicOutputProjection.java:164,187,203, search/SearchService.java:48 → project). Aujourd'hui elles n'ajoutent aucune arête de cycle (graphe imports+FQN = baseline, vérifié), mais une nouvelle dépendance écrite ainsi contourne le filet. | 09/11 : le style se propage dans le nouveau code (FederatedSearchService.java:73, DefaultContextBuilder.java:188) ; l'arête cli→security (CliRenderer.java:257) n'existe que par une référence qualifiée et échappe au test.
```

**Impact.** Le garde-fou du sprint 7 (casser les cycles « sous la protection du test ») peut laisser revenir un cycle ou une remontée depuis project sans échec.

**Action.** Ajouter le préfixe racine analysé (com.nexus.) aux références qualifiées et les injecter dans packageGraph et projectLeaf ; ajouter un cas négatif « cycle par nom pleinement qualifié ».

### AUD-QUA-02 — Le graphe dérivé est documenté comme cache par génération alors que le code ne le matérialise plus ; ProjectGraph est du code mort

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S11 · Finitions documentaires et micro-optimisations
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Branche claude/s11-graphe-01c9d0 (90408ac9) : ProjectGraph supprimé, ProjectGraphBuilder renommé GraphNeighborhoodProjection, section graphe de docs/developer/search-ranking.md réécrite et tenue par le contrat documentaire, décision consignée dans BE-0087 (Proposed, complète BE-0071) · **Vérification du 2026-11-12** (`244ab1d3`) : Tient (fichier cité modifié, correction intacte) — Aucun fichier cité modifié ; ProjectGraph absent, contrat « ProjectGraph cache » PASS. | Test actif : test-operational-doc-contracts.sh

**Preuve**

```text
docs/developer/search-ranking.md:79-86 « ProjectGraphBuilder construit le graphe… SQLite canonical generation N → ProjectGraph cache generation N. Tant que la génération ne change pas, le graphe est réutilisé » ; core/src/main/java/com/nexus/ranking/graph/ProjectGraphBuilder.java:14-15 « Le graphe projet complet n'est plus matérialisé ni mis en cache » ; core/src/main/java/com/nexus/ranking/graph/ProjectGraph.java:8 `public record ProjectGraph(...)` : 0 référence dans tout le dépôt Java (main + test).
```

**Impact.** Documentation courante (censée suivre l'exact head) qui décrit un mécanisme supprimé ; classe orpheline de 31 lignes et nom `ProjectGraphBuilder` trompeur (il ne construit plus de graphe).

**Action.** Réécrire la section « Graphe dérivé par génération » (projection bornée findGraphNeighbors, MAX_NEIGHBOR_EDGES), supprimer ProjectGraph, envisager de renommer ProjectGraphBuilder en GraphNeighborhoodProjection.

### AUD-QUA-03 — AGENTS.md prescrit `mvn clean install` alors que les hooks du dépôt refusent `mvn` hors wrapper

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S11 · Finitions documentaires et micro-optimisations
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : AGENTS.md prescrit ./mvnw clean install (.\mvnw.cmd sous Windows) ; le contrat refuse toute commande mvn hors wrapper dans AGENTS.md (sprint 11, tâche 3.1) · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — guard-command.ps1:49 règle mvn(\.cmd)?\s hors wrapper intacte ; CLAUDE.md : seul le flux main ; push direct refusé sur main seule. | Test actif : Test-Hooks.ps1 (ci.yml:40)

**Preuve**

```text
AGENTS.md:11 « exécuter `mvn clean install` puis `scripts/self-smoke.ps1` » ; .claude/hooks/guard-command.ps1:49-51 `Pattern = $start + 'mvn(\.cmd)?\s'` / « Utiliser le wrapper vérifié (./mvnw…) » ; README.md:164-170 et .claude/CLAUDE.md utilisent `mvnw`.
```

**Impact.** Instruction primaire des agents (indexée par NEXUS lui-même) contradictoire avec l'outillage : un agent qui suit AGENTS.md est bloqué par le hook, et le wrapper vérifié (ancre SHA-512) est contourné hors Claude Code.

**Action.** Remplacer par `./mvnw clean install` (ou `.\mvnw.cmd`) dans AGENTS.md ; ajouter le fait au contrat test-operational-doc-contracts.sh.

### AUD-QUA-04 — Quatre scripts validate-iteration-11 à 14 ne sont référencés nulle part et deux pointent vers des JAR 0.1.0-SNAPSHOT inexistants

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : aucun · **Débloque** : `AUD-QUA-05`
- **Statut** : Fermé · **Changement** : Corrigé par la PR #281 (85c08184) · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — pom.xml : seulement version 0.2.1, outputTimestamp, jackson ; aucun validate-iteration-1x actif hors archive. | Test actif : n/a (structure)

**Preuve**

```text
Comptage `grep -rl <nom>` hors du fichier lui-même : validate-iteration-11/12/13/14.ps1 refs=0, ci=0. scripts/validate-iteration-12.ps1:10 et scripts/validate-iteration-13.ps1:10-11 `nexus-mcp-java-0.1.0-SNAPSHOT-runner.jar` alors que pom.xml:9 `<version>0.2.0</version>`. Au total 23 scripts racine `scripts/*.ps1|py` dont seulement 3 appelés par la CI (validate-phase-6, measure-scale-regression, verify-scale-budgets).
```

**Impact.** Scripts cassés et non maintenus qui ressemblent à des points d'entrée de validation ; bruit pour les contributeurs et pour l'indexation NEXUS du dépôt.

**Action.** Supprimer ou déplacer dans scripts/archive/ les validate-iteration-9 à 14 et 16/17 non référencés par la doc courante ; documenter que validate-phase-6 + self-smoke sont les seuls gates.

### AUD-QUA-07 — La récence Git avale toute exception sans diagnostic ni log

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S3 · Filets de test et traces
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #282 (b6b56a07) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/context/source/git/GitRecencyCandidateEnricher.java:99-102 `} catch (Exception exception) { // … reste fail-open … return candidates; }` ; `grep -c Logger` sur le fichier → 0. Par contraste LocalGitContextSourceProvider.java:156-158 ajoute `diagnostics.add("contexte Git indisponible : " …)`.
```

**Impact.** Fail-open voulu, mais un incident JGit (dépôt corrompu, limite) dégrade le ranking sans aucune trace, contraire à la règle « jamais un abandon silencieux » (.claude/rules/java-core.md).

**Action.** Journaliser via System.Logger (niveau DEBUG/WARNING) et/ou ajouter une raison explicable au résultat lorsque explain est demandé.

### AUD-QUA-08 — Les cinq records d'opération de NexusApplication sont recopiés à l'identique dans le service REST

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S4 · Une seule sortie publique, redaction complétée
- **Dépend de** : `AUD-TST-01` · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #284 (050af1e4) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
Détection par fenêtres de 8 lignes normalisées : 27 fenêtres communes entre adapters/rest-quarkus/src/main/java/com/nexus/api/NexusApiApplicationService.java:226-272 et core/src/main/java/com/nexus/application/NexusApplication.java:474-520 (IndexOperation, SearchOperation, FederatedSearchOperation, ContextOperation, FederatedContextOperation) ; recopie champ à champ, ex. NexusApiApplicationService.java:85-87 `return new SearchOperation(operation.project(), operation.query(), …)`.
```

**Impact.** ~47 lignes dupliquées plus le code de recopie ; un champ ajouté côté cœur peut être oublié côté REST sans erreur de compilation.

**Action.** Retourner directement les records NexusApplication.* depuis NexusApiApplicationService (seules les métriques y sont ajoutées).

### AUD-QUA-11 — Environ un tiers des messages d'exception sont en anglais malgré la convention française

- **Axe** : Qualité · **Effort** : M 1-3j · **Sprint** : S11 · Finitions documentaires et micro-optimisations
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Frontière de langue écrite dans .claude/rules/java-core.md ; messages atteignables par CLI, REST, MCP ou configuration traduits (requêtes, budgets, scope fédéré, limites, provider Ollama, chemins, imports MINOS et SCIP) ; message des contraintes partagé par ContextRequest.UNSUPPORTED_CONSTRAINTS_MESSAGE et le mapper REST (sprint 11, tâches 5.1 à 5.3) ; derniers messages traduits : RepositoryPath (« Chemin repository invalide ») et BudgetedContextSelector (texte de ContextBudgetPolicy), PR de clôture des sprints 10 et 11 · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
Heuristique sur 316 messages littéraux `new (IllegalArgumentException|IllegalStateException|IOException|UncheckedIOException)("…` : 109 contiennent des marqueurs anglais (must/cannot/invalid…). Ex. core/src/main/java/com/nexus/context/FederatedContextService.java:57 `"query must not be blank"` ; core/src/main/java/com/nexus/search/semantic/ollama/OllamaEmbeddingProvider.java:154 `"dimensions must be between 1 and 1024"` ; à côté, ProjectIgnoreMatcher.java:136 `"Le chemin n'appartient pas au projet : "`.
```

**Impact.** Mélange de langues dans les erreurs remontées via CLI/MCP (NexusMcpTools.java:236 renvoie safeMessage(exception)) ; la règle .claude/rules/java-core.md exige le français pour les messages utilisateur. Une partie est des préconditions internes, d'où Faible.

**Action.** Traduire les messages atteignables depuis une entrée utilisateur/configuration (Ollama, requêtes, budgets) ; garder l'anglais pour les invariants internes et le documenter dans la règle.

### AUD-QUA-12 — Version 0.2.0 codée en dur dans la CI et le gate Windows

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : aucun · **Débloque** : `AUD-DEP-02`
- **Statut** : Fermé avec réserve · **Changement** : Fermé par #304 (tâches 8.1 et 8.2) : ci.yml et scripts/validate-phase-6.ps1 lisent la version produit dans pom.xml et la propagent en variable ; le contrat final refuse une version littérale dans ces deux fichiers · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — Aucun littéral 0.2.x dans ci.yml ni validate-phase-6.ps1 ; montée 0.2.1 sans toucher la CI. | Test actif : test-operational-doc-contracts.sh PASS Réserve : Réserve d'origine inchangée ; compose template et docs user portent 0.2.1 en dur (hors périmètre du contrat).

**Preuve**

```text
.github/workflows/ci.yml:91 `ZIP="target/distribution/nexus-context-engine-0.2.0.zip"`, :93, :133, :182 ; scripts/validate-phase-6.ps1:164-166, :188-189, :198, :268 `if ($version.version -ne "0.2.0")`.
```

**Impact.** Chaque bump de version impose ≥ 11 modifications manuelles hors pom.xml ; oubli = gate rouge ou, pire, contrôle d'une mauvaise version.

**Action.** Lire la version depuis pom.xml (`./mvnw help:evaluate` ou `[xml]` comme déjà fait à validate-phase-6.ps1:138) et la propager en variable.

### AUD-QUA-13 — NXA-20260914-05 partiellement ouvert : le watch item FTS/trigram reste présenté comme futur

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : S11 · Finitions documentaires et micro-optimisations
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Watch item de current-limitations.md borné à l'évolution au-delà du FTS5 trigram livré par V008 ; refus de l'ancienne formulation dans test-operational-doc-contracts.sh (sprint 11, tâche 2.1) · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — current-limitations.md : retrait de « develop » et content-v8 ; watch item FTS5 V008 inchangé. | Test actif : test-operational-doc-contracts.sh

**Preuve**

```text
docs/developer/current-limitations.md:148 « nouveau moteur FTS/trigram pour les recherches substring ; » alors que core/src/main/resources/db/migration/V008__indexed_substring_search.sql existe et que docs/developer/search-ranking.md:72 décrit `relation_search_fts` « en FTS5 trigram ». Volet Quarkus corrigé : docs/developer/README.md:18 `Quarkus 3.40.1` = pom.xml:52.
```

**Impact.** Priorisation trompeuse d'une fonctionnalité livrée ; le constat d'audit précédent n'est pas clos.

**Action.** Reformuler le watch item (« évolution au-delà de FTS5 trigram V008 ») ou le retirer ; ajouter l'assertion au contrat documentaire.

### AUD-QUA-21 — Le contrat « Maven uniquement via NexusScript.psm1 » se contourne par indirection ($m = "mvn" ; & $m, cmd /c mvn)

- **Axe** : Qualité · **Effort** : S · **Sprint** : S12 · Durcir les gates ajoutés
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #353 · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — ci-and-supply-chain.md réécrit pour main ; DIRECT_MAVEN_CALL et auto-test du contrat intacts : PASS. | Test actif : test-operational-doc-contracts.sh PASS Réserve : Réserve d'origine inchangée.

**Preuve**

```text
scripts/release/test-operational-doc-contracts.sh:484-491 (motifs littéraux). Mutations rejouées dans une copie : `$m = "mvn"; & $m clean install` et `cmd /c mvn clean install` ajoutés à scripts/compare-jdt.ps1 → contrat PASS ; `& mvn clean install` → rc=1.
```

**Impact.** Un script peut rappeler le Maven non ancré du runner sans que le gate le voie (régression AUD-QUA-05/AUD-QUA-06).

**Action.** Ajouter les motifs `cmd(.exe)? /c .*mvn`, `Invoke-Expression`/`iex` contenant mvn, et toute chaîne littérale 'mvn'/'mvn.cmd'/'mvnw' hors message (avec liste d'exceptions explicite) ; fixtures négatives correspondantes.

### AUD-QUA-23 — La clé du vecteur de requête partagé ignore le texte de la requête : un SearchQueryContext réutilisé rendrait le vecteur de la requête précédente

- **Axe** : Qualité · **Effort** : S · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #344 (merge 59ea02fa) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
SemanticSearchStrategy.java:188-190 queryVectorKey() = providerId|modelId ; SearchQueryContext.java:22 est public et :59-71 renvoie la première valeur calculée pour la clé.
```

**Impact.** Aujourd'hui, chaque contexte ne sert qu'à une requête : FederatedSearchService.java:80 et FederatedContextService.java:148. Mais un futur appelant qui réutiliserait un contexte pour deux requêtes, par exemple une reformulation ou une requête par terme, recevrait silencieusement le vecteur de la première. Le résultat sémantique serait faux, sans erreur.

**Action.** Inclure la requête redactée dans la clé, ou figer la requête dans SearchQueryContext.create(query, deadline) et refuser une autre requête.

### AUD-QUA-24 — L'échappement PowerShell de l'installateur ne double pas les apostrophes typographiques : un chemin contenant « ’ » casse les scripts générés

- **Axe** : Qualité · **Effort** : S · **Sprint** : S13 · Installateur et release finale
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : PowerShellSingleQuote double U+0027 et U+2018 à U+201B ; scripts PowerShell générés écrits en UTF-8 avec BOM (SavePowerShellScript), la conversion ANSI best-fit de SaveStringToFile changeant U+FF07 et U+02BC en apostrophe (PR #354, sprint 13). · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — PowerShellSingleQuote (iss :391-399) double U+0027 et U+2018-201B ; SavePowerShellScript (:1498) intact. | Test actif : test-windows-config-escaping.ps1 (non exécutable ici)

**Preuve**

```text
packaging/windows/nexus-installer.iss.template:388-392 :
  function PowerShellSingleQuote(Value: String): String;
  begin
    Result := Value;
    StringChangeEx(Result, '''', '''''', True);
utilisée pour {app}\nexus-mcp.cmd et le nom de conteneur dans les scripts générés (:1473, :1476, :1483, :1487).
```

**Impact.** PowerShell accepte U+2018, U+2019, U+201A, U+201B comme délimiteurs de chaîne simple. Un dossier d'installation ou un profil contenant « ’ » (ex. « Jean’s ») ferme la chaîne : script de connexion en erreur de syntaxe, ou suite du chemin interprétée comme code. Le chemin est choisi par l'utilisateur lui-même (pas d'attaquant distant) : risque de robustesse plus que d'exploitation. Le nom de conteneur est validé par IsValidDockerName (:1146).

**Action.** Doubler aussi U+2018-U+201B dans PowerShellSingleQuote (ou passer les valeurs par variable d'environnement / fichier JSON lu par le script) ; ajouter un cas « ’ » à test-windows-config-escaping.ps1.

### AUD-QUA-25 — 9 liens relatifs cassés dans le changement OpenSpec archivé le 09/11, que le contrat OpenSpec ne contrôle pas

- **Axe** : Qualité · **Effort** : S · **Sprint** : S13 · Installateur et release finale
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Liens de l'archive 2026-11-09-preciser-bornes-redaction-recence-git corrigés ; check_change_links contrôle tous les Markdown de openspec/changes/, archives comprises, avec fixtures négatives (PR #354, sprint 13). · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — check_change_links (openspec_doc_contracts.py) couvre les archives, y compris 2026-11-12-flux-sur-main-seule : test-openspec-doc-contracts.sh PASS (fixtures d'archive OK). | Test actif : test-openspec-doc-contracts.sh (ci.yml:136)

**Preuve**

```text
openspec/changes/archive/2026-11-09-preciser-bornes-redaction-recence-git/design.md:92-100 :
  [git-context.md](../../../docs/developer/git-context.md#openspec-git-recency-diagnostics)
  [secret-redaction.md](../../../docs/developer/secret-redaction.md#openspec-secret-redaction)
../../../ pointe vers openspec/docs/… (absent) ; les ancres openspec-* n'existent pas non plus dans docs/developer/git-context.md et secret-redaction.md (grep → 0).
```

**Impact.** La matrice de traçabilité exigence → preuve de ce changement (bornes de redaction et récence Git) mène à 404 : 9 liens morts. test-openspec-doc-contracts.sh (PASS) ne vérifie pas les liens des changements archivés.

**Action.** Remplacer par ../../../../docs/developer/… et cibler des titres existants ; ajouter au contrat OpenSpec une vérification des liens relatifs (script fourni par l'audit : 302 fichiers .md contrôlés).

### AUD-QUA-26 — Le contrat « Maven via NexusScript.psm1 » ne voit pas un appel direct à mvn hors début de ligne

- **Axe** : Qualité, Dépendances · **Effort** : S · **Sprint** : S14 · Suites de la vérification du 11 novembre
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : DIRECT_MAVEN_CALL refuse un nom Maven en position de commande après =, ;, {, (, | ou un . de dot-sourcing, cmd /k et pwsh/powershell -c/-Command ; une fixture d'auto-test par forme ; [Limite] de docs/developer/ci-and-supply-chain.md complétée (PR #367, sprint 14). · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — Copie de compare-jdt.ps1 + 1 ligne, contrat complet. Formes du constat (`$o = mvn`, `; mvn`, `{ mvn }`, `. mvn`, `cmd /k`, `pwsh -Command`) → rc=1 « compare-jdt.ps1:194: calls Maven directly ». Nouvelles formes rc=1 : `& { mvn … }`, `Start-Process mvn`, `-FilePath 'mvn.cmd'`, `$(mvn -v)`, `(mvn)`, `mvn.cmd`, `mvnw`, `./mvnw`, `.\mvnw.cmd`, `&mvn`, `& (Get-Command mvn)`, `& "$env:MAVEN_HOME\bin\mvn"`, `cmd /s /c`, `cmd /c"mvn"`, `pwsh -c`, `Invoke-Command {mvn}`, `ForEach-Object { mvn }`. rc=0 : `& {mvn}`, `$(mvn)`, `return mvn`, concaténation, Set-Alias, Process.Start, -EncodedCommand (en [Limite] ci-and-supply-chain.md:511), plus `mvn` seul en fin de ligne, `mvn.bat`, `mvnd`. | Test actif : Oui : ci.yml:123-124 (pull_request/push main) exécute test-operational-doc-contracts.sh ; auto-test à 29 fixtures détectées et 5 acceptées. Réserve : Filtre de motifs documenté ; non documentés : `mvn` seul sans argument (rc=0), `mvn.bat`/`mvnd` (hors Maven 3 du dépôt, impact négligeable). `mvnw` et `./mvnw` sont refusés comme attendu (seul Invoke-Maven est permis).

**Preuve**

```text
scripts/release/test-operational-doc-contracts.sh:588 motif `^\s*mvn…\s` ancré en début de ligne, :587 seulement après `&`. Copie de scripts/compare-jdt.ps1 + `$output = mvn clean install 2>&1`, `Push-Location $root; mvn clean install`, `if (…) { mvn clean install }`, `. mvn clean install`, `cmd /k mvn …`, `pwsh -Command "mvn …"` → contrat rc=0. Formes absentes de la [Limite] docs/developer/ci-and-supply-chain.md:485.
```

**Impact.** Un script peut rappeler le Maven non ancré du runner sous une forme d'appel courante (affectation, ; ou bloc) sans alerte : régression AUD-QUA-05 possible.

**Action.** Rechercher le jeton Maven en position de commande après `=`, `;`, `{`, `(`, `|`, `.` ou `&`, ajouter `cmd /k` et `pwsh|powershell -c(ommand)`, une fixture négative par forme ; compléter la [Limite].

### AUD-QUA-27 — Guide de reproduction encore sur des JAR 0.1.0-SNAPSHOT à la racine alors que la version publiée est 0.2.0

- **Axe** : Qualité · **Effort** : S · **Sprint** : S14 · Suites de la vérification du 11 novembre
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : docs/developer/reproduce-and-debug.md nomme les artefacts nexus-context-engine-<version> et entre dans current_docs ; la constante STALE_DEV_VERSION est refusée dans la documentation courante contrôlée et dans les scripts PowerShell actifs, module partagé compris (PR #367, sprint 14). · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — docs/developer/reproduce-and-debug.md:46-56 nomme target/nexus-context-engine-<version>(-cli).jar (copie racine réelle via antrun, core/pom.xml:313-325) ; 0.1.0-SNAPSHOT absent des docs courantes et scripts actifs (reste dans scripts/archive, history, audits). Mutations : guide + `…-0.1.0-SNAPSHOT-cli.jar` → rc=1 nommant le guide ; copie compare-jdt.ps1 citant la version → rc=1. Mais guide + `…-0.2.0-cli.jar`, `…-0.1.0-cli.jar` ou `…-0.1.1-SNAPSHOT-cli.jar` → rc=0 : le contrat ne refuse que la constante STALE_DEV_VERSION (test-operational-doc-contracts.sh:408-418), pas une version ≠ pom.xml (0.2.1). | Test actif : Oui : ci.yml:123-124 (test-operational-doc-contracts.sh, auto-test names_stale_version). Réserve : Défaut cité corrigé, mais la classe « version périmée » n'est pas gardée : README.md:181, 207-208 et docs/developer/cli.md:62-86 figent 0.2.1 sans contrat lié à pom.xml ; au prochain bump la dérive repassera rc=0.

**Preuve**

```text
docs/developer/reproduce-and-debug.md:49-50 `target/nexus-context-engine-0.1.0-SNAPSHOT.jar` / `-cli.jar` et :56 `java -jar .\target\nexus-context-engine-0.1.0-SNAPSHOT-cli.jar --version` ; pom.xml:9 `<version>0.2.0</version>` ; README.md:182/208 cite bien 0.2.0. Préexistant (fichier identique au 09/11) ; docs/developer/ci-and-supply-chain.md:530 reconnaît qu'aucun contrat ne cherche 0.1.0-SNAPSHOT.
```

**Impact.** Commande de vérification fausse pour un contributeur à la release 0.2.0 ; dérive non détectée par les contrats documentaires.

**Action.** Remplacer par `nexus-context-engine-<version>` (ou 0.2.0) et ajouter 0.1.0-SNAPSHOT aux motifs interdits de test-operational-doc-contracts.sh pour les docs courantes.

### AUD-QUA-29 — L'assistant refuse « ^ » dans NEXUS_HOME, y compris sur la page Runtime sautée : un profil Windows contenant ^ bloque le mode Docker (installation silencieuse figée sur une MsgBox)

- **Axe** : Qualité, Dépendances · **Effort** : S · **Sprint** : S15 · Suites de la vérification du 12 novembre
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Nouveau · **Vérification du 2026-11-12** (`244ab1d3`) : Nouveau constat — Rejoué le 12/11 sous Windows PowerShell 5.1 (repro-qua28.ps1, fonctions extraites de la source durcie) : profil « a^b » → ligne set "NEXUS_HOME=…\a^b\.nexus" correcte et exécutée sans erreur en page 850, mais ContainsUnsafeConfigChars(NEXUS_HOME) = vrai, donc refus par l'assistant. La MsgBox non supprimable est déduite de la lecture.

**Preuve**

```text
harden-windows-installer-source.ps1:113-117 ContainsUnsafeConfigChars refuse ^ ; :223-230 la page Docker vérifie aussi RuntimePage.Values[0] ; template:920 défaut %USERPROFILE%\.nexus, :972 dépôt = %USERPROFILE%, :1008 page Runtime sautée hors profil personnalisé. Or ^ est littéral dans set "…" (rendu a^b vérifié) et dans une valeur .env entre apostrophes (DotEnvQuoted). MsgBox et non SuppressibleMsgBox : /SUPPRESSMSGBOXES ne le supprime pas (test-windows-config-escaping.ps1:342). Déduit de la lecture, non rejoué.
```

**Impact.** Utilisateur dont le nom de profil contient ^ (autorisé par Windows) : mode Docker bloqué avec un message sur des « chemins générés » qu'il ne peut corriger (NEXUS_HOME natif inutilisé en Docker seul, page Runtime masquée) ; installation silencieuse Docker en attente d'une boîte modale.

**Action.** Retirer ^ de ContainsUnsafeConfigChars (déjà sûr dans les deux formats) ou ne valider RuntimePage.Values[0] que si InstallNative() ; passer les MsgBox de NextButtonClick en SuppressibleMsgBox ; fixture « a^b » dans test-windows-config-escaping.ps1.

### AUD-QUA-30 — Le guide de reproduction et le pipeline d'indexation prescrivent encore `mvn` direct (Maven non ancré) alors que le dépôt impose le wrapper

- **Axe** : Qualité · **Effort** : S · **Sprint** : S15 · Suites de la vérification du 12 novembre
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Nouveau · **Vérification du 2026-11-12** (`244ab1d3`) : Nouveau constat — Relevé à la vérification du 12/11.

**Preuve**

```text
docs/developer/reproduce-and-debug.md:18 `mvn -version`, :31 `mvn clean install`, :180 `mvn -q exec:java "-Dexec.args=--help"` ; docs/developer/indexing-pipeline.md:71 idem. Même classe qu'AUD-QUA-03 (fermé pour AGENTS.md seul) : le contrat test-operational-doc-contracts.sh:977-979 ne contrôle que AGENTS.md. Wrapper épinglé Maven 3.10.0. exec:java à la racine d'un POM parent (pom.xml:10 packaging pom, mainClass seulement core/pom.xml:232-235) : échec probable, non rejoué.
```

**Impact.** Un contributeur suivant le guide (que QUA-27 vient de corriger) construit avec un Maven quelconque, contourne la vérification du wrapper et se heurte aux hooks qui refusent mvn ; la commande exec:java donnée ne vise pas le module core.

**Action.** Remplacer par .\mvnw.cmd / ./mvnw (exec:java avec -pl core) et étendre le motif d'AGENTS.md (:977) à current_docs, avec fixture.

### AUD-SEC-03 — Les signatures de symboles issues de SCIP/MINOS/JDT sont renvoyées par REST, MCP et CLI sans passer par la redaction

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : S4 · Une seule sortie publique, redaction complétée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #284 (050af1e4) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
adapters/mcp-java/src/main/java/com/nexus/mcp/McpResultMapper.java:89 `result.put("signature", symbol.signature());` ; adapters/rest-quarkus/src/main/java/com/nexus/api/ApiMapper.java:150 `symbol.signature(),` ; core/src/main/java/com/nexus/cli/CliResultMapper.java:90. Origine externe : index/scip/ScipDocumentParser.java:107 + :142-149 (signature_documentation.text libre), index/minos/MinosFactMapper.java:59 (champ JSON `signature` libre), index/jdt/JdtSymbolMapper.java:104 (detail LSP).
```

**Impact.** Le texte de signature fourni par un producteur externe (hover/documentation SCIP, export MINOS) peut contenir des littéraux (ex. type littéral d'une constante) et sort sans redaction ni filtrage de chemins par find_symbol, search_code et les réponses de recherche REST. Non démontré avec un indexeur réel (voir angles morts).

**Action.** Appliquer SensitiveContentRedactor.redact (ou PublicDiagnosticPolicy.text) à signature/qualifiedName/source/target dans les trois mappers, ou à la persistance via CodeIntelligenceMetadataPolicy.

### AUD-SEC-05 — La découverte JGit remonte les répertoires parents sans plafond ni contrôle de propriétaire du dépôt trouvé

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : S8 · Façade, configuration et durcissements locaux
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : La fabrique unique GitRepositoryLocator (PR #303) retient le dépôt de la racine du projet et n'accepte un dépôt parent que s'il appartient à l'utilisateur courant, à l'image de safe.directory, sinon le contexte Git est ignoré avec un diagnostic sans chemin ; ce contrôle de propriétaire remplace délibérément un plafond strict pour préserver le monorepo (décision BE-0070, option C). · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

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
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #281 (85c08184) · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — scale-benchmark.yml:119 REQUESTED: ${{ inputs.profile }} en env (idem scanner :64, lucene :87) ; diff = commentaires develop→main et trigger. | Test actif : workflow_hygiene inputs PASS Réserve : Réserve d'origine inchangée.

**Preuve**

```text
.github/workflows/scale-benchmark.yml:73 `REQUESTED="${{ inputs.profile }}"` (input `type: string` :6-10) ; idem lucene-lifecycle-benchmark.yml:88 et scanner-corpus-benchmark.yml:63 ; la validation ci|full (scale-benchmark.yml:77) intervient après l'expansion. | Vu aussi par AUD-DEP-13 : .github/workflows/scale-benchmark.yml:73 `REQUESTED="${{ inputs.profile }}"` ; scanner-corpus-benchmark.yml:63 ; lucene-lifecycle-benchmark.yml:88.
```

**Impact.** Injection de commande shell par une valeur de workflow_dispatch contenant `"`/`$(...)`. Limité aux comptes ayant le droit write (déclencheurs dispatch) et à permissions: contents: read (scale-benchmark.yml:44-45).

**Action.** Passer la valeur par `env: REQUESTED: ${{ inputs.profile }}` puis utiliser "$REQUESTED", ou déclarer l'input en `type: choice`.

### AUD-SEC-08 — Absence de .dockerignore : `COPY . .` envoie tout le contexte de build (.git, fichiers locaux .env, données) dans l'étape builder

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : S10 · Conteneur et gouvernance du dépôt
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : .dockerignore à la racine pour Dockerfile (liste d'exclusion) et packaging/docker/Dockerfile.runtime.dockerignore en liste d'autorisation, livré dans le payload Windows et dans le staging de repli de docker-distribution.yml (sprint 10) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
packaging/docker/Dockerfile:8 `COPY . .` ; `ls -a` à la racine : aucun .dockerignore.
```

**Impact.** L'image runtime multi-stage ne copie que les jars (Dockerfile:22-27), mais le cache/la couche builder locale contient l'historique Git et tout secret local présent dans l'arbre au moment du build ; contexte plus lourd.

**Action.** Ajouter un .dockerignore (.git, **/target, .env*, nexus-data, workspace, docs/audits…).

### AUD-SEC-20 — Un jeton sk- dont le corps fait 256 à 260 caractères n'est ni redigé ni refusé (bornes 255 et 261 désalignées)

- **Axe** : Sécurité · **Effort** : S · **Sprint** : S8 · Façade, configuration et durcissements locaux
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Refus OVERSIZED_TOKEN aligné sur le masquage pour sk-, sk-proj- et sk-ant- (corps compté après le préfixe), politique secret-redaction-v7, content-v6 et migration V018 (PR #348, sprint 8). · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — SensitiveContentRedactor v9 : motif sk-(?:proj-|ant-)?+…{20,255}+ inchangé ; passe sautée seulement si le préfixe littéral « sk- » est absent (sensible à la casse comme le motif) ; refus OVERSIZED inchangé. | Test actif : SensitiveContentRedactorTest (ajouts seuls)

**Preuve**

```text
core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:47 `sk-(?:proj-|ant-)?+[A-Za-z0-9_-]{20,255}+` suivi de (?![A-Za-z0-9_-]) (:49) ne correspond plus au-delà de 255 ; :91 OVERSIZED_TOKEN `sk-[A-Za-z0-9_-]{261}` ne refuse qu'à partir de 261 caractères après « sk- ». Rejeu (copie des 3 classes, java 21) : sk- corps 256..260 → LEAK, sk-ant- corps 256 → LEAK, sk-proj- 256 → refus. Aucun test de borne sk- (SensitiveContentRedactorTest.java:129 teste seulement 300). Contredit secret-redaction.md:143,168 (« 255 caractères de corps … refus juste au-dessus »).
```

**Impact.** Une clé de type sk- de cette longueur part en clair dans Lucene, les embeddings Ollama et les sorties CLI/REST/MCP. Formats réels actuels (OpenAI ~164, Anthropic ~108) hors de la fenêtre : exploitation peu probable, mais la garantie « jamais publié en clair » est fausse.

**Action.** Aligner OVERSIZED_TOKEN sur la borne de redaction : `sk-(?:proj-|ant-)?+[A-Za-z0-9_-]{256}` (ou refuser dès 256 caractères de corps après le préfixe optionnel) ; ajouter les tests N/N+1 pour sk-, sk-proj-, sk-ant- (à joindre à la tâche 2.2 du changement ouvert preciser-bornes-redaction-recence-git, qui ne prévoit que ghp_) ; bumper POLICY_VERSION + migration d'invalidation.

### AUD-SEC-23 — Un jeton à préfixe collé à un bloc de clé privée, ou un jeton xox?-, gh?_, sk_/rk_ contenant un « _ », n'est ni redigé ni refusé (refus et masquage désalignés)

- **Axe** : Sécurité · **Effort** : S · **Sprint** : S8 · Façade, configuration et durcissements locaux
- **Dépend de** : `AUD-SEC-20` · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Refus lancé sur le texte où les blocs de clé privée sont remplacés, classes de masquage alignées sur le refus pour xox?-, gh?_, sk_ et rk_, politique secret-redaction-v8, content-v7 et migration V019 (PR #349, sprint 8). · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — Refus toujours sur replacePrivateKeyBlocks ; classes xox?-/gh?_/sk_/rk_ inchangées ; préfiltre STRUCTURED_TOKEN_PREFIXES dans le même ordre que STRUCTURED_TOKENS (9/9) ; doc secret-redaction v9. | Test actif : SensitiveContentRedactorTest Réserve : Réserve d'origine inchangée.

**Preuve**

```text
core/src/main/java/com/nexus/security/SensitiveContentRedactor.java (d193fb05, secret-redaction-v7) : redact lance OVERSIZED_TOKEN sur le contenu brut (:105-108) puis masque sur replacePrivateKeyBlocks(content) (:109-110) ; un jeton collé à « -----END … PRIVATE KEY----- » est précédé d'un « - » au refus (lookbehind (?<![A-Za-z0-9_-]) en échec, :95) et d'un « ] » au masquage, où le quantificateur possessif {20,255}+ échoue sur sa garde de fin. Masquage xox[abposr]-[A-Za-z0-9-] (:48) contre garde et refus [A-Za-z0-9_-] (:49, :96) ; masquage gh[pousr]_[A-Za-z0-9] et (sk|rk)_(live|test)_[A-Za-z0-9] suivis de \b (:45-46) contre refus \w (:94). Sonde java 21 du 10/11 (classes de d193fb05) : clé privée + sk-, sk-ant-, glpat-, xoxb- et corps de 256 ou 300 → LEAK ; xoxb-, ghp_, sk_live_ + 25 caractères + « _ » + 10 → LEAK ; github_pat_ (déjà \w) masqué.
```

**Impact.** Le jeton part en clair dans Lucene, les embeddings et les sorties CLI/REST/MCP. Les formats réels connus n'ont pas ces formes : exploitation peu probable, mais la garantie « borne de refus = borne de masquage + 1 » est fausse pour ces familles.

**Action.** Lancer les contrôles de refus sur replacePrivateKeyBlocks(content) ; aligner les classes de masquage sur le refus (xox?- [A-Za-z0-9_-], gh?_ et sk_/rk_ \w) ; tests 255/256 dans les deux voisinages ; POLICY_VERSION, content-vN et migration d'invalidation.

### AUD-SEC-25 — Un élément XML au nom composé par « - » ou « _ » (<db-password>, <db_password>) n'est pas redigé

- **Axe** : Sécurité · **Effort** : S · **Sprint** : S14 · Suites de la vérification du 11 novembre
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #369 · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:52-53 XML_OPEN `<([a-z0-9._-]++(?<=[<._-]SENSITIVE_NAME))` et :44 OPTION_NAME (lookbehind borné). Sonde : <db-password>, <db_password>, <db.password>, <my-token>, <api_key>, <DB-PASSWORD>, avec CDATA ou attributs, attribut password=, --db-password, --api-key → masqués (avant : <db-password>, <db_password>, <my-token>, --api-key en clair). Borne : corps 4 096 masqué, 4 097 refusé (4 noms, simple et CDATA, 2 options). Conformes à docs/developer/secret-redaction.md:12-13 : <password-hint>, <passwordless>, --passwordless, <keyboard>, <tokenizer>, <monkey>, <mytoken> non masqués ; pas de faux positif nouveau hors :226. | Test actif : Oui : SensitiveContentRedactorTest.java:582 (#xmlElementWhoseNameEndsWithASensitiveKeyIsRedacted, 10 noms), :589, :595 (#compositeXmlNameValueIsRedactedAtTheBoundAndRefusedJustAbove), :607 (#optionWhoseNameEndsWith…), :613, :618 (#compositeNameOfHalfAMillionComponentsHasBoundedCost), :681 ; SchemaMigratorCompositeNameUpgradeTest:45-46,91-92 ; aucun @Disabled. | SensitiveContentRedactorTest 116/0 et SchemaMigratorCompositeNameUpgradeTest 2/0 au build 244ab1d3. Réserve : Limites documentées (secret-redaction.md:226-227) mais fuites réelles : <x:password>, <wsse:Password> (espace de noms) et --db_password restent en clair (sonde) ; <dbPassword> non couvert.

**Preuve**

```text
core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:38 XML_OPEN `(?:[A-Za-z0-9_-]++[._-])*+` : le possessif avale « db-password » puis exige [._-] → zéro itération, et SENSITIVE_NAME échoue. Sonde : <db.password>, <api_key> → [REDACTED] ; <db-password>, <db_password>, <my-token>, <app.db-password> → valeur en clair (idem nexus-1109). Doc secret-redaction.md:13 ne cite que le nom pointé, alors que le commentaire :37 et la classe [._-] visent aussi - et _.
```

**Impact.** Mot de passe d'un fichier XML de configuration (Spring, Maven settings) publié en clair dans Lucene, embeddings et sorties CLI/REST/MCP.

**Action.** Remplacer par `(?:[A-Za-z0-9]++[._-])*+` ; tests <db-password>, <db_password> ; POLICY_VERSION v9, content-v8, migration d'invalidation.

### AUD-SEC-26 — Un marqueur « -----BEGIN » incomplet placé avant une clé privée fait sauter le vrai bloc : la clé reste en clair

- **Axe** : Sécurité · **Effort** : S · **Sprint** : S15 · Suites de la vérification du 12 novembre
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Nouveau · **Vérification du 2026-11-12** (`244ab1d3`) : Nouveau constat — Rejoué indépendamment (java 21, classes de 244ab1d3, corps de clé factice) : bloc seul masqué ; précédé de `if (line.startsWith("-----BEGIN ")) {}` ou d'une ligne « -----BEGIN  » → corps en clair. Identique sur 510ff8ca (préexistant).

**Preuve**

```text
core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:403-419 replacePrivateKeyBlocks : après un « -----BEGIN  » sans suffixe, markerEnd = indexOf("-----", begin+11)+5 trouve les tirets du vrai « -----BEGIN RSA PRIVATE KEY----- » qui suit, puis cursor = markerEnd (:418) passe au-delà de son début ; le bloc n'est plus reconnu et aucun autre motif ne couvre un corps PEM. Sonde (corps factice) : masqué seul ; en clair derrière `if (line.startsWith("-----BEGIN "))` + fixture, « -----BEGIN \n », ou « -----BEGIN CERT\n ». Identique sur 510ff8ca : préexistant, non introduit par la PR #369.
```

**Impact.** Clé privée d'une fixture de test (parseur PEM, doc) publiée en clair dans Lucene, embeddings et sorties CLI/REST/MCP.

**Action.** Si le marqueur n'est pas une clé privée, reprendre à begin + PRIVATE_KEY_BEGIN.length() (ou chercher la fin de marqueur sur la même ligne) ; test « BEGIN incomplet puis bloc RSA » ; POLICY_VERSION v10, content-v9, migration d'invalidation.

### AUD-TST-06 — Assertions de durée murale serrées dans 5 tests de la suite par défaut (500 ms à 2 s)

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : S3 · Filets de test et traces
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #282 (b6b56a07) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/test/java/com/nexus/index/ExternalTaskRunnerTest.java:34 « assertTrue(elapsedMillis < 750L » ; JdtLanguageServerCodeIntelligenceProviderTest.java:186 « assertTimeoutPreemptively(Duration.ofMillis(500), session::close) » ; ProjectIndexingServiceTimeoutTest.java:74 « elapsedMillis < 1_500L » ; SqliteWriteContentionIntegrationTest.java:244 « elapsedMillis < 1_000L » ; SensitiveContentRedactorTest.java:57 « ofSeconds(2) ».
```

**Impact.** Risque de rouge intermittent sur runner chargé (Windows gate, antivirus) sans régression réelle.

**Action.** Garder la sémantique « ne pas attendre le worker » en comparant au délai du worker récalcitrant (1 500 ms) avec marge, ou isoler ces tests dans une exécution non parallèle avec seuils ×3.

### AUD-TST-07 — Tests E2E monolithiques : NexusResourceTest et NexusMcpServerIntegrationTest = 1 @Test chacun pour 200 et 360 lignes

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : S3 · Filets de test et traces
- **Dépend de** : aucun · **Débloque** : `AUD-QUA-09`
- **Statut** : Fermé · **Changement** : Corrigé par la PR #282 (b6b56a07) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
adapters/rest-quarkus/src/test/java/com/nexus/api/NexusResourceTest.java:29-30 unique @Test « exposesTheNexusFlowThroughRestWithoutLeakingCoreModels » (≈15 requêtes) ; adapters/mcp-java/src/test/java/com/nexus/mcp/NexusMcpServerIntegrationTest.java:56 unique @Test ; /home/claude/audit-work/run/mvn-verify.log:705 « Tests run: 1 … NexusResourceTest ».
```

**Impact.** La première assertion en échec masque tous les contrats suivants ; diagnostic CI plus lent.

**Action.** Découper par endpoint/outil (projet partagé via @BeforeAll ou fixture), ou assertAll par étape.

### AUD-TST-08 — Scripts de test orphelins : test-rest-token-contract.ps1 et Test-Hooks.ps1 appelés par aucun workflow ni script

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : S1 · Remettre les mesures d'aplomb
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #266 (89a37f0b) · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — ci.yml:40 et :44 lancent toujours Test-Hooks.ps1 et test-rest-token-contract.ps1 ; claude-code.md : seule la description des hooks passée à main. | Test actif : ci.yml:40 Test-Hooks.ps1 Réserve : Test-Hooks.ps1 adapté (deny git push origin main / refs/heads/main) mais non exécutable ici (pas de pwsh).

**Preuve**

```text
grep -rn "test-rest-token-contract\|Test-Hooks" .github scripts → seulement l'auto-usage scripts/release/test-rest-token-contract.ps1:20 et docs/developer/claude-code.md:45 ; test-mcp-protocol.ps1 n'est appelé que par test-docker-runtime.ps1:53 et test-windows-installer.ps1:214.
```

**Impact.** Contrat token REST côté distribution et garde-fous hooks non rejoués en CI ; dérive silencieuse possible.

**Action.** Brancher test-rest-token-contract.ps1 dans le Windows gate ou docker-distribution, et Test-Hooks.ps1 dans un job pwsh léger de ci.yml.

### AUD-TST-09 — 5 tests opt-in ne tournent dans aucun workflow (MINOS réel, baselines large/small-delta, 2 diagnostics sémantiques)

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : S3 · Filets de test et traces
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #285 (6dc03c2d) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/test/java/com/nexus/index/minos/MinosRealIntegrationTest.java:45 assumeTrue(Boolean.getBoolean("nexus.minos.integration.replay")) → seul scripts/validate-minos-integration.ps1 ; LargeScaleSearchBaselineTest.java:49, SmallDeltaIndexingBaselineTest.java:44, RealSemanticRetrievalDiagnosticTest.java:28, SemanticSearchBenchmarkTest.java:27 → uniquement scripts/measure-iteration-1x-*.ps1 (grep .github/workflows : 0).
```

**Impact.** Intégration MINOS réelle jamais requalifiée automatiquement ; ces classes peuvent pourrir (compilent mais leurs scénarios ne sont plus exécutés).

**Action.** Ajouter un workflow_dispatch/schedule pour MinosRealIntegrationTest, ou documenter leur statut d'outillage de mesure et les sortir de src/test.

### AUD-TST-10 — IndexRepository : 98 lignes de méthodes default jamais exécutées (0/98, 0/56 branches) gonflent le dénominateur core

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : S3 · Filets de test et traces
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #282 (b6b56a07) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/index/IndexRepository.java:18,31,52 « default … » ; /home/claude/audit-work/run/summary.md « IndexRepository 98 manquées / 0 couverte ».
```

**Impact.** Code de repli mort ou non testé ; si une implémentation alternative l'utilise un jour, comportement non vérifié.

**Action.** Supprimer les default inutilisés (SqliteIndexRepository les surcharge) ou les tester via un fake minimal.

### AUD-TST-11 — Tests symlink conditionnés par assume (15 sites) : un runner sans symlinks les ignore silencieusement

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : S1 · Remettre les mesures d'aplomb
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par les PR #267 (e3f67017) et #277 (a218a613) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/test/java/com/nexus/security/SafeFileIOTest.java:54-55 « Assumptions.assumeTrue(createSymbolicLink(...)) » ; idem ProjectScannerTest.java:105, ProjectIndexLockManagerTest.java:148,165, ScipProjectPathGuardTest.java:181, MinosProjectPathGuardTest.java:159… ; contre-exemple fail-closed : FilesystemSemanticsQualificationTest.java:70 « fail(...) ». Run Windows utilisateur : aucun skip symlink (/home/claude/audit-work/run/mvn-verify.log:533 SafeFileIOTest 1 skip = fileKey l.229).
```

**Impact.** Sur un poste Windows sans mode développeur, les tests d'évasion symlink de SafeFileIO/scanner/lock passent en « skipped » sans alerte.

**Action.** En CI (env CI=true), transformer l'assume symlink en fail via une propriété, comme FilesystemSemanticsQualificationTest.

### AUD-TST-15 — Planchers JaCoCo déjà loin de la mesure (api 78/67 contre 88,4/73,7 ; security 85/77 contre 91,7/82,9), contrairement à la doc « 2 à 3 points sous la mesure »

- **Axe** : Tests · **Effort** : S · **Sprint** : S12 · Durcir les gates ajoutés
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par les PR #347 et #353 · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — Planchers security 87/82 = int(min mesure)-2, ≤3 pts sous 89,29/84,63 ; table et règle mises à jour. | Test actif : coverage_floors (coverage-floors=PASS) Réserve : Réserve d'origine inchangée.

**Preuve**

```text
adapters/rest-quarkus/pom.xml:129,134 (com.nexus.api 0.78/0.67) et :149,154 (NexusApiApplicationService 0.60/0.38) contre j7-rest.csv 88,4 %/73,7 % et 84,1 %/60 % ; core/pom.xml:106,111 (security 0.85/0.77) contre 91,7 %/82,9 % ; docs/developer/adapter-coverage-gates.md:72-84 annonce « deux à trois points sous la mesure » avec REST 81,05 %.
```

**Impact.** Une régression d'environ 10 points sur com.nexus.api ou de 24 points sur NexusApiApplicationService passe le gate : le défaut d'origine d'AUD-TST-03 (marge trop large) se reforme dès le sprint suivant.

**Action.** Remonter les planchers à la clôture de chaque sprint (règle écrite dans adapter-coverage-gates.md) ou contrat qui compare jacoco.xml aux planchers et échoue au-delà d'un écart de N points.

### AUD-TST-16 — Aucun test ne couvre un échec entre le commit Lucene et la publication SQLite, ni l'échec du commit sémantique après le lexical

- **Axe** : Tests · **Effort** : S · **Sprint** : S7 · Casser le bloc cyclique du cœur
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #342 (merge 0d64e56b) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
ProjectIndexingService.java:253-254 `publication.commitDerivedIndexes(); publication.publishCanonical(removedPaths);`. Un échec de publish laisse Lucene committé sur le nouveau contenu et SQLite sur l'ancienne génération ; seul markFailed (:322-332) protège, en forçant un rebuild. ProjectIndexingPublicationTest.aFailureAfterSeveralBatches… n'injecte l'échec que pendant l'analyse, avant tout commit. Aucun test n'injecte d'échec dans publish() ni dans le commit sémantique après le commit lexical.
```

**Impact.** L'invariant « SQLite canonique, Lucene dérivé » est tenu par le statut FAILED et la garde READY (SearchService.java:48, FederatedSearchService.java:51), pas par l'atomicité. L'ordre est déjà celui d'avant le sprint, ce n'est donc pas une régression fonctionnelle. Mais ce chemin de reprise n'a pas de test de non-régression, alors que l'atomicité est la promesse centrale de DATA-0062.

**Action.** Test avec IndexWriteSession dont publish lève une exception après un commit Lucene réussi : vérifier statut FAILED, génération inchangée, staging vidé, puis rebuild complet au passage suivant. Idem pour un échec du commit sémantique après le commit lexical.

### AUD-TST-17 — Les 40 règles Archgate (*.rules.ts) ne tournent dans aucun workflow et leur outillage n'est pas épinglé

- **Axe** : Tests, Architecture · **Effort** : S · **Sprint** : S12 · Durcir les gates ajoutés
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #353 · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — ci.yml:270-295 job archgate intact (check --base HEAD) ; .archgate/config.json baseBranch origin/main (plus origin/develop). | Test actif : job Archgate ADR rules (ci.yml) Réserve : GEN-0057 point 1 cite encore origin/develop (annoté :93).

**Preuve**

```text
grep -rn archgate .github → 0 résultat ; exécution prévue seulement en local (.claude/rules/documentation.md:81 « … `archgate check` et `scripts/self-smoke.ps1` ») ; docs/architecture/adr/tsconfig.json:2 « Archgate les exécute avec son propre moteur » ; aucun package.json ni lockfile dans le dépôt ; 38 règles embarquent une copie de gabarit/fonctions-communes.ts (70 l.), dont l'identité n'est vérifiée que par une autre règle Archgate (GEN-0057-…rules.ts:20, :212). | docs/architecture/adr/BE-0046-…md:149 : « Règle Archgate … `git-cache-in-memory-sixteen-entries` vérifie `DEFAULT_CAPACITY = 16` … »
```

**Impact.** Les sections « Compliance and Enforcement » des ADR se présentent comme des contrôles, mais aucun gate ne les exécute. Une violation, ou une dérive des 38 copies, passe la CI. Version de la CLI Archgate non fixée : `archgate check` peut donner des résultats différents d'un poste à l'autre. Certaines règles figent des valeurs que l'audit demande de changer (AUD-PRF-06).

**Action.** Ajouter un job CI qui installe une version épinglée d'Archgate (npm ci sur un package.json + lockfile versionnés) et lance `archgate check`, ou retirer des ADR la mention « contrôlé par » tant que ce job n'existe pas. Factoriser les fonctions communes si l'outil permet un import.

### AUD-TST-18 — Plafond de peuplement 1M relâché après l'échec de la release v0.2.0 : hors PR, un ralentissement uniforme jusqu'à 52 s passe, et la page affirme encore « plafonds inchangés »

- **Axe** : Tests, Performance · **Effort** : S · **Sprint** : S14 · Suites de la vérification du 11 novembre
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Plafond absolu de 1M à 48 906 ms (40 755 × 1,20) dans verify-scale-budgets.py : min(48 906, max(40 s, 2,6 × 500k)), rapport général et courbe dédiée, avec ou sans base ; tests du ralentissement uniforme 20 000/52 000 refusé ; scale-regression-benchmark.md énonce la valeur, sous contrat qui la relit dans le script (PR #368, sprint 14). Réserve : valeur à confirmer par le mainteneur ; check Hermetic scale benchmark mesuré sur la PR. · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — verify-scale-budgets.py:66-68 population_hard_ceiling={1M:48_906}, :105-111 ceiling=min(48 906, max(40 000, int(500k×2,6))) appliqué avec ou sans base ; doc scale-regression-benchmark.md:124/:130/:146 cohérente (plus de « inchangés ») ; contrat check_population_hard_ceiling (test-operational-doc-contracts.sh:1024-1060) + auto-test 3 mutations : PASS. | Test actif : scripts/tests/test_scale_budgets.py : 26 tests OK (python3 -m unittest, relancé ici), dont 4 nouveaux sur le plafond dur ; exécuté par scale-benchmark.yml:225 avant verify ; contrat doc PASS. Réserve : Hors PR (release, dispatch) un ralentissement uniforme reste admis jusqu'à 500k=20 s (plafond inchangé, ~+50 % vs 13 s) et 1M=48 906 ms (~+57 % vs ~31 s) ; valeur dérivée d'une seule mesure max (40 755 ms ×1,20) ; sur push main (profil ci) aucun palier 1M. Check « Hermetic scale benchmark » mesuré sur la PR #368 : à confirmer par gh.

**Preuve**

```text
scripts/verify-scale-budgets.py:60-62 et :98-100 `ceiling = max(ceiling, int(sqlite[500_000]['populationMs'] * 2.6))` ; plafond 500k 20 s (:53) ⇒ plafond 1M effectif jusqu'à 52 s. docs/project/history/population-1m-runners-2026-11-11.md (Décision) : la règle a été posée pour admettre le run 42945695122 (40 755 ms > 40 000). docs/developer/scale-regression-benchmark.md:124 « Les plafonds absolus de latence et de population restent inchangés » contredit :130 et :146. Relevé gh : le run release.yml 42945695122 (v0.2.0) a échoué en 1re tentative sur « Full scale release gate / Hermetic scale benchmark — Verify report and regression budgets » et n'est passé qu'à la relance du job ; le run 42923369260 (tag sur 3ecdb2be) avait échoué au même endroit.
```

**Impact.** Sur workflow_call de release et workflow_dispatch (pas de base comparable), un ralentissement uniforme de ~50 % (500k 13→19,9 s, 1M 31→51,7 s) n'est plus bloqué ; seule la comparaison relative en PR (+20 %) le détecte. Le relèvement à 240 s de la durée totale est en revanche justifié (+~50 s de scénarios stagedPublication/singleProjectFuzzy).

**Action.** Corriger scale-regression-benchmark.md:124 ; envisager un plafond dur 1M (ex. 48 s) en plus du ratio, ou comparer la release à la dernière release mesurée sur le même type de runner.

### AUD-PRF-03 — La fédération n'a pas de deadline globale : un projet lent retarde toute la requête sans diagnostic du projet non servi

- **Axe** : Performance · **Effort** : M · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : `AUD-QUA-10` · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : La recherche et le contexte fédérés démarrent une échéance globale (FederationDeadlinePolicy, 60 s par défaut, NEXUS_FEDERATION_DEADLINE_SECONDS borné à 1-600 s) vérifiée avant chaque projet sans parallélisme, plafonnent le délai d'embedding par le temps restant et nomment les projets non commencés dans unservedProjects à parité CLI, REST et MCP ; décision BE-0064, consolidée depuis dans BE-0081 (sprint 6, tâches 3.1 à 3.3, PR #299) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/search/FederatedSearchService.java:56-66 boucle `for (ProjectDescriptor project : scope)` synchrone ; core/src/main/java/com/nexus/context/FederatedContextService.java:78-90 boucle `for (int index = 0; index < scope.size(); index++)` synchrone ; aucun Executor/deadline (grep Executor|parallel|deadline : 0 occurrence dans ces fichiers). | ADR-0043:86-89 : « aucun parallélisme n'est introduit avant d'avoir mesuré le besoin ».
```

**Impact.** La latence p95 croît linéairement avec le scope (jusqu'à MAX_PROJECTS=100) ; c'est la structure qui portait NXA-20260914-04 (p95 25 projets hors budget). Une seule source lente (Git, Ollama) bloque toute la requête fédérée.

**Action.** Ajouter une échéance globale et un diagnostic par projet non servi ; le parallélisme reste exclu tant qu'une mesure ne le justifie pas (ADR-0043:86-89).

### AUD-PRF-06 — Cache de contexte Git limité à 16 entrées alors que le scope fédéré monte à 100 projets : éviction LRU systématique sur une requête fédérée répétée

- **Axe** : Performance · **Effort** : S · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : La capacité du cache de contexte Git est alignée sur FederatedScopePolicy.MAX_PROJECTS (100) et ses compteurs CacheStats sont exposés sous gitContextCache dans les métadonnées explain, mono-projet et fédéré ; le workflow git-context-cache-qualification vérifie un scope de plus de 16 projets sans éviction ; décision BE-0065, consolidée depuis dans BE-0070 (sprint 6, tâches 5.1 à 5.3, PR #299) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/context/source/git/PersistentGitContextSourceProvider.java:38 `static final int DEFAULT_CAPACITY = 16;` ; :43 LinkedHashMap accessOrder ; :98-103 éviction de l'entrée la plus ancienne ; clé :120 `(projectRoot, query, targets, explain)` → une entrée par projet. FederatedContextService.java:78 parcourt jusqu'à FederatedScopePolicy.MAX_PROJECTS=100. | ADR-0046:61, :82 et :112 assument la capacité 16 et le churn ; constat maintenu en Faible pour la fédération à 100 projets.
```

**Impact.** Pour un scope > 16 projets, l'accès cyclique évince chaque entrée avant sa réutilisation : taux de hit nul sur la répétition d'un contexte fédéré, alors que le fingerprint (status + 2 diffs) est quand même calculé à chaque appel.

**Action.** Aligner la capacité sur MAX_PROJECTS (ou la rendre configurable avec borne mémoire), et exposer hits/misses (CacheStats existe, :108) dans les métadonnées explain ; qualifier via git-context-cache-qualification avec un scope > 16.

### AUD-PRF-08 — Chaque indexation, même incrémentale sans changement, hache intégralement tous les fichiers deux fois (scan initial + scan final)

- **Axe** : Performance · **Effort** : M · **Sprint** : S5 · Indexation et écriture SQLite
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Le benchmark scanner rapporte la part du hachage au scan initial et au scan final ; la mesure montre que le coût vient de l'ouverture du fichier et non du SHA-256, et le design D3 décide de ne pas introduire de raccourci taille/date, DATA-0023 restant inchangé (PR #297, sprint 5, tâches 3.1 et 3.2) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/index/scan/ProjectScanner.java:158 `FileHasher.sha256(safeFile, maxFileSizeBytes)` pour chaque fichier visité, sans raccourci (taille, mtime) ; ProjectIndexingService.java:247 `scanner.scanWithDiagnostics(...)` puis :297-298 second `scanner.scanWithDiagnostics(...)` + fingerprint ; les fichiers modifiés sont en plus relus et rehachés (IndexDocumentPublisher.java:58-59). | ADR-0023 accepte la lecture intégrale et prévoit « mesurer, puis metadata-first si nécessaire » ; le scan final est un contrôle de cohérence voulu.
```

**Impact.** I/O proportionnelle à la taille du dépôt (budget DEFAULT_MAX_TOTAL_BYTES = 2 GiB, ProjectScanLimits.java:23) ⇒ jusqu'à ~4 GiB lus pour une réindexation sans delta ; pénalise les réindexations fréquentes du runtime REST et les filesystems réseau.

**Action.** Mesurer la part du double hachage sur le corpus du scanner-corpus-benchmark ; n'introduire un raccourci (taille, mtime) qu'au-delà du seuil fixé par ADR-0023.

### AUD-PRF-09 — Recherche de symboles : index FTS trigram et préfiltre fuzzy globaux (non scopés projet), jusqu'à 9 recherches × 3 requêtes SQL par projet

- **Axe** : Performance · **Effort** : M · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : La migration V015 remplace le préfiltre fuzzy global par idx_symbols_file_fuzzy_prefilter (file_id en tête, sondé depuis les fichiers du projet) et SymbolSearchStrategy interroge tous les termes en lot (une requête SQL par famille) ; le MATCH FTS trigram reste global par décision mesurée et documentée, la restriction par rowid IN étant 10 à 300 fois plus lente (sprint 6, tâches 7.1 et 7.2, PR #299) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
V008__indexed_substring_search.sql:20-21 `idx_symbols_fuzzy_prefilter ON symbols(SUBSTR(LOWER(name),1,1), LENGTH(name))` (sans projet) ; SqliteSymbolSearch.java:38-52 `symbol_search_fts MATCH ? AND f.project_id = ?` (filtre projet après le MATCH global) ; au-delà de FUZZY_SMALL_CANDIDATE_THRESHOLD=10 000 (SqliteIndexSql.java:25), requête `INDEXED BY idx_symbols_qualified_name` : EXPLAIN → `SCAN s USING INDEX idx_symbols_qualified_name` ; SymbolSearchStrategy.java:43-49 appelle searchSymbols pour la requête puis pour chaque terme (MAX_QUERY_TERMS=8). | Le benchmark 2 projets × 100 k symboles reste dans son budget.
```

**Impact.** Le travail par requête dépend du nombre total de symboles de tous les projets (pas du seul projet ciblé) et est multiplié par terme et par projet fédéré ; le chemin « >10 000 candidats » peut parcourir tout l'index qualified_name.

**Action.** Ajouter project_id dénormalisé (ou index composite via indexed_files) au préfiltre fuzzy, mutualiser trigram+count+fuzzy en une requête par terme ou interroger les termes en un lot json_each ; benchmark avec 25/100 projets peuplés dans la même base.

### AUD-PRF-10 — La décision de garder le journal rollback (sans WAL) est mesurée mais consignée hors ADR

- **Axe** : Performance · **Effort** : S · **Sprint** : S11 · Finitions documentaires et micro-optimisations
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : ADR DATA-0088 (Proposed) consigne le journal rollback, les mesures DELETE/WAL et la condition de réévaluation ; scale-regression-results.md et scale-regression-benchmark.md y renvoient ; SqliteDatabaseHardeningTest.keepsTheRollbackJournalOnEveryConnection fixe PRAGMA journal_mode = delete (sprint 11, tâches 4.1 et 4.2) · **Vérification du 2026-11-12** (`244ab1d3`) : Tient (fichier cité modifié, correction intacte) — Aucun fichier cité modifié ; DATA-0088 et SqliteDatabaseHardeningTest toujours cités (contrat doc PASS) ; mention develop sans effet. | Test actif : SqliteDatabaseHardeningTest Réserve : Réserve d'origine inchangée.

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteDatabase.java:100-110 (aucun PRAGMA journal_mode) ; docs/developer/scale-regression-results.md:70-93 compare DELETE et WAL et conclut :89 « WAL n'est pas adopté » ; docs/developer/sqlite-write-contention.md:7 ; transaction unique voulue (V008:13-15, ADR-0052). Aucun ADR ne porte la décision.
```

**Impact.** Une décision mesurée et structurante vit dans un document de résultats ; elle peut être défaite sans que la chaîne d'ADR le signale.

**Action.** Écrire l'ADR qui consigne le choix rollback/WAL, ses mesures et la règle de réévaluation (scale-regression-benchmark.md:142).

### AUD-PRF-11 — Indexation Lucene : un IndexWriter ouvert et un commit (fsync) par lot de 128 fichiers

- **Axe** : Performance · **Effort** : M · **Sprint** : S5 · Indexation et écriture SQLite
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Une session d'écriture par indexation garde un seul IndexWriter lexical et un seul sémantique, alimentés à chaque lot, committés une fois à la barrière de publication et annulés si l'indexation échoue (PR #297, sprint 5, tâche 4.1) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/search/lucene/LuceneSearchIndex.java:96-108 `applyChanges` → `open(projectId)` (new IndexWriter) puis `commit(resources.writer())` ; appelé à chaque flush IndexDocumentPublisher.java:120-123 ; lots de DEFAULT_INDEX_DOCUMENT_BATCH_FILES=128 / 16 MiB (ProjectIndexingService.java:31-32). Idem pour l'index sémantique (LuceneSemanticSearchIndex.java:188-191).
```

**Impact.** ≈ 782 ouvertures de writer + commits durables pour 100 000 fichiers (lexical) et autant côté sémantique ; coût d'écriture et fragmentation en segments.

**Action.** Conserver un writer pour la durée d'une indexation (operation-scoped au niveau de l'indexation, compatible ADR 0045) et ne committer qu'à la barrière, ou espacer les commits.

### AUD-PRF-12 — Les résultats d'analyse (symboles, relations) de tous les fichiers modifiés restent en mémoire jusqu'à l'écriture SQLite finale

- **Axe** : Performance · **Effort** : M · **Sprint** : S5 · Indexation et écriture SQLite
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Les analyses des fichiers modifiés sont mises en attente dans les tables index_staged_* (V014) par courtes transactions au rythme des flushs, puis appliquées et publiées avec la génération en une transaction finale (PR #297, sprint 5, tâches 2.1 et 2.2, DATA-0074) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/index/IndexDocumentPublisher.java:24 `List<IndexedFileUpdate> updates = new ArrayList<>()` alimentée :66-68 pour chaque fichier modifié, retournée :82 ; écrite en une fois ProjectIndexingService.java:279 `indexRepository.applyChanges(projectId, updates, removedPaths)`.
```

**Impact.** Le lot de documents est borné (128 fichiers/16 MiB) mais pas les analyses : en rebuild complet, la rétention suit le nombre de fichiers (jusqu'à 100 000) et de symboles/relations ; pic de heap non mesuré.

**Action.** Écrire les analyses par lots dans la même transaction (ou une table de staging) au rythme des flushs de documents, la génération restant publiée à la fin.

### AUD-PRF-13 — Troncature de fragment quadratique : reconstruction du préfixe complet et réestimation à chaque ligne

- **Axe** : Performance · **Effort** : S · **Sprint** : S11 · Finitions documentaires et micro-optimisations
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé au sprint 11 (tâches 6.1, 6.2) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/context/BudgetedContextSelector.java:135-147 : `for (String line : lines) { String candidate = builder.isEmpty() ? line : builder + System.lineSeparator() + line; String withMarker = candidate + ...; if (tokenEstimator.estimate(withMarker) > budget) break;`
```

**Impact.** O(lignes × longueur du préfixe) par fragment tronqué ; avec maxPerItemTokens = budget/2 (jusqu'à 100 000 tokens pour MAX_CONTEXT_TOKEN_BUDGET=200 000) et un gros symbole, CPU notable sur le chemin requête.

**Action.** Cumuler incrémentalement le nombre de points de code (l'estimateur est linéaire : HeuristicTokenEstimator 3,5 cp/token) et ne construire la chaîne qu'une fois.

### AUD-PRF-14 — Écriture par fichier : 4 prepareStatement + un SELECT supplémentaire après chaque upsert

- **Axe** : Performance · **Effort** : S · **Sprint** : S5 · Indexation et écriture SQLite
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : SqliteFileWriter prépare l'upsert et les deux DELETE une fois par transaction, obtient l'identifiant par RETURNING id sans relecture, et SqliteBatchInsert met en cache l'instruction de fin de lot par taille (PR #297, sprint 5, tâche 5.1) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteFileWriter.java:58-92 (INSERT ... ON CONFLICT puis `SELECT id FROM indexed_files WHERE project_id = ? AND relative_path = ?`) et :100-103 (2 DELETE préparés), appelés dans la boucle :28-31 ; SqliteBatchInsert prépare en plus le tail à chaque fichier (SqliteBatchInsert.java:57).
```

**Impact.** Aller-retours JDBC et compilation SQL répétés par fichier dans la transaction d'indexation.

**Action.** Préparer les instructions une fois par transaction et utiliser `RETURNING id` (SQLite ≥ 3.35) sur l'upsert.

### AUD-PRF-15 — Index redondants qui amplifient les écritures : préfixes stricts d'autres index

- **Axe** : Performance · **Effort** : S · **Sprint** : S5 · Indexation et écriture SQLite
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : La migration V013 supprime idx_symbol_relations_project, idx_indexed_files_project et idx_symbols_file après vérification par EXPLAIN QUERY PLAN qu'un index plus large sert chaque requête (PR #297, sprint 5, tâches 6.1 et 6.2) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
V001__initial_schema.sql:79-80 `idx_symbol_relations_project ON symbol_relations(project_id)` (préfixe de 5 autres index) ; V001:38-39 `idx_indexed_files_project` (préfixe de UNIQUE(project_id, relative_path)) ; V005:28 `idx_symbols_file ON symbols(file_id)` (préfixe de idx_symbols_file_provider :31). Commande PRAGMA index_list : 6 index sur symbol_relations.
```

**Impact.** Chaque INSERT/DELETE de relation met à jour 6 index B-tree + projection FTS ; coût d'écriture et de stockage sans gain de plan.

**Action.** Migration supprimant les index préfixes après vérification EXPLAIN QUERY PLAN des requêtes existantes.

### AUD-PRF-16 — Voisinage graphe sortant paginé par OFFSET : chaque page rescanne les pages précédentes

- **Axe** : Performance · **Effort** : S · **Sprint** : S11 · Finitions documentaires et micro-optimisations
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Branche claude/s11-graphe-01c9d0 (90408ac9) : OUTGOING_GRAPH_SQL paginée par clé (source_ref, target_ref) > (?, ?), sans OFFSET ; reprise après le dernier couple lu · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteGraphQueries.java:20 `LIMIT ? OFFSET ?` ; boucle :130-150 relance OUTGOING_GRAPH_SQL avec `offset += relations.size()` tant que les imports ne se résolvent pas en arêtes (imports externes).
```

**Impact.** Coût quadratique en nombre de pages pour des graines aux nombreux imports non résolus ; borné par les imports des graines.

**Action.** Pagination par clé (`(source_ref, target_ref) > (?, ?)`) sur l'ordre déjà imposé.

### AUD-PRF-17 — Regex compilées à chaque appel sur des chemins chauds d'import et de découverte

- **Axe** : Performance · **Effort** : S · **Sprint** : S11 · Finitions documentaires et micro-optimisations
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé au sprint 11 (tâches 8.1, 8.2) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
core/src/main/java/com/nexus/paths/RepositoryPath.java:42 `slashPath.matches("^[A-Za-z]:.*")` dans fromProvider (appelé par fait SCIP/MINOS) ; context/source/instruction/RepositoryGlobMatcher.java:14 `Pattern.compile(toRegex(pattern))` par appel et par motif.
```

**Impact.** Allocation/compilation répétée proportionnelle au nombre de faits importés et de requêtes de contexte.

**Action.** Constantes Pattern précompilées ; cache borné des globs compilés.

### AUD-PRF-18 — SemanticIndexingService redige deux fois le même document avant l'envoi d'embeddings

- **Axe** : Performance · **Effort** : S · **Sprint** : S4 · Une seule sortie publique, redaction complétée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #284 (050af1e4) · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — SemanticIndexingService : seul CONTENT_PROFILE_VERSION 7→8 ; redaction unique par document intacte. | Test actif : SemanticIndexingServiceTest (assert content-v8)

**Preuve**

```text
core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java:202 et :210 — même document redigé deux fois. core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java:202 et :210 (`SensitiveContentRedactor.redact(document.content())` deux fois par document, avant troncature) ; context/ContextFragmentFactory.java:124 puis BudgetedContextSelector.java:41-44 redactent à nouveau chaque fragment ; LuceneSearchIndex.java:255 une troisième fois à l'indexation lexicale.
```

**Impact.** Passes regex répétées sur des fichiers jusqu'à 8 MiB ; la défense en profondeur est voulue mais le travail sur le document entier peut être mutualisé.

**Action.** Supprimer l'appel redondant de SemanticIndexingService.java:210 (ou :202) ; la redaction répétée aux frontières CLI/REST/MCP est voulue (secret-redaction.md:29-31) et reste en place.

### AUD-PRF-28 — IndexRepository.applyChanges est mort en production mais reste le chemin mesuré par les benchmarks de réindexation

- **Axe** : Performance, Tests · **Effort** : S · **Sprint** : S6 · Fédération et runtimes longue durée
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #344 (merge 59ea02fa) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
IndexRepository.java:55 `void applyChanges(...)` et :63 openWriteSession ; SqliteIndexRepository.java:94-96 ; aucun appel main à applyChanges (grep core/src/main : seule l'implémentation). Utilisé par ScaleRegressionBenchmarkTest.benchmarkReindexExistingFiles, IndexingHeapPeakBenchmarkTest (singleWriteTransactionMs) et 17 fichiers de test. SqliteFileWriter.writeChanges (:36-54) et writeStagedChanges (:87-115) dupliquent la logique d'écriture.
```

**Impact.** Les mesures de performance publiées (PRF-01, PRF-12 §2) portent sur un chemin que la production n'exécute plus. La régression NEW-C-01 est passée inaperçue pour cette raison. Le port garde une API d'écriture qui contourne la mise en attente et la session.

**Action.** Retirer applyChanges du port, ou le réimplémenter comme openWriteSession + stage + publish et le déplacer en support de test. Rebrancher les benchmarks sur la session.

### AUD-DEP-03 — Le job de publication n'utilise aucun environnement GitHub protégé ; la protection des tags v*.*.* n'est pas attestée

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : aucun · **Débloque** : `AUD-DEP-02`
- **Statut** : Fermé avec réserve · **Changement** : Fermé par #304 (tâche 3.2) : le job publish déclare environment: release ; le mainteneur a créé l'environnement release (reviewer requis, déploiement limité aux tags v*, sans secret) et le ruleset de tags « Protect tags » sur refs/tags/v* (tâches manuelles 3.1 et 3.3) · **Vérification du 2026-11-12** (`244ab1d3`) : Tient (fichier cité modifié, correction intacte) — release.yml identique à 1111 : publish garde environment: release (:169) et needs des 13 gates ; préflight GHCR (:308) intact. Environnement/ruleset : à relire par gh. | Test actif : contrats release (final-audit PASS) Réserve : Réserve d'origine (réglages GitHub externes).

**Preuve**

```text
.github/workflows/release.yml:150-175 : job publish avec `contents: write`, `packages: write`, `id-token: write`, `attestations: write`, sans clé `environment:` ; ruleset 20503835 (docs/audits/2026-09-14/github-ruleset.json:1) ne cible que `~DEFAULT_BRANCH` et `refs/heads/develop`. | gh api …/environments → total_count 0 ; ruleset 20503835 limité à ~DEFAULT_BRANCH et refs/heads/develop (aucune règle sur refs/tags).
```

**Impact.** Tout acteur capable de pousser un tag déclenche la publication ; la garde repose uniquement sur validate-release-tag.sh (tag = HEAD de main, release.yml:28-46). Pas d'approbation manuelle ni de secrets cantonnés à un environnement.

**Action.** Ajouter `environment: release` (reviewer requis, branches/tags autorisés v*.*.*) au job publish et y cantonner les secrets PFX ; créer un ruleset de tags (création/suppression/mise à jour restreintes).

### AUD-DEP-04 — Dans l'image Docker, les scripts d'entrée et les répertoires applicatifs appartiennent à l'UID d'exécution 10001

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S10 · Conteneur et gouvernance du dépôt
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : COPY sans --chown vers /opt/nexus et /usr/local/bin, chmod 0644/0755, chown limité à /data/nexus et /workspace dans les deux Dockerfiles ; refus d'un --chown au contrat test-operational-doc-contracts.sh (sprint 10) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
packaging/docker/Dockerfile:19-20 `chown -R 10001:10001 /opt/nexus …` ; :26-27 `COPY --chown=10001:10001 … /usr/local/bin/nexus-entrypoint|nexus-healthcheck` ; :52 `USER 10001:10001`. Idem Dockerfile.runtime:7-15 (jars en --chown=10001).
```

**Impact.** Un processus compromis peut réécrire l'entrypoint, la sonde de santé ou les JAR (persistance au redémarrage du conteneur si le système de fichiers n'est pas en lecture seule). Non-root mais pas immuable.

**Action.** Laisser /opt/nexus et /usr/local/bin propriété de root (COPY sans --chown, chmod 0755/0644), ne chown que /data/nexus et /workspace ; documenter `read_only: true` dans le compose.

### AUD-DEP-05 — Aucune propriété project.build.outputTimestamp : les JAR et ZIP produits ne sont pas reproductibles bit à bit

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Fermé par #304 (tâche 5.1) : le pom racine fixe project.build.outputTimestamp et la procédure de release prescrit sa mise à jour à chaque version · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — pom.xml:36 outputTimestamp 2026-11-11T00:00:00Z, mis à jour avec 0.2.1. | Test actif : procédure release-and-recovery.md Réserve : Réserve d'origine inchangée.

**Preuve**

```text
grep -rn outputTimestamp --include=pom.xml . → aucun résultat (pom.xml racine, core, adapters/*).
```

**Impact.** Impossible de vérifier indépendamment qu'un artefact publié correspond aux sources ; les SHA-256 publiés varient à chaque build (horodatages d'entrées ZIP).

**Action.** Ajouter `<project.build.outputTimestamp>` au pom racine (mis à jour à chaque release) et vérifier avec `mvnw verify artifact:compare` ou deux builds successifs.

### AUD-DEP-06 — L'enforcer ne contrôle ni versions de plugins ni convergence ; les plugins implicites (jar, resources, install, deploy, clean) ne sont pas épinglés

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Fermé par #304 (tâches 6.1 et 6.2) : les plugins clean, resources, jar, install et deploy sont épinglés en pluginManagement ; l'enforcer ajoute requirePluginVersions et requireUpperBoundDeps et exige Maven [3.10.0,) · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — pom.xml : requirePluginVersions (:315-320), requireUpperBoundDeps (:321-333) intacts ; diff = version/jackson. | Test actif : maven-enforcer (build)

**Preuve**

```text
pom.xml:257-265 règles = requireJavaVersion [21,), requireMavenVersion [3.9.0,), banDuplicatePomDependencyVersions ; grep maven-(jar|resources|install|deploy|clean)-plugin dans les pom → aucun.
```

**Impact.** Les versions des plugins du cycle de vie dépendent du super-POM de la version de Maven (atténué par le wrapper épinglé 3.10.0, mais requireMavenVersion accepte toute 3.9+) ; conflits transitifs non signalés.

**Action.** Épingler les plugins du cycle de vie en pluginManagement, ajouter requirePluginVersions et dependencyConvergence (ou requireUpperBoundDeps), resserrer requireMavenVersion à [3.10.0,).

### AUD-DEP-07 — La génération des notices de licences ne bloque aucune licence incompatible

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Fermé par #304 (tâches 7.1 et 7.2) : l'exécution aggregate-third-party-notices applique une liste blanche includedLicenses avec failOnBlacklist=true et normalise les libellés par licenseMerges ; le contrat final exige ces réglages · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — pom.xml : failOnBlacklist (:359) + includedLicenses (:360) intacts. | Test actif : aggregate-add-third-party (build)

**Preuve**

```text
pom.xml:282-292 : aggregate-add-third-party avec `failOnMissing=true`, sans includedLicenses/excludedLicenses/failOnBlacklist ; grep forbiddenLicenses|includedLicenses|failOnBlacklist → aucun.
```

**Impact.** Une dépendance GPL/AGPL introduite par Dependabot ou un transitif passerait le build alors que le produit est sous licence propriétaire (LICENSE:1-3).

**Action.** Configurer une liste blanche (Apache-2.0, MIT, BSD, EPL-2.0/EDL, LGPL dual…) avec `failOnBlacklist`/`failIfWarning`, et un `licenseMerges` pour normaliser les libellés.

### AUD-DEP-08 — L'image Docker publiée n'embarque ni LICENSE ni THIRD_PARTY_NOTICES ni labels OCI

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S10 · Conteneur et gouvernance du dépôt
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : LICENSE et THIRD_PARTY_NOTICES.txt copiés dans /opt/nexus/ par les deux Dockerfiles ; labels org.opencontainers.image.{source,licenses,revision,version}, revision et version passés en --build-arg par docker-distribution.yml (sprint 10) · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — pom.xml : seule la version ; Dockerfiles non modifiés. | Test actif : test-operational-doc-contracts.sh ; test-docker-runtime.ps1

**Preuve**

```text
packaging/docker/Dockerfile:22-27 et Dockerfile.runtime:10-15 : seuls JAR, quarkus-app et scripts sont copiés ; grep LABEL dans packaging/docker → aucun. Les notices sont pourtant produites (pom.xml:291) et intégrées au ZIP (distribution/assembly.xml).
```

**Impact.** Distribution GHCR sans notices tierces (obligations NOTICE Apache-2.0) ni métadonnées source/licence/révision exploitables par les scanners.

**Action.** Copier LICENSE et target/licenses/THIRD_PARTY_NOTICES.txt dans /opt/nexus/ ; ajouter LABEL org.opencontainers.image.{source,revision,licenses,version}.

### AUD-DEP-09 — Les assets Windows (ZIP, setup.exe) ne reçoivent aucune attestation de provenance

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : aucun · **Débloque** : `AUD-DEP-02`
- **Statut** : Fermé · **Changement** : Fermé par #304 (tâches 4.1 et 4.2) : le job publish atteste par subject-path le ZIP et le setup.exe Windows, vérifie chaque attestation par gh attestation verify avant l'upload, et la documentation décrit la vérification consommateur · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
.github/workflows/release.yml:352-365 : les deux `actions/attest` ciblent uniquement l'image (`subject-name: ${{ steps.publish.outputs.image }}`) ; les assets l.374-381 ne sont accompagnés que de fichiers .sha256 publiés au même endroit.
```

**Impact.** Le consommateur Windows ne peut vérifier la provenance (gh attestation verify) ; un .sha256 co-localisé ne protège pas contre une substitution sur la release.

**Action.** Ajouter `actions/attest` (subject-path des ZIP/EXE) avant l'upload et documenter `gh attestation verify`.

### AUD-DEP-10 — Le commentaire de version d'upload-artifact dans osv-scanner.yml est faux (« v4.6.2 » pour le SHA de v7.0.2)

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #281 (85c08184) · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — osv-scanner.yml:70 upload-artifact@cf430e03… # v7.0.2 inchangé ; diff = develop retiré des triggers PR/push (main gardé). | Test actif : workflow_hygiene pins + action-pin-upstream.yml (PR main + cron hebdo) Réserve : Réserve d'origine inchangée.

**Preuve**

```text
.github/workflows/osv-scanner.yml:72 `actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v4.6.2` ; `git ls-remote --tags https://github.com/actions/upload-artifact` → `cf430e03… refs/tags/v7.0.2` ; v4.6.2 = ea165f8d….
```

**Impact.** Revue des pins trompeuse ; Dependabot (PR #248 « bump upload-artifact 4.6.2 → 7.0.2 ») a modifié le SHA sans le commentaire sur cette ligne.

**Action.** Corriger le commentaire en `# v7.0.2` ; ajouter au contrat local une vérification SHA ↔ commentaire.

### AUD-DEP-11 — Deux versions majeures d'actions/checkout coexistent : 11 usages en v4.4.0 et 8 en v7.0.1

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #281 (85c08184) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
grep -ho 'uses: …# …' .github/workflows/*.yml | uniq -c → `11 actions/checkout@11d5960… # v4.4.0`, `8 actions/checkout@3d3c42e… # v7.0.1` (ex. windows-installer.yml:59, scale-benchmark.yml:58) ; ls-remote confirme v4.4.0 et v7.0.1.
```

**Impact.** Comportements divergents entre workflows (credentials persistés, runtime Node), surface de maintenance double ; Dependabot ne converge pas les pins.

**Action.** Aligner toutes les occurrences sur v7.0.1 (même SHA) dans une PR dédiée.

### AUD-DEP-12 — Les workflows réutilisables OSV sont épinglés sur un commit non tagué, sans version en commentaire

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #281 (85c08184) · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — osv-scanner.yml:28 et :80 @b1d267ae… # v2.7.0 inchangés ; triggers PR/push main + schedule. | Test actif : workflow_hygiene pins/upstream PASS

**Preuve**

```text
.github/workflows/osv-scanner.yml:31 et :82 `google/osv-scanner-action/…@8ac9e5ce… # fail closed on incomplete scans` ; `git describe --tags 8ac9e5c` → `v2.6.0-2-g8ac9e5c` (commit sur main, contenu dans v2.7.0).
```

**Impact.** Pin légitime (vérifié sur main amont) mais non suivable sémantiquement par Dependabot/revue ; dérive silencieuse probable.

**Action.** Épingler sur le SHA de v2.7.0 (6ad84ce1…) avec `# v2.7.0`, après vérification que le correctif « fail closed » y est inclus.

### AUD-DEP-14 — Le benchmark d'échelle n'est pas un check requis et sa version post-merge sur develop ignore une partie des chemins couverts en PR

- **Axe** : Dépendances, Tests · **Effort** : S · **Sprint** : S1 · Remettre les mesures d'aplomb
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : PR #265 (4aa90dfa) et #278 (4024c874) ; « Hermetic scale benchmark » ajouté au ruleset par le mainteneur · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — main-push-scale-benchmark.yml paths = WATCHED_PATHS de scale-benchmark.yml (18 entrées identiques, vérifié) ; push main + PR main ; scale-benchmark.yml sur toute PR main (sans filtre) ; ruleset requis à revérifier. | Test actif : test-operational-doc-contracts.sh (égalité des listes) PASS ; test_scale_budgets.py 26 OK

**Preuve**

```text
scale-benchmark.yml:15-31 (PR) inclut application/**, index/**, project/**, config/**, scripts/verify-scale-budgets.py, scripts/tests/test_scale_budgets.py, config/scale-benchmark-protocol ; develop-push-scale-benchmark.yml:6-17 ne les contient pas.
```

**Impact.** Un merge touchant ces zones (ou un merge combinant plusieurs PR) n'est pas re-benchmarké sur develop ; un rouge de charge peut n'apparaître qu'à la release.

**Action.** Aligner les filtres de chemins du wrapper develop-push sur ceux du workflow réutilisable (ou les factoriser). Rendre « Scale benchmark » requis sur les chemins qu'il surveille (ou documenter pourquoi il reste consultatif).

### AUD-DEP-15 — Plusieurs jobs n'ont pas de timeout-minutes (défaut GitHub 360 min)

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Corrigé par la PR #281 (85c08184) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
Analyse YAML : ci.yml windows-gate/linux-reactor, codeql.yml analyze, release.yml validate-release, osv-scanner scan-pr/scan-sbom (réutilisables) → timeout absent ; les autres jobs en ont (5 à 180).
```

**Impact.** Un blocage (Windows gate, résolution Maven) consomme jusqu'à 6 h de runner et retarde les checks requis.

**Action.** Fixer timeout-minutes sur ci.yml (ex. 45), codeql.yml (30), validate-release (10).

### AUD-DEP-16 — Le ruleset autorise squash et rebase alors que la politique impose le merge commit

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S10 · Conteneur et gouvernance du dépôt
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Documentation de la méthode merge commit seule (docs/developer/branch-governance.md, skill nexus-pr-workflow) et assertion du contrat documentaire (sprint 10, tâche 5.1) ; restriction du ruleset 20503835 et désactivation de squash/rebase à faire par le mainteneur (tâche 5.2) ; réglages GitHub appliqués par le mainteneur (sprint 10, tâches 5.2 et 5.4) · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — Skill nexus-pr-workflow:13 merge commit seul, exact-head, ruleset allowed_merge_methods=["merge"] ; relevé 2026-11-12 : ruleset 20503835 « Protect main » merge=["merge"], squash/rebase false. | Test actif : test-operational-doc-contracts.sh PASS

**Preuve**

```text
gh api repos/FTurleque/nexus-context-engine/rulesets/20503835 (17/10/2026) : pull_request.allowed_merge_methods = [merge, squash, rebase] ; réglages du dépôt allow_squash_merge et allow_rebase_merge = true ; .claude/skills/nexus-pr-workflow/SKILL.md:13 « merge commit, jamais squash/rebase ».
```

**Impact.** La règle n'est tenue que par un hook local Claude Code ; un merge via l'interface GitHub peut la contourner (instantané du 14/09, état actuel non vérifié).

**Action.** Restreindre allowed_merge_methods à ["merge"] dans le ruleset 20503835.

### AUD-DEP-17 — Le dépôt public n'a ni SECURITY.md ni CODEOWNERS

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S10 · Conteneur et gouvernance du dépôt
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : SECURITY.md complété (signalement privé GitHub, aucune issue publique, versions prises en charge, délai de réponse) et .github/CODEOWNERS créé, vérifiés par le contrat documentaire (sprint 10, tâche 5.3) ; activation du signalement privé à faire par le mainteneur (tâche 5.4) ; réglages GitHub appliqués par le mainteneur (sprint 10, tâches 5.2 et 5.4) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
ls .github → dependabot.yml, workflows ; `ls SECURITY.md .github/SECURITY.md` → No such file or directory.
```

**Impact.** Pas de canal documenté de divulgation de vulnérabilités ; les fichiers sensibles (.github/workflows, config/tool-integrity.properties) n'ont pas de propriétaire déclaré.

**Action.** Ajouter SECURITY.md (signalement privé GitHub) et un CODEOWNERS minimal sur workflows, scripts/release et ancres d'intégrité.

### AUD-DEP-18 — Durcissement conteneur incomplet : pas d'instruction HEALTHCHECK dans l'image, compose sans cap_drop ni no-new-privileges

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S10 · Conteneur et gouvernance du dépôt
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : HEALTHCHECK sur /usr/local/bin/nexus-healthcheck dans les deux Dockerfiles ; compose : read_only: true, tmpfs /tmp:exec, cap_drop: [ALL], security_opt: [no-new-privileges:true] ; smoke Compose durci ajouté au workflow Docker distribution (sprint 10) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
packaging/docker/Dockerfile:1-55 sans HEALTHCHECK (sonde seulement dans docker-compose.yml.template:46-53) ; grep read_only|cap_drop|no-new-privileges dans le template → aucun.
```

**Impact.** `docker run` direct sans compose n'a pas de sonde ; surface noyau/capacités par défaut.

**Action.** Ajouter `HEALTHCHECK CMD /usr/local/bin/nexus-healthcheck` ; dans le compose : `read_only: true` + tmpfs, `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`.

### AUD-DEP-19 — La qualification sémantique réelle est instable : 16 des 25 derniers runs annulés, environ 2 h par run, hors gate de release

- **Axe** : Dépendances · **Effort** : M · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : aucun · **Débloque** : `AUD-PRF-02`
- **Statut** : Fermé · **Changement** : Corrigé par la PR #281 (85c08184) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
WebFetch …/actions/workflows/semantic-search-qualification.yml → runs #40-#64 : 16 cancelled, 9 success (#61 annulé sur la PR de promotion #250 après 2h02) ; semantic-search-qualification.yml:33 timeout 180 ; release.yml:151-164 `needs` sans job sémantique.
```

**Impact.** La promotion develop→main du 16/10 s'est faite sans qualification sémantique réelle aboutie sur ce SHA ; coût runner élevé. PR #253 (98b1f3f) vient d'ajuster le budget.

**Action.** Réduire le corpus de qualification PR (sous-ensemble représentatif) et garder le corpus complet en planifié ; décider explicitement si la release doit l'exiger.

### AUD-DEP-20 — Deux gates requis mesurent moins que leur nom : « Windows gate » sur un JDK 24 non épinglé, CodeQL Java sans build

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S1 · Remettre les mesures d'aplomb
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #266 (89a37f0b) · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — ci.yml:36 Windows gate Temurin '21.0.12+101.0.LTS', codeql.yml:52 idem ; diff = triggers develop retirés, main conservé. | Test actif : workflow_hygiene pins PASS Réserve : Réserve d'origine inchangée.

**Preuve**

```text
.github/workflows/ci.yml:33-37 `java-version: '24'` (choix documenté docs/developer/runtime-flags-qualification.md:21) ; codeql.yml:52-54 `build-mode: none`, `queries: security-extended`.
```

**Impact.** Le gate Windows obligatoire ne compile pas avec la cible livrée (Java 21.0.12) et dérive avec les mises à jour 24.x ; CodeQL sans build a une précision réduite sur Java (flux à travers types résolus).

**Action.** Faire tourner le Windows gate en 21 épinglé (24/25 en matrice secondaire), passer CodeQL en build-mode manual/autobuild via mvnw.

### AUD-DEP-21 — release.yml transmet tous les secrets du dépôt au workflow Windows avec secrets: inherit

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S9 · Chaîne de release exécutable
- **Dépend de** : aucun · **Débloque** : `AUD-DEP-02`
- **Statut** : Fermé · **Changement** : Fermé par #304 (tâches 1.1 et 1.2) : release.yml n'utilise plus secrets: inherit et appelle le job Windows avec require_authenticode: false sans transmettre aucun secret ; le contrat final refuse secrets: inherit dans .github/workflows · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
.github/workflows/release.yml:59 `secrets: inherit` alors que windows-installer.yml:11-15 ne déclare que les deux secrets PFX.
```

**Impact.** Moindre privilège non appliqué : tout secret ajouté plus tard au dépôt devient accessible au job de build Windows.

**Action.** Passer explicitement `secrets: { NEXUS_WINDOWS_SIGNING_PFX_BASE64: …, NEXUS_WINDOWS_SIGNING_PFX_PASSWORD: … }`.

### AUD-DEP-22 — Les runners sont désignés par des libellés flottants -latest et aucun cache Maven n'est utilisé

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S2 · Outillage des scripts et des workflows
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #281 (85c08184) · **Vérification du 2026-11-12** (`244ab1d3`) : Inchangé — Aucun fichier cité modifié entre 510ff8ca et 244ab1d3 (git diff --name-only, 193 fichiers hors archives OpenSpec).

**Preuve**

```text
grep -ho 'runs-on: .*' → 13 ubuntu-latest, 4 windows-latest, 3 matrices sur *-latest ; grep 'cache:' / actions/cache dans .github/workflows → aucun.
```

**Impact.** Changement d'image runner silencieux (cf. variance des benchmarks de l'audit 2026-09-14) ; chaque run retélécharge le reactor depuis Maven Central (temps, dépendance réseau). Pas de risque d'empoisonnement de cache en contrepartie.

**Action.** Épingler ubuntu-24.04 / windows-2025 pour les benchmarks ; `setup-java cache: maven` si le gain justifie la surface de cache.

### AUD-DEP-31 — La montée Quarkus 3.40.1.1 (PR #262) échoue sur un contrat documentaire qui recopie le numéro de version

- **Axe** : Dépendances, Qualité · **Effort** : S · **Sprint** : S10 · Conteneur et gouvernance du dépôt
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : Symptôme fermé par #269 (documentation alignée sur Quarkus 3.40.1.1 et le SDK MCP 2.1.0) ; cause fermée par la PR du lot 6 du sprint 10 : la documentation courante nomme les propriétés quarkus.platform.version et mcp.sdk.version, le contrat documentaire les lit dans pom.xml et refuse les littéraux de version · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — README dev : version 0.2.1 (bloc), Quarkus/MCP toujours par propriétés ; contrat lit le pom et refuse les littéraux : PASS. | Test actif : test-operational-doc-contracts.sh

**Preuve**

```text
gh pr checks 262 : « Linux reactor Maven build » FAILURE (run 40405755776, 26/10) ; log : « documentation contract drift: docs/developer/README.md must contain 'Quarkus      3.40.1.1' » (étape scripts/release/test-operational-doc-contracts.sh). Les 9 autres PR Dependabot ouvertes (#255 à #264) sont vertes et en attente depuis 3 à 10 jours.
```

**Impact.** Chaque montée de version d'une dépendance recopiée dans la documentation casse la CI de sa PR Dependabot et demande une retouche manuelle ; les correctifs de sécurité de Quarkus attendent.

**Action.** Faire lire la version au contrat documentaire depuis pom.xml (ou générer la ligne de doc), puis fusionner les PR Dependabot en attente ; à rapprocher de AUD-QUA-12 (version du projet codée en dur).

### AUD-DEP-32 — workflow_hygiene.py ne lit pas toutes les formes YAML (run: multi-lignes simple, flow mapping, clés citées, with: script:) ni les actions composites

- **Axe** : Dépendances, Sécurité · **Effort** : S · **Sprint** : S12 · Durcir les gates ajoutés
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #353 · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — workflow_hygiene.py : formes ajoutées (clé espacée, clé de job mal indentée), lignes de bloc run:/script: exclues de forms ; fixtures négatives ajoutées. | Test actif : test-tool-integrity-anchors.sh : workflow-hygiene-forms/pins PASS Réserve : Nouveau faux positif : SPACED_KEY lit aussi le texte des scalaires bloc hors run:/script: (voir nouveaux).

**Preuve**

```text
scripts/release/workflow_hygiene.py:28 et :114-128 (run: inline ou bloc |/> seulement), :30-31 (clés non quotées), :147-173 (jobs en block mapping), :46-54 (seul .github/workflows). Fixture locale : `run:` + valeur à la ligne suivante contenant ${{ inputs.profile }}, job `b: { runs-on: …, steps: [ { run: "echo ${{ inputs.profile }}" } ] }`, clé "runs-on" quotée sans timeout, `with: script:` github-script → inputs et timeouts PASS. | scripts/release/test-tool-integrity-anchors.sh:116 `python3 "$HYGIENE" pins "$ROOT/.github/workflows"` ; scripts/release/test-operational-doc-contracts.sh:568 `python3 "$HYGIENE" "$check" .github/workflows` ; .github/actions/ollama-embedding-runtime/action.yml hors périmètre.
```

**Impact.** Une réintroduction d'AUD-SEC-07 ou d'AUD-DEP-15 sous une de ces formes passe le gate ; les actions composites (.github/actions/ollama-embedding-runtime) ne sont pas contrôlées.

**Action.** Fail-closed : rejeter les formes non supportées (flow mapping sous jobs, clé quotée, run: sans valeur suivi d'un scalaire simple), étendre inputs à `script:` et aux fichiers .github/actions/*/action.yml ; ou parser avec un YAML épinglé ; ajouter une fixture par forme.

### AUD-DEP-33 — Le contrôle des épinglages compare les commentaires entre eux, pas au tag amont : AUD-DEP-10 peut revenir sans alerte

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S12 · Durcir les gates ajoutés
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Corrigé par la PR #353 · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — action-pin-upstream.yml : PR main (paths) + cron hebdo + dispatch ; upstream inchangé. | Test actif : action-pin-upstream.yml (réseau, non relancé) Réserve : Réserve d'origine inchangée.

**Preuve**

```text
scripts/release/workflow_hygiene.py:88-111 ; scripts/release/test-tool-integrity-anchors.sh:111-113 « Upstream tags are checked by hand on a bump ».
```

**Impact.** AUD-DEP-10 se reproduit sans alerte pour toute action épinglée à un seul endroit (ex. github/codeql-action, google/osv-scanner-action, actions/attest) si un bump change le SHA sans le commentaire.

**Action.** Job planifié (ou sur PR Dependabot) avec réseau qui compare chaque SHA à `git ls-remote --tags` (tag ou tag^{}) et échoue sur écart.

### AUD-DEP-34 — Des tags GHCR :0.2.0 et :latest posés le 9 août par l'ancienne chaîne Docker existent hors de la release et bloqueront le préflight immuable de la release finale v0.2.0

- **Axe** : Dépendances, Sécurité · **Effort** : S · **Sprint** : S13 · Installateur et release finale
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé · **Changement** : docs/user/docker-installation.md attribue la publication GHCR à release.yml ; contrat test-operational-doc-contracts.sh : aucune publication dans les workflows de distribution, packages: write réservé à release.yml (PR #354, tâche 4.1). Tâches mainteneur 4.2 (état du registre avant la promotion de main) et 4.3 (provenance après la release finale) ouvertes. · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — docker-distribution.yml : PR/push main (paths), dispatch, workflow_call ; contents: read, aucun login/push GHCR, seulement upload-artifact ; packages: write et docker push uniquement dans release.yml (:306, :335) ; contrat ghcr-publication PASS. | Test actif : test-operational-doc-contracts.sh (ghcr-publication-contracts=PASS)

**Preuve**

```text
docs/project/history/release-rehearsal-0.2.0-rc.2-2026-11-08.md:46 « ghcr.io/fturleque/nexus-context-engine:latest a le digest sha256:fbe2256… celui de :0.2.0 » alors qu'aucun run release.yml n'a précédé rc.1/rc.2 (github.md) ; plus aucun workflow ne pousse sur GHCR (grep ghcr|docker push dans docker-distribution.yml et develop-push-docker-distribution.yml → 0) ; scripts/release/ghcr-immutable-preflight.sh:94 refuse un tag de version qui désigne un autre contenu ; packaging/windows/nexus-installer.iss.template:960 utilise par défaut ghcr.io/…:{#NexusVersion} ; docs/user/docker-installation.md:455-460 affirme encore que docker-distribution.yml publie <version> et latest sur main. | Contrôle du 09/11 à 22 h : docker buildx imagetools inspect → :0.2.0 et :latest = sha256:fbe2256d…, distinct de :0.2.0-rc.2 (014bdc0a…) et :0.2.0-rc.3 (ff572034…). gh attestation verify oci://…:0.2.0 → attestation présente, signée par docker-distribution.yml@refs/heads/main (run 31318894822 du 09/08/2026, commit 3326a848) : ces tags sont attestés, mais pas par la chaîne de release. Le workflow docker-distribution.yml actuel ne publie plus sur GHCR au push de main.
```

**Impact.** Le préflight immuable de la release finale v0.2.0 (ghcr-immutable-preflight.sh) trouvera un tag :0.2.0 déjà occupé par une image d'août, construite hors de la chaîne actuelle ; un utilisateur qui tire :latest ou :0.2.0 aujourd'hui reçoit cette image ancienne au lieu de la dernière pré-release.

**Action.** Relever l'origine et la date des versions de paquet GHCR :0.2.0 et :latest (gh api /user/packages/container/nexus-context-engine/versions). Décider de la suppression ou d'un passage à 0.2.1, consigné dans un ADR ou le relevé de release, avant de promouvoir main (PR #332). Corriger docs/user/docker-installation.md:455-460.

### AUD-DEP-35 — Les labels OCI revision et version sont vides dans l'image construite par l'installateur Windows

- **Axe** : Dépendances · **Effort** : S · **Sprint** : S13 · Installateur et release finale
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : Compose généré et repli docker build passent NEXUS_IMAGE_VERSION et NEXUS_IMAGE_REVISION écrits dans .env par l'installateur ; label revision « unknown » sans révision, l'image de base UBI portant son propre label (PR #354, sprint 13). · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — compose template :12-13 build.args NEXUS_IMAGE_VERSION/REVISION intacts ; image par défaut 0.2.1. | Test actif : test-operational-doc-contracts.sh Réserve : Réserve d'origine inchangée.

**Preuve**

```text
packaging/docker/Dockerfile.runtime:7-8 (ARG NEXUS_IMAGE_REVISION="" / NEXUS_IMAGE_VERSION="") ; packaging/docker/docker-compose.yml.template:6-8 (build: context/dockerfile sans args:) ; seul docker-distribution.yml:120-124, :354-355 passe les --build-arg.
```

**Impact.** L'image locale construite par `pull_policy: build` sur le poste de l'utilisateur porte des labels org.opencontainers.image.revision et version vides : impossible de rattacher un conteneur installé à son commit ou à sa version par docker inspect (l'image GHCR publiée, elle, est correcte).

**Action.** Passer NEXUS_IMAGE_VERSION/REVISION dans build.args du template Compose (valeurs écrites par l'installeur), ou figer la version par défaut dans Dockerfile.runtime au packaging ; asserter le label version dans test-windows-installer.ps1.

### AUD-DEP-36 — workflow_hygiene.py ignore les clés écrites avec un espace avant « : » et les corps de job indentés autrement que de 2

- **Axe** : Dépendances, Sécurité · **Effort** : S · **Sprint** : S14 · Suites de la vérification du 11 novembre
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : workflow_hygiene.py : forms refuse une clé suivie d'espaces avant « : » hors du texte des blocs run:/script: (SPACED_KEY) ; jobs_of lit les clés de job à l'indentation de la première clé du job et marque une clé désalignée, refusée par forms (PR #367, sprint 14). · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — workflow_hygiene.py forms/inputs/timeouts sur fixtures : `- run : echo ${{ inputs.profile }}` → 1/1/1 ; `runs-on : …` → 1/1/1 ; job indenté de 4 sans timeout → timeouts=1 « job 'deep' has runs-on but no timeout-minutes » ; `"runs-on" :`, `"run" :` → quoted key ; `runs-on<TAB>:`, `jobs :`, ancres, flux rejetés ; `"b" :` rejeté (message trompeur). Injectés dans .github/workflows de la copie : test-operational-doc-contracts.sh rc=1, test-tool-integrity-anchors.sh rc=1 (forms seul, attendu). Restent rc=0 : job cité `"b":`/`'b':` sans timeout et `? runs-on` (documentés :447), expression ${{ … }} coupée sur deux lignes d'un bloc run: | (inputs rc=0). | Test actif : Oui : ci.yml:117-124 exécute test-tool-integrity-anchors.sh (pins+forms) et test-operational-doc-contracts.sh (forms, inputs, timeouts, runners + fixtures négatives). Réserve : Identifiant de job cité et clé complexe documentés ; nouveau : `echo ${{` / `inputs.profile }}` sur deux lignes d'un bloc run: n'est pas vu par inputs (EXPRESSION lue ligne par ligne, workflow_hygiene.py:52) — acceptation par GitHub non vérifiée par un run.

**Preuve**

```text
scripts/release/workflow_hygiene.py:44 RUN exige `run:` collé, :58 KEY idem, :294 clés de job seulement à job_indent + 2. Fixtures sur copie : `- run : echo ${{ inputs.profile }}` → forms/inputs rc=0 ; `runs-on : ubuntu-latest` sans timeout → timeouts rc=0 ; job dont les clés sont indentées de 4 sans timeout → timeouts rc=0 ; injectés dans .github/workflows de la copie → les deux contrats rc=0.
```

**Impact.** Une réintroduction d'AUD-SEC-07 (input interpolé dans run:) ou d'AUD-DEP-15 (job sans timeout) sous une forme YAML valide passe le gate qui se dit fail-closed.

**Action.** Dans forms, refuser toute clé suivie d'espaces avant « : » et toute clé de job à une indentation différente de la première clé du job ; fixtures négatives correspondantes (ou parser YAML épinglé).

### AUD-DEP-37 — La release GitHub v0.2.0 n'est pas immuable et le tag v0.2.0 a été recréé par le contournement admin du ruleset des tags

- **Axe** : Dépendances, Sécurité · **Effort** : S · **Sprint** : S14 · Suites de la vérification du 11 novembre
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Fermé avec réserve · **Changement** : publish-github-release-assets.sh sort en code 79 si la release publiée n'est pas immuable, avant latest ; procédure « Reprise d'une release échouée » dans release-and-recovery.md, sous contrat refusant les commandes de suppression ou de forçage de tag v* dans docs/developer/ ; SEC-0068 complété (création réservée au mainteneur, mise à jour et suppression interdites à tous, releases immuables) ; relevé daté du 2026-11-11 (PR #368, sprint 14). Réserve : tâches mainteneur 2.1 (releases immuables) et 2.2 (deux rulesets de tags) ouvertes ; v0.2.0 reste non immuable. · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé avec réserve — Relevé gh du 12/11 (lecture seule, ta machine) : immutable-releases enabled:true ; ruleset 27049344 « Immutable version tags » (refs/tags/v*) : deletion, non_fast_forward, update, aucun contournement ; ruleset 26777159 « Protect tags » réduit à creation, admin en contournement always ; releases v0.2.1-rc.1 et v0.2.1 immutable:true (runs release.yml 43080652705 et 43092786728, 1re tentative), tags GHCR 0.2.1 et latest sur sha-9b188ab9, attestation release.yml@refs/tags/v0.2.1. Code : publish-github-release-assets.sh sort en 79 si la release publiée n'est pas immuable, avant la pose de latest GHCR ; procédure de reprise sous contrat (release-and-recovery.md). Réserve : v0.2.0 reste non immuable (on ne peut pas le rattraper). Le réglage n'est pas imposé par le propriétaire (enforced_by_owner:false). La création de tag v* reste ouverte à l'admin en contournement. Le contrôle 79 arrive après la publication : une version finale recevrait déjà le badge Latest GitHub (AUD-DEP-38). La regex du contrat anti-tag ignore le drapeau placé après le tag et l'API PATCH (Info).

**Preuve**

```text
gh api …/releases/tags/v0.2.0 → immutable: false. Ruleset « Protect tags » (26777159, refs/tags/v*) : creation, update, deletion, non_fast_forward, mais bypass_actors = RepositoryRole 5 (admin) en bypass_mode always. Runs release.yml : 42923369260 sur v0.2.0 → 3ecdb2be (échec au gate de charge), puis 42945695122 sur v0.2.0 → 9f6e7839 (succès) : le tag a été supprimé et reposé. Rien n'a été publié par le premier run, et les assets du second sont attestés par release.yml.
```

**Impact.** Rien n'empêche techniquement de remplacer un asset de la release publiée ni de déplacer un tag de version après publication : la garantie « tag immuable » de AUD-DEP-27 repose sur la discipline de l'administrateur. Les attestations SLSA permettent de détecter une substitution, pas de l'empêcher.

**Action.** Activer les releases immuables du dépôt (Settings → General → Releases) ; passer le contournement du ruleset des tags en mode pull_request ou le retirer, et documenter la procédure de reprise d'une release échouée (nouvelle version rc plutôt que tag recréé).

### AUD-DEP-38 — Code 79 posé après la publication : une version finale non immuable reçoit déjà le badge Latest GitHub, contrairement à release-and-recovery.md

- **Axe** : Dépendances, Sécurité · **Effort** : S · **Sprint** : S15 · Suites de la vérification du 12 novembre
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Ouvert · **Changement** : Nouveau · **Vérification du 2026-11-12** (`244ab1d3`) : Nouveau constat — Relevé à la vérification du 12/11.

**Preuve**

```text
scripts/release/publish-github-release-assets.sh:147 `gh release edit "$TAG" --draft=false` (sans --latest=false pour une version finale, création :73-78 sans --latest) précède le contrôle :163-170 ; docs/developer/release-and-recovery.md:79 « `latest` n'a pas bougé ». Le faux gh de test-publish-github-release-assets.sh ne modélise pas make_latest (défaut true de l'API à la publication).
```

**Impact.** Si le réglage « releases immuables » était désactivé, une version finale serait publique, mutable et désignée Latest sur GitHub avant l'échec 79 ; seul le tag d'image GHCR latest reste protégé. Risque résiduel tant que le réglage reste actif (relevé 2026-11-11 enabled:true).

**Action.** Créer/publier les versions finales avec --latest=false puis `gh release edit --latest` après le contrôle immuable (avant ou avec le déplacement GHCR), ou corriger la doc en « le tag d'image latest n'a pas bougé » ; tester make_latest dans le faux gh.

## Info (44)

Constats sans action : ils documentent ce qui tient. Ne pas les « corriger » ; plusieurs expliquent pourquoi un risque est déjà borné.

### AUD-ARC-15 — Cœur sans framework applicatif et dépendances techniques confinées à leurs packages adaptateurs

- **Axe** : Architecture · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
Commande : `grep -rn 'jakarta|quarkus|springframework' core/src/main` → 0 ; imports tiers par package : org.apache.lucene uniquement dans search.lucene (29) et search.semantic.lucene (19), org.sqlite uniquement dans persistence.sqlite, org.eclipse.jgit dans context.source.git (33) et index.scan (1), com.github.javaparser dans index.java (11) ; java.sql hors persistence.sqlite seulement dans application et cli (signatures, cf. AUD-ARC-11).
```

**Impact.** Borne le risque de contamination framework/infra du domaine ; constat de l'audit du 14/09/2026 (ligne Architecture) toujours d'actualité.

**Action.** Figer ces règles par le test d'AUD-ARC-07.

### AUD-ARC-16 — Adaptateurs REST/MCP minces : ils passent exclusivement par NexusApplication et des types valeur

- **Axe** : Architecture · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
Commande : imports `com.nexus.*` des adapters/*/src/main → `com.nexus.application.NexusApplication` (11) + types valeur (ProjectDescriptor, QueryPolicy, RankedCandidate, ContextItem…) ; aucun import persistence/lucene/service en main (persistence.sqlite n'apparaît que dans 2 tests). adapters/assistant-clients/pom.xml ne dépend pas de nexus-context-engine.
```

**Impact.** Les adaptateurs ne contournent pas l'application (ADR-0039/0040/0041 respectés) ; seule la projection publique est dupliquée (AUD-ARC-08).

**Action.** Aucune.

### AUD-ARC-17 — Composition root explicite et cohérente ; packages project et persistence.sqlite hors cycles

- **Axe** : Architecture · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
core/src/main/java/com/nexus/application/NexusApplicationFactory.java:73-163 (construction explicite SQLite, Lucene, providers, ranker, sources ; variantes Persistent* en décorateurs de SearchIndex/SemanticSearchIndex/GitContextSourceProvider) ; Tarjan : project n'a aucune arête sortante, persistence.sqlite → {index, project, search, config, persistence} sans retour.
```

**Impact.** Ports IndexRepository/ProjectRepository/SearchIndex effectivement implémentés côté adaptateurs ; SQLite canonique et Lucene dérivé conformes à ADR-0006/0022.

**Action.** Aucune.

### AUD-ARC-18 — Pas de god object : fichier main le plus long 532 lignes (NexusApplication), façade à 31 membres publics

- **Axe** : Architecture · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
Commande : `find core/src/main adapters/*/src/main -name '*.java' | xargs wc -l | sort -rn` → 532 NexusApplication.java, 425 ProjectIndexingService.java, 391 LuceneSemanticSearchIndex.java ; aucune ligne > 200 caractères.
```

**Impact.** La décomposition en classes est fine (243 fichiers, ~760 lignes/package en moyenne) ; le couplage est un problème de dépendances entre packages, pas de taille de classes.

**Action.** Aucune.

### AUD-QUA-15 — Duplication globale faible et sans TODO/FIXME ni printStackTrace

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
Script de fenêtres de 8 lignes normalisées sur 243 fichiers / 26 383 lignes main : 45 fenêtres dupliquées inter-fichiers, dont 27 pour la paire AUD-QUA-08 ; suivantes : LuceneSemanticSearchIndex/PersistentLuceneSemanticSearchIndex (5), AiSkillsRegistryProvider/LocalAgentSkillsProvider (4). `grep -rnE 'TODO|FIXME|XXX|HACK'` → 0 ; `grep printStackTrace` → 0 ; aucun catch vide.
```

**Impact.** Base de code homogène et peu dupliquée.

**Action.** Aucune.

### AUD-QUA-16 — Logs : System.Logger dans le cœur, aucun System.out/err hors CLI

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
`grep -rnE 'System\.(out|err)'` hors /cli/ → 1 seul résultat, adapters/mcp-java/src/main/java/com/nexus/mcp/NexusMcpServer.java:46 (System.out passé au transport STDIO, légitime) ; System.Logger utilisé dans 8 classes du cœur (NexusPaths, SqliteDatabase, SqliteWriteRetryPolicy…) ; aucune dépendance slf4j/JUL.
```

**Impact.** Conforme à la règle java-core.

**Action.** Aucune.

### AUD-QUA-17 — Gestion d'erreurs larges globalement justifiée

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
11 `catch (Exception|Throwable|RuntimeException …)` en main ; frontières d'adaptateur (NexusCli.java:62, NexusMcpTools.java:236 avec safeMessage), agrégation `addSuppressed` (NexusApplication.java:441-446), rethrow enrichi (SkillFrontmatterParser.java:52-53), diagnostic (LocalGitContextSourceProvider.java:156-157). Les 10 `catch (… ignored)` sont commentés (ex. ProjectIndexLockManager.java:212-213 « Best effort lors d'un échec d'acquisition »). Seul écart : AUD-QUA-07.
```

**Impact.** Pas d'exception avalée sans intention documentée.

**Action.** Aucune.

### AUD-QUA-18 — Typage propre : aucun raw type, Optional limité aux retours, deux @SuppressWarnings justifiés

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
Recherche de raw types `(List|Map|Set|Optional|Class) x =` → 0 ; Optional en champ/paramètre → 0 (seul SqliteProjectRepository.java:141 en retour) ; `@SuppressWarnings` → 2 (SqliteBatchInsert.java:62 commenté, SafeFileIO.java:122 unchecked). `Map<String, Object>` : 79 occurrences concentrées dans les mappers de sortie (McpResultMapper 20, CliResultMapper 11, CliRenderer 11) — cf. AUD-QUA-09.
```

**Impact.** Typage fort dans le domaine ; typage faible confiné aux projections JSON.

**Action.** Aucune.

### AUD-QUA-19 — Taille des fichiers maîtrisée

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
`wc -l` main : 1 seul fichier > 500 lignes (core/src/main/java/com/nexus/application/NexusApplication.java = 532, composition root + façade), 16 fichiers > 300 lignes ; NexusCli 344, CliRenderer 379, SqlScriptSplitter 277, NexusMcpTools 279.
```

**Impact.** Pas de fichier démesuré.

**Action.** Surveiller NexusApplication (façade) ; ne rien découper sans besoin.

### AUD-QUA-20 — Configuration .claude/ hygiénique et exclue de l'indexation NEXUS

- **Axe** : Qualité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
.nexusignore exclut `/.claude/CLAUDE.md` (avec justification self-smoke étape 9) ; .claude/settings.json : allow limité à mvnw/git/gh en lecture, `ask` pour `gh pr merge|close`, deny sur .env/*.pem/*.key ; grep de chemins locaux/identifiants dans .claude → seul `C:\Program Files\Git\bin\bash.exe` dans un message d'aide (.claude/scripts/contracts.sh:12). contracts.sh rejoue exactement les 3 contrats du job Linux (ci.yml:63-79) : liste dupliquée à maintenir en double.
```

**Impact.** Pas de secret ni de chemin personnel ; seule dette : liste de contrats recopiée.

**Action.** Optionnel : faire lire la liste des contrats par la CI depuis contracts.sh pour éviter la double maintenance.

### AUD-QUA-31 — nexus-docker-up.cmd garde un message accentué écrit en ANSI : « prêt » affiché « prÛt » en console 850

- **Axe** : Qualité · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Nouveau · **Vérification du 2026-11-12** (`244ab1d3`) : Nouveau constat — Relevé à la vérification du 12/11.

**Preuve**

```text
packaging/windows/nexus-installer.iss.template:1411 'echo Docker Desktop n''est pas prêt…' écrit par SaveStringToFile (:1437, ANSI 1252) et lu par cmd.exe dans la page OEM ; les autres echo du même script sont volontairement sans accent (telechargement, privee, echouee).
```

**Impact.** Message d'erreur Docker légèrement corrompu ; aucun effet fonctionnel.

**Action.** Écrire « pret » comme les autres lignes, ou ajouter les lanceurs .cmd au contrôle d'ASCII.

### AUD-QUA-32 — workflow_hygiene forms : faux positif « key followed by a space » sur le texte d'un scalaire bloc autre que run:/script:

- **Axe** : Qualité, Dépendances · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Nouveau · **Vérification du 2026-11-12** (`244ab1d3`) : Nouveau constat — Relevé à la vérification du 12/11.

**Preuve**

```text
scripts/release/workflow_hygiene.py:65 SPACED_KEY, :238-241 (seules les lignes de blocs run:/script: sont exclues, block_text_lines :227-229). Reproduit : un step `with: previews: |` contenant « Note : texte libre » → « fx/a.yml:13: unsupported YAML form: key followed by a space before ':' », rc=1.
```

**Impact.** Un workflow valide portant du texte français (espace avant « : ») dans un with:/body: en scalaire bloc fait échouer le contrat hygiène (fail-closed) ; aucun risque de sécurité, blocage de PR.

**Action.** Exclure de forms les lignes de tout scalaire bloc (| ou >) quelle que soit la clé, ou n'appliquer SPACED_KEY qu'aux clés lues par les contrôles ; ajouter une fixture positive.

### AUD-SEC-09 — NXA-20260914-01 corrigé : les clés JSON/JS citées et les scalaires non cités sont redigés, index historiques invalidés par V009/V010

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.
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
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.
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
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.
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
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
adapters/rest-quarkus/src/main/java/com/nexus/api/NexusRestExposureGuard.java:83-90 (token requis sur loopback sauf opt-out) et :33-55 (hors loopback : token, allowlist, mode d'exposition, TLS effectif) ; NexusRestSecurity.java:221-232 `MessageDigest.isEqual` ; :185-192 ≥32 octets ; NexusRestTransportPolicy.java (insecure-requests=disabled, trusted-proxies non globaux) ; application.properties : `quarkus.http.host=127.0.0.1`, `max-body-size=1M`, `quarkus.management.host=127.0.0.1`.
```

**Impact.** Pas de faille distante tant que REST reste en configuration par défaut ; seules extensions Quarkus : rest-jackson, smallrye-health, micrometer (pas d'OpenAPI/Swagger exposé).

**Action.** Aucune.

### AUD-SEC-13 — Réponses d'erreur REST/MCP sans chemins ni stack traces ; chemins projet masqués

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
IllegalArgumentExceptionMapper.java:28-37 (message générique « Requête invalide » sauf 2 messages contrôlés) ; IOExceptionMapper.java:21 ; IllegalStateExceptionMapper.java:38-43 ; ApiMapper.java:38 `rootPath` renvoyé à null ; NexusMcpTools.java:260-272 safeMessage via PublicDiagnosticPolicy ; PublicDiagnosticPolicy.java:47-54 (file: URI et chemins absolus → [INTERNAL_PATH], puis redaction).
```

**Impact.** Pas d'exposition d'informations internes dans les cas mappés.

**Action.** Aucune (exceptions non mappées : voir angles morts).

### AUD-SEC-14 — SQL entièrement paramétré ; LIKE échappé avec ESCAPE et requêtes FTS5 citées trigramme par trigramme

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteIndexSql.java:56-69 (ftsTrigramQuery : `"` doublé ; escapeLike : \, %, _) ; SqliteSymbolSearch.java:58-66 et SqliteRelationQueries.java:70-96 `MATCH ?` / `LIKE ? ESCAPE '\\'` ; SqliteProjectRepository.java:67 `json_each(?)` ; seule concaténation : SqliteBatchInsert.java:65 (préfixe de table constant + placeholders).
```

**Impact.** Pas d'injection SQL ni d'injection d'opérateurs FTS depuis les requêtes clients.

**Action.** Aucune.

### AUD-SEC-15 — Lectures de fichiers projet bornées par ProjectPathGuard + SafeFileIO (NOFOLLOW, SecureDirectoryStream, revalidation) ; aucune lecture de contenu hors de ces garde-fous trouvée

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
grep `Files.read*|newInputStream|FileInputStream|Files.walk|Files.lines` hors SafeFileIO : uniquement readAttributes (ProjectScanner.java:139, ProjectIgnoreMatcher.java:163), walkFileTree sans FOLLOW_LINKS (ProjectScanner.java:93, InstructionDiscoverySupport.java:44/115, AiSkillsRegistryProvider.java:53, LocalAgentSkillsProvider.java:87/165), Files.list d'index internes/JDT plugins ; ProjectPathGuard.java:60-71 (symlink d'ancêtre refusé, toRealPath sous la racine) ; SafeFileIO.java:116-148 ; IndexDocumentPublisher.java:58-62 (relecture + vérification SHA-256 contre le scan).
```

**Impact.** Traversal/symlink/TOCTOU couverts pour le contenu indexé et le contexte ; SCIP lu via guard + SafeFileIO borné (ScipCodeIndexImporter.java:142-158).

**Action.** Aucune.

### AUD-SEC-16 — JDT LS : racine exacte approuvée, environnement réduit à une allowlist de 28 variables, URI non file: ou hors racine ignorées

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
core/src/main/java/com/nexus/index/jdt/ProcessBuilder.java:33-61 (allowlist), :79-96 (vérification de confiance juste avant start, re-sanitisation), :98-126 (égalité exacte du chemin réel) ; JdtProcessLauncher.java:16-37 (commande en liste, pas de shell) ; JdtLocationMapper.java:39-45 (scheme file, normalisé, sous projectRoot).
```

**Impact.** NEXUS_REST_API_TOKEN et autres secrets du processus ne sont pas transmis au subprocess qui peut exécuter des builds Maven/Gradle.

**Action.** Aucune.

### AUD-SEC-17 — Désérialisation sûre : pas de typage polymorphe Jackson, SnakeYAML Engine sans alias et borné

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
grep `DefaultTyping|JsonTypeInfo|ObjectInputStream|XMLDecoder|new Yaml` : 0 ; core/src/main/java/com/nexus/context/source/skill/SkillFrontmatterParser.java:34-38 `setAllowDuplicateKeys(false).setMaxAliasesForCollections(0).setCodePointLimit(65_536)` ; application.properties `quarkus.jackson.fail-on-unknown-properties=true` ; MINOS en streaming borné 128 MiB (MinosPayloadReader.java:57).
```

**Impact.** Pas de gadget de désérialisation ni de billion-laughs.

**Action.** Aucune.

### AUD-SEC-18 — Ollama : URL issue de la seule configuration (pas de SSRF client), credentials refusés, HTTP distant soumis à opt-in

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
core/src/main/java/com/nexus/search/semantic/SemanticSearchConfiguration.java:22,90 (NEXUS_OLLAMA_BASE_URL) ; OllamaEndpointResolver.java:70-90 ; aucune route REST/outil MCP ne modifie l'URL. Note : en Docker, une URL loopback est réécrite en http://host.docker.internal (:49-64) après validation, donc en clair sur le pont Docker sans opt-in.
```

**Impact.** Pas de SSRF pilotable par un client ; le trafic Docker→hôte en HTTP reste local à la machine.

**Action.** Aucune (éventuellement documenter la réécriture Docker).

### AUD-SEC-19 — Docker et CI : runtime non-root, bases épinglées par digest, actions tierces toutes épinglées par SHA, aucun pull_request_target

- **Axe** : Sécurité · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
packaging/docker/Dockerfile:3-4 (digests), :52 `USER 10001:10001` ; docker-compose.yml.template:36 token obligatoire `:?`, :42 publication `${NEXUS_DOCKER_BIND_ADDRESS:-127.0.0.1}`, :45 workspace `:ro` ; grep `uses:` sans SHA de 40 caractères → uniquement des workflows locaux `./.github/workflows/*` ; grep `pull_request_target|workflow_run|issue_comment` : 0 ; permissions `contents: read` par défaut (ex. ci.yml:15-16), écriture limitée à release.yml:167-170.
```

**Impact.** Surface supply-chain et conteneur maîtrisée.

**Action.** Aucune.

### AUD-SEC-21 — Risque accepté : les assets Windows de release ne sont pas signés Authenticode (décision SEC-0068), compensé par l'attestation SLSA et l'empreinte SHA-256

- **Axe** : Sécurité, Dépendances · **Effort** : M · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Nouveau · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
.github/workflows/release.yml:60-64 (require_authenticode: false, aucun secret) ; github.md : Get-AuthenticodeSignature setup.exe → NotSigned ; docs/user/windows-installation.md:28-34 (SmartScreen « éditeur inconnu », gh attestation verify, « Exécuter quand même ») ; docs/architecture/adr/SEC-0068-…md:34-36,45,92,108 (option C retenue, option E écartée pour son coût, risque « asset remplacé » évalué Moyen, conditions de réévaluation). L'installateur exige pourtant l'Authenticode des binaires tiers qu'il télécharge (nexus-installer.iss.template:395-433, Docker Inc. et Ollama Inc.). nexus.exe (lanceur jpackage) n'est pas signé non plus.
```

**Impact.** La provenance ne se vérifie que si l'utilisateur lance gh attestation verify (outil et compte GitHub requis). Le .sha256 publié au même endroit ne protège pas contre une substitution sur la release. Les utilisateurs apprennent à contourner SmartScreen. AppLocker ou WDAC en entreprise et certains antivirus bloquent les exécutables non signés. Le critère de sortie du sprint 9 (« artefacts … signés ») n'est tenu que par les attestations Sigstore. Pas de régression exploitable : setup per-user (PrivilegesRequired=lowest, iss:19), donc sans élévation.

**Action.** Garder le constat en risque accepté, sans le requalifier en Moyenne tant que le setup reste per-user. Publier dans la release une procédure de vérification sans gh, par exemple cosign/sigstore-verify ou empreintes reprises dans les notes de release signées. Réévaluer selon SEC-0068:108 (signature sans coût via Azure Trusted Signing ou SignPath OSS) avant une 1.0 ou une diffusion hors cercle restreint. Supprimer ou documenter comme non qualifié le chemin de signature mort (windows-installer.yml:84-119, sign-windows-artifact.ps1) s'il n'est pas exercé.

### AUD-SEC-22 — Le contrôle Host/Origin loopback est inactif dans l'image Docker (écoute 0.0.0.0) ; le jeton obligatoire compense

- **Axe** : Sécurité · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Nouveau · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
packaging/docker/Dockerfile.runtime:45 et Dockerfile:57 `NEXUS_REST_HOST=0.0.0.0` ; NexusRestSecurity.java:189-199 requiresLoopbackTransportControl → faux pour un hôte non loopback ; NexusRestLoopbackTransportFilter.java:64-67 `if (!active) return;`.
```

**Impact.** Le rebinding DNS reste bloqué par le token obligatoire hors loopback (requireRemoteTokenPolicy, docker-compose.yml.template:47 `:?`), donc pas d'exposition ; seule la défense en profondeur Host/Origin manque dans le déploiement Docker publié sur 127.0.0.1.

**Action.** Documenter l'écart dans rest-exposure-security.md, ou activer le contrôle Host en loopback-forward avec l'adresse et le port déclarés (NEXUS_DOCKER_HOST_FORWARD_ADDRESS).

### AUD-TST-12 — Build mesuré vert : 618 tests (519 core + 53 REST + 22 MCP + 24 assistant), 24 ignorés tous attendus, tous les jacoco:check passent

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
/home/claude/audit-work/run/mvn-verify.log:541 « Tests run: 519, … Skipped: 24 », :734 « 53 », :819 « 22 », :899 « 24 », :573-920 « All coverage checks have been met. » ; couverture core 83,8 %/67,7 %, REST 73,4 %/69,0 %, MCP 89,7 %/78,8 %, assistant 94,5 %/88,8 % (/home/claude/audit-work/run/summary.md).
```

**Impact.** Les 24 skips = 14 opt-in/benchmark + 10 tests POSIX (séparateur « / ») qui s'exécutent sur le job Linux (.github/workflows/ci.yml:49,84 ubuntu-latest ./mvnw -B clean install, sans filtre OS).

**Action.** Aucune ; vérifier dans le log Linux CI que le nombre de skips y est ≤ 15 (POSIX exécutés).

### AUD-TST-13 — Redaction et bornes bien testées : SensitiveContentRedactorTest 260 l. (JSON cités, quotes échappées, clés composées, clés privées, lookahead borné), SecretAssignmentScanner 121/132 branches

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java:28-33 (JSON + YAML commentaire), :85 « redactsQuotedJsonAndJavascriptKeysIncludingEscapedValues », :227 clés privées ; JdtJsonRpcFrameReaderTest.java:35 MAX_MESSAGE_BYTES+1 ; ScipIndexLimitsTest/ScipParseBudgetTest ; OllamaEmbeddingProviderResponseLimitTest ; NexusRestSecurity 91 %/80 %, NexusRestAuthenticationFilter 100 %.
```

**Impact.** Chemins secrets/token/bornes de protocole couverts ; trou non couvert : scalaires YAML multi-lignes (« password: | ») non testés (grep 0).

**Action.** Ajouter un cas YAML bloc littéral/plié et documenter le comportement attendu.

### AUD-TST-14 — Surefire sans exclusion de tags, failIfNoSpecifiedTests forcé en CI, CI ci.yml sur PR/push develop+main en Linux (JDK 21) et Windows (JDK 24)

- **Axe** : Tests · **Effort** : S<1j · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
pom.xml:153-160 (pas d'excludedGroups/groups) ; pom.xml:319-330 profil ci-strict-specified-tests (env.CI=true) ; .github/workflows/ci.yml:5-12, :25 windows-latest + validate-phase-6.ps1 (clean install + self-smoke), :49/:84 ubuntu ./mvnw -B clean install ; .claude/rules/ci-and-scripts.md : « Windows gate », « Linux reactor Maven build » requis.
```

**Impact.** Le lifecycle install inclut verify → jacoco:check bloquant sur les deux OS. Réserve : validate-phase-6.ps1:73-80 bascule sur un mvn système si le wrapper échoue ; le Windows gate n'exécute pas la suite sous JDK 21.

**Action.** Supprimer le fallback mvn système en CI ; envisager une matrice JDK 21 sur Windows.

### AUD-PRF-19 — Lots d'écriture SQLite multi-lignes bornés à 128 lignes, conformes à l'ADR 0051

- **Axe** : Performance · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteBatchInsert.java:26 `private static final int ROW_LIMIT = 128;`, :44-49 exécution par lot complet, transaction possédée par l'appelant.
```

**Impact.** Pas de N+1 à l'insertion des symboles/relations (embarqués et externes, SqliteProviderWriter.java:160-200 via map fileIds).

**Action.** Aucune.

### AUD-PRF-20 — Lectures du registre fédéré groupées (json_each, tranches de 500) conformément à l'ADR 0054

- **Axe** : Performance · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteProjectRepository.java:65-82 `WHERE id IN (SELECT value FROM json_each(?))` + `for (int offset = 0; offset < ids.size(); offset += 500)` ; findAll charge langages/technologies en 2 requêtes globales (:126-127).
```

**Impact.** Pas de N+1 sur le registre.

**Action.** Aucune.

### AUD-PRF-21 — Requêtes de graphe groupées par json_each et contraintes par index (project_id, kind, ref), avec test de plan

- **Axe** : Performance · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteGraphQueries.java:12-20 et :73-80 `INDEXED BY idx_symbol_relations_project_kind_target` ; V003:13-17 ; core/src/test/java/com/nexus/persistence/sqlite/SqliteGraphQueryPlanTest.java ; GraphCandidateEnricher.java:40 un seul findFiles groupé.
```

**Impact.** Le voisinage graphe ne fait pas de N+1 par candidat.

**Action.** Aucune.

### AUD-PRF-22 — Cache de searchers Lucene borné à 100 projets (= MAX_PROJECTS) sans writer retenu, rafraîchi après commit (ADR 0045)

- **Axe** : Performance · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
core/src/main/java/com/nexus/search/lucene/PersistentLuceneSearchIndex.java:24 `MAX_CACHED_PROJECTS = 100` ; BoundedLuceneSearcherCache.java:221-233 `maybeRefreshBlocking()` puis acquire/release ; PersistentLuceneSearchIndex.java:44-46 refreshIfCached après applyChanges, invalidate avant rebuild (:51-54).
```

**Impact.** Recherche lexicale sans ouverture de reader par requête dans les runtimes longue durée (hors AUD-PRF-04/05).

**Action.** Aucune ; à noter : pas d'éviction, un miss au-delà de la capacité retombe sur le chemin operation-scoped.

### AUD-PRF-23 — Embeddings d'indexation envoyés par lots (≤ 256 documents, budget de caractères) et fingerprint canonique mis en cache par génération

- **Axe** : Performance · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java:62 (batchSize 1–256), :141-159 boucle de lots `embeddingProvider.embedAll(...)` ; OllamaEmbeddingProvider.java:209-210 un seul POST `input: texts` ; SemanticSearchStrategy.java:189-197 cache `(generation, fingerprint)`.
```

**Impact.** Pas d'appel HTTP par document ; findFiles complet évité tant que la génération ne change pas.

**Action.** Aucune.

### AUD-PRF-24 — Une seule connexion SQLite réutilisée par requête applicative (session de lecture thread-local)

- **Axe** : Performance · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
core/src/main/java/com/nexus/persistence/sqlite/SqliteDatabase.java:48-62 (connexion empruntée via proxy, close neutralisé) et :65-88 ; NexusApplication.java:211, :231, :254, :282 `indexRepository.openReadSession()` autour de search/context (fédérés inclus).
```

**Impact.** Évite l'ouverture d'une connexion + PRAGMA + chargement du schéma par instruction.

**Action.** Aucune.

### AUD-PRF-25 — Lot de documents d'indexation borné (128 fichiers / 16 MiB) et temporaires SQLite en mémoire (ADR 0050)

- **Axe** : Performance · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
core/src/main/java/com/nexus/index/ProjectIndexingService.java:31-32 ; IndexDocumentPublisher.java:46-49 et :76-78 flush avant/après dépassement ; SqliteDatabase.java:105 `PRAGMA temp_store = MEMORY`.
```

**Impact.** Mémoire de contenu bornée pendant l'indexation (hors analyses, cf. AUD-PRF-12).

**Action.** Aucune.

### AUD-PRF-30 — La redaction enchaîne désormais 9 passes (un motif par famille) : surcoût mesuré de 30 à 60 % sur contenu ordinaire, à confirmer

- **Axe** : Performance · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Corrigé par la PR #369 · **Vérification du 2026-11-12** (`244ab1d3`) : Fermé — core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:82-85 STRUCTURED_TOKEN_PREFIXES, :167-171 passe sautée sans préfixe. Les 9 motifs (:67-76) sont sensibles à la casse (aucun (?i)) et commencent par ces littéraux exacts (gh[pousr]_ → 5 préfixes, (?:sk|rk)_ → sk_/rk_) ; contains() porte sur tout le texte courant, donc aucun préfixe coupé ; [REDACTED] n'en crée aucun. Différentiel avec/sans pré-filtre, 3×10 000 cas aléatoires (préfixes en casse mixte inclus, ~1 380 modifiés par lot) : 0 écart. Mesure 1 Mo, meilleur de 5 : code ordinaire 172 ms (510ff8ca : 285), Z continu 116 ms (222) ; 8 Mo de code : 1 318 ms. | Test actif : Oui : SensitiveContentRedactorTest.java:628 (#structuredTokenPrefixFilterKeepsEveryPassResult) ; RedactionThroughputBenchmarkTest opt-in (banc, non bloquant). | RedactionThroughputBenchmarkTest opt-in (ignoré au build par défaut) ; différentiel avec et sans pré-filtre sur 30 000 cas : 0 écart.

**Preuve**

```text
core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:53-62, :129-131 un replaceAll par famille au lieu d'une alternance unique (nexus-1109). Mesure 1 Mo de code : 285 ms (1111) contre 177 ms (1109) ; Z continu 188 contre 113 ms ; mesures sur 2 vCPU, indicatives.
```

**Impact.** Surcoût d'indexation et de construction de contexte proportionnel au volume, sans effet de sécurité.

**Action.** Mesurer dans le benchmark d'indexation ; si confirmé, pré-filtrer par indexOf des préfixes avant chaque passe.

### AUD-DEP-23 — Toutes les actions externes sont épinglées par SHA de 40 caractères vérifié, aucun pull_request_target, permissions read par défaut sur les 19 workflows

- **Axe** : Dépendances · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
Script YAML sur .github/workflows/*.yml : `unpinned=[]` pour les 19 fichiers, `perms={'contents': 'read'…}` au niveau workflow ; grep pull_request_target|workflow_run → aucun ; écritures (`contents/packages/id-token/attestations: write`) limitées au job publish (release.yml:167-171). SHA vérifiés par git ls-remote (checkout, upload/download-artifact, setup-java, attest, trivy-action, codeql-action).
```

**Impact.** Chaîne d'approvisionnement CI saine.

**Action.** Conserver ; ajouter un contrôle SHA ↔ commentaire (cf. AUD-DEP-10).

### AUD-DEP-24 — Wrapper Maven durci : archive 3.10.0 vérifiée par ancre SHA-512 versionnée et cache extrait revérifié

- **Axe** : Dépendances · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
mvnw:4-18,46,98 ; config/tool-integrity.properties:4 `maven.3.10.0.sha512=22d3…` (ancres jdtls et ollama l.5-6) ; distributionSha256Sum absent de .mvn/wrapper/maven-wrapper.properties mais non utilisé (wrapper maison).
```

**Impact.** Bootstrap Maven intègre ; Dependabot ne doit pas remplacer ces scripts (consigne .claude/skills/nexus-dependency-bump/SKILL.md:18).

**Action.** Conserver.

### AUD-DEP-25 — Images de base épinglées par digest, runtime JRE UBI10 minimal non-root, REST en loopback par défaut

- **Axe** : Dépendances · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
packaging/docker/Dockerfile:3-4 (`maven:3.9.11-…@sha256:6fdc…`, `eclipse-temurin:21.0.12_8-jre-ubi10-minimal@sha256:c7fe…`), :52 USER 10001 ; docker-compose.yml.template:35,41 (token requis, bind 127.0.0.1) ; Dependabot docker actif (.github/dependabot.yml:19-24).
```

**Impact.** Base saine.

**Action.** Conserver.

### AUD-DEP-26 — Dépendances majeures à jour au 17/10/2026 ; seul le SDK MCP a une mineure publiée depuis deux jours

- **Axe** : Dépendances · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
pom.xml:40-52 vs git ls-remote --tags : Quarkus 3.40.1, Lucene 10.5.2, sqlite-jdbc 3.53.4.0, Jackson 2.22.3, Micrometer 1.17.1, JavaParser 3.28.2, JGit 7.8.0.202609011348-r = derniers tags ; MCP SDK 2.0.1 utilisé, v2.1.0 taguée le 2026-10-15T19:05+02:00.
```

**Impact.** Pas de dette de version ; aucune CVE vérifiable faute d'accès API (angle mort).

**Action.** Laisser Dependabot proposer MCP SDK 2.1.0 et le qualifier.

### AUD-DEP-27 — Chaîne de release conçue fail-closed : tag = HEAD de main, tous les gates en needs, tags immuables, attestations provenance + SBOM, Trivy bloquant

- **Axe** : Dépendances · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

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
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
WebFetch …/actions/workflows/scale-benchmark.yml : #390 (PR #250, 16/10) et #389 verts ; Develop Push Scale Benchmark #57 vert ; scripts/verify-scale-budgets.py:128-129 plafonds inchangés (25 projets : search 160, context 260). | Vu aussi par AUD-PRF-26 : scripts/verify-scale-budgets.py:127-136 `25: {'search': 160, 'context': 260}` (plafonds identiques à ceux cités par l'audit du 14/09) ; FederatedSearchService.java:56 et FederatedContextService.java:78 toujours séquentiels ; seul le timeout du workflow sémantique réel a été fondé sur mesures (.githu | AUD-TST-05 (lecture statique, « toujours ouvert ») écarté : les runs réels sont verts.
```

**Impact.** Gate de charge à nouveau qualifiant.

**Action.** Aucune.

### AUD-DEP-29 — Observabilité en place : health liveness/readiness, Prometheus sur listener de management loopback, journalisation System.Logger

- **Axe** : Dépendances · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Aucun · **Vérification du 2026-11-12** (`244ab1d3`) : Tient — Relevé inchangé.

**Preuve**

```text
adapters/rest-quarkus/src/main/resources/application.properties:8-13 (management 127.0.0.1:9000, prometheus, binder http-server) ; adapters/rest-quarkus/src/main/java/com/nexus/api/NexusLivenessCheck.java, NexusReadinessCheck.java ; 9 usages System.getLogger dans core/adapters.
```

**Impact.** Exploitation outillée ; pas de configuration de format de logs (JSON) ni de niveau par environnement.

**Action.** Optionnel : quarkus-logging-json pour Docker.

### AUD-DEP-39 — Contrat de reprise de release : la regex de suppression/forçage de tag v* ignore le drapeau placé après le tag et l'API PATCH

- **Axe** : Dépendances · **Effort** : S · **Sprint** : hors sprint
- **Dépend de** : aucun · **Débloque** : aucun
- **Statut** : Info · **Changement** : Nouveau · **Vérification du 2026-11-12** (`244ab1d3`) : Nouveau constat — Relevé à la vérification du 12/11.

**Preuve**

```text
scripts/release/test-operational-doc-contracts.sh:1075-1081 : non détectés (vérifié) `git push origin v0.2.0 --force`, `git push origin refs/tags/v0.2.0 -f`, `git push origin --force --tags`, `gh api -X PATCH repos/o/r/git/refs/tags/v0.2.0 -F force=true`.
```

**Impact.** Une page docs/developer/ pourrait prescrire un déplacement de tag sans que le contrat échoue ; atténué côté serveur par le ruleset « Immutable version tags » sans contournement (si confirmé par gh).

**Action.** Accepter le drapeau avant ou après la référence, couvrir --tags/--mirror avec --force et PATCH|DELETE sur git/refs/tags ; ajouter ces échantillons à l'auto-test.
