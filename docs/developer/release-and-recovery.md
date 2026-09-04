# Release, installation et recovery

Ce document décrit le contrat opérationnel courant de NEXUS Context Engine 0.2.0 après NXA3 + NXA4.

## Toolchain

- Java runtime : 21 ou supérieur ;
- bytecode/API : Java 21 (`maven.compiler.release=21`) ;
- wrapper : **Maven 3.9.16** avec SHA-512 versionné dans `config/tool-integrity.properties` ;
- Eclipse JDT LS optionnel : archive vérifiée contre le SHA-256 versionné dans le même fichier.

Les scripts d'installation échouent si l'ancre attendue manque ou ne correspond pas aux octets téléchargés. L'installateur JDT LS ne fait pas confiance à un checksum récupéré depuis le même origin que l'archive.

## Livrables

Le reactor produit notamment :

```text
target/nexus-context-engine-0.2.0-cli.jar
target/nexus-context-engine-0.2.0-cli.jar.sha256
target/distribution/nexus-context-engine-0.2.0.zip
target/distribution/nexus-context-engine-0.2.0.zip.sha256
target/licenses/THIRD_PARTY_NOTICES.txt
target/sbom/bom.json
```

La distribution Windows x64 self-contained produit également :

```text
target\dist\nexus-context-engine-0.2.0-windows-x64.zip
target\dist\nexus-context-engine-0.2.0-windows-x64.zip.sha256
target\dist\NEXUS-0.2.0-windows-x64-setup.exe
target\dist\NEXUS-0.2.0-windows-x64-setup.exe.sha256
```

## Contrat de release

`develop` est la branche d'intégration ; `main` est la branche de release. Une publication GHCR est déclenchée uniquement par un tag `vX.Y.Z` qui pointe sur le HEAD exact de `main` et dont la version correspond au `pom.xml`.

La release réutilise les gates exécutables : NEXUS CI, Windows Installer, Docker Distribution, Scale Benchmark, Scanner Corpus Benchmark, CodeQL et OSV-Scanner selon le contrat du workflow. SonarCloud reste un Quality Gate PR externe, pas un job de release.

L'image Docker est **construite une seule fois** dans Docker Distribution, qualifiée, exportée avec preuve d'intégrité puis chargée dans le job de publication. Le workflow de release ne reconstruit pas l'image.

Les tags version et SHA sont immuables. Le préflight GHCR est fail-closed sur toute erreur ambiguë et accepte une reprise idempotente uniquement si le contenu déjà publié correspond à l'image qualifiée. `latest` est le seul pointeur mutable.

Voir [`immutable-release-publishing.md`](immutable-release-publishing.md).

## SQLite : autorité, migrations et recovery

SQLite reste l'autorité canonique. Les migrations sont forward-only, enregistrées dans `schema_migrations` et protégées par `script_sha256`.

Les migrations de plage de symboles sont :

- `V004__invalidate_invalid_symbol_ranges.sql` : invalide les index historiques contenant des plages que le domaine Java ne peut plus représenter et force un rebuild déterministe ;
- `V005__enforce_symbol_range_constraints.sql` : reconstruit `symbols` et impose :

```text
start_line >= 1
end_line >= start_line
```

Une base V004 valide est migrée vers V005 en conservant ses données/index. Un `INSERT` SQL direct invalide est rejeté. Réexécuter le migrateur sur une base V005 est idempotent.

### Permissions de `NEXUS_HOME`

Avant l'ouverture/migration SQLite, NEXUS crée/durcit le stockage persistant :

```text
NEXUS_HOME/          0700 sur POSIX
NEXUS_HOME/indexes/  0700 sur POSIX
NEXUS_HOME/locks/    0700 sur POSIX
NEXUS_HOME/nexus.db  0600 sur POSIX
```

Les chemins persistants durcis concernés sont refusés lorsqu'ils sont symboliques. Sur Windows ou filesystem sans vue POSIX, NEXUS conserve les ACL natives au lieu de les remplacer destructivement.

