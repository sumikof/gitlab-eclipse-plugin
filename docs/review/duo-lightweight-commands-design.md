# D3/D4 Duo 軽量コマンド(Quick Pick メニュー / チュートリアル / ターミナル出力の説明)設計書

対象 issue: #14(Phase 6)/ 台帳 #7 D3・D4 / ロードマップ #8
ベースブランチ: `gitlab-ls-9.3.0` @ `cbb34f2`
参照実装: `gitlab-workflow` v6.85.3(`./out/gitlab-vscode-extension`、読み取り専用)
同梱 LS: 9.3.0(`build/gitlab-lsp/bin/main.js.map` の `sourcesContent` を実値の根拠とする。以下「LS map @<byte offset>」)

---

## 1. 背景と目的

Phase 6 の残余のうち、**独立したサブシステムを持たず、既存の配線(状態メニュー・エディタ・classic chat 経路)に薄く乗せられる 3 コマンド**を 1 サイクルで閉じる。

| VSCode コマンド | 台帳 | 現状 |
|---|---|---|
| `gl.showDuoQuickPickMenu` | D4 | ❌(固定メニューはあるがフォーラム・サインイン導線がない) |
| `gl.duoTutorial` | D3 | ❌ |
| `gl.webview.explainSelectedTerminalOutput` | D3 | ❌ |

パリティは **74/85 → 77/85**(D3 +2、D4 +1)。

**台帳・ロードマップの記述訂正を伴う。** #14(2026-08-07 の分析)および #8 は「D4 の全行が `vscode.comments` API に依存する」としているが、`gl.showDuoQuickPickMenu` は **Quick Chat とも `vscode.comments` とも無関係**である(`src/common/duo_quick_pick/commands/show_quick_pick_menu.ts:100-118` は `QuickPick` だけで構成され、`comments` を import しない)。本サイクルで D4 のこの 1 行を等価実装で ✅ にし、#14 / #8 の当該記述を訂正する。

## 2. 対象範囲

| # | 台帳行 | VSCode | Eclipse での形 |
|---|---|---|---|
| F1 | Duo クイックピックメニュー(D4) | `gl.showDuoQuickPickMenu` | 既存の固定トリムメニュー「GitLab Duo」(`plugin.xml:1593-1603` / `:1605-1711`)に **3 項目を追加**(フォーラム / サインイン(未認証時のみ)/ Duo ドキュメント)。ユーザー決定 Q1=(A) |
| F2 | Duo チュートリアル(D3) | `gl.duoTutorial` | ワークスペースプロジェクト **`GitLab Duo Tutorial`** を作成し、その中の `.js` を開く。ユーザー決定 Q2=(a) |
| F3 | ターミナル出力の説明(D3) | `gl.webview.explainSelectedTerminalOutput` | **Terminal ビューと Console ビューの両方**の右クリックメニューから、選択テキストを LS の AI コンテキストに追加し、classic chat に `explainTerminalOutput` を送る。ユーザー決定 Q3=(b)+Q4 |

**1 設計書・1 Codex レビュー・1 実装 PR(3 タスク)**(ユーザー決定 Q5)。

## 3. 対象外

