# Phase 4 PR-2: パイプライン/ジョブ操作(retry / cancel / play)設計書

- 対象リポジトリ: `sumikof/gitlab-eclipse-plugin`
- ベースブランチ: `develop`(tip `d60f2e8`)
- フェーズ: #12(Pipelines / CI)、PR-2
- 前提 PR: PR-1 パイプライン表示基盤(#34 MERGED)
- パリティ台帳: #7、ロードマップ: #8
- 本書はレビュー専用。実装 PR・マージ先には含めない。

---

## 1. 背景と目的

Phase 4 PR-1 で、サイドバー「For current branch」節に現ブランチ最新パイプラインを
**Pipeline → Stage → Job** ツリーで **read-only** 表示する基盤(`gl.status.pipeline` 相当)が入った。

PR-2 の目的は、この表示済みツリーに対して VSCode 拡張(`gitlab-workflow`)と同等の
**パイプライン/ジョブの書き込み操作**を追加すること。具体的には、パイプラインの retry / cancel、
ジョブの retry / cancel / play(手動ジョブ起動)を、サイドバーの各ノードのコンテキストメニューから
実行できるようにする。

VSCode の該当実装:
- `src/desktop/commands/pipeline_actions.ts`(`retryPipeline` / `cancelPipeline`)
- `src/desktop/commands/job_actions.ts`(`retryJob` / `cancelJob` / `executeJob`)
- REST 実体は `src/desktop/gitlab/gitlab_service.ts`
  - `cancelOrRetryPipeline` = `POST /projects/:id/pipelines/:pipeline_id/{cancel,retry}`
  - `cancelOrRetryJob` = `POST /projects/:id/jobs/:job_id/{cancel,retry,play}`

## 2. 対象範囲

| 操作 | 対象ノード | REST |
|---|---|---|
| Retry Pipeline | `PipelineNode` | `POST /projects/{projectId}/pipelines/{pipelineId}/retry` |
| Cancel Pipeline | `PipelineNode` | `POST /projects/{projectId}/pipelines/{pipelineId}/cancel` |
| Retry Job | `JobNode` | `POST /projects/{projectId}/jobs/{jobId}/retry` |
| Cancel Job | `JobNode` | `POST /projects/{projectId}/jobs/{jobId}/cancel` |
| Play Job | `JobNode` | `POST /projects/{projectId}/jobs/{jobId}/play` |

- いずれも **body なし POST**。成功時に更新後オブジェクトが返るが、本実装は本文を解析せず 2xx を成功と判定する。
- 実行後はサイドバーを refresh して最新状態を再取得・再描画する。
- `retryFailedPipelineJobs`(VSCode の「Retry Last Pipeline」)は REST 上 **pipeline retry と同一**
  (`command_names.ts` の `RETRY_FAILED_PIPELINE_JOBS` → `retryPipeline` ハンドラ)であり、
  別エンドポイントは存在しない。よって本 PR の「Retry Pipeline」がこれを兼ねる。

## 3. 対象外(後続 PR / 意図的除外)

- **Create pipeline**(`POST /projects/:id/pipeline?ref=`): 現ブランチのトラッキングブランチ解決と、
  既存 `PipelineNode` が無い状態(最初のパイプライン生成)に対応する toolbar 等の別 UI 面が必要なため、
  後続 PR に分離する。
- Artifacts / trace のダウンロード・表示(Phase 4 PR-3)。
- CI lint(Phase 4 PR-4)。
- 操作中の busy / optimistic UI(VSCode `withMarkedAsBusy` 相当)。本 PR は手動更新・増分描画モデルに合わせ
  busy 表示を行わない。
- 実行前の確認ダイアログ(VSCode 準拠で確認なし・即実行)。
- 自動ポーリング。

## 4. 現在の課題(PR-1 時点の資産と不足)

- `GitLabApiClient` は **GET 専用**(`sendGet` / `fetchObject` / `fetchListFromApi` /
  `fetchListWithinDeadline`)。POST 経路が無い。
- `CiStatus`(`ci/CiStatus.kt`)は name + priority のみ移植済み。**`contextAction`
  (retryable / cancellable / executable)は PR-1 で意図的に未移植**。操作の可視条件に必要。
- `GitLabPipeline` は `projectId`(REST `project_id`, `Long?`)を保持するが、
  `GitLabJob` は project_id を持たない(VSCode は `job.pipeline.project_id` を使用)。
- サイドバーのコンテキストメニュー(`popup:com.gitlab.eclipse.views.GitLabSidebarView`)は
  現状 `<instanceof>` のみで出し分けており、**ステータス依存**の出し分け機構が無い。

## 5. 要件

### 5.1 機能要件

- FR-1: `PipelineNode` を右クリックしたとき、そのパイプラインのジョブに **retryable なジョブが1つ以上**
  あれば「Retry Pipeline」を、**cancellable なジョブが1つ以上**あれば「Cancel Pipeline」を表示する
  (VSCode `getPipelineItemContextValue` と同一判定)。
- FR-2: `JobNode` を右クリックしたとき、そのジョブの `contextAction` に応じて
  retryable → 「Retry Job」、cancellable → 「Cancel Job」、executable → 「Play Job」を表示する。
- FR-3: メニュー選択で該当 REST POST を発行し、**2xx を成功**として扱い、サイドバーを refresh する。
- FR-4: **成功/失敗の 2 値**で扱う(VSCode パリティ)。2xx = 成功 → refresh。それ以外(非 2xx / タイムアウト /
  IO 例外)= 失敗 → ユーザー通知 + Error Log 出力を行い、サイドバーは変更しない(refresh しない)。
  失敗の細分類(4xx/5xx/timeout 等)は**監査ログ用のメタデータとしてのみ記録**し、UI 挙動は分けない(§15)。
- FR-5: 確認ダイアログは出さない(即実行)。
- FR-6: **同一対象への全 write を直列化する** in-flight ガード(非可視・busy 表示なし)。キーは
  **(instanceUrl, 対象種別[pipeline/job], 対象ID)**(action は含めない)。VSCode の
  `withMarkedAsBusy('pipeline'|'job', id)`(type+id キー)と同じ粒度で、同一パイプライン/ジョブに対する
  retry と cancel(や連打)が応答待ち中に混在するのを防ぐ(到着順次第で「retry 直後に cancel」等の意図しない
  結果を回避)。起動時に登録し **成否によらず `finally` で除去**(POST 完了/タイムアウト=最大 30s まで保持)。
  unknown/HELD のような照合待ちの保持状態は持たない(下記「スコープ判断」)。
- **スコープ判断(timeout の二重発火)**: retry/play は API 契約上冪等でない(§12)が、「POST がサーバ適用後に
  タイムアウトし、応答だけ失われた」ケースをクライアントで exactly-once 保証することは、GitLab API が
  idempotency key を持たない以上できない。**パリティ元の VSCode 拡張もこの保護を実装していない**ため、本 PR は
  VSCode と同等とし、この残存リスクを**受容する制限**として §22 に明記する。in-flight ガード(連打抑止)と、
  失敗時に盲目的な再試行を促さない通知文で緩和するに留める。
- FR-8: **操作の送信先インスタンスをノードに固定する**。サイドバー表示中に `gitlab.url` / 認証先を別インスタンスへ
  変更しても、ノードには表示時点(取得元)のインスタンスの数値 ID しか残らない一方 `GitLabApiClient.buildUri` は
  POST 時点のグローバル設定を読む。両者が食い違うと、旧表示の cancel/retry/play が**新インスタンス上の同一数値 ID を
  持つ無関係な対象へ送られ得る**。よってノードに取得元インスタンス識別(instance URL または設定世代)を保持し、
  POST 直前に現在のグローバル設定と一致しなければ **write を拒否し、refresh を促す**(§8.4・§8.7・§22 R-5)。

### 5.2 非機能要件

- NFR-1: REST POST は UI スレッドをブロックしない(背景コルーチンで実行)。
- NFR-2: トークンをログに出さない(既存方針の継続)。
- NFR-3: `develop` の detekt 0 件を維持。ディレクトリ構成・ビルドシステムは変更しない。
- NFR-4: headless(devcontainer)で検証可能なロジック(REST・ステータス判定・ノード算出)は TDD する。

### 5.3 `contextAction` 対応表(`ci_status_metadata.ts` より確定)

| status | contextAction |
|---|---|
| manual | executable |
| success | retryable |
| created | cancellable |
| waiting_for_resource | cancellable |
| preparing | cancellable |
| pending | cancellable |
| scheduled | cancellable |
| skipped | (なし) |
| canceled | retryable |
| canceling | retryable |
| failed | retryable |
| running | cancellable |
| failed かつ allow_failure=true | retryable |
| 上記以外 / null(unknown) | (なし) |

## 6. 前提条件と制約

- 認証は既存の Bearer トークン(`GitLabTokenProviderManager.getToken()`)を再利用。
- project 識別子は **`pipeline.projectId`(数値 `project_id`)** を使用する。GitLab REST の
  `/projects/:id/...` は数値 id を受理する。ジョブは同一パイプライン=同一プロジェクトに属するため、
  ジョブ操作でも同じ `pipeline.projectId` を用いる(VSCode の `job.pipeline.project_id` と等価)。
  これにより encoded-path の再解決も RepositoryContext の再取得も不要になる。
- ディレクトリ構成変更禁止(`com.gitlab.eclipse.*` 内に追加)。ビルドシステム変更禁止。
- プロトコル定数(エンドポイント・status→action 対応)は本書で実ソース確定済み。

## 7. システム構成

```
[SidebarView context menu]
   │ (visibleWhen: instanceof + PropertyTester)
   ▼
[PipelineActionHandler / JobActionHandler]  ← AbstractHandler、選択ノード取得(UIスレッド)
   │ 背景コルーチン(共有 CoroutineScope)
   ▼
[PipelineActionService / JobActionService]  ← projectId / id を渡す
   │
   ▼
[GitLabApiClient.sendPost]  ← Bearer 認証・buildUri 再利用・非2xx→GitLabApiException
   │ 成功
   ▼
[Display.asyncExec → GitLabSidebarView.refresh()]  ← 最新状態を再取得・再描画
```

## 8. コンポーネントの責務

### 8.1 `GitLabApiClient`(POST 追加)
- 新規 `private fun sendPost(path, query, timeout): HttpResponse<String>`:
  `buildUri` / `Authorization: Bearer` / `Accept: application/json` / `timeout` を GET と共通化し、
  `.POST(HttpRequest.BodyPublishers.noBody())` を発行。**非 2xx はレスポンスヘッダから correlation ID を抽出し
  3 引数 `GitLabApiException(status, body, correlationId)` を投げる**(status 種別によらず一律 3 引数構築。
  UI 挙動は失敗一律で、status は監査ログ用メタデータ=§15)。
- 公開 API: `fun post(path: String, query: Map<String, String> = emptyMap(), instanceUrl: String): PostResult`。
  **`instanceUrl`(呼び出し側が固定した送信先ベース URL)を必須引数で受け取り、`buildUri` はこの値から URI を
  構築する**(グローバル設定を再読しない)。これは FR-8 の TOCTOU を塞ぐため:ハンドラの検証(§8.7 2b)〜URI 構築の
  間に `gitlab.url` が変わっても、検証済みインスタンスへ送る。POST 用に `buildUri(path, query, baseOverride)` を
  追加し、既存 GET は従来どおりグローバル設定を読む(GET 系メソッドのシグネチャは不変)。
  本文(業務データ)は解析しないが、**監査に必要なレスポンスメタデータは呼び出し元へ返す**(§15 の根拠)。
  `data class PostResult(val httpStatus: Int, val correlationId: String?)`(correlationId = `x-request-id` 等の
  ヘッダ、無ければ null)。
- 呼び出し側の分類(FR-4 = 成功/失敗の 2 値):
  - 2xx = **成功**(`PostResult` を返す)。
  - 非 2xx = **失敗**。`GitLabApiException`(status, body, correlationId)として伝播。`correlationId` は nullable の
    追加フィールド(既存 status/body は不変・既存 GET の `sendGet` は 2 引数のままで無影響)。ヘッダから
    correlation ID を抽出して 3 引数構築するのは、§15 の監査ログに status/correlation ID を残すため。
  - 送信のタイムアウト(`HttpTimeoutException`)・接続断等の `IOException` も **失敗**として扱う(そのまま伝播。
    correlation ID は取得不能)。
  - UI 挙動は失敗で一律(通知 + Error Log、refresh しない)。status(4xx/5xx/3xx)や例外種別は監査ログの
    メタデータとしてのみ用い、挙動は分けない。非 2xx での correlation ID 伝播は §19.1 のテスト対象とする。

### 8.2 `PipelineActionService` / `JobActionService`(新規)
- `PipelineActionService`: `retry(instanceUrl: String, projectId: Long, pipelineId: Long): PostResult` / `cancel(...)`。
- `JobActionService`: `retry/cancel/play(instanceUrl: String, projectId: Long, jobId: Long): PostResult`。
- いずれも path を組み立てて `apiClient.post(path, instanceUrl = instanceUrl)` を呼び、その `PostResult` を返す薄い
  ラッパ(検証済み送信先の固定=FR-8/6A + 監査メタデータをハンドラへ橋渡し)。DI は既存 `service()` 既定引数。

### 8.3 `CiStatus.contextAction`(追加)
- `enum class CiAction { RETRYABLE, CANCELLABLE, EXECUTABLE }` を導入。
- `fun contextAction(status: String?, allowFailure: Boolean = false): CiAction?` を §5.3 の表どおり実装
  (`failed` かつ `allowFailure` は RETRYABLE、未知/null は null)。既存 `displayName` / `priority` は不変。

### 8.4 ノード拡張(`SidebarNode.kt`)
- `PipelineNode`: 追加フィールド `pipelineId: Long`(= `pipeline.id`)、`projectId: Long?`(= `pipeline.projectId`)、
  `canRetry: Boolean`、`canCancel: Boolean`。後者2つは構築時にジョブ一覧から算出。
- `JobNode`: 追加フィールド `projectId: Long?`(= 所属パイプラインの `pipeline.projectId`)。
  retryable / cancellable / executable は `job.status` + `allowFailure` から `CiStatus.contextAction` で都度算出。
- **送信先固定(FR-8)**: `PipelineNode` / `JobNode` に取得元インスタンス識別を保持する。実装は
  `sourceInstanceUrl: String`(構築時点の `GITLAB_INSTANCE_URL`。正規化して比較)を carry する
  (buildPipelineNode/buildStageNodes で pipeline とともに渡す)。設定世代トークンでも可だが、URL 直持ちが単純で確実。

### 8.5 `SidebarViewModel.buildPipelineNode`(改修)
- 既存シグネチャ `buildPipelineNode(pipeline, jobsResult)` を維持。
- 成功した jobs 一覧から `canRetry = jobs.any { contextAction == RETRYABLE }`、
  `canCancel = jobs.any { contextAction == CANCELLABLE }` を算出して `PipelineNode` に渡す。
- `buildStageNodes` 経由で各 `JobNode` に `pipeline.projectId` を carry する(内部 build 関数の引数追加のみ。
  View 側の配線変更なし)。
- jobs 取得が失敗した場合(`jobsResult` failure)は従来どおり "Failed to load jobs" 行を出し、
  `canRetry = canCancel = false`(操作不可)とする。

### 8.6 `CiActionPropertyTester : PropertyTester`(新規)
- プロパティ `canRetry` / `canCancel` / `canPlay` を評価。
  - `canRetry` = `PipelineNode.canRetry` または(`JobNode` かつ `contextAction(job) == RETRYABLE`)
  - `canCancel` = `PipelineNode.canCancel` または(`JobNode` かつ `contextAction(job) == CANCELLABLE`)
  - `canPlay` = `JobNode` かつ `contextAction(job) == EXECUTABLE`
- `projectId == null` のノードは、対応するアクションを **false**(操作不可)とする。

### 8.7 ハンドラ(新規、2 クラス)
- `PipelineActionHandler`(command `RetryPipeline` / `CancelPipeline` の双方に登録):
  `event.command.id` で action を分岐。
- `JobActionHandler`(command `RetryJob` / `CancelJob` / `PlayJob` に登録):同様に分岐。
- **in-flight ガード**(FR-6): 実行中の **(instanceUrl, 対象種別, 対象ID)** を保持する共有・スレッドセーフな `Set`
  (action は含めない=同一対象への retry/cancel を直列化)。起動前に判定し、既に実行中なら再入を抑止(no-op)。
  busy 表示は出さない。**成否によらず `finally` で除去**する。POST は最大 30s(request timeout)まで実行中扱いの
  ため、その間の連打・並行 write を防ぐ。unknown/HELD のような照合待ちの保持状態は持たない(§5.1 スコープ判断・
  VSCode パリティ)。
- 流れ(`CheckoutMrBranchHandler` 準拠):
  1. UI スレッドで `HandlerUtil.getActiveMenuSelection`(fallback `getCurrentSelection`)から対象ノードを取得。
  2. ノードから `sourceInstanceUrl` / `projectId` / `pipelineId` or `jobId` を抽出。null なら通知して終了。
  2b. **送信先インスタンス検証(FR-8)**: ノードの `sourceInstanceUrl` と現在のグローバル `GITLAB_INSTANCE_URL`
      を正規化比較。不一致なら **write を発行せず**「接続先が変わっています。サイドバーを更新してください」と通知して終了。
      **一致した `sourceInstanceUrl` をこの操作の送信先として以降固定**(サービス→`post(instanceUrl=…)` に渡し、
      URI 構築までグローバル再読しない=6A の TOCTOU 回避)。
  3. in-flight ガードで (instanceUrl, 対象種別, 対象ID) を確認・登録。既に実行中なら終了。
  4. 背景コルーチン(`lazyService<CoroutineScope>()`)で該当サービスを **固定した instanceUrl 付きで**呼び、
     結果を FR-4 の 2 値で扱う:
     - **成功(2xx)** → `Display.getDefault().asyncExec { findSidebarView()?.refresh() }`(+ 監査ログ §15)。
     - **失敗(`GitLabApiException`〈非 2xx〉/ `HttpTimeoutException` / `IOException`)** → `NotificationUtils.show`
       (「操作に失敗しました。サイドバーを更新して状態をご確認ください」= 盲目的な再試行は促さない文言)+
       監査ログ(§15、instanceUrl / status / correlation ID / 例外種別)。サイドバーは変更しない(refresh しない)。
     - `CancellationException` は再送。
  5. 解除: 成否によらず POST の `finally` で (instanceUrl, 対象種別, 対象ID) を除去する。

### 8.8 `plugin.xml`(配線)
- `<propertyTester>`(`type` = `SidebarNode`、namespace 例 `com.gitlab.eclipse.node`、
  properties `canRetry,canCancel,canPlay`、class = `CiActionPropertyTester`)を登録。
- `<command>` 5 件(id: `com.gitlab.eclipse.commands.{RetryPipeline,CancelPipeline,RetryJob,CancelJob,PlayJob}`)。
- `<handler>` 5 件(2 クラスを各 command に割当)。
- `menuContribution locationURI="popup:com.gitlab.eclipse.views.GitLabSidebarView"` に 5 `<command>`。
  各 `visibleWhen`(`checkEnabled="false"`)は
  `<with variable="activeMenuSelection"><iterate ifEmpty="false"><and><instanceof .../><test property=".../canXxx"/></and></iterate></with>`。

## 9. 処理フロー(正常系 / 異常系 / 境界)

- 正常系(成功): メニュー選択 → in-flight 登録 → 背景 POST → 2xx → UI スレッドで refresh → 最新ツリー描画。
- 異常系(失敗): 非 2xx(権限なし 403 / 見つからない 404 / 状態遷移不可 400 / 5xx / 3xx など)、タイムアウト、
  IO 例外。いずれも `GitLabApiException` または送信例外を捕捉し、**一律で通知 + 監査ログ**(§15)。
  サイドバーは変更しない(refresh しない)。VSCode パリティで細分岐した UI 挙動は持たない。
- 境界:
  - jobs が 0 件のパイプライン: canRetry/canCancel とも false(メニュー非表示)。
  - `projectId == null` / `sourceInstanceUrl` 不一致: 前者は非表示(§8.6)、後者は write 拒否(FR-8/§8.7 2b)。
  - 選択が対象ノードでない/複数選択: `visibleWhen` の `<iterate>` により非表示。ハンドラ側でも null チェック。
  - 操作対象が操作発行時点で既に状態遷移済み(例: cancel 済みを再 cancel): サーバが 4xx を返す →
    失敗として通知(ローカルの楽観更新はしない)。
  - 同一ノード同一操作の連打: in-flight ガードにより 2 回目以降(POST 実行中)は抑止(FR-6)。
  - タイムアウト後の二重発火: §5.1 スコープ判断のとおり、VSCode 同等の受容制限(§22 R-4)。in-flight ガードで
    連打は抑止するが、30s 経過後の意図的な再実行はユーザー判断に委ねる。

## 10. API / インターフェース

- `GitLabApiClient.post(path: String, query: Map<String, String> = emptyMap(), instanceUrl: String): PostResult`
- `data class PostResult(val httpStatus: Int, val correlationId: String?)`
- `GitLabApiException`(既存)に `correlationId: String? = null` を追加(status/body は不変)
- `PipelineActionService.retry(instanceUrl: String, projectId: Long, pipelineId: Long): PostResult` / `.cancel(...)`
- `JobActionService.retry(instanceUrl: String, projectId: Long, jobId: Long): PostResult` / `.cancel(...)` / `.play(...)`
- `CiStatus.contextAction(status: String?, allowFailure: Boolean = false): CiAction?`

## 11. データモデル

- 既存 `GitLabPipeline`(`id`, `projectId`, `status`, …)・`GitLabJob`(`id`, `name`, `status`, `stage`,
  `allowFailure`, `webUrl`)を再利用。**モデルの新規フィールド追加は行わない**
  (project_id は pipeline 由来で足りるため `GitLabJob` は変更しない)。
- ノード(`PipelineNode` / `JobNode`)にのみ操作用フィールドを追加(§8.4)。

## 12. トランザクション境界 / 冪等性

- 各操作は単一 REST POST であり、複合トランザクションは無い。
- **冪等性(正確な整理)**:
  - `cancel`(pipeline / job): 概ね冪等・安全。既 cancel 状態への再 cancel はサーバが 4xx を返し failure 扱い。
  - `retry`(pipeline / job)・`play`(job): **API 契約として冪等ではない**。retry は失敗/キャンセル済みジョブの
    新しい実行を生成し、play は手動ジョブを起動する。同一操作を 2 回サーバが受理すると、実行が二重生成され得る。
- **二重処理の防止(本 PR の範囲=VSCode パリティ)**:
  - クライアント連打・同一対象への並行 write: in-flight ガード(FR-6、キー=instanceUrl+対象種別+対象ID)で直列化(最大 30s)。
  - **タイムアウト後のサーバ二重実行**(POST 適用後に応答欠落): GitLab API は idempotency key を持たず、
    クライアントで exactly-once を保証できない。VSCode 拡張も保護しないため、本 PR は同等とし、この残存リスクを
    **受容する制限**とする(§5.1 スコープ判断・§22 R-4)。緩和は「失敗時に盲目的な再試行を促さない通知文」で行う。
- クライアント状態の不整合は生じない(成功時は refresh がサーバ状態でツリーを上書きし、失敗時はツリーを変えない。
  ユーザーが手動 refresh すれば常にサーバ状態が反映される)。
- create は本 PR 対象外のため、二重「生成(新規パイプライン)」の懸念は本 PR には無い。

## 13. 並行処理 / 競合

- 操作は背景コルーチン(既存の共有 `CoroutineScope`)で実行。UI スレッドとの受け渡しは
  `Display.asyncExec` で行う(既存 View / ハンドラと同一規律)。
- refresh は既存 `SidebarRefreshCoordinator` の世代管理に委ねる。操作後 refresh が
  進行中の別 refresh と競合しても、世代ガードにより新しい世代が勝つ(PR-1 の不変条件を踏襲)。
- 同一ノードへの連続操作(busy 表示なし)の扱い: 各操作は独立 POST。結果は refresh がサーバ状態で
  収束させるため、二重操作による永続的な不整合は生じない(§12)。

## 14. 認証と認可

- 認証: 既存 Bearer トークン。トークンはログに出さない。
- 認可: 権限不足時は GitLab が 403 を返す → 異常系として通知。クライアント側で事前の権限判定は行わない
  (VSCode も同様)。

## 15. ログ / 監視 / 監査

- **構造化した相関情報**を、書き込み操作の監査記録として共通形式で残す。応答欠落・タイムアウトや
  複数プロジェクト/同一ノードへの並行操作でも「どの POST がどの対象へ到達したか」を追跡できるようにする。
- 記録項目(1 操作 1 レコード):`action`(retry/cancel/play + pipeline/job 種別)、**`instanceUrl`(正規化済み
  送信先=複数インスタンス間で projectId/ID が衝突しても判別可能にするため必須)**、`projectId`、
  `pipelineId` または `jobId`、開始・終了時刻(または所要時間)、HTTP status(取得できた場合)、
  `outcome`(`success` / `failure`)、GitLab が返す request/correlation ID(`x-request-id` 等、取得可能なら)。
  correlation ID が null(timeout/IO)でも `instanceUrl` があれば「どちらの GitLab へ送ったか」を追跡できる。
- `outcome` は **success / failure の 2 値**(UI 挙動は 2 値=FR-4)。failure レコードには、追跡のために
  以下の細分メタデータを可能な範囲で残す:
  - **HTTP 応答を伴う失敗(4xx/5xx/3xx)**: `GitLabApiException`(status, correlationId)から HTTP status と
    correlation ID を記録する(502/504 等をプロキシ/GitLab 側で追跡するために不可欠)。
  - **HTTP 応答を伴わない失敗(タイムアウト/IO)**: status/correlation ID は取得不能のため null とし、
    例外種別(timeout / IO)のみ記録する。
- **メタデータの伝播経路**(§8.1・§8.2):success 時は `PostResult`(httpStatus, correlationId)、失敗時は
  `GitLabApiException`(status, correlationId)でハンドラ(監査レコード作成地点)まで届く。
- **除外・マスキング**: アクセストークンおよび未加工のレスポンス本文はログに出さない(NFR-2)。
- レベル: success = `info`、failure = `error`。ユーザーには `NotificationUtils.show` で
  簡潔に通知し、詳細は Error Log を参照とする(既存パターン)。保持先は Eclipse Error Log。
  障害調査は「対象 id と outcome、correlation ID を Error Log で突き合わせ、GitLab 側の実状態と照合」する。

## 16. 既存機能への影響

- `GitLabApiClient` の GET 系メソッドは不変(POST は独立追加)。
- `CiStatus.displayName` / `priority` は不変(`contextAction` は追加のみ)。
- `PipelineNode` / `JobNode` はフィールド追加。既存の label / activationUrl / children 契約は不変。
- `buildPipelineNode` はシグネチャ不変(内部で新フィールドを算出)。View の配線変更なし。
- Koin / 依存の追加なし。plugin.xml は command / handler / menu / propertyTester の追加のみ。

## 17. 移行方法 / ロールバック

- 移行: 追加のみで既存挙動を変えないため、特別な移行手順は不要。
- **ロールバックは 2 層に分けて定義する(混同禁止)**:
  1. **コード(配布物)のロールバック**: 本 PR をリバートすれば UI から操作メニューが消え、PR-1 の read-only
     表示に戻る。プラグイン側のデータ移行はない。
  2. **業務操作(GitLab 側状態)の補償**: 上記リバートは、**リバート前に実行された cancel / retry / play の
     GitLab 側結果を元に戻さない**。各操作は外部システム(GitLab)への確定操作であり、本プラグインからの
     自動ロールバック/補償は行わない。
     - `cancel`: **取消不能**(キャンセルされたジョブ/パイプラインを本プラグインからは復元しない)。復旧が必要な
       場合の補償は「ユーザーが対象を retry / play で再実行」する(実施主体=ユーザー、判断条件=業務都合、
       確認方法=refresh 後の状態表示)。
     - `retry` / `play`: 生成された実行の取消が必要なら、ユーザーが当該ジョブ/パイプラインを cancel する。
  - 運用担当者はこの記述を「配布物のロールバック手順」として読み、業務データ復旧手順と解釈しないこと。

## 18. タイムアウトとリトライ

- POST の request timeout は既存 GET と同一(30s)を既定とする。
- 自動リトライは行わない(書き込み操作。二重発火を避けるため)。
- タイムアウト・IO 例外・非 2xx はすべて **失敗** として一律に扱い(FR-4)、通知 + 監査ログ + refresh 無し。
- タイムアウト時にサーバ側で適用済みだった場合の二重実行は、VSCode 同等の**受容制限**(§5.1・§12・§22 R-4)。
  通知文は盲目的な再試行を促さず、ユーザーが手動 refresh で状態を確認したうえで判断する。

## 19. テスト方針

### 19.1 自動テスト(headless 可・TDD)

- REST POST(`sendPost` / `post` / 2 サービス): モック HTTP クライアントで path・メソッド(POST)・認証ヘッダ・
  body なし・**成功(2xx)/ 失敗(非 2xx / タイムアウト・IO)** の分岐を検証。加えて **success 時 `PostResult` に
  httpStatus/correlationId が入ること**、**非 2xx 時に `GitLabApiException.correlationId` へ `x-request-id` が
  伝播すること**(3 引数構築)を検証。
- ハンドラの結果分類(FR-4): **2xx=成功 / 非 2xx・timeout・IO=失敗** に振り分けられ、失敗時は refresh されず
  通知 + 監査ログ、成功時は refresh されることを検証。
- 送信先インスタンス検証(FR-8): ノードの `sourceInstanceUrl` が現在のグローバル設定と一致しないとき、
  **POST が発行されず**通知して終了することを検証。
- `CiStatus.contextAction`: §5.3 の全 status(null・unknown・`failed`+`allow_failure` 含む)を網羅。
- ノード算出(`buildPipelineNode` の canRetry/canCancel、jobs 0 件・jobsResult failure、JobNode への projectId /
  sourceInstanceUrl carry)。
- in-flight ガード(FR-6): キーが **(instanceUrl, 対象種別, 対象ID)** で、同一対象への **retry と cancel の並行/
  連打が直列化(2 回目 no-op)**されること、**成否によらず `finally` で除去**され以後は再操作可能になることを検証。
- 送信先固定(6A): サービス/`post` に渡した `instanceUrl` から URI が構築され、検証後にグローバル設定を変えても
  送信先が変わらないことを検証(TOCTOU 回避)。

### 19.2 既知失敗のベースライン(件数ではなく ID で固定)

- headless の既知失敗は SWT ネイティブ未ロード由来の env 失敗であり、**テストの完全修飾名 + 失敗シグネチャの対**
  をベースラインとして固定する(実装計画=#12 に、`develop@d60f2e8` 実行時の {FQN, 期待例外型, 安定した
  メッセージ断片} 一覧を貼付)。SWT 未ロード由来の代表シグネチャ(例: `UnsatisfiedLinkError` /
  `SWTError` / no-more-handles 等)を各 FQN に対応付ける。
