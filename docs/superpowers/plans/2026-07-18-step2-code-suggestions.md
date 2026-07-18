# ステップ2: Code Suggestions(インライン補完)実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GitLab Language Server の `textDocument/inlineCompletion` を使った ghost text インライン補完(ストリーミング生成・TAB/ESC/単語受け入れ/手動トリガー・SHOWN/ACCEPTEDテレメトリ込み)を Eclipse プラグインに実装する。

**Architecture:** セッションマネージャ主導(スペック §4 案B)。純JDK POJO 層(`com.gitlab.eclipse.suggestions`: モデル・ストリームバッファ・描画計画)と Eclipse 配線層(`com.gitlab.eclipse.suggestions.ui`)を分離。ghost text は CodeMining API(Platform 4.34+)のみで描画。実装パターンは microsoft/copilot-for-eclipse(MIT)で実証済みのものを踏襲。

**Tech Stack:** Java 21 / Tycho 4.0.13 / Eclipse 2025-06 (Platform 4.36) / LSP4E 0.18.x / LSP4J 0.23.1 / gson / JUnit 5

## Global Constraints

- 検証コマンドはリポジトリルートで `mvn -q verify`(全テストグリーン必須。現在21件)
- 全コミットメッセージ末尾に以下を付与:
  ```
  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_019FJzDHRGfimekUdNM1Uwmw
  ```
- インデントはタブ(既存コードに合わせる)
- `/workspace/out/` は参照コピーのため変更・コミット禁止
- LSP4J の `InlineCompletion*` 型は使わない(0.23.1 に存在しない)。`Position` / `Range` / `TextDocumentIdentifier` は LSP4J のものを再利用する
- POJO 層(`com.gitlab.eclipse.suggestions` 直下)には OSGi/Eclipse/SWT の import を持ち込まない

## 確定済みプロトコル定数(gitlab-lsp v9.5.0 実ソースで検証済み。変更禁止)

| 項目 | 確定値 |
|---|---|
| インライン補完リクエスト | `textDocument/inlineCompletion` → レスポンスは `{"items":[...]}`(配列ではない) |
| triggerKind | Invoked=1, Automatic=2(LSP 3.18) |
| 通常itemのcommand | `gitlab.ls.codeSuggestionAccepted`, arguments=[trackingId(string), optionIndex(number,1始まり,省略可)] |
| ストリーミングitemのcommand | `gitlab.ls.startStreaming`, arguments=[streamId(string), trackingId(string)]。insertTextは空 |
| ストリーミング通知(server→client) | `streamingCompletionResponse`、`{id: string, completion?: string, done: boolean}`。**completionは累積文字列**(連結不要、最新が全体) |
| キャンセル通知(client→server) | `cancelStreaming`、`{id: string}` |
| テレメトリ通知(client→server) | `$/gitlab/telemetry`、`{category:"code_suggestions", action, context:{trackingId, optionId?}}` |
| テレメトリaction実値 | SHOWN=`suggestion_shown`, ACCEPTED=`suggestion_accepted` |
| テレメトリ購読設定 | didChangeConfiguration の `telemetry.actions: [{action:"suggestion_shown"},{action:"suggestion_accepted"}]`。クライアントがSHOWNを購読しない場合はLSが自前でSHOWN記録するため、**購読する以上クライアントは必ずSHOWNを送る義務がある** |
| アクティブエディタ通知 | `$/gitlab/didChangeDocumentInActiveEditor`、パラメータは**uri文字列単体**(推奨形) |
| ストリーミング有効化 | capability不要。didChangeConfiguration の `featureFlags.streamCodeGenerations: true`(既存コードが送信済み) |

## Copilot for Eclipse 実証済みパターン(踏襲する。根拠: microsoft/copilot-for-eclipse, MIT)

- CodeMining登録は `org.eclipse.ui.workbench.texteditor.codeMiningProviders`(enabledWhen不要、JDTエディタにも無条件適用)
- `LineContentCodeMining` のPositionは **length≥1 必須**(2024-12の癖)。カーソル行末では `LineEndCodeMining` を使う
- 複数行ブロックは `LineHeaderCodeMining` 1個に `\n` 入りラベルを丸ごと渡す(行分割不要)。**最終行ではブロック表示不可**(次行が無いため。既知の制約として許容)
- 再描画は必ず `Display.asyncExec` 経由で `((ISourceViewerExtension5) viewer).updateCodeMinings()`(同期呼び出しはLSP4Eのロックとデッドロックする)
- CodeMiningはタブ文字を描画しない → 各行の**先頭タブのみ**スペース置換
- TAB/ESC は `org.eclipse.ui.textEditorScope` に直接バインドし、**ハンドラの `isEnabled()`**(候補なし→false)で無効化して通常編集に素通しさせる。独自コンテキスト(`parentId=org.eclipse.ui.textEditorScope`)は Ctrl+→ のみに使う ※スペック§4は「TAB/ESCもコンテキスト限定」としていたが、実証済みのこのパターンに変更する(競合回避の実績があるため)
- キャレット追跡は CaretListener ではなく KeyListener(keyReleased)+ MouseListener(mouseDown)
- ドキュメント変更は `ITextViewer.addTextListener`(IDocumentListenerではない)

## ファイル構成(全タスクの俯瞰)

```
bundles/gitlab-eclipse-plugin/
  META-INF/MANIFEST.MF                          … 変更(T1: gson import / T7: jface.text等)
  plugin.xml                                    … 変更(T7: startup / T8: codeMining / T9: commands等)
  src/com/gitlab/eclipse/lsp/
    GitLabLanguageServer.java                   … 変更(T2)
    GitLabLanguageClient.java                   … 変更(T2)
    GitLabLanguageServerProvider.java           … 変更(T6: telemetry.actions)
    GitLabLanguageServerConfigurationParams.java … 変更(T6: Telemetryにactions追加)
    InlineCompletionParams.java                 … 新規(T1)
    InlineCompletionContext.java                … 新規(T1)
    SelectedCompletionInfo.java                 … 新規(T1)
    InlineCompletionItem.java                   … 新規(T1)
    InlineCompletionCommand.java                … 新規(T1)
    InlineCompletionList.java                   … 新規(T1)
    StreamingCompletionResponse.java            … 新規(T1)
    CancelStreamingParams.java                  … 新規(T1)
    TelemetryParams.java                        … 新規(T1)
    SuggestionTelemetry.java                    … 新規(T6)
  src/com/gitlab/eclipse/suggestions/           … 純JDK POJO
    StreamingCompletionEvents.java              … 新規(T2)
    SuggestionModel.java                        … 新規(T3)
    StreamBuffer.java                           … 新規(T4)
    RenderPlan.java                             … 新規(T5)
  src/com/gitlab/eclipse/suggestions/ui/        … Eclipse配線(ハンドラも同パッケージ=package-private共有)
    SuggestionSessions.java                     … 新規(T7)
    CompletionSessionManager.java               … 新規(T7、T9/T10で加筆)
    EditorTracker.java                          … 新規(T7)
    SuggestionStartup.java                      … 新規(T7、T10で加筆)
    GhostTextCodeMiningProvider.java            … 新規(T8)
    InlineGhostTextMining.java                  … 新規(T8)
    EolGhostTextMining.java                     … 新規(T8)
    BlockGhostTextMining.java                   … 新規(T8)
    AcceptSuggestionHandler.java                … 新規(T9)
    AcceptNextWordHandler.java                  … 新規(T9)
    DismissSuggestionHandler.java               … 新規(T9)
    TriggerSuggestionHandler.java               … 新規(T9)
tests/gitlab-eclipse-plugin.tests/
  META-INF/MANIFEST.MF                          … 変更(T1: gson import)
  src/com/gitlab/eclipse/lsp/InlineCompletionDtoTest.java        … 新規(T1)
  src/com/gitlab/eclipse/suggestions/StreamingCompletionEventsTest.java … 新規(T2)
  src/com/gitlab/eclipse/suggestions/SuggestionModelTest.java    … 新規(T3)
  src/com/gitlab/eclipse/suggestions/StreamBufferTest.java       … 新規(T4)
  src/com/gitlab/eclipse/suggestions/RenderPlanTest.java         … 新規(T5)
  src/com/gitlab/eclipse/lsp/GitLabLanguageServerConfigurationParamsTest.java … 変更(T6)
docs/manual-tests/2026-07-18-step2-code-suggestions.md           … 新規(T11)
```

---

### Task 1: プロトコルDTO(record群)とシリアライズテスト

**Files:**
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/InlineCompletionParams.java` ほか計9 DTO(下記)
- Modify: `bundles/gitlab-eclipse-plugin/META-INF/MANIFEST.MF`(gson import追加)
- Modify: `tests/gitlab-eclipse-plugin.tests/META-INF/MANIFEST.MF`(gson import追加)
- Test: `tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/lsp/InlineCompletionDtoTest.java`

**Interfaces:**
- Consumes: `org.eclipse.lsp4j.Position` / `Range` / `TextDocumentIdentifier`(既存依存)
- Produces(後続タスクが使う正確な型):
  - `record InlineCompletionParams(TextDocumentIdentifier textDocument, Position position, InlineCompletionContext context)`
  - `record InlineCompletionContext(int triggerKind, SelectedCompletionInfo selectedCompletionInfo)` + 定数 `TRIGGER_INVOKED=1` / `TRIGGER_AUTOMATIC=2`
  - `record InlineCompletionItem(String insertText, Range range, InlineCompletionCommand command)`
  - `record InlineCompletionCommand(String title, String command, List<Object> arguments)` + 定数 `START_STREAMING` / `SUGGESTION_ACCEPTED` + `String stringArg(int)` / `Integer intArg(int)`
  - `record InlineCompletionList(List<InlineCompletionItem> items)`
  - `record StreamingCompletionResponse(String id, String completion, boolean done)`
  - `record CancelStreamingParams(String id)`
  - `record TelemetryParams(String category, String action, CodeSuggestionsContext context)` + `static TelemetryParams codeSuggestion(String action, String trackingId, Integer optionId)` + 定数 `ACTION_SHOWN="suggestion_shown"` / `ACTION_ACCEPTED="suggestion_accepted"`

- [ ] **Step 1: MANIFEST 2つに gson import を追加**

`bundles/gitlab-eclipse-plugin/META-INF/MANIFEST.MF` の行
```
Import-Package: jakarta.inject;version="[2.0.0,3.0.0)"
```
を
```
Import-Package: com.google.gson;version="[2.10.0,3.0.0)",
 jakarta.inject;version="[2.0.0,3.0.0)"
