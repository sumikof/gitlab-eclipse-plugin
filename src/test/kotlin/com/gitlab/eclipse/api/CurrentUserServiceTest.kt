package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabUser
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class CurrentUserServiceTest : DescribeSpec({
  val apiClient = mockk<GitLabApiClient>()
  val service = CurrentUserService(apiClient)

  describe("getCurrentUser") {
    it("fetches the authenticated user from /user") {
      val user = GitLabUser(1, "octocat")
      every { apiClient.fetchObject("/user", type = GitLabUser::class.java) } returns user

      val result = service.getCurrentUser()

      result shouldBe user
    }

    it("fetches /user through the given pinned connection") {
      val user = GitLabUser(2, "pinned")
      val snapshot = ConnectionSnapshot("https://pinned.example.com", "t", "fp", 2L)
      every { apiClient.fetchObject("/user", type = GitLabUser::class.java, connection = snapshot) } returns user

      service.getCurrentUser(snapshot) shouldBe user
    }
  }
})
