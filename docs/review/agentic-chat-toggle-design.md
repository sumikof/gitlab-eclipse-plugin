# Agentic Chat 切り替え(表示切替方式)設計書

- 対象リポジトリ: `sumikof/gitlab-eclipse-plugin`
- ベースブランチ: `develop`(本設計時点 tip `f823875`)
- 関連 issue: ロードマップ #8 / パリティ台帳 #7 / 不具合調査 #22(本機能の動機)
- 関連実装 PR: なし(本設計レビュー後に作成予定)
- ステータス: **レビュー用ドラフト(マージ禁止)**
- 改訂: r2(Codex P1×4 反映)

---

## 0. 改訂履歴

- r1: 初版。
- r2: Codex レビュー P1×4 を反映。
  - P1-A(§16): classic の feature-state で全体を遮断しない「webview 単位の可用性モデル」に再設計。
  - P1-B(旧 U1): Agentic の webview メッセージ契約を**実装前提の確定事項**へ格上げ。共有ホストメッセージ契約を実ソースで確定し、`agentic-duo-chat` 用コントローラ登録を設計に含めた。
  - P1-C(旧 U2): 候補変更時の `Browser` ライフサイクルと状態保持規則を確定。
  - P1-D(§9.1): metadata 取得を UI スレッド外へ。世代番号 latest-wins + dispose ガード + 進行中表示を明記。

---

## 1. 背景と目的

