# Audit NEXUS — 17 octobre 2026

**Verdict : socle sain et vérifié, dette concentrée sur cinq causes ; aucun constat critique ni élevé.** Le build complet passe, la CI de `develop` est verte, les constats de sécurité du 14 septembre sont corrigés. Aucun code applicatif n'a été modifié pendant l'audit. **Au 9 novembre**, 82 des 100 constats actionnables sont fermés ; un constat Élevé est apparu dans l'installateur Windows (`AUD-QUA-22`).

> **Vérification du 9 novembre 2026** (`origin/develop` @ `7bbfe675`, vérification dans le code) : 82 constats fermés sur 100 actionnables, dont 28 avec réserve ; 1 partiel ; 17 ouverts. Les sprints 2 à 5 et 9 à 11 sont clôturés ; les sprints 6, 7 et 8 le sont sauf pour des constats du 07/11. La clôture déclarée le 9 novembre (« 83 constats fermés ») ne voit pas les 10 constats ajoutés le 07/11 : la mise à jour du 07/11 était restée sur une branche locale. Aucun de ces 10 constats n'est corrigé. `AUD-TST-03` reste partiel, en désaccord avec la clôture déclarée. 9 constats nouveaux, dont `AUD-QUA-22` (Élevée, installateur Windows, reproduit sous PowerShell 5.1). Build réel : 971 tests verts. Détail : [`etat-2026-11-09.md`](etat-2026-11-09.md). **Contrôle de 22 h** (`bc707ed5`) : seule la version passe en 0.2.0-rc.3 (5 `pom.xml`) ; rc.3 publiée et `main` promue deux fois (PR #332, #334) sans correction des constats ouverts ; `AUD-DEP-34` corrigé dans sa formulation (tags attestés par l'ancienne chaîne, pas « sans attestation ») et toujours bloquant pour la release finale.
>
> **Clôture du 9 novembre 2026** (`origin/develop` @ `d78868ee`) : les 83 constats actionnables sont fermés, aucun partiel ni ouvert ; les 38 constats Info restent des relevés. Sprints 5 à 9 vérifiés contre le code de `develop` : sprint 5 par #297 (`AUD-PRF-01`, `-08`, `-11`, `-12`, `-14`, `-15`, `AUD-ARC-10`, `-13`), sprint 6 par #299 (`AUD-PRF-02`, `-03`, `-04`, `-06`, `-07`, `-09`, `AUD-QUA-10`), sprint 7 par #300 (`AUD-ARC-01`, `-02`, `-04`, `-05`, `-06` ; graphe des packages du cœur acyclique, gardé par `CoreArchitectureTest`), sprint 8 par #303 (`AUD-SEC-04`, `-05`, `AUD-ARC-09`, `-11`, `-12`, `-14`, `-19`), sprint 9 par #304 et la répétition de release `v0.2.0-rc.2` (`AUD-DEP-02`, `-03`, `-05`, `-06`, `-07`, `-09`, `-21`, `AUD-SEC-06`, `AUD-QUA-12`) ; sprints 10 et 11 par #321 à #329. `AUD-PRF-08` et `AUD-PRF-09` sont fermés par une décision mesurée et documentée (`docs/developer/indexing-pipeline.md`, `docs/developer/large-scale-search.md`). Preuves par constat : [`findings.json`](findings.json).
>
> **Mise à jour du 7 novembre 2026** (`origin/develop` @ `7c4c2619`, vérification dans le code, restée sur la branche locale `docs/audit-etat-2026-11-07`) : sprints 2 à 5 clôturés, sprint 1 à un constat près. 39 constats fermés, dont 14 avec réserve ; 4 partiels (`AUD-TST-03`, en désaccord avec la clôture déclarée, `AUD-ARC-01`, `AUD-DEP-17`, `AUD-DEP-31`) ; 10 constats nouveaux, dont `AUD-PRF-27` (Moyenne). Le sprint 5, fusionné par la PR #297 mais non déclaré dans le suivi, est vérifié fermé. Build réel : 840 tests verts. Détail : [`etat-2026-11-07.md`](etat-2026-11-07.md).
>
> **Mise à jour du 5 novembre 2026** (`origin/develop` @ `e6d81f8e`) : sprints 1 à 4 traités. Trente-deux constats fermés : `AUD-TST-01`, `AUD-TST-03` (#268), `AUD-QUA-06`, `AUD-DEP-20`, `AUD-TST-08` (#266), `AUD-TST-11` (#267, #277), `AUD-DEP-14` (#265, #278 ; « Hermetic scale benchmark » requis par le ruleset) et `AUD-DEP-30` (#276 ; alertes Dependabot, secret scanning et push protection activés) pour le sprint 1 ; `AUD-ARC-07`, `AUD-TST-07`, `AUD-QUA-07`, `AUD-TST-02`, `AUD-TST-06`, `AUD-TST-10` (#282) et `AUD-TST-09` (#285) pour le sprint 3 ; `AUD-QUA-04`, `AUD-QUA-05`, `AUD-SEC-07`, `AUD-DEP-10`, `AUD-DEP-11`, `AUD-DEP-12`, `AUD-DEP-15`, `AUD-DEP-22`, `AUD-DEP-19` (#281 ; GEN-0060 à accepter) pour le sprint 2 ; `AUD-QUA-08`, `AUD-QUA-09`, `AUD-ARC-03`, `AUD-QUA-01`, `AUD-SEC-03`, `AUD-SEC-02`, `AUD-SEC-01`, `AUD-PRF-18` (#284) pour le sprint 4. Statuts : [`findings.json`](findings.json).
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

Au 09/11, les notes sont recalculées sur les seuls constats non fermés ; un constat partiel compte à plein. Architecture et Sécurité (99) ne disent pas que tout est réglé : il reste un constat Faible dans chaque axe, et la note de Sécurité ne compte pas `AUD-QUA-22`, rangé en Qualité, qui touche pourtant les configurations des utilisateurs. Qualité (86) est la plus basse parce que ce seul constat Élevé pèse 10 points. Performance (91) contient deux régressions SQLite mesurées sur SQLite 3.45, pas sur le moteur embarqué.

| Axe | 17/10 | 29/10 | 07/11 | 09/11 | Calcul au 09/11 (constats non fermés) |
|---|---:|---:|---:|---:|---|
| Architecture | 78 | 78 | 87 | **99** | 100 − 1×1 Faible |
| Qualité | 72 | 72 | 90 | **86** | 100 − 10×1 Élevée − 1×4 Faible |
| Sécurité | 80 | 80 | 89 | **99** | 100 − 1×1 Faible |
| Tests | 82 | 82 | 93 | **93** | 100 − 4×1 Moyenne − 1×3 Faible |
| Performance | 71 | 71 | 76 | **91** | 100 − 4×2 Moyenne − 1×1 Faible |
| Dépendances | 73 | 72 | 82 | **96** | 100 − 1×4 Faible |

Barème : 100 − 25 par Critique − 10 par Élevée − 4 par Moyenne − 1 par Faible, plancher 0, sur l'axe principal de chaque constat (un constat fusionné ne compte qu'une fois).

