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

> **Codex レビュー反映(R1-1 / R1-2)**: 事前判定と適用の間の窓を閉じ、`PatchApplier` が原子的でない
> 前提で復元を設計する。

1. **[UI]** 早期の体験のための**予備チェック**: 対象リポジトリ配下に未保存のエディタがあれば、
   アラートを出して保存を促し、中止する(ユーザー判断・2026-08-12。自動保存はしない)。
   **これは体験上の早期中断であって、安全性の根拠ではない**(下記 5 が根拠)。
2. **[背景]** GraphQL で一覧取得 → `.patch` で終わる blob を持つものだけに絞る。0 件なら通知して終了。
3. **[UI]** スニペットを選択。
4. **[背景]** 内容を取得。
5. **[背景・排他境界の内側]** `GitOperationGuard` を取得したうえで、**適用直前に再検証する**。
   ここが安全性の根拠であり、1 の判定は再利用しない。
   1. パッチが触れる**対象パスを列挙**する(パッチのヘッダから求める)。
   2. **in-core 検証**: `PatchApplier(repo, HEAD ツリー, ObjectInserter)` で適用を試し、
      `Result.getErrors()` が空でなければ**作業ツリーに一切触れずに中止**する。
      JGit 7.5.0 の `PatchApplier.Result` は `getPaths()` / `getTreeId()` / **`getErrors()`** を返す
      = **全適用か例外かではなく、部分適用とエラー収集**の API である(§12)。
   3. 対象パスについて、**dirty 状態と修正スタンプ(および内容ハッシュ)を再確認**する。
      1 の判定以降に編集・保存されていれば**中止**する。
   4. 対象パスの**現内容をスナップショット**する(復元用)。
   5. 作業ツリーへ適用する。**途中で失敗したらスナップショットから復元**する。
6. **[背景→UI]** 成功・復元いずれの場合も **`IResource.refreshLocal(DEPTH_INFINITE)`** を実行する
   (JGit はディスクを直接書くため、Eclipse のリソースツリーが古いままになる)。

**排他境界について(Codex レビュー第 2 巡 R2-1 で全面改訂)**

第 1 巡の反映時に書いた「残る窓で保存が起きても復元により**ユーザーの編集が失われない側に倒れる**」は
**誤りだった**。スナップショット取得後に対象ファイルが保存され、その後に後続ファイルの適用が失敗すると、
**復元が新しい保存内容を古いスナップショットで上書きして破壊する**。さらに適用後 refresh 前の保存は、
JGit が成功していても**パッチ側を上書き**する。検出型だけでは閉じない。

したがって、次の**二重の防御**を設計に組み込む。

1. **排他を適用・復元・refresh まで保持する。** 対象ファイルを覆う `ISchedulingRule` を取得し、
   `IWorkspace.run(...)` の内側で「再検証 → 適用 → 復元判定 → refresh」を実行する。
   Eclipse の保存はワークスペースを経由するため、この区間の保存は待たされる。
   `GitOperationGuard` は**git 操作の直列化専用**であり、この排他の代わりにはならない。
2. **書き込みのたびに stamp/hash を照合する。** 適用時も**復元時も**、書き込む直前に対象ファイルの
   現在の修正スタンプと内容ハッシュが、こちらが記録した値と一致することを確認する。
   一致しなければ**外部変更が入ったものとして、その書き込みを行わない**。
   **外部の変更を上書きするくらいなら、作業ツリーが中間状態のまま残るほうを選ぶ**。
   その場合は「一部のファイルは外部で変更されたため復元しませんでした」と対象件数を通知する
   (パスは出さない — §16)。

1 だけでは Eclipse 外からの書き込み(外部エディタ・他ツール)を止められず、2 だけでは
チェックと書き込みの間の窓が残るため、**両方を要求する**。

### 9.5 `cloneWiki` / `openRepository`(F5 / F7)

1. **[背景]** プロジェクト候補を取得。**[UI]** プロジェクトを選択。
2. **[UI]** `cloneWiki` は URL を `.git` → `.wiki.git` に変換した候補から選択、`openRepository` は
   そのままの URL 候補(SSH / HTTPS)から選択。
3. **[UI]** クローン先ディレクトリを選択。**空でなければ中止**。
4. **[背景]** `GitAuthConfigurer.applyAuth` を通した `CloneCommand` を実行。
5. **[背景→UI]** クローン先を Eclipse プロジェクトとして取り込む。

### 9.6 `publishToGitLab`(F6)

