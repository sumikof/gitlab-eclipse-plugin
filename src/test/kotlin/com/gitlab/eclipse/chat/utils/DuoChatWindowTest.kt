package com.gitlab.eclipse.chat.utils

import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.views.LanguageServerBrowserView
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.eclipse.ui.IViewPart
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PlatformUI
import org.osgi.framework.Bundle

private const val FAILURE_MESSAGE = "Could not open GitLab Duo Chat. See the Error Log."

/**
 * Design §12's `showView` row, verified from a production entry point (§20a rule 1) rather than
 * from the private helper the window functions share.
 *
 * The recording log is installed once for the whole spec because `DuoChatWindow`'s `logger` is a
 * lazy top-level val: whichever `ILog` it resolves on first use is the one it keeps for the rest of
 * the JVM, so a per-test replacement would only be honoured by the first test. Only this spec ever
 * runs `showDuoChatView` for real — every other spec stubs the enclosing window functions — so this
 * spec is what resolves it; and were that ever to stop being true, the two reporting tests below
 * would fail on an empty list rather than pass vacuously.
 *
 * The two-argument `error` is deliberately left unstubbed on this strict mock, so attaching an
 * exception to an entry fails the test loudly instead of passing unnoticed.
 */
class DuoChatWindowTest : DescribeSpec({
  val errors = mutableListOf<String>()
  val log = mockk<ILog>()
  val page = mockk<IWorkbenchPage>()
  val window = mockk<IWorkbenchWindow>()
  val workbench = mockk<IWorkbench>()

  beforeSpec {
    mockkStatic(Platform::class)
    mockkStatic(PlatformUI::class)
    mockkObject(NotificationUtils)
    val message = slot<String>()
    every { log.error(capture(message)) } answers { errors += message.captured }
    every { Platform.getLog(any<Bundle>()) } returns log
    every { Platform.getLog(any<Class<*>>()) } returns log
  }

  beforeEach {
    errors.clear()
    // Only the call history: `answers = false` leaves the recording `log` above untouched, which a
    // blanket clear would silently disarm.
    clearMocks(NotificationUtils, page, window, workbench, answers = false)
    every { NotificationUtils.show(any()) } just runs
    every { PlatformUI.getWorkbench() } returns workbench
    every { workbench.activeWorkbenchWindow } returns window
    every { window.activePage } returns page
  }

  afterSpec { unmockkAll() }

  describe("openDuoChatWindow") {
    // Design §12: this used to be a `warn` and nothing else — the failure reached no user at all.
    it("reports through both channels when there is no active workbench page") {
      every { workbench.activeWorkbenchWindow } returns null

      openDuoChatWindow()

      errors shouldHaveSize 1
      errors.first() shouldContain "no active workbench page"
      verify(exactly = 1) { NotificationUtils.show(FAILURE_MESSAGE) }
    }

    // Design §12: the same, for the second of the helper's two null returns.
    it("reports through both channels when the view is not the browser view") {
      every { page.showView(any()) } returns mockk<IViewPart>()

      openDuoChatWindow()

      errors shouldHaveSize 1
      errors.first() shouldContain "did not resolve to LanguageServerBrowserView"
      verify(exactly = 1) { NotificationUtils.show(FAILURE_MESSAGE) }
    }

    // The success path is unchanged: nothing is logged and nothing is shown to the user.
    it("says nothing when the view is shown") {
      val view = mockk<LanguageServerBrowserView>(relaxUnitFun = true)
      every { page.showView(any()) } returns view

      openDuoChatWindow()

      errors.shouldBeEmpty()
      verify(exactly = 0) { NotificationUtils.show(any()) }
      verify(exactly = 1) { view.requestFocus() }
    }
  }
})
