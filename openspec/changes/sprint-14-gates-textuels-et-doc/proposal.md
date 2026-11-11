# Proposition

## Why

Le sprint 12 a durci deux gates textuels (contrat « Maven par `Invoke-Maven` », contrôle `forms` de `workflow_hygiene.py`) et les a testés par mutation. La vérification du 11/11 montre que des formes courantes les contournent encore : un appel `mvn` hors début de ligne, une clé YAML écrite `run :` ou un corps de job indenté de 4. Ces gates se disent fail-closed ; une forme valide qui passe sans alerte rouvre `AUD-QUA-05`, `AUD-SEC-07` ou `AUD-DEP-15`. Le même relevé trouve une commande de vérification fausse dans le guide de reproduction, qu'aucun contrat documentaire ne voit.

Source : `docs/project/audits/2026-10-17/etat-2026-11-11.md` (sections `AUD-QUA-26`, `AUD-DEP-36`, `AUD-QUA-27`), `docs/project/audits/2026-10-17/findings.json` (sprint 14, commit `510ff8ca`).

## What Changes

- `AUD-QUA-26`
  - [Existant vérifié] `DIRECT_MAVEN_CALL` ne voit un jeton Maven nu qu'en début de ligne (`scripts/release/test-operational-doc-contracts.sh:588`) ou après `&` (`:587`) ; le passage par `cmd` n'accepte que `/c` (`:598-600`).
  - [Existant vérifié] Sur une copie de `scripts/compare-jdt.ps1`, `$output = mvn clean install 2>&1`, `Push-Location $root; mvn clean install`, `if (…) { mvn clean install }`, `. mvn clean install`, `cmd /k mvn …` et `pwsh -Command "mvn …"` laissent le contrat à rc=0 (`findings.json`, `AUD-QUA-26`).
  - [Écart] La [Limite] de `docs/developer/ci-and-supply-chain.md:485` ne cite pas ces formes, et nomme `Start-Job { mvn … }` comme non détecté.
  - [Exigence] Le contrat refuse un jeton Maven en position de commande après `=`, `;`, `{`, `(`, `|`, `.` ou `&`, ainsi que `cmd /k` et `pwsh`/`powershell -c`/`-Command`, avec une fixture négative par forme.
- `AUD-DEP-36`
  - [Existant vérifié] `RUN` exige `run:` collé (`scripts/release/workflow_hygiene.py:44`), `KEY` aussi (`:58`), et `jobs_of` ne lit les clés de job qu'à `job_indent + 2` (`:294`).
  - [Existant vérifié] `- run : echo ${{ inputs.profile }}` passe `forms` et `inputs` ; `runs-on : ubuntu-latest` sans `timeout-minutes` et un job dont les clés sont indentées de 4 sans `timeout-minutes` passent `timeouts` (`findings.json`, `AUD-DEP-36`).
  - [Exigence] `forms` refuse toute clé suivie d'espaces avant « : » hors du texte d'un bloc `run:`/`script:` ; les clés de job se lisent à l'indentation de la première clé du job, et une clé de job à une autre indentation est refusée.
- `AUD-QUA-27`
  - [Existant vérifié] `docs/developer/reproduce-and-debug.md:49-50` et `:56` citent `target/nexus-context-engine-0.1.0-SNAPSHOT.jar`, `-cli.jar` et `java -jar .\target\nexus-context-engine-0.1.0-SNAPSHOT-cli.jar --version` ; `pom.xml:9` déclare `<version>0.2.0</version>`.
  - [Existant vérifié] La liste `current_docs` du contrat (`scripts/release/test-operational-doc-contracts.sh:375-402`) ne contient pas `docs/developer/reproduce-and-debug.md`, et ses motifs interdits (`:403-412`) ne contiennent pas `0.1.0-SNAPSHOT`.
  - [Écart] `docs/developer/ci-and-supply-chain.md:493` et `:530` reconnaissent qu'aucun contrat ne cherche `0.1.0-SNAPSHOT`, ni dans les scripts actifs (scénario « Aucune référence vers un artefact inexistant » de `powershell-script-tooling`, `docs/developer/openspec-traceability.md:279`, « non couvert ») ni dans la documentation courante.
  - [Exigence] Le guide de reproduction nomme les artefacts sans version figée (`nexus-context-engine-<version>`), et le contrat refuse `0.1.0-SNAPSHOT` dans la documentation courante contrôlée.
  - [Proposition] Le même contrôle couvre les scripts actifs de `scripts/` (hors `scripts/archive/`), ce qui donne un contrat au scénario existant, sans changer son exigence.

