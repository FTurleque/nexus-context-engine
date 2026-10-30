# Plan d'action — audit du 17 octobre 2026

Classement par **urgence**. Pour l'**ordre d'exécution**, qui prime en cas de divergence, voir [`sprints.md`](sprints.md) : une action urgente dont le prérequis n'est pas livré ne peut pas être vérifiée. Exemple : `AUD-ARC-03` (Moyenne, effort S) figure dans l'immédiat, mais il suppose la projection publique unique (`AUD-QUA-09`, sprint 4).

## Immédiat — Moyennes rapides (15)

Aucun constat Critique ni Élevé. Les Moyennes d'effort S :

| ID | Sévérité | Effort | Sprint | Dépend de | Constat |
|---|---|---|---|---|---|
| `AUD-DEP-30` | Moyenne | S | S1 | aucun | Alertes Dependabot, mises à jour de sécurité et secret scanning désactivés sur le dépôt public |
| `AUD-QUA-06` | Moyenne | S | S1 | aucun | Le gate CI Windows retombe silencieusement sur le Maven installé du runner si le wrapper échoue |
| `AUD-TST-01` | Moyenne | S | S1 | aucun | NexusApiApplicationService, bean CDI exercé par les @QuarkusTest, n'est crédité qu'à 13 lignes sur 95 par l'agent JaCoCo seul |
| `AUD-TST-03` | Moyenne | S | S1 | aucun | Gates JaCoCo au seul niveau BUNDLE, seuils effectifs différents de la doc : 6 classes de sécurité ou de bornes sous 55 % de branches passent sans alerte |
| `AUD-ARC-07` | Moyenne | S | S3 | aucun | Aucune protection structurelle des frontières : pas de test d'architecture, pas de module-info, 160/219 types publics |
| `AUD-TST-02` | Moyenne | S | S3 | aucun | ProjectPathGuard (primitive de confinement) sans classe de test dédiée : 3 appels directs dans un seul test, 13/24 branches couvertes (54,2 %) |
| `AUD-ARC-03` | Moyenne | S | S4 | `AUD-QUA-09` | Cycle context ↔ security : la politique de sortie publique est rangée dans security et dépend des bundles de contexte |
| `AUD-QUA-01` | Moyenne | S | S4 | aucun | Les sorties MCP et REST exposent scoreComponents dans un ordre qui change à chaque démarrage de JVM |
| `AUD-SEC-02` | Moyenne | S | S4 | aucun | Une seule valeur sensible de plus de 4 096 caractères fait échouer l'indexation complète du projet et toute construction de contexte qui l'inclut |
| `AUD-PRF-01` | Moyenne | S | S5 | aucun | Aucun index sur symbol_relations(file_id) : chaque fichier réindexé ou supprimé déclenche un SCAN complet de la table (tous projets) |
| `AUD-PRF-02` | Moyenne | S | S6 | `AUD-DEP-19` | Le vecteur de la requête est recalculé par appel HTTP Ollama pour chaque projet de la fédération (jusqu'à 100 fois la même requête) |
| `AUD-PRF-04` | Moyenne | S | S6 | aucun | La recherche sémantique et la sonde de readiness REST ouvrent chacune un DirectoryReader Lucene complet, hors du cache de l'ADR 0045 |
| `AUD-PRF-07` | Moyenne | S | S6 | aucun | GitRecencyCandidateEnricher relit 50 commits et 50 diffs d'arbres à chaque recherche et pour chaque projet, sans cache |
| `AUD-SEC-04` | Moyenne | S | S8 | aucun | En mode NEXUS_REST_TRUST_LOCAL=true, l'API REST loopback sans token n'a ni contrôle Host/Origin ni allowlist de racines : exposée au DNS rebinding depuis un navigateur |
| `AUD-SEC-06` | Moyenne | S | S9 | aucun | Le PFX Authenticode et son mot de passe sont exposés à toutes les étapes du job Windows, y compris sur pull_request avec le code de la PR |

## Court terme — le mois qui vient, sprints 1 à 4 (23)

Le reste des quatre premiers sprints : mesures, outillage, filets de test, sortie publique.

| ID | Sévérité | Effort | Sprint | Dépend de | Constat |
|---|---|---|---|---|---|
| `AUD-QUA-05` | Moyenne | M | S2 | `AUD-QUA-04` | 12 scripts PowerShell redéfinissent chacun une fonction Invoke-Maven et 16 appellent `mvn` au lieu du wrapper |
| `AUD-QUA-09` | Moyenne | M | S4 | `AUD-TST-07` | Pas de projection publique unique : CLI, MCP et REST réappliquent chacun la politique de sortie et divergent sur les mêmes objets |
| `AUD-SEC-01` | Moyenne | M | S4 | aucun | La redaction, liste fermée documentée comme non exhaustive, ne couvre pas 19 formats de secrets courants (Bearer, token=, sk-, xoxb-, AIza, glpat-, PGP…) |
| `AUD-DEP-14` | Faible | S | S1 | aucun | Le benchmark d'échelle n'est pas un check requis et sa version post-merge sur develop ignore une partie des chemins couverts en PR |
| `AUD-DEP-20` | Faible | S | S1 | aucun | Deux gates requis mesurent moins que leur nom : « Windows gate » sur un JDK 24 non épinglé, CodeQL Java sans build |
| `AUD-TST-08` | Faible | S | S1 | aucun | Scripts de test orphelins : test-rest-token-contract.ps1 et Test-Hooks.ps1 appelés par aucun workflow ni script |
| `AUD-TST-11` | Faible | S | S1 | aucun | Tests symlink conditionnés par assume (15 sites) : un runner sans symlinks les ignore silencieusement |
| `AUD-DEP-10` | Faible | S | S2 | aucun | Le commentaire de version d'upload-artifact dans osv-scanner.yml est faux (« v4.6.2 » pour le SHA de v7.0.2) |
| `AUD-DEP-11` | Faible | S | S2 | aucun | Deux versions majeures d'actions/checkout coexistent : 11 usages en v4.4.0 et 8 en v7.0.1 |
| `AUD-DEP-12` | Faible | S | S2 | aucun | Les workflows réutilisables OSV sont épinglés sur un commit non tagué, sans version en commentaire |
| `AUD-DEP-15` | Faible | S | S2 | aucun | Plusieurs jobs n'ont pas de timeout-minutes (défaut GitHub 360 min) |
| `AUD-DEP-19` | Faible | M | S2 | aucun | La qualification sémantique réelle est instable : 16 des 25 derniers runs annulés, environ 2 h par run, hors gate de release |
| `AUD-DEP-22` | Faible | S | S2 | aucun | Les runners sont désignés par des libellés flottants -latest et aucun cache Maven n'est utilisé |
| `AUD-QUA-04` | Faible | S | S2 | aucun | Quatre scripts validate-iteration-11 à 14 ne sont référencés nulle part et deux pointent vers des JAR 0.1.0-SNAPSHOT inexistants |
| `AUD-SEC-07` | Faible | S | S2 | aucun | inputs.profile (type string) est interpolé directement dans des blocs run: de trois workflows |
| `AUD-QUA-07` | Faible | S | S3 | aucun | La récence Git avale toute exception sans diagnostic ni log |
| `AUD-TST-06` | Faible | S | S3 | aucun | Assertions de durée murale serrées dans 5 tests de la suite par défaut (500 ms à 2 s) |
| `AUD-TST-07` | Faible | S | S3 | aucun | Tests E2E monolithiques : NexusResourceTest et NexusMcpServerIntegrationTest = 1 @Test chacun pour 200 et 360 lignes |
| `AUD-TST-09` | Faible | S | S3 | aucun | 5 tests opt-in ne tournent dans aucun workflow (MINOS réel, baselines large/small-delta, 2 diagnostics sémantiques) |
| `AUD-TST-10` | Faible | S | S3 | aucun | IndexRepository : 98 lignes de méthodes default jamais exécutées (0/98, 0/56 branches) gonflent le dénominateur core |
| `AUD-PRF-18` | Faible | S | S4 | aucun | SemanticIndexingService redige deux fois le même document avant l'envoi d'embeddings |
| `AUD-QUA-08` | Faible | S | S4 | `AUD-TST-01` | Les cinq records d'opération de NexusApplication sont recopiés à l'identique dans le service REST |
| `AUD-SEC-03` | Faible | S | S4 | aucun | Les signatures de symboles issues de SCIP/MINOS/JDT sont renvoyées par REST, MCP et CLI sans passer par la redaction |

## Fond — chantiers structurels, sprints 5 à 11 (45)

Indexation et fédération, cycles de packages (chantier L sous protection du test d'architecture), façade et configuration, chaîne de release, conteneur, finitions.

| ID | Sévérité | Effort | Sprint | Dépend de | Constat |
|---|---|---|---|---|---|
| `AUD-QUA-10` | Moyenne | M | S6 | aucun | Méthodes longues concentrées dans le contexte fédéré, l'indexation et la découverte Git/skills |
| `AUD-ARC-01` | Moyenne | L | S7 | `AUD-ARC-02`, `AUD-ARC-03`, `AUD-ARC-04`, `AUD-ARC-05`, `AUD-ARC-06`, `AUD-ARC-07` | Composante fortement connexe de 12 packages du cœur (context, context.source, .git, .skill, index, index.scan, ranking, ranking.graph, search, search.semantic, search.semantic.ollama, security) |
| `AUD-ARC-02` | Moyenne | M | S7 | `AUD-ARC-07` | Cycle index ↔ search : le package index mêle modèle de domaine, port de persistance et orchestration qui dépend des ports de recherche |
| `AUD-DEP-02` | Moyenne | M | S9 | `AUD-DEP-03`, `AUD-DEP-09`, `AUD-DEP-21`, `AUD-QUA-12`, `AUD-SEC-06` | Le workflow Release n'a jamais été exécuté : 0 run et 0 release/tag publiés |
| `AUD-ARC-10` | Faible | S | S5 | aucun | Écriture MINOS orchestrée par la façade et la CLI, hors ProjectIndexingService |
| `AUD-ARC-13` | Faible | S | S5 | aucun | ProjectIndexingService expose cinq constructeurs publics télescopiques dont deux retombent silencieusement sur un verrou process-local |
| `AUD-PRF-08` | Faible | M | S5 | aucun | Chaque indexation, même incrémentale sans changement, hache intégralement tous les fichiers deux fois (scan initial + scan final) |
| `AUD-PRF-11` | Faible | M | S5 | aucun | Indexation Lucene : un IndexWriter ouvert et un commit (fsync) par lot de 128 fichiers |
| `AUD-PRF-12` | Faible | M | S5 | aucun | Les résultats d'analyse (symboles, relations) de tous les fichiers modifiés restent en mémoire jusqu'à l'écriture SQLite finale |
| `AUD-PRF-14` | Faible | S | S5 | aucun | Écriture par fichier : 4 prepareStatement + un SELECT supplémentaire après chaque upsert |
| `AUD-PRF-15` | Faible | S | S5 | aucun | Index redondants qui amplifient les écritures : préfixes stricts d'autres index |
| `AUD-PRF-03` | Faible | M | S6 | `AUD-QUA-10` | La fédération n'a pas de deadline globale : un projet lent retarde toute la requête sans diagnostic du projet non servi |
| `AUD-PRF-06` | Faible | S | S6 | aucun | Cache de contexte Git limité à 16 entrées alors que le scope fédéré monte à 100 projets : éviction LRU systématique sur une requête fédérée répétée |
| `AUD-PRF-09` | Faible | M | S6 | aucun | Recherche de symboles : index FTS trigram et préfiltre fuzzy globaux (non scopés projet), jusqu'à 9 recherches × 3 requêtes SQL par projet |
| `AUD-ARC-04` | Faible | S | S7 | `AUD-ARC-07` | Cycles context ↔ context.source / .git / .skill : les ports de sources dépendent du modèle de contexte qui dépend d'eux |
| `AUD-ARC-05` | Faible | S | S7 | `AUD-ARC-07` | Cycle search ↔ ranking : le ranker dépend des candidats de recherche, le service de recherche dépend du ranker |
| `AUD-ARC-06` | Faible | S | S7 | `AUD-ARC-07` | Le package de ports sémantiques instancie l'implémentation Ollama (cycle search.semantic ↔ search.semantic.ollama) |
| `AUD-ARC-09` | Faible | S | S8 | aucun | Séquence de lecture cohérente (readiness → verrou partagé → session → readiness) dupliquée dans six méthodes de la façade |
| `AUD-ARC-11` | Faible | S | S8 | aucun | SQLException (technologie de persistance) fait partie du contrat public de la façade et des points d'entrée |
| `AUD-ARC-12` | Faible | M | S8 | aucun | Configuration lue directement dans l'environnement par 12 classes (16 appels à System.getenv) du cœur, hors composition root |
| `AUD-ARC-14` | Faible | S | S8 | aucun | Responsabilités mélangées dans config.NexusPaths (résolution des chemins + durcissement POSIX/ACL du stockage) et code de test dans le jar (search.evaluation) |
| `AUD-ARC-19` | Faible | S | S8 | aucun | Le package index.jdt définit une classe nommée ProcessBuilder qui masque java.lang.ProcessBuilder |
| `AUD-SEC-05` | Faible | S | S8 | aucun | La découverte JGit remonte les répertoires parents sans plafond ni contrôle de propriétaire du dépôt trouvé |
| `AUD-DEP-03` | Faible | S | S9 | aucun | Le job de publication n'utilise aucun environnement GitHub protégé ; la protection des tags v*.*.* n'est pas attestée |
| `AUD-DEP-05` | Faible | S | S9 | aucun | Aucune propriété project.build.outputTimestamp : les JAR et ZIP produits ne sont pas reproductibles bit à bit |
| `AUD-DEP-06` | Faible | S | S9 | aucun | L'enforcer ne contrôle ni versions de plugins ni convergence ; les plugins implicites (jar, resources, install, deploy, clean) ne sont pas épinglés |
| `AUD-DEP-07` | Faible | S | S9 | aucun | La génération des notices de licences ne bloque aucune licence incompatible |
| `AUD-DEP-09` | Faible | S | S9 | aucun | Les assets Windows (ZIP, setup.exe) ne reçoivent aucune attestation de provenance |
| `AUD-DEP-21` | Faible | S | S9 | aucun | release.yml transmet tous les secrets du dépôt au workflow Windows avec secrets: inherit |
| `AUD-QUA-12` | Faible | S | S9 | aucun | Version 0.2.0 codée en dur dans la CI et le gate Windows |
| `AUD-DEP-04` | Faible | S | S10 | aucun | Dans l'image Docker, les scripts d'entrée et les répertoires applicatifs appartiennent à l'UID d'exécution 10001 |
| `AUD-DEP-08` | Faible | S | S10 | aucun | L'image Docker publiée n'embarque ni LICENSE ni THIRD_PARTY_NOTICES ni labels OCI |
| `AUD-DEP-16` | Faible | S | S10 | aucun | Le ruleset autorise squash et rebase alors que la politique impose le merge commit |
| `AUD-DEP-17` | Faible | S | S10 | aucun | Le dépôt public n'a ni SECURITY.md ni CODEOWNERS |
| `AUD-DEP-18` | Faible | S | S10 | aucun | Durcissement conteneur incomplet : pas d'instruction HEALTHCHECK dans l'image, compose sans cap_drop ni no-new-privileges |
| `AUD-DEP-31` | Faible | S | S10 | aucun | La montée Quarkus 3.40.1.1 (PR #262) échoue sur un contrat documentaire qui recopie le numéro de version |
| `AUD-SEC-08` | Faible | S | S10 | aucun | Absence de .dockerignore : `COPY . .` envoie tout le contexte de build (.git, fichiers locaux .env, données) dans l'étape builder |
| `AUD-PRF-10` | Faible | S | S11 | aucun | La décision de garder le journal rollback (sans WAL) est mesurée mais consignée hors ADR |
| `AUD-PRF-13` | Faible | S | S11 | aucun | Troncature de fragment quadratique : reconstruction du préfixe complet et réestimation à chaque ligne |
| `AUD-PRF-16` | Faible | S | S11 | aucun | Voisinage graphe sortant paginé par OFFSET : chaque page rescanne les pages précédentes |
| `AUD-PRF-17` | Faible | S | S11 | aucun | Regex compilées à chaque appel sur des chemins chauds d'import et de découverte |
| `AUD-QUA-02` | Faible | S | S11 | aucun | Le graphe dérivé est documenté comme cache par génération alors que le code ne le matérialise plus ; ProjectGraph est du code mort |
| `AUD-QUA-03` | Faible | S | S11 | aucun | AGENTS.md prescrit `mvn clean install` alors que les hooks du dépôt refusent `mvn` hors wrapper |
| `AUD-QUA-11` | Faible | M | S11 | aucun | Environ un tiers des messages d'exception sont en anglais malgré la convention française |
| `AUD-QUA-13` | Faible | S | S11 | aucun | NXA-20260914-05 partiellement ouvert : le watch item FTS/trigram reste présenté comme futur |
