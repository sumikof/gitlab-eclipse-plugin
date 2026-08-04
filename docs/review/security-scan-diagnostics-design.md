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

**これらの LS 側文言は本設計では表示にも記録にも使わない**(§11.4 でクライアント側の固定文言へ置き換える)。文言に Markdown リンクが埋め込まれていることは、LS の `error` が「そのまま画面に出す前提で作られていない」ことの傍証でもある。

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
  |   CommandWaiters (コマンド応答の待機のみ)      |
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
| `CommandWaiters` | `COMMAND` 起動の応答待ち(正規化パス → 待機数)。可視の応答(F6)を返すためだけに持つ(§9.1.1 / §13.2) |
| `SecurityScanStatusReporter` | 応答の分類・通知の抑制・監査ログ |
| `SecurityScanParams` / `SecurityScanResponse` | ワイヤ DTO |

### 8.2.1 保存の検出方式(Codex round1-P2 により未決 U-8 を確定)

**採用: `IElementStateListener` の `elementDirtyStateChanged(element, isDirty = false)`。** エディタのドキュメントプロバイダに登録し、dirty → clean の遷移を保存とみなす。さらに「保存された element がアクティブエディタの入力であるとき」だけ発火させる(VSCode の `onDidSaveActiveTextDocument` と同じ絞り込み。Save All で多数のファイルが一斉スキャンされるのを防ぐ)。バンドル `org.eclipse.ui.workbench.texteditor` は既に依存にある。

- **却下**: `IResourceChangeListener` — git チェックアウトや `refreshLocal(DEPTH_INFINITE)` でも発火し、無関係なスキャンを大量に誘発する
- **却下**: 保存コマンドへの `IExecutionListener` — プログラム的な保存を取りこぼす

**File > Revert は保存として扱わない(確定)。** 当初は「リバート時に 1 回余分なスキャンが走るが実害なし」としていたが、本機能は**ファイル全文を外部の GitLab インスタンスへ送信する opt-in 機能**であり、利用者は設定文言「Scan file on save」から**自分が保存した時だけ送信される**と理解する。保存していない操作で送信するのは同意範囲を超えるため、余分な通信を実害なしとは扱えない。

実現方法: `IElementStateListener` の `elementContentAboutToBeReplaced(element)` / `elementContentReplaced(element)` でリバート中であることを識別し、**そのシーケンス内で発生する dirty → clean 遷移をスキャン対象から除外する**。

```
elementContentAboutToBeReplaced(e)  -> revertingElements.add(e)
elementDirtyStateChanged(e, false)  -> e が revertingElements に含まれるならスキャンしない
elementContentReplaced(e)           -> revertingElements.remove(e)
```

`elementContentReplaced` が呼ばれない異常系に備え、`elementContentAboutToBeReplaced` からの経過が一定を超えたエントリは次回の判定時に破棄する(集合が伸び続けないようにする)。

### 8.3 変更する既存ファイル

| ファイル | 変更内容 | 種別 |
|---|---|---|
| `GitLabLanguageServerClient.kt` | `publishDiagnostics` の実装。`/response` の `@JsonNotification` 追加 | **振る舞い変更**(§16.1) |
| `GitLabLanguageServerConfigurationService.kt` | `remoteSecurityScans` を設定読み取りへ。`securityScannerOptions` を送出。**`launch { }` 本体を `LanguageServerOutboundLock` の `withLock` で包む**(§15.1) | **振る舞い変更**(§16.1)+ 送信の直列化 |
| `WorkspaceModule.kt`(または `LanguageServerModule.kt`) | `LanguageServerOutboundLock`(`Mutex`)の `single` 登録 | 追加のみ |
| `GitLabLanguageServer.kt` | `runSecurityScan` の `@JsonNotification` 追加 | 追加のみ |
| `GitLabLanguageServerConfigurationParams.kt` | `securityScannerOptions` フィールドと `SecurityScannerOptions` 型 | 追加のみ |
| `GitLabLanguageServerProcessProvider.kt` | `stopLocked()` に `onServerStopped()` 呼び出しを追加 | 追加のみ |
| `PreferenceConstants.kt` / `PreferenceInitializer.kt` | 設定 2 件の定数と既定値 | 追加のみ |
| `GitLabPreferencePage.kt` | チェックボックス 2 件の追加、および `performOk()` への「有効 → 無効の遷移検出」呼び出しの追加(§17.1) | 追加のみ |
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
設定 SECURITY_SCAN_ENABLED == false -> 何もしない(通知も監査も出さない)
  |
  v
トークン未設定 -> 中止 (source=command のときのみ通知)
  |
  v
source == COMMAND なら waiterId = CommandWaiters.add(正規化パス, connectionEpoch)
  |    // ★ ここでは期限を起動しない(§13.2)。Mutex 待ちで誤タイムアウトするため
  v (coroutineScope・捕捉した server プロキシを使用・§15.1 の Mutex 下)
ConnectionConfigGeneration の seqlock 下で設定を読む
  |- 不安定 -> 中止(COMMAND なら待機を解除して通知)
  v
server.didChangeConfiguration(読んだ設定)      // 同一コルーチンで先に送る
  |- 例外 -> 中止(COMMAND なら待機を解除して通知)
  v
server.runSecurityScan(SecurityScanParams(uri, source))
  |    // ★ 送信を試みた時点で応答期限を起動する(§13.2)
  v
COMMAND なら waiterId に対する応答期限(60 秒)を登録
  |- 例外 -> 中止(COMMAND なら待機を解除して通知)
```

**LS 側の前提(明記が必要)**: LS は自身のドキュメントストアに `didOpen` 済みの URI しかスキャンしません(見つからなければ**黙って return**)。本プラグインは `IFileEditorInput` のエディタにしか `didOpen` を送らないため、**ワークスペース外のファイル・プラグイン自身のインメモリエディタ(ジョブログ / merged YAML)はスキャン対象外**です。

### 9.1.1 並行スキャンを許容する(単一飛行を採らない)

**本設計は、同一ファイルに対する複数のスキャンが同時に進行することを許容する。**要求と応答を 1 対 1 に対応づける機構(single-flight・要求トークン・昇格キュー)は**設けない**。

#### 根拠

1. **プロトコルに相関 ID が無い。**`$/gitlab/security/remoteSecurityScan/response` は `{filePath, status, results?, error?, timestamp}` のみを返し、どの要求への応答かを識別する情報を持たない(§6.1 P1)。`textDocument/publishDiagnostics` も同様である。したがってクライアント側で厳密な対応づけを作るには、**送信・応答・タイムアウト・キャンセル・LS 再起動・設定変更のすべてに跨る状態機械**が必要になる。
2. **これはパリティ要件ではない。**移植元の VSCode 拡張(`gitlab-workflow` v6.85.3)は single-flight もタイムアウトも持たず、**同じ逆転を放置している**(`src/common/security_scans/run_security_scan.ts` は毎回無条件に通知を送るだけである)。本プロジェクトの目標は VSCode 版との機能パリティであり、VSCode 版が持たない不変条件を上乗せする理由は無い。
3. **実害が限定的で、自己修復する。**逆転が起きた場合の影響は「**次にそのファイルをスキャンするまで、古い内容に対する検出結果が表示される**」ことに留まる。marker は派生データであり、再保存・再スキャンで完全に置き換わる(§12 の二段階置換が、同一ファイルの世代交代そのものは正しく処理する)。誤ったコードが送信されることも、他インスタンスへ送信されることも、秘匿情報が漏れることもない。

#### 受容する制限(§23 に既知の制限として記載する)

| # | 制限 | 発生条件 |
|---|---|---|
| L1 | 同一ファイルの並行スキャンで、**古い内容の検出結果が新しい結果を上書きしうる** | 短時間に連続して保存し、応答が要求順と異なる順序で返った場合 |
| L2 | `COMMAND` 起動の通知が、保存起動の応答に消費されうる(通知の `source` の取り違え) | 同一ファイルに対する `COMMAND` と保存がほぼ同時に走った場合 |
| L3 | 送信例外の後に応答が届くと、**失敗通知が 2 回出うる** | `runSecurityScan` がワイヤ書き込み後に例外を返し、その後 **非 200 の応答**が届いた場合 |

いずれも**再スキャンで解消し、永続的な不整合を残さない**。L1 は VSCode 版と同一の挙動である。

#### 失敗経路の扱い

single-flight を持たないため、送信の失敗で解放すべきスロットは存在しない。したがって round7 で導入した Definite / Ambiguous の分類も**不要になる**(分類の目的は「スロットを解放してよいか」の判断だった)。

- seqlock が不安定 / `didChangeConfiguration` が例外 / `runSecurityScan` が例外 — いずれも**同じ扱い**とする
- `source == COMMAND` なら待機(§13.2)を解除し、§11.4 の固定文言で通知する
- 監査行に `outcome=failure` と `exceptionType` を残す(例外本文は出さない)
- `source == SAVE` なら監査行のみ

`runSecurityScan` の例外の後に応答が届く可能性は L3 として受容する。**その挙動を正確に述べる**(Codex round13-P2 / round15-P2):

- **同一パスに他の `COMMAND` 待機が無い場合**: 送信失敗の時点でその待機は解除されるため、後着応答は §9.3 で `SAVE` と分類される。200 応答なら §11.3 により成功通知は抑制され二重にならない。**非 200 応答のときだけ**、`SAVE` の失敗通知がもう 1 回出うる(抑制状態は送信失敗時に触れていないため)
- **同一パスに他の `COMMAND` 待機がある場合**: 後着応答は `consumeOldest` でその待機を消費し `COMMAND` と分類される。したがって **200 応答でも、送信失敗通知と成功通知の両方**が出る。さらにその `COMMAND` 自身の応答は後で `SAVE` として扱われる

どちらも通知の重複に留まり、marker の内容にも送信先にも影響しない。

**送信コルーチンが一度も開始しなかった場合(Codex round13-P2)。**共有 `CoroutineScope` が既にキャンセルされていると、`launch` は**例外を投げず本体を一度も実行しない**。この場合 try/catch では検出できず、実際には送信していない `COMMAND` が 60 秒後に誤って「応答なし」と通知され、しかもその状態のスコープでは以後の全コマンドが同じ挙動になる。

single-flight を撤廃しても**この検出は残す**。ただし状態機械は不要で、次の 3 行で足りる。

```kotlin
val entered = AtomicBoolean(false)
val job = runCatching { coroutineScope.launch { entered.set(true); sendScan(...) } }.getOrNull()
job?.invokeOnCompletion { cause -> if (cause != null && !entered.get()) onSendFailed(path, source) }
  ?: onSendFailed(path, source)
