# Registre des décisions d'architecture de NEXUS

Ce répertoire contient les **Architecture Decision Records (ADR)** de NEXUS.

Les ADR constituent l'historique durable des décisions architecturales significatives du projet. Ils complètent `docs/architecture.md` :

- `docs/architecture.md` décrit l'architecture actuelle de manière synthétique ;
- les ADR expliquent **pourquoi** cette architecture existe, quelles alternatives ont été étudiées et quelles conséquences ont été acceptées.

## Convention retenue

NEXUS utilise un format inspiré de **MADR 4 (Markdown Architectural Decision Records)**, adapté en français et enrichi pour les besoins du projet.

Chaque ADR doit traiter une décision architecturale cohérente et contenir au minimum :

1. le contexte et le problème ;
2. les facteurs de décision ;
3. les options envisagées ;
4. la décision retenue ;
5. les conséquences ;
6. le moyen de confirmer le respect de la décision ;
7. l'analyse détaillée des options ;
8. les conditions éventuelles de réexamen.

Les alternatives rejetées sont documentées **dans l'ADR de la décision concernée**. NEXUS n'utilise pas d'ADR transversal regroupant toutes les options rejetées.

## Statuts

Les statuts utilisés sont :

- `proposed` : décision proposée, encore ouverte à discussion ;
- `accepted` : décision acceptée et applicable ;
- `deprecated` : décision toujours présente dans l'historique mais déconseillée ;
- `superseded` : décision remplacée par un ADR plus récent ;
- `rejected` : proposition étudiée mais explicitement rejetée avant adoption.

Une décision `accepted` ne doit pas être réécrite ultérieurement pour modifier rétroactivement sa justification. Lorsqu'une décision change de manière substantielle, un nouvel ADR est créé et l'ancien passe au statut `superseded`, avec un lien vers le nouvel ADR.

Les corrections éditoriales ou factuelles mineures restent autorisées tant qu'elles ne changent pas le sens historique de la décision.

## Numérotation et nommage

Les ADR sont numérotés séquentiellement sur quatre chiffres :

```text
0000-adopter-les-adr-madr.md
0001-positionner-nexus-comme-moteur-intelligence-contexte.md
...
```

Le numéro est un identifiant stable. Le nom de fichier utilise des minuscules, des tirets et aucun accent.

## Index

