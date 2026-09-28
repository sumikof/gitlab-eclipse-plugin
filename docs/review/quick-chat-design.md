# D4 Quick Chat 設計書

- 対象リポジトリ: `sumikof/gitlab-eclipse-plugin` / ベース: `gitlab-ls-9.3.0` @ `0f5b074`
- 台帳: #7 D4(Quick Chat 本体 3 件 ❌ → ✅、77 → 80/85)/ ロードマップ: #8 / フェーズ: #14
- 技術調査 S1〜S6 と確定事項: #20 のコメント(2026-09-28)
- 関連する実装 PR: なし(本設計のレビュー後に 2 本作成する)
- 参照実装: `gitlab-workflow` v6.85.3(以下 REF = `out/gitlab-vscode-extension/src/common`)
- GitLab 本体の参照: `gitlab-org/gitlab` master `dfabdc105a1509d52077e41ea78082b2a6a4d197`(以下 GL)
- 自リポジトリの参照: 以下 E = `src/main/kotlin/com/gitlab/eclipse`

---

## 1. 背景と目的

VSCode 版の Quick Chat は、エディタの行にぶら下がる小さなチャット欄で、選択したコードについて GitLab Duo に質問し、回答のコードをコピー / 挿入できる機能である。Eclipse 版には無い(台帳 D4 の残り 3 件)。

#95 で MR に依存しないエディタ内スレッド UI(`E/views/inlinethread`)ができたので、これを再利用して Quick Chat を実装する。
本設計の目的は、(1) GitLab へ質問を送り回答を取得する通信・セッション基盤、(2) エディタ上の popup UI とコード操作、の 2 層を、実装 PR 2 本に分けられる形で定義することである。

## 2. 対象範囲

| ID | 機能 | VSCode の対応 | 本設計での実現 |
|---|---|---|---|
| F1 | Quick Chat を開く / 閉じる | `gl.openQuickChat` / `gl.openQuickChatWithShortcut`(Alt+C)/ `gl.closeQuickChat`(Esc) | コマンド「Open Quick Chat」(`M1+M3+C`、エディタのコンテキストメニュー)/「Close Quick Chat」、popup 内の Esc・閉じるボタン |
| F2 | 送信 | `gl.sendQuickChat` / `gl.sendQuickChatDup` | popup の [Send] ボタンと popup 内の `Ctrl+Enter`(mac は `Cmd+Enter`)。`/clear` `/reset` を含む |
| F3 | スニペットのコピー / 挿入 | `gl.copyCodeSnippetFromQuickChat` / `gl.insertCodeSnippetFromQuickChat` | 回答中のコードブロックごとの [Copy] [Insert] ボタン |
| T1 | 回答の取得 | ActionCable 購読 | **`aiMessages` のポーリング**(確定事項) |
| G1 | 利用可否 | `config.gitlab.duoChat.enabled && gitlab:chatAvailable && gitlab:chatAvailableForProject` | 既存の `duo_chat_enabled`(LS の `chat` 機能状態) |
| G2 | GitLab バージョン | 4 種類の mutation をバージョンで切替 | **17.10 以上のみ**。未満は明確なエラー(確定事項) |

## 3. 対象外

- ActionCable によるストリーミング(後続サイクル。§26 に申し送り)。回答は完成形で一度に表示する。
- ガター アイコン、選択行の「(Alt+C) Duo Quick Chat」ヒント、入力欄のスラッシュ補完、コードアクション「Fix with GitLab Duo」(REF `quick_chat/quick_chat_hint.ts`、`quick_chat_gutter_icon.ts`、`utils.ts:25-47`、`code_actions/`)。台帳の 3 件に含まれない UI 装飾・別機能。
- popup の折りたたみ / 再展開(VSCode の collapse)。Eclipse の popup は閉じる = 会話を終える(確定事項 4)。
- 追加コンテキスト(`additionalContext`)。参照実装の Quick Chat も送らない(REF `quick_chat/quick_chat.ts:258`)。
- GitLab 17.10 未満への対応(確定事項 1)。
- 1 ウィンドウで複数の Quick Chat を同時に開くこと(参照実装も単一。REF `quick_chat/comment_thread_service.ts:25`)。
- Agentic Chat への対応。Quick Chat は classic Duo Chat の機能である。
- テレメトリ(`gl.quickChat*Telemetry`)。台帳でも対象外。

## 4. 現在の課題

