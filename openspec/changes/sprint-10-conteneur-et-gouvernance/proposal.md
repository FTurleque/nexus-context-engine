# Proposal

## Why

L'image Docker de NEXUS tourne sans privilèges, mais ses binaires et ses scripts d'entrée appartiennent à l'UID d'exécution : elle n'est pas immuable pour son propre processus. Le contexte de build n'est pas filtré, l'image publiée ne porte ni notices de licence ni métadonnées OCI, et le durcissement d'exécution (sonde intégrée, capacités, `no-new-privileges`, système de fichiers en lecture seule) n'est pas déclaré.

Côté dépôt, la politique « merge commit uniquement » n'est tenue que par un hook local : le ruleset GitHub et les réglages du dépôt acceptent encore squash et rebase. Le dépôt public n'offre aucun canal privé de signalement de vulnérabilité ni de propriétaire déclaré pour les fichiers sensibles. Enfin, la documentation courante recopie des numéros de version de dépendances que le contrat documentaire compare au `pom.xml` : chaque montée Dependabot de ces dépendances rend sa PR rouge.

Ce changement porte le sprint 10 de l'audit du 17/10/2026 (`docs/audits/2026-10-17/sprints.md`).

## What Changes

Constats actifs, dans l'ordre du sprint :

- `AUD-DEP-04` — `/opt/nexus` et `/usr/local/bin` appartiennent à root dans `Dockerfile` et `Dockerfile.runtime` ; seuls `/data/nexus` et `/workspace` sont attribués à l'UID 10001.
- `AUD-SEC-08` — un `.dockerignore` à la racine limite le contexte de build du `Dockerfile` aux entrées nécessaires au build Maven.
- `AUD-DEP-08` — l'image embarque `LICENSE` et `THIRD_PARTY_NOTICES.txt` sous `/opt/nexus/` et porte les labels `org.opencontainers.image.{source,revision,version,licenses}`.
- `AUD-DEP-18` — instruction `HEALTHCHECK` dans les deux Dockerfiles ; le compose déclare `read_only: true` avec `tmpfs`, `cap_drop: [ALL]` et `security_opt: [no-new-privileges:true]`.
- `AUD-DEP-16` — tâche du mainteneur : le ruleset « Protect main & develop » et les réglages du dépôt n'autorisent que le merge commit ; la documentation de gouvernance le décrit.
- `AUD-DEP-17` — `SECURITY.md` (canal de signalement privé GitHub) et `.github/CODEOWNERS` sur les workflows, les scripts de release et les ancres d'intégrité ; activation du signalement privé par le mainteneur.
- `AUD-DEP-31` (reformulé) — la documentation courante ne recopie plus les versions de dépendances Maven que Dependabot fait évoluer ; le contrat documentaire lit `pom.xml` et vérifie la référence à la propriété au lieu d'un littéral.

Constat déjà fermé :

- `AUD-DEP-31`, symptôme d'origine (PR #262 rouge sur `Quarkus      3.40.1.1`) : fermé par #269, qui aligne la documentation sur Quarkus 3.40.1.1 et le SDK MCP 2.1.0. La cause reste ouverte (voir ci-dessus) : la documentation recopie toujours la version, et la ligne `MCP SDK     2.1.0` du contrat est un littéral.

## Capabilities

### New Capabilities
- `container-image-hardening`: immuabilité de l'image pour son UID d'exécution, contexte de build filtré, notices et métadonnées OCI, sonde intégrée et durcissement d'exécution du compose.
- `repository-governance`: méthodes de merge autorisées et propriétaires déclarés des fichiers sensibles.
- `repository-security-settings`: canal privé de signalement des vulnérabilités (capability partagée avec `sprint-01-cloture-mesures`, qui y ajoute la surveillance Dependabot et secret scanning).
- `operational-documentation-contracts`: alignement de la documentation courante sur le code et le `pom.xml`, vérifié par `scripts/release/test-operational-doc-contracts.sh` (capability partagée avec `sprint-11-finitions-et-micro-optimisations`).

### Modified Capabilities

(aucune : `openspec/specs/` est vide)

## Impact

- Conteneur : `packaging/docker/Dockerfile`, `packaging/docker/Dockerfile.runtime`, `packaging/docker/docker-compose.yml.template`, nouveau `.dockerignore` ; staging de l'image de repli dans `.github/workflows/docker-distribution.yml` et payload Windows (`scripts/release/build-windows-distribution.ps1`) si les notices n'y sont pas déjà au bon chemin.
- Gouvernance : nouveaux `SECURITY.md` (ou complément de celui créé par `sprint-01-cloture-mesures`) et `.github/CODEOWNERS` ; `docs/developer/branch-governance.md`, skill `nexus-pr-workflow`.
- Contrats : `scripts/release/test-operational-doc-contracts.sh`, `docs/developer/README.md`, `docs/developer/mcp.md`, `docs/developer/rest-api.md`, `docs/developer/architecture-implementation.md`, vues arc42 concernées.
- Réglages GitHub (mainteneur, hors PR) : ruleset 20503835, `allow_squash_merge`, `allow_rebase_merge`, signalement privé de vulnérabilités.
- Aucun code Java, aucune migration SQLite.
- Hors périmètre : version du projet codée en dur dans la CI et le tag d'image du compose (`AUD-QUA-12`, sprint 9), wrapper Maven et son ancre SHA-512 (versionnés par conception).