Répartition : 140 constats — 1 élevée, 24 moyenne, 75 faible, 40 info ; 18 actionnables répartis en 13 sprints.

## Cinq constats majeurs restants

Les cinq majeurs du 17/10 (`AUD-QUA-09`, `AUD-SEC-01`, `AUD-TST-01`, `AUD-ARC-07`, `AUD-PRF-01`) sont fermés, trois d'entre eux avec réserve. Restent, au 09/11 :

1. **`AUD-QUA-22` — L'installateur réécrit les configurations MCP des clients IA sans sauvegarde : chemins accentués corrompus, JSON invalide remplacé par un fichier vide** (Élevée, Ouvert, effort S, S13). Sur un poste dont le nom d'utilisateur ou un chemin contient un accent (courant en français), connecter ou déconnecter NEXUS corrompt silencieusement les autres serveurs MCP déclarés dans claude_desktop_config.json et le mcp.json de Copilot JetBrains. Un fichier JSON invalide est remplacé par un fichier vide, sans sauvegarde, avec un code de sortie 0.
1. **`AUD-PRF-29` — La migration V015 (préfiltre approché scopé par fichier) ralentit la recherche de symboles d'un gros projet seul dans sa base : 0,10 → 0,36 s à 100 000 fichiers** (Moyenne, Ouvert, effort S, S6). Le cas mono-projet est l'usage principal. La recherche de symboles y coûte désormais de l'ordre du nombre de fichiers du projet par terme, alors qu'avant elle coûtait de l'ordre du nombre de candidats. On gagne en fédération et on perd au plafond de 100 000 fichiers. Aucun benchmark mono-projet ne compare avant et après V015.
1. **`AUD-PRF-27` — La publication du staging pagine index_staged_files sans index (project_id, id) : coût quadratique, 5,6 s à 100 000 fichiers sous verrou d'écriture** (Moyenne, Ouvert, effort S, S6). Au plafond par défaut (100 000 fichiers), la transaction de publication tient le verrou d'écriture SQLite plusieurs secondes de plus (sur disque, sans compter les copies). C'est de l'ordre du busy_timeout de 5 s × 2 tentatives (SqliteWriteRetryPolicy) : les autres écrivains, dont l'indexation d'un autre projet, peuvent échouer en SQLITE_BUSY. Au plafond dur (1 000 000), on extrapole à quelques centaines de secondes. Cela contredit l'objectif de DATA-0062 de « ne jamais tenir le verrou pendant toute l'indexation ». Aucun benchmark ne l'observe : le scénario de réindexation et singleWriteTransactionMs mesurent applyChanges.
1. **`AUD-TST-03` — Gates JaCoCo au seul niveau BUNDLE, seuils effectifs différents de la doc : 6 classes de sécurité ou de bornes sous 55 % de branches passent sans alerte** (Moyenne, Partiel, effort S, S1). Marge core 83,8 %/67,7 % vs 70 %/55 % : on peut perdre ~1 380 lignes couvertes avant que le gate ne réagisse ; le gate ne protège pas les paquets security/index.jdt/index.minos.
1. **`AUD-DEP-34` — Des tags GHCR :0.2.0 et :latest posés le 9 août par l'ancienne chaîne Docker existent hors de la release et bloqueront le préflight immuable de la release finale v0.2.0** (Faible, Ouvert, effort S, S13). Le préflight immuable de la release finale v0.2.0 (ghcr-immutable-preflight.sh) trouvera un tag :0.2.0 déjà occupé par une image d'août, construite hors de la chaîne actuelle ; un utilisateur qui tire :latest ou :0.2.0 aujourd'hui reçoit cette image ancienne au lieu de la dernière pré-release.

