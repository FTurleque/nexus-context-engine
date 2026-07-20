# Reproduire, tester et déboguer NEXUS

Ce chapitre permet de reconstruire les scénarios de développement depuis un poste Windows avec PowerShell et Maven.

## 1. Pré-requis

Le projet compile avec :

```text
Java --release 21
Maven
```

Un JDK plus récent peut être utilisé pour lancer Maven tant que le code compile avec la cible Java 21.

```powershell
java -version
mvn -version
```

## 2. Récupérer la dernière version

```powershell
cd N:\workspace-dev\nexus-context-engine
git pull --ff-only
```

## 3. Build complet

```powershell
mvn clean install
```

Ce build doit :

1. nettoyer `target` ;
2. copier les migrations SQL ;
3. compiler le code avec `release 21` ;
4. compiler les tests ;
5. exécuter JUnit ;
6. publier les métriques du corpus golden ;
7. générer le JAR bibliothèque ;
8. générer le JAR CLI autonome avec classifier `cli` ;
9. installer les artefacts Maven locaux.

Artefacts attendus :

```text
target/nexus-context-engine-0.1.0-SNAPSHOT.jar
target/nexus-context-engine-0.1.0-SNAPSHOT-cli.jar
```

Le second est directement exécutable :

```powershell
java -jar .\target\nexus-context-engine-0.1.0-SNAPSHOT-cli.jar --version
```

## 4. Avertissements connus non bloquants

### Maven / Guice

```text
sun.misc.Unsafe::staticFieldBase
```

Ce warning provient des bibliothèques utilisées par Maven/Guice, pas du code métier NEXUS.

### SQLite JDBC

```text
Use --enable-native-access=ALL-UNNAMED
```

Le driver SQLite charge une bibliothèque native. Le warning ne bloque pas actuellement le build.

### SLF4J

```text
No SLF4J providers were found
```

NEXUS n'a pas encore choisi de backend de logging. SLF4J utilise donc une implémentation NOP pour les dépendances concernées.

### Lucene Vector API

```text
Java vector incubator module is not readable
```

Le moteur lexical fonctionne sans le module vectoriel. Ce warning concerne une optimisation potentielle.

## 5. Lancer le self-smoke complet

```powershell
.\scripts\self-smoke.ps1 -KeepData
```

Le script utilise son propre `NEXUS_HOME` :

```text
target/nexus-self-smoke-home
```

L'Itération 7 étend le self-smoke à treize étapes sur le **JAR autonome** :

```text
1. construire le JAR CLI autonome
2. valider --version --json
3. enregistrer NEXUS avec project add --json
4. vérifier project list --json
5. première indexation complète --json
6. seconde indexation attendue avec 0 changement
7. inspect --json avec état READY
8. search --explain --json
9. contexte strict 180 tokens + instructions natives, Git désactivé
10. contexte multi-source code + documentation + instructions
11. activation progressive d'un Agent Skill
12. contexte Git local borné et explicable
13. sortie humaine sans --json
```

Les sorties JSON sont réellement parsées avec :

```powershell
ConvertFrom-Json
```

Le résultat attendu est :

```text
SELF-SMOKE SUCCESS
```

La section `=== METRICS ===` affiche notamment :

- indexation complète ;
- indexation incrémentale ;
- recherche ;
- contexte strict ;
- contexte multi-source ;
- contexte avec skill ;
- contexte Git ;
- commits Git inspectés et liés ;
- réduction du contexte candidat.

Avec `-KeepData`, les données SQLite/Lucene restent disponibles après le script.

## 6. Tester manuellement avec un NEXUS_HOME isolé

```powershell
$env:NEXUS_HOME = "$PWD\target\manual-nexus-home"
```

Pour repartir de zéro :

```powershell
Remove-Item -Recurse -Force $env:NEXUS_HOME -ErrorAction SilentlyContinue
```

## 7. Choisir le mode d'exécution CLI

