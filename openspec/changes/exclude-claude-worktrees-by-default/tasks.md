# Tasks

## 1. Exclusion dans le moteur

- [ ] 1.1 Ajouter dans `ProjectIgnoreMatcher` la détection des segments consécutifs `.claude`/`worktrees` (insensible à la casse, à toute profondeur), appliquée dans `isBuiltInExcluded` avant les fichiers d'ignore ; vérifier par `./mvnw -B -ntp -pl core test -Dtest=ProjectIgnoreMatcher* -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 1.2 Couvrir dans `ProjectScannerTest` : worktree à la racine, worktree dans `module/`, `.claude/skills/` toujours indexé, `docs/worktrees/` et `.claude/worktrees-notes.md` non exclus, négation `!.claude/worktrees/` sans effet ; vérifier par `-Dtest=ProjectScannerTest`
- [ ] 1.3 Vérifier sous Windows et Linux que les cas de 1.2 passent (séparateurs de chemin) en lançant le module `core` complet : `./mvnw -B -ntp -pl core test`

## 2. Décision et documentation

- [ ] 2.1 Rédiger l'ADR suivant (skill `nexus-adr`), statut accepté, qui complète l'ADR-0021 ; mettre à jour `docs/adr/README.md` et la vue arc42 `09-decisions.md` ; vérifier avec `bash .claude/scripts/contracts.sh`
- [ ] 2.2 Mettre à jour `docs/developer/indexing-pipeline.md` si elle liste les exclusions intégrées, sinon l'y ajouter ; vérifier que `bash .claude/scripts/contracts.sh` passe

## 3. Intégration

- [ ] 3.1 Retirer `/.claude/worktrees/` du `.nexusignore` du dépôt seulement si le self-smoke passe sans (sinon le garder, la règle étant une défense en profondeur) ; vérifier par `scripts/self-smoke.ps1` puis `/nx-validate`

## Workflow follow-up

- Ouvrir la PR vers `develop` (skill `nexus-pr-workflow`), merge exact-head.
- Archiver le changement OpenSpec une fois la PR fusionnée.
