# CI, couverture et supply-chain

Ce document décrit les gates de qualité et de sécurité applicables à NEXUS 0.2.0.

## Objectifs

La CI doit empêcher l'intégration silencieuse de quatre classes de régression :

1. build ou distribution non fonctionnels sur les plateformes supportées ;
2. baisse matérielle de couverture du cœur ;
3. nouvelle vulnérabilité dans le graphe de dépendances ;
4. régression de l'inventaire de conformité (licence NEXUS, notices tierces, SBOM).

Les workflows GitHub Actions utilisent des **SHA de commit immuables**. Les commentaires de version (`# vX.Y.Z`) sont informatifs ; le SHA est l'autorité exécutée.

## NEXUS CI

`.github/workflows/ci.yml` qualifie chaque pull request vers `main` :

- **Windows gate** : Java 24, `scripts/validate-phase-6.ps1` ;
- **Linux reactor Maven build** : Java 21, reactor complet puis smoke de la distribution autonome.

Le script Windows et le job Linux vérifient tous deux les artefacts de conformité distribués.

### Couverture JaCoCo

Le gate de couverture s'applique au module `core`, qui contient la logique métier et les tests historiques.

Baseline mesurée pendant la calibration de #22 :

| Compteur | Couverture mesurée | Minimum bloquant |
|---|---:|---:|
| lignes | 77,07 % | 70 % |
| branches | 58,46 % | 50 % |

Les seuils sont des **planchers de non-régression**, pas un objectif final de qualité. Ils conservent une marge raisonnable sous la baseline réelle tout en rendant une baisse matérielle bloquante. Toute hausse future doit être accompagnée de tests et d'une qualification exacte-head ; une baisse doit être justifiée explicitement et ne doit pas être utilisée pour contourner un défaut de tests.

Le rapport XML est produit dans `core/target/site/jacoco/jacoco.xml`.

## Vulnérabilités — OSV-Scanner

`.github/workflows/osv-scanner.yml` utilise le workflow officiel OSV-Scanner épinglé à un commit immuable.

Politique :

- sur une PR vers `main`, le workflow compare la base et le changement et fait échouer le gate lorsqu'une **nouvelle vulnérabilité** est introduite ;
- sur `main`, en planification hebdomadaire ou en lancement manuel, l'état courant des dépendances est rescanné et publié sans transformer automatiquement une dette préexistante en blocage de toutes les opérations ;
- les `pom.xml` Maven sont analysés avec résolution des dépendances transitives supportée par OSV-Scanner.

Le premier essai avec GitHub Dependency Review a été abandonné : cette Action exigeait l'activation du Dependency Graph du repository. OSV-Scanner fournit un gate autonome qui ne dépend pas de ce réglage GitHub.

## Politique de licences tierces

La compatibilité d'une licence ne doit pas être décidée par une simple recherche textuelle de `GPL` : certaines dépendances déclarent plusieurs licences ou des exceptions (par exemple Classpath Exception), et la licence applicable dépend du composant et de la modalité de distribution.

La politique automatisée de NEXUS est donc :

- chaque dépendance compile/runtime distribuée doit fournir une information de licence exploitable ;
- `license-maven-plugin` est exécuté avec `failOnMissing=true` ;
- les modules `io.github.fturleque` et les dépendances de test ne sont pas considérés comme composants tiers distribués ;
- toute nouvelle dépendance sous copyleft fort ou sous conditions inhabituelles doit faire l'objet d'une revue explicite de compatibilité avec le modèle propriétaire avant merge ;
- une exception juridique ne doit jamais être créée en désactivant silencieusement la génération de notices.

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

`THIRD_PARTY_NOTICES.txt` est généré par `org.codehaus.mojo:license-maven-plugin` à partir des dépendances compile/runtime du reactor. La calibration de #22 a produit un inventaire non vide couvrant les dépendances tierces du reactor et a passé `failOnMissing=true`.

L'archive autonome contient :

```text
LICENSE
THIRD_PARTY_NOTICES.txt
SBOM.cdx.json
```

Le script de qualification Windows compare les hashes des fichiers embarqués aux fichiers générés afin d'éviter un ZIP contenant un inventaire obsolète.

Le job Linux conserve également pendant 90 jours un artefact `nexus-ci-evidence-<qualified-head-sha>` contenant :

- le SBOM ;
- les notices tierces ;
- le rapport JaCoCo XML/HTML disponible.

Le nom de cet artefact utilise le head exact de la PR, pas le merge SHA synthétique créé par GitHub pour l'événement `pull_request`.

Le SBOM reste en plus embarqué dans le ZIP ; une release qui conserve le ZIP conserve donc son inventaire logiciel indépendamment de la rétention GitHub Actions.

## Politique d'échec

Un gate rouge n'est jamais interprété comme PASS sans preuve contraire exécutable.

- échec Maven/tests/JaCoCo : corriger le code ou les tests ;
- licence tierce manquante : résoudre/valider l'information de licence, ne pas désactiver `failOnMissing` par réflexe ;
- nouvelle vulnérabilité OSV : mettre à jour/remplacer la dépendance ou analyser explicitement le risque avant toute exception ;
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
OSV new-vulnerability gate
CodeQL Java analysis
```

Pour un repository maintenu par une seule personne, l'obligation de PR peut rester active avec **0 approbation humaine requise** ; la protection repose alors sur les gates automatisés et la résolution des conversations, sans imposer une auto-approbation que GitHub ne permet pas.
