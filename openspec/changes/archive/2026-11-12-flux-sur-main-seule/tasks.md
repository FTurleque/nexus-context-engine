# Tasks

## 1. CI, Dependabot et Archgate

- [x] 1.1 Passer sur `main` les déclencheurs des workflows de `.github/workflows/`, renommer les enveloppes `develop-push-*` en `main-push-*` et supprimer `develop-push-docker-distribution.yml` ; couvre `scale-benchmark-gate › Le check est produit sur toute PR`, `scale-benchmark-gate › Une seule liste de chemins surveillés`, `adr-rule-enforcement › Les règles des ADR s'exécutent en CI`, `semantic-search-qualification › Une PR exécute un corpus réduit borné`, `semantic-search-qualification › Le corpus complet s'exécute sur main` ; vérifier : `git grep -w develop -- .github` vide, `bash .claude/scripts/contracts.sh`
- [x] 1.2 Faire cibler `main` par `.github/dependabot.yml` ; vérifier : `bash .claude/scripts/contracts.sh`
- [x] 1.3 Passer `.archgate/config.json` à `baseBranch: origin/main` ; vérifier : `archgate check`
- [x] 1.4 Ajouter aux contrats le refus de toute mention de `develop` dans `.github/` et aligner les contrats existants (Dependabot, chemins surveillés, workflows Docker) ; couvre `scale-benchmark-gate › Une seule liste de chemins surveillés` ; vérifier : `bash .claude/scripts/contracts.sh` vert, puis en échec après ajout local de `develop` à un déclencheur

## 2. Outillage Claude Code

- [x] 2.1 Hooks : `guard-command.ps1` refuse le push direct vers `main`, `session-context.ps1` compare la branche à `origin/main` ; tests dans `Test-Hooks.ps1` ; vérifier : `pwsh -NoProfile -File .claude/hooks/tests/Test-Hooks.ps1`
- [x] 2.2 Skills, agents, commandes, règles, `CLAUDE.md` et mémoire d'agent : PR vers `main`, diff contre `origin/main`, `/nx-release` sans promotion ; vérifier : `git grep -w develop -- .claude` ne cite plus de flux par `develop`

## 3. Documentation durable

- [x] 3.1 `docs/developer/branch-governance.md` (section `OpenSpec repository-governance`), `ci-and-supply-chain.md`, `release-and-recovery.md`, `scale-regression-benchmark.md`, `semantic-search.md` et les autres pages courantes, `README.md`, `SECURITY.md`, arc42, `overview.md`, `ROADMAP.md` ; couvre `repository-governance › Seul le merge commit est accepté` (scénario « Documentation alignée ») ; vérifier : `bash .claude/scripts/contracts.sh`
- [x] 3.2 ADR GEN-0089 et BE-0090 (remplace BE-0084, archivé), lien « Complété par » et écart constaté dans GEN-0057, index et `09-decisions.md` (skill `nexus-adr`) ; vérifier : `archgate check`, `archgate adr list`
- [x] 3.3 Synchroniser les quatre specs de référence et `docs/developer/openspec-traceability.md` (exigences et scénarios renommés) ; vérifier : `bash scripts/release/test-openspec-doc-contracts.sh` après synchronisation

## 4. Après le merge (mainteneur)

- [x] 4.1 Supprimer `develop` sur GitHub (`git push origin --delete develop`) et en local (`git branch -D develop`) ; vérifier : `git ls-remote --heads origin develop` vide
- [x] 4.2 Relire le ruleset 20503835 « Protect main » (`allowed_merge_methods`, `required_status_checks` dont « Hermetic scale benchmark ») et le consigner dans un relevé daté de `docs/project/history/` ; couvre `repository-governance › Seul le merge commit est accepté` (scénario « Lecture du ruleset »), `scale-benchmark-gate › Le check est requis sur main`

## Workflow follow-up

- Les specs de référence sont synchronisées dans la PR (tâche 3.3) : archiver avec `openspec archive flux-sur-main-seule --yes --skip-specs` après 4.1 et 4.2, sur demande explicite.