Après `mvn clean install`, le mode recommandé est :

```powershell
.\scripts\nexus.ps1 --help
```

Le launcher exécute le dernier JAR `*-cli.jar` disponible dans `target`.

Le launcher CMD reste disponible :

```cmd
scripts\nexus.cmd --help
```

L'exécution Maven reste possible pendant le développement :

```powershell
mvn -q exec:java "-Dexec.args=--help"
```

mais elle n'est pas le chemin principal de validation.

## 8. Enregistrer un projet

```powershell
.\scripts\nexus.ps1 project add . nexus-manual
.\scripts\nexus.ps1 project list
```

Version JSON :

```powershell
.\scripts\nexus.ps1 project list --json
```

## 9. Indexer et vérifier l'idempotence

```powershell
.\scripts\nexus.ps1 index nexus-manual
.\scripts\nexus.ps1 inspect nexus-manual
.\scripts\nexus.ps1 index nexus-manual --json
```

Sans modification du repository, la seconde passe doit produire :

```text
report.changedFiles = 0
report.removedFiles = 0
```

Si ce n'est pas le cas :

1. identifier le chemin considéré comme changé ;
2. comparer son SHA-256 ;
3. vérifier si un outil modifie le fichier pendant le build ;
4. vérifier `.gitignore` / `.nexusignore` ;
5. ajouter un test de non-régression avant correction.

## 10. Forcer la reconstruction Lucene

```powershell
.\scripts\nexus.ps1 index nexus-manual --rebuild
```

Utiliser cette commande lorsque :

- l'index Lucene a été supprimé ;
- une évolution de schéma d'index l'exige ;
- une incohérence SQLite/Lucene est suspectée.

SQLite reste la source structurelle de référence.

## 11. Rechercher et inspecter le ranking

```powershell
.\scripts\nexus.ps1 search nexus-manual ProjectIndexingService --limit 5 --explain
```

À vérifier :

- le fichier attendu remonte ;
- le score est affiché ;
- les raisons sont cohérentes ;
- une deuxième exécution sans changement produit le même ordre ;
- la latence est affichée.

Dans un repository Git local, une raison supplémentaire peut apparaître :

```text
récence Git locale: 1.000 -> +0.050
```

Version JSON :

```powershell
$result = .\scripts\nexus.ps1 search nexus-manual ProjectIndexingService --limit 5 --explain --json |
    ConvertFrom-Json
$result.durationMs
$result.results[0].scoreComponents
$result.results[0].reasons
```

## 12. Comprendre le score de recherche

Le score par défaut est :

```text
score =
    lexical    × 0.40
  + exact      × 0.30
  + fuzzy      × 0.10
  + path       × 0.10
  + graph      × 0.10
  + gitRecency × 0.05
```

Le bonus Git est configurable dans `DeterministicContextRanker` entre `0.0` et `0.20`.

Avec :

```java
new DeterministicContextRanker(0.0d)
```

le signal Git ne contribue plus au score et le ranking historique est retrouvé.

Le mode `--explain` doit permettre de refaire le calcul à partir des composantes affichées.

## 13. Construire un contexte

```powershell
.\scripts\nexus.ps1 context nexus-manual ProjectIndexingService --budget 1200 --explain
```

Invariant :

```text
estimatedTokens <= tokenBudget
```

Le bundle peut contenir :

```text
INSTRUCTION
SKILL
GIT
SYMBOL
FILE
TEST
DOCUMENTATION
```

Les métadonnées et exclusions sont visibles avec `--explain`.

## 14. Tester un petit budget

```powershell
.\scripts\nexus.ps1 context nexus-manual ProjectIndexingService --budget 180 --explain --json
```

Pour un budget global inférieur à 500 tokens :

```text
gitEnabled = false
gitBudget = 0
```

Cette règle protège le code et les instructions essentielles lorsque le budget est très contraint.

## 15. Inspecter le contexte Git