```

`onSendFailed` は待機を解除して(`COMMAND` なら)§11.4 の固定文言で通知し、監査行に `outcome=failure` を残す。**解放すべきスロットが無いので、これ以上の状態遷移は生じない。**

#### コマンド待機の管理

`COMMAND` 起動に可視の応答を返す(F6)ためだけに、最小限の状態を持つ。

```kotlin
object CommandWaiters {                    // 正規化パス -> 待機 id の FIFO
  /** COMMAND 送信時。待機 id を払い出す(epoch 不一致なら null) */
  fun add(path: String, connectionEpoch: Long): Long?

  /** 応答受信時。最も古い待機を 1 件除去して true(どの要求の応答かは区別できない = L2) */
  fun consumeOldest(path: String, connectionEpoch: Long): Boolean

  /** 期限切れ・送信失敗・未開始時。**自分の待機 id だけ**を除去する */
  fun consumeById(waiterId: Long, connectionEpoch: Long): Boolean

  /** LS 停止・設定無効化。除去した待機を返す(パス -> 件数) */
  fun clear(connectionEpoch: Long): Map<String, Int>
}
```

- **パスごとの待機数だけを持ち、要求単位の識別子・世代・トークン・キューは持たない**
- 応答受信時に `consumeOldest(path, epoch)` が `true` を返せば `COMMAND` として扱い、`false` なら `SAVE` として扱う(L2 の取り違えはここで生じるが受容する)
- 送信失敗時・タイムアウト時・LS 停止時・設定無効化時に除去する

**接続 epoch を全操作に渡す(Codex round13-P1)。**`consume` が epoch を受け取らないと、旧接続の応答が §9.3 の入口検査を通過した直後に LS 停止と新接続の `add` が入った場合、**旧応答が新しい `COMMAND` の待機を消費**してしまう。§14.6 が要求する「照合から状態変更までの原子性」を、single-flight 撤廃後の新 API で再び破ることになる。したがって `add` / `consume` / `clear` はいずれも `connectionEpoch` を受け取り、**§14.6 と同じロックの下で照合してから実行する**。epoch が一致しなければ何もせず `false` を返す。

**集合ではなく待機の列を持つ(Codex round13-P2 / round14-P1)。**単なる集合にすると、同じファイルで `COMMAND` を応答前に 2 回実行した場合、2 回目の `add` は状態を増やさず、最初の応答が唯一の要素を除去する。残る応答は `SAVE` 扱いとなり、**もう一方の明示操作には成功・失敗・タイムアウトのいずれの通知も出ない**(F6 違反)。

round13 ではこれを整数カウンタで解決したが、**それでも不十分だった**。カウンタだけでは、期限タスクが「自分の待機」を特定できない。要求 A の応答が count を減らした後、60 秒以内に B を開始し、A の期限タスクが後から発火すると、**A の期限タスクが B の待機を消費**する。B は自分の期限より早く「応答なし」と通知され、後着した B の応答は `SAVE` 扱いになる。設定を無効化して同じ epoch のまま再有効化した場合も、`clear` 前に登録された期限タスクが新しい待機を消費しうる。

したがって **`add` は待機 id を払い出し**、除去の経路を 2 つに分ける。

| 除去の契機 | 使う API | 理由 |
|---|---|---|
| 応答の受信 | `consumeOldest(path, epoch)` | どの要求の応答かは原理的に区別できない(L2)。最も古い待機を 1 件消す |
| **期限切れ・送信失敗・未開始** | **`consumeById(waiterId, epoch)`** | **自分が登録した待機だけ**を消す。他の要求の待機に触れない |

`COMMAND` を n 回実行すれば n 回通知され、期限タスクが互いの待機を奪うこともない。

**`waiterId` は再利用しない(Codex round15-P1)。**`consumeById` が古い期限タスクから新しい待機を守れるのは、**id が再利用されない**場合だけである。設定の無効化は接続 epoch を進めないため、`clear` が採番器も初期化する実装では、再有効化後の B に A と同じ id が払い出され、A の残存期限タスクが B を再び消費する。したがって:

- `waiterId` は**単一の `AtomicLong` から単調に採番する**
- **`clear` も `suspendSource` も epoch の更新も、採番器を初期化しない**
- 採番器はバンドルの生存期間を通じて単調増加する(64bit なので枯渇しない)

#### 期限起動前の待機にも終了経路を与える(Codex round15-P2)

§13.2 のとおり応答期限は `runSecurityScan` の試行**後**に起動するため、そこへ到達しない経路では待機に期限が付かない。到達しない経路は次のとおり。

- コルーチンが一度も開始しない(キャンセル済みスコープ)
- `entered = true` の後、`Mutex` 待ちや seqlock 読み取り中にキャンセルされた
- `didChangeConfiguration` / `runSecurityScan` が例外を投げた

**`invokeOnCompletion` を「期限が起動しなかった待機」の万能の後始末にする。**

```kotlin
job?.invokeOnCompletion { _ ->
  // 正常終了・例外・キャンセルのいずれでも通る。
  // 期限が起動済みならその期限に任せ、未起動なら待機をここで解決する。
  if (!CommandWaiters.isDeadlineArmed(waiterId)) onSendFailed(waiterId, path, source)
}
```

`markDeadlineArmed(waiterId, epoch)` は期限タスクを登録した直後に呼ぶ。`onSendFailed` は `consumeById` で自分の待機だけを除去し、`COMMAND` なら §11.4 の固定文言で通知し、監査行に `outcome=failure` を残す。**`consumeById` は冪等**なので、送信失敗の catch と `invokeOnCompletion` の両方から呼ばれても通知は 1 回である。

**残る唯一の穴**: `runSecurityScan` の書き込みが**永久にブロック**した場合、Job は完了せず待機も残る。これは LSP チャネル自体が死んでいる状態であり、本機能単独では回復できない。K11(`Mutex` 保持中のブロック)と同じ扱いで受容し、LS 再起動(`clear`)で解消する。

#### 無効化と並行する待機登録(Codex round15-P2)

起動処理が `enabled == true` を確認した後、待機を登録する前に設定の無効化が `clear` を完了すると、その起動処理は `clear` の後に新しい待機を登録して送信を続行できる。無効化済みなので LS は黙って return し、**無効化した後になって 60 秒後のタイムアウト通知が出る**。

**確定仕様: `Mutex` の中で設定を再確認する。**§15.1 の seqlock 下で設定値を読む際に `SECURITY_SCAN_ENABLED` も読み、**偽なら送信せず `consumeById` で待機を除去して終了する**(通知はしない。利用者自身が無効化した直後であり、通知は不要かつ誤解を招く)。

これは §15.1 の「送信直前に設定を読んで先に送る」手順に 1 つ条件を足すだけで実現でき、追加のロックも順序制約も生じない。

### 9.2 診断の受信と適用

```
[lsp4j リスナースレッド]  publishDiagnostics(params)
  |
  v
this.capturedConnectionEpoch != registry.currentEpoch -> debug ログのみ、破棄   // §14.6
  |
  v
key = DiagnosticUri.normalize(params.uri)
  |- 失敗 -> debug ログのみ、破棄
  v
tokens = params.diagnostics.associateWith { registry.acceptToken(it.source) }
  |    // ★ acceptToken は「停止しているか」と「source 失効世代」を **単一の原子的読み取り**で返す。
  |    //   停止中なら null。§17.1 の偶奇エンコードにより 1 回の volatile 読みで済む
  v
accepted = tokens.filterValues { it != null }.keys
  |
  v
params.diagnostics.isNotEmpty() && accepted.isEmpty() -> **何もしない(no-op)**
  |    // 全要素が停止中 source で除外された場合、この URI に対する権威ある情報が
  |    // 残らないため、全置換として適用すると他 source の marker まで消しうる(§9.2.1)
  v
gen   = registry.nextGeneration(key)        // ここで順序が確定。全 URI を通じて一意
  |- null(active=false)-> 破棄
epoch = registry.currentEpoch               // gen とは別軸。§11.2 の表を参照
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
final = accepted.filter { registry.isTokenValid(tokens[it]) }
  |    // ★ 受理時に得た token を再検査。世代が進んでいる source(= 受理後に停止された)は落ちる
  |
  |- accepted が非空で final が空 -> **何もしない(no-op)**。理由は上と同じ
  v
files.forEach { f -> §12 の二段階置換(生成 -> 全件成功時のみ gen != genNew を削除) }
  |
  v
CoreException は捕捉してログ。Job の外へ例外を出さない
```

### 9.2.1 全要素が除外されたバッチを no-op にする理由(Codex round4-P2)

指摘の機序について、事実関係を先に整理する。**LSP の `publishDiagnostics` は URI 単位の全置換であり、単一のサーバから届く 1 バッチはその URI の全 source を通じた権威ある集合である。**したがって「security だけのバッチが届いた」= 「その URI には他 source の診断は無い」であり、他 source の marker を消すこと自体は LSP のセマンティクス上は正しい。現時点の LS は診断の発行器を 1 つしか持たないため、実害も生じない。

それでもなお **no-op を採用する**。理由は次のとおり。

1. 停止中の source を除外した時点で、**その集合はもはやサーバが送った集合ではない**。他 source について権威を主張できるのは「サーバが実際に送った要素」だけであり、除外の結果できた空集合に全置換の権威を与えるのは論理の飛躍である
2. 受け入れ条件 **A10 は「source 単位の停止が他診断に影響しない」と明言している**。上記の挙動はその条件と正面から矛盾する。設計内の矛盾は、LSP 的に正当化できるかどうかとは別に解消すべきである
3. リスクが非対称である。no-op にして失うのは「停止中 source のバッチが他 source の marker を掃除する機会」だけで、次に届く非除外バッチが正しく置換する。採用しない場合に失うのは他 source の marker そのものである

**元のバッチが空だった場合は従来どおり全置換(= 全削除)として適用する。**「除外の結果空になった」場合とは区別する。

### 9.3 スキャン応答

```
[lsp4j リスナースレッド]  securityScanResponse(res)
  |
  v
this.capturedConnectionEpoch != registry.currentEpoch -> debug ログのみ、破棄   // §14.6
  |
  v
