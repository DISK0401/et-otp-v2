# et-otp-v2

[![CI](https://github.com/DISK0401/et-otp-v2/actions/workflows/ci.yml/badge.svg)](https://github.com/DISK0401/et-otp-v2/actions/workflows/ci.yml)
[![License: GPL-2.0-only](https://img.shields.io/github/license/DISK0401/et-otp-v2)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-blue)](https://adoptium.net/)
[![Website](https://img.shields.io/badge/website-disk0401.github.io-informational)](https://disk0401.github.io/et-otp-v2/)
[![Last commit](https://img.shields.io/github/last-commit/DISK0401/et-otp-v2)](https://github.com/DISK0401/et-otp-v2/commits/main)

English | [日本語](README.md)

A TOTP soft token for the desktop, written in Java (Swing).

This repository is a maintained continuation of [ecki/et-otp](https://github.com/ecki/et-otp) by Bernd Eckenfels. The original project has not been updated since 2020, so maintenance and new features continue here. The upstream commit history is preserved.

See the [et-otp-v2 website](https://disk0401.github.io/et-otp-v2/) for the user guide.

## Features

- Generates one-time passwords according to RFC 6238 (TOTP) / RFC 4226 (HOTP).
- Accepts Base32 secrets as used by the two-step verification of Google, Amazon, Dropbox, Microsoft and others.
- Stores the secret encrypted with a password in `.et-otp.properties` (PBKDF2-HMAC-SHA256 and AES-256-GCM).
- Copies the generated code to the clipboard.

## Requirements

- Java 17 or later to run.
- Maven 3.6.3 or later to build.

## Build and run

```sh
mvn verify
java -jar target/et-otp-2.0-SNAPSHOT.jar
```

The configuration file `.et-otp.properties` is created in the current working directory.

## Upgrading from et-otp 1.x

et-otp-v2 reads the `.et-otp.properties` of the original et-otp. On the first unlock it copies the old file to `.et-otp.properties.v1.bak` and then re-writes it in the new format. See [Upgrading from et-otp 1.x](https://disk0401.github.io/et-otp-v2/#upgrade) for details.

## Main changes from upstream

- Targets Java 17 and uses current Maven plugins.
- Package renamed to `net.disk0401.etotp`.
- Encryption changed to PBKDF2-HMAC-SHA256 and AES-256-GCM, with migration of old files.
- Removed debug output that printed key material to stdout.
- CI moved from Travis CI to GitHub Actions.
- Added the LICENSE file.

## License

[GNU General Public License v2.0 only](LICENSE)

Copyright (C) 2012-2020 Bernd Eckenfels (original author)  
Copyright (C) 2026 DISK0401 (changes in this fork)
