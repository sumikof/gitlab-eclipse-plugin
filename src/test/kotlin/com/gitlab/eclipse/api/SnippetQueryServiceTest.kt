package com.gitlab.eclipse.api

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class SnippetQueryServiceTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val connection = ConnectionSnapshot("https://gitlab.example.com", "tok-123", "fp-1", 1L)

  fun blob(name: String, path: String) = SnippetBlobDto(name = name, path = path, rawPath = "/raw$path")

  describe("listSnippets") {
    it("maps the first page and reports whether more pages exist") {
      val apiClient = mockk<GitLabApiClient>()
      val graphQl = mockk<GitLabGraphQlClient>()
      every { apiClient.captureConnectionIf(any()) } returns connection
      val variables = slot<Map<String, Any?>>()
      every {
        graphQl.execute(any(), capture(variables), SnippetsQueryData::class.java, connection, any())
      } returns SnippetsQueryData(
        project = SnippetsProjectDto(
          snippets = SnippetConnectionDto(
            pageInfo = SnippetPageInfoDto(hasNextPage = true),
            nodes = listOf(
              SnippetSummaryDto(
                id = "gid://gitlab/ProjectSnippet/7",
                title = "Patch",
                description = "d",
                blobs = SnippetBlobConnectionDto(listOf(blob("a.kt", "/a.kt"), blob("b.kt", "/b.kt"))),
              ),
              null,
            ),
          ),
        ),
      )

      val page = SnippetQueryService(apiClient, graphQl).listSnippets("group/proj") { true }

      page!!.hasNextPage shouldBe true
      page.snippets.size shouldBe 1
      page.snippets[0].id shouldBe "gid://gitlab/ProjectSnippet/7"
      page.snippets[0].title shouldBe "Patch"
      page.snippets[0].blobs.map { it.name } shouldBe listOf("a.kt", "b.kt")
      variables.captured["namespaceWithPath"] shouldBe "group/proj"
    }

    it("returns null without querying when the connection gate rejects the instance url") {
      val apiClient = mockk<GitLabApiClient>()
      val graphQl = mockk<GitLabGraphQlClient>()
      every { apiClient.captureConnectionIf(any()) } returns null

      SnippetQueryService(apiClient, graphQl).listSnippets("group/proj") { false } shouldBe null

      verify(exactly = 0) { graphQl.execute<Any>(any(), any(), any(), any(), any()) }
    }

    it("treats a missing project as an empty page") {
      val apiClient = mockk<GitLabApiClient>()
      val graphQl = mockk<GitLabGraphQlClient>()
      every { apiClient.captureConnectionIf(any()) } returns connection
      every {
        graphQl.execute(any(), any(), SnippetsQueryData::class.java, connection, any())
      } returns SnippetsQueryData(project = null)

      val page = SnippetQueryService(apiClient, graphQl).listSnippets("group/proj") { true }

      page!!.snippets shouldBe emptyList()
      page.hasNextPage shouldBe false
    }
  }

  describe("fetchBlobContent") {
    it("returns the raw text of the blob whose path matches") {
      val apiClient = mockk<GitLabApiClient>()
      val graphQl = mockk<GitLabGraphQlClient>()
      every { apiClient.captureConnectionIf(any()) } returns connection
      val variables = slot<Map<String, Any?>>()
      every {
        graphQl.execute(any(), capture(variables), SnippetContentQueryData::class.java, connection, any())
      } returns SnippetContentQueryData(
        snippets = SnippetContentConnectionDto(
          nodes = listOf(
            SnippetContentDto(
              blobs = SnippetContentBlobConnectionDto(
                listOf(
                  SnippetContentBlobDto(path = "/a.kt", rawPlainData = "AAA"),
                  SnippetContentBlobDto(path = "/b.kt", rawPlainData = "BBB"),
                ),
              ),
            ),
          ),
        ),
      )

      val content =
        SnippetQueryService(apiClient, graphQl).fetchBlobContent("gid://x", "/b.kt") { true }

      content shouldBe "BBB"
      variables.captured["snippetId"] shouldBe "gid://x"
    }

    it("returns null when no blob has that path") {
      val apiClient = mockk<GitLabApiClient>()
      val graphQl = mockk<GitLabGraphQlClient>()
      every { apiClient.captureConnectionIf(any()) } returns connection
      every {
        graphQl.execute(any(), any(), SnippetContentQueryData::class.java, connection, any())
      } returns SnippetContentQueryData(snippets = null)

      SnippetQueryService(apiClient, graphQl).fetchBlobContent("gid://x", "/missing") { true } shouldBe null
    }
  }
})
