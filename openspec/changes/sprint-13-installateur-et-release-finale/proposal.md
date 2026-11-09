# Proposition

## Why

L'installateur Windows réécrit les configurations MCP des clients IA (Claude Desktop, Copilot JetBrains) sans les lire en UTF-8, sans s'arrêter sur un JSON invalide et sans sauvegarde : sur un poste dont un chemin contient un accent, connecter ou déconnecter NEXUS corrompt les autres serveurs MCP déclarés, et un fichier invalide est remplacé par `null` avec un code de sortie 0. L'audit du 17/10/2026, revérifié le 09/11, classe ce constat en sévérité élevée (`AUD-QUA-22`). Il doit être corrigé avant la release finale v0.2.0, comme les quatre constats de release qui l'accompagnent : tags GHCR d'août qui bloqueront le préflight immuable, labels OCI vides dans l'image construite par l'installateur, échappement PowerShell incomplet et liens cassés dans un changement OpenSpec archivé.

Source : `docs/project/audits/2026-10-17/findings.json` (sprint 13), `etat-2026-11-09.md`.

## What Changes

- `AUD-QUA-22` (Élevée)
  - [Existant vérifié] Les scripts générés lisent la configuration par `Get-Content $cfg -Raw | ConvertFrom-Json` sans `-Encoding` (`packaging/windows/nexus-installer.iss.template:1604`, `:1681`, `:1792`, `:1805`) et l'écrivent par `Save-CleanJson`, qui appelle `[System.IO.File]::WriteAllText` sans copie préalable (`:1453-1459`). Aucun `$ErrorActionPreference = 'Stop'` dans ces scripts (seule occurrence : `:528`, autre script). Ils s'exécutent sous Windows PowerShell 5.1 (`:1542`).
  - [Existant vérifié] Le rejeu du 09/11 sous Windows PowerShell 5.1.19041 corrompt `C:\Users\Hélène\outil.exe` et réécrit un JSON invalide en 2 octets (`findings.json`, champ `verification`).
  - [Existant vérifié] Les tests de l'installateur n'écrivent que de l'ASCII (`scripts/release/test-windows-installer.ps1:203-205`, `-Encoding ascii`) et ne testent ni JSON invalide ni chemin non ASCII.
  - [Exigence] Lecture UTF-8 explicite, arrêt sur erreur, refus d'écrire si le JSON ne se lit pas, sauvegarde avant écriture, écriture atomique, pour la connexion comme pour la désinstallation.
- `AUD-QUA-24`
  - [Existant vérifié] `PowerShellSingleQuote` ne double que l'apostrophe ASCII (`packaging/windows/nexus-installer.iss.template:388-392`) ; la fonction protège `{app}\nexus-mcp.cmd` et le nom de conteneur dans les scripts générés (`:1473`, `:1476`, `:1483`, `:1487`). PowerShell accepte aussi U+2018 à U+201B comme délimiteurs de chaîne simple.
  - [Exigence] Un chemin d'installation contenant `’` produit un script syntaxiquement valide qui conserve le chemin intact.
- `AUD-DEP-35`
  - [Existant vérifié] `Dockerfile.runtime` déclare `NEXUS_IMAGE_REVISION` et `NEXUS_IMAGE_VERSION` vides par défaut (`packaging/docker/Dockerfile.runtime:7-8`) ; le compose construit l'image (`pull_policy: build`) sans `args:` (`packaging/docker/docker-compose.yml.template:5-8`). Seul `.github/workflows/docker-distribution.yml:120-124`, `:354-355` passe les `--build-arg`.
  - [Exigence] L'image construite sur le poste par le compose généré porte la version de NEXUS dans `org.opencontainers.image.version`.
- `AUD-DEP-34`
  - [Existant vérifié] `ghcr.io/fturleque/nexus-context-engine:0.2.0` et `:latest` désignent le digest `sha256:fbe2256d…`, attesté par `docker-distribution.yml@refs/heads/main` (run du 09/08/2026), distinct de `:0.2.0-rc.3` (contrôle du 09/11 à 22 h, `findings.json`). `scripts/release/ghcr-immutable-preflight.sh:94` refuse un tag de version qui désigne un autre contenu.
  - [Existant vérifié] `docs/user/docker-installation.md:455-460` affirme que `docker-distribution.yml` publie `<version>` et `latest` sur `main` ; plus aucun de ses jobs ne pousse sur GHCR, seul `.github/workflows/release.yml:306-335` le fait.
  - [Proposition] Tâche mainteneur : retirer ou réassigner les deux tags avant la promotion de `main`, puis vérifier par `docker buildx imagetools inspect`.
  - [Exigence] La documentation de publication GHCR nomme le workflow de release.
