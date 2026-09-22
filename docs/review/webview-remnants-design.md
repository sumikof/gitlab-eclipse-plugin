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
- **★ 前提 Q / R(§9.1)は同梱 LS 9.3.0 に対する全列挙で証明したものであり、`package.json` の
  gitlab-lsp を上げるときは再検証する。** 具体的には
  (a) `sendScanResponse` 相当の呼び出し元が `security_diagnostics_publisher.ts` の 2 箇所のままか、
  (b) `handleScanNotification` の呼び出し元が `onNotification(RemoteSecurityScanNotificationType)` だけか、
  (c) 応答を返さない早期 return の数と位置、
  (d) 応答スキーマに要求識別子が増えていないか(増えていれば**この方式ごと単純化できる**)。
  **手順は `main.js.map` の `sourcesContent` を全展開して grep するだけ**で、5 分で終わる。
  **PR の「LS 更新時の確認項目」へ転記する。**

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
| `VulnerabilityIntake` | **★ 第 6 版で新設。`ScanFlightTracker` と `VulnerabilityStore` を `DiagnosticGenerationRegistry.lock` の単一区間で束ねる唯一の入口。** 公開するのは **5 つだけ**: `onRequestSent(path, capturedEpoch)` / `onContextChanged(newFingerprint, capturedEpoch)` / `onResponse(..., connectionEpoch)` / **`onConnectionClosed(deadEpoch)`**(第 7 版で追加)/ `read(path, epoch)`。**判定と記録を別々に呼べる API を外へ出さない**(§9.1「判定と記録は一操作」)。**書き込み側 4 つはすべて epoch を受け取り、区間の中で現行世代と照合してから状態を変える**(§9.1「★ 世代の束縛」) |
| `VulnerabilityStore` | パス → (所見リスト, timestamp, 世代, **scan context fingerprint**)。**記録・順序採番・上限 eviction は単一ロック下の 1 操作**(`LinkedHashMap(accessOrder = true)` の `removeEldestEntry`)。**自前の錠は持たない** —— 守るのは `VulnerabilityIntake` が取る `DiagnosticGenerationRegistry.lock`(第 6 版)。記録は `RecordPermit` を受け取り、**現行の世代・fingerprint と突き合わせてから**書く |
| `VulnerabilityLookup` | **所見を「選ぶ」だけ。** 所見の生 `Map` から `location.start_line` を取り出し、カーソル行に一致する先頭 1 件を返す。**型は仮定しない**(LS の形が変わっても落ちない) |
| `ScanFlightTracker` | **パスごとに「未着の scan 要求の件数」と「fingerprint 変更時に飛行中だったか」だけを持つ**(§9.1)。状態は**パスあたり整数 1 個と真偽値 1 個**、加えて**全体で保持世代 `heldGeneration` 1 個**(第 7 版)。送信で `+1`、応答で `−1`、汚染中は記録させず、`0` に戻った時点で汚染を解く。**キューも期限も上限も持たない。自前の錠も持たない**(`VulnerabilityIntake` の区間の中でのみ触られる) |
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
3. **加えて `VulnerabilityIntake.onResponse(...)`** —— **世代照合 → 未着件数の減算 → 汚染判定 →
   記録可否の確定 → `VulnerabilityStore` への記録(または消去)を、`DiagnosticGenerationRegistry.lock`
   の単一区間で 1 操作として行う**(下記「★★ 判定と記録は一操作でなければならない」)。
   **「要求時 fingerprint」というパラメータは存在しない**(第 6 版で削除)。第 5 版で応答と要求の
   対応づけを廃止した時点で、この値は**原理的に取得不能**になっていた。記録に使うのは
   **判定とまったく同じ区間で読んだ現行 fingerprint** であり、判定と記録の間に fingerprint が
   動く余地は無い —— 区間が 1 つだから。
4. `results` が null / 空なら、そのパスの記録を**消す**(所見が無くなった状態を残さない)。
   **この消去も手順 3 と同じ区間の中**で行う(記録と消去は同じ許可判定の下にある)。

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

##### ★★ 前提 Q: 受信する応答はすべて「クライアントが送り tracker が数えた 1 要求」に由来する(第 6 版で証明)

**静穏の証明は「各要求の応答が最大 1 件」だけでは足りない。**
**計数されていない応答が 1 件でも先着すれば、それが未着件数を 0 にして汚染を解いてしまう**(round 4 / P1-2)。
したがって成功・失敗経路の排他性ではなく、**「この通知を作れる経路がほかに無い」ことの全列挙**が要る。
**同梱 LS 9.3.0 の sourcemap(`build/gitlab-lsp/bin/main.js.map`、2,839 sources)を全展開して列挙した結果、
経路は一本道で閉じている。**

| # | 主張 | 全列挙による根拠(同梱 LS 9.3.0) |
|---|---|---|
| **Q1** | `$/gitlab/security/remoteSecurityScan/response` を**生成できるのは 1 箇所だけ** | `DefaultSecurityScanNotifier.sendScanResponse`(`security_notifier.ts:18-23`)。その `#notify` は `registerInitializeNotifier(RemoteSecurityResponseScanNotificationType, …)`(`node_connection_service.ts:81-84`)から `createNotifyFn = (param) => c.sendNotification(method, param)`(`connection_service.ts:42-45` / `:198-199`)として注入される。**ほかに `RemoteSecurityResponseScanNotificationType` を送る箇所は無い** |
| **Q2** | `sendScanResponse` の**呼び出し元は 2 つだけ** | `security_diagnostics_publisher.ts:191`(成功)と `:242`(`#handleError` 内)。**バンドル全体の grep で他に無し** |
| **Q3** | `#handleError` の**呼び出し元は 1 つだけ** | `:193` ―― `#runSecurityScan` の `catch` |
| **Q4** | `#runSecurityScan`(private)の**呼び出し元は 1 つだけ** | `:112` ―― `handleScanNotification` |
| **Q5** | `handleScanNotification` の**呼び出し元は 1 つだけ**で、それは**クライアント通知のハンドラ** | `node_connection_service.ts:91`。その直上 `:90` が `this.#connection.onNotification(RemoteSecurityScanNotificationType, (params) => { … })`。**タイマも監視も document 変更フックも、この経路には入らない** |
| **Q6** | **クライアント側の送信も 1 箇所だけ**で、それは**計数する場所そのもの** | `SecurityScanLauncher.kt:304` の `target.runSecurityScan(params)`。`$/gitlab/security/remoteSecurityScan` を送る箇所は本プラグインに他に無い(`GitLabLanguageServer.kt:87-88` が唯一の宣言) |

**Q1〜Q6 の帰結** = **LS は自発的な応答を送らない。受信するすべての応答は、クライアントが送り、
かつ `outstanding(P) += 1` を通った 1 要求の下流である。**

##### ★★ 前提 R: 1 要求に対して**観測される**応答は 0 件か 1 件(第 6 版で穴を塞いだ)

**第 5 版の「成功 `:191` / 失敗 `:242` が排他だから 2 件は無い」は不正確だった。**
`:191` の送信は **`try` ブロックの中**にあり、**その送信が reject すると `catch` が `:242` で 2 通目を送る。**
呼び出し回数としては **1 要求に対し最大 2 回**ありうる。
**しかし「クライアントが観測する応答」は 0 件か 1 件にしかならない。** 同梱の `vscode-jsonrpc` で確定した
(バンドルには `node_modules/vscode-jsonrpc/lib/common/` と
`node_modules/vscode-languageserver-protocol/node_modules/vscode-jsonrpc/lib/common/` の**2 コピーが入っているが、
下記の行番号は両方で一致する**):

| 送信が reject する条件 | 実際に流れたバイト | 根拠 |
|---|---|---|
| 接続が closed / disposed | **0 バイト**(同期 throw で `messageWriter` に到達しない) | `connection.js:910` `throwIfClosedOrDisposed()` → `:950` の `messageWriter.write` に届かない |
| シリアライズ失敗 | **0 バイト** | `messageWriter.js:76-95`。`Semaphore(1)` で直列化され、encode の失敗は `fireError` して throw。`doWrite` を呼ばない |
| ヘッダ書き込み失敗 | **0 バイト** | `messageWriter.js:97-105` `doWrite` の 1 行目 |
| 本文書き込み失敗 | **`Content-Length` ヘッダだけ**が流れる = **メッセージとして不完全** | 同 `:99-105`。受信側リーダは同期を失い、**その接続はもう使えない** |

⇒ **reject した送信が「完全で解釈可能な 1 通」を届けることはない。**
したがって 2 通目が送られる場合、1 通目は**観測されない**。
唯一の例外である「本文の途中で切れたストリーム」は**接続の破断**そのものであり、
**それは本方式のバリア(接続世代の前進)が効く条件と同一**である。

##### ★★ 前提 S: 送信側の計数キーと受信側の減算キーは一致する

**±1 が別のキーに落ちると、そのパスは永久に静穏を証明できない。**

