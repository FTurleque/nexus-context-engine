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

**Itération 1 — terminée et validée localement : indexation locale et fondations de recherche.**

L'itération comprend :

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

Validation locale du 19 juillet 2026 :

- `mvn clean install` : succès ;
- compilation de 43 fichiers source en Java 21 : succès ;
- compilation de 4 fichiers de test : succès ;
- tests : 6 exécutés, 0 échec, 0 erreur, 0 ignoré ;
- test de non-régression JavaParser sur les text blocks Java 21 : succès ;
- génération du JAR `nexus-context-engine-0.1.0-SNAPSHOT.jar` : succès ;
- installation dans le dépôt Maven local : succès.

Validation self-smoke réelle sur le repository NEXUS lui-même :

- enregistrement du repository : succès ;
- première indexation : 47 fichiers scannés et 47 fichiers modifiés ;
- index produit : 47 fichiers, 161 symboles et 287 relations ;
- première indexation avec reconstruction complète : 741 ms sur la machine de validation ;
- seconde indexation incrémentale : 0 fichier modifié et 0 fichier supprimé ;
- seconde indexation : 282 ms sur la machine de validation ;
- état final du projet : `READY` ;
- résultat du script : `SELF-SMOKE SUCCESS`.

Le self-smoke a également permis de détecter puis corriger un défaut réel : JavaParser utilisait son niveau de langage par défaut et refusait les text blocks présents dans le code NEXUS. L'analyseur est désormais configuré explicitement avec le niveau Java 21 et ce comportement est couvert par un test automatisé.

**Itération 2 — terminée et validée localement : recherche, graphe et classement explicable.**

L'itération comprend :

- recherche lexicale Lucene multi-champs avec ranking BM25 ;
- boosts explicites sur les noms de symboles, noms qualifiés, chemins et contenu ;
- recherche exacte et approximative de symboles depuis SQLite ;
- fusion déterministe des candidats et de leurs signaux ;
- graphe minimal de fichiers construit à partir des imports internes résolus ;
- propagation de pertinence sur un et deux sauts ;
- ranking déterministe à composantes pondérées et explicables ;
- commande CLI `search` avec `--limit` et `--explain` ;
- corpus de requêtes de référence ;
- calcul de `precision@K` et `recall@K` ;
- tests d'intégration dédiés au ranking et au corpus golden.

Validation locale du 19 juillet 2026 :

- `mvn clean install` : succès ;
- compilation de 57 fichiers source avec `--release 21` : succès ;
- compilation de 7 fichiers de test : succès ;
- tests : 9 exécutés, 0 échec, 0 erreur, 0 ignoré ;
- tests couverts : analyse JavaParser, indexation, scanner, registre, métriques de qualité, corpus golden et recherche hybride de bout en bout ;
- génération et installation locale du JAR : succès.

Validation self-smoke de la recherche sur NEXUS :

- première indexation : 64 fichiers scannés, 64 fichiers modifiés, 0 supprimé ;
- index produit : 64 fichiers, 238 symboles et 460 relations ;
- première indexation avec reconstruction complète : 943 ms sur la machine de validation ;
- seconde indexation incrémentale : 64 fichiers scannés, 0 fichier modifié et 0 supprimé ;
- seconde indexation : 278 ms sur la machine de validation ;
- recherche explicable de `ProjectIndexingService` : succès ;
- `ProjectIndexingService.java` classé en première position avec un score de `0,5585` ;
- explication du premier résultat : BM25 `+0,400`, chemin `+0,100`, graphe `+0,059` ;
- résultat final : `SELF-SMOKE SUCCESS`.

La prochaine étape est l'**Itération 3 — construction du contexte et budget**.

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
search <id-ou-nom> <requête> [--limit N] [--explain]
inspect <id-ou-nom>
```

Exemple de recherche :

```powershell
mvn -q exec:java "-Dexec.args=search nexus-context-engine-self-smoke ProjectIndexingService --limit 5 --explain"
```

Le packaging final en commande native `nexus` est prévu plus tard dans la phase de consolidation de la CLI.

### Self-smoke test : NEXUS indexe et recherche dans NEXUS

Le script PowerShell `scripts/self-smoke.ps1` valide le flux réel de la CLI sur le repository NEXUS lui-même :

1. compilation de la CLI ;
2. enregistrement du repository courant ;
3. vérification du registre ;
4. première indexation complète ;
5. seconde indexation incrémentale attendue avec `0 modifiés` et `0 supprimés` ;
6. inspection de l'index avec état `READY` ;
7. recherche explicable de `ProjectIndexingService` et vérification de la présence de `ProjectIndexingService.java`.

Le test utilise un `NEXUS_HOME` isolé sous `target/nexus-self-smoke-home` et supprime ces données à la fin par défaut.

Sous Windows PowerShell :

```powershell
git pull --ff-only
.\scripts\self-smoke.ps1
```

Pour conserver la base SQLite et l'index Lucene générés afin de les inspecter manuellement :

```powershell
.\scripts\self-smoke.ps1 -KeepData
```

L'exécution de la CLI par Maven utilise `exec-maven-plugin`, dont la version est fixée dans le `pom.xml` pour conserver un comportement reproductible.

Sous Windows PowerShell 5.1, les accents de certaines sorties Maven/CLI capturées peuvent être mal affichés selon l'encodage de la console. Ce défaut d'affichage est non bloquant et n'affecte ni les données SQLite/Lucene ni le résultat fonctionnel du self-smoke.

## Sécurité par défaut

NEXUS adopte une approche locale par défaut. Aucun contenu du repository ne doit quitter la machine sans activation explicite d'une intégration externe. Un fichier `.nexusignore` complète les mécanismes de type `.gitignore` afin d'exclure notamment les secrets, les fichiers sensibles et les contenus générés.

## Licence

Le choix de la licence reste volontairement ouvert tant que le repository n'est pas rendu public.
