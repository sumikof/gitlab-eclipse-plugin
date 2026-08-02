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
| NFR-3 | 書き込みは、スナップショットが「表示元と同一インスタンス」であることを確認してから送信する |
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
  /** 単発の GraphQL 呼び出し。connection に pin される。 */
  fun <T> execute(
    query: String,
    variables: Map<String, Any?>,
    type: Class<T>,
    connection: ConnectionSnapshot,
  ): T

  /** 接続スナップショットの取得は GitLabApiClient に委譲する（seqlock の実装を二重に持たない）。 */
  fun captureConnection(): ConnectionSnapshot = apiClient.captureConnection()
}
```

- URI は `<connection.instanceUrl のトレイリングスラッシュ除去>/api/graphql` として組み立てる。`GitLabApiClient.buildUri` は使わない（`/api/v4` を含むため）。
- 本文は `{"query": ..., "variables": {...}}` を Gson でシリアライズしたもの。`Content-Type: application/json`。
- ページングは行わない。呼び出し側（`DiscussionService`）がカーソルを進める。**理由**: `pageInfo` の位置はクエリごとに異なり、トランスポート層が知るべきではない。

**知らないこと**: 何のクエリを投げているか、結果をどう使うか、UI。

### 7.2 `DiscussionService`

**責務**: GraphQL のクエリ・変数・レスポンス DTO を持ち、ドメイン型を返す。

- クエリ文字列を定数として保持（§10 の確定値）。
- GID の組み立て（`gid://gitlab/MergeRequest/{id}`）。
- `namespaceWithPath` の導出（`references.full` を `#` または `!` で分割した先頭）。
- ページングループ（discussion 一覧とノート一覧の二重）。
- レスポンス DTO（nullable）からドメイン型（non-null）への正規化。
- `system: true` のノートの除外。

**知らないこと**: SWT、UI スレッド、ツリー構造。**このクラスは SWT-free であり、単体テスト可能である。**

### 7.3 `DiscussionGenerationRegistry`

**責務**: 応答の UI 反映を最新世代に限定する。Phase 4 の `CiLintGenerationRegistry`（`src/main/kotlin/com/gitlab/eclipse/ci/lint/CiLintGenerationRegistry.kt`）の忠実なミラー。

- キー = （instanceUrl, projectId, mrIid）。
- `nextGeneration(key)` / `isLatest(key, gen)` / `currentEpoch` / `onActivate()` / `onDeactivate()`。
- `@Volatile active` のみが複数スレッドから読まれ、カウンタ・epoch・latest マップは UI スレッド専有。

### 7.4 サイドバーノード

`SidebarNode.kt` に追加する（既存ファイルへの追記であり新規ファイルではない — 既存のノード定義がすべて 1 ファイルに集約されている構成に従う）。

| ノード | 保持する情報 |
|---|---|
| `DiscussionsSectionNode` | 親 MR の識別子（instanceUrl / projectId / mrIid / mrGid / mrSha / namespaceWithPath）、子スレッド、読み込み状態 |
| `ThreadNode` | `replyId`、`resolved`、`resolvable`、位置情報（path / line / positionType）、`userPermissions`、子ノート |
| `NoteNode` | ノート GID、`body`、著者 username、作成日時、`userPermissions`、親スレッドへの参照 |

**設計上の注意**: Phase 3 の `ChangedFileNode` は「ハンドラが選択ノードから親 MR まで遡れない」ため必要な情報を自ノードに複製している（`SidebarNode.kt:118-132` の KDoc を参照）。同じ制約が本設計にも当てはまるため、`ThreadNode` / `NoteNode` にも書き込みに必要な識別子一式を複製して持たせる。

### 7.5 `CommentInputDialog`

**責務**: 複数行のコメント本文を受け取る。

JFace の `InputDialog` は単一行 `Text` のみであり、コメント本文には適さない。`org.eclipse.jface.dialogs.Dialog` を継承し、`SWT.MULTI or SWT.WRAP or SWT.V_SCROLL or SWT.BORDER` の `Text` を持つ小さなダイアログを 1 つ作り、作成・返信・編集・再試行で共用する。

パラメータ: タイトル、説明文、初期本文（編集・再試行時に事前充填）、エラーメッセージ（再試行時に表示）。

---

## 8. 処理フロー

### 8.1 読み取り（PR-1）

`DiscussionsSectionNode` が展開されたとき、初回のみ取得する（遅延取得）。

