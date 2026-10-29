# Design

## Context

Sur `origin/develop`, `scripts/` contient 23 scripts racine ; seuls `validate-phase-6.ps1`, `measure-scale-regression.ps1` et `verify-scale-budgets.py` sont appelés par la CI, et `self-smoke.ps1` par la procédure de validation. Le wrapper Maven (`mvnw`/`mvnw.cmd`) vérifie l'archive Maven contre `config/tool-integrity.properties` ; seuls `measure-scale-regression.ps1`, `self-smoke.ps1`, `validate-minos-integration.ps1`, `validate-phase-6.ps1` et `release/build-windows-distribution.ps1` l'utilisent.

Les workflows épinglent leurs actions par SHA avec la version en commentaire (règle `.claude/rules/ci-and-scripts.md`), mais aucun contrat ne vérifie la concordance SHA ↔ commentaire. Le contrat `test-final-audit-contracts.sh` fige des propriétés de `semantic-search-qualification.yml` (checkout exact, `timeout-minutes: 180`).

**Dépendances envers d'autres sprints.**
- Sprint 1 (fusionné, PR #265 à #268) : `validate-phase-6.ps1` (sélection du wrapper, refus du repli en CI) et `ci.yml` (Windows gate sur JDK 21 épinglé). Ce sprint modifie les deux fichiers après lui et conserve ses garanties.
- Changement `sprint-01-cloture-mesures` (non fusionné) : il modifie `scale-benchmark.yml` (déclencheur sans filtre, détection interne). La tâche `AUD-SEC-07` sur ce fichier se fait après sa fusion, ou se rebase dessus.
- Sprint 6 : `AUD-PRF-02` a besoin d'une qualification sémantique qui termine (`AUD-DEP-19`).
- Sprint 9 : chaîne de release ; la décision D7 fixe seulement si la release exige la qualification sémantique.

## Goals / Non-Goals

**Goals:**
- Une seule implémentation de l'appel Maven dans les scripts, par le wrapper vérifié.
- Aucun input de workflow interpolé dans du shell.
- Épinglages cohérents et vérifiés mécaniquement.
- Une borne de durée sur chaque job qui l'accepte.
- Une qualification sémantique réelle qui termine sur `develop`.

**Non-Goals:**
- Réécrire les scripts de mesure archivés.
- Changer les seuils de qualité sémantique (`recallAt3`, `hitAt3`, `mrrAt3`) ou les budgets de benchmark.
- Modifier les workflows réutilisables amont d'OSV.

## Decisions

**D1 — Archiver avant de factoriser.** Les scripts d'itération non cités par la documentation courante partent dans `scripts/archive/` (historique Git conservé par `git mv`) avant l'extraction du module : on ne migre pas des scripts morts. `validate-iteration-10.ps1`, cité par `docs/developer/multi-language.md`, est soit migré, soit retiré de cette page ; les documents datés ne sont pas modifiés. Les scripts archivés sont exclus de la migration et marqués non maintenus par un `README.md` de dossier.

**D2 — Module `scripts/lib/NexusScript.psm1`.** Il exporte `Invoke-Maven`, `Resolve-AbsolutePath` et `Invoke-NexusJson`. `Invoke-Maven` reprend la sélection de `validate-phase-6.ps1` : wrapper obligatoire en CI (`CI` ou `GITHUB_ACTIONS`), repli local sur le Maven installé avec avertissement, code retour non nul propagé par une exception. `validate-phase-6.ps1` importe le module au lieu de définir `Select-MavenCommand`. Alternative écartée : supprimer le repli local, ce qui changerait le comportement fixé au sprint 1 sans décision.

**D3 — Inputs par `env:`.** `inputs.profile` est lu dans un bloc `run:` uniquement via une variable d'environnement de l'étape (`REQUESTED: ${{ inputs.profile }}` puis `"$REQUESTED"`). L'input `workflow_call` reste `string` (un appelant réutilisable ne peut pas utiliser `choice`) ; la validation `ci|full` existante s'applique à la variable.

**D4 — Contrat SHA ↔ commentaire.** Un contrat local lit chaque `uses: owner/action@<sha> # vX.Y.Z` des workflows et échoue si deux lignes associent le même SHA à des commentaires différents, si une action a deux versions majeures, ou si un commentaire n'est pas une version. La concordance avec les tags amont (`git ls-remote`) n'est pas vérifiée en CI, faute de réseau garanti ; elle est vérifiée à la main lors d'une montée (skill `nexus-dependency-bump`).

**D5 — Tag de commit pour OSV.** Les workflows réutilisables OSV sont épinglés sur le SHA de commit d'un tag publié qui contient le correctif « fail closed » (`v2.7.0` → `b1d267ae…`, ou un tag ultérieur s'il le contient), commenté `# vX.Y.Z`. Le SHA de l'objet tag (`6ad84ce1…`) n'est pas utilisé.

**D6 — Runners et cache.** Les jobs de benchmark et de qualification de performance utilisent `ubuntu-24.04` et `windows-2025` ; les autres jobs peuvent rester sur `-latest`, ce qui est documenté. Le cache Maven (`setup-java` `cache: maven`) n'est activé que si une mesure sur `ci.yml` montre un gain, et jamais sur les jobs de release ou de qualification d'artefacts publiés.

**D7 — Qualification sémantique en deux corpus.** Les PR exécutent un sous-ensemble représentatif du corpus (borne de durée inférieure à 45 min) ; le corpus complet s'exécute sur `develop` par un déclencheur `push` filtré par chemins ou par un wrapper planifié qui cible `develop`, sans `cancel-in-progress`. Le rôle de la qualification dans la release (exigée ou non par `release.yml`) est tranché par un nouvel ADR (skill `nexus-adr`) qui complète ADR-0014.

## Risks / Trade-offs

- [Un script archivé sert encore à un contributeur] → il reste exécutable dans `scripts/archive/` ; seul son statut de point d'entrée change.
- [Le sous-ensemble PR du corpus sémantique masque une régression] → le corpus complet tourne sur `develop` après fusion et ses seuils restent ceux d'aujourd'hui.
- [Le contrat `timeout-minutes: 180` de `test-final-audit-contracts.sh` casse avec deux corpus] → le contrat est mis à jour dans la même PR, avec une borne par corpus.
- [Une image `windows-2025` est retirée par GitHub] → l'échec est visible et la montée est explicite, ce qui est le but de l'épinglage.
- [Cache Maven empoisonné] → le cache n'est restauré que pour des jobs sans publication, et les artefacts publiés restent construits sans cache.
