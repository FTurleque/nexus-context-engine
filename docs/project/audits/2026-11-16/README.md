# Audit technique — NEXUS Context Engine — 16 novembre 2026

**Périmètre.** origin/main @ `7ec5c5dc`, v0.2.1. Profil complet, six axes. Sont couverts le cœur Java (263 fichiers main, environ 31 000 lignes), les trois adaptateurs, le packaging Docker et Windows, les 20 workflows et les scripts. L'audit est indépendant de celui du 17/10 : ses constats ont été relevés sur le code actuel, puis rapprochés de l'ancien audit (champ « Audit du 17/10 » de chaque constat).

**Preuves d'exécution.** `mvnw.cmd -B -ntp verify` sur ta machine Windows, au même commit : BUILD SUCCESS en 3 min 51 s, 1 159 tests (core 997, REST 95, MCP 43, assistant 24), 0 échec, 26 ignorés. Couverture du core : 87,15 % des lignes et 72,82 % des branches. CoreArchitectureTest passe (31/0). Les fuites de redaction ont été rejouées sur les classes v11 compilées par ce build.

## Notes par axe

Calcul : 100, moins 25 par Critique, 10 par Élevée, 4 par Moyenne et 1 par Faible ; les constats Info ne pèsent pas.

Ces notes ne se comparent pas aux 100/100 du 15/11. Ces dernières mesuraient ce qui restait de la liste d'octobre ; celles-ci notent une liste neuve, relevée sur le même code. L'écart ne traduit pas une régression : aucun constat n'est Critique, un seul est Élevé (AUD-SEC-01), et 35 constats Info documentent ce qui tient. Architecture perd 6 points sur des écarts de rangement et de documentation, pas sur la structure : le graphe des packages est acyclique et verrouillé par un test.

| Axe | Note | Élevée | Moyenne | Faible | Info | Calcul |
|---|---:|---:|---:|---:|---:|---|
| Architecture | **94** | 0 | 0 | 6 | 5 | 100 − 6×1 |
| Qualité | **84** | 0 | 2 | 8 | 4 | 100 − 2×4 − 8×1 |
| Sécurité | **81** | 1 | 1 | 5 | 7 | 100 − 1×10 − 1×4 − 5×1 |
| Tests | **93** | 0 | 1 | 3 | 9 | 100 − 1×4 − 3×1 |
| Performance | **89** | 0 | 2 | 3 | 4 | 100 − 2×4 − 3×1 |
| Dépendances | **91** | 0 | 1 | 5 | 6 | 100 − 1×4 − 5×1 |

73 constats : 1 Élevée, 7 Moyenne, 30 Faible, 35 Info. 38 constats sont actionnables. Les fusions et les changements de sévérité viennent d'une relecture indépendante qui a revu 22 constats dans le code : AUD-SEC-01 est passé en Élevée, AUD-SEC-06 en Faible, et AUD-DEP-06, AUD-TST-01 et AUD-TST-03 en Info.

## Cinq constats majeurs