```
[UI スレッド]
  ユーザーが Discussions 節を展開
    → 既に読み込み済みなら何もしない
    → startEpoch = registry.currentEpoch を捕捉
    → gen = registry.nextGeneration(key)
    → 節を「Loading…」表示に切り替え
    → background へ
[background]
  connection = graphQlClient.captureConnection()
  同一インスタンスゲート: sameConfiguredInstance(node.instanceUrl, connection.instanceUrl)
    → 不一致なら中止（読み取りでも他インスタンスに問い合わせない）
  discussions = discussionService.getDiscussions(connection, namespaceWithPath, mrIid)
    ページングループ（§8.5）
  正規化・system ノート除外・ソート
[UI スレッド（asyncExec）]
  ガード: registry.active が false → 破棄
  ガード: registry.currentEpoch != startEpoch → 破棄
  ガード: !registry.isLatest(key, gen) → 破棄
  ツリーへ反映
```

失敗時は節を「Failed to load discussions — see the Error Log」ノードに置き換え、同じ世代ガードを通す。

### 8.2 書き込み共通フロー（PR-2）

すべての書き込み（返信・全体コメント・解決切替・編集・削除）は同一の骨格に従う。

```
[UI スレッド]
  選択ノードを同期的に捕捉（背景に渡す前に）
  権限チェック（メニュー出し分けで既に済んでいるが、実行時にも再確認）
  入力が必要な操作はダイアログを開く → Cancel なら終了
  in-flight ガード取得（キー = 操作種別 + 対象 ID）→ 取得できなければ「already in progress」通知
  startEpoch / gen を捕捉
    → background へ
[background]
  connection = captureConnection()
  同一インスタンスゲート（送信前に評価する。ここを通らなければ HTTP を一度も発行しない）
  GraphQL mutation 送信
  3 層のエラー検査（§11.1）
[UI スレッド（asyncExec）]
  世代ガード（§8.1 と同じ 3 つ）
  成功 → 当該 MR の Discussions 節のみ再取得（§8.1 のフローを再入）
  失敗 → §9 の再試行フロー、または通知
  finally: in-flight ガード解放
```

**重要**: 同一インスタンスゲートは HTTP 送信より前に評価する。Phase 4 の `runCiLint` で確立した不変条件（「ゲート不成立時は API 呼び出し回数 0」をテストで実証する）を踏襲する。

### 8.3 エディタ行からの新規 diff スレッド（PR-3）

```
[UI スレッド]
  G1: アクティブエディタが ITextEditor か
  G2: エディタ入力が IFileEditorInput か（ワークスペース上のファイルか）
  G3: カーソル行番号を取得（1 始まりへ変換）
  ダイアログで本文を受け取る → Cancel なら終了
    → background へ
[background]
  G4: そのファイルを含むワークスペースリポジトリがちょうど 1 つに定まるか
      （RepositoryContextResolver。0 件・複数件は拒否）
  G5: そのリポジトリの現ブランチに対応する MR が特定できるか
      （CurrentBranchMrLookup）
  G6: リポジトリの HEAD sha == その MR の diff head sha か
      （OpenMrFileHandler と同一のゲート）
  G7: 対象ファイルの MR 相対パスが、その MR の diff の newPath に含まれるか
  connection = captureConnection() / 同一インスタンスゲート
  createDiffNote 送信（position は §10 の DiffPositionInput）
[UI スレッド]
  世代ガード → 成功なら Discussions 節を再取得
```

**G6 が本設計の要である。** このゲートが成立するとき、エディタに表示されているファイルの内容は MR の head 版そのものである。したがって**カーソル行 = diff の `newLine`** が推測なしに確定し、行ズレ補正が原理的に不要になる。

G6 不成立時のメッセージは既存 `OpenMrFileHandler.CHECKOUT_FIRST_MESSAGE`（`src/main/kotlin/com/gitlab/eclipse/mergerequests/OpenMrFileHandler.kt:133-134`）と同一文言を使い、ユーザーから見た挙動を既存機能と揃える。

### 8.4 old 側（削除行）の扱い

本設計は `newLine` のみを送る。VSCode は old 側の未変更行にコメントするために `getNewLineForOldUnchangedLine` で新旧行対応を計算しているが（`src/desktop/commands/mr_discussion_commands.ts:31-40`）、これは GitLab 側の未修正 issue に対する回避策である。`newLine` のみに限定すればこの計算は不要になる。

old 側へのコメント作成は**対象外**とし、PR に既知の制限として明記する。読み取り時には old 側のコメントも `oldPath:oldLine` として表示する（読み取りには制限を設けない）。

