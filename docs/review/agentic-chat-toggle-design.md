# Agentic Chat 切り替え(表示切替方式)設計書

- 対象リポジトリ: `sumikof/gitlab-eclipse-plugin`
- ベースブランチ: `develop`(本設計時点 tip `f823875`)
- 関連 issue: ロードマップ #8 / パリティ台帳 #7 / 不具合調査 #22(本機能の動機)
- 関連実装 PR: なし(本設計レビュー後に作成予定)
- ステータス: **レビュー用ドラフト(マージ禁止)**

---

## 1. 背景と目的

### 背景
- 本プラグインの Duo Chat ビュー(`LanguageServerBrowserView`)は、Language Server(以下 LS)が広告する webview のうち **`duo-chat-v2`(クラシック / 非 Agentic Chat)を 3 箇所でハードコード**して表示している(`views/LanguageServerBrowserView.kt:75`、`chat/webview/GitLabDuoChatWebViewClient.kt:14`、`GitLabDuoChatWebViewController.kt:21`)。Agentic Chat の実装は src 内に存在しない。
- GitLab の製品変更(2026-05-21 / GitLab 19.0)により、**Duo Core 系サブスクリプションではクラシック(非 Agentic)Duo Chat が廃止**され、Agentic Chat に置き換えられた。
- その結果、Duo Core 環境ではクラシック Chat のチャット送信がサーバー側エンタイトルメント判定で拒否され、**`M3006`(GitLab Duo Chat is not included in your GitLab Duo subscription)** が返る(#22)。Web では Agentic Chat が使えるがクラシックは使えない、という実機報告と整合する。
- LS には `ClassicChatLicenseCheck` と `AgenticChatSupportCheck` が別々に存在し(`gitlab-lsp.d.ts` 参照、後述)、クラシックと Agentic のエンタイトルメントが独立していることが製品コードで確認できる。

### 目的
- Eclipse プラグインに **Agentic Chat 表示対応**を追加し、**クラシック Chat と切り替えて使えるようにする**(既存のクラシック Chat を Agentic に置き換えるのではなく、両者を選択可能にする)。
- これにより Duo Core 環境でも Duo Chat が利用可能になり、#22 の実利用不能状態を解消する。

---

## 2. 対象範囲(スコープ)

- LS が広告する chat webview(`duo-chat-v2` / `agentic-duo-chat`)を **単一ビュー内で表示切替**できるようにする。
- 切替 UI(ビューのツールバー上のラジオ式ドロップダウン)。
- 選択の永続化(Preference 記憶と復元)。
- 利用可能な webview の自動判定(LS の広告有無)と初期選択。
- 純ロジック層(webview 一覧抽出)の単体テスト。

## 3. 対象外(このスライスでは実装しない)

- **Agentic 固有コマンド**(`gl.agenticChat.startNewConversation` / `gl.agenticChat.showHistoryView` 等)への配線。既存の New Conversation / Close / Focus はクラシック向けのまま据え置く。
- **Agentic の disabled 理由文言**(`agentic_chat` feature state)の詳細表示。今回は「LS が広告しなければトグルに出さない」で可用性を扱う。
- Duo Workflow / Agent Platform v2 / Flow Builder / MCP dashboard 等その他 webview(Phase 6 の範囲)。
- LS 再起動やプロトコル定数以外の LS 側挙動変更。

---

## 4. 現在の課題

- 単一ビューがクラシック webview id をハードコードしており、Agentic を表示する経路が存在しない。
- 可用性(どの chat が使えるか)は現状クラシック前提の feature-state のみで判断しており、Agentic の存在を考慮していない。

---

## 5. 要件

### 機能要件
- FR1: ビューは、LS が `webviewMetadata()` で広告した chat webview(`duo-chat-v2`, `agentic-duo-chat`)を切替候補として提示する。広告されない webview は候補に出さない。
- FR2: ユーザーはビューのツールバーから表示する chat を選択できる。選択切替でチャット内容(webview 状態)が失われないこと。
- FR3: 初期選択は「保存された前回選択が現在の候補に在ればそれ、無ければ候補先頭」。両方が候補かつ保存値が無い場合はクラシックを既定とする。
- FR4: 選択は Preference に永続化し、次回ビュー生成時に復元する。
- FR5: chat webview が 1 つも広告されない場合は、既存の disabled/feature-state 表示を維持する。

### 非機能要件
- NFR1: ディレクトリ構成・ビルドシステムを変更しない(CLAUDE.md 共通制約)。既存 `views/` パッケージ内に追加する。
- NFR2: 純ロジック層は TDD 可能な単体テストを備える。
- NFR3: 新規必須依存を追加しない。

---

## 6. 前提条件と制約

- **プロトコル定数(実 LS ソースで確定済み。gitlab-lsp v9 パッケージ)**:
  - クラシック webview id = `duo-chat-v2`、title = `GitLab Duo Chat`(`package/out/main-bundle-node.js`)。
  - Agentic webview id = `agentic-duo-chat`、title = `GitLab Duo Agentic Chat`(同 `main-bundle-node.js` 内 `oPt="agentic-duo-chat",rji="GitLab Duo Agentic Chat"`。VSCode 拡張 `src/common/constants.ts:18` の `AGENTIC_CHAT_WEBVIEW_ID = 'agentic-duo-chat'` と一致)。
  - 補足: `agentic_chat`(feature id、`gitlab-lsp.d.ts:219`)および `agentic-chat-no-support`(state check id、同 `:221`)は **webview id ではない**ので混同しないこと。
- LS の webview 取得は既存の `GitLabLanguageServerWrapper.languageServer?.webviewMetadata()` を用いる(戻り値 `List<WebviewInfo?>`、各要素に `id` / `title` / `uris`)。
- 実機依存(SWT `Browser` / `StackLayout` / view toolbar)は headless devcontainer では検証不能。手動検証手順を実装 PR に記載しユーザー実機で確認する。
- VSCode 参照コピー(`out/gitlab-vscode-extension`)は読み取り専用。

---

## 7. システム構成

```
LanguageServerBrowserView (ViewPart)  … 単一ビュー
├─ ルート Composite (StackLayout)
│   ├─ Browser(duo-chat-v2)     … 候補にあれば生成、URL ロード
│   ├─ Browser(agentic-duo-chat)… 候補にあれば生成、URL ロード
│   └─ disabled/empty ページ     … 候補ゼロ時
├─ view toolbar: ChatSelector(ラジオ式ドロップダウン)… topControl 切替
└─ 依存:
    ├─ GitLabLanguageServerWrapper.webviewMetadata()
    ├─ ChatWebviewCatalog (新規・純ロジック)
    ├─ ScopedPreferenceStore (選択の永続化)
    └─ DuoChatStateService (既存: classic の feature-state / disabled 理由)
```

## 8. コンポーネントの責務

- **`ChatWebviewCatalog`(新規・純ロジック)**
  - 入力: `webviewMetadata()` の結果 `List<WebviewInfo?>`。
  - 出力: chat 対象 id(`duo-chat-v2`, `agentic-duo-chat`)のみを、**LS の広告順を保ったまま**抽出した `List<ChatWebviewEntry(id, title, uri)>`。
  - null 要素・uri 欠落・未知 id・重複を除外する。副作用なし・UI 非依存 → 単体テスト対象。
- **`LanguageServerBrowserView`(改修)**
  - `StackLayout` の親を保持し、カタログの各エントリに対応する `Browser` を生成・URL ロード。
  - 初期選択の決定(§9)、topControl の切替、選択変更時の Preference 保存。
  - 候補ゼロ時は既存 `loadUnauthenticatedWebview` 相当の disabled 表示にフォールバック。
- **ChatSelector(view toolbar 貢献)**
  - 候補エントリをラジオ項目として提示。選択で View にコールバックし topControl を差し替える。候補が 1 つ以下なら非表示または無効。
- **Preference**
  - `PreferenceConstants.DUO_CHAT_SELECTED_WEBVIEW`(String、値 = webview id)。`PreferenceInitializer` に既定値(空 or `duo-chat-v2`)。

## 9. 処理フロー

### 9.1 ビュー生成 / リフレッシュ
1. `webviewMetadata()` を既存のタイムアウト(10 秒 `completeOnTimeout`)で取得。
2. `ChatWebviewCatalog.extract(metadata)` → 候補リスト。
3. 候補が空 → disabled/feature-state ページを表示して終了。
4. 候補が非空 → 各エントリの `Browser` を生成し `setUrl(uri)`。
5. **初期選択**:
   - 保存値(Preference)が候補 id に含まれる → それを選択。
   - 含まれない、かつ候補が複数 → クラシック(`duo-chat-v2`)が候補にあればそれ、無ければ先頭。
   - 候補が 1 つ → それを選択。
6. `StackLayout.topControl` を選択エントリの `Browser` に設定し `layout()`。

### 9.2 切替
1. ユーザーが ChatSelector で別エントリを選択。
2. `topControl` を差し替え、`layout()`。既存 `Browser` は破棄しない(状態保持)。
3. 選択 id を Preference に保存。

## 10. API / インターフェース(想定シグネチャ)

```kotlin
data class ChatWebviewEntry(val id: String, val title: String, val uri: String)

object ChatWebviewCatalog {
  // 既知の chat webview id を広告順で抽出。null/uri欠落/未知id/重複を除外。
  fun extract(metadata: List<WebviewInfo?>?): List<ChatWebviewEntry>
  val CHAT_WEBVIEW_IDS: List<String> // ["duo-chat-v2", "agentic-duo-chat"] 広告順の優先には使わない
}
```

- View 側の選択決定は純粋関数として切り出す(テスト可能化):
  `fun resolveInitialSelection(candidates: List<ChatWebviewEntry>, saved: String?): ChatWebviewEntry?`

## 11. データモデル

- `ChatWebviewEntry(id, title, uri)`: 表示・切替の単位。
- Preference: `gitlab.duoChat.selectedWebview`(String)。取り得る値は webview id または空。

## 12. トランザクション境界 / 冪等性 / 並行処理

- トランザクション概念なし(永続化は Preference の単一キー書き込み)。
- 冪等性: リフレッシュは何度実行しても、同じ metadata に対し同じ候補・同じ初期選択に収束する(純関数)。既存 `Browser` の再生成方針は「候補集合が変化した時のみ作り直す」ことで多重生成を避ける(実装詳細、§19 未決 U2)。
- 並行処理: `webviewMetadata()` は既存同様 join で同期取得。UI 反映は **UI スレッド**上で行う(`Browser` / `StackLayout` は SWT スレッド制約)。View のコールバックは UI スレッド前提。バックグラウンド完了からの UI 更新が必要な箇所は `asyncExec` でホップする(既存 `DuoChatStateService.update` と同方針)。

## 13. 認証と認可

- 本機能は認証情報を扱わない。chat の可用性・エンタイトルメント(ライセンス / シート / M3006 判定)は **LS + サーバー側**の責務であり、プラグインは LS が広告した webview を表示するのみ。
- 認証状態変化(既存 `AuthenticationStateService`)でビューがリフレッシュされる導線は現行のまま利用する(§19 未決 U3 で確認)。

## 14. ログ / 監視 / 監査

- 既存の `logger`(`Platform.getLog`)を踏襲。候補ゼロ時・URL 欠落時は既存同様 `logger.error` を出す。
- LS 側詳細は `language_server.log`(`${state.dir}/language_server.log`)に出力される既存構成を利用(本機能で変更なし)。

## 15. 障害時の復旧

- `webviewMetadata()` タイムアウト / null → 候補ゼロ扱いで disabled 表示(既存挙動と同等)。次回リフレッシュで回復。
- 保存値が不正(存在しない id)→ フォールバック規則(§9.1-5)で安全側に倒す。

## 16. 既存機能への影響

- **クラシック Chat**: 既定挙動を維持(両方可用時はクラシック既定)。既存の New Conversation / Close / Focus はクラシックのまま。
- **feature-state / disabled 表示**: 候補ゼロ時の経路として維持。`DuoChatStateService` の変更は最小(参照のみ)。
- **plugin.xml**: ビューは増やさず、view toolbar 貢献のみ追加。
- ハードコードされていた `duo-chat-v2` 参照は、View 内は動的化するが、`chat/webview/GitLabDuoChatWebViewController`(PluginController の id)側の扱いは §19 未決 U1 で確認。

## 17. 移行方法 / ロールバック方法

- 移行: 追加的変更。既存ユーザーは初回クラシック既定(両方可用時)で従来同様。Duo Core は Agentic に自動フォールバック。
- ロールバック: 実装 PR を revert すれば従来の単一クラシック表示に戻る(Preference キーは無害な残存)。

## 18. テスト方針 / 受け入れ条件

### テスト
- `ChatWebviewCatalog.extract`: 空 / null 要素 / 既知 2 種のみ抽出 / 広告順保持 / uri 欠落除外 / 未知 id 無視 / 重複除外。
- `resolveInitialSelection`: 保存値ヒット / 保存値ミス→クラシック優先 / クラシック不在→先頭 / 単一候補 / 候補空→null。
- Preference 既定・保存/復元。
- SWT(`StackLayout`/`Browser`/toolbar)は headless 不可 → **手動検証手順を PR に記載**。

### 受け入れ条件
- AC1: LS が両 webview を広告する環境で、ツールバーからクラシック↔Agentic を切替でき、切替後も各チャットの表示状態が保持される(手動)。
- AC2: Agentic のみ広告される環境(Duo Core)で、ビューが Agentic を初期表示し、チャット送信で M3006 が出ない(手動)。
- AC3: 前回選択が次回ビュー生成時に復元される(手動 + Preference 単体テスト)。
- AC4: chat webview ゼロ時に既存 disabled 表示になる(手動)。
- AC5: `./gradlew build` で detekt green、テストは既定の SWT 環境失敗(36 件)を超える新規失敗が無い。

## 19. 未決事項(推測で確定しない)

- **U1**: `GitLabDuoChatWebViewController`(`PluginController("duo-chat-v2")`)/ `GitLabDuoChatWebViewClient(pluginId="duo-chat-v2")` は webview メッセージバス配線用の登録。Agentic を表示するだけなら Agentic 用 PluginController 登録が必要か(メッセージ受信に依存する機能があるか)を、LS の webview 契約で確認する。表示のみで足りるなら本スライスでは追加しない。
- **U2**: 候補集合が変化した際の `Browser` 再生成/破棄ポリシー(全再生成 vs 差分更新)。状態保持と実装単純さのトレードオフ。
- **U3**: リフレッシュのトリガー(認証状態変化・feature-state 変化)が Agentic 可用性の変化を確実に拾うか。既存 `refresh()` 呼び出し経路の棚卸しが必要。
- **U4**: 両方可用かつ保存値なしの既定を「クラシック」とするか「Agentic」とするか。現設計はクラシック既定(現行踏襲)。運用方針次第で変更可。
- **U5**: view toolbar 上の切替 UI 形態(ラジオ式ドロップダウン vs 2 トグルボタン)。Eclipse の慣行・省スペース観点で確定する。

## 20. 想定されるリスク

- R1: SWT `StackLayout` 上の複数 `Browser` 常駐によるリソース/メモリ増(webview は最大 2)。影響は限定的だが実機で確認。
- R2: 切替時の `Browser` 状態保持が LS webview 側の再描画挙動に依存(`retainContextWhenHidden` 相当の保証がプラグイン側に無い)。実機検証で状態喪失が起きないか確認(AC1)。
- R3: プロトコル定数(webview id)が LS バージョンで変わるリスク。現行 pin(v8/v9 で `duo-chat-v2` / `agentic-duo-chat` を確認済み)だが、将来の LS 更新時に再確認が必要。
- R4: Duo Core 判定はサーバー側で行われ、プラグインからは webview 広告有無でしか観測できない。Agentic すら広告されない構成では本機能でも救済できない(想定内・対象外)。
