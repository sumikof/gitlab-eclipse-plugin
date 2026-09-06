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

**単一の状態機械で排他する。** タイムアウトと編集開始は**同じ状態変数の遷移**として表現し、
「開始前に諦める」か「開始したら最後まで応答を持つ」かのどちらかしか起こらないようにする。

```
状態: AtomicReference<State>   State ∈ { PENDING, RUNNING, SETTLED }

  PENDING --(UI ランナブルが CAS 成功)--> RUNNING --(応答確定)--> SETTLED
  PENDING --(タイムアウトが CAS 成功)--> SETTLED           ★ RUNNING からは遷移できない

不変条件:
  I1. 編集が適用されるのは PENDING → RUNNING の CAS に成功した 1 本だけ(高々 1 回)
  I2. RUNNING に入った後はタイムアウトで応答を返さない(commit 中に LS を
      フォールバックさせない)。応答は必ず RUNNING に入った本人が返す
  I3. future を完了させるのは SETTLED へ遷移させた 1 本だけ
```

```
[lsp4j ディスパッチスレッド]
  applyEdit(params)
    ├─ params.edit.documentChanges が null かつ changes が非 null → applied:false を即返す
    ├─ documentChanges の各要素が Either.isRight(ResourceOperation) → applied:false を即返す
    ├─ edits に Either.isRight(SnippetTextEdit) が含まれる → applied:false を即返す
    ├─ EditTargetResolver で URI を解決できない → applied:false を即返す
    └─ CompletableFuture<ApplyWorkspaceEditResponse> を未完了で生成
         state = PENDING
         onUiThread { ... }                      ← 既定 currentDisplay.asyncExec
         scheduleTimeout(START_TIMEOUT) { ... }  ← ★ orTimeout は使わない
         return future                            ← 素の future を返す

[タイムアウト経路]  ※「UI スレッドに到達しなかった」場合だけを救う
  └─ state.compareAndSet(PENDING, SETTLED) が成功したときのみ
       future.complete(applied = false)    ← LS は FS フォールバックへ。編集は未適用
     失敗(= 既に RUNNING か SETTLED)なら何もしない

[UI スレッド]
  ├─ state.compareAndSet(PENDING, RUNNING) が false → 何もせず抜ける   ★ I1
  ├─ bufferManager.connect(path, locationKind, monitor)
  │    try {
  │      buffer = bufferManager.getTextFileBuffer(path, locationKind)
  │      document = buffer.getDocument()
  │      undoManager?.beginCompoundChange()
  │      try {
  │        MultiTextEdit(全 ReplaceEdit).apply(document)   ← 原子的。重なりは MalformedTreeException
  │      } finally { undoManager?.endCompoundChange() }
  │
  │      // ここから先、編集は「適用済み」。応答は必ず applied:true になる
  │      try {
  │        buffer.commit(monitor, overwrite = true)        ← R2 の保存
  │        settle(applied = true)
  │      } catch (CoreException | RuntimeException) {
  │        // ★ 巻き戻さない(§12)。バッファは dirty のまま残す
  │        notifyUser(保存できなかったこと・手動保存で復旧できること)
  │        settle(applied = true)                          ← FS フォールバックを抑止する
  │      }
  │    } catch (BadLocationException | MalformedTreeException | CoreException) {
  │      settle(applied = false, failureReason = 種別のみ)  ← 適用前の失敗。未適用
  │    } finally { bufferManager.disconnect(path, locationKind, monitor) }
  └─ 上記全体を SWTException / IllegalStateException で包み、ディスパッチループへ漏らさない
     (この経路でも settle(applied=false) を必ず通り、future が未完了のまま残らない)

settle(...) = state.set(SETTLED) してから future.complete(...)  ← RUNNING からのみ呼ばれる
```

**設計上の要点**

