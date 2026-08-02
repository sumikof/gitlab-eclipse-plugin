package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.CiLintResult
import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class CiLintServiceTest : DescribeSpec({
  val apiClient = mockk<GitLabApiClient>()
  val service = CiLintService(apiClient)

  val connection = ConnectionSnapshot(
    instanceUrl = "https://x.example/",
    token = "tok",
    authFingerprint = "fp",
    configGeneration = 1L,
  )

  beforeEach { clearMocks(apiClient) }

  describe("validate") {
    it("posts to /projects/{projectId}/ci/lint with the connection") {
      val path = slot<String>()
      every { apiClient.postJson(capture(path), any(), any()) } returns """{"valid":true}"""

      service.validate(connection, "42", "yaml")

      path.captured shouldBe "/projects/42/ci/lint"
      verify(exactly = 1) { apiClient.postJson("/projects/42/ci/lint", any(), connection) }
    }

    it("passes an already-encoded projectId through by plain concatenation") {
      val path = slot<String>()
      every { apiClient.postJson(capture(path), any(), any()) } returns """{"valid":true}"""

      service.validate(connection, "group%2Fproject", "yaml")

      path.captured shouldBe "/projects/group%2Fproject/ci/lint"
    }

    it("sends a Gson-escaped {content: ...} JSON body") {
      val body = slot<String>()
      every { apiClient.postJson(any(), capture(body), any()) } returns """{"valid":true}"""
      val content = "a: \"b\"\nc"

      service.validate(connection, "42", content)

      body.captured shouldBe Gson().toJson(mapOf("content" to content))
    }

    it("normalizes valid:true + merged_yaml into mergedYaml") {
      every { apiClient.postJson(any(), any(), any()) } returns
        """{"valid":true,"merged_yaml":"merged: x"}"""

      val result = service.validate(connection, "42", "yaml")

      result shouldBe CiLintResult(valid = true, mergedYaml = "merged: x", errors = emptyList())
    }

    it("normalizes valid:false + errors into the errors list") {
      every { apiClient.postJson(any(), any(), any()) } returns
        """{"valid":false,"errors":["e1","e2"]}"""

      val result = service.validate(connection, "42", "yaml")

      result shouldBe CiLintResult(valid = false, mergedYaml = null, errors = listOf("e1", "e2"))
    }

    it("normalizes a response with all fields missing without throwing NPE") {
      every { apiClient.postJson(any(), any(), any()) } returns "{}"

      val result = service.validate(connection, "42", "yaml")

      result shouldBe CiLintResult(valid = false, mergedYaml = null, errors = emptyList())
    }

    it("normalizes a response with only valid present, defaulting errors to empty") {
      every { apiClient.postJson(any(), any(), any()) } returns """{"valid":true}"""

      val result = service.validate(connection, "42", "yaml")

      result shouldBe CiLintResult(valid = true, mergedYaml = null, errors = emptyList())
    }

    it("propagates GitLabApiException from the API client without swallowing it") {
      every { apiClient.postJson(any(), any(), any()) } throws GitLabApiException(422, "invalid", "corr-1")

      shouldThrow<GitLabApiException> { service.validate(connection, "42", "yaml") }
    }
  }
})
