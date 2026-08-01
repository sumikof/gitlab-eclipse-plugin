# Job trace 表示 + Artifacts ダウンロード 設計書(Phase 4 PR-3)

- 対象リポジトリ: `sumikof/gitlab-eclipse-plugin`
- ベースブランチ: `develop`(tip `e5a0a9a`)
- パリティ台帳: #7(D14 CI ドメイン)/ ロードマップ: #8 / フェーズ issue: #12
- 参照: VSCode 拡張 `gitlab-workflow` v6.85.3(`./out/gitlab-vscode-extension`、読み取り専用)
- 本設計書はレビュー専用。実装 PR・マージ先には含めない。
- 改訂履歴: v1 初版 → v2〜v7 で Codex #39 R1〜R6 の指摘(temp 安全化・接続名前空間・責務分割・404 非断定・correlationId 伝播・launcher 結果化、および並行機構=mutex/AtomicLong/refcount ライフサイクルの逐次精緻化)を反映 → **v8: 並行モデルを「共有可変状態を UI スレッド専有」に再設計(ユーザー選択)。coordinator の mutex/AtomicLong/refcount/所有権移譲を撤去し、大容量 I/O のみ背景・採番/登録/最新判定/commit/open/通知判定を UI スレッドに集約。R2〜R6 で扱った並行バグクラスを設計から消去。temp 安全化・堅牢 move・correlationId 伝播・launcher 結果化・404 非断定は維持。** → **v8.1: R7 指摘(NotificationUtils.show の内部 asyncExec 二重マーシャルで通知の最新性判定がすり抜ける)を反映。`showOnUiThread` 同期経路を追加し判定+表示を同一 UI ターンに。** → **v8.2: 再設計版レビュー(P1×1+P2×1)反映。スクラッチ所有権を commit runnable へ移譲し背景 finally の早すぎる削除を防止、commit/open の例外を commit runnable 内 try/catch で監査+latest-gated 通知。** → **v8.3: 内部整合(P1×1+P2×1)反映。§14.1 の「所有権移譲は不要」を『refcount 由来の移譲は不要・スクラッチ破棄移譲は必須』に訂正、§21 単体テスト一覧に (d)/(e) を追加。** → **v8.4: 停止/清掃(P2×2)反映。writeScratch の部分書き込み自己清掃(hZA)、job-log 専用の追跡可能 scope を新設し stop で cancel+join→dir 削除・破棄済み Display ガード(hZC)。** → **v8.5: 停止時削除が生む競合(移譲済み commit runnable/非協調 I/O/asyncExec 自体の SWTException=xSX/xSa/xSc)を根絶するため、掃除を起動時(race-free)へ移し専用 scope/停止フックを撤去。asyncExec 予約を try/catch(SWTException)で囲み予約成功時のみ所有権移譲。** → **v8.6: 同一プロセス OSGi 再起動(Vk1K9)対策にセッション別ディレクトリ `job-logs/<sessionId>/`(起動時は他セッションのみ掃除)、notifyIfLatest の予約も try/catch(SWTException) で no-op 化し背景 launch/共有 scope へ例外を漏らさない(Vk1K-)。** → **v8.7: セッション dir に FileLock を持たせ、起動時掃除は tryLock 取得できた(静止した)他セッションのみ削除し稼働中の旧 activation はスキップ(Vk3fr)。** → **v9: trace 格納先を temp ファイルからインメモリ read-only エディタ入力(IStorageEditorInput/IStorage)へ変更(ユーザー選択)。ディスク由来の系統(temp 権限/NOFOLLOW/symlink・堅牢 move・セッション dir・FileLock・起動時掃除・同一プロセス再起動のファイル競合=hZA/hZC/xSX/xSa/Vk1K9/Vk3fr)を撤去。残る同一プロセス再起動の UI 反映(Vk5Pi)は registry.active フラグ(stop で false)で無効化。UI スレッド専有並行モデル・correlationId 伝播・launcher 結果化・404 非断定・notifyIfLatest の SWTException 安全化(Vk1K-/xSc)は維持。** → **v9.1: notifyIfLatest と手順4 catch の通知経路にも `!registry.active` を no-op 条件へ追加(停止済み activation の通知抑止・VluBE)。** → **v9.2: JobLogStorage を IEncodedStorage 化し getCharset()=UTF-8 で文字化け防止(VlwEB)、stop で JobLogEditorInput の開いているエディタを閉じ旧入力/クラスローダを解放(VlwEC)。** → **v9.3: 再読込を確実化するため JobLogEditorInput を可変 JobLogContent 参照にし getStorage() が現在 text を返す形へ(resetDocument で最新反映=VlzIU)、NFR-2b/NFR-3 の旧ディスク保存契約をメモリ保持・背景 GET/整形に更新(VlzIV)。** → **v9.4: §21 に「接続済み provider で実 IDocument が更新される」テストを追加(resetDocument 実効性=VlzIU)、§24 R-5 の「背景書き込みで緩和」をインメモリ保持に修正(VlzIV)。** → **v9.5: openOrReload を全 workbench window/page 走査に拡張し、一致する全エディタを更新(別ページに古い trace を残さない=Vl21v)。** → **v9.6: 更新後、アクティブページに一致が無ければそこで新規オープンして呼び出し元に必ず可視化(他ページのみ一致時の「無反応」を回避=Vl4bf)。** → **v9.7: JobLogContent を JobLogKey ごとに共有(contentRegistry)し、同一 key の全 JobLogEditorInput が同一 content を参照。独立 content で他ページが古 text を保持する事態を防止(Vl6vN)。**

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

