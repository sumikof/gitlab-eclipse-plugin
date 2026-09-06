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

**応答契約について【重要・検証済み】**: 「`null` を返せば `z.void()` を通せるのではないか」という案は
**成立しない**。両側を実測した:

1. **zod 側** — 出荷バンドルの zod v4 ソース(`node_modules/zod/v4/core/schemas.js`)で `$ZodVoid` の
   `parse` は `if (typeof input === "undefined") return payload;` のみを受理し、それ以外は
   `{ expected: "void", code: "invalid_type" }` を積む。**JSON の `null` は JS で `typeof null === "object"`
   なので拒否される。**
2. **lsp4j 側** — `MessageTypeAdapter.write` のバイトコード(`org.eclipse.lsp4j.jsonrpc_1.0.0.v20260209-1721.jar`)
   で、`ResponseMessage` に `error` が無いとき **`name("result")` を無条件に出力**し(offset 169-175)、
   `getResult() == null` のときは `writeNullValue`(= `setSerializeNulls(true)` → `nullValue()` →
   元に戻す)で **`"result": null` を必ず書き出す**(offset 182-192)。
   **`result` メンバを省略する応答を lsp4j から作ることはできない。**

したがって **`z.void()` を満たす応答はこの lsp4j / LS の組み合わせでは存在しない。**
**受容する制限**とし、`ShowDocumentResult(success)` を返す(§25 R10)。
代償は LS 側に info ログが 1 行出ることだけで、**機能影響は無い**
(`desktop_url_opener_service.ts:18-30` が try/catch で握っており、URL は既に開かれている)。
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
| R2 | 適用に成功したら、対象ファイルを保存する(ディスクとバッファを一致させる)。**保存は `commit(overwrite = false)` で行い、外部変更を無言に上書きしない**(§9.1)。保存できなかった場合の扱いは §12.1。 |
| R3 | **編集がクライアント側に残らない場合は `applied: false` を返す** — 適用しなかった場合、および適用したがバッファにも残らない場合(§12.1)。LS が FS フォールバックで書くことを許容する(§5.1)。 |
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
| A6 | Eclipse のファイルバッファはエディタと共有される。**ただし共有されるのは「エディタと同じ API 系列で接続した場合」に限る** — ワークスペース内は `IPath` 系、ワークスペース外は `IFileStore` 系で、管理マップが別だから(§9.1) | Eclipse プラットフォーム仕様 + javap(§付録) | **本設計の中核。崩れると §8 の方式そのものが成立しない** |
| A7 | 同梱 lsp4j は 1.0.0(0.23.1 ではない) | §4.4 | ローリング p2 リポジトリのため将来変わりうる(§25 R6) |
| A8 | `workspaceFolders` は開いている `IProject` ごとに 1 件、`uri` は `file:/path` 形式 | `ProjectsWorkspaceFolder.kt:6-9` | 相対パス解決(§9.3)の前提 |

## 8. 適用方式の選択

| 案 | 内容 | 判定 |
|---|---|---|
| A. `ITextFileBufferManager` 一本化 | URI からパス / ファイルストアを決め、常に `FileBuffers` 経由でバッファへ適用する | **却下(第 8 版で変更)**。下記「A を却下した理由」 |
| B. `IFile.setContents` / 生ファイル書き込み | 内容を読んで文字列として編集し書き戻す | 却下。開いているエディタのバッファを完全に迂回する = #66 の欠陥そのもの |
| **C. エディタが開いていればそのドキュメント、開いていなければバッファ** | 対象にエディタが開いていれば**そのエディタのドキュメントプロバイダから `IDocument` を取得**して適用・保存する。開いていなければ従来どおりファイルバッファへ connect する | **採用** |

### A を却下した理由(第 8 版・重要)

第 1〜7 版は A を採り、「エディタの有無で分岐しない単一経路」を利点としていた。**その前提は成立しない。**
A が成り立つには「URI から、エディタが接続しているのと**同じバッファ**を引き当てられる」ことが要るが、
`TextFileDocumentProvider.createFileInfo` を実測すると、**接続系列はエディタ入力の型の関数であって
URI の関数ではない**(javap で確認。§付録):

| エディタ入力 | エディタが使う系列 | 属性・リンクの扱い |
|---|---|---|
| ① `IFileEditorInput`(`IFile` にアダプト可能) | `connect(file.getFullPath(), LocationKind.IFILE, …)` | hidden / team-private を**問わない** |
| ② `ILocationProviderExtension`(URI) | **1 引数の** `findFilesForLocationURI(uri)` が当たれば `IFILE`、外れれば `EFS.getStore(uri)` + `connectFileStore` | 1 引数版は `IResource.NONE` なので hidden / team-private を**除外する** |
| ③ `ILocationProvider`(パス) | **`connect(path, LocationKind.NORMALIZE, …)`**(`getWorkspaceFileAtLocation` を見るのは接続の**あと**) | `NORMALIZE` は `getFileForLocation` 経由なので**リンクを見つけない** |

同じ物理ファイルでも、**どの入力型で開かれたか**によって ①②③ のどれになるかが変わる。こちらは URI しか
持たないため、一般には再構成できない。実際、Codex レビューの 5・6・7 巡目で挙がった P1
(リンクリソース / hidden 資源 / `NORMALIZE`)は**すべてこの同じ根に由来する別の症状**であり、
個別に潰しても次の顔が出る構造になっていた。

**推測が避けられないのは「エディタが開いているとき」だけであり、そこは推測せずに済ませられる。**

- エディタが開いているなら、**推測せずに本人に聞ける。** そのエディタのドキュメントプロバイダから
  `IDocument` を取れば、それは**エディタが表示しているまさにそのドキュメント**である。
  ①②③ のどれで接続されていようと関係がない。
- **エディタが開いていない場合の「共有相手」は、検出できる。** エディタ以外の参照者
  (バックグラウンドのジョブ等)がバッファを保持していることはありうるが、**それは
  `ITextFileBufferManager` に問い合わせれば分かる**。第 9 版では **Buffered 経路に入る前に
  全系列を probe し、既存のバッファがあれば join する**(§11)。join すれば編集は相手のバッファに入り、
  相手の保存はこちらの編集ごと書く。**「エディタが無い = 共有相手が無い」とは言わない。**
- 残るのは「probe と `connect` の間に別系列のバッファが新しく作られる」窓だけで、これは
  バックグラウンドの接続が UI スレッドに同期しない以上クライアント側では閉じられない(R22)。

したがって C を採る。**分岐は 1 つ増えるが、増えた分岐は「推測が要らない側」と「検出して join できる側」の
境界に一致しており、#66 の欠陥が構造的に起こりえなくなる。**
**C を採ることで消える問題**: エディタとの接続系列の不一致(R17)/ リンクリソースの誤分類 /
hidden・team-private の誤分類(R20)/ `NORMALIZE` 経路の不一致。**いずれも「エディタが開いている場合」に
しか害が無く、その場合は分類を行わないため。**

## 9. システム構成とコンポーネントの責務

新規クラスはすべて既存パッケージ体系の中に置く(構成変更なし)。

| コンポーネント | 配置(予定) | 責務 | UI スレッド依存 |
|---|---|---|---|
| `GitLabLanguageServerClient`(既存に追加) | `lsp/` | `@JsonRequest` / `@JsonNotification` の受け口。処理は下記へ委譲し、自身は future の生成と例外封じ込めのみ | なし(ディスパッチスレッド) |
| `WorkspaceEditApplier` | `lsp/edit/` | `WorkspaceEdit` → 検証 → `MultiTextEdit` 組み立て → バッファ適用 → commit | あり |
| `LspTextEditConverter` | `lsp/edit/` | LSP `Range`/`Position` → `org.eclipse.text.edits.ReplaceEdit`。オフセット変換と境界検査 | **なし(純ロジック)** |
| `EditTargetResolver` | `lsp/edit/` | URI 文字列 → `EditTarget`(**エディタが開いていれば `OpenEditor`**、無ければ `Buffered.InWorkspace` / `Buffered.External`)と、`Buffered` から導く `BufferAccess`(§11)。エディタ照会と候補列挙は**引数で受け取る** | **クラス自体は純ロジック。ただし呼び出しは UI スレッド**(エディタ照会を含むため。§8・§11) |
| `WorkspaceFileOpener` | `lsp/` もしくは既存 `navigation/` | `filePath` → ワークスペースルート解決 → エディタで開く。`OpenMrFileHandler.openEditorFor` の idiom を共通化 | あり |
| `ClipboardWriter`(既存を再利用) | `navigation/` | クリップボード書き込み | あり(内部で `asyncExec`) |
| `BrowserLauncher`(既存を再利用) | `navigation/` | 外部ブラウザ | あり(内部で `asyncExec`) |
| `ExternalUrlPolicy` | `lsp/` | `http`/`https` のみ許可。`ArtifactsUrl.kt:10-17` の前例に倣う | **なし(純ロジック)** |

「UI スレッド依存なし」と記した 3 つが本サイクルの主要なテスト対象である(§22)。

### 9.1 F1 `workspace/applyEdit` の処理フロー

**単一の状態機械で排他する。** タイムアウト・セットアップ失敗・編集開始は**すべて同じ状態変数の遷移**として
表現し、「開始前に諦める」か「開始したら最後まで応答を持つ」かのどちらかしか起こらないようにする。

```
状態: AtomicReference<State>   State ∈ { PENDING, RUNNING, SETTLED }

  PENDING --(UI ランナブルが CAS 成功)--> RUNNING --(応答確定)--> SETTLED
  PENDING --(タイムアウト / セットアップ失敗が CAS 成功)--> SETTLED   ★ RUNNING からは遷移できない

  settleIfPending(applied)   = compareAndSet(PENDING, SETTLED) に成功したときだけ future.complete(applied)
                               失敗(= 既に RUNNING か SETTLED)なら何もしない
  settleFromRunning(applied) = state.set(SETTLED) → future.complete(applied)
                               ★ RUNNING に入った本人だけが、必ず 1 回だけ呼ぶ

不変条件:
  I1. ドキュメントを変更しうるのは PENDING → RUNNING の CAS に成功した 1 本だけ(高々 1 回)
  I2. RUNNING に入った後は、タイムアウトもセットアップ失敗も応答を返さない
      (適用中の LS を FS フォールバックさせない)
  I3. future を完了させるのは SETTLED へ遷移させた 1 本だけ
  I4. RUNNING に入った本人は、どの例外経路を通っても必ず 1 回 settleFromRunning に到達する
      (= future が未完了のまま残らない)。★ 応答は finally の中で「最初に」確定させる
  I5. 応答 applied は「編集がこのランナブルの退出後も残るか」と一致する。
      ★ これは予測ではなく退出時の観測で決める(§12.1)
```

```
[lsp4j ディスパッチスレッド]
  applyEdit(params)
    ├─ 事前検証(いずれも未適用のまま completedFuture(applied:false) を即返す。状態機械には入らない)
    │    ├─ documentChanges が null / 空(changes だけを持つ場合を含む)
    │    ├─ documentChanges の要素が Either.isRight(ResourceOperation)
    │    ├─ edits に Either.isRight(SnippetTextEdit) が含まれる
    │    └─ ★ URI の構文検証のみ(file: スキームか / URI として解析できるか)
    │         ワークスペース資源の解決とエディタの照会はここで行わない(下記)
    └─ 未完了の CompletableFuture を生成し state = PENDING にしてから arm()

  arm() {                                       ← ★ セットアップ全体を 1 つの try で囲む
    try {
      onUiThread { runEdit() }                  ← 既定 currentDisplay.asyncExec。★ 先に登録する
      scheduleTimeout(START_TIMEOUT) { settleIfPending(applied = false) }   ← ★ orTimeout は使わない
    } catch (Throwable t) {                     ← RejectedExecutionException / display 破棄の SWTException 等
      log(種別のみ)
      settleIfPending(applied = false)          ← ★ ここが「セットアップ失敗」も状態機械に載せる点
    }
    return future                               ← 例外はディスパッチスレッドへ投げない(§13)
  }
```

```
[タイムアウト経路]  ※「UI スレッドに到達しなかった」場合だけを救う
  └─ settleIfPending(applied = false)     ← 成功時のみ応答。LS は FS フォールバックへ。編集は未適用

[UI スレッド]  runEdit()
  if (!state.compareAndSet(PENDING, RUNNING)) return         ★ I1(既に SETTLED = 諦め済み)

  var documentMutated = false          // ドキュメントを変更した(可能性がある)
  var committed       = false          // 保存が成功した(または「書けていた」と判定できた)
  var target: EditTarget? = null       // 解決できた場合だけ非 null
  var editedBuffer: ITextFileBuffer? = null   // Buffered 経路で実際に編集したバッファ

  try {
    // ★★ 対象の解決は「エディタ優先」(§8・§11)。ここは UI スレッドでしか行えない
    target = EditTargetResolver.resolve(uri, findOpenEditorFor, findFilesForLocationURI)
               ?: throw UnresolvableTargetException()        ← E22。応答は finally が返す

    when (target) {
      // ───────── 経路 1: エディタが開いている(推測しない) ─────────
      is OpenEditor -> {
        document = target.provider.getDocument(target.input)   ← ★ エディタが表示しているそのもの
                     ?: throw UnresolvableTargetException()
        applyEdits(document)                                   ← 下の共通処理(documentMutated を立てる)
        saveVia { target.provider.saveDocument(monitor, target.input, document, /*overwrite=*/false) }
      }
      // ───────── 経路 2: エディタが無い(共有相手が居ないので分類の誤りは無害) ─────────
      is Buffered -> {
        val access = BufferAccess.of(target)
        access.connect(monitor)
        try {
          buffer   = access.current() ?: throw UnresolvableTargetException()
          document = buffer.getDocument()
          applyEdits(document)
          editedBuffer = buffer
          saveVia { buffer.commit(monitor, /*overwrite=*/false) }
        } finally {
          access.disconnect(monitor)   ← 参照数 0 なら dirty バッファは破棄される
        }
      }
    }
  } catch (Throwable t) {
    log(種別のみ)   ← CoreException / BadLocationException / MalformedTreeException /
                      SWTException / IllegalStateException / その他をすべてここで受ける
  } finally {
    // ★★ 退出処理。観測も含めて「必ず 1 回応答する」ことを構造で保証する
    var retained = committed        ← ★ 保存できていれば観測不要(既にディスクに載っている)
    var notice: Notice? = if (persistence == UNKNOWN) SAVE_RESULT_UNKNOWN else null   ← ★ E21
    try {
      if (!committed && documentMutated) {
        retained = when (target) {
          is OpenEditor -> true       ← ★ 観測不要。編集はエディタのドキュメントに残っている(§12.1)
          is Buffered   -> false      ← ★ エディタが無いので disconnect で破棄される
          null          -> false
        }
        if (retained) notice = SAVE_MANUALLY
        else if (target is Buffered && access?.current() === editedBuffer && editedBuffer != null)
          notice = STALE_BUFFER       ← disconnect が破棄しきれなかった場合だけ(E14)
      }
    } catch (Throwable t) {
      log(種別のみ)
      retained = committed          ← ★ 観測できなければ retention を主張しない
      notice   = if (documentMutated) STALE_BUFFER_UNKNOWN else notice
    } finally {
      try { settleFromRunning(applied = retained) } catch (Throwable t) { log(種別のみ) }  ★ I4。必ず 1 回
      try { notice?.let(::notifyUser) }            catch (Throwable t) { log(種別のみ) }  ★ 応答に影響しない
    }
  }

  // ───────── 共通処理 ─────────
  applyEdits(document):
    edits = LspTextEditConverter.toReplaceEdits(document, textEdits)   ← ★ 範囲外はここで検出(未変更)
    undoManager?.beginCompoundChange()
    try {
      try {
        MultiTextEdit(edits).apply(document)
        documentMutated = true
      } catch (MalformedTreeException e) {
        throw e                     ← checkIntegrity は変更前に投げる → documentMutated は false のまま
      } catch (Throwable t) {
        documentMutated = true      ← ★ 悲観的に真とする(下の「部分適用」を参照)
        throw t
      }
    } finally { undoManager?.endCompoundChange() }

  saveVia(save):                     ← ★ 「正常復帰」だけで永続化を判定しない(下記)
    try { save(); committed = true; persistence = MATCHED }
    catch (Throwable t) {
      log(種別のみ)
      persistence = persistedDespiteFailure(target, document, t)   ← MATCHED / NOT_MATCHED / UNKNOWN
      committed   = (persistence != NOT_MATCHED)                   ← ★ UNKNOWN も「書けた」側に倒す
      if (!committed) throw t                                      ← 未永続なら従来どおり失敗として扱う
    }
```