### 8.5 ページング

GraphQL のページングは二重構造である。

1. `project.mergeRequest.discussions.pageInfo` — スレッドのページング
2. 各 `discussion.notes.pageInfo` — スレッド内ノートのページング

両方にループ上限を設ける。既存 REST の `MAX_PAGES = 20`（`GitLabApiClient.kt:290`）に倣い、それぞれ上限に達したら以下を行う。

- `logger.warn` に打ち切りを記録（件数のみ。本文は出さない）
- ツリーに「(truncated — see GitLab for the full discussion)」ノードを追加

**打ち切りを黙って行わない**ことを要件とする（NFR-6）。

---

## 9. 失敗コメントの再試行・取消（F5）

### 9.1 VSCode の実装と、その前提

VSCode の `gl.retryFailedComment` / `gl.cancelFailedComment` は Comments API の楽観 UI に由来する。送信が失敗すると、入力されたテキストを `FAILED_COMMENT_CONTEXT` を持つプレースホルダのコメントとしてスレッドウィジェットに残し（`src/desktop/commands/mr_discussion_commands.ts:60-70`）、Retry は同じテキストで再送、Cancel はスレッドウィジェットを破棄して下書きを捨てる（同 :102-105, :133-137）。

### 9.2 本設計での等価物

ツリー + ダイアログの設計に楽観プレースホルダは存在しない。しかし**満たすべき本質的要件は同一である**: ユーザーが入力した本文を、送信失敗によって失わせない。

```
送信失敗
  → CommentInputDialog を再度開く
      初期本文 = 直前に入力された本文（そのまま保持）
      エラー表示 = 失敗理由（サーバ由来のメッセージ、またはジェネリックな文言）
      ボタン = [Retry] / [Cancel]
  → Retry: 同じ本文で §8.2 のフローを再入
  → Cancel: 何もせず閉じる（本文は破棄される。ユーザーの明示的な選択）
```

これにより FR-8 が満たされる。台帳 #7 では F5 を実装済みとして計上する。**この読み替えは設計上の判断であり、レビューで妥当性を確認したい点である。**

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
| L2 GraphQL 実行 | HTTP 200 かつ本文トップレベルに `errors: [...]` | `GraphQlException`。クエリ構文エラー・スキーマ不一致・認可エラーがここに出る |
| L3 mutation ペイロード | HTTP 200、トップレベル `errors` なし、しかし `createNote.errors` 等が非空 | `GraphQlException`。ビジネスロジック上の拒否がここに出る |

VSCode も L3 を明示的に検査している（`gitlab_service.ts:534-536`）。

**L2 の検査には注意点がある。** GraphQL は部分成功を返しうる（`data` と `errors` の両方が非 null）。本設計では**`errors` が非空なら常に失敗として扱う**。理由: 本設計が扱う操作はいずれも部分結果に意味がなく、部分結果を成功として表示するとユーザーを誤認させるため。

### 11.2 ユーザーへの提示

| 状況 | 提示 |
|---|---|
| 読み取り失敗 | 節を失敗ノードに置換 + 「see the Error Log」 |
| 書き込み失敗（本文入力を伴うもの） | §9 の再試行ダイアログ |
| 書き込み失敗（解決切替・削除など本文を伴わないもの） | 通知（`NotificationUtils.show`）。ユーザーは操作を再実行すればよい |
| 同一インスタンス不一致 | 「表示元と現在の接続先が異なる」旨の通知。HTTP は発行しない |
| 権限不足 | メニューに出さない（FR-9）。実行時に判明した場合は L3 エラーとして通知 |

### 11.3 ログと監査

Phase 4 の Codex レビューで確定した規律を最初から適用する。

- **例外オブジェクト本体をログに添付しない。** 監査行には `exceptionType=<クラス名>` のみを付す。
  理由: Phase 4 PR-4 の Codex 指摘 P1-1 で、`IllegalArgumentException` のメッセージに `Bearer <token>` が含まれ Error Log に漏洩する経路が実在した。同型の漏洩を構造的に防ぐ。
- **コメント本文・GraphQL 変数・リクエストボディをログに出さない。** 件数や種別のみ。
- **打ち切り・失敗の件数は出す**（`errorCount=` 等）。Phase 4 PR-4 のブランチ全体レビュー I-1 と同じ方針。

---

## 12. タイムアウト・リトライ・冪等性

### 12.1 タイムアウト

