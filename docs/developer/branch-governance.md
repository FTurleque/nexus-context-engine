# Gouvernance des branches

NEXUS sépare explicitement intégration et release :

```text
develop  = branche d'intégration
main     = branche de release
```

## État GitHub effectif

Au 6 septembre 2026, le ruleset repository actif `Protect main & develop` cible `~DEFAULT_BRANCH` et `refs/heads/develop`.

Il impose actuellement :

- passage par pull request ;
- résolution des conversations de review ;
- interdiction de suppression ;
- interdiction des non-fast-forward / force pushes ;
- bypass `Repository admin` limité au mode `pull_request` ;
- sept checks permanents requis :
  - `Windows gate` ;
  - `Linux reactor Maven build` ;
  - `CodeQL Java analysis` ;
  - `OSV new-vulnerability delta gate / osv-scan` ;
  - `Build aggregate reactor SBOM` ;
  - `OSV aggregate SBOM vulnerability gate / osv-scan` ;
  - `SonarCloud Code Analysis`.

NXA3-14 / #130 est donc satisfait pour `develop`. Le même ruleset couvre également `main`.

Le résiduel de hardening est `strict_required_status_checks_policy=false` : GitHub n'exige pas actuellement qu'une pull request soit remise à jour avec sa branche de base immédiatement avant merge. Toute modification de ce paramètre est une action repository-admin externe au code versionné.

## Contrat attendu pour `develop`

La configuration GitHub doit conserver :

- passage par pull request avant toute modification de `develop` ;
- les checks exact-head permanents définis par la politique du dépôt ;
- prise en compte des gates distribution/benchmark applicables au diff avant merge ;
- interdiction des **force pushes** ;
- interdiction de suppression de la branche ;
- bypass administrateur explicite, minimal et limité au flux pull request.

Les gates qui utilisent des filtres de chemins ne doivent pas être configurés comme checks globaux requis si GitHub peut ne pas créer le check pour un diff hors périmètre. La politique de merge doit néanmoins exiger leur succès lorsqu'ils sont applicables.

## Défense en profondeur sur `develop`

Les workflows versionnés qualifient aussi un éventuel push direct sur `develop` afin qu'une erreur future de gouvernance GitHub ne réduise pas silencieusement la couverture après l'entrée du commit :

- `NEXUS CI`, `CodeQL` et `OSV-Scanner` écoutent directement les pushes `develop` ;
- les qualifications à filtre de chemins (Docker Distribution, Scale Benchmark, Scanner Corpus Benchmark et Windows Installer) disposent de callers `Develop Push ...` qui réutilisent les workflows qualifiants via `workflow_call` avec les mêmes périmètres de fichiers.

Cette défense en profondeur intervient **après** l'arrivée du commit sur la branche. Elle ne remplace donc jamais le ruleset GitHub exigeant une pull request et les checks applicables avant merge.

## Contrat attendu pour `main`

`main` reçoit uniquement des promotions qualifiées depuis `develop`. La configuration GitHub doit donc conserver avant merge d'une promotion :

- passage par pull request ;
- succès des checks exact-head permanents ;
- succès des gates distribution/benchmark applicables au diff lorsqu'ils sont déclenchés ;
- interdiction des force pushes et de la suppression ;
- bypass administrateur explicite et minimal.

Les workflows à filtres de chemins ne doivent pas être ajoutés comme checks globaux requis lorsqu'ils peuvent légitimement ne pas être créés. Une release conteneur exige ensuite un tag SemVer `vX.Y.Z` sur le HEAD exact de `main`.

Une correction urgente qui contourne le flux normal doit rester exceptionnelle : revue explicite, qualification exact-head équivalente et justification conservée dans GitHub.

## Vérification effective

La documentation et les workflows ne peuvent pas remplacer une règle GitHub de repository. Après toute modification de ruleset/branch protection, vérifier l'état effectif via l'API GitHub et confirmer au minimum :

```text
protected = true
required pull request = true
force push = disabled
deletion = disabled
required checks = politique approuvée
```

Vérifier également explicitement `strict_required_status_checks_policy` lorsque la politique impose qu'une PR soit à jour avec sa base avant merge. L'état GitHub effectif, et non un ancien document ou run, reste l'autorité pour ce contrôle repository-admin.
