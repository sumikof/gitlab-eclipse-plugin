# 引き継ぎ書: gitlab-eclipse-plugin 機能拡張プロジェクト

作成日: 2026-07-17
現在ブランチ: `fix/step1-foundation-repairs`(12コミット、**未プッシュ**)

## 1. プロジェクトゴール

Eclipse向けGitLabプラグイン(本リポジトリ、PoC段階で更新停止中)の機能を、活発に開発されているVSCode版GitLab拡張(参照コピーが `./out/gitlab-vscode-extension`、gitignore済み)並みに拡張する。

## 2. 作業方針(ユーザー指定・厳守)

1. **ステップごとにブランチを切る** → 修正 → **MR(PR)作成** → **ユーザーレビュー** → 再修正 → レビュー → **マージ** → 次のステップのブランチ作成、のサイクルで進める。
2. 実装プロセスはsuperpowersスキルに従う: brainstorming(設計質問→設計承認)→ スペック作成(`docs/superpowers/specs/`)→ 実装計画(`docs/superpowers/plans/`)→ Subagent-Driven Development(タスクごとに実装サブエージェント+レビューサブエージェント、TDD、タスクごとコミット)→ 最終ブランチ全体レビュー。
3. コミットメッセージ末尾に以下を付与(全コミット済み分は付与済み):
   ```
   Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_01Ku6m3MQ7LooNZXtkT7QNPb
   ```
4. git identityはリポジトリローカルに `sumikof187 <sumikof187@gmail.com>` を設定済み。

## 3. 全体ロードマップ(承認済み推奨順序)

| ステップ | 内容 | 状態 |
|---|---|---|
| 1 | 基盤修復(LSPバイナリDL機構、壊れた宣言削除、ホスト導出、Tychoビルド) | **実装完了・MR作成待ち** |
| 2 | Code Suggestions(インライン補完、LSP経由 `textDocument/inlineCompletion`) | 未着手 |
| 3 | Duo Chat webview双方向メッセージング完成 → Chat v2 / Agentic Chat | 未着手 |
| 4 | Explain/Fix/Tests/Refactor コマンドハンドラ実装(Chatに接続) | 未着手 |
| 5 | 認証強化(OAuth、証明書/プロキシ) | 未着手 |
| 6 | ネイティブ系(Issue/MR/CI)の段階導入 | 未着手 |

根拠資料: `docs/feature-gap-analysis.md`(VSCode拡張との機能差異の全対照表。LSP再利用可能な機能とネイティブ再実装が必要な機能の分類あり)。

## 4. ステップ1の実績

### 設計・計画ドキュメント
- スペック: `docs/superpowers/specs/2026-07-17-step1-foundation-repairs-design.md`
- 実装計画(9タスク): `docs/superpowers/plans/2026-07-17-step1-foundation-repairs.md`

### コミット一覧(main..HEAD、古い順)
```
7c766ea docs: Add feature gap analysis and step-1 foundation-repairs design spec
32af57d docs: Add step-1 implementation plan, align spec details
468e28f build: Restructure to standard Tycho layout with headless build
438cf46 feat: Add test fragment and language-server platform detection
ce44d40 feat: Add minimal streaming ustar reader for LSP tarball extraction
4e07f65 feat: Add language-server installer with cached download and extraction
52a5284 fix: Close response stream on error and add timeouts to LSP download
abb9e4d feat: Auto-download language server, replace hardcoded developer paths
328cf58 fix: Remove broken menu declarations, code mining stub, and dead code
894cbed fix: Derive secret-storage host from configured instance URL
06e2245 fix: Remove telemetry builder overload that overwrote featureFlags
4104f5b fix: Guard against launching a partially downloaded language server
```

### 主な成果
- **Tychoヘッドレスビルド**: 標準レイアウト(`bundles/gitlab-eclipse-plugin/` + `tests/gitlab-eclipse-plugin.tests/`)。検証コマンドはリポジトリルートで `mvn -q verify`(Tycho 4.0.13、Eclipse 2025-06 p2、初回はp2取得で数分)。**ユニットテスト18件**(Platform 6 / Tar 4 / Installer 4 / Params 2 / SecretStorage 2)。
- **LSPバイナリ自動DL**: `com.gitlab.eclipse.lsp.install` パッケージの純JDK POJO群(`LanguageServerPlatform` / `TarArchiveReader` / `LanguageServerInstaller`、バージョン固定 9.5.0)+ Eclipse配線(`LanguageServerInstallJob` / `LanguageServerStartup`(IStartup) / 設定 `gitlab.languageServer.binaryPath`)。DL元はGitLab genericパッケージレジストリのtarball(約300MB、全プラットフォームバイナリ+tree-sitter文法同梱)。キャッシュはバンドルstate location配下 `lsp/<version>/`、`.complete` マーカーで完全性管理。
- **品質ゲート実績**: 全9タスクでタスク別レビュー実施(修正2回: install()のタイムアウト/ストリームクローズ、最終レビューでダウンロード中の不完全バイナリ起動競合をマーカーベースのstart()ガードで修正)。最終ブランチ全体レビュー承認済み。
- **E2E実機検証**(このdevcontainer=linux-arm64): 実際に300MBをDL→展開→実行権限→`--version` 実行(exit 0)→キャッシュ再利用(再実行0.24秒、再DLなし)まで確認。

