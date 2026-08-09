# Secret redaction mechanism — design

対象 issue: #54(`data class` の生成 `toString()` が資格情報を載せている件)
ベースブランチ: `develop` @ `0ea03e6`
関連実装 PR: なし(本設計のレビュー完了後に作成)

---

## §1 背景と目的

Phase 6 優先 1(webview 面の開放・PR #55)のブランチ全体レビュー中に、同種の欠陥を
そのブランチ内で「クラスとして閉じる」作業の副産物として、リポジトリ全体に同じ欠陥が
残っていることが判明した。当該 PR のスコープを広げないため #54 として切り出した。

**欠陥の形**: Kotlin の `data class` は `toString()` を自動生成し、全成分を文字列形に出力する。
秘匿すべき成分(資格情報・ユーザーのファイルパス・例外メッセージ)を持つ `data class` が
`toString()` を上書きしていない場合、その値が文字列形に載る。

**現時点ではいずれも live ではない。** 対象クラスを丸ごと補間しているログ行は存在しない
(§4 で実測)。ただしこれは規律であって機構ではない。1 箇所の `logger.debug("$token")` で live になる。
`GitLabAuthorizationToken` は、Phase 4 PR-4 の Codex P1-1 で実際に起きたインシデント
(例外添付経由で `Bearer <token>` が Error Log に漏れた)とまったく同じ形をしている。

**目的**: 秘匿値が生成 `toString()` に載らないことを、**規律ではなく機構で**担保する。
機構の要件は「今ある違反を閉じること」ではなく「**次の違反を止めること**」である。

## §2 対象範囲

1. 秘匿成分を持つ `data class` の `toString()` 上書き(§6 の 12 クラス)。
2. 上記の性質をリポジトリ全体に対して固定する規約テスト 1 本(§7・§8)。
3. 各 `toString()` の出力形を固定するクラス単位テスト 12 本(§9)。

## §3 対象外

以下は本設計では扱わない。それぞれ理由を付す。

| 対象外 | 理由 |
|---|---|
| 41 件の `data class` すべてへの `toString()` 上書き | #54 自身が「41 個の手書き上書きは対処にならない / 42 件目を止めるものが何も無い」と論じている。内訳の大半は秘匿を何も運ばない |
| フィールド型のラッパー型(`SecretString` 等)への置換 | §16 に検討結果と却下理由 |
| 明示注釈(`@Secret` 相当)の導入 | 本フェーズで印を付ける対象が新規に存在しない(既存の該当 6 件は PR #55 で閉じ済み)。使われない機構を先に建てることになるため。結果として生じる穴は §14 の L-1 |
| 非 `data class` の手書き `toString()` | §14 の L-2 |
| detekt カスタムルール | 閾値設定ファイル(`detekt.yml`)の変更を伴い、ロードマップ #8 の共通制約「ビルドシステムの変更禁止」と調整が要る。規約テストは同じ担保をビルド設定に触れずに与える |

## §4 現在の課題

### §4.1 実測した現況

`src/main/kotlin` 配下、`data class` 158 件。既存の `toString()` 上書きは 8 件
(`WebviewInfo` / `ProxyConfig` / `PluginMessageRoute` / `Outcome.Show` / `WebviewResolution.Resolved` /
`WebviewResolution.Failed` / `WebviewEditorKey` / `ActiveYamlEditorUri.Resolved`)。
このうち 6 件は PR #55 で「クラスとして閉じる」作業として追加されたもの。

### §4.2 live ではないことの確認

対象クラスを丸ごと補間しているログ行を掃いた結果、production に該当なし。
`GitLabLanguageServerProcessProvider.kt:179` の `$result` は lsp4j の `InitializeResult`、
`:332` の `$key` は環境変数名の文字列で、いずれも本設計の対象型ではない。

**ただし「補間されていない」は強制されていない性質である。** 実際に `data class` を丸ごと補間している
ログは他に実在し(`CiLintLaunch.kt:123` / `DisplayJobLogHandler.kt:168` の `$key`)、
そのうち `JobLogKey` は資格情報を**意図的にハッシュ化**することで「補間しても安全」を
個別の判断で担保している。判断が要る状態そのものが課題である。

### §4.3 #54 の記述に対する訂正・補足

本設計の作成過程で実コードを掃いた結果、#54 の記述に対して以下が判明した。

1. **#54 が挙げた資格情報 4 件のほかに、`EgressConfigSnapshot`(CA 証明書・クライアント証明書・
   クライアント鍵の**ユーザーファイルパス** 3 成分)と `HttpAgentOptions`(`cert` / `certKey`)がある。**
2. **`HttpAgentOptions` は `GitLabLanguageServerConfigurationParams` の入れ子である。**
   外側に `toString()` を書いて `token` を伏せても、`httpAgentOptions` を印字すれば
   入れ子の生成 `toString()` 経由で `certKey` が出る。「外側だけ直す」が効かない実例。
3. `data class` 総数は 158 件(#54 の記載は 149 件。PR #55 のマージで増えている)。

## §5 要件

- **R1**: 秘匿成分を持つ `data class` の `toString()` 出力に、その成分の値が現れないこと。
- **R2**: 新たに秘匿成分を持つ `data class` が追加されたとき、`toString()` を上書きしない限り
  ビルドが失敗すること(= 42 件目を止める)。
- **R3**: 検出器が壊れた/対象を見落とした場合に、それが「違反ゼロ」として観測されないこと。
- **R4**: 各 `toString()` が何を出力するかが固定されていること(R1 を満たすだけの空文字列等に
  退化していないこと)。
- **R5**: 障害調査に必要な情報(例外の型、接続先インスタンス、認証フィンガープリント等)が
  `toString()` から失われないこと。

## §5.1 前提条件と制約

**前提条件**

- `kotlin-reflect` 2.2.21 が `build.gradle.kts:137` で `implementation` として既に導入されており、
  テスト実行時のクラスパスに載る。**新規依存を追加しない前提はこれに依存している。**
- テストは Kotest(`kotest-runner-junit5` 6.1.2)+ JUnit Platform で走る(`build.gradle.kts:50-52`)。
- Gradle の `test` タスクは `compileKotlin` に依存するため、規約テスト実行時に
  プラグイン本体のコンパイル出力が存在する。

**制約**(ロードマップ #8 の全フェーズ共通制約)

- ディレクトリ構成を変更しない。既存 `src/main/kotlin/com/gitlab/eclipse/*` /
  `src/test/kotlin/com/gitlab/eclipse/*` の中に追加する。
- ビルドシステムを変更しない。`build.gradle.kts` / `detekt.yml` の差分ゼロ。新規 OSGi 依存ゼロ。
- `plugin.xml` に触れない(本設計は拡張点を追加しない)。
- ドキュメントをコミット対象に含めない。**本設計書はレビュー専用ブランチ
  `docs/review-secret-redaction` にのみ存在し、`develop` にマージしない。**
- detekt に `--auto-correct` を使わない。閾値設定ファイルを変更しない。

## §6 対象クラス

検出器(§7)が現在拾う `data class` は 15 件。うち修正が要るのは 12 件。

### §6.1 D1(名前による検出)= 8 件

| # | FQCN | 契機フィールド | 修正 |
|---|---|---|---|
| 1 | `com.gitlab.eclipse.authentication.GitLabAuthorizationToken` | `accessToken`, `refreshToken`, `tokenExpirationTimestamp` | 要 |
| 2 | `com.gitlab.eclipse.api.ConnectionSnapshot` | `token` | 要 |
| 3 | `com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams` | `token`, `ignoreCertificateErrors` | 要 |
| 4 | `…GitLabLanguageServerConfigurationParams$HttpAgentOptions` | `cert`, `certKey` | 要 |
| 5 | `com.gitlab.eclipse.preferences.healthcheck.ConfigurationValidationRequest` | `token` | 要 |
| 6 | `com.gitlab.eclipse.api.http.EgressConfigSnapshot` | `caCertificatePath`, `clientCertificatePath`, `clientCertificateKeyPath`, `ignoreCertificateErrors` | 要 |
| 7 | `com.gitlab.eclipse.lsp.proxy.ProxyConfig` | `password` | 不要(既に安全・先例) |
| 8 | `…GitLabLanguageServerConfigurationParams$CodeCompletion` | `enableSecretRedaction` | 不要(§7.3 により assert を生まない) |

### §6.2 D2(型による検出)= 7 件

| # | FQCN | 契機フィールド | 修正 |
|---|---|---|---|
| 9 | `com.gitlab.eclipse.mergerequests.BranchPushService$PushOutcome$Failed` | `cause` | 要 |
| 10 | `com.gitlab.eclipse.mergerequests.MrBranchCheckoutService$CheckoutResult$Failed` | `cause` | 要 |
| 11 | `com.gitlab.eclipse.mergerequests.discussions.DiscussionsLoader$FetchOutcome$Failed` | `cause` | 要 |
| 12 | `com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteOutcome$Definite` | `cause` | 要 |
| 13 | `com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteOutcome$Ambiguous` | `cause` | 要 |
| 14 | `com.gitlab.eclipse.mergerequests.discussions.LoadOutcome$Failed` | `cause` | 要 |
| 15 | `com.gitlab.eclipse.lsp.webview.WebviewUriResolver$WebviewResolution$Failed` | `cause` | 不要(既に安全・先例) |

> §6.1 / §6.2 の FQCN と契機フィールドはソース走査で得た**見込み値**である。ソース走査は
> 1 行で書かれた主コンストラクタを取りこぼす(実際 `HttpAgentOptions` は行頭 `val` の走査から漏れた)。
> **確定は §8 の A5 が実行時に行う。** 実装時に初回実行の出力で本表を更新する(§17 の U1)。

## §7 検出規則

### §7.1 走査対象

`KClass.isData == true` の Kotlin クラスのみ。

**非 `data class` を対象にしない理由は 2 つある。**

1. 本設計が閉じる欠陥は「**生成された** `toString()`」である。非 `data class` の既定 `toString()` は
   `Any` 由来の同一性ベースで、成分の値を出力し得ない。
2. 名前パターンは非 `data class` のフィールドにも当たる
   (`OAuthTokenProvider.currentToken` / `GitLabApiClient.tokenManager` /
   `SecurityScanLauncher.tokenProviderManager` / `SidebarRefreshCoordinator.assignedKey` /
   `OAuthSecretStorage.secretStorage` 等)。これらはサービス実体であり、§7.4 の組み立てが
   成立しない。A6(組み立て不能は失敗)と組み合わせると、対象に含めた時点でビルドが落ちる。

### §7.2 秘匿フィールドの判定

**クラスにではなくフィールドに印を付ける。** あるフィールドが秘匿を運ぶとは:

- **D1**: フィールド名が次のいずれかを大小無視の部分一致で含む —
  `token` / `secret` / `password` / `credential` / `passphrase` / `key` / `cert`
- **D2**: フィールドの型が `Throwable` に代入可能

D1 に**型の制限を課さない**。制限の代わりに §7.3 の性質を使う。

### §7.3 sentinel を運べないフィールドは assert を生まない

sentinel は「秘匿フィールドのうち sentinel を運べるもの」にのみ植える。

| フィールド型 | 植える値 |
|---|---|
| `String` / `CharSequence` | そのフィールド固有の sentinel 文字列 |
| `Throwable` に代入可能 | `RuntimeException(そのフィールド固有の sentinel)` |
| 上記以外(`Boolean` / 数値 / `Instant` 等) | 植えない。**このフィールドは assert を生まない** |

この規則により `enableSecretRedaction: Boolean`(単なるフラグ)と
`tokenExpirationTimestamp: Instant`(有効期限であってトークンではない)と
`ignoreCertificateErrors: Boolean` は、検出はされるが何も要求しない。

**型の除外リストを書かずに偽陽性が消えることが、この規則を選ぶ理由である。**
除外リストは drift する。書かずに済むなら書かない。

**sentinel は秘匿フィールドにのみ植える。** クラス単位で全 `String` フィールドに植えると、
`CodeCompletion.disabledSupportedLanguages: List<String>` のような正当に出力されるべき
フィールドまで「出てはいけない」ことになる。

### §7.4 インスタンスの組み立て

`kotlin-reflect`(`build.gradle.kts:137` で `implementation` として既に導入済み)の
`KFunction.callBy` を用いる。**デフォルト値を持つ引数は写像に載せず、コンパイラのデフォルトに委ねる。**
これにより `GitLabAuthorizationToken.tokenExpirationTimestamp`(式デフォルト)や
`HttpAgentOptions` の省略可能引数について、テスト側が `Instant` 等の値を発明せずに済む。

残る引数は型から合成する:

| 引数 | 値 |
|---|---|
| 秘匿フィールド(§7.3 で運べるもの) | sentinel |
| nullable(かつ非秘匿) | `null` |
| 非 null `String` | 中立文字列(どの sentinel とも一致しない固定値) |
| 数値型 | `0` |
| `Boolean` | `false` |
| `List` / `Set` / `Map` | 空 |
| enum | 先頭の定数 |
| 上記に当てはまらない非 null 参照型 | **合成不能 → 失敗**(§8 の A6) |

### §7.5 合成(入れ子)の扱い

**入れ子を再帰的に組み立てない。各クラスを単独で検証する。**

根拠となる不変条件: **秘匿フィールドは必ずいずれかの被検出クラスに属する**(§7.2 の判定が
フィールド単位であるため、定義により成立する)。外側のクラスが入れ子を印字しても、
その入れ子自身が単独で安全なら、外側の出力も安全である。

この合成は今日すでに実在する。`EgressConfigSnapshot` は `ProxyConfig` を保持しており、
`ProxyConfig` は自前で安全なので、`EgressConfigSnapshot` の生成 `toString()` は
プロキシのパスワードを漏らしていない。

## §8 検出器の不変条件(偽合格の遮断)

前フェーズ(PR #55)で実地に踏んだ検証ツールの偽合格機構 5 件は、すべて
「**信号の不在から『何も捕まえなかった』を推論する**」という同一の形をしていた。
本検出器は同じ形の穴を作りやすい構造なので、「違反ゼロ」に化ける経路を明示的に塞ぐ。

条件そのものは §22 に置く(同じ事実を 2 箇所に書かない)。ここには**各条件が塞いでいる偽合格の機序**を記す。

| 条件 | 塞ぐ偽合格の機序 |
|---|---|
| A3 | コード出力ディレクトリの解決が壊れると、走査は 0 クラスを見て「違反なし」と報告する |
| A4 | `Class.getDeclaredFields()` は `LinkageError` を投げ得る。捕まえて読み飛ばすと、その中に秘匿クラスがあっても静かに通る |
| A5 | 検出器のパターンを壊す変異(例: `key` を落とす)で、全緑のまま `EgressConfigSnapshot` の検査が消える |
| A6 | 「作れなかったから検査していない」が緑になる |

**A5 の期待リストは検出器の出力から作らない。** §6 の 15 個の FQCN と契機フィールド名を
テストコードに直接書く。前フェーズで 2 度確認された一般則
「**テスト対象そのものから期待値を組み立てると、最も起きやすい変異を素通しする**」への直接の対処である。

A5 を**両方向**にする理由: 増えた側が R2(42 件目を止める)の本体、減った側が R3(検出器が壊れた)を捕まえる。

sentinel は**フィールドごとに一意**にする。どのフィールドが漏れたかが失敗メッセージから直接わかるため。

## §9 修正の形

### §9.1 D1 系(6 件)

秘匿成分を伏せ、診断に有用な非秘匿成分を残す。既存の `ProxyConfig.kt:14-16` の形
(`username=${username?.let { "***" }}`)に合わせる。

R5 により残す非秘匿成分の例: `instanceUrl` / `authFingerprint` / `configGeneration` /
`baseUrl` / `logLevel`。

**上書きは「生成形の再現」を目指さない。** 伏せる成分と、診断に有用な成分を明示的に選ぶ。

理由: `GitLabLanguageServerConfigurationParams` は成分を 12 個持つ。生成形をすべて手書きで
再現すると、**成分が 1 つ増えたときに `toString()` だけが黙って古いままになる**。これは
前フェーズで最頻だった欠陥クラス「コードが果たさない約束をするコメント」の `toString()` 版である
(「このオブジェクトの内容を表す」という約束を、実際には果たさない)。
成分を明示的に選んだ形は、その約束を最初からしない。

この選択の帰結は L-4(§14.3)。

`HttpAgentOptions` は `ca` も伏せる。`ca` は名前パターンに掛からないが CA 証明書のパスまたは内容であり、
§14 の秘匿対象である。**パターンは検出器であって、修正の範囲を決めるものではない。**

### §9.2 D2 系(6 件)

`Failed(type=java.io.IOException)` の形。既存の `WebviewUriResolver.kt:35` に一致させる。

**message を落とし、型を残す。** message を落とすのは、`Throwable.toString()` が
クラス名**と** message を出し、message に URI・ファイルパスが入るため。
型を残すのは R5(型を落とすと障害調査が成立しない)。

`cause` が null を取り得るクラス(`PushOutcome.Failed` / `CheckoutResult.Failed`)は
`type=null` を出す(`WebviewUriResolver` の既存形と同じ)。

## §10 システム構成とコンポーネントの責務

構成は 2 段。**production 側は各 `data class` の `toString()` 上書きのみで、新しい型・層・
サービスを一切導入しない。** 機構はすべてテスト側にあり、production の振る舞いには現れない。

```
production 側          テスト側
─────────────          ─────────────────────────────────────────
data class の          規約テスト 1 本  ── コンパイル出力を走査し
toString() 上書き ←──                     秘匿フィールドを持つ data class を
(12 クラス)                              自力で見つけて検証する(R1/R2/R3)
       ↑
       └────────────  クラス単位テスト 12 本 ── 出力形を固定する(R4/R5)
```

| コンポーネント | 責務 | 依存 |
|---|---|---|
| 各 `data class` の `toString()` 上書き | 自クラスの文字列形から秘匿成分を除く | なし |
| 規約テスト(`SecretRedactionConventionTest`) | リポジトリ全体に対して R1・R2・R3 を固定する | `kotlin-reflect`、コンパイル出力ディレクトリ |
| クラス単位テスト 12 本 | 各 `toString()` の**出力形**を固定する(R4) | なし |

**規約テスト 1 本では足りない。** 規約テストの assert は「sentinel が出力に現れない」であり、
`toString()` が空文字列を返す変異はこれを通る。「簡潔にしよう」は現実に起きる編集で、
前フェーズでも文言を短くする方向の変異が受け入れ条件を片方向で素通しした実例がある。

- 規約テスト = 性質をリポジトリ全体に対して固定する(R2 を担うのはこれ)
- クラス単位テスト = 各 `toString()` が**何と言うか**を固定する(R4 を担うのはこれ)

## §11 処理フロー(規約テスト)

```
1. ConnectionSnapshot::class.java.protectionDomain.codeSource.location
   → プラグイン本体のコンパイル出力ディレクトリ
   ※ パスをハードコードしない(Gradle のレイアウト変更に影響されない)
2. ディレクトリを再帰走査し .class を列挙
3. 各クラスを Class.forName(name, initialize = false, loader) で読む
   ※ 初期化しない = Eclipse 依存の静的初期化子を headless で走らせない
   ※ LinkageError は捕まえて「検査不能」集合に記録(A4)
4. KClass.isData == true でないものを除外(§7.1)
5. getDeclaredFields() を §7.2 の D1 / D2 で判定 → 秘匿フィールドの写像を得る
6. 走査総数 > 0 を確認(A3)/ 検査不能集合を照合(A4)/
   秘匿クラス集合を手書き期待リストと双方向照合(A5)
7. 秘匿フィールドを持つ各クラスについて §7.4 で組み立て(失敗は fail・A6)
8. toString() を呼び、植えた sentinel が 1 つも現れないことを assert(A1)
```

## §12 API / インターフェース

外部に公開する API・インターフェースの追加変更はない。
`toString()` は `Any` の既存メンバの上書きであり、シグネチャは変わらない。

## §13 データモデル

データモデルの変更はない。`toString()` の上書きは `equals` / `hashCode` /
コンストラクタ / プロパティに触れない。

## §13.1 エラー処理

### production 側

**`toString()` は例外を投げてはならない。** `toString()` はログ出力・デバッガ・例外メッセージから
呼ばれるため、ここで throw すると**本来のエラーを覆い隠す**。

具体的な危険は nullable 成分にある。`PushOutcome.Failed` / `CheckoutResult.Failed` /
`WebviewResolution.Failed` は `cause: Throwable?` を持つので、`cause.javaClass.name` と書くと
`cause` が null のとき NPE になる。`cause?.javaClass?.name` を使う(既存の
`WebviewUriResolver.kt:35` がこの形)。§9.2 が `type=null` の出力を規定しているのはこのため。

### テスト側

規約テストが遭遇し得るエラーは 3 種。**いずれもスキップせず失敗として報告する。**

| エラー | 扱い |
|---|---|
| クラス読み込み時の `LinkageError` | 「検査不能」集合に記録し、pin した集合と照合(A4) |
| §7.4 の組み立て失敗(合成不能な型・`callBy` の失敗) | 失敗(A6)。FQCN と解決できなかった引数の型を報告する |
| `toString()` 呼び出し自体が throw | 失敗。FQCN と例外の型を報告する |

3 種いずれも、**「検査できなかった」を「違反がなかった」と読ませない**ことが要件である(R3)。

## §14 既存機能への影響

### §14.1 シリアライズへの影響 — なし

Gson はフィールドのリフレクションで JSON を構築するため、`toString()` の上書きは
シリアライズ結果に影響しない。したがって以下は**バイト単位で不変**である。

- `OAuthSecretStorage.kt:32` の `gson.toJson(token)`(SecretStorage の永続形式)
- lsp4j 経由で LS に送る `GitLabLanguageServerConfigurationParams` のワイヤ形式

これは §16 で検討したラッパー型案(フィールドの**型**を変える案)とは異なる。
型を変えれば両方が壊れるが、`toString()` の上書きでは壊れない。

### §14.2 既存テストへの影響

対象 12 クラスの `toString()` 出力に依存している既存テストは掃いた範囲で存在しない。

`LanguageServerProxyManagerProxyConfigTest.kt:30`(`config.toString().contains("s3cret") shouldBe false`)は
`ProxyConfig` について規約テストと性質が重複するが、**残す**。
このテストは「プロキシ設定の解決」という別の production 経路の中で見ているものであり、
削るとその経路側の被覆が減る。

### §14.3 受容済みの制限

| ID | 制限 | 根拠 |
|---|---|---|
| L-1 | 名前パターンに掛からない秘匿値は検出できない。#54 が実例として挙げた `WebviewEditorKey.queryParams: Map<String, String>`(名前も型も秘匿性を示唆しないのにユーザーのファイルパスを運んでいた)はこの形。**#54 の「41 は数ではなく下限」という記述は本機構の導入後も有効なまま** | 明示注釈を §3 の理由で導入しないため |
| L-2 | 非 `data class` の手書き `toString()` が漏らす場合は検出できない | §7.1。既定 `toString()` は漏らし得ず、漏らすのは意図的に書かれた場合に限られる |
| L-3 | `toString()` 以外の経路(独自のフォーマッタ、`Gson().toJson()` をログに出す等)は対象外 | 本設計は生成 `toString()` の欠陥クラスに限定している |
| L-4 | 上書きしたクラスに後から成分が追加されても、`toString()` の出力には現れない | §9.1。生成形の再現を目指さない選択の帰結。新しい成分が**秘匿**であれば A5 が落ちるので R1/R2 は守られる。守られないのは「新しい**非秘匿**成分が診断に必要だったのに出ない」場合に限られる |

## §15 秘匿・ログ・監査

本設計は**秘匿の機構そのもの**であるため、設計の適用対象が本設計の産物と重なる。

- 規約テストの失敗メッセージには sentinel(テストが生成した固定文字列)と FQCN・フィールド名のみが載る。
  実際の資格情報は**テスト実行時に存在しない**(すべて合成値)。
- 修正後の `toString()` が出力してよいものは、非秘匿成分と例外の**型名**のみ(§9)。

**前フェーズからの申し送り**: PR #55 の秘匿テストは `shouldNotContain "/"` という **proxy** で
担保されており、区切り文字を含まない漏洩(裸のファイル名・トークン)を捕まえない。
本設計の規約テストは**植えた sentinel そのもの**を探すため、この proxy の弱点を持たない。

## §16 検討したが採らなかった案

### §16.1 ソース走査による lint テスト

`src/main/kotlin` をテキストで走査し、`data class` の主コンストラクタに秘匿名/`Throwable` があれば
`override fun toString(` の存在を要求する案。

**却下理由 3 点**:

1. **`override fun toString()` の存在は性質ではなく性質の proxy。** 上書きは存在するが依然として
   トークンを出す実装で緑になる。
2. **Kotlin の主コンストラクタをテキストで切り出すのは見た目より脆い。**
   実証: `HttpAgentOptions` は 1 行で書かれているため、行頭 `val` を見る走査から漏れた(§6 の注記)。
   `GitLabLanguageServerConfigurationParams` は入れ子 `data class` を 8 個持つ。
3. 入れ子の漏洩(§4.3 の 2)を構造上検出できない。

### §16.2 ラッパー型(`SecretString` 等)への移行

秘匿成分の**型**を、`toString()` が固定文字列を返すラッパー型に置き換える案。
構築による安全であり、テストによる強制が不要になる。

**却下理由 3 点**:

1. `GitLabAuthorizationToken` は SecretStorage の永続 JSON 形、
   `GitLabLanguageServerConfigurationParams` は LS のワイヤ形式であり、
   **両方に Gson の `TypeAdapter` が要る**。後者は lsp4j 側の Gson 構成に手を入れる話になる。
2. ロードマップ #8 の共通制約 5「プロトコル定数・LSP/webview メッセージ仕様は実装前に実ソースで確定」に
   対して、ワイヤ形式を変えないという選択のほうが risk が小さい。
3. `Throwable` の message 漏洩(D2・6 件)には何の効果もない。

## §17 未決事項

| ID | 未決事項 | 確定方法 |
|---|---|---|
| U1 | A4 の「検査不能クラス集合」が実際に空になるか。`build.gradle.kts:194` が p2 依存を `compileOnly` と `testImplementation` の両方に入れているため空になる見込みだが、**推測で確定しない** | 実装時に初回実行で実測する。空でなかった場合の方針は「**根拠を付けてリストを pin する。黙って読み飛ばす経路は作らない**」 |
| U2 | §6 の 15 クラス / 契機フィールドの厳密な集合。ソース走査には取りこぼしがある(§6 の注記) | 実装時に初回実行の出力で §6 の表を更新し、A5 の期待リストとする |
| U3 | §7.4 の合成規則が 15 クラスすべてを組み立てられるか。ソース上は `String` / 数値 / `Boolean` / nullable 参照 / `Throwable` / デフォルト値付きのみだが未実測 | 実装時に実測。組み立て不能な型が出た場合は合成規則を拡張する(スキップはしない・A6) |

## §18 想定されるリスク

| リスク | 影響 | 緩和 |
|---|---|---|
| 規約テストが将来の無関係な変更で頻繁に落ちる | 開発の摩擦 | A5 の期待リスト更新が「新しい秘匿クラスを追加した」の明示的な承認になる。これは摩擦ではなく R2 そのもの |
| `Class.forName` の一括走査が headless で `LinkageError` を出す | テストが赤になる | U1 で実測。空でなければ根拠付きで pin(§17) |
| 検出器のパターン変更が静かに被覆を減らす | R3 の違反 | A5 の双方向照合(§8) |
| `toString()` の上書きが診断情報を落とす | 障害調査が困難になる | R5 + §9 のクラス単位テスト 12 本が出力形を固定する |
| 修正が新たな欠陥を作る | — | 前フェーズで検出した 8 件のコメント欠陥のうち 3 件は「前の修正の中で」生まれた。レビュー依頼には「この修正が新たに開いたものは何か」を最重要の問いとして書く |

## §19 該当のない項目

CLAUDE.md の設計書テンプレートのうち、本設計に該当しない項目とその理由。
**推測で内容を埋めない。**

| 項目 | 該当しない理由 |
|---|---|
| トランザクション境界 | 永続化・外部システムへの書き込みを行わない |
| タイムアウトとリトライ | ネットワーク I/O・非同期処理を含まない |
| 冪等性 | 状態を変更する操作がない |
| 並行処理 | `toString()` は既存の不変 `data class` に対する純関数。共有可変状態を導入しない |
| 認証と認可 | 認証情報を**扱う**が、認証・認可の判断は行わない |
| 監視 | 実行時の振る舞いを変えない(§14.1) |
| 障害時の復旧方法 | 実行時の障害モードを追加しない |
| 移行方法 | データ形式・永続形式・ワイヤ形式が不変(§14.1)のため移行不要 |

## §20 ロールバック方法

実装 PR の revert 1 手。`toString()` の上書きと新規テストのみで構成され、
production の制御フロー・データ形式・ワイヤ形式に触れないため、部分的なロールバックは不要。

## §21 テスト方針

| 種別 | 本数 | 固定する性質 |
|---|---|---|
| 規約テスト(`SecretRedactionConventionTest`) | 1 | R1・R2・R3。リポジトリ全体に対する性質 |
| クラス単位の厳密形テスト | 12 | R4・R5。各 `toString()` が何と言うか |

規約テストの配置は `src/test/kotlin/com/gitlab/eclipse/SecretRedactionConventionTest.kt`
(横断的な規約のため既存の `GitLabEclipseStartupTest.kt` と同じくルート直下)。

クラス単位テストは、対象クラスに対応する既存テストがあればそこに追加し、無ければ
**当該クラスと同じパッケージに**新規テストファイルを追加する。**ディレクトリ構成は変更しない。**

クラス単位テストの期待リテラルは **production の定数を経由せずテスト側に直接書く**(§8 A5 と同じ理由)。

先例として `WebviewUriResolverTest.kt:203-207` が、性質(`shouldNotContain`)と
形(`shouldBe "WebviewResolution.Failed(type=java.lang.IllegalStateException)"`)の
両方を固定する形を既に取っている。本設計はこの形を 12 クラスに広げる。

## §22 受け入れ条件

| ID | 条件 |
|---|---|
| A1 | 12 クラスそれぞれについて、秘匿成分に一意の sentinel を植えて `toString()` を呼び、sentinel が出力に現れない |
| A2 | 12 クラスそれぞれについて、`toString()` の出力が期待リテラルと完全一致する。期待リテラルは production の定数を経由せずテスト側に直接書かれている。nullable な秘匿成分を持つクラス(`PushOutcome.Failed` / `CheckoutResult.Failed`)は null の場合も固定する |
| A3 | 規約テストが走査したクラス数 > 0 |
| A4 | 検査不能クラス集合が pin した集合と一致する(U1 で実測して確定) |
| A5 | 秘匿フィールドを持つ `data class` の集合が、手書きの期待リスト(§6 の FQCN + 契機フィールド名)と**両方向で**一致する |
| A6 | 組み立て不能なクラス、または `toString()` の呼び出し自体が throw したクラスが 1 つでもあれば失敗する(§13.1 のテスト側 2 種目・3 種目) |
| A7 | `./gradlew build` が `1846 tests + 新規 / 36 failed / FAILSET_IDENTICAL`(36 件は実 SWT ディスプレイを要する既存テストで headless devcontainer では動かせないベースライン)、`./gradlew detekt --rerun-tasks` が OK |
| A8 | `build.gradle.kts` / `detekt.yml` / `plugin.xml` / `.md` の差分ゼロ。新規 OSGi 依存ゼロ |

### §22.1 受け入れ条件の識別性

各条件は「守れている一例」ではなく性質を検査していなければならない。
以下の変異がそれぞれ対応する条件だけを落とすこと(実装時に変異前後の実出力を提出する)。

| 変異 | 落ちるべき条件 |
|---|---|
| 任意の 1 クラスの `toString()` 上書きを削除 | A1(そのクラス)+ A2(そのクラス) |
| 任意の 1 クラスの `toString()` を `""` にする | A2 のみ(A1 は通る) |
| D1 のパターンから `key` を落とす | A5 |
| D1 のパターンから `cert` を落とす | A5 |
| D2 の判定を削除 | A5 |
| §7.1 の `isData` 判定を削除 | A5(サービス群が集合に入る)+ A6 |
| 走査ディレクトリの解決を壊す | A3 |
| `LinkageError` を捕まえて読み飛ばす | A4 |
| 組み立て失敗を skip にする | A6 |
| sentinel を秘匿フィールドだけでなくクラス内の全 `String` フィールドに植える(§7.3 の後段に反する) | A1(`CodeCompletion.disabledSupportedLanguages` 等が偽陽性で落ちる) |
| §9.2 で例外の型を出力から落とす | A2 |
| `cause?.javaClass?.name` を `cause!!.javaClass.name` にする | A2(null ケース)。§13.1 |
| `toString()` 呼び出しの throw を捕まえて読み飛ばす | A6 |