- 送信側は `DiagnosticUri.normalize(uri)`(`SecurityScanLauncher.kt` の `launch`)。
- 受信側は `securityScanPathKey(filePath)`(`GitLabLanguageServerClient.kt:126`)= 同じ `DiagnosticUri.normalize`。
- **LS の失敗経路は `filePath ?? documentSource.toString()` を返す**(`security_diagnostics_publisher.ts:193`)。
  `filePath` が決まる前(`:150`)に失敗すると、**パスではなく URI 文字列**(`file:///w/a.kt`)が載る。
  `securityScanPathKey` は **URI 形式と素のパスを同じキーへ正規化する**ので、これは問題にならない
  (`SecurityScanStatusReporterTest.kt:478-489` / `:529` が `securityScanPathKey(osPath) == DiagnosticUri.normalize(sentUri)` を固定済み)。
- **`filePath` が null の応答はクライアントが早期 return する**(`GitLabLanguageServerClient.kt:126` の `?: return`)。
  **減算されない = そのパスは汚染されたまま**(fail-closed)。LS 9.3.0 は null を送らないが、DTO 上は可能なので
  **「減らないほうへ倒れる」ことを明記しておく。**

##### 応答を返さない経路(**5 つ**。第 5 版は 4 つと書いていた)

| 行 | 条件 |
|---|---|
| `:121` | 文書が取れない |
| `:141` | クライアント機能フラグ `RemoteSecurityScans` が無効 |
| `:144` | `securityScannerOptions.enabled` が false |
| `:147` | `securityScannerOptions` 自体が無い |
| **`:173-175`** | **★ 第 6 版で追加。API 呼び出しは成功したが `vulnerabilities` が配列でない / null。** 前の 4 つと違い**設定とは無関係に起こりうる**ので、後述の fail-closed の頻度見積もりはこれを含めて考える |

**この 5 つはいずれも「応答が 0 件」であって「2 件」ではない。** つまり**計数は過小にはなっても過大にはならない**(= 安全側)。

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
**前提 Q**(全応答は計数済みの 1 要求の下流)と **前提 R**(1 要求につき観測される応答は 0 件か 1 件)と
**前提 S**(±1 のキーが一致する)がそろうと、
**`送信数(P) == 受信数(P)` が成り立った時点で、それ以前に送った要求は「すべて応答済み」**であり、
**順序に関係なく**、旧文脈の応答が飛行中に残っていないことが**証明できる。**
**この 3 つは本方式が寄りかかっている前提のすべてであり、どれかが崩れれば方式ごと崩れる。**

| 状態 | 内容 |
|---|---|
| `heldGeneration` | **この `byPath` がどの接続世代のものか**(第 7 版。§9.1「★ 世代の束縛」の遅延ロールオーバで使う) |
| `outstanding(P)` | 送信済みで未着の scan 要求の件数。送信で +1、応答で −1 |
| `contaminated(P)` | **fingerprint 変更の瞬間に `outstanding(P) > 0` だったか** |

**規則はこれだけ**(いずれも **`DiagnosticGenerationRegistry.lock` の単一区間**で実行する。後述):

1. **送信時**(`SecurityScanLauncher.kt:304` の直前、`outboundLock` 区間の内側) → `outstanding(P) += 1`。
2. **fingerprint 変更時**(全量送信の 2 箇所、同じ `outboundLock` 区間の内側) →
   **1 つの区間で**「前回値との比較 → fingerprint の更新 → `outstanding(P) > 0` のパスを `contaminated` に →
   `VulnerabilityStore.clear()`」を行う。
   (飛行中が無いパスは汚染しない。**以後そのパスに届く応答は、変更後に送った要求のものしかありえない。**)
3. **応答着信時** —— **1 つの区間で**次をすべて行い、**途中で区間を出ない**:
   - 接続世代が不一致 → 拒否(既存規律)。
   - `outstanding(P) > 0` なら `outstanding(P) -= 1`。
   - **`contaminated(P)` なら拒否**(記録しない)。**そのうえで `outstanding(P) == 0` になったら汚染を解く**
     — この時点で**変更前の要求はすべて応答済みであることが証明された**から。
   - 汚染されていなければ、**同じ区間で読んだ現行 fingerprint と現行世代を `RecordPermit` として捕捉し、
     そのまま `VulnerabilityStore` へ記録(`results` が null / 空なら消去)する。**

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
- **未着件数が 0 なのに応答が届いた場合** — **前提 Q / R / S のどれかが崩れている証拠**なので、
  **その応答を拒否し、そのパスを汚染する。** 件数は 0 未満にしない。
  **前提が崩れたときに記録を続けるほうが危険**であり、解除は他と同じくバリアに委ねる。
  **これが前提違反の唯一の観測点**であり、LS を上げたときの安全網でもある(§6 の再検証手順)。

##### ★★ 判定と記録は一操作でなければならない(第 6 版 / round 4 P1-1)

**第 5 版は「どの応答を記録してよいか」は定めたが、「判定した結果がいつ store に着くか」を定めていなかった。**
判定(tracker)と記録(store)と消去(clear)が**別々の区間**だと、判定と記録の隙間に変更側が丸ごと入り込む:

> **破れる筋書き(round 4)**: FA の応答が着信し、`outstanding(P)` が 0 になって **「記録可」と判定される。**
> 判定区間を出た直後、別スレッドが fingerprint を FB へ変更する。**このとき `outstanding(P)` は既に 0 なので
> 変更側はこのパスを汚染しない**(規則 2 は正しく働いている)。変更側は `clear()` を済ませる。
> そのあとで応答側が記録を実行し、**現行値である FB を貼って FA の所見を書き戻す。**
> **clear を生き延びた FA の所見が、FB の所見として読み出せる。** N5 の認可境界が破れる。

**根本原因は「現行 fingerprint を読む時点」が「記録してよいと決めた時点」より後にあること。**
対策は 1 つしかない —— **同じ錠の同じ区間に全部入れる。**

###### なぜ `outboundLock` では駄目か(共有できる錠は 1 つしかない)

- `outboundLock` は **`kotlinx.coroutines.sync.Mutex`**(`SecurityScanLauncher.kt:192` /
  `GitLabLanguageServerConfigurationService.kt:30`)。**`withLock` は `suspend` 関数**である。
- 応答を捌くのは **lsp4j のディスパッチスレッド**で、**コルーチンではない**
  (`GitLabLanguageServerClient.securityScanResponse` は `runAsync` すらしない。§11)。
  **受信側は `outboundLock` を取れない。** `runBlocking` で取るのは、送信を待つスレッドで
  受信を止めることになるので採らない。
- 逆に `VulnerabilityStore` 自前の錠(第 5 版の `Collections.synchronizedMap`)は**変更側と受信側で
  共有されるが、tracker の判定を含まない**。

⇒ **両側が取れて、tracker と store の両方を覆える錠は `DiagnosticGenerationRegistry.lock` だけである。**
これは**プレーンなモニタ**で、**UI スレッドから入ることを前提に設計されている**
(`DiagnosticGenerationRegistry.kt` の KDoc: 「UI スレッドから入ることもあるため
`kotlinx.coroutines.sync.Mutex` ではなく**プレーンなモニタ**である(runBlocking を避ける)」)。
**ロック順序「送信 Mutex → このモニタ」も既に固定されている**ので、新しい順序を導入しない。

###### 確定する規律

| 項目 | 規律 |
|---|---|
| **錠** | **`DiagnosticGenerationRegistry.lock` ただ 1 つ。** `ScanFlightTracker` と `VulnerabilityStore` は**2 つのオブジェクトだが 1 つの錠の下にある** |
| **`VulnerabilityStore` 自前の錠を廃止** | 第 5 版の `Collections.synchronizedMap` ラップを**外す**。二重施錠は無意味なうえ、「store は自分で自分を守る」という誤読を招く。**`LinkedHashMap(accessOrder = true)` と `removeEldestEntry` はそのまま**(P1-g / A19 は退行しない。`removeEldestEntry` は `put` の内側で走り、その `put` が上記の区間の中にある) |
| **入口** | 外から呼べるのは `VulnerabilityIntake` の**5 つだけ**: 書き込み側の `onRequestSent(path, capturedEpoch)` / `onContextChanged(newFingerprint, capturedEpoch)` / `onResponse(..., connectionEpoch)` / **`onConnectionClosed(deadEpoch)`** と、読み出しの `read(path, epoch)`。**tracker と store を個別に外から叩く API を公開しない**(公開すれば、いつか誰かが 2 回に分けて呼ぶ)。**`ScanFlightTracker.clear` を外から直接呼ぶ経路は作らない**(第 7 版 / round 5 R5-3) |
| **許可トークン** | `RecordPermit(path, epoch, fingerprint, regionNonce)`。**判定区間で生成され、同じ区間で消費される。** 値の一致だけでは「同じ区間」を証明できない(下記「★ permit は値の一致では守れない」)ので、**単回消費の区間 nonce と `Thread.holdsLock` の 2 つで束縛する**(第 7 版 / round 5 R5-2)。A13b-8 |
| **区間の中でしてよいこと** | **メモリ上の読み書きだけ。** 区間の中で**サスペンドしない・送信しない・UI スレッドへホップしない・ファイルに触れない**。したがってこのモニタを持ったまま `outboundLock` や UI スレッドを待つことは無く、**順序は一方向のまま**である |
| **読み出し(UI スレッド)** | 同じ区間に入り、**値のスナップショットだけ取って出る。** 投影(`VulnerabilityProjection`)も webview 送信も**区間の外**で行う |

###### ★ permit は値の一致では守れない(第 7 版 / round 5 R5-2)

