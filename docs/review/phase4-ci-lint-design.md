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

目的は VSCode の挙動とのパリティであり、独自機能の追加ではない。ただし Eclipse プラットフォームの制約に起因する 2 つの**製品判断済みパリティ例外**を持ち、目的・機能一覧・受け入れ条件・完了条件のすべてで一貫して扱う(Codex 設計レビュー round1 P1 反映):

- **E1(ライブ再検証なし)**: マージ済み YAML を開いた後、元 CI 設定ファイルの変更に追随する VSCode の自動再 lint(`MergedYamlContentProvider` ウォッチャ)は移植せず、**静的スナップショット**とする(§3・§15)。再表示はコマンド再実行。
- **E2(横並びでなくタブ)**: マージ済み YAML は VSCode の「横並び(Beside)」ではなく**通常のエディタタブ**で開く。Eclipse には依存追加なしで Beside 相当を実現する安定 API が無いため(既存 `JobLogEditorOpener.kt:98` も `IWorkbenchPage.openEditor` で現ページにタブを開くのみ。§8.8・§4 の制約「新規依存なし」)。

D14「8/8・Phase 4 完結」の完了判断は、この 2 例外を織り込んだ上でのパリティ達成を指す。

## 2. 対象範囲(スコープ)

- 2 コマンドの実装: `validateCIConfig` / `showMergedCIConfig`。
- REST 呼び出し `POST /projects/:id/ci/lint`(body `{content}`)と応答 `{valid, merged_yaml, errors}` の処理。
- 検証結果のダイアログ通知(valid/invalid)。
- マージ済み YAML の**読み取り専用インメモリエディタ**表示(静的スナップショット)。
- 起動口: エディタ右クリック「GitLab」サブメニュー(既存)への 2 項目追加 + Quick Access(コマンド定義により自動)。
- 接続安全(クロスインスタンス誤送信・資格情報漏洩の防止)、token/body 非出力の構造化監査ログ。

## 3. 対象外(非スコープ)

- **マージ済み YAML のライブ再検証**(元 CI 設定ファイルの変更を監視して自動再 lint する VSCode の `MergedYamlContentProvider` ウォッチャ機構)= **パリティ例外 E1**(§1)。v1 は静的スナップショット。再表示はコマンド再実行で対応。受け入れ条件 AC-3 で「開いた時点のスナップショットである」ことを明示検証する(§24)。
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
- **FR-6(showMerged 表示・成功)**: `merged_yaml` が存在すれば、それを**読み取り専用の別ドキュメント**「.gitlab-ci (Merged).yml」として開く。VSCode は横並び(Beside)で開くが、**プラグインは E2(§1・§8.8)により通常タブで開く**(Eclipse 制約)。根拠: `ci_config_lint_commands.ts:61-70`、`merged_yaml_uri.ts:14`(表示名)。
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
  ├─ CiLint.kt                     (新) SWT-free コア: CiLintKey / CiLintOutcome / runCiLint / 監査
  ├─ CiLintGenerationRegistry.kt   (新) UI: キーごとの世代/latest gate(JobLogGenerationRegistry 同型)
  ├─ ActiveEditorContent.kt        (新) UI: アクティブエディタ text + source identity 抽出
  ├─ MergedYamlContent.kt          (新) 可変 text ホルダ(共有・resetDocument で再読込)
  ├─ MergedYamlStorage.kt          (新) IEncodedStorage(UTF-8・read-only)
  ├─ MergedYamlEditorInput.kt      (新) IStorageEditorInput(exists=false・key 同一性)
  └─ MergedYamlEditorOpener.kt     (新) UI: openOrReload(全 window/page 一致エディタ resetDocument + タブで open)

com.gitlab.eclipse.ci.actions
  ├─ CiLintLaunch.kt               (新) launchCiLint(top-level・共有骨格) + CI-lint total UI helper
  ├─ ValidateCiConfigHandler.kt    (新) AbstractHandler
  └─ ShowMergedCiConfigHandler.kt  (新) AbstractHandler

com.gitlab.eclipse
  └─ GitLabEclipseStartup.kt         (変更=追加のみ) stop 先頭に CiLintGenerationRegistry.active=false

src/main/resources/plugin.xml       (変更=追加のみ) command×2 + handler×2 + submenu 項目×2
```

## 8. コンポーネントの責務

### 8.1 `CiLintResult`(データモデル・純)

**重要(Codex round2 P2)**: Gson は Unsafe でインスタンスを生成し**コンストラクタ/Kotlin 既定引数/`init` を実行しない**。したがって `errors: List<String> = emptyList()` の既定値は**効かず**、JSON に `errors` が無ければ non-null 宣言でも `null` が残り、`result.errors.firstOrNull()`(validate)や merge 不能ログ(showMerged)で NPE になる。→ **パース DTO は nullable、ドメイン型はパース後に non-null 正規化**に確定する。
```
// パース専用 DTO(全フィールド nullable。Gson が直接 map)
private data class CiLintResponse(
  val valid: Boolean?,
  @SerializedName("merged_yaml") val merged_yaml: String?,
  val errors: List<String>?,
)

