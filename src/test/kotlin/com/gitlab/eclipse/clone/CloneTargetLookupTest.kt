package com.gitlab.eclipse.clone

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.model.GitLabProject
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

private const val NOT_FOUND = 404
private const val SERVER_ERROR = 500

private fun snapshot(url: String = "https://gitlab.example.com") =
  ConnectionSnapshot(url, "t", "fp", 2L)

class CloneTargetLookupTest : StringSpec({
  "returns the clone url and the pinned instance url" {
    val api = mockk<GitLabApiClient>()
    every { api.captureConnectionIf(any()) } returns snapshot()
    every { api.fetchObject<GitLabProject>(any(), any(), any(), any()) } returns
      GitLabProject(id = 7, defaultBranch = "main", httpUrlToRepo = "https://gitlab.example.com/g/p.git")

    CloneTargetLookup(api).lookup("g/p") { true } shouldBe
      CloneTargetLookup.Result.Ok("https://gitlab.example.com/g/p.git", "https://gitlab.example.com")
  }

  "encodes the whole namespace path as one segment" {
    val api = mockk<GitLabApiClient>()
    every { api.captureConnectionIf(any()) } returns snapshot()
    every { api.fetchObject<GitLabProject>("/projects/g%2Fsub%2Fp", any(), any(), any()) } returns
      GitLabProject(id = 7, httpUrlToRepo = "https://gitlab.example.com/g/sub/p.git")

    CloneTargetLookup(api).lookup("g/sub/p") { true } shouldBe
      CloneTargetLookup.Result.Ok("https://gitlab.example.com/g/sub/p.git", "https://gitlab.example.com")
  }

  "reports a rejected connection gate without calling the api" {
    val api = mockk<GitLabApiClient>()
    every { api.captureConnectionIf(any()) } returns null

    CloneTargetLookup(api).lookup("g/p") { false } shouldBe CloneTargetLookup.Result.NotConnected
  }

  "maps 404 to NotFound" {
    val api = mockk<GitLabApiClient>()
    every { api.captureConnectionIf(any()) } returns snapshot()
    every { api.fetchObject<GitLabProject>(any(), any(), any(), any()) } throws
      GitLabApiException(NOT_FOUND, "not found")

    CloneTargetLookup(api).lookup("g/p") { true } shouldBe CloneTargetLookup.Result.NotFound
  }

  "maps any other api failure to Failed with the type name only" {
    val api = mockk<GitLabApiClient>()
    every { api.captureConnectionIf(any()) } returns snapshot()
    every { api.fetchObject<GitLabProject>(any(), any(), any(), any()) } throws
      GitLabApiException(SERVER_ERROR, "boom")

    CloneTargetLookup(api).lookup("g/p") { true } shouldBe
      CloneTargetLookup.Result.Failed("com.gitlab.eclipse.api.GitLabApiException")
  }

  "reports a project that has no http clone url" {
    val api = mockk<GitLabApiClient>()
    every { api.captureConnectionIf(any()) } returns snapshot()
    every { api.fetchObject<GitLabProject>(any(), any(), any(), any()) } returns GitLabProject(id = 7)

    CloneTargetLookup(api).lookup("g/p") { true } shouldBe CloneTargetLookup.Result.NoCloneUrl
  }
})
