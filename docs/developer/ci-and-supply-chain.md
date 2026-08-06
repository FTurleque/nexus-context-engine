# CI, couverture et supply-chain

Ce document décrit les gates de qualité et de sécurité applicables à NEXUS 0.2.0.

## Objectifs

La CI doit empêcher l'intégration silencieuse de quatre classes de régression :

1. build ou distribution non fonctionnels sur les plateformes supportées ;
2. baisse matérielle de couverture du cœur ;
3. nouvelle dépendance vulnérable ou sous licence incompatible avec une distribution propriétaire ;
4. régression de l'inventaire de conformité (licence NEXUS, notices tierces, SBOM).

Les workflows GitHub Actions utilisent des **SHA de commit immuables**. Les commentaires de version (`# vX.Y.Z`) sont informatifs ; le SHA est l'autorité exécutée.

## NEXUS CI

`.github/workflows/ci.yml` qualifie chaque pull request vers `main` :

- **Windows gate** : Java 24, `scripts/validate-phase-6.ps1` ;
- **Linux reactor Maven build** : Java 21, reactor complet puis smoke de la distribution autonome.

Le script Windows et le job Linux vérifient tous deux les artefacts de conformité distribués.

### Couverture JaCoCo

Le gate de couverture s'applique au module `core`, qui contient la logique métier et les tests historiques.

Seuils de régression initiaux :

| Compteur | Minimum |
|---|---:|
| lignes | 50 % |
| branches | 35 % |

Ces seuils sont des **planchers de non-régression**, pas un objectif final de qualité. Ils doivent rester sous la couverture réelle mesurée de la baseline qualifiée. Toute hausse future doit être accompagnée de tests et d'une qualification exacte-head ; une baisse doit être justifiée explicitement et ne doit pas être utilisée pour contourner un défaut de tests.

Le rapport XML est produit dans `core/target/site/jacoco/jacoco.xml`.

## Dependency Review

`.github/workflows/dependency-review.yml` s'exécute sur les PR vers `main`.

Politique :

- toute nouvelle vulnérabilité de sévérité **High** ou **Critical** bloque le check ;
- aucune exception GHSA n'est pré-autorisée ;
- les nouvelles dépendances sous licences fortement copyleft suivantes sont refusées pour préserver le modèle propriétaire de distribution : GPL-2.0-only/or-later, GPL-3.0-only/or-later et AGPL-3.0-only/or-later ;
- toute exception future doit être documentée dans une PR avec analyse de compatibilité juridique et sécurité.

Dependency Review compare la PR à sa base : il ne remplace pas l'inventaire périodique ni Dependabot.

## Dependabot

`.github/dependabot.yml` surveille chaque semaine :

- Maven ;
- GitHub Actions.

Les PR Dependabot passent par les mêmes gates que les autres changements. Une mise à jour d'Action doit conserver un pin sur un SHA immuable dans le workflow final.

## CodeQL

`.github/workflows/codeql.yml` analyse Java/Kotlin avec CodeQL :

- sur PR vers `main` ;
- sur push vers `main` ;
- une fois par semaine ;
- sur déclenchement manuel.

Le mode `none` est utilisé pour l'analyse Java afin d'éviter un second build Maven inutile ; les queries `security-extended` complètent les contrôles par défaut.

Un finding CodeQL doit être trié avant merge lorsqu'il est exposé comme alerte bloquante par la politique de protection du dépôt. Les suppressions/queries custom doivent être justifiées dans la PR.

## SBOM et notices tierces

Le reactor génère :

```text
target/sbom/bom.json
target/licenses/THIRD_PARTY_NOTICES.txt
```

`bom.json` est un SBOM CycloneDX agrégé.

`THIRD_PARTY_NOTICES.txt` est généré par `org.codehaus.mojo:license-maven-plugin` à partir des dépendances compile/runtime du reactor. Les modules `io.github.fturleque` et les dépendances de test ne sont pas traités comme composants tiers distribués. Le build échoue si une dépendance considérée ne fournit pas de licence exploitable.

L'archive autonome contient :

```text
LICENSE
THIRD_PARTY_NOTICES.txt
SBOM.cdx.json
```

Le script de qualification Windows compare les hashes des fichiers embarqués aux fichiers générés afin d'éviter un ZIP contenant un inventaire obsolète.

Le job Linux conserve également pendant 90 jours un artefact `nexus-ci-evidence-<sha>` contenant :

- le SBOM ;
- les notices tierces ;
- le rapport JaCoCo XML/HTML disponible.

Le SBOM reste en plus embarqué dans le ZIP ; une release qui conserve le ZIP conserve donc son inventaire logiciel indépendamment de la rétention GitHub Actions.

## Politique d'échec

Un gate rouge n'est jamais interprété comme PASS sans preuve contraire exécutable.

- échec Maven/tests/JaCoCo : corriger le code ou les tests ;
- licence tierce manquante : résoudre/valider l'information de licence, ne pas désactiver `failOnMissing` par réflexe ;
- vulnérabilité High/Critical : mettre à jour/remplacer la dépendance ou documenter une décision de risque explicite avant toute exception ;
- CodeQL : analyser le finding et corriger ou justifier la suppression ;
- artefact SBOM/notices absent du ZIP : échec de distribution.

## Mise à jour des Actions épinglées

Lors d'une mise à jour d'Action :

1. identifier une release officielle ;
2. résoudre son commit exact ;
3. remplacer le SHA et mettre à jour le commentaire de version ;
4. exécuter tous les workflows de PR ;
5. ne jamais remplacer un SHA par un tag mutable (`@v4`, `@main`, etc.).

## Protection de `main`

Le ruleset de `main` est configuré dans GitHub et n'est pas versionné dans le repository. Une fois les nouveaux workflows stabilisés, les checks suivants sont les candidats à rendre obligatoires :

```text
Windows gate
Linux reactor Maven build
Dependency vulnerability and license review
CodeQL Java analysis
```

Pour un repository maintenu par une seule personne, l'obligation de PR peut rester active avec **0 approbation humaine requise** ; la protection repose alors sur les gates automatisés et la résolution des conversations, sans imposer une auto-approbation que GitHub ne permet pas.
