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
  }
})