### MR作成時に説明へ記載すべき事項
- 検証結果: `mvn verify` 18/18 PASS + 上記E2E(linux-arm64)。
- 既知の制限(いずれも後続ステップで対応予定として明記):
  - DLした9.5.0バイナリが `--version` で「GitLab Language Server v9.4.0」と自己申告する(上流のラベリングの癖、動作は正常)
  - win-arm64は非対応(LSP公式バイナリが無い。`UnsupportedOperationException` が生でError Logに出る)
  - ダウンロードJobはProgressビューからキャンセル不可
  - 設定ページで接続URLとPATを同一セッションで変更すると、PATが旧ホスト側に保存される
  - Bundle-Versionを `0.1.0.beta` → `0.1.0.qualifier` に変更(Tycho互換のため)

### 後続ステップへのフォローアップ(最終レビューで記録)
1. LSPダウンロードのSHA256ピン(レジストリはSHA256を公開している。`VERSION` 定数の隣にピン追加が安価)
2. SecretStorageの解決を設定エディタの保存アクション時に行う(URL+PAT同時変更caveatの解消)→ ステップ5(認証)で対応
3. `LanguageServerInstallJob` のキャンセル対応(`monitor.isCanceled()` → `OperationCanceledException`)
4. 設定ストアのqualifierが数値バンドルID(`FrameworkUtil.getBundle().getBundleId()`)でインストール順依存 — 既存問題。移行込みで要対応
5. win-arm64での例外を捕捉してStatusログ1件に整形
6. InstallJobの初回「0 MB downloaded」表示(化粧)

## 5. 環境メモ

- **この環境の制約(引き継ぎの発端)**: SSHクライアント無し・`glab` CLI未インストールのためプッシュ/MR作成が未実施。ユーザーが必要コマンドをインストールして再実行予定。
  - リモート: `git@gitlab.com:sumikof187/gitlab-eclipse-plugin.git`(SSH)。HTTPSは読み取り可を確認済み。プッシュにはSSH鍵設定またはHTTPS+PAT(`write_repository`)、MR作成には `glab auth login`(`api` スコープ)が必要。
  - 前セッションでglab 1.108.0のarm64バイナリをscratchpad(/tmp配下、揮発)に展開済みだったが、環境再構築で消える前提。aptなら `apt install glab openssh-client` 相当で可。
- Java 21 + Maven 3.9.16 は devcontainer feature で導入済み(`.devcontainer/devcontainer.json`、未コミット・未追跡)。
- `.superpowers/sdd/progress.md` にサブエージェント駆動開発の進捗レジャーあり(gitignore対象、ローカルのみ)。タスクブリーフ/レポート/レビューdiffも同ディレクトリ。
- `/workspace/out/` はVSCode拡張の参照コピー。gitignore済み。**変更・コミット禁止**。

## 6. 再開時の手順(次セッションへの指示)

1. `git status` と `git log --oneline main..HEAD` でブランチ `fix/step1-foundation-repairs` が本書§4のコミット一覧と一致することを確認。
2. プッシュ: `git push -u origin fix/step1-foundation-repairs`(認証は§5参照)。
3. MR作成(glabの例):
   ```
   glab mr create --source-branch fix/step1-foundation-repairs --target-branch main \
     --title "Step 1: 基盤修復 — LSP自動DL・Tychoビルド・壊れた宣言の削除" \
     --description <本書§4の成果・検証結果・既知の制限を整形して記載>
   ```
4. ユーザーレビュー→指摘対応(修正は同ブランチに追加コミット→再レビュー)→ユーザー承認後にマージ。
5. マージ後、ステップ2「Code Suggestions(インライン補完)」の新ブランチを作成し、brainstormingスキルから同じプロセスを開始する。ステップ2の起点情報: VSCode拡張はLSPの `textDocument/inlineCompletion` +ストリーミング(`streamCodeGenerations`)が主経路(`docs/feature-gap-analysis.md` 参照)。Eclipse側はLSP4Eのインライン補完サポート状況の調査から始めること(LSP4E 0.18系のInlineCompletionProvider対応可否が最初の設計論点)。