1. **ジョブトレース表示**: サイドバー JobNode の context menu「Display Log」から、当該ジョブの trace をテキスト取得 → 制御文字を除去 → **インメモリの read-only エディタ入力**(`IStorageEditorInput`)でエディタに表示。手動更新(同コマンド再実行)対応。ディスク書き込みなし。
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
- 一時ファイルをエディタで開くパターンは既存(MCP 設定 `IDE.openEditorOnFileStore`)。ただし本設計は**ディスクを使わず** `IStorageEditorInput`/`IStorage` でインメモリ表示する(§7.1)ため、この file-store 経路は使わない。
  - 根拠: `src/main/kotlin/com/gitlab/eclipse/mcp/McpConfigEditorOpener.kt`(全体)。
- Console 表示・仮想ドキュメント・temp-file ヘルパは**既存コードに存在しない**(`org.eclipse.ui.console` 依存も未追加)。本設計は Console 依存を追加せず、**インメモリ `IStorageEditorInput`/`IStorage`**(既存 `org.eclipse.ui`/`org.eclipse.ui.editors` 依存で解決)を採用(依存追加ゼロ・ディスク未使用)。
- `GitLabJob.webUrl` は **`String?`(nullable、既定 null)**。API 応答で `web_url` 欠落時に null になり得る。
  - 根拠: `src/main/kotlin/com/gitlab/eclipse/api/model/GitLabJob.kt:11`。

## 5. 要件

### 機能要件

- **FR-1**: JobNode を選択して「Display Log」を実行すると、当該ジョブの trace を取得・整形してエディタに表示する。
- **FR-2**: 同一ジョブに対する「Display Log」再実行は、既存エディタの `IStorage` 内容を最新に差し替え文書をリセットして更新する(§7.2)。これが手動更新の手段。連続実行時は**最新の取得結果のみ**が表示される(§14 参照)。
- **FR-3**: trace を取得できない(404 等)場合、エディタを開かず**非断定的**な通知を出す(「ログが存在しないかアクセスできません」)。
- **FR-4**: JobNode を選択して「Download Artifacts」を実行すると、`job.webUrl` の妥当性(非 null・非空・http(s) scheme・host あり)を検証し、妥当なら `${job.webUrl}/artifacts/download?file_type=archive` を外部ブラウザで開く。妥当でない/起動失敗時はユーザーに通知し監査ログを残す。
- **FR-5**: 両コマンドは JobNode の context menu に常時表示される(PipelineNode/その他ノードには出さない)。

### 非機能要件

- **NFR-1(接続固定)**: trace の GET は、JobNode がロードされた接続(instance URL + auth fingerprint)に固定する。ノードの `sourceInstanceUrl`/`sourceAuthFingerprint` と現行接続が不一致なら GET を行わず通知のみ(既存 `pinnedConnectionFor` を流用)。
- **NFR-2(機密の非漏洩)**: (a) 例外メッセージ・監査ログに token/レスポンスボディを出さない。(b) **trace はディスクに永続化せずプロセスメモリ上のみ**で保持する(`IStorage`・§7.1)。ディスク断片が残る経路を持たない。
- **NFR-3(UI スレッド規律)**: **ネットワーク I/O(GET)と整形のみ**を背景コルーチン(共有 `Dispatchers.IO` scope)で行う(ファイル書き込みは無い)。**エディタ反映(open/reload)・通知のみ** UI スレッド(`asyncExec`)。`CancellationException` は再送、終端 catch。
- **NFR-4(依存追加ゼロ)**: `build.gradle.kts` の依存・icon を追加しない。model 変更もしない。
- **NFR-5(接続分離)**: 別インスタンス/別アカウントの同一 ID ジョブが、同一のエディタ入力/エディタを共有しない(`JobLogEditorInput` の equals=connHash 由来・§7.1)。

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
| `JobLogStorage`(`IStorage`)+ `JobLogEditorInput`(`IStorageEditorInput`) | 新規 | trace 整形テキストを**インメモリ**で保持する read-only エディタ入力(§7.1)。`getContents()` はメモリ上の文字列/バイトを返す。`isReadOnly()=true`。`equals/hashCode` は `JobLogKey`(connHash,projectId,jobId)由来=同一ジョブは同一入力に一致(エディタ再利用)。**ディスク書き込みなし**。 | — |
| `JobLogEditorOpener` | 新規 | `JobLogEditorInput` を開く。**既存エディタがあれば storage 差し替え+文書リセットで明示再読込**、無ければ `IWorkbenchPage.openEditor(input, textEditorId)` で新規オープン(§7.2)。 | UI |
| `JobLogGenerationRegistry` + activation 状態 | 新規 | `Map<JobLogKey, Long>`(key→最新 generation)と単調カウンタ、および `@Volatile active: Boolean` を持つ。**UI スレッドからのみ触れる**(採番・登録・最新判定すべて UI スレッド)。mutex/AtomicLong/refcount は**持たない**。stop で `active=false`(§14/§7.3)。 | UI 専有 |
| `DisplayJobLogHandler` | 新規 | thin SWT `AbstractHandler`。UI で JobNode 解決 → UI で採番・登録(registry)→ 背景コルーチンで pin→getTrace→strip(結果をメモリ保持)→ `asyncExec` で最新判定→エディタへ反映(open/reload)。エラー/404 は `notifyIfLatest`・監査。 | UI→背景→UI |
| `BrowserLauncher.openChecked(url): Boolean`(または結果型) | 変更(追加経路) | 既存 `open(url): Unit` は不変のまま、**成否を返す**経路を追加。artifacts ハンドラが失敗を検知して通知・監査できるようにする。 | UI |
| `DownloadArtifactsHandler` | 新規 | thin SWT `AbstractHandler`。UI で JobNode 解決 → webUrl 検証 → URL 構築 → `openChecked` → 失敗時に通知+構造化監査。ネットワーク I/O なし。 | UI |
| `plugin.xml` | 変更 | command×2 / handler×2 / popup×2(`instanceof JobNode` visibleWhen)。既存 job action ブロックと同形状。 | - |

