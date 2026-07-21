# Tier3 PR2 — Enterprise egress design (native mTLS + CA trust + authenticated proxy)

最終更新: 2026-07-21
対象リポジトリ: GitHub `sumikof/gitlab-eclipse-plugin`（base branch = `develop`）
先行: Tier3 **PR1**（PR #4, MERGED）= native REST クライアント基盤 + 担当 Issue 一覧。
関連 spec: `2026-07-19-tier3-rest-client-issues-design.md`（§6.5 に PR1/PR2 分割・PEM メモ）。本書はその PR2 を brainstorming で詳細化した確定版。

---

## 1. 目的とスコープ

PR1 で確立した単一 egress シーム `GitLabHttpClientFactory` を「埋める」。native REST クライアントが enterprise の TLS/プロキシ設定を尊重するようにし、VSCode 拡張（`https.Agent`）の挙動にパリティで近づける。**新規依存を追加しない**（PR1 の "no new dependency" を維持）。

`EgressConfigSnapshot` は PR1 で cert/CA/proxy-cred フィールドを**既に保持**しているが未使用。PR2 はこれらを消費する実装を Factory 内に追加するのみ。snapshot→再構成ライフサイクル（`GitLabHttpClient`）は不変＝設定変更は再起動なしに次回 Refresh で反映。

### パリティの根拠（`./out` 実測 2026-07-21）
VSCode `desktop/gitlab/http/get_http_agent_options.ts` は `ca`/`cert`/`certKey` を `fs.readFileSync` で生 PEM 読み込みし、Node の `https.Agent` に渡す。オプション形は `{ ca?, cert?, key?, proxy?, rejectUnauthorized?, keepAlive? }`。
- **passphrase フィールドなし** → 暗号化鍵は非対応（パリティ対象外）。
- `rejectUnauthorized = !ignoreCertificateErrors` → Node ではチェーン**と**ホスト名の両方を無効化。
- 受理鍵形式（Node TLS）: RSA PKCS#1（`BEGIN RSA PRIVATE KEY`, OpenSSL 既定）/ PKCS#8（`BEGIN PRIVATE KEY`）/ EC SEC1（`BEGIN EC PRIVATE KEY`）、いずれも非暗号化。

### 確定した設計判断（brainstorming 2026-07-21・ユーザー承認）
1. **鍵パース = 純JDK + RSA 手動 wrap**（新規依存なし）。RSA PKCS#1/PKCS#8 を受理、EC SEC1・暗号化鍵は明確なエラー＋変換ヒント。
2. **ignore-cert = チェーン/信頼検証のみ無効化**（PR1 踏襲）。ホスト名検証は残す。`java.net.http.HttpClient` に per-client のホスト名検証 off スイッチが無く、唯一の手段 `jdk.internal.httpclient.disableHostnameVerification` は JVM グローバル・読み取り1回限り・all-or-nothing のため**採用しない**。
3. 三軸 SSLContext で **ignore-cert が CA 信頼を supersede**（両方設定時は trust-all 優先）。
4. proxy Basic-over-tunnel 用に `jdk.http.auth.tunneling.disabledSchemes` を**起動時に一度だけ**クリア。

---

## 2. アーキテクチャ全体像

変更は `com.gitlab.eclipse.api.http` パッケージ内に集約。request/view 層・`GitLabHttpClient` の再構成ロジックは無改変。

```
EgressConfigSnapshot（PR1 既存・全フィールド保持）
  ├─ ignoreCertificateErrors, caCertificatePath,
  │  clientCertificatePath, clientCertificateKeyPath  ─▶ TlsMaterialLoader（新規）
  └─ proxy(ProxyConfig: host/port/bypass/username/password) ─┬▶ BypassAwareProxySelector（PR1）
                                                             └▶ GitLabProxyAuthenticator（新規）
GitLabHttpClientFactory.create(snapshot)（既存を拡張）
  ├─ SSLContext を三軸から合成（KeyManager / TrustManager / hostname=既定）
  ├─ proxy 資格情報ありなら builder.authenticator(...) を追加
  └─ それ以外は PR1 と同じ既定ビルダー経路
GitLabEclipseStartup（既存 Activator を1行拡張）
  └─ 起動時に jdk.http.auth.tunneling.disabledSchemes を一度クリア
```

---

## 3. コンポーネント設計

### 3.1 `TlsMaterialLoader`（新規・純JDK・独立テスト可能）

PEM ファイルパスを JCA オブジェクトに変換する単一責務ユニット。副作用なし（ファイル読み取りのみ）。失敗は `GitLabApiException`（actionable message）を投げる。

**公開インターフェース（案）:**
- `fun loadTrustManagers(caCertPath: String): Array<TrustManager>` — CA PEM を信頼アンカーに。
- `fun loadKeyManagers(certPath: String, keyPath: String): Array<KeyManager>` — クライアント証明書チェーン+鍵。
- （内部）`parsePrivateKey(pem): PrivateKey` / `parseCertificates(pem): List<X509Certificate>`

