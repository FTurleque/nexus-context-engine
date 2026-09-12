# Registre des risques — NEXUS Context Engine

Ce registre décrit les risques **courants**. Les documents d'itération conservent les preuves historiques ; ici, la qualification applicable est toujours celle du SHA exact concerné.

## Risques actifs

### Scale SQLite lexical

Les recherches substring peuvent se dégrader sur des corpus plus grands. Mitigation : Scale Benchmark et optimisations locales avant tout FTS/trigram/autre moteur.

### Filesystem local hostile

`ProjectPathGuard` et `SafeFileIO` réduisent fortement la surface : `SecureDirectoryStream` est utilisé lorsqu'il existe ; sinon le fallback capture puis revalide chemin réel et identité filesystem autour de l'ouverture finale. Cette défense ne constitue pas un sandbox absolu contre un acteur local capable d'effectuer un échange/rétablissement extrêmement rapide, de manipuler des hard-links ou d'exploiter une sémantique de montage particulière.

### `FileLock` réseau

La garantie inter-processus vise `NEXUS_HOME` sur filesystem local. Une fixture SMB 3.1.1 loopback Windows qualifie un cas ciblé ; SMB/NFS/distribué général exige une qualification dédiée du protocole et de la panne visés.

### Provider externe non coopératif — conditionnel

La composition de production courante n'exécute pas de provider Java tiers arbitraire : SCIP est importé et JDT LS s'exécute déjà dans un subprocess. `ExternalTaskRunner` borne néanmoins toute intégration in-process à **8 workers réellement actifs maximum**, conserve le slot jusqu'à terminaison et ouvre un circuit-breaker après timeout. Une isolation processus générique n'est justifiée que si un provider concret démontre un comportement non coopératif reproductible ; ce n'est pas un défaut actif du runtime actuel.

### Recovery sémantique

SQLite reste l'autorité et un index Lucene sémantique corrompu dispose d'une procédure de rebuild/quarantaine. L'indisponibilité transitoire d'Ollama dégrade la lecture de façon sûre. La qualité du pipeline réel est désormais surveillée par une qualification Ollama périodique/manuelle avec runtime vérifié par SHA-256 et seuils de non-régression.

## Risques fortement mitigés par NXA3

### Gouvernance `develop`

NXA3-14 / #130 est satisfait : le ruleset actif cible `refs/heads/develop`, impose le passage par pull request, interdit suppression et non-fast-forward/force-push, limite le bypass administrateur au flux pull request et exige les checks permanents approuvés. L'état effectif doit être revalidé par API après toute modification repository-admin.

NEXUS est actuellement maintenu par **une seule personne**. Une seconde approbation humaine et une resynchronisation stricte de branche avant merge ne font pas partie de la politique du projet et ne sont donc pas des risques ouverts. La protection applicable repose sur la PR obligatoire, le HEAD candidat, les checks automatisés et les protections contre suppression/force-push.

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

Mitigation : Maven 3.9.16 contrôlé par SHA-512 versionné, JDT LS fixe contrôlé par SHA-256 et runtime Ollama du benchmark réel contrôlé par SHA-256 ; contrats vérifiés dans NEXUS CI avant les usages concernés.

### Publication Docker divergente

Mitigation : build unique, gates sur cette image, handoff hash/ID, publication sans rebuild.

### GHCR ambigu/partiel

Mitigation : preflight fail-closed ; reprise idempotente uniquement pour contenu identique ; tags version/SHA immuables.

### Données SQLite incompatibles

Mitigation : V004 invalide les anciens index aux plages impossibles ; V005 impose les `CHECK` de `CodeSymbol`.

## Risques fortement mitigés par NXA4 et remédiations post-audit

### Management REST exposé avec l'API métier

Mitigation : health/metrics sont retirés du listener applicatif et servis sur le listener management loopback `127.0.0.1:9000`. Le smoke Docker valide le management depuis l'intérieur du conteneur sans publier ce port.

### JDT LS défectueux, hostile ou lancé sur dépôt non approuvé

Mitigation : `JdtJsonRpcFrameReader` borne messages à 16 MiB, headers à 64 KiB, lignes à 8 KiB et backlog à 256 messages. Framing invalide/tronqué ou saturation provoquent un échec fermé. La racine canonique exacte du projet doit en plus appartenir à `NEXUS_JDTLS_TRUSTED_PROJECT_ROOTS` immédiatement avant `Process.start()`.

### Amplification des métadonnées Code Intelligence

Mitigation : politique commune de taille UTF-8 et plafonds de **100 000 symboles**, **250 000 relations** et **64 MiB de métadonnées cumulées** avant canonicalisation/déduplication.

### Requête Lucene à très forte cardinalité

Mitigation : maximum 128 termes analysés uniques avant expansion sur les cinq champs de recherche, avec test de non-régression.

### Fuite accidentelle de secrets vers embeddings ou contexte

Mitigation : exclusions scanner sensibles + `SensitiveContentRedactor` avant embeddings et fragments retournés. La redaction cible les formats à forte confiance et conserve les séparateurs de lignes des blocs multilignes.

### Transport Ollama distant non sécurisé

Mitigation : HTTPS distant obligatoire par défaut ; HTTP distant uniquement avec `NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true` ; credentials/userinfo intégrés à l'URI refusés.

### Permissions de stockage local trop larges

Mitigation : `NEXUS_HOME`, `indexes`, `locks` en `0700` et SQLite en `0600` sur POSIX ; chemins persistants symboliques concernés refusés. Les ACL Windows natives ne sont pas remplacées destructivement. `NEXUS_REQUIRE_PRIVATE_STORAGE=true` permet aux environnements sensibles d'échouer fermé lorsqu'une ACL inattendue existe, que son inspection échoue ou qu'aucune vue POSIX/ACL ne permet de démontrer la confidentialité.

### Dérive des flags Vector API

Mitigation : la qualification ABBA same-runner a montré un bénéfice global trop faible et non univoque, avec légère régression du p95 graphe. `jdk.incubator.vector` reste donc non activé par défaut et le contrat CI empêche son ajout silencieux aux launchers sans nouvelle décision mesurée.

### Dérive documentaire

Mitigation : documentation courante réconciliée et `test-operational-doc-contracts.sh` / `test-final-audit-contracts.sh` exécutés par NEXUS CI. Les contrats machine-vérifiables couvrent les invariants de sécurité, la politique solo et les décisions de performance mesurées.

## Mise à jour

Mettre à jour ce registre après changement de frontière de support, nouveau risque majeur ou clôture matérialisée par code + preuve + documentation + qualification exact-head.
