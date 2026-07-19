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
- [Registre des décisions d'architecture — ADR](docs/adr/README.md) ;
- [Guide développeur détaillé](docs/developer/README.md).

Le guide développeur documente l'implémentation concrète avec diagrammes Mermaid/UML, séquences d'exécution, modèle SQLite, algorithmes de ranking et procédure de reproduction locale.

Les ADR constituent l'historique de référence des décisions structurantes, de leurs alternatives et de leurs conséquences. `docs/architecture.md` décrit l'état architectural courant.

## Socle technique

- Java 21 comme niveau de compilation du MVP ;
- Maven ;
- JavaParser pour l'analyse structurelle Java embarquée ;
- SQLite comme source de vérité structurelle locale ;
- Apache Lucene comme index de recherche local reconstructible ;
- JGit pour la sémantique `.gitignore` / `.nexusignore` ;
- Jackson à la frontière CLI pour la sérialisation JSON ;
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

**Itération 3 — terminée et validée localement : construction du contexte et budget.**

L'itération comprend :

- `HeuristicTokenEstimator` local, déterministe et remplaçable ;
- matérialisation des candidats en fragments symboliques ou fenêtres de fichier ;
- chemins de bundle relatifs au projet ;
- fusion des plages chevauchantes et adjacentes ;
- sélection gloutonne déterministe sous budget ;
- troncature explicite des fragments trop volumineux ;
- métadonnées de réduction et d'arbitrage ;
- implémentation `DefaultContextBuilder` ;
- commande CLI `context` avec `--budget` et `--explain` ;
- tests dédiés au budget, à la fusion, à la troncature et au déterminisme ;
- self-smoke étendu à la construction d'un `ContextBundle` réel.

Validation locale du 19 juillet 2026 :

- `mvn clean install` : succès ;
- compilation de 65 fichiers source avec `--release 21` : succès ;
- compilation de 10 fichiers de test : succès ;
- tests : 13 exécutés, 0 échec, 0 erreur, 0 ignoré ;
- génération du JAR `nexus-context-engine-0.1.0-SNAPSHOT.jar` : succès ;
- installation dans le dépôt Maven local : succès.

Validation self-smoke du `ContextBundle` sur NEXUS :

- première indexation : 75 fichiers scannés, 75 fichiers modifiés, 0 supprimé ;
- index produit : 75 fichiers, 288 symboles et 564 relations ;
- première indexation avec reconstruction complète : 931 ms sur la machine de validation ;
- seconde indexation incrémentale : 75 fichiers scannés, 0 fichier modifié et 0 supprimé ;
- seconde indexation : 275 ms sur la machine de validation ;
- recherche explicable de `ProjectIndexingService` : succès, fichier principal toujours classé premier ;
- construction du contexte avec un budget de 180 tokens : succès ;
- bundle obtenu : 3 items, 178 tokens estimés sur 180 ;
- fragments disponibles avant sélection : 5 076 tokens estimés ;
- ratio de réduction : environ 96,49 % ;
- 3 items tronqués explicitement et 9 fragments exclus faute de budget restant ;
- `ProjectIndexingService.java` et `ProjectIndexingServiceTest.java` sont conservés dans le bundle ;
- résultat final : `SELF-SMOKE SUCCESS`.

Le self-smoke confirme donc l'invariant principal de l'itération : `ContextBundle.estimatedTokens <= ContextBundle.tokenBudget`. Les troncatures et exclusions observées avec un budget volontairement très contraint de 180 tokens sont explicites et traçables ; elles constituent un point de calibration futur de la diversité du contexte, pas un échec du critère de sortie.

**Itération 4 — terminée et validée localement : CLI utilisable pour le MVP.**

L'itération comprend :

- sortie humaine conservée par défaut ;
- sortie JSON structurée via `--json` sur toutes les commandes ;
- séparation des succès sur `stdout` et des erreurs sur `stderr` ;
- codes de sortie `0` succès, `1` erreur d'exécution, `2` erreur d'utilisation ;
- commandes `--help` et `--version` ;
- latences `durationMs` pour indexation, recherche et construction du contexte ;
- JAR CLI autonome `nexus-context-engine-0.1.0-SNAPSHOT-cli.jar` ;
- scripts Windows `scripts/nexus.ps1` et `scripts/nexus.cmd` ;
- tests CLI JSON de bout en bout ;
- self-smoke exécutant directement le JAR autonome ;
- métriques de qualité du corpus golden publiées dans le log Maven.

Validation locale du 19 juillet 2026 :

- `mvn clean install` : succès ;
- compilation de 66 fichiers source avec `--release 21` : succès ;
- compilation de 11 fichiers de test : succès ;
- tests : 16 exécutés, 0 échec, 0 erreur, 0 ignoré ;
- baseline qualité : corpus de 3 requêtes, `mean precision@3 = 0,4444`, `mean recall@3 = 1,0000` ;
- génération du JAR bibliothèque `nexus-context-engine-0.1.0-SNAPSHOT.jar` : succès ;
- génération du JAR autonome `nexus-context-engine-0.1.0-SNAPSHOT-cli.jar` : succès ;
- installation des deux artefacts dans le dépôt Maven local : succès.

Validation self-smoke du MVP CLI sur le repository NEXUS :

- exécution directe du JAR autonome : succès ;
- `--version --json` : succès, version `0.1.0-SNAPSHOT` ;
- `project add --json` et `project list --json` réellement parsés avec `ConvertFrom-Json` : succès ;
- première indexation : 77 fichiers scannés, 77 modifiés, 0 supprimé ;
- index produit : 77 fichiers, 322 symboles et 599 relations ;
- indexation complète : 896 ms sur la machine de validation ;
- seconde indexation incrémentale : 0 fichier modifié, 0 supprimé, 232 ms ;
- état final : `READY` ;
- recherche explicable de `ProjectIndexingService` : succès, fichier principal classé premier, 254 ms ;
- construction du contexte : 3 items, 178/180 tokens, 285 ms ;
- réduction du contexte candidat : environ 96,45 % ;
- sortie humaine sans `--json` : succès ;
- résultat final : `SELF-SMOKE SUCCESS`.

Le self-smoke confirme que le **MVP du moteur NEXUS est validé de bout en bout** : un repository Java local peut être enregistré, indexé, réindexé de manière idempotente, recherché, expliqué et transformé en `ContextBundle` sous budget via un JAR autonome, avec un contrat JSON consommable par des scripts et outils externes.

Sous Windows PowerShell 5.1, les warnings écrits sur `stderr` par SLF4J ou la JVM peuvent encore apparaître sous la forme d'un `NativeCommandError` visuel. Ce bruit de console est non bloquant : le script contrôle le véritable `$LASTEXITCODE`, les documents JSON restent séparés sur `stdout` et les dix étapes du self-smoke sont validées.

Cette itération est encadrée par ADR-0030 et ADR-0031. La Phase 1 — validation du moteur NEXUS — est désormais achevée. La prochaine étape de la roadmap est l'**Itération 5 — Instructions et documentation**, première étape de la Phase 2 visant à étendre les sources de contexte.

### Point d'entrée CLI actuel

Classe principale :

```text
io.github.fturleque.nexus.cli.NexusCli
```

Commandes exposées :

```text
project add <chemin> [nom] [--json]
project list [--json]
index <id-ou-nom> [--rebuild] [--json]
search <id-ou-nom> <requête> [--limit N] [--explain] [--json]
context <id-ou-nom> <requête> [--budget N] [--explain] [--json]
inspect <id-ou-nom> [--json]
--help [--json]
--version [--json]
```

Après un build Maven, le chemin recommandé sous Windows PowerShell est :

```powershell
.\scripts\nexus.ps1 --help
.\scripts\nexus.ps1 project add . nexus-local
.\scripts\nexus.ps1 index nexus-local
.\scripts\nexus.ps1 search nexus-local ProjectIndexingService --limit 5 --explain
.\scripts\nexus.ps1 context nexus-local ProjectIndexingService --budget 500 --explain
```

Pour une sortie machine :

```powershell
.\scripts\nexus.ps1 search nexus-local ProjectIndexingService --limit 5 --explain --json
```

Le JAR autonome peut aussi être lancé directement :

```powershell
java -jar .\target\nexus-context-engine-0.1.0-SNAPSHOT-cli.jar --version --json
```

### Self-smoke test du MVP CLI

Le script PowerShell `scripts/self-smoke.ps1` valide le flux réel via le JAR autonome :

1. construction du JAR `*-cli.jar` ;
2. validation de `--version --json` ;
3. enregistrement du repository en JSON ;
4. vérification du registre en JSON ;
5. première indexation complète en JSON ;
6. seconde indexation incrémentale attendue avec `0 modifié` et `0 supprimé` ;
7. inspection de l'index en JSON avec état `READY` ;
8. recherche explicable en JSON ;
9. construction d'un `ContextBundle` JSON sous 180 tokens ;
10. vérification de la sortie humaine par défaut.

Les documents JSON sont réellement parsés par PowerShell avec `ConvertFrom-Json`.

Le test utilise un `NEXUS_HOME` isolé sous `target/nexus-self-smoke-home` et supprime ces données à la fin par défaut.

```powershell
git pull --ff-only
mvn clean install
.\scripts\self-smoke.ps1 -KeepData
```

Sous Windows PowerShell 5.1, les accents de certaines sorties JVM ou de bibliothèques peuvent être mal affichés selon l'encodage de la console. Le JSON NEXUS est capturé séparément de `stderr`, ce qui permet de le parser sans être pollué par ces avertissements.

## Sécurité par défaut

NEXUS adopte une approche locale par défaut. Aucun contenu du repository ne doit quitter la machine sans activation explicite d'une intégration externe. Un fichier `.nexusignore` complète les mécanismes de type `.gitignore` afin d'exclure notamment les secrets, les fichiers sensibles et les contenus générés.

## Licence

Le choix de la licence reste volontairement ouvert tant que le repository n'est pas rendu public.
