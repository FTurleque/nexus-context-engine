# ADR-0037 — Intégrer JDT Language Server comme provider Java profond optionnel

- Statut : accepté
- Date : 2026-07-15

## Contexte

NEXUS dispose déjà d'une analyse Java locale et déterministe fondée sur JavaParser. Cette analyse couvre les symboles structuraux utiles au moteur de contexte, mais ne résout pas toute la sémantique d'un projet Java réel : classpath Maven/Gradle, références inter-modules, hiérarchies, implémentations et appels deviennent rapidement coûteux à reconstruire correctement dans le cœur.

Le moteur supporte également des snapshots d'intelligence externes, notamment SCIP, mais leur disponibilité dépend des outils du projet analysé.

Nous voulons pouvoir enrichir l'intelligence Java sur demande sans :

- transformer JDT LS en dépendance obligatoire ;
- faire dépendre le domaine NEXUS de types Eclipse/LSP ;
- ralentir toutes les indexations ;
- masquer le coût d'un processus externe.

## Options étudiées

### Option A — Étendre JavaParser jusqu'à une résolution sémantique complète

Avantages :

- aucune dépendance processus externe ;
- chemin unique d'analyse.

Inconvénients :

- résolution Maven/Gradle/classpath complexe ;
- maintenance importante ;
- risque de reconstruire un langage serveur incomplet dans NEXUS.

### Option B — Lancer JDT LS automatiquement à chaque indexation Java

Avantages :

- informations sémantiques profondes toujours disponibles ;
- expérience transparente.

Inconvénients :

- coût élevé même lorsque l'analyse profonde n'est pas utile ;
- indexation incrémentale simple ralentie ;
- dépendance implicite à une installation JDT LS.

### Option C — Provider JDT LS explicite, activé par `--deep-java`

Avantages :

- aucun impact sur le chemin normal ;
- activation lisible et volontaire ;
- réutilisation du port `CodeIntelligenceProvider` ;
- possibilité de mesurer séparément le coût et le gain.

Inconvénients :

- installation/configuration externe nécessaire ;
- analyse profonde à relancer après modification Java ;
- protocole et cycle de vie du processus à maintenir.

## Décision

Nous retenons l'option C.

JDT LS est intégré comme implémentation optionnelle de `CodeIntelligenceProvider` :

```text
nexus index projet
        │
        ├── JavaParser
        ├── Markdown
        └── importers opportunistes (SCIP)

nexus index projet --deep-java
        │
        ├── chemin normal
        └── JdtLanguageServerCodeIntelligenceProvider
                │
                ▼
          processus JDT LS
                │ stdio / JSON-RPC / LSP
                ▼
       CodeIntelligenceSnapshot
                │
                ▼
              SQLite
```

### Activation

Le provider n'est disponible que lorsque `NEXUS_JDTLS_HOME` pointe vers une distribution JDT Language Server extraite.

L'analyse profonde est déclenchée uniquement par :

```text
nexus index <projet> --deep-java
nexus index <projet> --rebuild --deep-java
```

Sans `--deep-java`, JDT LS n'est jamais lancé.

Si `--deep-java` est demandé sans provider configuré, la CLI échoue explicitement au lieu de simuler une analyse profonde réussie.

### Isolation du processus

NEXUS lance JDT LS dans un processus Java distinct.

La communication utilise le transport standard `stdio` de JDT LS et le framing JSON-RPC de LSP.

NEXUS ne dépend pas des classes Eclipse JDT ni des modèles LSP dans son cœur. L'adaptateur traduit les réponses directement vers le modèle NEXUS.

Chaque projet reçoit un workspace JDT LS distinct sous `NEXUS_HOME/jdtls-workspaces`.

Le processus est arrêté après chaque analyse profonde initiale. Une stratégie de pool ou de daemon persistant ne sera envisagée que si les mesures démontrent qu'elle est nécessaire.

### Données collectées

Le prototype utilise les capacités LSP suivantes lorsqu'elles sont disponibles :

- `textDocument/documentSymbol` ;
- `textDocument/references` ;
- `textDocument/implementation` ;
- `textDocument/prepareTypeHierarchy` ;
- `typeHierarchy/supertypes` ;
- `typeHierarchy/subtypes` ;
- `textDocument/prepareCallHierarchy` ;
- `callHierarchy/incomingCalls` ;
- `callHierarchy/outgoingCalls`.

