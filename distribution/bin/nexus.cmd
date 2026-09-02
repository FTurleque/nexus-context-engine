@ECHO OFF
SETLOCAL
SET "SCRIPT_DIR=%~dp0"
WHERE java >NUL 2>&1
IF ERRORLEVEL 1 (
  ECHO Java 21 ou superieur est requis pour executer NEXUS. 1>&2
  EXIT /B 1
)
java --enable-native-access=ALL-UNNAMED -jar "%SCRIPT_DIR%..\lib\nexus-cli.jar" %*
EXIT /B %ERRORLEVEL%
