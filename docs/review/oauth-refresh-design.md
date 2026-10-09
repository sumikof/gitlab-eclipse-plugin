# OAuth トークン更新の堅牢化 設計書(レビュー専用・マージ禁止)

- 対象ブランチ: `gitlab-ls-9.3.0` @ `d04a604`
- 関連: issue #14(Phase 6 残余)/ #20(引き継ぎ)/ PR-0 #106(flaky テストの分離・マージ済み)
- 関連する実装 PR: なし(PR-1 / PR-2 は本設計の収束後に作成)
- レビュー体制: **今回に限り Codex は使わず Fable 5.1 でレビューする**(ユーザー指示)。

## 1. 背景と目的

OAuth のアクセストークン更新(`GitLabOAuthService.refreshToken`)は、通信にタイムアウトが無い。更新先が応答しないと呼び出し元が無期限に止まる。呼び出し元には UI スレッド(リモートセキュリティスキャンの起動)と、設定送信用の共有ロック(`outboundLock`)の内側が含まれるため、止まると IDE が固まる、または設定送信とスキャンが以後すべて待たされる。

目的は次の 4 点。

1. 更新が止まっても、呼び出し元が有限時間で戻ること。
2. 同時に更新が必要になっても、更新は 1 回にまとめること(GitLab の更新トークンは 1 回しか使えないため)。
3. 一時的な失敗(タイムアウト・通信断・5xx)でユーザーを無言でログアウトさせないこと。
4. UI スレッドからネットワーク更新を呼ばないこと。

## 2. 対象範囲 / 対象外

**対象**
- `authentication/GitLabOAuthService.kt`: タイムアウト、テスト用の差し替え口、結果の分類、`.debug()` の除去。
- `authentication/OAuthTokenProvider.kt`: 更新の 1 回化、状態の安全な公開、失敗の扱い、待機(backoff)。
- `security/SecurityScanLauncher.kt`(+ トークン提供側の非更新の確認 API): UI スレッドでのトークン取得の除去。

**対象外**
- 複数アカウント(D1。ユーザー判断で対象外)。
- OAuth の新規認可フロー(`startOAuthFlow` / コールバックサーバ)の挙動。
- 更新先が `https://gitlab.com/oauth/token` 固定である点(self-managed の OAuth 対応は別件)。
- `GitLabTokenProviderManager` の「選択中のプロバイダが空なら他を試す」流れ。
- OAuth 通信のプロキシ対応(JDK の `HttpURLConnection` の既定のまま。現状維持)。
- LS 側の更新(LS は独自に要求を送り、本プラグインの更新を起こさない)。

## 3. 現在の課題(実コード・実バイナリで確認済み)

