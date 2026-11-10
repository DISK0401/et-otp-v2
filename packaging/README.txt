et-otp-v2 portable edition

No installation is needed. Extract the archive and start the launcher in the folder.
A Java runtime built from Amazon Corretto 25 (LTS) is bundled in the "runtime" folder.

How to start
- Windows: double-click et-otp-v2.bat
- macOS:   double-click et-otp-v2.command. If it does not start, run in Terminal: sh et-otp-v2.command
- Linux:   run in a terminal: sh et-otp-v2.sh

Settings
The settings are saved to .et-otp.properties in your home folder, and the language and window
position to .et-otp-ui.properties next to it. The location can be changed in the setup wizard
or with Settings > General > Move.... To carry the settings with this folder (e.g. on a USB
drive), follow the comment in the launcher file.

Codes for batch files and scripts
Started with --code <name>, the code of the service is written to the console instead of opening
the window, for example: et-otp-v2.bat --code AWS (macOS: sh et-otp-v2.command --code AWS,
Linux: sh et-otp-v2.sh --code AWS).
Run with --help for the options.

If you use this on a company or organization computer, check your organization's software
policy first.

The licenses of the bundled Java runtime are in runtime/legal. et-otp-v2 is licensed under the
GNU General Public License v2.0 only.
https://disk0401.github.io/et-otp-v2/

----------------------------------------------------------------------

et-otp-v2 ポータブル版

インストールは不要です。展開したフォルダの中のファイルを起動するだけで使えます。Amazon Corretto 25（LTS）から作った Java の実行環境を、runtime フォルダに同梱しています。

起動の方法
- Windows: et-otp-v2.bat をダブルクリックします。
- macOS: et-otp-v2.command をダブルクリックします。起動できない場合は、ターミナルで sh et-otp-v2.command を実行してください。
- Linux: ターミナルで sh et-otp-v2.sh を実行します。

設定の保存先
設定はホームフォルダの .et-otp.properties に、言語とウィンドウの位置は同じフォルダの .et-otp-ui.properties に保存されます。保存先は、初期設定のウィザードか、設定画面の「全般」タブの「移動...」で変えられます。USB メモリなどで持ち運ぶ場合は、起動用のファイルの説明に従って、設定をこのフォルダに保存するように変えられます。

バッチファイルなどからコードを取得する
--code <サービス名> を付けて起動すると、ウィンドウを開かずに、そのサービスのコードを出力します。例: et-otp-v2.bat --code AWS（macOS では sh et-otp-v2.command --code AWS、Linux では sh et-otp-v2.sh --code AWS）。オプションは --help で確認できます。

会社や組織のパソコンで使う場合は、ソフトウェアの利用について社内のルールを確かめてから使ってください。

同梱している Java の実行環境のライセンスは、runtime/legal フォルダにあります。et-otp-v2 のライセンスは GNU General Public License v2.0 only です。
https://disk0401.github.io/et-otp-v2/ja/
