package com.gitlab.eclipse.navigation

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Platform
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTError
import org.eclipse.swt.SWTException
import org.eclipse.swt.dnd.DND
import org.osgi.framework.Bundle
import java.util.concurrent.TimeUnit

/**
 * Appears in every copied text and every exception message this spec uses, so "the log names
 * neither" can be asserted by looking for one distinctive string.
 */
private const val SECRET = "SECRETPAYLOAD"

/** A stand-in for the SWT clipboard: records what was written and how often it was disposed. */
private class FakeClipboard(private val failWith: Throwable? = null) : ClipboardTarget {
  val written = mutableListOf<String>()
  var disposed = 0

  override fun setText(text: String) {
    failWith?.let { throw it }
    written += text
  }

  override fun dispose() {
    disposed++
  }
}

/**
 * Installs a log that records every string handed to it, and returns that recording. The writer
 * takes its log lazily, so this may be called before or after the writer is constructed.
 */
private fun captureLog(): List<String> {
  val recorded = mutableListOf<String>()
  val log = mockk<ILog>()
  val message = slot<String>()
  val status = slot<IStatus>()
  every { log.error(capture(message)) } answers { recorded += message.captured }
  every { log.error(capture(message), any()) } answers { recorded += message.captured }
  every { log.warn(capture(message)) } answers { recorded += message.captured }
  every { log.warn(capture(message), any()) } answers { recorded += message.captured }
  every { log.info(capture(message)) } answers { recorded += message.captured }
  every { log.info(capture(message), any()) } answers { recorded += message.captured }
  every { log.log(capture(status)) } answers { recorded += status.captured.message }
  every { Platform.getLog(any<Bundle>()) } returns log
  every { Platform.getLog(any<Class<*>>()) } returns log
  return recorded
}

class ClipboardWriterTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  /** The failures `Clipboard.setContents` (and its neighbours) can raise, by name. */
  val swtError = SWTError(DND.ERROR_CANNOT_SET_CLIPBOARD, "cannot set $SECRET")
  val swtException = SWTException(SWT.ERROR_WIDGET_DISPOSED, "disposed while copying $SECRET")
  val runtimeException = IllegalStateException("no display for $SECRET")

  /** A writer whose UI hop runs inline (unless [onUiThread] says otherwise) over [clipboard]. */
  fun writer(
    clipboard: ClipboardTarget,
    onUiThread: (Runnable) -> Unit = { it.run() },
  ) = ClipboardWriter(onUiThread = onUiThread, openClipboard = { clipboard })

  describe("writeChecked") {
    it("completes true once the text is on the clipboard, and disposes the clipboard") {
      val clipboard = FakeClipboard()

      val landed = writer(clipboard).writeChecked("hello").get(1, TimeUnit.SECONDS)

      landed shouldBe true
      clipboard.written.shouldContainExactly("hello")
      clipboard.disposed shouldBe 1
    }

    it("completes only when the UI turn has run, not when it was queued") {
      val clipboard = FakeClipboard()
      val deferred = mutableListOf<Runnable>()

      val outcome = writer(clipboard, onUiThread = { deferred += it }).writeChecked("hello")

      outcome.isDone shouldBe false
      clipboard.written.shouldBeEmpty()
      deferred.single().run()
      outcome.get(1, TimeUnit.SECONDS) shouldBe true
      clipboard.written.shouldContainExactly("hello")
    }

    // SWTError extends java.lang.Error, not Exception: `catch (e: Exception)` lets it through. This
    // is the case the whole spec exists for.
    it("completes false, without throwing, when the write raises SWTError, and still disposes") {
      val clipboard = FakeClipboard(failWith = swtError)

      val outcome = shouldNotThrowAny { writer(clipboard).writeChecked("hello") }

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
      clipboard.written.shouldBeEmpty()
      clipboard.disposed shouldBe 1
    }

    it("completes false, without throwing, when the write raises SWTException, and still disposes") {
      val clipboard = FakeClipboard(failWith = swtException)

      val outcome = shouldNotThrowAny { writer(clipboard).writeChecked("hello") }

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
      clipboard.disposed shouldBe 1
    }

    it("completes false, without throwing, when the write raises a plain RuntimeException, and still disposes") {
      val clipboard = FakeClipboard(failWith = runtimeException)

      val outcome = shouldNotThrowAny { writer(clipboard).writeChecked("hello") }

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
      clipboard.disposed shouldBe 1
    }

    it("completes false when the clipboard itself cannot be opened") {
      val outcome = shouldNotThrowAny {
        ClipboardWriter(onUiThread = { it.run() }, openClipboard = { throw swtError }).writeChecked("hello")
      }

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
    }

    it("completes false, rather than staying pending, when the UI-thread hop fails") {
      val clipboard = FakeClipboard()

      val outcome = shouldNotThrowAny {
        writer(clipboard, onUiThread = { throw swtException }).writeChecked("hello")
      }

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
      clipboard.written.shouldBeEmpty()
    }

    it("completes false when the display lookup behind the hop fails") {
      val outcome = shouldNotThrowAny {
        writer(FakeClipboard(), onUiThread = { throw runtimeException }).writeChecked("hello")
      }

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
    }
  }

  describe("writeAndNotify") {
    it("shows the copied notice once the write has landed") {
      val notices = mutableListOf<String>()

      val landed = writer(FakeClipboard()).writeAndNotify("hello") { notices += it }.get(1, TimeUnit.SECONDS)

      landed shouldBe true
      notices.shouldContainExactly(ClipboardWriter.COPIED_TO_CLIPBOARD)
    }

    it("shows nothing when the write failed") {
      val notices = mutableListOf<String>()

      val landed = writer(FakeClipboard(failWith = swtError))
        .writeAndNotify("hello") { notices += it }
        .get(1, TimeUnit.SECONDS)

      landed shouldBe false
      notices.shouldBeEmpty()
    }

    it("shows nothing until the UI turn that writes has actually run") {
      val notices = mutableListOf<String>()
      val deferred = mutableListOf<Runnable>()

      writer(FakeClipboard(), onUiThread = { deferred += it }).writeAndNotify("hello") { notices += it }

      notices.shouldBeEmpty()
      deferred.single().run()
      notices.shouldContainExactly(ClipboardWriter.COPIED_TO_CLIPBOARD)
    }
  }

  describe("write") {
    it("writes on the UI thread and disposes the clipboard, as before") {
      val clipboard = FakeClipboard()

      writer(clipboard).write("hello")

      clipboard.written.shouldContainExactly("hello")
      clipboard.disposed shouldBe 1
    }

    it("still disposes the clipboard when the write fails, and keeps letting the failure out") {
      val clipboard = FakeClipboard(failWith = swtError)

      shouldThrow<SWTError> { writer(clipboard).write("hello") }

      clipboard.disposed shouldBe 1
    }
  }

  describe("log hygiene") {
    // captureLog installs a recording log into the static Platform mock, and that stub must not
    // outlive the test that asked for it. Removing and reinstalling the static mock leaves exactly
    // the state LoggingKotestExtension.beforeSpec created: a bare static mock, which its
    // beforeEach then stubs afresh. (A plain unmockkStatic would leave that beforeEach stubbing a
    // class that is no longer mocked at all.)
    afterTest {
      unmockkStatic(Platform::class)
      mockkStatic(Platform::class)
    }

    it("logs a failed write by exception class name, naming neither the text nor the message") {
      val logged = captureLog()

      writer(FakeClipboard(failWith = swtError)).writeChecked("copy of $SECRET").get(1, TimeUnit.SECONDS)

      logged.any { it.contains(SWTError::class.java.name) } shouldBe true
      logged.forEach { it.contains(SECRET) shouldBe false }
    }

    it("logs a failed hop by exception class name, naming neither the text nor the message") {
      val logged = captureLog()

      writer(FakeClipboard(), onUiThread = { throw swtException })
        .writeChecked("copy of $SECRET")
        .get(1, TimeUnit.SECONDS)

      logged.any { it.contains(SWTException::class.java.name) } shouldBe true
      logged.forEach { it.contains(SECRET) shouldBe false }
    }

    it("logs nothing at all for a write that landed") {
      val logged = captureLog()

      writer(FakeClipboard()).writeChecked("copy of $SECRET").get(1, TimeUnit.SECONDS)

      logged.shouldBeEmpty()
    }
  }
})
