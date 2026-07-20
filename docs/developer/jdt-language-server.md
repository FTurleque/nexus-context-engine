# Analyse Java profonde optionnelle avec Eclipse JDT Language Server

Ce chapitre décrit l'intégration initiale de l'Itération 9.

L'objectif est d'utiliser Eclipse JDT Language Server pour enrichir certains projets Java complexes sans modifier le chemin d'indexation normal de NEXUS.

## 1. Positionnement

NEXUS dispose désormais de trois niveaux complémentaires :

```text
JavaParser
→ structure Java embarquée
→ toujours disponible

SCIP
→ index externe importé opportunément
→ utilisé lorsqu'un index.scip est présent

JDT Language Server
→ provider Java profond actif
→ lancé uniquement avec --deep-java
```

JDT LS n'est ni une dépendance Maven de NEXUS, ni un runtime obligatoire.

Le cœur continue de manipuler uniquement :

- `CodeSymbol` ;
- `SymbolRelation` ;
- `CodeIntelligenceSnapshot` ;
- `CodeIntelligenceProvider`.

L'adaptateur JDT reste confiné dans :

```text
com.nexus.index.jdt
```

## 2. Pourquoi JDT LS

JavaParser fournit une excellente base syntaxique mais ne résout pas à lui seul toute la sémantique d'un projet Maven ou Gradle complexe.

JDT LS peut s'appuyer sur le modèle de compilation du projet pour fournir notamment :

- références ;
- implémentations ;
- hiérarchies de types ;
- hiérarchies d'appels.

Le prototype NEXUS utilise les méthodes LSP correspondantes puis normalise les résultats dans SQLite.

## 3. Pré-requis

JDT LS nécessite Java 21 au minimum pour exécuter le serveur.

NEXUS utilise une distribution JDT LS extraite contenant notamment :

```text
plugins/
config_win/
config_linux/
config_mac/
```

Sous Windows, le script fourni installe une version figée pour rendre les mesures reproductibles :

```powershell
.\scripts\install-jdtls.ps1
```

Le script :

1. télécharge `jdt-language-server-1.60.0-202606262232.tar.gz` depuis les snapshots Eclipse ;
2. télécharge le checksum SHA-256 publié ;
3. vérifie l'archive ;
4. extrait JDT LS sous `~/.nexus/tools` ;
5. positionne `NEXUS_JDTLS_HOME` dans le processus PowerShell courant.

Une installation existante peut également être utilisée directement :

```powershell
$env:NEXUS_JDTLS_HOME = "C:\chemin\vers\jdtls"
```

Le chemin doit pointer vers la racine contenant `plugins` et `config_win` sous Windows.

## 4. Configuration

### `NEXUS_JDTLS_HOME`

Obligatoire pour activer le provider.

```text
NEXUS_JDTLS_HOME=C:\...\jdtls-1.60.0-202606262232
```

### `NEXUS_JDTLS_JAVA`

Optionnel.

Commande ou chemin de l'exécutable Java utilisé pour lancer JDT LS.

Valeur par défaut :

```text
java
```

### `NEXUS_JDTLS_TIMEOUT_SECONDS`

Optionnel.

Timeout d'un échange avec JDT LS.

Valeur par défaut :

```text
120
```

### `NEXUS_JDTLS_MAX_SYMBOLS`

Optionnel.

Nombre maximal de symboles sur lesquels NEXUS lance les requêtes sémantiques profondes.

Valeur par défaut :

```text
250
```

Ce bornage évite qu'un premier prototype déclenche un nombre incontrôlé de requêtes LSP sur un très grand projet.

## 5. Activation explicite

L'indexation normale reste :

```powershell
nexus index mon-projet
```

Elle n'exécute jamais JDT LS.

L'analyse profonde est activée par :

```powershell
nexus index mon-projet --deep-java
```

Une reconstruction complète avec analyse profonde utilise :

```powershell
nexus index mon-projet --rebuild --deep-java
```

Les options `--rebuild` et `--deep-java` peuvent être combinées dans la même commande.

Si `--deep-java` est demandé sans `NEXUS_JDTLS_HOME`, NEXUS retourne une erreur explicite.

## 6. Cycle de vie du snapshot JDT

