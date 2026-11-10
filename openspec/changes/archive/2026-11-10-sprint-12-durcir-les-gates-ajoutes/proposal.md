# Proposition

## Why

Les sprints précédents ont ajouté des gates (planchers JaCoCo par paquet, contrat « Maven par `Invoke-Maven` », contrôle d'hygiène des workflows, épinglage des actions par SHA, règles Archgate des ADR). La revérification du 09/11 montre que chacun se contourne ou ne s'exécute pas : planchers déjà 4 à 12 points sous la mesure, contrat Maven trompé par une indirection, formes YAML que `workflow_hygiene.py` ne lit pas, commentaires d'épinglage jamais comparés au tag amont, règles Archgate qui ne tournent dans aucun workflow. Un gate qui laisse passer la régression qu'il vise donne une fausse assurance.

Source : `docs/project/audits/2026-10-17/findings.json` (sprint 12), `etat-2026-11-09.md`.

## What Changes

- `AUD-TST-15`
  - [Existant vérifié] Planchers : `com.nexus.security` 0.85/0.77 (`core/pom.xml:98-111`), `com.nexus.api` 0.78/0.67 et `NexusApiApplicationService` 0.60/0.38 (`adapters/rest-quarkus/pom.xml:121-154`), core `BUNDLE` 0.70/0.55 (`core/pom.xml:80-90`), REST `BUNDLE` 0.65/0.60 (`adapters/rest-quarkus/pom.xml:106-116`).
  - [Existant vérifié] Mesure du 09/11 (commit `7bbfe675`, `findings.json`) : `com.nexus.api` 89,7/77,5 ; `com.nexus.security` 89,0/81,2 ; `NexusApiApplicationService` 84,1/60 ; core `BUNDLE` 86,9/71,6.
  - [Écart] `docs/developer/adapter-coverage-gates.md:73` affirme « deux à trois points sous la mesure » avec une table de mesure périmée (`:75-85`, core 83,81 %).
  - [Exigence] Chaque plancher est au plus 3 points sous la mesure de référence, et un contrat le vérifie.
- `AUD-TST-17`
  - [Existant vérifié] Aucun workflow ne cite `archgate` (`grep -rn archgate .github` vide) ; aucun `package.json` ni lockfile ; 25 fichiers `docs/architecture/adr/*.rules.ts` ; exécution seulement locale (`.claude/rules/documentation.md:81`). Le poste de développement a Archgate 0.61.0.
  - [Écart] L'audit cite 40 règles (relevé du 07/11) ; il en reste 25 après la consolidation des ADR.
  - [Exigence] Les règles Archgate s'exécutent en CI avec une version épinglée et vérifiée — ou, selon la [Question], les ADR cessent de présenter ces règles comme un contrôle.
- `AUD-QUA-21`
  - [Existant vérifié] `DIRECT_MAVEN_CALL` ne reconnaît que des appels littéraux (`scripts/release/test-operational-doc-contracts.sh:579-586`) ; `$m = "mvn"; & $m …`, `cmd /c mvn …` et `Invoke-Expression "mvn …"` passent (mutations du 09/11).
  - [Exigence] Le contrat refuse les appels indirects de Maven, prouvé par des mutations négatives.
- `AUD-DEP-32`
  - [Existant vérifié] `workflow_hygiene.py` ne lit que `run:` en ligne ou en bloc `|`/`>` (`:28`, `:114-128`), des clés non citées, des jobs en block mapping (`:147-173`), et seulement `.github/workflows` (`:46-54`) ; `.github/actions/ollama-embedding-runtime/action.yml` n'est pas contrôlé (`test-operational-doc-contracts.sh:663`, `test-tool-integrity-anchors.sh:116`).
  - [Exigence] Le contrôle refuse les formes qu'il ne sait pas lire, contrôle `with: script:` et les actions composites, avec une fixture par forme.
- `AUD-DEP-33`
  - [Existant vérifié] `check_pins` compare les commentaires entre eux (`workflow_hygiene.py:88-111`) ; « Upstream tags are checked by hand on a bump » (`scripts/release/test-tool-integrity-anchors.sh:111-113`) ; aucun `ls-remote` dans `.github/`.
  - [Exigence] Un job avec réseau compare chaque SHA épinglé au commit du tag amont annoncé par son commentaire.

## Type de changement

outillage (gates CI et contrats) : profil court, complété par les vues utiles aux nouveaux contrôles.

## Périmètre et exclusions

**Dans le périmètre :**
- Planchers JaCoCo existants, table de mesure de `docs/developer/adapter-coverage-gates.md`, contrat d'écart plancher/mesure.
- Exécution des règles Archgate en CI (ou retrait des mentions, selon la [Question]).
- Contrat Maven de `test-operational-doc-contracts.sh`, `workflow_hygiene.py` et ses fixtures, nouveau workflow de vérification des tags amont.

**Hors périmètre :**
- Nouvelles règles JaCoCo `CLASS` pour les classes de bornes (`sprint-01-suite-couverture-par-classe`).
- Factorisation de `gabarit/fonctions-communes.ts` dans les règles Archgate : dépend de la capacité d'import de l'outil, non vérifiée.
- Le contenu des règles Archgate et des ADR (`AUD-PRF-06` et autres).
- Remplacer `workflow_hygiene.py` par un parseur YAML tiers : écarté (dépendance Python supplémentaire à épingler) au profit d'un refus des formes non lues.

## Capabilities

### New Capabilities
- `coverage-gates`: planchers de couverture JaCoCo bloquants, leur écart maximal à la mesure de référence et les règles par paquet ou par classe (capacité partagée avec `sprint-01-suite-couverture-par-classe`).
- `adr-rule-enforcement`: exécution reproductible des règles Archgate des ADR par la CI.

### Modified Capabilities
- `powershell-script-tooling`: ajout d'une exigence sur les appels indirects de Maven.
- `ci-workflow-tooling`: ajout d'exigences sur les formes YAML non lues, les actions composites et la concordance des SHA avec les tags amont.

## Impact

- Build : `core/pom.xml`, `adapters/rest-quarkus/pom.xml` (et `pom.xml` si le plancher par défaut change).
- Scripts : `scripts/release/test-operational-doc-contracts.sh`, `scripts/release/workflow_hygiene.py`, `scripts/release/fixtures/workflow-hygiene/`, `scripts/release/test-tool-integrity-anchors.sh`, nouveau contrôle d'écart de couverture.
- Workflows : `.github/workflows/ci.yml` (contrôle de couverture, job Archgate), nouveau workflow planifié de vérification des tags amont.
- Ancres : `config/tool-integrity.properties` (Archgate).
- Documentation : `docs/developer/adapter-coverage-gates.md`, `docs/developer/ci-and-supply-chain.md`, ADR qui citent Archgate si la [Question] retient le retrait, `docs/developer/openspec-traceability.md`.
- Aucun code Java de production.

## Questions ouvertes

- [Question] `AUD-TST-17` : exécuter les 25 règles Archgate en CI avec une version épinglée (proposé par défaut, tâches 2.x), ou retirer des ADR les mentions « contrôlé par une règle Archgate » et les fichiers `.rules.ts` ? La seconde option remplace les tâches 2.1 à 2.3 par une tâche de retrait.
- [Question] `AUD-TST-15` : un contrat qui échoue quand la mesure dépasse le plancher de plus de 3 points oblige à remonter le plancher dans la PR qui améliore la couverture. Est-ce le comportement voulu, ou le contrat doit-il seulement avertir ?
