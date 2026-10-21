package com.gitlab.eclipse.utils

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTException

class ActionNotificationsTest : DescribeSpec({
  // Same seam approach as NotificationUtilsTest: no Display can exist headless, so the popup itself
  // (openOnUiThread) is stubbed and only the gating around it is exercised.
  beforeSpec { mockkObject(ActionNotifications) }

  afterEach { clearAllMocks() }

  afterSpec { unmockkAll() }

  val links = listOf<Pair<String, () -> Unit>>("Show Logs" to {})

  fun show(
    scheduled: MutableList<Runnable>,
    disposed: Boolean = false,
    gate: () -> Boolean = { true },
  ) = ActionNotifications.show(
    "GitLab Duo",
    "message",
    SWT.ICON_ERROR,
    links,
    0,
    onUiThread = { scheduled += it },
    isDisplayDisposed = { disposed },
    gate = gate,
  )

  describe("show") {
    it("returns normally when the display lookup throws IllegalStateException") {
      shouldNotThrowAny {
        ActionNotifications.show("t", "m", SWT.ICON_ERROR, links, 0, onUiThread = { error("no workbench") })
      }
    }

    it("returns normally when scheduling throws SWTException") {
      shouldNotThrowAny {
        ActionNotifications.show(
          "t",
          "m",
          SWT.ICON_ERROR,
          links,
          0,
          onUiThread = { throw SWTException(SWT.ERROR_DEVICE_DISPOSED) },
        )
      }
    }

    it("opens nothing before the runnable runs, then opens once") {
      every { ActionNotifications.openOnUiThread(any(), any(), any(), any(), any()) } returns AutoCloseable {}
      val scheduled = mutableListOf<Runnable>()

      show(scheduled)
      verify(exactly = 0) { ActionNotifications.openOnUiThread(any(), any(), any(), any(), any()) }
      scheduled.single().run()

      verify(exactly = 1) { ActionNotifications.openOnUiThread("GitLab Duo", "message", SWT.ICON_ERROR, links, 0) }
    }

    it("opens nothing when the gate says no") {
      every { ActionNotifications.openOnUiThread(any(), any(), any(), any(), any()) } returns AutoCloseable {}
      val scheduled = mutableListOf<Runnable>()

      show(scheduled, gate = { false })
      scheduled.single().run()

      verify(exactly = 0) { ActionNotifications.openOnUiThread(any(), any(), any(), any(), any()) }
    }

    it("opens nothing when the display was disposed in the meantime") {
      every { ActionNotifications.openOnUiThread(any(), any(), any(), any(), any()) } returns AutoCloseable {}
      val scheduled = mutableListOf<Runnable>()

      show(scheduled, disposed = true)
      scheduled.single().run()

      verify(exactly = 0) { ActionNotifications.openOnUiThread(any(), any(), any(), any(), any()) }
    }

    it("opens nothing when the handle was closed before the runnable ran") {
      every { ActionNotifications.openOnUiThread(any(), any(), any(), any(), any()) } returns AutoCloseable {}
      val scheduled = mutableListOf<Runnable>()

      show(scheduled).close()
      scheduled.single().run()

      verify(exactly = 0) { ActionNotifications.openOnUiThread(any(), any(), any(), any(), any()) }
    }

    it("closes the opened popup through the returned handle, once") {
      var closes = 0
      every { ActionNotifications.openOnUiThread(any(), any(), any(), any(), any()) } returns AutoCloseable { closes++ }
      val scheduled = mutableListOf<Runnable>()

      val handle = show(scheduled)
      scheduled.single().run()
      handle.close()
      handle.close()

      closes shouldBe 1
    }

    it("swallows an SWTException from the popup inside the runnable") {
      every { ActionNotifications.openOnUiThread(any(), any(), any(), any(), any()) } throws
        SWTException(SWT.ERROR_WIDGET_DISPOSED)
      val scheduled = mutableListOf<Runnable>()

      show(scheduled)

      shouldNotThrowAny { scheduled.single().run() }
    }
  }
})
