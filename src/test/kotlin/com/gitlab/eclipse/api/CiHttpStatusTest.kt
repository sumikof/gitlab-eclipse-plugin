package com.gitlab.eclipse.api

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class CiHttpStatusTest : DescribeSpec({
  describe("isAccessDenied") {
    it("returns true for a 403 GitLabApiException") {
      CiHttpStatus.isAccessDenied(GitLabApiException(403, "forbidden")) shouldBe true
    }

    it("returns true for a 404 GitLabApiException") {
      CiHttpStatus.isAccessDenied(GitLabApiException(404, "not found")) shouldBe true
    }

    it("returns false for a 500 GitLabApiException") {
      CiHttpStatus.isAccessDenied(GitLabApiException(500, "server error")) shouldBe false
    }

    it("returns false for a non-GitLabApiException throwable") {
      CiHttpStatus.isAccessDenied(RuntimeException("boom")) shouldBe false
    }
  }
})
