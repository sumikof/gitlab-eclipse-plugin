# 引き継ぎ書: gitlab-eclipse-plugin 機能拡張プロジェクト(ステップ2完了時点)

作成日: 2026-07-18(ステップ1完了時の旧版を全面更新)
現在ブランチ: `feat/step2-code-suggestions`(**MR !2 作成済み・ユーザーレビュー中**)

## 1. プロジェクトゴール

Eclipse向けGitLabプラグイン(本リポジトリ、元はPoC段階)の機能を、活発に開発されているVSCode版GitLab拡張(参照コピーが `./out/gitlab-vscode-extension`、gitignore済み・**変更禁止**)並みに拡張する。

## 2. 作業方針(ユーザー指定・厳守)

1. **ステップごとにブランチを切る** → 修正 → **MR作成** → **ユーザーレビュー** → 指摘対応(同ブランチに追加コミット→再レビュー) → **マージ** → 次ステップのブランチ作成、のサイクル。
2. 実装プロセスはsuperpowersスキルに従う: brainstorming(設計質問→設計承認)→ スペック(`docs/superpowers/specs/`)→ 実装計画(`docs/superpowers/plans/`)→ **Subagent-Driven Development**(タスクごとに実装サブエージェント+レビューサブエージェント、TDD、タスクごとコミット)→ 最終ブランチ全体レビュー(最上位モデルで実施)→ finishing-a-development-branch。
3. コミットメッセージ末尾に以下を付与(Claude-Session は**現セッションのURL**に置き換えること。Bashツール説明文に記載されている):
   ```
   Co-Authored-By: Claude <モデル名> <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_<現セッションID>
   ```
4. git identityはリポジトリローカルに `sumikof187 <sumikof187@gmail.com>` 設定済み。
5. MR作成時は `--remove-source-branch --yes`、説明文には成果・検証結果・既知の制限・後続への申し送りを整形して記載(MR !1/!2 の形式を踏襲)。

## 3. 全体ロードマップ

| ステップ | 内容 | 状態 |
|---|---|---|
| 1 | 基盤修復(LSPバイナリDL機構、Tychoビルド、壊れた宣言削除) | ✅ **マージ済み**(MR !1) |
| 2 | Code Suggestions(インライン補完、ghost text、ストリーミング) | **MR !2 レビュー中** |
| 3 | Duo Chat webview双方向メッセージング完成 → Chat v2 / Agentic Chat | 未着手 |
| 4 | Explain/Fix/Tests/Refactor コマンドハンドラ実装(Chatに接続) | 未着手 |
| 5 | 認証強化(OAuth、証明書/プロキシ) | 未着手 |
| 6 | ネイティブ系(Issue/MR/CI)の段階導入 | 未着手 |

根拠資料: `docs/feature-gap-analysis.md`(VSCode拡張との全機能差異対照表)。

## 4. ステップ1の実績(マージ済み・要点のみ)

- MR !1(マージ済み): Tychoヘッドレスビルド化(`bundles/` + `tests/` 標準レイアウト、`mvn -q verify`)、LSPバイナリ自動DL(genericレジストリのtarball約300MB、`.complete`マーカーで完全性管理、`lsp/<version>/` キャッシュ)、壊れた宣言の削除、secret-storageホスト導出、テレメトリbuilderバグ修正。
- ユーザーレビュー指摘対応: tar読み取りの防御的失敗(GNU long-name/PAX/base-256検知でIOException)を追加。実9.5.0 tarball全325エントリが純ustarであることを確認済み。
- 詳細は旧版引き継ぎ書(git履歴 `c864ee2` の docs/HANDOVER.md)と `docs/superpowers/specs|plans/2026-07-17-step1-*`。

## 5. ステップ2の実績(現MR)

### ドキュメント
- スペック: `docs/superpowers/specs/2026-07-18-step2-code-suggestions-design.md`
- 実装計画(11タスク、確定プロトコル定数・実証済みAPIパターン埋め込み済み): `docs/superpowers/plans/2026-07-18-step2-code-suggestions.md`
- 手動検証手順書(12項目): `docs/manual-tests/2026-07-18-step2-code-suggestions.md`

