# ADR-0036 — Importer SCIP comme enrichissement opportuniste de l'intelligence de code

- Statut : `accepted`
- Date : 2026-07-20

## Contexte et problème

NEXUS utilise JavaParser comme analyseur Java embarqué. Cette base est locale, légère et suffisante pour extraire la structure principale d'un fichier, mais elle ne fournit pas toute l'intelligence sémantique disponible dans des index produits par des compilateurs ou des indexeurs spécialisés : références, relations d'implémentation, définitions indirectes et autres liens entre symboles.

L'ADR-0009 prévoit déjà deux ports distincts :

```text
LanguageAnalyzer
→ analyse syntaxique embarquée

CodeIndexImporter
→ import d'un index produit ailleurs

CodeIntelligenceProvider
→ intelligence calculée par un provider actif
```

L'itération 8 doit concrétiser cette architecture avec SCIP sans transformer SCIP ou `scip-java` en dépendance obligatoire du fonctionnement normal de NEXUS.

## Facteurs de décision

- JavaParser doit rester disponible sans outil externe.
- NEXUS ne doit pas lancer implicitement un build Maven/Gradle ou un indexeur tiers.
- Un index externe doit pouvoir être ajouté ou retiré sans rendre l'index SQLite incohérent.
- Les modèles métier NEXUS ne doivent pas dépendre des classes Protobuf SCIP.
- La provenance des symboles et relations doit rester inspectable.
- La fusion entre plusieurs sources doit être déterministe.
- Les données externes ne doivent jamais créer artificiellement des fichiers canoniques absents du scan local.
- Le format SCIP doit pouvoir évoluer sans imposer de changement au `ContextBuilder` ou aux stratégies de recherche.

## Options envisagées

### Option A — Remplacer JavaParser par SCIP

Avantages :

- modèle sémantique plus riche lorsque l'index est disponible ;
- une seule source d'intelligence de code.

Inconvénients :

- fonctionnement impossible sans index externe ;
- génération de l'index dépendante d'outils et du build du projet ;
- perte de l'autonomie du cœur NEXUS.

### Option B — Lancer automatiquement `scip-java` pendant `nexus index`

Avantages :

- expérience automatisée lorsque l'environnement Java est complet ;
- index SCIP toujours rafraîchi avec l'index NEXUS.

Inconvénients :

- déclenchement implicite de Maven/Gradle ou du compilateur ;
- latence et effets de bord sur les caches de build ;
- dépendance opérationnelle forte à un exécutable externe ;
- comportement moins prévisible et moins local-first.

### Option C — Import opportuniste d'un `index.scip` déjà disponible

Avantages :

- aucun processus externe lancé par NEXUS ;
- fonctionnement JavaParser inchangé en l'absence de SCIP ;
- réutilisation d'un index produit par `scip-java` ou un autre indexeur ;
- séparation nette entre génération et consommation de l'index.

Inconvénients :

- l'utilisateur ou son outillage doit générer l'index SCIP séparément ;
- un index présent peut être obsolète par rapport au code local ;
- une stratégie explicite de remplacement et de fusion est nécessaire.

## Décision

Nous retenons l'option C.

NEXUS introduit les contrats suivants :

```text
CodeIndexImporter
        │
        ▼
CodeIntelligenceSnapshot
        │
        ▼
IndexRepository
        │
        ▼
SQLite

CodeIntelligenceProvider
→ même modèle normalisé pour les futurs providers actifs
```

Le premier adaptateur est `ScipCodeIndexImporter`.

### Activation SCIP

Le comportement initial est volontairement simple :

1. NEXUS cherche `index.scip` à la racine du projet enregistré ;
2. si le fichier existe, il est importé ;
3. s'il n'existe pas, l'indexation JavaParser continue normalement ;
4. si un ancien snapshot SCIP avait été importé puis que `index.scip` disparaît, les données du provider `scip` sont purgées ;
5. NEXUS ne lance jamais `scip-java` automatiquement.

`scip-java` est donc supporté en tant que producteur possible d'un index disponible, pas comme runtime obligatoire de NEXUS.

### Normalisation

Le format SCIP reste confiné dans `com.nexus.index.scip`.

L'importeur convertit les données utiles vers :

- `CodeSymbol` ;
- `SymbolRelation` ;
- `IndexedSymbol` ;
- `IndexedRelation` ;
- `CodeIntelligenceSnapshot`.

Les consommateurs de recherche et de contexte ne dépendent d'aucune classe SCIP.

Le prototype initial consomme directement le wire format Protobuf nécessaire aux champs utilisés par NEXUS et ignore les champs inconnus. Cette implémentation peut être remplacée ultérieurement par les bindings Java officiels SCIP sans modifier le port `CodeIndexImporter` ni le modèle métier.

Les plages SCIP, exprimées avec des lignes à base zéro, sont converties vers les lignes à base un utilisées par NEXUS. Les plages typées récentes sont prioritaires ; l'ancien champ compact `range` reste accepté en repli.

