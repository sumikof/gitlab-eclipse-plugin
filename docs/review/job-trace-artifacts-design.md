# Job trace 表示 + Artifacts ダウンロード 設計書(Phase 4 PR-3)

- 対象リポジトリ: `sumikof/gitlab-eclipse-plugin`
- ベースブランチ: `develop`(tip `e5a0a9a`)
- パリティ台帳: #7(D14 CI ドメイン)/ ロードマップ: #8 / フェーズ issue: #12
- 参照: VSCode 拡張 `gitlab-workflow` v6.85.3(`./out/gitlab-vscode-extension`、読み取り専用)
- 本設計書はレビュー専用。実装 PR・マージ先には含めない。
- 改訂履歴: v1 初版 / v2 Codex 設計レビュー #39 R1(P1×5+P2×3)反映 / v3 R2(P1×1+P2×2: commit critical section・UI 再確認・既存エディタ明示再読込)反映 / **v4 R3(P1×1+P2×2: generation 単調非再利用トークン+refcount ライフサイクル・REPLACE_EXISTING+ATOMIC_MOVE と fallback・§9 を openOrReload に統一)反映**。

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

1. **ジョブトレース表示**: サイドバー JobNode の context menu「Display Log」から、当該ジョブの trace をテキスト取得 → 制御文字を除去 → **ユーザー専用状態ディレクトリ**の一時ファイルに安全に書き出し → エディタで開く。手動更新(同コマンド再実行)対応。
2. **アーティファクトダウンロード**: JobNode の context menu「Download Artifacts」から、`${job.webUrl}/artifacts/download?file_type=archive` を外部ブラウザで開く(webUrl の妥当性検証を伴う)。

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
- ただし最下層 `sendGet` は `HttpResponse<String>` を返す(テキスト応答)。**テキスト取得には byte/stream 層の新設は不要**で、`sendGet(...).body()`(String)をそのまま返す公開ラッパを 1 つ足せば足りる。
  - 根拠: `GitLabApiClient.kt:222-241`(`private fun sendGet(...): HttpResponse<String>`、接続固定・非 2xx→`GitLabApiException`)。
- **`sendGet` は非 2xx 時に `GitLabApiException(statusCode, body)` を投げるが correlationId を渡していない**(`sendPost` は line 208 で渡す)。障害調査(§16)のため sendGet 側の伝播修正が必要。
  - 根拠: `GitLabApiClient.kt:238`(correlationId なし)対 `:208`(あり)。
- ブラウザ起動ユーティリティは既存。`BrowserLauncher.open(url)` が UI スレッドへマーシャルし外部ブラウザで開く。ただし**戻り値なし・例外は内部で catch/log するだけ**で、呼び出し側が失敗を検知できない。
  - 根拠: `src/main/kotlin/com/gitlab/eclipse/navigation/BrowserLauncher.kt:12-20`。
- 一時ファイルをエディタで開くパターンは既存(MCP 設定)。`EFS.getLocalFileSystem().getStore(path.toUri())` + `IDE.openEditorOnFileStore(page, fileStore)`。
  - 根拠: `src/main/kotlin/com/gitlab/eclipse/mcp/McpConfigEditorOpener.kt`(全体)。
- Console 表示・仮想ドキュメント・temp-file ヘルパは**既存コードに存在しない**(`org.eclipse.ui.console` 依存も未追加)。本設計は Console 依存を追加せず、状態ディレクトリの一時ファイル + `IDE.openEditorOnFileStore` を採用(依存追加ゼロ)。
- `GitLabJob.webUrl` は **`String?`(nullable、既定 null)**。API 応答で `web_url` 欠落時に null になり得る。
  - 根拠: `src/main/kotlin/com/gitlab/eclipse/api/model/GitLabJob.kt:11`。

## 5. 要件

### 機能要件