> **Codex レビュー反映(R1-4 / R1-5 / R1-6 / R2-1)**: 破壊的操作をすべてユーザー入力の**後**に寄せ、
> 初期コミットを定義し、push の成否を `RemoteRefUpdate.Status` で判定し、push 失敗から再開できるようにする。

**フェーズ 0 — 前提の判定(読み取りのみ。ファイルシステムを変更しない)**

1. **[UI]** ワークスペースプロジェクトを選択(複数時)。
2. **[背景]** 対象の状態を判定する。**この時点では `init` しない**(R2-1: キャンセルしただけで `.git` が
   残るのは N5 違反)。
   - 既に remote がある → **原則中止**。ただし**再開条件**(下記フェーズ 3)に合致する場合のみ続行。
   - リポジトリだが **detached HEAD** → 中止(push すべきブランチが定まらない)。
   - リポジトリでもディレクトリでもない、または**追跡対象になりうるファイルが 0 件** → 中止。

**フェーズ 1 — 入力(まだファイルシステムを変更しない)**

3. **[UI]** 名前空間・プロジェクト名・**可視性(既定 private)**・接続方式(SSH/HTTPS)を入力。
4. **[UI]** **最終確認**。非リポジトリの場合は「`git init` して**全ファイルを初期コミットする**」ことと、
   **コミット対象のファイル数**を明示する。`.gitignore` が無い場合は
   **「除外設定が無いためすべてのファイルが公開されます」と警告**する(秘密情報の混入防止)。
   ここまでのキャンセルでは**ファイルシステムは一切変化していない**。

**フェーズ 2 — ローカル準備(ここから破壊的)**

5. **[背景]** 非リポジトリなら `init` → `AddCommand.addFilepattern(".")`(**`.gitignore` を尊重する**)
   → `Initial commit` をコミットする。既存リポジトリで**コミットが 0 件**の場合も同様に初期コミットを作る。

**フェーズ 3 — リモート作成と push**

6. **[背景・R2-3]** **`POST /projects` を出す前に `PublishIntent` を永続化する**
   (リポジトリ識別子・名前空間・パス)。POST 成功後に記録する順序では、**応答受信後のプロセス終了、
   preference 保存失敗、応答断**のいずれかで「GitLab にプロジェクトはあるが記録も remote も無い」状態になり、
   次回は再開条件に入らず新規作成へ進んで**プロジェクトが孤立し、二重作成もできてしまう**。
7. **[背景]** `POST /projects` に `{path, namespace_id, visibility}`。
8. **[背景]** 応答を受けて `PublishIntent` を **`PublishState` へ確定遷移**させる(§11)。
   **intent の破棄と state の書き込みは同一のロック下で一度に行う**(中間状態を残さない)。
9. **[背景]** remote を追加し、現在のブランチを push する。
   **成否は `call()` が返ったことではなく、既存 `BranchPushService.doPush` と同じ判定による**
   (R1-6): JGit は protected branch や non-fast-forward を例外にせず `PushResult` の
   `RemoteRefUpdate.Status` で返す。**全 transport の対象 ref が `OK` または `UP_TO_DATE` の場合のみ成功**とし、
   **upstream 設定はその後に行う**。判定ロジックは新規に書かず既存実装を共有する。
9. **[UI]** 結果を通知。

**再開(R1-5 / R2-3 / R2-4)**: push だけが失敗した場合、作成済みプロジェクトと追加済み remote は
**残す**(§12)。再実行時、フェーズ 0 の「remote があれば中止」に**例外**を設ける —
**確定済み `PublishState` が存在し、既存 remote がその記録と一致する場合に限り**、
**プロジェクトを作らずに push だけを再試行**する。一致しなければ従来どおり中止する。
成功したら記録を破棄する。

一致判定は **`remoteName` と `projectWebUrl` では成立しない**(R2-4)。特に SSH を選んだ場合、
web URL から SSH の user / host / port を一意に復元できず、緩いパス比較は取り違えを招く。
したがって `PublishState` には**実際に追加した remote URL を正規化して保存**し、
`instanceUrl` と **GitLab の project ID** も併せて保存する(§11)。判定はこの正規化 URL の一致で行う。

**未確定 intent の回復(R2-3)**: 再実行時に `PublishIntent` が残っていた場合、
記録された名前空間とパスで**プロジェクトの存在を照会**する。
- 存在する → 前回の POST は成功していたと判断し、**新規作成せず** remote 追加と push から再開する。
- 存在しない → intent を破棄し、通常のフローを続行する。

