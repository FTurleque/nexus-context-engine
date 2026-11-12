# Proposition

## Why

Le dépôt n'appelle Maven que par le wrapper vérifié (`./mvnw`, `.\mvnw.cmd`), qui contrôle l'archive Maven contre l'ancre SHA-512 versionnée ; les hooks Claude Code refusent `mvn` hors wrapper (`.claude/hooks/guard-command.ps1:49-51`). `AUD-QUA-03` a fermé cette classe pour `AGENTS.md` seul. La vérification du 12/11 trouve encore des commandes `mvn` directes dans le guide de reproduction (corrigé au sprint 14 pour `AUD-QUA-27`) et dans le pipeline d'indexation, et une commande `exec:java` lancée à la racine d'un POM parent, qui ne vise pas le module `core`. Aucun contrat ne les voit.

Source : `docs/project/audits/2026-10-17/etat-2026-11-12.md` (section `AUD-QUA-30`), `docs/project/audits/2026-10-17/findings.json` (`AUD-QUA-30`, sprint 15, commit `244ab1d3`), `docs/project/audits/2026-10-17/sprints.md` (S15, critère « guides sous contrat du wrapper »).

## What Changes

- `AUD-QUA-30`
  - [Existant vérifié] Commandes Maven directes dans un bloc de code : `docs/developer/reproduce-and-debug.md:18` (`mvn -version`), `:31` (`mvn clean install`), `:180` (`mvn -q exec:java "-Dexec.args=--help"`) ; `docs/developer/indexing-pipeline.md:71` (`mvn -q exec:java "-Dexec.args=project add …"`).
  - [Existant vérifié] Commandes Maven directes en code en ligne, absentes de la preuve de l'audit : `docs/developer/reproduce-and-debug.md:163` et `:518` (`mvn clean install`), `docs/developer/agent-skills.md:5`, `docs/architecture/arc42/02-contraintes.md:24` (contrainte O3, qui cite `AGENTS.md` comme preuve alors que `AGENTS.md:11` prescrit `./mvnw clean install`).
  - [Existant vérifié] Le seul contrôle est un `grep` sur `AGENTS.md` (`scripts/release/test-operational-doc-contracts.sh:977-984`, motif `(^|[^A-Za-z0-9_./\-])mvn[[:space:]]` à `:978`). La liste `current_docs` (`:377-405`) ne porte que des chaînes interdites et ne contient ni `docs/developer/indexing-pipeline.md`, ni `docs/developer/agent-skills.md`, ni `docs/architecture/arc42/02-contraintes.md`.
  - [Existant vérifié] `pom.xml:10` déclare `<packaging>pom</packaging>` ; la `mainClass` `com.nexus.cli.NexusCli` n'est configurée que dans `core/pom.xml:232-235`. `exec:java` lancé à la racine ne vise donc pas `core` (échec probable, non rejoué par l'audit).
  - [Exigence] Les guides courants n'écrivent aucune commande Maven directe (`mvn`, `mvn.cmd`) en position de commande dans un bloc de code ou un code en ligne ; le contrat le vérifie et rejette une fixture de contournement.
  - [Exigence] Toute commande `exec:java` des guides courants vise le module `core` par `-pl core`.
  - [Décision] Décision du mainteneur du 2026-11-12 : le contrôle couvre toute la documentation courante (`docs/**/*.md` hors ADR, audits, relevés datés et rapports du superviseur, plus `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `SECURITY.md`), et non la seule liste `current_docs` : la liste ne contient pas trois des pages fautives.
  - [Écart] L'action de l'audit demande d'« étendre le motif d'AGENTS.md (:977) à current_docs » (`docs/project/audits/2026-10-17/etat-2026-11-12.md:102`, `docs/project/audits/2026-10-17/findings.json:6013`) ; le changement couvre un périmètre plus large que cette liste.
  - [Décision] Les exemples de fixtures de `DIRECT_MAVEN_CALL` cités par `docs/developer/ci-and-supply-chain.md:500-502` restent tels quels, en exceptions listées du contrat (document et texte exact de ligne) ; une exception obsolète fait échouer le contrat.
  - [Décision] La contrainte O3 (`docs/architecture/arc42/02-contraintes.md:24`) cite les deux formes du wrapper, `./mvnw clean install` et `.\mvnw.cmd clean install`.

## Type de changement

documentaire et outillage (contrat documentaire) : profil court.

## Périmètre et exclusions

**Dans le périmètre :**
- Les quatre pages courantes fautives (huit occurrences) : `docs/developer/reproduce-and-debug.md`, `docs/developer/indexing-pipeline.md`, `docs/developer/agent-skills.md`, `docs/architecture/arc42/02-contraintes.md`.
- Un nouveau bloc de contrat en fin de `scripts/release/test-operational-doc-contracts.sh`, avec son auto-test.
- La page durable `docs/developer/ci-and-supply-chain.md#openspec-operational-documentation-contracts` et `docs/developer/openspec-traceability.md`.

**Hors périmètre :**
- Les ADR, append-only, qui citent `mvn clean install` ou `mvn exec:java` à leur date (`docs/architecture/adr/GEN-0002-compiler-le-coeur-en-java-21.md:79`, `GEN-0031-packager-la-cli-dans-un-jar-autonome.md:16`, `:30`, `:103`, et les ADR de `docs/architecture/adr/archive/`).
- Les audits (`docs/project/audits/`), relevés datés (`docs/project/history/`) et rapports du superviseur (`docs/project/rapport-supervisor/`).
- Les mentions qui désignent Maven sans le prescrire : `docs/developer/claude-code.md:38` (« `mvn` hors wrapper »), `docs/developer/large-scale-baseline-runbook.md:49` (`mvn.cmd`), `docs/developer/ci-and-supply-chain.md:515`, `:538` (diagramme Mermaid), `:548`.
- Le contrat Maven des scripts PowerShell (`DIRECT_MAVEN_CALL`, capacité `powershell-script-tooling`) : inchangé.
- Les autres constats du sprint 15 (`AUD-SEC-26`, `AUD-DEP-38`, `AUD-QUA-29`).

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities
- `operational-documentation-contracts`: ajout de deux exigences : les guides courants n'appellent Maven que par le wrapper ; les commandes `exec:java` visent le module `core`. L'exigence existante sur `AGENTS.md` reste inchangée.

## Impact

- Documentation : `docs/developer/reproduce-and-debug.md`, `docs/developer/indexing-pipeline.md`, `docs/developer/agent-skills.md`, `docs/architecture/arc42/02-contraintes.md`, `docs/developer/ci-and-supply-chain.md` (section `#openspec-operational-documentation-contracts`), `docs/developer/openspec-traceability.md`.
- Script : `scripts/release/test-operational-doc-contracts.sh` (nouveau bloc en fin de script, après `:1133`).
- Aucun code Java, aucun workflow, aucune ancre d'intégrité, aucun ADR.
