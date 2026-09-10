# Construction du contexte et gestion des budgets

Ce chapitre décrit les contrats courants de `DefaultContextBuilder` et de la fédération après NXA3 + NXA4.

## Validation de requête

`ContextRequest` valide le budget, copie défensivement les sources/contraintes et refuse explicitement toute map `constraints` non vide :

```text
constraints are not supported yet
```

Le champ reste présent pour compatibilité de contrat, mais une contrainte non implémentée n'est jamais ignorée silencieusement.

## Deux niveaux de bornes

NEXUS borne le travail **avant** et **après** la découverte :

1. `ContextDiscoveryLimits` limite visites, candidats, octets et durée des sources natives/Git avant sélection ;
2. le budget de tokens limite le `ContextBundle` final.

Defaults :

```text
visited entries  100000
candidates       5000
bytes            32 MiB
elapsed          15 s
```

Voir [`native-context-discovery-limits.md`](native-context-discovery-limits.md).

## Pipeline mono-projet

```text
ContextRequest validé
  ↓
SearchService
  ↓
instructions / skills / Git
  │  même ContextDiscoveryBudget
  ↓
fragments + déduplication
  ↓
SensitiveContentRedactor
  ↓
BudgetedContextSelector
  ↓
ContextBundle
```

Invariant : `estimatedTokens <= tokenBudget`.

Les fichiers sont lus via les frontières filesystem durcies. Les ressources de skills sont inventoriées, jamais exécutées automatiquement.

`ContextFragmentFactory` redige les secrets à forte confiance dans le contenu source avant de construire les fragments exposés. La redaction des blocs multilignes conserve les séparateurs de lignes afin de maintenir l'alignement avec les ranges persistés.

## Recherche sémantique

Lorsque le sémantique est activé, `SemanticIndexingService` applique la même redaction avant l'appel au provider d'embeddings. Le profil `content-v2` force la reconstruction d'un index historique incompatible.

Voir [`semantic-search.md`](semantic-search.md).

## Git

Git est local/read-only et soumis au budget de découverte partagé. Historique, chemins et patch sont bornés avant le sélecteur de tokens ; le diff utilise `BoundedOutput` à capacité fixe.

## Fédération : ordre de validation

```text
sélecteurs explicites
  ↓
normalisation / UUID
  ↓
cardinalité canonique <= 100 projets uniques
  ↓
résolution projet
  ↓
gate READY
  ↓
service fédéré
```

La limite de 100 uniques est fail-fast : le 101e UUID unique provoque l'erreur de portée avant les lookups/readiness ultérieurs.

## FederatedContextService

Pour une portée valide et READY :

- budget global ;
- budget de travail préparatoire ;
- fair floor ;
- provenance projet ;
- round-robin/refill ;
- déduplication inter-projet ;
- metadata de starvation.

Les sources natives sont évaluées dans leur projet d'origine. Une instruction, un skill ou un fragment Git n'est jamais propagé implicitement d'un projet à un autre.

Les limites REST de budget contexte fédéré réutilisent `ContextBudgetPolicy` ; elles ne définissent pas un plafond parallèle.

## Métadonnées et validation

Le bundle explicable expose notamment les métriques de découverte, sélection, déduplication, Git, skills et budget de travail.

Validation :

- `ContextDiscoveryLimitsTest` : frontière exacte et N+1 ;
- `NativeContextDiscoveryBudgetBenchmarkTest` : 1 000 skills filesystem ;
- tests fédérés application/CLI : cardinalité avant résolution ;
- tests REST : limites centrales + rejet de `constraints` ;
- tests `SensitiveContentRedactor` et fragments : secrets non exposés et lignes préservées ;
- benchmark fédéré : 100 projets et budget de travail global ;
- `ContextMaterializationBudgetTest` : limites d'octets, d'ouvertures physiques et de durée.

Les mêmes contrats métier sont réutilisés par CLI, REST et MCP.

## Budget physique de matérialisation

Les fichiers candidats du contexte de tâche partagent un budget d'I/O physique par requête. Trois dimensions sont bornées indépendamment :

```text
NEXUS_MAX_CONTEXT_MATERIALIZATION_BYTES   64 MiB par défaut   512 MiB maximum
NEXUS_MAX_CONTEXT_MATERIALIZATION_FILES   10000 par défaut    100000 maximum
NEXUS_MAX_CONTEXT_MATERIALIZATION_MILLIS  30000 par défaut    300000 maximum
```

Le plafond de fichiers est consommé avant chaque ouverture physique, y compris pour un fichier vide. La deadline est contrôlée avant l'ouverture et pendant la lecture par blocs ; elle borne donc le travail cumulé et empêche qu'un grand nombre de petits fichiers échappe au seul budget d'octets.

En contexte fédéré, une seule instance de `ContextMaterializationBudget` est partagée par tous les projets : augmenter le nombre de projets ne multiplie ni le volume maximal lu, ni le nombre maximal d'ouvertures, ni la durée de matérialisation autorisée.