Les données sont normalisées vers :

- `CodeSymbol` ;
- `SymbolRelation` ;
- `IndexedSymbol` ;
- `IndexedRelation` ;
- `CodeIntelligenceSnapshot`.

La provenance est `jdtls` et la confiance initiale vaut `1,0` pour les relations retournées directement par le serveur.

### Fraîcheur du snapshot

Un snapshot JDT peut être conservé lors d'une indexation incrémentale normale qui ne modifie aucun fichier Java.

En revanche, si une indexation normale détecte une modification ou une suppression Java sans `--deep-java`, NEXUS purge les données du provider actif. Cette règle évite de conserver des références ou hiérarchies potentiellement périmées.

Une nouvelle indexation `--deep-java` reconstruit ensuite le snapshot.

### Bornage du coût

Le nombre de symboles interrogés profondément est borné.

La valeur par défaut est de 250 symboles et peut être ajustée avec :

```text
NEXUS_JDTLS_MAX_SYMBOLS
```

La valeur reste limitée à **10000 symboles**.

Le timeout par échange est configurable avec :

```text
NEXUS_JDTLS_TIMEOUT_SECONDS
```

La valeur par défaut est de 120 secondes et le maximum accepté est de **3600 secondes**.

Pour ces deux paramètres, une valeur non entière, nulle, négative ou supérieure au plafond est rejetée explicitement lorsque le provider JDT LS est configuré. Une valeur absente ou vide conserve le défaut. Cette règle évite qu'une erreur de configuration désactive silencieusement le contrat de bornage.

Le binaire Java utilisé pour lancer JDT LS peut être configuré avec :

```text
NEXUS_JDTLS_JAVA
```

### Lecture seule

Le provider JDT NEXUS est un consommateur d'intelligence de code.

Il n'applique pas les `workspace/applyEdit` demandés par le serveur et ne transforme pas NEXUS en IDE ou en client de refactoring.

## Conséquences positives

- l'indexation normale reste inchangée lorsqu'aucun besoin profond n'existe ;
- JDT LS peut enrichir les projets Maven/Gradle complexes ;
- les dépendances Eclipse restent hors du cœur NEXUS ;
- les références, implémentations et hiérarchies partagent le modèle normalisé existant ;
- les données profondes périmées sont purgées après changement Java ;
- le coût est mesurable et configurable dans des plafonds explicites ;
- une configuration numérique invalide échoue fermé au lieu de restaurer silencieusement un défaut.

## Conséquences négatives

- l'utilisateur doit installer JDT LS séparément ;
- l'initialisation du workspace peut être coûteuse ;
- le protocole LSP impose un client JSON-RPC minimal à maintenir ;
- certaines relations ne peuvent être exprimées qu'avec les kinds actuellement disponibles dans NEXUS ;
- un snapshot profond n'est pas automatiquement recalculé après chaque changement.

## Validation attendue

L'Itération 9 ne doit être déclarée validée qu'après :

1. `mvn clean install` ;
2. `scripts/self-smoke.ps1` ;
3. exécution réelle de JDT LS via `--deep-java` ;
4. comparaison reproductible entre le socle courant et le socle + JDT LS ;
5. mesure du nombre de symboles/relations supplémentaires ;
6. mesure de `precision@K` et `recall@K` ;
7. mesure du coût d'indexation ;
8. démonstration d'un gain utile sur des relations Java profondes avant recommandation du provider.

## Conditions de réexamen

Cette décision pourra être réexaminée si :

- le coût de démarrage domine systématiquement le bénéfice ;
- un index SCIP couvre suffisamment les besoins Java complexes ;
- un mode daemon JDT LS apporte un gain mesurable ;
- une bibliothèque LSP mature réduit significativement le code d'adaptation sans alourdir le cœur ;
- un autre provider Java offre un meilleur compromis coût/qualité.

## Décisions liées

- ADR-0005 — Adopter un fonctionnement local-first et des intégrations externes opt-in.
- ADR-0008 — Utiliser JavaParser comme analyseur Java embarqué du MVP.
- ADR-0009 — Rendre l'intelligence de code extensible via des providers et index externes.
- ADR-0036 — Importer SCIP comme enrichissement opportuniste de l'intelligence de code.