```
に変更。`tests/gitlab-eclipse-plugin.tests/META-INF/MANIFEST.MF` の `Import-Package:` にも同様に `com.google.gson;version="[2.10.0,3.0.0)",` を先頭要素として追加。

- [ ] **Step 2: 失敗するテストを書く**

`tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/lsp/InlineCompletionDtoTest.java`:

```java
package com.gitlab.eclipse.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

class InlineCompletionDtoTest {
	private final Gson gson = new Gson();

	@Test
	void serializesParamsToLspShape() {
		var params = new InlineCompletionParams(
				new TextDocumentIdentifier("file:///tmp/A.java"),
				new Position(3, 7),
				new InlineCompletionContext(InlineCompletionContext.TRIGGER_AUTOMATIC, null));
		JsonObject json = gson.toJsonTree(params).getAsJsonObject();
		assertEquals("file:///tmp/A.java", json.getAsJsonObject("textDocument").get("uri").getAsString());
		assertEquals(3, json.getAsJsonObject("position").get("line").getAsInt());
		assertEquals(7, json.getAsJsonObject("position").get("character").getAsInt());
		assertEquals(2, json.getAsJsonObject("context").get("triggerKind").getAsInt());
	}

	@Test
	void deserializesNormalItemWithAcceptedCommand() {
		String json = """
				{"items":[{"insertText":"return x;","range":{"start":{"line":1,"character":2},
				 "end":{"line":1,"character":2}},"command":{"title":"accepted",
				 "command":"gitlab.ls.codeSuggestionAccepted","arguments":["tracking-1",2]}}]}""";
		var list = gson.fromJson(json, InlineCompletionList.class);
		var item = list.items().get(0);
		assertEquals("return x;", item.insertText());
		assertEquals(InlineCompletionCommand.SUGGESTION_ACCEPTED, item.command().command());
		assertEquals("tracking-1", item.command().stringArg(0));
		assertEquals(Integer.valueOf(2), item.command().intArg(1)); // gsonは数値をDoubleで持つ
		assertNull(item.command().intArg(5));                       // 範囲外はnull
	}

	@Test
	void deserializesStreamingItem() {
		String json = """
				{"items":[{"insertText":"","command":{"title":"Start streaming",
				 "command":"gitlab.ls.startStreaming","arguments":["stream-1","tracking-1"]}}]}""";
		var item = gson.fromJson(json, InlineCompletionList.class).items().get(0);
		assertEquals(InlineCompletionCommand.START_STREAMING, item.command().command());
		assertEquals("stream-1", item.command().stringArg(0));
		assertEquals("tracking-1", item.command().stringArg(1));
		assertNull(item.range());
	}

	@Test
	void streamingResponseAllowsMissingCompletion() {
		var response = gson.fromJson("{\"id\":\"s1\",\"done\":true}", StreamingCompletionResponse.class);
		assertEquals("s1", response.id());
		assertNull(response.completion());
		assertTrue(response.done());
	}

	@Test
	void telemetryFactoryBuildsCodeSuggestionEvent() {
		var params = TelemetryParams.codeSuggestion(TelemetryParams.ACTION_SHOWN, "tracking-1", 2);
		assertEquals("code_suggestions", params.category());
		assertEquals("suggestion_shown", params.action());
		assertEquals("tracking-1", params.context().trackingId());
		assertEquals(Integer.valueOf(2), params.context().optionId());
		JsonObject json = gson.toJsonTree(params).getAsJsonObject();
		assertEquals("code_suggestions", json.get("category").getAsString());
	}
}
```

- [ ] **Step 3: 赤を確認**

Run: `mvn -q verify 2>&1 | tail -30`
Expected: コンパイルエラー(`InlineCompletionParams` 等が存在しない)で FAIL。

- [ ] **Step 4: DTO 9ファイルを実装**

`bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/InlineCompletionParams.java`:
```java
package com.gitlab.eclipse.lsp;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;

/** LSP 3.18 textDocument/inlineCompletion request params (subset we need). */
public record InlineCompletionParams(TextDocumentIdentifier textDocument, Position position,
		InlineCompletionContext context) {}
```

`InlineCompletionContext.java`:
```java
package com.gitlab.eclipse.lsp;

public record InlineCompletionContext(int triggerKind, SelectedCompletionInfo selectedCompletionInfo) {
	public static final int TRIGGER_INVOKED = 1;
	public static final int TRIGGER_AUTOMATIC = 2;
}
```

`SelectedCompletionInfo.java`:
```java
package com.gitlab.eclipse.lsp;

import org.eclipse.lsp4j.Range;

public record SelectedCompletionInfo(Range range, String text) {}
```

`InlineCompletionItem.java`:
```java
package com.gitlab.eclipse.lsp;

import org.eclipse.lsp4j.Range;

public record InlineCompletionItem(String insertText, Range range, InlineCompletionCommand command) {}
```

`InlineCompletionCommand.java`:
```java
package com.gitlab.eclipse.lsp;

import java.util.List;

import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;

/**
 * Command attached to an inline-completion item. gitlab-lsp uses it to carry
 * tracking ids; argument values may arrive as raw JSON primitives depending on
 * the deserializer, so the accessors normalize both representations.
 */
public record InlineCompletionCommand(String title, String command, List<Object> arguments) {
	public static final String START_STREAMING = "gitlab.ls.startStreaming";
	public static final String SUGGESTION_ACCEPTED = "gitlab.ls.codeSuggestionAccepted";

	public String stringArg(int index) {
		Object value = raw(index);
		if (value == null) {
			return null;
		}
		if (value instanceof JsonPrimitive primitive) {
			return primitive.getAsString();
		}
		return value.toString();
	}

	public Integer intArg(int index) {
		Object value = raw(index);
		if (value instanceof JsonPrimitive primitive && primitive.isNumber()) {
			return primitive.getAsInt();
		}
		if (value instanceof Number number) {
			return number.intValue();
		}
		return null;
	}

	private Object raw(int index) {
		if (arguments == null || index < 0 || index >= arguments.size()) {
			return null;
		}
		Object value = arguments.get(index);
		return value instanceof JsonNull ? null : value;
	}
}
```

`InlineCompletionList.java`:
```java
package com.gitlab.eclipse.lsp;

import java.util.List;

public record InlineCompletionList(List<InlineCompletionItem> items) {}
```

`StreamingCompletionResponse.java`:
```java
package com.gitlab.eclipse.lsp;

/** gitlab-lsp 'streamingCompletionResponse' notification. {@code completion} is cumulative. */
public record StreamingCompletionResponse(String id, String completion, boolean done) {}
```

`CancelStreamingParams.java`:
```java
package com.gitlab.eclipse.lsp;

/** gitlab-lsp 'cancelStreaming' notification params. */
public record CancelStreamingParams(String id) {}
```

`TelemetryParams.java`:
```java
package com.gitlab.eclipse.lsp;

/** gitlab-lsp '$/gitlab/telemetry' notification params. */
public record TelemetryParams(String category, String action, CodeSuggestionsContext context) {
	public static final String CATEGORY_CODE_SUGGESTIONS = "code_suggestions";
	public static final String ACTION_SHOWN = "suggestion_shown";
	public static final String ACTION_ACCEPTED = "suggestion_accepted";

	public record CodeSuggestionsContext(String trackingId, Integer optionId) {}

	public static TelemetryParams codeSuggestion(String action, String trackingId, Integer optionId) {
		return new TelemetryParams(CATEGORY_CODE_SUGGESTIONS, action, new CodeSuggestionsContext(trackingId, optionId));
	}
}
```

- [ ] **Step 5: 緑を確認**

Run: `mvn -q verify 2>&1 | tail -10`
Expected: `Tests run: 26, Failures: 0, Errors: 0`(21既存+5新規)
※もし gson が record 非対応バージョンで FAIL した場合のみ、DTO をフィールド+getter のクラスに書き換える(その場合もテストは変えない)。

- [ ] **Step 6: コミット**

```bash
git add bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/ bundles/gitlab-eclipse-plugin/META-INF/MANIFEST.MF tests/
git commit -m "feat: Add inline-completion protocol DTOs"
```

---

### Task 2: LSPインターフェース拡張とストリーミングイベントディスパッチャ

**Files:**
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/GitLabLanguageServer.java`
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/GitLabLanguageClient.java`
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/StreamingCompletionEvents.java`
- Test: `tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/suggestions/StreamingCompletionEventsTest.java`

**Interfaces:**
- Consumes: Task 1 の DTO 全種
- Produces:
  - `GitLabLanguageServer#inlineCompletion(InlineCompletionParams): CompletableFuture<InlineCompletionList>`
  - `GitLabLanguageServer#cancelStreaming(CancelStreamingParams): void`
  - `GitLabLanguageServer#telemetry(TelemetryParams): void`
  - `GitLabLanguageServer#didChangeDocumentInActiveEditor(String uri): void`
  - `StreamingCompletionEvents.subscribe/unsubscribe(Consumer<StreamingCompletionResponse>)` / `publish(StreamingCompletionResponse)`(static)

- [ ] **Step 1: 失敗するテストを書く**

`tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/suggestions/StreamingCompletionEventsTest.java`:

```java
package com.gitlab.eclipse.suggestions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.gitlab.eclipse.lsp.StreamingCompletionResponse;

class StreamingCompletionEventsTest {
	@Test
	void deliversToSubscribersAndStopsAfterUnsubscribe() {
		List<String> seen = new ArrayList<>();
		Consumer<StreamingCompletionResponse> listener = r -> seen.add(r.id());
		StreamingCompletionEvents.subscribe(listener);
		try {
			StreamingCompletionEvents.publish(new StreamingCompletionResponse("a", "x", false));
		} finally {
			StreamingCompletionEvents.unsubscribe(listener);
		}
		StreamingCompletionEvents.publish(new StreamingCompletionResponse("b", "y", true));
		assertEquals(List.of("a"), seen);
	}

	@Test
	void aFailingListenerDoesNotBlockOthers() {
		List<String> seen = new ArrayList<>();
		Consumer<StreamingCompletionResponse> bad = r -> { throw new IllegalStateException("boom"); };
		Consumer<StreamingCompletionResponse> good = r -> seen.add(r.id());
		StreamingCompletionEvents.subscribe(bad);
		StreamingCompletionEvents.subscribe(good);
		try {
			StreamingCompletionEvents.publish(new StreamingCompletionResponse("a", null, true));
		} finally {
			StreamingCompletionEvents.unsubscribe(bad);
			StreamingCompletionEvents.unsubscribe(good);
		}
		assertEquals(List.of("a"), seen);
	}
}
```

- [ ] **Step 2: 赤を確認**

Run: `mvn -q verify 2>&1 | tail -20`
Expected: コンパイルエラー(`StreamingCompletionEvents` 不在)で FAIL。

- [ ] **Step 3: 実装**

`bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/StreamingCompletionEvents.java`:
```java
package com.gitlab.eclipse.suggestions;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.gitlab.eclipse.lsp.StreamingCompletionResponse;

/**
 * Static dispatch point decoupling the LSP client (which receives
 * 'streamingCompletionResponse' notifications) from UI subscribers.
 * Pure JDK: no OSGi/Eclipse dependencies.
 */
public final class StreamingCompletionEvents {
	private static final List<Consumer<StreamingCompletionResponse>> LISTENERS = new CopyOnWriteArrayList<>();

	private StreamingCompletionEvents() {}

	public static void subscribe(Consumer<StreamingCompletionResponse> listener) {
		LISTENERS.add(listener);
	}

	public static void unsubscribe(Consumer<StreamingCompletionResponse> listener) {
		LISTENERS.remove(listener);
	}

	public static void publish(StreamingCompletionResponse response) {
		for (Consumer<StreamingCompletionResponse> listener : LISTENERS) {
			try {
				listener.accept(response);
			} catch (RuntimeException e) {
				// one broken listener must not starve the rest
			}
		}
	}
}
```

`GitLabLanguageServer.java` を以下の内容に置き換え(コメントアウトのスケッチを正式実装に):
```java
package com.gitlab.eclipse.lsp;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageServer;

public interface GitLabLanguageServer extends LanguageServer {
	@JsonRequest("$/gitlab/webview-metadata")
	public CompletableFuture<List<WebviewInfo>> webviewMetadata();

	@JsonRequest("textDocument/inlineCompletion")
	public CompletableFuture<InlineCompletionList> inlineCompletion(InlineCompletionParams params);

	@JsonNotification("cancelStreaming")
	public void cancelStreaming(CancelStreamingParams params);

	@JsonNotification("$/gitlab/telemetry")
	public void telemetry(TelemetryParams params);

	@JsonNotification("$/gitlab/didChangeDocumentInActiveEditor")
	public void didChangeDocumentInActiveEditor(String uri);
}
```

`GitLabLanguageClient.java` にメソッド追加(既存の `gitlabFeatureStateChange` の上に):
```java
	@JsonNotification("streamingCompletionResponse")
	public void streamingCompletionResponse(StreamingCompletionResponse params) {
		StreamingCompletionEvents.publish(params);
	}
```
import に `com.gitlab.eclipse.suggestions.StreamingCompletionEvents` を追加。

- [ ] **Step 4: 緑を確認**

Run: `mvn -q verify 2>&1 | tail -10`
Expected: `Tests run: 28, Failures: 0, Errors: 0`

- [ ] **Step 5: コミット**

```bash
git add bundles/ tests/
git commit -m "feat: Wire inline-completion and streaming methods into LSP interfaces"
```

---

### Task 3: SuggestionModel(セッション状態と単語分割)

**Files:**
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/SuggestionModel.java`
- Test: `tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/suggestions/SuggestionModelTest.java`

**Interfaces:**
- Produces:
  - `record SuggestionModel.Suggestion(String text, int offset, String trackingId, Integer optionIndex)`
  - `void show(Suggestion)` / `void clear()` / `boolean isShowing()` / `Suggestion current()`
  - `String remainingText()`(未挿入部分、非表示ならnull)/ `int insertionOffset()`(次挿入位置)
  - `String nextChunk()`(次の単語チャンク: 先頭空白+単語連続 or 記号1文字。なければnull)
  - `boolean advance(int chars)`(受け入れ済みを進める。全消費でclearしてtrue)

- [ ] **Step 1: 失敗するテストを書く**

`tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/suggestions/SuggestionModelTest.java`:

```java
package com.gitlab.eclipse.suggestions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.gitlab.eclipse.suggestions.SuggestionModel.Suggestion;

class SuggestionModelTest {
	private final SuggestionModel model = new SuggestionModel();

	@Test
	void showClearLifecycle() {
		assertFalse(model.isShowing());
		assertNull(model.remainingText());
		model.show(new Suggestion("abc", 10, "t1", null));
		assertTrue(model.isShowing());
		assertEquals("abc", model.remainingText());
		assertEquals(10, model.insertionOffset());
		model.clear();
		assertFalse(model.isShowing());
		assertNull(model.remainingText());
	}

	@Test
	void nextChunkSplitsWordsWhitespaceAndSymbols() {
		model.show(new Suggestion("foo bar();", 0, "t1", null));
		assertEquals("foo", model.nextChunk());
		model.advance(3);
		assertEquals(" bar", model.nextChunk());
		model.advance(4);
		assertEquals("(", model.nextChunk());
		model.advance(1);
		assertEquals(")", model.nextChunk());
		model.advance(1);
		assertEquals(";", model.nextChunk());
	}

	@Test
	void nextChunkCarriesNewlineIndentIntoNextWord() {
		model.show(new Suggestion("a\n\tb", 0, "t1", null));
		assertEquals("a", model.nextChunk());
		model.advance(1);
		assertEquals("\n\tb", model.nextChunk());
	}

	@Test
	void nextChunkReturnsTrailingWhitespaceWhole() {
		model.show(new Suggestion("a  ", 0, "t1", null));
		model.advance(1);
		assertEquals("  ", model.nextChunk());
	}

	@Test
	void advanceClearsWhenFullyConsumed() {
		model.show(new Suggestion("ab", 5, "t1", 2));
		assertFalse(model.advance(1));
		assertEquals(6, model.insertionOffset());
		assertEquals("b", model.remainingText());
		assertTrue(model.advance(1));
		assertFalse(model.isShowing());
	}
}
```

- [ ] **Step 2: 赤を確認**

Run: `mvn -q verify 2>&1 | tail -20`
Expected: コンパイルエラー(`SuggestionModel` 不在)で FAIL。

- [ ] **Step 3: 実装**

`bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/SuggestionModel.java`:
```java
package com.gitlab.eclipse.suggestions;

/**
 * Holds the currently displayed inline suggestion and the word-by-word
 * acceptance progress. Thread-safe. Pure JDK: no OSGi/Eclipse dependencies.
 */
public final class SuggestionModel {
	public record Suggestion(String text, int offset, String trackingId, Integer optionIndex) {}

	private Suggestion current;
	private int consumed;

	public synchronized void show(Suggestion suggestion) {
		this.current = suggestion;
		this.consumed = 0;
	}

	public synchronized void clear() {
		current = null;
		consumed = 0;
	}

	public synchronized boolean isShowing() {
		return current != null;
	}

	public synchronized Suggestion current() {
		return current;
	}

	/** Text not yet inserted into the document, or null when nothing is showing. */
	public synchronized String remainingText() {
		return current == null ? null : current.text().substring(consumed);
	}

	/** Document offset where {@link #remainingText()} should be inserted. */
	public synchronized int insertionOffset() {
		return current.offset() + consumed;
	}

	/**
	 * Next word-sized chunk of the remaining text: leading whitespace plus a run
	 * of word characters, or a single symbol. Null when nothing remains.
	 */
	public synchronized String nextChunk() {
		String remaining = remainingText();
		if (remaining == null || remaining.isEmpty()) {
			return null;
		}
		int i = 0;
		while (i < remaining.length() && Character.isWhitespace(remaining.charAt(i))) {
			i++;
		}
		if (i == remaining.length()) {
			return remaining;
		}
		if (isWordChar(remaining.charAt(i))) {
			while (i < remaining.length() && isWordChar(remaining.charAt(i))) {
				i++;
			}
		} else {
			i++;
		}
		return remaining.substring(0, i);
	}

	/** Advances past an accepted chunk. Returns true when fully consumed (and clears). */
	public synchronized boolean advance(int chars) {
		consumed += chars;
		if (current != null && consumed >= current.text().length()) {
			clear();
			return true;
		}
		return false;
	}

	private static boolean isWordChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}
}
```

- [ ] **Step 4: 緑を確認**

Run: `mvn -q verify 2>&1 | tail -10`
Expected: `Tests run: 33, Failures: 0, Errors: 0`

- [ ] **Step 5: コミット**

```bash
git add bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/SuggestionModel.java tests/
git commit -m "feat: Add suggestion session model with word-by-word acceptance"
```

---

### Task 4: StreamBuffer(ストリーミング応答の蓄積)

**Files:**
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/StreamBuffer.java`
- Test: `tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/suggestions/StreamBufferTest.java`

**Interfaces:**
- Consumes: `StreamingCompletionResponse`(Task 1)
- Produces:
  - `record StreamBuffer.Completed(String text, String trackingId, Integer optionIndex)`
  - `void start(String streamId, String trackingId, Integer optionIndex)` / `boolean isActive()`
  - `Completed onNotification(StreamingCompletionResponse)`(完了時のみ非null)
  - `String cancel()`(アクティブならstreamIdを返しリセット。cancelStreaming送信用)

