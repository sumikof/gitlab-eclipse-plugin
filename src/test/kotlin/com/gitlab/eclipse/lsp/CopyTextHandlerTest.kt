package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.messages.CopyTextParams
import com.gitlab.eclipse.navigation.ClipboardTarget
import com.gitlab.eclipse.navigation.ClipboardWriter
import io.kotest.assertions.throwables.shouldNotThrowAny
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
import org.eclipse.swt.SWTError
import org.eclipse.swt.dnd.DND
import org.osgi.framework.Bundle

/** Appears in every payload this spec sends, so "the log names no text" is one string search. */
private const val SECRET = "SECRETPAYLOAD"

/** Installs a log that records every string handed to it, and returns that recording. */
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

class CopyTextHandlerTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val written = mutableListOf<String>()
  val notices = mutableListOf<String>()

  beforeTest {
    written.clear()
    notices.clear()
  }

  fun clipboard(failing: Boolean = false) = object : ClipboardTarget {
    override fun setText(text: String) {
      if (failing) throw SWTError(DND.ERROR_CANNOT_SET_CLIPBOARD, "clipboard busy")
      written += text
    }

    override fun dispose() = Unit
  }

  fun handler(failing: Boolean = false) = CopyTextHandler(
    clipboard = ClipboardWriter(onUiThread = { it.run() }, openClipboard = { clipboard(failing) }),
    notify = { notices += it },
  )

  describe("handle") {
    it("copies the text the server sent and then says so") {
      handler().handle(CopyTextParams("glab mr view 1"))

      written.shouldContainExactly("glab mr view 1")
      notices.shouldContainExactly(ClipboardWriter.COPIED_TO_CLIPBOARD)
    }

    it("says nothing when the clipboard refused the text") {
      handler(failing = true).handle(CopyTextParams("glab mr view 1"))

      written.shouldBeEmpty()
      notices.shouldBeEmpty()
    }

    it("copies nothing and says nothing when the text is null") {
      shouldNotThrowAny { handler().handle(CopyTextParams(null)) }

      written.shouldBeEmpty()
      notices.shouldBeEmpty()
    }

    it("copies nothing and says nothing when the text is empty") {
      handler().handle(CopyTextParams(""))

      written.shouldBeEmpty()
      notices.shouldBeEmpty()
    }

    it("copies nothing and says nothing when the text is only whitespace") {
      handler().handle(CopyTextParams(" \n\t "))

      written.shouldBeEmpty()
      notices.shouldBeEmpty()
    }

    it("copies text that merely has surrounding whitespace, unchanged") {
      handler().handle(CopyTextParams("  padded  "))

      written.shouldContainExactly("  padded  ")
    }
  }

  describe("log hygiene") {
    // Same teardown as the other captureLog users: restore the bare static mock that
    // LoggingKotestExtension.beforeSpec installed, so its beforeEach keeps stubbing a mocked class.
    afterTest {
      unmockkStatic(Platform::class)
      mockkStatic(Platform::class)
    }

    it("names no text in the log when the write fails") {
      val logged = captureLog()

      handler(failing = true).handle(CopyTextParams("copy of $SECRET"))

      logged.any { it.contains(SWTError::class.java.name) } shouldBe true
      logged.forEach { it.contains(SECRET) shouldBe false }
    }

    it("names no text in the log when the write lands") {
      val logged = captureLog()

      handler().handle(CopyTextParams("copy of $SECRET"))

      logged.forEach { it.contains(SECRET) shouldBe false }
    }
  }
})
