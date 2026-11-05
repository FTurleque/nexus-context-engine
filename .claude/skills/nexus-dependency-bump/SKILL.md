---
name: nexus-dependency-bump
description: Monter une dépendance, un outil épinglé ou une action GitHub dans NEXUS sans casser les ancres d'intégrité ni les contrats documentaires — propriétés Maven du pom, wrapper Maven, Quarkus et SDK MCP, actions épinglées par SHA, CodeQL. Utiliser pour une montée de version manuelle ou pour corriger une PR Dependabot.
argument-hint: "<dépendance> <version>"
---

# Montée de version NEXUS

## Propriété Maven (`pom.xml`)

1. Modifier la propriété `<x.version>` du pom parent.
2. Rechercher la version dans la doc courante : `git grep -n "<ancienne>" -- README.md docs ':!docs/project/audits' ':!docs/project/history'`.
3. Quarkus / SDK MCP : la version est documentée dans `docs/developer/README.md`, `docs/developer/architecture-implementation.md`, `docs/developer/rest-api.md` et vérifiée par `scripts/release/test-operational-doc-contracts.sh`.
4. `bash .claude/scripts/contracts.sh`, puis validation complète (skill `nexus-validate`).

## Wrapper Maven

Les wrappers `mvnw`/`mvnw.cmd` sont **durcis** (ancre SHA-512 versionnée, vérification du cache extrait par `scripts/release/ToolArchiveVerifier.java`). Dependabot les remplace par les scripts Apache standards : ne jamais merger cette version.

1. Récupérer et vérifier l'archive officielle :
   ```bash
   U=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/<v>/apache-maven-<v>-bin.zip
   curl -sfSLO "$U" && curl -sfSL "$U.sha512"; sha512sum apache-maven-<v>-bin.zip
   ```
   Les deux empreintes doivent être identiques.
2. Remplacer la version dans : `mvnw` (`MAVEN_VERSION="<v>"`), `mvnw.cmd` (`SET "MAVEN_VERSION=<v>"`), `.mvn/wrapper/maven-wrapper.properties`, `config/tool-integrity.properties` (`maven.<v>.sha512=<empreinte>`), `scripts/release/test-tool-integrity-anchors.sh` (regex échappée `maven\.<v>\.sha512`), `scripts/release/test-operational-doc-contracts.sh`, `scripts/validate-phase-6.ps1`, `packaging/docker/Dockerfile` (commentaire), doc courante (README, `docs/developer/*`, arc42, registre des risques). Ne pas réécrire `docs/project/audits/**` ni `docs/project/history/**`.
3. Préserver les CRLF de `mvnw.cmd` et des `.ps1` : outil Edit ou `perl -pi`, jamais `sed -i`.
4. `bash .claude/scripts/contracts.sh`, build complet via le nouveau wrapper (il télécharge et vérifie l'archive), self-smoke.
5. Ouvrir une PR dédiée, fermer la PR Dependabot en expliquant le remplacement.

## Action GitHub

- Pin par SHA complet + commentaire de version : `uses: owner/action@<sha40> # vX.Y.Z`.
- `github/codeql-action/init` et `github/codeql-action/analyze` : toujours ensemble à la même version (deux PR Dependabot séparées → combiner sur l'une, fermer l'autre).
- Vérifier que le nom des jobs portant des checks obligatoires ne change pas.

## Vulnérabilités

OSV (`osv-scanner`) et Trivy (job Docker) échouent sur une CVE High : identifier la version corrigée dans le rapport (`GHSA-…`, colonne « Fixed Version ») et prioriser cette montée avant les autres PR.
