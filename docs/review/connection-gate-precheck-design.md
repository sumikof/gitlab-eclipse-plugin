# 接続ゲートの副作用先行抑止(インスタンス URL 照合の同期境界内移動)設計書

対象 issue: #49
関連: PR #48 の Codex レビュー https://github.com/sumikof/gitlab-eclipse-plugin/pull/48#discussion_r3703790506
対象ブランチ(設計時点): `develop` @ `2f1ec61`
改訂: round 2(Codex round 1 の P1 / P2 を反映。経緯は §25)

> 本書はレビュー専用。実装 PR およびマージ先ブランチには含めない。

---

## 1. 背景と目的

接続ゲート(サイドバーのノードが読み込まれた接続が現在も有効かの判定)は、URL を照合する **前** に `GitLabApiClient.captureConnection()` を呼ぶ。`captureConnection` はトークンを無条件に読むため、以下の副作用が照合より先に発生しうる。

```
captureConnection()
  └─ tokenManager.getToken()
       └─ OAuthTokenProvider.getToken()
            └─ refreshTokenIfExpired()          ← トークンが期限切れなら
                 ├─ GitLabOAuthService.refreshToken()  = HTTP
                 ├─ updateToken() → 設定書き込み + secure storage + LS 設定通知
                 └─ 失敗時 NotificationUtils.show(...)  = ユーザー可視の通知
```

結果として「ゲート不成立時は観測可能な副作用ゼロ」という Phase 4 の設計上の主張は、文字通りには成立していない。

**本設計の目的は、インスタンス URL の照合を `captureConnection` の同期境界(seqlock)の内側・資格情報の読み取りより前へ移し、URL 不一致によるゲート不成立で資格情報に一切触れないようにすることである。**

## 2. 対象範囲

- `api/GitLabApiClient.kt` に、URL 述語を受け取る捕捉メソッドを追加する。
- `ci/actions/WriteAction.kt` の `pinnedConnectionFor` をそれに載せ替える。
- `ci/lint/CiLint.kt` の `runCiLint` と `ci/actions/CreatePipeline.kt` の `runCreatePipeline` をそれに載せ替える。
- 上記に対応するテストの追加と、**既存テストの計測点の見直し**(§20)。

### 2.1 影響を受けるゲート経路(全 6 経路)

**issue #49 の本文は影響範囲を「CI の全書き込み経路」と記述しているが、これは過小である。** 実際には 2 種類のヘルパに分かれた **6 経路**があり、うち 2 経路は書き込みではなく**読み取り**である。本設計はこの 6 経路すべてをスコープに含む。

| # | 経路 | 種別 | 経由するヘルパ |
|---|---|---|---|
| 1 | `ci/actions/PipelineActionHandler.kt:129` | write | `pinnedConnectionFor` |
| 2 | `mergerequests/discussions/DiscussionWriteFlow.kt:56` | write | `pinnedConnectionFor` |
| 3 | `mergerequests/discussions/DiscussionsLoader.kt:114` | **read** | `pinnedConnectionFor` |
| 4 | `ci/actions/DisplayJobLogHandler.kt:110` | **read** | `pinnedConnectionFor` |
| 5 | `ci/actions/CiLintLaunch.kt:58` | write(lint POST) | `runCiLint` |
| 6 | `ci/actions/CreatePipelineHandler.kt:99` | write | `runCreatePipeline` |

経路 5・6 は `pinnedConnectionFor` を使わないが、**構造的に同型**である。

```kotlin
// ci/lint/CiLint.kt:54-61 — runCreatePipeline (ci/actions/CreatePipeline.kt:48-55) も完全に同じ形
val connection = try { capture() } catch (ignored: UnstableConnectionException) {
  return CiLintOutcome.ConnectionUnstable
}
if (!sameConfiguredInstance(contextInstanceUrl, connection.instanceUrl)) {
  return CiLintOutcome.InstanceMismatch          // ← capture() の中でトークンは既に読まれている
}
```

## 3. 対象外

