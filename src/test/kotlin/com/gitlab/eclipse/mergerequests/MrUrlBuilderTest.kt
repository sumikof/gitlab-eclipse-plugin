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
  "new MR url encodes ampersand and slash in source branch" {
    MrUrlBuilder.newMergeRequestUrl("https://h/g/p", "feature/a&b") shouldBe
      "https://h/g/p/-/merge_requests/new?merge_request%5Bsource_branch%5D=feature%2Fa%26b"
  }
  "new MR url encodes hash in source branch" {
    MrUrlBuilder.newMergeRequestUrl("https://h/g/p", "a#b") shouldBe
      "https://h/g/p/-/merge_requests/new?merge_request%5Bsource_branch%5D=a%23b"
  }
  "new MR url encodes plus in source branch" {
    MrUrlBuilder.newMergeRequestUrl("https://h/g/p", "a+b") shouldBe
      "https://h/g/p/-/merge_requests/new?merge_request%5Bsource_branch%5D=a%2Bb"
  }
  "new MR url trims trailing slash on project web url" {
    MrUrlBuilder.newMergeRequestUrl("https://h/g/p/", "main") shouldBe
      "https://h/g/p/-/merge_requests/new?merge_request%5Bsource_branch%5D=main"
  }
  "compare url encodes slash in ref as path segment and keeps ellipsis literal" {
    MrUrlBuilder.compareUrl("https://h/g/p", "release/1.0", "abc123") shouldBe
      "https://h/g/p/-/compare/release%2F1.0...abc123"
  }
  "compare url encodes space in ref as percent-20 not plus" {
    MrUrlBuilder.compareUrl("https://h/g/p", "feature branch", "abc123") shouldBe
      "https://h/g/p/-/compare/feature%20branch...abc123"
  }
})