既存 REST と同じ 30 秒（`GitLabApiClient.REQUEST_TIMEOUT_SECONDS`、`GitLabApiClient.kt:292`）を GraphQL にも適用する。ページングループ全体に対する上限は、既存 `fetchListWithinDeadline`（同 :112-143）と同型の deadline を設ける。

**未決**: ページング全体の deadline を PR-1 で入れるか、単発タイムアウトのみで足りるとするか（§16 U-6）。

### 12.2 リトライ

**自動リトライは行わない。** すべての書き込みが副作用を持ち、GraphQL mutation に冪等キーがないため、自動再送は二重投稿を起こしうる。再試行は §9 のとおり**ユーザーの明示的な操作**に限る。

読み取りについても自動リトライは行わない（ユーザーが節を再展開すればよい）。

### 12.3 冪等性

| 操作 | 冪等か | 対策 |
|---|---|---|
| `createNote` / `createDiffNote` | **冪等でない**（二重送信で重複コメント） | in-flight ガード + 自動リトライ禁止 |
| `discussionToggleResolve` | 冪等（`resolved` は目標状態を渡す。トグルではない） | 追加対策不要 |
| `updateNote` | 冪等（同じ body を 2 回送っても結果同一） | §13 の上書き防止が別途必要 |
| `destroyNote` | 2 回目は L3 エラー | エラーを通知して終わり（既に消えているため実害なし） |

`discussionToggleResolve` の引数が `resolve: Boolean`（トグルではなく目標状態）である点は mutation 名から誤解しやすいため、実装時に明示的にコメントを残す。

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

`DiscussionGenerationRegistry` により、UI 反映は以下 3 条件を**すべて**満たす場合のみ行う。

1. `registry.active` が true（プラグインが停止処理に入っていない）
2. `registry.currentEpoch == startEpoch`（停止→再開を跨いでいない）
3. `registry.isLatest(key, gen)`（同一 MR に対するより新しい要求が出ていない）

これらは `asyncExec` の runnable 内で**最初に**評価する（gate-first）。ガード評価と反映が同一 UI ターン内で完結するため、判定後に状態が変わる窓は存在しない。

### 14.3 起動・停止順序

Phase 4 PR-4 の Codex 指摘 P1-2 を最初から織り込む。

- `GitLabEclipseStartup.start` で `DiscussionGenerationRegistry.onActivate()` を UI スレッド上（`syncExec`）で呼ぶ。
- `stop` では `onDeactivate()` を **UI スレッドの `disposeAtShutdown` 系 `syncExec` の先頭**で呼ぶ。bare な `@Volatile` 書き込みにはしない。

理由: bare な volatile 書き込みでは「UI runnable がガードを通過してから実際に反映するまで」と停止が全順序化されず、停止後に通知やダイアログが出る窓（torn window）が残る。Phase 4 で実際に指摘され修正された経路であり、同じ誤りを繰り返さない。

**併せて申し送り**: 既存の `JobLogGenerationRegistry` には `onActivate` 相当がなく非対称のままである（follow-up issue #44）。本設計はその不具合を引き継がない。

### 14.4 多重送信の防止

既存 `InFlightWriteGuard`（`src/main/kotlin/com/gitlab/eclipse/ci/actions/InFlightWriteGuard.kt`）を流用する。キーは（操作種別, 対象 ID）とし、同一スレッドへの返信の二重送信、同一ノートの二重削除などを防ぐ。

---

## 15. 認証・認可 / 既存機能への影響 / 移行・ロールバック

### 15.1 認証

トークンは既存の `GitLabTokenProviderManager` 経由で `GitLabApiClient.captureConnection()` が取得したスナップショットからのみ用いる。`GitLabGraphQlClient` は独自にトークンを読まない。

seqlock により「URL とトークンが同一世代の組であること」が保証される（`GitLabApiClient.kt:172-184` の KDoc を参照）。これにより、設定変更中の torn な `(新 URL, 旧トークン)` の組で送信することがない。

### 15.2 認可

- 表示: `note.userPermissions { resolveNote adminNote createNote }` をノードに保持し、コンテキストメニューの出し分けに使う（`PropertyTester`。既存 `CiActionPropertyTester` と同型）。
- 実行: 権限がないのに実行された場合は L3 エラーとして通知する（UI での抑止は最適化であり、最終的な判断はサーバ側が行う）。

### 15.3 同一インスタンスゲート

**読み取り・書き込みの両方**で、ノードが由来する `instanceUrl` と現在の接続スナップショットの `instanceUrl` を正規化して比較し、不一致なら HTTP を発行しない。Phase 4 で確立した `sameConfiguredInstance` / `normalizeInstanceUrl` を再利用する。

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

