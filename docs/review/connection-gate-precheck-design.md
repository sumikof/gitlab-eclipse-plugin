# 接続ゲートの副作用先行抑止(インスタンス URL 照合の同期境界内移動)設計書

対象 issue: #49
関連: PR #48 の Codex レビュー https://github.com/sumikof/gitlab-eclipse-plugin/pull/48#discussion_r3703790506
対象ブランチ(設計時点): `develop` @ `2f1ec61`
改訂: round 5(Codex round 1〜4 の指摘を反映。経緯は §25)

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
| R-3 | ゲートの判定が、**呼び出し中のある瞬間に実際に設定されていた URL(= ある安定世代の値)に対して線形化可能**であること。存在しなかった設定値や更新途中の中間状態に基づく判定を行わないこと。**現行実装のタイミング依存挙動をビット単位で再現することは要求しない**(理由と競合時の帰結は §9.4) |
| R-3a | 資格情報を読まないことによって、**別インスタンスへの送信が起きる方向**の緩みが生じないこと。すなわち「URL が不一致のまま成立する」判定を一切増やさないこと。**これは各試行(per-attempt)の性質として読む** — アルゴリズム全体の結果が現行の部分集合になるという意味ではない(理由は §9.5 末尾) |
| R-4 | **設定更新と競合していない場合**、**呼び出し元が生成するもの**(ゲート結果・監査行・generic なユーザー通知)が現状と同一であること。競合時に許容する差は §9.5 に列挙する(それ以外の差は認めない) |
| R-4a | R-4 は**資格情報の取得に由来する副作用**(OAuth リフレッシュ失敗時の再認証通知、認証種別の書き換え等)を含まない。これらは R-2 の目的そのものとして、**競合が無くても意図的に消える**。消える範囲は §9.6 に列挙する |
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

責務は不変。`capture` seam の型を `() -> ConnectionSnapshot` から `() -> ConnectionSnapshot?` に変え、`null` を `InstanceMismatch` に対応させる。呼び出し元(経路 5・6)が述語を束縛する。

**関数内の既存の `sameConfiguredInstance` 呼び出しは削除せず、postcondition として残す。** これは冗長ではなく**安全境界**である。理由:

- `pinnedConnectionFor` は述語を**関数内で束縛**する(§9.2)ため、述語と照合対象が同一関数に閉じており構造的に安全である。
- 対して `runCiLint` / `runCreatePipeline` は `capture` seam を**呼び出し元から受け取る**。束縛は外部にあり、`ConnectionSnapshot?` という型には**「どの述語で検証されたスナップショットか」が符号化されていない**。呼び出し元が述語を誤る(あるいは `{ true }` を渡す)と、ヘルパは別インスタンスへ lint YAML や pipeline 作成要求を送ってしまう。現行はヘルパ自身の照合がこれを止めている。
- したがって、述語は「資格情報に触れないため」の早期判定、`sameConfiguredInstance` は「別インスタンスへ送らないため」の最終判定であり、**役割が異なる**。前者を入れても後者は外せない(R-3a)。