| # | 事実 | 根拠 |
|---|---|---|
| C1 | `ServiceBuilder` に HTTP 設定を渡していない。ScribeJava 8.3.3 は設定が無いと `JDKHttpClient(JDKHttpClientConfig.defaultConfig())` を使い、`connectTimeout` / `readTimeout` が null のため `HttpURLConnection` に設定しない(= 無期限) | `GitLabOAuthService.kt:31-39`、`scribejava-core-8.3.3.jar` の `OAuthService.<init>` / `JDKHttpClient.doExecute` を `javap` で確認 |
| C2 | 修正に必要な API は存在する: `JDKHttpClientConfig.withConnectTimeout(Integer)` / `withReadTimeout(Integer)`、`ServiceBuilder.httpClientConfig(HttpClientConfig)` | 同 jar を `javap` |
| C3 | **`.debug()` が有効**。ScribeJava は debug 時に `request with body params [%s]`(更新トークンを含む)と `response body: %s`(新しいアクセストークン・更新トークンを含む)を debug ストリーム(既定 `System.out`)へ出力する。**資格情報の漏洩** | `GitLabOAuthService.kt:32`、`OAuth20Service` の `ldc` 文字列を `javap -c` で確認 |
| C4 | `refreshToken` は全例外を握りつぶして `null` を返す。呼び出し側は失敗の種類を区別できず、一律に「再認証してください」通知 + PAT へ切り替える | `GitLabOAuthService.kt:75-83`、`OAuthTokenProvider.kt:77-81` |
| C5 | `OAuthTokenProvider` は同時の呼び出しを 1 回にまとめない。`currentToken` に `@Volatile` も無い。期限切れのとき同時に呼ばれると呼び出しごとに更新が走る | `OAuthTokenProvider.kt:20-87` |
| C6 | GitLab(Doorkeeper 5.9.3、`use_refresh_token`)の更新トークンは 1 回しか使えない。再利用は `Errors::InvalidGrantReuse`(= `invalid_grant`) | gitlab `config/initializers/doorkeeper.rb:42`、`Gemfile.lock` の `doorkeeper (5.9.3)`、doorkeeper v5.9.3 `lib/doorkeeper/oauth/refresh_token_request.rb:33-49` |
| C7 | よって C5 の同時更新は、片方が `invalid_grant` で失敗 → C4 で PAT に切り替わる(= 無言のログアウト)を起こしうる | C4 + C6 |
| C8 | `SecurityScanLauncher.launch` は UI スレッド(コマンド・保存時リスナー)から呼ばれ、ゲート引数の評価で `tokenProviderManager.getToken()` を同期的に呼ぶ(有効かつ URI ありのとき) | `SecurityScanLauncher.kt:259`、`RunSecurityScanHandler.kt:25-27`、`SecurityScanSaveListener.kt:195` |
| C9 | `buildParams()` は `outboundLock.withLock` の内側で `getToken()` を呼ぶ。更新が止まるとロックを握ったままになる | `GitLabLanguageServerConfigurationService.kt:60-77,125`、`SecurityScanLauncher.kt:321-337` |
| C10 | 更新後の `updateToken` → `sendConfiguration` は別コルーチンでロックを取り直す(`coroutineScope.launch { outboundLock.withLock { ... } }`)。ロックの内側から呼ばれても再入・デッドロックにならない | `GitLabLanguageServerConfigurationService.kt:60-62` |
| C11 | 定期更新は単一スレッドの `scheduleAtFixedRate`。実行が止まると以後の定期実行も来ない | `OAuthTokenProvider.kt:23,104-109` |
| C12 | VSCode 版は同時の更新を 1 回にまとめる(`#refreshesInProgress`)。期限の 40 秒前に更新する | `out/gitlab-vscode-extension/src/desktop/gitlab/token_exchange_service.ts:8-12,31-60` |
| C13 | **非 200 応答は本文の形にかかわらず `OAuth2AccessTokenErrorResponse` になる。** 本文が JSON でない(HTML の 502 など)と `JsonProcessingException` を捕捉して `error = null` で投げる。`error` が未知の文字列でも `IllegalArgumentException` を捕捉して `error = null` で投げる | `OAuth2AccessTokenJsonExtractor.generateError`(`javap -c`: 18-29 の `new OAuth2AccessTokenErrorResponse` / 例外表 `5-13→16 JsonProcessingException`、`65-80→83 IllegalArgumentException`) |
| C14 | `OAuthResponseException` のメッセージはレスポンス本文そのもの。`OAuthException("Response body is incorrect. Can't extract …: '<body>'")` も本文を含む。Throwable をロガーに渡すと本文が出る | 同 jar を `javap`(Fable 第 1 巡 F5) |
| C15 | `loadCachedToken()` は `currentToken != null` の検査と代入が非アトミック。起動時は定期更新(初期遅延 0、`GitLabEclipseStartup.kt:99`)と LS 起動の `buildParams → getToken` がほぼ同時に入るため、片方が更新後のトークンを、もう片方が先に読んだ古いトークンで上書きしうる → 古い更新トークンで再更新 → `invalid_grant` | `OAuthTokenProvider.kt:47-67` |
| C16 | 拒否のあとも `currentToken` が残る。`GitLabTokenProviderManager` のフォールバック(`:22-27`)と定期更新が、死んだ更新トークンで毎回ネットワーク更新 → 毎回「再認証してください」通知 | `OAuthTokenProvider.kt:77-81`、`GitLabTokenProviderManager.kt:22-27` |
| C17 | `scheduleAtFixedRate` のタスクから例外が漏れると、以後の周期実行は止まる(`ScheduledExecutorService` の契約)。現行の定期タスクは例外を捕捉していない | `OAuthTokenProvider.kt:104-109` |

本プラグインは期限の 120 秒前を期限扱いにする(`TOKEN_EXPIRATION_BUFFER_SECONDS = 120`)。これは変えない。

## 4. 要件

