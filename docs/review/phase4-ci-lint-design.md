# Phase 4 PR-4 — CI Lint(`validateCIConfig` / `showMergedCIConfig`)設計書

- 対象リポジトリ: `sumikof/gitlab-eclipse-plugin`
- ベースブランチ: `develop`(tip `2017ec1`)
- フェーズ: Phase 4(Pipelines / CI)PR-4 = Phase 4 の最終 PR。完了で機能 D14 が 8/8・Phase 4 完結。
- パリティ対象: VSCode 拡張 `gitlab-workflow` v6.85.3(参照コピー `out/gitlab-vscode-extension`、変更禁止)

> 本書は Codex 設計レビュー専用。実装コードおよびマージ先ブランチには含めない。

---

## 1. 背景と目的

公式 GitLab Eclipse プラグイン(Gradle/Kotlin、`com.gitlab.eclipse.*`)を VSCode 版 GitLab 拡張と機能パリティさせる取り組みの一部。Phase 4 では Pipelines/CI を扱い、PR-1(パイプライン表示)・PR-2(ノード操作)・create pipeline・PR-3(job trace 表示 + artifacts DL)を完了済み。本 PR-4 は残る 2 コマンドを移植する。

- **`gl.validateCIConfig`**: 現在アクティブなエディタのテキストを GitLab の CI Lint API に送り、CI 設定の妥当性を検証してユーザーに結果を通知する。
- **`gl.showMergedCIConfig`**: 同じ検証呼び出しの副産物である「マージ済み(`include` 等を展開した)CI 設定 YAML」を読み取り専用エディタに表示する。

目的は VSCode の挙動との**厳密なパリティ**であり、独自機能の追加ではない。

## 2. 対象範囲(スコープ)

- 2 コマンドの実装: `validateCIConfig` / `showMergedCIConfig`。
- REST 呼び出し `POST /projects/:id/ci/lint`(body `{content}`)と応答 `{valid, merged_yaml, errors}` の処理。
- 検証結果のダイアログ通知(valid/invalid)。
- マージ済み YAML の**読み取り専用インメモリエディタ**表示(静的スナップショット)。
- 起動口: エディタ右クリック「GitLab」サブメニュー(既存)への 2 項目追加 + Quick Access(コマンド定義により自動)。
- 接続安全(クロスインスタンス誤送信・資格情報漏洩の防止)、token/body 非出力の構造化監査ログ。

## 3. 対象外(非スコープ)

- **マージ済み YAML のライブ再検証**(元 CI 設定ファイルの変更を監視して自動再 lint する VSCode の `MergedYamlContentProvider` ウォッチャ機構)。v1 は静的スナップショット。再表示はコマンド再実行で対応。
  - 根拠: VSCode `out/gitlab-vscode-extension/src/desktop/ci/merged_yaml_content_provider.ts:29-49`(`createFileSystemWatcher` + `onDidChange` で再 lint)。本 PR では移植しない。
- YAML 構文ハイライト(Eclipse 既定テキストエディタに委譲。YAML エディタが導入済みなら content-type で使われるが、本 PR は保証しない)。
- 新しいサイドバー要素や CI Lint 専用ビュー。
- CI Lint 以外のパイプライン/ジョブ機能(既存 PR で完了済み)。

## 4. 現在の課題

- Phase 4 の CI 機能はパイプライン/ジョブの表示・操作・trace までを実装済みだが、**CI 設定ファイルそのものの検証手段が無い**。VSCode 利用者は `.gitlab-ci.yml` を編集しながらローカルからサーバの Lint を叩けるが、Eclipse プラグインには相当機能が無い(パリティ欠落)。
- 既存の CI POST ヘルパ `GitLabApiClient.post()` は **body 無しの POST** で、かつ**応答 body を破棄**する(`PostResult(statusCode, correlationId)` のみ返す)。CI Lint は **JSON body `{content}`** を送り、**応答 body の JSON**(`valid/merged_yaml/errors`)を必要とするため、この既存ヘルパは流用できない。

## 5. 要件

### 5.1 機能要件(VSCode 実挙動に基づく・根拠付き)

参照: `out/gitlab-vscode-extension/src/desktop/commands/ci_config_lint_commands.ts`(コマンド本体)、`.../gitlab/gitlab_service.ts:634-639,170-174`(API と応答型)、`.../common/gitlab/api/api_client.ts:130-145`(POST 実装)、`.../commands/run_with_valid_project.ts:126-134`(プロジェクト解決)、`.../desktop/command_names.ts:26-27`(コマンド ID)、`.../desktop/constants.ts:35`(バージョン要件)。