**冪等性の根拠**: この照会があるため、POST の応答が失われても同じプロジェクトが二重に作られない。

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
- **`ProjectAssignment(repositoryRootPath, remoteUrl, instanceUrl, namespaceWithPath, projectId)`**
- **`PublishIntent(repositoryRootPath, namespacePath, projectPath)`** — `POST /projects` の**前**に永続化し、
  応答受領で `PublishState` へ確定遷移する(§9.6・R2-3)。残存していれば未確定として回復処理に入る。
- **`PublishState(repositoryRootPath, remoteName, normalizedRemoteUrl, instanceUrl, projectId, projectWebUrl)`**
  — §9.6 の再開判定に使う。成功で破棄。
  **`normalizedRemoteUrl` / `instanceUrl` / `projectId` は再開の一致判定に必須**(R2-4)。
  `projectWebUrl` は通知用であって判定には使わない — SSH remote を web URL から復元できないため。
- `SelectedProjectStore` の永続先は既存 `ScopedPreferenceStore`(**新規の設定ファイルは作らない**)。
  複数割当を 1 キーに収めるため、区切り文字ではなく**構造化した文字列**(JSON 相当)を 1 値に入れる。
  **区切り文字方式は remote URL に任意文字が入りうるため採らない。**

### 11.1 割当のインスタンス束縛(Codex レビュー反映 R1-3)

**`instanceUrl` は必須**。これが無いと、インスタンス A で保存した割当が、設定を B に変えた後も
そのまま使われる。デコレータは既存 `GitLabProjectUrlResolver` の**前**に立つため、
resolver が行う「remote と設定インスタンスの照合」を**迂回してしまう**。その状態で
`namespaceWithPath` / `projectId` を B に対して使うと、**同名・同 ID の別プロジェクトへ書き込みが向かう**
(スニペット作成・プロジェクト作成など)。

したがって `AssignedProjectResolver` は、割当を採用する前に**次の 2 つを検証し、いずれかが
一致しなければその割当を無効として既存 resolver に委譲する**:

1. 割当の `instanceUrl` が**現在の接続インスタンスと一致**する(正規化して比較。
   既存 `GitAuthConfigurer.hostMatchesInstance` と同じ「scheme + host + 実効ポート」の基準を使う)。
2. 割当の `remoteUrl` が**対象リポジトリに現存する remote と一致**する。
3. **(R2-4 で追加)割当の `remoteUrl` 自身が、その `instanceUrl` のインスタンスに属する。**

**条件 3 が無いと穴が残る**(Codex レビュー第 2 巡 R2-5): 1 と 2 だけでは
「保存された `instanceUrl` が現在の接続と一致」かつ「その remote が現存する」しか見ておらず、
**remote が別インスタンス A や GitHub のものであっても両条件を通過する**。その状態で
デコレータが当該リポジトリを B 上の割当プロジェクトとして解決すると、**書き込みが誤送信される**。

条件 3 の判定は**両方のリモート形式**を扱う。

- **HTTPS**: `hostMatchesInstance(remoteUrl, instanceUrl)` をそのまま使う(scheme + host + 実効ポート)。
- **SSH / scp-like**(`ssh://user@host:port/path` / `git@host:path`): **host(と明示ポート)を取り出し、
  `instanceUrl` の host と照合**する。scheme は比較対象にしない(SSH と HTTPS で必ず異なるため)。
  この照合は既存 `GitLabRemoteParser` が remote から host を取り出す実装と基準を揃える。

無効化は**黙って行わず**、解決結果の Warn として「割当が現在のインスタンスと一致しないため使用しません」を返す。
- スニペット一覧は**先頭ページのみ**を扱い、`hasNextPage` が true のときは
  「一部のみ表示しています」を UI に出す(黙って切り捨てない)。

## 12. トランザクション境界

本機能に分散トランザクションは無いが、**部分的に成功して戻せない操作**が 2 つある。

| 操作 | 部分成功のしかた | 扱い |
|---|---|---|
| `publishToGitLab` | プロジェクトは作成されたが push に失敗 | **作成したプロジェクトも remote も削除しない。** 「プロジェクトは作成されましたが push に失敗しました」と明示し、リモート URL を通知する。**`PublishState` を残し、次回実行で push だけを再開する**(§9.6・R1-5) |
| `applySnippetPatch` | パッチが部分適用される | **`PatchApplier` は原子的ではない**(下記)。in-core 事前検証 + スナップショット復元で対処する(§9.4) |

