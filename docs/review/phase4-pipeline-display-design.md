# Phase 4 PR-1: パイプライン表示基盤(`status.pipeline` 相当・read-only)設計書

- 対象リポジトリ: `sumikof/gitlab-eclipse-plugin`
- ベースブランチ: `develop`(tip = `b804d08`)
- 関連 issue: ロードマップ #8 / パリティ台帳 #7(D14)/ Phase 4 = #12
- 関連実装 PR: なし(本 PR-1 が Phase 4 の最初の実装スライス。設計レビュー後に着手)

> 本書は Phase 4(CI ドメイン、D14 = 8 機能)を薄い縦切り 4 PR に分割した**うち PR-1 のみ**の設計である。PR-2(操作)/ PR-3(アーティファクト・トレース)/ PR-4(CI lint)は本書の対象外(§3)。

---

## 1. 背景と目的

### 背景

公式 GitLab Eclipse プラグイン(Gradle/Kotlin、`com.gitlab.eclipse.*`)を VSCode 拡張 `gitlab-workflow` v6.85.3 と機能パリティさせるプロジェクト。現況パリティ 41/85。Phase 3 でサイドバー基盤(`GitLabSidebarView` = `TreeViewer`、sealed `SidebarNode`、`SidebarViewModel`、「For current branch」節)と現ブランチ→project/ref 解決(`RepositoryContextResolver`/`CurrentBranchGitReader`)、native REST 基盤(`GitLabApiClient`)が揃った。

D14「Pipelines / CI / Jobs」は 8 機能すべて未実装。VSCode 側は専用パイプラインツリー + ステータスバー項目 + ジョブトレース仮想ドキュメント + CI lint コマンドで構成される。

### 目的(PR-1 の到達点)

VSCode `gl.status.pipeline`(ステータスバーの現ブランチ最新パイプライン表示)に相当する **read-only 表示**を、サイドバー「For current branch」節に **Pipeline → Stage → Job** の 3 階層で追加する。これにより Phase 4 の残り 3 PR(操作・アーティファクト/トレース・lint)が乗る土台(REST モデル・CI ステータスモデル・サイドバーノード拡張)を確立する。

### 非目標(PR-1 では達成しない。§3 参照)

パイプライン/ジョブへの書き込み操作、アーティファクト DL、トレース表示、CI lint、自動更新、アイコン表示。

---

## 2. 対象範囲(PR-1)

| # | 項目 | 内容 |
|---|---|---|
| S1 | REST モデル | `GitLabPipeline` / `GitLabJob`(Jackson マッピング、既存 `GitLabMergeRequest` と同方式) |
| S2 | CI ステータスモデル | status 文字列 → 表示名 + priority の純ロジックマップ(VSCode `ci_status_metadata.ts` 忠実移植、name/priority のみ) |
| S3 | REST サービス | `PipelineService.getLatestPipelineForRef` / `JobService.getJobsForPipeline`(**GET のみ**) |
| S4 | サイドバーノード | sealed `SidebarNode` に `PipelineNode` / `StageNode` / `JobNode` を追加 |
| S5 | ViewModel | `SidebarViewModel.buildPipelineNode(pipeline, jobs)`(ステージ別グループ化・純ロジック) |
| S6 | View 統合 | `GitLabSidebarView` の「For current branch」節にパイプラインを eager fetch → 描画(手動更新のみ) |

達成する台帳項目: **D14 `gl.status.pipeline`(表示のみ)** = 1/8。

---

## 3. 対象外(PR-1・明示)

以下は Phase 4 の後続 PR または将来課題であり、本 PR-1 では**実装しない・依存構造も持ち込まない**。

- **REST POST 全般**(パイプライン/ジョブ操作、CI lint)。`GitLabApiClient` への POST 追加は最初に使う **PR-2 で行う**(YAGNI。PR-1 の差分と認証リスクを最小化)。
- パイプライン操作(`pipelineActions` / `cancelPipeline` / `retryPipeline` / `retryFailedPipelineJobs` / `createPipeline`)= **PR-2**。
- ジョブ操作(`executeJob` / `retryJob` / `cancelJob`)= **PR-2**。
- アーティファクト DL、ジョブトレース表示(Console)= **PR-3**。
- CI lint(`validateCIConfig` / `showMergedCIConfig`)= **PR-4**。
- **自動ポーリング/更新**(VSCode `currentBranchRefresher` 相当)= 後続(専用 issue 化を検討)。手動 refresh のみ。
- **ステータスアイコン**(SWT `Image`/`ImageDescriptor`)= 後続。headless で検証不能なため PR-1 はテキスト表示に限定。
- CI ステータスの `contextAction`(retryable/cancellable/executable)= 操作を実装する **PR-2**。
- **MR パイプライン優先解決**(VSCode の `getPipelinesForMr` + higher-iid マージ)= 後続。PR-1 は ref 解決のみ(§7.1・未決 U-1)。
- 専用パイプラインツリービュー(独立 view)= 採用しない(サイドバー節に統合)。