- **FR-1(対象コンテンツ)**: 両コマンドは**アクティブなテキストエディタの全テキスト**を送る。ファイル名は不問(`.gitlab-ci.yml` に限定しない)。根拠: `ci_config_lint_commands.ts:18,43`(`editor.document.getText()`)。
- **FR-2(エディタ無し)**: アクティブなテキストエディタが無ければ「GitLab: No open file.」を情報表示して終了。根拠: `ci_config_lint_commands.ts:13-16,38-41`。
- **FR-3(プロジェクト解決)**: `runWithValidProject` = アクティブプロジェクト、無ければ選択プロンプト。根拠: `run_with_valid_project.ts:126-134`。本プラグインでは既存の `RepositoryContextResolver.selectActiveContext`(単一 repo=自動 / 複数=picker / 無し=通知)に対応させる。
- **FR-4(API)**: `POST /api/v4/projects/{id}/ci/lint`、JSON body `{ "content": <yaml text> }`。根拠: `gitlab_service.ts:634-639`。応答は `{ valid?: boolean, merged_yaml?: string, errors: string[] }`。根拠: `gitlab_service.ts:170-174`。非 2xx は例外(`handleFetchError`)。根拠: `api_client.ts:143`。
- **FR-5(validate 表示)**: `valid` が真 → 情報「GitLab: Your CI configuration is valid.」。偽 → エラー「GitLab: Invalid CI configuration.」を表示し、`errors[0]` があればそれもエラー表示。根拠: `ci_config_lint_commands.ts:25-32`。
- **FR-6(showMerged 表示・成功)**: `merged_yaml` が存在すれば、それを**読み取り専用の別ドキュメント**「.gitlab-ci (Merged).yml」として横並び(Beside)で開く。根拠: `ci_config_lint_commands.ts:61-70`、`merged_yaml_uri.ts:14`(表示名)。
- **FR-7(showMerged 表示・マージ不能)**: `merged_yaml` が無い(CI 設定にエラーがあり展開できない)場合、`errors` を Error Log に記録し、エラー「GitLab: Cannot merge the CI configuration. Check your CI configuration files for errors.」を「Validate GitLab CI Config」ボタン付きで表示。ボタン押下で `validateCIConfig` を実行。根拠: `ci_config_lint_commands.ts:49-59`。
- **FR-8(起動口)**: エディタ右クリック「GitLab」サブメニュー + Quick Access。VSCode はコマンドパレットのみ(`package.json` の `commandPalette` にゲート記載なし=常時)だが、Eclipse 慣習に合わせて既存の editor 右クリック「GitLab」サブメニューにも追加する(アクティブエディタ対象という VSCode セマンティクスと一致)。

### 5.2 非機能要件(既存 CI PR の確立パターンを継承)

- **NFR-1(接続安全)**: 表示中の接続先/認証切替による cross-instance 誤送信・資格情報漏洩を防ぐ。POST は `ConnectionSnapshot`(config 世代 seqlock で原子捕捉)にピン留めし、**捕捉 → same-instance ゲート → POST** の順序をコードで保証する。根拠パターン: `ci/actions/CreatePipeline.kt:43-60`(`runCreatePipeline`)、`api/GitLabApiClient.kt:171-183`(`captureConnection`)。
- **NFR-2(監査)**: 構造化監査ログは **token も応答 body(yaml)も一切含めない**。根拠パターン: `ci/actions/WriteAction.kt:80-89`、`ci/actions/CreatePipeline.kt:67-90`。
- **NFR-3(非断定エラー)**: 404/403 は存在を確認しない generic メッセージ。根拠パターン: `ci/actions/DisplayJobLogHandler.kt:28-30`。
- **NFR-4(共有 scope 保護)**: バックグラウンド処理は共有 `CoroutineScope`(plain Job)上で動く。`CancellationException` のみ再スロー、他の例外はすべて launch 内で catch し、scope を汚染しない。根拠パターン: `DisplayJobLogHandler.kt:137-144`、`CreatePipelineHandler.kt:60-68`。
- **NFR-5(ディスク非書込)**: マージ済み YAML はディスクに書かず**インメモリ**で表示する(`exists()=false`)。根拠パターン: PR-3 `ci/joblog/JobLogEditorInput.kt`、`JobLogStorage.kt`。
- **NFR-6(UI スレッド境界)**: エディタテキスト読取・ダイアログ・エディタ open は UI スレッド、capture+POST は IO ディスパッチャ(OAuth refresh でブロックし得るため UI で行わない)。根拠パターン: `CreatePipelineHandler.kt:56-68`。