### 背景
- Duo Chat ビュー(`LanguageServerBrowserView`)は、LS が広告する webview のうち **`duo-chat-v2`(クラシック / 非 Agentic)を 3 箇所でハードコード**して表示している(`views/LanguageServerBrowserView.kt:75`、`chat/webview/GitLabDuoChatWebViewClient.kt:14`、`GitLabDuoChatWebViewController.kt:21`)。Agentic Chat 実装は src 内に存在しない。
- GitLab の製品変更(2026-05-21 / GitLab 19.0)で **Duo Core 系ではクラシック Duo Chat が廃止**され Agentic Chat に置換。結果、Duo Core 環境ではクラシックのチャット送信がサーバー側で拒否され **`M3006`(GitLab Duo Chat is not included in your GitLab Duo subscription)** が返る(#22)。
- LS には `ClassicChatLicenseCheck` と `AgenticChatSupportCheck` が別々に存在(`gitlab-lsp.d.ts`)。クラシックと Agentic のエンタイトルメントは独立している。

### 目的
- Agentic Chat 表示対応を追加し、**クラシックと切り替えて使えるようにする**(置換ではなく併用)。Duo Core でも Duo Chat を利用可能にし #22 を解消する。

---

## 2. 対象範囲(スコープ)

- LS が広告する chat webview(`duo-chat-v2` / `agentic-duo-chat`)を**単一ビュー内で表示切替**。
- 切替 UI(ビューのツールバー)。選択の永続化と復元。
- **webview 単位の可用性判定**(広告有無 + feature-state)と初期選択。
- **Agentic を実際に使えるようにする最小限のメッセージ配線**: `agentic-duo-chat` 向けの共有ホストメッセージ契約(下記 §10.2)を担うコントローラ登録。
- 純ロジック層(webview 一覧抽出・初期選択決定)の単体テスト。
- metadata 取得の非ブロッキング化(UI スレッドを止めない)。

## 3. 対象外(このスライスでは実装しない)

- **Agentic 固有コマンド**: `gl.agenticChat.startNewConversation` / `gl.agenticChat.showHistoryView`、および webview への `switchView` 通知(`duo_agentic_chat_commands.ts`)。これらは繰延。既存の New Conversation / Close / Focus はクラシック向けのまま据え置く。
  - 注: これは §2 の「共有ホストメッセージ契約」とは**別レイヤ**。共有契約(getCurrentFileContext / insertCodeSnippet / copy / openLink 等)は全 chat webview の基盤であり対象内、Agentic 固有コマンドのみ対象外。
- Duo Workflow / Agent Platform v2 / Flow Builder / MCP dashboard 等その他 webview(Phase 6)。
- LS 側挙動の変更、LS 再起動。

---

## 4. 現在の課題

- `setBrowserContent()` は metadata より先に classic の feature-state を評価し、engaged なら disabled ページへ全体分岐する(`LanguageServerBrowserView.kt:62-68`)。Duo Core(classic engaged / agentic 可)では Agentic に到達できない。
- webview↔プラグインのメッセージルータは `pluginId` 完全一致で handler を引き(`lsp/plugins/PluginRegistry.kt`)、登録済みは `duo-chat-v2` のみ。`agentic-duo-chat` 宛ては `PluginMessageService.dispatch` が `"No plugin registered for $route. Skipping."` で破棄する(`PluginMessageService.kt:14`)。
- metadata 取得が UI スレッド上で `join()` 同期(`LanguageServerBrowserView.kt:70-74`)。LS 無応答時に最大 10 秒 UI を止める。

---

## 5. 要件

### 機能要件
- FR1: ビューは LS が広告した chat webview(`duo-chat-v2`, `agentic-duo-chat`)を切替候補として提示する。広告されない webview は候補に出さない。
- FR2: ユーザーはツールバーから表示 chat を選択できる。**同一 id・同一 URI が維持される限り**、切替後もチャット状態(webview 状態)を保持する(§12 に保持保証の境界を定義)。
- FR3: 初期選択は「保存値が候補かつ有効ならそれ → 無ければ有効な候補の先頭 → 全て無効なら候補先頭」。両方有効で保存値なしはクラシックを既定(§9.2)。
- FR4: 選択は Preference に永続化し復元する。
- FR5: **classic が feature-state で無効でも、Agentic が有効候補なら表示を止めない**(webview 単位の可用性)。chat webview が 1 つも広告されない場合のみ全体 disabled 表示。
- FR6: `agentic-duo-chat` webview が送る共有ホストメッセージ(§10.2)をプラグインが受理・処理する。未対応メッセージは既存同様 warn ログのみ(クラッシュしない)。
- FR7: metadata 取得中に UI スレッドをブロックしない。取得中は進行中表示、失敗/タイムアウトは再試行契機を提供する。

### 非機能要件
- NFR1: ディレクトリ構成・ビルドシステム不変(既存 `views/` `chat/webview/` パッケージ内に追加)。
- NFR2: 純ロジック層は TDD。
- NFR3: 新規必須依存を追加しない。

---

## 6. 前提条件と制約(プロトコル定数=実 LS/参照ソースで確定)

- webview id(gitlab-lsp v9 `package/out/main-bundle-node.js`):
  - クラシック = `duo-chat-v2`(title `GitLab Duo Chat`)。
  - Agentic = `agentic-duo-chat`(title `GitLab Duo Agentic Chat`。`oPt="agentic-duo-chat",rji="GitLab Duo Agentic Chat"`。VSCode `src/common/constants.ts:18` の `AGENTIC_CHAT_WEBVIEW_ID='agentic-duo-chat'` と一致)。
  - `agentic_chat`(feature id、`gitlab-lsp.d.ts:219`)/ `agentic-chat-no-support`(check id、同 `:221`)は **webview id ではない**。
- **共有 chat ホストメッセージ契約**(VSCode `src/common/webview/duo_chat/duo_chat_handlers.ts:104-118`。`registerDuoChatHandlers` が **`duo-chat-v2` と `agentic-duo-chat` の両方に登録**):
  - request: `getCurrentFileContext`
  - notification: `insertCodeSnippet` / `copyCodeSnippet` / `copyMessage` / `showMessage` / `focusChange` / `appReady` / `openLink` / `openUrl`
  - Eclipse の classic コントローラ(`GitLabDuoChatWebViewController`)は `openUrl` を除き実装済み。`openUrl` は `openLink` のエイリアス(同ファイルのコメント)。
- feature-state 通知 `$/gitlab/featureStateChange` は featureId ごとに届く(`GitLabLanguageServerClient.kt:64-77`)。現状 `chat` のみ `DuoChatStateService` に配線。`agentic_chat` は未配線(else で無視)。
- webview 取得は既存 `GitLabLanguageServerWrapper.languageServer?.webviewMetadata()`(戻り値 `CompletableFuture<List<WebviewInfo?>>`、各要素 `id`/`title`/`uris`)。
- 実機依存(SWT `Browser`/`StackLayout`/view toolbar)は headless 検証不可。手動検証手順を実装 PR に記載。
- VSCode 参照コピー(`out/gitlab-vscode-extension`)は読み取り専用。

---

## 7. システム構成

```
LanguageServerBrowserView (ViewPart)  … 単一ビュー
├─ ルート Composite (StackLayout)
│   ├─ "loading" ページ            … metadata 取得中
│   ├─ Browser[duo-chat-v2]        … 有効候補にあれば生成
│   ├─ Browser[agentic-duo-chat]   … 有効候補にあれば生成
│   ├─ disabled ページ(per-webview 理由文言)
│   └─ empty ページ(候補ゼロ)
├─ view toolbar: ChatSelector      … topControl 切替
└─ 依存:
    ├─ GitLabLanguageServerWrapper.webviewMetadata()  (非ブロッキング消費)
    ├─ ChatWebviewCatalog        (新規・純ロジック)
    ├─ ChatSelectionResolver     (新規・純ロジック: 初期選択)
    ├─ ChatAvailabilityService   (classic=既存 DuoChatStateService / agentic=agentic_chat 状態)
    ├─ ScopedPreferenceStore     (選択永続化)
    └─ AgenticChatWebViewController (新規: pluginId="agentic-duo-chat" 共有契約)
```

## 8. コンポーネントの責務

- **`ChatWebviewCatalog`(新規・純ロジック)**: `webviewMetadata()` 結果から chat 対象 id(`duo-chat-v2`,`agentic-duo-chat`)のみを LS 広告順で抽出、null/uri 欠落/未知 id/重複を除外し `List<ChatWebviewEntry(id,title,uri)>` を返す。
- **`ChatSelectionResolver`(新規・純ロジック)**: 候補 + 各 webview の有効/無効 + 保存値 から初期選択 id を決める(§9.2)。
- **`ChatAvailabilityService`(可用性集約)**: webview 単位に「広告有無 × feature-state(有効/無効 + 理由)」を返す。classic は既存 `DuoChatStateService`(featureId `chat`)、agentic は featureId `agentic_chat` の状態(§10.3 で client に配線を追加)。
- **`LanguageServerBrowserView`(改修)**: metadata を非ブロッキング取得(§9.1)。有効候補ごとに `Browser` を生成、`StackLayout` で切替。per-webview の disabled/理由表示。選択保存。
- **ChatSelector(view toolbar 貢献)**: 候補をラジオ提示。選択で topControl 差し替え。候補 ≤1 は非表示/無効。
- **`AgenticChatWebViewController`(新規)**: `PluginController("agentic-duo-chat")`。§10.2 の共有契約を処理(classic 実装を共有化)。
- **Preference**: `PreferenceConstants.DUO_CHAT_SELECTED_WEBVIEW`(String=webview id)。`PreferenceInitializer` に既定。

## 9. 処理フロー

### 9.1 ビュー生成 / リフレッシュ(非ブロッキング・latest-wins)
1. 現在の世代番号を +1 し `gen` を捕捉。ルート StackLayout を "loading" ページに。
2. `webviewMetadata()`(既存 `completeOnTimeout(10s)`)を **`join()` せず** `whenComplete` で消費(LS スレッド)。
3. 継続処理は UI スレッドへ `asyncExec`。その中で **(a) View が dispose 済みでない、(b) `gen` が最新世代と一致** を確認。不一致/dispose 済みなら破棄(古い結果で上書きしない)。
4. `ChatWebviewCatalog.extract(metadata)` → 候補。各候補の有効/無効を `ChatAvailabilityService` で付与。
5. 候補が空 → empty ページ。全候補が無効 → 選択候補の disabled ページ(理由文言)。それ以外 → §12 の規則で `Browser` を用意し、`ChatSelectionResolver` の初期選択を topControl に。
6. 取得失敗/タイムアウトの空結果時は disabled/empty へ進めつつ、ツールバーの Refresh で再試行可能(既存 `refresh()` を利用)。

### 9.2 初期選択(`ChatSelectionResolver`)
- 保存値が候補にあり **有効** → それ。
- 無ければ **有効な候補の先頭**(広告順)。両方有効で保存値なしは `duo-chat-v2` を優先。
- 有効候補が無ければ候補先頭(全 disabled 表示の対象)。
- Duo Core(classic 無効・agentic 有効)では agentic が選ばれる(AC2)。

### 9.3 切替
1. ユーザーが ChatSelector で別候補を選択 → `topControl` 差し替え + `layout()`。既存 `Browser` は破棄しない。
2. 選択 id を Preference 保存。

## 10. API / インターフェース

### 10.1 純ロジック
```kotlin
data class ChatWebviewEntry(val id: String, val title: String, val uri: String)
object ChatWebviewCatalog {
  val CHAT_WEBVIEW_IDS: List<String> // ["duo-chat-v2","agentic-duo-chat"]
  fun extract(metadata: List<WebviewInfo?>?): List<ChatWebviewEntry>
}
data class ChatAvailability(val id: String, val enabled: Boolean, val disabledReason: String?)
object ChatSelectionResolver {
  fun resolve(candidates: List<ChatWebviewEntry>, availability: Map<String, ChatAvailability>, saved: String?): String?
}
```

### 10.2 Agentic コントローラの共有メッセージ契約(§6 で確定)
`AgenticChatWebViewController : PluginController("agentic-duo-chat")` が処理:
- `@PluginRequest("getCurrentFileContext")`
- `@PluginNotification`: `insertCodeSnippet` / `copyCodeSnippet` / `copyMessage` / `showMessage` / `focusChange` / `appReady` / `openLink` / `openUrl`
- 実装は既存 classic コントローラのロジックを共有化(重複回避のため共通基底 or 委譲。ディレクトリ構成は不変、`chat/webview/` 内に追加)。
- 併せて classic 側にも `openUrl`(= `openLink` エイリアス)を追加し両者の欠落を解消。
- payload 検証は既存 `PluginMessageHandler` の型パースに準拠(パース不能は既存どおり warn で skip)。

### 10.3 agentic feature-state 配線
`GitLabLanguageServerClient.gitlabFeatureStateChange` の `when(featureId)` に `"agentic_chat" -> ...` を追加し、agentic の有効/無効 + 理由を `ChatAvailabilityService` が参照できる状態に保持する(classic の `DuoChatStateService` と対称の最小実装)。

## 11. データモデル
- `ChatWebviewEntry(id,title,uri)` / `ChatAvailability(id,enabled,disabledReason)`。
- Preference `gitlab.duoChat.selectedWebview`(String、webview id または空)。

## 12. Browser ライフサイクルと状態保持規則(P1-C 確定)

- **同一性は webview `id`** で判定(uri ではない)。View は `Map<id, Browser>` を保持。
- リフレッシュで候補集合が変化した場合:
  - **id 継続 & URI 不変** → 既存 `Browser` を再利用(**状態保持**)。
  - **id 継続 & URI 変化** → 当該 `Browser` を `setUrl(newUri)` で再読込(状態はリセットされる=明示的再読込。AC で受容)。
  - **id 消滅** → 当該 `Browser` を `dispose()`。それが選択中なら §9.2 で選択を再決定し Preference を更新。
  - **id 追加** → `Browser` 生成 + URL ロード。
- `topControl` は選択 id の `Browser`。ChatSelector の選択値は新候補に整合(消滅時はフォールバック)。
- **状態保持を保証するのは「id 継続 & URI 不変」時のみ**。URI 変化・id 再生成時は状態リセットが避けられないため、その旨を受け入れ条件(AC1/AC6)で明示する。

## 13. トランザクション境界 / 冪等性 / 並行処理(P1-D 確定)

- 永続化は Preference 単一キー書き込みのみ(トランザクション概念なし)。
- 冪等性: 同一 metadata + 同一保存値に対し、リフレッシュは同一候補・同一初期選択・同一 `Browser` 集合に収束(純ロジック + id ベース差分)。
- 並行処理:
  - metadata 取得は UI スレッド外(`whenComplete`)。UI 反映は `asyncExec`。
  - **世代番号(latest-wins)**: refresh ごとに `generation` を増分し、継続処理は最新世代のみ適用。認証/feature-state 変化で多重 refresh が重なっても古い結果で上書きしない(#16 と同方針)。
  - **dispose ガード**: 継続処理は View/`Browser` が生存している場合のみ SWT 操作を行う。
- SWT オブジェクト操作は必ず UI スレッド上(既存 `currentDisplay.asyncExec` 方針)。

## 14. 認証と認可
- 本機能は認証情報を扱わない。エンタイトルメント(license/シート/M3006)は LS + サーバー責務。プラグインは広告 + feature-state を表示に反映するのみ。
- 認証状態変化(既存 `AuthenticationStateService`)→ ビュー refresh の導線を利用(§18 U3 で棚卸し)。

## 15. ログ / 監視 / 監査
- 既存 `logger`(`Platform.getLog`)踏襲。候補ゼロ・URI 欠落・未対応メッセージは warn。
- 未対応 agentic メッセージは `PluginMessageService` の既存 warn(`No plugin registered ...`)に現れる。共有契約を登録することで正常系はこの warn が出ないことを確認する。
- LS 詳細は既存 `language_server.log`(本機能で変更なし)。

## 16. 障害時の復旧
- metadata タイムアウト/null → empty/disabled 表示 + Refresh で再試行。UI はブロックしない。
- 保存値不正(存在しない/無効 id)→ §9.2 フォールバック。
- Agentic メッセージ handler 内例外 → 既存の `supplyAsync` + warn で握り(`PluginMessageService.kt`)、UI は落とさない。

## 17. 既存機能への影響
- クラシック: 既定挙動維持(両方有効時 classic 既定)。既存 New Conversation/Close/Focus は classic のまま。
- feature-state: 全体遮断をやめ **per-webview** 化。`DuoChatStateService` は参照利用 + agentic 用に対称サービスを追加(既存 classic 挙動は不変)。
- plugin.xml: ビューは増やさず view toolbar 貢献のみ追加。
- webview メッセージ配線: `agentic-duo-chat` コントローラ追加 + classic への `openUrl` 追加(後方互換・既存挙動不変)。
- ハードコード `duo-chat-v2`(View 内)は動的化。`GitLabDuoChatWebViewController`/`Client` の classic 用途は維持。

## 18. 未決事項(推測で確定しない)
- U2(旧 U3): refresh トリガー(認証/feature-state 変化)が agentic 可用性変化を確実に拾うか。既存 `refresh()` 経路の棚卸しを計画時に実施。
- U4: 両方有効・保存値なしの既定を classic とするか agentic とするか。現設計は classic。運用判断で変更可。
- U5: view toolbar 切替 UI 形態(ラジオ式ドロップダウン vs 2 トグル)。Eclipse 慣行で確定。
- U6: agentic の `getCurrentFileContext` 応答が classic と同一 payload 型でよいか(LS agentic 契約の request 応答形を計画時に v9 で最終確認)。
- U7: agentic webview がプラグイン→webview 通知(例 `switchView`)を前提に初期描画するか。前提なら最小の client 側 notify が必要か計画時に確認(対象外コマンドと切り分け)。

## 19. テスト方針 / 受け入れ条件

### テスト(headless 可能)
- `ChatWebviewCatalog.extract`: 空/null 要素/既知 2 種抽出/広告順保持/uri 欠落除外/未知 id 無視/重複除外。
- `ChatSelectionResolver.resolve`: 保存値有効ヒット / 保存値無効→有効先頭 / classic 無効・agentic 有効→agentic / 両有効保存値なし→classic / 有効ゼロ→候補先頭 / 候補空→null。
- 世代番号 latest-wins の単体テスト(古い世代の反映が破棄される)。
- Preference 既定・保存/復元。
- SWT(`StackLayout`/`Browser`/toolbar/実メッセージ往復)は headless 不可 → 手動検証手順を PR に記載。

### 受け入れ条件
- AC1: 両 webview 有効環境で classic↔agentic を切替でき、**id 継続 & URI 不変の間**は各チャット状態が保持される(手動)。
- AC2: Agentic のみ有効(Duo Core)で、ビューが Agentic を初期表示しチャット送信で M3006 が出ない(手動)。
- AC3: 前回選択が次回ビュー生成時に復元される(手動 + 単体)。
- AC4: chat webview ゼロ時に empty/disabled 表示(手動)。
- AC5: **classic が feature-state 無効でも agentic 有効なら表示継続**し agentic を選択できる(手動)。
- AC6: 候補集合変化(認証/LS 再起動等で URI 変化)時に、選択・topControl・Preference が整合し、URI 変化した webview は明示的に再読込される(手動)。
- AC7: Agentic で `getCurrentFileContext`/`insertCodeSnippet`/`copyCodeSnippet`/`copyMessage`/`openLink`/`openUrl` が機能し、`No plugin registered` warn が正常系で出ない(手動 + ログ確認)。
- AC8: LS 無応答時に metadata 取得が UI をブロックしない(手動: LS 停止状態でビューを開いても Eclipse が固まらない)。
- AC9: `./gradlew build` で detekt green、テストは既定 SWT 環境失敗(36 件)を超える新規失敗なし。

## 20. 想定されるリスク
- R1: `StackLayout` 上の複数 `Browser` 常駐によるリソース増(最大 2)。実機確認。
- R2: 状態保持は「id 継続 & URI 不変」に限定(§12)。LS webview 側の再描画挙動に依存する部分は実機確認(AC1/AC6)。
- R3: webview id / 共有メッセージ契約が LS バージョンで変わるリスク。現行 pin(v8/v9)で確認済みだが LS 更新時に再確認。
- R4: Agentic すら広告されない構成では本機能でも救済不可(想定内・対象外)。
- R5: agentic の共有契約に classic と異なる payload/型が混じる可能性(U6)。計画時に v9 で最終確認し、差分があればコントローラで吸収。
