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
| S1 | REST モデル | `GitLabPipeline` / `GitLabJob`(**Gson `@SerializedName`**、既存 `GitLabMergeRequest` と同方式) |
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
- **MR パイプライン優先解決**(VSCode の `getPipelinesForMr` + higher-iid マージ)= 後続。PR-1 は effective-ref による ref 解決のみ(§7.1・U-1)。
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
- FR-4: パイプラインノードの **double-click** でパイプライン Web ページをブラウザで開く。ジョブノードの double-click でジョブ Web ページを開く。ステージノードは非 activatable(展開のみ)。(Enter/キーボード activation は既存サイドバー全体が未対応のため PR-1 対象外=§7.2。)
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

**シリアライズ方式(Codex #5 反映・確定)**: 本リポジトリの `GitLabApiClient` は **Gson** でデシリアライズし、既存モデル(`GitLabMergeRequest`/`GitLabIssue`)は `com.google.gson.annotations.SerializedName` を用いる(Jackson 依存は無い)。新モデルも **Gson `@SerializedName`** に統一する。

**重要(Gson の default 無視・既存 `GitLabMrVersion.diffs` の実績)**: Gson は unsafe object construction でフィールドを埋めるため、**JSON に無いキーは Kotlin の既定値ではなく `null` になる**(non-nullable 宣言でも実行時 null)。したがって「JSON に必ず存在する保証が無いフィールドは nullable 宣言 + 参照時 null ガード」を原則とし、モデルテストで欠落キーの挙動を固定する。

`GitLabPipeline`(GitLab REST `GET /projects/:id/pipelines` の要素):
- `@SerializedName("id") id: Long`, `iid: Long?`, `@SerializedName("project_id") projectId: Long?`, `status: String?`, `ref: String?`, `sha: String?`, `@SerializedName("web_url") webUrl: String?`, `source: String?`, `@SerializedName("created_at") createdAt: String?`, `@SerializedName("updated_at") updatedAt: String?`。
- `status`/`webUrl` は表示・activation に使うため参照時に null ガード(未知/欠落は §6.3 Unknown・activation 無効)。

`GitLabJob`(`GET /projects/:id/pipelines/:pid/jobs` の要素):
- `@SerializedName("id") id: Long`, `name: String?`, `status: String?`, `stage: String?`, `@SerializedName("web_url") webUrl: String?`, `@SerializedName("allow_failure") allowFailure: Boolean?`(参照時 `?: false`)。
- `name`/`stage` 欠落時の表示既定(例 `stage` null → `"(no stage)"`)をモデル/ViewModel テストで固定。

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

**プロジェクト ID の型(Codex #1 反映・確定)**: `RepositoryContext.projectId` は数値ではなく **URL エンコード済み namespace パス文字列**(`encodeProjectId` = `/`→`%2F`)であり、GitLab REST はこれを `:id` として受理する。サービスはこの **`String`(エンコード済みパス)をそのまま受け取る**。数値 ID を別途解決する追加 API 呼び出しは**行わない**。

`PipelineService`:
- `getLatestPipelineForRef(projectId: String, ref: String): GitLabPipeline?`
  - `GET /projects/{projectId}/pipelines?ref={ref}&per_page=1&page=1`。GitLab は既定で `id` 降順に返すため先頭 = 最新。0 件なら `null`。
  - **取得経路(Codex #8 反映・U-2 確定事項)**: 既存 `fetchListFromApi` は `MAX_PAGES=20` × `PER_PAGE=100` を同期ループで全取得するため使わない。**単一ページ(1件)だけを取る GET 経路を追加する**(`GitLabApiClient` に単ページ配列取得メソッドを足すか、`fetchObject` に配列型を通す。**実装前に既存 `sendGet`/`fetchObject` の再利用可否を確定**)。ページングも 20 ページ盲信もしない。

`JobService`:
- `getJobsForPipeline(projectId: String, pipelineId: Long): List<GitLabJob>`
  - `GET /projects/{projectId}/pipelines/{pipelineId}/jobs`。ジョブは多数ページになりうるため `fetchListFromApi`(ページング)を用いるが、**総ページ上限と総所要時間(§8.3)を明記**する。
  - **順序(Codex #4 反映・確定)**: 「API がステージ実行順・id 昇順で返す」という前提は**採らない**(GitLab jobs API の返却順は保証が弱く、retry で新しい id のジョブが混ざる)。表示順はクライアント側で**決定的に**定める(§6.5)。GitLab jobs API の実際の返却順は**実装時に実 API/ドキュメントで確認**する(表示は返却順に依存しない設計にするため、確認結果に関わらず表示は安定する)。

いずれも **GET のみ**。認証は `GitLabApiClient` 既存経路(トークンは client 内で付与)。サービスは never-throw を強制せず、呼び出し元(View のコルーチン)が独立 `Result` として隔離する(§6.6・§8.2)。HTTP エラーの分類(0件 vs 403/404 vs その他)は §8.1。

### 6.5 ViewModel(S5)

**決定的な表示順(Codex #4 反映・確定)**: API の返却順に依存せず、クライアント側で次の順に整える。
1. ジョブを **`id` 昇順**にソートする。
2. ソート済み列を **stage 名で初出順にグルーピング**(`LinkedHashMap` 相当)。結果として **ステージ順 = そのステージ内の最小 job id の昇順**、**ステージ内ジョブ順 = id 昇順**という決定的規則になる(retry・並列・複数ページ結合を含め安定)。
3. `stage` が null/空のジョブは既定ラベル(例 `"(no stage)"`)のステージにまとめる。

`SidebarViewModel.buildPipelineNode(pipeline: GitLabPipeline, jobsResult: Result<List<GitLabJob>>): PipelineNode`(純関数):
- `PipelineNode(label="Pipeline #{id} · {statusName}", activationUrl=webUrl, children=…)`。ラベルは §6.3 の CiStatus マップ(`pipeline.status`、null→Unknown)。`webUrl` null 時は activation 無効。
- children は **`jobsResult` を fold**(Codex #3 のジョブ単独失敗対応):
  - 成功 → 上記手順で `StageNode`→`JobNode` を構築。ジョブ 0 件なら子なし(展開不可・パイプライン行のみ。VSCode も 0 件時は非展開でパイプライン URL を開く)。
  - 失敗 → 子を `listOf(MessageNode("Failed to load jobs"))` とし、**パイプライン行自体は残す**(status.pipeline 表示を維持)。
- `JobNode` ラベルは `"{name} · {statusName}"`(`failed`+`allowFailure` は §6.3 の特例文言)。

すべて純ロジックで TDD 対象(status マップ・id ソート・ステージ初出順・0件・ジョブ失敗・null stage)。

### 6.6 View 統合(S6)

**MR とパイプラインを独立した結果として合成(Codex #3 反映・確定)**: 現行 `buildCurrentBranchSection(result: Result<CurrentBranchInfo>)` は単一 Result で、MR が null だと即「No merge request found」を返す。ここにパイプラインを相乗りさせると (a) MR 無し=パイプラインも消える、(b) パイプライン/ジョブ失敗で MR・closing issues も失われ FR-1/NFR-2/AC-5 に違反する。したがって **MR とパイプラインを別タスク・別 `Result` として取得し、節ビルドで独立合成**する。

**プリステップの終端状態を分離(Codex R5/R6 反映・確定)**: 現行 `applyResults` は `currentBranchResult == null` を **「リポジトリ未解決 = Select a repository」**として扱う(`GitLabSidebarView:205-210`)。増分描画で `null` を「未完了(Loading)」に再利用すると衝突し、さらに**プリステップが sibling を起動できないケースだと永久に "Loading…"** になる。したがって節入力を sealed 型にし、「未解決」と「解決済み(増分)」を区別する。

**プリステップ API は never-throw(Codex R6 で確認・確定)**: `CurrentBranchGitReader.read` は `catch (e: Exception)` で全例外を握り潰し all-null の `CurrentBranch` を返す(`CurrentBranchGitReader:57-60`)。`RepositoryContextResolver.resolveNonInteractive`/`candidateContexts` は解決失敗を `mapNotNull`/`catch` で `null` に畳む(`RepositoryContextResolver:48-55,100-167`)。この never-throw は Phase 3 の意図的契約(共有 CoroutineScope 保護)。したがって **git dir 破損・remote 解決失敗は「例外」ではなく `null` context(→ 未解決)または all-null branch(→ detached=パイプライン無し)として現れる**。よって当初案の `PreStepFailure` 状態は**到達不能なので設けない**(返却値も detached と破損を区別できないため分類も不可能)。失敗を config-aware エラーとして明示表示するには never-throw 契約を変える失敗保持 API が必要で、**PR-1 対象外(後続候補)**。
```
sealed interface CurrentBranchSectionInput
object NoRepository : CurrentBranchSectionInput          // context null(未解決。解決失敗も既存挙動でここに畳まれる)
data class Resolved(                                      // context 解決 → 増分描画
  val mr: Result<CurrentBranchInfo>?,        // null = MR タスク未完了(Loading)
  val pipeline: Result<PipelineSnapshot?>?,  // null = パイプラインタスク未完了(Loading)
) : CurrentBranchSectionInput
// PipelineSnapshot = (pipeline: GitLabPipeline, jobsResult: Result<List<GitLabJob>>) / 内側値 null = パイプライン無し
```
`buildCurrentBranchSection(input: CurrentBranchSectionInput): SidebarNode`:
- `NoRepository` → `CurrentBranchSectionNode(listOf(MessageNode(SELECT_REPOSITORY_MESSAGE)))`(現行の "Select a repository" を保持)。
- `Resolved` → 増分合成(children = `pipelineChildren + mrChildren`、表示順は U-3):
  - `pipelineChildren`: `mr/pipeline` の `null`(未完了)→ `MessageNode("Loading…")` / 成功&値null → 行なし(FR-5) / 成功&非null → `buildPipelineNode(pipeline, jobsResult)`(ジョブ失敗はパイプライン行を残す §6.5)/ 失敗 → `MessageNode`(§8.1 分類)。
  - `mrChildren`: `null`(未完了)→ `MessageNode("Loading…")` / それ以外は現行 `currentBranchChildren(mr)` を不変で流用。

**適用経路**: プリステップ = context 解決 + branch 読取 + effectiveRef 算出(いずれも never-throw)。
- context null(未解決)→ 即 `asyncExec` で `NoRepository` を適用(siblings 起動せず・Loading にしない)。破損 git dir は all-null branch(detached)として `Resolved` 側で「パイプライン無し」になり、これも Loading に留まらない。
- context 解決 → siblings 起動 + 初回 `asyncExec` で `Resolved(null, null)`(両 Loading)を適用し、各タスク完了ごとに `Resolved(latestMr, latestPipeline)` を適用。
- 防御的に、万一プリステップが将来例外を投げても(現契約では起きない)最上位 `try/catch` で捕捉しログして `NoRepository` に落とす(永久 Loading を作らない)。

**ブランチ snapshot は sibling 起動前に一度だけ取得して共有(Codex R2-#1 反映・確定)**: 現行 `GitLabSidebarView.fetchCurrentBranchInfo(context)` は内部で `currentBranchGitReader.read(context.gitDir)` を呼ぶ(`GitLabSidebarView:192`)。ここへパイプラインタスクが**別途もう一度 branch を読む**と、refresh 中に checkout が切り替わった場合に **MR=旧ブランチ / パイプライン=新ブランチ** という不整合な節を合成しうる。したがって:

- **共有プリステップ(sibling 起動前・1回)**: `RepositoryContextResolver` で `RepositoryContext`(`projectId: String` = エンコード済みパス、`remoteName`、`gitDir`)を解決し、`CurrentBranchGitReader.read(gitDir)` で `CurrentBranch(name, trackingBranch, upstreamRemote, headSha)` を読む。**この (context, branch) の組を両タスクに同じ値として渡す**。MR 経路は branch を再読しないよう、`fetchCurrentBranchInfo` を「渡された branch/context を使う」形にリファクタする(内部再読の除去)。
- **effective ref**(共有プリステップで算出、`CurrentBranchMrLookup` 準拠): `effectiveRef = trackingBranch?.takeIf { upstreamRemote == context.remoteName } ?: name`。`name == null`(detached)→ **パイプライン無し**。remote 一致判定は純関数に切り出し(または既存ロジック共用)、remote 一致/不一致/tracking 無し/detached をテスト(AC-4・§10)。
- **MR タスク**: 共有 (context, branch) を用いて現行の MR/closes-issues 取得 → `Result<CurrentBranchInfo>`。
- **パイプラインタスク**: `PipelineService.getLatestPipelineForRef(context.projectId, effectiveRef)`(null=No pipeline)→ 非 null なら `JobService.getJobsForPipeline(context.projectId, pipeline.id)` を **独立 `Result`** で取得 → `PipelineSnapshot(pipeline, jobsResult)`。

> 注: 下記 §7.1 の図は current-branch 節(ユニット B)のフローを示す。assigned roots(ユニット A = Issues+MRs、既存 Phase 3 の fetch)は独立ユニットとして並走し、完了時に同じ増分 apply でスロット更新される。

**増分描画で待ち時間を分離(Codex R4/R7 反映・確定)**: 「揃うまで待って一度に描画」すると、refresh の描画が遅い結果に律速される。現行 `refresh()` は **assigned Issues・assigned MRs・current-branch の3結果をすべて `await` してから単一の `applyResults` を1回だけ呼ぶ**(`GitLabSidebarView:150-186`)。したがって section 内で MR/pipeline を増分化するだけでは、**assigned Issues/MRs API が遅いとそこで律速**され AC-11 を満たせない(Codex R7)。そこで **apply を top-level ユニット単位の増分に一般化**する:
- **ユニット分割**: (A) assigned roots(Issues + MRs の対)、(B) current-branch 節(プリステップ + MR/pipeline 増分)。A と B は独立に適用する。
- **各ユニット完了時に `asyncExec`** を発行し、**既存のキャッシュスロット**(`cachedIssues` / `cachedMrs` / `cachedCurrentBranchSection`。onModeChanged 用に既存)を更新して `viewer.input` を全スロットから再構築する。未到達スロットは placeholder(assigned root は "Loading…" ルート、section は §6.6 の Loading)。
- 結果として **パイプライン行(ユニット B 内)は、assigned Issues/MRs(ユニット A)にも current-branch の MR にも律速されず**、パイプラインタスクの内部上限(§8.3)で表示される。
- MR 側の既存ページング遅延は **MR 行のみ**、assigned roots の遅延は **assigned root のみ**に限局(既存 Phase 3 の fetch 自体は非回帰で温存。変わるのは「揃うまで待つ単一 apply」→「ユニット単位の増分 apply」だけ)。

この top-level 増分 apply(スロット更新 + 全体再構築 + 世代/dispose 判定)も §10 の注入可能コーディネータに含め、ユニット完了順を fake scheduler で制御して検証する。

**並行・世代・dispose**: MR タスクとパイプラインタスクは共有 snapshot を入力に `supervisorScope` の sibling として並走(片方の失敗が他方をキャンセルしない)。各完了時の `asyncExec` は **世代 latest-wins + dispose ガード**(asyncExec 内で世代・`control.isDisposed` 再チェック)を通す(Phase 3 実装を流用)。同一世代内で MR/パイプラインの結果を保持する小さな可変状態(UI スレッド上でのみ更新)を持ち、到達済み分で節を再構築する。失敗は `logger.error`(§8.1 のログ規則)。この結果保持・合成・世代判定ロジックは §10 のとおり注入可能な純コーディネータに切り出して自動検証する。

**表示順(U-3・暫定確定)**: 節内は **パイプライン行 → MR 行 → 閉じる Issue 行**(CI 状態を最上部に。VSCode のステータスバー相当を節頭に置く意図)。レビューで異論あれば MR 先頭へ変更可。

---

## 7. 処理フロー

### 7.1 現ブランチ最新パイプラインの解決

```
サイドバー refresh / ビューオープン
  └─ (IO coroutine, 世代 g をキャプチャ)
       │
       ├─[共有プリステップ・1回] RepositoryContext(projectId, remoteName, gitDir)  ※どちらも never-throw
       │                          + CurrentBranch(name, trackingBranch, upstreamRemote, headSha)
       │        ・context 未解決(null。解決失敗も畳まれる)→ 即 asyncExec: NoRepository ("Select a repository") ←終端
       │        ・context 解決 → effectiveRef 算出 → 初回 asyncExec: Resolved(null,null)=両 Loading → siblings 起動
       │             (破損 git dir は all-null branch=detached → Resolved 側で「パイプライン無し」。Loading に留まらない)
       │             effectiveRef = trackingBranch.takeIf{ upstreamRemote == remoteName } ?: name
       │                          (name==null → detached → パイプライン無し)
       │             ↓ (context, branch, effectiveRef) を両タスクに同一値で渡す
       │
       ├─[MR タスク]  共有 snapshot で MR/closes-issues → Result<CurrentBranchInfo>  (branch 再読しない)
       │
       └─[PIPELINE タスク] → Result<PipelineSnapshot?>
             ├─ PipelineService.getLatestPipelineForRef(projectId, effectiveRef)
             │     ・0 件 → null (No pipeline) / 403・404 → 失敗(§8.1)
             └─ 非null → JobService.getJobsForPipeline(projectId, pipeline.id) を独立 Result・独立期限(≤15s)で
       │
       │  (supervisorScope: 片方失敗は他方を巻き込まない。★各タスクは完了ごとに個別に asyncExec)
       ↓
       各タスク完了時に asyncExec(増分描画):
         ・世代 g が最新でない / control.isDisposed → 破棄
         ・そうでなければ 到達済み分で buildCurrentBranchSection(latestMr?, latestPipeline?) を再構築
           (未到達パートは "Loading…"。パイプライン行は MR の遅延に律速されない/逆も同様)
```

VSCode パリティ注記(Codex #2 反映): VSCode は「MR があれば MR パイプライン、無ければ ref パイプライン、iid の大きい方」を採る(`get_pipeline_and_mr_for_branch.ts`)。PR-1 は **effective-ref による ref 解決のみ**に簡約(§3・U-1)。ただし ref は naive な tracking 名ではなく、**Phase 3 の effective-ref 規則(remote 一致時のみ tracking、他はローカル名、detached は無し)を必ず適用**する。MR パイプライン優先(higher-iid)は後続。

### 7.2 activation(ブラウザで開く)

**現行機構の事実(Codex #7 反映)**: `GitLabSidebarView` は `viewer.addDoubleClickListener` のみを登録し、その中で `node.activationUrl` を `openInBrowser(url)` に渡す。**Enter/default-selection/key リスナは存在しない**。

- したがって PR-1 の activation は **double-click のみ**とする(既存 `IssueNode`/`MergeRequestNode`/`OverviewNode` と同じ挙動。パイプライン固有ではなく既存サイドバー全体の仕様に合わせる)。`PipelineNode`/`JobNode` の `activationUrl`(webUrl)を既存 double-click 経路がそのまま処理する。新規ハンドラ不要。
- `StageNode` は `activationUrl = null`(非activatable、展開/折り畳みのみ)。
- **キーボード(Enter)での activation は PR-1 対象外**(全サイドバーノード共通の後続改善。FR-4/AC-3 も double-click に統一)。

---

## 8. 横断的関心事

### 8.1 認証と認可(Codex #6 反映・確定)

- 認証は `GitLabApiClient` 既存経路(PAT/OAuth トークンを client 内で付与)。本 PR で新たな認証コードを追加しない。
- **HTTP status の分類を一意に定める**(0件と認可障害を混同しない):
  - **200・空配列**(パイプライン 0 件)= 正常の「No pipeline」→ **パイプライン行を出さない**。
  - **403 / 404**(token scope 不足・membership 不足・private・存在秘匿の 404)= 失敗。**存在を推測させない共通の利用者向け文言**(例 `"Unable to load pipeline"`)の `MessageNode` を出す(private project の存在有無を UI に露出しない)。
  - **その他エラー**(5xx・ネットワーク・パース)= 同じく `MessageNode` の一般的失敗文言。
- **ログ規則(Codex R2-#4 反映・確定)**: `logger.error` に **HTTP status・endpoint 種別(`pipelines`/`jobs`)** を記録。**response body とトークンは記録しない**(NFR-3)。
  - **403/404(存在秘匿対象)では project 識別子(エンコード済みパス)をログに残さない**。private project の存在を秘匿する 404 でパスを記録すると R-5 と矛盾するため。必要なら**非可逆な相関 ID**(パスから復元不能なハッシュ等)に留める。
  - 具体的な project パスをログに残してよいのは **存在秘匿の対象外と確認できるエラー**(5xx・ネットワーク・パース失敗など)に限定する。
- 0件・403/404・その他の**区別を純ロジックテストで固定**(§10・AC-7)。API 呼び出し側で status を判別できるよう、REST 経路は status を保った失敗(例外種別 or 分類済み結果)を上位へ渡す。

### 8.2 エラー処理・並行処理・障害分離

- REST 失敗(ネットワーク/HTTP エラー/JSON パース失敗)は View のコルーチンが `runCatching` で捕捉。パイプライン取得の失敗は**パイプライン行のみ**を落とし、MR/Issue 行・他節を保持(Phase 3 の supervisorScope パターン)。
- 世代 latest-wins: refresh 連打時、古い世代の asyncExec は描画前に破棄(Phase 3 実装流用)。
- dispose ガード: asyncExec 実行時に `control.isDisposed` を再チェック(ビュークローズ競合)。
- ステージグルーピング・status マップは never-throw(未知 status は Unknown)。

### 8.3 タイムアウト・リトライ・冪等性(Codex #8 反映・確定)

- **リクエスト単位タイムアウト**: `GitLabApiClient` 既存の HTTP タイムアウト(30s/リクエスト)に従う(本 PR で変更しない)。
- **総所要時間の上限**: `fetchListFromApi` は `MAX_PAGES=20` × 30s = 最悪約 10 分になりうる。PR-1 では:
  - `getLatestPipelineForRef` は **単一ページ(1件)取得に限定**(§6.4)→ ページングによる長時間化を回避。
  - **jobs の期限は「jobs 取得だけ」に掛ける(Codex R2-#2 反映)**: パイプライン取得成功後の `getJobsForPipeline` のみを期限付き独立 `Result` にする。**節取得全体を `withTimeout` で包まない**(包むと jobs タイムアウトで取得済みパイプライン行まで失われ、§6.5/AC-5「ジョブ単独失敗はパイプライン行を残す」に反する)。jobs のタイムアウト/失敗は `jobsResult` の失敗となり、`buildPipelineNode` がパイプライン行を残して子に "Failed to load jobs" を出す。
  - **ページ間 deadline 検査(Codex R2-#3 反映)**: `fetchListFromApi` は非 suspend の `while` ループ内で同期 `sendPage` を連続実行し、**ページ間に cancellation check も suspension point も無い**(`GitLabApiClient:33-48` で確認)。そのため coroutine の `withTimeout` は実行中ループを止められない。jobs のページングには **REST ループ自体に deadline 検査を持つ経路**を用いる(ページ取得ごとに経過時間を確認し超過で打ち切る、`fetchListFromApi` に deadline 付きの派生を足すか jobs 専用の有界取得を新設)。
  - **具体的な期限値(Codex R3 反映・確定)**: **jobs 取得全体の soft deadline = 15 秒**(ページ取得の合間に経過時間を検査し、15 秒を超えたら以降のページ取得を打ち切って `jobsResult` を失敗扱いにする)。個々のリクエストは既存の 30 秒/リクエスト HTTP タイムアウトに従う。**ユーザーがパイプライン行の jobs 結果を見るまでの最大待ち時間 = 15 秒(deadline)+ 進行中 1 リクエスト分の超過(最大 30 秒)≒ 45 秒**(この時点で jobs は失敗表示になりパイプライン行は残る=§6.5)。この 15 秒はチューナブル定数として実装し、deadline テストの期待値に用いる(AC-10)。`getLatestPipelineForRef` は単一リクエストのため 30 秒/リクエストで有界。
- **キャンセルの限界(明示)**: 個々の `httpClient.send` は同期で、coroutine キャンセル/`withTimeout`/dispose は**実行中の 1 リクエストは中断しない**。したがって「進行中の 1 ページ」は最後まで走り(上記 max 待ち時間に反映)、その結果は asyncExec の世代判定で破棄される(Phase 3 と同じ)。ページ間 deadline 検査で**次ページ以降**は止められる。残る (a) refresh 連打時の重複取得、(b) 取得の直列化/共有(`GitOperationGuard` 相当)、(c) REST 層の完全なキャンセル対応(キャンセル可能な非同期 HTTP 経路)は**後続候補**。
- **リトライ**: なし(GET・read-only。失敗時は次の手動 refresh)。
- **冪等性**: すべて GET のため自明に冪等。副作用なし。

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
  - モデル(Gson): 欠落キー→null(既定値無視)の挙動、`allowFailure` null→false、`stage`/`name` null の既定ラベル。
  - `buildPipelineNode`: **id 昇順ソート → ステージ初出順グルーピング**の決定的順序(retry で id が混在するケース、並列ジョブ、複数ページ結合、null stage)、ジョブ 0 件、`jobsResult` 失敗時にパイプライン行が残ること。
  - `buildCurrentBranchSection`: **MR とパイプラインの独立合成**(MR 無し+パイプライン有り、パイプライン失敗時に MR/Issue が残る、両失敗、パイプライン成功&null=行なし)。
  - effective-ref 純関数: remote 一致→tracking / 不一致→ローカル名 / tracking 無し / detached→無し。
  - status 分類: 200空=0件(行なし)、403/404=共通失敗文言、その他=失敗(§8.1・AC-7)。※ HTTP はフェイク client。
- **障害分離・競合の自動検証(Codex #9 反映・確定)**: 世代 latest-wins・dispose 判定・2 タスク結果合成を **SWT widget から分離した注入可能な純コーディネータ(state reducer)** に切り出し、**fake scheduler + fake client** で「完了順の逆転」「片側のみ失敗」「stale(古い世代)破棄」「dispose 中の到着」を自動テストする。SWT 依存の `asyncExec`/widget 反映のみを最小の実機確認として残す。
- **SWT/coroutine 統合(fable)**: 上記コーディネータを配線する薄い View 層は headless で SWT 未ロードのため env 失敗になりうる(既存 sidebar テストと同様)。実機で手動検証。
- **手動検証(PR 説明文に記載、ユーザー実機)**: パイプラインのあるブランチをチェックアウト → サイドバーに Pipeline → Stage → Job が出る / status 表示 / パイプライン・ジョブ double-click でブラウザが開く / パイプライン無しブランチで行が出ない / refresh で更新。
- **検証 3 点(既存運用)**: 対象テスト PASS / 全体失敗数が develop と同数(36)/ 変更ファイルに detekt 指摘ゼロ。

---

## 11. 受け入れ条件

- AC-1: パイプラインのある現ブランチで、サイドバー「For current branch」節に `Pipeline #<id> · <status>` が表示され、展開すると Stage → Job が出る。
- AC-2: 各ノードのラベルにステータス表示名が正しく出る(§6.3 の 13 種 + allow_failure 特例)。
- AC-3: パイプライン **double-click** でパイプライン Web ページ、ジョブ double-click でジョブ Web ページがブラウザで開く(Enter は対象外)。
- AC-4: パイプラインが無いブランチ、detached HEAD、tracking 無しブランチ、**tracking remote が context と不一致のブランチ**ではローカル名で解決/パイプライン無しが正しく判定され、MR/Issue の既存表示は不変。
- AC-5: パイプライン/ジョブ取得失敗が MR/Issue 行・他節を巻き込まない。MR 無しでもパイプライン行は独立に出る。ジョブのみ失敗時はパイプライン行が残りジョブ位置に失敗表示。
- AC-6: `./gradlew build` で detekt 0、headless test の全体失敗数が develop と同数。
- AC-7: 200空(0件)=パイプライン行なし、403/404=存在を漏らさない共通失敗文言、が区別される(純ロジックテストで固定)。
- AC-8: ステージ/ジョブ表示順が **id 昇順→ステージ初出順**で決定的(retry・並列・複数ページで安定)。
- AC-9: 世代逆転・片側失敗・dispose・stale 破棄が注入可能コーディネータの自動テストで再現・検証される。
- AC-10: jobs 取得の soft deadline = 15 秒。fake client で 15 秒超過時にページングが打ち切られ `jobsResult` が失敗となり、**パイプライン行は残り**子に "Failed to load jobs" が出ることを自動テストで検証する(最大待ち時間 ≒ 15s + 進行中1リクエスト分)。
- AC-11: 増分描画。パイプライン行の表示は **(a) current-branch の MR 完了にも (b) assigned Issues/MRs ルートの完了にも律速されない**。パイプラインが他ユニットより先に完了した場合、パイプライン行が表示され未完了ユニットは "Loading…" になることを、fake scheduler でユニット完了順を制御して検証する(A=assigned roots / B=current-branch 節、B 内で MR/pipeline)。
- AC-12: プリステップ終端状態(never-throw 前提)。(a) リポジトリ未解決(context null)→ "Select a repository" が即時に出て "Loading…" にならない(現行挙動を保持)、(b) 破損/読取不能 git dir → all-null branch(detached)として "パイプライン無し" になり永久 Loading にならない。いずれも sibling 未起動でも Loading に留まらないことを検証する(config-aware エラー表示は never-throw 契約変更を要するため PR-1 対象外)。

---

## 12. 未決事項(Codex レビュー反映後の残件)

- **U-1(方針確定済み)**: PR-1 は **effective-ref による ref 解決のみ**(MR パイプライン優先 + higher-iid は後続)。ただし ref は Phase 3 の effective-ref 規則を適用(§6.6・§7.1・Codex #2 反映)。
- **U-2(実装時確定)**: (a) `getLatestPipelineForRef` の単一ページ取得経路(`GitLabApiClient` に単ページ配列取得を足すか `fetchObject` に配列型を通すか)、(b) jobs のページ間 deadline 検査付き有界取得経路(`fetchListFromApi` の deadline 付き派生 or jobs 専用メソッド)を、既存 `sendGet`/`fetchObject`/`fetchListFromApi` の実装を見て確定(Codex #8/R2-#3 反映)。GitLab jobs API の実際の返却順も実装時に実 API/docs で確認(表示は返却順に非依存=§6.5)。
- **U-3(暫定確定)**: 節内表示順 = **パイプライン → MR → Issue**(§6.6)。レビューで異論あれば MR 先頭に変更可。
- **U-4**: `com.gitlab.eclipse.ci` サブパッケージ新設の可否(CLAUDE.md「ディレクトリ構成変更禁止」= 既存 `com.gitlab.eclipse.*` 配下への追加は許容と理解。確認)。
- **U-5(暫定確定)**: ステージノードのラベルは**ステージ名のみ**(集約 status は出さない)。集約用 priority はモデルに保持のみ(将来利用)。

---

## 13. 想定されるリスク

- **R-1(検出困難系)**: View 統合の世代/dispose/障害分離。§10 の注入可能コーディネータ + fake scheduler/client で**自動検証可能な範囲を最大化**し(Codex #9 反映)、残る SWT 反映のみを実機手動検証。実装・レビューは fable。
- **R-2**: 長時間ページング。`getLatestPipelineForRef` を単一ページ化、節取得に soft deadline(§8.3・Codex #8 反映)。同期 send の非中断は受容済み制限として明記。
- **R-3**: ステージ順の解釈違い。**API 返却順に依存しない決定的順序(id 昇順→ステージ初出順)**をクライアントで確定(§6.5・Codex #4 反映)。
- **R-4**: ref 簡約(U-1)による VSCode との微差(MR パイプライン優先の非実装)。effective-ref 規則は適用済み(Codex #2 反映)。higher-iid は後続として台帳・PR 説明に明記。
- **R-5**: 認可障害と 0 件の混同(§8.1・Codex #6 反映で分類を確定)。存在秘匿(private 404)を UI **にもログにも**漏らさない(Codex R2-#4 反映: 403/404 ではエンコード済みパスをログに残さない)。
- **R-6**: refresh 中の checkout 切替で MR とパイプラインがブランチ不整合(Codex R2-#1 反映)。ブランチ snapshot を sibling 起動前に 1 回だけ取得し両タスクで共有(§6.6・§7.1)。
- **R-7**: jobs ページングの長時間化。jobs 専用期限(15s)+ ページ間 deadline 検査で総時間を有界化(§8.3・Codex R2-#2/#3/R3 反映)。進行中 1 リクエストの非中断は受容済み制限。
- **R-8**: refresh 全体の待ち時間が遅い結果に律速(Codex R4/R7 反映)。**top-level ユニット単位の増分描画**(A=assigned roots / B=current-branch 節)で各パートを揃い次第表示。パイプライン行は assigned Issues/MRs にも current-branch MR にも律速されない(§6.6・AC-11)。各 fetch 自体は非回帰。
- **R-9**: 増分描画の "Loading" とプリステップ未解決の混同で永久 Loading(Codex R5/R6 反映)。節入力を sealed 型(`NoRepository`/`Resolved`)化し、未解決=Select a repository を**即時終端描画**。プリステップ API は never-throw と確認済みで破損は未解決/detached に畳まれる(§6.6・§7.1・AC-12)。失敗の明示表示は never-throw 契約変更を要する後続。

---

## 14. Codex 設計レビュー反映履歴(PR #33・commit `d43e0c5`)

| # | severity | 指摘 | 反映(実コードで検証済み) |
|---|---|---|---|
| 1 | P1 | projectId/ref 解決経路の具体化 | §6.4/§6.6: `RepositoryContext.projectId` は**エンコード済みパス `String`**(数値でない)。サービスは String を受理、数値 ID 解決の追加往復なし。ref は git reader から |
| 2 | P1 | tracking remote 不一致時の ref | §6.6/§7.1: Phase 3 の **effective-ref 規則**(remote 一致時のみ tracking、他はローカル名、detached=無し)を適用。純関数テスト化 |
| 3 | P1 | MR とパイプラインの独立合成 | §6.6: `buildCurrentBranchSection(mrResult, pipelineResult)` に分離。MR 無しでもパイプライン表示、片側失敗が他方を巻き込まない。ジョブ単独失敗はパイプライン行を残す |
| 4 | P1 | API 返却順をステージ順としない | §6.5: **id 昇順→ステージ初出順**の決定的順序に変更。返却順に非依存。retry/並列/複数ページをテスト |
| 5 | P1 | REST モデルを Gson 方針に | §6.2: **Gson `@SerializedName`**(Jackson 依存なし)。欠落キー→null(既定値無視)を nullable + null ガード + テストで担保 |
| 6 | P1 | 403/404 の表示・監査 | §8.1: 200空=0件(行なし)/ 403・404=**存在を漏らさない共通失敗文言** の分類を確定。ログは status+endpoint+パスのみ、body/token 非記録 |
| 7 | P2 | Enter activation を過信しない | §7.2/FR-4: 現行 view は double-click listener のみ。PR-1 は **double-click に限定**、Enter は全ノード共通の後続 |
| 8 | P2 | ページング総期限・キャンセル | §8.3: latest は単一ページ化、節取得に soft deadline。同期 send 非中断は受容済み制限として明記、直列化/REST キャンセルは後続 |
| 9 | P1 | 障害分離/競合の自動検証 | §10: 世代/dispose/合成を**注入可能な純コーディネータ**に切り出し、完了順逆転・片側失敗・stale・dispose を自動テスト |

### ラウンド2(commit `d129f0d` に対する追加指摘)

| # | severity | 指摘 | 反映(実コードで検証済み) |
|---|---|---|---|
| R2-1 | P1 | MR とパイプラインで同じブランチ snapshot を共有 | §6.6/§7.1: 現行 `fetchCurrentBranchInfo` が内部で branch を再読(`GitLabSidebarView:192`)。**sibling 起動前に (context, branch) を 1 回取得して両タスクへ同一値で渡す**(checkout 切替中の MR=旧/パイプライン=新 不整合を防止) |
| R2-2 | P2 | jobs timeout でもパイプライン行を保持 | §8.3: 期限を**節全体でなく jobs 取得だけ**に掛ける。jobs 失敗は `jobsResult` 失敗 → §6.5 でパイプライン行を残す |
| R2-3 | P2 | ページ間でも deadline を検査 | §8.3: `fetchListFromApi` は非 suspend 同期ループでページ間に停止点が無い(`GitLabApiClient:33-48`)→ `withTimeout` で止まらない。**REST ループ自体に deadline 検査**を持つ有界取得経路を用いる(U-2) |
| R2-4 | P2 | 存在秘匿エラーで project path をログに残さない | §8.1: **403/404 ではエンコード済みパスをログに記録しない**(非可逆な相関 ID に留める)。具体パスは存在秘匿対象外のエラーに限定。R-5 更新 |

### ラウンド3(commit `f6f229d` に対する追加指摘)

| # | severity | 指摘 | 反映(実コードで検証済み) |
|---|---|---|---|
| R3-1 | P2 | jobs の総期限値を確定する | §8.3/AC-10: **jobs 総 soft deadline = 15 秒**(チューナブル定数)を明記。最大待ち時間 ≒ 15s + 進行中1リクエスト(最大30s)≒ 45s。deadline テストの期待値に使用 |

### ラウンド4(commit `de042a4` に対する追加指摘)

| # | severity | 指摘 | 反映(実コードで検証済み) |
|---|---|---|---|
| R4-1 | P2 | refresh 全体の待ち時間も有界化 | §6.6/§7.1/AC-11: 45s は jobs のみで、両 Result 待ち + パイプライン直列(latest→jobs)だと refresh 全体が有界でない。**増分描画**に変更(各タスク完了ごとに asyncExec、未到達は "Loading…")→ パイプライン行は MR に律速されず内部上限で表示。MR 遅延は MR 行のみに影響(既存挙動温存) |

### ラウンド5(commit `4410003` に対する追加指摘)

| # | severity | 指摘 | 反映(実コードで検証済み) |
|---|---|---|---|
| R5-1 | P2 | 共有プリステップ失敗の終端状態を定義 | §6.6/§7.1/AC-12: 現行は `currentBranchResult==null` を "Select a repository" として扱う(`GitLabSidebarView:205-210`)。増分の "Loading" と衝突し、プリステップが sibling を起動できないケースで永久 Loading になる問題。節入力を **sealed 型**にし、未解決は siblings を起動せず**即時終端描画**。現行の "Select a repository" を保持 |

### ラウンド6(commit `cedff33` に対する追加指摘)

| # | severity | 指摘 | 反映(実コードで検証済み) |
|---|---|---|---|
| R6-1 | P2 | 握り潰されたプリステップ失敗を終端エラーへ | §6.6/§7.1/AC-12: `CurrentBranchGitReader.read` は全例外を握り潰し all-null 返却(`:57-60`)、`RepositoryContextResolver` は失敗を null に畳む(`:48-55,100-167`)を確認 → **`PreStepFailure` は到達不能**。返却値も detached と破損を区別不能。当初案の `PreStepFailure` を**削除**し sealed を `NoRepository`/`Resolved` の2状態に。破損は未解決 or detached(パイプライン無し)として現れる(既存 never-throw 挙動)。config-aware 失敗表示は never-throw 契約変更を要するため PR-1 対象外(後続) |

### ラウンド7(commit `1ecd895` に対する追加指摘)

| # | severity | 指摘 | 反映(実コードで検証済み) |
|---|---|---|---|
| R7-1 | P2 | assigned Issue/MR の完了待ちから増分描画を分離 | §6.6/AC-11: 現行 `refresh()` は assigned Issues/MRs/current-branch の3結果を全 `await` してから単一 `applyResults`(`GitLabSidebarView:150-186`)→ section 内だけ増分化しても assigned が遅いと律速。apply を **top-level ユニット単位の増分**に一般化(A=assigned roots / B=current-branch 節)。既存キャッシュスロットを使い各ユニット完了時に再構築。パイプライン行は assigned にも MR にも律速されない |

---

## 付録: Phase 4 全体の分割(本書は PR-1 のみ)

| PR | 内容 | 主な追加 |
|---|---|---|
| **PR-1(本書)** | パイプライン表示(read-only、`status.pipeline` 相当) | REST モデル・CiStatus・GET サービス・サイドバーノード |
| PR-2 | パイプライン/ジョブ操作 | `GitLabApiClient` POST 追加、retry/cancel/play/create、retryFailedPipelineJobs、pipelineActions メニュー、contextAction |
| PR-3 | アーティファクト DL + ジョブトレース | downloadArtifacts(ブラウザ)、trace 表示(Console) |
| PR-4 | CI lint | validateCIConfig / showMergedCIConfig(`/ci/lint` POST) |
