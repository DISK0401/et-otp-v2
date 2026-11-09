@echo off
rem et-otp-v2 portable launcher (Windows).
rem Starts the bundled Java runtime; nothing is installed.
rem To keep the settings next to this file (e.g. on a USB drive), add:
rem   -Detotp.config="%~dp0et-otp.properties"
rem before -jar below.
start "" "%~dp0runtime\bin\javaw.exe" -jar "%~dp0et-otp-v2.jar" %*
