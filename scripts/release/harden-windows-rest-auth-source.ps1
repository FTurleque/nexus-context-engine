Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Protect-NexusNativeRestAuthSource {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Source)

    $anchor = @'
procedure WriteNativeConfiguration;
var
  Content: String;
begin
  if not InstallNative() then exit;
  ForceDirectories(ExpandConstant('{app}\config'));
  Content := '@echo off' + #13#10 +
    'set "NEXUS_HOME=' + CmdEnvEscape(RuntimePage.Values[0]) + '"' + #13#10 +
    'set "NEXUS_REST_HOST=' + CmdEnvEscape(RuntimePage.Values[1]) + '"' + #13#10 +
    'set "NEXUS_REST_PORT=' + CmdEnvEscape(RuntimePage.Values[2]) + '"' + #13#10 +
    'set "NEXUS_REST_API_TOKEN=' + CmdEnvEscape(RuntimePage.Values[3]) + '"' + #13#10 +
    'set "NEXUS_SEMANTIC_PROVIDER=' + CmdEnvEscape(SemanticProviderValue()) + '"' + #13#10 +
    'set "NEXUS_OLLAMA_BASE_URL=' + CmdEnvEscape(OllamaPage.Values[0]) + '"' + #13#10;
'@

    $replacement = @'
procedure WriteNativeConfiguration;
var
  Content: String;
  NativeToken: String;
begin
  if not InstallNative() then exit;
  ForceDirectories(ExpandConstant('{app}\config'));
  NativeToken := RuntimePage.Values[3];
  if InstallNativeRest() and (Trim(NativeToken) = '') then
  begin
    NativeToken := GenerateLocalToken();
    RuntimePage.Values[3] := NativeToken;
  end;
  Content := '@echo off' + #13#10 +
    'set "NEXUS_HOME=' + CmdEnvEscape(RuntimePage.Values[0]) + '"' + #13#10 +
    'set "NEXUS_REST_HOST=' + CmdEnvEscape(RuntimePage.Values[1]) + '"' + #13#10 +
    'set "NEXUS_REST_PORT=' + CmdEnvEscape(RuntimePage.Values[2]) + '"' + #13#10 +
    'set "NEXUS_REST_API_TOKEN=' + CmdEnvEscape(NativeToken) + '"' + #13#10 +
    'set "NEXUS_SEMANTIC_PROVIDER=' + CmdEnvEscape(SemanticProviderValue()) + '"' + #13#10 +
    'set "NEXUS_OLLAMA_BASE_URL=' + CmdEnvEscape(OllamaPage.Values[0]) + '"' + #13#10;
'@

    $first = $Source.IndexOf($anchor, [StringComparison]::Ordinal)
    if ($first -lt 0) {
        throw 'Installer REST authentication anchor missing.'
    }
    if ($Source.IndexOf($anchor, $first + $anchor.Length, [StringComparison]::Ordinal) -ge 0) {
        throw 'Installer REST authentication anchor is ambiguous.'
    }
    return $Source.Substring(0, $first) + $replacement + $Source.Substring($first + $anchor.Length)
}
