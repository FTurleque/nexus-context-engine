@ECHO OFF
SETLOCAL EnableExtensions

SET "MAVEN_VERSION=3.9.16"
SET "MAVEN_DIST_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip"
SET "WRAPPER_HOME=%USERPROFILE%\.m2\wrapper\dists\nexus\apache-maven-%MAVEN_VERSION%"
SET "MAVEN_HOME=%WRAPPER_HOME%\apache-maven-%MAVEN_VERSION%"
SET "ARCHIVE=%WRAPPER_HOME%\apache-maven-%MAVEN_VERSION%-bin.zip"
SET "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
SET "INTEGRITY_FILE=%~dp0config\tool-integrity.properties"

IF EXIST "%MAVEN_HOME%\bin\mvn.cmd" GOTO RUN_MAVEN
IF NOT EXIST "%POWERSHELL_EXE%" (
  ECHO [NEXUS] Windows PowerShell 5.1 introuvable : %POWERSHELL_EXE%
  EXIT /B 1
)
IF NOT EXIST "%INTEGRITY_FILE%" (
  ECHO [NEXUS] Fichier d'integrite introuvable : %INTEGRITY_FILE%
  EXIT /B 1
)

ECHO [NEXUS] Installation locale de Maven %MAVEN_VERSION% via Maven Central...
"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [void](New-Item -ItemType Directory -Force -Path ([Environment]::ExpandEnvironmentVariables('%WRAPPER_HOME%')))"
IF ERRORLEVEL 1 EXIT /B %ERRORLEVEL%

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
"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $homeDir=[Environment]::ExpandEnvironmentVariables('%WRAPPER_HOME%'); $archive=[Environment]::ExpandEnvironmentVariables('%ARCHIVE%'); $integrity=[Environment]::ExpandEnvironmentVariables('%INTEGRITY_FILE%'); $key='maven.%MAVEN_VERSION%.sha512'; $entry=Get-Content -LiteralPath $integrity | Where-Object { $_ -like ($key + '=*') } | Select-Object -First 1; if (-not $entry) { throw ('Ancre SHA-512 Maven absente pour %MAVEN_VERSION% dans ' + $integrity) }; $expected=$entry.Substring($key.Length + 1).Trim().ToUpperInvariant(); if ($expected -notmatch '^[0-9A-F]{128}$') { throw 'Ancre SHA-512 Maven invalide' }; $actual=(Get-FileHash -Algorithm SHA512 -Path $archive).Hash.ToUpperInvariant(); if ($expected -ne $actual) { Remove-Item -Force $archive; throw ('Checksum SHA-512 Maven invalide. Attendu=' + $expected + ', obtenu=' + $actual) }; Expand-Archive -Force -Path $archive -DestinationPath $homeDir; Remove-Item -Force $archive;"
IF ERRORLEVEL 1 EXIT /B %ERRORLEVEL%

:RUN_MAVEN
CALL "%MAVEN_HOME%\bin\mvn.cmd" %*
EXIT /B %ERRORLEVEL%