- **アカウント(`authFingerprint`)側の先行照合。** `authFingerprint` はトークンから導出される(`api/GitLabApiClient.kt:36-39`、sha256 → 先頭 N 桁の hex)ため、トークンを取得せずにアカウント一致は判定できない。`TokenProvider`(`authentication/TokenProvider.kt`、3 行)には非更新読み取り API が無く、追加は認証周りの共有インターフェース変更になる。
  **したがって「URL は一致するがアカウントが変わった」ケースでは、従来どおり照合前に副作用が発生しうる。本設計はこれを解決しない**(§22 の将来の拡張)。
- `captureConnection()` の URL ゲートを持たない呼び出し元(`api/GitLabApiClient.kt:272` の `sendGet` フォールバック、`views/sidebar/GitLabSidebarView.kt:348` および `:527`)。これらは照合対象の期待 URL を持たないため、本設計の適用対象ではない。
- `OAuthTokenProvider` のリフレッシュ挙動そのもの。
- `GitLabTokenProviderManager.getToken()` のフォールバック探索の見直し。

## 4. 現在の課題

`api/GitLabApiClient.kt:178-190`(現行)。

```kotlin
fun captureConnection(): ConnectionSnapshot {
  repeat(MAX_CAPTURE_ATTEMPTS) {
    val g1 = readGeneration()
    if (g1 % 2 != 0L) return@repeat            // 更新中 -> リトライ
    val url = preferenceStore.getString(PreferenceConstants.GITLAB_INSTANCE_URL)
    val token = tokenManager.getToken()        // ← URL 照合と無関係に必ず実行される
    val g2 = readGeneration()
    if (g1 == g2) {                            // 偶数かつ不変 -> 一貫した組
      return ConnectionSnapshot(url, token, authFingerprint(token), g1)
    }
  }
  throw UnstableConnectionException()
}
```

seqlock が保証するのは **(url, token) の原子性**であって、「URL が期待と違うときトークンを読まない」ことではない。照合はこの関数の外・戻り値に対して行われるため、副作用は常に照合より先に起きる。

## 5. 要件

| ID | 要件 |
|---|---|
| R-1 | 確定した設定 URL がノード/コンテキストの期待 URL と一致しない場合、資格情報(`tokenManager.getToken()`)を読まずにゲートを不成立にすること |
| R-2 | R-1 の不成立が観測可能な副作用(HTTP・設定書き込み・secure storage 書き込み・LS 設定通知・ユーザー通知)を一切起こさないこと |
| R-3 | ゲートの判定結果(成立/不成立)が現行と**完全に一致**すること。緩める方向にも厳しくする方向にも変えない |
| R-4 | ゲート不成立時の戻り値・監査ログ・ユーザー通知が、呼び出し元から見て現状と同一であること |
| R-5 | URL の正規化が単一の実装(`normalizeInstanceUrl`)に統一されたままであること |
| R-6 | 6 経路すべてに適用されること(§2.1) |

## 6. 前提条件と制約

- ディレクトリ構成の変更禁止。ビルドシステム(`build.gradle.kts` / `detekt.yml`)の変更禁止。新規 OSGi バンドル依存の追加なし。
- ログに URI・ユーザーのファイルパス・トークンを出さない。
- 実装は `develop` から分岐した実装ブランチで行い、**実コードのみをコミット**する(本設計書はマージしない)。
- headless devcontainer のため、実 SWT / OAuth 実接続を要する検証はできない。
- **レイヤ制約**: `api` パッケージは `ci` パッケージに依存してはならない。正規化関数 `normalizeInstanceUrl`(`ci/actions/WriteAction.kt:29`)と `sameConfiguredInstance`(`ci/actions/CreatePipeline.kt:24`)は `ci` 側にあるため、`api` 側から呼んではならない。§8.1 の述語方式はこの制約から導かれる。

## 7. システム構成

新しいコンポーネントは導入しない。既存の 4 ファイルに閉じる(呼び出し元 2 箇所の引数変更を含む)。

