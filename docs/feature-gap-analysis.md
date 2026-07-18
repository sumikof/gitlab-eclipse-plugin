# 機能差異分析: gitlab-eclipse-plugin vs gitlab-vscode-extension

作成日: 2026-07-17
対象: Eclipseプラグイン `0.1.0.beta` / VSCode拡張 `gitlab-workflow` v6.85.3 (GitLab Language Server ^9.3.0 同梱)

## 1. 前提: 両者の現状

**VSCode拡張** は約72コマンド・25設定・2つのアクティビティバーコンテナを持つ成熟した拡張。アーキテクチャ上の重要なトレンドとして、UI(Duo Chat v2、Agentic Chat、Knowledge Graph、MCPダッシュボード等)が **GitLab Language Server (LSP) がサーブするWebviewへ移行中**(`languageServerWebviews` フラグ、デフォルト有効)。

**Eclipseプラグイン** はPoC段階のスキャフォールド(コミット4つ)。実際に動作するのは以下のみ:

- 設定ページ(URL / PAT / ログレベル等)と Equinox Secure Storage によるPAT保存
- LSP4E経由のLSPプロセス起動と設定プッシュ — ただし**バイナリパスが開発者のMacの絶対パスにハードコード**されており、他環境では起動不能
- Duo Chatビュー(SWT Browser)— LSPの `$/gitlab/webview-metadata` から取得したURLへリダイレクトするだけの骨組み

さらに宣言済みだが**壊れている**ものが多数ある(§3)。

## 2. 機能領域別の差異対照表

凡例: ✅ 実装済 / ⚠️ 部分実装・骨組みのみ / ❌ 未実装 / 💥 宣言はあるが壊れている

| 機能領域 | VSCode拡張 | Eclipseプラグイン | VSCode側の実装方式 |
|---|---|---|---|
| **Duo Chat** | ✅ レガシー(Vue2)+ v2(LSP Webview)、スラッシュコマンド8種、コンテキストプロバイダ、Quick Chat | ⚠️ SWT BrowserでLSP WebviewのURLを開くのみ。webviewメッセージハンドラは全てno-opスタブ、注入JSは空ファイル | LSP (v2) |
| **Explain/Fix/Tests/Refactor コマンド** | ✅ エディタ右クリック+キーバインド(Alt+E/T/R) | 💥 メニュー宣言のみ。コマンド定義・ハンドラが存在せず全て非動作 | Native→LSP |
| **Code Suggestions(インライン補完)** | ✅ LSP経由(ストリーミング生成含む)+レガシー直接API fallback、言語別トグル、ステータスバー | ❌ `textDocument/inlineCompletion` はコメントアウト。クライアント側の消費コードなし | LSP(主経路) |
| **Agentic Chat / Duo Agent Platform** | ✅ 専用コンテナ、Agentic Chat、Duo Workflow、サンドボックス、Flow Builder | ❌ 皆無 | LSP Webview |
| **MCP / Knowledge Graph** | ✅ MCP設定・ダッシュボード、Knowledge Graphビュー | ❌ 皆無 | LSP Webview + Native設定 |
| **Issue/MR サイドバー** | ✅ ツリービュー、カスタムクエリ、検索、Issue/MR詳細Webview(Vue3) | ❌ 皆無 | Native(LSPデータソース選択可) |
| **MRレビュー** | ✅ 差分コメント・スレッド解決・viewed追跡・仮想`review:`FS | ❌ 皆無 | Native |
| **パイプライン/CI** | ✅ ステータスバー、ジョブ実行/リトライ/キャンセル、ログビューア(ANSI+折り畳み)、アーティファクトDL | ❌ 皆無 | Native |
| **CI/CD オーサリング** | ✅ CI Lint、マージ済み設定表示、CI変数補完 | ❌ 皆無 | Native |
| **Git連携** | ✅ MRブランチcheckout、Web表示/リンクコピー、スニペット(+パッチ)、Wikiクローン、Publish | ❌ 皆無 | Native |
| **セキュリティスキャン** | ✅ リモートSAST、保存時スキャン、Finding詳細Webview | ❌ FeatureFlagで `remoteSecurityScans: false` を送信するのみ | Native + LSP診断 |
| **認証** | ✅ OAuth + PAT、マルチアカウント、トークンリフレッシュ、ワークスペース別選択 | ⚠️ PATのみ。ホストが `gitlab.com` にハードコードで設定URLに追従しない | Native |
| **証明書/プロキシ** | ✅ ca/cert/certKey設定、プロキシ(VS Code設定+環境変数をLSPへ伝搬) | ⚠️ ignoreCertificateErrorsのみ。`HttpAgentOptions` は定義済み未使用。プロキシなし | Native→LSP |
| **LSPバイナリ管理** | ✅ npm依存としてバンドル | 💥 開発者Macの絶対パスにハードコード。DL/バンドル機構なし | — |
| **ステータスバー/通知** | ✅ パイプライン・MR・Duoトグル・アカウント・LSP起動状態 | 💥 トリムウィジェット宣言はあるがコマンド未定義+`StatusWidget`は空スタブ。ラベルは"Enabled"固定文字列 | Native |
| **オンボーディング** | ✅ ウォークスルー6ステップ、Duoチュートリアル、キーバインドヒント | ❌ 皆無 | Native |
| **テレメトリ** | ✅ Snowplow + OpenTelemetry、診断エクスポート | ⚠️ LSPにエンドポイントを渡すのみ。UIトグルなし | Native + LSP |
| **フィーチャーフラグ/状態反映** | ✅ ローカル16種+インスタンスフラグ(GraphQL)、状態ポリシー | ⚠️ DTOは存在するが `$/gitlab/featureStateChange` ハンドラがno-opで UI に反映されない | Native |
| **キーバインド** | ✅ 14種 | ❌ コメントアウトでTODO | — |
| **リモートFS/ブラウザ対応** | ✅ vscode.dev対応、読み取り専用リモートリポジトリ閲覧 | ❌ (Eclipseでは概念が異なるため対象外候補) | Native |