path   = DiagnosticUri.normalize("file:" + res.filePath) ?: res.filePath
source = if (CommandWaiters.consumeOldest(path, capturedConnectionEpoch)) COMMAND else SAVE  // §9.1.1
  |
  v
分類:
  status == 200 -> Success(findings = res.results?.size ?: 0)
  status != 200 -> Failure(status)          // res.error は使わない(§11.4)
  |
  v
監査ログ(§16.2) — error 本文・絶対パスを出さない
  |
  v
通知(§11.3 の表 + §11.4 の固定文言。SAVE の失敗抑制状態は SecurityScanStatusReporter が保持)
```

**この経路は状態遷移を持たない。**`CommandWaiters` から 1 件除去し、分類して通知するだけである。次に送るべき要求(昇格)も、解放すべきスロットも存在しない。

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
  @Volatile var active: Boolean          // バンドルの生存。LS 停止では落とさない(§14.2.1)
  val currentEpoch: Long

  /** active でないときは null を返す(= その診断は破棄) */
  fun nextGeneration(key: String): Long?

  /**
   * 「停止しているか」と「source 失効世代」を **単一の原子的読み取り**で返す(§17.1)。
   * 停止中なら null。判定と捕捉を分けると、その隙間の suspendSource を取りこぼす。
   */
  fun acceptToken(source: String?): SourceToken?
  fun isTokenValid(token: SourceToken?): Boolean

  fun shouldApply(key: String, generation: Long, capturedEpoch: Long): Boolean

  fun onActivate()                       // バンドル起動
  fun onDeactivate()                     // バンドル停止: active = false
  fun onServerStopped()                  // LS 停止: epoch++ / latest 破棄(active は不変)

  /**
   * 層 2 が呼ぶ。引数の source を解釈しない = セキュリティ固有の概念を持たない(§17.1)。
   * 停止は source 単位にスコープされ、他の source の診断には一切影響しない。
   */
  fun suspendSource(source: String)
  fun resumeSource(source: String)
  fun isSuspended(source: String?): Boolean   // 表示・診断用。受理判定には acceptToken を使う
}

/** COMMAND 起動に可視の応答を返すためだけの最小状態(§9.1.1) */
object CommandWaiters {                    // 正規化パス -> 待機 id の FIFO
  fun add(path: String, connectionEpoch: Long): Long?
  fun consumeOldest(path: String, connectionEpoch: Long): Boolean
  fun consumeById(waiterId: Long, connectionEpoch: Long): Boolean
  fun markDeadlineArmed(waiterId: Long, connectionEpoch: Long)
  fun isDeadlineArmed(waiterId: Long): Boolean
  fun clear(connectionEpoch: Long): Map<String, Int>
}
```

`CommandWaiters` の全操作は `connectionEpoch` を受け取り、§14.6 と同じロックの下で照合してから実行する。詳細は §9.1.1。

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
| `com.gitlab.eclipse.diagnosticSource` | `diagnostic.source`。**必須。**欠落時は `"(unknown)"` を入れる(§17.1 の source 単位の失効に使うため空にできない) |
| `com.gitlab.eclipse.diagnosticCode` | `diagnostic.code` の文字列表現(欠落時は設定しない) |
| `com.gitlab.eclipse.diagnosticGeneration` | **その置換 1 回に固有の generation。必須。**§12 の二段階置換の判定に使う |
| `com.gitlab.eclipse.diagnosticEpoch` | **ライフサイクル epoch。必須。**§14.2.1 の LS 停止時の掃除に使う |

**`diagnosticGeneration` と `diagnosticEpoch` は別物である(Codex round2-P1 により分離)。**round1 の反映では両者を 1 つの属性に統合していたが、これは致命的な誤りだった。`epoch` は LS 停止・バンドル停止でしか進まないため、**同一 LS セッション内の連続スキャンでは旧 marker と新 marker が同じ値を持つ**。その結果、

- 成功時の「`!= epochNew` を削除」は旧 marker を削除できず、**検出結果が累積して F5 に反する**
- 生成失敗時の「`== epochNew` を削除」は、**保持すべき旧 marker まで削除する**

`generation` は §9.2 の `nextGeneration(key)` が返す値をそのまま使う。単一の `AtomicLong` から採番されるため**全 URI を通じて一意**であり、置換ごとに必ず異なる。

| 用途 | 比較する属性 | 進む契機 |
|---|---|---|
| §12 の二段階置換(同一ファイルの世代交代) | `diagnosticGeneration` | 診断を受信するたび |
| §14.2.1 の LS 停止時の掃除 | `diagnosticEpoch` | LS 停止・バンドル停止 |
| §17.1 の設定無効化時の失効 | `diagnosticSource` | 設定の有効 → 無効の遷移 |

**`CHAR_START` / `CHAR_END` は設定しない。** 文字オフセットは行・桁からは求まらず、`IDocument` かファイル内容の読み込みを要する。編集中のバッファとディスク内容は一致しないため、Job スレッドから正しい値を出せない。`LINE_NUMBER` のみでも Problems ビューと縦ルーラには表示され、F4(ダブルクリックで該当行へ)も成立する。**トレードオフ: エディタ内の範囲下線は出ず行単位の表示となる**(VSCode は範囲表示)。

**クランプ**: `range` が null、`line` が負、`character` が負のいずれの場合も例外にせず既定値へ丸める。P3 のとおり LS は負の `character` を出しうる。

### 11.3 通知ポリシー(確定表)

**Codex round1 の指摘により追加。**従来この表は本文に存在せず、§9.3 が実在しない表を参照していた。

`source` × `outcome` の全組み合わせを次のとおり確定する。ここに無い組み合わせは存在しない。

| source | outcome | 通知 | 文言 | 抑制キー | 抑制の解除条件 |
|---|---|---|---|---|---|
| `COMMAND` | 成功・検出 N>0 | 出す | `GitLab security scan: N issue(s) found. See the Problems view.` | なし(常に出す) | — |
| `COMMAND` | 成功・検出 0 | 出す | `GitLab security scan: no issues found.` | なし(常に出す) | — |
| `COMMAND` | 失敗 | 出す | §11.4 の固定文言 | なし(常に出す) | — |
| `COMMAND` | タイムアウト | 出す | `GitLab security scan: no response from the language server.` | なし(常に出す) | — |
| `SAVE` | 成功(件数を問わず) | **出さない** | — | — | 結果は Problems ビューに出る |
| `SAVE` | 失敗 | **状態が変化したときのみ**出す | §11.4 の固定文言 | `(正規化パス, status)` | 同一パスで `status` が変わる / 成功が挟まる / LS 再起動 / 設定の再有効化 |
| `SAVE` | タイムアウト | **出さない** | — | — | 監査行にのみ残す |
| `COMMAND` | 送信に失敗(§9.1.1) | 出す | §11.4 の固定文言(`status` 不明なので汎用文言) | なし(常に出す) | — |
| `SAVE` | 送信に失敗(§9.1.1) | **出さない** | — | — | 監査行にのみ残す |
| `COMMAND` | LS 停止で待機を破棄(§13.4) | 出す | `GitLab security scan: the scan was cancelled because the language server restarted. Run the scan again.` | なし(常に出す) | — |
| `SAVE` | LS 停止で待機を破棄 | **出さない** | — | — | `SAVE` は待機を持たないため該当しない |

**`COMMAND` を一切抑制しない理由**: 明示操作には必ず可視の応答を返す(F6)。抑制すると「コマンドを押したのに何も起きない」が発生する。

**`SAVE` の失敗抑制の状態**は `(正規化パス) -> 直近の status` を保持する `Map` で表現し、成功時と LS 再起動時と設定再有効化時にエントリを消す。抑制は**時間ベースではない**(時間ベースにすると「直したのに再通知されない」窓が生まれるため)。

### 11.4 エラー文言(クライアント側の固定文言)

**Codex round1-P1(秘匿)の指摘により方針変更。**LS が返す `error` 文字列は**表示にも記録にも一切使わない**。LS 側の該当コードは固定文言だけでなく `e instanceof Error && (o = e.message)` の経路を持ち、ネットワーク層や認証層の例外メッセージ(= 資格情報や URL を含みうる)がそのまま入る。画面・スクリーンショット・画面共有への露出は Error Log への出力と同じく秘匿事故であるため、**`status` から引くクライアント側 allowlist へ変換する**。

| status | 表示文言 |
|---|---|
| 401 | `GitLab security scan failed: authentication failed. Your token may be invalid or expired. Re-authenticate in the GitLab preferences.` |
| 403 | `GitLab security scan failed: the real-time scan is not available for this project or namespace.` |
| 404 | `GitLab security scan failed: the real-time scan is not available on this GitLab instance (requires GitLab 17.5.0 or later).` |
| 上記以外 / 欠落 | `GitLab security scan failed (status <status>). See the Error Log for details.` |

文言はクライアント側で持つため Markdown リンクは含まれない(従来ここに置いていた `[text](url)` 整形の純関数は**不要になったため削除する**)。`status` は整数のみで、`error` 本文は通知にも Error Log にも渡さない。

---

## 12. トランザクション境界

**Codex round1-P2 の指摘により「削除 → 生成」から二段階置換へ変更。**

当初は 1 つの `WorkspaceJob` の中で「旧 marker を削除 → 新 marker を生成」する順序としていたが、生成の途中で `CoreException` が起きると**一部の検出結果だけが Problems ビューに残り、利用者はそれを完全なスキャン結果と誤認する**。「誤った検出結果を表示することはない」という当初の根拠は、失敗位置が削除直後の場合しか成立していなかった。

### 12.1 二段階置換(確定手順)

1 つの `WorkspaceJob`(rule = 対象ファイル群)の中で、次の順に行う。

1. `genNew` = この適用処理の generation(§9.2 の `nextGeneration` が返した値。**epoch ではない**)
2. **生成フェーズ**: すべての diagnostic について marker を生成し、`diagnosticGeneration = genNew` と `diagnosticEpoch = 現在の epoch` を付与する
3. **切替フェーズ**: 生成が全件成功した場合にのみ、**そのファイル上の** `diagnosticGeneration != genNew` の自分の型の marker を削除する
4. **失敗時**: 生成フェーズで `CoreException` が起きたら、**`diagnosticGeneration == genNew` の marker をすべて削除して中止する**。旧 marker はそのまま残る

これにより、観測可能な状態は「**完全な旧世代**」か「**完全な新世代**」の二択になり、部分的な集合は表示されない。`diagnostics` が空配列の場合も同じ手順で、生成 0 件 → 旧世代削除、となり整合する。