### 7.1 trace のインメモリ保持(read-only エディタ入力)

trace はディスクに永続化せず、**インメモリの read-only エディタ入力**で表示する。ディスクを使わないため、temp ファイルの権限/`NOFOLLOW`/symlink・堅牢 move・セッションディレクトリ・`FileLock`・起動時掃除・同一プロセス再起動のファイル競合(旧設計 v8 系で扱った hZA/hZC/xSX/xSa/xSc/Vk1K9/Vk1K-/Vk3fr の一群)が**すべて不要**になる。

- **格納**: 整形済み trace テキストを `JobLogStorage`(**`IEncodedStorage` 実装**=`IStorage`+`getCharset()="UTF-8"`、`getContents()` はメモリ上の UTF-8 バイト、`isReadOnly()=true`)に保持し、`JobLogEditorInput`(`IStorageEditorInput`)でエディタに開く。ディスク書き込みは行わない。表示エディタは既定のテキストエディタ(`org.eclipse.ui.DefaultTextEditor` 相当・既存 `org.eclipse.ui.editors` 依存で解決)。
- **エンコーディング(VlwEB)**: `IStorage` のみだと `StorageDocumentProvider` は workspace 既定エンコーディングでバイトを復号し、非 UTF-8 既定環境で日本語等が文字化けする。`IEncodedStorage.getCharset()` で **UTF-8 を明示**し、既定値に依存せず正しく表示する。
- **接続名前空間化(NFR-5)**: `JobLogEditorInput` の `equals/hashCode` を `JobLogKey`=`(connHash, projectId, jobId)`(`connHash` = `normalizeInstanceUrl(instanceUrl)+authFingerprint` の非可逆ハッシュ)で定義。別インスタンス/別アカウントの同一 (projectId, jobId) は**別の入力**=別エディタになり、内容も混ざらない(AC-8)。
- **機密の非永続化(NFR-2b)**: trace はプロセスメモリ上のみに存在し、ディスクに書かない。エディタを閉じれば `IStorage` は GC 対象。プラグイン/JVM 終了でメモリごと消える。ディスク断片が残る経路が無い。
- **大容量**: 巨大 trace はメモリに載る(GET で全文取得する時点と同等・単発)。極端な場合の上限は #12 フォローアップ(§24 R-5)。

### 7.2 既存エディタの明示再読込(FR-2 / P2-61 反映)

`IWorkbenchPage.openEditor(input, id)` は、`equals` 一致する入力に対しては既存エディタを**再利用してフォーカスするだけ**で、内容差し替えを文書へ反映しない。かつ入力の text をコンストラクタで固定すると `getStorage()` が旧 text の storage を返すため、リセットしても旧ログが再表示される(VlzIU)。手動更新(FR-2)を確実に反映するため:

- **可変コンテンツ参照(key ごとに共有)**: `JobLogEditorInput` は**可変の `JobLogContent`**(現在の text を保持)への参照を持ち、`getStorage()` は**その時点の text** から `JobLogStorage` を生成する。`equals/hashCode` は `JobLogKey` のみに依存(text を含めない=同一ジョブは同一入力)。**`JobLogContent` は `JobLogKey` ごとに 1 インスタンスを共有**する(UI スレッド専有の `Map<JobLogKey, JobLogContent>` レジストリで get-or-create)。よって別ページに既存入力があっても、アクティブページに開く新入力と**同一の content を参照**し、`content.text` の 1 回更新で全入力の `getStorage()` が最新を返す(Vl6vN: 独立 content で他ページが古 text を保持する事態を防ぐ)。
- `JobLogEditorOpener.openOrReload(key, text)`:
  - **共有 content を取得/生成**(`contentRegistry[key]`)し `content.text = text` に更新。以降の全エディタは同一 content を参照する。
  - **全 workbench window / page を走査**し(`PlatformUI.getWorkbench().getWorkbenchWindows()` → 各 `getPages()` → `findEditor(input)`)、`JobLogKey` に `equals` 一致する開いているエディタを**すべて**集める(`findEditor` は当該ページ内しか見ないため=Vl21v)。
  - **1 つ以上存在すれば**、共有 `content.text` は更新済みなので、**一致する全エディタ**の文書を **`IDocumentProvider.resetDocument(input)`**(または `AbstractTextEditor.setInput(input)`)で再読込する(全入力が共有 content を参照するため、どのページにも古い trace を残さない=Vl6vN)。read-only 意図で dirty は発生しないが、万一 dirty でも破棄して最新表示。
  - **呼び出し元(アクティブページ)で必ず可視化する(Vl4bf)**: 上記更新の後、
    - アクティブページに一致エディタが**在れば** `activate/reveal` でフォーカス。
    - アクティブページに一致が**無ければ**(他ウィンドウ/非アクティブページにだけ在る、またはどこにも無い場合)、アクティブページで `openEditor(JobLogEditorInput(key, content), textEditorId)`(**共有 content を参照**)を**新規オープン**する。これで現在のページに必ず結果が表示され、「実行したのに無反応」を避ける。他ページのタブは既に最新へ更新済み(重複タブは許容・全て同一 content 参照)。
- 本メソッドは UI スレッドでのみ呼ぶ。呼び出しは §8.1 手順 4 の UI runnable 内で `latest[key] == myGen`(最新)かつ `active`(§7.3)を通過した後にのみ行う。

### 7.3 ライフサイクル(activation ガード・破棄済み Display ガード)

