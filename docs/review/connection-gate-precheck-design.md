# 接続ゲートの副作用先行抑止(URL 先行照合)設計書

対象 issue: #49
関連: PR #48 の Codex レビュー https://github.com/sumikof/gitlab-eclipse-plugin/pull/48#discussion_r3703790506
対象ブランチ(設計時点): `develop` @ `2f1ec61`

> 本書はレビュー専用。実装 PR およびマージ先ブランチには含めない。

---

## 1. 背景と目的

`ci/actions/WriteAction.kt` の `pinnedConnectionFor` は、サイドバーのノードが読み込まれた接続が現在も有効かを判定する共有ゲートである。現在の実装は、URL と `authFingerprint` を照合する **前** に `GitLabApiClient.captureConnection()` を呼ぶ。

```
pinnedConnectionFor
  └─ captureConnection()                       ← 照合より前
       └─ tokenManager.getToken()
            └─ OAuthTokenProvider.getToken()
                 └─ refreshTokenIfExpired()    ← トークンが期限切れなら
                      ├─ GitLabOAuthService.refreshToken()  = HTTP
                      ├─ updateToken() → 設定書き込み + secure storage + LS 設定通知
                      └─ 失敗時 NotificationUtils.show(...)  = ユーザー可視の通知
```

このため、古い URL を持つノードから操作した場合でも、**ゲート不成立と判定されるより前に** HTTP・永続化・ユーザー通知が発生しうる。Phase 4 が置いた「ゲート不成立時は観測可能な副作用ゼロ」という設計上の主張は、文字通りには成立していない。

本設計の目的は、**主要シナリオであるインスタンス不一致について、副作用を発生させる前にゲートを不成立にする**ことである。

## 2. 対象範囲

- `ci/actions/WriteAction.kt` の `pinnedConnectionFor` に、副作用のない URL 先行照合を追加する。
- `api/GitLabApiClient.kt` に、副作用のない設定 URL 読み取りメソッドを 1 つ追加する。
- 上記に対応するテストの追加と、**既存テストの計測点の見直し**(§20)。

### 2.1 影響を受ける呼び出し元(全 4 箇所)

**issue #49 の本文は影響範囲を「CI の全書き込み経路」と記述しているが、これは過小である。** 実際の `pinnedConnectionFor` の呼び出し元は 4 箇所あり、**うち 2 箇所は書き込みではなく読み取り**である。本設計はこの 4 箇所すべてをスコープに含む。

| 呼び出し元 | 種別 | 機能 |
|---|---|---|
| `ci/actions/PipelineActionHandler.kt:129` | write | CI(pipeline/job の retry/cancel/play) |
| `mergerequests/discussions/DiscussionWriteFlow.kt:56` | write | MR ディスカッションへの投稿 |
| `mergerequests/discussions/DiscussionsLoader.kt:114` | **read** | MR ディスカッションのロード |
| `ci/actions/DisplayJobLogHandler.kt:110` | **read** | ジョブトレースの表示 |

## 3. 対象外

- **アカウント(`authFingerprint`)側の先行照合。** `authFingerprint` はトークンから導出される(`api/GitLabApiClient.kt:36-39`、sha256 → 先頭 N 桁の hex)ため、トークンを取得せずにアカウント一致は判定できない。`TokenProvider`(`authentication/TokenProvider.kt`、3 行)には非更新読み取り API が無く、追加は認証周りの共有インターフェース変更になる。**したがって「URL は一致するがアカウントが変わった」ケースでは、従来どおり照合前に副作用が発生しうる。** 本設計はこれを解決しない(§21 の将来の拡張を参照)。
- `captureConnection()` の他の呼び出し元 6 箇所(`ci/actions/CiLintLaunch.kt:58`、`ci/actions/CreatePipelineHandler.kt:99`、`api/GitLabApiClient.kt:272`、`views/sidebar/GitLabSidebarView.kt:348` および `:527`、`ci/actions/WriteAction.kt:66`)。変更を `pinnedConnectionFor` に閉じる。
- `OAuthTokenProvider` のリフレッシュ挙動そのもの。
- `GitLabTokenProviderManager.getToken()` のフォールバック探索の見直し。

