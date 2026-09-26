# D3/D4 Duo 軽量コマンド(Quick Pick メニュー / チュートリアル)設計書

対象 issue: #14(Phase 6)/ 台帳 #7 D3・D4 / ロードマップ #8
ベースブランチ: `gitlab-ls-9.3.0` @ `cbb34f2`
参照実装: `gitlab-workflow` v6.85.3(`./out/gitlab-vscode-extension`、読み取り専用)
同梱 LS: 9.3.0(`build/gitlab-lsp/bin/main.js.map` の `sourcesContent` を実値の根拠とする。以下「LS map @<byte offset>」)

---

## 1. 背景と目的

Phase 6 の残余のうち、**独立したサブシステムを持たず、既存の配線(状態メニュー・エディタ)に薄く乗せられる 2 コマンド**を 1 サイクルで閉じる。

| VSCode コマンド | 台帳 | 現状 |
|---|---|---|
| `gl.showDuoQuickPickMenu` | D4 | ❌(固定メニューはあるがフォーラム・サインイン導線がない) |
| `gl.duoTutorial` | D3 | ❌ |

パリティは **74/85 → 76/85**(D3 +1、D4 +1)。

**F3(`gl.webview.explainSelectedTerminalOutput`、ターミナル出力の説明)は本サイクルから分離した(ユーザー決定、2026-09-26)。** 当初は 3 コマンド(パリティ 77/85)を対象としていたが、Codex round 7 で**クライアント側では閉じられない P1 が 2 件**指摘された。LS の「選択中の文脈」(`$/gitlab/ai-context/*`)は全ての classic prompt が共有する単一のストアであり、LS プロトコルは「webview が prompt の処理を終えた」ことをクライアントに通知しない。そのため (1) webview が処理中の先行 prompt が、F3 が後から追加した terminal item を消す/読むことがあり、(2) `Sent` 後に item を除去する正しい時刻は固定タイマー以外に存在しない。参照実装の VSCode 拡張も同じ競合を抱えたまま出荷されている。F3 はトランスポートから設計し直す別サイクルに送る(検討記録は付録 A)。

**台帳・ロードマップの記述訂正を伴う。** #14(2026-08-07 の分析)および #8 は「D4 の全行が `vscode.comments` API に依存する」としているが、`gl.showDuoQuickPickMenu` は **Quick Chat とも `vscode.comments` とも無関係**である(`src/common/duo_quick_pick/commands/show_quick_pick_menu.ts:100-118` は `QuickPick` だけで構成され、`comments` を import しない)。本サイクルで D4 のこの 1 行を等価実装で ✅ にし、#14 / #8 の当該記述を訂正する。

## 2. 対象範囲

| # | 台帳行 | VSCode | Eclipse での形 |
|---|---|---|---|
| F1 | Duo クイックピックメニュー(D4) | `gl.showDuoQuickPickMenu` | 既存の固定トリムメニュー「GitLab Duo」(`plugin.xml:1593-1603` / `:1605-1711`)に **3 項目を追加**(フォーラム / サインイン(未認証時のみ)/ Duo ドキュメント)。ユーザー決定 Q1=(A) |
| F2 | Duo チュートリアル(D3) | `gl.duoTutorial` | ワークスペースプロジェクト **`GitLab Duo Tutorial`** を作成し、その中の `.js` を開く。ユーザー決定 Q2=(a) |

**1 設計書・1 Codex レビュー・1 実装 PR(2 タスク: T1 = F1、T2 = F2)**(ユーザー決定 Q5。当初の「3 タスク」は F3 の分離(2026-09-26)に伴い変更)。

## 3. 対象外

