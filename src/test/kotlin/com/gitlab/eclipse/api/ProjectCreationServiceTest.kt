package com.gitlab.eclipse.api

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant

class ProjectCreationServiceTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun client(): GitLabApiClient = mockk<GitLabApiClient>().also {
    every { it.captureConnectionIf(any()) } returns mockk(relaxed = true)
  }

  fun rejectingClient(): GitLabApiClient = mockk<GitLabApiClient>().also {
    every { it.captureConnectionIf(any()) } returns null
  }

  describe("resolveNamespaceId") {
    it("reads the id from GET /namespaces/{path}") {
      val api = client()
      every { api.fetchText(any(), any()) } returns """{"id": 7}"""

      ProjectCreationService(api).resolveNamespaceId("my/group") { true } shouldBe 7L

      // The whole namespace path is ONE segment: a bare slash would address a different resource.
      verify { api.fetchText("/namespaces/my%2Fgroup", any()) }
    }

    it("returns null without a request when the gate rejects the instance") {
      val api = rejectingClient()

      ProjectCreationService(api).resolveNamespaceId("g") { true } shouldBe null

      verify(exactly = 0) { api.fetchText(any(), any()) }
    }
  }

  describe("createProject") {
    it("posts path, namespace_id and visibility and reads both clone urls") {
      val api = client()
      val body = slot<String>()
      val response = """
        {"id": 9, "ssh_url_to_repo": "git@h:g/p.git",
         "http_url_to_repo": "https://h/g/p.git", "web_url": "https://h/g/p"}
      """.trimIndent()
      every { api.postJson("/projects", capture(body), any()) } returns response

      val created = ProjectCreationService(api).createProject("p", 7, "private") { true }!!

      created.id shouldBe 9L
      created.sshUrl shouldBe "git@h:g/p.git"
      created.httpUrl shouldBe "https://h/g/p.git"
      created.webUrl shouldBe "https://h/g/p"
      body.captured shouldContain "\"namespace_id\":7"
      body.captured shouldContain "\"visibility\":\"private\""
      body.captured shouldContain "\"path\":\"p\""
    }

    it("omits namespace_id for the personal namespace") {
      val api = client()
      val body = slot<String>()
      every { api.postJson(any(), capture(body), any()) } returns
        """{"id": 1, "ssh_url_to_repo": "s", "http_url_to_repo": "h", "web_url": "w"}"""

      ProjectCreationService(api).createProject("p", null, "private") { true }

      body.captured shouldNotContain "namespace_id"
    }

    it("returns null without a request when the gate rejects the instance") {
      val api = rejectingClient()

      ProjectCreationService(api).createProject("p", null, "private") { true } shouldBe null

      verify(exactly = 0) { api.postJson(any(), any(), any()) }
    }
  }

  describe("findProject") {
    it("reads the creator and the creation time the recovery path needs") {
      val api = client()
      val response = """
        {"id": 3, "creator_id": 11, "created_at": "2026-08-14T09:30:00.000Z",
         "web_url": "https://h/g/p", "http_url_to_repo": "https://h/g/p.git"}
      """.trimIndent()
      every { api.fetchText(any(), any()) } returns response

      val found = ProjectCreationService(api).findProject("g/p") { true }!!

      found.id shouldBe 3L
      found.creatorId shouldBe 11L
      found.createdAt shouldBe Instant.parse("2026-08-14T09:30:00Z")
      found.webUrl shouldBe "https://h/g/p"
      found.httpUrl shouldBe "https://h/g/p.git"
    }

    it("reports a missing project as null rather than throwing") {
      val api = client()
      every { api.fetchText(any(), any()) } throws GitLabApiException(404, "", null)

      ProjectCreationService(api).findProject("g/p") { true } shouldBe null
    }

    it("still returns the project when the creation time is unparseable") {
      val api = client()
      every { api.fetchText(any(), any()) } returns
        """{"id": 3, "creator_id": 11, "created_at": "nonsense", "web_url": "w"}"""

      val found = ProjectCreationService(api).findProject("g/p") { true }!!

      found.createdAt shouldBe null
      found.creatorId shouldBe 11L
    }
  }
})
