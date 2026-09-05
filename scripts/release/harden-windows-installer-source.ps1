Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Protect-NexusInstallerSource {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Source)

    function Replace-ExactlyOnce([string]$Text, [string]$Needle, [string]$Replacement, [string]$Label) {
        $first = $Text.IndexOf($Needle, [StringComparison]::Ordinal)
        if ($first -lt 0) { throw "Installer hardening anchor missing: $Label" }
        $second = $Text.IndexOf($Needle, $first + $Needle.Length, [StringComparison]::Ordinal)
        if ($second -ge 0) { throw "Installer hardening anchor is ambiguous: $Label" }
        return $Text.Substring(0, $first) + $Replacement + $Text.Substring($first + $Needle.Length)
    }

    $escapeAnchor = @'
function TomlEscape(Value: String): String;
begin
  Result := Value;
  StringChangeEx(Result, '\', '\\', True);
  StringChangeEx(Result, '"', '\"', True);
end;
'@
    $escapeReplacement = $escapeAnchor + @'

function CmdEnvEscape(Value: String): String;
begin
  Result := Value;
  { In a batch file, percent signs are expanded even inside SET "...". Doubling
    them preserves the literal character. Delayed expansion is disabled by every
    launcher before calling nexus-native.env.cmd, so literal ! remains intact. }
  StringChangeEx(Result, '%', '%%', True);
end;

function DotEnvQuoted(Value: String): String;
begin
  Result := Value;
  { Compose treats single-quoted .env values literally. Escape only an embedded
    single quote so dollar signs, backslashes and double quotes survive unchanged. }
  StringChangeEx(Result, '''', '\''', True);
  Result := '''' + Result + '''';
end;

function ContainsUnsafeConfigChars(Value: String): Boolean;
begin
  Result := (Pos(#13, Value) > 0) or (Pos(#10, Value) > 0) or
            (Pos('"', Value) > 0) or (Pos('^', Value) > 0);
end;

function IsValidDockerImageReference(Value: String): Boolean;
var
  I: Integer;
  C: Char;
begin
  { A deliberately conservative Docker-reference alphabet. Besides rejecting
    CR/LF and shell/dotenv metacharacters, this still supports registry ports,
    repository paths, tags and digest syntax. Docker remains the final semantic
    validator for the reference itself. }
  Value := Trim(Value);
  Result := False;
  if (Value = '') or (Length(Value) > 512) then exit;
  for I := 1 to Length(Value) do
  begin
    C := Value[I];
    if not (((C >= 'a') and (C <= 'z')) or
            ((C >= 'A') and (C <= 'Z')) or
            ((C >= '0') and (C <= '9')) or
            (C = '.') or (C = '_') or (C = '-') or
            (C = '/') or (C = ':') or (C = '@') or (C = '+')) then
      exit;
  end;
  Result := True;
end;

function IsLoopbackRestHost(Value: String): Boolean;
begin
  Value := Lowercase(Trim(Value));
  Result := (Value = '127.0.0.1') or (Value = 'localhost') or
            (Value = '::1') or (Value = '[::1]');
end;
'@
    $Source = Replace-ExactlyOnce $Source $escapeAnchor $escapeReplacement 'configuration escaping helpers'

    $runtimeValidation = @'
    if Trim(RuntimePage.Values[0]) = '' then
    begin
      MsgBox('NEXUS_HOME ne peut pas être vide.', mbError, MB_OK);
      Result := False;
      exit;
    end;
'@
    $runtimeValidationReplacement = $runtimeValidation + @'
    if ContainsUnsafeConfigChars(RuntimePage.Values[0]) or
       ContainsUnsafeConfigChars(RuntimePage.Values[3]) then
    begin
      MsgBox('NEXUS_HOME et le token REST ne peuvent pas contenir de retour à la ligne, guillemet double ou accent circonflexe.', mbError, MB_OK);
      Result := False;
      exit;
    end;
'@
    $Source = Replace-ExactlyOnce $Source $runtimeValidation $runtimeValidationReplacement 'runtime value validation'

    $remoteNative = @'
    if InstallNativeRest() and (Lowercase(Trim(RuntimePage.Values[1])) <> '127.0.0.1') and (Lowercase(Trim(RuntimePage.Values[1])) <> 'localhost') and (Trim(RuntimePage.Values[3]) = '') then
    begin
      MsgBox('Un token API REST est obligatoire pour une écoute native non-loopback.', mbError, MB_OK);
      Result := False;
      exit;
    end;
'@
    $remoteNativeReplacement = @'
    if InstallNativeRest() and not IsLoopbackRestHost(RuntimePage.Values[1]) then
    begin
      MsgBox('Le wizard Windows limite le REST natif au loopback. Une exposition distante est une configuration administrative avancée : configurez manuellement NEXUS_REST_ALLOWED_PROJECT_ROOTS, un token fort, NEXUS_REST_EXPOSURE_MODE et HTTPS/tunnel.', mbError, MB_OK);
      Result := False;
      exit;
    end;
'@
    $Source = Replace-ExactlyOnce $Source $remoteNative $remoteNativeReplacement 'native REST remote exposure guard'

    $dockerImageValidation = @'
    if HasWhitespace(Trim(DockerPage.Values[0])) then
    begin
      MsgBox('L''image Docker ne doit pas contenir d''espace (ex. ghcr.io/org/nexus:tag).', mbError, MB_OK);
      Result := False;
      exit;
    end;
'@
    $dockerImageValidationReplacement = @'
    if not IsValidDockerImageReference(DockerPage.Values[0]) then
    begin
      MsgBox('L''image Docker contient un caractère non autorisé. Utilisez une référence registry/repository:tag ou @digest sans espace, retour à la ligne, $, #, guillemet ou antislash.', mbError, MB_OK);
      Result := False;
      exit;
    end;
'@
    $Source = Replace-ExactlyOnce $Source $dockerImageValidation $dockerImageValidationReplacement 'Docker image reference validation'

    $dockerBindValidation = @'
    if not IsSafeBindAddress(DockerPage.Values[2]) then
    begin
      MsgBox('L''adresse d''écoute hôte Docker est vide ou invalide.', mbError, MB_OK);
      Result := False;
      exit;
    end;
'@
    $dockerBindReplacement = $dockerBindValidation + @'
    if not IsLoopbackRestHost(DockerPage.Values[2]) then
    begin
      MsgBox('Le wizard Windows limite la publication REST Docker au loopback. Le mode loopback-forward exige que l''adresse hôte réellement déclarée soit loopback ; utilisez un reverse proxy HTTPS ou un tunnel pour une exposition distante.', mbError, MB_OK);
      Result := False;
      exit;
    end;
    if ContainsUnsafeConfigChars(RuntimePage.Values[0]) or
       ContainsUnsafeConfigChars(DockerPage.Values[5]) or
       ContainsUnsafeConfigChars(OllamaPage.Values[0]) then
    begin
      MsgBox('Les chemins et URL générés ne peuvent pas contenir de retour à la ligne, guillemet double ou accent circonflexe.', mbError, MB_OK);
      Result := False;
      exit;
    end;
'@
    $Source = Replace-ExactlyOnce $Source $dockerBindValidation $dockerBindReplacement 'Docker loopback and value validation'

    $nativeWriter = @'
  Content := '@echo off' + #13#10 +
    'set "NEXUS_HOME=' + RuntimePage.Values[0] + '"' + #13#10 +
    'set "NEXUS_REST_HOST=' + RuntimePage.Values[1] + '"' + #13#10 +
    'set "NEXUS_REST_PORT=' + RuntimePage.Values[2] + '"' + #13#10 +
    'set "NEXUS_REST_API_TOKEN=' + RuntimePage.Values[3] + '"' + #13#10 +
    'set "NEXUS_SEMANTIC_PROVIDER=' + SemanticProviderValue() + '"' + #13#10 +
    'set "NEXUS_OLLAMA_BASE_URL=' + OllamaPage.Values[0] + '"' + #13#10;
'@
    $nativeWriterReplacement = @'
  Content := '@echo off' + #13#10 +
    'set "NEXUS_HOME=' + CmdEnvEscape(RuntimePage.Values[0]) + '"' + #13#10 +
    'set "NEXUS_REST_HOST=' + CmdEnvEscape(RuntimePage.Values[1]) + '"' + #13#10 +
    'set "NEXUS_REST_PORT=' + CmdEnvEscape(RuntimePage.Values[2]) + '"' + #13#10 +
    'set "NEXUS_REST_API_TOKEN=' + CmdEnvEscape(RuntimePage.Values[3]) + '"' + #13#10 +
    'set "NEXUS_SEMANTIC_PROVIDER=' + CmdEnvEscape(SemanticProviderValue()) + '"' + #13#10 +
    'set "NEXUS_OLLAMA_BASE_URL=' + CmdEnvEscape(OllamaPage.Values[0]) + '"' + #13#10;
'@
    $Source = Replace-ExactlyOnce $Source $nativeWriter $nativeWriterReplacement 'native batch configuration escaping'

    $dockerEnv = @'
  EnvContent :=
    'NEXUS_DOCKER_IMAGE=' + DockerPage.Values[0] + #13#10 +
    'NEXUS_DOCKER_CONTAINER=' + DockerPage.Values[1] + #13#10 +
    'NEXUS_DOCKER_RESTART_POLICY=' + DockerPage.Values[6] + #13#10 +
    'NEXUS_DOCKER_BIND_ADDRESS=' + DockerPage.Values[2] + #13#10 +
    'NEXUS_DOCKER_HOST_PORT=' + DockerPage.Values[3] + #13#10 +
    'NEXUS_DOCKER_CONTAINER_PORT=' + DockerPage.Values[4] + #13#10 +
    'NEXUS_HOME_BIND=' + DockerPath(RuntimePage.Values[0]) + #13#10 +
    'NEXUS_REPOSITORY_BIND=' + DockerPath(DockerPage.Values[5]) + #13#10 +
    'NEXUS_RUNTIME=docker' + #13#10 +
    'NEXUS_SEMANTIC_PROVIDER=' + SemanticProviderValue() + #13#10 +
    'NEXUS_OLLAMA_BASE_URL=' + OllamaUrlForDocker(OllamaPage.Values[0]) + #13#10 +
    'NEXUS_REST_API_TOKEN=' + DockerToken + #13#10;
'@
    $dockerEnvReplacement = @'
  EnvContent :=
    'NEXUS_DOCKER_IMAGE=' + DockerPage.Values[0] + #13#10 +
    'NEXUS_DOCKER_CONTAINER=' + DockerPage.Values[1] + #13#10 +
    'NEXUS_DOCKER_RESTART_POLICY=' + DockerPage.Values[6] + #13#10 +
    'NEXUS_DOCKER_BIND_ADDRESS=' + DockerPage.Values[2] + #13#10 +
    '# NEXUS_DOCKER_HOST_FORWARD_ADDRESS is derived by Compose from NEXUS_DOCKER_BIND_ADDRESS' + #13#10 +
    'NEXUS_DOCKER_HOST_PORT=' + DockerPage.Values[3] + #13#10 +
    'NEXUS_DOCKER_CONTAINER_PORT=' + DockerPage.Values[4] + #13#10 +
    'NEXUS_HOME_BIND=' + DotEnvQuoted(DockerPath(RuntimePage.Values[0])) + #13#10 +
    'NEXUS_REPOSITORY_BIND=' + DotEnvQuoted(DockerPath(DockerPage.Values[5])) + #13#10 +
    'NEXUS_RUNTIME=docker' + #13#10 +
    'NEXUS_REST_EXPOSURE_MODE=loopback-forward' + #13#10 +
    'NEXUS_SEMANTIC_PROVIDER=' + SemanticProviderValue() + #13#10 +
    'NEXUS_OLLAMA_BASE_URL=' + DotEnvQuoted(OllamaUrlForDocker(OllamaPage.Values[0])) + #13#10 +
    'NEXUS_REST_API_TOKEN=' + DotEnvQuoted(DockerToken) + #13#10;
'@
    $Source = Replace-ExactlyOnce $Source $dockerEnv $dockerEnvReplacement 'Docker dotenv escaping and forward declaration contract'

    $nativeJsonArgument = @'
    '      "args": ["-jar", "' + RunnerPath + '"]' + #13#10 +
'@
    $nativeJsonArgumentReplacement = @'
    '      "args": ["--enable-native-access=ALL-UNNAMED", "-jar", "' + RunnerPath + '"]' + #13#10 +
'@
    $Source = Replace-ExactlyOnce $Source $nativeJsonArgument $nativeJsonArgumentReplacement 'native MCP JSON runtime option'

    $nativeTomlArgument = @'
    'args = ["-jar", "' + RunnerPath + '"]' + #13#10;
'@
    $nativeTomlArgumentReplacement = @'
    'args = ["--enable-native-access=ALL-UNNAMED", "-jar", "' + RunnerPath + '"]' + #13#10;
'@
    $Source = Replace-ExactlyOnce $Source $nativeTomlArgument $nativeTomlArgumentReplacement 'native Codex TOML runtime option'

    $nativeCommandTail = @'
    CommandTail := '"' + ExpandConstant('{app}\app\runtime\bin\java.exe') + '" -jar "' + ExpandConstant('{app}\lib\nexus-mcp.jar') + '"';
'@
    $nativeCommandTailReplacement = @'
    CommandTail := '"' + ExpandConstant('{app}\app\runtime\bin\java.exe') + '" --enable-native-access=ALL-UNNAMED -jar "' + ExpandConstant('{app}\lib\nexus-mcp.jar') + '"';
'@
    $Source = Replace-ExactlyOnce $Source $nativeCommandTail $nativeCommandTailReplacement 'native assistant CLI runtime option'

    $hashHelperAnchor = @'
function PowerShellSingleQuote(Value: String): String;
begin
  Result := Value;
  StringChangeEx(Result, '''', '''''', True);
end;
'@
    $hashHelperReplacement = $hashHelperAnchor + @'

function VerifyFileSha256(FilePath: String; ExpectedSha256: String): Boolean;
var
  PowerShellPath: String;
  Script: String;
  Parameters: String;
  ResultCode: Integer;
begin
  Result := False;
  PowerShellPath := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
  if not FileExists(PowerShellPath) then exit;
  Script :=
    '$actual=(Get-FileHash -LiteralPath ''' + PowerShellSingleQuote(FilePath) + ''' -Algorithm SHA256).Hash.ToLowerInvariant(); ' +
    'if ($actual -eq ''' + Lowercase(ExpectedSha256) + ''') { exit 0 } else { exit 1 }';
  Parameters := '-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "' + Script + '"';
  Result := Exec(PowerShellPath, Parameters, '', SW_HIDE, ewWaitUntilTerminated, ResultCode) and (ResultCode = 0);
end;
'@
    $Source = Replace-ExactlyOnce $Source $hashHelperAnchor $hashHelperReplacement 'external installer SHA-256 verifier'

    $dockerDownloadAnchor = @'
    DownloadedSize := DownloadTemporaryFile(
      'https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe',
      'NEXUS-Docker-Desktop-Installer.exe', '', nil);
'@
    $dockerDownloadReplacement = @'
    DownloadedSize := DownloadTemporaryFile(
      'https://desktop.docker.com/win/main/amd64/236216/Docker%20Desktop%20Installer.exe',
      'NEXUS-Docker-Desktop-Installer.exe', '', nil);
'@
    $Source = Replace-ExactlyOnce $Source $dockerDownloadAnchor $dockerDownloadReplacement 'pinned Docker Desktop 4.86.0 download'

    $dockerFileAnchor = @'
  if not FileExists(InstallerPath) then
  begin
    Result := 'Le téléchargement Docker Desktop n''a pas produit le fichier attendu.';
    exit;
  end;
'@
    $dockerFileReplacement = $dockerFileAnchor + @'
  if not VerifyFileSha256(InstallerPath, '820438e75c16e44b393079154bea7d27958a15845c23a635b1a1f6f586b2ed44') then
  begin
    Result := 'Le SHA-256 de Docker Desktop ne correspond pas à la version NEXUS qualifiée 4.86.0. Installation annulée.';
    exit;
  end;
'@
    $Source = Replace-ExactlyOnce $Source $dockerFileAnchor $dockerFileReplacement 'Docker Desktop qualified SHA-256 gate'

    $ollamaDownloadAnchor = @'
    DownloadedSize := DownloadTemporaryFile(
      'https://ollama.com/download/OllamaSetup.exe',
      'NEXUS-Ollama-Installer.exe', '', nil);
'@
    $ollamaDownloadReplacement = @'
    DownloadedSize := DownloadTemporaryFile(
      'https://github.com/ollama/ollama/releases/download/v0.33.3/OllamaSetup.exe',
      'NEXUS-Ollama-Installer.exe', '', nil);
'@
    $Source = Replace-ExactlyOnce $Source $ollamaDownloadAnchor $ollamaDownloadReplacement 'pinned Ollama v0.33.3 download'

    $ollamaFileAnchor = @'
  if not FileExists(InstallerPath) then
  begin
    Result := 'Le téléchargement Ollama n''a pas produit le fichier attendu.';
    exit;
  end;
'@
    $ollamaFileReplacement = $ollamaFileAnchor + @'
  if not VerifyFileSha256(InstallerPath, '32cdcb1da477bc7fffbf1c1cdeeb99b1db003af094db56dd3c156abd04d34f8e') then
  begin
    Result := 'Le SHA-256 du programme d''installation Ollama ne correspond pas à la version NEXUS qualifiée v0.33.3. Installation annulée.';
    exit;
  end;
'@
    $Source = Replace-ExactlyOnce $Source $ollamaFileAnchor $ollamaFileReplacement 'Ollama qualified SHA-256 gate'

    return $Source
}
