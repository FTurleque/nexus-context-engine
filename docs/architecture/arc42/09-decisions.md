# Section 9 — Décisions d'architecture

L'historique complet des ADR se trouve dans [`docs/architecture/adr/`](../adr/README.md).
Cette section présente un index synthétique avec statut, date et relations de remplacement.

> **Règle** : aucun ADR accepté ne peut être supprimé ni réécrit rétroactivement.
> Toute révision substantielle crée un nouvel ADR qui le remplace (`Remplace` / `Remplacé par`) ;
> l'ADR remplacé part dans `archive/` (GEN-0057).

## Index des ADR

| ADR | Titre | Statut | Date |
|---|---|---|---|
| [GEN-0002](../adr/GEN-0002-compiler-le-coeur-en-java-21.md) | Compiler le cœur avec Java 21 comme niveau minimal | Accepted | 2026-07-19 |
| [ARCH-0005](../adr/ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.md) | Adopter un fonctionnement local-first et des intégrations externes opt-in | Accepted | 2026-07-19 |
| [BE-0008](../adr/BE-0008-utiliser-javaparser-comme-analyseur-java-embarque.md) | Utiliser JavaParser comme analyseur Java embarqué du MVP | Accepted | 2026-07-19 |
| [DATA-0018](../adr/DATA-0018-utiliser-des-uuid-pour-les-identites-metier-et-des-ids-locaux-pour-la-persistance.md) | Utiliser des UUID pour les identités métier et des identifiants locaux pour la persistance | Accepted | 2026-07-19 |
| [DATA-0020](../adr/DATA-0020-versionner-le-schema-sqlite-avec-des-migrations-sql-embarquees.md) | Versionner le schéma SQLite avec des migrations SQL embarquées | Accepted | 2026-07-19 |
| [DATA-0023](../adr/DATA-0023-utiliser-sha-256-pour-detecter-les-changements-de-fichiers.md) | Utiliser SHA-256 pour détecter les changements de fichiers | Accepted | 2026-07-19 |
| [GEN-0031](../adr/GEN-0031-packager-la-cli-dans-un-jar-autonome.md) | Packager la CLI dans un JAR autonome | Accepted | 2026-07-19 |
| [SEC-0033](../adr/SEC-0033-separer-instructions-contextuelles-et-configuration-operationnelle-des-agents.md) | Séparer les instructions contextuelles de la configuration opérationnelle des agents | Accepted | 2026-07-19 |
| [BE-0038](../adr/BE-0038-indexer-les-langages-additionnels-lexicalement-et-enrichir-la-structure-via-providers.md) | Indexer les langages additionnels lexicalement et enrichir la structure via des providers | Accepted | 2026-07-20 |
| [DATA-0049](../adr/DATA-0049-identite-repository-par-composants-et-diagnostics-publics.md) | Identité repository par composants et diagnostics publics | Accepted | 2026-09-09 |
| [GEN-0057](../adr/GEN-0057-tenir-le-registre-d-adr-au-format-archgate.md) | Tenir le registre d'ADR au format Archgate, à l'emplacement arc42 | Accepted | 2026-10-29 |
| [SEC-0068](../adr/SEC-0068-publier-les-releases-depuis-un-environnement-protege-avec-des-assets-attestes.md) | Publier les releases depuis un environnement protégé, avec des assets attestés et des tags de pré-version | Proposed | 2026-11-07 |
| [BE-0070](../adr/BE-0070-integrer-le-contexte-git-local-borne-mis-en-cache-et-confine.md) | Intégrer le contexte Git local, borné, mis en cache en mémoire et confiné au dépôt du projet | Accepted | 2026-11-08 |
| [BE-0071](../adr/BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md) | Rechercher les candidats dans Lucene, SQLite et le graphe d'imports, puis les classer par un score composé explicable | Accepted | 2026-11-08 |
| [BE-0072](../adr/BE-0072-construire-le-contextbundle-sous-budget-de-tokens.md) | Construire le ContextBundle sous budget de tokens : estimateur local, fragments symboliques, sélection gloutonne déterministe | Accepted | 2026-11-08 |
| [DATA-0073](../adr/DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md) | Tenir SQLite pour source de vérité locale et Lucene pour index de recherche dérivé et reconstructible | Accepted | 2026-11-08 |
| [DATA-0074](../adr/DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md) | Garantir le cycle de vie et la cohérence des index dérivés : verrous par projet, writers par opération, readers en cache, publication atomique | Accepted | 2026-11-08 |
| [ARCH-0075](../adr/ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md) | Exposer le cœur par des adaptateurs minces isolés : REST Quarkus, MCP STDIO sur le SDK Java officiel, clients assistants | Accepted | 2026-11-08 |
| [ARCH-0076](../adr/ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md) | Garder un cœur Java sans framework, aux packages acycliques vérifiés par un test d'architecture, et n'extraire un module Maven que sur besoin réel | Accepted | 2026-11-08 |
| [ARCH-0077](../adr/ARCH-0077-positionner-nexus-comme-moteur-de-contexte-independant.md) | Positionner NEXUS comme moteur de contexte indépendant des modèles, des clients et des orchestrateurs | Accepted | 2026-11-08 |
| [ARCH-0078](../adr/ARCH-0078-normaliser-les-sources-de-contexte-natives-derriere-des-providers.md) | Découvrir et normaliser les sources de contexte natives des projets derrière des providers | Accepted | 2026-11-08 |
| [BE-0079](../adr/BE-0079-charger-les-agent-skills-par-divulgation-progressive.md) | Charger les Agent Skills par divulgation progressive, avec AI Skills Registry en snapshot local optionnel | Accepted | 2026-11-08 |
| [ARCH-0080](../adr/ARCH-0080-enrichir-l-intelligence-de-code-par-des-providers-et-importers-optionnels.md) | Enrichir l'intelligence de code par des providers et importers optionnels : SCIP, JDT Language Server, MINOS | Accepted | 2026-11-08 |
| [BE-0081](../adr/BE-0081-federer-la-recherche-locale-par-projet-sous-echeance-globale.md) | Fédérer la recherche locale par projet, avec provenance conservée et une échéance globale sans parallélisme | Accepted | 2026-11-08 |
| [SEC-0082](../adr/SEC-0082-borner-le-support-filesystem-et-la-traversee-des-chemins.md) | Borner le support aux filesystems locaux qualifiés et distinguer traversée stricte et compatibilité | Accepted | 2026-11-08 |
| [BE-0083](../adr/BE-0083-appliquer-des-regles-d-exclusion-du-scan-de-type-gitignore.md) | Appliquer des règles d'exclusion du scan de type gitignore, avec des exclusions intégrées | Accepted | 2026-11-08 |
| [ARCH-0085](../adr/ARCH-0085-stabiliser-le-contrat-de-sortie-publique-cli-rest-mcp.md) | Stabiliser le contrat de sortie publique dans la projection du cœur : CLI humaine ou JSON, codes de sortie, parité REST et MCP | Accepted | 2026-11-08 |
| [ARCH-0086](../adr/ARCH-0086-resoudre-la-configuration-et-nexus-home-dans-la-composition-root.md) | Résoudre la configuration NEXUS, dont NEXUS_HOME, dans la composition root et l'injecter | Accepted | 2026-11-08 |
| [BE-0087](../adr/BE-0087-projeter-le-voisinage-du-graphe-d-imports-a-la-demande.md) | Projeter le voisinage du graphe d'imports à la demande depuis SQLite, borné, sans matérialisation ni cache | Proposed | 2026-11-09 |
| [DATA-0088](../adr/DATA-0088-conserver-le-journal-rollback-sqlite.md) | Conserver le journal rollback SQLite et ne réévaluer WAL que sur mesure répétée | Proposed | 2026-11-09 |
| [GEN-0089](../adr/GEN-0089-integrer-et-publier-depuis-la-seule-branche-main.md) | Intégrer et publier depuis la seule branche main | Accepted | 2026-11-12 |
| [BE-0090](../adr/BE-0090-garder-la-recherche-semantique-optionnelle-et-qualifiee-hors-release.md) | Garder la recherche sémantique optionnelle et la qualifier en deux corpus, hors du chemin de release | Accepted | 2026-11-12 |
| [ADR-0000](../adr/archive/0000-adopter-les-adr-madr.md) | Adopter MADR comme format et définir la gouvernance des ADR | Superseded (GEN-0057) | 2026-07-19 |
| [ARCH-0001](../adr/archive/ARCH-0001-positionner-nexus-comme-moteur-intelligence-contexte.md) | Positionner NEXUS comme moteur d'intelligence de contexte indépendant des modèles | Superseded (ARCH-0077) | 2026-07-19 |
| [ARCH-0003](../adr/archive/ARCH-0003-conserver-un-coeur-java-sans-framework-applicatif.md) | Conserver un cœur Java sans framework applicatif obligatoire | Superseded (ARCH-0076) | 2026-07-19 |
| [ARCH-0004](../adr/archive/ARCH-0004-demarrer-avec-un-seul-module-maven.md) | Démarrer avec un seul module Maven et extraire uniquement sur besoin réel | Superseded (ARCH-0076) | 2026-07-19 |
| [DATA-0006](../adr/archive/DATA-0006-utiliser-sqlite-comme-source-de-verite-structurelle.md) | Utiliser SQLite comme source de vérité structurelle locale | Superseded (DATA-0073) | 2026-07-19 |
| [DATA-0007](../adr/archive/DATA-0007-utiliser-lucene-comme-index-de-recherche-local.md) | Utiliser Apache Lucene comme index de recherche local | Superseded (DATA-0073) | 2026-07-19 |
| [ARCH-0009](../adr/archive/ARCH-0009-rendre-intelligence-code-extensible-via-providers.md) | Rendre l'intelligence de code extensible via des providers et index externes | Superseded (ARCH-0080) | 2026-07-19 |
| [BE-0010](../adr/archive/BE-0010-adopter-un-ranking-hybride-deterministe-et-explicable.md) | Adopter un ranking hybride, déterministe et explicable | Superseded (BE-0071) | 2026-07-19 |
| [ARCH-0011](../adr/archive/ARCH-0011-normaliser-les-sources-de-contexte.md) | Normaliser les sources de contexte derrière des providers | Superseded (ARCH-0078) | 2026-07-19 |
| [ARCH-0012](../adr/archive/ARCH-0012-reutiliser-les-standards-instructions-et-agent-skills.md) | Réutiliser les standards existants pour les instructions et les skills | Superseded (ARCH-0078) | 2026-07-19 |
| [BE-0013](../adr/archive/BE-0013-construire-un-contextbundle-sous-budget-de-tokens.md) | Construire un ContextBundle sous budget de tokens explicable | Superseded (BE-0072) | 2026-07-19 |
| [BE-0014](../adr/archive/BE-0014-rendre-la-recherche-semantique-optionnelle.md) | Rendre la recherche sémantique et les embeddings optionnels | Superseded (BE-0084) | 2026-07-19 |
| [GEN-0015](../adr/archive/GEN-0015-valider-le-mvp-par-la-cli-avant-les-integrations.md) | Valider le MVP par la CLI avant l'API, MCP et les intégrations IDE | Superseded (ARCH-0085) | 2026-07-19 |
| [BE-0016](../adr/archive/BE-0016-utiliser-le-sdk-java-officiel-pour-mcp.md) | Utiliser le SDK Java MCP officiel pour l'adaptateur MCP | Superseded (ARCH-0075) | 2026-07-19 |
| [ARCH-0017](../adr/archive/ARCH-0017-decoupler-nexus-des-outils-et-orchestrateurs-externes.md) | Découpler NEXUS des outils et orchestrateurs externes | Superseded (ARCH-0077) | 2026-07-19 |
| [DATA-0019](../adr/archive/DATA-0019-stocker-les-donnees-locales-dans-un-nexus-home-configurable.md) | Stocker les données locales dans un NEXUS_HOME configurable | Superseded (ARCH-0086) | 2026-07-19 |
| [BE-0021](../adr/archive/BE-0021-reutiliser-jgit-pour-les-regles-gitignore-et-nexusignore.md) | Réutiliser JGit pour les règles `.gitignore` et `.nexusignore` | Superseded (BE-0083) | 2026-07-19 |
| [DATA-0022](../adr/archive/DATA-0022-traiter-lucene-comme-un-index-derive-reconstructible-de-sqlite.md) | Traiter Lucene comme un index dérivé et reconstructible de SQLite | Superseded (DATA-0073) | 2026-07-19 |
| [BE-0024](../adr/archive/BE-0024-combiner-lucene-et-sqlite-pour-la-recherche-de-candidats.md) | Combiner Lucene et SQLite pour la recherche de candidats | Superseded (BE-0071) | 2026-07-19 |
| [BE-0025](../adr/archive/BE-0025-normaliser-les-signaux-et-calculer-un-score-compose-explicable.md) | Normaliser les signaux et calculer un score composé explicable | Superseded (BE-0071) | 2026-07-19 |
| [BE-0026](../adr/archive/BE-0026-construire-un-graphe-minimal-de-fichiers-a-partir-des-imports.md) | Construire un graphe minimal de fichiers à partir des imports résolus | Superseded (BE-0071) | 2026-07-19 |
| [BE-0027](../adr/archive/BE-0027-utiliser-un-estimateur-de-tokens-local-deterministe-et-remplacable.md) | Utiliser un estimateur de tokens local, déterministe et remplaçable | Superseded (BE-0072) | 2026-07-19 |
| [BE-0028](../adr/archive/BE-0028-construire-le-contexte-a-partir-de-fragments-de-code-prioritairement-symboliques.md) | Construire le contexte à partir de fragments de code prioritairement symboliques | Superseded (BE-0072) | 2026-07-19 |
| [BE-0029](../adr/archive/BE-0029-selectionner-le-contextbundle-par-un-algorithme-glouton-deterministe-sous-budget.md) | Sélectionner le ContextBundle par un algorithme glouton déterministe sous budget | Superseded (BE-0072) | 2026-07-19 |
| [ARCH-0030](../adr/archive/ARCH-0030-stabiliser-le-contrat-cli-avec-sorties-humaines-json-et-codes-de-sortie.md) | Stabiliser le contrat CLI avec sorties humaines, JSON et codes de sortie | Superseded (ARCH-0085) | 2026-07-19 |
| [BE-0032](../adr/archive/BE-0032-preserver-et-normaliser-le-contexte-natif-des-projets.md) | Préserver et normaliser le contexte natif des projets | Superseded (ARCH-0078) | 2026-07-19 |
| [BE-0034](../adr/archive/BE-0034-adopter-la-divulgation-progressive-pour-les-agent-skills.md) | Adopter la divulgation progressive pour les Agent Skills | Superseded (BE-0079) | 2026-07-20 |
| [BE-0035](../adr/archive/BE-0035-integrer-le-contexte-git-local-comme-source-bornee-et-explicable.md) | Intégrer le contexte Git local comme source bornée et explicable | Superseded (BE-0070) | 2026-07-20 |
| [BE-0036](../adr/archive/BE-0036-importer-scip-comme-enrichissement-opportuniste.md) | Importer SCIP comme enrichissement opportuniste de l'intelligence de code | Superseded (ARCH-0080) | 2026-07-20 |
| [BE-0037](../adr/archive/BE-0037-integrer-jdt-language-server-comme-provider-java-profond-optionnel.md) | Intégrer JDT Language Server comme provider Java profond optionnel | Superseded (ARCH-0080) | 2026-07-20 |
| [ARCH-0039](../adr/archive/ARCH-0039-isoler-l-adaptateur-rest-quarkus-du-coeur-nexus.md) | Isoler l'adaptateur REST Quarkus du cœur NEXUS | Superseded (ARCH-0075) | 2026-07-20 |
| [ARCH-0040](../adr/archive/ARCH-0040-exposer-nexus-via-un-adaptateur-mcp-stdio-mince.md) | Exposer NEXUS via un adaptateur MCP STDIO mince | Superseded (ARCH-0075) | 2026-07-20 |
| [ARCH-0041](../adr/archive/ARCH-0041-reutiliser-le-serveur-mcp-pour-les-clients-assistants.md) | Réutiliser le serveur MCP pour les clients assistants | Superseded (ARCH-0075) | 2026-07-20 |
| [ARCH-0042](../adr/archive/ARCH-0042-consommer-ai-skills-registry-comme-snapshot-local-optionnel.md) | Consommer AI Skills Registry comme snapshot local optionnel | Superseded (BE-0079) | 2026-07-20 |
| [BE-0043](../adr/archive/BE-0043-federer-la-recherche-locale-par-projet-avant-un-moteur-externe.md) | Fédérer la recherche locale par projet avant d'introduire un moteur externe | Superseded (BE-0081) | 2026-07-21 |
| [ARCH-0044](../adr/archive/ARCH-0044-consommer-minos-via-un-contrat-json-local-versionne.md) | Consommer MINOS via un contrat JSON local versionné | Superseded (ARCH-0080) | 2026-07-24 |
| [DATA-0045](../adr/archive/DATA-0045-conserver-les-writers-operation-scoped-et-mettre-en-cache-les-readers-lucene.md) | Conserver les writers Lucene operation-scoped et mettre en cache les readers | Superseded (DATA-0074) | 2026-09-02 |
| [BE-0046](../adr/archive/BE-0046-mettre-en-cache-le-contexte-git-dans-les-runtimes-longue-duree.md) | Mettre en cache le contexte Git dans les runtimes longue durée | Superseded (BE-0070) | 2026-09-02 |
| [SEC-0047](../adr/archive/SEC-0047-borner-le-support-filesystem-aux-filesystems-locaux-qualifies.md) | Borner le support filesystem aux filesystems locaux qualifiés | Superseded (SEC-0082) | 2026-09-03 |
| [SEC-0048](../adr/archive/SEC-0048-distinguer-traversee-stricte-et-compatibilite-filesystem.md) | Distinguer traversée stricte et compatibilité filesystem | Superseded (SEC-0082) | 2026-09-09 |
| [DATA-0050](../adr/archive/DATA-0050-conserver-les-temporaires-sqlite-en-memoire.md) | Conserver les temporaires SQLite en mémoire | Reclassé | 2026-09-12 |
| [DATA-0051](../adr/archive/DATA-0051-borner-les-lots-d-ecriture-sqlite.md) | Borner les lots d'écriture SQLite | Reclassé | 2026-09-12 |
| [DATA-0052](../adr/archive/DATA-0052-garantir-la-coherence-des-lectures-et-des-index-derives.md) | Garantir la cohérence des lectures et des index dérivés | Superseded (DATA-0074) | 2026-09-12 |
| [BE-0053](../adr/archive/BE-0053-borner-le-parcours-des-arbres-git-avant-collecte.md) | Borner le parcours des arbres Git avant collecte | Superseded (BE-0070) | 2026-09-12 |
| [DATA-0054](../adr/archive/DATA-0054-lectures-registre-federees-groupees.md) | Grouper les lectures du registre pour les opérations fédérées | Superseded (BE-0081) | 2026-09-12 |
| [DATA-0055](../adr/archive/DATA-0055-preserver-les-lecteurs-lors-des-reconstructions-semantiques.md) | Préserver les lecteurs lors des reconstructions sémantiques | Superseded (DATA-0074) | 2026-09-13 |
| [ADR-0056](../adr/archive/0056-regrouper-le-registre-des-adr-dans-la-documentation-d-architecture.md) | Regrouper le registre des ADR dans la documentation d'architecture | Superseded (GEN-0057) | 2026-10-29 |
| [BE-0058](../adr/archive/BE-0058-exclure-les-worktrees-claude-code-du-scan-par-defaut.md) | Exclure les worktrees Claude Code du scan par défaut | Superseded (BE-0083) | 2026-10-30 |
| [ARCH-0059](../adr/archive/ARCH-0059-verifier-les-frontieres-du-coeur-par-un-test-d-architecture.md) | Vérifier les frontières du cœur par un test d'architecture | Superseded (ARCH-0076) | 2026-10-30 |
| [GEN-0060](../adr/archive/GEN-0060-qualifier-la-recherche-semantique-reelle-en-deux-corpus.md) | Qualifier la recherche sémantique réelle en deux corpus, hors du chemin de release | Superseded (BE-0084) | 2026-10-30 |
| [ARCH-0061](../adr/archive/ARCH-0061-decider-le-contrat-de-sortie-publique-dans-la-projection-du-coeur.md) | Décider le contrat de sortie publique dans la projection du cœur | Superseded (ARCH-0085) | 2026-10-30 |
| [DATA-0062](../adr/archive/DATA-0062-mettre-en-attente-les-analyses-d-indexation-dans-sqlite.md) | Mettre en attente les analyses d'indexation dans SQLite et committer Lucene une fois | Superseded (DATA-0074) | 2026-11-07 |
| [ARCH-0063](../adr/archive/ARCH-0063-casser-les-cycles-de-packages-du-coeur-par-des-packages-de-modele-feuilles.md) | Casser les cycles de packages du cœur par des packages de modèle feuilles | Superseded (ARCH-0076) | 2026-11-07 |
| [BE-0064](../adr/archive/BE-0064-borner-la-federation-par-une-echeance-globale-sans-parallelisme.md) | Borner la fédération par une échéance globale vérifiée entre projets, sans parallélisme | Superseded (BE-0081) | 2026-11-07 |
| [BE-0065](../adr/archive/BE-0065-dimensionner-les-caches-git-sur-le-scope-federe.md) | Dimensionner les caches Git des runtimes longue durée sur le scope fédéré | Superseded (BE-0070) | 2026-11-07 |
| [ARCH-0066](../adr/archive/ARCH-0066-resoudre-la-configuration-dans-la-composition-root.md) | Résoudre la configuration NEXUS dans la composition root et l'injecter | Superseded (ARCH-0086) | 2026-11-07 |
| [BE-0067](../adr/archive/BE-0067-plafonner-la-decouverte-du-depot-git-a-la-racine-du-projet.md) | Plafonner la découverte du dépôt Git à la racine du projet, sauf dépôt parent de l'utilisateur courant | Superseded (BE-0070) | 2026-11-07 |
| [DATA-0069](../adr/archive/DATA-0069-dimensionner-le-cache-de-pages-sqlite-des-connexions.md) | Dimensionner le cache de pages SQLite des connexions | Reclassé | 2026-11-08 |
| [BE-0084](../adr/archive/BE-0084-garder-la-recherche-semantique-optionnelle-et-qualifiee-hors-release.md) | Garder la recherche sémantique optionnelle et la qualifier en deux corpus, hors du chemin de release | Superseded (BE-0090) | 2026-11-08 |

