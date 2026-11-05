---
paths:
  - "**/src/test/java/**"
---

# Tests

- JUnit 5 ; répertoires temporaires via `@TempDir` ; un `NexusPaths` dédié par test (`new NexusPaths(tempDir.resolve("..."))`).
- Aucune assertion dépendante de l'horloge ou de l'ordonnancement : ne pas comparer des `elapsedMillis`/`Instant` entre deux captures (cause réelle d'une Windows gate instable). Comparer les compteurs exactement et seulement la monotonie des champs temporels.
- Les tests doivent passer sous Windows **et** Linux : séparateurs de chemins, casse, symlinks, fins de ligne. Un test qui crée un lien symbolique passe par `com.nexus.testsupport.SymbolicLinks` (`createOrSkip`, `requireSupport`) : skip en local si le filesystem refuse le lien, échec quand `CI` est défini ou avec `-Dnexus.test.symlinks.required=true`. Jamais `assumeTrue` sur la création d'un symlink.
- Tester la borne elle-même (seuil exact, seuil + 1) et le diagnostic produit, pas seulement le cas nominal.
- Les benchmarks de scale (`com.nexus.benchmark.*`) sont désactivés par défaut et lancés par leurs workflows ; ne pas abaisser un budget ou un seuil pour masquer une régression.
- Préférer des fixtures construites dans le test plutôt que dépendre du repository NEXUS lui-même.
- Lancer un test ciblé : `./mvnw -B -ntp -pl core test -Dtest=NomDuTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`.