**設計上の要点**

- **ディスパッチスレッドをブロックしない**(N1)。`syncExec` はディスパッチスレッドと UI スレッドの相互待ちを
  作りうるため使わない。未完了 future を返し、UI ランナブル内で complete する。
- **★ `orTimeout` を使わない。** `orTimeout` は状態機械を経由せず future を直接例外完了させるため、
  (a) タイムアウト直後に UI ランナブルが `PENDING → RUNNING` に成功して**適用してしまう**、
  (b) UI が先に走っていても connect/commit が制限時間を超えれば**処理中に応答を失敗させ、LS を
  フォールバックさせる**、という 2 つの二重書き込み窓が残る。
- **★ セットアップ失敗も状態機械に載せる。** `onUiThread` の登録に成功したあとで `scheduleTimeout` が
  例外を投げた場合、ハンドラ側がエラー応答を返すと **LS が FS フォールバックを始める一方で、登録済みの
  ランナブルはまだ `PENDING → RUNNING` に成功して同じ編集を適用できる**。したがってセットアップ中の失敗は
  例外として外へ出さず、**`settleIfPending(false)` で原子的に `SETTLED` へ落とす**。こうすると登録済み
  ランナブルは CAS に負けて no-op になる。**登録順は「ランナブル → タイムアウト」**にする。逆順だと
  ランナブルの登録失敗を救うのがタイムアウト(10 秒後)になり、応答が無用に遅れる。
  CAS に負けた場合(= 既に `RUNNING`)は何もしない — 応答は走っている本人が返す(I2)。
- **★ 応答点を 1 つにし、`finally` の「先頭」に置く。** 外側の例外ハンドラを no-op にすると、状態が
  `RUNNING` のためタイムアウトも遷移できず **future が永久に未完了**になる。逆に無条件に `applied:false`
  を返すと、`MultiTextEdit.apply` 成功後の失敗で**適用済みのバッファへ FS フォールバックが重なる**。
  したがって外側は「握って `finally` に流す」だけにする。**さらに `finally` の中でも、通知より先に
  `settleFromRunning` を呼ぶ。** `finally` ブロック内で例外が出るとその `finally` の残りは実行されずに
  伝播するため、**通知を先に置くと通知の失敗が応答を丸ごと飛ばし、I4 が成立しない**。応答と通知は
  それぞれ独立に try/catch で封じ込める。
- **★★ 対象は「エディタ優先」で決める(第 8 版で変更。§8)。** 第 7 版までは URI からバッファを引き当てて
  いたが、**エディタの接続系列はエディタ入力の型の関数であって URI の関数ではない**(§8 の表)。したがって:
  - **エディタが開いていれば、そのエディタのドキュメントプロバイダから `IDocument` を取る。** これは
    エディタが表示しているまさにそのドキュメントであり、**推測が入らない**。保存も同じプロバイダの
    `saveDocument(monitor, input, document, overwrite = false)` で行う。
  - **開いていなければファイルバッファへ connect する。** 共有すべき相手が居ないので、
    ワークスペース内 / 外の分類を誤っても「同じファイルに正しく書く」という結果は変わらない
    (= データ不整合を生まない)。分類規則そのものは §11 に残す。
  - **この分岐は「推測が要らない側」と「推測しても害がない側」の境界に一致している。** #66 の欠陥
    (エディタのバッファを知らずにディスクへ書く)は、この構造では起こりえない。
  - `ITextFileBufferManager` が `fFilesBuffers`(`IPath` キー)と `fFileStoreFileBuffers`(`IFileStore` キー)の
    **互いに参照しない 2 マップ**を持つこと(javap。§付録)は変わらないので、**Buffered 経路の中では
    系列を混ぜない**(§11 の `BufferAccess`)。
- **★★ 退出処理は「観測 → 応答 → 通知」の 3 段で、応答は最も外側の `finally` から行う。**
  観測(`access.current()` / エディタ照会)を `settleFromRunning` より前に、しかもどの try/catch の外側で
  評価すると、**ワークベンチ破棄後などに例外が出た瞬間に状態が `RUNNING` のまま future が未完了になり、
  タイムアウトも応答できない**。したがって:
  1. **`retained` の初期値を `committed` にする。** 保存できていれば編集はディスクに載っており、
     **観測そのものが不要**である(観測失敗の影響を受けない)。
  2. **観測を try/catch で封じ込める。** 失敗したら `retained = committed`(= `false`)に倒す。
     **観測できないときに retention を主張しない**、という向きに倒す(§12.1 の E14 と同じ優先順位)。
  3. **その外側の `finally` から `settleFromRunning` を呼ぶ。** 応答と通知はそれぞれ独立に try/catch で
     封じ込める。これで**観測・応答・通知のどれが失敗しても応答は必ず 1 回返る**(I4)。
- **★★ 第 8 版では観測の負担が大きく減る。** 「編集が退出後も残るか」は経路で決まる:
  - **OpenEditor 経路**: 編集は**エディタが保持しているドキュメント**にある。エディタは UI スレッドでしか
    開閉せず、本ランナブルはイベントループを回さないので、**退出後も残ることが構成上わかる**
    (`bufferSurvived` の identity 観測は不要)。dirty なエディタを閉じれば Eclipse が保存を促す。
  - **Buffered 経路**: エディタが無いので、参照者はこちらの `connect` だけである。`disconnect` で
    **バッファは破棄される**(javap。§付録)。したがって `retained = false` が既定であり、
    観測が要るのは **`disconnect` が破棄しきれなかった場合の検出**(E14)だけになる。
  - 第 3〜7 版で必要だった「別の参照者が居るかどうか」の予測・観測は、**エディタ優先にしたことで
    そもそも問われなくなった**(エディタ以外の参照者はバックグラウンドで増減しうるが、その存在は
    「ユーザーが編集を見て保存できるか」とは無関係なので、retention の根拠にしない)。
- **★ 保存は必ず `overwrite = false`。** `commit(monitor, overwrite = true)` は Eclipse の out-of-sync 検査を
  迂回する(`ResourceTextFileBuffer.commitFileBufferContent` / `FileStoreTextFileBuffer.commitFileBufferContent`
  の先頭 = `if (!isSynchronized() && !overwrite) throw CoreException(WARNING, code 274)`。javap。§付録)。
  connect 後・保存前に外部プロセスがファイルを書き換えた場合、`true` では**その更新を古い内容で無言に
  上書きする** — 本設計が防ごうとしているデータ消失を、こちらの手で作ることになる。`false` にして
  競合を保存失敗として扱い、§12.1 の分岐に載せる。**OpenEditor 経路の `saveDocument` にも同じ理由で
  `overwrite = false` を渡す。**
  `isSynchronized()` は `fSynchronizationStamp == file.getModificationStamp() && file.isSynchronized(DEPTH_ZERO)`
  であり、**バッファが dirty なだけでは false にならない**。したがって R2 / AC4(ユーザーの未保存変更
  もろとも保存する)は `false` でもそのまま成立する。
- **★★ 保存の永続化判定を「正常復帰」に頼らない。** 保存処理は**内容を書いたあとにも例外を投げうる**:
  `ResourceTextFileBuffer.commitFileBufferContent` では `IFile.setContents`(offset 444)のあとに
  `IFile.revertModificationStamp`(488)と **`IPersistableAnnotationModel.commit(IDocument)`(535)** を呼ぶ
  (javap で確認。§付録)。`FileStoreTextFileBuffer` にも同じ `IPersistableAnnotationModel.commit` がある。
  `saveDocument` も内部でこれらを通る。どちらも `CoreException` を投げうるため、**ディスクには新しい内容が
  載っているのに保存が「失敗」に見える**状態が起こりうる。ここで `applied:false` を返すと、**LS が FS
  フォールバックで「元の座標の TextEdit」を既に編集済みのディスク内容へ再適用する** = 二重適用による
  ファイル破損(R1)。
  したがって保存の例外時は **`persistedDespiteFailure` で永続化されたかを判定する。★ 戻り値は
  `Boolean` ではなく 3 値**(`MATCHED` / `NOT_MATCHED` / `UNKNOWN`)にし、**判定は「安い順・確実な順」に
  4 段で行い、`UNKNOWN` へ倒すのは最後の 1 段だけ**にする:

  ```
  persistedDespiteFailure(target, document, thrown): MATCHED | NOT_MATCHED | UNKNOWN
    1. 既知の「書き込み前」失敗 → NOT_MATCHED      ★ 二重適用の可能性が無いので倒さない
         - out-of-sync 拒否: CoreException で plugin = "org.eclipse.core.filebuffers"、code = 274
           (= IResourceStatus.OUT_OF_SYNC_LOCAL。§付録で確認した唯一の生成箇所)
         - charset 構築失敗: CoreException の cause が
           UnsupportedCharsetException / IllegalCharsetNameException
    2. 対象が存在しない → NOT_MATCHED               ★ 存在しないファイルに永続化はありえない
         (connect 後に外部削除された場合など。読み取りが file-not-found で失敗する経路もここで吸収する)
    3. バイト列を比較 → 一致なら MATCHED / 不一致なら NOT_MATCHED
         expected = encode(document.get(), cs)
         候補は 2 つ: { expected, bom(cs) + expected }      ★ どちらかに一致すれば MATCHED
         cs は buffer.getEncoding() に commit と同じ UTF-16LE 調整を適用したもの
    4. 上記のいずれでも決まらない(対象は存在するが読み取り / エンコードが別の理由で失敗した等)
         → UNKNOWN                                   ★ ここだけが「破損を避けるために倒す」段

  呼び出し側: committed = (結果 != NOT_MATCHED)。★ UNKNOWN のときだけ「保存結果を確認できなかった」
              通知を出す(E21)。MATCHED では通知しない(ディスクは正しい)。
  ```

  - **★ デコードして比較せず、バイト列で比較する(第 8 版で変更)。** 第 7 版は「両側から先頭の `U+FEFF` を
    1 つ剥がす」としていたが、**UTF-8 BOM の直後に本文としての `U+FEFF` があるファイルで壊れる**:
    ディスク側は `FEFF FEFF …`、`document.get()` は `FEFF …` なので、両側から 1 つずつ剥がすと
    前者にだけ本文の `U+FEFF` が残り不一致になる。**永続化済みなのに `applied:false` を返して二重適用**
    という、この判定が防ごうとしている破損そのものを引き起こす。
    バイト列比較なら曖昧さが無い。commit が作るのは
    **`SequenceInputStream(ByteArrayInputStream(fBOM), encode(document.get()))`**(javap。§付録)であり、
    `fBOM` は「その encoding の BOM」か null のいずれかなので、**候補 2 つとの一致判定で必要十分**である。
    本文としての `U+FEFF` は `expected` の内部に符号化されるため、物理 BOM と取り違えようがない。
  - **本文は改行変換なしでそのまま書かれる。** commit は `CharBuffer.wrap(fDocument.get())` をエンコード
    するだけなので(javap)、比較に必要な正規化は無い。
  - **UTF-16 の二重 BOM に注意する。** Java の `"UTF-16"` エンコーダは BOM を自分で付けるため、Eclipse は
    `fBOM == BOM_UTF_16LE && "UTF-16".equals(encoding)` のとき encoding を `"UTF-16LE"` へ差し替える
    (javap で確認した分岐)。**`expected` を作るときも同じ差し替えを適用する。** 適用できない場合は
    3 段目で判定せず `UNKNOWN`(4 段目)へ落とす。
  - **`UNKNOWN` で「永続化済み」に倒す理由**: 未永続なのに `applied:true` を返すと編集は失われる
    (通知はする)が、永続済みなのに `applied:false` を返すと**二重適用でファイルが壊れる**。本設計は
    一貫して**破損 > 消失**の順で重く見ている(R1 = 高、R16 = 低)。**1 段目・2 段目でこの倒しの適用範囲を
    「書き込みが始まった可能性がある場合」に限定している**ことが要点で、書き込み前と分かる失敗
    (out-of-sync・charset・対象消失)は §12.1 の分岐(E12〜E14)へ流す。
  - **`isDirty()` / `isSynchronized()` では判定できない。** `fCanBeSaved` が false になるのは
    `commitFileBufferContent` が正常復帰したあとであり、同期スタンプは書き込みの前後どちらでも
    「同期している」を返しうるため、いずれも書き込みの有無と対応しない。
  - なお **dirty-state リスナの例外はこの窓を作らない**。`TextFileBufferManager.fireDirtyStateChanged` は
    `SafeRunner.run` でリスナを呼ぶため(javap。§付録)、リスナの例外は外へ出ない。