## 4. 現在の課題

`ci/actions/WriteAction.kt:60-73`(現行)。

```kotlin
fun pinnedConnectionFor(
  apiClient: GitLabApiClient,
  nodeInstanceUrl: String,
  nodeAuthFingerprint: String,
): ConnectionSnapshot? {
  val snapshot = try {
    apiClient.captureConnection()          // ← 照合の前。ここで副作用が起きうる
  } catch (ignored: UnstableConnectionException) {
    return null
  }
  val sameInstance = normalizeInstanceUrl(snapshot.instanceUrl) == normalizeInstanceUrl(nodeInstanceUrl)
  val sameAccount = snapshot.authFingerprint == nodeAuthFingerprint
  return if (sameInstance && sameAccount) snapshot else null
}
```

## 5. 要件

| ID | 要件 |
|---|---|
| R-1 | ノードの URL が現在の設定 URL と一致しない場合、`captureConnection()` を呼ばずに `null` を返すこと |
| R-2 | R-1 の判定が観測可能な副作用(HTTP・永続化・secure storage 書き込み・LS 設定通知・ユーザー通知)を一切起こさないこと |
| R-3 | ゲートの**成立条件**を現状より緩めないこと(誤って通す方向の変化を作らない) |
| R-4 | ゲート不成立時の戻り値・監査ログ・ユーザー通知が、呼び出し元から見て現状と同一であること |
| R-5 | URL の正規化が先行照合と既存の照合とで一致すること |

## 6. 前提条件と制約

- ディレクトリ構成の変更禁止。ビルドシステム(`build.gradle.kts` / `detekt.yml`)の変更禁止。新規 OSGi バンドル依存の追加なし。
- ログに URI・ユーザーのファイルパス・トークンを出さない。
- 実装は `develop` から分岐した実装ブランチで行い、**実コードのみをコミット**する(本設計書はマージしない)。
- headless devcontainer のため、実 SWT / LSP 実接続を要する検証はできない。

## 7. システム構成

新しいコンポーネントは導入しない。既存の 2 ファイルに閉じる。

```
呼び出し元 4 箇所
   │
   ▼
WriteAction.pinnedConnectionFor          ← 先行照合を先頭に追加(本設計の中核)
   │  ①  apiClient.peekInstanceUrl()      ← 追加。副作用なし
   │  ②  normalizeInstanceUrl 同士で比較 → 不一致なら return null(捕捉せず終了)
   │  ③  apiClient.captureConnection()    ← 一致した場合のみ。既存のまま
   │  ④  snapshot に対する既存の照合       ← 権威。既存のまま
   ▼
GitLabApiClient
   ├─ peekInstanceUrl()      ← 追加。preferenceStore の読みのみ
   └─ captureConnection()    ← 既存。seqlock で (url, token) を原子的に取得
```

## 8. コンポーネントの責務

### 8.1 `GitLabApiClient.peekInstanceUrl()`(追加)

- **責務**: 現在設定されているインスタンス URL を、**副作用なく**返す。
- **実装**: `preferenceStore.getString(PreferenceConstants.GITLAB_INSTANCE_URL)` のみ。トークンマネージャに触れない。
- **なぜ `GitLabApiClient` に置くか**: `captureConnection()` と**同じ `preferenceStore` インスタンス**を読むことを型で担保するため。先行照合と本照合が別のソースを見る事故を、構造的に起こせなくする。
- **一貫性について**: seqlock(`configGeneration`)の外で読む。これは意図的であり、その正当性は §9.2 に示す。

### 8.2 `WriteAction.pinnedConnectionFor`(変更)