---

## 4. 現在の課題

1. `GitLabApiClient` は **GET 専用**(`fetchObject` / `fetchListFromApi` / private `sendGet`)。PR-1 は GET のみで完結するため問題にならないが、POST が必要な PR-2 以降のために「PR-1 は GET のみ」という境界を明示しておく。
2. プラグインに**ステータスバーが無い**。Phase 3 で `status.mr` / `status.issue` をサイドバー「For current branch」節へ写した前例に倣い、`status.pipeline` も同節へ写す。
3. サイドバーの遅延展開機構(`MergeRequestNode` の `loadedChildren`)は MR 用に作られている。パイプラインは eager fetch とするため**この機構は流用しない**(§6.4・§8.2)。

---

## 5. 要件

### 機能要件

- FR-1: 「For current branch」節に、現ブランチの最新パイプラインを 1 件表示する(存在時)。
- FR-2: パイプラインノードはステージ別にジョブをグルーピングして展開できる(Pipeline → Stage → Job)。
- FR-3: 各ノードのラベルにステータス表示名を含める(例 `Pipeline #1234 · Passed` / `unit-test · Failed`)。
- FR-4: パイプラインノードの activation(double-click / Enter)でパイプライン Web ページをブラウザで開く。ジョブノードの activation でジョブ Web ページを開く。ステージノードは非 activatable(展開のみ)。
- FR-5: パイプラインが存在しない場合は節にパイプライン行を出さない(MR/Issue 行の既存挙動を変えない)。
- FR-6: 更新は既存のサイドバー refresh(ボタン/ビュー再オープン)に追従する。自動更新はしない。

### 非機能要件

- NFR-1: REST 取得・JGit 参照は IO コルーチンで実行し、UI スレッドをブロックしない(Phase 3 パターン踏襲)。
- NFR-2: パイプライン取得の失敗が節の他要素(MR/Issue)や他節を巻き込まない(節・要素単位の障害分離)。
- NFR-3: トークンをログ・URL・例外メッセージに出さない(既存 `GitLabApiClient` の認証経路をそのまま使い、新たな認証コードを増やさない)。
- NFR-4: 純ロジック(status マップ・stage グループ化・ref 選択)は headless で TDD 可能な形に分離する。

---

## 6. システム構成とコンポーネントの責務

### 6.1 パッケージ配置(既存構成内・新設ディレクトリなし方針)

- `com.gitlab.eclipse.api.model`: `GitLabPipeline`, `GitLabJob`(既存 `GitLabMergeRequest`/`GitLabIssue` と同居)。
- `com.gitlab.eclipse.api`: `PipelineService`, `JobService`(既存 `MergeRequestService` 等と同居)。
- `com.gitlab.eclipse.ci`: `CiStatus`(status → 表示名 + priority マップ、純ロジック)。※ CI ドメイン専用の新規サブパッケージ。CLAUDE.md「ディレクトリ構成変更禁止」は既存パッケージ体系内への**追加**を許容(`com.gitlab.eclipse.*` 配下)。**未決 U-4**: `ci` サブパッケージ新設の可否をレビューで確認。
- `com.gitlab.eclipse.views.sidebar`: `PipelineNode`/`StageNode`/`JobNode`(既存 `SidebarNode.kt`)、`SidebarViewModel`/`GitLabSidebarView` 拡張。

### 6.2 REST モデル(S1)

`GitLabPipeline`(GitLab REST `GET /projects/:id/pipelines` の要素):
- `id: Long`, `iid: Long?`, `projectId: Long`(`project_id`), `status: String`, `ref: String?`, `sha: String?`, `webUrl: String`(`web_url`), `source: String?`, `createdAt: String?`, `updatedAt: String?`。

`GitLabJob`(`GET /projects/:id/pipelines/:pid/jobs` の要素):
- `id: Long`, `name: String`, `status: String`, `stage: String`, `webUrl: String?`(`web_url`), `allowFailure: Boolean`(`allow_failure`、既定 false)。
- 未知フィールドは無視(Jackson `@JsonIgnoreProperties(ignoreUnknown = true)` 既存方針)。

