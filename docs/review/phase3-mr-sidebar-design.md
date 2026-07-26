# Phase 3 設計書: MR ドメイン + サイドバー(ツリービュー)基盤

- 対象リポジトリ: `sumikof/gitlab-eclipse-plugin`(Gradle/Kotlin、`com.gitlab.eclipse.*`)
- 親ロードマップ: #8 / パリティ台帳: #7 / フェーズ issue: #11
- パリティ基準: VSCode `gitlab-workflow` v6.85.3(参照コピー `/workspace/out/gitlab-vscode-extension`、読み取り専用)
- ベースブランチ: `develop`(tip `1e32d4c`)
- 本書はレビュー専用。実装コードではなく **システム設計書** としてレビューする。実装 PR・マージ先には含めない。

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
| PR-1 基盤 | サイドバー一般化 | D17×2 + `showMergeRequestsAssignedToMe` |
| PR-2 現ブランチ | 「For current branch」節 | `status.mr` + `status.issue` + `openCurrentMergeRequest` / `openCreateNewMR` / `compareCurrentBranch` |
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

## 5. 要件

### 機能要件

- **FR-1(showMergeRequestsAssignedToMe)**: 自分にアサインされた MR 群をブラウザで開く。VSCode は `${projectUrl}/-/merge_requests?assignee_id=${userId}`(openers.ts:53-70、`GET /user` で userId 取得)。加えてサイドバーに「MRs assigned to me」根ノードを設け、global REST で取得したフラット一覧を表示する。
- **FR-2(サイドバー基盤 / refreshSidebar / list-tree)**:
  - 再利用可能な `TreeViewer` サイドバー(単一「GitLab」ビュー)。根セクション: クエリ根(Issues assigned / MRs assigned)+(PR-2 で)For current branch。
  - `gl.refreshSidebar` = 全ノード再取得・再描画(VSCode extension.ts:248-254 = full reload)。
  - `gl.sidebarViewAsList` / `gl.sidebarViewAsTree` = 表示モード切替。**Eclipse での意味(意図的適応)**: list=フラット項目、tree=プロジェクト別グループ化。VSCode ではトグルは「MR 配下の変更ファイル階層」だけを切替えるが、変更ファイルは PR-3 まで存在しないため、PR-1 ではトグルにプロジェクト別グループ化の意味を与え、PR-3 で MR 配下の変更ファイル階層切替にも拡張する(§20 差異記録)。ツールバーに tree ⇄ list の相互トグルボタン(VSCode desktop.package.json:671-679 に相当)。状態は非永続・既定 list。
- **FR-3(openCurrentMergeRequest)**: 現ブランチの open MR をブラウザで開く。無ければ VSCode 同様に何もしない(openers.ts:125-134 に silent no-op)。ただし Eclipse では発見不能時に情報通知を出すか否かを未決事項 U-2 とする。
- **FR-4(openCreateNewMR)**: 現ブランチに upstream が無ければ push を促し(VSCode create_mr.ts:11-52: dirty→SCM ビュー、clean→push +「Create MR」通知)、upstream があれば `${webUrl}/-/merge_requests/new?merge_request%5Bsource_branch%5D=${branch}` をブラウザで開く。API 呼び出しなし。
- **FR-5(compareCurrentBranch)**: `${webUrl}/-/compare/${defaultBranch}...${headSha}` をブラウザで開く。既定ブランチは REST `default_branch`、headSha はローカル git HEAD。
- **FR-6(status.mr / status.issue → For current branch 節)**: 現ブランチの MR とその閉じる Issue を「For current branch」節に表示。MR ノードのクリックで MR をブラウザで開く、Issue ノードのクリックで Issue をブラウザで開く。MR 無し・Issue 無しはそれぞれ「No merge request found」「No closing issue found」相当のメッセージノード(VSCode current_branch_data_provider.ts:144-175)。
- **FR-7(checkoutMrBranch)**: MR ノードのコンテキストメニューから、同一プロジェクト MR の source ブランチをローカルに checkout(JGit `fetch` + `checkout`)。fork MR は対象外(ボタン非表示)。checkout 後、ローカル HEAD SHA が `mr.sha` と異なれば「out of sync」警告(VSCode checkout_mr_branch.ts:18-49)。
- **FR-8(openMrFile)**: MR の変更ファイルノードから、ローカルワークスペースの実ファイルを開く(VSCode open_mr_file.ts:41-49)。事前に MR ノードを展開し変更ファイル一覧が取得済みであること(§13 前提)。

