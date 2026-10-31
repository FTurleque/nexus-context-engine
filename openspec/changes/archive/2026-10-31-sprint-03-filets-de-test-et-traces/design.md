# Design

## Context

Le cœur `com.nexus` est un seul module Maven (ADR-0004), sans framework applicatif (ADR-0003). SQLite est la source de vérité (ADR-0006), Lucene un index dérivé (ADR-0007, ADR-0022), et les adaptateurs REST et MCP sont minces (ADR-0039, ADR-0040). Ces règles ne reposent que sur la revue : aucun test ne les vérifie.

Les tests de contrat REST (`@QuarkusTest`) et MCP rejouent tout le parcours dans une seule méthode : la première assertion en échec masque les contrats suivants. Le signal de récence Git est volontairement fail-open (ADR-0035) mais n'en laisse aucune trace. `.claude/rules/tests.md` interdit les assertions sur l'horloge et `.claude/rules/java-core.md` les abandons silencieux.

**Dépendances envers d'autres sprints.**
- Sprint 7 (`sprint-07-casser-le-bloc-cyclique-du-coeur`) : utilise le test d'architecture et sa baseline de cycles ; il filtre ses tests par `-Dtest='*Architecture*'`. Le nom de classe `CoreArchitectureTest` respecte ce filtre.
- Sprint 4 (`AUD-QUA-09`, projection publique unique) : s'appuie sur le découpage d'`AUD-TST-07`.
- Sprint 2 (`AUD-QUA-05`, `AUD-DEP-19`) : si les scripts `measure-iteration-1x-*.ps1` sont archivés au sprint 2, les tests opt-in d'`AUD-TST-09` perdent leur seul lanceur ; la décision D7 en tient compte.
- Sprint 1 (fusionné, #267) : `SymbolicLinks.createOrSkip`/`requireSupport` est le seul moyen de créer un symlink de test ; `ProjectPathGuardTest` l'utilise.

## Goals / Non-Goals

**Goals:**
- Un test d'architecture rapide, sans dépendance nouvelle, dans la suite par défaut.
- Un échec de contrat REST ou MCP isolé dans son propre test.
- Une trace pour tout incident absorbé par la récence Git.
- Une couverture directe de la primitive de confinement.
- Une suite par défaut qui ne dépend pas de la charge du runner.

**Non-Goals:**
- Casser les cycles existants (sprint 7).
- Ajouter `module-info.java` ou réduire la visibilité des types publics.
- Contrôler les imports des adaptateurs depuis le test du cœur.

## Decisions

**D1 — Test d'architecture sans dépendance.** `CoreArchitectureTest` lit les sources de `core/src/main/java`, extrait les déclarations `package` et `import` (y compris `import static`), et calcule le graphe des packages, comme le fait le graphe d'imports d'ADR-0026. Alternative écartée : ArchUnit, qui ajoute une dépendance de test, son graphe transitif et une surface supply-chain pour des règles exprimables en quelques dizaines de lignes. L'analyse de sources plutôt que de bytecode évite de dépendre de la compilation et reste déterministe.

**D2 — Règles nommées.** Chaque règle a un nom stable et son message d'échec cite la classe et l'import fautifs :
- `core-sans-framework` : aucun import `io.quarkus`, `jakarta`, `javax.ws`, `org.springframework`, `io.modelcontextprotocol` ;
- `lucene-confine` : `org.apache.lucene` seulement dans `search.lucene` et `search.semantic.lucene` ;
- `sqlite-confine` : `org.sqlite` et `java.sql` seulement dans `persistence.sqlite`, avec une liste d'exceptions explicite et fermée (`java.sql.SQLException` dans `application.NexusApplication`, `application.NexusApplicationFactory` et `cli.NexusCli`) ;
- `project-feuille` : `com.nexus.project` n'importe aucun autre package `com.nexus`.

**D3 — Baseline de cycles décroissante.** Le test calcule les composantes fortement connexes du graphe des packages et les compare à un fichier de baseline versionné (`core/src/test/resources/architecture/package-cycles.txt`), une arête par ligne, triée. Une arête de cycle absente de la baseline fait échouer le test ; une ligne de baseline qui ne correspond plus à une arête de cycle fait aussi échouer le test, pour que la baseline soit retirée dès qu'un cycle est cassé. Le sprint 7 vide ce fichier.

**D4 — ADR complémentaire.** Le passage d'une discipline de revue à un contrôle automatique des frontières est une décision d'architecture : un nouvel ADR (skill `nexus-adr`) complète ADR-0004, sans le modifier.

**D5 — Découpage E2E par fixture partagée.** Le projet de test, son indexation et l'identifiant de projet sont préparés une fois par classe (`@BeforeAll` avec `@TestInstance(PER_CLASS)` et un répertoire temporaire de classe) ; chaque endpoint REST ou outil MCP a son `@Test`. Les assertions de non-fuite (pas de chemin absolu, pas de secret, pas de modèle du cœur) restent dans chaque test qui lit une réponse. L'ordre d'exécution n'est pas une hypothèse : un test qui modifie l'état (réindexation) recrée sa propre fixture.

**D6 — Journalisation fail-open.** `GitRecencyCandidateEnricher` obtient un `System.Logger` et journalise au niveau `WARNING` le type d'exception et un message sans chemin absolu ni contenu de fichier ; `RepositoryNotFoundException` reste silencieux (cas normal d'un projet hors Git). Si l'appel porte une demande d'explication, une raison « récence Git indisponible » est ajoutée, sur le modèle du diagnostic de `LocalGitContextSourceProvider`. Le résultat reste les candidats inchangés.

