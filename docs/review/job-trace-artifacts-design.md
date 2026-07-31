# Job trace 表示 + Artifacts ダウンロード 設計書(Phase 4 PR-3)

- 対象リポジトリ: `sumikof/gitlab-eclipse-plugin`
- ベースブランチ: `develop`(tip `e5a0a9a`)
- パリティ台帳: #7(D14 CI ドメイン)/ ロードマップ: #8 / フェーズ issue: #12
- 参照: VSCode 拡張 `gitlab-workflow` v6.85.3(`./out/gitlab-vscode-extension`、読み取り専用)
- 本設計書はレビュー専用。実装 PR・マージ先には含めない。

---

## 1. 背景と目的

Phase 4(Pipelines / CI / Jobs)の薄い縦切りの続き。PR-1(表示 #34)、PR-2(操作 retry/cancel/play #36)、create pipeline(#38)がマージ済み。本 PR-3 は残り 2 機能のうち **ジョブトレース(ログ)表示**と**アーティファクトのダウンロード**を実装し、VSCode 版とのパリティを進める。

VSCode 実装の実挙動(実ソースで確定):

- **ジョブトレース**: `GET /api/v4/projects/{projectId}/jobs/{jobId}/trace` で生ログ全文をテキスト取得し、read-only 仮想テキストドキュメントに表示。running ジョブは `If-None-Match`+304 で 3 秒ポーリング。
  - 根拠: `out/gitlab-vscode-extension/src/desktop/gitlab/gitlab_service.ts:929-946`(`getJobTrace` 署名/エンドポイント/ETag/304→null/`response.text()`)、`src/desktop/ci/job_log_refresher.ts:7-75`(3s ポーリング)、`src/desktop/constants.ts:5`(`JOB_LOG_URI_SCHEME='gl-job-log'`)、`src/desktop/command_names.ts:54`(`OPEN_TRACE_ARTIFACT='gl.openTraceArtifact'`)。
- **アーティファクト**: REST バイナリダウンロードは**行わない**。ジョブの `web_url` を用いて `${job.web_url}/artifacts/download?file_type=${file_type}` を**外部ブラウザで開く**だけ。ストリーミングもディスク書き込みもしない。
  - 根拠: `src/desktop/commands/download_artifact.ts:13-50`(`downloadArtifacts`)、特に `:48-49`(URL 構築 + `vscode.open` 外部ブラウザ)、`src/desktop/command_names.ts:53`(`DOWNLOAD_ARTIFACTS='gl.downloadArtifacts'`)。

目的は、上記 2 機能を Eclipse プラグインに移植し、**薄い縦切りで**パリティを 1 歩進めること。ライブポーリング・ANSI 色描画・複数 file_type 選択などの「厚み」は本 PR では割愛し #12 のフォローアップに残す。

## 2. 対象範囲

1. **ジョブトレース表示**: サイドバー JobNode の context menu「Display Log」から、当該ジョブの trace をテキスト取得 → 制御文字を除去 → 一時ファイルに書き出し → エディタで開く。手動更新(同コマンド再実行)対応。
2. **アーティファクトダウンロード**: JobNode の context menu「Download Artifacts」から、`${job.webUrl}/artifacts/download?file_type=archive` を外部ブラウザで開く。

## 3. 対象外(本 PR では実装しない・#12 フォローアップ)

- running ジョブの**ライブポーリング**(3 秒 ETag 再取得・autoscroll・エディタ可視/不可視連動・ジョブ終了検知)。本 PR は「一回取得 + 手動更新(コマンド再実行)」のみ。
- trace の **ANSI 色描画**。本 PR は制御文字を strip したプレーンテキスト表示のみ。
- **複数 file_type の選択 UI**(VSCode QuickPick 相当)。本 PR は `file_type=archive` 固定。
- **IDE 内でのアーティファクト REST ダウンロード**(バイナリを disk へ)。VSCode もしていない。ブラウザ委譲のみ。
- pending ジョブ用 webview プレースホルダ(VSCode `gl.waitForPendingJob`)。
- raw trace の「名前を付けて保存」(VSCode `gl.saveRawJobTrace`)。
- メニュー可視化の精密ゲート(VSCode の `with-trace`/`with-artifacts` 相当)。本 PR は JobNode に**常時表示**し、trace なし(404)/artifacts なしは実行時に優雅に処理する。

## 4. 現在の課題(既存コードの前提)

- `GitLabApiClient` の公開メソッドはすべて Gson デシリアライズ前提(`fetchObject`/`fetchListWithinDeadline` 等)。生テキストを返す公開 API が無い。
  - 根拠: `src/main/kotlin/com/gitlab/eclipse/api/GitLabApiClient.kt:80-87`(`fetchObject`=`gson.fromJson(sendGet(...).body(), type)`)。
- ただし最下層 `sendGet` は `HttpResponse<String>` を返す(`HttpResponse.BodyHandlers.ofString()` 相当のテキスト応答)。**テキスト取得には byte/stream 層の新設は不要**で、`sendGet(...).body()`(String)をそのまま返す公開ラッパを 1 つ足せば足りる。
  - 根拠: `GitLabApiClient.kt:222-241`(`private fun sendGet(...): HttpResponse<String>`、接続固定・非 2xx→`GitLabApiException`)。
- ブラウザ起動ユーティリティは既存。`BrowserLauncher.open(url)` が UI スレッドへマーシャルし外部ブラウザで開く(例外は catch/log)。
  - 根拠: `src/main/kotlin/com/gitlab/eclipse/navigation/BrowserLauncher.kt:12-20`。
- 一時ファイルをエディタで開くパターンは既存(MCP 設定)。`EFS.getLocalFileSystem().getStore(path.toUri())` + `IDE.openEditorOnFileStore(page, fileStore)`。
  - 根拠: `src/main/kotlin/com/gitlab/eclipse/mcp/McpConfigEditorOpener.kt`(全体)。
- Console 表示・仮想ドキュメント・temp-file ヘルパは**既存コードに存在しない**(`org.eclipse.ui.console` 依存も未追加)。本設計は Console 依存を追加せず、temp-file + `IDE.openEditorOnFileStore` を採用(依存追加ゼロ)。

## 5. 要件

### 機能要件

- **FR-1**: JobNode を選択して「Display Log」を実行すると、当該ジョブの trace を取得・整形してエディタに表示する。
- **FR-2**: 同一ジョブに対する「Display Log」再実行は、整形結果で一時ファイルを再書き込みし、既存エディタを更新(再オープン=フォーカス)する。これが手動更新の手段。
- **FR-3**: trace が未取得(未開始/消去済み=404)の場合、エディタを開かず「ログはまだありません」旨を通知する。
- **FR-4**: JobNode を選択して「Download Artifacts」を実行すると、`${job.webUrl}/artifacts/download?file_type=archive` を外部ブラウザで開く。
- **FR-5**: 両コマンドは JobNode の context menu に常時表示される(PipelineNode/その他ノードには出さない)。

### 非機能要件

- **NFR-1(接続固定)**: trace の GET は、JobNode がロードされた接続(instance URL + auth fingerprint)に固定する。ノードの `sourceInstanceUrl`/`sourceAuthFingerprint` と現行接続が不一致なら GET を行わず通知のみ(既存 `pinnedConnectionFor` を流用)。
- **NFR-2(トークン非漏洩)**: 例外メッセージ・監査ログに token/レスポンスボディを出さない。既存 `GitLabApiException`(message=status code)/監査ログ規律を踏襲。
- **NFR-3(UI スレッド規律)**: ネットワーク I/O・ファイル書き込みは背景コルーチン(共有 `Dispatchers.IO` scope)で行い、エディタ起動・通知のみ UI スレッド(`asyncExec`)。`CancellationException` は再送、終端 catch + finally。
- **NFR-4(依存追加ゼロ)**: `build.gradle.kts` の依存・icon を追加しない。model 変更もしない。

## 6. 前提条件と制約

- `develop` の既存パッケージ体系内に追加(ディレクトリ構成変更禁止)。
- JobNode は数値 `projectId: Long?` と `job: GitLabJob`(`id: Long`, `webUrl: String`, `status`)を保持。trace パスは数値 projectId を使う(VSCode と同じ。`RepositoryContext.projectId` の String エンコード値ではない)。
  - 根拠: `src/main/kotlin/com/gitlab/eclipse/views/sidebar/SidebarNode.kt:175-184`(`JobNode(job, projectId: Long?, sourceInstanceUrl, sourceAuthFingerprint)`、`activationUrl=job.webUrl`)、`src/main/kotlin/com/gitlab/eclipse/api/model/GitLabJob.kt:6-13`。
- アーティファクトのブラウザダウンロードは、外部ブラウザが当該 GitLab インスタンスにログイン済みであることに依存(VSCode と同一の既知制限)。
- headless devcontainer では SWT/Browser/エディタ起動を実行できないため、UI/スレッド/エディタ起動は**手動検証手順**(PR 説明文)で実機確認する。

## 7. システム構成 / コンポーネントの責務

| コンポーネント | 種別 | 責務 |
|---|---|---|
| `GitLabApiClient.fetchText(path, connection)` | 変更(公開メソッド追加) | 既存 `sendGet(path, emptyMap(), connection=connection).body()` を返す薄いラッパ。接続固定・エラー処理は sendGet を再利用。 |
| `JobTraceService` | 新規 | `getTrace(projectId: Long, jobId: Long, connection): String` = `apiClient.fetchText("/projects/$projectId/jobs/$jobId/trace", connection)`。`JobActionService` と同じワンライナー様式。 |
| `TraceFormatter.stripTraceFormatting(raw): String` | 新規(純関数) | ANSI CSI/SGR エスケープ除去、GitLab `section_start/end` マーカー除去、`\r` overwrite 解決、改行正規化。TDD 対象。 |
| `JobLogEditorOpener` | 新規 | 整形テキストを安定パス一時ファイル `gl-job-<projectId>-<jobId>.log` へ UTF-8 書き出し → `IDE.openEditorOnFileStore`。McpConfigEditorOpener を汎用化。UI スレッドで呼ぶ。 |
| `DisplayJobLogHandler` | 新規 | thin SWT `AbstractHandler`。UI で JobNode 解決 → 背景コルーチンで pin→fetchText→strip→temp 書き → `asyncExec` で `JobLogEditorOpener` 起動。エラー/404 通知。 |
| `DownloadArtifactsHandler` | 新規 | thin SWT `AbstractHandler`。UI で JobNode 解決 → URL 構築 → `BrowserLauncher.open(url)`。ネットワーク I/O なし。 |
| `plugin.xml` | 変更 | command×2 / handler×2 / popup×2(`instanceof JobNode` visibleWhen)。既存 job action ブロックと同形状。 |

## 8. 処理フロー

### 8.1 Display Log(trace)

1. ユーザーが JobNode を選択し context menu「Display Log」実行。
2. ハンドラ(UI スレッド): `selectedSidebarNode<JobNode>()` で JobNode 取得。取得不可 → 何もしない。
3. `projectId == null` → 「ログを取得できません」通知して終了(数値 projectId 必須)。
4. 背景コルーチン(共有 `Dispatchers.IO` scope):
   1. `pinnedConnectionFor(apiClient, node.sourceInstanceUrl, node.sourceAuthFingerprint)` で接続固定。`null`(不一致/Unstable)→ 通知して `return@launch`。
   2. `JobTraceService.getTrace(projectId, job.id, connection)` を呼ぶ。
   3. 成功: `stripTraceFormatting(raw)` → temp ファイル書き込み → `asyncExec { JobLogEditorOpener.open(...) }`。
   4. `GitLabApiException`:
      - statusCode == 404 → 「ログはまだありません(未開始/消去済み)」通知。
      - それ以外 → generic 通知 + 監査ログ(token/body 非出力)。
   5. `HttpTimeoutException`/`IOException` → 通知 + 監査ログ。
   6. `CancellationException` → rethrow。終端 `catch (Exception)` で log+notify。`finally` は本 READ では解放対象リソースなし(ガード未使用)。

### 8.2 Download Artifacts

1. ユーザーが JobNode を選択し context menu「Download Artifacts」実行。
2. ハンドラ(UI スレッド): JobNode 取得。`job.webUrl` から URL `${webUrl}/artifacts/download?file_type=archive` 構築。
3. `BrowserLauncher.open(url)`(UI スレッドマーシャル + 例外 catch は BrowserLauncher 内)。

## 9. API / インターフェース

### 追加 REST 呼び出し

- **trace**: `GET {instanceUrl}/api/v4/projects/{projectId}/jobs/{jobId}/trace`(数値 projectId)。応答=生ログ(text)。`Authorization: Bearer <token>`(接続 snapshot 由来)。非 2xx→`GitLabApiException`。
  - VSCode 根拠: `gitlab_service.ts:935-946`。
- **artifacts**: REST 呼び出しなし。ブラウザで `{job.webUrl}/artifacts/download?file_type=archive` を開く(GitLab web UI エンドポイント、`/api/v4` ではない)。
  - VSCode 根拠: `download_artifact.ts:48-49`。

### 追加 Kotlin シグネチャ(案)

```kotlin
// GitLabApiClient
fun fetchText(path: String, connection: ConnectionSnapshot? = null): String =
  sendGet(path, emptyMap(), connection = connection).body()

// JobTraceService
fun getTrace(projectId: Long, jobId: Long, connection: ConnectionSnapshot): String =
  apiClient.fetchText("/projects/$projectId/jobs/$jobId/trace", connection)

// TraceFormatter
fun stripTraceFormatting(raw: String): String
```

## 10. データモデル

- **model 変更なし**。`GitLabJob`(`id`,`name`,`status`,`stage`,`webUrl`,`allowFailure`)をそのまま使用。artifacts 有無フィールド・started_at/erased_at は追加しない(常時表示ゲートのため不要)。

## 11. エラー処理

| 事象 | 検出 | 挙動 |
|---|---|---|
| projectId null | ハンドラ UI スレッド | 通知「ログを取得できません」。GET せず。 |
| instance/auth 不一致・接続不安定 | `pinnedConnectionFor`→null / `UnstableConnectionException` | 通知。GET せず(誤インスタンス送信・資格情報漏洩を防止)。 |
| trace 404(未開始/消去済み) | `GitLabApiException.statusCode==404` | 通知「ログはまだありません」。エディタ開かず。 |
| 403 等アクセス不可 | `GitLabApiException` | generic 通知 + 監査ログ。 |
| timeout/IO | 例外 | generic 通知 + 監査ログ。 |
| temp ファイル書き込み失敗 | `IOException` | generic 通知 + 監査ログ。エディタ開かず。 |
| artifacts(ブラウザ起動失敗) | `BrowserLauncher` 内 catch | log のみ(既存挙動)。 |

- 監査ログ・例外メッセージに token / レスポンスボディを出さない(NFR-2)。

## 12. タイムアウトとリトライ

- trace GET は既存 `sendGet` の既定 `REQUEST_TIMEOUT_SECONDS` を用いる(単発リクエスト)。リトライしない(VSCode も trace 単発にリトライなし)。
- ライブポーリングは本 PR 対象外(手動更新のみ)。

## 13. 冪等性

- trace GET・artifacts ブラウザ起動はいずれも**べき等な READ**。サーバ状態を変更しない。二重実行はエディタ再オープン/ブラウザ再オープンに留まり、副作用なし。よって `InFlightWriteGuard` は使用しない。

## 14. 並行処理

- trace 取得は共有 `Dispatchers.IO` scope 上の 1 コルーチン。UI 状態(エディタ起動・通知)は `asyncExec` で UI スレッドに直列化。
- 同一ジョブへの連続実行: 安定 temp パスにより後発の書き込みが先発を上書きし、`IDE.openEditorOnFileStore` は同一 fileStore に対し既存エディタへフォーカス。競合破壊なし(最後の書き込みが表示される)。
- READ は接続 snapshot に固定するため、実行中の設定変更で誤インスタンスへ送信しない(seqlock `captureConnection` 由来)。

## 15. 認証と認可

- trace GET は接続 snapshot の `Bearer <token>` を使用(`sendGet` 既存経路)。preference/token を直接読まない(pin 済)。
- artifacts はブラウザの GitLab セッションに委譲(トークンをプラグインから渡さない)。
- 認可失敗(403/404)は上記エラー処理どおり通知。既存/存在秘匿のため GitLab は 404 を返しうる。

## 16. ログ、監視、監査

- 失敗時のみ監査ログ 1 行(action=表示ログ/DL、instanceUrl、projectId、jobId、outcome、httpStatus/correlationId)。token/body 非出力。既存 `writeAuditMessage` の READ 版様式を踏襲(verb を read 系に)。
- 正常時は冗長ログを出さない。

## 17. 障害時の復旧方法

- trace が開けない(ネットワーク/権限/404)場合、ユーザーは通知内容を確認し再実行(手動更新)。恒久障害でも READ のためサーバ状態に影響なし。
- temp ファイルは安定パス(OS 一時領域)に置き、再実行で上書き。プラグイン再起動後も再取得可能。

## 18. 既存機能への影響

- `GitLabApiClient` に公開メソッド 1 つ追加(既存 `sendGet` を再利用・既存メソッド不変)。
- JobNode/PipelineNode/PropertyTester/既存 job action(retry/cancel/play)には変更なし。
- plugin.xml は command/handler/popup を**追加**のみ(既存エントリ不変)。
- build 依存・model・ディレクトリ構成の変更なし。

## 19. 移行方法

- 新規機能追加のみ。データ移行なし。マージで即有効(新 context menu 項目が JobNode に出現)。

## 20. ロールバック方法

- 実装 PR を revert すれば完全に元へ戻る(追加ファイル削除 + `GitLabApiClient`/plugin.xml の追加分除去)。既存機能に破壊的変更がないため副作用なし。

## 21. テスト方針

- **単体(headless で検証可)**:
  - `stripTraceFormatting`: ANSI SGR/CSI 除去、`section_start/end` 除去、`\r` overwrite、改行正規化、空入力/非制御入力の恒等性を TDD。
  - `JobTraceService.getTrace`: モック `GitLabApiClient` で正しいパス `/projects/{id}/jobs/{jobId}/trace` と connection 引き回しを検証。
  - `GitLabApiClient.fetchText`: モック http client で connection pin(instanceUrl/Bearer)を検証、非 2xx→例外伝播。
  - ハンドラの純ロジック(URL 構築 `${webUrl}/artifacts/download?file_type=archive`、projectId null 分岐、404 分岐)を可能な範囲で抽出しテスト。
- **手動(実機・PR 説明文にチェックリスト)**: Display Log 表示/整形結果/404 通知/手動更新(再実行での更新)、Download Artifacts のブラウザ起動、接続変更時の pin 挙動、権限 403 時の通知。
- ベースライン: 既存 36 失敗(SWT-env)は不変。検証=対象テスト PASS + ベースライン外の新規失敗ゼロ + 変更ファイル detekt 0。

## 22. 受け入れ条件

- **AC-1**: JobNode の「Display Log」で trace が整形表示される(ANSI/section マーカー/`\r` が除去され可読)。
- **AC-2**: 「Display Log」再実行で表示内容が更新される(手動更新)。
- **AC-3**: trace 404 でエディタを開かず「ログはまだありません」通知が出る。
- **AC-4**: JobNode の「Download Artifacts」で `${webUrl}/artifacts/download?file_type=archive` が外部ブラウザで開く。
- **AC-5**: 両コマンドが JobNode の context menu にのみ表示される(他ノードに出ない)。
- **AC-6**: instance/auth 不一致時に trace GET を行わず通知のみ(誤送信・資格情報漏洩なし)。
- **AC-7**: 例外メッセージ・監査ログに token/レスポンスボディが出ない。
- **AC-8**: build 依存・model・既存機能の破壊的変更がない(detekt 0、ベースライン外新規失敗 0)。

## 23. 未決事項

- **U1**: trace の `Accept` ヘッダ。既存 `sendGet` は `Accept: application/json` を固定送信する(`GitLabApiClient.kt:231`)。GitLab の `/trace` は Accept を無視して text を返すため実害はないと想定するが、`fetchText` 用に `Accept: text/plain` を送る専用経路を用意すべきか(sendGet の分岐 or 別メソッド)は要判断。過剰実装回避のため、まずは既存 sendGet 流用(json Accept のまま)を第一候補とする。
- **U2**: temp ファイルの配置とクリーンアップ。OS 一時領域に安定パスで置き上書き運用とするが、明示的削除(deleteOnExit / エディタクローズ時削除)を行うか。まずは上書き運用のみ(明示削除なし)を第一候補。
- **U3**: 監査ログの READ 版様式。既存 `writeAuditMessage` を READ 用に一般化するか、READ 専用の簡易ログにするか。挙動(token/body 非出力)は不変。
- **U4**: `stripTraceFormatting` の `\r` overwrite の厳密仕様。GitLab の進捗行は `\r` で行頭に戻り上書きする。行内 `\r` は「最後の `\r` 以降を採用」で近似するが、より厳密なターミナルエミュレーションは対象外(近似で受容)。

## 24. 想定されるリスク

- **R-1(検証不能領域)**: エディタ起動・UI スレッドマーシャル・context menu 配線は headless で検出できず、実機でのみ露見。→ 実装は fable、手動検証手順を PR に明記。
- **R-2(plugin.xml 配線ミス)**: command id / handler FQN / visibleWhen の不一致で menu が黙って消える/誤発火。→ 既存 job action ブロックの形状を厳密に踏襲し、id 一致・FQN 実在・well-formed(jshell)を検証。
- **R-3(接続 pin の抜け)**: READ 経路で pin を通さないと、設定変更中に誤インスタンス送信・資格情報漏洩。→ PR-2 の READ/WRITE 両経路 pin 実績(`sendGet` の `connection ?: captureConnection()`)と `pinnedConnectionFor` を必ず通す。
- **R-4(大容量 trace)**: 巨大ログを全文テキストで取得しエディタに開くとメモリ/描画負荷。VSCode も全文取得のため同等だが、本 PR は単発取得のみで緩和。極端な場合の上限は未対応(#12 フォローアップ候補)。