- **D1 多アカウント(`gl.validateAccounts` / `gl.removeAccount` / `gl.selectWorkspaceAccount` / `gl.status.account`)。** ユーザー決定: 本プラグインは単一アカウント運用であり、対象外とする(#7 / #14 に記録する)。
- **OAuth タイマー修正。** `GitLabPreferencePage.kt:72` の `if (BuildConfig.OAUTH_ENABLED)` により OAuth UI 自体が無効化されており、`OAUTH_ENABLED` は `build.gradle.kts:88-92` で `false` 固定。到達不能な経路の修正は行わない(ユーザー決定)。
- **D4 の残り 3 行(Quick Chat 系)** と **Agent Sandboxing の状態・トグル項目**(`utils.ts:101-143`)。Sandbox は LS の `sandbox` feature state を UI に反映する新規サブシステムであり、本サイクルの「薄い追加」の範囲を超える。
- **メニューの動的再構築。** VSCode は QuickPick を開くたびに項目を組み立て直す(`show_quick_pick_menu.ts`)が、Eclipse 側は既存の固定メニューを維持する(Q1=(A))。
- **ターミナル「最後のコマンドとその出力」フォールバック**(`gitlab_chat_terminal_context.ts:81-87`)。Eclipse の Terminal / Console にシェル統合相当の API がない(§5.3 のギャップとして明記)。
- **新規 OSGi 依存の導入**(`org.eclipse.terminal.*` / `org.eclipse.tm.terminal.*` / `org.eclipse.ui.console` / `org.eclipse.debug.ui`)。§6.2。
- **キーバインドの追加**(F3)。§11.4 に理由。

## 4. 現在の課題

1. **F1**: 状態メニューに「フォーラム」導線がなく(コード内に `forum.gitlab.com` への参照はゼロ)、未認証時の導線は 2 秒デバウンス付きのポップアップ(`AuthenticationStateService.kt:33-47, 55-80`)しかない。ポップアップは 3 秒で閉じる(`:78`)ため、見逃すと設定ページを自力で探すことになる。
2. **F2**: チュートリアルが存在しない。VSCode は untitled ドキュメントを開くだけ(`duo_tutorial.ts:159-166`)だが、Eclipse では **`IFileEditorInput` でないエディタは LS に `didOpen` されない**(`GitLabLanguageServerOpenFilesService.kt:135-142`)ため、同じ手は使えない。ワークスペース上の実ファイルが必要。
3. **F3**: クライアント→サーバの `$/gitlab/ai-context/*` リクエストが `GitLabLanguageServer.kt` に **1 本も宣言されていない**(`:39-112`。クライアント側に `$/gitlab/ai-context/git-diff` / `editor-selection` の受信 `GitLabLanguageServerClient.kt:79, 94` があるのみ)。`chat_terminal_context` の feature state は `FeatureStateStore` に記録されるだけで(`FeatureStateStore.kt:57`)、`GitLabLanguageServerClient.kt:106-115` のディスパッチに case がなく、UI からは見えない。
4. **既存の配線上の癖**: `gitlab-eclipse-plugin.commands.ExplainCode` 等はハンドラ(`plugin.xml:651-654`)とメニュー(`:1091-1101`)があるが **`<command>` 宣言がない**(`org.eclipse.ui.commands` 拡張 `:4-434` に該当 id なし)。本サイクルで追加するコマンドは全て `<command>` を宣言する(§11.1)。

## 5. 要件

### 5.1 F1 状態メニュー

| ID | 要件 |
|---|---|
| R1 | 「GitLab Forum(Help and feedback)」項目を追加し、`https://forum.gitlab.com/c/gitlab-duo/52` を外部ブラウザで開く(ラベル・説明・URL は `src/common/duo_quick_pick/constants.ts:14-15, 18`)。 |
| R2 | 「GitLab Duo Documentation」項目を追加し、`https://docs.gitlab.com/user/gitlab_duo/` を開く(`constants.ts:13, 17`)。**既存の「Show Documentation」(`https://docs.gitlab.com/editor_extensions/eclipse/`、`ShowDocumentation.kt:17-19`)は残す。** 2 つは対象が違う(製品ドキュメント vs 拡張のドキュメント)。 |
| R3 | 「Sign in to GitLab」項目を追加し、**未認証のときだけ表示する**。動作は既存の認証ポップアップと同じ `openGitLabPreferences()`(`AuthenticationStateService.kt:70-73` → `PreferencesUtil.kt:5-11`)。 |
| R4 | 「未認証」の判定は LS の `authentication` feature state から行う(§8.1)。認証状態が一度も届いていない間は**表示しない**。 |
| R5 | 既存項目・既存の並び・既存の `ShowPluginStatusMenu`(`ShowPluginStatusMenu.kt:14-28`)は変更しない。 |

### 5.2 F2 チュートリアル

| ID | 要件 |
|---|---|
| R6 | コマンド実行で、ワークスペースに **`GitLab Duo Tutorial`** という名前のプロジェクトを(なければ)作成し、その直下の **`duo_tutorial.js`** をエディタで開く。 |
| R7 | 開いたエディタで **Code Suggestions が動く**こと。すなわち (a) `IFileEditorInput` で開かれ `didOpen` が送られる(`GitLabLanguageServerOpenFilesService.kt:119-125, 144-149`)、(b) `languageId` が `javascript` になる(`LanguageServerLanguage.kt:15, 34-40`: 拡張子 `js` → `"javascript"`)、(c) JavaScript が既定で有効である(`CodeSuggestionsLanguageService.kt:22-29`: サポート言語は `CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES` に含まれない限り有効、既定値は `""`(`PreferenceInitializer.kt:17`))。 |
| R8 | 内容は VSCode の `duo_tutorial.ts:4-157` を **Eclipse 向けに翻案**する(§12.2): キーは `plugin.xml:444-500` の実バインド、Quick Chat 節は削除、Explain / Tests / Refactor はエディタ右クリック「GitLab Duo Chat」サブメニューの**実ラベル**(`plugin.xml:1093` Explain Code / `:1117` Generate Tests / `:1129` Refactor Code)、MIT 表記を保持、上流の誤記(`duo_tutorial.ts:36-37`: Command と Control の OS が逆)を修正。 |
| R9 | **ユーザーの編集を上書きしない。** ファイルが既にあれば内容を書き換えず開くだけ(§9.2 の分岐表)。 |
| R10 | 表示条件は VSCode の `commandPalette` の `when`(`package.json:218-221`: `config.gitlab.duoChat.enabled && gitlab:chatAvailable && gitlab:chatAvailableForProject`)に対応させる。Eclipse では `duo_chat_enabled == true` がその等価物(§11.3)。 |

### 5.3 F3 ターミナル出力の説明

| ID | 要件 |
|---|---|
| R11 | Terminal ビュー(`org.eclipse.terminal.view.ui.TerminalsView` および旧 `org.eclipse.tm.terminal.view.ui.TerminalsView`)と Console ビュー(`org.eclipse.ui.console.ConsoleView`)の右クリックメニューに「Explain Terminal Output with Duo」(`package.json:128`)を出す。 |
| R12 | 選択テキストを **`$/gitlab/ai-context/add`** で LS に追加し、**追加が完了してから** classic chat に `newPrompt {prompt: "explainTerminalOutput"}` を送る(順序は `duo_chat_commands.ts:42-43` の `await` と同じ)。 |
| R13 | 表示条件は `chat_terminal_context` feature の engaged チェックがゼロ(`chat_state_manager.ts:54-56`)かつ `duo_chat_enabled == true`。 |
| R14 | 選択がない/空のときは何も送らず、ユーザーに通知する(§14)。VSCode は無言で戻る(`duo_chat_commands.ts:38-40`)が、Eclipse には「最後のコマンド」フォールバックがないため、無言だと押しても何も起きないように見える。 |
| R15 | UI スレッドをブロックしない。LS 応答待ちは `CompletableFuture` + タイムアウト(§15)。 |
| R16 | クリップボードを使う経路(§9.3 第 3 段)は、**必ず元の内容を復元する**(`finally`)。 |
| R17 | 選択内容・クリップボード内容・ファイル内容を**ログに出さない**(§19)。 |

### 5.4 非機能要件

| ID | 要件 |
|---|---|
| N1 | 新規 OSGi 依存ゼロ(`Require-Bundle` は `build.gradle.kts:162-192` のまま)。Terminal / Console には `plugin.xml` の `menuContribution` と汎用ワークベンチ API(`HandlerUtil` / `ISelectionService` / `IHandlerService` / SWT `Clipboard`)だけで到達する。 |
| N2 | 判断ロジックは SWT 非依存の純関数/クラスに置き、SWT に触る殻は注入シームの背後に置く(既存パターン: `ClipboardWriter.kt:49-52`、`NotificationUtils.kt:29-33`、`WorkspaceFileOpener.kt:46-51`、`ChatIntentRouter.kt:26-32`)。 |
| N3 | 既存の 36 件失敗集合と detekt ベースラインを変えない(`verify.sh` が `FAILSET_IDENTICAL`)。 |

## 6. 前提条件と制約

### 6.1 共通制約(#8 より)

- ディレクトリ構成の変更禁止。新規コードは `src/main/kotlin/com/gitlab/eclipse/` 配下の既存パッケージ体系に追加する。
- ビルドシステムの変更禁止。`build.gradle.kts` / `gradle.properties` / `detekt.yml` は変更しない。
- ドキュメントはコミットしない(本設計書はレビュー専用ブランチのみ)。
- プロトコル定数は実ソースで確定してから計画に埋め込む(本書 §11 / §12 は全て LS map と VSCode ソースから抽出済み)。

### 6.2 新規バンドル依存を導入しない(確定)

対象プラットフォームは `eclipseRelease = "4.33"`(`build.gradle.kts:160`)で、`eclipseDependencies`(`:162-192`)に Terminal / Console / Debug 系バンドルはない。Terminal は 2025 年に CDT から eclipse.platform へ移設され名前空間が `org.eclipse.tm.terminal.*` → `org.eclipse.terminal.*` に変わった((§11.3 の両 id を参照))ため、依存を張ると **どちらか一方の世代でしか解決しない**。Console(`org.eclipse.ui.console`)は D10 設計 §6.2 でも不採用にした。

**したがって F3 は「メニュー寄与 + 汎用 API」だけで成立させる。** 型に触らないので両世代を同時に扱える。代償として Terminal 側の実挙動は本コンテナで検証できず、§26 の U-item として実機に委ねる。

### 6.3 検証上の制約

- devcontainer は headless。SWT / Display / Clipboard / ワークベンチを要する箇所はテストできない。決定層と殻を分け、殻は手動検証(§21)に回す。
- `./gradlew build` は 36 件失敗で BUILD FAILED が正常。合否は `.superpowers/sdd/phase5b/verify.sh` の `FAILSET_IDENTICAL` で判定する。
- detekt は `detektMain` / `detektTest` を明示実行し、ベースラインとの一致で判定する。

### 6.4 lsp4j の制約(#20 の教訓)

- `GitLabLanguageServer` は `LanguageServer` を継承しない(`GitLabLanguageServer.kt:27-37`)。新メソッドはこのインターフェースに **直接** 追加する。
- **オーバーライドに `@JsonRequest` を重ねない**(`:84-91` の KDoc: 重ねると `Multiple methods for name` になる)。
- クライアント側 `GitLabLanguageServerClient` に同名メソッドがあると応答型が `Object` に潰れる(`:84-91`、`pluginRequest` の事例)。**`$/gitlab/ai-context/add` はクライアント側に存在しない**(クライアントの ai-context 系は `$/gitlab/ai-context/git-diff`(`GitLabLanguageServerClient.kt:79`)と `$/gitlab/ai-context/editor-selection`(`:94`)の受信のみ)ので、戻り値を `CompletableFuture<Boolean?>` と宣言してよい。`GitLabLanguageServerPluginRequestTest.kt:18-31` と同型の宣言テストで固定する(§23)。

## 7. システム構成

```
 F1 ──────────────────────────────────────────────────────────────────────────
   $/gitlab/featureStateChange ("authentication")
        │ GitLabLanguageServerClient.kt:107 の case に 1 行追加
        ▼
   AuthenticationSourceProvider ── gitlab_authenticated ──▶ plugin.xml visibleWhen
   (AbstractSourceProvider)                                  (Sign in 項目)
   ShowDuoForum / ShowDuoDocumentation ──▶ BrowserLauncher(既存)
   SignIn ──▶ ShowSettings(既存クラスを別 command id に再割当)

 F2 ──────────────────────────────────────────────────────────────────────────
   DuoTutorialHandler ─▶ DuoTutorialProjectPlanner(純ロジック: 状態 → 行動)
        │                        │
        │                        ▼
        │               DuoTutorialWorkspaceWriter(WorkspaceJob, rule = root)
        │                        │ done
        ▼                        ▼ asyncExec
   DuoTutorialContent    openInActiveEditor(IFile)  ──▶ didOpen / CodeSuggestionsManager(既存)

 F3 ──────────────────────────────────────────────────────────────────────────
   popup:<Terminal/Console> ──▶ ExplainTerminalOutputCommandHandler
        │  ExecutionEvent
        ▼
   TerminalOutputSelectionReader(純ロジック: ISelection → String?)
        │ null → ClipboardSelectionCapture(第 3 段、UI スレッド、finally 復元)
        ▼ text
   ExplainTerminalOutputCommand(純ロジック: 順序・タイムアウト・分岐)
        │ 1. languageServer.addAiContextItem(item)   $/gitlab/ai-context/add
        │ 2. true → openDuoChatWindowWithClassicPrompt(NewPromptRequest("explainTerminalOutput"))
        ▼
   LanguageServerBrowserView.requestClassicPrompt → ChatIntentRouter → GitLabDuoChatWebViewClient
   (既存経路 DuoChatWindow.kt:30-32 / ChatIntentRouter.kt:46-57 / GitLabDuoChatWebViewClient.kt:12-23)

   $/gitlab/featureStateChange ("chat_terminal_context") ─▶ TerminalContextStateService
                                                            ── duo_chat_terminal_context_available
```

## 8. コンポーネントの責務

### 8.1 F1(パッケージ `com.gitlab.eclipse.authentication` / `com.gitlab.eclipse.handlers`)

| コンポーネント | 責務 | 根拠・既存パターン |
|---|---|---|
| `AuthenticationSourceProvider` | `AbstractSourceProvider`。変数 `gitlab_authenticated` を `"unknown"` / `"true"` / `"false"` の 3 値で提供。`update(FeatureStateChange)` で `allChecks` を見て、`checkId` が `authentication-required` または `invalid-token` で `engaged == true` のものがあれば `"false"`、なければ `"true"`。`fireSourceChanged` は UI スレッド(`asyncExec`)。 | check id は LS map @23528968(`AUTHENTICATION_REQUIRED = "authentication-required"`, `INVALID_TOKEN = "invalid-token"`)。既存 `AuthenticationStateService.kt:38` も `authentication-required` を見る。プロバイダの形は `DuoChatStateService.kt:10-53` の複製 |
| `ShowDuoForum` / `ShowDuoDocumentation` | `AbstractHandler`。固定 URL を `BrowserLauncher.open`(`navigation/BrowserLauncher.kt:12-20`)で開く。 | `ShowDocumentation.kt:14-21` と同型。URL は定数として `companion object` に置き、テストで文字列一致を固定する |
| `SignIn`(command id のみ) | ハンドラクラスは **既存 `ShowSettings`**(`ShowSettings.kt:10-19`)を別 command id に割り当てる。 | 「同一クラス・複数 command id」は `ShowDiagnostics` / `ShowDiagnosticsFromSidePanel` で既に採用(`plugin.xml:902-910`) |

**3 値にする理由**: `Boolean` にすると「まだ届いていない」と「認証済み」が区別できず、起動直後に Sign in 項目が一瞬出る/出ないの揺れが起きる(R4)。既存 `AuthenticationStateService.kt:25` も `Boolean?` で「未知」を持つ。

**Koin 登録**: `AuthModule.kt:6` に `single<AuthenticationSourceProvider>` を追加し、`ChatModule.kt:18-32` と同じく `ISourceProviderService.getSourceProvider("gitlab_authenticated")` で **ワークベンチが生成したインスタンスを解決**する(2 インスタンス化すると `fireSourceChanged` が届かない。`ChatModule.kt:25-26` の P1-K コメントが同じ事故の記録)。

### 8.2 F2(パッケージ `com.gitlab.eclipse.codesuggestions.tutorial`)

| コンポーネント | 責務 | 根拠・既存パターン |
|---|---|---|
| `DuoTutorialContent` | `object`。プロジェクト名 `PROJECT_NAME = "GitLab Duo Tutorial"`、ファイル名 `FILE_NAME = "duo_tutorial.js"`、本文 `TEXT`(Kotlin raw string)。 | VSCode も本文をソース内テンプレートに持つ(`duo_tutorial.ts:3-4`)。本リポジトリでも `McpConfigService.kt:45` が `DEFAULT_CONFIG_TEMPLATE` を Kotlin 定数で持つ。**リソースファイルにしない**ので `src/main/resources`(現在 `plugin.xml` / `log4j2.xml` / `icons` のみ)にもビルドにも触れない |
| `DuoTutorialProjectPlanner` | 純ロジック。「プロジェクトの有無・開閉・所有・ディスク上のフォルダの有無・ファイルの有無」を入力に、実行すべき行動列(`CreateProject` / `OpenProject` / `CreateFile` / `OpenEditor` / `Refuse(reason)`)を返す(§9.2 の分岐表)。`IProject` に触らず、状態を `data class` で受ける。 | `ChatIntentRouter.kt:26-32`(決定だけを切り出す)と同じ動機 |
| `DuoTutorialWorkspaceWriter` | `WorkspaceJob`。`runInWorkspace` で行動列を実行。`rule = workspace.root`、`isUser = true`。 | `DiagnosticMarkerService.kt:96-107`(`WorkspaceJob` + `rule` + `schedule`)、`ClonedProjectImporter.kt:176-200`(`create` → `open` と補償) |
| `DuoTutorialHandler` | `AbstractHandler`。状態の読み取り → Planner → Writer の起動 → 完了時に `asyncExec` で `openInActiveEditor`(`utils/EditorOpening.kt:34-47`)。 | — |

### 8.3 F3(パッケージ `com.gitlab.eclipse.chat.terminal` / `com.gitlab.eclipse.chat.commands` / `com.gitlab.eclipse.lsp.messages`)

| コンポーネント | 責務 | 根拠・既存パターン |
|---|---|---|
| `AiContextItem` / `AiContextItemMetadata`(`lsp.messages`) | LS の `AIContextItemSchema` に一致する DTO(§12.3)。 | LS map @25790766 |
| `TerminalAiContextItems` | 純ロジック。テキストから `AiContextItem` を組み立てる(`id = UUID`、`category = "terminal"`、`metadata` 固定値)。 | `gitlab_chat_terminal_context.ts:23-37, 42-49` |
| `TerminalOutputSelectionReader` | 純ロジック。`ISelection`(current / menu)から文字列を取り出す 2 段(§9.3)。リフレクションはここに閉じる。 | — |
| `ClipboardSelectionCapture` | 第 3 段(クリップボード)。**UI スレッド専用**。`ClipboardTarget` 相当のシーム(`ClipboardWriter.kt:15-25` を読み取り・クリアまで拡張した `ClipboardPort`)の背後で、退避 → プレースホルダ書込 → コピー実行 → 読み出し → `finally` 復元。 | `ClipboardWriter.kt:101-125`(dispose を `finally`、`SWTError` を捕まえる) |
| `ExplainTerminalOutputCommand` | 純ロジック。「テキスト → add → 結果分岐 → prompt 送信」の順序・タイムアウト・失敗分岐を `CompletableFuture` で表現。LS 呼び出しと chat 送信は関数として注入。 | `WorkspaceFileOpener.kt:46-51` の注入シーム |
| `ExplainTerminalOutputCommandHandler`(`chat.commands`) | `AbstractHandler`。`HandlerUtil` で選択と部位を取り、Reader → (Capture) → Command を組み立てる。二重起動ガード。 | `ChatCommandHandler.kt:11-37` |
| `TerminalContextStateService`(`chat`) | `AbstractSourceProvider`。変数 `duo_chat_terminal_context_available`(Boolean)。`update(FeatureStateChange)` で `allChecks?.none { it.engaged } ?: false`。 | `DuoChatStateService.kt:16-33` の複製。engaged になる check は `chat-include-terminal-context-unavailable`(LS map @23530333)、その値は `DuoFeature.IncludeTerminalContext` の可否(@28544002) |

## 9. 処理フロー

### 9.1 F1

1. LS が `$/gitlab/featureStateChange` を送る → `GitLabLanguageServerClient.kt:107` の `"authentication"` case に `service<AuthenticationSourceProvider>().update(change)` を **1 行追加**(既存 `AuthenticationStateService.update` はそのまま)。
2. プロバイダが `gitlab_authenticated` を発火 → メニューの `visibleWhen` が再評価される。
3. 項目の動作: Forum / Duo Documentation は外部ブラウザ、Sign in は設定ダイアログ。いずれも同期・即時・LS 往復なし。

**メニューは一度だけ生成される**(`ShowPluginStatusMenu.kt:15-28`: `menu == null` のときだけ `populateContributionManager`)。`visibleWhen` を持つ寄与項目の可視性が、生成後の変数変化に追随するかは **U1(実機確認)**。追随しない場合の代替は `ICommandService.refreshElements`(`ChatAvailabilityService.kt:93-98` が `chatStatus` に使っている)ではなく、`ShowPluginStatusMenu` で毎回 `menuManager.update(true)` を呼ぶ 1 行の追加になる(§21 に影響として記載)。

### 9.2 F2

```
execute(event)                                   ← UI スレッド
  ├ 1. state = 状態の読み取り(IProject.exists/isOpen、.project の有無、ディスク上のフォルダ、IFile.exists)
  ├ 2. plan = DuoTutorialProjectPlanner.plan(state)
  ├ 3. plan が Refuse → 通知して終了
  ├ 4. Writer(WorkspaceJob, rule=root).schedule()   ← バックグラウンド
  │      runInWorkspace: CreateProject → OpenProject → CreateFile を順に
  └ 5. Job 完了(JobChangeAdapter.done、OK のとき) → asyncExec { openInActiveEditor(file) }
```

**分岐表(R9。`state` → 行動)**:

| プロジェクト `GitLab Duo Tutorial` | ディスク上のフォルダ | `duo_tutorial.js` | 行動 |
|---|---|---|---|
| なし | なし | — | `CreateProject`(既定ロケーション = ワークスペース直下)→ `OpenProject` → `CreateFile` → `OpenEditor` |
| なし | **あり**(過去に削除したがフォルダが残った等) | — | `CreateProject`(既定ロケーションのまま。`.project` が残っていれば Eclipse がそれを再利用する)→ `OpenProject` → ファイルの有無で次行へ |
| あり・閉じている | — | — | `OpenProject` → ファイルの有無で次行へ |
| あり・開いている | — | なし | `CreateFile` → `OpenEditor` |
| あり・開いている | — | あり | **`OpenEditor` のみ**(内容は一切触らない) |
| あり・開いている・**同名の別プロジェクト**(ユーザー自身のもの) | — | — | 区別する手段が名前しかないため、**同名なら自分のものとみなす**。`duo_tutorial.js` がなければ作り、あれば開く。ユーザーのファイルを消したり書き換えたりする行動は表に存在しない |

「自分のプロジェクトか」を判定する専用のマーカー(nature 等)は**置かない**。置くとディレクトリ構成・`plugin.xml` の拡張が増えるうえ、表のどの行でも破壊的な行動がないので判定の必要がない。

**ファイル作成は `IFile.create(InputStream, false, monitor)`**(`force = false`)。ディスク上に同名ファイルがあり Eclipse が未同期なら `CoreException` になり、その場合は `refreshLocal` してから「あり」行に落とす(上書きしない)。

**エディタの種類**: `IDE.openEditor(page, file)`(`EditorOpening.kt:41`)はエディタレジストリの既定で開く。JavaScript 用エディタ(Wild Web Developer 等)が入っていなければ Eclipse 既定のテキストエディタになる。**どちらも `ITextEditor`** なので `CodeSuggestionsManager.kt:86-95` の `editor is ITextEditor` を通り、セッションが張られる。Generic Editor が選ばれた場合も `ExtensionBasedTextEditor` は `ITextEditor` である。実際に何が選ばれるかは **U2(実機確認)**。

**非 git プロジェクトと LS のプロジェクト方針**: LS の `duo-disabled-for-project` チェックは、`enabledWithoutGitlabProject === true` なら常に非 engaged(LS map @28563384)。Eclipse の既定は `true`(`PreferenceInitializer.kt:22`、`GitLabLanguageServerConfigurationService.kt:154-156` で送信)。`false` にしていても、GitLab プロジェクトが見つからないフォルダは `DuoProjectStatus.NonGitlabProject`(@28416839)であり `DuoDisabled` ではないので engaged にならない(@28562112: `NonGitlabProject` は `hasDuoAccess` を変えない)。**チュートリアルプロジェクトは Duo を無効化しない。**

**ワークスペースフォルダの通知**: 本プラグインは `workspaceFolders` を起動時の設定と `validateConfiguration` に載せる(`ProjectsWorkspaceFolder.kt:6-9`、`ConfigurationValidationService.kt:33`、`GitLabLanguageServerProcessProvider.kt:298`)が、プロジェクト追加時の `didChangeWorkspaceFolders` 送信は grep で見当たらない。新プロジェクトが LS に即時伝わらなくても上記のとおり Duo は無効化されず、Code Suggestions は `didOpen` 単位で動く。**通知の追加は本サイクルの対象外**(U3 として記録)。

### 9.3 F3

```
execute(event)                                            ← UI スレッド
  ├ 0. inFlight.compareAndSet(false, true) に失敗 → return(二重起動)
  ├ 1. text = TerminalOutputSelectionReader.read(current, menu)   ← 第 1・2 段(クリップボード不使用)
  ├ 2. text == null && 部位が Terminal → text = ClipboardSelectionCapture.capture()   ← 第 3 段
  ├ 3. text が null/空 → 通知(§14)、inFlight=false、return
  ├ 4. ExplainTerminalOutputCommand.run(text)             ← 以降バックグラウンド(future 連鎖)
  │     a. item = TerminalAiContextItems.selected(text)
  │     b. languageServer.addAiContextItem(item).orTimeout(10s)
  │     c. 結果 true → asyncExec { openDuoChatWindowWithClassicPrompt(NewPromptRequest("explainTerminalOutput", null)) }
  │        false / 例外 / timeout → 通知(§14)
  └ 5. whenComplete で inFlight=false
```

**選択テキストの取得 3 段(Reader が 1・2、Capture が 3)**:

| 段 | 対象 | 手段 | 根拠 |
|---|---|---|---|
| 1 | Console | `HandlerUtil.getActiveMenuSelection(event) ?: getCurrentSelection(event)` が `ITextSelection` → `.text` | `TextConsolePage` は `getSite().setSelectionProvider(fViewer)`、`fViewer` は `TextConsoleViewer extends SourceViewer`、`TextViewer.getSelection()` は `TextSelection` / `BlockTextSelection` / `MultiTextSelection`(いずれも `ITextSelection`)を返す(eclipse.platform `master` `debug/org.eclipse.ui.console/src/org/eclipse/ui/console/TextConsolePage.java`、`TextConsoleViewer.java`、eclipse.platform.ui `master` `bundles/org.eclipse.jface.text/src/org/eclipse/jface/text/TextViewer.java`)。`ConsoleView` は `PageBookView` で、ページの選択プロバイダを転送する(`PageBookView.java` `SelectionProvider.getSelection()` → `site.getSelectionProvider().getSelection()`)。既存の `HandlerUtil` 併用順は `OpenMrFileHandler.kt:60-61` |
| 1' | Terminal | `getCurrentSelection(event)` が `IStructuredSelection` で `firstElement is String` → その文字列 | Terminal の `TabFolderManager` は `ISelectionProvider` で、**マウスで範囲選択した mouseUp 時に `fireSelectionChanged(new StructuredSelection(terminal.getSelection()))` を `asyncExec` で発火**する(eclipse.platform `master` `terminal/bundles/org.eclipse.terminal.view.ui/.../internal/tabs/TabFolderManager.java` `TerminalControlSelectionListener.mouseUp`)。`ITerminalViewControl.getSelection()` は `String`(`org.eclipse.terminal.control/.../ITerminalViewControl.java`)。ワークベンチの `SelectionService.selectionChanged` はこの発火を `ESelectionService.setSelection` に流し、`HandlerUtil.getCurrentSelection` は `ISources.ACTIVE_CURRENT_SELECTION_NAME` を読む(eclipse.platform.ui `master` `.../internal/e4/compatibility/SelectionService.java`、`.../handlers/HandlerUtil.java`)。**実機での到達性は U4** |
| 2 | Terminal | 選択(menu / current)に `CTabItem` があれば `item.getData()` を取り、その実行時クラスが `getSelection(): String` を持てば**リフレクションで**呼ぶ | `TabFolderManager.getSelection()` は `new StructuredSelection(activeTabItem)`、`createTabItem` は `item.setData(terminal)`(`ITerminalViewControl`)(同上 `TabFolderManager.java`)。型に触らないので依存ゼロ(N1)。旧 `org.eclipse.tm.terminal.*` 世代で同じ形かは **U5** |
| 3 | Terminal | **クリップボード経由**(Q3=(b)): 退避 → プレースホルダ書込 → `IHandlerService.executeCommand("org.eclipse.ui.edit.copy", null)` → `TextTransfer` 読出 → `finally` で復元 | VSCode `gitlab_chat_terminal_context.ts:64-69, 71-79, 96-102`。command id は `IWorkbenchCommandConstants.EDIT_COPY = "org.eclipse.ui.edit.copy"`(eclipse.platform.ui `master` `.../ui/IWorkbenchCommandConstants.java`)。**ただし Terminal ビューが `org.eclipse.ui.edit.copy` のハンドラを登録している証拠は見つからなかった**(`TerminalsView.java` / `TabFolderMenuHandler.java` / `TabFolderToolbarHandler.java` に `setGlobalActionHandler` / `activateHandler` / `EDIT_COPY` の参照なし。コピーはメニュー内の `TerminalActionCopy` インスタンス)。したがって第 3 段は **`ICommandService.getCommand(EDIT_COPY).isHandled()` が真のときだけ実行**し、偽なら第 3 段をスキップして「選択なし」に落とす。実機でこの段が有効になるかは **U6** |

第 1' 段と第 2 段は、ユーザー決定 Q3=(b) を**置き換えるものではなく前段に置く**。理由: (i) 依存ゼロという (b) の目的を満たし、(ii) クリップボードを一切触らないので復元失敗のリスクがなく、(iii) 上記のとおり (b) 単独ではコピーコマンドが未ハンドルで**何も取れない可能性が高い**。(b) は最後の砦として仕様どおり残す。

**クリップボード復元の意味論(第 3 段、R16)**:

| 論点 | 方針 |
|---|---|
| 退避する形式 | **`TextTransfer` のみ**(`clipboard.getContents(TextTransfer.getInstance()) as? String`)。VSCode も `readText()` / `writeText()` の文字列だけ(`:65, 98`) |
| 非テキスト形式(ファイル・画像・RTF 等) | **失われる。** プレースホルダの書込で他形式は消え、復元は文字列しか戻せない。VSCode と同じ制限であり、正直に記載する。緩和: 第 1'・2 段で取れた場合は第 3 段に入らない(= 通常経路ではクリップボードに触らない) |
| 退避結果が `null`(クリップボードが空/非テキスト) | 復元は `clearContents()`。「空だったものを空に戻す」 |
| 復元のタイミング | **`finally`**。コピー実行・読出のどこで例外が出ても必ず走る |
| 復元の失敗 | ログ(例外クラス名のみ)+ 通知「クリップボードを復元できませんでした」。VSCode は `log.debug` だけ(`:99-101`)だが、Eclipse ではユーザーの貼り付け内容が置き換わったままになるので通知する |
| `Clipboard` の生成・破棄 | 1 回の capture につき 1 つ生成し `finally` で `dispose()`(`ClipboardWriter.kt:101-109` と同じ) |
| スレッド | **UI スレッド専用**(`Clipboard` は `Display` を要する。ハンドラの `execute` は UI スレッドで呼ばれるのでその場で同期的に行う)。`executeCommand` も同期 |
| プレースホルダ | `"GitLab for Eclipse has temporarily reset the clipboard to read terminal selection"`(VSCode `:6-7` の文言の製品名だけ置換)。読出結果がプレースホルダと一致 = 何もコピーされなかった |
| `SWTError` | `Clipboard.setContents` はクリップボードを確保できないと `SWTError`(`Error` 系)を投げる(`ClipboardWriter.kt:111-116`)。`Throwable` ではなく `SWTError` と `RuntimeException` を個別に捕まえる |

## 10. 等価表(F1: VSCode 項目 → Eclipse 項目)

| # | VSCode(`show_quick_pick_menu.ts:108-118` の順) | Eclipse(状態メニュー) | 差異と正当化 |
|---|---|---|---|
| 1 | 状態行(`utils.ts:145-163`)→ `gl.showOutput` | 既存「Show Extension Logs」(`plugin.xml:1696-1699`)。状態文言は `chatStatus` / `codeSuggestionsStatus` の 2 行(`:1606-1616`) | 状態が「1 行 + ログ」ではなく「2 行 + ログ」。情報量は同等以上 |
| 2 | Code Suggestions Enabled/Disabled(`utils.ts:46-56`) | 既存 `codeSuggestionsStatus`(`:1612-1616`) | 既存 |
| 3 | Duo Chat Enabled/Disabled(`utils.ts:71-75`) | 既存 `chatStatus`(`:1606-1610`) | 既存 |
| 4, 7 | Sandbox 状態 / トグル(`utils.ts:101-143`) | **なし** | 対象外(§3)。VSCode でも LS が `sandbox` state を返さない環境では出ない(`:105, 131`) |
| 5 | 言語別トグル(`utils.ts:82-96`) | 既存 `toggleCodeSuggestionsForLanguage`(`:1626-1630`) | 既存 |
| 6 | グローバルトグル(`utils.ts:77-80`) | 既存 `toggleCodeSuggestions`(`:1618-1622`) | 既存 |
| 2' | 未認証時: 「Duo unavailable / Please sign in to GitLab」→ `gl.authenticate`(`utils.ts:60-69`、`constants.ts:6, 9`)。**2〜7 を置き換えて 1 項目に畳む**(`show_quick_pick_menu.ts:100-102`) | **「Sign in to GitLab」項目を未認証時のみ追加表示**(R3/R4)。他の項目は畳まない | 固定メニューでは項目の差し替えができない(Q1=(A))。未認証時も既存項目は「Disabled」表示のまま残るが、サインイン導線が同じメニューにあるという点で目的は満たす。動作は `gl.authenticate` ではなく設定ページ(本プラグインの認証入口は設定ページ、`AuthenticationStateService.kt:72`) |
| 8 | Duo Settings(`constants.ts:12`) | 既存「Show Settings」(`:1634-1638`) | 既存 |
| 9 | Documentation → `https://docs.gitlab.com/user/gitlab_duo/`(`constants.ts:13, 17`) | **新規「GitLab Duo Documentation」**(R2)。既存「Show Documentation」(`:1640-1643`、拡張ドキュメント)は併存 | Eclipse は 2 項目。VSCode に「拡張自体のドキュメント」項目はないが、削ると既存導線が消えるので残す |
| 10 | GitLab Forum「Help and feedback」→ `https://forum.gitlab.com/c/gitlab-duo/52`(`constants.ts:14-15, 18`) | **新規「GitLab Forum (Help and feedback)」**(R1) | ラベルに説明を括弧で含める(Eclipse のメニュー項目に description 欄がない) |

**台帳 D4「Duo クイックピックメニュー」は上表をもって ✅(等価)とする。** 未達は「未認証時の畳み込み」と「Sandbox」の 2 点で、いずれも意図的(Q1=(A)、§3)。

## 11. API / インターフェース

### 11.1 コマンド(`org.eclipse.ui.commands`、category `gitlab-eclipse-plugin`(`plugin.xml:5`))

| コマンド ID | name | ハンドラ |
|---|---|---|
| `gitlab-eclipse-plugin.commands.ShowDuoForum` | GitLab Forum | `com.gitlab.eclipse.handlers.ShowDuoForum` |
| `gitlab-eclipse-plugin.commands.ShowDuoDocumentation` | GitLab Duo Documentation | `com.gitlab.eclipse.handlers.ShowDuoDocumentation` |
| `gitlab-eclipse-plugin.commands.SignIn` | Sign in to GitLab | `com.gitlab.eclipse.handlers.ShowSettings`(既存) |
| `gitlab-eclipse-plugin.commands.DuoTutorial` | GitLab Duo Tutorial | `com.gitlab.eclipse.codesuggestions.tutorial.DuoTutorialHandler` |
| `gitlab-eclipse-plugin.commands.ExplainTerminalOutput` | Explain Terminal Output with Duo | `com.gitlab.eclipse.chat.commands.ExplainTerminalOutputCommandHandler` |

5 件とも **`<command>` を宣言する**(§4-4 の癖を繰り返さない)。

### 11.2 ソース変数(`org.eclipse.ui.services`、`plugin.xml:502-522` に追記)

| 変数 | プロバイダ | 値 |
|---|---|---|
| `gitlab_authenticated` | `com.gitlab.eclipse.authentication.AuthenticationSourceProvider` | `"unknown"` / `"true"` / `"false"` |
| `duo_chat_terminal_context_available` | `com.gitlab.eclipse.chat.TerminalContextStateService` | `true` / `false` |

`GitLabLanguageServerClient.kt:106-115` のディスパッチに case を 2 つ足す: `"authentication"` に 1 行追加、`"chat_terminal_context"` を新規(現状 `else -> return@forEach` に落ちている)。`FeatureStateStore.record`(`:105`)は変更しない。

### 11.3 メニュー寄与(`org.eclipse.ui.menus`)

**F1(状態メニュー、`menu:gitlab-eclipse-plugin.menus.statusWidgetMenu`)** — 既存ブロック(`:1605-1711`)は触らず、**別の `menuContribution` を追記**して位置指定する:

| 項目 | locationURI | visibleWhen |
|---|---|---|
| Sign in to GitLab | `menu:gitlab-eclipse-plugin.menus.statusWidgetMenu?before=StatusActions`(separator 名は `:1624`) | `<with variable="gitlab_authenticated"><equals value="false"/></with>` |
| GitLab Duo Tutorial | `menu:gitlab-eclipse-plugin.menus.statusWidgetMenu?after=gitlab-eclipse-plugin.menus.showDocumentation`(項目 id は `:1642`) | `<with variable="duo_chat_enabled"><equals value="true"/></with>` |
| GitLab Duo Documentation | 同上 | なし |
| GitLab Forum (Help and feedback) | 同上 | なし |

`visibleWhen` の書式は `plugin.xml:1096-1100`(`checkEnabled="false"` + `<with variable>`)に合わせる。

**F3(右クリックメニュー)**:

| 対象 | locationURI | 根拠 |
|---|---|---|
| Terminal(新) | `popup:org.eclipse.terminal.view.ui.TerminalsView?after=additions` | ビュー id と、同バンドル自身が使う locationURI(eclipse.platform `master` `terminal/bundles/org.eclipse.terminal.view.ui/plugin.xml`)。コンテキストメニューは `TabFolderMenuHandler` が `registerContextMenu(menuManager, selectionProvider)`(2 引数 = 部位 id を menu id とする形)で登録 |
| Terminal(旧) | `popup:org.eclipse.tm.terminal.view.ui.TerminalsView?after=additions` | CDT `CDT_11_6_0` `terminal/plugins/org.eclipse.tm.terminal.view.ui/plugin.xml`(ビュー id `org.eclipse.tm.terminal.view.ui.TerminalsView`、同 locationURI) |
| Console(プロセスコンソール) | `popup:org.eclipse.debug.ui.ProcessConsoleType.#ContextMenu` | `TextConsolePage` は menu id を `getConsole().getType() + ".#ContextMenu"`(type が null なら `#ContextMenu`)で `registerContextMenu` する(`TextConsolePage.java`)。`ID_PROCESS_CONSOLE_TYPE = "org.eclipse.debug.ui.ProcessConsoleType"`(eclipse.platform `master` `debug/org.eclipse.debug.ui/ui/org/eclipse/debug/ui/IDebugUIConstants.java`)。`IOConsole extends TextConsole`、`createPage` は `IOConsolePage`(`IOConsole.java`) |
| Console(メッセージコンソール) | `popup:org.eclipse.ui.MessageConsole.#ContextMenu` | `MESSAGE_CONSOLE_TYPE = "org.eclipse.ui.MessageConsole"`(`IConsoleConstants.java`) |
| Console(型なし) | `popup:#ContextMenu` | 同 `TextConsolePage.java` |

`popup:<id>` が `registerContextMenu` の id に対応することは `PopupMenuExtender.java` の `"popup:" + menuId`(eclipse.platform.ui `master`)と、本リポジトリの `popup:#AbstractTextEditorContext`(`plugin.xml:1051`)の実績で確認済み。ProcessConsole が実際に `ID_PROCESS_CONSOLE_TYPE` を type に持つことと、Gradle / Maven 等の他コンソール型への追随は **U7**(代替案 `popup:org.eclipse.ui.popup.any` + `activePartId == org.eclipse.ui.console.ConsoleView` は文字列 `org.eclipse.ui.popup.any` を実ソースで確認できなかったため採らない)。

**F3 の visibleWhen(全寄与で共通)**:

```xml
<visibleWhen checkEnabled="false">
  <and>
    <with variable="duo_chat_enabled"><equals value="true"/></with>
    <with variable="duo_chat_terminal_context_available"><equals value="true"/></with>
  </and>
</visibleWhen>
```

VSCode の `when`(`package.json:255-257`)の `config.gitlab.duoChat.enabled && gitlab:chatAvailable && gitlab:chatAvailableForProject` は、LS の `chat` feature の checks 列 `chat-disabled-by-user` / `chat-no-license` 等 / `duo-disabled-for-project`(LS map @23531198 `CHAT_CHECKS_PRIORITY_ORDERED`)に全て含まれるので、`duo_chat_enabled`(`DuoChatStateService.kt:18-19`: `chat` の checks が全て非 engaged)1 変数で等価。`gitlab.featureFlags.languageServerWebviews` は Eclipse では常に LS webview を使うため該当なし。

### 11.4 キーバインド(F3)

**追加しない。** VSCode は `ctrl+alt+t` / `cmd+alt+t`(`package.json:279-284`)だが、Eclipse 側の既定バインド(Pleiades を含む)との衝突を本コンテナで確認できない。Terminal バンドル自身は `CTRL+SHIFT+M3+T` を使っている(同 `plugin.xml`)。衝突したときの実害(ユーザーの既存操作を奪う)が利益を上回るので、右クリックメニューのみとする。**U8**: 実機で空いていれば後続サイクルで追加。

### 11.5 LSP(クライアント → サーバ)

`GitLabLanguageServer` に追加:

```kotlin
/** LS `AIContextEndpoints.ADD`. 応答は「追加を受理したか」。名前衝突なし(§6.4)。 */
@JsonRequest("$/gitlab/ai-context/add")
fun addAiContextItem(item: AiContextItem): CompletableFuture<Boolean?>
```

- メソッド名と応答型: LS map @28600934(`ADD: '$/gitlab/ai-context/add'`、`request: AIContextItem; response: boolean`)。サーバ側の受け口は @29322157(`onRequest(AIContextEndpoints.ADD, item => chatContextManager.addSelectedContextItem(item))`)。
- **サーバの `false` の意味**: @25793690 — provider 解決や `provider.addSelectedContextItem` が**例外**を投げたときだけ `false`。provider 側(@25788850)は policy 不許可・subType 不一致・**id 重複**を `logger.error` するだけで例外にしないため、その場合でも **`true` が返る**。つまり `true` は「追加された」ではなく「拒否されなかった」。id を毎回 UUID にするのはこのため(重複で黙って捨てられる)。
- `newPrompt` は既存経路のまま(`ExtensionToPluginNotification(pluginId = CLASSIC_WEBVIEW_ID, type = "newPrompt", payload = NewPromptRequest)`、`GitLabDuoChatWebViewClient.kt:12-23`)。LS 側は `extension.onNotification("newPrompt", ({prompt, fileContext}) => controller.handleExtensionPrompt(prompt, fileContext))`(@26312928)で、`explainTerminalOutput` は `fileContextOptional`(@26258270)、送られる本文は `"Explain this terminal output"`(@26245433 `commandToContentMap`)。

## 12. データモデル

### 12.1 F1

なし(状態は `String` 1 値)。

### 12.2 F2 チュートリアル本文(`DuoTutorialContent.TEXT`)

構成(VSCode 7 節 → Eclipse 6 節):

| 節 | VSCode(`duo_tutorial.ts`) | Eclipse 版の記述 |
|---|---|---|
| 冒頭 | L4-10 | 同文 + `Adapted from the GitLab Workflow extension for VS Code (duo_tutorial.ts). MIT License, Copyright (c) 2020-present GitLab Inc.`(VSCode `LICENSE:1-3`。本リポジトリも MIT、`LICENSE:1-3`) |
| 1) Code Completion | L12-24 | 単語受入 = **Alt + →**(`M3+ARROW_RIGHT`、`plugin.xml:473-477`; macOS は Option)、行受入 = **Ctrl/Cmd + →**(`M1+ARROW_RIGHT`、`:466-470`)、全体 = **Tab**(`:459-463`)。手動要求 **Ctrl/Cmd + .**(`:446-450`)、却下 **Esc**(`:452-456`)、候補切替 **Alt + ] / Alt + [**(`:480-491`)を追記 |
| 2) Code Generation | L26-46 | 同上のキー。**上流の誤記(L36-37 の Command / Control の OS 逆転)を修正** |
| 3) Non-Agentic Chat | L48-66 | **Alt + D**(`M3+D`、`:494-498`; macOS は Option + D)。「Insert」はチャットのコードスニペット挿入ボタン(`InsertCodeSnippetService` が存在)として記述 |
| 4) Quick Chat | L68-83 | **削除**(Eclipse に Quick Chat がない。`fibonacci` のサンプルコードも削除) |
| 4) Explain Code | L85-110 | 「右クリック → GitLab Duo Chat → **Explain Code**」(`:1093`)。VSCode の「Explain Selected Snippet」表記は Eclipse の実ラベルに置換 |
| 5) Generate Tests | L112-125 | 「右クリック → GitLab Duo Chat → **Generate Tests**」(`:1117`)。Alt+T 相当のバインドはない |
| 6) Refactor Code | L127-156 | 「右クリック → GitLab Duo Chat → **Refactor Code**」(`:1129`) |

