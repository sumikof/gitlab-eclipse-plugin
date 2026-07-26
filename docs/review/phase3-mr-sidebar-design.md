# Phase 3 設計書: MR ドメイン + サイドバー(ツリービュー)基盤

- 対象リポジトリ: `sumikof/gitlab-eclipse-plugin`(Gradle/Kotlin、`com.gitlab.eclipse.*`)
- 親ロードマップ: #8 / パリティ台帳: #7 / フェーズ issue: #11
- パリティ基準: VSCode `gitlab-workflow` v6.85.3(参照コピー `/workspace/out/gitlab-vscode-extension`、読み取り専用)
- ベースブランチ: `develop`(tip `1e32d4c`)
- 本書はレビュー専用。実装コードではなく **システム設計書** としてレビューする。実装 PR・マージ先には含めない。
- **改訂履歴**:
  - rev2 = Codex round 1(P1×11)反映。主な追加: リポジトリ選択規則(§6.1)、git 認証/操作基盤(§7.1)、URL エンコード規則(§10.1)、根ごとの障害分離 refresh(§8.1)、ブランチ→MR の tracking フォールバック + source project 照合(§8.3)、checkout の remote 結合・多重実行排除・段階別復旧(§8.4/§12/§16)、openMrFile の repo 限定(FR-8)、変更ファイルノード型(§7)。
  - rev3 = Codex round 2(P1×6)反映。主な追加: SSH transport 依存の明示(§7.1)、ピッカーが返す `RepositoryContext` 契約(§6.1/§11)、fork MR を global lookup + source_project_id 照合に変更(§8.3/§10)、checkout の最終 HEAD SHA 検証 + 既存ブランチ ff/reset/拒否(§8.4/§16)、push 直接 URL 経路の upstream-remote 一致検証(§8.5)、openMrFile の repo HEAD ↔ MR revision 検証(§8.6)。
  - rev4 = Codex round 3(P1×5)反映。主な確定: source_project_id は数値 id 照合に**確定**(U-9 解決、§8.3)、closes_issues は MR の target project_id を使用(§8.3)、tracking 名は `branch.<n>.remote` が解決 remote と一致時のみ採用(§8.3)、fetch SHA 不一致は再取得/中止で誤成功を排除(§8.4)、SSH factory は対象 Transport 限定(`TransportConfigCallback`/`SshTransport`、プロセス全体を置換しない、§7.1)。
  - rev10 = Codex round 8(P1×1)反映。Tycho の p2 repositories は Gradle と別(root pom.xml:131-141 = 2024-09 + gitlab-maven のみ、EGit p2 は Gradle 専用)。SSH バンドルの Tycho 解決は 2024-09 の IU 有無次第で、無ければ pom.xml への p2 追加(要ユーザー承認)が SSH 出荷の必須前提。U-10/R-7 を精緻化(§7.1/§17/§21/§24)。
  - rev9 = Codex round 7(P1×1)反映(rev8 の自己訂正)。jgit core の供給機序を実コードで再確認し訂正: fat-jar 同梱は `kotlinLibraries` 名一致のみ(build.gradle.kts:214 の `.filter`、jgit/guava 非該当)。jgit core は `eclipseDependencies`→`Require-Bundle`+p2deps(既存 EGit p2 repo:188)で供給。SSH バンドルも同 OSGi 経路に確定(feature.xml でも fat-jar でもない)、素 jar 推移依存のみ kotlinLibraries fat-jar(§7.1/§17/§21/§24)。
  - rev8 = Codex round 6(P1×1)反映(供給機序を誤って fat-bundle と記載 → rev9 で訂正)。Tycho 出荷 update-site への供給・解決性を U-10 + R-7 として明記。
  - rev7 = U-8 をユーザー確定(2026-07-26、SSH 第一案採用)。SSH 対応を確定事項化し縮小案の分岐記述を整理(§7.1/§9-h/§17/§23/§24)。
  - rev6 = Codex round 5(P1×1)反映。SSH 第一案採用時の ssh-agent 認証に `org.eclipse.jgit.ssh.apache.agent`(+ ランタイム依存)を feature.xml/OSGi に追加、含めない場合は agent-only 非対応を通知する契約(§7.1/§17)。
  - rev5 = Codex round 4(P1×6)反映。主な追加: 候補 repo を正規化 gitDir で重複排除(§6.1/§8.6)、REST project id は既エンコード済み `namespaceWithPath` を再エンコードせず `/`→`%2F` のみ(§10/§11、Phase 2 PR#28 の二重エンコード修正と整合)、push は `PushResult` の `RemoteRefUpdate.Status` が OK/UP_TO_DATE の時だけ成功扱い(§8.5)、openMrFile は head SHA の厳密一致 + real-path 包含検査(§8.6)、§25 の U-9 矛盾を解消。

---

## 1. 背景と目的

公式 GitLab Eclipse プラグインを拡張し、VSCode 拡張との機能パリティを進めている(現況 31/85 ≈ 36%)。Phase 2 までで Duo Chat・ナビゲーション(git remote → GitLab URL 解決)・LS 再起動・MCP 設定が揃った。Phase 3 は **Merge Request(MR)ドメイン**(台帳 D12 全7件)と、以降のフェーズ(CI 等)の器となる **サイドバー(ツリービュー)基盤**(D17 全2件)を実装し、あわせて Phase 2 で唯一繰り延べた **`gl.status.issue`**(D11)を本来の姿で解消する。

目的:
- VSCode の MR 関連 9 コマンドを Eclipse 上で忠実に(または意図的差異を明示して)再現する。
- 既存 `IssuesView`(フラットな `TableViewer`)を、複数のクエリ根と現ブランチ情報を扱える再利用可能な **`TreeViewer` サイドバー基盤** に一般化する。

## 2. 対象範囲(スコープ)

台帳 #7 の以下 ❌9件 + 繰り延べ1件:

- **D12(MR、7件)**: `gl.showMergeRequestsAssignedToMe` / `gl.openCurrentMergeRequest` / `gl.openCreateNewMR` / `gl.checkoutMrBranch` / `gl.openMrFile` / `gl.compareCurrentBranch` / `gl.status.mr`
- **D17(サイドバー、2件)**: `gl.refreshSidebar` / `gl.sidebarViewAsList`・`gl.sidebarViewAsTree`
- **D11(繰り延べ、1件)**: `gl.status.issue`(現ブランチの MR が閉じる Issue)

PR 分割(3 本、`develop` ベース):

| PR | 主眼 | 提供機能 |
|---|---|---|
| PR-1 基盤 | サイドバー一般化 + repo 選択/git 基盤の骨格 | D17×2 + `showMergeRequestsAssignedToMe` |
| PR-2 現ブランチ | 「For current branch」節 + ブランチ文脈コマンド | `status.mr` + `status.issue` + `openCurrentMergeRequest` / `openCreateNewMR`(push 含む) / `compareCurrentBranch` |
| PR-3 MR 深掘り | MR 展開 + git 連携 | `checkoutMrBranch` + `openMrFile` |

## 3. 対象外(Non-goals)

- **GraphQL 層の新設**(Phase 5 #13)。Phase 3 は既存 REST `GitLabApiClient` のみで完結させる。既定ブランチは REST `GET /projects/:id` の `default_branch` で取得し、VSCode が `compareCurrentBranch` で用いる GraphQL `GetProjectWithRepositoryInfo.rootRef` は使わない。
- **MR インライン・ディスカッション/コメント**(VSCode は GraphQL `GetMrDiscussions` + CommentController で diff エディタにスレッド表示)。Phase 5(MR レビュー)の領分。Phase 3 の MR ノードはコメントを扱わない。
- **MR webview(`gl.showRichContent`)**。VSCode はステータスバー/ツリーから MR webview を開くが、Phase 3 では該当箇所は**ブラウザで MR を開く**(`web_url`)に置換する(意図的差異、§20)。
- **プロジェクト別カスタムクエリ(`gitlab.customQueries` 設定)** の完全再現。Phase 3 は「assigned to me」を global スコープ REST で提供する(§9-b)。カスタムクエリは後続強化。
- **サイドバー表示モードの永続化**。VSCode も非永続(起動時 list リセット、extension.ts:621)なので永続化しない。
- 30 秒ポーリング更新(VSCode `CurrentBranchRefresher`)。Eclipse ではオンデマンド更新にする(§10)。

## 4. 現在の課題

- 既存 `IssuesView` は単一の `TableViewer` + `ArrayContentProvider` で、Issue のフラット一覧しか扱えない(`views/issues/IssuesView.kt`)。複数クエリ根・現ブランチ情報・list/tree 切替を載せられない。
- MR ドメインの REST サービス・モデルが未実装。
- Phase 2 で `gl.status.issue` は「現ブランチの MR が閉じる Issue」という MR 依存のため繰り延べられた(#7 D11)。MR 基盤が前提。
- Phase 2 の git 利用は **read-only ローカル git**(remote 解析のみ、ネットワーク・認証なし)。Phase 3 は fetch/checkout/push を伴い、**git トランスポートと認証**が新たに必要(§7.1)。

## 5. 要件

### 機能要件

- **FR-1(showMergeRequestsAssignedToMe)**: 自分にアサインされた MR 群をブラウザで開く。VSCode は `${projectUrl}/-/merge_requests?assignee_id=${userId}`(openers.ts:53-70、`GET /user` で userId 取得)。加えてサイドバーに「MRs assigned to me」根ノードを設け、global REST で取得したフラット一覧を表示する。`projectUrl` は §6.1 の選択リポジトリ由来。
- **FR-2(サイドバー基盤 / refreshSidebar / list-tree)**:
  - 再利用可能な `TreeViewer` サイドバー(単一「GitLab」ビュー)。根セクション: クエリ根(Issues assigned / MRs assigned)+(PR-2 で)For current branch。
  - `gl.refreshSidebar` = 全ノード再取得・再描画(VSCode extension.ts:248-254 = full reload)。根ごとに障害分離(§8.1)。
  - `gl.sidebarViewAsList` / `gl.sidebarViewAsTree` = 表示モード切替。**Eclipse での意味(意図的適応)**: list=フラット項目、tree=プロジェクト別グループ化。VSCode ではトグルは「MR 配下の変更ファイル階層」だけを切替えるが、変更ファイルは PR-3 まで存在しないため、PR-1 ではトグルにプロジェクト別グループ化の意味を与え、PR-3 で MR 配下の変更ファイル階層切替にも拡張する(§20 差異記録)。ツールバーに tree ⇄ list の相互トグルボタン(VSCode desktop.package.json:671-679 に相当)。状態は非永続・既定 list。
- **FR-3(openCurrentMergeRequest)**: §6.1 で選択したリポジトリの現ブランチの open MR(§8.3)をブラウザで開く。VSCode は無ければ silent no-op(openers.ts:125-134)。**Eclipse では情報通知「No merge request found for the current branch.」を出す**(U-2 解決: silent より発見性を優先。差異を §20 に記録)。
- **FR-4(openCreateNewMR)**: §8.5 の push 契約に従う。現ブランチに upstream が無く worktree が clean なら **プラグインが push(set-upstream)** し(VSCode create_mr.ts:11-52 と同じ実行主体)、完了後に作成 URL を開く。dirty なら Team/SCM への誘導通知(自動 push しない)。upstream があれば直接 `${webUrl}/-/merge_requests/new?merge_request%5Bsource_branch%5D=${enc(branch)}` をブラウザで開く(§10.1 エンコード)。
- **FR-5(compareCurrentBranch)**: `${webUrl}/-/compare/${enc(defaultBranch)}...${enc(headSha)}` をブラウザで開く。既定ブランチは REST `default_branch`、headSha はローカル git HEAD。ref は §10.1 に従いエンコード(`...` は区切りとしてリテラル)。
- **FR-6(status.mr / status.issue → For current branch 節)**: §6.1 選択リポジトリの現ブランチ MR とその閉じる Issue を「For current branch」節に表示。MR ノードのクリックで MR をブラウザで開く、Issue ノードのクリックで Issue をブラウザで開く。MR 無し・Issue 無しはそれぞれ「No merge request found」「No closing issue found」相当のメッセージノード(VSCode current_branch_data_provider.ts:144-175)。
- **FR-7(checkoutMrBranch)**: MR ノードのコンテキストメニューから、同一プロジェクト MR の source ブランチをローカルに checkout(§8.4)。fork MR は対象外(ボタン非表示)。§7.1 の git 操作直列化に従い、実行中は再実行不可。
- **FR-8(openMrFile)**: MR の変更ファイルノードから、**その MR に対応する唯一の workspace repository** 内のローカル実ファイルを開く(§8.6)。MR の project/`source_project_id` を各 workspace remote と照合して repo を一意特定し、正規化後パスが work tree 内に収まることを検証。未 checkout/削除/rename/多重一致/未対応 repo は開かず理由を通知(VSCode open_mr_file.ts:41-49 を安全側に強化)。

### 非機能要件

- **既存パターン踏襲**: coroutine fetch → `control.display.asyncExec` で presenter 反映、世代 latest-wins(`ViewRefreshState`)、`never-throws` を共有 `CoroutineScope` 汚染防止のため各 launch を try/catch で保護。
- **UI スレッド規律**: SWT 部品操作は UI スレッド。バックグラウンド(coroutine)では API/git のみ。全 sink は自マーシャル(`currentDisplay.asyncExec`)。
- **detekt 0 / headless suite の失敗数は SWT ベースライン 36 のまま**。純ロジックは TDD。

## 6. 前提条件と制約

- ディレクトリ構成変更禁止(既存 `com.gitlab.eclipse.*` 内に追加のみ)。ビルドシステム変更禁止(Gradle + update-site。必須依存追加は PR で明示)。
- プロトコル定数(REST パス/クエリ)は実装前に実ソースで確定済み(本書 §10 に VSCode `file:line` 付きで記載)。
- 認証は既存 `GitLabTokenProviderManager`(Bearer)。instance URL は `PreferenceConstants.GITLAB_INSTANCE_URL`。
- git 操作は JGit(Phase 2 と一貫、`org.eclipse.jgit`)。プロジェクト識別は Phase 2 の `GitLabRemoteParser` / `GitLabProjectUrlResolver`(git remote → `namespaceWithPath` + instance + **一致 remote 名**)を流用。
- headless devcontainer では SWT/JGit-transport(fetch/checkout/push)/実 LS は動かない → UI・git 系は実機のみ検証(手動検証手順を PR に記載)。

### 6.1 操作対象リポジトリの選択規則(全 MR/status/compare/checkout/openMrFile 共通)

単一グローバルサイドバーのため、コマンドが作用するリポジトリを一意に決める規則を定義する(誤って別プロジェクトへ push/checkout する事故を防ぐ)。**選択はコマンド起動時(UI スレッド、非同期開始前)に確定し、以降の非同期処理はその固定値を用いる**。

優先順位:
1. **アクティブエディタの入力が `IFile` を持つ場合**、その `IProject` に対応する git リポジトリ(Phase 2 の repo 検出を流用)。
2. アクティブエディタが無い/`IFile` を持たない場合、ワークスペースの git リポジトリを列挙する。**列挙は正規化した `gitDir`(`.git` 実パス)で重複排除する**: 既存 `WorkspaceProjectPicker.workspaceRepoDirs()` は各 `IProject` を列挙してから `findGitDir` するため、1 つのマルチモジュール Git リポジトリを複数 `IProject` としてインポートしたワークスペースでは同一 repo が複数候補になる。重複排除後の件数で分岐:
   - 0 件 → 中止し通知「No GitLab repository found in the workspace.」。
   - 1 件 → それを採用。
   - 複数件 → **明示ピッカー**(Phase 2 `WorkspaceProjectPicker` = `ElementListSelectionDialog` を流用、ただし gitDir 重複排除後の一覧)。キャンセルは無操作(silent)。
3. ツリーノード起点のコマンド(`checkoutMrBranch`/`openMrFile`)は、選択された **MR ノードが保持する repo 結合**(§8.4/§8.6)を用い、上記より優先する。

選択したリポジトリから、Phase 2 resolver で `namespaceWithPath` + instance + 一致 remote 名を解決する。resolver が GitLab remote を見つけられない場合は中止し通知。**受け入れ条件に複数リポジトリ・エディタ無し・resolver 失敗の各ケースを含める**。

**ピッカー/解決の返却契約 = `RepositoryContext`**: Phase 2 の `WorkspaceProjectPicker.pickWebUrl` / `GitLabProjectUrlResolver.Resolution.Ok` は **URL 文字列のみ**を返し、後続の push/checkout が必要とする repo path/remote 名が失われる。そのまま流用すると「非同期前に固定」を保証できず、後段で再列挙/再解決が必要になる。したがって repo 選択の結果として、以下を一体で保持する **`RepositoryContext`** を返す:
- `gitDir`(`.git` の正規化パス = repo identity、`GitOperationGuard` のキー)
- `workTree`(work tree ルート)
- `namespaceWithPath` / `instanceUrl` / `webUrl`
- `remoteName`(解決した一致 remote 名)
- `projectId`(REST 用にエンコードした `namespace/project`)

`RepositoryContext` はコマンド起動時に一度だけ生成して以降の非同期処理・ツリーノード結合(§8.4/§8.6)へ渡す。既存の URL のみを返す Phase 2 API は変更せず、context を返す**新しいオーバーロード/バリアント**を追加する(既存挙動不変)。

## 7. システム構成(コンポーネント)

パッケージ配置(すべて既存体系内の追加):

- **`com.gitlab.eclipse.api`(REST 拡張)**
  - `CurrentUserService`: `GET /user` → `GitLabUser`。
  - `MergeRequestService`: assigned-to-me 一覧 / ブランチ→MR / 閉じる Issue / (PR-3)変更ファイル versions。
  - `ProjectDetailService`: `GET /projects/:id` → `default_branch`。
  - モデル: `GitLabMergeRequest`、`GitLabUser`(部分フィールド)。既存 `GitLabApiClient`(REST・ページング・Bearer)を利用。単一 GET 用に `fetchObject`(単一オブジェクト)を `GitLabApiClient` に追加(現状は `fetchListFromApi` のみ)。
- **`com.gitlab.eclipse.views.sidebar`(新規・ツリー基盤)**
  - `SidebarView : ViewPart`(`TreeViewer`)。
  - ノードモデル `SidebarNode`(sealed): `QueryRootNode` / `MergeRequestNode` / `IssueNode` / `CurrentBranchSectionNode` / `ProjectGroupNode` / `MessageNode`(空/エラー/ローディング)/ **`ChangedFileNode`** / **`ChangedDirectoryNode`**(§7.2)。
  - `SidebarContentProvider : ITreeContentProvider`、`SidebarLabelProvider`。
  - `SidebarViewMode`(enum list/tree)+ `SidebarViewState`(モジュール状態 + 変更イベント)。
  - `SidebarViewModel`(純ロジック): クエリ結果 → 根/子ノード構成、list/tree グルーピング、latest-wins 世代、**根ごとの Result 合成**(§8.1)。**TDD 対象**。
  - 既存 `views/issues/IssuesView` を本基盤に吸収(§18 移行)。
- **`com.gitlab.eclipse.mergerequests`(新規・ハンドラ + git)**
  - ブラウザ系ハンドラ: `OpenCurrentMergeRequestHandler` / `OpenCreateNewMrHandler` / `CompareCurrentBranchHandler` / `ShowMergeRequestsAssignedToMeHandler`。Phase 2 `BrowserLauncher` を再利用。
  - git 系: `CheckoutMrBranchHandler` + `MrBranchCheckoutService`(PR-3、JGit)、`BranchPushService`(PR-2、JGit push)。
  - ブランチ→MR 解決: `CurrentBranchMrLookup`(tracking/フォールバック解決 → REST source_branch フィルタ → source project 照合 → updated_at 降順先頭)。
  - `MrUrlBuilder`(§10.1 のブラウザ URL 生成・純ロジック・TDD)。

### 7.1 git 操作 / 認証 / トランスポート基盤(fetch・checkout・push 共通)

Phase 3 で初めてネットワーク git 操作を行うため、共通基盤を定義する。

- **認証(HTTPS remote)**: JGit `CredentialsProvider` を、GitLab トークン(`GitLabTokenProviderManager.getToken()`)を用いて構成(username=`oauth2`、password=token)。instance ホストと remote ホストが一致する場合のみ適用(他ホストには適用しない)。**トークンを含む URL やログ出力はしない**(§15)。
- **SSH transport(必須依存の追加・明示)**: JGit core だけでは SSH 通信を開始できない(`SshSessionFactory` 実装が無いと fetch/push が実行時 transport error)。したがって SSH remote を扱うには **`org.eclipse.jgit.ssh.apache`(Apache MINA sshd ベース)を必須依存として追加**する。**プロセス全体の静的置換(`SshSessionFactory.setInstance(...)`)はしない**(同一 JVM の EGit や他プラグインの factory を奪い、認証・proxy・host-key 処理を壊すため)。代わりに、当該 `FetchCommand`/`PushCommand` の **`setTransportConfigCallback` から対象 `SshTransport` にだけ** プラグイン所有の `SshdSessionFactory` を設定し、ライフサイクルもプラグイン内に閉じる。鍵/エージェント/known_hosts はユーザーの `~/.ssh`(および ssh-agent)を用いる。self-managed 環境の host-key 検証(未知ホストの扱い)は実機検証項目(§21 R-3)。
  - **依存追加の機序(実コード確認済み・確定)**: 供給機序は依存の性質で 2 系統に分かれる(build.gradle.kts で確認):
    - **OSGi バンドルとして提供されるもの(jgit core 等)= `Require-Bundle` + p2deps**。`eclipseDependencies`(build.gradle.kts:158-182、`org.eclipse.jgit` を含む:179)が manifest の `Require-Bundle`(:231-233)に入り、`p2deps`(:184-194)が **EGit の p2 repo(`https://download.eclipse.org/egit/updates/`、:188 に既存)** 等から解決する。jgit core は feature.xml 非掲載・fat-jar 非同梱で、この OSGi 経路で出荷される。
    - **非 OSGi の素 jar(kotlin/coroutines/koin/slf4j/log4j/scribejava/nanohttpd)= fat-jar 同梱**。jar タスクは `kotlinLibraries` 名一致の依存**だけ**を `runtimeClasspath` から `zipTree` して同梱(build.gradle.kts:200-216 の `.filter`)。**jgit/guava はこのリストに無い**。
  - **SSH バンドルの供給(確定)**: `org.eclipse.jgit.ssh.apache` と `org.eclipse.jgit.ssh.apache.agent` は **EGit が p2 で配布する OSGi バンドル**。したがって **jgit core と同一の Require-Bundle + p2deps 経路**で供給する(`eclipseDependencies` に追加 → `Require-Bundle`、既存の EGit p2 repo:188 から解決)。**feature.xml 追加でも kotlinLibraries fat-jar でもない**。Apache MINA sshd の推移依存(`org.apache.sshd.osgi` 等)も OSGi バンドルなら同経路、素 jar しか無いものがあれば `kotlinLibraries` リストへ追加して fat-jar 同梱、と依存ごとに振り分ける。
  - **Tycho 出荷 update-site への供給(repo は Gradle と別)**: **EGit p2 repo(build.gradle.kts:188)は Gradle `p2deps` 専用**で Tycho には効かない。Tycho reactor の repositories は **`2024-09`(`https://download.eclipse.org/releases/2024-09`)+ `gitlab-maven` のみ**(root pom.xml:131-141)。jgit core は現状 Tycho 側では 2024-09 が提供する 7.0 系を Require-Bundle 範囲 `[7.0.0,8.0.0)` で解決している(Gradle の Maven pin 7.5 とはバージョンスキューだが range で許容)。**SSH バンドルも同 range で Require-Bundle するが、`2024-09` が `org.eclipse.jgit.ssh.apache(.agent)` + `org.apache.sshd.osgi` の IU を含むか否かが分岐点**(含めば追加設定不要、含まなければ Tycho 解決が失敗)。含まない場合は **root `pom.xml` に p2 リポジトリ(EGit 等)を追加**する必要があり、これは**ビルド構成変更に当たるためユーザー明示承認が前提**(§21 R-7、U-10 で確認)。
  - **ssh-agent 認証の依存(JGit 7.5)**: 鍵を `ssh-agent` のみに登録した環境では `org.eclipse.jgit.ssh.apache` 単体では agent connector が無い。**`org.eclipse.jgit.ssh.apache.agent`(+ そのランタイム)**を上記機序で供給し、公開鍵ファイル + agent 双方をカバーする。
  - **決定(U-8 確定 = SSH 第一案 / パリティ確保)**: 上記 SSH 対応を採用する(ユーザー確定 2026-07-26)。HTTPS-only 縮小案は不採用。したがって `org.eclipse.jgit.ssh.apache`(+ ssh-agent 用 `.agent`)を必須依存として追加する。
- **SSH remote**: 上記 `SshdSessionFactory`(対象 Transport 限定)に委譲(トークンは使わない)。
- **操作直列化(`GitOperationGuard`)**: **リポジトリ identity(`.git` ディレクトリの正規化パス)をキー**に、mutating git 操作(fetch+checkout、push)を直列化する in-memory レジストリ。処理中は同一 repo の該当 action を無効化し、重複要求は拒否(または同一 Job に合流)。並行実行テストを課す(§22)。
- **実行文脈**: すべて `service<CoroutineScope>()`(Dispatchers.IO)上で実行し UI を塞がない。進捗は `IProgressService`/`Job` + 通知、キャンセル対応。本体は try/catch(共有スコープ汚染防止)。

### 7.2 変更ファイルノード(PR-1 で拡張点を宣言、PR-3 で実装)

sealed `SidebarNode` に PR-3 の受け入れ条件(MR 展開・変更ファイル list/tree・openMrFile)を表現できる型を **PR-1 時点で定義**し、PR-3 での基盤再設計の手戻りを防ぐ:

- `ChangedFileNode`: `oldPath`/`newPath`、変更種別(new/deleted/renamed/modified)、diff version id(base/head commit sha)、活性化動作(openMrFile 可否)、list 表示時のディレクトリラベル。
- `ChangedDirectoryNode`: 子ノード(単一子ディレクトリは連結して畳む)、tree 表示時のみ生成。
- MR ノード配下の loading/error は `MessageNode` で表現。
- **list/tree 親子規則**: list=フラット `ChangedFileNode`(ディレクトリはラベル補足)、tree=`ChangedDirectoryNode` 階層。`SidebarViewModel` がこの変換を担う(TDD)。

## 8. 処理フロー(主要シナリオ)

### 8.1 サイドバー初期表示 / refresh(根ごとの障害分離)
1. `SidebarView.createPartControl` で `TreeViewer` + content/label provider 構築、`SidebarViewState` 購読、初回 `refresh()`。
2. `refresh()`: 世代 begin、進行中 fetchJob キャンセル、`coroutineScope.launch { … }`(本体 try/catch で共有スコープ保護)。**各根は独立に取得**する: `supervisorScope` 下で根ごとに `async { runCatching { fetch() } }` を起動し、根単位で成功/失敗を `Result` として集約。**先頭根(Issues)の失敗が他根(MRs / current-branch)の取得を止めない。** `CancellationException` は失敗表示せず再送出(協調キャンセル)。
3. 取得後 `control.display.asyncExec { viewModel.apply(generation, perRootResults); viewer.refresh() }`(disposed/世代ガード)。失敗根のみメッセージノード化し、他根は保持。
4. `gl.refreshSidebar` ハンドラ → `SidebarView.refresh()`(全リロード)。

### 8.2 list/tree 切替
1. `gl.sidebarViewAsTree`/`AsList` ハンドラ → `SidebarViewState.setMode(mode)`。
2. `setContext`(トグルボタン表示切替相当)+ 変更イベント発火 → 購読中の `SidebarView` が `viewModel.regroup(mode)` + `viewer.refresh()`(再取得不要、保持データを再構成)。

### 8.3 現ブランチ MR / 閉じる Issue(For current branch、PR-2)
1. §6.1 でリポジトリ確定。**ブランチ解決**(`CurrentBranchMrLookup`): HEAD がブランチを指す場合、tracking ブランチ名を `branch.<name>.merge`(`refs/heads/` 除去)から取得。ただし **`branch.<name>.remote` が解決 remote(`RepositoryContext.remoteName`)と一致する場合のみ** tracking 名を採用する。別 remote を追跡している(例: ローカル `feature` が別 remote の `review` を追跡)場合や **tracking 未設定** の場合は **HEAD のローカル短縮名にフォールバック**(誤 remote のブランチ名で解決 project を検索して見失うのを防ぐ)。**detached HEAD は「No merge request found」**(通知/メッセージノード)。
2. プロジェクト解決(§6.1、`RepositoryContext.projectId`)。**MR 検索は global エンドポイント** `GET /merge_requests?scope=all&state=opened&source_branch=<enc(branch)>` を用いる。**理由(fork 対応)**: 選択 repo が fork の場合、そのブランチの MR は upstream(target)project に属し、fork の project-scoped `/projects/:forkId/merge_requests`(:id は target 側を返す)では取得できない。global lookup なら target project に依らず、source 側のブランチから MR を発見できる。VSCode の project-scoped lookup(mr_lookup_helpers.ts)からの**意図的な correctness 改善**(§20)。
3. 結果を絞り込み: `state=='opened'`(設定 `showClosedMergeRequests` は Phase 3 対象外、常に opened)、かつ **`source_project_id == 解決した数値 project id`**(別 fork の同名 source_branch MR を除外)。残候補が複数なら updated_at 降順先頭を採用、可能なら head SHA がローカル HEAD と一致するものを優先。0 件 → 「No merge request found」。
   - **数値 id 照合(U-9 確定)**: `source_project_id` は数値 id。fork→upstream MR では `references.full`/`web_url` は **target** project を表すため namespace 照合では正しい MR を除外してしまう(fork 対応が壊れる)。したがって **`GET /projects/:id`(:id=`RepositoryContext.projectId`)で選択 repo の数値 `id` を一度取得し、`source_project_id` と比較する**契約に確定する(namespace 照合案は不採用)。
4. MR があれば閉じる Issue を取得: **`GET /projects/{mr.projectId}/merge_requests/{mr.iid}/closes_issues`**。ここは `RepositoryContext.projectId`(source/fork)ではなく **発見した MR の `projectId`(= target project、`iid` が属する project)** を用いる(fork MR で 404 や別 MR 参照を避ける)。
5. 「For current branch」節に MR ノード + Issue ノード(無ければメッセージノード)。ケース(未 push=tracking 無し・push 済み・複数 remote)を受け入れ条件に含める。

### 8.4 checkoutMrBranch(PR-3)
1. MR ノードのコンテキストメニュー(**同一プロジェクト MR = `source_project_id == target_project_id` のみ表示**)→ `CheckoutMrBranchHandler`。MR ノードは §8.6 の repo 結合(解決済み repo + remote 名)を保持。
2. `GitOperationGuard`(§7.1)で当該 repo をロック(重複起動拒否・action 無効化)。
3. **事前条件チェック**(§16): merge/rebase/cherry-pick 進行中なら中止し通知。dirty/untracked による checkout 競合は JGit 例外で検出し通知(強制上書きはしない)。
4. `MrBranchCheckoutService`(bg): **MR 結合の remote 名**(`RepositoryContext.remoteName`)から明示 refspec `+refs/heads/<source_branch>:refs/remotes/<remote>/<source_branch>` で `FetchCommand`(§7.1 認証)。**fetched remote-tracking SHA を `mr.sha` と照合**。**不一致(MR 取得後の force-push 等)の場合は checkout を進めない**: MR レコードを一度再取得して SHA を更新・再照合し、なお不一致なら操作を中止して通知する(最新 remote tip を MR revision と誤認して checkout し「成功」と報告しない)。一致した場合のみ次へ。
5. ローカルブランチの整合(**最終 HEAD SHA を検証するまで成功にしない**):
   - **同名ローカルブランチが無い** → `CheckoutCommand.setCreateBranch(true).setStartPoint(remote-tracking)` で作成・切替。
   - **同名ローカルブランチが有る** → その先端と fetched remote-tracking SHA の関係を判定: **fast-forward 可能(remote が local の子孫)** なら切替 + ff、**一致** ならそのまま切替、**diverge(local に固有 commit)** なら**自動 reset せず**、警告して続行可否をユーザーに委ねる(silent に古い branch を checkout しない)。
6. checkout 後、**HEAD 名の検証に加えて最終 HEAD SHA が `mr.sha`(= step 4 で照合済みの fetched SHA)と一致することを検証**。一致で「Branch changed」通知、不一致(既存 diverge を維持した等)は「out of sync」警告(誤成功通知を出さない)。
7. 失敗は §16 の段階別復旧に従い通知。

### 8.5 openCreateNewMR の push 契約(PR-2)
1. §6.1 でリポジトリ確定(`RepositoryContext`)、現ブランチ・upstream の有無と**指す先**を判定。
2. **upstream が解決済み GitLab remote(`RepositoryContext.remoteName`)に属し、かつ upstream ref == `refs/heads/<branch>`** の場合のみ → ブラウザで作成 URL(§10.1)を直接開く(git 操作なし)。
3. **upstream が別 remote(別 GitLab/GitHub/別名)や別ブランチを指す、または upstream 無し + worktree dirty** → 選択 GitLab remote にその source branch が存在しない/不確実なので、**直接 URL を開かない**。dirty は「Commit and push the branch before creating a merge request.」通知 + Team/SCM 誘導。非 dirty で upstream 不一致は、選択 remote への push(下記 4)または明示確認(「Push <branch> to <remoteName> for the merge request?」)を経る。
4. **選択 remote へ push が必要な場合(upstream 無し + clean、または upstream 不一致で確認 OK)** → `GitOperationGuard` でロックし、`BranchPushService` が **`RepositoryContext.remoteName` の remote** へ `refs/heads/<branch>:refs/heads/<branch>` を push(§7.1 認証、`setUpstream`)。進捗・キャンセル対応。**push の成否は `PushResult` の対象 ref の `RemoteRefUpdate.Status` で判定**する(JGit `PushCommand.call()` は non-fast-forward・protected branch・server hook 拒否で**例外を投げず** `REJECTED_*` を正常 return しうるため、「call が返った」を成功扱いしない)。**`OK` または `UP_TO_DATE` の場合のみ** upstream(`branch.<n>.remote`/`.merge`)設定 → 作成 URL を開く。`REJECTED_*` 等は **upstream 不変の失敗**として通知(再実行=再 push、push は冪等)。
5. 誤 remote 防止のため push/直接 URL の判定は常に §6.1 の解決 remote を基準にする(既定 remote 名や任意の upstream に依存しない)。

### 8.6 openMrFile の repo 限定(PR-3)
1. 対象 MR の変更ファイルノードから起動。**workspace repository は正規化 gitDir で重複排除**(§6.1 と同じ理由: マルチモジュール repo が複数 `IProject` として現れ複数一致するのを防ぐ)したうえで、MR の `project_id`/`source_project_id`(数値、§8.3 の数値 id 照合方式)を各 repo の解決済み数値 project id と照合し、**一意な repo** を特定。0/複数一致は開かず理由を通知。
2. **MR revision の厳密確認(SHA 一致必須)**: ファイル存在やブランチ名一致では不十分(同名ブランチ checkout 後に MR が force-push されると古い worktree の版を現行 MR 版として開く)。**`ChangedFileNode` が保持する diff version の head commit SHA と repo HEAD の `ObjectId` が厳密一致する場合のみ**開く(変更ファイル一覧と同一スナップショット SHA を必須条件に)。不一致は開かず「Check out the MR branch first (current branch does not match this merge request).」と通知。
3. **パス封じ込め(real-path)**: `new_path`(削除は `old_path`)を work tree 直下で結合し、**候補と work tree の real path(`toRealPath`、symlink 解決)を解決してから包含関係を検査**する(字句 `normalize()`+`startsWith` だけでは symlink 解決されず、dirty worktree で対象ディレクトリが外部を指す symlink に置換されていると worktree 外の実ファイルを開けてしまう)。real-path 解決に失敗するパス・存在しないファイルは開かない。
4. 検査を通過した場合のみ `IDE.openEditor` で開く。
5. 将来オプション(§20): MR SHA 指定の revision-backed(read-only)エディタを開けば checkout 不要にできるが、内容取得を伴うため Phase 3 対象外。

## 9. 設計判断(承認済み + rev2 追記)

- **(a) Eclipse ビューは 1 つ**: 単一「GitLab」ツリービューに 2 根セクション(Queries / For current branch)。VSCode は 2 ビュー(issuesAndMrs + currentBranchInfo)だが、Eclipse では 1 ビューが「IssuesView 一般化」に自然。既存 `IssuesView` id は本基盤に統合。
- **(b) 「assigned to me」は global REST**: `GET /merge_requests?scope=assigned_to_me&state=opened`(既存 `IssueService` の global `/issues?scope=assigned_to_me` と一貫)。プロジェクト別カスタムクエリは後続強化。global scope に伴う repo 誤特定は §6.1/§8.6 の照合で防ぐ。
- **(c) `checkoutMrBranch` は JGit**: Phase 2 と一貫。remote 結合(§8.4)・多重実行排除(§7.1)・段階別復旧(§16)・EGit index 同期(§21 R-1)を伴う。
- **(d) status.mr/status.issue の受け皿 = サイドバー「For current branch」節**。VSCode currentBranchInfo に忠実。
- **(e) 更新はオンデマンド**(30 秒ポーリングを採らない)。
- **(f) git 認証 = GitLab トークン(oauth2:token)を HTTPS remote に、SSH は既存鍵**(§7.1)。
- **(g) 操作リポジトリは起動時に一意確定**(§6.1)。曖昧時はピッカー/中止で、暗黙選択しない。ピッカー/解決は `RepositoryContext` を返す。
- **(h) SSH remote 対応(U-8 確定 = 第一案、2026-07-26)**: `org.eclipse.jgit.ssh.apache`(+ ssh-agent 用 `.agent`)を明示依存追加し、対象 Transport 限定で `SshdSessionFactory` を設定してパリティ確保。HTTPS-only 縮小案は不採用。

## 10. インターフェース / API(REST・確定値)

すべて既存 `GitLabApiClient`(`${instanceUrl}/api/v4<path>`、Bearer、per_page=100、最大 20 ページ)経由。VSCode 実ソース根拠を併記。

| 用途 | メソッド | パス / クエリ | VSCode 根拠 |
|---|---|---|---|
| 現在ユーザー | GET | `/user` → `{id, username}` | get_current_user.ts:3-7 |
| assigned MR 一覧(サイドバー根) | GET | `/merge_requests?scope=assigned_to_me&state=opened` | 既存 IssueService と対の global scope。VSCode はプロジェクト別 getIssuables(gitlab_service.ts:704-840)だが §9-b で global 採用 |
| ブランチ→MR(global、fork 対応) | GET | `/merge_requests?scope=all&state=opened&source_branch=<enc(branch)>` → source_project_id 一致で絞り updated_at 降順先頭(§8.3)。VSCode の project-scoped からの意図的改善 | get_merge_requests_for_branch.ts:4-12、mr_lookup_helpers.ts:58-101 |
| 閉じる Issue | GET | `/projects/{mr.projectId}/merge_requests/{mr.iid}/closes_issues`(MR の target project id を使用、§8.3) | gitlab_service.ts:841-852 |
| project 数値 id(照合用) | GET | `/projects/:id` → `id`(数値。`source_project_id` 照合、§8.3) | REST |
| 既定ブランチ | GET | `/projects/:id` → `default_branch` | REST 代替(VSCode は GraphQL rootRef、§3 対象外) |
| MR 変更ファイル(PR-3) | GET | `/projects/:project_id/merge_requests/:iid/versions` → 最新 → `/versions/:id`(diffs) | gitlab_service.ts:344-350 |

**`:id` のエンコード(一度だけ)**: `:id` は URL エンコードした `namespace/project`(REST 許容)。Phase 2 `GitLabRemoteParser` の `namespaceWithPath` は URI `rawPath` 由来で**既にセグメントが percent-escape 済み**(例 `grüp/proj` → `gr%C3%BCp/proj`)。これを `PathSegmentEncoder` で**再エンコードすると `%`→`%25` になり `GET /projects/gr%25C3%25BCp%2Fproj` が 404** となる(rev4 の数値 id 照合含む全 project API が失敗)。したがって **`:id` は `namespaceWithPath` の区切り `/` だけを `%2F` に置換して得る**(セグメントは再エンコードしない)。これは Phase 2 PR#28 round-1 の二重エンコード修正(namespaceWithPath は verbatim)と同じ方針。`RepositoryContext.projectId` はこの一度だけエンコードした形で保持する。

### 10.1 URL エンコード規則(ブラウザ URL・ref/branch)

REST の project id 以外に、**ブラウザ URL に埋め込む branch/ref も明示的にエンコード**する(`feature/a&b`・`a#b`・`a+b`・`release/1.0` などが有効なブランチ名のため、無エンコードだと URL 分断・fragment 化・誤ルートを招く)。`MrUrlBuilder`(純ロジック・TDD)が担当:

- **クエリ値**(新規 MR の `merge_request[source_branch]`): `application/x-www-form-urlencoded` 準拠でエンコード(`&`/`#`/`+`/`/`/空白すべて安全化)。既存 `GitLabApiClient.encode`(`URLEncoder`)と同方針。
- **パス ref**(compare の `from`/`to`): 各 ref を percent-encode(`/` も含めセグメント安全化)。**区切り `...` はリテラル**として 2 つのエンコード済み ref を連結。SHA は安全だが同経路でエンコード。
- 受け入れ条件に上記特殊文字と `/` を含むブランチ名の期待 URL を追加。

## 11. データモデル

- `GitLabUser`: `id: Long`, `username: String`(`@SerializedName`)。
- `GitLabMergeRequest`(部分): `id, iid, title, projectId(project_id), webUrl(web_url), references.full, sha, state('opened'|'closed'|'merged'), draft, sourceProjectId(source_project_id), targetProjectId(target_project_id), sourceBranch(source_branch), updatedAt(updated_at)`。一覧描画は `iid/title/state/references`、checkout は `sourceBranch/sha/source/targetProjectId`、status 表示は `iid/state/draft`、lookup 照合は `source_project_id`。
- `RepositoryContext`(§6.1): `gitDir`(正規化実パス=identity、候補重複排除キー)、`workTree`、`namespaceWithPath`(rawPath 由来・既 escape 済み)、`instanceUrl`、`webUrl`、`remoteName`、`projectId`(REST `:id` 用に `namespaceWithPath` の `/`→`%2F` のみ置換した一度だけエンコード形、§10)。コマンド起動時に一度生成し非同期・ノード結合へ渡す。
- `SidebarNode`(sealed): §7 + §7.2。ノードは表示ラベル・アイコン種別・アクティベート動作(open URL 等)・子取得契機・**起点 `RepositoryContext` 結合**(MR/変更ファイル系)を持つ。
- `GitLabIssue`(既存)を再利用。

## 12. 並行処理 / トランザクション境界

- サイドバーの各 fetch は独立(§8.1、根ごと `async`+`Result`)。1 回の `refresh()` に単一世代。世代不一致・disposed の結果は破棄。`CancellationException` は再送出。
- 進行中 fetchJob は次 `refresh()`/`dispose()` でキャンセル。
- 共有 `CoroutineScope`(plain Job)汚染防止のため各 launch 本体を try/catch(Phase 2/LS 再起動と同じ方針、既知 N2)。
- list/tree 再構成は保持データの再マップのみ(API 再呼び出しなし)= 競合なし。
- **mutating git 操作(fetch+checkout、push)は `GitOperationGuard`(§7.1)でリポジトリ単位に直列化**。処理中の再実行は拒否/合流し、`.git/index`・HEAD への競合(lock failure・誤 SHA 検証・誤成功通知)を排除。並行実行テストで担保。

## 13. エラー処理 / タイムアウト / リトライ / 冪等性

- REST タイムアウトは既存 `GitLabApiClient`(30s)。個別リトライなし(ユーザー起点 refresh が再試行)。
- `GitLabConfigurationException`(instance URL/トークン未設定)→ ユーザー向けメッセージノード。その他例外 → Error Log + 汎用「読み込み失敗」ノード。ツリーは破壊しない(§8.1 で失敗根のみメッセージ化)。
- `refreshSidebar` は冪等(全リロード)。list/tree 切替は冪等。
- git: `FetchCommand` は remote-tracking 更新のみ(冪等)。`push` は `PushResult`/`RemoteRefUpdate.Status` を検査し `OK`/`UP_TO_DATE` のみ成功(§8.5、`REJECTED_*` は例外を伴わない失敗)、同一 ref 再送は冪等。`checkout` は HEAD 移動(§16 段階別)。
- `openMrFile` は repo 特定不能/多重一致/パス不正/ファイル不在で開かず通知(§8.6)。
- `openCurrentMergeRequest` は MR 不在時に情報通知(FR-3、U-2 解決)。

## 14. 認証・認可

- 全 REST は Bearer(`GitLabTokenProviderManager.getToken()`)。トークン未設定は `GitLabConfigurationException` 経路。
- git HTTPS 認証は §7.1(oauth2:token、instance ホスト一致時のみ)。SSH は既存鍵。
- 認可は GitLab 側(assigned_to_me は認証ユーザーのスコープ)。MR コメント権限(GraphQL userPermissions)は Phase 3 対象外。

## 15. ログ / 監視 / 監査

- 既存 `logger<T>()`(log4j)。API/git 失敗は `logger.error`(Error Log ビュー)。ユーザー操作の結果は `NotificationUtils`。
- **トークン・認証情報はログ・URL・例外メッセージに出さない**(git 認証は CredentialsProvider 経由で URL に埋め込まない)。
- 追加のテレメトリ送信はしない(既存テレメトリ範囲を変えない)。

## 16. 障害時の復旧(git 段階別)

- fetch 失敗 → メッセージノード/通知、`refreshSidebar` または再実行で再試行。ツリー・他根は保持。
- **checkout の段階別 commit point と復旧**(実機検証項目):
  1. **fetch 後**: remote-tracking ref のみ更新。HEAD/index/working tree 不変。失敗しても副作用は remote-tracking のみで安全、再実行可。
  2. **ローカルブランチ作成後**: ローカル ref 追加のみ(HEAD 未移動)。以降で失敗した場合、作成済み ref は残置(害なし)で通知に明記、再実行は既存 ref を再利用。
  3. **checkout 後**: HEAD 移動 + working tree/index 更新。ここで失敗(競合等)した場合 JGit は checkout を中断し HEAD は元のまま → 「切替できなかった」旨を通知(HEAD 実値を併記)。
- **既存同名ブランチ**(§8.4-5): fast-forward/一致は切替、diverge は自動 reset せず警告・ユーザー判断。**最終 HEAD SHA が期待 `mr.sha` と一致した場合のみ成功通知**、不一致は out-of-sync 警告(誤成功を出さない)。
- **事前条件**: dirty/untracked による競合、merge/rebase/cherry-pick 進行中は checkout を実行せず理由通知(強制しない)。「失敗通知後に実は切替わっていた」を避けるため、通知は最終 HEAD 実値に基づく。
- push 失敗 → upstream 不変・通知、再実行可(冪等)。

## 17. 既存機能への影響

- `IssuesView`(view id `com.gitlab.eclipse.views.IssuesView`)を一般化サイドバーに統合。plugin.xml の view 宣言・perspective 配置を更新(U-1 で id 確定)。
- Phase 2 `navigation` の `BrowserLauncher` / `GitLabRemoteParser` / `GitLabProjectUrlResolver` / `PathSegmentEncoder` / `WorkspaceProjectPicker` を再利用(参照のみ、変更なし)。resolver から remote 名を取り出す薄い拡張が要る場合は追加(既存挙動不変)。
- `GitLabApiClient` に単一オブジェクト取得メソッド追加(既存 `fetchListFromApi` に影響なし)。
- git ネットワーク操作(§7.1)は新規。既存の read-only git 利用には影響しない。
- **SSH transport 依存**(§7.1、U-8 確定=採用): `org.eclipse.jgit.ssh.apache`(+ ssh-agent 認証の `org.eclipse.jgit.ssh.apache.agent`)を **jgit core と同一機序 = `eclipseDependencies` 追加 → manifest `Require-Bundle` + p2deps(既存 EGit p2 repo:188 から解決)**で供給(feature.xml 追加でも fat-jar でもない)。MINA sshd 推移依存は OSGi バンドルなら同経路、素 jar なら `kotlinLibraries` fat-jar リストへ。PR で明示(CLAUDE.md「必須依存の追加は PR で明示」)。**Tycho reactor の repositories は Gradle と別(2024-09 + gitlab-maven のみ、EGit p2 は Gradle 専用)。U-10 で 2024-09 が ssh/sshd IU を含むか確認し、含まなければ pom.xml への p2 追加=ビルド構成変更=要ユーザー承認**(R-7)。

## 18. 移行方法

- `IssuesView` → サイドバー統合: Issues assigned はサイドバーの「Issues assigned to me」根として提供。旧 `IssuesView` クラス/view 宣言は削除し、新サイドバー view に一本化。プレリリース段階のためユーザー保存レイアウト互換は要求しない(既知の軽微影響として PR に明記)。
- 段階導入: PR-1 でサイドバー + Issues/MRs 根 + repo 選択/git 基盤の骨格 + 変更ファイルノード型宣言、PR-2 で For current branch 節 + push 契約、PR-3 で MR 展開 + checkout/openMrFile。各 PR は単体で動作しビルド green。

## 19. ロールバック方法

- 各 PR は独立ブランチ・独立 PR。問題時は当該 PR を revert(`develop` から)。
- **PR-1 の旧 `IssuesView` 削除**: revert 時に旧 view を復帰できるよう、削除は単一コミットに分離し PR 説明文に revert 手順を明記(U-5 解決の方針)。PR-1 revert = 新サイドバー削除 + 旧 IssuesView 復帰の 2 点。

## 20. VSCode からの意図的差異(パリティ注記)

- **list/tree トグルの意味**: PR-1 では list=フラット / tree=プロジェクト別グループ。VSCode は MR 配下変更ファイル階層のみ切替。PR-3 で変更ファイル階層切替にも拡張。
- **MR webview → ブラウザ**: `status.mr` クリック等で VSCode は MR webview(`gl.showRichContent`)。Phase 3 はブラウザで `web_url` を開く(webview は将来)。
- **openCurrentMergeRequest の MR 不在**: VSCode は silent no-op、Eclipse は情報通知(発見性優先、U-2)。
- **現在ユーザー/assigned の取得スコープ**: global `/merge_requests`(§9-b)。
- **既定ブランチ**: REST `default_branch`(GraphQL rootRef 不使用)。
- **openMrFile の repo 特定**: VSCode は `repositoryRoot` 起点、Eclipse は global scope のため project 照合 + パス検証 + **repo HEAD ↔ MR revision 検証**を強化(§8.6)。revision-backed editor は将来オプション。
- **ブランチ→MR は global lookup**: VSCode の project-scoped(fork 未対応)から global `/merge_requests` + source_project_id 照合へ改善(§8.3、fork のブランチからでも MR を発見)。
- **更新契機**: オンデマンド(30 秒ポーリング不使用)。

## 21. 想定されるリスク

- **R-1(checkoutMrBranch × EGit)**: EGit 管理下ワーキングツリーを JGit で直接 checkout すると EGit の index キャッシュと不整合の可能性。checkout 後に `IProject` リフレッシュ(`refreshLocal`)で緩和。実機のみ検証。PR-3 で index 更新/リフレッシュ要否を検証。
- **R-2(UI スレッド/SWT/TreeViewer)**: headless で検証不能。fable 実装 + 手動検証手順で担保。
- **R-3(git 認証/トランスポート)**: HTTPS の oauth2:token 認証、SSH(`SshdSessionFactory` + ~/.ssh/agent/known_hosts、self-managed の host-key 検証・未知ホスト扱い)、self-managed の TLS 証明書は実機のみ検証。トークン漏洩防止(§15)を検証項目に。SSH バンドル追加時は OSGi 解決(Require-Bundle/feature.xml)も検証。
- **R-4(global assigned スコープ)**: 自分の全 MR が出る(VSCode プロジェクト別と結果集合が異なる)。§9-b の意図的差異として受容。repo 誤特定は §6.1/§8.6 で防止。
- **R-5(旧 IssuesView 統合)**: view id 変更でユーザーレイアウト影響(プレリリースのため受容、§19 でロールバック明確化)。
- **R-6(同名ブランチ/複数 remote)**: source_project_id 照合(§8.3)・remote 結合(§8.4)・repo 選択(§6.1)で誤対象を防止。多重 remote 一致時の順序は Phase 2 resolver 準拠(origin 優先)。
- **R-7(SSH バンドルのビルド供給)**: `org.eclipse.jgit.ssh.apache(.agent)` + MINA sshd を、Gradle は EGit p2 repo(:188)経由の Require-Bundle+p2deps で供給できる見込みだが、**Tycho の repositories は 2024-09 + gitlab-maven のみ**でありそこに ssh/sshd IU が無ければ **root pom.xml への p2 追加(ビルド構成変更・要ユーザー承認)が必須**。これは SSH 第一案(U-8)の実現に不可欠な前提であり、確認できなければ SSH 出荷不能。sshd スタックの OSGi クラスローディング・agent の native/JNA は実機検証。U-10 で PR-3 前に確定。

## 22. テスト方針

- **純ロジック(TDD、sonnet)**: `SidebarViewModel`(根/子構成・list/tree グルーピング・latest-wins・**根ごと成功/失敗混在の合成**)、`MergeRequestService`/`CurrentUserService`/`ProjectDetailService`(URL/クエリ組立・JSON パース、global lookup)、`CurrentBranchMrLookup`(tracking 名は `branch.<n>.remote`==解決 remote 時のみ採用/他はローカル名フォールバック・数値 id での source_project_id 照合・fork 経路・closes_issues は MR target project・updated_at 降順)、`MrUrlBuilder`(§10.1 の特殊文字エンコード)、`GitOperationGuard`(直列化・重複拒否)、`RepositoryContext` 生成(§6.1、選択→context 一体化、gitDir 重複排除、`namespaceWithPath`→`:id` の一度だけエンコード)。
- **git 結果判定(実機 or 決定的テスト、fable)**: `BranchPushService` の `RemoteRefUpdate.Status` 分岐(OK/UP_TO_DATE 成功・REJECTED_* 失敗)、`openMrFile` の head SHA 厳密一致 + real-path 包含(symlink 脱出拒否)。
- **SWT/UI/JGit-transport(実機のみ、fable 実装)**: `SidebarView` 描画・list/tree トグル・コンテキストメニュー・repo ピッカー・`checkoutMrBranch`(fetch/checkout/段階別復旧)・`BranchPushService`(push/認証)・`openMrFile`。手動検証手順を各 PR 説明文に記載。
- **並行**: `GitOperationGuard` の重複起動拒否/合流(決定的テスト)。
- **検証ゲート**: 対象テスト PASS + headless suite 失敗数 36 のまま + 変更ファイル detekt 0。

## 23. 受け入れ条件(PR 単位)

- **PR-1**: サイドバー view が表示され、Issues assigned / MRs assigned 根が展開でき、項目ダブルクリックでブラウザが開く。**先頭根 API が失敗しても他根は表示され、失敗根のみメッセージ**(混在テスト)。`refreshSidebar` で再取得。list/tree トグルでフラット ⇄ プロジェクト別。`showMergeRequestsAssignedToMe` が §6.1 の選択 repo の assigned MR ページを開く(複数 repo/エディタ無し/resolver 失敗を含む)。`ChangedFile/DirectoryNode` 型と `GitOperationGuard` が宣言され単体テスト green。detekt 0 / suite 36 維持。
- **PR-2**: 「For current branch」節に §6.1 選択 repo の現ブランチ MR と閉じる Issue が表示、クリックでブラウザ。MR lookup は global `/merge_requests` + `source_project_id` 照合で **fork のブランチからでも発見**、tracking 無しはローカル名フォールバック、detached は No MR。`openCurrentMergeRequest` は不在時に通知。`compareCurrentBranch`/新規 MR URL が §10.1 準拠で特殊文字ブランチでも正しい。`openCreateNewMR` は **upstream が解決 remote の `refs/heads/<branch>` に一致する時のみ直接 URL**、それ以外(別 remote/別ブランチ/無 upstream)は clean なら選択 remote へ push→URL、dirty は誘導通知(誤 remote に push しない)。push は `RemoteRefUpdate.Status` が OK/UP_TO_DATE の時のみ成功扱いし、REJECTED_*(non-ff/protected/hook)は upstream 不変の失敗として通知(URL を開かない)。
- **PR-3**: MR ノード展開で変更ファイルが list/tree 表示。`openMrFile` は gitDir 重複排除のうえ対応 repo を一意特定し、**ChangedFileNode の head SHA が repo HEAD ObjectId と厳密一致する時のみ**、real-path 包含検査(symlink 脱出拒否)を通過したファイルを開く(SHA 不一致/多重一致/不在/real-path 解決失敗は通知)。`checkoutMrBranch`(同一プロジェクト MR)は MR 結合 remote から明示 refspec で fetch、既存同名ブランチは ff/一致で切替・diverge は警告、**最終 HEAD SHA が `mr.sha` と一致した時のみ成功通知**、段階別復旧に従い、重複起動を拒否。SSH remote でも fetch/checkout/push が動作(実機、ssh-agent-only 含む)。

## 24. 未決事項(実装前に判断)

- **U-1**: 単一サイドバー view の id とラベル。既存 `com.gitlab.eclipse.views.IssuesView` を改名/新設どちらか。既存 `LanguageServerBrowserView`(Duo)とは別 view 継続で良いか。
- **U-6**: tree モードのグルーピングキー(プロジェクト `references.full` の namespace 部分か、`web_url` のプロジェクトパスか)。
- **U-7**: 「For current branch」節と「Queries」節を単一 TreeViewer の疑似根として並置するか、`TreeViewer` の複数トップレベルノードにするか(描画・空状態表現の差)。
- **U-10**(ビルド供給・PR-3 着手前): 機序は確定(§7.1: OSGi バンドルは Require-Bundle+p2deps、素 jar は kotlinLibraries fat-jar)。残確認 2 点: (1) **Gradle** = EGit p2 repo(build.gradle.kts:188)が `org.eclipse.jgit.ssh.apache(.agent)` + `org.apache.sshd.osgi` を提供するか。(2) **Tycho** = root pom.xml の `2024-09` リポジトリがそれらの IU を含むか(EGit p2 は Tycho には効かない)。**(2) が否なら root pom.xml への p2 追加が必須=ビルド構成変更=要ユーザー承認**(§21 R-7)。SSH 第一案採用(U-8)は Tycho 側で pom.xml の p2 追加を要する可能性が高い点をユーザーに事前共有する。
> rev4 で解決済み: U-9(`source_project_id` 照合 = `GET /projects/:id` の数値 id 比較に確定、§8.3。namespace 照合案は fork で誤るため不採用)。
> rev7 で解決済み: U-8(git SSH remote = 第一案採用に確定。`org.eclipse.jgit.ssh.apache`(+ `.agent`)を明示依存追加、対象 Transport 限定で `SshdSessionFactory`。HTTPS-only 縮小案は不採用。§7.1/§9-h/§17)。

> rev2 で解決済み: U-2(openCurrentMergeRequest 不在時=通知, §20/FR-3)、U-3(checkout 多重実行=GitOperationGuard, §7.1/§12)、U-4(openMrFile 未取得/不在=通知, §8.6)、U-5(旧 IssuesView 削除の分離コミット + revert 手順, §19)。

## 25. 確定できた範囲 / 追加情報が必要な事項

- 確定: REST エンドポイント/クエリ(§10、VSCode 実ソース根拠付き)、URL エンコード規則(§10.1)、PR 分割、サイドバー基盤方針、repo 選択規則 + `RepositoryContext`(§6.1)、git 認証/操作基盤 + SSH 依存方針(§7.1)、status の受け皿、GraphQL 不使用の代替、根ごと障害分離(§8.1)、ブランチ→MR の global lookup + fork 対応(§8.3)、checkout(最終 HEAD SHA 検証・既存ブランチ ff/diverge)/push(upstream-remote 一致)/openMrFile(revision 検証)の各契約。
- 追加情報待ち: §24 の U-1/U-6/U-7(view/ツリー描画の詳細、PR-1 着手前に確定)。self-managed 環境での git 認証・host-key・証明書挙動は実機検証で確定。(U-8=SSH 第一案採用、U-9=数値 id 照合、いずれも確定済みで追加情報待ちには含めない。)
