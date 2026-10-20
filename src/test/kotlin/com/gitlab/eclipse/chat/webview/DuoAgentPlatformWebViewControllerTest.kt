package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.services.InsertCodeSnippetService
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerHandle
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.plugins.PluginMessageService
import com.gitlab.eclipse.lsp.plugins.PluginRegistry
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageType
import com.gitlab.eclipse.navigation.ClipboardTarget
import com.gitlab.eclipse.navigation.ClipboardWriter
import com.gitlab.eclipse.preferences.storage.CapturedLog
import com.gitlab.eclipse.utils.CodeFormatter
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformLayout
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformLayoutStore
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformReadiness
import com.gitlab.eclipse.views.webview.agentplatform.LayoutPreferenceAccess
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.swt.SWTError

private const val SNIPPET = "SECRET-SNIPPET-TEXT"
private const val MESSAGE = "SECRET-MESSAGE-TEXT"
private const val LINK = "https://gitlab.example.com/some/page?token=SECRET"

/**
 * Design §5.3: the `root/duoAgentPlatform` controller. The bus dispatches on a pool thread, so every
 * session-aware handler hops through `onUiThread` and compares the sender against the current
 * session only after the hop. The hop here is captured, never run inline, so a test decides what
 * the world looks like when it runs.
 */
class DuoAgentPlatformWebViewControllerTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  class Fixture {
    val log = CapturedLog().apply { install() }
    val sessionA = LanguageServerSession()
    val sessionB = LanguageServerSession()
    val wrapper = mockk<GitLabLanguageServerWrapper>()
    val readiness = mockk<AgentPlatformReadiness>(relaxed = true)
    val handlers = mockk<ChatWebViewMessageHandlers>(relaxed = true)
    var stored: String? = null
    val layoutStore = AgentPlatformLayoutStore(object : LayoutPreferenceAccess {
      override fun read() = stored
      override fun write(value: String) {
        stored = value
      }
    })
    var openerCalls = 0
    var openerFailure: Throwable? = null
    val shown = mutableListOf<String>()
    val hops = mutableListOf<Runnable>()

    val controller = DuoAgentPlatformWebViewController(
      mockk<PlatformUtils>(),
      mockk<CurrentFileContextProvider>(),
      mockk<InsertCodeSnippetService>(),
      wrapper,
      layoutStore,
      readiness,
      openMcpDashboard = {
        openerCalls++
        openerFailure?.let { throw it }
      },
      showDocument = { shown += it },
      onUiThread = { hops += it },
      handlers = handlers,
    )

    init {
      current(sessionA)
    }

    fun current(session: LanguageServerSession?) {
      every { wrapper.currentSnapshot } returns session?.let { LanguageServerHandle(mockk<GitLabLanguageServer>(), it, 0L) }
    }

