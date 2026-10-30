# Section 9 — Décisions d'architecture

L'historique complet des ADR se trouve dans [`docs/architecture/adr/`](../adr/README.md).
Cette section présente un index synthétique avec statut, date et relations de remplacement.

> **Règle** : aucun ADR accepté ne peut être supprimé ni réécrit rétroactivement.
> Toute révision substantielle crée un nouvel ADR qui le remplace (`Remplace` / `Remplacé par`) ;
> l'ADR remplacé part dans `archive/` (GEN-0057).

## Index des ADR

| ADR | Titre | Statut | Date |
|-----|-------|--------|------|
| [ARCH-0001](../adr/ARCH-0001-positionner-nexus-comme-moteur-intelligence-contexte.md) | Positionner NEXUS comme moteur d'intelligence de contexte indépendant des modèles | Accepted | 2026-07-19 |
| [GEN-0002](../adr/GEN-0002-compiler-le-coeur-en-java-21.md) | Compiler le cœur avec Java 21 comme niveau minimal | Accepted | 2026-07-19 |
| [ARCH-0003](../adr/ARCH-0003-conserver-un-coeur-java-sans-framework-applicatif.md) | Conserver un cœur Java sans framework applicatif obligatoire | Accepted | 2026-07-19 |
| [ARCH-0004](../adr/ARCH-0004-demarrer-avec-un-seul-module-maven.md) | Démarrer avec un seul module Maven et extraire uniquement sur besoin réel | Accepted | 2026-07-19 |
| [ARCH-0005](../adr/ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.md) | Adopter un fonctionnement local-first et des intégrations externes opt-in | Accepted | 2026-07-19 |
| [DATA-0006](../adr/DATA-0006-utiliser-sqlite-comme-source-de-verite-structurelle.md) | Utiliser SQLite comme source de vérité structurelle locale | Accepted | 2026-07-19 |
| [DATA-0007](../adr/DATA-0007-utiliser-lucene-comme-index-de-recherche-local.md) | Utiliser Apache Lucene comme index de recherche local | Accepted | 2026-07-19 |
| [BE-0008](../adr/BE-0008-utiliser-javaparser-comme-analyseur-java-embarque.md) | Utiliser JavaParser comme analyseur Java embarqué du MVP | Accepted | 2026-07-19 |
| [ARCH-0009](../adr/ARCH-0009-rendre-intelligence-code-extensible-via-providers.md) | Rendre l'intelligence de code extensible via des providers et index externes | Accepted | 2026-07-19 |
| [BE-0010](../adr/BE-0010-adopter-un-ranking-hybride-deterministe-et-explicable.md) | Adopter un ranking hybride, déterministe et explicable | Accepted | 2026-07-19 |
| [ARCH-0011](../adr/ARCH-0011-normaliser-les-sources-de-contexte.md) | Normaliser les sources de contexte derrière des providers | Accepted | 2026-07-19 |
| [ARCH-0012](../adr/ARCH-0012-reutiliser-les-standards-instructions-et-agent-skills.md) | Réutiliser les standards existants pour les instructions et les skills | Accepted | 2026-07-19 |
| [BE-0013](../adr/BE-0013-construire-un-contextbundle-sous-budget-de-tokens.md) | Construire un ContextBundle sous budget de tokens explicable | Accepted | 2026-07-19 |
| [BE-0014](../adr/BE-0014-rendre-la-recherche-semantique-optionnelle.md) | Rendre la recherche sémantique et les embeddings optionnels | Accepted | 2026-07-19 |
| [GEN-0015](../adr/GEN-0015-valider-le-mvp-par-la-cli-avant-les-integrations.md) | Valider le MVP par la CLI avant l'API, MCP et les intégrations IDE | Accepted | 2026-07-19 |
| [BE-0016](../adr/BE-0016-utiliser-le-sdk-java-officiel-pour-mcp.md) | Utiliser le SDK Java MCP officiel pour l'adaptateur MCP | Accepted | 2026-07-19 |
| [ARCH-0017](../adr/ARCH-0017-decoupler-nexus-des-outils-et-orchestrateurs-externes.md) | Découpler NEXUS des outils et orchestrateurs externes | Accepted | 2026-07-19 |
| [DATA-0018](../adr/DATA-0018-utiliser-des-uuid-pour-les-identites-metier-et-des-ids-locaux-pour-la-persistance.md) | Utiliser des UUID pour les identités métier et des identifiants locaux pour la persistance | Accepted | 2026-07-19 |
| [DATA-0019](../adr/DATA-0019-stocker-les-donnees-locales-dans-un-nexus-home-configurable.md) | Stocker les données locales dans un NEXUS_HOME configurable | Accepted | 2026-07-19 |
| [DATA-0020](../adr/DATA-0020-versionner-le-schema-sqlite-avec-des-migrations-sql-embarquees.md) | Versionner le schéma SQLite avec des migrations SQL embarquées | Accepted | 2026-07-19 |
| [BE-0021](../adr/BE-0021-reutiliser-jgit-pour-les-regles-gitignore-et-nexusignore.md) | Réutiliser JGit pour les règles `.gitignore` et `.nexusignore` | Accepted | 2026-07-19 |
| [DATA-0022](../adr/DATA-0022-traiter-lucene-comme-un-index-derive-reconstructible-de-sqlite.md) | Traiter Lucene comme un index dérivé et reconstructible de SQLite | Accepted | 2026-07-19 |
| [DATA-0023](../adr/DATA-0023-utiliser-sha-256-pour-detecter-les-changements-de-fichiers.md) | Utiliser SHA-256 pour détecter les changements de fichiers | Accepted | 2026-07-19 |
| [BE-0024](../adr/BE-0024-combiner-lucene-et-sqlite-pour-la-recherche-de-candidats.md) | Combiner Lucene et SQLite pour la recherche de candidats | Accepted | 2026-07-19 |
| [BE-0025](../adr/BE-0025-normaliser-les-signaux-et-calculer-un-score-compose-explicable.md) | Normaliser les signaux et calculer un score composé explicable | Accepted | 2026-07-19 |
| [BE-0026](../adr/BE-0026-construire-un-graphe-minimal-de-fichiers-a-partir-des-imports.md) | Construire un graphe minimal de fichiers à partir des imports résolus | Accepted | 2026-07-19 |
| [BE-0027](../adr/BE-0027-utiliser-un-estimateur-de-tokens-local-deterministe-et-remplacable.md) | Utiliser un estimateur de tokens local, déterministe et remplaçable | Accepted | 2026-07-19 |
| [BE-0028](../adr/BE-0028-construire-le-contexte-a-partir-de-fragments-de-code-prioritairement-symboliques.md) | Construire le contexte à partir de fragments de code prioritairement symboliques | Accepted | 2026-07-19 |
| [BE-0029](../adr/BE-0029-selectionner-le-contextbundle-par-un-algorithme-glouton-deterministe-sous-budget.md) | Sélectionner le ContextBundle par un algorithme glouton déterministe sous budget | Accepted | 2026-07-19 |
| [ARCH-0030](../adr/ARCH-0030-stabiliser-le-contrat-cli-avec-sorties-humaines-json-et-codes-de-sortie.md) | Stabiliser le contrat CLI avec sorties humaines, JSON et codes de sortie | Accepted | 2026-07-19 |
| [GEN-0031](../adr/GEN-0031-packager-la-cli-dans-un-jar-autonome.md) | Packager la CLI dans un JAR autonome | Accepted | 2026-07-19 |
| [BE-0032](../adr/BE-0032-preserver-et-normaliser-le-contexte-natif-des-projets.md) | Préserver et normaliser le contexte natif des projets | Accepted | 2026-07-19 |
| [SEC-0033](../adr/SEC-0033-separer-instructions-contextuelles-et-configuration-operationnelle-des-agents.md) | Séparer les instructions contextuelles de la configuration opérationnelle des agents | Accepted | 2026-07-19 |
| [BE-0034](../adr/BE-0034-adopter-la-divulgation-progressive-pour-les-agent-skills.md) | Adopter la divulgation progressive pour les Agent Skills | Accepted | 2026-07-20 |
| [BE-0035](../adr/BE-0035-integrer-le-contexte-git-local-comme-source-bornee-et-explicable.md) | Intégrer le contexte Git local comme source bornée et explicable | Accepted | 2026-07-20 |
| [BE-0036](../adr/BE-0036-importer-scip-comme-enrichissement-opportuniste.md) | Importer SCIP comme enrichissement opportuniste de l'intelligence de code | Accepted | 2026-07-20 |
| [BE-0037](../adr/BE-0037-integrer-jdt-language-server-comme-provider-java-profond-optionnel.md) | Intégrer JDT Language Server comme provider Java profond optionnel | Accepted | 2026-07-20 |
| [BE-0038](../adr/BE-0038-indexer-les-langages-additionnels-lexicalement-et-enrichir-la-structure-via-providers.md) | Indexer les langages additionnels lexicalement et enrichir la structure via des providers | Accepted | 2026-07-20 |
| [ARCH-0039](../adr/ARCH-0039-isoler-l-adaptateur-rest-quarkus-du-coeur-nexus.md) | Isoler l'adaptateur REST Quarkus du cœur NEXUS | Accepted | 2026-07-20 |
| [ARCH-0040](../adr/ARCH-0040-exposer-nexus-via-un-adaptateur-mcp-stdio-mince.md) | Exposer NEXUS via un adaptateur MCP STDIO mince | Accepted | 2026-07-20 |
| [ARCH-0041](../adr/ARCH-0041-reutiliser-le-serveur-mcp-pour-les-clients-assistants.md) | Réutiliser le serveur MCP pour les clients assistants | Accepted | 2026-07-20 |
| [ARCH-0042](../adr/ARCH-0042-consommer-ai-skills-registry-comme-snapshot-local-optionnel.md) | Consommer AI Skills Registry comme snapshot local optionnel | Accepted | 2026-07-20 |
| [BE-0043](../adr/BE-0043-federer-la-recherche-locale-par-projet-avant-un-moteur-externe.md) | Fédérer la recherche locale par projet avant d'introduire un moteur externe | Accepted | 2026-07-21 |
| [ARCH-0044](../adr/ARCH-0044-consommer-minos-via-un-contrat-json-local-versionne.md) | Consommer MINOS via un contrat JSON local versionné | Accepted | 2026-07-24 |
| [DATA-0045](../adr/DATA-0045-conserver-les-writers-operation-scoped-et-mettre-en-cache-les-readers-lucene.md) | Conserver les writers Lucene operation-scoped et mettre en cache les readers | Accepted | 2026-09-02 |
| [BE-0046](../adr/BE-0046-mettre-en-cache-le-contexte-git-dans-les-runtimes-longue-duree.md) | Mettre en cache le contexte Git dans les runtimes longue durée | Accepted | 2026-09-02 |
| [SEC-0047](../adr/SEC-0047-borner-le-support-filesystem-aux-filesystems-locaux-qualifies.md) | Borner le support filesystem aux filesystems locaux qualifiés | Accepted | 2026-09-03 |
| [SEC-0048](../adr/SEC-0048-distinguer-traversee-stricte-et-compatibilite-filesystem.md) | Distinguer traversée stricte et compatibilité filesystem | Accepted | 2026-09-09 |
| [DATA-0049](../adr/DATA-0049-identite-repository-par-composants-et-diagnostics-publics.md) | Identité repository par composants et diagnostics publics | Accepted | 2026-09-09 |
| [DATA-0050](../adr/DATA-0050-conserver-les-temporaires-sqlite-en-memoire.md) | Conserver les temporaires SQLite en mémoire | Accepted | 2026-09-12 |
| [DATA-0051](../adr/DATA-0051-borner-les-lots-d-ecriture-sqlite.md) | Borner les lots d'écriture SQLite | Accepted | 2026-09-12 |
| [DATA-0052](../adr/DATA-0052-garantir-la-coherence-des-lectures-et-des-index-derives.md) | Garantir la cohérence des lectures et des index dérivés | Accepted | 2026-09-12 |
| [BE-0053](../adr/BE-0053-borner-le-parcours-des-arbres-git-avant-collecte.md) | Borner le parcours des arbres Git avant collecte | Accepted | 2026-09-12 |
| [DATA-0054](../adr/DATA-0054-lectures-registre-federees-groupees.md) | Grouper les lectures du registre pour les opérations fédérées | Accepted | 2026-09-12 |
| [DATA-0055](../adr/DATA-0055-preserver-les-lecteurs-lors-des-reconstructions-semantiques.md) | Préserver les lecteurs lors des reconstructions sémantiques | Accepted | 2026-09-13 |
| [GEN-0057](../adr/GEN-0057-tenir-le-registre-d-adr-au-format-archgate.md) | Tenir le registre d'ADR au format Archgate, à l'emplacement arc42 | Accepted | 2026-10-29 |
| [BE-0058](../adr/BE-0058-exclure-les-worktrees-claude-code-du-scan-par-defaut.md) | Exclure les worktrees Claude Code du scan par défaut | Accepted | 2026-10-30 |
| [ADR-0000](../adr/archive/0000-adopter-les-adr-madr.md) | Adopter MADR comme format et définir la gouvernance des ADR | Superseded (GEN-0057) | 2026-07-19 |
| [ADR-0056](../adr/archive/0056-regrouper-le-registre-des-adr-dans-la-documentation-d-architecture.md) | Regrouper le registre des ADR dans la documentation d'architecture | Superseded (GEN-0057) | 2026-10-29 |