### 12.1 `PatchApplier` の非原子性(Codex レビュー反映 R1-1・実バイナリで確認)

当初 §12 に書いていた「JGit `ApplyCommand` は全適用か例外か」という前提は**誤り**だった。
同梱される JGit 7.5.0(`org.eclipse.jgit-7.5.0.202512021534-r.jar`)の API を確認した結果:

```
public class org.eclipse.jgit.patch.PatchApplier {
  public PatchApplier(Repository);
  public PatchApplier(Repository, RevTree, ObjectInserter);   // ← in-core(作業ツリーに触れない)
  public PatchApplier$Result applyPatch(Patch) throws IOException;
}
public class org.eclipse.jgit.patch.PatchApplier$Result {
  public List<String> getPaths();
  public ObjectId getTreeId();
  public List<PatchApplier$Result$Error> getErrors();          // ← エラーは「収集」される
}
```

**結果型がエラーの一覧を返す時点で、「全適用か例外か」ではない。** 複数ファイルのパッチで
後続ファイルが衝突・I/O エラーになっても、先行ファイルへの書き込みは既に済んでいる。

**したがって、これを実装時の確認事項(旧 U2)にはせず、設計上の前提として扱う。** 対策は §9.4 のとおり:

1. **in-core コンストラクタ**(`Repository, RevTree, ObjectInserter`)で**先に検証**する。
   このモードは作業ツリーを一切書き換えない。`getErrors()` が空でなければそこで中止する。
2. それでも作業ツリーへの適用は非原子的なので、**対象パスをスナップショットしてから適用し、
   途中失敗時は復元**する。復元時の上書き防止は §9.4 の二重防御に従う。
3. 受け入れ条件に**「途中で失敗するパッチを適用しても作業ツリーが元に戻る」**テストを含める(A11)。

### 12.2 index(DirCache)も復元対象に含める(Codex レビュー第 2 巡 R2-2・実バイトコードで確認)

**作業ツリーのファイル内容だけを復元しても足りない。** 同梱 JGit 7.5.0 の `PatchApplier` を
`javap -p -c` で確認したところ、**`lockDirCache` を呼び、`DirCacheBuilder.commit` を実行**している。
つまりエラーを収集した後でも **index への変更は確定する**。複数ファイルのパッチの後半で失敗した場合、
先行ファイルの追加・削除・rename による index 更新が残り、**作業ツリーと index が食い違う**。

したがってスナップショットには次を含める。

- 対象パスの**ファイル内容**(または不存在であること)
- 対象パスの**存在種別**(通常ファイル / シンボリックリンク / 不存在)
- **適用前の index**(対象パスのエントリ。ステージ状態を含む)

復元時は作業ツリーと index の**両方**を戻す。**A11 は「staged 状態まで適用前に戻る」ことまで検証する。**

## 13. エラー処理

- 既存の `Resolution.Warn(message)` / 通知パターンに揃える。**例外をユーザーに素通ししない。**
- **JGit の例外メッセージはリモート URL を含みうる**ため、そのまま通知・ログに流さない。
  ユーザー向けは定型文、ログは**例外の型名のみ**。
- REST/GraphQL の失敗は既存 `GitLabApiException` / `GraphQlException` の扱いに従う。
- キャンセル(ダイアログを閉じる)は**エラーではない**。無言で終了する。

## 14. タイムアウト・リトライ・冪等性

- **タイムアウト**: REST/GraphQL は既存 `GitLabHttpClient` の設定に従う。

**git 操作のキャンセルとタイムアウト(Codex レビュー反映 R1-7)**

当初の「`IProgressMonitor` 付き Job に入れるのでキャンセルできる」は**不十分**だった。
Eclipse の Job が monitor を持っていても、**ブロッキング中の JGit transport は止まらない**。
本プラグインは EGit UI に依存しないため `EclipseGitProgressTransformer` も使えない。したがって:

1. **アダプタを自前で持つ**: `org.eclipse.jgit.lib.ProgressMonitor` を実装し、Eclipse の
   `IProgressMonitor` に委譲する小さなクラスを作る。**`isCancelled()` が Eclipse 側のキャンセルを返す**
   ようにし、`CloneCommand` / `PushCommand` に `setProgressMonitor(...)` で必ず設定する。
2. **有限のタイムアウトを設定する**: `TransportCommand.setTimeout(seconds)` で接続・読み取りに上限を置く。
   progress callback に到達しない状態(接続が張れない等)は `isCancelled()` では抜けられないため、
   **タイムアウトが最後の砦**になる。既定値は設計上の定数として定め、設定では露出しない。