### 12.2 ロールバックの限界(明示)

切替フェーズ(手順 3)の削除中に `CoreException` が起きた場合、旧世代の一部が残り新世代と混在しうる。これは**受容する**。理由は (a) この状態でも表示される marker はすべて実在した検出結果であり、捏造された結果ではない、(b) 次回のスキャンで完全に再構築される、(c) 削除失敗を補償する削除を再試行しても同じ理由で失敗する公算が高い、ため。**この限界を §24 のリスク表に記載する。**

### 12.3 その他

- `WorkspaceJob` はワークスペース操作を 1 つのバッチにまとめ、リソース変更イベントを終了時にまとめて発火する。
- REST 呼び出しは LS 側で完結しており、クライアント側にトランザクション境界は無い。

---

## 13. エラー処理・タイムアウト・リトライ・冪等性

### 13.1 エラー処理

| 発生箇所 | 扱い |
|---|---|
| `DiagnosticUri.normalize` 失敗 | debug ログ、破棄。ユーザーには出さない |
| `DiagnosticFileResolver` が 0 件 | debug ログ、破棄。ワークスペース外のファイルは marker を持てない |
| `WorkspaceJob` 内の `CoreException` | 捕捉してログ。Job の status は `Status.OK_STATUS` を返し、**プラットフォームのエラーダイアログを出さない** |
| 応答 `status != 200` | §11.3 の通知ポリシー表と §11.4 の固定文言に従う |
| `runSecurityScan` 送信時の例外 | 捕捉してログ。**共有 `CoroutineScope` は plain `Job` であり、未捕捉例外 1 つでセッション中すべての非同期処理が停止する**(Phase 5A の教訓)。送信呼び出しは必ず try/catch で囲む |
| `publishDiagnostics` ハンドラ内の例外 | 捕捉してログ。lsp4j リスナースレッドへ例外を伝播させない |
| 停止中の `source` の診断 | その diagnostic のみバッチから除外(他 source は通常どおり適用)。debug ログのみ(§17.1) |
| 待機の無い応答(`consumeOldest` が false) | `SAVE` として分類する(§9.3)。成功は通知せず、失敗は §11.3 の抑制規則に従う |

### 13.2 コマンド応答の期限

§6.1 P2 のとおり、LS は次の 3 経路で**応答を返さずに return する**。

- 手順 2: 対象 URI のドキュメントが LS のストアに無い
- 手順 6: 設定ゲート(`remoteSecurityScans` / `securityScannerOptions.enabled`)が偽
- 手順 8: 応答の `vulnerabilities` が null または非配列

期限が無いと、**明示コマンドを実行しても永久に何も起きず、ユーザーは成功と失敗を区別できない**(F6 違反)。したがって期限は設ける。ただし **`COMMAND` に可視の応答を返すためだけの仕組み**であり、送信の可否や状態遷移には一切関与しない。

**確定仕様**:

- `COMMAND` 送信時に `waiterId = CommandWaiters.add(path, connectionEpoch)` で待機を登録する。**この時点では期限を起動しない**
- **`server.runSecurityScan(...)` を試みた直後**に、`SCAN_RESPONSE_TIMEOUT`(既定 **60 秒**)後に起床する遅延タスクを、その `waiterId` に対して 1 つ登録する
- 起床時に `CommandWaiters.consumeById(waiterId, connectionEpoch)` が `true` を返したら、§11.3 のタイムアウト文言を通知し、監査行に `outcome=timeout` を残す
- `false`(既に応答・送信失敗・停止などで除去済み)なら**何もしない**
- **`SAVE` には期限を設けない。**保存起動は成功時に通知しないため、待つ対象が無い

**期限の起点は「待機の登録時」ではなく「送信を試みた時点」である(Codex round14-P2)。**待機の登録は UI スレッド側で行うが、実際の送信は §15.1 の `Mutex` を取得してからになる。リスク K11 のとおり `Mutex` 保持中に LS への書き込みがブロックしうるため、登録時点で 60 秒の期限を起動すると、**まだ送信していない `COMMAND` が「応答なし」と誤って通知され、その後ロックを取得した送信は続行して応答が `SAVE` 扱いになる**。

送信に到達する前の異常(コルーチンが一度も開始しない・seqlock 不安定・`didChangeConfiguration` の例外)は、期限ではなく §9.1.1 の**未開始検出と送信失敗処理**が `consumeById` で解決する。したがって「待機が永久に残る」経路は生じない。

**期限切れはそのパスを塞がない。**次のコマンドも保存も、いつでも新しいスキャンを送信できる。単一飛行を採らない(§9.1.1)ため、塞ぐべきスロットが存在しない。

タイマ起床は共有 `CoroutineScope` を使わず、`org.eclipse.core.runtime.jobs.Job` の `schedule(delay)` で行う(共有スコープは plain `Job` であり、未捕捉例外がセッション全体を止めるため)。

### 13.3 リトライ

自動リトライは行わない。ユーザーが再度コマンドを実行するか、再保存することが再試行である。

### 13.4 冪等性と並行実行

**スキャンはサーバ側の状態を変えない冪等な分析である。**二重送信が破壊的な副作用を生むことはない。したがって **in-flight ガードも single-flight も設けない**(Phase 4 の CI lint と同じ判断)。

並行スキャンによって生じる結果の逆転・通知の取り違えは、§9.1.1 の L1〜L3 として**受容する既知の制限**である。VSCode 版も同じ挙動であり、いずれも再スキャンで解消する。

デバウンスも入れない(VSCode パリティ)。ただし「保存のたびにファイル全文が POST される」ことは設定の説明文に明記する。

**LS 停止時の扱い**: `CommandWaiters.clear(epoch)` を呼び、**除去した待機数のぶんだけ** §11.3 に従って `COMMAND` に通知し(旧接続の応答は §14.6 で破棄されるため、通知しなければ永久に無反応になる)、監査行に `outcome=cancelled` を残す。保持している状態はパスごとの待機数だけなので、これ以外に破棄するものは無い。

**設定無効化時は通知しない**(§17.1)。破棄する点は同じだが、利用者自身の操作の直接の結果であり、かつ §11.3 の破棄文言は LS 再起動を指すため流用すると虚偽になる。

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
| 要求タイムアウトの起床 | `org.eclipse.core.runtime.jobs.Job`(共有 `CoroutineScope` を使わない・§13.2) |

### 14.2 全順序化の根拠(既存レジストリとの設計差)

既存の `JobLogGenerationRegistry` / `CiLintGenerationRegistry` / `DiscussionGenerationRegistry` は、**UI スレッド**を用いて *gate-check-then-act* を全順序化している。本設計はそれを踏襲**しない**。marker 操作は UI スレッドを必要とせず、UI スレッドに寄せるとワークスペースロック待ちを UI スレッドへ持ち込むためである。

代わりに **Eclipse のスケジューリングルール階層**が同じ役割を果たす。

- 適用 Job のルール = 対象 `IFile`(複数解決時は `MultiRule`)
- 一括削除 Job のルール = **ワークスペースルート**(すべてのファイルルールと衝突する)

これにより、次が構造的に保証される。

1. 適用 Job が gate を通過して marker を書いている最中に、削除 Job が割り込むことはない(ルールが衝突するため、プラットフォームが直列化する)。
2. **バンドル停止**の手順を「(a) `active = false` → (b) 全削除 Job を schedule」の順にすると、(a) の後に**開始する**適用 Job は `shouldApply` で false を見て何もせず、(a) の時点で**実行中の**適用 Job は削除 Job に先行して完了する。どちらの順序でも「最終的に marker は残らない」が成立する。

### 14.2.1 LS 停止・再起動の扱い(Codex round1-P1 により全面改訂)

当初は「停止手順」を LS 停止とバンドル停止で書き分けておらず、そこに実欠陥があった。**両者は別物として確定する。**

まず事実確認として、`GitLabLanguageServerProcessProvider.restart()` は `stopLocked()` → `startLocked()` を呼ぶだけでバンドルは再起動しない。当初設計は LS 停止時に `active` を落とさないため、「新 LS の診断が `shouldApply` で恒久的に破棄される」という事象は起きない。**しかし別の実欠陥が存在する**: LS 停止時に schedule した**全削除 Job(ルートルール)が、新 LS の診断を適用した Job より後に走ると、新しい marker を消してしまう**。Job の実行順序は FIFO で保証されないため、これは現実に起こりうる。

**確定仕様: 掃除は「epoch による選択削除」にする。**

- すべての marker は生成時に `com.gitlab.eclipse.diagnosticEpoch`(ライフサイクル epoch)を持つ(§11.2)。§12 が使う `diagnosticGeneration` とは**別の属性**である
- **LS 停止**: `active` は落とさない。`epoch` を進め(これが §14.6 の接続 epoch でもある)、`CommandWaiters` を **§13.4 の手順で**破棄し、`latest` を破棄し、**「`diagnosticEpoch != 現在の epoch` の marker を削除する」Job** を schedule する

#### 停止フックの結線点は 2 つある(Codex round5-P2 により追加)

`onServerStopped()` を `stopLocked()` にだけ繋ぐと、**LS がクラッシュした場合や自発的に終了した場合にフックを通らない**。現行の `onExit()` コールバック(`GitLabLanguageServerProcessProvider.kt:123-133`)は `process` と `processListener` を null にするだけで、epoch も marker も `CommandWaiters` の待機も残る。F8(LS が停止したらその LS の検出結果は消える)を満たせず、§14.6 の接続 epoch も旧クライアントを失効させられない。

**確定仕様: `stopLocked()` と、プロセス同一性を確認した `onExit()` の両方から `onServerStopped()` を呼ぶ。**

```kotlin
startedProcess.onExit().thenApply {
  synchronized(lifecycleLock) {
    if (process === startedProcess) {     // 既存の同一性ガード
      ...
      onServerStopped()                   // ★ 追加
    }
  }
}
```

`onServerStopped()` は**冪等**に実装する(既に同じ接続について実行済みなら何もしない)。`stop()` 経由と `onExit()` 経由で二重に呼ばれても、epoch が二重に進むことも通知が二重に出ることもない。判定には「この接続の epoch について既に停止処理を実行したか」を用いる。
- **LS 起動(`startLocked()` 成功後)**: 追加の activate は不要。epoch は停止時に既に進んでおり、新 LS の診断はその新 epoch で適用される
- **バンドル停止**: `active = false` の後、**epoch を問わず全削除**する Job を schedule する