## 6. 前提条件と制約

- ディレクトリ構成変更禁止(既存 `src/main/kotlin/com/gitlab/eclipse/*` 内に追加)。
- ビルドシステム変更禁止。**新規 bundle 依存は追加しない**(Gson は既存 `GitLabApiClient` で使用済み)。
- projectId は encoded path 文字列(`group%2Fproject`)。`RepositoryContext.projectId` は既にエンコード済み。二重エンコード禁止。根拠: `mergerequests/RepositoryContext.kt:16-25`、`ci/actions/PipelineActionService.kt:18-19`(create も encoded path String を使用)。
- GitLab API バージョン要件は 13.6.0(2020 年)。VSCode は `validateVersion` で明示チェックするが(`gitlab_service.ts:635`, `constants.ts:35`)、本プラグインには汎用のバージョンゲート機構が無く、13.6.0 は実質常に満たされる。→ **未決事項 U-1** として扱う(§25)。

## 7. システム構成

新規パッケージ `com.gitlab.eclipse.ci.lint` を追加(既存 `ci.joblog` / `ci.actions` と同格。ディレクトリ構成規約に準拠)。API 層は既存 `com.gitlab.eclipse.api` に追加。

```
com.gitlab.eclipse.api
  ├─ model/CiLintResult.kt         (新) データモデル
  ├─ GitLabApiClient.kt            (変更=追加のみ) postJson メソッド
  └─ CiLintService.kt              (新) path 組立 + body 生成 + パース

com.gitlab.eclipse.ci.lint          (新パッケージ)
  ├─ CiLint.kt                     (新) SWT-free コア: CiLintOutcome / runCiLint / 監査
  ├─ ActiveEditorContent.kt        (新) UI: アクティブエディタ text 抽出
  ├─ MergedYamlContent.kt          (新) 可変 text ホルダ
  ├─ MergedYamlStorage.kt          (新) IEncodedStorage(UTF-8・read-only)
  ├─ MergedYamlEditorInput.kt      (新) IStorageEditorInput(exists=false・key 同一性)
  └─ MergedYamlEditorOpener.kt     (新) UI: 既定テキストエディタで横並び open/差替

com.gitlab.eclipse.ci.actions
  ├─ CiLintLaunch.kt               (新) launchCiLint(top-level・共有骨格)
  ├─ ValidateCiConfigHandler.kt    (新) AbstractHandler
  └─ ShowMergedCiConfigHandler.kt  (新) AbstractHandler

src/main/resources/plugin.xml       (変更=追加のみ) command×2 + handler×2 + submenu 項目×2
```

## 8. コンポーネントの責務

### 8.1 `CiLintResult`(データモデル・純)
```
data class CiLintResult(
  val valid: Boolean,
  @SerializedName("merged_yaml") val mergedYaml: String?,
  val errors: List<String>,
)
```
- Gson で応答 JSON を写像。`valid` 欠落時は false、`errors` 欠落時は空リスト扱い(Gson 既定では null。null 安全のため `errors` を non-null 化する正規化を CiLintService 側で行う、または `errors: List<String> = emptyList()` の既定を持たせる)。**U-2**(§25)で明確化。

### 8.2 `GitLabApiClient.postJson`(追加のみ)
```
fun postJson(path: String, jsonBody: String, connection: ConnectionSnapshot): String
```
- 既存 `sendPost`(`GitLabApiClient.kt:206-224`)をミラーしつつ、`Content-Type: application/json` ヘッダ + `BodyPublishers.ofString(jsonBody, UTF_8)` を付与し、**応答 body(text)を返す**。
- URI base と Bearer は `connection`(snapshot)由来。非 2xx → `GitLabApiException(status, body, correlationId)`。timeout/IO は伝播。
- クエリは不要(空)。

