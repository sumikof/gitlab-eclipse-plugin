# MR Discussions（GraphQL 層新設 + MR レビュー/コメント）設計書

- 対象フェーズ: Phase 5A（ロードマップ issue #8 / フェーズ issue #13）
- パリティ台帳: issue #7 の D13（MR レビュー/コメント・全5件が未実装）
- ベースブランチ: `develop`（本設計時点の tip = `0f53243`）
- 本書のステータス: **レビュー用。実装 PR およびマージ先ブランチには含めない。**

---

## 1. 背景と目的

### 1.1 背景

本プロジェクトは公式 GitLab Eclipse プラグイン（Gradle/Kotlin、`com.gitlab.eclipse.*`）を拡張し、VSCode 拡張 `gitlab-workflow` v6.85.3 との機能パリティを目指している。現在のパリティは 49/85（約 58%）。

Phase 1〜4 で以下の native 基盤が揃った。

- `com.gitlab.eclipse.api` の REST クライアント（`GitLabApiClient` / `GitLabHttpClient`）。mTLS・カスタム CA・認証プロキシに対応。
- 接続設定の seqlock スナップショット（`GitLabApiClient.captureConnection()` / `ConnectionConfigGeneration`）。
- サイドバービュー（`com.gitlab.eclipse.views.sidebar`）と、そこに MR / 変更ファイル / パイプラインを出す ViewModel。
- 書き込み操作の共通パターン（同一インスタンスゲート・in-flight ガード・per-key 世代管理・UI スレッド反映）。

一方 D13（MR レビュー/コメント）は全 5 件が未実装である。理由は単一で、**GitLab の MR ディスカッション操作が GraphQL 専用 API であり、本プラグインに GraphQL クライアントが存在しない**ことによる。

### 1.2 目的

1. `com.gitlab.eclipse.api` に GraphQL クライアントを新設し、GraphQL 依存機能のブロッカーを解消する。
2. その上に MR ディスカッション（スレッド/ノート）の閲覧と操作を実装し、D13 の 5 機能を達成する。

### 1.3 非目的

本設計は D13 のみを対象とする。フェーズ issue #13 は D9（セキュリティスキャン）も同一フェーズに含めているが、調査の結果 D9 は技術的に別のサブシステムであることが判明したため、本設計の対象外とする（詳細は §3.2）。

---

## 2. 対象範囲

### 2.1 対象（D13 全 5 件）

| # | 台帳の機能 | VSCode command | 本設計での実現 |
|---|---|---|---|
| F1 | コメント作成 | `gl.createComment` | スレッドへの返信 / MR 全体コメント / エディタ行からの新規 diff スレッド |
| F2 | スレッド解決・未解決 | `gl.resolveThread` / `gl.unresolveThread` | スレッドノードの右クリック → Resolve / Unresolve |
| F3 | コメント削除 | `gl.deleteComment` | ノートノードの右クリック → Delete（確認ダイアログ付き） |
| F4 | コメント編集（開始/送信/取消） | `gl.startEditingComment` 他 | ノートノードの右クリック → Edit…（本文を事前充填したダイアログ。送信 = OK、取消 = Cancel） |
| F5 | 失敗コメント再試行・取消 | `gl.retryFailedComment` / `gl.cancelFailedComment` | 送信失敗時に本文を保持したままダイアログを再提示し Retry / Cancel（§9） |

加えて、上記すべての前提となる**読み取り**（MR のディスカッション一覧の取得と表示）を実装する。読み取り自体は台帳に独立した行を持たないためパリティ計上はされないが、機能的には必須である。

### 2.2 対象外

| 項目 | 理由 |
|---|---|
| D9 セキュリティスキャン | 別サブシステム（§3.2）。別フェーズで扱う |
| Issue のディスカッション | D13 は MR レビューのみを対象とする。VSCode には `getIssueDiscussionsQuery` も存在し、クエリ構造は共通なので後日追加可能 |
| diff の old 側（削除行）へのコメント | §8.4 に記載。既知の制限として PR に明記する |
| 画像ディスカッション（`positionType: image`）への新規コメント | 読み取り時の表示のみ対応。作成は対象外 |
| コメント本文の Markdown / HTML レンダリング | ツリー UI は HTML を描画できない。`body`（Markdown 原文）をテキストとして表示する |
| アバター画像の表示 | 画像取得のための追加 HTTP リクエストを避ける。`username` のみ表示 |
| ディスカッションの自動更新（ポーリング / push） | 既存のサイドバー同様、手動更新のみ |

---

## 3. 現在の課題

### 3.1 GraphQL クライアントが存在しない

`GitLabApiClient` は REST 専用である。具体的な障壁は 3 点。

1. **エンドポイントが異なる。** GraphQL は `<instanceUrl>/api/graphql` にあり、`/api/v4` 配下ではない。`GitLabApiClient.buildUri`（`src/main/kotlin/com/gitlab/eclipse/api/GitLabApiClient.kt:281-287`）は `"$base/api/v4$path?$queryString"` を組み立てており、この形では GraphQL の URL を作れない。
2. **エラーモデルが異なる。** REST は非 2xx を `GitLabApiException` にしている（同 :275-277）。GraphQL は HTTP 200 を返しながら本文に `errors` 配列を含めうるため、既存のエラー判定では失敗を成功として通してしまう。
3. **ページングモデルが異なる。** REST は `x-next-page` ヘッダを見る（同 :73, :134）。GraphQL は Relay の `pageInfo { hasNextPage, endCursor }` を使う。

なお `GitLabApiClient` の KDoc には「GraphQL will be added later as a sibling method on this class」（同 :52）とあるが、本設計では別クラスとする（理由は §5.1）。

### 3.2 フェーズ issue #13 と実装の乖離（調査で判明）

issue #13 は D13 と D9 を「GraphQL 依存の 2 ドメイン」として同一フェーズに置いているが、参照実装を確認したところ D9 の大半は GraphQL に依存しない。

| D9 の機能 | 実際の機構 | 根拠 |
|---|---|---|
| `gl.runSecurityScan` | LSP 通知 `RemoteSecurityScanNotificationType` を送信し、結果は `RemoteSecurityResponseScanNotificationType` および `publishDiagnostics` で受ける。GraphQL 不使用 | `src/common/security_scans/run_security_scan.ts:26-67` |
| `gl.webview.securityVulnDetails` | LSP 側 webview | `src/common/security_scans/open_vulns_details.ts:6-7` |
| `remoteSecurityScans` フラグ | LSP 設定パラメータ | `src/common/feature_flags/constants.ts:5` |
| `gl.viewSecurityFinding` | MR の `findingReportsComparer` を GraphQL で取得したツリーアイテムから起動 | `src/desktop/gitlab/security_findings/api/get_security_finding_report.ts`、`src/desktop/tree_view/items/security/security_finding_item.ts:14-20` |

したがって D9 は「LSP 経路 3 件 + GraphQL 経路 1 件」であり、D13 とは独立に進行できる。

**また、台帳 issue #7 の D9 の記述には誤りがある。**「セキュリティ検出結果を表示 `gl.viewSecurityFinding` — `publishDiagnostics` 依存」とあるが、`gl.viewSecurityFinding` は MR の GraphQL レポート経路であり `publishDiagnostics` には依存しない。本設計の範囲外だが、台帳の訂正が必要である（§16 に申し送り）。

### 3.3 Eclipse に VSCode Comments API の相当物がない

VSCode 版は `vscode.comments` API（`CommentController` / `CommentThread`）を中心に構成され、仮想ファイルシステム（`src/desktop/review/review_file_system.ts`）で MR の base/head 版を供給し、diff エディタ上にコメントスレッドを重ねている。Eclipse にはこれに相当する API がない。

Eclipse の Compare フレームワーク（`org.eclipse.compare`）は diff エディタを提供するが、**本プラグインの依存に含まれていない**（`build.gradle.kts` の `eclipseDependencies` に不在）。新規 bundle 依存の追加はプロジェクト制約で禁止されているため、Compare 方式は採らない。

---

## 4. 要件

### 4.1 機能要件

| ID | 要件 |
|---|---|
| FR-1 | サイドバーの MR ノード配下でディスカッション一覧（スレッドとノート）を閲覧できる |
| FR-2 | 既存スレッドに返信できる |
| FR-3 | MR 全体に対して（行に紐づかない）コメントを作成できる |
| FR-4 | MR ブランチをチェックアウトしている状態で、エディタのカーソル行に対して新規 diff スレッドを作成できる |
| FR-5 | 解決可能なスレッドを解決 / 未解決にできる |
| FR-6 | 自分が権限を持つノートを編集できる |
| FR-7 | 自分が権限を持つノートを削除できる（確認を経る） |
| FR-8 | 送信に失敗した場合、入力した本文を失わずに再試行または破棄できる |
| FR-9 | 権限のない操作はメニューに出さない（サーバの 403 に到達する前に UI で抑止する） |
| FR-10 | 書き込み成功後、当該 MR のディスカッション表示が最新化される |

### 4.2 非機能要件

| ID | 要件 |
|---|---|
| NFR-1 | すべてのネットワーク呼び出しは UI スレッド外で行う |
| NFR-2 | すべての呼び出しは接続スナップショットに pin される（URL とトークンが同一世代の組であることが保証される） |
| NFR-3 | 読み取り・書き込みとも、スナップショットが「表示元と同一インスタンス**かつ同一アカウント**（`authFingerprint` 一致）」であることを確認してから送信する |
| NFR-3b | 取得処理は wall-clock deadline（60 秒）で有界であり、超過時は取得済み分と打ち切り表示を返す |
| NFR-3c | サーバのコミット有無を判定できない失敗の後に、同じ mutation を自動でも単純な再試行でも再送しない |
| NFR-4 | ログ・監査出力にトークン、リクエストボディ、コメント本文、例外オブジェクト本体を含めない |
| NFR-5 | 応答の UI 反映は最新世代のもののみとし、古い応答が後着しても上書きしない |
| NFR-6 | ページングは有界であり、打ち切り時はユーザーから見て分かる |
| NFR-7 | ディレクトリ構成・ビルドシステム・bundle 依存を変更しない |

---

## 5. 前提条件と制約

### 5.1 設計判断（確定済み）

| # | 判断 | 理由 |
|---|---|---|
| D-1 | Phase 5 を 5A（GraphQL + D13）と 5B（D9）に分割し、5A を先行する | §3.2 のとおり別サブシステムであり、GraphQL 層は 5A のゴールそのもの |
| D-2 | UI サーフェスは**サイドバーツリーの拡張**とする | 新規依存ゼロ。Phase 3/4 で確立した実装・レビューパターンをそのまま適用でき、headless で検証できない未知数が最小 |
| D-3 | コメント作成は「返信 + MR 全体 + エディタ行」の 3 入口とする | エディタ行方式は既存のリビジョン一致ゲートにより行ズレが原理的に発生しない（§8.3） |
| D-4 | GraphQL クライアントは**新規クラス** `GitLabGraphQlClient` とする | エラーモデルが REST と別物のため境界が自然。既存 `GitLabApiClient` を一行も変更せずに済み回帰リスクがない。同クラスは既に `@Suppress("TooManyFunctions")` を抱えており、これ以上増やさない |
| D-5 | 実装は PR-1（読み取り基盤）/ PR-2（書き込み）/ PR-3（エディタ行）の 3 本に分割する | 各 PR が独立にレビュー・検証可能な単位になる |

### 5.2 制約

- ディレクトリ構成を変更しない。追加は既存 `src/main/kotlin/com/gitlab/eclipse/*` 配下に行う。
- 新規 bundle 依存を追加しない。したがって `org.eclipse.compare` は使用しない。
- ビルドシステムを変更しない。
- ドキュメントは実装 PR に含めない。本書はレビュー専用ブランチにのみ存在する。

### 5.3 環境上の制約（検証可能性）

開発環境は headless コンテナであり、以下は**自動検証できない**。

- SWT ウィジェット・ツリー描画・ダイアログ・コンテキストメニューの出し分け
- エディタ連携（アクティブエディタ・カーソル行の取得）
- GitLab インスタンスへの実接続（したがって GraphQL クエリがスキーマと整合するかは実機でのみ確認できる）

これらは PR 説明文の手動検証チェックリストとしてユーザー実機で確認する。この制約は §17 のリスクに直結する。

