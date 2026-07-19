@echo off
setlocal EnableExtensions

set "ROOT=%~dp0.."
set "JAR="
for %%F in ("%ROOT%\target\nexus-context-engine-*-cli.jar") do set "JAR=%%~fF"

if not defined JAR (
    echo JAR CLI NEXUS introuvable dans target/. Lancez d'abord : mvn clean package 1>&2
    exit /b 1
)

java -jar "%JAR%" %*
exit /b %ERRORLEVEL%