Un backup/restauration doit conserver les protections adaptées au système cible. Ne pas déduire de `0700/0600` qu'une ACL Windows doit être remplacée par un équivalent artisanal.

### Recovery après échec d'indexation

- une lecture indexée exige un projet `READY` ;
- toute `IOException`/erreur runtime durant l'indexation marque le projet `FAILED` avant propagation ;
- un projet persistant non-READY est reconstruit lors de la prochaine indexation ;
- SQLite doit être conservé ; Lucene lexical/sémantique reste dérivé et reconstructible ;
- une mutation concurrente détectée pendant l'indexation provoque un échec fail-closed ;
- un timeout/failure d'un provider externe explicitement demandé n'est pas converti silencieusement en `READY` ;
- `index_generation` ne progresse pas pour un no-op effectif.

Avant une migration de production, sauvegarder SQLite service arrêté. Ne jamais restaurer uniquement un index Lucene en ignorant SQLite.

## Recovery sémantique / `content-v2`

Le profil de contenu sémantique courant est `content-v2`, introduit avec la redaction de secrets avant embeddings. Un index créé sous l'ancien profil est considéré incompatible et doit être reconstruit ; NEXUS ne réutilise pas silencieusement les vecteurs historiques.

La redaction de secrets réduit la fuite accidentelle de tokens/clés/mots de passe structurés, mais n'est pas une sauvegarde ni un scanner de secrets complet.

L'indisponibilité d'Ollama ou une corruption physique de l'index Lucene sémantique reste le watch item #54 pour des diagnostics/runbooks supplémentaires.

## Concurrence et providers externes

Une mutation d'index par projet est protégée par :

1. mutex JVM par `projectId` ;
2. `FileLock` OS sous `NEXUS_HOME/locks`.

La garantie cible un `NEXUS_HOME` sur filesystem local. Les mêmes garanties ne sont pas revendiquées sur filesystem réseau.

Les tâches providers/importers sont bornées en temps et à **8 workers réellement actifs maximum**. La capacité n'est rendue que lorsque le worker se termine effectivement. Un provider ignorant l'interruption peut donc vivre plus longtemps que l'appelant, mais il ne peut pas provoquer une croissance non bornée du nombre de workers NEXUS.

## Recovery Docker

`NEXUS_HOME` contient l'état persistant et doit être sauvegardé. Une image Docker est remplaçable.

Pour diagnostiquer une release :

- identifier le digest réellement exécuté ;
- vérifier les tags version/SHA et les attestations liées à ce digest ;
- utiliser les preuves Trivy/SBOM du run exact ;
- ne jamais considérer `latest` seul comme identité immuable.

## Sécurité REST et management

Listener applicatif par défaut : `127.0.0.1:8080`.

Health/metrics sont servis sur le listener management séparé `127.0.0.1:9000`. `/q/*` ne doit pas être disponible sur le listener applicatif ni publié par le reverse proxy métier.

Loopback reste autorisé localement sans token par défaut. Une écoute API hors loopback exige transport réellement sécurisé, token robuste et allowlist de racines. `direct-https` doit correspondre à un listener TLS effectif ; `reverse-proxy-https` doit respecter la frontière proxy/backend. `loopback-forward` reste limité à une publication Docker hôte sur loopback.

Contourner les gardes REST ou exposer le listener management n'est jamais une procédure de recovery valide.

## Ollama

Un endpoint Ollama distant doit utiliser HTTPS par défaut. HTTP distant nécessite `NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true`. Les credentials intégrés à `NEXUS_OLLAMA_BASE_URL` sont refusés.

## Qualification

Une version, un merge ou une release n'est qualifié que si les gates applicables ont terminé en succès sur le **HEAD exact** concerné. Un ancien run vert n'est pas une preuve pour un nouveau commit.

Voir aussi [`ci-and-supply-chain.md`](ci-and-supply-chain.md), [`branch-governance.md`](branch-governance.md), [`semantic-search.md`](semantic-search.md) et [`current-limitations.md`](current-limitations.md).
