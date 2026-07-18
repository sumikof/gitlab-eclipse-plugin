# ステップ2設計: Code Suggestions(インライン補完)

作成日: 2026-07-18
ブランチ: `feat/step2-code-suggestions`
ステータス: 設計承認済み

## 1. ゴール

GitLab Eclipse プラグインに Code Suggestions(ghost text によるインライン補完)を実装する。GitLab Language Server(9.5.0、ステップ1で自動DL済み)の `textDocument/inlineCompletion` を主経路とし、生成系のストリーミング応答にも対応する。

## 2. 調査で確定した前提(2026-07-18)

- **LSP4E は `textDocument/inlineCompletion` 完全未対応**(0.18.x/main とも実装なし、feature request も無し)。LSP4E を経由せず、自前でリクエスト送信+描画する。
- **LSP4J 0.23.1(現依存)に InlineCompletion 型は無い**(1.0.0 初出だが Eclipse 2025-06 の LSP4E と共存不可)。LSP 3.18 サブセットの独自 record DTO を定義し、既存の `$/gitlab/webview-metadata` と同じ `@JsonRequest` 方式で実装する。
- **ghost text 描画は CodeMining 方式のみ**(Platform 4.34+ / ターゲットは Eclipse 2025-06 = 4.36)。参照実装は Microsoft Copilot for Eclipse(MIT・ソース公開)。既知の癖: CodeMining はタブ文字を描画しない(スペース置換が必要)、複数行は 1 行目 LineContentCodeMining + 2 行目以降 LineHeaderCodeMining の分割方式。
- **VSCode 拡張の挙動**(gitlab-vscode-extension v6.85.3 で確認): デバウンスではなくキャンセル駆動、ストリーミングは生成系のみでカスタム通知(`streamingCompletionResponse` / `cancelStreaming`)、**done まで貯めて一括表示**(逐次描画しない)、テレメトリは `$/gitlab/telemetry` に SHOWN/ACCEPTED、TAB 受け入れは候補表示中のみ有効な独自コンテキストで競合回避。
- カスタム通知の正確なメソッド名・コマンド ID(START_STREAMING 等)は npm パッケージ側にあり参照コピーに実体が無いため、**実装前に gitlab-lsp 9.5.0 ソースで検証する**(計画にタスク化)。

## 3. スコープ

含む:
- `textDocument/inlineCompletion` リクエスト経路(独自 DTO)
- ストリーミング生成(done までバッファ→一括表示)
- 操作系: TAB=全文受け入れ / ESC=破棄 / Ctrl+→=単語単位受け入れ / Ctrl+Alt+/=手動トリガー
- テレメトリ: SHOWN / ACCEPTED の 2 イベント
- `$/gitlab/didChangeDocumentInActiveEditor` 通知(エディタ切替時)

含まない(明示的スコープ外):
- 言語別有効/無効リスト、on/off トグル設定
- タイプ先行文字の候補プレフィックス消費(typed-prefix)
- ステータスバー表示
- PaintListener フォールバック(旧 Eclipse 対応)
- suggestionsCache / openTabsContext 設定

## 4. アーキテクチャ(案B: セッションマネージャ主導)

状態(モデル)と描画を分離する。エディタごとのリスナーがリクエストサイクルを駆動し、CodeMining プロバイダはモデルを描画するだけの薄い層とする。

```
com.gitlab.eclipse.lsp                     … 既存。DTO と LSP インターフェース拡張
  GitLabLanguageServer                     … @JsonRequest("textDocument/inlineCompletion") を正式追加
                                             @JsonNotification("$/gitlab/didChangeDocumentInActiveEditor") /
                                             @JsonNotification("$/gitlab/telemetry") / cancelStreaming 通知も追加
  GitLabLanguageClient                     … @JsonNotification("streamingCompletionResponse") 受信を追加
  新 DTO(record): InlineCompletionParams / InlineCompletionItem / InlineCompletionList /
                   StreamingCompletionResponse / TelemetryParams など(LSP 3.18 サブセット)

com.gitlab.eclipse.suggestions             … 新設。純 JDK POJO(単体テスト対象)
  SuggestionModel   … セッション状態: 候補テキスト・挿入位置・trackingId・受け入れ済みプレフィックス。
                      状態遷移(set/clear/advanceWord)と単語境界分割
  StreamBuffer      … streamId 別チャンク蓄積。done 検知で完成テキスト確定。ID 不一致は無視
  RenderPlan        … 候補テキスト+位置 →「1 行目インライン+2 行目以降ブロック」分割と
                      タブ→スペース置換を計算する純関数

com.gitlab.eclipse.suggestions.ui          … 新設。Eclipse 配線(SWT/JFace 依存)
  CompletionSessionManager … 中核。ドキュメント/キャレットリスナー、デバウンス(250ms)、
                             旧リクエスト cancel(lsp4j が $/cancelRequest 自動送信)、
                             モデル反映、updateCodeMinings() による再描画要求
  EditorTracker            … IPartListener2 でアクティブエディタ追跡・マネージャ着脱。
                             切替時に didChangeDocumentInActiveEditor 送信
  GhostTextCodeMiningProvider … RenderPlan を LineContentCodeMining(isAfterPosition=true)+
                             LineHeaderCodeMining 列に変換するだけの描画層
  ハンドラ 4 種: Accept(TAB) / Dismiss(ESC) / AcceptNextWord(Ctrl+→) / Trigger(Ctrl+Alt+/)
```