```
6 経路
 ├─ 経路 1-4 ─▶ WriteAction.pinnedConnectionFor
 └─ 経路 5-6 ─▶ CiLint.runCiLint / CreatePipeline.runCreatePipeline
                        │
                        ▼  期待 URL の述語を渡す(正規化の所有権は ci 側に残る)
              GitLabApiClient.captureConnectionIf(acceptInstanceUrl)   ← 追加
                        │
                        └─ seqlock の内側で: 世代確認 → URL 読取 → 述語 → (通過時のみ)トークン読取
```

## 8. コンポーネントの責務

### 8.1 `GitLabApiClient.captureConnectionIf(acceptInstanceUrl)`(追加)

- **責務**: 設定 URL が `acceptInstanceUrl` に受理された場合にのみ接続スナップショットを捕捉する。受理されない場合は **資格情報を読まずに** `null` を返す。
- **なぜ述語か**: 正規化関数が `ci` パッケージにあり、`api` から参照するとレイヤの逆転になる(§6)。述語にすることで正規化の所有権を `ci` 側に残し、R-5 を維持したまま照合位置だけを `api` 側へ移せる。
- **述語の契約**: 純粋であること(副作用を持たないこと)。リトライにより**複数回呼ばれうる**。例外を投げないこと。呼び出し元はいずれも `normalizeInstanceUrl` による文字列比較のみであり、この契約を満たす。
- `captureConnection()` は `captureConnectionIf { true }` に委譲する薄いラッパとして残す。**シグネチャも意味も不変**であり、URL ゲートを持たない 3 箇所(§3)は無変更。

### 8.2 `WriteAction.pinnedConnectionFor`(変更)

責務は不変。内部で `captureConnectionIf` を使う。`null` は「URL 不一致」を意味し、従来と同じく戻り値 `null` になる。

### 8.3 `CiLint.runCiLint` / `CreatePipeline.runCreatePipeline`(変更)

責務は不変。`capture` seam の型を `() -> ConnectionSnapshot` から `() -> ConnectionSnapshot?` に変え、`null` を `InstanceMismatch` に対応させる。関数内の `sameConfiguredInstance` 呼び出しは削除する(判定が捕捉側へ移るため)。呼び出し元(経路 5・6)が述語を束縛する。

## 9. 処理フロー

### 9.1 `captureConnectionIf`

```kotlin
/**
 * 設定 URL が [acceptInstanceUrl] に受理されたときだけ接続を捕捉する。受理されない場合は
 * 資格情報を読まずに null を返す(#49)。判定は seqlock の内側・[tokenManager] へ触れる前に
 * 行うため、URL 不一致によるゲート不成立は OAuth リフレッシュ(HTTP・永続化・通知)を
 * 誘発しない。[acceptInstanceUrl] は純粋でなければならない(リトライで複数回呼ばれうる)。
 */
fun captureConnectionIf(acceptInstanceUrl: (String) -> Boolean): ConnectionSnapshot? {
  repeat(MAX_CAPTURE_ATTEMPTS) {
    val g1 = readGeneration()
    if (g1 % 2 != 0L) return@repeat                 // 更新中 -> リトライ
    val url = preferenceStore.getString(PreferenceConstants.GITLAB_INSTANCE_URL)
    if (!acceptInstanceUrl(url)) {
      // 不一致。ただし「更新途中の URL を読んだだけ」かどうかを世代で検証してから確定する。
      // 検証せずに拒否すると、確定後なら一致していたはずの URL で落としうる(§9.3)。
      if (readGeneration() == g1) return null       // 安定した不一致 -> 資格情報に触れず終了
      return@repeat                                 // 更新とレースした読み -> リトライ
    }
    val token = tokenManager.getToken()             // ← 述語を通過した場合のみ
    val g2 = readGeneration()
    if (g1 == g2) {
      return ConnectionSnapshot(url, token, authFingerprint(token), g1)
    }
  }
  throw UnstableConnectionException()
}

fun captureConnection(): ConnectionSnapshot =
  captureConnectionIf { true } ?: throw UnstableConnectionException()
```

