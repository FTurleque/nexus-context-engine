# Recherche sémantique optionnelle — Itération 17

## Objectif

Mesurer si une stratégie de recherche sémantique améliore réellement la qualité de recherche et du contexte NEXUS par rapport au socle lexical + symbolique + graphe, sans rendre les embeddings obligatoires.

Cette itération applique l'ADR-0014 : la recherche sémantique reste **désactivée par défaut**, local-first lorsque possible, et ne sera conservée durablement que si les métriques montrent un gain utile.

## Invariants

- `NexusApplication.create(paths)` continue de fonctionner sans modèle d'embeddings, sans réseau et sans stockage vectoriel supplémentaire obligatoire.
- La recherche lexicale, symbolique et graphe reste le chemin complet de repli.
- Aucun contenu de repository n'est envoyé à un fournisseur externe sans activation explicite.
- L'identité et la version du modèle d'embeddings doivent être observables dans les mesures.
- Le ranking reste déterministe pour une configuration, un corpus et un jeu de vecteurs donnés.
- L'activation sémantique doit pouvoir être comparée A/B à la baseline non sémantique sur le même corpus.
- Aucune base vectorielle dédiée n'est introduite tant que Lucene ou une abstraction locale simple suffit.

## Architecture cible

```text
SearchService
├── LuceneFileSearchStrategy
├── SymbolSearchStrategy
└── SemanticSearchStrategy             optionnelle
        │
        ├── EmbeddingProvider          port
        │     ├── provider local       optionnel
        │     └── provider externe     opt-in uniquement
        │
        └── SemanticSearchIndex        port
              └── index vectoriel local dérivé
```

Le résultat de la stratégie sémantique rejoint les `SearchCandidate` existants au moyen d'un signal `semanticScore`. Le ranking décide de sa contribution avec un poids explicite et explicable. Sans stratégie sémantique configurée, ce signal est absent et le comportement historique est inchangé.

## Incréments

### 1. Contrats et signal de ranking

- introduire `EmbeddingProvider` ;
- introduire `SemanticSearchIndex` ;
- introduire `SemanticSearchStrategy` ;
- ajouter le signal `semanticScore` au ranking ;
- garantir par test que le chemin par défaut reste inchangé lorsque la sémantique n'est pas configurée.

### 2. Index vectoriel local dérivé

- stocker les vecteurs dans un index local reconstructible par projet ;
- supporter reconstruction complète et mise à jour incrémentale ;
- ne jamais faire de l'index vectoriel une source canonique ;
- conserver SQLite comme source de vérité structurelle.

### 3. Provider d'embeddings explicitement activé

Le premier provider réel doit être choisi pour permettre une mesure reproductible et sans dépendance obligatoire. Un runtime local est préféré pour la baseline ; un provider externe éventuel doit rester strictement opt-in.

### 4. Corpus sémantique et benchmark A/B

Le benchmark doit contenir des requêtes où le vocabulaire attendu diffère volontairement du vocabulaire des documents pertinents. Mesures minimales :

- `precision@3` ;
- `recall@3` ;
- `hit@3` ;
- `MRR@3` ;
- qualité du contexte final sous budget ;
- latence d'indexation ;
- latence de recherche ;
- taille de l'index vectoriel ;
- mémoire observée ;
- coût financier éventuel ;
- volume de données envoyé hors machine, qui doit rester nul pour une baseline locale.

## Critère d'adoption

La recherche sémantique n'est pas adoptée par défaut simplement parce qu'elle fonctionne techniquement.

Elle ne sera conservée comme capacité recommandée que si le benchmark montre un gain mesurable sur des requêtes réellement sémantiques sans dégradation disproportionnée de la précision, de la latence, du stockage ou de la confidentialité.

Dans le cas contraire, l'itération pourra conclure rationnellement que le socle lexical + symbolique + graphe reste préférable et la capacité sémantique restera expérimentale ou sera retirée.