### 非機能要件

- **既存パターン踏襲**: coroutine fetch → `control.display.asyncExec` で presenter 反映、世代 latest-wins(`ViewRefreshState`)、`never-throws` を共有 `CoroutineScope` 汚染防止のため各 launch を try/catch で保護。
- **UI スレッド規律**: SWT 部品操作は UI スレッド。バックグラウンド(coroutine)では API/git のみ。全 sink は自マーシャル(`currentDisplay.asyncExec`)。
- **detekt 0 / headless suite の失敗数は SWT ベースライン 36 のまま**。純ロジックは TDD。

## 6. 前提条件と制約

- ディレクトリ構成変更禁止(既存 `com.gitlab.eclipse.*` 内に追加のみ)。ビルドシステム変更禁止(Gradle + update-site。必須依存追加は PR で明示)。
- プロトコル定数(REST パス/クエリ)は実装前に実ソースで確定済み(本書 §11 に VSCode `file:line` 付きで記載)。
- 認証は既存 `GitLabTokenProviderManager`(Bearer)。instance URL は `PreferenceConstants.GITLAB_INSTANCE_URL`。
- git 操作は JGit(Phase 2 と一貫、`org.eclipse.jgit`)。プロジェクト識別は Phase 2 の `GitLabRemoteParser` / `GitLabProjectUrlResolver`(git remote → `namespaceWithPath` + instance)を流用。
- headless devcontainer では SWT/JGit-checkout/実 LS は動かない → UI・git 系は実機のみ検証(手動検証手順を PR に記載)。

## 7. システム構成(コンポーネント)

パッケージ配置(すべて既存体系内の追加):

- **`com.gitlab.eclipse.api`(REST 拡張)**
  - `CurrentUserService`: `GET /user` → `GitLabUser`。
  - `MergeRequestService`: assigned-to-me 一覧 / ブランチ→MR / 閉じる Issue(必要に応じ)。
  - `ProjectDetailService`: `GET /projects/:id` → `default_branch`。
  - モデル: `GitLabMergeRequest`、`GitLabUser`(部分フィールド)。既存 `GitLabApiClient`(REST・ページング・Bearer)を利用。単一 GET 用に `fetchOne`/`fetchObject` を `GitLabApiClient` に追加(現状は `fetchListFromApi` のみ)。
- **`com.gitlab.eclipse.views.sidebar`(新規・ツリー基盤)**
  - `SidebarView : ViewPart`(`TreeViewer`)。
  - ノードモデル `SidebarNode`(sealed): `QueryRootNode` / `MergeRequestNode` / `IssueNode` / `CurrentBranchSectionNode` / `ProjectGroupNode` / `MessageNode`。
  - `SidebarContentProvider : ITreeContentProvider`、`SidebarLabelProvider`。
  - `SidebarViewMode`(enum list/tree)+ `SidebarViewState`(モジュール状態 + 変更イベント)。
  - `SidebarViewModel`(純ロジック): クエリ結果 → 根/子ノード構成、list/tree グルーピング、latest-wins 世代。**TDD 対象**。
  - 既存 `views/issues/IssuesView` を本基盤に吸収(§18 移行)。