// ドメイン型(non-null 正規化済み。上位はこれだけを扱う)
data class CiLintResult(
  val valid: Boolean,
  val mergedYaml: String?,
  val errors: List<String>,
)
```
- `CiLintService`(§8.3)が `CiLintResponse` にパース後、`CiLintResult(valid = r.valid ?: false, mergedYaml = r.merged_yaml, errors = r.errors ?: emptyList())` に正規化。これで欠落フィールドの NPE を根絶(U-2 解決)。

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
- `apiClient.postJson("/projects/$projectId/ci/lint", body, connection)` → 応答 body を `CiLintResponse`(nullable DTO)にパース → non-null 正規化した `CiLintResult` を返す(§8.1)。
- 例外はそのまま伝播(分類・監査は上位の `runCiLint`)。`JobTraceService`(`api/JobTraceService.kt`)と同格の薄いサービス。

### 8.4 `CiLint.kt`(SWT-free コア=headless テスト可能)
```
/** UI 反映の世代キー。command で validate/showMerged を分離し、同一 source の再実行を超越判定する。 */
data class CiLintKey(
  val command: String,        // "validateCiConfig" | "showMergedCiConfig"
  val instanceUrl: String,    // normalizeInstanceUrl 済み
  val projectId: String,
  val sourceId: String,       // §8.7 の安定 source identity(URI/full path/フォールバック)
)

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
- `buildCiLintAuditMessage(instanceUrl, projectId, command, outcome)`: `ciLint command=<validateCiConfig|showMergedCiConfig> instanceUrl=<正規化> projectId=<id> outcome=…`。token/body/yaml を一切含めない。`CreatePipeline.kt:67-90` と同型。**`Linted` を含む全 outcome を分岐**(`buildCreateAuditMessage` が `Created` を扱うのと同様):success 行は `outcome=success valid=<bool> merged=<present|absent> httpStatus=…`(yaml 本文は出さず存在有無のみ)。

### 8.4a `CiLintGenerationRegistry`(UI スレッド専有・順序安全=Codex round1 P1 反映)

