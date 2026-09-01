# Code Intelligence dans NEXUS

Ce document décrit la Code Intelligence **courante** de NEXUS 0.2.0. NEXUS conserve son propre modèle (`CodeSymbol`, `SymbolRelation`) et traite JavaParser, SCIP, JDT Language Server et MINOS comme des sources de faits.

## Contrats

```text
LanguageAnalyzer
→ analyse syntaxique/structurelle locale embarquée

CodeIndexImporter
→ import opportuniste d'un index déjà produit

CodeIntelligenceProvider
→ analyse profonde explicitement activée

MINOS
→ import JSON local explicite fourni par l'appelant
```

Les résultats convergent vers `CodeIntelligenceSnapshot` et conservent leur provenance.

## JavaParser — baseline embarquée

`JavaParserLanguageAnalyzer` fournit sans installation externe : classes, interfaces, records, enums, annotations, méthodes/constructeurs, signatures, positions et imports.

Le parcours AST cible directement les catégories nécessaires (`TypeDeclaration`, méthodes, constructeurs, imports) au lieu d'énumérer tous les `Node` du fichier.

```text
sourceProvider = javaparser
```

## SCIP — import opportuniste borné et confiné

`ScipCodeIndexImporter` cherche un `index.scip` sûr sous la racine canonique. NEXUS ne lance pas `scip-java`.

Les sources référencées par SCIP sont relues via `ProjectPathGuard`. Traversal, symlink final/ancêtre, source absente et plages incohérentes sont traités fail-closed. Le parseur protobuf utilise des vérifications de bornes résistantes aux overflows avant toute lecture.

Le mapping reste conservateur : un kind sans équivalent fiable n'est pas inventé.

## JDT Language Server — analyse profonde opt-in

`JdtLanguageServerCodeIntelligenceProvider` n'est exécuté qu'avec :

```powershell
nexus index mon-projet --deep-java
```

Il fournit références, implémentations, hiérarchies de types et d'appels. Le transport JSON-RPC/LSP est borné : messages 16 MiB, headers 64 KiB, lignes de header 8 KiB et file entrante 256 messages maximum.

Les tâches externes sont en plus bornées en temps et en concurrence globale (8 workers actifs maximum). Voir [`jdt-language-server.md`](jdt-language-server.md).

```text
sourceProvider = jdtls
```

## MINOS — contrat JSON local explicite

MINOS n'est ni un importer automatique ni un processus enfant NEXUS.

```text
MINOS Java 24
  nexus-export --root <project>
        │ JSON
        ▼
NEXUS Java 21
  minos-import <project> < stdin
```

Invariants :

- `contractVersion=1` ;
- `producer=MINOS` ;
- racine canonique identique ;
- payload <= 128 MiB ;
- chemins relatifs validés ;
- mapping conservateur ;
- `sourceProvider=minos` ;
- aucun type `com.minos` dans NEXUS ;
- aucun `ProcessBuilder` MINOS.

## Multi-langage

Le scanner reconnaît nativement Java, Markdown, Kotlin, TypeScript, JavaScript, Python et SQL. Java possède l'analyseur structurel embarqué dédié ; les autres langages restent indexés lexicalement et peuvent être enrichis par un provider/importer explicite.

## Persistance et fusion

SQLite conserve la provenance sur `symbols` et `symbol_relations`. Le remplacement d'un snapshot externe ne supprime que les données du provider concerné et ne crée pas de fichier canonique absent de `indexed_files`.

Depuis V005, les plages de symboles sont aussi contraintes au niveau SQLite :

```text
start_line >= 1
end_line >= start_line
```

## Recherche et scale

Les recherches symboliques/usages utilisent les requêtes repository ciblées et les projections de graphe sont bornées. La documentation historique qui annonçait encore un chargement projet-wide et un futur correctif « Itération 19 » est obsolète.

La recherche lexicale Lucene borne également une requête analysée à **128 termes uniques** avant expansion sur les cinq champs de recherche, afin de rester sous le budget de clauses Lucene par défaut.

Les providers externes lourds restent optionnels et doivent rester bornés avant toute généralisation.

## Confiance et provenance

`SymbolRelation` porte une confiance normalisée entre `0` et `1`. Les faits directs de providers sans probabilité utilisent généralement `1.0`. Une donnée dérivée sans confiance acceptable est rejetée plutôt que promue arbitrairement.

CLI, REST et MCP ne doivent pas perdre cette provenance.

## Qualification

Les protections couvrent notamment :

- JavaParser et ranges ;
- SCIP absent/présent, confinement et bounds protobuf ;
- JDT LS opt-in, framing borné, queue bornée et timeout ;
- contrat/replay MINOS ;
- provenance/déduplication ;
- recherche symbolique ciblée et graphe borné ;
- requêtes Lucene à forte cardinalité.

Voir [`current-limitations.md`](current-limitations.md), [`large-scale-search.md`](large-scale-search.md) et [`jdt-language-server.md`](jdt-language-server.md).
