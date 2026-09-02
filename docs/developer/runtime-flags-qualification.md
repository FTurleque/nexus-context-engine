# Qualification des flags JVM runtime

Ce document décrit la qualification NXA7-P3 suivie par #152 et la décision d'adoption issue des mesures exact-head.

## Warnings de départ

Sur Java 21, les chemins SQLite/Lucene qualifiés par NEXUS peuvent émettre :

- `Use --enable-native-access=ALL-UNNAMED` lors d'un appel FFM/JNI depuis le classpath non modulaire ;
- `Java vector incubator module is not readable` avec la recommandation Lucene `--add-modules jdk.incubator.vector`.

Le scope `ALL-UNNAMED` est évalué parce que les distributions NEXUS actuelles sont des classpath/uber-JARs non modulaires. Il ne doit pas être élargi au-delà de ce que le runtime exige.

## Méthode

`.github/workflows/runtime-flags-qualification.yml` sépare deux questions :

1. **contrat fonctionnel des warnings** :
   - `baseline` : aucun flag additionnel ;
   - `tuned` : `--enable-native-access=ALL-UNNAMED` et, si le runtime expose le module, `--add-modules=jdk.incubator.vector` ;
   - Java 21 / Linux et Java 24 / Windows doivent reproduire les warnings en baseline et les éliminer en tuned sans régression SQLite/Lucene ;
2. **effet performance du Vector API** :
   - profil A `native-only` : `--enable-native-access=ALL-UNNAMED` ;
   - profil B `native+vector` : même flag native-access + `--add-modules=jdk.incubator.vector` ;
   - le workload hermétique `ScaleRegressionBenchmarkTest`, `GraphScaleRegressionBenchmarkTest`, `FederatedContextBudgetScaleBenchmarkTest` et `NativeContextDiscoveryBudgetBenchmarkTest` est exécuté quatre fois sur **le même runner Java 21/Linux** dans l'ordre ABBA : `native-a`, `vector-a`, `vector-b`, `native-b` ;
   - les médianes et ratios Vector/native pour `totalDurationMs` et `graph.p95Ms` sont enregistrés dans `target/runtime-flags/summary.json` ; tous les logs et JSON sont conservés 90 jours.

Le protocole compare `native-only` à `native+vector` afin que l'effet mesuré soit celui du **Vector API uniquement**. L'ABBA same-runner réduit à la fois le bruit inter-machine et le biais d'échauffement.

## Qualification fonctionnelle

Les runs qualifiés ont confirmé que :

- Java 21 / Linux expose `jdk.incubator.vector` et le profil tuned supprime les deux warnings ciblés ;
- Java 24 / Windows (Temurin 24.0.2) expose également `jdk.incubator.vector` et le profil tuned supprime les deux warnings ciblés ;
- les tests SQLite/Lucene restent verts avec et sans flags ;
- le wrapper Windows Maven conserve sa vérification SHA-512 repository-pinned avant extraction via `System.Security.Cryptography.SHA512` / `ComputeHash` ;
- le packaging Windows self-contained complet reste fonctionnel avec ce bootstrap.

La première tentative de comparaison performance exécutait baseline et tuned sur deux runners distincts. Ses nombres ont été explicitement écartés de la décision, car ils mélangeaient effet JVM et variabilité machine.

## Résultat Vector API exact-head

La comparaison same-runner ABBA finale a été exécutée sur le HEAD `55511bf545fc4434ddf688c64f458a79666bad42`, ensuite mergé par #156.

Résultats médians :

- `native-only` total : **14 760,5 ms** ;
- `native+vector` total : **14 534,5 ms** ;
- ratio Vector/native total : **0,98469** (~1,5 % plus rapide) ;
- `native-only` graphe p95 : **3 027,92 ms** ;
- `native+vector` graphe p95 : **3 049,13 ms** ;
- ratio Vector/native graphe p95 : **1,00700** (~0,7 % plus lent).

Le Vector API n'apporte donc pas de bénéfice robuste et univoque sur le workload NEXUS qualifié. Un gain global faible coexiste avec une légère régression du p95 graphe ; cela ne justifie pas d'imposer un module incubateur à toutes les surfaces runtime.

## Décision d'adoption

### Retenu

`--enable-native-access=ALL-UNNAMED` est versionné dans les surfaces NEXUS supportées :

- Maven/Surefire via `argLine` à expansion tardive afin de préserver JaCoCo ;
- launcher CLI Linux et Windows ;
- JVM enfant MCP qualifiée avec l'agent JaCoCo ;
- commandes MCP générées pour les assistants ;
- image Docker, y compris les commandes `docker exec ... java` qui contournent l'entrypoint ;
- runtime Windows self-contained : `jpackage`, MCP, assistant et REST.

Cette option correspond au classpath non modulaire actuel et supprime le warning native-access qualifié sans élargir la surface au-delà de `ALL-UNNAMED`.

### Non retenu

`--add-modules=jdk.incubator.vector` **n'est pas activé par défaut**. Le warning Lucene associé reste un diagnostic d'optimisation advisory, pas une erreur de correction ou de sécurité.

Une future adoption Vector devra être réévaluée si :

- le workload évolue vers une charge vectorielle significativement plus importante ;
- Lucene/JDK fournit une API non incubateur ou un bénéfice plus net ;
- une nouvelle qualification same-runner montre un gain stable sans régression des chemins critiques.

## Gate de clôture #152

L'adoption native-access n'est considérée terminée qu'après qualification exact-head des surfaces suivantes :

- reactor Maven/Surefire + JaCoCo ;
- CLI shaded JAR ;
- MCP STDIO et JVM enfant ;
- REST/Quarkus ;
- Docker distribution ;
- Windows self-contained installer.

Les workflows NEXUS CI, Docker Distribution et Windows Installer constituent les preuves de clôture.
