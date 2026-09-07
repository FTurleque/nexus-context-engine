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
- sept checks permanents requis :
  - `Windows gate` ;
  - `Linux reactor Maven build` ;
  - `CodeQL Java analysis` ;
  - `OSV new-vulnerability delta gate / osv-scan` ;
  - `Build aggregate reactor SBOM` ;
  - `OSV aggregate SBOM vulnerability gate / osv-scan` ;
  - `SonarCloud Code Analysis`.

NXA3-14 / #130 est satisfait pour `develop`. Le même ruleset couvre également `main`.

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

Les workflows versionnés qualifient aussi un éventuel push direct sur `develop` afin qu'une erreur future de gouvernance GitHub ne réduise pas silencieusement la couverture après l'entrée du commit sur la branche :

- `NEXUS CI`, `CodeQL` et `OSV-Scanner` écoutent directement les pushes `develop` ;
- les qualifications à filtre de chemins (Docker Distribution, Scale Benchmark, Scanner Corpus Benchmark et Windows Installer) disposent de callers `Develop Push ...` qui réutilisent les workflows qualifiants via `workflow_call` avec les mêmes périmètres de fichiers.

Cette défense en profondeur intervient **après** l'arrivée du commit sur la branche. Elle ne remplace pas le ruleset GitHub exigeant une pull request et les checks applicables avant merge.

## Contrat attendu pour `main`

`main` reçoit uniquement des promotions qualifiées depuis `develop`. La configuration GitHub doit donc conserver avant merge d'une promotion :

- passage par pull request ;
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
```

Aucune approbation humaine additionnelle ni resynchronisation stricte de branche n'est requise par le modèle solo courant. Si le projet passe un jour à plusieurs mainteneurs, cette section devra être revue explicitement avant de changer les règles GitHub.