`captureConnectionIf { true }` は述語が常に真なので `null` を返す枝に入らない。上の `?:` は Kotlin の型を満たすための到達不能枝であり、意味は現行と同一である。

**未確定事項**: 到達不能枝を `?: error(...)` にするか `throw UnstableConnectionException()` にするかは実装時の判断とする。前者は「起こりえない」ことを表明でき、後者は万一到達しても既存の失敗モードに収まる。本設計では後者を既定とするが、レビューでの指摘があれば従う。

### 9.2 `pinnedConnectionFor`

```kotlin
fun pinnedConnectionFor(
  apiClient: GitLabApiClient,
  nodeInstanceUrl: String,
  nodeAuthFingerprint: String,
): ConnectionSnapshot? {
  val snapshot = try {
    // URL 不一致なら null。資格情報には触れられていない(#49)。
    apiClient.captureConnectionIf { url -> sameConfiguredInstance(nodeInstanceUrl, url) }
  } catch (ignored: UnstableConnectionException) {
    return null
  } ?: return null
  // instanceUrl の一致は捕捉時点で構造的に保証済み。残るアカウント照合のみを行う。
  return if (snapshot.authFingerprint == nodeAuthFingerprint) snapshot else null
}
```

`sameConfiguredInstance`(`ci/actions/CreatePipeline.kt:24`)は既に `normalizeInstanceUrl` を用いた共有関数であり、これを使うことで R-5 を満たす。既存の `sameInstance` 比較は捕捉時の述語に置き換わるため削除する(構造的に保証されるものを二重に書かない)。

### 9.3 なぜ拒否側にも世代検証が必要か

素朴に「述語が偽なら即 `null`」とすると、**更新途中の URL を読んで拒否**しうる。世代が奇数でないことを確認した直後に更新が始まると、`preferenceStore` から読める URL は新しい値でありながら、その更新はまだ確定していない。現行実装ではこの読みは `g1 != g2` によって破棄され、リトライ後の確定値で判定される。

拒否側に世代検証を入れないと、**現行なら成立していたゲートが不成立になりうる**(R-3 違反)。§9.1 のとおり、不一致を確定する前に `readGeneration() == g1` を確認し、一致しなければリトライすることで、**拒否の判定も確定値に対してのみ行われる**。

### 9.4 決定関数の同一性

上記により、ゲートの判定結果は次のとおり現行と完全に一致する(R-3)。

| 状況 | 現行 | 本設計 |
|---|---|---|
| 確定 URL がノードと一致・fingerprint 一致 | スナップショット | スナップショット(同一値) |
| 確定 URL がノードと不一致 | `null`(**トークン読取後**) | `null`(**トークン未読取**) |
| 確定 URL 一致・fingerprint 不一致 | `null`(トークン読取後) | `null`(トークン読取後・§3 のとおり対象外) |
| `MAX_CAPTURE_ATTEMPTS` 回とも不安定 | `UnstableConnectionException` → `null` | 同左 |

**変わるのは 2 行目における副作用の有無のみであり、判定結果は変わらない。** round 1 で受容した「フラップ時の挙動差」は、この設計では**発生しない**(§25)。

## 10. API / インターフェース

### 追加

```kotlin
// api/GitLabApiClient.kt
fun captureConnectionIf(acceptInstanceUrl: (String) -> Boolean): ConnectionSnapshot?
```

### 変更(シグネチャ)

```kotlin
// ci/lint/CiLint.kt
fun runCiLint(contextInstanceUrl: String, capture: () -> ConnectionSnapshot?, ...): CiLintOutcome
// ci/actions/CreatePipeline.kt
fun runCreatePipeline(contextInstanceUrl: String, capture: () -> ConnectionSnapshot?, ...): CreateResult
```

**未確定事項**: `contextInstanceUrl` 引数を残すか。判定が述語へ移るため関数内では監査メッセージ用途しか残らない。呼び出し元は監査に同じ値を使っており、引数を残すほうが差分は小さい。本設計では**残す**を既定とする。