選択削除にすることで、停止時に schedule された掃除 Job が遅れて実行されても、**新 epoch で作られた marker は削除対象に入らない**。これが順序保証の代わりとなり、Job の実行順に依存しない正しさが得られる。

なお `active` を LS 停止で落とさないのは意図的である。落とすと、再起動完了後に誰かが戻さねばならず、「戻す前に届いた診断が捨てられる」窓と「戻した後に古い削除 Job が走る」窓の両方を新たに作ってしまう。epoch 単調増加だけで扱うほうが状態が少ない。

**§14.2 と §14.2.1 の論証が本設計における最重要の不変条件であり、レビューの主眼としたい。**

### 14.3 世代とデータ構造

`nextGeneration` は lsp4j の単一リスナースレッドから呼ばれるため実質的に直列だが、将来 lsp4j にエグゼキュータが設定される可能性を考慮し、`AtomicLong` + `ConcurrentHashMap` で実装する(UI スレッド閉じ込めを前提にした既存レジストリとの明確な差異)。

`latest` マップは URI ごとに 1 エントリ増える。epoch bump 時にクリアするため、上限はセッション中にスキャンしたファイル数となる。

### 14.4 既知の順序リスク(受容)

`documentChanged` → `didChange` は共有 `CoroutineScope`(`Dispatchers.IO`、複数スレッド)で送信されるため、直前の打鍵の `didChange` とスキャン通知の到着順は保証されない。最悪、**最後の数打鍵が反映されない内容でスキャンされる**。

