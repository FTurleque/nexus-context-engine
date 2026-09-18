# Audit NEXUS — 14 septembre 2026

**Verdict : socle architectural et validation fonctionnelle solides ; confidentialité et disponibilité encore à corriger, qualification de performance incomplète.** Le build et le self-smoke passent. Trois défauts de code sont reproduits, deux checks de charge sont rouges sur le commit audité et la documentation courante contient des informations périmées. Aucun correctif applicatif n'a été apporté pendant cet audit.

| Priorité | Référence | Constat | Preuve |
|---|---|---|---|
| P1 — haute | NXA-20260914-01 | Les secrets associés à des clés JSON entre guillemets traversent la redaction | Reproduction unitaire et contexte public de bout en bout |
| P2 — moyenne | NXA-20260914-02 | Le timeout Ollama ne couvre pas la lecture du corps HTTP | Réponse réussie après 2 612 ms malgré un timeout de 500 ms |
| P2 — moyenne | NXA-20260914-03 | Les comptes système Windows localisés sont considérés comme non fiables | Comptes Windows français réels et prédicat de production |
| P2 — moyenne | NXA-20260914-04 | La qualification de charge du HEAD échoue à 25 projets | Deux runs GitHub exacts, logs inspectés |
| P3 — basse | NXA-20260914-05 | Le guide courant dérive des versions et capacités implémentées | Comparaison documentation/POM/migration V008 |

P1 signifie à traiter avant de compter sur la garantie concernée en exploitation ; P2 appelle une correction planifiée ; P3 concerne la fiabilité de la documentation. Aucun P0 n'a été établi. L'absence d'autres findings ne constitue pas une preuve d'absence de vulnérabilités.

**Périmètre et méthode**

- Commit : `a4dc704e4f89a2333b955efc5de54cf0b77a31bd`, merge de la PR #219, version `0.2.0`.
- Inventaire versionné : 199 fichiers Java de production, 174 fichiers Java de test, quatre modules fonctionnels, 19 workflows, 52 fichiers d'ADR numérotés, 159 composants dans le SBOM agrégé produit.
- Analyse des frontières application/adaptateurs, indexation, migrations SQLite, recherche et fédération, budgets, redaction, chemins, skills/Git, Ollama/JDT/SCIP, REST/MCP, build, livraison et gouvernance.
- Revue ciblée des chemins critiques, exécution du reactor complet et du self-smoke, sondes supplémentaires isolées, consultation des checks et du ruleset GitHub. Il ne s'agit pas d'une inspection manuelle exhaustive de chacune des lignes des 199 fichiers.
- Environnement local : Windows français, Microsoft OpenJDK `21.0.12.1`, compilation `release 21`. `mvn clean install` utilise Maven `3.9.11` installé ; le self-smoke appelle le wrapper Maven `3.9.16` du dépôt.
- Les répertoires non suivis `scripts/__pycache__/` et `scripts/tests/` existaient avant l'audit et sont conservés. Le self-smoke travaille sur l'arbre local ; les checks GitHub portent sur le SHA versionné exact.
- Les premières tentatives Maven/GitHub ont rencontré les restrictions réseau du bac à sable. Les reprises autorisées ont abouti ; ces erreurs initiales ne sont pas des défauts de NEXUS.

**NXA-20260914-01 — P1 : fuite de secrets dans les objets JSON**

