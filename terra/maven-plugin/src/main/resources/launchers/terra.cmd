@echo off
set "terra_app_home=%~dp0.."
if defined JAVA_HOME (set "terra_java=%JAVA_HOME%\bin\java.exe") else (set "terra_java=java")
"%terra_java%" --enable-preview -cp "%terra_app_home%\lib\*" org.pragmatica.terra.launcher.TerraMain "%terra_app_home%\application" %*
