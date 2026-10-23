package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.navigation.BrowserLauncher
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

private const val PIPELINE_URL = "https://gitlab.example.com/g/p/-/pipelines/1"
private const val MR_URL = "https://gitlab.example.com/g/p/-/merge_requests/3"

internal fun linkPipeline(url: String?) = PipelineNode(
  GitLabPipeline(id = 1, status = "success", webUrl = url),
  children = emptyList(),
  canRetry = false,
  canCancel = false,
  sourceInstanceUrl = "https://gitlab.example.com",
  sourceAuthFingerprint = "fp0",
)

internal fun linkMergeRequest(url: String) =
  MergeRequestNode(GitLabMergeRequest(id = 1, iid = 3, title = "t", projectId = 9, webUrl = url, state = "opened"))

class OpenSidebarItemInGitLabHandlerTest : StringSpec({
  fun fixture(launches: Boolean = true): Triple<OpenSidebarItemInGitLabHandler, BrowserLauncher, MutableList<String>> {
    val browser = mockk<BrowserLauncher>()
    every { browser.openChecked(any()) } returns launches
    val notices = mutableListOf<String>()
    return Triple(OpenSidebarItemInGitLabHandler(browser = browser, notify = { notices += it }), browser, notices)
  }

  "no selection only notifies" {
    val (handler, browser, notices) = fixture()
    handler.run(null)
    notices shouldContainExactly listOf(SELECT_SIDEBAR_ITEM_MESSAGE)
    verify(exactly = 0) { browser.openChecked(any()) }
  }

  "a node without a web URL only notifies" {
    val (handler, browser, notices) = fixture()
    handler.run(MessageNode("hi"))
    handler.run(linkPipeline(null))
    notices shouldContainExactly listOf(NO_SIDEBAR_LINK_MESSAGE, NO_SIDEBAR_LINK_MESSAGE)
    verify(exactly = 0) { browser.openChecked(any()) }
  }

  "a non-http(s) URL only notifies" {
    val (handler, browser, notices) = fixture()
    handler.run(linkPipeline("file:///etc/passwd"))
    notices shouldContainExactly listOf(NO_SIDEBAR_LINK_MESSAGE)
    verify(exactly = 0) { browser.openChecked(any()) }
  }

  "a node with a web URL opens it once, silently" {
    val (handler, browser, notices) = fixture()
    handler.run(linkPipeline(PIPELINE_URL))
    verify(exactly = 1) { browser.openChecked(PIPELINE_URL) }
    notices.shouldBeEmpty()
  }

  "a merge request opens its own web URL" {
    val (handler, browser, _) = fixture()
    handler.run(linkMergeRequest(MR_URL))
    verify(exactly = 1) { browser.openChecked(MR_URL) }
  }

  "a browser that failed to launch is reported" {
    val (handler, browser, notices) = fixture(launches = false)
    handler.run(linkPipeline(PIPELINE_URL))
    verify(exactly = 1) { browser.openChecked(PIPELINE_URL) }
    notices shouldContainExactly listOf(BROWSER_FAILED_MESSAGE)
  }
})