---

## 6. システム構成

### 6.1 全体図

```
【既存・無変更】                        【新規（Phase 5A）】
────────────────────────────────────────────────────────────────
GitLabHttpClient ─────────────┐
  mTLS / proxy / custom CA    │
                              ├──▶ GitLabGraphQlClient          [PR-1]
GitLabApiClient               │      POST <base>/api/graphql
  .captureConnection() ───────┘      {query, variables}
  seqlock 接続スナップショット         200 + errors[] を検査
                                     pageInfo ページング（上限つき）
                                              │
                                              ▼
                                     DiscussionService           [PR-1/2/3]
                                       getDiscussions            [PR-1]
                                       createNote                [PR-2]
                                       toggleResolve             [PR-2]
                                       updateNote / destroyNote   [PR-2]
                                       createDiffNote            [PR-3]
                                              │
                        ┌─────────────────────┴─────────────────────┐
                        ▼                                           ▼
             SidebarViewModel 拡張                        エディタ側ハンドラ  [PR-3]
             DiscussionsSectionNode                       CommentOnLineHandler
             ThreadNode / NoteNode        [PR-1]
                        │
                        ▼
             コンテキストメニュー ハンドラ群               [PR-2]
             Reply / Resolve / Edit / Delete
                        │
                        ▼
             DiscussionGenerationRegistry                 [PR-1]
             per-key 世代 + epoch + active gate
```

### 6.2 パッケージ配置

既存構成を変更しない範囲で以下に置く。

| パッケージ | 追加物 |
|---|---|
| `com.gitlab.eclipse.api` | `GitLabGraphQlClient`、`GraphQlRequest`、`GraphQlException` |
| `com.gitlab.eclipse.api` | `DiscussionService` |
| `com.gitlab.eclipse.api.model` | `GitLabDiscussion`、`GitLabNote`、`GitLabNotePosition`、`GitLabNotePermissions` |
| `com.gitlab.eclipse.mergerequests.discussions`（新規サブパッケージ） | ハンドラ群、`DiscussionGenerationRegistry`、`CommentInputDialog`、GraphQL クエリ定数 |
| `com.gitlab.eclipse.views.sidebar` | `DiscussionsSectionNode` / `ThreadNode` / `NoteNode`（既存 `SidebarNode.kt` に追加） |

`com.gitlab.eclipse.mergerequests` は Phase 3 で作られた既存パッケージであり、その配下にサブパッケージを作るのは既存の階層方針（`ci/actions`、`ci/joblog`、`ci/lint`）と同格である。

---

## 7. コンポーネントの責務

### 7.1 `GitLabGraphQlClient`

**責務**: GraphQL トランスポートのみ。クエリ文字列の意味を知らない。

```kotlin
class GitLabGraphQlClient(
  private val httpClient: GitLabHttpClient = service(),
  private val apiClient: GitLabApiClient = service(),
) {
  /**
   * 単発の GraphQL 呼び出し。connection に pin される。
   *
   * [timeout] は呼び出し側が決める（既定は使わせない）。ページングを伴う取得では
   * `min(30 秒, deadline の残時間)` が渡される（§12.1）。この引数がないと、残り 2 秒しか
   * ないページ要求が固定 30 秒待ててしまい、全体 deadline 60 秒を超過する。
   */
  fun <T> execute(
    query: String,
    variables: Map<String, Any?>,
    type: Class<T>,
    connection: ConnectionSnapshot,
    timeout: Duration,
  ): T

  /** 接続スナップショットの取得は GitLabApiClient に委譲する（seqlock の実装を二重に持たない）。 */
  fun captureConnection(): ConnectionSnapshot = apiClient.captureConnection()
}
```

**エラー分類はトランスポート層では行わない。** `execute` は L1（非 2xx）と L2（トップレベル `errors`）を例外に変換し、`data` の有無を例外に載せる（§12.2 の分類に必要）。Definite / Ambiguous の判定は `DiscussionService` が行う。

- URI は `<connection.instanceUrl のトレイリングスラッシュ除去>/api/graphql` として組み立てる。`GitLabApiClient.buildUri` は使わない（`/api/v4` を含むため）。
- 本文は `{"query": ..., "variables": {...}}` を Gson でシリアライズしたもの。`Content-Type: application/json`。
- ページングは行わない。呼び出し側（`DiscussionService`）がカーソルを進める。**理由**: `pageInfo` の位置はクエリごとに異なり、トランスポート層が知るべきではない。

**知らないこと**: 何のクエリを投げているか、結果をどう使うか、UI。

### 7.2 `DiscussionService`

**責務**: GraphQL のクエリ・変数・レスポンス DTO を持ち、ドメイン型を返す。

- クエリ文字列を定数として保持（§10 の確定値）。
- GID の組み立て（`gid://gitlab/MergeRequest/{id}`）。
- `namespaceWithPath` の導出（`references.full` を `#` または `!` で分割した先頭）。
- **外側（`discussions`）のページングループのみ**。`pageInfo.endCursor` を次要求の `$afterCursor` に渡す。ページ上限 20 と wall-clock deadline 60 秒で打ち切り、取得済み分を返す（§8.5 / §12.1）。
- **内側（`notes`）はページングしない。** `notes.pageInfo.hasNextPage` を読み取り、真ならそのスレッドに打ち切りフラグを立てるだけ（§8.5）。
- 各ページ要求の timeout を `min(30 秒, 残時間)` として `GitLabGraphQlClient.execute` に渡す（§12.1）。
- レスポンス DTO（nullable）からドメイン型（non-null）への正規化。
- `system: true` のノートの除外。
- 失敗の Definite / Ambiguous 分類（§12.2）。

**知らないこと**: SWT、UI スレッド、ツリー構造。**このクラスは SWT-free であり、単体テスト可能である。**

### 7.3 `DiscussionGenerationRegistry`

**責務**: 応答の UI 反映を最新世代に限定する。Phase 4 の `CiLintGenerationRegistry`（`src/main/kotlin/com/gitlab/eclipse/ci/lint/CiLintGenerationRegistry.kt`）の忠実なミラー。

- キー = （`normalizeInstanceUrl(instanceUrl)`, **`authFingerprint`**, `projectId`, `mrIid`）。§8.1 / §15.3 と同一。
  **`authFingerprint` を必ず含める。** 含めないと、同じ URL でアカウントを切り替えた後に旧アカウントの遅い応答と新アカウントの取得が同じ世代を争い、旧アカウントの結果が UI に反映されうる。既存 `JobLogKey.of`（`src/main/kotlin/com/gitlab/eclipse/ci/joblog/JobLogKey.kt:17-19`）が `normalizeInstanceUrl(instanceUrl) + "\n" + authFingerprint` をハッシュしているのと同じ扱いである。
- `nextGeneration(key)` / `isLatest(key, gen)` / `currentEpoch` / `onActivate()` / `onDeactivate()`。
- `@Volatile active` のみが複数スレッドから読まれ、カウンタ・epoch・latest マップは UI スレッド専有。

### 7.4 サイドバーノード

`SidebarNode.kt` に追加する（既存ファイルへの追記であり新規ファイルではない — 既存のノード定義がすべて 1 ファイルに集約されている構成に従う）。

| ノード | 保持する情報 |
|---|---|
| `DiscussionsSectionNode` | **`sourceInstanceUrl` + `sourceAuthFingerprint`**、親 MR の識別子（projectId / mrIid / mrGid / mrSha / namespaceWithPath）、子スレッド、`loadState` |
| `ThreadNode` | 上記の接続タグ + MR 識別子一式（複製）、`replyId`、`resolved`、`resolvable`、位置情報（path / line / positionType）、`userPermissions`、子ノート |
| `NoteNode` | 上記の接続タグ + MR 識別子一式（複製）、ノート GID、`body`、著者 username、作成日時、`userPermissions`、親スレッドへの参照 |

**接続タグは必須である。** `sourceInstanceUrl` と `sourceAuthFingerprint` は「そのノードが実際にどの接続で取得されたか」を表す非秘密のタグであり、後続の読み書きが同じインスタンス**かつ同じアカウント**に対してのみ行われることを保証する（§15.3）。これは既存の `PipelineNode` / `JobNode` が持つ `sourceInstanceUrl` / `sourceAuthFingerprint`（`src/main/kotlin/com/gitlab/eclipse/views/sidebar/SidebarViewModel.kt:102-114`）と同一の仕組みであり、本設計はその確立済みパターンに従う。

**設計上の注意**: Phase 3 の `ChangedFileNode` は「ハンドラが選択ノードから親 MR まで遡れない」ため必要な情報を自ノードに複製している（`SidebarNode.kt:118-132` の KDoc を参照）。同じ制約が本設計にも当てはまるため、`ThreadNode` / `NoteNode` にも接続タグと書き込みに必要な識別子一式を複製して持たせる。

### 7.5 `CommentInputDialog`

**責務**: 複数行のコメント本文を受け取る。

JFace の `InputDialog` は単一行 `Text` のみであり、コメント本文には適さない。`org.eclipse.jface.dialogs.Dialog` を継承し、`SWT.MULTI or SWT.WRAP or SWT.V_SCROLL or SWT.BORDER` の `Text` を持つ小さなダイアログを 1 つ作り、作成・返信・編集・再試行で共用する。

パラメータ: タイトル、説明文、初期本文（編集・再試行時に事前充填）、エラーメッセージ（再試行時に表示）。

---

## 8. 処理フロー

### 8.1 読み取り（PR-1）

読み取りの入口は **`loadDiscussions(node, force: Boolean, onOutcome: (LoadOutcome) -> Unit)` の 1 つ**とする。`force = false` は遅延取得（節の展開時。既に読み込み済みなら何もしない）、`force = true` は無条件取得（書き込み成功後の再取得。§8.2）。

**完了契約（`LoadOutcome`）。** `loadDiscussions` は background を起動して即座に戻るため、呼び出し側が「ツリーに実際に反映された時点」を知る手段が必要である（§9.3 の Ambiguous フローがこれに依存する）。

契約は次のとおり。

> **プラグインが動作している限り、`onOutcome` は UI スレッド上で必ず 1 回だけ呼ばれる。**
> **唯一の例外はライフサイクル終了（`active == false` または `epoch` 変化）であり、この場合 callback の所有権は意図的に破棄され、`onOutcome` は呼ばれない。**

この例外を契約に明記するのは、停止時には「UI 処理を一切起こさない」ことが優先されるためである（§14.2 / §14.3 / R-8）。停止中に callback を呼べば、その先でダイアログや再取得が起動し、gate-first 不変条件を破る。**呼ばないことが正しい動作**であり、契約違反ではない。

呼び出し側（§9.3）は「callback が来ないのは停止時だけ」を前提にしてよい。停止時にダイアログが出ないことは問題にならない（ワークベンチ自体が閉じている）。

`onOutcome` に渡される値:

| 値 | 意味 |
|---|---|
| `Applied` | 最新世代として**ツリーへの反映が完了**した。取得結果がユーザーの目に入っている |
| `Superseded` | より新しい世代に破棄された。この呼び出しの結果は表示されていない |
| `Failed(cause)` | 取得が失敗した（分類は §12.2）。失敗ノードを表示した |
| `GateRejected` | 接続ゲート（§15.3）で拒否された。HTTP は発行していない |
| `Skipped` | `force = false` かつ既に読み込み済みだったため何もしなかった |

`Applied` **以外は「ユーザーが最新状態を見た」ことを意味しない。** §9.3 はこの区別に依存する。

**すべての早期終了は、対応する outcome の 1 回通知に合流させる。** 「中止して return」で終わる経路を残してはならない。残すと §9.3 の callback が永久に呼ばれず、保持した本文をコピーするダイアログすら出ない。