- R1: 更新の通信は接続・読み取りのタイムアウトを持つ。
- R2: 同時に期限切れを検出しても、ネットワークへの更新は 1 回。待っていた呼び出しはその結果を使う。
- R3: 失敗を「拒否」と「一時的」に分類し、扱いを分ける(§9)。
- R4: 一時的な失敗のあと、すぐに再試行を繰り返さない(待機時間を置く)。
- R5: 更新処理が失敗・例外で終わっても、次の更新を妨げない(ロックや状態を残さない)。
- R6: トークン・更新トークン・レスポンス本文をログ・通知・例外メッセージ・標準出力に出さない。更新まわりの例外は **Throwable をロガーに渡さず、型名(と OAuth のエラーコード)だけ**を出す(C14)。
- R9: 拒否のあとは、死んだ更新トークンで更新を繰り返さない(C16)。
- R10: 定期更新は、1 回の実行が例外で終わっても次の周期を止めない(C17)。
- R7: UI スレッドでネットワーク更新を呼ばない(スキャン起動)。
- R8: 既存の成功時の挙動(保存・設定送信・期限の計算)を変えない。

## 5. 前提条件と制約

- ビルド構成・依存・ディレクトリ構成は変えない(ScribeJava は既存依存)。
- 単一アカウント前提。
- 実機の OAuth は gitlab.com のみ(更新先が固定のため)。
- headless の devcontainer ではネットワーク遮断時の UI 凍結そのものは観測できない。ローカルの `ServerSocket` を相手にした単体テストで代替する。

## 6. 構成とコンポーネントの責務

```
呼び出し元(各機能)
   │ getToken()
   ▼
GitLabTokenProviderManager ─── PatTokenProvider
   │
   ▼
OAuthTokenProvider  ……(1 回化・状態・失敗の扱い・待機)
   │ refreshToken(refreshToken)
   ▼
GitLabOAuthService  ……(HTTP 1 往復・タイムアウト・結果の分類)
   │ ScribeJava OAuth20Service(JDKHttpClient + タイムアウト設定)
   ▼
https://gitlab.com/oauth/token
```

- **`GitLabOAuthService`**: 1 回の更新要求を送り、結果を `RefreshOutcome` に分類して返す。状態を持たない。ログには例外のクラス名と OAuth のエラーコードだけを出す。
- **`OAuthTokenProvider`**: 現在のトークンの保持、期限の判定、更新の 1 回化、失敗の扱い(通知・PAT への切り替え・待機)。
- **`SecurityScanLauncher`**: UI スレッドではネットワーク更新をしない「トークンの有無」だけを見る。

## 7. インターフェース

### 7.1 `GitLabOAuthService`

```kotlin
sealed interface RefreshOutcome {
  data class Refreshed(val token: GitLabAuthorizationToken) : RefreshOutcome
  /** The server answered with an OAuth error (e.g. invalid_grant): the refresh token is unusable. */
  data class Rejected(val error: String) : RefreshOutcome
  /** No usable answer: timeout, I/O failure, non-OAuth error status, unparsable body. */
  data class Transient(val reason: String) : RefreshOutcome
}

class GitLabOAuthService(
  tokenEndpoint: String = TOKEN_ENDPOINT,                 // テスト用の差し替え口
  httpClientConfig: HttpClientConfig = defaultHttpConfig() // 接続・読み取りのタイムアウト
) {
  fun refreshToken(currentRefreshToken: String): RefreshOutcome
}
```

- `Rejected.error` / `Transient.reason` はログ用の**非機密**のラベル(OAuth のエラーコード、例外のクラス名)に限る。`toString` にトークンを含めない。
- 認可コードの交換(`createServer` 内の `getAccessToken`)も同じ `oauthService` を使うので、タイムアウトの恩恵を受ける。挙動は変えない。
- Koin の登録(`GitLabOAuthService()`)は既定値で従来どおり。

### 7.2 `OAuthTokenProvider`

```kotlin
class OAuthTokenProvider(
  ...既存引数,
  private val oAuthService: () -> GitLabOAuthService = { service() }, // 遅延解決(循環回避)
  private val clock: () -> Instant = Instant::now,                      // 待機の判定用
  private val notify: (String) -> Unit = NotificationUtils::show,
) : TokenProvider {
  override fun getToken(): String           // 従来どおり。必要なら更新(1 回化)
  fun hasToken(): Boolean                    // 新規。更新しない(§7.3)
}
```

### 7.3 トークンの有無の確認(PR-2)

- `TokenProvider.hasToken(): Boolean` を追加する。**ネットワーク更新をしない。** 
  - OAuth: キャッシュ済みトークン(未ロードならセキュアストレージから読む = 従来と同じ読み取り。§8 の初回ロードと同じ CAS 公開)があり、アクセストークンが空でなければ true。期限切れでも true(更新は送信側で行う)。**`refreshLock` を取らない**(実行中の更新を待たない)。
  - PAT: 従来の `getToken()` と同じ読み取りで、空でなければ true。
