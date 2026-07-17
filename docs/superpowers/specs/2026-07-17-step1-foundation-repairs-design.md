# ステップ1: 基盤修復 設計書

日付: 2026-07-17
ブランチ: `fix/step1-foundation-repairs`
背景: `docs/feature-gap-analysis.md` §3(既知の破損)を参照。本ステップは機能追加の前提となる基盤の修復のみを行う。

## 目的

プラグインを「開発者のMac以外でも起動し、壊れたUIが無く、CLIでビルド検証できる」状態にする。

## スコープ

### 1. LSPバイナリの自動ダウンロード機構

**新クラス** `com.gitlab.eclipse.lsp.LanguageServerInstaller`。責務: 実行可能なLSPバイナリの絶対パスを解決して返す。

- **配布元(確認済み)**: GitLab genericパッケージレジストリ
  `https://gitlab.com/api/v4/projects/gitlab-org%2Feditor-extensions%2Fgitlab-lsp/packages/generic/gitlab-language-server/<version>/gitlab-lsp-<version>.tar.gz`
  tarball(約300MB)に全プラットフォームのバイナリ `bin/gitlab-lsp-{linux-x64,linux-arm64,macos-x64,macos-arm64,win-x64.exe}` と `bin/vendor/grammars/*.wasm`(tree-sitter文法)が同梱されている。
- **バージョン固定**: 定数 `9.5.0`(2026-07時点の最新。VSCode拡張同梱の ^9.3.0 と同系)。
- **プラットフォーム判定**: `os.name`/`os.arch` → `linux-x64` / `linux-arm64` / `macos-x64` / `macos-arm64` / `win-x64`。win-arm64はアセットが存在しないため非対応(明示エラー)。判定ロジックはPOJO(`LanguageServerPlatform`)として切り出しユニットテスト対象とする。
- **キャッシュ**: `Platform.getStateLocation()` 配下 `lsp/<version>/` に、自プラットフォームのバイナリと `vendor/` ディレクトリのみを展開して保存(tarball本体は展開後削除)。既に存在すれば再DLしない。Unix系は展開後に実行権限を付与。
- **DL実行**: Eclipse `Job` でプログレス表示付き。tarballは `GZIPInputStream` + 自前の最小tar(ustar)リーダーでストリーミング展開し、必要エントリのみ書き出す。必要なのは通常ファイルの名前とサイズだけなので新規依存を追加しない。tarリーダーもユニットテスト対象のPOJOとする。
- **設定で上書き**: 新設定 `gitlab.languageServer.binaryPath`(既存キーの命名規約に合わせる。既定値: 空 = 自動DL)。非空ならそのパスを使用しDLしない。設定ページに `FileFieldEditor` を追加。
- **作業ディレクトリ**: ハードコード(`/Users/erran/...`)を廃止し、state location配下 `lsp-workdir/` に変更。
- **エラー処理**: DL/展開失敗はJobのエラーステータス(Progressビュー/Error Log)で通知し、その起動でのLSP開始を断念(Eclipse本体の動作は継続)。次回起動時に自動再試行。中断・破損対策として、展開完了後に `.complete` マーカーを書き込み、マーカーが無いキャッシュは不完全とみなして再取得する(tarballはディスクに保存せずストリーミング展開する)。

`GitLabLanguageServerProvider` は `LanguageServerInstaller` から解決したパスでコマンドを構築する。initialization optionsのハードコードバージョン `"0.1.0-erran"` はバンドルバージョン(`Platform.getBundle(...).getVersion()`)に置き換える。

### 2. plugin.xml クリーンアップ

削除する(いずれも参照先が未定義または空スタブで非動作):

- エディタ右クリック「GitLab Duo」サブメニューの4コマンド(ExplainCode / FixCode / GenerateTests / RefactorCode)
- ステータストリムのツールバー+プルダウンメニュー一式(showPluginStatus / chatStatus / codeSuggestionsStatus / disableAllCodeSuggestions / showSettings / showDocumentation)
- CodeMiningプロバイダ拡張(クラス `MyCodeMiningProvider` が存在しない)
- 空の `org.eclipse.ui.commands` カテゴリ宣言

削除する死にコード:

- `StatusWidget.java`(空スタブ、どこからも参照なし)
- `ShowPluginStatus.java` / `ToggleDuoChatHandler.java`(未登録の孤立ハンドラ)
- `PreferenceConstants.TANUKI_ONLY_SHOW_CODE_MININGS` と設定ページの該当項目(消費者不在)

残すもの: Duo Chatビュー、設定ページ、LSP登録、contentType-languageIdマッピング。

### 3. ホスト名ハードコード解消

`SecretStorage` を使う3箇所(`GitLabLanguageServerProvider:52` / `GitLabPreferencePage:28` / `LanguageServerBrowserView:139`)の `"gitlab.com"` 固定をやめ、設定 `gitlab.url` から `URI#getHost()` でホストを導出する共通メソッドを `SecretStorage` に追加。URL未設定・解析不能時は `gitlab.com` にフォールバック。

### 4. Builderコピペバグ修正

`GitLabLanguageServerConfigurationParams.Builder` の `telemetry(FeatureFlags)` オーバーロード(誤って `featureFlags` に代入)を削除。正しい `telemetry(Telemetry)` のみ残す。

### 5. Maven Tychoビルド導入

- ルート `pom.xml`(Tycho最新安定版、packaging `eclipse-plugin`)+ ターゲットプラットフォームは Eclipse 2025-06 リリースp2リポジトリ + lsp4e/lsp4j を解決できるリポジトリ。
- `mvn verify` でヘッドレスコンパイルが通ることを完了条件とする。
- 新規POJOロジック(プラットフォーム判定、アセットURL組み立て、Builder)向けにテストフラグメント `gitlab-eclipse-plugin.tests` を追加し、tycho-surefireでJUnit実行。既存コードのテストは対象外(後続ステップで実装とともに追加)。

### 6. リポジトリ衛生

- `.gitignore` 追加: `/out/`、`target/`、`bin/`、`.polyglot.*` 等Tycho生成物。
- `docs/feature-gap-analysis.md` と本設計書をコミットに含める。

## スコープ外

インライン補完(ステップ2)、Chat webviewメッセージング(ステップ3)、Explain/Fix等のハンドラ実装(ステップ4)、OAuth/プロキシ(ステップ5)、Issue/MR/CI(ステップ6)。

## テスト方針

- TDD: `LanguageServerPlatform`(os/arch→アセット名)、DL URL組み立て、`SecretStorage` のホスト導出、`Builder` はユニットテストを先に書く。
- ネットワークを伴うDL本体は手動確認(このdevcontainer内で `linux-arm64` バイナリのDL・展開・実行権限までを実機検証)。
- `mvn verify` グリーンを完了条件。

## 完了条件

1. `mvn verify` が成功する(コンパイル+ユニットテスト)。
2. plugin.xmlに未定義参照が残っていない。
3. ハードコードされた開発者パス・ホスト名・バージョン文字列が排除されている。
4. この環境でLSPバイナリのDL→キャッシュ→実行権限付与が動作する(手動検証ログをPRに添付)。
