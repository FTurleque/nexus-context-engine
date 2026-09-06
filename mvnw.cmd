@ECHO OFF
SETLOCAL EnableExtensions

SET "MAVEN_VERSION=3.9.16"
SET "MAVEN_DIST_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip"
SET "WRAPPER_HOME=%USERPROFILE%\.m2\wrapper\dists\nexus\apache-maven-%MAVEN_VERSION%"
SET "MAVEN_HOME=%WRAPPER_HOME%\apache-maven-%MAVEN_VERSION%"
SET "ARCHIVE=%WRAPPER_HOME%\apache-maven-%MAVEN_VERSION%-bin.zip"
SET "ARCHIVE_PREFIX=apache-maven-%MAVEN_VERSION%/"
SET "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
SET "INTEGRITY_FILE=%~dp0config\tool-integrity.properties"
SET "CACHE_VERIFY_SCRIPT=%~dp0scripts\release\ToolArchiveVerifier.java"

IF NOT EXIST "%POWERSHELL_EXE%" (
  ECHO [NEXUS] Windows PowerShell 5.1 introuvable : %POWERSHELL_EXE%
  EXIT /B 1
)
IF NOT EXIST "%INTEGRITY_FILE%" (
  ECHO [NEXUS] Fichier d'integrite introuvable : %INTEGRITY_FILE%
  EXIT /B 1
)
IF NOT EXIST "%CACHE_VERIFY_SCRIPT%" (
  ECHO [NEXUS] Verificateur de cache Maven introuvable : %CACHE_VERIFY_SCRIPT%
  EXIT /B 1
)
WHERE java.exe >NUL 2>NUL
IF ERRORLEVEL 1 (
  ECHO [NEXUS] Java 21+ est requis pour verifier et executer Maven Wrapper.
  EXIT /B 1
)

"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [void](New-Item -ItemType Directory -Force -Path ([Environment]::ExpandEnvironmentVariables('%WRAPPER_HOME%')))"
IF ERRORLEVEL 1 EXIT /B %ERRORLEVEL%

IF EXIST "%ARCHIVE%" GOTO VERIFY_MAVEN

:DOWNLOAD_MAVEN
ECHO [NEXUS] Telechargement de Maven %MAVEN_VERSION% via Maven Central...
WHERE curl.exe >NUL 2>NUL
IF ERRORLEVEL 1 GOTO DOWNLOAD_POWERSHELL

curl.exe --fail --location --silent --show-error --retry 3 --retry-delay 2 --retry-all-errors --output "%ARCHIVE%" "%MAVEN_DIST_URL%"
IF ERRORLEVEL 1 (
  IF EXIST "%ARCHIVE%" DEL /F /Q "%ARCHIVE%" >NUL 2>NUL
  ECHO [NEXUS] curl.exe indisponible pour Maven Central, tentative Windows PowerShell...
  GOTO DOWNLOAD_POWERSHELL
)
GOTO VERIFY_MAVEN

:DOWNLOAD_POWERSHELL
"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $archive=[Environment]::ExpandEnvironmentVariables('%ARCHIVE%'); $headers=@{'User-Agent'='NEXUS-Maven-Wrapper/%MAVEN_VERSION%'}; Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri '%MAVEN_DIST_URL%' -OutFile $archive"
IF ERRORLEVEL 1 (
  IF EXIST "%ARCHIVE%" DEL /F /Q "%ARCHIVE%" >NUL 2>NUL
  ECHO [NEXUS] Echec du telechargement Maven avec Windows PowerShell.
  EXIT /B 1
)

:VERIFY_MAVEN
"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $archive=[Environment]::ExpandEnvironmentVariables('%ARCHIVE%'); $integrity=[Environment]::ExpandEnvironmentVariables('%INTEGRITY_FILE%'); $key='maven.%MAVEN_VERSION%.sha512'; $entry=Get-Content -LiteralPath $integrity | Where-Object { $_ -like ($key + '=*') } | Select-Object -First 1; if (-not $entry) { throw ('Ancre SHA-512 Maven absente pour %MAVEN_VERSION% dans ' + $integrity) }; $expected=$entry.Substring($key.Length + 1).Trim().ToUpperInvariant(); if ($expected -notmatch '^[0-9A-F]{128}$') { throw 'Ancre SHA-512 Maven invalide' }; $sha=[System.Security.Cryptography.SHA512]::Create(); $stream=$null; try { $stream=[System.IO.File]::OpenRead($archive); $actual=([System.BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToUpperInvariant() } finally { if ($null -ne $stream) { $stream.Dispose() }; $sha.Dispose() }; if ($expected -ne $actual) { Remove-Item -Force $archive; throw ('Checksum SHA-512 Maven invalide. Attendu=' + $expected + ', obtenu=' + $actual) }"
IF ERRORLEVEL 1 EXIT /B %ERRORLEVEL%

IF NOT EXIST "%MAVEN_HOME%\bin\mvn.cmd" GOTO INSTALL_MAVEN
java "%CACHE_VERIFY_SCRIPT%" zip "%ARCHIVE%" "%MAVEN_HOME%" "%ARCHIVE_PREFIX%"
IF NOT ERRORLEVEL 1 GOTO RUN_MAVEN

ECHO [NEXUS] Cache Maven extrait altere ; reconstruction depuis l'archive SHA-512 verifiee.
"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $home=[Environment]::ExpandEnvironmentVariables('%MAVEN_HOME%'); if (Test-Path -LiteralPath $home) { Remove-Item -Recurse -Force -LiteralPath $home }"
IF ERRORLEVEL 1 EXIT /B %ERRORLEVEL%

:INSTALL_MAVEN
ECHO [NEXUS] Extraction de Maven %MAVEN_VERSION% depuis l'archive verifiee...
"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $archive=[Environment]::ExpandEnvironmentVariables('%ARCHIVE%'); $homeDir=[Environment]::ExpandEnvironmentVariables('%WRAPPER_HOME%'); Expand-Archive -Force -Path $archive -DestinationPath $homeDir"
IF ERRORLEVEL 1 EXIT /B %ERRORLEVEL%
IF NOT EXIST "%MAVEN_HOME%\bin\mvn.cmd" (
  ECHO [NEXUS] L'archive Maven verifiee n'a pas produit le launcher attendu : %MAVEN_HOME%\bin\mvn.cmd
  EXIT /B 1
)
java "%CACHE_VERIFY_SCRIPT%" zip "%ARCHIVE%" "%MAVEN_HOME%" "%ARCHIVE_PREFIX%"
IF ERRORLEVEL 1 (
  ECHO [NEXUS] Verification du Maven extrait en echec.
  EXIT /B 1
)

:RUN_MAVEN
CALL "%MAVEN_HOME%\bin\mvn.cmd" %*
EXIT /B %ERRORLEVEL%
