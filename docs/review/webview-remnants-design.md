# D9 脆弱性詳細 webview / D6 Knowledge Graph 設計書

対象 issue: #14(Phase 6)/ 台帳 #7 D9・D6 / ロードマップ #8
ベースブランチ: `gitlab-ls-9.3.0` @ `0319421`(第 1・2 版は `84dad18` 基準。**PR #87 = D10 診断・ログ群がマージ済み**)
参照実装: `gitlab-workflow` v6.85.3(`./out/gitlab-vscode-extension`、読み取り専用)
同梱 LS: 9.3.0(`build/gitlab-lsp/bin/main.js.map` の `sourcesContent` から確定)

---

## 1. 背景と目的

Phase 6 の「webview 面の開放」は **PR #55** で大半が完了し、D5 / D7 / D8 が ✅ になった。
台帳 #7 はそのとき「**残る webview 面は D6 Knowledge Graph と `security-vuln-details` の 2 つ**」と記している。本サイクルはその 2 つを閉じる。

パリティ **67/85 → 69/85**(D9 = 4/5 → 5/5、D6 = 0/1 → 1/1)。

> **注記**: `feat/diagnostics`(D10 5 件)は **PR #87 としてマージ済み**(base = `0319421`)。
> 衝突はもはや「並行」ではなく、**実装ブランチ `feat/webview-remnants` が base を取り込む**形で解消する(#20 §4 の型)。

## 2. 対象範囲

| # | 台帳行 | コマンド | 現状 | 目標 |
|---|---|---|---|---|
| F1 | 脆弱性詳細 webview | `gl.webview.securityVulnDetails.show` | ❌ | ✅ 所見を選んで詳細 webview に表示 |
| F2 | Knowledge Graph 表示 | `gl.knowledgeGraph.show` | ❌ | ✅ `gkg` が動いていれば表示 |

## 3. 対象外

- **スキャン結果の一覧ビュー。** 参照実装はサイドバーのツリーから所見を選ぶが、本プラグインにその器は無い。**エディタのカーソル行から引く**方式を採る(§5.2)。ツリーの新設は別サイクル。
- **`gkg` バイナリの同梱・インストール支援。** 参照実装も `PATH` 上の `gkg` に依存し、無ければ何も起きない。**その挙動が parity である。**
- **Knowledge Graph の設定(`knowledgeGraph.binaryPath` 等)を LS へ送ること。** 現在も送っておらず、LS は既定の `"gkg"` を使う。設定 UI の新設は対象外。
- **D1 / D3 / D4 / D13。** 別サイクル。

## 4. 現在の課題

1. **脆弱性の所見オブジェクトが届いているのに捨てられている。** `$/gitlab/security/remoteSecurityScan/response` の `results` は GitLab REST API の `vulnerabilities` 配列そのものだが、`SecurityScanStatusReporter.kt:162` が `response.results?.size` を数えた直後に破棄している。
2. **PR #51 の診断経路では代替できない**(§5.1)。
3. **Knowledge Graph は `$/gitlab/webview-metadata` に載らない。** LS 側で `webviewPlugins` ではなく `PluginManager` に登録されているため(`src/node/main.ts:498-500`)、既存の解決経路では必ず `NotAdvertised` になる。

## 5. 要件

### 5.1 ★ D9 のデータ源の確定(**本設計で最も重要**)

参照実装が webview へ渡すペイロードは 3 つ組である(`src/common/security_scans/open_vulns_details.ts:11-29`):

```
{ vulnerability: <API の所見オブジェクトそのもの>, filePath: <文字列>, timestamp: <文字列> }
```

webview が実際に描画するのは `vulnerability.name` / `.severity` / `.description`(markdown)/ `.location.start_line` / `.location.start_column` と、同梱の `filePath` / `timestamp`。

**PR #51 の `publishDiagnostics` → IMarker 経路では不足する。** 実ソースで確認した欠落:

| 必要な値 | 診断/IMarker 経路 | 判定 |
|---|---|---|
| `name` / `description` | **LS 側で `"<name>\n\n<description>"` に連結済み**で送られ(`security_diagnostics_publisher.ts:198`)、さらに `DiagnosticMarkerAttributes.messageOf` が空白を 1 個に潰す | **不可逆** |
| `severity` | **LS 側で `'high'`→Error / それ以外→Warning に潰される**(同 :199-200)。原文字列は復元不能 | **不可逆** |
| `location.start_column` | marker に保存していない(`DiagnosticMarkerAttributes.kt:11-12`) | 欠落 |
| `timestamp` | 診断には無い | 欠落 |

**一方、十分な情報はすでに届いている。** LS は API の配列を**無改変で** `results` に載せる(`security_diagnostics_publisher.ts:184-191`)。

**結論**: 新たな取得経路は要らない。**`results` を保持するだけでよい。**

### 5.2 機能要件

| ID | 要件 |
|---|---|
| R1 | スキャン応答の `results` と `timestamp` を、正規化済みファイルパスごとに保持する。 |
| R2 | エディタのコンテキストメニューから、**カーソル行**の所見の詳細を開ける。 |
| R3 | 詳細は **PR #55 のエディタタブ**(`WebviewEditorPart`)に `security-vuln-details` として表示する。 |
| R4 | 所見は `$/gitlab/plugin/notification` の `updateDetails` で webview へ送る。 |
| R5 | webview 内リンクのクリック(`$/gitlab/openUrl`)をブラウザで開く。 |
| R6 | LS が `ready` を通知したら Knowledge Graph の URL を保持し、コマンドで開けるようにする。 |
| R7 | 取りこぼしに備え、LS 接続確立後に `getUrl` を 1 度問い合わせる。 |
| R8 | `gkg` が動いていなければ、**その旨を説明するメッセージ頁**を出す(黙って何も起きない、にしない)。 |

### 5.3 非機能要件

| ID | 要件 |
|---|---|
| N1 | 所見の保持は**接続世代で無効化**する。死んだ接続のスキャン結果を新しい接続の文脈で見せない。 |
| N2 | 保持量に上限を設ける。スキャンはファイル保存ごとに走りうる。 |
| N3 | **所見の本文・パス・URL をログに出さない。** PR #51 の §16.1 と同じ規律。 |
| N4 | UI スレッドを塞がない。 |
| N5 | **接続が生きたままでもスキャン文脈が変われば所見を破棄する。**(§11 の scan context fingerprint)接続世代は `didChangeConfiguration` では進まないため、**N1 だけでは前のインスタンス・前のアカウントで取得した所見が見え続ける**(= 認可境界の違反)。fingerprint 変更時は store を消し、**変更前から飛行中の応答も着信時に拒否**する。 |
| N6 | **外部由来の所見本文を HTML として実行させない。** 同梱バンドルはサニタイズしないため、**エスケープはクライアントの責務**(§14)。 |

## 6. 前提条件と制約

- 共通制約(#8): ディレクトリ構成・ビルドシステムを変更しない。ドキュメントをコミットしない。
- **`build.gradle.kts` は変更しない。** 本サイクルで新規バンドル依存は不要(#55 の器と既存のメッセージバスで足りる)。
- devcontainer は headless。**SWT・Browser・実 LS 接続は検証できない。** UI 接触部はシームの背後へ。
- `./gradlew` の合否は失敗集合(`verify.sh` の `FAILSET_IDENTICAL`)。detekt は `detektMain` / `detektTest` を明示実行し **main 17 / test 45** と比較。

## 7. システム構成

```
  $/gitlab/security/remoteSecurityScan/response
              │  (results: List<Any?>, timestamp)
              ▼
    ┌──────────────────────┐      既存: SecurityScanStatusReporter（件数のみ）
    │ VulnerabilityStore   │◀─────同じ応答から分岐。
    │ (path → 所見 + 時刻)  │      世代 ＋ scan context fingerprint で無効化（§11）
    └──────────┬───────────┘
               │ カーソル行で引く
               ▼
    ┌──────────────────────┐     ┌────────────────────────┐
    │ VulnerabilityLookup  │────▶│ ShowVulnDetailsHandler │ エディタ右クリック
    │ （所見を「選ぶ」）      │     └───────────┬────────────┘
    └──────────────────────┘                 ▼
                                 ┌────────────────────────┐
                                 │ VulnerabilityProjection│ 描画フィールドを
                                 │ （検証・正規化・エスケープ）│ 検証して HTML エスケープ
                                 └───────────┬────────────┘
                                             ▼
                          WebviewEditorOpener（#55）で
                          "security-vuln-details" タブを開く
                                             │
                                             ▼
                          pluginNotification("updateDetails", payload)

  ─────────────────────────────────────────────────────────────

  $/gitlab/plugin/notification  type="ready" pluginId="knowledge-graph"
              │  {url}
              ▼
    ┌──────────────────────┐        ┌──────────────────────────┐
    │ KnowledgeGraphState  │───────▶│ WebviewUriResolver の     │
    │ (url, session) の組   │        │ directUris シーム（新設） │
    │ 送信元 session に束縛  │        │ ＋ 本番配線（§16）        │
    └──────────────────────┘        └──────────────────────────┘
              ▲                                   │
              │ 取りこぼし補償                      ▼
     pluginRequest("getUrl")              #55 の通常の読み込み経路
```

## 8. コンポーネントの責務

### 8.1 純ロジック(headless で完全にテスト可能)

| コンポーネント | 責務 |
|---|---|
| `VulnerabilityStore` | パス → (所見リスト, timestamp, 世代, **scan context fingerprint**)。**記録・順序採番・上限 eviction は単一ロック下の 1 操作**(`Collections.synchronizedMap` + `LinkedHashMap(accessOrder = true)` の `removeEldestEntry`)。世代・fingerprint 不一致の読み書きを拒否 |
| `VulnerabilityLookup` | **所見を「選ぶ」だけ。** 所見の生 `Map` から `location.start_line` を取り出し、カーソル行に一致する先頭 1 件を返す。**型は仮定しない**(LS の形が変わっても落ちない) |
| `ScanFlightTracker` | **パスごとに「未着の scan 要求の件数」と「fingerprint 変更時に飛行中だったか」だけを持つ**(§9.1)。状態は**整数 1 個と真偽値 1 個**。送信で `+1`、応答で `−1`、汚染中は記録させず、`0` に戻った時点で汚染を解く。**キューも期限も上限も持たない** |
| `VulnerabilityProjection` | **選ばれた 1 件を webview 用に投影する。** 描画対象 5 フィールド(`name` / `severity` / `description` / `location.start_line` / `.start_column`)を**個別に検証・正規化し、HTML エスケープ**する。`location` が使えない 1 件は `null`(= 除外)、表示テキストが欠けるだけの所見は安全な既定値で描画(§10.1 / §14) |
| `VulnerabilityPayload` | `updateDetails` のペイロード 3 つ組を組み立てる。`timestamp` の文字列化を含む |
| `KnowledgeGraphState` | `ready` / `getUrl` で受けた URL を**送信元 `LanguageServerSession` と組で**保持。未設定を表現でき、**現行でない session の書き込み・読み出しを拒否**する(§11) |

### 8.2 プラットフォーム接触(シームの背後)

| コンポーネント | 責務 | 危険な点 |
|---|---|---|
| `SecurityVulnDetailsClient` | タブを開き `updateDetails` を送る | UI スレッド・接続世代 |
| `ShowVulnDetailsHandler` | カーソル行の取得 → lookup → client | UI スレッド専用(`ITextEditor` 操作) |
| `KnowledgeGraphController` | `PluginController("knowledge-graph")` の `@PluginNotification("ready")`。**ハンドラ末尾で `LanguageServerSession` を受け取り**、`KnowledgeGraphState.record(url, session)` へ渡す(`PluginRegistry.kt:32` が末尾パラメータの型を見て注入する既存の仕組み) | lsp4j ディスパッチスレッド。**旧接続の遅延通知**(§11) |
| `ShowKnowledgeGraphHandler` | タブを開く | UI スレッド |
| `GitLabLanguageServer.pluginRequest` | **新設の `@JsonRequest("$/gitlab/plugin/request")`。戻り値型は `CompletableFuture<Any?>` に固定し、`{url}` は手で取り出す** | **★ 同名メソッドがクライアント側に既に存在する**(§8.3)。DTO 戻り値を宣言しても実際には `Object` で返り、**呼び出し側が `ClassCastException` になる**。あわせて **override に `@JsonRequest` を重ねない**(#20 の既知事項: `GenericEndpoint` が `Multiple methods for name` を投げ、クライアントが接続不能になる) |
| `OpenUrlHandler` | `$/gitlab/openUrl` を受けてブラウザで開く | UI スレッド。**PR #85 の `ShowDocumentLauncher` を使う。`BrowserLauncher` は使わない** — 後者は scheme / host を制限せず、失敗時に **URL 全文と例外メッセージをログへ書く**(`BrowserLauncher.kt:17,32`)。所見の markdown に `file:` リンクや token 付き query があると、ローカル resource を開く / 秘密を永続ログに残す。`ShowDocumentLauncher` は `isBrowsableExternalUrl`(絶対 http/https・host あり・userinfo なし)で**ブラウザに触れる前に拒否**し、**URI もメッセージも一切ログに出さない**(出るのは例外クラス名と、拒否時の scheme のみ)。用途もまさに同じ「LS が寄越した URI」である |

### 8.3 確定したプロトコル事実(実装時に再調査しないこと)

すべて同梱 LS 9.3.0 と参照実装の実ソースで確認済み。

- webview id = **`"security-vuln-details"`**、LS が広告するタイトルは **`"GitLab SAST Remote Scanner"`**。
- `updateDetails` のペイロードキーは **`vulnerability` / `filePath` / `timestamp`** の 3 つ。
- **LS 側は `updateDetails` を「後から接続した webview インスタンスにも再生」する。**
  `onInstanceConnected` がハンドラを保存し、既存インスタンスには即時実行する。
  → **クライアント側に readiness ラッチは要らない。** タブを開く前に送っても後に送っても届く。
  (`AgenticChatWebViewClient` が持つ 3 回送信の仕組みは、ここでは**不要**。)
- webview からの唯一の outbound は **`openLink {href}`** で、LS がそれを **`$/gitlab/openUrl {url}`** としてクライアントへ送る。
- Knowledge Graph の id = **`"knowledge-graph"`**。**`webviewPlugins` ではなく `PluginManager` に登録**されるため `$/gitlab/webview-metadata` に**載らない**。
- Knowledge Graph の LS 側 API は **通知 `ready {url}`** と **リクエスト `getUrl` → `{url}`** の 2 つだけ。
- `gkg` は `spawn(binaryPath || "gkg", ["server","start"], {detached:true})` で起動され、stdout の JSON `{port}` から `http://localhost:<port>` が決まる。**未インストール時は ENOENT を LS が警告に落として終わり**(クライアントには何も来ない)。
  ※ `mcp.json` があるときは引数が `["server","start","--register-mcp",<path>]` になるが、結論は変わらない。
- **`gkg` は最初の `didChangeConfiguration` の後に起動する。** したがって **initialize 直後に `getUrl` を投げても
  ほぼ常に `{url: undefined}` が返る。** R7 の補償はこの点で価値が小さく、**主経路はあくまで `ready`**。
  空の url は **no-op**(保持中の値を消さない)。
- 参照実装の Knowledge Graph webview は、**他の LS webview と同じ iframe テンプレートに外部 URL を流し込むだけ**(`get_ls_webview_content.ts`)。
- **★ `$/gitlab/plugin/request` はクライアント側に既に存在する。**
  `GitLabLanguageServerClient.kt:202-203` が `@JsonRequest("$/gitlab/plugin/request")
  fun gitlabPluginRequest(message: PluginMessage): CompletableFuture<Any?>` を持つ(**受信側**)。
  ここへ**送信側**の同名メソッドをサーバインタフェースに足すと、**JSON-RPC 名が衝突する**。
  lsp4j の `Launcher.Builder.getSupportedMethods()` は `LinkedHashMap` に
  **remote(サーバ interface)→ local(クライアント実装)の順で `putAll`** するため、
  **同名キーは local 側の `JsonRpcMethod` で上書きされる**
  (`org.eclipse.lsp4j.jsonrpc_1.0.0.v20260209-1721.jar` の `Launcher$Builder.getSupportedMethods`:
  offset 39-42 が remote、103-109 が local)。
  応答のパースは `MessageTypeAdapter.parseResult` → `MessageJsonHandler.getJsonRpcMethod(name).getReturnType()`
  を通るので、**サーバ側に DTO 戻り値を宣言しても実際には `Object`(Gson の `LinkedTreeMap`)が返る。**
  → **戻り値型は `CompletableFuture<Any?>` と宣言し、`{url}` は `Map` から手で取り出す。**
  **`Multiple methods for name` 例外は出ないので接続は生き、headless の fake proxy でも通る。
  壊れるのは実機だけ。**
  (既存の `$/gitlab/plugin/notification` が両側にあって動いているのは、**通知に戻り値が無い**ため。
  request には当てはまらない。)

## 9. 処理フロー

### 9.1 所見の保持(常時)

1. `$/gitlab/security/remoteSecurityScan/response` 着信(既存 `GitLabLanguageServerClient.securityScanResponse`)。
2. 既存の `SecurityScanStatusReporter.settle(...)` はそのまま(件数の報告)。
3. **加えて** `VulnerabilityStore.record(path, results, timestamp, epoch, requestFingerprint)`。
   **`requestFingerprint` は「着信時点の現行値」ではなく、その scan を要求したときの値**(下記)。
   **現行 fingerprint と一致しなければ記録しない。**
4. `results` が null / 空なら、そのパスの記録を**消す**(所見が無くなった状態を残さない)。

#### ★ 飛行中の応答の扱い(N5 の機構)

**「着信時点の現行値で記録し、現行値と照合する」は成立しない。** 常に一致するトートロジーで、何も拒否されない。
**照合できるのは「その応答がどの設定の下で作られたか」を証明できるときだけ。**

##### ★★ 前提: 応答は要求を識別せず、順序も保証されない(第 5 版で確定)

第 3 版は単一スロット、第 4 版はパス別 FIFO + 隔離境界を採ったが、**どちらも破れる。**
**根本原因は共通で、「エントリを消費したこと」が「対応する応答が返ってきたこと」の証明になっていない**ため:

> **第 4 版の隔離境界が破れる筋書き**(Codex round 3):
> FA で要求 A → FB へ変更 → FB で要求 B → **応答 B が先着**(境界 = B)→ **隔離中に FB で要求 C** →
> 応答 C が B のエントリを消費して**隔離が解除**され → **遅延した応答 A が C のエントリと照合されて FB と一致** →
> **前アカウント由来の所見が記録される。**
> **期限切れや上限 eviction でエントリを捨てる場合も同じ** — エントリを消しても、**対応する応答は取り消せない。**

**この前提は実ソースで確定した(推測ではない)**:

| 事実 | 根拠 |
|---|---|
| **応答は要求を識別する値を持たない** | `SecurityScanClientResponse = { filePath, status, results, timestamp }`(`security_diagnostics_publisher.ts:184-189, 235-240`)。既存コードも同じ認識(`GitLabLanguageServerClient`「Nothing identifies the request」) |
| **scan は重なる(順序保証が無い)** | `handleScanNotification` は **`async` な通知ハンドラ**で、LS のディスパッチャはその完了を待たずに次のメッセージを処理する(`:108-113`)。スキャン本体は GitLab API への往復を含む |
| **1 要求に対し応答は 0 件または 1 件** | 成功時 `:191`、失敗時 `:242` で**ちょうど 1 件**。ただし **`:121` / `:141` / `:144` / `:147` の早期 return は応答を返さない**(文書なし・フラグ無効・スキャン無効) |
| **2 件返ることは無い** | 上記の経路が排他だから。**これは後述の計数が過小にはなっても過大にはならないことを意味する**(= 安全側) |

##### 検討した方式の比較

| 方式 | 正しさ | 採否 |
|---|---|---|
| **要求 ID を応答へ往復させる** | **完全** | **不可**。応答スキーマは同梱 LS のもので、クライアントから変えられない |
| **順序保証を契約・検証する** | 契約できれば可 | **不可**。上表のとおり `handleScanNotification` は非同期で重なる。**クライアント側から検証する手段も無い** |
| **パス別 FIFO + 隔離境界(第 4 版)** | **破れる**(上記) | **撤回** |
| **パスごとに同時飛行 1 件に制限** | 可 | **採らない。** 実現には**要求の送信を抑止**することになり、「保存したのにスキャンされない」= **PR #51 の診断の既存挙動を変える**。本サイクルは `SecurityScanStatusReporter` と診断経路を変更しない(§16)。**クライアントが制御してよいのは「何を記録するか」であって「何を送るか」ではない** |
| **★ 飛行中の要求が捌けるまで記録しない(採用)** | **証明可能** | **採用**。下記 |

##### ★ 採る方式: 静穏になるまで記録しない(`ScanFlightTracker`)

**発想を変える。応答を要求に対応づけようとしない。**
**「その応答が旧文脈で作られた可能性が残っているか」だけを判定する。**

**鍵は、対応づけはできなくても「未処理件数」は数えられること。**
1 要求に対する応答は 0 件か 1 件で、**2 件返ることは無い**(上表)。したがって
**`送信数(P) == 受信数(P)` が成り立った時点で、それ以前に送った要求は「すべて応答済み」**であり、
**順序に関係なく**、旧文脈の応答が飛行中に残っていないことが**証明できる。**

| 状態(パスごと・接続世代スコープ) | 内容 |
|---|---|
| `outstanding(P)` | 送信済みで未着の scan 要求の件数。送信で +1、応答で −1 |
| `contaminated(P)` | **fingerprint 変更の瞬間に `outstanding(P) > 0` だったか** |

**規則はこれだけ**:

1. **送信時**(`SecurityScanLauncher.kt:304` の直前、`outboundLock` 区間) → `outstanding(P) += 1`。
2. **fingerprint 変更時**(全量送信の 2 箇所、同じ `outboundLock` 区間) → `VulnerabilityStore.clear()`。
   加えて **`outstanding(P) > 0` のパスをすべて `contaminated` にする。**
   (飛行中が無いパスは汚染しない。**以後そのパスに届く応答は、変更後に送った要求のものしかありえない。**)
3. **応答着信時**:
   - 接続世代が不一致 → 拒否(既存規律)。
   - `outstanding(P) > 0` なら `outstanding(P) -= 1`。
   - **`contaminated(P)` なら拒否**(記録しない)。**そのうえで `outstanding(P) == 0` になったら汚染を解く**
     — この時点で**変更前の要求はすべて応答済みであることが証明された**から。
   - 汚染されていなければ**記録する。**

**Codex round 3 の筋書きを当てる**: 要求 A(outstanding=1)→ 変更(outstanding>0 なので **汚染**)→
要求 B(2)→ 応答 B(1・汚染中で**拒否**)→ 要求 C(2)→ 応答 C(1・**拒否**)→
**遅延した応答 A**(0・**拒否**、ここで初めて汚染解除)→ 以降の要求の応答は記録される。
**遅延応答 A は記録されない。順序にも期限にも依存しない。**

**なぜ「拒否しても件数は減らす」のか**: 減らさないと静穏が永遠に証明できない。
**減らすのは「何件返ってきたか」の事実であって、「どれが返ってきたか」の主張ではない。**

**境界条件を 2 つ明記する**:

- **送信が例外で終わった場合** — `runSecurityScan` はストリームが死んでいると throw する
  (既存コードが「never sent」として扱っている経路)。**件数は送信の直前に増やす**ので、
  この要求は永久に未着のまま残り、そのパスは汚染されうる。**これは安全側**であり、
  かつストリームが死んでいる以上**接続更新(バリア)が続く**ので必ず解消する。
  **減算のために例外を握って「送れなかった」と見なす扱いはしない** — 送れたかどうかは
  クライアントには確定できず、確定できないものを証明に使わないのが本方式の原則である。
- **未着件数が 0 なのに応答が届いた場合** — 本方式の前提(すべての要求を数えている・応答は 2 件来ない)が
  崩れている証拠なので、**その応答を拒否し、そのパスを汚染する。** 件数は 0 未満にしない。
  **前提が崩れたときに記録を続けるほうが危険**であり、解除は他と同じくバリアに委ねる。

##### この方式が守っていること / 捨てたもの

- **期限切れも上限 eviction も、証明として使わない。** どちらも「エントリを捨てる」だけで
  **対応する応答を取り消さない**ため、第 4 版はここで破れた。**本方式にキューも期限も上限も無い。**
  状態はパスあたり**整数 1 個と真偽値 1 個**だけで、第 4 版の台帳より**単純**である。
- **fail-closed**。判断できないときは記録しない。**「記録しない」の最悪は詳細が出ないことで、
  「記録する」の最悪は他アカウントの機密が現行として表示されること。**

##### 残る限界(明示する)

**応答が 1 件も返らない要求があると、そのパスは静穏を証明できない。**
LS は `:121` / `:141` / `:144` / `:147` の早期 return で**応答を返さずに終わる**ことがある
(文書が無い / クライアント機能フラグ無効 / スキャン無効)。うち後者 2 つは、
`SecurityScanLauncher` が自ら記録している既知のレース
(「gate と送信の間で設定が反転すると `remoteSecurityScans=false` を送ってから scan 要求を送る」)で起こりうる。

そのパスは **`contaminated` のまま残る** = **詳細が出せない。**
**これは仕様であって不具合ではない**(fail-closed)。解除できるのは**証明可能なバリアだけ**:

> **★ バリア = 接続の更新。** LS 接続が張り直されると `GitLabLanguageServerClient` が作り直され、
> **旧接続の応答は新しいクライアントには到達しえない**(到達しても接続世代の照合で落ちる)。
> したがって**接続世代が進んだ時点で `outstanding` と `contaminated` をすべて捨ててよい。**
> **これは N1 で既に持っている仕組みそのもので、新しい概念を足していない。**

**影響範囲は狭い**: 汚染されるのは**「fingerprint 変更の瞬間に scan が飛んでいたパス」だけ**である。
設定変更は稀で、そのとき飛行中の scan は通常 0 件なので、**平常時は何も汚染されない。**
**診断(PR #51)には一切影響しない** — 本方式は「何を送るか」を変えず、「何を**記録**するか」だけを決める。

**設定変更(N5)**: `didChangeConfiguration` を送る箇所のうち **fingerprint の成分を含む全量送信は 2 つ** —
`GitLabLanguageServerConfigurationService.sendConfiguration`(`:58`)と `SecurityScanLauncher.kt:303`。
いずれも `outboundLock` 内なので、**前回値と異なれば同じロック区間で `VulnerabilityStore.clear()`** を行う。

> **★ 3 つ目の送信箇所は対象外**: `ProjectOpenLanguageServerListener.kt:60` は
> **`workspaceFolders` だけの部分送信**(`GitLabLanguageServerConfigurationParams(workspaceFolders = …)`)。
> ここで fingerprint を計算すると `baseUrl` / `token` が null になり、**毎回「変わった」と誤判定して
> プロジェクトを開くたびに store が消える**。**fingerprint は全量送信の 2 箇所でのみ計算する。**

### 9.2 F1 詳細を開く

1. 右クリック → 「Show Vulnerability Details」(エディタの既存 `GitLab` サブメニュー配下)。
2. アクティブエディタのファイルとカーソル行(1 始まり)を得る。**UI スレッド。**
3. `VulnerabilityLookup.at(store, path, line)` → 一致する所見(**選択のみ**)。
4. `VulnerabilityProjection.of(finding)` → 描画用オブジェクト(**検証・正規化・エスケープ**)。
   `null`(= `location` が使えない)なら、その 1 件は**無かったものとして扱う**。
5. **3 か 4 で得られなければ通知**(「この行に GitLab の所見はありません」)して終了。
6. **`wrapper.currentSnapshot` を確認する。`null` なら通知のみ出して終了 —
   タブに触れない**(§12)。**旧所見を表示したままのタブを前面に出して何も送らない、を防ぐ。**
7. `WebviewEditorOpener.openOrReload(page, WebviewEditorInput.securityVulnDetails())` でタブを開く。
8. `pluginNotification(ExtensionToPluginNotification("security-vuln-details", "updateDetails", payload))`。
   **ペイロードは 4 の投影結果**を `VulnerabilityPayload.of` に渡して組み立てる(生の所見ではない)。

> **順序について**: §8.3 のとおり LS が再生するため、7 と 8 の順序はどちらでもよい。
> **タブを先に開く**のは、送信が成功しても頁が出ないという見え方を避けるためだけの理由による。
> **ただしタブは 1 枚を共有する**(§13)ので、**送信が失敗すると前の所見を表示したままになる**。
> その限界と根拠は §12 に明記する。

### 9.3 F2 Knowledge Graph を開く

1. LS 接続確立後、`pluginRequest("knowledge-graph", "getUrl")` を 1 度投げ、返った `{url}` を
   **その要求を出した session と組で** `KnowledgeGraphState` へ(R7)。
2. `ready` 通知が来たら同じく保持(こちらが主経路)。**通知ハンドラが受け取った session を添える。**
3. **1 と 2 のいずれも、`record` 時点で現行 session でなければ捨てる。**(§11)
4. コマンド → `WebviewEditorOpener` でタブを開く。`WebviewUriResolver` は `knowledge-graph` に対し
   **`directUris` シームから URL を返す**。シームは**現行 session を引数に取り**、
   **保持している session と一致するときだけ** URL を返す。
5. URL 未設定・session 不一致なら `NotAdvertised` 相当のメッセージ頁に **`gkg` の説明文**を出す(R8)。

### 9.4 F1 のリンククリック

1. webview 内リンク → LS → `$/gitlab/openUrl {url}` 通知。
2. **`ShowDocumentLauncher.show(url)`** で開く(§8.2)。
   絶対 http/https・host あり・userinfo なしでなければ**ブラウザに触れる前に拒否**し、
   **URL はログに出さない**(N3)。`file:` / `javascript:` / userinfo 偽装はここで落ちる。

## 10. データモデル

```kotlin
/** LS の形を仮定しない。所見は生のまま持つ。 */
data class FileVulnerabilities(
  val findings: List<Any?>,
  val timestampMillis: Long?,
  val epoch: Long,
  val contextFingerprint: String,
)
```

`VulnerabilityStore` は `Map<String, FileVulnerabilities>`(キー = 既存 `securityScanPathKey` が作る正規化済みパス)。
**保持の実体は `Collections.synchronizedMap` で包んだ `LinkedHashMap(accessOrder = true)`** で、上限超過は
`removeEldestEntry` が返す(§11)。

`KnowledgeGraphState` の状態は **`String?` ではなく `(url, session)` の組**(§11)。

**所見そのものを型付けしない理由**: 既存 `SecurityScanResponse.results` の KDoc が「意図的に untyped。形は LS のものであり、ここで固定すると認識できない所見が**無視ではなくパース失敗**になる」と述べている。同じ理由をここでも維持する。`VulnerabilityLookup` は `Map<*, *>` として `location.start_line` だけを best-effort で読む。

### 10.1 描画用の投影(`VulnerabilityProjection`)

**保持は untyped のまま、webview へ渡す 1 件だけを投影する。** 両立させる理由は 2 つある。

1. **`start_line` しか検証しないと不十分。** `VulnerabilityLookup` は所見を**選ぶ**のに `location.start_line` しか
   要らないが、そこだけ数値で `name` / `severity` / `description` / `start_column` が欠落・異型の所見は
   選ばれてしまい、生のまま webview へ渡って**描画側で空表示か例外**になる。「異形の所見で落ちない」は
   end-to-end では満たされない。
2. **エスケープが要る**(§14)。

応答全体を厳密 DTO に parse する方式は採らない(1 件の異形で全件を失う)。**要素ごとに寛容な投影へ変換し、
描画対象フィールドを個別に検証・正規化する。**

| フィールド | 不正・欠落時の扱い |
|---|---|
| `location` / `location.start_line` | **投影を `null` にして、その 1 件だけ除外**(位置が分からない所見は見せる意味がない) |
| `location.start_column` | `0` |
| `name` / `description` | 空文字。`String` 以外は `toString()` で文字列化して採用(LS の形を取り締まらない) |
| `severity` | `"unknown"`。小文字へ正規化 |

すべての文字列フィールドは採用前に `& < > " '` を**エスケープ**する(§14)。

## 11. 並行処理

| 論点 | 方針 |
|---|---|
| 応答着信(lsp4j ディスパッチ)と読み出し(UI スレッド) | **`Collections.synchronizedMap` + `LinkedHashMap(accessOrder = true)`。** 値は不変 |
| **記録と上限 eviction の原子性** | **`ConcurrentHashMap` では駄目。** 個々の get/put/remove しか原子的でなく、「記録して上限超過なら最古を捨てる」という**複合操作を守らない** — 上限 N の状態へ複数応答が並行着信すると、両方が同じ最古要素を選んで消して最終サイズが N を超えたり、同一パスの新しい値を古い空応答が消したりできる。**`removeEldestEntry` は put と同じロック区間で走る**ので、記録・順序採番・eviction が**単一操作**になる |
| **退去順序** | `timestamp` ではなく **`LinkedHashMap` のアクセス順**で決まる。`timestamp` が null の所見が混じっても順序が壊れない(応答に timestamp が無い場合の挿入順が別途要る、という問題が起きない) |
| 接続世代 | 既存 `DiagnosticGenerationRegistry.currentEpoch` を使う(marker と同じ土俵)。**読み出し時に世代を照合**し、古ければ「所見なし」を返す |
| **scan context fingerprint**(N5) | 接続世代は `didChangeConfiguration` では進まない。**インスタンス URL・認証・スキャン有効状態が変われば、同じ接続のままでも所見は無効。** `GitLabLanguageServerConfigurationParams` の **`baseUrl` / `token` / `featureFlags.remoteSecurityScans` / `securityScannerOptions.enabled`** から**不可逆ダイジェスト**を作る(§15: **fingerprint 自体もログに出さない。`token` を平文で保持しない**)。全量送信の 2 箇所の `outboundLock` 区間で前回値と比較し、異なれば `clear()`(**部分送信の `ProjectOpenLanguageServerListener.kt:60` は対象外** — §9.1) |
| **飛行中の応答の扱い** | **「着信時点の現行値」では照合にならない**(常に一致する)。**応答は要求を識別せず、順序も保証されない**ため、**応答を要求に対応づける方式はすべて破れる**(§9.1 に第 3・第 4 版の反例)。採るのは **`ScanFlightTracker`** — **対応づけを試みず、「未着件数が 0 に戻った」ことで静穏を証明する** |
| **同一パスの重複要求** | 重複排除もデバウンスも無く、**最大 60 秒**(`RESPONSE_DEADLINE_MS`)同一パスの要求が飛行しうる(`SecurityScanLauncher.kt:399` が明言)。**要求の送信は抑止しない**(診断の既存挙動を変えないため)。重複しても**件数で数えられる**ので方式は成り立つ |
| **`ScanFlightTracker` のロック** | **`DiagnosticGenerationRegistry.lock`**(`CommandWaiters` と同一モニタ)。ロック順序は既存の固定順 **outbound `Mutex` → このモニタ**。**トラッカ側から outbound `Mutex` を取らない** |
| 応答の順序 | **クライアント側**は lsp4j ディスパッチスレッド上で同期に処理される(`securityScanResponse` は `runAsync` しない)。**サーバ側は順序を保証しない** — `handleScanNotification` は `async` な通知ハンドラで、ディスパッチャは完了を待たない(`security_diagnostics_publisher.ts:108-113`)。**既存コードの「サーバは要求順に答える」という仮定は、本機能では使わない** |
| **順序仮定への非依存** | **`ScanFlightTracker` は順序も期限も使わない。** 使うのは **1 要求に対する応答が 0 件か 1 件で、2 件にはならない**という事実だけ(§9.1 の表)。したがって**応答が入れ替わっても、期限を超えて遅延しても、旧文脈の所見は記録されない** |
| `KnowledgeGraphState` | **`(url, session)` の組。** `String?` 1 個では足りない |
| `getUrl` と `ready` の競合 | **同一 session 内なら後勝ちで構わない**(同一の `gkg` プロセスを指す) |
| **LS 再起動を跨ぐ競合** | **後勝ちは同一接続内でしか成立しない。** 再起動中に旧接続の遅延した `getUrl` 完了または `ready` が新接続の値を上書きすると、**停止済み `gkg` の localhost URL が現行 URL として返る**。しかも `WebviewUriResolver` は現行 snapshot の session を付けて `Resolved` を作るため、**既存の session 検査を素通りして別プロセス(あるいは同じポートを取った第三者)へ接続しうる**。→ **`record` は送信元 session を受け取り、現行でなければ拒否。読み出しも現行 session と一致するときだけ URL を返す。** 送信元 session は既存の仕組みで得られる: 通知ハンドラは末尾パラメータの型で注入され(`PluginRegistry.kt:32`)、`getUrl` の要求元は `GitLabLanguageServerClient.session`(同 `:54`)。**明示的な clear フックは要らない** — 読み出し時照合で同じ保証が得られ、取りこぼしが無い |

## 12. エラー処理

| 失敗 | 扱い |
|---|---|
| `results` の要素が想定形でない | その 1 件を**無視**する。他の所見の表示を止めない(§10.1 の投影で、選択後のフィールド不正も同じ扱いに揃える) |
| カーソル行に所見が無い | 通知して終了。webview は開かない |
| `updateDetails` の送信失敗 | **下記「★ 認識している限界」** |
| `getUrl` がタイムアウト / エラー | `ready` 待ちへ縮退。コマンドはメッセージ頁を出す |
| `getUrl` / `ready` が**旧 session** から届く | **捨てる**(§11)。メッセージ頁のまま |
| `gkg` 未インストール | R8 のメッセージ頁。**エラーログを出さない**(ユーザーの環境選択であって障害ではない) |
| `$/gitlab/openUrl` の URL が不正 | `ShowDocumentLauncher` が**ブラウザに触れる前に拒否**。**URL をログに出さない**(出るのは scheme のみ) |

### ★ 認識している限界: `updateDetails` の送信失敗(P1-d)

**「LS が再生するので再送不要」は、遅れて接続した webview にしか効かない。**
所見 A を表示中の共有タブに対し、所見 B の `updateDetails` が接続障害で届かなければ、
**タブは A を表示したまま**で、ユーザーには B の詳細に見える誤表示になる。

**ack 機構は作れない。** 実ソースで確認した:

- **プロトコルに ack が無い。** vuln-details プラグインは `extension.onNotification("updateDetails")` だけを
  登録し、`onRequest` を登録しない(`packages/webview_vuln_details/dist/index.mjs:10-18`)。
  参照実装も一方向通知(`open_vulns_details.ts:20-28`)。
- **lsp4j は `notify` の失敗を握り潰す。** `RemoteEndpoint.notify` は `out.consume` を
  `catch (Exception)` して `LOG.log` するだけで、例外を投げ直さない
  (`org.eclipse.lsp4j.jsonrpc_1.0.0.v20260209-1721.jar`、`notify` の exception table `7-17 → 20`)。
  **根拠はこの実装であって #20 ではない**(#20 にこの記述は無い)。

> **申し送り**: 既存コメント `SecurityScanLauncher.kt:306-308` は「死んだストリームの lsp4j proxy は
> 上の 2 つの呼び出しから直接 throw する」と主張しており、**上記の bytecode と食い違う。**
> 本サイクルでは触らないが、**別 issue で確認する価値がある**(通知の送信失敗を前提にした
> 既存のエラー処理が空振りしている可能性)。

**ただし「何も検知できない」わけではない。** 検知できるものは検知する:

| 状況 | 検知 | 本設計の扱い |
|---|---|---|
| **LS 接続が無い** | **できる**(`wrapper.currentSnapshot == null`。resolver が `LanguageServerUnavailable` を返すのと同じ判定) | **§9.2 で送信前に確認し、無ければタブに触れず通知のみ出す。**(旧所見を表示したタブを前面に出してから何も送らない、を防ぐ)A18 |
| **接続はあるが送信が失敗した** | **できない**(上記 2 点) | **限界として明記。再送も遷移も作らない** |

**したがって限界の範囲は「接続が生きている場合の送信失敗」に限られる。**
**検知できない以上、検知できるふりをする機構は作らない。**

**緩和**: ペイロードには `filePath` と行番号が必ず含まれ、webview はそれをロケーション行として描画する。
**右クリックした箇所と表示が食い違えばユーザーが気づける。**

**この限界は PR 説明文の「既知の制限」へ転記し、実機検証項目に含める。**
ack を持つ双方向経路が LS 側に入った時点で再検討する(§20 U6)。

## 13. タイムアウトとリトライ / 冪等性

- `getUrl` に **10 秒**のタイムアウト(既存 `WebviewUriResolver` の既定値に合わせる)。**リトライしない** — `ready` が主経路であり、これは取りこぼし補償にすぎない。
- `updateDetails` は**冪等**。同じ所見を 2 度送っても webview は同じ内容を描き直すだけ。
- タブは `WebviewEditorKey("security-vuln-details", emptyMap())` の 1 枚を共有する(参照実装も単一パネル)。

## 14. 認証と認可

- 新たな認証経路は無い。所見は既存のスキャン応答に含まれて届く。
- **所見の保持は認可境界を持つ。** インスタンス URL・認証・スキャン有効状態が変わったら、
  **前の文脈で取得した所見を見せてはならない**(N5 / §11 の fingerprint)。接続世代だけでは
  `didChangeConfiguration` を跨げない。

### ★ 外部データの HTML 展開(サニタイズ責務の所在)

**所見の `name` / `description` は GitLab インスタンス由来の外部データ**であり、webview 内で
markdown → HTML に展開される。

**当初案は「サニタイズは LS が配信する webview バンドルの責務。本プラグインは内容に手を触れない
(参照実装も同じ)」としていた。これは撤回する。実バンドルを読んだ結果、成立しないことが確定した。**

**根拠(同梱 9.3.0、実ファイル)**:
`build/gitlab-lsp/bin/webviews/security-vuln-details/assets/index-Bhmygly-.js` の描画関数は

```
Rh(t) = Dn.parse(t.toString())
```

すなわち **`marked` をオプション無しで呼んでおり、`sanitize` は既定の `false`** のまま。
その結果が Vue の **`domProps: { innerHTML: markdownContent }`** に入る。
**バンドルはサニタイズしていない。** 「境界を明記する」ことは XSS の緩和にならなかった。

**したがってエスケープはクライアントの責務である。**
`VulnerabilityProjection`(§10.1)が描画対象フィールドを投影する際に `& < > " '` をエスケープする。

**strip ではなく escape。** HTML を含む description は**実行されずテキストとして見える**。
見出し・箇条書き・強調・リンク記法といった markdown の構造は**そのまま効く**。

> **★ ただし「見た目が変わらない」は正しくない(第 3 版で訂正)。**
> 同梱バンドルの `marked` は**コードスパン / コードブロックの中身を再エスケープ**するため、
> 投影で `` `<script>` `` → `` `&lt;script&gt;` `` にすると、画面には **`&lt;script&gt;`** という
> 文字列が出る。リンク URL も `[x](https://a?b=1&c=2)` が `&amp;` になって href が壊れる。
> **`& < > " '` を含むのは、まさに XSS / インジェクション系の所見のコード例**なので、
> **最も見たい種類の所見で表示が劣化する。**
>
> **それでもこの方式を採る**(実行されるよりは劣化するほうがよい)が、**無害ではない。**
> **PR の「既知の制限」へ転記し、A17 にコードスパン内 `<` のケースを含める。**
> 表示を保ったまま安全にするには HTML sink 直前の allowlist サニタイズが要るが、
> **その sink はバンドル内にありクライアントから触れない**ため、本サイクルでは採れない(§20 U8)。

**これは参照実装からの意図的な乖離である。** 参照実装は同じ description を live HTML として描画する。
乖離を選ぶ理由は、所見本文が**本プラグインの著作物ではない外部データ**であり、
**エスケープしなければ誰もしない**ため。**PR 説明文に乖離として明記する。**

> **将来バンドルがサニタイズを始めた場合**: 二重エスケープで `&amp;lt;` のように見えるようになる。
> 実害は表示崩れのみ(実行はされない)。**LS 更新時の確認項目として PR に残す。**

## 15. ログ、監視、監査

- **所見の本文・ファイルパス・URL は一切ログに出さない。** 出してよいのは件数と例外のクラス名のみ(PR #51 §16.1 の規律)。
- **scan context fingerprint もログに出さない。** 成分に `token` を含むため(§11)。
  **`token` を平文で保持しない**(不可逆ダイジェストのみ)。fingerprint 変更は「変わった」という事実だけを記録する。
- `$/gitlab/openUrl` の拒否は **scheme のみ**を記録する(`ShowDocumentLauncher` の既存規律)。
  ※ ログ文言の接頭辞は `showDocument:` 固定。openUrl 経由でも同じ文言になるが、**許容する**
  (文言のために URL を記録可能にするほうが悪い)。
- `gkg` 未検出はログに出さない(§12)。`gkg` の localhost URL もログに出さない。
  **★ `ready` の DTO パースが失敗すると `PluginMessageService` が payload をそのまま warn する**ため、
  そこで localhost URL が露出しうる。**`ready` の DTO は nullable・寛容にしてパース失敗を起こさせない。**

## 16. 既存機能への影響

| 既存 | 影響 |
|---|---|
| `SecurityScanStatusReporter` | **変更しない。** store への記録はクライアント側で分岐して行う |
| `SecurityScanResponse` | **変更しない**(`results` は untyped のまま) |
| `DiagnosticMarkerService` / PR #51 の marker | **変更しない** |
| `WebviewUriResolver` | **`directUris` シームを 1 つ追加**(既定は「無し」)。**metadata 要求より前に引く**(直接アドレスは metadata を要さず、待たせる理由が無い)。session は従来どおり現行 snapshot から取るので supersession 検出は不変。既存の解決順序は不変 |
| **`WebviewEditorPart.kt:41`** | **`directUris` を渡す配線を追加。**(P1-c) |
| **`AgenticTabsView.kt:27`** | **同上。** |
| `GitLabLanguageServerConfigurationService` / `SecurityScanLauncher` | **fingerprint の計算と `clear()` を `outboundLock` 区間に追加**、`SecurityScanLauncher` は**送信直前に `ScanFlightTracker.onSent`** も行う(§9.1)。**送信内容も送信可否も不変**(要求は一切抑止しない) |
| `CommandWaiters` / `DiagnosticGenerationRegistry` | **変更しない。** `ScanFlightTracker` は `DiagnosticGenerationRegistry.lock` を**共有するだけ**(`CommandWaiters` と同じ扱い)。既存のロック順序 outbound `Mutex` → モニタ を守る |
| `SecurityScanLifecycle` | 接続停止時の後始末に **`ScanFlightTracker.clear(deadEpoch)` を 1 行追加**(`CommandWaiters.clear` と同じ引数・同じ位置)。**死んだ接続の epoch を渡す**(進めた後の値ではない)。**これが §9.1 の「証明可能なバリア」の実体** |
| `GitLabLanguageServer` | `@JsonRequest("$/gitlab/plugin/request")` を 1 つ追加 |
| `GitLabLanguageServerClient` | `$/gitlab/openUrl` ハンドラと store への記録を追加 |
| `WebviewEditorInput` | ファクトリを 2 つ追加 |
| `plugin.xml` | コマンド 2・ハンドラ 2・メニュー寄与 2。**末尾追記のみ**。`feat/diagnostics` と衝突する(#20 §4) |

### 16.1 ★ `directUris` の本番配線(P1-c)

**シームを足すだけでは死ぬ。** `WebviewUriResolver` は本番で 2 箇所から**直接生成**されており、
どちらも `KnowledgeGraphState` を渡す経路を持たない:

```
src/main/kotlin/com/gitlab/eclipse/views/webview/WebviewEditorPart.kt:41
  resolver = WebviewUriResolver(languageServerWrapper),
src/main/kotlin/com/gitlab/eclipse/views/webview/AgenticTabsView.kt:27
  resolver = WebviewUriResolver(languageServerWrapper),
```

**このまま実装すればコンパイルは通るが、既定値「無し」が使われ続け、Knowledge Graph は常に
`NotAdvertised` になる。**(設計だけ正しく、機能が動かない状態)

**方針**: **本番ファクトリを 1 つ設け、両生成箇所はそれを呼ぶ。**

```kotlin
// シームの型（現行実装の (String) -> DirectWebview? から変更する）
directUris: (String, LanguageServerSession) -> DirectWebview? = { _, _ -> null }

// 本番ファクトリ（テストの対象はここ）
fun WebviewUriResolver.Companion.forProduction(wrapper: GitLabLanguageServerWrapper) =
  WebviewUriResolver(wrapper, directUris = KnowledgeGraphState::directWebviewFor)
```

- **「現行 session」の入手元を 1 つに確定する。** resolver は既に `snapshot.session` を持っている
  (`WebviewUriResolver.kt` の `wrapper.currentSnapshot`)ので、**resolver がシームへ渡す**。
  `KnowledgeGraphState` 側が wrapper を引いたり、静的に現行 session を探したりしない。
  **現行実装のシーム型 `(String) -> DirectWebview?` は session を受け取れないので変更が要る。**
- **静的な共有 map で回避しない。** それをやると session 分離を失い、P1-b で入れた session 束縛が無意味になる。
- **優先順位**: `directUris` → (無ければ) 既存 metadata 経路。直接アドレスは metadata に載らないので競合しない。
- **`getUrl` の送信側も同じ規律**: 送信時に `currentSnapshot`(handle)を捕捉し、`handle.proxy` で送って
  **`handle.session` を `record` へ渡す**(`LanguageServerHandle.session` は `client.session` と同一オブジェクト)。

> **★ テスト可能性の限界(A15)**: 生成箇所 `WebviewEditorPart.createPartControl`(`:41`)と
> `AgenticTabsView.createPartControl`(`:27`)は **SWT `Composite` を受けるので headless で呼べない。**
> したがって「2 箇所に配線されていること」自体は**自動テストできない。**
> **自動で固定するのはファクトリの振る舞い**(`knowledge-graph` は直接経路 / 他 id は metadata 経路)、
> **配線そのものは目視レビューと実機**で確認する。A15 はこの 2 つに分けて書く。

## 17. 移行方法 / ロールバック方法

- 永続データを持たない(store はメモリのみ)。移行なし。
- ロールバックは revert のみ。外部状態を変更しない。

## 18. テスト方針

| 層 | 方式 | 対象 |
|---|---|---|
| 純ロジック | **TDD** | **`ScanFlightTracker`(件数・汚染・世代。A13b-1〜5。時刻に依存しないので注入するシームも要らない)** / `VulnerabilityStore`(世代・**fingerprint**・上限・削除・**並行 record/delete と同時上限超過**)/ `VulnerabilityLookup`(行一致・異形無視)/ **`VulnerabilityProjection`(5 フィールドの検証・正規化・エスケープ)** / `VulnerabilityPayload` / `KnowledgeGraphState`(**session 束縛・再起動競合**) |
| 受信配線 | `GitLabLanguageServerClient` のハンドラを直接呼ぶ | store への記録、`openUrl` の委譲、**旧 session の `ready` 拒否** |
| 解決経路 | `WebviewUriResolver` に fake wrapper + fake `directUris` | **通常 webview が metadata 経路のまま**(keep-behaviour)/ **Knowledge Graph が直接経路**(A15) |
| 送信 | 注入シームに fake proxy | `updateDetails` のペイロード形 |
| ログ陰性 | `LoggingKotestExtension` | 所見本文・パス・URL・**fingerprint** が出ないこと(A7)/ **拒否 scheme と token 付き URL**(A16) |
| UI 実体 | **テストしない**(headless 不可) | エディタのカーソル行取得、タブ表示、Browser |
| **`updateDetails` 送信失敗** | **テストしない**(検知手段が無い。§12) | **実機の「既知の制限」として PR に記載** |

**`VulnerabilityLookup` と `VulnerabilityProjection` が最重要のテスト対象。** LS の所見は untyped なので、想定外の形(欠落キー・型違い・null)で落ちないことを陰性テストで固める。**投影側は、`start_line` だけが正しく他が異型の所見**という P2-a の条件を名指しでテストする。

## 19. 受け入れ条件

| # | 条件 | 検証 |
|---|---|---|
| A1 | `results` が null / 空ならそのパスの記録が消える | 自動 |
| A2 | 世代が進むと古い記録が読み出せない | 自動 |
| A3 | 上限を超えた記録は最古から捨てられる | 自動 |
| A4 | 所見が `Map` でない / `location` が無い / `start_line` が数値でない場合も**例外を投げず**、その 1 件だけ無視する | 自動 |
| A4b | **`start_line` だけが数値で `name` / `severity` / `description` / `start_column` が欠落・異型**の所見でも、例外にならず**安全な既定値で描画対象になる**(P2-a) | 自動 |
| A5 | カーソル行に一致する所見が返る(1 始まりの一致) | 自動 |
| A6 | `updateDetails` のペイロードキーが `vulnerability` / `filePath` / `timestamp` ちょうど | 自動 |
| A7 | 所見の本文・パス・URL がログに現れない | 自動 |
| A8 | `KnowledgeGraphState` 未設定時にコマンドがメッセージ頁を出す(例外にしない) | 自動 |
| A9 | 失敗集合が `FAILSET_IDENTICAL` | `verify.sh` |
| A10 | detekt main 17 / test 45 | 明示実行 |
| A11 | 実機: 右クリックで詳細タブが開き、内容が描画される | **実機** |
| A12 | 実機: `gkg` 導入時に Knowledge Graph が開く | **実機**(`gkg` 必須) |
| **A13** | **fingerprint が変われば store が消える。** 全量送信の 2 箇所でのみ計算され、**`workspaceFolders` だけの部分送信では消えない**(N5 / P1-a) | 自動 |
| **A13b** | **fingerprint 変更の瞬間に飛行中だったパスは、飛行分が捌けきるまで一切記録されない**(§9.1)。飛行中が無ければ汚染しない | 自動 |
| **A13b-1** | **★ round 3 の筋書き**: 要求 A(FA)→ FB へ変更 → 要求 B(FB)→ **応答 B**(先着)→ **隔離中に要求 C**(FB)→ **応答 C** → **遅延した応答 A**。**3 つの応答のいずれも記録されない。** とくに**遅延応答 A が「現行 FB のもの」として受理されない**こと | 自動 |
| **A13b-2** | **A13b-1 の続き(解除の証明)**: 遅延応答 A で未着件数が 0 になった後、**新たな要求 D の応答は記録される**。汚染が恒久化しないこと | 自動 |
| **A13b-3** | **期限を超えた遅延応答も記録されない**: 応答が `RESPONSE_DEADLINE_MS` より後に届いても、未着件数が 0 になっていなければ拒否される。**期限切れを「出し切った」証明に使っていない**こと | 自動(時刻に依存しない = 件数だけで判定していることの裏返し) |
| **A13b-4** | **過剰拒否しない(liveness)**: fingerprint が変わらないまま同一パスへ重複要求した場合、**両方の応答が記録される**。汚染が常時 on になっていないこと | 自動 |
| **A13b-5** | **汚染はパス単位**: 変更時に飛行中だったパスだけが汚染され、**飛行中でなかった別パスは直後から記録される** | 自動 |
| **A13b-6** | **応答が返らない要求は fail-closed**: 応答が 1 件も返らない要求があるパスは、**接続世代が進むまで汚染されたまま**(詳細が出ない)。**`clear(deadEpoch)` で解除され、以後は記録される**。生きている接続の件数を巻き添えにしない | 自動 |
| **A14** | **旧 session の `ready` / `getUrl` 完了は新接続の値を上書きしない。** 再起動競合で、停止済み `gkg` の URL が `Resolved` にならない(P1-b) | 自動 |
| **A15** | **本番ファクトリが `knowledge-graph` を直接経路で解決し、他の id は metadata 経路のまま**(keep-behaviour)(P1-c) | 自動 |
| **A15b** | **`WebviewEditorPart.kt:41` / `AgenticTabsView.kt:27` がそのファクトリを呼んでいる** | **目視 + 実機**(SWT `Composite` を受けるため headless で呼べない) |
| **A16** | **`$/gitlab/openUrl` が `file:` / `javascript:` / userinfo 付き URL を開かない。** 失敗・拒否のいずれでも **URL 全文・query・fragment がログに出ない**(scheme のみ)(P1-e) | 自動 |
| **A17** | **`description` に `<script>` 等を含む所見が、実行されずテキストとして描画される**(エスケープ済みで webview へ渡る)(P1-f) | 自動(投影の出力を検査)+ **実機**(A11 に相乗り) |
| **A17b** | **コードスパン内の `<` が `&lt;` として投影される**ことを**既知の表示劣化として固定**する(§14。挙動を隠さないためのテスト) | 自動 |
| **A18** | **`wrapper.currentSnapshot` が `null` のとき、タブを開かずに通知だけ出す**(§9.2 手順 6 / §12) | 自動 |
| **A19** | **上限 N の store へ複数スレッドが同時に `record` しても、最終サイズが N を超えず例外も出ない**(P1-g。`removeEldestEntry` が put と同一ロック区間で走ることの確認) | 自動 |
| **A20** | **`$/gitlab/plugin/request` の応答が DTO ではなく `Map` として扱われ、`ClassCastException` にならない**(§8.3 の名前衝突)。`{url}` の取り出しが型に依存しない | 自動 |

## 20. 未決事項

| ID | 内容 | 扱い |
|---|---|---|
| U1 | 同一行に複数の所見がある場合 | **先頭 1 件**を送る。webview は 1 件しか描けないため。選択 UI は別サイクル |
| U2 | `timestamp` の表示形式。参照実装は「5 minutes ago」の相対文字列を渡す | **ISO-8601 の絶対時刻**を渡す。相対表現の実装は本質でなく、診断用途では絶対時刻のほうが有用 |
| U3 | `filePath` に何を渡すか。参照実装は**ファイル名のみ** | ファイル名のみに倣う(webview はロケーション文字列に使うだけ) |
| U4 | store の上限件数 | 実装時に決める。ファイル単位で最新のみ保持するため、上限はファイル数に対するもの |
| U5 | Knowledge Graph の「準備完了」をコマンドの有効/無効に反映するか | **しない。** 常に有効にし、未準備ならメッセージ頁で理由を説明する(R8)。VSCode の `when` 句相当を作るより説明のほうが親切 |
| U6 | `updateDetails` の到達確認(§12 の限界) | **本サイクルでは解けない。** プロトコルに ack が無く、lsp4j が `notify` の失敗を握り潰すため、**クライアントから検知する手段が存在しない。** LS 側に ack を持つ経路が入った時点で再検討する。**未解決のまま PR の「既知の制限」へ転記する** |
| U7 | fingerprint の成分を `baseUrl` / `token` / `featureFlags.remoteSecurityScans` / `securityScannerOptions.enabled` の 4 つに限ってよいか。とくに **`token` を成分に含めてよいか** | **この 4 つで始めるが、`token` は未決。** 所見の出所(インスタンス・認証主体)と有効性を決めるのはこの 4 つで、他の設定(ログレベル・テレメトリ等)は所見の帰属を変えない。**ただし OAuth では `buildParams()` が毎回トークンを更新しうる**(`GitLabLanguageServerConfigurationService.kt` のコメント「refreshes the OAuth token over the network」/ `OAuthTokenProvider` の更新が `sendConfiguration()` を呼ぶ)ため、**同一アカウントのままで fingerprint が変わり、他ファイルの所見まで消える**。fail-safe ではあるが体験が悪い。**代替として「認証主体」を表す値(トークンそのものではないもの)で代用できないか実装時に確認する。** 決着まではトークン込みで実装してよい(安全側) |
| U8 | 表示を保ったまま安全にする(HTML sink 直前の allowlist サニタイズ) | **本サイクルでは採れない。** sink は LS 配信バンドル内にありクライアントから触れない(§14)。エスケープによる表示劣化を受け入れる |

## 21. 想定されるリスク

| リスク | 影響 | 緩和 |
|---|---|---|
| **所見の形が LS のバージョンで変わる** | 詳細が出ない | 型を仮定しない(§10)。A4 / A4b の陰性テスト。**保持は untyped、投影だけ検証**(§10.1) |
| **`gkg` 不在で D6 が実機でも検証できない** | 受け入れ確認ができない | R8 のメッセージ頁までは検証可能。**PR にその旨を明記** |
| **外部データを webview が HTML 展開する** | **XSS** | **同梱バンドルはサニタイズしない(§14 に実ソース根拠)。クライアント側でエスケープする**(`VulnerabilityProjection`)。A17 |
| **設定変更後も旧文脈の所見が見える** | **認可境界の違反**(前のインスタンス・前のアカウントの機密) | scan context fingerprint(§11)。A13 |
| **遅延した旧文脈の応答が「現行のもの」として受理される** | **同上(認可境界の違反)。** **応答を要求に対応づける方式はすべてここで破れる**(単一スロット・FIFO・隔離境界のいずれも) | **対応づけを諦め、未着件数が 0 に戻ることで静穏を証明する**(`ScanFlightTracker`、§9.1)。A13b-1 |
| **設定変更を跨いだパスで正しい応答まで捨てる** | scan 結果 1 回分の損失 | **意図した代償**(fail-closed)。次の保存・コマンドで撃ち直せる。A13b-4 が過剰拒否でないことを固定 |
| **応答が返らない要求があると汚染が解けない** | そのパスの**詳細が出ない**(診断には影響しない) | **仕様**(fail-closed)。解除は**証明可能なバリア = 接続更新**のみ。**期限切れは証明にならないので使わない。** A13b-3 / A13b-6 |
| **LS 再起動を跨いだ旧 session の遅延応答** | 停止済み / 第三者のプロセスへ接続 | `(url, session)` 束縛と読み出し時照合(§11)。A14 |
| **`directUris` の本番配線漏れ** | **コンパイルは通るが機能が常に `NotAdvertised`** | §16.1 で 2 生成箇所を名指し。A15 |
| **`updateDetails` 送信失敗で前の所見が残る** | 誤表示(B を見ているつもりで A) | **LS 不在は送信前に検知(A18)。接続が生きている場合の送信失敗のみ検知手段が無い**(§12 / U6)。緩和は payload の `filePath` + 行番号。**PR の「既知の制限」へ転記** |
| **エスケープでコードスパン・URL が劣化する** | XSS 系所見の表示が読みにくくなる | **実行はされない。** 既知の制限として PR へ転記し、A17b で挙動を固定(§14 / U8) |
| **`$/gitlab/plugin/request` の名前衝突** | **実機でのみ `ClassCastException`**(headless の fake proxy では通る) | 戻り値型を `Any?` に固定(§8.3 / A20) |
| **OAuth トークン更新で fingerprint が変わる** | 同一アカウントのまま所見が消える | fail-safe(消えるだけ)。代替成分を U7 で継続検討 |
| `plugin.xml` の並行衝突 | マージ時の手戻り | #20 §4 の型で解決 |
| `directUris` シームが既存解決を乱す | 既存 webview が壊れる | 既定を「無し」にし、既存経路のテストを keep-behaviour で固定(A15) |
| 将来バンドルがサニタイズを始める | 二重エスケープの表示崩れ | 実行はされない。**LS 更新時の確認項目として PR に残す**(§14) |

---

## 22. レビュー反映履歴

### 第 2 版(2026-09-21)— Codex レビュー round 1 反映

PR #88 の Codex レビューで **P1×7 + P2×1**。**8 件すべて妥当と判断し、全件反映した。**
うち 3 件は**実ソースで裏取りした結果、指摘のほうが正しいことを確認**している。

> **反映先**: 8 件はいずれも**本文の該当節に反映済み**で、本節はその記録にすぎない。
> 本文と本節が食い違う場合は**本文が正**。

| 指摘 | 反映した本文の節 |
|---|---|
| P1-a | §5.3 N5 / §7 / §8.1 / §9.1 / §10 / §11 / §14 / §15 / §16 / §19 A13 / §20 U7 / §21 |
| P1-b | §7 / §8.1 / §8.2 / §9.3 / §10 / §11 / §12 / §19 A14 / §21 |
| P1-c | §7 / §16 / **§16.1** / §18 / §19 A15 / §21 |
| P1-d | §9.2 / **§12「★ 認識している限界」** / §18 / §20 U6 / §21 |
| P1-e | §8.2 / §9.4 / §12 / §15 / §18 / §19 A16 |
| P1-f | §5.3 N6 / §7 / §8.1 / §9.2 / **§10.1** / **§14** / §18 / §19 A17・A17b / §20 U8 / §21 |
| P1-g | §8.1 / §10 / §11 / §18 / §19 A19 |
| P2-a | §8.1 / §9.2 / **§10.1** / §12 / §18 / §19 A4b / §21 |

**round 2(P1-h)の反映先**: §8.1 / §9.1 / §11 / §16 / §18 / §19 / §21 / §22 第 4 版。
**※ 第 4 版で採った `ScanContextLedger`(パス別 FIFO + 隔離境界)は、第 5 版で撤回されている。**
**round 3(P1-i)の反映先**: §8.1(`ScanFlightTracker`)/ **§9.1「飛行中の応答の扱い」全面改稿** /
§11 / §16 / §18 / §19 A13b・A13b-1〜6 / §21 / §22 第 5 版。

第 3 版で追加・修正した節(自己レビュー分): §8.3(`pluginRequest` の名前衝突)/ §9.1(要求時 fingerprint)/
§9.2 手順 6(LS 不在の事前確認)/ §12(限界の縮小・根拠の訂正)/ §14(表示劣化)/ §16.1(本番ファクトリ)/
§19 A13b・A15b・A17b・A18・A19・A20 / §20 U7・U8。

| # | 指摘 | 検証結果と反映 |
|---|---|---|
| P1-a | 所見を**認証・設定世代**でも無効化せよ。接続世代だけでは、同じ接続に URL / トークン / スキャン無効化を送っても世代が変わらず、**前のアカウントで取得した所見が見え続ける** | **妥当。認可境界の問題。** §11 に **scan context fingerprint** を追加(成分は `GitLabLanguageServerConfigurationParams` の **`baseUrl` / `token` / `featureFlags.remoteSecurityScans` / `securityScannerOptions.enabled`** の不可逆ダイジェスト。§20 U7)。**fingerprint が変わったら store を消す** — `didChangeConfiguration` を送る 2 箇所の `outboundLock` 区間で行うので、送信と消去が不可分(§9.1)。飛行中の応答も、着信時に fingerprint が一致しなければ捨てる。要件 N5・受け入れ条件 A13 を追加 |
| P1-b | Knowledge Graph の URL を**送信元セッションに束縛**せよ。LS 再起動中に旧接続の遅延 `ready` / `getUrl` が新接続の値を上書きすると、**停止済みの `gkg` を指す URL に現行セッションが付いて `Resolved` になり、既存の session 検査を素通りする** | **妥当。** この種の「遅延した旧接続の応答」は本プロジェクトが過去に踏んだ型(#20 の superseded process ガード)。`KnowledgeGraphState` を **`(url, session)` の組**にし、`record` は送信元 session を受け取って**現行でなければ拒否**、**読み出しも現行 session と一致するときだけ返す**。送信元 session は既存の仕組みで得られる(`PluginRegistry.kt:32` の末尾パラメータ注入 / `GitLabLanguageServerClient.session`)。**明示的な clear フックは置かない** — 読み出し時照合で同じ保証が取りこぼし無く得られるため(§11)。再起動競合テストを A14 に追加 |
| P1-c | **`directUris` の本番配線が設計に無い。** `WebviewUriResolver` は 2 箇所で直接生成されており、シームは既定値のまま死ぬ | **妥当。実ソースで確認** — `WebviewEditorPart.kt:41` と `AgenticTabsView.kt:27` がいずれも `WebviewUriResolver(languageServerWrapper)` と生成している。**このまま実装すればコンパイルは通るが Knowledge Graph は常に `NotAdvertised`。** §16 に **両生成箇所へ `KnowledgeGraphState::directWebviewFor` を渡す**ことを明記し、「既存 webview が metadata 経路のままであること」を keep-behaviour テストで固定する(A15) |
| P1-d | **送信失敗時に古い所見を表示し続けない。** 共有タブが所見 A を表示中に B の `updateDetails` が届かなければ、ユーザーには B に見える | **妥当。私の「LS が再生するので再送不要」は、遅れて接続した webview にしか効かない。** ただし**プロトコルに ack が無く**、lsp4j は `notify` の失敗を握り潰す(#20 の既知事項)ため、**クライアントから検知する手段が無い**。§12 に**限界として明記**し、緩和として「ペイロードに `filePath` と行番号が必ず含まれるので、**右クリックした箇所と表示が食い違えばユーザーが気づける**」ことを記す。**検知できない以上、検知できるふりをする機構は作らない** |
| P1-e | `openUrl` に**制限付き・非記録**のランチャーを使え。`BrowserLauncher` は scheme/host を制限せず、失敗時に URL 全文を記録する | **妥当。しかも既に正解が存在する** — PR #85 で入れた **`ShowDocumentLauncher`** が `isBrowsableExternalUrl`(絶対 http/https・host あり・userinfo なし)で拒否し、**URI もメッセージも一切ログに出さない**。§8.2 の `BrowserLauncher` を **`ShowDocumentLauncher` に差し替える**。`file:` / `javascript:` / userinfo 付きの拒否と、ログ陰性を A16 に追加 |
| P1-f | **markdown サニタイズを検証済みの契約にせよ。** 「LS バンドルの責務」と宣言するだけでは XSS の緩和にならない | **妥当。実バンドルを読んだ結果、指摘が正しいことが確定した** — `build/gitlab-lsp/bin/webviews/security-vuln-details/assets/index-Bhmygly-.js` の描画関数は **`Rh(t) = Dn.parse(t.toString())`**、すなわち **`marked` をオプション無しで呼んでおり `sanitize` は既定の `false`**。その結果が Vue の **`domProps: { innerHTML: markdownContent }`** に入る。**バンドルはサニタイズしていない。** → §14 を全面改稿し、**クライアント側で投影・検証・HTML エスケープする**方式に変更(`VulnerabilityProjection`)。**参照実装からの意図的な乖離**であることを明記 |
| P1-g | store の更新と上限 eviction を**原子的**にせよ。`ConcurrentHashMap` は複合操作を守らない | **設計記述が不正確だった(実装は既に正しい)。** `Collections.synchronizedMap` + `LinkedHashMap(accessOrder=true)` の `removeEldestEntry` を使っており、put と eviction は**同一ロック下の 1 操作**。§11 の「`ConcurrentHashMap`」という記述を実装に合わせて訂正。順序は timestamp ではなく **LinkedHashMap のアクセス順**で決まるので、`timestamp` が null でも破綻しない |
| P2-a | `start_line` しか検証しないため、**そこだけ数値で他フィールドが異型の所見**が選ばれて生のまま渡る | **妥当。** P1-f の対応と同じ `VulnerabilityProjection` で解決した。**描画対象 5 フィールドすべてを個別に検証・正規化**し、`location` が使えない 1 件だけを除外、表示テキストが欠けるだけの所見は安全な既定値で描画する |

#### §14 の改稿(P1-f)

**旧**: 外部データの markdown→HTML 展開は LS 配信バンドルの責務であり、本プラグインは内容に手を触れない。

**新**: **同梱バンドルはサニタイズしない**(上記の実ソース根拠)。したがって**エスケープはクライアントの責務**である。
`VulnerabilityProjection` が描画対象フィールドを投影する際に `& < > " '` をエスケープする。
**strip ではなく escape** — markdown は見出し・箇条書き・コード・リンクのいずれにもこの 5 文字を必要としないため、正当な description は**見た目が変わらない**。HTML を含む description は**実行されずテキストとして見える**。

#### §10 の改稿(P2-a / P1-f)

**旧**: 所見を無改変で webview へ渡す。

**新**: `VulnerabilityLookup` が**所見を選ぶ**(`location.start_line` のみを見る。型を仮定しない)。
`VulnerabilityProjection` が**選ばれた 1 件を webview 用に投影する**(5 フィールドを検証・正規化・エスケープ)。
無改変で渡すのをやめた理由は §14 の改稿と同じ。

### 実装状況(第 2 版時点)

**純ロジックは実装・検証済み**(`feat/webview-remnants` @ 1 コミット目、新規テスト 55 本、`FAILSET_IDENTICAL`、detekt ベースラインちょうど)。
`VulnerabilityStore` / `VulnerabilityLookup` / `VulnerabilityProjection` / `VulnerabilityPayload` / `KnowledgeGraphState` / `DirectWebview` / `WebviewUriResolver` のシーム。

**未実装(次に着手する順)**: P1-a の fingerprint(**`ScanFlightTracker` による静穏判定を含む。§9.1 の第 5 版が確定版**)/ P1-b の session 束縛 /
P1-c の本番ファクトリと配線(**シーム型を `(String, LanguageServerSession) -> DirectWebview?` へ変更**)/
P1-e のランチャー差し替え / 受信配線(`GitLabLanguageServerClient`)/ 送信クライアント(**`pluginRequest` の
戻り値型は `Any?`**)/ ハンドラ 2 件 / `plugin.xml` / Koin 登録。

### 第 3 版(2026-09-22)— 反映内容の自己レビュー

第 2 版の本文反映を独立にレビューし、**新たに P1×2・P2×7・P3×8** を摘出して反映した。要点:

| # | 内容 |
|---|---|
| **P1-1** | **第 2 版の「飛行中の応答を着信時に拒否」は機構が無かった。** 着信時点の現行値で記録して現行値と照合すれば**常に一致する**(トートロジー)。応答は要求を識別する値を持たない。→ **要求時 fingerprint をパスごとに控える**方式へ修正(§9.1)。`runSecurityScan` の送信箇所が `outboundLock` 内の 1 つだけで、そこに `buildParams()` の結果があることを確認済み |
| **P1-2** | **`$/gitlab/plugin/request` はクライアント側に既に存在する**(`GitLabLanguageServerClient.kt:202-203`)。lsp4j は local 側で remote 側を上書きするため、**サーバ側に DTO 戻り値を宣言しても `Object` で返る**。第 2 版の「新規メソッドなので該当しない」は誤り。§8.3 に確定事実として追加し、戻り値型を `Any?` に固定(A20) |
| P2 群 | §14 の「見た目が変わらない」を訂正(コードスパン・URL が劣化する)/ シームのシグネチャと「現行 session」の入手元を 1 つに確定 / A15 を自動・目視に分割(生成箇所は headless で呼べない)/ P1-d の限界を「接続が生きている場合の送信失敗」に縮小し、LS 不在は送信前に検知(A18)/ `didChangeConfiguration` の**3 箇所目**(部分送信)を fingerprint の対象外と明記 / P1-g の受け入れ条件 A19 を追加 |
| P3 群 | ベースを `0319421`(PR #87 マージ済)へ更新 / `gkg` の spawn 引数と起動タイミング / OAuth トークン更新で fingerprint が変わる問題を U7 へ / `ready` payload のログ露出 / `ShowDocumentLauncher` のログ文言 |


**根拠の訂正**: 第 2 版は lsp4j の notify 握り潰しを「#20 の既知事項」としていたが、**#20 に該当記述は無い。**
根拠は `RemoteEndpoint.notify` の実装そのもの(§12)。あわせて、**既存コメント
`SecurityScanLauncher.kt:306-308` が逆のことを主張している**ことを申し送りに含めた。

**実装側の追従が必要な箇所(設計が先行している)**: `VulnerabilityPayload` の KDoc が旧 §14
(「所見を無改変で渡す」)を参照している / `KnowledgeGraphState` の KDoc が「last-write-wins で調整不要」
「接続が消えたら `clear()`」と書いており §11 と食い違う(**`clear()` を残すか消すかを実装時に決める**)/
`VulnerabilityStore` の KDoc が "least recently **written**" と書いているが `accessOrder = true` なので
**アクセス順が正**。

### 第 4 版(2026-09-22)— Codex レビュー round 2 反映

> **★ この版で採った `ScanContextLedger`(パス別 FIFO + 隔離境界)は第 5 版で撤回された。**
> 指摘 P1-h の**診断は正しかった**が、**処方が不十分**だった —— 順序保証が無い前提では
> 隔離境界も破れる(round 3 / P1-i)。**以下は経緯の記録であり、設計の現行値ではない。**
> **現行の方式は §9.1 と第 5 版を見ること。**

round 2 の指摘は **P1×1**。**妥当と判断し、実コード・プロトコルで裏を取ったうえで反映した。**

| # | 指摘 | 検証結果と反映 |
|---|---|---|
| **P1-h** | **同一パスの要求を単一 fingerprint で上書きしない。** fingerprint A の scan が飛行中に設定を B へ変え、同じパスを B で再要求すると、応答 A が B のエントリを消費して現行 B と一致し、**前アカウント由来の所見が記録される**。正しい応答 B は対応エントリを失う。「要求順に返る」前提はこの誤対応を**確定させる** | **妥当。第 3 版の退行で、round 1 の P1-a が正しく閉じていなかった。** 「同一パスへの再要求は上書きでよい」と書いたのは誤り。**実在の条件であることも確認**: 重複排除もデバウンスも無く、`SecurityScanLauncher.kt:399` が「応答期限が来るころには同じファイルに別の scan が queue されている可能性がある」と明言、期限は `RESPONSE_DEADLINE_MS = 60_000`(`:41`)。→ **`ScanContextLedger`(パス別 FIFO + 隔離)へ置き換え**(§9.1) |

**この形は新規発明ではない。** `CommandWaiters` が**同一の問題**(同一パスに複数要求・応答は要求を識別しない)を
**パス別 FIFO の `consumeOldest`** で解いており(`CommandWaiters.kt`)、**構造・モニタ・ロック順序をそのまま踏襲**した。

**順序保証にも依存しない形にした。** 単純な FIFO 照合は、応答が入れ替わると安全側に倒れない
(`[FA, FB]` で応答 B が先に着くと `FA` を消費して拒否し、続く応答 A が `FB` と**一致してしまう**)。
**そのパスの飛行分を捨てきるまで拒否し続ける「隔離」**にすることで、**順序が崩れても旧文脈の所見は記録されない。**

**代償を明示した**: 設定変更を跨いだパスでは正しい応答も捨てる(fail-closed)。失うのは scan 結果 1 回分で、
次の保存・コマンドで撃ち直せる。**過剰拒否になっていないこと**は A13b-4 で固定する。

**応答欠落で恒久停止しないこと**も設計に入れた: 滞留エントリは `RESPONSE_DEADLINE_MS`(既存値を流用・新定数を作らない)
で失効し、キュー長とパス数に上限を置く。**隔離は最大でも「汚染された最後の要求から 60 秒 + 次の応答 1 件」で必ず解ける**(A13b-3)。

**自己レビューで 1 段階詰めた点**: 隔離を**真偽値 +「キューが空なら解除」**にすると、滞留中に新しい要求が入った場合に
**失効後の正しい応答が 1 件だけ余分に拒否される**。**`quarantineUntilSeq` という境界**で持てばこの穴が無い。
`seq` は `CommandWaiters.ids` と同じく**前進のみで、`clear` でも世代交代でもリセットしない**
(番号の再利用は、古い判定を別の新しい scan に適用させる)。

### 第 5 版(2026-09-22)— Codex レビュー round 3 反映

round 3 の指摘は **P1×1**。**妥当と判断し、方式そのものを置き換えた。**

| # | 指摘 | 検証結果と反映 |
|---|---|---|
| **P1-i** | **順序保証が無い前提では隔離境界も破れる。** `応答 B 先着 → 隔離中に要求 C → 応答 C が境界を解除 → 遅延応答 A が C のエントリと照合されて一致` で旧所見が記録される。**期限切れ・上限 eviction も同じ**(エントリを捨てても対応する応答は取り消せない) | **妥当。** 第 3 版(単一スロット)・第 4 版(FIFO + 隔離境界)は**同じ根本原因**で破れていた ―― **「エントリを消費したこと」が「対応する応答が返ったこと」の証明になっていない。** → **応答を要求に対応づける方式を全面的に捨て、`ScanFlightTracker` へ置き換えた**(§9.1) |

**実ソースで前提を確定させた**(第 4 版までは推測が混じっていた):

- **応答は要求を識別しない** — `SecurityScanClientResponse = { filePath, status, results, timestamp }`
  (`security_diagnostics_publisher.ts:184-189, 235-240`)。
- **サーバは順序を保証しない** — `handleScanNotification` は **`async` な通知ハンドラ**で、
  ディスパッチャは完了を待たない(`:108-113`)。**既存コードの「サーバは要求順に答える」という仮定は
  本機能では使えない。**
- **1 要求 → 応答は 0 件か 1 件。2 件は無い** — 成功 `:191` / 失敗 `:242` が排他。
  **`:121` / `:141` / `:144` / `:147` の早期 return は応答を返さない。**

**採用した方式 = 「静穏になるまで記録しない」。** 対応づけを試みず、**未着件数だけ**を数える。
1 要求に対する応答が **2 件にならない**ので、**`送信数(P) == 受信数(P)` が成り立てば、それ以前の要求は
すべて応答済み**であり、**順序にも期限にも依存せず**旧文脈の応答が残っていないことを**証明できる。**
状態はパスあたり**整数 1 個と真偽値 1 個**で、**第 4 版の台帳より単純**である
(キュー・`seq`・失効・上限・隔離境界がすべて不要になった)。

**比較して採らなかった案**(ユーザ指示により明示):

| 案 | 判定 |
|---|---|
| 要求 ID を応答へ往復させる | **不可**。応答スキーマは同梱 LS のもの |
| 順序保証を契約・検証する | **不可**。`handleScanNotification` が非同期で重なる。クライアントから検証もできない |
| **パスごとに同時飛行 1 件**(後続要求をバリアまで送らない) | **採らない。** 実現には**要求の送信を抑止**することになり、「保存したのにスキャンされない」= **PR #51 の診断の既存挙動を変える**。**クライアントが制御してよいのは「何を記録するか」であって「何を送るか」ではない** |
| **応答欠落時は fail-closed で接続更新までブロック** | **採用**(本方式の限界の扱いとしてそのまま組み込んだ)。応答が返らない要求があるパスは、**証明可能なバリア = 接続更新まで**汚染されたまま。**期限切れはバリアとして使わない** |

**受け入れ条件を入れ替えた**: A13b-1 は **round 3 の筋書きそのもの**(`応答 B → 要求 C → 応答 C → 遅延応答 A`)、
A13b-3 は**期限超過の遅延応答**、A13b-6 は**応答欠落の fail-closed と接続更新での解除**。
A13b-2 / A13b-4 / A13b-5 で**恒久化しないこと・過剰拒否でないこと・汚染がパス単位であること**を固定する。