- 合格条件(2 段):
  1. **ベースライン集合の外に新規失敗がゼロ**(単なる合計件数一致では、既知失敗の解消と新規回帰の相殺を
     検出できないため)。
  2. **ベースライン集合内の失敗も、失敗理由がベースラインのシグネチャと一致すること**。既知テストが SWT 未ロード
     ではなく本変更起因の別例外/assertion failure で落ちるようになった場合は、FQN が集合内でも**新規回帰**として
     扱い不合格とする(FQN 一致だけの無条件スキップはしない)。

### 19.3 手動検証チェックリスト(実機・PR 説明文に記載)

PropertyTester / 各 command・handler / 選択境界は headless で実行不能なため、下記を実機で確認する
(実装 PR 説明文に、環境・入力 status・期待メニュー・送信 endpoint・通知/refresh の期待結果を列挙):

| # | 入力(node と status) | 期待メニュー | 送信 endpoint | 期待結果(通知 / refresh) |
|---|---|---|---|---|
| 1 | PipelineNode(retryable ジョブ有) | Retry Pipeline 表示 | `POST /pipelines/{id}/retry` | 無通知(成功)/ refresh で更新 |
| 2 | PipelineNode(cancellable ジョブ有) | Cancel Pipeline 表示 | `POST /pipelines/{id}/cancel` | 同上 |
| 3 | PipelineNode(jobs 0 / 全 skipped) | 操作メニューなし | — | — |
| 4 | JobNode(status=failed) | Retry Job | `POST /jobs/{id}/retry` | 同上 |
| 5 | JobNode(status=running) | Cancel Job | `POST /jobs/{id}/cancel` | 同上 |
| 6 | JobNode(status=manual) | Play Job | `POST /jobs/{id}/play` | 同上 |
| 7 | JobNode(status=skipped/unknown) | 操作メニューなし | — | — |
| 8 | 権限なしユーザーで retry | 表示はされる | POST → 403 | failure 通知 + Error Log / refresh なし |
| 9 | 同一対象へ Retry と Cancel を連続実行 | — | 直列化 | 実行中は 2 つ目の POST が飛ばない(FR-6) |
| 10 | ネットワーク切断/タイムアウト | — | POST → timeout | 失敗通知(再試行を促さない)+ Error Log / refresh なし |
| 11 | 表示中に接続先を別インスタンスへ変更 | — | write 拒否 | 「接続先が変わっています」通知 / POST 発行なし(FR-8) |