- **責務**: 変更なし。ノードの接続が現在も有効かを判定し、有効なら固定用スナップショットを返す。
- **変更点**: 冒頭に URL 先行照合を追加。不一致なら `captureConnection()` を呼ばずに `null`。

## 9. 処理フロー

### 9.1 変更後のフロー

```kotlin
fun pinnedConnectionFor(
  apiClient: GitLabApiClient,
  nodeInstanceUrl: String,
  nodeAuthFingerprint: String,
): ConnectionSnapshot? {
  // 副作用のない先行照合。不一致ならトークンに触れずに終了する(#49)。
  if (normalizeInstanceUrl(apiClient.peekInstanceUrl()) != normalizeInstanceUrl(nodeInstanceUrl)) {
    return null
  }

  val snapshot = try {
    apiClient.captureConnection()
  } catch (ignored: UnstableConnectionException) {
    return null
  }
  val sameInstance = normalizeInstanceUrl(snapshot.instanceUrl) == normalizeInstanceUrl(nodeInstanceUrl)
  val sameAccount = snapshot.authFingerprint == nodeAuthFingerprint
  return if (sameInstance && sameAccount) snapshot else null
}
```

`normalizeInstanceUrl` は既存(`ci/actions/WriteAction.kt:29`、`url.trimEnd('/')`)をそのまま使う。これにより R-5 を満たす。

### 9.2 設計の中核となる性質: 先行照合は「誤って通す」ことができない

`captureConnection()`(`api/GitLabApiClient.kt:178-190`)は seqlock である。世代カウンタ(`api/ConnectionConfigGeneration.kt`、`AtomicLong`。`beginUpdate` で奇数、`endUpdate` で偶数)を読み、奇数(更新中)なら再試行し、URL とトークンを読み、再度カウンタを読んで一致したときだけスナップショットを返す。これが (url, token) の原子性を作っている。

先行照合はこの seqlock の外で URL のみを読む。したがって**単体ではレースする**。しかし:

**`captureConnection()` 取得後の既存の照合(`sameInstance && sameAccount`)は一切変更せず、権威のまま残る。** よって先行照合は結果を `null` 側へ倒すことしかできず、**本来不成立であるべきゲートを成立させることは原理的にできない**(R-3)。

これは「先行照合は新しい正当性ゲートではなく、不成立が濃厚なケースで副作用を回避する最適化である」と言い換えられる。

### 9.3 受容する挙動差(唯一の非互換)

上記の性質は「決定関数が完全に不変」を意味しない。正確には次のとおりである。

先行照合の時刻を t1、`captureConnection` の時刻を t2(t1 < t2)、時刻 t における設定 URL を U(t) とすると:

- 変更前の拒否条件: `U(t2) ≠ node`
- 変更後の拒否条件: `U(t1) ≠ node ∨ U(t2) ≠ node`

差分は **`U(t1) ≠ node` かつ `U(t2) = node`** の場合のみ、すなわち t1〜t2 の間に設定 URL が「不一致 → 一致」へ戻るフラップが起きた場合である。この場合、変更前は成立していたゲートが変更後は不成立になる。

- 発生には、極めて短い時間内に設定変更が 2 回起きる必要がある。
- 結果は安全側(書き込みを行わない / 読み取りを行わない)に倒れる。
- ユーザーから見た挙動は「接続が変わったため操作を中止した」旨の既存の通知であり、新しい失敗モードではない。

**本設計はこれを受容済みの挙動差とする。** 「完全に不変」と主張しないことを明記する。

## 10. API / インターフェース

### 追加

```kotlin
// api/GitLabApiClient.kt
/**
 * 現在設定されているインスタンス URL を副作用なく返す。
 * [captureConnection] と異なりトークンマネージャに触れないため、OAuth リフレッシュ
 * (HTTP・永続化・ユーザー通知)を誘発しない。接続ゲートの先行照合専用(#49)。
 * seqlock の外で読むため単体ではレースするが、照合の権威は捕捉後の比較側にある。
 */
fun peekInstanceUrl(): String
```

