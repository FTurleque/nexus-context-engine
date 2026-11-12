# Proposition

## Why

L'assistant d'installation Windows refuse le caractère `^` dans `NEXUS_HOME`, le token REST, le dossier de dépôt Docker et l'URL Ollama. Or Windows autorise `^` dans un nom de profil, et les valeurs par défaut de `NEXUS_HOME` et du dossier de dépôt Docker dérivent du profil. Un utilisateur dont le profil contient `^` ne peut donc pas valider la page de configuration Docker, même en profil standard où la page Runtime est sautée : le message parle de « chemins générés » qu'il ne voit pas et ne peut pas corriger. En installation silencieuse, le refus passe par `MsgBox`, que `/SUPPRESSMSGBOXES` ne supprime pas. La vérification du 12/11/2026 classe ce constat en sévérité faible (`AUD-QUA-29`, sprint 15). Critère de sortie du sprint : un profil « a^b » est accepté en mode Docker.

Source : `docs/project/audits/2026-10-17/findings.json` (entrée `AUD-QUA-29`), `docs/project/audits/2026-10-17/etat-2026-11-12.md` (section `AUD-QUA-29`, « Angles morts »), `docs/project/audits/2026-10-17/sprints.md` (« S15 — Suites de la vérification du 12 novembre »).

## What Changes

- `AUD-QUA-29` (Faible) — `^` refusé à tort par l'assistant
  - [Existant vérifié] `ContainsUnsafeConfigChars` refuse retour chariot, saut de ligne, guillemet double et `^` (`scripts/release/harden-windows-installer-source.ps1:113-117`). La page Runtime l'applique à `NEXUS_HOME` et au token REST (`:161-168`). La page Docker l'applique à `NEXUS_HOME` (`RuntimePage.Values[0]`), au dossier de dépôt Docker (`DockerPage.Values[5]`) et à l'URL Ollama (`:223-230`), que le déploiement soit natif ou non.
  - [Existant vérifié] Les deux valeurs refusées viennent du profil : `NEXUS_HOME` vaut par défaut `GetEnv('USERPROFILE') + '\.nexus'` (`packaging/windows/nexus-installer.iss.template:920`), le dossier de dépôt Docker `GetEnv('USERPROFILE')` (`:972`). La page Runtime est sautée hors profil personnalisé (`:1008`) ; la page Docker est affichée dès que Docker est déployé (`:1012`).
  - [Existant vérifié] `^` est littéral dans les formats écrits. Dans `config\nexus-native.env.cmd`, chaque valeur est écrite entre guillemets, `set "NOM=valeur"` (`scripts/release/harden-windows-installer-source.ps1:243-251`, `scripts/release/harden-windows-rest-auth-source.ps1:15-45`), et les lanceurs désactivent l'expansion retardée (`scripts/release/build-windows-distribution.ps1:262`). Dans `docker\.env`, le dossier de dépôt, l'URL Ollama et le token passent par `DotEnvQuoted` (`scripts/release/harden-windows-installer-source.ps1:104-111`, `:280`, `:285-286`) : valeurs entre apostrophes, que Compose lit telles quelles. Relevé local du 12/11/2026 : `set "V=a^b"` rend `V=a^b` ; la vérification de l'audit rejoue la ligne `NEXUS_HOME` d'un profil « a^b » sans erreur en page 850 (`findings.json`, champ `verification`).
  - [Existant vérifié] Les refus de `NextButtonClick` passent par `MsgBox` (`packaging/windows/nexus-installer.iss.template:1105-1242`), comme l'avis de port remplacé (`NotifyPortSelection`, `:611-618`, appelée en `:1142` et `:1237`) et l'avis de port réservé (`:1232-1236`). Seul le refus du dossier d'installation passe par `SuppressibleMsgBox` (`:1092-1099`), forme que le smoke vérifie en installation silencieuse (`scripts/release/test-windows-installer.ps1:330-347`). [Écart] Le blocage d'une installation silencieuse Docker sur une boîte modale est déduit de la lecture, pas rejoué (`etat-2026-11-12.md`, « Angles morts ») ; le smoke ne déploie jamais Docker (`packaging/windows/nexus-installer.iss.template:131-134`).
  - [Exigence] L'assistant accepte `^` dans `NEXUS_HOME`, le token REST, le dossier de dépôt Docker et l'URL Ollama, l'écrit tel quel et refuse toujours retour à la ligne et guillemet double.
  - [Exigence] En installation silencieuse avec `/SUPPRESSMSGBOXES`, un refus ou un avis de l'assistant est journalisé sans boîte modale ; un refus interrompt l'installation.
  - [Décision] Retirer `^` de `ContainsUnsafeConfigChars`, plutôt que de ne valider `RuntimePage.Values[0]` que si `InstallNative()` : le même contrôle de la page Docker porte sur `DockerPage.Values[5]`, qui vaut aussi le profil, et un profil « a^b » resterait refusé (voir `design.md`, « Décisions »).
  - [Décision] Toutes les boîtes de `NextButtonClick` et de `NotifyPortSelection` passent à `SuppressibleMsgBox`, avis d'information compris : chacune bloquerait une installation silencieuse.
  - [Question] Préexistant, hors `AUD-QUA-29` : dans un dossier d'installation qui contient `^`, ce qui est le cas du dossier par défaut `{localappdata}\Programs\NEXUS` sous un tel profil, les lanceurs natifs ne chargent pas `config\nexus-native.env.cmd`. Faut-il ouvrir un constat séparé ? Voir `design.md`, « Questions ouvertes ».

