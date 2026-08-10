# Publication de release immuable

NXA2-07 sépare définitivement la **qualification** de la **publication**.

## Signal de release

Une publication GHCR n'est autorisée que par `.github/workflows/release.yml`, déclenché par un push de tag Git correspondant à :

```text
v<major>.<minor>.<patch>
```

Le workflow refuse la release si :

- le tag n'est pas un SemVer strict `vX.Y.Z` ;
- la version du tag ne correspond pas exactement à la version racine de `pom.xml` ;
- le commit pointé par le tag n'est pas le HEAD exact courant de `main`.

La même règle est implémentée dans `scripts/release/validate-release-tag.sh` et qualifiée par `scripts/release/test-release-tag-policy.sh`.

## Contrat de qualification atomique

Avant toute écriture dans GHCR, le workflow release attend le succès de tous les gates suivants sur la release exacte :

1. NEXUS CI ;
2. Windows Installer ;
3. Docker Distribution ;
4. Scale Benchmark en profil `full` ;
5. Scanner Corpus Benchmark en profil `full` ;
6. CodeQL ;
7. OSV-Scanner.

Les workflows correspondants exposent `workflow_call` afin que la release réutilise leurs gates réels au lieu de dupliquer une seconde implémentation divergente.

Le workflow `docker-distribution.yml` reste un workflow de **qualification uniquement**. Un push ordinaire sur `develop` ou `main`, une pull request ou un déclenchement manuel de ce workflow ne publie aucune image.

## Tags GHCR

Pour une release Maven `0.2.0` sur le commit `<sha>`, les références sont :

```text
ghcr.io/fturleque/nexus-context-engine:0.2.0
ghcr.io/fturleque/nexus-context-engine:sha-<sha>
ghcr.io/fturleque/nexus-context-engine:latest
```

Les tags versionné et SHA sont **immuables**. Avant publication, le workflow interroge GHCR et échoue explicitement si l'un des deux existe déjà. Il n'existe donc aucun chemin automatisé permettant de déplacer silencieusement un tag de version ou de commit vers un nouveau digest.

`latest` reste volontairement mutable. Il n'est déplacé qu'après :

- succès de tous les gates release ;
- build de l'image de publication exacte ;
- contrôle Trivy bloquant des vulnérabilités HIGH/CRITICAL corrigibles ;
- génération du SBOM CycloneDX ;
- publication des tags immuables ;
- vérification que les tags version et SHA désignent le même digest ;
- publication des attestations de provenance et de SBOM sur ce digest.

## Digest, SBOM et attestations

L'image est reconstruite à partir du commit qualifié et contrôlée avant push. Le digest résolu depuis le tag versionné devient l'autorité de publication.

Le workflow vérifie que le tag SHA résout le même digest. Les attestations `actions/attest` portent sur ce digest exact et le SBOM de release généré par Trivy. Enfin, `latest` est poussé et vérifié contre ce même digest.

Les preuves Trivy et CycloneDX de publication sont conservées comme artefacts GitHub Actions.

## Reproductibilité

La traçabilité d'une release repose sur le triplet :

```text
Git tag vX.Y.Z
Git commit SHA
GHCR digest sha256:...
```

Le tag Maven, le tag SHA et les attestations permettent de remonter au commit exact. Les images de base Docker restent épinglées par digest dans le Dockerfile ; les gates sont exécutés sur le commit de release avant publication.

Une reconstruction ultérieure n'est pas autorisée à remplacer le tag SemVer existant. Elle doit utiliser une nouvelle version si le contenu publié doit changer.

## Échec partiel et reprise

La publication est fail-closed. Si un tag immuable existe déjà, une nouvelle exécution échoue au lieu de l'écraser.

Si une exécution échoue après la première écriture GHCR, l'opérateur doit examiner les références déjà créées et les attestations avant toute action manuelle. Le workflow ne tente jamais de masquer l'état partiel en réécrivant un tag immuable.

Pour corriger le contenu d'une release déjà publiée, la procédure normale est :

1. corriger le code sur `develop` ;
2. qualifier et promouvoir vers `main` ;
3. incrémenter la version Maven ;
4. créer un nouveau tag `vX.Y.Z` sur le HEAD exact de `main` ;
5. laisser le workflow release exécuter l'intégralité des gates puis publier le nouveau digest.

`latest` peut ainsi avancer vers la nouvelle release sans modifier les références historiques.

## Rollback

Un rollback de consommation consiste à repointer le déploiement vers un **tag SemVer historique** ou, de préférence, vers son **digest GHCR**. Les tags immuables ne sont pas déplacés pour simuler un rollback.

Si `latest` doit être corrigé opérationnellement, ce changement doit rester une opération explicite et traçable ; il ne transforme jamais un ancien tag versionné en nouvelle image.
