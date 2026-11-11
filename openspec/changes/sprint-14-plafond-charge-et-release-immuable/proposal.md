# Proposition

## Why

La release v0.2.0 du 11/11/2026 a laissé deux garanties plus faibles que ce qu'annonce la documentation. Pour admettre le run de release `42945695122` (palier 1M à 40 755 ms pour un plafond de 40 000 ms), le plafond de peuplement à 1M est devenu proportionnel au palier 500k du même rapport. Hors PR, aucune borne fixe ne refuse donc plus un ralentissement uniforme jusqu'à 52 s, alors que la page du benchmark affirme encore que les plafonds absolus sont inchangés (`AUD-TST-18`). Le tag `v0.2.0` a par ailleurs été supprimé puis reposé grâce au contournement administrateur du ruleset des tags, et la release publiée n'est pas immuable (`AUD-DEP-37`). Les deux constats sont planifiés au sprint 14 et doivent être réglés avant la prochaine release.

Source : `docs/project/audits/2026-10-17/findings.json` (`AUD-TST-18`, `AUD-DEP-37`, sprint 14), `docs/project/audits/2026-10-17/etat-2026-11-11.md` (sections des deux constats).

## What Changes

- `AUD-TST-18` (Faible)
  - [Existant vérifié] `scripts/verify-scale-budgets.py:50-55` fixe le plafond de sécurité à 40 000 ms pour 1M et 20 000 ms pour 500k ; `:60-62` déclare le rapport `(500_000, 2.6)` et `:98-100` calcule `ceiling = max(ceiling, int(sqlite[lower_tier]['populationMs'] * ratio))`. Le plafond effectif de 1M atteint donc 52 000 ms quand le palier 500k est à son plafond.
  - [Existant vérifié] La comparaison relative à la base (`:102-112`, `max(base × 1,20, base + jitter)`) ne s'applique qu'avec le marqueur `scale-benchmark-base-population-comparable` (`:19`), absent d'un `workflow_call` de release et d'un `workflow_dispatch` (`docs/developer/scale-regression-benchmark.md:128`).
  - [Existant vérifié] Le relevé `docs/project/history/population-1m-runners-2026-11-11.md:15-32` donne seize mesures à 1M entre 25 046 et 40 755 ms et un rapport 1M/500k entre 2,36 et 2,47 (`:39`) ; tous les runners sont des Linux amd64 à 4 processeurs, sans modèle de processeur publié (`:9`).
  - [Existant vérifié] `scripts/tests/test_scale_budgets.py:186-189` (`test_uniform_slowdown_still_fails_on_500k_ceiling`) ne refuse un ralentissement uniforme qu'au-delà de 20 000 ms à 500k.
  - [Écart] `docs/developer/scale-regression-benchmark.md:124` affirme « Les plafonds absolus de latence et de population restent inchangés », contredit par `:130` (plafond de 1M `max(40 s, 2,6 × population 500k)`, jusqu'à 52 s). Le constat cite aussi `:146` ; au HEAD de cette branche, `:146` renvoie au protocole 6 sans contredire (commit `f87eda01`) : seule `:124` reste fausse.
  - [Exigence] Le gate refuse le palier 1M au-delà d'un plafond absolu fixe, en plus de la règle proportionnelle, pour le rapport général comme pour la courbe dédiée.
  - [Exigence] La page du benchmark énonce les plafonds de population réellement appliqués, sous contrat.
  - [Question] Valeur du plafond absolu : voir « Questions ouvertes ».
- `AUD-DEP-37` (Faible)
  - [Existant vérifié] Relevé `gh` du 11/11 (`findings.json`, `AUD-DEP-37`, champ `preuve`) : release `v0.2.0` à `immutable: false` ; ruleset « Protect tags » (26777159, `refs/tags/v*`) avec les règles `creation`, `update`, `deletion`, `non_fast_forward` et un acteur de contournement `RepositoryRole 5` (admin) en `bypass_mode: always` ; runs `release.yml` `42923369260` (tag sur `3ecdb2be`, échec au gate de charge) puis `42945695122` (tag reposé sur `9f6e7839`, succès).
  - [Existant vérifié] `scripts/release/publish-github-release-assets.sh:72-77` crée la release en brouillon, `:132-140` téléverse et revérifie chaque asset octet par octet, `:145` publie le brouillon : c'est l'ordre recommandé par GitHub pour les releases immuables (documentation GitHub « Immutable releases », section « Best practices »). Aucune étape ne contrôle le champ `immutable` de la release publiée.
  - [Existant vérifié] GitHub n'applique l'immuabilité qu'aux releases publiées après l'activation du réglage (documentation GitHub « Preventing changes to your releases ») : `v0.2.0` restera non immuable.
  - [Écart] Le constat propose de passer le contournement du ruleset en mode `pull_request` ou de le retirer. Le même ruleset porte la règle `creation` : sans acteur de contournement utilisable par un push de tag, plus personne ne pourrait créer de tag `v*`, donc plus aucune release. La création et la mise à jour ou la suppression doivent être portées par deux rulesets distincts.
  - [Existant vérifié] `docs/developer/immutable-release-publishing.md:115` dit de créer une nouvelle version SemVer pour corriger le contenu d'une release, sans traiter le cas d'un run en échec avant publication ; `docs/developer/release-and-recovery.md:36-46` ne décrit aucune reprise d'un run de release échoué.
  - [Exigence] Releases immuables activées, et contrôle par le job de publication que la release publiée est immuable avant le déplacement de `latest`.
  - [Exigence] Aucun acteur ne peut mettre à jour ni supprimer un tag `v*` ; la création reste réservée au mainteneur.
  - [Exigence] Procédure documentée de reprise d'une release échouée : relance du même run si le commit ne change pas, nouvelle version sinon, jamais de tag recréé.
  - [Proposition] Réglages GitHub appliqués par le mainteneur hors PR, vérifiés par `gh api` en lecture.

## Type de changement

outillage (gate de charge, script de publication, réglages GitHub) avec une part documentaire : profil court.

## Périmètre et exclusions

**Dans le périmètre :**
- Le calcul du plafond de peuplement à 1M dans `scripts/verify-scale-budgets.py` et ses tests `scripts/tests/test_scale_budgets.py`.
- La page `docs/developer/scale-regression-benchmark.md` et son contrat dans `scripts/release/test-operational-doc-contracts.sh`.
- Le réglage « releases immuables » du dépôt et les rulesets de tags `refs/tags/v*` (tâches mainteneur, hors PR).
- Le contrôle du champ `immutable` par `scripts/release/publish-github-release-assets.sh`.
- La procédure de reprise d'une release échouée dans `docs/developer/release-and-recovery.md` et `docs/developer/immutable-release-publishing.md`.

**Hors périmètre :**
- Les plafonds des paliers 10k, 100k et 500k, les budgets de latence p95, le budget de durée totale de 240 s (jugé justifié par le constat) et la comparaison relative en PR : inchangés.
- Le protocole de mesure (version 6, échantillons, échauffement) : aucune mesure ne change.
- Une comparaison de la release avec la dernière release mesurée : écartée (voir design, « Décisions »).
- Le rattrapage de la release `v0.2.0` : elle ne peut pas devenir immuable ; ni suppression ni republication.
- Les tags d'image GHCR : leur immuabilité est déjà portée par `scripts/release/ghcr-immutable-preflight.sh`.
- Les autres réglages de sécurité du dépôt (`repository-security-settings`) et le ruleset des branches `Protect main & develop`.

## Capabilities

### New Capabilities
<!-- Aucune. -->

### Modified Capabilities
- `scale-benchmark-gate`: ajout d'une exigence de plafond absolu de peuplement à 1M et d'une exigence de concordance entre la page du benchmark et le gate.
- `release-pipeline`: ajout d'exigences sur l'immuabilité des releases publiées, l'interdiction de déplacer ou supprimer un tag `v*`, et la procédure de reprise d'une release échouée.

## Impact

- Gate de charge : `scripts/verify-scale-budgets.py`, `scripts/tests/test_scale_budgets.py` (tous deux dans `WATCHED_PATHS` de `.github/workflows/scale-benchmark.yml:69-71` : la PR mesurera).
- Publication : `scripts/release/publish-github-release-assets.sh`, `scripts/release/test-publish-github-release-assets.sh`.
- Contrats : `scripts/release/test-operational-doc-contracts.sh`.
- Documentation : `docs/developer/scale-regression-benchmark.md`, `docs/developer/release-and-recovery.md`, `docs/developer/immutable-release-publishing.md`, `docs/developer/branch-governance.md`, `docs/developer/openspec-traceability.md`, ADR `SEC-0068` (statut `Proposed`, à compléter).
- Réglages GitHub (hors PR) : `immutable-releases` du dépôt, rulesets de cible `tag`.
- Relevé daté de la lecture des réglages sous `docs/project/history/`.
- Aucun code Java, aucune migration SQLite.

## Questions ouvertes

- [Question] `AUD-TST-18` : quelle valeur pour le plafond absolu de 1M ? Le constat suggère « ex. 48 s » sans mesure à l'appui. Bornes sourcées : au moins 40 755 ms (plus grande mesure admise, `docs/project/history/population-1m-runners-2026-11-11.md:32`) et strictement moins de 52 000 ms (plafond effectif actuel). [Proposition] 48 906 ms, soit 40 755 ms × 1,20, le facteur relatif déjà appliqué par le gate (`scripts/verify-scale-budgets.py:105`). Le mainteneur confirme ou choisit une autre valeur dans ces bornes avant la tâche 1.1.
- [Question] `AUD-DEP-37` : accepter que `v0.2.0` reste non immuable, consigné dans le relevé de la tâche 2.5 ? L'alternative (supprimer la release et republier sous un nouveau tag) changerait la version annoncée sans gain de contenu.
