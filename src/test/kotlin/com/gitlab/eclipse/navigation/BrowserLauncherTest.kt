package com.gitlab.eclipse.navigation

import com.gitlab.eclipse.extensions.LoggingKotestExtension
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
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTError
import org.eclipse.swt.SWTException
import org.eclipse.ui.PartInitException
import org.osgi.framework.Bundle
import java.util.concurrent.TimeUnit

/** Planted in every part of a link a log line could leak: query, fragment, userinfo, host, scheme, exception message. */
private const val SECRET = "SUPERSECRET"

private const val ALLOWED = "https://gitlab.com/g/p/-/issues/1?a=b#frag"

/** Refused for its scheme alone: it has a host and no userinfo. */
private const val REFUSED = "javascript://gitlab.com/%0Aalert(1)"

/**
 * Installs a log that records everything a real [ILog] would write, the throwable's message included (the platform
 * log writes an attached exception's message too), and returns that recording. Same shape as `ShowDocumentLauncherTest`.
 */
private fun captureLog(): List<String> {
  val recorded = mutableListOf<String>()
  val log = mockk<ILog>()
  val message = slot<String>()
  val status = slot<IStatus>()
  every { log.error(capture(message)) } answers { recorded += message.captured }
  every { log.error(any<String>(), any()) } answers { recorded += "${arg<String>(0)} ${arg<Throwable?>(1)?.message}" }
  every { log.warn(capture(message)) } answers { recorded += message.captured }
  every { log.warn(any<String>(), any()) } answers { recorded += "${arg<String>(0)} ${arg<Throwable?>(1)?.message}" }
  every { log.info(capture(message)) } answers { recorded += message.captured }
  every { log.info(any<String>(), any()) } answers { recorded += "${arg<String>(0)} ${arg<Throwable?>(1)?.message}" }
  every { log.log(capture(status)) } answers {
    recorded += "${status.captured.message} ${status.captured.exception?.message}"
  }
  every { Platform.getLog(any<Bundle>()) } returns log
  every { Platform.getLog(any<Class<*>>()) } returns log
  return recorded
}

class BrowserLauncherTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  var hops = 0
  val opened = mutableListOf<String>()
  val notices = mutableListOf<String>()

  beforeTest {
    hops = 0
    opened.clear()
    notices.clear()
  }

  fun launcher(browserFailure: Throwable? = null, hopFailure: Exception? = null) = BrowserLauncher(
    onUiThread = { runnable ->
      hops++
      if (hopFailure != null) throw hopFailure
      runnable.run()
    },
    openInBrowser = { url ->
      // toExternalForm, never the URL itself: URL.equals resolves the host over DNS.
      opened += url.toExternalForm()
      if (browserFailure != null) throw browserFailure
    },
    notify = { notices += it },
  )

  describe("open") {
    it("opens an allowed link inside the one UI-thread hop, untouched, without notifying") {
      launcher().open(ALLOWED)

      hops shouldBe 1
      opened.shouldContainExactly(ALLOWED)
      notices.shouldBeEmpty()
    }

    it("refuses a disallowed link before the hop and tells the user once") {
      launcher().open(REFUSED)

      hops shouldBe 0
      opened.shouldBeEmpty()
      notices.shouldContainExactly(BROWSER_OPEN_FAILED_MESSAGE)
    }

    listOf(
      PartInitException("could not open $ALLOWED"),
      SWTError(SWT.ERROR_FAILED_EXEC, "could not open $ALLOWED"),
    ).forEach { failure ->
      it("contains a browser that throws ${failure.javaClass.simpleName} and tells the user once") {
        shouldNotThrowAny { launcher(browserFailure = failure).open(ALLOWED) }

        opened.shouldContainExactly(ALLOWED)
        notices.shouldContainExactly(BROWSER_OPEN_FAILED_MESSAGE)
      }
    }

    listOf(IllegalStateException("workbench is gone"), SWTException("Device is disposed")).forEach { failure ->
      it("contains a UI hop that throws ${failure.javaClass.simpleName} and tells the user once") {
        shouldNotThrowAny { launcher(hopFailure = failure).open(ALLOWED) }

        opened.shouldBeEmpty()
        notices.shouldContainExactly(BROWSER_OPEN_FAILED_MESSAGE)
      }
    }
  }

  describe("openChecked") {
    it("opens an allowed link synchronously, without a hop, and reports true") {
      launcher().openChecked(ALLOWED) shouldBe true

      hops shouldBe 0
      opened.shouldContainExactly(ALLOWED)
      notices.shouldBeEmpty()
    }

    it("refuses a disallowed link and reports false without notifying") {
      launcher().openChecked(REFUSED) shouldBe false

      hops shouldBe 0
      opened.shouldBeEmpty()
      notices.shouldBeEmpty()
    }

    listOf(
      PartInitException("could not open $ALLOWED"),
      SWTError(SWT.ERROR_FAILED_EXEC, "could not open $ALLOWED"),
    ).forEach { failure ->
      it("reports false when the browser throws ${failure.javaClass.simpleName}, leaving the notice to the caller") {
        shouldNotThrowAny { launcher(browserFailure = failure).openChecked(ALLOWED) } shouldBe false

        notices.shouldBeEmpty()
      }
    }
  }

  describe("openAsync") {
    it("completes true once the allowed link opened inside the hop") {
      val outcome = launcher().openAsync(ALLOWED)

      outcome.get(1, TimeUnit.SECONDS) shouldBe true
      hops shouldBe 1
      opened.shouldContainExactly(ALLOWED)
      notices.shouldBeEmpty()
    }

    listOf(REFUSED, null, "   ").forEach { url ->
      it("completes false without a hop for the refused link ${url?.let { "\"$it\"" } ?: "null"}") {
        val outcome = launcher().openAsync(url)

        outcome.get(1, TimeUnit.SECONDS) shouldBe false
        outcome.isCompletedExceptionally shouldBe false
        hops shouldBe 0
        opened.shouldBeEmpty()
        notices.shouldBeEmpty()
      }
    }

    listOf(
      PartInitException("could not open $ALLOWED"),
      SWTError(SWT.ERROR_FAILED_EXEC, "could not open $ALLOWED"),
    ).forEach { failure ->
      it("completes false, never exceptionally, when the browser throws ${failure.javaClass.simpleName}") {
        val outcome = shouldNotThrowAny { launcher(browserFailure = failure).openAsync(ALLOWED) }

        outcome.get(1, TimeUnit.SECONDS) shouldBe false
        outcome.isCompletedExceptionally shouldBe false
        notices.shouldBeEmpty()
      }
    }

    listOf(IllegalStateException("workbench is gone"), SWTException("Device is disposed")).forEach { failure ->
      it("completes false, never exceptionally, when the UI hop throws ${failure.javaClass.simpleName}") {
        val outcome = shouldNotThrowAny { launcher(hopFailure = failure).openAsync(ALLOWED) }

        outcome.get(1, TimeUnit.SECONDS) shouldBe false
        outcome.isCompletedExceptionally shouldBe false
        opened.shouldBeEmpty()
        notices.shouldBeEmpty()
      }
    }
  }

  describe("log hygiene") {
    // Restore the bare static mock LoggingKotestExtension.beforeSpec installed, so its beforeEach keeps working.
    afterTest {
      unmockkStatic(Platform::class)
      mockkStatic(Platform::class)
    }

    fun List<String>.shouldLeakNothingOf(url: String) = forEach {
      it.contains(SECRET) shouldBe false
      it.contains("token=") shouldBe false
      it.contains(url) shouldBe false
    }

    listOf(
      "https://u:$SECRET@gitlab.com/x" to "reason=USERINFO, scheme=https",
      "glpat-$SECRET:x" to "reason=DISALLOWED_SCHEME, scheme=other",
      "javascript://$SECRET.example/x?token=$SECRET#$SECRET" to "reason=DISALLOWED_SCHEME, scheme=javascript",
      "https://$SECRET.example/a b?token=$SECRET" to "reason=UNPARSEABLE, scheme=unparseable",
    ).forEach { (url, expected) ->
      it("names only the refusal reason and scheme label for \"$expected\"") {
        val logged = captureLog()

        launcher().open(url)
        launcher().openChecked(url)
        launcher().openAsync(url)

        logged.size shouldBe 3
        logged.forEach { it.contains(expected) shouldBe true }
        logged.shouldLeakNothingOf(url)
      }
    }

    it("names the browser failure by exception class, never its message or the link") {
      val logged = captureLog()
      val url = "https://$SECRET.example/x?token=$SECRET#$SECRET"
      val failure = PartInitException("could not open $url")

      launcher(browserFailure = failure).open(url)
      launcher(browserFailure = SWTError(SWT.ERROR_FAILED_EXEC, "could not open $url")).openChecked(url)
      launcher(browserFailure = failure).openAsync(url)

      logged.size shouldBe 3
      logged.count { it.contains(PartInitException::class.java.name) } shouldBe 2
      logged.count { it.contains(SWTError::class.java.name) } shouldBe 1
      logged.shouldLeakNothingOf(url)
    }

    it("names the UI hop failure by exception class, never its message or the link") {
      val logged = captureLog()
      val url = "https://$SECRET.example/x?token=$SECRET#$SECRET"

      launcher(hopFailure = IllegalStateException("gone while opening $url")).open(url)
      launcher(hopFailure = SWTException("disposed while opening $url")).openAsync(url)

      logged.size shouldBe 2
      logged.count { it.contains(IllegalStateException::class.java.name) } shouldBe 1
      logged.count { it.contains(SWTException::class.java.name) } shouldBe 1
      logged.shouldLeakNothingOf(url)
    }

    it("logs nothing when every entry point opens the link") {
      val logged = captureLog()

      launcher().open(ALLOWED)
      launcher().openChecked(ALLOWED) shouldBe true
      launcher().openAsync(ALLOWED).get(1, TimeUnit.SECONDS) shouldBe true

      opened.size shouldBe 3
      logged.shouldBeEmpty()
    }

    it("keeps the notice a fixed text, never the link") {
      launcher().open("javascript://$SECRET.example/x?token=$SECRET")

      notices.shouldContainExactly(BROWSER_OPEN_FAILED_MESSAGE)
      notices.forEach { it.contains(SECRET) shouldBe false }
    }
  }
})
