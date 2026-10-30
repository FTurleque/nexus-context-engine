# Design

## Context

Deux Dockerfiles produisent la même image : `packaging/docker/Dockerfile` construit depuis les sources (contexte : racine du dépôt, `COPY . .` dans l'étape `build`), `packaging/docker/Dockerfile.runtime` assemble l'image depuis un payload déjà construit (contexte : racine d'installation Windows via le compose, `context: ..`, ou répertoire de staging dans `docker-distribution.yml`). Les deux créent `/opt/nexus` et les scripts d'entrée avec l'UID 10001 puis exécutent `USER 10001:10001`. Le compose (`docker-compose.yml.template`) porte la seule sonde de santé et monte `/workspace` en lecture seule.

La racine d'installation Windows contient aussi `docker/.env` (token REST) et, par défaut, le répertoire de données lié au conteneur : elle fait partie du contexte envoyé au moteur Docker par le compose.

Le dépôt est public, sous licence propriétaire source-available (`LICENSE`), avec un seul mainteneur. `docs/developer/branch-governance.md` décrit le ruleset « Protect main & develop » (id 20503835) ; lu le 29/10/2026 en lecture seule, il autorise `merge`, `squash` et `rebase`, les réglages du dépôt ont `allow_squash_merge` et `allow_rebase_merge` à `true`, et le signalement privé de vulnérabilités est désactivé. Ni `SECURITY.md` ni `CODEOWNERS` n'existent sur `origin/develop`.

`scripts/release/test-operational-doc-contracts.sh` lit déjà `quarkus.platform.version` dans le `pom.xml` et exige que trois documents contiennent ce numéro ; la ligne du SDK MCP est un littéral. La documentation courante recopie donc des versions que Dependabot modifie dans le seul `pom.xml`.

## Goals / Non-Goals

**Goals:**
- Image immuable pour son UID d'exécution, avec notices tierces et métadonnées OCI.
- Contextes de build des deux Dockerfiles réduits à ce que le build lit.
- Sonde de santé disponible hors compose ; exécution compose sans capacités, sans élévation de privilèges, racine en lecture seule.
- Merge commit seul accepté par GitHub ; canal privé de signalement ; propriétaires déclarés des fichiers sensibles.
- Une montée Dependabot d'une dépendance Maven ne rend aucun contrat documentaire rouge.

**Non-Goals:**
- Exiger une revue de code owner (le modèle solo de `branch-governance.md` reste en vigueur).
- Version du projet codée en dur (`AUD-QUA-12`, sprint 9).
- Wrapper Maven : sa version et son ancre SHA-512 restent recopiées par conception (contrat `test-tool-integrity-anchors.sh` inchangé).
- Image distroless, signature d'image, changement de base.

## Decisions

**D1 — Root possède le code, l'UID 10001 ne possède que les données.** `COPY` sans `--chown` pour les JAR, `quarkus-app` et les scripts ; `chmod 0755` sur les répertoires et scripts, `0644` sur les fichiers ; `chown 10001:10001` limité à `/data/nexus` et `/workspace`. La normalisation CRLF (`sed` exigé par `build-docker-image.ps1`) reste exécutée en root avant `USER`. Alternative écartée : garder `--chown` et compter sur `read_only` seul, qui ne protège pas un `docker run` direct.

**D2 — Fichiers d'ignore par Dockerfile.** Un `.dockerignore` à la racine pour `Dockerfile` (exclusion de `.git`, des `target/`, des fichiers d'environnement locaux, des données et espaces de travail locaux, de `docs/project/audits/`, des worktrees d'outils) et un `Dockerfile.runtime.dockerignore` à côté de `Dockerfile.runtime`, livré dans le payload, en liste d'autorisation (`*` puis `lib/`, `rest/`, les deux scripts de `docker/` copiés par le Dockerfile, `LICENSE`, `THIRD_PARTY_NOTICES.txt` ; rien d'autre de `docker/`). Le fichier propre au Dockerfile est lu par BuildKit, builder par défaut de Docker Desktop et de la CI. Alternative écartée : liste d'autorisation à la racine pour `Dockerfile`, fragile à chaque nouveau module Maven.

**D3 — Notices et labels sans version codée en dur.** `Dockerfile` copie `LICENSE` et `target/licenses/THIRD_PARTY_NOTICES.txt` depuis l'étape `build` ; `Dockerfile.runtime` les prend dans son contexte (déjà présents à la racine d'installation ; ajoutés au staging de `docker-distribution.yml`). Labels : `source` et `licenses` statiques (`LicenseRef-NEXUS-Proprietary`, la licence n'étant pas SPDX), `revision` et `version` par `ARG` alimentés par la CI ; sans valeur fournie, le label est vide plutôt qu'inventé.

**D4 — Sonde dans l'image, durcissement dans le compose.** `HEALTHCHECK` appelle `/usr/local/bin/nexus-healthcheck` avec les mêmes intervalles que le compose, qui garde sa propre déclaration. Le compose ajoute `read_only: true`, `tmpfs: [/tmp]` (extraction des bibliothèques natives SQLite et JNA), `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`. Le smoke Docker existant (`docker-distribution.yml`, `test-docker-runtime.ps1`) qualifie le tout.

**D5 — Merge commit seul, réglé par le mainteneur.** Le ruleset passe `allowed_merge_methods` à `["merge"]` et le dépôt désactive squash et rebase ; ces réglages se font dans GitHub, hors PR. La PR met à jour `branch-governance.md` et le skill `nexus-pr-workflow`, et le contrat documentaire vérifie que la doc mentionne la restriction.

**D6 — CODEOWNERS déclaratif, SECURITY.md unique.** `.github/CODEOWNERS` désigne le mainteneur pour `.github/workflows/`, `scripts/release/`, `config/tool-integrity.properties`, `mvnw*`, `.mvn/` et `packaging/` ; la revue code owner n'est pas rendue obligatoire. `SECURITY.md` est un seul fichier : s'il existe déjà (section de traitement des alertes de `sprint-01-cloture-mesures`), ce changement le complète avec la politique de signalement privé, les versions prises en charge et le délai de réponse.

**D7 — La doc nomme la propriété, pas la version.** Les documents courants désignent la version Quarkus et SDK MCP par leur propriété du `pom.xml` (`quarkus.platform.version`, `mcp.sdk.version`) ; le contrat lit ces propriétés, exige leur présence et la mention de la propriété, et refuse tout littéral `Quarkus <x.y.z>` / `MCP SDK <x.y.z>` dans ces documents. Alternative écartée : comparer seulement `majeure.mineure`, qui casse encore à chaque mineure. Les ADR et documents datés ne sont pas concernés.

## Risks / Trade-offs

- [Une bibliothèque écrit sous `/opt/nexus` à l'exécution] → le smoke Docker échouerait ; `/tmp` en `tmpfs` couvre les extractions natives connues. À confirmer par le smoke de `docker-distribution.yml`.
- [Builder classique sans BuildKit] → `Dockerfile.runtime.dockerignore` est ignoré ; documenté dans la doc Docker, le compose officiel passe par BuildKit.
- [Image de repli construite depuis un staging incomplet] → le staging de `docker-distribution.yml` copie les notices, et le smoke vérifie leur présence dans l'image.
- [Merge via l'interface avant le réglage] → la PR documentaire précède la tâche mainteneur, mais le critère de sortie n'est atteint qu'après lecture du ruleset par `gh`.
- [Lecteur cherchant la version exacte dans la doc] → la doc renvoie au `pom.xml`, source unique.
