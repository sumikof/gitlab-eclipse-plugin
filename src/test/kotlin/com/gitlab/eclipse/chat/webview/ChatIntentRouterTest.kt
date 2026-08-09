package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.lsp.NewPromptRequest
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly

private const val CLASSIC = ChatWebviewCatalog.CLASSIC_WEBVIEW_ID
private const val AGENTIC = ChatWebviewCatalog.AGENTIC_WEBVIEW_ID

/** A webview id that is neither chat surface, to keep either gate from being read as "any id". */
private const val UNRELATED = "root-mcp"

private const val HISTORY = "history"

private val USER_PROMPT = NewPromptRequest(prompt = "explain this")

/**
 * Spelled out rather than built from [ChatIntentRouter.FOCUS_CHAT_PROMPT], which would compare the
 * constant with itself and stay green when its value changes. This is a wire value the classic
 * webview on the other side expects, so the literal is the assertion.
 *
 * The `CLASSIC` / `AGENTIC` ids above are deliberately NOT spelled out: there the property is
 * "matches whatever the catalog advertises", so the catalog constant is what the test should follow.
 */
private val FOCUS_CHAT = NewPromptRequest(prompt = "focusChat")

/** Every intent pending at once, so a test names only the one thing it is about. */
private val ALL = PendingChatIntents(
  focusRequested = true,
  classicPrompt = USER_PROMPT,
  agenticView = HISTORY,
)

class ChatIntentRouterTest : DescribeSpec({

  // A10 (design §21). Every test in this block is keep-behaviour: it encodes what
  // LanguageServerBrowserView.flushPendingIntents did inline before the agentic intent existed.
  // "Encodes", not "was run against": ChatIntentRouter did not exist on the far side of that
  // change, so these tests cannot ever have been executed against the old code. That the encoding
  // is faithful was established by reading the old call site, and nothing here re-checks it.
  describe("classic intents (keep-behaviour, A10)") {

    it("keep-behaviour: turns a focus request into a focusChat prompt when classic is shown") {
      ChatIntentRouter.route(CLASSIC, PendingChatIntents(focusRequested = true)) shouldContainExactly
        listOf(ChatIntentAction.SendClassicPrompt(FOCUS_CHAT))
    }

    it("keep-behaviour: passes the queued classic prompt through unchanged when classic is shown") {
      ChatIntentRouter.route(CLASSIC, PendingChatIntents(classicPrompt = USER_PROMPT)) shouldContainExactly
        listOf(ChatIntentAction.SendClassicPrompt(USER_PROMPT))
    }

    it("keep-behaviour: orders the focusChat prompt before the queued one when both are pending") {
      ChatIntentRouter.route(
        CLASSIC,
        PendingChatIntents(focusRequested = true, classicPrompt = USER_PROMPT),
      ) shouldContainExactly listOf(
        ChatIntentAction.SendClassicPrompt(FOCUS_CHAT),
        ChatIntentAction.SendClassicPrompt(USER_PROMPT),
      )
    }

    it("keep-behaviour: sends nothing to classic when classic is shown with no classic intent") {
      ChatIntentRouter.route(CLASSIC, PendingChatIntents(agenticView = HISTORY))
        .filterIsInstance<ChatIntentAction.SendClassicPrompt>()
        .shouldBeEmpty()
    }

    it("keep-behaviour: drops both classic intents when the agentic webview is the one shown") {
      ChatIntentRouter.route(
        AGENTIC,
        PendingChatIntents(focusRequested = true, classicPrompt = USER_PROMPT),
      ).shouldBeEmpty()
    }

    it("keep-behaviour: drops both classic intents when no webview was shown") {
      ChatIntentRouter.route(
        null,
        PendingChatIntents(focusRequested = true, classicPrompt = USER_PROMPT),
      ).shouldBeEmpty()
    }
  }

  describe("agentic intent") {

    it("hands the pending view to the agentic client when the agentic webview is shown") {
      ChatIntentRouter.route(AGENTIC, PendingChatIntents(agenticView = HISTORY)) shouldContainExactly
        listOf(ChatIntentAction.SwitchAgenticView(HISTORY))
    }

    it("drops the pending view when the classic webview is the one shown") {
      ChatIntentRouter.route(CLASSIC, PendingChatIntents(agenticView = HISTORY))
        .filterIsInstance<ChatIntentAction.SwitchAgenticView>()
        .shouldBeEmpty()
    }

    it("switches nothing when the agentic webview is shown with no view pending") {
      ChatIntentRouter.route(
        AGENTIC,
        PendingChatIntents(focusRequested = true, classicPrompt = USER_PROMPT),
      ).shouldBeEmpty()
    }

    it("drops the pending view when no webview was shown") {
      ChatIntentRouter.route(null, PendingChatIntents(agenticView = HISTORY)).shouldBeEmpty()
    }
  }

  describe("a webview that is neither chat surface") {

    it("routes nothing at all, however many intents are pending") {
      ChatIntentRouter.route(UNRELATED, ALL).shouldBeEmpty()
    }
  }
})