- **FR-1**: JobNode を選択して「Display Log」を実行すると、当該ジョブの trace を取得・整形してエディタに表示する。
- **FR-2**: 同一ジョブに対する「Display Log」再実行は、整形結果で一時ファイルを再書き込みし、既存エディタを更新(再オープン=フォーカス)する。これが手動更新の手段。連続実行時は**最新の取得結果のみ**が表示される(§14 参照)。
- **FR-3**: trace を取得できない(404 等)場合、エディタを開かず**非断定的**な通知を出す(「ログが存在しないかアクセスできません」)。
- **FR-4**: JobNode を選択して「Download Artifacts」を実行すると、`job.webUrl` の妥当性(非 null・非空・http(s) scheme・host あり)を検証し、妥当なら `${job.webUrl}/artifacts/download?file_type=archive` を外部ブラウザで開く。妥当でない/起動失敗時はユーザーに通知し監査ログを残す。
- **FR-5**: 両コマンドは JobNode の context menu に常時表示される(PipelineNode/その他ノードには出さない)。

### 非機能要件

- **NFR-1(接続固定)**: trace の GET は、JobNode がロードされた接続(instance URL + auth fingerprint)に固定する。ノードの `sourceInstanceUrl`/`sourceAuthFingerprint` と現行接続が不一致なら GET を行わず通知のみ(既存 `pinnedConnectionFor` を流用)。
- **NFR-2(機密の非漏洩)**: (a) 例外メッセージ・監査ログに token/レスポンスボディを出さない。(b) **保存する trace ファイルはユーザー専用**とし、他ユーザーが閲覧・改竄できない場所・権限で扱う(§7.1)。
- **NFR-3(UI スレッド規律)**: ネットワーク I/O・**ファイル書き込み**は背景コルーチン(共有 `Dispatchers.IO` scope)で行い、**エディタ起動・通知のみ** UI スレッド(`asyncExec`)。`CancellationException` は再送、終端 catch + finally。
- **NFR-4(依存追加ゼロ)**: `build.gradle.kts` の依存・icon を追加しない。model 変更もしない。
- **NFR-5(接続分離)**: 別インスタンス/別アカウントの同一 ID ジョブが、同一の一時ファイル/エディタを共有しない(§7.1)。

## 6. 前提条件と制約

- `develop` の既存パッケージ体系内に追加(ディレクトリ構成変更禁止)。
- JobNode は数値 `projectId: Long?` と `job: GitLabJob`(`id: Long`, `webUrl: String?`, `status: String?`)を保持。trace パスは数値 projectId を使う(VSCode と同じ。`RepositoryContext.projectId` の String エンコード値ではない)。
  - 根拠: `src/main/kotlin/com/gitlab/eclipse/views/sidebar/SidebarNode.kt:175-184`、`GitLabJob.kt:6-13`。
- アーティファクトのブラウザダウンロードは、外部ブラウザが当該 GitLab インスタンスにログイン済みであることに依存(VSCode と同一の既知制限)。
- headless devcontainer では SWT/Browser/エディタ起動を実行できないため、UI/スレッド/エディタ起動は**手動検証手順**(PR 説明文)で実機確認する。

## 7. システム構成 / コンポーネントの責務

| コンポーネント | 種別 | 責務 | スレッド |
|---|---|---|---|
| `GitLabApiClient.fetchText(path, connection)` | 変更(公開メソッド追加) | 既存 `sendGet(path, emptyMap(), connection=connection).body()` を返す薄いラッパ。接続固定・エラー処理は sendGet を再利用。 | 背景 |
| `GitLabApiClient.sendGet`(既存) | 変更 | 非 2xx 時の `GitLabApiException` に `correlationId(response)` を付与(sendPost と対称化)。 | 背景 |
| `JobTraceService` | 新規 | `getTrace(projectId: Long, jobId: Long, connection): String` = `apiClient.fetchText("/projects/$projectId/jobs/$jobId/trace", connection)`。 | 背景 |
| `TraceFormatter.stripTraceFormatting(raw): String` | 新規(純関数) | ANSI CSI/SGR エスケープ除去、GitLab `section_start/end` マーカー除去、`\r` overwrite 解決、改行正規化。TDD 対象。 | 任意 |
| `JobLogFileStore` | 新規 | 整形テキストを**ユーザー専用状態ディレクトリ**の接続名前空間つき一時ファイルへ**安全に書き込み**、`IFileStore`(またはパス)を返す。原子的置換・権限・symlink 対策(§7.1)。**書き込みは背景スレッド**。 | 背景 |
| `JobLogEditorOpener` | 新規 | 与えられた `IFileStore` を開く。**既存エディタがあれば明示的に再読込**、無ければ `IDE.openEditorOnFileStore` で新規オープン(§7.2)。書き込みは持たない。 | UI |
| `JobLogRequestCoordinator` | 新規 | per-key(接続ハッシュ+projectId+jobId)の **単調非再利用** request generation と mutex を管理し、**最新 generation のみ**が commit(判定→atomic move)→open を完了できるよう直列化・supersede する。generation は UI runnable 完了まで refcount で保持(§14)。 | 背景 |
| `DisplayJobLogHandler` | 新規 | thin SWT `AbstractHandler`。UI で JobNode 解決 → 背景コルーチンで pin→fetchText→strip→(coordinator 経由で)write → 最新なら `asyncExec` で opener 起動。エラー/404 通知・監査。 | UI→背景→UI |
| `BrowserLauncher.openChecked(url): Boolean`(または結果型) | 変更(追加経路) | 既存 `open(url): Unit` は不変のまま、**成否を返す**経路を追加。artifacts ハンドラが失敗を検知して通知・監査できるようにする。 | UI |
| `DownloadArtifactsHandler` | 新規 | thin SWT `AbstractHandler`。UI で JobNode 解決 → webUrl 検証 → URL 構築 → `openChecked` → 失敗時に通知+構造化監査。ネットワーク I/O なし。 | UI |
| `plugin.xml` | 変更 | command×2 / handler×2 / popup×2(`instanceof JobNode` visibleWhen)。既存 job action ブロックと同形状。 | - |