plugin.xml / MANIFEST:
- CodeMining プロバイダを `org.eclipse.ui.workbench.texteditor.codeMiningProviders` に登録
- 独自コンテキスト `suggestionVisible`(候補表示中のみ活性)を定義し、TAB/ESC/Ctrl+→ のキーバインドはこのコンテキスト限定(インデント操作との競合回避)
- `Require-Bundle` に `org.eclipse.jface.text` を再追加(CodeMining API の所在)

LSP サーバ参照は既存の `GitLabLanguageServerProvider.languageServer` static 捕捉パターンを再利用する(ステップ2では変更しない)。

## 5. データフロー

補完(通常):
1. タイピング → ドキュメント変更イベント → 現候補クリア+デバウンスタイマー再スタート(250ms)。実行中リクエストは `CompletableFuture.cancel()`
2. タイマー発火 → キャレット位置から params 構築(triggerKind=Automatic)→ `textDocument/inlineCompletion`
3. 応答 item の `command` で分岐:
   - 通常補完: `SuggestionModel.set()` → SHOWN 送信 → `updateCodeMinings()`
   - ストリーミング開始: streamId を `StreamBuffer` に登録し待機
4. ストリーミング: `streamingCompletionResponse` 通知 `{id, completion, done}` を蓄積。done=true で一括反映+SHOWN+表示

操作:
- TAB: `IDocument.replace()` で残り全文挿入 → クリア → ACCEPTED 送信
- Ctrl+→: 次の単語境界まで挿入しプレフィックス前進。全文消費時点で ACCEPTED
- ESC: クリア+再描画。ストリーミング中は `cancelStreaming{id}` も送信
- Ctrl+Alt+/: デバウンスを飛ばし即リクエスト(triggerKind=Invoked)
- キャレット移動・エディタ切替・フォーカス喪失 → クリア(ストリーミング中は cancel 通知)

## 6. エラー処理

- LSP 未起動/リクエスト失敗 → 候補なしとして静かにクリア。エラーログはレート制限(同種エラーは初回のみ Status ログ)
- `CancellationException` は正常系として無視
- 古い応答の破棄: リクエスト時のドキュメント修正スタンプを保持し、応答到着時に不一致なら捨てる

## 7. テスト戦略

POJO 層をユニットテスト(ステップ1と同じ方式、テストフラグメントに追加):
- `SuggestionModel`: 状態遷移、単語境界分割(記号・空白・改行混在)
- `StreamBuffer`: チャンク蓄積、ID 不一致無視、completion なし done 通知、キャンセル後のチャンク破棄
- `RenderPlan`: 単一行/複数行分割、タブ→スペース置換、行末/行中位置
- DTO: JSON シリアライズ形状(gson で LSP 3.18 仕様と一致するか)

SWT/エディタ配線(マネージャ・プロバイダ・キーバインド)はユニットテスト対象外。**実機 Eclipse での手動検証手順書**を成果物に含め、ユーザー実機で最終確認する(devcontainer は headless のため)。

## 8. 成功基準

- `mvn verify` 全テストグリーン(既存 21 + 新規)
- 実機 Eclipse で: タイピング停止後に ghost text 表示 / TAB 受け入れ / ESC 破棄 / 単語受け入れ / 手動トリガー / 複数行生成(ストリーミング)が動作
- 表示・受け入れが GitLab 側テレメトリに計上される
