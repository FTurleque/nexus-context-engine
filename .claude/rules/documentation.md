---
paths:
  - "docs/**"
  - "README.md"
  - "AGENTS.md"
  - ".claude/CLAUDE.md"
  - "openspec/**/*.md"
---

# Documentation

- **Français**, concise, factuelle, sans promesse de capacité non qualifiée ; identifiants de code, commandes et noms de fichiers restent en anglais.
- **État courant** : `docs/project/governance/STATUS.md` (livré, par renvois) et `docs/project/governance/ROADMAP.md` (travaux ouverts), avec `docs/architecture/overview.md`, `docs/developer/*` et `README.md`. Ces pages suivent l'exact head. Un fait chiffré (version, borne, seuil, profil `content-vN`) s'écrit une seule fois puis se référence.
- **Contrats CI** : plusieurs faits et chemins documentaires sont vérifiés littéralement (`scripts/release/test-operational-doc-contracts.sh`, `test-final-audit-contracts.sh`, `test-tool-integrity-anchors.sh`). Quand un fait change, mettre à jour la doc **et** le contrat, puis lancer `bash .claude/scripts/contracts.sh`.
- **Historique figé** : `docs/project/history/`, `docs/project/audits/` (audit en cours compris) et `docs/architecture/adr/archive/` conservent l'état de leur date (anciens SHA, anciens chemins, anciens nombres). Ne pas les « mettre à jour » ; un lien cassé ou une erreur factuelle se corrige, avec confirmation (hook `guard-edit.ps1`).
- **ADR** (`docs/architecture/adr/`, format Archgate, GEN-0057) : copier `gabarit/template.md` ; fichier `{PRÉFIXE}-{NNNN}-{slug}.md` (préfixe du domaine, numéro unique jamais renuméroté) ; en-tête YAML en ligne 1 ; ajouté à l'index de `docs/architecture/adr/README.md` et à `docs/architecture/arc42/09-decisions.md`. Une décision acceptée ne se réécrit pas : compléter sa mise en œuvre, consigner un écart dans « Écarts constatés », ou écrire l'ADR qui la remplace et déplacer l'ancien dans `archive/` (skill `nexus-adr`, commande `/nx-adr`). Jamais `archgate init` ni `archgate adr create`, jamais de fichier dans `.archgate/adrs/` hors `.gitkeep`.
- **Quand écrire un ADR** : seulement pour un choix entre options réelles qui contraint durablement plusieurs modules, une surface publique, un format persisté, un modèle de confiance ou la toolchain, et dont l'inversion coûte cher. Un état de jalon, une preuve datée, un plan, une spécification de comportement, une convention locale ou un correctif vont dans `docs/project/`, OpenSpec, `.claude/rules/` ou la PR.
- **Instructions indexées** : NEXUS indexe lui-même `AGENTS.md` et les skills : n'y écrire ni chemin local, ni secret, ni configuration opérationnelle (SEC-0033). Un nouveau fichier d'instructions (`CLAUDE.md`, `GEMINI.md`…) concurrence `AGENTS.md` dans les contextes NEXUS : l'exclure par `.nexusignore` s'il ne vise que l'outillage, puis lancer `scripts/self-smoke.ps1`.
- **Preuve** : un ancien run vert n'est jamais une preuve pour un nouveau HEAD ; ne pas écrire « validé » sans le run exact-head correspondant.
- **Liens** : relatifs, vérifiés (aucun lien cassé dans les fichiers modifiés). Références de code sous la forme `chemin:ligne`, relues au HEAD avant d'être citées.
- **Documentation utilisateur** (`docs/user/`) et **développeur** (`docs/developer/`) : une fonctionnalité qui change une sortie, une option, une borne ou un diagnostic met à jour la page correspondante dans la même PR.
- Mermaid pour les diagrammes.

## Organisation de `docs/`

```text
docs/
  README.md            aiguillage, rien d'autre
  user/                installer, configurer, lancer NEXUS
  developer/           construire, modifier, qualifier NEXUS ; contrats techniques courants
  architecture/        overview.md, dossier arc42, quality/, risks/
    adr/               registre unique des ADR (format Archgate) + gabarit/
  project/             piloter et auditer le projet
    governance/        STATUS.md, ROADMAP.md
    audits/            audit en cours <AAAA-MM-JJ>/, archive/<AAAA-MM-JJ>/
    history/           relevés figés d'itération, de benchmark et de qualification
    rapport-supervisor/  dossier local du superviseur de code, non suivi
```