Le snapshot JDT suit une règle de fraîcheur conservatrice.

### Aucun fichier Java ne change

Une indexation normale incrémentale conserve le dernier snapshot JDT.

Cela évite de lancer le serveur à chaque commande lorsque le code Java est inchangé.

### Un fichier Java change ou disparaît

Une indexation normale purge le snapshot JDT existant.

```text
Code Java modifié
      │
      ▼
nexus index
      │
      ├── JavaParser recalculé
      └── snapshot jdtls purgé
```

Cette purge empêche des références, appels ou hiérarchies devenus obsolètes de rester dans SQLite.

Pour reconstruire l'intelligence profonde :

```powershell
nexus index mon-projet --deep-java
```

## 7. Communication avec JDT LS

Le provider lance JDT LS comme processus externe.

```text
NEXUS
  │
  │ stdin / stdout
  │ JSON-RPC 2.0 + LSP
  ▼
JDT Language Server
```

NEXUS retire `CLIENT_PORT` et `CLIENT_HOST` de l'environnement du processus afin d'utiliser le transport standard `stdio`.

Le processus reçoit un workspace dédié par projet sous :

```text
NEXUS_HOME/jdtls-workspaces/<identifiant-du-projet>
```

Le prototype arrête le processus après chaque analyse profonde.

Un daemon persistant ne sera étudié que si les métriques montrent que le coût de démarrage justifie cette complexité.

## 8. Requêtes LSP utilisées

### Symboles

```text
textDocument/documentSymbol
```

NEXUS normalise les classes, interfaces, records, enums, méthodes et constructeurs représentables.

### Références

```text
textDocument/references
```

Normalisation :

```text
RelationKind.REFERENCES
```

### Implémentations

```text
textDocument/implementation
```

Normalisation :

```text
RelationKind.IMPLEMENTS
```

### Hiérarchie de types

```text
textDocument/prepareTypeHierarchy
typeHierarchy/supertypes
typeHierarchy/subtypes
```

Normalisation :

```text
RelationKind.EXTENDS
RelationKind.IMPLEMENTS
```

### Hiérarchie d'appels

```text
textDocument/prepareCallHierarchy
callHierarchy/incomingCalls
callHierarchy/outgoingCalls
```

Normalisation :

```text
RelationKind.CALLS
```

## 9. Provenance et fusion

Tous les éléments produits par le provider utilisent :

```text
sourceProvider = jdtls
```

Les relations initiales directement retournées par JDT LS utilisent :

```text
confidence = 1.0
```

La persistance réutilise la stratégie générique introduite à l'Itération 8 :

- remplacement atomique du snapshot du provider ;
- déduplication avec les informations déjà présentes ;
- aucun rattachement à un fichier absent de l'index canonique ;
- SQLite reste la source de vérité structurelle.

## 10. Lecture seule

Le provider ne transforme pas NEXUS en IDE.

Si JDT LS demande un `workspace/applyEdit`, NEXUS refuse l'édition.

NEXUS consomme l'intelligence produite mais ne modifie pas le repository par l'intermédiaire du serveur de langage.

## 11. Comparaison reproductible

Le script :

```powershell
.\scripts\compare-jdt.ps1
```

compare deux états sur le même repository :

```text
A. socle normal
   JavaParser + importers opportunistes disponibles

B. même socle + JDT LS
   via --deep-java
```

Le script utilise un `NEXUS_HOME` dédié afin de ne pas polluer les données locales normales.

Il mesure :

- fichiers ;
- symboles ;
- relations ;
- temps d'indexation ;
- `precision@3` ;
- `recall@3`.

Le rapport détaillé est écrit dans :

```text
target/jdt-evaluation/summary.json
```

L'objectif n'est pas de prouver que JDT LS produit plus de données à tout prix.

L'Itération 9 ne sera recommandée que si les mesures montrent un gain utile sur des cas Java profonds pour un coût acceptable.

## 12. Validation de l'Itération 9

Avant de déclarer l'itération terminée :

```powershell
mvn clean install
.\scripts\self-smoke.ps1
.\scripts\install-jdtls.ps1
.\scripts\compare-jdt.ps1
```

Les mesures réelles doivent ensuite être enregistrées dans la roadmap.

Le provider doit rester optionnel même si son gain est validé.