### 6.3 CI ステータスモデル(S2)

VSCode `ci_status_metadata.ts` の忠実移植(name + priority のみ、icon/contextAction は除外)。status キー(13)と表示名:

| status | 表示名 | priority |
|---|---|---|
| `manual` | Manual | 0 |
| `success` | Passed | 1 |
| `created` | Created | 3 |
| `waiting_for_resource` | Waiting for resource | 4 |
| `preparing` | Preparing | 5 |
| `pending` | Pending | 6 |
| `scheduled` | Delayed | 7 |
| `skipped` | Skipped | 8 |
| `canceled` | Cancelled | 9 |
| `canceling` | Cancelling | 10 |
| `failed` | Failed | 11 |
| `running` | Running | 12 |
| (不明) | Status Unknown | 0 |

- 特例: `status == "failed" && allowFailure == true` → 表示名 `Failed (allowed to fail)`(priority 2)。ジョブにのみ適用(パイプラインには `allow_failure` が無いため常に通常マップ)。
- priority は本 PR では表示に使わないが、将来のパイプラインレベル集約(最も注意すべきジョブ status を代表させる用途)に備え移植する。
- 大文字小文字は API 実値(小文字)前提。未知 status は Unknown フォールバック(never-throw)。

### 6.4 REST サービス(S3)

`PipelineService`:
- `getLatestPipelineForRef(projectId: Long, ref: String): GitLabPipeline?`
  - `GET /projects/{projectId}/pipelines?ref={ref}`。API は既定で新しい順に返す(`id` 降順)。先頭要素を返す。0 件なら `null`。
  - `GitLabApiClient.fetchListFromApi`(既存 GET・ページング)を利用。**先頭 1 件しか要らないため per_page=1 の 1 ページ取得に限定**(全ページ取得しない)。**未決 U-2**: `fetchListFromApi` が単一ページ・件数制限をサポートするか、`fetchObject` で配列を受ける新経路が要るかを実装前に実 API/既存コードで確認。

`JobService`:
- `getJobsForPipeline(projectId: Long, pipelineId: Long): List<GitLabJob>`
  - `GET /projects/{projectId}/pipelines/{pipelineId}/jobs`。既存 `fetchListFromApi` でページング取得。
  - API はステージ実行順・ジョブ id 昇順で返す。ステージ順は**ジョブ列の初出順**で決定(§6.5)。

いずれも **GET のみ**。認証は `GitLabApiClient` 既存経路(トークンは client 内で付与)。サービスは never-throw を強制せず、呼び出し元(View のコルーチン)が `runCatching` で節単位に隔離する(§8.2)。

### 6.5 ViewModel(S5)

`SidebarViewModel.buildPipelineNode(pipeline: GitLabPipeline, jobs: List<GitLabJob>): PipelineNode`(純関数):
1. ジョブを **stage 名で初出順にグルーピング**(`LinkedHashMap` 相当。API の返却順を保存)。
2. 各 stage → `StageNode(label=stage, children=[JobNode...])`。ジョブは API 返却順を保持。
3. `PipelineNode(label="Pipeline #{id} · {statusName}", activationUrl=webUrl, children=[StageNode...])`。
4. ジョブ 0 件時は `PipelineNode(children=emptyList())`(展開しても子なし)。VSCode も 0 件時は非展開 + パイプライン URL を開く。

ラベル生成に §6.3 の CiStatus マップを使用。すべて純ロジックで TDD 対象。

### 6.6 View 統合(S6)

`GitLabSidebarView` の「For current branch」節ビルド処理(Phase 3 で既に MR/Issue を fetch している IO コルーチン)に、パイプライン取得を**追加**する:
1. `RepositoryContextResolver` で現ブランチの project(restId)と ref(tracking branch 名)を解決(Phase 3 の既存経路)。
2. `PipelineService.getLatestPipelineForRef(projectId, ref)` → null なら節にパイプライン行を出さない(FR-5)。
3. 非 null なら `JobService.getJobsForPipeline(projectId, pipeline.id)` を取得。
4. `SidebarViewModel.buildPipelineNode(...)` で `PipelineNode` を構築。
5. `asyncExec` で描画。**世代 latest-wins + dispose ガード**(asyncExec 内で世代・`control.isDisposed` を再チェック)は Phase 3 実装をそのまま流用。
6. 節・要素単位の障害分離: パイプライン取得の例外は `supervisorScope` + `runCatching` で捕捉し、失敗時はパイプライン行を省略(または `MessageNode` で "Failed to load pipeline")。MR/Issue 行と他節を巻き込まない(NFR-2)。失敗は `logger.error`。