3. **後始末を定義する**: キャンセル/タイムアウト時、
   - **clone(R2-7 で訂正)**: 第 1 巡の反映時に「作成したクローン先を削除する」と書いたが、
     §9.5 は**空であれば既存ディレクトリも許す**ため、**コマンドが作らなかったディレクトリまで
     消してしまう**恐れがあった。**実行前に宛先の存在を記録し**、
     - 宛先を**このコマンドが新規作成した場合のみ、ディレクトリごと削除**する。
     - 宛先が**元から存在した場合は、コマンドが作成した子要素だけを削除**し、
       ディレクトリ自体は残す。
   - push: 追加済み remote は**残す**(§9.6 の再開経路が使う)。
4. `GitOperationGuard` の占有は上記により有限時間で解放される。**無応答 transport を使った
   タイムアウト/キャンセル試験を受け入れ条件に含める**(A12)。
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

### 15.1 `SelectedProjectStore` の更新は原子的に行う(Codex レビュー反映 R1-8)

当初は「読み取りはスナップショットを返す」しか定めていなかったが、**複数割当を単一の JSON 値に
収める設計では書き込み側の read-modify-write にも排他が要る**。異なるリポジトリに対する
`selectProject` が 2 件並行して完了すると、双方が同じ旧 JSON を読み、後勝ちの保存で**一方の割当が消える**。

- **読み取り・更新・preference への永続化を同一のロック下**で行う(`Mutex` または同期ブロック)。
- 公開スナップショットは**確定した状態から**更新する(ロック内で確定 → 参照を差し替える)。
- **永続化が失敗したらスナップショットを更新しない**(R2-8)。書き込みまたは flush が失敗した場合、
  メモリ上だけ成功したように見える状態を作らない。**「同一セッション中は効くが再起動で消える」は
  A7 と矛盾する**ため、順序は必ず「永続化成功 → スナップショット差し替え」とする。
  受け入れ条件 **A17**、および**書き込み失敗を注入する試験**を定める。
- 受け入れ条件に**「異なるキーへの並行保存後、両方の割当が残る」**テストを含める(A13)。

### 15.2 パッチ適用の排他境界

§9.4 のとおり、`GitOperationGuard` はプラグイン内の git 操作しか排他しない。エディタの編集・保存は
止められないため、**再検証(dirty + 修正スタンプ)→ 適用 → 復元判定 → refresh** を一連の区間として扱い、
区間の入口で状態が変わっていれば中止する。**「止める」のではなく「検出して中止し、必要なら戻す」**設計。

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
| ~~U2~~ | ~~`ApplyCommand` の原子性~~ | **決着済み(R1-1)。非原子的であることを実バイナリで確認し、§12.1 の設計前提に格上げした。** |
| **U3** | プロジェクト候補の取得方法(検索 API か、ユーザーの参加プロジェクト一覧か)とページング | VSCode の `pickProject` の実装を実ソースで確認して確定する |
| **U4** | クローン後の Eclipse プロジェクト取り込み方法(`.project` がある場合と無い場合) | 実装前に Eclipse API の挙動を確認する |
| ~~U5~~ | ~~未保存エディタの判定範囲~~ | **決着済み(R1-2)。予備チェックは対象リポジトリ配下。安全性の根拠は §9.4-5 の適用直前の再検証であり、判定範囲はパッチが触れる対象パスに限定する。** |
| **U6** | git のタイムアウト既定値(秒)と、無応答 transport の再現手段 | §14 の定数を実装計画で確定する |

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

> **Codex レビュー反映(R1-9)**: 当初は登録の有無と一部の境界条件しか要求しておらず、
> **各要件の正常系が壊れていても条件を満たせてしまった**。F1〜F8 ごとに
> 「正常 / キャンセル / 失敗 / 境界」の観測可能な結果(**API 要求・ローカル変更・UI 結果**)を定める。

**ID の所在**: A1 / A8 / A9 / A10 / A11〜A15 は下表 21.1 で定義する。
**A2〜A7 は 21.2 の該当セル内で太字 ID つきで定義**しており、別途の一覧は持たない。
実装計画はこの ID を引用してタスクに対応付けること。

### 21.1 横断条件

