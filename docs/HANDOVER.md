# 引き継ぎ書: gitlab-eclipse-plugin 機能拡張プロジェクト(ステップ2完了・GitHub移行済み)

作成日: 2026-07-18(最終更新: リポジトリのGitHub移行後)
**開発拠点: GitHub `sumikof/gitlab-eclipse-plugin` の `feature/port-vscode` ブランチ**(= 本プロジェクトのベースブランチ。旧GitLabリポジトリの `main` 全履歴をここへ移行済み)。**次はステップ3のブランチ作成から**。

> ⚠️ GitHub側リポジトリの `main` は**公式 GitLab Plugin for Eclipse(Gradle/Kotlin実装、821コミット)の別系統コード**であり、本プロジェクト(Tycho/Java PoC拡張)とは共通祖先が無い。**`main` をPRターゲットにしたりマージしたりしないこと**。本プロジェクトの全作業は `feature/port-vscode` を基点に行う。

## 1. プロジェクトゴール

Eclipse向けGitLabプラグイン(本リポジトリ、元はPoC段階)の機能を、活発に開発されているVSCode版GitLab拡張(参照コピーが `./out/gitlab-vscode-extension`、gitignore済み・**変更禁止**)並みに拡張する。

## 2. 作業方針(ユーザー指定・厳守)

1. **ステップごとに `feature/port-vscode` からブランチを切る** → 修正 → **PR作成(base: `feature/port-vscode`)** → **ユーザーレビュー** → 指摘対応(同ブランチに追加コミット→再レビュー) → **マージ** → 次ステップのブランチ作成、のサイクル。ツールは `gh` CLI(例: `gh pr create --base feature/port-vscode --title ... --body-file ...` / `gh pr merge N --merge --delete-branch`)。
2. 実装プロセスはsuperpowersスキルに従う: brainstorming(設計質問→設計承認)→ スペック(`docs/superpowers/specs/`)→ 実装計画(`docs/superpowers/plans/`)→ **Subagent-Driven Development**(タスクごとに実装サブエージェント+レビューサブエージェント、TDD、タスクごとコミット)→ 最終ブランチ全体レビュー(最上位モデルで実施)→ finishing-a-development-branch。
3. コミットメッセージ末尾に以下を付与(Claude-Session は**現セッションのURL**に置き換えること。Bashツール説明文に記載されている):
   ```
   Co-Authored-By: Claude <モデル名> <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_<現セッションID>
   ```
4. git identityはリポジトリローカルに `sumikof187 <sumikof187@gmail.com>` 設定済み。
5. PR説明文には成果・検証結果・既知の制限・後続への申し送りを整形して記載(旧GitLabの MR !1/!2 の形式を踏襲。過去MRは旧リポジトリ側で閲覧可)。マージ後はソースブランチを削除。

## 3. 全体ロードマップ

| ステップ | 内容 | 状態 |
|---|---|---|
| 1 | 基盤修復(LSPバイナリDL機構、Tychoビルド、壊れた宣言削除) | ✅ **マージ済み**(MR !1) |
| 2 | Code Suggestions(インライン補完、ghost text、ストリーミング) | ✅ **マージ済み**(MR !2) |
| 3 | Duo Chat webview双方向メッセージング完成 → Chat v2 / Agentic Chat | **次に着手** |
| 4 | Explain/Fix/Tests/Refactor コマンドハンドラ実装(Chatに接続) | 未着手 |
| 5 | 認証強化(OAuth、証明書/プロキシ) | 未着手 |
| 6 | ネイティブ系(Issue/MR/CI)の段階導入 | 未着手 |

根拠資料: `docs/feature-gap-analysis.md`(VSCode拡張との全機能差異対照表)。

## 4. ステップ1の実績(マージ済み・要点のみ)