## Thèmes transverses

- **Des gates qui mesurent moins que leur nom.** Couverture REST qui ne crédite pas le bean central, seuils au seul niveau du module, gate Windows qui retombe sur le Maven du runner, tests symlink ignorés sans alerte, benchmark d'échelle non requis, alertes GitHub désactivées. Chaque gate est vert, mais certains le resteraient après une régression. D'où le sprint 1 avant tout le reste. Au 07/11, les gates sont réparés (sprints 1 et 2) ; restent la couverture par classe (AUD-TST-03) et des contournements sur des variantes (sprint 12). Au 09/11, le sprint 12 n'a pas commencé : les contournements restent possibles. (`AUD-TST-03`, `AUD-DEP-32`, `AUD-QUA-21`, `AUD-TST-01`, `AUD-QUA-06`…)
- **Une sortie publique écrite trois fois.** CLI, MCP et REST appliquent chacun la politique de sortie et projettent les mêmes objets différemment : ordre des scores instable, signatures non redigées, records recopiés. La redaction elle-même est une liste fermée qui laisse passer 19 formats courants et fait échouer tout un projet sur une valeur trop longue. Au 07/11, la projection est unique et la redaction complétée (sprint 4 clôturé) ; reste une borne sk- désalignée (AUD-SEC-20). (`AUD-SEC-20`, `AUD-QUA-09`, `AUD-QUA-01`, `AUD-SEC-03`, `AUD-ARC-03`…)
- **Des coûts répétés par projet et par fichier.** Le chemin multi-projets refait à chaque projet ce qui pourrait l'être une fois (vecteur de requête, DirectoryReader, historique Git) et l'indexation supprime les relations d'un fichier sans index. Aucun budget n'est dépassé aujourd'hui ; ces coûts grandissent avec la taille des dépôts et le nombre de projets fédérés. Au 07/11, l'indexation est corrigée (sprint 5), au prix d'une pagination quadratique du staging (AUD-PRF-27) ; la fédération (sprint 6) reste à faire. Au 09/11, la fédération est corrigée (sprint 6) ; deux régressions SQLite restent mesurées (AUD-PRF-27, AUD-PRF-29). (`AUD-PRF-29`, `AUD-PRF-27`, `AUD-PRF-28`, `AUD-PRF-01`, `AUD-PRF-02`…)
- **Des frontières logiques que rien n'outille.** Les dépendances techniques sont bien confinées (Lucene, SQLite, JGit), mais 12 packages du cœur se dépendent mutuellement et aucun test d'architecture ne l'empêche de s'étendre. La configuration est lue dans 12 classes au lieu de la composition root. Au 07/11, le test d'architecture existe et le bloc est scindé en deux (22 arêtes de cycle au lieu de 36), mais il ne voit pas les références qualifiées (AUD-ARC-20). Au 09/11, le cœur est acyclique ; reste à faire voir au test les références qualifiées (AUD-ARC-20). (`AUD-ARC-20`, `AUD-ARC-07`, `AUD-ARC-01`, `AUD-ARC-02`, `AUD-ARC-04`…)
- **Une chaîne de release jamais exécutée.** La release est conçue en échec fermé (tag égal à main, attestations, Trivy, Authenticode), mais elle n'a jamais tourné : aucun tag, aucun environnement protégé, une clé de signature visible de toutes les étapes du job Windows, une version codée en dur. Le premier run réel sera aussi le premier test. Au 07/11, rien n'a bougé : toujours aucun run de release, aucun environnement protégé. Au 09/11, la pré-release rc.2 est publiée depuis un environnement protégé, avec des assets attestés mais non signés ; la release finale reste à exercer (AUD-DEP-34). (`AUD-DEP-34`, `AUD-SEC-21`, `AUD-DEP-02`, `AUD-SEC-06`, `AUD-DEP-03`…)

