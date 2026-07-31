# create pipeline 設計書（Phase 4 / CI）

- リポジトリ: `sumikof/gitlab-eclipse-plugin`
- ベースブランチ: `develop`（tip = `d080998`）
- 関連 issue: #12（Phase 4 Pipelines/CI）/ #7（パリティ台帳 D14）/ #8（ロードマップ）
- 位置づけ: Phase 4 は薄い縦切り 4 PR。PR-1 表示（#34）・PR-2 操作 retry/cancel/play（#36）はマージ済み。本設計は **PR-2 から分離した create pipeline**（VSCode `triggerPipelineAction` の `create` 分岐に対応）。

> 本書は設計レビュー専用。実装 PR・マージ先には含めない。

---

## 1. 背景と目的

VSCode 拡張 `gitlab-workflow` v6.85.3 は `triggerPipelineAction`（QuickPick）の中で「Create New Pipeline from Current Branch」を提供し、`POST /projects/{restId}/pipeline?ref={trackingBranch}` で現ブランチのトラッキングブランチに対して新規パイプラインを生成する（実ソース: `out/gitlab-vscode-extension/src/desktop/commands/trigger_pipeline_action.ts` L26-28, L54-58 / `.../gitlab/gitlab_service.ts` L911-916）。Eclipse プラグインにこの機能パリティを追加する。

PR-2（retry/cancel/play）は既存パイプライン/ジョブ**ノードの context menu** から起動する操作だった。create は **既存パイプラインが 1 件も無い状態でも起動できる別 UI 面**が必要で、かつ **非冪等（POST /pipeline は呼ぶたびに新パイプラインを生成）**という点が retry/cancel と根本的に異なるため、独立 PR とした。

## 2. 対象範囲

- サイドバービュー `GitLabSidebarView` の toolbar に「Create Pipeline from Current Branch」ボタン（コマンド `com.gitlab.eclipse.commands.CreatePipeline`）を追加。
- クリック時: リポジトリ文脈解決 → 現ブランチのトラッキングブランチ（effective ref）解決 → 確認ダイアログ → `POST /projects/{id}/pipeline?ref={ref}` → 成功で起動元ウィンドウの sidebar を refresh。
- コマンドは name/category を持たせ Quick Access からも起動可能にする。

## 3. 対象外

- CI 変数（`variables[...]`）を伴う生成。VSCode の当該コマンドも変数なしの単純生成であり、パリティ対象外（将来拡張候補）。
- 生成された pipeline の詳細表示・ブラウザオープン。VSCode 同様、成功時は sidebar refresh のみ。
- artifacts DL / job trace（PR-3）、CI lint（PR-4）。
- タグ（tag ref）指定生成。VSCode は tracking branch のみを渡す（tag は current_branch_data_provider 側の別文脈）。本 PR も branch ref に限定する。

## 4. 現在の課題

- create を起動できる UI 面が無い（PR-1/PR-2 はノード前提）。
- 非冪等操作の二重生成リスク（連打・timeout 二重発火）に対する方針が未確定。
- 表示系（GET）は project id（git remote 由来の namespace path）を設定済み接続に対して読むだけで、remote と設定済み接続のインスタンス不一致は 404 として無害に吸収される。しかし **create（POST）では不一致が「別インスタンスの同名 project への実パイプライン誤生成」になり得る**（無害でない）。

## 5. 要件

### 機能要件
- FR-1: サイドバー toolbar のボタン（コマンド）で create を起動できる。ノードの有無に依存しない。
- FR-2: ref は現ブランチのトラッキングブランチを `EffectiveRef.resolve` で解決する（remote 一致検証込み、upstream 無しはローカル短名にフォールバック）。detached HEAD（ローカル名なし）は生成せず通知して中止。
- FR-3: 対象プロジェクトは `RepositoryContextResolver.selectActiveContext`（interactive）で解決する。0 件=通知して中止、1 件=それを使用、複数=選択ダイアログ。
- FR-4: POST 前に確認ダイアログを表示し、解決済みの ref と project（webUrl）を提示する。Cancel で中止。
- FR-5: 生成 POST は `PipelineActionService.create(connection, projectId, ref)` = `POST /projects/{projectId}/pipeline?ref={ref}`。
- FR-6: 成功時は起動元ウィンドウの sidebar を refresh し、「Pipeline created」を通知。失敗時は監査ログ + 汎用失敗通知。
- FR-7: 生成の重複を抑止する。同一（instance, project, ref）への in-flight 中の再クリックは無視する。
- FR-8: create の POST は、リポジトリ文脈のインスタンス（git remote 由来）が現在設定済み接続のインスタンスと一致する場合のみ実行する（同一インスタンス・ゲート）。不一致なら POST せず通知して中止。

