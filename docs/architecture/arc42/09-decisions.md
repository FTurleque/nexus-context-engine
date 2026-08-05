# Section 9 — Décisions d'architecture

L'historique complet des ADR se trouve dans [`docs/adr/`](../../adr/README.md).
Cette section présente un index synthétique avec statut, date et relations de remplacement.

> **Règle** : aucun ADR accepté ne peut être supprimé ni réécrit rétroactivement.
> Toute révision substantielle crée un nouvel ADR marqué `supersedes: ADR-XXXX`.

## Index des ADR

| ADR | Titre | Statut | Date |
|-----|-------|--------|------|
| [ADR-0000](../../adr/0000-adopter-les-adr-madr.md) | Adopter MADR comme format et gouvernance des ADR | Accepté | 2026-07-19 |
| [ADR-0001](../../adr/0001-positionner-nexus-comme-moteur-intelligence-contexte.md) | Positionner NEXUS comme moteur d'intelligence de contexte indépendant des modèles | Accepté | 2026-07-19 |
| [ADR-0002](../../adr/0002-compiler-le-coeur-en-java-21.md) | Compiler le cœur avec Java 21 comme niveau minimal | Accepté | 2026-07-19 |
| [ADR-0003](../../adr/0003-conserver-un-coeur-java-sans-framework-applicatif.md) | Conserver un cœur Java sans framework applicatif obligatoire | Accepté | 2026-07-19 |
| [ADR-0004](../../adr/0004-demarrer-avec-un-seul-module-maven.md) | Démarrer avec un seul module Maven et extraire uniquement sur besoin réel | Accepté | 2026-07-19 |
| [ADR-0005](../../adr/0005-adopter-un-fonctionnement-local-first-et-opt-in.md) | Adopter un fonctionnement local-first et des intégrations externes opt-in | Accepté | 2026-07-19 |
| [ADR-0006](../../adr/0006-utiliser-sqlite-comme-source-de-verite-structurelle.md) | Utiliser SQLite comme source de vérité structurelle locale | Accepté | 2026-07-19 |
| [ADR-0007](../../adr/0007-utiliser-lucene-comme-index-de-recherche-local.md) | Utiliser Apache Lucene comme index de recherche local | Accepté | 2026-07-19 |
| [ADR-0008](../../adr/0008-utiliser-javaparser-comme-analyseur-java-embarque.md) | Utiliser JavaParser comme analyseur Java embarqué du MVP | Accepté | 2026-07-19 |
| [ADR-0009](../../adr/0009-rendre-intelligence-code-extensible-via-providers.md) | Rendre l'intelligence de code extensible via des providers et index externes | Accepté | 2026-07-19 |
| [ADR-0010](../../adr/0010-adopter-un-ranking-hybride-deterministe-et-explicable.md) | Adopter un ranking hybride, déterministe et explicable | Accepté | 2026-07-19 |
| [ADR-0011](../../adr/0011-normaliser-les-sources-de-contexte.md) | Normaliser les sources de contexte derrière des providers | Accepté | 2026-07-19 |
| [ADR-0012](../../adr/0012-reutiliser-les-standards-instructions-et-agent-skills.md) | Réutiliser les standards existants pour instructions et skills | Accepté | 2026-07-19 |
| [ADR-0013](../../adr/0013-construire-un-contextbundle-sous-budget-de-tokens.md) | Construire un ContextBundle sous budget de tokens explicable | Accepté | 2026-07-19 |
| [ADR-0014](../../adr/0014-rendre-la-recherche-semantique-optionnelle.md) | Rendre la recherche sémantique et les embeddings optionnels | Accepté | 2026-07-19 |
| [ADR-0015](../../adr/0015-valider-le-mvp-par-la-cli-avant-les-integrations.md) | Valider le moteur par la CLI avant API, MCP et IDE | Accepté | 2026-07-19 |
| [ADR-0016](../../adr/0016-utiliser-le-sdk-java-officiel-pour-mcp.md) | Utiliser le SDK Java officiel pour l'adaptateur MCP | Accepté | 2026-07-19 |
| [ADR-0017](../../adr/0017-decoupler-nexus-des-outils-et-orchestrateurs-externes.md) | Découpler NEXUS de Copilot, Claude, AI Skills Registry et des orchestrateurs | Accepté | 2026-07-19 |
| [ADR-0018](../../adr/0018-utiliser-des-uuid-pour-les-identites-metier-et-des-ids-locaux-pour-la-persistance.md) | Utiliser des UUID pour les identités métier et des identifiants locaux pour la persistance | Accepté | 2026-07-19 |
| [ADR-0019](../../adr/0019-stocker-les-donnees-locales-dans-un-nexus-home-configurable.md) | Stocker les données locales dans un NEXUS_HOME configurable | Accepté | 2026-07-19 |
| [ADR-0020](../../adr/0020-versionner-le-schema-sqlite-avec-des-migrations-sql-embarquees.md) | Versionner le schéma SQLite avec des migrations SQL embarquées | Accepté | 2026-07-19 |
| [ADR-0021](../../adr/0021-reutiliser-jgit-pour-les-regles-gitignore-et-nexusignore.md) | Réutiliser JGit pour les règles `.gitignore` / `.nexusignore` | Accepté | 2026-07-19 |
| [ADR-0022](../../adr/0022-traiter-lucene-comme-un-index-derive-reconstructible-de-sqlite.md) | Traiter Lucene comme un index dérivé et reconstructible de SQLite | Accepté | 2026-07-19 |
| [ADR-0023](../../adr/0023-utiliser-sha-256-pour-detecter-les-changements-de-fichiers.md) | Utiliser SHA-256 pour détecter les changements de fichiers | Accepté | 2026-07-19 |
| [ADR-0024](../../adr/0024-combiner-lucene-et-sqlite-pour-la-recherche-de-candidats.md) | Combiner Lucene et SQLite pour la recherche de candidats | Accepté | 2026-07-19 |
| [ADR-0025](../../adr/0025-normaliser-les-signaux-et-calculer-un-score-compose-explicable.md) | Normaliser les signaux et calculer un score composé explicable | Accepté | 2026-07-19 |
| [ADR-0026](../../adr/0026-construire-un-graphe-minimal-de-fichiers-a-partir-des-imports.md) | Construire un graphe minimal de fichiers à partir des imports résolus | Accepté | 2026-07-19 |
| [ADR-0027](../../adr/0027-utiliser-un-estimateur-de-tokens-local-deterministe-et-remplacable.md) | Utiliser un estimateur de tokens local, déterministe et remplaçable | Accepté | 2026-07-19 |
| [ADR-0028](../../adr/0028-construire-le-contexte-a-partir-de-fragments-de-code-prioritairement-symboliques.md) | Construire le contexte à partir de fragments de code prioritairement symboliques | Accepté | 2026-07-19 |
| [ADR-0029](../../adr/0029-selectionner-le-contextbundle-par-un-algorithme-glouton-deterministe-sous-budget.md) | Sélectionner le ContextBundle par un algorithme glouton déterministe sous budget | Accepté | 2026-07-19 |
| [ADR-0030](../../adr/0030-stabiliser-le-contrat-cli-avec-sorties-humaines-json-et-codes-de-sortie.md) | Stabiliser le contrat CLI avec sorties humaines, JSON et codes de sortie | Accepté | 2026-07-19 |
| [ADR-0031](../../adr/0031-packager-la-cli-dans-un-jar-autonome.md) | Packager la CLI dans un JAR autonome tout en conservant le JAR bibliothèque | Accepté | 2026-07-19 |
| [ADR-0032](../../adr/0032-preserver-et-normaliser-le-contexte-natif-des-projets.md) | Préserver et normaliser le contexte natif déjà configuré dans les projets | Accepté | 2026-07-19 |
| [ADR-0033](../../adr/0033-separer-instructions-contextuelles-et-configuration-operationnelle-des-agents.md) | Séparer les instructions contextuelles de la configuration opérationnelle des agents | Accepté | 2026-07-19 |
| [ADR-0034](../../adr/0034-adopter-la-divulgation-progressive-pour-les-agent-skills.md) | Adopter la divulgation progressive pour les Agent Skills | Accepté | 2026-07-19 |
| [ADR-0035](../../adr/0035-integrer-le-contexte-git-local-comme-source-bornee-et-explicable.md) | Intégrer le contexte Git local comme source bornée et explicable | Accepté | 2026-07-19 |
| [ADR-0036](../../adr/0036-importer-scip-comme-enrichissement-opportuniste.md) | Importer SCIP comme enrichissement opportuniste de l'intelligence de code | Accepté | 2026-07-19 |
| [ADR-0037](../../adr/0037-integrer-jdt-language-server-comme-provider-java-profond-optionnel.md) | Intégrer JDT Language Server comme provider Java profond optionnel | Accepté | 2026-07-19 |
| [ADR-0038](../../adr/0038-indexer-les-langages-additionnels-lexicalement-et-enrichir-la-structure-via-providers.md) | Indexer les langages additionnels lexicalement et enrichir la structure via des providers | Accepté | 2026-07-19 |
| [ADR-0039](../../adr/0039-isoler-l-adaptateur-rest-quarkus-du-coeur-nexus.md) | Isoler l'adaptateur REST Quarkus du cœur NEXUS | Accepté | 2026-07-19 |
| [ADR-0040](../../adr/0040-exposer-nexus-via-un-adaptateur-mcp-stdio-mince.md) | Exposer NEXUS via un adaptateur MCP STDIO mince | Accepté | 2026-07-19 |
| [ADR-0041](../../adr/0041-reutiliser-le-serveur-mcp-pour-les-clients-assistants.md) | Réutiliser le serveur MCP pour les clients assistants | Accepté | 2026-07-19 |
| [ADR-0042](../../adr/0042-consommer-ai-skills-registry-comme-snapshot-local-optionnel.md) | Consommer AI Skills Registry comme snapshot local optionnel | Accepté | 2026-07-19 |
| [ADR-0043](../../adr/0043-federer-la-recherche-locale-par-projet-avant-un-moteur-externe.md) | Fédérer la recherche locale par projet avant d'introduire un moteur externe | Accepté | 2026-07-19 |
| [ADR-0044](../../adr/0044-consommer-minos-via-un-contrat-json-local-versionne.md) | Consommer MINOS via un contrat JSON local versionné | Accepté | 2026-07-24 |

