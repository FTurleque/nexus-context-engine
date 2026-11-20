# Ordre de correction — audit du 16 novembre 2026

Le plan en trois temps ([plan-action.md](plan-action.md)) classe par urgence. Ce document classe par ordre d'exécution, et c'est lui qui s'applique quand les deux divergent : une correction dont le prérequis n'est pas livré ne peut pas être vérifiée.

L'ordre a été vérifié par script (`check_sprints.py`). Chaque constat actionnable est dans un seul sprint, aucun constat Info n'est affecté, aucun prérequis n'est dans un sprint postérieur, et le graphe n'a pas de cycle. Un test négatif, qui déplace AUD-PRF-04 au sprint 4, fait bien échouer le contrôle.

## Vue d'ensemble

| Sprint | Titre | Constats | Composition | Charge |
|---|---|---:|---|---|
| S1 | Remettre la CI et les mesures d'aplomb | 5 | 1 moyenne · 4 faible | 1,25 à 5 j |
| S2 | Couverture à jour et erreurs serveur tracées | 6 | 2 moyenne · 4 faible | 2,25 à 8 j |
| S3 | Redaction v12 : formes d'affectation du code | 5 | 1 élevée · 1 moyenne · 3 faible | 2 à 7 j |
| S4 | Robustesse des chemins de requête et d'indexation | 4 | 2 moyenne · 2 faible | 2,5 à 8 j |
| S5 | Frontières du cœur et API d'index unique | 8 | 1 moyenne · 7 faible | 3,5 à 12 j |
| S6 | Contrats des adaptateurs et du contexte servi | 5 | 5 faible | 2,75 à 9 j |
| S7 | Hygiène du code, des scripts et de l'installateur | 5 | 5 faible | 3,5 à 11 j |

Charge totale : 17,75 à 60 jours, en sommant les efforts avec S de 0,25 à 1 j, M de 1 à 3 j et L de 3 à 8 j. C'est une enveloppe : elle ne compte ni la revue, ni les allers-retours de CI, ni l'écriture de l'ADR du sprint 5 et des décisions du sprint 6.

## Chemin critique

Ce sont les constats qui débloquent le plus de travail en aval :

- **AUD-ARC-12** (S) : l'ADR qui complète ARCH-0076 conditionne tout le sprint 5 (ARC-02, ARC-04, puis la matrice d'ARC-01) et, par ricochet, SEC-06 et QUA-05.
- **AUD-PRF-04** (S) : brancher et seuiller les bancs (dont le débit de redaction) rend mesurables les quatre corrections de redaction du sprint 3.
- **AUD-SEC-01** (S) : corriger le saut de curseur, la seule fuite Élevée, ouvre la v12 sur laquelle s'empilent SEC-02 et SEC-05, et fixe le numéro de migration que PRF-01 suit.
- **AUD-TST-13** (S) : tester le découpage en fenêtres avant de remesurer la couverture évite une double remesure (TST-14, puis TST-04).

## S1 — Remettre la CI et les mesures d'aplomb

**Pourquoi ici.** Les corrections suivantes s'appuient sur des gates fiables et des bancs qui mesurent quelque chose. Ce sprint touche le pom, les workflows et le wrapper, presque pas le code applicatif. La montée Quarkus y entre parce que c'est un correctif de sécurité d'effort S.