### 非機能要件
- NFR-1: UI スレッドをブロックしない。接続捕捉（OAuth token refresh を誘発しうる）と全 HTTP は UI 外で実行。
- NFR-2: 監査ログに token / 生レスポンスボディを出さない。
- NFR-3: 共有 `Dispatchers.IO` CoroutineScope を汚染しない（例外を launch 外へ逃がさない、`CancellationException` は再送）。
- NFR-4: ディレクトリ構成・ビルド構成を変えない（新規依存追加なし。既存 `com.gitlab.eclipse.*` パッケージ内に追加）。

## 6. 前提条件と制約

- `GitLabApiClient.post(path, query, connection)` は既に query パラメータ対応済みで、`buildUri` が値を URL エンコードする（`api/GitLabApiClient.kt` L188-199, L243-248）。→ `?ref=` は query マップで渡すだけでよい。
- `PostResult` は httpStatus + correlationId のみ保持しボディを parse しない（`api/PostResult.kt`）。→ 生成 pipeline の id/web_url は取得しない（VSCode パリティ）。
- `captureConnection()` は config 世代 seqlock で `ConnectionSnapshot(instanceUrl, token, authFingerprint, configGeneration)` を原子捕捉（`api/GitLabApiClient.kt` L168, `api/ConnectionSnapshot.kt`）。cross-store `(新URL,旧token)` を封鎖済み。
- `EffectiveRef.resolve(branch, remoteName)` = `branch.trackingBranch.takeIf { upstreamRemote == remoteName } ?: branch.name`（detached は null）(`mergerequests/EffectiveRef.kt`)。
- `CurrentBranchGitReader.read(gitDir)` は never-throws で `CurrentBranch(name, trackingBranch, hasUpstream, upstreamRemote, headSha)` を返す（`mergerequests/CurrentBranchGitReader.kt`）。
- `RepositoryContext` は `projectId`（namespace path をエンコードした REST 用 id）, `instanceUrl`, `remoteName`, `gitDir`, `webUrl` を保持。
- 制約: これは VSCode 参照コピー（`out/`）読み取り専用、公式パッケージ体系維持、ドキュメント非コミット（本書はレビュー専用ブランチのみ）。

## 7. システム構成

```
[GitLabSidebarView toolbar]
  └ command com.gitlab.eclipse.commands.CreatePipeline
       └ CreatePipelineHandler (AbstractHandler / thin SWT shell)         ★新規
            ├ RepositoryContextResolver.selectActiveContext  (再利用: project/instance/remote 解決 + picker)
            ├ CurrentBranchGitReader.read + EffectiveRef.resolve (再利用: ref 解決)
            ├ 確認ダイアログ (SWT MessageDialog.openConfirm)                ★新規(小)
            ├ InFlightWriteGuard (再利用: instance+"createPipeline"+project+ref で直列化)
            ├ captureConnection() (再利用: 接続原子捕捉)
            ├ sameConfiguredInstance(ctxUrl, connUrl) (純関数)              ★新規
            ├ CreatePipelineOrchestrator (SWT フリー・順序保証テスト対象)     ★新規
            │    ├ captureConnection → sameConfiguredInstance ゲート → classifyWrite
            │    └ PipelineActionService.create(conn, projectId:String, ref)  ★新規(+1メソッド)
            │         └ apiClient.post("/projects/{id}/pipeline", {ref}, conn)
            ├ buildCreateAuditMessage (create 用監査・token 非出力)          ★新規
            └ classifyWrite / 成功refresh (再利用)
```

## 8. コンポーネントの責務

