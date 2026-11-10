# et-otp-v2

[![CI](https://github.com/DISK0401/et-otp-v2/actions/workflows/ci.yml/badge.svg)](https://github.com/DISK0401/et-otp-v2/actions/workflows/ci.yml)
[![License: GPL-2.0-only](https://img.shields.io/github/license/DISK0401/et-otp-v2)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-blue)](https://adoptium.net/)
[![Website](https://img.shields.io/badge/website-disk0401.github.io-informational)](https://disk0401.github.io/et-otp-v2/ja/)
[![Last commit](https://img.shields.io/github/last-commit/DISK0401/et-otp-v2)](https://github.com/DISK0401/et-otp-v2/commits/main)

[English](README.en.md) | 日本語

Java (Swing) で作ったデスクトップ用の TOTP ソフトトークンです。

本リポジトリは Bernd Eckenfels 氏の [ecki/et-otp](https://github.com/ecki/et-otp) を引き継いだフォークです。上流は 2020 年以降更新されていないため、こちらで保守と新機能の追加を続けます。上流のコミット履歴はそのまま残しています。

使い方は [et-otp-v2 のページ](https://disk0401.github.io/et-otp-v2/ja/)にまとめています。

## 機能

- RFC 6238 (TOTP) / RFC 4226 (HOTP) に基づいてワンタイムパスワードを生成します。
- Google、Amazon、Dropbox、Microsoft などの2段階認証で使われる Base32 形式のシークレットに対応しています。
- シークレットはパスワードで暗号化して `.et-otp.properties` に保存します（PBKDF2-HMAC-SHA256 と AES-256-GCM）。
- 生成したコードをクリップボードにコピーできます。

## 必要な環境

- JAR だけを使う場合は、Java 17 以降が必要です。ポータブル版には Java を同梱しています。
- ビルドには Maven 3.6.3 以降が必要です。

## ダウンロードと起動

[Releases ページ](https://github.com/DISK0401/et-otp-v2/releases)から、使っている OS に合うポータブル版をダウンロードします。ポータブル版には Amazon Corretto 25（LTS）から作った Java の実行環境を同梱しているので、インストールや管理者の権限は要りません。

| OS | ファイル | 起動の方法 |
|---|---|---|
| Windows | `et-otp-v2-<バージョン>-windows-x64-portable.zip` | 展開して `et-otp-v2.bat` をダブルクリックします。 |
| macOS（Apple シリコン） | `et-otp-v2-<バージョン>-macos-arm64-portable.tar.gz` | 展開して `et-otp-v2.command` をダブルクリックします。起動できない場合は、ターミナルで `sh et-otp-v2.command` を実行してください。 |
| Linux | `et-otp-v2-<バージョン>-linux-x64-portable.tar.gz` | 展開して、ターミナルで `sh et-otp-v2.sh` を実行します。 |

同梱している Java の実行ファイルは、Amazon の署名が付いた Corretto のものをそのまま使っています。会社や組織のパソコンで使う場合は、ソフトウェアの利用について社内のルールを確かめてから使ってください。

## ビルドと実行

```sh
mvn verify
java -jar target/et-otp-<バージョン>.jar
```

`<バージョン>` は pom.xml の `<version>`（例: `2.0.0`）に置き換えてください。

設定はホームフォルダの `.et-otp.properties` に保存されます。起動したときの作業フォルダに `.et-otp.properties` がある場合は、そちらを使います（et-otp 1.x と同じ動きです）。ファイルの場所は Help → About で確認できます。

## et-otp 1.x からの移行

元の et-otp の `.et-otp.properties` はそのまま読めます。最初にロックを解除したとき、古いファイルを `.et-otp.properties.v1.bak` にコピーしてから、新しい形式で書き直します。注意点は[移行の説明](https://disk0401.github.io/et-otp-v2/ja/#upgrade)を見てください。

## 上流からの主な変更点

- ビルド対象を Java 17 に上げ、Maven プラグインを新しいバージョンにしました。
- パッケージ名を `net.disk0401.etotp` に変えました。
- 暗号化の方式を PBKDF2-HMAC-SHA256 と AES-256-GCM に変え、古い形式のファイルを移行できるようにしました。
- 鍵などの情報を標準出力に書き出していた処理を削除しました。
- Java を同梱したポータブル版を、リリースのたびに作るようにしました。
- CI を Travis CI から GitHub Actions に移しました。
- LICENSE ファイルを追加しました。

## ライセンス

[GNU General Public License v2.0 only](LICENSE)

Copyright (C) 2012-2020 Bernd Eckenfels（オリジナル作者）  
Copyright (C) 2026 DISK0401（フォークでの変更）
