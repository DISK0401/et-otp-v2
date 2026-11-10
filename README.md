# et-otp-v2

[![CI](https://github.com/DISK0401/et-otp-v2/actions/workflows/ci.yml/badge.svg)](https://github.com/DISK0401/et-otp-v2/actions/workflows/ci.yml)
[![License: GPL-2.0-only](https://img.shields.io/github/license/DISK0401/et-otp-v2)](LICENSE)
[![Java 25+](https://img.shields.io/badge/Java-25%2B-blue)](https://adoptium.net/)
[![Website](https://img.shields.io/badge/website-disk0401.github.io-informational)](https://disk0401.github.io/et-otp-v2/ja/)
[![Last commit](https://img.shields.io/github/last-commit/DISK0401/et-otp-v2)](https://github.com/DISK0401/et-otp-v2/commits/main)

[English](README.en.md) | 日本語

Java (Swing) で作ったデスクトップ用の TOTP ソフトトークンです。

本リポジトリは Bernd Eckenfels 氏の [ecki/et-otp](https://github.com/ecki/et-otp) を引き継いだフォークです。上流は 2020 年以降更新されていないため、こちらで保守と新機能の追加を続けます。上流のコミット履歴はそのまま残しています。

使い方は [et-otp-v2 のページ](https://disk0401.github.io/et-otp-v2/ja/)にまとめています。

## 機能

- RFC 6238 (TOTP) に基づいてワンタイムパスワードを生成し、次のコードと残り時間もあわせて表示します。SHA-1、SHA-256、SHA-512、6〜8 桁、任意の更新間隔に対応しています。
- 複数のサービスを登録できます。コードはクリックするか「コピー」を押すと、クリップボードにコピーできます。
- 画面に表示した QR コードを読み取ってサービスを登録できます。Windows では、ほかのウィンドウの裏にあるウィンドウからも読み取れます。QR コードの画像ファイルや、キーの入力でも登録できます。
- 登録したサービスは暗号化して `.et-otp.properties` に保存します。Windows では、Windows のユーザーで保護する方法（DPAPI、パスワード不要）と、パスワードで保護する方法（PBKDF2-HMAC-SHA256 と AES-256-GCM）を選べます。macOS と Linux では、パスワードで保護します。
- 画面は日本語と英語に対応しています。OS の言語が日本語なら日本語、それ以外なら英語で表示します。設定画面の「全般」タブで切り替えることもできます。
- 設定ファイルがない状態で起動すると、初期設定のウィザードが開きます。保護方法と設定ファイルの保存先を選べるほか、Windows ではデスクトップにショートカットを作れます。すでにある設定ファイルを選んで、そのまま使うこともできます。
- 終了したときのウィンドウの位置を、次に起動したときに使います。

## 必要な環境

- JAR だけを使う場合は、Java 25 以降が必要です。ポータブル版には Java を同梱しています。
- ビルドには Maven 3.9.0 以降が必要です。

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

設定はホームフォルダの `.et-otp.properties` に保存されます。保存先のフォルダとファイル名は、初期設定のウィザードの「変更...」か、設定画面の「全般」タブの「移動...」で変えられます。変えた保存先はホームフォルダの `.et-otp-location.properties` に記録し、次に起動したときもその保存先を使います。保存先を変えていない場合、起動したときの作業フォルダに `.et-otp.properties` があれば、そちらを使います（et-otp 1.x と同じ動きです）。ファイルの場所は、ヘルプ → バージョン情報（英語表示では Help → About）で確認できます。言語とウィンドウの位置は、同じフォルダの `.et-otp-ui.properties` に保存されます。

## et-otp 1.x と et-otp-v2 2.0 からの移行

元の et-otp と et-otp-v2 2.0 の `.et-otp.properties` はそのまま読めます。最初にロックを解除したとき、古いファイルを `.et-otp.properties.v1.bak`（et-otp 1.x の場合）または `.et-otp.properties.v2.bak`（et-otp-v2 2.0 の場合）にコピーしてから、複数のサービスを登録できる新しい形式で書き直します。注意点は[移行の説明](https://disk0401.github.io/et-otp-v2/ja/#upgrade)を見てください。

## 上流からの主な変更点

- ビルド対象を Java 25 に上げ、Maven プラグインを新しいバージョンにしました。
- パッケージ名を `net.disk0401.etotp` に変えました。
- 複数のサービスの登録と、QR コードの読み取りに対応しました。
- 暗号化の方式を PBKDF2-HMAC-SHA256 と AES-256-GCM に変え、古い形式のファイルを移行できるようにしました。
- 鍵などの情報を標準出力に書き出していた処理を削除しました。
- Java を同梱したポータブル版を、リリースのたびに作るようにしました。
- CI を Travis CI から GitHub Actions に移しました。
- LICENSE ファイルを追加しました。

## ライセンス

[GNU General Public License v2.0 only](LICENSE)

Copyright (C) 2012-2020 Bernd Eckenfels（オリジナル作者）  
Copyright (C) 2026 DISK0401（フォークでの変更）

QR コードの解析には、Daniel Beer 氏の [quirc](https://github.com/dlbeer/quirc)（Copyright (C) 2010-2012 Daniel Beer、ISC ライセンス）を Java に移植したコードを使っています。ライセンスの全文は、`src/main/java/net/disk0401/etotp/qr` のソースファイルと、jar の中の `META-INF/LICENSE-quirc.txt` にあります。