### 8.3 `CiLintService`(純パス組立 + パース)
```
class CiLintService(apiClient = service()) {
  fun validate(connection: ConnectionSnapshot, projectId: String, content: String): CiLintResult
}
```
- body = Gson で `{"content": content}` を生成(手組み文字列連結ではなく Gson でエスケープ)。
- `apiClient.postJson("/projects/$projectId/ci/lint", body, connection)` → 応答 body を `CiLintResult` にパースして返す。
- 例外はそのまま伝播(分類・監査は上位の `runCiLint`)。`JobTraceService`(`api/JobTraceService.kt`)と同格の薄いサービス。

### 8.4 `CiLint.kt`(SWT-free コア=headless テスト可能)
```
sealed interface CiLintOutcome {
  data class Linted(val result: CiLintResult) : CiLintOutcome
  object ConnectionUnstable : CiLintOutcome
  object InstanceMismatch : CiLintOutcome
  data class Failed(val outcome: WriteOutcome.Failure) : CiLintOutcome
}

fun runCiLint(
  contextInstanceUrl: String,
  capture: () -> ConnectionSnapshot,
  lint: (ConnectionSnapshot) -> CiLintResult,
): CiLintOutcome
```
- 手続き: `capture()`(`UnstableConnectionException` → `ConnectionUnstable`)→ `sameConfiguredInstance(contextInstanceUrl, connection.instanceUrl)` が false → `InstanceMismatch`(**lint を一度も呼ばない**)→ `lint(connection)` を try で実行し、`GitLabApiException`→`Failed(http)` / `HttpTimeoutException`→`Failed(timeout)` / `IOException`→`Failed(io)` / 成功 → `Linted`。`CancellationException` は再スロー。
- `sameConfiguredInstance` / `normalizeInstanceUrl` は既存 `CreatePipeline.kt` / `WriteAction.kt` の関数を再利用。
- `classifyWrite`(`WriteAction.kt:37`)は `() -> PostResult` 専用で成功ペイロードが `PostResult` 固定のため流用不可。`runCiLint` 内で直接分類する(`WriteOutcome.Failure` はペイロード非依存なので再利用)。
- `buildCiLintAuditMessage(instanceUrl, projectId, command, outcome)`: `ciLint command=<validateCiConfig|showMergedCiConfig> instanceUrl=<正規化> projectId=<id> outcome=…`。token/body/yaml を一切含めない。`CreatePipeline.kt:67-90` と同型。

### 8.5 `launchCiLint`(top-level・共有骨格)
```
internal fun launchCiLint(
  scope: CoroutineScope, log: ILog,
  apiClient: GitLabApiClient, service: CiLintService,
  context: RepositoryContext, content: String, command: String,
  onLinted: (CiLintResult) -> Unit,   // UI スレッドで呼ばれる
)
```
- `scope.launch { try { … } catch (CancellationException) throw; catch (Exception) 監査+通知 }`。
- 本体: `runCiLint(context.instanceUrl, { apiClient.captureConnection() }, { service.validate(it, context.projectId, content) })` を評価。
- 結果を `Display.getDefault().asyncExec { … }` でマーシャルし、`Linted` → `onLinted(result)`、`InstanceMismatch`/`ConnectionUnstable`/`Failed` → 監査ログ(`log.error(buildCiLintAuditMessage(...))`)+ `NotificationUtils.showOnUiThread(<generic>)`。
- `DisplayJobLogHandler.launchDisplayJobLog`(`DisplayJobLogHandler.kt:94-146`)と同型。2 ハンドラは `onLinted` のみ差分。

### 8.6 `ValidateCiConfigHandler` / `ShowMergedCiConfigHandler`
- `execute`(UI): `ActiveEditorContent.of(event)` で text 抽出。null → `NotificationUtils.show("GitLab: No open file.")` して return。次に `contextResolver.selectActiveContext { ctx -> if (ctx != null) launchCiLint(..., onLinted) }`。
- **validate の onLinted**: `if (result.valid) NotificationUtils.showOnUiThread("GitLab: Your CI configuration is valid.") else { エラー "GitLab: Invalid CI configuration."; result.errors.firstOrNull()?.let { エラー } }`。
- **showMerged の onLinted**: `val merged = result.mergedYaml; if (merged != null) MergedYamlEditorOpener.open(key, merged, sourceLabel) else { log.error(errors); エラーダイアログ + "Validate GitLab CI Config" ボタン → 押下で validate コマンド実行 }`。
- `@Suppress("unused")`(plugin.xml リフレクションで実体化されるため Kotlin 参照なし。既存ハンドラと同様)。

