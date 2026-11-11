# Ordre de correction — audit du 17 octobre 2026

Le [plan d'action](plan-action.md) classe par urgence ; ce document classe par **ordre d'exécution**. Quand les deux divergent, c'est cet ordre qui s'applique : une correction dont le prérequis n'est pas livré ne peut pas être vérifiée.

Principes : le dispositif avant ce qu'il mesure ; l'observabilité avant les corrections de fond ; les mêmes fichiers dans des sprints successifs, jamais parallèles (les sprints 1 et 2 partagent `validate-phase-6.ps1` et `ci.yml`, les sprints 5 et 6 `ProjectIndexingService` et `LuceneSearchIndex`). L'ordre a été vérifié par script (`consolidate.py`) : chaque constat actionnable est dans exactement un sprint, aucun Info n'y figure, aucun prérequis n'est placé après son dépendant.

## Vue d'ensemble au 11 novembre 2026

| Sprint | Titre | État | Fermés | Composition | Charge (j) | Reste (j) |
|---|---|---|---:|---|---|---|
| S1 | Remettre les mesures d'aplomb | Clôturé | 8/8 | 4 moyenne · 4 faible | 2 à 8 | 0 à 0 |
| S2 | Outillage des scripts et des workflows | Clôturé | 9/9 | 1 moyenne · 8 faible | 3,75 à 13 | 0 à 0 |
| S3 | Filets de test et traces | Clôturé | 7/7 | 2 moyenne · 5 faible | 1,75 à 7 | 0 à 0 |
| S4 | Une seule sortie publique, redaction complétée | Clôturé | 8/8 | 5 moyenne · 3 faible | 3,5 à 12 | 0 à 0 |
| S5 | Indexation et écriture SQLite | Clôturé | 8/8 | 1 moyenne · 7 faible | 4,25 à 14 | 0 à 0 |
| S6 | Fédération et runtimes longue durée | Clôturé | 11/11 | 6 moyenne · 5 faible | 5 à 17 | 0 à 0 |
| S7 | Casser le bloc cyclique du cœur | Clôturé | 7/7 | 2 moyenne · 5 faible | 5,25 à 16 | 0 à 0 |
| S8 | Façade, configuration et durcissements locaux | Clôturé | 9/9 | 1 moyenne · 8 faible | 3 à 11 | 0 à 0 |
| S9 | Chaîne de release exécutable | Clôturé | 9/9 | 2 moyenne · 7 faible | 3 à 11 | 0 à 0 |
| S10 | Conteneur et gouvernance du dépôt | Clôturé | 7/7 | 7 faible | 1,75 à 7 | 0 à 0 |
| S11 | Finitions documentaires et micro-optimisations | Clôturé | 8/8 | 8 faible | 2,75 à 10 | 0 à 0 |
| S12 | Durcir les gates ajoutés | Clôturé | 5/5 | 5 faible | 1,25 à 5 | 0 à 0 |
| S13 | Installateur et release finale | Clôturé | 5/5 | 1 élevée · 4 faible | 1,25 à 5 | 0 à 0 |
| S14 | Suites de la vérification du 11 novembre | À faire | 0/8 | 1 élevée · 1 moyenne · 6 faible | 2,75 à 10 | 2,75 à 10 |

Les sprints 12 à 14 n'existaient pas le 17/10. Le sprint 12 regroupe les contournements relevés le 07/11 dans les gates livrés aux sprints 1 et 2 ; le sprint 13 les défauts de l'installateur et de la publication relevés le 09/11 ; le sprint 14 les constats relevés le 11/11. Aucun n'a de dépendance avec les sprints antérieurs.

Charge restante : **2,75 à 10 jours**. C'est une enveloppe (S 0,25 à 1 j, M 1 à 3 j, L 3 à 8 j) : elle ne couvre ni la revue, ni les allers-retours de CI, ni les arbitrages écrits (ADR) qu'exigent les sprints 5, 6 et 11.

## Chemin critique restant

Les sprints 1 à 13 sont clôturés et la release finale v0.2.0 est publiée. Aucun constat ouvert n'en bloque un autre. L'ordre restant, au sprint 14, est une question d'urgence :

- **`AUD-QUA-28` (Élevée, M)** : avec un profil Windows accentué, l'installation native par défaut ne trouve pas `NEXUS_HOME` ; même population que `AUD-QUA-22`, à corriger avant la prochaine release.
- **`AUD-SEC-24` (Moyenne, S)** : un fichier forgé de 8 Mo bloque l'indexation environ 1 min 30 par passe de redaction.
- **`AUD-SEC-25`, `AUD-DEP-37`** : redaction XML et réglages GitHub de release ; puis les contournements des gates (`AUD-QUA-26`, `AUD-DEP-36`), le plafond de charge (`AUD-TST-18`) et la doc (`AUD-QUA-27`).

## S1 — Remettre les mesures d'aplomb

**État au 11/11** : Clôturé (8/8 fermés). Sortie atteinte (#343, #347) : règles CLASS pour les quatre classes de bornes, au plus 3 points sous la mesure ; une règle mal nommée fait échouer `coverage_floors.py`.

**Pourquoi ici.** Plusieurs gates passent sans mesurer tout ce qu'ils annoncent ; tant qu'ils ne sont pas réparés, les corrections des sprints suivants ne sont pas vérifiables.

**Critère de sortie.** NexusApiApplicationService est crédité par la couverture REST ; une règle par classe existe pour les classes de bornes listées ; le gate Windows échoue si le wrapper échoue ; un runner sans symlinks échoue au lieu d'ignorer ; les alertes Dependabot et le secret scanning sont actifs.

**Charge** : 2 à 8 j.

| Ordre | ID | Sévérité | Effort | Statut | Constat |
|---:|---|---|---|---|---|
| 1 | `AUD-TST-01` | Moyenne | S | Fermé avec réserve | NexusApiApplicationService, bean CDI exercé par les @QuarkusTest, n'est crédité qu'à 13 lignes sur 95 par l'agent JaCoCo seul |
| 2 | `AUD-TST-03` | Moyenne | S | Fermé avec réserve | Gates JaCoCo au seul niveau BUNDLE, seuils effectifs différents de la doc : 6 classes de sécurité ou de bornes sous 55 % de branches passent sans alerte |
| 3 | `AUD-QUA-06` | Moyenne | S | Fermé | Le gate CI Windows retombe silencieusement sur le Maven installé du runner si le wrapper échoue |
| 4 | `AUD-TST-11` | Faible | S | Fermé | Tests symlink conditionnés par assume (15 sites) : un runner sans symlinks les ignore silencieusement |
| 5 | `AUD-TST-08` | Faible | S | Fermé avec réserve | Scripts de test orphelins : test-rest-token-contract.ps1 et Test-Hooks.ps1 appelés par aucun workflow ni script |
| 6 | `AUD-DEP-30` | Moyenne | S | Fermé | Alertes Dependabot, mises à jour de sécurité et secret scanning désactivés sur le dépôt public |
| 7 | `AUD-DEP-20` | Faible | S | Fermé avec réserve | Deux gates requis mesurent moins que leur nom : « Windows gate » sur un JDK 24 non épinglé, CodeQL Java sans build |
| 8 | `AUD-DEP-14` | Faible | S | Fermé | Le benchmark d'échelle n'est pas un check requis et sa version post-merge sur develop ignore une partie des chemins couverts en PR |

## S2 — Outillage des scripts et des workflows

**État au 11/11** : Clôturé (9/9 fermés). Sortie atteinte.

**Pourquoi ici.** Les scripts et workflows portent toutes les vérifications ; on les rend uniformes, et on stabilise la qualification sémantique qui mesurera le sprint 6. Touche validate-phase-6.ps1 et ci.yml après le sprint 1, jamais en parallèle.

**Critère de sortie.** Un seul module PowerShell appelle Maven, via le wrapper ; aucun input n'est interpolé dans un bloc run: ; chaque job a un timeout ; la qualification sémantique réelle termine sur develop.

**Charge** : 3,75 à 13 j.

**Ordre interne** : `AUD-QUA-04` avant `AUD-QUA-05`

| Ordre | ID | Sévérité | Effort | Statut | Constat |
|---:|---|---|---|---|---|
| 1 | `AUD-QUA-04` | Faible | S | Fermé | Quatre scripts validate-iteration-11 à 14 ne sont référencés nulle part et deux pointent vers des JAR 0.1.0-SNAPSHOT inexistants |
| 2 | `AUD-QUA-05` | Moyenne | M | Fermé avec réserve | 12 scripts PowerShell redéfinissent chacun une fonction Invoke-Maven et 16 appellent `mvn` au lieu du wrapper |
| 3 | `AUD-SEC-07` | Faible | S | Fermé avec réserve | inputs.profile (type string) est interpolé directement dans des blocs run: de trois workflows |
| 4 | `AUD-DEP-10` | Faible | S | Fermé avec réserve | Le commentaire de version d'upload-artifact dans osv-scanner.yml est faux (« v4.6.2 » pour le SHA de v7.0.2) |
| 5 | `AUD-DEP-11` | Faible | S | Fermé | Deux versions majeures d'actions/checkout coexistent : 11 usages en v4.4.0 et 8 en v7.0.1 |
| 6 | `AUD-DEP-12` | Faible | S | Fermé | Les workflows réutilisables OSV sont épinglés sur un commit non tagué, sans version en commentaire |
| 7 | `AUD-DEP-15` | Faible | S | Fermé | Plusieurs jobs n'ont pas de timeout-minutes (défaut GitHub 360 min) |
| 8 | `AUD-DEP-22` | Faible | S | Fermé avec réserve | Les runners sont désignés par des libellés flottants -latest et aucun cache Maven n'est utilisé |
| 9 | `AUD-DEP-19` | Faible | M | Fermé | La qualification sémantique réelle est instable : 16 des 25 derniers runs annulés, environ 2 h par run, hors gate de release |

## S3 — Filets de test et traces

**État au 11/11** : Clôturé (7/7 fermés). Sortie atteinte ; la baseline de cycles est vide et le test lit désormais les références qualifiées.

**Pourquoi ici.** Les sprints 4 à 8 modifient la sortie publique, l'indexation et les frontières de packages : il faut d'abord les tests de contrat, les traces et le garde-fou d'architecture qui signaleront une régression.

**Critère de sortie.** Le test d'architecture échoue sur un import interdit ; NexusResourceTest et NexusMcpServerIntegrationTest ont un @Test par contrat ; GitRecencyCandidateEnricher journalise ce qu'il attrape ; ProjectPathGuard a sa classe de test.

**Charge** : 1,75 à 7 j.

| Ordre | ID | Sévérité | Effort | Statut | Constat |
|---:|---|---|---|---|---|
| 1 | `AUD-ARC-07` | Moyenne | S | Fermé avec réserve | Aucune protection structurelle des frontières : pas de test d'architecture, pas de module-info, 160/219 types publics |
| 2 | `AUD-TST-07` | Faible | S | Fermé | Tests E2E monolithiques : NexusResourceTest et NexusMcpServerIntegrationTest = 1 @Test chacun pour 200 et 360 lignes |
| 3 | `AUD-QUA-07` | Faible | S | Fermé | La récence Git avale toute exception sans diagnostic ni log |
| 4 | `AUD-TST-02` | Moyenne | S | Fermé | ProjectPathGuard (primitive de confinement) sans classe de test dédiée : 3 appels directs dans un seul test, 13/24 branches couvertes (54,2 %) |
| 5 | `AUD-TST-06` | Faible | S | Fermé | Assertions de durée murale serrées dans 5 tests de la suite par défaut (500 ms à 2 s) |
| 6 | `AUD-TST-10` | Faible | S | Fermé | IndexRepository : 98 lignes de méthodes default jamais exécutées (0/98, 0/56 branches) gonflent le dénominateur core |
| 7 | `AUD-TST-09` | Faible | S | Fermé | 5 tests opt-in ne tournent dans aucun workflow (MINOS réel, baselines large/small-delta, 2 diagnostics sémantiques) |

## S4 — Une seule sortie publique, redaction complétée

**État au 11/11** : Clôturé (8/8 fermés). Sortie atteinte ; la borne `sk-` est alignée (`AUD-SEC-20`, #348).

**Pourquoi ici.** La projection publique est écrite trois fois ; on la rend unique avant d'y corriger l'ordre, les signatures et les formats de secrets, sinon chaque correctif est à faire trois fois.

**Critère de sortie.** Un test de parité compare les clés produites par CLI, MCP et REST ; la sonde de 35 formats est versionnée en test et passe pour les 19 formats retenus ; un fichier portant une valeur sensible de plus de 4 096 caractères est exclu seul, le reste du projet s'indexe.

**Charge** : 3,5 à 12 j.

**Prérequis livrés par un sprint antérieur** : `AUD-TST-01` (S1), `AUD-TST-07` (S3)

**Ordre interne** : `AUD-QUA-09` avant `AUD-ARC-03`

| Ordre | ID | Sévérité | Effort | Statut | Constat |
|---:|---|---|---|---|---|
| 1 | `AUD-QUA-08` | Faible | S | Fermé | Les cinq records d'opération de NexusApplication sont recopiés à l'identique dans le service REST |
| 2 | `AUD-QUA-09` | Moyenne | M | Fermé | Pas de projection publique unique : CLI, MCP et REST réappliquent chacun la politique de sortie et divergent sur les mêmes objets |
| 3 | `AUD-ARC-03` | Moyenne | S | Fermé | Cycle context ↔ security : la politique de sortie publique est rangée dans security et dépend des bundles de contexte |
| 4 | `AUD-QUA-01` | Moyenne | S | Fermé | Les sorties MCP et REST exposent scoreComponents dans un ordre qui change à chaque démarrage de JVM |
| 5 | `AUD-SEC-03` | Faible | S | Fermé | Les signatures de symboles issues de SCIP/MINOS/JDT sont renvoyées par REST, MCP et CLI sans passer par la redaction |
| 6 | `AUD-SEC-02` | Moyenne | S | Fermé | Une seule valeur sensible de plus de 4 096 caractères fait échouer l'indexation complète du projet et toute construction de contexte qui l'inclut |
| 7 | `AUD-SEC-01` | Moyenne | M | Fermé avec réserve | La redaction, liste fermée documentée comme non exhaustive, ne couvre pas 19 formats de secrets courants (Bearer, token=, sk-, xoxb-, AIza, glpat-, PGP…) |
| 8 | `AUD-PRF-18` | Faible | S | Fermé | SemanticIndexingService redige deux fois le même document avant l'envoi d'embeddings |

## S5 — Indexation et écriture SQLite

**État au 11/11** : Clôturé (8/8 fermés). Sortie atteinte ; l'ancien chemin `applyChanges` est supprimé (`AUD-PRF-28`).

**Pourquoi ici.** Les coûts d'indexation touchent les mêmes classes (ProjectIndexingService, SqliteFileWriter, LuceneSearchIndex) : un seul lot, avant la fédération qui réutilise ces classes.

**Critère de sortie.** Le plan du DELETE de symbol_relations par file_id utilise un index (test de plan) ; un benchmark couvre la réindexation de fichiers existants ; toute écriture d'index, MINOS compris, passe par ProjectIndexingService.

**Charge** : 4,25 à 14 j.

| Ordre | ID | Sévérité | Effort | Statut | Constat |
|---:|---|---|---|---|---|
| 1 | `AUD-PRF-01` | Moyenne | S | Fermé avec réserve | Aucun index sur symbol_relations(file_id) : chaque fichier réindexé ou supprimé déclenche un SCAN complet de la table (tous projets) |
| 2 | `AUD-PRF-12` | Faible | M | Fermé avec réserve | Les résultats d'analyse (symboles, relations) de tous les fichiers modifiés restent en mémoire jusqu'à l'écriture SQLite finale |
| 3 | `AUD-PRF-08` | Faible | M | Fermé avec réserve | Chaque indexation, même incrémentale sans changement, hache intégralement tous les fichiers deux fois (scan initial + scan final) |
| 4 | `AUD-PRF-11` | Faible | M | Fermé | Indexation Lucene : un IndexWriter ouvert et un commit (fsync) par lot de 128 fichiers |
| 5 | `AUD-PRF-14` | Faible | S | Fermé | Écriture par fichier : 4 prepareStatement + un SELECT supplémentaire après chaque upsert |
| 6 | `AUD-PRF-15` | Faible | S | Fermé | Index redondants qui amplifient les écritures : préfixes stricts d'autres index |
| 7 | `AUD-ARC-10` | Faible | S | Fermé | Écriture MINOS orchestrée par la façade et la CLI, hors ProjectIndexingService |
| 8 | `AUD-ARC-13` | Faible | S | Fermé | ProjectIndexingService expose cinq constructeurs publics télescopiques dont deux retombent silencieusement sur un verrou process-local |

## S6 — Fédération et runtimes longue durée

**État au 11/11** : Clôturé (11/11 fermés). Sortie atteinte (#344) : pagination du staging indexée (V016), choix d'index selon la part du projet (V017).

**Pourquoi ici.** Le chemin multi-projets répète des coûts par projet (embedding, readers, Git) ; on découpe d'abord la méthode de 168 lignes qui le porte. Mesuré par la qualification sémantique stabilisée au sprint 2.

**Critère de sortie.** Une requête fédérée sur N projets calcule un seul vecteur de requête, n'ouvre aucun DirectoryReader hors cache et respecte une échéance globale qui nomme les projets non servis.

**Charge** : 5 à 17 j.

**Prérequis livrés par un sprint antérieur** : `AUD-DEP-19` (S2)

**Ordre interne** : `AUD-QUA-10` avant `AUD-PRF-03`

| Ordre | ID | Sévérité | Effort | Statut | Constat |
|---:|---|---|---|---|---|
| 1 | `AUD-QUA-10` | Moyenne | M | Fermé | Méthodes longues concentrées dans le contexte fédéré, l'indexation et la découverte Git/skills |
| 2 | `AUD-PRF-02` | Moyenne | S | Fermé | Le vecteur de la requête est recalculé par appel HTTP Ollama pour chaque projet de la fédération (jusqu'à 100 fois la même requête) |
| 3 | `AUD-PRF-03` | Faible | M | Fermé avec réserve | La fédération n'a pas de deadline globale : un projet lent retarde toute la requête sans diagnostic du projet non servi |
| 4 | `AUD-PRF-04` | Moyenne | S | Fermé | La recherche sémantique et la sonde de readiness REST ouvrent chacune un DirectoryReader Lucene complet, hors du cache de l'ADR 0045 |
| 5 | `AUD-PRF-06` | Faible | S | Fermé | Cache de contexte Git limité à 16 entrées alors que le scope fédéré monte à 100 projets : éviction LRU systématique sur une requête fédérée répétée |
| 6 | `AUD-PRF-07` | Moyenne | S | Fermé avec réserve | GitRecencyCandidateEnricher relit 50 commits et 50 diffs d'arbres à chaque recherche et pour chaque projet, sans cache |
| 7 | `AUD-PRF-09` | Faible | M | Fermé avec réserve | Recherche de symboles : index FTS trigram et préfiltre fuzzy globaux (non scopés projet), jusqu'à 9 recherches × 3 requêtes SQL par projet |
| 8 | `AUD-PRF-27` | Moyenne | S | Fermé | La publication du staging pagine index_staged_files sans index (project_id, id) : coût quadratique, 5,6 s à 100 000 fichiers sous verrou d'écriture |
| 9 | `AUD-PRF-28` | Faible | S | Fermé | IndexRepository.applyChanges est mort en production mais reste le chemin mesuré par les benchmarks de réindexation |
| 10 | `AUD-PRF-29` | Moyenne | S | Fermé avec réserve | La migration V015 (préfiltre approché scopé par fichier) ralentit la recherche de symboles d'un gros projet seul dans sa base : 0,10 → 0,36 s à 100 000 fichiers |
| 11 | `AUD-QUA-23` | Faible | S | Fermé | La clé du vecteur de requête partagé ignore le texte de la requête : un SearchQueryContext réutilisé rendrait le vecteur de la requête précédente |

## S7 — Casser le bloc cyclique du cœur

**État au 11/11** : Clôturé (7/7 fermés). Sortie atteinte (#342) : `JavaSourceGraph` lit les références qualifiées, échecs de publication testés.

**Pourquoi ici.** Les cycles se cassent un par un sous la protection du test d'architecture (sprint 3), après les sprints qui modifient context, security et search.

**Critère de sortie.** Le graphe des packages du cœur est acyclique et le test d'architecture l'impose (AUD-ARC-01, constat chapeau).

**Charge** : 5,25 à 16 j.

**Prérequis livrés par un sprint antérieur** : `AUD-ARC-03` (S4), `AUD-ARC-07` (S3)

**Ordre interne** : `AUD-ARC-02` avant `AUD-ARC-01` ; `AUD-ARC-04` avant `AUD-ARC-01` ; `AUD-ARC-05` avant `AUD-ARC-01` ; `AUD-ARC-06` avant `AUD-ARC-01` ; `AUD-ARC-20` avant `AUD-ARC-01`

| Ordre | ID | Sévérité | Effort | Statut | Constat |
|---:|---|---|---|---|---|
| 1 | `AUD-ARC-20` | Faible | S | Fermé avec réserve | Le test d'architecture ne lit que les imports : 39 références pleinement qualifiées entre packages échappent au contrôle des cycles |
| 2 | `AUD-ARC-02` | Moyenne | M | Fermé | Cycle index ↔ search : le package index mêle modèle de domaine, port de persistance et orchestration qui dépend des ports de recherche |
| 3 | `AUD-ARC-04` | Faible | S | Fermé | Cycles context ↔ context.source / .git / .skill : les ports de sources dépendent du modèle de contexte qui dépend d'eux |
| 4 | `AUD-ARC-05` | Faible | S | Fermé | Cycle search ↔ ranking : le ranker dépend des candidats de recherche, le service de recherche dépend du ranker |
| 5 | `AUD-ARC-06` | Faible | S | Fermé | Le package de ports sémantiques instancie l'implémentation Ollama (cycle search.semantic ↔ search.semantic.ollama) |
| 6 | `AUD-ARC-01` | Moyenne | L | Fermé avec réserve | Composante fortement connexe de 12 packages du cœur (context, context.source, .git, .skill, index, index.scan, ranking, ranking.graph, search, search.semantic, search.semantic.ollama, security) |
| 7 | `AUD-TST-16` | Faible | S | Fermé avec réserve | Aucun test ne couvre un échec entre le commit Lucene et la publication SQLite, ni l'échec du commit sémantique après le lexical |

## S8 — Façade, configuration et durcissements locaux

**État au 11/11** : Clôturé (9/9 fermés). Sortie atteinte (#348, #349) : refus et masquage alignés, sur le même texte (`AUD-SEC-20`, `AUD-SEC-23`).

**Pourquoi ici.** La façade et la configuration se nettoient une fois les packages stabilisés ; les deux durcissements locaux touchent des points d'entrée revus aux sprints 4 et 7.

**Critère de sortie.** La façade n'expose plus SQLException ; System.getenv n'apparaît plus que dans la composition root ; le mode trust-local refuse un Host ou une Origin non loopback ; la découverte Git s'arrête à la racine du projet.

**Charge** : 3 à 11 j.

**Ordre interne** : `AUD-SEC-20` avant `AUD-SEC-23`

| Ordre | ID | Sévérité | Effort | Statut | Constat |
|---:|---|---|---|---|---|
| 1 | `AUD-ARC-09` | Faible | S | Fermé | Séquence de lecture cohérente (readiness → verrou partagé → session → readiness) dupliquée dans six méthodes de la façade |
| 2 | `AUD-ARC-11` | Faible | S | Fermé | SQLException (technologie de persistance) fait partie du contrat public de la façade et des points d'entrée |
| 3 | `AUD-ARC-12` | Faible | M | Fermé avec réserve | Configuration lue directement dans l'environnement par 12 classes (16 appels à System.getenv) du cœur, hors composition root |
| 4 | `AUD-ARC-14` | Faible | S | Fermé | Responsabilités mélangées dans config.NexusPaths (résolution des chemins + durcissement POSIX/ACL du stockage) et code de test dans le jar (search.evaluation) |
| 5 | `AUD-ARC-19` | Faible | S | Fermé | Le package index.jdt définit une classe nommée ProcessBuilder qui masque java.lang.ProcessBuilder |
| 6 | `AUD-SEC-04` | Moyenne | S | Fermé avec réserve | En mode NEXUS_REST_TRUST_LOCAL=true, l'API REST loopback sans token n'a ni contrôle Host/Origin ni allowlist de racines : exposée au DNS rebinding depuis un navigateur |
| 7 | `AUD-SEC-05` | Faible | S | Fermé avec réserve | La découverte JGit remonte les répertoires parents sans plafond ni contrôle de propriétaire du dépôt trouvé |
| 8 | `AUD-SEC-20` | Faible | S | Fermé | Un jeton sk- dont le corps fait 256 à 260 caractères n'est ni redigé ni refusé (bornes 255 et 261 désalignées) |
| 9 | `AUD-SEC-23` | Faible | S | Fermé avec réserve | Un jeton à préfixe collé à un bloc de clé privée, ou un jeton xox?-, gh?_, sk_/rk_ contenant un « _ », n'est ni redigé ni refusé (refus et masquage désalignés) |

## S9 — Chaîne de release exécutable

**État au 11/11** : Clôturé (9/9 fermés). Sortie atteinte ; la release finale v0.2.0 est publiée et attestée.

**Pourquoi ici.** La release n'a jamais tourné : on corrige ce qu'elle manipule (secrets, environnement, attestations, version) avant de la lancer une première fois sur un tag de pré-version.

**Critère de sortie.** Un tag de pré-version produit, depuis un environnement protégé, des artefacts attestés et signés, sans que la clé soit visible hors de l'étape de signature ni la version codée en dur.

**Charge** : 3 à 11 j.

**Ordre interne** : `AUD-DEP-03` avant `AUD-DEP-02` ; `AUD-DEP-09` avant `AUD-DEP-02` ; `AUD-DEP-21` avant `AUD-DEP-02` ; `AUD-QUA-12` avant `AUD-DEP-02` ; `AUD-SEC-06` avant `AUD-DEP-02`

| Ordre | ID | Sévérité | Effort | Statut | Constat |
|---:|---|---|---|---|---|
| 1 | `AUD-DEP-21` | Faible | S | Fermé | release.yml transmet tous les secrets du dépôt au workflow Windows avec secrets: inherit |
| 2 | `AUD-SEC-06` | Moyenne | S | Fermé avec réserve | Le PFX Authenticode et son mot de passe sont exposés à toutes les étapes du job Windows, y compris sur pull_request avec le code de la PR |
| 3 | `AUD-DEP-03` | Faible | S | Fermé avec réserve | Le job de publication n'utilise aucun environnement GitHub protégé ; la protection des tags v*.*.* n'est pas attestée |
| 4 | `AUD-DEP-09` | Faible | S | Fermé | Les assets Windows (ZIP, setup.exe) ne reçoivent aucune attestation de provenance |
| 5 | `AUD-DEP-05` | Faible | S | Fermé avec réserve | Aucune propriété project.build.outputTimestamp : les JAR et ZIP produits ne sont pas reproductibles bit à bit |
| 6 | `AUD-DEP-06` | Faible | S | Fermé | L'enforcer ne contrôle ni versions de plugins ni convergence ; les plugins implicites (jar, resources, install, deploy, clean) ne sont pas épinglés |
| 7 | `AUD-DEP-07` | Faible | S | Fermé | La génération des notices de licences ne bloque aucune licence incompatible |
| 8 | `AUD-QUA-12` | Faible | S | Fermé avec réserve | Version 0.2.0 codée en dur dans la CI et le gate Windows |
| 9 | `AUD-DEP-02` | Moyenne | M | Fermé avec réserve | Le workflow Release n'a jamais été exécuté : 0 run et 0 release/tag publiés |

## S10 — Conteneur et gouvernance du dépôt

**État au 11/11** : Clôturé (7/7 fermés). Sortie atteinte.

**Pourquoi ici.** Durcissements indépendants du code, regroupés parce qu'ils touchent les mêmes fichiers (Dockerfile, compose) ou les réglages GitHub.

**Critère de sortie.** L'image n'est pas modifiable par son UID d'exécution et embarque licences et labels OCI ; le ruleset n'autorise que le merge commit ; SECURITY.md et CODEOWNERS existent. Une montée de version Dependabot ne casse aucun contrat documentaire.

**Charge** : 1,75 à 7 j.

| Ordre | ID | Sévérité | Effort | Statut | Constat |
|---:|---|---|---|---|---|
| 1 | `AUD-DEP-04` | Faible | S | Fermé | Dans l'image Docker, les scripts d'entrée et les répertoires applicatifs appartiennent à l'UID d'exécution 10001 |
| 2 | `AUD-SEC-08` | Faible | S | Fermé avec réserve | Absence de .dockerignore : `COPY . .` envoie tout le contexte de build (.git, fichiers locaux .env, données) dans l'étape builder |
| 3 | `AUD-DEP-08` | Faible | S | Fermé | L'image Docker publiée n'embarque ni LICENSE ni THIRD_PARTY_NOTICES ni labels OCI |
| 4 | `AUD-DEP-18` | Faible | S | Fermé | Durcissement conteneur incomplet : pas d'instruction HEALTHCHECK dans l'image, compose sans cap_drop ni no-new-privileges |
| 5 | `AUD-DEP-16` | Faible | S | Fermé | Le ruleset autorise squash et rebase alors que la politique impose le merge commit |
| 6 | `AUD-DEP-17` | Faible | S | Fermé avec réserve | Le dépôt public n'a ni SECURITY.md ni CODEOWNERS |
| 7 | `AUD-DEP-31` | Faible | S | Fermé | La montée Quarkus 3.40.1.1 (PR #262) échoue sur un contrat documentaire qui recopie le numéro de version |

## S11 — Finitions documentaires et micro-optimisations

**État au 11/11** : Clôturé (8/8 fermés). Sortie atteinte.

**Pourquoi ici.** Corrections isolées, sans prérequis ni conséquence sur les autres lots.

**Critère de sortie.** Les quatre dérives relevées (graphe dérivé, FTS trigram, AGENTS.md, décision WAL) sont corrigées ; aucune regex des deux chemins relevés n'est compilée par appel.

**Charge** : 2,75 à 10 j.

| Ordre | ID | Sévérité | Effort | Statut | Constat |
|---:|---|---|---|---|---|
| 1 | `AUD-QUA-02` | Faible | S | Fermé | Le graphe dérivé est documenté comme cache par génération alors que le code ne le matérialise plus ; ProjectGraph est du code mort |
| 2 | `AUD-QUA-13` | Faible | S | Fermé | NXA-20260914-05 partiellement ouvert : le watch item FTS/trigram reste présenté comme futur |
| 3 | `AUD-QUA-03` | Faible | S | Fermé | AGENTS.md prescrit `mvn clean install` alors que les hooks du dépôt refusent `mvn` hors wrapper |
| 4 | `AUD-PRF-10` | Faible | S | Fermé avec réserve | La décision de garder le journal rollback (sans WAL) est mesurée mais consignée hors ADR |
| 5 | `AUD-QUA-11` | Faible | M | Fermé | Environ un tiers des messages d'exception sont en anglais malgré la convention française |
| 6 | `AUD-PRF-13` | Faible | S | Fermé | Troncature de fragment quadratique : reconstruction du préfixe complet et réestimation à chaque ligne |
| 7 | `AUD-PRF-16` | Faible | S | Fermé avec réserve | Voisinage graphe sortant paginé par OFFSET : chaque page rescanne les pages précédentes |
| 8 | `AUD-PRF-17` | Faible | S | Fermé | Regex compilées à chaque appel sur des chemins chauds d'import et de découverte |

## S12 — Durcir les gates ajoutés

**État au 11/11** : Clôturé (5/5 fermés). Sortie atteinte avec réserve (#353) : gates durcis et testés par mutation ; des formes simples les contournent encore (`AUD-QUA-26`, `AUD-DEP-36`).

**Pourquoi ici.** Les gates livrés aux sprints 1 et 2 tiennent sur les cas d'origine mais se contournent sur des variantes. Sans dépendance avec les sprints 6 à 11 : à mener tôt, numéroté 12 pour ne pas renuméroter les changements OpenSpec existants.

**Critère de sortie.** Chaque gate ajouté échoue sur les variantes relevées (fixtures workflow_hygiene, mutations du contrat Maven, tag amont faux) ; les règles Archgate tournent en CI ; les planchers JaCoCo sont à moins de 3 points de la mesure.

**Charge** : 1,25 à 5 j.

| Ordre | ID | Sévérité | Effort | Statut | Constat |
|---:|---|---|---|---|---|
| 1 | `AUD-TST-15` | Faible | S | Fermé avec réserve | Planchers JaCoCo déjà loin de la mesure (api 78/67 contre 88,4/73,7 ; security 85/77 contre 91,7/82,9), contrairement à la doc « 2 à 3 points sous la mesure » |
| 2 | `AUD-TST-17` | Faible | S | Fermé avec réserve | Les 40 règles Archgate (*.rules.ts) ne tournent dans aucun workflow et leur outillage n'est pas épinglé |
| 3 | `AUD-QUA-21` | Faible | S | Fermé avec réserve | Le contrat « Maven uniquement via NexusScript.psm1 » se contourne par indirection ($m = "mvn" ; & $m, cmd /c mvn) |
| 4 | `AUD-DEP-32` | Faible | S | Fermé avec réserve | workflow_hygiene.py ne lit pas toutes les formes YAML (run: multi-lignes simple, flow mapping, clés citées, with: script:) ni les actions composites |
| 5 | `AUD-DEP-33` | Faible | S | Fermé avec réserve | Le contrôle des épinglages compare les commentaires entre eux, pas au tag amont : AUD-DEP-10 peut revenir sans alerte |

## S13 — Installateur et release finale

**État au 11/11** : Clôturé (5/5 fermés). Sortie atteinte (#354, release v0.2.0) : configurations JSON des clients préservées octet pour octet sous PS 5.1 (rejoué) ; reste l'écriture ANSI d'autres fichiers (`AUD-QUA-28`).

**Pourquoi ici.** Défauts relevés le 09/11 sur l'installateur Windows et la publication, à corriger avant la promotion vers main (PR #332) et la release finale v0.2.0.

**Critère de sortie.** Connecter puis déconnecter NEXUS laisse intacts octet pour octet les autres serveurs d'une configuration UTF-8 contenant des chemins accentués, et refuse d'écrire sur un JSON invalide (tests de l'installateur) ; aucun tag GHCR hors chaîne de release ; labels OCI renseignés.

**Charge** : 1,25 à 5 j.

| Ordre | ID | Sévérité | Effort | Statut | Constat |
|---:|---|---|---|---|---|
| 1 | `AUD-QUA-22` | Élevée | S | Fermé avec réserve | L'installateur réécrit les configurations MCP des clients IA sans sauvegarde : chemins accentués corrompus, JSON invalide remplacé par un fichier vide |
| 2 | `AUD-DEP-34` | Faible | S | Fermé | Des tags GHCR :0.2.0 et :latest posés le 9 août par l'ancienne chaîne Docker existent hors de la release et bloqueront le préflight immuable de la release finale v0.2.0 |
| 3 | `AUD-QUA-24` | Faible | S | Fermé | L'échappement PowerShell de l'installateur ne double pas les apostrophes typographiques : un chemin contenant « ’ » casse les scripts générés |
| 4 | `AUD-DEP-35` | Faible | S | Fermé avec réserve | Les labels OCI revision et version sont vides dans l'image construite par l'installateur Windows |
| 5 | `AUD-QUA-25` | Faible | S | Fermé | 9 liens relatifs cassés dans le changement OpenSpec archivé le 09/11, que le contrat OpenSpec ne contrôle pas |

## S14 — Suites de la vérification du 11 novembre

**État au 11/11** : À faire (0/8 fermés). Nouveau au 11/11.

**Pourquoi ici.** Constats relevés le 11/11 : deux défauts de la redaction antérieurs aux sprints 8 (motif JWT coûteux, éléments XML composés), des contournements restants des gates textuels du sprint 12, le plafond de charge relâché pour la release 0.2.0 et deux réglages GitHub de la release.

**Critère de sortie.** 1 Mo de « eyJ- » redigé en moins d'1 s (test) ; <db-password> et <db_password> masqués ; une fixture négative par forme de contournement relevée, rejetée par les contrats ; plafond 1M dur et page de benchmark exacte ; releases immuables activées.

**Charge** : 2,75 à 10 j.

| Ordre | ID | Sévérité | Effort | Statut | Constat |
|---:|---|---|---|---|---|
| 1 | `AUD-QUA-28` | Élevée | M | Ouvert | L'installateur écrit la configuration native, le .env Docker et le fragment TOML de Codex dans la page ANSI : avec un profil accentué, NEXUS_HOME par défaut est introuvable |
| 2 | `AUD-SEC-24` | Moyenne | S | Ouvert | Le motif JWT de la redaction coûte environ 12 s par Mo sur une entrée forgée « eyJ- » répétée : un fichier de 8 Mo bloque l'indexation |
| 3 | `AUD-DEP-36` | Faible | S | Ouvert | workflow_hygiene.py ignore les clés écrites avec un espace avant « : » et les corps de job indentés autrement que de 2 |
| 4 | `AUD-DEP-37` | Faible | S | Ouvert | La release GitHub v0.2.0 n'est pas immuable et le tag v0.2.0 a été recréé par le contournement admin du ruleset des tags |
| 5 | `AUD-QUA-26` | Faible | S | Ouvert | Le contrat « Maven via NexusScript.psm1 » ne voit pas un appel direct à mvn hors début de ligne |
| 6 | `AUD-QUA-27` | Faible | S | Ouvert | Guide de reproduction encore sur des JAR 0.1.0-SNAPSHOT à la racine alors que la version publiée est 0.2.0 |
| 7 | `AUD-SEC-25` | Faible | S | Ouvert | Un élément XML au nom composé par « - » ou « _ » (<db-password>, <db_password>) n'est pas redigé |
| 8 | `AUD-TST-18` | Faible | S | Ouvert | Plafond de peuplement 1M relâché après l'échec de la release v0.2.0 : hors PR, un ralentissement uniforme jusqu'à 52 s passe, et la page affirme encore « plafonds inchangés » |

## Constats Info, hors sprint

Ils ne demandent aucune action. Ceux marqués ★ expliquent pourquoi un risque est déjà borné : ne pas les « corriger ».

- ★ `AUD-ARC-15` — Cœur sans framework applicatif et dépendances techniques confinées à leurs packages adaptateurs
- `AUD-ARC-16` — Adaptateurs REST/MCP minces : ils passent exclusivement par NexusApplication et des types valeur
- `AUD-ARC-17` — Composition root explicite et cohérente ; packages project et persistence.sqlite hors cycles
- `AUD-ARC-18` — Pas de god object : fichier main le plus long 532 lignes (NexusApplication), façade à 31 membres publics
- ★ `AUD-DEP-23` — Toutes les actions externes sont épinglées par SHA de 40 caractères vérifié, aucun pull_request_target, permissions read par défaut sur les 19 workflows
- ★ `AUD-DEP-24` — Wrapper Maven durci : archive 3.10.0 vérifiée par ancre SHA-512 versionnée et cache extrait revérifié
- `AUD-DEP-25` — Images de base épinglées par digest, runtime JRE UBI10 minimal non-root, REST en loopback par défaut
- `AUD-DEP-26` — Dépendances majeures à jour au 17/10/2026 ; seul le SDK MCP a une mineure publiée depuis deux jours
- ★ `AUD-DEP-27` — Chaîne de release conçue fail-closed : tag = HEAD de main, tous les gates en needs, tags immuables, attestations provenance + SBOM, Trivy bloquant
- `AUD-DEP-28` — Le constat NXA-20260914-04 (gate de charge rouge) est résolu sans relèvement des plafonds
- `AUD-DEP-29` — Observabilité en place : health liveness/readiness, Prometheus sur listener de management loopback, journalisation System.Logger
- `AUD-PRF-19` — Lots d'écriture SQLite multi-lignes bornés à 128 lignes, conformes à l'ADR 0051
- `AUD-PRF-20` — Lectures du registre fédéré groupées (json_each, tranches de 500) conformément à l'ADR 0054
- `AUD-PRF-21` — Requêtes de graphe groupées par json_each et contraintes par index (project_id, kind, ref), avec test de plan
- ★ `AUD-PRF-22` — Cache de searchers Lucene borné à 100 projets (= MAX_PROJECTS) sans writer retenu, rafraîchi après commit (ADR 0045)
- `AUD-PRF-23` — Embeddings d'indexation envoyés par lots (≤ 256 documents, budget de caractères) et fingerprint canonique mis en cache par génération
- `AUD-PRF-24` — Une seule connexion SQLite réutilisée par requête applicative (session de lecture thread-local)
- ★ `AUD-PRF-25` — Lot de documents d'indexation borné (128 fichiers / 16 MiB) et temporaires SQLite en mémoire (ADR 0050)
- `AUD-PRF-30` — La redaction enchaîne désormais 9 passes (un motif par famille) : surcoût mesuré de 30 à 60 % sur contenu ordinaire, à confirmer
- `AUD-QUA-15` — Duplication globale faible et sans TODO/FIXME ni printStackTrace
- `AUD-QUA-16` — Logs : System.Logger dans le cœur, aucun System.out/err hors CLI
- ★ `AUD-QUA-17` — Gestion d'erreurs larges globalement justifiée
- `AUD-QUA-18` — Typage propre : aucun raw type, Optional limité aux retours, deux @SuppressWarnings justifiés
- `AUD-QUA-19` — Taille des fichiers maîtrisée
- `AUD-QUA-20` — Configuration .claude/ hygiénique et exclue de l'indexation NEXUS
- `AUD-SEC-09` — NXA-20260914-01 corrigé : les clés JSON/JS citées et les scalaires non cités sont redigés, index historiques invalidés par V009/V010
- `AUD-SEC-10` — NXA-20260914-02 corrigé : le timeout Ollama couvre désormais en-têtes et corps (sendAsync + get(timeout)), redirections non suivies
- `AUD-SEC-11` — NXA-20260914-03 corrigé : les principaux Windows de confiance sont résolus par SID (S-1-5-18, S-1-5-32-544, S-1-3-0), sans fallback par nom
- ★ `AUD-SEC-12` — Authentification REST solide par défaut : token exigé même sur loopback, comparaison temps constant, politique de longueur/diversité, management loopback-only
- ★ `AUD-SEC-13` — Réponses d'erreur REST/MCP sans chemins ni stack traces ; chemins projet masqués
- ★ `AUD-SEC-14` — SQL entièrement paramétré ; LIKE échappé avec ESCAPE et requêtes FTS5 citées trigramme par trigramme
- ★ `AUD-SEC-15` — Lectures de fichiers projet bornées par ProjectPathGuard + SafeFileIO (NOFOLLOW, SecureDirectoryStream, revalidation) ; aucune lecture de contenu hors de ces garde-fous trouvée
- ★ `AUD-SEC-16` — JDT LS : racine exacte approuvée, environnement réduit à une allowlist de 28 variables, URI non file: ou hors racine ignorées
- ★ `AUD-SEC-17` — Désérialisation sûre : pas de typage polymorphe Jackson, SnakeYAML Engine sans alias et borné
- ★ `AUD-SEC-18` — Ollama : URL issue de la seule configuration (pas de SSRF client), credentials refusés, HTTP distant soumis à opt-in
- `AUD-SEC-19` — Docker et CI : runtime non-root, bases épinglées par digest, actions tierces toutes épinglées par SHA, aucun pull_request_target
- `AUD-SEC-21` — Risque accepté : les assets Windows de release ne sont pas signés Authenticode (décision SEC-0068), compensé par l'attestation SLSA et l'empreinte SHA-256
- `AUD-SEC-22` — Le contrôle Host/Origin loopback est inactif dans l'image Docker (écoute 0.0.0.0) ; le jeton obligatoire compense
- `AUD-TST-12` — Build mesuré vert : 618 tests (519 core + 53 REST + 22 MCP + 24 assistant), 24 ignorés tous attendus, tous les jacoco:check passent
- `AUD-TST-13` — Redaction et bornes bien testées : SensitiveContentRedactorTest 260 l. (JSON cités, quotes échappées, clés composées, clés privées, lookahead borné), SecretAssignmentScanner 121/132 branches
- `AUD-TST-14` — Surefire sans exclusion de tags, failIfNoSpecifiedTests forcé en CI, CI ci.yml sur PR/push develop+main en Linux (JDK 21) et Windows (JDK 24)