```
loadDiscussions(node, force, onOutcome)

[UI スレッド]
  if (!force && node.loadState == LOADED) {
    onOutcome(Skipped); return                        ← ★ return だけで終わらせない ★
  }
  node.loadState = LOADING                            ← UI スレッド専有なので原子的
  startEpoch = registry.currentEpoch
  gen        = registry.nextGeneration(key)           ← key は §7.3（authFingerprint を含む）
  節を「Loading…」表示に切り替え
    → background へ
[background]
  connection = pinnedConnectionFor(apiClient, node.sourceInstanceUrl, node.sourceAuthFingerprint)
    → null（URL 不一致 / 資格情報不一致 / 接続不安定）なら
       HTTP を発行せず finish(GateRejected) へ         ← ★ 中止するだけにしない ★
  deadline = 60 秒の wall-clock（§12.1）
  try {
    discussions = discussionService.getDiscussions(connection, namespaceWithPath, mrIid, deadline)
      ページングループ（§8.5）
    正規化・system ノート除外・ソート
    finish(Applied 候補, discussions)
  } catch (e) {
    finish(Failed(e))
  }

finish(...) = [UI スレッド（asyncExec）]
  ガード: registry.active が false          → 破棄（onOutcome を呼ばない = 契約上の例外）
  ガード: registry.currentEpoch != startEpoch → 破棄（同上）
  ── ここから先は必ず onOutcome を 1 回呼ぶ ──
  GateRejected → loadState を FAILED へ戻す → onOutcome(GateRejected)
  Failed(e)    → 失敗ノード表示・loadState を FAILED へ戻す → onOutcome(Failed(e))
  Applied 候補:
    !registry.isLatest(key, gen) → onOutcome(Superseded)     ← loadState は触らない
    それ以外 → ツリーへ反映 / loadState = LOADED → onOutcome(Applied)
```

`Superseded` の場合に `loadState` を触らないのは、より新しい要求が進行中でありその要求が最終的に `LOADED` か `FAILED` を確定させるためである。ここで書き換えると新しい要求の状態を壊す。

`key` は（`normalizeInstanceUrl(sourceInstanceUrl)`, `sourceAuthFingerprint`, `projectId`, `mrIid`）から作る。既存 `JobLogKey.of`（`src/main/kotlin/com/gitlab/eclipse/ci/joblog/JobLogKey.kt:17-19`）と同じく、URL と資格情報フィンガープリントの**両方**をキーに織り込む。

失敗時は節を「Failed to load discussions — see the Error Log」ノードに置き換え、`loadState` を `FAILED`（= 次回展開時に再取得する）に戻し、同じ世代ガードを通す。

**設計上の注意**: `loadState` は UI スレッド専有であり、`@Volatile` にしない。読み書きがすべて UI スレッド上で起きるため、チェックと更新の間に他スレッドが割り込む窓が存在しない。

### 8.2 書き込み共通フロー（PR-2）

すべての書き込み（返信・全体コメント・解決切替・編集・削除）は同一の骨格に従う。

```
[UI スレッド]
  選択ノードを同期的に捕捉（背景に渡す前に）
  権限チェック（メニュー出し分けで既に済んでいるが、実行時にも再確認）
  入力が必要な操作はダイアログを開く → Cancel なら終了
  in-flight ガード取得（キー = §14.4 の DiscussionWriteKey。操作種別を含めない）
    → 取得できなければ「already in progress」通知
  ★ startEpoch = registry.currentEpoch を **この UI ターンで凍結する** ★
    （background 起動の前。完了時に比較する基準値）
    → background へ
[background]
  try {
    connection = pinnedConnectionFor(apiClient, node.sourceInstanceUrl, node.sourceAuthFingerprint)
      → null なら中止し HTTP を発行しない（§15.3）
    GraphQL mutation 送信
    3 層のエラー検査（§11.1）→ Success / Definite / Ambiguous に分類（§12.2）
  } finally {
    in-flight ガード解放          ← ★background の finally。UI スレッドに到達しなくても必ず解放される★
  }
[UI スレッド（asyncExec）]
  ライフサイクルガード: registry.active が false          → 破棄（UI を一切出さない）
  ライフサイクルガード: registry.currentEpoch != startEpoch → 破棄（同上）
  ★ isLatest（世代/鮮度ガード）は適用しない ★
  Success   → loadDiscussions(node, force = true) （§8.1）
  Definite  → §9.2 の再試行ダイアログ（本文保持・[Retry] / [Cancel]）
  Ambiguous → §9.3 の結果確認フロー（本文保持・自動再送しない）
```

**ガードを 2 種類に分けて扱う。** これらは目的が異なるため、一括で適用しても一括で外してもいけない。

| 種類 | 対象 | mutation 完了処理への適用 |
|---|---|---|
| **ライフサイクルガード**（`active` / `epoch`） | プラグインが停止処理に入っていないか | **適用する。** 適用しないと、停止中にブロッキング HTTP が戻ったときに再取得やダイアログが起動し、§14.3 / R-8 の gate-first 不変条件を破る。コルーチンのキャンセルだけでは後続の非 suspend 処理を止められない |
| **鮮度ガード**（`isLatest(key, gen)`） | この取得より新しい取得が出ていないか | **適用しない。** mutation の完了は取得結果ではない |

**`startEpoch` は background 起動前の UI ターンで凍結する。** background 完了後に `registry.currentEpoch` を読んで比較しても常に一致してしまい、送信中の停止→再開を跨いだ古い mutation のダイアログや再取得が新しいライフサイクルで起動する。読み取り側（§8.1）と同じ扱いである。`gen` は mutation 側では取得しない（鮮度ガードを適用しないため）。

鮮度ガードを mutation 完了に適用してはならない理由: 送信中にユーザーが同じ MR を更新すると新しい generation が発行されるため、

- サーバで成功しているのに再取得が発行されず、表示が古いままになる（FR-10 違反）。しかも先行した更新取得が mutation のコミット前に完了していると、古い状態が確定して残る。
- 失敗時に再試行ダイアログが出ず、ユーザーが入力した本文が失われる（FR-8 違反）。

**in-flight ガードの解放は background の `finally` で行う**（UI スレッドのブロックではない）。ライフサイクルガードで UI 処理が破棄された場合でも、`asyncExec` がそもそも実行されない場合でも、キーが確実に解放される。既存 CI ハンドラも同じ形である（`InFlightWriteGuard` の KDoc: 「releases it in the coroutine's `finally` so success, failure, and cancellation all free the target」）。

**同一インスタンス/同一アカウントのゲートは HTTP 送信より前に評価する。** Phase 4 の `runCiLint` で確立した不変条件（「ゲート不成立時は API 呼び出し回数 0」をテストで実証する）を踏襲する。

### 8.3 エディタ行からの新規 diff スレッド（PR-3）

```
[UI スレッド：ただ 1 回のターンで、以下をアトミックにスナップショット化する]
  G1: アクティブエディタが ITextEditor か
  G2: エディタ入力が IFileEditorInput か（ワークスペース上のファイルか）
  G3: エディタが dirty でないか（editor.isDirty() == false）
      → dirty なら拒否（早期・親切な拒否。最終的な保証は G8 が与える）
  ★ SNAPSHOT: (oneBasedLine, documentText, filePath) を同一ターン内で取得 ★
     documentText = documentProvider.getDocument(input).get()
     oneBasedLine = selection.startLine + 1   ← ★ 0 始まり → 1 始まりへ変換（§8.3.1）★
  ダイアログで本文を受け取る → Cancel なら終了
    → background へ（以降、エディタの状態には二度と触れない）
[background]
  G5: そのファイルを含むワークスペースリポジトリがちょうど 1 つに定まるか
      （RepositoryContextResolver。0 件・複数件は拒否）
  G6: そのリポジトリの現ブランチに対応する MR が特定できるか
      （CurrentBranchMrLookup）
  G7: リポジトリの HEAD sha == その MR の diff head sha か
      （OpenMrFileHandler と同一のゲート）
  G8: ★ snapshot.documentText == HEAD blob の内容（対象パス）★
      JGit で HEAD ツリーから当該パスの blob を読み、捕捉した本文と厳密比較
      → 不一致なら拒否
  G9: 対象ファイルの MR 相対パスが、その MR の diff の newPath に含まれるか
  connection = pinnedConnectionFor(...)（§15.3）
  createDiffNote 送信（newLine = snapshot.oneBasedLine）
[UI スレッド] ライフサイクルガードのみ（§8.2）
  Success → loadDiscussions(node, force = true)
  失敗    → §9 のフロー
```

**行の一致は「捕捉した本文が HEAD blob と一致すること」で保証する。** `dirty か` と `作業ツリーが変更されているか` という 2 つの間接指標を別々の時点で評価するのではなく、**行番号と一緒に捕捉した本文そのもの**を HEAD blob と直接比較する。

この形にする理由は、間接指標では TOCTOU が閉じないためである。

- G3（dirty 判定）は UI ターンで、作業ツリーの検査は background で評価される。その間にユーザーは編集を続けられる。
- ディスク上のファイルは HEAD のままでも、エディタのバッファがカーソル行より前に行を追加していれば、捕捉した行番号は「ユーザーが見ていた内容」と対応しなくなる。
- 逆に、捕捉時点では作業ツリーが変更されていて、background の検査時点までに revert されていた場合、作業ツリー検査は通ってしまう。

**本文そのものを比較すれば、これらはすべて 1 つの判定に畳まれる。** `snapshot.documentText == HEAD blob` が成立するとき、捕捉した行番号は HEAD blob の同じ行を指し、HEAD blob = MR head 版（G7 による）なので、**`newLine = snapshot.oneBasedLine`** が確定する。比較対象は UI ターンで凍結された値であり、その後のエディタ操作に影響されない。

G3 を残すのは、よくある失敗（保存し忘れ）を background に降りる前に安価かつ分かりやすく弾くためであり、正しさの根拠は G8 が単独で与える。

拒否時のメッセージ:

| ゲート | メッセージ |
|---|---|
| G3 | `Save the file before commenting on a line.` |
| G7 | 既存 `OpenMrFileHandler.CHECKOUT_FIRST_MESSAGE`（`src/main/kotlin/com/gitlab/eclipse/mergerequests/OpenMrFileHandler.kt:133-134`）と同一文言 |
| G8 | `This file does not match the merge request revision; its line numbers would not line up.` |

G8 の JGit 呼び出しはブロッキングであり、必ず background 側で行う（既存 `OpenCreateNewMrHandler.kt:85` が `Git(repo).status().call().hasUncommittedChanges()` を background で使っているのと同じ扱い）。**検査は対象パスの blob 1 本に限定**する。リポジトリ全体の clean を要求すると、無関係なファイルの編集中にコメントできなくなり実用に耐えないため。

比較は改行コードを正規化せずバイト列として厳密に行う。正規化すると、行区切りの違いで行数がずれる場合を見逃す。

### 8.3.1 行番号の基数（0 始まり → 1 始まり）

**Eclipse の `ITextSelection.getStartLine()` は 0 始まり、GraphQL の `newLine` は 1 始まりである。** 変換を落とすとすべての diff コメントが 1 行上に付く。

**変換はスナップショット時に行い、`oneBasedLine` は最初から 1 始まりの値として保持する**（送信時に変換しない）。理由: 送信時変換にすると、値が 0 始まりのまま複数の関数を通過し、どこで変換済みかが呼び出し側から見て分からなくなる。スナップショットの時点で基数を確定させれば、以降のすべてのコードが 1 始まりとして一貫して扱える。

既存コードも同じ前提に立っている。`GitLabProjectUrlResolver.anchor`（`src/main/kotlin/com/gitlab/eclipse/navigation/GitLabProjectUrlResolver.kt:153-156`）は 0 始まりの `startLine` を受け取り、URL を組み立てる時点で `"#L${startLine + 1}"` としている。本設計はこの既存パターンとは逆に「早期に変換する」方針を採る。両者が混在すると取り違えの温床になるため、**変換済みの値は `oneBasedLine` と命名し、基数を名前に含める**ことを実装時の要件とする（`cursorLine` のような基数の曖昧な名前を使わない）。

**受け入れテストに先頭行（ファイルの 1 行目）を含める。** 0 始まり/1 始まりの取り違えは先頭行で最も検出しやすく（`0` と `1` の差が境界として現れる）、中間行だけのテストでは両方の実装が通ってしまう場合がある。

### 8.4 old 側（削除行）の扱い

本設計は `newLine` のみを送る。VSCode は old 側の未変更行にコメントするために `getNewLineForOldUnchangedLine` で新旧行対応を計算しているが（`src/desktop/commands/mr_discussion_commands.ts:31-40`）、これは GitLab 側の未修正 issue に対する回避策である。`newLine` のみに限定すればこの計算は不要になる。