## Regroupements thématiques

### Fondations

ARCH-0077, GEN-0002, ARCH-0005, ARCH-0076, ARCH-0086 — positionnement, Java 21, local-first et
opt-in, cœur sans framework aux packages acycliques, configuration résolue par la composition root.

### Persistance et indexation

DATA-0073, DATA-0074, DATA-0088, DATA-0018, DATA-0020, DATA-0023, DATA-0049, BE-0083 — SQLite canonique
et Lucene dérivé, cycle de vie et cohérence des index dérivés, journal rollback SQLite, identités,
migrations, SHA-256, identité repository, règles d'exclusion du scan.

### Recherche, ranking et contexte

BE-0071, BE-0087, BE-0072, BE-0090 — recherche de candidats et score composé, voisinage du graphe
d'imports projeté à la demande, ContextBundle sous budget, recherche sémantique optionnelle et sa
qualification.

### Sources contextuelles

ARCH-0078, SEC-0033, BE-0079, BE-0070 — sources natives normalisées, séparation des instructions
et de la configuration opérationnelle, Agent Skills, contexte Git local.

### Intelligence de code

BE-0008, BE-0038, ARCH-0080 — JavaParser, langages additionnels, providers et importers optionnels
(SCIP, JDT LS, MINOS).

### Surfaces d'exposition et fédération

ARCH-0075, ARCH-0085, BE-0081 — adaptateurs REST, MCP et clients assistants, contrat de sortie
publique, fédération locale bornée.

### Confinement et chaîne de release

SEC-0082, GEN-0031, SEC-0068 — filesystems supportés et traversée, JAR autonome, publication depuis
l'environnement protégé `release`.

### Gouvernance

GEN-0057 — registre des ADR au format Archgate (remplace ADR-0000 et ADR-0056, archivés).
GEN-0089 — intégration et release depuis la seule branche `main`.