Kotlin raw string 上の注意: 本文の正規表現 `[^\\s@]+$` は TS テンプレートリテラル用に `\\` が二重化されている(`duo_tutorial.ts:123`)。Kotlin raw string では `\s` と一重にし、`$` は `${'$'}` で書く。**この 2 点は A6 でテストする**(本文に `\\s` が現れないこと、`$/` が現れること)。

### 12.3 F3 DTO(`com.gitlab.eclipse.lsp.messages`)

LS の `AIContextItemSchema`(LS map @25790766)に一致させる:

```kotlin
data class AiContextItem(
  val id: String,                    // UUID v4 文字列(VSCode: uuidv4())
  val category: String,              // "terminal"
  val content: String?,              // 選択テキスト(terminal は必須。LS @29181126: 「常に content 済み」)
  val metadata: AiContextItemMetadata,
)

data class AiContextItemMetadata(
  val title: String,                 // "Selected command:"
  val enabled: Boolean,              // true
  val subType: String,               // "snippet"(provider の type、@29181126 super('snippet'))
  val icon: String,                  // "terminal"
  val secondaryText: String,         // ""
  val subTypeLabel: String,          // "Selected terminal output"
  val disabledReasons: List<String>? = null,
  val languageId: String? = null,
)
```

固定値の根拠: `gitlab_chat_terminal_context.ts:26-36, 44-49`、LS `TerminalMetadata`(@29180454)。`lastCommand` 系の値(`:50-55`)は Eclipse に該当経路がないので持たない。

