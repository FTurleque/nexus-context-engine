# Limites actuelles et dette de consolidation

Ce registre décrit l'état courant après les campagnes **NXA3 + NXA4** et les remédiations des audits `develop` des 2 et 6 septembre 2026. Les anciens numéros de PR/runs ne constituent pas une preuve permanente ; la preuve de qualification est toujours le run attaché au HEAD exact concerné.

## Invariants techniques désormais couverts

### Filesystem, stockage et sources natives

- `ProjectPathGuard` protège les lectures sensibles sous la racine canonique ;
- traversal, symlink final et symlink d'ancêtre sont refusés sur les chemins durcis ;
- `SafeFileIO` traverse les composants par `SecureDirectoryStream` lorsque le filesystem le supporte, puis ouvre le fichier final en `NOFOLLOW_LINKS` ; le fallback portable revérifie chaque composant immédiatement avant l'ouverture finale ;
- SCIP relit ses sources canoniques via la même frontière et vérifie ses bounds protobuf sans overflow arithmétique ;
- skills/customisations projet utilisent la frontière commune ;
- la découverte native partage `ContextDiscoveryLimits` avant sélection de tokens ;
- le scanner exclut davantage de répertoires/fichiers sensibles (`.ssh`, `.aws`, `.gnupg`, `.kube`, credentials, keystores, etc.) ;
- `NEXUS_MAX_INDEX_FILES` conserve son nom historique mais borne toutes les entrées non racine rencontrées par le walk, y compris les répertoires et entrées ensuite ignorées ;
- les rebuilds d'index dérivés ne conservent plus le corpus source complet en heap : les documents sont flushés par batches bornés à 128 fichiers / 16 MiB ;
- l'analyse Java locale refuse plus de **20 000 symboles** ou **10 000 relations d'import** par fichier avant matérialisation des collections de faits ;
- `NEXUS_HOME`, `indexes` et `locks` sont forcés à `0700` sur POSIX ; le fichier SQLite est forcé à `0600` ;
- les chemins persistants NEXUS durcis concernés sont refusés lorsqu'ils sont symboliques ;
- la sémantique du lock projet est qualifiée avec **deux JVM distinctes** sur Linux et Windows locaux ;
- la mutation déterministe validation→ouverture où le fichier final devient un symlink échoue fermée via `SafeFileIO`.

Sur Windows/filesystems sans vue POSIX, NEXUS conserve les ACL natives au lieu de les réécrire naïvement.

Le contrat de support courant reste volontairement borné aux filesystems locaux qualifiés Linux/Windows. Une fixture **SMB 3.1.1 loopback Windows** est désormais qualifiée comme preuve ciblée (round-trip UNC + lock inter-JVM), mais SMB/CIFS général, NFS, volumes distribués/synchronisés et montages à sémantique spéciale restent non supportés faute de qualification multi-client, panne/reconnexion et stockage complet SQLite/Lucene. Voir [`filesystem-support.md`](filesystem-support.md) et ADR-0047.

Limite résiduelle : sur les providers Java ne fournissant pas `SecureDirectoryStream`, les primitives portables réduisent fortement mais ne suppriment pas mathématiquement toute fenêtre TOCTOU contre un acteur local capable de muter agressivement les ancêtres, hard-links ou points de montage pendant l'opération.

### Git local

- commits, historique et chemins modifiés sont bornés ;
- les statuts sont filtrés aux cibles ;
- les diffs utilisent un sink à capacité fixe et sont tronqués déterministiquement ;
- un test de diff massif empêche le retour à un buffer extensible non borné ;
- l'enrichisseur de récence de recherche ne scanne plus les diffs complets : il filtre Git aux chemins candidats, refuse plus de 512 chemins candidats et borne les entrées modifiées par commit/cumul avant de dégrader fail-open vers les candidats non enrichis ;
- les runtimes longue durée REST/MCP utilisent un cache mémoire LRU borné à 16 résultats, validé par HEAD/status/diffs avant chaque hit ;
- la CLI et les usages one-shot conservent le recalcul operation-scoped.

### Recherche et fédération

- maximum de 100 projets uniques ;
- validation de cardinalité avant résolution/readiness ;
- CLI, application et MCP partagent le contrat ;
- le travail préparatoire du contexte fédéré est borné indépendamment du budget final ;
- les limites publiques REST utilisent les politiques centrales de résultats et de budget contexte ;
- une requête Lucene analysée est bornée à **128 termes uniques** avant expansion sur les cinq champs de recherche ;
- la recherche fuzzy de symboles réutilise une seule projection bornée à **8 termes** de **128 caractères maximum** pour la collecte, le scoring exact/fuzzy et le score de chemin ;
- REST/MCP conservent des readers/searchers Lucene bornés entre requêtes, avec writers toujours operation-scoped afin de préserver les verrous inter-processus.

Le champ `constraints` existe encore dans certains contrats DTO/records pour compatibilité, mais aucune sémantique n'est implémentée : une map non vide est rejetée explicitement.

### Code Intelligence externe

- JDT LS reste opt-in ;
- les tâches externes sont bornées en temps et à **8 workers actifs maximum** à l'échelle JVM ;
- le framing JDT LS borne message (16 MiB), headers (64 KiB), ligne de header (8 KiB) et queue entrante (256) ;
- le subprocess JDT LS reçoit une allowlist d'environnement minimale (runtime, chemins utilisateur/temp et outils de build usuels) ; tokens NEXUS/CI/cloud, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` et sockets d'agents ne sont pas hérités ;
- saturation ou framing invalide déclenchent un échec fermé et l'arrêt de la session concernée.

