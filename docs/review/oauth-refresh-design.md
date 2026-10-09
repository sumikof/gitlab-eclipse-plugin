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

本プラグインは期限の 120 秒前を期限扱いにする(`TOKEN_EXPIRATION_BUFFER_SECONDS = 120`)。これは変えない。

## 4. 要件

- R1: 更新の通信は接続・読み取りのタイムアウトを持つ。
- R2: 同時に期限切れを検出しても、ネットワークへの更新は 1 回。待っていた呼び出しはその結果を使う。
- R3: 失敗を「拒否」と「一時的」に分類し、扱いを分ける(§9)。
- R4: 一時的な失敗のあと、すぐに再試行を繰り返さない(待機時間を置く)。
- R5: 更新処理が失敗・例外で終わっても、次の更新を妨げない(ロックや状態を残さない)。
- R6: トークン・更新トークン・レスポンス本文をログ・通知・例外メッセージ・標準出力に出さない。
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
  - OAuth: キャッシュ済みトークン(未ロードならセキュアストレージから読む = 従来と同じ読み取り)があり、アクセストークンが空でなければ true。期限切れでも true(更新は送信側で行う)。
  - PAT: 従来の `getToken()` と同じ読み取りで、空でなければ true。
- `GitLabTokenProviderManager.hasToken()` は `getToken()` と同じ選択順で判定する。
- `SecurityScanLauncher.launch` のゲート引数を `tokenProviderManager.hasToken()` に置き換える。短絡条件(`enabled && !scanUri.isNullOrBlank() && ...`)とゲートの順序は変えない。
- 実際のトークン(必要なら更新)は、既存どおりバックグラウンドの `buildParams()` で取得する。

## 8. 処理フロー(更新の 1 回化)

```
getToken():
  token = currentToken(@Volatile)
  if token == null → loadCachedToken()(従来どおり)
  else if 期限内 → そのまま返す(ロックなし・高速経路)
  else → refreshIfExpired()
  return currentToken?.accessToken.orEmpty()

refreshIfExpired():
  effects = synchronized(refreshLock) {
    token = currentToken ?: return none
    if 期限内(待っている間に他が更新した) → return none
    if clock() < retryNotBefore(一時的失敗の待機中) → return none
    outcome = oAuthService().refreshToken(token.refreshToken)   ← ロック内でネットワーク(有限時間)
    when (outcome)
      Refreshed → currentToken = new; 保存; retryNotBefore = MIN; transientNotified = false
                  → effects = [設定送信]
      Rejected  → PAT へ切り替え → effects = [再認証の通知]
      Transient → retryNotBefore = clock() + RETRY_BACKOFF
                  if !transientNotified { transientNotified = true; effects = [一時失敗の通知] }
  }
  effects をロックの外で実行(通知・sendConfiguration)
```

- **待っていた呼び出しの扱い**: 実行中の更新が終わるまで `refreshLock` で待つ(最大でタイムアウトの合計)。終わったら期限を再判定し、更新済みならそのトークン、一時的失敗なら待機中なので更新せずに戻る。→ **1 回の失敗で N 回のタイムアウトを積み上げない。**
- **一時的失敗のときの戻り値**: 従来と同じく現在の(期限切れの)アクセストークンを返す。呼び出し先で 401 になりうるが、止まらない。
- **定期更新(`scheduleAtFixedRate`)**: 同じ `refreshIfExpired()` を通るので、呼び出し時の更新と自然に 1 回化される。
- **`updateToken`(認可フローの完了)**: 状態の書き込みを `refreshLock` の内側で行い、`sendConfiguration` は外側で呼ぶ。`retryNotBefore` と `transientNotified` もリセットする。

## 9. エラー処理(失敗の分類)

| 結果 | 条件 | 扱い |
|---|---|---|
| `Refreshed` | 200 で本文が解析できた | 従来どおり保存 + 設定送信 |
| `Rejected` | ScribeJava が `OAuth2AccessTokenErrorResponse`(OAuth のエラー応答)を投げた。代表は `invalid_grant`(更新トークンの失効・再利用) | **従来どおり**: 「再認証してください」通知 + PAT へ切り替え |
| `Transient` | 上記以外の例外すべて(`IOException` = 接続・読み取りのタイムアウトと通信断を含む / `InterruptedException` / `ExecutionException` / エラーコードを持たない 5xx などの解析失敗 / その他 `RuntimeException`) | **新規**: OAuth のまま維持。通知は失敗の連続中に 1 回だけ。`RETRY_BACKOFF` の間は再試行しない。次の成功で通知状態をリセット |

- `InterruptedException` は割り込みフラグを復元してから `Transient` にする。
- ログ: 分類と非機密のラベルだけ(`Rejected(invalid_grant)`、`Transient(SocketTimeoutException)` など)。例外のメッセージ・レスポンス本文・URL のクエリは出さない。

## 10. タイムアウトとリトライ、冪等性

