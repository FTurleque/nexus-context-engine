# CI, couverture et supply-chain

Ce document décrit le contrat courant de qualification et de publication de NEXUS 0.2.0 après les campagnes **NXA3 + NXA4 + NXA7**. Le code et les workflows versionnés restent l'autorité exécutable.

## Branches et exact-head

`develop` est la branche d'intégration. `main` est la branche de release. Une promotion vers `main` n'est autorisée qu'après qualification du **HEAD exact** candidat.

NEXUS CI et CodeQL checkoutent explicitement `github.event.pull_request.head.sha` sur pull request. CodeQL vérifie ensuite que `git rev-parse HEAD` correspond au SHA attendu.

Le ruleset GitHub actif `Protect main & develop` protège effectivement `develop` et `main`, exige le passage par pull request, interdit suppression/non-fast-forward et impose les sept checks permanents approuvés. NXA3-14 / #130 est satisfait. `strict_required_status_checks_policy=false` et l'absence d'approbation obligatoire restent des hardenings repository-admin suivis dans #202 : les checks qualifient le HEAD de PR, mais GitHub n'impose pas encore une remise à jour avec la base immédiatement avant merge ni une approbation humaine minimale.

Les workflows versionnés conservent en plus une défense en profondeur sur les pushes directs `develop` : NEXUS CI, CodeQL et OSV écoutent directement la branche ; les gates Docker, benchmarks et Windows sont réutilisés par des callers dédiés avec leurs filtres de chemins.

## NEXUS CI

`.github/workflows/ci.yml` qualifie `develop` et `main` :

- Windows : Java 24 et qualification locale ;
- Linux : Java 21, **Maven 3.9.16**, reactor complet, tests, distribution, JaCoCo, SBOM et notices ;
- vérification explicite des ancres d'intégrité Maven/JDT LS ;
- vérification des contrats documentaires machine-vérifiables **avant** le reactor.

Le profil Maven `ci-strict-specified-tests` s'active automatiquement lorsque `CI=true`. Il force alors `surefire.failIfNoSpecifiedTests=true` : toute commande CI ciblée avec `-Dtest=...` échoue si le sélecteur ne correspond à aucun test. Hors CI, la valeur reste `false` afin de préserver les scripts développeur historiques qui sélectionnent un test `core` depuis le reactor multi-module.

JaCoCo est un gate du lifecycle `verify`, pas seulement un rapport : chaque bundle de code doit conserver au moins **65 % de lignes** et **55 % de branches** couvertes. Ces seuils ont été placés sous la baseline du dernier CI vert afin de bloquer les régressions significatives sans transformer l'ajout du gate en hausse artificielle immédiate de couverture.

Le gate documentaire contrôle notamment les invariants NXA4 :

- listener management `127.0.0.1:9000` séparé du listener API ;
- politique Ollama distante et opt-in HTTP ;
- redaction de secrets + profil `content-v2` ;
- JDT LS : 16 MiB / 64 KiB / 8 KiB / 256 messages ;
- maximum 8 tâches externes actives ;
- Lucene : 128 termes analysés uniques ;
- stockage POSIX : 0700/0600 ;
- `constraints` non supportées rejetées ;
- installateur JDT LS vérifié contre l'ancre repository-pinned ;
- noms de providers et colonne `script_sha256` de la documentation de schéma.

Les actions GitHub contrôlées par le dépôt sont référencées par SHA immuable.

## Windows Installer

`.github/workflows/windows-installer.yml` est un gate exact-head de packaging et de comportement Windows. Ses filtres PR couvrent les sources qui peuvent modifier le payload distribué :

- `core/src/**` ;
- `adapters/**` ;
- POM, wrapper Maven et configuration `.mvn/**` ;
- scripts/distribution/packaging Windows.

Une modification Java ordinaire ne peut donc plus contourner le smoke du ZIP self-contained et de l'installateur Windows avant intégration dans `develop`.

NXA7 a également durci le bootstrap `mvnw.cmd` après qu'un runner Windows a reçu un HTTP 403 via `Invoke-WebRequest` sur Maven Central. Le wrapper :

1. conserve Maven **3.9.16** comme version de bootstrap ;
2. préfère `curl.exe` avec suivi des redirections et retries ;
3. bascule vers Windows PowerShell avec un User-Agent explicite si `curl.exe` est absent ou échoue ;
4. refuse toujours l'archive si son SHA-512 ne correspond pas à l'ancre versionnée dans `config/tool-integrity.properties` ;
5. conserve cette archive vérifiée comme ancre locale ;
6. compare l'arbre Maven extrait, fichier par fichier, à l'archive vérifiée **avant chaque réutilisation** ;
7. reconstruit automatiquement l'arbre extrait si une mutation ou un fichier inattendu est détecté.

La résilience réseau ne modifie donc pas la racine de confiance du bootstrap et une compromission du cache extrait n'est plus silencieusement réutilisée.

## CodeQL, OSV et SonarCloud

- CodeQL : Java/Kotlin `security-extended`, contrat exact-head ;
- OSV : delta PR + scan bloquant du SBOM CycloneDX agrégé ;
- reusable workflow OSV épinglé au SHA qui échoue fermé lorsqu'un scan est incomplet ;
- SonarQube Cloud : GitHub App en Automatic Analysis, avec Quality Gate externe sur les changements de PR.

Un check externe n'apparaît pas nécessairement dans la liste des workflows GitHub Actions ; la qualification finale d'une PR doit donc regarder les **check-runs du commit**, pas seulement les workflow runs.

