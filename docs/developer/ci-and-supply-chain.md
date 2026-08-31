# CI, couverture et supply-chain

Ce document décrit le contrat courant de qualification et de publication de NEXUS 0.2.0 après la campagne NXA3. Le code et les workflows versionnés restent l'autorité exécutable ; ce document explicite les invariants attendus.

## Branches et qualification

`develop` est la branche d'intégration. `main` est la branche de release. Une promotion `develop -> main` n'est autorisée qu'après qualification du **HEAD exact** candidat par les gates applicables au diff.

Les workflows NEXUS CI et CodeQL utilisent explicitement le SHA de tête de la pull request pour le checkout. CodeQL ne doit donc pas être interprété comme une qualification implicite du merge-ref synthétique GitHub.

La protection GitHub de `develop` (PR obligatoire, checks requis, interdiction force-push/suppression) est un contrôle de gouvernance distinct du code versionné et doit être configurée par un administrateur du repository.

## NEXUS CI

`.github/workflows/ci.yml` qualifie `develop` et `main` sur Windows et Linux :

- Windows : Java 24 et script de qualification locale ;
- Linux : Java 21, reactor Maven complet, tests, JaCoCo, distribution autonome, SBOM et notices.

Les actions GitHub contrôlées par le dépôt sont référencées par SHA immuable. Les commentaires de version sont informatifs ; le SHA est l'autorité.

### Couverture

Les planchers bloquants restent définis dans les POM des modules. Les rapports sont conservés sous :

```text
core/target/site/jacoco/jacoco.xml
adapters/rest-quarkus/target/site/jacoco/jacoco.xml
adapters/mcp-java/target/site/jacoco/jacoco.xml
adapters/assistant-clients/target/site/jacoco/jacoco.xml
```

Un seuil ne doit pas être abaissé pour faire passer une régression.

## OSV et CodeQL

`.github/workflows/osv-scanner.yml` scanne le delta PR et le SBOM CycloneDX agrégé du reactor avec échec sur vulnérabilité selon la politique du workflow.

`.github/workflows/codeql.yml` analyse Java/Kotlin avec `security-extended`. Sur pull request, le workflow checkout et vérifie le SHA exact `github.event.pull_request.head.sha`; pour les autres événements il qualifie `github.sha`. Toute dérive de checkout est un échec explicite.

## Benchmarks

`scale-benchmark.yml` et `scanner-corpus-benchmark.yml` fournissent des budgets de non-régression. Les PR utilisent leur profil automatique ; la release exige les profils `full` via `workflow_call`.

Le contexte natif possède en outre des bornes de travail avant sélection de tokens (`ContextDiscoveryLimits`) sur :

- entrées filesystem/Git visitées ;
- ressources candidates ;
- octets cumulés lus/rendus ;
- durée globale de découverte.

Le dépassement est fail-closed. Les limites peuvent être ajustées par les variables `NEXUS_CONTEXT_DISCOVERY_*` documentées dans `native-context-discovery-limits.md`.

## Docker Distribution : build unique qualifié

`.github/workflows/docker-distribution.yml` ne publie rien dans GHCR. Il construit l'image NEXUS **une seule fois** puis exécute sur cette même image :

1. smokes CLI, MCP et REST ;
2. qualification Docker/Compose ;
3. rapport Trivy ;
4. SBOM CycloneDX image ;
5. gate HIGH/CRITICAL corrigibles.

Lorsqu'il est appelé par le workflow de release avec `export_qualified_image: true`, il exporte ensuite exactement cette image via `docker save`, calcule le SHA-256 de l'archive et expose aussi l'ID de configuration Docker de l'image. L'archive et ses métadonnées sont conservées comme artefact à courte rétention.

Le job de publication **ne reconstruit jamais l'image**.

## Publication GHCR immuable et reprise

`.github/workflows/release.yml` est déclenché uniquement par un tag `vX.Y.Z`. Avant publication, il vérifie :

- SemVer strict ;
- égalité avec la version Maven ;
- égalité entre commit taggé et HEAD courant de `main` ;
- succès de NEXUS CI, Windows Installer, Docker Distribution, benchmarks `full`, CodeQL et OSV.

Le job de publication télécharge l'archive Docker qualifiée et les preuves de sécurité produites par le gate Docker. Il vérifie le SHA-256 de l'archive contre l'output du workflow appelant, charge l'image, puis vérifie son ID Docker. Il n'exécute aucun second `docker build`.

### Préflight GHCR fail-closed

`scripts/release/ghcr-immutable-preflight.sh` distingue :

- tag réellement absent : publication permise ;
- erreur auth/réseau/serveur ou résultat ambigu : échec ;
- tag existant avec le même ID d'image qualifiée : reprise idempotente permise ;
- tag existant avec un autre contenu : conflit d'immutabilité et échec.

Si les tags version et SHA existent tous deux, ils doivent également résoudre le même digest de manifeste.

### Publication et attestations

Les tags immuables sont :

```text
<X.Y.Z>
sha-<commit>
```

Un retry après publication partielle complète uniquement le tag manquant à partir du tag immuable déjà validé. Les deux tags sont ensuite relus et doivent correspondre exactement à l'image qualifiée et au même digest.

Les attestations de provenance et de SBOM portent sur ce digest publié. `latest` est le seul pointeur mutable et n'est déplacé qu'après succès des tags immuables et des attestations.

## Ancres d'intégrité des outils téléchargés

Les outils à version fixe ne téléchargent plus leur archive **et** leur checksum depuis la même origine pendant l'installation.

Les valeurs attendues sont versionnées dans :

```text
config/tool-integrity.properties
```

Actuellement :

- Maven 3.9.11 : SHA-512 épinglé dans Git ;
- Eclipse JDT LS 1.60.0-202606262232 : SHA-256 épinglé dans Git.

`mvnw` et `scripts/install-jdtls.ps1` échouent si l'ancre manque, est invalide ou ne correspond pas aux octets téléchargés. Une montée de version doit modifier version + hash dans la même revue et justifier la provenance de l'ancre indépendante.

## SQLite

Les migrations sont forward-only et protégées par checksum. Depuis V005, la table `symbols` impose au niveau SQLite les mêmes invariants que le domaine Java :

```text
start_line >= 1
end_line >= start_line
```

V004 nettoie les index historiques invalides avant la reconstruction V005.

## Dependabot

`.github/dependabot.yml` surveille Maven, GitHub Actions et Docker chaque semaine et cible explicitement `develop`. Une mise à jour urgente qui contournerait cette branche d'intégration doit être un chemin d'exception explicite et revu, jamais le comportement par défaut.

## Politique d'échec

Un gate rouge ou ambigu n'est pas un PASS :

- Maven/tests/JaCoCo : corriger code ou tests ;
- CodeQL/OSV/Trivy : corriger ou traiter explicitement le finding ;
- artefact/hachage/digest incohérent : échec ;
- registry indisponible pendant le préflight : échec ;
- benchmark hors budget : analyser le run exact-head avant tout rerun ;
- tag immuable avec contenu différent : échec définitif, jamais écrasement automatique.
