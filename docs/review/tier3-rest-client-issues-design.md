# 設計: Tier3 ネイティブ GitLab REST クライアント基盤 + Issue一覧（担当分）スライス

最終更新: 2026-07-19
対象リポジトリ: `sumikof/gitlab-eclipse-plugin`（`develop` 起点）
フェーズ: フェーズ1（VSCodeパリティ）/ Tier3（native群のクリティカルパス基盤）
関連: `parity/vscode-parity-backlog.md` §3-§4、`HANDOVER.md` §5

---

## 0. 決定事項サマリ（brainstorming 確定）

| 論点 | 決定 | 根拠 |
|---|---|---|
| 実装方式 (a)ネイティブKotlin vs (b)LS `$/gitlab/*`拡張 | **(a) ネイティブKotlin REST クライアント** | 下記 §1 |
| プロトコル対応範囲（初回） | **REST のみ**（GraphQL は後続で同一抽象へ追加） | 基盤最小化。ユーザー確定 |
| HTTP スタック | **Java 21 `java.net.http.HttpClient`（新規依存なし）** | scribejava は OAuth 専用。GraphQL も POST-JSON で同一スタック |
| 最初の一垂線（初回 PR スコープ） | **Issue一覧（担当分）= `gl.showIssuesAssignedToMe` 相当** | 最安の read-only、Git非依存、MUST 項目 |
| UI サーフェス | 最小 read-only `ViewPart`（TableViewer） | Tier3 サイドバーツリー基盤の新設を回避しつつ end-to-end 実証 |

## 1. なぜ (a) ネイティブクライアントか（(b) が却下された根拠）

調査で以下を確認した:

- 本リポジトリの LSP は**上流 vendored バイナリ `@gitlab-org/gitlab-lsp`**（`build.gradle.kts` が gitlab.com のジェネリックパッケージから取得。`gitlab-language-server-0.8.2.jar`）。**我々の所有コードではない**。
- LSP が公開する `$/gitlab/*` メソッドは `runCommand` / `webview` / `document` / `theme/didChangeTheme` / `telemetry` / `plugin/notification` / `plugin/request` / `copyText` / `openFile` / `openUrl` のみ。**汎用 GitLab API パススルーは存在しない**（VSCode 側 `src` 実測）。
- パリティ基準の VSCode 拡張自体が、Issue/MR/Pipeline/Comment/Snippet を**拡張内のネイティブ `gitlab_service.ts`**（REST `desktop/gitlab/api/*.ts` + GraphQL `desktop/gitlab/graphql/*.ts`、共通抽象 `common/gitlab/api/api_client.ts` の `fetchFromApi`）で実装している。**LSP は使っていない**。（LSP を使う `MediatorCommandsApiClient` は web-IDE 変種のみ。desktop は native 経路。）

→ (b) は上流 `gitlab-lsp` への RPC 追加＝フォーク/上流貢献が必要で、我々が制御できないリリースサイクルに依存する。VSCode のアーキテクチャとも不一致。よって **(a) が忠実なパリティ経路**。

**(a) の既知トレードオフ（本設計で対処）**: ネイティブクライアントは **3本目のネットワーク egress 経路**（既存: LSP プロセス / Verify Setup 検証）になる。Tier0 は `cert`/`certKey`/proxy をこの2経路にのみ配線した。新クライアントが同一の mTLS/CA/proxy 設定を尊重しなければ、Tier0 が対象とした enterprise ユーザーで**サイレントに壊れる**。→ egress 設定の集中化を基盤要件とする（§3）。

## 2. アーキテクチャ概要

新規パッケージ `com.gitlab.eclipse.api`（VSCode の `gitlab/` に対応）。各ユニットは単一責務・独立テスト可能:

```
IssueService ──uses──▶ GitLabApiClient.fetchFromApi(ApiRequest<T>) ──uses──▶ GitLabHttpClient
                              │                                                     │
                        (URL組立・認証ヘッダ・JSONパース)              (Java HttpClient, egress設定適用)
                                                                                    ▲
                                                              GitLabHttpClientFactory (§3, cert/CA/proxy集中)
```

- 上位（`IssueService`）はドメイン API を提供、下位（`GitLabHttpClient`）は egress のみを知る。
- `GitLabApiClient.fetchFromApi` が VSCode `ApiClient.fetchFromApi` に対応する抽象境界。**GraphQL は将来 `fetchFromGraphql` を同クライアントに追加**するだけで、上位・egress 層は不変。