    fun runHops() {
      val pending = hops.toList()
      hops.clear()
      pending.forEach { it.run() }
    }
  }

  describe("appReady") {
    it("marks the current session ready on the UI thread") {
      val f = Fixture()

      f.controller.appReady(f.sessionA)
      verify(exactly = 0) { f.readiness.markReady(any()) }
      f.runHops()

      verify(exactly = 1) { f.readiness.markReady(f.sessionA) }
    }

    it("ignores an appReady from a session that is not current") {
      val f = Fixture()

      f.controller.appReady(f.sessionB)
      f.runHops()

      verify(exactly = 0) { f.readiness.markReady(any()) }
    }

    // The comparison belongs after the hop: a restart can land while the runnable waits in the queue.
    it("ignores an appReady whose session was replaced between dispatch and the UI turn") {
      val f = Fixture()

      f.controller.appReady(f.sessionA)
      f.current(f.sessionB)
      f.runHops()

      verify(exactly = 0) { f.readiness.markReady(any()) }
    }

    it("ignores an appReady once the language server has stopped") {
      val f = Fixture()

      f.controller.appReady(f.sessionA)
      f.current(null)
      f.runHops()

      verify(exactly = 0) { f.readiness.markReady(any()) }
    }
  }

  describe("openMcpDashboard") {
    it("opens the dashboard once for the current session") {
      val f = Fixture()

      f.controller.openMcpDashboard(f.sessionA)
      f.openerCalls shouldBe 0
      f.runHops()

      f.openerCalls shouldBe 1
    }

    it("does not open the dashboard for a stale session") {
      val f = Fixture()

      f.controller.openMcpDashboard(f.sessionA)
      f.current(f.sessionB)
      f.runHops()

      f.openerCalls shouldBe 0
    }

    it("logs a failure by exception class only") {
      val f = Fixture()
      f.openerFailure = IllegalStateException("SECRET-DETAIL")

      f.controller.openMcpDashboard(f.sessionA)
      f.runHops()

      f.log.contains("IllegalStateException") shouldBe true
      f.log.contains("SECRET-DETAIL") shouldBe false
    }

    // SWTError extends Error: escaping the asyncExec runnable, the event loop would log its message
    // and stack trace and may raise the internal-error dialog (review H2, ShowDocumentLauncher.openNow).
    it("contains an SWTError from the opener, logging the class only") {
      val f = Fixture()
      f.openerFailure = SWTError("SECRET-DETAIL")

      f.controller.openMcpDashboard(f.sessionA)
      f.runHops()

      f.log.contains("SWTError") shouldBe true
      f.log.contains("SECRET-DETAIL") shouldBe false
    }
  }

  describe("switchToClassicLayout") {
    it("switches the layout to classic for the current session") {
      val f = Fixture()

      f.controller.switchToClassicLayout(SwitchToClassicLayoutNotification(hideSurvey = false), f.sessionA)
      f.layoutStore.current() shouldBe AgentPlatformLayout.NEW
      f.runHops()

      f.layoutStore.current() shouldBe AgentPlatformLayout.CLASSIC
    }

    it("leaves the layout unchanged for a stale session") {
      val f = Fixture()

      f.controller.switchToClassicLayout(SwitchToClassicLayoutNotification(hideSurvey = false), f.sessionB)
      f.runHops()

      f.layoutStore.current() shouldBe AgentPlatformLayout.NEW
      f.stored shouldBe null
    }

    // Design U-B: hideSurvey is accepted for the reference contract and read by nothing.
    it("does nothing beyond the switch when hideSurvey is true") {
      val f = Fixture()

      f.controller.switchToClassicLayout(SwitchToClassicLayoutNotification(hideSurvey = true), f.sessionA)
      f.runHops()

      f.stored shouldBe "classic"
      f.openerCalls shouldBe 0
      f.shown.shouldBeEmpty()
      verify(exactly = 0) { f.readiness.markReady(any()) }
    }

    it("parses the language server's payload through the bus") {
      val f = Fixture()
      val route = PluginMessageRoute(
        AgentPlatformLayout.NEW_WEBVIEW_ID,
        PluginMessageType.NOTIFICATION,
        "switchToClassicLayout",
      )
      val payload = JsonObject().apply { add("hideSurvey", JsonPrimitive(true)) }

      PluginMessageService(PluginRegistry(listOf(f.controller))).dispatch(route, payload, f.sessionA).get()
      f.runHops()

      f.layoutStore.current() shouldBe AgentPlatformLayout.CLASSIC
    }
  }

  describe("snippet and message actions") {
    it("inserts the snippet through the shared handler") {
      val f = Fixture()
      val notification = InsertCodeSnippetNotification(SNIPPET)

      f.controller.insertCodeSnippet(notification)

      verify(exactly = 1) { f.handlers.insertCodeSnippet(match { it.snippet == SNIPPET }) }
    }

    it("copies the snippet through the shared handler") {
      val f = Fixture()

      f.controller.copyCodeSnippet(CopyCodeSnippetNotification(SNIPPET))

      verify(exactly = 1) { f.handlers.copyCodeSnippet(match { it.snippet == SNIPPET }) }
    }

    it("copies the message through the shared handler") {
      val f = Fixture()

      f.controller.copyMessage(CopyMessageNotification(MESSAGE))

      verify(exactly = 1) { f.handlers.copyMessage(match { it.message == MESSAGE }) }
    }

    it("serves the current file context through the shared handler") {
      val f = Fixture()
      every { f.handlers.getCurrentFileContext() } returns null

      f.controller.getCurrentFileContext() shouldBe null

      verify(exactly = 1) { f.handlers.getCurrentFileContext() }
    }

    // Runs the real shared handlers (not the relaxed mock): only the display-bound ends are
    // replaced, so a log line added anywhere on the delegated path is caught (review H5). Both the
    // landed and the failed clipboard write are taken, and the insert reaches the service.
    it("logs neither snippet nor message text on the real delegated path") {
      val f = Fixture()
      val platformUtils = mockk<PlatformUtils>()
      every { platformUtils.getActiveDocument() } returns null
      var clipboardFails = false
      val clipboard = object : ClipboardTarget {
        override fun setText(text: String) {
          if (clipboardFails) throw SWTError("$SNIPPET $MESSAGE")
        }

        override fun dispose() = Unit
      }
      val notices = mutableListOf<String>()
      val realHandlers = ChatWebViewMessageHandlers(
        platformUtils,
        mockk<CurrentFileContextProvider>(),
        InsertCodeSnippetService(platformUtils, mockk<CodeFormatter>()),
        clipboardWriter = ClipboardWriter(onUiThread = { it.run() }, openClipboard = { clipboard }),
        notify = { notices += it },
      )
      val controller = DuoAgentPlatformWebViewController(
        platformUtils,
        mockk<CurrentFileContextProvider>(),
        mockk<InsertCodeSnippetService>(),
        f.wrapper,
        f.layoutStore,
        f.readiness,
        openMcpDashboard = {},
        showDocument = {},
        onUiThread = { it.run() },
        handlers = realHandlers,
      )

      controller.insertCodeSnippet(InsertCodeSnippetNotification(SNIPPET))
      controller.copyCodeSnippet(CopyCodeSnippetNotification(SNIPPET))
      controller.copyMessage(CopyMessageNotification(MESSAGE))
      clipboardFails = true
      controller.copyCodeSnippet(CopyCodeSnippetNotification(SNIPPET))
      controller.copyMessage(CopyMessageNotification(MESSAGE))

      // The path really ran: two landed copies notified, two failures and the insert logged.
      notices shouldContainExactly listOf(ClipboardWriter.COPIED_TO_CLIPBOARD, ClipboardWriter.COPIED_TO_CLIPBOARD)
      f.log.contains("No document found") shouldBe true
      f.log.lines.count { it.contains("SWTError") } shouldBe 2
      f.log.contains(SNIPPET) shouldBe false
      f.log.contains(MESSAGE) shouldBe false
    }
  }

  describe("links") {
    it("opens the url of openUrl") {
      val f = Fixture()

      f.controller.openUrl(OpenLinkNotification(url = LINK))

      f.shown shouldContainExactly listOf(LINK)
    }

    it("opens the href of openLink") {
      val f = Fixture()

      f.controller.openLink(OpenLinkNotification(href = LINK))

      f.shown shouldContainExactly listOf(LINK)
    }

    it("prefers href over url, like the reference handler") {
      val f = Fixture()

      f.controller.openLink(OpenLinkNotification(href = LINK, url = "https://other.example.com"))

      f.shown shouldContainExactly listOf(LINK)
    }

    it("opens nothing when neither href nor url is given") {
      val f = Fixture()

      f.controller.openLink(OpenLinkNotification())
      f.controller.openUrl(OpenLinkNotification())

      f.shown.shouldBeEmpty()
    }

    it("does not log the link") {
      val f = Fixture()

      f.controller.openUrl(OpenLinkNotification(url = LINK))

      f.log.contains("SECRET") shouldBe false
    }
  }

  describe("showMessage") {
    // Review H7: the type is webview-supplied; only the three known categories are logged verbatim.
    it("logs an unknown type as other, never verbatim") {
      val f = Fixture()

      f.controller.showMessage(ShowMessageNotification(type = "SECRET-TYPE", message = MESSAGE))

      f.log.contains("type=other") shouldBe true
      f.log.contains("SECRET-TYPE") shouldBe false
    }

    it("logs each known type as itself") {
      val f = Fixture()

      val known = listOf("info", "warning", "error")
      known.forEach { type ->
        f.controller.showMessage(ShowMessageNotification(type = type, message = MESSAGE))
      }

      val logged = f.log.lines.filter { it.contains("showMessage") }
      logged shouldContainExactly known.map { "Agent Platform showMessage received: type=$it" }
    }

    it("logs the type but never the message text") {
      val f = Fixture()

      f.controller.showMessage(ShowMessageNotification(type = "warning", message = MESSAGE))

      f.log.contains("warning") shouldBe true
      f.log.contains(MESSAGE) shouldBe false
      verify(exactly = 0) { f.handlers.showMessage(any()) }
    }
  }

  describe("focusChange") {
    it("touches no state") {
      val f = Fixture()

      f.controller.focusChange(FocusChangeNotification(isFocused = true))

      f.hops.shouldBeEmpty()
      f.layoutStore.current() shouldBe AgentPlatformLayout.NEW
      verify(exactly = 0) { f.readiness.markReady(any()) }
    }
  }
})