Localisation : [SensitiveContentRedactor.java:36](../../../core/src/main/java/com/nexus/security/SensitiveContentRedactor.java#L36).

`SECRET_ASSIGNMENT` reconnaît une clé sensible immédiatement suivie d'espaces puis de `:` ou `=`. Le guillemet fermant de `"password"` ou `"api_key"` empêche la correspondance. Une affectation `password = "…"` est correctement masquée ; la même valeur sous une clé JSON reste intacte. Il s'agit de clés explicitement prises en charge par la politique actuelle, pas de la demande de détecter tout secret arbitraire.

Scénario reproduit : un fichier `guide.md` contient un exemple JSON avec deux valeurs synthétiques, sous `password` et `api_key`. Il est enregistré et indexé par `NexusApplication`. La requête `auditConfig` construit ensuite un contexte passé à `PublicContextPolicy.expose`, la défense commune utilisée par les adaptateurs.

```text
plain_assignment_secret_present=false
json_assignment_secret_present=true
first_changed=1 second_changed=0
items=1 estimated_tokens=43
public_context_secret_present=true
repeated_context_items_equal=true
secret_query_hits=1
```

Le contexte public contient donc le secret et le terme est aussi retrouvable dans la recherche. Le scanner n'indexe pas les fichiers `.json` nativement, mais le scénario reste pleinement atteignable dans Markdown, JavaScript, TypeScript et autres sources contenant des objets ou exemples JSON. La sonde de bout en bout utilise Markdown.

Impact : divulgation du secret au client consommateur du contexte. Le chemin d'embeddings utilise le même redactor ; l'exposition à un Ollama distant configuré est donc également possible par ce chemin, conclusion issue de la lecture du code. Aucun secret réel ni envoi à un service distant n'a été utilisé dans la reproduction.

Correction attendue : prendre en charge les clés citées, leurs délimiteurs et échappements usuels, en conservant les bornes de traitement et les séparateurs de lignes. Appliquer la correction à la frontière commune. Prévoir l'invalidation/reconstruction des index lexicaux et sémantiques déjà créés avec cette représentation, selon le modèle existant V006 et de provenance sémantique ; corriger seulement le rendu ne supprime pas les anciens termes/vecteurs.

Critères de clôture : tests de clés JSON citées, exemples Markdown/JS, absence du secret dans le contexte CLI/REST/MCP et dans le texte fourni à un provider d'embeddings factice ; absence de régression de budgets et des numéros de ligne ; stratégie explicite pour les index historiques.

**NXA-20260914-02 — P2 : délai Ollama incomplet**

Localisations : [OllamaEmbeddingProvider.java:217](../../../core/src/main/java/com/nexus/search/semantic/ollama/OllamaEmbeddingProvider.java#L217) et [lecture bloquante:274](../../../core/src/main/java/com/nexus/search/semantic/ollama/OllamaEmbeddingProvider.java#L274).

Le provider applique `HttpRequest.timeout`, puis utilise `BodyHandlers.ofInputStream()`. L'objet réponse peut être rendu avant réception complète du corps, comme l'indique la [documentation Java 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandlers.html#ofInputStream()). La boucle `readBoundedResponse` impose un plafond d'octets, mais pas une échéance à `InputStream.read`.

Reproduction sur un serveur HTTP loopback synthétique : envoi immédiat des headers et d'un premier octet, attente de 2 500 ms, puis fin du JSON. Le provider configuré avec 500 ms accepte la réponse après 2 612 ms. Le retard est borné volontairement dans la sonde afin de ne pas laisser de processus bloqué.

Impact : un Ollama qui commence à répondre puis se bloque peut immobiliser une recherche ou une indexation au-delà du timeout configuré. Le chemin sémantique appelle directement le provider ; il ne bénéficie pas automatiquement du budget des tâches externes de Code Intelligence. Le plafond en octets ne protège pas contre un corps qui n'arrive plus. La sémantique étant opt-in, ce défaut ne concerne pas le fonctionnement lexical seul.

Correction attendue : une échéance couvrant headers et corps, avec annulation de l'échange et fermeture effective du flux. Conserver le plafond d'octets. Transformer l'indisponibilité transitoire en exception permettant le repli lexical existant en recherche.

Critères de clôture : tests avec headers reçus puis corps interrompu, corps lent, réponse trop volumineuse et réponse normale ; restitution du contrôle dans une marge raisonnable autour du timeout ; absence de threads/flux abandonnés après répétition des délais dépassés.

**NXA-20260914-03 — P2 : confidentialité Windows dépendante de la langue**

Localisation : [NexusPaths.java:333](../../../core/src/main/java/com/nexus/config/NexusPaths.java#L333).

`isTrustedStoragePrincipal` compare les noms à `NT AUTHORITY\SYSTEM`, `CREATOR OWNER` et `BUILTIN\ADMINISTRATORS`. Sur la machine auditée, les SID système donnent :

```text
S-1-5-18     = AUTORITE NT\Système
S-1-5-32-544 = BUILTIN\Administrateurs
```

La sonde invoque le prédicat de production : les noms anglais sont acceptés et les deux noms français sont refusés. La comparaison insensible à la casse ne résout pas la traduction des noms.

Impact : une ACL Windows légitime peut produire des warnings répétés ; avec `NEXUS_REQUIRE_PRIVATE_STORAGE=true`, l'accès est refusé alors que les principaux concernés sont ceux que la politique entend accepter. C'est un défaut de disponibilité et de diagnostic, pas une ouverture de permissions. Les ACL des fixtures locales contiennent aussi d'autres comptes : la sonde isole le problème de langue et ne prétend pas que toutes ces ACL sont privées.

Correction attendue : identifier les principaux Windows bien connus par leur identité stable, ou résoudre de manière fiable leurs identités locales. Conserver les comparaisons exactes et le rejet des comptes seulement ressemblants ; ne pas revenir à un test de suffixe ou modifier les ACL de l'utilisateur.

Critères de clôture : tests avec les identités système anglaises et françaises, maintien des cas négatifs domaine/nom trompeur, et qualification du mode privé sur une ACL de fixture contrôlée.

**NXA-20260914-04 — P2 : HEAD non qualifié sur le gate de charge**

Les deux checks suivants étaient en échec lors de la consultation GitHub du 14 septembre. Ils ciblent tous deux le SHA audité ; les runs datent du 12 septembre.

| Run | Événement | Première assertion en échec | Plafond |
|---|---|---|---|
| [34661130804](https://github.com/FTurleque/nexus-context-engine/actions/runs/34661130804/job/103463686159) | Pull request | Contexte, 25 projets : p95 = 263,821 ms | 260 ms |
| [34661129224](https://github.com/FTurleque/nexus-context-engine/actions/runs/34661129224/job/103463681413) | Push | Recherche, 25 projets : p95 = 168,396 ms | 160 ms |

Les plafonds figurent dans [scale-benchmark.yml:247](../../../.github/workflows/scale-benchmark.yml#L247). Les écarts observés sont respectivement d'environ 1,47 % et 5,25 %. Les programmes de benchmark s'exécutent ; c'est le contrôle des budgets qui échoue. Ces checks ne peuvent pas être présentés comme verts au motif que le reactor ou la PR antérieure a réussi.

La cause n'est pas établie. Les petits écarts, deux warmups et cinq échantillons par requête justifient de distinguer variance du runner et régression avant toute modification. La première assertion qui échoue empêche également de conclure que tous les contrôles situés après elle ont été évalués.

Action attendue : analyser les artefacts des deux runs et le chemin fédéré, comparer sur un même runner avec un protocole stable, puis corriger le coût dominant ou documenter une qualification statistique plus robuste. Ne pas augmenter un plafond pour transformer mécaniquement le rouge en vert. Le ruleset impose les checks permanents, pas ce check de charge conditionnel ; cette différence décrit la politique actuelle et ne constitue pas en elle-même un contournement de protection.

Critère de clôture : gate de charge applicable vert sur le commit contenant la correction, avec mesures conservées et explication de l'écart.

**NXA-20260914-05 — P3 : documentation courante périmée**

- [Guide développeur:17](../../../docs/developer/README.md#L17) et [architecture d'implémentation:29](../../../docs/developer/architecture-implementation.md#L29) annoncent Quarkus `3.39.1` ; le POM courant impose `3.39.2`.
- Le watch item « nouveau moteur FTS/trigram pour les recherches substring » de `current-limitations.md` décrit encore comme futur un mécanisme déjà présent dans V008 et `SqliteIndexRepository`. Le guide de recovery et les ADR-0050/0051 reconnaissent bien ce mécanisme.

Impact : diagnostic de version trompeur et priorisation d'une fonctionnalité déjà livrée. La documentation s'annonce comme reflétant le HEAD courant ; il ne s'agit donc pas simplement d'un document historique à préserver.

Correction attendue : synchroniser les documents courants avec les sources de vérité, maintenir les ADR acceptés sans réécriture rétroactive et ajouter une vérification de cohérence utile pour ces informations. Aucun ADR architectural n'est nécessaire pour le simple rafraîchissement de ces textes.

**Résultats de validation locale**

`mvn clean install` : **BUILD SUCCESS**, environ 1 min 38 s, compilation Java 21. Les seuils JaCoCo configurés passent.

| Module | Tests recensés | Ignorés | Échecs / erreurs | Lignes couvertes | Branches couvertes |
|---|---:|---:|---:|---:|---:|
| Core | 460 | 24 | 0 / 0 | 83,11 % | 66,44 % |
| REST Quarkus | 53 | 0 | 0 / 0 | 73,37 % | 68,97 % |
| MCP Java | 14 | 0 | 0 / 0 | 89,62 % | 76,47 % |
| Assistant clients | 24 | 0 | 0 / 0 | 94,53 % | 88,76 % |
| Total | 551 | 24 | 0 / 0 | — | — |

Soit 527 tests non ignorés réussis. Les 24 ignorés ne sont pas comptés comme des succès ; le build ordinaire comporte notamment des qualifications opt-in. Les pourcentages proviennent des compteurs XML JaCoCo par module et ne démontrent pas à eux seuls la couverture de tous les scénarios de sécurité.

`scripts/self-smoke.ps1` : **SELF-SMOKE SUCCESS** après le build. JAR autonome, registre, indexation complète puis incrémentale idempotente, recherche explicable, Markdown, instructions, Agent Skills et Git sont validés. `ProjectIndexingService.java` est bien au premier rang du résultat affiché.

| Observation locale | Résultat |
|---|---:|
| Indexation complète du dépôt | 9 653 ms |
| Indexation incrémentale | 3 734 ms |
| Recherche | 5 612 ms |
| Contexte strict | 102 / 180 unités de tokens, 3 items, 6 139 ms |
| Contexte multi-source | 1 180 / 1 200 unités de tokens, 8 items, 6 397 ms |
| Skills | 1 découvert, 1 sélectionné, 1 ressource inventoriée |
| Git | 50 commits inspectés, 9 liés, 3 fragments sélectionnés |

Ces durées décrivent ce poste, cet arbre local et ces appels CLI ; elles ne sont ni un p95 de production ni directement comparables aux benchmarks synthétiques Linux. Elles montrent néanmoins que la latence interactive locale mérite d'être mesurée dans un runtime long-lived avant de fixer un objectif utilisateur.

**Évaluation transversale**

| Domaine | Évaluation et limites |
|---|---|
| Architecture | Composition commune par `NexusApplication`, ports de repository/recherche/providers et adaptateurs CLI/REST/MCP identifiables. Aucun import Quarkus, Jakarta ou Spring trouvé dans le code de production du core. Dépendances techniques présentes dans le module core, avec séparation par packages. |
| SQLite et cohérence | Migrations V001–V008 transactionnelles et checksumées ; valeurs SQL paramétrées ; contraintes de plage ; incrément de génération lié aux écritures. Les projections FTS V008 ont des files de changements et un flush à la génération. Les tests de migration, rollback et recherche font partie du build réussi. |
| Indexation et recovery | Verrous projet JVM/OS, passage par INDEXING, contrôle des fingerprints et état FAILED en cas d'erreur. Les dérivés Lucene sont reconstructibles. READY ne garantit pas un snapshot immuable du filesystem vivant, limitation explicitement documentée. |
| Chemins et stockage | `ProjectPathGuard`, `SafeFileIO`, politique stricte et limites physiques de lecture structurent la défense. Le fallback Windows reste best-effort face aux substitutions ABA ; ce risque accepté n'est pas reformulé comme un nouveau défaut. Le problème des noms ACL localisés reste ouvert. |
| Recherche et classement | Tri déterministe, contributions explicables, bornes de requête et de récupération, sur-récupération fédérée. La sonde répète les mêmes items de contexte ; cela prouve ce cas, pas une invariance universelle des métadonnées temporelles. La charge fédérée reste non qualifiée sur les deux runs rouges. |
| Budget de contexte | Sélection et troncature appliquent l'estimateur local ; budget partagé de matérialisation et de découverte. Les deux budgets du self-smoke sont respectés. L'heuristique 3,5 points de code/token ne garantit pas le nombre exact de tokens facturés par un fournisseur et n'inclut pas tout l'habillage du prompt client. |
| Instructions et skills | Découverte puis sélection des métadonnées avant chargement complet ; ressources inventoriées ; aucune exécution de script par `SkillLoader`. Les instructions d'un repository restent du contenu à traiter selon la politique de confiance de l'agent consommateur. |
| Git | Historique, diffs et cache de contexte bornés ; usage local en lecture. Validation du chemin Git par self-smoke et checks Git cache verts sur le SHA audité. |
| Code Intelligence | SCIP, JDT et MINOS disposent de bornes et de validations de métadonnées. JDT exige une racine approuvée et utilise un environnement filtré ; framing et concurrence sont bornés. Aucun lancement réel de JDT n'a été effectué durant cet audit. |
| REST | Loopback par défaut, Bearer sauf opt-out explicite, allowlist de racines, validation TLS hors loopback et management séparé. Les tests d'authentification, d'autorisation et d'exposition passent. Pas de test d'intrusion sur un déploiement distant réel. |
| MCP et intégrations | STDIO séparé des logs applicatifs, fermeture des ressources et contrats négatifs testés. Les 14 tests MCP et 24 tests des générateurs clients passent. Pas de qualification interactive de chaque IDE/client tiers. |
| Sémantique | Opt-in, provenance d'index, redaction avant embeddings et politiques d'endpoint. Le check Ollama réel GitHub est vert pour ce SHA ; le défaut de timeout découvert illustre un scénario distinct non couvert par ce succès. |
| Observabilité | Métriques REST d'opérations/durées, état de readiness et diagnostics d'exclusion disponibles. Les faux positifs ACL produisent un bruit visible ; corriger leur cause améliorera aussi la lisibilité des logs. |
| Maintenabilité | Les responsabilités les plus concentrées sont JDT LS (1 241 lignes), SQLite repository (1 126), SCIP (925), indexation (652), MCP tools (596) et builder contexte (593). Ce sont des zones de revue prioritaire ; leur taille seule n'est pas un bug et ne justifie pas une réécriture générale. |
| Livraison et dépendances | Versions/BOM centralisés, wrapper et outils à ancres d'intégrité, génération SBOM/notices, workflow Docker construisant puis publiant l'image qualifiée. Les checks Docker et Windows sont verts ; aucune publication ou installation n'a été déclenchée par l'audit. |

**Gouvernance et preuves externes**

La réponse GitHub conservée contient 32 check-runs : 29 réussites, deux échecs et un skip. Plusieurs noms correspondent à des exécutions distinctes du même workflow ; ce ne sont pas 32 familles de contrôles indépendantes. Parmi les réussites exactes : reactor Linux/Windows, CodeQL, OSV SBOM, SonarCloud, Docker, installateur Windows, scanner corpus, filesystem Linux/Windows, SMB loopback, caches Git/Lucene et récupération sémantique Ollama réelle.

Le [ruleset 20503835](https://github.com/FTurleque/nexus-context-engine/rules/20503835) est actif. Il cible la branche par défaut `main` et `develop`, impose les PR et la résolution des discussions, interdit suppression et non-fast-forward, et exige les sept checks permanents documentés. Les choix de maintenance solo — zéro approbation humaine distincte et absence de synchronisation stricte obligatoire — sont respectés et ne sont pas des findings.

Les succès OSV/CodeQL observés sont des preuves de ces scanners au moment de leurs runs. Aucun scan exhaustif neuf de toutes les bases de vulnérabilités, audit juridique des licences ou contrôle administratif complet de l'organisation GitHub n'a été réalisé. La recherche web par `mcp-search-net` était indisponible ; sa récupération d'URL a permis de vérifier la documentation Java, et l'API GitHub a fourni les états exacts.

**Plan de correction proposé**

1. Corriger NXA-01 et traiter les index historiques. Valider absence de fuite et non-régression de sélection/budgets.
2. Corriger NXA-02 et NXA-03 avec leurs scénarios reproductibles. Garder les refus de sécurité lorsque l'identité ou le transport n'est pas démontré.
3. Résoudre NXA-04 par mesure du chemin fédéré et qualification sur le même SHA, sans assouplissement opportuniste des seuils.
4. Synchroniser la documentation courante NXA-05 et ses contrôles de cohérence.
5. Pour chaque changement, relancer le reactor et le self-smoke, puis les gates conditionnels concernés. Ne clore aucun finding sur la seule existence d'un patch.

**Reproduction et conservation**

Les sondes utilisent exclusivement des valeurs synthétiques. Elles sont fournies avec le rapport : [AuditProbe.java](../../../docs/audits/2026-09-14/AuditProbe.java) et [ContextProbe.java](../../../docs/audits/2026-09-14/ContextProbe.java). Elles exposent l'état actuel et affichent les défauts ; ce ne sont pas des tests verts attestant que les défauts sont corrigés.

Depuis la racine du dépôt, avec Java 21 actif et après construction du JAR :

```powershell
mvn clean install
.\scripts\self-smoke.ps1
New-Item -ItemType Directory -Force target/audit-20260914 | Out-Null
java --enable-native-access=ALL-UNNAMED -cp target/nexus-context-engine-0.2.0-cli.jar docs/audits/2026-09-14/AuditProbe.java
java --enable-native-access=ALL-UNNAMED -cp target/nexus-context-engine-0.2.0-cli.jar docs/audits/2026-09-14/ContextProbe.java
```

`ContextProbe` crée un sous-répertoire de fixture dans `target/audit-20260914`. `AuditProbe` ouvre temporairement un serveur HTTP loopback et le ferme en fin de sonde. Les identifiants de projets et les durées peuvent varier.

Les états externes consultés sont conservés dans [github-checks.json](../../../docs/audits/2026-09-14/github-checks.json) et [github-ruleset.json](../../../docs/audits/2026-09-14/github-ruleset.json). Les logs détaillés locaux sont dans `target/audit-20260914/` : build, self-smoke, sondes et deux runs de charge. Ce dossier est ignoré par Git et effaçable par un futur clean ; les constats essentiels et sorties utiles sont donc reproduits dans le présent rapport.

Seuls les livrables de cet audit ont été ajoutés au dépôt. Le code applicatif, les tests existants, les dépendances, les protections GitHub et les artefacts publiés n'ont pas été modifiés.
