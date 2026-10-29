---
paths:
  - "docs/**"
  - "README.md"
  - "AGENTS.md"
  - ".claude/CLAUDE.md"
---

# Documentation

- La documentation est rédigée en **français** ; identifiants de code, commandes et noms de fichiers restent en anglais.
- Trois familles (voir `docs/developer/README.md`) :
  1. **courante** (`docs/architecture.md`, `docs/roadmap.md`, `docs/developer/*`, `README.md`) : doit suivre l'exact head ;
  2. **datée** (`docs/audits/**`, rapports d'itération et de benchmark) : historique, ne pas réécrire ;
  3. **ADR** (`docs/architecture/adr/`, format Archgate, GEN-0057) : fichier `{PRÉFIXE}-{NNNN}-{slug}.md` copié de `gabarit/template.md`, en-tête YAML en ligne 1, ajouté à l'index `README.md` et à `arc42/09-decisions.md` ; append-only, ne jamais réécrire une décision acceptée ; la remplacer par un nouvel ADR et déplacer l'ancien dans `archive/` (skill `nexus-adr`). Jamais `archgate init` ni `archgate adr create`, jamais de fichier dans `.archgate/adrs/` hors `.gitkeep`.
- Plusieurs faits documentaires sont vérifiés par la CI (`scripts/release/test-operational-doc-contracts.sh`, `test-final-audit-contracts.sh`, `test-tool-integrity-anchors.sh`) : quand un fait change (version Quarkus/Maven, profil `content-vN`, bornes), mettre à jour la doc **et** le contrat, puis lancer `bash .claude/scripts/contracts.sh`.
- NEXUS indexe lui-même `AGENTS.md` et les skills : n'y écrire ni chemin local, ni secret, ni configuration opérationnelle (SEC-0033). Tout nouveau fichier d'instructions (`CLAUDE.md`, `GEMINI.md`…) concurrence `AGENTS.md` dans les contextes NEXUS : l'exclure par `.nexusignore` s'il ne vise que l'outillage, comme `.claude/CLAUDE.md`, puis lancer le self-smoke.
- Un ancien run vert n'est jamais une preuve pour un nouveau HEAD : ne pas écrire « validé » sans le run exact-head correspondant.
