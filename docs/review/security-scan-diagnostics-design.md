# リモートセキュリティスキャン + 診断マッピング層 設計書

対象フェーズ: Phase 5B(ロードマップ #8 / パリティ台帳 #7 の D9 / フェーズ issue #13)
ベース: `develop` @ `335fabc`
版: 初版(Codex 設計レビュー用)

---

## 1. 背景と目的

### 1.1 背景

本プラグインは公式 GitLab Eclipse プラグインを拡張し、VSCode 拡張 `gitlab-workflow` v6.85.3 との機能パリティを目指している。台帳 #7 の D9「Remote Security Scans」は 5 項目中 0 件が実装済みで、台帳上は「障壁: `publishDiagnostics` が no-op。IMarker/IResource マッピング層の新設が前提」と記録されていた。

本設計にあたり、**VSCode 拡張の実ソース**と、**本リポジトリが pin している gitlab-lsp の実バイナリ**(`package.json` の `"@gitlab-org/gitlab-lsp": "8.80.0"`、`build/gitlab-lsp/bin/gitlab-lsp-linux-arm64`)の双方を読み、以下を確定した。

1. **台帳の「障壁」記述は正しい。** LS の `DefaultSecurityDiagnosticsPublisher` は SAST スキャン結果を **標準の LSP `textDocument/publishDiagnostics`** として送出する。VSCode 拡張は診断コレクションを一切自前で持たず、`vscode-languageclient` の既定処理によって Problems パネルに表示されている。本プラグインは lsp4j を直接使う自前クライアントであり、`publishDiagnostics` が log-only の no-op であるため、**この一点だけが欠落している**。
2. **台帳の `gl.viewSecurityFinding` の備考は誤り。** このコマンドは Merge Request のパイプライン検出結果を GraphQL(`pipelineFinding` クエリ)で取得して独自 webview に表示する**別機能**であり、リモートスキャンにも `publishDiagnostics` にも依存しない。リモートスキャン側の詳細表示コマンドは `gl.webview.securityVulnDetails` である。**本設計の受け入れ時に台帳を訂正する。**

### 1.2 目的

Eclipse 上で GitLab のリアルタイム SAST スキャンを実行し、その検出結果を **Problems ビュー**に表示できるようにする。あわせて、将来 LS が別種の診断を送出したときにも機能するよう、診断 → marker の写像を**セキュリティ非依存の汎用層**として新設する。

---

## 2. 対象範囲

| # | 項目 | 台帳 #7 D9 の行 |
|---|---|---|
| S1 | `publishDiagnostics` → `IMarker`/`IResource` マッピング層の新設 | 「障壁: publishDiagnostics が no-op」 |
| S2 | セキュリティスキャンの実行(明示コマンド + アクティブエディタ保存時) | 「セキュリティスキャン実行」 |
| S3 | `remoteSecurityScans` フラグと `securityScannerOptions` の設定配線 | 「flag: remoteSecurityScans」 |
| S4 | 検出結果の表示(= S1 の帰結として Problems ビューに出る) | 「セキュリティ検出結果を表示」 |
| S5 | スキャン応答(状態・エラー)のユーザー提示 | (VSCode ではツリービューが担う部分) |

## 3. 対象外

| # | 項目 | 理由 |
|---|---|---|
| N1 | `gl.viewSecurityFinding`(MR パイプライン検出結果の GraphQL 経路) | §1.1 のとおり別機能。MR ドメイン + GraphQL + 独自 webview であり、本設計に混ぜると Phase 5A で分離した「毛色の違う経路を同じ設計に入れるとレビュー指摘がそこに集中する」問題を再現する。**別サイクルで設計する。** |
| N2 | 脆弱性詳細 webview(`gl.webview.securityVulnDetails` / LS webview id `security-vuln-details`) | Eclipse 側の webview ホストは `ChatWebviewCatalog` の 2 件固定 allowlist であり、かつ editor 領域に webview を開く仕組みが無い。基盤拡張が本体より大きくなる。**別サイクル。** |
| N3 | VSCode のツリービュー `remoteSecurityScanning` 相当 | 検出結果は Problems ビューに出るため二重表示になる。状態・エラーは §11 の通知で代替する。 |
| N4 | `gl.runSecurityScanViewAction` | 上記ツリービューのタイトルバー用コマンドであり、ツリービューが無ければ存在意義が無い。 |
| N5 | 検出結果の抑止・却下(dismiss)、重大度しきい値によるフィルタ | VSCode 拡張にも存在しない。 |
| N6 | スキャンのデバウンス / キャンセル | VSCode 拡張にも存在しない(パリティ)。 |
| N7 | GitLab インスタンスのバージョン検出 | LS が 404 応答に対する文言を持つ。クライアント側での事前判定は行わない。 |

---

## 4. 現在の課題

| # | 課題 | 根拠(実ソース) |
|---|---|---|
| C1 | `publishDiagnostics` が log-only の no-op | `src/main/kotlin/com/gitlab/eclipse/lsp/GitLabLanguageServerClient.kt:151-153` |
| C2 | `remoteSecurityScans` が `false` にハードコードされている | `src/main/kotlin/com/gitlab/eclipse/lsp/configuration/GitLabLanguageServerConfigurationService.kt:45` |
| C3 | `securityScannerOptions` フィールドが設定パラメータに存在しない | `src/main/kotlin/com/gitlab/eclipse/lsp/configuration/GitLabLanguageServerConfigurationParams.kt`(全フィールドを確認) |
| C4 | スキャン起動通知を送るメソッドが無い | `src/main/kotlin/com/gitlab/eclipse/lsp/GitLabLanguageServer.kt:37-90` |
| C5 | スキャン応答通知のハンドラが無い | `GitLabLanguageServerClient.kt` 全体 |
| C6 | `IMarker` を作る実装がリポジトリ内に一切存在しない | `IMarker` / `createMarker` / `deleteMarkers` の全文検索が 0 件 |

補足: C1 の現行実装 `logger.info("publishDiagnostics: $diagnostic")` は、診断本文(= ユーザーのソースに関する脆弱性の説明)とファイル URI を Eclipse の Error Log に丸ごと出力する。これは §14 の監査規律に反するため、実装時に削除する。

---

## 5. 要件

### 5.1 機能要件

| # | 要件 |
|---|---|
| F1 | ユーザーはコマンド「Run Remote Scan (SAST)」でアクティブエディタのファイルをスキャンできる |
| F2 | 設定が有効なとき、アクティブエディタの保存でスキャンが自動実行される |
| F3 | スキャン結果の脆弱性は Problems ビューに問題として表示される |
| F4 | 表示された問題をダブルクリックすると該当ファイルの該当行が開く(`problemmarker` + `LINE_NUMBER` により Eclipse 標準機能で成立) |
| F5 | 同一ファイルを再スキャンすると、前回の検出結果は置き換わる(累積しない) |
| F6 | スキャンが失敗したとき、ユーザーは失敗した事実と理由を知ることができる |
| F7 | スキャン機能は既定で無効であり、有効化は明示的な設定操作を要する |
| F8 | LS が停止・再起動したとき、その LS が出した検出結果は Problems ビューから消える |

### 5.2 非機能要件

| # | 要件 |
|---|---|
| NF1 | 診断の受信処理は lsp4j の JSON-RPC 読み取りループをブロックしない |
| NF2 | 診断の適用処理は UI スレッドを使用しない |
| NF3 | 設定を変更しない限り、本変更前と観測可能な振る舞いが一致する |
| NF4 | Error Log にトークン・レスポンス本文・診断本文・ユーザーのソースコードを出力しない |
| NF5 | 新規の OSGi バンドル依存を追加しない |
| NF6 | 既存のディレクトリ構成・ビルドシステムを変更しない |

---

## 6. 前提条件と制約

### 6.1 前提条件(実ソースで確定済み)

**P1. LSP メソッド定数**(出典: pin 版 LS バイナリ 8.80.0 の逆読み)

```
$/gitlab/security/remoteSecurityScan            client -> server  NotificationType
$/gitlab/security/remoteSecurityScan/response   server -> client  NotificationType
```

バイナリ内の該当箇所(整形済み):

```js
cis = "$/gitlab/security/remoteSecurityScan",
m7c = `${cis}/response`,
lis = new NotificationType(cis),
uis = new NotificationType(m7c)
```

**P2. LS 側スキャン処理の全フロー**(`DefaultSecurityDiagnosticsPublisher`)

1. `trackEvent("scan_initiated", { source })`
2. LS 内のドキュメントストアから `documentUri` のドキュメントを取得。**無ければ黙って return**
3. `workspaceFolders` から uri が前方一致するものを探索。無ければ `"Real-time SAST scan failed. No valid workspace detected."`
4. `checkProjectStatus` → `project.namespaceWithPath` が無ければ `"Real-time SAST scan failed. No valid project detected."`
5. 発行器が未初期化なら `"Real-time SAST scan failed. Reload your IDE, and try again"`
6. **ゲート**: `isClientFlagEnabled("remoteSecurityScans")` が偽、または `securityScannerOptions?.enabled` が偽なら**黙って return**
7. `POST /api/v4/projects/{encodeURIComponent(namespaceWithPath)}/security_scans/sast/scan`、body `{ file_path, content }`、`supportedSinceInstanceVersion = { resourceName: "SAST security scan", version: "17.5.0" }`
8. 応答の `vulnerabilities` が null または非配列なら return
9. 各要素を Diagnostic へ写像し **`publishDiagnostics({ uri, diagnostics })`**
10. `sendScanResponse({ filePath, status: 200, results: vulnerabilities, timestamp })`

失敗時: `status` はエラーの `status` か既定 **500**、`error` は下表の文言(または例外の `message`)を載せて `sendScanResponse` する。

| status | 文言 |
|---|---|
| 401 | `Real-time SAST scan authentication failed. Your GitLab authentication token is invalid or has expired. Reauthenticate with your GitLab account or generate a new personal access token.` |
| 403 | `Real-time SAST scan is not available for this project or [namespace](https://docs.gitlab.com/user/namespace/).` |
| 404 | `Real-time SAST scan is not available on your [GitLab instance version](https://docs.gitlab.com/api/projects/#real-time-security-scan).` |
| 500(既定) | `Real-time SAST scan failed with an unknown error. Check your network connection, reload your IDE, and try again ` |

**文言に Markdown リンクが埋め込まれている**点に注意(§11.3)。

**P3. LS 側の Diagnostic 写像**

```js
message  = `${e.name}` + 改行 + `${e.description}`
severity = e.severity === "high" ? DiagnosticSeverity.Error : DiagnosticSeverity.Warning
range    = { start: { line: location.start_line - 1, character: location.start_column - 1 },
             end:   { line: location.end_line   - 1, character: location.end_column   - 1 } }
source   = "gitlab_security_scan"
```

- `severity` の比較は**小文字 `"high"`**。LS が生成する severity は **Error / Warning の 2 値のみ**。
- `character` は `start_column - 1` をそのまま出すため、API が 0 始まりの桁を返すと**負値になりうる**。
- 改行の個数(`\n` か `\n\n` か)は抽出手法の制約で確定していない。**本設計は改行を空白へ畳むため、個数に依存しない。**

**P4. フラグの適用範囲**(バイナリ全文検索で確認)

`isClientFlagEnabled("remoteSecurityScans")` の出現は **1 箇所のみ**(他は `duoWorkflow` 2 件、`editFileDiagnosticsResponse` 1 件、`streamCodeGenerations` 1 件)。`securityScannerOptions` の参照も同発行器の 1 箇所のみ。したがって**ユーザー設定 1 つで両者を駆動しても、他機能への副作用は無い**。

**P5. 依存バンドル**

`org.eclipse.core.resources`(`build.gradle.kts:163`)、`org.eclipse.ui.ide`(:178)、`org.eclipse.ui.workbench.texteditor`(:181)は既に `Require-Bundle` にある。**新規依存は不要。**

**P6. LS へ送出する URI の形**

`didOpen` / `didClose` / `didChangeDocumentInActiveEditor` / `workspaceFolders` はいずれも `IResource.getLocationURI().toASCIIString()` を用いる(`GitLabLanguageServerOpenFilesService.kt:66,89,145`、`ProjectsWorkspaceFolder.kt`)。EFS が生成する `java.net.URI` は authority を持たないため、**`file:/abs/path`(スラッシュ 1 本)** の形になる。リポジトリ内のテストフィクスチャも `file:/project/test.kt` を用いている(`DidChangeWatchedFileCapabilityTest.kt:26 ほか`)。

### 6.2 制約

| # | 制約 |
|---|---|
| R1 | 既存ディレクトリ構成を変更しない(既存パッケージ配下への新規サブパッケージ追加は可) |
| R2 | ビルドシステムを変更しない。新規バンドル依存を追加しない |
| R3 | 既存ファイルへの変更は原則追加のみ。**例外 2 件**(§16.1)は機能本体であり、既定の振る舞いは変えない |
| R4 | ドキュメントを製品ブランチにコミットしない。本設計書はレビュー専用ブランチのみ |
| R5 | `out/gitlab-vscode-extension` は読み取り専用 |

---

## 7. システム構成

```
                     [ユーザー]
                        |
        コマンド / 保存   |
                        v
  +---------------------------------------------+
  |  層2: com.gitlab.eclipse.security            |
  |   SecurityScanLauncher (SWTフリー core)       |
  |   RunSecurityScanHandler                      |
  |   SecurityScanSaveListener                    |
  |   SecurityScanRequestRegistry (path -> source)|
  |   SecurityScanStatusReporter                  |
  +---------------------------------------------+
        |  $/gitlab/security/remoteSecurityScan
        v
  +---------------------------------------------+
  |            gitlab-lsp (8.80.0)               |
  |   POST /projects/{id}/security_scans/sast/scan|
  +---------------------------------------------+
        |                          |
        | textDocument/            | $/gitlab/security/
        | publishDiagnostics       | remoteSecurityScan/response
        v                          v
  +--------------------------+   +--------------------------+
  | 層1: lsp.diagnostics     |   | SecurityScanStatusReporter|
  |  DiagnosticUri           |   |  (通知 + 監査ログ)         |
  |  DiagnosticMarkerAttrs   |   +--------------------------+
  |  DiagnosticFileResolver  |
  |  DiagnosticGenerationReg |
  |  DiagnosticMarkerService |
  +--------------------------+
        |  IMarker
        v
   [Problems ビュー / エディタ縦ルーラ]
```

**層 1 はセキュリティを一切知らず、層 2 は marker を一切知らない。** この分離は「汎用マッピング層」という要件の帰結であり、同時に headless テスト可能性の分割線でもある。

---

## 8. コンポーネントの責務

### 8.1 層 1: `com.gitlab.eclipse.lsp.diagnostics`(新規サブパッケージ)

| クラス | 責務 | 依存 | headless テスト |
|---|---|---|---|
| `DiagnosticUri` | URI 文字列の正規化。比較キーの生成 | なし(純) | 完全 |
| `DiagnosticMarkerAttributes` | `Diagnostic` → marker 属性 `Map<String, Any>` | lsp4j の型のみ | 完全 |
| `DiagnosticGenerationRegistry` | per-URI 世代・epoch・active の管理 | なし(純) | 完全 |
| `DiagnosticFileResolver` | 正規化済みパス → `List<IFile>` | `IWorkspaceRoot` | 不可 |
| `DiagnosticMarkerService` | 世代検査・`WorkspaceJob` の schedule・marker の全置換 | `IResource`, `WorkspaceJob` | 一部 |

**`DiagnosticFileResolver` の解決手順**(曖昧さを残さないため明示する):

1. 正規化済み絶対パスを `org.eclipse.core.filesystem.URIUtil.toURI(path)` で `java.net.URI` に戻す
2. `ResourcesPlugin.getWorkspace().root.findFilesForLocationURI(uri)` を呼ぶ
3. 結果のうち `exists()` が真のものだけを返す
4. 0 件なら呼び出し元が破棄する(ワークスペース外のファイル)

`getFileForLocation` ではなく `findFilesForLocationURI` を使う理由は、**同一の物理ファイルが複数のプロジェクトにリンクされている場合に全件を返す**ためである。複数件が返った場合は**全件に marker を付け、削除も同じ集合に対して行う**。世代キーは正規化パス 1 つなので、集合が変わらない限り整合する。

### 8.2 層 2: `com.gitlab.eclipse.security`(新規サブパッケージ)

| クラス | 責務 |
|---|---|
| `SecurityScanLauncher` | ゲート判定と通知送信。**SWT フリーの `runSecurityScan(...)` を core として持つ**(Phase 4 の `runCiLint` / `runCreatePipeline` と同型) |
| `RunSecurityScanHandler` | コマンドハンドラ。UI スレッドでアクティブエディタを解決し core に渡す |
| `SecurityScanSaveListener` | `IPartListener2` + `IElementStateListener`。保存(dirty → clean)を検出して core を呼ぶ |
| `SecurityScanRequestRegistry` | 送信した `path -> source` の対応表(応答に `source` が含まれないため) |
| `SecurityScanStatusReporter` | 応答の分類・通知の抑制・監査ログ |
| `SecurityScanParams` / `SecurityScanResponse` | ワイヤ DTO |

### 8.3 変更する既存ファイル

| ファイル | 変更内容 | 種別 |
|---|---|---|
| `GitLabLanguageServerClient.kt` | `publishDiagnostics` の実装。`/response` の `@JsonNotification` 追加 | **振る舞い変更**(§16.1) |
| `GitLabLanguageServerConfigurationService.kt` | `remoteSecurityScans` を設定読み取りへ。`securityScannerOptions` を送出 | **振る舞い変更**(§16.1) |
| `GitLabLanguageServer.kt` | `runSecurityScan` の `@JsonNotification` 追加 | 追加のみ |
| `GitLabLanguageServerConfigurationParams.kt` | `securityScannerOptions` フィールドと `SecurityScannerOptions` 型 | 追加のみ |
| `GitLabLanguageServerProcessProvider.kt` | `stopLocked()` に `onServerStopped()` 呼び出しを追加 | 追加のみ |
| `PreferenceConstants.kt` / `PreferenceInitializer.kt` / `GitLabPreferencePage.kt` | 設定 2 件 | 追加のみ |
| `LanguageServerModule.kt`(または新規モジュール) | Koin 登録 | 追加のみ |
| `GitLabEclipseStartup.kt` | 起動時 activate / 停止時 deactivate | 追加のみ |
| `plugin.xml` | marker 型・command・handler・menu | 追加のみ |

---

## 9. 処理フロー

### 9.1 スキャン起動

```
[コマンド] または [アクティブエディタの保存]
  |
  v (UI スレッド)
アクティブエディタの IEditorInput を取得
  |- IFileEditorInput でない -> 中止 (source=command のときのみ通知)
  v
uri = file.locationURI.toASCIIString()      // didOpen と逐語的に同一の式
  |
  v (SWT フリー core: runSecurityScan(uri, source, ...))
設定 SECURITY_SCAN_ENABLED == false -> 何もしない(通知も出さない)
  |
  v
トークン未設定 -> 中止 (source=command のときのみ通知)
  |
  v
SecurityScanRequestRegistry.record(DiagnosticUri.normalize(uri), source)
  |
  v (coroutineScope)
languageServer?.runSecurityScan(SecurityScanParams(uri, source))
```

**対応表のキーについて。** LS は応答の `filePath` を `sh(n.uri).path`、すなわち**ドキュメント URI の decode 済み path** から作る。これは `DiagnosticUri.normalize` の戻り値と同一の値になる。したがって送信側は正規化済みパスをキーにして記録し、受信側は `res.filePath` をそのまま(念のため同じ正規化を通したうえで)引き当てる。

Windows では `URI.path` が `/C:/...` のように先頭スラッシュ付きになるため、`DiagnosticUri` の正規化がこの形を吸収する必要がある(U-5)。

保存トリガの場合は、上記の手前に `SECURITY_SCAN_ON_SAVE == true` の判定と「保存された element がアクティブエディタの入力である」判定を置く。

### 9.2 診断の受信と適用

```
[lsp4j リスナースレッド]  publishDiagnostics(params)
  |
  v
key = DiagnosticUri.normalize(params.uri)
  |- 失敗 -> debug ログのみ、破棄
  v
gen   = registry.nextGeneration(key)        // ここで順序が確定
epoch = registry.currentEpoch
  |
  v
files = DiagnosticFileResolver.resolve(key)
  |- 空 -> debug ログのみ、破棄(ワークスペース外)
  v
WorkspaceJob(rule = MultiRule(files)) を schedule して即 return   // ブロックしない
  |
  v [Job スレッド]
registry.shouldApply(key, gen, epoch) == false -> return
  |
  v
files.forEach { f ->
    f.deleteMarkers(GITLAB_DIAGNOSTIC_TYPE, false, DEPTH_ZERO)
    params.diagnostics.forEach { d ->
        f.createMarker(GITLAB_DIAGNOSTIC_TYPE).setAttributes(DiagnosticMarkerAttributes.of(d))
    }
}
  // diagnostics が空配列 -> 削除のみ(LSP の全置換セマンティクス)
  |
  v
CoreException は捕捉してログ。Job の外へ例外を出さない
```

### 9.3 スキャン応答

```
[lsp4j リスナースレッド]  securityScanResponse(res)
  |
  v
source = SecurityScanRequestRegistry.consume(normalize(res.filePath)) ?: SAVE   // 不明なら fail-quiet
  |
  v
分類:
  status == 200 -> Success(findings = res.results?.size ?: 0)
  status != 200 -> Failure(status, res.error)
  |
  v
監査ログ(§14) — error 本文は出さない
  |
  v
通知(§11.2 の表に従う。抑制判定は SecurityScanStatusReporter が保持)
```

---

## 10. API / インターフェース

### 10.1 追加する LSP インターフェース

`GitLabLanguageServer`(client → server):

```kotlin
@JsonNotification("$/gitlab/security/remoteSecurityScan")
fun runSecurityScan(params: SecurityScanParams)
```

`GitLabLanguageServerClient`(server → client):

```kotlin
@JsonNotification("$/gitlab/security/remoteSecurityScan/response")
fun securityScanResponse(response: SecurityScanResponse)

override fun publishDiagnostics(diagnostic: PublishDiagnosticsParams)   // 既存の override を実装
```

### 10.2 内部インターフェース(主要なもの)

```kotlin
object DiagnosticUri {
  /**
   * `file:` スキームのときだけ **decode 済みの絶対パス**を返す。
   * それ以外のスキーム、パース失敗、絶対パスでない場合は null(= 破棄)。
   */
  fun normalize(raw: String?): String?
}

object DiagnosticMarkerAttributes {
  fun of(diagnostic: Diagnostic): Map<String, Any>
}

object DiagnosticGenerationRegistry {
  @Volatile var active: Boolean
  val currentEpoch: Long
  fun nextGeneration(key: String): Long
  fun shouldApply(key: String, generation: Long, capturedEpoch: Long): Boolean
  fun onActivate()
  fun onDeactivate()
  fun onServerStopped()
}
```

`SecurityScanLauncher` の SWT フリー core:

```kotlin
internal fun runSecurityScan(
  uri: String?,                      // アクティブエディタが無い / IFileEditorInput でない場合は null
  source: SecurityScanSource,        // COMMAND | SAVE
  enabled: Boolean,
  hasToken: Boolean,
  send: (SecurityScanParams) -> Unit,
  notify: (String) -> Unit,
): SecurityScanLaunchOutcome         // SENT | DISABLED | NO_TOKEN | NO_EDITOR
```

`uri` を **nullable** にしてあるのは、「アクティブエディタが無い」という分岐まで headless で検証できるようにするためである(UI スレッド側は解決を試みて結果をそのまま渡すだけにする)。引数として外部依存を受け取ることで、「設定 OFF のとき `send` が 0 回呼ばれる」ことを直接証明できる。

**ゲートの評価順は `enabled` → `uri` → `hasToken` に固定する。** `enabled == false` のときは通知も監査ログも出さない(機能を使っていないユーザーに何も見せない)ため、この順序でなければならない。

---

## 11. データモデルと表示

### 11.1 ワイヤ DTO

```kotlin
data class SecurityScanParams(val documentUri: String, val source: String)   // source: "command" | "save"

data class SecurityScanResponse(
  val filePath: String? = null,
  val status: Int? = null,
  val results: List<Any?>? = null,   // 件数のみ使用。要素の形は未確定(U-3)
  val error: String? = null,
  val timestamp: Long? = null,
)
```

すべて nullable で受け、非 null へ正規化してから使う。Gson は非 null 宣言を迂回してコンストラクタを回避するため(follow-up #47 と同根)、**nullable 宣言 + 明示正規化**を必須とする。

### 11.2 marker 属性

marker 型: **`com.gitlab.eclipse.gitlab-eclipse-plugin.gitlabDiagnostic`**
(`Bundle-SymbolicName` = `com.gitlab.eclipse.${project.name}`(`build.gradle.kts:236`)、`rootProject.name = "gitlab-eclipse-plugin"`(`settings.gradle.kts:5`)より)

```xml
<extension point="org.eclipse.core.resources.markers" id="gitlabDiagnostic" name="GitLab Diagnostic">
    <super type="org.eclipse.core.resources.problemmarker"/>
    <super type="org.eclipse.core.resources.textmarker"/>
    <persistent value="false"/>
</extension>
```

| 属性 | 値 |
|---|---|
| `IMarker.SEVERITY` | `Error` → `SEVERITY_ERROR` / `Warning` → `SEVERITY_WARNING` / `Information`・`Hint` → `SEVERITY_INFO` / 未指定 → `SEVERITY_INFO` |
| `IMarker.MESSAGE` | `diagnostic.message` の**改行・連続空白を単一空白へ畳んだもの**。空なら `"(no message)"` |
| `IMarker.LINE_NUMBER` | `max(0, range.start.line) + 1`(LSP は 0 始まり、IMarker は 1 始まり) |
| `com.gitlab.eclipse.diagnosticSource` | `diagnostic.source`(欠落時は属性を設定しない) |
| `com.gitlab.eclipse.diagnosticCode` | `diagnostic.code` の文字列表現(欠落時は設定しない) |

**`CHAR_START` / `CHAR_END` は設定しない。** 文字オフセットは行・桁からは求まらず、`IDocument` かファイル内容の読み込みを要する。編集中のバッファとディスク内容は一致しないため、Job スレッドから正しい値を出せない。`LINE_NUMBER` のみでも Problems ビューと縦ルーラには表示され、F4(ダブルクリックで該当行へ)も成立する。**トレードオフ: エディタ内の範囲下線は出ず行単位の表示となる**(VSCode は範囲表示)。

**クランプ**: `range` が null、`line` が負、`character` が負のいずれの場合も例外にせず既定値へ丸める。P3 のとおり LS は負の `character` を出しうる。

### 11.3 エラー文言の整形

LS の文言には Markdown リンクが含まれる。Eclipse の通知はプレーンテキストであるため、`[text](url)` → `text (url)` に変換する純関数を通す。ネストしないパターンのみを対象とし、マッチしない場合は原文をそのまま返す。

---

## 12. トランザクション境界

- **marker の全置換が唯一のトランザクション境界**である。1 つの `WorkspaceJob` の中で「削除 → 生成」を行う。`WorkspaceJob` はワークスペース操作を 1 つのバッチにまとめ、リソース変更イベントを終了時にまとめて発火する。
- 途中で `CoreException` が発生した場合、**ロールバックはしない**。すでに削除された marker は戻らず、生成途中の marker は残る。これは受容する設計判断であり、理由は (a) marker は派生データであり再スキャンで完全に再構築できる、(b) 部分的な結果でも「削除だけ成功した」状態(= 何も表示されない)に留まり、誤った検出結果を表示することはない、ため。
- REST 呼び出しは LS 側で完結しており、クライアント側にトランザクション境界は無い。

---

## 13. エラー処理・タイムアウト・リトライ・冪等性

### 13.1 エラー処理

| 発生箇所 | 扱い |
|---|---|
| `DiagnosticUri.normalize` 失敗 | debug ログ、破棄。ユーザーには出さない |
| `DiagnosticFileResolver` が 0 件 | debug ログ、破棄。ワークスペース外のファイルは marker を持てない |
| `WorkspaceJob` 内の `CoreException` | 捕捉してログ。Job の status は `Status.OK_STATUS` を返し、**プラットフォームのエラーダイアログを出さない** |
| 応答 `status != 200` | §11.2 の通知ポリシーに従う |
| `runSecurityScan` 送信時の例外 | 捕捉してログ。**共有 `CoroutineScope` は plain `Job` であり、未捕捉例外 1 つでセッション中すべての非同期処理が停止する**(Phase 5A の教訓)。送信呼び出しは必ず try/catch で囲む |
| `publishDiagnostics` ハンドラ内の例外 | 捕捉してログ。lsp4j リスナースレッドへ例外を伝播させない |

### 13.2 タイムアウト

クライアント側にタイムアウトは設けない。理由は、スキャンは通知(fire-and-forget)であり応答を待つ `CompletableFuture` を持たないため、タイムアウトすべき対象が存在しないこと。応答が永久に来ない場合は「通知が出ないだけ」で、marker も変化せず、リソースリークは `SecurityScanRequestRegistry` のエントリ 1 件に留まる(§13.4)。

### 13.3 リトライ

自動リトライは行わない。ユーザーが再度コマンドを実行するか、再保存することが再試行である。

### 13.4 冪等性

- **スキャン自体は冪等**である(分析であり、サーバ側の状態を変えない)。したがって **in-flight ガードは設けない**(Phase 4 の CI lint と同じ判断)。同一ファイルへの並行スキャンが起きても、世代レジストリにより「最後に到着した診断だけが marker になる」。
- `SecurityScanRequestRegistry` は同一 path への上書きを許す。応答時に `consume` して除去する。**応答が来なかったエントリは残留する**ため、上限(例: 直近 64 件の LRU)を設けて無制限成長を防ぐ。LS 再起動時にクリアする。

---

## 14. 並行処理

### 14.1 スレッドの所在

| 処理 | スレッド |
|---|---|
| コマンド起動時のエディタ解決 | UI スレッド |
| 保存検出 | UI スレッド(`IElementStateListener` はエディタから呼ばれる) |
| スキャン通知の送信 | 共有 `CoroutineScope`(`Dispatchers.IO`) |
| `publishDiagnostics` / `/response` の受信 | lsp4j の単一リスナースレッド |
| 世代の採番 | lsp4j リスナースレッド |
| marker の適用 | `WorkspaceJob` のワーカスレッド |
| 通知の表示 | UI スレッド(`NotificationUtils` 経由) |

### 14.2 全順序化の根拠(既存レジストリとの設計差)

既存の `JobLogGenerationRegistry` / `CiLintGenerationRegistry` / `DiscussionGenerationRegistry` は、**UI スレッド**を用いて *gate-check-then-act* を全順序化している。本設計はそれを踏襲**しない**。marker 操作は UI スレッドを必要とせず、UI スレッドに寄せるとワークスペースロック待ちを UI スレッドへ持ち込むためである。

代わりに **Eclipse のスケジューリングルール階層**が同じ役割を果たす。

- 適用 Job のルール = 対象 `IFile`(複数解決時は `MultiRule`)
- 一括削除 Job のルール = **ワークスペースルート**(すべてのファイルルールと衝突する)

これにより、次が構造的に保証される。

1. 適用 Job が gate を通過して marker を書いている最中に、削除 Job が割り込むことはない(ルールが衝突するため、プラットフォームが直列化する)。
2. 停止手順を「(a) `active = false` → (b) 削除 Job を schedule」の順にすると、(a) の後に**開始する**適用 Job は `shouldApply` で false を見て何もせず、(a) の時点で**実行中の**適用 Job は削除 Job に先行して完了する。どちらの順序でも「最終的に marker は残らない」が成立する。

**この論証が本設計における最重要の不変条件であり、レビューの主眼としたい。**

### 14.3 世代とデータ構造

`nextGeneration` は lsp4j の単一リスナースレッドから呼ばれるため実質的に直列だが、将来 lsp4j にエグゼキュータが設定される可能性を考慮し、`AtomicLong` + `ConcurrentHashMap` で実装する(UI スレッド閉じ込めを前提にした既存レジストリとの明確な差異)。

`latest` マップは URI ごとに 1 エントリ増える。epoch bump 時にクリアするため、上限はセッション中にスキャンしたファイル数となる。

### 14.4 既知の順序リスク(受容)

`documentChanged` → `didChange` は共有 `CoroutineScope`(`Dispatchers.IO`、複数スレッド)で送信されるため、直前の打鍵の `didChange` とスキャン通知の到着順は保証されない。最悪、**最後の数打鍵が反映されない内容でスキャンされる**。

これは既存の同型課題(#16「`sendConfiguration()` の連続呼び出しに順序保証がなく、古い設定が後着しうる」)と同根であり、根治するには LS への通知全体を単一ディスパッチャへ直列化する必要がある。本フェーズの範囲を超えるため**既知の制限として受容し、PR 本文と台帳に明記する**。影響は「1 回古い内容でスキャンされる」に留まり、再保存で解消する。

### 14.5 ワークスペースロックの競合

`MrBranchCheckoutService.kt:200` の `refreshLocal(IResource.DEPTH_INFINITE, null)` と適用 Job が競合しうる。スケジューリングルールにより正しさは保たれるが、**待ちは発生する**。ブランチ切替中にスキャン結果の反映が遅れることがある。

---

## 15. 認証と認可

- スキャンの REST 呼び出しは **LS が行う**。トークンは既存の `didChangeConfiguration` 経路で LS へ渡されており、本機能で新たにトークンを扱う箇所は無い。
- クライアント側では「トークンが設定されているか」だけを判定して早期中止する(ユーザー体験のため。認可の実体ではない)。
- **クロスインスタンス送信の懸念は本機能には該当しない。** Phase 4 / 5A の `ConnectionSnapshot` / `pinnedConnectionFor` による接続固定は、クライアントが自ら REST を叩く経路のための機構である。本機能ではクライアントが GitLab へ直接リクエストを送らないため、接続固定の対象が存在しない。**ただしこれは「LS が現在の設定に従う」ことに依存しており、設定変更中にスキャンを投げると LS 側で新旧どちらの設定が使われるかは決定できない。** この点は §22 の未決事項 U-6 として明示する。
- スキャンは**ファイル全文を GitLab インスタンスへ送信する**。この事実を設定の説明文に明記し、既定を無効とすることで明示的なオプトインを要求する(F7)。

---

## 16. ログ・監視・監査

### 16.1 出力してよいもの / いけないもの

| 種別 | Error Log | ユーザー通知 |
|---|---|---|
| 監査行(下記) | 出す | — |
| 応答の `error` 本文 | **出さない** | 出す |
| 診断本文(脆弱性の名称・説明) | **出さない** | — |
| ファイルの絶対パス | **出さない**(ワークスペース相対に変換) | — |
| トークン・レスポンス本文 | **出さない** | 出さない |

`error` を Error Log に出さない理由: LS 側は固定文言だけでなく**例外の `message` をそのまま載せる経路**を持つ(`e instanceof Error && (o = e.message)`)。Phase 4 PR-4 で `Bearer <token>` が例外メッセージ経由で Error Log へ漏れた事例があるため、**共有・永続化されうる Error Log には載せず、ユーザー自身の画面にのみ出す**という分離を採る。

現行の `logger.info("publishDiagnostics: $diagnostic")` は診断本文とファイル URI を丸ごと出力しているため、**削除する**。

### 16.2 監査行の形式

```
securityScan source=command|save outcome=success|failure httpStatus=<int|-> findings=<int|-> path=<workspace-relative>
```

トークン・本文・診断内容を含まない。既存の `writeAuditMessage`(`WriteAction.kt:80-104`)/ `discussionAuditMessage`(`DiscussionWriteFlow.kt:90-116`)と同じ規律に従う。ログ呼び出し自体も `runCatching` で包み、ログの失敗が処理を壊さないようにする(Phase 5A の教訓)。

---

## 17. 障害時の復旧方法

| 症状 | 復旧 |
|---|---|
| Problems ビューに古い検出結果が残る | LS を再起動(既存コマンド `gl.restartLanguageServer`)。`onServerStopped()` が全 marker を削除する |
| marker が表示されない | (1) 設定が有効か、(2) 対象ファイルがワークスペース内でエディタに開かれているか、(3) Error Log の監査行で `outcome` を確認 |
| スキャンが常に失敗する | 監査行の `httpStatus` で切り分け(401 = 認証、403 = プロジェクト/ネームスペース、404 = インスタンスバージョン、500 = 不明) |
| 通知が出続ける | 設定を無効化する。無効化すると以後スキャンは送信されず、既存 marker は次の LS 停止時に消える |

**設定を無効化しても既存 marker は即座には消えない**点は仕様である(明示的な削除コマンドは設けない)。これを §22 の未決事項 U-7 とし、レビューで是非を問う。

---

## 18. 既存機能への影響

| 機能 | 影響 |
|---|---|
| Code Suggestions | なし。診断処理は lsp4j リスナースレッドをブロックしない(NF1) |
| Duo Chat / Agentic Chat | なし |
| サイドバー(MR / CI / Discussions) | なし。UI スレッドを使わない |
| LS の起動・再起動 | `stopLocked()` に呼び出しが 1 行増える。marker 削除は非同期のため `lifecycleLock` の保持時間は延びない |
| 設定ページ | チェックボックス 2 件が増える。`performOk()` の既存動作は変わらない |
| ワークスペースのビルド | 新しい marker 型が増えるが、`persistent=false` かつビルダーを追加しないため、ビルドには関与しない |

**既定の振る舞いは不変**である。マスタ設定の既定が `false` であるため `remoteSecurityScans` は従来どおり `false` として送出され、LS は診断を一切発行しない。したがって marker も作られない。これを受け入れ条件 A1 として検証する。

---

## 19. 移行方法

データ移行は不要。marker は `persistent=false` であり永続化されない。設定は新規キー 2 件で、既定値は `PreferenceInitializer` が与える。既存ユーザーのワークスペースに対して追加の初期化処理は要らない。

---

## 20. ロールバック方法

本機能は単一 PR で導入し、リバートで完全に元へ戻せる。理由:

- 新規ファイルはすべて新規サブパッケージ配下にある
- 既存ファイルへの変更のうち振る舞いを変えるのは 2 箇所のみで、いずれも元の 1 行に戻すだけで復元できる
- marker 型は `persistent=false` のため、リバート後に孤児 marker が永続化されて残ることはない

運用上の緊急停止は「設定を無効化する」で足りる(コード変更不要)。

---

## 21. テスト方針

### 21.1 headless で走る単体テスト(Kotest `DescribeSpec` + MockK)

| 対象 | ケース |
|---|---|
| `DiagnosticUri` | `file:/a/b` と `file:///a/b` が同一キーになる / パーセントエンコードのデコード / Windows の `/C:/` 整形とドライブレター大文字化 / 非 file スキーム / 不正 URI で null |
| `DiagnosticMarkerAttributes` | severity 4 値の写像と未指定 / **負の line・character のクランプ** / 改行と連続空白の畳み込み / 空メッセージ / `source`・`code` の有無 |
| `DiagnosticGenerationRegistry` | 追い越し(古い世代が false)/ epoch 不一致で false / `active=false` で false / `onServerStopped` で epoch が進み `latest` が消える |
| `runSecurityScan`(SWT フリー core) | **設定 OFF で `send` が 0 回** / トークン無しで 0 回 / `source=save` のとき通知が 0 回 / `source=command` のとき通知が 1 回 / 正常時に `send` が 1 回で params が期待どおり |
| 応答の分類 | `status=200` → Success と findings 件数 / `status!=200` → Failure / `filePath` 不明時に `save` へ倒れる / 抑制(同一 path・同一 status の連続で 2 回目が出ない・status 変化で出る) |
| Markdown 整形 | `[text](url)` → `text (url)` / リンクなしは原文のまま / 不完全な記法は原文のまま |
| 監査行 | `error` 本文・絶対パス・トークンが含まれないこと |
| `GitLabLanguageServerClient` | 既存の `GenericEndpoint` パターンで `textDocument/publishDiagnostics` と `$/gitlab/security/remoteSecurityScan/response` がディスパッチされること(`LoggingKotestExtension` が必要) |

### 21.2 テストの品質バー

各タスクのレビューで **ミュータント解析**(production を一時的に壊してテストが落ちるかを確認)を必須とする。Phase 5A では「閉じたはずの不変条件を再び開いても全テストが通る」穴がこの手法で複数発見された。

特に次の 3 点は、壊したときに落ちることを明示的に確認する。

- 設定 OFF のとき `send` が呼ばれないこと
- 古い世代の診断が適用されないこと
- 監査行に `error` 本文が入らないこと

### 21.3 plugin.xml の機械的検証

plugin.xml はコンパイルされないため、次を**件数で**報告する。

1. `jshell` による XML パース成功
2. command / handler / menu の 3-way id 一致(XPath で拡張ポイント単位にスコープ)
3. すべてのハンドラ FQN の宣言が実在すること
4. **marker 型名が Kotlin 定数と逐語一致すること**(`com.gitlab.eclipse.gitlab-eclipse-plugin.gitlabDiagnostic`)
5. `locationURI` が既存の「GitLab」サブメニューと一致すること

### 21.4 実機での手動検証(headless では原理的に不可能)

PR 本文にチェックリストとして記載する。

1. 設定を有効にせずにプラグインを起動し、Problems ビューに GitLab の項目が出ないこと
2. 設定を有効にしてコマンドを実行し、検出結果が Problems ビューに出ること
3. **`didOpen` で送った URI 文字列と `publishDiagnostics` で返ってきた URI 文字列をバイト単位で比較**(U-1 の確定)
4. 再スキャンで結果が置き換わること(累積しないこと)
5. LS を再起動して Problems ビューから消えること
6. 保存でスキャンが走ること / Save All では走らないこと
7. 401 / 404 のいずれかを意図的に起こし、通知が出て Error Log に本文が出ないこと
8. Problems ビューの項目をダブルクリックして該当行が開くこと

---

## 22. 受け入れ条件

| # | 条件 | 検証方法 |
|---|---|---|
| A1 | 設定を変更しなければ、本変更前と観測可能な振る舞いが一致する | `remoteSecurityScans=false` が送出されることのテスト + 実機 21.4-1 |
| A2 | 対象テストが PASS し、**全体の失敗数が 36 のまま** | `./gradlew build` |
| A3 | 変更ファイルの detekt 指摘が 0 | `./gradlew detekt` |
| A4 | **新規依存が無い** | `build.gradle.kts` と生成 MANIFEST の diff が空であることを提示 |
| A5 | plugin.xml の 3-way id 一致と marker 型名一致を件数で報告 | §21.3 |
| A6 | 診断適用が UI スレッドを使わないこと | コードレビューでの経路確認 |
| A7 | Error Log に診断本文・`error` 本文・絶対パスが出ないこと | 単体テスト + 実機 21.4-7 |
| A8 | ディレクトリ構成・ビルドシステムの変更が無いこと | diff の提示 |

---

## 23. 未決事項

推測で確定させず、未確定のまま明示する。

| # | 未決事項 | 影響 | 確定方法 |
|---|---|---|---|
| U-1 | `publishDiagnostics` が返す URI の実際の形(`file:/` のままか `file:///` に正規化されるか) | 設計は文字列比較を避けることで**両対応**にしてある。したがって設計判断には影響しないが、実装後の確認は必要 | 実機 21.4-3 |
| U-2 | 実 API が返す `severity` の値集合と大小(LS は小文字 `"high"` のみ Error 扱い) | Error/Warning の配分が変わるのみ。`SEVERITY` の写像は LS が決めた Diagnostic の値に従うため、クライアント側の設計には影響しない | 実機 |
| U-3 | `results[]` の要素の正確な形 | **marker には使わない**(marker は Diagnostic のみから作る)。通知の件数表示にのみ使うため影響は限定的 | 実機 |
| U-4 | 改行の個数(`\n` か `\n\n` か) | 畳み込むため**影響しない** | — |
| U-5 | Windows での `locationURI` の形とドライブレターの大小 | `DiagnosticUri` の正規化規則が正しいかに影響する | Windows 実機 |
| U-6 | 設定変更中にスキャンを投げた場合、LS 側で新旧どちらの設定が使われるか | 理論上、旧インスタンスの設定でスキャンが走る窓がありうる。ただしリクエストを送るのは LS であり、クライアントは接続を固定できない | **レビューで方針を問う** |
| U-7 | 設定を無効化したときに既存 marker を即座に消すべきか | 現設計では消えない(次の LS 停止まで残る)。明示的な削除コマンドを設けるかどうか | **レビューで方針を問う** |
| U-8 | `IElementStateListener` が File > Revert でも発火する点を許容するか | リバート時に 1 回余分なスキャンが走る(実害は無いが通信が発生する) | **レビューで方針を問う** |

---

## 24. 想定されるリスク

| # | リスク | 影響度 | 緩和 |
|---|---|---|---|
| K1 | **headless で検証できない領域が大きい**(URI 往復・marker 表示・保存検出・LS 実接続) | 高 | 純関数へ最大限切り出して単体テスト可能にする。残りは実機チェックリスト(§21.4)。実機検証を経ずにマージするとリグレッションの検出機会が無いことを PR に明記する |
| K2 | plugin.xml の marker 型名とKotlin 定数の不一致 | 高 | コンパイルもテストも通ってしまい実行時まで検出されない。§21.3-4 で機械的に照合する |
| K3 | 共有 `CoroutineScope`(plain `Job`)の汚染 | 高 | 送信・通知のスケジューリング呼び出しをすべて try/catch で封じ込める(Phase 5A の教訓) |
| K4 | ワークスペースロックの競合による遅延 | 中 | 正しさはスケジューリングルールで保たれる。遅延は受容し、§14.5 に明記 |
| K5 | 保存のたびにファイル全文が送信される | 中 | 既定を無効にし、設定説明文に明記する。デバウンスは VSCode パリティのため入れない |
| K6 | `SecurityScanRequestRegistry` の残留エントリ | 低 | 上限つき LRU + LS 再起動時クリア(§13.4) |
| K7 | 既存の `publishDiagnostics` ログを削除することで、既存の運用手順が壊れる | 低 | 当該ログは no-op のデバッグ出力であり、機能として依存されていない |

---

## 25. 実装の分割方針(参考)

単一 PR を想定する。理由は、層 1 のみでは起動手段が無く実機で何も検証できず、層 2 のみでは表示先が無いため、どちらか一方では受け入れ条件 A1 以外を満たせないため。

SDD のタスク分割は実装計画(フェーズ issue #13 へのコメント)で確定する。おおよその境界は次のとおり。

1. `DiagnosticUri` / `DiagnosticMarkerAttributes` / `DiagnosticGenerationRegistry`(純ロジック・TDD)
2. `DiagnosticFileResolver` / `DiagnosticMarkerService` / `publishDiagnostics` 実装
3. 設定 2 件 + `securityScannerOptions` + `remoteSecurityScans` の配線
4. `SecurityScanLauncher` core + DTO + `/response` ハンドラ + 通知・監査
5. `RunSecurityScanHandler` / `SecurityScanSaveListener` / plugin.xml 配線
6. `GitLabEclipseStartup` / `stopLocked` のライフサイクル結線