- 接続タイムアウト・読み取りタイムアウトを設定する。**値は実装段階で決める**(目安: 接続 10 s / 読み取り 30 s)。
- `HttpURLConnection` の読み取りタイムアウトは「1 回の読み取りの待ち時間」であり、合計時間の上限ではない。少しずつ返す相手には合計が延びうる(既知の制限として記録)。
- 自動の即時リトライはしない。一時的失敗のあとは `RETRY_BACKOFF`(**値は実装段階で決める**。目安 30 s)以降の次の呼び出し、または定期更新で再試行する。
- 冪等性: 1 回化により、同じ更新トークンを 2 回送らない(C6 の `invalid_grant` を自分で起こさない)。

## 11. 並行処理

- `currentToken` は `@Volatile`。読み取りの高速経路はロックを取らない。
- 書き込み(更新・認可フロー完了・失敗時の状態)は `refreshLock`(JVM モニタ)の内側だけで行う。
- ネットワーク呼び出しは `refreshLock` の内側で行う(1 回化のため)。待ち時間はタイムアウトで上限が付く。
- `refreshLock` の内側から**呼ばないもの**: 通知(`NotificationUtils`)、`sendConfiguration`、UI スレッドへの同期ホップ。デッドロックの経路を作らないため。
- ロックの順序: 呼び出し元が `outboundLock`(コルーチンの `Mutex`)を持ったまま `refreshLock` を取ることはある(C9)。逆順(`refreshLock` を持ったまま `outboundLock` を待つ)は作らない。`sendConfiguration` はロックの外で別コルーチンを起動するだけ(C10)。
- 例外でロックが残らないよう、`synchronized` ブロック(または `try/finally`)で囲む。

## 12. 認証と認可 / ログ・監視・監査

- `.debug()` を外す(C3)。標準出力へのトークンの出力を止める。
- トークン・更新トークン・レスポンス本文は、ログ・通知・例外メッセージ・`toString` に出さない。既存の `GitLabAuthorizationToken.toString` のマスクは維持する。
- ログに出すのは、更新の開始・結果の分類・非機密のラベル・期限の時刻(従来どおり)。

## 13. 既存機能への影響

- 成功時の挙動は不変。
- 拒否(`invalid_grant` など)のときの挙動は不変。
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

## 16. テスト方針(headless で自動検証)

`GitLabOAuthService`(ローカルの `ServerSocket` / 簡易 HTTP 応答を更新先に差し替え、短いタイムアウトを注入)
- T1: 接続を受けて応答しない相手に、読み取りタイムアウト + 余裕の時間内に `Transient` が返る。
- T2: `400 {"error":"invalid_grant"}` → `Rejected("invalid_grant")`。
- T3: `500` + 非 JSON 本文 → `Transient`。
- T4: `200` + 正しい本文 → `Refreshed`(従来のトークン解析と同じ値)。
- T5: 更新中に `System.out` を捕捉し、更新トークン・アクセストークンの文字列が出ないこと。

`OAuthTokenProvider`(偽の `GitLabOAuthService`、注入した時計)
- T6: 期限切れの状態で 10 スレッドが同時に `getToken()` → 更新は 1 回、全員が新しいトークンを受け取る。
- T7: `Transient` → OAuth のまま(設定は OAUTH)、通知 1 回。待機時間内の再呼び出しでは更新しない。待機時間の経過後は再び更新する。
- T8: `Transient` が 2 回続いても通知は 1 回。成功後の次の失敗では再び通知する。
- T9: `Rejected` → 「再認証してください」通知 + PAT へ切り替え(従来どおり)。
- T10: 更新中に例外が出ても次の呼び出しが更新できる(ロック・状態が残らない)。
- T11: 実行中の更新を待っていた呼び出しは、一時的失敗のあと自分では更新しない(1 回の失敗でタイムアウトを積み上げない)。
- T12: 通知と `sendConfiguration` が `refreshLock` の外で呼ばれること(呼び出し時に別スレッドからロックを取得できる、などで検証)。

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
- A4: 拒否は従来どおり(T9)。
- A5: 失敗・例外のあとも次の更新ができる(T10)。
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
- U3: 期限の何秒前に更新するか(現行の 120 秒を維持する前提。変える場合は別件)。

## 20. 想定されるリスク

- ロック内でネットワーク呼び出しをするため、待つ呼び出しは最大でタイムアウトの合計だけ止まる。UI スレッドの呼び出し元が残っていれば、その間 UI が止まる(PR-2 で既知の 1 箇所を除去。他の UI スレッドの呼び出し元は既存のコメント上は無い)。
- 読み取りタイムアウトは合計時間の上限ではない(§10)。
- 認証まわりは公式プラグイン由来のコードで、上流との差分が増える。

## 21. 実装レビューの重点確認項目(Fable 5.1)

- 1 回化のロックが、失敗・タイムアウト・例外のあとに確実に解放されるか。
- `outboundLock` → `refreshLock` の順序だけで、逆順が無いか。通知・`sendConfiguration` がロックの外か。
- 定期更新と呼び出し時の更新、認可フロー完了(`updateToken`)の競合。
- 失敗の分類が §9 の表どおりか(とくに `OAuth2AccessTokenErrorResponse` 以外を `Rejected` にしていないか)。
- スキャン起動のゲートの順序と短絡が保たれ、未オプトインのユーザーにセキュアストレージの読み取りを起こさないか。
- 秘密情報が標準出力・ログ・通知・`toString` に出ないか。

## 22. 反映履歴

(レビュー後に追記)