## Type de changement

outillage (gates CI et contrats documentaires) : profil court.

## Périmètre et exclusions

**Dans le périmètre :**
- `DIRECT_MAVEN_CALL`, ses fixtures d'auto-test et `MAVEN_CALL_EXCEPTIONS` dans `scripts/release/test-operational-doc-contracts.sh`.
- Le contrôle `forms` et `jobs_of` de `scripts/release/workflow_hygiene.py`, leurs fixtures dans `scripts/release/fixtures/workflow-hygiene/` et leurs boucles d'assertion dans `scripts/release/test-operational-doc-contracts.sh` et `scripts/release/test-tool-integrity-anchors.sh`.
- `docs/developer/reproduce-and-debug.md`, la liste `current_docs` et les motifs interdits du contrat documentaire.

**Hors périmètre :**
- Une analyse syntaxique de PowerShell : le contrat reste un filtre de motifs ; les formes de la [Limite] actuelle autres que `Start-Job { mvn … }` (propriété, concaténation, `Set-Alias`, tableau, `[scriptblock]::Create`, `[Diagnostics.Process]::Start`) restent non détectées, et `-EncodedCommand` s'y ajoute.
- Un parseur YAML tiers épinglé pour `workflow_hygiene.py` : écarté au sprint 12 (`openspec/changes/archive/2026-11-10-sprint-12-durcir-les-gates-ajoutes/design.md`, « Décisions ») ; les formes absentes de la liste (`? …`, `!!str`, documents multiples) restent hors contrôle.
- Les documents datés et append-only (`docs/project/history/`, ADR comme `docs/architecture/adr/ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md:134`, audits) qui citent `0.1.0-SNAPSHOT` à juste titre.
- Les autres constats du sprint 14 (`AUD-SEC-24`, `AUD-SEC-25`, `AUD-TST-18`, `AUD-DEP-37`, `AUD-QUA-28`).

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities
- `powershell-script-tooling`: ajout d'une exigence sur l'appel direct de Maven en position de commande et par `cmd /k` ou `pwsh -Command`.
- `ci-workflow-tooling`: ajout de deux exigences : refus d'une clé espacée avant « : », lecture et refus des clés de job selon l'indentation de la première clé du job.
- `operational-documentation-contracts`: ajout d'une exigence interdisant `0.1.0-SNAPSHOT` dans la documentation courante contrôlée.

## Impact

- Scripts : `scripts/release/test-operational-doc-contracts.sh` (`DIRECT_MAVEN_CALL`, auto-test, `current_docs`, motifs interdits, boucle de fixtures `forms`/`timeouts`), `scripts/release/workflow_hygiene.py` (`check_forms`, `jobs_of`, docstring), `scripts/release/test-tool-integrity-anchors.sh` (boucle de fixtures `forms`).
- Fixtures : nouvelles fixtures sous `scripts/release/fixtures/workflow-hygiene/`.
- Documentation : `docs/developer/reproduce-and-debug.md`, `docs/developer/ci-and-supply-chain.md` (sections « Hygiène des workflows », `#openspec-ci-workflow-tooling`, `#openspec-powershell-script-tooling`, `#openspec-operational-documentation-contracts`), `docs/developer/openspec-traceability.md`.
- Aucun code Java, aucun workflow, aucune ancre d'intégrité.