## Type de changement

fonctionnel (validation de l'assistant d'installation Windows) : profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- `ContainsUnsafeConfigChars` et ses deux messages de refus (`scripts/release/harden-windows-installer-source.ps1:113-117`, `:165`, `:227`).
- Les boîtes de `NextButtonClick` et de `NotifyPortSelection` (`packaging/windows/nexus-installer.iss.template:611-618`, `:1084-1247`), et les ancres du script de durcissement qui en recopient le texte (`scripts/release/harden-windows-installer-source.ps1:151-231`).
- Les tests `scripts/release/test-windows-config-escaping.ps1` et `scripts/release/test-windows-installer.ps1` (contrat statique).

**Hors périmètre :**
- Les lanceurs natifs dans un dossier d'installation qui contient `^` (`scripts/release/build-windows-distribution.ps1:263`, `:271`, `:279`, `:287`) : défaut préexistant, objet de la [Question].
- Les boîtes affichées hors validation des pages : fermeture de Claude Desktop (`packaging/windows/nexus-installer.iss.template:1709`), fin d'installation (`:2170`, `:2389`), désinstallation (`:2502`).
- La garde `InstallNative()` sur `NEXUS_HOME` à la page Docker : sans `^`, le contrôle n'y refuse plus que retour à la ligne et guillemet double, absents d'un nom de profil Windows.
- Les fragments TOML et JSON des clients : ils ne contiennent que des chemins sous `{app}` (`packaging/windows/nexus-installer.iss.template:1393-1394`, `:1424-1425`), sans `NEXUS_HOME` ni dossier de dépôt.

## Capabilities

### New Capabilities

### Modified Capabilities
- `installer-client-configuration`: ajout de deux exigences : `^` accepté et écrit tel quel dans les valeurs de la configuration générée, refus et avis de l'assistant sans boîte modale en installation silencieuse.

## Impact

- Installateur : `scripts/release/harden-windows-installer-source.ps1` (`ContainsUnsafeConfigChars`, messages, ancres des validations), `packaging/windows/nexus-installer.iss.template` (`NotifyPortSelection`, `NextButtonClick`).
- Tests : `scripts/release/test-windows-config-escaping.ps1`, `scripts/release/test-windows-installer.ps1` ; workflow `Windows installer` (`.github/workflows/windows-installer.yml`, `windows-latest`), seul à exécuter ces tests.
- Documentation : `docs/developer/release-and-recovery.md` (section `OpenSpec installer-client-configuration`), `docs/user/windows-installation.md` (« Écran 4 — Configuration runtime », « Écran 8 — Configuration Docker »), `docs/developer/openspec-traceability.md` à l'implémentation.
- Audit : statut de `AUD-QUA-29` dans `docs/project/audits/2026-10-17/findings.json`.
- Aucun code Java, aucune migration SQLite, aucun ADR.

## Questions ouvertes

- [Question] Lanceurs natifs dans un dossier d'installation qui contient `^` : constat séparé ou extension de ce changement (voir `design.md`, « Questions ouvertes »).