- MR !1(マージ済み): Tychoヘッドレスビルド化(`bundles/` + `tests/` 標準レイアウト、`mvn -q verify`)、LSPバイナリ自動DL(genericレジストリのtarball約300MB、`.complete`マーカーで完全性管理、`lsp/<version>/` キャッシュ)、壊れた宣言の削除、secret-storageホスト導出、テレメトリbuilderバグ修正。
- ユーザーレビュー指摘対応: tar読み取りの防御的失敗(GNU long-name/PAX/base-256検知でIOException)を追加。実9.5.0 tarball全325エントリが純ustarであることを確認済み。
- 詳細は旧版引き継ぎ書(git履歴 `c864ee2` の docs/HANDOVER.md)と `docs/superpowers/specs|plans/2026-07-17-step1-*`。

## 5. ステップ2の実績(MR !2・マージ済み)

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
1. **実機Eclipse手動検証が未実施**(devcontainerはheadless)。`docs/manual-tests/2026-07-18-step2-code-suggestions.md` の12項目をユーザー実機で確認予定。最重要は **#3 単語受け入れ**(`6ee35cf` の修正対象)と **#5 TABフォールスルー**(`isEnabled()`再評価に依存、静的検証不能)。不具合が見つかったら **fix ブランチ+PR(base: `feature/port-vscode`)** で修正し、ステップ3と並行して対応可。

### 後続ステップへの申し送り(レビューで軽微と判断・記録済み)
- ステップ1由来: LSPダウンロードのSHA256ピン / SecretStorage解決を保存アクション時に(→ステップ5)/ InstallJobキャンセル対応 / 設定ストアqualifierが数値バンドルID依存 / win-arm64例外の整形 / 「0 MB」初回表示
- ステップ2由来: `logOnce()`が全エラー種で初回のみ(仕様は同種毎)/ `lsp`↔`suggestions`の循環パッケージ依存(同一バンドル内なので実害なし)/ `requestNow()`がアクティブストリームを未キャンセル / `SuggestionStartup.windowClosed`空実装(実機QAで確認)/ `EditorTracker`未使用import / `InlineCompletionCommand`のJsonPrimitive分岐が単体テスト未検証
- **ステップ3への設計メモ**: `StreamingCompletionEvents`のディスパッチャパターンは webview 通知にも再利用可能(同一インスタンスを流用せず同型を新設)。`SuggestionSessions`のプロセス全体シングルトン(モデル/ストリーム/アクティブマネージャ各1)は単一カーソル補完には十分だが、エディタ並行状態が必要になったらマネージャ単位状態へ移行。

## 6. 環境メモ

- **リモート構成(GitHub移行後)**:
  - `origin` = `https://github.com/sumikof/gitlab-eclipse-plugin.git`(**開発拠点**。ベースブランチ `feature/port-vscode`)
  - `gitlab` = `https://gitlab.com/sumikof187/gitlab-eclipse-plugin.git`(旧リポジトリ。アーカイブ扱い、今後は更新しない。過去のMR !1/!2 の記録はこちら)
- **GitHub認証(重要)**: `gh` CLI(apt導入済み、2.45)で **deviceフロー**ログイン済み+`gh auth setup-git` でgit認証設定済み。設定は `/root/.config/gh/hosts.yml`。**コンテナ再構築で消える**ため、再ログインは `gh auth login --hostname github.com --git-protocol https --web` をユーザーに `! ` プレフィックスで実行してもらい(非TTYのため通常Bashでは対話不可。deviceフローなのでトークンがチャットに残らない)、その後 `gh auth setup-git`。アカウントは `sumikof`。
- (旧)GitLab認証: glab のOAuth deviceフロー(`/root/.config/glab-cli/config.yml`)。旧リポジトリを参照する必要がある場合のみ再ログイン。
- ビルド: リポジトリルートで `mvn -q verify`(Java 21 + Maven 3.9系はdevcontainer featureで導入済み。Tycho 4.0.13 / Eclipse 2025-06 p2、初回はp2取得で数分)。
- `.devcontainer/` は**未追跡のまま**(コミットするか未決。ユーザーに確認するのが無難)。
- `.superpowers/sdd/progress.md` にサブエージェント駆動開発の進捗レジャー(gitignore対象・ローカルのみ)。タスクブリーフ/レポート/レビューdiffも同ディレクトリ。**セッション再開時はまずこのレジャーと `git log` を確認**(完了済みタスクの再実行は厳禁)。
- `/workspace/out/` はVSCode拡張の参照コピー。**変更・コミット禁止**。
- scratchpad(/tmp配下)は揮発。MR説明文の原稿等はセッションごとに作り直す。

