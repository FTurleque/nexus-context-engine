# CI, couverture et supply-chain

Ce document décrit le contrat courant de qualification et de publication de NEXUS 0.2.0 après les campagnes **NXA3 + NXA4 + NXA7**. Le code et les workflows versionnés restent l'autorité exécutable.

## Branches et exact-head

`develop` est la branche d'intégration. `main` est la branche de release. Une promotion vers `main` n'est autorisée qu'après qualification du **HEAD exact** candidat.

NEXUS CI et CodeQL checkoutent explicitement `github.event.pull_request.head.sha` sur pull request. CodeQL vérifie ensuite que `git rev-parse HEAD` correspond au SHA attendu.

La protection GitHub de `develop` est un contrôle de gouvernance distinct. Le contrat attendu est documenté dans [`branch-governance.md`](branch-governance.md). Tant que `develop` retourne `protected=false`, #130 reste ouvert.

Tant que ce contrôle repository-admin n'est pas actif, les workflows versionnés conservent une défense en profondeur sur les pushes directs `develop` : NEXUS CI, CodeQL et OSV écoutent directement la branche ; les gates Docker, benchmarks et Windows sont réutilisés par des callers dédiés avec leurs filtres de chemins.

## NEXUS CI

`.github/workflows/ci.yml` qualifie `develop` et `main` :

- Windows : Java 24 et qualification locale ;
- Linux : Java 21, **Maven 3.9.16**, reactor complet, tests, distribution, JaCoCo, SBOM et notices ;
- vérification explicite des ancres d'intégrité Maven/JDT LS ;
- vérification des contrats documentaires machine-vérifiables **avant** le reactor.

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
5. n'extrait Maven qu'après cette vérification.

La résilience réseau ne modifie donc pas la racine de confiance du bootstrap.

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

- le builder utilise le wrapper repository-pinned `./mvnw`, donc Maven **3.9.16** après vérification de l'ancre SHA-512 versionnée ;
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

Sur Unix, `mvnw` télécharge Maven puis vérifie l'ancre avant extraction. Sur Windows, `mvnw.cmd` applique le même contrat quel que soit le client HTTP utilisé (`curl.exe` ou fallback PowerShell).

`scripts/install-jdtls.ps1` télécharge l'archive JDT LS puis la compare à **l'ancre versionnée dans le repository** ; il ne prend pas sa décision de confiance sur un checksum téléchargé depuis le même origin.

`scripts/release/test-tool-integrity-anchors.sh` est exécuté par NEXUS CI. Une montée de version doit modifier version + hash dans la même revue.

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
