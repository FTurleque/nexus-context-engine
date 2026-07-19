# NEXUS Context Engine

> Un moteur local, indépendant des modèles, dédié à l'intelligence de contexte pour les projets logiciels.

NEXUS construit un contexte minimal, pertinent, explicable et traçable pour les assistants et agents IA. NEXUS n'est pas un chatbot et ne réalise aucun routage vers un modèle particulier.

## Mission

À partir d'un repository logiciel local et d'une demande en langage naturel, NEXUS doit identifier et classer les fichiers et symboles les plus susceptibles d'être utiles, puis construire un `ContextBundle` respectant un budget de tokens configurable.

```text
Utilisateur / Agent / IDE
          │
          ▼
        Demande
          │
          ▼
        NEXUS
   indexation + recherche
   classement + explication
   budget + construction
          │
          ▼
     ContextBundle
          │
          ▼
      LLM / Agent IA
```

## Périmètre du MVP

Le périmètre du MVP est volontairement resserré :

- repositories locaux uniquement ;
- Java en premier langage pris en charge ;
- indexation structurelle basée sur un AST ;
- recherche lexicale et recherche orientée symboles ;
- classement déterministe et explicable ;
- budget de tokens configurable ;
- extraits de fichiers et de symboles plutôt qu'une injection systématique des fichiers complets ;
- fonctionnement local par défaut ;
- aucune dépendance obligatoire à un LLM ou à un fournisseur d'embeddings.

Sont explicitement reportés : les sources GitHub et GitLab, les intégrations IDE, le serveur MCP complet, les embeddings externes, les bases vectorielles, ainsi que les intégrations avec JARVIS, Alfred, Brainiac et AI Skills Registry.

## Orientation architecturale

Le repository démarre avec un seul module Maven organisé par responsabilités. Des modules Maven distincts ne seront créés que lorsqu'une séparation de runtime, de packaging ou de dépendances le justifiera réellement.

Les principaux points d'extension du cœur sont :

- `LanguageAnalyzer` ;
- `SearchStrategy` ;
- `ContextRanker` ;
- `TokenEstimator` ;
- `ContextBuilder`.

Documentation principale :

- [Architecture](docs/architecture.md) ;
- [Définition du MVP](docs/mvp.md) ;
- [Feuille de route](docs/roadmap.md) ;
- [Registre des décisions d'architecture — ADR](docs/adr/README.md).

Les ADR constituent l'historique de référence des décisions structurantes, de leurs alternatives et de leurs conséquences. `docs/architecture.md` décrit l'état architectural courant.

## Socle technique

- Java 21 comme niveau de compilation du MVP ;
- Maven ;
- JavaParser pour l'analyse structurelle Java embarquée ;
- SQLite comme source de vérité structurelle locale ;
- Apache Lucene comme index de recherche local reconstructible ;
- JGit pour la sémantique `.gitignore` / `.nexusignore` ;
- JUnit pour les tests automatisés.

Le cœur est volontairement développé en Java sans framework applicatif. Quarkus pourra être introduit ultérieurement au niveau de l'adaptateur API, sans coupler le moteur de contexte à un runtime particulier.

## État du projet

**Itération 0 — terminée et validée localement.**

Le socle architectural, les contrats initiaux, le premier analyseur AST Java et son test sont en place. Le build `mvn clean install` de cette itération a été validé localement.

**Itération 1 — en cours : indexation locale et fondations de recherche.**

L'implémentation initiale comprend désormais :

- le registre local des projets ;
- le répertoire `NEXUS_HOME` configurable ;
- SQLite et les migrations SQL versionnées ;
- le scanner des sources Java ;
- les règles `.gitignore`, `.nexusignore` et exclusions intégrées ;
- les empreintes SHA-256 ;
- l'indexation incrémentale des fichiers, symboles et relations ;
- Lucene comme index dérivé ;
- la propagation des suppressions ;
- une reconstruction complète de l'index de recherche ;
- une CLI minimale pour `project add`, `project list`, `index` et `inspect` ;
- des tests d'intégration pour le registre, le scanner et le pipeline SQLite/Lucene.

Le critère de sortie de l'Itération 1 ne sera considéré comme atteint qu'après validation du build et des nouveaux tests sur l'environnement local.

### Point d'entrée CLI actuel

Classe principale :

```text
io.github.fturleque.nexus.cli.NexusCli
```

Commandes actuellement exposées :

```text
project add <chemin> [nom]
project list
index <id-ou-nom> [--rebuild]
inspect <id-ou-nom>
```

Le packaging final en commande native `nexus` est prévu plus tard dans la phase de consolidation de la CLI.

## Sécurité par défaut

NEXUS adopte une approche locale par défaut. Aucun contenu du repository ne doit quitter la machine sans activation explicite d'une intégration externe. Un fichier `.nexusignore` complète les mécanismes de type `.gitignore` afin d'exclure notamment les secrets, les fichiers sensibles et les contenus générés.

## Licence

Le choix de la licence reste volontairement ouvert tant que le repository n'est pas rendu public.