`pinnedConnectionFor` のシグネチャは不変。経路 1-4 の呼び出し元は無変更。

`GitLabApiClient` には既に `@Suppress("TooManyFunctions")` が付いている(`api/GitLabApiClient.kt:54`)。本追加で 1 つ増える。**未確定事項**: クラス分割の是非は issue #44 で「GraphQL 追加時などに検討」として defer 済みであり、本設計では判断しない。

## 11. データモデル

変更なし。`ConnectionSnapshot`(`api/ConnectionSnapshot.kt`)は不変。新しい `data class` を追加しないため、`SecretRedactionConventionTest` の期待マップに追加すべきクラスは発生しない。

## 12. トランザクション境界

変更なし。本変更は書き込みを開始する **前** の判定にのみ作用する。ゲート成立後の書き込みは従来どおり `ConnectionSnapshot` に固定される。

## 13. エラー処理

- `captureConnectionIf` は述語が偽で確定したとき `null` を返す。呼び出し元はこれを従来の不成立と同じに扱う(`pinnedConnectionFor` は `null`、`runCiLint`/`runCreatePipeline` は `InstanceMismatch`)。
- `UnstableConnectionException` の意味と発生条件は不変。
- 設定が未投入の場合、`getString` は空文字列を返す。空文字列は期待 URL と一致しないため不成立になる。**現行も同じく不成立**であり挙動は変わらない。
- ゲート不成立時の監査行(`connectionRejected` / `InstanceMismatch` の監査メッセージ)とユーザー通知は**一切変更しない**(R-4)。
- 述語が例外を投げた場合は `captureConnectionIf` を貫通する。契約違反(§8.1)であり、握りつぶさない。呼び出し元の述語はいずれも文字列比較のみ。

## 14. タイムアウトとリトライ

- `captureConnectionIf` の再試行回数は現行の `MAX_CAPTURE_ATTEMPTS` を据え置く。**述語による拒否は再試行を消費しうる**(更新とレースした読みの場合)。全試行が不安定だった場合は現行と同じく `UnstableConnectionException`。
- 述語による確定的な拒否は再試行しない(結果が変わらないため)。
- I/O を伴わないためタイムアウトの対象外。

## 15. 冪等性

`captureConnectionIf` は読み取りのみで状態を変えない。ただし述語を通過した場合は `tokenManager.getToken()` を呼ぶため、**現行の `captureConnection` と同じ**副作用可能性を持つ(期限切れトークンのリフレッシュ)。これは変更前と同じであり、本設計が減らすのは「述語が拒否した場合」の副作用のみ。

## 16. 並行処理と競合

- 設定更新との競合は seqlock で扱う。**述語の評価も、拒否の確定も、seqlock の内側にある**(§9.3)。したがって設定更新とゲート判定の間に、判定結果を変える競合は残らない。
- 述語は複数回呼ばれうるため純粋でなければならない(§8.1)。
- 二重処理・データ不整合は発生しない。本変更は書き込みを行わず、既存の `InFlightWriteGuard` にも触れない。
- `pinnedConnectionFor` および 2 つのヘルパは複数のバックグラウンドコルーチンから並行に呼ばれうるが、追加の共有状態を持たないため新たな同期は不要。

## 17. 認証と認可