| ID | 条件 |
|---|---|
| A1 | F1〜F8 の各コマンドが `plugin.xml` に登録され、メニュー/Quick Access から起動できる |
| A8 | ストアが空のとき、既存のプロジェクト解決の挙動が**変わらない**(既存テストが無改変で通る) |
| A9 | ログに URI・パス・トークンが出ない(例外は型名のみ)。**JGit の例外メッセージを素通ししない** |
| A10 | 新規 OSGi 依存が無く、`build.gradle.kts` / `detekt.yml` の差分が無い |
| A11 | **途中で失敗するパッチ**を適用しても、作業ツリーが**適用前の内容に戻る**(§12.1) |
| A12 | **無応答の transport** に対して、clone/push が**タイムアウトで終了**し、キャンセルが JGit に伝播し、`GitOperationGuard` が解放される(§14) |
| A13 | **異なるキーへの `selectProject` を並行実行**しても、両方の割当が残る(§15.1) |
| A14 | 割当の `instanceUrl` が現在の接続と一致しないとき、その割当は**使われず** Warn になる(§11.1) |
| A15 | **副作用が始まる前**のキャンセル(ダイアログを閉じる・最終確認で中止)では、**API 要求を出さず、ファイルシステムも変えない**。※ **副作用開始後のキャンセルは A16 が定める**(R2-6) |
| A16 | **副作用開始後**のキャンセル/タイムアウトについて、操作ごとに**残る状態・復元・通知**が下表のとおりであること(R2-6) |

**A16 の内訳** — 第 1 巡の A15 は「全コマンドでキャンセル時に副作用ゼロ」を要求していたが、
F6 は最終確認後に初期コミットと `POST /projects` を行い、§14 は push キャンセル時に remote と
`PublishState` を**意図的に残す**と定めている。**A15 と各フローは同時に満たせなかった**ため分離する。

| 操作 | 副作用の開始点 | 開始後にキャンセルしたとき |
|---|---|---|
| clone(F5 / F7) | 転送開始 | 宛先が新規作成なら**ディレクトリごと削除**、既存なら**作成した子要素のみ削除**(§14-3)。通知する |
| パッチ適用(F4) | 作業ツリーへの最初の書き込み | §9.4 の復元を試みる。**外部変更が入ったファイルは復元しない**(上書きしない)。復元しなかった件数を通知する |
| 初期コミット(F6 フェーズ 2) | `init` / commit | **戻さない**。作成した `.git` と初期コミットは残る旨を通知する |
| プロジェクト作成(F6 フェーズ 3) | `POST /projects` | **プロジェクトは削除しない**。`PublishIntent` / `PublishState` を残し、次回の再開経路に載せる(§9.6) |
| push(F6 フェーズ 3) | 転送開始 | remote と `PublishState` を**残す**。次回は push から再開する |

### 21.2 要件ごとの条件

| 要件 | 正常系(観測可能な結果) | キャンセル | 失敗 | 境界 |
|---|---|---|---|---|
| **F1** createSnippet | `POST /projects/{id}/snippets` が `{title, file_name, visibility, content}` で 1 回だけ発行され、応答の `web_url` がブラウザで開く | 可視性/ソース選択のいずれで閉じても**要求を出さない** | API 失敗時に定型文を通知し、ブラウザを開かない | **A2**: 選択範囲は VSCode と同じ**行単位の丸め**(選択終端の次の行頭まで)。エディタ未オープン時は通知して終了 |
| **F2** insertSnippet | 選択した blob の `rawPlainData` が**カーソル位置に**挿入される | 一覧/blob 選択で閉じてもエディタを変更しない | 取得失敗時にエディタを変更しない | 一覧 0 件は通知して終了。blob 1 件なら blob 選択を出さない。`hasNextPage` のとき**打ち切りを表示**(§11) |
| **F3** createSnippetPatch | diff が `content`、タイトルが `patch: <名前>`、ファイル名が `<名前>.patch`、説明文に適用手順を含む | 名前/可視性で閉じても要求を出さない | — | HEAD コミットが無ければ中止。diff が空なら通知して終了 |
| **F4** applySnippetPatch | パッチが作業ツリーに適用される。**A4**: 適用後に `IResource.refreshLocal(DEPTH_INFINITE)` が実行され、変更が **Eclipse のリソースツリーから見える**(復元した場合も refresh する) | 一覧選択で閉じても作業ツリーを変更しない | **A11** の復元 | **A3**: 適用直前の再検証で dirty/変更を検出したら中止し作業ツリーを変更しない。`.patch` blob を持つスニペットが 0 件なら通知して終了 |
| **F5** cloneWiki | `.wiki.git` に変換された URL がクローンされ、Eclipse プロジェクトとして取り込まれる | プロジェクト/URL/ディレクトリ選択で閉じてもディレクトリを作らない | 失敗時に**作成したクローン先を削除**する | **A6**: 空でないディレクトリなら中止 |
| **F6** publishToGitLab | `POST /projects` が 1 回、remote 追加、push が**全 destination で `OK`/`UP_TO_DATE`**、その後に upstream 設定 | **最終確認までのキャンセルでファイルシステムが不変**(R2-1) | push 拒否を**失敗として通知**(R1-6)。`PublishState` を残す | **A5**: 既存 remote があれば中止。ただし `PublishState` に一致する場合のみ**push だけ再開し、プロジェクトを重複作成しない**(R1-5)。非リポジトリは初期コミットを作る。detached HEAD / ファイル 0 件は中止 |
| **F7** openRepository | 選んだプロジェクトがクローンされ、ワークスペースに取り込まれる | F5 と同じ | F5 と同じ | F5 と同じ |
| **F8** selectProject | 割当が保存され、**以後の解決が割当を優先する**。**A7**: Eclipse 再起動後も保持される | 保存しない | **A17**(R2-8): preference の書き込み/flush が失敗した場合、**公開スナップショットを更新せず**(または直前の値へ戻し)、定型エラーを通知する。成功通知は出さない | **A13** / **A14** |

