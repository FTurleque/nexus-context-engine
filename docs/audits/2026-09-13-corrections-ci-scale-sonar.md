# Corrections CI scale et Sonar — 13 septembre 2026

## Diagnostic reproduit

Les artefacts du run [34718730833](https://github.com/FTurleque/nexus-context-engine/actions/runs/34718730833),
sur `377d680d`, contiennent un échauffement `[1547, 259]` ms. Le gate appliquait
à cette phase préparatoire le plafond de 1,4 s du peuplement mesuré à 10k.

Le rejeu local a révélé un second dépassement, masqué par cette première
assertion : le seul peuplement candidat à 1M mesure 45 880 ms, contre un plafond
absolu de 40 000 ms et une base à 38 789 ms. Le palier 500k montre aussi une
forte variation entre candidat (14 211 ms) et base (21 622 ms).

## Corrections

- Les échauffements restent publiés et contrôlés pour leur présence et leur
  validité numérique. Les plafonds de population portent sur les mesures qui
  suivent l'échauffement.
- Le protocole 6 utilise trois peuplements de bases neuves par palier dans les
  courbes SQLite dédiées. Leur médiane reste soumise aux plafonds absolus et
  relatifs existants, dont 40 s à 1M. Les valeurs brutes restent disponibles.
- Chaque PR, y compris avec un profil général `full`, exécute la courbe dédiée.
  La base utilise le même harness ; les transitions 4/5 → 6 sont comparables.
- Les trois requêtes de `SqliteProjectRepository.findByIds` sont statiques.
  Les UUID passent par un paramètre JSON lié et `json_each`, déjà utilisé dans
  l'adaptateur SQLite. Le snapshot et les lots bornés à 500 restent conservés.
- `ReadLockAcquisition` utilise `try-with-resources` pour libérer une acquisition
  partielle et conserver l'échec initial, sans intercepter explicitement `Error`.
- La boucle de `SymbolSearchStrategy` contient un seul `continue` ; les filtres
  de catégorie et les critères de score restent applicables.

## Validation

Les 15 tests Python couvrent notamment `[1547, 259]`, le dépassement isolé à 1M,
le rejet d'un ralentissement répété, les plafonds absolus et relatifs, les
échantillons invalides et une baseline incomplète. Les syntaxes YAML et Bash du
workflow sont valides. Une comparaison des constantes confirme que les plafonds
SQLite, population, jitter et portfolio sont inchangés.

Les résultats de build, self-smoke et benchmark de cette correction sont
conservés sous `target/verification-ci-fixes-*` et `target/ci-linux-final/`.
La campagne Linux reproduit le profil PR `ci` et les deux courbes SQLite dédiées,
avec Java 21, quatre processeurs visibles et 4 Go de heap. La base de la PR est
`b3738a988fed6f58fddd194058d8a0eaa1cef6c2`.

`mvnw.cmd -B clean install` et `scripts/self-smoke.ps1` ont réussi sur les
corrections. Le validateur appliqué aux nouveaux rapports Linux retourne
`all-regression-budgets=PASS`, comparaison relative avec la base comprise.

| Symboles | Médiane candidat | Médiane base | Plafond relatif |
|---:|---:|---:|---:|
| 10 000 | 199 ms | 219 ms | 419 ms |
| 100 000 | 1 858 ms | 1 805 ms | 2 305 ms |
| 500 000 | 10 972 ms | 10 932 ms | 13 118 ms |
| 1 000 000 | 25 122 ms | 24 293 ms | 29 151 ms |

À 1M, les trois mesures candidat sont 24 107 / 25 751 / 25 122 ms ; le stockage
est identique à la base, à 938 147 840 octets. La recherche sur 25 projets mesure
112,921 ms au p95 et le contexte 208,53 ms, sous leurs plafonds respectifs de
160 et 260 ms. Le graphe 100k mesure 2 792,511 ms au p95 et la découverte des
1 000 skills 240 ms. Les journaux et échantillons bruts sont conservés dans
`target/ci-linux-final/` ; il s'agit d'une reproduction locale, pas d'un nouveau
run GitHub Actions.

Cette campagne ne qualifie pas le scénario graphe 1M du profil global `full` :
un essai Linux du 12 septembre a mesuré 27,87 s au p95 pour un plafond de 15 s.
Le profil PR utilise le scénario graphe 100k. L'analyse SonarCloud distante reste
à confirmer sur le commit portant les corrections ; les cinq motifs signalés
ont été supprimés du code concerné.