**D7 — Tests opt-in.** `MinosRealIntegrationTest` est lancé par un workflow `workflow_dispatch` (et planifié si MINOS est installable sur le runner) ; les baselines `LargeScaleSearchBaselineTest` et `SmallDeltaIndexingBaselineTest` et les deux diagnostics sémantiques sont soit lancés par un workflow manuel, soit déplacés hors de `src/test` vers un outillage de mesure documenté. Le choix se fait par test et est consigné dans `docs/developer/ci-and-supply-chain.md`. Un test opt-in ne reste pas dans `src/test` sans lanceur automatisé.

**D8 — Durées murales.** Les assertions qui vérifient « ne pas attendre le worker récalcitrant » comparent la durée observée au délai du worker (borne = délai du worker, pas un seuil absolu serré) ou vérifient un état (tâche abandonnée, compteur de timeouts) plutôt qu'une durée. `assertTimeoutPreemptively` est gardé quand il protège contre un blocage infini, avec une marge d'au moins ×5 sur la durée nominale. Aucun seuil n'est abaissé.

**D9 — `IndexRepository`.** Les méthodes `default` que `SqliteIndexRepository` surcharge et dont aucune autre implémentation n'a besoin deviennent abstraites ; le fake de `ProjectGraphBuilderProjectionTest` implémente ce dont il a besoin. Une méthode `default` qui garde un sens de repli est testée par un fake minimal.

## Risks / Trade-offs

- [Le parseur d'imports manque un cas (import dans un commentaire, nom pleinement qualifié dans le code)] → la règle porte sur les déclarations `import` ; les noms pleinement qualifiés sont détectés par un motif secondaire sur `org.apache.lucene.`, `java.sql.`, `org.sqlite.` ; un test du parseur couvre ces cas.
- [La baseline de cycles masque une érosion pendant le sprint 3] → elle ne peut que décroître et toute nouvelle arête de cycle fait échouer le build.
- [La fixture partagée rend les tests E2E dépendants entre eux] → aucun test ne suppose l'ordre ; ceux qui mutent l'état recréent leur fixture.
- [Le log `WARNING` est bruyant sur un dépôt corrompu] → un message par appel, sans pile ; le niveau est revu si le bruit est constaté.
- [Supprimer des `default` casse une implémentation externe] → `IndexRepository` est un port interne au cœur, sans implémentation hors du dépôt.