### 16.2 未決（レビューで判断したい）

| # | 事項 | 現時点の提案 | 判断が必要な理由 |
|---|---|---|---|
| U-1 | `mergeRequestDiffHeadSha` 引数は GitLab 14.9 以降でのみ有効。VSCode はインスタンスバージョンで新旧 mutation を切り替えている（`gitlab_service.ts:522-527`）。本プラグインには**インスタンスバージョン検出機構が存在しない** | 新版 mutation のみを使い、旧版フォールバックを持たない。14.9 は 2022-03 リリースであり、Duo を前提とする本プラグインの実質的な下限を下回る | 誤っていれば古いインスタンスで `CreateNote` が全滅する。バージョン検出を新設するかどうかの判断でもある |
| U-2 | 上記に関連し、VSCode は `validateVersion('MR Discussions', REQUIRED_VERSIONS.MR_DISCUSSIONS)` を全操作の冒頭で呼んでいる | 移植しない（U-1 と同じ理由） | 同上 |
| U-3 | `system: true` のノートを除外する方針 | 除外する（レビュー用途でノイズになる） | 除外すると「alice が assignee を変更」等の履歴が見えなくなる。それが許容されるかは利用者判断 |
| U-4 | ディスカッションの取得タイミング | 節の展開時に遅延取得。自動更新なし | MR ノードの展開時に先読みする案もある。応答性とリクエスト数のトレードオフ |
| U-5 | クエリで要求するフィールドの絞り込み。`avatarUrl` / `bodyHtml` は不要（§2.2）だが、`author.name` / `author.webUrl` / `note.url` も不要か | `username` / `body` / `createdAt` / `id` / `system` / `userPermissions` / `position` のみ要求し、他は落とす | 落としすぎると後で機能追加時にクエリ変更が必要になる。過剰に取ると応答が重くなる |
| U-6 | ページングループ全体の deadline を設けるか | 単発 30 秒 + ループ上限 20 で足りるとする | 最悪 20 ページ × 30 秒 = 10 分理論値。UI は background なのでブロックしないが、ユーザーから見て終わらない |
| U-7 | `ThreadNode` / `NoteNode` に複製して持たせる識別子の範囲（§7.4） | 書き込みに必要な最小集合（instanceUrl / projectId / mrIid / mrGid / mrSha / namespaceWithPath / replyId / noteId） | 複製が多いとノード生成コストとメモリが増え、少ないとハンドラが親を辿れず失敗する |
| U-8 | §9 の「失敗コメント再試行」の読み替えを、台帳 #7 で F5 実装済みとして計上してよいか | 計上する（本質的要件 FR-8 を満たすため） | パリティ計数の一貫性に関わる。UI 機構が異なるため、厳密には同一機能ではない |
| U-9 | 台帳 #7 の D9 の記述誤り（§3.2） | 本設計の範囲外。Phase 5B 着手時、または独立に訂正する | 本設計で訂正すると設計書がコミット対象になるため、issue 側で行う必要がある |

---

## 17. 想定されるリスク

| # | リスク | 影響 | 緩和策 |
|---|---|---|---|
| R-1 | **GraphQL クエリがインスタンスのスキーマと整合しない。** headless 環境では実接続で検証できないため、クエリ文字列の誤りは CI もテストも検出できず、ユーザー実機で初めて露見する | 機能が全滅する。手戻りが最大 | クエリ/変数を持つコンポーネントは最上位モデル（`fable`）に割り当て、**読んだ実ソースのパス/行を根拠として成果物に併記させる**（記憶からの捏造防止）。加えて §10 の確定値を実装計画に埋め込み、実装者に再導出させない |
| R-2 | L2/L3 のエラー検査漏れにより、失敗が成功として表示される | ユーザーがコメントできたと誤認し、実際には投稿されていない | 3 層すべての検査を単体テストで実証する（各層について「失敗が例外になる」テストを個別に持つ） |
| R-3 | 同一インスタンスゲートの評価が HTTP 送信より後になり、他インスタンスにトークンが送られる | 資格情報の漏洩 | Phase 4 と同じく「ゲート不成立時は API 呼び出し回数 0」をカウンタで実証するテストを置く |
| R-4 | 監査ログ経由でのトークン・コメント本文の漏洩 | 情報漏洩 | §11.3 の規律。加えて Phase 4 PR-4 と同様、「ログ出力に本文由来のマーカー文字列が含まれない」ことをテストで実証する |
| R-5 | ツリーのノード数がスレッド数 × ノート数で増大し、描画が重くなる | UI 応答性の劣化 | ページング上限による有界化 + 打ち切り表示（§8.5） |
| R-6 | 編集の TOCTOU 窓（§13） | 他者の編集を上書きする | 窓を狭める事前確認を実装。閉じられないことを既知の制限として明記 |
| R-7 | 二重送信による重複コメント | ユーザーから見て不快、削除の手間 | in-flight ガード + 自動リトライ禁止（§12.2） |
| R-8 | 停止処理と UI 反映の競合により、停止後に通知やダイアログが出る | 例外・ゴースト UI | §14.3 の停止順序を最初から適用 |