**第 6 版は `RecordPermit(path, epoch, fingerprint)` を「現行値と突き合わせれば区間をまたいだと分かる」と書いたが、
これは成立しない。** 値の比較で落とせるのは「値が違う permit」だけで、**「同じ値を持つ、区間外で作られた permit」は
素通りする**。具体的に 2 つ抜ける:

| 抜け道 | なぜ値比較で落ちないか |
|---|---|
| **区間外で生成し、現行 epoch と現行 fingerprint を詰めた permit** | 値としては現行と一致するので比較は成功する |
| **ABA**: fingerprint が A → B → A と戻った後に、古い A の permit を使う | 現行値がふたたび A なので比較は成功する。**間に挟まった B の `clear()` は取り消されない**ので、**消えたはずの所見を書き戻せる** |

**したがって第 6 版の A13b-8(「区間外の permit は拒否される」)は、第 6 版の機構では原理的に達成できなかった。**
これは round 4 P1-1 の①と同じ型の誤り —— **受け入れ条件が機構の能力を超えて書かれていた。**

**2 段で塞ぐ**:

1. **第一の保証は字句スコープ(lexical containment)。**
   `RecordPermit` は **`VulnerabilityIntake` の private な入れ子型**にし、生成子も private にする。
   `VulnerabilityStore` の記録入口は permit を要求するので、**`VulnerabilityIntake` の外からは
   permit を構築できず、記録入口を呼べない。** 抜け道は「作れない」のが本筋であって、
   「作られたら検出する」は保険である。
2. **第二の保証は実行時の検出器。**
   permit に**単回消費の区間 nonce**を持たせる。nonce は**モニタ区間の中で単調増加のカウンタから採番**し、
   tracker 側に「いま生きている nonce」として 1 個だけ保持、**消費時に破棄**する。
   `VulnerabilityStore` の記録入口は、記録の前に次の 2 つを確かめる:
   - **`Thread.holdsLock(DiagnosticGenerationRegistry.lock)` が真である**
     (= 呼び出し元が本当にモニタを保持している。JVM 標準 API で、テストからも観測できる)。
   - **permit の nonce が「いま生きている nonce」と一致する**(= 同じ区間で採番されたもので、未消費)。

   **nonce は再利用しない**ので、**ABA も、区間外で現行値を詰めた permit も、使い回した permit も落ちる。**

> **★ この 2 段は「単一区間だから安全」という主張を置き換えるものではない。**
> 安全性の根拠はあくまで**単一区間**であり、nonce と `holdsLock` は
> **その前提が将来のリファクタリングで崩れたときに、静かにではなく大きな音で壊れるようにするため**にある。

###### ★ 世代の束縛 —— 書き込み側は必ず epoch を運ぶ(第 7 版 / round 5 R5-1)

**第 6 版の `onRequestSent(path)` / `onContextChanged(newFingerprint)` には世代が無く、
「接続世代スコープ」という前提を実装できなかった。**

**実在の条件**: `SecurityScanLauncher.launch` は **server と epoch をコルーチン実行より前に捕捉する**
(`val epoch = DiagnosticGenerationRegistry.currentEpoch` … `dispatch(params, path, source, server, epoch)`)。
実際の送信はその後、`outboundLock` を取ってから起きる。**捕捉と送信の間に再接続が入ると**:

> 旧 server へ送る処理が、**新世代の** tracker を `+1` する。
> その要求の応答は**旧接続のクライアント**に届き、**世代照合で拒否されて減算されない**。
> ⇒ **新世代に永久の残留 `+1` が残り、次の fingerprint 変更でそのパスが汚染され、接続更新まで解けない。**

`GitLabLanguageServerConfigurationService.sendConfiguration` も同じ形
(「Send to the server captured at CALL time」)なので、**旧接続向けの `onContextChanged` が
現行 store を消す**余地もある。

**規律**: **書き込み側の 4 操作はすべて epoch を引数に取り、区間の中で現行世代と照合してから状態を変える。**

| 操作 | 渡された epoch が現行世代でないとき |
|---|---|
| `onRequestSent(path, capturedEpoch)` | **何もしない**(`+1` しない)。その要求の応答は旧クライアントへ行き、どのみち減算されない。**`+1` も `−1` も起きないので釣り合う** |
| `onContextChanged(newFingerprint, capturedEpoch)` | **何もしない**(現行 store を消さない・現行パスを汚染しない)。旧世代の状態は `onConnectionClosed` かロールオーバが捨てる |
| `onResponse(…, connectionEpoch)` | **拒否**(既存規律。減算もしない) |
| `onConnectionClosed(deadEpoch)` | **これだけは性質が違う** —— `deadEpoch` は**現行でないのが当たり前**である。下記の規則で「保持中の世代がまさに `deadEpoch` のときだけ」捨てる |

**照合は必ず区間の中で行う** —— 区間の外で `currentEpoch` を読んでから入ると、読んだ瞬間に古くなる。

**★ 「その世代の状態」を実装可能にするために、tracker は保持中の世代を持つ。**
これを書かないと「旧世代の状態だけ捨てる」が実装者によって別物になる:

```
ScanFlightTracker の状態 = (heldGeneration: Long, byPath: Map<String, (outstanding, contaminated)>)
```

| 規則 | 内容 |
|---|---|
| **書き込み 3 操作の共通前処理** | `capturedEpoch != currentEpoch` なら**何もしないで戻る**。そうでなく `heldGeneration != currentEpoch` なら、**`byPath` を捨てて `heldGeneration = currentEpoch` にしてから**本体を適用する(**遅延ロールオーバ**) |
| **`onConnectionClosed(deadEpoch)`** | `heldGeneration == deadEpoch` のときだけ `byPath` を捨てる。**既に新しい世代へロールオーバ済みなら何もしない**(現行世代を巻き添えにしない) |

**遅延ロールオーバがあるので、`onConnectionClosed` が呼ばれ損ねても旧世代の件数が新世代へ漏れない。**
`onConnectionClosed` は**取りこぼしを早く解消するための明示的なバリア**であって、正しさの唯一の担保ではない
—— **両方あることが、fail-closed の解除が遅れすぎないことと、漏れないことの両方を保証する。**

**この形は新規発明ではない。** `CommandWaiters` が**同じ問題**を
**捕捉した epoch を引数で持ち回る**ことで解いている(`CommandWaiters.consumeById(waiterId, epoch)` /
`armDeadline(waiterId, path, epoch)`)。**構造をそのまま踏襲する。**

###### ★★ epoch と proxy は「同じ 1 回の読み取り」から取る(第 8 版 / round 6 R6-1)

**「捕捉した epoch を運ぶ」だけでは足りない。epoch と送信先 proxy が同じ接続のものでなければ、
世代照合はかえって害になる。**

**実在の条件**: `SecurityScanLauncher.launch` は **2 つを別々に読む**。

```kotlin
val epoch = DiagnosticGenerationRegistry.currentEpoch   // ← ここ
val enabled = isEnabled()
DiagnosticGenerationRegistry.reconcileSource(…)
val path = DiagnosticUri.normalize(uri)
…
val server = languageServerWrapper.languageServer       // ← と、ここ（6 文あと）
```

`GitLabLanguageServerConfigurationService.sendConfiguration()` も
`languageServerWrapper.languageServer` を単独で読む(`:37`)。**2 つの読み取りの間の再接続**で、
**食い違った組**ができる:

| 組 | 何が起きるか |
|---|---|
| **(旧 epoch, 新 server)** | `onRequestSent` は stale epoch として**何もしない**が、**要求は新 server へ実際に送られる**。その応答は新世代として**受理**され、`outstanding == 0` なので**前提違反と判定されてパスが汚染**される。**次の再接続まで詳細が出ない** |
| **(新 epoch, 旧 server)** | `onContextChanged` は現行世代として**通る**ので、**クライアントの追跡 fingerprint は進む**。しかし `didChangeConfiguration` は**死んだ接続へ送られて効かない**。生きている接続が独立に同じ設定へ行き着くかは readiness 経路次第であり、**「追跡 fingerprint が生きている接続の設定を表している」という本方式の土台が、偶然に依存する**。ここは**偶然に頼ってよい場所ではない** |

**第 7 版の A13b-11 は「両方を捕捉した後」の再接続しか固定していないので、この競合を検出できない。**

**規律**: **送信側は接続の identity を 1 回だけ読み、proxy と epoch をその 1 つの値から取る。**

**これも新規発明ではない。** 本リポジトリには**まさにこの目的の型が既にある**:

- `GitLabLanguageServerWrapper.currentSnapshot: LanguageServerHandle?` は `AtomicReference` から**1 回で読める**。
- `LanguageServerHandle` の KDoc がその意図を明言している ——
  「**Published as one immutable value so that a reader can never observe a new proxy paired with the
  session of the connection it replaced.**」
- `WebviewUriResolver` / `WebviewLoadCoordinator` は**既にこの読み方をしている**。

**足りないのは epoch だけ**なので、**`LanguageServerHandle` に接続 epoch を加える**:

```kotlin
data class LanguageServerHandle(
  val proxy: GitLabLanguageServer,
  val session: LanguageServerSession,
  val connectionEpoch: Long,   // ★ 第 8 版で追加
)
```

