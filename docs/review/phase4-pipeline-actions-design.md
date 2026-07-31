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
- FR-4: 失敗(非 2xx / 通信例外)時はユーザー通知 + Error Log 出力を行い、サイドバーは変更しない。
- FR-5: 確認ダイアログは出さない(即実行)。

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
  `.POST(HttpRequest.BodyPublishers.noBody())` を発行。非 2xx は既存 `GitLabApiException(status, body)` を投げる。
- 公開 API: `fun post(path: String, query: Map<String, String> = emptyMap())`(成功時 Unit、失敗時 throw)。
  本文は解析しない(refresh で最新化するため)。既存 GET 系メソッドは一切変更しない。

### 8.2 `PipelineActionService` / `JobActionService`(新規)
- `PipelineActionService`: `retry(projectId: Long, pipelineId: Long)` / `cancel(projectId: Long, pipelineId: Long)`。
- `JobActionService`: `retry/cancel/play(projectId: Long, jobId: Long)`。
- いずれも path を組み立てて `apiClient.post(path)` を呼ぶだけの薄いラッパ。DI は既存 `service()` 既定引数。

### 8.3 `CiStatus.contextAction`(追加)
- `enum class CiAction { RETRYABLE, CANCELLABLE, EXECUTABLE }` を導入。
- `fun contextAction(status: String?, allowFailure: Boolean = false): CiAction?` を §5.3 の表どおり実装
  (`failed` かつ `allowFailure` は RETRYABLE、未知/null は null)。既存 `displayName` / `priority` は不変。

### 8.4 ノード拡張(`SidebarNode.kt`)
- `PipelineNode`: 追加フィールド `pipelineId: Long`(= `pipeline.id`)、`projectId: Long?`(= `pipeline.projectId`)、
  `canRetry: Boolean`、`canCancel: Boolean`。後者2つは構築時にジョブ一覧から算出。
- `JobNode`: 追加フィールド `projectId: Long?`(= 所属パイプラインの `pipeline.projectId`)。
  retryable / cancellable / executable は `job.status` + `allowFailure` から `CiStatus.contextAction` で都度算出。

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
- 流れ(`CheckoutMrBranchHandler` 準拠):
  1. UI スレッドで `HandlerUtil.getActiveMenuSelection`(fallback `getCurrentSelection`)から対象ノードを取得。
  2. `projectId` / `pipelineId` or `jobId` を抽出。null なら通知して終了。
  3. 背景コルーチン(`lazyService<CoroutineScope>()`)で該当サービスを呼ぶ。
  4. 成功 → `Display.getDefault().asyncExec { findSidebarView()?.refresh() }`。
  5. 失敗 → `CancellationException` は再送、その他は `logger.error` + `NotificationUtils.show`。

### 8.8 `plugin.xml`(配線)
- `<propertyTester>`(`type` = `SidebarNode`、namespace 例 `com.gitlab.eclipse.node`、
  properties `canRetry,canCancel,canPlay`、class = `CiActionPropertyTester`)を登録。
- `<command>` 5 件(id: `com.gitlab.eclipse.commands.{RetryPipeline,CancelPipeline,RetryJob,CancelJob,PlayJob}`)。
- `<handler>` 5 件(2 クラスを各 command に割当)。
- `menuContribution locationURI="popup:com.gitlab.eclipse.views.GitLabSidebarView"` に 5 `<command>`。
  各 `visibleWhen`(`checkEnabled="false"`)は
  `<with variable="activeMenuSelection"><iterate ifEmpty="false"><and><instanceof .../><test property=".../canXxx"/></and></iterate></with>`。

## 9. 処理フロー(正常系 / 異常系 / 境界)

- 正常系: メニュー選択 → 背景 POST → 2xx → UI スレッドで refresh → 最新ツリー描画。
- 異常系(権限なし 403 / 見つからない 404 / 状態遷移不可 400 など): `GitLabApiException` を捕捉し通知 + ログ。
  サイドバーは変更しない。
- 異常系(通信例外・タイムアウト): 同上(通知 + ログ)。
- 境界:
  - jobs が 0 件のパイプライン: canRetry/canCancel とも false(メニュー非表示)。
  - `projectId == null`: 全アクション非表示。
  - 選択が対象ノードでない/複数選択: `visibleWhen` の `<iterate>` により非表示。ハンドラ側でも null チェック。
  - 操作対象が操作発行時点で既に状態遷移済み(例: cancel 済みを再 cancel): サーバが 4xx を返す →
    異常系として通知(ローカルの楽観更新はしない)。

## 10. API / インターフェース

