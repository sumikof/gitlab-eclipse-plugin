package com.gitlab.eclipse.lsp.plugins

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.services.InsertCodeSnippetService
import com.gitlab.eclipse.chat.webview.AgenticChatWebViewClient
import com.gitlab.eclipse.chat.webview.AgenticChatWebViewController
import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewClient
import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewController
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageType
import com.gitlab.eclipse.utils.PlatformUtils
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk

class PluginRegistryChatRouteTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val sharedNotifications = listOf(
    "showMessage",
    "appReady",
    "insertCodeSnippet",
    "focusChange",
    "openLink",
    "openUrl",
    "copyCodeSnippet",
    "copyMessage"
  )

  // Lazy: PluginRegistry creates its logger eagerly, so it must be constructed after
  // LoggingKotestExtension has mocked Platform.getLog (i.e. inside a test, not at spec init).
  val registry by lazy {
    val classicController = GitLabDuoChatWebViewController(
      mockk<PlatformUtils>(),
      mockk<CurrentFileContextProvider>(),
      mockk<GitLabDuoChatWebViewClient>(),
      mockk<InsertCodeSnippetService>()
    )

    val agenticController = AgenticChatWebViewController(
      mockk<PlatformUtils>(),
      mockk<CurrentFileContextProvider>(),
      mockk<InsertCodeSnippetService>(),
      mockk<AgenticChatWebViewClient>()
    ) { it.run() }

    PluginRegistry(listOf(classicController, agenticController))
  }

  describe("agentic-duo-chat routes") {
    it("resolves the getCurrentFileContext request") {
      val route = PluginMessageRoute("agentic-duo-chat", PluginMessageType.REQUEST, "getCurrentFileContext")

      registry[route] shouldNotBe null
    }

    sharedNotifications.forEach { method ->
      it("resolves the $method notification") {
        val route = PluginMessageRoute("agentic-duo-chat", PluginMessageType.NOTIFICATION, method)

        registry[route] shouldNotBe null
      }
    }
  }

  describe("agentic-duo-chat appReady") {
    // Its only parameter is the connection the notification came from, which is not a payload. Were
    // it registered as one, every appReady would arrive with a null payload, fail the payload/type
    // match and be dropped with a warning — agentic readiness would stop working with no exception
    // and nothing in the error log.
    it("registers with no payload type") {
      val route = PluginMessageRoute("agentic-duo-chat", PluginMessageType.NOTIFICATION, "appReady")

      registry[route]?.type shouldBe null
    }
  }

  describe("duo-chat-v2 routes") {
    it("resolves the getCurrentFileContext request") {
      val route = PluginMessageRoute("duo-chat-v2", PluginMessageType.REQUEST, "getCurrentFileContext")

      registry[route] shouldNotBe null
    }

    sharedNotifications.forEach { method ->
      it("resolves the $method notification") {
        val route = PluginMessageRoute("duo-chat-v2", PluginMessageType.NOTIFICATION, method)

        registry[route] shouldNotBe null
      }
    }
  }
})
