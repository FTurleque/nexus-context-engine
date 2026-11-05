# Correction des régressions et du protocole scale — 12 septembre 2026

## Preuves examinées

L'analyse repart du code et des artefacts GitHub Actions du run
[34701726463](https://github.com/FTurleque/nexus-context-engine/actions/runs/34701726463),
sur `a3041b8a`, sans prendre un ancien rapport comme preuve de validation.

Le même candidat mesure le peuplement 10k à **244 ms** dans le rapport général
`ci`, puis **990 ms** dans le rapport dédié `full`. La base mesure **289 ms**,
pour un plafond relatif de **489 ms**. Les autres paliers candidats mesurent
2 323 / 14 892 / 29 071 ms. La mesure unique du premier palier ne permet pas
de distinguer une variation à froid d'une régression durable.

Le code de recherche fédérée ouvre aussi une connexion SQLite par projet et par
contrôle de disponibilité, soit 75 ouvertures pour 25 projets. La tentative
d'acquisition parallèle des verrous a bloqué un essai Windows précédent.

## Corrections

- Chargement ciblé des descripteurs et métadonnées via `findByIds`, sur une
  connexion et un snapshot SQLite ; les trois contrôles restent actifs.
- Acquisition et libération des verrous sur le même thread, avec restitution
  de toute acquisition partielle en cas d'échec. Voir l'ADR-0054.
- Protocole 5 : échauffement explicite, médiane de trois peuplements à 10k,
  vingt échantillons pour les lectures et publication des valeurs brutes.
- Même harness exécuté sur les codes de production base/candidat, y compris
  lors de la transition 4 → 5 ; runs dédiés limités à SQLite.
- Gate Python testable, preuve de stockage obligatoire en PR, lancement manuel
  `full` possible sans baseline de PR. Les plafonds restent inchangés.

## Validation du code corrigé

- Java 21.0.10, Windows : `mvnw.cmd -B clean install` réussi sur les cinq modules.
  557 tests déclarés, aucun échec ni erreur, 24 tests ignorés ; contrôles de
  couverture réussis. Journal : `target/verification-clean-install.log`.
- `scripts/self-smoke.ps1` : `SELF-SMOKE SUCCESS`, sur les JAR reconstruits.
  Journal : `target/verification-self-smoke.log`.
- Dix tests Python du gate réussis, notamment le rejet des deux dépassements
  signalés : population 990/289/489 ms et recherche 25 projets 168,577/160 ms.
- Syntaxes YAML et Bash du workflow vérifiées.

Les anciens résultats Linux décrivent les commits en échec, pas les corrections
locales. Un nouveau run Linux sur le commit portant ces corrections reste la
preuve applicable aux plafonds du runner GitHub.