- **★ 適用前チェック(`isSynchronized()` を見て事前に諦める)は入れない。** 参照者が居る場合に諦めると、
  LS が FS 直書きへ落ちて「開いている dirty なエディタとディスクが無言で乖離する」= §4.1 の症状を
  こちらから作ってしまう。競合の検出は commit に任せ、判断は §12.1 に一本化する。
- **`MultiTextEdit` を使う理由**。LSP の複数 `TextEdit` の `range` は**すべて元ドキュメント座標**である。
  自前で逆順ソートして順次 `document.replace` するより、`org.eclipse.text.edits.MultiTextEdit` に
  `ReplaceEdit` を積んで一括 `apply` する方が安全で、**重なりを `MalformedTreeException` として検出できる**。
- **★ 部分適用の扱い(悲観)。** `TextEditProcessor.performEdits` は `dispatchCheckIntegrity()` →
  `dispatchPerformEdits()` の 2 段で、**`checkIntegrity` は重なりをドキュメント変更前に検出する**が、
  **`dispatchPerformEdits` には巻き戻しが無い**(javap で確認。§付録)。`checkIntegrity` が見るのは
  ツリーの整合性だけで、**ドキュメント長は見ない**。したがって:
  - 範囲外は `LspTextEditConverter` の**オフセット変換時**に `BadLocationException` として検出する
    (`apply` を呼ぶ前 = 未変更)。これが範囲外検出の正規の経路である(AC9・AC17)。
  - それでも `apply` の内部から `MalformedTreeException` 以外の例外が出た場合は、**部分適用され得たものと
    みなして `documentMutated = true` にする**。「変更していないのに `applied:true`」より
    「変更したかもしれないのに `applied:false`」の方が二重書き込みを招くため、悲観側に倒す。
- **`version` を照合しない**。常に `null` のため(A2)。VSCode 参照実装のバージョンチェックも同じ理由で
  常にスキップされている(§5.3)。
- **`failureReason` は LS に読まれない**(§5.1)。埋めるのはローカルログとテストのためであり、
  **内容にファイルパスや本文を含めない**(N3)。
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

開く処理は `OpenMrFileHandler.openEditorFor`(`:109-126`)の idiom を共通化して再利用するが、**ワークスペース内 / 外の判定は §11 と同じ `findFilesForLocationURI` で行う**: 一致すれば `IDE.openEditor(page, iFile)`、0 件なら `IDE.openEditorOnFileStore(page, EFS.getStore(uri))`。**`getFileForLocation` は使わない** — リンクリソースを見つけられず(§11)、`applyEdit` が `IFILE` 系で接続するファイルを `openFile` が file store 系で開いてしまい、**同じファイルに 2 つのバッファができる**ため。

**既存の呼び出し元(`OpenMrFileHandler`)の判定は変えない**(§20)。共通化するのは「開く」部分で、ルックアップは呼び出し側から渡す。

**範囲の reveal は実装しない**(LS が `selection` を送らないため。§5.2)。

### 9.4 F3 / F4

#### F3 `$/gitlab/copyText`

VSCode と同じく、クリップボードへ書いて「Copied to clipboard」を出す。ただし**既存 `ClipboardWriter` を
そのまま使うと、コピーが失敗しても通知が出る**。

現行実装(`ClipboardWriter.kt:9-18`)は `asyncExec` に登録して**即座に戻る `void`** であり、
`try/finally` は `dispose()` のためだけで **`setContents` の失敗を捕捉していない**。
クリップボードがビジー、または display が破棄された条件では、書き込みが失敗して例外が UI イベントループへ
漏れる一方、別途スケジュールされた通知は表示されうる。これは §13 の「失敗はログのみ」に反する。

**対応**: `ClipboardWriter` に**完了結果を返す API** を追加する(既存の `write(text)` は現行の呼び出し元
2 箇所のために残す)。

```kotlin
/** 書き込みの成否を返す。例外は内側で封じ込め、呼び出し元へ伝播させない。 */
fun writeChecked(text: String): CompletableFuture<Boolean>
```

- `setContents` を `try/catch` で包み、`SWTError` / `SWTException` / `RuntimeException` を捕捉して `false` で完了。
- `dispose()` は `finally` で必ず実行。
- display 破棄・`asyncExec` 自体の失敗も `false` で完了(future が未完了のまま残らない)。
- **通知は `true` で完了したときだけ出す。**

あわせて `ChatWebViewMessageHandlers.copyToClipboard`(`:72-78`)の重複実装を `ClipboardWriter` へ寄せる
(既存実装は `syncExec` かつ `try/finally` を持たず、例外時に `Clipboard` が dispose されない)。
**ただし `syncExec` → `asyncExec` の変更が webview 側の前提を壊さないかは §24 U3 で確定する。**

#### F4 `window/showDocument`

`external != false`(既定 true 扱い)なら `ExternalUrlPolicy` で `http`/`https` のみ許可してブラウザへ。
`external == false` は現行 LS では来ないが、来た場合は §9.3 の開く経路に回す。

**★ `BrowserLauncher` をそのまま使わない(N3 違反のため)。** `BrowserLauncher.open`(`:12-20`)と
`openChecked`(`:27-34`)は失敗時に **`logger.error("Failed to open URL: $url", e)`** と
**URL 全体および例外メッセージ**を記録する。`window/showDocument` の URI は**サーバ由来**で、
クエリやフラグメントに署名やトークンを含みうるため、ブラウザが URL を拒否した等の失敗時に
**認証情報がログへ永続化されうる**。

**対応**: URI を受け取らないログ経路を用意する。いずれかを実装時に選ぶ(§24 U7):

- (a) `BrowserLauncher` に「失敗を URI 抜きでログする」オーバーロードを追加する
  (例: `openChecked(url: String, logUrl: Boolean)`。既定 `true` で**既存 4 箇所の挙動は不変**)、または
- (b) `showDocument` 側に専用の起動ヘルパを持ち、`browserSupport.externalBrowser.openURL` を直接呼んで
  失敗は `e.javaClass.name` のみログする。

**既存呼び出し元(`OpenInGitLabHandler` / `OpenActiveFileHandler` / `DownloadArtifactsHandler`)の挙動は
変えない。** あちらはユーザー起点の GitLab URL であり、サーバ由来の任意 URI とはリスクの性質が違う。
**失敗時のログキャプチャで URI と例外メッセージが含まれないことをテストで固定する**(AC14)。

**応答について**: `ShowDocumentResult(success)` を返す。**現行 LS の zod 検証は必ず失敗するが、それは
避けられない**(§5.5 の「応答契約について」を参照)。

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
  // ★ 開始タイムアウト。実時間を待たずに発火させられることがテストの必須要件(§23・U8)
  // ★ 対象の解決(§11)。エディタ照会を含むため UI スレッド上でのみ呼ぶ
  private val findOpenEditorFor: (URI) -> Pair<IEditorInput, IDocumentProvider>? = { defaultEditorLookup(it) },
  private val findFilesForLocationURI: (URI) -> Array<IFile> =
      { ResourcesPlugin.getWorkspace().root.findFilesForLocationURI(it, INCLUDE_HIDDEN or INCLUDE_TEAM_PRIVATE_MEMBERS) },
  // ★ 保存失敗時のユーザー通知。回数と文言を検証するためテストから差し替える(AC16 / AC20 / AC22)
  private val notifyUser: (String) -> Unit = { NotificationUtils.showWarning(it) },
)
```

**この 6 つの seam がテスト可能性の前提である**(§23)。`scheduleTimeout` / `findOpenEditorFor` / `findFilesForLocationURI` / `notifyUser` を注入できない実装は、AC13 / AC16 / AC18 / AC20 / AC22 / AC26 が「10 秒待つテスト」または「検証できない副作用」になるため受け入れない。

`findOpenEditorFor` の既定実装は、開いている全ウィンドウ → 全ページ → `getEditorReferences()` を走査し、`IEditorInput` が対象 URI を指すものを探して**その入力とドキュメントプロバイダを返す**(`IFileEditorInput` は `IFile` の `getLocationURI()` を、`IURIEditorInput` は `getURI()` を比較)。**参照を解決するために `getEditor(true)` でエディタを復元してはならない**(未復元エディタの強制復元は重い副作用になる)。未復元エディタの扱いは §24 U9。
## 11. データモデル

```kotlin
data class OpenFileParams(val filePath: String?)
data class CopyTextParams(val text: String?)
```

Gson 経由でデシリアライズされるため、**非 null 宣言は迂回されうる**(issue #47 と同型の既知問題)。したがって両フィールドとも **nullable で宣言し、ハンドラ側で null / blank を弾く**。

`EditTargetResolver` の出力:

```kotlin
sealed interface EditTarget {
  // 経路 1: エディタが開いている。推測しない(§8)
  data class OpenEditor(
    val input: IEditorInput,
    val provider: IDocumentProvider,
  ) : EditTarget

  // 経路 2: エディタが無い。共有相手が居ないので分類の誤りは無害
  sealed interface Buffered : EditTarget {
    data class InWorkspace(val path: IPath) : Buffered      // IFile.getFullPath() + LocationKind.IFILE
    data class External(val fileStore: IFileStore) : Buffered
  }
}
```

**解決順序(UI スレッド上で行う)**:

```
1. 開いている全ウィンドウ → 全ページ → getEditorReferences() を走査し、
   ★ 復元済みのエディタだけを対象に、入力が対象 URI を指すものを探す。
   照合は createFileInfo と同じアダプタ優先順位で行う(§8 の ①②③):
     ① input.getAdapter(IFile.class)      → その IFile の getLocationURI() を比較
     ② input.getAdapter(ILocationProvider.class) が ILocationProviderExtension
                                          → getURI(input) を比較
     ③ 同アダプタの getPath(input)        → ファイルシステムパスとして URI へ正規化して比較
   見つかれば OpenEditor(input, editor.getDocumentProvider())         ← ★ ここで終わり。分類しない

   ★ 未復元の IEditorReference は「開いていない」として扱う(2 へ進む)。§24 U9 で確定。
     理由: エディタ本体が生成されていなければ createFileInfo は実行されておらず、
           そのエディタは「ファイルバッファを 1 つも保持していない」。したがって
           2 の経路で作るバッファが唯一のバッファになり、競合しない。
           getEditor(true) による強制復元はしない(エージェントの編集がユーザーの
           見ていないエディタを開く、という重い副作用になるため)。

2. 見つからなければバッファ経路。★ まず「既にバッファが存在するか」を全系列で調べる:
     candidates = findFilesForLocationURI(uri,
                    IContainer.INCLUDE_HIDDEN or IContainer.INCLUDE_TEAM_PRIVATE_MEMBERS)
     probe = 次のうち非 null のもの(存在すれば join する)
       (a) getTextFileBuffer(candidates[i].getFullPath(), LocationKind.IFILE)
       (b) getTextFileBuffer(physicalPath(uri),           LocationKind.NORMALIZE)
       (c) getFileStoreTextFileBuffer(EFS.getStore(uri))
     probe が 1 つ以上見つかれば → その系列の Buffered を使う(= 既存バッファに join する)
     見つからなければ分類して新規に作る:
       candidates が 1 件以上 → Buffered.InWorkspace(candidates[0].getFullPath())
       0 件                   → Buffered.External(EFS.getStore(uri))

3. どれも決まらなければ null(呼び出し側が E22 として applied:false にする)
```

**★ 2 の probe を入れる理由(第 9 版で追加)**: エディタが無くても、**バックグラウンド処理が別の系列で
バッファを接続し、dirty のまま保持している**ことがありうる(§16)。そこへこちらが別系列で新しいバッファを
作ると、**同じファイルに 2 つのバッファ**ができ、あとで相手が保存した時点でこちらの編集が上書きされる。
**probe して join すれば、こちらの編集は相手のバッファに入る**ので、相手の保存はこちらの編集ごと書く。
`connect` は参照カウントを +1 するため、**join したあとは相手の `disconnect` でバッファが破棄されることもない**。

**★ probe が複数見つかった場合**は「先に見つかった 1 つ」でよい。同一ファイルに対して複数系列のバッファが
同時に存在すること自体は Eclipse プラットフォーム上ありうるが、その場合はどれを選んでも他方は古いまま
残る(R18 と同じ性質で、LSP の `WorkspaceEdit` はどのバッファかを表現できない)。

**★ 残余(R22)**: probe と `connect` の間に、バックグラウンドが別系列で新しくバッファを作る窓は残る。
バックグラウンドの接続は UI スレッドに同期しないため、クライアント側では閉じられない。**受容する。**

**★ 1 が 2 に優先することが本設計の要点である(§8)。** エディタが開いている場合、その接続系列は
**エディタ入力の型の関数**であって URI の関数ではないため、2 の規則では一般に再現できない。
1 で決着させることで再現の必要がなくなる。

**★ 2 の分類が多少ずれても害が無い理由**: probe で既存バッファが見つからなければ、共有すべき `IDocument` が
存在しない。`InWorkspace` と `External` のどちらで接続しても、書き込み先の実ファイルは同じである。
したがって **2 の分類は「正しい API 系列で 1 つのバッファを作る」ためだけのもの**で、
第 5〜7 版で問題になった「エディタと別バッファになる」帰結は生じない。
複数一致時は先頭でよい(エディタが無いので「開いているものを選ぶ」余地が無い)。

**★ `Buffered.InWorkspace.path` は `IFile.getFullPath()`(= `/project/dir/file` 形式のワークスペース絶対パス)
であって、ファイルシステム上のパスではない。** `LocationKind.IFILE` はワークスペース絶対パスを要求する。

**`Buffered` は `ITextFileBufferManager` への 3 操作を提供する**(2 つの管理マップを混ぜないため。§付録):

```kotlin
interface BufferAccess {
  fun connect(monitor: IProgressMonitor)     // connect(path, IFILE, m) / connectFileStore(store, m)
  fun current(): ITextFileBuffer?            // getTextFileBuffer(path, IFILE) / getFileStoreTextFileBuffer(store)
  fun disconnect(monitor: IProgressMonitor)  // disconnect(path, IFILE, m) / disconnectFileStore(store, m)
}
```

URI → パス / ファイルストア変換は **`file:///path` と `file:/path` の両形式を受け付ける**(§5.1 末尾)。`java.net.URI.getPath()` はどちらも `/path` を返すため、これを基礎にする。Windows の `file:/C:/…` に対する扱いは §24 U1 の未決事項。