ディスクを使わないため停止時削除・ロック・起動時掃除は無い。残る同一プロセス OSGi 再起動の懸念は「旧 activation の遅延 UI runnable が共有 Display に古い内容を反映する(Vk5Pi)」のみで、これを軽量な**activation ガード**で無効化する:

- **activation フラグ**: `JobLogGenerationRegistry`(activation 単位のインスタンス)が `@Volatile active: Boolean = true` を持つ。`GitLabEclipseStartup.stop` で **`active=false` に落とす**(§18)。commit / notify の UI runnable は冒頭で **`if (!registry.active) return`** を確認し、停止済み activation の runnable は UI に何も反映しない。これで同一 JVM stop→start 後に旧 GET が遅れて完了しても、旧 registry の runnable は no-op(Vk5Pi/VluBE)。`latest[key]` は activation 毎に別インスタンスなので、新 activation の判定を汚さない。
- **停止時に旧 job-log エディタを解放(VlwEC)**: `active=false` は遅延 runnable を止めるだけで、既に開いている `DefaultTextEditor`+旧 `JobLogEditorInput` は閉じない。旧入力が旧 bundle のクラスローダを保持し(re-deploy 間リーク)、trace が停止後も残り、再実行時に別クラスの入力と `equals` 不一致で stale 旧タブが残る。よって `GitLabEclipseStartup.stop` で、**開いている全エディタのうち入力が `JobLogEditorInput` のものを閉じる**(`IWorkbenchPage.closeEditors`)。stop が UI スレッド外で走る場合は `Display.syncExec`(`isDisposed`/`SWTException` ガード)で UI スレッドに委譲。ワークベンチ破棄中(`workbench.isClosing`)は既にエディタごと破棄されるため no-op。
- **破棄済み Display ガード**: `Display.asyncExec` は破棄済み Display で `SWTException(ERROR_DEVICE_DISPOSED)` を投げ得る。**予約呼び出しを `try/catch (SWTException)` で囲み**(`GitLabSidebarView.applyCompose` と同規律)、runnable 冒頭でも `if (display.isDisposed) return` + `SWTException` catch。これにより破棄済み Display への反映・予約が例外を UI ループ/背景 launch へ漏らさない。
- **scope**: 背景 I/O は既存共有 `CoroutineScope(Dispatchers.IO)`(`WorkspaceModule.kt:22`、CI handlers と同じ)で launch。停止時に in-flight GET が残っても request timeout(§12)で自然終了し、UI 反映は activation フラグと isDisposed ガードで無害化。専用 scope は不要。

## 8. 処理フロー

### 8.1 Display Log(trace)

1. ユーザーが JobNode を選択し context menu「Display Log」実行。
2. ハンドラ(UI スレッド): `selectedSidebarNode<JobNode>()` で JobNode 取得。取得不可 → 何もしない。
3. `projectId == null` → 「ログを取得できません」通知して終了(数値 projectId 必須)。
4. **採番・登録(UI スレッド、背景 launch の前)**: `connKey = hash(normalizeInstanceUrl(node.sourceInstanceUrl) + node.sourceAuthFingerprint)`、`key = (connKey, projectId, job.id)`。`JobLogGenerationRegistry` で `myGen = ++counter; latest[key] = myGen`(UI スレッド専有=§14)。以前の同 key 実行を supersede。
5. 背景コルーチン(共有 `CoroutineScope(Dispatchers.IO)`=CI handlers と同じ・§7.3)= **ネットワーク I/O のみ**:
   1. `pinnedConnectionFor(apiClient, node.sourceInstanceUrl, node.sourceAuthFingerprint)` で接続固定。`null`(不一致/Unstable)→ 監査ログ(即時)+ `notifyIfLatest(key, myGen, …)`(§14)で通知して `return@launch`。
   2. `JobTraceService.getTrace(projectId, job.id, connection)`。
   3. 成功: `stripTraceFormatting(raw)` を**メモリ上のローカル変数**として保持(ディスク書き込みなし)。
   4. **反映を UI スレッドへ予約**: 予約呼び出しは `try/catch (SWTException)` で囲む(破棄済み Display で `asyncExec` 自体が投げ得る=§7.3/xSc)。
      ```
      asyncExec {
        try {
          if (display.isDisposed || !registry.active) return@asyncExec   // 破棄/停止済み activation は反映しない(§7.3/Vk5Pi)
          if (latest[key] != myGen) return@asyncExec                     // supersede 済み→何もしない
          JobLogEditorOpener.openOrReload(key, text)                     // 既存=content 更新+resetDocument / 無=新規 open(§7.2)
        } catch (e: SWTException) { /* Display 破棄済み: no-op */
        } catch (e: Exception) { writeAudit(...); if (latest[key]==myGen && registry.active) NotificationUtils.showOnUiThread(generic) }
      }
      ```
      判定(latest/active/isDisposed)・open/reload・失敗処理がすべて同一 UI runnable 内で直列=不可分(mutex 不要=§14.2)。**メモリ保持のため所有権移譲・スクラッチ破棄・finally は不要**。
   6. `GitLabApiException`:
      - statusCode == 404 → 監査ログ(status/correlationId、token/body 非出力)を即時に残し、`notifyIfLatest` で「ログが存在しないかアクセスできません」**非断定**通知(supersede/停止済みなら UI runnable 冒頭判定で抑止=P2-SGk/U32/Vk5Pi)。
      - それ以外 → 監査ログを即時に残し、`notifyIfLatest` で generic 通知。
   7. `HttpTimeoutException`/`IOException`(GET)→ 監査ログを即時に残し、`notifyIfLatest` で generic 通知。
   8. `CancellationException` → rethrow(通知しない=意図的キャンセル)。終端 `catch (Exception)` は log + 監査(即時)+ `notifyIfLatest`。**ディスク資源を持たないため finally での破棄処理は不要**。`latest[key]` は UI スレッド上で自然に上書き、activation 状態は §7.3 の停止フラグで管理(refcount/mutex/scratch/ロックは無い)。

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

