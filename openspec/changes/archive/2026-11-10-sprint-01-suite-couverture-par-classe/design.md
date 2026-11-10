# Design

**Profil :** court (outillage)

## Contexte

- [Existant vérifié] Les règles JaCoCo de `core` sont dans l'exécution `jacoco-check-core` (`core/pom.xml:72-168`), celles du REST dans `jacoco-check-rest` (`adapters/rest-quarkus/pom.xml:100-155`) ; la règle `CLASS` existante (`adapters/rest-quarkus/pom.xml:141-154`) sert de modèle.
- [Existant vérifié] Classes visées : `core/src/main/java/com/nexus/index/jdt/JdtStdioSession.java`, `core/src/main/java/com/nexus/index/minos/MinosPayloadReader.java`, `core/src/main/java/com/nexus/search/semantic/SemanticSearchConfiguration.java`, `adapters/rest-quarkus/src/main/java/com/nexus/api/NexusRestTransportPolicy.java`.
- [Existant vérifié] JaCoCo n'échoue pas quand l'`include` d'une règle ne correspond à aucune classe : la règle est simplement vide (constat `AUD-TST-03`, « aucune preuve qu'une include CLASS/PACKAGE mal nommée échouerait »).
- [Existant vérifié] `sprint-12-durcir-les-gates-ajoutes` (tâche 1.2) propose `scripts/release/coverage_floors.py`, qui lit planchers et `jacoco.csv` ; il porte naturellement le contrôle « règle sans classe ».

## Objectifs / Hors objectifs

**Objectifs :** `coverage-gates › Les classes de bornes ont un plancher de couverture par classe`.

**Hors objectifs :** améliorer la couverture de ces classes.

## Composants et responsabilités

- Quatre blocs `<rule><element>CLASS</element>…` : trois dans `jacoco-check-core`, un dans `jacoco-check-rest`.
- [Proposition] `coverage_floors.py` signale toute règle `CLASS` ou `PACKAGE` dont l'inclusion n'a aucune ligne dans le `jacoco.csv` du module.

## Modèle de domaine et invariants

Non applicable : configuration de build.

## Traitements et interactions

Non applicable : exécution JaCoCo standard au `verify` de chaque module.

## États et transitions

Non applicable.

## Contrats d'interface

Non applicable : aucune API.

## Persistance et intégrité

Non applicable.

## Transactions, concurrence et idempotence

Non applicable.

## Sécurité et isolation

Non applicable : les règles protègent des classes de sécurité et de bornes sans changer leur comportement.

## Erreurs et reprise

Une règle non respectée fait échouer le module avec le message JaCoCo qui nomme la classe.

## Observabilité

Non applicable.

## Performance et mesure

- Seuils : mesure du HEAD (`jacoco.csv` après `./mvnw clean install`), au plus 3 points dessous, arrondis au centième inférieur, règle déjà écrite dans `docs/developer/adapter-coverage-gates.md:73`.
- Mesure de référence du 09/11 (à relever de nouveau à l'implémentation) : branches `JdtStdioSession` 18,6 %, `MinosPayloadReader` 38,9 %, `NexusRestTransportPolicy` 51,9 %, `SemanticSearchConfiguration` 70 % ; les pourcentages de lignes ne sont pas publiés par l'audit.

## Compatibilité, migration et retour arrière

Retour arrière : retirer les blocs de règles ; aucun format persisté.

## Décisions

- [Proposition] Règles `CLASS` plutôt que nouvelles règles `PACKAGE` (`com.nexus.search.semantic`) : conforme au critère de sortie écrit ; l'alternative est la [Question] de la proposition.
- [Décision] Le contrôle « règle sans classe » vit dans `coverage_floors.py` ; ce changement dépend donc de `sprint-12-durcir-les-gates-ajoutes` › 1.2.
- Aucun ADR, sauf si la [Question] retient la règle par paquet (ADR qui réécrit le critère).

## Risques / Compromis

- [Risque] Plancher très bas pour `JdtStdioSession` → [Question] de la proposition.
- [Risque] Mesure instable entre Windows et Linux (tests opt-in JDT) → relever la mesure sur le build qui exécute le gate (Linux reactor et Windows gate) et prendre la plus basse.

## Stratégie de vérification

`./mvnw clean install` (« All coverage checks have been met »), `python3 scripts/release/coverage_floors.py`, `bash .claude/scripts/contracts.sh`, `openspec validate sprint-01-suite-couverture-par-classe --strict`.

## Traçabilité

| Exigence (`capacité › titre`) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| `coverage-gates › Les classes de bornes ont un plancher de couverture par classe` | Régression d'une classe de bornes | Composants | 1.1, 1.3 | test non écrit |
| `coverage-gates › Les classes de bornes ont un plancher de couverture par classe` | Règle mal nommée | Composants (`coverage_floors.py`) | 1.2 | test non écrit |
| `coverage-gates › Les classes de bornes ont un plancher de couverture par classe` | Mesure courante | Performance et mesure | 1.1 | test non écrit |
