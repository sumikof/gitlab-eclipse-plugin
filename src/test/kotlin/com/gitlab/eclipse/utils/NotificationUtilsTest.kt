package com.gitlab.eclipse.utils

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTException

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationUtilsTest : DescribeSpec({
  // Seam injection instead of mockkStatic("com.gitlab.eclipse.utils.DisplayKt"): stubbing
  // `currentDisplay` makes MockK materialise a temporary Display mock, and ANY Display instance
  // initialises Device, whose static init loads the SWT natives — impossible in a headless JVM
  // (the reason every Display-mocking test in this repo sits in the known headless-failure set).
  // The seams' production defaults are the only lines that touch the Display, so every test here
  // runs headless. `showOnUiThread` opens a real SWT popup, so it is stubbed via mockkObject
  // where a test must observe whether the popup was requested.
  beforeSpec { mockkObject(NotificationUtils) }

  afterEach { clearAllMocks() }

  afterSpec { unmockkAll() }

  describe("show") {
    it("returns normally when the display lookup throws IllegalStateException (workbench torn down)") {
      shouldNotThrowAny {
        NotificationUtils.show("hello", onUiThread = { error("Workbench has not been created yet.") })
      }
    }

    it("returns normally when scheduling throws SWTException (display disposed)") {
      shouldNotThrowAny {
        NotificationUtils.show("hello", onUiThread = { throw SWTException(SWT.ERROR_DEVICE_DISPOSED) })
      }
    }

    it("schedules exactly one runnable and opens nothing before it runs") {
      every { NotificationUtils.showOnUiThread(any()) } just Runs
      val scheduled = mutableListOf<Runnable>()

      NotificationUtils.show("hello", onUiThread = { scheduled += it })

      scheduled.size shouldBe 1
      verify(exactly = 0) { NotificationUtils.showOnUiThread(any()) }
    }

    it("opens the popup when the display is still alive when the runnable runs") {
      every { NotificationUtils.showOnUiThread(any()) } just Runs
      val scheduled = mutableListOf<Runnable>()

      NotificationUtils.show("hello", onUiThread = { scheduled += it }, isDisplayDisposed = { false })
      scheduled.single().run()

      verify(exactly = 1) { NotificationUtils.showOnUiThread("hello") }
    }

    it("swallows an SWTException thrown by the popup inside the runnable (reflectLatest's shape)") {
      // showOnUiThread builds a shell and opens the popup; on a display disposed mid-turn that
      // throws SWTException INSIDE the scheduled runnable, where the outer catch cannot see it —
      // uncaught it would surface as an "Unhandled event loop exception" per notification.
      every { NotificationUtils.showOnUiThread(any()) } throws SWTException(SWT.ERROR_WIDGET_DISPOSED)
      val scheduled = mutableListOf<Runnable>()

      NotificationUtils.show("hello", onUiThread = { scheduled += it }, isDisplayDisposed = { false })

      shouldNotThrowAny { scheduled.single().run() }
    }

    it("skips the popup when the display is disposed by the time the runnable runs") {
      every { NotificationUtils.showOnUiThread(any()) } just Runs
      val scheduled = mutableListOf<Runnable>()

      NotificationUtils.show("hello", onUiThread = { scheduled += it }, isDisplayDisposed = { true })
      scheduled.single().run()

      verify(exactly = 0) { NotificationUtils.showOnUiThread(any()) }
    }

    it("does not cancel a shared scope when called from a coroutine's terminal catch") {
      // launchCiWrite's shape (#41): plain Job on a SHARED scope, show() in the terminal catch.
      // Pre-fix, show() rethrows there, the child fails, and the shared Job — and every other
      // coroutine on the scope — is cancelled.
      val swallowUncaught = CoroutineExceptionHandler { _, _ -> }
      val sharedScope = CoroutineScope(Job() + UnconfinedTestDispatcher() + swallowUncaught)

      val job = sharedScope.launch {
        try {
          throw IllegalArgumentException("simulated CI write failure")
        } catch (expected: Exception) {
          NotificationUtils.show("write failed", onUiThread = { error("Workbench has not been created yet.") })
        }
      }
      job.join()

      sharedScope.isActive shouldBe true
    }
  }
})
