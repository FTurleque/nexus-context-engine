# Qualification des flags JVM runtime

Ce document décrit la qualification NXA7-P3 suivie par #152. Les warnings Java observés sont advisory : aucun flag global n'est adopté avant comparaison reproductible des runtimes supportés.

## Warnings de départ

Sur Java 21, les chemins SQLite/Lucene qualifiés par NEXUS peuvent émettre :

- `Use --enable-native-access=ALL-UNNAMED` lors d'un appel FFM/JNI depuis le classpath non modulaire ;
- `Java vector incubator module is not readable` avec la recommandation Lucene `--add-modules jdk.incubator.vector`.

Le scope `ALL-UNNAMED` est évalué parce que les distributions NEXUS actuelles sont des classpath/uber-JARs non modulaires. Il ne doit pas être élargi au-delà de ce que le runtime exige.

## Méthode

`.github/workflows/runtime-flags-qualification.yml` compare deux profils :

- `baseline` : aucun flag additionnel ;
- `tuned` : `--enable-native-access=ALL-UNNAMED` et, uniquement si le runtime expose le module, `--add-modules=jdk.incubator.vector`.

La qualification couvre :

1. Java 21 / Linux : tests d'intégration SQLite/Lucene avec vérification explicite de la présence des warnings en baseline et de leur absence en tuned ;
2. Java 24 / Windows : même contrat, avec détection préalable de la disponibilité de `jdk.incubator.vector` ;
3. Java 21 / Linux : le workload hermétique `ScaleRegressionBenchmarkTest`, `GraphScaleRegressionBenchmarkTest`, `FederatedContextBudgetScaleBenchmarkTest` et `NativeContextDiscoveryBudgetBenchmarkTest` est exécuté quatre fois sur **le même runner** dans l'ordre ABBA : `baseline-a`, `tuned-a`, `tuned-b`, `baseline-b` ;
4. les médianes baseline/tuned et leurs ratios `totalDurationMs` / `graph.p95Ms` sont enregistrés dans `target/runtime-flags/summary.json` ; tous les logs et JSON sont conservés 90 jours.

Le protocole ABBA évite de prendre une différence entre machines GitHub comme un effet JVM et réduit le biais d'échauffement en entourant les deux mesures tuned par les deux mesures baseline.

Cette première PR ne modifie aucun launcher utilisateur. Elle produit l'évidence nécessaire avant décision d'adoption.

## Baseline avant qualification

Le run CI du 2 septembre 2026 sur le correctif #155 a mesuré, sans flags additionnels :

- profil `ci` ;
- SQLite max : 100 000 symboles ;
- portfolio max : 25 projets ;
- récupération sémantique : 5 000 documents ;
- graphe : 100 000 symboles / 100 000 relations ;
- p95 graphe : environ 2 893 ms sur ce runner ;
- durée du rapport scale : environ 14 902 ms ;
- découverte native : 1 000 skills / 2 001 entrées, environ 189 ms.

Ces valeurs servent de point de comparaison, pas de promesse de performance absolue entre runners GitHub différents.

## Qualification fonctionnelle initiale des flags

Le premier run qualifié sur le HEAD `7db489d...` a confirmé que :

- Java 21 / Linux expose `jdk.incubator.vector` et le profil tuned supprime les deux warnings ciblés ;
- Java 24 / Windows (Temurin 24.0.2) expose également `jdk.incubator.vector` et le profil tuned supprime les deux warnings ciblés ;
- les tests SQLite/Lucene restent verts avec et sans flags.

La première tentative de comparaison performance exécutait baseline et tuned sur deux runners distincts. Elle a produit environ 14 914 ms / 3 188 ms p95 graphe en baseline contre 16 728 ms / 3 639 ms en tuned. **Ces nombres ne sont pas utilisés pour la décision d'adoption**, car ils mélangent l'effet des flags avec la variabilité de machines différentes. Le protocole ABBA same-runner remplace cette comparaison.

## Bootstrap Windows découvert pendant la qualification

Le premier run Windows Java 24 a également révélé que le Windows PowerShell du runner pouvait ne pas fournir `Get-FileHash`. `mvnw.cmd` calcule désormais le SHA-512 Maven via `System.Security.Cryptography.SHA512` / `ComputeHash`, puis le compare à l'ancre versionnée dans `config/tool-integrity.properties` **avant** extraction. Le fallback HTTP `curl.exe` puis PowerShell reste inchangé.

## Décision d'adoption

Une seconde modification peut versionner les flags dans les launchers uniquement si :

- le profil tuned est supporté sur les runtimes qualifiés ;
- les warnings ciblés disparaissent sans masquer d'autre diagnostic ;
- la comparaison ABBA same-runner ne montre pas de régression significative ;
- JaCoCo et le JVM enfant MCP restent fonctionnels ;
- CLI, MCP, REST, Docker et Windows self-contained passent ensuite leurs smokes exact-head.

Si `jdk.incubator.vector` n'existe pas sur un runtime supporté, le flag vector ne doit pas être imposé aveuglément à cette surface : le launcher devra soit détecter la capacité, soit rester sans ce flag sur ce runtime.
