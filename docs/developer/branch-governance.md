# Gouvernance des branches

NEXUS sépare explicitement intégration et release :

```text
develop  = branche d'intégration
main     = branche de release
```

## Contrat attendu pour `develop`

La configuration GitHub doit imposer :

- passage par pull request avant toute modification de `develop` ;
- au minimum les checks exact-head permanents définis par la politique du dépôt, notamment NEXUS CI et les gates sécurité retenus ;
- prise en compte des gates distribution/benchmark applicables au diff avant merge ;
- interdiction des **force pushes** ;
- interdiction de suppression de la branche ;
- bypass administrateur limité aux situations d'urgence, explicite et traçable.

Les gates qui utilisent des filtres de chemins ne doivent pas être configurés comme checks globaux requis si GitHub peut ne pas créer le check pour un diff hors périmètre. La politique de merge doit néanmoins exiger leur succès lorsqu'ils sont applicables.

## Défense en profondeur tant que `develop` reste pushable

Les workflows versionnés doivent également qualifier un éventuel push direct sur `develop` afin qu'une erreur de gouvernance GitHub ne réduise pas silencieusement la couverture après l'entrée du commit :

- `NEXUS CI`, `CodeQL` et `OSV-Scanner` écoutent directement les pushes `develop` ;
- les qualifications à filtre de chemins (Docker Distribution, Scale Benchmark, Scanner Corpus Benchmark et Windows Installer) disposent de callers `Develop Push ...` qui réutilisent les workflows qualifiants via `workflow_call` avec les mêmes périmètres de fichiers.

Cette défense en profondeur intervient **après** l'arrivée du commit sur la branche. Elle ne remplace donc jamais le ruleset GitHub exigeant une pull request et les checks applicables avant merge.

## Contrat attendu pour `main`

`main` reçoit uniquement des promotions qualifiées depuis `develop`. La configuration GitHub doit donc imposer avant merge d'une promotion :

- passage par pull request ;
- succès des checks exact-head permanents toujours créés sur une PR vers `main` :
  - `NEXUS CI / Windows gate` ;
  - `NEXUS CI / Linux reactor Maven build` ;
  - `CodeQL / CodeQL Java analysis` ;
  - `OSV-Scanner / OSV new-vulnerability delta gate` ;
  - `OSV-Scanner / Build aggregate reactor SBOM` ;
  - `OSV-Scanner / OSV aggregate SBOM vulnerability gate` ;
- succès des gates distribution/benchmark applicables au diff lorsqu'ils sont déclenchés ;
- interdiction des force pushes et de la suppression ;
- bypass administrateur limité aux situations d'urgence, explicite et traçable.

Les workflows à filtres de chemins ne doivent pas être ajoutés comme checks globaux requis lorsqu'ils peuvent légitimement ne pas être créés. Une release conteneur exige ensuite un tag SemVer `vX.Y.Z` sur le HEAD exact de `main`.

Une correction urgente qui contourne le flux normal doit rester exceptionnelle : revue explicite, qualification exact-head équivalente et justification conservée dans GitHub.

## Vérification effective

La documentation et les workflows ne peuvent pas remplacer une règle GitHub de repository. Après toute modification de ruleset/branch protection, vérifier l'état effectif via l'API GitHub et confirmer :

```text
protected = true
required pull request = true
force push = disabled
deletion = disabled
required checks = politique approuvée
```

Pour `develop`, tant que l'API GitHub retourne `protected=false`, le contrôle de gouvernance NXA3-14 (#130) reste **non satisfait**, même si les workflows CI eux-mêmes sont corrects.

Pour `main`, tant que le ruleset actif n'impose aucun `required_status_checks`, le contrôle de gouvernance NXA3-15 (#167) reste **non satisfait**, même si les workflows se déclenchent sur les pull requests.