**`SecretRedactionConventionTest` への影響なし**: フィールド名に `token/secret/password/credential/passphrase/key/cert`(`SecretRedactionConventionTest.kt:16-17`)を含まない。`content` は検出語ではない。

## 13. トランザクション境界

- **F1**: なし(状態の読み取りと外部プロセス起動のみ)。
- **F2**: `WorkspaceJob.runInWorkspace` 1 回が境界。`rule = workspace.root` で、プロジェクト作成・open・ファイル作成が 1 つのワークスペース操作としてロックされる。`create` 成功後 `open` に失敗した場合の補償(`ClonedProjectImporter.kt:176-200` の `deregisterAfterFailedOpen` 相当)は**行わない**: 閉じたプロジェクトが残っても次回実行の分岐表「あり・閉じている」で回復するため(§20)。
- **F3**: LS 側の「コンテキスト追加」と「プロンプト送信」は **2 つの独立した操作**で、原子性はない。`add` 成功後に prompt 送信が失敗すると、terminal コンテキストが LS に選択されたまま残る(次の会話でコンテキストとして付く可能性)。VSCode も同じ構造(`duo_chat_commands.ts:42-43`)。`$/gitlab/ai-context/remove`(@28600934)で戻すことは可能だが、prompt 送信の失敗は「ビューが出せない」類(`DuoChatWindow.kt:96-118`)であり、そのときはユーザーが再試行するだけなので**補償しない**(§14 に記載)。