## 7. 再開時の手順(次セッションへの指示): ステップ3の開始

前提確認(1分):
1. `git checkout feature/port-vscode && git pull` / `.superpowers/sdd/progress.md` で現在地確認(ステップ2までcomplete)。
2. `gh auth status` で認証確認(切れていたら§6の手順でユーザーに再ログインしてもらう)。
3. ユーザーに実機手動検証(§5未完了事項1)の結果を確認。不具合報告があれば fix ブランチ(base: `feature/port-vscode`)で先に対応。

ステップ3「Duo Chat webview双方向メッセージング」の進め方:
1. ブランチ作成: `git checkout feature/port-vscode && git checkout -b feat/step3-duo-chat-webview && git push -u origin feat/step3-duo-chat-webview`(PRのbaseは必ず `feature/port-vscode`。**GitHubの `main` は別系統コードのため絶対にターゲットにしない**)
2. **brainstormingスキルを起動**し、まず並行サブエージェントで調査(ステップ2で効果実証済みの型):
   - 調査A(ローカル `out/gitlab-vscode-extension`): VSCode拡張のwebview接続方式 — webview URLの取得と表示、拡張⇔webview⇔LSPのメッセージ中継(`$/gitlab/webview/notification`・`$/gitlab/webview/request` の使われ方、pluginId/webviewId/型)、認証情報の受け渡し。
   - 調査B(Web、gitlab-lsp v9.5.0実ソース): webviewプロトコルの**確定値** — `$/gitlab/webview-metadata` の応答型、`$/gitlab/webview/created|destroyed|notification|request` の正確なペイロード、webviewサーバのポート/URL構成、Duo Chat v2のwebviewId。※ステップ2同様「実装前にプロトコル定数を実ソースで確定→計画に埋め込み」が品質の要。
   - 調査C(Web): SWT Browser のJava⇔JS双方向ブリッジ — `BrowserFunction`(JS→Java)+ `Browser.execute/evaluate`(Java→JS)のパターン、Edge(WebView2)/WebKitの差異、`ProgressListener`でのJS注入タイミング。
3. 設計質問(1問ずつ・選択肢形式)→ 2-3案提示 → 設計承認 → スペック(`docs/superpowers/specs/`)→ 実装計画(`docs/superpowers/plans/`、確定値埋め込み・No Placeholders)→ Subagent-Driven Development(タスクごと実装+レビュー、POJO層はTDD)→ 最終ブランチ全体レビュー(最上位モデル)→ finishing-a-development-branch → PR作成(base: `feature/port-vscode`)。
4. 実装の起点(現状コード):
   - `LanguageServerBrowserView.java`(SWT Browser)は `$/gitlab/webview-metadata` で取得したURLを開くだけ。PATをInputDialogで保存するボタンあり。
   - `GitLabLanguageClient` の `$/gitlab/webview/*` ハンドラ4つ+旧`$gitlab/`互換2つは**全てno-op** — ここに双方向配線を実装する。
   - 注入JS `LanguageServerBrowserView.js` は**0バイト**。
   - ステップ2の `StreamingCompletionEvents`(static購読/publish、リスナー例外隔離)がディスパッチャの参照パターン(同型を新設、流用はしない)。
5. 設計時の考慮点(§5申し送りより): webviewが必要とする認証・設定はLSP側がサーブする想定だが、トークン露出経路はレビュー観点に含めること。SWT Browser実機依存の動作はステップ2同様「手動検証手順書」を成果物に含める。