`docs/README.md` est le **seul** fichier à la racine de `docs/`, et `user/`, `developer/`, `architecture/`, `project/` les **seuls** dossiers de premier niveau suivis par Git. Un nouveau dossier de premier niveau, ou un nouveau sous-dossier de `project/`, est une décision de structure : la demander à l'utilisateur, ne pas la prendre en passant.

### Où ranger un nouveau fichier

Se poser la question du **lecteur**, pas du sujet :

| Le lecteur veut… | Emplacement |
|---|---|
| installer, configurer, lancer une commande, dépanner | `docs/user/` |
| compiler, tester, qualifier, comprendre un module ou une surface (CLI, REST, MCP) | `docs/developer/` |
| comprendre l'architecture d'ensemble, un scénario qualité, un risque | `docs/architecture/` |
| retrouver ou écrire une décision structurante | `docs/architecture/adr/` |
| connaître l'état livré et les travaux ouverts | `docs/project/governance/` |
| lire un audit | `docs/project/audits/` |
| lire un résultat d'itération, de benchmark ou de qualification daté | `docs/project/history/` |

Un même sujet a souvent **deux pages** : `user/` dit comment s'en servir, `developer/` comment c'est construit. Écrire deux pages plutôt qu'une page mixte.

### TOUJOURS

- Ranger une page dans **un seul** emplacement ; ne jamais la dupliquer.
- Ajouter la page à l'index de son espace dans le même changement : `README.md` du dossier, et `docs/README.md` si elle répond à un besoin de premier niveau.
- Écrire les liens **relatifs** entre pages de `docs/` ; citer un chemin depuis la racine du dépôt (`docs/developer/cli.md`) partout ailleurs : scripts, tests, workflows, `.claude/`, OpenSpec.
- Garder une page de `docs/user/` lisible sans le dépôt : elle parle du produit installé, de ses commandes et de sa configuration.

### JAMAIS

- Jamais de fichier à la racine de `docs/` autre que `README.md` (`architecture.md`, `roadmap.md`, `mvp.md`, `index-provenance.md` n'y reviennent pas).
- Jamais de retour d'un ancien dossier de premier niveau : `docs/adr/`, `docs/audits/`, `docs/rapport-supervisor/`.
- Jamais de second registre d'ADR, ni de second dossier d'audits.
- Jamais de résultat daté (itération, benchmark, campagne de qualification) dans `docs/developer/` : il va dans `docs/project/history/`.
- Jamais de document versionné sous `docs/project/rapport-supervisor/` : c'est le dossier local du superviseur de code, fixé par `log_subdir` dans `.claude/supervisor.config.json`.

### Déplacer ou renommer un fichier de `docs/`

Un chemin de documentation est **épinglé** : les contrats `scripts/release/test-*-contracts.sh`, les configurations de corpus `scripts/config/*.json` et `scripts/measure-*.ps1`, les filtres `paths` des workflows, des tests (`RealSemanticSearchBenchmarkTest`), les hooks `.claude/hooks/*.ps1`, `.claude/supervisor.config.json`, les agents et skills, `AGENTS.md` (`@docs/...`) et OpenSpec le citent.

1. Déplacer avec `git mv`, jamais par suppression puis recréation.
2. Chercher l'ancien chemin dans **tout** le dépôt (`git grep -n "docs/<ancien>"`), sous ses écritures `docs/x` et `docs\x`.
3. Recalculer les liens relatifs des pages déplacées **et** de celles qui pointent vers elles, relevés figés compris.
4. Lancer `bash .claude/scripts/contracts.sh`, `pwsh -NoProfile -File .claude/hooks/tests/Test-Hooks.ps1`, `archgate check` et `scripts/self-smoke.ps1`.
5. Compléter la table de correspondance de `docs/project/README.md`.

Dans un relevé figé, les **liens** sont recalculés, la **prose** n'est pas réécrite : elle cite les chemins de sa date.
