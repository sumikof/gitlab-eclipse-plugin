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

パリティは **74/85 → 77/85**(D3 +2、D4 +1)。ただし D3「ターミナル出力を説明」の ✅ は §9.3.4 の実機ゲート次第で、Terminal 側が全段不成立なら 🟡 とし **76/85 + 🟡1** になる。

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
| R9 | **ユーザーの編集を上書きしない。ユーザーのプロジェクト・フォルダに触らない。** ファイルが既にあれば内容を書き換えず開くだけ。同名のプロジェクト/フォルダがプラグイン作成のもの(§9.2 の所有記録と一致)でなければ、何も変更せず拒否する(§9.2 の分岐表)。 |
| R10 | 表示条件は VSCode の `commandPalette` の `when`(`package.json:218-221`: `config.gitlab.duoChat.enabled && gitlab:chatAvailable && gitlab:chatAvailableForProject`)に対応させる。Eclipse では `duo_chat_enabled == true` がその等価物(§11.3)。 |

### 5.3 F3 ターミナル出力の説明

| ID | 要件 |
|---|---|
| R11 | Terminal ビュー(`org.eclipse.terminal.view.ui.TerminalsView` および旧 `org.eclipse.tm.terminal.view.ui.TerminalsView`)と Console ビュー(`org.eclipse.ui.console.ConsoleView`)の右クリックメニューに「Explain Terminal Output with Duo」(`package.json:128`)を出す。 |
| R12 | 選択テキストを **`$/gitlab/ai-context/add`** で LS に追加し、**追加が完了してから** classic chat に `newPrompt {prompt: "explainTerminalOutput"}` を送る(順序は `duo_chat_commands.ts:42-43` の `await` と同じ)。 |
| R13 | 表示条件は `chat_terminal_context` feature の engaged チェックがゼロ(`chat_state_manager.ts:54-56`)かつ `duo_chat_enabled == true`。 |
| R14 | 選択がない/空のときは何も送らず、ユーザーに通知する(§14)。VSCode は無言で戻る(`duo_chat_commands.ts:38-40`)が、Eclipse には「最後のコマンド」フォールバックがないため、無言だと押しても何も起きないように見える。 |
| R15 | UI スレッドをブロックしない。LS 応答待ちは `CompletableFuture` + タイムアウト(§15)。 |
| R16 | クリップボードを使う経路(§9.3 第 3 段)は、**文字列以外の内容があれば実行せず**、実行した場合は**必ず元の内容を復元する**(`finally`)。 |
| R18 | ハンドラは LS 往復の前に、feature state と実行部位を自分で検証する(§9.3.1)。メニューの非表示を唯一の防御にしない。 |
| R19 | `content` は 400,000 UTF-16 コード単位を上限とし、超過時は末尾を残して通知する(§9.3.2)。 |
| R20 | `add` を送った後は、`current-items` で item の不在を確認するまで同じ接続の F3 再実行と他の classic prompt の送出を止める(§9.3.3 / §9.3.5)。確認できなければ安全側に倒し、LS 再起動を案内する。 |
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
| `AuthenticationSourceProvider` | `AbstractSourceProvider`。変数 `gitlab_authenticated` を `"unknown"` / `"true"` / `"false"` の 3 値で提供。**値は既存 `AuthenticationStateService` の 2 秒デバウンスを通った後にだけ更新する**(Codex round 3 P2 反映。`AuthenticationStateService.kt:31-33` が「LS 起動直後の連続通知は古い認証状態を含みうる」として debounce を必須にしているため)。`update(change, session)` は debounce 後の同じ `featureStateChange` を受け、`checkId` が `authentication-required` または `invalid-token` で `engaged == true` のものがあれば `"false"`、なければ `"true"`。**`session` が現在の接続と異なる更新は捨てる**(停止前に始まった debounce が再起動後に着地しても反映しない)。**LS の停止・終了で `reset()` し `"unknown"` に戻す**(§9.1)。`fireSourceChanged` は UI スレッド(`asyncExec`)。 | check id は LS map @23528968(`AUTHENTICATION_REQUIRED = "authentication-required"`, `INVALID_TOKEN = "invalid-token"`)。既存 `AuthenticationStateService.kt:38` も `authentication-required` を見る。プロバイダの形は `DuoChatStateService.kt:10-53` の複製 |
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
| `DuoTutorialOwnership` | 所有記録の読み書き(§9.2)。`record(id, locationUri)` / `isOwned(project)`(開いている・persistent property `duoTutorialId` が記録 ID と一致・`locationURI` が記録と一致)。設定ストアとプロジェクトへのアクセスは注入(テストで fake) | 非表示キーの前例 `DUO_CHAT_SELECTED_WEBVIEW`(`PreferenceConstants.kt:22`) |

### 8.3 F3(パッケージ `com.gitlab.eclipse.chat.terminal` / `com.gitlab.eclipse.chat.commands` / `com.gitlab.eclipse.lsp.messages`)

| コンポーネント | 責務 | 根拠・既存パターン |
|---|---|---|
| `AiContextItem` / `AiContextItemMetadata`(`lsp.messages`) | LS の `AIContextItemSchema` に一致する DTO(§12.3)。 | LS map @25790766 |
| `TerminalAiContextItems` | 純ロジック。テキストから `AiContextItem` を組み立てる(`id = UUID`、`category = "terminal"`、`metadata` 固定値)。 | `gitlab_chat_terminal_context.ts:23-37, 42-49` |
| `TerminalOutputSelectionReader` | 純ロジック。`ISelection`(current / menu)から文字列を取り出す 2 段(§9.3)。リフレクションはここに閉じる。 | — |
| `ClipboardSelectionCapture` | 第 3 段(クリップボード)。**UI スレッド専用**。`ClipboardTarget` 相当のシーム(`ClipboardWriter.kt:15-25` を読み取り・クリアまで拡張した `ClipboardPort`)の背後で、退避 → プレースホルダ書込 → コピー実行 → 読み出し → `finally` 復元。 | `ClipboardWriter.kt:101-125`(dispose を `finally`、`SWTError` を捕まえる) |
| `ExplainTerminalOutputCommand` | 純ロジック。「テキスト → add → 結果分岐 → prompt 送信」の順序・タイムアウト・失敗分岐を `CompletableFuture` で表現。LS 呼び出しと chat 送信は関数として注入。 | `WorkspaceFileOpener.kt:46-51` の注入シーム |
| `ExplainTerminalOutputCommandHandler`(`chat.commands`) | `AbstractHandler`。`HandlerUtil` で選択と部位を取り、Reader → (Capture) → Command を組み立てる。二重起動ガード。 | `ChatCommandHandler.kt:11-37` |
| `TerminalOutputGate` | 純関数。「捕捉した接続で terminal context が可か」(§9.3.1)と `activePartId` から実行可否を返す | — |
| `TerminalOutputLimit` | 純関数。400,000 UTF-16 コード単位の上限、末尾保持、サロゲートペア保護、切り詰めフラグ(§9.3.2) | LS map @26232942 `MAX_CONTENT_LENGTH` |
| `TerminalContextStateService`(`chat`) | `AbstractSourceProvider`。変数 `duo_chat_terminal_context_available`(Boolean、表示用)。内部には `(session, available)` の組を保持し、`update(change, session)` で `allChecks?.none { it.engaged } ?: false` を**その接続の値として**記録。`isAvailableFor(session)` は記録の `session` と同一参照のときだけ `available`、違えば `false`(§9.3.1) | `DuoChatStateService.kt:16-33` の形を複製し、接続への結び付けを追加。engaged になる check は `chat-include-terminal-context-unavailable`(LS map @23530333)、その値は `DuoFeature.IncludeTerminalContext` の可否(@28544002) |

## 9. 処理フロー

### 9.1 F1

1. LS が `$/gitlab/featureStateChange` を送る → 既存どおり `GitLabLanguageServerClient.kt:107` の `"authentication"` case が `AuthenticationStateService.update(change)` を呼ぶ。**`AuthenticationStateService` の debounce ブロック(`:33-46`)の中、判定の直前に `service<AuthenticationSourceProvider>().update(featureStateChange, session)` を 1 行追加**する(ポップアップの挙動は不変。`update` に `session` 引数を足し、クライアントは自分の `session` を渡す)。
1'. LS の停止・終了: `GitLabLanguageServerProcessProvider` が既に `SecurityScanLifecycle.onServerStopped()` を呼んでいる 2 箇所(`:228` 終了コールバック / `:262` 停止経路)に並べて `AuthenticationSourceProvider.reset()` と `TerminalContextStateService.reset()` を呼ぶ(表示用の変数を `unknown` / `false` に戻す)。
2. プロバイダが `gitlab_authenticated` を発火 → メニューの `visibleWhen` が再評価される。
3. 項目の動作: Forum / Duo Documentation は外部ブラウザ、Sign in は設定ダイアログ。いずれも同期・即時・LS 往復なし。

**メニューは一度だけ生成される**(`ShowPluginStatusMenu.kt:15-28`: `menu == null` のときだけ `populateContributionManager`)。`visibleWhen` を持つ寄与項目の可視性が、生成後の変数変化に追随するかは **U1(実機確認)**。追随しない場合の代替は `ICommandService.refreshElements`(`ChatAvailabilityService.kt:93-98` が `chatStatus` に使っている)ではなく、`ShowPluginStatusMenu` で毎回 `menuManager.update(true)` を呼ぶ 1 行の追加になる(§21 に影響として記載)。

### 9.2 F2