- `GitLabApiClient.post(path: String, query: Map<String, String> = emptyMap())`
- `PipelineActionService.retry(projectId: Long, pipelineId: Long)` / `.cancel(...)`
- `JobActionService.retry(projectId: Long, jobId: Long)` / `.cancel(...)` / `.play(...)`
- `CiStatus.contextAction(status: String?, allowFailure: Boolean = false): CiAction?`

## 11. データモデル

- 既存 `GitLabPipeline`(`id`, `projectId`, `status`, …)・`GitLabJob`(`id`, `name`, `status`, `stage`,
  `allowFailure`, `webUrl`)を再利用。**モデルの新規フィールド追加は行わない**
  (project_id は pipeline 由来で足りるため `GitLabJob` は変更しない)。
- ノード(`PipelineNode` / `JobNode`)にのみ操作用フィールドを追加(§8.4)。

## 12. トランザクション境界 / 冪等性

- 各操作は単一 REST POST であり、複合トランザクションは無い。
- 冪等性: retry / cancel は GitLab 側で概ね冪等または安全(既遷移状態への再操作はサーバが 4xx を返し、
  本実装は異常系として扱う)。play を既起動ジョブへ再発行した場合もサーバが拒否し異常系となる。
  ローカルでの楽観更新を行わないため、二重発火してもクライアント状態の不整合は生じない
  (最終的に refresh がサーバ状態で上書きする)。
- create は本 PR 対象外のため、二重生成の懸念は本 PR には無い。

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

- 成功: `logger.info`(操作種別のみ。id 等は必要最小限、トークンは出さない)。
- 失敗: `logger.error`(例外 + メッセージ)。ユーザーには `NotificationUtils.show` で簡潔に通知し、
  詳細は Error Log 参照とする(既存パターン)。

## 16. 既存機能への影響

- `GitLabApiClient` の GET 系メソッドは不変(POST は独立追加)。
- `CiStatus.displayName` / `priority` は不変(`contextAction` は追加のみ)。
- `PipelineNode` / `JobNode` はフィールド追加。既存の label / activationUrl / children 契約は不変。
- `buildPipelineNode` はシグネチャ不変(内部で新フィールドを算出)。View の配線変更なし。
- Koin / 依存の追加なし。plugin.xml は command / handler / menu / propertyTester の追加のみ。

## 17. 移行方法 / ロールバック

- 移行: 追加のみで既存挙動を変えないため、特別な移行手順は不要。
- ロールバック: 本 PR をリバートすれば PR-1 の read-only 表示に戻る(データ移行なし)。

## 18. タイムアウトとリトライ

- POST の request timeout は既存 GET と同一(30s)を既定とする。
- 自動リトライは行わない(書き込み操作。二重発火を避ける)。失敗はユーザーに通知し、
  ユーザーが再実行できる。

## 19. テスト方針

- REST POST(`sendPost` / `post` / 2 サービス): モック HTTP クライアントで path・メソッド・認証ヘッダ・
  成功/失敗分岐を検証(TDD、headless 可)。
- `CiStatus.contextAction`: §5.3 の全 status を網羅(TDD、純ロジック)。
- ノード算出(`buildPipelineNode` の canRetry/canCancel、JobNode への projectId carry): TDD。
- PropertyTester / ハンドラ / plugin.xml 配線: headless では実行不能な SWT/DI/UI スレッド依存のため、
  **手動検証手順を実装 PR 説明文に記載**しユーザー実機で確認。
- 検証3点: 対象テスト PASS / 全体失敗数 36 維持 / 変更ファイル detekt 0。

## 20. 受け入れ条件

- AC-1: retryable ジョブを含むパイプラインの `PipelineNode` に「Retry Pipeline」が表示され、実行すると
  該当パイプラインの failed ジョブが再実行され、refresh 後に状態が更新される。
- AC-2: cancellable ジョブを含むパイプラインの `PipelineNode` に「Cancel Pipeline」が表示され、実行すると
  パイプラインがキャンセルされる。
- AC-3: `JobNode` は status に応じて Retry / Cancel / Play のいずれか(該当時)を表示し、実行が反映される。
- AC-4: 操作可能でない status のノードには該当メニューが表示されない。
- AC-5: 権限不足や状態不整合で失敗した場合、ユーザーに通知され Error Log に記録され、
  サイドバー表示は破壊されない。
- AC-6: トークンがログに出力されない。detekt 0・全体テスト失敗 36 維持。

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
- R-4: 二重操作(busy 表示なし)による混乱。→ 楽観更新をせず refresh でサーバ状態に収束させるため
  永続的不整合は無い。create を対象外としたため二重生成リスクも無い。