### Provenance et confiance

`CodeSymbol` expose désormais `sourceProvider`.

`SymbolRelation` expose :

- `sourceProvider` ;
- `confidence` entre `0` et `1`.

Les constructeurs historiques restent disponibles et utilisent `javaparser` avec une confiance de `1,0`, ce qui préserve la compatibilité du code existant.

SQLite continue d'être la source de vérité structurelle et stocke les providers dans les colonnes déjà prévues par le schéma initial.

### Stratégie de fusion initiale

La fusion est déterministe et conservatrice :

1. JavaParser reste la base embarquée ;
2. lors du rafraîchissement d'un provider externe, les anciennes données de ce provider sont remplacées atomiquement ;
3. un symbole externe qui recouvre une définition déjà présente pour le même fichier, type, nom et ligne de début n'est pas dupliqué ;
4. un symbole externe absent de JavaParser est ajouté ;
5. une relation externe exactement identique à une relation déjà connue n'est pas dupliquée ;
6. les références, implémentations et relations de définition supplémentaires restent persistées avec leur provenance ;
7. aucun symbole ou relation externe n'est rattaché à un fichier qui n'existe pas dans `indexed_files`.

Cette stratégie donne la priorité à la stabilité de JavaParser pour les définitions déjà connues tout en utilisant SCIP pour compléter la couverture sémantique.

## Conséquences

### Positives

- NEXUS fonctionne exactement comme avant lorsqu'aucun index SCIP n'est présent.
- Un index `scip-java` existant peut enrichir la recherche sans lancer de build supplémentaire.
- Les références et relations sémantiques enrichies deviennent disponibles dans SQLite.
- La provenance est conservée dans le modèle Java et dans la persistance.
- Un futur importer LSIF ou un autre format peut réutiliser `CodeIndexImporter` et `CodeIntelligenceSnapshot`.
- Un futur provider JDT peut réutiliser `CodeIntelligenceProvider` sans modifier les consommateurs.

### Négatives

- La fraîcheur de `index.scip` n'est pas garantie par NEXUS.
- Le prototype ne génère pas automatiquement l'index SCIP.
- Le mapping des nombreux `SymbolInformation.Kind` vers le modèle NEXUS reste volontairement réduit.
- Les symboles SCIP conservent actuellement leur identifiant SCIP comme `qualifiedName` lorsque celui-ci constitue l'identité la plus sûre.
- Le parseur Protobuf minimal devra être surveillé face aux évolutions du protocole ; les champs inconnus sont ignorés pour réduire ce risque.

## Mesure de qualité

L'évaluation doit comparer au minimum deux configurations sur le même corpus :

```text
A. JavaParser seul
B. JavaParser + index SCIP
```

Les mesures retenues sont :

- nombre de symboles locaux supplémentaires réellement utilisables ;
- nombre de références et relations sémantiques supplémentaires ;
- évolution de `precision@K` et `recall@K` sur le corpus de requêtes de référence ;
- absence de doublons visibles dans les résultats de recherche ;
- coût supplémentaire d'indexation.

L'itération ne doit être déclarée validée qu'après `mvn clean install`, exécution du self-smoke et enregistrement de ces mesures dans la roadmap.

## Confirmation du respect de la décision

La décision est respectée si les tests démontrent que :

1. l'absence de `index.scip` ne fait pas échouer l'indexation ;
2. un index SCIP disponible ajoute des symboles ou relations normalisés ;
3. la provenance `scip` est lisible depuis `IndexRepository` ;
4. une définition déjà fournie par JavaParser n'est pas dupliquée ;
5. la suppression de `index.scip` purge le snapshot SCIP précédent ;
6. aucune génération automatique d'index SCIP n'est déclenchée ;
7. les plages SCIP historiques et typées sont acceptées ;
8. les métriques de qualité JavaParser seul et JavaParser + SCIP peuvent être comparées avant clôture de l'itération.

## Conditions de réexamen

Cette décision pourra être réexaminée si :

- les bindings Java officiels SCIP apportent un bénéfice net de maintenance ou de performance ;
- la taille des index impose un parseur entièrement streaming pour chaque message imbriqué ;
- la fraîcheur de `index.scip` doit être contrôlée automatiquement ;
- les mesures montrent que SCIP dégrade la précision ou introduit trop de bruit ;
- un provider actif comme JDT devient préférable pour certains projets Java complexes.

## Décisions liées

- ADR-0005 — Adopter un fonctionnement local-first et des intégrations externes opt-in.
- ADR-0006 — Utiliser SQLite comme source de vérité structurelle locale.
- ADR-0008 — Utiliser JavaParser comme analyseur Java embarqué du MVP.
- ADR-0009 — Rendre l'intelligence de code extensible via des providers et index externes.
- ADR-0024 — Combiner Lucene et SQLite pour la recherche de candidats.