### 8.7 `ActiveEditorContent`(UI・text 抽出)
- `IEditorPart` を `ITextEditor` にアダプトし `IDocumentProvider.getDocument(input).get()` で全 text 取得。source label はエディタ入力名(ファイル名)。非テキストエディタ/取得不能は null。
- Quick Access 経由(`HandlerUtil.getActiveEditor(event)`)と右クリック(同 event)双方で同一 API。

### 8.8 merged yaml 読み取り専用エディタ(`MergedYamlContent` / `MergedYamlStorage` / `MergedYamlEditorInput` / `MergedYamlEditorOpener`)
- `MergedYamlContent(var text)`: storage が open 時点で読む可変ホルダ(再実行時に同一タブの content 差替を可能に)。
- `MergedYamlStorage(content, name)`: `IEncodedStorage`・`getCharset()="UTF-8"`・`isReadOnly()=true`・`getContents()` は `content.text` を UTF-8 で返す。`JobLogStorage.kt` と同型。
- `MergedYamlEditorInput(key, content)`: `IStorageEditorInput`・`exists()=false`・`getName()="`.gitlab-ci (Merged).yml`"`・`getPersistable()=null`・`equals/hashCode` は `key` のみ依存。`JobLogEditorInput.kt` と同型。
- `MergedYamlKey(instanceUrl, projectId, sourcePath)`: 同一ソースファイルの再表示は同一タブに集約(タブ堆積防止)。異なるインスタンス/プロジェクト/ソースは別タブ。
- `MergedYamlEditorOpener.open(key, mergedText, name)`(UI): アクティブページで、既存の同一 input エディタがあれば content を差し替えて再描画、無ければ既定テキストエディタ(`EditorsUI` の `IStorageEditorInput` 対応=`org.eclipse.ui.DefaultTextEditor` 相当)で `openEditor(input, ...)` を横並び(`OPEN_EDITOR` + Beside 相当のカラム)で開く。`PartInitException` は呼び出し元(onLinted の UI turn)で catch し、Error Log + 通知。PR-3 `JobLogEditorOpener` の open 失敗伝播方針(Codex R1 反映)と整合。

## 9. 処理フロー

### 9.1 `validateCIConfig`
```
[UI] execute
  → ActiveEditorContent.of(event)
      null → show "GitLab: No open file." → 終了
  → contextResolver.selectActiveContext { ctx ->            // 複数=picker / 単一=自動 / 無し=通知
       ctx==null → 終了(resolver が通知)
       launchCiLint(ctx, content, "validateCiConfig", onLinted=validateOnLinted)
    }
[IO] launch:
  runCiLint(ctx.instanceUrl, capture=captureConnection, lint=service.validate(_, ctx.projectId, content))
  → asyncExec:
       Linted(r)  → r.valid ? info "…is valid." : (error "Invalid…"; r.errors[0]? → error)
       InstanceMismatch / ConnectionUnstable / Failed → 監査 + generic 通知
  例外: Cancellation 再スロー / その他 catch+監査+generic 通知
```

### 9.2 `showMergedCIConfig`
```
[UI] execute … (validate と同一; command="showMergedCiConfig", onLinted=mergedOnLinted)
[IO] launch: runCiLint(...) → asyncExec:
       Linted(r):
         r.mergedYaml != null → MergedYamlEditorOpener.open(key, merged, label)
                                  (PartInitException → Error Log + generic 通知)
         r.mergedYaml == null → log.error(r.errors); error "Cannot merge…" + [Validate GitLab CI Config]
                                  ボタン押下 → ValidateCiConfig コマンド実行
       InstanceMismatch / ConnectionUnstable / Failed → 監査 + generic 通知
```

## 10. API / インターフェース

| 項目 | 値 |
|---|---|
| メソッド/パス | `POST /api/v4/projects/{id}/ci/lint` |
| `{id}` | encoded path 文字列(`group%2Fproject`)。`RepositoryContext.projectId` |
| リクエストヘッダ | `Authorization: Bearer <token>`(snapshot 由来)、`Content-Type: application/json`、`Accept: application/json` |
| リクエスト body | `{"content": "<アクティブエディタの全 text>"}` |
| 応答(2xx) | `{ "valid": bool, "merged_yaml": string?, "errors": string[], "warnings"?: string[] }` |
| 応答(非 2xx) | `GitLabApiException(status, body, correlationId)` |
| タイムアウト | `REQUEST_TIMEOUT_SECONDS = 30`(既存既定) |

