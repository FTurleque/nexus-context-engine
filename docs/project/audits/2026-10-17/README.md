# Audit NEXUS — 17 octobre 2026

**Verdict : socle sain et vérifié, dette concentrée sur cinq causes ; aucun constat critique ni élevé.** Le build complet passe, la CI de `develop` est verte, les constats de sécurité du 14 septembre sont corrigés. Aucun code applicatif n'a été modifié pendant l'audit.

> **Mise à jour du 30 octobre 2026** (`origin/develop` @ `6dc03c2d`) : sprints 1, 2 et 3 traités. Vingt et un constats fermés : `AUD-TST-01`, `AUD-TST-03` (#268), `AUD-QUA-06`, `AUD-DEP-20`, `AUD-TST-08` (#266), `AUD-TST-11` (#267, #277) pour le sprint 1 ; `AUD-ARC-07`, `AUD-TST-07`, `AUD-QUA-07`, `AUD-TST-02`, `AUD-TST-06`, `AUD-TST-10` (#282) pour le sprint 3 ; `AUD-QUA-04`, `AUD-QUA-05`, `AUD-SEC-07`, `AUD-DEP-10`, `AUD-DEP-11`, `AUD-DEP-12`, `AUD-DEP-15`, `AUD-DEP-22`, `AUD-DEP-19` (#281 ; GEN-0060 à accepter) pour le sprint 2, dont `AUD-TST-09` reste ouvert. Deux partiels, en attente d'un réglage GitHub du mainteneur : `AUD-DEP-14` (#265, #278 ; ajout de « Hermetic scale benchmark » au ruleset) et `AUD-DEP-30` (procédure dans `SECURITY.md`, #276 ; activation des alertes). Statuts : [`findings.json`](findings.json).
>
> **Mise à jour du 29 octobre 2026** (`origin/develop` @ `0b6828ce`) : aucun constat fermé ni partiel. Seule la PR #254 a été fusionnée (configuration du superviseur de revue, exclusion des worktrees) ; elle ajoute des règles de revue qui préviennent trois constats pour le code futur sans corriger l'existant. Un constat nouveau, `AUD-DEP-31`. Détail : [`etat-2026-10-29.md`](etat-2026-10-29.md).

## Contexte

- Commit : `origin/develop` @ `98b1f3f01b7b60f454b8028d292a6e28faca34b2` (PR #253), version 0.2.0. L'arbre local (`chore/claude-code-config`, supprimée côté GitHub, 21 commits de retard) n'a pas servi.
- Profil **complet**, six axes. Code : 243 fichiers de production (26 383 lignes), 181 fichiers de test, 4 modules Maven, 19 workflows, 55 ADR.
- Exécution réelle : `mvnw.cmd -B -ntp clean verify` sous Windows avec Microsoft OpenJDK 21.0.12.1 → BUILD SUCCESS en 2 min 31 s, 618 tests verts, 24 ignorés attendus (opt-in et POSIX), tous les `jacoco:check` atteints. Couverture lignes/branches : core 83,8/67,7 %, REST 73,4/69,0 %, MCP 89,7/78,8 %, assistant 94,5/88,8 %.
- GitHub lu en lecture seule avec `gh` : ruleset, alertes, environnements, derniers runs.
- Méthode : lecture directe du code, outils du dépôt, documentation à jour via Context7 ; pas de serveur d'indexation. Six sous-audits en parallèle, consolidation, puis relecture contradictoire par un agent tiers (49 changements retenus, dont 2 Élevées ramenées en Moyenne et 6 constats de performance déjà assumés par des ADR).

## Notes par axe

Deux réserves avant de lire ces notes. Performance (71) repose sur des coûts lus dans le code, pas chronométrés ; les choix déjà assumés par un ADR (journal rollback, cache Git de 16 entrées, lecture intégrale des fichiers) ont été ramenés en Faible. Architecture (78) compte séparément les cinq cycles d'un même bloc ; le constat chapeau AUD-ARC-01 n'est pas compté une seconde fois. Aucune note ne descend sous 70 parce qu'aucun constat critique ni élevé n'a tenu après vérification.

| Axe | 17/10 | 29/10 | Calcul au 29/10 |
|---|---:|---:|---|
| Architecture | 78 | **78** | 100 − 4×3 Moyenne − 1×10 Faible |
| Qualité | 72 | **72** | 100 − 4×5 Moyenne − 1×8 Faible |
| Sécurité | 80 | **80** | 100 − 4×4 Moyenne − 1×4 Faible |
| Tests | 82 | **82** | 100 − 4×3 Moyenne − 1×6 Faible |
| Performance | 71 | **71** | 100 − 4×4 Moyenne − 1×13 Faible |
| Dépendances | 73 | **72** | 100 − 4×2 Moyenne − 1×20 Faible |

Barème : 100 − 25 par Critique − 10 par Élevée − 4 par Moyenne − 1 par Faible, plancher 0, sur l'axe principal de chaque constat (un constat fusionné ne compte qu'une fois).

Répartition : 121 constats — 22 moyenne, 61 faible, 38 info ; 83 actionnables répartis en 11 sprints.

## Cinq constats majeurs

1. **`AUD-QUA-09` — Pas de projection publique unique : CLI, MCP et REST réappliquent chacun la politique de sortie et divergent sur les mêmes objets** (Moyenne, effort M, S4). La règle de parité CLI/REST/MCP (.claude/rules/adapters.md) dépend d'une relecture manuelle de 3 mappers (103 + 159 + 180 lignes) ; AUD-QUA-01 en est une conséquence.
1. **`AUD-SEC-01` — La redaction, liste fermée documentée comme non exhaustive, ne couvre pas 19 formats de secrets courants (Bearer, token=, sk-, xoxb-, AIza, glpat-, PGP…)** (Moyenne, effort M, S4). Les jetons de ces formats présents dans le code ou la documentation d'un projet indexé sortent en clair dans les contextes CLI/REST/MCP et vers un Ollama distant éventuel. C'est une limite de couverture assumée (secret-redaction.md:7-10, current-limitations.md:114), pas une régression.
1. **`AUD-TST-01` — NexusApiApplicationService, bean CDI exercé par les @QuarkusTest, n'est crédité qu'à 13 lignes sur 95 par l'agent JaCoCo seul** (Moyenne, effort S, S1). Le gate REST (73,4 % mesurés) sous-estime la classe centrale de l'adaptateur : une régression de NexusApiApplicationService ne fait pas bouger la mesure, et la refonte AUD-QUA-08 n'est pas vérifiable par la couverture.
1. **`AUD-ARC-07` — Aucune protection structurelle des frontières : pas de test d'architecture, pas de module-info, 160/219 types publics** (Moyenne, effort S, S3). Les règles « ports/adaptateurs », « core sans framework », « Lucene/SQLite confinés » ne reposent que sur la discipline ; la SCC d'AUD-ARC-01 montre l'érosion déjà acquise ; un adaptateur peut instancier n'importe quel service interne (ex. tests REST/MCP qui construisent SqliteDatabase).
1. **`AUD-PRF-01` — Aucun index sur symbol_relations(file_id) : chaque fichier réindexé ou supprimé déclenche un SCAN complet de la table (tous projets)** (Moyenne, effort S, S5). Coût O(fichiers modifiés × relations de toute la base) dans la transaction d'indexation : un rebuild complet (fullRebuild ⇒ tous les fichiers passent par replaceAnalysis) ou une suppression massive devient quadratique (déduit du plan de requête, non mesuré) et s'aggrave avec le nombre de projets fédérés partageant la base. Non couvert par les benchmarks : ScaleRegressionBenchmarkTest.java:404-432 peuple par INSERT directs, sans passer par applyChanges sur données existantes.

## Thèmes transverses

- **Des gates qui mesurent moins que leur nom.** Couverture REST qui ne crédite pas le bean central, seuils au seul niveau du module, gate Windows qui retombe sur le Maven du runner, tests symlink ignorés sans alerte, benchmark d'échelle non requis, alertes GitHub désactivées. Chaque gate est vert, mais certains le resteraient après une régression. D'où le sprint 1 avant tout le reste. (`AUD-TST-01`, `AUD-TST-03`, `AUD-QUA-06`, `AUD-TST-11`, `AUD-TST-08`…)
- **Une sortie publique écrite trois fois.** CLI, MCP et REST appliquent chacun la politique de sortie et projettent les mêmes objets différemment : ordre des scores instable, signatures non redigées, records recopiés. La redaction elle-même est une liste fermée qui laisse passer 19 formats courants et fait échouer tout un projet sur une valeur trop longue. (`AUD-QUA-09`, `AUD-QUA-01`, `AUD-SEC-03`, `AUD-ARC-03`, `AUD-QUA-08`…)
- **Des coûts répétés par projet et par fichier.** Le chemin multi-projets refait à chaque projet ce qui pourrait l'être une fois (vecteur de requête, DirectoryReader, historique Git) et l'indexation supprime les relations d'un fichier sans index. Aucun budget n'est dépassé aujourd'hui ; ces coûts grandissent avec la taille des dépôts et le nombre de projets fédérés. (`AUD-PRF-01`, `AUD-PRF-02`, `AUD-PRF-04`, `AUD-PRF-07`, `AUD-PRF-06`…)
- **Des frontières logiques que rien n'outille.** Les dépendances techniques sont bien confinées (Lucene, SQLite, JGit), mais 12 packages du cœur se dépendent mutuellement et aucun test d'architecture ne l'empêche de s'étendre. La configuration est lue dans 12 classes au lieu de la composition root. (`AUD-ARC-07`, `AUD-ARC-01`, `AUD-ARC-02`, `AUD-ARC-04`, `AUD-ARC-05`…)
- **Une chaîne de release jamais exécutée.** La release est conçue en échec fermé (tag égal à main, attestations, Trivy, Authenticode), mais elle n'a jamais tourné : aucun tag, aucun environnement protégé, une clé de signature visible de toutes les étapes du job Windows, une version codée en dur. Le premier run réel sera aussi le premier test. (`AUD-DEP-02`, `AUD-SEC-06`, `AUD-DEP-03`, `AUD-DEP-21`, `AUD-DEP-09`…)

## Sprints

| Sprint | Titre | Constats | Charge (j) | Critère de sortie |
|---|---|---:|---|---|
| S1 | Remettre les mesures d'aplomb | 8 | 2 à 8 | NexusApiApplicationService est crédité par la couverture REST ; une règle par classe existe pour les classes de bornes listées ; le gate Windows échoue si le wrapper échoue ; un runner sans symlinks échoue au lieu d'ignorer ; les alertes Dependabot et le secret scanning sont actifs. |
| S2 | Outillage des scripts et des workflows | 9 | 3,75 à 13 | Un seul module PowerShell appelle Maven, via le wrapper ; aucun input n'est interpolé dans un bloc run: ; chaque job a un timeout ; la qualification sémantique réelle termine sur develop. |
| S3 | Filets de test et traces | 7 | 1,75 à 7 | Le test d'architecture échoue sur un import interdit ; NexusResourceTest et NexusMcpServerIntegrationTest ont un @Test par contrat ; GitRecencyCandidateEnricher journalise ce qu'il attrape ; ProjectPathGuard a sa classe de test. |
| S4 | Une seule sortie publique, redaction complétée | 8 | 3,5 à 12 | Un test de parité compare les clés produites par CLI, MCP et REST ; la sonde de 35 formats est versionnée en test et passe pour les 19 formats retenus ; un fichier portant une valeur sensible de plus de 4 096 caractères est exclu seul, le reste du projet s'indexe. |
| S5 | Indexation et écriture SQLite | 8 | 4,25 à 14 | Le plan du DELETE de symbol_relations par file_id utilise un index (test de plan) ; un benchmark couvre la réindexation de fichiers existants ; toute écriture d'index, MINOS compris, passe par ProjectIndexingService. |
| S6 | Fédération et runtimes longue durée | 7 | 4 à 13 | Une requête fédérée sur N projets calcule un seul vecteur de requête, n'ouvre aucun DirectoryReader hors cache et respecte une échéance globale qui nomme les projets non servis. |
| S7 | Casser le bloc cyclique du cœur | 5 | 4,75 à 14 | Le graphe des packages du cœur est acyclique et le test d'architecture l'impose (AUD-ARC-01, constat chapeau). |
| S8 | Façade, configuration et durcissements locaux | 7 | 2,5 à 9 | La façade n'expose plus SQLException ; System.getenv n'apparaît plus que dans la composition root ; le mode trust-local refuse un Host ou une Origin non loopback ; la découverte Git s'arrête à la racine du projet. |
| S9 | Chaîne de release exécutable | 9 | 3 à 11 | Un tag de pré-version produit, depuis un environnement protégé, des artefacts attestés et signés, sans que la clé soit visible hors de l'étape de signature ni la version codée en dur. |
| S10 | Conteneur et gouvernance du dépôt | 7 | 1,75 à 7 | L'image n'est pas modifiable par son UID d'exécution et embarque licences et labels OCI ; le ruleset n'autorise que le merge commit ; SECURITY.md et CODEOWNERS existent. Une montée de version Dependabot ne casse aucun contrat documentaire. |
| S11 | Finitions documentaires et micro-optimisations | 8 | 2,75 à 10 | Les quatre dérives relevées (graphe dérivé, FTS trigram, AGENTS.md, décision WAL) sont corrigées ; aucune regex des deux chemins relevés n'est compilée par appel. |

Charge totale : 34 à 118 jours, enveloppe hors revue, CI et ADR. Détail et ordre interne : [`sprints.md`](sprints.md).

## Chemin critique

- `AUD-ARC-07` (S, sprint 3) : un test d'architecture conditionne les cinq corrections de cycles du sprint 7.
- `AUD-TST-07` (S, sprint 3) : découper les deux tests E2E monolithiques rend vérifiable la projection publique unique (`AUD-QUA-09`), qui conditionne à son tour `AUD-ARC-03` et `AUD-ARC-01`.
- `AUD-TST-01` (S, sprint 1) : tant que `NexusApiApplicationService` n'est pas crédité, la refonte du service REST (`AUD-QUA-08`) n'est pas mesurable.

## Suivi de l'audit du 14 septembre

| Constat | État au 17/10 | Preuve |
|---|---|---|
| NXA-20260914-01 secrets sous clés JSON citées | Corrigé | `AUD-SEC-09` : SecretAssignmentScanner.java:43-73, POLICY_VERSION v5, migrations V009/V010 |
| NXA-20260914-02 timeout Ollama sur le corps | Corrigé | `AUD-SEC-10` : OllamaEmbeddingProvider.java:219-245 |
| NXA-20260914-03 comptes Windows localisés | Corrigé | `AUD-SEC-11` : WindowsStoragePrincipals.java:21-48, résolution par SID |
| NXA-20260914-04 charge à 25 projets | Résolu | `AUD-DEP-28` : Scale Benchmark #389/#390 et Develop Push #57 verts, plafonds inchangés |
| NXA-20260914-05 dérive documentaire | Partiel | `AUD-QUA-13` : FTS trigram encore présenté comme futur |

## Angles morts

- Pas de graphe d'appels complet : les dépendances sont reconstruites à partir des imports, au niveau package ; le code atteint par réflexion, ServiceLoader ou injection CDI est mal couvert.
- Modules hors du cœur survolés : index.jdt, index.scip et les scripts de release ont été lus de façon ciblée, pas ligne à ligne.
- Comportement à l'exécution non observé : aucun appel REST ou MCP réel, aucune mesure de performance ; les coûts de l'axe Performance sont déduits du code et des plans de requête (SQLite 3.45 en Python, pas la version embarquée).
- Build et tests exécutés sous Windows uniquement ; les 10 tests POSIX ignorés ici tournent sur le job Linux de la CI, dont le log n'a pas été lu.
- Pas de CVE vérifiable : les alertes Dependabot sont désactivées sur le dépôt (AUD-DEP-30) et Maven Central était inaccessible depuis l'espace d'audit ; l'arbre transitif et les licences effectives ne sont pas vérifiés.
- La cause de la sous-couverture de NexusApiApplicationService (AUD-TST-01) est probable, pas démontrée.
- Les 55 ADR n'ont pas été relus un par un ; seuls ceux cités par les constats ont été confrontés au code.

## Fichiers

- [`findings.md`](findings.md) / [`findings.json`](findings.json) — tous les constats, avec sprint et dépendances.
- [`sprints.md`](sprints.md) / [`sprints-meta.json`](sprints-meta.json) — ordre de correction.
- [`architecture.md`](architecture.md) — découpage observé et diagramme.
- [`plan-action.md`](plan-action.md) — classement par urgence.
- [`etat-2026-10-29.md`](etat-2026-10-29.md) — vérification du travail fait depuis l'audit.