---

## 18. テスト方針

### 18.1 単体テスト（自動）

| 対象 | 検証内容 |
|---|---|
| `GitLabGraphQlClient` | URI 組み立て（`/api/graphql` であること、トレイリングスラッシュ処理）／L1 非 2xx → 例外／L2 `errors` 非空 → 例外／接続 pin（URL・トークンがスナップショット由来であること）／タイムアウト伝播／リクエスト本文が `{"query","variables"}` 形であること |
| `DiscussionService` | クエリ変数の組み立て（`iid` が文字列であること）／GID 組み立て（`id` を使い `iid` を使わないこと）／`namespaceWithPath` の導出（`#` と `!` の両方）／L3 ペイロード `errors` 非空 → 例外／DTO 正規化（null フィールドの既定値）／`system` ノート除外／`positionType` 判別／二重ページングと上限打ち切り |
| `DiscussionGenerationRegistry` | `CiLintGenerationRegistryTest` と同等の 13 ケース（完了順逆転・per-key 独立・停止区間・epoch・ABA 回避） |
| 同一インスタンスゲート | 不一致時に API 呼び出し回数が 0 であること（カウンタで実証） |
| 監査ログ | 本文・トークン由来のマーカー文字列がログ行に含まれないこと（識別可能なマーカーを使う。Phase 4 PR-4 の指摘を踏まえ、引用符付きの弱い検証にしない） |
| 位置情報 | `DiffPositionInput` の組み立て（`newLine` のみ、`oldLine` 不在） |

### 18.2 実機のみで確認する項目（PR 説明文のチェックリスト）

- Discussions 節の展開・ツリー描画・truncated 表示
- コンテキストメニューの出し分け（権限・`resolvable` による）
- `CommentInputDialog` の複数行入力・事前充填・Retry/Cancel
- エディタ右クリックからの新規 diff スレッド（G1〜G7 の各ゲート）
- 実 GitLab インスタンスに対する全 mutation の成功
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
2. `DiscussionService.getDiscussions` が二重ページングを行い、上限で打ち切り、警告を記録する。
3. サイドバーの MR ノード配下に Discussions 節が出る。展開時に遅延取得する。
4. スレッドが `path:line` または `(overall)` のラベルで、解決状態とともに表示される。
5. `system` ノートが表示されない。
6. 取得失敗時に失敗ノードが表示され、Error Log に本文・トークンを含まない記録が残る。
7. 同一インスタンス不一致時に HTTP が発行されない。
8. 検証バー（§18.3）を満たす。

### PR-2（書き込み）

1. スレッドへの返信ができる（FR-2）。
2. MR 全体コメントを作成できる（FR-3）。
3. 解決可能なスレッドを解決・未解決にできる（FR-5）。
4. ノートを編集できる。§13 の事前確認により、表示後に変更されたノートの編集は拒否される（FR-6）。
5. ノートを確認ダイアログを経て削除できる（FR-7）。
6. 権限のない操作がメニューに出ない（FR-9）。
7. 送信失敗時、本文を保持したダイアログが Retry / Cancel とともに再提示される（FR-8）。
8. 同一操作の二重送信が in-flight ガードで抑止される。
9. 書き込み成功後、当該 MR の Discussions 節のみが再取得される（FR-10）。
10. 検証バー（§18.3）を満たす。

### PR-3（エディタ行からの新規 diff スレッド）

1. MR ブランチをチェックアウトした状態で、エディタ右クリックから行コメントを作成できる（FR-4）。
2. G4〜G7 の各ゲート不成立時に、既存機能と同一文言で拒否される。
3. `DiffPositionInput` が `newLine` のみを含み、3 つの sha が MR の diff version 由来である。
4. old 側へのコメント作成が対象外であることが PR に明記される。
5. 検証バー（§18.3）を満たす。

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
