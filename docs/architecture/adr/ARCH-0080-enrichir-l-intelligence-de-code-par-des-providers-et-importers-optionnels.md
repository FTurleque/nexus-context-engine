---
id: ARCH-0080
title: "Enrichir l'intelligence de code par des providers et importers optionnels : SCIP, JDT Language Server, MINOS"
domain: architecture
rules: true
files: ["core/src/main/java/com/nexus/index/*.java", "**/src/main/java/**", "core/src/main/java/**", "core/pom.xml", "**/src/*/java/**/*.java", "**/pom.xml"]
---

# Enrichir l'intelligence de code par des providers et importers optionnels : SCIP, JDT Language Server, MINOS

- **Statut** : Accepted
- **Date** : 2026-11-08
- **Origine** : consolidation du registre ADR (audit du 2026-11-08)
- **Remplace** : [ARCH-0009](archive/ARCH-0009-rendre-intelligence-code-extensible-via-providers.md), [BE-0036](archive/BE-0036-importer-scip-comme-enrichissement-opportuniste.md), [BE-0037](archive/BE-0037-integrer-jdt-language-server-comme-provider-java-profond-optionnel.md), [ARCH-0044](archive/ARCH-0044-consommer-minos-via-un-contrat-json-local-versionne.md)
- **Remplacé par** : —

## Context

JavaParser est l'analyseur Java embarqué de NEXUS ([BE-0008](BE-0008-utiliser-javaparser-comme-analyseur-java-embarque.md)) ; les autres langages sont indexés lexicalement ([BE-0038](BE-0038-indexer-les-langages-additionnels-lexicalement-et-enrichir-la-structure-via-providers.md)). Cette base locale et légère extrait la structure d'un fichier, mais pas toute l'intelligence sémantique : références résolues, implémentations, hiérarchies de types et d'appels, classpath Maven/Gradle.

Des outils matures produisent ces faits : les indexeurs SCIP (dont `scip-java`), Eclipse JDT Language Server, et MINOS, le moteur de Code Intelligence de l'écosystème. Les réimplémenter coûterait cher ; rendre l'un d'eux obligatoire ferait perdre l'autonomie locale ([ARCH-0005](ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.md)). Chacun a ses contraintes : un `index.scip` est produit hors de NEXUS et peut être périmé ; JDT LS est un processus externe lourd (workspace, import du projet, protocole LSP) ; MINOS est compilé en Java 24 alors que NEXUS reste en Java 21, ce qui exclut toute dépendance binaire.

La question est : **comment enrichir l'intelligence de code par des sources externes sans enfermer NEXUS dans JavaParser ni dans un fournisseur unique, sans rendre aucune source obligatoire, et en gardant la provenance de chaque fait ?**

## Options envisagées

- **Architecture d'ensemble** : étendre JavaParser jusqu'à toute l'intelligence sémantique (réimplémentation, mauvaise voie multi-langage) ; prendre SCIP comme modèle interne obligatoire (modèle NEXUS couplé à un standard externe, index pas toujours disponible) ; JDT LS comme backend Java unique (lourd, propre à Java) ; Tree-sitter comme fondation commune (sémantique limitée, contraintes de bindings) ; **ports `CodeIntelligenceProvider` et `CodeIndexImporter` à implémentations optionnelles** (retenu).
- **SCIP** : remplacer JavaParser par SCIP (aucun fonctionnement sans index) ; lancer `scip-java` pendant `nexus index` (build implicite, effets de bord, dépendance opérationnelle) ; **import opportuniste d'un `index.scip` déjà disponible** (retenu).
- **JDT LS** : remplacer JavaParser (dépendance obligatoire, démarrage lent) ; lancer JDT LS à chaque `nexus index` (coût permanent, dépendance implicite) ; **provider explicite activé par `--deep-java`** (retenu).
- **MINOS** : dépendance Java/Maven directe (rejetée : bytecode Java 24, modèles couplés) ; serveur MCP ou HTTP MINOS (non retenu : couche réseau inutile pour un transfert local de snapshot) ; processus MINOS lancé par NEXUS (rejeté : orchestration d'un runtime Java 24 et surface d'exécution de processus) ; **export MINOS explicite puis import NEXUS par stdin** (retenu).

## Decision

### Ports et modèle neutre

