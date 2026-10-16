# Audit indépendant du code NEXUS — 12 septembre 2026

> **Document historique.** Les constats et les sorties de reproduction ci-dessous
> décrivent l'état du commit audité avant correction. Les correctifs sont appliqués
> dans le working tree courant ; les validations post-correction sont rapportées
> séparément par l'agent.

## Résultat

**Sept défauts confirmés par des reproductions locales : deux P1 et cinq P2.** Le build et le self-smoke passent, mais ne couvrent pas ces scénarios.

Code examiné : `1a46eaa006effc2c56358fc52f98fac1c688d873`, version `0.2.0`. Le dépôt était propre au début de l'analyse. Aucun rapport d'audit antérieur n'a servi de base aux constats. Les guides développeur, l'architecture et la roadmap ont été consultés pour identifier les contrats à confronter au code, pas comme preuves de leur respect.

P1 désigne ici un défaut à traiter en priorité pour la confidentialité ou la disponibilité. P2 désigne un défaut de cohérence, de sélection ou de robustesse nécessitant une correction. Aucun P0 n'a été établi.

| Référence | Priorité | Défaut | Reproduction |
|---|---|---|---|
| A01 | P1 | Secrets sous clés entre guillemets non masqués | Redactor, contexte et entrée du provider d'embeddings |
| A02 | P1 | Timeout Ollama inopérant pendant la lecture du corps HTTP | Réponse après 2 607 ms avec timeout de 500 ms |
| A03 | P2 | Lecture admise sur READY puis exécutée pendant un rebuild | Recherche réussie avec zéro résultat pendant INDEXING |
| A04 | P2 | Un seul terme répété contourne la limitation Lucene | `TooManyClauses` avec une requête valide de 6 599 octets |
| A05 | P2 | Le graphe et les symboles réintroduisent les ressources de skills | Ressource de skill renvoyée dans un contexte FILE uniquement |
| A06 | P2 | Le budget Git est vérifié après matérialisation du diff complet | 10 000 entrées produites avec budget d'une visite |
| A07 | P2 | Index lexical absent, réindexation annoncée réussie et READY | Un résultat devient zéro ; seul le rebuild explicite le restaure |

Les sources des reproductions et les observations sont dans [2026-09-12-evidence](2026-09-12-evidence/observations.txt). Les fixtures utilisent exclusivement des données synthétiques et des répertoires temporaires. Aucun code de production n'a été corrigé pendant cet audit.

## A01 — Masquer aussi les valeurs associées aux clés entre guillemets

**Emplacement :** `core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:36–40`.

L'expression `SECRET_ASSIGNMENT` attend les espaces puis `:` ou `=` immédiatement après le nom de la clé. Le guillemet fermant de `"password": "…"` empêche donc la correspondance. La même faiblesse touche les objets JavaScript, les dictionnaires Python et les exemples JSON dans la documentation.

La reproduction indexe un fichier JavaScript valide contenant :

```javascript
export const settings = {"password":"AuditSyntheticPassword98765","feature":"auditneedle"};
```

Le mot de passe synthétique reste présent après `redact`, dans le texte envoyé au provider d'embeddings de capture, puis dans un item du contexte demandé sur `auditneedle`. Les barrières successives appellent le même redactor et conservent donc la même valeur. Le provider de capture reste local : aucun secret réel ni aucune donnée projet n'a été envoyé à un service externe.

```text
JSON_REDACTOR_LEAK=true
JSON_EMBEDDING_LEAK=true
JSON_CONTEXT_LEAK=true
```

**Impact :** des valeurs explicitement nommées comme secrets peuvent sortir dans le contexte ou être transmises à l'endpoint sémantique configuré. Ce n'est pas une demande de détecter tout secret arbitraire : les noms de clés appartiennent déjà à la politique existante.

**Correction attendue :** couvrir les clés citées et leurs échappements sans dégrader les cas non secrets ; vérifier JavaScript, Python et JSON embarqué de bout en bout. Prévoir l'invalidation des projections lexicales/sémantiques concernées : corriger seulement les nouvelles écritures laisserait les anciens dérivés exposés.

**Précision :** le scanner natif ne prend pas en charge les fichiers `.json` autonomes. Le constat de bout en bout porte sur `settings.js`, pas sur une extension non indexée.

## A02 — Appliquer le délai Ollama jusqu'à la fin du corps de réponse

**Emplacements :** `core/src/main/java/com/nexus/search/semantic/ollama/OllamaEmbeddingProvider.java:217`, `:231` et `:274`.

`HttpRequest.timeout` accompagne `send(..., BodyHandlers.ofInputStream())`. Dès que le flux est disponible, `readBoundedResponse` le consomme synchroniquement sans deadline ni mécanisme de fermeture à expiration. La limite d'octets ne limite pas le temps passé dans `input.read`.

