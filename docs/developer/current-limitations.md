# Limites actuelles et dette de consolidation

Ce registre décrit l'état courant après les campagnes **NXA3 + NXA4** et la remédiation de l'audit `develop` du 2 septembre 2026. Les anciens numéros de PR/runs ne constituent pas une preuve permanente ; la preuve de qualification est toujours le run attaché au HEAD exact concerné.

## Invariants techniques désormais couverts

### Filesystem, stockage et sources natives

- `ProjectPathGuard` protège les lectures sensibles sous la racine canonique ;
- traversal, symlink final et symlink d'ancêtre sont refusés sur les chemins durcis ;
- SCIP relit ses sources canoniques via la même frontière et vérifie ses bounds protobuf sans overflow arithmétique ;
- skills/customisations projet utilisent la frontière commune ;
- la découverte native partage `ContextDiscoveryLimits` avant sélection de tokens ;
- le scanner exclut davantage de répertoires/fichiers sensibles (`.ssh`, `.aws`, `.gnupg`, `.kube`, credentials, keystores, etc.) ;
- `NEXUS_MAX_INDEX_FILES` conserve son nom historique mais borne toutes les entrées non racine rencontrées par le walk, y compris les répertoires et entrées ensuite ignorées ;
- les rebuilds d'index dérivés ne conservent plus le corpus source complet en heap : les documents sont flushés par batches bornés à 128 fichiers / 16 MiB ;
- `NEXUS_HOME`, `indexes` et `locks` sont forcés à `0700` sur POSIX ; le fichier SQLite est forcé à `0600` ;
- les chemins persistants NEXUS durcis concernés sont refusés lorsqu'ils sont symboliques.

Sur Windows/filesystems sans vue POSIX, NEXUS conserve les ACL natives au lieu de les réécrire naïvement.

Limite résiduelle : les primitives Java portables ne sont pas un sandbox absolu contre un acteur local capable de muter agressivement le filesystem pendant l'opération.

### Git local

- commits, historique et chemins modifiés sont bornés ;
- les statuts sont filtrés aux cibles ;
- les diffs utilisent un sink à capacité fixe et sont tronqués déterministiquement ;
- un test de diff massif empêche le retour à un buffer extensible non borné.

### Recherche et fédération

- maximum de 100 projets uniques ;
- validation de cardinalité avant résolution/readiness ;
- CLI, application et MCP partagent le contrat ;
- le travail préparatoire du contexte fédéré est borné indépendamment du budget final ;
- les limites publiques REST utilisent les politiques centrales de résultats et de budget contexte ;
- une requête Lucene analysée est bornée à **128 termes uniques** avant expansion sur les cinq champs de recherche.

Le champ `constraints` existe encore dans certains contrats DTO/records pour compatibilité, mais aucune sémantique n'est implémentée : une map non vide est rejetée explicitement.

### Code Intelligence externe

- JDT LS reste opt-in ;
- les tâches externes sont bornées en temps et à **8 workers actifs maximum** à l'échelle JVM ;
- le framing JDT LS borne message (16 MiB), headers (64 KiB), ligne de header (8 KiB) et queue entrante (256) ;
- saturation ou framing invalide déclenchent un échec fermé et l'arrêt de la session concernée.

Limite résiduelle : un provider tiers peut ignorer l'interruption ; NEXUS borne alors l'accumulation de workers mais ne revendique pas une isolation processus absolue.

### SQLite et verrous d'indexation

SQLite reste canonique. V004 invalide les index historiques contenant des plages de symboles impossibles ; V005 impose ensuite :

```text
start_line >= 1
end_line >= start_line
```

Les index Lucene restent dérivés et reconstructibles.

La libération du verrou inter-processus ne transforme plus une mutation déjà validée en faux échec lorsque `FileLock.release()` signale une erreur mais que la fermeture du channel réussit effectivement. Une erreur de fermeture du channel reste propagée et conserve l'échec de release en exception supprimée.

### REST et management

- listener applicatif par défaut : `127.0.0.1:8080` ;
- health/metrics sont isolés sur le listener de management `127.0.0.1:9000` ;
- `/q/*` n'est pas servi par le listener applicatif ;
- hors loopback, le démarrage échoue fermé si transport sécurisé effectif, token robuste ou allowlist de racines ne sont pas démontrés.

Le listener de management est volontairement loopback-only et ne doit pas être publié par un reverse proxy. Le runtime Docker et Compose sondent ce listener via le probe embarqué `/usr/local/bin/nexus-healthcheck`, sans exposer le port management.

### Sémantique / Ollama / secrets

- sémantique désactivé par défaut ;
- Ollama HTTP sans opt-in est limité aux adresses de bouclage ; un endpoint distant doit utiliser HTTPS ;
- `NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true` est l'exception administrative explicite pour HTTP distant ;
- credentials intégrés dans `NEXUS_OLLAMA_BASE_URL` refusés ;
- secrets à forte confiance redigés avant embeddings et avant restitution des fragments de contexte ;
- les troncatures embedding/excerpt ne coupent plus une paire surrogate UTF-16 ;
- le profil sémantique est `content-v2`, ce qui force le rebuild d'un ancien index incompatible.

La redaction conservatrice réduit les fuites accidentelles mais ne remplace pas un scanner de secrets spécialisé.

### CI, release et supply-chain

- exact-head explicite pour NEXUS CI/CodeQL ;
- OSV, CodeQL, Trivy et SBOM actifs ;
- Maven/JDT LS vérifiés contre des ancres versionnées indépendantes ;
- le gate Windows Installer couvre désormais `core/src/**`, `adapters/**`, les POM et le wrapper Maven ;
- les images Docker builder/runtime sont épinglées par digest et les Dockerfiles n'exécutent plus de `apt-get` dépendant de l'état courant d'un miroir ;
- image Docker construite une fois, qualifiée puis publiée sans rebuild ;
- GHCR preflight fail-closed avec reprise idempotente uniquement pour le même contenu ;
- Dependabot cible `develop` ;
- les contrats documentaires courants sont vérifiés automatiquement par NEXUS CI.

## Contrôle de gouvernance encore externe au code

La protection GitHub de `develop` est un état repository-admin, pas un fichier versionné. Le contrat attendu est décrit dans [`branch-governance.md`](branch-governance.md).

Tant que GitHub retourne `protected=false` pour `develop`, NXA3-14 / #130 reste ouvert : une poussée directe peut entrer avant le gate PR même si la CI se déclenche ensuite. Ce point ne peut pas être clôturé par une modification de code ou de workflow ; il exige le ruleset GitHub effectif.

## Watch items

Les sujets suivants ne doivent pas être changés sans mesure ou scénario reproductible :

- lifecycle Lucene persistant/partagé ;
- isolation processus plus forte d'un provider réellement non coopératif ;
- garanties supplémentaires sur filesystem réseau/hostile ;
- cache Git persistant ;
- recovery sémantique face à une indisponibilité provider ou corruption physique ;
- nouveau moteur FTS/trigram pour les recherches substring.

## Règle de clôture audit

Un finding n'est déclaré fermé que si :

1. le comportement est implémenté ;
2. les preuves/tests/benchmarks exigés existent ;
3. la documentation correspond au code ;
4. les gates applicables sont verts sur le HEAD exact ;
5. les contrôles GitHub externes requis sont effectivement configurés lorsqu'ils font partie du finding.

Voir aussi [`ci-and-supply-chain.md`](ci-and-supply-chain.md), [`release-and-recovery.md`](release-and-recovery.md), [`rest-api.md`](rest-api.md), [`semantic-search.md`](semantic-search.md) et [`branch-governance.md`](branch-governance.md).