old 側へのコメント作成は**対象外**とし、PR に既知の制限として明記する。読み取り時には old 側のコメントも `oldPath:oldLine` として表示する（読み取りには制限を設けない）。

### 8.5 ページング

GraphQL の応答には 2 つの `pageInfo` が現れるが、**本設計がページングするのは外側の 1 つだけ**である。

| 位置 | 扱い |
|---|---|
| `project.mergeRequest.discussions.pageInfo` | **ページングする。** `hasNextPage` が真なら `endCursor` を `$afterCursor` に渡して次ページを取得する |
| 各 `discussion.notes.pageInfo` | **ページングしない。** `hasNextPage` を読み取り、真ならそのスレッドに打ち切り表示を出す |

**内側（notes）をページングしない理由。** §10.2 のクエリの `notes` フィールドには `after` 引数がなく、変数も外側の `$afterCursor` しか存在しないため、このクエリ 1 本ではノートの次ページへ進めない。ノートをページングするには「discussion ID と notes カーソルを受け取る別クエリ」が必要になるが、**参照実装にそのクエリは存在しない**（VSCode も外側のみを再帰でページングし、`notes.pageInfo` は取得するだけで使っていない。`gitlab_service.ts:452-459`）。

プロジェクト制約「プロトコル定数は実装前に実ソースで確定する」に照らすと、実ソースに存在しないクエリを設計時に創作することはできない。したがって本設計は**内側をページングしないことを確定仕様とし、代わりに打ち切りを可視化する**。これは参照実装の挙動（超過分を黙って落とす）より厳密である。

打ち切りの扱い:

| 条件 | 動作 |
|---|---|
| 外側が `MAX_DISCUSSION_PAGES = 20` に到達（`GitLabApiClient.MAX_PAGES` に倣う） | `logger.warn`（件数のみ）+ 節の末尾に `(truncated — open the merge request in GitLab to see all discussions)` ノード |
| 外側が §12.1 の deadline に到達 | 同上（打ち切り理由は timeout）。取得済み分は表示する |
| あるスレッドの `notes.pageInfo.hasNextPage` が真 | そのスレッドの末尾に `(more replies — open in GitLab)` 子ノード |

**打ち切りを黙って行わない**ことを要件とする（NFR-6）。上記 3 つの打ち切りはいずれもツリー上に現れ、ユーザーが「全部見えている」と誤認しない。

**将来の拡張余地**: GitLab の GraphQL スキーマが `Discussion.notes(after:)` を直接引ける形を提供している場合は内側もページング可能になるが、それは実インスタンスのスキーマで確認できてからの判断とする（§16 U-10）。

---

## 9. 失敗コメントの再試行・取消（F5）

### 9.1 VSCode の実装と、その前提

VSCode の `gl.retryFailedComment` / `gl.cancelFailedComment` は Comments API の楽観 UI に由来する。送信が失敗すると、入力されたテキストを `FAILED_COMMENT_CONTEXT` を持つプレースホルダのコメントとしてスレッドウィジェットに残し（`src/desktop/commands/mr_discussion_commands.ts:60-70`）、Retry は同じテキストで再送、Cancel はスレッドウィジェットを破棄して下書きを捨てる（同 :102-105, :133-137）。

### 9.2 本設計での等価物 — 確定拒否（Definite）の場合

ツリー + ダイアログの設計に楽観プレースホルダは存在しない。しかし**満たすべき本質的要件は同一である**: ユーザーが入力した本文を、送信失敗によって失わせない。

ただし「失敗」を一括で再送可能として扱ってはならない。**サーバがコミットしたか判定できる失敗と、できない失敗を分ける**（§12.2 の分類）。

本節が扱うのは前者、すなわちサーバが要求を**受理して拒否した**ことが確定している失敗である。HTTP 4xx、L2 のうちリクエストレベルエラー（`data` キーが応答に無い）、L3（mutation ペイロードの `errors`）が該当する。この場合サーバ側に副作用は無いので、同じ本文の再送は安全である。

```
送信失敗（Definite）
  → CommentInputDialog を再度開く
      初期本文 = 直前に入力された本文（そのまま保持）
      エラー表示 = 失敗理由
      ボタン = [Retry] / [Cancel]
  → Retry: 同じ本文で §8.2 のフローを再入
  → Cancel: 何もせず閉じる（本文は破棄される。ユーザーの明示的な選択）
```

### 9.3 結果不明（Ambiguous）の場合

**サーバがコミット済みか判定できない**失敗。タイムアウト、接続断、レスポンス解析失敗、HTTP 5xx が該当する。この状態で同じ mutation をそのまま再送すると、サーバが既にコミットしていた場合に**重複コメントが作られる**。`createNote` / `createDiffNote` には冪等キーが無く（§12.3）、in-flight ガードは最初の要求が既に完了しているため防げない。

したがって **Ambiguous では [Retry] を出さない。**

```
送信失敗（Ambiguous）
  → loadDiscussions(node, force = true) { outcome ->
       when (outcome) {
         Applied    -> ダイアログ [Send again] / [Cancel]      ← ここだけ再送を許す
         Superseded,
         Failed,
         GateRejected,
         Skipped    -> ダイアログ [Copy text] / [Cancel]        ← 再送を許さない
       }
     }
  → [Send again] のダイアログ:
      初期本文 = 直前に入力された本文（保持）
      表示     = 「送信結果を確認できませんでした。最新の状態を再読み込みしました。
                  上のスレッドに反映されていない場合のみ、送信し直してください。」
  → [Copy text] のダイアログ:
      初期本文 = 同上（保持）
      表示     = 「送信結果を確認できず、最新の状態も取得できませんでした。
                  GitLab で確認してください。」
```

**`Applied` でのみ `[Send again]` を出すことが要件である。** §8.1 の `loadDiscussions` は background を起動して即座に戻るため、「呼んだこと」と「ユーザーが最新状態を見たこと」は同じではない。完了契約（§8.1 の `LoadOutcome`）を通さずに実装すると、

- 要求の発行直後に `[Send again]` を出してしまう
- より新しい世代に破棄された要求（`Superseded`）の通信完了時点で `[Send again]` を出してしまう

のいずれかが起こり、ユーザーが最新状態を確認しないまま再送できてしまう。`Applied` は「最新世代としてツリーへの反映が完了した」ことだけを意味するので、この経路でのみ再送を許可する。

再読み込みが成立しなかった場合に `[Send again]` を出さないのは、状態を確認できないまま再送を促さないためである。ユーザーは本文をコピーして GitLab 側で確認できる。

### 9.4 パリティ上の位置づけ

以上により FR-8 が満たされる。台帳 #7 では F5 を実装済みとして計上する。**この読み替えは設計上の判断であり、レビューで妥当性を確認したい点である**（§16 U-8）。

---

## 10. API / インターフェース（確定値）

以下はすべて参照実装（`gitlab-workflow` v6.85.3、`./out/gitlab-vscode-extension`）の実ソースから確定した値である。行番号は参照コピー時点のもの。

### 10.1 エンドポイント

```
POST <instanceUrl（トレイリングスラッシュ除去）>/api/graphql
Content-Type: application/json
Accept: application/json
Authorization: Bearer <token>

{"query": "<GraphQL document>", "variables": { ... }}
```

根拠: `src/common/gitlab/api/api_client.ts:151-152`
（`new URL('./api/graphql', ensureEndsWithSlash(this.#instanceUrl)).href`）

**注意**: `/api/v4/graphql` **ではない**。カスタムパス配下の GitLab（例 `https://example.com/gitlab`）でも `<base>/api/graphql` になる。

### 10.2 読み取りクエリ

根拠: `src/desktop/gitlab/graphql/get_discussions.ts:25-37` および `src/desktop/gitlab/graphql/shared.ts:3-60`

```graphql
query GetMrDiscussions($namespaceWithPath: ID!, $iid: String!, $afterCursor: String) {
  project(fullPath: $namespaceWithPath) {
    id
    mergeRequest(iid: $iid) {
      discussions(after: $afterCursor) {
        pageInfo { hasNextPage endCursor }
        nodes {
          replyId
          createdAt
          resolved
          resolvable
          notes {
            pageInfo { hasNextPage endCursor }
            nodes {
              id
              createdAt
              system
              author { avatarUrl name username webUrl }
              body
              bodyHtml
              url
              userPermissions { resolveNote adminNote createNote }
              position {
                diffRefs { baseSha headSha startSha }
                filePath
                positionType
                newLine
                oldLine
                newPath
                oldPath
              }
            }
          }
        }
      }
    }
  }
}
```

変数の型に注意: `$iid` は **`String!`**（数値ではない）。`$namespaceWithPath` は **`ID!`**。

本設計では `avatarUrl` / `bodyHtml` は要求しない（§2.2 の対象外による。取得しないことで応答サイズも減る）。ただし **`name` / `webUrl` / `url` も同様に不要か**はレビューで確認したい（§16 U-5）。

### 10.3 mutation

| 用途 | mutation | 根拠 |
|---|---|---|
| 返信 / MR 全体コメント | `mutation CreateNote($issuableId: NoteableID!, $body: String!, $replyId: DiscussionID, $mergeRequestDiffHeadSha: String) { createNote(input: { noteableId: $issuableId, body: $body, discussionId: $replyId, mergeRequestDiffHeadSha: $mergeRequestDiffHeadSha }) { errors note { ...noteDetails } } }` | `graphql/create_note.ts:18-39` |
| 新規 diff スレッド | `mutation CreateDiffNote($issuableId: NoteableID!, $body: String!, $position: DiffPositionInput!) { createDiffNote(input: { noteableId: $issuableId, body: $body, position: $position }) { errors note { discussion { ...discussionDetails } } } }` | `graphql/create_diff_comment.ts:16-24` |
| 解決切替 | `mutation DiscussionToggleResolve($replyId: DiscussionID!, $resolved: Boolean!) { discussionToggleResolve(input: { id: $replyId, resolve: $resolved }) { errors } }` | `gitlab_service.ts:128-134` |
| 削除 | `mutation DeleteNote($noteId: NoteID!) { destroyNote(input: { id: $noteId }) { errors } }` | `gitlab_service.ts:137-143` |
| 編集 | `mutation UpdateNoteBody($noteId: NoteID!, $body: String) { updateNote(input: { id: $noteId, body: $body }) { errors } }` | `gitlab_service.ts:146-152` |

`replyId` は「返信先スレッドの `discussion.replyId`」であり、null なら新規の（行に紐づかない）スレッドになる。

### 10.4 識別子の組み立て

| 値 | 形式 | 根拠 |
|---|---|---|
| `issuableId`（MR の GID） | `gid://gitlab/MergeRequest/{mr.id}` — **`iid` ではなく `id`** | `gitlab_service.ts:157` |
| `namespaceWithPath` | `mr.references.full` を `#` または `!` で分割した先頭要素 | `gitlab_service.ts:154` |
| `iid`（クエリ変数） | `mr.iid` を文字列化 | `gitlab_service.ts:471` |
| `mergeRequestDiffHeadSha` | `mr.sha` | `gitlab_service.ts:532` |
| `noteId` | 読み取り結果の `note.id`（既に GID 形式） | — |

**Eclipse 側 DTO は必要フィールドをすべて既に保持している。** `GitLabMergeRequest`（`src/main/kotlin/com/gitlab/eclipse/api/model/GitLabMergeRequest.kt`）に `id`（:7）、`iid`（:8）、`sha`（:17）、`references.full`（:19, :21）が揃っており、追加の REST 取得は不要。

### 10.5 `DiffPositionInput`

根拠: `graphql/create_diff_comment.ts:4-14`

```
{
  baseSha:  <MR diff version の base_commit_sha>,
  headSha:  <MR diff version の head_commit_sha>,
  startSha: <MR diff version の start_commit_sha>,
  paths: { newPath: <diff の new_path>, oldPath: <diff の old_path> },
  newLine: <カーソル行（1 始まり）>
}
```

`oldLine` は本設計では送らない（§8.4）。

