package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.navigation.BrowserLauncher
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
import io.mockk.verify
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Platform
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTError
import org.eclipse.ui.PartInitException
import org.osgi.framework.Bundle
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Stands in for the signature or token a `window/showDocument` URI can carry. It appears in the
 * query of every URI the log-hygiene block sends, and in the message of every exception thrown at
 * the launcher, so "the log leaks nothing" is one string search.
 */
private const val SECRET = "SUPERSECRET"

/** Host of every URI in this spec: it must not reach the log either, and is one more search term. */
private const val HOST = "example.com"

/** Refused for its scheme alone — it has a host and no userinfo, so no other guard can reject it. */
private const val REFUSED_URL = "javascript://$HOST/x?token=$SECRET"

/** Accepted by the policy; the browser is what fails on it in the failure cases. */
private const val ACCEPTED_URL = "https://$HOST/x?token=$SECRET"

/**
 * Installs a log that records everything a real [ILog] would write, and returns that recording.
 *
 * **The throwable arm of each overload is recorded too, not just the message.** The platform log
 * writes an attached exception's own message into the log file, so a line built as
 * `log.warn("… ${e.javaClass.name}", e)` — the one-token change someone makes when they want a
 * stack trace — leaks whatever that exception says, which for a browser failure is the whole URL.
 * A capture that read only the first argument would call that regression green.
 */
private fun captureLog(): List<String> {
  val recorded = mutableListOf<String>()
  val log = mockk<ILog>()
  val message = slot<String>()
  val status = slot<IStatus>()
  every { log.error(capture(message)) } answers { recorded += message.captured }
  every { log.error(any<String>(), any()) } answers {
    recorded += "${arg<String>(0)} ${arg<Throwable?>(1)?.message}"
  }
  every { log.warn(capture(message)) } answers { recorded += message.captured }
  every { log.warn(any<String>(), any()) } answers {
    recorded += "${arg<String>(0)} ${arg<Throwable?>(1)?.message}"
  }
  every { log.info(capture(message)) } answers { recorded += message.captured }
  every { log.info(any<String>(), any()) } answers {
    recorded += "${arg<String>(0)} ${arg<Throwable?>(1)?.message}"
  }
  every { log.log(capture(status)) } answers {
    recorded += "${status.captured.message} ${status.captured.exception?.message}"
  }
  every { Platform.getLog(any<Bundle>()) } returns log
  every { Platform.getLog(any<Class<*>>()) } returns log
  return recorded
}

class ShowDocumentLauncherTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val opened = mutableListOf<String>()
  val notices = mutableListOf<String>()

  beforeTest {
    opened.clear()
    notices.clear()
  }

  /**
   * A real [ShowDocumentLauncher] over a real [BrowserLauncher] gate whose three seams are fakes. The browser fake
   * records what reached it by `toExternalForm()` (`URL.equals` resolves hosts over DNS). The production browser
   * signals "did not launch" only by throwing (`IWebBrowser.openURL` returns `void`), so `launched = false` is a
   * browser that received the URL and then threw.
   */
  fun launcher(
    launched: Boolean = true,
    browserFailure: Throwable? = null,
    hopFailure: Exception? = null,
  ) = ShowDocumentLauncher(
    BrowserLauncher(
      onUiThread = { runnable -> if (hopFailure != null) throw hopFailure else runnable.run() },
      openInBrowser = { url ->
        opened += url.toExternalForm()
        if (browserFailure != null) throw browserFailure
        if (!launched) throw PartInitException("the browser declined")
      },
      notify = { notices += it },
    ),
  )

  describe("show") {
    it("opens an allowed https URI in the external browser and reports success") {
      val outcome = launcher().show("https://gitlab.com/g/p/-/issues/1")

      outcome.get(1, TimeUnit.SECONDS) shouldBe true
      opened.shouldContainExactly("https://gitlab.com/g/p/-/issues/1")
    }

    it("passes the URI through untouched, query and fragment included") {
      launcher().show("https://gitlab.com/x?token=abc#frag").get(1, TimeUnit.SECONDS) shouldBe true

      opened.shouldContainExactly("https://gitlab.com/x?token=abc#frag")
    }

    it("keeps a javascript URI away from the browser even when it carries a host") {
      val outcome = launcher().show("javascript://$HOST/%0Aalert(1)")

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
      opened.shouldBeEmpty()
    }

    it("keeps a file URI away from the browser even when it carries a host") {
      val outcome = launcher().show("file://localhost/etc/passwd")

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
      opened.shouldBeEmpty()
    }

    it("keeps a URI whose userinfo disguises the real host away from the browser") {
      val outcome = launcher().show("https://gitlab.com@evil.example/x")

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
      opened.shouldBeEmpty()
    }

    it("opens nothing when the server sent no URI") {
      val outcome = shouldNotThrowAny { launcher().show(null) }

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
      opened.shouldBeEmpty()
    }

    it("opens nothing when the URI is blank") {
      val outcome = launcher().show("   ")

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
      opened.shouldBeEmpty()
    }

    it("hands a null URI to the gate as null, not as an empty string") {
      val browser = mockk<BrowserLauncher>()
      every { browser.openAsync(null) } returns CompletableFuture.completedFuture(false)

      ShowDocumentLauncher(browser).show(null).get(1, TimeUnit.SECONDS) shouldBe false

      verify(exactly = 1) { browser.openAsync(null) }
      verify(exactly = 1) { browser.openAsync(any()) }
    }

    it("does not trim a URI padded with whitespace: the gate refuses it and nothing reaches the browser") {
      val outcome = launcher().show(" https://gitlab.com/x ")

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
      opened.shouldBeEmpty()
    }

    it("hands a padded URI to the gate exactly as received") {
      val browser = mockk<BrowserLauncher>()
      every { browser.openAsync(any()) } returns CompletableFuture.completedFuture(false)

      ShowDocumentLauncher(browser).show(" https://gitlab.com/x ")

      verify(exactly = 1) { browser.openAsync(" https://gitlab.com/x ") }
    }

    it("defaults to the production BrowserLauncher gate, not a stand-in") {
      ShowDocumentLauncher().browser.javaClass shouldBe BrowserLauncher::class.java
    }

    it("reports failure when the browser declined the URI") {
      val outcome = launcher(launched = false).show(ACCEPTED_URL)

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
      opened.shouldContainExactly(ACCEPTED_URL)
    }

    it("reports failure, and lets nothing escape, when the browser call threw") {
      val outcome = shouldNotThrowAny {
        launcher(browserFailure = PartInitException("no browser")).show(ACCEPTED_URL)
      }

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
    }

    it("reports failure, and lets nothing escape, when the browser call threw an SWT error") {
      // SWTError extends Error, not Exception. Inside an asyncExec runnable an escaping Error
      // reaches the SWT event loop, which logs it WITH its message and may raise the internal-error
      // dialog — the exact log path this class exists to prevent, and one it cannot reach from there.
      val outcome = shouldNotThrowAny {
        launcher(browserFailure = SWTError(SWT.ERROR_FAILED_EXEC, "no browser")).show(ACCEPTED_URL)
      }

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
    }

    it("reports failure, and lets nothing escape, when the UI thread could not be reached") {
      val outcome = shouldNotThrowAny {
        launcher(hopFailure = IllegalStateException("workbench is gone")).show(ACCEPTED_URL)
      }

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
      opened.shouldBeEmpty()
    }

    it("never shows the user a notice, whatever the outcome: the server is the one told") {
      fun outcomeOf(launcher: ShowDocumentLauncher, uri: String?) = launcher.show(uri).get(1, TimeUnit.SECONDS)

      outcomeOf(launcher(), ACCEPTED_URL) shouldBe true
      outcomeOf(launcher(), REFUSED_URL) shouldBe false
      outcomeOf(launcher(), null) shouldBe false
      outcomeOf(launcher(launched = false), ACCEPTED_URL) shouldBe false
      outcomeOf(launcher(browserFailure = SWTError(SWT.ERROR_FAILED_EXEC, "x")), ACCEPTED_URL) shouldBe false
      outcomeOf(launcher(hopFailure = IllegalStateException("gone")), ACCEPTED_URL) shouldBe false

      notices.shouldBeEmpty()
    }
  }

  describe("log hygiene") {
    // Same teardown as the other captureLog users: restore the bare static mock that
    // LoggingKotestExtension.beforeSpec installed, so its beforeEach keeps stubbing a mocked class.
    afterTest {
      unmockkStatic(Platform::class)
      mockkStatic(Platform::class)
    }

    it("refuses through the production default gate before any UI hop") {
      val logged = captureLog()

      // A refusal needs no UI hop, so the real default gate can be exercised headless.
      ShowDocumentLauncher().show(REFUSED_URL).get(1, TimeUnit.SECONDS) shouldBe false

      logged.any { it.contains("refused a link outside") && it.contains("reason=DISALLOWED_SCHEME") } shouldBe true
      logged.any { it.contains("scheme=") || it.contains("javascript") } shouldBe false
      logged.any { it.contains("could not reach the UI thread") } shouldBe false
    }

    it("names only the refusal reason, not the scheme or any part of the URI, when the policy rejects it") {
      val logged = captureLog()

      launcher().show(REFUSED_URL).get(1, TimeUnit.SECONDS) shouldBe false

      logged.any { it.contains("reason=DISALLOWED_SCHEME") } shouldBe true
      logged.any { it.contains("scheme=") || it.contains("javascript") } shouldBe false
      // Review H8: page clicks reach this too, so the line must not name the language server as the source.
      logged.any { it.contains("language server") } shouldBe false
      logged.forEach {
        it.contains(SECRET) shouldBe false
        it.contains(HOST) shouldBe false
        it.contains("token=") shouldBe false
        it.contains(REFUSED_URL) shouldBe false
      }
    }

    it("names no part of the URI, and no exception message, when the browser call threw") {
      val logged = captureLog()
      val failure = PartInitException("could not open $ACCEPTED_URL")

      launcher(browserFailure = failure).show(ACCEPTED_URL).get(1, TimeUnit.SECONDS) shouldBe false

      logged.any { it.contains(PartInitException::class.java.name) } shouldBe true
      logged.forEach {
        it.contains(SECRET) shouldBe false
        it.contains(HOST) shouldBe false
        it.contains("token=") shouldBe false
        it.contains("could not open") shouldBe false
      }
    }

    it("names no part of the URI, and no error message, when the browser call threw an SWT error") {
      val logged = captureLog()
      val failure = SWTError(SWT.ERROR_FAILED_EXEC, "could not open $ACCEPTED_URL")

      launcher(browserFailure = failure).show(ACCEPTED_URL).get(1, TimeUnit.SECONDS) shouldBe false

      logged.any { it.contains(SWTError::class.java.name) } shouldBe true
      logged.forEach {
        it.contains(SECRET) shouldBe false
        it.contains(HOST) shouldBe false
        it.contains("could not open") shouldBe false
      }
    }

    it("names no part of the URI when the UI-thread hop itself threw") {
      val logged = captureLog()
      val failure = IllegalStateException("no display for $ACCEPTED_URL")

      launcher(hopFailure = failure).show(ACCEPTED_URL).get(1, TimeUnit.SECONDS) shouldBe false

      logged.any { it.contains(IllegalStateException::class.java.name) } shouldBe true
      logged.forEach {
        it.contains(SECRET) shouldBe false
        it.contains(HOST) shouldBe false
        it.contains("no display") shouldBe false
      }
    }

    it("names no part of the URI when the browser simply declined it") {
      val logged = captureLog()

      launcher(launched = false).show(ACCEPTED_URL).get(1, TimeUnit.SECONDS) shouldBe false

      logged.forEach {
        it.contains(SECRET) shouldBe false
        it.contains(HOST) shouldBe false
      }
    }
  }
})