- **判定基準を変更しない。** URL 一致後は従来どおり `authFingerprint` で照合する(`pinnedConnectionFor`)。
- 機密性の不変条件(別インスタンスへ資格情報が渡らない)は変更前も破れていなかった(issue #49 の「限定条件」)。本設計はこれを維持したまま、**URL 不一致時に資格情報へ触れる回数をゼロにする**。
- 述語はトークンを受け取らない。URL のみを受け取る。ログにも出さない。

## 18. ログ・監視・監査

- ゲート不成立時に呼び出し元が出す監査行は**従来どおり出る**(戻り値の意味が変わらないため)。監査の網羅性は落ちない。
- 捕捉側に新しいログを追加しない。追加すると、インスタンスを切り替えて古いノードを操作する正常系で恒常的にログが出るため。
- **未確定事項**: 「不成立が URL 述語で起きたか fingerprint で起きたか」を運用上区別する必要があるか。現時点で必要という根拠がないため区別しない。

## 19. 障害時の復旧方法 / 既存機能への影響 / 移行 / ロールバック

- **復旧**: 永続状態を作らないため復旧手順は不要。
- **既存機能への影響**: §2.1 の 6 経路。判定結果は不変(§9.4)。設定・データの移行なし。
- **移行方法**: 不要。
- **ロールバック**: 実装コミットの revert のみ。

## 20. テスト方針

### 20.1 既存テストの計測点が誤っていることの確認(重要)

`src/test/kotlin/com/gitlab/eclipse/ci/actions/WriteActionTest.kt` は `apiClient.captureConnection()` を**丸ごとモックしている**(`:75`, `:81`, `:92`, `:103`)。したがって `captureConnection` 内部のトークン取得は一度も実行されず、**「ゲート不成立時に API 呼び出し 0 回」を主張する既存テストは OAuth リフレッシュを数えていない**。issue #49 の指摘どおりである。

同様に `runCiLint` / `runCreatePipeline` のテストは `capture` seam をモックするため、同じ盲点を持つ。

したがって「副作用ゼロ」の主張は、**`GitLabApiClient` 実体を使う層**で検証しなければならない。

### 20.2 追加するテスト

**(a) `GitLabApiClient` 実体を使う層 — 本設計の中核性質**

`tokenManager` をモックし、`readGeneration` を制御して以下を固定する。

- 述語が拒否 → 戻り値 `null` かつ **`verify(exactly = 0) { tokenManager.getToken() }`**(R-1 / R-2)
- 述語が受理 → スナップショットを返し、`getToken()` は 1 回(既存の回帰)
- **§9.3 の性質**: 述語が拒否したときの読みが更新とレースしていた場合(1 回目の `readGeneration` の後で世代が進む)、`null` を返さずリトライすること。リトライ後に述語が受理すればスナップショットを返すこと。**これが R-3(判定結果の同一性)を守っている箇所であり、最も落としやすい**
- 全試行が不安定 → `UnstableConnectionException`(既存の回帰)
- `captureConnection()` が `captureConnectionIf { true }` と同一の結果になること

この計測(`verify(exactly = 0) { tokenManager.getToken() }`)はリポジトリに既存の慣行である(`src/test/kotlin/com/gitlab/eclipse/security/SecurityScanLauncherTest.kt:313`、`src/test/kotlin/com/gitlab/eclipse/api/GitLabApiClientReadPinningTest.kt:80` ほか)。新しい手法を導入しない。`readGeneration` は既にコンストラクタ注入の seam である(`api/GitLabApiClient.kt:59`)ため、世代を制御する手段は既にある。

**(b) `WriteActionTest`(`captureConnectionIf` をモックする層)**

- 述語が拒否 → `null`
- 受理・fingerprint 不一致 → `null`。**このテストは「アカウント側は本設計のスコープ外であり副作用は依然として発生しうる」ことを明示的に固定する**(§3)。範囲を後から誤解できないよう意図的に置く
- 受理・fingerprint 一致 → スナップショット
- `pinnedConnectionFor` が `captureConnectionIf` に渡す述語が、末尾スラッシュの差を吸収すること(R-5)

**(c) `runCiLint` / `runCreatePipeline`**

- `capture` が `null` → `InstanceMismatch`(経路 5・6 の R-6)
- 既存の `InstanceMismatch` テストを新しい形に移行し、**判定が捕捉側へ移ったあとも同じ結果になる**ことを固定する

### 20.3 変異テスト

追加した各テストについて production 側に変異を当て、実際に RED になること、および**変異がコンパイルされている**ことを確認する。最低限:

- 述語判定を `tokenManager.getToken()` の後ろへ移動(= 現行の欠陥の再現)
- §9.3 の世代再確認(`if (readGeneration() == g1)`)を削除
- `sameConfiguredInstance` を素の `==` に置換(正規化の除去)

### 20.4 検証できないこと

実機 Eclipse での OAuth リフレッシュ挙動(期限切れトークンを持つ状態で古いノードを操作する)は headless では再現できない。手動検証手順を実装 PR に記載する。

## 21. 受け入れ条件

| ID | 条件 | 検証方法 |
|---|---|---|
| AC-1 | URL 述語が拒否したとき `tokenManager.getToken()` が呼ばれない | 単体テスト(20.2 a) |
| AC-2 | 述語の拒否が確定値に対してのみ行われ、更新とレースした読みではリトライする | 単体テスト(20.2 a) |
| AC-3 | 6 経路すべてでゲートの判定結果が現行と一致する | 既存テストの回帰 + 20.2 b/c |
| AC-4 | `captureConnection()` の意味・シグネチャが不変で、URL ゲートを持たない 3 箇所が無変更 | ビルド + 既存テスト |
| AC-5 | `./gradlew build` がベースライン(headless SWT 由来の 36 失敗のみ)を維持 | `verify.sh` で `FAILSET_IDENTICAL` |
| AC-6 | detekt の新規指摘 0 | pristine worktree との指摘集合の差分 |

## 22. 未決事項

本設計で確定していない事項。**推測で確定させない。**

1. **`captureConnection()` の到達不能枝の書き方**(§9.1)。
2. **`runCiLint` / `runCreatePipeline` の `contextInstanceUrl` 引数を残すか**(§10)。既定は「残す」。
3. **`GitLabApiClient` のクラス分割**(§10)。issue #44 で defer 済み。
4. **ゲート不成立の理由を監査ログで区別するか**(§18)。
5. **アカウント側の先行照合を将来行う場合の設計**(下記)。

### 将来の拡張(本設計では実装しない)

アカウント側も照合前に判定するには `TokenProvider` に非更新読み取りが必要である。`OAuthTokenProvider` の `currentToken` は既にキャッシュされたフィールド(`authentication/OAuthTokenProvider.kt:20`)であり、新しい状態を作る話ではなく公開の仕方の問題である。

ただし `GitLabTokenProviderManager.getToken()`(`authentication/GitLabTokenProviderManager.kt:13-29`)は、設定された種別の provider が空文字列を返した場合に**全 provider を順に試すフォールバック**を持つ。したがって非更新読み取りは、
(a) このフォールバックを再現する(`captureConnection` と整合するが複雑)か、
(b) 設定された種別のみを読む(単純だが `captureConnection` の結果と食い違い、誤った拒否を生みうる)
のいずれかを選ぶ必要がある。**この選択は未決である。**

なお本設計の `captureConnectionIf` は、将来この判定を足す際の自然な置き場所になる(述語をトークン読取後の位置にもう 1 つ設ける、あるいは述語の引数を拡張する)。

## 23. 想定されるリスク

| ID | リスク | 影響度 | 緩和 |
|---|---|---|---|
| RISK-1 | §9.3 の世代再確認を実装時に落とし、更新とレースした読みで誤って拒否する(R-3 違反) | **高** | AC-2 として受け入れ条件に明示。20.3 で当該行の変異を必須にする |
| RISK-2 | 述語が副作用を持つ実装に将来変えられ、リトライで複数回実行される | 中 | §8.1 に契約として明記し KDoc に書く。現状の述語は文字列比較のみ |
| RISK-3 | 「副作用ゼロ」の主張が URL 側のみであることが誤解され、アカウント側も解決したと思われる | 中 | §3 で対象外を明記し、20.2(b) に「URL 一致・fingerprint 不一致では `getToken` が呼ばれる」テストを置いて範囲を固定 |
| RISK-4 | 経路 5・6(`runCiLint` / `runCreatePipeline`)が見落とされ、同型の欠陥が残る | 中 | §2.1 に 6 経路すべてを明記。R-6 と AC-3 で固定。**issue #49 本文の「CI の全書き込み経路」という記述は過小である** |
| RISK-5 | `captureConnection()` のラッパ化で既存 3 箇所の意味が変わる | 中 | AC-4 として固定。`captureConnectionIf { true }` は `null` 枝に入らないことを §9.1 で論証 |
| RISK-6 | `api` から `ci` の正規化関数を直接呼ぶ実装にされ、レイヤが逆転する | 低 | §6 と §8.1 に制約として明記。述語方式がこの制約から導かれることを説明済み |

## 24. 確認できた範囲 / 確認できていない範囲

**確認できた範囲**(`develop` @ `2f1ec61` の実ソースで裏取り済み):

- `captureConnection` が `tokenManager.getToken()` を URL 照合と無関係に無条件で呼ぶこと(`api/GitLabApiClient.kt:183`)
- インスタンス URL ゲートが 6 経路あり、2 種類のヘルパに分かれること。うち 2 経路が読み取りであること
- `runCiLint`(`ci/lint/CiLint.kt:54-61`)と `runCreatePipeline`(`ci/actions/CreatePipeline.kt:48-55`)が `pinnedConnectionFor` と構造的に同型であること
- `sameConfiguredInstance`(`ci/actions/CreatePipeline.kt:24`)が `normalizeInstanceUrl`(`ci/actions/WriteAction.kt:29` = `url.trimEnd('/')`)を用いた共有関数であること
- `ConnectionConfigGeneration` が `AtomicLong` による偶奇の seqlock であること
- `readGeneration` が既にコンストラクタ注入の seam であること(`api/GitLabApiClient.kt:59`)= 世代を制御するテストが書けること
- `authFingerprint` がトークン由来であること。`TokenProvider` が `getToken()` のみを持つこと。`GitLabTokenProviderManager` にフォールバック探索があること
- `OAuthTokenProvider.getToken()` の副作用の連鎖
- `WriteActionTest` が `captureConnection` をモックしており、トークン経路を通っていないこと

**追加情報がなければ判断できない事項**:

- 実運用で「URL は一致するがアカウントのみ変わった」ケースがどの程度発生するか(対象外とした判断の妥当性に関わる)
- 設定更新が `beginUpdate`/`endUpdate` の間で URL とトークンをどの順で書くか。§9.3 の論証は「更新中は世代が奇数である」ことのみに依存し書き込み順に依存しないが、順序が分かればテストの現実性を上げられる
- `MAX_CAPTURE_ATTEMPTS` の現行値と、述語拒否が試行を消費することの実用上の影響

## 25. レビュー履歴

### round 1(Codex、2026-08-11)— 反映済み

| 指摘 | 判定 | 反映 |
|---|---|---|
| **P1**: 先行照合(`peekInstanceUrl`)方式では、照合後に設定が変わると `captureConnection` が最終照合の前に `getToken()` を実行するため副作用を取り消せず、R-1/R-2 を満たさない | **正しい**。`api/GitLabApiClient.kt:183` で確認 | 方式を全面的に変更。照合を `captureConnection` の seqlock 内側・トークン読取より前へ移した(§9.1)。`peekInstanceUrl` 案は破棄 |
| **P2**: 「早期拒否には設定変更が 2 回必要」は誤り。ノードが A、設定が以前から B の状態で、1 回の B→A 保存で成立する | **正しい**。最初の不一致が直前の変更である必要はなかった | 該当する挙動差そのものが新方式では発生しなくなったため、記述ごと削除(§9.4)。RISK-1 も差し替え |

**round 1 の設計に対する自己指摘(掃引で発見、Codex の指摘外)**: `runCiLint` / `runCreatePipeline` が `pinnedConnectionFor` と同型であることを見落としており、影響範囲を 4 経路と記述していた。実際は 6 経路(§2.1)。R-6・RISK-4・AC-3 を追加。

**round 2 で新たに導入し、まだレビューを受けていない論点**: §9.3(拒否側の世代検証)。これは round 1 の P1 対応で新たに生じた要件であり、実装時に最も落としやすい箇所として RISK-1 に格上げしている。
