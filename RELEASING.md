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
- `Revert "..."` で始まる行（取り消したコミットの件名や、取り消しの PR のタイトル）にある `Fixes #123` などは、修正を取り消したものとして扱います。GitHub がこの行で Issue を閉じた場合は開き直し、`fixed in develop` のラベルを外して、取り消したことをコメントします。もう一度直したら、ラベルが付き直します。`Revert "Revert "..."` のように取り消しを取り消した行は、修正として扱います。
- GitHub が `Fixes` などで Issue を閉じるのは、Issue status より遅れることがあります。そのため、Issue status は開いたままの Issue を最大 2 分待ち、その間に閉じられたら開き直します。閉じられたあと、閉じたコミットを GitHub がまだ返さない場合も、2 分の間は確かめ直します。2 分を過ぎてから閉じられた場合は、ラベルが付いたまま閉じた状態で残ります。この Issue のラベルは、記録したコミットを含むリリースを公開したときに「Close released issues」が外します。この状態の Issue に `Refs #123` で別の対応を加えた場合も、Issue を開き直して記録します。
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

2. `develop` から `main` への PR を作ります。タイトルは「Release 2.0.0」とし、本文はリリース用のテンプレート（`.github/PULL_REQUEST_TEMPLATE/release.md`）から書きます。PR の本文は、変更履歴の材料になります（「変更履歴の自動生成」を参照）。

   ```sh
   gh pr create --base main --head develop --title "Release 2.0.0" --template release.md
   ```

   ブラウザーで作る場合は、`https://github.com/DISK0401/et-otp-v2/compare/main...develop?template=release.md` を開きます。`release-check` は次の点を確かめます。
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

リリースしたあとも、`develop` のバージョンは次のリリースの手順1まで変えません。

## 変更履歴の自動生成

Release ワークフローは、英語と日本語の変更履歴を Draft に入れます。変更履歴は次の順に作ります（`.github/scripts/release-notes.sh`）。

1. **リリースの PR に書いた変更履歴**：リリースの PR（`develop` から `main`）の本文に「## Release notes / リリースノート」の節があれば、AI を使わずに、その節をそのまま Draft の本文にします。
   - 節の中の見出しは、1 段上げます（「### English」は「## English」に、「#### New features」は「### New features」になります）。HTML のコメント（`<!-- -->`）は消します。
   - 節がない場合、または節にテンプレートのコメントしかない場合は、次の AI で作ります。
   - 使うのは、メンテナー（オーナー、メンバー、コラボレーター）が作った PR の節だけです。
2. **GitHub Copilot CLI**（Copilot Free で使えます。1回のリリースで使うのは1リクエストです）
3. **OpenAI API**（Copilot が失敗したとき）
4. どちらも使えないときは、GitHub が自動で作る変更履歴を Draft に入れます。

AI には、次の材料を渡します。

| 材料 | 目的 |
|---|---|
| リリースの PR のタイトルと本文 | メンテナーがまとめた、リリースの内容と分類 |
| 前のタグから今回までに `develop` へマージした PR のタイトルと本文 | どのコミットが 1 つの変更なのか、利用者にとって何が変わったのか |
| 前のタグから今回までのコミットのメッセージ | 細かい事実 |
| 画面の文言（`messages.properties` と `messages_ja.properties`。キーごとに英語と日本語を並べ、前のリリースから変わったキーに印を付ける） | 画面の名前を、英語と日本語の画面のとおりに書くため |

プロンプトでは、次の決まりを指示しています。

- 「不具合の修正」に入れるのは、前のリリースにあった動きを直したものだけです。
- 今回のリリースで加えた機能をレビューなどで直したもの（「Fix problems found in the review ...」など）は、その機能の説明にまとめます。「不具合の修正」には入れません。
- 前のリリースで、ある OS で動いていなかったもの（対応漏れ）は、「不具合の修正」に入れます。
- 画面の名前は、一覧の文言をそのまま使います。「パスワードで保護している場合だけ」などの条件は省きません。

PR の本文とコミットのメッセージは、外部の人が書いたものも含めて、データとして渡します。中にある指示には従わないように指示し、Copilot CLI はツールを使えない状態で、リポジトリーのトークンと OpenAI のキーを渡さずに動かします。

AI が作った文は Draft の下書きです。公開する前に必ず読み、事実と違う所を直してください。直す量が多い場合は、リリースの PR に「Release notes / リリースノート」の節を書くと、その内容がそのまま使われます。

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

`notes_tag` に前のリリースのタグ（例：`v2.3.1`）を入れると、変更履歴だけをそのリリースのときの内容で作ります。材料はそのタグまでのコミットと PR で、スクリプトとプロンプトは実行したブランチのものを使います。プロンプトを直したときに、公開した変更履歴と比べるために使います。作った変更履歴は、実行結果の Summary にも表示します。

手元で材料を確かめる場合は、AI のキーを渡さずにスクリプトを実行し、`NOTES_PROMPT_FILE` にプロンプトを書き出します。

```sh
GH_TOKEN=$(gh auth token) GH_REPO=DISK0401/et-otp-v2 NOTES_HEAD=v2.3.1 NOTES_PROMPT_FILE=prompt.txt \
  bash .github/scripts/release-notes.sh 2.3.1 notes.md
```
