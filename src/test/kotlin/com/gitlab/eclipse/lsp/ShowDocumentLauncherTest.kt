package com.gitlab.eclipse.lsp

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
import org.eclipse.ui.PartInitException
import org.osgi.framework.Bundle
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

class ShowDocumentLauncherTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val opened = mutableListOf<String>()

  beforeTest {
    opened.clear()
  }

  fun launcher(
    launched: Boolean = true,
    browserFailure: Exception? = null,
    hopFailure: Exception? = null,
  ) = ShowDocumentLauncher(
    onUiThread = { runnable -> if (hopFailure != null) throw hopFailure else runnable.run() },
    openInBrowser = { url ->
      opened += url
      if (browserFailure != null) throw browserFailure
      launched
    },
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

    it("reports failure, and lets nothing escape, when the UI thread could not be reached") {
      val outcome = shouldNotThrowAny {
        launcher(hopFailure = IllegalStateException("workbench is gone")).show(ACCEPTED_URL)
      }

      outcome.get(1, TimeUnit.SECONDS) shouldBe false
      opened.shouldBeEmpty()
    }
  }

  describe("log hygiene") {
    // Same teardown as the other captureLog users: restore the bare static mock that
    // LoggingKotestExtension.beforeSpec installed, so its beforeEach keeps stubbing a mocked class.
    afterTest {
      unmockkStatic(Platform::class)
      mockkStatic(Platform::class)
    }

    it("names the refused scheme but no part of the URI when the policy rejects it") {
      val logged = captureLog()

      launcher().show(REFUSED_URL).get(1, TimeUnit.SECONDS) shouldBe false

      logged.any { it.contains("javascript") } shouldBe true
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