### 2.1 コンポーネント詳細

| コンポーネント | 責務 | 主要 API | 依存 |
|---|---|---|---|
| `GitLabHttpClient` | 設定適用済み `HttpClient` を保持し HTTP 実行 | `send(HttpRequest): HttpResponse<String>` | `GitLabHttpClientFactory` |
| `GitLabHttpClientFactory` | egress 設定（cert/CA/proxy/ignore）を読み `HttpClient`+`SSLContext` を構築 | `create(): HttpClient` | Preferences, `LanguageServerProxyManager`, `SecretStorage` |
| `GitLabApiClient` | REST エンドポイント抽象。URL 組立・`Authorization` 付与・JSON パース | `fetchFromApi(req: ApiRequest<T>): T` | `GitLabHttpClient`, `GitLabTokenProviderManager`, Preferences(`gitlab.url`) |
| `ApiRequest<T>` | 1リクエストの記述（method/path/query/型） | data class | — |
| `GitLabIssue` | Issue DTO（部分集合） | data class | — |
| `IssueService` | 担当 Issue 取得 | `getIssuesAssignedToMe(): List<GitLabIssue>` | `GitLabApiClient` |

### 2.2 DTO（初回スコープの最小集合）

`GitLabIssue`（GitLab REST `/issues` レスポンスの部分）:
- `iid: Long`（プロジェクト内 Issue 番号、表示用）
- `title: String`
- `webUrl: String`（`web_url`、ブラウザ起動用）
- `state: String`（`opened`/`closed`）
- `references: Reference?`（`references.full` 例: `group/project#123`、表示補助。無ければ null 許容）

JSON パース: **Gson**（既存依存。`GitLabAuthorizationToken.kt` / `OAuthSecretStorage.kt` 等で `Gson`/`GsonBuilder`/`@SerializedName` を使用中）。`@SerializedName("web_url")` 等で snake_case をマッピング。新規 JSON ライブラリは追加しない。

## 3. Egress 設定の集中化（基盤要件）

`GitLabHttpClientFactory.create()` が以下 5 設定を読み、`HttpClient` を構築する:

| 設定キー（`PreferenceConstants`） | 適用先 |
|---|---|
| `gitlab.certificate.clientCertificate`（`CLIENT_CERTIFICATE`） | mTLS: `KeyManager` へロード → `SSLContext` |
| `gitlab.certificate.clientCertificateKey`（`CLIENT_CERTIFICATE_KEY`） | 同上（秘密鍵） |
| `gitlab.certificate.caCertificate`（`CA_CERTIFICATE`） | カスタム `TrustManager`（社内CA信頼） |
| `gitlab.certificate.ignoreCertificate`（`IGNORE_CERTIFICATE_ERRORS`） | true 時は全信頼 `TrustManager`（検証無効） |
| proxy | `LanguageServerProxyManager`（既存、Eclipse `ProxyManager` ベース）の HTTPS URL / bypass hosts を `ProxySelector` として適用 |

- 認証トークン: `GitLabTokenProviderManager.getToken()`（`AUTHENTICATION_TYPE` により PAT/OAuth を選択）。ヘッダは `Authorization: Bearer <token>`（GitLab は PAT/OAuth 双方で Bearer を受理）。
- ベース URL: `gitlab.url`（`GITLAB_INSTANCE_URL`）。末尾スラッシュ正規化 + `/api/v4` を付与。
- **設計意図**: cert/CA/proxy の SSLContext 構築ロジックはこの Factory に一元化し、将来 MR/Pipeline クライアントも同 Factory を共有する（egress 経路を1つに保つ）。

## 4. UI（最小 read-only ビュー）

- `ViewPart` "GitLab Issues"（id 例: `com.gitlab.eclipse.views.issues`）。`TableViewer` で `#<iid>  <title>`（references があれば `<full>` 併記）を1列表示。
- ダブルクリック → `webUrl` を**外部ブラウザ**で開く（既存 openUrl パターン再利用。`handlers/ShowDocumentation.kt` 参照）。
- ツールバー Refresh アクションで再取得。
- コマンド `gl.showIssuesAssignedToMe` + `AbstractHandler` サブクラス + `plugin.xml` に `<view>` / `<command>` / `<handler>` / メニュー(またはコマンド)エントリを追加。
- **非対象**: フィルタ/検索/作成/ステータスバー項目、Tier3 サイドバーツリー基盤（`tree_view/*` 相当）は本 PR で作らない。

