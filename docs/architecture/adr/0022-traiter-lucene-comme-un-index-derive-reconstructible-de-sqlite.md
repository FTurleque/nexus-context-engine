---
status: accepted
date: 2026-07-19
---

# ADR-0022 — Traiter Lucene comme un index dérivé et reconstructible de SQLite

## Contexte et problème

NEXUS utilise SQLite comme source de vérité structurelle et Lucene comme moteur de recherche. Une opération d'indexation peut donc modifier deux stockages qui ne partagent pas une transaction distribuée.

Chercher à garantir une atomicité parfaite entre SQLite et Lucene introduirait une complexité importante. À l'inverse, ignorer le risque de divergence pourrait produire des résultats de recherche incohérents après une interruption ou une erreur disque.

## Facteurs de décision

- SQLite est la source canonique ;
- Lucene est reconstructible ;
- absence de transaction distribuée ;
- récupération simple après incident ;
- indexation incrémentale ;
- cohérence observable ;
- simplicité du MVP.

## Options envisagées

- considérer SQLite et Lucene comme deux sources de vérité égales ;
- écrire Lucene avant SQLite ;
- utiliser une transaction distribuée ou un journal complexe ;
- committer d'abord l'état canonique SQLite puis mettre à jour Lucene, avec statut de synchronisation et reconstruction possible.

## Décision retenue

**Option retenue : SQLite est la source de vérité ; Lucene est un index dérivé reconstructible.**

Le flux d'indexation d'un projet suit le principe :

```text
Scan + analyse
      │
      ▼
Transaction SQLite
- fichiers
- symboles
- relations
- état d'indexation
      │
      ▼ commit
Mise à jour Lucene
      │
      ├── succès → index synchronisé
      └── échec  → marquer/retenir qu'une reconstruction est nécessaire
```

Une commande ou opération de reconstruction doit pouvoir supprimer l'index Lucene d'un projet puis le recréer à partir des fichiers et métadonnées persistés/canoniques.

Le MVP peut commencer avec une synchronisation séquentielle simple. Il doit toutefois éviter qu'un échec Lucene annule ou corrompe silencieusement l'état SQLite déjà validé.

Les documents Lucene utilisent une clé stable dérivée de `(projectId, relativePath)` afin de permettre `updateDocument` et `deleteDocuments` de manière idempotente.

### Conséquences positives

- modèle de cohérence simple à comprendre ;
- récupération possible sans transaction distribuée ;
- Lucene peut être supprimé et reconstruit ;
- SQLite reste l'autorité pour l'état du projet ;
- les mises à jour Lucene peuvent être rejouées.

### Conséquences négatives et compromis acceptés

- une fenêtre de divergence temporaire existe entre commit SQLite et commit Lucene ;
- il faut détecter ou signaler un index de recherche obsolète ;
- une reconstruction peut être coûteuse sur de gros projets ;
- la première implémentation n'offre pas une atomicité stricte multi-stockages.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Crash après commit SQLite mais avant Lucene | Moyen | Index Lucene reconstructible et état de synchronisation |
| Document Lucene orphelin après suppression d'un fichier | Élevé | Suppression explicite par clé stable lors de la réindexation |
| Mise à jour Lucene partielle | Moyen | Commit Lucene à la fin d'un lot et possibilité de rebuild |
| Rebuild trop fréquent | Faible à moyen | Réserver la reconstruction complète aux erreurs ou migrations nécessaires |

### Confirmation

- SQLite peut être inspecté sans Lucene ;
- supprimer le dossier Lucene ne détruit aucune donnée canonique ;
- un index Lucene peut être reconstruit ;
- les mises à jour utilisent une clé stable ;
- les fichiers supprimés disparaissent également de Lucene.

## Analyse détaillée des options

### Deux sources de vérité égales

**Avantages :** aucune hiérarchie conceptuelle.

**Inconvénients :** résolution de conflits difficile et état ambigu après incident.

### Écrire Lucene avant SQLite

**Avantages :** la recherche voit rapidement les nouvelles données.

**Inconvénients :** Lucene pourrait référencer des données jamais validées dans la source canonique.

### Transaction distribuée/journal complexe

**Avantages :** garanties plus fortes.

**Inconvénients :** complexité disproportionnée pour un moteur local et deux stockages embarqués.

### SQLite canonique, Lucene dérivé

**Avantages :** responsabilité claire, récupération simple et architecture robuste.

**Inconvénients :** cohérence éventuelle courte et besoin d'un mécanisme de rebuild.

## Conditions de réexamen

Réexaminer si NEXUS devient un service hautement concurrent nécessitant une cohérence de recherche stricte en temps réel, ou si un stockage unique peut satisfaire à la fois les besoins relationnels et de recherche sans perte de qualité.

## Décisions liées

- ADR-0006 — Utiliser SQLite comme source de vérité structurelle locale.
- ADR-0007 — Utiliser Apache Lucene comme index de recherche local.
