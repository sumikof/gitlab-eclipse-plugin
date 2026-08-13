package com.gitlab.eclipse.api

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.snippets.SnippetPayload
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class SnippetServiceTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val payload = SnippetPayload(
    title = "Foo.kt",
    fileName = "Foo.kt",
    visibility = "private",
    content = "hello\n",
  )
  val connection = ConnectionSnapshot("https://gitlab.example.com", "tok-123", "fp-1", 1L)

  describe("create") {
    it("posts the snake_case body to the project's snippet path and returns web_url") {
      val apiClient = mockk<GitLabApiClient>()
      every { apiClient.captureConnectionIf(any()) } returns connection
      val path = slot<String>()
      val body = slot<String>()
      every { apiClient.postJson(capture(path), capture(body), connection) } returns
        """{"web_url":"https://gitlab.example.com/-/snippets/7"}"""

      val webUrl = SnippetService(apiClient).create(4711L, payload) { true }

      webUrl shouldBe "https://gitlab.example.com/-/snippets/7"
      path.captured shouldBe "/projects/4711/snippets"
      body.captured shouldBe
        """{"title":"Foo.kt","file_name":"Foo.kt","visibility":"private","content":"hello\n"}"""
    }

    it("sends nothing when the connection gate rejects the instance url") {
      // captureConnectionIf matches the url INSIDE the seqlock and BEFORE reading any credential,
      // so a mismatch must not reach postJson at all (issue #49).
      val apiClient = mockk<GitLabApiClient>()
      every { apiClient.captureConnectionIf(any()) } returns null

      val webUrl = SnippetService(apiClient).create(4711L, payload) { false }

      webUrl shouldBe null
      verify(exactly = 0) { apiClient.postJson(any(), any(), any()) }
    }

    it("returns null when the response carries no web_url") {
      val apiClient = mockk<GitLabApiClient>()
      every { apiClient.captureConnectionIf(any()) } returns connection
      every { apiClient.postJson(any(), any(), connection) } returns """{"id":7}"""

      SnippetService(apiClient).create(4711L, payload) { true } shouldBe null
    }
  }
})
