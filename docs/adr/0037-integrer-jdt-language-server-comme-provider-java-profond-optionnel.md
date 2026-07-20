# ADR-0037 — Intégrer JDT Language Server comme provider Java profond optionnel

- Statut : `accepted`
- Date : 2026-07-20

## Contexte et problème

L'Itération 8 a validé l'import opportuniste de SCIP et confirmé la séparation prévue par l'ADR-0009 :

```text
LanguageAnalyzer
→ analyse syntaxique embarquée

CodeIndexImporter
→ import d'un index déjà produit

CodeIntelligenceProvider
→ intelligence calculée activement à la demande
```

JavaParser reste rapide, local et autonome, tandis que SCIP apporte une couverture sémantique supplémentaire lorsqu'un `index.scip` est disponible. Certains projets Java complexes nécessitent cependant une compréhension plus profonde du classpath, des dépendances Maven/Gradle, des références résolues, des implémentations et des hiérarchies.

Eclipse JDT Language Server fournit ces capacités, mais son coût opérationnel est supérieur : processus externe, initialisation d'un workspace, import du projet et protocole LSP.

La question est donc : **comment exploiter JDT LS sans le rendre obligatoire ni ralentir l'indexation normale de NEXUS ?**

## Facteurs de décision

- JavaParser doit rester le socle embarqué par défaut.
- SCIP et JDT doivent pouvoir coexister sans imposer leur présence.
- JDT LS ne doit être lancé qu'à la demande.
- Le processus JDT doit être isolé du processus NEXUS.
- Le cœur métier ne doit dépendre d'aucun modèle Eclipse ou LSP.
- Les données produites doivent être normalisées en `CodeSymbol` / `SymbolRelation`.
- Les snapshots profonds ne doivent pas rester silencieusement périmés après une modification Java.
- Le coût de l'analyse doit être bornable.
- L'adoption durable doit dépendre de mesures réelles.

## Options envisagées

### Option A — Remplacer JavaParser par JDT LS

Avantages :

- intelligence Java riche en permanence ;
- résolution du classpath et du modèle de compilation.

Inconvénients :

- dépendance opérationnelle obligatoire ;
- démarrage plus lent ;
- perte du fonctionnement léger et autonome ;
- complexité de cycle de vie pour chaque indexation.

### Option B — Lancer JDT LS automatiquement à chaque `nexus index`

Avantages :

- snapshot profond toujours recalculé ;
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

Le timeout par échange est configurable avec :

```text
NEXUS_JDTLS_TIMEOUT_SECONDS
```

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
- le coût est mesurable et configurable.

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