## 3. Eclipse側の既知の破損(機能追加以前の修復対象)

1. **LSPバイナリパスのハードコード** — `GitLabLanguageServerProvider.java:63-68`。DL/バンドル機構が必要。これが直らない限り誰の環境でも動かない。
2. **9個のメニューコマンドが未定義** — `plugin.xml` の menuContribution が参照するコマンドIDに `org.eclipse.ui.commands` 定義も handlers 拡張も無い。
3. **CodeMiningプロバイダのクラス欠落** — `plugin.xml:185-189` が存在しない `MyCodeMiningProvider` を参照しロード失敗。
4. **ホスト名ハードコード** — Secure Storageのキーが全箇所 `"gitlab.com"` 固定で、設定した接続URLに追従しない。
5. **`GitLabLanguageServerConfigurationParams.Builder` のコピペバグ** — `telemetry(...)` オーバーロードが `featureFlags` に代入(`.java:45-48`)。
6. `LanguageServerBrowserView.js` が0バイト、`$/gitlab/webview/*` ハンドラが全てno-op — LSP Webviewとの双方向メッセージングが不在。

## 4. 戦略的示唆: LSP再利用 vs ネイティブ再実装

VSCode拡張の機能は再利用可能性で二分される。**EclipseプラグインはすでにLSP4Eで同じGitLab Language Serverに接続する設計**のため、LSP側に寄った機能から着手するのが費用対効果が高い。

**LSP経由でほぼそのまま得られる(優先度高・工数小):**
- インライン補完(ストリーミング含む)— LSP4Jの `textDocument/inlineCompletion` を有効化しエディタに接続
- Duo Chat v2 / Agentic Chat / Knowledge Graph / MCPダッシュボード — LSPがWebviewコンテンツをサーブ。必要なのはブラウザビューと `$/gitlab/webview/*` メッセージングの実装(現在no-op)
- AIコンテキストプロバイダ、チャットスラッシュコマンド(v2経路)
- 診断ベースのセキュリティ/品質Finding

**Eclipseネイティブで再実装が必要(工数大):**
- Issue/MRサイドバー、MRレビューフロー(差分コメント等)
- パイプライン/ジョブ操作、ログビューア
- CI Lint・CI変数補完
- OAuth認証、マルチアカウント
- ステータスバー、ウォークスルー相当のオンボーディング

**推奨着手順序(案):**
1. 基盤修復: LSPバイナリのDL/バンドル、壊れたコマンド定義の整理、ホスト名ハードコード解消
2. Code Suggestions(インライン補完)— LSPが主経路のため最小工数で最大価値
3. Duo Chat のwebviewメッセージング完成(双方向通信)→ Chat v2 / Agentic Chat
4. Explain/Fix/Tests/Refactor コマンドのハンドラ実装(Chatに接続)
5. 認証強化(OAuth、インスタンスURL追従)、証明書/プロキシ
6. ネイティブ系(Issue/MR/CI)は価値と工数を見て段階導入
