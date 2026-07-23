# Phase 2: 小型 native + 低コスト配線 設計書

- 対象ロードマップ: #8(Phase 2)/ フェーズ issue: #10 / パリティ台帳: #7
- ベースブランチ: `develop`(develop tip `5c83a63`)
- 対象パリティ: VSCode 拡張 `gitlab-workflow` v6.85.3
- ステータス: レビュー用ドラフト(実装前)

> 本書は Codex 設計レビュー専用。実装 PR およびマージ先ブランチには含めない。

> **Codex レビュー反映(2026-07-23、PR #21)**: P1 指摘 4 件をすべて反映済み。
> - P1-a: `openInGitLab`/`copyLinkToClipboard` の起動契約を §9 決定表で確定(コミット対象は Phase 3 送り)。
> - P1-b: LS 再起動を provider のアトミック `restart()` に(§8:単一ロック + プロセス同一性ガード + executor 再生成)。既存コードの共有 `process` 破壊競合を確証し反映。
> - P1-c: 生成 URL のパスセグメントを RFC 3986 で percent-encode(§8 規則 / §9 フロー A)。
> - P1-d: 高度な検索をスコープで分岐(§9 フロー B')。
>
> **Codex 再レビュー反映(2026-07-23、コミット `7003633` に対する 2 巡目)**: 追加 P1×2 + P2×1 を反映。
> - P1-new-1: `openInGitLab`/`copyLinkToClipboard` はプロジェクト対象のみに確定し、ファイル対象は専用コマンドへ委譲(§9 決定表)。重複挙動を排除。
> - P1-new-2: 高度な検索の project スコープを数値 `project_id` 非依存に変更。プロジェクト検索 URL `${webUrl}/-/search`(API 不要、A 案と整合)を用いる(§9 フロー B')。
> - P2-new: §16 を restart 状態機械変更あり(provider 内部実装変更・互換性確認範囲 2 経路)に更新。
>
> **Codex 再レビュー反映(2026-07-23、コミット `24c685c` に対する 3 巡目)**: P1×1 を反映。
> - P1-3rd: §2 対象範囲表・§19 受け入れ条件・§21 U-2 に残っていた `openInGitLab` の「ファイル」対象記述を、§9 決定表(プロジェクト専用)に揃えた。ファイルは専用コマンドで検証する旨を明記。
>
> **Codex 再レビュー反映(2026-07-23、コミット `f36b3fc` に対する 4 巡目)**: P1×1 を反映。
> - P1-4th: 要件 §5 FR-2 を Phase 2 の実コマンドに整合(プロジェクト対象のみ、ファイルは FR-1、コミットは Phase 3 送り)。

---

## 1. 背景と目的

公式 GitLab Eclipse プラグイン(Gradle/Kotlin、`com.gitlab.eclipse.*`)を VSCode 拡張 `gitlab-workflow` v6.85.3 と機能パリティにする取り組みの Phase 2。Phase 1(Duo Chat 完全化、PR #19 マージ済み)に続き、既存基盤(自前管理の Language Server プロセス、Tier3 の native REST クライアント、JGit)の上に**小さく独立したスライス**を実装し、以降のフェーズ(Phase 3: MR、Phase 4: CI)で再利用する「リポジトリ ⇔ GitLab プロジェクト URL 解決層」を確立する。

**目的:**
- Phase 2 対象コマンド群を VSCode と挙動一致で実装する。
- Phase 3/4 が依存する URL 解決の共通部品を、テスト可能な純粋層として先行整備する。

## 2. 対象範囲(スコープ)

Phase 2 対象 9 項目のうち、本設計は以下 8 項目を対象とする(`status.issue` は Phase 3 へ繰り延べ。理由は §3 と §20)。

| # | コマンド | #7 該当 | 実装方式 |
|---|---|---|---|
| 1 | `gl.mcp.openUserConfig` / `gl.mcp.openWorkspaceConfig` | D8 | ローカル `mcp.json` 冪等生成 → エディタで開く |
| 2 | `gl.restartLanguageServer` | D10 | 既存プロセスプロバイダの `stop()` → `start(bundle)` |
| 3 | `gl.openActiveFile` / `gl.copyLinkToActiveFile` | D16 | JGit + URL 解決 → ブラウザ / クリップボード |
| 4 | `gl.openInGitLab`(プロジェクトのみ)/ `gl.copyLinkToClipboard` | D16 | 同上。ファイル対象は #3 の専用コマンドが担う。コミット対象は Phase 3 送り(§9 決定表) |
| 5 | `gl.openCreateNewIssue` | D11 | `${webUrl}/-/issues/new` をブラウザ |
| 6 | `gl.issueSearch` / `gl.mergeRequestSearch` / `gl.advancedSearch` | D11/D16 | 入力ダイアログ → URL → ブラウザ |

### パリティ方針の決定(設計判断)

VSCode では上記 4–6 はすべて「GitLab の URL を組み立てて外部ブラウザで開く / リンクをコピーする」実装であり、ネイティブ UI ではない。本プラグインは Tier3 で native `IssuesView` + REST クライアントを持つが、Phase 2 では **VSCode に挙動一致(ブラウザ/クリップボード方式)** を採用する。根拠:

- プロジェクトの北極星は gitlab-workflow v6.85.3 とのパリティ。create/search をネイティブ化すると UX が逆に乖離する。
- 再利用される土台(プロジェクト URL 解決層)はブラウザ方式でも構築される。
- ネイティブ CRUD パターンは Tier3 で確立済みで、Phase 2 で再証明する必要はない。
- ネイティブ Issue 作成は REST の POST 基盤 + プロジェクト数値 ID 解決 + フォーム UI を要し、GitLab 本体の新規 Issue ページ(テンプレート・検証)に機能で劣る。POST 基盤への投資はそれが必要になる Phase 3 に取っておく。

### URL 解決方式の決定(設計判断)

GitLab の `web_url` は実質 `{instanceUrl}/{namespaceWithPath}`。したがって **API を呼ばずローカル構築**する(A 案)。git remote を解析して `namespaceWithPath` を得て `${gitlab.url}/${namespaceWithPath}` を構築する。ブラウザ表示/リンクコピー用途では API の正規 `web_url` と実質同一であり、プロジェクト改名時も GitLab 側の 301 リダイレクトで正しく着地する。API ルックアップ方式(B 案)はコマンドごとのネットワーク往復・認証依存・オフライン失敗を招くため Phase 2 では採らない。正規データ(プロジェクト ID 等)が必要になる Phase 3 で API を上乗せする。

## 3. 対象外(スコープ外)

- **`gl.status.issue`(ステータスバー Issue 項目)→ Phase 3 へ繰り延べ。** VSCode の当該項目は「現在ブランチの MR が閉じる Issue」を表示し、MR ルックアップ(ブランチ → MR → 閉じる Issue)に構造的に依存する。これは Phase 3(MR ドメイン)の成果物であり、かつ本プラグイン初のステータスバー項目となるが MR データ無しでは表示内容が無い。依存が揃う Phase 3 で本来の姿で実装する。
- ネイティブ Issue 作成/検索フォーム(§2 の方針決定により対象外)。
- REST クライアントの POST/PUT 対応(Phase 3 以降)。
- GraphQL 層(Phase 5)。
- プロジェクト改名追従のための API による正規 `web_url` 取得(A 案採用により対象外)。

## 4. 現在の課題

- 上記コマンドは未実装(`restartLanguageServer` は `stop()`/`start()` プリミティブのみ存在し、ハンドラ/コマンド/メニュー配線が無い)。
- git remote から GitLab プロジェクト URL を解決する共通部品が存在しない。Phase 3/4 も同じ解決を必要とする。

## 5. 要件

### 機能要件

- **FR-1**: アクティブ**ファイル**の GitLab blob URL(コミット SHA 固定・選択行アンカー付き)をブラウザで開く / クリップボードにコピーできる(`gl.openActiveFile`/`gl.copyLinkToActiveFile`)。
- **FR-2**: 現在の**プロジェクト**を GitLab で開く / リンクをコピーできる(`gl.openInGitLab`/`gl.copyLinkToClipboard`、Phase 2 はプロジェクト対象のみ)。ファイル対象は FR-1 が担う。**コミット対象は Phase 2 対象外(Phase 3 送り)**(§3・§9 決定表)。
- **FR-3**: 新規 Issue 作成ページをブラウザで開ける。
- **FR-4**: Issue / MR / 高度な検索の入力を受け、対応する GitLab 検索 URL をブラウザで開ける。
- **FR-5**: MCP のユーザー/ワークスペース設定ファイルを冪等に生成し、エディタで開ける。
- **FR-6**: Language Server を再起動できる。

### 非機能要件

- **NFR-1**: URL 解決ロジック(remote 解析・検索クエリ組立・MCP パス/生成)は純粋・テスト可能な層に隔離する。
- **NFR-2**: UI スレッド境界を厳守(§10)。UI 応答をブロックしない。
- **NFR-3**: ディレクトリ構成・ビルドシステムを変更しない。新規依存を追加しない(JGit は既存)。
- **NFR-4**: 既存の detekt 0 件・テスト失敗 36 件(headless の SWT 既知失敗)を維持する。

## 6. 前提条件と制約

- 既存 `src/main/kotlin/com/gitlab/eclipse/*` パッケージ体系内への追加のみ。ディレクトリ構成変更禁止。
- Gradle + 既存 update-site 構成のまま。依存追加なし(JGit 7.5.0 は既存・使用実績あり)。
- ドキュメントはコミットしない(本書はレビュー専用ブランチのみ)。
- headless devcontainer では SWT/Browser/実 LS は動作検証不可。UI/スレッド/実プロセス依存は手動検証。
- プロトコル/挙動は実ソース(`out/gitlab-vscode-extension`、読み取り専用)で確定済み(§18 参照元)。

## 7. システム構成(コンポーネント)

新規サブパッケージを既存体系内に追加する。

```
com.gitlab.eclipse
├── navigation/                 (新規)
│   ├── GitLabRemoteParser       remote 文字列 → namespaceWithPath/host/protocol(純関数)
│   ├── GitLabProjectUrlResolver アクティブファイル/エディタ → JGit → webUrl / blob URL 構築
│   ├── SearchQueryBuilder       検索文字列トークン → URL クエリ(純関数)
│   ├── BrowserLauncher          外部ブラウザで URL を開く
│   └── ClipboardWriter          URL をクリップボードへ
├── mcp/                        (新規)
│   └── McpConfigService         mcp.json パス解決 + テンプレ冪等生成
└── handlers/                   (既存に追加)
    ├── OpenActiveFileHandler / CopyLinkToActiveFileHandler
    ├── OpenInGitLabHandler / CopyLinkToClipboardHandler
    ├── OpenCreateNewIssueHandler
    ├── IssueSearchHandler / MergeRequestSearchHandler / AdvancedSearchHandler
    ├── OpenMcpUserConfigHandler / OpenMcpWorkspaceConfigHandler
    └── RestartLanguageServerHandler
```

## 8. コンポーネントの責務

### GitLabRemoteParser(純関数)
- 入力: remote URL 文字列、任意で instanceUrl(カスタムルート除去用)。
- 出力: `{ host, hostname, protocol, namespace, projectPath, namespaceWithPath }` または `null`。
- VSCode `git_remote_parser.ts` の `normalizeSshRemote` / `parseGitLabRemote` を Kotlin 移植。SSH(カスタムポート `[git@h:7999]:g/r.git`、絶対パス `git@h:/g/r.git`、scheme+port、末尾 `.git`/スラッシュ)・HTTPS・カスタムインスタンスルートを吸収。
- host/protocol 照合は VSCode `parseProject` 準拠(protocol 一致時は host 一致、protocol 不一致時は hostname 一致)。

### GitLabProjectUrlResolver
- アクティブファイルの `IFile` → ローカルパス → JGit `FileRepositoryBuilder.findGitDir` で包含リポジトリ検出。
- `remote.origin.url`(無ければ `gitlab.url` host に一致する最初の remote)→ `GitLabRemoteParser` → `namespaceWithPath`。
- `webUrl = ${gitlab.url(末尾スラッシュ除去)}/${namespaceWithPath}` を構築(A 案・API 不使用)。
- blob URL 用にファイルの最新コミット SHA(`git log -1 -- <path>` 相当)とリポジトリ root 相対パスを取得。
- 選択範囲(開始/終了行、UI 層から受領)→ アンカー生成。
- **URL エンコード規則(Codex P1-c 反映)**: 生成 URL のパス部は RFC 3986 に従い**セグメント単位で percent-encode** する。対象は `namespaceWithPath` の各セグメントとリポジトリ相対パスの各セグメント、コミット SHA。**階層区切りの `/` は保持**(エンコードしない)。空白・`#`・`?`・`%`・非 ASCII・既存 percent escape を含むファイル名でもリンクが壊れないことを保証する。VSCode `openers.ts` はファイルパスを生連結しており特殊文字で壊れる既知の実バグがあるため、本設計では VSCode より堅牢化する(通常のファイル名では生成 URL は VSCode と一致)。行アンカー `#L..` はフラグメントとしてエンコード対象外。

### SearchQueryBuilder(純関数)
- VSCode `search_input.ts` の `parseQuery` を移植。トークン(`labels`/`label`/`title`/`milestone`/`author`(`me`→`created-by-me`)/`assignee`(`me`→`assigned-to-me`、issues は `assignee_username[]`、MR は `assignee_username`))と基本テキスト検索を URL クエリへ変換。
- 高度な検索のスコープ/レベル対応(project/instance、GitLab.com/self-managed のスコープ表)は `AdvancedSearchHandler` 側で選択、本ビルダは `search`/`scope` からクエリ文字列を組む(数値 `project_id` は用いない。project スコープはプロジェクト検索エンドポイント `${webUrl}/-/search` を使うため — §9 フロー B'/P1-new-2)。

### McpConfigService
- user パス: `${HOME}/.gitlab/duo/mcp.json`。workspace パス: `<workspaceRoot>/.gitlab/duo/mcp.json`。
- テンプレ(VSCode `DEFAULT_CONFIG_TEMPLATE` と同一)を冪等生成:ディレクトリ作成 → `wx`(排他作成)相当で書き込み、既存なら何もしない、既存がディレクトリなら明示エラー。
- HOME 未設定時・ワークスペース未オープン時はエラーメッセージ。

### 各ハンドラ
- `AbstractHandler` を継承する薄い配線。UI 層(選択・アクティブエディタ取得、ブラウザ/クリップボード起動、入力/選択ダイアログ)とロジック層(resolver/builder/service)を仲介。JGit などの I/O はバックグラウンド `Job` に載せる。
- `RestartLanguageServerHandler`: provider の**アトミックな `restart()`**(下記)を呼ぶだけの薄い配線。bundle は `FrameworkUtil.getBundle(...)` 等で取得。

### GitLabLanguageServerProcessProvider の変更(Codex P1-b 反映・重要)
既存 provider は restart を安全に行えない。実コードで確認した欠陥:
- `process` は共有可変フィールドで、`onExit` コールバック(`GitLabLanguageServerProcessProvider.kt:68-71`)が**終了プロセスの同一性を確認せず無条件に `process = null`** する。`stop()` → `start()` 後に旧プロセスの終了通知が届くと、**新プロセスのフィールドを null 化**し、以後の停止・再起動・復旧が不整合になる(単一 restart でも発生)。
- `pullStdErrLogsExecutor` は `stop()` で `shutdownNow()` されるが `start()` で再生成されないため、restart 後に `pullStdErrLogs()` が `RejectedExecutionException` になる。

対応(provider をライフサイクル状態機械にする):
1. **単一ロック/直列化**: `start`/`stop`/`restart` を同一ロックで直列実行し、多重再起動を排除。
2. **プロセス同一性ガード**: `onExit` は「終了したプロセスが現行フィールドと同一のときだけ」状態(`process`/`processListener`)を更新する(世代トークンまたは Process 参照比較)。
3. **executor ライフサイクル修正**: `pullStdErrLogsExecutor` を `start()` 時に生成/再生成する(または stop で shutdown しない設計に変更)。
4. **`restart()` を追加**: `stop()` → `start(bundle)` を上記ロック下でアトミックに実行。
5. **開始失敗時の到達状態**: `start()` が失敗(`process == null`)した場合の状態(停止扱い)・再試行可否・ユーザー通知を定義し受け入れ条件に含める(§19)。
既存の起動/終了経路(`GitLabEclipseStartup`)の呼び出し契約は不変(内部の同一性ガード追加のみ)。

## 9. 処理フロー

### フロー A: アクティブファイル → blob URL(FR-1)
1. [UI] アクティブエディタ → `IFileEditorInput.getFile()`、`ITextEditor` 選択範囲(開始/終了行)取得。
2. [BG] `IFile` ローカルパス → JGit で包含リポジトリ検出 → `remote.origin.url` → `GitLabRemoteParser` → `namespaceWithPath` + host/protocol。
3. [BG] host/protocol を `gitlab.url` と照合(不一致は §11 エラー)→ `webUrl` 構築。
4. [BG] 当該ファイルの最新コミット SHA(空なら「未コミット」エラー)+ root 相対パス。
5. 選択範囲 → アンカー `#L{start+1}` または `#L{start+1}-{end+1}`。
6. パス各セグメントを percent-encode(§8 エンコード規則)して `${webUrl}/-/blob/${encode(sha)}/${encodePathSegments(relpath)}${anchor}` を構築。
7. [UI] `BrowserLauncher` で開く / `ClipboardWriter` でコピー。

### フロー B: プロジェクト系 URL(FR-2/3)
- リポジトリ → `webUrl` はフロー A の 2–3 と同一。テンプレ:
  - 新規 Issue: `${webUrl}/-/issues/new`
  - Issue 検索: `${webUrl}/-/issues?${query}`(入力ダイアログ → SearchQueryBuilder)
  - MR 検索: `${webUrl}/-/merge_requests?${query}`

### フロー B': 高度な検索(FR-4、Codex P1-d/P1-new-2 反映)
高度な検索は **数値 `project_id` を用いない**(Phase 2 は API を呼ばず、remote から数値 ID を導出できないため)。スコープにより URL の起点だけを切り替え、いずれも `namespaceWithPath`/`webUrl` のみで構築する。
1. `gitlab.url` 設定を確認(未設定なら §11 エラーで即終了)。
2. 検索文字列を入力ダイアログで受領(空なら何もしない)。
3. スコープ/レベル選択(project / instance)。GitLab.com か self-managed かでスコープ表を切替。
4. URL 構築:
   - **instance スコープ**(リポジトリ非依存): `${gitlab.url}/search?search=..&scope=..`。リポジトリ選択フローに一切依存しない。
   - **project スコープ**: リポジトリ解決(§下記)で `webUrl` を得て、**プロジェクトスコープ検索 URL** `${webUrl}/-/search?search=..&scope=..` を構築(`project_id` 不要)。VSCode は instance の `/search?project_id=` を使うが、プロジェクトの `/-/search` は等価の結果を返し、かつ API 不要で A 案と整合する。
5. [UI] `BrowserLauncher` で開く。
- ワークスペース未オープン・Git リポジトリ無し・複数リポジトリでも、instance スコープなら成功する。project スコープ選択時のみリポジトリ選択・不一致エラー(§11)が起こりうる。

### openInGitLab / copyLinkToClipboard 起動契約(決定表、Codex P1-a 反映)
VSCode の `gl.openInGitLab` はツリー項目(Issue/MR/Job/Pipeline)専用だが Phase 2 にツリーは無い。台帳 D16「ファイル/コミット/プロジェクト」を Phase 2 では以下に**確定**する。各コマンドは起動口・入力・対象なし時挙動・生成 URL を固定:

| コマンド | 起動口 | 対象・入力 | 生成 URL | 対象取得不可時 |
|---|---|---|---|---|
| `gl.openActiveFile` | エディタ右クリック / コマンド | アクティブファイル + 選択行 | `${webUrl}/-/blob/${sha}/${relpath}#L..` | §11 の各警告 |
| `gl.copyLinkToActiveFile` | 同上 | 同上 | 同上(クリップボードへ) | 同上 |
| `gl.openInGitLab` | コマンド / プロジェクト右クリック | アクティブファイルの包含リポジトリ、無ければリポジトリ選択 | `${webUrl}`(プロジェクトページ) | remote/設定エラー |
| `gl.copyLinkToClipboard` | 同上 | 同上 | `${webUrl}`(クリップボードへ) | 同上 |

- **対象の割り当て(Codex P1-new-1 反映)**: ファイル(blob)対象は専用コマンド `gl.openActiveFile`/`gl.copyLinkToActiveFile` が担う。`gl.openInGitLab`/`gl.copyLinkToClipboard` は **Phase 2 ではプロジェクト対象のみ**とし、ファイル起点の振り分けは持たせない(重複挙動を排除)。将来 Issue/MR/Pipeline/コミット等の対象が増える際に、これらのコマンドへ対象を追加する(Phase 3 以降)。
- **「コミット」対象は Phase 2 では見送り。** VSCode ではコミット履歴/ツリー項目起点(`openCommitInGitLab` は `SourceControlHistoryItemDetailsProvider` 経由)であり、Phase 2 にその起動口が無い。ツリー/履歴連携が整う Phase 3 以降で実装する(§3・台帳に明記)。

### リポジトリ選択(multi-repo / multi-remote)
- 適用対象: フロー A/B と、フロー B' の **project スコープ選択時のみ**。
- アクティブファイルがある → そのファイルの包含リポジトリ(一意)。
- アクティブファイルが無い(プロジェクト系のみ)→ ワークスペース内 GitLab リポジトリを列挙:単一なら自動、複数なら SWT 選択ダイアログ。
- remote は `origin` 優先、無ければ `gitlab.url` host 一致の最初の remote。

## 10. 並行処理・スレッド境界

- UI スレッド: エディタ/選択取得、ブラウザ起動、クリップボード書込、各種ダイアログ。
- バックグラウンド(`Job`): JGit(リポジトリ検出・log)。Phase 2 は API を呼ばないため重い I/O は JGit のみ。
- `syncExec` は使わない。選択情報は先に UI で取得しバックグラウンドへ値渡し(Phase 1 の `asyncExec` パターン踏襲)。
- restart は provider 内の**アトミックな `restart()`**(§8:単一ロック + プロセス同一性ガード + executor 再生成)で実行。`onExit` は現行プロセスと同一のときだけ状態更新するため、旧プロセスの遅延終了通知が新プロセス状態を破壊しない。

## 11. エラー処理(VSCode 準拠メッセージ)

| 条件 | 挙動 |
|---|---|
| ファイルがリポジトリ外 | 警告「The current file is not in the project repository.」 |
| ファイル未コミット(log 空) | 警告「No link exists for the current file. Commit the current file to the repository.」 |
| remote が設定 GitLab インスタンスに不一致 | 警告(現在のプロジェクトが設定 GitLab に一致しない旨) |
| `gitlab.url` 未設定 / remote 無し | 警告 + 設定を促す |
| 検索入力が空 | 何もしない(VSCode 準拠) |
| MCP: HOME 未設定 / ワークスペース未オープン | 警告(それぞれの原因を明示) |
| MCP: 設定パスが既存ディレクトリ | エラー(削除/改名を促す) |
| LS 再起動失敗(start 例外、`process == null`) | 停止状態に確定 + エラーログ + ユーザー通知(再試行可能である旨)。部分起動状態を残さない |
| 高度な検索(instance)で `gitlab.url` 未設定 | 警告 + 設定を促す(リポジトリは参照しない) |
| 高度な検索(project)でリポジトリ未解決/不一致 | 警告(project スコープはリポジトリ解決が必要な旨。instance スコープを促す) |
| ファイル名/パスに特殊文字 | percent-encode で正しい URL を生成(§8 規則) |

## 12. 認証と認可

- Phase 2 は GitLab API を呼ばない(A 案)。認証トークンは使用しない。ブラウザで開いた先の GitLab がユーザーのブラウザセッションで認証する。
- MCP 設定・LS 再起動もトークン不要。よって Phase 2 に新たな認証・認可の考慮点は無い。

## 13. 冪等性・多重実行

- MCP 設定生成は冪等(既存ファイルは上書きしない。排他作成 + EEXIST 時は既存を尊重)。
- LS 再起動は provider 内の単一ロックで直列化し、多重 `restart()`/`start()`/`stop()` を排除(§8・§10)。`onExit` のプロセス同一性ガードにより、旧プロセスの遅延終了通知は現行状態を変更しない。
- URL 生成・ブラウザ起動は副作用が外部ブラウザのタブ生成のみで、多重実行は無害。

## 14. タイムアウトとリトライ

- ネットワーク呼び出しが無いためタイムアウト/リトライは基本不要。
- JGit の log/リポジトリ検出はローカル I/O。異常時は例外を捕捉しエラー表示(リトライしない)。

## 15. ログ・監視・監査

- 既存 `logger<T>()` を使用。各ハンドラは開始・結果・エラーを既存水準でログ。
- 監査要件は無し(外部送信なし)。

## 16. 既存機能への影響

- 既存 `IssuesView`(自分宛て Issue 一覧)は変更しない。`openInBrowser` の実装パターンを `BrowserLauncher` として一般化する際、既存呼び出しは現状維持(リファクタは最小・任意)。
- **`GitLabLanguageServerProcessProvider` を変更する(Codex P1-b/P2-new 反映)。** §8 のとおり内部状態機械化を行う:(1) `start`/`stop`/`restart` の単一ロックによる直列化、(2) `onExit` にプロセス同一性ガードを追加(現行プロセスと同一のときだけ `process`/`processListener` を更新)、(3) `pullStdErrLogsExecutor` を `start()` 時に生成/再生成、(4) アトミックな `restart()` の追加。
  - **既存経路への影響と互換性確認範囲**: 起動(`GitLabEclipseStartup` L55 `start`)・終了(L83 `stop`)の**呼び出し契約(シグネチャ・呼び出しタイミング)は不変**。変更は provider 内部実装に閉じる。回帰確認の対象は「通常起動 → 動作 → シャットダウン」と「起動 → restart → 動作 → シャットダウン」の 2 経路(§19 受け入れ条件)。onExit の同一性ガード追加により、従来の単発起動/終了の挙動は変わらないことを確認する。
- 既存 `IssuesView`(自分宛て Issue 一覧)は変更しない。`openInBrowser` の実装パターンを `BrowserLauncher` として一般化する際、既存呼び出しは現状維持(リファクタは最小・任意)。
- plugin.xml へコマンド/ハンドラ/メニュー項目を追加(既存項目は不変)。

## 17. 障害時の復旧・ロールバック

- 各機能は独立し副作用が小さい(ブラウザタブ、ローカルファイル生成、プロセス再起動)。障害時は該当コマンドを使わなければよい。
- ロールバック: 3 スライスは PR 単位で独立。問題があれば該当 PR を revert。develop への影響は局所。

## 18. テスト方針

| 対象 | 方法 |
|---|---|
| `GitLabRemoteParser` | TDD。VSCode テストケース移植(SSH カスタムポート/絶対パス/scheme+port/HTTPS/カスタムルート/host 照合) |
| `SearchQueryBuilder` | TDD。トークン(labels/author:me/assignee:me/milestone/title/基本テキスト)→ クエリ |
| `McpConfigService` | TDD。一時ディレクトリでパス解決・冪等生成・既存ディレクトリ衝突検出 |
| `GitLabProjectUrlResolver`(JGit 部) | 一時リポジトリで remote 設定 + コミット → blob URL 検証(JGit は headless 可)。**特殊文字(空白/`#`/`?`/`%`/非 ASCII/既存 escape)を含むファイル名の percent-encode ケースを含める**(Codex P1-c) |
| URL エンコード(パスセグメント) | TDD。予約文字・Unicode・二重エンコード回避・`/` 保持の純関数テスト |
| 高度な検索の分岐 | instance スコープはリポジトリ非依存で URL 生成、project スコープのみ解決を要する分岐をテスト(Codex P1-d) |
| resolver(選択/エディタ部)・各ハンドラ配線・ブラウザ・クリップボード・restart | 手動検証(PR 説明文に手順、実機 `equoIde`) |
| LS `restart()` アトミック性 | provider の状態機械ロジック(プロセス同一性ガード・executor 再生成・開始失敗時の停止確定)を、実プロセスに依存しない範囲でユニットテスト。実接続の復帰確認は手動(Codex P1-b) |

検証ゲート(Phase 1 と同一): 対象テスト PASS + 全体失敗数 36 維持 + 変更ファイル detekt 0 件。

参照実ソース(読み取り専用):
- `out/gitlab-vscode-extension/src/desktop/git/git_remote_parser.ts`(remote 解析)
- `.../src/desktop/commands/openers.ts`(blob URL・openActiveFile・issues/new)
- `.../src/desktop/commands/open_in_gitlab.ts`(openInGitLab / copyLink)
- `.../src/desktop/search_input.ts`(検索クエリ・スコープ)
- `.../src/desktop/mcp/utils/mcp_config.ts` / `mcp_workspace_config.ts`(MCP パス・テンプレ)
- Eclipse 側: `lsp/GitLabLanguageServerProcessProvider.kt`(start/stop、L52 共有 `process`・L68-71 `onExit` の無条件 null 化・L116 executor shutdown を確認)、`lsp/git/GitDiffService.kt`(JGit パターン)、`views/issues/IssuesView.kt`(ブラウザ起動パターン)、`api/GitLabApiClient.kt`(URL/設定パターン)

## 19. 受け入れ条件

- 手動検証(実機):
  - openActiveFile が選択行アンカー付きの正しい blob URL をブラウザで開く。copyLinkToActiveFile がクリップボードに同 URL を入れる。特殊文字を含むファイル名でもリンクが壊れない。
  - openInGitLab(**プロジェクトのみ**)・copyLinkToClipboard が正しく動く。ファイル(blob)対象は専用コマンド openActiveFile/copyLinkToActiveFile で検証する(上項)。コミット対象は Phase 3 送り。
  - 新規 Issue・Issue 検索・MR 検索の各ブラウザ遷移が VSCode と一致。
  - **高度な検索**: リポジトリ未オープン/複数リポジトリでも instance スコープで成功する。project スコープ選択時のみプロジェクト解決が働く。
  - MCP user/workspace 設定がファイル生成 + エディタで開く。2 回目は上書きしない。
  - **LS 再起動**: 単発再起動でチャット/コード提案が復帰する。連続再起動しても状態が壊れない。開始失敗時は停止状態に確定し再試行できる。
- 自動テスト: §18 の TDD 対象が PASS、全体失敗 36 維持、detekt 0 件。
- パリティ台帳 #7 の該当行(D8/D10/D11/D16)を更新(`gl.openInGitLab` のコミット対象と `gl.status.issue` は Phase 3 送りである旨を注記)。

## 20. 実装分割の見通し

Phase 2 は独立 3 クラスタ。実装は 3 PR に分割予定(最終確定は実装計画時):

| PR | 内容 | 規模 |
|---|---|---|
| PR-1 | navigation 基盤(RemoteParser + ProjectUrlResolver + SearchQueryBuilder + openers + search + issue create) | 大 |
| PR-2 | MCP 設定オープン(McpConfigService + 2 ハンドラ) | 小 |
| PR-3 | LS 再起動ハンドラ | 小 |

いずれも base=`develop`・実コードのみ。

## 21. 未決事項

- **U-1**: リポジトリ選択で複数 GitLab リポジトリがある場合の選択 UI(SWT リストダイアログ)の具体仕様。VSCode の `run_with_valid_project` 相当を Eclipse でどこまで踏襲するか。実装計画で確定。
- **U-2(確定・Codex P1-a/P1-new-1)**: `openInGitLab`/`copyLinkToClipboard` の起動契約は §9 決定表で確定。**対象はプロジェクトのみ**(ファイル対象は専用コマンド `openActiveFile`/`copyLinkToActiveFile` に委譲、コミット対象は Phase 3 送り)、起動口=コマンド/プロジェクト右クリック。
- **U-3(確定・Codex P1-b)**: LS 再起動は provider のアトミック `restart()`(§8:単一ロック + プロセス同一性ガード + executor 再生成 + 開始失敗時の停止確定)で実装。bundle 取得は `FrameworkUtil.getBundle` を用いる。残る詳細(世代トークン vs 参照比較)は実装時に選択。
- **U-4**: 高度な検索のスコープ表(GitLab.com vs self-managed)の維持責務を定数としてどこに置くか(`navigation` 内の定数オブジェクト想定)。実装計画で確定。

## 22. 想定されるリスク

- **R-1(中)**: `GitLabRemoteParser` の正規表現移植漏れ。多様な remote 形式で誤解析 → 誤 URL。→ VSCode テストケース網羅移植で緩和。
- **R-2(中)**: UI スレッド境界の誤り(headless で検出不能・実機のみ露見)。→ 選択取得を UI、JGit を BG に分離し `syncExec` 不使用。実装/レビューを UI スレッド系に強いモデルで担当。
- **R-3(低)**: プロジェクト改名時に構築 URL が旧パス。→ GitLab の 301 リダイレクトで着地するため実害小(§2 決定事項)。
- **R-4(低)**: MCP 設定の排他生成でのレース。→ `wx` 相当 + EEXIST 尊重で冪等。
- **R-5(低)**: `status.issue` 繰り延べにより Phase 2 完了時点で #10 の 1 項目が未達。→ Phase 3 で MR 依存とともに実装する旨を台帳に明記。
