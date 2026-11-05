# Proposal

## Why

Le sprint 2 de l'audit du 17/10/2026 (« Outillage des scripts et des workflows ») a pour critère de sortie : un seul module PowerShell appelle Maven, via le wrapper ; aucun input n'est interpolé dans un bloc `run:` ; chaque job a un timeout ; la qualification sémantique réelle termine sur `develop`. Les scripts et les workflows portent toutes les vérifications du dépôt ; tant qu'ils divergent entre eux, une correction d'outillage doit être répétée partout et une vérification peut passer sans mesurer.

Les neuf constats du sprint sont actifs sur `origin/develop` (vérifiés le 2026-10-29, après les PR #265 à #270) :

- `AUD-QUA-04` : `validate-iteration-11` à `14.ps1` ne sont référencés par aucun fichier ; `validate-iteration-12.ps1:10` et `validate-iteration-13.ps1:10-11` visent des JAR `0.1.0-SNAPSHOT` alors que le reactor est en `0.2.0`. `validate-iteration-9` et `10` ne sont cités que par l'historique daté et par `docs/developer/multi-language.md:176`.
- `AUD-QUA-05` : 12 scripts définissent chacun une fonction `Invoke-Maven` ; 15 scripts de `scripts/` appellent l'exécutable Maven installé au lieu du wrapper. `validate-phase-6.ps1` appelle le wrapper et ne retombe sur le Maven installé qu'hors CI, avec un avertissement (comportement fixé par le sprint 1).
- `AUD-SEC-07` : `scale-benchmark.yml:73`, `lucene-lifecycle-benchmark.yml:88` et `scanner-corpus-benchmark.yml:63` interpolent `${{ inputs.profile }}` (input `workflow_call` de type `string`) dans un bloc `run:` avant la validation `ci|full`.
- `AUD-DEP-10` : `osv-scanner.yml:72` annote `# v4.6.2` le SHA `cf430e03…`, qui est celui de `v7.0.2`.
- `AUD-DEP-11` : `actions/checkout` coexiste en `v4.4.0` (11 usages) et `v7.0.1` (8 usages).
- `AUD-DEP-12` (reformulé) : les deux workflows réutilisables OSV sont épinglés sur `9a96bc4…` (montée de #269), commit non tagué annoté `# fail closed on incomplete scans`. Le tag `v2.7.0` est un tag annoté : son objet est `6ad84ce1…`, son commit `b1d267ae…` ; l'épinglage vise le SHA de commit.
- `AUD-DEP-15` (reformulé) : sont sans `timeout-minutes` les jobs `ci.yml` `windows-gate` et `linux-reactor`, `codeql.yml` `analyze`, `release.yml` `validate-release`, et aussi `runtime-flags-qualification.yml` `warning-contract-linux` et `warning-contract-windows`. Les jobs `scan-pr` et `scan-sbom` d'`osv-scanner.yml` appellent un workflow réutilisable : GitHub n'accepte pas `timeout-minutes` sur un tel job ; leur borne relève du workflow appelé.
- `AUD-DEP-22` : 13 `ubuntu-latest`, 4 `windows-latest` et 3 matrices sur `*-latest` ; aucun cache Maven.
- `AUD-DEP-19` : la qualification sémantique réelle ne se déclenche que sur PR filtrée par chemins (`cancel-in-progress` sur PR), sur `workflow_dispatch` et sur un `schedule` mensuel qui s'exécute sur la branche par défaut `main`. Aucun run n'a eu lieu sur `develop` depuis le 17/10 ; un run complet dure 1 h 10 à 2 h 10 ; `release.yml` n'en dépend pas.

## What Changes

- Les scripts d'itération non référencés par la documentation courante sont déplacés dans `scripts/archive/` ; la documentation courante désigne `validate-phase-6.ps1` et `self-smoke.ps1` comme seuls points d'entrée de validation.
- Un module `scripts/lib/NexusScript.psm1` porte `Invoke-Maven` (wrapper uniquement), `Resolve-AbsolutePath` et `Invoke-NexusJson` ; tous les scripts actifs l'importent et n'appellent plus Maven autrement.
- Les inputs de workflow passent par `env:` avant tout bloc `run:`.
- Les épinglages d'actions sont alignés et leur commentaire de version est vérifié par un contrat local.
- Chaque job non réutilisable a un `timeout-minutes`.
- Les benchmarks tournent sur des images de runner épinglées ; le cache Maven fait l'objet d'une décision mesurée.
- La qualification sémantique réelle a un corpus PR réduit, un corpus complet qui s'exécute sur `develop`, et une règle écrite sur son rôle dans la release.

## Capabilities

### New Capabilities
- `powershell-script-tooling` : scripts PowerShell du dépôt, module partagé, appel de Maven par le wrapper, points d'entrée de validation.
- `ci-workflow-tooling` : hygiène des workflows GitHub Actions (épinglage, inputs, timeouts, runners).
- `semantic-search-qualification` : qualification sémantique réelle sur PR, sur `develop` et en release.

### Modified Capabilities

(aucune : `openspec/specs/` est vide)

## Impact

- Scripts : `scripts/*.ps1`, nouveau `scripts/lib/NexusScript.psm1`, nouveau `scripts/archive/`.
- Workflows : `ci.yml`, `codeql.yml`, `release.yml`, `osv-scanner.yml`, `runtime-flags-qualification.yml`, `scale-benchmark.yml`, `lucene-lifecycle-benchmark.yml`, `scanner-corpus-benchmark.yml`, `semantic-search-qualification.yml`, et les autres workflows qui utilisent `actions/checkout` ou un runner `-latest`.
- Contrats : `scripts/release/test-operational-doc-contracts.sh` ou `test-final-audit-contracts.sh` (ce dernier vérifie `timeout-minutes: 180` sur la qualification sémantique).
- Documentation : `docs/developer/ci-and-supply-chain.md`, `docs/developer/semantic-search.md`, `docs/developer/multi-language.md`, `.claude/rules/ci-and-scripts.md` si une règle change.
- Hors périmètre : `AUD-PRF-02` (sprint 6), que `AUD-DEP-19` débloque ; les constats de release du sprint 9.