**実装詳細:**
- **CA / 証明書チェーン:** `CertificateFactory.getInstance("X.509").generateCertificates(stream)`（複数 PEM ブロック対応）。CA は空の `KeyStore` に `setCertificateEntry` で投入 → `TrustManagerFactory.getInstance(default)`。
- **クライアント鍵（1 PEM ブロックを判定）:**
  - `BEGIN PRIVATE KEY`（PKCS#8）→ Base64 デコード → `PKCS8EncodedKeySpec` を **RSA → EC の順で `KeyFactory.getInstance(algo).generatePrivate(spec)` を try**（RSA が `InvalidKeySpecException` なら EC を試行）。両方失敗なら `GitLabApiException`。（OID 手動抽出より単純で堅牢。対応アルゴリズムは RSA/EC のみで十分＝パリティ範囲。）
  - `BEGIN RSA PRIVATE KEY`（PKCS#1）→ **手動 ASN.1 wrap**で PKCS#8 `PrivateKeyInfo` を構築（`AlgorithmIdentifier` = rsaEncryption OID `1.2.840.113549.1.1.1` + NULL params、`privateKey` OCTET STRING = 元 PKCS#1 DER）→ `KeyFactory.getInstance("RSA")`。固定プレフィックス + DER length 符号化（~15行）。
  - `BEGIN EC PRIVATE KEY`（SEC1）/ `BEGIN ENCRYPTED PRIVATE KEY` / passphrase 必要 → **`GitLabApiException`**。message 例: `EC/暗号化されたクライアント鍵は未対応です。'openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.pk8.pem' で PKCS#8 に変換してください。`
- **KeyManager:** 空の `KeyStore` に private key + 証明書チェーンを `setKeyEntry`（ダミーパスワード char[0]）→ `KeyManagerFactory.getInstance(default)`。

**ログ規約:** 鍵・パスワード・PEM 本文は**ログ出力しない**（パスのみ許容）。VSCode `log_network_options.ts` の redact（`certKey: "***"`）に倣う。

### 3.2 `GitLabHttpClientFactory.create()` — 三軸 SSLContext 合成

いずれかの軸が非既定のときのみカスタム `SSLContext` を構築（全軸既定なら PR1 の `HttpClient.newBuilder()...build()` 既定経路をそのまま維持）。

| 軸 | 条件 | 供給 |
|----|------|------|
| KeyManagers | client cert+key の**両パスあり** | `TlsMaterialLoader.loadKeyManagers` |
| KeyManagers | **両方なし**（cert/key とも空） | `null`（mTLS なし・正常） |
| KeyManagers | **片方のみ**指定 | **`GitLabApiException`**（cert と key は両方必須。§6 参照） |
| TrustManagers | `ignoreCertificateErrors` = true | trust-all（**チェーンのみ無効・既存 `trustAllContext`**）※ CA を supersede |
| TrustManagers | ignore=false かつ CA パスあり | `TlsMaterialLoader.loadTrustManagers` |
| TrustManagers | ignore=false かつ CA なし | `null`（JVM 既定トラスト） |
| Hostname | 常時 | 既定（HTTPS endpoint identification 有効・**無変更**） |

`SSLContext.getInstance("TLS").init(keyManagers, trustManagers, SecureRandom())` に合成。既存 `trustAllContext()` は trust-all TrustManager 供給の内部ヘルパに整理（SSLContext 直生成から TrustManager 供給へ）。

**エラー波及:** `create()` 内の PEM ロード失敗（`GitLabApiException`）は呼び出し元 `GitLabHttpClient.rebuildIfNeeded()` → `send()` を経て `IssuesView` の `runCatching` でユーザー可視エラーになる（PR1 の失敗表示経路を再利用）。＝設定ミスで機能全体がクラッシュせず、ビュー上にエラー表示。

### 3.3 `GitLabProxyAuthenticator`（新規）+ グローバルプロパティ

- `snapshot.proxy?.username`/`password` が両方あるとき `builder.authenticator(GitLabProxyAuthenticator(proxy))` を追加。
- `java.net.Authenticator` を継承し `getPasswordAuthentication()` をオーバーライド。**`requestorType == RequestorType.PROXY`** かつ **要求元 host/port が proxy と一致**するときのみ `PasswordAuthentication(username, password.toCharArray())` を返す。それ以外（サーバ 401 = `RequestorType.SERVER`）は `null` → **proxy 資格情報がサーバへ漏れない**。
- `toString`/ログに資格情報を出さない。
- **`jdk.http.auth.tunneling.disabledSchemes`:** HTTPS CONNECT トンネル越しの Basic proxy 認証は JDK 既定で無効。`GitLabEclipseStartup`（既存 Activator）で起動時に一度だけ `System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")` を実行。読み取り1回限りの性質のため遅延設定は不可、起動時が確実。**JVM グローバルの緩和**であることを明記（実害は認証プロキシを実際に使うときのみ・無設定時は無影響）。冪等ガード（既に空なら再設定不要）。`Authenticator.setDefault` は**使わない**（per-client authenticator のみ）。