Trois responsabilités séparées :

```text
LanguageAnalyzer          extraction syntaxique et structurelle embarquée
CodeIntelligenceProvider  intelligence calculée activement, à la demande
CodeIndexImporter         import d'un index produit par un outil externe
```

Les ports `CodeIntelligenceProvider` et `CodeIndexImporter`, et le modèle `CodeIntelligenceSnapshot`, `CodeSymbol`, `SymbolRelation` qu'ils échangent, ne dépendent d'aucun format externe. Chaque provider ou importer convertit ses données vers `CodeSymbol`, `SymbolRelation`, `IndexedSymbol`, `IndexedRelation` et `CodeIntelligenceSnapshot` ; recherche, ranking et `ContextBuilder` ne connaissent que ce modèle. L'ajout d'un langage ou d'un provider ne modifie pas le contrat du `ContextBuilder`.

Aucun des outils SCIP, `scip-java`, Eclipse JDT Language Server, Tree-sitter ou MINOS n'est obligatoire : JavaParser reste le socle embarqué et NEXUS fonctionne pleinement sans eux. Tree-sitter reste une option future, à adopter quand son coût est justifié ; un analyseur spécifique est admis lorsqu'il est plus adapté à un langage. L'adoption durable d'un provider dépend d'un gain mesuré (symboles et relations supplémentaires, `precision@K`, `recall@K`, coût d'indexation).

### Provenance, confiance et fusion

`CodeSymbol` porte `sourceProvider` ; `SymbolRelation` porte `sourceProvider` et une `confidence` entre `0` et `1`. Les constructeurs historiques utilisent `javaparser` et une confiance de `1,0`. SQLite reste la source de vérité structurelle et stocke la provenance dans `symbols` et `symbol_relations`.

La fusion est déterministe et conservatrice :

1. JavaParser reste la base embarquée ;
2. au rafraîchissement d'un provider externe, ses anciennes données sont remplacées atomiquement ;
3. un symbole externe qui recouvre une définition existante (même fichier, type, nom et ligne de début) n'est pas dupliqué ;
4. un symbole externe absent de JavaParser est ajouté ;
5. une relation externe identique à une relation connue n'est pas dupliquée ;
6. les références, implémentations et relations de définition supplémentaires sont persistées avec leur provenance ;
7. aucun symbole ni aucune relation externe n'est rattaché à un fichier absent de `indexed_files`.

### SCIP : import opportuniste

`ScipCodeIndexImporter` cherche `index.scip` à la racine du projet enregistré : s'il existe, il est importé ; sinon l'indexation JavaParser continue normalement ; s'il disparaît après un import, les données du provider `scip` sont purgées. NEXUS ne lance jamais `scip-java` automatiquement : `scip-java` est un producteur possible de l'index, pas un runtime de NEXUS.

Le format SCIP reste confiné dans `com.nexus.index.scip` ; hors de ce paquetage, seule la composition référence l'importeur. Le parseur lit les champs Protobuf utiles et ignore les champs inconnus ; il peut être remplacé par les bindings officiels sans changer le port. Les lignes SCIP à base zéro sont converties en lignes à base un ; les plages typées sont prioritaires, le champ compact `range` reste accepté en repli. Un kind sans équivalent fiable n'est pas inventé.

### JDT Language Server : provider Java profond optionnel

`JdtLanguageServerCodeIntelligenceProvider` implémente `CodeIntelligenceProvider`. Il n'est disponible que si `NEXUS_JDTLS_HOME` pointe vers une distribution JDT LS extraite, et ne s'exécute que par `nexus index <projet> --deep-java` ou `nexus index <projet> --rebuild --deep-java`. Sans `--deep-java`, JDT LS n'est jamais lancé ; `--deep-java` sans provider configuré échoue explicitement.