## Regroupements thématiques

### Fondations

ADR-0001, ADR-0002, ADR-0003, ADR-0005 — positionnement, technologie de base et
principe local-first.

### Persistance et indexation

ADR-0006, ADR-0007, ADR-0008, ADR-0020, ADR-0021, ADR-0022, ADR-0023 — SQLite,
Lucene, JavaParser, migrations, JGit, SHA-256.

### Recherche et ranking

ADR-0010, ADR-0014, ADR-0024, ADR-0025, ADR-0026 — ranking hybride, sémantique
optionnel, graphe, score composé.

### Contexte et budget

ADR-0011, ADR-0012, ADR-0013, ADR-0027, ADR-0028, ADR-0029 — providers normalisés,
standards natifs, budget, estimateur de tokens, sélection gloutonne.

### Sources contextuelles

ADR-0032, ADR-0033, ADR-0034, ADR-0035 — instructions natives, skills, Git.

### Providers intelligence

ADR-0009, ADR-0036, ADR-0037, ADR-0038, ADR-0044 — SCIP, JDT LS, langages additionnels,
MINOS.

### Surfaces d'exposition

ADR-0015, ADR-0016, ADR-0017, ADR-0030, ADR-0031, ADR-0039, ADR-0040, ADR-0041 —
CLI, REST, MCP, découplage, packaging.

### Écosystème et fédération

ADR-0042, ADR-0043 — AI Skills Registry, fédération locale.

### Gouvernance

ADR-0000, ADR-0004, ADR-0018, ADR-0019 — ADR MADR, module unique, UUID, NEXUS_HOME.