```
execute(event)                                   ← UI スレッド
  ├ 1. state = 状態の読み取り(IProject.exists/isOpen/locationURI、ディスク上のフォルダ、記録済み所有ロケーション、IFile.exists)
  ├ 2. plan = DuoTutorialProjectPlanner.plan(state)
  ├ 3. plan が Refuse → 通知して終了(ワークスペースは一切変更しない)
  ├ 4. Writer(WorkspaceJob, rule=root).schedule()   ← バックグラウンド
  │      runInWorkspace: 状態を再読み取りして plan を再計算(Refuse なら何もせず終了)→ 行動列を順に実行
  └ 5. Job 完了(JobChangeAdapter.done、OK のとき) → asyncExec { openInActiveEditor(file) }
```

**所有権の記録(Codex round 1 / round 2 / round 3 P1 反映)**: プラグインが作ったプロジェクトかどうかを**名前でもパスだけでも判定しない**。
新規作成時に次の 2 つを記録し、**両方が一致するときだけ**所有とみなす。

1. **ランダム ID**: 作成ごとに `UUID.randomUUID()` を生成し、(a) 作成したプロジェクトの **persistent property**
   `QualifiedName("com.gitlab.eclipse", "duoTutorialId")` と、(b) 非表示の設定キー `PreferenceConstants.DUO_TUTORIAL_PROJECT_ID = "gitlab.duoTutorial.projectId"` の両方に書く。
   persistent property はワークスペースのメタデータに保存され、**プロジェクトを削除すると消える**(同じフォルダを再 import しても復活しない)。
   これで「削除 → 同じパスにユーザーが無関係なフォルダを作成 → import」を識別できる。
2. **ロケーション URI**: `IProject.getLocationURI().toString()` を非表示キー `PreferenceConstants.DUO_TUTORIAL_PROJECT_LOCATION = "gitlab.duoTutorial.projectLocation"` に書く(プロジェクト記述を差し替えて別の場所を指させた場合の検出)。

**所有 = 同名プロジェクトが存在し、開いており、`getPersistentProperty(duoTutorialId)` が記録 ID と一致し、かつ `locationURI` が記録ロケーションと一致する。**
非表示の設定キーを持つ前例は `DUO_CHAT_SELECTED_WEBVIEW`(`PreferenceConstants.kt:22` / `LanguageServerBrowserView.kt:154`)。nature やマーカーファイルは使わない(`plugin.xml` の拡張もユーザーから見えるファイルも増やさない)。キー名は `SecretRedactionConventionTest.kt:16-17` の検出語を含まない。

- **閉じた同名プロジェクトは所有判定をしない**(persistent property は開いていないと読めない)。判定のためにユーザーのプロジェクトを開くことはせず、一律 `Refuse`(安全側)。
- **記録の順序**: 設定キーへの記録(ID・ロケーション)→ `create` → `open` → persistent property の設定 → `CreateFile`。property の設定前に失敗した場合、次回は「ID 不一致」で `Refuse` に倒れる(プロジェクトは残るので、ユーザーが削除すれば「なし・なし」行で作り直せる)。
- 記録は 1 組だけ(最新の作成)。設定ストアは `InstanceScope`(ワークスペース単位)なので、ワークスペースをまたいで所有を誤認しない。

**分岐表(R9。`state` → 行動)**:

| プロジェクト `GitLab Duo Tutorial` | 所有(ID とロケーションの両方一致) | ディスク上のフォルダ | `duo_tutorial.js` | 行動 |
|---|---|---|---|---|
| なし | — | なし | — | ID とロケーションを記録 → `CreateProject`(既定ロケーション = ワークスペース直下)→ `OpenProject` → property 設定 → `CreateFile` → `OpenEditor` |
| なし | **問わない** | あり | — | **`Refuse`**。プロジェクトの無い既存フォルダは一切取り込まない(パスは再利用されうるため、記録だけでは由来を証明できない)。通知「A folder named 'GitLab Duo Tutorial' already exists in the workspace location. Rename or remove it to use the tutorial.」 |
| あり・閉じている | **判定しない** | — | — | **`Refuse`**。プロジェクトを開かない。通知「A project named 'GitLab Duo Tutorial' exists but is closed. Open it and run the command again, or rename it.」 |
| あり・開いている | **不一致 / 未記録**(ユーザー自身の同名プロジェクト、削除後に再 import されたもの等) | — | — | **`Refuse`**。**ファイルを追加しない。** 通知「A project named 'GitLab Duo Tutorial' already exists and was not created by GitLab. Rename it to use the tutorial.」 |
| あり・開いている | 一致 | — | なし | `CreateFile` → `OpenEditor` |
| あり・開いている | 一致 | — | あり | **`OpenEditor` のみ**(内容は一切触らない) |

**ファイル作成は `IFile.create(InputStream, false, monitor)`**(`force = false`)。ディスク上に同名ファイルがあり Eclipse が未同期なら `CoreException` になり、その場合は `refreshLocal` してから「あり」行に落とす(上書きしない)。

**エディタの種類**: `IDE.openEditor(page, file)`(`EditorOpening.kt:41`)はエディタレジストリの既定で開く。JavaScript 用エディタ(Wild Web Developer 等)が入っていなければ Eclipse 既定のテキストエディタになる。**どちらも `ITextEditor`** なので `CodeSuggestionsManager.kt:86-95` の `editor is ITextEditor` を通り、セッションが張られる。Generic Editor が選ばれた場合も `ExtensionBasedTextEditor` は `ITextEditor` である。実際に何が選ばれるかは **U2(実機確認)**。

**非 git プロジェクトと LS のプロジェクト方針**: LS の `duo-disabled-for-project` チェックは、`enabledWithoutGitlabProject === true` なら常に非 engaged(LS map @28563384)。Eclipse の既定は `true`(`PreferenceInitializer.kt:22`、`GitLabLanguageServerConfigurationService.kt:154-156` で送信)。`false` にしていても、GitLab プロジェクトが見つからないフォルダは `DuoProjectStatus.NonGitlabProject`(@28416839)であり `DuoDisabled` ではないので engaged にならない(@28562112: `NonGitlabProject` は `hasDuoAccess` を変えない)。**チュートリアルプロジェクトは Duo を無効化しない。**

**ワークスペースフォルダの通知**: 本プラグインは `workspaceFolders` を起動時の設定と `validateConfiguration` に載せる(`ProjectsWorkspaceFolder.kt:6-9`、`ConfigurationValidationService.kt:33`、`GitLabLanguageServerProcessProvider.kt:298`)が、プロジェクト追加時の `didChangeWorkspaceFolders` 送信は grep で見当たらない。新プロジェクトが LS に即時伝わらなくても上記のとおり Duo は無効化されず、Code Suggestions は `didOpen` 単位で動く。**通知の追加は本サイクルの対象外**(U3 として記録)。

### 9.3 F3

```
execute(event)                                            ← UI スレッド
  ├ 0. handle = GitLabLanguageServerWrapper.currentSnapshot を 1 回だけ読む(proxy + session を組で捕捉、§9.3.5)
  │     null → 通知「GitLab Duo Chat is not available.」、return
  ├ 1. 実行時ゲート(§9.3.1): terminal context 可(**handle.session で観測した値に限る**)&& 部位が許可リスト
  │     否 → 通知して return(LS 往復は一切しない)
  ├ 2. 同じ接続に未解決の run がある → 通知「A previous request is still in progress.」、return(§9.3.3)
  │     run の接続が古い(LS 再起動済み)→ その run を破棄扱いにして続行
  ├ 3. text = TerminalOutputSelectionReader.read(current, menu)   ← 第 1・1'・2 段(クリップボード不使用)
  ├ 4. text == null && 部位が Terminal → text = ClipboardSelectionCapture.capture()   ← 第 3 段(前提条件つき)
  ├ 5. text が null/空 → 通知(§14)、return(run は登録されない)
  ├ 6. text = TerminalOutputLimit.apply(text)(§9.3.2。切り詰めたら通知)
  ├ 7. item = TerminalAiContextItems.selected(text)
  │     tracked = TrackedPrompt(payload = NewPromptRequest("explainTerminalOutput", null), attachment = item,
  │                             session = handle.session, 配送期限 30 秒)
  │     run を登録(session, tracked)
  └ 8. openDuoChatWindowWithTrackedClassicPrompt(tracked)   ← ここで F3 のハンドラは戻る
        以降の配送・文脈の追加・後始末は §9.3.5 の送出手順が行い、run は tracked の「解決」(§9.3.3)で終わる
```

