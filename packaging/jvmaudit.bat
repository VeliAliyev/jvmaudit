@echo off
rem JVMAudit launcher for the self-contained distribution.
rem
rem Runs the bundled runtime, so this works on a host with no Java on PATH at all - which is the
rem whole point of the self-contained archive. Nothing here reaches the network.

setlocal
set "JVMAUDIT_HOME=%~dp0.."
set "JVMAUDIT_JAVA=%JVMAUDIT_HOME%\runtime\bin\java.exe"
set "JVMAUDIT_JAR=%JVMAUDIT_HOME%\lib\jvmaudit.jar"

if not exist "%JVMAUDIT_JAVA%" (
  echo jvmaudit: the bundled runtime is missing from %JVMAUDIT_HOME%\runtime. 1>&2
  echo           Re-extract the archive, keeping its directory layout intact. 1>&2
  exit /b 2
)
if not exist "%JVMAUDIT_JAR%" (
  echo jvmaudit: %JVMAUDIT_JAR% is missing. Re-extract the archive. 1>&2
  exit /b 2
)

"%JVMAUDIT_JAVA%" -jar "%JVMAUDIT_JAR%" %*
exit /b %ERRORLEVEL%
