# Tasks

## 1. Règles JaCoCo par classe

- [x] 1.1 `AUD-TST-03` — Relever sur `./mvnw clean install` du HEAD la couverture de lignes et de branches de `JdtStdioSession`, `MinosPayloadReader`, `SemanticSearchConfiguration` (`core/target/site/jacoco/jacoco.csv`) et `NexusRestTransportPolicy` (`adapters/rest-quarkus/target/site/jacoco/jacoco.csv`), puis ajouter une règle `<element>CLASS</element>` par classe (lignes et branches, au plus 3 points sous la mesure, arrondi au centième inférieur) dans `jacoco-check-core` (`core/pom.xml:72-168`) et `jacoco-check-rest` (`adapters/rest-quarkus/pom.xml:100-155`) ; couvre `coverage-gates › Les classes de bornes ont un plancher de couverture par classe` (scénario « Mesure courante ») ; vérifier : `./mvnw clean install` vert, « All coverage checks have been met » pour les deux exécutions
- [x] 1.2 `AUD-TST-03` — Après `sprint-12-durcir-les-gates-ajoutes` › 1.2, étendre `scripts/release/coverage_floors.py` pour échouer sur une règle `CLASS` ou `PACKAGE` dont l'inclusion n'a aucune ligne dans le `jacoco.csv` du module, avec une fixture négative (inclusion `com.nexus.api.NexusRestTransportPolicyTypo`) ; couvre le scénario « Règle mal nommée » ; vérifier : `python3 scripts/release/coverage_floors.py` vert sur le dépôt et rouge sur la fixture
- [x] 1.3 `AUD-TST-03` — Prouver une fois que la règle mord : dans une copie de travail jetable, désactiver un test qui couvre des branches de `NexusRestTransportPolicy` sans faire passer `com.nexus.api` sous son plancher de paquet, puis restaurer ; couvre le scénario « Régression d'une classe de bornes » ; vérifier : `./mvnw -B -ntp -pl adapters/rest-quarkus -am verify` échoue en nommant `NexusRestTransportPolicy`, puis `git status --short` vide après restauration

## 2. Documentation durable

- [x] 2.1 `AUD-TST-03` — Compléter `docs/developer/adapter-coverage-gates.md` (table « Seuils bloquants » `:60-71` avec les quatre règles `CLASS`, table de mesure avec leur mesure et son commit) et la section `## OpenSpec coverage-gates` (créée par `sprint-12-durcir-les-gates-ajoutes` › 6.1, ou ici si ce changement passe en premier) ; vérifier : `bash .claude/scripts/contracts.sh` et `python3 scripts/release/coverage_floors.py`
- [x] 2.2 `AUD-TST-03` — Mettre à jour `docs/developer/openspec-traceability.md` : lignes de `coverage-gates › Les classes de bornes ont un plancher de couverture par classe` avec les preuves de 1.1 à 1.3 ou « test non écrit » (page durable et section de la capacité créées si absentes) ; vérifier : `bash scripts/release/test-openspec-doc-contracts.sh` après synchronisation des specs

## Workflow follow-up

- Si la [Question] retient la règle par paquet : abandonner ce changement, rédiger l'ADR qui réécrit le critère de sortie du sprint 1 (skill `nexus-adr`) et clore `AUD-TST-03` sur cette base.
- Une PR vers `develop` (skill `nexus-pr-workflow`), merge exact-head, après celle de `sprint-12-durcir-les-gates-ajoutes` pour la tâche 1.2.
- Mettre à jour le statut de `AUD-TST-03` dans `docs/project/audits/2026-10-17/findings.json` avec la PR.
- Synchroniser les specs et archiver le changement une fois les tâches vérifiées.