**この時点では LS に何も追加していない。** 文脈の追加は、prompt を実際に送る直前(クライアントの送出手順の中、§9.3.5)に行う。したがって、ビューを出せない・classic 以外に解決した・後続の依頼で上書きされた・期限切れ・破棄のいずれで配送が止まっても、**LS 側に取り消すべきものが存在しない。**

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
| 第 3 段に入る前提条件(**Codex round 1 P1 反映**) | 次の**いずれか**に当たれば第 3 段を**実行しない**(クリップボードに一切書き込まない)。(1) `Clipboard.getAvailableTypes()` のいずれかが `RTFTransfer` / `HTMLTransfer` / `FileTransfer` / `ImageTransfer` / `URLTransfer` の `isSupportedType` で真(= 文字列以外の内容を持つ)。(2) 利用可能な形式が 1 つ以上あるのに `TextTransfer` で読めない(= 非テキストのみ)。(3) `EDIT_COPY` が未ハンドル。スキップ時は「選択なし」扱いで、通知は「Copy the output with the Terminal's Copy command, or select it and try again.」 |
| 退避・復元する形式 | 上の前提条件を通った場合、クリップボードは「空」か「文字列のみ」なので、**`TextTransfer` の退避・復元で元の内容が戻る**。VSCode も文字列だけを扱う(`:65, 98`) |
| 残るリスク | 上記 5 種の Transfer では識別できないアプリ固有形式が、文字列と一緒に置かれている場合(例: 一部アプリの独自形式)。その形式は失われうる。OS が自動合成する派生形式(Windows の `CF_LOCALE` / `CF_OEMTEXT` 等)は文字列から再合成されるので問題にならない。前提条件が実機で「普通にテキストをコピーした状態」を誤ってスキップしないかは **U9** |
| 退避結果が `null`(クリップボードが空) | 復元は `clearContents()`。「空だったものを空に戻す」 |
| 復元のタイミング | **`finally`**。コピー実行・読出のどこで例外が出ても必ず走る |
| 復元の失敗 | ログ(例外クラス名のみ)+ 通知「クリップボードを復元できませんでした」。VSCode は `log.debug` だけ(`:99-101`)だが、Eclipse ではユーザーの貼り付け内容が置き換わったままになるので通知する |
| `Clipboard` の生成・破棄 | 1 回の capture につき 1 つ生成し `finally` で `dispose()`(`ClipboardWriter.kt:101-109` と同じ) |
| スレッド | **UI スレッド専用**(`Clipboard` は `Display` を要する。ハンドラの `execute` は UI スレッドで呼ばれるのでその場で同期的に行う)。`executeCommand` も同期 |
| プレースホルダ | `"GitLab for Eclipse has temporarily reset the clipboard to read terminal selection"`(VSCode `:6-7` の文言の製品名だけ置換)。読出結果がプレースホルダと一致 = 何もコピーされなかった |
| `SWTError` | `Clipboard.setContents` はクリップボードを確保できないと `SWTError`(`Error` 系)を投げる(`ClipboardWriter.kt:111-116`)。`Throwable` ではなく `SWTError` と `RuntimeException` を個別に捕まえる |

#### 9.3.1 実行時ゲート(**Codex round 1 P1 反映**: メニューの非表示を認可境界にしない)

`visibleWhen` は表示を絞るだけで、Quick Access や `IHandlerService.executeCommand` からの直接実行は止めない。さらに LS は policy 不許可でも `add` に `true` を返す(§11.5)。したがって**ハンドラ自身が LS 往復の前に拒否する**。

| 層 | 内容 |
|---|---|
| `plugin.xml` の `<handler>` | `<enabledWhen>` に §11.3 と同じ `and(duo_chat_enabled == true, duo_chat_terminal_context_available == true)` を置く(無効時は Quick Access でもグレーアウト) |
| `execute` 冒頭(正本) | `TerminalOutputGate.check(terminalContextAvailableForCapturedSession, activePartId)` を評価する純関数。否なら通知「Explaining terminal output is not available.」(部位違いは R14 の文言)で **return し、`add` も `newPrompt` も送らない**。値は `TerminalContextStateService.isAvailableFor(handle.session)` を直接読む(評価コンテキストの遅延にも LS 再起動にも依存しない) |
| 部位の許可リスト | `HandlerUtil.getActivePartId(event)` が `org.eclipse.terminal.view.ui.TerminalsView` / `org.eclipse.tm.terminal.view.ui.TerminalsView` / `org.eclipse.ui.console.ConsoleView` のいずれか |

**状態は接続に結び付ける(Codex round 2 P1 反映)。** `TerminalContextStateService` は値を `(session: LanguageServerSession, available: Boolean)` の組で保持し、`featureStateChange` を受けた接続の `session`(`GitLabLanguageServerClient.kt:58`)と一緒に記録する。ゲートは **§9.3 手順 0 で捕捉した `handle.session` と記録の `session` が同一参照のときだけ** `available` を採用し、違えば `false` とみなす。これで「LS 再起動前は許可・再起動後は不許可」の場合に、新しい接続の `featureStateChange` が届くまでの間も `add` は送られない。初期値は「記録なし」= `false`。

chat 側の有効性も同じ理由で接続に結び付く必要があるが、**`chat_terminal_context` の check 列は chat の check 列を丸ごと含む**(`CHECKS_PER_FEATURE[CHAT_TERMINAL_CONTEXT] = [...CHAT_CHECKS_PRIORITY_ORDERED, CHAT_INCLUDE_TERMINAL_CONTEXT_UNAVAILABLE]`、LS map @23532164)ので、「同じ接続で terminal context が可」なら chat も可である。ゲートの正本は接続に結び付いた terminal context 1 つで足り、`DuoChatStateService`(既存・接続に結び付かない)の値は `enabledWhen` / `visibleWhen` の表示用にだけ使う。既存の `DuoChatStateService` は変更しない。

#### 9.3.2 入力上限(**Codex round 1 P1 反映**)

- LS / `AIContextItemSchema` に `content` の長さ上限はない(VSCode 側にも切り詰めはない、`gitlab_chat_terminal_context.ts` 全体)。
- 一方 LS の classic chat webview は、エディタのファイル文脈を **`MAX_CONTENT_LENGTH = 4e5`(JS の文字列長 = UTF-16 コード単位)** で切り詰めている(`packages/webview_duo_chat_classic/dist/index.mjs` `trimActiveFileContext`、LS map @26232942)。本機能はこれと**同じ単位・同じ値**を上限とする: `TerminalOutputLimit.MAX_CHARS = 400_000`(Kotlin の `String.length` も UTF-16 コード単位なので単位が一致する)。
- 超過時は**末尾を残す**(ターミナル出力は末尾ほど新しく、エラーは末尾に出る。LS の `contentAboveCursor.slice(-max)` と同じ向き)。切り出し位置がサロゲートペアの後半に当たる場合は 1 単位進めて、ペアを割らない。
- 切り詰めたときは通知「Only the last 400,000 characters of the selection were sent.」を出す(数値は定数から生成)。
- 上限適用は取得直後・DTO 生成前(§9.3 手順 5)。第 3 段のクリップボード読み出し自体は SWT が文字列全体を返すので、そこでの上限はかけられない(残るリスクとして §27)。

#### 9.3.3 解決の定義・タイムアウト・未解決の扱い(**Codex round 1 / 2 / 3 P1 反映**)

F3 の不変条件: **同じ LS 接続の上で、F3 が追加した terminal item が「選択中の文脈」に残ったまま、他の classic prompt が送られることはない。**
これを 1 つの概念で担保する: tracked prompt の**解決(resolution)**。

| 状態 | 意味 | run / 送出バリア(§9.3.5) |
|---|---|---|
| 未送出で終了(`Dropped`: `VIEW_UNAVAILABLE` / `NOT_SHOWN` / `SUPERSEDED` / `EXPIRED` / `DISPOSED` / `SESSION_CHANGED`) | `add` を一度も送っていない | 即解決。run 終了。バリアは張られていない |
| `add` を送った後 | 結果にかかわらず、**`$/gitlab/ai-context/current-items` で item の不在を確認できたときだけ**解決 | 解決まで run とバリアを保持 |
| 接続が替わった(`currentSnapshot.session` が tracked の `session` と異なる) | 旧 LS プロセスごと選択中の文脈が失われている | 解決扱い。run とバリアは次の送出・次の実行の時点で破棄 |

**`add` を送った後の解決手順**(`TerminalItemResolver`、送出と同じ捕捉済み `proxy` に対して行う):

