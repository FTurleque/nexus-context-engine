[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($env:OS -ne 'Windows_NT') {
    throw 'Windows configuration escaping smoke is Windows-only.'
}

$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$hardener = Join-Path $PSScriptRoot 'harden-windows-installer-source.ps1'
$template = Join-Path $repo 'packaging\windows\nexus-installer.iss.template'
. $hardener
$hardened = Protect-NexusInstallerSource -Source ([IO.File]::ReadAllText($template))

foreach ($required in @(
    'function CmdEnvEscape(Value: String): String;',
    "StringChangeEx(Result, '%', '%%', True);",
    'function ContainsUnsafeConfigChars(Value: String): Boolean;',
    'function IsValidDockerImageReference(Value: String): Boolean;',
    'CmdEnvEscape(RuntimePage.Values[3])',
    'DotEnvQuoted(DockerToken)',
    'NEXUS_REST_EXPOSURE_MODE=loopback-forward',
    '# NEXUS_DOCKER_HOST_FORWARD_ADDRESS is derived by Compose from NEXUS_DOCKER_BIND_ADDRESS',
    'Le mode loopback-forward exige que',
    '"args": ["--enable-native-access=ALL-UNNAMED", "-jar"',
    'args = ["--enable-native-access=ALL-UNNAMED", "-jar"',
    ' --enable-native-access=ALL-UNNAMED -jar '
)) {
    if ($hardened.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "Generated installer hardening contract missing: $required"
    }
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

    Write-Host 'NEXUS Windows cmd configuration escaping and Docker forward/native-access contract PASS' -ForegroundColor Green
}
finally {
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
