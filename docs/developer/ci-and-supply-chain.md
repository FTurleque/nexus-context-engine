# CI, couverture et supply-chain

Ce document décrit le contrat courant de qualification et de publication de NEXUS 0.2.0 après la campagne NXA3. Le code et les workflows versionnés restent l'autorité exécutable.

## Branches et exact-head

`develop` est la branche d'intégration. `main` est la branche de release. Une promotion vers `main` n'est autorisée qu'après qualification du **HEAD exact** candidat.

NEXUS CI et CodeQL checkoutent explicitement `github.event.pull_request.head.sha` sur pull request. CodeQL vérifie ensuite que `git rev-parse HEAD` correspond au SHA attendu.

La protection GitHub de `develop` est un contrôle de gouvernance distinct. Le contrat attendu est documenté dans [`branch-governance.md`](branch-governance.md).

## NEXUS CI

`.github/workflows/ci.yml` qualifie `develop` et `main` :

- Windows : Java 24 et qualification locale ;
- Linux : Java 21, **Maven 3.9.16**, reactor complet, tests, distribution, JaCoCo, SBOM et notices ;
- vérification explicite des ancres d'intégrité Maven/JDT LS ;
- vérification des contrats documentaires machine-vérifiables avant le build.

Les actions GitHub contrôlées par le dépôt sont référencées par SHA immuable.

## CodeQL et OSV

- CodeQL : Java/Kotlin `security-extended`, contrat exact-head ;
- OSV : delta PR + scan bloquant du SBOM CycloneDX agrégé ;
- le reusable workflow OSV est épinglé au SHA qui échoue fermé lorsqu'un scan ne se termine pas correctement.

## Benchmarks

`scale-benchmark.yml` couvre :

- SQLite ;
- graphe ;
- fédération ;
- **découverte native filesystem sous `ContextDiscoveryLimits`**.

Le benchmark natif crée un corpus hermétique de 1 000 skills et vérifie le nombre exact d'entrées visitées, de candidats, les octets consommés, le déterminisme et la durée. Voir [`native-context-discovery-limits.md`](native-context-discovery-limits.md).

## Docker Distribution : construire une fois

`.github/workflows/docker-distribution.yml` ne publie rien dans GHCR. Il construit l'image une seule fois et exécute sur cette image les smokes CLI/MCP/REST, Trivy, SBOM et gates de vulnérabilités.

Pour une release, il exporte ensuite l'image qualifiée avec son SHA-256 d'archive, son ID Docker et le SHA Git. Le job de publication vérifie ce handoff et **ne reconstruit jamais l'image**.

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

`config/tool-integrity.properties` contient les ancres indépendantes des outils téléchargés à version fixe :

- Maven 3.9.16 : SHA-512 ;
- Eclipse JDT LS 1.60.0-202606262232 : SHA-256.

`scripts/release/test-tool-integrity-anchors.sh` est exécuté par NEXUS CI. Une montée de version doit modifier version + hash dans la même revue.

## SQLite

Les migrations sont forward-only et protégées par checksum. Depuis V005, `symbols` impose :

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
- CodeQL/OSV/Trivy : traiter le finding ;
- artefact/hash/digest incohérent : échec ;
- registry ambiguë : échec ;
- benchmark hors budget : analyser le run exact-head ;
- tag immuable divergent : échec définitif, jamais d'écrasement automatique.