## Benchmarks

`scale-benchmark.yml` couvre :

- SQLite ;
- graphe ;
- fédération ;
- découverte native filesystem sous `ContextDiscoveryLimits`.

Le benchmark natif crée un corpus hermétique de 1 000 skills et vérifie frontière exacte, compteurs, déterminisme et durée. Voir [`native-context-discovery-limits.md`](native-context-discovery-limits.md).

`scanner-corpus-benchmark.yml` qualifie séparément le comportement/performance du scanner sur son corpus dédié. Les tests du reactor couvrent en plus l'explosion de répertoires vides et le batching mémoire des index dérivés ; voir [`indexing-corpus-limits.md`](indexing-corpus-limits.md).

La borne Lucene de 128 termes est couverte par un test de non-régression du reactor plutôt que par une baisse de seuil benchmark.

## Docker Distribution : construire une fois

`.github/workflows/docker-distribution.yml` ne publie rien dans GHCR. Il construit l'image une seule fois et exécute sur cette image les smokes CLI/MCP/REST, Trivy, SBOM et gates de vulnérabilités.

Les images de base builder/runtime sont épinglées par digest. Les Dockerfiles n'exécutent **aucun `apt-get`** après ces bases :

- le builder utilise le wrapper repository-pinned `./mvnw`, donc Maven **3.9.16** après vérification de l'ancre SHA-512 versionnée et de l'arbre extrait ;
- le runtime n'installe pas `curl`/`wget` uniquement pour le healthcheck ;
- `/usr/local/bin/nexus-healthcheck` utilise le support TCP de Bash contre le listener management local.

Le contenu OS de ces couches dépend donc des digests de base, et non de l'état courant d'un miroir APT au moment de la build.

Le smoke REST respecte la séparation NXA4 :

- health sondé **dans le conteneur** par `/usr/local/bin/nexus-healthcheck` sur `127.0.0.1:9000/q/health/live` ;
- endpoint métier vérifié séparément via le port applicatif publié ;
- le port management n'est pas exposé à l'hôte uniquement pour satisfaire la CI.

Le template Compose réutilise le même probe embarqué : il ne tente plus de lire `/q/health/live` sur le listener applicatif.

Pour une release, Docker Distribution exporte ensuite l'image qualifiée avec son SHA-256 d'archive, son ID Docker et le SHA Git. Le job de publication vérifie ce handoff et **ne reconstruit jamais l'image**.

## Publication GHCR

`.github/workflows/release.yml` est déclenché par un tag `vX.Y.Z` sur le HEAD exact de `main`.

Le préflight GHCR :

- autorise un tag réellement absent ;
- échoue sur auth/réseau/timeout/serveur ou réponse ambiguë ;
- autorise une reprise idempotente si le tag existant correspond à l'image qualifiée ;
- échoue définitivement si un tag immuable contient un autre contenu ;
- exige que tags version et SHA convergent sur le même digest.

Les attestations de provenance et SBOM portent sur le digest publié. `latest` n'est déplacé qu'après succès des références immuables.

## Ancres d'intégrité

`config/tool-integrity.properties` contient les ancres indépendantes :

- Maven 3.9.16 : SHA-512 ;
- Eclipse JDT LS 1.60.0-202606262232 : SHA-256.

Sur Unix et Windows, les wrappers Maven conservent l'archive dont le checksum correspond à l'ancre versionnée. `scripts/release/ToolArchiveVerifier.java`, lancé en mode source Java avant Maven, exige ensuite que l'installation extraite soit une projection exacte de cette archive : contenu identique, aucun symlink et aucun fichier supplémentaire. Un écart reconstruit le cache extrait depuis l'archive vérifiée.

`scripts/install-jdtls.ps1` conserve l'archive JDT LS sous le cache NEXUS, la re-hashe contre **l'ancre versionnée dans le repository**, puis reconstruit un staging propre à chaque invocation avant de remplacer l'installation existante. Un simple dossier `plugins/` préexistant n'est donc plus une preuve d'intégrité.

`scripts/release/test-tool-integrity-anchors.sh` est exécuté par NEXUS CI. Il vérifie les ancres et altère volontairement un cache extrait synthétique afin de confirmer que mutations et fichiers inattendus sont rejetés. Une montée de version doit modifier version + hash dans la même revue.

## SQLite

Les migrations sont forward-only et protégées par checksum `script_sha256`. Depuis V005, `symbols` impose :

```text
start_line >= 1
end_line >= start_line
```

V004 invalide les index historiques incompatibles avant V005. Les tests couvrent base fraîche, upgrade V004, conservation des données valides, idempotence et rejet d'INSERT SQL invalide.

## Dependabot

`.github/dependabot.yml` cible explicitement `develop` pour Maven, GitHub Actions et Docker. Une exception urgente vers `main` doit être explicite, revue et qualifiée ; elle n'est jamais le chemin par défaut.

## Politique d'échec

Un résultat rouge ou ambigu n'est pas un PASS :

- tests/JaCoCo : corriger code ou test ;
- CodeQL/OSV/Trivy/SonarCloud : traiter le finding/gate ;
- contrat documentaire divergent : corriger doc ou source de vérité, jamais supprimer le contrôle sans justification ;
- artefact/hash/digest incohérent : échec ;
- registry ambiguë : échec ;
- benchmark hors budget : analyser le run exact-head ;
- tag immuable divergent : échec définitif, jamais d'écrasement automatique.