### 7.1 一時ファイルの安全な取り扱い(P1-2/P1-3 反映)

- **配置**: OS 共有一時ディレクトリ(`/tmp` 等)を使わず、**プラグインのユーザー専用状態ディレクトリ**(`Platform.getStateLocation(bundle)` 配下の `job-logs/` サブディレクトリ)に置く。state location は各ユーザーのワークスペース metadata 配下で、共有 world-writable ではない。
- **接続名前空間化(NFR-5)**: ファイル/エディタ識別子に `normalizeInstanceUrl(instanceUrl)` + `authFingerprint` の**非可逆ハッシュ**(例: SHA-256 の先頭 N 桁)を含める。ファイル名例: `job-<connHash>-<projectId>-<jobId>.log`。別インスタンス/別アカウントの同一 (projectId, jobId) は別 fileStore になる。
- **安全な生成**: 既存ファイルが**シンボリックリンクの場合は追従せず失敗**(`LinkOption.NOFOLLOW_LINKS` で検査、リンクなら拒否)。書き込みは同ディレクトリ内の一時名(generation 専用スクラッチ)へ行い、**既存宛先を置換する原子移動** `Files.move(scratch, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)` で置換する(部分書き込みの露出防止・2 回目以降の再実行で既存 dest を確実に置換=P1-102)。`ATOMIC_MOVE` 非対応プロバイダで `AtomicMoveNotSupportedException` が出た場合は、同一ディレクトリ内での **`REPLACE_EXISTING` 単独の move**(非原子・極短時間の窓を許容)へフォールバックし、フォールバックした旨を debug ログに残す。可能なプラットフォームでは POSIX 権限 `rw-------`(0600)を best-effort で設定(Windows 等 POSIX 非対応は state location のユーザー専用性に依拠)。テストで **既存 dest への 2 回目書き込みが成功**することを検証。
- **クリーンアップ**: プラグイン停止時に `job-logs/` を best-effort で削除。再実行時は同名を原子的に上書き。スクラッチ一時ファイル(generation 専用)は commit 成否に関わらず finally で破棄。

### 7.2 既存エディタの明示再読込(P2-61 反映)

`IDE.openEditorOnFileStore` は、同一 `IFileStore` に対しては既に開いているエディタを**再利用してフォーカスするだけ**で、外部で原子的置換した内容を文書バッファへ再読込しない(`McpConfigEditorOpener.kt:9-12` と同じ挙動)。このままでは FR-2 の手動更新が表示に反映されない。対策として `JobLogEditorOpener.openOrReload(fileStore)` は:

- 対象 `IFileStore`(= `FileStoreEditorInput`)に一致する開いているエディタを `IWorkbenchPage.findEditor(input)` で探索。
- **存在すれば**、そのエディタが非 dirty であることを前提に**明示的に文書を再読込**(text editor の `doRevertToSaved()` 相当、または document provider 経由の resetDocument)してフォーカス。未保存編集は本ファイルが使い捨てのため発生し得ないが、万一 dirty の場合も破棄して最新内容を表示する(read-only 意図)。
- **存在しなければ** `IDE.openEditorOnFileStore(page, fileStore)` で新規オープン。
- 本メソッドは UI スレッドでのみ呼ぶ。呼び出し前に §8.1 手順 5 の generation 再確認を通過していること(stale runnable による誤表示防止)。

## 8. 処理フロー

### 8.1 Display Log(trace)

1. ユーザーが JobNode を選択し context menu「Display Log」実行。
2. ハンドラ(UI スレッド): `selectedSidebarNode<JobNode>()` で JobNode 取得。取得不可 → 何もしない。
3. `projectId == null` → 「ログを取得できません」通知して終了(数値 projectId 必須)。
4. `connKey = hash(normalizeInstanceUrl(node.sourceInstanceUrl) + node.sourceAuthFingerprint)`、`key = (connKey, projectId, job.id)`。`JobLogRequestCoordinator` から本実行の `generation`(単調増加)を取得(同 key の以前の実行を supersede)。
5. 背景コルーチン(共有 `Dispatchers.IO` scope):
   1. `pinnedConnectionFor(apiClient, node.sourceInstanceUrl, node.sourceAuthFingerprint)` で接続固定。`null`(不一致/Unstable)→ 通知して `return@launch`。
   2. `JobTraceService.getTrace(projectId, job.id, connection)`。
   3. 成功: `stripTraceFormatting(raw)` → **本 generation 専用のスクラッチ一時ファイル**へ背景スレッドで安全書き込み(§7.1)。この時点では可視ファイル(エディタが開く安定パス)は変更しない。
   4. **コミット critical section(per-key mutex 内で不可分に実行)**: (a) 本 `generation` が同 key の**最新**か確認。最新でなければ**スクラッチを破棄して終了**(可視ファイルへ move しない=stale な巻き戻りを起こさない=P1-203)。(b) 最新なら、スクラッチを可視ファイルへ `ATOMIC_MOVE` で置換し、`asyncExec` で UI 反映を予約する。**generation 判定と atomic move は同一 mutex 区間で行い、判定→置換の割り込みを許さない**。
   5. **UI 反映(`asyncExec` runnable)**: runnable 冒頭で **再度**本 `generation` が同 key の最新か確認する(判定〜runnable 実行の間に後発が開始しても古い結果を表示しない=P2-117)。stale なら何もしない。最新なら `JobLogEditorOpener.openOrReload(fileStore)`(§7.2)で新規オープンまたは既存エディタの明示再読込。
   6. `GitLabApiException`:
      - statusCode == 404 → 「ログが存在しないかアクセスできません」**非断定**通知 + 監査ログ(status/correlationId、token/body 非出力)。
      - それ以外 → generic 通知 + 監査ログ。
   7. `HttpTimeoutException`/`IOException`(GET・ファイル書き込み双方)→ generic 通知 + 監査ログ。スクラッチ一時ファイルは finally で破棄。
   8. `CancellationException` → rethrow。終端 `catch (Exception)` で log+notify。`finally` では **per-key mutex とスクラッチ一時ファイルのみ**を解放する。**generation トークン(`latest[key]`)は解放しない**(§14 のライフサイクル参照)。UI runnable が後で `latest[key]` を参照するため、背景 finally で消すと判定不能・ABA を招く(P1-132)。key エントリの回収は §14 の refcount による(UI runnable 完了後)。

### 8.2 Download Artifacts

1. ユーザーが JobNode を選択し context menu「Download Artifacts」実行(UI スレッド)。
2. JobNode 取得。`job.webUrl` を検証:
   - null または空白 → generic 通知 + 監査ログ、終了。
   - `URI.create(webUrl)` の scheme が `http`/`https` でない、または host が無い → generic 通知 + 監査ログ、終了。
3. URL `${webUrl}/artifacts/download?file_type=archive` を構築。
4. `BrowserLauncher.openChecked(url)`。失敗(false/例外)→ generic 通知 + 構造化監査ログ(action/instanceUrl/projectId/jobId/outcome、token/body 非出力)。

## 9. API / インターフェース

### 追加/変更 REST 呼び出し

