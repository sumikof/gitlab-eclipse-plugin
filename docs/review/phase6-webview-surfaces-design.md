# Phase 6 優先 1: webview 面の開放 設計書

対象リポジトリ: `sumikof/gitlab-eclipse-plugin` / ベース: `develop` @ `47bb056`
参照実装: `gitlab-workflow` v6.85.3(`./out/gitlab-vscode-extension`・読み取り専用)
Language Server pin: **8.80.0**(`package.json:11`。逆アセンブル対象 `build/gitlab-lsp/bin/gitlab-lsp-linux-x64` の `installed_versions` も 8.80.0)

> 本書は設計レビュー専用であり、マージ先ブランチには含めない。

---

## 1. 背景と目的

VSCode 拡張は Language Server が広告する webview を複数の面(サイドバーのビュー / エディタ領域のタブ)にホストしている。本 Eclipse プラグインは #26(2026-07-24)でマルチ webview 対応を入れたが、対象を Duo Chat の 2 id に限定したままである。

限定の実体は `src/main/kotlin/com/gitlab/eclipse/chat/webview/ChatWebviewCatalog.kt:17` の

```kotlin
val CHAT_WEBVIEW_IDS = listOf(CLASSIC_WEBVIEW_ID, AGENTIC_WEBVIEW_ID)
```

と、同 `:24` の `.filter { it.id in CHAT_WEBVIEW_IDS }` である。

> **従来記述の訂正 1**: issue #8 / #14 にある「`LanguageServerBrowserView.kt:75` のフィルタをパラメータ化する」は不正確。`LanguageServerBrowserView.kt` に id フィルタは存在せず、絞り込みは上記 `ChatWebviewCatalog` にある。