- `GitLabTokenProviderManager.hasToken()` は `getToken()` と同じ選択順で判定する。
- `SecurityScanLauncher.launch` のゲート引数を `tokenProviderManager.hasToken()` に置き換える。短絡条件(`enabled && !scanUri.isNullOrBlank() && ...`)とゲートの順序は変えない。
- 実際のトークン(必要なら更新)は、既存どおりバックグラウンドの `buildParams()` で取得する。

## 8. 処理フロー(更新の 1 回化)

```
currentToken: AtomicReference<GitLabAuthorizationToken?>

getToken():
  token = currentToken.get()
  if token == null → loadCached()                       // 下記。ロックなし
  token = currentToken.get() ?: return ""
  if 期限内 → return token.accessToken                   // ロックなし・高速経路
  refreshIfExpired()
  return currentToken.get()?.accessToken.orEmpty()

loadCached():                                            // 初回ロード(C15)
  if !isOAuthEnabled() → return
  loaded = secretStorage.getOAuthToken() ?: { PAT へ切り替え(従来どおり); return }
  currentToken.compareAndSet(null, loaded)              // 先に公開された値(更新後を含む)を決して上書きしない

refreshIfExpired():
  effects = synchronized(refreshLock) {
    token = currentToken.get() ?: return none          // 拒否後は null(R9)
    if 期限内(待っている間に他が更新した) → return none
    if clock() < retryNotBefore(一時的失敗の待機中) → return none
    if !isOAuthEnabled() → currentToken.set(null); return none   // 拒否直後の遅れた初回ロード・ユーザーの PAT 切替(第 2 巡 N1)
    outcome = oAuthService().refreshToken(token.refreshToken)   ← ロック内でネットワーク(有限時間)
    when (outcome)
      Refreshed → currentToken.set(new); 保存; retryNotBefore = MIN; transientNotified = false
                  → effects = [設定送信]
      Rejected  → currentToken.set(null); retryNotBefore = MIN; transientNotified = false
                  PAT へ切り替え → effects = [再認証の通知]
      Transient → retryNotBefore = clock() + RETRY_BACKOFF
                  if !transientNotified { transientNotified = true; effects = [一時失敗の通知] }
  }
  effects をロックの外で実行(通知・sendConfiguration)
```

- **初回ロード**: ロックを取らず、`compareAndSet(null, loaded)` だけで公開する。同時に複数が読んでも、最初の 1 つだけが公開され、すでに公開済み(更新後を含む)の値は上書きしない。セキュアストレージの読み取りは呼び出し元のスレッドで行う(従来どおり)。
- **待っていた呼び出しの扱い**: 実行中の更新が終わるまで `refreshLock` で待つ(最大でタイムアウトの合計)。終わったら期限を再判定し、更新済みならそのトークン、一時的失敗なら待機中なので更新せずに戻る。→ **1 回の失敗で N 回のタイムアウトを積み上げない。**
- **一時的失敗のときの戻り値**: 従来と同じく現在の(期限切れ扱いの)アクセストークンを返す。期限扱いは実際の期限の 120 秒前なので、その間はサーバ側ではまだ有効。それを過ぎると呼び出し先で 401 になりうるが、止まらない。
- **拒否のあと**: `currentToken` を null にし、認証方式は PAT へ切り替わる。以後 `getToken()` は OAuth が無効なのでロードもせず `""` を返し、更新を呼ばない。セキュアストレージの `oauth_token` は**削除しない**(ユーザー資産を消さない。再認証で上書きされる)。
- **定期更新(`scheduleAtFixedRate`)**: 同じ `refreshIfExpired()` を通るので、呼び出し時の更新と自然に 1 回化される。**定期タスクの本体は `Throwable` を捕捉し、型名だけをログに出す**(R10)。
- **`updateToken`(認可フローの完了)**: 状態の書き込みを `refreshLock` の内側で行い、`sendConfiguration` は外側で呼ぶ。`retryNotBefore` と `transientNotified` もリセットする。

## 9. エラー処理(失敗の分類)