- **trace**: `GET {instanceUrl}/api/v4/projects/{projectId}/jobs/{jobId}/trace`(数値 projectId)。応答=生ログ(text)。`Authorization: Bearer <token>`(接続 snapshot 由来)。非 2xx→`GitLabApiException`(correlationId 付与)。
- **artifacts**: REST 呼び出しなし。ブラウザで `{job.webUrl}/artifacts/download?file_type=archive` を開く(GitLab web UI エンドポイント)。

### 追加/変更 Kotlin シグネチャ(案)

```kotlin
// GitLabApiClient(追加)
fun fetchText(path: String, connection: ConnectionSnapshot? = null): String =
  sendGet(path, emptyMap(), connection = connection).body()

// GitLabApiClient.sendGet(変更): 非 2xx 分岐
throw GitLabApiException(response.statusCode(), response.body(), correlationId(response))

// JobTraceService(新規)
fun getTrace(projectId: Long, jobId: Long, connection: ConnectionSnapshot): String =
  apiClient.fetchText("/projects/$projectId/jobs/$jobId/trace", connection)

// TraceFormatter(新規・純関数)
fun stripTraceFormatting(raw: String): String

// JobLogFileStore(新規): 背景スレッドで安全書き込み
fun write(key: JobLogKey, text: String): org.eclipse.core.filesystem.IFileStore

// JobLogEditorOpener(新規): UI スレッドで開く/既存なら明示再読込(§7.2)
fun openOrReload(fileStore: IFileStore)

// BrowserLauncher(追加経路)
fun openChecked(url: String): Boolean   // 既存 open(url): Unit は不変
```

`JobLogKey` = `(connHash: String, projectId: Long, jobId: Long)`。

## 10. データモデル

- **model 変更なし**。`GitLabJob`(`id`,`name`,`status`,`stage`,`webUrl: String?`,`allowFailure`)をそのまま使用。artifacts 有無フィールド・started_at/erased_at は追加しない(常時表示ゲートのため不要)。

## 11. エラー処理

| 事象 | 検出 | 挙動 |
|---|---|---|
| projectId null | ハンドラ UI スレッド | 通知「ログを取得できません」。GET せず。 |
| instance/auth 不一致・接続不安定 | `pinnedConnectionFor`→null / `UnstableConnectionException` | 通知。GET せず(誤送信・資格情報漏洩防止)。 |
| trace 404 | `GitLabApiException.statusCode==404` | **非断定**通知「ログが存在しないかアクセスできません」+ 監査(status/correlationId)。エディタ開かず。 |
| 403 等アクセス不可・その他非 2xx | `GitLabApiException` | generic 通知 + 監査ログ(correlationId 含む)。 |
| timeout/IO(GET) | 例外 | generic 通知 + 監査ログ。 |
| trace ファイル書き込み失敗(IO/symlink 拒否/権限) | `IOException` 等 | generic 通知 + 監査ログ。エディタ開かず。 |
| 本実行が最新でない(supersede 済) | coordinator generation 比較 | open せず静かに終了(通知不要)。 |
| webUrl が null/空/不正 scheme・host | ハンドラ検証 | generic 通知 + 監査ログ。ブラウザ起動せず。 |
| artifacts ブラウザ起動失敗 | `openChecked`→false/例外 | generic 通知 + 構造化監査ログ。 |

- 監査ログ・例外メッセージに token / レスポンスボディを出さない(NFR-2a)。

## 12. タイムアウトとリトライ

- trace GET は既存 `sendGet` の既定 `REQUEST_TIMEOUT_SECONDS` を用いる(単発リクエスト)。リトライしない(VSCode も trace 単発にリトライなし)。
- ライブポーリングは本 PR 対象外(手動更新のみ)。

## 13. 冪等性

- trace GET・artifacts ブラウザ起動はいずれも**べき等な READ**。サーバ状態を変更しない。二重実行はエディタ再オープン/ブラウザ再オープンに留まり、サーバ副作用なし。よって書き込みガード(`InFlightWriteGuard`)は使用しないが、**表示の一貫性**のため §14 の直列化を行う。

## 14. 並行処理(P1-5 反映)

