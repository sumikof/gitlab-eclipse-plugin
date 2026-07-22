# Phase 1: Duo Chat 完全化 設計書

- 対象 issue: #9(Phase 1)/ ロードマップ: #8 / パリティ台帳: #7
- ベースブランチ: `develop`(@ `07d0c1a`)
- 作成日: 2026-07-22
- ステータス: レビュー用ドラフト(実装未着手)
- 改訂: 2026-07-22 rev.2 — Codex レビュー(PR #15)の P1 指摘 4 件に対応。反映内容は §28。

## 1. 背景と目的

本プラグイン(GitLab for Eclipse)は VSCode 拡張 `gitlab-workflow` v6.85.3 との機能パリティを目標としている(#7 時点で 18/85)。Duo Chat は既に主要機能が実装済み(チャット表示、Explain/Tests/Refactor/Fix、コードスニペット挿入、メッセージコピー)だが、以下が欠けている。

- **`/include` スラッシュコマンドによるコンテキスト添付が動作しない。** ユーザーが任意のファイル・スニペット等をチャットのコンテキストに追加できない。
- チャットのライフサイクル操作(閉じる/新規会話/フォーカス)に対応する専用コマンドがない。
- Duo Chat の有効化トグル、および GitLab プロジェクト非紐付け時に Duo を有効化する設定がない。

本フェーズの目的は、Duo Chat の利用体験を VSCode 版と同等にすることである。

## 2. 対象範囲

| # | 項目 | #7 該当行 | 現状 |
|---|---|---|---|
| F1 | `/include` を含む AI コンテキスト機能の有効化(エディタ選択範囲の提供) | 台帳外(webview 内部機能) | 未配線 |
| F2 | チャットを閉じる専用コマンド | D3 `gl.closeChat` | ❌ |
| F3 | 新規会話開始コマンド | D3 `gl.newChatConversation` | ❌ |
| F4 | チャットにフォーカス(単独コマンド化) | D3 `gl.focusChat` | 🟡 |
| F5 | 設定: Duo Chat 有効化トグル | D3 `gitlab.duoChat.enabled` | ❌ |
| F6 | 設定: GitLab プロジェクト非紐付け時も Duo 有効 | D2 `gitlab.duo.enabledWithoutGitlabProject` | ❌ |
| F7 | スラッシュコマンド網羅検証(コード変更なし) | 台帳外 | 未検証 |

## 3. 対象外

- ターミナル出力の説明(`gl.webview.explainSelectedTerminalOutput`)— Eclipse ターミナル API 連携が別課題。Phase 6(#14)。
- Duo チュートリアル(`gl.duoTutorial`)— Phase 6(#14)。
- Quick Chat、Agentic Chat、Knowledge Graph、Flow Builder、MCP webview — Phase 6(#14)。
- `$/gitlab/ai-context/git-commit-contents` の実装 — LS 8.80.0 のバンドル内に定数定義のみ存在し**呼び出し箇所が存在しない**ことを確認済み(§20 検証記録)。将来 LS 側が使い始めた時点で対応。
- gitlab-lsp のバージョンアップ(8.80.0 を維持。判断根拠は §6)。
- ディレクトリ構成・ビルドシステムの変更(#8 共通制約)。

## 4. 現在の課題

`/include` が機能しない直接原因は、**LS が必要とする逆リクエスト `$/gitlab/ai-context/editor-selection` にクライアントが応答していない**ことである。

現状 `GitLabLanguageServerClient` が実装している `$/gitlab/ai-context/*` は `git-diff` のみ(`src/main/kotlin/com/gitlab/eclipse/lsp/GitLabLanguageServerClient.kt:43`)。LS 8.80.0 の `DefaultEditorSelectionContextProvider` は `$/gitlab/ai-context/editor-selection` をクライアントへ送信し、応答が得られないと当該プロバイダのコンテキスト項目を返せない。

なお `/include` のファイル検索・項目管理・カテゴリ列挙は **LS 内部で完結**しており(§7)、クライアント側に検索実装は不要である。

## 5. 要件

### 機能要件

- **R1**: LS からの `$/gitlab/ai-context/editor-selection` リクエストに対し、アクティブなテキストエディタの選択範囲(ファイル名・選択テキスト)を返す。エディタ未オープン・選択なし・取得失敗時は `null` を返し、例外を LS に伝播させない。
- **R2**: LS へ送信する `workspaceFolders` が、`/include` のファイル検索起点として妥当であることを確認する(現状 Eclipse の全オープンプロジェクトの `locationURI` を送信している。§9)。
- **R3**: 「チャットを閉じる」コマンドは Duo Chat ビューを非表示にする。webview へのメッセージ送信は行わない。
- **R4**: 「新規会話」コマンドは Duo Chat ビューを表示し、webview へ `newPrompt` 通知(`prompt = "newConversation"`)を送る。`fileContext` は**選択範囲がある場合のみ**付与し、それ以外は `null` とする(詳細な場合分けは §9.2.1、根拠は §27.4)。
- **R5**: 「チャットにフォーカス」コマンドは Duo Chat ビューを表示し、webview へ `newPrompt` 通知(`prompt = "focusChat"`、`fileContext` なし)を送る。
- **R6**: 設定 `gitlab.duoChat.enabled`(既定 `true`)を追加し、LS へ `settings.duoChat.enabled` として送信する。
- **R7**: 設定 `gitlab.duo.enabledWithoutGitlabProject`(既定 `false`)を追加し、LS へ `settings.duo.enabledWithoutGitlabProject` として送信する。
- **R8**: 上記コマンドはコマンドパレット/メニューから実行でき、Duo Chat 無効時は表示されない(既存 `duo_chat_enabled` 変数による `visibleWhen` を踏襲)。

### 非機能要件

- **R9**: 逆リクエストの応答は、**クライアント側で定めた期限内に必ず返る**(UI スレッドの状態に関わらずハングしない。§14)。また、選択範囲の提供にあたり**用途上不要なデータ(ファイル全文)を送信しない**。なお選択テキスト自体はサイズ上限を設けない(判断根拠は §14.2)。
- **R10**: 既存の Code Suggestions / Duo Chat 機能に回帰を起こさない。
- **R11**: POJO 層(データクラス、コンテキスト変換)は単体テストを持つ。

## 6. 前提条件と制約

- **LS バージョンは 8.80.0 を維持**(`package.json` の pin)。`/include` に必要なプロトコルは 8.80.0 に全て存在することを確認済み(§20)。VSCode 拡張 v6.85.3 は LS 9.3.0 を使うが、AI コンテキストのエンドポイント定義は 8.80.0 と 9.3.0 で同一であることを確認済み。
- **ディレクトリ構成・ビルドシステムを変更しない。** 新規クラスは既存パッケージ体系(`com.gitlab.eclipse.*`)内に追加する。
- webview の HTML/JS は **LS がホストしており、本プラグインは変更できない**。したがってスラッシュコマンドのメニュー UI・`/include` の項目選択 UI は LS 側の実装をそのまま利用する。
- `/include` で選択可能なカテゴリは、接続先 GitLab インスタンスの機能フラグおよび Duo ライセンスに依存する(§7)。クライアント側で有効化する手段はない。
- devcontainer は headless のため、SWT/Browser 依存の動作確認は実機で行う(§18)。

## 7. システム構成

### 7.1 現行のメッセージ経路(調査で確定)

```
[webview (LS がHTTPでホスト)]
        ⇅  (LS 内部)
[gitlab-lsp プロセス]  ── stdio JSON-RPC ──  [Eclipse プラグイン]
```

- Eclipse は `$/gitlab/webview-metadata` で得た URI を SWT `Browser` に表示するだけ(`views/LanguageServerBrowserView.kt:70-85`、`id == "duo-chat-v2"` で絞り込み:75)。
- webview → Eclipse: LS が `$/gitlab/plugin/notification` / `$/gitlab/plugin/request` を送出 → `GitLabLanguageServerClient`(:87, :99)→ `PluginMessageService.dispatch` → `GitLabDuoChatWebViewController` の `@PluginNotification` / `@PluginRequest`。
- Eclipse → webview: `GitLabDuoChatWebViewClient.notify(type, payload)` が `ExtensionToPluginNotification(pluginId = "duo-chat-v2", type, payload)` を `$/gitlab/plugin/notification` で送出。

### 7.2 `/include` のデータフロー(LS 8.80.0 実装を確認)

`/include` に関わる JSON-RPC(`AIContextEndpoints`、LS → 実装は LS 内部):

| 定数 | メソッド文字列 | request | response |
|---|---|---|---|
| QUERY | `$/gitlab/ai-context/query` | `DuoChatAIRequest` | `AIContextItem[]` |
| ADD | `$/gitlab/ai-context/add` | `AIContextItem` | `boolean` |
| REMOVE | `$/gitlab/ai-context/remove` | `AIContextItem` | `boolean` |
| CURRENT_ITEMS | `$/gitlab/ai-context/current-items` | `undefined` | `AIContextItem[]` |
| RETRIEVE | `$/gitlab/ai-context/retrieve` | `undefined` | `AIContextItem[]` |
| GET_PROVIDER_CATEGORIES | `$/gitlab/ai-context/get-provider-categories` | `undefined` | `AIContextCategory[]` |
| CLEAR | `$/gitlab/ai-context/clear` | `undefined` | `boolean` |
| GET_ITEM_CONTENT | `$/gitlab/ai-context/get-item-content` | `AIContextItem` | `AIContextItem` |

**重要**: 上表は VSCode 拡張が「拡張ホストから LS へ」呼ぶための API である。**duo-chat-v2 webview は LS 内部で直接これらのプロバイダを呼ぶため、Eclipse 側の実装は不要**(LS バンドル内に `contextCategoriesResult` / `contextCurrentItemsResult` / `contextItemSearchResult` の送出処理が存在することを確認済み。§20)。

クライアント(エディタ)が応答すべき逆リクエスト(`AiContextEditorRequests`):

| 定数 | メソッド文字列 | 状態 |
|---|---|---|
| GIT_DIFF | `$/gitlab/ai-context/git-diff` | ✅ 実装済み(`GitLabLanguageServerClient.kt:43`) |
| GIT_COMMIT_CONTENTS | `$/gitlab/ai-context/git-commit-contents` | 定数のみ・LS 8.80.0 に呼び出し箇所なし → 対象外 |
| **EDITOR_SELECTION** | **`$/gitlab/ai-context/editor-selection`** | **❌ 未実装 → 本フェーズで実装(F1)** |

`/include` 実行時の流れ:

1. ユーザーが webview で `/include` を入力 → LS がカテゴリ一覧(`getAvailableCategories()`)を webview に返す。
2. ユーザーがカテゴリ・検索語を指定 → LS が内部プロバイダで検索(ファイル検索は LS 同梱の ripgrep が `workspaceFolders` を起点に実行)。
3. `file` カテゴリのうち「Editor Selection」項目は `DefaultEditorSelectionContextProvider` が担当し、クライアントへ `$/gitlab/ai-context/editor-selection` を送信する。**ここが本フェーズの実装点。**
4. 選択された項目は LS がチャット送信時に `additionalContext` として GitLab AI API へ添付する。

### 7.3 カテゴリ有効性

`AIContextCategory`(LS 8.80.0)= `file` / `snippet` / `terminal` / `issue` / `merge_request` / `dependency` / `local_git` / `user_rule` / `repository` / `directory`。

利用可否は LS が `getAvailableCategories()` の結果(空配列なら無効)で判断し、これはインスタンス側の機能フラグ・ライセンス(`DuoFeature.IncludeSnippetContext` 等)に依存する。**クライアント capability や設定キーによる有効化手段は存在しない**(VSCode 拡張も同様で、専用のフラグを送っていないことを確認済み)。

## 8. コンポーネントの責務

| コンポーネント | 種別 | 責務 |
|---|---|---|
| `GitLabLanguageServerClient` | 既存・拡張 | `$/gitlab/ai-context/editor-selection` の受信と応答(F1) |
| `EditorSelectionContextProvider`(新規) | 新規クラス | アクティブエディタから「ファイル名 + 選択テキスト」を取得する。UI スレッドへのホップを内包 |
| `EditorSelectionContext`(新規) | 新規 data class | 逆リクエストの応答 DTO(`fileName`, `selectedText`) |
| `CloseDuoChatCommandHandler`(新規) | 新規ハンドラ | Duo Chat ビューを非表示(F2) |
| `NewChatConversationCommandHandler`(新規) | 新規ハンドラ | `newPrompt`/`newConversation` 送信(F3) |
| `FocusChatCommandHandler`(新規) | 新規ハンドラ | `newPrompt`/`focusChat` 送信(F4) |
| `DuoChatWindow.kt` | 既存・拡張 | `closeDuoChatWindow()` を追加。既存 `openDuoChatWindow()` は変更しない |
| `GitLabLanguageServerConfigurationParams` | 既存・拡張 | `duoChat` / `duo` ネストを追加(F5/F6) |
| `GitLabLanguageServerConfigurationService` | 既存・拡張 | 上記の値を preference から読み出して送信 |
| `PreferenceConstants` / `PreferenceInitializer` / `GitLabPreferencePage` | 既存・拡張 | 設定キー・既定値・UI(F5/F6) |
| `plugin.xml` | 既存・拡張 | コマンド宣言・ハンドラ束縛・メニュー登録 |

## 9. 処理フロー

### 9.1 F1: エディタ選択範囲の提供

```
LS ──"$/gitlab/ai-context/editor-selection"(パラメータなし)──▶ GitLabLanguageServerClient
                                                                       │ (非UIスレッド)
                                                                       ▼
                                                      EditorSelectionContextProvider.provide()
                                                                       │ asyncExec + 期限付き待機(§14.1)
                                                                       ▼ (UIスレッド)
                                              PlatformUtils.getActiveTextEditor()
                                                → IFile 取得 → ITextSelection 取得
                                                       │
                          ┌────────────────────────────┴───────────────────────────┐
                          ▼ 取得成功かつ選択テキストが非空                            ▼ それ以外/例外
        EditorSelectionContext(fileName, selectedText)                            null
                          └────────────────────────────┬───────────────────────────┘
                                                        ▼
LS ◀────────────── CompletableFuture<EditorSelectionContext?> ──────────────────────┘
```

LS 側の消費フィールドは `selectedText` と `fileName` のみであることをバンドル実装で確認済み(§27.1)。したがって応答 DTO はこの 2 フィールドのみとし、**ファイル全文(`contentAboveCursor` / `contentBelowCursor`)は送らない**(R9)。

既存 `FileContext`(`lsp/NewPromptRequest.kt`)を再利用しない理由: 同型は `newPrompt` 用でファイル全文を含み、選択範囲提供の用途では不要なデータを毎回転送することになるため。`fileName` は既存 `CurrentFileContextProvider` と同じくワークスペース相対パス(`IFile.relativePath`)を用いる。LS はこれを `relativePath` / `secondaryText` として表示に使う。

UI スレッドの待機は**クライアント側の期限で必ず打ち切る**(§14.1)。上図の `syncCall` は概念上の同期取得であり、実装は `asyncExec` + `CompletableFuture` + 期限付き完了とする。

### 9.2 F2/F3/F4: コマンド

- **閉じる**: `page.hideView(view)`。VSCode 版は `workbench.action.closeSidebar` を実行するのみで webview へのメッセージ送信はないため、これに揃える。
- **新規会話**: `openDuoChatWindow()` 相当(ビュー表示)→ `notify("newPrompt", NewPromptRequest(prompt = "newConversation", fileContext = <§9.2.1 で確定>))`。
- **フォーカス**: `openDuoChatWindow()`(既存実装がまさに `newPrompt`/`focusChat` を送っているため、単独コマンドから呼ぶだけ)。

### 9.2.1 「新規会話」の `fileContext` 仕様(確定)

VSCode 拡張の `getActiveFileContext()` は `editor.selection.isEmpty` なら `null` を返し、さらに `getFileContext()` が `if (!selectedText || !fileName) return undefined` で弾く。すなわち **VSCode 版は「選択がなければ `fileContext` を送らない」**(§27.4 で実ソース確認)。これに合わせて以下を確定仕様とする。

| 状態 | 送信する `fileContext` | 備考 |
|---|---|---|
| アクティブなテキストエディタなし | `null` | コマンド自体は実行可(ビュー表示 + `newConversation` 送信は行う) |
| エディタあり・選択テキストが**空** | `null` | **ファイル全文を送らない**(重要。下記の落とし穴を参照) |
| エディタあり・選択テキストが非空 | `FileContext(fileName, selectedText, contentAboveCursor, contentBelowCursor)` | 既存 `explainCode` 等と同一形式 |
| エディタあり・`IFile` に adapt 不可(外部ファイル等) | `null` | 既存 `CurrentFileContextProvider` の判断に準拠 |

**実装上の落とし穴(必ず回避すること)**: 既存 `CurrentFileContextProvider.provide()` は**選択が空でも `FileContext` を返す**。その場合 `selectedText = ""` となる一方、`contentAboveCursor` にカーソル位置までの全文、`contentBelowCursor` に以降の全文が入り、**結果としてファイル全文が送信される**。既存の `ChatCommandHandler` は `isEnabled()` が選択非空を要求するためこの経路に到達しないが、「新規会話」コマンドが無条件に `provide()` を呼ぶと到達する。したがって**新規会話コマンドは `provide()` の戻り値の `selectedText` が空なら `fileContext = null` に落とすこと**。

「新規会話」コマンドは選択の有無に関わらず**実行可能**とする(`isEnabled()` で選択を要求しない)。会話のリセットは選択と無関係な操作であるため。

### 9.3 F5/F6: 設定

```
GitLabPreferencePage(BooleanFieldEditor) ──performOk()──▶ sendConfiguration()
                                                              ▼
     GitLabLanguageServerConfigurationParams(duoChat=..., duo=...)
                                                              ▼
                              workspace/didChangeConfiguration ──▶ LS
                                                              ▼
                                LS が featureStateChange を通知 ──▶ DuoChatStateService
                                                              ▼
                                               UI(メニュー可視性)へ反映
```

既存 `performOk()` が `sendConfiguration()` を呼ぶ経路をそのまま利用するため、新規の伝搬機構は不要。

## 10. API / インターフェース

### 10.1 新規: 逆リクエストハンドラ

```kotlin
// GitLabLanguageServerClient に追加(既存 getGitDiff の隣)
@JsonRequest("$/gitlab/ai-context/editor-selection")
fun getEditorSelection(): CompletableFuture<EditorSelectionContext?>
```

- **パラメータなし**: LS は `sendRequest(EDITOR_SELECTION, void 0)` で呼び出す(§20 で確認)。lsp4j がパラメータ無し(`params` が `null` または省略)のリクエストを 0 引数メソッドへディスパッチできることは、実装時に実接続で検証する(§19 未決事項 U1)。
- 応答型:

```kotlin
// com.gitlab.eclipse.lsp.messages パッケージ(既存 GitDiffParams と同じ場所)
data class EditorSelectionContext(
  val fileName: String,
  val selectedText: String
)
```

Gson はフィールド名をそのまま出力する(本プラグインの LSP メッセージ型に `@SerializedName` や命名ポリシーは設定されていない)。LS 側の期待キーは `fileName` / `selectedText` であり一致する。

### 10.2 既存: webview 通知(変更なし・利用のみ)

```kotlin
GitLabDuoChatWebViewClient.notify("newPrompt", NewPromptRequest(prompt, fileContext))
```

`prompt` に使う文字列: `"newConversation"`(F3)、`"focusChat"`(F4)。VSCode 拡張が送る値と同一。

### 10.3 設定パラメータ拡張

```kotlin
data class GitLabLanguageServerConfigurationParams(
  // ... 既存フィールドは変更しない ...
  val duoChat: DuoChat? = null,
  val duo: Duo? = null,
) {
  data class DuoChat(val enabled: Boolean)
  data class Duo(val enabledWithoutGitlabProject: Boolean)
}
```

送信 JSON(`settings` 直下):`{"duoChat":{"enabled":true},"duo":{"enabledWithoutGitlabProject":false}}`。VSCode 拡張が送るキーパスと一致することを確認済み(`settings.duoChat.enabled` / `settings.duo.enabledWithoutGitlabProject`)。**`enabledWithoutGitlabProject` は "Gitlab" の l が小文字**である点に注意(VSCode 側の設定キーも同綴り)。

### 10.4 設定キー

| 定数名 | キー文字列 | 既定値 |
|---|---|---|
| `DUO_CHAT_ENABLED` | `gitlab.duoChat.enabled` | `true` |
| `DUO_ENABLED_WITHOUT_GITLAB_PROJECT` | `gitlab.duo.enabledWithoutGitlabProject` | `false` |

既定値の根拠: VSCode 拡張の `getDuoChatConfiguration()` は `enabled` の既定を `true` としている。`enabledWithoutGitlabProject` は VSCode 側 `package.json` の既定に合わせ `false`(実装時に再確認 — §19 U3)。

### 10.5 コマンド ID

| 機能 | コマンド ID | キーバインド |
|---|---|---|
| F2 閉じる | `gitlab-eclipse-plugin.commands.CloseDuoChat` | なし |
| F3 新規会話 | `gitlab-eclipse-plugin.commands.NewChatConversation` | なし |
| F4 フォーカス | `gitlab-eclipse-plugin.commands.FocusDuoChat` | なし |

既存の `OpenDuoChat`(`M3+D`)と競合するキーバインドは割り当てない。3 件とも `<extension point="org.eclipse.ui.commands">` に**明示的に `<command>` を宣言**する(既存の `ExplainCode` 等は宣言が省略されており、キーバインド付与やカテゴリ表示ができない状態にある。新規分では同じ轍を踏まない)。

## 11. データモデル

新規の永続データはない。追加されるのは以下のみ。

- Eclipse preference store の 2 キー(`boolean`、§10.4)。スコープ・保存形式は既存設定と同一(`ScopedPreferenceStore`)。
- 揮発的な DTO `EditorSelectionContext`(§10.1)。

AI コンテキストの選択項目(`AIContextItem` のリスト)は **LS プロセス内のメモリに保持**され、Eclipse 側は保持しない。したがって LS 再起動で選択項目は失われる(VSCode 版と同じ挙動)。

## 12. トランザクション境界

データベースや複数ステップの永続化を伴う処理はないため、狭義のトランザクションは存在しない。整合性の境界は以下:

- **設定変更**: `performOk()` 内で「preference store への保存」→「`sendConfiguration()` による LS への全設定送信」が順に行われる。設定は差分ではなく**全量送信**であるため、部分適用による不整合は生じない。送信失敗時は LS 側が旧設定のまま動作する(§13)。
- **AI コンテキスト選択**: 追加/削除/クリアは LS 内部の状態遷移であり、Eclipse は関与しない。

## 13. エラー処理

| 事象 | 挙動 | 根拠 |
|---|---|---|
| `editor-selection` 時にアクティブエディタなし | `null` を返す | LS は `!e \|\| !e.selectedText` で空配列を返す実装(§20) |
| `editor-selection` 時に選択テキストが空 | `null` を返す | 同上。LS 側で「選択なし」として扱われる |
| エディタが `IFile` に adapt できない(外部ファイル等) | `null` を返す | 既存 `CurrentFileContextProvider` と同じ判断 |
| `editor-selection` 処理中の例外 | ログ出力の上 `null` を返す。例外を LS に伝播させない | 既存 `getGitDiff` が同じ方針(`catch (e: Throwable)` → `warn` → `null`) |
| ビュー非表示時に「閉じる」実行 | 何もしない(`findView` が `null`) | — |
| Duo Chat ビュー未生成時に「新規会話」実行 | ビューを表示してから通知(既存 `openDuoChatWindow` と同じ) | — |
| `sendConfiguration()` 送信失敗 | 既存挙動を踏襲(coroutine 内で送信、LS が `null` なら無送信) | 本フェーズで変更しない |

## 14. タイムアウトとリトライ

### 14.1 `editor-selection` のクライアント側期限(必須)

**`Display.syncCall` / `syncExec` による無期限待機は採用しない。** UI スレッドがモーダルダイアログ表示中・長時間処理中・workbench 終了処理中の場合、待機が無期限になり、LS 側でリクエストが失効した後もハンドラスレッドが滞留する。

仕様:

- クライアント側の期限を **2 秒**とする。期限内に UI スレッドから結果を得られなければ **`null` を返す**(LS 側は `null` を「選択なし」として正常処理する。§27.1 で確認済み)。
- 実装は `currentDisplay.asyncExec { ... }` で UI スレッドに投入し、結果を `CompletableFuture` に載せて `orTimeout(2, TimeUnit.SECONDS)` で打ち切る(`streamingCompletionResponse` が既に `orTimeout` を使っている前例に倣う)。**`syncExec` / `syncCall` は使わない。**
- **late UI task の扱い**: 期限超過後に UI タスクが実行されて完了しても、対象の `CompletableFuture` は既に完了済みであるため結果は破棄される(`complete()` が `false` を返すだけ)。古い選択範囲が後から応答に混入することはなく、明示的な世代管理は不要。
- 期限超過は `logger.warn` に記録する(選択テキストの内容は出力しない。§18)。

2 秒の根拠: 処理内容は選択範囲の取得のみで I/O を伴わないため、正常時はミリ秒オーダーで完了する。2 秒を要する状況は UI スレッドが実質ブロックされている状況であり、待ち続けても結果は改善しない。LS 側の既定タイムアウトより十分短い想定だが、LS の実値は本設計では制御しないため、実機検証で応答が期限内に返っていることを確認する(§25 U6)。

### 14.2 選択テキストのサイズ上限を設けない判断

**上限・切り詰めは実装しない。** 根拠:

- **VSCode 版に上限が存在しない。** `getSelectedText()` は `document.getText(selectionRange)` をそのまま返し、`getActiveFileContext()` にもサイズ検査はない(§27.4 で実ソース確認)。上限を設けると「機能等価」から外れ、Eclipse 版でのみ添付が黙って欠落する。
- **既存経路と一貫しない。** 既存の `explainCode` / `fixCode` / `generateTests` / `refactorCode` は `FileContext` として**選択テキストに加えてファイル全文**を無制限に送っている。`editor-selection` にだけ上限を課すのは一貫性を欠く。
- R9 の意図は「用途上不要なデータ(ファイル全文)を送らない」ことであり、**ユーザーが明示的に選択したテキストは必要なデータ**である。両者は矛盾しない。

ただしペイロード肥大が現実の問題になりうることは認識しており、**リスク K7 として記録**する。上限が必要と判明した場合は、`editor-selection` 単独ではなく `FileContext` を含む全チャット送信経路に対して一貫した方針を定める(Phase 1 のスコープ外)。

### 14.3 リトライ

- リトライは行わない。`/include` はユーザー操作起点であり、失敗時はユーザーが再実行できる。
- LS 側のリクエストタイムアウト値は本設計では制御しない(LS 内部の既定に従う)。

## 15. 冪等性

- `editor-selection` は**参照のみ**の読み取り操作であり、副作用を持たないため冪等。同一状態に対して何度呼ばれても同じ応答を返す。
- 「フォーカス」「新規会話」コマンドは重複実行されても webview 側で同じ通知が再処理されるだけで、Eclipse 側に累積状態は生じない。ただし「新規会話」の重複実行は会話が複数回リセットされる可能性がある(webview 側の挙動。ユーザー起点操作のため許容)。
- 設定送信は全量送信のため冪等。

## 16. 並行処理

- **UI スレッド制約**: `PluginMessageService.dispatch` および lsp4j のリクエストハンドラは**非 UI スレッド**で動作する。`PlatformUI` / `ITextEditor` / `ITextSelection` へのアクセスは UI スレッド必須のため、`EditorSelectionContextProvider` 内で UI スレッドへホップする。ホップは **`asyncExec` + 期限付き `CompletableFuture`** で行い、`syncExec` / `syncCall` による無期限待機は使わない(理由と仕様は §14.1)。既存 `CodeFormatter` は `syncCall` を使っているが、あれは UI 起点(ユーザー操作コンテキスト)であるのに対し、本ハンドラは **LS 起点で UI スレッドの状態を前提にできない**ため、同じパターンを踏襲しない。
- **競合**: `editor-selection` の処理中にユーザーがエディタを切り替えた場合、応答は「UI タスク実行時点のアクティブエディタ」のものとなる。LS 側はこの応答を 1 回限りのスナップショットとして扱うため、不整合は生じない(古い選択範囲が添付され得るが、これは VSCode 版も同じ)。期限超過後に遅れて実行された UI タスクの結果は破棄される(§14.1)。
- **webview 送信キューの制約(既知の地雷)**: `GitLabDuoChatWebViewClient.notify()` は webview 非フォーカス時にメッセージを**キューへ退避**し、フォーカス取得時にフラッシュする。このため「閉じる」コマンドで webview へメッセージを送る設計にすると、閉じた後に送信されたメッセージが滞留する。本設計では**閉じる操作で webview へ送信しない**ことでこれを回避する(§9.2)。
- **設定送信の順序**: 既存の `sendConfiguration()` は `coroutineScope.launch { }` で送信しており、**複数回呼び出しの順序は保証されない**。全量送信のため部分適用による不整合は起きないが、古いスナップショットが後着すると LS 側の状態が preference store と食い違いうる。

  これは**既存の全設定送信経路が持つ構造的な問題**であり、Phase 1 の変更が持ち込むものではない。かつ Phase 1 で追加する 2 キーは **`GitLabPreferencePage.performOk()` 経由でのみ送信される**(モーダルダイアログの OK であり、ユーザー操作として直列化される)ため、本フェーズの機能が問題に到達することは実質ない。現実に到達しうるのは既存の Code Suggestions トグルコマンドの連打などである。

  修正は Code Suggestions / OAuth / LS 初期化を含む全経路に影響し、headless 環境では回帰検証ができないため、**Phase 1 のスコープ外とし #16 に切り出した**。本フェーズでは既存挙動を変更しない。

## 17. 認証と認可

- 本フェーズで認証機構の変更は行わない。既存の PAT / OAuth トークンが `sendConfiguration()` 経由で LS に渡される仕組みをそのまま使う。
- `/include` で参照できるコンテキスト(Issue、MR 等)の可視性は **GitLab インスタンス側で認可**される。Eclipse 側で追加のアクセス制御は行わない。
- `editor-selection` はローカルのエディタ内容(**選択範囲のテキストのみ**)を LS へ渡す。LS はこれをチャット送信時に GitLab AI API へ送る。選択がない場合は `null` を返すため、**ユーザーがテキストを選択していない状態でコード内容が送信されることはない**(§9.2.1)。
- 同様に「新規会話」コマンドも、選択がない場合は `fileContext` を送らない。既存 `CurrentFileContextProvider` を無条件に使うとファイル全文が送信される経路が存在するため、これを塞ぐことを明示的な要件としている(§9.2.1 の落とし穴、受け入れ条件 1-b)。
- なお、既存の `explainCode` / `fixCode` / `generateTests` / `refactorCode` は選択範囲に加えてファイル全文(`contentAboveCursor` / `contentBelowCursor`)を送る。これは既存の挙動であり VSCode 版と同一。本フェーズでは変更しない。
- 秘匿情報のマスキングは本フェーズの対象外(Code Suggestions の `enableSecretRedaction` は別経路)。設計書・ログに認証情報を記載しない。

## 18. ログ・監視・監査

- `editor-selection` の失敗時は既存 `logger.warn` パターンで記録する(`GitLabLanguageServerClient` の既存 logger を使用)。**選択テキストの内容そのものはログに出力しない**(機微情報の漏洩防止)。出力するのはファイル名と例外情報に留める。
- LS 側の詳細ログは既存の `gitlab.languageServer.logLevel` 設定(4 段階)で制御できる。障害調査時は `debug` に設定して LS のログを確認する。
- 専用の Output channel の新設は Phase 6(#14 の D10 項目)で扱う。本フェーズでは Eclipse 標準 Error Log を使う。
- テレメトリの新規追加は行わない。

## 19. 障害時の復旧方法

| 症状 | 想定原因 | 復旧手順 |
|---|---|---|
| `/include` でカテゴリが表示されない | インスタンス側の機能フラグ/ライセンス未有効、または認証切れ | 「Verify Setup」で接続を確認 → GitLab 管理者にインスタンス設定を確認 |
| `/include` で「Editor Selection」が出ない | 逆リクエストの応答失敗 | Error Log を確認。エディタを開いてテキストを選択した状態で再試行 |
| 設定変更が LS に反映されない | `sendConfiguration()` 未実行、または LS 未起動 | 設定画面で OK を再実行、または Eclipse 再起動(LS 再起動コマンドは Phase 2 で追加) |
| チャットが応答しない | LS プロセス異常 | Eclipse 再起動(現状 LS 単体の再起動手段がない) |

## 20. 既存機能への影響

| 既存機能 | 影響 | 評価 |
|---|---|---|
| Code Suggestions | なし | 変更するファイルが重複しない(設定パラメータ型のみ共有だが、既存フィールドは変更しない) |
| Duo Chat(既存の Explain/Fix/Tests/Refactor) | なし | `ChatCommandHandler` および `openDuoChatWindow()` を変更しない |
| `git-diff` 逆リクエスト | なし | 新規メソッドの追加のみ |
| 設定画面 | 項目が 2 つ増える | レイアウトへの影響は軽微。既存項目の位置は変更しない |
| `didChangeConfiguration` の送信内容 | フィールドが 2 つ増える | LS は未知フィールドを無視する設計であり、旧 LS との組み合わせでも安全 |

## 21. 移行方法

- データ移行は不要。新規 preference キーは `PreferenceInitializer` の既定値により、既存ユーザーの環境でも初回参照時に既定値が適用される。
- 既存ユーザーが設定を触らない限り、Duo Chat は従来どおり有効(`gitlab.duoChat.enabled` 既定 `true`)。
- LS のバージョン変更を伴わないため、LS バイナリの再取得は発生しない。

## 22. ロールバック方法

- 本フェーズは単一の実装 PR(または機能単位に分割した複数 PR)であり、`develop` へのマージ後に問題が判明した場合は **PR の revert** で戻せる。
- 部分ロールバックの単位: F1(逆リクエスト)、F2-F4(コマンド)、F5-F6(設定)は相互依存がないため、コミットを機能単位に分けることで個別 revert を可能にする。
- revert 後に残る副作用: preference store に書き込まれたキーは残るが、参照されなくなるだけで害はない。

## 23. テスト方針

### 単体テスト(headless で実行可能・TDD 対象)

- `EditorSelectionContext` の Gson シリアライズが `fileName` / `selectedText` を出力すること。
- `GitLabLanguageServerConfigurationParams` に `duoChat` / `duo` を設定した際の JSON キーパスが `duoChat.enabled` / `duo.enabledWithoutGitlabProject` になること。
- `EditorSelectionContextProvider` のロジック(エディタなし → `null`、`IFile` に adapt 不可 → `null`、選択空 → `null`、正常時 → 期待値)。UI 依存部はモック可能な形に切り出す。
- **UI スレッドが期限内に応答しない場合に `null` を返すこと**(UI タスクを意図的に遅延させ、期限超過を再現する)。
- **期限超過後に遅れて完了した UI タスクの結果が応答に反映されないこと**(late task の破棄)。
- **「新規会話」の `fileContext` 決定ロジック**の 4 ケース(§9.2.1 の表)。特に**選択空のときに `fileContext = null` となり、ファイル全文が含まれないこと**を明示的に検証する。
- `sendConfiguration()` が新規 preference キーを読み出して設定に反映すること。

### 手動検証(実機・ユーザー環境)

headless の devcontainer では SWT/Browser/LS 実接続を伴う検証ができないため、以下は手動検証手順として PR 説明文に記載し、ユーザー実機で確認する。

1. エディタでコードを選択 → Duo Chat で `/include` → 「Editor Selection」項目が表示され、選択できること。
2. `/include` でファイルを検索・添付し、その内容に基づいた回答が返ること。
3. `/explain` `/tests` `/refactor` `/fix` `/reset` `/clear` `/help` が webview 内で動作すること(F7)。
4. 「チャットを閉じる」でビューが閉じ、再度開いた際に会話が保持されていること。
5. 「新規会話」で会話がリセットされること。
6. 「フォーカス」でビューが前面に出て入力欄にフォーカスが移ること。
7. `gitlab.duoChat.enabled` を `false` にすると Duo Chat 関連メニューが非表示になること。
8. `gitlab.duo.enabledWithoutGitlabProject` を `true` にした状態で、GitLab プロジェクトに紐付かないワークスペースでも Duo が利用できること。

### 回帰確認

- 既存の Explain/Fix/Tests/Refactor が従来どおり動作すること。
- Code Suggestions が従来どおり動作すること。

## 24. 受け入れ条件

1. LS からの `$/gitlab/ai-context/editor-selection` に対し、選択範囲がある場合は `fileName` と `selectedText` を含む応答を返し、ない場合は `null` を返す(単体テストで検証)。
1-a. UI スレッドが 2 秒以内に応答しない場合、ハングせず `null` を返す。遅延して完了した UI タスクの結果は破棄される(単体テストで検証)。
1-b. 「新規会話」実行時、**選択が空またはエディタなしの場合に `fileContext` が `null`** であり、ファイル全文が送信されない(単体テストで検証)。
2. 実機で `/include` を実行するとカテゴリ一覧が表示され、「Editor Selection」を含む項目を添付でき、添付内容が回答に反映される(手動検証)。
3. 「閉じる」「新規会話」「フォーカス」の 3 コマンドがコマンドパレットおよびメニューから実行でき、期待どおり動作する(手動検証)。
4. 設定画面に 2 つのトグルが表示され、OK 押下で LS へ `settings.duoChat.enabled` / `settings.duo.enabledWithoutGitlabProject` が送信される(単体テスト + 手動検証)。
5. `./gradlew build` が成功し、既存テストが全て通る。
6. 既存の Duo Chat 機能・Code Suggestions に回帰がない(手動検証)。
7. #7 の台帳で D3 が ✅11 / ❌2(terminal, tutorial)/ 🟡0、D2 が ✅7 / ❌0 になる。

## 25. 未決事項

推測で確定させず、実装時に実接続または実ソースで確認する。

- **U1**: lsp4j が「パラメータなし」の `@JsonRequest` を 0 引数メソッドへ正しくディスパッチするか。LS は `sendRequest(EDITOR_SELECTION, void 0)` で呼ぶため `params` が省略または `null` になる。ディスパッチできない場合の代替案は「`Any?` を受ける 1 引数メソッドにする」。**実装タスク 1 の最初に実接続で確認する。**
- **U2**: `/include` のカテゴリが検証環境の GitLab インスタンスで有効になっているか。無効の場合、F1 の実機検証ができない。事前にユーザー環境で `/include` 実行時のカテゴリ表示を確認する必要がある。
- **U3**: `gitlab.duo.enabledWithoutGitlabProject` の既定値。VSCode 拡張の `package.json` の該当既定値を実装時に再確認して合わせる(本設計では `false` を仮置き)。
- **U4**: `workspaceFolders` として送信している `IProject.locationURI.toASCIIString()` が、LS のファイル検索(ripgrep)で期待どおり解決されるか。Windows のドライブレター付きパスや、リンクされたリソースを含む場合の挙動は未確認。実機検証で確認し、問題があれば別途対応する。
- ~~**U5**: 「新規会話」コマンドで `fileContext` を付けるべきか。~~ **解決済み**(Codex レビュー指摘 P1-1)。VSCode 実装を確認した結果「選択がなければ送らない」が正であることが確定したため、§9.2.1 に確定仕様として記載した。
- **U6**: LS 側の `editor-selection` リクエストタイムアウト値。クライアント期限(2 秒)が LS 側の期限より十分短いことを実機のログで確認する。LS 側の方が短い場合はクライアント期限を再調整する。

## 26. 想定されるリスク

| # | リスク | 影響度 | 対応 |
|---|---|---|---|
| K1 | `/include` のカテゴリがインスタンス側で無効で、実機検証が完了できない | 高 | 着手前にユーザー環境で `/include` の表示を確認(U2)。無効の場合は F1 の受け入れ条件を「逆リクエストへ正しく応答すること」までに限定し、E2E はインスタンス有効化後に持ち越す |
| K2 | UI スレッドのブロック(モーダルダイアログ等)により逆リクエストの応答が遅延・ハングする | 中 | **設計で解消**: `syncExec` を使わず `asyncExec` + 2 秒期限で必ず打ち切り `null` を返す(§14.1)。処理内容も選択範囲の取得のみに保つ |
| K3 | LS 8.80.0 と 9.3.0 の webview 実装差により、VSCode 版と挙動が異なる | 中 | パリティ判定は「機能等価」で行う(#7 の凡例に準拠)。差異が実用上の問題になる場合のみ LS バージョンアップを別 PR で検討 |
| K4 | lsp4j のパラメータなしリクエスト非対応(U1) | 中 | 代替案(1 引数メソッド)を用意済み。実装初手で確認するため手戻りは小さい |
| K5 | 新規コマンドのキーバインド未割り当てにより発見性が低い | 低 | メニューおよびコマンドパレットから到達可能にする。キーバインドはユーザーが Eclipse の設定で割り当て可能(明示的な `<command>` 宣言により可能になる) |
| K6 | 設定キーのネスト構造が LS の期待と異なり、設定が無視される | 中 | VSCode 拡張の送信キーパスと LS バンドル双方で確認済み。実機で LS ログ(debug)により受信内容を確認する |
| K7 | 巨大な選択範囲により JSON-RPC / LS / AI API のペイロードが肥大する | 低〜中 | 上限は設けない(§14.2 の判断)。VSCode 版・既存チャット経路と同条件であり本フェーズ固有の新規リスクではない。実機で問題が観測された場合は、`FileContext` を含む全チャット送信経路に対する一貫した方針として別途対応する |
| K8 | 設定通知の順序入れ替わりにより LS の状態が preference store と食い違う | 低 | 既存の構造的問題であり #16 に切り出し済み。Phase 1 の 2 キーは `performOk()` 経由のみのため実質到達しない(§16) |

## 27. 検証記録(本設計の根拠)

本設計の確定値は以下で検証した。

### 27.1 gitlab-lsp 8.80.0 / 9.3.0 のプロトコル定数

`@gitlab-org/gitlab-lsp` の npm tarball(8.80.0 = Eclipse 側 pin、9.3.0 = VSCode 拡張 v6.85.3 の pin)を取得し、型定義 `out/gitlab-lsp.d.ts` と実装バンドル `out/main-bundle-node.js` を確認。

- `AIContextEndpoints` の 8 メソッド文字列、`AiContextEditorRequests` の 3 メソッド文字列は**両バージョンで完全一致**。
- `AIContextCategory` の列挙値(10 種)を型定義で確認。
- `DefaultEditorSelectionContextProvider` の実装を確認し、**応答の消費フィールドが `selectedText` / `fileName` のみ**であること、`!e || !e.selectedText` で null / 空選択を許容することを確認。
- `$/gitlab/ai-context/git-commit-contents` は定数定義のみで**呼び出し箇所が存在しない**ことを確認。
- LS バンドル内に `contextCategoriesResult` / `contextCurrentItemsResult` / `contextItemSearchResult` の送出処理が存在し、**duo-chat-v2 webview 向けのコンテキスト UI は LS 内部で完結**することを確認。
- duo-chat-v2 の webview バンドル内に `"/include"` リテラルが存在し、スラッシュコマンドが webview 側実装であることを確認。

### 27.2 VSCode 拡張(v6.85.3)側の実装

`/workspace/out/gitlab-vscode-extension`(参照コピー・読み取りのみ)を確認。

- `gl.webview.closeChat` は `workbench.action.closeSidebar` を実行するのみで webview 送信なし。
- `gl.webview.newChatConversation` / `gl.webview.focusChat` は `newPrompt` 通知(`prompt` = `newConversation` / `focusChat`)を送る。
- 設定は `settings.duoChat.enabled` / `settings.duo.enabledWithoutGitlabProject` として `didChangeConfiguration` で送信される。
- AI コンテキストを有効化する専用の client capability / feature flag は送っていない。

### 27.3 VSCode 版のファイルコンテキスト取得(Codex レビュー P1-1 / P1-2 の判断根拠)

`src/common/chat/gitlab_chat_file_context.ts` および `src/common/chat/utils/editor_text_utils.ts` を確認。

- `getActiveSelectionRange()` は `editor.selection.isEmpty` の場合 `null` を返す。
- `getFileContext()` は `if (!selectedText || !fileName) return undefined` で弾く。
- したがって `getActiveFileContext()` は**選択が空なら `undefined`** を返し、これが `newPrompt` の `fileContext` と `editor-selection` 逆リクエストの応答の**両方**に使われる(`language_client_wrapper.ts:219` で `onRequest(EDITOR_SELECTION, getActiveFileContext)`)。
- `getSelectedText()` は `document.getText(selectionRange)` をそのまま返し、**サイズ上限・切り詰めの実装は存在しない**。

→ P1-1(新規会話の `fileContext` 条件)は「選択が空なら送らない」が VSCode 準拠として確定(§9.2.1)。P1-2(サイズ上限)は VSCode 版に存在しないため導入しない(§14.2)。

### 27.4 Eclipse プラグイン(develop @ 07d0c1a)側の現状

- `GitLabLanguageServerClient.kt:43` に `$/gitlab/ai-context/git-diff` のみ実装。他の ai-context メソッドは未実装。
- `GitLabLanguageServerConfigurationParams` の既存フィールドと Gson の命名(`@SerializedName` 不使用)を確認。
- `workspaceFolders` は `ResourcesPlugin.getWorkspace().root.projects` から `WorkspaceFolder(locationURI.toASCIIString(), name)` を生成。
- `openDuoChatWindow()` が既に `notify("newPrompt", NewPromptRequest(prompt = "focusChat"))` を送っている。
- `GitLabDuoChatWebViewClient` の非フォーカス時キューイング挙動を確認(§16 の設計判断根拠)。
- `plugin.xml` のコマンド/ハンドラ/バインディング/メニューの登録パターン、および `ExplainCode` 等が `<command>` 未宣言である事実を確認。

## 28. 改訂履歴

### rev.2(2026-07-22)— Codex レビュー(PR #15)P1 指摘への対応

| 指摘 | 判断 | 反映先 |
|---|---|---|
| **P1-1** 新規会話の `fileContext` 条件が §5 R4 と §25 U5 で矛盾 | **受け入れ・修正**。VSCode 実ソースを確認し「選択が空なら送らない」が正であることを確定 | §5 R4 / **§9.2.1(新設・確定仕様と実装上の落とし穴)** / §17 / §23 / 受け入れ条件 1-b / §25 U5 解決 / §27.3 |
| **P1-2** 選択テキストのサイズ上限と超過時の挙動が未定義 | **一部受け入れ・上限導入は見送り**。VSCode 版に上限が存在せず、既存チャット経路も無制限であるため、上限導入は機能等価性と一貫性を損なう。判断根拠を明文化しリスクとして記録 | **§14.2(新設・判断と根拠)** / §5 R9 / §26 K7 |
| **P1-3** UI スレッド待機を LS タイムアウトより前に打ち切るべき | **受け入れ・修正**。`syncExec`/`syncCall` による無期限待機を廃し、`asyncExec` + 2 秒期限 + late task 破棄に変更 | **§14.1(新設・期限仕様)** / §9.1 / §16 / §23 / 受け入れ条件 1-a / §25 U6 / §26 K2 |
| **P1-4** 設定通知の順序保証 | **受け入れ・ただし Phase 1 スコープ外**。既存の全設定送信経路が持つ構造的問題であり Phase 1 が持ち込む欠陥ではない。Phase 1 の 2 キーは `performOk()` 経由のみで実質到達しない。修正は Code Suggestions / OAuth を含む全経路に影響し headless で回帰検証不可のため **#16 に切り出し** | §16 / §26 K8 / issue #16 |