- [ ] **Step 1: 失敗するテストを書く**

`tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/suggestions/StreamBufferTest.java`:

```java
package com.gitlab.eclipse.suggestions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.gitlab.eclipse.lsp.StreamingCompletionResponse;

class StreamBufferTest {
	private final StreamBuffer buffer = new StreamBuffer();

	@Test
	void accumulatesCumulativeChunksUntilDone() {
		buffer.start("s1", "t1", 2);
		assertTrue(buffer.isActive());
		assertNull(buffer.onNotification(new StreamingCompletionResponse("s1", "hel", false)));
		assertNull(buffer.onNotification(new StreamingCompletionResponse("s1", "hello", false)));
		var completed = buffer.onNotification(new StreamingCompletionResponse("s1", null, true));
		assertEquals("hello", completed.text()); // done通知はcompletion無し=最後の累積値を使う
		assertEquals("t1", completed.trackingId());
		assertEquals(Integer.valueOf(2), completed.optionIndex());
		assertFalse(buffer.isActive());
	}

	@Test
	void ignoresNotificationsForOtherStreams() {
		buffer.start("s1", "t1", null);
		assertNull(buffer.onNotification(new StreamingCompletionResponse("other", "xxx", true)));
		assertTrue(buffer.isActive());
	}

	@Test
	void ignoresNotificationsWhenIdle() {
		assertNull(buffer.onNotification(new StreamingCompletionResponse("s1", "x", true)));
	}

	@Test
	void cancelReturnsIdOnceAndDropsLaterChunks() {
		buffer.start("s1", "t1", null);
		assertEquals("s1", buffer.cancel());
		assertNull(buffer.cancel());
		assertNull(buffer.onNotification(new StreamingCompletionResponse("s1", "late", true)));
	}

	@Test
	void doneWithoutAnyChunkCompletesEmpty() {
		buffer.start("s1", "t1", null);
		assertEquals("", buffer.onNotification(new StreamingCompletionResponse("s1", null, true)).text());
	}
}
```

- [ ] **Step 2: 赤を確認**

Run: `mvn -q verify 2>&1 | tail -20`
Expected: コンパイルエラー(`StreamBuffer` 不在)で FAIL。

- [ ] **Step 3: 実装**

`bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/StreamBuffer.java`:
```java
package com.gitlab.eclipse.suggestions;

import com.gitlab.eclipse.lsp.StreamingCompletionResponse;

/**
 * Accumulates gitlab-lsp streaming notifications for the active stream.
 * The server sends cumulative text, so only the latest chunk is kept.
 * Thread-safe. Pure JDK: no OSGi/Eclipse dependencies.
 */
public final class StreamBuffer {
	public record Completed(String text, String trackingId, Integer optionIndex) {}

	private String streamId;
	private String trackingId;
	private Integer optionIndex;
	private String latest = "";

	public synchronized void start(String streamId, String trackingId, Integer optionIndex) {
		this.streamId = streamId;
		this.trackingId = trackingId;
		this.optionIndex = optionIndex;
		this.latest = "";
	}

	public synchronized boolean isActive() {
		return streamId != null;
	}

	/** Returns the finished suggestion when this notification completes the active stream, else null. */
	public synchronized Completed onNotification(StreamingCompletionResponse response) {
		if (streamId == null || !streamId.equals(response.id())) {
			return null;
		}
		if (response.completion() != null) {
			latest = response.completion();
		}
		if (!response.done()) {
			return null;
		}
		Completed completed = new Completed(latest, trackingId, optionIndex);
		reset();
		return completed;
	}

	/** Clears the buffer; returns the id to send 'cancelStreaming' for, or null when idle. */
	public synchronized String cancel() {
		String id = streamId;
		reset();
		return id;
	}

	private void reset() {
		streamId = null;
		trackingId = null;
		optionIndex = null;
		latest = "";
	}
}
```

- [ ] **Step 4: 緑を確認**

Run: `mvn -q verify 2>&1 | tail -10`
Expected: `Tests run: 38, Failures: 0, Errors: 0`

- [ ] **Step 5: コミット**

```bash
git add bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/StreamBuffer.java tests/
git commit -m "feat: Add stream buffer for cumulative code-generation chunks"
```

---

### Task 5: RenderPlan(描画分割とタブ置換)

**Files:**
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/RenderPlan.java`
- Test: `tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/suggestions/RenderPlanTest.java`

**Interfaces:**
- Produces:
  - `record RenderPlan(String firstLine, String block)` — `firstLine`=カーソル行にインライン表示する文字列、`block`=次行以降にブロック表示する複数行文字列(なければnull)
  - `static RenderPlan of(String suggestionText, int tabSize)` — 空入力はnull。改行を`\n`に正規化し、各行の**先頭タブのみ**スペース展開

- [ ] **Step 1: 失敗するテストを書く**

`tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/suggestions/RenderPlanTest.java`:

```java
package com.gitlab.eclipse.suggestions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class RenderPlanTest {
	@Test
	void singleLineHasNoBlock() {
		var plan = RenderPlan.of("return x;", 4);
		assertEquals("return x;", plan.firstLine());
		assertNull(plan.block());
	}

	@Test
	void splitsFirstLineFromBlock() {
		var plan = RenderPlan.of("if (x) {\n\treturn;\n}", 4);
		assertEquals("if (x) {", plan.firstLine());
		assertEquals("    return;\n}", plan.block());
	}

	@Test
	void expandsOnlyLeadingTabs() {
		var plan = RenderPlan.of("\ta\tb\n\t\tc", 2);
		assertEquals("  a\tb", plan.firstLine()); // 行中のタブは温存
		assertEquals("    c", plan.block());
	}

	@Test
	void normalizesCrlf() {
		var plan = RenderPlan.of("a\r\nb\rc", 4);
		assertEquals("a", plan.firstLine());
		assertEquals("b\nc", plan.block());
	}

	@Test
	void trailingNewlineOnlyMeansNoBlock() {
		assertNull(RenderPlan.of("a\n", 4).block());
	}

	@Test
	void blankInputYieldsNull() {
		assertNull(RenderPlan.of("", 4));
		assertNull(RenderPlan.of(null, 4));
	}
}
```

- [ ] **Step 2: 赤を確認**

Run: `mvn -q verify 2>&1 | tail -20`
Expected: コンパイルエラー(`RenderPlan` 不在)で FAIL。

- [ ] **Step 3: 実装**

`bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/RenderPlan.java`:
```java
package com.gitlab.eclipse.suggestions;

/**
 * Splits a suggestion into the caret-line part and the following block, and
 * expands leading tabs (the CodeMining API does not render tab characters).
 * Pure JDK: no OSGi/Eclipse dependencies.
 */
public record RenderPlan(String firstLine, String block) {

	/** Returns null for blank input. tabSize below 1 is treated as 1. */
	public static RenderPlan of(String suggestionText, int tabSize) {
		if (suggestionText == null || suggestionText.isEmpty()) {
			return null;
		}
		String normalized = suggestionText.replace("\r\n", "\n").replace('\r', '\n');
		int newline = normalized.indexOf('\n');
		String first = newline < 0 ? normalized : normalized.substring(0, newline);
		String rest = newline < 0 ? null : normalized.substring(newline + 1);
		if (rest != null) {
			rest = expandLeadingTabs(rest, tabSize);
			if (rest.isEmpty()) {
				rest = null;
			}
		}
		return new RenderPlan(expandLeadingTabs(first, tabSize), rest);
	}

	private static String expandLeadingTabs(String text, int tabSize) {
		String spaces = " ".repeat(Math.max(1, tabSize));
		String[] lines = text.split("\n", -1);
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < lines.length; i++) {
			if (i > 0) {
				out.append('\n');
			}
			String line = lines[i];
			int tabs = 0;
			while (tabs < line.length() && line.charAt(tabs) == '\t') {
				tabs++;
			}
			out.append(spaces.repeat(tabs)).append(line.substring(tabs));
		}
		return out.toString();
	}
}
```

- [ ] **Step 4: 緑を確認**

Run: `mvn -q verify 2>&1 | tail -10`
Expected: `Tests run: 44, Failures: 0, Errors: 0`

- [ ] **Step 5: コミット**

```bash
git add bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/RenderPlan.java tests/
git commit -m "feat: Add ghost-text render plan with tab expansion"
```

---

### Task 6: テレメトリ購読設定と送信ヘルパ

**Files:**
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/GitLabLanguageServerConfigurationParams.java`(`Telemetry` recordに`actions`追加)
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/GitLabLanguageServerProvider.java`(actions送信)
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/SuggestionTelemetry.java`
- Test: `tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/lsp/GitLabLanguageServerConfigurationParamsTest.java`(変更)

**Interfaces:**
- Produces:
  - `record Telemetry(boolean enabled, String trackingUrl, List<TelemetryAction> actions)`(既存2引数→3引数に変更)
  - `record TelemetryAction(String action)`(`GitLabLanguageServerConfigurationParams` のネスト)
  - `SuggestionTelemetry.shown(String trackingId, Integer optionIndex)` / `accepted(String trackingId, Integer optionIndex)`(static。LS未起動/trackingId nullなら黙ってスキップ)

- [ ] **Step 1: 既存テストを新形式に書き換えて赤にする**

`GitLabLanguageServerConfigurationParamsTest.java` の `telemetrySetterMustNotTouchFeatureFlags` を以下に置き換え、新テストを追加:

```java
	@Test
	void telemetrySetterMustNotTouchFeatureFlags() {
		var telemetry = new Telemetry(true, "https://snowplow.example",
				List.of(new TelemetryAction("suggestion_shown")));
		var params = GitLabLanguageServerConfigurationParams.builder()
				.telemetry(telemetry)
				.build();
		assertEquals(telemetry, params.telemetry());
		assertNull(params.featureFlags());
	}

	@Test
	void telemetryCarriesSubscribedActions() {
		var telemetry = new Telemetry(true, "https://snowplow.example",
				List.of(new TelemetryAction("suggestion_shown"), new TelemetryAction("suggestion_accepted")));
		assertEquals("suggestion_shown", telemetry.actions().get(0).action());
		assertEquals(2, telemetry.actions().size());
	}
```
import に `java.util.List` と `com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.TelemetryAction` を追加。