| 決めたこと | 理由 |
|---|---|
| **値は `GitLabLanguageServerClient` の `connectionEpoch` をそのまま複製する**(`GitLabLanguageServerProcessProvider.kt:155` は `client` を既にスコープに持つ) | **送信側が使う epoch が、受信側が照合に使う epoch と同一値であることが構成上保証される。** `DiagnosticGenerationRegistry.currentEpoch` を別に読む形だと、`onActivate` の走るタイミング次第でずれうる |
| **既定引数を付けない** | 既定値は「epoch を渡し忘れた呼び出し」を黙って通す = **本指摘の穴をそのまま再導入する**。構築箇所は**本番 1 つ**(`GitLabLanguageServerProcessProvider.kt:155`)と**テスト 8 箇所**(`GitLabLanguageServerWrapperTest.kt:131,133,177,178,189` / `WebviewUriResolverTest.kt:47,161` / `AgenticChatWebViewClientTest.kt:109`)だけなので、明示更新で足りる |
| **送信側は `currentSnapshot` を 1 回だけ読む** | `SecurityScanLauncher.launch` は `languageServer` ではなく `currentSnapshot` を読み、`handle.proxy` と `handle.connectionEpoch` を使う。`sendConfiguration()`(引数なし)も同様。`sendConfiguration(server)` は**handle を受け取る形へ**(`GitLabLanguageServerProcessProvider.kt:187` は `:155` の `handle` を既に持っている) |
| **`handle` が null なら送らない・数えない** | 既存の「No server means nothing was sent」と同じ扱い。**`+1` も `onContextChanged` も起こさない** |

> **★ 読み取りは 1 回でよく、ロックは要らない。** `AtomicReference.get()` が 1 回で不可分な値を返すので、
> **「2 つの読み取りの間」という窓がそもそも消える。** 読んだ直後に再接続しても、
> 手元の handle は**一貫した 1 つの接続**を指し続け、その epoch は区間の中の照合で stale と判定される
> —— **これは本節が既に扱っている正常な経路**である。

###### 区間のネスト

**変更側の区間のネスト**: `outboundLock.withLock { … synchronized(lock) { 世代照合・比較・更新・汚染・clear } ; 送信 … }`。

> **★ 区間の順序は load-bearing である。** `SecurityScanLauncher.kt:303-304` は**同じ `outboundLock` 区間で
> `didChangeConfiguration` と `runSecurityScan` を続けて送る**ので、この区間には**モニタ区間が 2 回**入る:
> **①「比較・更新・汚染・clear」→ ② `outstanding(P) += 1`** の順である。
> **逆順にしてはならない** —— 先に `+= 1` すると、直後の変更判定が `outstanding(P) > 0` を見て
> **いま送ろうとしている新文脈の要求を自分で汚染する**。新しい fingerprint の下で送る要求は
> **変更を済ませてから数える**。
**`didChangeConfiguration` の送信自体はモニタの外**(送信は I/O であり、上表の規律に従う)。
**送信より先にモニタ区間を終えてよい** —— 記録側が見るのは「新しい fingerprint と汚染フラグ」であって、
サーバが新しい設定を受け取ったかどうかではない。**むしろ先に更新しておくほうが安全側**である
(まだ旧設定で走っている scan の応答が、既に新 fingerprint で拒否される)。

##### この方式が守っていること / 捨てたもの

- **期限切れも上限 eviction も、証明として使わない。** どちらも「エントリを捨てる」だけで
  **対応する応答を取り消さない**ため、第 4 版はここで破れた。**本方式にキューも期限も上限も無い。**
  状態はパスあたり**整数 1 個と真偽値 1 個**(＋全体で保持世代 1 個)だけで、第 4 版の台帳より**単純**である。
- **fail-closed**。判断できないときは記録しない。**「記録しない」の最悪は詳細が出ないことで、
  「記録する」の最悪は他アカウントの機密が現行として表示されること。**

##### 残る限界(明示する)

**応答が 1 件も返らない要求があると、そのパスは静穏を証明できない。**
LS は **5 つ**の早期 return で**応答を返さずに終わる**ことがある(前掲の表: `:121` / `:141` / `:144` /
`:147` / **`:173-175`**)。`:141` / `:144` は、`SecurityScanLauncher` が自ら記録している既知のレース
(「gate と送信の間で設定が反転すると `remoteSecurityScans=false` を送ってから scan 要求を送る」)で起こりうる。
**`:173-175` だけは設定と無関係**で、API が `vulnerabilities` を配列で返さなかったときに起こる。
**頻度の見積もりに含めること** —— ここが平常時に踏まれると、汚染が「稀」でなくなる。

そのパスは **`contaminated` のまま残る** = **詳細が出せない。**
**これは仕様であって不具合ではない**(fail-closed)。解除できるのは**証明可能なバリアだけ**:

> **★ バリア = 接続の更新。** LS 接続が張り直されると `GitLabLanguageServerClient` が作り直され、
> **旧接続の応答は新しいクライアントには到達しえない**(到達しても接続世代の照合で落ちる)。
> したがって**接続世代が進んだ時点で、その世代の `outstanding` と `contaminated` を捨ててよい。**
> **これは N1 で既に持っている仕組みそのもので、新しい概念を足していない。**
> **実体は `VulnerabilityIntake.onConnectionClosed(deadEpoch)`**(第 7 版。tracker を直接叩かない。§16)。
> **捨てるのは死んだ世代の分だけ**で、**現行世代の件数・汚染は巻き添えにしない**(A13b-11 (iii))。

**影響範囲は狭い、ただし条件付きである**: 汚染されるのは**「fingerprint 変更の瞬間に `outstanding(P) > 0`
だったパス」だけ**である。設定変更は稀で、そのとき飛行中の scan は通常 0 件なので、**平常時は何も汚染されない。**

