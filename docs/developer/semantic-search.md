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

Le profil d'index sémantique est `content-v5` et porte la version de politique `secret-redaction-v6`. Un index créé avec un autre profil n'est pas considéré compatible : la prochaine indexation reconstruit les vecteurs afin de ne pas conserver silencieusement des embeddings issus d'un contenu redigé par une politique antérieure. `SemanticIndexingService` redige chaque document une seule fois avant normalisation, troncature et envoi au provider ; le pipeline d'indexation lui transmet un contenu déjà redigé, et cette seconde barrière est volontaire ; un document qu'il est impossible de rediger (valeur sensible de plus de 4 096 caractères) n'est pas envoyé.

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

La recherche sémantique est un signal optionnel. Une indisponibilité transitoire du provider d'embeddings ou une panne I/O du Lucene sémantique ne doit donc pas rendre indisponibles les stratégies lexicales et symboliques déjà cohérentes.

En recherche :

- timeout, connexion impossible, HTTP 429 ou HTTP 5xx Ollama → `EmbeddingProviderUnavailableException`, zéro candidat sémantique pour cette requête et poursuite avec les autres stratégies ;
- erreur I/O de lecture/compatibilité du Lucene sémantique → même fallback ;
- un diagnostic stable est journalisé avec `code=embedding_provider_unavailable` ou `code=semantic_index_unavailable` ;
- une erreur de protocole ou de contrat, par exemple JSON invalide, dimension incorrecte ou valeur non finie, reste une `IOException` bloquante : elle ne doit pas être masquée comme indisponibilité transitoire.

Le retry Ollama est automatique à la requête suivante : aucun circuit ouvert persistant n'est conservé. Une instance de provider qui reçoit temporairement HTTP 503 ou dépasse son timeout peut donc reprendre sans redémarrer NEXUS dès que le service répond à nouveau.

L'**indexation**, elle, reste fail-closed. Si la construction des embeddings ou de l'index dérivé échoue, le projet passe `FAILED` et n'est pas publié `READY`. Cette règle protège la cohérence canonique ; la dégradation sémantique ne s'applique qu'à la lecture d'un projet déjà READY.

### Procédure normale pour un index sémantique corrompu

La première action est toujours une reconstruction explicite :

```text
nexus index <id-ou-nom> --rebuild
```

Le Lucene sémantique est dérivé de l'index canonique et des fichiers du repository. Un rebuild normal utilise `OpenMode.CREATE` sans supprimer préalablement les fichiers : Lucene conserve la progression des générations et les lecteurs indépendants voient le nouveau commit. Une erreur pendant la construction ne publie pas les documents partiellement ajoutés.

Si le commit `segments_*` est corrompu ou illisible, NEXUS construit un index complet dans `semantic-lucene/recovery-<uuid>`, puis publie atomiquement ce répertoire via `semantic-lucene/current-generation`. Les lecteurs persistants changent de répertoire à leur prochaine opération. Le pointeur est borné, validé et lu sans suivre de lien symbolique. Voir [ADR-0055](../architecture/adr/DATA-0055-preserver-les-lecteurs-lors-des-reconstructions-semantiques.md).

Les anciennes générations corrompues restent présentes pour préserver les fichiers encore ouverts par d'autres processus. Après des récupérations répétées, la procédure de quarantaine ci-dessous permet de récupérer cet espace, après arrêt de tous les processus NEXUS. Un retour à une version antérieure ne comprenant pas `current-generation` exige également une quarantaine du seul index sémantique et sa reconstruction.

La qualification utilise deux instances indépendantes, corrompt physiquement les fichiers de commit et vérifie que le lecteur déjà ouvert suit la récupération. Elle couvre aussi les rebuilds répétés, l'index vide et les changements de dimension sous Windows et Linux.

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

Phase 6 implémente le batching. Les mesures historiques restent une référence de décision, pas une preuve suffisante de la qualité d'un runtime/modèle futur.

## Qualification réelle exact-head

`.github/workflows/semantic-search-qualification.yml` qualifie le chemin complet **runtime Ollama → modèle réel → embeddings → index Lucene sémantique → ranking**.

Le workflow n'est pas exécuté sur les PR sans rapport avec le sémantique. Ses déclencheurs suivent [GEN-0060](../architecture/adr/GEN-0060-qualifier-la-recherche-semantique-reelle-en-deux-corpus.md) :

- une PR vers `develop`/`main` qui modifie le workflow, l'ancre Ollama ou le code/test sémantique ciblé exécute le corpus réduit `pr` ;
- un `push` vers `develop` qui touche ces mêmes chemins, c'est-à-dire la fusion d'une telle PR, exécute le corpus complet `full` sur le SHA de `develop` ; le groupe de concurrence est ce SHA, donc un push ultérieur n'annule pas ce run, et un run en cours n'est jamais annulé (seul un run en attente sur le même SHA peut être remplacé) ;
- `workflow_dispatch` choisit le corpus (`full` par défaut) ; la planification mensuelle exécute `full` sur `main`.

Le corpus `pr` est déclaré dans `RealSemanticBenchmarkCorpus` (test de `core`) : `docs/developer/`, ADR `BE-*` et `DATA-*`, classes de premier niveau des paquets `com.nexus.context` et `com.nexus.search` de `core`, paquet `com.nexus.context.source.git`, sources de `adapters/mcp-java`. Il est copié dans un répertoire temporaire avant indexation et contient chaque chemin jugé pertinent par les requêtes ; `RealSemanticBenchmarkCorpusTest` vérifie cette inclusion sur le dépôt à chaque build. Les seuils sont les mêmes pour les deux corpus.

Contrat courant :