**3 つの sha は Eclipse 側に既に存在する。** `GitLabMrVersion`（`src/main/kotlin/com/gitlab/eclipse/api/model/GitLabMrVersion.kt:12-14`）が `headCommitSha` / `baseCommitSha` / `startCommitSha` を保持しており、Phase 3 の `MergeRequestService`（`src/main/kotlin/com/gitlab/eclipse/api/MergeRequestService.kt:59-65`）が既に取得している。ただし現在 `ChangedFileNode` は `diffHeadSha` のみを保持しているため（`SidebarNode.kt:123-131`）、base / start を PR-3 でノードまで引き回す必要がある。

---

## 11. エラー処理

### 11.1 3 層のエラー検査

GraphQL の失敗は 3 箇所に現れうる。**すべてを検査しないと失敗が成功として通る。**

| 層 | 現れ方 | 扱い |
|---|---|---|
| L1 トランスポート | HTTP 非 2xx | 既存 `GitLabApiException`（status + correlation id）。既存 REST と同一 |
| L2 GraphQL 実行 | HTTP 200 かつ本文トップレベルに `errors: [...]` | `GraphQlException`。クエリ構文エラー・スキーマ不一致・認可エラー・**フィールド resolver の失敗**がここに出る |
| L3 mutation ペイロード | HTTP 200、トップレベル `errors` なし、しかし `createNote.errors` 等が非空 | `GraphQlException`。ビジネスロジック上の拒否がここに出る |

VSCode も L3 を明示的に検査している（`gitlab_service.ts:534-536`）。

**`errors` が非空なら常に失敗として扱う。** 本設計が扱う操作はいずれも部分結果に意味がなく、部分結果を成功として表示するとユーザーを誤認させるため。

**ただし「失敗」と「副作用が無い」は別である。** L2 は 2 つの異なる状況を含み、mutation が実行されたかどうかが違う。

| L2 の種類 | 応答の形 | mutation は実行されたか | 分類（§12.2） |
|---|---|---|---|
| **リクエストレベルエラー**（構文エラー、バリデーション、変数の型不一致、認証失敗） | `data` フィールドが**存在しない** | 実行されていないことが応答から証明できる | **Definite** |
| **フィールドレベルエラー**（部分成功。resolver の失敗） | `data` が**存在する**（`null` を含みうる）かつ `errors` も非空 | **証明できない。** 例えば `createNote` 自体は完了した後に、選択した `note` / `discussion` サブフィールドの resolver が失敗した場合がこれにあたる | **Ambiguous** |

この区別は GraphQL の仕様に基づく。リクエストレベルのエラーでは実行フェーズに入らないため `data` はレスポンスに含まれない。実行フェーズに入った後の失敗では `data` キーが存在する。

**したがって `GraphQlException` は「`data` キーが応答に存在したか」を保持する必要がある。** これを持たないと §12.2 の分類ができず、部分成功を Definite として `[Retry]` を出し、同じコメントを重複投稿する。トランスポート層（`GitLabGraphQlClient`）はこのフラグを例外に載せるところまでを担い、分類自体は `DiscussionService` が行う（§7.1）。

### 11.2 ユーザーへの提示

| 状況 | 提示 |
|---|---|
| 読み取り失敗 | 節を失敗ノードに置換 + 「see the Error Log」 |
| 書き込み失敗（本文入力を伴うもの） | §9 の再試行ダイアログ |
| 書き込み失敗（解決切替・削除など本文を伴わないもの） | 通知（`NotificationUtils.show`）。ユーザーは操作を再実行すればよい |
| 接続ゲート不成立（インスタンス不一致 / アカウント不一致 / 接続不安定） | 「表示元と現在の接続先が異なる」旨の通知。HTTP は発行しない（§15.3） |
| 権限不足 | メニューに出さない（FR-9）。実行時に判明した場合は L3 エラーとして通知 |

### 11.3 ログと監査

Phase 4 の Codex レビューで確定した規律を最初から適用する。

- **例外オブジェクト本体をログに添付しない。** 監査行には `exceptionType=<クラス名>` のみを付す。
  理由: Phase 4 PR-4 の Codex 指摘 P1-1 で、`IllegalArgumentException` のメッセージに `Bearer <token>` が含まれ Error Log に漏洩する経路が実在した。同型の漏洩を構造的に防ぐ。
- **コメント本文・GraphQL 変数・リクエストボディをログに出さない。** 件数や種別のみ。
- **打ち切り・失敗の件数は出す**（`errorCount=` 等）。Phase 4 PR-4 のブランチ全体レビュー I-1 と同じ方針。

---

## 12. タイムアウト・リトライ・冪等性

### 12.1 タイムアウト（確定要件）

**単発リクエスト**: 30 秒。既存 REST の `GitLabApiClient.REQUEST_TIMEOUT_SECONDS`（`GitLabApiClient.kt:292`）と同値。

**取得処理全体（wall-clock deadline）**: **60 秒**。ページングを伴う取得 1 回（= `loadDiscussions` の 1 呼び出し）の総時間上限とする。既存 `fetchListWithinDeadline`（同 :112-143）と同型の実装とし、以下を満たす。

```
start = clock()
fetched = []
ループ先頭（各ページ取得の前）で:
  1. キャンセル判定: isActive() が false → CancellationException
  2. 残時間 = deadline - (clock() - start)
     残時間 <= 0 → 打ち切り（例外ではなく fetched + 打ち切り理由 DEADLINE を返す）
  3. この 1 リクエストの timeout = min(30 秒, 残時間)
  4. try {
       page = execute(..., timeout)
       fetched += page
     } catch (タイムアウト例外) {
       ★ この timeout が deadline 予算に由来する（= 残時間 < 30 秒だった）場合、
         これは通常の deadline 到達である。例外を外へ伝播させず、
         fetched + 打ち切り理由 DEADLINE を返す ★
       残時間 >= 30 秒だった場合は真のサーバ無応答なので、例外を伝播させる
     }
```

**deadline 到達は 2 つの経路で起きる。両方を部分結果として扱う。**

| 経路 | 発生条件 | 扱い |
|---|---|---|
| ループ先頭での検出 | 前のページ取得が終わった時点で既に残時間 <= 0 | 取得済み分 + 打ち切り表示 |
| **リクエスト中のタイムアウト** | 残時間を timeout に設定した要求が、その残時間を使い切った | **同じく**取得済み分 + 打ち切り表示 |

2 番目を見落とすと、例えば「1 ページ取得済み・残り 10 秒」の次要求が 10 秒を超えた場合、ループ先頭に戻る前に例外が発生し、§8.1 の読み取り失敗経路に落ちて**取得済みページも打ち切り表示も失われる**。deadline 予算で設定した timeout に由来する例外は、通常の打ち切りとして扱わなければならない。

判別は「その要求に設定した timeout が 30 秒未満だったか（= 残時間由来だったか）」で行う。30 秒フルを与えた要求のタイムアウトは deadline とは無関係のサーバ無応答なので、通常の失敗として伝播させる。

**単発 30 秒 + ページ上限 20 だけでは総時間が有界にならない**（最悪 20 × 30 = 600 秒）。60 秒の wall-clock deadline を置くことで、ユーザーが節を何度も開き直しても長時間ジョブが積み上がらない。残時間を単発 timeout に反映することで、deadline 直前に 30 秒待つことも防ぐ。

本設計は内側（notes）をページングしない（§8.5）ため、ネストしたループによる要求数の増殖は起きない。ループは外側 1 段のみである。

この deadline は **PR-1 の確定要件**であり、未決事項ではない。

### 12.2 失敗の分類とリトライ

**自動リトライは一切行わない。** すべての書き込みが副作用を持ち、GraphQL mutation に冪等キーがないため、自動再送は二重投稿を起こす。

再送の可否は失敗の種類で決まる。**すべての失敗を「ユーザーが明示的に押したから安全」として扱ってはならない。**

| 分類 | 該当する失敗 | サーバ状態 | 再送 |
|---|---|---|---|
| **Definite**（確定拒否） | HTTP 4xx / **L2 のうちリクエストレベルエラー（`data` キーが応答に無い）** / L3 mutation ペイロード `errors` | mutation が実行されていないことを応答から証明できる | **安全**。§9.2 の [Retry] を出す |
| **Ambiguous**（結果不明） | タイムアウト、`IOException`（接続断）、レスポンス解析失敗、HTTP 5xx、**L2 のうちフィールドレベルエラー（`data` キーが存在する部分成功）** | **判定不能。**コミット済みかもしれない | **危険**。§9.3 のとおり、先に強制再取得してユーザーに現状を見せ、[Send again] を明示的に選ばせる |

**L2 を一律 Definite にしてはならない。** §11.1 のとおり、`data` と `errors` が併存する部分成功では mutation 本体が完了している可能性がある（`createNote` が成功した後に `note` サブフィールドの resolver が失敗した場合など）。これを Definite として `[Retry]` を出すと同じコメントを重複投稿する。**「mutation が実行されなかったことをレスポンスから証明できる場合だけ Definite」**という規準で判定する。

実装上の判定順:

```
HTTP 非 2xx        → 4xx: Definite / 5xx: Ambiguous
GitLabApiTimeoutException / IOException / JSON 解析失敗 → Ambiguous
GraphQlException(L2) → hasDataKey ? Ambiguous : Definite
GraphQlException(L3) → Definite（実行されたが payload.errors で拒否＝副作用なし）
```

L3 が Definite でよいのは、mutation の `errors` フィールドは「実行されたが業務ルールで拒否した」ことをサーバが明示したものであり、副作用が無いことをサーバが表明しているためである。

**分類のテストを単体テストに含める**（§18.1）。特に「`data` あり + `errors` あり → Ambiguous」「`data` なし + `errors` あり → Definite」の 2 ケースを個別に持つ。

読み取りについても自動リトライは行わない（ユーザーが節を再展開すればよい）。読み取りは副作用が無いため分類は不要。

### 12.3 冪等性

| 操作 | 冪等か | 対策 |
|---|---|---|
| `createNote` / `createDiffNote` | **冪等でない**（二重送信で重複コメント） | in-flight ガード + 自動リトライ禁止 + **Ambiguous 時の再送抑止（§9.3）** |
| `discussionToggleResolve` | 冪等（`resolved` は目標状態を渡す。トグルではない） | Ambiguous でも再送は安全。ただし現状を見せる方が親切なため §9.3 に合わせる |
| `updateNote` | 冪等（同じ body を 2 回送っても結果同一） | §13 の上書き防止が別途必要 |
| `destroyNote` | 2 回目は L3 エラー | エラーを通知して終わり（既に消えているため実害なし） |

`discussionToggleResolve` の引数が `resolve: Boolean`（トグルではなく目標状態）である点は mutation 名から誤解しやすいため、実装時に明示的にコメントを残す。

**冪等な操作（`discussionToggleResolve` / `updateNote` / `destroyNote`）については、Ambiguous でも重複の実害が無い。**それでも §9.3 の「先に再取得して現状を見せる」フローに揃えるのは、ユーザーから見た挙動を操作ごとに分岐させないためである。

---

## 13. 編集時の上書き防止（楽観ロックの代替）

GitLab の `updateNote` には楽観ロックがない。VSCode はこれを REST での事前確認で補っている。

```
updateNote を送る前に:
  REST GET /projects/{project_id}/merge_requests/{iid}/notes/{restNoteId}
  取得した body != 編集開始時に表示していた body
    → 送信せず拒否し、「このコメントは表示後に変更されたため編集できません」と通知
```

根拠: `gitlab_service.ts:567-590`。

**本設計はこれを移植する。** 移植しない場合、他者が同一ノートを編集した内容を黙って上書きする。これは復旧困難なデータ損失であり、許容できない。

REST 呼び出しは既存 `GitLabApiClient.fetchObject` で足りる。GraphQL の note GID から REST の数値 ID を取り出す必要がある（VSCode の `getRestIdFromGraphQLId` 相当。GID の末尾セグメント）。

**トランザクション境界**: この事前確認と `updateNote` の間に他者が編集する窓は残る（TOCTOU）。GitLab 側に楽観ロックがない以上これは閉じられない。**窓を狭めることしかできないという事実を、既知の制限として PR に明記する。**

---

## 14. 並行処理・UI スレッド

### 14.1 スレッドモデル

| 処理 | スレッド |
|---|---|
| 選択ノードの捕捉、ダイアログ表示、権限判定、ツリー更新 | UI スレッド |
| 接続捕捉、GraphQL 送信、DTO 正規化 | background（`coroutineScope.launch`） |
| background → UI の復帰 | `currentDisplay.asyncExec` |

