# Gouvernance des branches

NEXUS sépare explicitement intégration et release :

```text
develop  = branche d'intégration
main     = branche de release
```

## Modèle de maintenance

NEXUS est actuellement maintenu par **un seul mainteneur**. Cette contrainte est une décision de projet, pas un défaut de gouvernance.

Tant que ce modèle n'est pas explicitement changé :

- aucune approbation humaine distincte du mainteneur n'est requise avant merge ;
- aucune règle ne doit être ajoutée uniquement pour simuler une revue à plusieurs personnes qui n'existe pas ;
- la barrière de confiance avant merge repose sur la pull request, les checks automatisés exact-head, l'interdiction des force-pushes/suppressions et la résolution des conversations lorsqu'il y en a ;
- le fait qu'une PR ne soit pas forcée à se resynchroniser avec sa base immédiatement avant merge n'est pas suivi comme finding tant que les checks applicables qualifient bien le HEAD candidat.

Les audits NEXUS ne doivent donc pas remonter l'absence d'approbation humaine obligatoire ou l'absence de mode « branch up to date before merge » comme vulnérabilités/hardenings résiduels sans changement explicite de cette politique.

## État GitHub effectif

Le ruleset repository actif `Protect main & develop` cible `~DEFAULT_BRANCH` et `refs/heads/develop`.

Il impose actuellement :

- passage par pull request ;
- résolution des conversations de review ;
- interdiction de suppression ;
- interdiction des non-fast-forward / force pushes ;
- bypass `Repository admin` limité au mode `pull_request` ;
- huit checks permanents requis :
  - `Windows gate` ;
  - `Linux reactor Maven build` ;
  - `CodeQL Java analysis` ;
  - `OSV new-vulnerability delta gate / osv-scan` ;
  - `Build aggregate reactor SBOM` ;
  - `OSV aggregate SBOM vulnerability gate / osv-scan` ;
  - `SonarCloud Code Analysis` ;
  - `Hermetic scale benchmark`.

`Hermetic scale benchmark` (`.github/workflows/scale-benchmark.yml`) est produit sur toute PR et conclut en succès sans mesurer lorsque la PR ne touche aucun chemin surveillé (voir [scale-regression-benchmark.md](scale-regression-benchmark.md)) : il peut donc être requis sans laisser en attente les PR qui ne touchent pas ses chemins.

NXA3-14 / #130 est satisfait pour `develop`. Le même ruleset couvre également `main`.

### Rulesets de tags

À côté du ruleset des branches, [SEC-0068](../architecture/adr/SEC-0068-publier-les-releases-depuis-un-environnement-protege-avec-des-assets-attestes.md) exige pour les tags de version `refs/tags/v*` deux rulesets de cible `tag` :

- « Immutable version tags » : règles `update`, `deletion` et `non_fast_forward`, aucun acteur de contournement ;
- « Protect tags » (id 26777159) : règle `creation`, contournement par le rôle `Repository admin`.

**[Existant vérifié]** Lecture du 2026-11-11 : seul « Protect tags » existe ; il porte les quatre règles `creation`, `update`, `deletion` et `non_fast_forward`, avec le contournement admin en mode `always`. La séparation en deux rulesets est un geste du mainteneur, consigné avec ses lectures `gh api` dans [le relevé du 2026-11-11](../project/history/releases-immuables-et-tags-2026-11-11.md) et dans les relevés qui le suivent. La lecture et la procédure de reprise d'une release sans recréer son tag sont décrites dans [immutable-release-publishing.md](immutable-release-publishing.md#politique-de-publication-cible-sec-0068-proposée) et [release-and-recovery.md](release-and-recovery.md#reprise-dune-release-échouée).

## Méthode d'intégration : merge commit uniquement

Une PR vers `develop` ou `main` s'intègre **uniquement par merge commit**, exact-head (`gh pr merge <n> --merge --match-head-commit <sha>`). Le squash et le rebase sont interdits : ils réécrivent les SHA qualifiés par la CI exact-head, et la promotion `develop` → `main` suppose que `main` ne contient que des commits déjà présents sur `develop`.

