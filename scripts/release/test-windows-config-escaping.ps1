[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($env:OS -ne 'Windows_NT') {
    throw 'Windows configuration escaping smoke is Windows-only.'
}

$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$hardener = Join-Path $PSScriptRoot 'harden-windows-installer-source.ps1'
$restAuthHardener = Join-Path $PSScriptRoot 'harden-windows-rest-auth-source.ps1'
$template = Join-Path $repo 'packaging\windows\nexus-installer.iss.template'
. $hardener
. $restAuthHardener
$hardened = Protect-NexusInstallerSource -Source ([IO.File]::ReadAllText($template))
$hardened = Protect-NexusNativeRestAuthSource -Source $hardened

foreach ($required in @(
    'function CmdEnvEscape(Value: String): String;',
    "StringChangeEx(Result, '%', '%%', True);",
    'function ContainsUnsafeConfigChars(Value: String): Boolean;',
    'function IsValidDockerImageReference(Value: String): Boolean;',
    'CmdEnvEscape(NativeToken)',
    'NativeToken := GenerateLocalToken();',
    'RuntimePage.Values[3] := NativeToken;',
    'DotEnvQuoted(DockerToken)',
    'NEXUS_REST_EXPOSURE_MODE=loopback-forward',
    '# NEXUS_DOCKER_HOST_FORWARD_ADDRESS is derived by Compose from NEXUS_DOCKER_BIND_ADDRESS',
    'Le mode loopback-forward exige que',
    '"args": ["--enable-native-access=ALL-UNNAMED", "-jar"',
    'args = ["--enable-native-access=ALL-UNNAMED", "-jar"',
    "PowerShellSingleQuote(ExpandConstant('{app}\nexus-mcp.cmd'))",
    'function Read-ClientJson([string]$Path) {',
    '[System.IO.File]::ReadAllText($Path, [System.Text.UTF8Encoding]::new($false))',
    '[System.IO.File]::Replace($tmp, $Path, $candidate)',
    'function Add-NexusServerEntry([string[]]$Paths, [string]$Section, [object]$Entry) {',
    'function Remove-NexusServerEntry([string[]]$Paths, [string]$Section) {'
)) {
    if ($hardened.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "Generated installer hardening contract missing: $required"
    }
}

if ($hardened.IndexOf(
        'set "NEXUS_REST_API_TOKEN='' + CmdEnvEscape(RuntimePage.Values[3]) + ''"',
        [StringComparison]::Ordinal) -ge 0) {
    throw 'Native REST configuration still writes a blank user token directly.'
}

# PowerShellSingleQuote: replay its StringChangeEx calls (Pascal literals 'x' and #$hhhh) on an
# install folder with a typographic apostrophe, then parse the generated MCP entry line.
$quoteMatch = [regex]::Match($hardened, '(?s)function PowerShellSingleQuote\(Value: String\): String;\s*begin(?<body>.*?)\bend;')
if (-not $quoteMatch.Success) { throw 'PowerShellSingleQuote not found in the hardened installer source.' }
$pascalLiteral = "(?:'(?:[^']|'')*'|#\$[0-9A-Fa-f]+|#[0-9]+)+"
function ConvertFrom-PascalLiteral([string]$Expression) {
    $builder = New-Object System.Text.StringBuilder
    foreach ($token in [regex]::Matches($Expression, "'(?:[^']|'')*'|#\$[0-9A-Fa-f]+|#[0-9]+")) {
        $value = $token.Value
        if ($value.StartsWith("'")) { [void]$builder.Append($value.Substring(1, $value.Length - 2).Replace("''", "'")) }
        elseif ($value.StartsWith('#$')) { [void]$builder.Append([char][Convert]::ToInt32($value.Substring(2), 16)) }
        else { [void]$builder.Append([char][int]$value.Substring(1)) }
    }
    return $builder.ToString()
}
$quoteRules = @([regex]::Matches($quoteMatch.Groups['body'].Value,
    "StringChangeEx\(Result,\s*(?<from>$pascalLiteral),\s*(?<to>$pascalLiteral),\s*True\);") | ForEach-Object {
    [PSCustomObject]@{ From = (ConvertFrom-PascalLiteral $_.Groups['from'].Value); To = (ConvertFrom-PascalLiteral $_.Groups['to'].Value) }
})
function Invoke-PowerShellSingleQuote([string]$Value, [object[]]$Rules) {
    foreach ($rule in $Rules) { $Value = $Value.Replace($rule.From, $rule.To) }
    return $Value
}
function Test-GeneratedMcpEntry([string]$Launcher, [object[]]$Rules) {
    $line = "`$e = [PSCustomObject]@{ command = 'cmd.exe'; args = @('/d', '/s', '/c', '" +
        (Invoke-PowerShellSingleQuote $Launcher $Rules) + "') }"
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($line, [ref]$tokens, [ref]$errors)
    if ($errors.Count -gt 0) { return $false }
    $strings = $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.StringConstantExpressionAst] }, $true)
    return @($strings | Where-Object { $_.Value -ceq $Launcher }).Count -eq 1
}
foreach ($quote in @(0x2018, 0x2019, 0x201A, 0x201B)) {
    if (@($quoteRules | Where-Object { $_.From -eq [string][char]$quote -and $_.To -eq ([string][char]$quote * 2) }).Count -ne 1) {
        throw ('PowerShellSingleQuote must double U+{0:X4}.' -f $quote)
    }
}
$typographic = 'C:\Outils\Jean' + [char]0x2019 + 's NEXUS\nexus-mcp.cmd'
if (-not (Test-GeneratedMcpEntry $typographic $quoteRules)) {
    throw "Generated MCP entry for an install folder with U+2019 does not parse back to $typographic"
}
foreach ($quote in @(0x0027, 0x2018, 0x201A, 0x201B)) {
    $launcher = 'C:\Outils\Q' + [char]$quote + 'Q\nexus-mcp.cmd'
    if (-not (Test-GeneratedMcpEntry $launcher $quoteRules)) { throw ('Generated MCP entry does not round-trip U+{0:X4}.' -f $quote) }
}
# Negative control: doubling only the ASCII apostrophe breaks the same line.
if (Test-GeneratedMcpEntry $typographic @($quoteRules | Where-Object { $_.From -eq "'" })) {
    throw 'PowerShell single-quote probe is vacuous: ASCII-only doubling must fail on U+2019.'
}

