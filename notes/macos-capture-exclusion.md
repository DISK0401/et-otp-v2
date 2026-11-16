# macOS で画面共有から除外する作業の手順書

Issue #24 の「画面共有やスクリーンショットに映さない」を macOS でも使えるようにするための手順書です。Mac で作業する人（または Mac 上の AI エージェント）が、この文書だけで作業を始められるように書いています。

## 背景

- Windows では PR #40 で実装しました。`SetWindowDisplayAffinity`（`WDA_EXCLUDEFROMCAPTURE`）を FFM API で呼び、このプログラムのすべてのウィンドウに指定しています。実装は `src/main/java/net/disk0401/etotp/CaptureExclusion.java` です。
- 今は Windows 以外では `CaptureExclusion.isSupported()` が false を返し、設定画面の「表示」タブにこの設定を表示しません。
- macOS では、`NSWindow` の `sharingType` を `NSWindowSharingNone`（0）にすると、ウィンドウを画面の取得から除外できるとされています。ただし、macOS 15 以降の ScreenCaptureKit はこの指定を無視するという報告があります。この点は確かめていません。この作業では、まずこの点を確かめます。

## 守ること

- 依存ライブラリは増やしません。Objective-C のランタイムは、JDK の FFM API（`java.lang.foreign`）で直接呼びます。
- JDK の内部 API（`sun.lwawt.*` など）と、Apache-2.0 のライブラリは使いません。このプロジェクトのライセンスは GPL-2.0-only で、Apache-2.0 のライブラリとは両立しないためです。
- 新しいソースファイルには、既存のファイルと同じ GPL-2.0-only のヘッダーを付けます。
- コミットに `Co-Authored-By` は付けません。
- PR は `develop` に向けて作り、本文に `Refs #24` と書きます。

## 1. 準備

1. JDK 25 を入れます（Amazon Corretto 25 を推奨）。Maven も必要です。
2. リポジトリを取得して、このブランチに切り替えます。

   ```sh
   git clone https://github.com/DISK0401/et-otp-v2.git
   cd et-otp-v2
   git switch feature/issue-24-macos
   ```

3. ビルドとテストが通ることを確かめます。

   ```sh
   mvn verify
   ```

4. 普段の設定ファイルを使わないように、テスト用のフォルダで起動します。

   ```sh
   mkdir -p /tmp/etotp-dev
   java -Duser.home=/tmp/etotp-dev -Detotp.config=/tmp/etotp-dev/accounts.properties \
        -jar target/et-otp-*.jar
   ```

   初期設定のウィザードで「パスワードで保護する」を選び、テスト用のサービスを「キーを入力...」で登録します（例：`JBSWY3DPEHPK3PXP`）。

## 2. 調査：`sharingType` が効くかを確かめる

実装の前に、`sharingType` を `NSWindowSharingNone` にしたウィンドウが、どの取得方法で映らなくなるかを確かめます。結果によって、実装するかどうかが決まります。

### 2.1 確かめるための小さなプログラム

Swing のウィンドウを開き、アプリのすべての `NSWindow` に `sharingType = 0` を指定するプログラムを作ります。要点は次のとおりです。

- Objective-C のランタイムは `/usr/lib/libobjc.A.dylib` にあります。`objc_getClass`、`sel_registerName`、`objc_msgSend` を FFM で呼びます。
- `objc_msgSend` は、呼ぶメッセージごとに引数と戻り値の型を合わせた `FunctionDescriptor` で、別々の `MethodHandle` を作ります。Apple シリコン（arm64）では、可変長引数の関数として呼ぶと正しく動きません。
- 呼ぶメッセージは次のとおりです。
  - `[NSApplication sharedApplication]` でアプリを得る（戻り値は `ADDRESS`）
  - `[app windows]` でウィンドウの配列を得る
  - `[array count]`（戻り値は `JAVA_LONG`）と `[array objectAtIndex:i]` で、1 つずつ取り出す
  - `[window setSharingType:0]`（引数は `JAVA_LONG`、戻り値なし）で指定する
  - `[window sharingType]`（戻り値は `JAVA_LONG`）で読み戻す