`GitLabApiClient` には既に `@Suppress("TooManyFunctions")` が付いている(`api/GitLabApiClient.kt:54`)。本追加でさらに 1 つ増える。**未確定事項**: クラス分割の是非は issue #44 で既に「GraphQL 追加時などに検討」として defer されており、本設計では判断しない。

### 変更

`pinnedConnectionFor` のシグネチャは**変更しない**。呼び出し元 4 箇所は無変更。

## 11. データモデル

変更なし。`ConnectionSnapshot`(`api/ConnectionSnapshot.kt`、`instanceUrl` / `token` / `authFingerprint` / `configGeneration`)は不変。新しい `data class` を追加しないため、`SecretRedactionConventionTest` の期待マップに追加すべきクラスは発生しない。

## 12. トランザクション境界

変更なし。本変更は書き込みを開始する **前** の判定にのみ作用する。ゲート成立後の書き込みは従来どおり `ConnectionSnapshot` に固定される。

## 13. エラー処理

- `peekInstanceUrl()` は preference store の読み取りのみで、例外を投げない。設定が未投入の場合は空文字列が返る(`ScopedPreferenceStore.getString` の既定)。空文字列はノードの URL と一致しないため、先行照合で `null` になる。**これは変更前も `captureConnection` の URL が空になり不成立だったため、挙動は同じ。**
- `UnstableConnectionException` の扱いは現状のまま(`null`)。
- ゲート不成立時に呼び出し元が行う `connectionRejected` 監査行の出力とユーザー通知は**一切変更しない**(R-4)。戻り値が従来と同じ `null` であるため、呼び出し元は変更不要。

## 14. タイムアウトとリトライ

- `peekInstanceUrl()` は I/O を伴わないため、タイムアウト・リトライの対象外。
- 先行照合にリトライを入れない。レースした場合は後段の `captureConnection` の seqlock リトライと最終照合が引き受ける。

## 15. 冪等性

先行照合は純粋な読み取りであり、何度呼んでも状態を変えない。ゲート全体の冪等性は変更前と同じ。

## 16. 並行処理と競合

- 先行照合は seqlock の外で読む。競合の帰結は §9.2 / §9.3 に尽きる。
- 二重処理・データ不整合は発生しない。先行照合は書き込みを行わず、既存の `InFlightWriteGuard` にも触れない。
- `pinnedConnectionFor` は複数のスレッド(バックグラウンドのコルーチン)から並行に呼ばれうるが、`peekInstanceUrl` は状態を持たないため追加の同期は不要。

## 17. 認証と認可

