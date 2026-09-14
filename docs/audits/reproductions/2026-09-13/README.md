# Reproductions de l'audit après fusion #221

Référence : `6f863b78c1f7d384e234e1de64be850da31dda5c`.
Voir le [rapport](../../2026-09-13-audit-complet-apres-merge-221.md).
Les sondes n'utilisent que des données synthétiques et des répertoires
temporaires distincts. Elles n'accèdent à aucun projet enregistré et ne font
aucun appel réseau. Java 21 est requis.

Depuis la racine du dépôt, après `mvn clean install` (ou `mvnw.cmd -B clean install`
sous Windows), exécuter :

```text
java -Xmx256m -cp core/target/nexus-context-engine-0.2.0-cli.jar docs/audits/reproductions/2026-09-13/NexusAuditProbe.java
java --enable-native-access=ALL-UNNAMED -Xmx256m -cp core/target/nexus-context-engine-0.2.0-cli.jar docs/audits/reproductions/2026-09-13/NexusSemanticAuditProbe.java
```

`NexusAuditProbe` imprime le résultat de la rédaction puis la taille de la
métadonnée YAML décodée. L'appel réflexif sert uniquement à accéder au vrai
parseur interne de production. Résultats constatés : suffixe secret visible,
mot de passe sous clé à guillemets simples intact, 498 octets lus pour
2 621 436 caractères de métadonnée. La durée varie selon la machine.

`NexusSemanticAuditProbe` ouvre deux instances indépendantes sur le même index.
Sur Windows, l'exception de suppression est le résultat défectueux attendu.
Sur Linux, comparer `fresh` et `cached` : seul le premier renvoie `after.java`.
Le code zéro de la sonde Linux ne constitue donc pas un test de correction.
Ces sondes impriment les observations ; elles ne remplacent pas les futurs
tests de non-régression avec assertions.

Le benchmark graphe a été exécuté sous Linux avec :

```sh
export JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx4g'
./mvnw -B -pl core -Dtest=GraphScaleRegressionBenchmarkTest \
  -Dnexus.scale.benchmark.enabled=true \
  -Dnexus.scale.benchmark.profile=full \
  -Dnexus.graph.scale.benchmark.output="$PWD/target/audit-graph-full.json" test
```

Il consomme plusieurs minutes, de la mémoire et de l'espace disque pour sa
base temporaire. `graph-full.json` conserve la mesure originale. Pour vérifier
uniquement son dépassement du plafond actuel, indépendamment des autres
rapports exigés par le vérificateur complet :

```sh
python3 -c 'import json; r=json.load(open("docs/audits/reproductions/2026-09-13/graph-full.json")); assert r["p95Ms"] <= 15000, r["p95Ms"]'
```

Cette commande échoue avec `AssertionError: 32251.22` sur le rapport conservé.
`baseline.json` enregistre l'inventaire et les comptes Surefire du build
complet ; il ne représente pas une base comparative de performance.
