# et-otp-v2

[![CI](https://github.com/DISK0401/et-otp-v2/actions/workflows/ci.yml/badge.svg)](https://github.com/DISK0401/et-otp-v2/actions/workflows/ci.yml)
[![License: GPL-2.0-only](https://img.shields.io/github/license/DISK0401/et-otp-v2)](LICENSE)
[![Java 25+](https://img.shields.io/badge/Java-25%2B-blue)](https://adoptium.net/)
[![Website](https://img.shields.io/badge/website-disk0401.github.io-informational)](https://disk0401.github.io/et-otp-v2/ja/)
[![Last commit](https://img.shields.io/github/last-commit/DISK0401/et-otp-v2)](https://github.com/DISK0401/et-otp-v2/commits/develop)

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
- 検索欄に名前の一部を入力すると、そのサービスだけを表示します。上下キーで選んで Enter を押すと、コードをコピーします。「編集」のメニューで、サービスの並び順も変えられます。
- `--code <サービス名>` を付けて起動すると、ウィンドウを開かずにコードを出力します。バッチファイルなどに組み込めます。

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

## コマンドラインでコードを取得する

`--code <サービス名>` を付けて起動すると、ウィンドウを開かずに、そのサービスの現在のコードを出力します。バッチファイルなどで、2 段階認証のコードを入力する処理に組み込めます。Windows のポータブル版では、次のように使います。

```bat
for /f "delims=" %%c in ('call "C:\tools\et-otp-v2\et-otp-v2.bat" --code AWS') do set MFA_CODE=%%c
if not defined MFA_CODE exit /b 1
```

- サービス名には、et-otp-v2 に表示している名前を指定します。同じ名前がない場合は、大文字と小文字の違いを無視して探します。`--list` を付けると、登録しているサービスの名前を出力します。
- パスワードで保護している場合は、パスワードを聞かれます。入力したパスワードは画面に表示しません。Windows のユーザーで保護している場合は、何も聞かずにコードを出力します。
- コードが 3 秒以内に変わる場合は、次のコードに変わるまで待ってから出力します。
- 標準出力にはコードだけを出力します。パスワードの問い合わせとエラーは、標準エラー出力に出します。終了コードは、成功が 0、失敗が 1、オプションの誤りが 2 です。
- 設定ファイルは読むだけで書き換えないので、ウィンドウを開いたままでも使えます。ただし、et-otp 1.x と et-otp-v2 2.0 の形式のファイルは読めないため、先にウィンドウで一度開いて新しい形式に変換してください。
- macOS のポータブル版では `sh et-otp-v2.command --code AWS`、Linux のポータブル版では `sh et-otp-v2.sh --code AWS`、JAR だけを使う場合は `java -jar et-otp-v2-<バージョン>.jar --code AWS` のように実行します。

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