```powershell
$gitContext = .\scripts\nexus.ps1 context nexus-manual `
    "DefaultContextBuilder git context budget recent changes" `
    --budget 1600 --explain --json |
    ConvertFrom-Json
```

Inspecter :

```powershell
$gitContext.items | Where-Object type -eq "GIT"
$gitContext.metadata.gitEnabled
$gitContext.metadata.gitRepositoryAvailable
$gitContext.metadata.gitCommitsInspected
$gitContext.metadata.gitRelatedCommits
$gitContext.metadata.gitCoChangeLinks
$gitContext.metadata.gitBudget
$gitContext.metadata.gitSelectedItems
$gitContext.metadata.gitSelectedTokens
$gitContext.metadata.gitDiagnostics
```

Chemins virtuels possibles :

```text
.nexus/git/recent-commits.md
.nexus/git/file-history.md
.nexus/git/working-tree-diff.md
.nexus/git/co-changes.md
```

Le contexte Git est local et en lecture seule. NEXUS ne doit jamais déclencher :

```text
fetch
pull
push
checkout
commit
```

## 16. Diagnostiquer un contexte Git absent

```mermaid
flowchart TD
    A[Pas d'item GIT] --> B{Budget global >= 500 ?}
    B -- Non --> C[Comportement attendu : Git désactivé]
    B -- Oui --> D{gitRepositoryAvailable = true ?}
    D -- Non --> E[Inspecter gitDiagnostics et la racine du projet]
    D -- Oui --> F{gitRelatedCommits > 0 ?}
    F -- Non --> G[Aucun commit récent ne touche les chemins candidats]
    F -- Oui --> H{gitBudget > 0 ?}
    H -- Non --> I[Instructions / skills ont consommé le budget restant]
    H -- Oui --> J[Inspecter gitSelectedItems et exclusions]
```

Dans un monorepo, vérifier que la racine du projet enregistrée dans NEXUS correspond bien au sous-projet attendu. NEXUS convertit les chemins entre la racine Git et la racine projet et exclut les co-changements situés hors du sous-projet.

## 17. Inspecter les Agent Skills

```powershell
$skillContext = .\scripts\nexus.ps1 context nexus-manual `
    "validate NEXUS context quality progressive disclosure" `
    --budget 1200 --explain --json |
    ConvertFrom-Json
```

Vérifier :

```text
skillsDiscovered
skillsMatched
skillsActivated
skillsSelected
skillResourcesDiscovered
skillsExecuted = false
```

Les scripts et ressources ne sont jamais exécutés automatiquement.

## 18. Vérifier les codes de sortie

Succès :

```powershell
.\scripts\nexus.ps1 --version
$LASTEXITCODE
# 0
```

Erreur d'utilisation :

```powershell
.\scripts\nexus.ps1 unknown-command --json
$LASTEXITCODE
# 2
```

Une erreur d'exécution inattendue utilise le code `1`.

## 19. Inspecter SQLite

Le fichier de base est résolu par `NexusPaths` sous `NEXUS_HOME`.

```sql
SELECT id, name, root_path, index_status
FROM projects;
```

```sql
SELECT relative_path, content_hash, category
FROM indexed_files
ORDER BY relative_path;
```

```sql
SELECT f.relative_path, s.kind, s.qualified_name, s.start_line, s.end_line
FROM symbols s
JOIN indexed_files f ON f.id = s.file_id
ORDER BY f.relative_path, s.start_line;
```

```sql
SELECT kind, source_ref, target_ref, source_provider
FROM symbol_relations
ORDER BY source_ref, target_ref;
```

Le contexte Git de l'Itération 7 n'est pas persisté dans SQLite : il est relu localement à la demande.

## 20. Comprendre un ContextBundle trop pauvre