`pinnedConnectionFor` 側でも同じ postcondition を残す(§9.2)。そちらでは構造的に真であることが保証されるが、不変条件が局所的に読み取れることの価値を優先する。

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
  // instanceUrl の一致は捕捉時の述語で保証済みだが、postcondition として残す(§8.3・R-3a)。
  val sameInstance = sameConfiguredInstance(nodeInstanceUrl, snapshot.instanceUrl)
  val sameAccount = snapshot.authFingerprint == nodeAuthFingerprint
  return if (sameInstance && sameAccount) snapshot else null
}
```

`sameConfiguredInstance`(`ci/actions/CreatePipeline.kt:24`)は既に `normalizeInstanceUrl` を用いた共有関数であり、述語と postcondition の両方でこれを使うことで R-5 を満たす。

### 9.3 なぜ拒否側にも世代検証が必要か

素朴に「述語が偽なら即 `null`」とすると、**更新途中の URL を読んで拒否**しうる。世代が奇数でないことを確認した直後に更新が始まると、`preferenceStore` から読める URL は新しい値でありながら、その更新はまだ確定していない。現行実装ではこの読みは `g1 != g2` によって破棄され、リトライ後の確定値で判定される。

拒否側に世代検証を入れないと、**どの安定世代にも存在しなかった中間状態に基づいて拒否**することになり、R-3(線形化可能性)に違反する。§9.1 のとおり、不一致を確定する前に `readGeneration() == g1` を確認し、一致しなければリトライすることで、**拒否の判定も、ある安定世代に実在した値に対してのみ行われる**。

これは §9.4 で受容する挙動差(**拒否確定の直後**に始まる更新)とは別の問題である。§9.3 が閉じるのは「読んだ値がそもそも確定していなかった」ケース、§9.4 が受容するのは「読んだ値は確定していたが、その直後に変わった」ケースであり、前者は不正、後者は線形化可能で正当である。

### 9.4 判定の線形化可能性と、競合時に残る挙動差

**「現行と完全に一致」は達成できない。これは実装の不備ではなく、目的そのものから導かれる帰結である。**

現行実装の判定点は `tokenManager.getToken()` の**後**にある(`api/GitLabApiClient.kt:183-185`)。本設計の目的は不一致時に資格情報を読まないことなので、判定点は必然的にその**前**へ移る。判定点を前へ動かせば、両者の間に更新が着地しうる時間帯の扱いが変わる。すなわち **(a) 不一致時に資格情報を読まない と (b) 資格情報読み取り後に決定する実装のタイミング依存挙動を保存する は両立しない。**

具体的な差(Codex round 2 P1-B):

1. `g1` を読む(偶数)
2. URL を読む → `B`(期待は `A`)
3. 本設計: `readGeneration() == g1` を確認 → 一致 → **即 `null`(拒否)**
4. **その直後**に `B → A` の保存が始まる
5. 現行: 手順 3 の位置ではまだ `getToken()` を実行中であり、その所要時間(OAuth リフレッシュなら HTTP を含む)に手順 4 が着地すると `g1 != g2` でリトライし、確定した `A` で**成立しうる**

現行がここで成立するのは、**高価な資格情報読み取りに時間がかかり、その間に更新が着地するから**にすぎない。これは仕様ではなくタイミングの副産物であり、判定を速くする実装はいずれもこの挙動を再現できない。

したがって本設計は要件を次のとおり定める(R-3)。

> ゲートの判定は、**呼び出し中のある瞬間に実際に設定されていた URL に対して線形化可能**でなければならない。存在しなかった設定値や更新途中の中間状態に基づいて判定してはならない。

この定義のもとで:

| 状況 | 現行 | 本設計 | R-3 |
|---|---|---|---|
| 安定した一致・fingerprint 一致 | スナップショット | スナップショット(同一値) | 満たす |
| 安定した不一致 | `null`(**トークン読取後**) | `null`(**トークン未読取**) | 満たす |
| 一致・fingerprint 不一致 | `null`(トークン読取後) | `null`(トークン読取後・§3 のとおり対象外) | 満たす |
| 全試行が不安定 | `UnstableConnectionException` → 不成立 | 同左 | 満たす |
| **更新が拒否確定の直後に始まる**(上記 3→4) | 成立しうる | **不成立** | 満たす(拒否は手順 2 で実在した `B` に対する判定であり、`B` は確かにその瞬間の設定値だった) |

**受容する競合時の帰結**: 設定を「不一致 → 一致」に戻す操作と、バックグラウンドのゲート判定が重なった場合、その操作は**一時的に拒否されうる**。ユーザーから見た挙動は既存の「接続が変わったため中止した」通知であり、新しい失敗モードではない。再実行すれば成立する。

**方向の非対称性(R-3a)**: この差は常に**拒否側**に倒れる。「不一致なのに成立する」判定は一切増えない。別インスタンスへ送信する方向の緩みが無いことが、本設計で守るべき本質的な不変条件である。

### 9.5 競合時に許容する差の完全な一覧

**ここに挙げた差以外は認めない。** すべて「設定更新と競合した場合」に限る。競合していない場合は現行と完全に一致する(R-4)。

| # | 競合の内容 | 現行 | 本設計 | 影響 |
|---|---|---|---|---|
| 1 | 拒否確定の直後に「不一致 → 一致」の更新が始まり、現行側はトークン読み取り中に検出してリトライし、確定した一致値で**成立**する | 成立 | **不成立**(`InstanceMismatch` / `null`) | 操作が一時的に拒否される。再実行で成立 |
| 2 | 拒否確定の直後に更新が始まり、現行側は残りの試行を使い切っても世代が安定しない | `UnstableConnectionException` | **`InstanceMismatch` / `null`** | `runCiLint` / `runCreatePipeline` で**監査理由が `connection-unstable` から `instance-mismatch` へ変わる**。ユーザー通知はどちらも同じ generic メッセージ。`pinnedConnectionFor` 経路は両者とも `null` に畳まれるため差は出ない |

| 3 | 最初の不一致読みの直後に「不一致 → 一致」更新が起き、現行はその試行で**遅い** `getToken()` を実行している間に更に更新が着地して試行枠を消費し尽くす。本設計は不一致を**速く**検出して次の試行へ進み、安定した一致値を捕捉できる | `UnstableConnectionException` | **成立** | 本設計のほうが成功する。安全側の差(下記) |

**「逆方向は一切存在しない」という主張は誤りである(差 3)。** 各試行が成立を返す条件は現行の部分集合だが、**両者は有界な試行枠(`MAX_CAPTURE_ATTEMPTS`)を異なる時刻に消費する**ため、アルゴリズム全体の結果は部分集合にならない。本設計は拒否を速く判定する分だけ試行枠を温存でき、現行が枯渇する状況でも成立しうる。

**それでも守られる不変条件(R-3a)**: 差 3 で本設計が成立を返す場合も、**その成立は述語が受理し `g1 == g2` が成立した安定世代に対するもの**である。すなわち「URL が不一致のまま成立する」ことは決してない。**別インスタンスへ送信する方向の緩みが無い**という本質的な性質は、試行枠の消費順序に依存せず、各試行の条件だけから成立する。R-3a はこの per-attempt な性質として読むこと。「全体の結果が部分集合」という強い読み方はしない。

**差 2 について**: 監査理由が変わることは、障害調査上の意味を持つ。ただし**どちらの理由も「接続が変わったため中止した」という同一の事象を指しており**、失われる情報は「不安定だったのか不一致だったのか」の区別のみである。競合していない通常のケースでは従来どおり正しい理由が出る。この差を消すには現行と同じく資格情報読み取り後まで判定を遅らせるしかなく、それは本設計の目的そのものを否定する。

**差 3 について**: 本設計のほうが成功しやすくなる方向であり、安全性を損なわない。ユーザーから見れば「以前は稀に『接続が不安定』で失敗した操作が成功するようになる」だけである。

### 9.6 安定時にも意図的に消える副作用(R-4a)

**これは本設計で最もユーザーから見える変化であり、競合の有無に関わらず起きる。**

URL が**安定して不一致**で、かつ **OAuth トークンが期限切れ**の場合、現行は照合前に `getToken()` を呼ぶため、リフレッシュに失敗すると `authentication/OAuthTokenProvider.kt:77-80` が実行される。

```kotlin
if (refreshedToken == null) {
  logger.info("Failed to refresh the OAuth token.")
  NotificationUtils.show("Failed to refresh the OAuth token. Please re-authenticate.")  // ユーザー可視
  setOAuthInPreferenceStore(false)                                                      // 設定書き込み
  return
}
```

本設計はこの `getToken()` 呼び出し自体を省くため、**以下は起きなくなる**。

| 消えるもの | 現行 | 本設計 |
|---|---|---|
| 再認証を促すユーザー通知 | 表示される | **表示されない** |
| 認証種別の書き換え(`setOAuthInPreferenceStore(false)`) | 実行される | **実行されない** |
| リフレッシュの HTTP 要求 | 送信される | **送信されない** |
| リフレッシュ成功時の設定書き込み・secure storage 書き込み・LS 設定通知 | 実行される | **実行されない** |

**これは欠陥ではなく R-2 の目的そのものである。** ただし帰結を明示しておく:

- ユーザーは「**トークンが期限切れである**」ことを、この操作では知らされない。別インスタンスのノードを操作したことによる副産物として通知を受け取っていたのが、受け取らなくなる。
- ただし**情報が失われるわけではない**。トークンのリフレッシュは、正しいインスタンスに対する次の操作、および `startTokenRefreshTimer` による定期リフレッシュで従来どおり行われ、そこで同じ通知が出る。**本設計が変えるのは「無関係な操作のついでに通知される」という経路だけ**である。
- 認証種別が書き換えられないことにより、期限切れ状態がより長く保持されうる。これも次の正規の操作で解消する。

**受け入れ条件**: AC-1 / AC-2 の `verify(exactly = 0) { tokenManager.getToken() }` が、この節の内容をまとめて固定する(`getToken` を呼ばない以上、その下流の副作用はすべて起きえない)。個別の副作用ごとのテストは置かない。

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
- ゲート不成立時に**呼び出し元が出す**監査行(`connectionRejected` / `InstanceMismatch` の監査メッセージ)と generic なユーザー通知は**一切変更しない**(R-4)。
- 一方、**資格情報の取得に由来する通知・永続化は安定時にも消える**(R-4a・§9.6)。とくに OAuth リフレッシュ失敗時の「再認証してください」通知と認証種別の書き換えが、URL 不一致の操作では出なくなる。
- 述語が例外を投げた場合は `captureConnectionIf` を貫通する。契約違反(§8.1)であり、握りつぶさない。呼び出し元の述語はいずれも文字列比較のみ。

## 14. タイムアウトとリトライ

- `captureConnectionIf` の再試行回数は現行の `MAX_CAPTURE_ATTEMPTS` を据え置く。**述語による拒否は再試行を消費しうる**(更新とレースした読みの場合)。全試行が不安定だった場合は現行と同じく `UnstableConnectionException`。
- 述語による確定的な拒否は再試行しない(結果が変わらないため)。
- **拒否枝**(述語が偽で確定)は世代読みと文字列比較のみで I/O を含まないため、**新規のタイムアウト設定は不要**。
- **受理枝**は `tokenManager.getToken()` を呼び、期限切れ OAuth トークンでは**同期の refresh HTTP が走りうる**(§1・§15)。この枝の待ち時間・タイムアウト・例外・キャンセルの方針は **既存の OAuth リフレッシュ実装の挙動をそのまま継承**し、本設計では変更しない(§3 の対象外)。すなわち本設計は受理枝の待ち時間特性を改善も悪化もさせない。
  - **未確定事項**: 既存の OAuth リフレッシュ経路に明示的なタイムアウトが設定されているか、無期限待ちになりうるかは本設計では確認していない。もし無期限待ちであれば、それは本設計以前から存在する性質であり、別途扱うべき問題である。

## 15. 冪等性

`captureConnectionIf` は読み取りのみで状態を変えない。ただし述語を通過した場合は `tokenManager.getToken()` を呼ぶため、**現行の `captureConnection` と同じ**副作用可能性を持つ(期限切れトークンのリフレッシュ)。これは変更前と同じであり、本設計が減らすのは「述語が拒否した場合」の副作用のみ。

## 16. 並行処理と競合

- 設定更新との競合は seqlock で扱う。**述語の評価も、拒否の確定も、seqlock の内側にある**(§9.3)。したがって「どの安定世代にも存在しなかった中間状態で判定する」ことは起きない。
- **ただし、設定更新と競合した場合の結果が現行と一致するとは限らない。** 判定点が資格情報読み取りより前へ移るため、競合窓の扱いが変わる。許容する差の完全な一覧は §9.5 にある。競合していない場合は現行と一致する(R-4)。
- 述語は複数回呼ばれうるため純粋でなければならない(§8.1)。
- 二重処理・データ不整合は発生しない。本変更は書き込みを行わず、既存の `InFlightWriteGuard` にも触れない。
- `pinnedConnectionFor` および 2 つのヘルパは複数のバックグラウンドコルーチンから並行に呼ばれうるが、追加の共有状態を持たないため新たな同期は不要。

## 17. 認証と認可

- **判定基準を変更しない。** URL 一致後は従来どおり `authFingerprint` で照合する(`pinnedConnectionFor`)。
- 機密性の不変条件(別インスタンスへ資格情報が渡らない)は変更前も破れていなかった(issue #49 の「限定条件」)。本設計はこれを維持したまま、**URL 不一致時に資格情報へ触れる回数をゼロにする**。
- 述語はトークンを受け取らない。URL のみを受け取る。ログにも出さない。

## 18. ログ・監視・監査

- ゲート不成立時に呼び出し元が出す監査行は**従来どおり出る**。監査の網羅性(不成立が必ず 1 行残ること)は落ちない。
- **ただし競合時は監査の「理由」が変わりうる。** `runCiLint` / `runCreatePipeline` は `InstanceMismatch` と `ConnectionUnstable` を別の結果として監査に記録するため、§9.5 の差 2 が起きると理由が入れ替わる。**障害調査時に「同じ事象でも版によって理由が異なりうる」ことを前提にする必要がある。** `pinnedConnectionFor` 経路は両者とも `null` に畳まれるため理由の差は出ない。
- 捕捉側に新しいログを追加しない。追加すると、インスタンスを切り替えて古いノードを操作する正常系で恒常的にログが出るため。
- **未確定事項**: 「不成立が URL 述語で起きたか fingerprint で起きたか」を運用上区別する必要があるか。現時点で必要という根拠がないため区別しない。

## 19. 障害時の復旧方法 / 既存機能への影響 / 移行 / ロールバック

- **復旧**: 永続状態を作らないため復旧手順は不要。
- **既存機能への影響**: §2.1 の 6 経路。**設定更新と競合しない場合、判定結果・監査理由とも不変**(R-4)。競合時に許容する差は §9.5 に列挙。設定・データの移行なし。
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
- **§9.3 の性質**: 述語が拒否したときの読みが更新とレースしていた場合(1 回目の `readGeneration` の後で世代が進む)、`null` を返さずリトライすること。リトライ後に述語が受理すればスナップショットを返すこと。**これが R-3(更新途中の中間状態で判定しないこと)を守っている箇所であり、最も落としやすい**
- **§9.4 で選んだ意味論の固定**: 拒否を確定した**直後**に世代が進む場合でも `null` を返すこと(リトライしないこと)。これは「現行なら成立しえた」ケースであり、**受容した挙動差を明示的にテストで固定する**。このテストが無いと、後から「バグではないか」と再修正されうる
- 全試行が不安定 → `UnstableConnectionException`(既存の回帰)
- `captureConnection()` が `captureConnectionIf { true }` と同一の結果になること

この計測(`verify(exactly = 0) { tokenManager.getToken() }`)はリポジトリに既存の慣行である(`src/test/kotlin/com/gitlab/eclipse/security/SecurityScanLauncherTest.kt:313`、`src/test/kotlin/com/gitlab/eclipse/api/GitLabApiClientReadPinningTest.kt:80` ほか)。新しい手法を導入しない。`readGeneration` は既にコンストラクタ注入の seam である(`api/GitLabApiClient.kt:59`)ため、世代を制御する手段は既にある。

**(b) `WriteActionTest`(`captureConnectionIf` をモックする層)**

この層で固定できるのは **結果の分類のみ**である。`captureConnectionIf` がモックされているため、資格情報が読まれたか否かはここでは観測できない。

- 述語が拒否 → `null`
- 受理・fingerprint 不一致 → `null`(結果分類のみ)
- 受理・fingerprint 一致 → スナップショット
- `pinnedConnectionFor` が `captureConnectionIf` に渡す述語が、末尾スラッシュの差を吸収すること(R-5)

**(b2) `GitLabApiClient` 実体 + モックした `tokenManager` を通した `pinnedConnectionFor`(スコープ境界の固定)**

§3 の「アカウント側は対象外であり、副作用は依然として発生しうる」は **(b) の層では固定できない**。モックが同じスナップショットを返す限り、将来「捕捉側でアカウントを早期拒否する」変更や「トークン読み取りを省略する」変更が入っても (b) は素通りするため、範囲を誤解させる受け入れ条件になる。したがって実体を通す層に置く。

- URL 一致・fingerprint 不一致を `pinnedConnectionFor` まで通し、**`verify(exactly = 1) { tokenManager.getToken() }` と最終結果 `null` を同時に**固定する(RISK-3)
- 対になるケースとして、URL 不一致では `verify(exactly = 0) { tokenManager.getToken() }` と `null`(AC-1 の経路端点からの確認)

**(c) `runCiLint` / `runCreatePipeline`**

- `capture` が `null` → `InstanceMismatch`(経路 5・6 の R-6)
- **`capture` が「コンテキストと異なる URL を持つ非 null スナップショット」を返した場合でも `InstanceMismatch` になり、`lint` / `create` が呼ばれないこと**(§8.3 の postcondition・R-3a)。呼び出し元の述語の束縛漏れを模したケースであり、**このテストが安全境界そのものを守る**
- 既存の `InstanceMismatch` テストを新しい形に移行し、**判定が捕捉側へ移ったあとも同じ結果になる**ことを固定する

### 20.3 変異テスト

追加した各テストについて production 側に変異を当て、実際に RED になること、および**変異がコンパイルされている**ことを確認する。最低限:

- 述語判定を `tokenManager.getToken()` の後ろへ移動(= 現行の欠陥の再現)
- §9.3 の世代再確認(`if (readGeneration() == g1)`)を削除
- `runCiLint` / `runCreatePipeline` の postcondition `sameConfiguredInstance` を削除(= §8.3 の安全境界の除去)
- `sameConfiguredInstance` を素の `==` に置換(正規化の除去)

### 20.4 検証できないこと

実機 Eclipse での OAuth リフレッシュ挙動(期限切れトークンを持つ状態で古いノードを操作する)は headless では再現できない。手動検証手順を実装 PR に記載する。

## 21. 受け入れ条件

| ID | 条件 | 検証方法 |
|---|---|---|
| AC-1 | URL 述語が拒否したとき `tokenManager.getToken()` が呼ばれない | 単体テスト(20.2 a) |
| AC-2 | 述語の拒否が確定値に対してのみ行われ、更新とレースした読みではリトライする | 単体テスト(20.2 a) |
| AC-2a | 拒否確定の直後に世代が進む場合は `null` のまま(§9.4 で受容した挙動差の固定) | 単体テスト(20.2 a) |
| AC-3 | 6 経路すべてで、ゲートの判定が §9.4 の意味で線形化可能であり、**成立側に緩まない**(R-3 / R-3a) | 既存テストの回帰 + 20.2 b/c |
| AC-3a | `runCiLint` / `runCreatePipeline` が、URL の異なる非 null スナップショットに対して `lint` / `create` を呼ばない | 単体テスト(20.2 c) |
| AC-3b | 競合時に生じる差が §9.5 の一覧(差 1〜3)に収まること。**「URL が不一致のまま成立する」判定が存在しない**こと(R-3a、per-attempt な性質として) | 単体テスト(20.2 a)+ §9.5 の論証 |
| AC-3c | URL 一致・fingerprint 不一致で `tokenManager.getToken()` が**実際に呼ばれる**(対象範囲の固定・RISK-3) | 単体テスト(20.2 b2) |
| AC-3d | URL が安定して不一致のとき、`getToken()` を呼ばないことにより §9.6 の副作用群が起きないこと | AC-1 の `verify(exactly = 0) { tokenManager.getToken() }` が包含 |
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
| RISK-3 | 「副作用ゼロ」の主張が URL 側のみであることが誤解され、アカウント側も解決したと思われる | 中 | §3 で対象外を明記し、**20.2(b2)** に「URL 一致・fingerprint 不一致では `getToken` が実際に呼ばれる」テストを**実体を通す層**で置いて範囲を固定(モック層では固定できない) |
| RISK-4 | 経路 5・6(`runCiLint` / `runCreatePipeline`)が見落とされ、同型の欠陥が残る | 中 | §2.1 に 6 経路すべてを明記。R-6 と AC-3 で固定。**issue #49 本文の「CI の全書き込み経路」という記述は過小である** |
| RISK-5 | `captureConnection()` のラッパ化で既存 3 箇所の意味が変わる | 中 | AC-4 として固定。`captureConnectionIf { true }` は `null` 枝に入らないことを §9.1 で論証 |
| RISK-6 | `api` から `ci` の正規化関数を直接呼ぶ実装にされ、レイヤが逆転する | 低 | §6 と §8.1 に制約として明記。述語方式がこの制約から導かれることを説明済み |
| RISK-7 | 実装時に「述語で保証済みだから」と `runCiLint` / `runCreatePipeline` の postcondition を削除され、呼び出し元の述語誤りが別インスタンスへの送信に直結する | **高** | §8.3 に安全境界として理由つきで明記。AC-3a で固定し、20.3 で当該行の変異を必須にする |
| RISK-8 | §9.4 で受容した挙動差(拒否確定直後の更新)が後から「バグ」として再修正され、資格情報読み取りが復活する | 中 | 受容した意味論を AC-2a としてテストで固定し、§9.4 に「engineering で消せる差ではない」理由を残す |

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

### round 2(Codex、2026-08-11)— 反映済み

| 指摘 | 判定 | 反映 |
|---|---|---|
| **P1-A**(§8.3): ヘルパ内の `sameConfiguredInstance` を削除すると、`capture` seam の束縛が外部にあるため、呼び出し元の述語誤りで別インスタンスへ lint/create を送りうる。`ConnectionSnapshot?` には「どの述語で検証済みか」が符号化されていない | **正しい**。`pinnedConnectionFor` は述語を関数内で束縛するので構造的に安全だが、2 つのヘルパは seam を外部から受け取るという非対称を見落としていた | postcondition を**残す**方針に変更(§8.3)。役割が「資格情報に触れない早期判定」と「別インスタンスへ送らない最終判定」で異なることを明記。R-3a・AC-3a・RISK-7 を追加 |
| **P1-B**(§9.3): 拒否側の世代読みは「その読みより前に始まった更新」しか検出できない。拒否確定の直後に更新が始まる場合、現行はまだ `getToken()` 実行中で `g1 != g2` を検出しリトライし成立しうる。「完全に一致」は成立しない | **正しい**。round 2 で追加した拒否側の世代読みが、現行のトークン読みより早い線形化点を作っている | R-3 を「**ある安定世代へ線形化可能**」に改めた(§5)。§9.4 を全面的に書き直し、**これは engineering で消せる差ではなく目的そのものからの帰結**(不一致時に資格情報を読まないことと、資格情報読み取り後に決定する実装のタイミング依存挙動の保存は両立しない)であることを論証。競合時の帰結を受容として明記し、AC-2a でテスト固定。方向の非対称性(拒否側にしか倒れない)を R-3a として明示 |

### round 3(Codex、2026-08-11)— 反映済み

| 指摘 | 判定 | 反映 |
|---|---|---|
| **P1**(§5 R-4): 「不成立時の戻り値・監査ログが現状と同一」は §9.4 で受容した競合結果と両立しない。拒否確定直後に更新が始まり現行側が試行を使い切ると、現行は `UnstableConnectionException`、本設計は `InstanceMismatch` となり監査理由が変わる。§16「判定結果を変える競合は残らない」・§19「判定結果は不変」も同時には満たせない | **正しい**。§9.4 を書き換えた際に、R-4・§16・§19 を追随させておらず**内部矛盾を残していた** | R-4 を「競合しない場合」に限定(§5)。**§9.5 を新設し、競合時に許容する差を 2 件に限定して完全列挙**。§16・§18・§19 の表現を修正。AC-3b を追加 |
| **P2-A**(§20.2(b)): fingerprint 不一致テストは `captureConnectionIf` をモックするため結果分類しか固定できず、「アカウント側は対象外で副作用が残る」ことも `getToken()` 呼び出しも固定できない。将来の変更で素通りする | **正しい**。このテストで範囲を固定できると書いたのは誤り | (b) の記述を「結果分類のみ」に限定し、**(b2) として `GitLabApiClient` 実体を通す層**を新設。`verify(exactly = 1) { tokenManager.getToken() }` と `null` を同時に固定。AC-3c 追加、RISK-3 の緩和先を差し替え |
| **P2-B**(§14): 「I/O を伴わないためタイムアウト対象外」は拒否枝でのみ成立する。受理枝は `getToken()` が同期 refresh HTTP を実行しうることを §1・§15 自身が認めている | **正しい** | §14 を拒否枝/受理枝に分割。受理枝は既存 OAuth リフレッシュの方針を継承し本設計では変更しないと明記。既存経路にタイムアウトがあるかは**未確認**として未確定事項に追加 |

### round 4(Codex、2026-08-11)— 反映済み

| 指摘 | 判定 | 反映 |
|---|---|---|
| **P1**(§5 R-4): URL が安定して不一致かつトークン期限切れの場合、現行は `getToken()` 経由で再認証通知(`OAuthTokenProvider.kt:77-80`)と認証種別の書き換えを行うが、本設計はその呼び出しを省くため、**競合が無くても**通知と設定状態が同一にならない。R-2 と R-4 を同時に満たせない | **正しい**。実コードで確認。しかもこれは**本設計で最もユーザーから見える変化**でありながら、設計書に一言も書いていなかった | R-4 を「呼び出し元が生成するもの」に限定し、**R-4a** を新設。**§9.6 を新設**して意図的に消える副作用を表で列挙し、ユーザーへの帰結(期限切れをこの操作では知らされない/ただし次の正規操作と定期リフレッシュで解消する)まで明記。§13・§18 を修正。AC-3d を追加 |
| **P2**(§9.5): 「逆方向は存在しない」の論証が不十分。各試行の成立条件が部分集合でも、**両者は有界な試行枠を異なる時刻に消費する**ため全体の結果は部分集合にならない。本設計は拒否を速く判定する分だけ枠を温存でき、現行が枯渇する状況で成立しうる | **正しい**。per-attempt な性質と、アルゴリズム全体の性質を混同していた | §9.5 に**差 3** を追加(現行 `UnstableConnectionException` / 本設計 成立、安全側)。R-3a を「**URL が不一致のまま成立することは無い**」という per-attempt な性質として読むことを明記し、「全体の結果が部分集合」という強い読み方をしないと宣言。AC-3b を差し替え |

**round 5 で新たに導入し、まだレビューを受けていない論点**:

- **§9.6(安定時にも意図的に消える副作用)**。列挙に漏れが無いか。「情報は失われず、通知経路が変わるだけ」という整理(次の正規操作と `startTokenRefreshTimer` で解消する)が妥当か。ユーザーが期限切れに気づくのが遅れることを受容してよいか。
- **R-3a を per-attempt な性質として読む**という宣言(§9.5 末尾)。これが安全性の議論として十分か。
- **§9.5 の差が 3 件で尽きているか**。試行枠の消費順序に起因する差が他にも無いか。

**まだ明示的な評価を受けていない論点**:

- R-3 の線形化可能性による再定義(§5・§9.4)が実装者にとって検証可能か。
- postcondition を `pinnedConnectionFor` 側にも残した判断(§8.3 末尾)。冗長と見るべきか。
