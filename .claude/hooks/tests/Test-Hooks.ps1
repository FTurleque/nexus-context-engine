# Tests de non-régression des hooks Claude Code du projet.
# Usage : pwsh -NoProfile -File .claude/hooks/tests/Test-Hooks.ps1

$ErrorActionPreference = 'Stop'
$hooks = Split-Path -Parent $PSScriptRoot
$root = (Resolve-Path (Join-Path $hooks '../..')).Path
$env:CLAUDE_PROJECT_DIR = $root
$failures = 0

function Invoke-Hook([string] $script, [hashtable] $payload) {
    $json = $payload | ConvertTo-Json -Depth 5 -Compress
    $output = $json | pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $hooks $script)
    return ($output -join "`n")
}

function Get-Decision([string] $output) {
    if ([string]::IsNullOrWhiteSpace($output)) { return 'none' }
    return ($output | ConvertFrom-Json).hookSpecificOutput.permissionDecision
}

function Assert-Command([string] $command, [string] $expected) {
    $actual = Get-Decision (Invoke-Hook 'guard-command.ps1' @{ tool_name = 'Bash'; tool_input = @{ command = $command } })
    Assert-Equal "commande « $command »" $expected $actual
}

function Assert-Edit([string] $relativePath, [string] $expected) {
    $actual = Get-Decision (Invoke-Hook 'guard-edit.ps1' @{ tool_name = 'Edit'; tool_input = @{ file_path = (Join-Path $root $relativePath) } })
    Assert-Equal "édition $relativePath" $expected $actual
}

function Assert-Equal([string] $label, [string] $expected, [string] $actual) {
    if ($expected -ne $actual) {
        Write-Host "FAIL $label : attendu $expected, obtenu $actual" -ForegroundColor Red
        $script:failures++
    } else {
        Write-Host "ok   $label -> $actual"
    }
}

# guard-command
Assert-Command 'git push --force origin feature/x' 'deny'
Assert-Command 'git push -f origin feature/x' 'deny'
Assert-Command 'git push --force-with-lease origin feature/x' 'deny'
Assert-Command 'git push origin develop' 'deny'
Assert-Command 'git push origin HEAD:main' 'deny'
Assert-Command 'git push -u origin chore/develop-tooling' 'none'
Assert-Command 'git push origin sync/main-into-develop' 'none'
Assert-Command 'git commit --no-verify -m x' 'deny'
Assert-Command "git -c commit.gpgsign=false commit -m x" 'deny'
Assert-Command "git commit -F - <<'EOF'`nchore: x`n`nhooks blocking --no-verify and force-push`nEOF" 'none'
Assert-Command 'git rebase -i HEAD~3' 'deny'
Assert-Command 'gh pr merge 12 --merge' 'deny'
Assert-Command 'gh pr merge 12 --squash --match-head-commit abc' 'deny'
Assert-Command 'gh pr merge 12 --merge --match-head-commit abc' 'none'
Assert-Command 'mvn clean install' 'deny'
Assert-Command './mvnw -B clean install' 'none'
Assert-Command 'cd core && mvn test' 'deny'
Assert-Command "sed -i 's/a/b/' mvnw.cmd" 'deny'
Assert-Command "sed -i 's/a/b/' README.md" 'none'
Assert-Command 'git reset --hard origin/develop' 'ask'
Assert-Command 'git status --short' 'none'
Assert-Command "gh pr create --body 'refuse ``sed -i`` sur ``.cmd``/``.ps1`` et ``mvn`` hors wrapper'" 'none'
Assert-Command "`$body = @'`n- hooks : refus de sed -i sur mvnw.cmd et de git push --force`n'@`ngh pr create --body `$body" 'none'
Assert-Command 'git fetch origin; git push origin develop' 'deny'
Assert-Command 'git status | sed -i s/a/b/ x.ps1' 'deny'

# guard-edit
Assert-Edit 'core/src/main/resources/db/migration/V001__initial_schema.sql' 'deny'
Assert-Edit 'core/src/main/resources/db/migration/V999__future_migration.sql' 'none'
Assert-Edit 'docs/architecture/adr/DATA-0051-borner-les-lots-d-ecriture-sqlite.md' 'ask'
Assert-Edit 'docs/architecture/adr/GEN-0057-tenir-le-registre-d-adr-au-format-archgate.md' 'ask'
Assert-Edit 'docs/architecture/adr/GEN-9999-nouvelle-decision.md' 'none'
Assert-Edit 'docs/architecture/adr/archive/0000-adopter-les-adr-madr.md' 'ask'
Assert-Edit 'docs/architecture/adr/README.md' 'none'
Assert-Edit '.env' 'deny'
Assert-Edit 'config/tool-integrity.properties' 'ask'
Assert-Edit 'core/src/main/java/com/nexus/application/NexusApplication.java' 'none'

# post-edit-reminders
$pom = Invoke-Hook 'post-edit-reminders.ps1' @{ tool_name = 'Edit'; tool_input = @{ file_path = (Join-Path $root 'pom.xml') } }
Assert-Equal 'rappel pom.xml' 'True' ([string]($pom -match 'contracts\.sh'))
$adr = Invoke-Hook 'post-edit-reminders.ps1' @{ tool_name = 'Edit'; tool_input = @{ file_path = (Join-Path $root 'docs/architecture/adr/DATA-0051-borner-les-lots-d-ecriture-sqlite.md') } }
Assert-Equal 'rappel ADR' 'True' ([string]($adr -match 'archgate check'))
$plain = Invoke-Hook 'post-edit-reminders.ps1' @{ tool_name = 'Edit'; tool_input = @{ file_path = (Join-Path $root 'core/src/main/java/com/nexus/cli/NexusCli.java') } }
Assert-Equal 'aucun rappel hors zones sensibles' 'True' ([string][string]::IsNullOrWhiteSpace($plain))

# session-context
$session = Invoke-Hook 'session-context.ps1' @{ hook_event_name = 'SessionStart' }
Assert-Equal 'contexte de session' 'True' ([string]($session -match 'branche courante'))

if ($failures -gt 0) {
    Write-Host "$failures échec(s)" -ForegroundColor Red
    exit 1
}
Write-Host 'Tous les tests de hooks passent.'
