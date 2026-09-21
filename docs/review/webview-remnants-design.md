# D9 脆弱性詳細 webview / D6 Knowledge Graph 設計書

対象 issue: #14(Phase 6)/ 台帳 #7 D9・D6 / ロードマップ #8
ベースブランチ: `gitlab-ls-9.3.0` @ `84dad18`
参照実装: `gitlab-workflow` v6.85.3(`./out/gitlab-vscode-extension`、読み取り専用)
同梱 LS: 9.3.0(`build/gitlab-lsp/bin/main.js.map` の `sourcesContent` から確定)

---

## 1. 背景と目的

Phase 6 の「webview 面の開放」は **PR #55** で大半が完了し、D5 / D7 / D8 が ✅ になった。
台帳 #7 はそのとき「**残る webview 面は D6 Knowledge Graph と `security-vuln-details` の 2 つ**」と記している。本サイクルはその 2 つを閉じる。

パリティ **67/85 → 69/85**(D9 = 4/5 → 5/5、D6 = 0/1 → 1/1)。

> **注記**: 並行して `feat/diagnostics`(D10 5 件)が open。両者はマージ順に応じて `plugin.xml` で衝突する(#20 §4 の型)。

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
    │ VulnerabilityStore   │◀─────同じ応答から分岐。世代で無効化
    │ (path → 所見 + 時刻)  │
    └──────────┬───────────┘
               │ カーソル行で引く
               ▼
    ┌──────────────────────┐     ┌────────────────────────┐
    │ VulnerabilityLookup  │────▶│ ShowVulnDetailsHandler │ エディタ右クリック
    └──────────────────────┘     └───────────┬────────────┘
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
    │ (@Volatile url)      │        │ directUris シーム（新設） │
    └──────────────────────┘        └──────────────────────────┘
              ▲                                   │
              │ 取りこぼし補償                      ▼
     pluginRequest("getUrl")              #55 の通常の読み込み経路
```

## 8. コンポーネントの責務

### 8.1 純ロジック(headless で完全にテスト可能)

| コンポーネント | 責務 |
|---|---|
| `VulnerabilityStore` | パス → (所見リスト, timestamp, 世代)。上限件数を超えた分は最古から捨てる。世代不一致の読み書きを拒否 |
| `VulnerabilityLookup` | 所見の生 `Map` から `location.start_line` を取り出し、カーソル行に一致するものを返す。**型は仮定しない**(LS の形が変わっても落ちない) |
| `VulnerabilityPayload` | `updateDetails` のペイロード 3 つ組を組み立てる。`timestamp` の文字列化を含む |
| `KnowledgeGraphState` | `ready` で受けた URL を保持。未設定を表現できる |

### 8.2 プラットフォーム接触(シームの背後)

| コンポーネント | 責務 | 危険な点 |
|---|---|---|
| `SecurityVulnDetailsClient` | タブを開き `updateDetails` を送る | UI スレッド・接続世代 |
| `ShowVulnDetailsHandler` | カーソル行の取得 → lookup → client | UI スレッド専用(`ITextEditor` 操作) |
| `KnowledgeGraphController` | `PluginController("knowledge-graph")` の `@PluginNotification("ready")` | lsp4j ディスパッチスレッド |
| `ShowKnowledgeGraphHandler` | タブを開く | UI スレッド |
| `GitLabLanguageServer.pluginRequest` | **新設の `@JsonRequest("$/gitlab/plugin/request")`** | **override に `@JsonRequest` を重ねない**(#20 の既知事項: `GenericEndpoint` が `Multiple methods for name` を投げ、クライアントが接続不能になる。ここは新規メソッドなので該当しないが、隣接コードを触る際の注意) |
| `OpenUrlHandler` | `$/gitlab/openUrl` を受けてブラウザで開く | UI スレッド。既存 `BrowserLauncher` を使う |

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
- `gkg` は `spawn(binaryPath || "gkg", ["server","start"], {detached:true})` で起動され、stdout の JSON `{port}` から `http://localhost:<port>` が決まる。**未インストール時は LS が警告を出して終わり。**
- 参照実装の Knowledge Graph webview は、**他の LS webview と同じ iframe テンプレートに外部 URL を流し込むだけ**(`get_ls_webview_content.ts`)。

## 9. 処理フロー

### 9.1 所見の保持(常時)

1. `$/gitlab/security/remoteSecurityScan/response` 着信(既存 `GitLabLanguageServerClient.securityScanResponse`)。
2. 既存の `SecurityScanStatusReporter.settle(...)` はそのまま(件数の報告)。
3. **加えて** `VulnerabilityStore.record(path, results, timestamp, epoch)`。
4. `results` が null / 空なら、そのパスの記録を**消す**(所見が無くなった状態を残さない)。

### 9.2 F1 詳細を開く

1. 右クリック → 「Show Vulnerability Details」(エディタの既存 `GitLab` サブメニュー配下)。
2. アクティブエディタのファイルとカーソル行(1 始まり)を得る。**UI スレッド。**
3. `VulnerabilityLookup.at(store, path, line)` → 一致する所見。
4. **無ければ通知**(「この行に GitLab の所見はありません」)して終了。
5. `WebviewEditorOpener.openOrReload(page, WebviewEditorInput.securityVulnDetails())` でタブを開く。
6. `pluginNotification(ExtensionToPluginNotification("security-vuln-details", "updateDetails", payload))`。

> **順序について**: §8.3 のとおり LS が再生するため、5 と 6 の順序はどちらでもよい。
> **タブを先に開く**のは、送信が成功しても頁が出ないという見え方を避けるためだけの理由による。

### 9.3 F2 Knowledge Graph を開く

1. LS 接続確立後、`pluginRequest("knowledge-graph", "getUrl")` を 1 度投げ、返った `{url}` を `KnowledgeGraphState` へ(R7)。
2. `ready` 通知が来たら同じく保持(こちらが主経路)。
3. コマンド → `WebviewEditorOpener` でタブを開く。`WebviewUriResolver` は `knowledge-graph` に対し **`directUris` シームから URL を返す**。
4. URL 未設定なら `NotAdvertised` 相当のメッセージ頁に **`gkg` の説明文**を出す(R8)。

### 9.4 F1 のリンククリック

1. webview 内リンク → LS → `$/gitlab/openUrl {url}` 通知。
2. 既存 `BrowserLauncher` で開く。**URL はログに出さない**(N3)。

## 10. データモデル

```kotlin
/** LS の形を仮定しない。所見は生のまま持つ。 */
data class FileVulnerabilities(
  val findings: List<Any?>,
  val timestampMillis: Long?,
  val epoch: Long,
)
```

`VulnerabilityStore` は `Map<String, FileVulnerabilities>`(キー = 既存 `securityScanPathKey` が作る正規化済みパス)。

**所見そのものを型付けしない理由**: 既存 `SecurityScanResponse.results` の KDoc が「意図的に untyped。形は LS のものであり、ここで固定すると認識できない所見が**無視ではなくパース失敗**になる」と述べている。同じ理由をここでも維持する。`VulnerabilityLookup` は `Map<*, *>` として `location.start_line` だけを best-effort で読む。

## 11. 並行処理

| 論点 | 方針 |
|---|---|
| 応答着信(lsp4j ディスパッチ)と読み出し(UI スレッド) | `ConcurrentHashMap`。値は不変 |
| 接続世代 | 既存 `DiagnosticGenerationRegistry.currentEpoch` を使う(marker と同じ土俵)。**読み出し時に世代を照合**し、古ければ「所見なし」を返す |
| `KnowledgeGraphState` | `@Volatile` な `String?` 1 個 |
| `getUrl` と `ready` の競合 | どちらも同じ URL を書くだけ。**後勝ちで構わない**(同一の `gkg` プロセスを指す) |

## 12. エラー処理

| 失敗 | 扱い |
|---|---|
| `results` の要素が想定形でない | その 1 件を**無視**する。他の所見の表示を止めない |
| カーソル行に所見が無い | 通知して終了。webview は開かない |
| `updateDetails` の送信失敗 | lsp4j が握り潰す(既知)。**クライアント側で再送しない**(LS が再生するため) |
| `getUrl` がタイムアウト / エラー | `ready` 待ちへ縮退。コマンドはメッセージ頁を出す |
| `gkg` 未インストール | R8 のメッセージ頁。**エラーログを出さない**(ユーザーの環境選択であって障害ではない) |
| `$/gitlab/openUrl` の URL が不正 | 開かずに無視。**URL をログに出さない** |

## 13. タイムアウトとリトライ / 冪等性

- `getUrl` に **10 秒**のタイムアウト(既存 `WebviewUriResolver` の既定値に合わせる)。**リトライしない** — `ready` が主経路であり、これは取りこぼし補償にすぎない。
- `updateDetails` は**冪等**。同じ所見を 2 度送っても webview は同じ内容を描き直すだけ。
- タブは `WebviewEditorKey("security-vuln-details", emptyMap())` の 1 枚を共有する(参照実装も単一パネル)。

## 14. 認証と認可

- 新たな認証経路は無い。所見は既存のスキャン応答に含まれて届く。
- **所見の本文は GitLab インスタンス由来の外部データ**であり、webview 内で markdown → HTML に展開される。**そのサニタイズは LS が配信する webview バンドルの責務**で、本プラグインは内容に手を触れない(参照実装も同じ)。**この境界を設計上の前提として明記する。**

## 15. ログ、監視、監査

- **所見の本文・ファイルパス・URL は一切ログに出さない。** 出してよいのは件数と例外のクラス名のみ(PR #51 §16.1 の規律)。
- `gkg` 未検出はログに出さない(§12)。

## 16. 既存機能への影響

| 既存 | 影響 |
|---|---|
| `SecurityScanStatusReporter` | **変更しない。** store への記録はクライアント側で分岐して行う |
| `SecurityScanResponse` | **変更しない**(`results` は untyped のまま) |
| `DiagnosticMarkerService` / PR #51 の marker | **変更しない** |
| `WebviewUriResolver` | **`directUris` シームを 1 つ追加**(既定は「無し」)。既存の解決順序は不変 |
| `GitLabLanguageServer` | `@JsonRequest("$/gitlab/plugin/request")` を 1 つ追加 |
| `GitLabLanguageServerClient` | `$/gitlab/openUrl` ハンドラと store への記録を追加 |
| `WebviewEditorInput` | ファクトリを 2 つ追加 |
| `plugin.xml` | コマンド 2・ハンドラ 2・メニュー寄与 2。**末尾追記のみ**。`feat/diagnostics` と衝突する(#20 §4) |

## 17. 移行方法 / ロールバック方法

- 永続データを持たない(store はメモリのみ)。移行なし。
- ロールバックは revert のみ。外部状態を変更しない。

## 18. テスト方針

| 層 | 方式 | 対象 |
|---|---|---|
| 純ロジック | **TDD** | `VulnerabilityStore`(世代・上限・削除)/ `VulnerabilityLookup`(行一致・異形無視)/ `VulnerabilityPayload` / `KnowledgeGraphState` |
| 受信配線 | `GitLabLanguageServerClient` のハンドラを直接呼ぶ | store への記録、`openUrl` の委譲 |
| 送信 | 注入シームに fake proxy | `updateDetails` のペイロード形 |
| UI 実体 | **テストしない**(headless 不可) | エディタのカーソル行取得、タブ表示、Browser |

**`VulnerabilityLookup` が最重要のテスト対象。** LS の所見は untyped なので、想定外の形(欠落キー・型違い・null)で落ちないことを陰性テストで固める。

## 19. 受け入れ条件

| # | 条件 | 検証 |
|---|---|---|
| A1 | `results` が null / 空ならそのパスの記録が消える | 自動 |
| A2 | 世代が進むと古い記録が読み出せない | 自動 |
| A3 | 上限を超えた記録は最古から捨てられる | 自動 |
| A4 | 所見が `Map` でない / `location` が無い / `start_line` が数値でない場合も**例外を投げず**、その 1 件だけ無視する | 自動 |
| A5 | カーソル行に一致する所見が返る(1 始まりの一致) | 自動 |
| A6 | `updateDetails` のペイロードキーが `vulnerability` / `filePath` / `timestamp` ちょうど | 自動 |
| A7 | 所見の本文・パス・URL がログに現れない | 自動 |
| A8 | `KnowledgeGraphState` 未設定時にコマンドがメッセージ頁を出す(例外にしない) | 自動 |
| A9 | 失敗集合が `FAILSET_IDENTICAL` | `verify.sh` |
| A10 | detekt main 17 / test 45 | 明示実行 |
| A11 | 実機: 右クリックで詳細タブが開き、内容が描画される | **実機** |
| A12 | 実機: `gkg` 導入時に Knowledge Graph が開く | **実機**(`gkg` 必須) |

## 20. 未決事項

| ID | 内容 | 扱い |
|---|---|---|
| U1 | 同一行に複数の所見がある場合 | **先頭 1 件**を送る。webview は 1 件しか描けないため。選択 UI は別サイクル |
| U2 | `timestamp` の表示形式。参照実装は「5 minutes ago」の相対文字列を渡す | **ISO-8601 の絶対時刻**を渡す。相対表現の実装は本質でなく、診断用途では絶対時刻のほうが有用 |
| U3 | `filePath` に何を渡すか。参照実装は**ファイル名のみ** | ファイル名のみに倣う(webview はロケーション文字列に使うだけ) |
| U4 | store の上限件数 | 実装時に決める。ファイル単位で最新のみ保持するため、上限はファイル数に対するもの |
| U5 | Knowledge Graph の「準備完了」をコマンドの有効/無効に反映するか | **しない。** 常に有効にし、未準備ならメッセージ頁で理由を説明する(R8)。VSCode の `when` 句相当を作るより説明のほうが親切 |

## 21. 想定されるリスク

| リスク | 影響 | 緩和 |
|---|---|---|
| **所見の形が LS のバージョンで変わる** | 詳細が出ない | 型を仮定しない(§10)。A4 の陰性テスト |
| **`gkg` 不在で D6 が実機でも検証できない** | 受け入れ確認ができない | R8 のメッセージ頁までは検証可能。**PR にその旨を明記** |
| 外部データを webview が HTML 展開する | XSS | **境界を §14 に明記**。サニタイズは LS 配信バンドルの責務で、参照実装と同じ |
| `plugin.xml` の並行衝突 | マージ時の手戻り | #20 §4 の型で解決 |
| `directUris` シームが既存解決を乱す | 既存 webview が壊れる | 既定を「無し」にし、既存経路のテストを keep-behaviour で固定 |

---

## 22. レビュー反映履歴

### 第 2 版(2026-09-21)— Codex レビュー round 1 反映

PR #88 の Codex レビューで **P1×7 + P2×1**。**8 件すべて妥当と判断し、全件反映した。**
うち 3 件は**実ソースで裏取りした結果、指摘のほうが正しいことを確認**している。

| # | 指摘 | 検証結果と反映 |
|---|---|---|
| P1-a | 所見を**認証・設定世代**でも無効化せよ。接続世代だけでは、同じ接続に URL / トークン / スキャン無効化を送っても世代が変わらず、**前のアカウントで取得した所見が見え続ける** | **妥当。認可境界の問題。** §11 に **scan context fingerprint**(instance URL + 認証種別 + スキャン有効状態のハッシュ)を追加し、**fingerprint が変わったら store を消す**。飛行中の応答も、着信時に fingerprint が一致しなければ捨てる。受け入れ条件 A13 を追加 |
| P1-b | Knowledge Graph の URL を**送信元セッションに束縛**せよ。LS 再起動中に旧接続の遅延 `ready` / `getUrl` が新接続の値を上書きすると、**停止済みの `gkg` を指す URL に現行セッションが付いて `Resolved` になり、既存の session 検査を素通りする** | **妥当。** この種の「遅延した旧接続の応答」は本プロジェクトが過去に踏んだ型(#20 の superseded process ガード)。`KnowledgeGraphState` を **`(url, session)` の組**にし、`record` は送信元 session を受け取って**現行でなければ拒否**、接続停止で `clear`。再起動競合テストを A14 に追加 |
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

**未実装(次に着手する順)**: P1-a の fingerprint / P1-b の session 束縛 / P1-c の本番配線 / P1-e のランチャー差し替え / 受信配線(`GitLabLanguageServerClient`)/ 送信クライアント / ハンドラ 2 件 / `plugin.xml` / Koin 登録。
