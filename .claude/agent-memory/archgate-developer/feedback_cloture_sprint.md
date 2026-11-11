---
name: feedback-cloture-sprint
description: Après le merge des PR d'un sprint, archiver les changes OpenSpec terminés et supprimer branches locales et worktrees mergés sans attendre qu'on le demande
metadata:
  type: feedback
---

Une fois les PR d'un sprint mergées dans `develop`, la clôture fait partie du travail : archiver chaque change OpenSpec dont toutes les tâches sont cochées (`openspec archive <nom> --yes --skip-specs` si les deltas sont déjà dans `openspec/specs`, puis noms datés dans `docs/developer/openspec-traceability.md`), supprimer les branches locales `[gone]` ou mergées, les branches `claude/*` sans travail propre et les worktrees détachés sur des commits mergés.

**Why:** l'utilisateur a dû le réclamer (« tu n'as pas fait le ménage dans les branch et tu n'as pas clôturé les change finis »).

**How to apply:** en fin de sprint ou après le dernier merge, proposer ou faire la PR d'archivage et le ménage. Un change qui garde des tâches mainteneur reste ouvert ; une branche avec des commits non mergés se garde et se signale.

Piège : le hook `guard-command.ps1` lit `git push ...` jusqu'à la fin de la ligne, donc `git push ...; gh pr create --base develop` sur une même ligne est refusé comme push vers develop. Lancer `gh pr create` dans un appel séparé.