- **ディスパッチスレッドをブロックしない**(N1)。`syncExec` はディスパッチスレッドと UI スレッドの相互待ちを作りうるため使わない。未完了 future を返し、UI ランナブル内で complete する。
- **★ `orTimeout` を使わない。** `orTimeout` は状態機械を経由せず future を直接例外完了させるため、
  (a) タイムアウト直後に UI ランナブルが `PENDING → RUNNING` に成功して**適用してしまう**、
  (b) UI が先に走っていても connect/commit が制限時間を超えれば**処理中に応答を失敗させ、LS を
  フォールバックさせる**、という 2 つの二重書き込み窓が残る。自前のスケジュール済みタイムアウトから
  `compareAndSet(PENDING, SETTLED)` を試み、**成功したときだけ**応答する形にすれば、両方の窓が閉じる。
- **タイムアウトが救うのは「UI スレッドに到達しなかった」場合だけ**である(ワークベンチ停止、display 破棄、
  UI スレッドの恒久的な停止)。**適用が始まったあとに時間切れで諦めることはしない** — 諦めた瞬間に
  LS がディスク直書きを始め、こちらのバッファ適用と衝突するため。connect/commit が長引く場合は
  待つ方が安全である。
- **`MultiTextEdit` を使う理由**。LSP の複数 `TextEdit` の `range` は**すべて元ドキュメント座標**である。自前で逆順ソートして順次 `document.replace` するより、`org.eclipse.text.edits.MultiTextEdit` に `ReplaceEdit` を積んで一括 `apply` する方が安全で、**重なりを `MalformedTreeException` として検出できる**。`apply` は適用前にツリー全体を検査するため**部分適用が起こらない**。
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

### 12.1 commit 失敗時の方針【確定・旧 U2】

**適用は成功したが `commit()` が失敗した場合、巻き戻さず `applied: true` を返し、バッファを dirty のまま
残してユーザーに通知する。**

境界は「適用が成功した瞬間」に引く。そこから先、応答は必ず `applied: true` になる。

**採らなかった案 — 巻き戻して `applied:false` を返す**(`MultiTextEdit.apply` が返す undo edit を適用して
バッファを戻し、LS の FS フォールバックに委ねる):

- commit の失敗原因が**読み取り専用・ディスク満杯・権限変更**であれば、**FS 直書きも同じ理由で失敗する**。
  巻き戻した分だけ編集が失われ、得るものが無い。
- 逆に FS 直書きが成功した場合、**バッファは元に戻り、ディスクだけが新しい**状態になる。
  これは開いているエディタとディスクが無言で乖離した状態 = **§4.1 で解消しようとしている症状そのもの**である。
- さらに「巻き戻しにも失敗する」という第 2 の失敗経路を作る。そこでの復旧方針をまた決める必要が生じる。

**採る案の根拠**:

- **参照実装と同じ着地である。** VSCode の `SaveFileMiddleware`(`save_file_middleware.ts:57-71`)は
  `await document.save()` の失敗を `log.error` で記録するだけで **`result.applied` を変更しない**。
  つまり VSCode も「適用成功・保存失敗」で `applied: true` を返す。
- **FS フォールバックを抑止できる**(`applied: true` なら LS は次のサービスへ落ちない)。
  ディスクとバッファが二重に編集される経路が閉じる。
- **編集は失われず、ユーザーの手に残る。** エディタに `*` が出て内容が見え、undo で取り消せ、
  手動保存で確定できる。通知で「保存できなかったこと」と「手動保存で復旧できること」を明示する。
- `applied: true` は「**クライアントが編集を適用した**」の意味であり、嘘ではない。LSP の
  `ApplyWorkspaceEditResponse` は永続化の成否を表す欄を持たない。

**代償(受容する)**: ディスクが一時的に古いままになるため、エージェントが直後にビルドやテストを走らせると
古い内容を読む。ただし LS 自身の `getText` は LSP 優先(= こちらのバッファ)なので、
**LS から見た内容は一貫している**。

**この方針は失敗を握り潰さない。** 通知とログで必ず可視化する(§13・§18)。

## 13. エラー処理

