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
import com.gitlab.eclipse.lsp.plugins.messages.ExtensionToPluginNotification
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageType
import com.gitlab.eclipse.utils.PlatformUtils
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle

private const val HISTORY = "history"
private const val NEW_CONVERSATION = "newConversation"
private const val READINESS_TIMEOUT_MILLIS = 10_000L

private val APP_READY = PluginMessageRoute(
  ChatWebviewCatalog.AGENTIC_WEBVIEW_ID,
  PluginMessageType.NOTIFICATION,
  "appReady",
)

/**
 * The timer seam of design §7.4, recording what was scheduled instead of running it, so a test
 * chooses when each callback fires and what the world looks like when it does.
 */
private class Timers {
  val scheduled = mutableListOf<Pair<Long, Runnable>>()

  fun schedule(delayMillis: Long, task: Runnable) {
    scheduled += delayMillis to task
  }

  /** Runs the callback scheduled at [index]. The list only ever grows, so indices stay stable. */
  fun fire(index: Int) = scheduled[index].second.run()

  /**
   * Runs every callback, including the ones the earlier ones schedule, up to [LIMIT] of them. The
   * limit turns an unbounded resend into a failed assertion instead of a hung test.
   */
  fun drain() {
    var index = 0
    while (index < scheduled.size && index < LIMIT) {
      fire(index)
      index++
    }
  }

  val delays: List<Long> get() = scheduled.map { it.first }

  private companion object {
    const val LIMIT = 20
  }
}

private class Fixture {
  val sessionA = LanguageServerSession()
  val sessionB = LanguageServerSession()
  val proxy = mockk<GitLabLanguageServer>()
  val wrapper = mockk<GitLabLanguageServerWrapper>()
  val timers = Timers()
  val notifications = mutableListOf<String>()
  val sent = mutableListOf<ExtensionToPluginNotification>()

  val client = AgenticChatWebViewClient(wrapper, { notifications += it }, timers::schedule)

  init {
    every { proxy.pluginNotification(capture(sent)) } just Runs
    current(sessionA)
  }

  /** Makes [session] the connection the wrapper reports as current, or none at all when null. */
  fun current(session: LanguageServerSession?) {
    every { wrapper.currentSnapshot } returns session?.let { LanguageServerHandle(proxy, it) }
  }

  /** The `view` every delivered notification carried, in order. */
  val sentViews: List<String?> get() = sent.map { (it.payload as? Map<*, *>)?.get("view") as? String }

  /**
   * The production path an `appReady` takes: the plugin bus dispatches it to the controller, and
   * the controller is what calls `markReady` (design §20a rule 1).
   *
   * [onUiThread] runs the hop the controller makes; the default runs it inline so a test that is
   * not about the hop reads as one step.
   */
  fun appReadyArrives(from: LanguageServerSession, onUiThread: (Runnable) -> Unit = { it.run() }) {
    val controller = AgenticChatWebViewController(
      mockk<PlatformUtils>(),
      mockk<CurrentFileContextProvider>(),
      mockk<InsertCodeSnippetService>(),
      client,
      onUiThread,
    )
    // Joining the future is all this is for. It completes normally whether the handler succeeded or
    // threw — the registry contains and logs a session-aware notification's failure — so nothing is
    // asserted about its value.
    PluginMessageService(PluginRegistry(listOf(controller))).dispatch(APP_READY, null, from).get()
  }
}

/**
 * Records the message of every single-argument `error` the client logs.
 *
 * The two-argument form is left unstubbed on purpose: this mock throws on it, so attaching an
 * exception to the entry fails the test rather than passing unnoticed.
 */
private fun captureErrorLog(): List<String> {
  val recorded = mutableListOf<String>()
  val log = mockk<ILog>()
  val message = slot<String>()
  every { log.error(capture(message)) } answers { recorded += message.captured }
  every { Platform.getLog(any<Bundle>()) } returns log
  every { Platform.getLog(any<Class<*>>()) } returns log
  return recorded
}

class AgenticChatWebViewClientTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("switchView") {
    // A8 (design §21).
    it("sends nothing while the webview has not reported itself ready") {
      val fixture = Fixture()

      fixture.client.switchView(HISTORY)

      fixture.sentViews.shouldBeEmpty()
    }

    // A9 (design §21): one slot, latest-wins.
    it("keeps only the latest view when several arrive before the webview is ready") {
      val fixture = Fixture()

      fixture.client.switchView(HISTORY)
      fixture.client.switchView(NEW_CONVERSATION)
      fixture.appReadyArrives(from = fixture.sessionA)

      fixture.sentViews shouldContainExactly listOf(NEW_CONVERSATION)
    }

    it("addresses the delivered notification to the agentic webview's switchView") {
      val fixture = Fixture()

      fixture.appReadyArrives(from = fixture.sessionA)
      fixture.client.switchView(HISTORY)

      fixture.sent.shouldHaveSize(1)
      fixture.sent.first().pluginId shouldBe ChatWebviewCatalog.AGENTIC_WEBVIEW_ID
      fixture.sent.first().type shouldBe "switchView"
      fixture.sent.first().payload shouldBe mapOf("view" to HISTORY)
    }