// JobLogContent(新規): 可変コンテンツ参照。JobLogKey ごとに 1 インスタンス共有(Vl6vN)
class JobLogContent(@Volatile var text: String)
// contentRegistry: Map<JobLogKey, JobLogContent>(UI スレッド専有・get-or-create)
// JobLogStorage(新規): インメモリ read-only IEncodedStorage(charset 明示=VlwEB)
class JobLogStorage(content: JobLogContent) : IEncodedStorage {  // IStorage + getCharset
  override fun getContents() = content.text.toByteArray(Charsets.UTF_8).inputStream()  // その時点の text
  override fun getCharset() = "UTF-8"                            // StorageDocumentProvider の復号を UTF-8 に固定
  override fun isReadOnly() = true
  // getName()=表示名(例 "job-<jobId>.log"), getFullPath()=null 可
}
// JobLogEditorInput(新規): IStorageEditorInput。equals/hashCode は JobLogKey のみ(text 非依存)
class JobLogEditorInput(val key: JobLogKey, val content: JobLogContent) : IStorageEditorInput {
  override fun getStorage() = JobLogStorage(content)            // content.text の現在値を反映
  override fun equals(o) = o is JobLogEditorInput && o.key == key
  override fun hashCode() = key.hashCode()
}
// JobLogEditorOpener(新規): 共有 content.text 更新→全ページ一致 reset→アクティブpage可視化(§7.2)
fun openOrReload(key: JobLogKey, text: String)  // 全 JobLogEditorInput は contentRegistry[key] を共有参照

// BrowserLauncher(追加経路)
fun openChecked(url: String): Boolean   // 既存 open(url): Unit は不変

// NotificationUtils(追加経路): 既に UI スレッド上で再マーシャルせず同期表示
fun showOnUiThread(message: String)     // 既存 show(message): 内部 asyncExec は不変
// notifyIfLatest(§14): try { asyncExec { if (display.isDisposed || !registry.active) return; if (latest[key]==myGen) NotificationUtils.showOnUiThread(message) } } catch (SWTException) { /* no-op */ }
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
| 本実行が最新でない(supersede 済)/停止済み activation | UI runnable 冒頭で `latest[key] != myGen` または `!registry.active` | open/通知せず静かに終了(通知不要)。 |
| Display 破棄済み | `asyncExec` 予約時 `SWTException` / runnable 冒頭 `isDisposed` | no-op(例外を漏らさない・§7.3)。 |
| webUrl が null/空/不正 scheme・host | ハンドラ検証 | generic 通知 + 監査ログ。ブラウザ起動せず。 |
| artifacts ブラウザ起動失敗 | `openChecked`→false/例外 | generic 通知 + 構造化監査ログ。 |

- 監査ログ・例外メッセージに token / レスポンスボディを出さない(NFR-2a)。
- **supersede 済み/停止済み activation の失敗はユーザー通知を抑止**し監査ログのみ残す: **全失敗経路(pin 不一致・404・その他・timeout・IO・終端 catch)を共通の `notifyIfLatest` に通し、最新性判定は通知を出す UI runnable の冒頭で `latest[key] == myGen` かつ `registry.active`(UI スレッド専有・§14/§7.3)により行う**。監査は背景側で即時・常時。

## 12. タイムアウトとリトライ

- trace GET は既存 `sendGet` の既定 `REQUEST_TIMEOUT_SECONDS` を用いる(単発リクエスト)。リトライしない(VSCode も trace 単発にリトライなし)。
- ライブポーリングは本 PR 対象外(手動更新のみ)。

## 13. 冪等性

- trace GET・artifacts ブラウザ起動はいずれも**べき等な READ**。サーバ状態を変更しない。二重実行はエディタ再表示/ブラウザ再オープンに留まり、サーバ副作用なし。よって書き込みガード(`InFlightWriteGuard`)は使用しないが、**表示の一貫性**のため §14 の UI スレッド専有 generation 判定(最新のみ反映)を行う。

## 14. 並行処理(UI スレッド専有モデル)

「Display Log」は**手動・低頻度のユーザー操作**である。ライブポーリング(対象外)を持たない本 PR では、専用の並行機構を組むより **共有可変状態へのアクセスを UI スレッドに閉じ込める**方が、検証不能な並行バグの温床を根絶でき、Eclipse 慣用にも合致する。

### 14.1 原則

- **ネットワーク I/O(GET)と整形だけを背景スレッド**で行い、結果は**メモリ上のローカル変数**として保持する(ディスク書き込みなし=§7.1)。
- **共有可変状態(`JobLogGenerationRegistry` の `latest[key]`・採番カウンタ・`active` フラグ)は UI スレッドからのみ**読み書きする。UI スレッドは単一で全 runnable を直列実行するため、採番・登録・最新判定・active 判定・エディタ open/reload・通知の最新性判定が**自然に不可分・直列化**される。→ 旧設計の **per-key mutex・AtomicLong・refcount・所有権移譲・スクラッチ破棄責務は不要**(ディスク資源が無い)。

### 14.2 プロトコル