## 14. エラー処理

| 機能 | 失敗 | 扱い |
|---|---|---|
| F1 | 外部ブラウザ起動失敗 | `BrowserLauncher.open` が捕捉してログ(`BrowserLauncher.kt:14-18`)。追加処理なし |
| F1 | `authentication` の `allChecks` が null | `"unknown"` のまま(表示しない) |
| F2 | Planner が `Refuse`(ワークスペースが読み取り専用、`.project` の解析失敗 = `readDescription` が null 等) | 通知「Could not create the GitLab Duo Tutorial project. See the Error Log.」+ ログは**例外クラス名のみ** |
| F2 | `WorkspaceJob` 内の `CoreException` | Job の `IStatus` を ERROR で返す(Eclipse が Job のエラーダイアログを出す)。部分状態(プロジェクトだけできた等)は残してよい — 次回実行で分岐表が拾う |
| F2 | エディタを開けない(`PartInitException`) | `WorkspaceFileOpener.kt:89-103` と同じ包み方。通知 + クラス名ログ。**ファイルは作られている**ので Project Explorer から開ける |
| F3 | 選択が空 / プレースホルダ / Console でも Terminal でもない部位 | 通知「Select text in the Terminal or Console first.」(R14)。LS 往復なし |
| F3 | LS 未接続(`languageServer == null`、`GitLabLanguageServerWrapper.kt:24-25`) | 通知「GitLab Duo Chat is not available.」 |
| F3 | `add` が `false` | 通知「Could not add the terminal output to Duo Chat.」**prompt は送らない**。VSCode は結果を見ずに送る(`:42-43`)が、コンテキストなしの「Explain this terminal output」は空振りになるため、意図的な差 |
| F3 | `add` が例外 / タイムアウト(§15) | 同上 + ログはクラス名のみ(`TimeoutException` も同様) |
| F3 | prompt 送信側の失敗(ビューが出せない) | 既存 `reportCannotShow`(`DuoChatWindow.kt:121-124`)が通知・ログする。補償なし(§13) |
| F3 | クリップボード復元失敗 | §9.3 の表 |
| F3 | 二重起動 | 2 回目は無視(通知なし、debug ログ 1 行) |

