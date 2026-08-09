@echo off
REM ----------------------------------------------------------------------------
REM JVM Wrapper (jvmw) — point d'entree Windows du systeme de mise a jour.
REM
REM Meme principe que mvnw.cmd pour Maven : au premier lancement, telecharge
REM et extrait un JRE Temurin 21 autonome dans .jvm\current\, verifie son
REM checksum SHA-256, puis delegue a java.exe. Les lancements suivants
REM reutilisent directement le runtime deja provisionne (aucun reseau).
REM
REM But : le poste client n'a besoin d'AUCUNE installation Java prealable.
REM Ce script est concu pour etre le "Programme" de la tache planifiee
REM Windows qui lance le Self-Updater (voir README.md, section "Planification
REM Windows") :
REM
REM   Programme  : C:\Apps\updater\jvmw.cmd
REM   Arguments  : -jar self-updater.jar self-updater-config.yml
REM
REM Une fois le runtime provisionne par ce script, le Self-Updater relance
REM lui-meme le System-Updater via ProcessBuilder — a ce niveau-la, pointer
REM "java-executable" vers .jvm\current\bin\java.exe directement dans
REM self-updater-config.yml (ProcessBuilder sur Windows n'execute pas les
REM scripts .cmd de facon fiable, contrairement a un .exe).
REM
REM Configuration : .jvm\wrapper\jvm-wrapper.properties (distributionUrl,
REM distributionSha256Sum)
REM ----------------------------------------------------------------------------

setlocal

set "JVM_PROJECTBASEDIR=%~dp0"
if "%JVM_PROJECTBASEDIR:~-1%"=="\" set "JVM_PROJECTBASEDIR=%JVM_PROJECTBASEDIR:~0,-1%"

set "JVM_WRAPPER_DIR=%JVM_PROJECTBASEDIR%\.jvm\wrapper"
set "JVM_HOME=%JVM_PROJECTBASEDIR%\.jvm\current"
set "JVM_PROPERTIES=%JVM_WRAPPER_DIR%\jvm-wrapper.properties"

if exist "%JVM_HOME%\bin\java.exe" goto run

if not exist "%JVM_PROPERTIES%" (
    echo. 1>&2
    echo Erreur : fichier introuvable %JVM_PROPERTIES% 1>&2
    echo. 1>&2
    exit /b 1
)

set "DISTRIBUTION_URL="
set "DISTRIBUTION_SHA256="
for /F "usebackq eol=# tokens=1,2 delims==" %%A in ("%JVM_PROPERTIES%") do (
    if "%%A"=="distributionUrl" set "DISTRIBUTION_URL=%%B"
    if "%%A"=="distributionSha256Sum" set "DISTRIBUTION_SHA256=%%B"
)

if "%DISTRIBUTION_URL%"=="" (
    echo Erreur : distributionUrl manquant dans %JVM_PROPERTIES% 1>&2
    exit /b 1
)

if not exist "%JVM_WRAPPER_DIR%" mkdir "%JVM_WRAPPER_DIR%"
set "JVM_ZIP=%JVM_WRAPPER_DIR%\jre-download.zip"
set "JVM_EXTRACT_DIR=%JVM_PROJECTBASEDIR%\.jvm\_extract"

echo Provisionnement du JRE (premier lancement) : %DISTRIBUTION_URL%
powershell -NoProfile -ExecutionPolicy Bypass -Command "&{[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%DISTRIBUTION_URL%' -OutFile '%JVM_ZIP%'}"
if errorlevel 1 (
    echo Erreur : echec du telechargement du JRE 1>&2
    exit /b 1
)

if not "%DISTRIBUTION_SHA256%"=="" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "&{$hash = (Get-FileHash '%JVM_ZIP%' -Algorithm SHA256).Hash.ToLower(); if ('%DISTRIBUTION_SHA256%' -ne $hash) { Write-Error 'Erreur : checksum SHA-256 invalide, le JRE telecharge est peut-etre corrompu ou compromis.'; exit 1 }}"
    if errorlevel 1 (
        del /q "%JVM_ZIP%" 2>nul
        exit /b 1
    )
)

echo Extraction...
if exist "%JVM_EXTRACT_DIR%" rmdir /s /q "%JVM_EXTRACT_DIR%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "&{Expand-Archive -Path '%JVM_ZIP%' -DestinationPath '%JVM_EXTRACT_DIR%' -Force}"
if errorlevel 1 (
    echo Erreur : echec de l'extraction du JRE 1>&2
    exit /b 1
)

del /q "%JVM_ZIP%" 2>nul

REM L'archive Temurin contient un unique dossier racine (ex: jdk-21.0.12+8-jre)
REM dont le nom exact varie selon la version -> on le deplace tel quel vers
REM .jvm\current.
set "JVM_EXTRACTED_ROOT="
for /D %%D in ("%JVM_EXTRACT_DIR%\*") do set "JVM_EXTRACTED_ROOT=%%D"

if "%JVM_EXTRACTED_ROOT%"=="" (
    echo Erreur : archive JRE inattendue, dossier racine introuvable 1>&2
    exit /b 1
)

if exist "%JVM_HOME%" rmdir /s /q "%JVM_HOME%"
move "%JVM_EXTRACTED_ROOT%" "%JVM_HOME%" >nul
rmdir /s /q "%JVM_EXTRACT_DIR%" 2>nul

if not exist "%JVM_HOME%\bin\java.exe" (
    echo Erreur : java.exe introuvable apres extraction dans %JVM_HOME% 1>&2
    exit /b 1
)

echo JRE provisionne : %JVM_HOME%

:run
"%JVM_HOME%\bin\java.exe" %*
exit /b %ERRORLEVEL%