1. **採番と登録(UI スレッド、背景 launch の前)**: `myGen = ++counter; latest[key] = myGen`。UI スレッド専有なので `counter` は単調、`latest[key]` の巻き戻しは起こり得ない(小 gen が後で大 gen を上書きする経路が構造的に無い)。
2. **背景(`Dispatchers.IO`)**: `pinnedConnectionFor` → `getTrace` → `stripTraceFormatting` の結果を**メモリ保持**(ディスク書き込みなし)。
3. **反映(UI スレッド、単一 `asyncExec`、成功時のみ予約)**: 予約呼び出しは `try/catch (SWTException)` で囲む(§7.3/xSc)。runnable 内はすべて UI スレッドで直列:
   - `if (display.isDisposed || !registry.active) return` — 破棄/停止済み activation は反映しない(§7.3/Vk5Pi)。
   - `if (latest[key] != myGen) return` — supersede 済みなら何もしない。
   - 最新なら `JobLogEditorOpener.openOrReload(key, text)`=既存エディタは content 更新+resetDocument、無ければ新規 open(§7.2)。
   - **open/reload が UI 内で失敗しても背景 catch では捕捉できない**ため、runnable 内 `try/catch`(`SWTException` は no-op、他例外は監査 + latest-gated 通知)で受ける(P2-b5e 相当)。
   判定・open/reload・失敗処理が同一 UI runnable 内で連続実行され割り込みが入らない(mutex 不要)。**ディスク資源が無いのでスクラッチ破棄・所有権移譲・finally は不要**。
4. **失敗時の通知(UI スレッド・同一ターンで判定+表示)**: 全失敗経路(pin 不一致 / 404 / その他 GitLabApiException / timeout / IO / 書込失敗 / 終端 catch)は共通の **`notifyIfLatest(key, myGen, message)`** を使う。
   - **注意**: 既存 `NotificationUtils.show` は本体を **さらに `currentDisplay.asyncExec` で再マーシャル**する(`NotificationUtils.kt:11-26`)。よって `asyncExec { if (latest==myGen) NotificationUtils.show(msg) }` は、判定(turn N)と実際の popup 表示(turn N+1)が**別 UI ターン**になり、その間に後発が `latest` を更新すると stale 通知が出る(R7 指摘)。
   - **対策**: 既に UI スレッド上にいる前提で **再マーシャルせず popup を同期的に開く経路**を用意する(`NotificationUtils.showOnUiThread(message)` を追加。既存 `show` は不変)。`notifyIfLatest` は次のとおり、**予約(`asyncExec`)呼び出し自体を `try/catch (SWTException)` で囲み**(破棄済み Display で `asyncExec` が投げても no-op)、runnable 内で判定と表示を同一 UI ターンで行う:
     ```
     fun notifyIfLatest(key, myGen, message) {
       try {
         display.asyncExec {
           if (display.isDisposed || !registry.active) return@asyncExec   // 破棄/停止済み activation は通知しない(Vk5Pi/VluBE)
           if (latest[key] == myGen) NotificationUtils.showOnUiThread(message)
         }
       } catch (e: SWTException) { /* Display 破棄済み: 通知 no-op */ }
     }
     ```
     これで **notifyIfLatest は決して例外を投げない**(Vk1K- 反映: 背景 `launch` から SWTException が漏れて plain Job の共有 scope が他機能ごと cancel されるのを防ぐ)。判定〜表示間に後発が割り込む余地も無い。
   - **監査ログは背景側で即時・無条件**に残す(通知抑止と独立)。通知の no-op 条件は `display.isDisposed || !registry.active || latest[key]!=myGen`(停止済み activation でも通知しない=VluBE)。
5. **キャンセル**: `CancellationException` は rethrow(通知しない)。ディスク資源が無いため破棄処理は不要。

### 14.3 性質

- 同一 (接続, project, job) の連続/並行実行では、**最新の採番(=最後にユーザーが起動した実行)のみ**がエディタ・通知に反映される。反映も通知も UI スレッドで `latest[key]`(+`active`)を見て判定するため、遅延完了した先発・停止済み activation は反映も通知もしない(AC-2/P2-SGk/Vk5Pi 充足)。
- `latest[key]` は UI スレッド専有の小さなマップ(値は `Long` 1 個/閲覧した (接続,job))。**refcount を持たない**ため解放漏れの概念が無い。多数 job 閲覧でも Long が増えるだけで、activation 破棄で GC。ABA は単調カウンタ(再利用しない)で起きない。
- trace 内容はメモリ上の `IStorage` に保持し、エディタを閉じれば GC。ディスクに書かないため権限/symlink/cleanup/同一プロセス再起動のファイル競合が構造的に無い。
- 別 key(別 job/別接続)は独立(相互ブロックしない)。
- 接続 snapshot 固定により、実行中の設定変更でも誤インスタンス送信・資格情報漏洩は起きない(seqlock `captureConnection` 由来)。

### 14.4 テスト

UI スレッド直列実行を模したドライバで: (a) 応答順を逆転(先発の背景完了を後発より遅らせる)させ、エディタ内容が**後発(最新)**になり先発 runnable が反映しないこと、(b) supersede 済み実行の失敗が `notifyIfLatest` の UI 冒頭判定で通知を出さず監査のみ残すこと、(c) `latest[key]` が UI スレッドからのみ更新され単調で巻き戻らないこと、(d) **`active=false`(停止済み activation)の runnable が open/reload も通知もしないこと**(`notifyIfLatest` と手順4 catch の両経路・Vk5Pi/VluBE)、(e) **open/reload が `SWTException` を投げても no-op、他例外は監査+latest-gated 通知され UI ループへ漏れないこと**、を検証。

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
- trace はメモリ保持のみ(ディスク書き込みなし)。エディタを閉じる/プラグイン停止で消え、再実行で再取得可能。ディスク残留・cleanup 競合が無い。
- 破棄済み Display への UI 反映・予約は `SWTException`/`isDisposed` ガード、停止済み activation は `active` フラグで無害化(§7.3)。

## 18. 既存機能への影響