**課題(指摘 #2)**: lint はサーバ副作用が無く「並行実行は無害」だが、それは **UI 反映順序の安全性を保証しない**。同一エディタで内容 A→B を送り B が先に完了した後に A が遅れて完了すると、遅れた A が(showMerged では)マージ済みタブを古い内容で上書きし、(validate では)現在の内容と異なる結果通知を最後に表示し得る。ユーザーが古い設定を「妥当」と誤認する危険がある。

**対策**: `JobLogGenerationRegistry`(PR-3、`DisplayJobLogHandler` が使用)と**同型**の per-key 世代レジストリを新設し、**最新世代のみが UI(エディタ・通知・エラー)へ反映**されるようにする。
```
object CiLintGenerationRegistry {          // 変異/読取は UI スレッド。active のみ @Volatile
  @Volatile var active: Boolean = true      // plugin 停止で false(stop フックが設定)
  fun nextGeneration(key: CiLintKey): Long  // この実行を key の最新として採番・記録
  fun isLatest(key: CiLintKey, gen: Long): Boolean
  fun shouldAct(key: CiLintKey, gen: Long): Boolean  // active かつ isLatest
  internal fun resetForTest()               // テスト用リセット(本番未使用)
}
```
- `JobLogGenerationRegistry`(実ソース `ci/joblog/JobLogGenerationRegistry.kt`)を**忠実にミラー**: `counter` 単調増加・key ごと `latest` map・`active` は `@Volatile`(停止が別スレッドからの syncExec 経由になり得るため)。
- 世代採番は **UI スレッド**(§8.6 の `selectActiveContext` コールバック内、context 解決後・IO launch 前)。
- 反映/通知は UI turn 内で `shouldAct(key, myGen)` を判定してから実行(判定と実行を同一 turn=stale すり抜け防止。`DisplayJobLogHandler.reflectLatest/notifyIfLatest` と同一構造)。
- **ライフサイクル配線(Codex round2 P1 反映)**: `GitLabEclipseStartup.stop`(実ソース `:86-96` で既に `JobLogGenerationRegistry.active = false` を実行)の**先頭**に `CiLintGenerationRegistry.active = false` を追加する。これが無いと Display 生存中の停止区間で `shouldAct` が true のままとなり、停止後の通知/エディタ open が走って AC-10 に反する。stop への変更は §20(既存機能への影響)に明記。

### 8.5 `launchCiLint`(top-level・共有骨格)+ CI-lint total UI helper
```
internal fun launchCiLint(
  scope: CoroutineScope, log: ILog,
  apiClient: GitLabApiClient, service: CiLintService,
  context: RepositoryContext, content: String,
  key: CiLintKey, myGen: Long,        // UI スレッドで採番済み(§8.6)
  onLinted: (CiLintResult) -> Unit,   // UI スレッド・latest gate 通過後にのみ呼ばれる
)
```
- `scope.launch { try { … } catch (CancellationException) throw; catch (Exception) 監査 + gated 通知 }`。
- 本体: `runCiLint(context.instanceUrl, { apiClient.captureConnection() }, { service.validate(it, context.projectId, content) })` を評価。
- **監査(1 操作 1 行・§18・Codex round2 P2)**: `Linted` は `log.info(buildCiLintAuditMessage(..., outcome=Linted))`(成功も件数/成功率が追跡できるよう記録・body 非出力)、`InstanceMismatch`/`ConnectionUnstable`/`Failed` は `log.error(buildCiLintAuditMessage(...))`。監査はゲート判定と独立に**必ず 1 行**出す(反映/通知のみ gate で抑制)。
- 結果を **CI-lint total helper** で UI にマーシャル(下記)。`Linted` → gate 通過時のみ `onLinted(result)`、`InstanceMismatch`/`ConnectionUnstable`/`Failed` → gate 通過時のみ generic 通知。
- `DisplayJobLogHandler.launchDisplayJobLog`(`DisplayJobLogHandler.kt:94-146`)と同型。2 ハンドラは `onLinted` のみ差分。

**CI-lint total UI helper(指摘 #5 反映)**: `NotificationUtils.showOnUiThread` は**それ自体は total ではない**(実ソース `NotificationUtils.kt:21-35` に catch 無し。teardown 安全性は呼び出し側の private helper が担う)。したがって CI lint 専用に、`DisplayJobLogHandler.reflectLatest/notifyIfLatest`(`:157-200`)と**同一構造**の total helper を `CiLintLaunch.kt` に置く:
```
// UI マーシャリングの各境界(display 取得・asyncExec 予約・UI runnable)を total 化。
private fun reflectOnUiThread(log, key, myGen, action: () -> Unit) {
  try {
    currentDisplay.asyncExec {
      try {
        if (currentDisplay.isDisposed) return@asyncExec
        if (!CiLintGenerationRegistry.shouldAct(key, myGen)) return@asyncExec
        action()   // onLinted 本体(dialog / editor open)
      } catch (ignored: SWTException) { /* disposed mid-turn: no-op */ }
        catch (e: Exception) { log.error(...); if (shouldAct) NotificationUtils.showOnUiThread(GENERIC) }
    }
  } catch (ignored: SWTException) { /* asyncExec on disposed display: no-op */ }
    catch (ignored: IllegalStateException) { /* workbench 破棄: display 取得失敗 no-op */ }
}
private fun notifyIfLatest(key, myGen, message) { /* 同型。isLatest 判定 → showOnUiThread */ }
```
- **不変条件**: これらの helper は**決して例外を投げない(total)**。`currentDisplay` getter(`PlatformUI.getWorkbench().display`)が workbench 破棄後に投げる `IllegalStateException`、および `asyncExec`/UI runnable の `SWTException` を精密 catch し、破棄時は no-op。launch の終端 catch がこの helper 経由で通知しても例外が共有 plain-Job scope へ漏れない(§19)。

### 8.6 `ValidateCiConfigHandler` / `ShowMergedCiConfigHandler`
- `execute`(UI): `ActiveEditorContent.of(event)` で **text + source identity(§8.7)を同一 UI turn で抽出**。null → `NotificationUtils.show("GitLab: No open file.")` して return。
- 次に `contextResolver.selectActiveContext { ctx -> …(UI スレッド)}`:
  - `ctx == null` → 終了(resolver が通知)。
  - `key = CiLintKey(command, normalizeInstanceUrl(ctx.instanceUrl), ctx.projectId, sourceId)`。
  - `myGen = CiLintGenerationRegistry.nextGeneration(key)`(**UI スレッドで採番**・§8.4a)。
  - `launchCiLint(scope, log, apiClient, service, ctx, content, key, myGen, onLinted)`。
- **validate の onLinted**(gate 通過後・UI): `if (result.valid) NotificationUtils.showOnUiThread("GitLab: Your CI configuration is valid.") else { エラー "GitLab: Invalid CI configuration."; result.errors.firstOrNull()?.let { エラー } }`。
- **showMerged の onLinted**(gate 通過後・UI): `val merged = result.mergedYaml; if (merged != null) MergedYamlEditorOpener.openOrReload(mergedKey, merged) else <merge 不能ダイアログ>`。
  - `mergedKey = MergedYamlKey(key.instanceUrl, key.projectId, sourceId)`(§8.8)。`openOrReload` の `PartInitException`/`CoreException` は onLinted の UI turn(=total helper 内)で catch され、Error Log + latest-gated 通知(§8.5 の inner catch)。
  - **merge 不能ダイアログの実装方式(Codex round2 P2 反映)**: `NotificationUtils` はメッセージのみ popup でボタン/コマンド実行を持たないため使わない。`errors` を `log.error` 記録の上、`MessageDialog`(`org.eclipse.jface.dialogs`)を明示使用:
    ```
    val open = MessageDialog.open(
      MessageDialog.ERROR, shell, "GitLab",
      "GitLab: Cannot merge the CI configuration. Check your CI configuration files for errors.",
      SWT.NONE, "Validate GitLab CI Config", "Close",
    )  // 戻り値 true = 先頭ボタン(index 0)押下
    if (open) executeValidateCommand()
    ```
    - `shell` は `HandlerUtil.getActiveShell(event)`(execute で捕捉)or `PlatformUI.getWorkbench().activeWorkbenchWindow?.shell`。
    - `executeValidateCommand()` = `PlatformUI.getWorkbench().getService(IHandlerService).executeCommand("com.gitlab.eclipse.commands.ValidateCiConfig", null)`(VSCode の `executeCommand(VALIDATE_CI_CONFIG)` パリティ・`ci_config_lint_commands.ts:56`)。`ExecutionException`/`NotHandledException` 等は catch して Error Log(共有 scope 非依存の UI 経路)。
    - これにより AC-4(Validate 再実行)を一貫実装。`MessageDialog` は既存 JFace(`CreatePipelineHandler` が `MessageDialog.openConfirm` を使用済み・新規依存なし)。
- `@Suppress("unused")`(plugin.xml リフレクションで実体化されるため Kotlin 参照なし。既存ハンドラと同様)。

### 8.7 `ActiveEditorContent`(UI・text + source identity 抽出=Codex round1 P1 反映)
- 返り値: `data class ActiveEditorContent(val text: String, val sourceId: String, val displayName: String)`。null = アクティブテキストエディタ無し/text 取得不能。
- text: `IEditorPart` を `ITextEditor` にアダプトし `documentProvider.getDocument(editorInput).get()` で全 text 取得。
- **sourceId(安定 source identity・指摘 #6)**: エディタタブ分離キー(§8.8 `MergedYamlKey`)と世代キー(§8.4 `CiLintKey`)の同一性根拠。**text と同一 UI turn**で `IEditorInput` から次の優先順で導出する:
  1. `IFileEditorInput` → `file.fullPath.toString()`(workspace 相対フルパス。同名別パスを区別)。
  2. それ以外で `IURIEditorInput` → `uri.toString()`(外部ファイル等の絶対 URI)。
  3. いずれでもない/未保存の非ファイル入力 → `input.name + "#" + System.identityHashCode(input)`。**当該エディタ入力インスタンスに対して安定**(同一未保存エディタの再 lint は同一 sourceId=同一タブに集約、別の未保存エディタは別 sourceId)。
  - basename(`getName()`)単独は**採用しない**(同一プロジェクト内の同名別パスで衝突するため)。`displayName` は UI 表示補助のみで identity には使わない。
- Quick Access 経由(`HandlerUtil.getActiveEditor(event)`)と右クリック(同 event)双方で同一 API。同一 turn 捕捉により text と sourceId の不整合(捕捉の間にアクティブエディタが変わる)を防ぐ。

### 8.8 merged yaml 読み取り専用エディタ(`MergedYamlContent` / `MergedYamlStorage` / `MergedYamlEditorInput` / `MergedYamlEditorOpener`)

PR-3 の JobLog エディタトリオ(`JobLogContent`/`JobLogStorage`/`JobLogEditorInput`/`JobLogEditorOpener`)を**忠実にミラー**する(A1 採用=JobLog パッケージと疎結合の専用実装)。ライブ再検証(E1)を行わないため JobLog の shutdown/part-listener/content-GC 機構までは要さないが、**再読込の機構は JobLog と同一でなければならない**(指摘 #3)。

- `MergedYamlContent(var text)`: storage が `getContents()` 時に読む**共有可変ホルダ**。既存タブの再読込を成立させる要。`JobLogContent` 同型。
- `MergedYamlStorage(content, name)`: `IEncodedStorage`・`getCharset()="UTF-8"`・`isReadOnly()=true`・`getContents()` は `content.text` を UTF-8 で返す。`JobLogStorage.kt` と同型。
- `MergedYamlEditorInput(key, content)`: `IStorageEditorInput`・`exists()=false`・`getName()="`.gitlab-ci (Merged).yml`"`(VSCode 固定名パリティ・`merged_yaml_uri.ts:14`。U-4 解決)・`getPersistable()=null`・`getAdapter()=null`・`equals/hashCode` は `key` のみ依存。`JobLogEditorInput.kt` と同型。
- `MergedYamlKey(instanceUrl, projectId, sourceId)`(指摘 #6 反映): `instanceUrl` は正規化済み、`sourceId` は §8.7 の安定 source identity。同一(インスタンス, プロジェクト, ソース)の再実行は同一タブに集約(タブ堆積防止)、同名別パス/別未保存入力は別 `sourceId`=別タブ。

**`MergedYamlEditorOpener.openOrReload(key, mergedText)`(UI スレッド専有・`JobLogEditorOpener.openOrReload:49-100` を忠実移植=指摘 #3/#4 反映)**:
1. **共有 content 更新**: `val content = contentRegistry[key]?.get() ?: MergedYamlContent(mergedText).also { contentRegistry[key] = WeakReference(it) }` を取得(**新規生成時は registry へ即登録**=`JobLogEditorOpener.kt:57` の `.also { contentRegistry[key] = WeakReference(it) }` と同一。これが無いと初回タブの content が registry に残らず、再実行時に別 content を作ってしまい既存エディタの古い input を reset して古い YAML が残る=AC-3 不成立/Codex round2 P1)し、`content.text = mergedText`。既存タブの input が同一 content を strong-ref するため weak が生存。最後のタブが閉じると GC 可能。
2. **全 window/page の一致エディタ列挙**: `PlatformUI.getWorkbench().workbenchWindows` × `pages` を走査し、`page.findEditors(input, null, IWorkbenchPage.MATCH_INPUT)` + `getEditor(true)` で**分割/クローンを含む全一致タブ**を収集(`findEditor` は 1 つしか返さない=不可)。
3. **文書再読込**: 各一致エディタを `ITextEditor` にアダプトし `documentProvider.resetDocument(editorInput)` を呼ぶ(**content 差替だけでは既定テキストエディタの `IDocument` キャッシュが更新されない**ため必須)。背景ページの `CoreException` は log して best-effort 継続、**アクティブページのエディタの reset 失敗は伝播**(呼び出し元 total helper が Error Log + latest-gated 通知。stale を「更新済み」に見せない)。
4. **可視化(タブ・Beside ではない=E2)**: アクティブページに既存一致タブがあれば `activePage.activate(editor)`、無ければ `activePage.openEditor(input, "org.eclipse.ui.DefaultTextEditor")` で**通常タブとして開く**(U-5 解決。`JobLogEditorOpener.DEFAULT_TEXT_EDITOR_ID` と同一)。`IWorkbenchPage.openEditor` は横並び配置を行わず、Eclipse には依存追加なしの Beside 相当 API が無いため、VSCode の `ViewColumn.Beside` はタブで代替(E2・§1)。`PartInitException` は呼び出し元(total helper)へ伝播し Error Log + latest-gated 通知。
- 呼び出しは §8.6 の onLinted 経由=**latest gate 通過後のみ**(§8.4a)。よって遅延完了した旧 lint は既存タブを上書きしない。

## 9. 処理フロー

### 9.1 `validateCIConfig`
```
[UI] execute
  → ActiveEditorContent.of(event)   // text + sourceId を同一 turn で取得
      null → show "GitLab: No open file." → 終了
  → contextResolver.selectActiveContext { ctx ->            // 複数=picker / 単一=自動 / 無し=通知
       ctx==null → 終了(resolver が通知)
       key = CiLintKey("validateCiConfig", norm(ctx.instanceUrl), ctx.projectId, sourceId)
       myGen = CiLintGenerationRegistry.nextGeneration(key)     // UI スレッドで採番
       launchCiLint(ctx, content, key, myGen, onLinted=validateOnLinted)
    }
[IO] launch:
  runCiLint(ctx.instanceUrl, capture=captureConnection, lint=service.validate(_, ctx.projectId, content))
  → total helper で asyncExec(shouldAct(key,myGen) 通過時のみ反映):
       Linted(r)  → r.valid ? info "…is valid." : (error "Invalid…"; r.errors[0]? → error)
       InstanceMismatch / ConnectionUnstable / Failed → 監査 + gated generic 通知
  例外: Cancellation 再スロー / その他 catch+監査+gated generic 通知
```

### 9.2 `showMergedCIConfig`
```
[UI] execute … (validate と同一; command="showMergedCiConfig", onLinted=mergedOnLinted)
[IO] launch: runCiLint(...) → total helper で asyncExec(shouldAct 通過時のみ):
       Linted(r):
         r.mergedYaml != null → MergedYamlEditorOpener.openOrReload(mergedKey, merged)
                                  (全一致タブ resetDocument・active-page 失敗伝播・タブで open
                                   PartInitException/CoreException → Error Log + gated 通知)
         r.mergedYaml == null → log.error(r.errors); error "Cannot merge…" + [Validate GitLab CI Config]
                                  ボタン押下 → ValidateCiConfig コマンド実行
       InstanceMismatch / ConnectionUnstable / Failed → 監査 + gated generic 通知
```
- 遅延完了した旧世代の lint は `shouldAct` が false のため**エディタ上書きも通知も行わない**(§8.4a・指摘 #2)。

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
- `CiLintKey(command, instanceUrl, projectId, sourceId)`(§8.4・世代/latest gate キー)。
- `MergedYamlKey(instanceUrl: String, projectId: String, sourceId: String)`(§8.8・エディタタブ同一性。`sourceId` は §8.7 の安定 source identity)。
- `ActiveEditorContent(text, sourceId, displayName)`(§8.7)。
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
- したがって PR-2/PR-3 write 系の `InFlightWriteGuard`(送信の直列化)は**適用しない**(PR-3 READ 経路と同じ判断)。連打時は複数の並行 lint が走るが、サーバ状態は変わらない。
- **ただしサーバ副作用の無さは UI 反映順序の安全を保証しない**(指摘 #2)。in-flight ガード非適用と**別に**、per-key 世代/latest gate(§8.4a `CiLintGenerationRegistry`)を設け、**遅延完了した旧世代の結果はエディタ上書きも通知も行わない**。これにより A→B 送信で B 先着後に A が遅延完了しても、最新(B)の結果のみが反映される。showMerged の同一ソース再実行は同一 `MergedYamlKey` の同一タブに `resetDocument` で反映される(静的スナップショット=E1)。

## 16. 並行処理

- capture+POST は共有 `CoroutineScope`(IO)上。エディタ text/sourceId 抽出・世代採番・ダイアログ・エディタ open/reset は UI スレッド。
- **接続捕捉の原子性**: `captureConnection` の config 世代 seqlock により、設定保存中の torn ペア `(新 URL, 旧 token)` を捕捉しない(`GitLabApiClient.kt:157-183`)。
- **same-instance ゲートの順序保証**: `runCiLint` は `capture → gate → lint` を SWT-free に構成し、ゲート失敗時に `lint` を呼ばないことをヘッドレステストで検証可能(誤インスタンス送信ゼロ)。`CreatePipeline.kt` の `runCreatePipeline` と同型。
- **UI 反映の順序安全**: per-key 世代/latest gate(§8.4a)を UI スレッドで採番・判定し、判定と反映(エディタ reset/通知)を同一 UI turn で行う(遅延完了の旧世代を破棄。`DisplayJobLogHandler` と同型・指摘 #2)。
- **エディタ再読込の一貫性**: `openOrReload` が全 window/page の一致エディタ(分割/クローン含む)を `resetDocument` で更新し、アクティブページの reset 失敗を伝播(§8.8・指摘 #3)。
- 共有 scope 保護: launch 内で `CancellationException` のみ再スロー、他は catch。UI マーシャリングは total helper(§8.5)で例外を漏らさない。

## 17. 認証と認可

- 認証: Bearer token。snapshot 経由でピン留めし、live preference/token manager を POST 中に再読しない(クロスインスタンス漏洩防止)。
- 認可: サーバ側で判定。403/404 は非断定 generic メッセージで存在を確認しない。
- **資格情報保護**: token は監査ログ・通知・エディタ content のいずれにも出力しない。body(yaml)も監査に出さない。

## 18. ログ、監視、監査

- 監査ログ **1 行/操作**(`buildCiLintAuditMessage`): `command`(validateCiConfig|showMergedCiConfig)、正規化 instanceUrl、projectId、outcome(success+valid+merged有無 / aborted-reason / failure+status+correlationId)。token/body/yaml を含めない。
- **成功(`Linted`)は `ILog.info`、異常(mismatch/unstable/failed/unexpected)は `ILog.error`**(Codex round2 P2。成功も件数・成功率が Error Log から追跡可能)。監査行は latest gate と独立に必ず出力(反映/通知のみ gate 対象)。
- showMerged のマージ不能時は `errors` を Error Log に記録(VSCode `ci_config_lint_commands.ts:50` と同等)。
- 監視: 既存の Error Log ベース。追加のテレメトリは無い。

## 19. 障害時の復旧方法

- ネットワーク/サーバ障害時はユーザーがコマンドを再実行するのみ(状態を持たないため復旧手順は不要)。
- **workbench 破棄レース(指摘 #5 反映)**: `NotificationUtils.showOnUiThread` は**それ自体は total ではない**(実ソース `NotificationUtils.kt:21-35` に catch 無し。`show()` の `currentDisplay.asyncExec` も無防備)。teardown 安全性は PR-3 では**呼び出し側 private helper**(`DisplayJobLogHandler.reflectLatest/notifyIfLatest:157-200`)が `SWTException`(disposed display の `asyncExec`/UI runnable)+ `IllegalStateException`(workbench 破棄後の `PlatformUI.getWorkbench().display` getter)を精密 catch して担保している。
  - したがって本 PR は §8.5 の **CI-lint 専用 total helper** を新設し、`currentDisplay` 取得 → `asyncExec` 予約 → UI runnable の**各境界**を try/catch で囲み、破棄時は**通知/反映を no-op** にする。launch の終端 catch がこの helper 経由で通知しても、例外が共有 plain-Job scope へ漏れて他の CI コルーチンを巻き添えに cancel することはない。
  - 例外注入テスト(§23)で「破棄相当で helper が例外を投げず no-op」を検証する。
  - 注: 既存 write 経路の `NotificationUtils.show` の同型露出は follow-up #41 の範囲(本 PR とは別)。本 PR は自前 total helper を使うため #41 の影響を受けない。

## 20. 既存機能への影響

- **`GitLabApiClient` に `postJson` を追加**(既存メソッド不変)。既存テストへの回帰なし。
- 新規パッケージ `ci.lint` の追加のみ。既存 `ci.joblog` / `ci.actions` / `api` の既存型は不変(新規ファイル追加と plugin.xml への追加のみ)。
- **`GitLabEclipseStartup.stop` に 1 行追加**(`CiLintGenerationRegistry.active = false`。既存 `shutdownJobLog()` と同経路・Codex round2 P1)。start は不変。既存挙動への回帰なし。
- plugin.xml は command/handler/submenu 項目の**追加のみ**。既存の「GitLab」サブメニュー(navigation)に 2 項目を足す。
- 新規 bundle 依存なし。

## 21. 移行方法

- 破壊的変更なし。プラグイン更新で新コマンドが利用可能になるだけ。設定・データの移行不要。

## 22. ロールバック方法

- 本 PR の revert で完全に元に戻る(新規ファイル削除 + plugin.xml/GitLabApiClient の追加分削除)。永続状態を作らないため、ロールバック後の残留物は無い。

## 23. テスト方針

### headless 単体(TDD・SWT 非依存)
- `CiLintResult` の Gson パース: valid/invalid、`merged_yaml` の snake_case 写像、`errors` 空/複数、欠落フィールドの既定。
- `CiLintService.validate`: 正しい path(`/projects/{encoded}/ci/lint`)+ body(`{"content":…}` の Gson エスケープ)+ 応答パース(mock apiClient)。**`errors`/`valid`/`merged_yaml` 欠落 JSON でも NPE を出さず `errors=[] / valid=false / mergedYaml=null` に正規化**(Codex round2 P2)。`valid:false`+`errors` あり、`merged_yaml` あり、の各ケース。
- `GitLabApiClient.postJson`: `Content-Type: application/json`、body 送出、Bearer が snapshot 由来、非 2xx → `GitLabApiException`、応答 body 返却(mock httpClient。既存 `sendPost` テストと同型)。
- **`runCiLint`(安全網の要)**: `capture → gate → lint` の順序、`InstanceMismatch` 時に lint ラムダが **0 回**呼ばれること、`ConnectionUnstable`、`Failed` の分類(http/timeout/io)、`CancellationException` 伝播。
- `buildCiLintAuditMessage`: **`Linted`(success)を含む全 outcome**で行を生成し、token/body/yaml を含まず merged は present/absent のみ、を検証。
- `MergedYamlEditorInput`: `equals/hashCode` が key のみ依存、`exists()=false`、`getName()` が期待値。`MergedYamlKey`: 同一(instance,project,sourceId)は等価、sourceId 差で非等価。
- **`CiLintGenerationRegistry`(指摘 #2)**: `nextGeneration` が単調増加・key の最新を更新、`isLatest/shouldAct`、**完了順逆転シナリオ**(gen1 採番→gen2 採番→gen1 で `shouldAct`=false・gen2 で true)。plugin 停止で `active=false` → `shouldAct`=false。
- **`ActiveEditorContent.sourceId` 導出(指摘 #6)**: `IFileEditorInput`(mock)→ fullPath、`IURIEditorInput`(mock)→ uri、フォールバック → `name + "#" + identityHashCode`。**同名別パス → 別 sourceId**、**同一未保存入力インスタンスの 2 回抽出 → 同一 sourceId**。SWT/document 取得部は手動、input→sourceId 純ロジックは mock 入力で単体化。
- **total helper の gating 純ロジック**: `shouldAct` 判定分岐(反映/通知の可否)を SWT 非依存部分で単体化。SWT 例外 catch(disposed display/`asyncExec`)の no-op は手動検証(§手動)。

> merged エディタの `openOrReload`(全一致タブ列挙 + `resetDocument` + active-page 失敗伝播 + タブ open)は SWT/workbench 実体依存のため headless 不可 → 手動検証。JobLog 版が PR-3 Codex R1/R2 で実証済みの同一機構。

### 手動検証(headless 不能・PR 本文チェックリスト)
- エディタ右クリック「GitLab」/ Quick Access からの起動(両コマンド)。
- valid な `.gitlab-ci.yml` → 「…is valid.」。invalid → 「Invalid…」+ errors[0]。
- showMerged 成功 → 「.gitlab-ci (Merged).yml」が**通常タブ**・read-only で表示、`include` 展開済み(E2=Beside でなくタブ)。
- **showMerged 再実行(同一ソース)→ 同一タブの内容が `resetDocument` で更新される**(古い YAML が残らない・指摘 #3)。
- **同名別パスの CI ファイル 2 つで showMerged → 別タブ**(sourceId 分離・指摘 #6)。未保存エディタの lint → 「No open file.」にならず lint 実行、再実行で同一タブ。
- **完了順逆転**: 内容 A で実行直後に内容 B で実行 → 最終的に B の結果のみが表示/通知される(A の遅延完了が上書きしない・指摘 #2)。手動再現が難しい場合はネットワーク遅延注入で確認。
- showMerged で merge 不能 → `MessageDialog` に「Cannot merge…」+「Validate GitLab CI Config」ボタン → 押下で validate コマンドが `IHandlerService` 経由で実行される(指摘 P2)。
- 監査ログに **成功操作も 1 行**残る(`ILog.info`・valid/merged 有無つき・yaml 本文なし)。
- 進行中 lint がある状態で plugin を停止 → 完了しても通知/エディタ操作が出ない(stop フックで `CiLintGenerationRegistry.active=false`・指摘 P1)。
- アクティブエディタ無し/非テキストエディタ → 「No open file.」。
- 複数リポジトリ → picker。
- 表示中に接続先/認証を切替 → 誤インスタンスへ送信されない(監査ログで確認)。
- **plugin 停止 → 進行中 lint が完了しても通知/エディタ操作が出ない**(total helper no-op・指摘 #5)。

### ビルド検証バー
- 対象テスト PASS + 全体失敗数がベースライン(現 develop `2017ec1` = 806/36、36 は全て `LanguageServerWebviewServiceTest` の SWT-env)のまま + 新規失敗が `api`/`ci.*` に無いこと + 変更ファイル detekt 0。

## 24. 受け入れ条件

- AC-1: valid な CI 設定で validate が「…is valid.」を表示。
- AC-2: invalid な CI 設定で validate が「Invalid…」+ 先頭エラーを表示。
- AC-3: showMerged が `include` 展開済み YAML を**読み取り専用の通常エディタタブ**(E2=Beside でなくタブ)に表示し、その内容は**開いた時点のスナップショット**(E1=ライブ再検証なし)である。同一ソースで再実行すると同一タブが `resetDocument` で更新され、古い YAML が残らない。
- AC-4: merge 不能時に「Cannot merge…」+ Validate ボタンを表示し、押下で validate が走る。
- AC-5: アクティブエディタ無し/非テキストで「No open file.」、リクエストを送らない。
- AC-6: 表示中の接続切替でクロスインスタンス誤送信が起きない(`runCiLint` の gate が POST 前に走る=ヘッドレステスト + 監査で担保)。
- AC-7: 監査ログに token も yaml body も出力されない。
- AC-8: エディタ右クリック「GitLab」と Quick Access の両方から起動できる。
- AC-9: **完了順が逆転しても最新世代の結果のみが UI に反映**され、遅延完了した旧世代はエディタ上書き/通知を行わない(§8.4a)。同名別パスのソースは別タブに分離(§8.7 sourceId)。
- AC-10: plugin 停止後に進行中 lint が完了しても total helper が no-op で例外を漏らさない(共有 scope 非 cancel)。
- AC-11: `./gradlew build` がベースライン(806/36)+ detekt 0 を維持。

## 25. 未決事項(推測で確定しない)

継続中(要 Codex/ユーザー判断):

- **U-1(バージョンゲート)**: VSCode は `POST /ci/lint` 前に GitLab ≥ 13.6.0 を明示チェックする(`gitlab_service.ts:635`, `constants.ts:35`)。本プラグインには汎用のバージョンチェック機構が無い。13.6.0 は 2020 年で実質常に満たされる。**方針案**: 実装しない(未対応バージョンでは通常の API エラー→ generic 通知にフォールバック)。Codex 判断を仰ぐ。
解決済み(Codex round1/round2 反映):

- **U-2(`errors`/`valid` の null 安全)→ 解決**: Gson がコンストラクタを迂回するため既定値は効かない。nullable パース DTO `CiLintResponse` → non-null 正規化した `CiLintResult`(§8.1・§8.3・Codex round2 P2)。

- **U-3(merged 再表示ポリシー)→ 解決**: 同一 `MergedYamlKey` の再実行は `openOrReload` で**同一タブを `resetDocument` 更新**(タブ堆積防止・§8.8・指摘 #3)。異なる sourceId は別タブ。
- **U-4(source label)→ 解決**: merged エディタ名は VSCode 固定文字列「.gitlab-ci (Merged).yml」(`merged_yaml_uri.ts:14`)。source identity は表示名でなく §8.7 の `sourceId` が担う。
- **U-5(既定テキストエディタ ID)→ 解決**: `"org.eclipse.ui.DefaultTextEditor"`(実ソース `JobLogEditorOpener.kt:27` で確認)。
- **E2(横並び→タブ)→ 決定**: Eclipse に依存追加なしの Beside 相当 API が無いため(実ソース `JobLogEditorOpener.kt:98`=`openEditor` は現ページタブのみ)、マージ済み YAML は通常タブで開く製品判断済み例外(§1・§8.8・指摘 #4)。

## 26. 想定されるリスク

- **R-1(UI スレッド/SWT の headless 非検証)**: `ActiveEditorContent`・`MergedYamlEditorOpener`・ハンドラは headless で検証できない。→ fable 実装 + 手動検証チェックリスト + PR-3 で実証済みのエディタパターン流用で緩和。
- **R-2(`postJson` の回帰)**: 既存 `GitLabApiClient` に手を入れる。→ **追加メソッドのみ**(既存 `sendPost`/`post`/`fetchText` 不変)+ 単体テストで緩和。
- **R-3(接続捕捉の TOCTOU)**: PR-2/PR-3 で Codex が精査した領域。→ `captureConnection` seqlock + `runCiLint` の gate-before-POST 順序保証(ヘッドレステスト)で緩和。既存パターンの忠実な再利用。
- **R-4(大きな YAML の body 送出)**: アクティブエディタが巨大ファイルでも VSCode 同様そのまま送る。→ サーバ側で制限(413 等)→ generic 通知。クライアント側の上限は設けない(VSCode パリティ)。
- **R-5(非テキストエディタでの起動)**: 画像/バイナリエディタがアクティブな場合 text 抽出不能。→ null → 「No open file.」で安全に終了。

## 27. 確認できた範囲 / 追加情報がなければ判断できない事項

- 確認済み(実ソース根拠): エンドポイント・body・応答型・両コマンドの UI 挙動・プロジェクト解決・バージョン要件・既存プラグインの接続安全/監査/インメモリエディタ/コンテキスト解決パターン。
- 追加情報が必要: U-1(バージョンゲートの要否=製品方針)、U-3(再表示 UX)、U-5(実 editor ID=実装時に既存コードで確定)。