- 各「Display Log」実行は共有 `Dispatchers.IO` scope に独立 launch される。開始順と完了順は保証されないため、素朴な「後発が上書き」では**先発 GET の遅延完了が後発の新しい内容を古い内容へ巻き戻す**危険がある(AC-2 違反)。
- 対策: `JobLogRequestCoordinator` が per-key(`JobLogKey`)に
  1. **request generation**(単調増加カウンタ): 実行開始時に採番。
  2. **per-key mutex**: 「最新 generation 判定 + 可視ファイルへの `ATOMIC_MOVE` + UI 反映の予約」を**不可分の 1 区間**で実行する(下記)。
- **可視ファイルへの置換は generation 判定の後に、同一 mutex 区間で行う**(P2-203 反映)。フェッチ・整形・スクラッチ書き込み(generation 専用一時ファイル)は mutex 外で並行してよいが、可視ファイルを変える「コミット」だけは mutex 内:
  1. `if (generation != latest[key]) { スクラッチ破棄; return }` — stale なら**可視ファイルを一切変更しない**(旧内容への巻き戻り不能)。
  2. スクラッチ → 可視ファイルへ `ATOMIC_MOVE`。
  3. `asyncExec` で UI 反映を予約。
  これにより、後発が先に可視ファイルを更新した後で先発が古い内容へ上書きする経路が消える(判定と置換の間に割り込みが入らない)。
- **UI runnable 内でも generation を再確認**(P2-117 反映): `asyncExec` の予約〜実行の間に後発が開始し得るため、runnable 冒頭で `if (generation != latest[key]) return` を再チェックしてから `openOrReload` する。stale な UI runnable が古い結果を表示・フォーカスするのを防ぐ。
- **generation トークンのライフサイクル(P1-132 反映)**:
  - `generation` は**単調増加で決して再利用しないトークン**(プロセス内 `AtomicLong` を全 key 共通でインクリメント、または per-key の never-reset カウンタ)。同じ値が別実行に再割り当てされないため、UI runnable の再確認で **ABA が起きない**。
  - `latest[key]` は「その key で採番された最大 generation」を保持し、**背景コルーチンの `finally` では解放しない**。UI runnable がその後に参照するため。
  - **参照カウント付き cleanup**: 各実行は開始時に key の refcount を +1、UI runnable 完了時(または stale 早期 return 時)に −1。refcount が 0 になった時点で `latest[key]` エントリを回収してよい(long-lived な小さなマップだが、多数の job を閲覧しても増え続けないようにする)。
  - `finally` で解放するのは per-key **mutex**(write 直列化用)とスクラッチ一時ファイルのみ。mutex 解放は generation 判定の正しさに影響しない(判定・置換は既に mutex 区間で完了しているため)。
- 結果、同一 (接続, project, job) に対する連続/並行実行では**最新取得のみ**が可視ファイルとエディタに反映される。
- 別 key(別 job/別接続)は独立に進行(相互ブロックしない)。
- 接続 snapshot 固定により、実行中の設定変更でも誤インスタンス送信・資格情報漏洩は起きない(seqlock `captureConnection` 由来)。
- **テスト**: (a) 応答順を逆転(先発を後発より遅く完了)させ、可視ファイル/エディタ内容が**後発(最新)**になり先発が可視ファイルを更新しないこと。(b) generation 判定後・UI runnable 実行前に後発が最新化した場合、先発の runnable が open/reload を行わないこと。

## 15. 認証と認可

- trace GET は接続 snapshot の `Bearer <token>` を使用(`sendGet` 既存経路)。preference/token を直接読まない(pin 済)。
- artifacts はブラウザの GitLab セッションに委譲(トークンをプラグインから渡さない)。
- 認可失敗時、GitLab は存在秘匿のため 403 ではなく **404** を返し得る。したがって 404 を「ログ未生成」と断定せず、非断定通知に統一する(§11、P2-6)。

## 16. ログ、監視、監査

- 失敗時のみ監査ログ 1 行(action=表示ログ/DL、instanceUrl、projectId、jobId、outcome、httpStatus/correlationId)。token/body 非出力。既存 `writeAuditMessage` の READ 版様式を踏襲(verb を read 系に)。
- **correlationId 伝播**: `sendGet` の非 2xx 例外に `correlationId(response)` を付与する変更を行い(§4・§9)、HTTP 障害時に監査ログへ correlation ID を残せるようにする。`fetchText` のテストで (a) correlationId が例外に伝播すること、(b) token/body が出力されないことを検証。
- 正常時は冗長ログを出さない。