- **Isolation** : JDT LS tourne dans un processus Java distinct, sur `stdio` avec le framing JSON-RPC de LSP, et s'arrête après chaque analyse profonde initiale ; pas de pool ni de daemon persistant sans mesure qui le justifie. Chaque projet a un workspace distinct sous `NEXUS_HOME/jdtls-workspaces`. NEXUS ne dépend pas des classes Eclipse JDT ni des modèles LSP dans son cœur : l'adaptateur traduit les réponses vers le modèle NEXUS.
- **Données** : `documentSymbol`, `references`, `implementation`, hiérarchies de types (`prepareTypeHierarchy`, `supertypes`, `subtypes`) et d'appels (`prepareCallHierarchy`, `incomingCalls`, `outgoingCalls`) ; provenance `jdtls`, confiance initiale `1,0`.
- **Fraîcheur** : un snapshot JDT est conservé par une indexation normale qui ne modifie aucun fichier Java ; une modification ou une suppression Java détectée sans `--deep-java` purge les données du provider actif.
- **Bornage du coût** : 250 symboles par défaut (`NEXUS_JDTLS_MAX_SYMBOLS`, plafond 10 000) ; 120 secondes par échange (`NEXUS_JDTLS_TIMEOUT_SECONDS`, plafond 3 600). Une valeur non entière, nulle, négative ou supérieure au plafond est rejetée ; seule une valeur absente ou vide prend le défaut. `NEXUS_JDTLS_JAVA` choisit le binaire Java.
- **Lecture seule** : le provider n'applique pas les `workspace/applyEdit` demandés par le serveur ; NEXUS n'est ni un IDE ni un client de refactoring.

### MINOS : contrat JSON local versionné

`MinosCodeIndexImporter` est un adaptateur pur de contrat JSON : il ne lance aucun processus et ne découvre aucun JAR MINOS ; aucun type `com.minos` ni aucune dépendance Maven vers MINOS n'existe dans NEXUS. L'appelant (shell, IDE, script) chaîne lui-même les deux processus :

```text
MINOS (Java 24)  nexus-export --root <project>  ──JSON stdout──▶  NEXUS (Java 21)  minos-import <project> < stdin
```

`minos-import` lit le payload sur stdin avec une borne de **128 MiB**, le valide (version de contrat, producteur, racine du projet) puis appelle `IndexRepository.replaceExternalCodeIntelligence(...)` avec `sourceProvider=minos`. Un import MINOS n'est jamais déclenché implicitement par `nexus index`. Seuls les symboles et relations `RESOLVED` dont la sémantique existe dans le modèle NEXUS sont convertis ; un kind non représentable est ignoré, jamais reclassé.

Chemins : NEXUS construit une allow-list des fichiers locaux réellement présents sous la racine projet de confiance ; le `filePath` du JSON n'est normalisé que lexicalement ; un chemin absolu, remontant ou absent de l'allow-list est ignoré ; le `filePath` externe n'est jamais utilisé comme argument d'une API d'I/O.

Hors décision : un contexte multi-projet, la génération des index externes, un rafraîchissement automatique des faits MINOS.

## Do's and Don'ts

### Do

- Faire passer l'intelligence de code par `CodeIntelligenceProvider` et `CodeIndexImporter`, l'extraction embarquée restant à `LanguageAnalyzer`.
- Convertir les données externes vers `CodeSymbol`, `SymbolRelation`, `IndexedSymbol`, `IndexedRelation` et `CodeIntelligenceSnapshot`, avec `sourceProvider` et une `confidence` entre `0` et `1`.
- Remplacer atomiquement les données d'un provider externe à son rafraîchissement.
- Importer `index.scip` à la racine du projet s'il existe ; poursuivre l'indexation sans lui ; purger le provider `scip` quand il disparaît.
- Convertir les lignes SCIP à base zéro en lignes à base un ; préférer les plages typées, accepter `range` en repli.
- Déclencher JDT LS uniquement par `--deep-java`, avec `NEXUS_JDTLS_HOME` configuré, et échouer explicitement sinon.
- Lancer JDT LS dans un processus distinct sur `stdio`, l'arrêter après chaque analyse, avec un workspace par projet sous `NEXUS_HOME/jdtls-workspaces`.
- Purger les données du provider actif quand une indexation normale détecte une modification ou une suppression Java.
- Borner JDT LS à 250 symboles (plafond 10 000) et 120 s par échange (plafond 3 600).
- Lire le payload MINOS sur stdin, borné à 128 MiB, et ne convertir que les faits `RESOLVED` représentables.
- Construire l'allow-list des chemins MINOS à partir des fichiers locaux présents sous la racine de confiance.

### Don't