- [ ] **Step 2: 赤を確認**

Run: `mvn -q verify 2>&1 | tail -20`
Expected: コンパイルエラー(`Telemetry` は2引数、`TelemetryAction` 不在)で FAIL。

- [ ] **Step 3: 実装**

`GitLabLanguageServerConfigurationParams.java` の
```java
	public static record Telemetry(boolean enabled, String trackingUrl) {}
```
を
```java
	public static record Telemetry(boolean enabled, String trackingUrl, List<TelemetryAction> actions) {}

	public static record TelemetryAction(String action) {}
```
に変更。

`GitLabLanguageServerProvider.java` の `new Telemetry(...)` 呼び出し(`onDidChangeConfiguration` 内)を:
```java
				.telemetry(new Telemetry(
					preferenceStore.getBoolean(PreferenceConstants.TELEMETRY_ENABLED),
					"https://snowplowprd.trx.gitlab.net",
					List.of(new TelemetryAction(TelemetryParams.ACTION_SHOWN),
							new TelemetryAction(TelemetryParams.ACTION_ACCEPTED)))
				);
```
に変更(既存の閉じ括弧構造に合わせる)。import に `com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.TelemetryAction` を追加。

`SuggestionTelemetry.java` を新規作成:
```java
package com.gitlab.eclipse.lsp;

/**
 * Sends code-suggestion telemetry to the language server. Because we subscribe
 * to shown/accepted in the didChangeConfiguration telemetry.actions, the server
 * relies on the client to report these events.
 */
public final class SuggestionTelemetry {
	private SuggestionTelemetry() {}

	public static void shown(String trackingId, Integer optionIndex) {
		send(TelemetryParams.ACTION_SHOWN, trackingId, optionIndex);
	}

	public static void accepted(String trackingId, Integer optionIndex) {
		send(TelemetryParams.ACTION_ACCEPTED, trackingId, optionIndex);
	}

	private static void send(String action, String trackingId, Integer optionIndex) {
		GitLabLanguageServer server = GitLabLanguageServerProvider.languageServer;
		if (server == null || trackingId == null) {
			return;
		}
		server.telemetry(TelemetryParams.codeSuggestion(action, trackingId, optionIndex));
	}
}
```

- [ ] **Step 4: 緑を確認**

Run: `mvn -q verify 2>&1 | tail -10`
Expected: `Tests run: 45, Failures: 0, Errors: 0`

- [ ] **Step 5: コミット**

```bash
git add bundles/ tests/
git commit -m "feat: Subscribe to suggestion telemetry actions and add sender"
```

---

### Task 7: セッションマネージャ・エディタトラッカ・起動配線

**Files:**
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/ui/SuggestionSessions.java`
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/ui/CompletionSessionManager.java`
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/ui/EditorTracker.java`
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/ui/SuggestionStartup.java`
- Modify: `bundles/gitlab-eclipse-plugin/META-INF/MANIFEST.MF`(Require-Bundle追加)
- Modify: `bundles/gitlab-eclipse-plugin/plugin.xml`(startup追加)

**Interfaces:**
- Consumes: T1〜T6 の全部品、`GitLabLanguageServerProvider.languageServer`(public static、既存)、`org.eclipse.lsp4e.LSPEclipseUtils.toUri(IDocument)`
- Produces(T8〜T10が使う):
  - `SuggestionSessions.model(): SuggestionModel` / `streamBuffer(): StreamBuffer` / `active(): CompletionSessionManager` / `setActive(...)`(static)
  - `CompletionSessionManager`: `viewer(): ITextViewer` / `discard()` / `requestNow()` / `refreshMinings()` / `beginEdit()` / `endEdit()` / `showSuggestion(String text, int offset, String trackingId, Integer optionIndex)`(すべてpackage-private+一部public、同パッケージのハンドラ/プロバイダから使用)
  - `EditorTracker.activatePart(IWorkbenchPart)`(public。起動時の既アクティブエディタ用)

このタスクはUI配線のためユニットテストなし(POJOロジックはT3〜T5で担保済み)。`mvn verify` のコンパイル+既存45テストグリーンが合格条件。

- [ ] **Step 1: MANIFEST の Require-Bundle を拡張**

`bundles/gitlab-eclipse-plugin/META-INF/MANIFEST.MF` の
```
 org.eclipse.equinox.security;bundle-version="1.4.400"
```
を
```
 org.eclipse.equinox.security;bundle-version="1.4.400",
 org.eclipse.jface.text;bundle-version="3.25.0",
 org.eclipse.ui.workbench.texteditor;bundle-version="3.17.0",
 org.eclipse.ui.editors;bundle-version="3.17.0"
```
に変更(jface.text=CodeMining/ITextViewer、workbench.texteditor=ITextEditor/codeMiningProviders拡張点、ui.editors=EditorsUI設定ストア)。

- [ ] **Step 2: SuggestionSessions を実装**

```java
package com.gitlab.eclipse.suggestions.ui;

import com.gitlab.eclipse.suggestions.StreamBuffer;
import com.gitlab.eclipse.suggestions.SuggestionModel;

/** Process-wide session state: one suggestion model/stream, one active editor manager. */
public final class SuggestionSessions {
	private static final SuggestionModel MODEL = new SuggestionModel();
	private static final StreamBuffer STREAM = new StreamBuffer();
	private static volatile CompletionSessionManager active;

	private SuggestionSessions() {}

	public static SuggestionModel model() {
		return MODEL;
	}

	public static StreamBuffer streamBuffer() {
		return STREAM;
	}

	public static CompletionSessionManager active() {
		return active;
	}

	static void setActive(CompletionSessionManager manager) {
		active = manager;
	}
}
```

- [ ] **Step 3: CompletionSessionManager を実装**

```java
package com.gitlab.eclipse.suggestions.ui;

import java.net.URI;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.ITextListener;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.TextEvent;
import org.eclipse.jface.text.source.ISourceViewerExtension5;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;

import com.gitlab.eclipse.lsp.CancelStreamingParams;
import com.gitlab.eclipse.lsp.GitLabLanguageServerProvider;
import com.gitlab.eclipse.lsp.InlineCompletionCommand;
import com.gitlab.eclipse.lsp.InlineCompletionContext;
import com.gitlab.eclipse.lsp.InlineCompletionItem;
import com.gitlab.eclipse.lsp.InlineCompletionList;
import com.gitlab.eclipse.lsp.InlineCompletionParams;
import com.gitlab.eclipse.lsp.SuggestionTelemetry;
import com.gitlab.eclipse.suggestions.SuggestionModel;

/**
 * Drives the inline-completion request cycle for one editor: listens for typing,
 * debounces, cancels stale requests, and pushes results into the shared model.
 * All entry points run on the SWT UI thread unless noted.
 */
@SuppressWarnings("restriction") // LSPEclipseUtils: same URI derivation LSP4E uses for didOpen
public final class CompletionSessionManager implements ITextListener, KeyListener, MouseListener {
	private static final int DEBOUNCE_MS = 250;
	private static boolean requestFailureLogged;

	private final ITextViewer viewer;
	private CompletableFuture<InlineCompletionList> inflight;
	private int requestSerial;
	private boolean applyingEdit;
	// set when a streaming response was announced; validated when the stream completes
	private int streamOffset;
	private long streamStamp;

	CompletionSessionManager(ITextViewer viewer) {
		this.viewer = viewer;
	}

	void install() {
		viewer.addTextListener(this);
		StyledText widget = viewer.getTextWidget();
		widget.addKeyListener(this);
		widget.addMouseListener(this);
	}

	void dispose() {
		discard();
		viewer.removeTextListener(this);
		StyledText widget = viewer.getTextWidget();
		if (widget != null && !widget.isDisposed()) {
			widget.removeKeyListener(this);
			widget.removeMouseListener(this);
		}
	}

	public ITextViewer viewer() {
		return viewer;
	}

	/** Handlers set this around programmatic document edits so textChanged ignores them. */
	void beginEdit() {
		applyingEdit = true;
	}

	void endEdit() {
		applyingEdit = false;
	}

	@Override
	public void textChanged(TextEvent event) {
		if (applyingEdit || event.getDocumentEvent() == null) {
			return;
		}
		discard();
		scheduleRequest();
	}

	@Override
	public void keyPressed(KeyEvent e) {
		// nothing: dismissal decisions happen on release, once the caret has moved
	}

	@Override
	public void keyReleased(KeyEvent e) {
		switch (e.keyCode) {
		case SWT.ARROW_LEFT, SWT.ARROW_RIGHT, SWT.ARROW_UP, SWT.ARROW_DOWN, SWT.HOME, SWT.END, SWT.PAGE_UP,
				SWT.PAGE_DOWN -> discard();
		default -> { /* typing is handled via textChanged */ }
		}
	}

	@Override
	public void mouseDown(MouseEvent e) {
		discard();
	}

	@Override
	public void mouseUp(MouseEvent e) {
	}

	@Override
	public void mouseDoubleClick(MouseEvent e) {
	}

	private void scheduleRequest() {
		StyledText widget = viewer.getTextWidget();
		if (widget == null || widget.isDisposed()) {
			return;
		}
		int serial = ++requestSerial;
		widget.getDisplay().timerExec(DEBOUNCE_MS, () -> {
			if (serial == requestSerial && !widget.isDisposed()) {
				request(InlineCompletionContext.TRIGGER_AUTOMATIC);
			}
		});
	}

	/** Manual trigger: skips the debounce. */
	public void requestNow() {
		++requestSerial; // invalidate any pending debounce timer
		request(InlineCompletionContext.TRIGGER_INVOKED);
	}

	private void request(int triggerKind) {
		var server = GitLabLanguageServerProvider.languageServer;
		IDocument document = viewer.getDocument();
		if (server == null || document == null) {
			return;
		}
		cancelInflight();
		int offset = viewer.getSelectedRange().x;
		InlineCompletionParams params;
		try {
			int line = document.getLineOfOffset(offset);
			int character = offset - document.getLineOffset(line);
			URI uri = LSPEclipseUtils.toUri(document);
			if (uri == null) {
				return;
			}
			params = new InlineCompletionParams(new TextDocumentIdentifier(uri.toString()),
					new Position(line, character), new InlineCompletionContext(triggerKind, null));
		} catch (BadLocationException e) {
			return;
		}
		long stamp = modificationStamp(document);
		CompletableFuture<InlineCompletionList> future = server.inlineCompletion(params);
		inflight = future;
		future.whenComplete((result, error) -> onResponse(result, error, offset, stamp));
	}

	// lsp4j executor thread
	private void onResponse(InlineCompletionList result, Throwable error, int offset, long stamp) {
		if (error != null) {
			logOnce(error);
			return;
		}
		if (result == null || result.items() == null || result.items().isEmpty()) {
			return;
		}
		InlineCompletionItem item = result.items().get(0);
		StyledText widget = viewer.getTextWidget();
		if (widget == null || widget.isDisposed()) {
			return;
		}
		widget.getDisplay().asyncExec(() -> {
			if (widget.isDisposed() || isStale(offset, stamp)) {
				return;
			}
			InlineCompletionCommand command = item.command();
			if (command != null && InlineCompletionCommand.START_STREAMING.equals(command.command())) {
				streamOffset = offset;
				streamStamp = stamp;
				SuggestionSessions.streamBuffer().start(command.stringArg(0), command.stringArg(1), null);
				return; // 完成テキストは streamingCompletionResponse 通知で届く(Task 10)
			}
			String text = item.insertText();
			if (text == null || text.isEmpty()) {
				return;
			}
			String trackingId = command == null ? null : command.stringArg(0);
			Integer optionIndex = command == null ? null : command.intArg(1);
			showSuggestion(text, offset, trackingId, optionIndex);
		});
	}

	// UI thread
	void showSuggestion(String text, int offset, String trackingId, Integer optionIndex) {
		SuggestionSessions.model().show(new SuggestionModel.Suggestion(text, offset, trackingId, optionIndex));
		SuggestionTelemetry.shown(trackingId, optionIndex);
		refreshMinings();
	}

	/** Clears ghost text and aborts in-flight work (request, stream, pending timer). */
	public void discard() {
		++requestSerial;
		cancelInflight();
		String streamId = SuggestionSessions.streamBuffer().cancel();
		var server = GitLabLanguageServerProvider.languageServer;
		if (streamId != null && server != null) {
			server.cancelStreaming(new CancelStreamingParams(streamId));
		}
		if (SuggestionSessions.model().isShowing()) {
			SuggestionSessions.model().clear();
			refreshMinings();
		}
	}

	void refreshMinings() {
		StyledText widget = viewer.getTextWidget();
		if (widget == null || widget.isDisposed()) {
			return;
		}
		// async: a synchronous call can deadlock against LSP4E's document locks
		widget.getDisplay().asyncExec(() -> {
			if (!widget.isDisposed() && viewer instanceof ISourceViewerExtension5 minings) {
				minings.updateCodeMinings();
			}
		});
	}

	boolean isStale(int offset, long stamp) {
		IDocument document = viewer.getDocument();
		return document == null || modificationStamp(document) != stamp || viewer.getSelectedRange().x != offset;
	}

	private void cancelInflight() {
		if (inflight != null) {
			inflight.cancel(true);
			inflight = null;
		}
	}

	private static long modificationStamp(IDocument document) {
		return document instanceof IDocumentExtension4 extended ? extended.getModificationStamp() : -1;
	}

	private static synchronized void logOnce(Throwable error) {
		Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
		if (cause instanceof CancellationException) {
			return; // 正常系: 自分でキャンセルした
		}
		if (requestFailureLogged) {
			return;
		}
		requestFailureLogged = true;
		ILog log = Platform.getLog(CompletionSessionManager.class);
		log.warn("Inline completion request failed (further failures will not be logged)", cause);
	}
}
```