### 14.2 世代ガード

ガードは目的の異なる 2 群からなる。**適用範囲が違うため、常に区別して扱う。**

| 群 | 条件 | 意味 | 適用範囲 |
|---|---|---|---|
| **ライフサイクル** | 1. `registry.active` が true<br>2. `registry.currentEpoch == startEpoch` | プラグインが停止処理に入っていない / 停止→再開を跨いでいない | **UI スレッドで何かを行うすべての箇所**。取得結果の反映も、mutation 完了処理も |
| **鮮度** | 3. `registry.isLatest(key, gen)` | 同一 MR に対するより新しい要求が出ていない | **取得結果をツリーへ反映する箇所のみ**。mutation 完了処理には適用しない（§8.2） |

- **取得結果の反映**（§8.1）: 1・2・3 をすべて満たす場合のみ行う。3 で落ちた場合は `LoadOutcome.Superseded` を返す。1・2 で落ちた場合は停止中なので `onOutcome` も呼ばない。
- **mutation 完了処理**（§8.2）: 1・2 のみを適用する。3 は適用しない。

これらは `asyncExec` の runnable 内で**最初に**評価する（gate-first）。ガード評価と後続処理が同一 UI ターン内で完結するため、判定後に状態が変わる窓は存在しない。

なお in-flight ガードの解放だけは UI スレッドではなく background の `finally` で行うため、ライフサイクルガードで UI 処理が破棄されても解放される（§14.4）。

### 14.3 起動・停止順序

Phase 4 PR-4 の Codex 指摘 P1-2 を最初から織り込む。

- `GitLabEclipseStartup.start` で `DiscussionGenerationRegistry.onActivate()` を UI スレッド上（`syncExec`）で呼ぶ。
- `stop` では `onDeactivate()` を **UI スレッドの `disposeAtShutdown` 系 `syncExec` の先頭**で呼ぶ。bare な `@Volatile` 書き込みにはしない。

理由: bare な volatile 書き込みでは「UI runnable がガードを通過してから実際に反映するまで」と停止が全順序化されず、停止後に通知やダイアログが出る窓（torn window）が残る。Phase 4 で実際に指摘され修正された経路であり、同じ誤りを繰り返さない。

**併せて申し送り**: 既存の `JobLogGenerationRegistry` には `onActivate` 相当がなく非対称のままである（follow-up issue #44）。本設計はその不具合を引き継がない。

### 14.4 多重送信の防止

既存 `InFlightWriteGuard`（`src/main/kotlin/com/gitlab/eclipse/ci/actions/InFlightWriteGuard.kt`）を流用する。

**キーに操作種別を含めてはならない。** 既存 `WriteKey` の KDoc が定めている契約は次のとおりである。

> The ACTION is deliberately NOT part of the key so a retry and a cancel on the same target serialize instead of racing each other.

操作種別をキーに含めると、同じノートに対する Edit と Delete、同じスレッドに対する Reply と Resolve が別キーになり、**相反する書き込みが同時に飛ぶ**。既存ガードを流用しても対象単位の直列化が成立しない。

したがってキーは**接続タグと対象**のみで構成する。

```kotlin
data class DiscussionWriteKey(
  val instanceUrl: String,      // normalizeInstanceUrl 済み
  val authFingerprint: String,  // §15.3 と同じ理由でアカウントも識別に含める
  val targetKind: String,       // "discussion" | "note" | "mergeRequest" | "mergeRequestLine"
  val targetId: String,         // targetKind に応じた識別子（下表）
)
```

**作成操作には送信前に存在する識別子を使う。** 既存スレッド/ノートに対する操作は `replyId` や note GID で識別できるが、**MR 全体コメント（`replyId = null`）と新規 diff スレッド（`createDiffNote`）には、送信前の時点で discussion / note の ID が存在しない。**これらにキーを与えないと、ダブルクリックによる重複コメントを防げない。

| 操作 | `targetKind` | `targetId`（送信前に確定している値） |
|---|---|---|
| スレッドへの返信 / 解決切替 | `"discussion"` | `discussion.replyId` |
| ノートの編集 / 削除 | `"note"` | note GID |
| **MR 全体コメント作成** | `"mergeRequest"` | **MR GID**（`gid://gitlab/MergeRequest/{id}`） |
| **新規 diff スレッド作成** | `"mergeRequestLine"` | **`"{MR GID}#{newPath}:{oneBasedLine}"`** |

- MR 全体コメントは MR GID 単位で直列化する。同一 MR への全体コメントを同時に 2 本投げる状況は UI 上ほぼ発生せず（ダイアログは 1 つずつ）、二重送信の抑止が目的として適切である。
- 新規 diff スレッドは行単位で直列化する。MR GID 単位にすると別の行へのコメントまでブロックしてしまうため、`path:line` まで含めて粒度を細かくする。ダブルクリック抑止という目的はこの粒度でも達成される。

`InFlightWriteGuard.tryAcquire` は `Any` を受けるため（既存 `WriteKey` と `CreateWriteKey` が別 data class として共存しているのと同じ理屈で）、新しい data class を追加するだけで既存キーと衝突せずに共存できる。

取得は UI スレッドで background 起動の**前**に行い、解放は background の `finally` で行う（§8.2）。これも既存ハンドラと同じ形である。

---

## 15. 認証・認可 / 既存機能への影響 / 移行・ロールバック

### 15.1 認証

トークンは既存の `GitLabTokenProviderManager` 経由で `GitLabApiClient.captureConnection()` が取得したスナップショットからのみ用いる。`GitLabGraphQlClient` は独自にトークンを読まない。

seqlock により「URL とトークンが同一世代の組であること」が保証される（`GitLabApiClient.kt:172-184` の KDoc を参照）。これにより、設定変更中の torn な `(新 URL, 旧トークン)` の組で送信することがない。

### 15.2 認可

- 表示: `note.userPermissions { resolveNote adminNote createNote }` をノードに保持し、コンテキストメニューの出し分けに使う（`PropertyTester`。既存 `CiActionPropertyTester` と同型）。
- 実行: 権限がないのに実行された場合は L3 エラーとして通知する（UI での抑止は最適化であり、最終的な判断はサーバ側が行う）。

### 15.3 接続ゲート（インスタンス + アカウント）

**読み取り・書き込みの両方**で、既存 `pinnedConnectionFor`（`src/main/kotlin/com/gitlab/eclipse/ci/actions/WriteAction.kt:60-74`）をそのまま用いる。

```kotlin
val connection = pinnedConnectionFor(apiClient, node.sourceInstanceUrl, node.sourceAuthFingerprint)
  ?: return  // 通知して終了。HTTP は一度も発行しない
```

このヘルパは 1 回の `captureConnection()` で比較値と pin 対象の両方を得たうえで、以下を**すべて**要求する。

1. `normalizeInstanceUrl(snapshot.instanceUrl) == normalizeInstanceUrl(nodeInstanceUrl)` — インスタンス一致
2. `snapshot.authFingerprint == nodeAuthFingerprint` — **アカウント一致**
3. `UnstableConnectionException` が出ない — 設定が安定している

**URL だけの比較では不十分である。** 同じ GitLab URL のままトークンだけが別アカウントに切り替わった場合、旧アカウントで取得した本文・権限・対象 ID に対する操作が新アカウントの資格情報で送信される。新アカウントにも権限があればサーバ側の認可は通ってしまうため、UI の実行時権限再確認では防げず、意図しないアカウントによる編集・削除が成立する。

この経路は Phase 4 で既に特定され封鎖されている（`pinnedConnectionFor` の KDoc: 「Rejects a changed instance url (FR-8), **a changed credential on the same url (9A)**」）。本設計は同じヘルパを再利用することでこれを継承する。

同じ理由から、世代管理のキーにも `authFingerprint` を織り込む（§8.1）。既存 `JobLogKey.of`（`src/main/kotlin/com/gitlab/eclipse/ci/joblog/JobLogKey.kt:17-19`）が `normalizeInstanceUrl(instanceUrl) + "\n" + authFingerprint` をハッシュしているのと同じ扱いである。アカウントが切り替わったのに世代キーが同一だと、旧アカウントで発行した取得の応答が新アカウントの表示に反映されうる。

### 15.4 既存機能への影響

| 対象 | 影響 |
|---|---|
| `GitLabApiClient` | **変更しない**（`captureConnection` を呼ぶだけ） |
| `GitLabHttpClient` | **変更しない** |
| `SidebarNode.kt` | ノード型を追加（既存型は変更しない） |
| `SidebarViewModel` / `SidebarContentProvider` / `SidebarLabelProvider` | 新ノード型の分岐を追加。既存分岐は変更しない |
| `plugin.xml` | command / handler / menu を追加。既存要素は変更しない |
| `GitLabEclipseStartup` | `onActivate` / `onDeactivate` の呼び出しを追加 |
| 既存テスト | 変更しない（既存 873 テスト・失敗 36 のベースラインを維持する） |

**変更はすべて追加であり、既存の振る舞いを変えない**ことを設計目標とする。

### 15.5 移行

データ移行はない。永続化する状態を持たない（すべてサーバ側が正本であり、プラグインはキャッシュを永続化しない）。

### 15.6 ロールバック

PR 単位で revert 可能である。

- PR-3 を revert → エディタからの新規 diff スレッドのみ消える。PR-1/PR-2 は動作を続ける。
- PR-2 を revert → 書き込みが消え、読み取りのみ残る。
- PR-1 を revert → Discussions 節ごと消える。GraphQL クライアントも消えるが、他機能は GraphQL を使っていないため影響がない。

各 PR は前の PR にのみ依存し、後の PR には依存しない。

---

## 16. 未決事項

「前提として確定した事項」と「レビューで判断を仰ぎたい事項」を区別する。

### 16.1 確定した前提（推測ではなく実ソースで確認済み）

- §10 のすべてのプロトコル定数（エンドポイント・クエリ・mutation・GID 形式・`DiffPositionInput`）は参照実装の実ソースから確定した。行番号を併記している。
- Eclipse 側 DTO に必要フィールドが揃っていること（§10.4 / §10.5）はソースを読んで確認した。
- **ページング全体の deadline = 60 秒**（§12.1）。Codex レビュー round 1 の指摘を受けて確定要件とした（旧 U-6 を削除）。
- **内側（notes）はページングしない**（§8.5）。参照実装にノートページング用のクエリが存在せず、実ソースで確定できないため。打ち切りは可視化する。
- **接続ゲートはインスタンス + アカウント（authFingerprint）の両方**（§15.3）。既存 `pinnedConnectionFor` を再利用する。
- **世代ガードは取得結果の反映にのみ適用し、mutation の完了処理には適用しない**（§8.2）。
- **エディタ行コメントは G3（非 dirty）+ G7（HEAD 一致）+ G8（対象パス未変更）の 3 ゲート**が揃って初めて行一致が保証される（§8.3）。
- **失敗は Definite / Ambiguous に分類し、Ambiguous では単純再送しない**（§9.3 / §12.2）。

round 2 の指摘を受けて追加で確定した事項:

- **L2 は一律 Definite ではない。** `data` キーが応答に存在しない（リクエストレベルエラー）場合のみ Definite、`data` が存在する部分成功は Ambiguous（§11.1 / §12.2）。
- **mutation 完了処理にもライフサイクルガード（`active` / `epoch`）を適用する。** 外すのは鮮度ガード（`isLatest`）だけである（§8.2）。
- **in-flight ガードの解放は background の `finally`** で行う（§8.2 / §14.4）。
- **in-flight キーに操作種別を含めない。** `DiscussionWriteKey(instanceUrl, authFingerprint, targetKind, targetId)`（§14.4）。
- **`loadDiscussions` は `LoadOutcome` の完了契約を持つ。** `Applied` でのみ `[Send again]` を出す（§8.1 / §9.3）。
- **行一致は「捕捉した本文 == HEAD blob」で直接検証する。** dirty 判定と作業ツリー検査の組み合わせでは TOCTOU が閉じない（§8.3）。
- **`GitLabGraphQlClient.execute` は `timeout: Duration` を受け取る**（§7.1 / §12.1）。
- **残時間由来の timeout でリクエストが失敗した場合も部分結果として扱う**（§12.1）。