- AppKit は、メインスレッド（AppKit のスレッド）以外から触ってはいけません。Swing の EDT はメインスレッドではありません。`dispatch_async_f(&_dispatch_main_q, null, 関数)` でメインスレッドに処理を渡し、処理は FFM の upcall stub で作ります。`_dispatch_main_q` は libSystem にある変数で、`dispatch_get_main_queue()` はこの変数のアドレスを返すマクロです。
- `dispatch_sync_f` は使いません。AppKit のスレッドが EDT を待っているときに、互いに待ち続けるおそれがあるためです。
- `NSWindow` は AWT のウィンドウとの対応を探さずに、アプリのすべてのウィンドウに指定します。Windows の実装も同じ考え方です。

### 2.2 確かめる取得方法

macOS のバージョン（`sw_vers`）ごとに、次の方法でウィンドウが映るかを確かめ、表にまとめます。

| 取得方法 | 確かめ方 |
|---|---|
| スクリーンショット（全画面） | ⌘⇧3 |
| スクリーンショット（ウィンドウ） | ⌘⇧4 のあと Space |
| スクリーンショットツール | ⌘⇧5 |
| `screencapture` コマンド | `screencapture /tmp/s.png` |
| QuickTime Player の画面収録 | ファイル → 新規画面収録 |
| オンライン会議の画面共有 | Zoom、Teams、Google Meet（ブラウザー）で画面全体を共有し、相手側の表示を見る |

記録する内容は次のとおりです。

- 映らない（後ろにあるものが映る）か、黒く塗られるか、そのまま映るか
- macOS のバージョン（14、15、26 など）
- Intel か Apple シリコンか

### 2.3 判断

- 主な取得方法（スクリーンショットと、Zoom・Teams・Meet の画面共有）で映らなければ、3 の実装に進みます。
- macOS 15 以降の画面共有で映ってしまう場合は、実装しません。調べた結果の表を Issue #24 にコメントして、作業を終えます。効かない場合が多い設定を表示すると、利用者が守られていると誤解するためです。

## 3. 実装

### 3.1 変更するファイル

- `CaptureExclusion.java`
  - macOS の処理を加えます。`isSupported()` は、macOS（`os.name` が `Mac` で始まる）でも true を返すようにします。
  - 2.3 で効かなかった macOS のバージョンがある場合は、そのバージョンでは false を返します。
- `apply(boolean exclude)`
  - macOS では、`sharingType` に `NSWindowSharingNone`（0）か、元の値の `NSWindowSharingReadOnly`（1）を指定します。
  - 戻り値は、指定できたかどうかです。メインスレッドへ渡すため結果をすぐには得られないので、指定したあとに読み戻して確かめる方法を検討してください。
- `affinity(long)` は Windows 専用のままにします。macOS で読み戻す処理は、テストと確認用に別のメソッドで作ります。
- `ScreenCapture.windowsSupported()` は Windows だけを表します。macOS の判定に流用しないでください。

### 3.2 呼ばれる場所

`GUI.applyCaptureExclusion()` は、ウィンドウを開いたとき（`WINDOW_OPENED`）と、1 秒ごとのタイマーから呼ばれます。macOS でも同じ呼び出しで動くようにします。1 秒ごとの呼び出しが重くないか（メインスレッドを長く止めないか）も確かめてください。

### 3.3 文書

- メッセージ（`messages.properties`、`messages_ja.properties`）の `display.capture.note` と `display.capture.failed` は、今は「Windows 10 バージョン 2004 以降」と書いています。macOS で使える条件も加えます。
- README（日・英）と `docs/index.html`、`docs/ja/index.html` には「macOS と Linux では、この設定は表示しません」と書いています。対応した macOS のバージョンに合わせて直します。

## 4. 確認

1. `mvn verify` が通ること。
2. 1 の手順で起動し、「表示」タブに「画面共有やスクリーンショットに映さない」が表示され、初期設定でオンになっていること。
3. メインのウィンドウ、設定画面、ダイアログ（名前の変更など）、ウィンドウの外に出るメニューが、2.2 の方法で映らないこと。
4. 設定をオフにすると、すべてのウィンドウがまた映ること。
5. オンのまま「画面の QR コードを読み取る」で、ほかのウィンドウの QR コードを読み取れること。
6. ロック（⌘L）、バックアップの書き出しと復元、設定の変更など、ほかの操作が今までどおり動くこと。

## 5. 報告

- 2.2 の結果の表と、実装した内容を PR の本文に書きます。
- 確かめられなかったこと（試していない macOS のバージョンや会議アプリなど）も、その旨を書きます。