La règle est portée par GitHub, pas seulement par le hook local de Claude Code :

- le ruleset `Protect main & develop` (id 20503835) n'autorise que la méthode `merge` : `allowed_merge_methods = ["merge"]` ;
- les réglages du dépôt désactivent les deux autres méthodes : `allow_squash_merge = false` et `allow_rebase_merge = false` (`allow_merge_commit` reste à `true`).

Ces réglages sont modifiés à la main par le mainteneur dans GitHub. Ils se vérifient en lecture :

```bash
gh api repos/FTurleque/nexus-context-engine/rulesets/20503835 --jq '.rules[]|select(.type=="pull_request").parameters.allowed_merge_methods'
gh api repos/FTurleque/nexus-context-engine --jq '[.allow_squash_merge,.allow_rebase_merge]'
```

Résultats attendus : `["merge"]` puis `[false,false]`.

- **[Existant vérifié]** Lecture du 2026-11-09 : le ruleset renvoie `["merge"]` et les réglages du dépôt `[false,false]`.

## Propriétaires déclarés

[`.github/CODEOWNERS`](../../.github/CODEOWNERS) désigne le mainteneur comme propriétaire des fichiers qui gouvernent la qualification et la chaîne d'approvisionnement : `.github/workflows/`, `scripts/release/`, `config/tool-integrity.properties`, les wrappers Maven (`mvnw`, `mvnw.cmd`, `.mvn/`) et `packaging/`. GitHub affiche ce propriétaire sur les PR qui touchent ces chemins.

