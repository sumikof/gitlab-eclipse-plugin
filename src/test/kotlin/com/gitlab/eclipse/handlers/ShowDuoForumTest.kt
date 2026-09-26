package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.navigation.BrowserLauncher
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.eclipse.core.commands.ExecutionEvent

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
  }
})
