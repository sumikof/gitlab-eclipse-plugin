package com.gitlab.eclipse.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException

class JobTraceServiceTest : DescribeSpec({
  val apiClient = mockk<GitLabApiClient>()
  val service = JobTraceService(apiClient)

  val connection = ConnectionSnapshot(
    instanceUrl = "https://x.example/",
    token = "tok",
    authFingerprint = "fp",
    configGeneration = 1L,
  )

  beforeEach { clearMocks(apiClient) }

  describe("getTrace") {
    it("builds the correct path and passes the connection through") {
      every { apiClient.fetchText(any(), any()) } returns "raw log"

      service.getTrace(projectId = 42L, jobId = 9L, connection = connection)

      verify(exactly = 1) { apiClient.fetchText("/projects/42/jobs/9/trace", connection) }
    }

    it("returns the client's body verbatim") {
      every { apiClient.fetchText(any(), any()) } returns "raw log"

      val result = service.getTrace(projectId = 42L, jobId = 9L, connection = connection)

      result shouldBe "raw log"
    }

    it("propagates exceptions from the API client") {
      every { apiClient.fetchText(any(), any()) } throws GitLabApiException(404, "not found", "corr-1")

      shouldThrow<GitLabApiException> { service.getTrace(1L, 2L, connection) }
    }
  }

  describe("getTraceSnapshot") {
    it("builds the trace path and forwards etag, cap and connection") {
      every { apiClient.fetchTrace(any(), any(), any(), any()) } returns TraceFetch.NotModified

      val result = service.getTraceSnapshot(42L, 9L, "W/\"abc\"", connection, maxBytes = 100)

      result shouldBeSameInstanceAs TraceFetch.NotModified
      verify(exactly = 1) { apiClient.fetchTrace("/projects/42/jobs/9/trace", "W/\"abc\"", 100, connection) }
    }

    it("defaults to the display limit and no etag") {
      every { apiClient.fetchTrace(any(), any(), any(), any()) } returns TraceFetch.NotModified

      service.getTraceSnapshot(1L, 2L, null, connection)

      verify { apiClient.fetchTrace("/projects/1/jobs/2/trace", null, 16 * 1024 * 1024, connection) }
    }
  }

  describe("downloadRawTrace") {
    val dir = tempdir()

    it("streams the body into the target file") {
      every { apiClient.downloadTo(any(), any(), any(), any()) } answers {
        arg<(InputStream) -> Unit>(3).invoke(ByteArrayInputStream("raw \u65E5 log".toByteArray()))
      }
      val target = dir.toPath().resolve("job.log")

      service.downloadRawTrace(42L, 9L, connection, target)

      Files.readAllBytes(target).decodeToString() shouldBe "raw \u65E5 log"
      verify { apiClient.downloadTo("/projects/42/jobs/9/trace", connection, any(), any()) }
    }

    it("leaves no partial file when the download fails") {
      every { apiClient.downloadTo(any(), any(), any(), any()) } throws IOException("boom")
      val target = dir.toPath().resolve("failed.log")

      shouldThrow<IOException> { service.downloadRawTrace(1L, 2L, connection, target) }

      Files.exists(target) shouldBe false
      Files.list(dir.toPath()).use { it.filter { p -> p.fileName.toString().endsWith(".part") }.count() } shouldBe 0L
    }

    it("stops a cancelled download and leaves neither the target nor a partial file") {
      val own = tempdir().toPath()
      every { apiClient.downloadTo(any(), any(), any(), any()) } answers {
        arg<(InputStream) -> Unit>(3).invoke(ByteArrayInputStream(ByteArray(1_000_000)))
      }
      val target = own.resolve("cancelled.log")

      shouldThrow<CancellationException> { service.downloadRawTrace(1L, 2L, connection, target) { true } }

      Files.list(own).use { it.count() } shouldBe 0L
    }

    it("hands the client's close hook to the caller") {
      val own = tempdir().toPath()
      var closedByHook = false
      every { apiClient.downloadTo(any(), any(), any(), any()) } answers {
        arg<(() -> Unit) -> Unit>(2).invoke { closedByHook = true }
        arg<(InputStream) -> Unit>(3).invoke(ByteArrayInputStream("x".toByteArray()))
      }
      var hook: (() -> Unit)? = null

      service.downloadRawTrace(1L, 2L, connection, own.resolve("hook.log"), onOpened = { hook = it })
      hook?.invoke()

      closedByHook shouldBe true
    }
  }
})
