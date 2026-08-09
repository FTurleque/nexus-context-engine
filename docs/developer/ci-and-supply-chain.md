# CI, couverture et supply-chain

Ce document décrit les gates de qualité et de sécurité applicables à NEXUS 0.2.0 après la consolidation post-audit de l'issue #48 / PR #49.

## Objectifs

La CI doit empêcher l'intégration silencieuse de régressions de :

1. build, tests ou distribution sur les plateformes supportées ;
2. couverture du cœur ;
3. vulnérabilités du reactor Maven ;
4. sécurité de l'image Docker ;
5. inventaire de conformité (licence, notices tierces, SBOM) ;
6. comportement Windows/Docker de l'installateur et des launchers ;
7. passage à l'échelle sur SQLite, graphe et contexte fédéré.

Les workflows GitHub Actions utilisent des **SHA de commit immuables** pour les Actions contrôlées par le dépôt. Les commentaires de version (`# vX.Y.Z`) sont informatifs ; le SHA est l'autorité exécutée.

## NEXUS CI

`.github/workflows/ci.yml` qualifie les pull requests vers `main` et les pushes configurés :

- **Windows gate** : Java 24, script de qualification locale ;
- **Linux reactor Maven build** : Java 21, reactor complet, distribution autonome et artefacts de conformité.

Le reactor porte le gate JaCoCo, les tests unitaires/intégration et les contrôles Maven. Les surfaces REST et MCP sont couvertes par les modules du reactor.

### Couverture JaCoCo

Le gate de couverture s'applique au module `core`.

| Compteur | Baseline historique qualifiée | Minimum bloquant |
|---|---:|---:|
| lignes | 77,07 % | 70 % |
| branches | 58,46 % | 50 % |

Les seuils sont des planchers de non-régression. Ils ne doivent pas être abaissés pour contourner un défaut de tests.

Le rapport XML est produit dans `core/target/site/jacoco/jacoco.xml`.

## Vulnérabilités — OSV-Scanner

`.github/workflows/osv-scanner.yml` combine désormais deux protections complémentaires.

### Delta PR

Sur une pull request vers `main`, le workflow réutilisable OSV compare la base et le changement et bloque l'introduction d'une **nouvelle vulnérabilité**.

### SBOM agrégé du reactor

Le workflow construit en plus le reactor Maven avec Java 21 et génère :

```text
target/sbom/bom.json
```

Le SBOM CycloneDX agrégé est vérifié comme non trivial, copié sous `nexus.cdx.json`, publié temporairement comme artefact puis scanné par OSV avec `fail-on-vuln: true`.

Cette étape est l'autorité pour la couverture complète du reactor et de ses dépendances transitives matérialisées dans le SBOM. Elle remplace l'ancienne formulation « scan courant non bloquant » qui ne correspond plus à la politique active.

## CodeQL

`.github/workflows/codeql.yml` analyse Java/Kotlin avec CodeQL et les queries `security-extended` selon les événements configurés dans le workflow.

Un finding CodeQL doit être trié avant merge lorsqu'il est exposé comme bloquant. Les suppressions doivent être explicites et justifiées.

## Scale Benchmark

`.github/workflows/scale-benchmark.yml` exécute un benchmark hermétique sur trois axes :

- SQLite/recherches et concurrence ;
- projections et voisinages de graphe ;
- coût de travail du contexte fédéré.

Le profil automatique de PR utilise la taille `ci`; le profil `full` reste disponible en déclenchement manuel lorsque nécessaire.

Le gate produit et vérifie :

```text
target/scale-benchmark.json
target/graph-scale-benchmark.json
target/federated-budget-scale-benchmark.json
```

Les budgets sont des garde-fous de régression. Un outlier d'infrastructure peut justifier un rerun exact-head documenté, mais ne justifie pas d'assouplir le budget sans analyse.

## Windows Installer

`.github/workflows/windows-installer.yml` qualifie l'artefact Windows self-contained :

- échappement des configurations `.cmd` générées ;
- construction de la distribution Windows ;
- construction d'un setup smoke isolé ;
- installation, exécution et désinstallation smoke ;
- construction du setup production ;
- vérification et conservation des artefacts.

Le smoke valide le **profil réellement installé** : REST reste optionnel dans le profil natif recommandé. Les launchers portables complets sont qualifiés au niveau de la distribution, sans forcer l'installation d'un composant optionnel.

## Docker Distribution

`.github/workflows/docker-distribution.yml` qualifie la parité et la supply-chain conteneur.

### Runtime

Le job principal vérifie :

- round-trip littéral de la configuration dotenv/Compose ;
- build de l'image exacte ;
- smoke CLI ;
- smoke MCP STDIO ;
- smoke REST sur port hôte personnalisé ;
- fallback de payload installé.

