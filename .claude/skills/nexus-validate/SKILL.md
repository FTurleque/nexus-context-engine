---
name: nexus-validate
description: Procédure de validation d'une itération NEXUS avant commit, PR ou merge — build Maven complet via le wrapper vérifié, self-smoke du JAR, contrats documentaires et d'intégrité, tests ciblés. Utiliser quand on demande de vérifier, tester ou qualifier un changement.
argument-hint: "[quick|full]"
allowed-tools: Bash(./mvnw *) Bash(bash .claude/scripts/contracts.sh) PowerShell(.\mvnw.cmd *) PowerShell(.\scripts\self-smoke.ps1 *)
---

# Validation d'itération NEXUS

`AGENTS.md` impose, avant de considérer une itération validée : `mvn clean install` (via le wrapper) **puis** `scripts/self-smoke.ps1`. Un ancien run vert ne prouve rien pour un nouveau HEAD.

## Mode `quick` (boucle de développement)

1. Tests ciblés du module touché :
   ```bash
   ./mvnw -B -ntp -pl core test -Dtest=<Classe>[#méthode] -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
   ```
   Adaptateurs : `-pl adapters/rest-quarkus -am` (ou `mcp-java`, `assistant-clients`).
2. Contrats si doc, versions, wrapper ou workflows ont bougé : `bash .claude/scripts/contracts.sh`.
3. Si la sortie Maven est tronquée, lire `*/target/surefire-reports/TEST-*.xml` (`tests=`, `failures=`, `errors=`).

## Mode `full` (avant PR ou merge) — défaut

1. `bash .claude/scripts/contracts.sh` (intégrité Maven/JDT LS/Ollama, doc opérationnelle, audit final).
2. Build complet, en arrière-plan car il dure plusieurs minutes :
   - Windows : `.\mvnw.cmd -B -ntp clean install`
   - POSIX : `./mvnw -B -ntp clean install`
3. Self-smoke (13 étapes, doit finir par `SELF-SMOKE SUCCESS`) :
   ```powershell
   .\scripts\self-smoke.ps1
   ```
   Sortie volumineuse : ne retenir que les lignes `[n/13]`, `SELF-SMOKE` et les exceptions.
4. Rapporter précisément : commandes lancées, résultat de chacune, ce qui n'a pas été exécuté (workflows de qualification, benchmarks, Docker, Windows installer) et que la preuve applicable reste la CI exact-head.

## Pièges connus

- Ne jamais lancer `mvn` système : le wrapper vérifie l'archive Maven contre `config/tool-integrity.properties`.
- Sous Windows, `mvnw.cmd` doit garder ses CRLF ; un `sed -i` les retire (« Le système ne trouve pas le nom de fichier de commandes »).
- Le self-smoke vérifie que `AGENTS.md` est l'instruction retenue sous 180 tokens (étape 9) et que le skill `nexus-context-validation` est sélectionné sous 1200 tokens (étape 11) : un nouveau fichier d'instructions non exclu par `.nexusignore` (`CLAUDE.md`…) ou un skill trop proche de ces requêtes peut le faire échouer.
- Dans un worktree au chemin long, `target/` peut dépasser la limite de chemin Windows.
- Lancer les contrats depuis Git Bash (outil Bash) : depuis PowerShell, `bash` désigne souvent WSL, où la copie de travail CRLF fait échouer les contrats à tort (le script le détecte et s'arrête).
