package com.gitlab.eclipse.api

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class GitLabConfigurationExceptionTest : DescribeSpec({
  it("carries a user-safe message") {
    val e = GitLabConfigurationException("Client certificate and key must both be set.")
    e.message shouldBe "Client certificate and key must both be set."
  }
})