### Vulnérabilités et SBOM image

Trivy produit :

```text
target/trivy-image-vulnerabilities.json
target/nexus-image.cdx.json
```

Le workflow bloque ensuite les vulnérabilités **HIGH ou CRITICAL corrigibles** avec `ignore-unfixed: true` et `exit-code: 1`.

Les preuves de sécurité image sont conservées en artefacts CI.

### Publication et attestations

Sur un push vers `main`, après succès du job Docker :

1. l'image exacte est reconstruite ;
2. le gate Trivy est rejoué sur l'image de publication ;
3. un SBOM CycloneDX de publication est généré ;
4. les tags versionné et `latest` sont poussés vers GHCR ;
5. le digest réellement publié est résolu ;
6. une attestation de provenance est publiée pour ce digest ;
7. une attestation SBOM est publiée pour ce même digest.

Les attestations portent donc sur le sujet publié, pas seulement sur un tag mutable.

## Politique de licences tierces

La compatibilité juridique ne doit pas être déduite d'une simple recherche de chaîne dans un nom de licence.

La politique automatisée de NEXUS est :

- chaque dépendance compile/runtime distribuée doit fournir une information de licence exploitable ;
- `license-maven-plugin` s'exécute avec `failOnMissing=true` ;
- les modules `io.github.fturleque` et dépendances de test ne sont pas comptés comme composants tiers distribués ;
- toute dépendance sous conditions inhabituelles exige une revue explicite ;
- aucune exception ne doit être créée en désactivant silencieusement la génération de notices.

## SBOM et notices tierces

Le reactor génère :

```text
target/sbom/bom.json
target/licenses/THIRD_PARTY_NOTICES.txt
```

L'archive autonome contient :

```text
LICENSE
THIRD_PARTY_NOTICES.txt
SBOM.cdx.json
```

La qualification Windows compare les artefacts embarqués aux outputs générés afin d'éviter une distribution avec un inventaire obsolète.

Le job Linux conserve les preuves de conformité prévues par le workflow. L'image Docker possède en parallèle son propre SBOM et ses propres preuves Trivy.

## Dependabot

`.github/dependabot.yml` surveille chaque semaine :

- Maven ;
- GitHub Actions ;
- Docker sous `packaging/docker`.

Les PR Dependabot passent par les mêmes gates déclenchés par leur diff. Une mise à jour d'Action doit conserver un pin sur un SHA immuable.

## Politique d'échec

Un gate rouge n'est jamais interprété comme PASS sans preuve contraire exécutable.

- Maven/tests/JaCoCo : corriger le code ou les tests ;
- OSV : mettre à jour/remplacer la dépendance ou traiter explicitement le risque ;
- CodeQL : corriger ou justifier la suppression ;
- Trivy image : corriger les vulnérabilités HIGH/CRITICAL corrigibles ;
- SBOM/notices absents : échec de conformité ;
- Windows Installer/Docker/Scale : analyser le log exact avant rerun ;
- rerun : uniquement lorsqu'une cause transitoire est démontrée et toujours sur le même HEAD qualifié.

## SonarCloud

Aucun workflow, configuration ou status SonarCloud actif n'est actuellement défini dans la baseline du dépôt. **SonarCloud n'est donc pas un gate exécutable actuel.**

Une documentation historique qui le liste comme gate obligatoire doit être considérée obsolète tant qu'un workflow/status concret n'est pas ajouté et qualifié.

## Protection de `main`

Le ruleset de `main` est géré dans GitHub et n'est pas versionné dans le repository. Il impose notamment le passage par pull request pour les modifications normales de `main`.

La source de vérité des gates exécutés reste la combinaison :

- workflows présents dans `.github/workflows` ;
- événements/path filters qui s'appliquent au diff ;
- checks réellement associés au HEAD de la PR ;
- règles de protection GitHub actives.

Une PR documentaire ne doit pas inventer un gate absent ; elle doit attendre tous les checks réellement déclenchés pour son HEAD.

## Qualification post-audit de référence

PR #49 :

```text
QUALIFIED_HEAD=4f04c1ad3ff5b41aa9d1892ade57ad62b90a43f9
MERGE_SHA=c1ff9ef03ef33097c0d51154e02c30109b0a46f1
```

Résultats :

- NEXUS CI `31314135008` — PASS ;
- Windows Installer `31314134983` — PASS ;
- Docker Distribution `31314134994` — PASS ;
- Scale Benchmark `31314135000` — PASS ;
- CodeQL `31314134977` — PASS ;
- OSV-Scanner `31314135231` — PASS.

La qualification finale d'une future PR doit toujours être rattachée à son propre HEAD exact.