通知は全て `NotificationUtils.show`(`NotificationUtils.kt:29-50`、任意スレッドから安全)。

## 15. タイムアウトとリトライ

- **F3 の `add`**: `.orTimeout(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS)` の既存定数と同じ **10 秒**(`GitLabLanguageServerClient.kt:49, 77, 118`)。`ExplainTerminalOutputCommand` は値を引数で受け(テストでは短く)、既定 10 秒。
- **UI スレッドを待たせない**: `execute` は future を返した時点で戻る。継続は `whenComplete` で行い、UI に触る継続だけ `asyncExec`(`ClipboardWriter.kt:43-44`、`WorkspaceFileOpener.kt:37-38`: **`syncExec` は使わない**)。
- **リトライしない。** ユーザーが右クリックし直せばよい。
- **F1 / F2**: ネットワーク往復がないためタイムアウトなし。F2 の `WorkspaceJob` はキャンセル可能(`IProgressMonitor.isCanceled` を各行動の頭で確認)。

## 16. 冪等性

| 機能 | 冪等性 |
|---|---|
| F1 | 完全に冪等(閲覧・ダイアログ表示) |
| F2 | **冪等**(§9.2 の分岐表: 2 回目以降は既存を開くだけ)。ただし「ユーザーが本文を空にした」場合もそのまま開く(空 ≠ 未作成。書き戻さない) |
| F3 | **非冪等**(毎回新しい UUID でコンテキストが追加され、毎回新しいプロンプトが送られる)。これは仕様(VSCode も同じ)。二重起動ガードは「同時」だけを防ぐ |

## 17. 並行処理

| 論点 | 方針 |
|---|---|
| ソース変数の更新 | `featureStateChange` は lsp4j のディスパッチスレッド(`GitLabLanguageServerClient.kt:99-103` は `runAsync`)。`fireSourceChanged` は `asyncExec` で UI へ(`DuoChatStateService.kt:25-32` と同じ)。フィールド書込は UI 転送前に行う(`ChatAvailabilityService.kt:19-21` の方針) |
| F2 のワークスペース操作 | `WorkspaceJob` + `rule = root`。UI スレッドではワークスペースを変更しない。完了通知 → `asyncExec` → エディタ。ハンドラ内の状態読み取り(`exists` / `isOpen`)は UI スレッドで行う軽い読み取りで、Job 内で**再度**読み直して分岐する(読み取りと実行の間にユーザーがプロジェクトを消す可能性) |
| F2 の二重起動 | Job に `rule = root` があるので 2 本は直列化される。2 本目は Job 内の再読み取りで「あり」行に落ち、開くだけ |
| F3 のスレッド境界 | `execute`(UI): 選択読み取り・クリップボード・`inFlight` 取得。`add` の応答: lsp4j スレッド。prompt 送信: `asyncExec`(`LanguageServerBrowserView.kt:39-42` の契約: `requestClassicPrompt` は UI スレッドで呼ぶ) |
| F3 の二重起動 | `AtomicBoolean inFlight`。`whenComplete` で必ず戻す(例外・タイムアウト含む) |
| クリップボードの競合 | 第 3 段の退避〜復元は UI スレッド上で同期的に完結するため、その間に他の UI コードが割り込むことはない。他プロセスがその瞬間に書き込む競合は防げない(VSCode と同じ) |

## 18. 認証と認可

- 新たな認証経路はない。F1 の Sign in は既存の設定ページを開くだけ。
- `gitlab_authenticated` は **真偽/未知の 3 値だけ**を持ち、トークン等は一切通らない。
- F3 の `add` は LS が `DuoFeature.IncludeTerminalContext`(`"include_terminal_context"`、LS map @25785954)の可否で制御する(@28544002)。Eclipse 側の `visibleWhen` はその状態を UI に映すだけで、認可の主体は LS。

## 19. ログ、監視、監査

