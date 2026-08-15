# clone プロトコル設計書(F5 `cloneWiki` / F7 `openRepository`)

**この文書はレビュー専用です。実装 PR にもマージ先ブランチにも含めません。**

対象ブランチ: `gitlab-ls-9.3.0` / 関連 issue: #14(D15)/ 前サイクルの設計書: Close 済み Draft PR #70

## 1. 背景と目的

D15 の 8 コマンドのうち 6 つ(F1〜F4 / F6 / F8)は前サイクルで設計・実装を終えた
(PR #71 #72 #73 #76 #77 #78)。残る **F5 `cloneWiki`** と **F7 `openRepository`** は、
前サイクルの Codex レビュー 7 巡を通じて **「clone 先の所有権・確定・異常終了からの回復」プロトコル**が
繰り返し指摘の中心になり、方式を 3 度変えても
**「自分が作ったものだけを消す」ことを保証できない**という同じ壁に当たったため、
ユーザー判断で独立サイクルへ切り出された(前設計 §4.1)。

本設計の目的は、**その壁を回避する方針を確定させたうえで** F5 / F7 を実装可能な形にすることである。

### 1.1 前サイクルで未解決だった指摘(本設計の入力)

| 指摘 | 内容 |
|---|---|
| 第 3 巡 R3-7 | `CloneCommand` の内部作成物を呼び出し側から識別できない |
| 第 5 巡 R5-2 | `ATOMIC_MOVE` は宛先存在時の置換可否が実装依存(Linux は空ディレクトリを置換する) |
| 第 6 巡 R6-1 | `createDirectory` はディレクトリの作成者を保証するだけで、子要素の所有者は保証しない |
| 第 7 巡 R7-1 | **名前の秘匿は所有権の根拠にならない**(ディレクトリは列挙・監視から発見できる) |
| 第 7 巡 R7-2 / R7-3 | ステージング昇格時の同名衝突、予約と永続記録の間のクラッシュ窓 |

### 1.2 本設計の中心的な決定(2026-08-15・ユーザー判断)

> **D1: 失敗しても、プラグインは一切自動削除しない。**

上記 5 件の指摘はすべて **「作った物を安全に消す」** ための機構に関するものである。
**消さないと決めれば、機構ごと不要になる。** 代償は「失敗時にディスク上に途中経過が残る」ことだが、
**データ損失は発生しない**(消えるものが無い)。これは既存機能の方針
(F6 §12「作成したプロジェクトも remote も削除しない」/ F4「外部の変更を上書きするくらいなら
中間状態のまま残す」)とも一貫する。

**この決定は本設計の全体を規定する。以降の節はすべてこの前提の上に立つ。**

## 2. 対象範囲

- **F5 `cloneWiki`**: 現在のリポジトリに対応する GitLab Wiki を clone し、ワークスペースへ取り込む。
- **F7 `openRepository`**: GitLab プロジェクトを指定して clone し、ワークスペースへ取り込む。

前設計 §4 の決定を引き継ぐ: VSCode の `gl.openRepository` は仮想 FS(`gitlab-remote://`)で
**clone せずに**開くが、Eclipse に相当機能が無いため **「選んで clone して取り込む」に再定義**する。
**VSCode との挙動差が生じる**(ディスク消費・初回時間)。台帳 #7 に注記する。

## 3. 対象外(意図的に実装しない)

- **仮想ファイルシステム**(`gitlab-remote://` 相当)。Eclipse に基盤が無く、本サイクルの範囲を超える。
- **失敗した clone の自動削除・ロールバック**(D1)。
- **プロジェクト種別の推定インポート**(m2e / buildship の呼び出し)。**新規依存を作らないため**(D3)。
- **SSH での clone**(D4)。
- **submodule の取得**、**部分 clone / shallow clone**、**特定ブランチの指定**。
- **clone 済みリポジトリの更新(pull)**。

## 4. 要件

### 4.1 機能要件

| ID | 要件 |
|---|---|
| R1 | F5: アクティブファイルから GitLab プロジェクトを解決し、その **Wiki の clone URL を導出**して clone する |
| R2 | F7: **プロジェクトパスの入力**で対象を指定し、存在を検証したうえで clone する |
| R3 | clone 先は **親ディレクトリ + フォルダ名**をユーザーが指定する |
| R4 | clone 後、可能ならワークスペースへ取り込む(D3) |
| R5 | 進捗を表示し、**キャンセルできる** |
| R6 | 失敗時は **何も削除せず**、残っている可能性のある場所を伝える |

### 4.2 非機能要件

- **新規 OSGi 依存を追加しない。** `build.gradle.kts` / `detekt.yml` の差分ゼロ。
- ディレクトリ構成を変えない(既存 `com.gitlab.eclipse.*` 体系内に追加)。
- `plugin.xml` は追加のみ。
- **ログに URI・パス・トークンを出さない**(既存 A9)。**通知のパスは §13.2 の例外規定に従う。**
- 行長 120 文字以内。

## 5. 前提条件と制約

- **同梱 JGit は 7.5.0**(`org.eclipse.jgit-7.5.0.202512021534-r.jar`)。
- **EGit UI (`org.eclipse.egit.ui`) には依存していない**(ビルドの p2 リポジトリに EGit は入っているが、
  依存として取っているのは `org.eclipse.jgit` / `org.eclipse.jgit.ssh.apache` / `org.apache.sshd.osgi` のみ)。
  **したがって EGit のクローンウィザードへの委譲は「必須依存の追加」を意味し、本設計では採らない。**
- 認証は既存 `GitAuthConfigurer` のみを使う。トークンを URL に埋めない。
- headless の devcontainer では **ワークベンチ依存の動作(取り込み)を検証できない**。手動検証に回す。

## 6. 決定事項(ユーザー判断・2026-08-15)

| ID | 決定 | 根拠 |
|---|---|---|
| **D1** | **失敗しても一切自動削除しない** | §1.2 |
| **D2** | **clone 先の事前判定をせず、JGit の振る舞いに任せる** | 実装最小。非空ディレクトリは `CloneCommand` 自身が拒否する |
| **D3** | **`.project` があればインポート、無ければフォルダ名で単純プロジェクトを作る** | m2e / buildship への依存を作らない |
| **D4** | **HTTPS 固定** | API に到達できているなら必ず通る。SSH は鍵・エージェント・`known_hosts` に左右され、headless で検証できない失敗モードが増える |
| **D5** | **`ProgressMonitor` アダプタ + `setTimeout`** を実装する | 前設計 §14-1 が要求し、F6(PR #77)で見送った宿題。clone はこれが最も要る場面 |

**D2 の帰結として §13.1 と §13.2 の規定が必要になる**(JGit のメッセージを素通ししない / 残物の所有権を主張しない)。

## 7. システム構成

```
[UI] CloneWikiHandler / OpenRepositoryHandler
        │  (SWT: 入力・確認・通知。UI スレッド)
        ▼
[背景] WikiUrlDeriver          … F5 のみ。remote URL → wiki URL
[背景] CloneTargetLookup       … F7 のみ。プロジェクトパス → 存在検証 + clone URL
        │
        ▼
[背景] RepositoryCloner ──uses──▶ EclipseProgressMonitorAdapter
        │                         GitAuthConfigurer(既存)
        ▼
[UI/背景] ClonedProjectImporter … ワークスペースへ取り込む
```

## 8. コンポーネントの責務

| コンポーネント | 責務 | 依存 | 試験 |
|---|---|---|---|
| `EclipseProgressMonitorAdapter` | JGit `ProgressMonitor` を実装し Eclipse `IProgressMonitor` へ委譲。`isCancelled()` が Eclipse 側のキャンセルを返す | なし(純ロジック) | **単体可** |
| `WikiUrlDeriver` | `url.replace(/\.git$/, '.wiki.git')` | なし | **単体可** |
| `CloneTargetLookup` | プロジェクトパス → 存在検証 + `http_url_to_repo` | `GitLabApiClient` | **単体可**(MockK) |
| `RepositoryCloner` | `CloneCommand` の実行。auth / monitor / timeout を設定。**削除経路を持たない** | JGit / `GitAuthConfigurer` | **単体可**(`file://` の実リポジトリから実 clone) |
| `ClonedProjectImporter` | clone 先をワークスペースへ取り込む | Eclipse Resources | **headless 不可** → 手動検証 |
| ハンドラ 2 本 | 入力・確認・通知・スレッド境界 | SWT | **headless 不可** → 手動検証 |

## 9. 処理フロー

### 9.1 `cloneWiki`(F5)

1. **[UI]** アクティブエディタのファイルを取得。無ければ通知して終了。
2. **[背景]** `GitLabProjectUrlResolver.resolveContextForFile` でプロジェクトを解決。
   解決できなければ通知して終了。
3. **[背景]** 解決したリポジトリの **remote URL** から `WikiUrlDeriver` で wiki URL を導出する。
   **HTTPS の remote が無い場合は中止**(D4。SSH remote しか無いリポジトリでは、
   プロジェクトの `http_url_to_repo` を API から取得して代替する — §10.1)。
4. **[UI]** clone 先を入力させる(親ディレクトリ + フォルダ名)。**キャンセルで終了、副作用ゼロ**。
5. **[UI→背景]** `IProgressMonitor` 付きの Job で `RepositoryCloner.clone` を実行。
6. **[背景/UI]** 成功なら `ClonedProjectImporter` で取り込み。
7. **[UI]** 結果を通知。

### 9.2 `openRepository`(F7)

1. **[UI]** プロジェクトパスを入力させる(U3 の方針を踏襲。検索ピッカーは作らない)。
2. **[背景]** `CloneTargetLookup` で存在検証し、`http_url_to_repo` を得る。
   見つからなければ通知して終了。
3. 以降は §9.1 の 4〜7 と同一。

### 9.3 clone の実行(共通)

1. `CloneCommand` に `setURI` / `setDirectory` / `setProgressMonitor(adapter)` /
   `setTimeout(TRANSPORT_TIMEOUT_SECONDS)` を設定し、`GitAuthConfigurer.applyAuth` を通す。
2. `call()` の結果は `Git` を返すので **必ず `close()` する**(ハンドルを開いたままにしない)。
3. **例外・キャンセルのいずれでも、こちらからは何も削除しない**(D1)。

### 9.4 取り込み(共通・D3)

1. clone 先に **`.project` があれば**: `IWorkspace.loadProjectDescription` で読み、
   `IProject.create(description)` → `open()`。
   **同名プロジェクトが既にワークスペースにある場合は取り込みを行わず、その旨を通知する**
   (既存プロジェクトを勝手に差し替えない)。
2. **`.project` が無ければ**: フォルダ名でプロジェクトを作り、`IProjectDescription.setLocation`
   で clone 先を指す。**名前が衝突する場合は取り込まない**(上と同じ)。
3. **取り込みの失敗は clone の失敗ではない。** clone は成功しているので、
   「clone は完了したが取り込めなかった。手動でインポートしてください」と通知する。

## 10. API / インターフェース

### 10.1 REST

| 用途 | メソッド / パス | 使う欄 |
|---|---|---|
| プロジェクトの存在検証 + clone URL | `GET /projects/{namespace%2Fpath}` | `http_url_to_repo` |

**既存 `ProjectDetailService.getProject` は `GitLabProject(id, default_branch)` しか返さない。**
`http_url_to_repo` を **nullable フィールドとして `GitLabProject` に加算的に追加**する
(既存の利用箇所は影響を受けない)。**新規サービスを作るか既存を拡張するかは実装計画で確定する。**

### 10.2 定数(実ソースで確定済み)

| 定数 | 値 | 出典 |
|---|---|---|
| wiki URL 変換 | `url.replace(/\.git$/, '.wiki.git')` | `src/desktop/gitlab/clone/gitlab_remote_source.ts:35-37` |
| transport タイムアウト | **30 秒**(`setTimeout` は秒単位) | 前設計 U6 |

### 10.3 主要インターフェース(案)

```kotlin
sealed interface CloneOutcome {
  data class Cloned(val directory: File) : CloneOutcome
  /** 取り込みまで完了。 */
  data class Imported(val directory: File, val projectName: String) : CloneOutcome
  /** clone は成功したが取り込めなかった。clone 結果は有効。 */
  data class ImportSkipped(val directory: File, val reason: ImportSkipReason) : CloneOutcome
  data object Cancelled : CloneOutcome
  /** [type] は例外の型名のみ。メッセージは載せない(§13.1)。 */
  data class Failed(val type: String, val directory: File) : CloneOutcome
}

enum class ImportSkipReason { NAME_TAKEN, NO_WORKSPACE, IMPORT_FAILED }
```

## 11. データモデル

**永続化する状態を持たない。** 前サイクルで問題になった「予約」「永続記録」は D1 により不要になった。
これは本設計の重要な性質であり、**R7-3(予約と永続記録の間のクラッシュ窓)が構造的に消える**。

## 12. トランザクション境界

**分散トランザクションも、部分的に成功して戻せない操作も持たない** — 戻す操作自体を持たないため。
状態は次の 3 つのいずれかで、**いずれもディスク上に観測できる**:

| 状態 | 観測 | 扱い |
|---|---|---|
| clone 先が存在しない | ディレクトリ無し | 何も起きていない |
| clone 先に途中経過がある | ディレクトリ有り・`.git` が不完全 | **残す。**ユーザーが削除して再実行 |
| clone 完了 | 正常なリポジトリ | 取り込みへ進む |

## 13. エラー処理

### 13.1 JGit のメッセージを素通ししない

JGit の例外メッセージは **リモート URL を含みうる**(前設計 §13)。
ユーザー向けは定型文、ログは**例外の型名のみ**とする。
**`CloneCommand` が非空ディレクトリを拒否したケースも、この定型文に含める**
(D2 により事前判定をしないので、この経路は通常運転で発生する)。

> 例: 「clone できませんでした。指定した場所が空でないか、リポジトリへアクセスできない可能性があります。」

### 13.2 残物の通知(A9 の明示的な例外)

失敗・キャンセルのいずれでも、**「削除していません」と、残っている可能性のある場所**を伝える。

> 「clone は完了しませんでした。**このプラグインは何も削除していません。**
> `<clone 先>` に途中経過が残っている可能性があります。」

- **所有権を主張しない。** D2 により事前判定をしていないため、
  そのディレクトリの中身が全て今回のものだとは断定できない。「残っている可能性がある」と書く。
- **パスを通知に含めるのは、A9 の趣旨に対する意図的な例外である。**
  A9 が守るのは「ユーザーが与えていない情報の漏洩防止」であり、
  **この clone 先はユーザー自身がその場で入力した値**であるため、返して見せることは漏洩に当たらない。
  **ログには出さない**(ログは持ち出されうるため、型名のみ)。

## 14. タイムアウト・リトライ・冪等性

- **タイムアウト**: `TransportCommand.setTimeout(30)`(§10.2)。
  **progress callback に到達しない状態(経路が無い・接続がブラックホール)は
  `isCancelled()` では抜けられないため、タイムアウトが最後の砦になる。**
- **キャンセル**: `EclipseProgressMonitorAdapter.isCancelled()` が Eclipse の `IProgressMonitor` を返す。
  **Eclipse の Job が monitor を持つだけでは JGit のブロッキング transport は止まらない**ため、
  アダプタを必ず `setProgressMonitor` で渡す(前設計 §14-1)。
- **リトライ**: **自動リトライしない。** 失敗を提示し、再実行の判断をユーザーに委ねる。
- **冪等性**: **非冪等。** 同じ場所への 2 度目の clone は JGit が拒否する(D2)。
  これは正常な結果として扱う。

## 15. 並行処理

- clone は既存 `GitOperationGuard` の対象に**しない**。
  guard は**リポジトリ単位**の直列化であり、clone は**まだ存在しないリポジトリ**に対する操作なので
  キーが定まらない。**代わりに、同一 clone 先への同時実行は JGit が拒否する**(D2)。
- **UI スレッド境界**: 入力・確認・通知・取り込みの `IProject` 操作は UI スレッド。
  clone は背景。既存 `WorkspaceProjectPicker` の形(UI で読む → coroutine → `asyncExec` で戻る)を踏襲する。
- 例外がコルーチンから漏れると**共有 plain-Job スコープが道連れで停止する**(issue #16)。
  すべての `launch` で `CancellationException` は再送出、それ以外は封じ込める。

## 16. 認証と認可

- clone は **`GitAuthConfigurer.applyAuth` のみ**を使う。HTTPS + `oauth2:<token>`。
- **トークンを URL に埋めない。ログに出さない。**
- D4 により SSH transport は使わないが、`GitAuthConfigurer` は SSH transport にも
  プラグイン所有の `SshdSessionFactory` を設定する既存挙動を持つ。**この挙動は変更しない。**

## 17. ログ・監視・監査

- ログは**例外の型名と、種別を表す短い語のみ**。URI・パス・トークンを出さない(A9)。
- 通知は §13.2 の規定に従う。

## 18. 障害時の復旧方法

**復旧手順は「ユーザーが残ったディレクトリを削除して再実行する」の 1 つだけである。**
これは D1 の直接の帰結であり、**プラグイン側に復旧経路を持たない**ことが設計上の性質である。

## 19. 既存機能への影響

- `GitLabProject` に nullable フィールドを 1 つ追加する(§10.1)。**既存の利用箇所は影響を受けない。**
- **`EclipseProgressMonitorAdapter` は clone 専用にせず、`BranchPushService` からも使えるようにするか**は
  **未決事項 U3**(§22)。
- それ以外の既存コードは変更しない。

## 20. 移行方法・ロールバック方法

- 永続状態を持たないため、**移行は不要**。
- ロールバックは PR の revert で完結する。**ユーザーのディスクに残った clone は revert しても残る**が、
  それは通常のディレクトリであり、プラグインが管理しているものではない。

## 21. テスト方針と受け入れ条件

### 21.1 テスト方針

| 対象 | 方法 |
|---|---|
| `EclipseProgressMonitorAdapter` | 純ロジック。Eclipse `IProgressMonitor` をモックし、`isCancelled()` の委譲と `beginTask`/`update` の変換を検証 |
| `WikiUrlDeriver` | 純ロジック |
| `CloneTargetLookup` | MockK で `GitLabApiClient` をスタブ |
| `RepositoryCloner` | **`file://` の実リポジトリから実 clone** して検証(JGit の実挙動が価値であるため、モックしない) |
| `ClonedProjectImporter` / ハンドラ | **headless では試験不能**。手動検証手順を PR 本文に記載 |

### 21.2 受け入れ条件

| ID | 条件 |
|---|---|
| C1 | F5 / F7 が `plugin.xml` に登録され、メニューから起動できる |
| C2 | **clone 先の入力をキャンセルした時点でファイルシステムが不変**である |
| C3 | 正常系: clone が完了し、`.project` があればワークスペースに現れる |
| C4 | **`.project` が無い場合もフォルダ名でプロジェクトが作られる**(D3) |
| C5 | **同名プロジェクトが既にある場合、既存を差し替えず通知する** |
| C6 | **失敗・キャンセルのいずれでもプラグインが削除を行わない**(D1)。通知が §13.2 の文面である |
| C7 | **無応答 transport に対してタイムアウトで終了**し、キャンセルが JGit に伝播する(D5) |
| C8 | **JGit の例外メッセージが通知にもログにも現れない**(§13.1) |
| C9 | F5 が wiki URL を `<remote>.wiki.git` に導出する |
| C10 | F7 が存在しないプロジェクトパスに対して clone を開始しない |
| C11 | 新規 OSGi 依存が無く、`build.gradle.kts` / `detekt.yml` の差分が無い |

## 22. 未決事項(推測で確定しない)

| ID | 内容 |
|---|---|
| **U1** | **clone 先の入力 UI**。`DirectoryDialog`(親)+ `InputDialog`(フォルダ名)の 2 段か、単一の入力か。実装計画で確定する |
| **U2** | **F5 で HTTPS remote が無い場合**、プロジェクトの `http_url_to_repo` を API から取得して wiki URL を導出する経路を持つか、単に中止するか |
| **U3** | **`EclipseProgressMonitorAdapter` を `BranchPushService` にも適用するか**(PR #77 で見送った §14-1 の宿題を、ここで全面的に返すか、clone に限るか) |
| **U4** | **取り込み時のプロジェクト名衝突**の扱い。通知して中止(本設計案)か、別名を提案するか |
| **U5** | **F7 のプロジェクト指定**を、PR #78 で入れた `SelectedProjectStore` の割当と連携させるか(clone 後に自動割当するか) |

## 23. 想定されるリスク

| リスク | 影響 | 緩和 |
|---|---|---|
| **【受容】失敗した clone がディスクに残る** | ディスク消費・混乱 | **D1 の意図的な帰結。**§13.2 で必ず場所を伝える。所有権は主張しない |
| clone 先が非空で JGit に拒否される | 使い勝手 | §13.1 の定型文に「空でない可能性」を含める。D2 の帰結 |
| 大きなリポジトリの clone が長時間 UI を占有する | UI 凍結 | `IProgressMonitor` 付き Job + キャンセル(D5) |
| キャンセルしても JGit transport が止まらない | 機能停止 | アダプタ + `setTimeout`(D5 / C7) |
| 取り込みでワークスペースの既存プロジェクトを壊す | データ損失 | **同名なら取り込まない**(C5) |
| VSCode 版との挙動差(仮想 FS 対 ローカル clone) | 期待との齟齬 | 前設計 §4 の決定どおり。台帳 #7 に注記 |
| JGit の例外メッセージにリモート URL が含まれる | 情報露出 | §13.1 |

## 24. レビューで特に確認してほしい点

1. **D1(何も削除しない)が、前サイクルで未解決だった R3-7 / R5-2 / R6-1 / R7-1 / R7-2 / R7-3 を
   本当にすべて解消しているか。** 消さないことで新たに生じる問題は無いか。
2. **§13.2 のパス通知が A9 の例外として妥当か。** ログとの区別の付け方は十分か。
3. **§15 の「clone を `GitOperationGuard` の対象にしない」判断が妥当か。**
   同一 clone 先への同時実行を JGit の拒否だけに委ねてよいか。
4. **§9.4 の取り込みが、既存ワークスペースを壊しうる経路を残していないか。**
5. **§22 の未決事項が、実装開始前に決めるべきものとして過不足ないか。**
