# Résultats de baseline — Itération 16

Ce document conserve les résultats mesurés pendant l'Itération 16 afin de distinguer les limites réellement observées des hypothèses de passage à l'échelle.

Les mesures sont des observations reproductibles sur la machine de validation utilisée. Elles ne constituent pas, à elles seules, des seuils universels pour Lucene ou SQLite.

## 1. Palier 1 — repository NEXUS seul

Date de validation locale : **21 juillet 2026**.

Repository mesuré :

```text
N:\workspace-dev\nexus-context-engine
```

Requête de référence :

```text
SearchService
```

Commande de mesure :

```powershell
.\scripts\measure-iteration-16-baseline.ps1 `
    -ProjectRoots @("N:\workspace-dev\nexus-context-engine") `
    -Query "SearchService"
```

Le harness utilise un `NEXUS_HOME` temporaire et n'a pas modifié le repository source.

## 2. Validation fonctionnelle

### Build complet

```text
mvn clean install
BUILD SUCCESS
117 fichiers source compilés
28 fichiers de test compilés
51 tests exécutés
0 échec
0 erreur
1 test ignoré
18,106 s
```

Le test ignoré est `LargeScaleSearchBaselineTest`, volontairement opt-in pendant le build standard.

### Self-smoke

```text
SELF-SMOKE SUCCESS
```

Mesures observées :

| Métrique | Valeur |
|---|---:|
| fichiers indexés | 232 |
| symboles | 1 208 |
| relations | 9 684 |
| indexation complète | 2 138 ms |
| indexation incrémentale sans changement | 617 ms |
| recherche explicable | 780 ms |
| contexte strict | 878 ms |
| contexte strict | 3 items, 100/180 tokens |
| contexte multi-source | 1 110 ms |
| contexte multi-source | 12 items, 1 180/1 200 tokens |
| contexte avec skill | 1 159 ms |
| contexte Git | 1 088 ms |
| commits Git inspectés | 50 |
| commits Git liés | 5 |
| fragments Git sélectionnés | 3 |
| réduction du contexte candidat strict | 99,47 % |

Les avertissements SLF4J, native access, Vector API, Maven Shade et `sun.misc.Unsafe` observés pendant la validation restent non bloquants pour ce palier.

Sous Windows PowerShell 5.1, les messages écrits sur `stderr` par Java apparaissent également sous forme de `NativeCommandError` dans la sortie du self-smoke. Le script a néanmoins poursuivi l'exécution et s'est terminé par `SELF-SMOKE SUCCESS` ; aucun échec fonctionnel n'a été observé.

## 3. Validation dédiée de la recherche fédérée

Commande :

```powershell
.\scripts\validate-iteration-16.ps1 -FocusedOnly
```

Résultat :

```text
BUILD SUCCESS
4 tests exécutés
0 échec
0 erreur
0 ignoré
```

Validation obtenue :

- recherche multi-projet : succès ;
- provenance `projectId` : validée par test ;
- corpus golden historique : succès ;
- corpus golden fédéré : succès ;
- aucun moteur externe introduit.

Qualité mesurée :

```text
mono-projet
precision@3 = 0,4444
recall@3    = 1,0000

fédéré technique
precision@3 = 0,4444
recall@3    = 1,0000
```

Le corpus fédéré actuel est un corpus technique de non-régression. Il ne remplace pas encore un corpus métier multi-repository représentatif.

## 4. Baseline de performance du harness

Rapport produit :

```text
target\iteration-16-baseline.json
```

Résultats :

| Métrique | Valeur |
|---|---:|
| repositories | 1 |
| fichiers | 232 |
| symboles | 1 208 |
| relations | 9 684 |
| taille totale index Lucene | 795 968 octets |
| indexation complète | 2 194 ms |
| indexation incrémentale sans changement | 371 ms |
| recherche fédérée p50 | 140 ms |
| recherche fédérée p95 | 162 ms |
| construction contexte p50 | 257 ms |
| construction contexte p95 | 263 ms |
| heap utilisé avant | 28 888 896 octets |
| heap utilisé après | 117 693 064 octets |
| delta heap observé | 88 804 168 octets |
| warmups recherche | 3 |
| échantillons recherche | 10 |
| warmups contexte par projet | 1 |
| échantillons contexte par projet | 3 |
| budget contexte | 1 200 tokens |

Les temps du harness et ceux du self-smoke ne sont pas directement interchangeables : le harness effectue des warmups et plusieurs échantillons, tandis que le self-smoke rapporte des opérations individuelles de bout en bout via le JAR CLI.

## 5. Interprétation du palier 1

À ce volume, aucune métrique ne démontre que Lucene est insuffisant :

- l'index local reste inférieur à 1 Mo pour 232 fichiers ;
- la recherche après échauffement reste à `p95 = 162 ms` sur le palier mesuré ;
- la construction de contexte reste à `p95 = 263 ms` dans le harness ;
- les baselines `precision@3` et `recall@3` restent inchangées ;
- l'indexation incrémentale sans changement reste nettement moins coûteuse que la reconstruction complète.

Ce résultat ne permet toutefois pas de conclure sur le passage à l'échelle : il ne couvre qu'un repository et environ 1 200 symboles.

Il ne justifie donc **ni Zoekt, ni OpenGrok, ni un index distant**.

## 6. Mesures encore nécessaires

Avant toute décision sur un moteur externe, l'Itération 16 doit encore mesurer :

1. plusieurs repositories réels dans une même recherche fédérée ;
2. des paliers croissants en nombre de fichiers, symboles et relations ;
3. l'évolution de `p50` et `p95` avec le nombre de projets sélectionnés ;
4. une indexation incrémentale avec petit delta sur une copie de travail contrôlée ;
5. un corpus métier multi-repository avec provenance `(projectId, relativePath)` ;
6. la stabilité du ranking inter-projets sur ce corpus ;
7. la mémoire sur plusieurs paliers comparables.

La décision d'évaluer Zoekt ou OpenGrok ne sera réouverte que si ces mesures montrent une limite reproductible que l'architecture locale ne peut pas corriger simplement.
