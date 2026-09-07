# Registre des risques — NEXUS Context Engine

Ce registre décrit les risques **courants**. Les documents d'itération conservent les preuves historiques ; ici, la qualification applicable est toujours celle du SHA exact concerné.

## Risques actifs

### Scale SQLite lexical

Les recherches substring peuvent se dégrader sur des corpus plus grands. Mitigation : Scale Benchmark et optimisations locales avant tout FTS/trigram/autre moteur.

### Filesystem local hostile

`ProjectPathGuard`, `SafeFileIO`, confinement SCIP/skills et budgets réduisent la surface, sans constituer un sandbox absolu contre un acteur local capable de muter l'arborescence pendant l'opération.

### `FileLock` réseau

La garantie inter-processus vise `NEXUS_HOME` sur filesystem local. SMB/NFS exigeraient une qualification dédiée.

### Provider externe non coopératif

Les tâches sont bornées en wall-clock et à **8 workers réellement actifs maximum**. Un provider qui ignore l'interruption peut continuer jusqu'à sa terminaison réelle ; une isolation processus généralisée plus forte reste conditionnée à un cas reproductible.

### Recovery sémantique

L'indisponibilité d'un provider ou une corruption physique Lucene nécessite encore des procédures opérationnelles spécifiques selon le scénario. SQLite reste l'autorité. Le profil `content-v2` garantit en revanche qu'un ancien index sémantique incompatible est reconstruit plutôt que réutilisé silencieusement.

### Gouvernance — branche à jour avant merge

Le ruleset GitHub `Protect main & develop` est actif et protège effectivement `develop`. Le résiduel de hardening est `strict_required_status_checks_policy=false` : les checks requis qualifient le HEAD de PR mais GitHub n'impose pas actuellement une remise à jour avec la base immédiatement avant merge. Ce point est une configuration repository-admin, pas un défaut du code versionné.

## Risques fortement mitigés par NXA3

### Gouvernance `develop`

NXA3-14 / #130 est satisfait : le ruleset actif cible `refs/heads/develop`, impose le passage par pull request, interdit suppression et non-fast-forward/force-push, limite le bypass administrateur au flux pull request et exige les sept checks permanents approuvés. L'état effectif doit être revalidé par API après toute modification repository-admin.

### REST distant

Mitigation : token généré par CSPRNG et soumis au gate structurel NEXUS + roots + mode explicite + listener TLS effectif ; reverse proxy avec forwarding et trusted proxies bornés. Le gate de longueur/diversité rejette les valeurs manifestement faibles mais ne prétend pas mesurer l'entropie cryptographique d'une chaîne statique.

### SCIP / skills / customisations hors racine

Mitigation : `ProjectPathGuard`, refus traversal/symlink final/symlink d'ancêtre et tests ciblés.

### Découverte native pathologique

Mitigation : budget partagé visites/candidats/octets/deadline avant sélection + benchmark filesystem de 1 000 skills.

### Portée fédérée surdimensionnée

Mitigation : maximum 100 projets uniques appliqué avant résolution/readiness dans les surfaces concernées.

### Diff Git massif

Mitigation : chemins/historique capés et sink de patch à capacité fixe, qualifié par test massif.

### Supply-chain outils

Mitigation : Maven 3.9.16 contrôlé par SHA-512 versionné et JDT LS fixe contrôlé par SHA-256 ; test exécuté dans NEXUS CI.

### Publication Docker divergente

Mitigation : build unique, gates sur cette image, handoff hash/ID, publication sans rebuild.

### GHCR ambigu/partiel

Mitigation : preflight fail-closed ; reprise idempotente uniquement pour contenu identique ; tags version/SHA immuables.

### Données SQLite incompatibles

Mitigation : V004 invalide les anciens index aux plages impossibles ; V005 impose les `CHECK` de `CodeSymbol`.

## Risques fortement mitigés par NXA4

### Management REST exposé avec l'API métier

Mitigation : health/metrics sont retirés du listener applicatif et servis sur le listener management loopback `127.0.0.1:9000`. Le smoke Docker valide le management depuis l'intérieur du conteneur sans publier ce port.

### JDT LS défectueux ou hostile

Mitigation : `JdtJsonRpcFrameReader` borne messages à 16 MiB, headers à 64 KiB, lignes à 8 KiB et backlog à 256 messages. Framing invalide/tronqué ou saturation provoquent un échec fermé.

### Requête Lucene à très forte cardinalité

Mitigation : maximum 128 termes analysés uniques avant expansion sur les cinq champs de recherche, avec test de non-régression.

### Fuite accidentelle de secrets vers embeddings ou contexte

Mitigation : exclusions scanner sensibles + `SensitiveContentRedactor` avant embeddings et fragments retournés. La redaction cible les formats à forte confiance et conserve les séparateurs de lignes des blocs multilignes.

### Transport Ollama distant non sécurisé

Mitigation : HTTPS distant obligatoire par défaut ; HTTP distant uniquement avec `NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true` ; credentials/userinfo intégrés à l'URI refusés.

### Permissions de stockage local trop larges

Mitigation : `NEXUS_HOME`, `indexes`, `locks` en `0700` et SQLite en `0600` sur POSIX ; chemins persistants symboliques concernés refusés. Les ACL Windows natives ne sont pas remplacées destructivement.

### Dérive documentaire

Mitigation : documentation courante réconciliée et `test-operational-doc-contracts.sh` exécuté par NEXUS CI. Les contrats machine-vérifiables couvrent désormais aussi les invariants NXA4.

## Mise à jour

Mettre à jour ce registre après changement de frontière de support, nouveau risque majeur ou clôture matérialisée par code + preuve + documentation + qualification exact-head.