## 17. 障害時の復旧方法

- trace が開けない(ネットワーク/権限/404)場合、ユーザーは通知内容を確認し再実行(手動更新)。恒久障害でも READ のためサーバ状態に影響なし。
- 一時ファイルはユーザー専用状態ディレクトリに置き、再実行で原子的に上書き。プラグイン再起動後も再取得可能。

## 18. 既存機能への影響

- `GitLabApiClient` に公開メソッド 1 つ追加(`fetchText`)。加えて `sendGet` の非 2xx 例外に correlationId を付与する変更 → **全 GET 呼出(IssueService/MergeRequestService/PipelineService/JobService 等)の失敗例外に correlationId が載る**。`GitLabApiException.correlationId` は既定 null の追加フィールドで後方互換(値が入るだけ)。既存テストで body/status を検査しているものへの影響有無を確認する。
- `BrowserLauncher` に成否を返す経路(`openChecked` 等)を**追加**。既存 `open(url): Unit` は不変で、他呼出(chat webview / ShowDocumentation / preferences / sidebar double-click)に影響なし。
- JobNode/PipelineNode/PropertyTester/既存 job action(retry/cancel/play)には変更なし。
- plugin.xml は command/handler/popup を**追加**のみ(既存エントリ不変)。
- build 依存・model・ディレクトリ構成の変更なし。

## 19. 移行方法

- 新規機能追加のみ。データ移行なし。マージで即有効(新 context menu 項目が JobNode に出現)。

## 20. ロールバック方法

- 実装 PR を revert すれば完全に元へ戻る(追加ファイル削除 + `GitLabApiClient`/`BrowserLauncher`/plugin.xml の追加分除去)。既存機能に破壊的変更がないため副作用なし(correlationId 付与は追加情報のみ)。

## 21. テスト方針

- **単体(headless で検証可)**:
  - `stripTraceFormatting`: ANSI SGR/CSI 除去、`section_start/end` 除去、`\r` overwrite、改行正規化、空入力/非制御入力の恒等性を TDD。
  - `JobTraceService.getTrace`: モック `GitLabApiClient` で正しいパス `/projects/{id}/jobs/{jobId}/trace` と connection 引き回しを検証。
  - `GitLabApiClient.fetchText` / `sendGet`: モック http client で connection pin(instanceUrl/Bearer)、非 2xx→例外、**correlationId 伝播**、token/body 非出力を検証。
  - `JobLogFileStore`: 接続名前空間化(別 connHash→別パス)、**既存 dest への 2 回目書き込み成功**(REPLACE_EXISTING+ATOMIC_MOVE と非対応時 fallback)、symlink 拒否、(POSIX 環境で)権限 0600 を検証。
  - `JobLogRequestCoordinator`: **応答順逆転**で (a) 可視ファイルへの置換が最新 generation のみで起こり stale は可視ファイルを触らないこと(commit critical section)、(b) generation 判定後〜UI runnable 実行前に最新化した場合に stale runnable が open/reload しないこと(UI 再確認)、(c) 背景 `finally` が UI runnable より先に走っても `latest[key]` が保持され判定でき、単調非再利用トークンで ABA が起きないこと(generation ライフサイクル)を検証。
  - artifacts URL 構築 + webUrl 検証(null/空/不正 scheme・host→失敗、正常→`.../artifacts/download?file_type=archive`)を純ロジックとして検証。
- **手動(実機・PR 説明文にチェックリスト)**: Display Log 表示/整形/404 非断定通知/手動更新/連続実行で最新反映、Download Artifacts のブラウザ起動と webUrl 不正時通知、接続変更時 pin 挙動、別アカウント同一 ID ジョブでの非混在、権限 403 時の通知。
- ベースライン: 既存 36 失敗(SWT-env)は不変。検証=対象テスト PASS + ベースライン外の新規失敗ゼロ + 変更ファイル detekt 0。

## 22. 受け入れ条件