- `AUD-QUA-25`
  - [Existant vérifié] `openspec/changes/archive/2026-11-09-preciser-bornes-redaction-recence-git/design.md` porte 9 liens `../../../docs/developer/…` qui résolvent vers `openspec/docs/…`, absent : le design a été écrit à `openspec/changes/<nom>/` puis déplacé d'un niveau par l'archivage.
  - [Écart] Le constat affirme aussi que les ancres `openspec-git-recency-diagnostics` et `openspec-secret-redaction` n'existent pas. Elles existent : elles dérivent des titres `## OpenSpec git-recency-diagnostics` (`docs/developer/git-context.md:120`) et `## OpenSpec secret-redaction` (`docs/developer/secret-redaction.md:64`). Seuls les chemins sont cassés.
  - [Existant vérifié] `scripts/release/openspec_doc_contracts.py:298-301` ne contrôle les liens que des `design.md` actifs et exclut `archive/`.
  - [Exigence] Le contrat OpenSpec contrôle les liens relatifs des changements archivés.

## Type de changement

fonctionnel (installateur, image locale) et outillage (contrat OpenSpec, gouvernance des tags GHCR) : profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- Les scripts PowerShell générés par `packaging/windows/nexus-installer.iss.template` qui lisent ou écrivent `claude_desktop_config.json` et le `mcp.json` de Copilot JetBrains (connexion et désinstallation).
- L'échappement de `PowerShellSingleQuote`.
- Les arguments de build du compose généré.
- L'état des tags GHCR `:0.2.0` et `:latest`, et la page `docs/user/docker-installation.md`.
- Les liens du changement archivé `2026-11-09-preciser-bornes-redaction-recence-git` et le contrôle des liens d'archive.

**Hors périmètre :**
- Les fichiers TOML de Codex (`:1750-1757`, `:1829-1835`) : ils sont déjà lus et écrits en UTF-8 explicite ; seule la sauvegarde pourrait leur être étendue, ce que ce changement ne fait pas.
- La promotion `develop` → `main` et la publication de la release v0.2.0 elles-mêmes (skill `nexus-pr-workflow`, `/nx-release`).
- Une refonte de l'installateur (passage des valeurs par fichier JSON au lieu de littéraux) : écartée au profit d'un échappement complet.
- La prose des archives OpenSpec : seuls les liens se corrigent (`.claude/rules/documentation.md`, historique figé).

## Capabilities

### New Capabilities
- `installer-client-configuration`: écriture sûre des configurations MCP des clients IA par l'installateur Windows (encodage, refus sur JSON invalide, sauvegarde, atomicité) et échappement des valeurs injectées dans les scripts générés.
- `openspec-documentation-contracts`: contrôles statiques de la documentation OpenSpec, dont l'intégrité des liens relatifs des changements actifs et archivés.

### Modified Capabilities
- `container-image-hardening`: ajout d'une exigence sur les métadonnées de l'image construite localement par le compose.
- `release-pipeline`: ajout d'une exigence sur l'origine des tags GHCR de version et `latest`.
- `operational-documentation-contracts`: ajout d'une exigence sur la page de publication GHCR.

## Impact

- Installateur : `packaging/windows/nexus-installer.iss.template` (fonctions `PowerShellSingleQuote`, `Save-CleanJson`, scripts `connect-claude-desktop.ps1`, connexion et déconnexion Copilot JetBrains, `disconnect-claude-desktop.ps1`), `scripts/release/harden-windows-installer-source.ps1` si une chaîne contrôlée change.
- Compose : `packaging/docker/docker-compose.yml.template`, `.env` écrit par l'installateur.
- Tests : `scripts/release/test-windows-installer.ps1`, `scripts/release/test-windows-config-escaping.ps1`, `scripts/release/openspec_doc_contracts.py`.
- Documentation : `docs/user/docker-installation.md`, `docs/user/windows-installation.md` (sauvegarde des configurations), `docs/developer/release-and-recovery.md`, `docs/developer/openspec.md`, `docs/developer/ci-and-supply-chain.md`, `docs/developer/immutable-release-publishing.md`, `docs/developer/openspec-traceability.md`.
- Registre : versions de paquet GHCR (tâche mainteneur, hors PR).
- Aucun code Java, aucune migration SQLite.

## Questions ouvertes

- [Question] `AUD-DEP-34` : faut-il supprimer les versions de paquet GHCR `:0.2.0` et `:latest` d'août (la release v0.2.0 publie alors sous `0.2.0`), ou laisser `0.2.0` à l'image d'août et publier la release finale sous `0.2.1` ? La première option réutilise un tag déjà tiré par des utilisateurs ; la seconde décale la version annoncée par la roadmap. Les tâches vérifient l'état final dans les deux cas.
