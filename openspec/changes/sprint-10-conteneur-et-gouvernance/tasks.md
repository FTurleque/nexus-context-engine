# Tasks

## 1. Image immuable pour son UID d'exécution

- [ ] 1.1 `AUD-DEP-04` — Dans `packaging/docker/Dockerfile` et `Dockerfile.runtime`, retirer `--chown=10001:10001` des `COPY` vers `/opt/nexus` et `/usr/local/bin`, limiter le `chown` à `/data/nexus` et `/workspace`, appliquer `chmod` 0755 (répertoires, scripts) et 0644 (fichiers) ; ajouter au contrat `scripts/release/test-operational-doc-contracts.sh` le refus d'un `--chown` vers ces deux chemins ; vérifier par `bash .claude/scripts/contracts.sh`, puis `docker build --file packaging/docker/Dockerfile --tag nexus-s10 .` et `docker run --rm --entrypoint /bin/sh nexus-s10 -c 'test ! -w /opt/nexus/lib/nexus-cli.jar && test ! -w /usr/local/bin/nexus-entrypoint && test -w /data/nexus'`

## 2. Contexte de build filtré

- [ ] 2.1 `AUD-SEC-08` — Ajouter `.dockerignore` à la racine (`.git`, `**/target`, fichiers d'environnement locaux, données et espaces de travail locaux, `docs/audits/`, `.claude/worktrees/`) sans exclure une entrée lue par `./mvnw -B -DskipTests package` ; vérifier que le build de 1.1 passe et que `docker build --file packaging/docker/Dockerfile --target build --tag nexus-s10-build .` puis `docker run --rm --entrypoint /bin/sh nexus-s10-build -c 'test ! -e /workspace/.git'` réussit
- [ ] 2.2 `AUD-SEC-08` — Ajouter `packaging/docker/Dockerfile.runtime.dockerignore` en liste d'autorisation (D2), l'inclure dans le payload Windows (`scripts/release/build-windows-distribution.ps1`, `build-windows-installer.ps1`) et dans le staging de repli de `.github/workflows/docker-distribution.yml` ; vérifier par le job « Smoke installed-payload fallback image » du workflow `Docker distribution` sur la PR

## 3. Notices et métadonnées OCI

- [ ] 3.1 `AUD-DEP-08` — Copier `LICENSE` et `THIRD_PARTY_NOTICES.txt` dans `/opt/nexus/` depuis les deux Dockerfiles (étape `build` pour `Dockerfile`, contexte pour `Dockerfile.runtime`, staging de repli complété) ; vérifier par `docker run --rm --entrypoint /bin/sh nexus-s10 -c 'test -s /opt/nexus/LICENSE && test -s /opt/nexus/THIRD_PARTY_NOTICES.txt'`
- [ ] 3.2 `AUD-DEP-08` — Ajouter les labels `org.opencontainers.image.source`, `.licenses`, `.revision` et `.version` (D3), `revision` et `version` passés en `--build-arg` par `docker-distribution.yml` ; vérifier par `docker inspect --format '{{json .Config.Labels}}' nexus-s10` et par une assertion ajoutée au smoke du workflow `Docker distribution`

## 4. Sonde et durcissement d'exécution

- [ ] 4.1 `AUD-DEP-18` — Ajouter `HEALTHCHECK CMD ["/usr/local/bin/nexus-healthcheck"]` aux deux Dockerfiles avec les intervalles du compose ; vérifier par `docker inspect --format '{{json .Config.Healthcheck}}' nexus-s10` puis le smoke `docker-distribution.yml` (état `healthy`)
- [ ] 4.2 `AUD-DEP-18` — Dans `docker-compose.yml.template`, ajouter `read_only: true`, `tmpfs: [/tmp]`, `cap_drop: [ALL]` et `security_opt: [no-new-privileges:true]` ; étendre le contrat documentaire à ces quatre clés ; vérifier par `bash .claude/scripts/contracts.sh` et `pwsh -NoProfile -File scripts/release/test-docker-runtime.ps1` (sonde `UP`)
- [ ] 4.3 `AUD-DEP-04`, `AUD-DEP-18` — Décrire dans la doc Docker courante l'immuabilité de l'image, les fichiers d'ignore par Dockerfile et le durcissement compose ; vérifier par `bash .claude/scripts/contracts.sh`

## 5. Gouvernance du dépôt

- [ ] 5.1 `AUD-DEP-16` — Mettre à jour `docs/developer/branch-governance.md` (méthodes de merge du ruleset) et le skill `nexus-pr-workflow` ; ajouter l'assertion au contrat documentaire ; vérifier par `bash .claude/scripts/contracts.sh`
- [ ] 5.2 `AUD-DEP-16` (mainteneur, après fusion de 5.1) — Restreindre `allowed_merge_methods` à `["merge"]` dans le ruleset « Protect main & develop » et désactiver squash et rebase dans les réglages du dépôt ; vérifier par `gh api repos/FTurleque/nexus-context-engine/rulesets/20503835 --jq '.rules[]|select(.type=="pull_request").parameters.allowed_merge_methods'` (`["merge"]`) et `gh api repos/FTurleque/nexus-context-engine --jq '[.allow_squash_merge,.allow_rebase_merge]'` (`[false,false]`)
- [ ] 5.3 `AUD-DEP-17` — Créer `SECURITY.md` (ou compléter celui de `sprint-01-cloture-mesures`) : signalement privé GitHub, aucune issue publique, versions prises en charge, délai de réponse ; créer `.github/CODEOWNERS` (D6) ; vérifier par `bash .claude/scripts/contracts.sh` et `gh api repos/FTurleque/nexus-context-engine/community/profile --jq '.files|keys'`
- [ ] 5.4 `AUD-DEP-17` (mainteneur) — Activer le signalement privé de vulnérabilités ; vérifier par `gh api repos/FTurleque/nexus-context-engine/private-vulnerability-reporting` (`{"enabled":true}`)

## 6. Versions de dépendances hors de la documentation

- [ ] 6.1 `AUD-DEP-31` — Remplacer dans `docs/developer/README.md`, `docs/developer/mcp.md`, `docs/developer/rest-api.md`, `docs/developer/architecture-implementation.md` et les vues arc42 courantes les numéros Quarkus et SDK MCP par la référence à `quarkus.platform.version` et `mcp.sdk.version` du `pom.xml` (D7) ; faire lire `mcp.sdk.version` au contrat, exiger la mention des propriétés et refuser les littéraux ; vérifier par `bash .claude/scripts/contracts.sh`
- [ ] 6.2 `AUD-DEP-31` — Vérifier qu'une montée locale simulée (`quarkus.platform.version` et `mcp.sdk.version` modifiés dans une copie de travail jetable) laisse `bash scripts/release/test-operational-doc-contracts.sh` vert, puis restaurer le `pom.xml` ; vérifier par `git status --short pom.xml` vide

## 7. Clôture

- [ ] 7.1 Mettre à jour le statut des constats du sprint 10 dans `docs/audits/2026-10-17/findings.json` (fermés avec la PR ; `AUD-DEP-31` : symptôme fermé par #269, cause fermée par cette PR) ; vérifier par `/nx-validate`

## Workflow follow-up

- Une PR vers `develop` pour 1 à 4, 5.1, 5.3, 6 et 7 (skill `nexus-pr-workflow`), merge exact-head (`gh pr merge --merge --match-head-commit`) ; le workflow `Docker distribution` doit être vert sur la PR.
- Les tâches mainteneur 5.2 et 5.4 se font dans GitHub, hors PR, après la fusion ; le critère de sortie est vérifié par les commandes `gh` en lecture.
- Archiver le changement OpenSpec (`openspec archive sprint-10-conteneur-et-gouvernance`) une fois la PR fusionnée et les réglages vérifiés.