| 事象 | 応答 | ログ |
|---|---|---|
| `documentChanges` が null / 空 | `applied:false` | INFO(件数のみ) |
| `ResourceOperation` が含まれる | `applied:false` | WARN(種別のみ) |
| `SnippetTextEdit` が含まれる | `applied:false` | WARN(種別のみ) |
| URI が `file:` 以外 / 解析不能 | `applied:false` | WARN(スキーマ名のみ。**URI 本体は出さない**) |
| `BadLocationException`(範囲がドキュメント外)= **適用前** | `applied:false` | WARN(例外種別のみ) |
| `MalformedTreeException`(編集の重なり)= **適用前** | `applied:false` | WARN(例外種別のみ) |
| `CoreException`(**connect 失敗** = 適用前) | `applied:false` | WARN(例外種別のみ) |
| **`CoreException` / `RuntimeException`(commit 失敗 = 適用後)** | **`applied:true`**(§12.1) | WARN(例外種別のみ)+ **ユーザー通知** |
| UI スレッドで想定外の `Exception`(適用前) | `applied:false` | ERROR(例外種別のみ) |
| `SWTException` / `IllegalStateException`(display 破棄・workbench 停止) | 状態が `PENDING` のままならタイムアウト経路が `applied:false` で確定させる(§9.1) | 無視(no-op) |
| 開始タイムアウト(UI スレッドに到達しなかった) | `applied:false` | INFO(件数のみ) |

**応答の分岐は「適用済みかどうか」だけで決まる。** 適用前の失敗はすべて `applied:false`(= LS の FS
フォールバックへ委ねる。編集は未適用なので二重にならない)、適用後の失敗はすべて `applied:true`
(= フォールバックを抑止する。編集はバッファに存在する)。

`$/gitlab/openFile` / `$/gitlab/copyText` は notification のため応答しない。失敗はログのみ。
**`$/gitlab/copyText` の完了通知は、クリップボード書き込みの成功が確定してからのみ出す**(§9.4)。