- **AC-1**: JobNode の「Display Log」で trace が整形表示される(ANSI/section マーカー/`\r` が除去され可読)。
- **AC-2**: 「Display Log」を連続実行しても、表示は**最新の取得結果**になる(応答順逆転でも可視ファイル/エディタが古い内容へ巻き戻らない)。既にエディタが開いている場合、再実行で**文書が明示再読込され最新内容が表示**される(単なるフォーカスに留まらない)。
- **AC-3**: trace 404 でエディタを開かず**非断定**通知(「存在しないかアクセスできません」)が出て、監査ログに status/correlationId が残る。
- **AC-4**: JobNode の「Download Artifacts」で、webUrl が妥当なとき `${webUrl}/artifacts/download?file_type=archive` が外部ブラウザで開く。webUrl 不正/起動失敗時は通知 + 監査ログが出る。
- **AC-5**: 両コマンドが JobNode の context menu にのみ表示される(他ノードに出ない)。
- **AC-6**: instance/auth 不一致時に trace GET を行わず通知のみ(誤送信・資格情報漏洩なし)。
- **AC-7**: 例外メッセージ・監査ログに token/レスポンスボディが出ない。GET 障害の監査に correlationId が載る。
- **AC-8**: 保存 trace ファイルはユーザー専用状態ディレクトリに接続名前空間つきで置かれ、別インスタンス/別アカウントの同一 ID ジョブが同一ファイル/エディタを共有しない。symlink 追従せず、原子的置換で書かれる。
- **AC-9**: build 依存・model・既存機能の破壊的変更がない(detekt 0、ベースライン外新規失敗 0)。

## 23. 未決事項

- **U1(解決)**: trace の `Accept` ヘッダ。既存 `sendGet` は `Accept: application/json` を固定送信するが、GitLab `/trace` は Accept を無視して text を返すため実害なし。専用 `text/plain` 経路は設けず既存 sendGet を流用する(過剰実装回避)。実機手動検証で text が正しく取得できることを確認。
- **U2(解決)**: 一時ファイルの配置・権限・クリーンアップは §7.1 で確定(ユーザー専用状態ディレクトリ・接続名前空間・原子的置換・best-effort 0600/NOFOLLOW・停止時削除)。
- **U3(解決)**: 監査ログの READ 版様式は既存 `writeAuditMessage` を READ 用に一般化する(token/body 非出力は不変)。
- **U4**: `stripTraceFormatting` の `\r` overwrite の厳密仕様。GitLab 進捗行は `\r` で行頭に戻り上書きする。行内 `\r` は「最後の `\r` 以降を採用」で近似する(厳密なターミナルエミュレーションは対象外・近似で受容)。テストで近似仕様を固定する。

## 24. 想定されるリスク

- **R-1(検証不能領域)**: エディタ起動・UI スレッドマーシャル・context menu 配線・並行直列化は headless で検出しにくく、実機でのみ露見しうる。→ 実装は fable、並行/接続分離は単体テストで可能な限り固定し、UI 部は手動検証手順を PR に明記。
- **R-2(plugin.xml 配線ミス)**: command id / handler FQN / visibleWhen の不一致で menu が黙って消える/誤発火。→ 既存 job action ブロックの形状を厳密に踏襲し、id 一致・FQN 実在・well-formed(jshell)を検証。
- **R-3(接続 pin/分離の抜け)**: READ 経路で pin を通さない、または一時ファイルを名前空間化しないと、誤インスタンス送信・資格情報漏洩・cross-account ログ混在。→ `pinnedConnectionFor` 必須通過 + §7.1 名前空間化 + 単体テスト。
- **R-4(共有コード波及)**: `sendGet` correlationId 付与は全 GET 呼出に波及。追加情報のみで後方互換だが、既存テストが例外の等価性を厳密比較していないか確認する。`BrowserLauncher` は既存 `open` を残し追加経路のみとし波及を断つ。
- **R-5(大容量 trace)**: 巨大ログを全文テキストで取得・書き込み・エディタ表示するとメモリ/描画負荷。VSCode も全文取得のため同等。本 PR は単発取得 + 背景書き込みで緩和。極端な場合の上限は未対応(#12 フォローアップ候補)。
- **R-6(スコープ増)**: v2 で安全生成・接続分離・直列化・correlationId 伝播・launcher 結果化を追加したため、v1 想定より実装量が増える。薄い縦切りの範囲を保つため、色描画/ライブポーリング/複数 file_type は引き続き対象外に据え置く。