1. **AUD-SEC-01** (Élevée, S) — Une clé sensible placée juste après la quote ouvrante d'une chaîne ("password=…", "POSTGRES_PASSWORD=…", "Authorization: …") n'est jamais masquée. Une affectation sensible écrite dans une chaîne (chaîne de connexion, variable d'environnement passée à docker/compose dans un README, en-tête HTTP dans un exemple curl) part en clair dans Lucene, les embeddings (Ollama éventuellement distant) et les sorties CLI/REST/MCP servies aux agents.
1. **AUD-SEC-02** (Moyenne, M) — Les formes d'affectation courantes des langages indexés (camelCase composé, indice de dictionnaire, appel put/setProperty, SQL) laissent la valeur en clair. Les noms composés en camelCase sont la convention Java/Kotlin/TypeScript (langages cibles de NEXUS) : un secret littéral dans un champ `dbPassword` ou `jwtSecret` est publié en clair aux agents et dans les embeddings.
1. **AUD-QUA-01** (Moyenne, S) — Les erreurs internes REST et MCP sont converties en réponse générique sans aucune trace côté serveur. La politique de sortie publique (pas de chemin ni de stack, AUD-SEC-13) est respectée, mais l'opérateur n'a aucun moyen de diagnostiquer un 500 REST, une erreur d'outil MCP ou une readiness dégradée : l'exception et sa cause sont perdues.
1. **AUD-PRF-02** (Moyenne, M) — Une panne transitoire d'Ollama (429, 5xx, délai) fait échouer toute l'indexation et perd tous les embeddings déjà calculés, sans aucune nouvelle tentative. Sur un dépôt réel, un rebuild sémantique sur CPU dure des heures ; un seul 503 ou dépassement de 60 s (proche du coût d'un document de 12 000 caractères sur une machine chargée) annule tout le travail : projet FAILED, lexical et structurel non publiés, et le passage suivant repart d'un rebuild complet, embeddings compris.
1. **AUD-DEP-01** (Moyenne, S) — Quarkus épinglé en 3.40.1.1 alors que la release d'urgence sécurité 3.40.1.2 du 09/11/2026 corrige trois CVE. brotli4j 1.23.0 (RCE, CVE-2026-111497) est bien livré dans le classpath REST via quarkus-vertx-http.

## Thèmes transverses

**La redaction promet plus que son balayeur ne tient.** La v11 masque bien les formes qu'elle teste, et son coût reste linéaire. Mais le balayeur d'affectations est écrit à la main, sur une liste fermée de formes. Un saut de curseur laisse passer une forme que la doc annonce couverte (AUD-SEC-01), et les conventions des langages que NEXUS indexe (camelCase, dictionnaires, put, SQL) restent en clair. Le texte ressort dans Lucene, dans les embeddings et dans les réponses aux agents. La même redaction s'exécute en plus trois fois par fichier. (AUD-SEC-01, AUD-SEC-02, AUD-SEC-03, AUD-SEC-04, AUD-SEC-05, AUD-PRF-03, AUD-SEC-10)

**Des gates qui dérivent sans échouer.** Chaque garde-fou est vert, mais plusieurs ne voient plus ce qu'ils gardent. La table de couverture est périmée, les paquets critiques n'ont que le seuil global, et deux bancs n'ont pas de seuil quand deux autres n'ont pas de lanceur. Le gate Docker ignore le wrapper, deux gates requis tournent sur un JDK flottant, et la matrice d'architecture ne couvre que 4 sens. D'où le sprint 1. (AUD-TST-14, AUD-TST-04, AUD-TST-05, AUD-PRF-04, AUD-DEP-02, AUD-DEP-04, AUD-ARC-01)

**Un serveur qui ne laisse pas de trace.** Les erreurs 500 REST, les erreurs d'outil MCP et une readiness dégradée sont converties en réponses propres, sans le moindre log serveur. La CLI et le serveur MCP n'ont d'ailleurs pas de backend de logs. Les corrections de fond qui suivent pourraient régresser sans que personne ne le voie : l'observabilité passe donc au sprint 2. (AUD-QUA-01, AUD-DEP-05, AUD-PRF-02)

**Des chantiers d'octobre fermés d'un seul côté.** L'audit précédent a supprimé l'écriture par lot côté SQLite, mais pas côté Lucene, où elle survit dans 2 interfaces et 5 classes. Il a découpé deux méthodes longues et laissé les deux autres qu'il citait. Il a sorti l'orchestration d'index, mais pas les verrous. Ce sont des restes, pas des régressions. (AUD-QUA-02, AUD-QUA-03, AUD-QUA-08, AUD-ARC-04)

**Des contrats portés par du texte plutôt que par des types.** Le statut 409 est déduit d'un message en français. Les métadonnées de contexte sont une Map de 68 clés littérales, la sémantique désactivée est un null, et chaque surface recode ses entrées. Rien n'est cassé, mais chaque évolution du cœur risque de casser une surface sans erreur de compilation. (AUD-QUA-06, AUD-QUA-04, AUD-ARC-05, AUD-ARC-03, AUD-SEC-06)

## Sprints

| Sprint | Titre | Constats | Charge | Critère de sortie (résumé) |
|---|---|---:|---|---|
| S1 | Remettre la CI et les mesures d'aplomb | 5 | 1,25 à 5 j | Quarkus est en 3.40.1.2 dans pom.xml et le gate OSV du SBOM agrégé passe. |
| S2 | Couverture à jour et erreurs serveur tracées | 6 | 2,25 à 8 j | La table de adapter-coverage-gates.md porte une mesure Linux et Windows au même commit, dénominateurs à jour. |
| S3 | Redaction v12 : formes d'affectation du code | 5 | 2 à 7 j | POLICY_VERSION vaut secret-redaction-v12 et le profil sémantique passe à content-v11. |
| S4 | Robustesse des chemins de requête et d'indexation | 4 | 2,5 à 8 j | Le banc d'échelle a un cas de requête de moins de 3 caractères avec un budget p95 dans verify-scale-budgets.py. |
| S5 | Frontières du cœur et API d'index unique | 8 | 3,5 à 12 j | Un nouvel ADR, qui complète ARCH-0076, porte la matrice complète des sens. |
| S6 | Contrats des adaptateurs et du contexte servi | 5 | 2,75 à 9 j | REST et MCP mappent ProjectNotReadyException et ProjectIndexBusyException par type, et IllegalStateExceptionMapper ne contient plus de préfixe de message. |
| S7 | Hygiène du code, des scripts et de l'installateur | 5 | 3,5 à 11 j | LocalGitContextSourceProvider.discover, SemanticHybridContextRanker.rank et ScipSnapshotMapper.importDocument font moins de 80 lignes, et une règle outillée (test d'architecture ou script de contrats) en garde la mesure. |

Charge totale : 17,75 à 60 jours. C'est une enveloppe, hors revue, CI et décisions écrites. Le détail est dans [sprints.md](sprints.md).

## Chemin critique

- **AUD-ARC-12** (S) : l'ADR qui complète ARCH-0076 conditionne tout le sprint 5 (ARC-02, ARC-04, puis la matrice d'ARC-01) et, par ricochet, SEC-06 et QUA-05.
- **AUD-PRF-04** (S) : brancher et seuiller les bancs (dont le débit de redaction) rend mesurables les quatre corrections de redaction du sprint 3.
- **AUD-SEC-01** (S) : corriger le saut de curseur, la seule fuite Élevée, ouvre la v12 sur laquelle s'empilent SEC-02 et SEC-05, et fixe le numéro de migration que PRF-01 suit.
- **AUD-TST-13** (S) : tester le découpage en fenêtres avant de remesurer la couverture évite une double remesure (TST-14, puis TST-04).