- **本変更は認証の判定基準を変更しない。** URL 一致後は従来どおり `authFingerprint` で照合する。
- 機密性の不変条件(別インスタンスへ資格情報が渡らない)は、変更前も破れていなかった(issue #49 の「限定条件」参照。リフレッシュ先は現在設定中のアカウントの OAuth サービスであり、ノード側の別インスタンスではない)。本変更はこれを維持したまま、**不一致時に資格情報へ触れる回数を減らす**。
- `peekInstanceUrl()` はトークンを返さない。ログにも出さない。

## 18. ログ・監視・監査

- 先行照合で不成立になった場合も、**呼び出し元が出す `connectionRejected` の監査行は従来どおり出る**(戻り値が `null` で変わらないため)。監査の網羅性は落ちない。
- 先行照合そのものに新しいログを追加しない。追加すると、正常系(インスタンスを切り替えて古いノードを操作した)で恒常的にログが出るため。
- **未確定事項**: 「ゲート不成立が先行照合で起きたか捕捉後の照合で起きたか」を運用上区別する必要があるか。現時点で必要という根拠がないため区別しない。必要ならば監査行の `outcome` に段階を持たせる案があるが、本設計では採らない。

## 19. 障害時の復旧方法 / 既存機能への影響 / 移行 / ロールバック

- **復旧**: 本変更は永続状態を作らないため、復旧手順は不要。
- **既存機能への影響**: §2.1 の 4 経路。いずれも戻り値の型・意味は不変。設定・データの移行なし。
- **移行方法**: 不要。
- **ロールバック**: 実装コミットの revert のみ。設定の巻き戻しやデータ修復は不要。

## 20. テスト方針

### 20.1 既存テストの計測点が誤っていることの確認(重要)

現在 `src/test/kotlin/com/gitlab/eclipse/ci/actions/WriteActionTest.kt` は `apiClient.captureConnection()` を**丸ごとモックしている**(`:75`, `:81`, `:92`, `:103`)。したがって `captureConnection` 内部のトークン取得は一度も実行されず、**「ゲート不成立時に API 呼び出し 0 回」を主張する既存テストは OAuth リフレッシュを数えていない**。issue #49 の指摘どおりである。

この層に「副作用ゼロ」の主張を書き足しても何も証明しないため、計測点を移す。

### 20.2 追加するテスト

**(a) `WriteActionTest`(`captureConnection` をモックする層)** — 本設計の中核性質を固定する。

- URL 不一致 → `verify(exactly = 0) { apiClient.captureConnection() }` かつ戻り値 `null`
- URL 一致・fingerprint 不一致 → `verify(exactly = 1) { apiClient.captureConnection() }` かつ戻り値 `null`
  **このテストは「アカウント側は本設計のスコープ外であり、副作用は依然として発生しうる」ことを明示的に固定する**(§3)。範囲を後から誤解できないようにするため、意図的に置く。
- URL 一致・fingerprint 一致 → スナップショットを返す(既存の回帰)
- 末尾スラッシュの有無が先行照合で吸収されること(R-5)

**(b) `GitLabApiClient` 実体を使う層** — 副作用が本当に起きないことを固定する。

- `peekInstanceUrl()` を呼んでも `verify(exactly = 0) { tokenManager.getToken() }` であること

この計測はリポジトリに既に慣行として存在する(`src/test/kotlin/com/gitlab/eclipse/security/SecurityScanLauncherTest.kt:313`、`src/test/kotlin/com/gitlab/eclipse/api/GitLabApiClientReadPinningTest.kt:80` ほか)。新しい手法を導入しない。

### 20.3 変異テスト

追加した各テストについて、production 側に変異を当てて実際に RED になることを確認する(先行照合の削除、正規化の除去、`!=` の反転)。変異が**コンパイルされている**ことも確認する。

### 20.4 検証できないこと

実機 Eclipse での OAuth リフレッシュ挙動(期限切れトークンを持つ状態で古いノードを操作する)は headless では再現できない。手動検証手順を実装 PR に記載する。

## 21. 受け入れ条件

| ID | 条件 | 検証方法 |
|---|---|---|
| AC-1 | ノード URL が現在の設定 URL と異なるとき、`captureConnection` が呼ばれない | 単体テスト(20.2 a) |
| AC-2 | `peekInstanceUrl()` がトークンマネージャに触れない | 単体テスト(20.2 b) |
| AC-3 | URL 一致時のゲート判定が現状と同一 | 既存テストの回帰 + 追加テスト |
| AC-4 | 4 つの呼び出し元が無変更でコンパイルできる | ビルド |
| AC-5 | `./gradlew build` がベースライン(headless SWT 由来の 36 失敗のみ)を維持 | `verify.sh` で `FAILSET_IDENTICAL` |
| AC-6 | detekt の新規指摘 0 | pristine worktree との指摘集合の差分 |

## 22. 未決事項

本設計で確定していない事項。**推測で確定させない。**

1. **`GitLabApiClient` のクラス分割**(§10)。`@Suppress("TooManyFunctions")` がさらに 1 つ増える。issue #44 で defer 済みの論点であり、本設計では判断しない。
2. **ゲート不成立の段階を監査ログで区別するか**(§18)。現時点で必要という根拠がない。
3. **アカウント側の先行照合を将来行う場合の設計**(下記)。

### 将来の拡張(本設計では実装しない)

アカウント側も先行照合するには `TokenProvider` に非更新読み取りを追加する必要がある。`OAuthTokenProvider` の `currentToken` は既にキャッシュされたフィールド(`authentication/OAuthTokenProvider.kt:20`)であり、新しい状態を作る話ではなく公開の仕方の問題である。

ただし `GitLabTokenProviderManager.getToken()`(`authentication/GitLabTokenProviderManager.kt:13-29`)は、設定された種別の provider が空文字列を返した場合に**全 provider を順に試すフォールバック**を持つ。したがって非更新読み取りは、
(a) このフォールバックを再現する(`captureConnection` と整合するが複雑)か、
(b) 設定された種別のみを読む(単純だが `captureConnection` の結果と食い違い、誤った早期拒否を生みうる)
のいずれかを選ぶ必要がある。**この選択は未決である。**

## 23. 想定されるリスク

| ID | リスク | 影響度 | 緩和 |
|---|---|---|---|
| RISK-1 | 設定 URL のフラップ時に、従来成立していたゲートが不成立になる(§9.3) | 低 | 発生には短時間に 2 回の設定変更が必要。結果は安全側。設計書に受容済みとして明記 |
| RISK-2 | 先行照合と本照合で正規化が食い違い、通るはずのゲートが落ちる | 中 | 既存の `normalizeInstanceUrl` を両方で使う(R-5)。末尾スラッシュのテストで固定 |
| RISK-3 | 「副作用ゼロ」の主張が URL 側のみであることが誤解され、アカウント側も解決したと思われる | 中 | §3 で対象外を明記し、20.2(a) に「URL 一致・fingerprint 不一致では capture が呼ばれる」テストを置いて範囲を固定 |
| RISK-4 | 読み取り経路(`DiscussionsLoader` / `DisplayJobLogHandler`)への影響が見落とされる | 中 | §2.1 に 4 経路すべてを明記。issue #49 本文の「CI の全書き込み経路」という記述は過小である |
| RISK-5 | `peekInstanceUrl` が将来誤って `captureConnection` の代わりに使われ、原子性のない (url, token) 組が作られる | 中 | KDoc に用途を先行照合専用と明記。トークンを返さない設計なので組を作れない |

## 24. 確認できた範囲 / 確認できていない範囲

**確認できた範囲**(`develop` @ `2f1ec61` の実ソースで裏取り済み):

- `pinnedConnectionFor` の呼び出し元が 4 箇所であり、うち 2 箇所が読み取りであること
- `captureConnection` が seqlock であること、`ConnectionConfigGeneration` が `AtomicLong` であること
- `authFingerprint` がトークン由来であること
- `TokenProvider` が `getToken()` のみを持つこと、`GitLabTokenProviderManager` にフォールバック探索があること
- `OAuthTokenProvider.getToken()` の副作用の連鎖(`loadCachedToken` / `refreshTokenIfExpired` / `updateToken` → 設定書き込み + secure storage + `sendConfiguration()`)
- `WriteActionTest` が `captureConnection` をモックしており、トークン経路を通っていないこと
- `normalizeInstanceUrl` が `url.trimEnd('/')` であること

**追加情報がなければ判断できない事項**:

- 実運用で「URL は一致するがアカウントのみ変わった」ケースがどの程度発生するか(対象外とした判断の妥当性に関わる)
- 設定 URL のフラップ(§9.3)が現実に起こりうる操作系列があるか。設定ダイアログの OK は直列化されるため、複数ウィンドウや外部からの preference 書き換えを想定しない限り起きないと考えられるが、確定していない
