---
status: accepted
date: 2026-07-19
---

# ADR-0009 — Rendre l'intelligence de code extensible via des providers et index externes

## Contexte et problème

JavaParser fournit une bonne base structurelle pour le MVP, mais NEXUS vise à terme des besoins plus riches : définitions, références, usages, implémentations, hiérarchies de types, hiérarchies d'appels et support multi-langage.

Réimplémenter toutes ces capacités dans NEXUS demanderait un effort considérable et reproduirait des outils déjà matures. À l'inverse, rendre un outil externe unique obligatoire ferait perdre l'autonomie locale et le contrôle de l'architecture.

La question est : **comment enrichir progressivement l'intelligence de code sans enfermer NEXUS dans JavaParser ni dans un fournisseur externe unique ?**

## Facteurs de décision

- réutilisation de standards et index existants ;
- maintien d'un fonctionnement de base sans dépendance externe ;
- support futur de plusieurs langages ;
- possibilité de fusionner des informations provenant de plusieurs sources ;
- traçabilité de la provenance et du niveau de confiance ;
- isolation des dépendances lourdes ;
- capacité à mesurer le gain réel d'un provider avant adoption.

## Options envisagées

- étendre JavaParser jusqu'à couvrir toute l'intelligence sémantique ;
- choisir SCIP comme modèle interne et dépendance obligatoire ;
- choisir JDT LS comme backend Java unique ;
- choisir Tree-sitter comme fondation commune à tous les langages ;
- introduire des ports `CodeIntelligenceProvider` et `CodeIndexImporter`, avec plusieurs implémentations optionnelles.

## Décision retenue

**Option retenue : introduire des ports `CodeIntelligenceProvider` et `CodeIndexImporter`, avec plusieurs implémentations optionnelles et un modèle métier NEXUS indépendant.**

Les responsabilités sont séparées :

```text
LanguageAnalyzer
→ extraction syntaxique et structurelle embarquée

CodeIntelligenceProvider
→ définitions, références, usages, implémentations, hiérarchies

CodeIndexImporter
→ import d'un index produit par un outil externe
```

NEXUS conserve comme modèle interne des concepts tels que :

- `CodeSymbol` ;
- `SymbolRelation` ;
- provenance (`sourceProvider`) ;
- niveau de confiance lorsque nécessaire.

Les orientations actuelles sont :

- **SCIP** : priorité d'évaluation pour importer une intelligence de code portable et multi-langage ;
- **scip-java** : candidat pour enrichir Java/Scala/Kotlin lorsqu'un index est disponible ;
- **Eclipse JDT Language Server** : provider Java profond optionnel pour les projets complexes ;
- **Tree-sitter** : option future pour l'analyse multi-langage, lorsque son coût et ses contraintes sont justifiés ;
- analyseurs spécifiques : autorisés lorsqu'ils sont plus adaptés à un langage donné.

Aucun de ces outils n'est obligatoire pour le MVP.

### Conséquences positives

- NEXUS peut réutiliser des index existants au lieu de tout réimplémenter ;
- le cœur reste indépendant de SCIP, JDT ou Tree-sitter ;
- plusieurs providers peuvent être combinés ;
- la provenance des relations peut être conservée ;
- le support multi-langage peut progresser sans modifier `ContextBuilder` ;
- l'adoption d'un provider peut être conditionnée à un gain mesurable.

### Conséquences négatives et compromis acceptés

- la fusion de plusieurs sources d'intelligence de code devient un problème à résoudre ;
- des relations contradictoires ou dupliquées peuvent apparaître ;
- les providers externes peuvent avoir des modèles de symboles différents ;
- certains providers nécessiteront des processus ou dépendances lourdes ;
- un niveau de confiance et une stratégie de priorité peuvent devenir nécessaires.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Couplage du modèle interne au format SCIP | Élevé | Mapper SCIP vers `CodeSymbol` / `SymbolRelation` |
| Données contradictoires entre providers | Élevé | Conserver provenance, confiance et règles de fusion explicites |
| Provider lourd activé pour tous les projets | Moyen | Activation optionnelle ou à la demande |
| Complexité multi-langage prématurée | Moyen | Java reste le chemin principal du MVP ; ajouter les providers après mesure |
| Dépendance externe indisponible | Moyen | Maintenir JavaParser comme capacité embarquée de base |

### Confirmation

La décision est respectée si :

- les contrats `CodeIntelligenceProvider` / `CodeIndexImporter` ne dépendent pas de formats externes ;
- un index SCIP est converti vers les modèles NEXUS ;
- l'absence de SCIP ou JDT n'empêche pas le MVP de fonctionner ;
- la provenance des relations enrichies peut être inspectée ;
- l'ajout d'un nouveau langage ne modifie pas le contrat du `ContextBuilder`.

## Analyse détaillée des options

### Étendre JavaParser jusqu'à couvrir toute l'intelligence sémantique

**Avantages :**

- une seule bibliothèque Java ;
- intégration homogène.

**Inconvénients :**

- réimplémentation de capacités complexes ;
- dépendance forte à une technologie Java ;
- mauvaise stratégie pour le multi-langage.

### Choisir SCIP comme modèle interne obligatoire

**Avantages :**

- protocole dédié à l'intelligence de code ;
- indexeurs disponibles pour plusieurs langages ;
- définitions et références normalisées.

**Inconvénients :**

- le modèle métier de NEXUS deviendrait dépendant d'un standard externe ;
- génération d'index pas toujours disponible ;
- toutes les sources de contexte NEXUS ne sont pas des concepts SCIP.

### Choisir JDT LS comme backend Java unique

**Avantages :**

- intelligence Java très riche ;
- compréhension des projets Maven/Gradle complexes.

**Inconvénients :**

- plus lourd opérationnellement ;
- spécifique à Java ;
- processus et cycle de vie plus complexes.

### Choisir Tree-sitter comme fondation commune

**Avantages :**

- large couverture de langages ;
- parsing performant.

**Inconvénients :**

- intelligence sémantique limitée sans couches supplémentaires ;
- contraintes de bindings et runtime ;
- une abstraction unique ne garantit pas la meilleure solution pour chaque langage.

### Introduire des providers et importers indépendants

**Avantages :**

- extensibilité ;
- réutilisation des meilleures briques disponibles ;
- indépendance du cœur ;
- adoption progressive et mesurable.

**Inconvénients :**

- nécessité d'un modèle de normalisation et de fusion ;
- davantage de contrats à concevoir.

## Impacts sur l'architecture

```text
                 Modèle NEXUS
        CodeSymbol / SymbolRelation
                   ▲
                   │ normalisation
      ┌────────────┼────────────┐
      │            │            │
 JavaParser       SCIP         JDT
 embarqué       importer     provider
      │
 Tree-sitter / autres providers futurs
```

## Conditions de réexamen

La priorité d'un provider spécifique doit être réévaluée si :

- sa maintenance s'arrête ;
- sa licence ou son mode d'intégration devient incompatible ;
- un autre provider offre une qualité nettement supérieure ;
- les métriques montrent que son enrichissement n'améliore pas le contexte.

L'abstraction multi-provider reste la décision fondamentale même si les implémentations changent.

## Décisions liées

- ADR-0008 — Utiliser JavaParser comme analyseur Java embarqué du MVP.
- ADR-0010 — Adopter un ranking hybride, déterministe et explicable.