```mermaid
flowchart TD
    A[Bundle pauvre] --> B{Recherche trouve-t-elle le bon candidat ?}
    B -- Non --> C[Diagnostiquer SearchService / ranking]
    B -- Oui --> D{Instructions applicables ?}
    D -- Oui --> E[Inspecter instructionBudget]
    D -- Non --> F{Skill pertinent ?}
    F -- Oui --> G[Inspecter skillBudget]
    F -- Non --> H{Contexte Git attendu ?}
    H -- Oui --> I[Inspecter gitBudget / gitRelatedCommits]
    H -- Non --> J{Fragment de tâche matérialisé ?}
    J -- Non --> K[Diagnostiquer ContextFragmentFactory]
    J -- Oui --> L[Inspecter BudgetedContextSelector]
```

Cette méthode évite de modifier le ranking lorsqu'en réalité le problème vient d'un sous-budget.

## 21. Diagnostiquer un problème JSON

```mermaid
flowchart TD
    A[JSON invalide ou pollué] --> B{stdout contient-il uniquement le JSON ?}
    B -- Non --> C[Inspecter CliRenderer]
    B -- Oui --> D{warnings présents sur stderr ?}
    D -- Oui --> E[Normal : séparer stderr dans le consommateur]
    D -- Non --> F{ConvertFrom-Json réussit ?}
    F -- Non --> G[Inspecter Jackson et le payload]
    F -- Oui --> H[Inspecter le contrat de champs attendu]
```

Les warnings JVM ou bibliothèques peuvent apparaître sur `stderr`, mais ne doivent jamais être concaténés au document JSON de succès sur `stdout`.

## 22. Encodage PowerShell 5.1

Windows PowerShell 5.1 peut afficher certains accents provenant des processus Java/Maven sous une forme incorrecte :

```text
modifiÚs
rÚsultat
```

Le self-smoke capture `stdout` / `stderr` dans des fichiers séparés et utilise des assertions ASCII-safe.

Ce problème d'affichage ne signifie pas que les données SQLite, Lucene ou Git sont corrompues.

## 23. Ajouter un test de non-régression

Ordre recommandé :

1. reproduire le bug dans un test ;
2. vérifier que le test échoue ;
3. corriger la classe la plus locale possible ;
4. exécuter `mvn clean install` ;
5. exécuter le self-smoke ;
6. mettre à jour la documentation si le comportement observable change ;
7. créer un ADR si la correction modifie une décision structurante.

## 24. Nettoyer l'environnement de test

```powershell
Remove-Item -Recurse -Force .\target\nexus-self-smoke-home -ErrorAction SilentlyContinue
Remove-Item Env:NEXUS_HOME -ErrorAction SilentlyContinue
```

## 25. Matrice de diagnostic rapide

| Symptôme | Zone probable |
|---|---|
| fichier absent dès l'index | `ProjectScanner`, ignore rules |
| fichier toujours « modifié » | SHA-256 / contenu généré |
| symboles absents | `JavaParserLanguageAnalyzer` |
| SQLite correct, recherche absente | synchronisation / Lucene |
| fichier trouvé mais mal classé | signaux / `DeterministicContextRanker` |
| bonus Git inattendu | `GitRecencyCandidateEnricher` / poids `gitRecencyScore` |
| voisin pertinent absent | graphe d'imports |
| recherche correcte, contexte absent | `ContextFragmentFactory` |
| contenu dupliqué | `FragmentMerger` |
| skill pertinent absent | `SkillSelector` / `SkillContextSelector` |
| item Git absent | `LocalGitContextSourceProvider` / `gitBudget` / chemins cibles |
| fuite hors sous-projet Git | mapping racine Git ↔ racine projet |
| budget dépassé | `BudgetedContextSelector` / `TokenEstimator` |
| bundle non déterministe | tri ou collection non ordonnée |
| JSON mélangé à des warnings | séparation stdout/stderr de la CLI ou du wrapper |
| `*-cli.jar` introuvable | phase Maven package / Shade Plugin |
| JAR autonome démarre mais SQLite échoue | services `META-INF/services` ou packaging natif |