round 3 の指摘を受けて追加で確定した事項:

- **`LoadOutcome` の契約はライフサイクル終了を明示的な例外とする。** それ以外では必ず 1 回呼ばれ、すべての早期終了が対応する outcome に合流する（§8.1）。
- **世代キーは §7.3 と §8.1 で同一**（`normalizeInstanceUrl` + `authFingerprint` + `projectId` + `mrIid`）。
- **`startEpoch` は mutation 側でも background 起動前の UI ターンで凍結する**（§8.2）。
- **行番号はスナップショット時に `selection.startLine + 1` で 1 始まりへ変換する**（§8.3.1）。
- **作成操作の in-flight キーは送信前に確定している識別子を使う**（MR GID / `{MR GID}#{newPath}:{line}`）（§14.4）。

### 16.2 未決（レビューで判断したい）

| # | 事項 | 現時点の提案 | 判断が必要な理由 |
|---|---|---|---|
| U-1 | `mergeRequestDiffHeadSha` 引数は GitLab 14.9 以降でのみ有効。VSCode はインスタンスバージョンで新旧 mutation を切り替えている（`gitlab_service.ts:522-527`）。本プラグインには**インスタンスバージョン検出機構が存在しない** | 新版 mutation のみを使い、旧版フォールバックを持たない。14.9 は 2022-03 リリースであり、Duo を前提とする本プラグインの実質的な下限を下回る | 誤っていれば古いインスタンスで `CreateNote` が全滅する。バージョン検出を新設するかどうかの判断でもある |
| U-2 | 上記に関連し、VSCode は `validateVersion('MR Discussions', REQUIRED_VERSIONS.MR_DISCUSSIONS)` を全操作の冒頭で呼んでいる | 移植しない（U-1 と同じ理由） | 同上 |
| U-3 | `system: true` のノートを除外する方針 | 除外する（レビュー用途でノイズになる） | 除外すると「alice が assignee を変更」等の履歴が見えなくなる。それが許容されるかは利用者判断 |
| U-4 | ディスカッションの取得タイミング | 節の展開時に遅延取得。自動更新なし | MR ノードの展開時に先読みする案もある。応答性とリクエスト数のトレードオフ |
| U-5 | クエリで要求するフィールドの絞り込み。`avatarUrl` / `bodyHtml` は不要（§2.2）だが、`author.name` / `author.webUrl` / `note.url` も不要か | `username` / `body` / `createdAt` / `id` / `system` / `userPermissions` / `position` のみ要求し、他は落とす | 落としすぎると後で機能追加時にクエリ変更が必要になる。過剰に取ると応答が重くなる |
| U-7 | `ThreadNode` / `NoteNode` に複製して持たせる識別子の範囲（§7.4） | 接続タグ（sourceInstanceUrl / sourceAuthFingerprint）+ 書き込みに必要な最小集合（projectId / mrIid / mrGid / mrSha / namespaceWithPath / replyId / noteId） | 複製が多いとノード生成コストとメモリが増え、少ないとハンドラが親を辿れず失敗する |
| U-8 | §9 の「失敗コメント再試行」の読み替えを、台帳 #7 で F5 実装済みとして計上してよいか | 計上する（本質的要件 FR-8 を満たすため） | パリティ計数の一貫性に関わる。UI 機構が異なるため、厳密には同一機能ではない |
| U-9 | 台帳 #7 の D9 の記述誤り（§3.2） | 本設計の範囲外。Phase 5B 着手時、または独立に訂正する | 本設計で訂正すると設計書がコミット対象になるため、issue 側で行う必要がある |
| U-10 | ノート（内側）のページングを将来対応するか（§8.5） | PR-1 では対応しない。打ち切りを可視化するに留める | GitLab の GraphQL スキーマが `Discussion.notes(after:)` を直接引ける形を提供しているかは実インスタンスでしか確認できない。確認できた時点で別途判断する |

---

## 17. 想定されるリスク

| # | リスク | 影響 | 緩和策 |
|---|---|---|---|
| R-1 | **GraphQL クエリがインスタンスのスキーマと整合しない。** headless 環境では実接続で検証できないため、クエリ文字列の誤りは CI もテストも検出できず、ユーザー実機で初めて露見する | 機能が全滅する。手戻りが最大 | クエリ/変数を持つコンポーネントは最上位モデル（`fable`）に割り当て、**読んだ実ソースのパス/行を根拠として成果物に併記させる**（記憶からの捏造防止）。加えて §10 の確定値を実装計画に埋め込み、実装者に再導出させない |
| R-2 | L2/L3 のエラー検査漏れにより、失敗が成功として表示される | ユーザーがコメントできたと誤認し、実際には投稿されていない | 3 層すべての検査を単体テストで実証する（各層について「失敗が例外になる」テストを個別に持つ） |
| R-3 | 接続ゲートの評価が HTTP 送信より後になり、他インスタンスにトークンが送られる | 資格情報の漏洩 | Phase 4 と同じく「ゲート不成立時は API 呼び出し回数 0」をカウンタで実証するテストを置く |
| R-3b | **同一 URL のままアカウントだけが切り替わり、旧アカウントで取得した対象を新アカウントの資格情報で操作する** | 意図しないアカウントによる編集・削除。新アカウントに権限があればサーバ認可も通るため検知されない | 既存 `pinnedConnectionFor` を再利用し、URL と `authFingerprint` の両方一致を要求する。世代キーにも fingerprint を含める（§15.3）。**URL のみのゲートはこのリスクを緩和しない** |
| R-4 | 監査ログ経由でのトークン・コメント本文の漏洩 | 情報漏洩 | §11.3 の規律。加えて Phase 4 PR-4 と同様、「ログ出力に本文由来のマーカー文字列が含まれない」ことをテストで実証する |
| R-5 | ツリーのノード数がスレッド数 × ノート数で増大し、描画が重くなる | UI 応答性の劣化 | ページング上限 + wall-clock deadline による有界化 + 打ち切り表示（§8.5 / §12.1） |
| R-6 | 編集の TOCTOU 窓（§13） | 他者の編集を上書きする | 窓を狭める事前確認を実装。閉じられないことを既知の制限として明記 |
| R-7 | **結果不明の失敗後に再送し、サーバが既にコミットしていた場合に重複コメントが作られる** | 重複投稿。in-flight ガードは最初の要求が完了済みのため防げない | 失敗を Definite / Ambiguous に分類し、Ambiguous では先に強制再取得してユーザーに現状を見せてから [Send again] を選ばせる（§9.3 / §12.2） |
| R-8 | 停止処理と UI 反映の競合により、停止後に通知やダイアログが出る | 例外・ゴースト UI | §14.3 の停止順序を最初から適用 |
| R-9 | **エディタの表示内容と MR head 版がずれた状態で行コメントを送る** | 別の行にコメントが付く、または GitLab が position を拒否する | G3（非 dirty）+ G7（HEAD 一致）+ G8（対象パス未変更）の 3 ゲートを揃えて初めて送信する（§8.3） |
| R-10 | mutation 完了処理を鮮度ガードで破棄し、成功が表示に反映されない / 失敗時に本文が失われる | FR-8・FR-10 違反 | 鮮度ガードの適用範囲を「取得結果の反映」に限定する（§8.2） |
| R-11 | **R-10 の修正を過剰に行い、停止処理中に UI が起動する。** ブロッキング HTTP は停止後に戻りうる | 停止後のダイアログ・再取得。R-8 と同じ障害 | ライフサイクルガード（`active` / `epoch`）は mutation 完了処理にも**適用する**。外すのは鮮度ガードだけ（§8.2） |
| R-12 | **部分成功（`data` + `errors`）を Definite と誤分類し、`[Retry]` で重複投稿する** | R-7 と同じ重複投稿が、L2 経由で再発する | L2 は `data` キーの有無で分岐。「mutation が実行されなかったことを証明できる場合だけ Definite」（§11.1 / §12.2） |
| R-13 | **in-flight キーに操作種別を含め、同一対象への相反する書き込みが同時に飛ぶ** | 同じノートへの Edit と Delete の競合。既存ガードを流用しても直列化されない | キーは接続タグ + 対象のみ。既存 `WriteKey` の契約（ACTION を意図的に含めない）に従う（§14.4） |
| R-14 | **`loadDiscussions` の完了を待たずに `[Send again]` を出し、ユーザーが最新状態を見ないまま再送する** | R-7 の緩和策が機能せず重複投稿 | `LoadOutcome` の完了契約を定義し、`Applied` でのみ再送を許可（§8.1 / §9.3） |
| R-15 | **残時間由来の timeout が例外として伝播し、取得済みページと打ち切り表示が失われる** | 通常の deadline 到達が読み取り失敗として扱われる | deadline 予算由来の timeout を捕捉して部分結果を返す（§12.1） |
| R-16 | **早期終了経路が `LoadOutcome` を返さず、§9.3 の callback が永久に呼ばれない** | Ambiguous 失敗後に本文を保持したダイアログすら出ず、入力が失われる（FR-8 違反） | すべての早期終了を対応する outcome の 1 回通知に合流させる（§8.1） |
| R-17 | **`startEpoch` を background 完了後に読み、停止→再開を跨いだ古い mutation の UI が新しいライフサイクルで起動する** | 停止後のダイアログ・再取得（R-8 と同じ障害） | `startEpoch` は background 起動前の UI ターンで凍結する（§8.2） |
| R-18 | **Eclipse の 0 始まり行番号を変換せず送り、全 diff コメントが 1 行上に付く** | 機能としては動くが、常に誤った行に付く。レビューとして使い物にならない | スナップショット時に `+ 1` して 1 始まりで保持し、変数名に基数を含める。先頭行を含む境界テストを置く（§8.3.1） |
| R-19 | **作成操作の in-flight キーが構築できず、ガードを省略してダブルクリックで重複コメントが作られる** | R-7 と同じ重複投稿が、作成経路で再発 | 送信前に確定している識別子（MR GID / `{MR GID}#{newPath}:{line}`）をキーにする（§14.4） |

---

## 18. テスト方針

### 18.1 単体テスト（自動）

