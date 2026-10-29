---
description: Évalue si une décision mérite un ADR et rédige le brouillon au format Archgate du projet
argument-hint: "<décision ou sujet>"
allowed-tools: Bash(git log *) Bash(ls *) Bash(grep *) Bash(sort *) Bash(tail *) Bash(archgate check*) Bash(archgate adr list*) Bash(true)
---

Derniers ADR du registre (le numéro suivant se prend dans l'index `docs/architecture/adr/README.md`, archive comprise) :

!`ls docs/architecture/adr | grep -E '^[A-Z]+-[0-9]{4}-.+\.md$' | sort -t- -k2,2 | tail -5`

Sujet : $ARGUMENTS

Applique le skill `nexus-adr` (convention GEN-0057 : `{PRÉFIXE}-{NNNN}-{slug}.md` à partir de `docs/architecture/adr/gabarit/template.md`). Commence par trancher « ADR nécessaire » ou « aucun ADR nécessaire » avec la justification. Si un ADR est justifié, rédige-le au statut `Proposed`, mets à jour l'index `README.md` et `docs/architecture/arc42/09-decisions.md`, vérifie `archgate check` et `archgate adr list`, et montre-moi le résultat ; ne passe jamais un ADR en `Accepted` sans ma décision. N'utilise jamais `archgate adr create`.