### 8.1 `PipelineActionService.create`（既存クラスに 1 メソッド追加）
```kotlin
fun create(connection: ConnectionSnapshot, projectId: String, ref: String): PostResult =
  apiClient.post("/projects/$projectId/pipeline", query = mapOf("ref" to ref), connection = connection)
```
- 純粋なパス組立 + delegation。例外は呼び出し元へ伝播。
- **`projectId` は `String`**（Codex P1 反映）。create の project id は `RepositoryContext.projectId`（= `encodeProjectId(namespaceWithPath)` の `group%2Fproject` エンコード済み文字列, `RepositoryContext.kt` L14/L24）に由来し、表示系 `PipelineService.getLatestPipelineForRef(projectId: String)` と同型。既存 retry/cancel が `projectId: Long` を取るのは、それらが `PipelineNode.projectId`(pipeline JSON の数値 id)を使うためで、create とは供給源が異なる。数値 id への変換は行わない（不要な GET・失敗条件を増やさない）。パス組立時、`projectId` は既にエンコード済みなので二重エンコードしない（そのまま補間）。

### 8.2 `sameConfiguredInstance`（純関数・SWT フリー）
```kotlin
fun sameConfiguredInstance(contextInstanceUrl: String, connectionInstanceUrl: String): Boolean =
  normalizeInstanceUrl(contextInstanceUrl) == normalizeInstanceUrl(connectionInstanceUrl)
```
- FR-8 のゲート。`normalizeInstanceUrl`（既存・末尾スラッシュ正規化）を再利用。headless TDD 対象。

### 8.3 `CreatePipelineHandler`（thin SWT shell）
- `execute()`（UI スレッド）: `activeEditorFile()` を同期捕捉 → `selectActiveContext { context -> ... }`。
- context 解決コールバック（UI スレッド）で context が非 null のとき、後述のフロー（§9）を駆動。
- **ノードを前提としない**ため PR-2 の `launchCiWrite`（`WriteKey` + node fingerprint 照合）はそのまま使わず、create 専用の小 launcher を同パッケージに置く。共有部（`classifyWrite` / `InFlightWriteGuard` / 成功時 refresh ヘルパ `findSidebarViewIn`）は再利用できる形に抽出する（`findSidebarViewIn` を internal 化するなど、既存 PR-2 の挙動は不変）。監査ログは create の識別子（String projectId + ref）を扱う create 用のビルダを使う（§18）。

### 8.5 `CreatePipelineOrchestrator`（SWT フリー・ヘッドレステスト対象・Codex P1 反映）
- handler の非 UI ロジック（接続捕捉 → 同一インスタンス・ゲート → 分類 → 監査/結果分類）を SWT/Eclipse 非依存の純オーケストレータに抽出する。SWT 依存部（ダイアログ・通知・refresh・`Display.asyncExec`）は handler 側に残し、オーケストレータには「captureConnection を行う関数」「create を行う関数」「通知/refresh を表すコールバックまたは戻り値」を注入する。
- 目的: FR-8/AC-5 の**順序保証**（ゲートが POST より前に走り、不一致では `create()` が一度も呼ばれず、通知が出て、in-flight guard が解放される）をヘッドレスで自動検証可能にする（PR-2 の `WriteAction.kt` の SWT フリー core と同じ方針）。戻り値は sealed な結果型（`Created` / `ConnectionChanged` / `InstanceMismatch` / `Unstable` / `Failure`）とし、handler がそれを UI 作用へ写像する。

### 8.4 確認ダイアログ
- `MessageDialog.openConfirm(shell, "Create pipeline", "Create a new pipeline for '<ref>' in <webUrl>?")`。UI スレッド・モーダル・戻り値 Boolean。Cancel/false で中止。

## 9. 処理フロー（スレッド境界を明示）

```
UI:  toolbar クリック → execute()
UI:  activeEditorFile 捕捉（同期）
IO:  selectActiveContext の内部解決（JGit）           ── resolver 既定
UI:  コールバック: context==null → resolver が通知済み・終了
                    context!=null → 次へ
IO:  CurrentBranchGitReader.read(context.gitDir) → EffectiveRef.resolve(branch, context.remoteName)
UI:  ref==null → "Cannot determine the current branch (it may be a detached HEAD or the
                 repository could not be read). See the Error Log for details." 通知・終了
                 （detached と I/O/リポジトリ異常を単一文言に一般化。§13 参照。Codex P2 反映）
     ref!=null → 確認ダイアログ（ref, webUrl 提示）
                  Cancel → 終了
UI:  key = CreateWriteKey(normalizeInstanceUrl(context.instanceUrl), context.projectId, ref)
     InFlightWriteGuard.tryAcquire(key) == false → info ログ・終了（連打抑止）
IO:  connection = captureConnection()                 ── UnstableConnectionException → 通知・終了
     sameConfiguredInstance(context.instanceUrl, connection.instanceUrl) == false
        → CONNECTION_CHANGED 系メッセージ通知・POST せず終了
     classifyWrite { PipelineActionService.create(connection, context.projectId, ref) }
UI:  Success → 起動元ウィンドウ sidebar refresh + "Pipeline created" 通知 + 監査ログ(success)
     Failure → 監査ログ(failure) + 汎用失敗通知
finally: InFlightWriteGuard.release(key)   （成功/失敗/例外/キャンセル全経路）
```

