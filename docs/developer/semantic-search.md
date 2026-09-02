# Recherche sémantique optionnelle

## Statut

La capacité sémantique a été livrée à l'Itération 17 et reste **strictement opt-in**. Phase 6 rend son activation opérationnelle identique pour CLI, REST et MCP et réduit le coût d'indexation via batching.

Sans configuration :

```java
NexusApplication.create(paths)
```

résout `SemanticSearchConfiguration.fromEnvironment()` en mode désactivé. Aucun provider d'embeddings ni index sémantique n'est créé.

## Activation commune

```powershell
$env:NEXUS_SEMANTIC_PROVIDER = "ollama"
```

Variables :

```text
NEXUS_SEMANTIC_PROVIDER                 ollama | disabled/off
NEXUS_SEMANTIC_RRF_WEIGHT               8.0 par défaut, <= 10
NEXUS_OLLAMA_BASE_URL                   http://localhost:11434 par défaut
NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA      false par défaut
NEXUS_OLLAMA_EMBEDDING_MODEL            qwen3-embedding:0.6b par défaut
NEXUS_OLLAMA_EMBEDDING_DIMENSIONS       1024 par défaut
NEXUS_OLLAMA_TIMEOUT_SECONDS            60 par défaut
```

L'activation programmable reste disponible avec `SemanticSearchConfiguration.enabled(...)` pour les tests/intégrations spécialisées.

### Politique transport Ollama

Une URL HTTP est autorisée sans opt-in uniquement lorsqu'elle cible une adresse de bouclage (`localhost`, `127.0.0.0/8`, `::1`). Un endpoint distant doit utiliser HTTPS.

L'exception volontaire pour un environnement administré exige :

```text
NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true
```

Cette option doit rester exceptionnelle : elle autorise le transport du contenu d'embedding sur HTTP. Les credentials intégrés directement dans l'URI Ollama sont refusés.

En runtime Docker, une URL de bouclage configurée est adaptée vers `host.docker.internal` après validation de la politique ; une URL distante n'est jamais réécrite.

## Protection des secrets

Avant chaque embedding, `SemanticIndexingService` applique une redaction conservatrice des secrets à forte confiance : clés privées, tokens structurés connus, JWT, affectations évidentes de mots de passe/secrets et credentials d'URI. La redaction est également appliquée aux excerpts sémantiques.

Le profil d'index sémantique est passé à `content-v2`. Un index créé avec l'ancien profil n'est donc pas considéré compatible : la prochaine indexation reconstruit les vecteurs afin de ne pas conserver silencieusement des embeddings historiques issus d'un contenu non redigé.

Cette protection complète, sans la remplacer, la politique d'exclusion des chemins sensibles du scanner.

## Pipeline

```text
indexation
SearchDocument[]
  ↓ redaction secrets à forte confiance
  ↓ lots de 32 par défaut
EmbeddingProvider.embedAll(...)
  ↓
SemanticSearchIndex (Lucene dérivé)

recherche
lexical + symbole + graphe/Git + sémantique
  ↓
SemanticHybridContextRanker
  ↓ RRF k=60, poids sémantique 8 par défaut
résultats explicables
```

`EmbeddingProvider.embedAll` possède un fallback séquentiel pour les providers existants. `OllamaEmbeddingProvider` l'implémente réellement en envoyant plusieurs textes dans une requête `/api/embed`.

### Borne de réponse Ollama

`OllamaEmbeddingProvider` ne matérialise plus directement la réponse HTTP avec `BodyHandlers.ofString()`. Le body est reçu comme `InputStream`, lu avec une limite stricte, puis seulement converti en UTF-8 et parsé en JSON.

Le plafond par défaut est **1 MiB (1 048 576 octets)**. Le lot par défaut contient au plus 32 entrées et un embedding est borné à 1024 dimensions, soit 32 768 floats. En comptant environ 16 octets par valeur JSON (valeur, signe/exposant éventuel et séparateur), cette forme représente environ 512 KiB ; 1 MiB fournit donc une marge supérieure à 2× pour les crochets et métadonnées Ollama sans rendre la protection symbolique.

La lecture consomme au maximum `limite + 1` octets :

- exactement à la limite : réponse acceptée ;
- un octet au-dessus : `IOException` explicite avec provider, opération `/api/embed`, endpoint et limite ;
- réponse chunked ou sans `Content-Length` : même contrôle, car la borne porte sur les octets réellement lus ;
- réponse HTTP non-2xx : body soumis à la même borne avant abréviation du message d'erreur ;
- aucun méga-body n'est inclus dans l'exception d'overflow.