Un faux serveur exclusivement sur `127.0.0.1` envoie les en-têtes et un premier octet, attend 2,5 secondes, puis termine un petit JSON valide. Avec un timeout de 500 ms, `embed` réussit après 2 607 ms au lieu d'échouer dans le délai.

**Impact :** un endpoint bloqué après ses en-têtes peut immobiliser les recherches sémantiques. Lors d'une indexation, l'appel sémantique conserve également la mutation et son permis de capacité ; deux opérations bloquées peuvent occuper la capacité d'indexation par défaut. Les embeddings ne passent pas par le timeout d'`ExternalTaskRunner` réservé aux importers/providers de Code Intelligence.

**Correction attendue :** borne temporelle couvrant l'intégralité de la réponse, annulation et fermeture effectives du flux, puis retour vers la dégradation prévue pour un provider indisponible. Conserver le plafond d'octets. Ajouter un test qui transmet les en-têtes immédiatement et retarde uniquement le corps.

La documentation officielle précise que le corps peut encore être incomplet lorsque la réponse `ofInputStream` est rendue : [Java 21 — BodyHandlers.ofInputStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandlers.html#ofInputStream()). Le dépassement de délai a été mesuré localement, pas seulement déduit de cette documentation.

## A03 — Protéger la génération pendant toute la lecture

**Emplacements :** `core/src/main/java/com/nexus/application/NexusApplication.java:342–345`, `core/src/main/java/com/nexus/search/SearchService.java:46–55` ; mutations dans `ProjectIndexingService.java:239` et suivantes.

Le contrôle READY produit un `ProjectDescriptor` immuable avant la recherche. Le second contrôle dans `SearchService` relit ce descripteur, pas l'état persistant. Aucun verrou partagé ou contrôle de génération n'encadre l'ensemble des lectures Lucene, SQLite et graphe. Le verrou de mutation ne couvre que les écrivains.

La reproduction suspend la première stratégie après le contrôle READY, démarre un rebuild doté du verrou fichier de production, puis suspend ce rebuild après son `deleteAll` commité. La lecture est ensuite libérée : elle retourne normalement une liste vide alors que l'état persistant vaut `INDEXING` et que le corpus contient toujours le fichier recherché.

```text
RACE_PERSISTED_STATE=INDEXING
RACE_SUCCESSFUL_READ_HITS=0
```

**Impact :** une requête admise juste avant une mutation peut retourner un résultat partiel ou combiner plusieurs générations. La fédération augmente la fenêtre entre validation et lecture d'un projet.

**Correction attendue :** définir une session de lecture cohérente avec les mutations, y compris inter-processus, ou publier des générations immuables et revalider une identité de génération avant restitution. Une simple relecture de READY en fin d'appel ne couvre pas un cycle complet READY → INDEXING → READY.

**Nature de la preuve :** les ports sont enveloppés uniquement pour contrôler l'ordonnancement par des latches. Les implementations SQLite/Lucene et les services exécutés sont ceux du JAR construit. Il ne s'agit pas d'un test de concurrence HTTP complet.

## A04 — Utiliser le terme dédupliqué même lorsqu'il est seul

**Emplacement :** `core/src/main/java/com/nexus/search/lucene/LuceneSearchIndex.java:173–175`.

Après analyse et déduplication, la branche `analyzedTerms.size() < 2` repasse la requête brute entière à `MultiFieldQueryParser`. Les répétitions sont donc réintroduites et expansées sur les champs Lucene.

`QueryPolicy.normalize("alpha ".repeat(1100))` produit une requête acceptée de 6 599 octets, sous la limite de 16 KiB, avec un seul terme distinct. La recherche lève une `IOException` causée par `ParseException`, elle-même causée par `IndexSearcher.TooManyClauses`.

**Impact :** une entrée pourtant acceptée casse la recherche, puis les opérations de contexte qui en dépendent. Le plafond de 128 termes uniques ne protège pas cette branche.

**Correction attendue :** construire la requête depuis les termes déjà bornés dans toutes les branches ; traiter explicitement l'absence de terme analysable. Ajouter les répétitions d'un même terme aux tests de forte cardinalité, sans relever la limite globale de clauses pour masquer le défaut.

## A05 — Préserver l'exclusion des skills dans les stratégies secondaires

**Emplacements :** `core/src/main/java/com/nexus/ranking/graph/GraphCandidateEnricher.java:91–110` et `core/src/main/java/com/nexus/search/SymbolSearchStrategy.java:64–72`.

L'indexation lexicale exclut `SKILL`, `INSTRUCTION` et `AGENT_PROFILE`. Cependant, les analyses structurelles de ces fichiers restent persistées. Le graphe transforme toute catégorie autre que TEST en FILE sans appliquer les mêmes exclusions ; la stratégie de symboles ne filtre pas non plus leur catégorie canonique.

La reproduction utilise uniquement JavaParser, sans import externe :

1. `Root.java` déclare `demo.Root`.
2. `.agents/skills/audit-helper/scripts/Helper.java` importe `demo.Root` et contient un marqueur distinct.
3. Un contexte demandé sur `Root` avec `requestedSources={FILE}` renvoie le helper comme FILE.

```text
FILE_ONLY_ITEM=FILE:Root.java
FILE_ONLY_ITEM=FILE:.agents\skills\audit-helper\scripts\Helper.java
FILE_ONLY_SKILL_RESOURCE_LEAK=true
SKILL_SYMBOL_SEARCH=2
```

**Impact :** les ressources de skill peuvent être chargées sans activation du skill et malgré la sélection explicite des sources. La provenance présentée au client est incorrecte. Aucune exécution de script n'a été constatée : le défaut concerne la sélection et la divulgation du contenu.

**Correction attendue :** politique commune d'éligibilité appliquée à Lucene, aux symboles et aux expansions du graphe, avec vérification de la catégorie canonique avant matérialisation. Tester les ressources Java de skills ainsi que les autres catégories natives exclues.

## A06 — Interrompre le parcours Git avant la matérialisation complète

**Emplacement :** `core/src/main/java/com/nexus/context/source/git/LocalGitContextSourceProvider.java:185–196` ; détection des renommages activée ligne 79.

La boucle appelle `formatter.scan(parent.getTree(), commit.getTree())` avant son premier débit de visite. Cette méthode construit et retourne la liste complète des `DiffEntry`. Les limites de 2 000 chemins par commit et le budget partagé sont donc contrôlés après ce travail. La détection des renommages peut également travailler avant le retour de `scan`.

La reproduction construit deux commits synthétiques via les objets JGit, sans créer 10 000 fichiers dans le worktree. Un wrapper observateur de la surcharge `scan(RevTree, RevTree)` délègue au vrai JGit et mesure la taille renvoyée avant le contrôle NEXUS.

```text
GIT_ENTRIES_MATERIALIZED_BEFORE_NEXUS_CHECK=10000
GIT_NEXUS_REJECTION=ContextDiscoveryLimitExceededException
GIT_CONFIGURED_VISIT_LIMIT=1
```

**Impact :** les métriques peuvent indiquer un travail borné alors que le parcours et l'allocation préalables dépendent de l'intégralité du commit. Un grand historique peut consommer mémoire et CPU au-delà du budget de découverte. Aucun épuisement mémoire n'a été provoqué pour établir ce constat.

**Correction attendue :** parcours incrémental des arbres sous compteur/deadline, avant collecte ; borner ou désactiver le travail de détection des renommages. Le test doit vérifier le travail accompli avant refus, pas seulement la présence finale d'une exception.

## A07 — Signaler ou reconstruire un index lexical disparu

**Emplacements :** `core/src/main/java/com/nexus/index/ProjectIndexingService.java:239` et `:286–287` ; `core/src/main/java/com/nexus/search/lucene/LuceneSearchIndex.java:125–134`.

Le choix du rebuild dépend du drapeau explicite et du statut SQLite. Si le projet reste READY mais que son répertoire Lucene a disparu, les fichiers dont le hash n'a pas changé sont ignorés. L'indexation normale termine avec succès, conserve READY et ne recrée pas les documents. Le moteur lexical assimile ensuite l'index absent à une liste de résultats vide.

La reproduction déplace uniquement le répertoire Lucene qu'elle vient de générer, dans une sauvegarde voisine vérifiée comme contenue dans son répertoire temporaire.

```text
MISSING_BEFORE=1
MISSING_STATE=READY,CHANGED=0
MISSING_AFTER=0
MISSING_REBUILD=1
```

**Impact :** après perte du dérivé ou restauration de SQLite seule, une indexation annoncée réussie et la readiness peuvent masquer une recherche incomplète. SQLite n'est pas perdu et le rebuild explicite fonctionne : le défaut est l'absence de détection et de diagnostic cohérent.

**Correction attendue :** vérifier l'existence et la compatibilité de la génération lexicale avant de choisir l'incrémental ; reconstruire le dérivé manquant ou refuser explicitement READY et demander un rebuild. Ne pas confondre un index légitimement vide avec un index absent.

## Vérifications exécutées

Environnement réel : Microsoft OpenJDK `21.0.12.1+1-LTS`, Windows, Maven Wrapper `3.9.16`, cible de compilation Java 21.

Le premier `mvn clean install` a échoué avant le build parce que le sandbox refusait l'accès au fichier `java.security` du JDK 24 par défaut. Le build a ensuite été exécuté avec le wrapper vérifié et Java 21, avec les permissions d'exécution nécessaires. Ce problème d'environnement n'est pas compté comme défaut NEXUS.

1. `mvnw.cmd clean install` : **BUILD SUCCESS**, cinq modules, 2 min 26 s.
2. `scripts/self-smoke.ps1` : **SELF-SMOKE SUCCESS**, treize étapes, après le build.
3. Trois programmes indépendants de reproduction : exécution terminée avec code zéro ; ils exposent les comportements défectueux, ils ne sont pas des tests de non-régression verts.

| Module | Tests déclarés | Ignorés | Échecs / erreurs | Lignes couvertes | Branches couvertes |
|---|---:|---:|---:|---:|---:|
| core | 460 | 24 | 0 / 0 | 7 798 / 9 381 | 2 678 / 4 031 |
| REST | 53 | 0 | 0 / 0 | 449 / 612 | 180 / 261 |
| MCP | 14 | 0 | 0 / 0 | 354 / 395 | 65 / 85 |
| Intégrations clientes | 24 | 0 | 0 / 0 | 121 / 128 | 79 / 89 |

Total : **551 tests déclarés, 527 exécutés, 24 ignorés, aucun échec ni erreur**. Les compteurs proviennent des XML Surefire et JaCoCo générés pendant cet audit. Les 24 tests ignorés comprennent des qualifications opt-in, des services externes et des cas POSIX/symlinks/SMB conditionnels ; ils ne constituent pas des validations réussies.

## Périmètre et limites des conclusions

L'inventaire de production comporte **199 fichiers Java et 24 275 lignes physiques**. La revue a parcouru les chaînes d'exécution de la façade, du scanner, de l'indexation, de SQLite et de ses huit migrations, de Lucene, de la recherche sémantique, du graphe, de la fédération, de la construction de contexte, de Git, des instructions/skills, des importers SCIP/MINOS, du transport JDT, des frontières CLI/REST/MCP et des intégrations clientes. Les points de sécurité, de concurrence et de budget ont été confrontés aux implémentations et aux tests ; l'inventaire ne prétend pas garantir l'absence de défaut dans chaque ligne.

La lecture des scripts et workflows a également couvert les entrées de distribution, le contrôle d'archives, les gates CI/release, les permissions déclarées et la publication d'artefacts. Les protections positives observées comprennent les requêtes SQL préparées, les transactions avec rollback/retry, les limites de frames JDT, le refus d'édition JDT, le verrouillage entre mutations, les gardes REST de token/roots/TLS, les chemins publics relatifs et les budgets de sélection. Elles n'annulent pas les contre-exemples détaillés ci-dessus.

N'ont pas été requalifiés dans cette exécution : runtime Linux strict, Docker réellement démarré, installateur/signature Windows, vraie session JDT LS, vrai producteur MINOS, vraie qualité Ollama, benchmarks volumétriques opt-in, analyse CodeQL/Sonar/OSV/Trivy et protections GitHub effectivement déployées. La présence de leurs workflows dans le dépôt ne prouve pas leur réussite actuelle. Aucune affirmation d'absence de CVE n'est faite.

Le catalogue documentaire MCP a rejeté la recherche et son fournisseur Web était indisponible ; la vérification ciblée de la sémantique HTTP Java a utilisé la documentation Oracle comme repli. Les autres constats reposent sur le code et les reproductions locales.

## Rejouer les reproductions

Depuis la racine du dépôt, après un build Java 21 :

```powershell
New-Item -ItemType Directory -Force target/audit-probes | Out-Null
$auditJar = 'target/nexus-context-engine-0.2.0-cli.jar'
$auditEvidence = 'docs/audits/2026-09-12-evidence'
java -cp $auditJar "$auditEvidence/AuditProbe.java" target/audit-probes
java -cp $auditJar "$auditEvidence/GitBudgetProbe.java" target/audit-probes
java -cp $auditJar "$auditEvidence/ScopeProbe.java" target/audit-probes
```

Les programmes créent de nouveaux répertoires uniques à chaque lancement. Le timeout Ollama utilise seulement un serveur HTTP éphémère sur loopback. Les durées et UUID peuvent varier ; les booléens et comportements décrits sont les critères utiles. Les logs complets du run initial sont conservés localement dans `target/audit-probes/logs/` ; les observations compactes et sources sont conservées avec ce document.

SHA-256 du JAR utilisé pour les reproductions : `f0f3b5eb14176f47fa3aba9d39d2b7af1aa725659a42685f50fb482f1e611f21`.