- [ ] **Step 4: EditorTracker を実装**

```java
package com.gitlab.eclipse.suggestions.ui;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.texteditor.ITextEditor;

import com.gitlab.eclipse.lsp.GitLabLanguageServerProvider;

/** Attaches a {@link CompletionSessionManager} to whichever text editor is active. */
@SuppressWarnings("restriction")
public final class EditorTracker implements IPartListener2 {
	private final Map<ITextEditor, CompletionSessionManager> managers = new HashMap<>();

	@Override
	public void partActivated(IWorkbenchPartReference partRef) {
		activatePart(partRef.getPart(false));
	}

	@Override
	public void partBroughtToTop(IWorkbenchPartReference partRef) {
		activatePart(partRef.getPart(false));
	}

	@Override
	public void partClosed(IWorkbenchPartReference partRef) {
		IWorkbenchPart part = partRef.getPart(false);
		ITextEditor editor = Adapters.adapt(part, ITextEditor.class);
		if (editor == null) {
			return;
		}
		CompletionSessionManager manager = managers.remove(editor);
		if (manager != null) {
			if (SuggestionSessions.active() == manager) {
				SuggestionSessions.setActive(null);
			}
			manager.dispose();
		}
	}

	public void activatePart(IWorkbenchPart part) {
		ITextEditor editor = Adapters.adapt(part, ITextEditor.class);
		if (editor == null) {
			return;
		}
		ITextViewer viewer = editor.getAdapter(ITextViewer.class);
		if (viewer == null || viewer.getTextWidget() == null) {
			return;
		}
		CompletionSessionManager manager = managers.computeIfAbsent(editor, e -> {
			CompletionSessionManager created = new CompletionSessionManager(viewer);
			created.install();
			return created;
		});
		CompletionSessionManager previous = SuggestionSessions.active();
		if (previous != null && previous != manager) {
			previous.discard();
		}
		SuggestionSessions.setActive(manager);
		notifyActiveDocument(viewer);
	}

	private static void notifyActiveDocument(ITextViewer viewer) {
		var server = GitLabLanguageServerProvider.languageServer;
		IDocument document = viewer.getDocument();
		if (server == null || document == null) {
			return;
		}
		URI uri = LSPEclipseUtils.toUri(document);
		if (uri != null) {
			server.didChangeDocumentInActiveEditor(uri.toString());
		}
	}
}
```

- [ ] **Step 5: SuggestionStartup を実装し plugin.xml に登録**

```java
package com.gitlab.eclipse.suggestions.ui;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;

/** Registers the editor tracker once the workbench UI is up. */
public class SuggestionStartup implements IStartup {

	@Override
	public void earlyStartup() {
		Display.getDefault().asyncExec(() -> {
			IWorkbench workbench = PlatformUI.getWorkbench();
			EditorTracker tracker = new EditorTracker();
			for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
				window.getPartService().addPartListener(tracker);
			}
			workbench.addWindowListener(new IWindowListener() {
				@Override
				public void windowOpened(IWorkbenchWindow window) {
					window.getPartService().addPartListener(tracker);
				}

				@Override
				public void windowClosed(IWorkbenchWindow window) {
				}

				@Override
				public void windowActivated(IWorkbenchWindow window) {
				}

				@Override
				public void windowDeactivated(IWorkbenchWindow window) {
				}
			});
			IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
			if (window != null && window.getActivePage() != null
					&& window.getActivePage().getActiveEditor() != null) {
				tracker.activatePart(window.getActivePage().getActiveEditor());
			}
		});
	}
}
```

`plugin.xml` の既存 startup 拡張:
```xml
	<extension point="org.eclipse.ui.startup">
		<startup class="com.gitlab.eclipse.lsp.install.LanguageServerStartup"/>
	</extension>
```
を
```xml
	<extension point="org.eclipse.ui.startup">
		<startup class="com.gitlab.eclipse.lsp.install.LanguageServerStartup"/>
		<startup class="com.gitlab.eclipse.suggestions.ui.SuggestionStartup"/>
	</extension>
```
に変更。

- [ ] **Step 6: 緑を確認しコミット**

Run: `mvn -q verify 2>&1 | tail -10`
Expected: `Tests run: 45, Failures: 0, Errors: 0`(新規テストなし、コンパイル成功が合格条件)

```bash
git add bundles/
git commit -m "feat: Add completion session manager and editor tracking"
```

---

### Task 8: CodeMining描画層

**Files:**
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/ui/InlineGhostTextMining.java`
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/ui/EolGhostTextMining.java`
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/ui/BlockGhostTextMining.java`
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/ui/GhostTextCodeMiningProvider.java`
- Modify: `bundles/gitlab-eclipse-plugin/plugin.xml`(codeMiningProviders登録)

**Interfaces:**
- Consumes: `SuggestionSessions.model()/active()`(T7)、`RenderPlan.of(text, tabSize)`(T5)
- Produces: CodeMining拡張(Platformが消費。他タスクからの直接参照なし)

- [ ] **Step 1: マイニング3クラスを実装**

`InlineGhostTextMining.java`(カーソル行・行中用):
```java
package com.gitlab.eclipse.suggestions.ui;

import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.codemining.ICodeMiningProvider;
import org.eclipse.jface.text.codemining.LineContentCodeMining;

/** Grey inline text drawn at the caret. Position length must be >= 1 (platform quirk). */
class InlineGhostTextMining extends LineContentCodeMining {
	InlineGhostTextMining(Position position, ICodeMiningProvider provider, String label) {
		super(position, provider);
		setLabel(label);
	}

	@Override
	public boolean isAfterPosition() {
		return true;
	}
}
```

`EolGhostTextMining.java`(カーソル行・行末用):
```java
package com.gitlab.eclipse.suggestions.ui;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.codemining.ICodeMiningProvider;
import org.eclipse.jface.text.codemining.LineEndCodeMining;

/** Grey text appended at the end of the caret line. */
class EolGhostTextMining extends LineEndCodeMining {
	EolGhostTextMining(IDocument document, int line, ICodeMiningProvider provider, String label)
			throws BadLocationException {
		super(document, line, provider);
		setLabel(label);
	}

	@Override
	public boolean isAfterPosition() {
		return true;
	}
}
```

