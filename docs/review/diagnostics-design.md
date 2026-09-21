# D10 Diagnostics / Output / LSP 設計書

対象 issue: #14(Phase 6)/ 台帳 #7 D10 / ロードマップ #8
ベースブランチ: `gitlab-ls-9.3.0` @ `84dad18`
参照実装: `gitlab-workflow` v6.85.3(`./out/gitlab-vscode-extension`、読み取り専用)

---

## 1. 背景と目的

台帳 #7 のドメイン **D10 Diagnostics/Output/LSP は 6 機能中 1 件のみ ✅**(`gl.restartLanguageServer`、Phase 2 PR-3 / #27)。
残る 5 件(❌2 + 🟡3)を本サイクルで解消し、**D10 を 6/6 で閉じる**。パリティは **67/85 → 72/85(約 85%)**。

副次的な目的として、開発拠点には **実機検証が未実施の PR が 6 本**(#81 / #82 / #76 / #77 / #78 / #85)積み上がっている。
本機能が提供する「サニタイズ済み診断エクスポート」は、その実機検証で異常が出たときにユーザーが状況を添付できる唯一の手段になる。

## 2. 対象範囲

| # | 台帳行 | コマンド / 設定 | 現状 | 目標 |
|---|---|---|---|---|
| F1 | 拡張ログ表示 | `gl.showOutput` | 🟡 Eclipse 標準 Error Log のみ | ✅ GitLab 専用のログ出力先を開く |
| F2 | 診断表示 | `gl.showDiagnostics` | 🟡 Verify Setup(単発)が最も近い | ✅ 診断レポートをエディタに表示 |
| F3 | サイドパネルから診断 | `gl.showDiagnosticsFromSidePanel` | ❌ 入口なし | ✅ Duo Chat ビューのツールバーから同じ診断 |
| F4 | 診断エクスポート | `gl.exportDiagnostics` | ❌ エクスポート処理なし | ✅ サニタイズ済み ZIP を保存 |
| F5 | 設定 debug | `gitlab.debug` | 🟡 4 段階ログレベル combo(「意図は等価」と記載) | ✅ §5.1 の再判定に基づき解消 |

## 3. 対象外

- **多アカウント(D1)/ Quick Chat(D4)/ Knowledge Graph(D6)/ 脆弱性詳細 webview(D9)/ ターミナル・チュートリアル(D3)。** Phase 6 の残余だが独立したサブシステムで、本サイクルとは共有する層がない。
- **LS 側のログレベル変更 UI の作り直し。** 既存の `gitlab.languageServer.logLevel` combo はそのまま残す(§5.1)。
- **GitLab インスタンスのバージョン取得のための新規 REST 往復。** 診断コマンドがネットワークを待つ設計にはしない(§9.4)。
- **Eclipse `org.eclipse.ui.console` バンドルの導入。** §6.2 で不採用とした。
- **ログの自動送信・テレメトリ連携。** エクスポートは常にユーザーが明示的に保存先を選ぶ。

## 4. 現在の課題

1. **プラグイン自身のログが GitLab 専用の入れ物を持たない。** 実装は `Platform.getLog(bundle)`(`utils/Logger.kt`)経由で Eclipse の `ILog` に出すだけで、Eclipse 標準の Error Log ビューに他バンドルのログと混ざって出る。VSCode 版は `GitLab` という専用 OutputChannel を持つ(`src/desktop/extension.ts:341`)。
2. **診断情報が一箇所に集まらない。** バージョン・設定・feature state・LS の状態は、それぞれ別の場所(設定ページ、Error Log、`language_server.log`)に散っている。
3. **ユーザーが不具合を報告する手段がない。** 実機で何かが起きたとき、何をどう集めればよいかが決まっていない。

## 5. 要件

### 5.1 F5 の再判定(**本設計で最も重要な確定事項**)

台帳 #7 の `gitlab.debug` 行は「bool ではなく 4 段階ログレベル combo(**意図は等価**)」と記載されているが、**実ソースで確認した結果これは誤りである。両者は別物であり、現状 `gitlab.debug` に相当する機能は存在しない。**

根拠(すべて実ソース):

- VSCode の `gitlab.debug` は **boolean**、既定 `false`(`out/gitlab-vscode-extension/package.json:485-490`)。
- その唯一の作用は 2 つ。
  1. `log.debug()` が出力されるかどうかのゲート — `if (config.debug) logWithLevel(LOG_LEVEL.DEBUG, a1, a2);`(`src/common/log.ts:63`)。
  2. source map の導入 — `if (e.affectsConfiguration(GITLAB_DEBUG_MODE)) installSourceMapsIfDebug();`(`src/desktop/extension.ts:332`)。
- **`GITLAB_DEBUG_MODE` の参照箇所は上記 2 箇所のみ**(`grep -rn "GITLAB_DEBUG_MODE" src --include=*.ts`)。**言語サーバへは送られない。**

一方 Eclipse 側の `gitlab.languageServer.logLevel` は **LS へ送る値**である(`GitLabLanguageServerConfigurationService.kt:106` が `logLevel = preferenceStore.getString(LANGUAGE_SERVER_LOG_LEVEL)` として設定ペイロードに載せる)。

つまり:

| | 制御対象 | LS へ送るか |
|---|---|---|
| VSCode `gitlab.debug` | **拡張自身**のデバッグログ | **送らない** |
| Eclipse `gitlab.languageServer.logLevel` | **言語サーバ**のログレベル | **送る** |

**結論**: 両者は対応しない。`gitlab.debug` の正しい Eclipse 等価物は「**プラグイン自身の** debug ログを出すか」であり、これは現状存在しない。
したがって F5 は「等価性の精査」ではなく **新規の boolean 設定 `gitlab.debug` の追加**として実装する。既存の `logLevel` combo は LS 用として残す(役割が違うため共存が正しい)。

#### 5.1.1 debug ログ経路の設計(**Codex レビュー P1 反映**)

**指摘**: §18 が「`utils/Logger.kt` と全ログ呼び出しを変更しない」としているため、boolean 設定を足しても R7 のゲートは成立しない。
実際、**現リポジトリにプラグインログの `.debug()` 呼び出しは 1 件も存在せず**、`logger<T>()` は素の Eclipse `ILog` を返すだけで debug レベルを持たない(`ILog` の severity は `IStatus` の OK/INFO/WARNING/ERROR/CANCEL であり DEBUG がない)。設定を on にしても出力は 1 行も増えないため、F5 を完了扱いにできない。

**反映**: 以下を設計に追加する。

1. **debug ロガー API を新設する。** `utils/Logger.kt` に `ILog.debug(message: String)` の拡張関数を足す。
   **既存の `logger<T>()` と既存の全ログ呼び出しは変更しない**(§18 の主旨は維持される)。
2. **ゲートの評価タイミング**は**呼び出しごと**。設定変更が次の 1 行から効き、再起動を要しない
   (VSCode は `settings.json` を読み直す実装で、同じく即時)。
3. **`IStatus` に DEBUG がない**ため、配送は `IStatus.INFO` に `[debug]` 接頭辞を付けて行う。
   これは表現上の妥協であり、**設定 off のときは `ILog` を一切呼ばない**(= 配送そのものが起きない)点が本質。
4. **初期の呼び出し箇所**: 本サイクルで新設する診断機能自身の内部イベント
   (スナップショット収集の開始・終了、エクスポートの各段階、リングバッファの回り込み)に限定して入れる。
   既存機能への `.debug()` 追加は**本サイクルのスコープ外**(§3)とし、API と実証可能な経路を用意することを完了条件とする。
5. **受け入れ条件 A13 を追加**(§21): 同一の debug メッセージが、設定 on では `ILog` へ配送され、off では配送されないこと。

> **未決事項 U1**: source map 相当(スタックトレース解決)は Kotlin/JVM には該当する概念がないため移植対象外とする。この判断でよいか。

### 5.2 機能要件

| ID | 要件 |
|---|---|
| R1 | プラグイン自身のログ行を、直近 **5000 行**までメモリ上に保持する(VSCode `LogCollector` と同じ上限。`src/common/diagnostics/log_collector.ts:6`)。 |
| R2 | `gl.showOutput` 相当のコマンドで、保持しているログをエディタに表示できる。 |
| R3 | `gl.showDiagnostics` 相当のコマンドで、診断レポート(Markdown)をエディタに表示できる。 |
| R4 | 同じ診断レポートを **Duo Chat ビューのツールバー**からも開ける(F3)。VSCode では `SHOW_DIAGNOSTICS` と `SHOW_DIAGNOSTICS_FROM_SIDE_PANEL` が**同一のコマンド関数**に束縛されている(`src/common/main.ts:49,53`)。**Eclipse でも同一ハンドラを 2 つのコマンド ID に割り当てる。** |
| R5 | `gl.exportDiagnostics` 相当のコマンドで、**ZIP** を保存できる。中身は VSCode と同じ 3 エントリ: `diagnostics.md` / `extension-logs.txt` / `language-server-logs.txt`(`src/desktop/diagnostics/export_diagnostics_command.ts`)。 |
| R6 | **エクスポートおよび表示の全内容は、書き出し前にサニタイズする。**(§10) |
| R7 | `gitlab.debug`(boolean、既定 false)を追加し、プラグイン自身の debug ログのゲートとする。 |
| R8 | 診断レポートには、バージョン / 設定 / feature state / 言語サーバ状態を含める(§8.3)。 |

### 5.3 非機能要件

| ID | 要件 |
|---|---|
| N1 | 診断コマンドは**ネットワーク往復を行わない**。すでに手元にある状態だけから組み立てる。 |
| N2 | ログ収集は**有界かつ極小の同期のみ**を用い、ログ呼び出し元を実質的にブロックしない。臨界区間は**配列への 1 代入**に限り、整形・連結・コピーは臨界区間の外で行う。 |
| N3 | ログ収集の失敗が、元のログ出力を失わせてはならない。 |
| N4 | **トークン・パスワード・認証情報が、診断レポート・エクスポート・ログのいずれにも平文で出てはならない。** |
| N5 | エクスポートは **UI スレッドを占有しない**。収集・サニタイズ・圧縮・書き込みはキャンセル可能なバックグラウンド Job で行う。 |
| N6 | エクスポート先への書き込みは**アトミック**とする。失敗・中断で部分ファイルを残さない。 |

> **Codex レビュー反映(P1)**: N2 の旧文言は「ロックを取らず」だったが、§13 が `synchronized` を採る方針と矛盾していた。
> ロックフリーのリングバッファは、本プロジェクトで唯一の安全網がレビューである以上リスクが見合わない。
> **要件側を実態に合わせて訂正**し、「有界かつ極小の同期」を許容する。許容範囲は A12 で受け入れ条件にする。

## 6. 前提条件と制約

### 6.1 共通制約(#8 より)

- ディレクトリ構成の変更禁止。新規コードは `src/main/kotlin/com/gitlab/eclipse/` 配下の既存パッケージ体系に追加する。
- ビルドシステムの変更禁止。`build.gradle.kts` / `gradle.properties` / `detekt.yml` は変更しない。
- ドキュメントはコミットしない(本設計書はレビュー専用ブランチのみ)。

### 6.2 新規バンドル依存を導入しない(確定)

VSCode の OutputChannel の Eclipse における最も近い対応物は `org.eclipse.ui.console` の `MessageConsole` である。しかし:

- `org.eclipse.ui.console` は **現在の `eclipseDependencies` に含まれていない**(`build.gradle.kts:165-195` を確認)。
- 共通制約が「必須依存の追加は PR で明示」としており、導入には相応の正当化が要る。

**採用しない。** 代わりに、**プラグイン状態ディレクトリ配下のファイルへ書き出してエディタで開く**方式を採る。理由:

1. **既存の `language_server.log` と完全に対称**になる(`log4j2.xml` が `${sys:gitlab.plugin.state.dir}/language_server.log` へ書く)。
2. **「生成した内容をエディタで開く」既存パターンがすでに 2 つある** — `JobLogEditorOpener` と `MergedYamlEditorOpener`(ともに `GitLabEclipseStartup` から参照)。
3. 新規バンドル依存がゼロで済む。
4. エクスポート(F4)が同じファイルを読むだけで済み、経路が一本化される。

> **未決事項 U2**: 状態ディレクトリへ書き出す方式では、Eclipse の Error Log ビューとの二重表示になる(同じ内容が両方に出る)。VSCode も拡張ログは OutputChannel だけなので二重ではない。許容するか、`ILog` への出力を止めるか。**本設計では許容とする**(Error Log を止めると他バンドル由来の相関が追えなくなるため)。

### 6.3 検証上の制約

- devcontainer は headless。**SWT ウィジェット(`FileDialog` 等)を生成する経路はテストで実行できない。** したがって UI に触れる箇所はすべて注入シームの背後に置き、純ロジック側を完全にテスト可能にする(既存 `ClipboardWriter` / `NotificationUtils` と同じ方針)。
- `./gradlew build` は **36 件失敗で BUILD FAILED が正常**。合否は失敗集合で判定する(`verify.sh` が `FAILSET_IDENTICAL` を出せば合格)。
- detekt は `detektMain` / `detektTest` を**明示実行**して判定する。ベースラインは **main 17 / test 45**。

## 7. システム構成

```
                       ┌──────────────────────────┐
   Platform.getLog ───▶│  PluginLogTap            │  ILogListener
     (既存の全ログ)     │  (IStatus → 1 行に整形)   │
                       └───────────┬──────────────┘
                                   ▼
                       ┌──────────────────────────┐
                       │  LogRingBuffer           │  純ロジック・5000 行上限
                       └───────────┬──────────────┘
                                   │
     ┌─────────────────────────────┼──────────────────────────────┐
     ▼                             ▼                              ▼
┌──────────────┐        ┌─────────────────────┐        ┌────────────────────┐
│ShowOutput    │        │ DiagnosticsSnapshot │        │ DiagnosticsArchive │
│Handler (F1)  │        │ Collector           │        │ (ZIP 組み立て)      │
└──────────────┘        └──────────┬──────────┘        └─────────┬──────────┘
                                   ▼                             │
                        ┌─────────────────────┐                  │
                        │ DiagnosticsReport   │  純ロジック        │
                        │ (Markdown 生成)      │──────────────────┤
                        └──────────┬──────────┘                  │
                                   ▼                             ▼
                        ┌──────────────────────────────────────────────┐
                        │ DiagnosticsSanitizer(純ロジック・§10)         │
                        └──────────────────────┬───────────────────────┘
                                   ┌───────────┴───────────┐
                                   ▼                       ▼
                        ┌────────────────────┐  ┌────────────────────┐
                        │ShowDiagnostics     │  │ExportDiagnostics   │
                        │Handler (F2 / F3)   │  │Handler (F4)        │
                        └────────────────────┘  └────────────────────┘
```

## 8. コンポーネントの責務

### 8.1 純ロジック(headless で完全にテスト可能)

| コンポーネント | 責務 | 根拠となる参照実装 |
|---|---|---|
| `LogRingBuffer` | ログ行をリングバッファに保持。`append` / `getAll` / `clear` / `lineCount`。上限 5000 行、超過時は最古を上書き。 | `src/common/diagnostics/log_collector.ts` の忠実移植 |
| `DiagnosticsSanitizer` | 文字列からトークン・パスワード・認証情報・ユーザ名付きパスを除去。 | `src/common/diagnostics/sanitizer.ts` の忠実移植(§10) |
| `DiagnosticsSnapshot` | 診断に載せる値のデータ保持のみ(振る舞いなし)。 | — |
| `DiagnosticsReport` | `DiagnosticsSnapshot` → Markdown 文字列。`# GitLab for Eclipse Diagnostics` + `## <title>` セクションを `\n\n` 連結。 | `generateDiagnosticsMarkdown`(`diagnostics_document_provider.ts`) |
| `FeatureStateSection` | `List<FeatureStateChangeCheck>` → チェックボックス行。`- [x] <label> (true)` 形式、`engaged` の真偽を反転して表示。 | `feature_state_diagnostics_renderer.ts` の `checkEnabledMapper` |
| `DiagnosticsArchive` | 名前付きエントリの列 → ZIP のバイト列(`java.util.zip.ZipOutputStream`)。 | `zip_creator.ts`(VSCode は `archiver`、こちらは JDK 標準で足りる) |
| `DiagnosticsFileNaming` | `Instant` → `gitlab-diagnostics-<timestamp>.zip`。 | `export_diagnostics_command.ts` の `defaultFilename` |

### 8.2 プラットフォーム接触(シームの背後)

| コンポーネント | 責務 | 危険な点 |
|---|---|---|
| `PluginLogTap` | `ILogListener` 実装。`Platform.getLog(bundle).addLogListener` で登録し、`IStatus` を 1 行へ整形して `LogRingBuffer` へ。 | **再入**(§13)。**自身の失敗がログを壊してはならない**(N3) |
| `DiagnosticsSnapshotCollector` | 設定ストア・各 State サービス・バンドル・LS から値を集める。 | Koin / ワークベンチが未起動の可能性 |
| `DiagnosticsDocumentWriter` | 文字列を状態ディレクトリ配下のファイルへ書き、エディタで開く。 | UI スレッド。既存 `openInActiveEditor` を使う |
| `DiagnosticsSaveLocationPrompt` | `FileDialog` で保存先を得る。**注入シーム**(headless 不可) | UI スレッド専用 |
| 3 ハンドラ | コマンド → 上記の組み立て | — |

### 8.3 診断レポートの内容(R8)

VSCode の 3 レンダラ(`version_diagnostics` / `feature_state_diagnostics` / `settings_state_diagnostics`)に対応させる。

```markdown
# GitLab for Eclipse Diagnostics

## Versions

- IDE: Eclipse <platform version>
- Plugin: GitLab for Eclipse (<bundle version>)
- Language Server version: <package.json の pin = 9.3.0>
- GitLab instance version: <既知なら。未取得なら "Not available">

## Configuration

- Instance URL: <gitlab.url>
- Authentication type: <gitlab.authentication.type>
- Token configured: <yes | no>            ← 値は絶対に出さない
- Language server log level: <info|...>
- Debug logging: <enabled | disabled>
- Telemetry: <enabled | disabled>
- Code Suggestions: <enabled | disabled>
- Duo Chat: <enabled | disabled>
- Security scan: <enabled | disabled>
- Ignore certificate errors: <true | false>
- CA certificate: <configured | not configured>   ← パスも出さない
- Client certificate: <configured | not configured>

## Language Server

- Status: <running | stopped>
- Log file: <state dir>/language_server.log

## GitLab Duo Code Suggestions (On|Off)

- [x] <check label> (true)
- [ ] <check label> (false)
  > <details>

## GitLab Duo Chat (On|Off)
...

## GitLab Duo Agentic Chat (On|Off)
...
```

> **未決事項 U3**: `STATE_CHECK_USER_READABLE_LABELS`(VSCode が `@gitlab-org/gitlab-lsp` から import しているチェック ID → 表示名の対応表)は Eclipse 側に存在しない。**実装前に同梱 LS バンドルから実値を抽出して計画に埋め込む**(#8 の共通制約 5)。抽出できなかった ID は `checkId` をそのまま表示にフォールバックする。

## 9. 処理フロー

### 9.1 ログ収集(常時)

1. 任意のコードが `logger<T>().info(...)` 等を呼ぶ(既存のまま。**呼び出し側は一切変更しない**)。
2. Eclipse が `ILog` へ `IStatus` を配送。
3. `PluginLogTap.logging(status, plugin)` が呼ばれる。
4. `IStatus` を `<ISO8601> [<severity>]: <message>` に整形(例外があれば後続行にスタックトレース、4 スペース字下げ = VSCode `log.ts:39` の `PADDING` に合わせる)。
5. **整形結果を改行で分割し、物理行ごとに** `LogRingBuffer.append(line)` する。

> **Codex レビュー P2 反映(行数上限の実効性)**: 旧設計は整形済み文字列を 1 回 `append` していたため、
> リングバッファが数えるのは**行数ではなく `IStatus` の件数**だった。スタックトレース 1 本が数十行になるため、
> **5000 件が容易に数万行**になり、R1 の保持上限とメモリ見積もりが両方破れる。
> A1 は単一行入力しか与えていないため、**この不整合をテストで検出できなかった**。
>
> **反映**: タップ側で改行を正規化(`\r\n` / `\r` → `\n`)したうえで**物理行ごとに `append` する**。
> これにより「5000 行」が文字どおりの意味になる。**受け入れ条件 A17 を追加**(§21):
> 数十行のスタックトレースを含む 1 件の `IStatus` が、`lineCount()` をその行数ぶん進めること。

### 9.2 F1 `gl.showOutput`

1. `LogRingBuffer.getAll()`。
2. `DiagnosticsSanitizer.sanitize()`。
3. 状態ディレクトリへ `gitlab-plugin-logs.txt` として書く(毎回上書き)。
4. `openInActiveEditor` でエディタに開く。

### 9.3 F2 / F3 `gl.showDiagnostics`

1. `DiagnosticsSnapshotCollector.collect()`。
2. `DiagnosticsReport.render(snapshot)` → Markdown。
3. `DiagnosticsSanitizer.sanitize()`。
4. 状態ディレクトリへ `GitLab Diagnostics.md` として書き、エディタで開く。

F3 は **同じハンドラクラス**を別コマンド ID に割り当てるだけ(R4)。

### 9.4 F4 `gl.exportDiagnostics`

> **Codex レビュー P2 反映(スレッド境界)**: Eclipse のコマンドハンドラは **UI スレッド**で呼ばれる。
> `language_server.log` は `log4j2.xml` の `SizeBasedTriggeringPolicy` により **最大 20MB** まで育つ。
> その全読み込み + 8〜11 本の正規表現 + メモリ内 ZIP 生成を UI スレッドで行うと**ワークベンチが固まる**。
> 「ローカル I/O だからタイムアウト不要」(§15)はこの問題を防がない。
>
> **スレッド境界を次のとおり確定する。**
> - **UI スレッド**: 保存ダイアログ(手順 1)と完了・失敗の通知(手順 5)**のみ**。
> - **バックグラウンド `org.eclipse.core.runtime.jobs.Job`**: 手順 2〜4(収集・サニタイズ・圧縮・書き込み)。
> - Job は **キャンセル可能**(`IProgressMonitor.isCanceled` を各段階の頭で確認)。
>   キャンセル時は**一時ファイルを消して何も残さない**。
> - 進捗は参照実装の 8 段階に合わせて `beginTask` / `subTask` で出す。
> - **`beginTask` は 1 monitor 1 回**(#20 の clone 系知見と同じ制約)。

1. `DiagnosticsSaveLocationPrompt` で保存先を得る(**UI スレッド**)。**キャンセルなら何もせず終了**(通知も出さない)。
   以降の手順 2〜4 は**バックグラウンド Job**(N5)。
2. 3 つの内容を組み立てる。
   - `diagnostics.md` = §9.3 の 1〜3
   - `extension-logs.txt` = §9.2 の 1〜2。空なら `No extension logs available.`
   - `language-server-logs.txt` = `language_server.log` を読んでサニタイズ。**存在しない/読めない場合は `No language server logs available.` を入れて続行**(エクスポート全体を失敗させない)
3. `DiagnosticsArchive.build(entries)` → バイト列。
4. 選ばれたパスへ**アトミックに**書く(下記 9.4.1)。
5. 成功通知(パスを含む。**UI スレッド**)。

#### 9.4.1 アトミックな書き込み(**Codex レビュー P1 反映**)

**指摘**: §13 の「同一パスなら後勝ちで完全な ZIP」は、完成した `ByteArray` を 1 回書くだけでは**保証されない**。
同じ保存先への 2 本のエクスポートが重なると truncate と write が競合し、**混在した壊れた ZIP** が残る。
ディスク枯渇やプロセス終了でも、§14 の「部分的なファイルを残さない」という主張が成り立たない。

**反映**: 次の手順で書く。

1. **保存先と同一ディレクトリ**に一意な一時ファイルを作る(`Files.createTempFile(dir, "gitlab-diagnostics", ".zip.tmp")`)。
   同一ディレクトリであることが必須 — 別ファイルシステム間では atomic move ができない。
2. 一時ファイルへ全バイトを書き、**`close()` する**。
3. `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)` で置換する。
4. **`ATOMIC_MOVE` が `AtomicMoveNotSupportedException` になる場合**(一部のネットワークドライブ)は、
   `REPLACE_EXISTING` のみの move へ縮退し、**その旨を通知に含める**(黙って弱い保証にしない)。
5. どの段階で失敗しても、**`finally` で一時ファイルを削除する**。

**同一パスの直列化は行わない。** ATOMIC_MOVE は OS が直列化するため、2 本が重なっても
「**既存のファイル**」か「**完全な新しい ZIP のどちらか一方**」しか残らない。これが §13 の主張の正しい根拠である。

**受け入れ条件 A15 を追加**(§21): 同一パスへの同時エクスポート 2 本の後、残ったファイルが**完全に読める ZIP**であること。
**A16**: 書き込み途中で失敗させたとき、**一時ファイルも部分ファイルも残らない**こと。

**順序の理由**: VSCode は ZIP を作ってから保存ダイアログを出す(`export_diagnostics_command.ts` の Step 5 → Step 6)。本設計は**先にダイアログ**にする。キャンセルが最も多い分岐であり、その場合に収集と圧縮を丸ごと無駄にしないため。振る舞いの差は「キャンセル時に何もしない」点だけで、ユーザーから見た結果は同じ。

## 10. サニタイズ(R6 / N4 — 本設計の安全上の中核)

`src/common/diagnostics/sanitizer.ts` を忠実移植する。適用順序も同じ(トークン → パスワード → URL 資格情報 → パス)。

| 対象 | パターン(参照実装のまま) | 置換後 |
|---|---|---|
| GitLab トークン | `/gl(pat\|ptt\|oas\|psc)-[a-zA-Z0-9_-]+/gi` | `[REDACTED_GITLAB_TOKEN]` |
| ヘッダトークン | `/(PRIVATE-TOKEN\|JOB-TOKEN\|X-GITLAB-TOKEN)['"\s:=]+['"]?[^\s"';,}]+['"]?/gi` | `$1: [REDACTED_TOKEN]` |
| Bearer | `/Bearer\s+[a-zA-Z0-9._-]+/gi` | `Bearer [REDACTED_TOKEN]` |
| Basic | `/Basic\s+[a-zA-Z0-9+/=]+/gi` | `Basic [REDACTED_CREDENTIALS]` |
| パスワード | `/\bpassword['"\s]*[:=]\s*(?:(['"]).*?\1\|[^\s;,}]+)/gi` | 接頭辞 + `[REDACTED_PASSWORD]` |
| URL 資格情報 | `/https?:\/\/[^:/@\s]+:[^@\s]+@[^\s"'<>]+/gi` | `[REDACTED_USERNAME]:[REDACTED_PASSWORD]@` に置換しホストとパスは保持 |
| Windows パス | `/([A-Z]:\\Users\\)[^\\\s"']+((?:\\[^\s"']*)?)/gi` | `$1[REDACTED_USER]$2` |
| Unix パス | `/\/(?:home\|Users)\/[^/\s"']+/gi` | `/home/[REDACTED_USER]` または `/Users/[REDACTED_USER]` |

**Kotlin 移植上の注意(実装時に確定させること)**:

- JS の `/gi` は Kotlin の `RegexOption.IGNORE_CASE` + `replace`(Kotlin の `replace` は既定で全置換なので `g` は不要)。
- JS の `$1` は Kotlin でも `$1` だが、**Kotlin の文字列テンプレートと衝突する**ため `"""` か `\$1` でエスケープする。
- JS の `[^\s"';,}]` の文字クラスはそのまま移せる。
- **`Regex` は必ず `companion object` の `val` に置く**(呼び出しごとのコンパイルを避ける)。

### 10.1 任意形式の認証情報(**Codex レビュー P1 反映**)

**指摘**: 上の 8 パターンは参照実装の忠実移植だが、**参照実装自体に穴がある**。次はどれにも一致しない。

- JSON 本体の `{"access_token":"..."}` / `{"refresh_token":"..."}`(既存 `GitLabAuthorizationToken` が保持する 2 値。任意の文字列形)
- クエリパラメータの `?private_token=...` / `&access_token=...`
- **self-managed GitLab の、`glpat-` 以外の接頭辞を持つトークン**

これらは **`language_server.log` に現実に載りうる**(LS は HTTP のやり取りをログする)。エクスポートは当該ログを ZIP に同梱するため、**N4 に直接違反する**。

**反映**: 次の 3 規則を §10 の表に追加する。

| 対象 | 規則 | 置換後 |
|---|---|---|
| **既知トークンの実値**(最優先・最初に適用) | 設定ストアと `GitLabAuthorizationToken` から得た**実際のトークン文字列**を、**リテラルとして**全文一致置換する | `[REDACTED_TOKEN]` |
| JSON の機密キー | `"(access_token\|refresh_token\|private_token\|token\|password\|secret)"\s*:\s*"[^"]*"` | `"<key>": "[REDACTED]"` |
| クエリの機密キー | `[?&](access_token\|private_token\|token\|password)=[^&\s"']*` | `<sep><key>=[REDACTED]` |

**既知トークンの実値置換が最も強力な規則である。** 接頭辞にも形にも依存せず、self-managed の任意形式トークンを確実に捕捉する。

実装上の注意:

- **リテラル置換であること。** トークン文字列は正規表現メタ文字を含みうるため、`Regex.escape` するか `String.replace(String, String)`(非正規表現版)を使う。
- **空文字・極端に短い値は置換に使わない**(空文字で全文が壊れる / 3 文字程度の値が本文を食い荒らす)。**8 文字未満は無視する。**
- 実値の取得に失敗しても、**残りのサニタイズは続行する**(取得失敗が全サニタイズの無効化になってはならない)。
- **サニタイザ自身がトークンを保持する**ことになるため、`SecretRedactionConventionTest` の対象になるか実装時に確認する。保持は呼び出しの引数として渡す形にし、フィールドに溜めない設計を優先する。

**受け入れ条件 A14 を追加**(§21): 既知トークンを設定した状態で、そのトークンを含む**ログ行**と**JSON 断片**をエクスポート経路に通したとき、ZIP の 3 エントリすべてに平文が現れないこと。**スナップショット由来だけでなく、ILog 由来と LS ログ由来の両方で検証する。**

**サニタイズは「最後の砦」であって唯一の防御ではない。** 収集側でもトークンは最初から載せない(§8.3 の `Token configured: yes|no`)。二重防御。

**既存の `SecretRedactionConventionTest`**(`src/test/kotlin/com/gitlab/eclipse/SecretRedactionConventionTest.kt`)は、秘匿らしきフィールドを持つ `data class` に redact する `toString()` を強制する規約テストである。**新規 `data class` がこの検出器に掛かる場合、`EXPECTED_SECRET_CLASSES` の更新が必要になる。** 本設計は `DiagnosticsSnapshot` に**トークンそのものを持たせない**ため原則掛からないが、実装時に必ず確認する。

## 11. API / インターフェース

新規の外部 API はない。**LSP の新規メソッドも追加しない**(診断は §9 のとおり手元の状態だけで組み立てる)。

コマンド ID(既存の命名規約 `com.gitlab.eclipse.commands.<Name>` に従う):

| コマンド ID | 表示名 | ハンドラ |
|---|---|---|
| `com.gitlab.eclipse.commands.ShowOutput` | Show Extension Logs | `ShowOutputHandler` |
| `com.gitlab.eclipse.commands.ShowDiagnostics` | Diagnostics | `ShowDiagnosticsHandler` |
| `com.gitlab.eclipse.commands.ShowDiagnosticsFromSidePanel` | Diagnostics | `ShowDiagnosticsHandler`(**同一クラス**) |
| `com.gitlab.eclipse.commands.ExportDiagnostics` | Export Diagnostics | `ExportDiagnosticsHandler` |

配置:

- 前 3 者 + エクスポート: GitLab メインメニュー配下(既存の配置規則に合わせる)。
- `ShowDiagnosticsFromSidePanel`: `toolbar:com.gitlab.eclipse.views.LanguageServerBrowserView`。VSCode の `view/title` + `when: view =~ /(gl.chatView|gl.webview.duo-chat-v2)/`(`package.json:205-209`)に対応する。

## 12. データモデル

```kotlin
data class DiagnosticsSnapshot(
  val ideVersion: String,
  val pluginVersion: String,
  val languageServerVersion: String,
  val gitlabInstanceVersion: String?,      // 未取得なら null
  val instanceUrl: String,
  val authenticationType: String,
  val tokenConfigured: Boolean,            // トークンそのものは保持しない
  val languageServerLogLevel: String,
  val debugLogging: Boolean,
  val telemetryEnabled: Boolean,
  val codeSuggestionsEnabled: Boolean,
  val duoChatEnabled: Boolean,
  val securityScanEnabled: Boolean,
  val ignoreCertificateErrors: Boolean,
  val caCertificateConfigured: Boolean,    // パスは保持しない
  val clientCertificateConfigured: Boolean,
  val languageServerRunning: Boolean,
  val languageServerLogPath: String,
  val featureStates: List<FeatureStateSnapshot>,
)

data class FeatureStateSnapshot(
  val title: String,                       // "GitLab Duo Chat" など
  val checks: List<FeatureStateChangeCheck>, // 既存の lsp/FeatureStateChange.kt を再利用
)
```

**トークン・証明書パス・パスワードはこのモデルに一切入らない。** これが N4 の第一防御。

## 13. 並行処理

| 論点 | 方針 |
|---|---|
| `LogRingBuffer` への並行 append | `ILogListener` は**任意のスレッド**から呼ばれる。バッファは `synchronized` で保護する。**臨界区間は配列への 1 代入のみ**に保ち、整形・改行分割は呼び出し側で済ませてから渡す(訂正後の N2)。ロックフリー実装は採らない — 唯一の安全網がレビューである以上、正しさの検証コストが利益に見合わない。 |
| **再入(最重要)** | `PluginLogTap` の中でログを出すと**無限再帰**になる。**タップ内では一切ログを出さない。** 例外は握り潰す(N3)。さらに `ThreadLocal<Boolean>` の再入ガードを置く。 |
| 収集中の状態変化 | `DiagnosticsSnapshotCollector.collect()` はスナップショットであり、整合性のあるある一時点を保証しない。**これは許容**(診断であって取引ではない)。 |
| エクスポート中の append | `getAll()` はコピーを返す。進行中の append はそのコピーに含まれないだけで、破損はしない。 |
| 同時エクスポート | 保存先はユーザーが選ぶので衝突しうる。**§9.4.1 の atomic move により**、同一パスでも「既存ファイル」か「完全な新しい ZIP」のどちらか一方しか残らない。アプリ側のガードは設けない(OS が直列化する)。 |
| エクスポート Job と UI | 収集・圧縮・書き込みは UI スレッドの外(N5)。UI に触れるのは保存ダイアログと通知のみ。通知は既存 `NotificationUtils` 経由で自らマーシャルする。 |

## 14. エラー処理

| 失敗 | 扱い |
|---|---|
| `PluginLogTap` 内の任意の例外 | **握り潰す。ログも出さない**(再入するため)。ログ収集の失敗が元のログを失わせてはならない(N3)。 |
| `language_server.log` が無い / 読めない | エクスポートは続行。当該エントリに `No language server logs available.` を入れる。 |
| 状態ディレクトリへの書き込み失敗 | ユーザーへ通知して終了。例外は**クラス名のみ**ログ(パスを含みうるため)。 |
| ZIP 組み立て失敗 | 同上。部分的なファイルを残さない(書き込みは完全なバイト列を作ってから 1 回)。 |
| 保存ダイアログのキャンセル | **何もしない。通知も出さない。** |
| Koin / ワークベンチ未起動 | 収集は取れた分だけで続行し、取れない項目は `Not available`。診断コマンドが診断できないのは本末転倒。 |
| `LogRingBuffer` が空 | `No extension logs available.`(VSCode と同文言) |

## 15. タイムアウトとリトライ / 冪等性

- **タイムアウトは設けない。** ネットワーク往復がなく(N1)、すべてローカルの読み書きのため。
- **リトライしない。** 失敗は即座にユーザーへ返す。ユーザーが再実行すればよい。
- **冪等**: F1 / F2 は同じファイルを毎回上書きする。F4 はタイムスタンプ付きファイル名で、同一秒内の再実行のみ上書きになりうる(§9.4 の後勝ち)。

## 16. 認証と認可

- 新たな認証経路は増えない。
- **本機能は既存のトークンを読むが、どこにも出力しない。** `tokenConfigured: Boolean` のみを持つ(§12)。
- サニタイズは二重防御(§10)。

## 17. ログ、監視、監査

- 本機能自体のログは**最小限**にする。`PluginLogTap` は無言(§13)。
- ハンドラの失敗ログは**例外クラス名のみ**。パス・ログ本文・設定値は出さない。
- **本機能はそれ自体が監視手段である。** 実機検証で異常が出たときの一次情報の収集経路になる。

## 18. 既存機能への影響

| 既存 | 影響 |
|---|---|
| `utils/Logger.kt` / 全ログ呼び出し | **変更しない。** タップは `ILog` の listener として外から付く。 |
| `log4j2.xml` / `language_server.log` | **変更しない。** エクスポートが読むだけ。 |
| `GitLabEclipseStartup` | タップ登録と停止時の解除を追加。**既存の起動順序は変えない**(状態ディレクトリのプロパティ設定より後に置く)。 |
| `PreferenceConstants` / `PreferenceInitializer` / `GitLabPreferencePage` | `gitlab.debug` を 1 件追加。**`plugin.xml` と同様、並行 PR の衝突点になる**(#20 §4)。 |
| `plugin.xml` | コマンド 4・ハンドラ 3・メニュー寄与を追加。**末尾追記のみ**(衝突の型は #20 §4 のとおり)。 |
| `gitlab.languageServer.logLevel` | **変更しない**(§5.1)。 |
| LS へ送る設定ペイロード | **変更しない。** `gitlab.debug` は LS へ送らない(VSCode と同じ、§5.1)。 |

## 19. 移行方法 / ロールバック方法

- **移行なし。** 新規の永続データを持たない(`gitlab.debug` は既定 false で、未設定時の挙動は現状と同一)。
- **ロールバック**: PR の revert のみで完結する。外部状態を変更しないため、残留物は `gl.showOutput` / `gl.showDiagnostics` が書いた状態ディレクトリ配下のファイル 2 つだけ(無害、次回起動で参照されない)。

## 20. テスト方針

| 層 | 方式 | 対象 |
|---|---|---|
| 純ロジック | **TDD**(Kotest `DescribeSpec` + MockK。既存規約どおり) | `LogRingBuffer` / `DiagnosticsSanitizer` / `DiagnosticsReport` / `FeatureStateSection` / `DiagnosticsArchive` / `DiagnosticsFileNaming` |
| タップ | `ILogListener` に対する直接呼び出し(`IStatus` は MockK で作れる) | `PluginLogTap` の整形・再入ガード・例外握り潰し |
| 収集 | 設定ストアと State サービスを MockK で差し替え | `DiagnosticsSnapshotCollector` |
| ハンドラ | 注入シームに fake を入れ、**UI へは到達させない** | 3 ハンドラの分岐(キャンセル・失敗・成功) |
| UI 実体 | **テストしない**(headless 不可)。手動検証手順を PR 本文に記載 | `FileDialog` / エディタ表示 / ツールバー表示 |

**サニタイザは本サイクルで最も重要なテスト対象**である。§10 の表の 8 パターンそれぞれについて、陽性(伏せられる)・陰性(伏せられない正常な文字列)の両方を書く。

**`StyledText` をモックする spec は headless で生成自体が失敗する**(既知 36 失敗の一部)。そこにテストを足すと実行されないまま緑に見えるため、**新規 spec を作る**。

## 21. 受け入れ条件

| # | 条件 | 検証手段 |
|---|---|---|
| A1 | `LogRingBuffer` が 5000 行を超えても最古を上書きし、`getAll()` が時系列順を返す | 自動テスト |
| A2 | §10 の 8 パターンすべてで、陽性が伏せられ陰性が保たれる | 自動テスト |
| A3 | 診断レポートに**トークンが平文で現れない**(トークンを設定した状態のスナップショットで検証) | 自動テスト |
| A4 | ZIP が 3 エントリちょうどを持ち、各エントリ名が VSCode と一致する | 自動テスト |
| A5 | 保存ダイアログのキャンセルで、**ファイルが 1 つも作られず通知も出ない** | 自動テスト(シームに fake) |
| A6 | `language_server.log` が無くてもエクスポートが成功する | 自動テスト |
| A7 | `PluginLogTap` がタップ内の例外で元のログ配送を壊さない | 自動テスト |
| A8 | `gitlab.debug` の既定が `false` | 自動テスト |
| A9 | `./gradlew` の失敗集合が `FAILSET_IDENTICAL` | `verify.sh` |
| A10 | detekt が main 17 / test 45 のベースラインちょうど | `detektMain` / `detektTest` 明示実行 |
| A11 | 4 コマンドが Quick Access から起動でき、Duo Chat ビューのツールバーに診断ボタンが出る | **実機**(手動検証手順) |
| A12 | `LogRingBuffer` の臨界区間が配列への 1 代入のみで、整形・分割・連結を含まない(訂正後の N2) | コードレビュー + 並行 append テスト |
| A13 | 同一の debug メッセージが、`gitlab.debug` on では `ILog` へ配送され、off では**配送されない** | 自動テスト |
| A14 | 既知トークンを含むログ行と JSON 断片が、ZIP の**3 エントリすべて**で平文にならない | 自動テスト |
| A15 | 同一パスへの同時エクスポート 2 本の後、残ったファイルが**完全に読める ZIP** | 自動テスト |
| A16 | 書き込み途中で失敗させたとき、**一時ファイルも部分ファイルも残らない** | 自動テスト |
| A17 | 数十行のスタックトレースを含む 1 件の `IStatus` が `lineCount()` をその行数ぶん進める | 自動テスト |
| A18 | エクスポートが UI スレッドを占有しない(Job 境界が守られている) | コードレビュー + Job のキャンセルテスト |

## 22. 未決事項

| ID | 内容 | 扱い |
|---|---|---|
| U1 | source map 相当を移植対象外とする判断(§5.1) | Kotlin/JVM に該当概念がないため対象外とする。レビューで異論があれば再考 |
| U2 | Error Log ビューとの二重表示を許容するか(§6.2) | 許容とする |
| U3 | `STATE_CHECK_USER_READABLE_LABELS` の実値 | **実装前に同梱 LS バンドルから抽出して計画に埋め込む**(#8 共通制約 5)。未抽出の ID は `checkId` をそのまま表示 |
| U4 | `gitlab.debug` を設定ページのどこに置くか | 実装時に既存のグループ構成に合わせる |
| U5 | GitLab インスタンスバージョンを既知のキャッシュから取れるか | 取れなければ常に `Not available`。**そのための REST 往復は追加しない**(§3) |

## 23. 想定されるリスク

| リスク | 影響 | 緩和 |
|---|---|---|
| **タップの再入による無限再帰** | 起動不能・スタックオーバーフロー | §13 の二重ガード(無言 + `ThreadLocal`)。テストで再入を直接検証 |
| **サニタイズ漏れによるトークン流出** | 重大 | 三重防御(§10 / §10.1)。収集側で最初から載せない + **既知トークンの実値置換** + パターン照合。陰陽テストを 11 パターンに拡張 |
| **UI スレッドの凍結**(20MB ログ + ZIP) | ワークベンチ無応答 | §9.4 の Job 境界。UI はダイアログと通知のみ |
| **壊れた ZIP が残る** | 診断が読めない = 機能の目的が失われる | §9.4.1 の temp + atomic move |
| 正規表現の移植ミス(JS → Kotlin) | サニタイズが効かない / 過剰に伏せる | §10 の移植注意点を計画に明記。陰性テストで過剰置換を検出 |
| `plugin.xml` / `PreferenceConstants` の並行 PR 衝突 | マージ時の手戻り | #20 §4 の型どおりに解決(両方残す・base 先) |
| headless で UI 配線を検証できない | ツールバーにボタンが出ない等が実機まで露見しない | UI 接触部を最小化し、配線は `fable` が担当。手動検証手順を PR 本文に記載 |
| リングバッファのメモリ | 5000 行 × 行長 | VSCode と同一上限。行長は整形時に制限しない(VSCode も同様) |

---

## 24. レビュー反映履歴

### 第 2 版(2026-09-21)— Codex レビュー round 1 反映

PR #86 の Codex レビューで **P1×4 + P2×2** の指摘。**6 件すべて妥当と判断し、全件反映した。**

| # | 指摘 | 反映先 | 判断 |
|---|---|---|---|
| P1 | 任意形式の認証情報(JSON の `access_token` / クエリの `private_token` / self-managed の非 `glpat-` トークン)がサニタイズ漏れ | **§10.1 を新設**(既知トークンの実値置換 + JSON キー + クエリキーの 3 規則)、A14 | **妥当。** 参照実装自体の穴で、`language_server.log` に現実に載る。**既知トークンの実値置換**が接頭辞非依存で最も強力 |
| P1 | プラグインの debug ログ経路が存在せず F5 が成立しない | **§5.1.1 を新設**、A13 | **妥当。** `.debug()` 呼び出しが 1 件も無く `ILog` に DEBUG severity も無い、という指摘は実ソースで確認した |
| P1 | 同一保存先への書き込みが非アトミック | **§9.4.1 を新設**、§13 訂正、A15 / A16 | **妥当。** `ByteArray` の 1 回書きでは truncate と write が競合する |
| P1 | N2「ロックを取らず」と §13 の `synchronized` が矛盾 | **N2 を訂正**(有界かつ極小の同期を許容)、§13 に不採用理由、A12 | **妥当。私の仕様矛盾。** 要件側を実態に合わせた。ロックフリー化は採らない(レビューが唯一の安全網である以上、検証コストが利益に見合わない) |
| P2 | エクスポートが UI スレッドを占有する | **§9.4 にスレッド境界**、N5、A18 | **妥当。** ハンドラは UI スレッドで呼ばれ、LS ログは最大 20MB |
| P2 | 5000 行上限が複数行ログに効かない | **§9.1 手順 5 を訂正**(物理行ごとに append)、A17 | **妥当。** 旧設計は `IStatus` 件数を数えていた。A1 が単一行入力だけだったため検出できなかった |

**未決事項の状態**: U1〜U5 は変更なし。**U3 は実装前に解決済み** — 同梱 LS 9.3.0 の
`build/gitlab-lsp/bin/main.js.map` から `STATE_CHECK_USER_READABLE_LABELS` の全 24 件と、
対応する `checkId` リテラル(`const AUTHENTICATION_REQUIRED = "authentication-required"` 等)を抽出した。

### 第 3 版(2026-09-21)— Codex レビュー round 2 反映

第 2 版(`803b6ac`)に対し **P1×3 + P2×1**。**4 件すべて妥当と判断し、全件反映した。**

| # | 指摘 | 判断と反映 |
|---|---|---|
| P1 | `ATOMIC_MOVE` 非対応時の縮退が N6 を破る | **妥当。私の仕様矛盾。** N6 を絶対条件と書きながら、警告付きで破る経路を用意していた。**縮退を廃止**し、`NonAtomicDestinationException` を投げて既存ファイルを保ったまま失敗させる。ユーザーはローカルディスクを選び直せる |
| P1 | OAuth 更新前の旧トークンが残る | **妥当。実在の漏洩経路。** エクスポート時点の現行トークンだけでは、更新前に捕捉した行の旧トークンに当たらない。**捕捉時点でのリテラル置換**を `PluginLogTap` に追加(その行が書かれた時点で現行だった値こそ、その行に載りうる値)。**リングバッファは完全に閉じた。`language_server.log` は LS プロセスが log4j 経由で直接書くためクライアントからは捕捉時点で触れず、形依存パターン + 公開済みトークン集合のみが効く — 残存リスクとして PR に明記する** |
| P1 | 8 文字未満の既知トークン除外で漏れる | **妥当。優先順位が逆だった。** 長さ下限を**撤廃**(ブランクのみ除外)。短い設定値は他のどのパターンにも当たらないため、除外は素通しを意味する。過剰置換は体裁の問題、漏洩は事故 |
| P2 | リングバッファのメタデータも同一同期区間 | **記述が不正確。実装は既に正しい。** 要素・カーソル・満杯フラグは 1 つの `synchronized` 区間で更新し、スナップショット取得も同区間で行う。N2 / A12 の「配列への 1 代入」という表現を「要素・カーソル・件数の一体更新と整合スナップショットまでを短い有界同期の対象とする」に訂正 |

#### 実装中に判明した設計の欠陥(レビュー指摘外・自己検出)

**`PluginLogTap` から `GitLabTokenProviderManager.getToken()` を呼ぶ設計は成立しない。**

OAuth 経路の `getToken()` は `refreshTokenIfExpired()` を通り、`OAuthTokenProvider.kt:69-87` で
**(a) ネットワーク往復**(`GitLabOAuthService.refreshToken`)/ **(b) ログ出力 3 行** / **(c) 通知表示** /
**(d) `updateToken` → `sendConfiguration()`** を行う。これを Eclipse のログ配送の内側で、**ログ 1 行ごとに**
実行すると、ログ配送がネットワークでブロックし、自分のログで再入し、ログ呼び出しからダイアログが出る。

**反映**: `DiagnosticsSecrets`(volatile キャッシュ)を新設した。値は **`GitLabLanguageServerConfigurationService.buildParams()` から publish** する — そこは既にトークンを手にしており、かつログ経路の外にある。タップとエクスポートはこのキャッシュを読むだけで、**I/O・ログ・通知のいずれも行わない**。

**代償(明示)**: 何も publish されていない間(= LS が一度も起動していない間)はリテラル置換の対象が空になり、形依存パターンのみが効く。ただしその状態は認証付きリクエストを 1 度も行っていない状態でもある。
