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
    'function Remove-NexusServerEntry([string[]]$Paths, [string]$Section) {',
    'procedure SaveUtf8TextFile(Path: String; Text: String);',
    'if not SaveStringsToUTF8FileWithoutBOM(Path, Lines, False) then',
    'function CmdEnvPathValue(Value: String): String;',
    'function CmdUtf8Frame(Body: String): String;',
    "Content := '@echo off' + #13#10 + CmdUtf8Frame(",
    'CmdEnvPathValue(RuntimePage.Values[0])',
    "SaveUtf8TextFile(ExpandConstant('{app}\config\nexus-native.env.cmd'), Content);"
)) {
    if ($hardened.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "Generated installer hardening contract missing: $required"
    }
}

# Generated configuration files are written in UTF-8 without BOM: SaveStringToFile converts to
# the ANSI code page, which alters an accented profile or install folder.
$ansiConfigWrites = @([regex]::Matches($hardened,
    "SaveStringToFile\([^;\r\n]*?(\.env|\.toml|\.json|nexus-native\.env\.cmd)'\)?\s*,") | ForEach-Object { $_.Value })
if ($ansiConfigWrites.Count -gt 0) {
    throw "Generated configuration files must be written by SaveUtf8TextFile: $($ansiConfigWrites -join '; ')"
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

    # config\nexus-native.env.cmd: replay CmdEnvPathValue and CmdUtf8Frame from the hardened
    # source, write the file in UTF-8 without BOM as SaveUtf8TextFile does, and call it from a
    # launcher-shaped probe in a console set to code page 850, visible then hidden (CreateNoWindow).
    $nonAsciiMatch = [regex]::Match($hardened, '(?s)function HasNonAsciiChar\(Value: String\): Boolean;.*?\nend;')
    if (-not $nonAsciiMatch.Success -or $nonAsciiMatch.Value.IndexOf('if Ord(Value[I]) > 127 then', [StringComparison]::Ordinal) -lt 0) {
        throw 'HasNonAsciiChar must detect a character above #127.'
    }
    $framePattern = '(?s)function CmdUtf8Frame\(Body: String\): String;\s*begin\s*if HasNonAsciiChar\(Body\) then\s*' +
        'Result := (?<head>.*?)\s*\+\s*Body\s*\+\s*(?<tail>.*?)\s*\r?\n\s*else\s+Result := Body;'
    $frameMatch = [regex]::Match($hardened, $framePattern)
    if (-not $frameMatch.Success) { throw 'CmdUtf8Frame must frame exactly the content that keeps a non-ASCII character.' }
    $frameHead = ConvertFrom-PascalLiteral $frameMatch.Groups['head'].Value
    $frameTail = ConvertFrom-PascalLiteral $frameMatch.Groups['tail'].Value
    $pathMatch = [regex]::Match($hardened, '(?s)function CmdEnvPathValue\(Value: String\): String;.*?\nend;')
    if (-not $pathMatch.Success) { throw 'CmdEnvPathValue not found in the hardened installer source.' }
    $pathVariables = @([regex]::Matches($pathMatch.Value, "Names\[\d\] := '(?<name>\w+)';") |
        ForEach-Object { $_.Groups['name'].Value })
    if (($pathVariables -join ',') -ne 'LOCALAPPDATA,USERPROFILE') {
        throw "CmdEnvPathValue must reference LOCALAPPDATA and USERPROFILE, found: $($pathVariables -join ',')"
    }
    foreach ($pathRule in @(
        'Result := CmdEnvEscape(Value);',
        'if not HasNonAsciiChar(Value) then exit;',
        'Prefix := RemoveBackslashUnlessRoot(GetEnv(Names[I]));',
        '(Length(Prefix) > Length(Best))',
        'CompareText(Copy(Value, 1, Length(Prefix)), Prefix) = 0',
        "((Length(Value) = Length(Prefix)) or (Value[Length(Prefix) + 1] = '\') or",
        "(Prefix[Length(Prefix)] = '\'))",
        "Result := '%' + BestName + '%' + CmdEnvEscape(Copy(Value, Length(Best) + 1, Length(Value)));"
    )) {
        if ($pathMatch.Value.IndexOf($pathRule, [StringComparison]::Ordinal) -lt 0) { throw "CmdEnvPathValue rule missing: $pathRule" }
    }
    # PowerShell port of the Pascal rules: CompareText folds ASCII letters only,
    # RemoveBackslashUnlessRoot drops one trailing backslash except on a drive root.
    function ConvertTo-AsciiUpper([string]$Value) {
        $chars = $Value.ToCharArray()
        for ($i = 0; $i -lt $chars.Length; $i++) {
            if ($chars[$i] -ge [char]'a' -and $chars[$i] -le [char]'z') { $chars[$i] = [char]([int]$chars[$i] - 32) }
        }
        return -join $chars
    }
    function Test-NonAscii([string]$Value) { return @($Value.ToCharArray() | Where-Object { [int]$_ -gt 127 }).Count -gt 0 }
    function Invoke-CmdEnvPathValue([string]$Value) {
        $escaped = $Value.Replace('%', '%%')
        if (-not (Test-NonAscii $Value)) { return $escaped }
        $best = ''; $bestName = ''
        foreach ($name in $pathVariables) {
            $prefix = [string][Environment]::GetEnvironmentVariable($name)
            if ($prefix.EndsWith('\') -and -not ($prefix.Length -eq 3 -and $prefix[1] -eq ':')) {
                $prefix = $prefix.Substring(0, $prefix.Length - 1)
            }
            if ($prefix.Length -gt $best.Length -and $Value.Length -ge $prefix.Length -and
                (ConvertTo-AsciiUpper $Value.Substring(0, $prefix.Length)) -ceq (ConvertTo-AsciiUpper $prefix) -and
                ($Value.Length -eq $prefix.Length -or $Value[$prefix.Length] -eq '\' -or $prefix.EndsWith('\'))) {
                $best = $prefix; $bestName = $name
            }
        }
        if ($bestName -eq '') { return $escaped }
        return '%' + $bestName + '%' + $Value.Substring($best.Length).Replace('%', '%%')
    }
    function Invoke-CmdUtf8Frame([string]$Body) {
        if (Test-NonAscii $Body) { return $frameHead + $Body + $frameTail }
        return $Body
    }
    # The probe reports through files: the exit code of the cmd.exe line is the one of its last
    # command, and redirecting the console output is not what a launcher sees. The caller's code
    # page is restored at the end of the line, the visible case shares this console.
    $callerPage = [Console]::OutputEncoding.CodePage
    function Invoke-EnvProbe([string]$EnvFile, [bool]$Hidden) {
        $resultFile = Join-Path $root 'native-probe.result'
        $pageFile = Join-Path $root 'native-probe.page'
        Remove-Item -LiteralPath $resultFile, $pageFile -Force -ErrorAction SilentlyContinue
        $psi = New-Object System.Diagnostics.ProcessStartInfo($env:ComSpec,
            "/d /c `"chcp 850 >nul & call `"$nativeProbe`" & chcp >`"$pageFile`" & chcp $callerPage >nul`"")
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $Hidden
        $psi.EnvironmentVariables['NEXUS_PROBE_ENV'] = $EnvFile
        $psi.EnvironmentVariables['NEXUS_PROBE_RESULT'] = $resultFile
        $p = [Diagnostics.Process]::Start($psi)
        $p.WaitForExit()
        $result = if (Test-Path -LiteralPath $resultFile) { ([IO.File]::ReadAllText($resultFile)).Trim() } else { 'no result' }
        $pageText = if (Test-Path -LiteralPath $pageFile) { [IO.File]::ReadAllText($pageFile) } else { '' }
        $page = [regex]::Match($pageText, '(\d+)\D*$')
        $pageValue = if ($page.Success) { $page.Groups[1].Value } else { '' }
        return [PSCustomObject]@{ Result = $result; Page = $pageValue; Output = $pageText }
    }

    $nativeProbe = Join-Path $root 'native-probe.cmd'
    [IO.File]::WriteAllText(
        $nativeProbe,
        "@echo off`r`nsetlocal DisableDelayedExpansion`r`ncall `"%NEXUS_PROBE_ENV%`"`r`n" +
        "if not exist `"%NEXUS_HOME%\marker`" (>`"%NEXUS_PROBE_RESULT%`" echo missing NEXUS_HOME& exit /b 3)`r`n" +
        "if not `"%NEXUS_REST_HOST%`"==`"127.0.0.1`" (>`"%NEXUS_PROBE_RESULT%`" echo missing NEXUS_REST_HOST& exit /b 4)`r`n" +
        ">`"%NEXUS_PROBE_RESULT%`" echo OK`r`nexit /b 0`r`n",
        [Text.Encoding]::ASCII)
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $savedProfile = @{ USERPROFILE = $env:USERPROFILE; LOCALAPPDATA = $env:LOCALAPPDATA }
    try {
        $profileDir = Join-Path $root ('H' + [char]0xE9 + 'l' + [char]0xE8 + 'ne ' + [char]0x141 + 'ukasz')
        $localDir = Join-Path $profileDir 'AppData\Local'
        $asciiProfile = Join-Path $root 'ascii-profile'
        $upperProfileHome = Join-Path $root ('H' + [char]0xC9 + 'L' + [char]0xC8 + 'NE ' + [char]0x141 + 'UKASZ\.nexus')
        $outside = Join-Path $root ('Donn' + [char]0xE9 + 'es\' + [char]0x141 + [char]0xF3 + 'd' + [char]0x17A + '\nexus')
        # Reference: the SET line expected in the file; '' means a literal value without variable.
        function New-EnvCase([string]$NexusHome, [string]$Reference = '', [string]$UserProfile = $profileDir,
                [string]$LocalAppData = $localDir) {
            return [PSCustomObject]@{ Home = $NexusHome; Reference = $Reference; UserProfile = $UserProfile; LocalAppData = $LocalAppData }
        }
        $cases = @(
            (New-EnvCase $outside),
            (New-EnvCase (Join-Path $root '100%\nexus')),
            (New-EnvCase (Join-Path $root ('Donn' + [char]0xE9 + 'es 100%\nexus'))),
            (New-EnvCase (Join-Path $profileDir '.nexus') 'set "NEXUS_HOME=%USERPROFILE%\.nexus"'),
            (New-EnvCase (Join-Path $localDir 'NEXUS\data') 'set "NEXUS_HOME=%LOCALAPPDATA%\NEXUS\data"'),
            (New-EnvCase $profileDir 'set "NEXUS_HOME=%USERPROFILE%"'),
            (New-EnvCase ($profileDir + '2\nexus')),
            (New-EnvCase $upperProfileHome),
            (New-EnvCase (Join-Path $localDir 'NEXUS\data') 'set "NEXUS_HOME=%USERPROFILE%\AppData\Local\NEXUS\data"' $profileDir ''),
            (New-EnvCase (Join-Path $asciiProfile '.nexus') '' $asciiProfile (Join-Path $asciiProfile 'AppData\Local'))
        )
        $index = 0
        foreach ($case in $cases) {
            $index++
            [Environment]::SetEnvironmentVariable('USERPROFILE', $case.UserProfile, 'Process')
            $caseLocal = if ($case.LocalAppData) { $case.LocalAppData } else { $null }
            [Environment]::SetEnvironmentVariable('LOCALAPPDATA', $caseLocal, 'Process')
            New-Item -ItemType Directory -Force -Path $case.Home | Out-Null
            [IO.File]::WriteAllText((Join-Path $case.Home 'marker'), 'marker', [Text.Encoding]::ASCII)
            $body = 'set "NEXUS_HOME=' + (Invoke-CmdEnvPathValue $case.Home) + '"' + "`r`n" + 'set "NEXUS_REST_HOST=127.0.0.1"' + "`r`n"
            $content = "@echo off`r`n" + (Invoke-CmdUtf8Frame $body) + "`r`n"
            $envFile = Join-Path $root "native-$index.env.cmd"
            [IO.File]::WriteAllText($envFile, $content, $utf8NoBom)
            $bytes = [IO.File]::ReadAllBytes($envFile)
            if ($bytes[0] -eq 0xEF) { throw "nexus-native.env.cmd case $index starts with a BOM." }
            if (($content.IndexOf('chcp 65001 >nul', [StringComparison]::Ordinal) -ge 0) -ne (Test-NonAscii $body)) {
                throw "nexus-native.env.cmd case $index is framed although its SET lines are ASCII, or the reverse: $content"
            }
            if ($case.Reference -ne '') {
                if ($content.IndexOf($case.Reference, [StringComparison]::Ordinal) -lt 0) {
                    throw "nexus-native.env.cmd case $index does not hold '$($case.Reference)': $content"
                }
            }
            elseif ($content -match '%(USERPROFILE|LOCALAPPDATA)%') {
                throw "nexus-native.env.cmd case $index must keep a literal NEXUS_HOME: $content"
            }
            foreach ($hidden in @($false, $true)) {
                $result = Invoke-EnvProbe $envFile $hidden
                $label = "Launcher probe (case $index, hidden console=$hidden)"
                if ($result.Result -ne 'OK') {
                    throw "$label did not receive NEXUS_HOME=$($case.Home): $($result.Result)"
                }
                if ($result.Page -ne '850') {
                    throw "$label left code page '$($result.Page)' instead of 850: $($result.Output)"
                }
            }
        }
        [Environment]::SetEnvironmentVariable('USERPROFILE', $profileDir, 'Process')
        [Environment]::SetEnvironmentVariable('LOCALAPPDATA', $localDir, 'Process')

        # Negative control: the literal non-ASCII value without the chcp frame is decoded in code
        # page 850 and the launcher misses the folder, so the probe above is not vacuous.
        $unframed = Join-Path $root 'native-unframed.env.cmd'
        [IO.File]::WriteAllText($unframed,
            "@echo off`r`nset `"NEXUS_HOME=$outside`"`r`nset `"NEXUS_REST_HOST=127.0.0.1`"`r`n", $utf8NoBom)
        foreach ($hidden in @($false, $true)) {
            if ((Invoke-EnvProbe $unframed $hidden).Result -ne 'missing NEXUS_HOME') {
                throw "nexus-native.env.cmd probe is vacuous (hidden console=$hidden): an unframed UTF-8 value must fail in code page 850."
            }
        }
        # Negative control: without the closing lines of the frame, the console stays in code page 65001.
        $unrestored = Join-Path $root 'native-unrestored.env.cmd'
        [IO.File]::WriteAllText($unrestored,
            "@echo off`r`n" + $frameHead + "set `"NEXUS_HOME=$outside`"`r`nset `"NEXUS_REST_HOST=127.0.0.1`"`r`n", $utf8NoBom)
        if ((Invoke-EnvProbe $unrestored $false).Page -eq '850') {
            throw 'Code page probe is vacuous: a frame without its closing lines must leave code page 65001.'
        }
    }
    finally {
        foreach ($name in $savedProfile.Keys) { [Environment]::SetEnvironmentVariable($name, $savedProfile[$name], 'Process') }
    }

    # Install folder outside the ANSI code page: refused on the folder page and before installing,
    # because the embedded Java runtime does not start from it. Replay of the String -> AnsiString
    # -> String round trip in code page 1252, the code page of the CI runners.
    $roundTripPattern = '(?s)function IsAnsiRoundTrip\(Value: String\): Boolean;\s*var\s*Ansi: AnsiString;\s*' +
        'begin\s*Ansi := Value;\s*Result := String\(Ansi\) = Value;\s*end;'
    $roundTrip = [regex]::Match($hardened, $roundTripPattern)
    if (-not $roundTrip.Success) { throw 'IsAnsiRoundTrip must compare the name with its AnsiString conversion.' }
    # SuppressibleMsgBox, not MsgBox: /SUPPRESSMSGBOXES does not suppress MsgBox, and a silent
    # install would wait on the modal box.
    $folderGuard = '(?s)function NextButtonClick\(CurPageID: Integer\): Boolean;.*?begin\s*Result := True;\s*' +
        '(?:\{[^}]*\}\s*)?if \(CurPageID = wpSelectDir\) and \(not IsAnsiRoundTrip\(WizardDirValue\(\)\)\) then\s*begin\s*' +
        'SuppressibleMsgBox\(InstallDirOutsideAnsiMessage\(\), mbError, MB_OK, IDOK\);\s*Result := False;\s*exit;'
    if (-not [regex]::IsMatch($hardened, $folderGuard)) {
        throw 'NextButtonClick must refuse an install folder outside the ANSI code page, by SuppressibleMsgBox, before any other check.'
    }
    $prepareGuard = '(?s)function PrepareToInstall\(var NeedsRestart: Boolean\): String;.*?' +
        'if not IsAnsiRoundTrip\(WizardDirValue\(\)\) then\s*begin\s*Result := InstallDirOutsideAnsiMessage\(\);\s*exit;'
    if (-not [regex]::IsMatch($hardened, $prepareGuard)) {
        throw 'PrepareToInstall must refuse an install folder outside the ANSI code page.'
    }
    if ($hardened.IndexOf('Le runtime Java embarqu', [StringComparison]::Ordinal) -lt 0) {
        throw 'The install-folder refusal must name the Java runtime limit.'
    }
    $ansi1252 = [Text.Encoding]::GetEncoding(1252)
    function Test-AnsiRoundTrip([string]$Value) { return $ansi1252.GetString($ansi1252.GetBytes($Value)) -ceq $Value }
    $acceptedFolder = 'C:\Outils\Inst' + [char]0xE0 + 'll' + [char]0xE9 + ' H' + [char]0xE9 + 'l' + [char]0xE8 + 'ne'
    $refusedFolder = 'C:\Outils\Inst' + [char]0xE0 + 'll' + [char]0xE9 + ' ' + [char]0x141 + 'ukasz'
    if (-not (Test-AnsiRoundTrip $acceptedFolder)) { throw "An install folder of the ANSI code page must be accepted: $acceptedFolder" }
    if (Test-AnsiRoundTrip $refusedFolder) { throw "An install folder with U+0141 must be refused under code page 1252: $refusedFolder" }

    Write-Host 'NEXUS Windows cmd and PowerShell escaping, client JSON helper, REST auth, Docker forward and native-access contract PASS' -ForegroundColor Green
}
finally {
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