| 結果 | 条件 | 扱い |
|---|---|---|
| `Refreshed` | 200 で本文が解析できた | 従来どおり保存 + 設定送信 |
| `Rejected` | **許可リスト方式**: `OAuth2AccessTokenErrorResponse` で、かつ HTTP ステータスが 4xx、かつ `getError()` が `INVALID_GRANT` / `INVALID_CLIENT` / `UNAUTHORIZED_CLIENT` のいずれか(更新トークンの失効・再利用、クライアントの失効) | 「再認証してください」通知 + PAT へ切り替え(従来の通知・切り替え)。**加えて `currentToken` を null にする**(§8、R9) |
| `Transient` | 上記以外すべて。`error == null` の `OAuth2AccessTokenErrorResponse`(本文が JSON でない 5xx・プロキシの HTML 応答など。C13)、許可リスト外のエラーコード、5xx、`IOException`(接続・読み取りのタイムアウトと通信断を含む)、`InterruptedException`、`ExecutionException`、その他の `RuntimeException`(本文の解析失敗を含む) | **新規**: OAuth のまま維持。通知は失敗の連続中に 1 回だけ。`RETRY_BACKOFF` の間は再試行しない。次の成功で通知状態をリセット |

PAT への切り替えは利用者から見るとログアウトに等しい破壊的な操作なので、「確実に更新トークンが使えない」と分かる場合だけに限る(許可リスト)。判断がつかないものは `Transient` に倒す。

- `InterruptedException` は割り込みフラグを復元してから `Transient` にする。
- ログ: 分類と非機密のラベルだけ(`Rejected(invalid_grant)`、`Transient(SocketTimeoutException)`、`Transient(http 502)` など)。例外のメッセージ・レスポンス本文・URL のクエリは出さない。**Throwable をロガーに渡さない**(`OAuthResponseException` のメッセージが本文そのものであるため。C14)。

## 10. タイムアウトとリトライ、冪等性

- 接続タイムアウト・読み取りタイムアウトを設定する。**値は実装段階で決める**(目安: 接続 10 s / 読み取り 30 s)。
- `HttpURLConnection` の読み取りタイムアウトは「1 回の読み取りの待ち時間」であり、合計時間の上限ではない。少しずつ返す相手には合計が延びうる(既知の制限として記録)。
- 自動の即時リトライはしない。一時的失敗のあとは `RETRY_BACKOFF`(**値は実装段階で決める**。目安 30 s)以降の次の呼び出し、または定期更新で再試行する。
- 冪等性: 1 回化により、同じ更新トークンを 2 回送らない(C6 の `invalid_grant` を自分で起こさない)。

## 11. 並行処理

- `currentToken` は `AtomicReference`。読み取りの高速経路はロックを取らない。
- 書き込みは 2 種類に分ける。
  - **初回ロード**: ロックを取らず `compareAndSet(null, loaded)` だけ(§8)。公開済みの値を上書きしない。
  - **更新・認可フロー完了・拒否による破棄**: `refreshLock`(JVM モニタ)の内側だけで行う。
- ネットワーク呼び出しは `refreshLock` の内側で行う(1 回化のため)。待ち時間はタイムアウトで上限が付く。
- **不変条件: `refreshLock` は UI スレッドで取らない。** UI スレッドから呼ばれうる経路(`hasToken()`、PR-2 後のスキャン起動)はロックを取らない。理由は 2 つ:
  1. 実行中の更新を待つと、タイムアウトの合計だけ UI が止まる。
  2. ロックの内側で行うセキュアストレージへの保存がマスターパスワードの入力を求め、それが UI スレッドへの同期ホップになる場合、UI スレッドが `refreshLock` を待っているとデッドロックする(実機依存。§19 U4)。
- `refreshLock` の内側から**呼ばないもの**: 通知(`NotificationUtils`)、`sendConfiguration`、UI スレッドへの同期ホップ。デッドロックの経路を作らないため。
- ロックの順序: 呼び出し元が `outboundLock`(コルーチンの `Mutex`)を持ったまま `refreshLock` を取ることはある(C9)。逆順(`refreshLock` を持ったまま `outboundLock` を待つ)は作らない。`sendConfiguration` はロックの外で別コルーチンを起動するだけ(C10)。`outboundLock` の待ち手は `Dispatchers.IO` 上なので、ロック内のブロッキングでスレッドは枯渇しない。
- 例外でロックが残らないよう、`synchronized` ブロック(または `try/finally`)で囲む。

## 12. 認証と認可 / ログ・監視・監査

- `.debug()` を外す(C3)。標準出力へのトークンの出力を止める。
- トークン・更新トークン・レスポンス本文は、ログ・通知・例外メッセージ・`toString` に出さない。既存の `GitLabAuthorizationToken.toString` のマスクは維持する。
- 更新まわりの例外は Throwable をロガーに渡さず、型名・HTTP ステータス・OAuth のエラーコードだけを出す(C14)。`RefreshOutcome` の `toString` にも本文を含めない。
- ログに出すのは、更新の開始・結果の分類・非機密のラベル・期限の時刻(従来どおり)。