`BlockGhostTextMining.java`(2行目以降のブロック用):
```java
package com.gitlab.eclipse.suggestions.ui;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.codemining.ICodeMiningProvider;
import org.eclipse.jface.text.codemining.LineHeaderCodeMining;

/** Multi-line grey block rendered above the line after the caret. Label may contain newlines. */
class BlockGhostTextMining extends LineHeaderCodeMining {
	BlockGhostTextMining(int beforeLineNumber, IDocument document, ICodeMiningProvider provider, String label)
			throws BadLocationException {
		super(beforeLineNumber, document, provider);
		setLabel(label);
	}
}
```

- [ ] **Step 2: GhostTextCodeMiningProvider を実装**

```java
package com.gitlab.eclipse.suggestions.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.codemining.AbstractCodeMiningProvider;
import org.eclipse.jface.text.codemining.ICodeMining;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants;

import com.gitlab.eclipse.suggestions.RenderPlan;
import com.gitlab.eclipse.suggestions.SuggestionModel;

/**
 * Thin rendering layer: converts the shared suggestion model into code minings.
 * All state lives in {@link SuggestionSessions}; this class only draws it.
 */
public class GhostTextCodeMiningProvider extends AbstractCodeMiningProvider {

	@Override
	public CompletableFuture<List<? extends ICodeMining>> provideCodeMinings(ITextViewer viewer,
			IProgressMonitor monitor) {
		return CompletableFuture.completedFuture(computeMinings(viewer));
	}

	private List<? extends ICodeMining> computeMinings(ITextViewer viewer) {
		CompletionSessionManager active = SuggestionSessions.active();
		if (active == null || active.viewer() != viewer) {
			return List.of();
		}
		SuggestionModel model = SuggestionSessions.model();
		String remaining = model.remainingText();
		if (remaining == null || remaining.isEmpty()) {
			return List.of();
		}
		IDocument document = viewer.getDocument();
		if (document == null) {
			return List.of();
		}
		RenderPlan plan = RenderPlan.of(remaining, tabSize());
		if (plan == null) {
			return List.of();
		}
		int offset = model.insertionOffset();
		List<ICodeMining> minings = new ArrayList<>(2);
		try {
			int line = document.getLineOfOffset(offset);
			IRegion lineInfo = document.getLineInformation(line);
			int lineEnd = lineInfo.getOffset() + lineInfo.getLength();
			if (!plan.firstLine().isEmpty()) {
				if (offset >= lineEnd) {
					minings.add(new EolGhostTextMining(document, line, this, plan.firstLine()));
				} else {
					minings.add(new InlineGhostTextMining(new Position(offset, 1), this, plan.firstLine()));
				}
			}
			// 既知の制約: 最終行では次行が無いためブロックを表示できない
			if (plan.block() != null && line + 1 < document.getNumberOfLines()) {
				minings.add(new BlockGhostTextMining(line + 1, document, this, plan.block()));
			}
		} catch (BadLocationException e) {
			return List.of();
		}
		return minings;
	}

	private static int tabSize() {
		int size = EditorsUI.getPreferenceStore()
				.getInt(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_TAB_WIDTH);
		return size > 0 ? size : 4;
	}
}
```

- [ ] **Step 3: plugin.xml に codeMiningProviders を登録**

`plugin.xml` の `</plugin>` 直前に追加:
```xml
	<extension point="org.eclipse.ui.workbench.texteditor.codeMiningProviders">
		<codeMiningProvider
			class="com.gitlab.eclipse.suggestions.ui.GhostTextCodeMiningProvider"
			id="com.gitlab.eclipse.suggestions.ghostTextProvider"
			label="GitLab Duo Code Suggestions">
		</codeMiningProvider>
	</extension>
```

- [ ] **Step 4: 緑を確認しコミット**

Run: `mvn -q verify 2>&1 | tail -10`
Expected: `Tests run: 45, Failures: 0, Errors: 0`

```bash
git add bundles/
git commit -m "feat: Render ghost text via code minings"
```

---

### Task 9: コマンド・ハンドラ・キーバインド・コンテキスト

**Files:**
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/ui/AcceptSuggestionHandler.java`
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/ui/AcceptNextWordHandler.java`
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/ui/DismissSuggestionHandler.java`
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/ui/TriggerSuggestionHandler.java`
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/ui/CompletionSessionManager.java`(コンテキスト活性化)
- Modify: `bundles/gitlab-eclipse-plugin/plugin.xml`(commands/handlers/contexts/bindings)

**Interfaces:**
- Consumes: `SuggestionSessions` / `CompletionSessionManager.beginEdit()/endEdit()/discard()/requestNow()/refreshMinings()`(T7)、`SuggestionModel.nextChunk()/advance()/remainingText()/insertionOffset()/current()`(T3)、`SuggestionTelemetry.accepted(...)`(T6)
- Produces: コマンドID `com.gitlab.eclipse.commands.acceptSuggestion` / `.acceptSuggestionWord` / `.dismissSuggestion` / `.triggerSuggestion`、コンテキストID `com.gitlab.eclipse.suggestionVisible`

- [ ] **Step 1: plugin.xml にコマンド・ハンドラ・コンテキスト・キーバインドを追加**

`</plugin>` 直前に追加:
```xml
	<extension point="org.eclipse.ui.commands">
		<category id="com.gitlab.eclipse.commands.category" name="GitLab Duo"/>
		<command id="com.gitlab.eclipse.commands.acceptSuggestion"
			categoryId="com.gitlab.eclipse.commands.category" name="Accept Code Suggestion"/>
		<command id="com.gitlab.eclipse.commands.acceptSuggestionWord"
			categoryId="com.gitlab.eclipse.commands.category" name="Accept Next Word of Code Suggestion"/>
		<command id="com.gitlab.eclipse.commands.dismissSuggestion"
			categoryId="com.gitlab.eclipse.commands.category" name="Dismiss Code Suggestion"/>
		<command id="com.gitlab.eclipse.commands.triggerSuggestion"
			categoryId="com.gitlab.eclipse.commands.category" name="Trigger Code Suggestion"/>
	</extension>

	<extension point="org.eclipse.ui.handlers">
		<handler commandId="com.gitlab.eclipse.commands.acceptSuggestion"
			class="com.gitlab.eclipse.suggestions.ui.AcceptSuggestionHandler"/>
		<handler commandId="com.gitlab.eclipse.commands.acceptSuggestionWord"
			class="com.gitlab.eclipse.suggestions.ui.AcceptNextWordHandler"/>
		<handler commandId="com.gitlab.eclipse.commands.dismissSuggestion"
			class="com.gitlab.eclipse.suggestions.ui.DismissSuggestionHandler"/>
		<handler commandId="com.gitlab.eclipse.commands.triggerSuggestion"
			class="com.gitlab.eclipse.suggestions.ui.TriggerSuggestionHandler"/>
	</extension>

	<extension point="org.eclipse.ui.contexts">
		<context id="com.gitlab.eclipse.suggestionVisible"
			name="GitLab Code Suggestion Visible"
			description="Active while an inline code suggestion is showing"
			parentId="org.eclipse.ui.textEditorScope"/>
	</extension>

	<!-- TAB/ESCはtextEditorScopeに直接バインドし、ハンドラのisEnabled()で候補が無いとき
	     バインドを無効化して通常編集へ素通しする(Copilot for Eclipse実証済みパターン)。
	     独自コンテキストはM1+ARROW_RIGHT(単語受け入れ)のみに使う。 -->
	<extension point="org.eclipse.ui.bindings">
		<key commandId="com.gitlab.eclipse.commands.acceptSuggestion"
			contextId="org.eclipse.ui.textEditorScope"
			schemeId="org.eclipse.ui.defaultAcceleratorConfiguration"
			sequence="TAB"/>
		<key commandId="com.gitlab.eclipse.commands.dismissSuggestion"
			contextId="org.eclipse.ui.textEditorScope"
			schemeId="org.eclipse.ui.defaultAcceleratorConfiguration"
			sequence="ESC"/>
		<key commandId="com.gitlab.eclipse.commands.acceptSuggestionWord"
			contextId="com.gitlab.eclipse.suggestionVisible"
			schemeId="org.eclipse.ui.defaultAcceleratorConfiguration"
			sequence="M1+ARROW_RIGHT"/>
		<key commandId="com.gitlab.eclipse.commands.triggerSuggestion"
			contextId="org.eclipse.ui.textEditorScope"
			schemeId="org.eclipse.ui.defaultAcceleratorConfiguration"
			sequence="M1+M3+/"/>
	</extension>
```

- [ ] **Step 2: マネージャにコンテキスト活性化を追加**

`CompletionSessionManager.java` に static フィールドとメソッドを追加:
```java
	private static org.eclipse.ui.contexts.IContextActivation suggestionContext;

	private static void activateSuggestionContext() {
		if (suggestionContext != null) {
			return;
		}
		var service = org.eclipse.ui.PlatformUI.getWorkbench()
				.getService(org.eclipse.ui.contexts.IContextService.class);
		if (service != null) {
			suggestionContext = service.activateContext("com.gitlab.eclipse.suggestionVisible");
		}
	}

	private static void deactivateSuggestionContext() {
		if (suggestionContext == null) {
			return;
		}
		var service = org.eclipse.ui.PlatformUI.getWorkbench()
				.getService(org.eclipse.ui.contexts.IContextService.class);
		if (service != null) {
			service.deactivateContext(suggestionContext);
		}
		suggestionContext = null;
	}
```
(import文に整理して構わない)。`showSuggestion(...)` の `refreshMinings();` の直前に `activateSuggestionContext();` を、`discard()` の `if (SuggestionSessions.model().isShowing())` ブロックの直前に `deactivateSuggestionContext();` を追加。

- [ ] **Step 3: ハンドラ4クラスを実装**

`AcceptSuggestionHandler.java`:
```java
package com.gitlab.eclipse.suggestions.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;

import com.gitlab.eclipse.lsp.SuggestionTelemetry;
import com.gitlab.eclipse.suggestions.SuggestionModel;