- Ne pas faire dépendre les ports ni le modèle d'un format externe (SCIP, JDT, LSP, Tree-sitter, MINOS).
- Ne rendre obligatoire aucun des outils SCIP, `scip-java`, JDT LS, Tree-sitter ou MINOS.
- Ne jamais lancer `scip-java` automatiquement ; ne pas faire dépendre recherche et contexte d'une classe SCIP.
- Ne pas dupliquer un symbole externe qui recouvre une définition existante, ni une relation externe identique.
- Ne rattacher aucun fait externe à un fichier absent de `indexed_files`.
- Ne jamais lancer JDT LS sans `--deep-java` ; ne pas dépendre d'Eclipse JDT ni de LSP4J dans le cœur.
- Ne pas accepter pour les bornes JDT une valeur non entière, nulle, négative ou au-dessus du plafond.
- Ne pas appliquer les `workspace/applyEdit` ; ne pas introduire de pool ni de daemon JDT sans mesure.
- Ne pas lancer de processus ni découvrir de JAR MINOS ; ne pas déclencher un import MINOS par `nexus index`.
- Ne pas reclasser un kind non représentable ; ne pas utiliser un `filePath` externe comme argument d'I/O.

## Consequences

### Positives

- NEXUS fonctionne à l'identique sans SCIP, JDT LS ni MINOS ; le cœur reste indépendant de leurs formats.
- Plusieurs sources se combinent sous un modèle unique, avec une provenance inspectable en SQLite et sur les surfaces publiques.
- Un index `scip-java` existant enrichit la recherche sans build supplémentaire ; JDT LS enrichit les projets Maven/Gradle complexes à la demande ; MINOS est réutilisé sans couplage binaire Java 21/24, sans réseau ni processus enfant.
- Le coût de chaque provider est mesurable et borné ; les données profondes périmées sont purgées.

### Négatives

- La fraîcheur de `index.scip` et des faits MINOS n'est pas garantie : l'appelant régénère et réimporte.
- JDT LS s'installe séparément ; son workspace est coûteux à initialiser ; un client JSON-RPC minimal est à maintenir.
- Le mapping des kinds SCIP, LSP et MINOS vers le modèle NEXUS est volontairement réduit ; un symbole SCIP garde son identifiant SCIP comme `qualifiedName` quand c'est l'identité la plus sûre.
- La fusion de plusieurs sources peut produire des relations contradictoires, arbitrées par provenance et confiance.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Couplage du modèle interne à un format externe | Élevé | Mapping vers `CodeSymbol` / `SymbolRelation` ; règle `code-intelligence-contracts-free-of-external-formats` |
| Données contradictoires entre providers | Élevé | Provenance, confiance et règles de fusion explicites |
| Provider lourd activé pour tous les projets | Moyen | Activation explicite (`--deep-java`, `minos-import`) ou opportuniste |
| Parseur Protobuf minimal face aux évolutions de SCIP | Moyen | Champs inconnus ignorés ; tests de plages historiques et typées |
| Chemins non fiables d'un document externe | Élevé | Allow-list locale, normalisation lexicale, aucune I/O induite |

## Compliance and Enforcement

- Règle Archgate `ARCH-0080-enrichir-l-intelligence-de-code-par-des-providers-et-importers-optionnels.rules.ts` :
  - `code-intelligence-contracts-free-of-external-formats` : `CodeIntelligenceProvider`, `CodeIndexImporter`, `CodeIntelligenceSnapshot`, `CodeSymbol` et `SymbolRelation` n'importent que `java.*` et `com.nexus.*`, sans référence à `com.nexus.index.{jdt,scip,minos,java}` ni aux bibliothèques JavaParser, Eclipse, SCIP, Protobuf ou Jackson ;
  - `scip-format-confined-to-scip-package` : hors de `com.nexus.index.scip`, seule `NexusApplicationFactory` référence ce paquetage ; `scip-import-launches-no-process` : aucun lancement de processus dans ce paquetage ;
  - `core-has-no-eclipse-jdt-or-lsp-dependency` : ni `org.eclipse.jdt` ni `org.eclipse.lsp4j` dans `core/src/main/java` et `core/pom.xml` ; `jdt-cost-bounds-match-decision` : 250, 10 000, 120 et 3 600 ; `jdt-workspace-edits-refused` : `JdtStdioSession` répond `applied=false` et jamais `applied=true` ;
  - `minos-import-launches-no-process` : aucun lancement de processus dans `com.nexus.index.minos` ; `no-minos-types-in-nexus` : aucune référence `com.minos` dans les sources Java, aucune dépendance Maven MINOS.
  - Commentaires et littéraux effacés avant la recherche.