## 22. 想定されるリスク

| リスク | 影響 | 緩和 |
|---|---|---|
| **`applySnippetPatch` が未保存バッファと競合し、変更が失われる** | データ損失 | §9.4 の**適用直前の再検証**(dirty + 修正スタンプ)+ in-core 事前検証 + スナップショット復元 + 適用後 refresh。**#66 と同じクラスの問題**であり、#66 の結論と整合させる |
| **`PatchApplier` の部分適用で作業ツリーが壊れる** | データ損失 | §12.1。in-core 検証で弾き、それでも失敗したらスナップショットから復元(A11) |
| **`publishToGitLab` が意図しないリポジトリを公開する** | 情報漏洩 | 既存 remote があれば中止。可視性を明示的に選ばせ**既定を private** にする。非リポジトリでは**コミット対象ファイル数を提示し、`.gitignore` が無い場合は警告**する(§9.6 フェーズ 1) |
| **push 失敗後に手作業で remote を消して再実行し、プロジェクトが重複作成される** | リモート汚染 | `PublishState` による再開経路(§9.6・R1-5) |
| **インスタンス切替後に古い割当が別ホストのプロジェクトへ書き込みを向ける** | 誤送信 | §11.1 のインスタンス束縛と二重検証(A14) |
| **キャンセルしても JGit transport が止まらず `GitOperationGuard` を占有し続ける** | 機能停止 | §14 の ProgressMonitor アダプタ + `setTimeout`(A12) |
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

## 24. Codex レビュー反映履歴(第 1 巡)

対象コミット `4bdf590`。**P1 × 9 / P2 × 1、全 10 件を反映**。検証可能な 2 件は実ソース・実バイナリで裏を取った。

| # | 指摘 | 判定 | 反映先 |
|---|---|---|---|
| R1-1 | `ApplyCommand` を原子的と見なさずロールバックを設計する | **妥当。当初の前提が誤りだった。** JGit 7.5.0 の `PatchApplier$Result` が `getErrors()` を返す構造であることを jar で確認 | §12.1 新設・§9.4・A11。旧 U2 を廃止し設計前提に格上げ |
| R1-2 | 適用直前までエディタ変更との競合を閉じる | **妥当。** 判定→取得→選択→適用の間の窓は実在 | §9.4 を再検証つきに全面改訂・§15.2・A3。旧 U5 を決着 |
| R1-3 | 割当を作成元の GitLab インスタンスに束縛する | **妥当。** デコレータが resolver のインスタンス照合を迂回する | §11.1 新設(`instanceUrl` 必須 + 二重検証)・A14 |
| R1-4 | 非リポジトリ発行時の初期コミットを定義する | **妥当。** 新規リポジトリに push 可能な ref は無く、`Initial commit` 定数も未使用だった | §9.6 フェーズ 2(`.gitignore` を尊重した初期コミット) |
| R1-5 | push 失敗後に発行処理を再開できるようにする | **妥当。** 「remote があれば中止」と「プロジェクトを消さない」の組合せで手詰まりになる | §9.6 再開節・`PublishState`・§12 |
| R1-6 | `PushResult` の拒否を失敗として判定する | **妥当。** 既存 `BranchPushService.doPush` が同じ判定を実装済みであることを確認(`isAccepted()` を全 destination で判定 → その後 upstream) | §9.6-8。**新規に書かず既存実装を共有する**と明記 |
| R1-7 | git キャンセルを JGit に伝播し期限も設定する | **妥当。** Eclipse Job の monitor だけでは JGit transport は止まらない。EGit UI 非依存のため `EclipseGitProgressTransformer` も使えない | §14 を全面改訂(自前アダプタ + `setTimeout` + 後始末)・A12・U6 |
| R1-8 | `SelectedProjectStore` の更新を原子的にする | **妥当。** 単一 JSON 値への read-modify-write は後勝ちで割当が消える | §15.1 新設・A13 |
| R1-9 | 各機能要件の正常系を受け入れ条件に追加する | **妥当。** 旧 A1〜A10 は登録と境界条件に偏っていた | §21 を 21.1(横断)/ 21.2(F1〜F8 の正常・キャンセル・失敗・境界)に再構成 |
| R2-1 | ユーザー入力前に `git init` を実行しない | **妥当。** 自らの N5 と矛盾していた | §9.6 をフェーズ 0〜3 に再構成し、破壊的操作を最終確認の後へ |

