@echo off
rem et-otp-v2 portable launcher (Windows).
rem Starts the bundled Java runtime; nothing is installed.
rem
rem To keep the settings next to this file (e.g. on a USB drive), remove "rem " from the line
rem "rem set ETOTP_OPTS=..." below.
rem
rem With an option, the code is written to the console instead of opening the window, for example
rem in a batch file:
rem   for /f "delims=" %%c in ('call "C:\et-otp-v2\et-otp-v2.bat" --code AWS') do set MFA_CODE=%%c
rem Run "et-otp-v2.bat --help" for the options.
setlocal
set "ETOTP_OPTS="
rem set ETOTP_OPTS=-Detotp.config="%~dp0et-otp.properties"
if not "%~1"=="" goto console
start "" "%~dp0runtime\bin\javaw.exe" %ETOTP_OPTS% -jar "%~dp0et-otp-v2.jar"
exit /b 0
:console
"%~dp0runtime\bin\java.exe" %ETOTP_OPTS% -jar "%~dp0et-otp-v2.jar" %*
