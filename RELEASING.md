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
  - CI（`build (25)`）とリリースの確認（`release-check`）が通らないとマージできません。
  - マージの方法は「Create a merge commit」だけです。squash にすると `develop` と `main` の履歴が分かれ、次のリリースでコンフリクトするためです。

## Issue を閉じるタイミング

Issue は、対応した変更を含むリリースを公開したときに閉じます。`develop` にマージした時点では閉じません。

`develop` がデフォルトブランチなので、そのままでは PR の本文やコミットのメッセージに `Fixes #123` と書くと、`develop` にマージした時点で Issue が閉じてしまいます。これを避けるため、次の2つのワークフローを動かしています。

| ワークフロー | 動くとき | すること |
|---|---|---|
| Issue status（`issue-status.yml`） | `develop` への push（PR のマージを含む） | コミットのメッセージと、マージした PR のタイトルと本文で参照している Issue に `fixed in develop` のラベルを付け、取り込んだ `develop` のコミットをコメントに記録する。`Fixes` などで閉じた Issue は開き直す |
| Close released issues（`close-released-issues.yml`） | リリースの公開 | `fixed in develop` のラベルがある Issue のうち、記録したコミットが今回のタグに含まれるものを閉じ、リリースへのリンクをコメントする |

- PR の本文には `Refs #123` と書きます（PR のテンプレートに書き方があります）。`Fixes #123`、`Closes #123`、`Resolves #123` と書いた場合も同じように扱います。
- 1つの Issue に何度か対応した場合は、対応ごとに記録のコメントを追加します。Issue を閉じるのは、記録したすべてのコミットがリリースに含まれる場合だけです。
- 記録として扱うのは、ワークフローとメンテナー（オーナー、メンバー、コラボレーター）が書いたコメントだけです。HTML のコメント（`<!-- -->`）の中にある Issue の番号は読みません。
- 記録がないのに `fixed in develop` のラベルが付いている Issue は閉じずに、ワークフローの実行結果に警告を出します。リリースを確かめてから、手で閉じてください。
- ワークフローがうまく動かなかった場合は、Actions の画面で「Close released issues」をタグを指定して実行します。`dry_run` を有効にしたままなら、閉じる Issue の一覧を出すだけです。
- 「Close released issues」は、公開したタグのコミットにあるワークフローの定義で動きます。そのため、このワークフローを含まないリリース（2.1.2 以前）を公開しても動きません。自動で動くのは、2.1.2 の次のリリースからです。
- `develop` への push とリリースの公開がほぼ同時に起きると、Issue を閉じたあとに記録が増えることがあります。この場合は、閉じた直後にもう一度確かめて Issue を開き直します。ただし、完全に同時の場合は取りこぼす可能性がわずかに残ります。リリースを公開したあとは、`fixed in develop` のラベルが付いた Issue が残っていないかを確かめてください。

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
4. マージすると Release ワークフローが動き、Draft のリリースを作ります。Draft には次のファイルが付きます。ポータブル版は Amazon Corretto 25（LTS、2032年10月までサポート）から作った Java の実行環境を同梱しており、インストールや管理者の権限なしで使えます。

   | ファイル | 対象 |
   |---|---|
   | `et-otp-v2-<バージョン>-windows-x64-portable.zip` | Windows。展開して `et-otp-v2.bat` をダブルクリックする |
   | `et-otp-v2-<バージョン>-macos-arm64-portable.tar.gz` | macOS（Apple シリコン）。展開して `et-otp-v2.command` をダブルクリックする |
   | `et-otp-v2-<バージョン>-linux-x64-portable.tar.gz` | Linux。展開して `sh et-otp-v2.sh` を実行する |
   | `et-otp-v2-<バージョン>.jar` | Java 25 以降が入っている環境 |
   | `SHA256SUMS.txt` | チェックサム |

5. Draft の変更履歴（英語と日本語）を読み、必要なら直してから「Publish release」で公開します。公開したときに、マージしたコミットに `v<バージョン>` のタグが付きます。また、このリリースに含まれる Issue が閉じます（「Issue を閉じるタイミング」を参照）。
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