```text
Ollama runtime      0.33.3 Linux amd64
modèle              qwen3-embedding:0.6b
manifest ID attendu ac6da0dfba84
dimensions          1024
endpoint            127.0.0.1:11434 uniquement
corpus              checkout exact de NEXUS_HEAD_SHA, entier (full) ou réduit (pr)
benchmark           RealSemanticSearchBenchmarkTest
```

Sur pull request, `NEXUS_HEAD_SHA` vaut explicitement `github.event.pull_request.head.sha` et le workflow vérifie `git rev-parse HEAD` après checkout. Hors PR, il vaut le SHA exact de l'événement. Le rapport final doit réémettre ce même SHA ; un merge ref GitHub ne peut donc pas être pris silencieusement pour la preuve du HEAD candidat.

L'archive Ollama est téléchargée depuis la release officielle mais **n'est jamais crue sur parole** : son SHA-256 attendu est stocké indépendamment dans `config/tool-integrity.properties` et vérifié avant extraction. Après pull, `ollama list` doit en plus exposer l'ID de manifest attendu `ac6da0dfba84` pour le modèle ; une mutation du tag fait échouer le gate au lieu de changer silencieusement le corpus d'embeddings.

Le rapport doit démontrer :

- SHA exact, corpus, modèle et endpoint attendus ;
- au moins les six requêtes de référence ;
- index sémantique non vide ;
- `recall@3 >= 0,25` ;
- `hit@3 >= 0,33` ;
- `MRR@3 >= 0,20` ;
- aucune baisse supérieure à `0,10` face au baseline lexical sur recall/hit/MRR.

Ces floors sont des garde-fous de régression, pas des objectifs à optimiser artificiellement. Un échec doit conduire à analyser runtime, modèle, corpus, pipeline ou ranking ; il ne doit pas être résolu en abaissant automatiquement les seuils. Le rapport JSON, la version Ollama, la liste des modèles, le CPU du runner, le résumé de débit `throughput.json` et les logs sont conservés comme artefacts pendant 90 jours.

### Budget de temps de la qualification réelle

Mesures d'octobre 2026 sur les runners GitHub `ubuntu-latest` (4 vCPU = 2 cœurs physiques avec hyper-threading, sans GPU) :

```text
corpus            ~574 entrées embarquées, ~620 000 tokens, 324 requêtes /api/embed
indexation        ≈ 100 % du temps passé dans les embeddings Ollama séquentiels
débit observé     86 à 165 tokens/s selon le CPU alloué
                  (Xeon 8573C : 100 à 165, Xeon 8370C : 103, EPYC 7763 : 86 à 88)
durée du job      1 h 00 à 2 h 01 réussis ; 2 h 00 atteint après 318/324 requêtes sur EPYC 7763
```

La variabilité provient du CPU alloué par GitHub et non du code NEXUS : le nombre de requêtes et de tokens est identique d'un run à l'autre pour un même corpus, et le temps Java entre deux requêtes est négligeable.

Une calibration ABBA sur le même runner (EPYC 7763, 12 documents de 12 000 caractères) a comparé 2 threads llama.cpp (défaut Ollama : cœurs physiques) et 4 threads (`LLAMA_ARG_THREADS=4` ou option `num_thread`) : 383,1 s contre 370,9 s, soit **−3,2 %** seulement. L'hyper-threading n'apporte presque rien à ce calcul vectoriel saturé ; le workflow conserve donc la configuration par défaut d'Ollama. Mettre le modèle en cache est sans intérêt (pull ≈ 5 s). Réutiliser des vecteurs d'un run précédent affaiblirait la preuve (transport d'embeddings non exercé) et n'est pas retenu. Le corpus réduit `pr` a moins de distracteurs : il donne un verdict rapide sur PR, et le corpus complet reste la mesure de référence, sur `develop` après fusion.

Le budget du corpus `full` est donc fixé à **180 minutes** : le runner le plus lent observé exige environ 2 h 05 de bout en bout, et 180 minutes laissent environ 45 % de marge pour la croissance du corpus.

Le corpus `pr` compte environ 130 fichiers. Un premier découpage, avec les paquets `com.nexus.context` et `com.nexus.search` entiers (191 fichiers), a embarqué 224 479 tokens en 30 min 25 s (123 tokens/s, AMD EPYC 9V74) pour un job de 31 min 40 s : au débit le plus lent observé (86 tokens/s), il aurait dépassé 45 minutes. Le découpage courant garde les classes de premier niveau de ces paquets et le paquet `com.nexus.context.source.git` : 133 fichiers et 180 599 tokens embarqués, en 16 min 55 s (178 tokens/s, AMD EPYC 9V45) pour un job de 18 min 13 s, seuils atteints (run du 2026-10-30 sur `1fdc5723`). Au débit le plus lent observé, ces tokens demandent environ 35 minutes. Son budget est fixé à **45 minutes**, préparation du runner et build de `core` compris (environ 1 minute 30).

Si `throughput.json` montre un corpus ou un débit qui rapproche la durée projetée d'un de ces budgets, il faut réanalyser le travail embarqué, ou réduire le corpus `pr`, avant d'augmenter le délai.

### Qualification réelle et release

La release n'exige pas la qualification sémantique réelle (GEN-0060) : `release.yml` ne la place pas dans les `needs` du job `publish`. La recherche sémantique est désactivée par défaut (BE-0014). La preuve de qualité d'une promotion `develop` → `main` est le dernier run `full` réussi sur `develop`, à consulter avant la promotion :

```bash
gh run list -w semantic-search-qualification.yml --branch develop
```

## Correctness

Le mode sémantique respecte les mêmes gates `READY` que le lexical. Si une indexation sémantique échoue, le projet ne doit pas être servi comme cohérent ; la prochaine indexation d'un état non-READY force un rebuild complet.