### in-flight キー（O-1 を確定・Codex P1 反映）
create 専用のキー型を新設する:
```kotlin
data class CreateWriteKey(val instanceUrl: String, val projectId: String, val ref: String)
```
- 既存 `WriteKey(instanceUrl, targetKind, targetId: Long)` は再利用しない。create の対象は数値 id を持たない (projectId: String, ref: String) の組であり、Long への合成ハッシュは**衝突により異なる (project, ref) の並行実行をブロックし §16 と矛盾する**ため採らない。
- `instanceUrl` は `normalizeInstanceUrl` 済み。`projectId`/`ref` は生値をそのまま保持し、衝突しない（AC-4 で担保）。
- `InFlightWriteGuard` はキー型に依存しない（`Any` を key に取る）か、create 用の並行ガードを別途持つ。実装計画で `InFlightWriteGuard` の key 型汎用化 or 専用ガードのどちらかを確定する（挙動要件は本節で確定済み）。
- 監査ログの識別子はこのキーとは別に持つ（§18）。in-flight キーは「同時実行の同一性」、監査は「事後追跡の可読識別」で役割が異なる。

## 10. API / インターフェース

- REST: `POST /api/v4/projects/{projectId}/pipeline?ref={urlencoded ref}`（`buildUri` が `/api/v4` を前置・query を URL エンコード）。
- 認証: `ConnectionSnapshot.token`（`captureConnection()` 由来）。既存 POST 経路と同一。
- 成功判定: `GitLabApiClient.post` が 2xx を `PostResult` で返す。非 2xx は `GitLabApiException`（statusCode + correlationId）。
- 期待ステータス: 201 Created（GitLab）。403（CI 実行権限なし）/ 400（`.gitlab-ci.yml` 無効・無し）/ 404（project/ref 不明）は Failure として分類。

## 11. データモデル

- 新規永続データなし。`ConnectionSnapshot` は transient（既存）。`PostResult` は body を持たない（既存）。生成結果は sidebar refresh（別 GET）で反映。

## 12. トランザクション境界

- 単一 POST。ローカルにトランザクションは無い。サーバ側の pipeline 生成が唯一の副作用。ロールバック不可（生成済み pipeline のキャンセルは PR-2 の cancel で別途可能・本 PR の責務外）。

## 13. エラー処理

| 事象 | 挙動 |
|---|---|
| リポジトリ 0 件 | resolver が「No GitLab repository found」通知・中止 |
| リポジトリ複数 | 選択ダイアログ。Cancel で中止 |
| detached HEAD / branch 読取失敗（ref null） | 「Cannot determine the current branch…（detached または読取不能）See the Error Log.」に一般化した通知・中止。`CurrentBranchGitReader.read` は detached（clean・正常系）と I/O/リポジトリ異常（catch 経路で warn ログ済み）の双方を all-null に写像するため、ユーザー文言では両者を区別せず、詳細は Error Log に委ねる（Codex P2 反映）。異常系（削除/破損 gitDir・HEAD 不在）は `read()` の warn ログで追跡可能 |
| upstream 無し | ローカル短名にフォールバックして継続（EffectiveRef 既定） |
| 接続不安定（UnstableConnectionException） | 通知・POST せず中止 |
| インスタンス不一致（FR-8） | 通知・POST せず中止 |
| 403/400/404 等 POST 失敗 | `classifyWrite`→Failure。監査ログ + 「The action failed. Refresh the sidebar to check the current state.」 |
| timeout（HttpTimeoutException） | Failure("timeout")。監査ログ + 通知。**サーバ側で生成された可能性は受容制限（§16 冪等性）** |
| 未分類例外 | 共有 scope を汚さず catch、監査ログ(unexpected) + 通知 |

