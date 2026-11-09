# Registre des décisions d'architecture de NEXUS

Ce répertoire contient les **Architecture Decision Records (ADR)** de NEXUS : c'est le registre unique des décisions, à l'emplacement que prévoit arc42 (section 9, [`09-decisions.md`](../arc42/09-decisions.md)).

Les ADR constituent l'historique durable des décisions architecturales significatives du projet. Ils complètent `docs/architecture/overview.md` :

- `docs/architecture/overview.md` décrit l'architecture actuelle de manière synthétique ;
- les ADR expliquent **pourquoi** cette architecture existe, quelles alternatives ont été étudiées et quelles conséquences ont été acceptées.

## Convention

Le registre suit le format et la convention d'[Archgate](https://cli.archgate.dev/), décidés par [GEN-0057](GEN-0057-tenir-le-registre-d-adr-au-format-archgate.md), qui remplace ADR-0000 (format MADR) et ADR-0056 (emplacement). `.archgate/config.json` pointe sur ce dossier (`paths.adrs`) ; `.archgate/adrs/` ne contient qu'un `.gitkeep`.

- **Nom** : `{PRÉFIXE}-{NNNN}-{slug}.md`, slug en kebab-case français sans accents. Préfixe du domaine : `architecture` → `ARCH`, `backend` → `BE`, `data` → `DATA`, `general` → `GEN`, `security` → `SEC`. Le numéro, sur quatre chiffres, est unique dans tout le registre (archive comprise), pris à la suite de cet index et jamais renuméroté ni réutilisé.
- **En-tête YAML en ligne 1** : `id: {PRÉFIXE}-{NNNN}` (début du nom de fichier), `title`, `domain`, `rules` (`true` seulement avec une règle compagnon `{même nom}.rules.ts` vue en échec), `files` facultatif. Le titre `#` reprend exactement le `title`.
- **Statut et date** en tête de corps : `- **Statut** : …`, `- **Date** : AAAA-MM-JJ`, puis les relations (`Complète`, `Remplace`, `Remplacé par`).
- **Sections** aux titres d'Archgate : `Context`, `Decision`, `Do's and Don'ts` (`Do`, `Don't`), `Consequences`, `Compliance and Enforcement`, `References` ; les autres sections (Facteurs de décision, Options envisagées, Conditions de réexamen, Décisions liées…) gardent leur titre français. Le texte est en français.
- **Gabarit** : [`gabarit/template.md`](gabarit/template.md). `archgate adr create` n'est pas utilisé.

Les alternatives rejetées sont documentées **dans l'ADR de la décision concernée**.

## Statuts

- `Proposed` : décision proposée, encore ouverte à discussion ;
- `Accepted` : décision acceptée et applicable ;
- `Superseded` : décision remplacée par un ADR plus récent ;
- `Rejected` : proposition étudiée mais explicitement rejetée avant adoption.

Une décision `Accepted` ne doit pas être réécrite ultérieurement pour modifier rétroactivement sa justification. Lorsqu'une décision change de manière substantielle, un nouvel ADR la remplace ; l'ancien passe `Superseded`, reçoit un bandeau qui renvoie à son remplaçant et part dans [`archive/`](archive/), où Archgate ne le lit plus. Un ADR n'est jamais supprimé. Un écart entre une décision et le code s'écrit dans une section « Écarts constatés » datée, sans modifier la décision.

Les corrections éditoriales ou factuelles mineures restent autorisées tant qu'elles ne changent pas le sens historique de la décision.

## Quand écrire un ADR

Oui si la décision est **durable et structurante** et tranche entre des options réelles : format persistant ou migration de schéma, contrat public CLI/REST/MCP, nouvelle borne ou politique de sécurité, dépendance majeure ou nouveau provider, changement d'une décision existante. Non pour un simple durcissement d'implémentation, un refactoring sans changement de contrat ou une montée de version mineure (skill `nexus-adr`, commande `/nx-adr`).

## Contrôles

- `archgate check` exécute la règle compagnon de GEN-0057 (nommage, `id`, préfixe, numéro unique, titre, présence dans cet index, sections Archgate) ; `archgate adr list` liste les ADR actifs.
- Ce fichier n'a pas d'en-tête Archgate : la CLI le signale comme non lu.

## Index

31 ADR actifs : architecture 8, backend 10, data 7, general 3, security 3.

| ADR | Décision | Date | Statut |
|---|---|---|---|
| [GEN-0002](GEN-0002-compiler-le-coeur-en-java-21.md) | Compiler le cœur avec Java 21 comme niveau minimal | 2026-07-19 | Accepted |
| [ARCH-0005](ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.md) | Adopter un fonctionnement local-first et des intégrations externes opt-in | 2026-07-19 | Accepted |
| [BE-0008](BE-0008-utiliser-javaparser-comme-analyseur-java-embarque.md) | Utiliser JavaParser comme analyseur Java embarqué du MVP | 2026-07-19 | Accepted |
| [DATA-0018](DATA-0018-utiliser-des-uuid-pour-les-identites-metier-et-des-ids-locaux-pour-la-persistance.md) | Utiliser des UUID pour les identités métier et des identifiants locaux pour la persistance | 2026-07-19 | Accepted |
| [DATA-0020](DATA-0020-versionner-le-schema-sqlite-avec-des-migrations-sql-embarquees.md) | Versionner le schéma SQLite avec des migrations SQL embarquées | 2026-07-19 | Accepted |
| [DATA-0023](DATA-0023-utiliser-sha-256-pour-detecter-les-changements-de-fichiers.md) | Utiliser SHA-256 pour détecter les changements de fichiers | 2026-07-19 | Accepted |
| [GEN-0031](GEN-0031-packager-la-cli-dans-un-jar-autonome.md) | Packager la CLI dans un JAR autonome | 2026-07-19 | Accepted |
| [SEC-0033](SEC-0033-separer-instructions-contextuelles-et-configuration-operationnelle-des-agents.md) | Séparer les instructions contextuelles de la configuration opérationnelle des agents | 2026-07-19 | Accepted |
| [BE-0038](BE-0038-indexer-les-langages-additionnels-lexicalement-et-enrichir-la-structure-via-providers.md) | Indexer les langages additionnels lexicalement et enrichir la structure via des providers | 2026-07-20 | Accepted |
| [DATA-0049](DATA-0049-identite-repository-par-composants-et-diagnostics-publics.md) | Identité repository par composants et diagnostics publics | 2026-09-09 | Accepted |
| [GEN-0057](GEN-0057-tenir-le-registre-d-adr-au-format-archgate.md) | Tenir le registre d'ADR au format Archgate, à l'emplacement arc42 | 2026-10-29 | Accepted |
| [SEC-0068](SEC-0068-publier-les-releases-depuis-un-environnement-protege-avec-des-assets-attestes.md) | Publier les releases depuis un environnement protégé, avec des assets attestés et des tags de pré-version | 2026-11-07 | Proposed |
| [BE-0070](BE-0070-integrer-le-contexte-git-local-borne-mis-en-cache-et-confine.md) | Intégrer le contexte Git local, borné, mis en cache en mémoire et confiné au dépôt du projet | 2026-11-08 | Accepted |
| [BE-0071](BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md) | Rechercher les candidats dans Lucene, SQLite et le graphe d'imports, puis les classer par un score composé explicable | 2026-11-08 | Accepted |
| [BE-0072](BE-0072-construire-le-contextbundle-sous-budget-de-tokens.md) | Construire le ContextBundle sous budget de tokens : estimateur local, fragments symboliques, sélection gloutonne déterministe | 2026-11-08 | Accepted |
| [DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md) | Tenir SQLite pour source de vérité locale et Lucene pour index de recherche dérivé et reconstructible | 2026-11-08 | Accepted |
| [DATA-0074](DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md) | Garantir le cycle de vie et la cohérence des index dérivés : verrous par projet, writers par opération, readers en cache, publication atomique | 2026-11-08 | Accepted |
| [ARCH-0075](ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md) | Exposer le cœur par des adaptateurs minces isolés : REST Quarkus, MCP STDIO sur le SDK Java officiel, clients assistants | 2026-11-08 | Accepted |
| [ARCH-0076](ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md) | Garder un cœur Java sans framework, aux packages acycliques vérifiés par un test d'architecture, et n'extraire un module Maven que sur besoin réel | 2026-11-08 | Accepted |
| [ARCH-0077](ARCH-0077-positionner-nexus-comme-moteur-de-contexte-independant.md) | Positionner NEXUS comme moteur de contexte indépendant des modèles, des clients et des orchestrateurs | 2026-11-08 | Accepted |
| [ARCH-0078](ARCH-0078-normaliser-les-sources-de-contexte-natives-derriere-des-providers.md) | Découvrir et normaliser les sources de contexte natives des projets derrière des providers | 2026-11-08 | Accepted |
| [BE-0079](BE-0079-charger-les-agent-skills-par-divulgation-progressive.md) | Charger les Agent Skills par divulgation progressive, avec AI Skills Registry en snapshot local optionnel | 2026-11-08 | Accepted |
| [ARCH-0080](ARCH-0080-enrichir-l-intelligence-de-code-par-des-providers-et-importers-optionnels.md) | Enrichir l'intelligence de code par des providers et importers optionnels : SCIP, JDT Language Server, MINOS | 2026-11-08 | Accepted |
| [BE-0081](BE-0081-federer-la-recherche-locale-par-projet-sous-echeance-globale.md) | Fédérer la recherche locale par projet, avec provenance conservée et une échéance globale sans parallélisme | 2026-11-08 | Accepted |
| [SEC-0082](SEC-0082-borner-le-support-filesystem-et-la-traversee-des-chemins.md) | Borner le support aux filesystems locaux qualifiés et distinguer traversée stricte et compatibilité | 2026-11-08 | Accepted |
| [BE-0083](BE-0083-appliquer-des-regles-d-exclusion-du-scan-de-type-gitignore.md) | Appliquer des règles d'exclusion du scan de type gitignore, avec des exclusions intégrées | 2026-11-08 | Accepted |
| [BE-0084](BE-0084-garder-la-recherche-semantique-optionnelle-et-qualifiee-hors-release.md) | Garder la recherche sémantique optionnelle et la qualifier en deux corpus, hors du chemin de release | 2026-11-08 | Accepted |
| [ARCH-0085](ARCH-0085-stabiliser-le-contrat-de-sortie-publique-cli-rest-mcp.md) | Stabiliser le contrat de sortie publique dans la projection du cœur : CLI humaine ou JSON, codes de sortie, parité REST et MCP | 2026-11-08 | Accepted |
| [ARCH-0086](ARCH-0086-resoudre-la-configuration-et-nexus-home-dans-la-composition-root.md) | Résoudre la configuration NEXUS, dont NEXUS_HOME, dans la composition root et l'injecter | 2026-11-08 | Accepted |
| [BE-0087](BE-0087-projeter-le-voisinage-du-graphe-d-imports-a-la-demande.md) | Projeter le voisinage du graphe d'imports à la demande depuis SQLite, borné, sans matérialisation ni cache | 2026-11-09 | Proposed |
| [DATA-0088](DATA-0088-conserver-le-journal-rollback-sqlite.md) | Conserver le journal rollback SQLite et ne réévaluer WAL que sur mesure répétée | 2026-11-09 | Proposed |

## Archive

ADR remplacés ou reclassés, conservés pour l'historique et non lus par Archgate.

| ADR | Décision | Statut | Remplacé par |
|---|---|---|---|
| [ADR-0000](archive/0000-adopter-les-adr-madr.md) | Adopter MADR comme format et définir la gouvernance des ADR | Superseded | [GEN-0057](GEN-0057-tenir-le-registre-d-adr-au-format-archgate.md) |
| [ARCH-0001](archive/ARCH-0001-positionner-nexus-comme-moteur-intelligence-contexte.md) | Positionner NEXUS comme moteur d'intelligence de contexte indépendant des modèles | Superseded | [ARCH-0077](ARCH-0077-positionner-nexus-comme-moteur-de-contexte-independant.md) |
| [ARCH-0003](archive/ARCH-0003-conserver-un-coeur-java-sans-framework-applicatif.md) | Conserver un cœur Java sans framework applicatif obligatoire | Superseded | [ARCH-0076](ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md) |
| [ARCH-0004](archive/ARCH-0004-demarrer-avec-un-seul-module-maven.md) | Démarrer avec un seul module Maven et extraire uniquement sur besoin réel | Superseded | [ARCH-0076](ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md) |
| [DATA-0006](archive/DATA-0006-utiliser-sqlite-comme-source-de-verite-structurelle.md) | Utiliser SQLite comme source de vérité structurelle locale | Superseded | [DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md) |
| [DATA-0007](archive/DATA-0007-utiliser-lucene-comme-index-de-recherche-local.md) | Utiliser Apache Lucene comme index de recherche local | Superseded | [DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md) |
| [ARCH-0009](archive/ARCH-0009-rendre-intelligence-code-extensible-via-providers.md) | Rendre l'intelligence de code extensible via des providers et index externes | Superseded | [ARCH-0080](ARCH-0080-enrichir-l-intelligence-de-code-par-des-providers-et-importers-optionnels.md) |
| [BE-0010](archive/BE-0010-adopter-un-ranking-hybride-deterministe-et-explicable.md) | Adopter un ranking hybride, déterministe et explicable | Superseded | [BE-0071](BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md) |
| [ARCH-0011](archive/ARCH-0011-normaliser-les-sources-de-contexte.md) | Normaliser les sources de contexte derrière des providers | Superseded | [ARCH-0078](ARCH-0078-normaliser-les-sources-de-contexte-natives-derriere-des-providers.md) |
| [ARCH-0012](archive/ARCH-0012-reutiliser-les-standards-instructions-et-agent-skills.md) | Réutiliser les standards existants pour les instructions et les skills | Superseded | [ARCH-0078](ARCH-0078-normaliser-les-sources-de-contexte-natives-derriere-des-providers.md) |
| [BE-0013](archive/BE-0013-construire-un-contextbundle-sous-budget-de-tokens.md) | Construire un ContextBundle sous budget de tokens explicable | Superseded | [BE-0072](BE-0072-construire-le-contextbundle-sous-budget-de-tokens.md) |
| [BE-0014](archive/BE-0014-rendre-la-recherche-semantique-optionnelle.md) | Rendre la recherche sémantique et les embeddings optionnels | Superseded | [BE-0084](BE-0084-garder-la-recherche-semantique-optionnelle-et-qualifiee-hors-release.md) |
| [GEN-0015](archive/GEN-0015-valider-le-mvp-par-la-cli-avant-les-integrations.md) | Valider le MVP par la CLI avant l'API, MCP et les intégrations IDE | Superseded | [ARCH-0085](ARCH-0085-stabiliser-le-contrat-de-sortie-publique-cli-rest-mcp.md) |
| [BE-0016](archive/BE-0016-utiliser-le-sdk-java-officiel-pour-mcp.md) | Utiliser le SDK Java MCP officiel pour l'adaptateur MCP | Superseded | [ARCH-0075](ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md) |
| [ARCH-0017](archive/ARCH-0017-decoupler-nexus-des-outils-et-orchestrateurs-externes.md) | Découpler NEXUS des outils et orchestrateurs externes | Superseded | [ARCH-0077](ARCH-0077-positionner-nexus-comme-moteur-de-contexte-independant.md) |
| [DATA-0019](archive/DATA-0019-stocker-les-donnees-locales-dans-un-nexus-home-configurable.md) | Stocker les données locales dans un NEXUS_HOME configurable | Superseded | [ARCH-0086](ARCH-0086-resoudre-la-configuration-et-nexus-home-dans-la-composition-root.md) |
| [BE-0021](archive/BE-0021-reutiliser-jgit-pour-les-regles-gitignore-et-nexusignore.md) | Réutiliser JGit pour les règles `.gitignore` et `.nexusignore` | Superseded | [BE-0083](BE-0083-appliquer-des-regles-d-exclusion-du-scan-de-type-gitignore.md) |
| [DATA-0022](archive/DATA-0022-traiter-lucene-comme-un-index-derive-reconstructible-de-sqlite.md) | Traiter Lucene comme un index dérivé et reconstructible de SQLite | Superseded | [DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md) |
| [BE-0024](archive/BE-0024-combiner-lucene-et-sqlite-pour-la-recherche-de-candidats.md) | Combiner Lucene et SQLite pour la recherche de candidats | Superseded | [BE-0071](BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md) |
| [BE-0025](archive/BE-0025-normaliser-les-signaux-et-calculer-un-score-compose-explicable.md) | Normaliser les signaux et calculer un score composé explicable | Superseded | [BE-0071](BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md) |
| [BE-0026](archive/BE-0026-construire-un-graphe-minimal-de-fichiers-a-partir-des-imports.md) | Construire un graphe minimal de fichiers à partir des imports résolus | Superseded | [BE-0071](BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md) |
| [BE-0027](archive/BE-0027-utiliser-un-estimateur-de-tokens-local-deterministe-et-remplacable.md) | Utiliser un estimateur de tokens local, déterministe et remplaçable | Superseded | [BE-0072](BE-0072-construire-le-contextbundle-sous-budget-de-tokens.md) |
| [BE-0028](archive/BE-0028-construire-le-contexte-a-partir-de-fragments-de-code-prioritairement-symboliques.md) | Construire le contexte à partir de fragments de code prioritairement symboliques | Superseded | [BE-0072](BE-0072-construire-le-contextbundle-sous-budget-de-tokens.md) |
| [BE-0029](archive/BE-0029-selectionner-le-contextbundle-par-un-algorithme-glouton-deterministe-sous-budget.md) | Sélectionner le ContextBundle par un algorithme glouton déterministe sous budget | Superseded | [BE-0072](BE-0072-construire-le-contextbundle-sous-budget-de-tokens.md) |
| [ARCH-0030](archive/ARCH-0030-stabiliser-le-contrat-cli-avec-sorties-humaines-json-et-codes-de-sortie.md) | Stabiliser le contrat CLI avec sorties humaines, JSON et codes de sortie | Superseded | [ARCH-0085](ARCH-0085-stabiliser-le-contrat-de-sortie-publique-cli-rest-mcp.md) |
| [BE-0032](archive/BE-0032-preserver-et-normaliser-le-contexte-natif-des-projets.md) | Préserver et normaliser le contexte natif des projets | Superseded | [ARCH-0078](ARCH-0078-normaliser-les-sources-de-contexte-natives-derriere-des-providers.md) |
| [BE-0034](archive/BE-0034-adopter-la-divulgation-progressive-pour-les-agent-skills.md) | Adopter la divulgation progressive pour les Agent Skills | Superseded | [BE-0079](BE-0079-charger-les-agent-skills-par-divulgation-progressive.md) |
| [BE-0035](archive/BE-0035-integrer-le-contexte-git-local-comme-source-bornee-et-explicable.md) | Intégrer le contexte Git local comme source bornée et explicable | Superseded | [BE-0070](BE-0070-integrer-le-contexte-git-local-borne-mis-en-cache-et-confine.md) |
| [BE-0036](archive/BE-0036-importer-scip-comme-enrichissement-opportuniste.md) | Importer SCIP comme enrichissement opportuniste de l'intelligence de code | Superseded | [ARCH-0080](ARCH-0080-enrichir-l-intelligence-de-code-par-des-providers-et-importers-optionnels.md) |
| [BE-0037](archive/BE-0037-integrer-jdt-language-server-comme-provider-java-profond-optionnel.md) | Intégrer JDT Language Server comme provider Java profond optionnel | Superseded | [ARCH-0080](ARCH-0080-enrichir-l-intelligence-de-code-par-des-providers-et-importers-optionnels.md) |
| [ARCH-0039](archive/ARCH-0039-isoler-l-adaptateur-rest-quarkus-du-coeur-nexus.md) | Isoler l'adaptateur REST Quarkus du cœur NEXUS | Superseded | [ARCH-0075](ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md) |
| [ARCH-0040](archive/ARCH-0040-exposer-nexus-via-un-adaptateur-mcp-stdio-mince.md) | Exposer NEXUS via un adaptateur MCP STDIO mince | Superseded | [ARCH-0075](ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md) |
| [ARCH-0041](archive/ARCH-0041-reutiliser-le-serveur-mcp-pour-les-clients-assistants.md) | Réutiliser le serveur MCP pour les clients assistants | Superseded | [ARCH-0075](ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md) |
| [ARCH-0042](archive/ARCH-0042-consommer-ai-skills-registry-comme-snapshot-local-optionnel.md) | Consommer AI Skills Registry comme snapshot local optionnel | Superseded | [BE-0079](BE-0079-charger-les-agent-skills-par-divulgation-progressive.md) |
| [BE-0043](archive/BE-0043-federer-la-recherche-locale-par-projet-avant-un-moteur-externe.md) | Fédérer la recherche locale par projet avant d'introduire un moteur externe | Superseded | [BE-0081](BE-0081-federer-la-recherche-locale-par-projet-sous-echeance-globale.md) |
| [ARCH-0044](archive/ARCH-0044-consommer-minos-via-un-contrat-json-local-versionne.md) | Consommer MINOS via un contrat JSON local versionné | Superseded | [ARCH-0080](ARCH-0080-enrichir-l-intelligence-de-code-par-des-providers-et-importers-optionnels.md) |
| [DATA-0045](archive/DATA-0045-conserver-les-writers-operation-scoped-et-mettre-en-cache-les-readers-lucene.md) | Conserver les writers Lucene operation-scoped et mettre en cache les readers | Superseded | [DATA-0074](DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md) |
| [BE-0046](archive/BE-0046-mettre-en-cache-le-contexte-git-dans-les-runtimes-longue-duree.md) | Mettre en cache le contexte Git dans les runtimes longue durée | Superseded | [BE-0070](BE-0070-integrer-le-contexte-git-local-borne-mis-en-cache-et-confine.md) |
| [SEC-0047](archive/SEC-0047-borner-le-support-filesystem-aux-filesystems-locaux-qualifies.md) | Borner le support filesystem aux filesystems locaux qualifiés | Superseded | [SEC-0082](SEC-0082-borner-le-support-filesystem-et-la-traversee-des-chemins.md) |
| [SEC-0048](archive/SEC-0048-distinguer-traversee-stricte-et-compatibilite-filesystem.md) | Distinguer traversée stricte et compatibilité filesystem | Superseded | [SEC-0082](SEC-0082-borner-le-support-filesystem-et-la-traversee-des-chemins.md) |
| [DATA-0050](archive/DATA-0050-conserver-les-temporaires-sqlite-en-memoire.md) | Conserver les temporaires SQLite en mémoire | Reclassé | — (reclassé, voir [sqlite-write-contention.md](../../developer/sqlite-write-contention.md)) |
| [DATA-0051](archive/DATA-0051-borner-les-lots-d-ecriture-sqlite.md) | Borner les lots d'écriture SQLite | Reclassé | — (reclassé, voir [sqlite-write-contention.md](../../developer/sqlite-write-contention.md)) |
| [DATA-0052](archive/DATA-0052-garantir-la-coherence-des-lectures-et-des-index-derives.md) | Garantir la cohérence des lectures et des index dérivés | Superseded | [DATA-0074](DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md) |
| [BE-0053](archive/BE-0053-borner-le-parcours-des-arbres-git-avant-collecte.md) | Borner le parcours des arbres Git avant collecte | Superseded | [BE-0070](BE-0070-integrer-le-contexte-git-local-borne-mis-en-cache-et-confine.md) |
| [DATA-0054](archive/DATA-0054-lectures-registre-federees-groupees.md) | Grouper les lectures du registre pour les opérations fédérées | Superseded | [BE-0081](BE-0081-federer-la-recherche-locale-par-projet-sous-echeance-globale.md) |
| [DATA-0055](archive/DATA-0055-preserver-les-lecteurs-lors-des-reconstructions-semantiques.md) | Préserver les lecteurs lors des reconstructions sémantiques | Superseded | [DATA-0074](DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md) |
| [ADR-0056](archive/0056-regrouper-le-registre-des-adr-dans-la-documentation-d-architecture.md) | Regrouper le registre des ADR dans la documentation d'architecture | Superseded | [GEN-0057](GEN-0057-tenir-le-registre-d-adr-au-format-archgate.md) |
| [BE-0058](archive/BE-0058-exclure-les-worktrees-claude-code-du-scan-par-defaut.md) | Exclure les worktrees Claude Code du scan par défaut | Superseded | [BE-0083](BE-0083-appliquer-des-regles-d-exclusion-du-scan-de-type-gitignore.md) |
| [ARCH-0059](archive/ARCH-0059-verifier-les-frontieres-du-coeur-par-un-test-d-architecture.md) | Vérifier les frontières du cœur par un test d'architecture | Superseded | [ARCH-0076](ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md) |
| [GEN-0060](archive/GEN-0060-qualifier-la-recherche-semantique-reelle-en-deux-corpus.md) | Qualifier la recherche sémantique réelle en deux corpus, hors du chemin de release | Superseded | [BE-0084](BE-0084-garder-la-recherche-semantique-optionnelle-et-qualifiee-hors-release.md) |
| [ARCH-0061](archive/ARCH-0061-decider-le-contrat-de-sortie-publique-dans-la-projection-du-coeur.md) | Décider le contrat de sortie publique dans la projection du cœur | Superseded | [ARCH-0085](ARCH-0085-stabiliser-le-contrat-de-sortie-publique-cli-rest-mcp.md) |
| [DATA-0062](archive/DATA-0062-mettre-en-attente-les-analyses-d-indexation-dans-sqlite.md) | Mettre en attente les analyses d'indexation dans SQLite et committer Lucene une fois | Superseded | [DATA-0074](DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md) |
| [ARCH-0063](archive/ARCH-0063-casser-les-cycles-de-packages-du-coeur-par-des-packages-de-modele-feuilles.md) | Casser les cycles de packages du cœur par des packages de modèle feuilles | Superseded | [ARCH-0076](ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md) |
| [BE-0064](archive/BE-0064-borner-la-federation-par-une-echeance-globale-sans-parallelisme.md) | Borner la fédération par une échéance globale vérifiée entre projets, sans parallélisme | Superseded | [BE-0081](BE-0081-federer-la-recherche-locale-par-projet-sous-echeance-globale.md) |
| [BE-0065](archive/BE-0065-dimensionner-les-caches-git-sur-le-scope-federe.md) | Dimensionner les caches Git des runtimes longue durée sur le scope fédéré | Superseded | [BE-0070](BE-0070-integrer-le-contexte-git-local-borne-mis-en-cache-et-confine.md) |
| [ARCH-0066](archive/ARCH-0066-resoudre-la-configuration-dans-la-composition-root.md) | Résoudre la configuration NEXUS dans la composition root et l'injecter | Superseded | [ARCH-0086](ARCH-0086-resoudre-la-configuration-et-nexus-home-dans-la-composition-root.md) |
| [BE-0067](archive/BE-0067-plafonner-la-decouverte-du-depot-git-a-la-racine-du-projet.md) | Plafonner la découverte du dépôt Git à la racine du projet, sauf dépôt parent de l'utilisateur courant | Superseded | [BE-0070](BE-0070-integrer-le-contexte-git-local-borne-mis-en-cache-et-confine.md) |
| [DATA-0069](archive/DATA-0069-dimensionner-le-cache-de-pages-sqlite-des-connexions.md) | Dimensionner le cache de pages SQLite des connexions | Reclassé | — (reclassé, voir [sqlite-write-contention.md](../../developer/sqlite-write-contention.md)) |

## Références

- Archgate : https://cli.archgate.dev/
- MADR (format d'origine du registre, ADR-0000) : https://adr.github.io/madr/