/** TAB: inserts the whole remaining suggestion. */
public class AcceptSuggestionHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		CompletionSessionManager manager = SuggestionSessions.active();
		SuggestionModel model = SuggestionSessions.model();
		SuggestionModel.Suggestion suggestion = model.current();
		if (manager == null || suggestion == null) {
			return null;
		}
		String remaining = model.remainingText();
		int offset = model.insertionOffset();
		IDocument document = manager.viewer().getDocument();
		if (document == null) {
			return null;
		}
		try {
			manager.beginEdit();
			document.replace(offset, 0, remaining);
			manager.viewer().setSelectedRange(offset + remaining.length(), 0);
		} catch (BadLocationException e) {
			throw new ExecutionException("Failed to insert code suggestion", e);
		} finally {
			manager.endEdit();
		}
		model.clear();
		SuggestionTelemetry.accepted(suggestion.trackingId(), suggestion.optionIndex());
		manager.refreshMinings();
		return null;
	}

	@Override
	public boolean isEnabled() {
		return SuggestionSessions.model().isShowing() && SuggestionSessions.active() != null;
	}
}
```

`AcceptNextWordHandler.java`:
```java
package com.gitlab.eclipse.suggestions.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;

import com.gitlab.eclipse.lsp.SuggestionTelemetry;
import com.gitlab.eclipse.suggestions.SuggestionModel;

/** Ctrl/Cmd+Right: inserts the next word-sized chunk of the suggestion. */
public class AcceptNextWordHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		CompletionSessionManager manager = SuggestionSessions.active();
		SuggestionModel model = SuggestionSessions.model();
		SuggestionModel.Suggestion suggestion = model.current();
		String chunk = model.nextChunk();
		if (manager == null || suggestion == null || chunk == null) {
			return null;
		}
		int offset = model.insertionOffset();
		IDocument document = manager.viewer().getDocument();
		if (document == null) {
			return null;
		}
		try {
			manager.beginEdit();
			document.replace(offset, 0, chunk);
			manager.viewer().setSelectedRange(offset + chunk.length(), 0);
		} catch (BadLocationException e) {
			throw new ExecutionException("Failed to insert code suggestion word", e);
		} finally {
			manager.endEdit();
		}
		boolean fullyConsumed = model.advance(chunk.length());
		if (fullyConsumed) {
			SuggestionTelemetry.accepted(suggestion.trackingId(), suggestion.optionIndex());
		}
		manager.refreshMinings();
		return null;
	}

	@Override
	public boolean isEnabled() {
		return SuggestionSessions.model().isShowing() && SuggestionSessions.active() != null;
	}
}
```

`DismissSuggestionHandler.java`:
```java
package com.gitlab.eclipse.suggestions.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

/** ESC: discards the current suggestion (and cancels an active stream). */
public class DismissSuggestionHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) {
		CompletionSessionManager manager = SuggestionSessions.active();
		if (manager != null) {
			manager.discard();
		}
		return null;
	}

	@Override
	public boolean isEnabled() {
		return SuggestionSessions.active() != null
				&& (SuggestionSessions.model().isShowing() || SuggestionSessions.streamBuffer().isActive());
	}
}
```

`TriggerSuggestionHandler.java`:
```java
package com.gitlab.eclipse.suggestions.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

/** Ctrl/Cmd+Alt+/: requests a suggestion immediately, skipping the debounce. */
public class TriggerSuggestionHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) {
		CompletionSessionManager manager = SuggestionSessions.active();
		if (manager != null) {
			manager.requestNow();
		}
		return null;
	}

	@Override
	public boolean isEnabled() {
		return SuggestionSessions.active() != null;
	}
}
```

- [ ] **Step 4: 緑を確認しコミット**

Run: `mvn -q verify 2>&1 | tail -10`
Expected: `Tests run: 45, Failures: 0, Errors: 0`

```bash
git add bundles/
git commit -m "feat: Add accept/dismiss/trigger commands and key bindings"
```

---

### Task 10: ストリーミング経路の配線

**Files:**
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/ui/CompletionSessionManager.java`(ストリーム完了処理)
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/suggestions/ui/SuggestionStartup.java`(購読登録)

**Interfaces:**
- Consumes: `StreamingCompletionEvents.subscribe`(T2)、`StreamBuffer.onNotification`(T4)、`CompletionSessionManager.showSuggestion/streamOffset/streamStamp/isStale`(T7)
- Produces: `CompletionSessionManager.onStreamingNotification(StreamingCompletionResponse)`(package-private)

- [ ] **Step 1: マネージャにストリーム完了処理を追加**

`CompletionSessionManager.java` にメソッド追加(import: `com.gitlab.eclipse.lsp.StreamingCompletionResponse`, `com.gitlab.eclipse.suggestions.StreamBuffer`):
```java
	// 任意スレッド(lsp4j通知スレッド)から呼ばれる
	void onStreamingNotification(StreamingCompletionResponse response) {
		StreamBuffer.Completed completed = SuggestionSessions.streamBuffer().onNotification(response);
		if (completed == null || completed.text().isEmpty()) {
			return;
		}
		StyledText widget = viewer.getTextWidget();
		if (widget == null || widget.isDisposed()) {
			return;
		}
		int offset = streamOffset;
		long stamp = streamStamp;
		widget.getDisplay().asyncExec(() -> {
			if (widget.isDisposed() || isStale(offset, stamp)) {
				return;
			}
			showSuggestion(completed.text(), offset, completed.trackingId(), completed.optionIndex());
		});
	}
```

- [ ] **Step 2: SuggestionStartup で購読を登録**

`SuggestionStartup.earlyStartup()` の `asyncExec` ブロック先頭(`EditorTracker tracker = ...` の前)に追加:
```java
			com.gitlab.eclipse.suggestions.StreamingCompletionEvents.subscribe(response -> {
				CompletionSessionManager manager = SuggestionSessions.active();
				if (manager != null) {
					manager.onStreamingNotification(response);
				}
			});
```
(import文に整理して構わない。ワークベンチ寿命と同じなのでunsubscribe不要。)

- [ ] **Step 3: 緑を確認しコミット**

Run: `mvn -q verify 2>&1 | tail -10`
Expected: `Tests run: 45, Failures: 0, Errors: 0`

```bash
git add bundles/
git commit -m "feat: Wire streaming code generations into the suggestion session"
```

---

### Task 11: 手動検証手順書と最終確認

**Files:**
- Create: `docs/manual-tests/2026-07-18-step2-code-suggestions.md`

**Interfaces:** なし(ドキュメントのみ)

- [ ] **Step 1: 手順書を書く**

`docs/manual-tests/2026-07-18-step2-code-suggestions.md`:
```markdown
# 手動検証: ステップ2 Code Suggestions

devcontainerはheadlessのため、以下はGUIのあるEclipse実機で実施する。

## 準備
1. Eclipse 2025-06 (4.36) + JDK 21。
2. リポジトリルートで `mvn -q verify` 後、`bundles/gitlab-eclipse-plugin/target/gitlab-eclipse-plugin-0.1.0-SNAPSHOT.jar` を Eclipse の `dropins/` へコピーして再起動(`-clean` 推奨)。
3. Preferences > GitLab で接続URLとPAT(Duo有効なアカウント)を設定。
4. 初回はLSバイナリDL(約300MB)がProgressビューに出るので完了を待つ。

## 検証項目
| # | 操作 | 期待結果 |
|---|---|---|
| 1 | .javaファイルを開き、メソッド本体内で `int sum = ` まで入力して手を止める | 250ms+LS応答後、グレーのghost textが表示される |
| 2 | ghost text表示中に TAB | 全文が挿入されghost text消滅。カーソルは挿入末尾 |
| 3 | ghost text表示中に Ctrl+→(macはCmd+→) | 1単語だけ挿入され、残りがghost textのまま |
| 4 | ghost text表示中に ESC | ghost textが消える。ドキュメント無変更 |
| 5 | ghost textなしで TAB | 通常のインデント挿入(素通し) |
| 6 | Ctrl+Alt+/(macはCmd+Alt+/) | 待たずに即補完リクエスト |
| 7 | `// 1からnまでの合計を返す関数` と書いて改行し手を止める | (生成系判定時)ストリーミング完了後に複数行ghost text一括表示。1行目インライン+2行目以降ブロック |
| 8 | 複数行ghost text表示中に TAB | 複数行全体が挿入される |
| 9 | ghost text表示中にタイピング継続 | 旧候補が消え、停止後に新候補 |
| 10 | ghost text表示中にマウスで別位置クリック | 候補が消える |
| 11 | エディタを切り替える | 候補が消え、新エディタ側で補完が動く |
| 12 | (管理者)GitLab側のDuo利用ログ | suggestion_shown / suggestion_accepted が記録される |

## 既知の制約(仕様)
- ファイル最終行では2行目以降のブロックghost textが表示されない(CodeMining APIの制約)
- ghost text表示中の1文字タイプは候補を消す(typed-prefix消費は未実装、スコープ外)
- 候補内タブは先頭のみスペース表示(行中タブは幅ゼロ描画になり得る)
- win-arm64非対応(ステップ1からの既知の制限)

## 問題発生時
- Error Logビュー(Window > Show View)で `gitlab-eclipse-plugin` のエントリを確認
- LSログ: Preferences > GitLab の Language Server Log Level を debug に
```

- [ ] **Step 2: 全体グリーンを最終確認**

Run: `mvn -q verify 2>&1 | tail -10`
Expected: `Tests run: 45, Failures: 0, Errors: 0`

- [ ] **Step 3: コミット**

```bash
git add docs/manual-tests/
git commit -m "docs: Add step-2 manual verification guide"
```

---

## 完了条件(ブランチ全体)

1. `mvn -q verify` で 45/45 グリーン(21既存+24新規)
2. 全11タスクがタスク単位でコミット済み
3. 最終ブランチ全体レビュー(subagent-driven-development の手順)を通過
4. MR作成 → ユーザーレビュー → 実機手動検証(Task 11の手順書)