## 14. タイムアウトとリトライ

- POST は既存 HTTP クライアントの write タイムアウト設定に従う（PR-2 と同一経路）。
- **自動リトライは行わない**（非冪等のため。timeout 後の自動再送は二重生成を招く）。ユーザーが明示的に再クリックすることが唯一のリトライ。

## 15. 冪等性

- `POST /pipeline` は**非冪等**。同一 ref への複数回呼び出しは複数 pipeline を生成する。
- 緩和策: (1) 確認ダイアログ（誤操作・解決済み ref の目視検証を兼ねる）、(2) in-flight ガード（同一 key の同時再クリックを無視）。
- **受容する制限**: クライアント timeout 後にサーバ側で生成が成功していた場合、ユーザーが再クリックすると二重生成し得る。VSCode も同挙動でガードしない。PR-2 の「timeout 二重発火は受容」と同じ判断。PR 本文の既知の制限に明記する。

## 16. 並行処理

- `execute()` は UI スレッド。context 解決・branch read・connection 捕捉・POST は共有 IO scope の coroutine。
- in-flight ガードは `CreateWriteKey(normalizeInstanceUrl(instance), projectId, ref)` で同一 create の同時実行を直列化。
- **異なる (project, ref) の create は並行可能**（キーが projectId/ref を生値保持し衝突しないため。§9 のキー確定で担保）。
- `CancellationException` は再送（scope 死回避）、その他の例外は launch 内で終端 catch。

## 17. 認証と認可

- 認証: 設定済み接続の token（`captureConnection()`）。
- 認可: サーバ側で CI 実行権限を検証（403 で拒否）。クライアントは事前認可判定を行わない。
- 同一インスタンス・ゲート（FR-8）で、表示中リポジトリと異なるインスタンスへ token を送らない。

## 18. ログ、監視、監査

- 成功/失敗とも 1 行構造化ログを Error Log に出力（token 非出力）。フィールド: `action="create"`, `instanceUrl`(正規化), `projectId`(String, エンコード済みパス), `ref`, `outcome`, および http 由来時は `httpStatus`/`correlationId`。
- 既存 `writeAuditMessage(action, instanceUrl, projectId: Long, targetKind, targetId: Long, outcome)` は Long 前提で create の String projectId + ref を表現できない。**create 用の監査ビルダ**（`buildCreateAuditMessage(action, instanceUrl, projectId: String, ref, outcome)`）を新設する（SWT フリー・ヘッドレステスト対象、token/生ボディ非出力の不変条件は既存と同じ）。既存 `writeAuditMessage` は変更しない（retry/cancel/play への影響なし）。
- 連打で無視した場合は info ログ。

## 19. 障害時の復旧方法

- POST 失敗時: 状態不明。ユーザーは sidebar refresh で現状（生成されたか）を確認し、必要なら再クリック。
- 二重生成した場合: PR-2 の cancel で不要な pipeline をキャンセル可能。

## 20. 既存機能への影響

- `PipelineActionService` に create メソッド追加（既存 retry/cancel に影響なし）。
- PR-2 の共有ヘルパ（`findSidebarViewIn` 等）を internal 抽出する場合、既存 PipelineActionHandler/JobActionHandler の挙動は不変であること（リファクタは呼び出し関係の移動のみ）。
- plugin.xml に command 1 + handler 1 + toolbar 項目 1 + icon を追加。既存 toolbar（refresh/toggle）に併置。
- 新規依存なし。

## 21. 移行方法

- 移行不要（新機能追加のみ）。

## 22. ロールバック方法

- 実装 PR を revert すれば toolbar ボタン・command・service メソッドが消えるだけ。永続データ・設定なし。

## 23. テスト方針

