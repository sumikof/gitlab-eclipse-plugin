package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** [retryButtonShown] (L4): the waiting page's Retry button follows the session's Retry state; other buttons do not. */
class JobLogRetryButtonTest : StringSpec({
  val retry = StatusPageModel("This job has been canceled", "", null, PageButton.RETRY)

  "Retry stays while the session can retry" {
    retryButtonShown(retry, canRetry = true) shouldBe retry
  }

  "Retry is dropped while a retry runs, after one succeeded, or without a session" {
    retryButtonShown(retry, canRetry = false) shouldBe StatusPageModel("This job has been canceled", "", null, null)
  }

  "Play and Cancel stay whatever the Retry state" {
    listOf(PageButton.PLAY, PageButton.CANCEL).forEach { button ->
      val model = StatusPageModel("t", "d", "Last checked at 09:05:07", button)
      retryButtonShown(model, canRetry = false) shouldBe model
      retryButtonShown(model, canRetry = true) shouldBe model
    }
  }
})