## 13. 既存機能への影響

- 成功時の挙動は不変。
- 拒否(`invalid_grant` など許可リストのエラー)のときの通知と PAT への切り替えは従来どおり。**加えて、保持していたトークンを破棄し、死んだ更新トークンで更新を繰り返さない**(セキュアストレージは消さない)。
- **一時的な失敗のときの挙動が変わる**: 従来は PAT へ切り替え(実質ログアウト)だったが、OAuth を維持して再試行する。
- 標準出力へのトークン出力がなくなる(デバッグ出力に頼っていた人はいない想定)。
- スキャン起動: ゲートの判定結果は同じ。期限切れのトークンでも「トークンあり」と判定し、送信側で更新する。

## 14. 移行・ロールバック・障害時の復旧

- データ形式(セキュアストレージの JSON)は変えないので移行は不要。
- ロールバックは PR の revert のみ。
- 一時的失敗が続く場合: OAuth のまま、待機時間ごとに再試行される。ユーザーは設定画面から再認証もできる(従来どおり)。

## 15. PR 分割

| PR | ブランチ | 内容 |
|---|---|---|
| PR-1 | `fix/oauth-refresh-timeout` | §7.1・§7.2・§8〜§12(タイムアウト、`.debug()` の除去、結果の分類、1 回化、失敗の扱い、待機) |
| PR-2 | `fix/security-scan-token-off-ui` | §7.3(`hasToken` の追加とスキャン起動のゲートの置き換え) |

PR-2 の後、PR-1 + PR-2 の変更全体を Fable 5.1 でレビューする。