- **`com.gitlab.eclipse.mergerequests`(新規・ハンドラ + git)**
  - ブラウザ系ハンドラ: `OpenCurrentMergeRequestHandler` / `OpenCreateNewMrHandler` / `CompareCurrentBranchHandler` / `ShowMergeRequestsAssignedToMeHandler`。Phase 2 `BrowserLauncher` を再利用。
  - git 系(PR-3): `CheckoutMrBranchHandler` + `MrBranchCheckoutService`(JGit)。
  - ブランチ→MR 解決: `CurrentBranchMrLookup`(tracking ブランチ名 → REST source_branch フィルタ → updated_at 降順先頭)。

## 8. 処理フロー(主要シナリオ)

### 8.1 サイドバー初期表示 / refresh
1. `SidebarView.createPartControl` で `TreeViewer` + content/label provider 構築、`SidebarViewState` 購読、初回 `refresh()`。
2. `refresh()`: 世代 begin、進行中 fetchJob キャンセル、`coroutineScope.launch{ try { … } catch }` で各根のデータ取得(Issues assigned / MRs assigned、PR-2 で現ブランチ)。
3. 取得後 `control.display.asyncExec { viewModel.apply(generation, results); viewer.refresh() }`(disposed/世代ガード)。
4. `gl.refreshSidebar` ハンドラ → `SidebarView.refresh()`(全リロード)。

### 8.2 list/tree 切替
1. `gl.sidebarViewAsTree`/`AsList` ハンドラ → `SidebarViewState.setMode(mode)`。
2. `setContext`(トグルボタン表示切替相当)+ 変更イベント発火 → 購読中の `SidebarView` が `viewModel.regroup(mode)` + `viewer.refresh()`(再取得不要、保持データを再構成)。

### 8.3 現ブランチ MR / 閉じる Issue(For current branch、PR-2)
1. `CurrentBranchMrLookup`: 現リポジトリ HEAD ブランチ名 → tracking ブランチ名(git config `branch.<n>.merge`、`refs/heads/` 除去)。detached は「No merge request found」。
2. プロジェクト解決(Phase 2 resolver)→ REST `GET /projects/:id/merge_requests?source_branch=<branch>` → `state=='opened'` で絞り(設定 `showClosedMergeRequests` は Phase 3 対象外、常に opened)→ updated_at 降順先頭。
3. MR があれば `GET /projects/:id/merge_requests/:iid/closes_issues` で閉じる Issue。
4. 「For current branch」節に MR ノード + Issue ノード(無ければメッセージノード)。

### 8.4 checkoutMrBranch(PR-3)
1. MR ノードのコンテキストメニュー(同一プロジェクト MR のみ表示)→ `CheckoutMrBranchHandler`。
2. `MrBranchCheckoutService`(JGit、bg): `FetchCommand`(既定 remote) → `CheckoutCommand`(source_branch、無ければ tracking 生成) → HEAD 名検証。
3. checkout 後 HEAD SHA ≠ mr.sha → 「out of sync」警告通知、一致 → 「Branch changed」通知。git エラーは Error Log + 通知。

## 9. 設計判断(承認済み)

- **(a) Eclipse ビューは 1 つ**: 単一「GitLab」ツリービューに 2 根セクション(Queries / For current branch)。VSCode は 2 ビュー(issuesAndMrs + currentBranchInfo)だが、Eclipse では 1 ビューが「IssuesView 一般化」に自然。既存 `IssuesView` id は本基盤に統合。
- **(b) 「assigned to me」は global REST**: `GET /merge_requests?scope=assigned_to_me&state=opened`(既存 `IssueService` の global `/issues?scope=assigned_to_me` と一貫)。プロジェクト別カスタムクエリは後続強化。
- **(c) `checkoutMrBranch` は JGit**: Phase 2 と一貫。EGit 管理下ワーキングツリーとの index 同期・実機のみ検証は PR-3 設計時に詰める(§21 リスク)。
- **(d) status.mr/status.issue の受け皿 = サイドバー「For current branch」節**(ステータスバー項目でも既存ステータスメニューでもなく)。VSCode の currentBranchInfo ツリーに忠実で、基盤に自然に載る。
- **(e) 更新はオンデマンド**(30 秒ポーリングを採らない)。