## 12. トランザクション境界

**1 リクエスト = 1 `TextDocumentEdit` = 1 トランザクション**(A1 により LS は常に 1 件しか送らない)。境界は「バッファ connect から disconnect まで」。

原子性は `MultiTextEdit.apply(document)` の `checkIntegrity` が担保する。**編集の重なりは 1 つも適用せずに `MalformedTreeException` になる**。ただし `checkIntegrity` が検査するのは**ツリーの整合性だけでドキュメント長は見ない**ため、範囲外は `LspTextEditConverter` のオフセット変換時に(= `apply` を呼ぶ前に)検出する。それでも `apply` の内部から例外が出た場合は部分適用され得たものとして扱う(§9.1 の「部分適用の扱い(悲観)」)。

### 12.1 保存に失敗したときの方針【確定・旧 U2】

**判断基準は「その編集がこのランナブルの退出後も残るか」の一点である。** 残るなら `applied: true` を返して
LS の FS フォールバックを抑止し、残らないなら `applied: false` を返してフォールバックに委ねる(I5)。

**第 8 版では、この判断が経路で決まる**(第 3〜7 版のような参照者の予測・観測は不要になった):

| 経路 | 状況 | 編集の行方 | 応答 | ユーザー通知 |
|---|---|---|---|---|
| — | 保存成功(または「書けていた」と判定できた = `MATCHED`) | ディスクに載る | `applied: true` | なし |
| — | 保存の結果を判定できない(`UNKNOWN`) | ディスクに載っている可能性が高い | `applied: true` | **出す**(保存結果を確認できなかったこと) |
| **OpenEditor** | 適用済み・保存失敗 | **エディタのドキュメントに残る。** `*` が出て内容が見え、undo が効き、手動保存で確定できる。閉じようとすれば Eclipse が保存を促す | **`applied: true`** | **出す**(保存できなかったこと・エディタで保存すれば復旧できること) |
| **Buffered** | 適用済み・保存失敗 | `disconnect` で**破棄される**(エディタが無いので参照者はこちらだけ。javap。§付録) | **`applied: false`**(LS が FS 経由で書き直す) | 出さない(ユーザーが取るべき行動が無い) |
| **Buffered** | 適用済み・保存失敗・**`disconnect` が破棄しきれなかった** | 登録済みのまま dirty で残る。ファイルを開けばそのバッファに接続されて内容が見える | **`applied: false`**(編集をディスプへ確実に届けることを優先) | **出す**(未保存の編集を含むバッファが残っている可能性) |
| — | 適用前の失敗(解決 / 変換 / `checkIntegrity` / connect) | 変更していない | `applied: false` | 出さない |

**OpenEditor 経路で観測が要らない理由**: エディタは UI スレッドでしか開閉せず、本ランナブルは
イベントループを回さない。したがって**退出時点でエディタが生きていることは構成上わかる**。
第 3〜7 版で必要だった `bufferSurvived` の identity 観測は、この経路では不要である。

**Buffered 経路で「破棄される」の根拠**(javap で確認。§付録):

- `ResourceFileBuffer.connect()` は `fReferenceCount` を +1、`disconnect()` は −1 し、0 以下で `disconnected()` を呼ぶ。
- `TextFileBufferManager.disconnect(...)` は `isDisconnected()` が真なら管理マップから
  **remove → `fireBufferDisposed` → `dispose()`** する。**保存は行わない。**

エディタが無い以上、こちらの `connect` が唯一の参照になる(バックグラウンドの一時的な参照者が居ることは
ありうるが、**それは「ユーザーが編集を見て保存できるか」とは無関係**なので retention の根拠にしない)。
したがって `applied: false` を返して LS に書き直させるのが、編集を失わない唯一の選択である。

**巻き戻さない理由**(旧版から維持):

- 保存の失敗原因が**読み取り専用・ディスク満杯・権限変更**であれば、**FS 直書きも同じ理由で失敗する**。
  巻き戻した分だけ編集が失われ、得るものが無い。
- 逆に FS 直書きが成功した場合、**エディタは元に戻り、ディスクだけが新しい**状態になる。
  これは §4.1 で解消しようとしている症状そのものである。
- さらに「巻き戻しにも失敗する」という第 2 の失敗経路を作る。
- **参照実装と同じ着地である。** VSCode の `SaveFileMiddleware`(`save_file_middleware.ts:57-71`)は
  `await document.save()` の失敗を `log.error` で記録するだけで **`result.applied` を変更しない**。
- `applied: true` は「**クライアントが編集を適用した**」の意味であり、嘘ではない。LSP の
  `ApplyWorkspaceEditResponse` は永続化の成否を表す欄を持たない。
- Buffered 経路では**巻き戻す必要が無い**。`disconnect` がバッファごと破棄するため、
  明示的な undo を実行しなくてもディスクとバッファのどちらにも編集は残らない。

**代償(受容する)**:

- `applied: true` を返したケースではディスクが一時的に古いままになるため、エージェントが直後にビルドや
  テストを走らせると古い内容を読む。ただし LS 自身の `getText` は LSP 優先(= こちらのドキュメント)なので、
  **LS から見た内容は一貫している**。
- `applied: false` を返したケースでは LS が FS 直書きへ落ちる。**out-of-sync が原因で保存に失敗した
  ときは、その外部変更が FS 直書きで上書きされうる**。クライアント側では止められない(§9.2 と同じ理由)。
  **受容する制限として R13 に記載。**

**この方針は失敗を握り潰さない。** 通知が出ない唯一のケースは「バッファが破棄され、LS が書き直す」経路で、
そこでは**ユーザーが取るべき行動が無い**。それ以外は通知とログで必ず可視化する(§13・§18)。

## 13. エラー処理

**応答は「編集が退出後も残るか」で決まる**(§12.1 の I5)。第 8 版ではそれが**経路で決まる**ため、
下表はその規則を事象ごとに展開したものであり、**表と規則が食い違ったら規則が正**である。

| # | 事象 | 段階 | 応答 | ログ / 通知 |
|---|---|---|---|---|
| E1 | `documentChanges` が null / 空(`changes` のみを含む場合) | 事前検証 | `applied:false` | INFO(件数のみ) |
| E2 | `ResourceOperation` が含まれる | 事前検証 | `applied:false` | WARN(種別のみ) |
| E3 | `SnippetTextEdit` が含まれる | 事前検証 | `applied:false` | WARN(種別のみ) |
| E4 | URI が `file:` 以外 / 解析不能 | 事前検証 | `applied:false` | WARN(スキーマ名のみ。**URI 本体は出さない**) |
| E5 | **セットアップ失敗**(`onUiThread` 登録失敗 / `scheduleTimeout` の `RejectedExecutionException` / display 破棄) | セットアップ | `applied:false`(`settleIfPending`。既に `RUNNING` なら**応答しない**) | WARN(種別のみ) |
| E6 | 開始タイムアウト(UI スレッドに到達しなかった) | セットアップ後 | `applied:false`(`settleIfPending`) | INFO(件数のみ) |
| E7 | `CoreException`(**connect 失敗** = Buffered 経路) | 適用前 | `applied:false` | WARN(種別のみ) |
| E8 | `BadLocationException`(範囲がドキュメント外。**オフセット変換時**に検出) | 適用前 | `applied:false` | WARN(種別のみ) |
| E9 | `MalformedTreeException`(編集の重なり。`checkIntegrity` が変更前に検出) | 適用前 | `applied:false` | WARN(種別のみ) |
| E10 | `apply` の内部から `MalformedTreeException` 以外の例外 | **適用済み扱い(悲観)** | E12〜E14 と同じ規則で分岐 | ERROR(種別のみ) |
| E11 | **保存成功、または `persistedDespiteFailure` が `MATCHED`** | 適用後 | **`applied:true`** | 保存成功はログなし / `MATCHED` は WARN(種別のみ)。**どちらも通知しない** |
| **E12** | **保存失敗・OpenEditor 経路** | 適用後 | **`applied:true`** | WARN(種別のみ)+ **ユーザー通知 1 回**(エディタで保存すれば復旧できる) |
| **E13** | **保存失敗・Buffered 経路**(`disconnect` でバッファが破棄された) | 適用後 | **`applied:false`**(LS が FS 経由で書き直す) | WARN(種別のみ)。**通知しない**(ユーザーが取るべき行動が無い) |
| **E14** | **保存失敗・Buffered 経路だが `disconnect` が破棄しきれず、同一インスタンスが残存** | 適用後 | **`applied:false`** | WARN(種別のみ)+ **ユーザー通知 1 回**(未保存の編集を含むバッファが残っている可能性) |
| E15 | **`disconnect` の失敗** | 退出時 | **応答を変えない。** 残存は E14 の観測に反映される | WARN(種別のみ) |
| E16 | **`notifyUser` の失敗** | 退出時 | **応答に影響しない**(応答は通知より先に確定させる) | WARN(種別のみ) |
| E17 | `settleFromRunning`(= `future.complete`)から例外 | 退出時 | **future は既に完了している**ので I4 は成立。以降の通知処理は継続する | ERROR(種別のみ) |
| E18 | UI ランナブル内の想定外の `Exception` / `SWTException` / `IllegalStateException` | どこでも | **`finally` の `settleFromRunning` が必ず 1 回応答する**(I4) | ERROR(種別のみ) |
| E19 | **退出時の観測の失敗**(`access.current()` などが例外) | 退出時 | **`retained = committed` に倒す**。**応答は必ず返る** | WARN(種別のみ)+ 適用済みなら**残存不明の通知 1 回** |
| **E20** | **保存が例外を投げたが、内容はディスクに書けていた**(`persistedDespiteFailure` = `MATCHED`) | 適用後 | **`applied:true`** | WARN(種別のみ)。**通知は出さない**(ディスクは正しい) |
| **E21** | **保存が例外を投げ、永続化されたかを判定できない**(`UNKNOWN` = 判定の 4 段目) | 適用後 | **`applied:true`** | WARN(種別のみ)+ **ユーザー通知 1 回**(保存結果を確認できなかったこと)。★ `MATCHED` と区別できるよう判定は 3 値で返す |
| **E21b** | **保存が「書き込み前」に失敗したと判明した**(`NOT_MATCHED` = out-of-sync code 274 / charset 構築失敗 / 対象が存在しない) | 適用後 | **`committed = false`** のまま §12.1 の分岐(E12〜E14)へ流す。**二重適用の可能性が無いので倒さない** | WARN(種別のみ) |
| **E22** | **UI スレッドでの対象解決に失敗した**(エディタ照会 / `findFilesForLocationURI` / ファイルストア生成の失敗、いずれにも当たらない) | 適用前 | `applied:false`(`documentMutated = false` のまま退出処理へ流れる) | WARN(種別のみ) |
| **E23** | **OpenEditor 経路で `provider.getDocument(input)` が null または例外** | 適用前 | `applied:false`(E22 と同じ扱い) | WARN(種別のみ) |

**E18 に no-op は無い。** `RUNNING` に入ったあとは、タイムアウトもセットアップ失敗も応答を返せない(I2)。
外側の例外経路を no-op にすると **future が永久に未完了**になるため、外側は握って `finally` に流すだけにし、
応答値は経路と観測から決める。

**★ `finally` の中の順序が意味を持つ(E16)。** `finally` ブロック内で例外が出ると、その `finally` の
残りは実行されずに例外が伝播する。**通知を応答より先に置くと、通知の失敗が応答を丸ごと飛ばして
状態が `RUNNING` のまま future が未完了になる**。したがって **`settleFromRunning` を先に呼び、
応答と通知をそれぞれ独立に try/catch で封じ込める**。

`$/gitlab/openFile` / `$/gitlab/copyText` は notification のため応答しない。失敗はログのみ。
**`$/gitlab/copyText` の完了通知は、クリップボード書き込みの成功が確定してからのみ出す**(§9.4)。

