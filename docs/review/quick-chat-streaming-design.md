# D4 Quick Chat ストリーミング表示(ActionCable 補助経路)設計書

- 対象リポジトリ: `sumikof/gitlab-eclipse-plugin` / ベース: `gitlab-ls-9.3.0` @ `db42f74`
- 前提設計: D4 Quick Chat 設計書 `docs/review/quick-chat-design.md` @ `e4bf4e8`(PR #99、Close 済み。以下「QC 設計」)。§26.3 の申し送りを本設計で扱う
- 机上調査と方式比較: #20 のコメント(2026-09-30「Quick Chat の ActionCable ストリーミング化」)
- 関連する実装 PR: なし(本設計のレビュー後に 2 本作成する)
- 参照実装: `gitlab-workflow` v6.85.3(以下 REF = `out/gitlab-vscode-extension/src/common`)
- 言語サーバ: gitlab-lsp 9.3.0(以下 LS。`main.js.map` の sourcesContent)
- GitLab 本体: `gitlab-org/gitlab` master `762ad9e`(以下 GL。#20 の調査で読んだ箇所を引用する)
- 自リポジトリ: 以下 E = `src/main/kotlin/com/gitlab/eclipse`、Q = `E/chat/quickchat`

---

## 1. 背景と目的

Quick Chat(PR #100 / #101)は、`aiAction` を送ったあと `aiMessages` を 3 秒間隔でポーリングし、**完成した回答を一度に**表示する(Q `QuickChatPoller.kt:43-63`)。回答の生成には数十秒かかることがあり、その間は「待機中」の表示しかない。

GitLab は `aiAction` に付けた `clientSubscriptionId` ごとに、回答の断片(チャンク)を ActionCable(WebSocket)で配信している(GL `ee/lib/gitlab/llm/completions/chat.rb:141-144`)。Eclipse 版は `clientSubscriptionId` を既に送っている(Q `QuickChatService.kt:45,109`)が、購読していない。

本設計の目的は、**この配信を購読して回答を逐次表示する**こと。ただし ActionCable は補助経路とし、**現行のポーリングを回答取得の正とする**(方式 ②-h、2026-10-04 ユーザー承認)。ストリームが壊れても、利用者の体験は現状より悪くならない。

## 2. 対象範囲

- WebSocket(ActionCable `actioncable-v1-json`)クライアントの新設(既存の TLS / プロキシ設定を共有)。
- `aiCompletionResponse` 購読と、チャンクの組み立て(順不同・振り直し・最終メッセージでの置き換え)。
- Quick Chat の送信フローへの組み込み(購読 → 確認待ち → `aiAction` → ポーリングとストリームの並走)。
- 待機中の回答欄への途中経過の表示(描画の間引きを含む)。

## 3. 対象外

- サイドバーの Duo Chat(LS の webview が独自にストリーミングしている)。
- 設定のトグル(「ストリーミングを使う」)。**設けない**(ユーザー承認)。②-h では既定で安全なため。
- 再接続。1 回の送信につき 1 本の接続で、切れたらその送信ではストリームを諦める(ポーリングが回答を届ける)。
- `/clear` `/reset`(回答が無いので購読しない)。
- Agentic Chat(別経路 `workflowEventsUpdated`)。
- ポーリングの間隔・期限・完了判定・エラー処理の変更(一切変えない)。

## 4. 現在の課題

- 最初の文字が出るまで、最短でも回答完成 + 最大 3 秒(ポーリング間隔)かかる。
- 長い回答では数十秒「待機中」のままで、動いているかどうか利用者に分からない。

## 5. 要件

### 5.1 機能要件

| # | 要件 |
|---|---|
| FR-S1 | 回答の生成中、届いたチャンクを先頭から連続している範囲だけ待機中の欄に表示する。 |
| FR-S2 | ストリームの最終メッセージ(`chunkId` 無し、`errors` 無し)が届いたら、途中経過の**表示**を最終メッセージの内容で置き換える(後処理で内容が変わるため。GL `react_executor.rb:60,187-194`)。これも表示だけで、送信は完了させない。 |
| FR-S3 | **送信の完了・回答・エラーはポーリング(`aiMessages`)の結果だけで確定する**(Codex round 1 #1)。ストリームは途中経過の表示にだけ使い、送信の終わり方・表示される最終の回答・エラーの判定に一切影響しない。完了時の表示はポーリングで得た回答で置き換える。 |
| FR-S4 | ストリームの失敗(接続不可・購読拒否・切断・不正なフレーム・上限超過)は**利用者に通知しない**。その送信のストリームを閉じるだけで、ポーリングが回答を届ける。 |
| FR-S5 | 購読の確認が取れない場合は、上限時間だけ待ってからストリーム無しで `aiAction` を送る(購読より前の配信は受け取れないため。GL Redis pub/sub)。 |
| FR-S6 | 送信が終わったとき(回答・失敗・期限切れ・置き換え・閉じる・バンドル停止)は、必ずソケットを閉じる。 |

### 5.2 非機能要件

| # | 要件 |
|---|---|
| NFR-S1 | トークン・ユーザー ID・質問本文・回答本文・チャンク本文をログに出さない(QC 設計 NFR-4 を継承)。ログはイベントの種類・件数・例外クラス名・`requestId` のみ。 |
| NFR-S2 | WebSocket にも HTTP と同じ TLS(CA / mTLS / 証明書エラーの無視)とプロキシを適用する(REF は適用していない欠陥がある。`gitlab/api/api_client.ts:64-72`)。 |
| NFR-S3 | UI スレッドで WebSocket の待ち・`syncExec`・ネットワーク I/O をしない。 |
| NFR-S4 | 受信データの量に上限を設ける(§15.3、保持データ全体のバイト上限を含む)。サーバの異常で IDE のメモリや UI スレッドを使い潰さない。 |
| NFR-S5 | ストリームの有無で、完了判定・エラー表示・期限(120 s)・`SendGate` の意味が変わらない。 |

## 6. 前提条件と制約

### 6.1 共通制約(#8)

ディレクトリ構成・ビルドシステムを変えない。新規 OSGi 依存・新規ライブラリを追加しない(JDK 21 の `java.net.http.WebSocket` と、既存の Gson を使う)。

### 6.2 検証上の制約(最重要)

- 現アカウントは `duoClassicChatAvailable=false` で、Duo の回答本文が得られない(Quick Chat の U1 は `M3006` で回答本文が未確認)。**チャンクが届く・逐次表示される・最終メッセージで置き換わる**ことは実機で確かめられない。D4 ストリーミングは「実装済み・実機未検証」の扱いになる。
- 接続層はスパイクで確認済み(§6.3)。
- devcontainer は headless で、SWT の逐次描画は自動テストで検出できない(Codex の重点確認項目 + 手動検証手順で担保)。

### 6.3 確定事実(スパイク、2026-10-04、gitlab.com、コミット無し・mutation 無し)

JDK 21 `HttpClient.newWebSocketBuilder()` で `wss://gitlab.com/-/cable` に接続した結果(ユーザー実行、認証情報・ユーザー ID・本文は非出力):

| # | 事実 |
|---|---|
| K-S1 | `Authorization: Bearer <OAuth トークン>` + `Origin: https://gitlab.com` + サブプロトコル `actioncable-v1-json` でハンドシェイクが成功する。JDK の `WebSocket.Builder.header("Origin", …)` は制限ヘッダとして拒否されない。 |
| K-S2 | `welcome` を受信した後に `subscribe`(`GraphqlChannel`、`aiCompletionResponse`、`userId` = `gid://gitlab/User/<id>`、`aiAction: CHAT`、ランダムな `clientSubscriptionId`)を送ると、`confirm_subscription` が届く。**Duo Chat の利用権が無いアカウントでも購読は確認される**(つまり購読の確認は回答が来ることを意味しない)。 |
| K-S3 | 購読の直後に `{"result":{"data":{"aiCompletionResponse":null}},"more":true}` 形のチャネルメッセージが 1 件届く(GL `graphql_channel.rb:10-43` と一致)。 |
| K-S4 | `Origin` ヘッダ無しでは `WebSocketHandshakeException` で接続が失敗する(GL `config/initializers/action_cable.rb:10-34` / Rails 既定の origin 検査と一致)。 |
| K-S5 | 12 秒の観察後に `unsubscribe` → close で正常終了する。 |
| — | ping(ActionCable は既定 3 秒間隔)の受信は**スパイク報告に含まれず未確認**。本設計は ping に依存しない(§15.2)。 |

### 6.4 確定事実(机上調査、#20 2026-09-30)

| # | 事実 | 根拠 |
|---|---|---|
| K-S6 | 認可: 購読時と 10 分ごとにトークンのスコープ(`api` / `read_api` / `ai_features`)を検査。`userId` は本人一致が必須。 | GL `channel.rb:8-31`、`ai_completion_response.rb:25-26` |
| K-S7 | チャンクは `chunkId` 1..N の**差分**。1 つの `requestId` 内で `chunkId` が 1 から振り直されることがある(ステップの再試行、最大 2 回)。打ち切りの警告も追加チャンクとして届く。 | GL `streamed_answer.rb:7-25`、`react_executor.rb:80-87,254`、`step_executor.rb:89-119` |
| K-S8 | 要求ごとのストリームに `chunkId` 無しの完成形が 1 件届く。エラー(A1000〜A1006 / A9999 / G3001・G3002 / M3006)も `errors` 付きの最終メッセージとして届く。 | GL `chat.rb:91-97` |
| K-S9 | 配信は**最大 1 回**で再送なし。購読前の配信は失われる。ワーカー例外などで最終メッセージが来ないことがある。最終メッセージは保存され `aiMessages` で回収できる。 | GL Redis pub/sub、`completion_service.rb:18,46-50` |
| K-S10 | ストリーミングは 16.8 から。Quick Chat は 17.10 以上に限定済みなので追加の版判定は要らない。スキーマ上 `aiCompletionResponse` は Experiment。 | GL スキーマ |
| K-S11 | REF の購読変数: `{htmlResponse:false, userId, aiAction:'CHAT', clientSubscriptionId}` を JSON 文字列で `variables` に入れる。query は §11.2。 | REF `api/graphql/ai_completion_response_channel.ts:19-43,110-126` |
| K-S12 | LS は ActionCable の結果を LSP クライアントに渡すメソッドを持たない(流用不可)。 | LS `$/gitlab/*` の全列挙 |

### 6.5 自リポジトリの確定事実

| # | 事実 |
|---|---|
| E-1 | すべての HTTP は `GitLabHttpClient.rebuildIfNeeded()` の `HttpClient` を使う。設定変更時は旧クライアントを `shutdown()` して作り直す(E `api/http/GitLabHttpClient.kt:22-31`)。TLS・プロキシは `GitLabHttpClientFactory.create`(`:36-49`)に集約。 |
| E-2 | 送信の背景処理は `QuickChatService.ask` の 1 コルーチンで、結果は `ResultSink.deliver` で UI に 1 回だけ渡る(Q `QuickChatService.kt:63-75`、`ResultSink.kt:27-30`)。終端は `QuickChatSession.finishOnce` だけ(Q `QuickChatSession.kt:123-136`)。 |
| E-3 | 送信の放棄(`abandon`)は `job.cancel()` と `ResultSink.detach()` を行う(Q `QuickChatSession.kt:168-177`)。バンドル停止は `QuickChatRuntime.scope` を cancel する(Q `QuickChatRuntime.kt:31-33`)。 |
| E-4 | `InlineThreadPopup.render()` は描画のたびに子コントロールを全部破棄して作り直す(E `views/inlinethread/InlineThreadPopup.kt:313-345`)。`QuickChatView.render` は送信 1 回につき 2 回しか呼ばれない(Q `QuickChatSession.kt:73,134`)。 |
| E-5 | 待機中の欄は `Entry.Pending`(data object)で、`resolvePending` が最後の `Pending` を結果で置き換える(Q `QuickChatConversation.kt:28,97-100`)。 |
| E-6 | リポジトリに WebSocket のコードは無い。 |

## 7. システム構成

```
 UI スレッド                                背景(QuickChatRuntime.scope, Dispatchers.IO)
 ───────────                                ────────────────────────────────────────────
 QuickChatSession.submit ──launch──▶ QuickChatService.ask
                                     w1-w3  接続・preflight(既存)
                                     s1     AiCompletionStream.open(connection, subscriptionId)  ※新規
                                              ├ userId 取得(currentUser)
                                              ├ ActionCableClient: connect → welcome → subscribe
                                              └ confirm を最大 SUBSCRIBE_WAIT 待つ(取れなければ stream=null)
                                     w4-w5  SendGate → aiAction(既存。同じ clientSubscriptionId)
                                     w6     poller.await(既存・無変更)が Outcome を確定する唯一の経路
                                              その間 stream は途中経過だけを流す(結果は出さない)
                                     finally stream.close()(abort)
   ◀── ResultSink.progress(text) ─── ChunkAssembler の表示可能テキスト(間引き)  ※新規
   ◀── ResultSink.deliver(outcome) ─ (既存・1 回だけ)
 QuickChatSession.onProgress → Pending の途中経過を更新 → 間引いて render
 QuickChatSession.finishOnce(既存・無変更)
```

## 8. コンポーネントの責務

### 8.1 SWT 非依存層(PR-1、配線なし)

新規パッケージは作らず Q(`com.gitlab.eclipse.chat.quickchat`)に置く。

| 名前 | 責務 |
|---|---|
| `CableEndpoint` | インスタンス URL から WebSocket URL と `Origin` を作る純関数。`https→wss` / `http→ws`、パスは `<instanceUrl の末尾 / 付き>-/cable`(サブパス対応。REF `gitlab/api/action_cable.ts:7-12`)、`Origin` = `scheme://host[:port]`(既定ポートは省略)。 |
| `CableSocketFactory`(interface)/ `JdkCableSocketFactory` | `HttpClient.newWebSocketBuilder()` で接続する唯一の場所。ヘッダは `Authorization: Bearer` / `Origin` / `User-Agent`(既存 HTTP と同じ値)の 3 つ、サブプロトコル `actioncable-v1-json`。`HttpClient` は `GitLabHttpClient.rebuildIfNeeded()` から取る(NFR-S2)。テストでは偽ソケットに差し替える。 |
| `ActionCableClient` | ActionCable プロトコル(`welcome` / `ping` / `confirm_subscription` / `reject_subscription` / `disconnect` / チャネルメッセージ)の状態機械。1 接続 1 購読。テキストフレームの分割受信の連結、フレーム長の上限、`subscribe` / `unsubscribe` の組み立て。受信コールバックは例外を投げない。 |
| `AiCompletionStream` | 1 回の送信のストリーム。`open` は接続と購読確認までを `SUBSCRIBE_WAIT` 以内に行い、確認できなければ閉じて null を返す。確認後は受信を `ChunkAssembler` に渡し、表示可能テキストが変わるたびに登録されたコールバック(`onProgress(text)`)を呼ぶだけで、**結果(完了・エラー)を返す API を持たない**。`close()` は冪等で即時(`abort`)。 |
| `ChunkAssembler` | 純ロジック。`requestId` ごとにチャンクを保持し、先頭から連続する範囲を表示用テキストとして返す。系列(振り直し)・順不同・空チャンク・最終メッセージの規則は §9.2。`requestId` 未確定の間のフレームは保持し、確定後は他の `requestId` を捨てる。保持データ全体を `MAX_BUFFERED_BYTES` で制限する(§15.3)。 |
| `QuickChatApi.currentUserId`(追加) | `query { currentUser { id } }`。`userId` の取得。null または失敗ならストリーム無し。 |

### 8.2 配線(PR-2)

| 対象 | 変更 |
|---|---|
| `QuickChatService.send` | w3 の後、w4 の前に s1(`AiCompletionStream.open`)。`aiAction` には同じ `clientSubscriptionId` を渡す。w5 の後に `requestId` をストリームへ確定させる。w6(ポーリング)は無変更。`finally` で `stream.close()`。 |
| `ResultSink` | `progress(text)` を追加。背景から途中経過を UI へ渡す。最新値だけを保持し、UI 側に未処理の投函が 1 件あれば新たに投函しない(洪水防止)。`detach` 後は何もしない。 |
| `QuickChatSession` | `onProgress(ticket, gen, text)` を追加(UI スレッド)。`isCurrent` でなければ捨てる。待機中の欄に途中経過を入れ、`RENDER_INTERVAL` で間引いて `view.render`。`finishOnce` で予約済みの描画を取り消す。 |
| `QuickChatConversation` | 待機中の欄に途中経過を持たせる(`Entry.Pending` → 途中経過付きの形)。`resolvePending` の探索を新しい形に合わせる。途中経過は Markdown のコード分割をしない(`codeBlocks = false`)。完成した回答で置き換えたときに初めてコードブロックのボタンが出る。 |
| `QuickChatTexts` | 途中経過を示す見出し(文言は実装段階)。 |

既存 API の変更は追加のみ。`QuickChatOutcome` / `SendGate` / `finishOnce` / `QuickChatPoller` / `QuickChatLimits` の既存値は変えない。

## 9. 処理フロー

### 9.1 送信(QC 設計 §9.2 w1〜w7 への差分)

1. w1〜w3(接続の取得、preflight)は無変更。
2. **s1(新規)**: `clientSubscriptionId` を生成(既存の `newSubscriptionId()` を前倒しで 1 回だけ呼ぶ)。`AiCompletionStream.open(connection, clientSubscriptionId, waitLimit)` を呼ぶ。`waitLimit = min(SUBSCRIBE_WAIT, 期限までの残り)`。
   - `userId` を `currentUser` で取得 → 接続 → `welcome` → `subscribe` → `confirm_subscription` を待つ。
   - **確認とみなすのは `confirm_subscription` の受信のみ**(K-S2)。K-S3 の初回メッセージは確認の代わりにしない。
   - 時間切れ・拒否・例外・`Origin` 不一致(K-S4)はすべて「ストリーム無し」(null)。ソケットは閉じる。利用者には何も見せない(FR-S4)。
3. w4(`SendGate.tryBeginSend`)が false(UI が既に終えた)なら、ストリームを閉じて null を返す(既存どおり)。
4. w5 `aiAction`(既存。s1 と同じ `clientSubscriptionId`)。
5. w5 の応答で得た `requestId` をストリームに確定させる(`aiAction` が `errors` 付き・`requestId` 無しで終わった場合は、ストリームを閉じて既存どおりの結果を返す)。
6. **w6(無変更)**: `poller.await(...)` が結果を確定する唯一の経路(FR-S3)。ポーリングの間隔・初回待ち・期限・判定は一切変えない。ストリームの最終メッセージが先に届いても、送信は完了させない(ポーリングが同じ回答を拾うまで最大 1 間隔の表示差が出るだけ)。
7. 途中経過: ストリームが動いている間、`ChunkAssembler` の表示可能テキストが変わるたびに `ResultSink.progress(text)`(ストリームのコールバックから。背景スレッド)。
8. `finally`: `stream.close()`。cancel(放棄・バンドル停止)でも必ず通る。`close` は `WebSocket.abort()` で待たない。
9. w7 `ResultSink.deliver`(既存)。`deliver` の時点でストリームは閉じている(`finally` が先)ので、以後 `progress` は来ない。

### 9.2 チャンクの組み立て(`ChunkAssembler`)

- 入力はチャネルメッセージの `aiCompletionResponse`(null はスキップ。K-S3)。
- `role` が ASSISTANT 以外(SYSTEM 等)は表示にも完了にも使わない(REF は SYSTEM を別扱い。完了判定は P に任せる)。
- `chunkId` 有り → 途中経過。`content` が null は空文字として扱い、**欠番を作らない**(REF の「空チャンクで止まる」欠陥 `quick_chat/response_processor.ts:41-50` を引き継がない)。
- **系列の判定(Codex round 1 #3)**: サーバは再試行の世代を示す項目を送らない(K-S7)。1 系列の中では各 `chunkId` は最大 1 回しか配信されない(K-S9: 最大 1 回配信・再送なし)。したがって **既に保持している `chunkId` がもう一度届いたら、それは新しい系列の断片**とみなし、その時点の保持分をすべて破棄して、届いた断片から新しい系列を始める。
  - 例: 旧 1, 2 を保持 → 新 2 → 重複なので破棄して {新 2} → 新 1 → {新 1, 新 2} を表示。旧 1, 2 → 新 1 → 破棄して {新 1} → 新 2 → {新 1, 新 2}。
  - 新系列が始まった後に旧系列の未着の断片(例: 旧 3)が遅れて届くと、新系列に混ざりうる。**表示だけの問題で、送信の結果はポーリングで確定し置き換わる**(FR-S3)ため許容する(§27)。
- `chunkId` 無し → 最終メッセージ。`requestId` 一致かつ `errors` 無しなら、表示を最終の `content` で置き換え(FR-S2)、以後のチャンクは無視する。`errors` 有りなら表示を変えない(エラーの表示はポーリングの結果で行う)。
- 表示可能テキスト = `chunkId` 1 から連続する範囲の連結。

### 9.3 ストリームと送信の終わり

- 送信の終わりはポーリング(または UI 側の期限・放棄)だけが決める。ストリームは結果を返さないので、ストリームの状態で送信が早く終わることも、長引くこともない。
- 送信全体の期限は従来どおり UI 側の `onDeadline` が守る(QC 設計 §15.1)。

### 9.4 UI への途中経過の反映

1. 背景: `ResultSink.progress(text)` → 最新値を保存し、未処理の投函が無ければ `runOnUi { onProgressUi() }`。
2. UI: `session.onProgress(ticket, gen, latest)`。`isCurrent` でなければ捨てる。途中経過を待機中の欄に保存。描画の予約が無ければ `scheduleOnUi(RENDER_INTERVAL) { render }` を予約。
3. `finishOnce` は予約済みの描画を取り消してから既存の処理をする(最終描画は `finishOnce` の `render` だけ)。
4. 途中経過の表示は `MAX_ANSWER_BYTES` で切る(既存の回答と同じ上限)。

### 9.5 閉じる・置き換え・バンドル停止

既存の `abandon`(`job.cancel()`)で背景コルーチンが cancel され、§9.1 手順 7 の `finally` でソケットが閉じる。`ResultSink.detach` 後の `progress` は何もしない。追加の後始末経路は作らない。

## 10. 等価表(VSCode → Eclipse)

| VSCode | Eclipse | 備考 |
|---|---|---|
| `@anycable/core` の購読 1 本/1 ソケット | `ActionCableClient` 1 本/送信 | 同等 |
| ストリームのみで回答を取得 | 回答の確定はポーリングのみ、ストリームは途中経過の表示のみ | 改善(K-S9 の欠落対策) |
| TLS / プロキシ未適用 | 適用 | 改善 |
| 順不同チャンクを 1 件しか排出しない / 空チャンクで止まる | 連続範囲をすべて表示 / 空チャンクは欠番にしない | 欠陥を引き継がない |
| `errors` を無視 | エラーはポーリングの結果で既存どおり表示 | 改善 |
| 旧ソケットを切断しない | 送信の終わりで必ず閉じる | 改善 |

## 11. API / インターフェース

### 11.1 ActionCable(`actioncable-v1-json`)

- 受信: `{"type":"welcome"}` / `{"type":"ping","message":<epoch>}` / `{"type":"confirm_subscription","identifier":…}` / `{"type":"reject_subscription","identifier":…}` / `{"type":"disconnect","reason":…,"reconnect":…}` / `{"identifier":…,"message":{"result":{"data":{"aiCompletionResponse":…}},"more":true}}`。
- 送信: `{"command":"subscribe","identifier":"<JSON 文字列>"}` / `{"command":"unsubscribe","identifier":"<同じ>"}`。`identifier` は `{"channel":"GraphqlChannel","query":…,"variables":"<JSON 文字列>","operationName":"aiCompletionResponse"}`(K-S11)。
- 受信の `identifier` が自分の購読と一致しないメッセージは捨てる。

### 11.2 GraphQL

- 購読(REF `ai_completion_response_channel.ts:19-43` の `htmlResponse` 付き形。`additionalContext` 無し):
  ```graphql
  subscription aiCompletionResponse($userId: UserID, $clientSubscriptionId: String, $aiAction: AiAction, $htmlResponse: Boolean = true) {
    aiCompletionResponse(userId: $userId, aiAction: $aiAction, clientSubscriptionId: $clientSubscriptionId) {
      id requestId content contentHtml @include(if: $htmlResponse) errors role timestamp type chunkId extras { sources }
    }
  }
  ```
  変数: `{"htmlResponse":false,"userId":"gid://gitlab/User/<id>","aiAction":"CHAT","clientSubscriptionId":"<UUID>"}`(スパイクで確認済みの形)。
- `currentUser`: `query quickChatCurrentUser { currentUser { id } }`。

## 12. データモデル

- `StreamFrame`(パース結果): `requestId?`, `role?`, `content?`, `errors?`, `chunkId?`。Gson の DTO は既存方針どおり全項目 nullable。
- `ChunkAssembler` の状態: 確定 `requestId?`、`requestId` → (`chunkId` → 断片)、最終メッセージの表示テキスト、保持バイト数の合計。上限は §15.3。
- 待機中の欄: 途中経過テキスト(空 = 従来の「待機中」表示)。

## 13. トランザクション境界

サーバ側の状態を変える操作は従来どおり `aiAction` の 1 回だけ。購読・購読解除はサーバの状態を変えない(Redis の購読のみ)。ストリームの成否は `SendGate` に影響しない。

## 14. エラー処理

| 事象 | 扱い |
|---|---|
| `currentUser` 失敗 / null | ストリーム無し(ログ: 種類のみ) |
| ハンドシェイク失敗(401/403/Origin/プロキシ遮断/TLS) | ストリーム無し |
| `reject_subscription` | ストリーム無し |
| 確認待ちの時間切れ | ストリーム無し(ソケットは閉じる) |
| `disconnect` / 切断 / onError | ストリーム停止。途中経過はそのまま残し、ポーリングの結果で置き換わる |
| 不正な JSON / 想定外の形 | そのフレームを捨てる。連続で上限を超えたらストリーム停止 |
| 上限超過(§15.3) | ストリーム停止 |
| ストリームの最終メッセージに `errors` | 表示を変えない。エラーの表示はポーリングの結果で行う |
| ポーリングの失敗・期限切れ | 既存どおり(ストリームの途中経過は `Failure` で置き換わる) |

いずれも利用者への新しい通知は無い(FR-S4)。表示される結果の種類は QC 設計 §14 と同一。

## 15. タイムアウトとリトライ

### 15.1 期限

送信全体の期限(`ANSWER_DEADLINE` 120 s)は無変更で UI 側が守る。s1 の確認待ちは `min(SUBSCRIBE_WAIT, 残り)`。`SUBSCRIBE_WAIT` は数秒(値は実装段階、§26.1)。確認待ちが長引いても `aiAction` を送る前の時間なので、期限切れは従来どおり `TimedOut(beforeSend = true)`。

### 15.2 生存監視

ping の監視はしない(§6.3 の注: 未確認)。ストリームは送信の終わりで必ず閉じるので、無言で止まったストリームも最長で送信期限までしか残らない。

### 15.3 上限

| 項目 | 上限(値は実装段階で確定) |
|---|---|
| **保持データ全体 `MAX_BUFFERED_BYTES`**(Codex round 1 #2) | `requestId` 確定前のフレーム・欠番の後ろのチャンク・連結済みテキスト・最終メッセージの表示テキストの**合計**(UTF-8 換算、または文字数 × 2 の保守的な見積もり)。**追加する前に**超過を判定し、超過するならその断片を保持せずストリームを停止する。値は `MAX_ANSWER_BYTES` の数倍(例: 1 MiB)。 |
| 1 フレームの文字数 | `MAX_BUFFERED_BYTES` 以下。分割受信の連結中も同じ判定。超過でストリーム停止 |
| 保持するチャンク数(欠番の後ろを含む) | 例: 4096。超過でストリーム停止 |
| 表示テキスト | `MAX_ANSWER_BYTES`(既存、256 KiB) |
| `requestId` 確定前に保持するフレーム数 | 例: 256 |

ストリームが停止しても、それまでの途中経過の表示は残り、ポーリングの結果で置き換わる。

### 15.4 リトライ

しない(再接続もしない)。ストリームはベストエフォート、ポーリングが正。

## 16. 冪等性

`aiAction` は 1 回だけ送る(既存の `SendGate` が保証)。ストリームの成否で再送しない。結果を確定するのはポーリングだけなので、`deliver` は従来どおり 1 回で、`finishOnce` も 1 回だけ効く。

## 17. 並行処理

- WebSocket のリスナーは `HttpClient` のスレッドで呼ばれる。リスナーは受信データを `ActionCableClient` の内部(ロック 1 本、または単一スレッドへの受け渡し)に入れるだけで、UI もセッションも触らない。`request(1)` によるバックプレッシャを使い、処理中に次のフレームを受け取らない。
- 途中経過の UI 投函は `ResultSink` 経由のみ(QC 設計 §9.2.5 の「背景は session を捕捉しない」を維持)。投函されるクロージャは sink と値だけを捕捉する。
- 途中経過と結果の順序: `deliver` の後に届いた `progress` は、`finishOnce` で `clearInFlight` 済みのため `isCurrent` が false になり捨てられる。
- `GitLabHttpClient` が設定変更で作り直されると旧 `HttpClient` が `shutdown()` され、その上の WebSocket は切れる → ストリーム停止、ポーリングは新しいクライアントで続く。
- **Codex 重点確認**: リスナー内で例外を投げないこと / `abort` が背景スレッドを待たないこと / `finally` が cancel 時にも必ず通ること / 描画予約の取り消し漏れが無いこと。

## 18. 認証と認可

- トークンはハンドシェイクの `Authorization` ヘッダにのみ載せる(URL のクエリに載せない)。トークンは送信開始時の `ConnectionSnapshot` から取る(既存の HTTP と同じ)。
- `userId` は同じ `ConnectionSnapshot` の `currentUser` から取り、他のユーザーの ID を使う経路は無い(サーバも本人一致を検査。K-S6)。
- `Origin` は接続先インスタンスの origin(K-S4)。任意の値を設定する UI は無い。
- 受信内容は信頼しない入力として扱う(`htmlResponse:false` でテキストのみ。既存の回答と同じ描画経路で HTML として解釈しない)。

## 19. ログ、監視、監査

1 送信につき 1 行の要約を INFO で出す: ストリームの結果(`none` / `confirmed` / `rejected` / `handshake_failed:<例外クラス>` / `timeout` / `dropped:<理由>`)、受信チャンク数、系列の破棄回数、ストリームの最終メッセージを受けたか、`requestId`。完了経路は常にポーリング。本文・トークン・ユーザー ID・URL のクエリは出さない。既存の「Quick Chat send ended」ログに追記する形でもよい(実装段階)。

## 20. 障害時の復旧方法

ストリームの障害は利用者の操作を要さない(ポーリングが回答を届ける)。ストリームが常に失敗する環境(WebSocket を遮断するプロキシ等)では、送信ごとに最大 `SUBSCRIBE_WAIT` だけ `aiAction` の送出が遅れる。これが問題になる場合の対処はトグル(対象外)ではなく、`SUBSCRIBE_WAIT` の値の見直しで行う。

## 21. 既存機能への影響

- Quick Chat の送信: s1 の分だけ `aiAction` の送出が遅れる(確認が速ければ数百 ms、失敗時は最大 `SUBSCRIBE_WAIT`)。
- サーバ負荷: 送信ごとに WebSocket 1 本(送信の間だけ)。REF と同等。
- MR のポップアップ(#95)・サイドバー Duo Chat・LS: 無影響(`views/inlinethread` は変更しない。途中経過は既存の `InlineThreadEntry` で表す)。

## 22. 移行方法 / ロールバック方法

設定・保存データの追加は無い。ロールバックは PR-2 の revert(PR-1 は配線が無いので残しても無害)。

## 23. テスト方針

- PR-1(headless TDD、偽ソケット): `CableEndpoint`(https/http、サブパス、ポート)/ `ActionCableClient`(welcome → subscribe、confirm、reject、disconnect、分割フレーム、上限、identifier 不一致、リスナーが例外を投げない)/ `AiCompletionStream`(確認待ちの時間切れ、`close` の冪等、cancel 時の `abort`、ログに本文・トークンが出ない)/ `ChunkAssembler`(順不同、欠番、振り直し、空チャンク、**振り直しと順不同の複合(§9.2 の 2 例、新 2 が新 1 より先)**、最終メッセージでの置き換え、`errors` 付き最終で表示不変、`requestId` 確定前後、**`MAX_BUFFERED_BYTES` の境界: ちょうど上限は保持・1 超過で停止、欠番が埋まらないまま大きな断片が続く場合、`requestId` 確定前のフレームが合計で超える場合**)。
- PR-2: `QuickChatService`(ストリームの最終メッセージが先に届いても送信は終わらずポーリングの結果で確定する、ストリーム無し、ストリームが途中で死ぬ、ストリームの `errors` 付き最終でも結果はポーリングどおり、w4 で拒否されたらストリームを閉じる、`aiAction` が失敗したらストリームを閉じる)/ `ResultSink.progress`(洪水防止、`detach` 後)/ `QuickChatSession.onProgress`(`isCurrent`、間引き、`finishOnce` での予約取り消し)。
- 既存テストはすべて無変更で通ること。全体回帰は `verify.sh` → `FAILSET_IDENTICAL`、detektMain / detektTest はベースライン。

## 24. 受け入れ条件

| # | 条件 | 検証 |
|---|---|---|
| A-S1 | ストリーム無し(購読失敗)でも、回答・失敗・期限切れの表示が現行と同一 | 単体テスト(PR-2) |
| A-S2 | チャンク到着で途中経過が表示され、最終メッセージで置き換わる | 単体テスト + 手動(利用権のあるアカウント) |
| A-S3 | 送信の結果(回答・エラー・期限切れ)はポーリングの結果だけで決まり、ストリームの最終メッセージの有無・内容で変わらない | 単体テスト |
| A-S8 | ストリームの保持データが `MAX_BUFFERED_BYTES` を超えない(超える入力でストリームが停止し、送信はポーリングで完了する) | 単体テスト |
| A-S4 | 送信の終わり方すべて(回答・失敗・期限・置き換え・閉じる・バンドル停止)でソケットが閉じる | 単体テスト + 手動(ログ) |
| A-S5 | TLS / プロキシ設定が WebSocket に適用される | 単体テスト(`HttpClient` の出所)+ 手動(社内プロキシ環境があれば) |
| A-S6 | ログに本文・トークン・ユーザー ID が出ない | 単体テスト + 手動 |
| A-S7 | 途中経過の描画で UI が固まらない(長い回答) | 手動(利用権のあるアカウント) |

## 25. 手動検証手順(実装 PR の本文に転記)

- M-S1(現アカウントで可): 質問を送り、ログでストリームが `confirmed` になること、`M3006` などのエラー最終メッセージが S または P で届き、表示が従来と同じであることを確認。
- M-S2(現アカウントで可): WebSocket を遮断するプロキシ設定、または `/-/cable` に届かない状態で送信し、ストリームが `handshake_failed` / `timeout` になっても、ポーリング経路の表示が従来どおりであること(送出の遅れは最大 `SUBSCRIBE_WAIT`)。
- M-S3(利用権のあるアカウント): 長い回答で途中経過が逐次表示され、最終で置き換わり、コードブロックのボタンが最終で出ること。入力・閉じる・置き換えが固まらないこと。
- M-S4: 回答中に閉じる / 置き換える / Eclipse を終了する → ログでソケットが閉じたこと。
- M-S5: Error Log に本文・トークンが出ないこと。

## 26. 未決事項

### 26.1 実装段階で決める(設計では記録のみ。実装 PR の Codex 重点確認項目に引き継ぐ)

- `SUBSCRIBE_WAIT`(数秒)、`RENDER_INTERVAL`(100〜250 ms 程度)、§15.3 の各上限値。
- 途中経過の見出し・表示の文言。
- `Entry.Pending` の拡張の形(data object → 途中経過付き)と `resolvePending` の探索。
- 要約ログの書式と出し方(§19)。
- `ActionCableClient` 内部の同期方式(ロックか単一スレッドへの受け渡しか)。
- `User-Agent` の値(既存 HTTP と同じものを使う前提)。
- `jdk.http.auth.tunneling.disabledSchemes` によるプロキシ CONNECT の Basic 認証の挙動(既存 HTTP と同じ性質であることの確認)。
- `userId` を送信ごとに取るか、会話の結び付きにキャッシュするか(既定は送信ごと)。

### 26.2 実機でしか確かめられない

- 実際のチャンク到着の頻度と、`RENDER_INTERVAL` による描画負荷(利用権のあるアカウントが必要)。
- 企業プロキシ・独自 CA の環境での WebSocket。
- ping の有無(本設計は依存しない)。

## 27. 想定されるリスク

| リスク | 対策 |
|---|---|
| サーバの `aiCompletionResponse` 仕様変更(Experiment) | ストリームは補助。壊れてもポーリングで従来どおり |
| 確認待ちによる送出遅延 | `SUBSCRIBE_WAIT` を短く。確認が速い通常時は数百 ms |
| 逐次描画の負荷(`render` が全再構築、E-4) | 間引き + 途中経過はコード分割しない + 上限 |
| ストリームの内容と保存内容の不一致 | 結果はポーリング(保存内容)で確定し置き換わる(FR-S3)。不一致は途中経過の表示に限られる |
| 振り直し後に旧系列の断片が遅れて届き、途中経過に混ざる(§9.2) | 表示のみ。ポーリングの結果で置き換わる |
| ストリームの最終が先に届いてから完了までの表示差(最大 1 ポーリング間隔) | 表示は最終の内容になっているので利用者の不利益は小さい。送信中の状態(入力不可)が最大 3 s 長く見える |
| 実機未検証のまま入る | 「実装済み・実機未検証」として台帳・PR に明記。手動検証 M-S3 を利用権取得後に実施 |

## 28. 実装タスク分割案(モデルは CLAUDE.md の選定基準に従う)

| PR | 内容 | 主なモデル |
|---|---|---|
| PR-1 `feat/quick-chat-stream-transport` | §8.1 すべて(配線なし、既存挙動不変) | `opus`(並行処理・プロトコル)、`ChunkAssembler` / `CableEndpoint` は `sonnet` 可 |
| PR-2 `feat/quick-chat-streaming-ui` | §8.2 すべて + 手動検証手順(PR-1 マージ後に作成、スタックしない) | `opus`(UI スレッド・並走) |

独立レビューは各 PR の Draft で Codex のみ。

## 29. Codex レビュー反映履歴

### round 1(`eafa21a` に対する指摘 3 件、すべて P1)

| # | 指摘 | 仕分け | 反映 |
|---|---|---|---|
| 1 | ストリームの先着で結果を確定すると「ポーリングが正」と矛盾する | **採用(設計)**: 方式の根幹 | FR-S3 を「結果はポーリングだけで確定」に変更。`awaitFinal` と並走を削除(§7、§8.1、§8.2、§9.1、§9.3、§10、§14、§16、§19、§23、§24 A-S3、§27) |
| 2 | 保持データ全体のバイト上限が無い | **採用(設計)**: 資源の上限(IDE の停止) | §15.3 に `MAX_BUFFERED_BYTES`(追加前判定)、§23 に境界テスト、§24 A-S8 |
| 3 | 振り直しと順不同が重なると新系列が欠落する | **採用(設計)**: 規則の定義 | §9.2 に「同じ `chunkId` の再着 = 新系列」の規則と例、残る混入は表示のみで許容(§27)、§23 に複合ケース |