## 10. インターフェース / API(REST・確定値)

すべて既存 `GitLabApiClient`(`${instanceUrl}/api/v4<path>`、Bearer、per_page=100、最大 20 ページ)経由。VSCode 実ソース根拠を併記。

| 用途 | メソッド | パス / クエリ | VSCode 根拠 |
|---|---|---|---|
| 現在ユーザー | GET | `/user` → `{id, username}` | get_current_user.ts:3-7 |
| assigned MR 一覧(サイドバー根) | GET | `/merge_requests?scope=assigned_to_me&state=opened` | 既存 IssueService と対の global scope。VSCode はプロジェクト別 getIssuables(gitlab_service.ts:704-840)だが §9-b で global 採用 |
| ブランチ→MR | GET | `/projects/:id/merge_requests?source_branch=<branch>` → opened を updated_at 降順先頭 | get_merge_requests_for_branch.ts:4-12、mr_lookup_helpers.ts:58-101 |
| 閉じる Issue | GET | `/projects/:id/merge_requests/:iid/closes_issues` | gitlab_service.ts:841-852 |
| 既定ブランチ | GET | `/projects/:id` → `default_branch` | REST 代替(VSCode は GraphQL rootRef、§3 対象外) |
| MR 変更ファイル(PR-3) | GET | `/projects/:project_id/merge_requests/:iid/versions` → 最新 → `/versions/:id`(diffs) | gitlab_service.ts:344-350 |

`:id` は URL エンコードした `namespace/project` パス(REST 許容)。Phase 2 の `PathSegmentEncoder` を流用。

## 11. データモデル

- `GitLabUser`: `id: Long`, `username: String`(`@SerializedName`)。
- `GitLabMergeRequest`(部分): `id, iid, title, projectId(project_id), webUrl(web_url), references.full, sha, state('opened'|'closed'|'merged'), draft, sourceProjectId, targetProjectId, sourceBranch, updatedAt`。一覧描画は `iid/title/state/references`、checkout は `sourceBranch/sha/source/targetProjectId`、status 表示は `iid/state/draft`。
- `SidebarNode`(sealed): 上記 §7。ノードは表示ラベル・アイコン種別・アクティベート動作(open URL 等)・子取得契機を持つ。
- `GitLabIssue`(既存)を再利用。

## 12. 並行処理 / トランザクション境界

- サイドバーの各 fetch は独立(トランザクション不要)。1 回の `refresh()` に単一世代。世代不一致・disposed の結果は破棄。
- 進行中 fetchJob は次 `refresh()`/`dispose()` でキャンセル。
- 共有 `CoroutineScope`(plain Job)汚染防止のため各 launch 本体を try/catch(Phase 2/LS 再起動と同じ方針、既知 N2)。
- list/tree 再構成は保持データの再マップのみ(API 再呼び出しなし)= 競合なし。
- JGit checkout は単一ハンドラ内で直列(並行 checkout はガードせず、UI 起点の逐次操作を前提。多重起動抑止は未決 U-3)。

## 13. エラー処理 / タイムアウト / リトライ / 冪等性

- REST タイムアウトは既存 `GitLabApiClient`(30s)。個別リトライなし(ユーザー起点 refresh が再試行)。
- `GitLabConfigurationException`(instance URL/トークン未設定)→ ユーザー向けメッセージノード。その他例外 → Error Log + 汎用「読み込み失敗」ノード。ツリーは破壊しない(失敗根のみメッセージ化)。
- `refreshSidebar` は冪等(全リロード)。list/tree 切替は冪等。
- `openMrFile` はローカルに実ファイルが無ければ警告(VSCode 同様)。MR 変更ファイル未取得(ノード未展開)時はメッセージ(VSCode は hard assert、Eclipse は警告に緩和=未決 U-4)。
- `openCurrentMergeRequest` は MR 不在時 no-op(U-2 で通知有無を決定)。