- `GitLabApiClient` に公開メソッド 1 つ追加(`fetchText`)。加えて `sendGet` の非 2xx 例外に correlationId を付与する変更 → **全 GET 呼出(IssueService/MergeRequestService/PipelineService/JobService 等)の失敗例外に correlationId が載る**。`GitLabApiException.correlationId` は既定 null の追加フィールドで後方互換(値が入るだけ)。既存テストで body/status を検査しているものへの影響有無を確認する。
- `BrowserLauncher` に成否を返す経路(`openChecked` 等)を**追加**。既存 `open(url): Unit` は不変で、他呼出(chat webview / ShowDocumentation / preferences / sidebar double-click)に影響なし。
- `NotificationUtils` に同期表示経路 `showOnUiThread` を**追加**(既に UI スレッド上で再マーシャルしない)。既存 `show(message)`(内部 `asyncExec`)は不変で他呼出に影響なし。`notifyIfLatest` の「判定と表示を同一 UI ターンで」を成立させるために使う(§14)。
- `GitLabEclipseStartup.stop`(`GitLabEclipseStartup.kt:82-87`)に **(1) job-log registry の `active=false`(Vk5Pi/VluBE)、(2) 入力が `JobLogEditorInput` の開いているエディタを閉じる(VlwEC・UI スレッドで、ワークベンチ破棄中は no-op)** を追加。既存の停止処理(LSP/CodeSuggestions/OAuth/HttpClient)には手を触れない。`start` は変更しない(起動時掃除は無い=ディスクを使わない)。共有 `CoroutineScope` singleton(`WorkspaceModule.kt:22`)も変更しない(job-log は既存共有 scope を利用)。
- `IStorageEditorInput`/`IStorage`/`IWorkbenchPage.openEditor` は既存 `org.eclipse.ui`/`org.eclipse.ui.editors`/`org.eclipse.ui.workbench.texteditor` 依存で解決(§18 の build 依存追加なし)。
- JobNode/PipelineNode/PropertyTester/既存 job action(retry/cancel/play)には変更なし。
- plugin.xml は command/handler/popup を**追加**のみ(既存エントリ不変)。
- build 依存・model・ディレクトリ構成の変更なし(ディスク未使用のため temp/state ディレクトリも使わない)。

## 19. 移行方法

- 新規機能追加のみ。データ移行なし。マージで即有効(新 context menu 項目が JobNode に出現)。

## 20. ロールバック方法

- 実装 PR を revert すれば完全に元へ戻る(追加ファイル削除 + `GitLabApiClient`/`BrowserLauncher`/plugin.xml の追加分除去)。既存機能に破壊的変更がないため副作用なし(correlationId 付与は追加情報のみ)。

## 21. テスト方針

- **単体(headless で検証可)**:
  - `stripTraceFormatting`: ANSI SGR/CSI 除去、`section_start/end` 除去、`\r` overwrite、改行正規化、空入力/非制御入力の恒等性を TDD。
  - `JobTraceService.getTrace`: モック `GitLabApiClient` で正しいパス `/projects/{id}/jobs/{jobId}/trace` と connection 引き回しを検証。
  - `GitLabApiClient.fetchText` / `sendGet`: モック http client で connection pin(instanceUrl/Bearer)、非 2xx→例外、**correlationId 伝播**、token/body 非出力を検証。
  - `JobLogEditorInput`/`JobLogStorage`/`JobLogContent`: **接続名前空間化**(別 connHash の入力が `equals` で不一致=別エディタ)、同一 `JobLogKey` の入力が `equals`/`hashCode` 一致(エディタ再利用)、`getContents()` が **`JobLogContent.text` の現在値**を返し `isReadOnly()=true`、**`getCharset()=="UTF-8"`**(非 UTF-8 既定環境でも文字化けしないこと=VlwEB)を検証。
  - **再読込で実文書が更新される(VlzIU/AC-2)**: `StorageDocumentProvider` を実際に入力へ接続して `IDocument` を得た状態で `openOrReload(key, 新text)` を呼び、**接続済み `IDocument.get()` が新 text になる**こと(storage の値だけでなく resetDocument/setInput が実際に文書へ反映されること)を検証。誤った入力へ resetDocument する/呼ばない実装を落とす。
  - **複数ページ更新+可視化+共有 content(Vl21v/Vl4bf/Vl6vN)**: 複数 window/page に同一 `JobLogKey` のエディタが開いている状況をモックし、`openOrReload` が**全ページの一致エディタ**を更新する(いずれのページにも古い trace が残らない)こと、**アクティブページに一致が無い場合はアクティブページで新規オープン**して可視化されること、および**同一 key の全 `JobLogEditorInput` が共有 `JobLogContent` を参照**し、他ページ入力を後日 provider 再接続しても最新 text を返すこと(独立 content で古 text に戻らない)を検証。
  - **停止時エディタ解放(VlwEC)**: stop で入力が `JobLogEditorInput` の開いているエディタが閉じられ(モック `IWorkbenchPage`)、他エディタは閉じないこと。ワークベンチ破棄中は no-op であること。
  - `JobLogGenerationRegistry` + 反映/通知 runnable(UI スレッド直列実行を模したドライバで、§14.4 と対応): (a) **応答順逆転**で先発の背景完了を後発より遅らせても、エディタ内容が**後発(最新)**になり先発 runnable が反映しないこと、(b) supersede 済み実行の失敗が `notifyIfLatest` の UI 冒頭判定(`latest[key]==myGen`)で通知を出さず監査のみ残すこと(pin 失敗・404・timeout・IO・終端 catch の全経路)、(c) `latest[key]`・カウンタが UI スレッドからのみ更新され単調で巻き戻らないこと、(d) **`active=false`(停止済み activation)の runnable が open/reload も通知もしないこと**(`notifyIfLatest` と手順4 catch の両経路・Vk5Pi/VluBE)、(e) **open/reload が `SWTException` を投げても no-op、他例外は監査+latest-gated 通知され UI ループへ漏れないこと**、を検証。
  - **通知の SWTException 安全性**: `notifyIfLatest` が予約(`asyncExec`)失敗(破棄済み Display)でも例外を投げず no-op になり、背景 `launch`/共有 scope へ SWTException を漏らさないこと(Vk1K-/xSc)。
  - artifacts URL 構築 + webUrl 検証(null/空/不正 scheme・host→失敗、正常→`.../artifacts/download?file_type=archive`)を純ロジックとして検証。