- 検証3点(自動側): 対象テスト PASS / **ベースライン外の新規失敗ゼロ**(§19.2) / 変更ファイル detekt 0。

## 20. 受け入れ条件

- AC-1: retryable ジョブを含むパイプラインの `PipelineNode` に「Retry Pipeline」が表示され、実行すると
  該当パイプラインの failed ジョブが再実行され、refresh 後に状態が更新される。
- AC-2: cancellable ジョブを含むパイプラインの `PipelineNode` に「Cancel Pipeline」が表示され、実行すると
  パイプラインがキャンセルされる。
- AC-3: `JobNode` は status に応じて Retry / Cancel / Play のいずれか(該当時)を表示し、実行が反映される。
- AC-4: 操作可能でない status のノードには該当メニューが表示されない。
- AC-5: 失敗(非 2xx / タイムアウト / 通信断)時は、盲目的な再試行を促さない通知が出て Error Log(監査ログ)に
  記録され、サイドバー表示は破壊されない(refresh しない)。
- AC-6: 接続先(`gitlab.url`/認証先)が表示時点から変わっている場合、write が発行されず更新を促す通知が出る(FR-8)。
- AC-7: 同一対象(instance+種別+ID)への retry/cancel の並行/連打が POST 実行中は直列化され、完了後(成否問わず)は再操作可能になる(FR-6)。
- AC-8: 監査ログに action / projectId / pipelineId or jobId / outcome(success/failure)/ HTTP status(取得時)/
  correlation ID(取得時)が記録され、トークン・生レスポンス本文は出力されない。detekt 0・
  **ベースライン外の新規テスト失敗ゼロ**(§19.2)。