    it("arms the readiness deadline ten seconds out when the webview is not ready yet") {
      val fixture = Fixture()

      fixture.client.switchView(HISTORY)

      fixture.timers.delays shouldContainExactly listOf(READINESS_TIMEOUT_MILLIS)
    }
  }

  describe("markReady") {
    // A21 (design §21), driven the way production drives it: the bus names the connection the
    // notification was sent from, and that connection is no longer the current one.
    it("does not open the latch for an appReady sent from a connection that has been replaced") {
      val fixture = Fixture()
      fixture.current(fixture.sessionB)

      fixture.appReadyArrives(from = fixture.sessionA)
      fixture.client.switchView(HISTORY)

      fixture.sentViews.shouldBeEmpty()
    }

    // A24 (design §21): the property A21 cannot detect — a latch that is already open stays open.
    it("keeps the open latch when a late appReady from the replaced connection arrives") {
      val fixture = Fixture()
      fixture.current(fixture.sessionB)

      fixture.appReadyArrives(from = fixture.sessionB)
      fixture.appReadyArrives(from = fixture.sessionA)
      fixture.client.switchView(HISTORY)

      fixture.sentViews shouldContainExactly listOf(HISTORY)
    }

    it("does not open the latch while there is no current connection to match against") {
      val fixture = Fixture()
      fixture.current(null)

      fixture.appReadyArrives(from = fixture.sessionA)
      fixture.current(fixture.sessionA)
      fixture.client.switchView(HISTORY)

      fixture.sentViews.shouldBeEmpty()
    }

    it("sends nothing of its own when no view is waiting") {
      val fixture = Fixture()

      fixture.appReadyArrives(from = fixture.sessionA)

      fixture.sentViews.shouldBeEmpty()
    }

    it("hands the work to the UI thread instead of doing it on the dispatch thread") {
      val fixture = Fixture()
      val queued = mutableListOf<Runnable>()
      fixture.client.switchView(HISTORY)

      fixture.appReadyArrives(from = fixture.sessionA, onUiThread = { queued += it })

      fixture.sentViews.shouldBeEmpty()
      queued.shouldHaveSize(1)
      queued.first().run()
      fixture.sentViews shouldContainExactly listOf(HISTORY)
    }
  }

  describe("markNotReady") {
    it("closes the latch so a later view waits again") {
      val fixture = Fixture()

      fixture.appReadyArrives(from = fixture.sessionA)
      fixture.client.markNotReady()
      fixture.client.switchView(HISTORY)

      fixture.sentViews.shouldBeEmpty()
    }

    it("re-arms the readiness deadline of a view that is still waiting") {
      val fixture = Fixture()

      fixture.client.switchView(HISTORY)
      fixture.client.markNotReady()
      fixture.timers.fire(1)

      fixture.notifications shouldHaveSize 1
    }
  }

  describe("the bounded resend that covers the window design §5.3a leaves open") {
    // A18 (design §21).
    it("stops after two resends") {
      val fixture = Fixture()

      fixture.appReadyArrives(from = fixture.sessionA)
      fixture.client.switchView(HISTORY)
      fixture.timers.drain()

      fixture.sentViews shouldContainExactly listOf(HISTORY, HISTORY, HISTORY)
    }

    // A18 (design §21): giving up cannot be told from succeeding, so giving up says nothing.
    it("never reaches the notification path when it gives up") {
      val fixture = Fixture()

      fixture.appReadyArrives(from = fixture.sessionA)
      fixture.client.switchView(HISTORY)
      fixture.timers.drain()

      fixture.notifications.shouldBeEmpty()
    }

    // A20 (design §21), sequence 1.
    it("sends nothing more for a view a later command has replaced") {
      val fixture = Fixture()

      fixture.appReadyArrives(from = fixture.sessionA)
      fixture.client.switchView(HISTORY)
      fixture.client.switchView(NEW_CONVERSATION)
      fixture.timers.fire(0)

      fixture.sentViews shouldContainExactly listOf(HISTORY, NEW_CONVERSATION)
    }

    // A20 (design §21), sequence 2.
    it("sends nothing more once the latch has been closed") {
      val fixture = Fixture()

      fixture.appReadyArrives(from = fixture.sessionA)
      fixture.client.switchView(HISTORY)
      fixture.client.markNotReady()
      fixture.timers.fire(0)

      fixture.sentViews shouldContainExactly listOf(HISTORY)
    }

    it("sends nothing more once there is no connection at all") {
      val fixture = Fixture()

      fixture.appReadyArrives(from = fixture.sessionA)
      fixture.client.switchView(HISTORY)
      fixture.current(null)
      fixture.timers.fire(0)

      fixture.sentViews shouldContainExactly listOf(HISTORY)
    }

    // The generation `markReady` advances on every match, not only when it flushes. A restart that
    // reuses the Browser calls no `markNotReady`, so nothing else expires this series: by the time
    // it runs, both the latch and the current connection would agree on the new one.
    it("sends nothing more once the latch has been re-opened for another connection") {
      val fixture = Fixture()

      fixture.appReadyArrives(from = fixture.sessionA)
      fixture.client.switchView(HISTORY)
      fixture.current(fixture.sessionB)
      fixture.appReadyArrives(from = fixture.sessionB)
      fixture.timers.fire(0)

      fixture.sentViews shouldContainExactly listOf(HISTORY)
    }

    // A20 (design §21), sequence 3: no command and no reset intervene, only the connection changes.
    it("sends nothing more once another connection has become current") {
      val fixture = Fixture()

      fixture.appReadyArrives(from = fixture.sessionA)
      fixture.client.switchView(HISTORY)
      fixture.current(fixture.sessionB)
      fixture.timers.fire(0)

      fixture.sentViews shouldContainExactly listOf(HISTORY)
    }
  }

  describe("the readiness deadline") {
    // A12 (design §21): the plain path, one deadline and nothing else.
    it("notifies when the webview never reports itself ready") {
      val fixture = Fixture()

      fixture.client.switchView(HISTORY)
      fixture.timers.fire(0)

      fixture.notifications shouldHaveSize 1
    }

    // A12 (design §21).
    it("discards the waiting view when the webview never reports itself ready") {
      val fixture = Fixture()

      fixture.client.switchView(HISTORY)
      fixture.timers.fire(0)
      fixture.appReadyArrives(from = fixture.sessionA)

      fixture.sentViews.shouldBeEmpty()
    }

    // Design §17: the entry names the surface and what went wrong, and carries nothing else.
    it("records the webview id and the failure category, with no exception attached") {
      // Installed first: the client resolves its log when it is constructed.
      val recorded = captureErrorLog()
      val fixture = Fixture()

      fixture.client.switchView(HISTORY)
      fixture.timers.fire(0)

      recorded shouldHaveSize 1
      recorded.first() shouldContain ChatWebviewCatalog.AGENTIC_WEBVIEW_ID
      recorded.first() shouldNotContain "/"
    }

    // A12b (design §21).
    it("does not notify when a deadline from a superseded command fires") {
      val fixture = Fixture()

      fixture.client.switchView(HISTORY)
      fixture.client.switchView(NEW_CONVERSATION)
      fixture.timers.fire(0)

      fixture.notifications.shouldBeEmpty()
    }

    // A12b (design §21): the newer command keeps its own full deadline.
    it("does not discard the waiting view of the command that superseded it") {
      val fixture = Fixture()

      fixture.client.switchView(HISTORY)
      fixture.client.switchView(NEW_CONVERSATION)
      fixture.timers.fire(0)
      fixture.appReadyArrives(from = fixture.sessionA)

      fixture.sentViews shouldContainExactly listOf(NEW_CONVERSATION)
    }

    // A12c (design §21), property 1.
    it("does not notify when another connection became current while the view waited") {
      val fixture = Fixture()

      fixture.client.switchView(HISTORY)
      fixture.current(fixture.sessionB)
      fixture.timers.fire(0)

      fixture.notifications.shouldBeEmpty()
    }

    // A12c (design §21), property 2: quiet is not the same as unfinished.
    it("still discards the waiting view when another connection became current") {
      val fixture = Fixture()

      fixture.client.switchView(HISTORY)
      fixture.current(fixture.sessionB)
      fixture.timers.fire(0)
      fixture.appReadyArrives(from = fixture.sessionB)

      fixture.sentViews.shouldBeEmpty()
    }

    // The fifth outcome, which design §7.4's four-row table does not carry: a command issued with
    // no connection to send it on was never delivered by any measure, and the quiet outcome's
    // premise — that the user swapped connections themselves — is not what happened.
    it("notifies when the deadline was armed with no connection and one arrived after") {
      val fixture = Fixture()
      fixture.current(null)

      fixture.client.switchView(HISTORY)
      fixture.current(fixture.sessionA)
      fixture.timers.fire(0)

      fixture.notifications shouldHaveSize 1
    }

    // The terminal-state half of the outcome above, and green whichever way the notification goes:
    // every one of the five outcomes has to leave the slot empty.
    it("still discards the waiting view when the deadline was armed with no connection") {
      val fixture = Fixture()
      fixture.current(null)

      fixture.client.switchView(HISTORY)
      fixture.current(fixture.sessionA)
      fixture.timers.fire(0)
      fixture.appReadyArrives(from = fixture.sessionA)

      fixture.sentViews.shouldBeEmpty()
    }

    // Design §7.4, the timer table's third row: no current connection is the condition F-a names.
    it("notifies when the connection is gone by the time the deadline fires") {
      val fixture = Fixture()

      fixture.client.switchView(HISTORY)
      fixture.current(null)
      fixture.timers.fire(0)

      fixture.notifications shouldHaveSize 1
    }
  }
})
