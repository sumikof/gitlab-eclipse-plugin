package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabJob
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

/** [JobActionService.retryReturningJob]: the retry POST whose response names the new job (L4). */
class JobActionServiceRetryReturningTest : StringSpec({
  val connection = ConnectionSnapshot(
    instanceUrl = "https://pinned.example.com/",
    token = "pinned-token",
    authFingerprint = "0123456789abcdef",
    configGeneration = 42L,
  )

  "POSTs the retry path pinned to the same connection and returns the new job" {
    val posted = PostedObject(PostResult(201, "cid"), GitLabJob(id = 77))
    val apiClient = mockk<GitLabApiClient> {
      every { postReturning("/projects/42/jobs/9/retry", GitLabJob::class.java, connection) } returns posted
    }

    JobActionService(apiClient).retryReturningJob(connection, 42L, 9L) shouldBeSameInstanceAs posted

    verify(exactly = 1) { apiClient.postReturning("/projects/42/jobs/9/retry", GitLabJob::class.java, connection) }
    verify(exactly = 0) { apiClient.post(any(), any(), any()) }
  }

  "keeps the plain retry on post" {
    val apiClient = mockk<GitLabApiClient> {
      every { post("/projects/42/jobs/9/retry", connection = connection) } returns PostResult(201, null)
    }

    JobActionService(apiClient).retry(connection, 42L, 9L) shouldBe PostResult(201, null)
  }
})
