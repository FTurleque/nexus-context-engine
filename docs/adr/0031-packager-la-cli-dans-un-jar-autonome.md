---
status: accepted
date: 2026-07-19
---

# ADR-0031 — Packager la CLI dans un JAR autonome

## Contexte et problème

Jusqu'à l'Itération 3, la CLI NEXUS est principalement exécutée avec `mvn exec:java`. Ce mode convient au développement mais impose Maven, le repository source et la résolution des dépendances au moment de l'utilisation.

L'Itération 4 doit rendre le MVP utilisable comme un outil local autonome, tout en conservant le JAR Maven standard pour la réutilisation comme bibliothèque.

## Facteurs de décision

- fournir un artefact directement exécutable avec Java 21 ;
- embarquer les dépendances runtime de SQLite, Lucene, JavaParser et JGit ;
- conserver le JAR standard non ombré pour les usages Maven futurs ;
- éviter un installeur natif ou jlink prématuré ;
- garder le packaging reproductible dans Maven.

## Options envisagées

1. continuer à exiger `mvn exec:java` ;
2. produire uniquement un JAR avec `Main-Class`, sans dépendances ;
3. produire un second JAR autonome via Maven Shade Plugin ;
4. construire immédiatement une image native ou un runtime jlink.

## Décision retenue

**Option retenue : produire, en plus du JAR standard, un JAR autonome attaché avec le classifier `cli` via Maven Shade Plugin.**

Le build doit produire :

```text
target/nexus-context-engine-<version>.jar
    JAR standard du projet

target/nexus-context-engine-<version>-cli.jar
    JAR autonome avec dépendances et Main-Class
```

La classe principale reste :

```text
io.github.fturleque.nexus.cli.NexusCli
```

Le JAR autonome peut être lancé avec :

```text
java -jar target/nexus-context-engine-<version>-cli.jar ...
```

Des scripts Windows légers peuvent rechercher ce JAR dans `target/` et déléguer l'exécution à `java -jar`.

## Conséquences positives

- la CLI peut être utilisée sans Maven après le build ;
- le JAR standard reste disponible pour la consommation comme dépendance ;
- les dépendances runtime sont embarquées dans l'artefact CLI ;
- le packaging reste indépendant d'un OS particulier.

## Conséquences négatives et compromis acceptés

- l'artefact CLI est plus volumineux ;
- le build produit deux JAR ;
- certaines signatures de dépendances doivent être exclues lors du shading ;
- Java 21 reste requis sur la machine cible.

## Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| écrasement du JAR principal | Élevé | attacher l'uber-JAR avec classifier `cli` |
| conflit de fichiers META-INF signés | Élevé | exclure `META-INF/*.SF`, `*.DSA`, `*.RSA` |
| dépendances natives SQLite | Moyen | valider le JAR autonome par le self-smoke ou un test de lancement |
| divergence entre Maven exec et JAR CLI | Moyen | même `Main-Class` et mêmes services de composition |

## Confirmation

La décision est respectée lorsque :

- `mvn clean install` produit le JAR standard et le JAR `-cli.jar` ;
- `java -jar ...-cli.jar --help` fonctionne ;
- le JAR autonome peut enregistrer, indexer, rechercher et construire un contexte ;
- le JAR standard reste installé dans le dépôt Maven local.

## Analyse détaillée des options

### Maven exec uniquement

**Avantages :** aucune configuration supplémentaire.

**Inconvénients :** pas réellement utilisable comme outil autonome.

### JAR non autonome avec Main-Class

**Avantages :** artefact léger.

**Inconvénients :** nécessite de reconstruire manuellement un classpath de dépendances.

### Uber-JAR avec classifier

**Avantages :** exécution simple, tout en préservant le JAR bibliothèque.

**Inconvénients :** taille supérieure et étape de packaging additionnelle.

### Runtime natif/jlink

**Avantages :** expérience utilisateur plus intégrée.

**Inconvénients :** packaging multiplateforme et maintenance disproportionnés pour le MVP.

## Impact architectural

Le changement concerne uniquement la couche de build et l'adaptateur CLI. Le cœur métier reste inchangé.

## Conditions de réévaluation

Réévaluer si NEXUS doit être distribué publiquement sous forme d'installateurs, de paquets OS ou d'image native.

## Décisions liées

- ADR-0015 — valider le MVP par la CLI avant les intégrations ;
- ADR-0004 — démarrer avec un seul module Maven ;
- ADR-0030 — stabiliser le contrat CLI humain/JSON et les codes de sortie.
