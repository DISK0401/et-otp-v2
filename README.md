# et-otp-v2

[![CI](https://github.com/DISK0401/et-otp-v2/actions/workflows/ci.yml/badge.svg)](https://github.com/DISK0401/et-otp-v2/actions/workflows/ci.yml)

Java (Swing) 製の TOTP ソフトウェアトークン。

本リポジトリは、Bernd Eckenfels 氏による [ecki/et-otp](https://github.com/ecki/et-otp)（[旧ホームページ](https://ecki.github.io/et-otp/)）を引き継いだフォークです。
上流は 2020 年以降更新されていないため、メンテナンスと新機能の追加をこちらで行います。上流のコミット履歴はそのまま保持しています。

> This is a maintained continuation of [ecki/et-otp](https://github.com/ecki/et-otp) by Bernd Eckenfels.

## 機能

- RFC 6238 (TOTP) / RFC 4226 (HOTP) に基づくワンタイムパスワードの生成
- Base32 形式のシークレットに対応（Google, Amazon, Dropbox, Microsoft などの 2 段階認証で利用可能）
- シークレットはパスワードで暗号化して `.et-otp.properties` に保存
- 生成したコードをクリップボードへコピー

## 必要環境

- Java 17 以上
- ビルドには Maven 3.6.3 以上

## ビルドと実行

```sh
mvn verify
java -jar target/et-otp-2.0-SNAPSHOT.jar
```

設定ファイル `.et-otp.properties` はカレントディレクトリに作成されます。

## 上流からの主な変更点

- ビルド対象を Java 17 に更新し、Maven プラグインを最新化
- CI を Travis CI から GitHub Actions に移行
- LICENSE ファイルを追加

## ライセンス

[GNU General Public License v2.0 only](LICENSE)

Copyright (C) 2012-2020 Bernd Eckenfels（オリジナル作者）  
Copyright (C) 2026 Daisuke Tomioka（フォークでの変更）