# Execute the same cmd.exe representation emitted by CmdEnvEscape. Start cmd.exe
# with delayed expansion ON deliberately: the launcher must turn it OFF before
# sourcing the generated environment file, otherwise !name! would be corrupted.
$root = Join-Path $repo 'target\windows-config-escaping-smoke'
Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $root | Out-Null
try {
    $expected = 'percent=%PATH%; delayed=!NEXUS_DELAYED!; amp=&; pipe=|; dollar=$value; tick=`value; spaces=two words'
    $escaped = $expected.Replace('%', '%%')
    $envFile = Join-Path $root 'probe.env.cmd'
    $probe = Join-Path $root 'probe.cmd'
    $actualFile = Join-Path $root 'actual.txt'

    [IO.File]::WriteAllText(
        $envFile,
        "@echo off`r`nset `"NEXUS_TEST_VALUE=$escaped`"`r`n",
        [Text.Encoding]::ASCII)
    [IO.File]::WriteAllText(
        $probe,
        "@echo off`r`nsetlocal DisableDelayedExpansion`r`ncall `"%~dp0probe.env.cmd`"`r`nset NEXUS_TEST_VALUE>`"%~dp0actual.txt`"`r`n",
        [Text.Encoding]::ASCII)

    $process = Start-Process -FilePath $env:ComSpec -ArgumentList @('/D','/V:ON','/S','/C',"`"$probe`"") -Wait -PassThru -NoNewWindow
    if ($process.ExitCode -ne 0) {
        throw "cmd.exe escaping probe failed with exit code $($process.ExitCode)"
    }
    $line = ([IO.File]::ReadAllText($actualFile)).TrimEnd("`r", "`n")
    $prefix = 'NEXUS_TEST_VALUE='
    if (-not $line.StartsWith($prefix, [StringComparison]::Ordinal)) {
        throw "cmd.exe escaping probe produced an unexpected SET line: $line"
    }
    $actual = $line.Substring($prefix.Length)
    if ($actual -cne $expected) {
        throw "cmd.exe escaping round-trip mismatch.`nExpected: $expected`nActual:   $actual"
    }

    Write-Host 'NEXUS Windows cmd and PowerShell escaping, client JSON helper, REST auth, Docker forward and native-access contract PASS' -ForegroundColor Green
}
finally {
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