**N3 の徹底**: ログには URI・パス・ファイル本文・`newText` を出さない。例外は `e.javaClass.name`。`?: e.javaClass.name` フォールバックの有無がブランチ全体で不統一という既知の申し送り(issue #52 の 6)があるため、**本サイクルで新規に追加する箇所は統一した形で書く**。**ユーザー通知の文面にもファイルパスを入れない**(対象の識別はエディタのタブと `*` で足りる)。

**★ 既存ヘルパの再利用は N3 を満たすとは限らない。** `BrowserLauncher.open` / `openChecked` は
失敗時に `logger.error("Failed to open URL: $url", e)` と **URL 全体と例外メッセージを記録する**
(`BrowserLauncher.kt:17` / `:32` で確認)。`window/showDocument` の URI は**サーバ由来で、クエリや
フラグメントに署名やトークンを含みうる**ため、この経路をそのまま使うと N3 に違反する。対応は §9.4。

## 14. タイムアウトとリトライ

**`applyEdit` は `orTimeout` を使わない**(§9.1)。`orTimeout` は状態機械を経由せず future を直接完了させる
ため、二重書き込みの窓を閉じられない。代わりに**開始タイムアウト**を自前でスケジュールし、
`compareAndSet(PENDING, SETTLED)` に成功したときだけ `applied:false` で応答する。

- **開始タイムアウトの意味**: 「UI スレッドに**到達しなかった**」場合だけを救う(ワークベンチ停止、
  display 破棄、UI スレッドの恒久停止)。値は house style の `TIMEOUT_IN_SECONDS = 10L` に合わせる。
- **★ 適用開始後(`RUNNING`)はタイムアウトしない。** connect / commit がどれだけ長引いても応答を
  失敗させない。諦めた瞬間に LS がディスク直書きを始め、こちらのバッファ適用と衝突するため。
  **「応答が遅れること」より「二重に書かれること」の方が重い**、というのが本設計の優先順位である。
- **★ タイムアウトの登録に失敗した場合も応答は必ず出る。** `scheduleTimeout` が例外を投げたら
  `settleIfPending(false)` で即座に確定させる(§9.1 の `arm()`)。この経路を例外としてディスパッチ
  スレッドへ返してはならない — 返すと LS がフォールバックを始める一方で、登録済みの UI ランナブルが
  まだ適用できてしまう。
- **リトライしない。** `applied:false` を返した時点で LS が FS フォールバックへ落ちる(§5.1)ため、
  クライアント側の再試行は二重書き込みを招く。

**`showDocument`** は編集を伴わないので `orTimeout(10, SECONDS)` で構わない(タイムアウトしても
LS 側は info ログを出すだけ。§5.5)。

## 15. 冪等性

`applyEdit` は**冪等ではない**。同一の `TextEdit` を 2 回適用すれば内容は 2 回変わる。LS 側にリトライ機構は無く(フォールバックは別サービスへの 1 回の切り替え)、同一リクエストが再送されることはない。

したがって本設計が守るべきは冪等性ではなく **「1 リクエストにつき高々 1 回しか適用しない」** ことであり、これを §9.1 の状態機械の不変条件 I1 / I2 / I5 で担保する。

**「高々 1 回」が破れる経路は 4 つあり、すべて状態機械で閉じている**:

| 破れ方 | 閉じ方 | AC |
|---|---|---|
| タイムアウトが応答したあとに UI ランナブルが適用する | UI 側は `PENDING → RUNNING` の CAS に失敗するので適用しない(I1) | AC10 |
| 適用中にタイムアウトが応答し、LS が FS 直書きを始める | タイムアウトは `PENDING` からしか遷移できないので `RUNNING` 中は応答しない(I2) | AC13 |
| **セットアップ失敗のエラー応答で LS がフォールバックする一方、登録済みランナブルが適用する** | **セットアップ失敗も `settleIfPending` で `SETTLED` に落とす。CAS に負けた(= 既に `RUNNING`)なら応答しない**(I2) | **AC18** |
| **適用後の例外で `applied:false` を返し、適用済みバッファに FS 直書きが重なる** | **応答は退出時の観測から決まる `retained` による。適用済みかつ `editorAttached` かつ `bufferSurvived` なら `applied:true`**(I5・§12.1) | **AC19** |

**あわせて「応答が 1 度も返らない」経路も閉じている**: `finally` の中では `settleFromRunning` を通知より**先に**呼び、応答と通知をそれぞれ独立に try/catch で封じ込める。`finally` 内の例外はその `finally` の残りを飛ばして伝播するため、順序を逆にすると通知の失敗が応答を飛ばし、`RUNNING` のまま future が未完了になる(I4・§13 の E16・AC23)。

## 16. 並行処理

- **ハンドラは lsp4j のディスパッチスレッドで呼ばれる。** UI 操作は必ず `onUiThread` seam を経由する。
- **複数の `applyEdit` が同時に来た場合**: 各リクエストは独立に UI スレッドへ post され、UI スレッドは単一なので**適用自体は直列化される**。同一ファイルに対する連続適用は、後続が先行の結果を含むドキュメントに対して適用されるが、LS は `getText`(LSP 優先)で最新のバッファ内容を読んでから編集を計算するため、**通常は整合する**。ただし LS が読んだ後・適用前に別の適用が挟まる窓は理論上存在する。この窓は VSCode 参照実装にも同じく存在し、`version: null` である以上クライアント側では検出できない。**受容する制限として §25 R3 に記載。**
- **★★ ファイルバッファの参照者は UI スレッド以外からも変化する(第 3 版の記述を撤回)。第 8 版ではこれを retention の根拠にしない**(§12.1)。
  `ITextFileBufferManager` は管理マップの `synchronized` だけで UI スレッドを要求せず、バックグラウンドジョブ
  (検索・リファクタリング・ビルド等)や `commit` 中に同期実行されるリソース変更リスナが connect / disconnect
  しうる。さらに `TextFileBufferManager.disconnect` は **map からの remove だけがロック内**で、
  `fireBufferDisposed` / `dispose()` は**ロック外**である(javap。§付録)。
  したがって第 3 版の「UI スレッドを手放さないから参照者集合は変化しない」は**成立しない**。
  **参照者の存続を予測せず、退出時に観測する**(§9.1・§12.1)。
- **★ 一方、エディタの開閉は UI スレッドでしか起こらない。これが第 8 版の中核である**(§8)。 `IWorkbenchPage` の editor 操作は
  display スレッドを要求するため、UI ランナブル内でイベントループを回さない限り `editorAttached` の
  判定は退出後も有効である。**この不変条件を壊す変更(進捗ダイアログの表示、`syncExec`、
  ネストしたイベントループ)を UI ランナブル内に入れてはならない。**
- **バッファの connect / disconnect は対で行う**(`finally`)。同一ファイルが並行して connect されても `ITextFileBufferManager` は参照カウントで管理するため安全。
- **状態変数の可視性**: `documentMutated` / `committed` / `editedBuffer` は **UI ランナブル内のローカル変数**であり、UI スレッドだけが読み書きする。スレッド間で共有するのは `AtomicReference<State>` と future だけである。
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
| UI スレッドが詰まる / セットアップに失敗する | 開始タイムアウトまたは `settleIfPending` が `applied:false` で確定 → FS 直書き | 同上 |
| **commit が失敗し、対象ファイルがエディタで開かれている**(E12) | ディスクは古いまま、編集はエディタに dirty で残る。**FS フォールバックは抑止される** | **通知に従ってユーザーが手動保存する。** 失敗原因(読み取り専用・容量不足・外部変更)を解消してから保存するか、undo で破棄する。エディタを閉じようとすれば Eclipse が保存を促すので、無言で失われることはない。**このケースだけは現行の挙動へ縮退させない** — 縮退させるとエディタとディスクが無言で乖離するため(§4.1) |
| **commit が失敗し、バッファが破棄された**(E13) | 編集は残らず、`applied:false` により LS が FS 直書きへ落ちる | **自動的に現行の挙動へ縮退する。** FS 直書きも失敗する場合はエージェント側にエラーが返る |
| **commit が失敗し、バッファは残存するがエディタは開いていない**(E14。`disconnect` 失敗を含む) | `applied:false` により**編集はディスクへ届く**。一方、未保存の編集を含むバッファが登録されたまま残る | **通知に従って対象ファイルを開く**と、Eclipse は登録済みのそのバッファに接続するため、未保存の編集がそのまま見える。保存すれば確定、破棄したければエディタを閉じて保存しないを選ぶ。**放置した場合の最悪ケースは、後からそのバッファが保存されて LS の書き込みを上書きすること**(R16) |
| 誤った編集が適用された | ファイル内容が壊れる | **エディタの undo で戻せる**(§9.1 の compound change)。保存済みならローカル履歴(Eclipse の Local History)からも復元可能 |
| `openFile` / `copyText` が失敗 | 無言の no-op(現行と同じ) | ログで種別を確認 |

**重要**: 失敗経路のうち **「編集がこちらに残らない」ものはすべて現行の挙動(FS 直書き)へ縮退する**。縮退させないのは「適用済みの編集がエディタのバッファに残っており、ユーザーが見て保存できる」場合(E12)だけで、ここは**通知でユーザーへ引き渡す**。**無言の失敗モードを増やさない**ことが設計の基本方針である。

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
| `LspTextEditConverter` | 複数 `TextEdit` を `MultiTextEdit` に積んだときの一括適用結果。**重なりで `MalformedTreeException` が出て 1 つも適用されないこと**。**範囲外は `apply` に到達する前(オフセット変換時)に弾かれること** |
| `EditTargetResolver` | `file:///path` と `file:/path` の両形式。`file:` 以外の拒否。ワークスペース内 / 外の判定。**ワークスペース内は `InWorkspace(IPath)`、外は `External(IFileStore)` を返すこと**(§11) |
| `ExternalUrlPolicy` | `http`/`https` の許可、`file:`/`javascript:`/相対/空文字の拒否 |
| `WorkspaceEditApplier` | 注入した偽 `onUiThread` とインメモリ `Document` で: 正常適用 / `ResourceOperation` 拒否 / `SnippetTextEdit` 拒否 / `changes` のみ拒否 |
| **`WorkspaceEditApplier` の状態機械(§9.1)** | **競合 1**: 開始タイムアウトが先に `SETTLED` へ遷移したあとに UI ランナブルが走っても**適用されない**(AC10)<br>**競合 2**: UI が `RUNNING` に入ったあとに開始タイムアウトが発火しても**応答を返さない**(AC13)<br>**競合 3**: `RUNNING` 中に commit が長引いても future が完了しないこと<br>**競合 4**: `onUiThread` 登録成功後に `scheduleTimeout` が例外を投げたとき、**future が `applied:false` で 1 回だけ完了し、後から走るランナブルがドキュメントを変更しない**(AC18)<br>**競合 5**: 同じく `scheduleTimeout` が例外を投げたが**ランナブルが既に `RUNNING`** の場合、セットアップ側は応答せず、**応答はランナブルの結果になる**(AC18) |
| **経路の選択(§8・§11・AC28)** | 注入した `findOpenEditorFor` で **OpenEditor 経路 / Buffered 経路**を切り替え、次を固定する:<br>**(a) エディタが開いている** → `provider.getDocument(input)` に適用され、保存は `provider.saveDocument(..., overwrite=false)`。**`findFilesForLocationURI` も `BufferAccess` も呼ばれない**<br>**(b) 開いていない** → `findFilesForLocationURI` の結果で `InWorkspace` / `External` を選び、対応する API 系列だけが呼ばれる |
| **エディタ同定(§11・AC29)** | 注入したエディタ一覧に対し、入力が `IFile` / `ILocationProviderExtension` / `ILocationProvider` の**どのアダプタで対象を指していても** `OpenEditor` になること。**未復元参照は `OpenEditor` にならず、`getEditor(true)` が呼ばれない**こと |
| **既存バッファの join(§11・AC30)** | 注入した `bufferManager` の 3 系列のうち 1 つだけに既存バッファを置き、**その系列で `connect` されること**(新規に別系列を作らないこと)。どれも無いときだけ分類が使われること |
| **退出時の判定(§12.1)** | **(a) OpenEditor + 保存失敗** → `applied:true` + 保存を促す通知 1 回(AC16)<br>**(b) Buffered + 保存失敗 + `disconnect` 後に破棄** → **`applied:false`・通知なし**(AC20)<br>**(c) Buffered + 保存失敗 + 同一インスタンスが残存** → **`applied:false`** + 残存を知らせる通知 1 回(AC22)<br>**適用前**の失敗は `applied:false`・通知なし(AC17) |
| **`disconnect` 失敗(E15)** | `disconnect` が例外を投げても **応答が観測どおりに決まる**こと。残存していれば (a) / (c)、破棄されていれば (b) と同じ結果になること(AC22) |
| **適用後の例外(§9.1・§13 の E10 / E18)** | `endCompoundChange` / `disconnect` / その他が例外を投げても **future が必ず 1 回完了する**こと、かつ応答が観測に一致すること(AC19) |
| **通知失敗の封じ込め(E16・AC23)** | 注入した `notifyUser` が例外を投げても、**future は既に `retained` で完了しており、例外が UI ランナブル外へ漏れない**こと。**応答が通知より先に確定していること**(通知を投げさせたうえで future の完了を確認する) |
| **観測失敗の封じ込め(E19・AC19)** | 注入した `access.current()` / `findOpenEditorFor` が例外を投げても **future が必ず 1 回完了する**こと。**commit 成功時は `applied:true`**(観測が呼ばれないこと自体も検証 = AC25)、**未 commit なら `applied:false` + 残存不明の通知**になること |
| **API 系列の選択(§9.1・§11)** | 注入した `bufferManager` で、**ワークスペース外の対象は `connectFileStore` / `getFileStoreTextFileBuffer` / `disconnectFileStore` だけ**が呼ばれ、`connect(IPath, LocationKind, …)` 系が**一度も呼ばれない**こと。ワークスペース内は逆であること(AC24) |
| **Buffered 経路の分類(§11・AC26)** | エディタが開いていない前提で、注入した `findFilesForLocationURI` の 0 件 / 1 件以上を切り替え、**選ばれた `EditTarget` と、渡されたパスがワークスペース絶対パスであること**、**2 引数版が `INCLUDE_HIDDEN or INCLUDE_TEAM_PRIVATE_MEMBERS` で呼ばれること**を固定する |
| **`commit` の永続化判定(§9.1・AC27)** | 注入した `commit` が例外を投げる状況で 4 段の判定を固定する:<br>**(1) 書き込み前と判明**(out-of-sync = plugin `org.eclipse.core.filebuffers` / code 274、charset 構築失敗、対象が存在しない)→ **`committed=false`** で §12.1 の分岐(E21b)<br>**(2) 内容一致** → `applied:true`・通知なし(E20)<br>**(3) 内容不一致** → §12.1 の分岐<br>**(4) 判定不能** → `applied:true` + 通知 1 回(E21) |
| **BOM 付きファイルの判定(§9.1・AC27)** | **バイト列比較**であることを固定する。(a) ディスク = `encode(doc)` → `MATCHED` / (b) ディスク = `BOM + encode(doc)` → `MATCHED` / (c) **UTF-8 BOM の直後に本文としての `U+FEFF` があるファイル**でも `MATCHED` になること(第 7 版の「両側から 1 文字剥がす」実装ではここが `NOT_MATCHED` になり二重適用を招くため、**変異注入で liveness を確認する**)/ (d) `"UTF-16"` + LE BOM のとき commit と同じ `"UTF-16LE"` 差し替えが適用されること |
| **判定の 3 値(§9.1・E20/E21・AC27)** | `persistedDespiteFailure` が **`MATCHED` / `NOT_MATCHED` / `UNKNOWN`** を返し、呼び出し側が **`UNKNOWN` のときだけ通知を出す**こと。`MATCHED` では通知が出ないこと(第 7 版は `Boolean` だったため `UNKNOWN` の通知が構造上出せなかった) |
| **UI スレッドでの解決(§11・AC26)** | 対象解決が**ディスパッチスレッドの事前検証では行われない**こと(事前検証は URI 構文のみ)。UI ランナブル内で `findFilesForLocationURI` と `findOpenEditorFor` が呼ばれること。**解決失敗で `applied:false`**(E22) |
| **`commit` の引数(§9.1)** | `commit` が **`overwrite = false`** で呼ばれること。out-of-sync を模した `CoreException` が commit から出たとき、§12.1 の分岐に載ること(AC21) |
| `ClipboardWriter.writeChecked` | `setContents` が `SWTError` / `SWTException` / `RuntimeException` を投げたとき **`false` で完了し、例外が呼び出し元へ伝播しない**こと。`dispose()` が必ず呼ばれること。**`false` のときに通知が出ないこと**(AC15) |
| `showDocument` の失敗ログ | 起動失敗時のログキャプチャに **URI と例外メッセージが含まれない**こと(AC14) |
| `OpenFileParams` / `CopyTextParams` | null / blank の弾き(Gson が非 null 宣言を迂回する前提。issue #47 と同型) |
| `$/gitlab/openFile` の相対パス解決 | 複数ルートのうち実在する最初のものを選ぶこと。どれにも無ければ何もしないこと |

**必要な seam**(いずれもテスト用に注入できることが必須要件):

1. **`onUiThread`** — 既定は `currentDisplay.asyncExec`。テストでは「即実行」「保留してから手動で実行」「登録時に例外」を切り替える。
2. **`scheduleTimeout`** — 開始タイムアウトを実時間を待たずに発火させる。**これが無いと競合 1〜5 が「10 秒待つテスト」になり、実質書かれなくなる**(§24 U8)。
3. **`bufferManager`** — `IPath` 系(`connect` / `getTextFileBuffer` / `disconnect`)と **`IFileStore` 系**(`connectFileStore` / `getFileStoreTextFileBuffer` / `disconnectFileStore`)の**両方**を差し替えて、**どちらの系列が呼ばれたか**・**commit 失敗**・**disconnect 後のバッファ存否とインスタンス同一性**を作る。`IDocument` はインメモリの `org.eclipse.jface.text.Document` を使う(SWT 非依存)。
4. **`findOpenEditorFor`** — 「エディタが開いているか」と「そのドキュメントプロバイダ」を切り替える。既定実装(ワークベンチ走査)は headless では動かないため、**必ず注入で置き換える**。**OpenEditor 経路と Buffered 経路の両方を作れることがテストの前提**(§8)。
5. **`findFilesForLocationURI`** — Buffered 経路の分類(0 件 / 1 件以上)を切り替える。
5. **`notifyUser`** — 呼び出し回数・文言の別を数える。**例外を投げさせるケースも用意する**(AC23)。

**headless で検証できない(= 手動検証手順を PR 説明文に書く)**

- 実バッファの connect / commit / disconnect と、参照カウントによる破棄の実挙動
- 開いているエディタへの反映と undo、dirty エディタを閉じたときの保存プロンプト
- **E14 の残存バッファを、ファイルを開き直して回収できること**(Eclipse が登録済みバッファに接続すること)
- **ワークスペース外のファイルをエディタで開いた状態でエージェントに編集させ、エディタの表示に反映されること**(= file store 系 API でエディタと同じバッファに当たっていること。A6・AC24 の実機確認)
- 外部プロセスによる書き換え(out-of-sync)で `overwrite=false` の commit が実際に失敗すること
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
| ~~**U2**~~ | ~~`commit()` 失敗時にバッファの編集を巻き戻すか~~ → **確定済み(2026-09-06、Codex レビュー #84 で提起・ユーザー承認。第 4 版で判定方法を確定)。巻き戻さず、応答は「編集が退出後も残るか」を退出時に観測して決める** — `retained = committed || (documentMutated && editorAttached && bufferSurvived)`。根拠、却下した代案(巻き戻し / 自前で所有権を持つ)、および `connect` 前の予測が成立しない理由は §12.1・§9.1 | — |
| **U3** | **`ChatWebViewMessageHandlers.copyToClipboard` を `syncExec` → `asyncExec` に変えてよいか。** webview 側のコピーボタンが同期完了を前提にしていないことの確認が必要 | 呼び出し元 4 箇所(`AgenticChatWebViewController.kt:89,92` / `GitLabDuoChatWebViewController.kt:47,50`)を読んで確定。前提していれば `ClipboardWriter` に同期版を足す |
| **U4** | `applyEdit` / `showDocument` の override に `@JsonRequest` を明示するか。インタフェース側に既にあるため不要だが、house style は全ハンドラに明示している | 実装時に既存コードと揃える |
| **U5** | `$/gitlab/openFile` で `filePath` が絶対パスかつワークスペース外を指した場合に開くか。VSCode は `path.join` の性質上、絶対パスならそのまま開く | 既定は「開く」。Codex レビューで再検討 |
| **U6** | `openFile` の相対パス解決で、複数ルートに同名ファイルが実在する場合の優先順位。現案は `IProject` の列挙順の先頭 | 曖昧さを受容するか、開かずに警告するかを Codex レビューで確定 |
| **U7** | **`showDocument` の URI 非ログ経路をどちらで実装するか**(§9.4): (a) `BrowserLauncher` に `logUrl` フラグつきオーバーロードを足す / (b) `showDocument` 専用の起動ヘルパを持つ。**どちらでも既存 4 箇所の挙動は変えない**という制約は共通 | 実装時に決める。(a) は重複が減るが既存クラスの API が増える。(b) は独立だが `externalBrowser` の呼び出しが 2 箇所になる |
| **U8** | **開始タイムアウトのスケジュール手段**(§9.1・§14)。lsp4j のディスパッチスレッドを塞がず、テストから発火させられるものが要る。候補: 共有 `ScheduledExecutorService` / `Display.timerExec`(UI スレッド前提)/ 注入した `scheduleTimeout` ラムダ | 実装時に決める。**テスト seam であることが必須要件**(§23) |
| ~~**U9**~~ | ~~`findOpenEditorFor` が未復元のエディタ参照をどう扱うか~~ → **確定済み(2026-09-06、第 9 版)。未復元の参照は「開いていない」として扱い、Buffered 経路へ進む。** 理由: エディタ本体が生成されていなければ `createFileInfo` は実行されておらず、**そのエディタはファイルバッファを 1 つも保持していない**。したがって Buffered 経路で作る(または join する)バッファが唯一のバッファになり競合しない。`getEditor(true)` による強制復元は行わない(ユーザーが見ていないエディタをエージェントの編集が開く副作用になる)。§11・AC29 | — |

**推測で確定しない。** 上記はいずれも実ソースまたは実機で確定させる。

## 25. 想定されるリスク

| ID | リスク | 影響 | 緩和 |
|---|---|---|---|
| **R1** | **二重適用**。(a) タイムアウト応答後に UI ランナブルが適用する / (b) 適用中にタイムアウトが応答して LS が FS 直書きを始める / (c) **セットアップ失敗のエラー応答で LS がフォールバックする一方、登録済みランナブルが適用する** / (d) **適用後の例外で `applied:false` を返し、適用済みバッファに FS 直書きが重なる** | 高(ファイル破損) | §9.1 の状態機械(I1 / I2 / I4 / I5)。**`orTimeout` を使わない**・**セットアップ失敗も `settleIfPending` に載せる**・**応答点を `finally` の先頭 1 箇所にする**ことが要。**4 つを個別にテストで固定する**(AC10 / AC13 / AC18 / AC19) |
| **R2** | UI スレッドと lsp4j ディスパッチスレッドのデッドロック | 高(LS 全体が固まる) | `syncExec` を使わない。未完了 future + `asyncExec` + 開始タイムアウト |
| **R3** | LS が `getText` で読んだ後・適用前に別の適用が挟まる窓 | 中(編集が意図とずれる) | `version: null` のためクライアント側では検出不能。**受容する制限**として PR に明記。VSCode 参照実装も同じ |
| **R4** | 意図しない保存(R2 要件により、ユーザーの未保存変更も一緒にディスクへ行く) | 中 | VSCode 参照実装と同一挙動。**PR とリリースノートに明記**。undo は効く |
| **R5** | 危険なパス(`.git/` 等)への書き込み | 中 | LS 側で封じ込め済み。**クライアント側の拒否では止められない**(§9.2)。止める必要が生じたら別設計 |
| **R6** | **`p2repo(".../lsp4e/releases/latest/")` がローリングであるため、同じコミットでも取得時期でビルド結果が変わりうる** | 中(再現性) | 本サイクルのスコープ外(ビルドシステム変更禁止)。**別 issue に記録する**(§21 とあわせて) |
| **R7** | headless で検証できない範囲が広い(バッファ・エディタ・クリップボード・ブラウザ) | 中 | 純ロジックを 3 クラスに切り出して最大限テストする(§9 の「UI スレッド依存なし」列)。**バッファ経路と退出時の観測は `bufferManager` / `findOpenEditorFor` を seam にして covered**(§23)。残りは手動検証手順を PR に記載 |
| **R8** | `getInitializationOptions` が並行 PR と衝突 | 低 | 現時点で open PR は 0 本。マージ順序に注意 |
| **R9** | `TextDocumentEdit.edits` の `Either<TextEdit, SnippetTextEdit>` を取り違え、`SnippetTextEdit` を `TextEdit` として扱う | 中 | `Either.isLeft` を明示的に検査し、右辺は `applied:false`。テストで固定 |
| **R10** | **`window/showDocument` の応答が LS の `z.void()` を必ず満たさず、正常に URL を開いても LS 側に info ログが 1 行残る** | 低(ログノイズのみ) | **回避不能。受容する**(根拠 = §5.5「応答契約について」。zod は `undefined` のみ受理、lsp4j は `"result": null` を必ず出力するため、満たせる応答が存在しない)。**機能影響は無い**。LS 側が `.withResponse` を付けた版になれば自然に解消する |
| **R11** | **サーバ由来 URI の署名・トークンがログへ永続化される** | 中(情報漏洩) | `BrowserLauncher` の既存ログが URL 全体を出すため、**そのまま再利用しない**(§9.4)。URI を受け取らないログ経路を用意し、ログキャプチャで検証(AC14) |
| **R12** | **クリップボード書き込みの失敗時にも成功通知が出る** | 低(誤情報) | `ClipboardWriter.writeChecked` で完了結果を受け、**`true` のときだけ通知**(§9.4)。失敗時の自動テストを置く(AC15) |
| **R13** | **エディタで開いていないファイルが、connect 後・commit 前に外部から書き換えられた場合。** `overwrite=false` によりこちらの commit は失敗し、`applied:false` を返すため、**LS の FS 直書きがその外部変更を上書きしうる** | 中(外部変更の消失) | **クライアント側では止められない。受容する。** 拒否しても「どちらの経路で書かれるか」しか変わらない(§9.2 と同じ論理)。ただし**こちらの手で無言に上書きすることはしない**(`overwrite=true` を使わない)ため、Eclipse のバッファ経路からのデータ消失は無くなる |
| **R14** | **`commit(overwrite=false)` によって、これまで通っていた保存が失敗するケースが増える**(外部変更・ワークスペース未リフレッシュ) | 低 | 失敗は握り潰さず §12.1 の分岐に載る(通知またはフォールバック)。`isSynchronized()` は**バッファが dirty なだけでは false にならない**ので AC4 には影響しない。**手動検証項目に入れる**(§23) |
| ~~**R15**~~ | ~~`editorAttached` と `bufferSurvived` の観測後にバッファが破棄される~~ → **第 8 版で消滅。** OpenEditor 経路では編集はエディタのドキュメントにあり、Buffered 経路ではそもそも retention を主張しない(§12.1) | — | — |
| **R16** | **E14(バッファ残存・エディタなし)で `applied:false` を返したあと、残存バッファが後から保存されて LS の FS 書き込みを上書きする** | 低 | どちらの内容にもエージェントの編集は含まれる(差は LS がその後に書いた分のみ)。**編集の消失より軽い**と判断して `applied:false` を選んでいる(§12.1)。**通知でユーザーに残存を知らせ**、ファイルを開けば内容を確認・保存・破棄できる |
| ~~**R17**~~ | ~~外部ファイルのエディタが file store 系以外の経路で接続している場合~~ → **第 8 版で消滅。** エディタが開いていれば接続系列を推測せず、そのエディタのドキュメントを直接使う(§8) | — | — |
| ~~**R18**~~ | ~~同じ物理ファイルが複数の `IFile` にリンクされ、複数でエディタが開いている~~ → **第 8 版で縮小。** エディタが開いていればそのエディタのドキュメントを使うため、**開いているエディタとは必ず一致する**。複数のエディタが同一物理ファイルを別 `IFile` 経由で開いている場合に他方が古いまま残る点だけが残余で、これは Eclipse のリンクリソースの性質そのもの(LSP の `WorkspaceEdit` も「どの `IFile` か」を表現できない) | 低 | **受容する** |
| **R19** | **`commit` の例外時に永続化されたかを判定できず(4 段目)、実際は未永続なのに `applied:true` を返す** | 低 | 判定は 4 段構成で、**「書き込み前と判明する失敗」(out-of-sync / charset / 対象消失)は 1〜2 段目で除外**され、内容比較(3 段目)で通常は決着する。4 段目に到達するのは「対象は存在するが読み取りが別の理由で失敗した」場合だけ。逆向きの誤り(永続済みなのに `applied:false` → FS フォールバックが同じ TextEdit を再適用してファイル破損)の方が重いため(R1 = 高)この向きに倒す。**判定不能のときは必ず通知する**(E21) |
| **R21** | **エディタが「開いている」と判定できるのに、そのドキュメントプロバイダが `TextFileDocumentProvider` 系でない**(独自エディタ)。`getDocument(input)` が LSP の座標系と対応しないドキュメントを返す可能性がある | 低 | `getDocument` が null / 例外なら **E23 として `applied:false`** へ縮退する。返ってきたドキュメントに対する範囲外はオフセット変換時に `BadLocationException` になり、やはり `applied:false`。**誤った内容を書くのではなく縮退する**設計であることを AC28 で固定する |
| **R22** | **probe と `connect` の間に、バックグラウンドが別系列で新しくバッファを作る窓。** 同一ファイルに 2 つのバッファができ、あとで相手が保存するとこちらの編集が上書きされうる | 低 | **クライアント側では閉じられない。受容する。** バックグラウンドの接続は UI スレッドに同期しないため、probe と `connect` を原子的にする手段が `ITextFileBufferManager` の公開 API に無い。**窓は probe を入れたことで「接続済みのバッファを見落とす」から「probe 後に新設される」へ狭まっている**。実機の手動検証で、リファクタリング等の実行中にエージェント編集を走らせるケースを確認する |
| ~~**R20**~~ | ~~hidden / team-private なファイルの分類ずれ~~ → **第 8 版で無害化。** エディタが開いていれば分類を行わない。開いていなければ共有相手が居ないので分類がずれても書き込み先は同じ。**なお 2 引数版(`INCLUDE_HIDDEN or INCLUDE_TEAM_PRIVATE_MEMBERS`)は引き続き使う**(正しい系列で 1 つのバッファを作るため) | 低 | — |

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
| AC9 | 編集の重なり・範囲外・`SnippetTextEdit`・`ResourceOperation` がすべて `applied:false` になり、**1 つも適用されない**(範囲外は `apply` に到達する前に弾かれること) | 自動テスト |
| AC10 | **開始タイムアウトが応答したあとに UI ランナブルが走っても適用されない**(競合 1・I1) | 自動テスト |
| AC11 | ログに URI・パス・ファイル本文が出ない。**ユーザー通知の文面にもファイルパスを含めない** | コードレビュー + 自動テスト(ログキャプチャ) |
| AC12 | 失敗集合が `FAILSET_IDENTICAL`、detekt が main 17 / test 45 のまま | `verify.sh` + `detektMain` / `detektTest` |
| AC13 | **適用開始後(`RUNNING`)に開始タイムアウトが発火しても応答を返さない**(競合 2・I2)。commit が長引く間、future が完了しないこと | 自動テスト |
| AC14 | **`showDocument` の起動失敗時のログに、URI も例外メッセージも含まれない**(§9.4・R11) | 自動テスト(ログキャプチャ) |
| AC15 | **クリップボード書き込みが失敗したとき、成功通知が出ない**。例外が呼び出し元へ伝播しない(§9.4・R12) | 自動テスト |
| AC16 | **適用済み・commit 失敗・`editorAttached` かつ同一バッファ残存のとき、`applied:true` が返り、編集が巻き戻されず、保存を促す通知が 1 回だけ出る**(§12.1 の E12) | 自動テスト |
| AC17 | **適用前の失敗**(connect 失敗 / `BadLocationException` / `MalformedTreeException`)では **`applied:false`** が返り、バッファが変更されず、通知も出ないこと | 自動テスト |
| AC18 | **セットアップ失敗**(`onUiThread` 登録成功後に `scheduleTimeout` が例外)で、**future は `applied:false` で 1 回だけ完了し、後から走るランナブルはドキュメントを変更しない**。既にランナブルが `RUNNING` の場合は**セットアップ側が応答せず**、応答はランナブルの結果になる(競合 4・5・I2) | 自動テスト |
| AC19 | **適用後に例外**(`endCompoundChange` / `disconnect` / 想定外)が起きても **future は必ず 1 回完了**し、その応答が退出時の観測に一致する。**さらに退出時の観測そのもの**(`access.current()` / `findOpenEditorFor`)**が例外を投げても future は必ず 1 回完了する** — commit 済みなら `applied:true`、未 commit なら `applied:false`(§13 の E19)。future が未完了のまま残らないこと(I4・I5) | 自動テスト |
| AC20 | **適用済み・commit 失敗・バッファが破棄されたとき、`applied:false` が返り、通知を出さない**(編集は LS の FS フォールバックが書き直す)(§12.1 の E13) | 自動テスト |
| AC21 | **`commit` が `overwrite = false` で呼ばれる。** 外部変更(out-of-sync)を模した `CoreException` が commit から出たとき、E12 / E13 / E14 の分岐どおりに応答する(§9.1・R13) | 自動テスト |
| **AC22** | **`bufferSurvived` は `disconnect` の後に同一インスタンスかどうかで判定される。** (a) disconnect 後 null → `applied:false`・通知なし / (b) disconnect 後に別インスタンス → `applied:false` / (c) disconnect 後に同一インスタンス残存だが `editorAttached=false` → **`applied:false` + 残存を知らせる通知 1 回**。**`disconnect` 自体が例外を投げたケースでも同じ規則で決まる**(§12.1 の E14・E15・R16) | 自動テスト |
| **AC23** | **`notifyUser` が例外を投げても future は `retained` で完了済みであり、例外が UI ランナブル外へ漏れない**。応答が通知より先に確定していること(§13 の E16・I4) | 自動テスト |
| **AC24** | **ワークスペース外のファイルでは file store 系 API が使われる。** 注入した `bufferManager` に対し `connectFileStore` / `getFileStoreTextFileBuffer` / `disconnectFileStore` が呼ばれ、**`connect(IPath, LocationKind, …)` 系が一度も呼ばれない**こと。ワークスペース内では逆に `IPath` 系だけが呼ばれること(§9.1・§11・A6) | 自動テスト |
| **AC25** | **保存に成功していれば退出時の観測を行わない。** 観測に使う関数を例外を投げるものに差し替えても、保存成功時は `applied:true` が返り、観測が**呼ばれない**こと(§9.1 の退出処理) | 自動テスト |
| **AC26** | **分類は `findFilesForLocationURI(uri, INCLUDE_HIDDEN or INCLUDE_TEAM_PRIVATE_MEMBERS)` で行う。** (a) 一致 1 件 → `InWorkspace(file.getFullPath())` で **`IPath` 系**(**ワークスペース絶対パスが渡ること**)/ (b) 一致 0 件 → `External(EFS.getStore(uri))` で **file store 系** / (c) 複数一致 → **エディタが開いている `IFile`** が選ばれ、無ければ先頭(R18)/ (d) **hidden / team-private なファイルでも `InWorkspace` に分類されること**(1 引数版では 0 件になり誤分類する。R20)/ (e) **解決はディスパッチスレッドでは行われず、UI ランナブル内で行われること**(§11・E22) | 自動テスト |
| **AC27** | **`commit` が「書き込み後に」例外を投げたとき、`applied:true` が返る。** (a) ディスク内容が `document.get()` と一致 → `applied:true`・通知なし(E20)/ (b) **ディスク先頭に UTF-8 BOM があっても一致と判定されること**(BOM 正規化)/ (c) 判定自体が例外 → `applied:true` + 通知 1 回(E21)/ (d) **書き込み前と判明する失敗**(out-of-sync code 274 / charset / 対象が存在しない)→ **`applied:true` へ倒さず** §12.1 の分岐(E21b)/ (e) 内容不一致 → §12.1 の分岐 | 自動テスト |
| **AC28** | **エディタが開いていれば分類を行わず、そのエディタのドキュメントに適用する。** (a) `findOpenEditorFor` が入力とプロバイダを返す場合、`provider.getDocument(input)` に適用され `provider.saveDocument(..., overwrite = false)` で保存され、**`findFilesForLocationURI` も `BufferAccess` も呼ばれない**こと / (b) `getDocument` が null または例外なら **`applied:false`**(E23。誤った内容を書かない)/ (c) エディタが無い場合だけ分類が走ること(§8・§11・R21) | 自動テスト |
| **AC29** | **エディタの同定と未復元参照の扱い。** (a) 入力が `IFile` アダプタ / `ILocationProviderExtension.getURI` / `ILocationProvider.getPath` の**いずれで対象を指していても** `OpenEditor` として同定されること(`createFileInfo` と同じ優先順位。§8 の ①②③)/ (b) **未復元の `IEditorReference` は `OpenEditor` にせず Buffered へ進む**こと、および `getEditor(true)` が**呼ばれない**こと(§24 U9) | 自動テスト |\n| **AC30** | **Buffered 経路は既存バッファを join する。** (a) `IFILE` / `NORMALIZE` / file store の**いずれかに既存バッファがある**とき、その系列で `connect` され**新しいバッファを作らない**こと / (b) どれも無いときだけ分類して新規に作ること(§11・R22) | 自動テスト |

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
| **`BrowserLauncher` が URL 全体をログに出す** | `BrowserLauncher.kt:17`(`open`)/ `:32`(`openChecked`)= `logger.error("Failed to open URL: $url", e)` |
| **`ClipboardWriter.write` が `setContents` の失敗を捕捉しない** | `ClipboardWriter.kt:9-18`(`asyncExec` で即戻る `void`、`finally` は `dispose()` のみ) |
| **zod `$ZodVoid` は `undefined` のみ受理(`null` は拒否)** | 出荷バンドル `node_modules/zod/v4/core/schemas.js` の `$ZodVoid`: `if (typeof input === "undefined") return payload;` → それ以外は `{ expected: "void", code: "invalid_type" }` |
| **lsp4j は `"result": null` を必ず出力する(省略できない)** | `javap -c` on `org.eclipse.lsp4j.jsonrpc_1.0.0.v20260209-1721.jar` の `adapters.MessageTypeAdapter.write`: offset 169-175 で `name("result")` を無条件出力、offset 182-192 で `getResult()==null` なら `writeNullValue`(= `setSerializeNulls(true)` → `nullValue()`) |
| **VSCode は save 失敗でも `applied` を変えない** | `out/gitlab-vscode-extension/src/common/language_server/save_file_middleware.ts:57-71` |
| **参照数が 0 になった `disconnect` はバッファを保存せずに破棄する** | `javap -c` on `org.eclipse.core.filebuffers-3.8.500.jar`(3.8.300 も同一): `TextFileBufferManager.disconnect(IPath, LocationKind, IProgressMonitor)` = `AbstractFileBuffer.disconnect()` → `isDisconnected()` が真なら `fFilesBuffers.remove(path)` → `fireBufferDisposed` → `dispose()`。**`commit` の呼び出しは無い**。`ResourceFileBuffer.connect()` は `fReferenceCount++`、`disconnect()` は `--` して 0 以下で `disconnected()` |
| **`getTextFileBuffer` は接続中のバッファがあるときだけ非 null**(= 参照者の有無を測れる) | 同 jar: `TextFileBufferManager.getTextFileBuffer(IPath, LocationKind)` → `getFileBuffer` → `internalGetFileBuffer(IPath)` = 管理マップ `fFilesBuffers.get(path)` |
| **`commit(monitor, overwrite=false)` は out-of-sync で `CoreException` を投げる** | 同 jar: `ResourceTextFileBuffer.commitFileBufferContent` / `FileStoreTextFileBuffer.commitFileBufferContent` の先頭 offset 0-49 = `if (!isSynchronized() && !overwrite) throw new CoreException(new Status(IStatus.WARNING, "org.eclipse.core.filebuffers", 274 /* IResourceStatus.OUT_OF_SYNC_LOCAL */, FileBuffer_error_outOfSync, null))` |
| **`isSynchronized()` は dirty なだけでは false にならない** | 同 jar: `ResourceFileBuffer.isSynchronized()` = `fSynchronizationStamp == fFile.getModificationStamp() && fFile.isSynchronized(IResource.DEPTH_ZERO)`。`FileStoreFileBuffer.isSynchronized()` = `fSynchronizationStamp == getModificationStamp()`(= `IFileStore.fetchInfo().getLastModified()`) |
| **`MultiTextEdit.apply` は checkIntegrity のあと巻き戻さずに適用する** | `javap -c` on `org.eclipse.text-3.14.500.jar`: `TextEditProcessor.performEdits()` = `fRoot.dispatchCheckIntegrity(this)`(重なりを**変更前**に `MalformedTreeException`)→ `fRoot.dispatchPerformEdits(this)`。**後者に巻き戻しは無く、`checkIntegrity` はドキュメント長を検査しない** |
| **`disconnect` の破棄は管理マップのロック外で行われる** | 同 jar: `TextFileBufferManager.disconnect(IPath, LocationKind, IProgressMonitor)` の `monitorexit`(offset 71)より後、offset 79 で `fireBufferDisposed`、offset 87 で `dispose()`。**「非 null であること」は「同じインスタンスが生き続けること」を意味しない** → 退出時の判定は identity 比較で行う(§12.1) |
| **エディタは生存中ずっとファイルバッファを connect し続ける** | `javap -c` on `org.eclipse.ui.editors-3.20.200.jar`: `TextFileDocumentProvider` が `ITextFileBufferManager.connect(IPath, LocationKind, IProgressMonitor)` / `connectFileStore` を呼び、`disconnect` / `disconnectFileStore` で解放する。**したがってテキストエディタが開いている限り、こちらの `disconnect` ではバッファは破棄されない** |
| **`ITextFileBufferManager` は互いに参照しない 2 つの管理マップを持つ** | 同 jar: フィールド `fFilesBuffers: Map<IPath, AbstractFileBuffer>` と `fFileStoreFileBuffers: Map<IFileStore, FileStoreFileBuffer>`。`connect(IPath, LocationKind, …)` は前者(offset 21 / 103 / 147 が `fFilesBuffers`)、`connectFileStore(IFileStore, …)` は後者(offset 16 / 91 / 132 が `fFileStoreFileBuffers`)だけを使い、**相互参照は無い** |
| **エディタは外部ファイルを file store 系 API で接続する** | `javap -c` on `org.eclipse.ui.editors-3.20.200.jar`: `TextFileDocumentProvider.createFileInfo(Object)` が入力に応じて `ITextFileBufferManager.connect(IPath, LocationKind, …)`(offset 73 / 229)と `connectFileStore(IFileStore, …)`(offset 161)を使い分け、`disposeFileInfo` は `disconnectFileStore` を呼ぶ。**したがって外部ファイルに `IPath` 系で接続すると別バッファになる** |
| **`getFileForLocation` はリンクリソースを見つけない** | `javap -c` on `org.eclipse.core.resources-3.23.100.jar`: `WorkspaceRoot.getFileForLocation(IPath)` → `FileSystemResourceManager.fileForLocation` → `resourceForLocation(IPath, boolean)`。本体が呼ぶのは `IWorkspaceRoot.getProjects` / `IProject.getLocation` / `IPath.isPrefixOf` / `Resource.isFiltered` だけで、**リンク解決(`findLinkedResourcesPaths`)を通らない**。リンクを含む解決は `findFilesForLocationURI` → `allResourcesFor(URI, …)` 側にある |
| **`createFileInfo` の分類順序(接続系列はエディタ入力の型の関数)** | `javap -c` on `org.eclipse.ui.editors-3.20.200.jar`: `TextFileDocumentProvider.createFileInfo(Object)` は ① `IAdaptable.getAdapter(IFile.class)`(offset 33)が当たれば `LocationKind.IFILE`(60)+ `connect`(73) ② `ILocationProviderExtension.getURI`(122)→ **1 引数の** `findFilesForLocationURI`(139)→ 外れれば `EFS.getStore`(150)+ `connectFileStore`(161) ③ `ILocationProvider.getPath`(190)→ **`LocationKind.NORMALIZE`(216)+ `connect`(229)**、`FileBuffers.getWorkspaceFileAtLocation`(249)を見るのは**接続のあと**。**`NORMALIZE` は `FileBuffers.normalizeLocation` → `getWorkspaceFileAtLocation` → `getFileForLocation` 経由なのでリンクを見つけない。** したがって同じ物理ファイルでも入力型によって ①②③ のどれになるかが変わり、**URI からは再構成できない**(§8) |
| **`commit` は内容を書いたあとにも例外を投げうる** | `javap -c` on `org.eclipse.core.filebuffers-3.8.500.jar`: `ResourceTextFileBuffer.commitFileBufferContent` は `IFile.setContents`(offset 444)→ `IFile.revertModificationStamp`(488)→ **`IPersistableAnnotationModel.commit(IDocument)`(535)** の順で、後 2 者は `CoreException` を投げうる。`FileStoreTextFileBuffer.commitFileBufferContent` にも `IPersistableAnnotationModel.commit`(412)がある。一方 **`TextFileBufferManager.fireDirtyStateChanged` は `SafeRunner.run`(offset 31)でリスナを呼ぶ**ので、リスナの例外はこの窓を作らない |
| **引数 1 つの `findFilesForLocationURI` は hidden / team-private を除外する** | `javap -c` on `org.eclipse.core.resources-3.23.100.jar`: `WorkspaceRoot.findFilesForLocationURI(URI)` の本体は `iconst_0` を積んで `findFilesForLocationURI(URI, int)` を呼ぶ(= `IResource.NONE`)。hidden は `IContainer.INCLUDE_HIDDEN`、team-private は `INCLUDE_TEAM_PRIVATE_MEMBERS` を明示しないと結果に入らない |
| **commit は BOM を再付与し、本文は改行変換なしで書く** | `javap -c` on `org.eclipse.core.filebuffers-3.8.500.jar`: `ResourceTextFileBuffer.commitFileBufferContent` は `fBOM` を `new ByteArrayInputStream(fBOM)` にして `SequenceInputStream`(offset 358 / 402)で本文の前に連結する。本文は `CharBuffer.wrap(fDocument.get())` を `CharsetEncoder.encode` したもの(offset 195-209)= **`IDocument` の内容そのまま**。したがって `IDocument` は BOM を含まず、ディスクとの比較には BOM の正規化だけが要る |

---

## 改訂履歴

| 版 | 日付 | 内容 |
|---|---|---|
| 1 | 2026-09-06 | 初版(`a4fb8a9`) |
| 2 | 2026-09-06 | **Codex レビュー(PR #84、P1×3 / P2×2)の反映。** ① §9.1・§14・§15: `orTimeout` を廃し**単一の状態機械**(`PENDING`/`RUNNING`/`SETTLED`)へ。適用開始後はタイムアウトで応答しない(P1-A)/ ② §12.1 新設・§13・§24 U2: **commit 失敗時は巻き戻さず `applied:true` + 通知**で確定。却下した代案と根拠を明記(P1-B)/ ③ §9.4・§13・R11: **`BrowserLauncher` の URL ログが N3 に違反する**ため、URI を受け取らないログ経路へ(P1-C)/ ④ §5.5・R10: **`z.void()` を満たす応答は存在しない**ことを zod と lsp4j 双方の実測で示し、受容する制限として明記(P2-D)/ ⑤ §9.4・R12: **`ClipboardWriter.writeChecked`** を追加し成功時のみ通知(P2-E)/ ⑥ §23・§26: AC13〜AC17 とテスト seam を追加 |
| 3 | 2026-09-06 | **Codex 再レビュー(PR #84、P1×4)の反映。設計の骨格 4 箇所を改訂。** ① §9.1・§13・§15・§26: **セットアップ失敗(`scheduleTimeout` の例外等)を状態機械に取り込む** — 例外を返さず `settleIfPending(false)` で `SETTLED` へ落とし、登録済みランナブルを CAS で無効化(P1-F・AC18)/ ② §9.1・§13・§16: **応答点を `finally` の 1 箇所に統一**し、`documentMutated` / `committed` / `hasOtherReferent` から応答を決める。適用後の例外で `applied:false` に落とさず、future が未完了のまま残ることもない(P1-G・AC19・I4・I5)/ ③ §12.1・§13・§23: **commit 失敗時の応答を「参照者の有無」で分岐** — 参照者ありなら `applied:true` + 通知、**参照者が自分だけなら `applied:false`**(`disconnect` でバッファが破棄され編集が消えるため)(P1-E・AC20)/ ④ §9.1・§13・R13・R14: **`commit(overwrite = true)` → `false`** に変更し、外部変更の無言上書きをやめる(P1-H・AC21)/ ⑤ §12・§9.1: `MultiTextEdit.apply` の原子性の記述を実測に合わせて訂正(`checkIntegrity` は変更前・`performEdits` に巻き戻し無し・ドキュメント長は未検査)。範囲外はオフセット変換時に検出し、`apply` 内の想定外例外は**悲観的に「変更済み」**として扱う / ⑥ 付録: Eclipse `filebuffers` / `text` の javap 根拠 5 件を追加 |
| 4 | 2026-09-06 | **Codex 3 巡目レビュー(PR #84、P1×3)の反映。** ① §9.1・§12.1・§16: **参照者の存続を「予測」せず「観測」する**(P1-I)。第 3 版の `hasOtherReferent`(`connect` 前の非 null 判定)は成立しない — バッファはバックグラウンドスレッドからも connect / disconnect され、`dispose` は管理マップのロック外で、既存参照者がエディタとも限らないため。**第 3 版 §16 の「UI スレッドを手放さないから参照者集合は変化しない」を撤回**し、退出時に **`bufferSurvived`(identity 比較)** と **`editorAttached`(エディタの開閉は UI スレッド限定)** の積で `retained` を決める形へ / ② §12.1・§13・§19: **`disconnect` 失敗を「破棄済み」と仮定しない**(P1-J)。`bufferSurvived` が破棄の直接観測を兼ね、残存かつエディタ無しは新設の **E14**(`applied:false` + 残存を知らせる通知)へ / ③ §9.1・§13: **`finally` 内で応答を通知より先に確定させる**(P1-K)。`finally` 内の例外はその `finally` の残りを飛ばして伝播するため、通知が先だと通知失敗で future が未完了のまま残り I4 が崩れる。応答・通知をそれぞれ独立に try/catch で封じ込め / ④ §10: seam に `isEditorOpenFor` を追加(計 5 つ)/ ⑤ §23・§26: AC22・AC23 と観測 4 組み合わせのテストを追加 / ⑥ §25: R15(観測の残余リスク)・R16(残存バッファの後追い保存)を追加 / ⑦ §24: U9(未復元エディタ参照の扱い)を追加 / ⑧ 付録: `dispose` がロック外である根拠と `TextFileDocumentProvider` の根拠を追加 |
| 5 | 2026-09-06 | **Codex 4 巡目レビュー(PR #84、P1×2)の反映。** ① §7 A6・§9.1・§11・§23・§26: **ワークスペース外のファイルは `IFileStore` 系 API で接続する**(P1-L)。`ITextFileBufferManager` は `fFilesBuffers`(`IPath` キー)と `fFileStoreFileBuffers`(`IFileStore` キー)の**互いに参照しない 2 マップ**を持ち、`TextFileDocumentProvider.createFileInfo` は外部ファイルに `connectFileStore` を使う(javap)。第 4 版の `External(IPath)` + `LocationKind.LOCATION` では**エディタと別バッファになり A6 が崩れ、#66 の欠陥が外部ファイルで残る**。`EditTarget.External` を `IFileStore` 保持へ変え、`BufferAccess`(connect / current / disconnect)でフローを 1 本に保つ(AC24)/ ② §9.1・§12.1・§13・§26: **退出時の観測の失敗からも応答を守る**(P1-M)。第 4 版は観測を `settleFromRunning` より前かつ try/catch の外で評価しており、`isEditorOpenFor` の例外で commit 成功時ですら future が未完了になりえた。**`retained` の初期値を `committed` にして commit 成功時は観測自体を不要にし**、観測を try/catch で封じ込め(失敗時は `retained = committed` に倒す)、**その外側の `finally` から必ず settle する**(E19・AC19 拡張・AC25)/ ③ §25: R17(外部ファイルのエディタが別経路で接続している場合)を追加 |
| 6 | 2026-09-06 | **Codex 5 巡目レビュー(PR #84、P1×2)の反映。** ① §11・§23・§25・§26: **ワークスペース内 / 外の分類を `getFileForLocation` から `findFilesForLocationURI` へ**(P1-N)。`getFileForLocation` の実体はプロジェクトの location プレフィックス照合だけで**リンクリソースを見つけない**一方、`TextFileDocumentProvider.createFileInfo` は `IFile` にアダプトできれば `IFILE` 系、URI 入力でも先に `findFilesForLocationURI` を検査する(javap)。第 5 版の分類ではワークスペース外を指すリンクリソースが `External` に落ち、**エディタと別バッファになって #66 が残る**。あわせて **`InWorkspace.path` が `IFile.getFullPath()`(ワークスペース絶対パス)であること**を明記し、複数一致の選択規則(エディタが開いている `IFile` 優先)と残余 R18 を追加(AC26)/ ② §9.1・§13・§25・§26: **`commit` の永続化を「正常復帰」で判定しない**(P1-O)。`commitFileBufferContent` は `IFile.setContents` の**あとに** `revertModificationStamp` と `IPersistableAnnotationModel.commit` を呼び、どちらも `CoreException` を投げうるため、**ディスクには書けているのに `committed=true` に到達しない**窓がある。ここで `applied:false` を返すと LS が同じ TextEdit を編集済みディスクへ再適用して**破損**する。commit 例外時は `persistedDespiteFailure`(ディスク内容と `document.get()` の比較)で観測し、**判定不能なら「永続化済み」に倒す**(破損 > 消失)。E20 / E21 / R19 / AC27 を追加。なお dirty-state リスナは `SafeRunner` 配下なのでこの窓を作らない |
| 7 | 2026-09-06 | **Codex 6 巡目レビュー(PR #84、P1×4)の反映。** ① §9.1・§23・§26: **永続化判定で BOM を正規化する**(P1-P)。commit は `SequenceInputStream(ByteArrayInputStream(fBOM), 本文)` で **BOM を再付与**し `IDocument` は BOM を含まない(javap)ため、素直にデコードすると UTF-8 BOM 付きファイルで必ず不一致になり、**永続済みなのに `applied:false` → 二重適用で破損**する。比較前に先頭 `U+FEFF` を 1 つ剥がす / ② §9.1・§13・§25・§26: **「書き込み前と判明する失敗」を永続化済みへ倒さない**(P1-Q)。判定を 4 段(既知の書き込み前失敗 → 対象の存在 → 内容比較 → 判定不能)にし、**`true` へ倒すのは最後の 1 段だけ**にした。out-of-sync(code 274)・charset 構築失敗・対象消失は E21b として §12.1 の分岐へ流す / ③ §9.1・§11・§13・§26: **対象の解決を UI スレッドへ移した**(P1-R)。複数一致時の選択がエディタ照会を含むため、ディスパッチスレッドの事前検証では **URI の構文しか見ない**ことにし、候補列挙と選択は `RUNNING` 取得後の UI ランナブル内で行う(E22)/ ④ §11・§25・§26: **`findFilesForLocationURI` は 2 引数版を使う**(P1-S)。1 引数版は `IResource.NONE` で呼ばれ **hidden / team-private を除外する**(javap)一方、エディタの `IFile` アダプト経路は属性に関係なく `IFILE` 系へ接続するため、誤分類で別バッファになる。R20 を追加 |
| 8 | 2026-09-06 | **Codex 7 巡目レビュー(P1×2 / P2×1)の反映と、それを機とした §8 の方式変更(ユーザー承認済み)。** ① **§8: 「エディタ優先」へ変更(方式 A → C)。** `createFileInfo` の実測で、**エディタの接続系列は入力型の関数であって URI の関数ではない**ことが確定した(① `IFile` → `IFILE` / ② URI → **1 引数** `findFilesForLocationURI` → file store / ③ パス → **`NORMALIZE`**)。5〜7 巡目の P1(リンクリソース / hidden 資源 / `NORMALIZE`)は**すべてこの同じ根の別の症状**で、URI からの再構成では潰し切れない。**推測が要るのはエディタが開いているときだけで、そのときは本人に聞ける** — エディタが開いていればそのドキュメントプロバイダから `IDocument` を取り、`saveDocument(..., overwrite=false)` で保存する。開いていなければ従来のバッファ経路(共有相手が居ないので分類の誤りは無害)。§9.1・§11・§12.1・§13 を全面改訂し、**R15 / R17 を消滅、R18 / R20 を無害化**、R21(独自エディタのプロバイダ)と E23 を追加(AC28)/ ② **§9.1: 永続化判定をバイト列比較へ**(P1-T)。第 7 版の「両側から `U+FEFF` を 1 つ剥がす」は、**UTF-8 BOM の直後に本文としての `U+FEFF` があるファイル**で不一致になり、永続済みなのに `applied:false` → 二重適用を招く。`{ encode(doc), bom + encode(doc) }` の 2 候補とのバイト比較に変更し、`"UTF-16"` → `"UTF-16LE"` の差し替えも commit に合わせた / ③ **§9.1・§13: 判定を 3 値へ**(P2-U)。第 7 版は `Boolean` だったため **`UNKNOWN` の通知が構造上出せず E21 / AC27(c) が満たせなかった**。`MATCHED` / `NOT_MATCHED` / `UNKNOWN` を返し、`UNKNOWN` のときだけ通知する |
| 9 | 2026-09-06 | **Codex 8 巡目レビュー(P1×2 / P2×1)の反映。第 8 版の方式変更に対する初回レビュー。** ① §11・§26: **エディタの同定を `createFileInfo` と同じアダプタ優先順位にした**(P1-V)。第 8 版は `IFileEditorInput` / `IURIEditorInput` しか見ておらず、**`ILocationProvider` だけをアダプトする入力(§8 の ③)を必ず見落とす**。その場合エディタは `NORMALIZE` 系バッファを持っているのに Buffered へ進み、別ドキュメントを保存して #66 が再発する。① `IFile` → ② `ILocationProviderExtension.getURI` → ③ `ILocationProvider.getPath` の順で照合する(AC29)/ ② §8・§11・§25: **Buffered 経路に入る前に全系列を probe し、既存バッファがあれば join する**(P1-W)。第 8 版の「エディタが無い = 共有相手が無い」は**言い過ぎ**で、バックグラウンドが別系列で dirty バッファを保持していれば別バッファ同士になり、あとで相手の保存に上書きされる。**probe して join すれば編集は相手のバッファに入り、`connect` の参照カウントで破棄もされない。** 残る窓(probe 後の新設)は R22 として受容(AC30)/ ③ §24 U9・§11・§26: **未復元のエディタ参照は「開いていない」として Buffered へ進む**と確定(P2-X)。未復元ならエディタ本体が無く `createFileInfo` も実行されていない = **ファイルバッファを 1 つも保持していない**ので、Buffered 経路のバッファが唯一になり競合しない。`getEditor(true)` による強制復元はしない(AC29) |
