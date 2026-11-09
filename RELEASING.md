# リリースと運用のルール

この文書は et-otp-v2 のメンテナー向けです。ブランチの使い方と、リリースの手順を決めています。

## ブランチ

| ブランチ | 役割 | 更新の方法 |
|---|---|---|
| `main` | リリースしたコード | `develop` からの PR だけでマージする。直接の push はできない |
| `develop` | 開発の本流（デフォルトブランチ） | 作業用ブランチからの PR、または直接の push |
| 作業用ブランチ | 機能の追加や修正 | `develop` から作り、`develop` へ PR を出す |

- Dependabot の PR は `develop` に向けて作られます。
- `main` には次のルール（リポジトリのルールセット「main protection」）を設定しています。
  - PR を通さない更新、強制 push、ブランチの削除はできません。管理者も例外にしていません。
  - CI（`build (17)`、`build (21)`）とリリースの確認（`release-check`）が通らないとマージできません。
  - マージの方法は「Create a merge commit」だけです。squash にすると `develop` と `main` の履歴が分かれ、次のリリースでコンフリクトするためです。

## リリースの流れ

1. `develop` で、リリースするバージョンを決めて pom.xml に設定します。

   ```sh
   mvn versions:set -DnewVersion=2.0.0 -DgenerateBackupFiles=false
   git commit -am "Release 2.0.0"
   git push
   ```

2. `develop` から `main` への PR を作ります。`release-check` は次の点を確かめます。
   - PR の元が `develop` であること
   - バージョンが `2.0.0` のような形で、`-SNAPSHOT` が付いていないこと
   - 同じバージョンのタグがまだないこと
3. CI が通ったら、「Create a merge commit」でマージします。
4. マージすると Release ワークフローが動き、Draft のリリースを作ります。Draft には次のファイルが付きます。

   | ファイル | 対象 |
   |---|---|
   | `et-otp-v2-<バージョン>-windows-x64.zip` | Windows。展開して `et-otp-v2.exe` を起動する。Java は不要 |
   | `et-otp-v2-<バージョン>-macos-arm64.dmg` | macOS（Apple シリコン）。Java は不要 |
   | `et-otp-v2-<バージョン>-linux-x64.tar.gz` | Linux。Java は不要 |
   | `et-otp-v2-<バージョン>.jar` | Java 17 以降が入っている環境 |
   | `SHA256SUMS.txt` | チェックサム |

5. Draft の変更履歴（英語と日本語）を読み、必要なら直してから「Publish release」で公開します。公開したときに、マージしたコミットに `v<バージョン>` のタグが付きます。
6. `develop` のバージョンを次の開発用バージョンに上げます。

   ```sh
   mvn versions:set -DnewVersion=2.0.1-SNAPSHOT -DgenerateBackupFiles=false
   git commit -am "Start 2.0.1 development"
   git push
   ```

## 変更履歴の自動生成

Release ワークフローは前のリリースのタグから今回までのコミットを AI に渡し、英語と日本語の変更履歴を作ります。AI は次の順に試します。

1. GitHub Copilot CLI（Copilot Free で使えます。1回のリリースで使うのは1リクエストです）
2. OpenAI API（Copilot が失敗したとき）
3. どちらも使えないときは、GitHub が自動で作る変更履歴を Draft に入れます。

AI が作った文は Draft の下書きです。公開する前に必ず読み、事実と違う所を直してください。

### 必要な設定

リポジトリの Settings → Secrets and variables → Actions に、次の値を登録します。

| 種類 | 名前 | 内容 |
|---|---|---|
| Secret | `COPILOT_GITHUB_TOKEN` | Copilot Requests の権限だけを持つ fine-grained PAT |
| Secret | `OPENAI_API_KEY` | OpenAI の API キー（予備） |
| Variable（任意） | `COPILOT_MODEL` | Copilot で使うモデル。未設定なら Copilot の既定のモデル |
| Variable（任意） | `OPENAI_MODEL` | OpenAI で使うモデル。未設定なら `gpt-5-mini` |

`COPILOT_GITHUB_TOKEN` は次の手順で作ります。

1. GitHub の Settings → Developer settings → Personal access tokens → Fine-grained tokens で「Generate new token」を押します。
2. Resource owner に自分のアカウントを選びます。Repository access は「Public repositories」で構いません。
3. Permissions の Account タブで「Copilot Requests」だけを追加します。
4. 有効期限を決めて作成し、表示されたトークンを Secret に登録します。期限が切れる前に作り直してください。

## 試しに動かす

Release ワークフローは、Actions の画面の「Run workflow」からも実行できます。`dry_run` を有効にしたまま実行すると、リリースは作らずにパッケージと変更履歴だけを作ります。`develop` のバージョンに `-SNAPSHOT` が付いていても実行できます。作ったファイルは実行結果の Artifacts からダウンロードできます。