**Critère de sortie.** Quarkus est en 3.40.1.2 dans pom.xml et le gate OSV du SBOM agrégé passe. ci.yml (linux-reactor) et osv-scanner.yml (build-sbom) épinglent java-version '21.0.12+101.0.LTS' et ubuntu-24.04. docker-distribution.yml (PR et push) et windows-installer.yml filtrent mvnw, mvnw.cmd, .mvn/**, config/tool-integrity.properties et les deux vérificateurs. workflow_hygiene.py échoue sur un checkout sans persist-credentials: false, et sa fixture négative est rejetée. RedactionThroughputBenchmarkTest et IndexingHeapPeakBenchmarkTest sont nommés dans un -Dtest= de workflow, avec un seuil qui fait échouer le job.

**Ordre interne.** aucune contrainte : les constats peuvent partir dans n'importe quel ordre, en évitant deux PR parallèles sur les mêmes fichiers.

**Prérequis des sprints antérieurs.** aucun.

| Ordre | ID | Constat | Sévérité | Effort |
|---:|---|---|---|---|
| 1 | AUD-DEP-01 | Quarkus épinglé en 3.40.1.1 alors que la release d'urgence sécurité 3.40.1.2 du 09/11/2026 corrige trois CVE | Moyenne | S |
| 2 | AUD-PRF-04 | Bancs de performance sans seuil (cycle de vie Lucene, cache Git) ou sans lanceur (débit de redaction, pic de tas à l'indexation) | Faible | S |
| 3 | AUD-DEP-02 | Le gate Docker ne se déclenche pas sur une modification de mvnw, .mvn, config/tool-integrity.properties ou des vérificateurs que le Dockerfile exécute | Faible | S |
| 4 | AUD-DEP-03 | 19 checkouts sur 25 conservent le jeton GITHUB_TOKEN dans .git/config, dont celui du job publish (contents et packages en écriture) | Faible | S |
| 5 | AUD-DEP-04 | Les gates requis Linux reactor et SBOM OSV tournent sur un JDK '21' flottant et ubuntu-latest, quand Windows, CodeQL et les images épinglent 21.0.12 | Faible | S |

## S2 — Couverture à jour et erreurs serveur tracées

**Pourquoi ici.** Les sprints 3 à 6 modifient la redaction, la recherche et les adaptateurs. Avant ça, il faut une table de couverture juste, des planchers sur les paquets critiques, et des erreurs 500 qui laissent une trace côté serveur pour voir les régressions.

**Critère de sortie.** La table de adapter-coverage-gates.md porte une mesure Linux et Windows au même commit, dénominateurs à jour. core/pom.xml porte des règles PACKAGE pour com.nexus.persistence.sqlite, com.nexus.search.lucene, com.nexus.context, com.nexus.cli et com.nexus.application, et coverage_floors.py passe sur les deux plateformes. queryRanges, queryTerms et mergeRanges ont une couverture de lignes non nulle dans jacoco.xml. Un job planifié exécute les tests REST et MCP en ordre aléatoire. IOExceptionMapper, la branche 500 d'IllegalStateExceptionMapper et le catch générique de NexusMcpTools journalisent l'exception avec un identifiant renvoyé au client. readiness() journalise la cause, et un test par surface le vérifie. Le niveau de log de la CLI et du serveur MCP se règle par une variable documentée.

**Ordre interne.** AUD-TST-13 avant AUD-TST-14 ; AUD-TST-14 avant AUD-TST-04 ; AUD-TST-13 avant AUD-TST-04

**Prérequis des sprints antérieurs.** aucun.

| Ordre | ID | Constat | Sévérité | Effort |
|---:|---|---|---|---|
| 1 | AUD-QUA-01 | Les erreurs internes REST et MCP sont converties en réponse générique sans aucune trace côté serveur | Moyenne | S |
| 2 | AUD-TST-13 | Le découpage d'un gros fichier en fenêtres de contexte (queryRanges, queryTerms, mergeRanges) n'est exécuté par aucun test : 54 lignes de ContextFragmentFactory à 0 % | Moyenne | S |
| 3 | AUD-TST-14 | Table de référence de couverture périmée : com.nexus.security est passé de 700 à 792 lignes, et la mesure Windows (92,30 %) est 5,3 points au-dessus du plancher de 87 % sans que la table l'enregistre | Faible | S |
| 4 | AUD-TST-04 | Paquets critiques cli, application, persistence.sqlite, search.lucene et context gardés par le seul BUNDLE du core (marge mesurée : 356 lignes et 186 branches) | Faible | S |
| 5 | AUD-TST-05 | Deux propriétés de la suite reposent sur la seule lecture : la preuve qu'une règle CLASS échoue est manuelle, et aucun job n'exécute les tests REST/MCP dans un ordre inversé ou aléatoire | Faible | S |
| 6 | AUD-DEP-05 | CLI et MCP sans backend ni configuration de logs : SLF4J tombe en NOP, aucun log structuré, aucun réglage de niveau documenté | Faible | M |

## S3 — Redaction v12 : formes d'affectation du code

**Pourquoi ici.** Toutes ces corrections partagent un seul passage de politique (POLICY_VERSION, profil content-vN, invalidation des dérivés) : les grouper évite plusieurs reconstructions d'index. Le banc de débit branché au sprint 1 mesure l'effet des nouvelles expressions.

**Critère de sortie.** POLICY_VERSION vaut secret-redaction-v12 et le profil sémantique passe à content-v11. SecretRedactionProbeTest contient un cas par forme listée dans SEC-01 à SEC-05 (le test affirme leur nombre), et docs/developer/secret-redaction.md cite ces formes. Une seule migration Vnnn invalide les dérivés, avec son test d'upgrade. Le banc de débit de redaction passe son seuil dans le workflow branché au sprint 1.

**Ordre interne.** AUD-SEC-01 avant AUD-SEC-02 ; AUD-SEC-02 avant AUD-SEC-05

**Prérequis des sprints antérieurs.** AUD-SEC-02 ← AUD-PRF-04 (S1) ; AUD-SEC-03 ← AUD-PRF-04 (S1) ; AUD-SEC-04 ← AUD-PRF-04 (S1) ; AUD-SEC-05 ← AUD-PRF-04 (S1)

| Ordre | ID | Constat | Sévérité | Effort |
|---:|---|---|---|---|
| 1 | AUD-SEC-01 | Une clé sensible placée juste après la quote ouvrante d'une chaîne ("password=…", "POSTGRES_PASSWORD=…", "Authorization: …") n'est jamais masquée | Élevée | S |
| 2 | AUD-SEC-02 | Les formes d'affectation courantes des langages indexés (camelCase composé, indice de dictionnaire, appel put/setProperty, SQL) laissent la valeur en clair | Moyenne | M |
| 3 | AUD-SEC-03 | Une chaîne Python triple-quotée ou une concaténation ne masque que le premier littéral : la suite de la valeur reste en clair | Faible | S |
| 4 | AUD-SEC-04 | Un mot de passe d'URL contenant « @ » ou de moins de 3 caractères n'est pas masqué | Faible | S |
| 5 | AUD-SEC-05 | Plusieurs familles de secrets structurés courantes ne sont ni masquées ni exclues (AccountKey Azure, SharedAccessKey, hf_, SG., webhook Slack, ya29., clé PuTTY) | Faible | S |

## S4 — Robustesse des chemins de requête et d'indexation

**Pourquoi ici.** Des corrections locales, indépendantes des frontières du cœur, dont l'effet se mesure avec les bancs du sprint 1.

**Critère de sortie.** Le banc d'échelle a un cas de requête de moins de 3 caractères avec un budget p95 dans verify-scale-budgets.py. Un test avec un faux provider qui renvoie 503 puis 200 montre que l'indexation aboutit en READY, et un test avec une erreur de contrat montre qu'elle reste FAILED. Le délai Ollama par défaut est réaligné sur la mesure documentée. Dans le repli Windows de SafeFileIO, un test injecte une jonction entre le contrôle et l'ouverture (point d'injection de test) et vérifie le refus. L'extrait sémantique ne normalise plus qu'un préfixe borné.

**Ordre interne.** AUD-PRF-05 avant AUD-PRF-02

**Prérequis des sprints antérieurs.** AUD-PRF-01 ← AUD-SEC-01 (S3)

| Ordre | ID | Constat | Sévérité | Effort |
|---:|---|---|---|---|
| 1 | AUD-PRF-01 | Un terme de recherche de moins de 3 caractères parcourt tous les symboles du projet puis les trie : environ 0,7 s par terme à 1 M de symboles (mesure SQLite hors JDBC), 2,3 s pour 3 termes | Moyenne | S |
| 2 | AUD-PRF-05 | L'extrait sémantique normalise les blancs de tout le fichier (jusqu'à 8 Mio) pour n'en garder que 320 caractères | Faible | S |
| 3 | AUD-PRF-02 | Une panne transitoire d'Ollama (429, 5xx, délai) fait échouer toute l'indexation et perd tous les embeddings déjà calculés, sans aucune nouvelle tentative | Moyenne | M |
| 4 | AUD-SEC-07 | Sous Windows, le repli non atomique de SafeFileIO ne détecte pas une substitution concurrente par jonction : fileKey nul et toRealPath(NOFOLLOW_LINKS) ne suivent pas la jonction | Faible | M |

## S5 — Frontières du cœur et API d'index unique

**Pourquoi ici.** On décide d'abord de la place de ProjectIgnoreMatcher et des verrous, puis on fige la matrice complète des sens. L'API d'index par lot disparaît avant qu'on optimise la redaction dans les sessions, pour ne pas corriger deux chemins.

**Critère de sortie.** Un nouvel ADR, qui complète ARCH-0076, porte la matrice complète des sens. CoreArchitectureTest la vérifie, et une fixture négative échoue par règle. SearchIndex et SemanticSearchIndex n'exposent plus que openSession, et Direct*Session est supprimée. processLocalOnly, RepositoryPath.fromPath et ProjectPathGuard.containsSymbolicLink ne sont plus publics dans le main. Les sessions Lucene et sémantique ne repassent plus un contenu déjà masqué dans le redacteur, et le gain apparaît dans portfolio.totalFullIndexMs. NexusApplication n'a plus de champ SemanticSearchIndex nullable.

**Ordre interne.** AUD-ARC-12 avant AUD-ARC-02 ; AUD-ARC-12 avant AUD-ARC-04 ; AUD-ARC-12 avant AUD-ARC-01 ; AUD-ARC-02 avant AUD-ARC-01 ; AUD-ARC-04 avant AUD-ARC-01 ; AUD-QUA-02 avant AUD-PRF-03

**Prérequis des sprints antérieurs.** aucun.

| Ordre | ID | Constat | Sévérité | Effort |
|---:|---|---|---|---|
| 1 | AUD-QUA-02 | Deux chemins d'écriture d'index coexistent : l'API par lot (rebuild/applyChanges) n'a plus d'appelant en production mais reste déclarée dans 2 interfaces et 5 classes | Moyenne | M |
| 2 | AUD-ARC-12 | La matrice complète des sens et le placement de ProjectIgnoreMatcher et des verrous ne sont décidés dans aucun ADR : ARCH-0076, accepté, ne peut pas être réécrit | Faible | S |
| 3 | AUD-ARC-02 | Les sources de contexte (instruction, skill) dépendent du package de scan d'indexation pour ProjectIgnoreMatcher | Faible | S |
| 4 | AUD-ARC-04 | Le package com.nexus.index, déclaré « modèle et ports », contient aussi de l'infrastructure concurrente et fichier (verrous, exécution de tâches) | Faible | S |
| 5 | AUD-ARC-01 | Le tableau des sens autorisés d'ARCH-0076 n'est automatisé que sur 4 règles, omet 12 packages et ne correspond plus au code sur 3 arêtes | Faible | M |
| 6 | AUD-ARC-05 | La recherche sémantique désactivée est représentée par null et un booléen dans la façade, contre la règle d'ARCH-0076 | Faible | S |
| 7 | AUD-QUA-08 | Méthodes publiques de production appelées par les seuls tests (code de test dans le jar) | Faible | S |
| 8 | AUD-PRF-03 | À l'indexation, chaque fichier passe trois fois en entier par le redacteur (éditeur de documents, Lucene, sémantique), alors que les deux dernières passes portent sur un texte déjà masqué | Faible | S |

## S6 — Contrats des adaptateurs et du contexte servi

**Pourquoi ici.** Ce sprint touche les mêmes mappers et le même schéma public que le sprint 2. Il passe après lui, et la provenance des instructions s'appuie sur le schéma typé.

**Critère de sortie.** REST et MCP mappent ProjectNotReadyException et ProjectIndexBusyException par type, et IllegalStateExceptionMapper ne contient plus de préfixe de message. REST et MCP appellent la même fonction du cœur pour requestedSources, qui traite une entrée vide de façon identique. Les métadonnées consommées par self-smoke.ps1 sont des records couverts par PublicOutputSchema, avec un champ de confiance pour les items INSTRUCTION et SKILL. SECURITY.md ou current-limitations.md décrit le modèle de menace d'injection. configure() et acquireShared échouent sur une valeur différente de celle déjà installée.

**Ordre interne.** AUD-QUA-04 avant AUD-SEC-06

**Prérequis des sprints antérieurs.** AUD-QUA-05 ← AUD-ARC-04 (S5) ; AUD-QUA-06 ← AUD-QUA-01 (S2) ; AUD-SEC-06 ← AUD-ARC-02 (S5)

| Ordre | ID | Constat | Sévérité | Effort |
|---:|---|---|---|---|
| 1 | AUD-ARC-03 | Normalisation des entrées recodée par chaque surface : requestedSources traité différemment en REST et en MCP, et limite littérale de 20 dans find_usages contre la politique commune de 10 | Faible | S |
| 2 | AUD-QUA-04 | Les métadonnées de contexte (68 clés) sont construites en Map<String, Object> à clés littérales, hors du contrat typé PublicOutputSchema | Faible | M |
| 3 | AUD-QUA-05 | Trois états globaux de processus installés par la composition root, aux sémantiques différentes (dernier écrivain ou premier écrivain, sans détection de conflit) | Faible | S |
| 4 | AUD-QUA-06 | Le mapper REST classe les IllegalStateException en 409 en analysant le texte français du message produit par le cœur | Faible | S |
| 5 | AUD-SEC-06 | Les instructions et skills des dépôts indexés sont servis aux agents comme items INSTRUCTION/SKILL sans marquage de confiance ni modèle de menace d'injection écrit | Faible | M |

## S7 — Hygiène du code, des scripts et de l'installateur

**Pourquoi ici.** De la dette sans urgence ni prérequis, qu'on traite une fois les chantiers de fond livrés pour ne pas réécrire deux fois les mêmes fichiers.

**Critère de sortie.** LocalGitContextSourceProvider.discover, SemanticHybridContextRanker.rank et ScipSnapshotMapper.importDocument font moins de 80 lignes, et une règle outillée (test d'architecture ou script de contrats) en garde la mesure. Un seul safeMessage interne et un seul utilitaire de fermeture restent dans le main. build-windows-distribution.ps1 et build-windows-installer.ps1 échouent si la distribution précédente subsiste. Les heredocs Python de test-operational-doc-contracts.sh sont des modules scripts/release/*.py testés. Les empreintes de l'installateur viennent de config/tool-integrity.properties seul.

**Ordre interne.** aucune contrainte : les constats peuvent partir dans n'importe quel ordre, en évitant deux PR parallèles sur les mêmes fichiers.

**Prérequis des sprints antérieurs.** AUD-QUA-03 ← AUD-QUA-04 (S6) ; AUD-QUA-07 ← AUD-QUA-02 (S5)

| Ordre | ID | Constat | Sévérité | Effort |
|---:|---|---|---|---|
| 1 | AUD-QUA-03 | 9 méthodes de plus de 80 lignes subsistent (mesure par accolades), dont LocalGitContextSourceProvider.discover (108) et SemanticHybridContextRanker.rank (95), déjà signalées | Faible | M |
| 2 | AUD-QUA-07 | Petits utilitaires recopiés avec des sémantiques divergentes : 3 safeMessage, 4 closeQuietly, 6 catch OversizedSecretException identiques, 42 noms pleinement qualifiés dans le code | Faible | S |
| 3 | AUD-QUA-09 | Scripts : duplication résiduelle (Measure-Quality, scripts de mesure quasi identiques) et 1 150 lignes de Python dans des heredocs Bash | Faible | M |
| 4 | AUD-QUA-10 | build-windows-distribution.ps1 ignore l'échec du nettoyage de la distribution précédente avant de la reconstruire | Faible | S |
| 5 | AUD-DEP-07 | L'épinglage URL et SHA-256 de Docker Desktop et d'Ollama dans l'installateur passe par une réécriture textuelle du template, avec empreintes recopiées dans 3 fichiers | Faible | M |

## Constats Info, hors sprint

Ces constats documentent ce qui tient. Ils ne demandent aucune action. Certains expliquent pourquoi un risque est déjà borné : il ne faut pas les « corriger » par erreur.

- **AUD-ARC-06** — Graphe des packages du cœur acyclique, vérifié indépendamment : 32 packages, 165 arêtes, aucune composante fortement connexe *Borne un risque : le graphe acyclique est verrouillé par CoreArchitectureTest ; ne pas assouplir sa baseline vide.*
- **AUD-ARC-07** — Adaptateurs REST et MCP minces : ils n'importent que la façade, la projection publique et des types valeur ou de politique
- **AUD-ARC-08** — Pas de god object : façade à 12 dépendances et 15 opérations publiques, composition root de 347 lignes
- **AUD-ARC-09** — Acyclicité garantie au niveau sous-package seulement : regroupés par espace de premier niveau, index, search et ranking forment un cycle
- **AUD-ARC-10** — Persistance correctement inversée : ports dans index et project, persistence.sqlite importé par la seule composition root
- **AUD-QUA-11** — Gestion d'erreurs larges toujours justifiée : 13 catch Exception/RuntimeException en main, tous à une frontière, agrégés ou commentés
- **AUD-QUA-12** — Typage, ressources et concurrence maîtrisés
- **AUD-QUA-13** — Taille des fichiers et duplication globale faibles ; adaptateurs REST/MCP/CLI sans duplication grâce aux politiques partagées du cœur
- **AUD-QUA-14** — Scripts shell et PowerShell fail-fast
- **AUD-SEC-08** — SQL entièrement paramétré et requêtes Lucene échappées : aucune injection depuis les entrées client
- **AUD-SEC-09** — REST : jeton exigé par défaut même en loopback, comparaison en temps constant, contrôle Host/Origin littéral et erreurs sans détail *Borne un risque : rend le jeton REST obligatoire, ce qui borne l'exposition des fuites de redaction en REST.*
- **AUD-SEC-10** — La redaction s'applique à l'indexation et à la frontière publique, et son coût reste linéaire sur des entrées forgées
- **AUD-SEC-11** — Packaging : image non-root, Compose durci et lié à 127.0.0.1, jeton d'installeur issu du CSPRNG et stocké sous le profil utilisateur
- **AUD-SEC-12** — Aucun secret réel commité : seules des valeurs factices de tests et un jeton de smoke CI éphémère
- **AUD-SEC-13** — Entrées externes bornées : endpoint Ollama validé, YAML sans alias, trame MCP limitée à 1 Mio *Borne un risque : empêche un client de piloter l'URL Ollama : pas de SSRF.*
- **AUD-SEC-14** — Le contenu indexé est limité à sept extensions de code et documentation, et les fichiers de secrets usuels sont exclus *Borne un risque : limite la surface de AUD-SEC-01 à 05 aux sept extensions indexées.*
- **AUD-TST-01** — Le wrapper Maven code en dur repo.maven.apache.org sans variable de miroir : la suite n'a pas pu être lancée sous Linux derrière un proxy qui refuse Maven Central *Borne un risque : artefact de l'environnement d'audit pour l'essentiel ; il reste à documenter le contournement par archive déposée.*
- **AUD-TST-03** — JDT Language Server réel jamais exécuté en CI : JdtStdioSession gardé par un plancher de 40 % des lignes et 16 % des branches *Borne un risque : dette arbitrée par écrit : le plancher de 16 % sert de garde de non-régression, ce n'est pas un objectif.*
- **AUD-TST-06** — Gates de couverture complets et vérifiés : BUNDLE + PACKAGE + CLASS sur 4 modules, table de référence contrôlée par script, fixtures négatives rejetées
- **AUD-TST-07** — Tests ignorés : 26 sous Windows (17 opt-in + 9 POSIX/système de fichiers), environ 19 attendus sous Linux (17 opt-in + 2 Windows-only), tous lancés ailleurs sauf les 2 bancs d'AUD-PRF-04
- **AUD-TST-08** — Fragilité maîtrisée : pas de port fixe, pas de dépendance réseau, Thread.sleep limité à des attentes bornées par une échéance
- **AUD-TST-09** — Qualité des assertions : sur 962 méthodes de test, aucune sans assertion dans la suite par défaut et aucune réduite à assertNotNull
- **AUD-TST-10** — 188 classes main sur 263 sans test homologue, mais les classes critiques concernées sont bien couvertes indirectement (SecretAssignmentScanner 98,7 %, IndexDocumentPublisher 87,0 % des lignes)
- **AUD-TST-11** — Tests des adaptateurs : 9 classes @QuarkusTest REST et un vrai serveur MCP STDIO instrumenté, un test par outil MCP, un endpoint REST testé hors de la classe de contrat
- **AUD-TST-12** — Scripts et hooks testés en CI sans Pester : Test-Hooks.ps1 (41 cas), Test-NexusScript.ps1 sous pwsh et PowerShell 5.1, test_scale_budgets.py (26 tests verts)
- **AUD-PRF-06** — Schéma SQLite : chaque filtre, jointure et tri fréquent est servi par un index ; LIKE '%x%' n'existe que dans le repli des termes courts
- **AUD-PRF-07** — Redaction de complexité linéaire sur 16 entrées forgées de 8 Mo (3,6 s au pire), motifs précompilés *Borne un risque : garantit que la redaction reste linéaire : un nouveau motif du sprint 3 doit préserver cette propriété.*
- **AUD-PRF-08** — Chemins de requête bornés : résultats Lucene, voisinage graphe, matérialisation du contexte, fédération et appel d'embedding de requête
- **AUD-PRF-09** — JDT LS : un processus par passage, borné par le délai global des providers (180 s) et 120 s par requête, mais tous les fichiers Java restent ouverts jusqu'à la fin
- **AUD-DEP-06** — Tags de version : un seul ruleset « Protect tags » dont le contournement admin `always` couvre aussi update et deletion *Borne un risque : réglage GitHub suivi par une tâche mainteneur (AUD-DEP-37 d'octobre), pas une modification du dépôt.*
- **AUD-DEP-08** — Jackson 2.23.0 importé avant le BOM Quarkus remplace la ligne LTS 2.21.x de la plate-forme ; elle est postérieure aux correctifs de CVE-2026-110879
- **AUD-DEP-09** — Quarkus 4.0 est annoncé pour le 25/11/2026 ; NEXUS est sur la LTS 3.40
- **AUD-DEP-10** — Chaîne d'approvisionnement CI solide : actions épinglées par SHA vérifié, permissions minimales, secrets bornés, release fail-closed et attestée
- **AUD-DEP-11** — Conteneur durci : bases épinglées par digest, UID 10001, HEALTHCHECK, racine en lecture seule, aucune capacité, bind loopback et jeton obligatoire
- **AUD-DEP-12** — Build gouverné : outputTimestamp, enforcer strict, licences en liste blanche bloquante, SBOM CycloneDX, notices préservées dans les JAR ombrés
