@ECHO OFF
SETLOCAL EnableExtensions

SET "MAVEN_VERSION=3.9.11"
SET "MAVEN_DIST_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip"
SET "MAVEN_DIST_SHA512_URL=%MAVEN_DIST_URL%.sha512"
SET "WRAPPER_HOME=%USERPROFILE%\.m2\wrapper\dists\nexus\apache-maven-%MAVEN_VERSION%"
SET "MAVEN_HOME=%WRAPPER_HOME%\apache-maven-%MAVEN_VERSION%"
SET "ARCHIVE=%WRAPPER_HOME%\apache-maven-%MAVEN_VERSION%-bin.zip"

IF EXIST "%MAVEN_HOME%\bin\mvn.cmd" GOTO RUN_MAVEN

ECHO [NEXUS] Installation locale de Maven %MAVEN_VERSION% via Maven Central...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop';" ^
  "$homeDir=[Environment]::ExpandEnvironmentVariables('%WRAPPER_HOME%');" ^
  "$archive=[Environment]::ExpandEnvironmentVariables('%ARCHIVE%');" ^
  "New-Item -ItemType Directory -Force -Path $homeDir ^| Out-Null;" ^
  "Invoke-WebRequest -UseBasicParsing -Uri '%MAVEN_DIST_URL%' -OutFile $archive;" ^
  "$expected=(Invoke-WebRequest -UseBasicParsing -Uri '%MAVEN_DIST_SHA512_URL%').Content.Trim().Split(' ')[0].ToUpperInvariant();" ^
  "$actual=(Get-FileHash -Algorithm SHA512 -Path $archive).Hash.ToUpperInvariant();" ^
  "if ($expected -ne $actual) { Remove-Item -Force $archive; throw 'Checksum SHA-512 Maven invalide' };" ^
  "Expand-Archive -Force -Path $archive -DestinationPath $homeDir;" ^
  "Remove-Item -Force $archive;"
IF ERRORLEVEL 1 EXIT /B %ERRORLEVEL%

:RUN_MAVEN
CALL "%MAVEN_HOME%\bin\mvn.cmd" %*
EXIT /B %ERRORLEVEL%