---

## 4. データフロー（例: 認証プロキシ経由 HTTPS + mTLS）

1. `IssuesView` Refresh → `IssueService` → `GitLabApiClient.send` → `GitLabHttpClient.send`。
2. `rebuildIfNeeded()` が `factory.currentSnapshot()` を取得。前回と差分あれば `factory.create(snapshot)` で再構成（旧 client `shutdown()`）。
3. `create()`: cert+key → KeyManagers、ignore-cert or CA → TrustManagers、合成 SSLContext を `builder.sslContext(...)`。proxy → `BypassAwareProxySelector` + `GitLabProxyAuthenticator`。
4. 実 handshake で mTLS 提示、proxy が 407 → authenticator が PROXY 資格情報を返し CONNECT トンネル確立、GitLab へ到達。
5. 設定変更（cert/CA/proxy/ignore のいずれか）→ 次回 snapshot 不一致 → 自動再構成。

---

## 5. テスト戦略

### 5.1 headless（gradle test）で実行可能
- **`TlsMaterialLoader`（新規テスト）:** テスト時に純JDK で鍵/証明書を生成（`KeyPairGenerator` RSA/EC、`CertificateFactory`、自己署名は最小限）。
  - RSA PKCS#1 wrap がラウンドトリップ（生成→PKCS#1 PEM 化→loader→同一 modulus/exponent）。
  - RSA PKCS#8 / EC PKCS#8 が読める。
  - SEC1（`BEGIN EC PRIVATE KEY`）・暗号化（`BEGIN ENCRYPTED PRIVATE KEY`）→ `GitLabApiException` かつ message に変換ヒント。
  - CA PEM → TrustManager 構築成功。cert+key → KeyManager 構築成功。片方のみ → エラー。
  - 不正 PEM / 存在しないパス → `GitLabApiException`。
- **`GitLabHttpClientFactory`（既存テスト拡張）:** snapshot を与え、
  - KeyManager が cert+key 両方あるときのみ有効。
  - trust 軸選択（ignore→trust-all / CA→custom / none→default）と **ignore-cert が CA を supersede**。
  - proxy 資格情報ありで authenticator が付与され、`RequestorType.PROXY` にのみ資格情報、`SERVER` には `null`。
  - 全軸既定なら PR1 と同じ既定ビルダー（カスタム SSLContext なし）。
- **snapshot 等価性:** cert/CA/proxy-cred 変更で `EgressConfigSnapshot` が不一致 → 再構成（PR1 のライフサイクルテストに軸追加）。
- Kotest `DescribeSpec` + mockk + Koin（`ScopedPreferenceStore`/`LanguageServerProxyManager` をモック）。SWT 非接触のため GTK3 不要。

### 5.2 手動QA（実 IDE 必須・ローカル成果物）
headless 不可の項目を `manual-tests/2026-07-21-tier3-pr2-egress.md` に：
- 実 mTLS handshake（RSA PKCS#1 鍵・PKCS#8 鍵の両方）。
- 認証プロキシ経由 407→成功（Basic-over-tunnel）。
- CA 信頼のみ（自己署名 CA + サーバ証明書）での接続成功。
- 設定変更（cert/CA/proxy/ignore）後の再構成が再起動なしで反映。
- EC 鍵設定時に actionable エラーがビューに表示。

---

## 6. スコープ外（パリティ正直・spec 明記）

- **EC SEC1 / 暗号化クライアント鍵**: 明確なエラー + `openssl pkcs8` 変換ヒント（純JDK 手動 wrap は RSA のみ）。将来 bcpkix 導入で拡張可。
- **ホスト名検証バイパス**: 非対応（§1 判断2）。CN/SAN 不一致の自己署名は handshake 失敗 → CA 登録か一致証明書を案内。
- **GraphQL**: 対象外（REST のみ・後続スライス）。
- **クライアント証明書の片方のみ**指定: サポートせず明確なエラー（cert と key は両方必須）。

---

## 7. 実装単位（plan で分解）

1. `TlsMaterialLoader` + テスト（TDD）。RSA wrap がコア。
2. `GitLabProxyAuthenticator` + テスト。
3. `GitLabHttpClientFactory.create()` の三軸合成 + authenticator 配線 + テスト拡張。
4. `GitLabEclipseStartup` に `disabledSchemes` クリア（1行 + 冪等ガード）+ テスト。
5. 手動QA チェックリスト作成（ローカル）。

各タスク = TDD、タスク別コミット。最終ブランチ全体レビュー（opus）→ Codex コードレビュー → PR(base=develop, コード+テストのみ)。