「For current branch」節内のパイプライン行の位置: **MR 行の下、閉じる Issue 行の前**(または節末尾)。**未決 U-3**: 表示順をレビューで確定。

---

## 7. 処理フロー

### 7.1 現ブランチ最新パイプラインの解決

```
サイドバー refresh / ビューオープン
  └─ (IO coroutine, 世代 g をキャプチャ)
       ├─ RepositoryContextResolver: 選択リポジトリ → RepositoryContext(projectId 数値, ref=tracking branch)
       │     ・detached / tracking 無し → No pipeline(節にパイプライン行なし)
       ├─ PipelineService.getLatestPipelineForRef(projectId, ref)
       │     ・0 件 → No pipeline
       ├─ JobService.getJobsForPipeline(projectId, pipeline.id)
       ├─ SidebarViewModel.buildPipelineNode(pipeline, jobs) → PipelineNode
       └─ asyncExec:
             ・世代 g が最新でない / control.isDisposed → 破棄
             ・そうでなければ「For current branch」節を再構築して描画
```

VSCode パリティ注記: VSCode は「MR があれば MR パイプライン、無ければ ref パイプライン、iid の大きい方」を採る(`get_pipeline_and_mr_for_branch.ts`)。PR-1 は **ref 解決のみ**に簡約(§3・未決 U-1)。同一リポジトリの現ブランチでは MR head と branch tip がほぼ一致するため実害は小さいが、fork MR 等で差異が出うる。

### 7.2 activation(ブラウザで開く)

- `PipelineNode` / `JobNode` の `activationUrl` を既存の activation ハンドラ(`SidebarNode.activationUrl` を開く既存経路 = `BrowserLauncher`)が処理。新規ハンドラ不要。
- `StageNode` は `activationUrl = null`(既存の非activatable ノードと同じ扱い。展開/折り畳みのみ)。

---

## 8. 横断的関心事

### 8.1 認証と認可

- 認証は `GitLabApiClient` 既存経路(PAT/OAuth トークンを client 内で付与)。本 PR で新たな認証コードを追加しない。
- 認可: GitLab API 側で権限判定。閲覧権限が無い/private の場合は API が 403/404 を返し、§8.2 の障害分離で節から静かに省く(または MessageNode)。

### 8.2 エラー処理・並行処理・障害分離

- REST 失敗(ネットワーク/HTTP エラー/JSON パース失敗)は View のコルーチンが `runCatching` で捕捉。パイプライン取得の失敗は**パイプライン行のみ**を落とし、MR/Issue 行・他節を保持(Phase 3 の supervisorScope パターン)。
- 世代 latest-wins: refresh 連打時、古い世代の asyncExec は描画前に破棄(Phase 3 実装流用)。
- dispose ガード: asyncExec 実行時に `control.isDisposed` を再チェック(ビュークローズ競合)。
- ステージグルーピング・status マップは never-throw(未知 status は Unknown)。

### 8.3 タイムアウト・リトライ・冪等性

- タイムアウト: `GitLabApiClient` 既存の HTTP タイムアウト設定に従う(本 PR で変更しない)。
- リトライ: なし(GET・read-only。失敗時は次の手動 refresh で再取得)。
- 冪等性: すべて GET のため自明に冪等。副作用なし。

### 8.4 ログ・監視・障害調査

- パイプライン/ジョブ取得の失敗は `logger.error`(既存 sidebar のロガー流用)。トークン等の機密は出さない(NFR-3)。
- 正常系の詳細ログは出さない(既存方針)。

### 8.5 障害時の復旧

- 一過性失敗はユーザーの手動 refresh で回復。プラグイン状態を破壊しない(read-only)。

---

## 9. 既存機能への影響 / 移行 / ロールバック

- **既存機能への影響**: 「For current branch」節に行が増えるのみ。MR/Issue の既存取得・描画経路は変更しない(節ビルドにパイプライン取得を**追加**する形)。`SidebarNode` は sealed だが、content/label provider は `children` で汎用ディスパッチするため新ノード追加で provider 改修は最小(ラベル生成の分岐のみ)。
- **移行**: データ移行なし。設定追加なし。
- **ロールバック**: 機能フラグは設けない。問題時は PR revert(read-only・状態非破壊のため revert は無害)。

---

## 10. テスト方針