**規約**: 例外は**クラス名のみ**、パス・選択内容・クリップボード内容・ファイル本文・URI は出さない(`ClipboardWriter.kt:73-74`、`WorkspaceFileOpener.kt:31, 63-64, 96`、`GitLabLanguageServerClient.kt:123-125`)。旧いハンドラにはパスを出すものがある(`OpenMcpUserConfigHandler.kt:19`)が、新規コードは新しい規約に従う。

| 機能 | 出すログ |
|---|---|
| F1 | ハンドラ実行 1 行(`info`、URL は固定定数なので出してよい) |
| F2 | Job 開始/完了(`info`)。行動列(`CreateProject` 等の**種別名**のみ)。パスは出さない |
| F3 | `add` の結果(`true`/`false`/`timeout`/例外クラス名)。**選択テキストの長さすら出さない**(長さから内容を推測できる場面があるため。`debug` レベルでも出さない) |

## 20. 障害時の復旧方法

- **F1**: 項目が出ない → `gitlab_authenticated` の値を Diagnostics(`ShowDiagnostics`)の feature state で確認(`authentication` の checks)。設定ページは従来どおりメニュー「Show Settings」から到達できる。
- **F2**: 中途半端な状態(プロジェクトだけ・閉じたまま)はコマンド再実行で回復(分岐表)。完全に消したいときは Project Explorer からプロジェクト削除(内容ごと)。
- **F3**: コンテキストが LS に残った(add 後に prompt 失敗)→ 次の会話が始まる前に chat の「/reset」(= `newConversation`)で新規会話にする(@26245433 `newConversation: "/reset"`)。クリップボードが復元されなかった → 通知の文面どおり、ユーザーが再コピーする。

## 21. 既存機能への影響

| 既存 | 影響 |
|---|---|
| `plugin.xml` | **追記のみ**、4 箇所: `org.eclipse.ui.commands`(`:4-434`)末尾にコマンド 5 件 / `org.eclipse.ui.services`(`:502-522`)にソースプロバイダ 2 件 / `org.eclipse.ui.handlers`(`:590-991`)末尾にハンドラ 5 件 / `org.eclipse.ui.menus`(`:1011-1711`)末尾に `menuContribution` ブロック(F1 用 2 つ + F3 用 5 つ)。既存の状態メニューブロック(`:1605-1711`)は**編集しない**(位置は `?before=` / `?after=` で指定) |
| `GitLabLanguageServerClient.kt:106-115` | case 追加 2 件。既存 case の動作は不変 |
| `GitLabLanguageServer.kt` | メソッド 1 件追加(§11.5)。既存メソッドは不変 |
| `AuthModule.kt` / `ChatModule.kt` | `single` を各 1 件追加 |
| `AuthenticationStateService` | **変更しない**(ポップアップは従来どおり) |
| `ShowPluginStatusMenu` | 原則変更しない。U1 が否のときだけ `menuManager.update(true)` 1 行 |
| `ShowSettings` | 変更なし(command id が 1 つ増えるだけ) |
| `ExplainCode` / `FixCode` / `GenerateTests` / `RefactorCode` | 変更なし(`ChatCommandHandler` は流用せず、F3 は独立クラス。`ChatCommandHandler.kt:17-18` の「アクティブなテキストエディタが必要」という前提がターミナルには当てはまらない) |
| `ShowDocumentation` | 変更なし。ログ文言の誤り(`ShowDocumentation.kt:16` が "settings" と言う)は本サイクルでは触らない |
| `CodeSuggestionsManager` / `GitLabLanguageServerOpenFilesService` / `LanguageServerLanguage` | 変更なし(F2 はこれらの既存動作に乗るだけ) |
| `FeatureStateStore` / Diagnostics | 変更なし |
| LS へ送る設定ペイロード | 変更なし |

## 22. 移行方法 / ロールバック方法

- **移行なし。** 新規の永続データはない(F2 のプロジェクトはユーザーのワークスペース内の通常プロジェクトで、プラグインは所有権を主張しない)。
- **ロールバック**: PR の revert で完結。残留物は F2 が作った `GitLab Duo Tutorial` プロジェクトのみ(無害。ユーザーが削除できる)。LS 側に残る terminal コンテキストは LS プロセスの寿命内のみ。

## 23. テスト方針

Kotest `DescribeSpec` + MockK(`build.gradle.kts:146-147`、既存例 `ClipboardWriterTest.kt:1-40`、`ChatCommandHandlerTest.kt:1-60`)。**新規 spec を作る**(`StyledText` をモックする既存 spec は headless で生成に失敗するため)。

| Spec | 対象 | 主な検証 |
|---|---|---|
| `AuthenticationSourceProviderTest` | F1 | 初期 `"unknown"`; `authentication-required` engaged → `"false"`; `invalid-token` engaged → `"false"`; 全て非 engaged → `"true"`; `allChecks == null` → 変化なし。`getProvidedSourceNames`。UI 転送はシームで捕捉(`applyForTest` 相当) |
| `ShowDuoForumTest` / `ShowDuoDocumentationTest` | F1 | URL 定数が `constants.ts:17-18` の文字列と一致; `BrowserLauncher` シームが 1 回呼ばれる |
| `DuoTutorialContentTest` | F2 | `PROJECT_NAME` / `FILE_NAME`; 本文に MIT 表記・`Alt + D`・`Explain Code`・`Generate Tests`・`Refactor Code` を含む; `Quick Chat` / `fibonacci` / `Alt> + C` / `Alt> + T` / `Alt> + R` を**含まない**; `\\s` を含まず `$/` を含む(§12.2) |
| `DuoTutorialProjectPlannerTest` | F2 | §9.2 の分岐表の**全行**を 1 例ずつ。「ファイルあり」の全行で `CreateFile` が出ないこと(R9) |
| `DuoTutorialHandlerTest` | F2 | `Refuse` → 通知のみ・Job 未起動; Job 完了 OK → エディタ open シームが 1 回; Job 失敗 → open されない |
| `TerminalOutputSelectionReaderTest` | F3 | `ITextSelection` → text; 空 `ITextSelection` → null; `StructuredSelection(String)` → text; `StructuredSelection(fake CTabItem-like)` でリフレクション経路(`getSelection(): String` を持つ fake の data)→ text; `getSelection` なし → null; `getSelection` が例外 → null(伝播しない) |
| `ClipboardSelectionCaptureTest` | F3 | fake `ClipboardPort` で: 退避 → プレースホルダ → コピー → 読出 → 復元の**呼び出し順**; 読出がプレースホルダ → null; 復元は例外時も走る(`finally`); 退避 null → `clear` で復元; `SWTError` を捕捉; `dispose` が必ず 1 回; コピーコマンド未ハンドル(`isHandled=false`)→ 一切書き込まない |
| `TerminalAiContextItemsTest` | F3 | `category="terminal"`, `metadata` の 6 固定値, `content` = 入力, `id` が UUID 形式で毎回異なる |
| `ExplainTerminalOutputCommandTest` | F3 | `add` true → prompt 送信 1 回・`NewPromptRequest("explainTerminalOutput", null)`; false → 送信なし+通知; 例外 → 同; タイムアウト(短い timeout) → 同; **prompt 送信が add 完了より前に呼ばれない**(add の future を手動で完了させて順序を検証); LS null → 通知 |
| `ExplainTerminalOutputCommandHandlerTest` | F3 | 選択なし → 通知・LS 未呼出; 二重起動 → 2 回目無視; `inFlight` が失敗経路でも戻る |
| `GitLabLanguageServerAiContextRequestTest` | F3 | `@JsonRequest("$/gitlab/ai-context/add")` と `ServiceEndpoints.getSupportedMethods` にその名前が含まれること; 戻り値が `CompletableFuture<Boolean>`(`GitLabLanguageServerPluginRequestTest.kt:18-31` の写し) |
| `TerminalContextStateServiceTest` | F3 | `DuoChatStateServiceTest` の写し |

**手動(実機)**: メニュー表示・可視性の切替・Terminal/Console での選択取得・クリップボード復元・エディタ種別・Code Suggestions の発火(§25)。

## 24. 受け入れ条件