## 14. 認証・認可

- 全 REST は Bearer(`GitLabTokenProviderManager.getToken()`)。トークン未設定は `GitLabConfigurationException` 経路。
- 認可は GitLab 側(assigned_to_me は認証ユーザーのスコープ)。MR コメント権限(GraphQL userPermissions)は Phase 3 対象外。

## 15. ログ / 監視 / 監査

- 既存 `logger<T>()`(log4j)。API/git 失敗は `logger.error`(Error Log ビュー)。ユーザー操作の結果は `NotificationUtils`。
- 追加のテレメトリ送信はしない(既存テレメトリ範囲を変えない)。

## 16. 障害時の復旧

- fetch 失敗 → メッセージノード表示、`refreshSidebar` で再試行。ツリー・他根は保持。
- checkout 失敗 → 状態は JGit のワーキングツリーに従う(部分適用なし。fetch は副作用だが冪等)。ユーザーは Git ツール/再実行で復旧。

## 17. 既存機能への影響

- `IssuesView`(view id `com.gitlab.eclipse.views.IssuesView`)を一般化サイドバーに統合。plugin.xml の view 宣言・perspective 配置を更新。**旧 view id を保持するか新 id にするかは §18 移行**で決定(既定は新規サイドバー id に集約し旧 id を retire)。
- Phase 2 `navigation` の `BrowserLauncher` / `GitLabRemoteParser` / `GitLabProjectUrlResolver` / `PathSegmentEncoder` を再利用(変更なし・参照のみ)。
- `GitLabApiClient` に単一オブジェクト取得メソッド追加(既存 `fetchListFromApi` に影響なし)。

## 18. 移行方法

- `IssuesView` → サイドバー統合: Issues assigned はサイドバーの「Issues assigned to me」根として提供。旧 `IssuesView` クラス/view 宣言は削除し、新サイドバー view に一本化。プレリリース段階のためユーザー保存レイアウト互換は要求しない(既知の軽微影響として PR に明記)。
- 段階導入: PR-1 でサイドバー + Issues/MRs 根、PR-2 で For current branch 節、PR-3 で MR 展開。各 PR は単体で動作しビルド green。

## 19. ロールバック方法

