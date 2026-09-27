package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.ShowDocumentLauncher
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle
import java.util.concurrent.CompletableFuture

/**
 * Installs a log that records every info message a real [ILog] would write, mirroring
 * `OpenUrlHandlerTest.captureLog`.
 */
private fun captureLog(): List<String> {
  val recorded = mutableListOf<String>()
  val log = mockk<ILog>(relaxUnitFun = true)
  val message = slot<String>()
  every { log.info(capture(message)) } answers { recorded += message.captured }
  every { Platform.getLog(any<Bundle>()) } returns log
  every { Platform.getLog(any<Class<*>>()) } returns log
  return recorded
}

/**
 * Records every message a real [ILog] would write, at any level — mirroring
 * `ShowDocumentLauncherTest.captureLog`. Needed for the failure-path case: [ShowDocumentLauncher]
 * logs its own failure with `log.warn`, on its own logger, not the handler's `log.info`.
 */
private fun captureAllLog(): List<String> {
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

/**
 * Throws a deliberately generic [RuntimeException] carrying a URL, mirroring the exact call the
 * Codex review round-3 fix required: prove neither handler leaks it. Suppression is scoped to this
 * helper only, following `DiscussionWriteLauncherTest.throwRuntime`.
 */
@Suppress("TooGenericExceptionThrown")
private fun throwRuntime(message: String): Nothing = throw RuntimeException(message)

/** Covers both status-menu URL handlers (design §23: ShowDuoForumTest / ShowDuoDocumentationTest). */
class ShowDuoForumTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val event = mockk<ExecutionEvent>()

  afterEach { clearAllMocks() }

  describe("ShowDuoForum") {
    it("points at the GitLab Duo forum category (constants.ts:18)") {
      ShowDuoForum.URL shouldBe "https://forum.gitlab.com/c/gitlab-duo/52"
    }

    it("opens that URL through the URI-free launcher exactly once") {
      val launcher = mockk<ShowDocumentLauncher>()
      every { launcher.show(any()) } returns CompletableFuture.completedFuture(true)

      ShowDuoForum(launcher).execute(event)

      verify(exactly = 1) { launcher.show("https://forum.gitlab.com/c/gitlab-duo/52") }
      verify(exactly = 1) { launcher.show(any()) }
    }

    it("logs a fixed message without the forum URL") {
      val launcher = mockk<ShowDocumentLauncher>()
      every { launcher.show(any()) } returns CompletableFuture.completedFuture(true)
      val logged = captureLog()

      ShowDuoForum(launcher).execute(event)

      logged.shouldContainExactly("Opening GitLab Forum")
      logged.forEach { it.contains("forum.gitlab.com") shouldBe false }
    }
  }

  describe("ShowDuoDocumentation") {
    it("points at the GitLab Duo product documentation (constants.ts:17)") {
      ShowDuoDocumentation.URL shouldBe "https://docs.gitlab.com/user/gitlab_duo/"
    }

    it("opens that URL through the URI-free launcher exactly once") {
      val launcher = mockk<ShowDocumentLauncher>()
      every { launcher.show(any()) } returns CompletableFuture.completedFuture(true)

      ShowDuoDocumentation(launcher).execute(event)

      verify(exactly = 1) { launcher.show("https://docs.gitlab.com/user/gitlab_duo/") }
      verify(exactly = 1) { launcher.show(any()) }
    }

    it("logs a fixed message without the documentation URL") {
      val launcher = mockk<ShowDocumentLauncher>()
      every { launcher.show(any()) } returns CompletableFuture.completedFuture(true)
      val logged = captureLog()

      ShowDuoDocumentation(launcher).execute(event)

      logged.shouldContainExactly("Opening GitLab Duo documentation")
      logged.forEach { it.contains("docs.gitlab.com") shouldBe false }
    }
  }

  describe("real ShowDocumentLauncher failure path (no URL leak)") {
    // Same teardown as ShowDocumentLauncherTest's log-hygiene block: restore the bare static mock
    // that LoggingKotestExtension.beforeSpec installed, so its beforeEach keeps stubbing a mocked
    // class for the next test.
    afterTest {
      unmockkStatic(Platform::class)
      mockkStatic(Platform::class)
    }

    fun throwingLauncher() =
      ShowDocumentLauncher(
        onUiThread = { it.run() },
        openInBrowser = { throwRuntime("boom https://forum.gitlab.com/c/gitlab-duo/52") },
      )

    it("ShowDuoForum: leaks neither the URL nor the exception message when the browser throws") {
      val logged = captureAllLog()

      ShowDuoForum(throwingLauncher()).execute(event)

      logged.isEmpty() shouldBe false
      logged.forEach {
        it.contains("forum.gitlab.com") shouldBe false
        it.contains("docs.gitlab.com") shouldBe false
        it.contains("boom") shouldBe false
      }
    }

    it("ShowDuoDocumentation: leaks neither the URL nor the exception message when the browser throws") {
      val logged = captureAllLog()

      ShowDuoDocumentation(throwingLauncher()).execute(event)

      logged.isEmpty() shouldBe false
      logged.forEach {
        it.contains("forum.gitlab.com") shouldBe false
        it.contains("docs.gitlab.com") shouldBe false
        it.contains("boom") shouldBe false
      }
    }
  }
})