| 対象 | 検証内容 |
|---|---|
| `GitLabGraphQlClient` | URI 組み立て（`/api/graphql` であること、トレイリングスラッシュ処理）／L1 非 2xx → 例外／L2 `errors` 非空 → 例外／接続 pin（URL・トークンがスナップショット由来であること）／タイムアウト伝播／リクエスト本文が `{"query","variables"}` 形であること |
| `DiscussionService` | クエリ変数の組み立て（`iid` が文字列であること）／GID 組み立て（`id` を使い `iid` を使わないこと）／`namespaceWithPath` の導出（`#` と `!` の両方）／L3 ペイロード `errors` 非空 → 例外／DTO 正規化（null フィールドの既定値）／`system` ノート除外／`positionType` 判別 |
| **ページング**（§8.5 / §12.1） | 外側の `hasNextPage` → `endCursor` を次要求の `$afterCursor` に渡すこと／ページ上限 20 で打ち切り、取得済み分を返し警告を出すこと／**wall-clock deadline 60 秒で打ち切ること**（注入クロックで実証）／**各ページの単発 timeout が `min(30 秒, 残時間)` として `execute` に渡ること**（引数を捕捉して実証）／**残時間由来の timeout でリクエストが失敗した場合に、例外を伝播させず取得済み分 + 打ち切り理由を返すこと**／逆に 30 秒フルを与えた要求のタイムアウトは通常の失敗として伝播すること／`notes.pageInfo.hasNextPage` が真のスレッドに打ち切りフラグが立つこと／内側をページングしようとしないこと（要求回数で実証） |
| **失敗分類**（§12.2） | `GitLabApiTimeoutException` / `IOException` / JSON 解析失敗 / HTTP 5xx → **Ambiguous**／HTTP 4xx / L3 `errors` → **Definite**／**L2 は `data` キーの有無で分岐**: 「`data` なし + `errors` あり → Definite」「`data` あり + `errors` あり → Ambiguous」の 2 ケースを個別に持つ |
| **完了契約**（§8.1 `LoadOutcome`） | `Applied` / `Superseded` / `Failed` / `GateRejected` / `Skipped` がそれぞれ **1 回だけ** UI スレッドで渡ること／`force = false` かつ読み込み済み → `Skipped`／接続ゲート拒否 → `GateRejected`（`return` で終わらない）／新しい世代に破棄 → `Superseded`（`Applied` ではない）／**`active == false` / `epoch` 変化時は呼ばれないこと**（契約上の例外） |
| **in-flight キー**（§14.4） | `DiscussionWriteKey` が**操作種別を含まない**こと。同一ノートへの Edit と Delete が同一キーになり直列化されること／`WriteKey` / `CreateWriteKey` と衝突しないこと／**作成操作のキーが構築できること**: MR 全体コメント = MR GID、新規 diff スレッド = `{MR GID}#{newPath}:{line}`／MR 全体コメントの二重送信が抑止されること／異なる行への diff コメントは同時に進められること |
| **行番号の基数**（§8.3.1） | `selection.startLine + 1` がスナップショット時に適用されること／**ファイル先頭行（0 始まりの 0 → 1 始まりの 1）** を含む境界ケース／送信される `newLine` が 1 始まりであること |
| `DiscussionGenerationRegistry` | `CiLintGenerationRegistryTest` と同等の 13 ケース（完了順逆転・per-key 独立・停止区間・epoch・ABA 回避）／**キーが `authFingerprint` を含み、同一 URL でアカウントが違えば別キーになること** |
| **接続ゲート**（§15.3） | URL 不一致時に API 呼び出し回数が 0 であること／**同一 URL・`authFingerprint` 不一致時にも 0 であること**（カウンタで実証）／`UnstableConnectionException` → null → 呼び出し 0 |
| **書き込み後の再取得**（§8.2 / FR-10） | mutation 成功時に `loadDiscussions(force = true)` 経路で**実際に HTTP 取得が発行されること**（`loadState == LOADED` でも抑止されないこと） |
| **ガードの適用範囲**（§8.2） | mutation 送信中に generation が進んでも、①in-flight ガードが解放される ②成功時の再取得が発行される ③失敗時に本文が保持されダイアログ入力が渡される — の 3 点が成立すること（**鮮度ガードは適用されない**）／**逆に `active == false` または `epoch` 変化時は UI 処理が一切起きないこと**（ライフサイクルガードは適用される）／**その場合でも in-flight ガードが解放されていること**（background の `finally` で解放されるため） |
| **行一致ゲート**（§8.3、SWT-free 部分） | 捕捉本文 == HEAD blob なら送信、1 バイトでも異なれば拒否／改行コードのみが異なる場合も拒否（正規化しない）／`newLine` が捕捉時のカーソル行であり、後続のエディタ操作に影響されないこと |
| 監査ログ | 本文・トークン由来のマーカー文字列がログ行に含まれないこと（識別可能なマーカーを使う。Phase 4 PR-4 の指摘を踏まえ、引用符付きの弱い検証にしない） |
| 位置情報 | `DiffPositionInput` の組み立て（`newLine` のみ、`oldLine` 不在） |

### 18.2 実機のみで確認する項目（PR 説明文のチェックリスト）

- Discussions 節の展開・ツリー描画・truncated 表示（3 種すべて: 外側上限 / deadline / notes 打ち切り）
- コンテキストメニューの出し分け（権限・`resolvable` による）
- `CommentInputDialog` の複数行入力・事前充填・[Retry]/[Cancel]・[Send again]/[Cancel]
- エディタ右クリックからの新規 diff スレッド（G1〜G9 の各ゲート）。特に **G3（未保存エディタで拒否されること）**
- 実 GitLab インスタンスに対する全 mutation の成功（**GraphQL クエリがスキーマと整合することの唯一の検証機会**）
- 書き込み成功後にツリーが最新化されること（FR-10）
- 接続先アカウントを切り替えた後に、旧アカウントで取得したノードから操作できないこと（§15.3）
- 停止→再開後の挙動

### 18.3 検証バー（既存踏襲）

各タスクおよび PR について以下をすべて満たす。

- 対象テストが PASS する
- **全体の失敗数が 36 のままである**（headless 環境の SWT 依存テスト由来。これがベースライン）
- 新規の失敗が `api/` `mergerequests/` に存在しない
- 変更したファイルの detekt 指摘が 0

---

## 19. 受け入れ条件

### PR-1（読み取り基盤）

1. `GitLabGraphQlClient` が存在し、§18.1 の全テストが PASS する。
2. `DiscussionService.getDiscussions` が**外側のみをページングし**、`endCursor` を次要求に渡す。ページ上限 20 と **wall-clock deadline 60 秒**の両方で打ち切り、取得済み分を返して警告を記録する（§8.5 / §12.1）。
3. 各ページの単発 timeout が `min(30 秒, 残時間)` として `execute` に渡る。**残時間由来の timeout でリクエストが失敗した場合も、例外を伝播させず取得済み分 + 打ち切り表示を返す**（§12.1）。
3b. `loadDiscussions` が `LoadOutcome`（`Applied` / `Superseded` / `Failed` / `GateRejected` / `Skipped`）を UI スレッドで 1 回だけ渡す。**すべての早期終了（読み込み済み → `Skipped`、接続ゲート拒否 → `GateRejected`）が対応する outcome に合流し、`return` だけで終わる経路が無い。**唯一の例外はライフサイクル終了（`active == false` / `epoch` 変化）で、この場合は callback を呼ばない（§8.1 の契約上の例外）。
3c. 世代キーが `normalizeInstanceUrl(instanceUrl)` と `authFingerprint` を含む（§7.3 / §8.1 が一致）。
4. `notes.pageInfo.hasNextPage` が真のスレッドに `(more replies — open in GitLab)` 子ノードが出る。内側をページングしようとしない。
5. サイドバーの MR ノード配下に Discussions 節が出る。展開時に遅延取得する（`loadDiscussions(force = false)`）。
6. スレッドが `path:line` または `(overall)` のラベルで、解決状態とともに表示される。
7. `system` ノートが表示されない。
8. 取得失敗時に失敗ノードが表示され、`loadState` が `FAILED` に戻り、Error Log に本文・トークンを含まない記録が残る。
9. **接続ゲート**（§15.3）: インスタンス不一致時も、同一 URL でのアカウント（`authFingerprint`）不一致時も、HTTP が一度も発行されない。
10. 世代キーが `authFingerprint` を含み、同一 URL でアカウントが異なれば別キーになる。
11. 検証バー（§18.3）を満たす。

### PR-2（書き込み）

1. スレッドへの返信ができる（FR-2）。
2. MR 全体コメントを作成できる（FR-3）。
3. 解決可能なスレッドを解決・未解決にできる（FR-5）。
4. ノートを編集できる。§13 の事前確認により、表示後に変更されたノートの編集は拒否される（FR-6）。
5. ノートを確認ダイアログを経て削除できる（FR-7）。
6. 権限のない操作がメニューに出ない（FR-9）。
7. **Definite 失敗**時、本文を保持したダイアログが [Retry] / [Cancel] とともに再提示される（FR-8 / §9.2）。
8. **L2 の部分成功（`data` キーあり + `errors`）が Ambiguous に分類され、[Retry] が出ない**（§11.1 / §12.2）。
9. **Ambiguous 失敗**時、[Retry] を出さず強制再取得を行い、**`LoadOutcome` が `Applied` の場合にのみ** [Send again] / [Cancel] を提示する。`Superseded` / `Failed` / `GateRejected` / `Skipped` では [Copy text] / [Cancel] のみ（§9.3）。
10. **in-flight キーが操作種別を含まず**、同一ノートへの Edit と Delete が直列化される（§14.4）。
10b. **MR 全体コメント作成の in-flight キーが MR GID で構築され**、ダブルクリックによる二重送信が抑止される（§14.4）。
11. **書き込み成功後、`loadState == LOADED` であっても再取得の HTTP が実際に発行され**、当該 MR の Discussions 節が最新化される（FR-10 / §8.2）。
12. **mutation 送信中に generation が進んでも**、in-flight ガードの解放・成功時の再取得・失敗時の本文保持がいずれも実行される（鮮度ガードは適用されない。§8.2）。
13. **`active == false` または `epoch` 変化時は UI 処理が一切起きず、それでも in-flight ガードは解放されている**（ライフサイクルガードは適用される / 解放は background の `finally`。§8.2）。
14. 接続ゲートが PR-1 AC-9 と同じ強度（URL + `authFingerprint`）で書き込みにも適用される。
15. 検証バー（§18.3）を満たす。

### PR-3（エディタ行からの新規 diff スレッド）

1. MR ブランチをチェックアウトした状態で、エディタ右クリックから行コメントを作成できる（FR-4）。
2. **カーソル行と本文が同一 UI ターンでスナップショット化され**、以降の処理がエディタの状態を再参照しない（§8.3）。
3. **G3（エディタが dirty）で拒否される**（早期拒否）。
4. **G8（捕捉した本文 != HEAD blob）で拒否される。**改行コードのみが異なる場合も拒否される（正規化しない）。
5. スナップショット取得後にエディタを編集しても、送信される `newLine` が変わらない。
5b. **送信される `newLine` が 1 始まりである。ファイル先頭行にコメントすると `newLine = 1` になる**（§8.3.1）。
5c. **新規 diff スレッド作成の in-flight キーが `{MR GID}#{newPath}:{line}` で構築され**、ダブルクリックによる二重送信が抑止される。異なる行へのコメントは同時に進められる（§14.4）。
6. G5〜G9 の各ゲート不成立時に、§8.3 の表に定めた文言で拒否される（G7 は既存 `OpenMrFileHandler` と同一文言）。
7. `DiffPositionInput` が `newLine` のみを含み、3 つの sha が MR の diff version 由来である。
8. old 側へのコメント作成が対象外であることが PR に明記される。
9. 検証バー（§18.3）を満たす。

### Phase 5A 全体

1. 台帳 #7 の D13 が 5/5 になる（U-8 の判断を経て）。
2. 既存機能の振る舞いが変わっていない（既存テストの結果が変化しない）。
3. 新規 bundle 依存が追加されていない。
4. ディレクトリ構成が変更されていない。

---

## 20. 参照

### 参照実装（`gitlab-workflow` v6.85.3、`./out/gitlab-vscode-extension`。読み取り専用）

- `src/common/gitlab/api/api_client.ts` — GraphQL エンドポイントとトランスポート
- `src/desktop/gitlab/graphql/shared.ts` — フラグメント定義
- `src/desktop/gitlab/graphql/get_discussions.ts` — 読み取りクエリ
- `src/desktop/gitlab/graphql/create_note.ts` — 返信 / 全体コメント
- `src/desktop/gitlab/graphql/create_diff_comment.ts` — 新規 diff スレッドと `DiffPositionInput`
- `src/desktop/gitlab/gitlab_service.ts` — 解決切替 / 削除 / 編集の mutation、GID 組み立て、楽観ロック代替
- `src/desktop/commands/mr_discussion_commands.ts` — 失敗コメントの再試行 / 取消
- `src/desktop/review/` — VSCode の Comments API による構成（本設計では採らない）
- `src/common/security_scans/run_security_scan.ts` — D9 が LSP 経路であることの根拠

### 本リポジトリ（`develop` @ `0f53243`）

- `src/main/kotlin/com/gitlab/eclipse/api/GitLabApiClient.kt` — REST クライアント、`captureConnection`
- `src/main/kotlin/com/gitlab/eclipse/api/model/GitLabMergeRequest.kt` — MR DTO
- `src/main/kotlin/com/gitlab/eclipse/api/model/GitLabMrVersion.kt` — diff version の 3 sha
- `src/main/kotlin/com/gitlab/eclipse/api/MergeRequestService.kt` — diff version 取得
- `src/main/kotlin/com/gitlab/eclipse/views/sidebar/SidebarNode.kt` — ノード定義
- `src/main/kotlin/com/gitlab/eclipse/mergerequests/OpenMrFileHandler.kt` — リビジョン一致ゲート
- `src/main/kotlin/com/gitlab/eclipse/ci/lint/CiLintGenerationRegistry.kt` — 世代管理の原型
- `src/main/kotlin/com/gitlab/eclipse/ci/actions/InFlightWriteGuard.kt` — 多重送信防止
- `build.gradle.kts` — bundle 依存一覧（`org.eclipse.compare` が不在であることの根拠）