Limite résiduelle : un provider tiers peut ignorer l'interruption ; NEXUS borne alors l'accumulation de workers, conserve les slots occupés jusqu'à la terminaison réelle et rejette explicitement les nouvelles tâches à saturation, mais ne revendique pas une isolation processus absolue. Une isolation plus forte exige de déplacer le provider concerné hors JVM ; elle reste un chantier architectural conditionné par un provider réel démontrant ce mode d'échec.

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
- hors loopback, le démarrage échoue fermé si transport sécurisé effectif, token généré par CSPRNG conforme au gate structurel ou allowlist de racines ne sont pas démontrés ;
- sur loopback, un token robuste est désormais requis par défaut ; l'ancien mode local sans authentification n'est disponible qu'avec l'opt-out explicite `NEXUS_REST_TRUST_LOCAL=true` ;
- `NEXUS_REST_HARDEN_LOCAL=true` exige en plus une allowlist de racines et prend priorité sur `NEXUS_REST_TRUST_LOCAL` ;
- le profil de test Quarkus déclare explicitement `nexus.rest.trust-local=true`, de sorte que les tests fonctionnels non liés à l'authentification ne dépendent pas d'une exception implicite du runtime ;
- l'installateur Windows génère un token CSPRNG lorsque REST natif est sélectionné sans token fourni, puis réutilise la même valeur pour Docker dans un déploiement combiné.

Le gate structurel du token vérifie longueur et diversité de caractères pour éliminer des valeurs manifestement faibles ; il ne mesure pas l'entropie cryptographique et ne remplace pas une génération CSPRNG.

Le listener de management est volontairement loopback-only et ne doit pas être publié par un reverse proxy. Le runtime Docker et Compose sondent ce listener via le probe embarqué `/usr/local/bin/nexus-healthcheck`, sans exposer le port management.

### Sémantique / Ollama / secrets

- sémantique désactivé par défaut ;
- Ollama HTTP sans opt-in est limité aux adresses de bouclage ; un endpoint distant doit utiliser HTTPS ;
- `NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true` est l'exception administrative explicite pour HTTP distant ;
- credentials intégrés dans `NEXUS_OLLAMA_BASE_URL` refusés ;
- secrets à forte confiance redigés avant embeddings et avant restitution des fragments de contexte ;
- les troncatures embedding/excerpt ne coupent plus une paire surrogate UTF-16 ;
- le profil sémantique est `content-v2`, ce qui force le rebuild d'un ancien index incompatible ;
- une indisponibilité provider dégrade la recherche de façon sûre et un index Lucene sémantique corrompu est purgé/reconstruit avant recovery.

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
- les contrats documentaires courants sont vérifiés automatiquement par NEXUS CI ;
- les changements de frontière filesystem déclenchent une qualification locale dédiée Linux/Windows ;
- la preuve SMB sélectionnée dispose d'un gate Windows séparé qui crée un vrai partage SMB, impose l'usage UNC et conserve les informations protocole/configuration ;
- NEXUS CI, CodeQL et OSV couvrent aussi les pushes directs sur `develop` en défense en profondeur ; Docker Distribution, Scale Benchmark, Scanner Corpus Benchmark et Windows Installer sont réutilisés par des callers `Develop Push ...` avec leurs filtres de chemins respectifs.

## Contrôle de gouvernance externe au code

La protection GitHub de `develop` est un état repository-admin, pas un fichier versionné. Le contrat attendu est décrit dans [`branch-governance.md`](branch-governance.md).

NXA3-14 / #130 est satisfait : le ruleset actif `Protect main & develop` protège `develop`, exige les pull requests, interdit suppression/non-fast-forward et impose les sept checks permanents approuvés. Après toute modification repository-admin, cet état doit être revalidé par API.

Hardening résiduel : `strict_required_status_checks_policy=false`. Les checks requis qualifient le HEAD de PR, mais GitHub n'impose pas actuellement une remise à jour avec la base immédiatement avant merge. Ce paramètre ne peut pas être modifié par un commit de code/workflow.

## Watch items

Les sujets suivants ne doivent pas être changés sans mesure ou scénario reproductible :

- isolation processus plus forte d'un provider réellement non coopératif (#51) ;
- extension de support vers un filesystem réseau/distribué précis : elle exige désormais de dépasser la preuve SMB loopback et de qualifier le protocole/configuration réellement visé, idéalement multi-client avec injection de panne ;
- nouveau moteur FTS/trigram pour les recherches substring ;
- activation repository-admin du mode strict « branch up to date before merge ».

Le watch item légal #55 reste un gate conditionnel pour toute dépendance ou modalité de redistribution inhabituelle ; il ne déclenche aucune modification tant qu'un candidat concret n'existe pas.

## Règle de clôture audit

Un finding n'est déclaré fermé que si :

1. le comportement est implémenté ;
2. les preuves/tests/benchmarks exigés existent ;
3. la documentation correspond au code ;
4. les gates applicables sont verts sur le HEAD exact ;
5. les contrôles GitHub externes requis sont effectivement configurés lorsqu'ils font partie du finding.

Voir aussi [`ci-and-supply-chain.md`](ci-and-supply-chain.md), [`release-and-recovery.md`](release-and-recovery.md), [`rest-api.md`](rest-api.md), [`semantic-search.md`](semantic-search.md), [`filesystem-support.md`](filesystem-support.md) et [`branch-governance.md`](branch-governance.md).
