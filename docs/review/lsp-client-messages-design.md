# lsp-client-messages 設計書

対象 issue: #66(`workspace/applyEdit` 未実装)/ #67(LSP ハンドラ欠落)
ベースブランチ: `gitlab-ls-9.3.0` @ `5c4fb43`
同梱 gitlab-lsp: 9.3.0

> 本書はレビュー専用。実装 PR およびマージ先ブランチには含めない。

---

## 1. 背景と目的

当プラグインは gitlab-lsp が client へ送る server→client メッセージのうち一部にハンドラを持たない。とくに `workspace/applyEdit` が未実装のため、agentic chat / Duo Workflow のエージェントによるファイル編集が **Eclipse のエディタバッファを経由せず直接ディスクに書き込まれる**。同じファイルを Eclipse で開いて未保存の変更を持っている場合、**保存時にエージェントの編集を無言で上書きしうる**(逆も起こりうる)。

パリティ台帳(issue #7)では D5 Agentic Chat が 3/3 ✅ と計上されているが、その ✅ は「webview が表示される」ことを意味しており、**チャット内の操作(ファイルリンク・コピー)とエージェントの編集が実機で機能することを担保していない**。本サイクルの目的は、既に ✅ と計上した機能の実効性を担保し、あわせてデータ整合性の欠陥を解消することにある。

本サイクルはパリティ計数(67/85)を動かさない。動かすのは「✅ の意味」である。

## 2. 対象範囲

| # | メッセージ | 種別 | 出典 issue |
|---|---|---|---|
| F1 | `workspace/applyEdit` | request | #66 |
| F2 | `$/gitlab/openFile` | notification | #67 |
| F3 | `$/gitlab/copyText` | notification | #67 |
| F4 | `window/showDocument` | request | #67 |
| F5 | クライアント能力宣言(`workspace.workspaceEdit` / `window.showDocument`) | initialize | 本設計で追加 |

付随して、`ChatWebViewMessageHandlers.copyToClipboard` の重複クリップボード実装を既存 `ClipboardWriter` へ寄せる(F3 が触る領域内の限定的な整理)。

## 3. 対象外

- **`$/gitlab/openUrl`(#67 の 4 番目)。** 出荷バンドル全体で送信元は `packages/webview_vuln_details` の 1 箇所のみ。当該 webview は未実装(台帳 D9 の ❌1)であり、いま実装しても到達しない死にコードになる。D9 脆弱性詳細 webview のサイクルで同時に入れる。
  - 注: agentic chat のリンククリックは `$/gitlab/openUrl` を**通らない**。webview プラグインバス(`extensionMessageBus.sendNotification("openUrl", …)`)経由であり、既に `ChatWebViewMessageHandlers.openLink` が処理している。
- **`$/gitlab/document-quality/get-diagnostics`(#67 の 5 番目)。** クライアント feature flag `EditFileDiagnosticsResponse` の下でのみ呼ばれ、LS 側は例外時に `[]` へ劣化するため壊れない。Eclipse の `IMarker` → LSP `Diagnostic` の逆変換層(Phase 5B で作った変換の逆向き)が必要で、本サイクル最大の追加になる。別サイクルへ送る。
- **適用前の UI(差分プレビュー / 確認ダイアログ)。** 参照実装にも適用前に止まるものは無く(後述 §5.3)、本サイクルは整合性の確保に絞る。
- **適用後の差分タブ・診断待ち・自動フォーマット。** VSCode の `DiffMiddleware` / `DiagnosticsDelayMiddleware` / `FormatEditsMiddleware` 相当は作らない。
- **`$/gitlab/runCommand` / `$/gitlab/cancelRunningCommand` / `$/gitlab/repositoriesChanged` / `$/gitlab/workflowMessage` / `$/gitlab/mcp/serversNeedApproval` / `$/gitlab/api/error` / `$/gitlab/api/recovered`。** #67 で「対応不要」と判断済み(根拠は §4.4 で更新)。
- ビルドシステム・ディレクトリ構成の変更(全フェーズ共通制約)。

## 4. 現在の課題

### 4.1 `workspace/applyEdit` — データが壊れうる

1. LS はファイル更新にまず LSP 経路を試みる(`src/common/services/lsp_file_access_service.ts` の `updateFile`)。
2. `GitLabLanguageServerClient` は `applyEdit` を override していないため、**lsp4j の default 実装が無条件で `UnsupportedOperationException` を throw** し、エラー応答が返る。
3. LS 側は `createFallbackService` が priority 降順(`LSP_PRIORITY = 2` / `FS_PRIORITY = 1`)でフォールバックし、`FsFileAccessService.updateFile`(`node:fs/promises` による readFile → 編集適用 → writeFile)へ落ちる。
4. ファイル自体は更新されるが、**Eclipse の開いているエディタのバッファは何も知らない。**

結果として、対象ファイルを Eclipse で開いて編集中(dirty)の場合、その後の保存でエージェントの編集が消える。逆にエディタを再読込するとユーザーの未保存変更が消える。**どちらに転んでも無言。**

### 4.2 `$/gitlab/openFile` / `$/gitlab/copyText` / `window/showDocument` — 無言の no-op

未処理メッセージは `INFO: Unsupported request method: …` として記録されるだけで、ユーザーには「押しても何も起きない」としか観測できない。

なお `window/showDocument` は `$/` 前置ではないため、後述 §4.4 の「未処理でも `result:null` が返る」経路には**乗らない**。`LanguageClient` インタフェース上に default メソッドとして存在するため `GenericEndpoint` はハンドラを発見してしまい、default 実装が `UnsupportedOperationException` を throw する。

### 4.3 クライアント能力を宣言していない

`GitLabLanguageServerProcessProvider.getInitializationOptions`(`:285-324`)は `workspace.configuration` / `workspace.workspaceFolders` / `textDocument.completion` / `window.showMessage` のみを宣言し、`workspace.workspaceEdit` と `window.showDocument` は null のまま。現行 LS は能力を参照しないが(出荷バンドル全体で client capabilities を検査する first-party コードは無い)、宣言が実態と食い違っている。

### 4.4 【重要】lsp4j のバージョン記述が実態と異なる

issue #20 §3-8 および #67 は「lsp4j 0.23.1」を根拠にしているが、**実際に解決されているのは 1.0.0.v20260209-1721** である。

- `build.gradle.kts:177-178` の `"org.eclipse.lsp4j" to "0.23.1"` は Maven 座標ではなく equo `p2deps` に渡す **OSGi バンドル名 + 最小バージョン**であり、取得元は `build.gradle.kts:200` の `p2repo("https://download.eclipse.org/lsp4e/releases/latest/")`(ローリング)。
- 実体: `/root/.m2/repository/dev/equo/p2-data/bundle-pool/…/org.eclipse.lsp4j_1.0.0.v20260209-1721.jar`。

**ただし #67 の「`$/gitlab/runCommand` は未処理でも機能する」という結論は 1.0.0 でも有効である。** `GenericEndpoint.request` を逆アセンブルして確認した:

- `isOptionalMethod(String)` の本体は `method != null && method.startsWith("$/")`。
- ハンドラ未登録かつ delegate 不在のとき、`isOptionalMethod` が真なら **INFO ログ + `CompletableFuture.completedFuture(null)`**(= `result: null` を即返す)。偽なら WARNING 経路。

結論は変わらないが、**根拠のバージョン記述は #20 / #67 で訂正が必要**(§21 参照)。

## 5. 実ソースで確定したプロトコル仕様

全フェーズ共通制約「プロトコル定数は実装前に実ソースで確定」に基づく。出典は出荷バンドル `build/gitlab-lsp/bin/`(ネイティブ実行ファイル内の文字列リテラル、および `main.js.map` の `sourcesContent`)と、参照コピー `out/gitlab-vscode-extension`。

### 5.1 `workspace/applyEdit`

**送信内容**(`src/common/services/lsp_file_access_service.ts`):

```jsonc
{
  "edit": {
    "documentChanges": [
      {
        "textDocument": { "uri": "<file URI>", "version": null },
        "edits": [ { "range": { "start": {...}, "end": {...} }, "newText": "..." } ]
      }
    ]
  }
}
```

確定事項:

- **`label` は送られない。**
- **`changes`(レガシー形式)は使われない。** 送信側の zod スキーマが `documentChanges` しか持たない。
- **`CreateFile` / `RenameFile` / `DeleteFile` は絶対に来ない。** スキーマが `{textDocument, edits}` 以外を受け付けない。ファイル作成は `writeFile()` 経由で、これは「not implemented yet, will fallback to direct FS access」を throw するため常に FS へ行く。
- **`TextDocumentEdit` は 1 リクエストにつき常に 1 件。**
- **`version` は常に `null`。**
- レスポンススキーマは `{ applied: boolean, failureReason?: string }`。**`failedChange` は宣言されていない**ため zod に捨てられ、埋めても読まれない。
- **`applied: false` と JSON-RPC エラーは完全に等価。** `!result.applied` で `Error updating file: …` を throw し、`createFallbackFn` が catch して次の priority のサービスへ落ちる。レスポンスが zod 検証に落ちた場合(例: `applied` 欠落)も同じ。

**したがって「拒否する」という選択肢は存在しない。** 拒否は §4.1 の無言のディスク直書きをそのまま起こす。この事実が本設計のほぼすべての判断を規定する。

**URI の形**: LS 側 `pathOrUriToUri` は、入力が `/^[a-zA-Z][a-zA-Z0-9+\-.]+:\/?\//` にマッチすればそのまま通し、しなければバックスラッシュを `/` に変換して `file://` を前置する。入力は `fileLookupKey` = `posix.join(workspaceFolderPath, filePath)`。当プラグインが送る `workspaceFolders[].uri` は `IProject.locationURI.toASCIIString()`(`ProjectsWorkspaceFolder.kt:6-9`)であり、Java の `URI` は **`file:/path` という単一スラッシュ形式**を生成する。この形は上記正規表現にマッチする(`\/?` が空にマッチし `\/` が最初のスラッシュを取る)ため**そのまま通過する**。よって受信する URI は `file:///path` とは限らず **`file:/path` でありうる**。§11・§25 参照。

### 5.2 `$/gitlab/openFile`

- 送信元: `packages/lib_webview_agentic_chat` の `openFile(payload) → connection.sendNotification("$/gitlab/openFile", payload)`。LS 側スキーマは無く、webview のペイロードを**そのまま素通し**する。
- webview 側の送信は `openFile(e){ this.sendNotification("openFile", { filePath: e }) }`。
- **パラメータは `{ filePath }` のみ。`uri` も `selection` も `range` も `line` も存在しない。** issue #67 の「範囲指定があればその位置へ」は本バージョンには該当しない。
- VSCode 参照実装(`language_client_wrapper.ts:204-208`)は `path.join(vscode.workspace.rootPath || '', filePath)` → `vscode.open`。存在チェックなし、エラー処理なし、マルチルート非対応。

### 5.3 `workspace/applyEdit` に対する VSCode 参照実装の挙動

`language_client_wrapper.ts:242-251` が `client.onRequest('workspace/applyEdit', …)` でライブラリ既定を差し替え、4 段のミドルウェアを積む。実効順は DiagnosticsDelay(外) → SaveFile → Diff → FormatEdits → 適用本体。

- **適用前に止まるものは 1 つも無い。** DiffMiddleware は適用前にスナップショットを取るだけで、差分タブは**適用後**に開く非ブロッキングな情報表示(設定 `editFileDiffBehavior`、既定 `foreground`)。
- **dirty チェックは存在しない。** 適用本体(`apply_edit_client_wrapper.ts:65-88`)はバージョン一致チェックを持つが `version >= 0` のときだけ働き、**LS は `version: null` を送るので常にスキップされる**。未保存バッファの上にそのまま適用される。
- **適用後に保存する。** `save_file_middleware.ts:57-71` が `if (document && document.isDirty) await document.save()`。開いていないドキュメントは対象外。
- 適用はエディタのドキュメントモデル経由(`vscode.workspace.applyEdit`)。ディスクへは書かない。
- **ワークスペース内包チェックはクライアント側に一切無い。** 封じ込めはサーバ側(`.git/` / `.metadata/` / `.idea/*.xml` / `.vscode/settings.json` 等の拒否)。

### 5.4 `$/gitlab/copyText`

- パラメータ `{ text: string }`。LS は `_ref.text` を分解して再構成するので `text` のみが渡る。
- VSCode 参照実装(`:210-213`): クリップボードへ書き、`showInformationMessage('Copied to clipboard')` を出す。

### 5.5 `window/showDocument`

- 宣言: `src/node/duo_workflow/workflow_rpc_messages.ts:28-36`。パラメータ `{ uri: string(url), external?: boolean, takeFocus?: boolean }`。**`selection` はスキーマに存在しない。**
- 唯一の送信元 `desktop_url_opener_service.ts:18-30` は常に **`{ uri, external: true, takeFocus: true }`** を送る。
- **レスポンススキーマは `.withResponse` 未指定 = `z.void()`。** LS は `ShowDocumentResult.success` を読まない。`z.void()` は `undefined` 以外を拒否するため、仕様準拠の `{ success: true }` を返しても検証に落ちて promise が reject するが、`openUrl` は try/catch で `Failed to open url (…)` を info ログするだけ。**返り値の中身は挙動に一切影響しない。**
- 用途は Duo Workflow からの外部 URL オープンのみ。ワークスペース内のファイルを開く用途では使われない。
- VSCode 拡張には **`window/showDocument` のハンドラが存在しない**(ライブラリ既定に委ねている)。

### 5.6 lsp4j 1.0.0 の API 面

`javap` で確認:

- `LanguageClient.applyEdit` は `@JsonRequest("workspace/applyEdit")` つき default メソッド。本体は 2 命令(`new UnsupportedOperationException` / `athrow`)。
- `LanguageClient.showDocument` は `@JsonRequest("window/showDocument")` つき default メソッド。同じく無条件 throw。
- `WorkspaceEdit`: `Map<String, List<TextEdit>> changes` / `List<Either<TextDocumentEdit, ResourceOperation>> documentChanges` / `Map<String, ChangeAnnotation> changeAnnotations`。
- **`TextDocumentEdit.edits` は `List<Either<TextEdit, SnippetTextEdit>>`**(0.23.x の資料にある `List<TextEdit>` ではない)。
- `TextDocumentEdit.textDocument` は `VersionedTextDocumentIdentifier`(`version: Integer`)。**`OptionalVersionedTextDocumentIdentifier` は本バージョンに存在しない。**
- `ApplyWorkspaceEditParams`: `WorkspaceEdit edit` / `String label` / `WorkspaceEditMetadata metadata`。
- `ApplyWorkspaceEditResponse`: `boolean applied` / `String failureReason` / `Integer failedChange`。
- `ShowDocumentParams`: `String uri` / `Boolean external` / `Boolean takeFocus` / `Range selection`。`ShowDocumentResult`: `boolean success`。

## 6. 要件

### 機能要件

| ID | 要件 |
|---|---|
| R1 | `workspace/applyEdit` を実装し、編集を Eclipse のファイルバッファ経由で適用する。対象ファイルがエディタで開かれている場合、その編集はエディタのドキュメントに反映され、エディタの undo で取り消せること。 |
| R2 | 適用に成功したら、対象ファイルを保存する(ディスクとバッファを一致させる)。 |
| R3 | 適用できなかった場合は `applied: false` を返す。LS が FS フォールバックで書くことを許容する(§5.1)。 |
| R4 | `$/gitlab/openFile` を実装し、`filePath` をエディタで開く。 |
| R5 | `$/gitlab/copyText` を実装し、`text` をクリップボードへ書き、その旨を通知する。 |
| R6 | `window/showDocument` を実装し、`external: true` の URI を外部ブラウザで開く。 |
| R7 | `workspace.workspaceEdit` と `window.showDocument` のクライアント能力を、実際に受けられる範囲で宣言する。 |

### 非機能要件

| ID | 要件 |
|---|---|
| N1 | lsp4j のディスパッチスレッドをブロックしない。 |
| N2 | いかなる経路でも共有 `CoroutineScope` / ディスパッチループへ例外を漏らさない。 |
| N3 | ログに URI・ユーザーのファイルパス・ファイル内容・トークンを出さない。例外は種別のみ(`e.javaClass.name`)。 |
| N4 | ディレクトリ構成・ビルドシステムを変更しない。 |
| N5 | 既知失敗 36 件のベースライン(`FAILSET_IDENTICAL`)を動かさない。detekt はベースライン main 17 / test 45 のままとする。 |

## 7. 前提条件と制約

| ID | 前提 / 制約 | 根拠 | 崩れたときの影響 |
|---|---|---|---|
| A1 | LS は `documentChanges` のみを送り、リソース操作(create/rename/delete)を送らない | §5.1 | リソース操作が来たら `applied:false` → FS フォールバック。設計上は安全に縮退する |
| A2 | `version` は常に `null` | §5.1 | 非 null が来ても照合しない設計なので影響なし |
| A3 | `applied:false` は LS の FS 直書きを招く | §5.1 | この前提が本設計の判断の根拠。崩れると §9・§12 の判断を見直す必要がある |
| A4 | `$/gitlab/openFile` のパラメータは `{filePath}` のみ | §5.2 | 追加フィールドが来ても無視するだけ |
| A5 | `window/showDocument` は常に `external:true` | §5.5 | `false` が来た場合の経路を用意する(§9.4) |
| A6 | Eclipse のファイルバッファはエディタと共有される | Eclipse プラットフォーム仕様 | **本設計の中核。崩れると §8 の方式そのものが成立しない** |
| A7 | 同梱 lsp4j は 1.0.0(0.23.1 ではない) | §4.4 | ローリング p2 リポジトリのため将来変わりうる(§25 R6) |
| A8 | `workspaceFolders` は開いている `IProject` ごとに 1 件、`uri` は `file:/path` 形式 | `ProjectsWorkspaceFolder.kt:6-9` | 相対パス解決(§9.3)の前提 |

## 8. 適用方式の選択

| 案 | 内容 | 判定 |
|---|---|---|
| **A. `ITextFileBufferManager` 一本化** | `FileBuffers.getTextFileBufferManager()` で対象パスの `ITextFileBuffer` を connect → `getDocument()` に適用 → `commit()` → disconnect | **採用** |
| B. `IFile.setContents` / 生ファイル書き込み | 内容を読んで文字列として編集し書き戻す | 却下。開いているエディタのバッファを完全に迂回する = #66 の欠陥そのもの |
| C. エディタがあればエディタの `IDocument`、無ければ別経路 | 既存 `InsertSnippetHandler` の延長 | 却下。A が両方を単一経路で満たすため、分岐を増やすだけで得るものが無い |

**A を採る理由**: Eclipse のテキストファイルバッファはエディタと共有される。対象ファイルがエディタで開かれていれば `getDocument()` は**エディタが表示しているのと同一の `IDocument`** を返すため、編集はエディタに反映され undo も効く。開かれていなければ connect したバッファ経由でディスクへ書かれる。**「開いている / いない」で分岐しない単一経路**になり、これが「バッファを知らずにディスクへ書く」を構造的に不可能にする。

## 9. システム構成とコンポーネントの責務

新規クラスはすべて既存パッケージ体系の中に置く(構成変更なし)。

| コンポーネント | 配置(予定) | 責務 | UI スレッド依存 |
|---|---|---|---|
| `GitLabLanguageServerClient`(既存に追加) | `lsp/` | `@JsonRequest` / `@JsonNotification` の受け口。処理は下記へ委譲し、自身は future の生成と例外封じ込めのみ | なし(ディスパッチスレッド) |
| `WorkspaceEditApplier` | `lsp/edit/` | `WorkspaceEdit` → 検証 → `MultiTextEdit` 組み立て → バッファ適用 → commit | あり |
| `LspTextEditConverter` | `lsp/edit/` | LSP `Range`/`Position` → `org.eclipse.text.edits.ReplaceEdit`。オフセット変換と境界検査 | **なし(純ロジック)** |
| `EditTargetResolver` | `lsp/edit/` | URI 文字列 → `ITextFileBufferManager` に渡す `IPath` + `LocationKind` | **なし(純ロジック)** |
| `WorkspaceFileOpener` | `lsp/` もしくは既存 `navigation/` | `filePath` → ワークスペースルート解決 → エディタで開く。`OpenMrFileHandler.openEditorFor` の idiom を共通化 | あり |
| `ClipboardWriter`(既存を再利用) | `navigation/` | クリップボード書き込み | あり(内部で `asyncExec`) |
| `BrowserLauncher`(既存を再利用) | `navigation/` | 外部ブラウザ | あり(内部で `asyncExec`) |
| `ExternalUrlPolicy` | `lsp/` | `http`/`https` のみ許可。`ArtifactsUrl.kt:10-17` の前例に倣う | **なし(純ロジック)** |

「UI スレッド依存なし」と記した 3 つが本サイクルの主要なテスト対象である(§22)。

### 9.1 F1 `workspace/applyEdit` の処理フロー

```
[lsp4j ディスパッチスレッド]
  applyEdit(params)
    ├─ params.edit.documentChanges が null かつ changes が非 null → applied:false を即返す
    ├─ documentChanges の各要素が Either.isRight(ResourceOperation) → applied:false を即返す
    ├─ edits に Either.isRight(SnippetTextEdit) が含まれる → applied:false を即返す
    ├─ EditTargetResolver で URI を解決できない → applied:false を即返す
    └─ CompletableFuture<ApplyWorkspaceEditResponse> を未完了で生成
         onUiThread { ... }  ← 既定 currentDisplay.asyncExec
         return future.orTimeout(10, SECONDS)

[UI スレッド]
  ├─ claimed.compareAndSet(false, true) が false → 何もせず抜ける   ★二重適用ガード
  ├─ bufferManager.connect(path, locationKind, monitor)
  │    try {
  │      buffer = bufferManager.getTextFileBuffer(path, locationKind)
  │      document = buffer.getDocument()
  │      undoManager?.beginCompoundChange()
  │      try {
  │        MultiTextEdit(全 ReplaceEdit).apply(document)     ← 重なりは MalformedTreeException
  │        buffer.commit(monitor, overwrite = true)          ← R2 の保存
  │        future.complete(applied = true)
  │      } finally { undoManager?.endCompoundChange() }
  │    } catch (BadLocationException | MalformedTreeException | CoreException) {
  │      future.complete(applied = false, failureReason = 種別のみ)
  │    } finally { bufferManager.disconnect(path, locationKind, monitor) }
  └─ 上記全体を SWTException / IllegalStateException で包み、ディスパッチループへ漏らさない
```

**設計上の要点**

- **ディスパッチスレッドをブロックしない**(N1)。`syncExec` はディスパッチスレッドと UI スレッドの相互待ちを作りうるため使わない。未完了 future を返し、UI ランナブル内で complete する。
- **二重適用ガード**(★)。`orTimeout` が先に発火して future が完了したあとに UI ランナブルが走ると、**バッファへの適用と LS の FS 直書きが二重に起きる**。UI ランナブルの先頭で `AtomicBoolean` を claim し、既に完了していれば適用せず抜ける。タイムアウト側も同じフラグを claim してから完了させる。
- **`MultiTextEdit` を使う理由**。LSP の複数 `TextEdit` の `range` は**すべて元ドキュメント座標**である。自前で逆順ソートして順次 `document.replace` するより、`org.eclipse.text.edits.MultiTextEdit` に `ReplaceEdit` を積んで一括 `apply` する方が安全で、**重なりを `MalformedTreeException` として検出できる**。
- **`version` を照合しない**。常に `null` のため(A2)。VSCode 参照実装のバージョンチェックも同じ理由で常にスキップされている(§5.3)。
- **`failureReason` は LS に読まれない**(§5.1)。埋めるのはローカルログとテストのためであり、**内容にファイルパスや本文を含めない**(N3)。

### 9.2 内包チェックを入れない判断【重要・レビュー観点】

ワークスペース内包チェック(「編集対象がワークスペース配下か」)は**入れない**。

理由は「拒否しても何も守れない」ことにある。`applied:false` を返すと LS は `FsFileAccessService` へフォールバックし、**同じ編集を同じファイルにディスク直書きする**(§5.1、`LSP_PRIORITY=2` / `FS_PRIORITY=1` をバンドルで確認)。つまり内包チェックは「書かれるかどうか」ではなく「**どちらの経路で書かれるか**」しか変えられない。そして拒否した場合に選ばれるのは、**バッファ同期を失った方の経路**である。

したがって内包チェックは安全性を上げず、むしろ #66 が防ごうとしている状態を再現する。封じ込めは LS 側が既に行っており(`.git/` / `.metadata/` / `.idea/*.xml` / `.vscode/settings.json` の拒否)、VSCode 参照実装もクライアント側では一切チェックしていない(§5.3)。

**この判断は「危険なパスへの書き込みを許す」ことを意味しない。**「クライアント側の拒否では危険な書き込みを止められない」という事実の帰結である。もし本当に止める必要があるなら、それは LS 側かフォールバックの無効化で行うべきで、本設計の範囲外(§25 R5)。

### 9.3 F2 `$/gitlab/openFile` の相対パス解決

VSCode は単一の `rootPath` に `path.join` するが、当プラグインが LS に送る `workspaceFolders` は**開いている `IProject` ごとに 1 件**(A8)なのでルートが複数ある。方針:

1. `filePath` が絶対パスならそのまま使う。
2. 相対パスなら、`workspaceFolders` と同じ順序(= `ResourcesPlugin.getWorkspace().root.projects` の順序)で各ルートに解決し、**実在する最初のもの**を採用する。
3. どれにも実在しなければ何もしない(通知なので応答不要)。ログは種別のみ。

開く処理は `OpenMrFileHandler.openEditorFor`(`:109-126`)の idiom を共通化して再利用する: `workspace.root.getFileForLocation(...)` が当たれば `IDE.openEditor(page, iFile)`、外れれば `IDE.openEditorOnFileStore(page, EFS.getLocalFileSystem().getStore(file.toURI()))`。

**範囲の reveal は実装しない**(LS が `selection` を送らないため。§5.2)。

### 9.4 F3 / F4

- **F3 `$/gitlab/copyText`**: 既存 `ClipboardWriter.write(text)` を呼び、`NotificationUtils` で「Copied to clipboard」を出す(VSCode と同一)。あわせて `ChatWebViewMessageHandlers.copyToClipboard`(`:72-78`)の重複実装を `ClipboardWriter` へ寄せる。既存実装は `syncExec` で try/finally を持たず、例外時に `Clipboard` が dispose されない差異があるため、寄せることで解消する。
- **F4 `window/showDocument`**: `external != false`(既定 true 扱い)なら `ExternalUrlPolicy` で `http`/`https` のみ許可して `BrowserLauncher` へ。`external == false` は現行 LS では来ないが、来た場合は §9.3 の開く経路に回す。返り値は `ShowDocumentResult(success)` を返すが、**LS はこれを読まない**(§5.5)。

### 9.5 F5 クライアント能力宣言

`getInitializationOptions`(`:285-324`)に追加:

- `WorkspaceClientCapabilities.workspaceEdit = WorkspaceEditCapabilities()` に `documentChanges = true`、`resourceOperations = emptyList()`(= create/rename/delete は受けない)、`failureHandling = "abort"`。
- `WindowClientCapabilities.showDocument = ShowDocumentCapabilities(true)`。

現行 LS は能力を参照しないため機能上の差は出ない。実際に受けられる範囲を正直に宣言し、将来 LS が能力で分岐した場合に**未対応のものを送られない**ようにするのが目的。

## 10. API / インターフェース

```kotlin
// GitLabLanguageServerClient に追加
@JsonRequest("workspace/applyEdit")
override fun applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture<ApplyWorkspaceEditResponse>

@JsonRequest("window/showDocument")
override fun showDocument(params: ShowDocumentParams): CompletableFuture<ShowDocumentResult>

@JsonNotification("$/gitlab/openFile")
fun gitlabOpenFile(params: OpenFileParams)

@JsonNotification("$/gitlab/copyText")
fun gitlabCopyText(params: CopyTextParams)
```

`applyEdit` / `showDocument` はインタフェースの default メソッドを **`override`** する(新規 `@JsonRequest` 宣言ではない)。アノテーションはインタフェース側に既にあるため、override 側に付け直す必要はないが、house style に合わせて明示するかは実装時に決める(§24 U4)。

seam(既定値つきコンストラクタラムダ。`PlatformUtils.kt:20-23` / `EditorSelectionContextProvider.kt:21-24` / `NotificationUtils.kt:29-33` と同型):

```kotlin
class WorkspaceEditApplier(
  private val onUiThread: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val bufferManager: () -> ITextFileBufferManager = { FileBuffers.getTextFileBufferManager() },
  private val undoManagerFor: (IDocument) -> IDocumentUndoManager? = { DocumentUndoManagerRegistry.getDocumentUndoManager(it) },
)
```

## 11. データモデル

```kotlin
data class OpenFileParams(val filePath: String?)
data class CopyTextParams(val text: String?)
```

Gson 経由でデシリアライズされるため、**非 null 宣言は迂回されうる**(issue #47 と同型の既知問題)。したがって両フィールドとも **nullable で宣言し、ハンドラ側で null / blank を弾く**。

`EditTargetResolver` の出力:

```kotlin
sealed interface EditTarget {
  data class InWorkspace(val path: IPath) : EditTarget   // LocationKind.IFILE
  data class External(val path: IPath) : EditTarget      // LocationKind.LOCATION
}
```

URI → パス変換は **`file:///path` と `file:/path` の両形式を受け付ける**(§5.1 末尾)。`java.net.URI.getPath()` はどちらも `/path` を返すため、これを基礎にする。Windows の `file:/C:/…` に対する扱いは §24 U1 の未決事項。

## 12. トランザクション境界

**1 リクエスト = 1 `TextDocumentEdit` = 1 トランザクション**(A1 により LS は常に 1 件しか送らない)。境界は「バッファ connect から disconnect まで」。

原子性は `MultiTextEdit.apply(document)` が担保する。`MultiTextEdit` は適用前にツリー全体の整合性を検査し、**重なりがあれば 1 つも適用せずに `MalformedTreeException` を投げる**。したがって「一部だけ適用された」状態は発生しない。

`commit()` が失敗した場合は**バッファ上には編集が残る**(ディスクには反映されない)。このとき `applied:false` を返すと LS が FS へ直書きし、バッファとディスクが二重に編集された状態になりうる。**この経路の扱いは §24 U2 の未決事項**とする。

## 13. エラー処理

| 事象 | 応答 | ログ |
|---|---|---|
| `documentChanges` が null / 空 | `applied:false` | INFO(件数のみ) |
| `ResourceOperation` が含まれる | `applied:false` | WARN(種別のみ) |
| `SnippetTextEdit` が含まれる | `applied:false` | WARN(種別のみ) |
| URI が `file:` 以外 / 解析不能 | `applied:false` | WARN(スキーマ名のみ。**URI 本体は出さない**) |
| `BadLocationException`(範囲がドキュメント外) | `applied:false` | WARN(例外種別のみ) |
| `MalformedTreeException`(編集の重なり) | `applied:false` | WARN(例外種別のみ) |
| `CoreException`(connect / commit 失敗) | `applied:false`(§24 U2) | WARN(例外種別のみ) |
| UI スレッドで想定外の `Exception` | `applied:false` | ERROR(例外種別のみ) |
| `SWTException` / `IllegalStateException`(display 破棄・workbench 停止) | future をタイムアウトに委ねる | 無視(no-op) |

`$/gitlab/openFile` / `$/gitlab/copyText` は notification のため応答しない。失敗はログのみ。

**N3 の徹底**: ログには URI・パス・ファイル本文・`newText` を出さない。例外は `e.javaClass.name`。`?: e.javaClass.name` フォールバックの有無がブランチ全体で不統一という既知の申し送り(issue #52 の 6)があるため、**本サイクルで新規に追加する箇所は統一した形で書く**。

## 14. タイムアウトとリトライ

- `applyEdit` / `showDocument` の future に `orTimeout(10, TimeUnit.SECONDS)`(house style の `TIMEOUT_IN_SECONDS = 10L` に合わせる)。
- **リトライしない。** タイムアウト時は LS が FS フォールバックへ落ちる(§5.1)ため、クライアント側の再試行は二重書き込みを招く。
- タイムアウト発火と UI ランナブル実行の競合は §9.1 の `AtomicBoolean` で解決する。

## 15. 冪等性

`applyEdit` は**冪等ではない**。同一の `TextEdit` を 2 回適用すれば内容は 2 回変わる。LS 側にリトライ機構は無く(フォールバックは別サービスへの 1 回の切り替え)、同一リクエストが再送されることはない。

したがって本設計が守るべきは冪等性ではなく **「1 リクエストにつき高々 1 回しか適用しない」** ことであり、これを §9.1 の二重適用ガードで担保する。

## 16. 並行処理

- **ハンドラは lsp4j のディスパッチスレッドで呼ばれる。** UI 操作は必ず `onUiThread` seam を経由する。
- **複数の `applyEdit` が同時に来た場合**: 各リクエストは独立に UI スレッドへ post され、UI スレッドは単一なので**適用自体は直列化される**。同一ファイルに対する連続適用は、後続が先行の結果を含むドキュメントに対して適用されるが、LS は `getText`(LSP 優先)で最新のバッファ内容を読んでから編集を計算するため、**通常は整合する**。ただし LS が読んだ後・適用前に別の適用が挟まる窓は理論上存在する。この窓は VSCode 参照実装にも同じく存在し、`version: null` である以上クライアント側では検出できない。**受容する制限として §25 R3 に記載。**
- **バッファの connect / disconnect は対で行う**(`finally`)。同一ファイルが並行して connect されても `ITextFileBufferManager` は参照カウントで管理するため安全。
- **共有 `CoroutineScope` は使わない。** 本サイクルの処理はすべて future と UI ランナブルで完結する。

## 17. 認証と認可

本サイクルは GitLab API を呼ばず、認証情報を扱わない。

認可の観点で意味を持つのは「LS が指示したファイル書き込み・ブラウザ起動をクライアントが実行してよいか」だが、LS は当プラグインが起動した子プロセスであり、既に同一ユーザー権限で任意のファイルへ書き込める(実際、フォールバックでそうしている)。したがって**クライアント側の拒否は権限境界にならない**(§9.2)。

唯一意味のある制限は **`window/showDocument` の URL スキーム制限**である。`file:` や `javascript:` を外部ブラウザへ渡さないよう `http`/`https` のみ許可する(§9.4)。

## 18. ログ・監視・監査

- 既存 house style(`logger<GitLabLanguageServerClient>()`)に従う。
- **監査行は出さない。** Phase 5B のセキュリティスキャン(`securityScanResponse`)は機微ペイロードを扱うため監査行を持つが、本サイクルは編集内容そのものが機微であり、**監査行を出すこと自体がリスク**になる。件数レベルの INFO に留める。
- 障害調査に必要な最小情報: メッセージ種別 / 編集件数 / 成否 / 例外種別。**パス・URI・本文は出さない。**

## 19. 障害時の復旧方法

| 障害 | 影響 | 復旧 |
|---|---|---|
| バッファ connect が常に失敗する | 全 `applyEdit` が `applied:false` → LS が FS 直書き | **現行と同じ挙動に縮退する**(退行ではない)。ユーザーはエディタを閉じてから使えば整合する |
| UI スレッドが詰まる | future がタイムアウト → FS 直書き | 同上 |
| 誤った編集が適用された | ファイル内容が壊れる | **エディタの undo で戻せる**(§9.1 の compound change)。保存済みならローカル履歴(Eclipse の Local History)からも復元可能 |
| `openFile` / `copyText` が失敗 | 無言の no-op(現行と同じ) | ログで種別を確認 |

**重要**: 本設計のあらゆる失敗経路は「現行の挙動(FS 直書き)」へ縮退する。**新たな失敗モードを増やさない**ことが設計の基本方針である。

## 20. 既存機能への影響

| 対象 | 影響 |
|---|---|
| `GitLabLanguageServerClient` | ハンドラ 4 件追加。既存 12 件のハンドラには触れない |
| `GitLabLanguageServerProcessProvider.getInitializationOptions` | 能力宣言 2 件追加。**並行 PR と衝突しうる箇所**(§4 の型) |
| `ChatWebViewMessageHandlers.copyToClipboard` | `ClipboardWriter` へ委譲に変更。**呼び出し元 4 箇所の挙動は `syncExec` → `asyncExec` に変わる** — webview のコピーボタンが同期完了を前提にしていないことの確認が必要(§24 U3) |
| `OpenMrFileHandler.openEditorFor` | 共通化のため抽出。既存の呼び出し挙動は変えない |
| `ClipboardWriter` / `BrowserLauncher` | 変更なし(再利用のみ) |
| Code Suggestions / Duo Chat / MR / CI / セキュリティスキャン | 影響なし |

`plugin.xml` は**変更しない**(本サイクルはコマンドもメニューも追加しない)。したがって D15 で 3 回発生した `plugin.xml` の並行コンフリクトは起きない。

## 21. 移行方法

デプロイ上の移行手順は不要(新規ハンドラの追加のみ、設定移行なし)。

ただし**ドキュメント上の訂正**が必要:

- **issue #20 §3-8** の「lsp4j 0.23.1 は `$/` 前置の未処理 request に即 `result:null` を返す」→ バージョンを **1.0.0.v20260209-1721** に訂正(結論は不変。§4.4)。
- **issue #67** の「対応不要と判断したもの」節の同じ記述も訂正。
- **issue #67** の `$/gitlab/openFile` 行の「範囲指定があればその位置へ」→ **本バージョンに `selection` は存在しない**旨を追記(§5.2)。
- **issue #67** の `$/gitlab/openUrl` 行に、**agentic chat のリンククリックはこの経路を通らない**旨を追記(§3)。

## 22. ロールバック方法

実装 PR 単位で revert 可能。ハンドラを削除すれば lsp4j の default(`UnsupportedOperationException`)に戻り、LS は FS フォールバックへ落ちる = **現行の挙動に完全に戻る**。永続状態・設定・スキーマの変更を伴わないため、ロールバックに後始末は不要。

能力宣言(F5)も同様に、削除すれば宣言なしに戻る。

## 23. テスト方針

**headless で検証できる(= 自動テストを書く)**

| 対象 | 検証内容 |
|---|---|
| `LspTextEditConverter` | `Position(line, character)` → offset の変換。行頭 / 行末 / 最終行 / 空ドキュメント / 範囲外(`BadLocationException`)。LSP の `character` が UTF-16 コード単位であることを、サロゲートペアを含む行で確認 |
| `LspTextEditConverter` | 複数 `TextEdit` を `MultiTextEdit` に積んだときの一括適用結果。**重なりで `MalformedTreeException` が出て 1 つも適用されないこと** |
| `EditTargetResolver` | `file:///path` と `file:/path` の両形式。`file:` 以外の拒否。ワークスペース内 / 外の判定 |
| `ExternalUrlPolicy` | `http`/`https` の許可、`file:`/`javascript:`/相対/空文字の拒否 |
| `WorkspaceEditApplier` | 注入した偽 `onUiThread` とインメモリ `Document` で: 正常適用 / `ResourceOperation` 拒否 / `SnippetTextEdit` 拒否 / `changes` のみ拒否 / **二重適用ガード(タイムアウト後に UI ランナブルが走っても適用されない)** |
| `OpenFileParams` / `CopyTextParams` | null / blank の弾き(Gson が非 null 宣言を迂回する前提。issue #47 と同型) |
| `$/gitlab/openFile` の相対パス解決 | 複数ルートのうち実在する最初のものを選ぶこと。どれにも無ければ何もしないこと |

**headless で検証できない(= 手動検証手順を PR 説明文に書く)**

- 実バッファの connect / commit / disconnect
- 開いているエディタへの反映と undo
- クリップボード / 外部ブラウザ
- LS からの実際のディスパッチ

**注意事項**(環境の癖):

- `StyledText` をモックする spec は headless で生成自体が失敗する(既知 36 失敗の一部)。**新規 spec は既存のそれらに足さず、別ファイルで作る。**
- ログを出すクラスの spec には `LoggingKotestExtension` が必要。
- 合否は絶対数ではなく失敗集合で見る。`verify.sh` が `FAILSET_IDENTICAL (36 failures)` を出せば合格。
- **detekt は `detektMain` / `detektTest` を明示実行する。** `./gradlew build` が回す `:detekt` は型解決なしで、`UnsafeCallOnNullableType` / `UnnecessaryFilter` を見逃した実績がある(#76 / #77 で実際にすり抜けた)。ベースラインは main 17 / test 45。

## 24. 未決事項

| ID | 内容 | 決め方 |
|---|---|---|
| **U1** | **Windows での URI → パス変換。** LS が送る URI は `file:/C:/…`(単一スラッシュ)になりうる(§5.1 末尾)。`java.nio.file.Paths.get(URI)` がこの形を受けるか、`URI.getPath()` の先頭スラッシュを手で剥がす必要があるかを確定する。**issue #68(Windows で `rootFsPath` と `fsPath` のパス形式が不一致)と同根の可能性がある。** | 実装前に Windows 実機か、パス変換だけを切り出した単体テストで確定 |
| **U2** | **`commit()` 失敗時にバッファの編集を巻き戻すか。** 巻き戻さずに `applied:false` を返すと、バッファに編集が残ったまま LS が FS へ直書きし、**二重に編集された状態**になりうる(§12)。巻き戻す場合は `MultiTextEdit.apply` の戻り値(逆編集)を保持して適用する方法があるが、undo スタックとの相互作用を確認する必要がある | Codex レビューで方針を確定 |
| **U3** | **`ChatWebViewMessageHandlers.copyToClipboard` を `syncExec` → `asyncExec` に変えてよいか。** webview 側のコピーボタンが同期完了を前提にしていないことの確認が必要 | 呼び出し元 4 箇所(`AgenticChatWebViewController.kt:89,92` / `GitLabDuoChatWebViewController.kt:47,50`)を読んで確定。前提していれば `ClipboardWriter` に同期版を足す |
| **U4** | `applyEdit` / `showDocument` の override に `@JsonRequest` を明示するか。インタフェース側に既にあるため不要だが、house style は全ハンドラに明示している | 実装時に既存コードと揃える |
| **U5** | `$/gitlab/openFile` で `filePath` が絶対パスかつワークスペース外を指した場合に開くか。VSCode は `path.join` の性質上、絶対パスならそのまま開く | 既定は「開く」。Codex レビューで再検討 |
| **U6** | `openFile` の相対パス解決で、複数ルートに同名ファイルが実在する場合の優先順位。現案は `IProject` の列挙順の先頭 | 曖昧さを受容するか、開かずに警告するかを Codex レビューで確定 |

**推測で確定しない。** 上記はいずれも実ソースまたは実機で確定させる。

## 25. 想定されるリスク

| ID | リスク | 影響 | 緩和 |
|---|---|---|---|
| **R1** | **二重適用**(タイムアウト後に UI ランナブルが適用してしまう)。バッファ適用 + LS の FS 直書きで編集が 2 回入る | 高(ファイル破損) | §9.1 の `AtomicBoolean` claim。**テストで明示的に固定する**(§23) |
| **R2** | UI スレッドと lsp4j ディスパッチスレッドのデッドロック | 高(LS 全体が固まる) | `syncExec` を使わない。未完了 future + `asyncExec` + `orTimeout` |
| **R3** | LS が `getText` で読んだ後・適用前に別の適用が挟まる窓 | 中(編集が意図とずれる) | `version: null` のためクライアント側では検出不能。**受容する制限**として PR に明記。VSCode 参照実装も同じ |
| **R4** | 意図しない保存(R2 要件により、ユーザーの未保存変更も一緒にディスクへ行く) | 中 | VSCode 参照実装と同一挙動。**PR とリリースノートに明記**。undo は効く |
| **R5** | 危険なパス(`.git/` 等)への書き込み | 中 | LS 側で封じ込め済み。**クライアント側の拒否では止められない**(§9.2)。止める必要が生じたら別設計 |
| **R6** | **`p2repo(".../lsp4e/releases/latest/")` がローリングであるため、同じコミットでも取得時期でビルド結果が変わりうる** | 中(再現性) | 本サイクルのスコープ外(ビルドシステム変更禁止)。**別 issue に記録する**(§21 とあわせて) |
| **R7** | headless で検証できない範囲が広い(バッファ・エディタ・クリップボード・ブラウザ) | 中 | 純ロジックを 3 クラスに切り出して最大限テストする(§9 の「UI スレッド依存なし」列)。残りは手動検証手順を PR に記載 |
| **R8** | `getInitializationOptions` が並行 PR と衝突 | 低 | 現時点で open PR は 0 本。マージ順序に注意 |
| **R9** | `TextDocumentEdit.edits` の `Either<TextEdit, SnippetTextEdit>` を取り違え、`SnippetTextEdit` を `TextEdit` として扱う | 中 | `Either.isLeft` を明示的に検査し、右辺は `applied:false`。テストで固定 |

## 26. 受け入れ条件

| ID | 条件 | 検証方法 |
|---|---|---|
| AC1 | エディタで開いていないファイルへのエージェント編集が、ディスクに反映される | 手動(実機) |
| AC2 | エディタで開いているファイルへのエージェント編集が、**エディタの表示に即座に反映される** | 手動(実機) |
| AC3 | AC2 の編集が **1 回の undo で取り消せる** | 手動(実機) |
| AC4 | 未保存変更のあるファイルにエージェントが編集したとき、**ユーザーの未保存変更が失われない**(編集後に保存され、両方がディスクに載る) | 手動(実機) |
| AC5 | agentic chat のファイルリンクを押すとエディタで開く | 手動(実機) |
| AC6 | agentic chat のコピー操作でクリップボードに入り、通知が出る | 手動(実機) |
| AC7 | Duo Workflow の「URL を開く」で外部ブラウザが開く | 手動(実機) |
| AC8 | `http`/`https` 以外の URI が外部ブラウザへ渡らない | 自動テスト |
| AC9 | 編集の重なり・範囲外・`SnippetTextEdit`・`ResourceOperation` がすべて `applied:false` になり、**1 つも適用されない** | 自動テスト |
| AC10 | タイムアウト後に UI ランナブルが走っても適用されない | 自動テスト |
| AC11 | ログに URI・パス・ファイル本文が出ない | コードレビュー + 自動テスト(ログキャプチャ) |
| AC12 | 失敗集合が `FAILSET_IDENTICAL`、detekt が main 17 / test 45 のまま | `verify.sh` + `detektMain` / `detektTest` |

---

## 付録: 根拠一覧

| 主張 | 出典 |
|---|---|
| `applied:false` → FS 直書き | `packages/lib_core/dist/index.mjs`(`createFallbackService` / `createFallbackFn`)、`packages/lib_fs/dist/index.mjs:26-27`(`LSP_PRIORITY=2` / `FS_PRIORITY=1`)、`packages/lib_fs/dist/node.mjs:315-318` |
| `applyEdit` の送信形状 | `src/common/services/lsp_file_access_service.ts:16-32, 80-101` |
| `openFile` のパラメータ | `packages/lib_webview_agentic_chat/dist/index.mjs:957-967`、webview 資産 `bin/webviews/agentic-duo-chat/assets/index.c3b1ebd7.js` |
| `copyText` のパラメータ | `packages/lib_webview_agentic_chat/dist/index.mjs:969-981` |
| `showDocument` の送信内容と `z.void()` | `src/node/duo_workflow/workflow_rpc_messages.ts:28-36`、`src/node/duo_workflow/desktop_url_opener_service.ts:18-30` |
| VSCode の applyEdit ミドルウェア | `out/gitlab-vscode-extension/src/common/language_server/language_client_wrapper.ts:242-251`、`apply_edit_client_wrapper.ts:54-92`、`save_file_middleware.ts:57-71`、`diff_middleware.ts:37-57` |
| lsp4j 1.0.0 の default 実装と型 | `javap` on `org.eclipse.lsp4j_1.0.0.v20260209-1721.jar` |
| `GenericEndpoint` の `$/` 扱い | `javap -c` on `org.eclipse.lsp4j.jsonrpc_1.0.0.v20260209-1721.jar` |
| Eclipse 側の既存経路 | `OpenMrFileHandler.kt:109-126`、`ClipboardWriter.kt:9-18`、`BrowserLauncher.kt:12-34`、`ArtifactsUrl.kt:10-17`、`PlatformUtils.kt:20-23`、`DisplayJobLogHandler.kt:157-179`、`ProjectsWorkspaceFolder.kt:6-9`、`GitLabLanguageServerProcessProvider.kt:285-324` |