**押し返し: 0 件。** 10 件とも本設計の欠陥であり、うち 2 件(R1-1 / R1-6)は
「実装時に確認する」で先送りしていた事項を、**実ソースで確定して設計に取り込んだ**。

## 25. Codex レビュー反映履歴(第 2 巡)

対象コミット `67b05b1`。**P1 × 7 / P2 × 1 = 8 件、全件反映・押し返し 0 件。**
**8 件中 6 件は第 1 巡の修正そのものが生んだ綻び**であり、修正が新たな不整合を作ったことを記録しておく。

| # | 指摘 | 判定 | 反映先 |
|---|---|---|---|
| R2-1 | パッチ適用完了までエディタ保存との競合を閉じる | **妥当。第 1 巡で書いた「復元によりユーザーの編集が失われない側に倒れる」は誤りだった** — スナップショット後の保存を古い内容で上書きしうる | §9.4 排他境界を全面改訂(`ISchedulingRule` を適用〜refresh まで保持 **+** 書き込みごとの stamp/hash 照合の二重防御) |
| R2-2 | 失敗時に Git index もスナップショットから復元する | **妥当。実バイトコードで確認** — `javap -p -c` で `PatchApplier` が `lockDirCache` を呼び `DirCacheBuilder.commit` を実行することを確認 | §12.2 新設(内容・存在種別・index を保存/復元)。A11 を staged 状態まで検証するよう拡張 |
| R2-3 | プロジェクト作成と `PublishState` 記録のクラッシュ窓を閉じる | **妥当。** POST 成功後に記録する順序では孤立プロジェクトと二重作成が起きうる | §9.6-6/8 に `PublishIntent` の事前永続化と確定遷移、未確定 intent の照会回復を追加 |
| R2-4 | `PublishState` に照合可能な remote と接続識別子を保存する | **妥当。** `remoteName` + `projectWebUrl` では SSH remote を一意に復元できない | §11 に `normalizedRemoteUrl` / `instanceUrl` / `projectId` を追加。判定は正規化 URL の一致で行う |
| R2-5 | 割当 remote 自体を現在のインスタンスと照合する | **妥当。** 条件 1・2 だけでは別インスタンスや GitHub の remote が通過する | §11.1 に**条件 3** を追加(HTTPS と SSH/scp-like の双方で origin を照合) |
| R2-6 | A15 を破壊的フェーズ後のキャンセル方針と整合させる | **妥当。** A15 と F6 / §14 は同時に満たせなかった | A15 を**副作用開始前**に限定し、**A16**(操作別の開始後キャンセル表)を新設 |
| R2-7 | 既存の空ディレクトリを clone 後始末で削除しない | **妥当。** §9.5 は空の既存ディレクトリを許すため、第 1 巡の後始末規定は作成していないものを消しうる | §14-3 を訂正(宛先の存在を事前記録し、新規作成時のみディレクトリごと削除) |
| R2-8 | F8 の永続化失敗時の結果を受け入れ条件に定める | **妥当。** 未定義だと「セッション中だけ有効」な実装が表を満たし A7 と矛盾する | §15.1 に順序(永続化成功 → スナップショット差し替え)を明記し、**A17** を新設 |
