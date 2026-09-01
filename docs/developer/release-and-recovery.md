# Release, installation et recovery

Ce document décrit le contrat opérationnel courant de NEXUS Context Engine 0.2.0.

## Toolchain

- Java runtime : 21 ou supérieur ;
- bytecode/API : Java 21 (`maven.compiler.release=21`) ;
- wrapper : **Maven 3.9.16** avec SHA-512 versionné dans `config/tool-integrity.properties` ;
- Eclipse JDT LS optionnel : archive vérifiée contre le SHA-256 versionné dans le même fichier.

Les scripts d'installation échouent si l'ancre attendue manque ou ne correspond pas aux octets téléchargés.

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

La release réutilise les gates exécutables : NEXUS CI, Windows Installer, Docker Distribution, Scale Benchmark, Scanner Corpus Benchmark, CodeQL et OSV-Scanner selon le contrat du workflow.

L'image Docker est **construite une seule fois** dans Docker Distribution, qualifiée, exportée avec preuve d'intégrité puis chargée dans le job de publication. Le workflow de release ne reconstruit pas l'image.

Les tags version et SHA sont immuables. Le préflight GHCR est fail-closed sur toute erreur ambiguë et accepte une reprise idempotente uniquement si le contenu déjà publié correspond à l'image qualifiée. `latest` est le seul pointeur mutable.

Voir [`immutable-release-publishing.md`](immutable-release-publishing.md).

## SQLite : autorité, migrations et recovery

SQLite reste l'autorité canonique. Les migrations sont forward-only, enregistrées dans `schema_migrations` et protégées par SHA-256.

Les migrations de plage de symboles sont :

- `V004__invalidate_invalid_symbol_ranges.sql` : invalide les index historiques contenant des plages que le domaine Java ne peut plus représenter et supprime leurs fichiers indexés pour forcer un rebuild déterministe ;
- `V005__enforce_symbol_range_constraints.sql` : reconstruit `symbols` et impose au niveau SQLite :

```text
start_line >= 1
end_line >= start_line
```

Une base V004 valide est migrée vers V005 en conservant ses données et index. Un `INSERT` SQL direct qui viole ces invariants est rejeté par SQLite. Réexécuter le migrateur sur une base V005 est idempotent.

### Recovery après échec d'indexation

- une lecture indexée exige un projet `READY` ;
- un projet persistant non-READY est reconstruit lors de la prochaine indexation ;
- SQLite doit être conservé ; Lucene lexical/sémantique reste dérivé et reconstructible ;
- une mutation concurrente du repository détectée pendant l'indexation provoque un échec fail-closed ;
- `index_generation` ne progresse pas pour un no-op effectif.

Avant une migration de production, sauvegarder SQLite service arrêté. Ne jamais restaurer uniquement un index Lucene en ignorant SQLite.

## Concurrence

Une mutation d'index par projet est protégée par :

1. mutex JVM par `projectId` ;
2. `FileLock` OS sous `NEXUS_HOME/locks`.

La garantie cible un `NEXUS_HOME` sur filesystem local. Les mêmes garanties ne sont pas revendiquées sur filesystem réseau.

## Recovery Docker

`NEXUS_HOME` contient l'état persistant et doit être sauvegardé. Une image Docker est remplaçable.

Pour diagnostiquer une release :

- identifier le digest réellement exécuté ;
- vérifier les tags version/SHA et les attestations liées à ce digest ;
- utiliser les preuves Trivy/SBOM du run exact ;
- ne jamais considérer `latest` seul comme identité immuable.

## Sécurité REST

Loopback reste autorisé localement sans token par défaut. Une écoute hors loopback exige le contrat de transport réellement sécurisé, un token robuste et une allowlist de racines. `direct-https` doit correspondre à un listener TLS effectif ; `reverse-proxy-https` doit respecter la frontière proxy/backend documentée par l'adapter REST. Le mode Docker `loopback-forward` reste limité à une publication hôte sur loopback.

Contourner les gardes REST n'est jamais une procédure de recovery valide.

## Qualification

Une version, un merge ou une release n'est qualifié que si les gates applicables ont terminé en succès sur le **HEAD exact** concerné. Un ancien run vert n'est pas une preuve pour un nouveau commit.

Voir aussi [`ci-and-supply-chain.md`](ci-and-supply-chain.md), [`branch-governance.md`](branch-governance.md) et [`current-limitations.md`](current-limitations.md).