**N3 の徹底**: ログには URI・パス・ファイル本文・`newText` を出さない。例外は `e.javaClass.name`。`?: e.javaClass.name` フォールバックの有無がブランチ全体で不統一という既知の申し送り(issue #52 の 6)があるため、**本サイクルで新規に追加する箇所は統一した形で書く**。

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
- **リトライしない。** `applied:false` を返した時点で LS が FS フォールバックへ落ちる(§5.1)ため、
  クライアント側の再試行は二重書き込みを招く。

**`showDocument`** は編集を伴わないので `orTimeout(10, SECONDS)` で構わない(タイムアウトしても
LS 側は info ログを出すだけ。§5.5)。

## 15. 冪等性

`applyEdit` は**冪等ではない**。同一の `TextEdit` を 2 回適用すれば内容は 2 回変わる。LS 側にリトライ機構は無く(フォールバックは別サービスへの 1 回の切り替え)、同一リクエストが再送されることはない。

したがって本設計が守るべきは冪等性ではなく **「1 リクエストにつき高々 1 回しか適用しない」** ことであり、これを §9.1 の状態機械の不変条件 I1 / I2 で担保する。

**「高々 1 回」が破れる経路は 2 つあり、両方とも状態機械で閉じている**:

| 破れ方 | 閉じ方 |
|---|---|
| タイムアウトが応答したあとに UI ランナブルが適用する | UI 側は `PENDING → RUNNING` の CAS に失敗するので適用しない(I1) |
| 適用中にタイムアウトが応答し、LS が FS 直書きを始める | タイムアウトは `PENDING` からしか遷移できないので `RUNNING` 中は応答しない(I2) |

**この 2 つは受け入れ条件 AC10 / AC13 として個別にテストする**(§26)。

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
| `WorkspaceEditApplier` | 注入した偽 `onUiThread` とインメモリ `Document` で: 正常適用 / `ResourceOperation` 拒否 / `SnippetTextEdit` 拒否 / `changes` のみ拒否 |
| **`WorkspaceEditApplier` の状態機械(§9.1)** | **競合 1**: 開始タイムアウトが先に `SETTLED` へ遷移したあとに UI ランナブルが走っても**適用されない**(AC10)<br>**競合 2**: UI が `RUNNING` に入ったあとに開始タイムアウトが発火しても**応答を返さない**(= LS をフォールバックさせない)(AC13)<br>**競合 3**: `RUNNING` 中に commit が長引いても future が完了しないこと |
| **commit 失敗時の応答(§12.1)** | 注入した commit が例外を投げたとき **`applied:true` を返し、巻き戻さず、ユーザー通知が 1 回だけ出る**こと。**適用前**の失敗(connect 失敗 / `BadLocationException` / `MalformedTreeException`)は `applied:false` になること |
| `ClipboardWriter.writeChecked` | `setContents` が `SWTError` / `SWTException` / `RuntimeException` を投げたとき **`false` で完了し、例外が呼び出し元へ伝播しない**こと。`dispose()` が必ず呼ばれること。**`false` のときに通知が出ないこと**(AC15) |
| `showDocument` の失敗ログ | 起動失敗時のログキャプチャに **URI と例外メッセージが含まれない**こと(AC14) |
| `OpenFileParams` / `CopyTextParams` | null / blank の弾き(Gson が非 null 宣言を迂回する前提。issue #47 と同型) |
| `$/gitlab/openFile` の相対パス解決 | 複数ルートのうち実在する最初のものを選ぶこと。どれにも無ければ何もしないこと |

**状態機械のテストには時計の seam が要る。** 開始タイムアウトは実時間を待たずに発火させたいので、
`scheduleTimeout` も既定値つきラムダで注入できるようにする(`PlatformUtils` と同型の seam)。
そうしないと競合 1〜3 が「10 秒待つテスト」になり、実質書かれなくなる。

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
| ~~**U2**~~ | ~~`commit()` 失敗時にバッファの編集を巻き戻すか~~ → **確定済み(2026-09-06、Codex レビュー #84 で提起・ユーザー承認)。巻き戻さず `applied:true` を返し、バッファを dirty のまま残して通知する。** 根拠と却下した代案は §12.1 | — |
| **U3** | **`ChatWebViewMessageHandlers.copyToClipboard` を `syncExec` → `asyncExec` に変えてよいか。** webview 側のコピーボタンが同期完了を前提にしていないことの確認が必要 | 呼び出し元 4 箇所(`AgenticChatWebViewController.kt:89,92` / `GitLabDuoChatWebViewController.kt:47,50`)を読んで確定。前提していれば `ClipboardWriter` に同期版を足す |
| **U4** | `applyEdit` / `showDocument` の override に `@JsonRequest` を明示するか。インタフェース側に既にあるため不要だが、house style は全ハンドラに明示している | 実装時に既存コードと揃える |
| **U5** | `$/gitlab/openFile` で `filePath` が絶対パスかつワークスペース外を指した場合に開くか。VSCode は `path.join` の性質上、絶対パスならそのまま開く | 既定は「開く」。Codex レビューで再検討 |
| **U6** | `openFile` の相対パス解決で、複数ルートに同名ファイルが実在する場合の優先順位。現案は `IProject` の列挙順の先頭 | 曖昧さを受容するか、開かずに警告するかを Codex レビューで確定 |
| **U7** | **`showDocument` の URI 非ログ経路をどちらで実装するか**(§9.4): (a) `BrowserLauncher` に `logUrl` フラグつきオーバーロードを足す / (b) `showDocument` 専用の起動ヘルパを持つ。**どちらでも既存 4 箇所の挙動は変えない**という制約は共通 | 実装時に決める。(a) は重複が減るが既存クラスの API が増える。(b) は独立だが `externalBrowser` の呼び出しが 2 箇所になる |
| **U8** | **開始タイムアウトのスケジュール手段**(§9.1・§14)。lsp4j のディスパッチスレッドを塞がず、テストから発火させられるものが要る。候補: 共有 `ScheduledExecutorService` / `Display.timerExec`(UI スレッド前提)/ 注入した `scheduleTimeout` ラムダ | 実装時に決める。**テスト seam であることが必須要件**(§23) |

**推測で確定しない。** 上記はいずれも実ソースまたは実機で確定させる。

## 25. 想定されるリスク

| ID | リスク | 影響 | 緩和 |
|---|---|---|---|
| **R1** | **二重適用**。(a) タイムアウト応答後に UI ランナブルが適用する / (b) 適用中にタイムアウトが応答して LS が FS 直書きを始める。どちらもバッファ適用 + FS 直書きで編集が 2 回入る | 高(ファイル破損) | §9.1 の状態機械(不変条件 I1 / I2)。**`orTimeout` を使わない**ことが要。**両方を個別にテストで固定する**(AC10 / AC13) |
| **R2** | UI スレッドと lsp4j ディスパッチスレッドのデッドロック | 高(LS 全体が固まる) | `syncExec` を使わない。未完了 future + `asyncExec` + 開始タイムアウト |
| **R3** | LS が `getText` で読んだ後・適用前に別の適用が挟まる窓 | 中(編集が意図とずれる) | `version: null` のためクライアント側では検出不能。**受容する制限**として PR に明記。VSCode 参照実装も同じ |
| **R4** | 意図しない保存(R2 要件により、ユーザーの未保存変更も一緒にディスクへ行く) | 中 | VSCode 参照実装と同一挙動。**PR とリリースノートに明記**。undo は効く |
| **R5** | 危険なパス(`.git/` 等)への書き込み | 中 | LS 側で封じ込め済み。**クライアント側の拒否では止められない**(§9.2)。止める必要が生じたら別設計 |
| **R6** | **`p2repo(".../lsp4e/releases/latest/")` がローリングであるため、同じコミットでも取得時期でビルド結果が変わりうる** | 中(再現性) | 本サイクルのスコープ外(ビルドシステム変更禁止)。**別 issue に記録する**(§21 とあわせて) |
| **R7** | headless で検証できない範囲が広い(バッファ・エディタ・クリップボード・ブラウザ) | 中 | 純ロジックを 3 クラスに切り出して最大限テストする(§9 の「UI スレッド依存なし」列)。残りは手動検証手順を PR に記載 |
| **R8** | `getInitializationOptions` が並行 PR と衝突 | 低 | 現時点で open PR は 0 本。マージ順序に注意 |
| **R9** | `TextDocumentEdit.edits` の `Either<TextEdit, SnippetTextEdit>` を取り違え、`SnippetTextEdit` を `TextEdit` として扱う | 中 | `Either.isLeft` を明示的に検査し、右辺は `applied:false`。テストで固定 |
| **R10** | **`window/showDocument` の応答が LS の `z.void()` を必ず満たさず、正常に URL を開いても LS 側に info ログが 1 行残る** | 低(ログノイズのみ) | **回避不能。受容する**(根拠 = §5.5「応答契約について」。zod は `undefined` のみ受理、lsp4j は `"result": null` を必ず出力するため、満たせる応答が存在しない)。**機能影響は無い**(URL は既に開かれており、LS は try/catch で握る)。LS 側が `.withResponse` を付けた版になれば自然に解消する |
| **R11** | **サーバ由来 URI の署名・トークンがログへ永続化される** | 中(情報漏洩) | `BrowserLauncher` の既存ログが URL 全体を出すため、**そのまま再利用しない**(§9.4)。URI を受け取らないログ経路を用意し、ログキャプチャで検証(AC14) |
| **R12** | **クリップボード書き込みの失敗時にも成功通知が出る** | 低(誤情報) | `ClipboardWriter.writeChecked` で完了結果を受け、**`true` のときだけ通知**(§9.4)。失敗時の自動テストを置く(AC15) |

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
| AC10 | **開始タイムアウトが応答したあとに UI ランナブルが走っても適用されない**(競合 1・§9.1 の I1) | 自動テスト |
| AC11 | ログに URI・パス・ファイル本文が出ない | コードレビュー + 自動テスト(ログキャプチャ) |
| AC12 | 失敗集合が `FAILSET_IDENTICAL`、detekt が main 17 / test 45 のまま | `verify.sh` + `detektMain` / `detektTest` |
| **AC13** | **適用開始後(`RUNNING`)に開始タイムアウトが発火しても応答を返さない**(競合 2・§9.1 の I2)。commit が長引く間、future が完了しないこと | 自動テスト |
| **AC14** | **`showDocument` の起動失敗時のログに、URI も例外メッセージも含まれない**(§9.4・R11) | 自動テスト(ログキャプチャ) |
| **AC15** | **クリップボード書き込みが失敗したとき、成功通知が出ない**。例外が呼び出し元へ伝播しない(§9.4・R12) | 自動テスト |
| **AC16** | **適用は成功したが commit が失敗したとき、`applied:true` が返り、編集が巻き戻されず、ユーザー通知が 1 回だけ出る**(§12.1) | 自動テスト |
| **AC17** | **適用前の失敗**(connect 失敗 / `BadLocationException` / `MalformedTreeException`)では **`applied:false`** が返り、バッファが変更されないこと | 自動テスト |

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
| **`BrowserLauncher` が URL 全体をログに出す** | `BrowserLauncher.kt:17`(`open`)/ `:32`(`openChecked`)= `logger.error("Failed to open URL: $url", e)` |
| **`ClipboardWriter.write` が `setContents` の失敗を捕捉しない** | `ClipboardWriter.kt:9-18`(`asyncExec` で即戻る `void`、`finally` は `dispose()` のみ) |
| **zod `$ZodVoid` は `undefined` のみ受理(`null` は拒否)** | 出荷バンドル `node_modules/zod/v4/core/schemas.js` の `$ZodVoid`: `if (typeof input === "undefined") return payload;` → それ以外は `{ expected: "void", code: "invalid_type" }` |
| **lsp4j は `"result": null` を必ず出力する(省略できない)** | `javap -c` on `org.eclipse.lsp4j.jsonrpc_1.0.0.v20260209-1721.jar` の `adapters.MessageTypeAdapter.write`: offset 169-175 で `name("result")` を無条件出力、offset 182-192 で `getResult()==null` なら `writeNullValue`(= `setSerializeNulls(true)` → `nullValue()`) |
| **VSCode は save 失敗でも `applied` を変えない** | `out/gitlab-vscode-extension/src/common/language_server/save_file_middleware.ts:57-71` |

---

## 改訂履歴

| 版 | 日付 | 内容 |
|---|---|---|
| 1 | 2026-09-06 | 初版(`a4fb8a9`) |
| 2 | 2026-09-06 | **Codex レビュー(PR #84、P1×3 / P2×2)の反映。** ① §9.1・§14・§15: `orTimeout` を廃し**単一の状態機械**(`PENDING`/`RUNNING`/`SETTLED`)へ。適用開始後はタイムアウトで応答しない(P1-A)/ ② §12.1 新設・§13・§24 U2: **commit 失敗時は巻き戻さず `applied:true` + 通知**で確定。却下した代案と根拠を明記(P1-B)/ ③ §9.4・§13・R11: **`BrowserLauncher` の URL ログが N3 に違反する**ため、URI を受け取らないログ経路へ(P1-C)/ ④ §5.5・R10: **`z.void()` を満たす応答は存在しない**ことを zod と lsp4j 双方の実測で示し、受容する制限として明記(P2-D)/ ⑤ §9.4・R12: **`ClipboardWriter.writeChecked`** を追加し成功時のみ通知(P2-E)/ ⑥ §23・§26: AC13〜AC17 とテスト seam を追加 |