## Sprints au 9 novembre

| Sprint | Titre | État | Fermés | Reste (j) | Critère de sortie |
|---|---|---|---:|---|---|
| S1 | Remettre les mesures d'aplomb | Entamé | 7/8 | 0,25 à 1 | NexusApiApplicationService est crédité par la couverture REST ; une règle par classe existe pour les classes de bornes listées ; le gate Windows échoue si le wrapper échoue ; un runner sans symlinks échoue au lieu d'ignorer ; les alertes Dependabot et le secret scanning sont actifs. |
| S2 | Outillage des scripts et des workflows | Clôturé | 9/9 | 0 à 0 | Un seul module PowerShell appelle Maven, via le wrapper ; aucun input n'est interpolé dans un bloc run: ; chaque job a un timeout ; la qualification sémantique réelle termine sur develop. |
| S3 | Filets de test et traces | Clôturé | 7/7 | 0 à 0 | Le test d'architecture échoue sur un import interdit ; NexusResourceTest et NexusMcpServerIntegrationTest ont un @Test par contrat ; GitRecencyCandidateEnricher journalise ce qu'il attrape ; ProjectPathGuard a sa classe de test. |
| S4 | Une seule sortie publique, redaction complétée | Clôturé | 8/8 | 0 à 0 | Un test de parité compare les clés produites par CLI, MCP et REST ; la sonde de 35 formats est versionnée en test et passe pour les 19 formats retenus ; un fichier portant une valeur sensible de plus de 4 096 caractères est exclu seul, le reste du projet s'indexe. |
| S5 | Indexation et écriture SQLite | Clôturé | 8/8 | 0 à 0 | Le plan du DELETE de symbol_relations par file_id utilise un index (test de plan) ; un benchmark couvre la réindexation de fichiers existants ; toute écriture d'index, MINOS compris, passe par ProjectIndexingService. |
| S6 | Fédération et runtimes longue durée | Entamé | 7/11 | 1 à 4 | Une requête fédérée sur N projets calcule un seul vecteur de requête, n'ouvre aucun DirectoryReader hors cache et respecte une échéance globale qui nomme les projets non servis. |
| S7 | Casser le bloc cyclique du cœur | Entamé | 5/7 | 0,5 à 2 | Le graphe des packages du cœur est acyclique et le test d'architecture l'impose (AUD-ARC-01, constat chapeau). |
| S8 | Façade, configuration et durcissements locaux | Entamé | 7/8 | 0,25 à 1 | La façade n'expose plus SQLException ; System.getenv n'apparaît plus que dans la composition root ; le mode trust-local refuse un Host ou une Origin non loopback ; la découverte Git s'arrête à la racine du projet. |
| S9 | Chaîne de release exécutable | Clôturé | 9/9 | 0 à 0 | Un tag de pré-version produit, depuis un environnement protégé, des artefacts attestés et signés, sans que la clé soit visible hors de l'étape de signature ni la version codée en dur. |
| S10 | Conteneur et gouvernance du dépôt | Clôturé | 7/7 | 0 à 0 | L'image n'est pas modifiable par son UID d'exécution et embarque licences et labels OCI ; le ruleset n'autorise que le merge commit ; SECURITY.md et CODEOWNERS existent. Une montée de version Dependabot ne casse aucun contrat documentaire. |
| S11 | Finitions documentaires et micro-optimisations | Clôturé | 8/8 | 0 à 0 | Les quatre dérives relevées (graphe dérivé, FTS trigram, AGENTS.md, décision WAL) sont corrigées ; aucune regex des deux chemins relevés n'est compilée par appel. |
| S12 | Durcir les gates ajoutés | À faire | 0/5 | 1,25 à 5 | Chaque gate ajouté échoue sur les variantes relevées (fixtures workflow_hygiene, mutations du contrat Maven, tag amont faux) ; les règles Archgate tournent en CI ; les planchers JaCoCo sont à moins de 3 points de la mesure. |
| S13 | Installateur et release finale | À faire | 0/5 | 1,25 à 5 | Connecter puis déconnecter NEXUS laisse intacts octet pour octet les autres serveurs d'une configuration UTF-8 contenant des chemins accentués, et refuse d'écrire sur un JSON invalide (tests de l'installateur) ; aucun tag GHCR hors chaîne de release ; labels OCI renseignés. |

Charge restante : 4,5 à 18 jours, enveloppe hors revue, CI et ADR. Détail et ordre interne : [`sprints.md`](sprints.md).

## Chemin critique

- Livré : tous les prérequis du 17/10 et du 07/11 ; le cœur est acyclique (`AUD-ARC-01`) et la pré-release 0.2.0-rc.2 est publiée (`AUD-DEP-02`).
- Avant la release finale v0.2.0 : `AUD-QUA-22` (installateur, Élevée) et `AUD-DEP-34` (tags GHCR hors chaîne) ; puis `AUD-ARC-20`, `AUD-PRF-27`, `AUD-PRF-29`.

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
- 07/11 : plans SQLite de AUD-PRF-27 et AUD-PRF-01 rejoués sur SQLite 3.45 (Python), pas sur le 3.54 embarqué par sqlite-jdbc.
- 07/11 : IndexingHeapPeakBenchmarkTest n'est lancé par aucun workflow ; les mesures de heap et de hachage viennent d'un poste Windows chargé, pas du runner de référence.
- 07/11 : les 40 règles Archgate n'ont pas été exécutées (aucun outillage épinglé dans le dépôt) ; les scripts PowerShell n'ont pas été lancés hors du build.
- 07/11 : un symbole de fragment plus long que la limite de 1 Mio des diagnostics publics ferait échouer toute la réponse de contexte ; jugé irréaliste, non retenu comme constat.
- 09/11 : plans et mesures SQLite (AUD-PRF-27, AUD-PRF-29, AUD-PRF-09) faits sur SQLite 3.45 en mémoire, pas sur le 3.54 embarqué.
- 09/11 : défaut de l'installateur rejoué hors installateur (script extrait du gabarit), pas sur une installation complète ; Copilot JetBrains non testé.
- 09/11 : CI de 7bbfe675 partiellement en cours au moment du relevé GitHub ; chemin de release finale (latest) jamais exercé.
- 09/11 : graphe de packages recalculé par analyse du texte, sans compilation ; adaptateurs non analysés.

## Fichiers

- [`findings.md`](findings.md) / [`findings.json`](findings.json) — tous les constats, avec sprint et dépendances.
- [`sprints.md`](sprints.md) / [`sprints-meta.json`](sprints-meta.json) — ordre de correction.
- [`architecture.md`](architecture.md) — découpage observé et diagramme.
- [`plan-action.md`](plan-action.md) — classement par urgence.
- [`etat-2026-11-09.md`](etat-2026-11-09.md) — vérification des sprints 6 à 11, des constats du 07/11 et nouveaux constats.
- [`etat-2026-11-07.md`](etat-2026-11-07.md) — vérification des sprints 1 à 5 dans le code.
- [`etat-2026-10-29.md`](etat-2026-10-29.md) — première vérification, aucun constat fermé.