- **手動(実機・PR 説明文にチェックリスト)**: Display Log 表示/整形/404 非断定通知/手動更新/連続実行で最新反映、Download Artifacts のブラウザ起動と webUrl 不正時通知、接続変更時 pin 挙動、別アカウント同一 ID ジョブでの非混在、権限 403 時の通知。
- ベースライン: 既存 36 失敗(SWT-env)は不変。検証=対象テスト PASS + ベースライン外の新規失敗ゼロ + 変更ファイル detekt 0。

## 22. 受け入れ条件

- **AC-1**: JobNode の「Display Log」で trace が整形表示される(ANSI/section マーカー/`\r` が除去され可読)。
- **AC-2**: 「Display Log」を連続実行しても、表示は**最新の取得結果**になる(応答順逆転でも可視ファイル/エディタが古い内容へ巻き戻らない=UI スレッド専有の最新判定・§14)。既にエディタが開いている場合、再実行で**文書が明示再読込され最新内容が表示**される(単なるフォーカスに留まらない)。
- **AC-3**: trace 404 でエディタを開かず**非断定**通知(「存在しないかアクセスできません」)が出て、監査ログに status/correlationId が残る。
- **AC-4**: JobNode の「Download Artifacts」で、webUrl が妥当なとき `${webUrl}/artifacts/download?file_type=archive` が外部ブラウザで開く。webUrl 不正/起動失敗時は通知 + 監査ログが出る。
- **AC-5**: 両コマンドが JobNode の context menu にのみ表示される(他ノードに出ない)。
- **AC-6**: instance/auth 不一致時に trace GET を行わず通知のみ(誤送信・資格情報漏洩なし)。
- **AC-7**: 例外メッセージ・監査ログに token/レスポンスボディが出ない。GET 障害の監査に correlationId が載る。
- **AC-8**: trace はメモリ保持(ディスク書き込みなし)。エディタ入力は接続名前空間つき(`JobLogKey`)で、別インスタンス/別アカウントの同一 ID ジョブは別エディタ入力になり内容が混ざらない。停止済み activation の遅延 runnable は反映しない(Vk5Pi)。
- **AC-9**: build 依存・model・既存機能の破壊的変更がない(detekt 0、ベースライン外新規失敗 0)。

## 23. 未決事項

- **U1(解決)**: trace の `Accept` ヘッダ。既存 `sendGet` は `Accept: application/json` を固定送信するが、GitLab `/trace` は Accept を無視して text を返すため実害なし。専用 `text/plain` 経路は設けず既存 sendGet を流用する(過剰実装回避)。実機手動検証で text が正しく取得できることを確認。
- **U2(解決)**: trace はディスクに永続化せずインメモリ `IStorage` で保持(§7.1)。temp ファイルの権限/symlink/cleanup 論点は設計から消滅。
- **U3(解決)**: 監査ログの READ 版様式は既存 `writeAuditMessage` を READ 用に一般化する(token/body 非出力は不変)。
- **U4**: `stripTraceFormatting` の `\r` overwrite の厳密仕様。GitLab 進捗行は `\r` で行頭に戻り上書きする。行内 `\r` は「最後の `\r` 以降を採用」で近似する(厳密なターミナルエミュレーションは対象外・近似で受容)。テストで近似仕様を固定する。

## 24. 想定されるリスク

- **R-1(検証不能領域)**: エディタ起動・UI スレッドマーシャル・context menu 配線・並行直列化は headless で検出しにくく、実機でのみ露見しうる。→ 実装は fable、並行/接続分離は単体テストで可能な限り固定し、UI 部は手動検証手順を PR に明記。
- **R-2(plugin.xml 配線ミス)**: command id / handler FQN / visibleWhen の不一致で menu が黙って消える/誤発火。→ 既存 job action ブロックの形状を厳密に踏襲し、id 一致・FQN 実在・well-formed(jshell)を検証。
- **R-3(接続 pin/分離の抜け)**: READ 経路で pin を通さない、またはエディタ入力を名前空間化しないと、誤インスタンス送信・資格情報漏洩・cross-account ログ混在。→ `pinnedConnectionFor` 必須通過 + `JobLogEditorInput` の connHash 名前空間化 + 単体テスト。
- **R-4(共有コード波及)**: `sendGet` correlationId 付与は全 GET 呼出に波及。追加情報のみで後方互換だが、既存テストが例外の等価性を厳密比較していないか確認する。`BrowserLauncher` は既存 `open` を残し追加経路のみとし波及を断つ。
- **R-5(大容量 trace)**: 巨大ログを全文テキストで取得・メモリ保持・エディタ表示するとメモリ/描画負荷。VSCode も全文取得のため同等。本 PR は**単発取得+インメモリ保持**(ディスク書き込みなし=§7.1)で、GET 時点と同等のメモリ量に留める。極端な場合の上限(切り詰め等)は未対応(#12 フォローアップ候補)。
- **R-6(スコープ増)**: v2 で安全生成・接続分離・直列化・correlationId 伝播・launcher 結果化を追加したため、v1 想定より実装量が増える。薄い縦切りの範囲を保つため、色描画/ライブポーリング/複数 file_type は引き続き対象外に据え置く。