Cette limite n'est pas exposée comme configuration opérationnelle : elle reste une politique interne cohérente avec les bornes de batching/dimensions, ce qui évite qu'une configuration arbitrairement élevée neutralise la protection.

## Dégradation et récupération

La recherche sémantique est un signal optionnel. Une panne **I/O** du provider d'embeddings ou du Lucene sémantique ne doit donc pas rendre indisponibles les stratégies lexicales et symboliques déjà cohérentes.

En recherche :

- indisponibilité/timeout Ollama → le signal sémantique retourne zéro candidat pour cette requête et la recherche continue avec les autres stratégies ;
- erreur I/O de lecture/compatibilité du Lucene sémantique → même fallback ;
- un diagnostic stable est journalisé avec `code=embedding_provider_unavailable` ou `code=semantic_index_unavailable` ;
- une erreur de contrat, par exemple un vecteur de dimension incorrecte, reste bloquante : elle ne doit pas être masquée comme indisponibilité transitoire.

Le retry Ollama est automatique à la requête suivante : aucun circuit ouvert persistant n'est conservé. Une instance de provider qui reçoit temporairement HTTP 503 ou dépasse son timeout peut donc reprendre sans redémarrer NEXUS dès que le service répond à nouveau.

L'**indexation**, elle, reste fail-closed. Si la construction des embeddings ou de l'index dérivé échoue, le projet passe `FAILED` et n'est pas publié `READY`. Cette règle protège la cohérence canonique ; la dégradation sémantique ne s'applique qu'à la lecture d'un projet déjà READY.

### Procédure normale pour un index sémantique corrompu

La première action est toujours une reconstruction explicite :

```text
nexus index <id-ou-nom> --rebuild
```

Le Lucene sémantique est dérivé de l'index canonique et des fichiers du repository. Un rebuild utilise un `OpenMode.CREATE` et remplace son contenu ; la qualification comprend une fixture qui corrompt physiquement les fichiers de commit Lucene puis vérifie qu'un rebuild restaure une recherche valide.

### Mise en quarantaine manuelle — dernier recours

Ne supprimer ni `nexus.db` ni l'index lexical. Si un problème de filesystem empêche même le rebuild du répertoire dérivé :

1. arrêter **tous** les processus NEXUS utilisant le même `NEXUS_HOME` ;
2. identifier l'UUID exact du projet et le répertoire `${NEXUS_HOME}/indexes/<uuid>/semantic-lucene` ;
3. vérifier que ce chemin reste sous `NEXUS_HOME`, qu'aucun composant n'est un lien symbolique et qu'il s'agit bien du seul répertoire `semantic-lucene` ciblé ;
4. renommer ce répertoire en quarantaine, par exemple `semantic-lucene.quarantine`, plutôt que de le supprimer immédiatement ;
5. relancer `nexus index <uuid> --rebuild` ;
6. vérifier que le projet revient `READY` et qu'une recherche fonctionne ;
7. seulement après cette validation, supprimer la quarantaine.

Cette procédure conserve une possibilité de rollback opérateur et limite l'action manuelle au cache vectoriel reconstructible.

## Baseline de décision

Corpus hermétique historique : 236 fichiers, 946 symboles, 1 539 relations, 6 requêtes.

```text
baseline lexical top-3 : tous les indicateurs sémantiques à zéro
semantic precision@3  : 0,1667
semantic recall@3     : 0,4167
semantic hit@3        : 0,5000
semantic MRR@3        : 0,3056
indexation            : 1 943 ms → 64 332 ms (~33,11×)
recherche             : 208,8 ms → 298,7 ms (~1,43×)
```

Cette baseline justifie toujours :

- sémantique désactivé par défaut ;
- pas de vector DB ;
- pas de promotion automatique du sémantique en moteur principal ;
- optimisation mesurée avant toute complexification supplémentaire.

Phase 6 implémente le batching, mais une nouvelle mesure locale est nécessaire avant de déclarer le coût réduit de manière chiffrée.

## Correctness

Le mode sémantique respecte les mêmes gates `READY` que le lexical. Si une indexation sémantique échoue, le projet ne doit pas être servi comme cohérent ; la prochaine indexation d'un état non-READY force un rebuild complet.
