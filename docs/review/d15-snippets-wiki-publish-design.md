# D15 Snippets / Wiki / Publish / Repo 設計書

- 対象 issue: #14(Phase 6 残余)/ パリティ台帳 #7 の **D15(5 行)**
- ベースブランチ: `gitlab-ls-9.3.0` @ `c2bee0b`(同梱 gitlab-lsp 9.3.0)
- 参照実装: `gitlab-workflow` v6.85.3(リポジトリ外 `./out/gitlab-vscode-extension`・読み取り専用)
- 本書はレビュー専用。実装 PR およびマージ先ブランチには含めない。

---

## 1. 背景と目的

パリティ台帳 #7 の D15 は **5 行すべてが未実装**で、Phase 6 に残る最大の塊のひとつ。本設計は D15 を
一括で対象とし、VSCode 拡張の 8 コマンド相当を Eclipse に実装するための構成を定める。

Phase 2〜5 で以下の基盤が揃っており、D15 はその上の薄い層として組める見込みが立ったことが着手理由。

- REST: `GitLabApiClient`(接続 seqlock・same-instance ゲート)+ `api/*Service.kt` 群
- GraphQL: `GitLabGraphQlClient`(#46)
- **認証付き git トランスポート: `GitAuthConfigurer.applyAuth(command, instanceUrl)`**(#32・§7.1)
- git 直列化: `GitOperationGuard`
- プロジェクト解決: `GitLabProjectUrlResolver`(A案・ローカルのみ)/ `WorkspaceProjectPicker`

## 2. 対象範囲

| 台帳の行 | VSCode command | 本設計での実現 |
|---|---|---|
| スニペット作成/挿入 | `gl.createSnippet` / `gl.insertSnippet` | REST 作成 + GraphQL 一覧/内容 + エディタ挿入 |
| スニペットパッチ作成/適用 | `gl.createSnippetPatch` / `gl.applySnippetPatch` | JGit diff → スニペット化 / スニペット → JGit `ApplyCommand` |
| Wiki クローン | `gl.cloneWiki` | JGit `CloneCommand`(`.wiki.git`)+ Eclipse プロジェクト取り込み |
| GitLab へ発行 | `gl.publishToGitLab` | プロジェクト作成 API + remote 追加 + push |
| リポジトリを開く/プロジェクト選択 | `gl.openRepository` / `gl.selectProject` | **`openRepository` はクローンとして再定義**(下記 §4)/ 割当の永続ストア |

## 3. 対象外(意図的に実装しない)

| 項目 | 理由 |
|---|---|
| スニペットの編集・削除 | VSCode 拡張にも無い(パリティ対象外) |
| 個人スニペット(`/snippets`) | VSCode はプロジェクトスニペットのみ扱う |
| 多アカウント選択(`pickAccount`) | 本プラグインは**単一アカウント設計**。VSCode の `pickAccount` は「設定済みインスタンス 1 件」に読み替える。多アカウント化は D1 の課題として分離 |
| `gitlab-remote://` 仮想ファイルシステム | Eclipse に相当機能が無く、EFS プロバイダの自前実装は独立した大基盤になる(§4 で決定を記録) |
| スニペットのページング全走査 | 初版は先頭ページのみ。`pageInfo.hasNextPage` は取得して**打ち切りを明示**する(§11) |

## 4. 現在の課題と、`openRepository` に関する決定

VSCode の `gl.openRepository` は `GitLabRemoteFileSystem`(スキーム `gitlab-remote://`)によって
**クローンせずに**リモートリポジトリをワークスペースとして開く。Eclipse には相当機能が無い。

**決定(ユーザー判断・2026-08-12)**: `openRepository` は **「プロジェクトを選んでクローンし、
ワークスペースに取り込む」** に再定義する。`cloneWiki` と実装基盤を共有できるため。

**この決定の帰結(レビュー時に検証してほしい点)**:

- VSCode との**挙動差**が生じる。VSCode は書き込み可能な仮想 FS、本設計はローカルクローン。
- ディスク消費と初回時間が VSCode 版と大きく異なる。
- 台帳 #7 の D15 該当行には「Eclipse ではクローン方式」と注記が必要。

## 5. 要件

### 5.1 機能要件

| ID | 要件 |
|---|---|
| F1 | `createSnippet`: アクティブエディタの**全文**または**選択範囲**を、可視性(private/public)を選んでプロジェクトスニペットとして作成し、作成後に `web_url` をブラウザで開く |
| F2 | `insertSnippet`: プロジェクトスニペット一覧から 1 件、blob が複数なら 1 件を選び、**アクティブエディタのカーソル位置**に内容を挿入する |
| F3 | `createSnippetPatch`: 作業ツリーの diff を取得し、名前と可視性を入力させ、`<名前>.patch` としてスニペット化する。説明文に適用手順を含める |
| F4 | `applySnippetPatch`: `.patch` で終わる blob を持つスニペットのみを一覧し、選択したパッチを作業ツリーに適用する |
| F5 | `cloneWiki`: プロジェクトを選び、その **wiki URL**(`.git` → `.wiki.git`)をクローンする |
| F6 | `publishToGitLab`: 選んだワークスペースプロジェクトについて、GitLab に新規プロジェクトを作成し、remote を追加して push する |
| F7 | `openRepository`: プロジェクトを選び、クローンしてワークスペースに取り込む(§4 の再定義) |
| F8 | `selectProject`: リポジトリの remote URL に GitLab プロジェクトを**手動で割り当て、永続化**する。以後のプロジェクト解決は割当を優先する |

### 5.2 非機能要件

| ID | 要件 |
|---|---|
| N1 | 新規 OSGi バンドル依存を**追加しない**(JGit 7.5.0 は依存済み。**EGit UI は使わない**) |
| N2 | ディレクトリ構成・ビルドシステムを変更しない |
| N3 | ログに URI・ファイルパス・トークンを出さない(例外は**種別のみ**) |
| N4 | SWT/UI に依存しない層をテスト可能な形で分離する |
| N5 | 破壊的操作(作業ツリー書き換え・push・ディレクトリ作成)は**前提を満たさなければ実行前に中止**する |

## 6. 前提条件と制約

- Eclipse 4.33 以降 / JDK 21 / JGit 7.5.0(依存済み)。
- GitLab インスタンス URL とトークンが設定済みであること。未設定時は既存の Warn 文言に合わせる。
- スニペット系はいずれも**プロジェクト文脈**を必要とする(VSCode の `ProjectCommand` 相当)。
  文脈は既存 `GitLabProjectUrlResolver` / `WorkspaceProjectPicker` から得る。
- `createSnippet` の REST は**数値プロジェクト ID** を必要とする(`/projects/{restId}/snippets`)。
  既存の A 案解決は `namespaceWithPath` までしか出さないため、**ID 解決の一手が要る**(§10・未決 U1)。

## 7. システム構成

```
handlers/                     ... 8 ハンドラ(薄い AbstractHandler。UI スレッド入出力のみ)
  ↓
snippets/                     ... SWT フリー: 本文組み立て / パッチ生成 / パッチ適用
repository/                   ... SWT フリー: クローン / 発行(JGit)
navigation/                   ... SelectedProjectStore + AssignedProjectResolver(デコレータ)
  ↓
api/  SnippetService / ProjectCreateService / NamespaceService
  ↓
GitLabApiClient(REST) / GitLabGraphQlClient(GraphQL) / GitAuthConfigurer(git transport)
```

新規パッケージは `com.gitlab.eclipse.snippets` と `com.gitlab.eclipse.repository` の 2 つ。
いずれも既存 `com.gitlab.eclipse.*` 体系内への追加であり、ディレクトリ構成の変更には当たらない。

## 8. コンポーネントの責務

| コンポーネント | 責務 | SWT 依存 |
|---|---|---|
| `api/SnippetService` | スニペットの作成(REST)・一覧(GraphQL)・blob 内容取得(GraphQL) | なし |
| `api/ProjectCreateService` | プロジェクト新規作成(REST) | なし |
| `api/NamespaceService` | 名前空間の解決(発行先の選択に使う) | なし |
| `snippets/SnippetContentBuilder` | ファイル名・タイトル・可視性・本文の組み立て(全文/選択範囲の切り分けは**入力として受け取る**) | なし |
| `snippets/SnippetPatchBuilder` | JGit で作業ツリー diff を生成し、説明文を組む | なし |
| `snippets/SnippetPatchApplier` | パッチ本文を作業ツリーへ適用(JGit `ApplyCommand`)。**適用前ガード**を持つ | なし |
| `repository/RepositoryCloneService` | JGit `CloneCommand` + Eclipse プロジェクト取り込み | なし |
| `repository/RepositoryPublishService` | プロジェクト作成 → remote 追加 → push | なし |
| `navigation/SelectedProjectStore` | 割当(リポジトリ + remote URL → プロジェクト)の永続 | なし |
| `navigation/AssignedProjectResolver` | 既存 resolver を包み、割当があればそれを返す | なし |
| `handlers/*Handler` | アクティブエディタ・選択範囲の読み取り、ダイアログ、結果の反映 | **あり** |

## 9. 処理フロー

### 9.1 `createSnippet`(F1)

1. **[UI]** アクティブエディタを取得。無ければ「開いているファイルがありません」で終了。
2. **[UI]** 可視性を選択(`Private` 既定先頭 / `Public`)。キャンセルで終了。
3. **[UI]** ソースを選択(`Snippet from file` / `Snippet from selection`)。キャンセルで終了。
4. **[UI]** 選択に応じて本文と**ファイル名**(パスの最終要素)を読み取る。
   選択範囲の場合、VSCode は**選択終端の次の行頭まで**を含める(行単位に丸める)。同挙動とする。
5. **[背景]** プロジェクト文脈を解決 → 数値 ID を解決(§10・U1)。
6. **[背景]** `POST /projects/{id}/snippets` に `{title, file_name, visibility, content}` を送る。
7. **[UI]** 応答の `web_url` を既存 `BrowserLauncher` で開く。

### 9.2 `insertSnippet`(F2)

1. **[UI]** アクティブエディタが無ければ終了。
2. **[背景]** GraphQL でプロジェクトスニペット一覧を取得。0 件なら通知して終了。
3. **[UI]** スニペットを選択 → blob が複数なら blob も選択。
4. **[背景]** GraphQL で `rawPlainData` を取得。
5. **[UI]** カーソル位置に挿入。

### 9.3 `createSnippetPatch`(F3)

1. **[背景]** 対象リポジトリを解決。**HEAD コミットが無ければ中止**(VSCode も `assert`)。
2. **[背景]** 作業ツリー diff を生成。空なら「変更がありません」で終了。
3. **[UI]** パッチ名を入力(タイトルとファイル名に使う)。空/キャンセルで終了。
4. **[UI]** 可視性を選択。
5. **[背景]** タイトル `patch: <名前>` / ファイル名 `<名前>.patch` / 説明文(適用手順 + コミット記述子)で作成。
6. **[UI]** `web_url` をブラウザで開く。

### 9.4 `applySnippetPatch`(F4)

1. **[UI]** **対象リポジトリ配下に未保存のエディタがあれば、アラートを出して保存を促し、中止する**
   (ユーザー判断・2026-08-12。自動保存はしない)。
2. **[背景]** GraphQL で一覧取得 → `.patch` で終わる blob を持つものだけに絞る。0 件なら通知して終了。
3. **[UI]** スニペットを選択。
4. **[背景]** 内容を取得し、`GitOperationGuard` の下で JGit `ApplyCommand` を実行。
5. **[背景→UI]** 成功したら **`IResource.refreshLocal(DEPTH_INFINITE)`** を実行(JGit はディスクを
   直接書くため、Eclipse のリソースツリーが古いままになる)。

### 9.5 `cloneWiki` / `openRepository`(F5 / F7)

1. **[背景]** プロジェクト候補を取得。**[UI]** プロジェクトを選択。
2. **[UI]** `cloneWiki` は URL を `.git` → `.wiki.git` に変換した候補から選択、`openRepository` は
   そのままの URL 候補(SSH / HTTPS)から選択。
3. **[UI]** クローン先ディレクトリを選択。**空でなければ中止**。
4. **[背景]** `GitAuthConfigurer.applyAuth` を通した `CloneCommand` を実行。
5. **[背景→UI]** クローン先を Eclipse プロジェクトとして取り込む。

### 9.6 `publishToGitLab`(F6)

1. **[UI]** ワークスペースプロジェクトを選択(複数時)。
2. **[背景]** 対象がリポジトリでなければ `init`。**既に remote が 1 つでもあれば中止**
   (ユーザー判断・2026-08-12。誤爆防止)。
3. **[UI]** 名前空間・プロジェクト名・可視性・接続方式(SSH/HTTPS)を入力。
4. **[背景]** `POST /projects` に `{path, namespace_id, visibility}`。
5. **[背景]** remote を追加し、現在のブランチを push(upstream 設定つき)。
6. **[UI]** 結果を通知。

### 9.7 `selectProject`(F8)

1. **[UI]** リポジトリを選択(複数時)→ remote URL を選択(複数時)。
2. **[背景]** プロジェクト候補を取得。**[UI]** プロジェクトを選択。
3. **[背景]** `SelectedProjectStore` に保存。
4. **[UI]** 割当内容を通知。

## 10. API / インターフェース(実ソースで確定した定数)

参照実装から確定済み。実装時に再調査しないこと。

### 10.1 REST

| 用途 | メソッド / パス | ボディ | 応答 |
|---|---|---|---|
| スニペット作成 | `POST /projects/{restId}/snippets` | `{title, file_name, visibility, content}`(パッチは `description` を追加) | `{web_url}` |
| プロジェクト作成 | `POST /projects` | `{path, namespace_id?, visibility}` | `RestProject`(`ssh_url_to_repo` / `http_url_to_repo`) |

出典: `src/desktop/gitlab/gitlab_service.ts:654-663` / `src/desktop/gitlab/api/create_project.ts`

### 10.2 GraphQL

スニペット一覧(出典 `src/desktop/gitlab/graphql/get_snippets.ts`):

```graphql
query GetSnippets($namespaceWithPath: ID!, $afterCursor: String) {
  project(fullPath: $namespaceWithPath) {
    id
    snippets(after: $afterCursor) {
      pageInfo { hasNextPage endCursor }
      nodes {
        id
        title
        description
        blobs { nodes { name path rawPath } }
      }
    }
  }
}
```

スニペット内容(出典 `src/desktop/gitlab/graphql/get_snippet_content.ts`):

```graphql
query GetSnippetContent($snippetId: SnippetID!) {
  snippets(ids: [$snippetId]) {
    nodes { blobs { nodes { path rawPlainData } } }
  }
}
```

### 10.3 定数

| 定数 | 値 | 出典 |
|---|---|---|
| パッチのタイトル接頭辞 | `patch: ` | `src/desktop/constants.ts:23` |
| パッチのファイル名接尾辞 | `.patch` | `src/desktop/constants.ts:24` |
| wiki URL 変換 | `url.replace(/\.git$/, '.wiki.git')` | `src/desktop/gitlab/clone/gitlab_remote_source.ts:35-37` |
| 初期コミットメッセージ | `Initial commit` | 同上 `:33` |
| 可視性の選択肢 | `private`(既定先頭)/ `public` | `src/desktop/commands/create_snippet.ts` |

**U1(未決)**: `POST /projects/{restId}/snippets` は**数値 ID** を要求するが、既存の A 案解決は
`namespaceWithPath` までしか出さない。ID 解決の手段を確定する必要がある(§17)。

## 11. データモデル

- `SnippetSummary(id, title, description, blobs: List<SnippetBlob>)`
- `SnippetBlob(name, path, rawPath)`
- `ProjectAssignment(repositoryRootPath, remoteUrl, namespaceWithPath, projectId)`
- `SelectedProjectStore` の永続先は既存 `ScopedPreferenceStore`(**新規の設定ファイルは作らない**)。
  複数割当を 1 キーに収めるため、区切り文字ではなく**構造化した文字列**(JSON 相当)を 1 値に入れる。
  **区切り文字方式は remote URL に任意文字が入りうるため採らない。**
- スニペット一覧は**先頭ページのみ**を扱い、`hasNextPage` が true のときは
  「一部のみ表示しています」を UI に出す(黙って切り捨てない)。

## 12. トランザクション境界

本機能に分散トランザクションは無いが、**部分的に成功して戻せない操作**が 2 つある。

| 操作 | 部分成功のしかた | 扱い |
|---|---|---|
| `publishToGitLab` | プロジェクトは作成されたが push に失敗 | **作成したプロジェクトは削除しない。** 「プロジェクトは作成されましたが push に失敗しました」と明示し、リモート URL を通知する(VSCode と同じ方針) |
| `applySnippetPatch` | パッチが部分適用される | JGit `ApplyCommand` は**全適用か例外か**。失敗時に作業ツリーが半端に書き換わらないことを実装時に検証する(§17 U2) |

## 13. エラー処理

- 既存の `Resolution.Warn(message)` / 通知パターンに揃える。**例外をユーザーに素通ししない。**
- **JGit の例外メッセージはリモート URL を含みうる**ため、そのまま通知・ログに流さない。
  ユーザー向けは定型文、ログは**例外の型名のみ**。
- REST/GraphQL の失敗は既存 `GitLabApiException` / `GraphQlException` の扱いに従う。
- キャンセル(ダイアログを閉じる)は**エラーではない**。無言で終了する。

## 14. タイムアウト・リトライ・冪等性

- **タイムアウト**: REST/GraphQL は既存 `GitLabHttpClient` の設定に従う。**git 操作(clone/push)は
  時間が読めない**ため、`IProgressMonitor` 付きのジョブとして実行し、**ユーザーがキャンセルできる**ようにする。
- **リトライ**: **自動リトライは行わない。** 作成系(スニペット作成・プロジェクト作成)は**冪等ではなく**、
  自動再試行は重複を生む。失敗はユーザーに提示し、再実行の判断を委ねる。
- **冪等性**:
  - スニペット作成・プロジェクト作成: **非冪等**(同名で複数作成されうる)。二重押し防止のため
    実行中はコマンドを無効化する。
  - `applySnippetPatch`: **非冪等**(同じパッチの二度適用は失敗する)。失敗を正常な結果として扱う。
  - `selectProject`: **冪等**(同じキーへの上書き)。

## 15. 並行処理

- git 操作は既存 `GitOperationGuard` で**リポジトリ単位に直列化**する。clone/push/apply すべて対象。
- **UI スレッド境界**: エディタ本文・選択範囲の読み取り、テキスト挿入、ダイアログは**UI スレッド必須**。
  REST/GraphQL/JGit は背景。既存 `WorkspaceProjectPicker` の形
  (**UI で読む → coroutine → 必要なら `asyncExec` で戻る**)を踏襲する。
- 例外がコルーチンから漏れると**共有 plain-Job スコープが道連れで停止する**(#16 で確認済み)。
  すべての `launch` で `CancellationException` は再送出、それ以外は封じ込める。
- `SelectedProjectStore` は複数スレッドから読まれる。読み取りは**スナップショットを返す**。

## 16. 認証・認可 / ログ・監視・監査

- REST/GraphQL は既存 `GitLabApiClient` 経由とし、**`captureConnectionIf` で URL 照合を資格情報の
  読み取りより前に行う**(#49 の教訓)。
- git トランスポートは `GitAuthConfigurer.applyAuth` のみを使う。
  **`SshSessionFactory.setInstance`(プロセス全体)は絶対に呼ばない。**
- **トークンを URL に埋め込まない。**
- 監査ログ: `publishToGitLab`(新規プロジェクト作成)と `applySnippetPatch`(作業ツリー書き換え)は
  **実行した事実**を残す。パス・URL・トークンは載せない。

## 17. 未決事項(推測で確定しない)

| ID | 内容 | 決め方 |
|---|---|---|
| **U1** | スニペット作成に必要な**数値プロジェクト ID** の解決手段。既存 A 案解決は `namespaceWithPath` 止まり | 既存 `ProjectDetailService` に ID を返す経路があるか実装前に確認する。無ければ GraphQL の `project.id`(GID)から REST ID を導出する |
| **U2** | JGit `ApplyCommand` が失敗したとき、作業ツリーが**部分的に書き換わらない**か | 実テスト(一時リポジトリ + 途中で衝突するパッチ)で確認する |
| **U3** | プロジェクト候補の取得方法(検索 API か、ユーザーの参加プロジェクト一覧か)とページング | VSCode の `pickProject` の実装を実ソースで確認して確定する |
| **U4** | クローン後の Eclipse プロジェクト取り込み方法(`.project` がある場合と無い場合) | 実装前に Eclipse API の挙動を確認する |
| **U5** | 「未保存のエディタ」の判定範囲(対象リポジトリ配下に限るか、全ワークスペースか) | §9.4 は「対象リポジトリ配下」を前提としているが、判定コストと確実性のトレードオフを要検討 |

## 18. 既存機能への影響

| 対象 | 影響 |
|---|---|
| `GitLabProjectUrlResolver` | **変更しない。** 割当は `AssignedProjectResolver` デコレータで外側から差し込む(ユーザー判断・2026-08-12)。**ストアが空なら挙動は現行と完全同一** |
| 既存 resolver のテスト | 変更不要 |
| ナビゲーション / MR / CI / ディスカッション / セキュリティ | デコレータを通す消費者のみ割当が効く。**どの消費者を通すかは実装計画で列挙し、段階的に切り替える** |
| `plugin.xml` | コマンド・ハンドラ・メニュー項目の**追加のみ**(削除なし) |
| 依存関係 | **追加なし** |

## 19. 移行方法・ロールバック

- **移行不要**(新規機能のみ。既存データ形式を変えない)。
- `SelectedProjectStore` のキーが存在しない状態が既定であり、**未設定 = 現行挙動**。
- **ロールバック**: 実装 PR を revert すれば足りる。永続データはプラグイン固有の preference キーのみで、
  残存しても他機能は参照しない。

## 20. テスト方針

| 層 | 方針 |
|---|---|
| `SnippetContentBuilder` / `SelectedProjectStore` / `AssignedProjectResolver` | Kotest + MockK で TDD。純ロジック |
| `SnippetPatchBuilder` / `SnippetPatchApplier` / `RepositoryCloneService` | **一時ディレクトリに実リポジトリを作る実テスト**(JGit をモックしない)。パッチ生成→適用の往復を検証 |
| `api/*Service` | HTTP/GraphQL クライアントをモックし、**リクエストの形**(パス・ボディ・クエリ)を固定する |
| ハンドラ / SWT | **headless で検証不能**。配線のみに留め、実機の手動検証手順を PR 本文に記載する |

検証ゲートは既存と同一: 対象テスト PASS + `./gradlew build` の失敗集合が `FAILSET_IDENTICAL (36 failures)` +
detekt が pristine 比で新規 0 + 行長 120 以内。

## 21. 受け入れ条件

| ID | 条件 |
|---|---|
| A1 | F1〜F8 の各コマンドが `plugin.xml` に登録され、メニュー/Quick Access から起動できる |
| A2 | 選択範囲からのスニペット作成が、VSCode と同じ**行単位の丸め**で本文を切り出す |
| A3 | `applySnippetPatch` が、対象リポジトリ配下に未保存エディタがあるとき**アラートを出して中止**し、作業ツリーを変更しない |
| A4 | `applySnippetPatch` 成功後、変更が **Eclipse のリソースツリーに反映**される(refresh 済み) |
| A5 | `publishToGitLab` が、既に remote のあるリポジトリでは**中止**する |
| A6 | クローン系が、空でないディレクトリを指定されたとき**中止**する |
| A7 | `selectProject` の割当が Eclipse 再起動後も保持される |
| A8 | ストアが空のとき、既存のプロジェクト解決の挙動が**変わらない**(既存テストが無改変で通る) |
| A9 | ログに URI・パス・トークンが出ない(例外は型名のみ) |
| A10 | 新規 OSGi 依存が無く、`build.gradle.kts` / `detekt.yml` の差分が無い |

## 22. 想定されるリスク

| リスク | 影響 | 緩和 |
|---|---|---|
| **`applySnippetPatch` が未保存バッファと競合し、変更が失われる** | データ損失 | §9.4 の適用前ガード(未保存なら中止)+ 適用後 refresh。**#66 と同じクラスの問題**であり、#66 の結論と整合させる |
| **`publishToGitLab` が意図しないリポジトリを公開する** | 情報漏洩 | 既存 remote があれば中止。可視性を明示的に選ばせる。既定を private にする |
| 割当ストアの導入で既存のプロジェクト解決が壊れる | 広範囲の回帰 | デコレータ方式。ストアが空なら完全に現行どおり(A8) |
| スニペット一覧の件数が多く、先頭ページで目的のものが出ない | 使い勝手 | 打ち切りを UI に明示(§11)。全ページ走査は別サイクル |
| JGit のエラーメッセージにリモート URL が含まれる | 情報露出 | 定型文に置換。ログは型名のみ(§13) |
| clone/push が長時間ブロックする | UI 凍結 | `IProgressMonitor` 付きジョブ + キャンセル可(§14) |
| D15 を 1 本の PR にすると巨大になる | レビュー困難 | **実装は複数 PR に分割する**(設計は 1 本のまま)。分割単位は実装計画で定める |

## 23. レビューで特に確認してほしい点

1. §4 の `openRepository` 再定義が妥当か。VSCode との挙動差を受け入れられるか。
2. §9.4 の適用前ガードで、データ損失の窓が本当に閉じているか(判定と適用の間の窓を含む)。
3. §12 の `publishToGitLab` 部分成功の扱い(作成済みプロジェクトを削除しない方針)。
4. §15 の UI スレッド境界の切り方に漏れがないか。
5. §17 の未決事項が、実装開始前に決めるべきものとして過不足ないか。
6. §11 のページング打ち切りが、受け入れ可能な制限か。
