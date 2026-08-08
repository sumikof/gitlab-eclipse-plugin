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
private val FOCUS_CHAT = NewPromptRequest(prompt = ChatIntentRouter.FOCUS_CHAT_PROMPT)

/** Every intent pending at once, so a test names only the one thing it is about. */
private val ALL = PendingChatIntents(
  focusRequested = true,
  classicPrompt = USER_PROMPT,
  agenticView = HISTORY,
)

class ChatIntentRouterTest : DescribeSpec({

  // A10 (design §21). Every test in this block is keep-behaviour: it pins what
  // LanguageServerBrowserView.flushPendingIntents already did before the agentic intent existed,
  // and is green on both sides of that change.
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