| # | 条件 | 検証 |
|---|---|---|
| A1 | 状態メニューに「GitLab Forum (Help and feedback)」「GitLab Duo Documentation」「GitLab Duo Tutorial」が「Show Documentation」の直後に並び、Forum / Duo Documentation が §5.1 の URL を開く | 実機(M1)+ 自動(URL 定数) |
| A2 | 未認証(トークン未設定/無効)のとき「Sign in to GitLab」が `StatusActions` 区切りの直前に出て、設定ページを開く。認証後は消える(再起動なしで) | 実機(M2)。再起動なしの切替は U1 |
| A3 | `gitlab_authenticated` が §8.1 の規則どおり 3 値を取る | 自動 |
| A4 | チュートリアルコマンドで `GitLab Duo Tutorial/duo_tutorial.js` が作られエディタで開き、`didOpen` の `languageId` が `javascript`(LS ログで確認)で、本文中の `const multiply` 直後で Code Suggestions が出る | 実機(M3) |
| A5 | 2 回目の実行、本文を編集後の実行、プロジェクトを閉じた後の実行のいずれでも、**本文が書き換わらない**で開く | 実機(M4)+ 自動(Planner 全行) |
| A6 | 本文が §12.2 の翻案規則を満たす(MIT 表記あり、Quick Chat なし、実ラベル、エスケープ正しい) | 自動 |
| A7 | チュートリアル実行後も Duo Chat / Code Suggestions が `duo-disabled-for-project` にならない | 実機(M3 で Diagnostics を確認) |
| A8 | Terminal(新旧いずれかの世代)で範囲選択 → 右クリック → 「Explain Terminal Output with Duo」で、Duo Chat に「Explain this terminal output」が投げられ、選択内容がコンテキストとして付く | 実機(M5)。到達段(1' / 2 / 3)を記録 |
| A9 | Console(実行結果のプロセスコンソール)で同様 | 実機(M6) |
| A10 | 選択なしで実行 → 通知のみ、LS 往復なし | 実機(M7)+ 自動 |
| A11 | 第 3 段に入ったとき、実行前にクリップボードにあった文字列が実行後も残っている | 実機(M8。第 3 段に入る条件は U6)+ 自動(順序・`finally`) |
| A12 | `add` が完了する前に `newPrompt` が送られない | 自動 |
| A13 | `add` が false / 例外 / タイムアウトで prompt が送られず通知される | 自動 |
| A14 | `chat_terminal_context` が engaged のとき、Terminal / Console のメニューに項目が出ない | 実機(M9: `include_terminal_context` 無効なインスタンス、または LS ログで engaged を確認) |
| A15 | `GitLabLanguageServer` に `$/gitlab/ai-context/add` が宣言され、`ServiceEndpoints` が認識する | 自動 |
| A16 | ログに選択内容・クリップボード内容・パスが出ない | コードレビュー + 自動(fake ログで文字列不在) |
| A17 | `verify.sh` が `FAILSET_IDENTICAL`、detekt がベースラインちょうど | CI 相当 |
| A18 | `Require-Bundle` が変わらない(`build.gradle.kts` 差分ゼロ) | `git diff` |

## 25. 手動検証手順

対象環境: Windows(Pleiades 2025-12)と Linux(GTK)。両方で M1〜M9 を行い、結果を PR 本文に記載する。

| # | 手順 | 期待 |
|---|---|---|
| M1 | ステータストリムの「GitLab Duo」をクリック | 「Show Documentation」の直後に Tutorial / Duo Documentation / Forum の順で並ぶ。後 2 者をクリックすると既定ブラウザで §5.1 の URL が開く |
| M2 | 設定でトークンを空にして LS を再起動(「Restart Language Server」)→ メニューを開く → トークンを設定して保存 → メニューを開き直す | 前者で「Sign in to GitLab」が `Duo Chat:` / `Code Suggestions:` の下に出て、クリックで設定ページ。後者で消えている(**再起動なし**で消えれば U1 = 追随する) |
| M3 | 「GitLab Duo Tutorial」をクリック | プロジェクトと `duo_tutorial.js` が作られエディタで開く。開いたエディタの種類(既定テキスト / Generic / JS エディタ)を記録(U2)。`const multiply` 直後で Space → 候補が出る。`language_server.log` の `didOpen` に `javascript`。Diagnostics で `duo-disabled-for-project` が engaged でない |
| M4 | 本文を 1 行編集して保存 → プロジェクトを閉じる → 再度コマンド | 編集が残ったまま開く |
| M5 | Terminal ビューでシェルを開き `ls -la` 等を実行 → 出力をマウスで範囲選択 → 右クリック → 項目 | Duo Chat が開き「Explain this terminal output」+ 選択内容がコンテキストとして付く。**ログ(debug)でどの段で取れたかを記録**(1' / 2 / 3)。旧世代(`org.eclipse.tm.terminal`)の Eclipse があればそちらでも |
| M6 | Java の Hello World を実行 → Console の出力を範囲選択 → 右クリック → 項目 | 同上 |
| M7 | 選択せずに右クリック → 項目 | 通知「Select text in the Terminal or Console first.」。Chat は開かない |
| M8 | 任意の文字列をコピーしておく → M5 と同じ操作 → どこかに貼り付け | **第 3 段に入った場合**でも元の文字列が貼り付く。第 3 段に入らなかった(1'/2 で取れた)場合はその旨を記録 |
| M9 | `include_terminal_context` が無効な GitLab インスタンス(または LS ログで `chat-include-terminal-context-unavailable` engaged の状態) | Terminal / Console の右クリックに項目が出ない |

## 26. 未決事項

| ID | 内容 | 扱い |
|---|---|---|
| U1 | 一度生成された状態メニューの項目可視性が、`gitlab_authenticated` の変化に再起動なしで追随するか(`ShowPluginStatusMenu.kt:15-28` は 1 回だけ populate) | 実機(M2)。否なら `menuManager.update(true)` を `execute` に 1 行追加 |
| U2 | `duo_tutorial.js` を `IDE.openEditor` が何のエディタで開くか(既定テキスト / Generic / Wild Web Developer)。いずれも `ITextEditor` である前提 | 実機(M3)。`ITextEditor` でないエディタが選ばれたら `IDE.openEditor(page, file, "org.eclipse.ui.DefaultTextEditor")` へ固定する |
| U3 | プロジェクト追加時に LS へ `workspace/didChangeWorkspaceFolders` を送っていない(既存の欠落) | 本サイクルの対象外。§9.2 のとおり Duo は無効化されないため実害なし。別 issue として記録 |
| U4 | Terminal の mouseUp 発火(`StructuredSelection(String)`)が `HandlerUtil.getCurrentSelection` まで届くか(§9.3 第 1' 段) | 実機(M5、到達段の記録) |
| U5 | 旧 `org.eclipse.tm.terminal.*` 世代の `TabFolderManager` が同じ `setData(terminal)` / 文字列発火を持つか(CDT 側ソースは `plugin.xml` のみ確認) | 実機。旧世代の Eclipse が手元にあれば M5 |
| U6 | Terminal ビューで `org.eclipse.ui.edit.copy` がハンドルされるか(第 3 段が有効になる条件)。ソース上はハンドラ登録が見当たらない | 実機(M5/M8)。否なら第 3 段は到達不能コードになるため、**実機結果を見てから削除するか残すかを決める**(ユーザー決定 Q3=(b) の扱い) |
| U7 | ProcessConsole の type が `org.eclipse.debug.ui.ProcessConsoleType` として `#ContextMenu` に付くか。Gradle / Maven 等の他コンソール型 | 実機(M6)。他コンソール型は必要になったら locationURI を追加 |
| U8 | F3 のキーバインド(VSCode `ctrl+alt+t`)を Eclipse で空いているか | 後続サイクル |

## 27. 想定されるリスク

| リスク | 影響 | 緩和 |
|---|---|---|
| Terminal で選択テキストがどの段でも取れない(U4/U5/U6 が全て否) | F3 の Terminal 側が「選択なし」通知しか出せない | Console 側は `ITextSelection` で確実。Terminal 側は実機結果を PR に記録し、否なら次サイクルで依存追加(Q3=(a) 相当)を再提案 |
| クリップボードの非テキスト内容の消失(第 3 段) | ユーザーの貼り付け内容が失われる | 第 1'・2 段を前に置き、第 3 段は `isHandled()` ゲート付き。制限は通知文言と PR 本文に明記 |
| 状態メニューの可視性が追随しない(U1) | 未認証項目が出っぱなし/出ない | `update(true)` の 1 行で解消可能 |
| `WorkspaceJob` の `rule = root` が他の Job と競合して待たされる | チュートリアルが数秒遅れる | `isUser = true` で進捗が見える。ワークスペース全体のビルド中でも正しく直列化される |
| 同名プロジェクトがユーザーのもの | ユーザーのプロジェクトに `duo_tutorial.js` を 1 つ追加する | 分岐表に破壊的行動がない。名前は「GitLab Duo Tutorial」で衝突の蓋然性は低い |
| lsp4j の名前衝突(§6.4) | 応答が `LinkedTreeMap` になり `ClassCastException` | クライアント側に同名がないことを確認済み。宣言テスト(A15)で固定 |
| `plugin.xml` の並行 PR 衝突 | マージ時の手戻り | 追記のみ・末尾のみ。既存ブロックは非編集 |
| headless で UI 配線を検証できない | メニューが出ない等が実機まで露見しない | UI 接触部を最小化し、配線と SWT 殻は `fable` が担当。M1〜M9 |

## 28. 実装タスク分割案

実装 PR は 1 本(`feat/duo-lightweight-commands`、base `gitlab-ls-9.3.0`)。タスクは独立に実装・レビューし、`plugin.xml` は各タスクが自分の追記だけを行う(全て末尾追記なので衝突しない)。

| # | タスク | 内容 | モデル | 理由(CLAUDE.md の表) |
|---|---|---|---|---|
| T1 | F1 | `AuthenticationSourceProvider` + Koin 登録 + ディスパッチ 1 行 + `ShowDuoForum` / `ShowDuoDocumentation` + `plugin.xml`(コマンド 3・ハンドラ 3・ソースプロバイダ 1・メニュー 2 ブロク)+ spec 3 本 | `opus` | 既存パターンの複製 + plugin.xml 配線。誤りは次段レビューで検出可能 |
| T2 | F2 | `DuoTutorialContent` / `Planner` / `WorkspaceWriter` / `Handler` + `plugin.xml`(コマンド 1・ハンドラ 1・メニュー項目)+ spec 3 本 | `fable` | `WorkspaceJob` → `asyncExec` → エディタの**スレッド境界**と `IProject` 状態の再読み取りを含む。headless で検証不能 |
| T3 | F3 | DTO + `GitLabLanguageServer` 追加 + `TerminalContextStateService` + ディスパッチ case + `SelectionReader` / `ClipboardSelectionCapture` / `Command` / `Handler` + `plugin.xml`(コマンド 1・ハンドラ 1・ソースプロバイダ 1・メニュー 5 ブロック)+ spec 6 本 | `fable` | SWT `Clipboard`・リフレクション・lsp4j ディスパッチ・UI スレッドホップ。全て「ユーザー実機でのみ発覚」する種類 |
| R1〜R3 | 各タスクのコードレビュー | — | `fable` | 唯一の安全網。実装者と別モデル(T1)/ 別インスタンス(T2, T3) |
| R4 | ブランチ全体レビュー + PR 本文(M1〜M9 の手順・U-item 一覧・台帳更新案) | — | `fable` | 同上 |
| L | 台帳 #7(D3 +2、D4 +1、D1 4 行を「対象外(単一アカウント運用)」に)、#14 / #8 の `vscode.comments` 記述訂正 | — | `haiku` | 即座に目視確認できる |

T3 の実装ブリーフには §9.3 の 3 段と §11.5 の LS 実値、§6.4 の lsp4j 注意、§19 のログ規約を**そのまま**埋め込む(実装者が LS map を再抽出しなくて済むように)。
