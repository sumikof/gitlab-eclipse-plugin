# D13 エディタ行コメント + エディタ内スレッド UI 基盤 設計書

- 版: 第 2 版(2026-09-27、Codex round 1 反映)
- ベース: `gitlab-ls-9.3.0` @ `5fba3e8`
- 関連: ロードマップ #8 / パリティ台帳 #7(D13「コメント作成」🟡)/ Phase 6 残余 #14 / 前提設計 = Phase 5A 設計書 §21(Close 済み PR #45、`git fetch origin refs/pull/45/head` → `docs/review/mr-discussions-design.md` L1285-1492)
- 参照実装: `gitlab-workflow` v6.85.3(`./out/gitlab-vscode-extension`、読み取り専用)

> 本書の「確定」は実ソース(参照実装 / 本リポジトリ / Eclipse R4_33 / GitLab ドキュメント)で裏取り済みの事実を指す。裏取りできなかったものは §26「未決事項」に分離する。

---

## 1. 背景と目的

VSCode 版は MR のレビュー diff(`gl-review` スキーム)上で任意の行に新規 diff スレッドを作成できる(`vscode.comments` API)。Eclipse には同等 API が無く、Phase 5A(#46 / #48)は「スレッドへの返信」と「MR 全体へのコメント」だけを実装し、**エディタ行からの新規 diff スレッドを別サイクルへ保全した**(台帳 D13 が 🟡)。

本サイクルの目的は 2 つ。

1. **D13 のエディタ行コメントを完成させる**(台帳 76 → 77 / 85)。
2. **エディタ内スレッド UI 基盤**(ルーラー注釈 + ホバー + 行に吸着する非モーダルポップアップ)を作り、後続の Quick Chat(D4 の 3 件)が再利用できる形にする。

## 2. 対象範囲

| # | 機能 | 内容 |
|---|---|---|
| F1 | MR レビューセッション | MR の変更ファイルを開いたエディタ(またはエディタから明示有効化したエディタ)に、MR・diff version・行対応表・スレッドを結び付ける |
| F2 | 既存スレッドの表示 | 最新 MR version に付いた diff スレッドを、該当行のルーラー / 概要ルーラーに注釈として表示。ホバーで要約を表示。解決済みは別アイコン |
| F3 | スレッドポップアップ | 行に吸着する非モーダルのポップアップで、スレッド本文(Markdown ソースを読み取り専用テキストで表示)を表示し、返信・解決 / 未解決化を行う |
| F4 | 新規 diff スレッド作成 | エディタの任意の行(追加行 / 変更なし行)から、ポップアップの入力欄で本文を入れて `createDiffNote` を送る |
| F5 | 基盤の再利用契約 | ポップアップと注釈層を「MR 固有でない」インターフェースで提供する(Quick Chat が後で使う) |

ユーザー決定(2026-09-27、外部監督経由で承認済み):

| 論点 | 決定 |
|---|---|
| Q1 コメント面 | 作業ツリーの通常エディタ(新規 diff エディタは作らない) |
| Q2 スレッド UI | ルーラー注釈 + ホバー + 非モーダルポップアップ(Quick Chat に再利用) |
| Q3 既存スレッド | 表示・返信・解決に対応。編集・削除は従来どおりサイドバー |
| Q4 対象行 | 追加行 + 変更なし行 |
| Q5 有効化 | サイドバーの MR 変更ファイルから開いたエディタ + エディタ右クリックからの明示有効化 |
| Q6 本文表示 | Markdown ソースを読み取り専用テキストで表示 |
| Q7 表示対象 | 最新 MR version の未解決・解決済みスレッド(解決済みは別アイコン)。古くなったスレッドと削除行だけのスレッドはサイドバーのみ |

## 3. 対象外

- Quick Chat 本体(`gl.openQuickChat` / `gl.closeQuickChat` / 送信 / スニペット)— 次サイクル。本サイクルは F5 の契約のみ。
- 古い側(base)の表示・古い側へのコメント(削除行へのコメント)— 作業ツリーに存在しないため。**既知の制限**。
- 複数行範囲のコメント(参照実装 6.85.3 にも無い)、draft note(参照実装にも無い)、suggestion ブロックの書き換え表示。
- エディタ内からの編集・削除(Q3)。
- ポップアップでの Markdown レンダリング / `bodyHtml`(Q6)。
- 古くなったスレッドの近似位置表示(Q7)。
- 新規 diff / compare エディタ、`org.eclipse.compare` の導入(Q1)。

## 4. 現在の課題

- MR 変更ファイルは「作業ツリーのファイルを通常エディタで開く」だけで(`OpenMrFileHandler.kt:41-120`)、エディタ上に MR の情報が何も出ない。
- 新規 diff スレッドを作る手段が無い。`DiscussionWriteService.createNote` は `position` を持たない(`api/DiscussionWriteService.kt:85-100, 291-303`)。
- diff の hunk テキストを保持していない。`GitLabMrVersion.Diff` は `old_path/new_path/new_file/deleted_file/renamed_file` のみ(`api/model/GitLabMrVersion.kt:54-60`)。
- スレッド取得クエリが `position.diffRefs` を選択していない(`api/DiscussionService.kt:101-130`)ため、スレッドが最新 version のものか判定できない。

## 5. 要件

### 5.1 機能要件

- **FR-1(セッション確立: サイドバー経由)** サイドバーの `ChangedFileNode` から `OpenMrFileHandler` でファイルを開いたとき、既存ゲート(リポジトリ一意・HEAD == `diffHeadSha`・パス内包)を通過してエディタが開いたら、そのエディタに MR レビューセッションを確立する。
- **FR-2(セッション確立: エディタ経由)** 任意の作業ツリーのテキストエディタで「GitLab ▸ Add Merge Request Comment on This Line…」を実行したとき、セッションが無ければ Phase 5A §21 の G1〜G9 と同じ手順(現ブランチ → MR)で確立してから F4 を続ける。
- **FR-3(表示対象)** 次をすべて満たすスレッドだけを注釈にする: 先頭ノートの `position.positionType == "text"` / `position.diffRefs` の `baseSha`・`startSha`・`headSha` がセッションの version と一致 / `newPath` がエディタのファイルの MR 内パスと一致 / `newLine != null`。解決済み(`resolved == true`)は別の注釈タイプ。
- **FR-4(ホバー)** 注釈のホバーに「作成者・本文の先頭(1 行、上限 200 文字)・返信数・解決状態」を出す。HTML としてエスケープする。
- **FR-5(ポップアップ)** 行に吸着する非モーダルのポップアップで、スレッドの全ノート(作成者 username・作成日時・本文の Markdown ソース)を読み取り専用で表示する。フォーカスがエディタへ戻っても閉じない。ウィンドウごとに同時に 1 つ。
- **FR-6(返信・解決)** ポップアップから返信(先頭ノートの `userPermissions.createNote` が true のとき)、解決 / 未解決化(`resolvable && resolveNote` のとき)を行う。送信経路は既存の `runDiscussionWrite` / `DiscussionWriteLauncher` を再利用する。
- **FR-7(新規作成)** 追加行と変更なし行のどちらにもコメントできる。`DiffPositionInput` は §12.3 のとおり(追加行 = `newLine` のみ、変更なし行 = `oldLine` + `newLine`)。
- **FR-8(本文保持)** 送信が成功するまで入力本文を失わない。失敗・曖昧時の扱いは既存ランチャーの終端処理(`[Retry]` / `[Copy text]` / `[Send again]`)に従う。
- **FR-9(権限)** `mergeRequest.userPermissions.createNote == false` のセッションでは新規作成・返信を拒否する。メニューは同期フィルタできないため常に表示し、実行時に拒否する(Phase 5A §21.4 と同じ明示的例外)。
- **FR-10(再表示)** 書き込み成功後、セッションのスレッドを再取得して注釈とポップアップを更新する。サイドバーの Discussions も既存の `reloadDiscussionsFor` で再取得する。
- **FR-11(解放)** その文書を表示する最後のエディタが閉じたら、セッションと注釈とルーラーリスナーを解放する。bundle 停止時はすべて解放し、ポップアップを閉じる。

### 5.2 非機能要件

- 新規 OSGi バンドル依存を追加しない(§6.2)。ディレクトリ構成・ビルドシステムを変えない。
- ネットワークと JGit は必ず background。SWT は必ず UI スレッド。
- token・本文・diff テキストをログに出さない。
- 表示に関わる判定(表示対象の選別・行対応・position の組み立て・ゲート列)は SWT 非依存の層に置き、headless でテストする。

## 6. 前提条件と制約

### 6.1 共通制約(#8)

ディレクトリ構成の変更禁止(`com.gitlab.eclipse.*` 内に追加)/ ビルドシステムの変更禁止 / プロトコル定数は実ソースで確定してから計画に埋め込む。

### 6.2 新規バンドル依存を導入しない(確定)

使う API はすべて既存の `Require-Bundle`(`build.gradle.kts:162-193` の `eclipseDependencies`)に含まれる: `org.eclipse.jface.text` 3.25.200 / `org.eclipse.ui.workbench.texteditor` 3.18.0 / `org.eclipse.ui.editors` 3.18.0 / `org.eclipse.jface` 3.35.0 / `org.eclipse.jgit`。`org.eclipse.compare` は使わない。

### 6.3 検証上の制約

devcontainer は headless で、エディタ・ルーラー・ポップアップ・ホバーの実表示は検証できない。これらは §25 の手動検証で確認する。**headless で検証できない判断は、本書で Eclipse R4_33 の実ソースを根拠に確定する**(§6.4)。

### 6.4 Eclipse プラットフォームの確定事実(R4_33、`ui/R4_33` = `eclipse-platform/eclipse.platform.ui` tag R4_33)

| # | 事実 | 根拠 |
|---|---|---|
| E1 | 全 `AbstractTextEditor` のルーラーメニューに寄与できる ID は `#AbstractTextEditorRulerContext`(`COMMON_RULER_CONTEXT_MENU_ID`)。JDT の `CompilationUnitEditor` も登録を上書きしないので含まれる | `AbstractTextEditor.java` L270-273, L3441-3462 / jdt.ui `CompilationUnitEditor.java` L1054-1055 |
| E2 | ルーラーでクリックした行は `editor.getAdapter(IVerticalRulerInfo).getLineOfLastMouseButtonActivity()`(0 始まり、範囲外は -1)。マウス押下ごとに更新され、メニュー表示後のハンドラ実行時点でも有効 | `CompositeRuler.java` L679-685, L739-743 / `AnnotationRulerColumn.java` L250-295 |
| E3 | エディタの注釈モデルは `documentProvider.getAnnotationModel(input)`。ワークスペースファイルでは `ResourceMarkerAnnotationModel`(JDT は `CompilationUnitAnnotationModel`)で、いずれも `IAnnotationModelExtension` を実装する。`addAnnotationModel(key, sub)` で独自のサブモデルを接続できる | `TextFileDocumentProvider` L601-605 / `ResourceMarkerAnnotationModelFactory` / `AnnotationModel.java` L942-950 |
| E4 | 注釈の `Position` は文書の既定カテゴリに登録され、編集に追従して移動する | `AnnotationModel.java` L360-373, L431-434 |
| E5 | 垂直ルーラーのホバーは `DefaultAnnotationHover` が `annotation.getText()` を表示する。改行は保持され、`<` `&` は HTML として解釈される(エスケープ必須) | `DefaultAnnotationHover.java` L59-117, L160-165 / `HTML2TextReader.java` L184-194 |
| E6 | 概要ルーラー / 垂直ルーラーへの表示は `markerAnnotationSpecification` の `overviewRulerPreferenceKey` / `verticalRulerPreferenceKey` 等で決まる(現行の Duo 用 spec にはキーが無い) | `TextSourceViewerConfiguration.java` L110-143 |
| E7 | `PopupDialog` は自 shell の Deactivate(GTK では親の Activate)で自動的に閉じ、止める手段は利用者の Pin 操作だけ。**フォーカスが戻っても開いたままのポップアップに `PopupDialog` は使えない** | `PopupDialog.java` L368, L579-660 |
| E8 | 行の画面座標: `JFaceTextUtil.modelLineToWidgetLine(viewer, line)`(折りたたみ等で非表示なら -1)→ `StyledText.getLinePixel(widgetLine)`(引数は [0, lineCount] に丸められるので -1 は呼び出し側で弾く)→ `toDisplay` | `JFaceTextUtil.java` L237-260 / `StyledText.java` L3865-3872 |
| E9 | `AbstractTextEditor.getAdapter` は `ITextViewer` / `Control` / `IVerticalRulerInfo` を返す | `AbstractTextEditor.java` L6180-6190 |

### 6.5 GitLab の確定事実

| # | 事実 | 根拠 |
|---|---|---|
| G-1 | `createDiffNote(input: {noteableId: NoteableID!, body: String!, position: DiffPositionInput!})` → `note`, `errors` | GraphQL reference `Mutation.createDiffNote` / 参照実装 `src/desktop/gitlab/graphql/create_diff_comment.ts:16-28` |
| G-2 | `DiffPositionInput`: 必須 `headSha` / `startSha` / `paths{newPath, oldPath}`、任意 `baseSha` / `newLine` / `oldLine` | GraphQL reference `DiffPositionInput` / 参照実装 `create_diff_comment.ts:4-14`, `mr_discussion_commands.ts:42-51` |
| G-3 | 追加行 = `newLine` のみ / 削除行 = `oldLine` のみ / 変更なし行 = 両方 | REST `discussions.md` L1078-1111 / 参照実装 `mr_discussion_commands.ts:32-41`, `diff_line_count.ts:135-144` |
| G-4 | `DiffPosition.diffRefs{baseSha headSha startSha}` を選択できる。GraphQL に `originalPosition` や `outdated` は無い。push 時に追跡可能なノートは `position` が新しい diff refs に書き換えられ、追跡不能なら古いまま残る。**最新判定 = `position.diffRefs` が MR の現在の diff refs と一致すること** | GraphQL reference `DiffPosition` / `DiffRefs` / gitlab `app/services/discussions/update_diff_position_service.rb` L5-33 / `diff_positionable_note.rb` L148-156 |
| G-5 | REST versions の `diffs[].diff` は unified diff の本文。巨大・生成ファイルは `diff == ""`(18.4 以降は `too_large` / `collapsed` も true) | `merge_requests.md` L4157-4215 / `lib/gitlab/git/diff.rb` L235-253 |
| G-6 | 参照実装は `diff == ""` で「この MR のファイル diff は大きすぎる」として作成を拒否する | `mr_discussion_commands.ts:27-31` |

## 7. システム構成

```
                       ┌─────────────────────────── SWT 非依存(headless テスト) ───────────────────────────┐
 Sidebar ChangedFileNode│  DiffLineMap(hunk 解析・新行→旧行対応)                                              │
   └ OpenMrFileHandler ─┼─▶ ReviewSessionLoader(接続捕捉 → version → diff → スレッド → 配置)                  │
                        │  ThreadPlacement(FR-3 の選別)   DiffPositionBuilder(FR-7)                          │
 Editor/Ruler menu ─────┼─▶ LineCommentFlow(G1〜G9 のゲート列、効果は注入)                                    │
                        │  InlineThreadModel(ポップアップの表示モデル = F5 の契約)                            │
                        └──────────────────────────────────────────────────────────────────────────────────────┘
                                          │ asyncExec                      ▲ runDiscussionWrite / Launcher(既存)
                       ┌──────────────────▼───────────── SWT 層(薄い殻・手動検証) ─────────────────────────┐
                       │ ReviewSessionRegistry(UI スレッド専有: 文書 → セッション、参照カウント、partClosed)  │
                       │ ThreadAnnotationAttacher(サブ注釈モデル接続 / 解除、ルーラーのマウスリスナー)       │
                       │ InlineThreadPopup(Shell: TOOL|RESIZE|TITLE|CLOSE、行に吸着、ウィンドウごとに 1 つ)   │
                       └──────────────────────────────────────────────────────────────────────────────────────┘
```

新パッケージ: `com.gitlab.eclipse.mergerequests.review`(セッション・フロー・MR 固有の配線)と `com.gitlab.eclipse.views.inlinethread`(MR に依存しない基盤 = ポップアップと注釈の汎用部。Quick Chat が再利用する)。いずれも既存の `com.gitlab.eclipse.*` 配下。

## 8. コンポーネントの責務

### 8.1 SWT 非依存層

| コンポーネント | 責務 | 依存 |
|---|---|---|
| `DiffLineMap` | 1 ファイル分の unified diff を解析し、新ファイルの各行(1 始まり)を `Added` / `Unchanged(oldLine)` に分類する。hunk 外の行は直前 hunk までの行数差で `oldLine` を求める。`diff == ""` は `Unavailable`(巨大・生成ファイル) | なし |
| `ThreadPlacement` | `GitLabDiscussion` の一覧とセッション(version の 3 SHA、ファイルの `newPath`)から、FR-3 を満たすスレッドを `(oneBasedLine, discussion, resolved)` に変換する | model |
| `DiffPositionBuilder` | `(version, diffEntry, lineMap, oneBasedLine)` → `DiffPositionInput` 変数マップ、または拒否理由 | `DiffLineMap` |
| `ReviewSessionLoader` | 捕捉済み接続 1 つで version 取得 → 対象 diff の特定 → `DiffLineMap` → スレッド取得 → `ThreadPlacement`。結果は `ReviewSessionSnapshot`(不変)または拒否理由 | 既存サービス(接続引数付き) |
| `LineCommentFlow` | 新規作成の 1 試行(`lineCommentAttempt`、§9.3)。ゲート G5〜G9 と送信を毎試行実行する。効果(JGit 読み取り・ファイル読み取り・API)は関数として注入する | 上記 |
| `InlineThreadModel` | ポップアップの表示モデル: タイトル、エントリ列(作成者・日時・本文テキスト)、状態(未解決 / 解決済み / 新規)、利用可能な操作(返信・解決・未解決化・作成)、入力欄のプレースホルダ。MR に依存しない | なし |
| `ReviewSessionGeneration` | セッションごとの再取得の世代(latest-wins)。UI スレッド専有 | なし |

### 8.2 SWT 層

| コンポーネント | 責務 |
|---|---|
| `ReviewSessionRegistry` | UI スレッド専有。キー = 文書(`IDocument` の同一性)。値 = セッション(`SessionIdentity` 付き)+ 接続中のエディタ集合。identity が異なる確立要求は置き換え(§9.5)。`IPartListener2.partClosed` で参照を減らし、0 で解放(FR-11)。bundle 停止で全解放 |
| `ThreadAnnotationAttacher` | 文書の注釈モデル(E3)にキー `com.gitlab.eclipse.mrReviewThreads` でサブモデルを接続し、注釈を差し替える。注釈モデルが `null` または `IAnnotationModelExtension` でない場合は表示を諦め、ログに 1 行残す(コメント作成は可能)。セッション確立時にルーラー制御へマウスリスナーを付け、解放時に外す |
| `InlineThreadPopup` | `Shell(workbenchShell, SWT.TOOL or SWT.RESIZE or SWT.TITLE or SWT.CLOSE)`。`PopupDialog` は使わない(E7)。E8 の手順で行の直下に配置し、行が非表示(-1)なら `revealRange` 後に再計算、それでも -1 ならエディタ領域の中央上部に置く。Esc と閉じるボタンで閉じる。エディタが閉じたら閉じる。スクロールには追従しない(開いた時点の位置のまま) |
| ハンドラ 2 本 | `AddLineCommentHandler`(エディタ / ルーラーのメニュー)、`OpenLineThreadHandler`(ルーラーメニュー)。UI ターンで行と本文をスナップショットして SWT 非依存層へ渡す |

### 8.3 既存コードへの追加(すべて追加のみ・既定値で従来挙動)

| 箇所 | 変更 |
|---|---|
| `GitLabMrVersion.Diff` | `diff: String?`、`tooLarge: Boolean?`、`collapsed: Boolean?` を追加(Gson の null 許容) |
| `DiscussionService.GET_MR_DISCUSSIONS_QUERY` | `position { diffRefs { baseSha headSha startSha } }` を追加。`GitLabNotePosition` に `diffRefs: GitLabDiffRefs?` |
| `DiscussionWriteService` | `createDiffNote(connection, mrGid, body, position)` と `createDiffNoteVariables(...)` を追加。3 層のエラー検査は既存 `requireNoPayloadErrors` を再利用 |
| `DiscussionWriteKey` | `forEditorLine(filePath, oneBasedLine)` を追加(§9.3.1) |
| `DiscussionWriteOutcome` / `DiscussionWriteLauncher` | `Rejected(message)` と、その終端分岐(`promptCopyText`)を追加。既存 5 分岐は不変(§9.3.1) |
| `CurrentBranchMrLookup.lookup` / `ProjectDetailService.getProject` | `connection: ConnectionSnapshot? = null` を追加(Phase 5A §21.1。既定 null で現行挙動と同一) |
| `ChangedFileNode` | `mrRef: MergeRequestRef? = null`(instanceUrl・authFingerprint・projectId・mrIid・mrGid・namespaceWithPath)を追加。`SidebarViewModel.toChangedFileNode` が `DiscussionsSectionNode` と同じ条件で埋める |
| `OpenMrFileHandler` | エディタを開けたら、`node.mrRef != null` のときだけ `ReviewSessionLoader` を起動する。開く処理・ゲート・文言は変えない |
| `plugin.xml` | コマンド 2、メニュー寄与(`popup:#AbstractTextEditorContext` の既存 GitLab サブメニュー / `popup:#AbstractTextEditorRulerContext`)、注釈タイプ 2 と `markerAnnotationSpecification` 2 |

## 9. 処理フロー

### 9.1 セッション確立(FR-1、サイドバー経由)

```
[UI] OpenMrFileHandler: 既存どおりゲート → エディタを開く(既存)
[UI] 開いたエディタ E と node.mrRef を ReviewSessionRegistry.begin(E, identity) へ
      identity = SessionIdentity(instanceUrl, authFingerprint, projectId, mrIid, headSha, newPath)
      → 同じ文書に identity が等しいセッションがあれば E を接続集合に加えて終わり
      → identity が異なるセッションがあれば、それを置き換える(§9.5)
      → 無ければ世代を進め、background へ
[BG] conn = captureConnection()(失敗 → 通知して終了)
     conn の (instanceUrl, fp) が mrRef と一致しなければ拒否(サイドバー取得後に接続が変わった)
     version = getLatestMrVersion(projectId, iid, conn)
       version.headCommitSha != diffHeadSha → 拒否(開いた後に push された)
     entry = version.diffs で newPath == relPath のもの → 無ければ拒否
     lineMap = DiffLineMap.parse(entry.diff)
     result = DiscussionService.getDiscussions(conn, namespaceWithPath, iid, deadline)(既存・diffRefs 追加)
       → discussions と canCreateNote(mergeRequest.userPermissions.createNote)を同時に得る
     placements = ThreadPlacement(result.discussions, version, entry.newPath)
[UI] 世代が最新 かつ 文書にまだ E が開いている かつ bundle が active → 注釈を接続
```

拒否は情報通知 1 回(エディタは開いたまま。コメント機能だけが無効)。

### 9.2 スレッドポップアップ(FR-5 / FR-6)

- 入口: (a) ルーラー右クリック「Open Merge Request Thread」(E2 の行に注釈があるときだけ有効)、(b) 注釈アイコンの左クリック(ルーラー制御のマウスリスナー、mouseUp・ボタン 1。**ベストエフォート** — §26 U1)。
- 同じ行に複数のスレッドがあれば、ポップアップ上部の選択(「Thread 1 of N」)で切り替える。
- 返信 / 解決: `DiscussionWriteKey.forDiscussion` と既存 `DiscussionWriteLauncher.launch` を使う。本文の入力はポップアップの入力欄が担い、既存の `CommentInputDialog` は開かない。終端処理(成功 / Definite / Ambiguous / GateRejected / Aborted)はランチャーの既存 UI をそのまま使う。成功時は入力欄を空にして FR-10。
- 権限の無い操作のボタンは出さない(スレッドごとの `userPermissions` はノードに既にある)。

### 9.3 新規 diff スレッド作成(FR-2 / FR-7)

Phase 5A §21.2 のゲート列を出発点とし、**入力を modal ダイアログからポップアップに置き換える**。

```
[UI ターン 1 — メニュー実行時]
  G1 アクティブパートが ITextEditor
  G2 入力がローカルファイル(IFileEditorInput、または file: の IURIEditorInput)
  G3 エディタが dirty でない(早期拒否。保証は G8)
  SNAPSHOT: oneBasedLine(エディタメニュー = キャレット行 / ルーラーメニュー = E2 の行、いずれも +1)、
            documentText = document.get()、encoding = エディタの文字コード(§9.3.2)、filePath(絶対パス)
  ポップアップを「新規」モードで開く(非モーダル。以降エディタは自由に操作できる)
[UI ターン 2 — 送信ボタン押下時]
  startEpoch = DiscussionGenerationRegistry.currentEpoch(ここで凍結)
  key = DiscussionWriteKey.forEditorLine(filePath, oneBasedLine)   ← UI ターンで確定できるローカル識別子
  DiscussionWriteLauncher.launch(key, body, startEpoch, write = lineCommentAttempt)   ← 既存の公開 API をそのまま使う
[BG] lineCommentAttempt(body, epoch) = 1 回の試行(下記を毎回すべて実行する)
  conn = captureConnection()(UnstableConnectionException は GateRejected。既存 runDiscussionWrite と同じ)
  セッションあり: その identity の (instanceUrl, fp) と conn が一致しなければ GateRejected
  セッションなし: G5 リポジトリ一意 → G6 現ブランチの MR(lookup(context, branch, conn))
  G6b 権限: mergeRequest.userPermissions.createNote(毎試行、getDiscussions の先頭ページまたは権限クエリ)
  G7 HEAD sha == MR の diff head sha
  G8 本文の同一性(§9.3.2)
  G9 version = getLatestMrVersion(conn)、version.head == G7 の sha、対象 diff エントリ、
     DiffLineMap が Unavailable でないこと → DiffPositionBuilder で position を凍結
  pinnedConnectionFor → ライフサイクル検証(epoch 引数と比較)→ createDiffNote → 3 層検査 → 分類
  ゲート不成立は Rejected(reason) を返す(送信なし)
[UI] 既存ランチャーの終端処理(Rejected を 1 分岐追加、§9.3.1)。成功時はポップアップを閉じ、
     セッションが無ければ確立し、あれば再取得(FR-10)
```

#### 9.3.1 ランチャー契約(Codex round 1 P1 反映)

- **既存の `DiscussionWriteLauncher.launch(key, body, startEpoch, write)` をそのまま使う。**キーは UI ターンで確定している必要があるため、エディタ経路のキーは MR の識別子ではなく**ローカル識別子** `DiscussionWriteKey.forEditorLine(filePath, oneBasedLine)`(`instanceUrl = ""`、`authFingerprint = ""`、`targetKind = "editorLine"`、`targetId = "<絶対パス>:<oneBasedLine>"`)とする。同じファイルの同じ行への同時送信はこれで 1 本に絞られる。Phase 5A §21.5 の「background でガードを取得する例外」は**不要になり、採らない**。
- **G5〜G9 はすべて `write` の中にあり、試行ごとに毎回実行される。**ランチャーの `[Retry]` / `[Send again]` は `relaunch` で同じ `write` を呼び直すため、再試行のたびに HEAD・version・権限・本文の同一性が再検証される。事前検証だけをして別ジョブで `launch` する構成は採らない。
- 再試行で使う `documentText` / `oneBasedLine` は UI ターン 1 の凍結値のままである(本文を編集したユーザーが再試行すると G8 で拒否され、その場合は新しくメニューから始め直す)。
- `DiscussionWriteOutcome` に `Rejected(message: String)` を追加する。意味は「ゲートで拒否した・送信していない・再送しても同じ結果」で、終端は `promptCopyText(message, body)`(本文保持、再送ボタンなし)。既存の 5 分岐は不変。`when` の網羅性のため既存の `when` 箇所(7 箇所)に分岐を足す。
- `reload`(終端から呼ばれる)は、エディタ経路では「そのセッションの再取得」とし、注釈とポップアップへ反映できたときだけ `LoadOutcome.Applied` を返す。セッションが無い・置き換えられた場合は `Skipped`(= `[Send again]` を出さない)。サイドバーの再取得は結果を待たずに並行して依頼する。
- 返信・解決はこれまでどおり `forDiscussion` キーで `launch` する(ゲートは既存の `runDiscussionWrite`)。

#### 9.3.2 G8 本文の同一性(Codex round 1 P1 反映)

バイト列の厳密比較はやめる。`core.autocrlf` / `eol` 属性 / BOM / 非 UTF-8 の文字コードでは、未編集のファイルでも作業ツリーと HEAD の blob のバイト列が一致しないためである。代わりに、次の 3 段で「凍結した本文の N 行目 = HEAD の N 行目」を保証する。

| 段 | 判定 | 拒否条件 |
|---|---|---|
| G8a | 対象パスの Git 属性に `filter`(LFS など clean/smudge)と `working-tree-encoding` が設定されていない | 設定されていれば拒否(行の対応を保証できない) |
| G8b | ディスク上のファイルを**エディタの文字コード**で復号し、先頭の BOM を取り除いた文字列が `documentText` と一致(改行は正規化しない) | 不一致なら拒否 |
| G8c | JGit の `status().addPath(path)` で、そのパスが modified / untracked / missing / conflicting のいずれでもない(JGit は `core.autocrlf` と `text` / `eol` 属性を適用して比較する) | 該当すれば拒否 |

G8b の読み取り → G8c → G8b と同じファイルの再読み取りを行い、両読み取りの内容が同一であることを確認する(G8c の最中の書き換えを弾く)。

根拠: G8c が成り立てば、作業ツリーと HEAD の blob の差は改行変換だけであり、改行変換は行の数と順序を変えない。G8b が成り立てば、凍結した本文と作業ツリーは同じ文字列である。したがって、凍結した本文の N 行目は HEAD の N 行目と一致する。

エディタの文字コードは `IFile.getCharset()`(ワークスペースファイル)または `IEncodingSupport.getEncoding()`(外部ファイル)から UI ターン 1 で取得する。取得できなければ拒否する。

**既知の制限**: `working-tree-encoding` と `filter` を使うファイルにはコメントできない。

**スナップショットと送信の分離(Phase 5A §21.2「2 つの捕捉時点」を踏襲)**: 行番号と本文はポップアップを開く前に凍結し、`startEpoch` は送信時に凍結する。ポップアップが開いている間にユーザーが編集しても、送る行番号は凍結した本文に対するもので、その本文が HEAD と一致することを G8 が保証する。

**本文の保持**: ポップアップを閉じるまで入力欄の本文は残る。Definite / Ambiguous はランチャーの既存ダイアログ([Retry] / [Copy text] / [Send again])で扱う。

### 9.4 再取得(FR-10)

書き込み成功後、セッションの世代を進めて §9.1 の BG 部分(version は取り直す)を実行し、UI で注釈と開いているポップアップの表示モデルを差し替える。サイドバーは既存 `reloadDiscussionsFor` を呼ぶ。

### 9.5 セッションの置き換え(同じ文書に別の identity)

レジストリのキーは文書だが、**セッションを共有するのは `SessionIdentity` が完全に一致するときだけ**である。別 MR の `ChangedFileNode` から同じファイルを開いた、アカウントを切り替えた、同じ MR の新しい version(head が違う)で開き直した、のいずれでも identity は異なる。そのときは次を UI ターン内で行ってから新しいセッションを確立する。

1. 旧セッションの世代を無効化する(到着中の再取得結果を捨てる)。
2. 旧セッションの注釈をサブモデルから取り除く。
3. 旧セッションに結び付いたポップアップを閉じる(入力中の本文があれば、閉じる前に `[Copy text]` 相当で退避する。文言は実装段階)。
4. 接続中のエディタ集合は新セッションへ引き継ぐ(同じ文書を表示しているため)。

進行中の書き込みは置き換えの影響を受けない(書き込みは試行ごとにゲートを再評価し、§9.3)。置き換え後の終端処理は、試行が持つ identity のセッションがもう無ければ再取得を `Skipped` とする。

### 9.6 解放(FR-11)

`partClosed` でエディタを接続集合から外し、空になったらサブ注釈モデルを外し、ルーラーのマウスリスナーを外し、そのエディタに吸着したポップアップを閉じる。bundle 停止時は `DiscussionGenerationRegistry` の停止と同じ経路で全セッションを解放する。

## 10. 等価表(VSCode → Eclipse)

| VSCode | Eclipse |
|---|---|
| `gl-review` の diff(左右) | 作業ツリーの通常エディタ(新しい側のみ) |
| `commentingRangeProvider`(新しい側 = 追加行のみ / 古い側 = 全行) | 新しい側の全行(追加行 = `newLine`、変更なし行 = `oldLine`+`newLine`)。削除行は不可 |
| `CommentThread`(行間のウィジェット) | ルーラー注釈 + ホバー + 非モーダルポップアップ |
| `gl.createComment` | Add Merge Request Comment on This Line…(エディタ / ルーラー右クリック) |
| 返信 / 解決 / 編集 / 削除 | 返信 / 解決はポップアップ、編集 / 削除はサイドバー(既存) |
| 失敗コメント(`failed-comment` + Retry / Cancel) | 既存ランチャーの終端ダイアログ + ポップアップの本文保持 |
| MarkdownString 表示 | Markdown ソースのテキスト表示 |

## 11. API / インターフェース

### 11.1 コマンド(category `gitlab-eclipse-plugin`)

| id | 名前 | 寄与先 |
|---|---|---|
| `gitlab-eclipse-plugin.addMergeRequestLineComment` | Add Merge Request Comment on This Line… | 既存 GitLab エディタサブメニュー(`popup:#AbstractTextEditorContext`)、`popup:#AbstractTextEditorRulerContext` |
| `gitlab-eclipse-plugin.openMergeRequestLineThread` | Open Merge Request Thread | `popup:#AbstractTextEditorRulerContext` |

キーバインドは付けない。

### 11.2 GraphQL

```graphql
mutation CreateDiffNote($noteableId: NoteableID!, $body: String!, $position: DiffPositionInput!) {
  createDiffNote(input: { noteableId: $noteableId, body: $body, position: $position }) {
    errors
  }
}
```

選択は `errors` のみ(既存 `CREATE_NOTE_MUTATION` と同じ方針。作成結果は再取得で得る)。`noteableId` = `gid://gitlab/MergeRequest/<id>`(`DiscussionService.mrGid`)。

スレッド取得クエリへの追加: `position { … diffRefs { baseSha headSha startSha } }`。

### 11.3 F5 の再利用契約(`com.gitlab.eclipse.views.inlinethread`)

```kotlin
interface InlineThreadHost {            // ポップアップの利用者(MR / 将来の Quick Chat)が実装
  fun onSubmit(text: String)            // 入力欄の送信
  fun onAction(actionId: String)        // 解決・未解決化などのボタン
  fun onClosed()
}
class InlineThreadPopup(editor: ITextEditor, oneBasedLine: Int, host: InlineThreadHost) {
  fun open(model: InlineThreadModel)
  fun update(model: InlineThreadModel)  // 表示モデルの差し替え(Quick Chat のストリーミングもこれで足りる)
  fun setBusy(busy: Boolean)            // 送信中は入力と送信を無効化
  fun close()
}
```

注釈層は `ThreadAnnotationAttacher.replace(document, List<LineAnnotation>)`(`LineAnnotation` = 行・種別・ホバー文)として MR 非依存にする。Quick Chat 固有の要件(ストリーミング追記・ローダー表示・コードブロックのコピー / 挿入)は次サイクルで `InlineThreadModel` に追加する。

## 12. データモデル

### 12.1 セッション

```kotlin
data class MergeRequestRef(val instanceUrl: String, val authFingerprint: String,
  val projectId: Long, val mrIid: Long, val mrGid: String, val namespaceWithPath: String)

data class SessionIdentity(val instanceUrl: String, val authFingerprint: String,
  val projectId: Long, val mrIid: Long, val headSha: String, val newPath: String)

data class ReviewSessionSnapshot(
  val identity: SessionIdentity,
  val mrRef: MergeRequestRef,
  val baseSha: String, val startSha: String, val headSha: String,
  val oldPath: String, val newPath: String,
  val lineMap: DiffLineMap,            // Unavailable を含む
  val canCreateNote: Boolean,
  val placements: List<PlacedThread>,  // (oneBasedLine, discussion, resolved)
)
```

### 12.2 行対応

```kotlin
sealed interface NewLineKind { object Added : NewLineKind; data class Unchanged(val oldLine: Int) : NewLineKind }
sealed interface DiffLineMap {
  object Unavailable : DiffLineMap
  class Parsed(...) : DiffLineMap { fun classify(oneBasedNewLine: Int): NewLineKind }
}
```

hunk ヘッダは `@@ -a[,b] +c[,d] @@`(参照実装 `diff_line_count.ts:17` と同じ正規表現)。`\ No newline at end of file` 行は数えない。

### 12.3 `DiffPositionInput`

| 行の種類 | 送る値 |
|---|---|
| 追加行 | `baseSha`, `headSha`, `startSha`, `paths{oldPath, newPath}`, `newLine` |
| 変更なし行 | 上記 + `oldLine` |

`oldPath` / `newPath` は diff エントリの値(リネームでは異なる)。新規ファイルは全行が追加行。

## 13. トランザクション境界

GitLab への書き込みは 1 操作 = 1 mutation(`createDiffNote` / `createNote` / `discussionToggleResolve`)で、クライアント側に複数書き込みのまとまりは無い。セッションの確立と再取得は読み取りのみで、失敗しても書き込み状態に影響しない。

## 14. エラー処理

| 事象 | 扱い |
|---|---|
| G1〜G3 の不成立 | 情報通知。ポップアップを開かない |
| G5〜G9 の不成立 | 送信しない。`Rejected(message)` で終端(本文保持・再送なし、§9.3.1)。文言は Phase 5A §21.5 の表を出発点にし、実装で確定 |
| `DiffLineMap.Unavailable` | 「この MR のファイル diff は大きすぎるためコメントできない」旨で拒否(G-6 と同じ) |
| セッション確立の失敗 | 情報通知 1 回。エディタは開いたまま |
| 注釈モデルが使えない | 表示を諦める(ログ 1 行)。メニューからの作成は可能 |
| 送信の失敗 | 既存の 3 層分類(Success / Definite / Ambiguous)と終端処理 |

文言の詳細は実装段階で確定する(§23 の重点確認)。

## 15. タイムアウトとリトライ

既存の GraphQL / REST クライアントのタイムアウトをそのまま使う。**自動リトライはしない**(Ambiguous の再送はユーザーが最新状態を見てから、既存ランチャーの `[Send again]` のみ)。

## 16. 冪等性

`createDiffNote` は冪等ではない。二重送信は in-flight ガード(`forEditorLine` キー、UI ターンで取得、既存ランチャーが管理)で防ぎ、Ambiguous 時の再送はユーザー確認後のみ。送信中はポップアップの送信ボタンを無効化する。

## 17. 並行処理

- UI スレッド専有: `ReviewSessionRegistry`、注釈の差し替え、ポップアップ、世代の比較。
- background: 接続捕捉、REST / GraphQL、JGit(G7 / G8)。
- UI → background の受け渡しは不変のスナップショットのみ。background → UI は `asyncExec` で、到着時に「世代が最新 / 文書にエディタが残っている / bundle が active」を再確認する(latest-wins)。
- 注釈モデルの変更は UI スレッドで行う(サブモデルへの `addAnnotation` / `removeAnnotation` / `replaceAnnotations`)。
- デッドロック回避: UI スレッドで `syncExec` やネットワーク待ちをしない。workspace のスケジューリングルールは取らない。

## 18. 認証と認可

- セッションは取得時の `(instanceUrl, authFingerprint)` を保持し、書き込みは `pinnedConnectionFor` で一致を確認する(不一致 = GateRejected、送信しない)。
- 権限: `mergeRequest.userPermissions.createNote`(新規・返信)、ノートの `resolveNote`(解決)。メニューは常に表示し実行時に拒否(FR-9)。
- 入力値: `oneBasedLine` は 1 以上かつ文書の行数以下。パスは diff エントリ由来(サーバ値)で、ファイル読み取りは既存の `resolveContainedRealPath` と JGit の HEAD ツリー参照のみ。

## 19. ログ、監視、監査

- ログに出す: セッション確立の成否と拒否理由の種別、注釈数、書き込みの分類結果。
- ログに出さない: token、authFingerprint、本文、diff テキスト、ファイル内容。
- Error Log に出すのは予期しない例外のみ。

## 20. 障害時の復旧方法

- 表示が古い / 消えた: サイドバーからファイルを開き直す(セッション再確立)。
- 送信結果が不明: 既存の Ambiguous 処理(再取得してから `[Send again]`)。
- 注釈が残る不具合: エディタを閉じれば解放される(FR-11)。

## 21. 既存機能への影響

| 既存機能 | 影響 |
|---|---|
| `OpenMrFileHandler` | 開いた後にセッション確立を追加。既存のゲート・文言・開き方は不変 |
| サイドバーの Discussions | クエリに `diffRefs` が増えるだけ(表示不変) |
| 返信・解決・MR コメント(Phase 5A) | 経路・終端処理は不変。エディタ側から同じ経路を呼ぶ入口が増える |
| Duo の注釈・ルーラー列 | 別の注釈タイプ・別のサブモデル。干渉しない |
| エディタ / ルーラーのコンテキストメニュー | 項目が 2 つ増える |

## 22. 移行方法 / ロールバック方法

データ移行なし。追加のみなので、ロールバックは実装 PR の revert で完結する。設定の追加なし。

## 23. テスト方針

- **TDD(headless)**: `DiffLineMap`(hunk 無し / 複数 hunk / hunk 外の行 / 先頭行 / 末尾行 / 新規ファイル / 空 diff / `\ No newline`)、`ThreadPlacement`(diffRefs 不一致・positionType・oldLine のみ・パス不一致・解決済み)、`DiffPositionBuilder`、`createDiffNoteVariables`、`LineCommentFlow` のゲート列(効果を注入し、各ゲートで送信 0 回を確認。再試行でゲートが再評価されること)、G8 の JGit 実リポジトリテスト(一時リポジトリで `core.autocrlf=true` の CRLF、Shift_JIS、BOM、`filter` / `working-tree-encoding` 属性)、`Rejected` の終端分岐、セッション置き換え(identity の各要素の不一致)、`ReviewSessionLoader`(接続 1 回捕捉・head 不一致拒否)、`InlineThreadModel` の組み立て(権限による操作の出し分け)。
- **実装レビューの重点確認項目**(設計では詰めず、実装段階で検出する): 空白のみ・Unicode 空白のみの本文の送信不可(A16)、通知文言、ホバー文の整形と HTML エスケープ、`oneBasedLine` の基数(先頭行のテストを含む)、世代比較の位置、ログに本文が出ないこと。
- **手動検証**(§25): SWT 表示・ルーラー・ポップアップ・JDT エディタ・ジェネリックエディタ。

## 24. 受け入れ条件

| # | 条件 |
|---|---|
| A1 | サイドバーの変更ファイルから開いたエディタに、最新 version の diff スレッドが該当行の注釈として出る。解決済みは別アイコン |
| A2 | 古くなったスレッド(`diffRefs` 不一致)と `oldLine` のみのスレッドは出ない |
| A3 | ホバーに作成者・本文の先頭・返信数・状態が出る(HTML 特殊文字がそのまま表示される) |
| A4 | ルーラー右クリックでポップアップが行の近くに開き、エディタへフォーカスを戻しても閉じない。Esc / 閉じるで閉じる |
| A5 | ポップアップから返信・解決・未解決化ができ、成功後に注釈とサイドバーが更新される |
| A6 | 追加行にコメントすると `newLine` のみ、変更なし行では `oldLine`+`newLine` で作成され、GitLab 上で正しい行に表示される(先頭行を含む) |
| A7 | 未保存のエディタ・HEAD 不一致・本文不一致・巨大 diff ではコメントを送らない |
| A8 | 手で開いたエディタでも右クリックからコメントでき、成功後にセッションが確立して注釈が出る |
| A9 | 権限の無い MR では作成・返信を送らない |
| A10 | 送信失敗時に本文が失われない |
| A11 | エディタを閉じると注釈・リスナー・ポップアップが解放される。bundle 停止でも同様 |
| A12 | 既存テストの失敗集合が変わらない(`FAILSET_IDENTICAL`)、detekt の新規指摘 0 |
| A13 | 同じファイルを別 MR / 別アカウント / 新しい version から開くと、旧セッションの注釈とポップアップが消え、新しいセッションの内容だけが出る(§9.5) |
| A14 | `[Retry]` / `[Send again]` の再試行でも G5〜G9 が再評価され、途中で HEAD や version が変わっていれば送信しない(§9.3.1) |
| A15 | CRLF(`core.autocrlf=true`)・非 UTF-8(例: Shift_JIS)・UTF-8 BOM の未編集ファイルにコメントできる。`filter` / `working-tree-encoding` 属性付きのファイルは拒否する(§9.3.2) |
| A16 | ポップアップの送信ボタンは、本文が `isSubmittable`(`CommentInputDialog.kt:122`、空白のみ不可)を満たし、かつ送信中でないときだけ有効 |

## 25. 手動検証手順(実装 PR の本文に転記)

M1 サイドバーから変更ファイルを開く → 注釈 / 解決済みアイコン / 概要ルーラー。M2 ホバー。M3 ポップアップの位置(先頭行・末尾行・折りたたみ内・画面外)と非モーダル性(エディタ編集・別エディタへの切替)。M4 返信・解決・未解決化。M5 追加行・変更なし行・hunk 外の変更なし行・先頭行にコメント → GitLab の MR 画面で行を確認。M6 リネームファイル。M7 未保存 / HEAD 不一致 / 巨大 diff。M8 手で開いたエディタから作成。M9 JDT の Java エディタ・ジェネリックエディタ・テキストエディタでそれぞれ M1〜M5。M10 分割エディタ(同じ文書を 2 枚)で片方を閉じても注釈が残り、両方閉じると消える。M11 Windows と Linux(GTK)でのポップアップの前面性・フォーカス。M12 注釈アイコンの左クリックで開くか(U1)。

## 26. 未決事項

| # | 事項 | 扱い |
|---|---|---|
| U1 | 注釈アイコンの左クリックでポップアップを開く方式(ルーラー制御のマウスリスナー)が、JDT のブレークポイント操作やマーカー選択と干渉しないか | 実機で確認(M12)。干渉するなら左クリックは外し、右クリックのみにする(機能は右クリックで満たせる) |
| U2 | JDT の Java エディタのホバーが独自注釈の `getText()` を表示するか | 実機で確認(M9)。出なくてもポップアップで内容を確認できる |
| U3 | hunk 外の変更なし行へのコメントを GitLab が受理するか(REST ドキュメント上は受理、参照実装も送る) | 実機で確認(M5) |
| U4 | `IStorageEditorInput` 等、ファイル以外の入力の注釈モデル | 対象外(G2 で弾く) |
| U5 | `TOOL` shell の前面性(Windows / GTK の差) | 実機で確認(M11) |

## 27. 想定されるリスク

| リスク | 対策 |
|---|---|
| 行のずれ(基数・hunk 解析の誤り) | `oneBasedLine` 命名、先頭行テスト、G8 の本文一致 |
| ポップアップの SWT 挙動差 | `PopupDialog` を避ける(E7)、手動検証 M3 / M11 |
| 注釈の残留・リーク | 参照カウント + `partClosed` + bundle 停止、M10 |
| サーバの受理条件差(古い GitLab) | Phase 5A と同じ最小バージョン前提(`createDiffNote` は 13.9 以降)。失敗は既存分類で通知 |

## 28. 実装タスク分割案(モデルは CLAUDE.md の選定基準に従う)

| # | 内容 | 実装 | レビュー |
|---|---|---|---|
| T1 | `DiffLineMap`(TDD) | sonnet | fable |
| T2 | API 追加: `Diff` の 3 フィールド、`diffRefs`、`createDiffNote`、`forEditorLine`、`Rejected` 分岐、接続引数(TDD) | sonnet | fable |
| T3 | `ThreadPlacement` / `DiffPositionBuilder` / `InlineThreadModel`(TDD) | sonnet | fable |
| T4 | `ReviewSessionLoader` / `LineCommentFlow`(効果注入、TDD) | opus | fable |
| T5 | `ReviewSessionRegistry` / `ThreadAnnotationAttacher` / 注釈タイプ | fable | fable |
| T6 | `InlineThreadPopup` と返信・解決・作成の配線 | fable | fable |
| T7 | ハンドラ・`plugin.xml`・`ChangedFileNode.mrRef`・`OpenMrFileHandler` の配線 | opus | fable |
| — | ブランチ全体レビュー | — | fable |

## 29. Codex レビュー反映履歴

### round 1(`59846d1` に対する指摘 4 件)

| # | 指摘 | 仕分け | 反映 |
|---|---|---|---|
| 1 | P1 文書セッションの再利用で MR の同一性を検証していない | 採用(別 MR への書き込み = データ整合性) | `SessionIdentity` と置き換え手順 §9.5、A13 |
| 2 | P1 事前検証と書き込みが既存ランチャーの契約と合わない | 採用(構造) | §9.3.1: ローカルキーを UI ターンで確定し、G5〜G9 を `write` 内で毎試行実行。`Rejected` 分岐を追加。Phase 5A §21.5 の background ガード取得は廃止。A14 |
| 3 | P1 G8 のバイト列比較が文字コード・改行変換で壊れる | 採用(Git の実挙動) | §9.3.2: G8a 属性 / G8b 文字コード復号の一致 / G8c JGit status。A15 |
| 4 | P2 ポップアップ送信の本文検証 | 実装段階へ | A16 と §23 の重点確認に記録のみ |