## Angles morts

- Pas de graphe d'appels complet : les dépendances sont reconstruites à partir des imports, au niveau package. Le code atteint par réflexion, ServiceLoader ou injection CDI est mal couvert, et le code de test n'est pas dans le graphe.
- Aucune exécution sous Linux : le proxy de la session refuse Maven Central. Le build et les mesures viennent d'un `mvnw.cmd -B -ntp verify` lancé sur ta machine Windows, au même commit, avec 1 159 tests verts.
- CVE des dépendances transitives non vérifiées : `dependency:tree` et osv.dev étaient inaccessibles. Seules les versions directes ont été comparées par recherche web.
- Réglages GitHub non relus (rulesets, alertes Dependabot, derniers runs CI) : l'API GitHub était inaccessible. AUD-DEP-06 repose sur la documentation du 11/11.
- Comportement à l'exécution non observé : aucun appel REST ou MCP réel. Les coûts d'AUD-PRF-01 ont été mesurés avec le SQLite de Python ; la course sur une jonction Windows (AUD-SEC-07) est déduite de la sémantique du JDK.
- Modules hors du cœur survolés : index.jdt, index.scip, index.minos, AssistantIntegrationGenerator, l'installateur Inno Setup et les scripts de release. Ils ont été lus de façon ciblée, sans être exécutés.

## Fichiers

- [findings.md](findings.md) / [findings.json](findings.json) : les constats, avec leur sprint et leurs dépendances
- [sprints.md](sprints.md) / [sprints-meta.json](sprints-meta.json) : l'ordre de correction
- [plan-action.md](plan-action.md) : le plan en trois temps
- [architecture.md](architecture.md) : le découpage observé