La revue code owner n'est **pas** obligatoire (`require_code_owner_review = false`) : avec un seul mainteneur, elle simulerait une revue à plusieurs personnes qui n'existe pas (voir [Modèle de maintenance](#modèle-de-maintenance)).

Le signalement des vulnérabilités suit [`SECURITY.md`](../../SECURITY.md#signaler-une-vulnérabilité) : signalement privé GitHub, aucune issue publique.

## Contrat attendu pour `develop`

La configuration GitHub doit conserver :

- passage par pull request avant toute modification de `develop` ;
- intégration par merge commit uniquement (`allowed_merge_methods = ["merge"]`) ;
- les checks exact-head permanents définis par la politique du dépôt ;
- prise en compte des gates distribution/benchmark applicables au diff avant merge ;
- interdiction des **force pushes** ;
- interdiction de suppression de la branche ;
- bypass administrateur explicite, minimal et limité au flux pull request.

Les gates qui utilisent des filtres de chemins ne doivent pas être configurés comme checks globaux requis si GitHub peut ne pas créer le check pour un diff hors périmètre. La politique de merge doit néanmoins exiger leur succès lorsqu'ils sont applicables.

## Défense en profondeur sur `develop`

Les workflows versionnés qualifient aussi un éventuel push direct sur `develop` afin qu'une erreur future de gouvernance GitHub ne réduise pas silencieusement la couverture après l'entrée du commit sur la branche :

- `NEXUS CI`, `CodeQL` et `OSV-Scanner` écoutent directement les pushes `develop` ;
- les qualifications à filtre de chemins (Docker Distribution, Scale Benchmark, Scanner Corpus Benchmark et Windows Installer) disposent de callers `Develop Push ...` qui réutilisent les workflows qualifiants via `workflow_call` avec les mêmes périmètres de fichiers.

Cette défense en profondeur intervient **après** l'arrivée du commit sur la branche. Elle ne remplace pas le ruleset GitHub exigeant une pull request et les checks applicables avant merge.

## Contrat attendu pour `main`

`main` reçoit uniquement des promotions qualifiées depuis `develop`. La configuration GitHub doit donc conserver avant merge d'une promotion :

- passage par pull request ;
- intégration par merge commit uniquement ;
- succès des checks exact-head permanents ;
- succès des gates distribution/benchmark applicables au diff lorsqu'ils sont déclenchés ;
- interdiction des force pushes et de la suppression ;
- bypass administrateur explicite et minimal.

Les workflows à filtres de chemins ne doivent pas être ajoutés comme checks globaux requis lorsqu'ils peuvent légitimement ne pas être créés. Une release conteneur exige ensuite un tag SemVer `vX.Y.Z` sur le HEAD exact de `main`.

Une correction urgente qui contourne le flux normal doit rester exceptionnelle et conserver une qualification exact-head équivalente ainsi qu'une justification GitHub.

## Vérification effective

La documentation et les workflows ne peuvent pas remplacer une règle GitHub de repository. Après toute modification de ruleset/branch protection, vérifier l'état effectif via l'API GitHub et confirmer au minimum :

```text
protected = true
required pull request = true
force push = disabled
deletion = disabled
required checks = politique approuvée
allowed merge methods = ["merge"]
allow_squash_merge = false
allow_rebase_merge = false
```

Aucune approbation humaine additionnelle ni resynchronisation stricte de branche n'est requise par le modèle solo courant. Si le projet passe un jour à plusieurs mainteneurs, cette section devra être revue explicitement avant de changer les règles GitHub.

## OpenSpec repository-security-settings

Spec : [`openspec/specs/repository-security-settings/spec.md`](../../openspec/specs/repository-security-settings/spec.md) ; matrice de traçabilité : [openspec-traceability.md](openspec-traceability.md).

Le dépôt public est surveillé en continu, indépendamment des PR : les gates OSV des PR ne couvrent que le code en revue, les alertes GitHub couvrent l'état fusionné.

### Comportement existant vérifié

- **[Existant vérifié]** `gh api repos/FTurleque/nexus-context-engine --jq '.security_and_analysis'` renvoie `dependabot_security_updates`, `secret_scanning` et `secret_scanning_push_protection` à `enabled`, et `gh api repos/FTurleque/nexus-context-engine/vulnerability-alerts -i` répond `204` (lecture du 2026-11-05). Aucune alerte Dependabot ni secret scanning n'est ouverte à cette date.
- **[Existant vérifié]** La procédure de traitement (délai de 7 jours, triage, fermeture `used_in_tests` d'une fixture de redaction avec son chemin) vit dans [`SECURITY.md`](../../SECURITY.md#traitement-des-alertes-de-sécurité) ; `scripts/release/test-operational-doc-contracts.sh:522-533` exige ses sections, le motif `used_in_tests` et l'existence de la fixture citée.

- **[Existant vérifié]** `gh api repos/FTurleque/nexus-context-engine/private-vulnerability-reporting` renvoie `{"enabled":true}` (lecture du 2026-11-09) ; `SECURITY.md` décrit ce canal, les versions prises en charge et le délai de réponse, exigés par `scripts/release/test-operational-doc-contracts.sh:549-556`.

### Écarts et limites

- **[Limite]** Les réglages sont modifiés à la main par le mainteneur ; aucun contrôle automatique du dépôt ne détecte leur désactivation. La vérification reste la lecture `gh api` ci-dessus.

## OpenSpec repository-governance

Spec : [`openspec/specs/repository-governance/spec.md`](../../openspec/specs/repository-governance/spec.md) ; matrice de traçabilité : [openspec-traceability.md](openspec-traceability.md).

Cette section reprend les règles des sections « Méthode d'intégration : merge commit uniquement » et « Propriétaires déclarés » ci-dessus.

### Comportement existant vérifié (repository-governance)

- **[Existant vérifié]** Lecture du 2026-11-09 : `allowed_merge_methods` du ruleset 20503835 vaut `["merge"]` et `[allow_squash_merge, allow_rebase_merge]` vaut `[false,false]`.
- **[Existant vérifié]** `scripts/release/test-operational-doc-contracts.sh:535-576` exige la politique merge commit dans cette page et dans le skill `nexus-pr-workflow`, et un propriétaire pour chaque chemin sensible de `.github/CODEOWNERS`.

### Écarts et limites (repository-governance)

- **[Limite]** Les réglages GitHub se modifient à la main ; aucun contrôle automatique ne détecte leur retour en arrière. La vérification reste la lecture `gh api` de la section « Méthode d'intégration ».
- **[Limite]** L'affichage du code owner par GitHub sur une PR n'est vérifié par aucun test.
