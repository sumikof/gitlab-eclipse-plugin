package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.storage.CapturedLog
import com.gitlab.eclipse.views.webview.AgenticTabsView
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.IViewPart
import org.eclipse.ui.IWorkbenchPage

/** "Reload" in the Agent Platform view's pulldown (design AGENT-02 §5.1 / §6). */
class ReloadAgentPlatformViewHandlerTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val event = mockk<ExecutionEvent>(relaxed = true)

  it("reloads the Agent Platform view of the active page") {
    val view = mockk<AgenticTabsView> { every { reloadFromMenu() } just runs }
    val page = mockk<IWorkbenchPage> { every { findView(AgenticTabsView.VIEW_ID) } returns view }
    val notices = mutableListOf<String>()

    ReloadAgentPlatformViewHandler({ page }) { notices += it }.execute(event)

    verify(exactly = 1) { view.reloadFromMenu() }
    notices.shouldBeEmpty()
  }

  it("does nothing when the view is not open on the active page") {
    val page = mockk<IWorkbenchPage> { every { findView(AgenticTabsView.VIEW_ID) } returns null }
    val notices = mutableListOf<String>()

    ReloadAgentPlatformViewHandler({ page }) { notices += it }.execute(event)

    notices.shouldBeEmpty()
  }

  it("does nothing when the id resolves to some other view") {
    val page = mockk<IWorkbenchPage> { every { findView(AgenticTabsView.VIEW_ID) } returns mockk<IViewPart>() }

    ReloadAgentPlatformViewHandler({ page }) { error("no notice expected") }.execute(event)
  }

  it("reports a missing active page: ERROR plus the reload failure notice") {
    val log = CapturedLog().apply { install() }
    val notices = mutableListOf<String>()

    ReloadAgentPlatformViewHandler({ null }) { notices += it }.execute(event)

    notices shouldContainExactly listOf(AgentPlatformMessages.RELOAD_FAILURE)
    log.contains("Agent Platform reload") shouldBe true
  }

  it("reports a failing reload by exception class only") {
    val log = CapturedLog().apply { install() }
    val view = mockk<AgenticTabsView> { every { reloadFromMenu() } throws IllegalStateException("secret-detail") }
    val page = mockk<IWorkbenchPage> { every { findView(AgenticTabsView.VIEW_ID) } returns view }
    val notices = mutableListOf<String>()

    ReloadAgentPlatformViewHandler({ page }) { notices += it }.execute(event)

    notices shouldContainExactly listOf(AgentPlatformMessages.RELOAD_FAILURE)
    log.contains(IllegalStateException::class.java.name) shouldBe true
    log.lines.forEach { it shouldNotContain "secret-detail" }
  }
})