注: GitLab の project-scoped `POST /projects/:id/ci/lint` は**プロジェクトのコンテキストで `include` を展開してマージ**する(グローバル `/ci/lint` と異なる)。VSCode はこのエンドポイントを使用(`gitlab_service.ts:636`)。任意パラメータ `dry_run`/`include_jobs`/`ref` は VSCode が送っていないため本 PR でも送らない。

## 11. データモデル

- `CiLintResult(valid: Boolean, mergedYaml: String?, errors: List<String>)`。
- `CiLintOutcome`(§8.4 sealed)。
- `MergedYamlKey(instanceUrl: String, projectId: String, sourcePath: String)`。
- `ConnectionSnapshot`(既存)。`RepositoryContext`(既存、`projectId`/`instanceUrl`/`webUrl`)。

## 12. トランザクション境界

- CI Lint は**サーバ状態を変更しない読み取り的操作**(検証のみ)。トランザクションは存在せず、ロールバック対象の副作用も無い。
- クライアント側の「トランザクション」は 1 回の HTTP 呼び出し + UI 反映で完結。部分適用状態は生じない。

## 13. エラー処理

| 事象 | 挙動 |
|---|---|
| アクティブエディタ無し/非テキスト | 「GitLab: No open file.」情報表示、リクエストなし(FR-2) |
| 複数リポジトリ | `selectActiveContext` の picker |
| リポジトリ無し/GitLab 未設定 | resolver が通知、リクエストなし |
| 接続不安定(`UnstableConnectionException`) | `ConnectionUnstable` → 監査 + generic 通知、リクエストなし |
| インスタンス不一致(repo instance ≠ 設定接続先) | `InstanceMismatch` → 監査 + generic 通知、**POST を送らない** |
| 401/403/404 | `Failed(http)` → 監査(status/correlationId)+ **非断定 generic 通知** |
| CI 設定が不正(valid:false, HTTP 200) | validate: エラー + errors[0] / showMerged: merged 無し → 「Cannot merge…」+ Validate ボタン |
| timeout / IO | `Failed(timeout|io)` → 監査 + generic 通知 |
| merged エディタ open 失敗(`PartInitException`) | Error Log + generic 通知 |
| 予期しない例外 | launch 終端 catch で監査 + generic 通知(共有 scope を汚さない) |

- **注意点**: `ci/lint` は不正 config でも HTTP 200(`valid:false`)を返すため、4xx は config エラーではなく認証/権限/not-found を意味する。config の妥当性は必ず応答 body の `valid`/`errors` で判定する。

## 14. タイムアウトとリトライ

- HTTP タイムアウト 30 秒(既存既定、`GitLabApiClient` `REQUEST_TIMEOUT_SECONDS`)。
- **自動リトライは行わない**(lint は冪等だが、UI 操作起点の 1 回呼び出しで十分。失敗時はユーザーが再実行)。VSCode も自動リトライしない。

## 15. 冪等性

- `POST /ci/lint` は**サーバ副作用の無い冪等操作**。同一 body の再送は同一結果を返すのみ。
- したがって PR-2/PR-3 write 系の `InFlightWriteGuard` は**適用しない**(PR-3 READ 経路と同じ判断)。連打時は複数の並行 lint が走るが、いずれも無害。showMerged の連打は同一 `MergedYamlKey` で同一タブに集約され、最後に完了したものの content が残る(静的スナップショットとして許容)。

## 16. 並行処理

- capture+POST は共有 `CoroutineScope`(IO)上。エディタ text 抽出・ダイアログ・エディタ open は UI スレッド(`asyncExec`)。
- **接続捕捉の原子性**: `captureConnection` の config 世代 seqlock により、設定保存中の torn ペア `(新 URL, 旧 token)` を捕捉しない(`GitLabApiClient.kt:157-183`)。
- **same-instance ゲートの順序保証**: `runCiLint` は `capture → gate → lint` を SWT-free に構成し、ゲート失敗時に `lint` を呼ばないことをヘッドレステストで検証可能(誤インスタンス送信ゼロ)。`CreatePipeline.kt` の `runCreatePipeline` と同型。
- 共有 scope 保護: launch 内で `CancellationException` のみ再スロー、他は catch。

## 17. 認証と認可

- 認証: Bearer token。snapshot 経由でピン留めし、live preference/token manager を POST 中に再読しない(クロスインスタンス漏洩防止)。
- 認可: サーバ側で判定。403/404 は非断定 generic メッセージで存在を確認しない。
- **資格情報保護**: token は監査ログ・通知・エディタ content のいずれにも出力しない。body(yaml)も監査に出さない。