## 21. 未決事項

- U-1: 成功時の通知の要否。VSCode は成功時トースト等を出さず refresh のみ。本設計も成功時は
  無通知(refresh のみ)を既定とするが、明示フィードバックを望む場合は軽い通知を追加可能。
- U-2: `PipelineActionHandler` / `JobActionHandler` を command id 分岐(2 クラス)にするか、
  command parameter か、アクション別ハンドラ(最大 5 クラス)にするか。本設計は **command id 分岐(2 クラス)**
  を推奨(plugin.xml の parameter 配線を避け、クラス数も抑える)。
- U-3: `projectId` の出所を `pipeline.projectId`(数値)に一本化する方針で確定してよいか
  (VSCode 準拠。encoded-path 再解決を避けられる)。null の場合の非表示扱いも本設計で確定。

## 22. 想定されるリスク

- R-1: `pipeline.projectId` が null のケース(GitLab は通常返すが、モデルは nullable)。
  → 該当ノードのアクションを非表示にし、ハンドラでも null チェック(操作不可通知)。
- R-2: PropertyTester の namespace / plugin.xml expression の記述ミスでメニューが出ない/常時出る。
  → 手動検証で status 別に確認。fable で実装・レビュー。
- R-3: 背景コルーチンからの UI スレッドホップ漏れ(refresh を UI 外で呼ぶ)。
  → 既存 `Display.asyncExec` 規律を踏襲。fable で実装・レビュー。
- R-4(**受容する制限**): タイムアウト時にサーバが POST を適用後、応答だけ欠落したケースでの
  **サーバ側の二重実行**(retry/play が非冪等)。GitLab API は idempotency key を持たず、クライアントで
  exactly-once を保証できない。**パリティ元の VSCode 拡張も同保護を実装していない**ため、本 PR は VSCode 同等とし
  この残存リスクを受容する(§5.1 スコープ判断・§12・§18)。緩和: (a) in-flight ガードで連打(POST 実行中の再入)を
  抑止、(b) 失敗通知は盲目的な再試行を促さず、ユーザーが手動 refresh で状態確認のうえ判断。create を対象外とした
  ため二重「生成(新規パイプライン)」リスクは無い。
- R-5: サイドバー表示中の接続先(`gitlab.url`/認証先)変更により、旧ノードの数値 ID が新インスタンスの無関係な
  対象へ write される **cross-instance 誤送信**。→ FR-8:ノードに `sourceInstanceUrl` を固定し、POST 直前に現在の
  グローバル設定と一致しなければ write を拒否し refresh を促す。