これは既存の同型課題(#16「`sendConfiguration()` の連続呼び出しに順序保証がなく、古い設定が後着しうる」)と同根であり、根治するには LS への通知全体を単一ディスパッチャへ直列化する必要がある。本フェーズの範囲を超えるため**既知の制限として受容し、PR 本文と台帳に明記する**。影響は「1 回古い内容でスキャンされる」に留まり、再保存で解消する。

### 14.5 ワークスペースロックの競合

`MrBranchCheckoutService.kt:200` の `refreshLocal(IResource.DEPTH_INFINITE, null)` と適用 Job が競合しうる。スケジューリングルールにより正しさは保たれるが、**待ちは発生する**。ブランチ切替中にスキャン結果の反映が遅れることがある。

### 14.6 接続 epoch によるコールバックの隔離(Codex round4-P1 により追加)

round3 では「LS 停止で旧応答は**原理的に到達不能**になる」と書いたが、**この主張は強すぎた**。実際の `stopLocked()`(`GitLabLanguageServerProcessProvider.kt:187-202`)は

```kotlin
processListener?.cancel(true)
processListener = null
...
process?.destroy()
```

と、**listener の終了を待たない**。したがって、既に dispatch 済みの旧クライアントのコールバックは `onServerStopped()` の後にも走りうる。その間に新 LS の要求 B が登録されていれば、旧 A のコールバックが B を完了させて source を取り違え、旧 `publishDiagnostics` が新しい epoch を捕捉する経路も残る。

**確定仕様: `GitLabLanguageServerClient` に接続 epoch を持たせ、全コールバックの入口で照合する。**

- `GitLabLanguageServerClient` は `Launcher.Builder.setLocalService(...)` で**起動ごとに新規構築される**(同 `:140`)。構築時に `DiagnosticGenerationRegistry.currentEpoch` を捕捉して保持する
- `onServerStopped()` は `stopLocked()` の中で epoch を進める。`restart()` は `stopLocked()` → `startLocked()` の順なので、**新しいクライアントは必ず新しい epoch を捕捉する**
- `publishDiagnostics` と `securityScanResponse` は、**自分の `capturedEpoch` をすべてのレジストリ操作に引数として渡す**

新しいカウンタは導入せず、既存のライフサイクル epoch を再利用する。これは既存の「プロセス同一性ガード」(`if (process === startedProcess)`、同 `:123-133`)と同じ発想を、クライアントのコールバック側に適用したものである。

**この照合は層 1 のコールバック入口に置く**ため、セキュリティ機能だけでなく将来の診断すべてが保護される。

#### 照合と状態変更は原子的でなければならない(Codex round5-P1)

入口で 1 回照合するだけでは不十分である。照合を通過した直後に `onServerStopped()` が epoch を進めると、旧 `securityScanResponse` が新しく登録された要求 B を `complete` でき、旧 `publishDiagnostics` も停止後の状態を新接続の診断として更新できてしまう。**照合から状態変更までが原子的でなければならない。**

**確定仕様: 両レジストリの状態変更をすべて `connectionEpoch` 付きの操作にし、単一のモニタの下で「照合してから実行」する。**

- `DiagnosticGenerationRegistry` と `CommandWaiters` は、**同一のロックオブジェクト**(`LanguageServerLifecycleLock`。Koin の `single`)を共有する
- 状態を変更する全操作は `connectionEpoch: Long` を受け取り、そのロックの下で `epoch == currentEpoch` を確認してから実行する。不一致なら何もせず false を返す
- **`onServerStopped()` も同じロックを取る**。したがって「照合 → 実行」と「epoch の更新 → 状態の破棄」が交錯することはない

対象となる操作: `acceptToken` / `nextGeneration` / `shouldApply` / `suspendSource` / `resumeSource`(`DiagnosticGenerationRegistry`)と `add` / `consumeOldest` / `consumeById` / `clear`(`CommandWaiters`)。**後者も `connectionEpoch` を受け取り、同じロックの下で照合してから実行する**(Codex round13-P1)。

このロックは**メモリ上の小さな状態遷移だけ**を保護する(marker への I/O やワークスペースロックの取得は保護区間の外に置く)。したがって保持時間は短く、`onServerStopped()` が `lifecycleLock` を保持している間にこのロックを取っても、逆順のロック取得経路が存在しないためデッドロックしない。**ロック順序は「`lifecycleLock` → `LanguageServerLifecycleLock`」の一方向に固定する**ことを実装上の不変条件とする。

---

## 15. 認証と認可

- スキャンの REST 呼び出しは **LS が行う**。トークンは既存の `didChangeConfiguration` 経路で LS へ渡されており、本機能で新たにトークンを扱う箇所は無い。
- クライアント側では「トークンが設定されているか」だけを判定して早期中止する(ユーザー体験のため。認可の実体ではない)。
### 15.1 送信先インスタンスの一致(Codex round1-P1 により未決 U-6 を確定)

当初は「クライアントが GitLab へ直接リクエストを送らないので接続固定の対象が無い」と整理し、設定変更との競合を未決のままにしていた。**これは実装へ進められる状態ではなかった。**

具体的な欠陥は次のとおり。`GitLabPreferencePage.performOk()` が呼ぶ `sendConfiguration()` と、本機能のスキャン通知は、**いずれも共有 `CoroutineScope`(`Dispatchers.IO`、複数スレッド)へ投入される**。したがってインスタンス URL やトークンを変更した直後にスキャンを実行すると、スキャン通知が設定変更通知を追い越して LS に届き、**ファイル全文が変更前の GitLab インスタンスへ送信されうる**。逆順であればゲートで黙って破棄され、F6 に反する。発生確率は低いが、影響は「利用者のソースコードが意図しない宛先へ渡る」ことであり、Phase 4 / 5A で確立した基準(**severity は発生確率ではなく影響範囲で決める**)では P1 に相当する。

**確定仕様: スキャン通知の直前に、同一コルーチン内で設定を同期的に再送する。**

```
[スキャン送信コルーチン]
  0. LanguageServerOutboundLock(Mutex)を取得          <- ここが Codex round2-P1 の反映点
  1. ConnectionConfigGeneration の seqlock 下で設定値を読む(既存 captureConnection と同じ手順)
     - 世代が奇数(更新中) / 読み取り前後で世代が変化 -> 規定回数リトライ後、送信を中止して
       COMMAND なら「設定の更新中です。もう一度実行してください」を通知
  2. server.didChangeConfiguration(その設定)      <- 同じコルーチン、同じ server プロキシ
  3. server.runSecurityScan(params)                <- 直後に同じコルーチンで送信
  4. Mutex を解放
```

**同一プロキシへの書き込み直列化だけでは不十分である(Codex round2-P1 により是正)。**round1 の反映では「lsp4j が同一プロキシへの書き込みを直列化するので順序が確定する」と論じたが、これは**個々の通知に全順序を与えるだけで、2 通を不可分にはしない**。旧設定 B を載せた `sendConfiguration()` が別の IO コルーチンに残っていると、手順 2 の直後・手順 3 の直前に B が割り込み、スキャンが B の設定で実行されうる。B が旧 URL・旧トークンであれば、**意図しないインスタンスへの全文送信**が残る。§14.4 で既知としていた「設定通知の後着」がそのまま効いてしまう。

**確定仕様: 共有 `Mutex` で「設定送信」全体と「設定 + スキャンの組」を相互排他にする。**

- 新規に `LanguageServerOutboundLock`(`kotlinx.coroutines.sync.Mutex` の単一インスタンス。Koin の `single`)を導入する
- `GitLabLanguageServerConfigurationService.sendConfiguration(server)` の `coroutineScope.launch { ... }` の本体を、この `Mutex` の中で実行するよう変更する
- 本機能のスキャン送信は、手順 1〜3 全体を同じ `Mutex` の中で実行する

これにより、他の設定送信が手順 2 と 3 の間へ割り込むことは不可能になる。既存ファイルへの変更は `sendConfiguration` の launch 本体を `mutex.withLock { }` で包む**追加のみ**で、送信内容は変わらない。

**副次効果**: 既存の follow-up #16(「`sendConfiguration()` の連続呼び出しに順序保証がなく、古い設定が後着しうる」)も、設定送信同士が直列化されることで**併せて解消される**。本 PR の範囲としては本機能の安全性確保が目的であり、#16 の完全な解消(送信内容の世代管理まで含む)は主張しないが、PR 本文で関係を明記する。

- `server` は**呼び出し時点で捕捉したプロキシ**を最後まで使う(既存の `sendConfiguration(server)` / `sendOpenTabs(server)` と同じ規律。再起動で新プロキシに差し替わっても、この送信は旧サーバに留まって無害に終わる)
- 追加の `didChangeConfiguration` は LS 側で `onConfigChange` を呼ぶだけで冪等であり、同じ内容が二重に届いても副作用は無い
- seqlock の読み取りは既存の `ConnectionConfigGeneration` をそのまま使う。**新機構は導入しない**

**受け入れ条件**: 「`didChangeConfiguration` が `runSecurityScan` より前に、同一 mock サーバ上で呼ばれること」を headless テストで順序検証する(§21.1)。
- スキャンは**ファイル全文を GitLab インスタンスへ送信する**。この事実を設定の説明文に明記し、既定を無効とすることで明示的なオプトインを要求する(F7)。

---

## 16. ログ・監視・監査

### 16.1 出力してよいもの / いけないもの

| 種別 | Error Log | ユーザー通知 |
|---|---|---|
| 監査行(下記) | 出す | — |
| 応答の `error` 本文 | **出さない** | **出さない**(§11.4 の固定文言へ置換) |
| 応答の `status`(整数) | 出す | 出す(固定文言に埋め込む) |
| 診断本文(脆弱性の名称・説明) | **出さない** | — |
| ファイルの絶対パス | **出さない**(ワークスペース相対に変換) | — |
| トークン・レスポンス本文 | **出さない** | 出さない |

**`error` 本文はどこにも出さない**(Codex round2-P1 により round1 の方針をさらに是正)。round1 の反映で §11.4 に固定文言 allowlist を導入したにもかかわらず、この表と直後の説明には「Error Log には出さないがユーザー画面には出す」という round1 以前の分離が残っており、§11.4 と正面から矛盾していた。実装者がこの表に従うと資格情報や URL が再び画面へ露出する。

理由: LS 側は固定文言だけでなく**例外の `message` をそのまま載せる経路**を持つ(`e instanceof Error && (o = e.message)`)。Phase 4 PR-4 で `Bearer <token>` が例外メッセージ経由で Error Log へ漏れた事例がある。画面・スクリーンショット・画面共有への露出は Error Log への出力と同等の秘匿事故であるため、**Error Log と画面のどちらにも渡さない**。表示は §11.4 の `status` から引く固定文言のみとする。

現行の `logger.info("publishDiagnostics: $diagnostic")` は診断本文とファイル URI を丸ごと出力しているため、**削除する**。

### 16.2 監査行の形式

```
securityScan source=command|save outcome=success|failure|timeout|cancelled httpStatus=<int|-> findings=<int|-> exceptionType=<簡易クラス名|-> path=<workspace-relative>
```

`outcome` の値(single-flight 撤廃に伴い `late` と `not_started` は廃止した):

| outcome | 意味 |
|---|---|
| `success` | `status == 200` の応答を受領 |
| `failure` | `status != 200` の応答を受領 |
| `timeout` | 応答期限(§13.2)を超過 |
| `cancelled` | **LS 停止**(§13.4)**または設定の無効化**(§17.1)により `COMMAND` の待機を破棄した。**LS 停止では通知を伴い、設定無効化では監査のみ**である点が異なる |

トークン・本文・診断内容を含まない。既存の `writeAuditMessage`(`WriteAction.kt:80-104`)/ `discussionAuditMessage`(`DiscussionWriteFlow.kt:90-116`)と同じ規律に従う。ログ呼び出し自体も `runCatching` で包み、ログの失敗が処理を壊さないようにする(Phase 5A の教訓)。

---

## 17. 障害時の復旧方法

| 症状 | 復旧 |
|---|---|
| Problems ビューに古い検出結果が残る | LS を再起動(既存コマンド `gl.restartLanguageServer`)。§14.2.1 の選択削除で旧 epoch の marker が消える |
| marker が表示されない | (1) 設定が有効か、(2) 対象ファイルがワークスペース内でエディタに開かれているか、(3) Error Log の監査行で `outcome` を確認(`timeout` なら LS がドキュメントを保持していない可能性) |
| スキャンが常に失敗する | 監査行の `httpStatus` で切り分け(401 = 認証、403 = プロジェクト/ネームスペース、404 = インスタンスバージョン、その他 = 不明) |
| 通知が出続ける | 設定を無効化する。§17.1 のとおり以後の送信は止まり、既存 marker も即座に消える |

### 17.1 設定を無効化したときの失効(Codex round1-P1 により未決 U-7 を確定)

当初は「無効化しても既存 marker は残る」を仕様としていたが、これは 2 つの点で不十分だった。

1. 停止した機能の結果が、現役の問題として Problems ビューに残り続ける
2. **より重い問題**: 無効化の直前に LS 側のゲートを通過したスキャンが、無効化の**後**に `publishDiagnostics` を送ってくる。単に無効化時へ削除 Job を足すだけでは、その遅延診断が削除の後で**新しい marker を作ってしまう**

**確定仕様: 無効化を epoch の境界として扱う。**

**round2 で 2 点を是正した。**(a) 手順の順序が誤っていた(epoch を進めてから止めていたため、その間に到着した遅延診断が新 epoch で採番されて掃除から保護される)。(b) 停止をグローバルにしていたため、**セキュリティスキャンを一度無効化すると、それ以外の診断も以後すべて破棄される**という、§1.2 の汎用層要件に反する副作用があった。

**確定仕様: 停止と失効を `source` 単位にスコープし、「止めてから消す」順に行う。**

設定が有効 → 無効へ**遷移した**とき(値が同じなら何もしない)、次を**この順に**行う。

1. **`DiagnosticGenerationRegistry.suspendSource("gitlab_security_scan")`** — この `source` の**失効世代(`sourceEpoch`)を奇数へ進め**、以後この `source` の診断は受理時に除外される。**必ず最初に行う**
2. `CommandWaiters.clear(epoch)` と `SAVE` 失敗抑制の状態(§11.3)を破棄する。**除去した待機は通知しない**(監査行に `outcome=cancelled` のみ残す)

   **通知しない理由(Codex round13-P2)**: §11.3 に存在する破棄時の文言は「language server restarted」の 1 種類だけであり、LS を再起動していない利用者に**虚偽の再起動通知**を出すことになる。加えて、この破棄は**利用者自身が設定を無効化した直接の結果**であり、無効化したのにスキャン結果を待っていると考えるのは不自然である。専用文言を増やすより通知しないほうが正確で、面積も小さい。
3. `deleteMarkersBySource("gitlab_security_scan", watermark)` の Job を schedule する。`watermark` は**この時点の generation カウンタ値**

手順 1 が手順 3 より先にあるため、掃除の後に到着した遅延診断は受理時点で除外され、**marker を復活させられない**。ライフサイクル epoch は**進めない**(進める必要がない。無効化は LS の生死とは無関係であり、他 source の in-flight な適用処理を巻き込む理由がない)。

**送信済みの要求について。**無効化の時点で既に送信済みのスキャンは、ワイヤ上に残り、後から応答と診断を返しうる。本設計はこれを次のように扱う。

- **診断**: 手順 1 の `suspendSource` により受理時点で除外されるので、**marker は作られない**(手順 1 が手順 3 より先にあるため、掃除の後に届いても復活させられない)
- **応答**: `CommandWaiters` から既に除去されているので `SAVE` 扱いとなり、成功なら通知されない。失敗なら抑制状態も破棄済みのため 1 回だけ通知されうるが、実害は無い

単一飛行を採らない(§9.1.1)ため、「送信済み要求を保持して次の要求を待たせる」必要はない。再有効化後の新しいスキャンは即座に送信され、その結果が最後に届けば正しく表示される。**古い応答が後着した場合の逆転は L1 として受容する。**

再び有効化されたときは `resumeSource("gitlab_security_scan")` を呼ぶ(失効世代をさらに進める)。

### 17.1.1 予約済み Job と古い削除 Job(Codex round3 により追加)

停止を「今後のフィルタ」だけで実装すると、次の 2 つの経路が残る。round2 の反映ではどちらも塞げていなかった。

**(a) 停止前に受理済みの適用 Job が、停止後に marker を復活させる。**
§9.2 では「source の確認」と「Job の実行」の間に時間差がある。停止直前に受理された診断の Job は、`shouldApply` が generation と epoch しか見ず、ここでは epoch を進めないため通過し、手順 3 の削除 Job より後に走れば security marker を再生成する。

→ **`sourceEpoch` を受理時に捕捉し、適用時に再検査する**(§9.2 の `tokens` / `final`)。世代が進んだ `source` の診断は適用直前に落とされる。

**捕捉は「停止判定」と原子的でなければならない(Codex round4-P1)。**round3 の反映では `isSuspended(source)` で判定した**後**に `sourceEpochOf(source)` を読む二段構えにしていたが、その隙間に別スレッドの `suspendSource` が入ると、停止前に届いた診断が**更新後の世代を捕捉**してしまう。その Job は適用時の照合にも成功して marker を復活させ、直後に再有効化された場合は watermark より新しい generation を得て古い削除 Job からも保護される。

**確定仕様: `sourceEpoch` に偶奇を持たせ、単一の読み取りで両方を判定する。**

| 値 | 意味 |
|---|---|
| **偶数** | 稼働中。その値が `SourceToken` になる |
| **奇数** | 停止中。`acceptToken` は `null` を返す |

`acceptToken` は**1 回の volatile 読み**で「停止していないこと」と「その時点の世代」を同時に得るため、隙間が存在しない。

**遷移は冪等でなければならない(Codex round5-P2)。**`incrementAndGet()` を無条件に行うと、`suspendSource` が二度続けば 偶 → 奇 → 偶 となり、**二度目の停止要求が source を再有効化してしまう**。`resumeSource` の連続も同様に停止状態を作る。設定ページの重複適用や並行呼び出しで実際に起こりうる。

したがって**現在の偶奇を確認する CAS ループ**で、`suspendSource` は「偶数のときだけ +1(奇数へ)」、`resumeSource` は「奇数のときだけ +1(偶数へ)」を行う。既に目的の状態なら**何もしない**。連続呼び出し・並行呼び出しのいずれでも状態は反転しない。

これは既存の `ConnectionConfigGeneration`(`ConnectionConfigGeneration.kt:13-18`。`beginUpdate()` で奇数・`endUpdate()` で偶数)と同じ偶奇 seqlock の型であり、本リポジトリで実績のあるパターンをそのまま使う。

**(b) 古い削除 Job が、再有効化後に作られた marker を消す。**
無効化直後に再有効化されると、非同期の削除 Job が待機したまま `resumeSource` と新しい診断の適用が進みうる。§14.2.1 自身が認めているとおり Job の実行順は FIFO ではないため、古い削除 Job が後から走ると、**再有効化後に作られた同じ `source` の marker まで削除する**。

→ **削除に generation の watermark を持たせる**。`deleteMarkersBySource(source, watermark)` は
`diagnosticSource == source` **かつ** `diagnosticGeneration <= watermark` の marker だけを削除する。
`diagnosticGeneration` は単一の `AtomicLong` から採番される全域単調増加値なので、削除要求より後に作られた marker は必ず `watermark` より大きく、**選択削除から自動的に除外される**。新しい属性を増やさずに済む。

この watermark 方式は §14.2.1 の epoch 選択削除と同じ発想であり、「遅れて走る掃除が新しいものを消さない」という不変条件を、`source` 軸でも成立させる。

**層の分離**(どの層が何を判定するかを明示する):

- **層 1(汎用)** は「有効/無効」というセキュリティ固有の概念を持たない。持つのは **`source` という LSP の語彙**だけである。公開 API は `suspendSource(source)` / `resumeSource(source)` / `acceptToken(source)` / `isTokenValid(token)`(`DiagnosticGenerationRegistry`)と `deleteMarkersBySource(source, watermark)`(`DiagnosticMarkerService`)で、いずれも引数の `source` を解釈しない
- **層 2(セキュリティ)** が、設定の遷移を観測し、自分の `source` 文字列(`"gitlab_security_scan"`)を渡して呼ぶ

`publishDiagnostics` のバッチに複数 `source` が混在する場合、**停止中の `source` の診断だけを除いた集合**を §12 の全置換として適用する。LSP の全置換セマンティクスと整合し、停止した `source` の marker は置換によって自然に消える。

遷移の観測点は `GitLabPreferencePage.performOk()` とする(既に `sendConfiguration()` を呼んでいる箇所)。プラグインには `IPropertyChangeListener` が一切存在しないため、リスナー機構を新設せず既存の明示的な再送経路に相乗りする。**チェックボックスの追加とは別メソッドへの追加変更**である。

---

## 18. 既存機能への影響

| 機能 | 影響 |
|---|---|
| Code Suggestions | なし。診断処理は lsp4j リスナースレッドをブロックしない(NF1) |
| Duo Chat / Agentic Chat | なし |
| サイドバー(MR / CI / Discussions) | なし。UI スレッドを使わない |
| LS の起動・再起動 | `stopLocked()` に呼び出しが 1 行増える。marker 削除は非同期のため `lifecycleLock` の保持時間は延びない |
| 設定ページ | チェックボックス 2 件が増える。`performOk()` には遷移検出の呼び出しが 1 行増えるが、既存の保存・再送の動作は変わらない |
| **設定送信(`sendConfiguration`)** | 送信内容は不変だが、`LanguageServerOutboundLock` により**送信同士が直列化される**(§15.1)。従来は共有 `Dispatchers.IO` 上で並行しており順序保証が無かった(follow-up #16)。直列化はその是正方向であり、退行にはならない |
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
| `DiagnosticGenerationRegistry` | 追い越し(古い世代が false)/ epoch 不一致で false / `active=false` で false / `onServerStopped` で epoch が進み `latest` が消え **`active` は真のまま** / `suspendSource(X)` 後に **X の診断だけが除外され Y は通る** / `resumeSource(X)` で復帰 / `generation` が全 URI を通じて一意 |
| `runSecurityScan`(SWT フリー core) | **設定 OFF で `send` が 0 回**(通知も監査も 0 回)/ トークン無しで 0 回 / `uri=null` で 0 回 / `source=save` のとき通知が 0 回 / `source=command` のとき通知が 1 回 / 正常時に `send` が 1 回で params が期待どおり / **ゲート評価順が `enabled` → `uri` → `hasToken`** |
| **送信順序(A9)** | `didChangeConfiguration` が `runSecurityScan` より前に、**同一 mock サーバ**上で呼ばれること(MockK の `verifyOrder`)/ seqlock が不安定なとき両方とも 0 回 / **`Mutex` 保持中は他の `sendConfiguration` が割り込めないこと**(§15.1・並行コルーチンで検証) |
| **期限タスクの帰属(§9.1.1 / §13.2)** | **A の応答で A の待機が消えた後に B を開始し、A の期限が後から発火しても B の待機を消費しないこと**(round14-P1 の回帰テスト) |
| **応答期限の起点(§13.2)** | **`Mutex` を 60 秒以上保持しても、送信前の `COMMAND` が「応答なし」と通知されないこと**(round14-P2 の回帰テスト)/ 期限が `runSecurityScan` の試行直後から起算されること |
| **コマンド待機(§9.1.1 / §13.2)** | `COMMAND` 送信で待機に入り応答で除去されること / `consume` が `true` を返したときだけ `COMMAND` として通知されること / **期限切れで `COMMAND` に 1 回通知され `SAVE` には 0 回**であること / 期限切れ後もそのパスへ新しいスキャンを送信できること(塞がらないこと) |
| **送信失敗(§9.1.1)** | seqlock 不安定 / `didChangeConfiguration` 例外 / `runSecurityScan` 例外のいずれでも、`COMMAND` は待機が解除され通知が 1 回・`SAVE` は 0 回 / 監査に `exceptionType` のみが載り本文が載らないこと |
| **並行スキャンの受容(§9.1.1)** | 同一パスへの 2 要求が**どちらも送信される**こと(single-flight を持たないことの確認)/ 応答が逆順でも例外や状態破壊が起きないこと |
| **LS 停止時の待機(§13.4)** | `CommandWaiters.clear(epoch)` で `COMMAND` に**待機数のぶんだけ**通知され監査が `cancelled` になること / 再送されないこと / **設定無効化では通知が 0 回で監査のみ**であること(§17.1) |
| **待機の epoch 照合(§9.1.1)** | **旧接続の応答が新接続の `COMMAND` 待機を消費しないこと**(round13-P1 の回帰テスト) |
| **同一パスの複数 COMMAND(§9.1.1)** | 応答前に `COMMAND` を 2 回実行したとき、**通知が 2 回**出ること(待機 id の列で管理されていること)(round13-P2 の回帰テスト) |
| **waiterId の一意性(§9.1.1)** | `clear` の前後で **id が再利用されないこと** / 無効化 → 再有効化を跨いでも古い期限タスクが新しい待機を消費しないこと(round15-P1 の回帰テスト) |
| **期限起動前の終了経路(§9.1.1)** | `entered=true` の後に `Mutex` 待ちでキャンセルされた場合 / 送信呼び出しが例外を投げた場合のいずれでも、**期限未起動の待機が `invokeOnCompletion` で解決され、永久に残らないこと**(round15-P2 の回帰テスト) |
| **無効化と並行する登録(§9.1.1)** | ゲート通過後・待機登録前に無効化が完了した場合、**`Mutex` 内の再確認で送信されず待機も残らず通知も 0 回**であること(round15-P2 の回帰テスト) |
| **送信コルーチン未開始(§9.1.1)** | キャンセル済み `CoroutineScope` で `launch` が本体を実行しない場合に、待機が即座に解除され `COMMAND` に送信失敗が通知されること / **60 秒後の誤った「応答なし」が出ないこと**(round13-P2 の回帰テスト) |
| **停止と予約済み Job(§17.1.1)** | **受理後・適用前に `suspendSource` された診断が適用時に落ちること**(round3-P1 の回帰テスト)/ 同じバッチの他 source は適用されること |
| **`acceptToken` の原子性(§17.1.1)** | 偶数世代で token を返し奇数で null / `suspendSource` → `resumeSource` を跨いだ token が無効になること / **判定と捕捉の間に停止が入っても停止後の世代を捕捉しないこと**(round4-P1 の回帰テスト) |
| **除外後が空のバッチ(§9.2.1)** | **非空バッチが全除外されたとき marker を 1 つも消さないこと**(round4-P2 の回帰テスト)/ **元から空のバッチは従来どおり全削除すること**(両者を区別できること) |
| **接続 epoch(§14.6)** | 旧接続の `publishDiagnostics` / `securityScanResponse` が新 epoch の状態に触れないこと(round4-P1 の回帰テスト) |
| **停止フックの冪等性(§14.2.1)** | `stopLocked()` と `onExit()` の両方から呼ばれても epoch が二重に進まず通知も二重に出ないこと / **`onExit()` だけ(クラッシュ)でも marker が消え epoch が進むこと**(round5-P2 の回帰テスト) |
| **`suspend`/`resume` の冪等性(§17.1.1)** | `suspendSource` を 2 回連続で呼んでも**再有効化されない**こと / `resumeSource` の連続でも停止しないこと / 並行呼び出しで状態が反転しないこと(round5-P2 の回帰テスト) |
| **epoch 照合の原子性(§14.6)** | 照合の直後に `onServerStopped()` が走っても、旧接続の `complete` / 適用が新しい状態に触れないこと(round5-P1 の回帰テスト。単一モニタ下での「照合してから実行」を検証) |
| **削除 watermark(§17.1.1)** | **古い `deleteMarkersBySource` が、再有効化後に作られた同 source の marker を消さないこと**(round3-P2 の回帰テスト)/ watermark 以前の marker は消えること |
| **二段階置換(§12)** | 生成途中の `CoreException` で**`genNew` の marker が 0 件になり旧 marker が残る**こと / 全件成功時にのみ `!= genNew` が消えること / 空 diagnostics で旧世代が消えること / **同一 LS セッション内の連続適用で結果が累積しないこと**(round2-P1 の回帰テスト) |
| **generation と epoch の分離(§11.2)** | `generation` が適用ごとに必ず変わること / `epoch` が LS 停止まで変わらないこと / **`epoch` が同じでも旧世代が正しく削除されること** |
| **epoch 選択削除(§14.2.1)** | 旧 epoch の掃除 Job が遅れて走っても**新 epoch の marker を消さない**こと |
| **無効化の失効(A10・§17.1)** | **`suspendSource` が掃除より先に効くこと**(掃除後に到着した診断が marker を作らない)/ 無効化で当該 source の marker のみ消えること / **他 source の診断が停止後も通常どおり適用されること**(round2-P2 の回帰テスト)/ 再有効化で復帰すること |
| 応答の分類 | `status=200` → Success と findings 件数 / `status!=200` → Failure / **待機の無い応答は `SAVE` として分類される**こと / 抑制(同一 path・同一 status の連続で 2 回目が出ない・status 変化で出る・成功が挟まると解除) |
| 固定文言(§11.4) | 401 / 403 / 404 / その他 の写像 / **`error` 本文が戻り値のどこにも現れないこと** |
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
6. 保存でスキャンが走ること / Save All では走らないこと / **File > Revert では走らないこと**
7. 401 / 404 のいずれかを意図的に起こし、固定文言の通知が出て Error Log に `error` 本文が出ないこと
8. Problems ビューの項目をダブルクリックして該当行が開くこと
9. **設定を無効化すると既存 marker が消えること**、および無効化直後に遅れて診断が届いても marker が復活しないこと
10. **インスタンス URL を変更した直後にスキャンしても、変更前のインスタンスへ送信されないこと**(LS のログまたはインスタンス側のアクセス記録で確認)
11. **エディタに開いていないファイル**に対してコマンドを実行し、60 秒後に「応答なし」の通知が出ること(タイムアウト経路)
12. 連続保存を素早く繰り返し、**最終的に Problems ビューが更新されること**を確認する。**内容が最後の保存に対応するとは限らない**(§23.1 の L1 として受容している既知の制限。応答が逆順に返れば古い結果が残りうる)。再保存で解消することも確認する

---

## 22. 受け入れ条件

| # | 条件 | 検証方法 |
|---|---|---|
| A1 | 設定を変更しなければ、本変更前と観測可能な振る舞いが一致する | `remoteSecurityScans=false` が送出されることのテスト + 実機 21.4-1 |
| A2 | 対象テストが PASS し、**失敗したテストの「集合」がベース(`develop`)と完全一致** | 下記 A2 補足 |
| A3 | 変更ファイルの detekt 指摘が 0 | `./gradlew detekt` |
| A4 | **新規依存が無い** | `build.gradle.kts` と生成 MANIFEST の diff が空であることを提示 |
| A5 | plugin.xml の 3-way id 一致と marker 型名一致を件数で報告 | §21.3 |
| A6 | 診断適用が UI スレッドを使わないこと | コードレビューでの経路確認 |
| A7 | Error Log に診断本文・`error` 本文・絶対パスが出ないこと | 単体テスト + 実機 21.4-7 |
| A8 | ディレクトリ構成・ビルドシステムの変更が無いこと | diff の提示 |
| A9 | `didChangeConfiguration` が `runSecurityScan` より**前**に同一サーバ上で呼ばれること | §21.1 の順序検証テスト |
| A10 | 設定を無効化した後、遅れて届いた**当該 source の**診断が marker を作らないこと。かつ**他 source の診断は通常どおり適用されること** | §21.1 の source 失効テスト |
| A11 | 同一 LS セッション内で同じファイルを連続スキャンしたとき、**検出結果が累積しない**こと | §21.1 の二段階置換テスト |

**A2 補足(Codex round1-P2 を縮小受理)。**指摘のとおり「失敗数 36」という条件は、新規リグレッションが 1 件増える一方で既知失敗が 1 件たまたま直る/スキップされる場合に相殺されて素通りする。ただし提案された「既知失敗をテスト ID の allowlist としてリポジトリに固定する」は、**全フェーズ共通のベースライン(#20・CLAUDE.md)を変更するリポジトリ全体の運用変更**であり、本機能の設計で単独に決めるべきものではない。

そこで本 PR では運用を変えずに検証だけを強化する。ベースと変更後の双方で `build/test-results/test/*.xml` から**失敗したテストの完全修飾名の集合**を抽出し、`diff` で**集合の一致**を確認する(件数の一致ではなく)。新規に失敗したテストと、消失した既知失敗の**どちらも不合格**として扱う。allowlist のファイル化はリポジトリ運用の課題として follow-up issue に切り出す。

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
**U-6 / U-7 / U-8 は Codex round1 の指摘を受けて確定済みとし、未決から外した。**それぞれ §15.1 / §17.1 / §8.2.1 を参照。未決のまま実装へ進めると、送信先の不一致・停止済み機能の結果表示・同意範囲外の送信という実害に直結するため、未決として残すのは不適切だった。

### 23.1 既知の制限(未決ではなく、意図して受容するもの)

| # | 制限 | 根拠 |
|---|---|---|
| L1 | **同一ファイルの並行スキャンで、古い内容の検出結果が新しい結果を上書きしうる** | §9.1.1。VSCode 版も同一の挙動。再スキャンで解消する |
| L2 | `COMMAND` 起動の通知が保存起動の応答に消費されうる | §9.1.1 |
| L3 | 送信例外の後に応答が届くと**二重通知になりうる**。(a) 同一パスに他の `COMMAND` 待機が**無い**場合は、非 200 応答のときだけ失敗通知が 2 回。(b) 他の `COMMAND` 待機が**ある**場合は、後着応答がその待機を消費して `COMMAND` と分類されるため、**200 応答でも送信失敗通知と成功通知の両方**が出る | §9.1.1 |
| L4 | 最後の数打鍵が反映されない内容でスキャンされうる | §14.4。`didChange` とスキャン通知の到着順が保証されないため(既存 #16 と同根) |
| L5 | エディタ内の範囲下線は出ず、行単位の marker のみ | §11.2。`CHAR_START` / `CHAR_END` を設定しないため |
| L6 | 切替フェーズの削除失敗時に旧世代の一部が残り新世代と混在しうる | §12.2 |
| L7 | ワークスペース外のファイル・インメモリエディタはスキャン対象外 | §9.1。`didOpen` を送っていないため LS が黙って return する |

**L1〜L3 は、single-flight 機構を採らないという設計判断(§9.1.1)の直接の帰結である。**これらを排除するには、相関 ID を持たないプロトコルの上で要求・応答の 1 対 1 対応を作る状態機械が必要になるが、それは VSCode 版が持たない不変条件であり、パリティ要件ではない。

---

## 24. 想定されるリスク

| # | リスク | 影響度 | 緩和 |
|---|---|---|---|
| K1 | **headless で検証できない領域が大きい**(URI 往復・marker 表示・保存検出・LS 実接続) | 高 | 純関数へ最大限切り出して単体テスト可能にする。残りは実機チェックリスト(§21.4)。実機検証を経ずにマージするとリグレッションの検出機会が無いことを PR に明記する |
| K2 | plugin.xml の marker 型名とKotlin 定数の不一致 | 高 | コンパイルもテストも通ってしまい実行時まで検出されない。§21.3-4 で機械的に照合する |
| K3 | 共有 `CoroutineScope`(plain `Job`)の汚染 | 高 | 送信・通知のスケジューリング呼び出しをすべて try/catch で封じ込める(Phase 5A の教訓) |
| K4 | ワークスペースロックの競合による遅延 | 中 | 正しさはスケジューリングルールで保たれる。遅延は受容し、§14.5 に明記 |
| K5 | 保存のたびにファイル全文が送信される | 中 | 既定を無効にし、設定説明文に明記する。デバウンスは VSCode パリティのため入れない |
| K8 | **§12.2 の限界**: 切替フェーズの削除中に `CoreException` が起きると旧世代の一部が残り新世代と混在する | 中 | 表示されるのはすべて実在した検出結果であり捏造ではない。次回スキャンで再構築される。再試行しても同じ理由で失敗する公算が高いため補償はしない |
| K9 | スキャンごとに `didChangeConfiguration` を 1 回追加送信する(§15.1) | 低 | LS 側は `onConfigChange` を呼ぶだけで冪等。保存のたびに 1 通増えるが、直後に送るファイル全文に比べれば無視できる |
| K6 | **同一ファイルの並行スキャンで結果が逆転しうる**(§9.1.1 の L1) | 中 | **意図的に受容する既知の制限**。VSCode 版も同一の挙動。影響は「次のスキャンまで古い内容の結果が表示される」ことに限られ、再保存・再スキャンで解消する。誤送信も秘匿漏洩も起きない |
| K10 | `COMMAND` の通知が保存由来の応答に消費されうる(§9.1.1 の L2) | 低 | 同上。パスごとの待機数だけで管理する代償(要求単位の識別子を持たないため、どの応答がどの要求のものか区別できない)。コマンドを再実行すれば解消する |
| K12 | 送信例外の後に応答が届くと二重通知になりうる(§9.1.1 の L3) | 低 | 同上。並行する `COMMAND` 待機が無ければ非 200 応答のときだけ、あれば 200 応答でも二重になる。永続的な不整合は残らない |
| K11 | `LanguageServerOutboundLock` の導入で設定送信が直列化される(§15.1) | 低 | 送信内容は不変。設定送信は本来まれで、直列化は #16 の是正方向でもある。`Mutex` 保持中に LS への書き込みがブロックしても、呼び出しは全てコルーチン内であり UI スレッドは影響を受けない |
| K7 | 既存の `publishDiagnostics` ログを削除することで、既存の運用手順が壊れる | 低 | 当該ログは no-op のデバッグ出力であり、機能として依存されていない |

---

## 25. 実装の分割方針(参考)

単一 PR を想定する。理由は、層 1 のみでは起動手段が無く実機で何も検証できず、層 2 のみでは表示先が無いため、どちらか一方では受け入れ条件 A1 以外を満たせないため。

SDD のタスク分割は実装計画(フェーズ issue #13 へのコメント)で確定する。おおよその境界は次のとおり。

1. `DiagnosticUri` / `DiagnosticMarkerAttributes` / `DiagnosticGenerationRegistry`(**generation と epoch の 2 軸**・`suspendSource`/`resumeSource` を含む。純ロジック・TDD)
2. `DiagnosticFileResolver` / `DiagnosticMarkerService`(**§12 の二段階置換** + §14.2.1 の epoch 選択削除)/ `publishDiagnostics` 実装
3. 設定 2 件 + `securityScannerOptions` + `remoteSecurityScans` の配線 + §17.1 の遷移検出
4. `SecurityScanLauncher` core(**§15.1 の設定先送り順序**を含む)+ DTO + `CommandWaiters` + `/response` ハンドラ
5. `SecurityScanStatusReporter`(§11.3 の通知表 + §11.4 の固定文言 + `SAVE` 失敗抑制 + 監査行 + §13.2 の期限)
6. `RunSecurityScanHandler` / `SecurityScanSaveListener`(**Revert 除外**)/ plugin.xml 配線
7. `GitLabEclipseStartup` / `stopLocked` / `onExit` のライフサイクル結線

**分割数の推移について。**Codex レビュー round1〜12 の反映で、当初 6 分割の見積もりが一時 10 分割相当まで膨らんだ。その増加分はほぼすべて single-flight 機構(要求トークン・昇格キュー・2 本の期限・4 値の開始状態)に由来していた。**§9.1.1 で単一飛行を採らないと決めたことにより 7 分割へ戻っている。**
