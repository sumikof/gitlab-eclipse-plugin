package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
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
import org.osgi.framework.Bundle
import java.util.concurrent.CompletableFuture

/**
 * Refused by the policy for its userinfo; every other part of it is a search term the log must not
 * contain.
 */
private const val DISGUISED_URL = "https://user:secret@evil.example/p?token=abc#frag"

/** Refused by the policy for its scheme. */
private const val SCRIPT_URL = "javascript:alert('x')"

/** What a relative link or fragment in the webview arrives as: the page's own tokened URL. */
private const val WEBVIEW_SELF_URL = "http://127.0.0.1:5000/webview/security-vuln-details?_csrf=T#x"

private const val WEB_URL = "https://gitlab.com/g/p/-/security/vulnerabilities/1?b=1#c"
private const val MAIL_URL = "mailto:security@example.com"

/** Every term from [DISGUISED_URL] and [SCRIPT_URL] other than their schemes. */
private val FORBIDDEN_IN_LOG = listOf("secret", "evil.example", "token=abc", "frag", "alert", "user")

/**
 * Installs a log that records everything a real [ILog] would write, attached exception messages
 * included (the platform log writes those out too), and returns that recording.
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

class OpenUrlHandlerTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val showDocument = mockk<ShowDocumentLauncher>()
  val launched = mutableListOf<String>()
  var hops = 0

  beforeTest {
    clearMocks(showDocument)
    every { showDocument.show(any()) } returns CompletableFuture.completedFuture(true)
    launched.clear()
    hops = 0
  }

  fun handler(
    launchFailure: Throwable? = null,
    hopFailure: Exception? = null,
  ) = OpenUrlHandler(
    showDocument = showDocument,
    onUiThread = { runnable ->
      hops++
      if (hopFailure != null) throw hopFailure else runnable.run()
    },
    launchProgram = { url ->
      launched += url
      if (launchFailure != null) throw launchFailure
      true
    },
  )

  describe("open") {
    it("hands a web link to the showDocument launcher exactly once, untouched") {
      handler().open(WEB_URL)

      verify(exactly = 1) { showDocument.show(WEB_URL) }
      launched.shouldBeEmpty()
      hops shouldBe 0
    }

    it("launches a mail link on the UI thread, untouched") {
      handler().open(MAIL_URL)

      hops shouldBe 1
      launched.shouldContainExactly(MAIL_URL)
      verify(exactly = 0) { showDocument.show(any()) }
    }

    listOf(DISGUISED_URL, SCRIPT_URL, WEBVIEW_SELF_URL, "file:///etc/passwd", "#frag", "", null).forEach { url ->
      it("touches neither the browser nor the mail client for <$url>") {
        shouldNotThrowAny { handler().open(url) }

        verify(exactly = 0) { showDocument.show(any()) }
        launched.shouldBeEmpty()
        hops shouldBe 0
      }
    }

    it("contains a mail client that throws") {
      shouldNotThrowAny { handler(launchFailure = RuntimeException("no mail client")).open(MAIL_URL) }

      launched.shouldContainExactly(MAIL_URL)
    }

    it("contains a mail client that throws an SWT error") {
      // SWTError extends Error: escaping the asyncExec runnable it would reach the SWT event loop,
      // which logs it with its message.
      shouldNotThrowAny {
        handler(launchFailure = SWTError(SWT.ERROR_FAILED_EXEC, "no mail client")).open(MAIL_URL)
      }

      launched.shouldContainExactly(MAIL_URL)
    }

    it("contains a UI thread that cannot be reached") {
      shouldNotThrowAny { handler(hopFailure = IllegalStateException("workbench is gone")).open(MAIL_URL) }

      launched.shouldBeEmpty()
    }
  }

  describe("log hygiene") {
    afterTest {
      unmockkStatic(Platform::class)
      mockkStatic(Platform::class)
    }

    listOf(DISGUISED_URL to "https", SCRIPT_URL to "javascript").forEach { (url, scheme) ->
      it("names only the scheme of a refused $scheme link") {
        val logged = captureLog()

        handler().open(url)

        logged.shouldContainExactly(
          "openUrl: refused a link from the vulnerability details webview; scheme=$scheme"
        )
        logged.forEach { line -> FORBIDDEN_IN_LOG.forEach { (line.contains(it)) shouldBe false } }
      }
    }

    it("names the exception class but not its message, nor the address, when the mail client threw") {
      val logged = captureLog()

      handler(launchFailure = SWTError(SWT.ERROR_FAILED_EXEC, "could not open $MAIL_URL")).open(MAIL_URL)

      logged.any { it.contains(SWTError::class.java.name) } shouldBe true
      logged.forEach {
        it.contains("could not open") shouldBe false
        it.contains("security@") shouldBe false
        it.contains("example.com") shouldBe false
      }
    }

    it("names the exception class but not its message when the UI thread could not be reached") {
      val logged = captureLog()

      handler(hopFailure = IllegalStateException("no display for $MAIL_URL")).open(MAIL_URL)

      logged.any { it.contains(IllegalStateException::class.java.name) } shouldBe true
      logged.forEach {
        it.contains("no display") shouldBe false
        it.contains("example.com") shouldBe false
      }
    }
  }
})
