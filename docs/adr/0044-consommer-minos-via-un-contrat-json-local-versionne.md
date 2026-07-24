# ADR-0044 — Consommer MINOS via un contrat JSON local versionné

- Statut : accepted
- Date : 2026-07-24
- Décideurs : projet NEXUS
- Lié à : MINOS M13 / NEXUS issue #11

## Contexte et problème

MINOS est le moteur de Code Intelligence de l’écosystème : symboles, relations, provenance, preuves, architecture et impact. NEXUS est le moteur de Context Intelligence : découverte des sources, recherche, ranking, sélection et construction d’un contexte sous budget.

NEXUS doit pouvoir exploiter la connaissance MINOS sans la réimplémenter et sans déplacer dans MINOS la responsabilité du contexte final.

Une contrainte technique interdit un couplage binaire simple : NEXUS conserve Java 21 alors que MINOS est compilé et validé avec Java 24.

## Facteurs de décision

- préserver Java 21 pour NEXUS ;
- préserver Java 24 pour MINOS ;
- ne pas introduire de dépendance Maven croisée entre deux dépôts privés ;
- garder l’intégration locale-first et opt-in ;
- réutiliser `CodeIndexImporter` et le pipeline d’indexation NEXUS ;
- conserver `SearchService`, le ranking et `DefaultContextBuilder` comme responsables du contexte ;
- permettre une évolution versionnée du contrat MINOS ;
- ne jamais construire une commande système à partir d’un chemin projet ou d’une configuration d’intégration ;
- échouer explicitement en cas de contrat incompatible ou de mauvais projet.

## Options envisagées

### Dépendance Java/Maven directe vers MINOS

Rejetée. Le bytecode Java 24 n’est pas une dépendance acceptable pour un cœur NEXUS Java 21 et les modèles deviendraient couplés.

### Appeler le serveur MCP MINOS

Non retenue pour M13. MCP est adapté aux consommateurs assistants mais introduirait ici une couche protocolaire plus large que nécessaire pour un import local déterministe de snapshot.

### Serveur HTTP MINOS

Rejeté. MINOS n’a pas besoin d’un framework serveur pour cette intégration et NEXUS ne doit pas dépendre d’un service réseau local pour son indexation de base.

### Contrat JSON local via processus MINOS

Retenu.

MINOS fournit un bridge read-only qui écrit un document JSON versionné sur stdout. NEXUS lance une commande système entièrement fixe et envoie la racine projet sur stdin.

## Décision

NEXUS adopte `MinosCodeIndexImporter` comme importer optionnel derrière `CodeIndexImporter`.

L’intégration est activée uniquement par la présence du shaded JAR MINOS à l’emplacement conventionnel :

```text
<NEXUS_HOME>/integrations/minos/minos-code-intelligence-all.jar
```

Le home MINOS dédié est :

```text
<NEXUS_HOME>/integrations/minos/home
```

Aucune variable `NEXUS_MINOS_*` n’est introduite.

La commande enfant est constante :

```text
java -cp integrations/minos/minos-code-intelligence-all.jar \
  com.minos.integration.nexus.NexusExportBridgeMain
```

Le répertoire de travail est `NEXUS_HOME`. `MINOS_HOME` reçoit dans l’environnement enfant la valeur relative constante `integrations/minos/home`. La racine du projet est transmise par stdin et ne figure jamais dans les arguments du processus.

Le `java` visible dans le `PATH` du processus NEXUS doit être Java 24 lorsque l’intégration MINOS est installée. La baseline de compilation NEXUS reste Java 21.

Sans JAR conventionnel, l’importer est désactivé et retourne un snapshot vide pour permettre la purge de données MINOS anciennes.

Lorsque MINOS est actif, il est importé avant l’import SCIP direct afin que la provenance `minos` soit conservée sur les faits identiques ; la persistance NEXUS déduplique déjà ces faits.

Seuls les symboles et relations résolus dont la sémantique existe dans le modèle NEXUS sont convertis. Les kinds non représentables sont ignorés, jamais reclassés arbitrairement.

Le document exporté est borné à 128 MiB et le processus à 20 secondes dans la configuration production.

## Conséquences positives

- aucun couplage binaire Java 21/24 ;
- aucun type `com.minos` dans NEXUS ;
- aucun réseau requis ;
- activation explicite par installation locale ;
- aucune donnée projet ou configuration d’intégration injectée dans la ligne de commande système ;
- `CodeIndexImporter`, SQLite, recherche, ranking et ContextBuilder existants sont réutilisés ;
- NEXUS reste pleinement utilisable sans MINOS ;
- le contrat peut évoluer par version sans modifier le modèle NEXUS immédiatement.

## Conséquences négatives acceptées

- un processus Java 24 supplémentaire est lancé lors de l’import ;
- le JAR MINOS doit être copié dans l’arborescence conventionnelle de NEXUS ;
- l’environnement du processus NEXUS doit permettre à la commande constante `java` de résoudre Java 24 pour l’enfant ;
- le mapping NEXUS ne représente pas encore toute la richesse du contrat MINOS ;
- un replay inter-dépôt est requis pour qualifier les évolutions de contrat.

## Confirmation

La décision est considérée respectée lorsque :

- les tests NEXUS prouvent l’absence d’activation sans JAR conventionnel ;
- un bridge de test est lancé réellement avec la même commande fixe ;
- la racine projet est transportée par stdin ;
- les versions/root invalides sont refusés ;
- les chemins absolus, remontants ou canoniquement extérieurs à la racine ne sont pas ingérés ;
- un replay avec le vrai JAR MINOS importe `GreetingPort` avec provenance `minos` ;
- une recherche NEXUS retrouve ce symbole ;
- les tests existants de ranking et de contexte restent inchangés et verts.

## Conditions de réexamen

Réexaminer cette décision si :

- NEXUS relève officiellement son niveau Java au niveau de MINOS ;
- MINOS expose un contrat de service local plus approprié et mesuré ;
- le coût de lancement processus devient significatif sur des mesures réelles ;
- NEXUS doit consommer des vues MINOS dynamiques qui ne peuvent plus être représentées comme snapshot d’indexation.
