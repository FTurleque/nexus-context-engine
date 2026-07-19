# NEXUS quality checks

Cette référence complète le skill `nexus-context-validation`.

Elle contient des contrôles détaillés volontairement séparés du `SKILL.md` principal afin de démontrer la divulgation progressive :

- comparer l'indexation complète et incrémentale ;
- vérifier la stabilité du ranking ;
- vérifier les budgets stricts ;
- inspecter `skillsDiscovered`, `skillsMatched`, `skillSelectedItems` et `skillsExecuted` ;
- vérifier que les ressources de skill ne sont pas injectées automatiquement.

NEXUS ne doit pas charger automatiquement ce fichier dans un `ContextBundle` lors de la simple activation du skill.