本設計の目的は、**既存の Duo Chat 経路をまったく変更せずに**、LS が広告する他の webview をホストする面を新設し、パリティ台帳(#7)の 5 機能を閉じることである。

## 2. 対象範囲

| # | 台帳 | コマンド ID | webview id | 面 |
|---|---|---|---|---|
| 1 | D5 Agentic タブ表示 | `gl.webview.agenticTabs.show` | `agentic-tabs` | 新規 ViewPart |
| 2 | D8 MCP webview 表示 | `gl.webview.root.mcp.show` | `root/mcp` | 新規エディタ領域タブ |
| 3 | D7 Flow Builder を開く | `gl.openFlowBuilder` | `root/flow` | 同上 |
| 4 | D5 履歴ビュー表示 | `gl.agenticChat.showHistoryView` | `agentic-duo-chat`(既存) | 既存 Duo Chat ビュー |
| 5 | D5 新規会話開始 | `gl.agenticChat.startNewConversation` | `agentic-duo-chat`(既存) | 同上 |

コマンド ID は参照実装の導出規則(`src/common/webview/setup_webviews.ts:60-67` の `kebabToCamelCase` / `slashToDot`)に一致することを確認済み。4・5 は `src/common/command_names.ts:27-28` のリテラル。3 は `src/desktop/command_names.ts:75`。

## 3. 対象外

- **`security-vuln-details`(脆弱性詳細 webview)** — ユーザー判断により本サイクルから除外。理由は §3.1。
- `knowledge-graph` — LS が広告する webview ではなく参照実装のローカル実装(`src/common/webview/knowldege_graph/`)。Phase 6 優先 5。
- `root/duoAgentPlatform` / `duo-workflow-panel` — 台帳の対象機能に無い(§3.2)。
- 既存 Duo Chat(classic / agentic)の表示・切替・可用性判定の挙動変更。**§7.4 の intent 追加を除き、既存コードに変更を加えない。**

### 3.1 `security-vuln-details` を外した理由

参照実装では専用ツリービュー `src/desktop/tree_view/remote_security_scans_data_provider.ts` の項目クリックが `gl.webview.securityVulnDetails`(`.show` とは別コマンド)を起動し、パネル生成後に

```
$/gitlab/plugin/notification
  pluginId = "security-vuln-details"
  type     = "updateDetails"
  payload  = { vulnerability, filePath, timestamp }
```

を送る(`src/common/security_scans/open_vulns_details.ts:11-29`)。

本リポジトリの `src/main/kotlin/com/gitlab/eclipse/security/SecurityScanResponse.kt:20` は `results: List<Any?>?` を**意図的に untyped**とし、保持もしていない(Phase 5B は `textDocument/publishDiagnostics` → `IMarker` 経路を採った)。したがって `.show` だけを配線すると**空のパネルが開くだけ**になる。脆弱性オブジェクトの供給元(結果の型付け・保持・起動口)は独立した設計判断であり、Phase 5B の秘匿方針(`results` / `error` をログにも UI にも出さない)と正面から関わるため、本サイクルから分離する。

> **従来記述の訂正 2**: `security-vuln-details` は参照実装の `LS_WEBVIEW_IDS`(`src/common/constants.ts:26-34`)に**含まれない**。`LS_WEBVIEW_IDS` はメッセージバスの型ユニオン用(`src/common/webview/message_handlers/webview_message.ts:6`)であり、面の有無を決めているのは `setup_webviews.ts:37-48` の `PANEL_WEBVIEW_IDS` / `EDITOR_WEBVIEW_IDS` である。

### 3.2 `root/duoAgentPlatform` について

> **従来記述の訂正 3**: 参照実装は `root/duoAgentPlatform` を `setup_webviews.ts:30-34` の `CHAT_WEBVIEW_IDS` に含め、**3 つ目のチャット webview**として `LSDuoChatWebviewController` を付けている。本リポジトリの `CHAT_WEBVIEW_IDS` は 2 件のみ。台帳 #7 に対応行が無いため本サイクルの対象外とするが、**Duo Chat 切替の完全パリティを主張する場合は将来の対象になる**ことを記録する。

`duo-workflow-panel` は pin 8.80.0 の LS バイナリに文字列として存在しない(下記 §5 の実測)。

## 4. 現在の課題

1. LS が広告する webview のうち 2 id しかホストできない。
2. **エディタ領域に webview をホストする面が存在しない。** 現状の webview ホストはサイドバーの単一ビュー `LanguageServerBrowserView` のみ。
3. webview id → URI の解決ロジックが `LanguageServerBrowserView.refresh()`(`:89-132`)の中で、チャットの選択解決・可用性判定・`StackLayout` 操作と絡んだ状態にある。他の面から再利用できない。
4. Agentic webview に対するホスト → webview のプッシュ経路が無い(`AgenticChatWebViewController` の KDoc `:18-21` が「deferred slice」と明記)。

## 5. 前提条件と制約(実ソース・バイナリで確定した値)

### 5.1 プロトコル

| 項目 | 値 | 根拠 |
|---|---|---|
| メタデータ取得 | `$/gitlab/webview-metadata`(JsonRequest) | 自リポジトリ `lsp/GitLabLanguageServer.kt:69-70` / 参照実装 `src/common/language_server/language_server_manager.ts:245` |
| 応答型 | `List<WebviewInfo?>?`、`WebviewInfo(id, title, uris: List<String>)` | 自リポジトリ `lsp/WebviewInfo.kt:3` / 参照実装 `src/common/webview/webview_info_provider.ts:1-5` |
| ホスト → webview 通知 | `$/gitlab/plugin/notification`(JsonNotification) | 自リポジトリ `lsp/GitLabLanguageServer.kt:75-76` |
| 通知ペイロード | `ExtensionToPluginNotification(pluginId, type, payload)` | 自リポジトリ `lsp/plugins/messages/ExtensionToPluginNotification.kt:3-7` |
| `switchView` ペイロード | `{ view: "history" \| "newConversation" }` | 参照実装 `src/common/webview/duo_agentic_chat/duo_agentic_chat_commands.ts:12-25` |

### 5.2 LS 8.80.0 バイナリの実測(`grep -a` による文字列抽出)

| webview id | title | LS 内の `setup` |
|---|---|---|
| `agentic-tabs` | `GitLab Duo Agent Platform` | クラス実装あり |
| `root/mcp` | `MCP Dashboard` | no-op(`n3.add({id:"root/mcp", title:"MCP Dashboard", setup:w})`、`w = () => {}`) |
| `root/flow` | `Flow Builder` | 上記の no-op 登録に加え、別途 `onInstanceConnected` で `appReady` → `initialState{nodeTypeDefinitions, toolDefinitions}` と `onRequest("saveFlow")` を提供 |
| `root/duoAgentPlatform` | `Duo Agent Platform` | 同様に `appReady` → `initialState{repositories}` |
| `security-vuln-details` | `GitLab SAST Remote Scanner` | `onNotification("updateDetails", ...)` |
| `duo-workflow-panel` | — | **文字列として存在しない** |

**帰結: `root/mcp` と `root/flow` はホスト側のメッセージ処理を一切必要としない。** `root/flow` の保存は LS 内部の `saveFlow` が行うため、**ホスト側にファイル書き込みは無い。**

### 5.3 `switchView` の到達条件(本設計で最も重要な制約)

LS の agentic chat プラグインは、ホスト → webview の `switchView` 転送ハンドラを **webview からの `webviewReady` を受信した後にはじめて登録する**:

```
a.onNotification("webviewReady", async () => {
  n.onNotification("switchView", x => { a.sendNotification("switchView", x) });
  ...
});
```

(`a` = webview チャネル、`n` = 拡張機能チャネル)

**`webviewReady` より前にホストが送った `switchView` は、LS 側にハンドラが無いため捨てられる。** 「履歴を開く」はビューが閉じた状態から呼ばれるのが主要経路なので、この窓は例外ではなく常態である。

### 5.3a 送信順序 — **実測により確定(旧 U-1 は解消)**

webview アプリのアセットが LS に同梱されている(`build/gitlab-lsp/bin/webviews/agentic-duo-chat/assets/index.91120bee.js`)。送信側は以下である:

```js
notifyAppReady(){ this.sendNotification("appReady"), this.sendNotification("webviewReady") }
```

**`appReady` が先、`webviewReady` が後。** 両者は同一の webview → LS 接続上で隣接して送られる。

**帰結(本設計の中心)**

1. **ホストが `appReady` を観測した時点で、LS が `switchView` 転送ハンドラを登録済みである保証は無い。** ホストが観測するのは `appReady` の LS 経由の転送であり、`webviewReady` はホストからは観測できない。
2. 実務上の非対称は本設計に有利である: ホスト側の経路は `appReady` → LS → host → `switchView` → LS の**往復**であるのに対し、`webviewReady` は同一ソケット上で `appReady` の**直後**に送られる。したがって通常は `webviewReady` が先に LS に届く。
3. **しかしこれは競合であって保証ではない。** 順序を強制する仕組みは JSON-RPC にも LS 実装にも存在しない。**「通常は勝つ」を根拠に silent no-op を許容しない。**

この確定を受け、§7.4 は**故障モードを 2 つに分離**して扱う(旧設計は 1 つの機構で受けようとしており、その結果 `markReady()` がタイマまで解除して**取りこぼし時に無反応かつ無通知**になっていた)。

### 5.4 readiness 信号

| プラグイン | ホストへ転送される webview → host 通知 |
|---|---|
| classic (`duo-chat-v2`) | `focusChange` / `openLink` / `appReady` / ほか |
| **agentic (`agentic-duo-chat`)** | `openUrl` / **`appReady`** / `copyCodeSnippet` / `insertCodeSnippet` / `copyMessage` / `openFile` — **`focusChange` を含まない** |

**帰結 1**: `AgenticChatWebViewController.focusChange`(`:48-52`)は pin 8.80.0 では呼ばれないと考えられる。既存 `GitLabDuoChatWebViewClient` と同じ focus ゲートは agentic では原理的に開かない。
**帰結 2**: agentic に対してホストが観測できる唯一の readiness 信号は **`appReady`** である。現在 `AgenticChatWebViewController.appReady()` は `= Unit`(`:42-43`)。**ただし §5.3a のとおり、これは「LS が `switchView` を受け付けられる」ことを意味しない。**

### 5.4a `agentic-tabs` のホスト向け経路 — **実測により確定(read-only)**

LS 8.80.0 の `agentic-tabs` プラグイン実装は以下がすべてである:

```js
var kz = class {
  id = "agentic-tabs"; title = "GitLab Duo Agent Platform";
  setup(e){ let { webview: r } = e; r.onInstanceConnected(() => { this.#e.debug("Duo Tabs created") }) }
};   // DefaultAgenticTabsWebviewPlugin
```

**`onNotification` / `onRequest` の登録は 1 つも無く、拡張機能チャネル(host)への転送も存在しない。** 参照実装側も `setup_webviews.ts:152-158` で素の `LsWebviewController` を割り当てるのみで、`registerDuoChatHandlers` は呼ばない(呼ぶのは `CHAT_WEBVIEW_IDS` のみ)。

**帰結**: `agentic-tabs` は**ホスト側のメッセージ経路を必要としない面**である。「未確認だが未登録なら warn で無視されるので安全」という消極的な根拠ではなく、**LS と参照実装の両方で経路が存在しないことを確認した**うえで read-only 面として設計する。したがって本面に host handler・認可・入力検証の設計要素は無い(webview 内の操作はすべて LS が直接処理する)。

### 5.5 環境・ビルド制約

- **ディレクトリ構成の変更禁止**(既存 `src/main/kotlin/com/gitlab/eclipse/*` 内に追加)。
- **ビルドシステムの変更禁止。** 新規 OSGi 依存は**不要**: `org.eclipse.ui` / `org.eclipse.ui.editors` は `build.gradle.kts:176-177` で既に Require-Bundle 済み。
- `plugin.xml` は**追加のみ**(既存エントリの変更・削除なし)。
- headless devcontainer では SWT / Browser / LS 実接続を動かせない。SWT を触るコードは自動検証できない(§20)。

## 6. システム構成

```
既存(無変更)                                    新規
────────────────────────────────                ──────────────────────────────────────
ChatWebviewCatalog                              WebviewUriResolver        (SWT フリー)
LanguageServerBrowserView  ◀── §7.4 の intent 追加のみ
GitLabDuoChatWebViewClient                      WebviewBrowserHost        (SWT・共有)
  (classic 専用・focus ゲート)                   ├─ AgenticTabsView       (ViewPart)
AgenticChatWebViewController ◀── appReady 実装のみ  └─ WebviewEditorPart    (EditorPart)
                                                     + WebviewEditorInput / Key / Opener
                                                AgenticChatWebViewClient  (appReady ゲート)
```

配置(ディレクトリ構成変更禁止のため既存パッケージ体系内):

- `com.gitlab.eclipse.lsp.webview` … `WebviewUriResolver`(既存 `LanguageServerWebviewService` と同居)
- `com.gitlab.eclipse.views.webview` … `WebviewBrowserHost` / `AgenticTabsView` / `WebviewEditor*`
- `com.gitlab.eclipse.chat.webview` … `AgenticChatWebViewClient`(既存 `GitLabDuoChatWebViewClient` と同居)
- `com.gitlab.eclipse.chat.commands` / `.actions` … 各ハンドラ

## 6a. LS セッション identity — 共有基盤への変更(**round 2 で追加。スコープ拡大**)

### 6a.1 何が欠けているか(実コードで確認)

`appReady` がホストに届くまでの経路に、**発信元セッションを示す情報が 1 つも無い**:

| 箇所 | 現状 |
|---|---|
| `GitLabLanguageServerClient.gitlabPluginNotification`(`:149-160`) | `PluginMessage(pluginId, type, payload)` のみを `dispatch` へ渡す |
| `PluginMessageService.dispatch(route, payload)`(`:11`) | 引数は 2 つ。`CompletableFuture.supplyAsync` で**別スレッドへ跳ぶ**(`:12`) |
| `PluginMessageRoute(pluginId, type, method)` | 接続 identity を持たない |
| `PluginRegistry`(`:26-52`) | `parameterCount > 1` を `error()` で禁止 |

したがって controller 側で「現在の proxy」を読むしかなく、それでは旧セッションの通知を判別できない。

### 6a.2 セッション identity の所在

**`GitLabLanguageServerClient` は LS 起動ごとに新規生成される**(`GitLabLanguageServerProcessProvider.kt:150` の `.setLocalService(GitLabLanguageServerClient())`)。**インスタンスの寿命がそのままセッションの寿命**なので、これを identity の担い手にできる。

controller に client 型そのものを漏らさないため、識別専用の空の型を置く:

```kotlin
/** 1 つの Language Server 接続の identity。状態を持たず、参照同一性だけが意味を持つ。 */
class LanguageServerSession
```

`GitLabLanguageServerClient` が自身の `val session = LanguageServerSession()` を持ち、4 つの `dispatch` 呼び出しすべてに渡す。

### 6a.3 「現在のセッション」の公開(原子的に)

`GitLabLanguageServerWrapper` は現在 companion object の `languageServerProxy` 1 つだけを保持する(`:3-18`)。**proxy と session を別々のフィールドにすると、2 回読む側が `(新 proxy, 旧 session)` を観測しうる。** そこで**単一の不変スナップショットとして公開**する:

```kotlin
data class LanguageServerHandle(val proxy: GitLabLanguageServer, val session: LanguageServerSession)

private val snapshot = AtomicReference<LanguageServerHandle?>(null)   // 唯一の真実

val currentSnapshot: LanguageServerHandle? get() = snapshot.get()
val languageServer: GitLabLanguageServer? get() = snapshot.get()?.proxy  // 既存 API・呼び出し元は無変更
fun registerLanguageServer(handle: LanguageServerHandle)   // snapshot.set(handle)。組を 1 回で公開
fun unregisterLanguageServer()                         // 既存(無条件・stopLocked から)
fun unregisterLanguageServer(captured: LanguageServerHandle): Boolean   // §6a.3b。CAS
```

**`AtomicReference` は必須(round 4 で修正)。** round 3 は `@Volatile` を指定したが、**それは読み書き単体の可視性しか与えず、条件付き更新の原子性を与えない。** identity-aware な解除を「現在 session を読む → 一致なら null を書く」と実装すると read-modify-write になり、以下が起きる:

> `GitLabLanguageServerProcessProvider.kt:165-193` の初期化 callback は **`lifecycleLock` の外**で走る(`synchronized` で囲まれているのは `process !== startedProcess` の判定だけで、**`err != null` 分岐にはロックが一切ない**)。旧 A の callback が「現在 = A」を確認した直後に restart が B を登録すると、**A が後から B の snapshot を null で上書きする。** チャット・診断起動・テーマ送信など `languageServer` を読む**既存の全読者が、次の restart まで LS 不在になる。**

→ **`unregisterLanguageServer` は `snapshot.compareAndSet(captured, null)` で実装する。** 比較対象は**その callback 自身が登録した handle** であり、読み直した session ではない。CAS が false を返したら**何もしない**(既に別の接続に置き換わっている)。

**handle の生成と公開の順序を厳密に規定する(round 5 で修正)。** round 4 の記述には 2 つの欠陥があった。

1. **`registerLanguageServer(proxy, session)` が内部で handle を生成する形**では、解除側が CAS の期待値を得られない。`AtomicReference.compareAndSet` は**参照同一性**で比較するため、呼び出し側が同値の `data class` を作り直しても**常に CAS が失敗する**(active crash も初期化失敗も解除できない)。
2. **登録メソッドが handle を返し、それをローカルへ格納する形**でも、**snapshot の公開とローカルへの格納の間に `onExit` が走る窓**が残り、その窓で死んだ snapshot を解除できない。

**したがって順序を逆にする。**

```kotlin
// GitLabLanguageServerProcessProvider.start() の中
val handleRef = AtomicReference<LanguageServerHandle?>(null)   // callback から見えるローカル
// ... :127 の onExit と初期化 callback は handleRef.get() を読む ...

val handle = LanguageServerHandle(languageServerProxy.remoteProxy, client.session)  // 一度だけ生成
handleRef.set(handle)                                  // ① 先に callback へ公開する
languageServerWrapper.registerLanguageServer(handle)   // ② 次に snapshot を公開する
```

- **handle は 1 度だけ生成し、同じインスタンスを両方に渡す。** `registerLanguageServer` は `(proxy, session)` ではなく **`handle` を受け取る**(内部生成しない)。
- **① が ② より先**であること。**ただし round 5 で書いた「①-② 間に `onExit` が走る」という論拠は誤りだった(round 6 で訂正)。**
  - **実際のロック順序**: `start`(`:71`)と `restart`(`:84`)は `startLocked` 全体を `synchronized(lifecycleLock)` で囲み、`onExit` callback(`:129`)も**同じロックを取る**。したがって ①-② の間にプロセスが終了しても callback はロック解放まで待ち、**①-② の窓は production の `onExit` からは到達不能**である。
  - **CAS が必要な本当の理由は初期化 callback である。** `initializeResult.handleAsync`(`:165-193`)は**ロックの外**で走り(`synchronized` は `process !== startedProcess` の判定だけを囲む)、`err != null` 分岐にはロックが一切ない。**ここだけが restart と真に並行しうる。**
  - **①→② の順序を保つ意味**: 「snapshot が公開されているなら `handleRef` は必ず set 済み」という不変条件を、**ロックに依存せずに**成立させる(初期化 callback はロックを持たないため、この不変条件がロック由来だと当てにできない)。
- `handleRef` は **`AtomicReference`**(ローカル変数の直接キャプチャにしない。別スレッドから読むため happens-before が要る)。

**A26 / A28 は到達可能な順序だけを固定する(round 6 で修正)。** 到達不能な ①-② 窓をテストしようとすると、production callback からは観測できず、callback を直接呼ぶ形にすると §20a の入口規約に反する。**固定するのは次の 3 つ。**

1. **実 `Process.onExit()` を `startLocked` の実行中に発火させる** → ロック解放後に callback が走り、snapshot が失効する。
2. **`startLocked` が例外を投げる** → `restart` の catch が `stopLocked()`(`:103`/`:108`)で**無条件** `unregisterLanguageServer()` を先に実行し、**後から走る `onExit` の CAS は失敗して新しい状態を壊さない**。
3. **初期化失敗 callback(ロック外)と restart の並行** → **CAS が B の snapshot を守る**。**この 3 番目が CAS の存在理由そのもの**であり、1・2 はロックで直列化されている。

**可視性(round 3 で追加)。** 組が裂けないことと、他スレッドから見えることは別問題である。**現在の `languageServerProxy` には `@Volatile` が無い**(`GitLabLanguageServerWrapper.kt:5`)。登録・解除は LS ライフサイクルのスレッド、照合は `PluginMessageService` の `supplyAsync` スレッドと UI スレッドから行われるため、素の `var` のままでは読み手が旧 A や null を観測し、**正しい B の結果を破棄したり、A 由来のタブを現セッション由来と誤判定したりできる。** `languageServer` も必ずこの安全に公開された値から導出する(2 つの別フィールドにしない)。

`registerLanguageServer` は `GitLabLanguageServerProcessProvider.kt:157` の 1 箇所から呼ばれる。同 `:150` で生成した client をローカルに束ね、その `session` から上記の順序で `handle` を作って渡す。**既存の `languageServer` プロパティのシグネチャと意味は維持する**ので、それを読んでいる既存コードは 1 行も変わらない。

### 6a.3b 異常終了・初期化失敗での失効(**round 3 で追加。既存挙動の変更を含む**)

実コードで確認した現状:

| 経路 | 現状 | 結果 |
|---|---|---|
| `onExit`(`:127-142`) | `process = null` / `processListener = null` / `SecurityScanLifecycle.onServerStopped()`。**`unregisterLanguageServer()` を呼ばない** | クラッシュ後も snapshot は A のまま |
| 初期化失敗(`:167-168`) | `logger.error` のみ | 同上 |
| `stopLocked`(`:200`) | `unregisterLanguageServer()` を呼ぶ | 正常停止だけが失効する |

このため、**クラッシュ後に「現在のセッション」を問うと死んだ A が返る。** §8.1 の判定は表示中の A と stale な A を「同一セッション」とみなし、**再コマンドでも dead URI を activate するだけ**になる(round 2 で追加した判定が、この経路では効かない)。

**修正: identity-aware な `unregisterLanguageServer(captured: LanguageServerHandle)` を追加し、`onExit` の identity ガード内と初期化失敗分岐から `handleRef.get()` を渡して呼ぶ。**

- **自分の session が現在の session と一致するときだけ失効させる。** superseded なプロセスの遅延失敗が、置き換わった新しい接続を落とさないようにする(既存の `process === startedProcess` ガードと同じ思想)。
- **これは既存挙動の変更である**: クラッシュ後 `languageServer` は**死んだ proxy ではなく null** を返すようになる。結果として Duo Chat は「language server の起動待ち」表示に落ちる(従来は死んだ proxy へ送って失敗していた)。**より正しい方向だが挙動変更なので、専用のテストを置き、§19 に明記する。**
- 固定するのは 2 つ: (1) **active なクラッシュで失効すること**、(2) **superseded なサーバの遅延失敗では失効しないこと**。

### 6a.4 バスでの伝播

- `PluginMessageService.dispatch(route, payload, session: LanguageServerSession?)` — 第 3 引数を追加。
- `PluginRegistry` の引数制約を「**payload 1 つ**、または **payload + `LanguageServerSession`**、または **`LanguageServerSession` のみ**、または**引数なし**」に緩める。判定は**末尾引数の型が `LanguageServerSession` か**で行う(名前ではなく型)。
- **既存の controller は 1 つも変わらない。** 現行のハンドラはすべて引数 0 か 1(payload のみ)であり、新しい分岐に入らない。

### 6a.5 このスコープ拡大に対する注意

**これは classic Duo Chat も通る共有基盤である。** 「既存への変更は 3 点のみ」という初版の制約は崩れる。したがって:

- **`PluginRegistry` の登録分岐と `PluginMessageService.dispatch` には keep-behaviour テストを必須とする**(既存の 4 形状 — 引数なし通知 / payload つき通知 / 引数なし要求 / payload つき要求 — が**現在とまったく同じように**解決されること)。
- **`parameterCount > 1` の `error()` を緩めるため、「複数引数の誤ったハンドラが黙って登録される」方向に穴が開いていないか**を明示的に固定する(末尾が `LanguageServerSession` でない 2 引数メソッドは**従来どおり `error()`**)。
- 受け入れ条件 A1 を更新する(`ChatWebviewCatalog` 等 4 つの差分ゼロは維持。`PluginMessageService` / `PluginRegistry` / `PluginController` / `GitLabLanguageServerClient` / `GitLabLanguageServerWrapper` / `GitLabLanguageServerProcessProvider` は**変更されるが、既存経路の挙動は不変**)。

## 7. コンポーネントの責務

### 7.1 `WebviewUriResolver`(SWT フリー・唯一の共有ロジック)

**責務**: webview id を受け取り、`$/gitlab/webview-metadata` の応答から当該 `WebviewInfo` を解決する。それ以外を行わない。

```kotlin
sealed interface WebviewResolution {
  /**
   * [session] = **要求開始時に捕捉した LS セッション identity**(`wrapper.currentSnapshot!!.session`)。
   * 適用側が `wrapper.currentSnapshot?.session` と**参照比較**する(§7.1a)。
   * proxy ではなく session を持つ: proxy 比較と session 比較の 2 方式に分かれると、
   * 発信元 identity(§6a)と解決結果の判定が食い違い stale URI 防止が崩れる。
   */
  data class Resolved(
    val id: String, val title: String, val uri: String, val session: LanguageServerSession,
  ) : WebviewResolution
  /** languageServer が null / webviewMetadata() が null future を返した */
  data object LanguageServerUnavailable : WebviewResolution
  /** タイムアウト、または future の例外完了 */
  data class Failed(val cause: Throwable?) : WebviewResolution
  /** 応答は得られたが当該 id が広告に無い */
  data class NotAdvertised(val id: String) : WebviewResolution
  /** id はあるが uris が空 */
  data class NoUri(val id: String) : WebviewResolution
}

fun resolve(id: String): CompletableFuture<WebviewResolution>   // 例外完了しない契約
```

#### 7.1a LS セッションの捕捉と照合(**必須。round 2 で全面改稿**)

**round 1 の形は誤りだった。** 初版は「適用時に `resolution.session !== wrapper.languageServer` を照合する」とし、readiness 側も `markReady(現在の LS proxy)` としていた。**後者は原理的に機能しない**: `appReady` を処理する時点で「現在の proxy」を読めば、旧セッションから来た通知であっても常に一致する。**照合が必ず通るため、実質 no-op だった。** さらに、受け入れ条件 A17 はテストから旧 proxy を直接渡す形だったので、**production が壊れたままでもテストは緑になる**(Phase 5B が繰り返し指摘した非識別的テスト)。

根本原因は、**通知の発信元セッションがバス上のどこにも載っていない**ことである(§6a で確認)。

**修正: 発信元 identity をバスに載せて controller まで運ぶ。** 詳細は §6a。resolver と client は以下のようになる。

- `resolve` は **要求開始時に `wrapper.currentSnapshot`(proxy と session の原子的な組)を 1 度だけ読み**、`snapshot.proxy` に要求を出し、`snapshot.session` を `Resolved.session` に載せる。
  - **proxy と session を別々に読まない。** 2 回読むと、その間の再起動で `(新 proxy, 旧 session)` の組ができる。Phase 4 の config 世代 seqlock が閉じたのと同型の穴である。
- 適用側は UI スレッドで `resolution.session !== wrapper.currentSnapshot?.session` なら破棄する(**参照比較**。`equals` ではない)。
- `AgenticChatWebViewClient.markReady(session)` の `session` は、**`appReady` を運んできた接続の identity**(§6a で伝播される値)であり、処理時に読み直した現在値ではない。

**なぜ必要か**: 世代カウンタは新しい `load()` が起きたときにしか上がらない。インスタンス A で開始した `webviewMetadata()` が飛行中に LS が再起動(インスタンス切替・`RestartLanguageServer`)すると、**新しい `load()` が無い限り世代は一致したままで、A 時代の URI が B のタブへ適用される。** 資格情報の漏洩が無くても、死んだ URI を表示するだけで機能不全になる。

**既存の前例がある**: `LanguageServerWebviewService.sendThemeChange(server)`(`:26-41`)は同じ理由で「**呼び出し時に捕捉した proxy** に副作用を束縛」しており、その KDoc(`:28-31`)は「rapid restart が queued send を新しい pre-initialize なサーバへ向け直さないようにする」と明記している。`GitLabLanguageServerProcessProvider` も `onExit` に**プロセス同一性ガード(参照比較 `===`)** を持つ(Phase 2 PR-3)。本設計はこの確立済みのパターンを踏襲する。

**ただし前例との決定的な差**: 上記 2 例はいずれも**自分が発信した**呼び出しの identity を捕捉している(捕捉点で正しい値が手に入る)。`appReady` は**相手から届く**通知であり、捕捉点が存在しない。だから伝播が要る。round 1 はこの差を見落として前例をそのまま当てはめていた。

**設計判断**

- **キャッシュしない。** メタデータは LS セッション内で安定だが、キャッシュを持つと「LS 再起動時の無効化」という状態を追加することになる。Phase 5B が 9 ラウンドかけて閉じたのは「install 時にプラットフォームが失敗すると黙って永久に劣化する」というクラスであり、無効化漏れは同じクラスの新規インスタンスになる。コマンド起動 1 回につき 1 往復で足りる。
- **never-throws 契約**(既存 `GitLabProjectUrlResolver` と同じ)。返す future は常に正常完了し、失敗は sealed 値で表現する。共有 CoroutineScope / UI コールバックを汚染しない。
- **UI に触らない** → headless で TDD 可能。
- タイムアウトは既存 `LanguageServerBrowserView.METADATA_TIMEOUT_SECONDS = 10L`(`:44`)と同値。**同じ定数を共有せず各自が持つ**(共有すると片方の都合で他方が変わる)。値が同じであることは意図であり、設計書に記録する。

**`uris` の選択**: `uris.firstOrNull()` を採る。参照実装は `webviewInfo.uris[0]` を使い、ソース中に `FIXME this is not the right way to pick the uri, this should be platform dependent`(`setup_webviews.ts:117`)と明記している。**参照実装が未解決としている点をこちらで独自に解決しない。** 既存 `ChatWebviewCatalog.kt:26` も `uris.firstOrNull()` であり、挙動を揃える。

### 7.2 `WebviewBrowserHost`(SWT・ViewPart とエディタの共有)

**責務**: 親 `Composite` の上に `StackLayout` を敷き、webview 用 `Browser` / loading ページ / message ページを保持し、`WebviewUriResolver` の結果に応じて表示を切り替える。

**なぜ共有するか**: ViewPart 側とエディタ側で必要なものが完全に一致する(Browser 生成の `SWT.EDGE`/`SWT.WEBKIT` 分岐、loading / message ページ、世代カウンタ、dispose ガード、失敗文言の写像)。2 箇所に書くと、Phase 5B が「同じ欠陥が複数箇所にあるならインスタンスではなくクラスとして閉じる」として最終的に到達した形の逆になる。

```kotlin
class WebviewBrowserHost(parent: Composite, private val resolver: WebviewUriResolver) {
  fun load(id: String, queryParams: Map<String, String> = emptyMap())  // UI スレッド
  fun setFocus(): Boolean
  fun dispose()
}
```

- Browser 生成は既存 `LanguageServerBrowserView.newBrowser`(`:324-327`)と同一の分岐(Windows = `SWT.EDGE`、他 = `SWT.WEBKIT`)。
- message ページの HTML は既存 `themedHtml`(`:329-355`)と同一の形(`ThemeProvider.currentTheme()` の CSS 変数)。
- **3 重ガード**: `whenComplete` → `asyncExec` の**中で**、(1) 世代一致、(2) `isDisposed`、(3) **§7.1a の LS セッション照合**をこの順に再チェックする。**(3) は (1) では代替できない**: 世代は新しい `load()` が無ければ上がらないため、飛行中に LS が再起動しても世代は一致したままになる(既存 `LanguageServerBrowserView.kt:114-116` は (1)(2) しか持たないが、あちらは feature-state 遷移と LS-ready フックが常に新しい `refresh()` を起こすため露出が小さい。本面にはその駆動源が無い)。
- 失敗しても既存の生きた Browser を破棄しない(既存 `:117-126` と同じ方針)。

### 7.3 エディタ領域の面(`root/mcp` / `root/flow`)

`ci/lint/MergedYaml*` トリオ(Phase 4 PR-4)と同型。差分は「`IStorageEditorInput` + `DefaultTextEditor`」が「独自 `EditorPart` + `Browser`」に置き換わる点のみ。

| 型 | 責務 |
|---|---|
| `WebviewEditorKey(webviewId: String, queryParams: Map<String, String>)` | 同一性。**webview id + クエリ(= 表示内容の識別子)** |
| `WebviewEditorInput(key, fallbackTitle, queryParams)` | `equals`/`hashCode` は **`key` のみ**に依存。`exists() = false`、`getPersistable() = null`、`getAdapter() = null` |
| `WebviewEditorPart` | `EditorPart` + `WebviewBrowserHost` を 1 つ保持。`isDirty() = false`、`doSave`/`doSaveAs` は no-op、`isSaveAsAllowed() = false` |

**タブ名の解決順序**: タブ名は `openEditor` の時点で必要になるが、正しい title は解決後(`WebviewResolution.Resolved.title`)にしか分からない。したがって `WebviewEditorInput.getName()` は **id ごとの固定 `fallbackTitle`** を返し(`root/mcp` → `MCP Dashboard`、`root/flow` → `Flow Builder`。§5.2 の実測値)、解決が成功した時点で `WebviewEditorPart` が `setPartName` で置き換える。**解決に失敗した場合は `fallbackTitle` のままにする**(失敗を示すのは message ページの役目であり、タブ名を書き換えると閉じたタブ履歴に紛らわしい名前が残る)。`fallbackTitle` は表示専用で、同一性(`equals`)には一切関与しない。

この判断は **SWT フリーの純関数 `WebviewEditorTitles.titleFor(resolution): String?`**(null = 変更しない)に切り出す。`EditorPart` の中に埋めると headless で検証できず、受け入れ条件 A13 が主張だけになる。
| `WebviewEditorOpener` | `openOrReload(input)`: **アクティブページ上**の key 一致エディタを activate。無ければ `activePage.openEditor`。**他ウィンドウは走査しない**(§8.1) |

**キーが `queryParams` を含む理由(設計変更・round 1 で修正)**

参照実装 `setup_webviews.ts:80-135` は webview id ごとに**パネルを 1 つだけ**保持し、空でない `initState` が来たら `panel.dispose()` して作り直す。当初はこれに合わせて **key = webview id のみ**とし、`equals` が `queryParams` を無視する設計にしていた。**これは誤りだった。**

- VSCode の「id あたり 1 パネル」は **1 つの extension host ウィンドウ内での同一性**である。初版はこれを `MergedYamlEditorOpener` 由来の**全ワークベンチウィンドウ走査**と組み合わせていたため、**ウィンドウ A の Flow Builder タブに、ウィンドウ B から開いた別 YAML の内容が黙って流し込まれる**状態になっていた。起動元 B のユーザーには何も起きず、A のユーザーは自分の開いていた flow が別ファイルに置き換わる。
- 根本原因は `MergedYamlEditorKey` との非対称にある。あちらの key は `(instanceUrl, projectId, sourceId)` = **内容の出所を含む**。こちらは内容の識別子(`queryParams`)を意図的に落としていたため、「別の内容を同じタブへ配信する」ことが仕様どおりになってしまっていた。

**したがって `queryParams` を key に含め、内容ごとに 1 タブとする。**

- `root/mcp` は `queryParams` が空なので、従来どおり**常に 1 タブ**(挙動不変)。
- `root/flow` は **YAML ファイルごとに 1 タブ**。Eclipse の「ファイルごとに 1 エディタ」という慣行に一致し、ウィンドウ間ブロードキャストという故障クラスが**構造的に消える**(インスタンス修正ではなくクラス修正)。
- `WebviewEditorInput` は同一性と表示内容の**両方**の担い手になり、`MergedYamlEditorInput` との非対称が解消する。

**タブの増殖について**: 上限はユーザーが Flow Builder を開いた YAML の数であり、通常のエディタタブと同じ性質である。参照実装からの逸脱であることは受容済みの制限として記録する(§23 L-新)。

**`getPersistable() = null` は必須**。ワークベンチ再起動後にエディタが復元されると、**前セッションの LS が発行した死んだ URI** を指すタブが出る。`exists() = false` と併せて `EditorHistory` とワークベンチ memento の両方から外す(`MergedYamlEditorInput.kt:23,31` と同じ理由)。

**`root/flow` の前提条件**: 参照実装 `src/desktop/commands/open_flow_builder.ts:5-8` はアクティブエディタが YAML でなければエラーメッセージを出して何もしない。同じ前提を課す。

#### 7.3a `uri` クエリの組み立て規則(**必須・round 1 で追加**)

「解決した URI に `uri=<ファイル URI>` を付ける」だけでは実装が分かれる。

**round 1 の指定(`URI(scheme, authority, path, query, fragment)` に query 全体を渡す)は誤りだった。** この JDK で実測した結果:

```
new URI("http","h","/p","a=%26b",       null) → http://h/p?a=%2526b
new URI("http","h","/p","uri=file:///x%20y",null) → http://h/p?uri=file:///x%2520y
```

multi-argument コンストラクタは query 引数中の `%` を `%25` に量子化する。したがって:

- **raw の既存 query を渡すと** `%26` が `%2526` になり、**既存 parameter の値が変わる。**
- **decode 済みの query を渡すと** 値の中の `%26` が `&` に戻り、**parameter 境界になってしまう。**
- 「符号化は 1 回だけ」を最終 URI 文字列に対して適用するという round 1 の言い方も誤りで、**query 値に入れる `file:///...%20...` の `%` は外側では `%25` にしなければならない。**

**したがって規則を以下に置き換える。**

1. **既存の raw query と raw fragment はそのまま保存する。** `URI.getRawQuery()` / `getRawFragment()` を使い、**decode も再符号化もしない**。
2. **追加する `uri` の値だけを RFC 3986 の query component として一段符号化する。** `unreserved`(`ALPHA` / `DIGIT` / `-` `.` `_` `~`)以外はすべて percent-encode。とくに `%` → `%25` / `&` → `%26` / `=` → `%3D` / `#` → `%23` / 半角空白 → `%20` / `+` → `%2B`。非 ASCII は **UTF-8 バイト列**を percent-encode。
3. **適用対象を限定する(round 3 で追加)**: **absolute かつ hierarchical な URI のみ**を受け付ける。`URI.isOpaque()` が真、または `isAbsolute()` が偽なら **`WebviewResolution.Failed` として扱い**、§12 の失敗経路へ流す。opaque URI では `getRawQuery()` が hierarchical な query として分離されないため、下の式が成立しない。
4. **`base` を厳密に定義する(round 3 で追加)**: `base` = **元の raw 文字列から、最初の `?` または `#`(いずれか早い方)以降を除いた prefix**。
   - **元の URI 文字列をそのまま `base` に使ってはならない。** 既存の query や fragment が残っていると、その後ろに `?` を足すことになり、**`uri` が fragment の中に入る。**
   - **components から再構成してもならない。** 再構成は 2. で避けたばかりの raw 値の変換を再導入する。**あくまで raw 文字列の切り出しである。**
5. **連結は文字列で行う**: `base` + (`rawQuery.isNullOrEmpty()` ? `"?"` : `"?" + rawQuery + "&"`) + `"uri=" + encodedValue` + (`rawFragment != null` ? `"#" + rawFragment` : `""`)。**multi-argument `URI` コンストラクタは使わない。**
   - `rawQuery` が**空文字列**(URI が `...?` で終わる)の場合は `null` と同じ扱いにする(`"?&uri=..."` にしない)。
6. **同名 parameter**: LS の URI に既に `uri` があっても**削除・上書きしない**(`append` のセマンティクス。参照実装 `setup_webviews.ts:116-119` も `searchParams.append`)。
7. **`java.net.URLEncoder` は使わない。** あれは `application/x-www-form-urlencoded` であり、**半角空白を `+` にする**。query component の符号化とは別物である。
8. **ファイル URI の取得元**: アクティブエディタの `IFileEditorInput.file.locationURI`(存在しない場合は `IURIEditorInput.uri`)。取得できなければ前提条件違反として §12 の通知経路へ。

**受け入れテスト(headless・純ロジック)**

- ファイル名に `&` / `#` / `=` / `?` / 半角空白 / 非 ASCII(日本語)/ `+` / `%` を含む YAML パス → URI が分断されず、`uri` の値を復号すると元に戻る。
- **LS の URI が既に `%26` / `%3D` / `%25` を含む query を持つ場合** → **その値が 1 バイトも変化しない**(round 2 の指摘に対応する識別的テスト。round 1 の実装指定ならここで落ちる)。
- LS の URI が fragment を持つ場合 → fragment が保存され、query の後ろに来る。
- **query 無し + fragment あり** → `base?uri=...#frag` になる(`uri` が fragment に入らない)。
- **空 query(`...?` で終わる)** → `?&uri=` にならない。
- **相対 URI / opaque URI** → `Failed` になり、連結を試みない。

> **既存の前例**: Phase 2 の `PathSegmentEncoder`(RFC 3986 セグメント単位)と `SearchQueryBuilder`(form encode)。**どちらもそのままでは使えない**(前者はセグメント用、後者は form 用で空白が `+`)。query component の符号化は本設計で新たに規定する。実装時にこの 2 つと `URLEncoder` の 3 者を取り違えないこと。

**エディタ領域の配置**: 参照実装は `root/flow` を `ViewColumn.Beside` で開く(`setup_webviews.ts:50-59`)。Eclipse には `Beside` の直接対応が無く、Phase 4 PR-4 も同じ理由で **E2「Beside 不可 = 通常タブ」**として通常タブを採用した。同じ判断を踏襲する。

### 7.4 Agentic コマンド 2 件

閉じるべき関門が **2 段**ある。片方だけでは黙って落ちる。

**関門 A — agentic webview が実際に表示されていること**

既存の pending-intent 機構を使う。`LanguageServerBrowserView` に以下を追加する(**本設計における既存コードへの唯一の変更**):

- フィールド `private var pendingAgenticView: String? = null`(`:69-71` の `focusRequested` / `pendingClassicPrompt` の隣)
- `requestAgenticView(view: String)`: `selectWebview(AGENTIC_WEBVIEW_ID)` → `pendingAgenticView = view` → `refresh()`(既存 `requestClassicPrompt`(`:169-173`)と同型)
- `showResolvedSelection` の戻り値を `Boolean`(classic が表示されたか)から **`String?`(実際に表示された webview id。表示に至らなかった場合は null)** に変更する。現在の 3 つの `return false` 経路(`:234` 候補なし / `:241` feature 無効 / `:248` Browser 不在)はすべて `null` になり、`:253` の `return resolved == CLASSIC_WEBVIEW_ID` は `return resolved` になる。
- `flushPendingIntents` の引数を同じく `String?` に変更し、**classic 系 2 つの intent は `shownId == CLASSIC_WEBVIEW_ID` のときのみ**(= 現行と同一条件)、`pendingAgenticView` は `shownId == AGENTIC_WEBVIEW_ID` のときのみフラッシュする。3 つの intent はいずれの場合も**クリアされる**(現行 `:261-266` の「transient = フラッシュされなくても捨てる」規約を維持)。**classic の 2 つの intent の観測可能な挙動は変更しない。**

`chat/utils/DuoChatWindow.kt` に `openDuoChatWindowWithAgenticView(view: String)` を追加(既存 `openDuoChatWindowWithClassicPrompt`(`:26-28`)と同型)。

**関門 B — agentic webview アプリが `webviewReady` を送り終えていること**(§5.3)

#### 故障モードを 2 つに分離する(**round 1 で全面改稿**)

初版は「`appReady` を受けたらフラッシュしてタイマを解除する」という**単一**の機構だった。§5.3a で `appReady` が `webviewReady` より**先**に送られると確定した以上、この形は以下のように破綻する。

> ホストが `appReady` を観測 → `switchView` を送信 → LS にまだ転送ハンドラが無く**破棄** → 同時に `pending` とタイマも解除 → **無反応・無ログ・無通知。**

つまり初版の「必ず気づける」という根拠(旧 R1)は、**まさに守ろうとした経路で成立していなかった。** 故障は 2 つあり、別々の機構で受ける必要がある。

| | 故障 | 観測できるか | 機構 |
|---|---|---|---|
| **F-a** | `appReady` が来ない(webview が読み込まれない・LS が落ちた) | できる(latch が開かない) | **readiness タイムアウト**(10 秒)→ `pending` 破棄 + Error Log + ユーザー通知 |
| **F-b** | `appReady` は来たが、LS の `switchView` 転送ハンドラが未登録 | **できない**(ack が無い) | **境界つき再送**(§下記) |

```kotlin
class AgenticChatWebViewClient(
  private val wrapper: GitLabLanguageServerWrapper,
  private val onUndelivered: (String) -> Unit,
  private val scheduleTimer: (Long, Runnable) -> Unit,   // シーム。既定は Display.timerExec
) {
  private var readySession: LanguageServerSession? = null // §7.1a: latch はセッション付き
  private var pending: String? = null                     // 1 スロット・latest-wins
  private var commandGeneration: Long = 0                 // §7.4a: 予約済み callback の失効に使う

  fun switchView(view: String)                  // 世代++ 。ready かつ同一セッションなら送信+再送予約
  fun markReady(session: LanguageServerSession) // appReady を「運んできた接続」の identity(§6a)
  fun markNotReady()                            // 世代++ 。Browser 新規作成 / URI 変更 / LS 再起動
}
```

#### 7.4a 予約済み callback の失効(**round 2 で追加**)

初版の再送にはコマンド世代が無く、**予約済みの再送 callback が値を捕捉したまま生き残っていた。** その結果:

> `history` を実行 → 送信 + 再送を予約 → その数百 ms 以内に `newConversation` を実行 → `newConversation` が送られる → **しかし残っていた `history` の再送が後から届き、ユーザーを履歴に引き戻す。**

これは L-10(ユーザーの手動移動)とは別物で、**2 つの正規のコマンド間で latest-wins が破れている**。`pending` には latest-wins を効かせながら、**再送スケジュールには効かせていなかった**ための欠陥である。

**規則**:

- `switchView` / `markNotReady` / **セッション変更**のいずれでも `commandGeneration` を進める。
- 予約された callback は自分の `gen` を捕捉し、**実行時に 3 つすべてを再照合する**: (1) `gen == commandGeneration`、(2) `readySession != null`、(3) `readySession === wrapper.currentSnapshot?.session`。1 つでも外れたら**何もせず終了**(送信も通知もしない)。
- 世代は単調増加。`markNotReady()` は世代を進めるので、**リセット後に古い再送列が復活する解釈は存在しない。**

**テストで固定する操作列**(受け入れ条件 A18・A20):

1. `history` → 再送予約 → `newConversation` → **`history` の再送が 1 回も送られない**
2. `history` → 再送予約 → `markNotReady()` → **再送が 1 回も送られない**
3. `history` → 再送予約 → セッション変更 → **再送が 1 回も送られない**

**F-b への対処 = 境界つき再送**

`switchView` は同一 `view` について冪等(§14)であり、送信時点は webview が開いた直後でユーザーがまだ操作していない。したがって**同じ `switchView` を短い間隔で有限回(2 回・合計 3 送信)再送する**ことで、`webviewReady` の処理が遅れた窓を覆う。

- これは「遅延を置いて競合を隠す」のとは**異なる**。遅延は順序を 1 点で賭けるが、再送は窓全体を覆う。
- **再送を無限にしない。** 有限回で打ち切り、以後は何もしない(F-b は観測できないため、打ち切り後に通知を出すと**成功時にも必ず誤通知が出る**= Phase 5B が「最悪の誤り方」とした偽陽性そのものになる)。
- **ユーザーへの最終的な確認は画面である**: 履歴に切り替わったかどうかはユーザーが直接見る。F-b が残った場合、ユーザーはコマンドを再実行でき、そのときは既に ready なので確実に届く。この受容を §23 に制限として明記する。

**その他の規則**

- **キューは 1 スロット・latest-wins。** `switchView` はビューセレクタであり、古い指示を後から配送する意味がない。classic の無制限 `MutableList`(`GitLabDuoChatWebViewClient.kt:10`)とは**意図的に変える**。
- `AgenticChatWebViewController.appReady(session)` (現在は引数なしで `= Unit`)が `markReady(session)` を呼ぶ。**`session` は §6a でバスから伝播された発信元 identity であり、処理時に読み直した現在値ではない。** `PluginMessageService.dispatch` は `supplyAsync` で別スレッド実行(`:12`)なので、UI スレッドへマーシャルしてから状態を触る。
- **latch はセッション付き**(§7.1a)。`switchView` の送信前に `readySession === wrapper.currentSnapshot?.session` を照合する。
- **`markReady` は受信 `session` が現在の session と一致するときだけ格納する(round 3 で追加)。不一致なら何もしない — 既存の latch を保持する。**
  - **無条件に格納すると新しい latch を壊す**: B の `appReady` で latch が開いた後に、`supplyAsync` / UI キューで遅延した **旧 A の `appReady`** が届くと `readySession` が A に戻る。送信時の照合は A を拒否するだけなので、以後の B 向けコマンドは `pending` に入り、**`appReady` は 1 回しか来ないので誰も latch を開け直さず F-a タイムアウトに落ちる。**
  - **A21 ではこの汚染を検出できない**(あれは「旧 A 単独では開かない」しか見ていない)。→ **A24 を追加**: `B ready → 遅延 A ready → B の switchView が即送信される`。
- **latch のリセット点**: `LanguageServerBrowserView.syncBrowsers`(`:200-227`)が agentic id の `Browser` を**新規作成した時**と **URI を差し替えた時**。セッション照合と併せて二重に守る(リセットが届く前に旧 `appReady` が来る競合を照合が受ける)。
- 送信は `ExtensionToPluginNotification(pluginId = AGENTIC_WEBVIEW_ID, type = "switchView", payload = mapOf("view" to view))`。

**F-a タイマも `commandGeneration` を捕捉し、発火時に照合する(round 5 で追加)。**

- 規定が無いと次が起きる: ready 前に `history` を実行 → F-a タイマ開始 → **9 秒後**に `newConversation` を実行して新しい `pending` を置く → **10 秒後に最初のタイマが発火し、後から置かれた `newConversation` を破棄して誤ったエラー通知を出す。** これは §12 で避けると宣言した**偽陽性**そのものである。
- 同様に、`markReady` 後に Browser が再作成されて新しい `pending` が置かれた場合も、旧タイマが残っていれば新しい要求を消す。
- → **F-a タイマは §7.4a の予約済み callback と同じ規則に従う**(発火時に `gen == commandGeneration` を照合し、不一致なら**何もしない**)。`switchView` / `markReady` によるフラッシュ / `markNotReady()` はいずれも世代を進めるので、明示的な取消し API が無くても失効する。
- **A12 に操作列を追加**: 旧タイマが発火しても、新しい `pending` とその 10 秒の猶予が維持されること。

**タイマはコンストラクタのシームで注入する。** `Display.timerExec` を直接呼ぶと readiness タイムアウトと再送(受け入れ条件 A12)が headless で一切検証できず、本設計の中心的な主張が**テストに裏付けられないまま**になる。既定値が SWT を触るため、既定引数ではなく**オーバーロード**にする(既存 `LanguageServerWebviewService.sendThemeChange`(`:21-32`)が同じ理由でオーバーロードを採っている: Kotlin の `$default` ブリッジは MockK のモック上でも既定式を評価するため)。タイマは UI スレッドで回す(状態が UI スレッド専有のため。§15)。

### 7.5 起動口(`plugin.xml`・追加のみ)

| コマンド | 配置 | 根拠 |
|---|---|---|
| `gl.webview.root.mcp.show` | `menu:gitlab-eclipse-plugin.menus.statusWidgetMenu`(`plugin.xml:1319`) | 既存の `OpenMcpUserConfig` / `OpenMcpWorkspaceConfig`(`:1371-1377`)の隣 |
| `gl.webview.agenticTabs.show` | 同上 | Duo 横断アクション(`RestartLanguageServer`(`:1379-1382`)と同列) |
| `gl.openFlowBuilder` | `popup:#AbstractTextEditorContext?after=additions` の `com.gitlab.eclipse.navigation.menu`(`:946-947`) | アクティブ YAML エディタが前提。Phase 4 CI lint と同じ判断 |
| `gl.agenticChat.showHistoryView` / `.startNewConversation` | `statusWidgetMenu` | 参照実装はコマンドパレットのみ。Eclipse では Quick Access + ここで等価 |

`gitlab-eclipse-plugin.menus.chatSelectorMenu`(`:1025`)には**入れない**。あれは 2 つの webview の**モード選択**であり、履歴表示は**アクション**である。混在させると #26 で既に踏んだ radio 表示の stale 問題を再現する。

`agentic-tabs` のビューは `org.eclipse.ui.views`(`:431`)に 1 件追加(category = 既存 `gitlab-eclipse-plugin`、name = LS が広告する `GitLab Duo Agent Platform`)。エディタは `org.eclipse.ui.editors` 拡張を新規に 1 つ追加(拡張子/ファイル名の関連付けは行わない。`page.openEditor(input, editorId)` で明示的にのみ開く)。

## 8. 処理フロー

### 8.1 エディタ領域 webview を開く(`root/mcp` / `root/flow`)

key に `queryParams` が入った(§7.3)ため、**一致 = 同じ webview かつ同じ内容**である。したがって「一致したタブを別の内容で再ロードする」経路そのものが存在しない。

```
ハンドラ (UI スレッド)
  └─ root/flow のみ: アクティブエディタが YAML か検査 + ファイル URI を取得
       └─ 違反 → ユーザー通知して終了(silent no-op にしない)
  └─ key = (webviewId, queryParams) を構築
  └─ WebviewEditorOpener.openOrReload(input)         [UI スレッド]
       ├─ (1) アクティブページに key 一致のエディタがあるか
       │        ├─ あり かつ host の表示内容が現セッション由来 → activate のみ
       │        └─ あり かつ 別セッション由来 → activate + host.load(...) で再解決
       └─ (2) 無ければ activePage.openEditor(input, EDITOR_ID)
                └─ WebviewEditorPart.createPartControl
                     └─ WebviewBrowserHost.load(id, params)
                          ├─ 世代++ / loading ページ表示 / LS proxy を捕捉
                          ├─ resolver.resolve(id)                  [UI スレッド外]
                          └─ whenComplete → asyncExec              [UI スレッドへ復帰]
                               ├─ 世代不一致 / isDisposed / セッション不一致 → 破棄
                               ├─ Resolved  → browser.setUrl(§7.3a のビルダ結果)
                               └─ その他    → message ページ
```

**分岐は「アクティブページ上の一致」でのみ判定する**(初版は「全 window/page の一致」と「アクティブページでの可視化」を混在させており、他ウィンドウに一致があるとき起動元にタブが開かない読みが成立していた)。他ウィンドウに同じ key のタブがあっても**触らない**: 内容が同一なので更新の必要が無く、他ウィンドウのユーザーの表示を勝手に動かす理由も無い。

**「内容が同じ」と「URI が現セッション由来」は別である(round 2 で追加)。** round 1 で key に内容を含めた際、「一致 = 同じ内容 → 再ロード不要」と単純化したが、これは**新しい経路を開いていた**:

> `root/mcp`(または同じ YAML の `root/flow`)のタブを開いたまま LS を再起動 → 同じコマンドを再実行 → key が一致するので `activate` だけで終了 → **旧 LS の死んだ URI が残り続ける。**

しかもこれは **§18 の「LS 再起動 → 次回のコマンドで新しい URI に解決」および実機検証項目 8 と正面から矛盾していた**(どちらも旧設計のまま更新し忘れていた)。

→ **`WebviewBrowserHost` は現在表示している内容がどのセッション由来かを保持する**(`load` が `Resolved` を適用したときの `resolution.session`)。`openOrReload` はそれを問い合わせ、**現セッションと異なるときだけ再 `load` する**。同一セッションなら `activate` のみ(webview の画面内状態を無意味に捨てない)。§18 と実機検証項目 8 もこの形に合わせて修正した。

**`MergedYamlEditorOpener` との差**: あちらは同一 key の内容が更新されうる(再 lint)ため全ページの再 reset が要る。こちらは key が内容を含むので、同一 key = 同一内容であり、**全ページ走査そのものが不要**になる。この違いを実装時に取り違えないこと。

### 8.2 `gl.agenticChat.showHistoryView`

```
ハンドラ (UI スレッド)
  └─ openDuoChatWindowWithAgenticView("history")
       └─ showDuoChatView()                          … page.showView(VIEW_ID)
            └─ view.requestAgenticView("history")
                 ├─ selectWebview(AGENTIC_WEBVIEW_ID) … 選択を永続化・Browser があれば前面へ
                 ├─ pendingAgenticView = "history"
                 └─ refresh()                        … 既存のメタデータ解決フロー
                      └─ applyMetadata → syncBrowsers
                           └─ agentic の Browser を新規作成 / URI 変更 → client.markNotReady()
                      └─ showResolvedSelection → 解決 id を flushPendingIntents へ
                           └─ agentic が表示された場合のみ client.switchView("history")
                                ├─ ready かつ同一セッション → 送信 + 境界つき再送を予約
                                └─ それ以外 → pending = "history"(latest-wins)
                                              + readiness タイマ(10s)開始

    …後刻、webview → LS → host の appReady 到達      [別スレッド → UI へマーシャル]
      └─ AgenticChatWebViewController.appReady(発信元 session) → client.markReady(発信元 session)
           ├─ 発信元 session が現 session と一致するときだけ readySession に格納(§7.4)
           │    不一致 → 何もしない(既存 latch を保持)
           └─ pending があれば送信 + 境界つき再送を予約(F-b 対策・有限回で打ち切り)

    …10 秒経過しても appReady が来ない場合(F-a)
      └─ pending 破棄 + Error Log + ユーザー通知
```

**再送は通知を伴わない**(F-b は観測不能なので、打ち切り時に通知すると成功時にも必ず誤通知になる。§7.4)。**readiness タイムアウト(F-a)だけが通知経路を持つ。**

## 9. API / インターフェース

新規の外部 API は無い。LS へ発行するのは既存の 2 メソッド(`webviewMetadata()` / `pluginNotification()`)のみで、**GitLab インスタンスへの HTTP リクエストは 1 本も追加しない。**

## 10. データモデル

永続化するデータは無い。

- `WebviewResolution` … 一時的な解決結果(§7.1)
- `WebviewEditorKey` … エディタ同一性のみ(§7.3)
- `AgenticChatWebViewClient` の `ready` / `pending` … UI スレッド専有のインメモリ状態

Eclipse 設定ストアへの新規キーの書き込みは無い(既存 `DUO_CHAT_SELECTED_WEBVIEW` は `selectWebview` 経由で従来どおり更新される)。

## 11. トランザクション境界

該当なし。すべての操作は単一の LS 通知または単一の UI 操作であり、複数リソースにまたがる原子性を要求しない。

**Phase 4 / 5 の `ConnectionSnapshot` / config 世代 seqlock は適用しない。しかし LS セッションの固定は必要である(round 1 で修正)。**

初版はこの 2 つを混同していた。初版の根拠は「ホストは GitLab へ送信しないので接続固定は不要」であり、これは**資格情報の cross-instance 漏洩**という問いへの答えである。実際のリスクはそれではない。

| | 何を守るか | 本設計で必要か |
|---|---|---|
| `ConnectionSnapshot` / config 世代 seqlock(Phase 4/5) | GitLab への送信が `(新 URL, 旧 token)` の組で飛ぶこと | **不要**。ホストから GitLab への送信が 1 本も無い(§9) |
| **LS セッションの捕捉と照合(§7.1a)** | **旧 LS セッションで開始した処理の結果が、新セッションに適用されること** | **必要** |

**後者が現実に起きる経路**(初版が見落としていた):

1. インスタンス A で `webviewMetadata()` を発行 → 飛行中にユーザーがインスタンスを切替 → LS が再起動。
2. **世代カウンタは上がらない**(新しい `load()` が無い)。A 時代の解決結果が B のタブへ適用され、**死んだ URI を表示する。**
3. 旧 Browser の遅延 `appReady` が共有 latch を開ける → `wrapper.languageServer` は既に B → **B の webview が ready になる前に B へ `switchView` を送る。**

**秘密が含まれるかどうかとは無関係に発生する。** 死んだ URI を表示するだけでも機能不全である。

→ **§7.1a の捕捉・照合を必須とし、`WebviewBrowserHost` の適用時と `AgenticChatWebViewClient` の送信時の両方でセッション同一性を確認する。** `markNotReady()` によるリセットと併せて二重に守る(リセットが届く前に旧 `appReady` が来る競合を照合が受ける)。

## 12. エラー処理

| 経路 | 反応 |
|---|---|
| `LanguageServerUnavailable` | message ページ「language server の起動待ち」 |
| `Failed` | message ページ「language server に到達できない」。原因は Error Log(**URI は出さない**) |
| `NotAdvertised` | message ページ「この GitLab Language Server は <title> を提供していません」 |
| `NoUri` | 同上の文言。ログ上は `NotAdvertised` と区別する |
| `root/flow` の YAML 前提違反 | ユーザー通知(参照実装と同じ文言意図) |
| `openEditor` / `showView` の失敗 | Error Log + ユーザー通知 |
| **F-a**: `appReady` が 10 秒以内に来ない | `pending` 破棄 + Error Log + ユーザー通知 |
| **F-b**: `appReady` は来たが LS の転送ハンドラ未登録 | **境界つき再送**(§7.4)。**通知しない**(観測不能なため、通知すると成功時にも必ず誤通知になる) |
| LS セッション不一致(§7.1a) | 破棄。**通知しない**(ユーザーが自分で切替えた結果であり、異常ではない)。debug ログのみ |

**silent no-op を作らない。ただし「観測できない事象を起きたことにして通知する」偽陽性も作らない。** 前者は Phase 5B が 9 ラウンド / 12 波を要した「黙って永久に劣化する」クラス、後者は同フェーズが「最悪の誤り方」とした監査の偽陽性である。F-b は前者にも後者にも該当しないよう、**再送で窓を覆いつつ通知は出さず、最終的な確認をユーザーの画面に委ねる**(§23 L-9)。

## 13. タイムアウトとリトライ

| 対象 | タイムアウト | リトライ |
|---|---|---|
| `webviewMetadata()` | 10 秒(`orTimeout`) | **自動リトライしない。** ユーザーがコマンドを再実行すれば新しい解決が走る |
| `appReady` の到達(F-a) | 10 秒 | **リトライしない。** `pending` を破棄して通知する |
| `switchView` の配送(F-b) | — | **境界つき再送 2 回(合計 3 送信)。** 有限回で打ち切り、通知しない |

**メタデータ解決に自動リトライを入れない理由**: リトライは「いつ諦めるか」という状態を追加し、その状態が漏れると Phase 5B と同じクラスになる。ユーザー起動のコマンドであり、再実行が自然な回復手段である。

**`switchView` にだけ再送を入れる理由**: こちらは**確認手段が原理的に存在しない**(ack が無い)ため「諦めの判断」を持てない。有限回に固定し、状態を持たないことで同じ罠を避ける。

## 14. 冪等性

- `WebviewEditorOpener.openOrReload` は冪等。同じ key で複数回呼んでもタブは増えない(key が内容を含むため、内容が違えば別タブ = これも決定的)。
- **`switchView` は同じ `view` について冪等であり、境界つき再送(§7.4)はこの冪等性に依存している。** 同じビューへ複数回切り替えても結果は同じ。
- 再送の窓(数百 ms 規模・有限回)は webview が開いた直後であり、ユーザーが手動で別ビューへ移動してから再送が届く可能性は実質的に無い。**ただし理論上は存在する**ため受容済みの制限として §23 に記録する。
- `markNotReady()` は冪等。

## 15. 並行処理

- **`WebviewBrowserHost` / `AgenticChatWebViewClient` / `WebviewEditorOpener` の可変状態はすべて UI スレッド専有。** 同期プリミティブを使わない。
- メタデータ future は UI スレッド外で消費し、結果適用は `asyncExec` で UI スレッドへマーシャルする。
- **世代カウンタ(latest-wins)は `asyncExec` の中で再チェックする。** `whenComplete` の時点でのチェックでは、キューイングと実行の間に新しい `load()` が走った場合を取りこぼす。
- **世代とは別に LS セッションを照合する(§7.1a)。** 世代は新しい `load()` が無ければ上がらないため、**LS 再起動をまたぐ競合は世代では捕まらない。** 2 つは異なる競合を守っており、片方で他方を代替できない。
- 複数ウィンドウ: `WebviewEditorOpener` は**アクティブページのみ**を見る(§8.1)。key が内容を含むため、他ウィンドウの同一 key タブは同一内容であり更新の必要が無い。**初版の「全 window/page を走査して再ロード」は撤回した**(別ウィンドウのタブに別の内容を配信していた)。
- `appReady` は LS のディスパッチスレッドから届く(`PluginMessageService.dispatch` が `CompletableFuture.supplyAsync`。`PluginMessageService.kt:12`)。`markReady(session)` は **UI スレッドへマーシャルしてから**状態を触る(`session` はバスから運ばれた発信元 identity。§6a)。
- **`markNotReady()` と遅延 `appReady` の競合**: `syncBrowsers` のリセットが走る前に旧セッションの `appReady` が UI キューに入っていることがある。**§6a で伝播された発信元 identity がこれを受ける**(旧 `LanguageServerSession` は `wrapper.currentSnapshot?.session` と一致しないので latch を開けない)。**処理時に現在値を読み直す形では判別できない**(round 1 の誤り。§7.1a)。リセット単独でも閉じない。
- **予約済み再送 callback**: `commandGeneration` により、後続コマンド・`markNotReady()`・セッション変更のいずれでも失効する(§7.4a)。callback は実行時に世代とセッションの**両方**を再照合する。

## 16. 認証と認可

- 本設計はホストから GitLab へ送信しない。認証トークンを扱わない。
- 表示可否の判定は行わない。**`agentic-tabs` / `root/mcp` / `root/flow` に feature-state は存在しない**(`ChatAvailabilityService` が扱うのは `chat` / `agentic_chat` の 2 つのみ。`ChatAvailabilityService.kt:48-52` は他 id に `enabled = false` を返す)。参照実装もこれらの面に可用性ゲートを持たない。
- したがって**可否は「LS が広告しているか」に一元化**される。広告が無ければ §12 の `NotAdvertised` で明示的に伝える。

## 17. ログ・監視・監査

- **解決した webview URI をログに出さない。** LS が発行するローカル URI に認証材が含まれない保証が無いため、含まれない前提で書かない。
- **`?uri=` に載せるユーザーのファイルパスをログに出さない。**
- ログに出すのは **webview id と失敗カテゴリのみ**。例外は種別のみを添える(Phase 4 PR-4 の Codex P1-1「例外添付で `Bearer <token>` が Error Log に漏れた」と同じ回避)。
- Phase 5B のような構造化監査ログは導入しない(GitLab への送信が無く、監査対象の副作用が無いため)。

## 18. 障害時の復旧方法

| 事象 | 復旧 |
|---|---|
| メタデータ解決失敗 | コマンドを再実行(自動リトライなし) |
| LS 再起動 | 次回のコマンドで再解決。**開いたままのタブは、表示内容が旧セッション由来と判定されたときに再 `load` される**(§8.1)。同一セッションなら画面内状態を保つため `activate` のみ |
| `switchView` 未配送(F-b) | **通知は出ない**(観測不能なため。§7.4 / §12 / L-9)。**画面が切り替わらないことを利用者が確認した場合にコマンドを再実行する。** そのときは既に ready なので確実に届く |
| Browser 生成失敗(SWT) | Error Log。ビュー / エディタを閉じて開き直す |

## 19. 既存機能への影響

| 既存 | 影響 |
|---|---|
| `ChatWebviewCatalog` | **無変更** |
| `ChatSelectionResolver` / `ChatAvailabilityService` | **無変更** |
| `GitLabDuoChatWebViewClient`(classic) | **無変更** |
| `LanguageServerBrowserView` | フィールド 1・メソッド 1 の追加、`flushPendingIntents` の引数型変更、`syncBrowsers` に `markNotReady()` の呼び出し 2 箇所。**classic の 2 つの intent の挙動は不変** |
| `AgenticChatWebViewController` | `appReady()` → **`appReady(session: LanguageServerSession)`**(バスから受け取った発信元 identity をそのまま `markReady(session)` へ渡す)。**処理時に current session を読む overload を足さないこと** — 旧 A の遅延通知が B の latch を開く既指摘の競合が再発する |
| `plugin.xml` | 追加のみ(commands / handlers / menus / views / editors) |
| **`PluginMessageService` / `PluginRegistry` / `PluginController`** | **§6a: `dispatch` に第 3 引数、登録の引数制約を緩和。既存 4 形状の挙動は不変**(A1b / A1c で固定) |
| **`GitLabLanguageServerClient`** | `val session` を持ち、4 つの `dispatch` に渡す |
| **`GitLabLanguageServerWrapper`** | **`AtomicReference` な `snapshot`** を唯一の真実にし、`currentSnapshot` を追加。**既存の `languageServer` はそこから導出**(シグネチャ・意味とも維持、既存呼び出し元は無変更)。`registerLanguageServer` は `handle` を受け取る形に。**CAS 版 `unregisterLanguageServer(captured)` を追加** |
| **`GitLabLanguageServerProcessProvider`** | `handle` を 1 度だけ生成し、**`handleRef` へ格納してから** `registerLanguageServer(handle)` で公開する(§6a.3 の ①→② 順序)。**`onExit`(`:127-142`)の identity ガード内と初期化失敗分岐(`:167-168`)から `unregisterLanguageServer(handleRef.get())` を呼ぶ** |
| `build.gradle.kts` / `detekt.yml` | **差分ゼロ** |

**round 2 でスコープが拡大した。** 初版の「既存への変更は 3 点のみ」は成立しない。拡大先は `appReady` の発信元 identity という**そもそも欠けていた情報**であり、classic Duo Chat も通る共有バスに触れる。§6a.5 の keep-behaviour テストを必須とする。

**round 3 で、意図的な既存挙動の変更が 1 件入った(§6a.3b)。**

> **LS がクラッシュした後、または初期化に失敗した後、`GitLabLanguageServerWrapper.languageServer` は死んだ proxy ではなく `null` を返す。**

現状は `unregisterLanguageServer()` が `stopLocked`(`:200`)からしか呼ばれず、**正常停止だけが失効する**。クラッシュ後に「現在のセッション」を問うと死んだ A が返るため、本設計のセッション照合が誤った判定を下す。

**観測される変化**: クラッシュ直後に Duo Chat を開くと「language server の起動待ち」表示になる(従来は死んだ proxy へ送って失敗していた)。**より正しい方向だが挙動変更である**ため、これを紛れ込ませず、専用テスト(A26)と本節での明示で扱う。**この変更が不要と判断される場合、代替は §8.1 の「同一セッションなら activate のみ」という最適化を撤回すること**(その場合コマンド再実行のたびに webview の画面内状態が失われる)。

**`flushPendingIntents` は既存テストが注入している関門である。** 引数型を変えるため、Phase 5B で 3 回起きた「関門を動かすとテストが緑のまま何も覆わなくなる」の再発点になる。§20 の再監査を必須とする。

## 20. テスト方針

| 対象 | headless | 方法 |
|---|---|---|
| `WebviewUriResolver`(sealed 5 分岐 + never-throws + セッション捕捉) | ✅ | TDD |
| `WebviewEditorKey` / `WebviewEditorInput`(`equals`/`hashCode`/`exists`/`getPersistable`。**`queryParams` 差で別 key**) | ✅ | TDD |
| `WebviewEditorTitles.titleFor`(解決成功時のみ改名) | ✅ | TDD |
| **`uri` クエリのビルダ**(§7.3a。`&`/`#`/`=`/`?`/空白/非 ASCII/`+`/`%` + 既存 query・fragment 保存) | ✅ | TDD |
| `AgenticChatWebViewClient`(latest-wins / latch / リセット / **F-a タイムアウト** / **F-b 再送回数** / **セッション照合**) | ✅ | TDD(wrapper は MockK、タイマはシーム注入) |
| `flushPendingIntents` の agentic 分岐 + classic 2 経路の keep-behaviour | ✅ | 既存テストへの追加 |
| `WebviewBrowserHost` / `WebviewEditorPart` / `AgenticTabsView` / `WebviewEditorOpener` | ❌ | **手動検証手順**(PR 本文) |

**共有基盤の keep-behaviour テスト(§6a.5・必須)**: `PluginRegistry` の登録分岐と `PluginMessageService.dispatch` を変更するため、**既存 4 形状が現在とまったく同じに解決されること**を keep-behaviour ラベル付きで固定する。あわせて「末尾が `LanguageServerSession` でない 2 引数メソッドは従来どおり `error()`」を固定し、制約を緩めた方向に穴が開いていないことを示す。

**セッション競合のテスト(headless で可能・必須)**

1. `WebviewUriResolver` が捕捉した `snapshot.session` と `wrapper.currentSnapshot?.session` が**異なる**状態を MockK で作り、`WebviewBrowserHost` の適用が破棄されること(A16)。**世代を変えずに**行う — 世代で代替できないことがこのテストの主張である。
2. **旧セッションの `appReady` が latch を開けないこと(A21)。** **`markReady` を直接呼ぶ形にしない。** production と同じ経路(`dispatch` に旧 `LanguageServerSession` を渡し、controller 経由で `markReady` に届く)で検査する。**round 1 の A17 はテストが旧 proxy を直接渡す形だったため、production が現在値を読んでいても緑になった。同じ形にしないこと。**

**Phase 5B から持ち越す規約(実装ブリーフに明記する)**

1. **テストは「通ること」ではなく「production を壊したら落ちること」。** 実装者に変異前後の実出力を貼らせる。「落ちるはず」を推測で書かせない。
2. **1 テスト 1 性質。** 複数の性質を束ねると変異時に最初の assert しか発火しない。
3. **両側で緑のテストには keep-behaviour ラベルを付ける**(ピンに見せない)。
4. **`flushPendingIntents` の関門を触るので、そこに注入している既存テストを全件再監査する**(どの行に届き、なぜ今も識別的か)。

**検証ベースライン**: `1645 tests completed, 36 failed` + `FAILSET_IDENTICAL (36 failures)` + detekt 0。36 件は headless で実 SWT を要求する既存テストであり、このリポジトリのグリーンベースラインである。

### 20a. 受け入れ条件の識別性(**round 4 で追加。この設計で 3 度繰り返した失敗**)

本レビューで、**「守るべき性質」ではなく「守れている一例」をテストにしていた**受け入れ条件が 3 度見つかった。

| 回 | 条件 | 何が漏れていたか |
|---|---|---|
| round 1 | A17 | テストから旧 proxy を直接 `markReady` に渡す形。**production が処理時に現在値を読んでいても緑になる** |
| round 2 | A21 | 「旧 A **単独**では latch を開かない」しか見ない。**遅延 A が既に開いた B の latch を上書きする**汚染を素通り |
| round 4 | A22 / A25 / A26 | 純関数だけ / 単一スレッドの値だけ / 3 経路を 2 本に畳む(下記) |

**3 度とも同じ機序である: 性質を実現する経路の一部だけを検査し、production の入口から検査していない。** 実装ブリーフに以下を規約として明記する。

1. **production の入口から検査する。** 内部に切り出した純関数を単体で固定するのは構わないが、**それだけを受け入れ条件にしない。** 呼び出し側が「呼ばない / 結果を反転する / 後続の副作用を起こさない」変異で緑のままなら、その条件は性質を守っていない。
   - A22: `WebviewEditorOpener.openOrReload` から検査する(`IWorkbenchPage` と session-aware host をモック)。「別 session → activate + 実際の `load`」「同一 session → activate のみ」。
2. **production の分岐数とテストの本数を一致させる。** 分岐を 1 つ削る変異が**対応する 1 本だけ**を落とすこと。
   - A26: `onExit` の解除だけ実装して初期化失敗分岐の解除を消しても、2 本構成では両方緑のままだった(プロセスは生存し `initialize` だけ失敗する経路で、**未初期化 proxy が `languageServer` に残る**)。**3 経路 = 3 本**にする。
3. **値だけでなく構造も固定する。** 「安全に公開される」のような性質は、値の一致だけを見ても**修飾子を外す変異に反応しない**。
   - A25: (1) reflection で backing field が `AtomicReference` であることを固定する構造テスト、(2) production accessor 経由の一貫性テスト、の**2 本に分ける**。**`AtomicReference` を素の `var` に戻す変異**と、**getter を別 backing field にする変異**の両方で、少なくとも片方が落ちること。
4. **変異前後の実出力を提出させる。** 実装者に「落ちるはず」を推測で書かせない(Phase 5B から継続)。

**この節は実装ブリーフにそのまま転記する。** 上記 4 点は本設計の受け入れ条件すべて(A1b / A1c / A6〜A28)に適用する。

## 21. 受け入れ条件

| # | 条件 | 検証 |
|---|---|---|
| A1 | `ChatWebviewCatalog` / `ChatSelectionResolver` / `ChatAvailabilityService` / `GitLabDuoChatWebViewClient` の差分がゼロ | `git diff` |
| A1b | **`PluginRegistry` / `PluginMessageService` の既存 4 形状**(引数なし通知 / payload つき通知 / 引数なし要求 / payload つき要求)**が現在とまったく同じに解決される** | keep-behaviour ラベル付きテスト(§6a.5) |
| A1c | **末尾が `LanguageServerSession` でない 2 引数ハンドラは従来どおり `error()`** | TDD |
| A2 | `build.gradle.kts` と `detekt.yml` の差分がゼロ | `git diff` |
| A3 | `plugin.xml` に削除行がゼロ | `git diff --numstat` |
| A4 | `.md` の差分がゼロ(ドキュメント非コミット運用) | `git diff` |
| A5 | 新規 OSGi バンドル依存がゼロ | `eclipseDependencies` の差分 |
| A6 | `WebviewUriResolver.resolve` が例外完了しない | TDD |
| A7 | `WebviewEditorInput.getPersistable()` が null / `exists()` が false | TDD |
| A8 | `switchView` が `ready` 前に送信されない | TDD |
| A9 | `pending` が 2 件以上溜まらない(latest-wins) | TDD |
| A10 | classic の 2 つの intent の挙動が不変 | keep-behaviour ラベル付きテスト |
| A11 | 解決した URI とユーザーのファイルパスがログに現れない | 実装レビュー + テスト |
| A12 | **F-a** タイムアウトが発火すると `pending` が破棄され通知経路が呼ばれる | TDD(タイマはシーム注入)。**単独タイマの通常経路のみ** |
| A12b | **旧世代タイマの失効**: ready 前に `history` → 9 秒後に `newConversation` → **10 秒後に発火する旧タイマが新しい `pending` を破棄せず、通知も出さない**。新しい 10 秒の猶予が維持される | TDD。**`commandGeneration` の照合を削除する変異で、A12 は緑のまま A12b だけが落ちること**(§20a 規約 2) |
| A13 | 解決失敗時にタブ名が `fallbackTitle` のまま変わらない | **純関数 `titleFor` だけを検査しない**(§20a 規約 1)。タイトル適用先をシーム化し、**production の解決結果適用入口から失敗分岐を駆動して `setPartName` が呼ばれないこと**まで検査する。**`titleFor` 呼び出しの削除 / 条件反転の変異出力を要求する** |
| A14 | **`queryParams` が異なれば別 key**(同一 key は同一内容) | TDD |
| A15 | **`uri` クエリが特殊文字で分断されず、往復で元の値に戻る**。既存 query / fragment が保存される | **純ビルダだけを検査しない**(§20a 規約 1)。ビルダ単体テストに加え、**`WebviewBrowserHost.load(id, queryParams)` に `Resolved` を返した結果として Browser に渡される最終 URL** をシーム経由で検査する。**ビルダ呼び出しの削除 / 直接連結への変異で落ちること**(A23 / A27 も同じビルダなので同様に扱う) |
| A16 | **旧 LS セッションの解決結果が適用されない**(世代は一致させた状態で) | TDD |
| A17 | ~~旧 LS セッションの `appReady` が latch を開けない~~ → **A21 に差し替え**(round 1 の A17 は「テストから旧 proxy を直接渡す」形で、production が壊れたままでも緑になる非識別的テストだった) | — |
| A18 | **F-b の再送が有限回で打ち切られ、打ち切り時に通知経路を呼ばない** | TDD |
| A20 | **予約済み再送が、後続コマンド / `markNotReady()` / セッション変更のいずれでも 1 回も送信されない** | TDD(§7.4a の 3 操作列) |
| A21 | **旧セッションで発信された `appReady` が latch を開けない**(発信元 identity で判定。処理時の現在値ではない) | TDD。**production と同じ経路で `dispatch` に旧 session を渡して検査する** |
| A22 | **LS 再起動後、開いたままのタブが再解決される**(同一セッションなら再 `load` しない) | TDD。**純関数の判定部分だけを検査しない** — `IWorkbenchPage` と session-aware host をモックし、**production の `openOrReload` エントリポイントから**「別 session → activate + 実際の `load`」「同一 session → activate のみ」を検査する(§20a) |
| A23 | **既存 query の `%26` / `%3D` / `%25` が 1 バイトも変化しない** | TDD(round 1 の実装指定ならここで落ちる) |
| A24 | **`B ready` → 遅延 `A ready` → `B` の `switchView` が即送信される**(遅延 A が latch を汚染しない) | TDD。**A21 では検出できない性質**なので独立したテストにする |
| A25 | **`currentSnapshot` が安全に公開される**(`AtomicReference` な不変 handle。`languageServer` はそこから導出) | **2 本に分ける**: (1) reflection で backing field が `AtomicReference` であることを固定する構造テスト、(2) register / unregister の各遷移で `languageServer === currentSnapshot?.proxy` を**production accessor 経由**で確認するテスト(§20a) |
| A26 | **3 つの失効経路がそれぞれ独立に固定される** | **3 本に分ける**(§20a): (1) active crash(`onExit`)、(2) **active な初期化失敗**、(3) superseded サーバの遅延失敗では失効しない。いずれも `GitLabLanguageServerProcessProvider` の実 callback から駆動する |
| A28 | **CAS の競合**: A の照合と解除の間に B が登録されると、A の解除は**失敗して B の snapshot を残す** | TDD(§20a) |
| A27 | **query 無し + fragment / 空 query / 相対 URI / opaque URI** が §7.3a の規則どおりに扱われる | TDD |
| A19 | `1645 + 新規` / `36 failed` / `FAILSET_IDENTICAL` / detekt 0 | `verify.sh` |

**実機でのみ検証可能な項目(PR 本文に手動検証手順として記載)**

1. `agentic-tabs` ビューが開き、webview が描画される
2. `root/mcp` がエディタタブとして開く
3. YAML エディタで `gl.openFlowBuilder` → Flow Builder タブが開き、LS 側の保存が効く
4. YAML でないエディタで `gl.openFlowBuilder` → エラー通知が出て何も開かない
5. **Duo Chat ビューを閉じた状態から `showHistoryView` → 履歴が表示される**(= §5.3a の競合を再送が覆えている)
6. Duo Chat ビューが開いて agentic 表示中に `showHistoryView` → 即座に切り替わる
7. LS 未起動状態で各コマンド → message ページ / 通知が出る(無反応にならない)
8. **`root/mcp` のタブを開いたまま LS を再起動 → 同じコマンドを実行 → 新しい URI で再ロードされる**(タブが残っていても死んだ URI のままにならない。round 2 で発見した経路)
9. ワークベンチ再起動後に webview エディタタブが復元されない
10. **ウィンドウ A で YAML-1 の Flow Builder を開いた状態で、ウィンドウ B から YAML-2 の Flow Builder を開く** → **B に YAML-2 のタブが開き、A の YAML-1 タブは変化しない**(§7.3 の key 変更が効いている。**初版はここで A が黙って YAML-2 に置き換わっていた**)
11. 同じ YAML から 2 回 `gl.openFlowBuilder` → タブが増えず、既存タブが前面に来る
12. **ファイル名に `&` / 空白 / 日本語を含む YAML** から Flow Builder を開く → 正しいファイルが開き、LS 側の保存がそのファイルに効く
13. `root/mcp` を 2 回開く → タブが 1 つのまま
14. **インスタンス切替の直後に各コマンドを実行** → 新しい LS の URI が表示され、古い URI のページが残らない

## 22. 移行方法 / ロールバック方法

- **移行不要。** 永続データ・設定キー・スキーマの変更が無い。
- **ロールバック** = PR の revert。既存 Duo Chat 経路への変更が §7.4 の 3 点に限定されるため、revert 面は小さい。
- 部分ロールバックが必要な場合、`plugin.xml` の該当 `menuContribution` / `command` を外せば起動口が消え、新規クラスは到達不能になる(既存挙動には影響しない)。

## 23. 未決事項

**U-1 は解消した(round 1)。** `appReady` が先・`webviewReady` が後であることを webview アセットの実ソースで確定した(§5.3a)。設計はこの確定を前提に §7.4 で故障モードを 2 分割して書き直した。**「未確定だから防御的に受ける」ではなく、「確定した順序に対して competing する経路を有限再送で覆う」形になっている。**

**U-2 `AgenticChatWebViewController.focusChange` の生存**
§5.4 の実測から pin 8.80.0 では呼ばれないと考えられるが、**本設計では削除も変更もしない**(スコープ外の挙動変更を作らないため)。

**U-3 `root/mcp` と既存 MCP 設定コマンドの関係**
`gl.mcp.openUserConfig` / `.openWorkspaceConfig`(PR #23)は `mcp.json` をエディタで開くもので、`root/mcp`(MCP Dashboard webview)とは別機能である。**同じステータスメニューに 3 つ並ぶことになるが、参照実装も 3 コマンドを別々に持つ。** ラベルで区別する(`Open MCP Dashboard` vs `Open MCP User/Workspace Configuration`)。

**U-4 `agentic-tabs` ビューの初期表示タイミング**
`ViewPart` は `showView` で初めて生成されるため、LS 未起動時に開くと message ページが出る。**LS が ready になったときの自動再解決は行わない**(既存 Duo Chat ビューは `refreshDuoChatWindow()` フックを持つが、それはチャット可用性のためのもの)。ユーザーがビューを開き直せば再解決される。**この判断は「無反応」ではない**(message ページが理由を示す)が、UX として弱い点は認識している。

## 23a. 意図的に受容した制限(round 1 で追加)

| # | 制限 | 根拠 |
|---|---|---|
| L-1 | `root/flow` は **YAML ごとに 1 タブ**(参照実装の「id あたり 1 パネル」から逸脱) | あちらは 1 extension ウィンドウ内の同一性であり、Eclipse の複数ワークベンチウィンドウを根拠づけない(§7.3)。タブ数の上限はユーザーが開いた YAML 数で、通常のエディタと同じ性質 |
| L-9 | **F-b(`switchView` の未配送)は通知しない** | ack が無く観測不能。打ち切り時に通知すると**成功時にも必ず誤通知**が出る = Phase 5B が「最悪の誤り方」とした偽陽性。最終確認はユーザーの画面で行われ、再実行で確実に届く |
| L-10 | **境界つき再送の窓中にユーザーが手動で別ビューへ移動すると、再送が引き戻す** | 窓は webview を開いた直後の数百 ms・有限回。理論上のみ存在する |
| L-11 | LS セッション不一致で結果を破棄する際は**通知しない** | ユーザー自身の切替操作の結果であり異常ではない。debug ログのみ |

## 24. 想定されるリスク

| # | リスク | 影響 | 緩和 |
|---|---|---|---|
| R1 | §5.3a の競合で `switchView` が LS に捨てられる | コマンド 2 件が届かない | **F-b = 境界つき再送**で窓を覆う(§7.4)。冪等性に依存。実機検証項目 5。**初版の「タイムアウトで必ず気づける」は成立していなかった**(`markReady` がタイマも解除していたため)。現行は F-a と F-b を別機構で受ける |
| R2 | `flushPendingIntents` の引数型変更で既存テストが緑のまま無効化される | classic の回帰が検出されない | §20 の全件再監査を必須化。keep-behaviour ラベル |
| R3 | ~~`equals` が `queryParams` を無視することの誤解~~ → **key に含めたので消滅**(§7.3) | — | クラスごと閉じた。残るのは「実装者が初版の設計を参照して id のみの key を書く」ことだけで、A14 が固定する |
| R4 | LS の webview URI に秘密が含まれる | ログ経由の漏洩 | §17 で URI を一切ログに出さない |
| R5 | SWT コードが headless で一切検証できない | 実機でのみ露見 | 実装 `opus` × レビュー `fable`(SWT・UI スレッド担当)+ 手動検証 14 項目 |
| R6 | `org.eclipse.ui.editors` 拡張の新規追加で実機のみの解決失敗 | エディタが開かない | 依存は既に Require-Bundle 済み(`build.gradle.kts:177`)。Phase 4 PR-2 の `org.eclipse.core.expressions` と同型の罠がないことを確認済み |
| R7 | ワークベンチ再起動時にエディタが復元され死んだ URI を指す | 壊れたタブ | `getPersistable() = null` + `exists() = false`。受け入れ条件 A7・実機検証項目 9 |
| R8 | **LS 再起動をまたぐ結果適用 / readiness 汚染** | 死んだ URI 表示・早すぎる `switchView` | 解決側 = §7.1a のスナップショット捕捉。readiness 側 = **§6a の発信元 identity 伝播**(round 1 の「処理時に現在値を読む」形は no-op だった)。A16 / A21・実機検証項目 14 |
| R10 | **§6a が共有バスを変更する**(classic Duo Chat も通る) | 既存 4 形状の解決が壊れると Duo Chat 全体が無音死 | A1b / A1c の keep-behaviour テスト。`parameterCount` 制約は**緩める方向**なので「誤ったハンドラが黙って登録される」側も固定する(§6a.5) |
| R11 | **予約済み再送 callback の失効漏れ** | 後続コマンドが古い指示に引き戻される | §7.4a の `commandGeneration`。A20 の 3 操作列。**`pending` にだけ latest-wins を効かせて callback に効かせないのが round 1 の誤りだった** |
| R12 | **開いたままのタブが旧セッションの URI を保持** | 死んだページが残る | §8.1 のセッション由来判定。A22・実機検証項目 8。**round 1 で key に内容を含めた修正が開いた経路** |
| R13 | **遅延 `appReady` が新しい latch を上書きする** | B 向けコマンドが F-a まで落ちる | §7.4 の「現在 session と一致するときだけ格納」。A24。**A21 では検出できない**ので独立テスト |
| R14 | **`currentSnapshot` の可視性不足** | 旧 A / null を観測し正しい結果を破棄 | `AtomicReference` な不変 handle(A25)。**組の原子性・可視性・条件付き更新の原子性は 3 つとも別問題**(round 3 で 2 つ目、round 4 で 3 つ目に気づいた) |
| R16 | **identity 照合と解除が原子的でない** | **旧 A の解除が新 B の snapshot を消し、既存の全読者が LS 不在になる** | `compareAndSet(captured, null)`。初期化 callback は `lifecycleLock` の外で走る(`:165-193`)ため、ロックに頼れない。A28 |
| R17 | **受け入れ条件が識別的でない** | production を壊してもテストが緑 | **§20a**(3 度繰り返した失敗の規約化)。入口から検査 / 分岐数とテスト本数の一致 / 構造も固定 / 変異出力の提出 |
| R15 | **クラッシュ後に snapshot が stale** | dead URI のタブが activate されるだけ | identity-aware unregister(§6a.3b)。A26。**既存挙動の変更を伴う** |
| R9 | **`uri` クエリの直列化を実装者が文字列連結で書く** | 特殊文字を含むパスで別ファイルを開く / 初期化失敗 | §7.3a の規則 + A15 の文字集合テスト。既存 `PathSegmentEncoder` / `SearchQueryBuilder` は**流用できない**ことを明記済み |

## 25. 確認できた範囲と、追加情報がなければ判断できない事項

**実ソース / バイナリ / webview アセットで確認済み**: webview id・title・コマンド ID の導出規則・`switchView` のペイロード形状・`switchView` 転送ハンドラの登録条件・**`appReady` と `webviewReady` の送信順序(§5.3a)**・agentic / classic それぞれのホスト向けフォワーダの内容・**`agentic-tabs` がハンドラを 1 つも登録しないこと(§5.4a)**・`root/mcp` と `root/flow` がホスト側処理を要さないこと・`root/flow` の保存が LS 内で完結すること・`org.eclipse.ui.editors` が Require-Bundle 済みであること・既存 `plugin.xml` の該当箇所。

**round 1 で新たに確定したもの**(いずれも「未確認だから安全側に倒す」から「確認したので断定する」に変わった):

| 旧 | 新 | 根拠 |
|---|---|---|
| U-1「順序は確認できなかった」 | **`appReady` が先・`webviewReady` が後** | `bin/webviews/agentic-duo-chat/assets/index.91120bee.js` の `notifyAppReady()` |
| 「`agentic-tabs` が host にメッセージを送るかは未確認」 | **送らない。LS 側の `setup` はハンドラを 1 つも登録しない** | LS 8.80.0 の `DefaultAgenticTabsWebviewPlugin` + 参照実装 `setup_webviews.ts:152-158` |

**なお確認できていない事項**:

- **LS の webview URI に認証材が含まれるか。** 含まれる前提で扱い、ログに一切出さない(§17)。この前提を緩めない。
- **`root/mcp` / `root/flow` / `agentic-tabs` の webview アプリが、LS が公開していないホスト機能を必要とするか。** LS 側に転送経路が無い以上、ホストは経路の欠落要因になりえない。**仮に必要であっても VSCode 側も同じ状態であり、パリティは保たれる**(参照実装もこれらに handler を登録しない)。
- **`LanguageServerSession` の伝播が classic Duo Chat の既存経路に与える影響。** 設計上は既存ハンドラの形状が新しい分岐に入らないため無変更だが、**これは keep-behaviour テスト(A1b / A1c)で経験的に示すべきものであり、設計書の主張だけでは足りない。**
- **境界つき再送の回数と間隔の最適値。** 2 回 / 数百 ms は §5.3a の非対称(ホスト往復 vs 同一ソケット隣接送信)から見て十分に余裕があるという判断であり、実測に基づく値ではない。**実機検証項目 5 で妥当性を確認する。足りなければ回数を増やすのではなく、LS への ready 信号追加を上流に要求する。**
