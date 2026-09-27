package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.navigation.BrowserLauncher
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle

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

/** Covers both status-menu URL handlers (design §23: ShowDuoForumTest / ShowDuoDocumentationTest). */
class ShowDuoForumTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val event = mockk<ExecutionEvent>()

  afterEach { clearAllMocks() }

  describe("ShowDuoForum") {
    it("points at the GitLab Duo forum category (constants.ts:18)") {
      ShowDuoForum.URL shouldBe "https://forum.gitlab.com/c/gitlab-duo/52"
    }

    it("opens that URL through the browser launcher exactly once") {
      val browser = mockk<BrowserLauncher>()
      every { browser.open(any()) } just runs

      ShowDuoForum(browser).execute(event)

      verify(exactly = 1) { browser.open("https://forum.gitlab.com/c/gitlab-duo/52") }
      verify(exactly = 1) { browser.open(any()) }
    }

    it("logs a fixed message without the forum URL") {
      val browser = mockk<BrowserLauncher>()
      every { browser.open(any()) } just runs
      val logged = captureLog()

      ShowDuoForum(browser).execute(event)

      logged.shouldContainExactly("Opening GitLab Forum")
      logged.forEach { it.contains("forum.gitlab.com") shouldBe false }
    }
  }

  describe("ShowDuoDocumentation") {
    it("points at the GitLab Duo product documentation (constants.ts:17)") {
      ShowDuoDocumentation.URL shouldBe "https://docs.gitlab.com/user/gitlab_duo/"
    }

    it("opens that URL through the browser launcher exactly once") {
      val browser = mockk<BrowserLauncher>()
      every { browser.open(any()) } just runs

      ShowDuoDocumentation(browser).execute(event)

      verify(exactly = 1) { browser.open("https://docs.gitlab.com/user/gitlab_duo/") }
      verify(exactly = 1) { browser.open(any()) }
    }

    it("logs a fixed message without the documentation URL") {
      val browser = mockk<BrowserLauncher>()
      every { browser.open(any()) } just runs
      val logged = captureLog()

      ShowDuoDocumentation(browser).execute(event)

      logged.shouldContainExactly("Opening GitLab Duo documentation")
      logged.forEach { it.contains("docs.gitlab.com") shouldBe false }
    }
  }
})