**マージの順序**: PR-1 だけが入った状態では、UI スレッドのスキャン起動(C8)が `refreshLock` を待ちうる(§11 の不変条件に反する)。そのため **PR-2 は PR-1 のブランチから派生させ(PR-1 の `OAuthTokenProvider` の初回ロードを `hasToken()` が使うため)、全体レビューの収束後に PR-1 → PR-2 の順で続けてマージする**。PR-1 のマージ後、PR-2 の base を `gitlab-ls-9.3.0` へ付け替えてからマージする(PR-1 のブランチ削除は PR-2 の base 付け替えの後。#20 §4 のマージ順序の罠)。

## 16. テスト方針(headless で自動検証)

`GitLabOAuthService`(ローカルの `ServerSocket` / 簡易 HTTP 応答を更新先に差し替え、短いタイムアウトを注入)
- T1: 接続を受けて応答しない相手に、読み取りタイムアウト + 余裕の時間内に `Transient` が返る。
- T2: `400 {"error":"invalid_grant"}` → `Rejected("invalid_grant")`。
- T3: `502` + HTML 本文(`error == null` の `OAuth2AccessTokenErrorResponse` になる。C13)→ `Transient`。`400 {"error":"invalid_request"}`(許可リスト外)→ `Transient`。`500 {"error":"invalid_grant"}`(5xx)→ `Transient`。空本文の 5xx → `Transient`。
- T4: `200` + 正しい本文 → `Refreshed`(従来のトークン解析と同じ値)。
- T5: 更新中に `System.out` を捕捉し、更新トークン・アクセストークンの文字列が出ないこと。 プラットフォームログ(`ILog`)に渡る内容にも、トークン・レスポンス本文が出ないこと(Throwable が渡されないこと)。

`OAuthTokenProvider`(偽の `GitLabOAuthService`、注入した時計)
- T6: 期限切れの状態で 10 スレッドが同時に `getToken()` → 更新は 1 回、全員が新しいトークンを受け取る。
- T6b: `currentToken == null`(未ロード)の状態から 10 スレッドが同時に `getToken()`(セキュアストレージには期限切れのトークン)→ 更新は 1 回、更新後のトークンが古いトークンで上書きされない(C15)。
- T7: `Transient` → OAuth のまま(設定は OAUTH)、通知 1 回。待機時間内の再呼び出しでは更新しない。待機時間の経過後は再び更新する。
- T8: `Transient` が 2 回続いても通知は 1 回。成功後の次の失敗では再び通知する。
- T9: `Rejected` → 「再認証してください」通知 + PAT へ切り替え。以後の `getToken()` は `""` を返し、更新を呼ばず、通知も重ねない。定期更新も更新を呼ばない(C16)。セキュアストレージの `oauth_token` は削除されない。
- T10: 更新中に例外が出ても次の呼び出しが更新できる(ロック・状態が残らない)。
- T11: 実行中の更新を待っていた呼び出しは、一時的失敗のあと自分では更新しない(1 回の失敗でタイムアウトを積み上げない)。
- T12: 通知と `sendConfiguration` が `refreshLock` の外で呼ばれること(呼び出し時に別スレッドからロックを取得できる、などで検証)。
- T12b: 定期タスクの 1 回の実行が例外で終わっても、次の周期が実行される(C17)。
- T12c: `hasToken()` は、別スレッドが `refreshLock` を保持している(更新中)ときも待たずに戻る。

`SecurityScanLauncher`(PR-2)
- T13: `launch` は `getToken()` を呼ばず `hasToken()` だけを呼ぶ。
- T14: 無効のとき・URI が無いときは `hasToken()` も呼ばない(短絡の維持)。
- T15: ゲートの結果(DISABLED / NO_EDITOR / NO_TOKEN / SENT)と通知が従来と同じ。
- T16: `hasToken()` は期限切れの OAuth トークンでも true、更新を起こさない。

共通: 全体回帰 `FAILSET_IDENTICAL`、`detektMain` / `detektTest` がベースライン(17 / 45)、変異を注入したときに新規テストが落ちること。

## 17. 受け入れ条件

- A1: 応答しない更新先で、`refreshToken` が設定したタイムアウト + 余裕の時間内に戻る(T1)。
- A2: 同時 10 呼び出しで更新は 1 回(T6)。
- A3: 一時的失敗で PAT に切り替わらず、通知は連続中 1 回、待機時間内は再試行しない(T7・T8・T11)。
- A4: 拒否は許可リストのエラーだけ。拒否のあとは死んだ更新トークンで更新を繰り返さない(T2・T3・T9)。
- A5: 失敗・例外のあとも次の更新ができ、定期更新も止まらない(T10・T12b)。
- A9: 初回ロードの競合で、更新後のトークンが古いもので上書きされない(T6b)。
- A10: UI スレッドから呼ばれうる経路は `refreshLock` を取らない(T12c・T13)。
- A6: トークンが標準出力・ログ・通知に出ない(T5 + 既存の監査)。
- A7: スキャン起動が UI スレッドでネットワーク更新を呼ばない(T13〜T16)。
- A8: 全体回帰・detekt がベースラインどおり。

## 18. 手動検証(実機・PR 本文に記載)

- M1: OAuth でサインインし、期限切れ後(または期限を短くした状態で)に Duo Chat・サイドバーなどが自動更新で使い続けられる。
- M2: 更新のタイミングでネットワークを遮断し、UI が固まらず、一時失敗の通知が 1 回だけ出て、OAuth のままである。復帰後に自動で回復する。
- M3: 保存時スキャンを有効にした状態で、期限切れのトークンでも保存で UI が止まらない。
- M4: Eclipse を端末から起動し、更新時に標準出力へトークンが出ない。
- M5: 更新トークンを失効させた状態(GitLab 側でアプリの認可を取り消す)で、従来どおり再認証の通知が出て PAT に切り替わる。

## 19. 未決事項

- U1: タイムアウトと待機時間の値(実装段階)。
- U2: 一時失敗の通知文言(実装段階)。
- U3: 期限の何秒前に更新するか(現行の 120 秒を維持する前提。変える場合は別件)。 定期更新の周期は `startTokenRefreshTimer` の呼び出し時刻基準の `expiresIn` で、バッファ付き期限より後に来る。一時的失敗からの回復は、次の `getToken()` 呼び出し(または次の周期)が前提。`startTokenRefreshTimer` は呼ぶたびに周期タスクを追加し、`shutdownNow()` 後は `RejectedExecutionException` になる(既存。対象外として申し送り)。
- U4: Equinox のセキュアストレージがマスターパスワードの入力を UI スレッドへの同期ホップで求めるか(実機依存)。本設計は §11 の不変条件(UI スレッドで `refreshLock` を取らない)により、どちらでもデッドロックしない。

## 20. 想定されるリスク

- ロック内でネットワーク呼び出しをするため、待つ呼び出しは最大でタイムアウトの合計だけ止まる。UI スレッドの呼び出し元が残っていれば、その間 UI が止まる(PR-2 で既知の 1 箇所を除去。他の UI スレッドの呼び出し元は既存のコメント上は無い)。
- 読み取りタイムアウトは合計時間の上限ではない(§10)。
- 認証まわりは公式プラグイン由来のコードで、上流との差分が増える。

## 21. 実装レビューの重点確認項目(Fable 5.1)

- 1 回化のロックが、失敗・タイムアウト・例外のあとに確実に解放されるか。
- `outboundLock` → `refreshLock` の順序だけで、逆順が無いか。通知・`sendConfiguration` がロックの外か。
- `refreshLock` を UI スレッドで取る経路が無いか(`hasToken()`、スキャン起動)。
- 初回ロードが `compareAndSet(null, …)` だけで公開され、公開済みの値を上書きしないか。
- 定期更新と呼び出し時の更新、認可フロー完了(`updateToken`)の競合。定期タスクが例外で止まらないか。
- 失敗の分類が §9 の許可リストどおりか(`error == null`・5xx・許可リスト外を `Rejected` にしていないか)。拒否のあと `currentToken` が null になるか。
- スキャン起動のゲートの順序と短絡が保たれ、未オプトインのユーザーにセキュアストレージの読み取りを起こさないか。
- 秘密情報が標準出力・ログ・通知・`toString` に出ないか。Throwable をロガーに渡していないか。
- 拒否の直後に遅れて完了した初回ロードが無効なトークンを再公開しても、ロック内の `isOAuthEnabled()` 再確認で更新・通知が重ならないか(第 2 巡 N1。決定的なテストで再現する)。

## 22. 反映履歴

### 第 1 巡(Fable 5.1、`d21244e`)

| # | 指摘 | 仕分け | 反映 |
|---|---|---|---|
| F1 | P1: `OAuth2AccessTokenErrorResponse` ⇔ `Rejected` は誤り。非 JSON の 5xx(HTML)も `error = null` で同じ例外になる | 採用(設計で解決) | C13 追加、§9 を許可リスト方式へ、T3 を拡張 |
| F2 | P1: 初回ロードの競合で古いトークンが更新後を上書き → `invalid_grant`。`hasToken()` がロックを取ると UI 凍結・デッドロックの恐れ | 採用(設計で解決) | C15 追加、`AtomicReference` + CAS(§8・§11)、`hasToken()` はロックなし(§7.3)、§11 に不変条件、T6b・T12c・A9・A10 |
| F3 | P2: 拒否のあとも `currentToken` が残り、死んだ更新トークンで更新・通知を繰り返す | 採用(設計で解決) | C16・R9、拒否時に `currentToken = null`(ストレージは削除しない)、T9・A4 |
| F4 | P2: 定期タスクの例外で周期実行が止まる。PR-1 だけ入った期間は UI スレッドが `refreshLock` を待ちうる | 採用(設計で解決) | C17・R10、定期タスクで `Throwable` を捕捉、T12b・A5。§15 にマージ順序(PR-2 は PR-1 から派生、全体レビュー後に続けてマージ) |
| F5 | P2: `OAuthResponseException` のメッセージは本文そのもの。Throwable をロガーに渡すと本文が出る | 採用(設計で解決) | C14・R6・§9・§12、T5 にプラットフォームログを追加 |
| F6 | P3: 周期と回復の前提、`startTokenRefreshTimer` の重複登録・`shutdownNow` 後の例外 | 記録(別件) | §19 U3 に記録、対象外として申し送り |
| F7 | P3: 一時的失敗で期限切れトークンを返す根拠、既存 `GitLabOAuthServiceTest` のリフレクション差し替えの書き換え、`Rejected.error` の型、detekt `SwallowedException` | 実装段階へ | 根拠は §8 に 1 行追記。残りは実装レビューの確認項目 |

### 第 2 巡(Fable 5.1、`d3ead18`)— **収束**

- F1〜F5 はすべて解消を確認(`OAuth2Error` の定数名・`OAuthResponseException.getResponse()` を javap で確認、CAS 初回ロード × 更新中 / × `hasToken()` / × 拒否の組み合わせを検証)。

| # | 指摘 | 仕分け | 反映 |
|---|---|---|---|
| N1 | P3: 拒否の直後に遅れて完了した初回ロードが、無効なトークンを CAS で再公開する窓(1 往復の無駄と通知の重複のみ) | 実装段階へ | §8 にロック内の `isOAuthEnabled()` 再確認を 1 行、§21 に確認項目を追加 |
| N2 | P3: 同時の初回ロードでセキュアストレージの読み取りとログが重複する | 実装段階へ | 実装時に `loadCached()` 直前で再読する |
| N3 | P3: PR-2 マージ時の手順(`--delete-branch` 不使用、base 付け替えの後に PR-1 ブランチ削除)、既存テストの整理 | 実装段階へ | §15 の順序どおり。実装計画に記載 |

収束基準(CLAUDE.md)により、再依頼せずに本 PR をマージせず Close する。