- 各 PR は独立ブランチ・独立 PR。問題時は当該 PR を revert(`develop` から）。PR-1 revert 時は旧 `IssuesView` 復帰が必要になるため、PR-1 では旧クラスを即削除せずデッドコード化せず段階的にするか、revert 手順を PR に明記(未決 U-5)。

## 20. VSCode からの意図的差異(パリティ注記)

- **list/tree トグルの意味**: PR-1 では list=フラット / tree=プロジェクト別グループ。VSCode は MR 配下変更ファイル階層のみ切替。PR-3 で変更ファイル階層切替にも拡張。
- **MR webview → ブラウザ**: `status.mr` クリック等で VSCode は MR webview(`gl.showRichContent`)。Phase 3 はブラウザで `web_url` を開く(webview は将来)。
- **現在ユーザー/assigned の取得スコープ**: global `/merge_requests`(§9-b)。
- **既定ブランチ**: REST `default_branch`(GraphQL rootRef 不使用)。
- **更新契機**: オンデマンド(30 秒ポーリング不使用)。

## 21. 想定されるリスク

- **R-1(checkoutMrBranch × EGit)**: EGit 管理下ワーキングツリーを JGit で直接 checkout すると EGit の index キャッシュと不整合の可能性。実機のみ検証可。PR-3 で JGit checkout 後の index 更新/リフレッシュ要否を検証。
- **R-2(UI スレッド/SWT/TreeViewer)**: headless で検証不能。fable 実装 + 手動検証手順で担保。
- **R-3(ブランチ→MR プロジェクト解決)**: git remote が非 GitLab / 複数リモートのケース。Phase 2 resolver の挙動(origin 優先の一致リモート探索)に依存。
- **R-4(global assigned スコープ)**: VSCode のプロジェクト別クエリと結果集合が異なりうる(自分の全 MR が出る)。§9-b の意図的差異として受容。
- **R-5(旧 IssuesView 統合)**: view id 変更でユーザーレイアウト影響(プレリリースのため受容、U-5 でロールバック明確化)。

## 22. テスト方針

- **純ロジック(TDD、sonnet)**: `SidebarViewModel`(根/子構成・list/tree グルーピング・latest-wins)、`MergeRequestService`/`CurrentUserService`/`ProjectDetailService`(URL/クエリ組立・JSON パース)、`CurrentBranchMrLookup`(tracking ブランチ名解決・updated_at 降順選択)。
- **SWT/UI/JGit(実機のみ、fable 実装)**: `SidebarView` 描画・list/tree トグル・コンテキストメニュー・`checkoutMrBranch`。手動検証手順を各 PR 説明文に記載。
- **検証ゲート**: 対象テスト PASS + headless suite 失敗数 36 のまま + 変更ファイル detekt 0。

## 23. 受け入れ条件(PR 単位)

- **PR-1**: サイドバー view が表示され、Issues assigned / MRs assigned 根が展開でき、項目ダブルクリックでブラウザが開く。`refreshSidebar` で再取得。list/tree トグルでフラット ⇄ プロジェクト別が切替わる。`showMergeRequestsAssignedToMe` がブラウザで assigned MR ページを開く。detekt 0 / suite 36 失敗維持。
- **PR-2**: 「For current branch」節に現ブランチの MR と閉じる Issue が表示され、クリックでブラウザが開く。MR/Issue 不在時はメッセージノード。`openCurrentMergeRequest`/`openCreateNewMR`/`compareCurrentBranch` が期待 URL を開く(create は upstream 有無で分岐)。
- **PR-3**: MR ノード展開で変更ファイルが list/tree 表示される。`openMrFile` でローカル実ファイルが開く。`checkoutMrBranch`(同一プロジェクト MR)で source ブランチが checkout され、out-of-sync 時は警告。

## 24. 未決事項(実装前に判断)

- **U-1**: 単一サイドバー view の id とラベル。既存 `com.gitlab.eclipse.views.IssuesView` を改名/新設どちらか。既存 `LanguageServerBrowserView`(Duo)とは別 view 継続で良いか。
- **U-2**: `openCurrentMergeRequest` の MR 不在時、VSCode の silent no-op に忠実にするか、Eclipse では情報通知を出すか。
- **U-3**: `checkoutMrBranch` の多重起動抑止(進行中の再クリック)を入れるか。
- **U-4**: `openMrFile` の変更ファイル未取得時、VSCode の hard assert に対し Eclipse は警告に緩和で良いか。
- **U-5**: PR-1 の旧 `IssuesView` 削除タイミングとロールバック手順。
- **U-6**: tree モードのグルーピングキー(プロジェクト `references.full` の namespace 部分か、`web_url` のプロジェクトパスか)。
- **U-7**: 「For current branch」節と「Queries」節を単一 TreeViewer の疑似根として並置するか、`TreeViewer` の複数トップレベルノードにするか(描画・空状態表現の差)。

## 25. 確定できた範囲 / 追加情報が必要な事項

- 確定: REST エンドポイント/クエリ(§10、VSCode 実ソース根拠付き)、PR 分割、サイドバー基盤方針、status の受け皿、GraphQL 不使用の代替。
- 追加情報待ち: §24 の未決事項(実装計画時にユーザー/実ソースで確定)。特に U-1/U-5(view 移行)と U-6/U-7(ツリー構造)は PR-1 着手前に確定する。