| ADR | Décision | Statut |
|---|---|---|
| [ADR-0000](0000-adopter-les-adr-madr.md) | Adopter MADR comme format et définir la gouvernance des ADR | Accepté |
| [ADR-0001](0001-positionner-nexus-comme-moteur-intelligence-contexte.md) | Positionner NEXUS comme moteur d'intelligence de contexte indépendant des modèles | Accepté |
| [ADR-0002](0002-compiler-le-coeur-en-java-21.md) | Compiler le cœur avec Java 21 comme niveau minimal | Accepté |
| [ADR-0003](0003-conserver-un-coeur-java-sans-framework-applicatif.md) | Conserver un cœur Java sans framework applicatif obligatoire | Accepté |
| [ADR-0004](0004-demarrer-avec-un-seul-module-maven.md) | Démarrer avec un seul module Maven et extraire uniquement sur besoin réel | Accepté |
| [ADR-0005](0005-adopter-un-fonctionnement-local-first-et-opt-in.md) | Adopter un fonctionnement local-first et des intégrations externes opt-in | Accepté |
| [ADR-0006](0006-utiliser-sqlite-comme-source-de-verite-structurelle.md) | Utiliser SQLite comme source de vérité structurelle locale | Accepté |
| [ADR-0007](0007-utiliser-lucene-comme-index-de-recherche-local.md) | Utiliser Apache Lucene comme index de recherche local | Accepté |
| [ADR-0008](0008-utiliser-javaparser-comme-analyseur-java-embarque.md) | Utiliser JavaParser comme analyseur Java embarqué du MVP | Accepté |
| [ADR-0009](0009-rendre-intelligence-code-extensible-via-providers.md) | Rendre l'intelligence de code extensible via des providers et index externes | Accepté |
| [ADR-0010](0010-adopter-un-ranking-hybride-deterministe-et-explicable.md) | Adopter un ranking hybride, déterministe et explicable | Accepté |
| [ADR-0011](0011-normaliser-les-sources-de-contexte.md) | Normaliser les sources de contexte derrière des providers | Accepté |
| [ADR-0012](0012-reutiliser-les-standards-instructions-et-agent-skills.md) | Réutiliser les standards existants pour instructions et skills | Accepté |
| [ADR-0013](0013-construire-un-contextbundle-sous-budget-de-tokens.md) | Construire un ContextBundle sous budget de tokens explicable | Accepté |
| [ADR-0014](0014-rendre-la-recherche-semantique-optionnelle.md) | Rendre la recherche sémantique et les embeddings optionnels | Accepté |
| [ADR-0015](0015-valider-le-mvp-par-la-cli-avant-les-integrations.md) | Valider le moteur par la CLI avant API, MCP et IDE | Accepté |
| [ADR-0016](0016-utiliser-le-sdk-java-officiel-pour-mcp.md) | Utiliser le SDK Java MCP officiel pour l'adaptateur MCP | Accepté |
| [ADR-0017](0017-decoupler-nexus-des-outils-et-orchestrateurs-externes.md) | Découpler NEXUS de Copilot, Claude, AI Skills Registry et des orchestrateurs | Accepté |
| [ADR-0018](0018-utiliser-des-uuid-pour-les-identites-metier-et-des-ids-locaux-pour-la-persistance.md) | Utiliser des UUID pour les identités métier et des identifiants locaux pour la persistance | Accepté |
| [ADR-0019](0019-stocker-les-donnees-locales-dans-un-nexus-home-configurable.md) | Stocker les données locales dans un NEXUS_HOME configurable | Accepté |
| [ADR-0020](0020-versionner-le-schema-sqlite-avec-des-migrations-sql-embarquees.md) | Versionner le schéma SQLite avec des migrations SQL embarquées | Accepté |
| [ADR-0021](0021-reutiliser-jgit-pour-les-regles-gitignore-et-nexusignore.md) | Réutiliser JGit pour les règles `.gitignore` et `.nexusignore` | Accepté |
| [ADR-0022](0022-traiter-lucene-comme-un-index-derive-reconstructible-de-sqlite.md) | Traiter Lucene comme un index dérivé et reconstructible de SQLite | Accepté |
| [ADR-0023](0023-utiliser-sha-256-pour-detecter-les-changements-de-fichiers.md) | Utiliser SHA-256 pour détecter les changements de fichiers | Accepté |
| [ADR-0024](0024-combiner-lucene-et-sqlite-pour-la-recherche-de-candidats.md) | Combiner Lucene et SQLite pour la recherche de candidats | Accepté |
| [ADR-0025](0025-normaliser-les-signaux-et-calculer-un-score-compose-explicable.md) | Normaliser les signaux et calculer un score composé explicable | Accepté |
| [ADR-0026](0026-construire-un-graphe-minimal-de-fichiers-a-partir-des-imports.md) | Construire un graphe minimal de fichiers à partir des imports résolus | Accepté |
| [ADR-0027](0027-utiliser-un-estimateur-de-tokens-local-deterministe-et-remplacable.md) | Utiliser un estimateur de tokens local, déterministe et remplaçable | Accepté |
| [ADR-0028](0028-construire-le-contexte-a-partir-de-fragments-de-code-prioritairement-symboliques.md) | Construire le contexte à partir de fragments de code prioritairement symboliques | Accepté |
| [ADR-0029](0029-selectionner-le-contextbundle-par-un-algorithme-glouton-deterministe-sous-budget.md) | Sélectionner le ContextBundle par un algorithme glouton déterministe sous budget | Accepté |

## Modèle

Le fichier [`template.md`](template.md) sert de base aux futurs ADR.

## Références

- MADR : https://adr.github.io/madr/
- Modèles ADR : https://adr.github.io/adr-templates/
- Répertoire MADR : https://github.com/adr/madr