## 18. ログ、監視、監査

- 監査ログ 1 行/操作(`buildCiLintAuditMessage`): `command`(validateCiConfig|showMergedCiConfig)、正規化 instanceUrl、projectId、outcome(success/aborted-reason/failure+status+correlationId)。token/body/yaml を含めない。
- 失敗時は Eclipse Error Log(`ILog`)へ。showMerged のマージ不能時は `errors` を Error Log に記録(VSCode `ci_config_lint_commands.ts:50` と同等)。
- 監視: 既存の Error Log ベース。追加のテレメトリは無い。

## 19. 障害時の復旧方法

- ネットワーク/サーバ障害時はユーザーがコマンドを再実行するのみ(状態を持たないため復旧手順は不要)。
- workbench 破棄レース: `NotificationUtils.showOnUiThread` は PR-3 で確立した `SWTException` + `IllegalStateException` を精密 catch する total な実装を使用(共有 scope 汚染防止)。根拠: `DisplayJobLogHandler.kt:157-200`。
  - 注: 本 PR は新規の通知経路として `showOnUiThread` を使う。既存 write 経路の `NotificationUtils.show` の同型露出は follow-up #41 の範囲(本 PR とは別)。

## 20. 既存機能への影響

- **`GitLabApiClient` に `postJson` を追加**(既存メソッド不変)。既存テストへの回帰なし。
- 新規パッケージ `ci.lint` の追加のみ。既存 `ci.joblog` / `ci.actions` / `api` の既存型は不変(新規ファイル追加と plugin.xml への追加のみ)。
- plugin.xml は command/handler/submenu 項目の**追加のみ**。既存の「GitLab」サブメニュー(navigation)に 2 項目を足す。
- 新規 bundle 依存なし。

## 21. 移行方法

- 破壊的変更なし。プラグイン更新で新コマンドが利用可能になるだけ。設定・データの移行不要。

## 22. ロールバック方法

- 本 PR の revert で完全に元に戻る(新規ファイル削除 + plugin.xml/GitLabApiClient の追加分削除)。永続状態を作らないため、ロールバック後の残留物は無い。

## 23. テスト方針

### headless 単体(TDD・SWT 非依存)
- `CiLintResult` の Gson パース: valid/invalid、`merged_yaml` の snake_case 写像、`errors` 空/複数、欠落フィールドの既定。
- `CiLintService.validate`: 正しい path(`/projects/{encoded}/ci/lint`)+ body(`{"content":…}` の Gson エスケープ)+ 応答パース(mock apiClient)。
- `GitLabApiClient.postJson`: `Content-Type: application/json`、body 送出、Bearer が snapshot 由来、非 2xx → `GitLabApiException`、応答 body 返却(mock httpClient。既存 `sendPost` テストと同型)。
- **`runCiLint`(安全網の要)**: `capture → gate → lint` の順序、`InstanceMismatch` 時に lint ラムダが **0 回**呼ばれること、`ConnectionUnstable`、`Failed` の分類(http/timeout/io)、`CancellationException` 伝播。
- `buildCiLintAuditMessage`: 各 outcome で token/body/yaml を含まないこと。
- `MergedYamlEditorInput`: `equals/hashCode` が key のみ依存、`exists()=false`、`getName()` が期待値。

### 手動検証(headless 不能・PR 本文チェックリスト)
- エディタ右クリック「GitLab」/ Quick Access からの起動(両コマンド)。
- valid な `.gitlab-ci.yml` → 「…is valid.」。invalid → 「Invalid…」+ errors[0]。
- showMerged 成功 → 「.gitlab-ci (Merged).yml」が横並び・read-only で表示、`include` 展開済み。
- showMerged で merge 不能 → 「Cannot merge…」+ Validate ボタン → validate 実行。
- アクティブエディタ無し/非テキストエディタ → 「No open file.」。
- 複数リポジトリ → picker。
- 表示中に接続先/認証を切替 → 誤インスタンスへ送信されない(監査ログで確認)。
- 同一ソースで showMerged 再実行 → 同一タブに集約(タブ堆積なし)。

### ビルド検証バー
- 対象テスト PASS + 全体失敗数がベースライン(現 develop `2017ec1` = 806/36、36 は全て `LanguageServerWebviewServiceTest` の SWT-env)のまま + 新規失敗が `api`/`ci.*` に無いこと + 変更ファイル detekt 0。

