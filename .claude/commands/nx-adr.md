---
description: Évalue si une décision mérite un ADR et rédige le brouillon au format du projet
argument-hint: "<décision ou sujet>"
allowed-tools: Bash(git log *) Bash(ls *) Bash(grep *) Bash(sort *) Bash(tail *) Bash(true)
---

Derniers ADR :

!`ls docs/architecture/adr | grep -E '^[0-9]{4}-' | sort | tail -5`

Sujet : $ARGUMENTS

Applique le skill `nexus-adr`. Commence par trancher « ADR nécessaire » ou « aucun ADR nécessaire » avec la justification. Si un ADR est justifié, rédige-le au statut `Proposé`, mets à jour l'index et montre-moi le résultat ; ne passe jamais un ADR en `Accepté` sans ma décision.
