package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabBridge
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Duration

class BridgeServiceTest : DescribeSpec({
  val apiClient = mockk<GitLabApiClient>()
  val service = BridgeService(apiClient)

  beforeEach { clearMocks(apiClient) }

  describe("getBridgesForPipeline") {
    it("fetches the bridges path with the 15 s deadline and no query") {
      val bridges = listOf(GitLabBridge(id = 1, name = "trigger"))
      val request = slot<ApiRequest<GitLabBridge>>()
      val deadline = slot<Duration>()
      every {
        apiClient.fetchListWithinDeadline(capture(request), capture(deadline), any(), any())
      } returns bridges

      service.getBridgesForPipeline("group%2Fproj", 99L) shouldBe bridges

      request.captured.path shouldBe "/projects/group%2Fproj/pipelines/99/bridges"
      request.captured.query shouldBe emptyMap()
      request.captured.elementType shouldBe GitLabBridge::class.java
      deadline.captured shouldBe Duration.ofSeconds(15)
    }

    it("threads isActive and pins the given connection") {
      val connection = ConnectionSnapshot(
        instanceUrl = "https://pinned.example.com/",
        token = "pinned-token",
        authFingerprint = "0123456789abcdef",
        configGeneration = 42L,
      )
      val isActive = slot<() -> Boolean>()
      val conn = slot<ConnectionSnapshot>()
      every {
        apiClient.fetchListWithinDeadline(any<ApiRequest<GitLabBridge>>(), any(), any(), capture(isActive), capture(conn))
      } returns emptyList()

      var active = true
      service.getBridgesForPipeline("42", 99L, isActive = { active }, connection = connection)

      isActive.captured() shouldBe true
      active = false
      isActive.captured() shouldBe false
      conn.captured shouldBe connection
    }

    it("passes a null connection when none is given") {
      val conn = slot<ConnectionSnapshot?>()
      every {
        apiClient.fetchListWithinDeadline(any<ApiRequest<GitLabBridge>>(), any(), any(), any(), captureNullable(conn))
      } returns emptyList()

      service.getBridgesForPipeline("42", 99L)

      conn.captured shouldBe null
    }

    it("propagates GitLabApiException 403 and 404 unchanged") {
      for (status in listOf(403, 404)) {
        val ex = GitLabApiException(status, "")
        every {
          apiClient.fetchListWithinDeadline(any<ApiRequest<GitLabBridge>>(), any(), any(), any(), any())
        } throws ex
        shouldThrow<GitLabApiException> { service.getBridgesForPipeline("42", 99L) } shouldBe ex
      }
    }
  }
})