> **★ ただし応答欠落は累積する。** 上の 5 経路で応答が返らなかった要求は、そのパスに **`+1` を恒久的に
> 残す**(接続世代が進むまで)。これ自体は無害だが、**そのパスを「次の設定変更で汚染され、しかも二度と
> 解けない」状態にしておく**という効果がある。つまり **`:173-175` のような設定非依存の欠落が平常時に
> 起きるほど、「平常時は何も汚染されない」の前提が痩せていく。**
> **実装時に、接続あたりの残留 `outstanding` を(パスを出さずに)件数だけ観測できるようにしておくこと**
> —— これが痩せ具合を測る唯一の手段になる(§15 のログ規律に従い、**パスも所見も出さない**)。
**診断(PR #51)には一切影響しない** — 本方式は「何を送るか」を変えず、「何を**記録**するか」だけを決める。

**設定変更(N5)**: `didChangeConfiguration` を送る箇所のうち **fingerprint の成分を含む全量送信は 2 つ** —
`GitLabLanguageServerConfigurationService.sendConfiguration`(`:58`)と `SecurityScanLauncher.kt:303`。
いずれも `outboundLock` 内なので、**前回値と異なれば同じ `outboundLock` 区間の内側のモニタ区間で
「更新 + 汚染 + `clear()`」を 1 操作として**行う(規則 2)。

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
**保持の実体は素の `LinkedHashMap(accessOrder = true)`** で、上限超過は `removeEldestEntry` が返す(§11)。
**自前の錠は持たない** —— 読み書きはすべて `VulnerabilityIntake` が取る `DiagnosticGenerationRegistry.lock`
の区間の中で行う(第 6 版 / §9.1「判定と記録は一操作」)。

記録の入口は permit を取る:

```kotlin
/**
 * 判定区間で生成され、同じ区間で消費される許可トークン。
 *
 * **`VulnerabilityIntake` の private な入れ子型**で、生成子も private。外からは構築できないので、
 * permit を要求する記録入口も外からは呼べない ―― これが第一の保証である(§9.1)。
 *
 * [regionNonce] は**モニタ区間の中で単調増加カウンタから採番される単回消費の値**。再利用しない。
 * 値の一致だけでは「同じ区間」を証明できず、**区間外で現行値を詰めた permit と ABA が素通りする**
 * ため、記録入口は `Thread.holdsLock(DiagnosticGenerationRegistry.lock)` と
 * 「いま生きている nonce と一致するか」を併せて確かめる(A13b-8)。
 */
private data class RecordPermit(
  val path: String,
  val epoch: Long,
  val contextFingerprint: String,
  val regionNonce: Long,
)
```

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
| 応答着信(lsp4j ディスパッチ)と読み出し(UI スレッド) | **`DiagnosticGenerationRegistry.lock` の下の `LinkedHashMap(accessOrder = true)`。** 値は不変。**`Collections.synchronizedMap` は第 6 版で外した**(下行) |
| **★ 判定・fingerprint 更新・clear・record の原子性**(第 6 版 / round 4 P1-1) | **錠は `DiagnosticGenerationRegistry.lock` ただ 1 つ**にまとめる。`ScanFlightTracker` と `VulnerabilityStore` は 2 オブジェクト・1 ロックで、**入口は `VulnerabilityIntake` の複合操作だけ**。判定区間で作った `RecordPermit` を**同じ区間で**消費する(**区間の同一性は値比較では証明できない**ので、**単回消費の区間 nonce + `Thread.holdsLock`** で束縛し、`RecordPermit` 自体を `VulnerabilityIntake` の private 入れ子型にして外から構築できなくする。第 7 版 / R5-2)。**書き込み側 4 操作はすべて捕捉済み epoch を運び、区間の中で現行世代と照合してから状態を変える**(第 7 版 / R5-1)。**受信側は `outboundLock`(kotlinx `Mutex`・`withLock` は `suspend`)を取れない**ため、両側が取れる錠はこのプレーンモニタしかない。区間の中では**サスペンド・送信・UI ホップ・I/O を一切しない**ので、ロック順序「送信 `Mutex` → このモニタ」は一方向のまま(§9.1) |
| **記録と上限 eviction の原子性** | **`ConcurrentHashMap` では駄目。** 個々の get/put/remove しか原子的でなく、「記録して上限超過なら最古を捨てる」という**複合操作を守らない** — 上限 N の状態へ複数応答が並行着信すると、両方が同じ最古要素を選んで消して最終サイズが N を超えたり、同一パスの新しい値を古い空応答が消したりできる。**`removeEldestEntry` は `put` の内側で走り、その `put` は上記の単一区間の中にある**ので、記録・順序採番・eviction が**単一操作**になる(P1-g は第 6 版でも退行しない) |
| **退去順序** | `timestamp` ではなく **`LinkedHashMap` のアクセス順**で決まる。`timestamp` が null の所見が混じっても順序が壊れない(応答に timestamp が無い場合の挿入順が別途要る、という問題が起きない) |
| 接続世代 | 既存 `DiagnosticGenerationRegistry.currentEpoch` を使う(marker と同じ土俵)。**読み出し時に世代を照合**し、古ければ「所見なし」を返す |
| **scan context fingerprint**(N5) | 接続世代は `didChangeConfiguration` では進まない。**インスタンス URL・認証・スキャン有効状態が変われば、同じ接続のままでも所見は無効。** `GitLabLanguageServerConfigurationParams` の **`baseUrl` / `token` / `featureFlags.remoteSecurityScans` / `securityScannerOptions.enabled`** から**不可逆ダイジェスト**を作る(§15: **fingerprint 自体もログに出さない。`token` を平文で保持しない**)。全量送信の 2 箇所の `outboundLock` 区間で、**その内側のモニタ区間**で前回値と比較し、異なれば「更新 + 汚染 + `clear()`」を 1 操作で行う(**部分送信の `ProjectOpenLanguageServerListener.kt:60` は対象外** — §9.1) |
| **飛行中の応答の扱い** | **「着信時点の現行値」では照合にならない**(常に一致する)。**応答は要求を識別せず、順序も保証されない**ため、**応答を要求に対応づける方式はすべて破れる**(§9.1 に第 3・第 4 版の反例)。採るのは **`ScanFlightTracker`** — **対応づけを試みず、「未着件数が 0 に戻った」ことで静穏を証明する** |
| **同一パスの重複要求** | 重複排除もデバウンスも無く、**最大 60 秒**(`RESPONSE_DEADLINE_MS`)同一パスの要求が飛行しうる(`SecurityScanLauncher.kt:399` が明言)。**要求の送信は抑止しない**(診断の既存挙動を変えないため)。重複しても**件数で数えられる**ので方式は成り立つ |
| **`ScanFlightTracker` のロック** | **`DiagnosticGenerationRegistry.lock`**(`CommandWaiters` と同一モニタ)。ロック順序は既存の固定順 **outbound `Mutex` → このモニタ**。**トラッカ側から outbound `Mutex` を取らない。** `VulnerabilityStore` も**同じ**モニタの下にある(第 6 版) |
| 応答の順序 | **クライアント側**は lsp4j ディスパッチスレッド上で同期に処理される(`securityScanResponse` は `runAsync` しない)。**サーバ側は順序を保証しない** — `handleScanNotification` は `async` な通知ハンドラで、ディスパッチャは完了を待たない(`security_diagnostics_publisher.ts:108-113`)。**既存コードの「サーバは要求順に答える」という仮定は、本機能では使わない** |
| **順序仮定への非依存** | **`ScanFlightTracker` は順序も期限も使わない。** 使うのは **前提 Q(全応答は計数済みの 1 要求に由来する)** と **前提 R(観測される応答は 1 要求につき 0 件か 1 件)** だけ(§9.1)。したがって**応答が入れ替わっても、期限を超えて遅延しても、旧文脈の所見は記録されない** |
| **★ 前提が崩れたときの挙動** | 前提 Q / R は**同梱 LS 9.3.0 のバイナリに対する全列挙で証明した**ものであり、**LS を上げたら再検証が要る**(§6 / §21)。崩れた場合に観測されるのは「未着件数が 0 なのに応答が届く」で、**その応答は拒否され、そのパスは汚染される**(§9.1 の境界条件)。**前提違反は静かに記録を通さず、fail-closed 側へ倒れる** |
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
- **★ 残留 `outstanding` の観測(第 6 版)**: 応答が返らない要求の累積は「平常時は何も汚染されない」という
  見積もりを痩せさせる唯一の要因なので、**接続世代ごとの残留件数(整数)だけ**を観測できるようにする。
  **パスは出さない**(件数だけなら上の規律に収まる)。**恒常的なログにはしない** —— 出すのは
  接続世代が閉じるときの 1 行、または既存の診断ログレベルの下だけ。
- `gkg` 未検出はログに出さない(§12)。`gkg` の localhost URL もログに出さない。
  **★ `ready` の DTO パースが失敗すると `PluginMessageService` が payload をそのまま warn する**ため、
  そこで localhost URL が露出しうる。**`ready` の DTO は nullable・寛容にしてパース失敗を起こさせない。**

## 16. 既存機能への影響

> **★ 第 8 版でスコープが 1 段広がった(明示する)。** round 6 R6-1 の対応で、
> **本サイクルは `LanguageServerHandle` に 1 フィールド、その構築箇所 1 つ、`GitLabLanguageServerClient` の
> 可視性 1 つを触る。** 第 7 版までは「既存の LSP 接続まわりは読むだけ」という範囲だった。
> **理由**: 送信側が proxy と epoch を別々に読む限り、どんな照合規律を足しても食い違った組を作れてしまう
> (§9.1「★★ epoch と proxy は同じ 1 回の読み取りから取る」)。**照合を足すより、窓を消すほうが小さい。**
> **追加は 1 フィールドで、既存の値の決まり方・公開 API の意味・ロック規律はどれも変えない。**
> **ディレクトリ構成もビルドシステムも変えない**(§6 の共通制約は維持)。
> **実装 PR の説明文に「逸脱」として記載し、ユーザー承認を得ること。**

| 既存 | 影響 |
|---|---|
| `SecurityScanStatusReporter` | **変更しない。** store への記録はクライアント側で分岐して行う |
| `SecurityScanResponse` | **変更しない**(`results` は untyped のまま) |
| `DiagnosticMarkerService` / PR #51 の marker | **変更しない** |
| `WebviewUriResolver` | **`directUris` シームを 1 つ追加**(既定は「無し」)。**metadata 要求より前に引く**(直接アドレスは metadata を要さず、待たせる理由が無い)。session は従来どおり現行 snapshot から取るので supersession 検出は不変。既存の解決順序は不変 |
| **`WebviewEditorPart.kt:41`** | **`directUris` を渡す配線を追加。**(P1-c) |
| **`AgenticTabsView.kt:27`** | **同上。** |
| `GitLabLanguageServerConfigurationService` / `SecurityScanLauncher` | **fingerprint の計算と `VulnerabilityIntake.onContextChanged(fp, capturedEpoch)` を `outboundLock` 区間の内側のモニタ区間に追加**、`SecurityScanLauncher` は**送信直前に `VulnerabilityIntake.onRequestSent(path, capturedEpoch)`** も行う。**★ 第 8 版: どちらも `languageServerWrapper.currentSnapshot` を 1 回だけ読み、`handle.proxy` と `handle.connectionEpoch` を同じ 1 値から取る**(§9.1「★★ epoch と proxy は同じ 1 回の読み取りから取る」)。`sendConfiguration(server)` は **handle を受け取る形へ**変更。**送信内容も送信可否も不変**(要求は一切抑止しない) |
| **`LanguageServerHandle`(`LanguageServerSession.kt:15`)** | **★ 第 8 版: `connectionEpoch: Long` を 1 フィールド追加**(既定引数は付けない。付けると渡し忘れを黙って通す)。値は `GitLabLanguageServerClient.connectionEpoch` の複製で、**送信側の epoch と受信側の照合 epoch が構成上同一**になる |
| **`GitLabLanguageServerProcessProvider.kt:155`** | **★ 第 8 版: `LanguageServerHandle(proxy, client.session, client.connectionEpoch)` へ。** `client` は既にスコープにある。`:187` の `sendConfiguration(readinessServer)` は `:155` の `handle` を渡す形へ |
| **`GitLabLanguageServerClient.kt:62`** | **★ 第 8 版: `connectionEpoch` を `private val` から読める可視性へ**(provider が複製するため)。**値の決まり方は変えない** |
| `CommandWaiters` / `DiagnosticGenerationRegistry` | **変更しない。** `ScanFlightTracker` は `DiagnosticGenerationRegistry.lock` を**共有するだけ**(`CommandWaiters` と同じ扱い)。既存のロック順序 outbound `Mutex` → モニタ を守る |
| `SecurityScanLifecycle` | 接続停止時の後始末に **`VulnerabilityIntake.onConnectionClosed(deadEpoch)` を 1 行追加**(`CommandWaiters.clear` と同じ引数・同じ位置)。**死んだ接続の epoch を渡す**(進めた後の値ではない)。**これが §9.1 の「証明可能なバリア」の実体。** ★ 第 7 版で **`ScanFlightTracker.clear` の直接呼び出しから `VulnerabilityIntake` 経由へ変更**した —— tracker は自前の錠を持たないので、直接呼ぶと**無施錠の `clear` が `onResponse` / `onRequestSent` と並行して同じ状態を書く**(round 5 R5-3) |
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
| 純ロジック | **TDD** | **`VulnerabilityIntake`(判定と記録の原子性。A13b-7 / A13b-8 / A13b-9 / **A13b-11**。区間の入口で相手スレッドを待たせるラッチを注入して**両方の順序**を決定的に再現する。**世代束縛は `currentEpoch` を進めるだけで再現でき、実接続は要らない**。**A13b-12 は「`currentSnapshot` を 1 回しか読まないこと」を fake wrapper の読み取り回数で固定する**)** / **`ScanFlightTracker`(件数・汚染・世代。A13b-1〜6・A13b-10。時刻に依存しないので注入するシームも要らない)** / `VulnerabilityStore`(世代・**fingerprint**・**permit 照合**・上限・削除・**並行 record/delete と同時上限超過**)/ `VulnerabilityLookup`(行一致・異形無視)/ **`VulnerabilityProjection`(5 フィールドの検証・正規化・エスケープ)** / `VulnerabilityPayload` / `KnowledgeGraphState`(**session 束縛・再起動競合**) |
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
| **A13b-6** | **応答が返らない要求は fail-closed**: 応答が 1 件も返らない要求があるパスは、**接続世代が進むまで汚染されたまま**(詳細が出ない)。**`VulnerabilityIntake.onConnectionClosed(deadEpoch)` で解除され、以後は記録される**。生きている接続の件数を巻き添えにしない | 自動 |
| **A13b-7** | **★ round 4 の筋書き(原子性)**: 応答 FA が着信して `outstanding` が 0 になり「記録可」と判定される、その**判定と記録の間**に別スレッドが fingerprint を FB へ変更して `clear()` する。**FA の所見が FB の所見として復活しない。** 実装は「変更スレッドを `VulnerabilityIntake` の区間の入口で待たせ、応答スレッドの区間が閉じてから入れる」順と、その逆の順の**両方**を走らせ、どちらでも復活しないことを固定する | 自動 |
| **A13b-8** | **`RecordPermit` が区間をまたげない**(第 7 版で機構ごと強化)。**値の一致では守れない**ので、次の 3 つを名指しで固定する: **(i) 区間外で生成し現行 epoch・現行 fingerprint を詰めた permit が拒否される**(`Thread.holdsLock` と nonce の両方で落ちること)/ **(ii) fingerprint が A → B → A と戻ったあと、古い A の permit が拒否される**(ABA。間の `clear()` を取り消せないため)/ **(iii) 一度消費した permit の再利用が拒否される**。**第一の保証は `RecordPermit` を `VulnerabilityIntake` の private 入れ子型にして構築自体を不可能にすること**で、本条件はその保険が効いていることの確認 | 自動 |
| **A13b-9** | **`VulnerabilityStore` も `ScanFlightTracker` も自前の錠を持たない。** 読み書きはすべて `VulnerabilityIntake` 経由で、**tracker と store を別々に叩く public API が存在しない**(公開 API を**書き込み 4 + 読み出し 1 = 5 本**に固定する)。**とくに `ScanFlightTracker.clear` が外から呼べないこと**(round 5 R5-3) | 自動 |
| **A13b-11** | **★ round 5 の筋書き(世代束縛)**: `SecurityScanLauncher.launch` が server と epoch を捕捉した**後**、`outboundLock` を取る**前**に接続世代が進む。このとき **(i) 旧 epoch の `onRequestSent` は新世代の件数を `+1` しない**(残留 `+1` を作らない)、**(ii) 旧 epoch の `onContextChanged` は現行 store を消さず現行パスを汚染しない**、**(iii) `onConnectionClosed(deadEpoch)` は旧世代の状態だけを捨て、現行世代の件数・汚染を巻き添えにしない**。**照合が区間の中で行われていること**は、照合と更新の間に世代を進めるラッチで固定する。あわせて **(iv) `onConnectionClosed` が呼ばれないまま世代が進んだ場合も、最初の書き込みで `byPath` がロールオーバし旧世代の件数が漏れない**(遅延ロールオーバ)、**(v) 既にロールオーバ済みのところへ遅れて届いた `onConnectionClosed(deadEpoch)` が現行世代を消さない** | 自動 |
| **A13b-12** | **★ round 6 の筋書き(epoch と proxy の食い違い)**: **2 つの読み取りの間**で再接続する。**(i) (旧 epoch, 新 server)** —— 要求が新 server へ送られたのに `+1` されず、応答が `outstanding == 0` で**前提違反と判定されてパスが汚染される**、が**起きないこと**。**(ii) (新 epoch, 旧 server)** —— 死んだ接続へ送った `didChangeConfiguration` で**追跡 fingerprint だけが進む**、が**起きないこと**。**固定の仕方**: `currentSnapshot` を 1 回だけ読む実装では**そもそもこの組が作れない**ので、テストは**「送信側が `currentSnapshot` を 1 回しか読まない」ことと、handle の `connectionEpoch` が受信側クライアントの `connectionEpoch` と同一値であること**を固定する(**組を作れないことの証明**であって、作ってから落とすテストではない) | 自動 |
| **A13b-10** | **前提 Q / R / S の成文化**: 応答の `filePath` が null の応答は**減算せずに捨てられる**(`GitLabLanguageServerClient.kt:126`)、**URI 形式の `filePath` も素のパスと同じキーへ正規化される**(前提 S)、**未着件数 0 での着信は拒否 + 汚染**。3 つとも fail-closed 側であること | 自動 |
| **A14** | **旧 session の `ready` / `getUrl` 完了は新接続の値を上書きしない。** 再起動競合で、停止済み `gkg` の URL が `Resolved` にならない(P1-b) | 自動 |
| **A15** | **本番ファクトリが `knowledge-graph` を直接経路で解決し、他の id は metadata 経路のまま**(keep-behaviour)(P1-c) | 自動 |
| **A15b** | **`WebviewEditorPart.kt:41` / `AgenticTabsView.kt:27` がそのファクトリを呼んでいる** | **目視 + 実機**(SWT `Composite` を受けるため headless で呼べない) |
| **A16** | **`$/gitlab/openUrl` が `file:` / `javascript:` / userinfo 付き URL を開かない。** 失敗・拒否のいずれでも **URL 全文・query・fragment がログに出ない**(scheme のみ)(P1-e) | 自動 |
| **A17** | **`description` に `<script>` 等を含む所見が、実行されずテキストとして描画される**(エスケープ済みで webview へ渡る)(P1-f) | 自動(投影の出力を検査)+ **実機**(A11 に相乗り) |
| **A17b** | **コードスパン内の `<` が `&lt;` として投影される**ことを**既知の表示劣化として固定**する(§14。挙動を隠さないためのテスト) | 自動 |
| **A18** | **`wrapper.currentSnapshot` が `null` のとき、タブを開かずに通知だけ出す**(§9.2 手順 6 / §12) | 自動 |
| **A19** | **上限 N の store へ複数スレッドが同時に `record` しても、最終サイズが N を超えず例外も出ない**(P1-g)。第 6 版で store 自前の錠を外したので、**`removeEldestEntry` が走る `put` が `VulnerabilityIntake` の `DiagnosticGenerationRegistry.lock` 区間の中にあること**を確認する形に読み替える | 自動 |
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
| **★ 判定と記録の隙間に設定変更が割り込む** | **認可境界の違反**(clear を生き延びた旧所見が現行として読める) | **判定・fingerprint 更新・clear・record を `DiagnosticGenerationRegistry.lock` の単一区間に入れ、`RecordPermit` を同一区間で消費する**(§9.1 / §11)。A13b-7 / A13b-8 |
| **★ 再接続を跨いだ送信が新世代の件数を汚す** | 残留 `+1` が新世代に残り、**次の設定変更でそのパスが接続更新まで恒久的に汚染される**(詳細が出ない) | **書き込み側 4 操作に捕捉済み epoch を運ばせ、区間の中で現行世代と照合してから状態を変える**(§9.1「★ 世代の束縛」)。`CommandWaiters` と同じ形。A13b-11 |
| **★ epoch と proxy が別々の読み取りで食い違う** | **(旧 epoch, 新 server)** = 生きた応答が前提違反と誤判定されてパスが汚染される(詳細が出ない)/ **(新 epoch, 旧 server)** = **追跡 fingerprint が生きている接続の設定を表さなくなる**(認可境界の土台が偶然頼みになる) | **`currentSnapshot` を 1 回だけ読み、`LanguageServerHandle` に `connectionEpoch` を持たせて proxy と同じ 1 値から取る**(§9.1 / §16)。既存の `AtomicReference` がそのまま使えるので**窓そのものが消える**。A13b-12 |
| **★ `RecordPermit` の抜け道(区間外生成・ABA)** | **clear 済みの古い所見を書き戻せる = 認可境界の違反** | **第一に字句スコープ**(private 入れ子型で構築不能)、**第二に単回消費 nonce + `Thread.holdsLock`**(§9.1「★ permit は値の一致では守れない」)。A13b-8 |
| **★ 無施錠の `clear` が並行更新と競合する** | 例外・計数の取り違え・**新世代を巻き込む消去** | **`ScanFlightTracker.clear` を外から呼べなくし、`VulnerabilityIntake.onConnectionClosed(deadEpoch)` を第 5 の複合操作として共有モニタの下に置く**(§16)。A13b-9 |
| **★ LS を上げて前提 Q / R が崩れる** | 計数されない応答が静穏を偽証し、**認可境界が破れる** | **前提を §9.1 に全列挙付きで成文化し、LS 更新時の再検証手順を §6 と PR に置く。** 崩れた場合の観測(未着件数 0 での着信)は**拒否 + 汚染**で fail-closed(§11)。A13b-10 |
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

### 第 6 版(2026-09-22)— Codex レビュー round 4 反映

round 4 の指摘は **P1×2**。**両方とも妥当と判断し、実コード・同梱 LS 9.3.0 のバイナリで裏を取ったうえで反映した。**
**P1-2 が要求した証明は成立した** —— 前提を捨てるのではなく、**全列挙して設計に固定した。**

| # | 指摘 | 検証結果と反映 |
|---|---|---|
| **P1-1** | **応答判定(`outstanding==0` で記録可)と `VulnerabilityStore.record` が原子的でない。** 判定直後に別スレッドの fingerprint 変更 + `clear` が割り込むと、変更側は `outstanding==0` なので汚染せず、応答側が現行 FB を貼って FA の所見を clear の後に復活させる。また手順 3 の「要求時 fingerprint」は、対応づけを廃止した `ScanFlightTracker` からは取得不能で自己矛盾 | **両方とも妥当。** ①**自己矛盾は実在**した —— `requestFingerprint` は第 3・4 版の残骸で、第 5 版で対応づけを捨てた時点で取得不能になっていた。**削除した。** ②**非原子性も実在**した —— 判定は `DiagnosticGenerationRegistry.lock`、記録は `VulnerabilityStore` 自前の `Collections.synchronizedMap`、`clear` は変更側が `outboundLock` 配下から呼ぶ、で**3 つの別区間**だった。→ **錠を `DiagnosticGenerationRegistry.lock` 1 つへ統合**し、`ScanFlightTracker` と `VulnerabilityStore` を **`VulnerabilityIntake` の複合操作 4 つ**の背後へ入れ、**判定区間で作った `RecordPermit` を同じ区間で消費**する形にした(§9.1「★★ 判定と記録は一操作でなければならない」/ §10 / §11) |
| **P1-2** | **静穏証明の健全性には「各要求の応答は最大 1 件」だけでなく「受信する全応答が一意な計数済み要求由来で、LS が自発応答を送らない」ことも必要。** 非対応応答が先着して `outstanding` を 0 にすると汚染が解除され境界が破れる。`sendScanResponse` 相当と通知メソッドの全呼び出し元を列挙して前提を固定せよ | **妥当。しかも第 5 版の根拠(「成功 `:191` / 失敗 `:242` が排他」)は不十分だった。** → `main.js.map` の **2,839 sources を全展開して全列挙**し、**前提 Q(Q1〜Q6)・前提 R・前提 S** として §9.1 に固定した |

#### P1-2 の列挙結果(同梱 LS 9.3.0)

**経路は一本道で閉じている**:

```
node_connection_service.ts:90  onNotification(RemoteSecurityScanNotificationType)   ← クライアントの通知だけ
  └ :91  handleScanNotification
      └ security_diagnostics_publisher.ts:112  #runSecurityScan   (private・呼び出し元はここだけ)
          ├ :191  sendScanResponse (成功)
          └ :193  #handleError → :242  sendScanResponse (失敗)
                security_notifier.ts:18-23  #notify
                  = createNotifyFn = connection.sendNotification
                    (connection_service.ts:42-45 / :198-199、
                     node_connection_service.ts:81-84 で登録)
```

- **`sendScanResponse` の呼び出し元はこの 2 つで全部**(バンドル全体の grep)。
- **クライアント側の送信も 1 箇所**: `SecurityScanLauncher.kt:304`。**計数する場所そのもの**。
- ⇒ **LS は自発応答を送らない。全応答は `outstanding(P) += 1` を通った 1 要求の下流。**

#### 第 5 版の記述を 2 つ訂正した

1. **「1 要求 → 応答は最大 1 件」は、呼び出し回数としては正しくない。**
   `:191` の送信は **`try` の中**にあり、reject すると `catch` が `:242` で 2 通目を送る。
   → **「観測される応答が 0 件か 1 件」へ言い換え、その証明を同梱 `vscode-jsonrpc` で与えた**
   (`connection.js:910` の同期 throw / `messageWriter.js:76-105` の `Semaphore(1)` と `doWrite`)。
   **reject した送信は完全な 1 通を届けない**ので、2 通目が出るときは 1 通目が観測されない。
   唯一の例外「本文の途中で切れたストリーム」は**接続破断**であり、本方式のバリアが効く条件と同じ。
2. **応答を返さない早期 return は 4 つではなく 5 つ。**
   第 5 版が挙げた `:121` / `:141` / `:144` / `:147` に加えて **`:173-175`**
   (`vulnerabilities` が配列でない / null)がある。**これだけは設定と無関係に起こる**ので、
   fail-closed の頻度見積もりに含めるよう §9.1 に明記した。

#### 併せて固定した前提(前提 S)

**送信側の計数キーと受信側の減算キーが一致すること**を根拠付きで書いた。
LS の失敗経路は `filePath ?? documentSource.toString()`(`:193`)で **URI 文字列**を返しうるが、
`securityScanPathKey` が URI と素のパスを同じキーへ正規化する
(`SecurityScanStatusReporterTest.kt:478-489` / `:529` が固定済み)。
`filePath` が null の応答は**減算せずに捨てられる**(`GitLabLanguageServerClient.kt:126`)= fail-closed。

#### 前提の寿命を設計に入れた

前提 Q / R は**特定バージョンのバイナリに対する証明**なので、**gitlab-lsp を上げたら再検証が要る。**
§6 に**再検証手順(4 項目・`main.js.map` を展開して grep するだけ)**を置き、§21 にリスク行を足し、
**PR の「LS 更新時の確認項目」へ転記する**ことにした。

#### 反映先

§6(再検証手順)/ §8.1(`VulnerabilityIntake` 新設・`VulnerabilityStore` と `ScanFlightTracker` から自前の錠を外す)/
**§9.1(手順 3・4 / 前提 Q・R・S / 規則の区間化 / 「判定と記録は一操作」新設 / 残る限界の 5 経路)** /
§10(`RecordPermit` / 素の `LinkedHashMap`)/ §11(原子性の行を新設・5 行改訂)/
§15(残留 `outstanding` の観測)/ §18(テスト対象)/
§19(A13b-7 / A13b-8 / A13b-9 / A13b-10・A19 の読み替え)/ §21(リスク 2 行)。

#### 自己レビューで併せて直した点(第 6 版の反映そのものに対して)

- **区間の順序が load-bearing であることが書けていなかった。** `SecurityScanLauncher.kt:303-304` は
  1 つの `outboundLock` 区間でモニタ区間に**2 回**入る。**「変更判定 → `+= 1`」の順でなければ、
  新文脈の要求が自分自身を汚染する。** §9.1 に明記した。
- **「平常時は何も汚染されない」が無条件に読めた。** 応答欠落は `+1` を恒久的に残すので、
  **そのパスは「次の設定変更で汚染され、二度と解けない」側へ寄っていく。** 条件付きの主張へ直し、
  痩せ具合を測るために**残留件数(整数のみ・パスを出さない)の観測**を §15 に足した。
- **A19(P1-g)の文言が旧ロック前提のままだった。** store 自前の錠を外したので、
  「`removeEldestEntry` が `VulnerabilityIntake` の区間の中で走ること」の確認へ読み替えた。

### 第 7 版(2026-09-22)— Codex レビュー round 5 反映

round 5 の指摘は **P1×3**。**3 件とも文言レベルではなく実体のある指摘**で、実コードと設計書本文で裏を取ったうえで全件反映した。
**3 件のうち 2 件(R5-2 / R5-3)は第 6 版で自分が入れ込んだ退行**である。

| # | 指摘 | 検証結果と反映 |
|---|---|---|
| **R5-1** | **送信計数を捕捉済みの接続世代に束縛せよ。** `onRequestSent(path)` / `onContextChanged(newFingerprint)` に世代が無く、「接続世代スコープ」を実装できない。server 捕捉後・`outboundLock` 取得前に再接続すると、旧 server へ送る処理が**新世代**の tracker を `+1` し、その応答は世代照合で拒否されて減算されないため**恒久的な残留 `+1`** になる | **妥当。実コードで確認**: `SecurityScanLauncher.launch` は `val epoch = DiagnosticGenerationRegistry.currentEpoch` … `dispatch(params, path, source, server, epoch)` と**コルーチン実行前に捕捉**し、送信は `outboundLock` 取得後。`sendConfiguration` も「Send to the server captured at CALL time」と同じ形。→ **書き込み側 4 操作すべてに epoch を持ち回らせ、区間の中で現行世代と照合してから状態を変える**形にした(§9.1「★ 世代の束縛」)。**`CommandWaiters.consumeById(waiterId, epoch)` / `armDeadline(waiterId, path, epoch)` と同じ構造**で、新しい概念は足していない。A13b-11 |
| **R5-2** | **`RecordPermit` を同一区間に束縛できる形にせよ。** `(path, epoch, fingerprint)` の値比較では「同じ区間で生成・消費された」ことを検証できない。**区間外で現行値を詰めた permit** と **fingerprint が A→B→A と戻る ABA** はどちらも比較が成功するので、第 6 版の A13b-8 は原理的に達成不能 | **妥当。第 6 版の退行で、round 4 P1-1 ①と同じ型の誤り** —— **受け入れ条件が機構の能力を超えて書かれていた。** → **2 段で塞いだ**: ①**第一の保証は字句スコープ** —— `RecordPermit` を `VulnerabilityIntake` の **private 入れ子型**にし、外から構築できなくする(記録入口も呼べなくなる)。②**第二の保証は実行時の検出器** —— **単回消費の区間 nonce**(区間内で採番・消費時に破棄・再利用しない)と **`Thread.holdsLock(DiagnosticGenerationRegistry.lock)`**。**ABA も区間外 permit も使い回しも落ちる。** A13b-8 を (i) 区間外 + 現行値 / (ii) ABA / (iii) 再利用 の 3 本へ書き換え |
| **R5-3** | **接続停止時の `clear` も `VulnerabilityIntake` 経由にせよ。** §16 が `SecurityScanLifecycle` から `ScanFlightTracker.clear(deadEpoch)` を直接呼ぶと指定しているが、第 6 版で tracker は自前の錠を失ったので、**無施錠の `clear` が `onResponse` / `onRequestSent` と並行して同じ状態を書く**。A13b-9 の「入口は複合操作だけ」も満たせない | **妥当。これも第 6 版の退行。** 第 5 版までは tracker が自分でモニタを取っていたので直接呼び出しで整合していたが、**第 6 版で錠を外したときに §16 を追従させ忘れた**。→ **`VulnerabilityIntake.onConnectionClosed(deadEpoch)` を第 5 の複合操作として追加**し、§16 / §8.1 / 「確定する規律」/ A13b-6 / A13b-9 を揃えた。**`ScanFlightTracker.clear` を外から呼べる経路は作らない** |

#### 第 6 版の退行が 2 件出たことについて

**第 6 版は「錠を 1 つにまとめる」という変更を入れたが、その前提に依存していた記述の追従が漏れた。**

- **R5-3** は §16 の配線表。tracker が自分で錠を取っていたときの記述がそのまま残っていた。
- **R5-2** は、機構を弱めた(store 自前の錠を外した)にもかかわらず、**受け入れ条件だけが強いまま**残っていた。

**第 6 版の自己レビューはこの 2 件を見落とした。** 見落とした理由は、レビューの軸が
「**新しく書いた節が正しいか**」に寄っており、「**この変更に依存していた既存記述はどれか**」を
洗っていなかったこと。**第 7 版では、変更した概念(錠・公開 API・permit)ごとに
全文を grep して参照箇所を突き合わせた**(`ScanFlightTracker.clear` / `clear(deadEpoch)` /
`onSent` / `onRequestSent` / `onContextChanged` / `onConnectionClosed`)。

#### 反映先

§8.1(`VulnerabilityIntake` を 5 操作へ・epoch 付き)/
**§9.1(「確定する規律」の入口行・許可トークン行 / 新節「★ permit は値の一致では守れない」/
新節「★ 世代の束縛」/ バリアの実体を `onConnectionClosed` に明記)** /
§10(`RecordPermit` に `regionNonce` を追加し private 入れ子型に)/ §11(原子性の行)/
§16(配線 2 行)/ §18(テスト対象)/ §19(A13b-6 / A13b-8 / A13b-9 を書き換え、**A13b-11** を追加)/
§21(リスク 3 行)。

#### 自己レビューで併せて直した点(第 7 版の反映そのものに対して)

- **「旧世代の状態だけを捨てる」が実装可能でなかった。** tracker が**どの世代の状態を保持しているか**を
  持っていなかったため、実装者によって別物になる(= P1 の「解釈が分かれる曖昧な記述」)。
  → **`heldGeneration` を状態に加え、書き込み 3 操作の共通前処理として遅延ロールオーバ**を規定した。
  これにより **`onConnectionClosed` が呼ばれ損ねても旧世代の件数が新世代へ漏れない**(A13b-11 (iv))。
- **`onConnectionClosed` を「渡された epoch が現行でないとき」の表に並べていたのが誤り。**
  この操作だけは**現行でない epoch を渡すのが正常**である。表から切り出し、
  「保持中の世代がまさに `deadEpoch` のときだけ捨てる」という規則へ書き直した(A13b-11 (v))。

### 第 8 版(2026-09-22)— Codex レビュー round 6 反映

round 6 の指摘は **P1×1**(第 7 版で 3 件 → 1 件)。**妥当と判断し、実コードで裏を取ったうえで反映した。**
**第 7 版の修正そのものが作った穴**である。

| # | 指摘 | 検証結果と反映 |
|---|---|---|
| **R6-1** | **server と epoch を不可分に捕捉せよ。** 第 7 版は「捕捉した epoch を運ぶ」と決めたが、**epoch と送信先 proxy が同じ接続のものである保証が無い**。`SecurityScanLauncher.launch` は 2 つを別々に読むので、その間の再接続で **(旧 epoch, 新 server)** ができ、要求は新 server へ送られるのに `onRequestSent` は stale として `+1` せず、**新世代の応答が `outstanding == 0` で前提違反と判定されてパスが汚染**される。逆順の `sendConfiguration` では **(新 epoch, 旧 server)** もできる。A13b-11 は「両方を捕捉した後」の再接続しか固定していない | **妥当。実コードで確認**: `launch` は `val epoch = …currentEpoch` と `val server = …languageServer` を **6 文はさんで別々に**読む。`sendConfiguration()` も `languageServer` を単独で読む(`:37`)。→ **照合を足すのではなく窓を消した** —— **送信側は `currentSnapshot` を 1 回だけ読み、proxy と epoch をその 1 値から取る**(§9.1「★★ epoch と proxy は同じ 1 回の読み取りから取る」)。A13b-12 |

#### 正解は既にリポジトリにあった

**`GitLabLanguageServerWrapper.currentSnapshot` は `AtomicReference` から 1 回で読める不可分な値**で、
`LanguageServerHandle` の KDoc は**まさにこの目的**を明言している ——
「Published as one immutable value so that a reader can never observe a new proxy paired with the
session of the connection it replaced.」**`WebviewUriResolver` / `WebviewLoadCoordinator` は既にこの読み方をしている。**

**足りないのは epoch だけ**だったので、`LanguageServerHandle` に `connectionEpoch: Long` を 1 つ足し、
**値は `GitLabLanguageServerClient.connectionEpoch` の複製**にした。
これで**送信側が使う epoch と受信側が照合に使う epoch が構成上同一**になる ——
`DiagnosticGenerationRegistry.currentEpoch` を別に読む形だと `onActivate` のタイミング次第でずれうる。
**既定引数は付けない**(渡し忘れを黙って通すのは、この指摘の穴の再導入そのもの)。

#### 受け入れ条件の形を変えた

**A13b-12 は「食い違った組を作ってから落とす」テストではない。**
`currentSnapshot` を 1 回だけ読む実装では**その組が作れない**ので、固定するのは

- **送信側が `currentSnapshot` を 1 回しか読まないこと**(fake wrapper の読み取り回数で観測)
- **handle の `connectionEpoch` が受信側クライアントの `connectionEpoch` と同一値であること**

の 2 つ、すなわち**組を作れないことの証明**である。

#### スコープが 1 段広がったことを明示した

第 7 版までは「既存の LSP 接続まわりは読むだけ」だったが、本版で
**`LanguageServerHandle` に 1 フィールド / 構築箇所 1 つ / `GitLabLanguageServerClient` の可視性 1 つ**を触る。
§16 の冒頭に**逸脱として明記**し、**実装 PR の説明文でユーザー承認を得る**ことにした。
**ディレクトリ構成・ビルドシステム・既存の値の決まり方・公開 API の意味・ロック規律はどれも変えない。**

#### 3 版続けて「自分の修正が次の穴を作る」型が出ている

- 第 6 版: 錠を 1 つにまとめた → その前提に依存する記述の追従漏れで **R5-2 / R5-3**。
- 第 7 版: epoch を運ぶことにした → **運ぶ値の出どころ**を決めていなかったので **R6-1**。

**共通しているのは「新しく導入した概念が、既存のどの読み取り・どの記述に依存しているか」を
洗っていないこと。** 第 8 版では **`currentSnapshot` / `languageServer` / `connectionEpoch` /
`LanguageServerHandle` の全参照箇所**を実コードと設計書の両方で grep して突き合わせた。

#### 反映先

§9.1(新節「★★ epoch と proxy は同じ 1 回の読み取りから取る」)/ §16(冒頭の逸脱注記 + 配線 4 行)/
§18 / §19 A13b-12 / §21(リスク 1 行)。
