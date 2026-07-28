# Code Intelligence dans NEXUS

Ce document décrit la Code Intelligence **actuellement intégrée** dans NEXUS.

NEXUS conserve son propre modèle (`CodeSymbol`, `SymbolRelation`) et traite JavaParser, SCIP, JDT Language Server et MINOS comme des sources de faits, jamais comme son modèle métier central.

## 1. Contrats

```text
LanguageAnalyzer
→ analyse syntaxique/structurelle locale embarquée

CodeIndexImporter
→ import opportuniste d'un index déjà produit

CodeIntelligenceProvider
→ analyse profonde explicitement activée

import MINOS explicite
→ contrat JSON fourni par l'appelant
```

Les résultats convergent vers :

```text
CodeIntelligenceSnapshot
├── sourceProvider
├── IndexedSymbol[]
└── IndexedRelation[]
```

La provenance est conservée sur les symboles et relations.

## 2. JavaParser — baseline embarquée

`JavaParserLanguageAnalyzer` reste l'analyseur Java structurel disponible sans installation externe.

Il fournit notamment :

- classes ;
- interfaces ;
- records ;
- enums ;
- annotations ;
- méthodes/constructeurs ;
- signatures ;
- positions ;
- imports.

Provenance par défaut :

```text
sourceProvider = javaparser
```

## 3. SCIP — index opportuniste

`ScipCodeIndexImporter` implémente `CodeIndexImporter`.

À l'indexation, NEXUS cherche :

```text
<projectRoot>/index.scip
```

S'il existe, le snapshot est importé. S'il disparaît, l'ancien snapshot SCIP est purgé.

NEXUS ne lance pas `scip-java` et ne rend pas SCIP obligatoire.

Le mapping reste conservateur : les kinds sans équivalent fiable dans le modèle NEXUS ne sont pas inventés.

## 4. JDT Language Server — analyse profonde opt-in

`JdtLanguageServerCodeIntelligenceProvider` implémente `CodeIntelligenceProvider`.

Il est composé lorsque l'environnement JDT LS est configuré et n'est exécuté que sur demande explicite :

```powershell
nexus index mon-projet --deep-java
```

Le provider peut enrichir les symboles/relations Java au-delà de JavaParser.

Il reste opt-in car la validation de l'Itération 9 a mesuré un coût très supérieur au chemin normal.

Documentation dédiée : [`jdt-language-server.md`](jdt-language-server.md).

## 5. MINOS — contrat JSON local explicite

MINOS n'est ni un `CodeIndexImporter` automatique ni un processus enfant.

Le flux est :

```text
MINOS Java 24
  nexus-export --root <project>
        │
        │ JSON
        ▼
NEXUS Java 21
  minos-import <project> < stdin
        │
        ▼
IndexRepository
```

Invariants :

- `contractVersion=1` ;
- `producer=MINOS` ;
- racine canonique identique ;
- payload ≤ 128 MiB ;
- chemins relatifs validés ;
- mapping conservateur ;
- `sourceProvider=minos` ;
- aucun type `com.minos` dans NEXUS ;
- aucun `ProcessBuilder` MINOS.

L'intégration issue #11 / PR #12 est terminée et validée.

Documentation dédiée : [`minos-code-intelligence.md`](minos-code-intelligence.md).

## 6. Multi-langage

Le scanner reconnaît nativement :

```text
Java
Markdown
Kotlin
TypeScript
JavaScript
Python
SQL
```

Seul Java possède aujourd'hui un analyseur structurel embarqué dédié. Les autres langages restent indexés lexicalement et peuvent recevoir des faits structurels via SCIP, MINOS ou un futur provider justifié par mesure.

Voir [`multi-language.md`](multi-language.md).

## 7. Persistance et fusion

SQLite conserve :

```text
symbols.source_provider
symbol_relations.source_provider
symbol_relations.confidence
```

Le remplacement d'un snapshot externe ne supprime que les données du provider concerné.

Les données externes ne créent pas artificiellement de fichier canonique absent de `indexed_files`.

La fusion vise à préserver les faits embarqués tout en ajoutant les informations externes sans doublons exacts inutiles.

## 8. Recherche

Les symboles de tous les providers compatibles peuvent participer à `SymbolSearchStrategy`.

Le graphe utilise les relations `IMPORTS` connues pour rapprocher des fichiers structurellement liés.

Les tools `find_symbol` et `find_usages` exposent également la provenance des données.

Limite de scale actuelle : les recherches symboliques/usages et la construction du graphe chargent encore des ensembles projet-wide. Leur remplacement par des requêtes repository ciblées est planifié en Itération 19.

## 9. Confiance et provenance

`SymbolRelation` porte une confiance normalisée entre `0` et `1`.

Les relations factuelles de providers qui ne fournissent pas de probabilité utilisent généralement `1.0`. Les données dérivées MINOS sans confiance explicite sont rejetées plutôt que promues arbitrairement.

NEXUS ne doit pas perdre la provenance lors d'une fusion ou d'une exposition par REST/MCP.

## 10. Validation

Les protections couvrent notamment :

- import SCIP absent/présent ;
- mapping/déduplication SCIP ;
- JDT LS opt-in ;
- multi-langage lexical ;
- contrat MINOS ;
- chemins MINOS invalides ;
- replay réel MINOS → NEXUS ;
- provenance des symboles/relations.

Les scripts spécialisés restent sous `scripts/validate-iteration-*.ps1` et `scripts/validate-minos-integration.ps1`.

## 11. Limites et prochain travail

Les prochaines évolutions ne consistent pas à ajouter immédiatement un nouveau parser :

1. rendre les requêtes symboles/relations ciblées ;
2. éviter le rebuild complet du graphe à chaque recherche ;
3. centraliser la composition des providers ;
4. conserver les providers lourds optionnels et bornés ;
5. mesurer tout nouveau provider avant adoption.

Voir [`current-limitations.md`](current-limitations.md) et la Phase 6 de la [`roadmap`](../roadmap.md).