- Tests (`core`, lancés par `mvnw clean install`, job « Linux reactor Maven build » de `.github/workflows/ci.yml`) : `core/src/test/java/com/nexus/index/scip/` (`ScipCodeIndexImporterTest`, `ScipRelationIdentityTest`, `ScipSymbolRangeValidationTest`, `ScipProjectPathGuardTest`), `core/src/test/java/com/nexus/index/jdt/` (`JdtLanguageServerCodeIntelligenceProviderTest`, `JdtConfigurationBoundsTest`), `core/src/test/java/com/nexus/index/minos/` (`MinosCodeIndexImporterTest`, `MinosCodeIndexImporterStreamingTest`, `MinosProjectPathGuardTest`, `MinosRealIntegrationTest`), `ProjectIndexingServiceTest` (`mergesExternalCodeIntelligenceAndPurgesItWhenTheIndexDisappears`, `runsActiveProviderOnlyOnDeepIndexAndPurgesItAfterJavaChanges`), `ProviderProvenanceCollisionTest`.
- Script manuel `scripts/validate-minos-integration.ps1` : replay avec un vrai export MINOS.
- Revue : un nouveau provider passe par l'un des deux ports, reste optionnel et arrive avec une mesure de gain.

## Conditions de réexamen

La décision est réévaluée si : la maintenance ou la licence d'un provider devient incompatible ; un autre provider offre une qualité nettement supérieure ; les mesures montrent qu'un enrichissement dégrade la précision ou introduit trop de bruit ; les bindings officiels SCIP apportent un gain net, ou la taille des index impose un parseur entièrement streaming ; la fraîcheur de `index.scip` doit être contrôlée automatiquement ; le coût de démarrage de JDT LS domine son bénéfice, un daemon apporte un gain mesuré ou une bibliothèque LSP mature réduit l'adaptation sans alourdir le cœur ; NEXUS passe au niveau Java de MINOS, un contrat standard de l'écosystème remplace l'échange JSON, ou un besoin mesuré exige un rafraîchissement automatique orchestré hors du cœur. L'abstraction multi-provider reste la décision fondamentale même si les implémentations changent.

## Mise en œuvre

- Ports et modèle : `core/src/main/java/com/nexus/index/` (`CodeIntelligenceProvider`, `CodeIndexImporter`, `CodeIntelligenceSnapshot`, `CodeSymbol`, `SymbolRelation`, `CodeIntelligenceMetadataPolicy`).
- SCIP : `core/src/main/java/com/nexus/index/scip/ScipCodeIndexImporter.java` (`DEFAULT_INDEX_FILE = "index.scip"`).
- JDT LS : `core/src/main/java/com/nexus/index/jdt/JdtLanguageServerCodeIntelligenceProvider.java` (bornes, `NEXUS_JDTLS_HOME`, `jdtls-workspaces`), `JdtStdioSession.java` (`workspace/applyEdit`).
- MINOS : `core/src/main/java/com/nexus/index/minos/MinosCodeIndexImporter.java` (`MAX_EXPORT_BYTES`), commande `minos-import` de `core/src/main/java/com/nexus/cli/NexusCli.java`.
- Description technique courante : [`code-intelligence.md`](../../developer/code-intelligence.md), [`jdt-language-server.md`](../../developer/jdt-language-server.md), [`minos-code-intelligence.md`](../../developer/minos-code-intelligence.md).

## References

- [BE-0008](BE-0008-utiliser-javaparser-comme-analyseur-java-embarque.md) — JavaParser, analyseur Java embarqué.
- [BE-0038](BE-0038-indexer-les-langages-additionnels-lexicalement-et-enrichir-la-structure-via-providers.md) — langages additionnels indexés lexicalement, structure enrichie par providers.
- [ARCH-0005](ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.md) — local-first, intégrations externes opt-in.
- [DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md) — SQLite source de vérité, Lucene index dérivé.
- [BE-0071](BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md) — recherche et ranking des candidats.