### 実装前に確定させた重要事実(再調査不要)
- **LSP4E は `textDocument/inlineCompletion` 完全未対応**(0.18.x/main とも)。LSP4J 0.23.1 に InlineCompletion 型は無い(1.0.0初出、Eclipse 2025-06のLSP4Eと共存不可)→ 独自DTO + `@JsonRequest` 方式で実装済み。
- **gitlab-lsp v9.5.0 プロトコル確定値**(実ソース検証済み): ストリーミング通知 `streamingCompletionResponse` の `completion` は**累積文字列**(連結不要)/ キャンセルは `cancelStreaming` `{id}` / コマンドID `gitlab.ls.startStreaming`(args=[streamId, trackingId])・`gitlab.ls.codeSuggestionAccepted`(args=[trackingId, 1始まりindex?])/ テレメトリ `$/gitlab/telemetry` category=`code_suggestions` action=`suggestion_shown`|`suggestion_accepted` context={trackingId, optionId?} / 購読は didChangeConfiguration の `telemetry.actions`(**購読したらSHOWNはクライアント送信義務**)/ `$/gitlab/didChangeDocumentInActiveEditor` はuri文字列 / ストリーミング有効化はcapability不要、`featureFlags.streamCodeGenerations` のみ。
- **ghost text は CodeMining 方式**(Platform 4.34+)。参照実装 microsoft/copilot-for-eclipse(MIT)。既知の癖: LineContentCodeMining の Position は length≥1 必須 / タブ文字は描画されない(先頭タブのみスペース展開)/ 最終行にはLineHeaderブロック表示不可 / `updateCodeMinings()` は必ず `Display.asyncExec` 経由(LSP4Eのロックとデッドロック)/ TAB・ESCは textEditorScope 直バインド+ハンドラ `isEnabled()` ゲート(独自コンテキストは Ctrl+→ のみ)。

### アーキテクチャ
- `com.gitlab.eclipse.lsp`: プロトコルDTO(record 9種)+ `GitLabLanguageServer`/`GitLabLanguageClient` 拡張 + `SuggestionTelemetry`
- `com.gitlab.eclipse.suggestions`(**純JDK、Eclipse依存禁止、ユニットテスト対象**): `SuggestionModel`(単語単位受け入れ状態)/ `StreamBuffer`(累積チャンク)/ `RenderPlan`(行分割・タブ展開)/ `StreamingCompletionEvents`(staticディスパッチャ)
- `com.gitlab.eclipse.suggestions.ui`(SWT/JFace配線、ハンドラ含め同一パッケージでpackage-private共有): `CompletionSessionManager`(デバウンス250ms・`requestSerial`無効化・`CompletableFuture.cancel`・修正スタンプ+キャレットのstale判定・`SuggestionSessions.active() != this` ガード)/ `EditorTracker` / `SuggestionStartup` / `GhostTextCodeMiningProvider`+CodeMining3種 / ハンドラ4種

### 品質ゲート実績
- 全11タスクでタスク別レビュー(spec compliance + code quality)実施、全承認。
- 最終ブランチ全体レビュー(opus): Important 1件 → `760f04c` で修正(エディタ切替中のasyncExecコールバックが旧エディタのモデルを汚染+虚偽SHOWNテレメトリ → 両クロージャ先頭に active-manager ガード)。再レビュー承認済み。
- ユーザーMRレビュー第1ラウンド: Important 1件 → `6ee35cf` で修正(Ctrl+→単語受け入れ直後のkeyUpが`keyReleased`に到達し無条件discardで残候補破棄 → `discardIfCaretMoved()`でキャレット位置認識型に変更)。
- `mvn -q verify`: **45/45 PASS**(ステップ1の21+新規24)。

### 未完了事項(次セッションが把握すべきこと)
1. **MR !2 のユーザーレビュー継続中**(`6ee35cf` の再レビュー待ち)。承認→マージ→ステップ3へ。
2. **実機Eclipse手動検証が未実施**(devcontainerはheadless)。`docs/manual-tests/2026-07-18-step2-code-suggestions.md` の12項目をユーザー実機で確認予定。最重要は **#3 単語受け入れ**(6ee35cfの修正対象)と **#5 TABフォールスルー**(`isEnabled()`再評価に依存、静的検証不能)。問題があれば同ブランチまたはfollow-up MRで修正。