- **純ロジック(TDD・headless 可、sonnet)**:
  - `CiStatus`: 全 13 status + Unknown フォールバック + `failed`+`allow_failure` 特例の表示名/priority。
  - `SidebarViewModel.buildPipelineNode`: ステージ別グルーピング(初出順保持)、ジョブ 0 件、単一/複数ステージ、ラベル生成。
  - `PipelineService.getLatestPipelineForRef`: 0 件 → null、先頭選択(新しい順)。※ HTTP はモック/フェイク client。
- **SWT/coroutine 統合(fable)**: View 統合は headless で SWT 未ロードのため env 失敗になりうる(既存 sidebar テストと同様)。世代・dispose・障害分離のロジックはレビューで担保し、実機で手動検証。
- **手動検証(PR 説明文に記載、ユーザー実機)**: パイプラインのあるブランチをチェックアウト → サイドバーに Pipeline → Stage → Job が出る / status 表示 / パイプライン・ジョブ double-click でブラウザが開く / パイプライン無しブランチで行が出ない / refresh で更新。
- **検証 3 点(既存運用)**: 対象テスト PASS / 全体失敗数が develop と同数(36)/ 変更ファイルに detekt 指摘ゼロ。

---

## 11. 受け入れ条件

- AC-1: パイプラインのある現ブランチで、サイドバー「For current branch」節に `Pipeline #<id> · <status>` が表示され、展開すると Stage → Job が出る。
- AC-2: 各ノードのラベルにステータス表示名が正しく出る(§6.3 の 13 種 + allow_failure 特例)。
- AC-3: パイプライン double-click でパイプライン Web ページ、ジョブ double-click でジョブ Web ページがブラウザで開く。
- AC-4: パイプラインが無いブランチ、detached HEAD、tracking 無しブランチではパイプライン行が出ず、MR/Issue の既存表示は不変。
- AC-5: パイプライン取得失敗が MR/Issue 行・他節を巻き込まない。
- AC-6: `./gradlew build` で detekt 0、headless test の全体失敗数が develop と同数。

---

## 12. 未決事項(レビューで確定したい)

- **U-1**: ブランチパイプラインの解決を PR-1 で **ref のみ**に簡約する方針の是非。VSCode パリティ(MR パイプライン優先 + higher-iid)を PR-1 に入れるか、後続に繰り延べるか。
- **U-2**: `GitLabApiClient.fetchListFromApi` が「単一ページ・per_page 指定」をサポートするか。`getLatestPipelineForRef` で全ページ取得を避けるための取得経路(既存 API のどれを使うか、新設が要るか)を実装前に確定。
- **U-3**: 「For current branch」節内でのパイプライン行の表示位置(MR 行の下 / Issue 行の前 / 節末尾)。
- **U-4**: `com.gitlab.eclipse.ci` サブパッケージ新設の可否(CLAUDE.md「ディレクトリ構成変更禁止」の解釈 = 既存 `com.gitlab.eclipse.*` 配下への追加は許容と理解しているが確認)。
- **U-5**: ステージノードのラベルに集約 status(そのステージで最も注意すべきジョブ status)を出すか、ステージ名のみにするか(PR-1 はステージ名のみを想定)。

---

## 13. 想定されるリスク

- **R-1(検出困難系)**: View 統合の世代/dispose/障害分離は headless で検証不能。Phase 3 の実績パターンをそのまま流用し、逸脱を作らないことで軽減。実装・レビューは fable、実機で手動検証。
- **R-2**: `fetchListFromApi` が全ページ取得前提だと、`pipelines?ref=` が大量にある場合に無駄取得。U-2 で単一ページ取得経路を確定して軽減。
- **R-3**: ステージ順の解釈違い。API 返却順(初出順)を正とし、クライアント側で並べ替えない方針を明記。
- **R-4**: ref 簡約(U-1)による VSCode との微差(fork MR 等)。PR-1 対象外として台帳・PR 説明に明記し、後続で解消。

---

## 付録: Phase 4 全体の分割(本書は PR-1 のみ)

| PR | 内容 | 主な追加 |
|---|---|---|
| **PR-1(本書)** | パイプライン表示(read-only、`status.pipeline` 相当) | REST モデル・CiStatus・GET サービス・サイドバーノード |
| PR-2 | パイプライン/ジョブ操作 | `GitLabApiClient` POST 追加、retry/cancel/play/create、retryFailedPipelineJobs、pipelineActions メニュー、contextAction |
| PR-3 | アーティファクト DL + ジョブトレース | downloadArtifacts(ブラウザ)、trace 表示(Console) |
| PR-4 | CI lint | validateCIConfig / showMergedCIConfig(`/ci/lint` POST) |
