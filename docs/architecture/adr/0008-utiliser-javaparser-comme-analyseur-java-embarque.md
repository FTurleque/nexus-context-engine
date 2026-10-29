---
status: accepted
date: 2026-07-19
---

# ADR-0008 — Utiliser JavaParser comme analyseur Java embarqué du MVP

## Contexte et problème

Le premier objectif concret de NEXUS est d'analyser un repository Java local pour identifier fichiers et symboles pertinents. Le moteur doit extraire une structure fiable du code sans reposer sur des expressions régulières fragiles.

Le MVP doit toutefois rester simple à exécuter localement. Une solution nécessitant un serveur de langage, un processus natif ou une chaîne de compilation complète augmenterait fortement la complexité avant même que le moteur de contexte soit validé.

La question est : **quelle technologie utiliser comme analyseur Java embarqué par défaut pour fournir une base structurelle suffisante au MVP sans prétendre résoudre toute la sémantique Java ?**

## Facteurs de décision

- analyse AST réelle ;
- intégration directe dans une application Java ;
- fonctionnement local ;
- absence de processus externe obligatoire ;
- extraction de classes, interfaces, records, enums, méthodes et imports ;
- accès aux positions dans les fichiers pour construire des extraits ;
- coût d'intégration limité ;
- possibilité de remplacer ou enrichir l'analyseur plus tard.

## Options envisagées

- analyser le code avec des expressions régulières ;
- utiliser JavaParser ;
- utiliser Eclipse JDT directement comme analyseur principal ;
- utiliser Tree-sitter dès le MVP ;
- dépendre d'un index SCIP comme seule source d'intelligence de code.

## Décision retenue

**Option retenue : utiliser JavaParser comme implémentation embarquée par défaut de `LanguageAnalyzer` pour Java dans le MVP.**

JavaParser est responsable de l'extraction structurelle locale de base :

- classes ;
- interfaces ;
- records ;
- enums ;
- méthodes ;
- signatures ;
- imports ;
- package ;
- positions et plages de lignes.

JavaParser n'est **pas** désigné comme moteur sémantique universel de NEXUS. Il ne doit pas devenir une dépendance des contrats métier. Son résultat est normalisé dans les modèles NEXUS tels que `CodeSymbol` et `SymbolRelation`.

Les fonctions de résolution plus riches — références précises, usages, implémentations, hiérarchies — pourront être apportées par des `CodeIntelligenceProvider` distincts.

### Conséquences positives

- le MVP dispose immédiatement d'une analyse Java structurelle robuste ;
- aucune expression régulière n'est utilisée comme fondation architecturale du parsing ;
- l'analyse fonctionne dans le même processus Java ;
- les positions de symboles permettent de construire des extraits ciblés ;
- le premier test d'analyseur peut rester rapide et déterministe ;
- l'architecture reste ouverte à d'autres providers.

### Conséquences négatives et compromis acceptés

- JavaParser seul ne résout pas tous les usages et références d'un projet Java complexe ;
- la résolution complète de symboles peut nécessiter une configuration de classpath ;
- certains projets utilisant génération de code ou configurations de build complexes pourront être partiellement compris ;
- une phase d'enrichissement externe restera nécessaire pour une intelligence de code profonde.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Confondre extraction syntaxique et résolution sémantique | Élevé | Séparer `LanguageAnalyzer` et `CodeIntelligenceProvider` |
| Couplage des modèles métier aux types JavaParser | Élevé | Mapper immédiatement vers les modèles NEXUS |
| Parsing de sources Java non standards ou incomplètes | Moyen | Enregistrer les erreurs par fichier et poursuivre l'indexation lorsque possible |
| Utilisation croissante de fonctionnalités JavaParser spécifiques | Moyen | Encapsuler l'implémentation dans un package/adaptateur dédié |

### Confirmation

La décision est respectée si :

- `JavaParserLanguageAnalyzer` implémente un contrat NEXUS ;
- aucun type JavaParser n'apparaît dans `ContextBundle` ou les modèles de domaine ;
- les tests vérifient l'extraction des catégories de symboles prévues ;
- un échec d'analyse d'un fichier ne rend pas conceptuellement impossible l'indexation du reste du repository ;
- les besoins sémantiques avancés ne sont pas ajoutés arbitrairement à `LanguageAnalyzer`.

## Analyse détaillée des options

### Analyser avec des expressions régulières

**Avantages :**

- implémentation initiale très rapide ;
- aucune dépendance externe.

**Inconvénients :**

- fragile face à la syntaxe réelle de Java ;
- gestion difficile des imbrications, annotations, generics et commentaires ;
- base non fiable pour un moteur de contexte.

### Utiliser JavaParser

**Avantages :**

- AST Java dédié ;
- bibliothèque embarquable ;
- bonne ergonomie pour l'extraction structurelle ;
- adapté au MVP local.

**Inconvénients :**

- couverture sémantique profonde limitée sans mécanismes supplémentaires ;
- spécifique au langage Java.

### Utiliser Eclipse JDT directement comme analyseur principal

**Avantages :**

- intelligence Java riche ;
- capacités avancées de résolution.

**Inconvénients :**

- intégration plus lourde ;
- complexité disproportionnée pour la première tranche ;
- risque de coupler tôt le MVP à un environnement de projet plus complexe.

### Utiliser Tree-sitter dès le MVP

**Avantages :**

- stratégie multi-langage cohérente ;
- parsing incrémental efficace.

**Inconvénients :**

- introduit des contraintes de bindings/runtime ;
- ne fournit pas à lui seul toute l'intelligence sémantique ;
- le MVP ne nécessite qu'un langage initial.

### Dépendre uniquement de SCIP

**Avantages :**

- possibilité de réutiliser des relations déjà résolues ;
- potentiel multi-langage.

**Inconvénients :**

- nécessite qu'un index SCIP soit disponible ou générable ;
- rendrait le fonctionnement de base dépendant d'un outil externe ;
- incompatible avec l'exigence d'un analyseur local embarqué toujours disponible.

## Impacts sur l'architecture

```text
Source Java
    │
    ▼
LanguageAnalyzer
    │
    ▼
JavaParserLanguageAnalyzer
    │
    ▼
CodeSymbol / SymbolRelation
    │
    ▼
SQLite + Lucene
```

## Conditions de réexamen

Réexaminer JavaParser comme implémentation par défaut si :

- les corpus montrent un taux d'échec structurel significatif ;
- un autre analyseur Java apporte un gain net avec un coût opérationnel comparable ;
- le projet devient multi-langage au point qu'une stratégie commune plus efficace s'impose.

Le contrat `LanguageAnalyzer` doit survivre au remplacement éventuel de JavaParser.

## Décisions liées

- ADR-0002 — Compiler le cœur avec Java 21 comme niveau minimal.
- ADR-0009 — Rendre l'intelligence de code extensible via des providers et index externes.