### 後続ステップへの申し送り(レビューで軽微と判断・記録済み)
- ステップ1由来: LSPダウンロードのSHA256ピン / SecretStorage解決を保存アクション時に(→ステップ5)/ InstallJobキャンセル対応 / 設定ストアqualifierが数値バンドルID依存 / win-arm64例外の整形 / 「0 MB」初回表示
- ステップ2由来: `logOnce()`が全エラー種で初回のみ(仕様は同種毎)/ `lsp`↔`suggestions`の循環パッケージ依存(同一バンドル内なので実害なし)/ `requestNow()`がアクティブストリームを未キャンセル / `SuggestionStartup.windowClosed`空実装(実機QAで確認)/ `EditorTracker`未使用import / `InlineCompletionCommand`のJsonPrimitive分岐が単体テスト未検証
- **ステップ3への設計メモ**: `StreamingCompletionEvents`のディスパッチャパターンは webview 通知にも再利用可能(同一インスタンスを流用せず同型を新設)。`SuggestionSessions`のプロセス全体シングルトン(モデル/ストリーム/アクティブマネージャ各1)は単一カーソル補完には十分だが、エディタ並行状態が必要になったらマネージャ単位状態へ移行。

## 6. 環境メモ

- **認証(重要)**: glab は OAuth **deviceフロー**でログイン済み(`glab auth login --hostname gitlab.com --device --git-protocol https`)。設定は `/root/.config/glab-cli/config.yml`。**コンテナ再構築で消える**ため、再ログインが必要なら同コマンドをユーザーに `! ` プレフィックスで実行してもらう(通常のBashツールは非TTYで対話ログイン不可。deviceフローはコード表示→ブラウザ認可なのでトークンがチャットに残らない)。
- リモートは **HTTPS に切替済み**: `https://gitlab.com/sumikof187/gitlab-eclipse-plugin.git`(push認証はglabのOAuthトークン)。
- ビルド: リポジトリルートで `mvn -q verify`(Java 21 + Maven 3.9系はdevcontainer featureで導入済み。Tycho 4.0.13 / Eclipse 2025-06 p2、初回はp2取得で数分)。
- `.devcontainer/` は**未追跡のまま**(コミットするか未決。ユーザーに確認するのが無難)。
- `.superpowers/sdd/progress.md` にサブエージェント駆動開発の進捗レジャー(gitignore対象・ローカルのみ)。タスクブリーフ/レポート/レビューdiffも同ディレクトリ。**セッション再開時はまずこのレジャーと `git log` を確認**(完了済みタスクの再実行は厳禁)。
- `/workspace/out/` はVSCode拡張の参照コピー。**変更・コミット禁止**。
- scratchpad(/tmp配下)は揮発。MR説明文の原稿等はセッションごとに作り直す。

## 7. 再開時の手順(次セッションへの指示)

1. `git status` / `git log --oneline main..HEAD` / `.superpowers/sdd/progress.md` で現在地を確認。`glab auth status` で認証確認(切れていたら§6の手順)。
2. **MR !2 の状態確認**: `glab mr view 2`。
   - ユーザーレビュー指摘があれば: 同ブランチに追加コミット(修正はサブエージェント+再レビューの品質ゲートを通す)→ push → MR説明のレビュー対応履歴を更新 → 再レビュー依頼。
   - 承認されたら: `glab mr merge 2` → `git checkout main && git pull && git branch -d feat/step2-code-suggestions`。
3. 実機手動検証の結果をユーザーに確認(§5未完了事項2)。不具合があれば修正を優先。
4. マージ後、**ステップ3「Duo Chat webview双方向メッセージング」**の新ブランチ(例: `feat/step3-duo-chat-webview`)を作成し、**brainstormingスキルから**開始。起点情報:
   - 現状: `LanguageServerBrowserView`(SWT Browser)はLSPの `$/gitlab/webview-metadata` から取得したURLを開くだけ。`$/gitlab/webview/*` ハンドラ(`GitLabLanguageClient`)は全てno-op、注入JS `LanguageServerBrowserView.js` は0バイト。
   - VSCode側はLSPがWebviewコンテンツをサーブし、双方向メッセージング(`$/gitlab/webview/notification`・`$/gitlab/webview/request`、旧`$gitlab/`プレフィックス互換あり)で通信(`docs/feature-gap-analysis.md` 参照)。
   - 最初の設計論点: SWT Browser(Edge/WebKit)とJavaの双方向ブリッジ方式(`BrowserFunction` + `execute/evaluate`)、およびgitlab-lsp側のwebviewプロトコル仕様の調査(ステップ2と同様、実装前にgitlab-lsp実ソースで通知名・ペイロードを確定させること)。
   - プロセスはステップ2と同一: 調査(並行サブエージェント)→ 設計質問(1問ずつ)→ 2-3案提示 → 設計承認 → スペック → 計画(確定値埋め込み)→ SDD実行 → 最終レビュー → MR。
