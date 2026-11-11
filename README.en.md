# et-otp-v2

[![CI](https://github.com/DISK0401/et-otp-v2/actions/workflows/ci.yml/badge.svg)](https://github.com/DISK0401/et-otp-v2/actions/workflows/ci.yml)
[![License: GPL-2.0-only](https://img.shields.io/github/license/DISK0401/et-otp-v2)](LICENSE)
[![Java 25+](https://img.shields.io/badge/Java-25%2B-blue)](https://adoptium.net/)
[![Website](https://img.shields.io/badge/website-disk0401.github.io-informational)](https://disk0401.github.io/et-otp-v2/)
[![Last commit](https://img.shields.io/github/last-commit/DISK0401/et-otp-v2)](https://github.com/DISK0401/et-otp-v2/commits/main)

English | [日本語](README.md)

A TOTP soft token for the desktop, written in Java (Swing).

This repository is a maintained continuation of [ecki/et-otp](https://github.com/ecki/et-otp) by Bernd Eckenfels. The original project has not been updated since 2020, so maintenance and new features continue here. The upstream commit history is preserved.

See the [et-otp-v2 website](https://disk0401.github.io/et-otp-v2/) for the user guide.

## Features

- Generates one-time passwords according to RFC 6238 (TOTP) and shows the next code and the remaining time. SHA-1, SHA-256 and SHA-512, 6 to 8 digits and any period are supported.
- Holds any number of services. Click a code or press Copy to copy it to the clipboard.
- Registers a service by reading the QR code shown on the screen; on Windows also from a window behind other windows. A QR code image file or the key typed in also works.
- Stores the services encrypted in `.et-otp.properties`. On Windows, choose between protection for your Windows user (DPAPI, no password) and a password (PBKDF2-HMAC-SHA256 and AES-256-GCM); on macOS and Linux a password is used.
- The user interface is in English and Japanese. It is shown in Japanese if the language of the OS is Japanese, else in English, and can be switched on the General tab of the settings.
- When started without a configuration file, a setup wizard opens. It asks for the protection and the location of the configuration file and, on Windows, can create a desktop shortcut. You can also choose an existing configuration file and keep using it.
- The window opens where it was when the program was last closed.
- Type part of a name into the search field to show only those services; choose one with the up and down keys and press Enter to copy its code. The edit menu changes the order of the services.
- Started with `--code <name>`, it writes the code without opening the window, for batch files and scripts.

## Requirements

- Java 25 or later to run the plain JAR. The portable editions include Java.
- Maven 3.9.0 or later to build.

## Download and run

Download the portable edition for your OS from the [Releases page](https://github.com/DISK0401/et-otp-v2/releases). It bundles a Java runtime built from Amazon Corretto 25 (LTS), so no installation and no administrator rights are needed.

| OS | File | How to start |
|---|---|---|
| Windows | `et-otp-v2-<version>-windows-x64-portable.zip` | Extract it and double-click `et-otp-v2.bat`. |
| macOS (Apple silicon) | `et-otp-v2-<version>-macos-arm64-portable.tar.gz` | Extract it and double-click `et-otp-v2.command`. If it does not start, run `sh et-otp-v2.command` in Terminal. |
| Linux | `et-otp-v2-<version>-linux-x64-portable.tar.gz` | Extract it and run `sh et-otp-v2.sh` in a terminal. |

The bundled Java executables are Amazon's original Corretto binaries with Amazon's signatures. On a company or organization computer, check your organization's software policy first.

## Build and run

```sh
mvn verify
java -jar target/et-otp-<version>.jar
```

Replace `<version>` with the `<version>` in pom.xml (for example `2.0.0`).

The settings are saved to `.et-otp.properties` in your home folder. To use another folder or file name, press "Change..." in the setup wizard, or "Move..." on the General tab of the settings. The chosen location is remembered in `.et-otp-location.properties` in your home folder and used on the next start. Without a chosen location, a `.et-otp.properties` in the working directory is used if it exists (same as et-otp 1.x). Help → About shows the full path. The language and the window position are saved to `.et-otp-ui.properties` in the same folder.

## Get a code on the command line

Started with `--code <name>`, et-otp-v2 writes the current code of the service without opening the window, so a batch file or script can use it where a 2-step verification code is needed. With the portable edition for Windows:

```bat
for /f "delims=" %%c in ('call "C:\tools\et-otp-v2\et-otp-v2.bat" --code AWS') do set MFA_CODE=%%c
if not defined MFA_CODE exit /b 1
```

- The name is the name shown in et-otp-v2. If no service has exactly this name, the case is ignored. `--list` writes the names of the registered services.
- If the services are protected with a password, you are asked for it; the password is not shown while you type. With protection for your Windows user, the code is written without a question.
- If the code changes within 3 seconds, the next code is waited for and written.
- Only the code goes to the standard output; the password prompt and errors go to the standard error output. The exit code is 0 when done, 1 when failed and 2 for wrong options.
- The configuration file is only read, so this also works while the window is open. Files in the format of et-otp 1.x and et-otp-v2 2.0 cannot be read: open them once in the window to convert them first.
- With the portable edition for macOS run `sh et-otp-v2.command --code AWS`, for Linux `sh et-otp-v2.sh --code AWS`, with the plain JAR `java -jar et-otp-v2-<version>.jar --code AWS`.

## Upgrading from et-otp 1.x and et-otp-v2 2.0

et-otp-v2 reads the `.et-otp.properties` of the original et-otp and of et-otp-v2 2.0. On the first unlock it copies the old file to `.et-otp.properties.v1.bak` (et-otp 1.x) or `.et-otp.properties.v2.bak` (et-otp-v2 2.0) and then re-writes it in the new format, which holds several services. See [Upgrading from et-otp 1.x](https://disk0401.github.io/et-otp-v2/#upgrade) for details.

## Main changes from upstream

- Targets Java 25 and uses current Maven plugins.
- Several services and registration by QR code.
- Package renamed to `net.disk0401.etotp`.
- Encryption changed to PBKDF2-HMAC-SHA256 and AES-256-GCM, with migration of old files.
- Removed debug output that printed key material to stdout.
- Portable editions with a bundled Java runtime are built for every release.
- CI moved from Travis CI to GitHub Actions.
- Added the LICENSE file.

## License

[GNU General Public License v2.0 only](LICENSE)

Copyright (C) 2012-2020 Bernd Eckenfels (original author)  
Copyright (C) 2026 DISK0401 (changes in this fork)

The QR code decoder is a Java port of [quirc](https://github.com/dlbeer/quirc) by Daniel Beer (Copyright (C) 2010-2012 Daniel Beer, ISC license). The full license text is in the source files in `src/main/java/net/disk0401/etotp/qr` and in `META-INF/LICENSE-quirc.txt` in the jar.
