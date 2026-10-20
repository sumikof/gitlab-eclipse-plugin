package com.gitlab.eclipse.lsp.plugins

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.services.InsertCodeSnippetService
import com.gitlab.eclipse.chat.webview.AgenticChatWebViewClient
import com.gitlab.eclipse.chat.webview.AgenticChatWebViewController
import com.gitlab.eclipse.chat.webview.DuoAgentPlatformWebViewController
import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewClient
import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewController
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageType
import com.gitlab.eclipse.preferences.storage.CapturedLog
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformLayout
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformLayoutStore
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformReadiness
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk

private const val PLUGIN_ID = AgentPlatformLayout.NEW_WEBVIEW_ID

/**
 * Design §3.3 / §3.4: the six notifications the language server forwards for
 * `root/duoAgentPlatform`, plus the shared set the reference extension registers on the same id.
 */
class PluginRegistryDuoAgentPlatformRouteTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val notifications = listOf(
    "appReady",
    "insertCodeSnippet",
    "copyCodeSnippet",
    "copyMessage",
    "openMcpDashboard",
    "switchToClassicLayout",
    "showMessage",
    "focusChange",
    "openLink",
    "openUrl",
  )

  fun controller() = DuoAgentPlatformWebViewController(
    mockk<PlatformUtils>(),
    mockk<CurrentFileContextProvider>(),
    mockk<InsertCodeSnippetService>(),
    mockk<GitLabLanguageServerWrapper>(),
    mockk<AgentPlatformLayoutStore>(),
    mockk<AgentPlatformReadiness>(),
    openMcpDashboard = {},
    showDocument = {},
    onUiThread = {},
  )

  describe("root/duoAgentPlatform routes") {
    it("resolves the getCurrentFileContext request") {
      val registry = PluginRegistry(listOf(controller()))

      registry[PluginMessageRoute(PLUGIN_ID, PluginMessageType.REQUEST, "getCurrentFileContext")] shouldNotBe null
    }

    notifications.forEach { method ->
      it("resolves the $method notification") {
        val registry = PluginRegistry(listOf(controller()))

        registry[PluginMessageRoute(PLUGIN_ID, PluginMessageType.NOTIFICATION, method)] shouldNotBe null
      }
    }

    it("registers 11 routes in total") {
      val registry = PluginRegistry(listOf(controller()))
      val requests = listOf("getCurrentFileContext").count {
        registry[PluginMessageRoute(PLUGIN_ID, PluginMessageType.REQUEST, it)] != null
      }
      val notified = notifications.count {
        registry[PluginMessageRoute(PLUGIN_ID, PluginMessageType.NOTIFICATION, it)] != null
      }

      requests + notified shouldBe 11
      controller().javaClass.declaredMethods.count { m ->
        m.annotations.any { it.annotationClass.simpleName in setOf("PluginRequest", "PluginNotification") }
      } shouldBe 11
    }

    // A payload type here would make every payload-less notification fail the payload/type match
    // and be dropped with a warning.
    listOf("appReady", "openMcpDashboard").forEach { method ->
      it("registers $method with no payload type") {
        val registry = PluginRegistry(listOf(controller()))

        registry[PluginMessageRoute(PLUGIN_ID, PluginMessageType.NOTIFICATION, method)]?.type shouldBe null
      }
    }

    it("registers no duplicate next to the two chat controllers") {
      val log = CapturedLog().apply { install() }
      val classic = GitLabDuoChatWebViewController(
        mockk<PlatformUtils>(),
        mockk<CurrentFileContextProvider>(),
        mockk<GitLabDuoChatWebViewClient>(),
        mockk<InsertCodeSnippetService>(),
      )
      val agentic = AgenticChatWebViewController(
        mockk<PlatformUtils>(),
        mockk<CurrentFileContextProvider>(),
        mockk<InsertCodeSnippetService>(),
        mockk<AgenticChatWebViewClient>(),
      ) { it.run() }

      PluginRegistry(listOf(classic, agentic, controller()))

      log.contains("already registered") shouldBe false
    }
  }
})