## 24. 受け入れ条件

- AC-1: valid な CI 設定で validate が「…is valid.」を表示。
- AC-2: invalid な CI 設定で validate が「Invalid…」+ 先頭エラーを表示。
- AC-3: showMerged が `include` 展開済み YAML を読み取り専用エディタに横並び表示。
- AC-4: merge 不能時に「Cannot merge…」+ Validate ボタンを表示し、押下で validate が走る。
- AC-5: アクティブエディタ無しで「No open file.」、リクエストを送らない。
- AC-6: 表示中の接続切替でクロスインスタンス誤送信が起きない(`runCiLint` の gate が POST 前に走る=ヘッドレステスト + 監査で担保)。
- AC-7: 監査ログに token も yaml body も出力されない。
- AC-8: エディタ右クリック「GitLab」と Quick Access の両方から起動できる。
- AC-9: `./gradlew build` がベースライン(806/36)+ detekt 0 を維持。

## 25. 未決事項(推測で確定しない)

- **U-1(バージョンゲート)**: VSCode は `POST /ci/lint` 前に GitLab ≥ 13.6.0 を明示チェックする(`gitlab_service.ts:635`, `constants.ts:35`)。本プラグインには汎用のバージョンチェック機構が無い。13.6.0 は 2020 年で実質常に満たされる。**方針案**: 実装しない(未対応バージョンでは通常の API エラー→ generic 通知にフォールバック)。Codex 判断を仰ぐ。
- **U-2(`errors` の null 安全)**: Gson は JSON に `errors` が無い場合 `null` を代入し得る。`CiLintResult.errors` を non-null 化する(既定 `emptyList()` かパース後正規化)。どちらを採るか実装時に確定。挙動要件は「errors[0] があれば表示」なので null=空扱いで問題なし。
- **U-3(merged エディタの再表示ポリシー)**: 静的スナップショットのため、同一 `MergedYamlKey` の再実行は content 差替(最新反映)とする。「別タブで新規」か「差替」かは UX 判断。**方針案**: 差替(タブ堆積防止)。Codex/ユーザー判断を仰ぐ。
- **U-4(source label)**: merged エディタ名は VSCode 固定文字列「.gitlab-ci (Merged).yml」に合わせる(`merged_yaml_uri.ts:14`)。ソースファイル名を含めるかは任意。**方針案**: VSCode 準拠の固定名。
- **U-5(既定テキストエディタ ID)**: `IStorageEditorInput` を開く際の editor ID は Eclipse 既定テキストエディタ。PR-3 `JobLogEditorOpener` が使う ID と揃える(実装時に実 ID を確認)。

## 26. 想定されるリスク

- **R-1(UI スレッド/SWT の headless 非検証)**: `ActiveEditorContent`・`MergedYamlEditorOpener`・ハンドラは headless で検証できない。→ fable 実装 + 手動検証チェックリスト + PR-3 で実証済みのエディタパターン流用で緩和。
- **R-2(`postJson` の回帰)**: 既存 `GitLabApiClient` に手を入れる。→ **追加メソッドのみ**(既存 `sendPost`/`post`/`fetchText` 不変)+ 単体テストで緩和。
- **R-3(接続捕捉の TOCTOU)**: PR-2/PR-3 で Codex が精査した領域。→ `captureConnection` seqlock + `runCiLint` の gate-before-POST 順序保証(ヘッドレステスト)で緩和。既存パターンの忠実な再利用。
- **R-4(大きな YAML の body 送出)**: アクティブエディタが巨大ファイルでも VSCode 同様そのまま送る。→ サーバ側で制限(413 等)→ generic 通知。クライアント側の上限は設けない(VSCode パリティ)。
- **R-5(非テキストエディタでの起動)**: 画像/バイナリエディタがアクティブな場合 text 抽出不能。→ null → 「No open file.」で安全に終了。

## 27. 確認できた範囲 / 追加情報がなければ判断できない事項

- 確認済み(実ソース根拠): エンドポイント・body・応答型・両コマンドの UI 挙動・プロジェクト解決・バージョン要件・既存プラグインの接続安全/監査/インメモリエディタ/コンテキスト解決パターン。
- 追加情報が必要: U-1(バージョンゲートの要否=製品方針)、U-3(再表示 UX)、U-5(実 editor ID=実装時に既存コードで確定)。