- headless TDD（sonnet）:
  - `PipelineActionService.create` のパス/query 組立（projectId: String を二重エンコードしない・ref は query でエンコードされる）。
  - `sameConfiguredInstance` の正規化比較（末尾スラッシュ差は既存 `normalizeInstanceUrl` に準拠）。
  - `buildCreateAuditMessage` が token/生ボディを含まず必須フィールドを出す。
  - **`CreatePipelineOrchestrator`（SWT フリー・Codex P1 反映）: 同一インスタンス・ゲートの順序保証**。fake の captureConnection/create を注入し、(a) 不一致 snapshot では `create()` 呼び出し回数が 0・結果 `InstanceMismatch`、(b) 一致では `create()` が 1 回呼ばれ結果 `Created`、(c) `UnstableConnectionException` では `create()` 0 回・結果 `Unstable`、を検証。in-flight guard の解放は handler 側 finally のため fable、ただしオーケストレータが guard 解放コールバックを受ける設計なら「全結果経路で解放コールバックが 1 回呼ばれる」もヘッドレスで検証する。
- fable レビュー（headless 検証不能領域）: `CreatePipelineHandler` のスレッド境界（UI↔IO ホップ）、確認ダイアログ、connection pin、in-flight ガード解放（全経路 finally）、共有 scope 非汚染、起動元ウィンドウ refresh。
- ref 解決は `EffectiveRef` 既存テストで担保（追加不要）。
- plugin.xml は jshell で well-formed 検証。生成 MANIFEST に新規依存が増えないこと（追加しないため）を確認。
- 手動検証（実機）: 手順を実装 PR 本文に記載。

## 24. 受け入れ条件

- AC-1: サイドバー toolbar の Create ボタンから、現ブランチ（トラッキングブランチ）に対し pipeline が生成され、成功後 sidebar に反映される。
- AC-2: detached HEAD では生成されず通知される。
- AC-3: 確認ダイアログの Cancel で POST が発行されない。
- AC-4: 連打しても in-flight 中の 2 回目は無視される（単一 POST）。**異なる (project, ref) の create は同時に実行できる**（`CreateWriteKey` が生値保持で衝突しないことをヘッドレステストで担保）。
- AC-5: 設定済み接続がリポジトリのインスタンスと異なる場合、POST されず通知される。**`CreatePipelineOrchestrator` のヘッドレステストで、不一致 snapshot では `create()` が一度も呼ばれない（順序保証）ことを検証する**（Codex P1 反映）。
- AC-6: 失敗時に token を含まない監査ログ（create 用ビルダ、projectId: String + ref を含む）が 1 行残り、汎用失敗通知が出る。
- AC-7: detached HEAD **および** branch 読取失敗の双方で、POST されず一般化文言で通知され、読取失敗時は Error Log に詳細（warn）が残る（Codex P2 反映）。
- AC-8: `./gradlew build` が detekt 0・対象テスト PASS・ベースライン外の新規失敗ゼロ。

## 25. 未決事項

- ~~**O-1（in-flight キー / 監査 targetId の表現）**~~ **解決済み（Codex P1 反映）**: create 専用キー `CreateWriteKey(normalizeInstanceUrl(instance), projectId: String, ref)` を採用（§9/§16）。監査は別途 `buildCreateAuditMessage`（§18）。`InFlightWriteGuard` の key 型汎用化 or create 専用ガードのどちらを取るかのみ実装計画で確定（挙動要件＝異なる (project,ref) 並行可・衝突なしは確定）。
- **O-2（確認ダイアログの文言・ref 表示範囲）**: webUrl と ref のみで十分か、upstream フォールバック時（ローカル短名使用）にその旨を示すか。
- **O-3（ボタンの enablement）**: 常時有効（クリック時に解決・不能なら通知）で良いか、現ブランチ文脈が解決できないときに無効化するか。常時有効を既定案とする（無効化は SidebarViewStateSourceProvider への追加配線が必要でコスト高）。
- **O-4（アイコン）**: 既存アイコン流用か新規追加か。plugin.xml の toolbar は icon 参照が必要。

## 26. 想定されるリスク

- R-1: トラッキングブランチ解決が意図と異なる ref を選び、想定外の ref で pipeline が走る。→ 確認ダイアログで ref を提示し目視検証可能にする（緩和）。
- R-2: timeout 二重発火による二重生成（§15 受容制限）。→ PR 本文に明記、cancel で復旧可能。
- R-3: インスタンス不一致で別 project に誤生成。→ FR-8 の同一インスタンス・ゲートで封鎖。
- R-4: 共有 IO scope の汚染で他機能の coroutine が全滅。→ launch 内終端 catch + CancellationException 再送（PR-2 と同一規律）。
