@echo off
rem JBCT - Java Backend Coding Technology CLI
rem
rem Usage: jbct <command> [options] [arguments]
rem

setlocal

rem Find the JBCT home directory
set "JBCT_HOME=%~dp0.."
set "JBCT_JAR=%JBCT_HOME%\lib\jbct.jar"

rem Check if JAR exists
if not exist "%JBCT_JAR%" (
    echo Error: jbct.jar not found at %JBCT_JAR% >&2
    exit /b 1
)

rem Set default JVM options if not already set
if not defined JBCT_OPTS set JBCT_OPTS=

rem Run JBCT
java %JBCT_OPTS% -jar "%JBCT_JAR%" %*
