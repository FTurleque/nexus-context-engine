# Fixtures négatives de `coverage_floors.py`

Chaque dossier est une racine de reactor réduite au module `adapters/rest-quarkus` : un `pom.xml` racine, le `pom.xml` du module et une page `coverage.md` qui publie la table de mesure. Tous lisent le même rapport `reports/adapters/rest-quarkus/jacoco.csv`.

| Dossier | Défaut | Message attendu |
|---|---|---|
| `floor-too-low` | plancher de 78 % pour une mesure de 89,70 % | `points under the reference measure` |
| `floor-without-measure` | règle `CLASS` sans ligne dans la table de mesure | `has no row in the reference measure table` |
| `rule-typo` | règle `CLASS` dont l'inclusion `com.nexus.api.NexusRestTransportPolicyTypo` ne correspond à aucune classe | `matches no class` |

Commande : `python3 scripts/release/coverage_floors.py --root <dossier> --modules adapters/rest-quarkus --doc coverage.md --platform linux --reports-dir scripts/release/fixtures/coverage-floors/reports`.
