package com.gitlab.eclipse.mergerequests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class MrUrlBuilderTest : StringSpec({
  "assigned MR url" {
    MrUrlBuilder.assignedMergeRequestsUrl("https://gitlab.com/g/p", 7L) shouldBe
      "https://gitlab.com/g/p/-/merge_requests?assignee_id=7"
  }
  "trims trailing slash" {
    MrUrlBuilder.assignedMergeRequestsUrl("https://gitlab.com/g/p/", 7L) shouldBe
      "https://gitlab.com/g/p/-/merge_requests?assignee_id=7"
  }
})