## 5. エラー処理・スレッド

- 取得は**バックグラウンド**（Eclipse `Job` またはコルーチン IO）で実行。UI 反映は `Display.syncExec`/`asyncExec` で**必ず UI スレッド**へ（HANDOVER §9 の既存 syncExec 欠落を踏襲せず、本コードでは正しく行う）。
- トークン未設定/空 → ユーザー向けメッセージ（設定ページ誘導）。ビューは空表示。
- HTTP 非 2xx / 接続失敗 / 証明書エラー → ダイアログ or ステータス表示 + `utils/Logger` へ記録。例外はドメイン例外 `GitLabApiException(status, body)` に包む。
- タイムアウト: `HttpClient`/`HttpRequest` に妥当なタイムアウト（例 30s）を設定。

## 6. テスト戦略（TDD, Kotest `DescribeSpec` + mockk + Koin）

- `GitLabApiClientTest`: `GitLabHttpClient` を mock（fake `HttpResponse<String>`）、`GitLabTokenProviderManager` を mock。→ **URL 組立**（`gitlab.url` + `/api/v4/issues?scope=assigned_to_me&state=opened`）、**`Authorization: Bearer` ヘッダ**、**JSON→DTO パース**、**非2xxで例外**を検証。
- `GitLabHttpClientFactoryTest`: 各設定キーのモックに対し、cert 設定時に `KeyManager` が構成される / `ignoreCertificate` で全信頼 TrustManager になる / proxy URL が反映される、を検証（`SSLContext`/`ProxySelector` の構成を観測可能に設計）。
- `IssueServiceTest`: `GitLabApiClient` を mock、`getIssuesAssignedToMe` が正しい `ApiRequest` を発行し DTO リストを返す。
- 共通: `workspaceFolders`（top-level val）を mock。ロガー遅延初期化があれば `LoggingKotestExtension` 登録。inline `service<T>()` は `startKoin { modules(module { single { mock } }) }`。
- **UI（`ViewPart`）**: SWT 接触は GTK3 必須。可能な範囲で TableViewer の入力反映を検証。ライブ IDE 挙動（ダブルクリック→ブラウザ、実 API 疎通）は**手動QAチェックリスト**を成果物にする（headless 不可）。
- Koin 配線: 新規 `ApiModule`（`GitLabHttpClient`/`GitLabApiClient`/`IssueService` を single 登録）。

## 7. 初回 PR の範囲外（後続タスク）

- GraphQL 対応（`fetchFromGraphql` 追加）。D13 MR コメント（discussions/notes）、セキュリティ検出は GraphQL 依存のため、その着手時に追加。
- 他 Issue 操作（作成 `gl.openCreateNewIssue` / 検索 `gl.issueSearch` / ステータスバー `gl.status.issue`）。
- MR / Pipeline / Comment / Snippet / Repo（同基盤の上に順次）。
- Tier3 サイドバーツリー基盤（`tree_view/*`）。
- 多アカウント対応（現状 `SecretStorage("gitlab.com")` ハードコードのまま。単一アカウント前提を踏襲）。

## 8. 受け入れ基準（初回 PR）

1. `com.gitlab.eclipse.api` 配下に `GitLabHttpClient` / `GitLabHttpClientFactory` / `GitLabApiClient` / `ApiRequest` / `GitLabIssue` / `IssueService` が存在し、Koin `ApiModule` で配線される。
2. `GitLabHttpClientFactory` が cert/certKey/CA/ignore/proxy の5設定を `HttpClient` 構築に反映する（ユニットテストで検証）。
3. `IssueService.getIssuesAssignedToMe()` が `GET /api/v4/issues?scope=assigned_to_me&state=opened` を `Authorization: Bearer` 付きで発行し DTO を返す（ユニットテストで検証）。
4. コマンド `gl.showIssuesAssignedToMe` でビューが開き、担当 Issue を一覧表示、ダブルクリックで `web_url` を外部ブラウザで開く（手動QAチェックリスト）。
5. `./gradlew compileKotlin` / `./gradlew test` がグリーン（GTK3 導入済み環境）。
6. PR は base=`develop`、**コード+テストのみ**（spec/plan/手動QA はローカル `gl-eclipse-planning/` に保持、非同梱）。