## Regroupements thématiques

### Fondations

ARCH-0001, GEN-0002, ARCH-0003, ARCH-0005 — positionnement, technologie de base et
principe local-first.

### Persistance et indexation

DATA-0006, DATA-0007, BE-0008, DATA-0020, BE-0021, DATA-0022, DATA-0023, BE-0058 —
SQLite, Lucene, JavaParser, migrations, JGit, SHA-256, exclusion des worktrees
Claude Code.

### Recherche et ranking

BE-0010, BE-0014, BE-0024, BE-0025, BE-0026 — ranking hybride, sémantique
optionnel, graphe, score composé.

### Contexte et budget

ARCH-0011, ARCH-0012, BE-0013, BE-0027, BE-0028, BE-0029 — providers normalisés,
standards natifs, budget, estimateur de tokens, sélection gloutonne.

### Sources contextuelles

BE-0032, SEC-0033, BE-0034, BE-0035 — instructions natives, skills, Git.

### Providers intelligence

ARCH-0009, BE-0036, BE-0037, BE-0038, ARCH-0044 — SCIP, JDT LS, langages additionnels,
MINOS.

### Surfaces d'exposition

GEN-0015, BE-0016, ARCH-0017, ARCH-0030, GEN-0031, ARCH-0039, ARCH-0040, ARCH-0041 —
CLI, REST, MCP, découplage, packaging.

### Écosystème et fédération

ARCH-0042, BE-0043 — AI Skills Registry, fédération locale.

### Robustesse, cohérence et confinement

DATA-0045, BE-0046, SEC-0047, SEC-0048, DATA-0049, DATA-0050, DATA-0051, DATA-0052,
BE-0053, DATA-0054, DATA-0055 — cycle de vie Lucene, cache Git, filesystems et
traversée, identité repository, lots et temporaires SQLite, cohérence des index
dérivés, lectures fédérées, reconstructions sémantiques.

### Gouvernance

ARCH-0004, DATA-0018, DATA-0019, GEN-0057 — module unique, UUID, NEXUS_HOME,
registre des ADR au format Archgate (remplace ADR-0000 et ADR-0056, archivés).