- Quick Chat のコマンド・UI・通信のいずれも無い。
- aiAction / aiMessages を呼ぶコード、GitLab のバージョンを取得するコード、Project の GraphQL ID(gid)を得るコードが無い(`E` を grep して 0 件)。
- `views/inlinethread` は MR 向けの固定値を持つ: popup のタイトル「Merge Request Thread — line N」(`E/views/inlinethread/InlineThreadPopup.kt:133`)、送信ボタンの「Comment」/「Reply」(`:334-337`)、本文は素の読み取り専用 `Text`(`:304`)。`isSubmittable` は `mergerequests` パッケージにある(`InlineThreadState.kt:3`、`E/mergerequests/discussions/CommentInputDialog.kt:122`、#97)。
- 参照実装の Quick Chat には欠陥がある。タイムアウトもキャンセルも無く、エラー時はログを出すだけで読み込み表示が残り、Send が無効のままになる(REF `quick_chat/quick_chat_state.ts:595-601`、`quick_chat.ts:221`)。移植時に引き継がない。

## 5. 要件

### 5.1 機能要件

- **FR-1 開く**: テキストエディタで「Open Quick Chat」を実行すると、そのエディタの選択範囲の終端行の下に Quick Chat の popup が開き、入力欄にフォーカスが入る。
- **FR-2 同じ位置での再実行**: 同じウィンドウで、同じエディタ・同じ行の Quick Chat が既に開いていれば、新しく作らずにその popup を前面に出す。
- **FR-3 置き換え**: 同じウィンドウで別のエディタ・別の行の Quick Chat が開いていれば、既存のセッションを終えて(§9.5)新しい popup を開く。送信中でも置き換える(確定事項 3)。MR の popup とは枠を共有しない。
- **FR-4 送信**: 入力が空白だけでなければ [Send] または `Ctrl+Enter` で送信する。送信中は入力欄と [Send] を無効にし、会話欄に質問と「回答を待っています」の表示を出す。
- **FR-5 回答**: 回答が得られたら「回答を待っています」を回答に置き換え、入力欄を再び有効にし、送った下書きを消す(送信後に編集されていなければ)。
- **FR-6 続けての質問**: 同じ popup での 2 回目以降の送信は、同じ会話(threadId)の続きとして送る。
- **FR-7 `/clear` / `/reset`**: 入力が(前後の空白を除き、大文字小文字を区別せず)`/clear` なら会話欄を空にし、`/reset` なら会話欄に区切り「New chat」を足す。どちらも以後の質問を新しい会話として送る。§9.4。
- **FR-8 失敗**: どの失敗(利用不可、バージョン不足、通信失敗、サーバのエラー、タイムアウト)でも、会話欄に理由を表示し、入力欄と [Send] を再び有効にする。下書きは残す。
- **FR-9 閉じる**: Esc、タイトルバーの閉じるボタン、「Close Quick Chat」、エディタを閉じる / 入力が差し替わる、のいずれでも popup を閉じて会話を終える。未送信の下書きはコピーを提示する(既存の #95 の挙動)。
- **FR-10 コピー**: 回答中の各コードブロックの [Copy] で、そのコードをクリップボードへ書き、書けたら通知する。
- **FR-11 挿入**: 回答中の各コードブロックの [Insert] で、そのコードを **popup を付けたエディタの現在の選択範囲**に挿入する(選択が空ならカーソル位置)。確定事項 5。
- **FR-12 利用可否**: classic Duo Chat が使えないとき(`duo_chat_enabled` が偽)は「Open Quick Chat」を無効にし、コンテキストメニューに出さない。

### 5.2 非機能要件

- **NFR-1 UI スレッド**: popup と会話の状態は UI スレッドだけで触る。通信は UI スレッドの外で行い、UI への戻りは `asyncExec` だけで行う(`syncExec` は使わない。E `navigation/ClipboardWriter.kt:43-44` に記録されたデッドロックの前例)。
- **NFR-2 古い結果を捨てる**: 閉じた後・置き換えた後・`/clear` `/reset` の後に届いた結果は、どの経路でも会話欄に反映しない。
- **NFR-3 依存を増やさない**: 新しいバンドルやライブラリを追加しない。Markdown ライブラリも Browser も使わない(確定事項 6)。
- **NFR-4 ログ衛生**: 質問・回答・コード・ファイル名・選択テキスト・トークンをログに出さない。
- **NFR-5 既存挙動の維持**: MR の popup(#95 / #98)の挙動を変えない。`views/inlinethread` への追加は既定値で従来挙動になる。

## 6. 前提条件と制約

### 6.1 共通制約(#8)

ディレクトリ構成・ビルドシステムを変えない。新規コードは既存の `com.gitlab.eclipse.*` パッケージ体系の中に置く。プロトコル定数は実ソースで確定したものだけを使う(本書 §6.3〜6.4 で出典を併記)。

### 6.2 検証上の制約

devcontainer は headless で、SWT の popup・フォーカス・キー操作・GitLab への実接続を動かせない。これらは PR 本文の手動検証手順(ユーザー実機、classic Duo Chat が使えるアカウントあり)で確認する。

### 6.3 GitLab の確定事実

| # | 事実 | 出典 |
|---|---|---|
| K1 | `aiMessages` は `threadId` も `conversationType` も無いと legacy スレッド(`DUO_CHAT_LEGACY`)を読む。`threadId` があればそのスレッドを読む | GL `ee/lib/gitlab/llm/thread_ensurer.rb:12-16`、`ee/app/graphql/resolvers/ai/chat_messages_resolver.rb:26-31` |
| K2 | 途中の断片(`chunk_id` 付き)は保存されない。`aiMessages` で読めるのは完成したメッセージだけ | GL `ee/lib/gitlab/llm/graphql_subscription_response_service.rb`(`save_message?`: `!response_message.chunk_id`) |
| K3 | `aiAction` の `threadId` を返す形は GitLab 17.10 から | REF `chat/gitlab_chat_api.ts:19-21`(`MINIMUM_CONVERSATION_TYPE_VERSION = '17.10.0-pre'`)、`:117-152` |
| K4 | `AiActionInput.threadId` を省くと、指定の `conversationType` で新しいスレッドを作る | gitlab.com スキーマ(2026-09-28 取得)の `AiActionInput.threadId` の説明 |
| K5 | `platformOrigin` は任意の String。サーバ側で値を検証しない | GL `ee/app/graphql/mutations/ai/action.rb:23-25, 178` |
| K6 | `AiActionPayload{ errors: [String!]!, requestId: String, threadId: AiConversationThreadID }` | gitlab.com スキーマ |
| K7 | `AiMessage{ requestId, role: AiMessageRole!, content, contentHtml, errors: [String!], threadId, timestamp: Time!, chunkId, type, ... }`、`AiMessageRole = USER / ASSISTANT / SYSTEM` | gitlab.com スキーマ |
| K8 | `AiConversationsThreadsConversationType` に `DUO_QUICK_CHAT` がある | gitlab.com スキーマ |
| K9 | `AiCurrentFileInput{ fileName: String!, selectedText: String!, contentAboveCursor: String, contentBelowCursor: String }` | gitlab.com スキーマ |
| K10 | `Metadata.version` と `Project.id`(GraphQL ID)がある | gitlab.com スキーマ |
| K11 | `Project.duoFeaturesEnabled: Boolean`「Indicates whether GitLab Duo features are enabled for the project.」(16.9、Experiment) | gitlab.com スキーマ(2026-09-28 取得) |

### 6.4 参照実装の確定事実

| # | 事実 | 出典 |
|---|---|---|
| R1 | Quick Chat は ActionCable 購読のみ。ポーリングしない | REF `quick_chat/quick_chat.ts:220-262` |
| R2 | ポーリングの前例はサイドバーの代替経路: 5000 ms 間隔 × 最大 20 回、最初の 1 件で終了、タイムアウト文言「Reached timeout while fetching response.」、1 リクエスト 25000 ms | REF `chat/api/pulling.ts:4-25`、`chat/gitlab_chat_api.ts:311-317, 334-347`、`constants.ts:13` |
| R3 | 送る文脈は `currentFile` だけ。選択が空なら `currentFile` を送らない。`contentAboveCursor` / `contentBelowCursor` は選択の前後のファイル全体 | REF `chat/gitlab_chat_file_context.ts:17-43` |
| R4 | 2 回目以降は応答の `threadId` を付けて送る | REF `quick_chat/quick_chat.ts:225-230, 260` |
| R5 | `/clear` `/reset` は `trim().toLowerCase()` で判定し、`question` と `threadId` だけの `aiAction` を送り、threadId を破棄する | REF `quick_chat/quick_chat.ts:275-287`、`quick_chat_state.ts:164-196`、`chat/gitlab_chat_api.ts:319-325` |
| R6 | コードブロックは正規表現で抽出(3 連バッククォート + `\w+` タグのみ)。Copy はクリップボードへ書いて「Code copied to clipboard!」、Insert はアクティブなエディタの選択へ | REF `quick_chat/utils.ts:77, 89-99`、`chat/gitlab_chat.ts:129-136`、`chat/insert_code_snippet.ts:8-27` |
| R7 | 利用可否は LS の `chat` 機能の状態。使えないときはコマンドを隠すだけ | REF `chat/chat_state_manager.ts:45-75`、`package.json` L223-224, 262-271 |

### 6.5 Eclipse / 自リポジトリの確定事実

| # | 事実 | 出典 |
|---|---|---|
| E1 | `GitLabGraphQlClient.execute(query, variables, type, connection, timeout)` は任意の GraphQL を送れる。2xx 以外は `GitLabApiException`、トップレベル `errors` は `GraphQlException`、`data` 欠落は `JsonSyntaxException` | E `api/GitLabGraphQlClient.kt:64-133` |
| E2 | `captureConnection()` / `captureConnectionIf(pred)` はインスタンス URL とトークンの一貫した組を返す。`authFingerprint` はトークンの SHA-256 の先頭で、**OAuth のトークン更新で変わる** | E `api/GitLabApiClient.kt:37-40, 183-219` |
| E3 | HTTP は JDK `HttpClient` の同期 `send`(`BodyHandlers.ofString()`)。既存のコードには実行中のリクエストを止める仕組み(`runInterruptible` など)が無い | E `api/http/GitLabHttpClient.kt:18-19`、`grep runInterruptible` = 0 件 |
| E4 | 共有のコルーチン scope は `SupervisorJob` ではない。取りこぼした例外は兄弟の処理まで止める | E `utils/WorkspaceModule.kt:34`、前例 `snippets/handlers/InsertSnippetHandler.kt:66-72` |
| E5 | `DuoChatStateService` が LS の `chat` 機能状態から `duo_chat_enabled` を提供し、`getFirstEngagedCheck()?.details` で理由の文言を返す。`gitlab.duoChat.enabled` の設定は LS に渡り `chat-disabled-by-user` として反映される | E `chat/DuoChatStateService.kt:10-45`、`lsp/GitLabLanguageServerClient.kt:98-121`、`lsp/configuration/GitLabLanguageServerConfigurationService.kt:153` |
| E6 | `InlineThreadState` は UI スレッド専用。`beginSubmit` / `onAttemptFinished` / `onSucceeded` のチケット方式で busy を管理する。`threadId == NEW_THREAD_ID` のときだけ成功で popup を閉じる | E `views/inlinethread/InlineThreadState.kt:9-11, 75-124` |
| E7 | `InlineThreadPopup` は `Shell(TOOL|RESIZE|TITLE|CLOSE)`。Esc・閉じるボタン・`EditorGoneListener`(エディタを閉じる / 入力の差し替え)で閉じ、閉じるときに未送信の下書きを `preserveDrafts` に渡す | E `views/inlinethread/InlineThreadPopup.kt:86-99, 127-167, 218-249`、`EditorGoneListener.kt:14-27` |
| E8 | 既定スキームで `M1+M3+C` は未使用(workbench / ide / editors / texteditor / JDT 3.39 / debug.ui 3.22 / search 3.19 / team.ui 3.12.200 / compare 3.12.200 / EGit 7.8 の plugin.xml を確認)。ESC は既に `rejectSuggestion` が `org.eclipse.ui.textEditorScope` で使う | E `src/main/resources/plugin.xml:497-502`、S5 調査 |
| E9 | コピーには `ClipboardWriter.writeAndNotify(text, notify)` がある(書けた後にだけ通知) | E `navigation/ClipboardWriter.kt:60-107` |
| E10 | Duo Chat の挿入 `InsertCodeSnippetService` と整形 `CodeFormatter` は**アクティブな**エディタと選択を読む。`CodeFormatter` は JDT の `K_COMPILATION_UNIT` で整形し、`IndexOutOfBoundsException` 以外の失敗(`format` が `null` を返す等)を扱わない | E `chat/services/InsertCodeSnippetService.kt:15-30`、`utils/CodeFormatter.kt:16-78` |
| E12 | **実測(JDK 21.0.12、ループバックの偽サーバ)**: `HttpClient.send` は、応答が来ない場合も、ヘッダの後に本文が止まった場合も、スレッドの割り込みから約 10 ms で `InterruptedException` を投げて戻る。`HttpRequest.timeout(2s)` は応答が来ない場合は 2 秒で `HttpTimeoutException` になるが、ヘッダの後に本文が止まった場合は効かない(8 秒後もブロックしたまま) | 2026-09-28 に devcontainer で実行した検証プログラムの出力(PR #99 のコメントに記録) |
| E13 | OAuth のトークン更新は `captureConnection` → `tokenManager.getToken()` → `OAuthTokenProvider.refreshTokenIfExpired()` → ScribeJava 8.3.3 の `refreshAccessToken` で同期に走る。ScribeJava の `JDKHttpClient` は `HttpURLConnection` を使い、接続・読み取りのタイムアウトは設定があるときだけ設定する。`GitLabOAuthService` は設定していない(= 無期限)。**実測**: タイムアウトなしの `HttpURLConnection` は割り込みでも戻らない | E `authentication/OAuthTokenProvider.kt:24-32, 69-87`、`authentication/GitLabOAuthService.kt:31-40, 75-83`、`scribejava-core-8.3.3.jar` の `JDKHttpClient`(`getConnectTimeout` が null でないときだけ `setConnectTimeout`)、同上の検証プログラム |
| E14 | `kotlinx-coroutines-core` 1.10.2 に `runInterruptible` がある(コルーチンの取り消しでブロック中のスレッドを割り込む) | `build.gradle.kts:137`、jar 内 `kotlinx/coroutines/InterruptibleKt.class` |
| E11 | リポジトリのファイルから `namespaceWithPath` と `instanceUrl` を得る `GitLabProjectUrlResolver.resolveContextForFile(file)` がある | E `navigation/GitLabProjectUrlResolver.kt:18-25, 94` |

## 7. システム構成

```
[エディタ] --Open Quick Chat--> QuickChatPopups(SWT。ウィンドウごとに popup を最大 1 つ)
                                     |
                                     +-- InlineThreadPopup(既存。一般化して再利用)
                                     +-- QuickChatHost(InlineThreadHost の実装。薄いアダプタ)
                                              |
                     ---------------- UI スレッド ----------------
                                              v
                                   QuickChatSession(SWT 非依存・UI スレッド専用)
                                      | 会話状態 QuickChatConversation
                                      | 期限の監視(UI タイマー)と唯一の終端 finishOnce
                                      v 起動 / 取り消し          ^ runOnUi(asyncExec)
                     ---------------- 背景 ----------------------
                                   QuickChatRuntime(バンドルに 1 つ)
                                      | 専用 scope = SupervisorJob + Dispatchers.IO
                                      | 手放した処理の数の上限(DetachedJobs)
                                      v
                                   QuickChatService(1 回の送信。送信ゲート SendGate)
                                      |-- QuickChatPreflight(version / project)
                                      |-- QuickChatApi(aiAction / aiMessages)
                                      +-- QuickChatPoller
                                              v
                                   GitLabGraphQlClient(既存。CA / mTLS / プロキシ)
```

新規パッケージは `com.gitlab.eclipse.chat.quickchat`(SWT 非依存の通信・会話層)と `com.gitlab.eclipse.chat.quickchat.ui`(SWT 層・ハンドラ)。`views/inlinethread` には追加だけを行う。専用の executor は持たない(§15)。

## 8. コンポーネントの責務

### 8.1 SWT 非依存層(PR-1)

| コンポーネント | 責務 |
|---|---|
| `QuickChatApi` | §11.2 の GraphQL 文書を `GitLabGraphQlClient` で送る。応答をデータ型に変換する。判断はしない |
| `QuickChatPreflight` | 会話の結び付きごとに 1 回、GitLab のバージョン確認と、アンカーのプロジェクトの確認(gid・`duoFeaturesEnabled`)をまとめて行う(§9.2.2)。結果は値として返し、会話への保存は UI スレッドが行う |
| `QuickChatPoller` | `requestId` と `threadId` で `aiMessages` を問い合わせ、ASSISTANT のメッセージが現れるまで待つ(§9.3)。時計と待ち合わせは注入できる |
| `QuickChatService` | 1 回の送信を「接続の取得 → プロジェクトの解決と preflight → 送信ゲート → aiAction → ポーリング」の順に実行し、結果を 1 つの `QuickChatOutcome`(§12.3)で返す。ブロックする呼び出しはすべて `runInterruptible` の中で行う。会話オブジェクトには触れない。例外を外に出さない(`CancellationException` を除く) |
| `SendGate` | 「`aiAction` を送ったか」を表す、1 回の送信ごとの原子的な状態(§12.4)。UI スレッドと背景処理が共有する唯一の可変状態 |
| `QuickChatRuntime` | バンドルに 1 つ(Koin の `single`)。Quick Chat 専用の scope(`SupervisorJob() + Dispatchers.IO`)、手放した処理の数 `DetachedJobs`(§15.3)、単調時計を持つ。バンドル停止時に scope を取り消す。全ウィンドウの会話がこの 1 つを共有する |
| `QuickChatSession` | 1 つの popup の会話と送信の進行役。**UI スレッド専用**。`QuickChatConversation` を持ち、送信の開始(`submit`)、期限の監視、唯一の終端 `finishOnce`(§9.2.4)、会話の終了(`end`)を行う。UI への戻り(`runOnUi`)と UI タイマー(`scheduleOnUi`)は注入する。画面への反映は `QuickChatView`(`render(model)` / `released(ticket, succeeded)`)を通す |
| `QuickChatConversation` | 会話の状態(§12.1): 会話欄の項目、結び付き、世代番号、実行中の送信。UI スレッド専用。`InlineThreadModel` を作る |
| `QuickChatCommand` | 入力を `/clear` / `/reset` / 通常の質問に分類する純粋関数 |
| `QuickChatContextBuilder` | 固定した文書テキストと選択範囲から `AiCurrentFileInput` 相当を作る純粋関数(§9.2.1) |
| `GitLabVersion` | `Metadata.version` の文字列から (major, minor) を取り出し、17.10 以上かを判定する純粋関数 |

### 8.2 SWT 層(PR-2)

| コンポーネント | 責務 |
|---|---|
| `QuickChatPopups` | ウィンドウごとに Quick Chat を最大 1 つ保持する。開く・前面に出す・置き換える・閉じる(§9.1、§9.5)。バンドル停止時にすべて破棄。scope も executor も持たない(`QuickChatRuntime` を使う) |
| `QuickChatHost` | `InlineThreadHost` と `QuickChatView` を実装する薄いアダプタ。`onSubmit` を `QuickChatSession.submit` に渡し、`released` を `InlineThreadState.onAttemptFinished` / `onSucceeded` に、`render` を `surface.update(model)` に写す。Copy / Insert を処理する |
| `QuickChatContextCapture` | 送信時、UI スレッドで popup を付けたエディタから文書テキスト・選択範囲・ファイル名を読む |
| `QuickChatSnippetInserter` | [Insert] の本体。popup を付けたエディタの現在の選択へ 1 回の置換で挿入する(§9.6) |
| `OpenQuickChatHandler` / `CloseQuickChatHandler` | コマンドのハンドラ |

### 8.3 既存コードへの追加(すべて追加のみ・既定値で従来挙動)

| 対象 | 追加 | MR 側への影響 |
|---|---|---|
| `isSubmittable` | `mergerequests/discussions/CommentInputDialog.kt` から `views/inlinethread` へ移す(#97)。`CommentInputDialog` は移設先を参照する | なし(同一実装) |
| `InlineThreadPopup` コンストラクタ | `title: String`(既定 = 現行の「Merge Request Thread — line N」)、`submitOnModEnter: Boolean = false` | なし |
| `InlineThreadItem` | `submitLabel: String? = null`(null なら現行の Comment / Reply) | なし |
| `InlineThreadEntry` | `codeBlocks: Boolean = false`(true の項目は §9.7 の分割表示) | なし |
| `InlineThreadHost` | `fun onCodeAction(surface, action: CodeBlockAction, code: String) {}`(既定は何もしない)。`CodeBlockAction = COPY / INSERT` | なし |
| `InlineThreadSurface` | `fun update(model: InlineThreadModel)` を契約に加える(既存の具象 `InlineThreadPopup.update` を公開するだけ。モデルの差し替え + 本文の再描画 + `refresh`)。E `views/inlinethread/InlineThreadPopup.kt:173-177` | なし(MR ホストは使わなくてよい) |
| `MarkdownCodeBlocks`(新規・`views/inlinethread`) | Markdown の本文を「文章 / コード」の区切りに分ける純粋関数(§9.7) | なし |
| `GitLabProjectUrlResolver` | 解決結果を 4 種類(`Resolved` / `NotInRepository` / `NoGitLabRemote` / `Failed`)で返す兄弟メソッドを追加(§9.2.2)。既存メソッドは変えない | なし |
| `CodeFormatter` | 文書と選択を引数で受ける `format(snippet, document, selection)` を追加し、既存の `format(snippet)` はアクティブなエディタから読んでそれを呼ぶ | なし(既存経路は同じ結果) |
| `plugin.xml` | コマンド 2 件、ハンドラ 2 件、キー 1 件、エディタのコンテキストメニュー 1 件 | なし |

## 9. 処理フロー

### 9.1 開く(FR-1〜FR-3、FR-12)

1. `OpenQuickChatHandler.execute`(UI スレッド)。有効条件は `duo_chat_enabled`(§11.1)。実行時に `DuoChatStateService` を再確認し、無効なら理由(`getFirstEngagedCheck()?.details`、無ければ既定文言)を通知して終わる。
2. アクティブなパートから `ITextEditor` を得る(`PlatformUtils.getActiveTextEditor()`、UI スレッドなので multi-page エディタのアダプタも効く)。無ければ何もしない。
3. アンカー行 = 選択範囲の終端のオフセットがある行(1 始まり)。参照実装は範囲の終端に置く(REF `quick_chat/comment_thread_service.ts:31-35`)。行の決め方の細部(終端が行頭ちょうどの複数行選択など)は実装段階で決める(§26)。
4. `QuickChatPopups.open(window, editor, line)`:
   - 同じウィンドウに Quick Chat が無い → 新規作成。
   - あり、同じエディタ参照・同じアンカー行 → その popup を前面に出して入力欄にフォーカス(FR-2)。
   - あり、それ以外 → 既存を §9.5 の手順で終えてから新規作成(FR-3)。送信中でも置き換える。
5. 新規作成: 空の `QuickChatConversation` を作り、`InlineThreadPopup(editor, line, host, title = "GitLab Duo Quick Chat — line N", submitOnModEnter = true)` を開く。項目は 1 つ(`threadId = QUICK_CHAT_ITEM_ID`、`NEW_THREAD_ID` 以外。成功で閉じないため。E6)、`submitLabel = "Send"`、アクション = `REPLY` のみ。

### 9.2 送信(FR-4〜FR-6、FR-8)

**UI スレッド**(`QuickChatHost.onSubmit` → `QuickChatSession.submit`):

1. [Send] または `Ctrl+Enter` → `InlineThreadPopup` が `state.beginSubmit()` でチケットを作り、同じ UI の処理の中で `host.onSubmit(surface, ticket)` を呼ぶ(既存の流れ)。
2. `submit` の**最初の文**で期限を決める: `deadline = clock.now() + ANSWER_DEADLINE`(単調時計。§15.1)。続けて `conversation.begin(ticket)` で実行中の送信(チケット、世代番号 `gen`、新しい `SendGate`)を記録する。**これ以降、この送信はどの経路でも `finishOnce`(§9.2.4)で終わる。** `submit` の残りの処理で例外が出た場合も `finishOnce(…, Failed)` で終える。
3. `QuickChatCommand.classify(ticket.body)`。`/clear` / `/reset` は §9.4 へ。
4. 通常の質問。次のどれかに当たれば、通信せずに `finishOnce` で終える:
   - `DuoChatStateService` が無効 → `Unavailable(reason)`。
   - 手放した処理が上限に達している(§15.3)→ `Busy`。
   - 文脈の固定(§9.2.1)でサイズ上限を超えた → `TooLarge`。
5. 会話欄に「質問」と「回答を待っています」を足し、表示を更新する。
6. 要求 `QuickChatRequest` を作る。内容は不変の値と送信ゲートだけ: 文脈(`QuickChatContext`)、アンカーのファイルの場所、会話の結び付きの不変のコピー `ConversationBinding?`(§12.1)、`deadline`、`SendGate`。
7. `QuickChatRuntime` の scope で背景処理を起動し、`Job` を実行中の送信に記録する。`Job` に完了フック(§9.2.4 の (c))を付ける。
8. UI タイマーで期限の監視を予約する: `scheduleOnUi(deadline - clock.now()) { onDeadline(ticket, gen) }`(残りが 0 以下ならすぐに実行する)。

**背景**(`QuickChatService.ask`)。会話オブジェクトには触れず、要求の値だけを読む。ブロックする呼び出し(接続の取得、プロジェクトの解決、HTTP)はすべて `runInterruptible { … }` の中で行い、各段の前に「取り消されていないか」と「期限までの残り時間」を確かめる:

- w1. 各段の前に残り時間が 0 以下なら、その段を実行せず、段階に応じた結果で終わる(`aiAction` の前 → `TimedOut(beforeSend = true)`、後 → `TimedOut(beforeSend = false)`)。通常は UI 側の監視が先に結果を出しており、この結果は `finishOnce` で捨てられる。
- w2. 接続を得る(§9.2.3)。`binding` があれば、そのインスタンスと一致する接続だけを受け入れる。一致しなければ `ConnectionChanged`(何も送らない)。
- w3. アンカーのプロジェクトを解決し、必要なら preflight を行う(§9.2.2)。結果が「送信不可」なら、その結果で終わる。
- w4. **送信ゲートを通る**: `SendGate` を `Open → Sending` に原子的に進める。進められなければ(UI 側が先に `Closed` にした = この送信はすでに終わっている)、**`aiAction` を送らずに**、結果も出さずに終わる。
- w5. `aiAction` を送る(§11.2 M1)。`threadId` は `binding` にあれば付ける(R4)。`resourceId` は preflight の結果の値。応答を検査する: `errors` が空でなければ `ServerRejected(errors)`。`requestId` が無ければ `ServerRejected`。`threadId` が無ければ `Unsupported`(K3)。成功したら `SendGate` を `Sent(bindingUpdate)` にする(`bindingUpdate` = 結び付けるインスタンス、`threadId`、preflight の結果)。
- w6. `QuickChatPoller` で回答を待つ(§9.3)。
- w7. 結果を `QuickChatOutcome`(§12.3)にし、`delivered` の印を立ててから `runOnUi { finishOnce(ticket, gen, outcome) }` で UI へ渡す。例外はすべて結果に変換する(`CancellationException` だけは再送出)。

送信は会話ごとに 1 本だけ(busy)なので、preflight や `threadId` の更新が並行して競合することは無い。

**表示の反映**(Codex round 3 #1): 会話の `entries` を変えるすべての経路(手順 5、`finishOnce`、§9.4、区切りの追加)は、同じ UI スレッドの処理の最後に `view.render(conversation.toInlineModel())`(= `surface.update(model)`)を呼ぶ。`refresh()` だけでは本文は再描画されない。

#### 9.2.1 文脈の固定(UI スレッド)

- 読む対象は **popup を付けたエディタ**(アクティブなエディタではない)。popup の Shell にフォーカスがあるときのアクティブなパートは実機でしか分からないため(S3/S4 調査)。
- 文書 = `editor.documentProvider.getDocument(editor.editorInput)`、選択 = `editor.selectionProvider.selection as? ITextSelection`。
- 選択が空 → `currentFile` を送らない(R3)。
- 選択が空でない → `selectedText` = 選択テキスト、`contentAboveCursor` = 選択より前、`contentBelowCursor` = 選択より後、`fileName` = 下記。
- `fileName`: エディタ入力が `IFile` に適応できればワークスペースからの相対パス(`IFile.fullPath` の先頭 `/` を除いたもの。例 `project/src/Foo.java`)、そうでなければファイル名だけ(絶対パスはホームディレクトリ名などを含むため送らない)。
- アンカーのファイルの場所(preflight 用、§9.2.2)もここで読む: `IFile.location`、または入力が `ILocationProvider` / `IURIEditorInput` ならそのファイルパス。取れなければ「場所なし」。
- **サイズ上限**(参照実装には上限が無い。本設計の値): 大きさは UTF-8 のバイト数で測る。
  | 項目 | 上限 | 超えたとき |
  |---|---|---|
  | 質問 | 16 KiB | 送信しない。「The question is too long.」 |
  | `selectedText` | 64 KiB | 送信しない。「The selection is too large for Quick Chat. Select less code.」(ユーザーが選んだ範囲を黙って切ると意味が変わるため切らない) |
  | `contentAboveCursor` | 32 KiB | 選択に**近い側を残す**(先頭側を捨てる) |
  | `contentBelowCursor` | 32 KiB | 選択に**近い側を残す**(末尾側を捨てる) |
  - 切り詰めはコードポイントの境界で行う(サロゲートペアや UTF-8 の多バイト文字を割らない)。
  - UI スレッドで文書全体を文字列化しない。`IDocument.get(offset, length)` で必要な窓だけを取る(前後は選択の境界から 32 Ki 文字まで。UTF-8 は 1 文字 1 バイト以上なので、取った後にバイト数で切り詰めれば足りる)。選択の文字数が 64 Ki を超えれば、文字列を取らずに上限超過と判定する。
  - これにより 1 回の要求の本文は最大でおよそ 144 KiB + GraphQL の固定部分になる。
- 固定したデータは不変の値(`QuickChatContext`)として背景処理に渡す。背景処理は `IDocument` にも SWT にも触らない。

#### 9.2.2 preflight(バックグラウンド・会話の結び付きごとに 1 回)

アンカーのファイルが、接続先の GitLab のどのプロジェクトに属するかを確かめ、プロジェクト単位の Duo 設定を守る(Codex round 1 #2)。**判定できないときは送らない(フェイルクローズ)。**

1. アンカーのファイルの場所から、プロジェクトの解決結果を**型で**得る。既存の `GitLabProjectUrlResolver.resolveContextForFile` は失敗も「リポジトリ外」も同じ `Warn(NOT_IN_REPO)` にまとめる(E `navigation/GitLabProjectUrlResolver.kt:95` と `withRepo` の例外処理)ため、既存メソッドを変えずに、次の 4 種類を返す兄弟メソッドを追加する:
   - `Resolved(info)`: GitLab のプロジェクトとして解決できた。
   - `NotInRepository`: Git の作業ツリーの外(または場所なし)。
   - `NoGitLabRemote`: Git の作業ツリーだが、GitLab のプロジェクトを指す remote / 割り当てが無い。
   - `Failed`: 解決の途中で例外(I/O、JGit)が出た。
2. 解決結果ごとの扱い:
   | 解決結果 | 扱い | `resourceId` |
   |---|---|---|
   | `Resolved` で、インスタンスが接続先と一致(正規化して比較) | Q1 を送る(手順 3) | Q1 の `project.id` |
   | `Resolved` で、インスタンスが接続先と異なる | **送らない**(`ProjectCheckFailed`)。別のインスタンスのプロジェクトの Duo 設定は確かめられないため | — |
   | `NotInRepository` / `NoGitLabRemote` | プロジェクト外のファイルとして送る。Q1 はバージョンの問い合わせだけ | null |
   | `Failed` | **送らない**(`ProjectCheckFailed`) | — |
3. Q1 の結果(Q1 = §11.2 の 2 つの問い合わせ。まずバージョン、次に必要なら project):
   - `metadata.version` が 17.10 未満 → `Unsupported(version)`(確定事項 1)。project は問い合わせない。
   - `metadata` が null、またはバージョン文字列を解釈できない → バージョンについては続行する(参照実装も解釈できないときは最新扱い。REF `utils/if_version_gte.ts:26-32`)。17.10 未満のサーバなら M1 がスキーマエラーになり、§14 の表示になる。
   - `project` を問い合わせた場合: `project` が null(見つからない・権限が無い)→ **送らない**(`ProjectCheckFailed`)。`project.duoFeaturesEnabled == false` → **送らない**(`Unavailable`、「GitLab Duo is turned off for this project.」)。それ以外 → `project.id` を `resourceId` にする。`duoFeaturesEnabled` が null の場合はサーバ側の判定に任せて送る(`resourceId` を付けるので、サーバでもプロジェクトの設定が適用される)。
   - Q1 自体が失敗 → `TransportFailed`。preflight は未実施のまま(`bindingUpdate` に含めない)で、次の送信で再試行する。
4. preflight の結果(`resourceId`、判定済み)と、その判定に使った**プロジェクトの対応キー**を `bindingUpdate` に入れて返し、`finishOnce`(§9.2.4)で会話に保存する。対応キー = 解決結果の種類 + `Resolved` ならインスタンスと full path(手順 5 の GraphQL 用の形)。
5. **アンカーの解決(手順 1)は送信のたびに行う**(Codex round 2 #2)。ファイルが同じでも、Git の remote やプロジェクトの割り当ては popup を開いたまま変えられるため。解決結果の対応キーが会話に保存されたものと同じなら、保存済みの preflight を再利用する(Q1 は送らない)。違えば、会話の `threadId` と preflight を使わずに(新しい会話として)手順 2〜3 をやり直し、結果を `bindingUpdate` で保存する。会話欄には区切り「New chat」を**その送信の質問の直前**に挿入する(UI スレッドで、`finishOnce` の中で。`bindingUpdate` に「プロジェクトが変わった」を含めて返す)。挿入位置は実装段階でテストに固定する(A26)。
- Q1 の `fullPath` は、各パス要素を 1 回だけ URL デコードした形にする(HTTP の remote から得た `namespaceWithPath` は percent-escape を保持している。E `navigation/GitLabProjectUrlResolverTest` が固定)。デコード規則は実装段階でテストに固定する(A25)。
- LS の `duo_chat_enabled` はアクティブなプロジェクトについての判定なので、アンカーのファイルについての判定の代わりにはしない。上の preflight がアンカーについての判定である。

#### 9.2.3 接続の取得と会話の結び付き(Codex round 1 #1)

- 会話は、最初に preflight が成功した送信の接続先インスタンス(正規化した URL)に**結び付く**。結び付き = インスタンス + preflight の結果 + `threadId`(得られていれば)。接続先の変更(`ConnectionChanged`)と会話の終了(§9.5)では結び付き全体を破棄する。`/clear` `/reset`(§9.4)は `threadId` だけを破棄する(インスタンスとアンカーは変わらないので preflight は再利用できる)。
- 結び付いた会話の送信では `captureConnectionIf { normalize(it) == 結び付いたインスタンス }` を使う。null(設定が別のインスタンスに変わった)なら何も送らずに `ConnectionChanged` を返し、`finishOnce` が結び付きを破棄して区切り「New chat」を足す。古い `threadId` や Project gid を別のインスタンスへ送ることは無い。
- 結び付いていない会話では `captureConnection()` を使う。
- 同じインスタンスでアカウントだけが変わった場合、トークンの指紋(`authFingerprint`)はトークン更新でも変わるので区別できない(E2)。この場合、古い `threadId` はサーバで見つからずエラーになる(K1 の `find_thread` はユーザーのスレッドだけを探す)。Project gid は同じインスタンスの同じプロジェクトを指し、サーバが新しいアカウントの権限で判定する。したがって他人のデータは読めず、別プロジェクトへ送られることも無い。エラーの表示後、ユーザーは `/reset` か開き直しで新しい会話にできる(§20)。

#### 9.2.4 唯一の終端 `finishOnce`(UI スレッド。Codex round 6 #3)

1 回の送信を終わらせる処理は `QuickChatSession.finishOnce(ticket, gen, outcome)` の 1 つだけで、必ず UI スレッドで実行する。呼び出し元は次の 4 つ。どれが先に来ても、最初の 1 回だけが効く。

| | 呼び出し元 | 渡す結果 |
|---|---|---|
| (a) | 背景処理の結果(w7 の `runOnUi`) | 背景処理が作った結果 |
| (b) | 期限の監視 `onDeadline`(UI タイマー) | `SendGate` を読んで決める(§12.4): 送信前 → `TimedOut(beforeSend = true)`、送信中 → `MaybeSent`、送信後 → `TimedOut(beforeSend = false)` |
| (c) | `Job` の完了フック `invokeOnCompletion`。`delivered` の印が立っていないときだけ `runOnUi` で呼ぶ(起動前の取り消し、scope の取り消し、捕まえ損ねた例外) | `Interrupted` |
| (d) | `submit` の中の即時失敗(手順 2・4) | その結果 |

処理の内容:

```
fun finishOnce(ticket, gen, outcome) {            // UI スレッド
  if (!conversation.isCurrent(ticket, gen)) return // 終了済み・閉じた・置き換えた・/clear 済み
  val send = conversation.clearInFlight()          // ここで「終了済み」になる。以後の呼び出しは上の行で戻る
  try {
    apply(outcome)                                 // bindingUpdate の保存、会話欄の Pending → 回答 / 失敗
  } catch (e: Exception) { log(クラス名のみ) } finally {
    send.watchdog?.cancel()                        // 即時失敗 (d) では未予約
    send.gate.closeIfOpen()                        // まだ送っていなければ、以後も送らせない
    send.job?.let { it.cancel(); runtime.detached.track(it) }   // 完了済みの Job には何も起きない
    guarded { view.released(ticket, succeeded = outcome is Answered) }
    guarded { view.render(conversation.toInlineModel()) }
  }
}
```

- 状態の切り替え(`clearInFlight`)を最初に行うので、`apply` や表示の更新が例外を出しても、チケットの解放(`released`)は `finally` で必ず 1 回行われ、2 回目以降の呼び出しは何もしない。印を付ける時機による分岐(投入時 / 反映後)は無い。
- すべて UI スレッドの上で順に実行されるので、(a)〜(d) の間の排他にロックは要らない。UI スレッドと背景処理の間で共有する可変状態は `SendGate` と `delivered` の印だけである。
- `guarded { … }` は例外を捕まえてクラス名だけをログに残す。`released` の失敗が `render` を止めない。
- 閉じる・置き換え・`/clear` `/reset`(§9.4、§9.5)は `finishOnce` を通らず、`session.end()` / 世代番号の更新で同じ後始末(`watchdog.cancel`、`gate.closeIfOpen`、`job.cancel`、`detached.track`)を行う。その後に届く (a)〜(c) は `isCurrent` で捨てられる。

### 9.3 ポーリング(T1)

- 問い合わせ = §11.2 Q2: `requestIds: [requestId]`、`roles: [ASSISTANT]`、`threadId`(K1 のとおり必須。`conversationType` はスレッドを指定すれば使われないので送らない)。
- 終了条件: `nodes` の中に `requestId` が一致し `role == ASSISTANT` のものがある(クライアント側でも照合する)。K2 により、それは完成した回答である。
  - `errors` が空でない → `ServerRejected(errors)`(`content` があっても失敗扱い。表示は §14)。
  - `errors` が空で `content` が空または null → `EmptyAnswer`。
  - それ以外 → `Answered(content)`。
- 間隔: 参照実装の前例(R2: 5000 ms 間隔、1 リクエスト 25000 ms)を既定値とする。最初の問い合わせまでの待ちと間隔の調整は実装段階で決める(§26)。**待ち続ける上限は回数ではなく、§9.2 手順 2 の `deadline`(実時間)で決める**(§15)。
- 1 回ごとに §9.2.3 と同じ条件(結び付いたインスタンスと一致)で接続を取り直す(OAuth 更新でトークンが変わっても続けられるように。E2)。一致しなければ `ConnectionChanged` で終わる。
- 1 回の問い合わせが失敗したら(通信・HTTP・GraphQL のエラー)、その時点で送信を失敗にする。途中の失敗を読み飛ばして続けることはしない(再試行は §15)。
- 待ちは `delay`。問い合わせは `runInterruptible` の中で行う。取り消されたら、待ちも実行中の HTTP もすぐ止まる(E12)。取り消しの後に届いた結果は `finishOnce` が捨てる(§9.2.4)。

### 9.4 `/clear` と `/reset`(FR-7)

UI スレッドで処理し、チケットは即座に完了させる(`finishOnce(ticket, gen, Cleared)` → `released(ticket, succeeded = true)` で下書きを消す)。

- `/clear`: 会話欄を空にする。
- `/reset`: 会話欄の末尾に区切り「New chat」を足す。
- 共通: `threadId` を破棄する。`threadId` を持っていた場合だけ、背景で `aiAction`(§11.2 M2: `question` = `/clear` または `/reset`、`threadId` のみ)を送る(期限と資源の扱いは §15.4)。接続は §9.2.3 と同じく結び付いたインスタンスと一致するものだけを使い、一致しなければ送らない。結果は待たず、失敗はログ(§19)に記録するだけ(参照実装も表示しない。R5)。
- 送信中は [Send] が無効なので、`/clear` `/reset` が実行中の通常の送信と重なることは無い。
- 会話を持っていない(`threadId` が無い)場合は、サーバへは何も送らない。

### 9.5 閉じる・置き換え(FR-3、FR-9)

どの閉じ方でも同じ終了処理を通す:

1. `InlineThreadPopup.close()`(既存): 未送信の下書きを `preserveDrafts` に渡し(実行中のチケットの本文は除く。E7)、Shell を破棄し、`host.onClosed()` を呼ぶ。
2. `QuickChatHost.onClosed()` → `QuickChatSession.end()`: 世代番号を進め、実行中の送信があれば期限の監視を取り消し、`SendGate` を閉じ(まだ送っていなければ以後も送られない)、`Job` を取り消して `DetachedJobs` に渡す。結び付きを破棄し、`QuickChatPopups` から外す。
3. サーバには何も送らない(`/clear` も送らない)。サーバ側の会話は GitLab の保持期限で消える。参照実装は次に開いたときに前の会話へ `/clear` を送る(REF `quick_chat/quick_chat_state.ts:394-418`)が、閉じる操作やウィンドウ終了のたびにネットワークへ書き込む副作用を避けるため採らない。
- `preserveDrafts` はコピー用ダイアログを出す(MR 経路と同じ部品 `CommentInputDialog` + `COPY_TEXT_PROMPT`。E `mergerequests/discussions/actions/DiscussionActionSupport.kt:54, 267`)。ダイアログの親はウィンドウの Shell。置き換え(§9.1 手順 4)では、古い popup の下書きダイアログを閉じてから新しい popup を開く。
- 「Close Quick Chat」はアクティブなウィンドウの Quick Chat を閉じる。開いていなければハンドラは無効。
- エディタを閉じる / 入力が差し替わる → 既存の `EditorGoneListener` が popup を閉じる(E7)。
- ウィンドウを閉じる → popup の Shell はウィンドウの Shell の子なので一緒に破棄される。このとき `discard()` 経路(下書きの提示なし)で `onClosed()` が呼ばれることを実装で保証する。

### 9.6 挿入(FR-11)

UI スレッドで行う。

1. 対象 = popup を付けたエディタ(popup が開いている = エディタは開いている。E7)。
2. 編集できるか: `ITextEditorExtension2.validateEditorInputState()` が偽 → 「このエディタは編集できません」を通知して終わる(読み取り専用ファイルでは、ここで Eclipse がチェックアウト / 書き込み可否の確認を出す)。
3. 選択 = `editor.selectionProvider.selection as? ITextSelection`(クリックした時点の選択)。
4. 整形: 文書が Java(`IFile` の拡張子が `java`、またはコンテンツタイプが JDT の Java ソース)なら `CodeFormatter.format(code, document, selection)`(§8.3 で追加する引数版。Duo Chat と同じ整形)。それ以外は整形しない(前後の空白を除くだけ)。整形の途中で例外が出た場合、または整形結果が得られない場合も整形しないで挿入する。
5. `document.replace(offset, length, text)` を 1 回。`IDocumentUndoManager` の compound change で囲み、1 回の元に戻すで戻せるようにする(前例 E `lsp/edit/WorkspaceEditApplier.kt:228-246`)。
6. 例外(`BadLocationException` など)は通知して終わる。

### 9.7 回答の表示とコードブロック(F3、確定事項 6)

- `MarkdownCodeBlocks.split(body): List<Segment>`、`Segment = Prose(text) | Code(language?, code)`。CommonMark のフェンスに合わせる:
  - 開始: 行頭の空白 0〜3 個 + バッククォート 3 個以上、またはチルダ 3 個以上。バッククォートのフェンスの情報文字列にバッククォートを含まない。情報文字列の最初の語を言語とする(`c++` や `objective-c` も言語として扱う。参照実装の `\w+` の制限は引き継がない)。
  - 終了: 同じ文字で開始以上の長さのフェンスだけの行。
  - 閉じられていないフェンスは文書の終わりまでをコードとする。
  - コードが空のブロックにはボタンを出さない(参照実装と同じ。REF `quick_chat/utils.ts:83-85`)。
- 表示: `codeBlocks = true` の項目は区切りごとに部品を作る。文章 = 読み取り専用・折り返しの `Text`(Markdown の記号はそのまま。現行 MR 表示と同じ)、コード = 読み取り専用・等幅の `StyledText` と、その上の [Copy] [Insert] ボタン行。
- 回答が長い場合に会話欄をスクロールできること(受け入れ条件 A15)。
- ボタンは `host.onCodeAction(surface, COPY|INSERT, code)` を呼ぶ。`code` はその区切りのコード(フェンスを除いたもの)。
- 質問・区切り・失敗の説明は `codeBlocks = false`(現行どおり 1 つの `Text`)。
- **資源の上限**(Codex round 3 #4。値は既定値で、実装段階で調整してよい):
  | 対象 | 上限 | 超えたとき |
  |---|---|---|
  | 1 つの回答の本文(UTF-8) | 256 KiB | 上限までを表示し、末尾に「(Answer truncated)」。切り詰めはコードポイント境界。コードブロックの途中で切れた場合、そのブロックは閉じられていないフェンスとして扱う |
  | 1 つの回答のコードブロックのボタン | 30 ブロック | 31 個目以降は等幅表示のみ(ボタンなし) |
  | 会話欄に保持する項目 | 直近 40 項目 | 古い項目から捨て、先頭に「(Earlier messages were removed)」。`threadId` は保つ(サーバ側の会話は続く) |
  - GraphQL 応答の受信サイズ自体は既存の `GitLabGraphQlClient` の扱いに従う(本サイクルで HTTP 層は変えない)。

### 9.8 コピー(FR-10)

`ClipboardWriter.writeAndNotify(code) { 通知 "Code copied to clipboard." }`(E9)。書けなかった場合は `ClipboardWriter` の既存の失敗処理に従う。

## 10. 等価表(VSCode → Eclipse)

| VSCode | Eclipse | 差異 |
|---|---|---|
| `gl.openQuickChat` / `gl.openQuickChatWithShortcut`(Alt+C) | `gitlab-eclipse-plugin.commands.OpenQuickChat`(`M1+M3+C`)+ エディタのコンテキストメニュー | キーを変更(Alt+文字はメニューのアクセスキーと衝突しうる。確定事項 2) |
| `gl.closeQuickChat`(Esc、`editorTextFocus`) | popup 内の Esc、閉じるボタン、`gitlab-eclipse-plugin.commands.CloseQuickChat` | グローバルな Esc は割り当てない(E8)。閉じる = 会話を終える(折りたたみは無い) |
| `gl.sendQuickChat` / `gl.sendQuickChatDup`(⌘⏎ / Ctrl+⏎) | popup の [Send]、popup 内 `M1+Enter` | — |
| `gl.copyCodeSnippetFromQuickChat` | コードブロックの [Copy] | — |
| `gl.insertCodeSnippetFromQuickChat` | コードブロックの [Insert] | 挿入先 = popup を付けたエディタの選択(参照はアクティブなエディタ。確定事項 5) |
| ActionCable で逐次表示 | `aiMessages` のポーリングで完成形を一度に表示 | 途中経過は表示しない(確定事項) |
| エラー時はログのみ・読み込み表示が残る | 会話欄に理由を表示し、Send を戻す | 改善 |

## 11. API / インターフェース

### 11.1 コマンド(category `gitlab-eclipse-plugin`)

| id | 名前 | キー | 有効条件 | メニュー |
|---|---|---|---|---|
| `gitlab-eclipse-plugin.commands.OpenQuickChat` | Open Quick Chat | `M1+M3+C`、context `org.eclipse.ui.textEditorScope`、scheme `org.eclipse.ui.defaultAcceleratorConfiguration` | `duo_chat_enabled` が真 | `popup:#AbstractTextEditorContext` の既存「GitLab Duo Chat」メニュー(`plugin.xml:1132` 付近)に追加。`visibleWhen` は既存の Explain 等と同じ `duo_chat_enabled` |
| `gitlab-eclipse-plugin.commands.CloseQuickChat` | Close Quick Chat | なし | アクティブなウィンドウに Quick Chat が開いている(ハンドラの `isEnabled`) | なし(Quick Access から実行) |

### 11.2 GraphQL

`platformOrigin` の値は実装段階で決める(K5: 任意の文字列。§26)。

**M1 送信**(K3〜K9。REF `chat/gitlab_chat_api.ts:117-148` の 17.10 版から `additionalContext` を除いたもの)

```graphql
mutation quickChatAsk($question: String!, $resourceId: AiModelID, $currentFile: AiCurrentFileInput,
  $clientSubscriptionId: String, $platformOrigin: String!,
  $conversationType: AiConversationsThreadsConversationType, $threadId: AiConversationThreadID) {
  aiAction(input: {
    chat: { resourceId: $resourceId, content: $question, currentFile: $currentFile }
    clientSubscriptionId: $clientSubscriptionId
    platformOrigin: $platformOrigin
    conversationType: $conversationType
    threadId: $threadId
  }) { requestId errors threadId }
}
```

- `conversationType = DUO_QUICK_CHAT`、`threadId` は 2 回目以降のみ、`clientSubscriptionId` = 送信ごとの UUID(後続のストリーミング対応で購読と結び付けるため。今回は使わない)。

**M2 `/clear` / `/reset`**(R5)

```graphql
mutation quickChatClear($question: String!, $threadId: AiConversationThreadID, $platformOrigin: String!) {
  aiAction(input: { chat: { content: $question }, threadId: $threadId, platformOrigin: $platformOrigin }) {
    requestId errors
  }
}
```

**Q1 preflight**(K10、K11)

```graphql
query quickChatVersion { metadata { version } }
```

```graphql
query quickChatProject($fullPath: ID!) { project(fullPath: $fullPath) { id duoFeaturesEnabled } }
```

バージョンを先に単独で問い合わせ、17.10 以上(または解釈できない)の場合だけ project を問い合わせる(Codex round 3 #2)。`duoFeaturesEnabled` は 16.9 からのフィールドで、古いサーバでは文書全体がスキーマエラーになるため、同じ文書に入れない。

**Q2 ポーリング**(K1、K7)

```graphql
query quickChatMessages($requestIds: [ID!], $roles: [AiMessageRole!], $threadId: AiConversationThreadID) {
  aiMessages(requestIds: $requestIds, roles: $roles, threadId: $threadId) {
    nodes { requestId role content errors timestamp }
  }
}
```

### 11.3 `views/inlinethread` の追加契約

§8.3 の表のとおり。追加はすべて既定値つきで、MR 側の呼び出しは変えない。`onCodeAction` は UI スレッドで呼ばれる。`submitOnModEnter = true` のとき、入力欄で `M1+Enter` を押すと [Send] と同じ `submit()` を通る(`canSubmit` が偽なら何もしない。改行も入らない)。

## 12. データモデル

### 12.1 会話(`QuickChatConversation`、UI スレッド専用)

| 項目 | 内容 |
|---|---|
| `entries` | 会話欄の項目の列。種類 = `Question(text)` / `Pending` / `Answer(markdown)` / `Failure(message)` / `Separator` |
| `binding` | 会話の結び付き(§9.2.3)。`instanceUrl`(正規化済み)、`preflight`(バージョン判定済み・`resourceId`・プロジェクトの対応キー。§9.2.2)、`threadId?`。未確立なら null。`ConnectionChanged` と終了で破棄 |
| `generation` | 世代番号。`/clear` `/reset`、`ConnectionChanged`、終了で進める |
| `inFlight` | 実行中の送信: チケット、開始時の世代番号、`deadline`、`SendGate`、期限の監視の取り消し手段、`Job`(起動後)。`begin` で作り、`clearInFlight` で外す |

- `binding` を書き換えるのは UI スレッドの 3 箇所だけ: `finishOnce`(照合済みの `bindingUpdate` の保存、`ConnectionChanged` での破棄)、`end`(§9.5 の破棄)、§9.4(`threadId` の破棄)。背景処理は要求に入った `binding` の不変のコピーだけを読む。

`toInlineModel()` は 1 項目の `InlineThreadModel` を作る。`Question` の author = 「You」、`Answer` / `Pending` / `Failure` の author = 「GitLab Duo」、`Answer` だけ `codeBlocks = true`。`createdAt` は表示しない(空文字)。

### 12.2 文脈(`QuickChatContext`、不変)

`question`、`currentFile: CurrentFile?`(`fileName`、`selectedText`、`contentAboveCursor`、`contentBelowCursor`。§9.2.1 の上限を満たす)。要求 `QuickChatRequest` = 文脈 + アンカーのファイルの場所(`java.io.File?`)+ `binding` の不変のコピー + `deadline` + `SendGate`。

### 12.3 送信の結果(`QuickChatOutcome`)

| 値 | 意味 | 会話の `threadId` |
|---|---|---|
| `Answered(content, threadId)` | 回答を得た | 保存 |
| `EmptyAnswer(threadId)` | 回答が空 | 保存 |
| `ServerRejected(messages, threadId?)` | `aiAction.errors` または回答の `errors` | 得られていれば保存 |
| `Unsupported(version?)` | 17.10 未満、または応答に `threadId` が無い | 変更なし |
| `Unavailable(reason)` | 利用不可(送信直前の確認、またはプロジェクトで Duo が無効) | 変更なし |
| `ProjectCheckFailed(kind)` | アンカーのプロジェクトを確かめられない(別インスタンス、解決の失敗、プロジェクトが見つからない)。§9.2.2 | 変更なし |
| `TooLarge(item)` | 質問または選択が §9.2.1 の上限を超えた(UI スレッドで判定し、背景処理は起動しない) | 変更なし |
| `TransportFailed(kind, status?, correlationId?)` | 通信・HTTP・GraphQL のエラー | 得られていれば保存 |
| `MaybeSent(threadId?)` | `aiAction` が送られたか分からない(応答前のタイムアウト・切断) | 得られていれば保存 |
| `TimedOut(beforeSend, threadId?)` | 期限内に終わらなかった。`beforeSend` = `aiAction` を送る前に打ち切った(以後も送られない。§12.4) | `aiAction` 後なら保存 |
| `Busy` | 手放した処理が上限に達していて、送信を受け付けなかった(§15.3)。何も送っていない | 変更なし |
| `Interrupted` | 背景処理が結果を出さずに終わった(取り消し、想定外の例外)。§9.2.4 の (c) | 変更なし |
| `Failed` | `submit` の中の想定外の例外。何も送っていない | 変更なし |
| `Cleared` | `/clear` `/reset` を処理した(§9.4)。失敗ではない | `threadId` を破棄 |
| `ConnectionChanged` | 送信前またはポーリング中に、接続先が結び付いたインスタンスと異なった | 結び付きごと破棄 |

「会話の `threadId`」列の「保存」は、`bindingUpdate` に入れて返し `finishOnce` で保存することを意味する。preflight の結果も、成功した場合は同じく `bindingUpdate` で保存する(送信自体が後段で失敗しても)。

### 12.4 送信ゲート(`SendGate`)

1 回の送信ごとに 1 つ作る、原子的な参照(`AtomicReference`)。状態と遷移:

| 状態 | 意味 |
|---|---|
| `Open` | まだ `aiAction` を送っていない(初期状態) |
| `Sending` | `aiAction` を送り始めた。結果はまだ分からない |
| `Sent(bindingUpdate)` | `aiAction` が成功した。会話に保存すべき更新(インスタンス、`threadId`、preflight の結果)を持つ |
| `Closed` | 送る前に UI 側が打ち切った。以後この送信で `aiAction` を送ってはならない |

| 操作 | 実行者 | 遷移 |
|---|---|---|
| `tryBeginSend()` | 背景(w4) | `Open → Sending` の比較交換。失敗したら送らない |
| `markSent(update)` | 背景(w5) | `Sending → Sent(update)` |
| `closeIfOpen()` | UI(`onDeadline`、`finishOnce`、`session.end()`、世代番号の更新) | `Open → Closed` の比較交換。戻り値 = 操作の直前の状態 |

`onDeadline` は `closeIfOpen()` の戻り値で結果を決める: `Open` → `TimedOut(beforeSend = true)`(以後送られないことが保証される)、`Sending` → `MaybeSent`、`Sent(update)` → `TimedOut(beforeSend = false)` で、`update` を会話に保存する(次の質問が同じ会話に続く)。

## 13. トランザクション境界

クライアント側に永続化は無い。サーバ側では、`aiAction` の成功で質問が会話に保存され、回答はサーバが非同期に保存する(K2)。クライアントの送信 1 回 = `aiAction` 1 回 + 読み取りのみのポーリング。`aiAction` だけが書き込みで、失敗しても部分的な状態をクライアントに残さない(会話欄の表示だけ)。

## 14. エラー処理

| 状況 | 会話欄の表示(英語。文言は実装段階) | 再送 |
|---|---|---|
| 利用不可 | LS の理由(`getFirstEngagedCheck()?.details`)または既定文言 | 可 |
| 17.10 未満 | 「Quick Chat requires GitLab 17.10 or later (this instance: X).」 | 可(同じ結果) |
| `aiAction.errors` | サーバの文言をそのまま(複数は改行区切り) | 可 |
| 回答の `errors` | 同上 | 可 |
| 空の回答 | 「GitLab Duo returned an empty answer.」 | 可 |
| HTTP エラー / GraphQL エラー / 解釈不能 | 「Failed to send the question to GitLab」+ HTTP ステータス、相関 ID(あれば) | 可 |
| `MaybeSent` | 「The question may have been sent, but no answer could be retrieved.」 | 可(§16) |
| タイムアウト(送信後) | 「Timed out waiting for the answer.」 | 可 |
| タイムアウト(送信前) | 「GitLab did not respond in time. Nothing was sent.」 | 可 |
| `Busy` | 「Earlier Quick Chat requests are still not responding. Try again later.」 | 可(後で) |
| `Interrupted` / `Failed` | 「The request was interrupted.」 | 可 |
| 接続先の変更 | 「The GitLab connection changed. Your next question starts a new chat.」+ 区切り「New chat」 | 可(新しい会話になる) |
| プロジェクトを確かめられない | 「Quick Chat could not confirm the GitLab project of this file, so nothing was sent.」(別インスタンスの場合はその旨) | 可 |
| プロジェクトで Duo が無効 | 「GitLab Duo is turned off for this project.」 | 可(同じ結果) |
| サイズ超過 | §9.2.1 の文言 | 可(選択を変えて) |
| 挿入先が編集不可 / 挿入失敗 | 通知(会話欄ではない) | — |

- 失敗後も popup と会話は残る。入力欄は有効に戻り、下書きは残る(FR-8)。
- サーバから返った文言は、プレーンテキストとして表示する(`Text` に入れるだけ。HTML として解釈しない)。

## 15. タイムアウトとリトライ

### 15.1 方針: 期限は UI 側で 1 か所だけ守る(2026-09-28 方式変更)

round 2〜6 の設計(止まりうる処理を 1 つずつ専用 executor に隔離し、それぞれを期限つきで待つ)は廃止した。理由と比較は §29「方式の再検討」。新しい方針は次の 3 点である。

1. **Send を戻す責任は UI 側の期限の監視だけが持つ。** 期限は `submit` の最初の文で決め(§9.2 手順 2)、UI タイマーが期限に `finishOnce` を呼ぶ(§9.2.4 の (b))。背景処理が終わったかどうかを待たないので、背景処理がどこで止まっていても、Send は `ANSWER_DEADLINE` で戻る。
2. **背景処理は割り込みで止める。** ブロックする呼び出しはすべて `runInterruptible` の中で行い、終了時・期限切れ・閉じる・置き換えで `Job.cancel()` する。HTTP は割り込みで止まる(E12)。
3. **割り込みで止まらない処理は「手放す」。数に上限を設ける**(§15.3)。

- `ANSWER_DEADLINE` の既定値は 120 秒(参照実装のポーリングの上限 5000 ms × 20 回 ≒ 100 秒に `aiAction` の時間を足した値。調整は実装段階)。
- UI タイマーは UI スレッドが動いていることを前提にする。UI スレッドが止まっている間は画面全体が止まっており、Send だけを戻す意味が無い。
- `scheduleOnUi` の実体は `Display.timerExec`(既存の使用例 E `chat/webview/AgenticChatWebViewClient.kt:30`)。テストでは偽物を注入する。

### 15.2 背景処理の各段

- 各 HTTP(preflight、`aiAction`、各ポーリング)の `HttpRequest.timeout` = min(1 リクエストの上限(既定 25000 ms。R2), 期限までの残り時間)。残り時間が 0 以下なら、その要求を送らずに終わる(§9.2 の w1)。
- `HttpRequest.timeout` は応答ヘッダまでしか守らない(本文の途中で止まると戻らない。E12)。本文の途中で止まった場合は、期限での `Job.cancel()` による割り込みで止まる(E12)。
- ポーリングの待ちは `delay`(取り消しですぐ止まる)。待ちは残り時間を超えない。
- 期限切れの結果は `SendGate` の状態で決まる(§12.4)。`aiAction` の結果が期限の後に届いても使わない(`finishOnce` が捨てる)。

### 15.3 手放した処理の上限(`DetachedJobs`)

- 「手放した処理」= チケットを解放した(`finishOnce` / 終了)後も完了していない `Job`、および実行中の `/clear` `/reset` の背景送信(§9.4)。`QuickChatRuntime` が全ウィンドウを通じて 1 つの計数を持つ。`Job` の完了で 1 つ減る。
- 割り込みで止まる処理(HTTP、`delay`)は取り消しから短時間で完了するので、数はすぐ 0 に戻る。残り続けるのは、割り込みで止まらない呼び出しの中にいる処理だけである。該当するのは OAuth のトークン更新(ScribeJava の `HttpURLConnection`。タイムアウト未設定で、割り込みでも止まらない。E13)と、JGit のローカル I/O。
- 手放した処理が `MAX_DETACHED`(既定 4)以上のあいだ、新しい送信は通信せずに `Busy` で終える(§9.2 手順 4)。新しい `/clear` `/reset` の背景送信は行わずにログに記録する(画面上の効果とローカルの `threadId` の破棄は行う)。
- **正常に実行中の送信は数えない**(会話ごとに 1 本なので、数はウィンドウの数で抑えられる)。したがって複数のウィンドウで同時に送信しても、互いを拒否しない。
- これにより、Quick Chat が同時に占める背景の処理は「送信中のウィンドウの数 + `MAX_DETACHED` + 上限に達した時点で送信中だったもの」を超えない。スレッドは `Dispatchers.IO` のものを使い、専用のスレッドは作らない。
- OAuth のトークン更新が止まっている状態では、`captureConnection` を使う他のすべての機能も同じ場所で止まる(既存の性質。§27)。Quick Chat はその状態でも Send を戻し、理由を表示する。

### 15.4 `/clear` `/reset` の背景送信(§9.4)

- `QuickChatRuntime` の scope で起動し、誰も完了を待たない。処理全体を `withTimeout(CLEAR_DEADLINE)`(既定 30 秒)と `runInterruptible` で囲む。HTTP のタイムアウト = min(25000 ms, 残り)。
- 起動した時点から完了まで `DetachedJobs` に数える(§15.3)。popup を閉じても続ける(サーバ上の会話の片付けなので)。バンドル停止時に scope ごと取り消す。
- 期限切れ・失敗はログに記録するだけ(§19)。

### 15.5 リトライ

**自動の再送はしない。** `aiAction` は書き込みで冪等でない(§16)。ポーリング中の失敗も再試行せず、その送信を失敗にする(実装を単純に保つ。ユーザーが再送できる)。

## 16. 冪等性

- `aiAction` は冪等でない。同じ質問を 2 回送ると会話に 2 回入る。
- 接続の確立前に失敗したと分かる場合(接続拒否・名前解決の失敗・プロキシの認証失敗など)は `TransportFailed`、リクエストを送り始めた後の失敗(応答待ちのタイムアウト・切断)は `MaybeSent` とする。どの例外をどちらに分けるかは実装段階でテストに固定する。
- `MaybeSent` になるのは、`SendGate` が `Sending` のあいだに期限が来た場合と、`aiAction` の HTTP が送信開始後に失敗した場合である。
- 応答前のタイムアウト・切断(`MaybeSent`)では、サーバに質問が届いたか分からない。**#96 と違い、再送をロックしない。** 理由: Quick Chat の質問は自分だけの会話に入り、他のユーザーに見える投稿ではない。重複しても回答が 1 つ余計に作られるだけで、取り消しの必要な外部への影響が無い。
- 初回の送信が `MaybeSent` になった場合は `threadId` が得られないので、次の質問は新しい会話になる(前の文脈は引き継がれない)。会話欄にその旨は出さない(文言は実装段階で検討)。
- `[Send]` の二重押しは `InlineThreadState` の busy で防ぐ(E6)。

## 17. 並行処理

- **UI スレッド専用**: popup、`InlineThreadState`、`QuickChatSession`、`QuickChatConversation`(NFR-1)。
- **背景処理が触るもの**: 要求に入った不変の値(`QuickChatContext`、`binding` のコピー、`deadline`)、`SendGate`、`delivered` の印、接続。UI スレッドと背景処理が共有する可変状態は `SendGate`(原子的な参照)と `delivered`(原子的な真偽値)だけである。
- **背景から UI へ**は `runOnUi`(= `Display.asyncExec`)のみ。`syncExec` は使わない。ディスプレイが破棄済みで投入に失敗したら、何もしない(popup も存在しない)。投入の失敗と、投入した処理の中の例外は捕まえてクラス名だけをログに残す(既存の `hop` と同じ扱い。E `mergerequests/review/MrThreadPopupHost.kt:310-324`)。
- **終端は 1 つ**: §9.2.4 の `finishOnce`。古い結果(閉じた後・置き換えた後・`/clear` `/reset` の後・期限切れの後)は `conversation.isCurrent(ticket, gen)` で捨てる。照合と反映は同じ UI の処理の中で行うので、順序はすべて UI スレッド上で決まる。
- **送信ゲート**(§12.4): 「期限切れ・閉じる・置き換えが先か、`aiAction` の送信が先か」は `SendGate` の 1 回の原子的な更新で決まる。UI 側が先なら `aiAction` は送られない。背景側が先なら UI 側は「送信中」または「送信後」として扱う。「何も送っていない」と表示した質問が後から送られることは無い。
- **scope**: `QuickChatRuntime` が持つ `CoroutineScope(SupervisorJob() + Dispatchers.IO)` を、全ウィンドウの送信と `/clear` `/reset` の背景送信が使う。既存の共有 scope(`SupervisorJob` でなく、他の機能の例外で失効しうる。E4)は使わない。`QuickChatRuntime` は Koin の `single` で、生成は 1 回だけ。
- **送信は会話ごとに 1 本**(busy)。ウィンドウごとに会話は 1 つ。ウィンドウが違えば独立して並行できる(§15.3 のとおり、正常な同時送信は拒否しない)。
- **キャンセル**: `finishOnce`・閉じる・置き換えで `Job.cancel()`。`runInterruptible` の中の HTTP と `delay` はすぐ止まる。割り込みで止まらない呼び出しは戻るまで残るが、誰も待たない(§15.3)。
- **`QuickChatService` は `CancellationException` 以外のすべての例外を結果に変換する。** 捕まえ損ねた例外で `Job` が終わっても、scope は `SupervisorJob` なので他の送信に波及せず、完了フック(§9.2.4 の (c))がチケットを解放する。
- **バンドル停止時の順序**: (1) UI スレッドで `QuickChatPopups` がすべての popup を `discard()` する(各 `session.end()` が `SendGate` を閉じ、`Job` を取り消す)。(2) `QuickChatRuntime.close()` が scope を取り消す。どちらも処理の完了を待たない(join しない)。

## 18. 認証と認可

- 認証は既存の接続(`captureConnection`)と同じ。トークンは `ConnectionSnapshot` からリクエストヘッダにだけ入り、ログに出ない。
- 認可はサーバが行う(Duo のライセンス、プロジェクトでの Duo 無効化、会話の所有者)。クライアントは LS の状態(`duo_chat_enabled`)で事前に止めるだけで、それを認可の代わりにしない。
- プロジェクトで Duo が無効な場合: LS の `duo-disabled-for-project` でコマンドが無効になるが、これはアクティブなプロジェクトについての判定である。アンカーのファイルについては §9.2.2 の preflight で確かめ(`duoFeaturesEnabled`)、確かめられなければ送らない(フェイルクローズ)。送信時には `resourceId` を付けるので、サーバ側でもプロジェクトの設定が適用される。
- 接続先が変わった会話の `threadId` と Project gid を別のインスタンスへ送らない(§9.2.3)。
- 会話(threadId)はユーザーに紐付く(K1 の `user.ai_conversation_threads`)。別アカウントのトークンでは読めない。

## 19. ログ、監視、監査

- ログに出すもの: 失敗の種類、HTTP ステータス、相関 ID(`x-request-id`)、`requestId`、ポーリングの回数、例外のクラス名。
- 出さないもの: 質問、回答、コード、ファイル名、選択テキスト、ファイルの内容、トークン、`/clear` `/reset` の失敗時のサーバの文言(本文を含みうるため、件数だけ記録)。
- 既存のロガー(`logger<T>()`)を使う。独自の監視・監査は追加しない。

## 20. 障害時の復旧方法

- 失敗した送信は、同じ popup でもう一度送れる(下書きが残っている)。
- 会話がおかしくなった場合は、`/reset`、または popup を閉じて開き直すと新しい会話になる。
- 状態はクライアントに永続化されないので、Eclipse の再起動ですべて消える。

## 21. 既存機能への影響

- MR の popup: `views/inlinethread` への追加はすべて既定値で従来挙動(§8.3)。`isSubmittable` の移設は同一実装。MR の popup とは別の枠(`QuickChatPopups`)なので、互いを閉じない。
- Duo Chat の挿入: `CodeFormatter` に引数版を追加し、既存の `format(snippet)` はそれに委譲する。結果は変わらない。
- `plugin.xml`: 追加のみ。`M1+M3+C` は既存のキーと衝突しない(E8)。
- LS との通信には触れない(GraphQL は GitLab へ直接送る)。

## 22. 移行方法 / ロールバック方法

設定やデータの移行は無い。ロールバックは実装 PR の revert。PR-2 だけを戻すと、PR-1 の通信層は呼び出し元が無いまま残るが、害は無い。

## 23. テスト方針

- **SWT 非依存層**(TDD、headless で実行): `QuickChatCommand`、`QuickChatContextBuilder`、`GitLabVersion`、`MarkdownCodeBlocks`、`QuickChatApi`(GraphQL クライアントを差し替えて、送る変数と応答の変換を検証)、`QuickChatPoller`(時計・待ち合わせ・問い合わせを差し替え: 即時応答 / 何回目かで応答 / 回数切れ / 途中のエラー / 他の requestId・role の混在 / `errors` あり / 空 / キャンセル / 接続先の変更)、`QuickChatService`(各段の失敗が `QuickChatOutcome` に写ること、`CancellationException` だけが外に出ること、送信ゲートが閉じていたら `aiAction` を送らないこと)、`SendGate`、`DetachedJobs`、`QuickChatSession`(偽の時計・偽の UI タイマー・偽の `runOnUi` を注入。`finishOnce` の 1 回性、期限の監視、完了フック、`end`)、`QuickChatConversation`(世代番号・チケットの照合、`/clear` `/reset`、閉じた後の結果の破棄、`toInlineModel`)。
- **SWT 層**: `QuickChatHost` は薄いアダプタで、`released` / `render` の写像を headless で検証する(MR の `MrThreadPopupHost` と同じ方式)。進行の論理は SWT 非依存の `QuickChatSession` にあり、PR-1 でテストする。popup の見た目・キー・フォーカス・挿入は手動検証(§25)。
- **既存の回帰**: `views/inlinethread` と MR 経路の既存テストがすべて通ること、`FAILSET_IDENTICAL`、detekt がベースライン(main 17 / test 45)から増えないこと。

## 24. 受け入れ条件

| # | 条件 | 確認方法 |
|---|---|---|
| A1 | `duo_chat_enabled` が偽のとき「Open Quick Chat」は無効で、コンテキストメニューに出ない | 手動 |
| A2 | テキストエディタで `Ctrl+Alt+C` を押すと、選択の終端行の下に popup が開き、入力欄にフォーカスがある | 手動 |
| A3 | 同じ行でもう一度開くと既存の popup が前面に出る。別の行・別のエディタで開くと置き換わる(送信中でも) | 手動 + ホストのテスト |
| A4 | 選択ありで送信すると回答が表示され、送った下書きが消える。送信中は入力欄と [Send] が無効 | 手動(実機アカウント) |
| A5 | 2 回目の質問が同じ会話として扱われる(1 回目の内容を踏まえた回答になる。M1 の変数に `threadId` が入る) | 手動 + API のテスト |
| A6 | 選択なしで送信すると `currentFile` を送らない | コンテキスト生成のテスト |
| A7 | `/clear` で会話欄が空になり、`/reset` で「New chat」が入り、次の質問が新しい会話になる | テスト + 手動 |
| A8 | 17.10 未満の GitLab では §14 のバージョン不足の表示になり、Send は戻る。プロジェクト内のファイルでも(`duoFeaturesEnabled` を知らない 16.8 以下でも)汎用の通信エラーにならない | preflight のテスト |
| A9 | どの失敗の後も Send が再び押せて、下書きが残っている | 会話・ホストのテスト + 手動(ネットワーク切断) |
| A10 | 回答待ちの間に popup を閉じても、後から届いた結果で何も起きない(例外・ログのエラーなし) | 会話のテスト + 手動 |
| A11 | 回答待ちの間に別の場所で開き直すと、古い回答が新しい popup に出ない | 会話・ホストのテスト + 手動 |
| A12 | [Copy] でコードがクリップボードに入り、通知が出る | 手動 |
| A13 | [Insert] で popup を付けたエディタの選択が置き換わり(選択なしならカーソル位置に入り)、1 回の Ctrl+Z で戻る | 手動 |
| A14 | 読み取り専用のファイルでは [Insert] で文書が変わらず、通知が出る | 手動 |
| A15 | 長い回答が会話欄でスクロールできる | 手動 |
| A16 | `c++` など `\w+` でない言語タグ、`~~~` のフェンス、閉じられていないフェンスでもボタンが出る | 分割のテスト |
| A17 | 失敗経路を通った後のログに質問・回答・コード・ファイル名・トークンが出ていない | 手動(ログの目視)+ テストで記録内容を検査 |
| A18 | MR の popup の挙動が変わらない(タイトル、送信ボタンの文言、Ctrl+Enter で送信されないこと) | 既存テスト + 手動 |
| A19 | popup の中の `Ctrl+Enter` で送信され、入力欄に改行が入らない。`canSubmit` が偽のときは何も起きない | 手動 |
| A20 | 背景処理がどこで止まっていても(応答の来ない HTTP、ヘッダの後に止まる本文、**割り込みを無視して永久に戻らない**接続の取得・プロジェクトの解決)、期限の監視が `ANSWER_DEADLINE` で `finishOnce` を呼び、Send が戻る。テスト本体は止まった処理の完了を待たずに終わる | 偽の時計・偽の UI タイマーと、ラッチで止まる(割り込みを無視する)偽の通信・偽のトークン取得・偽の解決を使ったセッションのテスト |
| A21 | 質問 16 KiB・選択 64 KiB の境界(ちょうど / 1 バイト超)、前後 32 KiB の切り詰め(近い側が残る、多バイト文字を割らない)、選択が 64 Ki 文字を超えると文字列を取らずに拒否 | コンテキスト生成のテスト |
| A22 | プロジェクト解決の 4 種類(§9.2.2)と Q1 の結果(project null / `duoFeaturesEnabled` false / null / true)ごとに、送る・送らないと `resourceId` が表のとおり | preflight のテスト |
| A23 | 1 回目の回答の後に接続先のインスタンスを変えて送ると、何も送らずに `ConnectionChanged` になり、次の送信は新しいインスタンスで新しい会話(`threadId` なし)になる。ポーリング中に変えた場合も同じ | サービス・会話のテスト |
| A25 | 非 ASCII の名前を percent-encoded の HTTP remote で clone したプロジェクトで、Q1 の `fullPath` がデコード済みになり、プロジェクトを確かめられる | preflight のテスト |
| A26 | 同じファイルを開いたまま remote / プロジェクトの割り当てを変えると、次の送信で新しいプロジェクトについて preflight をやり直し、古い `resourceId` と `threadId` を送らない。区切り「New chat」はその送信の質問の前に表示される | preflight・サービス・会話のテスト |
| A27 | 256 KiB を超える回答、31 個以上のコードブロック、41 項目目の追加で、それぞれ §9.7 の上限表のとおりになる | 分割・会話のテスト |
| A30 | 手放した処理が `MAX_DETACHED` 以上のあいだ、新しい送信は通信せずに `Busy` になり、`/clear` `/reset` の背景送信は行われない。止まっていた処理が完了すると数が減り、送信できるようになる。正常に実行中の送信は数えない(`MAX_DETACHED + 1` 個の会話が同時に送信しても拒否されない)。計数は全ウィンドウで 1 つ | `DetachedJobs`・セッションのテスト(複数の会話) |
| A31 | 期限は `submit` の最初の文で決まり、文脈の固定や背景処理の開始の遅れを含めて数えられる。`finishOnce` は (a) 背景の結果・(b) 期限の監視・(c) 完了フック・(d) 即時失敗のどの順序・組み合わせでも 1 回だけ効き、`released` は 1 回だけ呼ばれる。結果の反映や表示の更新が例外を出しても `released` は呼ばれる。scope が取り消し済み / 実行中に取り消されても、Send が戻る | セッションのテスト(順序を入れ替えた組み合わせ、例外を出す偽の view) |
| A28 | 送信ゲート: 期限切れ・閉じる・置き換えが `aiAction` の前に起きた場合、その後に背景処理が進んでも `aiAction` は送られない(`TimedOut(beforeSend = true)`)。`aiAction` の実行中の期限切れは `MaybeSent`、成功後は `TimedOut(beforeSend = false)` で `threadId` が会話に保存される。「何も送っていない」と表示した質問が送られることは無い | `SendGate`・サービス・セッションのテスト(w4 の直前で止めて UI 側を先に進める) |
| A29 | `/clear` `/reset` の背景送信が止まっても、画面上の効果と `threadId` の破棄は行われ、Send は使える。背景送信は `CLEAR_DEADLINE` で取り消され、完了するまで `DetachedJobs` に数えられる | 止まる偽物を使ったテスト |
| A32 | 取り消しで実行中の HTTP が止まる(`runInterruptible` + `HttpClient.send`)。ヘッダの後に本文が止まる偽サーバでも止まる | ループバックの偽サーバを使ったテスト(headless で実行可能) |
| A24 | 背景処理が `QuickChatConversation` に触れず、`bindingUpdate` は照合に通った `asyncExec` の中でだけ保存される(閉じた後・置き換え後の結果では保存されない) | 会話・ホストのテスト |

## 25. 手動検証手順(実装 PR の本文に転記)

実装 PR の本文に M1〜 として記載する。最低限: A1〜A5、A7、A9〜A15、A17〜A19 を、classic Duo Chat が使える実機アカウント(Windows / Pleiades)で確認する。ネットワーク切断(A9)、読み取り専用ファイル(A14)、Java と Java 以外のファイルでの Insert(整形の有無)を含める。

## 26. 未決事項

### 26.1 実装段階で決める(設計では記録のみ)

| # | 事項 | 決め方 |
|---|---|---|
| I1 | `platformOrigin` の値(候補 `eclipse_plugin`) | 実装計画で決める。サーバは任意の文字列を受ける(K5) |
| I3 | ポーリングの初回待ち・間隔、`ANSWER_DEADLINE` と 1 リクエストの上限の最終値 | 参照実装の 5000 ms 間隔 / 25000 ms と §15 の既定値を基準に実装計画で決める |
| I4 | 表示文言(§14)、popup のタイトル、ボタン名 | 実装時 |
| I5 | アンカー行の細部(行頭で終わる複数行選択など) | 実装時にテストで固定 |
| I6 | 回答の表示後に会話欄を末尾へスクロールするか | 実装時 |
| I7 | 挿入後に挿入範囲を選択 / 表示するか | 実装時 |

### 26.2 実機でしか確かめられない

| # | 事項 |
|---|---|
| U1 | threadId 付きのポーリングで Quick Chat の回答が取れること(K1・K2 はソースで確認済み。実接続での確認) |
| U2 | popup(TOOL Shell)にフォーカスがある状態で、アンカーのエディタの `selectionProvider` が最後の選択を返すこと |
| U3 | `M1+Enter` の処理が各 OS で改行を入れずに送信になること(`SWT.MULTI` の `Text` のキー処理) |
| U4 | `/clear` `/reset` を `conversationType` なしで送ったときのサーバの挙動(K1 から `threadId` 指定なら当該スレッドが対象になると読めるが、実接続で確認) |
| U5 | `validateEditorInputState()` の読み取り専用ファイルでの挙動 |
| U6 | コード部分(`StyledText`)を含む会話欄のスクロールと、popup の大きさ |

### 26.3 後続サイクルへの申し送り(ストリーミング)

wss 接続のプロキシ CONNECT、WebSocket への CA / mTLS、`Origin` と認証ヘッダ、トークン更新時の再接続、userId の取得、ActionCable プロトコルの自前実装、購読の後に送信する順序とポーリングへのフォールバック。`clientSubscriptionId` は今回から送る。

## 27. 想定されるリスク

| リスク | 影響 | 対策 |
|---|---|---|
| 参照実装に前例の無い組み合わせ(Quick Chat + ポーリング)| 実機で回答が取れない | K1・K2 をソースで確認済み。PR-1 の完了時点で、実機で API を叩く確認を依頼する(PR-2 の前) |
| ポーリングの遅延(最大 5 秒) | 体感が遅い | I3 で間隔を調整する。ストリーミングは後続 |
| GitLab の Experiment API(`aiAction` / `aiMessages` は Experiment 表記) | 将来の変更で壊れる | 17.10 版の形に固定し、失敗は §14 で表示する |
| 異常に大きい GraphQL 応答 | 既存の HTTP 層は本文全体を文字列で受け取ってから解析する(E `api/http/GitLabHttpClient.kt:18-31`)。巨大な応答でヒープを圧迫しうる | 本サイクルでは HTTP 層を変えない(既存のすべての API 呼び出しに共通する性質で、回答は LLM の出力でサーバ側で長さが限られる)。表示側の上限(§9.7)だけを設ける。受信バイト数の上限を HTTP 層に入れるのは後続候補(Codex round 4 #2) |
| OAuth のトークン更新が無期限に止まりうる(既存。E13) | `captureConnection` を使うすべての機能が同じ場所で止まる。Quick Chat では送信が期限で失敗し、4 回で `Busy` になる | Quick Chat は UI 側の期限の監視で Send を戻す(§15.1)ので、この欠陥に依存しない。認証コードは本サイクルで変えない。**後続候補**: `GitLabOAuthService` の ScribeJava に接続・読み取りのタイムアウトを設定する(`JDKHttpClientConfig.withConnectTimeout / withReadTimeout`)。全機能に効く修正で、更新失敗時の既存の挙動(通知と PAT への切り替え)に合流するため、別 issue で扱う |
| `CodeFormatter` の既存の弱さ(`null` の結果を扱わない) | Java の Insert で例外 | Quick Chat 側で例外を捕まえて整形なしで挿入する(§9.6)。Duo Chat 側の修正は本サイクルの対象外(後続候補として記録) |
| 置き換え時のコピー用ダイアログ | 新しい popup の前にモーダルが出る | 下書きがあるときだけ出る(既存の挙動)。手動で確認 |

## 28. 実装タスク分割案(モデルは CLAUDE.md の選定基準に従う)

**PR-1 通信・セッション基盤**(`feat/quick-chat-transport`、UI への配線なし)

| タスク | 内容 | モデル |
|---|---|---|
| T1 | `QuickChatCommand` / `QuickChatContextBuilder` / `GitLabVersion`(純粋関数 + テスト) | sonnet |
| T2 | `QuickChatApi`(M1 / M2 / Q1 / Q2 + 応答の型 + テスト) | opus |
| T3 | `QuickChatPoller` / `QuickChatPreflight` / `QuickChatService` / `SendGate`(結果の写像、`runInterruptible`、送信ゲート、会話の結び付きと接続の照合、`GitLabProjectUrlResolver` の 4 種類の兄弟メソッド + テスト。A32 の偽サーバのテストを含む) | opus |
| T4 | `QuickChatConversation` / `QuickChatSession` / `QuickChatRuntime` / `DetachedJobs`(世代番号・チケット照合・`finishOnce`・期限の監視・完了フック・`toInlineModel` + テスト) | opus |

**PR-2 UI・コード操作**(`feat/quick-chat-ui`)

| タスク | 内容 | モデル |
|---|---|---|
| T5 | `views/inlinethread` の一般化(`isSubmittable` 移設、タイトル・送信ラベル・`codeBlocks`・`onCodeAction`・`M1+Enter`)+ 既存テストの維持 | opus |
| T6 | `MarkdownCodeBlocks`(+ テスト) | sonnet |
| T7 | `QuickChatPopups` / `QuickChatHost` / `QuickChatContextCapture`(`QuickChatView` の写像、`Display.asyncExec` / `timerExec` の注入、バンドル停止の順序 + ホストのテスト) | opus |
| T8 | Copy / Insert(`QuickChatSnippetInserter`、`CodeFormatter` の引数版) | opus |
| T9 | ハンドラ 2 件と `plugin.xml` の配線 | opus |

**実装レビュー(Codex)の重点確認項目**: プロジェクト確認のフェイルクローズ(§9.2.2 の表)と接続先の結び付き(§9.2.3)、UI スレッド境界(`asyncExec` のみ、背景処理から `IDocument` / SWT に触れていない)、`finishOnce` が唯一の終端であること(§9.2.4。チケットを解放する経路が他に無いか)、送信ゲートの比較交換(§12.4)、`runInterruptible` の外にブロックする呼び出しが無いこと、`DetachedJobs` の計数の増減、ポーリングの終了条件と接続の取り直し、ログに本文が出ないこと、MR popup の既定値が従来挙動のままであること、`/clear` `/reset` の判定と threadId の破棄、Insert の undo 単位と編集可否の確認。

## 29. Codex レビュー反映履歴

仕分けの基準は CLAUDE.md「設計レビューの深さと収束基準」(設計で解決 / 実装段階へ / 不採用)。

### round 1(`d0fa281` に対する指摘 5 件、すべて P1)

| # | 指摘 | 仕分け | 反映 |
|---|---|---|---|
| 1 | 会話を接続先に固定せず、インスタンス変更後に古い `threadId` と Project gid を別インスタンスへ送りうる | 採用・設計(データの送り先の境界) | §9.2.3 を新設。会話をインスタンスに結び付け、不一致なら何も送らず `ConnectionChanged` → 結び付きを破棄。§9.3、§9.4、§12.1、§12.3、§14、§18、A23 |
| 2 | プロジェクトを解決できないと `resourceId = null` で送ってしまい、プロジェクト単位の Duo 無効化を回避しうる | 採用・設計(認可境界) | §9.2.2 を書き直し。解決結果を 4 種類に型で分け、同一インスタンスのプロジェクトは gid と `duoFeaturesEnabled` を確かめられた場合だけ送る。別インスタンス・解決失敗・プロジェクト不明は送らない。Q1 に `duoFeaturesEnabled`(K11)。§8.3 に resolver の兄弟メソッド。§12.3、§14、§18、A22 |
| 3 | preflight の結果を誰がどのスレッドで保存するかが無く、背景処理が UI 専用の状態に触れる実装を誘う | 採用・設計(UI スレッド境界) | §9.2 手順 5〜14 を書き直し。要求に不変の `binding` を渡し、結果の `bindingUpdate` を照合済みの `asyncExec` の中でだけ保存。§8.1、§12.1〜12.3、A24 |
| 4 | 回数 × 間隔の上限は HTTP タイムアウトと両立せず、最悪 10 分近く Send が戻らない | 採用(規則のみ設計で確定。値は実装段階) | §15 に単調時計の送信全体の期限、HTTP タイムアウトの残り時間への切り詰め。§9.3、A20 |
| 5 | 文脈のサイズ上限と切り詰め規則が未確定 | 採用(規則と既定値を設計で確定) | §9.2.1 に UTF-8 バイトでの上限表、近い側を残す切り詰め、UI スレッドで窓だけを取る方法。§12.3 `TooLarge`、§14、A21。§26 の I2 を削除 |

### round 2(`c617418` に対する指摘 3 件)

| # | 指摘 | 仕分け | 反映 |
|---|---|---|---|
| 1 | P1 接続の取得(OAuth のトークン更新)が期限の外で止まりうる | 採用・設計(スレッド・期限の境界) | §15 に「ブロックする処理はすべて期限の内側で待つ」(子処理 + `withTimeoutOrNull`)と段階ごとの結果。§12.3 `TimedOut(beforeSend)`、A20 を拡張 |
| 2 | P1 同じファイルのまま remote / 割り当てを変えると古いプロジェクトの preflight を使い続ける | 採用・設計(認可境界) | §9.2.2 手順 4〜5: 送信のたびにアンカーを解決し、プロジェクトの対応キーが変わったら preflight と `threadId` を捨てて新しい会話に。§12.1、A26 |
| 3 | P2 Q1 の `fullPath` が percent-escape のままになる | 実装段階へ(純ロジック。テストで検出できる) | §9.2.2 に 1 行と A25 のみ |

### round 3(`b5a4e11` に対する指摘 5 件)

| # | 指摘 | 仕分け | 反映 |
|---|---|---|---|
| 1 | P1 会話の変更を popup に反映する契約が無い(`refresh()` は本文を再描画しない) | 採用(再利用するインターフェースの契約) | §8.3 に `InlineThreadSurface.update(model)`、§9.2 に「表示の反映」 |
| 2 | P1 Q1 が 16.9 のフィールドと同じ文書で、古いサーバではバージョン不足ではなく通信エラーになる | 採用・設計(実機の旧サーバでしか露見しない) | §11.2 をバージョン単独 → project の 2 段に分離。§9.2.2 手順 3、A8 |
| 3 | P1 止まる処理を structured child で待つと期限を守れない | 採用・設計(並行処理) | §15 に専用の有界 executor + `CompletableFuture` + 取り消し可能な `await`、放置 future の扱い、スレッド枯渇時の即時失敗。A20 |
| 4 | P2 回答と表示履歴に資源上限が無い | 採用(規則と既定値のみ) | §9.7 に上限表、A27 |
| 5 | P2 プロジェクト変更時の区切りが質問の後ろに入る | 実装段階へ(純ロジック) | §9.2.2 手順 5 に挿入位置の 1 行、A26 に表示順 |

### round 4(`bc188fa` に対する指摘 3 件)

| # | 指摘 | 仕分け | 反映 |
|---|---|---|---|
| 1 | P1 executor 枯渇を常に「送信前」と分類すると、`aiAction` 後の枯渇で二重送信を誘う | 実装段階へ(純ロジック。段階の分類規則だけ設計で 1 行) | §15 の分類規則、A28 |
| 2 | P1 GraphQL 応答をバッファリング前に制限する | 不採用(本サイクル)・後続候補 | HTTP 層の横断的な性質で、既存の全 API 呼び出しに共通する。回答は LLM 出力でサーバ側で長さが限られる。§27 にリスクと後続候補として記録 |
| 3 | P1 `/clear` `/reset` の背景送信に期限・所有者・資源の上限が無い | 採用・設計(並行処理・資源) | §15 に別 executor(1 スレッド)、`CLEAR_DEADLINE`、所有者、枯渇時の扱い。A29 |

### round 5(`4393428` に対する指摘 3 件)

| # | 指摘 | 仕分け | 反映 |
|---|---|---|---|
| 1 | P1 失効しうる共有 scope で起動すると、取り消し時に busy のまま残る | 採用・設計(並行処理) | §17 に Quick Chat 専用の `SupervisorJob` scope と、`invokeOnCompletion` による必ずのチケット解放。A31 |
| 2 | P1 期限の起点が背景処理の開始で、開始待ちが含まれない | 採用(規則のみ。純ロジック) | §9.2 手順 3-4 で UI スレッドが期限を決め、要求に入れる。A31 |
| 3 | P1 待ち行列なしの executor は正常な同時処理まで拒否する | 採用・設計(並行処理) | §15 を有界の待ち行列(既定 16)に変更。A30 |

### round 6(`c0c4bf2` に対する指摘 5 件)と方式の再検討(2026-09-28、ユーザー指示)

round 2〜6 の 19 件中 9 件が「期限・止まる処理の隔離・executor・scope」の同じ層に集中し、毎巡新しい欠陥が見つかった。これを「細部の詰め不足」ではなく「方式の形が悪い」兆候とみなし、実ソースと実測に基づいて方式を比較した。

**確定した事実**: `HttpClient.send` は割り込みで止まる(本文の途中でも)。`HttpRequest.timeout` は本文の途中の停止を守らない。OAuth のトークン更新(ScribeJava の `HttpURLConnection`、タイムアウト未設定)は割り込みでも止まらない(E12〜E14)。

| 観点 | A: 専用 executor で隔離(round 5 の設計 + 5 件修正) | C: 各処理をタイムアウト化(OAuth を含む) | **D: UI 側の期限の監視 + 割り込み + 送信ゲート(採用)** |
|---|---|---|---|
| Send が戻る保証 | 各段を個別に期限つきで待つ。段ごとに放置・拒否・分類の規則が要り、抜けが出やすい(round 3〜6) | 各タイムアウトの合計。本文の途中の停止と JGit は守れない(E12) | **1 か所(UI タイマー)。背景処理の状態に依存しない** |
| 二重送信・誤表示の防止 | 段階の分類を executor の拒否・期限ごとに定義(round 4 #1) | 同左 | **送信ゲートの比較交換 1 回で「送っていない」を保証** |
| 変更範囲 | Quick Chat 内。executor 2 つ・待ち行列・`CompletableFuture` | **認証コード(全機能に影響)** + HTTP 層 | Quick Chat 内のみ。executor なし |
| 資源の上限 | スレッド 4 + 待ち 16 + M2 用 1。所有者・停止順の規則が要る(round 6 #4) | 上限は不要だが、止まる処理が残ると無制限 | 計数 1 つ(`MAX_DETACHED`)。スレッドは `Dispatchers.IO` |
| テスト可能性 | executor・待ち行列・`cancel` の意味に依存(`CompletableFuture.cancel(true)` は割り込まない。round 6 #5) | 実時間のタイムアウトに依存 | 偽の時計・偽の UI タイマー・偽の `runOnUi` で決定的にテストできる |
| Quick Chat 以外への影響 | なし | **あり**(OAuth 更新の失敗経路に合流) | なし |

**結論: D を採用。** C の OAuth タイムアウトは単独では保証にならないが、全機能に有益なので後続候補として §27 に記録した(本サイクルでは認証コードを変えない)。

| # | round 6 の指摘 | 仕分け | 反映 |
|---|---|---|---|
| 1 | P1 §7 / §8.2 の「共有 scope」が §17 の専用 scope と矛盾 | 採用 | §7、§8.1、§8.2、§17 を `QuickChatRuntime` の専用 scope に統一。所有者・生成数・停止順を明記 |
| 2 | P1 期限の起点が `beginSubmit` の直後でない | 採用 | §9.2 手順 2: `submit` の最初の文で期限を決める。A31 |
| 3 | P1 `asyncExec` と完了フックの終端処理が分かれている | 採用・設計(並行処理) | §9.2.4 に唯一の終端 `finishOnce`。状態の切り替えを先に行い、解放は `finally`。A31 |
| 4 | P1 通常用 executor の所有者と個数が未定義 | 方式変更で解消 | executor を廃止。scope と計数は `QuickChatRuntime`(バンドルに 1 つ)。§15.3、A30 |
| 5 | P2 `CompletableFuture.cancel(true)` は割り込まない | 方式変更で解消 | `CompletableFuture` を廃止。割り込みは `runInterruptible`(E14)、止まることは実測(E12)と A32 で確認。止まらない処理が残ることを §15.3 に明記 |

round 2 #1、round 3 #3、round 4 #1・#3、round 5 #1〜#3 の反映内容(§15・§17 の旧記述)は、この方式変更で置き換えた。各指摘が求めた性質(期限の保証、段階に応じた分類、`/clear` `/reset` の資源の上限、scope の隔離、正常な同時処理を拒否しないこと)は、それぞれ §15.1、§12.4、§15.4、§17、§15.3 で満たす。