1. `original = proxy.addAiContextItem(item)`。**`orTimeout` は写し(`original.thenApply { it }`)にだけ付ける**(#20 の既知事実: `orTimeout` は `this` を返し元の future を例外完了させる)。写しが 10 秒でタイムアウトしたら、prompt は送らず `Dropped(ADD_TIMEOUT)` で通知「Duo Chat did not respond in time.」。**ただし解決はしない**: `original` が確定するまで待つ(確定前に `current-items` を見ても、後から追加されうるため)。`original` が永遠に確定しない場合は「未解決」(下記)。
2. `original` が `true` かつ prompt 送出が可能(§9.3.5 手順 e)→ `newPrompt` を送る(`Sent`)。`false` / 例外 → prompt は送らず `Dropped(ADD_FAILED)`。
3. 確認ループ(最大 10 秒、200 ms 間隔): `proxy.currentAiContextItems()` を呼び、`id` が item と一致する要素が**無ければ解決**。
   - `Sent` の場合: webview がプロンプト処理時に選択中の文脈をクリアする(`handleExtensionPrompt` の `Promise.all([processNewUserRecord(record), _clearSelectedContextItems()])`、`packages/webview_duo_chat_classic/dist/index.mjs`、LS map @26258642 付近)ので、通常は数回の確認で不在になる。**5 秒経っても残っていれば** `remove(item)` を送り、確認を続ける。
   - `Sent` 以外(`ADD_TIMEOUT` / `ADD_FAILED`): 残っていれば直ちに `remove(item)` を送り、確認を続ける(`false` でも内部で追加済みの可能性を排除しない)。
   - **`remove` の戻り値は解決の根拠にしない**(`false` は「見つからない」と「失敗」を区別しない。@25794149 / @25789499)。根拠は常に `current-items` の結果。
4. 10 秒で不在を確認できない、`current-items` 自体が失敗・タイムアウトする、または `original` が確定しない → **未解決**。run とバリアを保持し、通知「GitLab Duo Chat could not confirm that the terminal output was cleared. Restart the language server to continue.」。**安全側に倒す**: 同じ接続では F3 の再実行も他の classic prompt の送出も止まる(§9.3.5 のバリア)。「Restart Language Server」で接続が替われば解決扱いになる。

`current-items` の契約: `CURRENT_ITEMS: '$/gitlab/ai-context/current-items'`(LS map @28600981)、型 `request: undefined; response: AIContextItem[]`(@28601281)、受け口 `onRequest(AIContextEndpoints.CURRENT_ITEMS, () => chatContextManager.getSelectedContextItems())`(@29322403)。`remove` の契約: `REMOVE`(@28600940)、受け口(@29322261)、manager は `metadata.subType` で provider を引き `removeSelectedContextItem(item.id)`(@25794149)、provider は id が無いと例外(@25789499)→ `false`。**`remove` に渡す `item` は `add` に渡したものと同一**でなければならない。

**なぜ `remove` の成功ではなく不在確認か**: round 3 の指摘どおり、`remove` の `false` / 例外 / タイムアウトは item が残っている可能性を排除しない。一方 `current-items` は LS の選択中文脈そのもの(`getSelectedContextItems`)を返すので、「残っていない」を直接確認できる。

#### 9.3.4 Terminal 経路の実現性ゲート(**Codex round 1 P1 反映**)

Terminal の選択取得は、第 1' / 2 / 3 段のどれが実機で機能するかを headless で確定できない(U4 / U5 / U6)。そのため:

1. **完了判定を 2 つに分ける。** 台帳 D3「ターミナル出力を説明」行は、**新世代 Terminal(`org.eclipse.terminal.*`、ユーザー環境 = Pleiades 2025-12)で A8 が 1 段以上で成立し、かつ Console で A9 が成立したときだけ ✅** とする。旧世代(`org.eclipse.tm.terminal.*`)は best-effort で、✅ の条件に含めない。
2. **Terminal が全段で不成立なら**、同行は **🟡(Console のみ)** と記録し、パリティは 77/85 ではなく **76/85 + 🟡1** とする。そのうえで「Terminal バンドルへの依存追加(Q3 の選択肢 (a))」を**別サイクルの判断事項としてユーザーに提示する**(本サイクル内で方式を変えない。Q3=(b) の決定を維持する)。
3. **手戻りの範囲を局所化する。** 取得手段は `TerminalOutputSelectionReader` / `ClipboardSelectionCapture` の 2 クラスに閉じ込め、DTO・LS 要求・ゲート・上限・送信・Console 経路はどの取得手段でも共通に使う。依存追加に切り替えても、差し替えは取得クラスと `Require-Bundle` だけで済む。
4. **実機検証の順序**: 実装 PR の手動検証では **M5(Terminal)を最初に行う**。結果(成立した段、または全段不成立)を PR 本文に記録し、マージ判断と台帳の値はそれに従う。どの段で取れたかは debug ログ(段の番号のみ、内容は出さない)で判別できるようにする。

#### 9.3.5 追跡付き配送・送出直前の追加・送出バリア(**Codex round 2 / round 3 反映**)

**現状の配送経路は成否を返さない**。`openDuoChatWindowWithClassicPrompt`(`DuoChatWindow.kt:30-32`)は戻り値なし、ビューは prompt を**1 枠**の `pendingClassicPrompt` に置き(`LanguageServerBrowserView.kt:77, 177-180`)、次の依頼で上書きし、解決結果が classic 以外なら捨てる(`:296-307`、`ChatIntentRouter.kt:46-57`)。classic クライアント(Koin の singleton)はフォーカスが無い間 `messagesAwaitingReady` に積み、**送る瞬間の** `languageServer` に送る(`GitLabDuoChatWebViewClient.kt:12-34`)。

**設計**: 既存の呼び出し元の挙動を変えずに、**追跡付きの入口を 1 本追加**し、文脈の追加を**送出手順の中**へ移す。

| 追加 | 内容 |
|---|---|
| `PromptDelivery`(`chat.webview`) | `Sent` / `Dropped(reason)`。`reason` は `VIEW_UNAVAILABLE` / `NOT_SHOWN` / `SUPERSEDED` / `EXPIRED` / `DISPOSED` / `SESSION_CHANGED` / `ADD_FAILED` / `ADD_TIMEOUT` |
| `TrackedPrompt` | `payload` / `attachment: AiContextItem` / `session` / `result: CompletableFuture<PromptDelivery>` / `resolved: CompletableFuture<Unit>`(§9.3.3 の解決)と、**状態機械 `AtomicReference<State>`(`PENDING` → `SENDING` → 終端、または `PENDING` → `EXPIRED`/`DROPPED`)**。遷移は CAS で一度だけ。期限: 作成から **30 秒**で `PENDING → EXPIRED` を試み、成功したら `Dropped(EXPIRED)`(タイマーは既存のスケジューラ。`SENDING` 以降には効かない) |
| `openDuoChatWindowWithTrackedClassicPrompt(tracked)` | `DuoChatWindow.kt` に追加。`showDuoChatView()` が null なら `Dropped(VIEW_UNAVAILABLE)` |
| `LanguageServerBrowserView.requestClassicPrompt(tracked)` | 既存と**同じ 1 枠**。追跡付きの依頼が枠にある間に**どちらの形の**新しい依頼が来ても、古い方を `Dropped(SUPERSEDED)`(`PENDING → DROPPED` の CAS)。route されなければ `Dropped(NOT_SHOWN)`。ビューの `dispose` で `Dropped(DISPOSED)`。枠の規則は SWT 非依存の `PendingClassicPromptSlot` に切り出す |
| `GitLabDuoChatWebViewClient.notify(tracked)` | フォーカス待ちならキューに積む。送出時(即時またはフォーカス取得時)に下の**送出手順**を行う。**キュー上の tracked が既に終端(期限切れ等)なら何もせずキューから外す**ので、後日ビューを開いても古い prompt は送られない |

**送出手順(UI スレッド、tracked 1 件につき)**:

- a. `PENDING → SENDING` の CAS。失敗(期限切れ・上書き済み)→ 何もしない。
- b. `snapshot = currentSnapshot` を 1 回読む。null または `snapshot.session !== tracked.session` → `Dropped(SESSION_CHANGED)`(`add` を送っていないので後始末なし)。
- c. **送出バリアを張る**(バリアは `session` 付き)。バリアが張られている間、同じクライアントへの**他の `notify`(追跡なしを含む)は送出順を保ったままバリア待ちキューに積む**。
- d. `snapshot.proxy` に対して §9.3.3 の手順 1〜2(`add` → 結果に応じて `newPrompt` を送るか `Dropped`)。`newPrompt` を送る直前にも `currentSnapshot.session === tracked.session` を確認し、違えば送らず `Dropped(SESSION_CHANGED)`。
- e. §9.3.3 の解決(不在確認)を待つ。**解決したらバリアを外し、バリア待ちキューを順に送出**(各メッセージは送る瞬間の `languageServer` へ。追跡なしの既存挙動と同じ)。未解決なら**バリアを保持**(§9.3.3 手順 4)。
- f. バリアは `session` 付きなので、`currentSnapshot.session` がバリアの `session` と異なる状態で `notify` が来たら、バリアとバリア待ちキューを**新しい接続向けに解放**する(旧接続の文脈は LS プロセスごと消えている)。

**上書き(`SUPERSEDED`)の扱い(round 3 P1 反映)**: 上書きは送出手順 a より前にしか起きない(枠にある = まだクライアントへ渡っていない)。したがって上書きされた F3 は `add` を送っておらず、上書きした側(Explain Code 等)の prompt に F3 の文脈が混入する経路は存在しない。**round 2 で U11 としていた処理順の問題は、構造的に消える。**
逆に、F3 の送出手順 c〜e の途中で他の classic コマンドが実行された場合、その prompt はバリア待ちキューに入り、**F3 の item の不在を確認した後に**送られる。

**既存経路への影響**: 追跡なしの `requestClassicPrompt(payload)` / `notify(type, payload)` は、**F3 の送出手順が進行中でない限り挙動不変**。進行中(通常は `add` 往復 + 確認の数百 ms、最悪で未解決の間ずっと)は、既存の classic prompt が送出順を保ったまま待たされる。未解決の間待たされるのは安全側の意図的な選択で、通知で LS の再起動を案内する。

**残るリスク(明記)**: 同じ接続で、F3 の prompt より**後に**送られた prompt が、webview 内で F3 の prompt より先に文脈を読むこと。バリアにより「F3 の item の不在を確認するまで後続は送らない」ので、この順序は起こりえない。F3 より**前に**キューにあった prompt は、バリアを張る前(手順 c 以前)に送出済みか、同じキューの前方で先に送られる。

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

5 件とも **`<command>` を宣言する**(§4-4 の癖を繰り返さない)。`ExplainTerminalOutput` の `<handler>` には §9.3.1 の `<enabledWhen>` を付ける(他 4 件は常時有効)。

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

/** LS `AIContextEndpoints.REMOVE`. タイムアウト後の遅延成功の補償だけに使う(§9.3.3)。 */
@JsonRequest("$/gitlab/ai-context/remove")
fun removeAiContextItem(item: AiContextItem): CompletableFuture<Boolean?>

/** LS `AIContextEndpoints.CURRENT_ITEMS`. 引数なし。追加した item の不在確認だけに使う(§9.3.3)。 */
@JsonRequest("$/gitlab/ai-context/current-items")
fun currentAiContextItems(): CompletableFuture<List<AiContextItem>?>
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
- **F3**: LS 側の「コンテキスト追加」と「プロンプト送信」は 2 つの独立した操作で、原子性はない。境界を閉じる手段は 2 つ: (1) **追加を送出手順の中へ移す**(§9.3.5)ので、配送が止まる経路はすべて「追加していない」状態で終わる。(2) 追加した後は、**`current-items` で item の不在を確認できるまで**(§9.3.3)同じ接続の他の classic prompt を送らない(送出バリア)。確認できなければ安全側(バリア保持 + LS 再起動の案内)に倒す。接続が替わった場合は旧 LS プロセスごと文脈が失われるので補償不要。

## 14. エラー処理

| 機能 | 失敗 | 扱い |
|---|---|---|
| F1 | 外部ブラウザ起動失敗 | `BrowserLauncher.open` が捕捉してログ(`BrowserLauncher.kt:14-18`)。追加処理なし |
| F1 | `authentication` の `allChecks` が null | `"unknown"` のまま(表示しない) |
| F1 | 起動直後の古い認証状態の連続通知 | debounce 後の最後の値だけを反映(既存ポップアップと同じ規則) |
| F1 | LS の停止・再起動 | `"unknown"` に戻し、新しい接続の debounce 済みの値が届くまで Sign in 項目を出さない |
| F2 | Planner が `Refuse`(同名のユーザープロジェクト / 未知の同名フォルダ) | §9.2 の文言で通知。ワークスペースは一切変更しない |
| F2 | Job の `CoreException`(ワークスペースが読み取り専用、`.project` の解析失敗等) | 通知「Could not create the GitLab Duo Tutorial project. See the Error Log.」+ ログは**例外クラス名のみ** |
| F2 | `WorkspaceJob` 内の `CoreException` | Job の `IStatus` を ERROR で返す(Eclipse が Job のエラーダイアログを出す)。部分状態(プロジェクトだけできた等)は残してよい — 次回実行で分岐表が拾う |
| F2 | エディタを開けない(`PartInitException`) | `WorkspaceFileOpener.kt:89-103` と同じ包み方。通知 + クラス名ログ。**ファイルは作られている**ので Project Explorer から開ける |
| F3 | 選択が空 / プレースホルダ / Console でも Terminal でもない部位 | 通知「Select text in the Terminal or Console first.」(R14)。LS 往復なし |
| F3 | LS 未接続(`languageServer == null`、`GitLabLanguageServerWrapper.kt:24-25`) | 通知「GitLab Duo Chat is not available.」 |
| F3 | `add` が `false` | 通知「Could not add the terminal output to Duo Chat.」**prompt は送らない**。VSCode は結果を見ずに送る(`:42-43`)が、コンテキストなしの「Explain this terminal output」は空振りになるため、意図的な差 |
| F3 | `add` が例外 | 同上 + ログはクラス名のみ |
| F3 | `add` がタイムアウト(§9.3.3) | 通知「Duo Chat did not respond in time.」。prompt は送らない。遅延成功なら `remove`(結果はログに `true`/`false`/クラス名のみ) |
| F3 | 実行時ゲート不成立(§9.3.1) | 通知のみ。LS 往復なし |
| F3 | 入力上限超過(§9.3.2) | 末尾 400,000 単位に切り詰めて続行 + 通知 |
| F3 | 第 3 段の前提条件不成立(非テキスト内容あり等) | クリップボードに触れず「選択なし」扱い + §9.3 の文言で通知 |
| F3 | prompt が配送されない(`Dropped`: ビューを出せない / classic 以外に解決 / 後続の依頼で上書き / 期限切れ / 破棄 / 接続が替わった) | 通知「The request was not delivered to Duo Chat.」。**`add` を送っていないので後始末なし**(§9.3.5)。ビューを出せない場合は既存 `reportCannotShow`(`DuoChatWindow.kt:121-124`)の通知も出る |
| F3 | 追加後に item の不在を確認できない(§9.3.3 手順 4) | 通知「GitLab Duo Chat could not confirm that the terminal output was cleared. Restart the language server to continue.」。run とバリアを保持(安全側) |
| F3 | クリップボード復元失敗 | §9.3 の表 |
| F3 | 同じ接続で未解決の run がある | 通知「A previous request is still in progress.」。LS 往復なし(§9.3.3) |

通知は全て `NotificationUtils.show`(`NotificationUtils.kt:29-50`、任意スレッドから安全)。

## 15. タイムアウトとリトライ

- **F3 の `add`**: 既存定数と同じ **10 秒**(`GitLabLanguageServerClient.kt:49, 77, 118`)。ただし **`orTimeout` は元の future に付けず、写し(`thenApply { it }`)に付ける**(§9.3.3。`orTimeout` は `this` を返し元の future を例外完了させるため、付けると遅延成功を観測できない)。`ExplainTerminalOutputCommand` は値を引数で受け(テストでは短く)、既定 10 秒。
- **UI スレッドを待たせない**: `execute` は future を返した時点で戻る。継続は `whenComplete` で行い、UI に触る継続だけ `asyncExec`(`ClipboardWriter.kt:43-44`、`WorkspaceFileOpener.kt:37-38`: **`syncExec` は使わない**)。
- **リトライしない。** ユーザーが右クリックし直せばよい。
- **F1 / F2**: ネットワーク往復がないためタイムアウトなし。F2 の `WorkspaceJob` はキャンセル可能(`IProgressMonitor.isCanceled` を各行動の頭で確認)。

## 16. 冪等性

| 機能 | 冪等性 |
|---|---|
| F1 | 完全に冪等(閲覧・ダイアログ表示) |
| F2 | **冪等**(§9.2 の分岐表: 2 回目以降は既存を開くだけ)。ただし「ユーザーが本文を空にした」場合もそのまま開く(空 ≠ 未作成。書き戻さない) |
| F3 | **非冪等**(毎回新しい UUID でコンテキストが追加され、毎回新しいプロンプトが送られる)。これは仕様(VSCode も同じ)。**同じ接続では run が 1 つしか存在しない**(遅延中の `add` を含む。§9.3.3)ので、未確定の item と次の item が同時に選択中になることはない |

## 17. 並行処理

| 論点 | 方針 |
|---|---|
| ソース変数の更新 | `featureStateChange` は lsp4j のディスパッチスレッド(`GitLabLanguageServerClient.kt:99-103` は `runAsync`)。`fireSourceChanged` は `asyncExec` で UI へ(`DuoChatStateService.kt:25-32` と同じ)。フィールド書込は UI 転送前に行う(`ChatAvailabilityService.kt:19-21` の方針) |
| F2 のワークスペース操作 | `WorkspaceJob` + `rule = root`。UI スレッドではワークスペースを変更しない。完了通知 → `asyncExec` → エディタ。ハンドラ内の状態読み取り(`exists` / `isOpen`)は UI スレッドで行う軽い読み取りで、Job 内で**再度**読み直して分岐する(読み取りと実行の間にユーザーがプロジェクトを消す可能性) |
| F2 の二重起動 | Job に `rule = root` があるので 2 本は直列化される。2 本目は Job 内の再読み取りで「あり」行に落ち、開くだけ |
| F3 のスレッド境界 | `execute`(UI): 接続の捕捉・ゲート・選択読み取り・クリップボード・run の登録・追跡付き依頼。送出手順(§9.3.5 a〜c)と `newPrompt` の送出・バリア待ちキューの送出: UI スレッド。`add` / `current-items` / `remove` の応答: lsp4j スレッド → UI へは `asyncExec`。期限タイマー: スケジューラスレッド(状態機械の CAS だけを行い、UI には触れない) |
| F3 の二重起動・再実行 | `AtomicReference<Run?>`(`Run` は `session` と `tracked` を持つ)。登録は `compareAndSet(null or 古い接続の run, 新 run)`、解除は `tracked.resolved` の完了時に `compareAndSet(自分, null)`。**解除は解決(§9.3.3)でだけ行い、タイムアウトや `Dropped(ADD_TIMEOUT)` では解除しない** |
| 送出バリアと既存の classic prompt | バリアは `GitLabDuoChatWebViewClient` の内部状態(UI スレッドだけが触る)。バリア中の `notify` は FIFO で待たせ、解決後に順に送る。接続が替わったらバリアを解放(§9.3.5 f) |
| tracked の状態機械 | `PENDING → SENDING`(送出手順 a)と `PENDING → EXPIRED / DROPPED`(期限・上書き・破棄)は CAS で排他。送出に入った tracked は期限切れにならず、期限切れになった tracked は送出されない |
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
- **F3**: item の不在を確認できず未解決になった(§9.3.3 手順 4)→ 通知どおり「Restart Language Server」で接続を替えると、F3 の再実行も他の classic prompt の送出も再開する。それでも Duo Chat の会話に古い文脈が見える場合は「/reset」(= `newConversation`、@26245433 `newConversation: "/reset"`)で新規会話にする。クリップボードが復元されなかった → 通知の文面どおり、ユーザーが再コピーする。

## 21. 既存機能への影響

| 既存 | 影響 |
|---|---|
| `plugin.xml` | **追記のみ**、4 箇所: `org.eclipse.ui.commands`(`:4-434`)末尾にコマンド 5 件 / `org.eclipse.ui.services`(`:502-522`)にソースプロバイダ 2 件 / `org.eclipse.ui.handlers`(`:590-991`)末尾にハンドラ 5 件 / `org.eclipse.ui.menus`(`:1011-1711`)末尾に `menuContribution` ブロック(F1 用 2 つ + F3 用 5 つ)。既存の状態メニューブロック(`:1605-1711`)は**編集しない**(位置は `?before=` / `?after=` で指定) |
| `GitLabLanguageServerClient.kt:106-115` | case 追加 2 件(`chat_terminal_context` は自分の `session` を添えて渡す)。既存 case の動作は不変 |
| `DuoChatWindow.kt` / `LanguageServerBrowserView.kt` / `PendingChatIntents`・`ChatIntentRouter.kt` / `GitLabDuoChatWebViewClient.kt` | **追跡付きの入口と送出バリアを追加**(§9.3.5)。追跡なしの既存入口(Explain Code 等が使う)は、**F3 の送出手順が進行中でない限り**挙動不変。進行中は既存の classic prompt が FIFO で待たされる(通常は数百 ms、未解決時は LS 再起動まで)。枠の規則とバリアの規則はそれぞれ SWT 非依存のクラス(`PendingClassicPromptSlot` / `ClassicOutboundBarrier`)に切り出して試験する |
| `GitLabLanguageServer.kt` | メソッド 1 件追加(§11.5)。既存メソッドは不変 |
| `AuthModule.kt` / `ChatModule.kt` | `single` を各 1 件追加 |
| `PreferenceConstants.kt` | 非表示キー `DUO_TUTORIAL_PROJECT_ID` / `DUO_TUTORIAL_PROJECT_LOCATION` を 2 件追加(UI なし・既定値なし・LS へ送らない) |
| `AuthenticationStateService` | debounce ブロック内に `AuthenticationSourceProvider.update(featureStateChange, session)` を 1 行追加、`update` に `session` 引数を追加(呼び出し元は `GitLabLanguageServerClient.kt:107` の 1 箇所)。ポップアップの挙動は不変 |
| `GitLabLanguageServerProcessProvider.kt` | `SecurityScanLifecycle.onServerStopped()` の 2 箇所(`:228` / `:262`)に `reset()` 呼び出しを 2 件ずつ並べる。ライフサイクルの制御は不変 |
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
| `AuthenticationSourceProviderTest` | F1 | 初期 `"unknown"`; `authentication-required` engaged → `"false"`; `invalid-token` engaged → `"false"`; 全て非 engaged → `"true"`; `allChecks == null` → 変化なし; **現在と異なる `session` の更新 → 無視**; **`reset()` → `"unknown"`**。`getProvidedSourceNames`。UI 転送はシームで捕捉 |
| `AuthenticationStateServiceProviderFeedTest` | F1 | 既存 debounce を短縮した構成で「stale(未認証)→ 最新(認証済み)」を連続投入 → プロバイダには最新値だけが 1 回届く; 既存ポップアップの判定は不変(既存 spec の回帰) |
| `ShowDuoForumTest` / `ShowDuoDocumentationTest` | F1 | URL 定数が `constants.ts:17-18` の文字列と一致; `BrowserLauncher` シームが 1 回呼ばれる |
| `DuoTutorialContentTest` | F2 | `PROJECT_NAME` / `FILE_NAME`; 本文に MIT 表記・`Alt + D`・`Explain Code`・`Generate Tests`・`Refactor Code` を含む; `Quick Chat` / `fibonacci` / `Alt> + C` / `Alt> + T` / `Alt> + R` を**含まない**; `\\s` を含まず `$/` を含む(§12.2) |
| `DuoTutorialProjectPlannerTest` | F2 | §9.2 の分岐表の**全行**を 1 例ずつ。「ファイルあり」の全行で `CreateFile` が出ないこと(R9)。**所有不一致 / 未記録の全行で行動列が `Refuse` のみ**(プロジェクトを開かない・ファイルを作らない)。所有記録が作成より前に行われる順序 |
| `DuoTutorialHandlerTest` | F2 | `Refuse` → 通知のみ・Job 未起動; Job 完了 OK → エディタ open シームが 1 回; Job 失敗 → open されない |
| `TerminalOutputSelectionReaderTest` | F3 | `ITextSelection` → text; 空 `ITextSelection` → null; `StructuredSelection(String)` → text; `StructuredSelection(fake CTabItem-like)` でリフレクション経路(`getSelection(): String` を持つ fake の data)→ text; `getSelection` なし → null; `getSelection` が例外 → null(伝播しない) |
| `ClipboardSelectionCaptureTest` | F3 | fake `ClipboardPort` で: 退避 → プレースホルダ → コピー → 読出 → 復元の**呼び出し順**; 読出がプレースホルダ → null; 復元は例外時も走る(`finally`); 退避 null(空)→ `clear` で復元; `SWTError` を捕捉; `dispose` が必ず 1 回; コピーコマンド未ハンドル → 一切書き込まない; **非テキストのみ(画像 / ファイル)・文字列 + RTF/HTML の複数形式 → 一切書き込まない**(前提条件の各分岐) |
| `TerminalOutputLimitTest` | F3 | 399,999 / 400,000 / 400,001 単位の境界; 超過時は末尾を残す; 切り出し位置がサロゲートペアの後半 → ペアを割らない; マルチバイト(日本語・絵文字)入力; 切り詰めの有無フラグ |
| `TerminalOutputGateTest` | F3 | 2 条件の全組み合わせ(捕捉した接続で terminal context 不可 / 部位が許可リスト外)で拒否、全て真のときだけ許可 |
| `TerminalAiContextItemsTest` | F3 | `category="terminal"`, `metadata` の 6 固定値, `content` = 入力, `id` が UUID 形式で毎回異なる |
| `ExplainTerminalOutputCommandTest` | F3 | ゲート通過・選択あり → `TrackedPrompt(NewPromptRequest("explainTerminalOutput", null), item, 捕捉した session)` の追跡付き依頼が 1 回; **依頼の時点で `add` が一度も呼ばれていない**; run の登録と解除(`resolved` 完了時のみ) |
| `TerminalItemResolverTest` | F3 | fake proxy で: `add` true → `newPrompt` 送出 → `current-items` が item を含まなくなった時点で解決; `Sent` 後 5 秒残る → `remove` を送り、不在確認で解決; `add` false / 例外 → prompt なし、`current-items` に残っていれば `remove`、不在確認で解決; **`remove` が false / 例外 / タイムアウトでも、`current-items` に残る限り解決しない**; 10 秒で未確認 → 未解決(run・バリア保持 + 通知); `current-items` 自体が失敗 → 未解決; **`add` の写しがタイムアウト → prompt なし・`Dropped(ADD_TIMEOUT)`、元の future が確定するまで確認を始めない**; 元の future が遅れて true → 確認ループ(残っていれば remove); 元の future が `orTimeout` で例外完了していない; `newPrompt` 直前に session が変わった → 送らない |
| `ExplainTerminalOutputCommandHandlerTest` | F3 | 選択なし → 通知・LS 未呼出; 機能無効(terminal context 不可 / 部位違い / 接続なし)の状態で `execute` を直接呼ぶ → `add` も `newPrompt` も一度も呼ばれない; **同じ接続で run が残っている間の再実行 → 拒否**; **run の接続が古い(LS 再起動済み)→ 再実行できる**; **LS 再起動前に許可・再起動後の接続で状態未着 → `add` が送られない** |
| `GitLabLanguageServerAiContextRequestTest` | F3 | `$/gitlab/ai-context/add` / `remove` / `current-items` の 3 件が `ServiceEndpoints.getSupportedMethods` に含まれること; 戻り値の型; クライアント側 `GitLabLanguageServerClient` に同名メソッドがないこと(`GitLabLanguageServerPluginRequestTest.kt:18-31` の写し) |
| `TerminalContextStateServiceTest` | F3 | `DuoChatStateServiceTest` の写し + `isAvailableFor(session)`: 記録と同じ session → 値、別 session → `false`、記録なし → `false` |
| `PendingClassicPromptSlotTest` | F3 | 追跡付きの依頼を置いた後に追跡付き / 追跡なしの依頼が来る → 古い方が `SUPERSEDED` で 1 回だけ確定; route されない解決 → `NOT_SHOWN`; route された → クライアントへ渡る; 破棄 → `DISPOSED`; 期限切れ済みの tracked は route されない; 追跡なしだけのときは従来と同じ後勝ち(既存の挙動の回帰試験) |
| `GitLabDuoChatWebViewClientTrackedTest` | F3 | フォーカスあり・同じ session → 送出手順(`add` → `newPrompt`); session 不一致 / 接続なし → `add` を送らず `SESSION_CHANGED`; フォーカス待ち → キューに積み、フォーカス時に同じ手順; **キュー上で期限切れ → フォーカス時に送らずキューから外れる**; 期限と送出の競合(CAS)で二重確定しない; 追跡なしの `notify` は、送出手順が進行中でなければ従来どおり即時 / キュー |
| `ClassicOutboundBarrierTest` | F3 | バリア中の追跡なし `notify` 3 件が FIFO で保持され、解決後に同じ順で送られる; 未解決の間は送られない; 接続が替わった後の `notify` でバリアが解放され、新しい接続へ送られる; **「F3 送出中に Explain Code」→ Explain Code の `newPrompt` は F3 の item の不在確認より後に送られる**(受信順と処理完了順の逆転を fake LS で再現しても、F3 の item を含む状態で Explain Code が送られない) |

**手動(実機)**: メニュー表示・可視性の切替・Terminal/Console での選択取得・クリップボード復元・エディタ種別・Code Suggestions の発火(§25)。

## 24. 受け入れ条件

| # | 条件 | 検証 |
|---|---|---|
| A1 | 状態メニューに「GitLab Forum (Help and feedback)」「GitLab Duo Documentation」「GitLab Duo Tutorial」が「Show Documentation」の直後に並び、Forum / Duo Documentation が §5.1 の URL を開く | 実機(M1)+ 自動(URL 定数) |
| A2 | 未認証(トークン未設定/無効)のとき「Sign in to GitLab」が `StatusActions` 区切りの直前に出て、設定ページを開く。認証後は消える(再起動なしで) | 実機(M2)。再起動なしの切替は U1 |
| A3 | `gitlab_authenticated` が §8.1 の規則どおり 3 値を取る | 自動 |
| A4 | チュートリアルコマンドで `GitLab Duo Tutorial/duo_tutorial.js` が作られエディタで開き、`didOpen` の `languageId` が `javascript`(LS ログで確認)で、本文中の `const multiply` 直後で Code Suggestions が出る | 実機(M3) |
| A5 | 2 回目の実行、本文を編集後の実行、プロジェクトを閉じた後の実行のいずれでも、**本文が書き換わらない**で開く | 実機(M4)+ 自動(Planner 全行) |
| A5b | ユーザー自身の `GitLab Duo Tutorial` プロジェクト(開/閉)、未知の同名フォルダ、**過去の Tutorial を削除した後に同じパスへ作って import したプロジェクト**がある状態で実行すると、**そのプロジェクト/フォルダに一切変更がなく**(ファイル追加なし・開閉状態も不変)拒否の通知だけが出る | 実機(M10)+ 自動(Planner) |
| A6 | 本文が §12.2 の翻案規則を満たす(MIT 表記あり、Quick Chat なし、実ラベル、エスケープ正しい) | 自動 |
| A7 | チュートリアル実行後も Duo Chat / Code Suggestions が `duo-disabled-for-project` にならない | 実機(M3 で Diagnostics を確認) |
| A8 | **新世代 Terminal**(`org.eclipse.terminal.*`、Pleiades 2025-12)で範囲選択 → 右クリック → 「Explain Terminal Output with Duo」で、Duo Chat に「Explain this terminal output」が投げられ、選択内容がコンテキストとして付く。旧世代は best-effort(✅ の条件外) | 実機(M5)。到達段(1' / 2 / 3)を記録。**不成立なら §9.3.4 に従い台帳は 🟡** |
| A9 | Console(実行結果のプロセスコンソール)で同様 | 実機(M6) |
| A10 | 選択なしで実行 → 通知のみ、LS 往復なし | 実機(M7)+ 自動 |
| A11 | 第 3 段に入ったとき、実行前にクリップボードにあった文字列が実行後も残っている | 実機(M8。第 3 段に入る条件は U6)+ 自動(順序・`finally`) |
| A11b | 実行前のクリップボードが画像のみ / ファイルのみ / 文字列 + リッチテキストのとき、第 3 段が実行されず、クリップボードの内容が実行前と同一 | 実機(M11)+ 自動(前提条件) |
| A12 | `add` が完了する前に `newPrompt` が送られない | 自動 |
| A13 | `add` が false / 例外 / タイムアウトで prompt が送られず通知される | 自動 |
| A13b | `add` を送った後は、結果にかかわらず `current-items` で item の不在を確認できるまで run と送出バリアが解除されない。`remove` の false / 例外 / タイムアウトは解除の根拠にならない | 自動 |
| A13c | 機能無効状態で(Quick Access / `executeCommand` 等から)直接実行しても、`add` / `newPrompt` が一切送られない | 自動 + 実機(M12) |
| A13d | 400,000 単位を超える選択は末尾 400,000 単位に切り詰められ、通知が出る。境界とサロゲートペアで正しい | 自動 |
| A13e | `add` がタイムアウトした後、同じ接続では、元の `add` が確定し不在確認が済むまで再実行も他の classic prompt も送られない。**「1 回目タイムアウト → 再実行 → 2 回目の add と prompt → 1 回目が遅れて true」の順序が起こりえない**ことを試験で再現して確認 | 自動 |
| A13f | LS 再起動の前に terminal context が許可・再起動後の接続では状態未着(または不許可)のとき、`add` が送られない | 自動 |
| A13g | prompt が配送されなかった各経路(ビュー不可 / classic 以外に解決 / 上書き / 期限切れ / 破棄 / 接続変更)で、**`add` が一度も送られていない**。キュー上で期限切れになった tracked は、後でビューがフォーカスされても送られない | 自動 |
| A13i | F3 の送出中(`add` 〜 不在確認)に実行された他の classic コマンドの prompt は、F3 の item の不在確認の後に送られる | 自動(`ClassicOutboundBarrierTest`)+ 実機(M13) |
| A3b | 起動直後の古い認証状態の連続通知で Sign in 項目が揺れない。LS の再起動後、新しい接続の状態が確定するまで Sign in 項目は出ない | 自動 + 実機(M2) |
| A13h | `add` の完了と prompt 送信の間に LS が再起動したとき、prompt は新しい接続へ送られない(`SESSION_CHANGED`) | 自動 |
| A14 | `chat_terminal_context` が engaged のとき、Terminal / Console のメニューに項目が出ない | 実機(M9: `include_terminal_context` 無効なインスタンス、または LS ログで engaged を確認) |
| A15 | `GitLabLanguageServer` に `$/gitlab/ai-context/add` / `remove` / `current-items` が宣言され、`ServiceEndpoints` が認識する | 自動 |
| A16 | ログに選択内容・クリップボード内容・パスが出ない | コードレビュー + 自動(fake ログで文字列不在) |
| A17 | `verify.sh` が `FAILSET_IDENTICAL`、detekt がベースラインちょうど | CI 相当 |
| A18 | `Require-Bundle` が変わらない(`build.gradle.kts` 差分ゼロ) | `git diff` |

## 25. 手動検証手順

対象環境: Windows(Pleiades 2025-12)と Linux(GTK)。両方で M1〜M14 を行い、結果を PR 本文に記載する。**§9.3.4 に従い M5(Terminal)を最初に行う。**

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
| M10 | (1) 手で `GitLab Duo Tutorial` という名前のプロジェクトを作る → コマンド実行 → 閉じて再実行。(2) それを削除し、コマンドで Tutorial を作る → 内容ごと削除 → 同じパスにフォルダを作って import → コマンド実行 | どれも拒否の通知のみ。プロジェクトにファイルが増えず、開閉状態も変わらない |
| M11 | 画像をコピー(スクリーンショット等)/ ファイルをエクスプローラでコピー / Word 等から書式付き文字列をコピー → Terminal で第 3 段に入る操作 → 貼り付け | 第 3 段は実行されず(debug ログで確認)、元の画像 / ファイル / 書式付き文字列がそのまま貼り付く |
| M12 | `include_terminal_context` が無効な状態(M9)で Quick Access(Ctrl+3)から「Explain Terminal Output with Duo」を実行 | 実行できない(グレーアウト)か、実行しても通知のみ。Chat にプロンプトが出ず、LS ログに `ai-context/add` がない |
| M13 | F3 を実行した直後(Duo Chat の応答が出る前)に、エディタで Explain Code を実行する | 両方の回答が順に出る。Explain Code の回答にターミナル出力の内容が混ざらない。Explain Code の送出がわずかに遅れるのは仕様(§9.3.5) |
| M14 | F3 を実行 → Duo Chat が開く前に「Restart Language Server」 | 新しい接続に「Explain this terminal output」が送られない(通知のみ)。再起動後に再実行できる |

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
| U9 | 第 3 段の前提条件(5 種の Transfer による非テキスト判定)が、普通にテキストをコピーした状態を誤ってスキップしないか(Windows の合成形式など) | 実機(M8 / M11) |
| U10 | 所有判定のための persistent property(`duoTutorialId`)が、Eclipse の再起動後も保持され、プロジェクト削除で消えること | 実機(M10 の後に再起動 / 削除 → 同じパスで再 import) |
| U11 | 配送期限 30 秒が、初回起動時(LS の起動・webview の初回読み込みを含む)に短すぎないか | 実機(M5 を Eclipse 起動直後に行う)。短すぎれば定数を調整(期限切れでも `add` を送っていないので安全) |
| U12 | 引数なしの `@JsonRequest("$/gitlab/ai-context/current-items")` を lsp4j 1.0.0 がどう直列化するか(`params` 省略 / `null`)。LS の受け口は引数を読まない(@29322403)ので どちらでも動く想定 | 自動(`GitLabLanguageServerAiContextRequestTest` で送出 JSON を固定)+ 実機(M5 の LS ログ) |

## 27. 想定されるリスク

| リスク | 影響 | 緩和 |
|---|---|---|
| Terminal で選択テキストがどの段でも取れない(U4/U5/U6 が全て否) | F3 の Terminal 側が「選択なし」通知しか出せない | Console 側は `ITextSelection` で確実。Terminal 側は実機結果を PR に記録し、否なら次サイクルで依存追加(Q3=(a) 相当)を再提案 |
| クリップボードの非テキスト内容の消失(第 3 段) | ユーザーの貼り付け内容が失われる | 第 3 段は「文字列以外の既知形式がない」ときだけ実行(§9.3 の前提条件)。残るのは 5 種の Transfer で識別できないアプリ固有形式のみ(U9)。第 1'・2 段で取れれば第 3 段に入らない |
| 巨大な選択 | UI スレッドでのクリップボード読み出し・文字列複製のメモリ | 送信前に 400,000 単位へ切り詰め(§9.3.2)。クリップボード読み出し自体は SWT が全体を返すので上限をかけられない(残るリスク) |
| Terminal で全段不成立 | F3 の Terminal 側が機能しない | §9.3.4 の実機ゲート。台帳は 🟡、依存追加は別サイクルで判断。取得手段は 2 クラスに局所化済み |
| 送出バリアの導入で既存の chat 経路が変わる | Explain Code 等が F3 の送出中に待たされる。未解決時は LS 再起動まで止まる | 通常は数百 ms。未解決は安全側の意図的な選択で、通知で再起動を案内。規則は `ClassicOutboundBarrier` に切り出して回帰試験で固定(§23)、実機では M13 |
| LS が応答しないまま生き続ける | F3 と他の classic prompt が止まる(run・バリアが残る) | 通知で「Restart Language Server」を案内(§9.3.3)。LS 無応答時は chat 自体も機能しないため、実害は F3 に固有ではない |
| 状態メニューの可視性が追随しない(U1) | 未認証項目が出っぱなし/出ない | `update(true)` の 1 行で解消可能 |
| `WorkspaceJob` の `rule = root` が他の Job と競合して待たされる | チュートリアルが数秒遅れる | `isUser = true` で進捗が見える。ワークスペース全体のビルド中でも正しく直列化される |
| 同名プロジェクト/フォルダがユーザーのもの | ユーザーの資産を変更してしまう | 所有記録(§9.2)と一致しなければ無変更で拒否。拒否時はユーザーがリネームする必要がある(UX 上の代償) |
| lsp4j の名前衝突(§6.4) | 応答が `LinkedTreeMap` になり `ClassCastException` | クライアント側に同名がないことを確認済み。宣言テスト(A15)で固定 |
| `plugin.xml` の並行 PR 衝突 | マージ時の手戻り | 追記のみ・末尾のみ。既存ブロックは非編集 |
| headless で UI 配線を検証できない | メニューが出ない等が実機まで露見しない | UI 接触部を最小化し、配線と SWT 殻は `fable` が担当。M1〜M9 |

## 28. 実装タスク分割案

実装 PR は 1 本(`feat/duo-lightweight-commands`、base `gitlab-ls-9.3.0`)。タスクは独立に実装・レビューし、`plugin.xml` は各タスクが自分の追記だけを行う(全て末尾追記なので衝突しない)。

| # | タスク | 内容 | モデル | 理由(CLAUDE.md の表) |
|---|---|---|---|---|
| T1 | F1 | `AuthenticationSourceProvider` + Koin 登録 + ディスパッチ 1 行 + `ShowDuoForum` / `ShowDuoDocumentation` + `plugin.xml`(コマンド 3・ハンドラ 3・ソースプロバイダ 1・メニュー 2 ブロク)+ spec 3 本 | `opus` | 既存パターンの複製 + plugin.xml 配線。誤りは次段レビューで検出可能 |
| T2 | F2 | `DuoTutorialContent` / `Ownership` / `Planner` / `WorkspaceWriter` / `Handler` + `PreferenceConstants` 2 件 + `plugin.xml`(コマンド 1・ハンドラ 1・メニュー項目)+ spec 4 本 | `fable` | `WorkspaceJob` → `asyncExec` → エディタの**スレッド境界**と `IProject` 状態の再読み取り・所有判定(persistent property)を含む。headless で検証不能 |
| T3a | F3 基盤 | DTO + `GitLabLanguageServer` 追加 3 件(add / remove / current-items)+ `TerminalContextStateService`(接続に結び付く)+ ディスパッチ case + **追跡付き配送(`PromptDelivery` / `TrackedPrompt` 状態機械 / `PendingClassicPromptSlot` / `ClassicOutboundBarrier` / `TerminalItemResolver` / `DuoChatWindow`・`LanguageServerBrowserView`・`ChatIntentRouter`・`GitLabDuoChatWebViewClient` への入口追加)** + spec 7 本 | `fable` | 既存 chat 経路への入口追加・lsp4j ディスパッチ・UI スレッドホップ・状態機械。全て「ユーザー実機でのみ発覚」する種類 |
| T3b | F3 取得・UI | `Gate` / `Limit` / `SelectionReader` / `ClipboardSelectionCapture` / `Command` / `Handler` + `plugin.xml`(コマンド 1・ハンドラ 1(`enabledWhen` 付き)・ソースプロバイダ 1・メニュー 5 ブロック)+ spec 6 本 | `fable` | SWT `Clipboard`・リフレクション・ワークベンチの選択。T3a の後に実装する |
| R1〜R3 | 各タスクのコードレビュー | — | `fable` | 唯一の安全網。実装者と別モデル(T1)/ 別インスタンス(T2, T3) |
| R4 | ブランチ全体レビュー + PR 本文(M1〜M9 の手順・U-item 一覧・台帳更新案) | — | `fable` | 同上 |
| L | 台帳 #7(D3 +2、D4 +1、D1 4 行を「対象外(単一アカウント運用)」に)、#14 / #8 の `vscode.comments` 記述訂正 | — | `haiku` | 即座に目視確認できる |

T3 の実装ブリーフには §9.3 の 3 段と §11.5 の LS 実値、§6.4 の lsp4j 注意、§19 のログ規約を**そのまま**埋め込む(実装者が LS map を再抽出しなくて済むように)。

## 29. Codex レビュー反映履歴

| 巡 | 指摘 | 判断 | 反映先 |
|---|---|---|---|
| 1 | P1 Terminal 選択取得の実現可能性を実装前に確定せよ | 採用。headless では確定できないため、台帳の ✅ 条件を実機ゲートに結び、全段不成立なら 🟡・依存追加は別サイクルで判断。取得手段は 2 クラスに局所化 | §1, §9.3.4, A8, M5 の順序, §27 |
| 1 | P1 非テキストのクリップボード内容を破壊しない | 採用。第 3 段に前提条件(5 種の非テキスト Transfer / 非テキストのみ / コピー未ハンドルならスキップ)を追加。残るのはアプリ固有形式のみ(U9) | §9.3 の表, R16, A11b, M11, §27 |
| 1 | P1 メニュー非表示を認可境界にしない | 採用。`enabledWhen` + `execute` 冒頭の純関数ゲート(feature state 2 つ + 部位の許可リスト)。無効状態の直接実行で LS 往復ゼロを試験 | §9.3.1, R18, A13c, M12, §23 |
| 1 | P1 add タイムアウト後の遅延成功を補償 | 採用。`orTimeout` を写しに付け(#20 の既知事実)、元の future が遅れて `true` なら同一 item で `$/gitlab/ai-context/remove`(LS の契約を実ソースで確認) | §9.3.3, §11.5, §15, R20, A13b |
| 1 | P1 入力上限 | 採用。LS 自身の `MAX_CONTENT_LENGTH = 4e5`(UTF-16 単位)に合わせて 400,000 単位・末尾保持・サロゲート保護・通知 | §9.3.2, R19, A13d, §23 |
| 1 | P1 同名ユーザープロジェクトを所有とみなさない | 採用。作成前に所有ロケーションを非表示設定キーに記録し、一致しない同名プロジェクト/フォルダは無変更で拒否 | §9.2, R9, A5b, M10, U10 |
| 2 | P1 再実行前に遅延 add の確定を待て | 採用。タイムアウトでは run を解除せず、元の `add` の確定(遅延 `true` なら remove 完了)まで同じ接続での再実行を拒否。接続が替われば旧 run は破棄(旧 LS プロセスごと文脈が消える)。無応答時は Restart Language Server を案内 | §9.3 手順 2・7, §9.3.3, §16, §17, A13e |
| 2 | P1 LS 再起動時に認可状態を未確定へ戻せ | 採用。terminal context の状態を `LanguageServerSession` と組で保持し、捕捉した接続と同一のときだけ採用。chat の check 列は terminal context の check 列に含まれる(LS map で確認)ので、ゲートの正本はこれ 1 つ | §9.3.1, §8.3, A13f |
| 2 | P1 ロケーションだけで所有権を復元するな | 採用。「プロジェクトなし・記録一致・フォルダあり」の取り込み行を削除し、プロジェクトの無い既存フォルダは常に拒否 | §9.2 |
| 2 | P1 prompt 未送信時は追加済みコンテキストを補償せよ | 採用。既存入口を変えずに追跡付きの配送(`PromptDelivery` = `Sent` / `Dropped(理由)`)を追加し、`Dropped` なら同じ接続で同じ item を remove。1 枠の後勝ち規則は SWT 非依存クラスに切り出して回帰試験。残る処理順の問題は U11 として明記 | §9.3.5, §13, §14, §21, A13g, M13 |
| 2 | P2 add と prompt を同じ LS 接続に固定せよ | 採用。実行開始時に `currentSnapshot`(proxy + session)を 1 回だけ捕捉し、`add` / `remove` はその proxy に、prompt は送出瞬間に session を照合して一致時のみ送る | §9.3 手順 0, §9.3.5, A13h, M14 |
| 3 | P1 remove の成功を確認するまで run を解放するな | 採用し、さらに強めた。解決の根拠を `remove` の戻り値ではなく **`current-items` による item の不在確認**にした(`false` は「見つからない」と「失敗」を区別しないため)。確認できなければ run とバリアを保持し、LS 再起動を案内 | §9.3.3, R20, A13b, `TerminalItemResolverTest` |
| 3 | P1 フォーカス待ちの tracked prompt に終了条件を | 採用。tracked に状態機械と 30 秒の配送期限を持たせ、期限切れ・上書き・破棄は CAS で一度だけ確定。キュー上で終端済みの tracked は送らずに外す。さらに **文脈の追加を送出手順の中へ移した**ので、終了条件で止まった tracked には後始末すべきものがない | §9.3, §9.3.5, A13g, U11 |
| 3 | P1 SUPERSEDED 後の prompt を remove 完了まで保留せよ | 構造で解消。上書きは送出手順より前にしか起きず、その時点で `add` を送っていないため混入経路が存在しない。送出中に来た他の classic prompt は送出バリアで待たせ、F3 の item の不在確認の後に送る。round 2 の U11(処理順の問題)は削除 | §9.3.5, A13i, `ClassicOutboundBarrierTest`, M13 |
| 3 | P1 永続パスだけで再作成プロジェクトを所有扱いするな | 採用。作成ごとのランダム ID をプロジェクトの persistent property(削除で消え、再 import で復活しない)と非表示設定キーの両方に書き、ロケーションと合わせて両方一致のときだけ所有。閉じた同名プロジェクトは判定せず拒否 | §9.2, A5b, M10, U10 |
| 3 | P2 authentication の stale event を表示に反映するな | 採用。プロバイダの更新を既存 `AuthenticationStateService` の 2 秒 debounce の後に置き、`session` 違いの更新は捨て、LS の停止・終了(`SecurityScanLifecycle.onServerStopped()` と同じ 2 箇所)で `unknown` に戻す | §8.1, §9.1, §14, §21, A3b |
