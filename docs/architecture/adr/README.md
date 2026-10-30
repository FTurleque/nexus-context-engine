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

57 ADR actifs : architecture 14, backend 22, data 14, general 4, security 3.

| ADR | Décision | Date | Statut |
|---|---|---|---|
| [ARCH-0001](ARCH-0001-positionner-nexus-comme-moteur-intelligence-contexte.md) | Positionner NEXUS comme moteur d'intelligence de contexte indépendant des modèles | 2026-07-19 | Accepted |
| [GEN-0002](GEN-0002-compiler-le-coeur-en-java-21.md) | Compiler le cœur avec Java 21 comme niveau minimal | 2026-07-19 | Accepted |
| [ARCH-0003](ARCH-0003-conserver-un-coeur-java-sans-framework-applicatif.md) | Conserver un cœur Java sans framework applicatif obligatoire | 2026-07-19 | Accepted |
| [ARCH-0004](ARCH-0004-demarrer-avec-un-seul-module-maven.md) | Démarrer avec un seul module Maven et extraire uniquement sur besoin réel | 2026-07-19 | Accepted |
| [ARCH-0005](ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.md) | Adopter un fonctionnement local-first et des intégrations externes opt-in | 2026-07-19 | Accepted |
| [DATA-0006](DATA-0006-utiliser-sqlite-comme-source-de-verite-structurelle.md) | Utiliser SQLite comme source de vérité structurelle locale | 2026-07-19 | Accepted |
| [DATA-0007](DATA-0007-utiliser-lucene-comme-index-de-recherche-local.md) | Utiliser Apache Lucene comme index de recherche local | 2026-07-19 | Accepted |
| [BE-0008](BE-0008-utiliser-javaparser-comme-analyseur-java-embarque.md) | Utiliser JavaParser comme analyseur Java embarqué du MVP | 2026-07-19 | Accepted |
| [ARCH-0009](ARCH-0009-rendre-intelligence-code-extensible-via-providers.md) | Rendre l'intelligence de code extensible via des providers et index externes | 2026-07-19 | Accepted |
| [BE-0010](BE-0010-adopter-un-ranking-hybride-deterministe-et-explicable.md) | Adopter un ranking hybride, déterministe et explicable | 2026-07-19 | Accepted |
| [ARCH-0011](ARCH-0011-normaliser-les-sources-de-contexte.md) | Normaliser les sources de contexte derrière des providers | 2026-07-19 | Accepted |
| [ARCH-0012](ARCH-0012-reutiliser-les-standards-instructions-et-agent-skills.md) | Réutiliser les standards existants pour les instructions et les skills | 2026-07-19 | Accepted |
| [BE-0013](BE-0013-construire-un-contextbundle-sous-budget-de-tokens.md) | Construire un ContextBundle sous budget de tokens explicable | 2026-07-19 | Accepted |
| [BE-0014](BE-0014-rendre-la-recherche-semantique-optionnelle.md) | Rendre la recherche sémantique et les embeddings optionnels | 2026-07-19 | Accepted |
| [GEN-0015](GEN-0015-valider-le-mvp-par-la-cli-avant-les-integrations.md) | Valider le MVP par la CLI avant l'API, MCP et les intégrations IDE | 2026-07-19 | Accepted |
| [BE-0016](BE-0016-utiliser-le-sdk-java-officiel-pour-mcp.md) | Utiliser le SDK Java MCP officiel pour l'adaptateur MCP | 2026-07-19 | Accepted |
| [ARCH-0017](ARCH-0017-decoupler-nexus-des-outils-et-orchestrateurs-externes.md) | Découpler NEXUS des outils et orchestrateurs externes | 2026-07-19 | Accepted |
| [DATA-0018](DATA-0018-utiliser-des-uuid-pour-les-identites-metier-et-des-ids-locaux-pour-la-persistance.md) | Utiliser des UUID pour les identités métier et des identifiants locaux pour la persistance | 2026-07-19 | Accepted |
| [DATA-0019](DATA-0019-stocker-les-donnees-locales-dans-un-nexus-home-configurable.md) | Stocker les données locales dans un NEXUS_HOME configurable | 2026-07-19 | Accepted |
| [DATA-0020](DATA-0020-versionner-le-schema-sqlite-avec-des-migrations-sql-embarquees.md) | Versionner le schéma SQLite avec des migrations SQL embarquées | 2026-07-19 | Accepted |
| [BE-0021](BE-0021-reutiliser-jgit-pour-les-regles-gitignore-et-nexusignore.md) | Réutiliser JGit pour les règles `.gitignore` et `.nexusignore` | 2026-07-19 | Accepted |
| [DATA-0022](DATA-0022-traiter-lucene-comme-un-index-derive-reconstructible-de-sqlite.md) | Traiter Lucene comme un index dérivé et reconstructible de SQLite | 2026-07-19 | Accepted |
| [DATA-0023](DATA-0023-utiliser-sha-256-pour-detecter-les-changements-de-fichiers.md) | Utiliser SHA-256 pour détecter les changements de fichiers | 2026-07-19 | Accepted |
| [BE-0024](BE-0024-combiner-lucene-et-sqlite-pour-la-recherche-de-candidats.md) | Combiner Lucene et SQLite pour la recherche de candidats | 2026-07-19 | Accepted |
| [BE-0025](BE-0025-normaliser-les-signaux-et-calculer-un-score-compose-explicable.md) | Normaliser les signaux et calculer un score composé explicable | 2026-07-19 | Accepted |
| [BE-0026](BE-0026-construire-un-graphe-minimal-de-fichiers-a-partir-des-imports.md) | Construire un graphe minimal de fichiers à partir des imports résolus | 2026-07-19 | Accepted |
| [BE-0027](BE-0027-utiliser-un-estimateur-de-tokens-local-deterministe-et-remplacable.md) | Utiliser un estimateur de tokens local, déterministe et remplaçable | 2026-07-19 | Accepted |
| [BE-0028](BE-0028-construire-le-contexte-a-partir-de-fragments-de-code-prioritairement-symboliques.md) | Construire le contexte à partir de fragments de code prioritairement symboliques | 2026-07-19 | Accepted |
| [BE-0029](BE-0029-selectionner-le-contextbundle-par-un-algorithme-glouton-deterministe-sous-budget.md) | Sélectionner le ContextBundle par un algorithme glouton déterministe sous budget | 2026-07-19 | Accepted |
| [ARCH-0030](ARCH-0030-stabiliser-le-contrat-cli-avec-sorties-humaines-json-et-codes-de-sortie.md) | Stabiliser le contrat CLI avec sorties humaines, JSON et codes de sortie | 2026-07-19 | Accepted |
| [GEN-0031](GEN-0031-packager-la-cli-dans-un-jar-autonome.md) | Packager la CLI dans un JAR autonome | 2026-07-19 | Accepted |
| [BE-0032](BE-0032-preserver-et-normaliser-le-contexte-natif-des-projets.md) | Préserver et normaliser le contexte natif des projets | 2026-07-19 | Accepted |
| [SEC-0033](SEC-0033-separer-instructions-contextuelles-et-configuration-operationnelle-des-agents.md) | Séparer les instructions contextuelles de la configuration opérationnelle des agents | 2026-07-19 | Accepted |
| [BE-0034](BE-0034-adopter-la-divulgation-progressive-pour-les-agent-skills.md) | Adopter la divulgation progressive pour les Agent Skills | 2026-07-20 | Accepted |
| [BE-0035](BE-0035-integrer-le-contexte-git-local-comme-source-bornee-et-explicable.md) | Intégrer le contexte Git local comme source bornée et explicable | 2026-07-20 | Accepted |
| [BE-0036](BE-0036-importer-scip-comme-enrichissement-opportuniste.md) | Importer SCIP comme enrichissement opportuniste de l'intelligence de code | 2026-07-20 | Accepted |
| [BE-0037](BE-0037-integrer-jdt-language-server-comme-provider-java-profond-optionnel.md) | Intégrer JDT Language Server comme provider Java profond optionnel | 2026-07-20 | Accepted |
| [BE-0038](BE-0038-indexer-les-langages-additionnels-lexicalement-et-enrichir-la-structure-via-providers.md) | Indexer les langages additionnels lexicalement et enrichir la structure via des providers | 2026-07-20 | Accepted |
| [ARCH-0039](ARCH-0039-isoler-l-adaptateur-rest-quarkus-du-coeur-nexus.md) | Isoler l'adaptateur REST Quarkus du cœur NEXUS | 2026-07-20 | Accepted |
| [ARCH-0040](ARCH-0040-exposer-nexus-via-un-adaptateur-mcp-stdio-mince.md) | Exposer NEXUS via un adaptateur MCP STDIO mince | 2026-07-20 | Accepted |
| [ARCH-0041](ARCH-0041-reutiliser-le-serveur-mcp-pour-les-clients-assistants.md) | Réutiliser le serveur MCP pour les clients assistants | 2026-07-20 | Accepted |
| [ARCH-0042](ARCH-0042-consommer-ai-skills-registry-comme-snapshot-local-optionnel.md) | Consommer AI Skills Registry comme snapshot local optionnel | 2026-07-20 | Accepted |
| [BE-0043](BE-0043-federer-la-recherche-locale-par-projet-avant-un-moteur-externe.md) | Fédérer la recherche locale par projet avant d'introduire un moteur externe | 2026-07-21 | Accepted |
| [ARCH-0044](ARCH-0044-consommer-minos-via-un-contrat-json-local-versionne.md) | Consommer MINOS via un contrat JSON local versionné | 2026-07-24 | Accepted |
| [DATA-0045](DATA-0045-conserver-les-writers-operation-scoped-et-mettre-en-cache-les-readers-lucene.md) | Conserver les writers Lucene operation-scoped et mettre en cache les readers | 2026-09-02 | Accepted |
| [BE-0046](BE-0046-mettre-en-cache-le-contexte-git-dans-les-runtimes-longue-duree.md) | Mettre en cache le contexte Git dans les runtimes longue durée | 2026-09-02 | Accepted |
| [SEC-0047](SEC-0047-borner-le-support-filesystem-aux-filesystems-locaux-qualifies.md) | Borner le support filesystem aux filesystems locaux qualifiés | 2026-09-03 | Accepted |
| [SEC-0048](SEC-0048-distinguer-traversee-stricte-et-compatibilite-filesystem.md) | Distinguer traversée stricte et compatibilité filesystem | 2026-09-09 | Accepted |
| [DATA-0049](DATA-0049-identite-repository-par-composants-et-diagnostics-publics.md) | Identité repository par composants et diagnostics publics | 2026-09-09 | Accepted |
| [DATA-0050](DATA-0050-conserver-les-temporaires-sqlite-en-memoire.md) | Conserver les temporaires SQLite en mémoire | 2026-09-12 | Accepted |
| [DATA-0051](DATA-0051-borner-les-lots-d-ecriture-sqlite.md) | Borner les lots d'écriture SQLite | 2026-09-12 | Accepted |
| [DATA-0052](DATA-0052-garantir-la-coherence-des-lectures-et-des-index-derives.md) | Garantir la cohérence des lectures et des index dérivés | 2026-09-12 | Accepted |
| [BE-0053](BE-0053-borner-le-parcours-des-arbres-git-avant-collecte.md) | Borner le parcours des arbres Git avant collecte | 2026-09-12 | Accepted |
| [DATA-0054](DATA-0054-lectures-registre-federees-groupees.md) | Grouper les lectures du registre pour les opérations fédérées | 2026-09-12 | Accepted |
| [DATA-0055](DATA-0055-preserver-les-lecteurs-lors-des-reconstructions-semantiques.md) | Préserver les lecteurs lors des reconstructions sémantiques | 2026-09-13 | Accepted |
| [GEN-0057](GEN-0057-tenir-le-registre-d-adr-au-format-archgate.md) | Tenir le registre d'ADR au format Archgate, à l'emplacement arc42 | 2026-10-29 | Accepted |
| [BE-0058](BE-0058-exclure-les-worktrees-claude-code-du-scan-par-defaut.md) | Exclure les worktrees Claude Code du scan par défaut | 2026-10-30 | Accepted |

## Archive

ADR remplacés, conservés pour l'historique et non lus par Archgate.

| ADR | Décision | Statut | Remplacé par |
|---|---|---|---|
| [ADR-0000](archive/0000-adopter-les-adr-madr.md) | Adopter MADR comme format et définir la gouvernance des ADR | Superseded | [GEN-0057](GEN-0057-tenir-le-registre-d-adr-au-format-archgate.md) |
| [ADR-0056](archive/0056-regrouper-le-registre-des-adr-dans-la-documentation-d-architecture.md) | Regrouper le registre des ADR dans la documentation d'architecture | Superseded | [GEN-0057](GEN-0057-tenir-le-registre-d-adr-au-format-archgate.md) |

## Références

- Archgate : https://cli.archgate.dev/
- MADR (format d'origine du registre, ADR-0000) : https://adr.github.io/madr/