- **D1 多アカウント(`gl.validateAccounts` / `gl.removeAccount` / `gl.selectWorkspaceAccount` / `gl.status.account`)。** ユーザー決定: 本プラグインは単一アカウント運用であり、対象外とする(#7 / #14 に記録する)。
- **OAuth タイマー修正。** `GitLabPreferencePage.kt:72` の `if (BuildConfig.OAUTH_ENABLED)` により OAuth UI 自体が無効化されており、`OAUTH_ENABLED` は `build.gradle.kts:88-92` で `false` 固定。到達不能な経路の修正は行わない(ユーザー決定)。
- **D4 の残り 3 行(Quick Chat 系)** と **Agent Sandboxing の状態・トグル項目**(`utils.ts:101-143`)。Sandbox は LS の `sandbox` feature state を UI に反映する新規サブシステムであり、本サイクルの「薄い追加」の範囲を超える。
- **メニューの動的再構築。** VSCode は QuickPick を開くたびに項目を組み立て直す(`show_quick_pick_menu.ts`)が、Eclipse 側は既存の固定メニューを維持する(Q1=(A))。
- **F3 ターミナル出力の説明(`gl.webview.explainSelectedTerminalOutput`、D3)。** ユーザー決定(2026-09-26)により本サイクルから分離。理由: LS の選択中文脈(`$/gitlab/ai-context/*`)は全 classic prompt が共有する単一ストアで、LS は prompt の処理完了をクライアントに通知しないため、先行 prompt による item の消去/読取(round 7 P1)と `Sent` 後の除去時刻の不定(同 P1)をクライアント側で閉じられない。VSCode 参照実装も同じ競合を持つ。**別サイクルでトランスポートから再設計する(検討記録は付録 A)。**
- **新規 OSGi 依存の導入**(`org.eclipse.terminal.*` / `org.eclipse.tm.terminal.*` / `org.eclipse.ui.console` / `org.eclipse.debug.ui`)。§6.2。

## 4. 現在の課題

1. **F1**: 状態メニューに「フォーラム」導線がなく(コード内に `forum.gitlab.com` への参照はゼロ)、未認証時の導線は 2 秒デバウンス付きのポップアップ(`AuthenticationStateService.kt:33-47, 55-80`)しかない。ポップアップは 3 秒で閉じる(`:78`)ため、見逃すと設定ページを自力で探すことになる。
2. **F2**: チュートリアルが存在しない。VSCode は untitled ドキュメントを開くだけ(`duo_tutorial.ts:159-166`)だが、Eclipse では **`IFileEditorInput` でないエディタは LS に `didOpen` されない**(`GitLabLanguageServerOpenFilesService.kt:135-142`)ため、同じ手は使えない。ワークスペース上の実ファイルが必要。
3. **既存の配線上の癖**: `gitlab-eclipse-plugin.commands.ExplainCode` 等はハンドラ(`plugin.xml:651-654`)とメニュー(`:1091-1101`)があるが **`<command>` 宣言がない**(`org.eclipse.ui.commands` 拡張 `:4-434` に該当 id なし)。本サイクルで追加するコマンドは全て `<command>` を宣言する(§11.1)。

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
| R6 | コマンド実行で、ワークスペースに **`GitLab Duo Tutorial`** という名前のプロジェクトを(なければ)作成し、その直下の **`duo_tutorial.js`** をエディタで開く。**同名プロジェクトが閉じている場合は、所有かどうかにかかわらず開かずに拒否する**(persistent property は閉じたプロジェクトでは読めず、所有を安全に照合できないため。ユーザーが自分で開いて再実行すれば、所有一致なら通常どおり開く)。 |
| R7 | 開いたエディタで **Code Suggestions が動く**こと。すなわち (a) `IFileEditorInput` で開かれ `didOpen` が送られる(`GitLabLanguageServerOpenFilesService.kt:119-125, 144-149`)、(b) `languageId` が `javascript` になる(`LanguageServerLanguage.kt:15, 34-40`: 拡張子 `js` → `"javascript"`)、(c) JavaScript が既定で有効である(`CodeSuggestionsLanguageService.kt:22-29`: サポート言語は `CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES` に含まれない限り有効、既定値は `""`(`PreferenceInitializer.kt:17`))。 |
| R8 | 内容は VSCode の `duo_tutorial.ts:4-157` を **Eclipse 向けに翻案**する(§12.2): キーは `plugin.xml:444-500` の実バインド、Quick Chat 節は削除、Explain / Tests / Refactor はエディタ右クリック「GitLab Duo Chat」サブメニューの**実ラベル**(`plugin.xml:1093` Explain Code / `:1117` Generate Tests / `:1129` Refactor Code)、MIT 表記を保持、上流の誤記(`duo_tutorial.ts:36-37`: Command と Control の OS が逆)を修正。 |
| R9 | **ユーザーの編集を上書きしない。ユーザーのプロジェクト・フォルダに触らない。** ファイルが既にあれば内容を書き換えず開くだけ。同名のプロジェクト/フォルダがプラグイン作成のもの(§9.2 の所有記録と一致)でなければ、何も変更せず拒否する(§9.2 の分岐表)。 |
| R10 | 表示条件は VSCode の `commandPalette` の `when`(`package.json:218-221`: `config.gitlab.duoChat.enabled && gitlab:chatAvailable && gitlab:chatAvailableForProject`)に対応させる。Eclipse では `duo_chat_enabled == true` がその等価物(§11.3)。 |

### 5.3 (F3 分離により該当なし)

R11–R20 は F3 用だったため削除(付録 A)。

### 5.4 非機能要件

| ID | 要件 |
|---|---|
| N1 | 新規 OSGi 依存ゼロ(`Require-Bundle` は `build.gradle.kts:162-192` のまま)。 |
| N2 | 判断ロジックは SWT 非依存の純関数/クラスに置き、SWT に触る殻は注入シームの背後に置く(既存パターン: `ClipboardWriter.kt:49-52`、`NotificationUtils.kt:29-33`、`WorkspaceFileOpener.kt:46-51`、`ChatIntentRouter.kt:26-32`)。 |
| N3 | 既存の 36 件失敗集合と detekt ベースラインを変えない(`verify.sh` が `FAILSET_IDENTICAL`)。 |

## 6. 前提条件と制約

### 6.1 共通制約(#8 より)

- ディレクトリ構成の変更禁止。新規コードは `src/main/kotlin/com/gitlab/eclipse/` 配下の既存パッケージ体系に追加する。
- ビルドシステムの変更禁止。`build.gradle.kts` / `gradle.properties` / `detekt.yml` は変更しない。
- ドキュメントはコミットしない(本設計書はレビュー専用ブランチのみ)。
- プロトコル定数は実ソースで確定してから計画に埋め込む(本書 §11 / §12 は全て LS map と VSCode ソースから抽出済み)。

### 6.2 新規バンドル依存を導入しない(確定)

対象プラットフォームは `eclipseRelease = "4.33"`(`build.gradle.kts:160`)で、`eclipseDependencies`(`:162-192`)は変更しない。F1 / F2 はワークベンチ・リソース API と既存の Koin サービスだけで成立し、新規依存を要しない。(Terminal / Console 系バンドルに関する事実は付録 A に記録。)

### 6.3 検証上の制約

- devcontainer は headless。SWT / Display / Clipboard / ワークベンチを要する箇所はテストできない。決定層と殻を分け、殻は手動検証(§25)に回す。
- `./gradlew build` は 36 件失敗で BUILD FAILED が正常。合否は `.superpowers/sdd/phase5b/verify.sh` の `FAILSET_IDENTICAL` で判定する。
- detekt は `detektMain` / `detektTest` を明示実行し、ベースラインとの一致で判定する。

### 6.4 lsp4j の制約(#20 の教訓)

- `GitLabLanguageServer` は `LanguageServer` を継承しない(`GitLabLanguageServer.kt:27-37`)。新メソッドはこのインターフェースに **直接** 追加する。
- **オーバーライドに `@JsonRequest` を重ねない**(`:84-91` の KDoc: 重ねると `Multiple methods for name` になる)。
- クライアント側 `GitLabLanguageServerClient` に同名メソッドがあると応答型が `Object` に潰れる(`:84-91`、`pluginRequest` の事例)。**本サイクルでは `GitLabLanguageServer` にメソッドを追加しない**(F3 分離。ai-context 系要求の宣言に関する注意は付録 A)。

## 7. システム構成

```
 F1 ──────────────────────────────────────────────────────────────────────────
   $/gitlab/featureStateChange ("authentication")
        │ GitLabLanguageServerClient.kt:107 の case に 1 行追加
        ▼
   AuthenticationSourceProvider ── gitlab_sign_in_required ──▶ plugin.xml visibleWhen
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
```

## 8. コンポーネントの責務

### 8.1 F1(パッケージ `com.gitlab.eclipse.authentication` / `com.gitlab.eclipse.handlers`)

| コンポーネント | 責務 | 根拠・既存パターン |
|---|---|---|
| `AuthenticationSourceProvider` | `AbstractSourceProvider`。**内部状態は 3 値**(未確定 / 認証済み / 未認証)。**公開するソース変数は `Boolean` の `gitlab_sign_in_required`**: 現在の接続で「未認証」と確定しているときだけ `Boolean.TRUE`、未確定・認証済み・接続不一致はすべて `Boolean.FALSE`(Codex round 9 P1 反映: core expression の `<equals value="..."/>` は引用符なしの `true` / `false` を `Boolean` に変換して比較する(`Expressions.convertArgument`、eclipse.platform `master` `runtime/bundles/org.eclipse.core.expressions/src/org/eclipse/core/internal/expressions/Expressions.java`)ため、文字列を公開すると一致しない。既存の `duo_chat_enabled` も `Boolean` を公開し `value="true"` で比較している、`DuoChatStateService.kt:39-41` / `plugin.xml:1097-1098`)。**値は既存 `AuthenticationStateService` の 2 秒デバウンスを通った後にだけ更新する**(Codex round 3 P2 反映。`AuthenticationStateService.kt:31-33` が「LS 起動直後の連続通知は古い認証状態を含みうる」として debounce を必須にしているため)。`update(change, session)` は debounce 後の同じ `featureStateChange` を受け、`checkId` が `authentication-required` または `invalid-token` で `engaged == true` のものがあれば `"false"`、なければ `"true"`(`allChecks == null` なら更新しない)。**状態は `AtomicReference<Pair<LanguageServerSession, Boolean>?>` の 1 値で持ち、`update` はその組を書き、`reset()` は `null` を書く。公開値は「保存された組の `session` が現在の接続(`currentSnapshot?.session`)と同一参照で、かつ値が未認証なら `true`、それ以外(未確定・認証済み・接続不一致)は `false`」で、`getCurrentState` と `fireSourceChanged` のたびにこの規則で計算する**(Codex round 8 P2 反映: 旧接続の debounce が照合を通過した直後に停止・`reset()` が挟まり、その後に旧値が書き込まれても、公開時の照合で `false`(= 未確定扱い)になる。比較と書き込みを原子的にする代わりに、**公開を接続に結び付ける**)。第 1 の防御は `AuthenticationStateService.update` の入口での拒否(§9.1 手順 1)。**LS の停止・終了で `reset()`**(§9.1)。`fireSourceChanged` は UI スレッド(`asyncExec`)。 | check id は LS map @23528968(`AUTHENTICATION_REQUIRED = "authentication-required"`, `INVALID_TOKEN = "invalid-token"`)。既存 `AuthenticationStateService.kt:38` も `authentication-required` を見る。プロバイダの形は `DuoChatStateService.kt:10-53` の複製 |
| `ShowDuoForum` / `ShowDuoDocumentation` | `AbstractHandler`。固定 URL を `BrowserLauncher.open`(`navigation/BrowserLauncher.kt:12-20`)で開く。 | `ShowDocumentation.kt:14-21` と同型。URL は定数として `companion object` に置き、テストで文字列一致を固定する |
| `SignIn`(command id のみ) | ハンドラクラスは **既存 `ShowSettings`**(`ShowSettings.kt:10-19`)を別 command id に割り当てる。 | 「同一クラス・複数 command id」は `ShowDiagnostics` / `ShowDiagnosticsFromSidePanel` で既に採用(`plugin.xml:902-910`) |

**内部を 3 値にする理由**: 2 値だと「まだ届いていない」と「認証済み」が区別できず、起動直後に Sign in 項目が一瞬出る/出ないの揺れが起きる(R4)。既存 `AuthenticationStateService.kt:25` も `Boolean?` で「未知」を持つ。公開値は「未認証と確定」だけを `true` にする Boolean に畳むので、未確定の間は表示されない。

**Koin 登録**: `AuthModule.kt:6` に `single<AuthenticationSourceProvider>` を追加し、`ChatModule.kt:18-32` と同じく `ISourceProviderService.getSourceProvider("gitlab_sign_in_required")` で **ワークベンチが生成したインスタンスを解決**する(2 インスタンス化すると `fireSourceChanged` が届かない。`ChatModule.kt:25-26` の P1-K コメントが同じ事故の記録)。

### 8.2 F2(パッケージ `com.gitlab.eclipse.codesuggestions.tutorial`)

| コンポーネント | 責務 | 根拠・既存パターン |
|---|---|---|
| `DuoTutorialContent` | `object`。プロジェクト名 `PROJECT_NAME = "GitLab Duo Tutorial"`、ファイル名 `FILE_NAME = "duo_tutorial.js"`、本文 `TEXT`(Kotlin raw string)。 | VSCode も本文をソース内テンプレートに持つ(`duo_tutorial.ts:3-4`)。本リポジトリでも `McpConfigService.kt:45` が `DEFAULT_CONFIG_TEMPLATE` を Kotlin 定数で持つ。**リソースファイルにしない**ので `src/main/resources`(現在 `plugin.xml` / `log4j2.xml` / `icons` のみ)にもビルドにも触れない |
| `DuoTutorialProjectPlanner` | 純ロジック。「プロジェクトの有無・開閉・所有・ディスク上のフォルダの有無・ファイルの有無」を入力に、実行すべき行動列(`CreateProject` / `OpenProject` / `CreateFile` / `OpenEditor` / `Refuse(reason)`)を返す(§9.2 の分岐表)。`IProject` に触らず、状態を `data class` で受ける。 | `ChatIntentRouter.kt:26-32`(決定だけを切り出す)と同じ動機 |
| `DuoTutorialWorkspaceWriter` | `WorkspaceJob`。`runInWorkspace` で状態を再読み取りして plan を再計算し、行動列を実行する。**結果を `WriterOutcome` で返す**: `Ready(file)`(開くべき所有ファイル)/ `Refused(reason)`(Job 内の再判定で拒否)/ `Failed`(例外・補償済み)/ `Cancelled`(補償済み)。`IStatus` は Eclipse への報告用で、**エディタを開くかどうかは `WriterOutcome` だけで決める**。`rule = workspace.root`、`isUser = true`。**この Job が作ったプロジェクトを追跡**し、property 設定前の非正常終了(例外・キャンセル)ではすべて削除を試みる(§9.2)。 | `DiagnosticMarkerService.kt:96-107`(`WorkspaceJob` + `rule` + `schedule`)、`ClonedProjectImporter.kt:176-200`(`create` → `open` と補償) |
| `DuoTutorialHandler` | `AbstractHandler`。状態の読み取り → Planner → Writer の起動 → 完了時に `asyncExec` で `openInActiveEditor`(`utils/EditorOpening.kt:34-47`)。 | — |
| `DuoTutorialOwnership` | 所有記録の読み書き(§9.2)。`record(id, locationUri)` / `isOwned(project)`(開いている・persistent property `duoTutorialId` が記録 ID と一致・`locationURI` が記録と一致)。設定ストアとプロジェクトへのアクセスは注入(テストで fake) | 非表示キーの前例 `DUO_CHAT_SELECTED_WEBVIEW`(`PreferenceConstants.kt:22`) |

### 8.3 (F3 分離により該当なし)

F3 のコンポーネント(DTO / 選択取得 / ゲート / 上限 / 追跡付き配送)は付録 A に記録。

## 9. 処理フロー

### 9.1 F1

1. LS が `$/gitlab/featureStateChange` を送る → 既存どおり `GitLabLanguageServerClient.kt:107` の `"authentication"` case が `AuthenticationStateService.update(change, session)` を呼ぶ(`update` に `session` 引数を足し、クライアントは自分の `session`(`GitLabLanguageServerClient.kt:58`)を渡す)。**`update` の入口照合・既存 Job の取消・新 Job の投入は、`AuthenticationStateService` 内の 1 つのロック(`synchronized(lock)`)の中で一続きに行う**(Codex round 9 P1 反映: 照合と取消が別操作だと、旧接続の呼び出しが照合を通過した直後に接続が替わり、新接続の Job が投入された後で旧呼び出しが再開して新 Job を取り消しうる)。ロックの中では、**`session` が現在の接続(`GitLabLanguageServerWrapper.currentSnapshot?.session`、`GitLabLanguageServerWrapper.kt:21-22`)と同一参照でなければ、既存の debounce Job を取り消す前(`AuthenticationStateService.kt:29` の `showAuthNotifJob?.cancel()` より前)に通知を捨てて return する**(Codex round 7 P2 反映: 旧クライアントの遅れた通知が新しい接続の debounce を取り消し、`gitlab_sign_in_required` が `unknown` のまま残るのを防ぐ)。通過した通知は既存どおり debounce ブロック(`:33-46`)に入る。**debounce ブロックは `delay(notifDelay)` の直後に、もう一度 `session` が現在の接続と同一参照かを確かめ、違えば何もせず終わる**(ロックを抜けた後に接続が替わった場合の第 2 の照合。旧接続の Job がポップアップを出したりプロバイダを更新したりしない)。**プロバイダへの通知は `delay(notifDelay)` の直後、既存のすべての早期 return(`:36-39` の `find { ... } ?: return@launch` と `:42` の `if (isAuthenticated == newAuthenticationState) return@launch`)より前に置く**(Codex round 8 P1 反映: LS 再起動でプロバイダだけが `unknown` に戻り、既存の `isAuthenticated` は以前の値を保つため、新しい接続が同じ認証値を返すと後者の早期 return に止められてプロバイダが更新されない)。ポップアップの判定・表示は既存の早期 return のまま不変(旧接続の遅れた通知を無視する点を除く)。プロバイダ側の公開時照合(§8.1)は第 2 の防御として残す。
1'. LS の停止・終了: `GitLabLanguageServerProcessProvider` が既に `SecurityScanLifecycle.onServerStopped()` を呼んでいる 2 箇所(`:228` 終了コールバック / `:262` 停止経路)に並べて `AuthenticationSourceProvider.reset()` を呼ぶ(内部状態を未確定に戻し、公開値 `gitlab_sign_in_required` を `false` にする)。
2. プロバイダが `gitlab_sign_in_required` を発火 → メニューの `visibleWhen` が再評価される。
3. 項目の動作: Forum / Duo Documentation は外部ブラウザ、Sign in は設定ダイアログ。いずれも同期・即時・LS 往復なし。

**メニューは一度だけ生成される**(`ShowPluginStatusMenu.kt:15-28`: `menu == null` のときだけ `populateContributionManager`)。`visibleWhen` を持つ寄与項目の可視性が、生成後の変数変化に追随するかは **U1(実機確認)**。追随しない場合の代替は `ICommandService.refreshElements`(`ChatAvailabilityService.kt:93-98` が `chatStatus` に使っている)ではなく、`ShowPluginStatusMenu` で毎回 `menuManager.update(true)` を呼ぶ 1 行の追加になる(§21 に影響として記載)。

### 9.2 F2

```
execute(event)                                   ← UI スレッド
  ├ 1. state = 状態の読み取り(IProject.exists/isOpen/locationURI、ディスク上のフォルダ、記録済み所有ロケーション、IFile.exists)
  ├ 2. plan = DuoTutorialProjectPlanner.plan(state)
  ├ 3. plan が Refuse → 通知して終了(ワークスペースは一切変更しない)
  ├ 4. Writer(WorkspaceJob, rule=root).schedule()   ← バックグラウンド
  │      runInWorkspace: 状態を再読み取りして plan を再計算 → Refuse なら `Refused(reason)` を返して終了(何も変更しない)
  │                      → 行動列を順に実行(各行動の前にキャンセル確認)→ `Ready(file)` / `Failed` / `Cancelled`
  └ 5. Job 完了(JobChangeAdapter.done)→ asyncExec {
           Ready(file) → openInActiveEditor(file)
           Refused(reason) → §9.2 の拒否の通知(UI 読み取り後・Job 再読み取り前に同名資産が現れた競合を含む)
           Failed → 通知「Could not create the GitLab Duo Tutorial project. See the Error Log.」
           Cancelled → 何もしない }
```

**所有権の記録(Codex round 1 / round 2 / round 3 P1 反映)**: プラグインが作ったプロジェクトかどうかを**名前でもパスだけでも判定しない**。
新規作成時に次の 2 つを記録し、**両方が一致するときだけ**所有とみなす。

1. **ランダム ID**: 作成ごとに `UUID.randomUUID()` を生成し、(a) 作成したプロジェクトの **persistent property**
   `QualifiedName("com.gitlab.eclipse", "duoTutorialId")` と、(b) 非表示の設定キー `PreferenceConstants.DUO_TUTORIAL_PROJECT_ID = "gitlab.duoTutorial.projectId"` の両方に書く。
   persistent property はワークスペースのメタデータに保存され、**プロジェクトを削除すると消える**(同じフォルダを再 import しても復活しない)。
   これで「削除 → 同じパスにユーザーが無関係なフォルダを作成 → import」を識別できる。
2. **ロケーション URI**: **`create` の後に** `IProject.getLocationURI().toString()` を取得して非表示キー `PreferenceConstants.DUO_TUTORIAL_PROJECT_LOCATION = "gitlab.duoTutorial.projectLocation"` に書く(プロジェクト記述を差し替えて別の場所を指させた場合の検出)。

**所有 = 同名プロジェクトが存在し、開いており、`getPersistentProperty(duoTutorialId)` が記録 ID と一致し、かつ `locationURI` が記録ロケーションと一致する。**
非表示の設定キーを持つ前例は `DUO_CHAT_SELECTED_WEBVIEW`(`PreferenceConstants.kt:22` / `LanguageServerBrowserView.kt:154`)。nature やマーカーファイルは使わない(`plugin.xml` の拡張もユーザーから見えるファイルも増やさない)。キー名は `SecretRedactionConventionTest.kt:16-17` の検出語を含まない。

- **閉じた同名プロジェクトは所有判定をしない**(persistent property は開いていないと読めない)。判定のためにユーザーのプロジェクトを開くことはせず、一律 `Refuse`(安全側)。
- **記録の順序(Codex round 8 P1 反映)**: ① ID(`UUID.randomUUID()`)を生成(メモリ上のみ)→ ② `create`(既定ロケーション。この行はディスク上にフォルダが無いことを Job 内の再読み取りで確認済み)→ ③ **`create` の後に** `project.locationURI` を取得(未作成のプロジェクトのハンドルでは `null` になりうるため、作成前には読まない)→ ④ 設定キー 2 つ(ID・ロケーション)を `setValue` し、**`ScopedPreferenceStore.save()` で明示的に永続化**(前例: `PublishRecordStore.kt:42-54` の「`setValue` → `save()`、失敗時は直前の値へ戻して `false`」)→ ⑤ `open` → ⑥ persistent property の設定 → ⑦ `CreateFile`。
  - **④ の保存が失敗したら**(例外)、設定キーを直前の値へ戻し、② で作ったプロジェクトを補償削除して `Failed`。保存できたときだけ ⑤ へ進む。これで「プロジェクトの property だけが残り、設定側に記録が無い」状態は作られない。
  - **② の後、⑥ が済む前に Job が非正常終了したら**(③〜⑥ の失敗・例外・キャンセルのいずれでも)、この Job 内で作成したプロジェクトを内容ごと削除する(補償。**削除には Job の monitor を渡さず `NullProgressMonitor` を使う**(Codex round 9 P2 反映: キャンセル済みの Job の monitor を渡すと削除自体が直ちにキャンセルされ、補償されない)。削除対象はこの Job が作ったものだけ)。補償の削除も失敗した場合だけプロジェクトが残り、次回は「閉じている」または「ID 不一致」で `Refuse` に倒れる(通知で削除を案内。§20)。
  - ②〜④ の間でプロセスが異常終了(強制終了・クラッシュ)した場合は補償が走らず、記録の無いプロジェクトが残りうる。次回は「閉じている」または「ID 不一致」で `Refuse`(安全側)になり、通知に従ってユーザーが削除する(残るリスクとして §27)。
- 記録は 1 組だけ(最新の作成)。設定ストアは `InstanceScope`(ワークスペース単位)なので、ワークスペースをまたいで所有を誤認しない。

**分岐表(R9。`state` → 行動)**:

| プロジェクト `GitLab Duo Tutorial` | 所有(ID とロケーションの両方一致) | ディスク上のフォルダ | `duo_tutorial.js` | 行動 |
|---|---|---|---|---|
| なし | — | なし | — | `CreateProject`(既定ロケーション = ワークスペース直下)→ `locationURI` 取得 → ID とロケーションを記録して保存 → `OpenProject` → property 設定 → `CreateFile` → `OpenEditor`(順序と失敗時の補償は上記「記録の順序」) |
| なし | **問わない** | あり | — | **`Refuse`**。プロジェクトの無い既存フォルダは一切取り込まない(パスは再利用されうるため、記録だけでは由来を証明できない)。通知「A folder named 'GitLab Duo Tutorial' already exists in the workspace location. Rename or remove it to use the tutorial.」 |
| あり・閉じている | **判定しない** | — | — | **`Refuse`**。プロジェクトを開かない。通知「A project named 'GitLab Duo Tutorial' exists but is closed. Open it and run the command again, or rename it.」 |
| あり・開いている | **不一致 / 未記録**(ユーザー自身の同名プロジェクト、削除後に再 import されたもの等) | — | — | **`Refuse`**。**ファイルを追加しない。** 通知「A project named 'GitLab Duo Tutorial' already exists and was not created by GitLab. Rename it to use the tutorial.」 |
| あり・開いている | 一致 | — | なし | `CreateFile` → `OpenEditor` |
| あり・開いている | 一致 | — | あり | **`OpenEditor` のみ**(内容は一切触らない) |

**ファイル作成は `IFile.create(InputStream, false, monitor)`**(`force = false`)。ディスク上に同名ファイルがあり Eclipse が未同期なら `CoreException` になり、その場合は `refreshLocal` してから「あり」行に落とす(上書きしない)。

**エディタの種類**: `IDE.openEditor(page, file)`(`EditorOpening.kt:41`)はエディタレジストリの既定で開く。JavaScript 用エディタ(Wild Web Developer 等)が入っていなければ Eclipse 既定のテキストエディタになる。**どちらも `ITextEditor`** なので `CodeSuggestionsManager.kt:86-95` の `editor is ITextEditor` を通り、セッションが張られる。Generic Editor が選ばれた場合も `ExtensionBasedTextEditor` は `ITextEditor` である。実際に何が選ばれるかは **U2(実機確認)**。

**非 git プロジェクトと LS のプロジェクト方針**: LS の `duo-disabled-for-project` チェックは、`enabledWithoutGitlabProject === true` なら常に非 engaged(LS map @28563384)。Eclipse の既定は `true`(`PreferenceInitializer.kt:22`、`GitLabLanguageServerConfigurationService.kt:154-156` で送信)。`false` にしていても、GitLab プロジェクトが見つからないフォルダは `DuoProjectStatus.NonGitlabProject`(@28416839)であり `DuoDisabled` ではないので engaged にならない(@28562112: `NonGitlabProject` は `hasDuoAccess` を変えない)。**チュートリアルプロジェクトは Duo を無効化しない。**

**ワークスペースフォルダの通知(Codex round 5 P2 で訂正)**: 既存の `ProjectOpenLanguageServerListener`(`lsp/listeners/ProjectOpenLanguageServerListener.kt`、Koin で `createdAtStart = true` 登録、`LanguageServerModule.kt:42-43`)が `POST_CHANGE` のプロジェクト集合の変化を検出し、**`workspaceFolders` を載せた `workspace/didChangeConfiguration` を送出ロック(`outboundLock`)の下で非同期に送る**。したがって Tutorial プロジェクトの作成は、既存の仕組みで LS のワークスペースフォルダに反映される(以前の版の U3「通知されない」は誤りだったので削除した)。順序: 作成(Job 内)→ リスナーの非同期送出 と、Job 完了 → `asyncExec` → エディタを開く → `didOpen` は**並行**で、どちらが先に届くかは決まらない。Code Suggestions は `didOpen` 単位で動き、ワークスペースフォルダに無いファイルでも前述のとおり Duo は無効化されない(`NonGitlabProject` は `hasDuoAccess` を変えない)ので、**どちらの順でも補完は損なわれない**。M3 で LS ログに新しいフォルダを含む `didChangeConfiguration` が出ることを確認する。本サイクルでリスナーは変更しない。

### 9.3 (F3 分離により該当なし)

F3 の処理フロー(選択取得 3 段・実行時ゲート・入力上限・解決・追跡付き配送・送出バリア)は付録 A に要約。

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

4 件とも **`<command>` を宣言する**(§4-3 の癖を繰り返さない)。いずれも常時有効(`<enabledWhen>` なし)。

### 11.2 ソース変数(`org.eclipse.ui.services`、`plugin.xml:502-522` に追記)

| 変数 | プロバイダ | 値 |
|---|---|---|
| `gitlab_sign_in_required` | `com.gitlab.eclipse.authentication.AuthenticationSourceProvider` | `Boolean`(現在の接続で未認証と確定しているときだけ `true`) |

`GitLabLanguageServerClient.kt:106-115` のディスパッチは、`"authentication"` case の呼び出しに `session` 引数を渡す 1 行の変更のみ(§9.1)。`FeatureStateStore.record`(`:105`)は変更しない。

### 11.3 メニュー寄与(`org.eclipse.ui.menus`)

**F1(状態メニュー、`menu:gitlab-eclipse-plugin.menus.statusWidgetMenu`)** — 既存ブロック(`:1605-1711`)は触らず、**別の `menuContribution` を追記**して位置指定する:

| 項目 | locationURI | visibleWhen |
|---|---|---|
| Sign in to GitLab | `menu:gitlab-eclipse-plugin.menus.statusWidgetMenu?before=StatusActions`(separator 名は `:1624`) | `<with variable="gitlab_sign_in_required"><equals value="true"/></with>`(`true` は core expression で `Boolean.TRUE` に変換される)  |
| GitLab Duo Tutorial | `menu:gitlab-eclipse-plugin.menus.statusWidgetMenu?after=gitlab-eclipse-plugin.menus.showDocumentation`(項目 id は `:1642`) | `<with variable="duo_chat_enabled"><equals value="true"/></with>` |
| GitLab Duo Documentation | 同上 | なし |
| GitLab Forum (Help and feedback) | 同上 | なし |

`visibleWhen` の書式は `plugin.xml:1096-1100`(`checkEnabled="false"` + `<with variable>`)に合わせる。

### 11.4 キーバインド

追加しない(F1 / F2 は既存メニューからの実行のみ。F3 のキーバインド検討は付録 A)。

### 11.5 LSP(クライアント → サーバ)

追加なし(F3 分離により該当なし)。`GitLabLanguageServer` は変更しない。

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

### 12.3 (F3 分離により該当なし)

## 13. トランザクション境界

- **F1**: なし(状態の読み取りと外部プロセス起動のみ)。
- **F2**: `WorkspaceJob.runInWorkspace` 1 回が境界。`rule = workspace.root` で、プロジェクト作成・open・property 設定・ファイル作成が 1 つのワークスペース操作としてロックされる。**`create` 成功後、property の設定が済む前に非正常終了(失敗・例外・キャンセル)したら、この Job が作ったプロジェクトを内容ごと削除して補償する**(§9.2 記録の順序)。ファイル作成の失敗はプロジェクトを残す(所有一致の開いたプロジェクトなので、次回は「ファイルなし」行で作り直せる)。

## 14. エラー処理

| 機能 | 失敗 | 扱い |
|---|---|---|
| F1 | 外部ブラウザ起動失敗 | `BrowserLauncher.open` が捕捉してログ(`BrowserLauncher.kt:14-18`)。追加処理なし |
| F1 | `authentication` の `allChecks` が null | 内部状態を変えない(未確定なら未確定のまま。公開値は `false` = 表示しない) |
| F1 | 起動直後の古い認証状態の連続通知 | debounce 後の最後の値だけを反映(既存ポップアップと同じ規則) |
| F1 | LS の停止・再起動 | 内部状態を未確定に戻し(公開値 `false`)、新しい接続の debounce 済みの値が届くまで Sign in 項目を出さない |
| F1 | 旧接続(停止済み / 再起動前)の遅れた `authentication` 通知 | `AuthenticationStateService.update` の入口で捨てる(§9.1 手順 1)。新しい接続の debounce は取り消されず、`gitlab_sign_in_required` が `unknown` のまま残らない |
| F2 | Planner が `Refuse`(同名のユーザープロジェクト / 未知の同名フォルダ) | §9.2 の文言で通知。ワークスペースは一切変更しない |
| F2 | Job の `CoreException`(ワークスペースが読み取り専用、`.project` の解析失敗等) | 通知「Could not create the GitLab Duo Tutorial project. See the Error Log.」+ ログは**例外クラス名のみ** |
| F2 | `WorkspaceJob` 内の `CoreException` / 例外 / キャンセル | **§9.2 の補償規則と同じ**: property 設定前ならこの Job が作ったプロジェクトを内容ごと削除して `Failed` / `Cancelled`。property 設定後の `CreateFile` 失敗だけは所有済みプロジェクトを残して `Failed`(次回「ファイルなし」行で回復)。補償の削除まで失敗したときだけ Job の `IStatus` を ERROR で返す(Eclipse が Job のエラーダイアログを出す) |
| F2 | エディタを開けない(`PartInitException`) | `WorkspaceFileOpener.kt:89-103` と同じ包み方。通知 + クラス名ログ。**ファイルは作られている**ので Project Explorer から開ける |

通知は全て `NotificationUtils.show`(`NotificationUtils.kt:29-50`、任意スレッドから安全)。

## 15. タイムアウトとリトライ

- **F1 / F2**: ネットワーク往復がないためタイムアウトなし。F2 の `WorkspaceJob` はキャンセル可能で、**各行動の前**に `IProgressMonitor.isCanceled` を確認する。キャンセルは `Cancelled` として扱い、この Job が作ったプロジェクトが property 設定前なら削除して補償する(§9.2)。property 設定後のキャンセル(ファイル作成前)はプロジェクトを残す(所有一致の開いたプロジェクトなので、次回は「ファイルなし」行で回復する)。
- **リトライしない。** F2 は失敗の通知後にユーザーがコマンドを再実行すれば、§9.2 の分岐表で続きから回復する。
- **UI スレッドを待たせない**: F2 のワークスペース操作は `WorkspaceJob`、完了通知からエディタを開く継続は `asyncExec`(`WorkspaceFileOpener.kt:37-38`: **`syncExec` は使わない**)。

## 16. 冪等性

| 機能 | 冪等性 |
|---|---|
| F1 | 完全に冪等(閲覧・ダイアログ表示) |
| F2 | **冪等**(§9.2 の分岐表: 2 回目以降は既存を開くだけ)。ただし「ユーザーが本文を空にした」場合もそのまま開く(空 ≠ 未作成。書き戻さない)。**閉じたプロジェクトは開かずに拒否**(R6) |

## 17. 並行処理

| 論点 | 方針 |
|---|---|
| ソース変数の更新 | `featureStateChange` は lsp4j のディスパッチスレッド(`GitLabLanguageServerClient.kt:99-103` は `runAsync`)。`fireSourceChanged` は `asyncExec` で UI へ(`DuoChatStateService.kt:25-32` と同じ)。フィールド書込は UI 転送前に行う(`ChatAvailabilityService.kt:19-21` の方針) |
| F2 のワークスペース操作 | `WorkspaceJob` + `rule = root`。UI スレッドではワークスペースを変更しない。完了通知 → `asyncExec` → エディタ。ハンドラ内の状態読み取り(`exists` / `isOpen`)は UI スレッドで行う軽い読み取りで、Job 内で**再度**読み直して分岐する(読み取りと実行の間にユーザーがプロジェクトを消す可能性) |
| F2 の二重起動 | Job に `rule = root` があるので 2 本は直列化される。2 本目は Job 内の再読み取りで「あり」行に落ち、開くだけ |
| `AuthenticationStateService.update` 入口の `session` 照合 | lsp4j のディスパッチスレッドで `GitLabLanguageServerWrapper.currentSnapshot`(`AtomicReference`、`GitLabLanguageServerWrapper.kt:17`)を 1 回読んで比較するだけ。UI には触れず、debounce の `Job` にも触れない(照合を通った通知だけが既存の `cancel()` → `launch` に進む) |

## 18. 認証と認可

- 新たな認証経路はない。F1 の Sign in は既存の設定ページを開くだけ。
- `AuthenticationSourceProvider` は **認証の真偽/未知の 3 値と接続の識別子だけ**を持ち(公開は `Boolean` 1 つ)、トークン等は一切通らない。

## 19. ログ、監視、監査

**規約**: 例外は**クラス名のみ**、パス・ファイル本文・URI は出さない(`ClipboardWriter.kt:73-74`、`WorkspaceFileOpener.kt:31, 63-64, 96`、`GitLabLanguageServerClient.kt:123-125`)。旧いハンドラにはパスを出すものがある(`OpenMcpUserConfigHandler.kt:19`)が、新規コードは新しい規約に従う。

| 機能 | 出すログ |
|---|---|
| F1 | ハンドラ実行 1 行(`info`、URL は固定定数なので出してよい) |
| F2 | Job 開始/完了(`info`)。行動列(`CreateProject` 等の**種別名**のみ)。パスは出さない |

## 20. 障害時の復旧方法

- **F1**: 項目が出ない → `gitlab_sign_in_required` の値を Diagnostics(`ShowDiagnostics`)の feature state で確認(`authentication` の checks)。設定ページは従来どおりメニュー「Show Settings」から到達できる。
- **F2**: 作成途中の失敗・キャンセルは Job 内の補償(作ったプロジェクトの削除)で残らない。補償の削除まで失敗して閉じた / ID 不一致のプロジェクトが残った場合は、拒否の通知に従い Project Explorer からプロジェクトを削除(内容ごと)して再実行する。ユーザーが Tutorial プロジェクトを閉じた場合は、自分で開いてから再実行する(R6)。

## 21. 既存機能への影響

| 既存 | 影響 |
|---|---|
| `plugin.xml` | **追記のみ**、4 箇所: `org.eclipse.ui.commands`(`:4-434`)末尾にコマンド 4 件 / `org.eclipse.ui.services`(`:502-522`)にソースプロバイダ 1 件 / `org.eclipse.ui.handlers`(`:590-991`)末尾にハンドラ 4 件 / `org.eclipse.ui.menus`(`:1011-1711`)末尾に `menuContribution` ブロック 2 つ(§11.3)。既存の状態メニューブロック(`:1605-1711`)は**編集しない**(位置は `?before=` / `?after=` で指定) |
| `GitLabLanguageServerClient.kt:106-115` | `"authentication"` case の呼び出しに `session` を渡す 1 行の変更のみ。既存 case の動作は不変 |
| `GitLabLanguageServer.kt` | 変更なし(F3 分離) |
| `AuthModule.kt` | `single<AuthenticationSourceProvider>` を 1 件追加。`ChatModule.kt` は変更なし |
| `PreferenceConstants.kt` | 非表示キー `DUO_TUTORIAL_PROJECT_ID` / `DUO_TUTORIAL_PROJECT_LOCATION` を 2 件追加(UI なし・既定値なし・LS へ送らない) |
| `AuthenticationStateService` | `update` に `session` 引数を追加し、**入口で現在の接続と異なる `session` の通知を捨てる(既存 debounce の取り消しより前、§9.1 手順 1)**。debounce ブロック内に `AuthenticationSourceProvider.update(featureStateChange, session)` を 1 行追加(呼び出し元は `GitLabLanguageServerClient.kt:107` の 1 箇所)。ポップアップの挙動は、旧接続の遅れた通知を無視する点を除き不変 |
| `GitLabLanguageServerProcessProvider.kt` | `SecurityScanLifecycle.onServerStopped()` の 2 箇所(`:228` / `:262`)に `AuthenticationSourceProvider.reset()` 呼び出しを 1 件ずつ並べる。ライフサイクルの制御は不変 |
| `ProjectOpenLanguageServerListener` | 変更なし。Tutorial プロジェクトの作成で既存どおり `workspaceFolders` 付きの `didChangeConfiguration` が送られる(§9.2) |
| `ShowPluginStatusMenu` | 原則変更しない。U1 が否のときだけ `menuManager.update(true)` 1 行 |
| `ShowSettings` | 変更なし(command id が 1 つ増えるだけ) |
| `ExplainCode` / `FixCode` / `GenerateTests` / `RefactorCode`、`DuoChatWindow` / `LanguageServerBrowserView` / `ChatIntentRouter` / `GitLabDuoChatWebViewClient` | 変更なし(classic chat 経路には触れない。F3 分離) |
| `ShowDocumentation` | 変更なし。ログ文言の誤り(`ShowDocumentation.kt:16` が "settings" と言う)は本サイクルでは触らない |
| `CodeSuggestionsManager` / `GitLabLanguageServerOpenFilesService` / `LanguageServerLanguage` | 変更なし(F2 はこれらの既存動作に乗るだけ) |
| `FeatureStateStore` / Diagnostics | 変更なし |
| LS へ送る設定ペイロード | 変更なし |

## 22. 移行方法 / ロールバック方法

- **移行なし。** 本サイクルで新設する永続データは次の 3 つで、**いずれも残置を仕様として許容する**(Codex round 5 P2 反映):

  | データ | 保存場所 | 寿命 | revert / アンインストール後 |
  |---|---|---|---|
  | `gitlab.duoTutorial.projectId` | `InstanceScope`(ワークスペースの `.metadata/.plugins/org.eclipse.core.runtime/.settings/` 配下の本バンドルの prefs) | ワークスペースの寿命 | 残る。読む者がいなくなるだけで無害 |
  | `gitlab.duoTutorial.projectLocation` | 同上 | 同上 | 同上 |
  | プロジェクトの persistent property `com.gitlab.eclipse:duoTutorialId` | ワークスペースのメタデータ(プロジェクト単位) | **プロジェクトの削除で消える** | 残る(プロジェクトが残る限り)。無害 |

  **後方互換の約束**: 上記のキー名・property 名は本機能専用として予約し、**将来も同じ意味でしか使わない**(意味を変えるときは別名にする)。所有判定は「ID(property)とロケーションの両方一致」なので、古い設定値が残っていても、その ID を property に持つプロジェクトが無ければ所有にならない(誤認しない)。停止・移行時の自動削除は行わない(削除処理そのものが新たな失敗経路になるため)。
- **ロールバック**: PR の revert で完結。残留物は F2 が作った `GitLab Duo Tutorial` プロジェクトと上表の永続データ(いずれも無害。プロジェクトはユーザーが削除できる)。

## 23. テスト方針

Kotest `DescribeSpec` + MockK(`build.gradle.kts:146-147`、既存例 `ClipboardWriterTest.kt:1-40`、`ChatCommandHandlerTest.kt:1-60`)。**新規 spec を作る**(`StyledText` をモックする既存 spec は headless で生成に失敗するため)。

| Spec | 対象 | 主な検証 |
|---|---|---|
| `AuthenticationSourceProviderTest` | F1 | 初期: 内部は未確定・公開値 `false`; `authentication-required` engaged → 未認証・公開値 `true`; `invalid-token` のみ engaged → 未認証・公開値 `true`; 関連する check(`authentication-required` / `invalid-token`)がどれも engaged でない → 認証済み・公開値 `false`(それまで未認証だった場合も `false` へ戻る); `allChecks == null` → 内部状態を変えない; **現在と異なる `session` で保存された値は公開されない(`false`)**; **`reset()` → 未確定・公開値 `false`**; **決定的な競合: 旧 session の更新が照合を通過した直後・書き込み前に `reset()` と接続の切り替えを挟む(fake の接続参照で順序を制御)→ 書き込み後も公開値は `false`**; **公開値の型が `Boolean` であり、plugin.xml と同じ `<with variable="gitlab_sign_in_required"><equals value="true"/></with>` を `ExpressionConverter.getDefault().perform(Element)` で式にして `EvaluationContext` で評価すると、未認証で `EvaluationResult.TRUE`・それ以外で `FALSE`**。`getProvidedSourceNames`。UI 転送はシームで捕捉 |
| `AuthenticationStateServiceProviderFeedTest` | F1 | 既存 debounce を短縮した構成で「stale(未認証)→ 最新(認証済み)」を連続投入 → プロバイダには最新値だけが 1 回届く; **新 session の通知が debounce 中に旧 session の通知が遅れて届く → 旧通知は debounce を取り消さず捨てられ、新 session の値が反映される**(§9.1 手順 1); **LS 再起動の前後で同じ認証値(例: 未認証 → 再起動 → 未認証)が届く → 既存の `isAuthenticated` が同値でもプロバイダは新しい接続の値で更新される(Sign in が `unknown` のまま消えない)**; プロバイダへ渡す通知は `allChecks` の内容にかかわらず(`authentication-required` が無くても)debounce 後に 1 回渡される(判定の規則は `AuthenticationSourceProviderTest` 側。§8.1 と同じ期待値: null / `invalid-token` のみ / 関連 check なしの 3 ケースを区別); **決定的な競合: 旧 session の呼び出しが入口の照合を通過した直後・取消の前に、新 session の通知を挟む(ロックと fake の接続参照で順序を制御)→ 新 session の Job は取り消されず、新しい値がプロバイダとポップアップ判定に届く; 旧 session の Job は debounce 後の再照合で何もしない**; 既存ポップアップの判定は不変(既存 spec の回帰) |
| `ShowDuoForumTest` / `ShowDuoDocumentationTest` | F1 | URL 定数が `constants.ts:17-18` の文字列と一致; `BrowserLauncher` シームが 1 回呼ばれる |
| `DuoTutorialContentTest` | F2 | `PROJECT_NAME` / `FILE_NAME`; 本文に MIT 表記・`Alt + D`・`Explain Code`・`Generate Tests`・`Refactor Code` を含む; `Quick Chat` / `fibonacci` / `Alt> + C` / `Alt> + T` / `Alt> + R` を**含まない**; `\\s` を含まず `$/` を含む(§12.2) |
| `DuoTutorialProjectPlannerTest` | F2 | §9.2 の分岐表の**全行**を 1 例ずつ。「ファイルあり」の全行で `CreateFile` が出ないこと(R9)。**所有不一致 / 未記録の全行で行動列が `Refuse` のみ**(プロジェクトを開かない・ファイルを作らない)。(順序は Writer 側の責務。`DuoTutorialWorkspaceWriterTest` で「`CreateProject` → ロケーション取得 → 所有記録の保存」の順を検証する。Codex round 9 P1 反映) |
| `DuoTutorialHandlerTest` | F2 | UI 時点の `Refuse` → 通知のみ・Job 未起動; `WriterOutcome` ごと: `Ready` → エディタ open シームが 1 回、`Refused` → 拒否の通知のみ(open されない)、`Failed` → 失敗の通知のみ、`Cancelled` → 何もしない; **UI 読み取り後・Job 再読み取り前に同名のユーザープロジェクト / フォルダが現れる競合 → `Refused` になり、ユーザー側のファイルを開かない** |
| `DuoTutorialWorkspaceWriterTest` | F2 | fake の `IProject` / `IWorkspaceRoot` / 設定ストアで: **未作成のハンドルは `locationURI == null` を返す fake にして、ロケーションの取得が `create` の後であること**; **設定の `save()` が例外 → 設定キーが直前の値に戻り、作ったプロジェクトを削除、`Failed`(`open` も property 設定も行われない)**; **保存に成功した値が、新しいストア インスタンス(再起動相当)から読めること**; `create` 後に `open` 失敗 → 作ったプロジェクトを内容ごと削除 1 回・`Failed`; property 設定失敗 → 同; 補償の削除も失敗 → `Failed`(Job は ERROR); `CreateFile` 失敗 → プロジェクトは削除しない; Job 内で状態を再読み取りして `Refuse` なら何も変更せず `Refused`; **キャンセルを `create` 前 / `create` 後 / 保存後 / `open` 後 / property 設定後の各行動間で発生させる → property 設定前なら作ったプロジェクトを削除して `Cancelled`、設定後なら残して `Cancelled`**; **補償の削除は、キャンセル済みの monitor を受け取ると `OperationCanceledException` を投げる fake に対しても完了する(`NullProgressMonitor` で呼ばれている)** |

**手動(実機)**: メニュー表示・可視性の切替・エディタ種別・Code Suggestions の発火(§25)。

## 24. 受け入れ条件

| # | 条件 | 検証 |
|---|---|---|
| A1 | 状態メニューに「GitLab Forum (Help and feedback)」「GitLab Duo Documentation」「GitLab Duo Tutorial」が「Show Documentation」の直後に並び、Forum / Duo Documentation が §5.1 の URL を開く | 実機(M1)+ 自動(URL 定数) |
| A2 | 未認証(トークン未設定/無効)のとき「Sign in to GitLab」が `StatusActions` 区切りの直前に出て、設定ページを開く。認証後は消える(再起動なしで) | 実機(M2)。再起動なしの切替は U1 |
| A3 | 内部状態が §8.1 の規則どおり 3 値を取り、公開値 `gitlab_sign_in_required` は「現在の接続で未認証と確定」のときだけ `Boolean.TRUE`。**plugin.xml と同じ `<with><equals value="true"/></with>` を core expression として評価し、未認証で真・それ以外で偽になる** | 自動 |
| A4 | チュートリアルコマンドで `GitLab Duo Tutorial/duo_tutorial.js` が作られエディタで開き、`didOpen` の `languageId` が `javascript`(LS ログで確認)で、本文中の `const multiply` 直後で Code Suggestions が出る | 実機(M3) |
| A5 | 2 回目の実行、本文を編集後の実行のいずれでも、**本文が書き換わらない**で開く。**Tutorial プロジェクトを閉じた状態で実行すると、開かずに拒否の通知が出る。ユーザーが開いてから再実行すると、編集済みの本文のまま開く** | 実機(M4)+ 自動(Planner 全行) |
| A5b | ユーザー自身の `GitLab Duo Tutorial` プロジェクト(開/閉)、未知の同名フォルダ、**過去の Tutorial を削除した後に同じパスへ作って import したプロジェクト**がある状態で実行すると、**そのプロジェクト/フォルダに一切変更がなく**(ファイル追加なし・開閉状態も不変)拒否の通知だけが出る | 実機(M10)+ 自動(Planner) |
| A6 | 本文が §12.2 の翻案規則を満たす(MIT 表記あり、Quick Chat なし、実ラベル、エスケープ正しい) | 自動 |
| A7 | チュートリアル実行後も Duo Chat / Code Suggestions が `duo-disabled-for-project` にならない | 実機(M3 で Diagnostics を確認) |
| A3b | 起動直後の古い認証状態の連続通知で Sign in 項目が揺れない。LS の再起動後、新しい接続の状態が確定するまで Sign in 項目は出ない。**旧接続の遅れた通知が新しい接続の debounce を取り消さず、`gitlab_sign_in_required` が `unknown` のまま残らない**(§9.1 手順 1)。**再起動の前後で同じ認証値が届いても、新しい接続の値で Sign in 項目が表示・非表示される** | 自動(`AuthenticationStateServiceProviderFeedTest` / `AuthenticationSourceProviderTest`)+ 実機(M2) |
| A16 | ログにパス・ファイル本文が出ない | コードレビュー + 自動(fake ログで文字列不在) |
| A17 | `verify.sh` が `FAILSET_IDENTICAL`、detekt がベースラインちょうど | CI 相当 |
| A18 | `Require-Bundle` が変わらない(`build.gradle.kts` 差分ゼロ) | `git diff` |

A8–A15 および A13b–A13j は F3 用だったため削除(付録 A)。

## 25. 手動検証手順

対象環境: Windows(Pleiades 2025-12)と Linux(GTK)。両方で M1〜M4・M10 を行い、結果を PR 本文に記載する。(M5〜M9・M11〜M14 は F3 用だったため削除。付録 A)

| # | 手順 | 期待 |
|---|---|---|
| M1 | ステータストリムの「GitLab Duo」をクリック | 「Show Documentation」の直後に Tutorial / Duo Documentation / Forum の順で並ぶ。後 2 者をクリックすると既定ブラウザで §5.1 の URL が開く |
| M2 | 設定でトークンを空にして LS を再起動(「Restart Language Server」)→ メニューを開く → トークンを設定して保存 → メニューを開き直す | 前者で「Sign in to GitLab」が `Duo Chat:` / `Code Suggestions:` の下に出て、クリックで設定ページ。後者で消えている(**再起動なし**で消えれば U1 = 追随する) |
| M3 | 「GitLab Duo Tutorial」をクリック | プロジェクトと `duo_tutorial.js` が作られエディタで開く。開いたエディタの種類(既定テキスト / Generic / JS エディタ)を記録(U2)。`const multiply` 直後で Space → 候補が出る。`language_server.log` の `didOpen` に `javascript`。Diagnostics で `duo-disabled-for-project` が engaged でない |
| M4 | 本文を 1 行編集して保存 → 再度コマンド → プロジェクトを閉じて再度コマンド → Project Explorer で開いて再度コマンド | 1 回目: 編集が残ったまま開く。2 回目(閉じた状態): 開かれず「...exists but is closed. Open it and run the command again, or rename it.」の通知のみ。3 回目: 編集が残ったまま開く |
| M10 | (1) 手で `GitLab Duo Tutorial` という名前のプロジェクトを作る → コマンド実行 → 閉じて再実行。(2) それを削除し、コマンドで Tutorial を作る → 内容ごと削除 → 同じパスにフォルダを作って import → コマンド実行 | どれも拒否の通知のみ。プロジェクトにファイルが増えず、開閉状態も変わらない |

## 26. 未決事項

| ID | 内容 | 扱い |
|---|---|---|
| U1 | 一度生成された状態メニューの項目可視性が、`gitlab_sign_in_required` の変化に再起動なしで追随するか(`ShowPluginStatusMenu.kt:15-28` は 1 回だけ populate) | 実機(M2)。否なら `menuManager.update(true)` を `execute` に 1 行追加 |
| U2 | `duo_tutorial.js` を `IDE.openEditor` が何のエディタで開くか(既定テキスト / Generic / Wild Web Developer)。いずれも `ITextEditor` である前提 | 実機(M3)。`ITextEditor` でないエディタが選ばれたら `IDE.openEditor(page, file, "org.eclipse.ui.DefaultTextEditor")` へ固定する |
| U3 | (削除: 既存 `ProjectOpenLanguageServerListener` が通知している。§9.2 のワークスペースフォルダの通知を参照) | — |
| U10 | 所有判定のための persistent property(`duoTutorialId`)が、Eclipse の再起動後も保持され、プロジェクト削除で消えること | 実機(M10 の後に再起動 / 削除 → 同じパスで再 import) |

U4–U9・U11・U12 は F3 用だったため削除(付録 A)。

## 27. 想定されるリスク

| リスク | 影響 | 緩和 |
|---|---|---|
| 状態メニューの可視性が追随しない(U1) | 未認証項目が出っぱなし/出ない | `update(true)` の 1 行で解消可能 |
| `WorkspaceJob` の `rule = root` が他の Job と競合して待たされる | チュートリアルが数秒遅れる | `isUser = true` で進捗が見える。ワークスペース全体のビルド中でも正しく直列化される |
| 同名プロジェクト/フォルダがユーザーのもの | ユーザーの資産を変更してしまう | 所有記録(§9.2)と一致しなければ無変更で拒否。拒否時はユーザーがリネームする必要がある(UX 上の代償) |
| Tutorial 作成の途中(`create` 〜 設定の保存)でプロセスが異常終了 | 記録の無いプロジェクトが残り、以後の実行が拒否される | 補償は Job 内の失敗・キャンセルにだけ効く。残った場合は拒否の通知に従いユーザーが削除する(安全側。§9.2 記録の順序) |
| `plugin.xml` の並行 PR 衝突 | マージ時の手戻り | 追記のみ・末尾のみ。既存ブロックは非編集 |
| headless で UI 配線を検証できない | メニューが出ない等が実機まで露見しない | UI 接触部を最小化し、配線と SWT 殻は `fable` が担当。M1〜M4・M10 |

## 28. 実装タスク分割案

実装 PR は 1 本(`feat/duo-lightweight-commands`、base `gitlab-ls-9.3.0`)。タスクは独立に実装・レビューし、`plugin.xml` は各タスクが自分の追記だけを行う(全て末尾追記なので衝突しない)。

| # | タスク | 内容 | モデル | 理由(CLAUDE.md の表) |
|---|---|---|---|---|
| T1 | F1 | `AuthenticationSourceProvider` + Koin 登録 + ディスパッチ 1 行 + `AuthenticationStateService` の `session` 引数と入口照合(§9.1 手順 1)+ `ShowDuoForum` / `ShowDuoDocumentation` + `plugin.xml`(コマンド 3・ハンドラ 3・ソースプロバイダ 1・メニュー 2 ブロク)+ spec 3 本 | `fable` | 大半は既存パターンの複製 + plugin.xml 配線だが、`AuthenticationStateService` の入口照合(lsp4j スレッドでの接続照合と debounce の取消順序)とソース変数の UI スレッド転送を含むため、CLAUDE.md の「並行処理・lsp4j ディスパッチ」行に従う |
| T2 | F2 | `DuoTutorialContent` / `Ownership` / `Planner` / `WorkspaceWriter` / `Handler` + `PreferenceConstants` 2 件 + `plugin.xml`(コマンド 1・ハンドラ 1・メニュー項目)+ spec 4 本 | `fable` | `WorkspaceJob` → `asyncExec` → エディタの**スレッド境界**と `IProject` 状態の再読み取り・所有判定(persistent property)を含む。headless で検証不能 |
| R1〜R2 | 各タスクのコードレビュー | — | `fable` | 唯一の安全網。実装者とは別インスタンスで行う |
| R3 | ブランチ全体レビュー + PR 本文(M1〜M4・M10 の手順・U-item 一覧・台帳更新案) | — | `fable` | 同上 |
| L | 台帳 #7(D3 +1、D4 +1、D1 4 行を「対象外(単一アカウント運用)」に、D3「ターミナル出力を説明」は別サイクルへ)、#14 / #8 の `vscode.comments` 記述訂正 | — | `haiku` | 即座に目視確認できる |

T1 / T2 の実装ブリーフには §8.1 / §9.1 の session 照合規則、§9.2 の分岐表、§19 のログ規約を**そのまま**埋め込む。

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
| 4 | P1 閉じた所有プロジェクトの挙動を一つに確定せよ | 採用。閉じた同名プロジェクトは所有にかかわらず開かずに拒否で統一(persistent property は閉じた状態で読めない)。R6 / A5 / M4 / §13 / §16 / §20 をそろえ、`create` 後の `open` / property 設定の失敗は Job 内で作ったプロジェクトを削除して補償 | §9.2, R6, A5, M4, §13, §16, §20, `DuoTutorialWorkspaceWriterTest` |
| 4 | P1 null の current-items を不在確認に使うな | 採用。非 null のリストで一致なしのときだけ解決。null・例外・タイムアウトは確認失敗として再試行し、10 秒で未解決に倒す | §9.3.3 手順 3, §11.5, `TerminalItemResolverTest` |
| 4 | P2 newPrompt の送出例外後も不在確認を | 採用。`SEND_FAILED` を追加し、`add` を送った後は結果・例外にかかわらず `finally` で解決(remove → 不在確認)へ進む | §9.3.5 手順 d / d', `TerminalItemResolverTest` |
| 5 | P1 Job 内の再判定結果を完了通知へ返せ | 採用。Writer の結果を `WriterOutcome`(`Ready` / `Refused` / `Failed` / `Cancelled`)で返し、`Ready` のときだけエディタを開く。UI 読み取り後・Job 再読み取り前の競合を試験に追加 | §8.2, §9.2 フロー, `DuoTutorialHandlerTest` |
| 5 | P1 current-items の待機を絶対期限で打ち切れ | 採用。個々の future とは独立したスケジューラのタイマー(`add` 送出から 20 秒)で `UNRESOLVED` と通知を一度だけ確定。個々の呼び出しにも写しに 2 秒のタイムアウト。`UNRESOLVED` 後に不在が確認できれば解放 | §9.3.3 手順 3・4, §17, `TerminalItemResolverTest` |
| 5 | P2 キャンセル時にも作成途中のプロジェクトを補償せよ | 採用。この Job が作ったプロジェクトを追跡し、property 設定前の非正常終了(失敗・例外・キャンセル)はすべて削除。各行動間のキャンセルを試験 | §9.2, §13, §15, §20, `DuoTutorialWorkspaceWriterTest` |
| 5 | P2 既存のワークスペース通知を設計に反映せよ | 採用(以前の記述の誤りを訂正)。`ProjectOpenLanguageServerListener` が `workspaceFolders` 付きの `didChangeConfiguration` を送ることを記載し、`didOpen` との順序がどちらでも補完を損なわないことを明記。U3 を削除 | §9.2, §21, U3, M3 |
| 5 | P2 永続データをロールバック設計に記載せよ | 採用。3 つの永続データ(設定キー 2・persistent property 1)の保存場所・寿命・revert 後の扱いを表にし、残置を仕様として許容、キー名の予約(意味を変えない)を約束 | §22 |
| 6 | P1 add の直前に feature state を再検証せよ | 採用。送出手順 b で session 照合に続けて `isAvailableFor(tracked.session)` を再評価し、不許可なら `Dropped(NOT_AVAILABLE)`(`add` 前) | §9.3.5 手順 b, A13j, `GitLabDuoChatWebViewClientTrackedTest` |
| 6 | P1 CoreException 時の補償方針を統一せよ | 採用。§14 の古い「部分状態は残してよい」を削除し、§9.2 の補償規則(property 設定前は削除、設定後の `CreateFile` 失敗だけ残す)に統一 | §14 |
| 6 | P2 未解決通知の時刻を 20 秒に統一せよ | 採用。試験定義の旧 10 秒を `add` 送出から 20 秒の絶対期限に置換し、`add` の写しのタイムアウト 10 秒は prompt 送出の判断にのみ使うと明記 | §23 `TerminalItemResolverTest` |
| 7 | (レビュー 2 回: 2026-09-25 13:55 / 14:35 UTC、対象コミット `c971fd8`。2 回目は当方の監視バグによる重複依頼で、指摘内容は同一。指摘は P1×5 + P2×2) | — | — |
| 7 | P1 webview が処理中の**先行** prompt が、F3 が後から追加した terminal item を消す/読む(LS の選択中文脈は全 classic prompt 共有の単一ストアで、prompt 処理完了の通知がない) | **クライアント側で閉じられない** → F3 分離により本設計では対象外(付録 A に記録) | §1, §3, 付録 A.4 |
| 7 | P1 `Sent` 後に item を除去する正しい時刻が、固定タイマー以外に存在しない | 同上(クライアント側で閉じられない)→ F3 分離により本設計では対象外(付録 A に記録) | §1, §3, 付録 A.4 |
| 7 | P1 `newPrompt` の送出前に `current-items` で item の**存在**を確認せよ(`add` の `true` は「拒否されなかった」に過ぎない) | F3 分離により本設計では対象外(付録 A に記録) | 付録 A.4 |
| 7 | P1 F3 の送出手順に入る時点で、既存のフォーカス待ちキュー(`messagesAwaitingReady`)の払い出しを止めよ | F3 分離により本設計では対象外(付録 A に記録) | 付録 A.4 |
| 7 | P2 旧 session の `chat_terminal_context` 更新を捨てよ | F3 分離により本設計では対象外(付録 A に記録) | 付録 A.4 |
| 7 | P1 `UNRESOLVED` 後の `current-items` ポーリングに上限またはバックオフを | F3 分離により本設計では対象外(付録 A に記録) | 付録 A.4 |
| 7 | P2 旧クライアントの遅れた `authentication` 通知が、新しい接続の debounce を取り消して `gitlab_authenticated` を `unknown` のまま残す | **採用。** `AuthenticationStateService.update(change, session)` の**入口**で、現在の接続(`GitLabLanguageServerWrapper.currentSnapshot?.session`)と異なる `session` の通知を、既存 debounce の取り消し(`AuthenticationStateService.kt:29`)より前に捨てる。プロバイダ側の `session` 照合は第 2 の防御として維持。試験を追加 | §8.1, §9.1 手順 1, §21, A3b, `AuthenticationStateServiceProviderFeedTest` |
| — | **ユーザー決定(2026-09-26): F3 を本サイクルから分離**し、別サイクルでトランスポートから再設計する | 本設計は F1 + F2(74/85 → 76/85)、実装 PR は 2 タスク(Q5 を変更)。F3 の規範記述(R11–R20、§8.3、§9.3、§11.3 の右クリック寄与、§11.4、§11.5、§12.3、A8–A15、M5–M9・M11–M14、U4–U9・U11・U12、T3)を削除し、付録 A に検討記録として要約 | §1, §2, §3, §28, 付録 A |

## 付録 A. F3(ターミナル出力の説明)の検討記録 — 本サイクル対象外

本付録は、本サイクルから分離した F3(`gl.webview.explainSelectedTerminalOutput`、台帳 D3「ターミナル出力を説明」)について、round 1〜7 で確定した事実・設計の変遷・未解決の問題・次サイクルの選択肢を、再設計の出発点として残すもの。**本設計の規範ではない**(本サイクルの実装 PR には含めない)。行番号・LS map オフセットは本文の旧版で実ソースから確定した値(LS 9.3.0 / `gitlab-workflow` v6.85.3)。

### A.1 確定済みの VSCode / LS 契約

| 項目 | 値 | 根拠 |
|---|---|---|
| コマンド | `gl.webview.explainSelectedTerminalOutput`、タイトル「Explain Terminal Output with Duo」 | `package.json:128` |
| メニュー `when` | `config.gitlab.duoChat.enabled && gitlab:chatAvailable && gitlab:chatAvailableForProject`。Eclipse では `duo_chat_enabled == true` が等価(LS `chat` の checks 列 `CHAT_CHECKS_PRIORITY_ORDERED` に全て含まれる) | `package.json:255-257`、LS map @23531198、`DuoChatStateService.kt:18-19` |
| キーバインド | `ctrl+alt+t` / `cmd+alt+t`。Eclipse 側の衝突(Pleiades、Terminal バンドル自身の `CTRL+SHIFT+M3+T`)は未確認 | `package.json:279-284` |
| 表示条件(feature) | `chat_terminal_context` の engaged チェックがゼロ。engaged になる check は `chat-include-terminal-context-unavailable`、その値は `DuoFeature.IncludeTerminalContext`(`"include_terminal_context"`)の可否。`CHECKS_PER_FEATURE[CHAT_TERMINAL_CONTEXT] = [...CHAT_CHECKS_PRIORITY_ORDERED, CHAT_INCLUDE_TERMINAL_CONTEXT_UNAVAILABLE]`(同じ接続で terminal context が可なら chat も可) | `chat_state_manager.ts:54-56`、LS map @23530333 / @25785954 / @28544002 / @23532164 |
| 選択取得(VSCode) | 選択が無ければ無言で戻る。取得は**クリップボード経由**: 退避 → プレースホルダ(`"... has temporarily reset the clipboard to read terminal selection"`)書込 → コピーコマンド → 読出 → 復元(失敗は `log.debug` のみ)。文字列以外は扱わない。「最後のコマンドとその出力」フォールバックあり(Eclipse の Terminal / Console にシェル統合相当の API はない) | `duo_chat_commands.ts:38-40`、`gitlab_chat_terminal_context.ts:6-7, 64-69, 71-79, 81-87, 96-102` |
| 順序 | `await add(item)` の後に `newPrompt`(`explainTerminalOutput`)。**`add` の結果は見ない** | `duo_chat_commands.ts:42-43` |
| `AIContextItem` の形 | `id`(uuidv4)/ `category = "terminal"` / `content`(選択テキスト)/ `metadata { title: "Selected command:", enabled: true, subType: "snippet", icon: "terminal", secondaryText: "", subTypeLabel: "Selected terminal output", disabledReasons?, languageId? }`。`lastCommand` 系は Eclipse に該当経路なし。フィールド名は `SecretRedactionConventionTest.kt:16-17` の検出語を含まない | `gitlab_chat_terminal_context.ts:23-37, 42-55`、LS map @25790766(`AIContextItemSchema`)/ @29180454(`TerminalMetadata`)/ @29181126(provider は `super('snippet')`、「常に content 済み」) |
| `$/gitlab/ai-context/add` | `request: AIContextItem; response: boolean`。受け口は `chatContextManager.addSelectedContextItem(item)`。**`false` は provider 解決/追加が例外を投げたときだけ**。provider は policy 不許可・subType 不一致・id 重複を `logger.error` するだけなので、その場合も `true`(= 「拒否されなかった」であって「追加された」ではない。id を毎回 UUID にする理由) | LS map @28600934 / @29322157 / @25793690 / @25788850 |
| `$/gitlab/ai-context/remove` | `add` と同一の `item` を渡す。manager は `metadata.subType` で provider を引き `removeSelectedContextItem(item.id)`、id が無いと例外 → `false`(「見つからない」と「失敗」を区別しない) | @28600940 / @29322261 / @25794149 / @25789499 |
| `$/gitlab/ai-context/current-items` | `request: undefined; response: AIContextItem[]`。受け口は `chatContextManager.getSelectedContextItems()`(選択中文脈そのもの) | @28600981 / @28601281 / @29322403 |
| `newPrompt` | `extension.onNotification("newPrompt", ({prompt, fileContext}) => controller.handleExtensionPrompt(prompt, fileContext))`。`explainTerminalOutput` は `fileContextOptional`、送られる本文は `"Explain this terminal output"`。既存経路は `ExtensionToPluginNotification(pluginId = CLASSIC_WEBVIEW_ID, type = "newPrompt", payload = NewPromptRequest)`(`GitLabDuoChatWebViewClient.kt:12-23`) | @26312928 / @26258270 / @26245433 |
| webview の文脈クリア | `handleExtensionPrompt` は `Promise.all([processNewUserRecord(record), _clearSelectedContextItems()])` で、prompt 処理時に**選択中の文脈を丸ごとクリア**する。「どの prompt がどの item を使ったか」はクライアントに伝わらない | `packages/webview_duo_chat_classic/dist/index.mjs`、LS map @26258642 付近 |
| 入力上限 | `AIContextItemSchema` に `content` 長の上限はない(VSCode も切り詰めない)。webview はファイル文脈を `MAX_CONTENT_LENGTH = 4e5`(UTF-16 コード単位)で末尾保持(`contentAboveCursor.slice(-max)`)に切り詰める(`trimActiveFileContext`) | @26232942 |
| Eclipse の既存受信 | クライアント側の ai-context 系は `$/gitlab/ai-context/git-diff` / `editor-selection` の**受信**のみ。サーバ側 `GitLabLanguageServer` に ai-context 系の宣言はない | `GitLabLanguageServerClient.kt:79, 94`、`GitLabLanguageServer.kt:39-112` |
| `chat_terminal_context` の扱い | `FeatureStateStore` に記録されるだけで、ディスパッチに case がない | `FeatureStateStore.kt:57`、`GitLabLanguageServerClient.kt:106-115` |

### A.2 Eclipse 側の事実

- **ビュー id と popup locationURI**: Terminal(新)`org.eclipse.terminal.view.ui.TerminalsView` → `popup:org.eclipse.terminal.view.ui.TerminalsView?after=additions`(eclipse.platform `master` `terminal/bundles/org.eclipse.terminal.view.ui/plugin.xml`。コンテキストメニューは `TabFolderMenuHandler` の `registerContextMenu(menuManager, selectionProvider)` = 部位 id を menu id とする形)/ Terminal(旧)`org.eclipse.tm.terminal.view.ui.TerminalsView` → 同形(CDT `CDT_11_6_0` `terminal/plugins/org.eclipse.tm.terminal.view.ui/plugin.xml`)/ Console `org.eclipse.ui.console.ConsoleView`: `TextConsolePage` が `getConsole().getType() + ".#ContextMenu"`(type が null なら `#ContextMenu`)で登録 → `popup:org.eclipse.debug.ui.ProcessConsoleType.#ContextMenu`(`IDebugUIConstants.ID_PROCESS_CONSOLE_TYPE`; `IOConsole extends TextConsole`)/ `popup:org.eclipse.ui.MessageConsole.#ContextMenu`(`IConsoleConstants.MESSAGE_CONSOLE_TYPE`)/ `popup:#ContextMenu`。`popup:<id>` が `registerContextMenu` の id に対応することは `PopupMenuExtender.java` の `"popup:" + menuId` と本リポジトリの `popup:#AbstractTextEditorContext`(`plugin.xml:1051`)で確認済み。`org.eclipse.ui.popup.any` は実ソースで確認できず不採用。ProcessConsole が実際にその type を持つか、Gradle / Maven 等の他コンソール型への追随は未確認(旧 U7)。
- **選択テキストの取得段**(headless で検証不能。旧 U4 / U5 / U6 / U9):

  | 段 | 対象 | 手段 | 状態 |
  |---|---|---|---|
  | 1 | Console | `HandlerUtil.getActiveMenuSelection(event) ?: getCurrentSelection(event)` が `ITextSelection` → `.text`(`TextConsolePage` は `setSelectionProvider(fViewer)`、`TextConsoleViewer extends SourceViewer`、`TextViewer.getSelection()` は `ITextSelection`。`ConsoleView` は `PageBookView` でページの選択プロバイダを転送。既存の併用順は `OpenMrFileHandler.kt:60-61`) | ソース上は確実 |
  | 1' | Terminal | `getCurrentSelection(event)` が `IStructuredSelection` で `firstElement is String`(`TabFolderManager.TerminalControlSelectionListener.mouseUp` が `StructuredSelection(terminal.getSelection())` を `asyncExec` で発火、`ITerminalViewControl.getSelection(): String`。ワークベンチの `SelectionService.selectionChanged` → `ESelectionService.setSelection` → `HandlerUtil.getCurrentSelection` は `ISources.ACTIVE_CURRENT_SELECTION_NAME`) | 到達性は実機未確認 |
  | 2 | Terminal | 選択中の `CTabItem` の `getData()`(`createTabItem` が `item.setData(terminal)`)に**リフレクション**で `getSelection(): String` を呼ぶ(型に触らず依存ゼロ) | 実機未確認。旧世代が同じ形かも未確認 |
  | 3 | Terminal | クリップボード経由(ユーザー決定 Q3=(b)): 退避 → プレースホルダ → `IHandlerService.executeCommand("org.eclipse.ui.edit.copy")`(`IWorkbenchCommandConstants.EDIT_COPY`)→ `TextTransfer` 読出 → `finally` 復元。**Terminal ビューが `EDIT_COPY` のハンドラを登録している証拠がない**(`TerminalsView.java` / `TabFolderMenuHandler.java` / `TabFolderToolbarHandler.java` に `setGlobalActionHandler` / `activateHandler` / `EDIT_COPY` の参照なし。コピーはメニュー内の `TerminalActionCopy` インスタンス)ため、`ICommandService.getCommand(EDIT_COPY).isHandled()` が真のときだけ実行し、`RTFTransfer` / `HTMLTransfer` / `FileTransfer` / `ImageTransfer` / `URLTransfer` のいずれかがあれば(または非テキストのみなら)クリップボードに触らない。`SWTError` と `RuntimeException` を個別に捕捉、`Clipboard` は capture ごとに生成し `finally` で `dispose()`(`ClipboardWriter.kt:101-125`) | **機能しない可能性が高い**。段 1'/2 が取れれば入らない |

  第 1'/2 段は Q3=(b) の前段であって置き換えではない(依存ゼロ、クリップボード非接触、(b) 単独では何も取れない可能性が高い)。全段不成立なら台帳は 🟡(Console のみ)とし、Terminal バンドルへの依存追加(Q3 の選択肢 (a))を別サイクルで判断する、という実機ゲートを置いていた。
- **新規 OSGi 依存は不可**(ビルドシステム変更禁止。`eclipseDependencies`(`build.gradle.kts:162-192`)に Terminal / Console / Debug 系はない。Terminal は 2025 年に CDT から eclipse.platform へ移設され名前空間が `org.eclipse.tm.terminal.*` → `org.eclipse.terminal.*` に変わったため、依存を張るとどちらか一方の世代でしか解決しない。`org.eclipse.ui.console` は D10 設計 §6.2 でも不採用)。
- **接続の同一性**: `GitLabLanguageServerWrapper.currentSnapshot` は `LanguageServerHandle`(`proxy` + `session`)を 1 値で公開し(`GitLabLanguageServerWrapper.kt:21-22`)、`LanguageServerSession` は接続ごとの同一性(`LanguageServerSession.kt:7, 22`。クライアントは `GitLabLanguageServerClient.kt:58` で生成)。F3 の設計は `add` / `remove` / `current-items` / prompt を、実行開始時に 1 回だけ捕捉した handle に固定していた(round 2 P2)。
- **lsp4j の注意**: `GitLabLanguageServer` は `LanguageServer` を継承せず(`GitLabLanguageServer.kt:27-37`)、新要求はこのインターフェースに直接追加する。オーバーライドに `@JsonRequest` を重ねない(`:84-91`)。クライアント側に同名メソッドがあると応答型が `Object` に潰れるが、ai-context の `add` / `remove` / `current-items` はクライアント側に存在しないので `CompletableFuture<Boolean?>` / `CompletableFuture<List<AiContextItem>?>` と宣言できる(宣言テストは `GitLabLanguageServerPluginRequestTest.kt:18-31` の写し)。引数なし要求を lsp4j 1.0.0 が `params` 省略 / `null` のどちらで直列化するかは未確認(旧 U12。LS の受け口は引数を読まない)。`orTimeout` は `this` を返し元の future を例外完了させる(#20)ため、写し(`thenApply { it }`)にだけ付ける。
- **既存の classic chat 配送経路**(F3 の追跡付き配送はここに入口を足す設計だった): `openDuoChatWindowWithClassicPrompt`(`DuoChatWindow.kt:30-32`)は成否を返さず、ビューは prompt を 1 枠の `pendingClassicPrompt` に置いて次の依頼で上書き(`LanguageServerBrowserView.kt:77, 177-180`)、classic 以外に解決したら捨てる(`:296-307`、`ChatIntentRouter.kt:46-57`)。classic クライアント(Koin singleton)はフォーカスが無い間 `messagesAwaitingReady` に積み、**送る瞬間の** `languageServer` に送る(`GitLabDuoChatWebViewClient.kt:12-34`)。ビューを出せないときの既存通知は `reportCannotShow`(`DuoChatWindow.kt:121-124`)。
- **ログ規約**: 選択内容・クリップボード内容は出さない。長さすら出さない(長さから内容を推測できる場面があるため)。

### A.3 設計の変遷(Codex round 1〜7)

| 巡 | 追加したもの | 理由 |
|---|---|---|
| 初版 | メニュー寄与 + 汎用 API のみ(依存ゼロ)、選択取得 3 段、`add` → `newPrompt` の順序、`add` に 10 秒タイムアウト、選択なし時は通知 | Q3=(b)、N1。VSCode は無言で戻るが Eclipse にはフォールバックがない |
| 1 | **実行時ゲート**(`<enabledWhen>` + `execute` 冒頭の純関数: feature state + 部位の許可リスト)/ **入力上限** 400,000 UTF-16 単位・末尾保持・サロゲートペア保護・通知 / **遅延成功の補償**(`orTimeout` を写しに付け、遅延 `true` なら同一 item で `remove`)/ 第 3 段の前提条件(非テキスト形式があれば触らない)/ 実機ゲート(Terminal 全段不成立なら台帳 🟡) | メニュー非表示は認可境界でなく、LS は policy 不許可でも `add` に `true` を返す。上限は webview の `MAX_CONTENT_LENGTH` に合わせる。クリップボードの非テキスト内容を壊さない。取得手段を 2 クラスに局所化 |
| 2 | **状態の接続への結び付け**(`TerminalContextStateService` が `(session, available)` の組を持ち、捕捉した handle と同一参照のときだけ採用)/ `add` と prompt を同じ handle に固定(`SESSION_CHANGED`)/ 再実行は元の `add` の確定まで拒否(run は 1 接続 1 つ)/ **追跡付き配送** `PromptDelivery`(`Sent` / `Dropped(reason)`) | LS 再起動で認可状態を未確定へ戻す。prompt 未送信時に追加済み文脈を補償する |
| 3 | 解決の根拠を `remove` の戻り値でなく **`current-items` による不在確認**に / tracked prompt の状態機械(`PENDING → SENDING → 終端`、CAS)と 30 秒の配送期限 / **文脈の追加を送出手順の中へ移動**(配送が止まる経路は全て「追加していない」で終わり、後始末不要)/ **送出バリア**(不在確認まで同じ接続の他の classic prompt を FIFO で待たせる。規則は SWT 非依存クラスに切り出す) | `remove` の `false` は「見つからない」と「失敗」を区別しない。SUPERSEDED 後の prompt に文脈が混入する経路を構造で消す |
| 4 | `null` の `current-items` は「確認失敗」であって「空」ではない / `newPrompt` の同期例外(`SEND_FAILED`)後も `finally` で不在確認へ | 空扱いすると item が残ったまま解放される |
| 5 | **絶対期限**(`add` 送出から 20 秒のスケジューラタイマーで `UNRESOLVED` + 通知 1 回、run とバリアは保持、以後の不在確認で解放)/ 個々の `current-items` / `remove` に 2 秒の写しタイムアウト | 個々の future が永遠に完了しなくても通知に到達する |
| 6 | 送出直前(`add` 前)に `isAvailableFor(tracked.session)` を再評価し、不許可なら `Dropped(NOT_AVAILABLE)` / 未解決通知の時刻を 20 秒に統一 | フォーカス待ちの間のログアウト・ライセンス失効・`include_terminal_context` 無効化 |
| 7 | (A.4。うち 2 件はクライアント側で閉じられず、F3 を分離) | — |

### A.4 round 7 の指摘(クライアント側で閉じられない 2 件 + 対処可能だった 4 件)

**クライアント側で閉じられない P1(分離の理由)**:

1. **先行 prompt による item の消去/読取。** webview は prompt 処理時に選択中の文脈を丸ごとクリアする(A.1)。送出バリアが止められるのは「F3 より後に送る prompt」だけで、F3 の送出手順に入る時点で**既に LS へ送られ webview がまだ処理中の先行 prompt**が、F3 の `add` の後に `_clearSelectedContextItems()` を実行して item を消す、あるいは F3 の `newPrompt` より先に item を読む。LS プロトコルには「webview が prompt の処理を終えた」ことをクライアントに伝える通知がなく、クライアントは先行 prompt の完了を観測できない。
2. **`Sent` 後の除去時刻。** 「5 秒残っていれば `remove`」は根拠のない固定タイマーで、webview が F3 の prompt をまだ処理していない(= item を読んでいない)うちに除去して空振りにするか、逆に長く残して次の prompt に混入させるかのどちらかになる。1 と同じく、正しい時刻を決める情報がプロトコルにない。

参照実装の VSCode 拡張(`duo_chat_commands.ts:42-43`)も `add` → `newPrompt` を投げるだけで、同じ競合を抱えたまま出荷されている。

**対処可能だった指摘(分離により未反映。再設計時の入力)**:

| 指摘 | 対処案(未反映) |
|---|---|
| P1 prompt 送出前に item の**存在**を確認せよ(`add` の `true` は「拒否されなかった」に過ぎず、policy 不許可・subType 不一致・id 重複では追加されない) | `add` の後・`newPrompt` の前に `current-items` で item の存在を確認し、無ければ `Dropped(ADD_FAILED)`(不在確認と同じ呼び出しを逆向きに使う) |
| P1 F3 の送出手順に入る時点で、既存の `messagesAwaitingReady`(フォーカス待ちキュー)の払い出しを止めよ | バリアを張る前にキューの前方にある追跡なしメッセージが送られると 1 の競合を広げる。F3 の tracked をキューから取り出した時点でバリアを張り、以降の払い出しを止める |
| P2 旧 session の `chat_terminal_context` 更新を捨てよ | `TerminalContextStateService.update(change, session)` の入口で `currentSnapshot?.session` と照合し、異なれば捨てる(F1 の round 7 P2 と同じ形。§9.1 手順 1) |
| P1 `UNRESOLVED` 後の `current-items` ポーリング(200 ms 間隔)に上限またはバックオフを | 絶対期限後は間隔を指数的に延ばし、上限回数で停止(run・バリアは保持。接続が替われば解放) |

### A.5 次サイクルの選択肢(トレードオフのみ。推奨なし)

| 案 | 内容 | 利点 | 代償 |
|---|---|---|---|
| (i) `newPrompt.fileContext` で送る | terminal のテキストを共有ストアに入れず、`newPrompt` の `fileContext`(`explainTerminalOutput` は `fileContextOptional`、A.1)に載せて prompt と一緒に届ける | 共有状態がなく、A.4 の 1・2 と `remove` / `current-items` / バリア / 絶対期限が全て不要になる | VSCode の契約と異なる(`fileContext` はエディタのファイル文脈の形で、terminal 用の表示・ラベルになるかは要確認)。`include_terminal_context` policy の強制がクライアント側ゲートだけになる(LS が `fileContext` に terminal の policy を適用するかは要確認) |
| (ii) 共有文脈のまま、競合を既知の制限として文書化 | round 6 までの設計を維持し、A.4 の 1・2 を「VSCode と同等の既知の制限」として台帳・PR に明記する | VSCode と同じ契約・同じ挙動。round 6 の設計をそのまま実装できる | 競合は残る(他の classic prompt と重なると文脈が消える/混入する)。バリア・絶対期限・未解決時の LS 再起動案内という重い機構を、確率的にしか効かない対策のために抱える |
| (iii) LS 側の変更を待つ/依頼する | 「prompt 処理完了」の通知、または prompt ごとの文脈関連付け(`newPrompt` に item を同梱する等)を LS / webview に求める | 根本解決。クライアントは完了通知で解決を確定できる | 本リポジトリの外(gitlab-lsp)の変更に依存し、時期を制御できない。同梱 LS のバージョン更新が必要 |
| 8 | P1 プロジェクト作成後に locationURI を取得せよ | 採用。ID の生成だけを先行させ、`create` の後にロケーションを取得してから記録する順序に変更。未作成ハンドルで `null` を返す fake で試験 | §9.2 記録の順序・分岐表, `DuoTutorialWorkspaceWriterTest` |
| 8 | P1 所有設定を明示的に永続化せよ | 採用。`setValue` の後に `ScopedPreferenceStore.save()`(前例 `PublishRecordStore.kt:42-54`)。保存失敗は直前の値へ戻し、作ったプロジェクトを補償削除して `Failed`。保存後に新しいストアから読める試験を追加。異常終了時の残留は安全側の拒否 + 手動削除として §27 に記載 | §9.2, §27, `DuoTutorialWorkspaceWriterTest` |
| 8 | P1 Provider 更新を既存の早期 return より前に固定せよ | 採用。`delay` 直後、`:36-39` と `:42` の早期 return より前にプロバイダへ通知すると明記。再起動前後で同じ認証値が届く試験を追加 | §9.1 手順 1, A3b, `AuthenticationStateServiceProviderFeedTest` |
| 8 | P2 セッション確認と状態更新を原子的に | 採用(方式は「公開を接続に結び付ける」)。状態を `(session, value)` の 1 値で保持し、公開時に現在の接続と照合。照合通過後・書き込み前に `reset()` を挟む決定的な競合試験を追加 | §8.1, `AuthenticationSourceProviderTest` |
| 9 | P1 visibleWhen の false をプロバイダの型に合わせよ | 採用(実ソースで確認: `Expressions.convertArgument` は引用符なしの `true` / `false` を `Boolean` に変換)。内部は 3 値のまま、公開するソース変数を `Boolean` の `gitlab_sign_in_required`(現在の接続で未認証と確定したときだけ `true`)に変更し、`<equals value="true"/>` で比較。既存の `duo_chat_enabled` と同じ形。plugin.xml と同じ式を core expression として評価する試験を追加 | §8.1, §11.2, §11.3, A3, `AuthenticationSourceProviderTest` |
| 9 | P1 セッション確認と debounce の差替えを直列化せよ | 採用。入口照合・既存 Job の取消・新 Job の投入を 1 つのロックで一続きにし、debounce 後にも再照合。照合通過後・取消前に新通知を挟む決定的な試験を追加 | §9.1 手順 1, `AuthenticationStateServiceProviderFeedTest` |
| 9 | P1 所有記録の順序テストを作成後へ修正せよ | 採用。Planner の試験から「記録が作成より前」を削除し、Writer の試験で「`CreateProject` → ロケーション取得 → 保存」の順を検証 | §23 |
| 9 | P1 authentication-required 不在時の期待値を統一せよ | 採用。試験を §8.1 の規則に合わせ、null / `invalid-token` のみ / 関連 check なしの 3 ケースを区別 | §23 |
| 9 | P2 キャンセル補償には未キャンセルの monitor を | 採用。補償の削除は `NullProgressMonitor` で行うと明記し、キャンセル済み monitor で失敗する fake で削除完了を検証 | §9.2, `DuoTutorialWorkspaceWriterTest` |
